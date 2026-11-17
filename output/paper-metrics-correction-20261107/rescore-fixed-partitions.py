"""Rescore archived partitions, independently verify the paper equations; no GA/JEV/HV."""
from pathlib import Path
import hashlib
import itertools
import json
import math
import subprocess

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[1]
read=lambda p:json.loads(Path(p).read_text())
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
source=ROOT/'output/cocome-abc-v2-20261107-seed107/experiment/CoCoME'
snapshot=OUT/'input-audits/cocome-snapshot.json'
snap=read(snapshot)
old=[read(source/f'group-{a}/run-{a}-107.json') for a in ['A','B','C']]
job=dict(input=str(ROOT/'cocome.remodel'),inputHash=snap['inputHash'],metricVersion='paper-ieee-eq5-21-v1',chromosomes=[r['representative']['chromosome'] for r in old])
job['taskKey']=hashlib.sha256(json.dumps(job,sort_keys=True).encode()).hexdigest()
(OUT/'fixed-partitions-request.json').write_text(json.dumps(job,indent=2))
cp=str(ROOT/'target/classes')+':'+(ROOT/'target/tuning-classpath.txt').read_text().strip()
with (OUT/'fixed-partitions-worker.log').open('w') as log:
    subprocess.run(['java','-cp',cp,'tuning.Worker',str(OUT/'fixed-partitions-request.json'),str(OUT/'fixed-partitions-result.json')],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
new=read(OUT/'fixed-partitions-result.json');assert new['metricVersion']=='paper-ieee-eq5-21-v1'


def jaccard(a,b):
    return len(a&b)/len(a|b) if a|b else 0


def independently_score(genes):
    u=len(snap['useCaseNames']);e=len(snap['entityNames']);services=set(genes[:u]);ue=snap['useCaseEntityMatrix'];ee=snap['entityRelationMatrix']
    required={s:set() for s in services};provided={s:set() for s in services}
    for a,b in itertools.product(range(e),repeat=2):
        if ee[a][b] and genes[u+a]!=genes[u+b]:required[genes[u+a]].add(b)
    for a,b in itertools.product(range(u),range(e)):
        if ue[a][b] and genes[a]!=genes[u+b]:required[genes[a]].add(b)
    for needs in required.values():
        for entity in needs:provided[genes[u+entity]].add(entity)
    ifn=sum(len(required[s])+len(provided[s]) for s in services)/len(services)
    instability=sum(len(provided[s])/(len(required[s])+len(provided[s])) if required[s]|provided[s] else 1 for s in services)/len(services)
    cohesions=[]
    for s in services:
        ops=set().union(*(set(snap['useCaseOperations'][snap['useCaseNames'][str(i)]]) for i in range(u) if genes[i]==s))
        total=sum(jaccard(set(snap['operationEntities'].get(a,[])),set(snap['operationEntities'].get(b,[]))) for a,b in itertools.permutations(ops,2))
        cohesions.append(total/len(ops)**2 if ops else 0)
    chd=sum(cohesions)/len(services)
    intra=[];inter=[]
    for a,b in itertools.permutations(range(e),2):
        attrs=snap['metricMetadata']['entityAttributeNames']
        value=jaccard(set(attrs[snap['entityNames'][str(a)]]),set(attrs[snap['entityNames'][str(b)]]))
        (intra if genes[u+a]==genes[u+b] else inter).append(value)
    mean=lambda values:sum(values)/len(values) if values else 0
    cmq=mean(intra)-mean(inter)
    intra=[];inter=[]
    scenario=set(snap['metricMetadata']['useCaseScenarioPairs'])
    for a,b in itertools.permutations(range(u),2):
        an=snap['useCaseNames'][str(a)];bn=snap['useCaseNames'][str(b)]
        actor=any(an in values and bn in values for values in snap['actors'].values())
        relation='\0'.join(sorted([an,bn])) in scenario
        va={i for i,x in enumerate(ue[a]) if x};vb={i for i,x in enumerate(ue[b]) if x}
        cosine=len(va&vb)/math.sqrt(len(va)*len(vb)) if va and vb else 0
        value=.2*actor+.3*relation+.5*cosine
        (intra if genes[a]==genes[b] else inter).append(value)
    return [ifn,chd,cmq,instability,mean(intra)-mean(inter)]


rows=[]
for arm,previous,current in zip(['A','B','C'],old,new['population']):
    genes=previous['representative']['chromosome'];assert genes==current['chromosome']
    expected=independently_score(genes)
    assert all(abs(a-b)<=.00005000001 for a,b in zip(expected,current['metrics'])),(arm,expected,current['metrics'])
    assert current['metrics'][0]==previous['representative']['metrics'][0] and current['metrics'][3]==previous['representative']['metrics'][3]
    rows.append(dict(group=arm,sourceRun=str(source/f'group-{arm}/run-{arm}-107.json'),sourceRunSha256=sha(source/f'group-{arm}/run-{arm}-107.json'),serviceCount=current['serviceCount'],chromosome=genes,oldMetrics=previous['representative']['metrics'],newMetrics=current['metrics'],independentUnroundedMetrics=expected))
result=dict(status='passed',metricVersion=new['metricVersion'],metricOrder=['IFN','CHD','CMQ','Instability','UCM'],inputHash=snap['inputHash'],kind='fixed-partition-rescore-not-a-new-search',gaRuns=0,jevCalls=0,hv=None,hvReason='Old normalization/front/selection cannot be reused after objective corrections; fresh calibration and search are required',rows=rows)
(OUT/'fixed-partition-rescore.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False))
