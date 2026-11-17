"""Resume this completed calibration after DNS failure; API key remains runtime-only."""
import getpass
import hashlib
import json
import os
import shutil
import subprocess
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=Path(__file__).resolve().parent
CASE=OUT/'experiment/JavaOnlineTaxi'
CONFIG=OUT/'run-config.json'
calibration=CASE/'calibration.json'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source_hash=sha(calibration)
failed=CASE/'selection-jev-network-failed.json'
if not failed.exists():shutil.copyfile(CASE/'selection-jev.json',failed)
env=os.environ.copy()
if not env.get('TYPESAFE_API_KEY'):env['TYPESAFE_API_KEY']=getpass.getpass('TYPESAFE_API_KEY (hidden, not saved): ')
cp=str(ROOT/'target/classes')+os.pathsep+(ROOT/'target/tuning-classpath.txt').read_text().strip()
cli=['java','-cp',cp,'tuning.TuningCli']
record=dict(reason='Previous DNS failure; original calibration preserved',sourceCalibrationSha256=source_hash,stages=[],status='running')
def save():
    (OUT/'recovery-status.json').write_text(json.dumps(record,indent=2)+'\n')
try:
    commands=[('select-network-retry',cli+['select',str(CONFIG),'--system','JavaOnlineTaxi']),
              ('run-101-recovery',cli+['run',str(CONFIG),'--system','JavaOnlineTaxi','--seed','101']),
              ('report-recovery',['python3','scripts/run-case.py','report','--input',str(ROOT/'java-online-taxi'),
               '--case','JavaOnlineTaxi','--experiment',str(OUT/'experiment'),'--snapshot',str(OUT/'model-snapshot.json'),
               '--test-seeds','101','--output',str(ROOT/'output/java-online-taxi-jev-report-20261101')])]
    for name,command in commands:
        print('['+name+']',flush=True)
        row=dict(stage=name,status='running');record['stages'].append(row);save();start=time.monotonic()
        log=OUT/'logs'/(name+'.log')
        with log.open('w') as stream:
            result=subprocess.run(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
        row.update(status='success' if result.returncode==0 else 'failed',seconds=time.monotonic()-start,log=str(log));save()
        if result.returncode:raise RuntimeError(name+' failed; see '+str(log))
    if sha(calibration)!=source_hash:raise RuntimeError('Calibration changed during recovery')
    record['status']='success'
except Exception as error:
    record.update(status='failed',error=str(error));raise
finally:save()
