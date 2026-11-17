#!/usr/bin/env python3
"""Repeat formal static/JEV searches using compatible frozen offline selection evidence."""
import argparse
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', default='CoCoME')
    parser.add_argument('--design', choices=['selected-pair','abc'], default='selected-pair')
    parser.add_argument('--control-config', type=Path, help='Explicit online-policy override and compatible-source proof')
    parser.add_argument('--test-seeds', default='101')
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Choose a new/empty output directory; prior runs are preserved')
    source = args.source_config.resolve()
    cfg = json.loads(source.read_text())
    systems = [s for s in cfg['systems'] if s['id'] == args.case]
    if len(systems) != 1:
        parser.error('Case is not unique in source config')
    try:
        seeds = [int(s) for s in args.test_seeds.split(',')]
        if not seeds or len(set(seeds)) != len(seeds) or set(seeds) & set(cfg['calibrationSeeds']):
            raise ValueError()
    except ValueError:
        parser.error('Unique integer test seeds, disjoint from calibration, are required')
    env = os.environ.copy()
    if not env.get('TYPESAFE_API_KEY'):
        if not sys.stdin.isatty():
            parser.error('Provide TYPESAFE_API_KEY in the runtime environment')
        env['TYPESAFE_API_KEY'] = getpass.getpass('TYPESAFE_API_KEY (hidden, not saved): ')
    if not env['TYPESAFE_API_KEY'].strip():
        parser.error('Empty API key')
    output.mkdir(parents=True, exist_ok=True)
    (output/'logs').mkdir()
    def run(name, command):
        print('['+name+']', flush=True)
        with (output/'logs'/(name+'.log')).open('w') as log:
            subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    run('build', ['mvn','-o','-q','-DskipTests','compile',
        'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath',
        '-DincludeScope=runtime','-Dmdep.outputFile=target/tuning-classpath.txt'])
    cp = str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/tuning-classpath.txt').read_text().strip()
    classes = output/'tool-classes'
    classes.mkdir()
    run('compile-runner', ['javac','-cp',cp,'-d',str(classes),str(ROOT/'scripts/RepeatOnlineComparison.java')])
    run('formal-comparison', ['java','-cp',str(classes)+os.pathsep+cp,'RepeatOnlineComparison',
        str(source),str(output),args.case,','.join(map(str,seeds)),args.design]+([str(args.control_config.resolve())] if args.control_config else []))
    if args.design=='abc':
        from abc_comparison_report import generate
    else:
        from online_comparison_report import generate
    report = generate(output, args.case, (source.parent/systems[0]['input']).resolve(), seeds)
    print('REPORT '+str(report), flush=True)


if __name__ == '__main__':
    main()
