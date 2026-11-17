"""Real transport pilot. Set TYPESAFE_API_KEY only in the runtime environment."""
from pathlib import Path
import json, os, subprocess, sys, time
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent/(__import__('os').environ.get('UNIFYMS_PILOT_DIR','pilot-real-v2'))
CONFIG=OUT/'run-config.json'
cp=str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/tuning-classpath.txt').read_text().strip()
java=['java','-cp',cp]
def run(name,command):
    existing = {'snapshot': OUT/'model-snapshot.json', 'calibrate': OUT/'experiment/CoCoME/calibration.json', 'select': OUT/'experiment/CoCoME/selection-jev.json'}
    if name in existing and existing[name].exists():
        print(name,'reuse existing artifact',flush=True);return
    if name.startswith('report-') and (OUT/name/'pipeline-status.json').exists():
        status=json.loads((OUT/name/'pipeline-status.json').read_text())
        if status.get('status')=='success':
            print(name,'reuse existing report',flush=True);return
    start=time.monotonic();log=OUT/(name+'.log')
    if log.exists():log=OUT/(name+'-resume-'+str(time.time_ns())+'.log')
    with log.open('w') as f:subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,check=True)
    print(name,round(time.monotonic()-start,3),'s',flush=True)
run('snapshot',java+['tuning.MermSnapshot',str(ROOT/'cocome.remodel'),str(OUT/'model-snapshot.json')])
for command in ('calibrate','select'):
    run(command,java+['tuning.TuningCli',command,str(CONFIG),'--system','CoCoME'])
for mode in ('off','rule','jev'):
    run('run-'+mode,java+['tuning.TuningCli','run',str(CONFIG),'--system','CoCoME','--seed','101','--control',mode,'--monitor','true'])
base=OUT/'experiment/CoCoME'
actual=json.loads((base/'online-jev/run-jev-101.json').read_text())
audit=Path(actual['run']['onlineControl']['auditDirectory'])
run('run-replay',java+['tuning.TuningCli','run',str(CONFIG),'--system','CoCoME','--seed','101','--control','replay','--replay-file',str(audit/'control-decisions.jsonl')])
replayed=json.loads((base/'online-replay/run-jev-101.json').read_text())
assert actual['run']['population']==replayed['run']['population'] and actual['hv']==replayed['hv']
expected=(actual['run']['completedGenerations']-1)//30
assert actual['run']['onlineControl']['decisionCount']==expected
for mode in ('off','rule','jev','replay'):
    run('report-'+mode,[sys.executable,str(ROOT/'scripts/run-case.py'),'report','--input',str(ROOT/'cocome.remodel'),'--case','CoCoME','--output',str(OUT/('report-'+mode)),'--selector','jev','--profile','smoke','--experiment',str(OUT/'experiment'),'--snapshot',str(OUT/'model-snapshot.json'),'--control',mode,'--monitor-control','--test-seeds','101','--skip-build'])
run('compare-online',java+['tuning.TuningCli','compare-online',str(CONFIG),'--system','CoCoME'])
# Verify the pipeline can reuse the same audited trial without another offline or online request.
run('oneclick-reuse',[sys.executable,str(ROOT/'scripts/run-case.py'),'run','--input',str(ROOT/'cocome.remodel'),'--case','CoCoME','--output',str(OUT/'oneclick-reuse'),'--selector','jev','--reuse-run-config',str(CONFIG),'--control','rule','--test-seeds','101','--skip-build'])
rows=[]
for mode in ('off','rule','jev','replay'):
    r=json.loads((base/('online-'+mode)/'run-jev-101.json').read_text())
    rows.append(dict(mode=mode,hv=r['hv'],metrics=r['representative']['metrics'],evaluations=r['run']['evaluations'],control=r['run']['onlineControl']))
summary=dict(status='success' if actual['run']['onlineControl']['fallbackCount']==0 else 'transport-degraded',profile='smoke',isQualityEvidence=False,replayMatches=True,rows=rows)
(OUT/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print(json.dumps(dict(status=summary['status'],expectedDecisions=expected,requests=actual['run']['onlineControl']['requestCount'],fallbacks=actual['run']['onlineControl']['fallbackCount'],replayMatches=True),ensure_ascii=False),flush=True)
