from pathlib import Path
import json, hashlib, xml.etree.ElementTree as ET
BASE=Path(__file__).resolve().parent
ROOT=BASE.parents[1]
OUT=BASE/'formal-cocome'
def read(p):return json.loads(p.read_text())
assert read(OUT/'pipeline-status.json')['status']=='success'
case=OUT/'experiment/CoCoME';rows=[]
for mode in ('off','rule','jev'):
    record=read(case/('online-'+mode)/'run-jev-101.json');run=record['run'];control=run['onlineControl'];audit=Path(control['auditDirectory'])
    assert run['evaluations']==1802000 and run['completedGenerations']==300
    assert run['config']['crossoverProbability']==.5 and run['config']['crossP']==.25
    stats=[json.loads(x) for x in (audit/'generation-stats.jsonl').read_text().splitlines()]
    decisions=[json.loads(x) for x in (audit/'control-decisions.jsonl').read_text().splitlines()]
    assert len(stats)==300 and [x['generation'] for x in decisions]==list(range(30,300,30))
    assert all(x['effectiveGeneration']==x['generation']+1 for x in decisions)
    assert all(len(x['evidence']['history'])==min(i+1,3) for i,x in enumerate(decisions))
    for d in decisions:
        g=d['generation'];assert abs(stats[g-1]['probability']-d['probabilityBefore'])<1e-12
        assert abs(stats[g]['probability']-d['probabilityAfter'])<1e-12
    index=audit/'audit-files.json';assert hashlib.sha256(index.read_bytes()).hexdigest()==control['auditIndexSha256']
    for name,digest in read(index).items():assert hashlib.sha256((audit/name).read_bytes()).hexdigest()==digest,name
    ET.parse(audit/'probability-trajectory.svg')
    if mode=='jev':
        assert control['requestCount']==9 and control['fallbackCount']==0
        for request in (audit/'requests').glob('*.json'):assert read(request)['status']=='success'
    report=OUT if mode=='jev' else OUT/'comparison-reports'/mode
    diagnostics=read(report/'visualizations/seed-101/cocome-diagnostics.json')
    assert diagnostics['counts']['uc']==10 and diagnostics['counts']['entity']==13
    assert not [x for x in diagnostics['diagnostics'] if x['severity']=='error']
    rows.append(dict(mode=mode,metrics=record['representative']['metrics'],hv=record['hv'],serviceCount=record['representative']['serviceCount'],control=control,searchMillis=run['searchMillis'],monitoringMillis=run['monitoringMillis'],searchComputeMillis=run['searchComputeMillis'],auditDirectory=str(audit)))
legacy=read(ROOT/'output/cocome-jev-v2-real-20261031/experiment/CoCoME/run-jev-101.json')
static=read(case/'online-off/run-jev-101.json')
assert static['run']['population']==legacy['run']['population'] and static['hv']==legacy['hv']
assert read(BASE/'pilot-real-v2/verification.json')['replayMatches']
roots=[ET.parse(p).getroot() for p in (ROOT/'target/surefire-reports').glob('TEST-*.xml')]
assert sum(int(x.attrib['failures'])+int(x.attrib['errors']) for x in roots)==0
counts=sum(int(x.attrib['tests']) for x in roots)
verification=dict(status='success',javaTests=counts,pythonTests=24,formalSeeds=[101],profile='formal',evaluationBudget=1802000,rows=rows,staticFullPopulationMatchesHistorical=True,realPilotReplayMatches=True,allCaseTenSeedStudyExecuted=False)
(BASE/'completion.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2))
report=BASE/'implementation-report.md'
s=report.read_text();s+='\n## 正式预算验证结果（seed 101）\n\n|模式|IFN|CHD|CMQ|Instability|UCM|HV|服务数|最终概率|请求数|回退数|\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n'
for r in rows:
 c=r['control'];s+='|'+r['mode']+'|'+'|'.join(str(v) for v in r['metrics']+[r['hv'],r['serviceCount'],c['finalProbability'],c['requestCount'],c['fallbackCount']])+'|\n'
s+='\n三组均完成300代、1,802,000次目标评价，crossP固定为0.25。静态监测组的完整种群和HV与历史seed101逐项一致。真实JEV组9次请求全部成功且无回退。\n\n耗时为搜索循环墙钟，包含逐代精确HV监测和系统调度等待；不等同于CPU时间。该监测开销明显，详见各RunResult分项。\n\n本次完成的是工程实现、短试验回放和一个正式种子的三组验证，不是八案例十种子论文实验。当前结果不证明JEV优于规则或固定参数。完整正式报告见 `formal-cocome/analysis.md`，其余模式报告在 `formal-cocome/comparison-reports/`。\n'
report.write_text(s)
print(json.dumps(dict(status='success',javaTests=counts,formalRows=[dict(mode=r['mode'],hv=r['hv'],finalProbability=r['control']['finalProbability']) for r in rows]),ensure_ascii=False))
