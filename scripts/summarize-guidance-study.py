#!/usr/bin/env python3
"""Create a measured ten-run comparison; no search or API requests."""
import argparse
import csv
import json
import statistics
from pathlib import Path

METRICS=['IFN','CHD','CMQ','Instability','UCM']
LABELS={'off':'原 JEV 概率控制','numeric':'LLM 生成＋数值／多样性筛选','jev':'LLM 生成＋JEV 优选'}


def build(study):
    study=Path(study).resolve()
    read=lambda p:json.loads(p.read_text())
    cfg=read(study/'run-config.json')
    seeds=cfg['validationSeeds']
    if len(seeds)!=10 or len(set(seeds))!=10:
        raise ValueError('This comparison requires exactly ten distinct formal seeds')
    case=cfg['systems'][0]['id']
    comparison=read(study/'report/guidance-comparison.json')
    rows=comparison['rows']
    expected=set(seeds)
    if len(rows)!=30:
        raise ValueError('Expected 30 measured runs (3 arms x 10 seeds)')
    selection=read(study/'experiment'/case/'selection-jev.json')
    baseline={r['seed']:r for r in rows if r['arm']=='off'}
    results={}
    lines=['# CoCoME：0.5 门槛，三组各 10 次正式实验','',
           f"其他四指标的归一化损失允许增加上限：**{cfg['guidance']['lossTolerance']}**，相对各运行首个引导窗口冻结参考；不是原始指标的 50%。目标指标仍须严格改善，合法性、固定服务数和一致性检查保留。",'',
           f"离线 JEV 实际选择：**{selection['choice']}**；本批只离线选参一次。三组正式种子相同：`{seeds}`。每组 10 次，以下均值按各次代表方案／解集 HV 分别取算术平均。",'',
           '## 1. 三组均值','',
           '|方法|次数|平均 MS_Num|IFN ↓|CHD ↑|Instability ↑|UCM ↑|CMQ ↑|HV ↑|',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    detail=[]
    def total(values):
        return None if any(v is None for v in values) else sum(values)
    for arm in ('off','numeric','jev'):
        samples=sorted([r for r in rows if r['arm']==arm],key=lambda r:r['seed'])
        if len(samples)!=10 or {r['seed'] for r in samples}!=expected:
            raise ValueError('Missing, duplicate or mismatched paired seeds: '+arm)
        for row in samples:
            if row['completedGenerations']!=row['config']['generations']:
                raise ValueError('Nominal generation count not completed')
            if row['config']['id']!=selection['choice']:
                raise ValueError('Run did not use the one offline choice')
        mean={m:statistics.mean(r['representative']['metrics'][i] for r in samples) for i,m in enumerate(METRICS)}
        sd={m:statistics.stdev(r['representative']['metrics'][i] for r in samples) for i,m in enumerate(METRICS)}
        guide=[r.get('guidance') or {} for r in samples]
        for g in guide:
            if g:
                manifest=read(Path(g['auditDirectory'])/'guidance-manifest.json')
                if manifest['binding']['policy']['lossTolerance']!=cfg['guidance']['lossTolerance']:
                    raise ValueError('Guidance tolerance differs from study config')
        res=dict(samples=10,meanMetrics=mean,sampleStandardDeviations=sd,
                 meanServiceCount=statistics.mean(r['representative']['serviceCount'] for r in samples),
                 meanHv=statistics.mean(r['hv'] for r in samples),sdHv=statistics.stdev(r['hv'] for r in samples),
                 meanEvaluations=statistics.mean(r['evaluations'] for r in samples),totalEvaluations=sum(r['evaluations'] for r in samples),
                 selectedCount=sum(g.get('selectedCount',0) for g in guide),retainedCount=sum(g.get('retainedCount',0) for g in guide),
                 extraCandidateEvaluations=sum(g.get('candidateEvaluations',0) for g in guide),
                 llmRequests=sum(g.get('llm',{}).get('requestCount',0) for g in guide),llmFailures=sum(g.get('llm',{}).get('failureCount',0) for g in guide),
                 selectionRequests=sum(g.get('jev',{}).get('requestCount',0) for g in guide),selectionFailures=sum(g.get('jev',{}).get('failureCount',0) for g in guide),
                 llmTokens=total([g.get('llm',{}).get('totalTokens',0) for g in guide]),
                 selectionTokens=total([g.get('jev',{}).get('totalTokens',0) for g in guide]),
                 probabilityRequests=sum((r.get('onlineControl') or {}).get('requestCount',0) for r in samples),
                 probabilityFallbacks=sum((r.get('onlineControl') or {}).get('fallbackCount',0) for r in samples),
                 perSeedHvDifferenceToProbabilityOnly={str(r['seed']):r['hv']-baseline[r['seed']]['hv'] for r in samples})
        res['retentionRate']=None if not res['selectedCount'] else res['retainedCount']/res['selectedCount']
        results[arm]=res
        values=[res['meanServiceCount'],mean['IFN'],mean['CHD'],mean['Instability'],mean['UCM'],mean['CMQ'],res['meanHv']]
        lines.append('|'+LABELS[arm]+'|10|'+'|'.join(f'{v:.8f}' for v in values)+'|')
        for r in samples:
            detail.append(dict(arm=arm,seed=r['seed'],serviceCount=r['representative']['serviceCount'],**dict(zip(METRICS,r['representative']['metrics'])),hv=r['hv'],evaluations=r['evaluations'],result=r['sourceFile']))
    lines+=['','## 2. 样本标准差（10 次）','','|方法|IFN|CHD|Instability|UCM|CMQ|HV|','|---|---:|---:|---:|---:|---:|---:|']
    for arm,r in results.items():
        values=[r['sampleStandardDeviations'][m] for m in ['IFN','CHD','Instability','UCM','CMQ']]+[r['sdHv']]
        lines.append('|'+LABELS[arm]+'|'+'|'.join(f'{v:.8f}' for v in values)+'|')
    lines+=['','## 3. 候选、调用与实际评价开销','','|方法|总评价次数|候选额外评价|入选|下一代留存|LLM 请求／失败|一致性优选 JEV 请求／失败|概率 JEV 请求／回退|','|---|---:|---:|---:|---:|---:|---:|---:|']
    for arm,r in results.items():
        lines.append('|'+LABELS[arm]+'|'+str(r['totalEvaluations'])+'|'+str(r['extraCandidateEvaluations'])+'|'+str(r['selectedCount'])+'|'+str(r['retainedCount'])+'|'+str(r['llmRequests'])+'/'+str(r['llmFailures'])+'|'+str(r['selectionRequests'])+'/'+str(r['selectionFailures'])+'|'+str(r['probabilityRequests'])+'/'+str(r['probabilityFallbacks'])+'|')
    lines+=['','三组按相同所选配置的名义代数运行，额外候选评价和 API 成本不同，不宣称等预算。出现调用失败或回退的运行仍如实计入十次结果；这些状态单列，不能把模拟或回退称为成功的 JEV 判断。未知 tokens 保留 null。','','## 4. 文件与拆分图','','- 每次五指标、HV、染色体及来源：`ten-run-details.csv`。','- 均值、标准差、逐种子 HV 差值和调用计数：`ten-run-summary.json`。','- JEV 优选组的十套 `.req`、HTML、SVG：`report/visualizations/seed-<seed>/`。','- 原概率组：`report/comparison-reports/guidance-off/visualizations/seed-<seed>/`。','- 数值筛选组：`report/comparison-reports/guidance-numeric/visualizations/seed-<seed>/`。','- 打开对应目录的 `cocome-merm.html` 查看拆分图；`cocome.req` 与两类 SVG 在同一目录。','- 原始搜索／窗口审计保留在 `experiment/CoCoME/online-jev/` 下各模式的 trials 目录。','','本次没有改写原来的 0.02 实验，不把单种子旧结果混入十次均值。']
    report=study/'report'
    (report/'ten-run-summary.json').write_text(json.dumps(dict(lossTolerance=cfg['guidance']['lossTolerance'],seeds=seeds,offlineChoice=selection['choice'],perArm=results),ensure_ascii=False,indent=2)+'\n')
    with (report/'ten-run-details.csv').open('w',newline='') as stream:
        w=csv.DictWriter(stream,fieldnames=list(detail[0]));w.writeheader();w.writerows(detail)
    path=report/'ten-run-comparison.md';path.write_text('\n'.join(lines)+'\n')
    return path


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('study',type=Path);args=parser.parse_args();print(build(args.study))
