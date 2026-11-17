#!/usr/bin/env python3
"""Calibrate/select once, run selected arms for independent seeds, then export all reports."""
import argparse
import concurrent.futures
import datetime
import getpass
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
SEEDS = [101,103,107,109,113,127,131,137,139,149]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, default=ROOT/'config/jev-llm-guidance.json')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--arms', default='off,numeric,jev', help='Comma-separated guidance arms; use jev for only LLM generation plus JEV selection')
    p.add_argument('--workers', type=int, default=4)
    p.add_argument('--test-seeds', default=','.join(map(str,SEEDS)))
    p.add_argument('--resume', action='store_true')
    p.add_argument('--skip-build', action='store_true')
    args = p.parse_args()
    if not 1 <= args.workers <= 4:
        p.error('--workers must be 1..4')
    arms = args.arms.split(',')
    if not arms or len(set(arms)) != len(arms) or any(a not in ('off','numeric','jev') for a in arms):
        p.error('--arms must contain distinct off,numeric,jev values')
    seeds = [int(s) for s in args.test_seeds.split(',')]
    if not seeds or len(set(seeds)) != len(seeds):
        p.error('Seeds must be nonempty and unique')
    cfg = json.loads(args.config.read_text())
    if len(cfg['systems']) != 1:
        p.error('Use one input Case per study')
    cfg['systems'][0]['input'] = str((args.config.resolve().parent/cfg['systems'][0]['input']).resolve())
    cfg['validationSeeds'] = seeds
    if set(seeds) & set(cfg['calibrationSeeds']):
        p.error('Formal seeds overlap calibration')
    output = args.output.resolve()
    cfg['outputDirectory'] = str(output/'experiment')
    cfg['selector'] = 'jev'
    cfg['onlineControl']['mode'] = 'jev'
    cfg['guidance']['mode'] = 'jev'
    config_path = output/'run-config.json'
    if output.exists() and any(output.iterdir()):
        if not args.resume or not config_path.exists() or json.loads(config_path.read_text()) != cfg:
            p.error('Choose a fresh output directory, or --resume with exactly the original config')
    env = os.environ.copy()
    for name in [cfg['guidance']['generator']['apiKeyEnv'], 'TYPESAFE_API_KEY']:
        if not env.get(name):
            if not os.isatty(0):
                p.error('Set '+name+' in the runtime environment')
            env[name] = getpass.getpass(name+' (hidden, runtime only): ')
        if not env[name].strip():
            p.error('Empty runtime credential')
    output.mkdir(parents=True, exist_ok=True)
    (output/'logs').mkdir(exist_ok=True)
    config_path.write_text(json.dumps(cfg,ensure_ascii=False,indent=2)+'\n')
    previous = output/'study-status.json'
    if previous.exists():
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        (output/('study-status-before-'+stamp+'.json')).write_bytes(previous.read_bytes())
    status = dict(status='running', started=datetime.datetime.now().astimezone().isoformat(),
                  seeds=seeds, arms=arms, lossTolerance=cfg['guidance']['lossTolerance'],
                  workers=args.workers, stages=[], runs=[])
    lock = threading.Lock()

    def publish():
        path=output/'study-status.json'
        temporary=output/'study-status.json.partial'
        temporary.write_text(json.dumps(status,ensure_ascii=False,indent=2)+'\n')
        temporary.replace(path)

    def command(name, cmd):
        log=output/'logs'/(name+'.log')
        start=time.monotonic()
        print('START',name,flush=True)
        with log.open('a') as stream:
            done=subprocess.run(cmd,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
        row=dict(name=name,status='success' if done.returncode==0 else 'failed',seconds=time.monotonic()-start,log=str(log))
        with lock:
            status['stages'].append(row)
            publish()
        if done.returncode:
            raise RuntimeError(name+' failed: '+str(log))
        print('DONE',name,flush=True)

    try:
        publish()
        if not args.skip_build:
            command('build',['mvn','-o','-q','-DskipTests','compile',
                'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath',
                '-DincludeScope=runtime','-Dmdep.outputFile=target/tuning-classpath.txt'])
        cp=str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/tuning-classpath.txt').read_text().strip()
        java=['java','-cp',cp]
        case=cfg['systems'][0]['id']
        source=cfg['systems'][0]['input']
        snapshot=output/'model-snapshot.json'
        if not snapshot.exists():
            command('snapshot',java+['tuning.MermSnapshot',source,str(snapshot)])
        cli=java+['tuning.TuningCli']
        command('calibrate',cli+['calibrate',str(config_path),'--system',case])
        command('select',cli+['select',str(config_path),'--system',case])

        def run_seed(seed):
            # Each seed has its own search RNG, state and model requests. No batch sharing across states.
            for arm in arms:
                command('run-'+str(seed)+'-'+arm,cli+['run',str(config_path),'--system',case,'--seed',str(seed),'--control','jev','--guidance',arm])
                directory=output/'experiment'/case/'online-jev'
                if arm!='off': directory=directory/('guidance-'+arm)
                result_path=directory/('run-jev-'+str(seed)+'.json')
                result=json.loads(result_path.read_text())
                run=result['run']
                if run['completedGenerations']!=run['config']['generations'] or run['config']['seed']!=seed:
                    raise RuntimeError('Incomplete or mismatched formal run')
                record=dict(seed=seed,arm=arm,result=str(result_path),hv=result['hv'],metrics=result['representative']['metrics'],
                            evaluations=run['evaluations'],completedGenerations=run['completedGenerations'])
                with lock:
                    status['runs'].append(record)
                    publish()
                print('RESULT',seed,arm,result['hv'],flush=True)

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            tasks=[pool.submit(run_seed,seed) for seed in seeds]
            for task in concurrent.futures.as_completed(tasks):
                task.result()
        if len(status['runs'])!=len(arms)*len(seeds):
            raise RuntimeError('Missing paired runs; refusing to publish a full-sample mean')
        report=output/'report'
        if len(arms)>1 and set(arms)!={'off','numeric','jev'}:
            raise RuntimeError('Reporting supports a single arm or all three arms')
        if not report.exists():
            command('report',['python3',str(ROOT/'scripts/run-case.py'),'report','--input',source,'--case',case,
                '--experiment',str(output/'experiment'),'--snapshot',str(snapshot),
                '--test-seeds',','.join(map(str,seeds)),'--control','jev','--guidance',('jev' if 'jev' in arms else arms[-1]),
                *(['--compare-guidance'] if set(arms)=={'off','numeric','jev'} else []),'--output',str(report)])
        elif not (report/'analysis.md').exists():
            raise RuntimeError('Incomplete existing report; choose a fresh report destination')
        if arms==['jev'] and len(seeds)==10:
            command('ten-run-summary',['python3',str(ROOT/'scripts/summarize-guidance-single.py'),str(output)])
        with lock:
            status.update(status='success',report=str(report/'analysis.md'),finished=datetime.datetime.now().astimezone().isoformat())
            publish()
        print('REPORT',report/'analysis.md',flush=True)
    except BaseException as error:
        with lock:
            status.update(status='interrupted' if isinstance(error,KeyboardInterrupt) else 'failed',error=type(error).__name__+': '+str(error))
            publish()
        raise


if __name__=='__main__':
    main()
