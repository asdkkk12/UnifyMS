#!/usr/bin/env python3
"""Validate and report the A fixed / B offline JEV / C online JEV ablation."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET

from online_comparison_report import METRICS, _fmt, _link, _table, _status
from merm_model import input_digest, export_req, parse_req, write_json
from merm_view import render_svg, render_html

LABELS = {'A': 'A：预定 C0 固定参数', 'B': 'B：离线 JEV 选参＋固定参数', 'C': 'C：同 B 起点＋在线 JEV 控制'}


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def generate(output, case, input_path, seeds):
    output, input_path, seeds = Path(output).resolve(), Path(input_path).resolve(), list(seeds)
    manifest = read(output/'repeat-manifest.json')
    if manifest.get('policyConfigSha256') and sha(output/'effective-policy.json')!=manifest['policyConfigSha256']:
        raise ValueError('Archived policy differs from the frozen policy hash')
    if manifest.get('arms') != ['A', 'B', 'C'] or manifest.get('seeds') != seeds:
        raise ValueError('Manifest design or seeds mismatch')
    if manifest.get('status') not in ('success', 'transport-degraded'):
        raise ValueError('Formal searches are incomplete')
    directory = output/'experiment'/case
    cal, selection = read(directory/'calibration.json'), read(directory/'selection-jev.json')
    snapshot = read(output/'model-snapshot.json')
    if input_digest(input_path) != cal['inputHash'] or sha(directory/'calibration.json') != manifest['calibrationSha256']:
        raise ValueError('Frozen input/calibration mismatch')
    if sha(directory/'selection-jev.json') != manifest['sourceSelectionSha256'] or selection['choice'] != manifest['initialChoice']:
        raise ValueError('Frozen selection mismatch')
    runs, paths, audits, decisions, diagrams = {}, {}, {}, {}, {}
    checks = []
    expected_prompt=manifest.get('promptVersion')
    effective_policy=manifest.get('effectiveOnlineControl')
    for seed in seeds:
        if seed in cal['calibrationSeeds']:
            raise ValueError('Formal/calibration seed overlap')
        for arm in ['A', 'B', 'C']:
            path = directory/f'group-{arm}'/f'run-{arm}-{seed}.json'
            row = read(path); run = row['run']; control = run['onlineControl']
            expected = 'C0' if arm == 'A' else selection['choice']
            if run['status'] != 'success' or run['config']['seed'] != seed or run['config']['id'] != expected:
                raise ValueError('Incorrect group/seed/configuration')
            config = cal['candidates'][expected]['config']
            for key in ['populationSize', 'generations', 'crossoverProbability', 'crossP']:
                if run['config'][key] != config[key]:
                    raise ValueError('Group initial parameters mismatch')
            mode = 'jev' if arm == 'C' else 'off'
            if control['mode'] != mode or run['inputHash'] != cal['inputHash']:
                raise ValueError('Group mode/input mismatch')
            expected_generations = (cal['evaluationBudget']//run['config']['populationSize']-1)//3
            expected_evaluations = (3*expected_generations+1)*run['config']['populationSize']
            if run['evaluations'] != expected_evaluations or run['completedGenerations'] != expected_generations:
                raise ValueError('Formal budget/generation mismatch')
            audit = Path(control['auditDirectory']).resolve()
            if output not in audit.parents or sha(audit/'audit-files.json') != control['auditIndexSha256']:
                raise ValueError('Audit source mismatch')
            audit_manifest=read(audit/'control-manifest.json')
            if expected_prompt and audit_manifest['promptVersion']!=expected_prompt:
                raise ValueError('Prompt version differs from frozen run manifest')
            if effective_policy and audit_manifest['binding']['policy']['gainThreshold']!=effective_policy['gainThreshold']:
                raise ValueError('Effective gain threshold differs from frozen run policy')
            for file, expected_sha in read(audit/'audit-files.json').items():
                if sha(audit/file) != expected_sha:
                    raise ValueError('Audit content hash mismatch: '+file)
            stats = [json.loads(line) for line in (audit/'generation-stats.jsonl').read_text().splitlines()]
            ds = [json.loads(line) for line in (audit/'control-decisions.jsonl').read_text().splitlines()]
            if [r['generation'] for r in stats] != list(range(1,expected_generations+1)) or [r['generation'] for r in ds] != list(range(30,expected_generations,30)):
                raise ValueError('Incomplete generation or decision trace')
            if arm in ('A','B'):
                if control['requestCount'] != 0 or any(r['probability'] != config['crossoverProbability'] for r in stats):
                    raise ValueError('Fixed group changed parameters or called JEV')
            else:
                responses = [read(audit/d['requestFile']) for d in ds]
                if len(responses) != control['requestCount'] or sum(r['status'] != 'success' for r in responses) != control['fallbackCount']:
                    raise ValueError('Online request count/fallback mismatch')
                for decision,response in zip(ds,responses):
                    g=decision['generation']
                    if decision['effectiveGeneration'] != g+1 or stats[g]['probability'] != decision['probabilityAfter']:
                        raise ValueError('Action does not apply to the next generation')
                    if response['status']=='success' and response.get('choice')!=decision['action']:
                        raise ValueError('Executed action differs from actual JEV Choice')
                    if expected_prompt and response.get('promptVersion')!=expected_prompt:
                        raise ValueError('Actual JEV request prompt version mismatch')
                    if effective_policy and response['request']['state']['gainThreshold']!=effective_policy['gainThreshold']:
                        raise ValueError('Actual JEV request gain threshold mismatch')
            vis = output/'reports'/arm/'visualizations'/f'seed-{seed}'
            vis.mkdir(parents=True, exist_ok=True)
            req = export_req(path, input_path, output/'model-snapshot.json', vis/(input_path.stem+'.req'))
            model = parse_req(req)
            if any(d['severity']=='error' for d in model['diagnostics']):
                raise ValueError('Exported model contains semantic errors')
            if model['counts']['uc'] != snapshot['structure']['useCases'] or model['counts']['entity'] != snapshot['structure']['entities'] or model['counts']['services'] != row['representative']['serviceCount']:
                raise ValueError('Exported model count differs from input/measured solution')
            uc, ul = render_svg(model, 'usecases'); domain, dl = render_svg(model, 'domain')
            model['layout'] = dict(usecases=ul, domain=dl)
            for suffix, content in [('-usecases.svg',uc),('-domain.svg',domain),('-merm.html',render_html(model,uc,domain))]:
                (vis/(input_path.stem+suffix)).write_text(content,encoding='utf-8')
            write_json(vis/(input_path.stem+'-merm.json'),model)
            write_json(vis/(input_path.stem+'-diagnostics.json'),dict(counts=model['counts'],diagnostics=model['diagnostics']))
            ET.parse(req);ET.parse(vis/(input_path.stem+'-usecases.svg'));ET.parse(vis/(input_path.stem+'-domain.svg'))
            runs[arm,seed], paths[arm,seed], audits[arm,seed], decisions[arm,seed], diagrams[arm,seed] = row,path,audit,ds,vis
            checks.append(dict(group=arm,seed=seed,taskKey=run['taskKey'],evaluations=run['evaluations'],auditVerified=True,modelCounts=model['counts']))
        if runs['B',seed]['run']['config'] != runs['C',seed]['run']['config']:
            raise ValueError('B and C do not use the same initial configuration')
        if all(d['action']=='KEEP' for d in decisions['C',seed]) and runs['B',seed]['run']['population'] != runs['C',seed]['run']['population']:
            raise ValueError('All-KEEP online control should preserve the seeded static population')

    mean=lambda arm, getter:statistics.mean(getter(runs[arm,s]) for s in seeds)
    aggregates={}
    for arm in ['A','B','C']:
        values={name:[runs[arm,s]['representative']['metrics'][i] for s in seeds] for name,i,_ in METRICS}
        values['HV']=[runs[arm,s]['hv'] for s in seeds]
        aggregates[arm]={name:dict(samples=len(v),mean=statistics.mean(v),sampleStandardDeviation=statistics.stdev(v) if len(v)>1 else None) for name,v in values.items()}
    summary=dict(case=case,seeds=seeds,initialChoice=selection['choice'],groups=aggregates,comparisons={})
    for name,left,right in [('B-A','B','A'),('C-B','C','B'),('C-A','C','A')]:
        summary['comparisons'][name]={metric:dict(pairedDifferences=[runs[left,s]['hv']-runs[right,s]['hv'] if metric=='HV' else runs[left,s]['representative']['metrics'][i]-runs[right,s]['representative']['metrics'][i] for s in seeds],meanDifference=aggregates[left][metric]['mean']-aggregates[right][metric]['mean']) for metric,i,_ in METRICS+[('HV',-1,1)]}
    write_json(output/'summary.json',summary)

    lines=[f'# {case}：A/B/C 三组正式预算消融实验','',
           f'本次用新的正式种子 `{seeds}` 实际运行三组，统一评价预算 **{cal["evaluationBudget"]:,}**。A 为预定 C0 固定配置，B 为离线 JEV 选择的 **{selection["choice"]}** 固定配置，C 使用与 B 相同的初始配置并启用在线 JEV。','',
           f'按本次结果，离线选参 B−A 的 HV 差值为 **{_fmt(summary["comparisons"]["B-A"]["HV"]["meanDifference"])}**；在线控制 C−B 的 HV 差值为 **{_fmt(summary["comparisons"]["C-B"]["HV"]["meanDifference"])}**。两项增量分别评价，不以 C 高于 A 直接认定在线控制有效。','',
           '## 1. 分组与问题','']
    rows=[]
    for arm in ['A','B','C']:
        c=runs[arm,seeds[0]]['run']['config']
        rows.append([LABELS[arm],c['id'],c['populationSize'],c['generations'],c['crossoverProbability'],c['crossP'],'动态 p_var' if arm=='C' else '固定参数'])
    lines+=_table(['组别','初始配置','N','G','初始 p_var','crossP','正式搜索'],rows)
    lines+=['','**B−A** 回答离线选参是否优于默认配置；**C−B** 回答在线控制在相同起点上是否带来增量；**C−A** 比较完整方案与默认配置。',
            '三组设计没有规则控制组，因此不能回答“JEV 是否优于规则控制”。这里的实验设计执行图中三组，不增加其他实验组。','',
            '## 2. 冻结与可比性','',
            f'输入 SHA-256：`{cal["inputHash"]}`；校准种子 `{cal["calibrationSeeds"]}`；指标版本 `{cal.get("metricVersion")}`。来源配置：'+_link(manifest['sourceConfig'])+'。',
            '本轮沿用已完成的真实离线 JEV 选择，未重新校准或再次调用离线选参。保留原选择与校准文件字节及绑定，正式种子未参与离线选择。A 的配置由实验预定，不按该正式种子的结果挑选。',
            '三组均开启相同的逐代新颖率和精确 HV 监测；A、B 的监测只观察，概率始终固定。C 每 30 代、且仍有后续代时调用 JEV，仅调整下一批子代的触发概率。',
            '五指标、初始化、搜索算子、环境选择和代表方案规则沿用现有实现。最终 HV 由最终保留非支配解集计算；Gain 是截断前父子代合并的新增覆盖。归一化及参考点对三组完全一致。','',
            '代表方案统一按到冻结校准理想点的归一化欧氏距离最小选取，并列按规范化染色体排序。搜索目标存储保留四位小数，所有组使用五维参考点 `[1.1,1.1,1.1,1.1,1.1]`，测试值不裁剪。','',
            '## 3. 正式运行结果','',
            'IFN 越小越好；CHD、Instability、UCM、CMQ 按当前实现越大越好。Instability 为当前 provided/(required+provided) 口径。五指标属于预定规则选出的代表方案，HV 属于完整解集。','']
    if effective_policy:
        insert_at=lines.index('## 3. 正式运行结果')
        policy_lines=['### 本轮在线规则版本','',
            f'提示词版本：`{expected_prompt}`；有效 Gain 阈值：**{effective_policy["gainThreshold"]}**；新颖率阈值：{effective_policy["noveltyThreshold"]}；观察窗口：{effective_policy["intervalGenerations"]} 代；调整步长：{effective_policy["step"]}。',
            '本轮不再把“Gain 为正”单独作为保持参数的依据。平均 Gain 不超过阈值时按低收益处理，JEV 结合新颖率、历史窗口和实际动作，在 INCREASE / KEEP / DECREASE 中自主选择；没有强制改变概率。','']
        if manifest.get('sourceCompatibility'):
            policy_lines+=['在线提示词源码已更新；通过原版源码备份重建原源码树哈希，核验其余搜索与评价源码均未改变，再沿用原离线证据。原实验绑定保持原样；本次记录新的实现哈希、提示词版本与独立运行标识。',
                           '有效策略与兼容证明：'+_link(output/'effective-policy.json')+'。','']
        lines[insert_at:insert_at]=policy_lines
    lines+=_table(['组别','种子','MS_Num','IFN ↓','CHD ↑','Instability ↑','UCM ↑','CMQ ↑','HV ↑'],
        [[LABELS[a],s,runs[a,s]['representative']['serviceCount']]+[runs[a,s]['representative']['metrics'][i] for _,i,_ in METRICS]+[runs[a,s]['hv']] for s in seeds for a in ['A','B','C']])
    lines+=['','### 分组均值与样本标准差','']
    lines+=_table(['组别','指标','样本量','均值','样本标准差'],[[a,k,v['samples'],v['mean'],v['sampleStandardDeviation']] for a,metrics in aggregates.items() for k,v in metrics.items()])
    lines+=['','### 配对差值','', '下表差值严格按组名顺序相减；IFN 的负差值表示改善，其余指标正差值表示改善。','']
    lines+=_table(['对比','IFN Δ','CHD Δ','Instability Δ','UCM Δ','CMQ Δ','HV Δ'],[[name]+[summary['comparisons'][name][k]['meanDifference'] for k in ['IFN','CHD','Instability','UCM','CMQ','HV']] for name in ['B-A','C-B','C-A']])
    for name,left,right in [('B-A','B','A'),('C-B','C','B'),('C-A','C','A')]:
        gain=summary['comparisons'][name]['HV']['meanDifference']
        improvements=[k for k,_,direction in METRICS if _status(summary['comparisons'][name][k]['meanDifference'],direction)=='改善']
        costs=[k for k,_,direction in METRICS if _status(summary['comparisons'][name][k]['meanDifference'],direction)=='退化']
        lines+=['',f'**{name}：** HV {_status(gain)}（差值 {_fmt(gain)}）；代表方案改善指标：{"、".join(improvements) or "无"}；退化指标：{"、".join(costs) or "无"}。']
    lines+=['','## 4. 实际预算与耗时','', '下列均为本次实测墙钟时间。搜索计算秒为搜索循环总秒扣除已记录监测、控制与审计秒，并非 CPU 时间。','']
    lines+=_table(['组别','种子','完成代数','评价次数','Worker 秒','搜索计算秒','监测秒','控制秒'],
        [[a,s,runs[a,s]['run']['completedGenerations'],runs[a,s]['run']['evaluations']]+[runs[a,s]['run'][k]/1000 for k in ['workerMillis','searchComputeMillis','monitoringMillis','controlMillis']] for s in seeds for a in ['A','B','C']])
    lines+=['','## 5. JEV 选择与在线控制','',
            f'离线 JEV 实际历史选择 **{selection["choice"]}**；模型 `{selection.get("actualModel")}`；置信度 `{selection.get("confidence")}`；选项概率 `{json.dumps(selection.get("probabilities"),ensure_ascii=False)}`。',
            '离线请求同时提供具名五指标、定义与方向、冻结归一化、候选间改善退化对照及解集 HV。JEV 返回配置编号；接口没有提供文字理由，下面的指标得失是程序生成的证据解释。','']
    selected=selection['choice'];candidates=cal['candidates']
    candidate_means={key:[statistics.mean(t['representative']['metrics'][i] for t in entry['trials']) for i in range(5)] for key,entry in candidates.items()}
    lines+=_table(['校准配置','IFN ↓','CHD ↑','Instability ↑','UCM ↑','CMQ ↑','平均 HV ↑'],[[key]+[candidate_means[key][i] for _,i,_ in METRICS]+[candidates[key]['meanHv']] for key in sorted(candidates)])
    lines+=['',f'所选 {selected} 相对 C0 的校准证据：','']
    lines+=_table(['指标','所选配置−C0','方向判断'],[[k,candidate_means[selected][i]-candidate_means['C0'][i],_status(candidate_means[selected][i]-candidate_means['C0'][i],direction)] for k,i,direction in METRICS]+[['HV',candidates[selected]['meanHv']-candidates['C0']['meanHv'],_status(candidates[selected]['meanHv']-candidates['C0']['meanHv'])]])
    lines+=['','本次新增离线 JEV 调用为 **0**；沿用的历史离线选择总响应时间为 **'+_fmt(selection.get('totalResponseMillis')/1000 if selection.get('totalResponseMillis') is not None else None)+' 秒**，总 tokens **'+_fmt(selection.get('totalTokens'))+'**。历史成本独立保留，不记作本次新增请求。在线调用统计如下，缺失 tokens 显示“未取得”。',
            '冻结校准与归一化：'+_link(directory/'calibration.json')+'；真实离线选择、请求及响应审计：'+_link(directory/'selection-jev.json')+'。','']
    lines+=_table(['组别','种子','在线请求','回退','总 tokens','响应总秒','最终 p_var'],[[a,s,runs[a,s]['run']['onlineControl']['requestCount'],runs[a,s]['run']['onlineControl']['fallbackCount'],runs[a,s]['run']['onlineControl'].get('totalTokens'),runs[a,s]['run']['onlineControl']['totalResponseMillis']/1000,runs[a,s]['run']['onlineControl']['finalProbability']] for s in seeds for a in ['A','B','C']])
    lines+=['','### C 组实际在线动作','']
    lines+=_table(['种子','决策代','生效代','平均新颖率','平均 Gain','动作','调整前 p_var','调整后 p_var'],[[s,d['generation'],d['effectiveGeneration'],d['evidence']['currentWindow']['meanNovelty'],d['evidence']['currentWindow']['meanGain'],d['action'],d['probabilityBefore'],d['probabilityAfter']] for s in seeds for d in decisions['C',s]])
    if all(d['action']=='KEEP' for s in seeds for d in decisions['C',s]):
        lines+=['','本次 C 组所有在线动作均为 KEEP，没有实际改变概率；已核对其最终完整种群与 B 组一致。因此本次在线控制没有产生额外质量收益，九次决策是否必要也应结合调用成本评价。']
    lines+=['','## 6. 原始文件、MERM 与拆分图','',
            '各组代表方案的模型均直接从本次该组结果导出，不重新运行搜索。打开对应 HTML 可切换用例图和实体／接口图，使用缩放、拖动、搜索和服务聚焦。','']
    lines+=_table(['组别','种子','运行 JSON','MERM .req','离线拆分图','用例 SVG','实体接口 SVG','审计'],[[a,s,_link(paths[a,s]),_link(diagrams[a,s]/(input_path.stem+'.req')),_link(diagrams[a,s]/(input_path.stem+'-merm.html')),_link(diagrams[a,s]/(input_path.stem+'-usecases.svg')),_link(diagrams[a,s]/(input_path.stem+'-domain.svg')),_link(audits[a,s]/'control-analysis.md')] for s in seeds for a in ['A','B','C']])
    lines+=['', '运行清单：'+_link(output/'repeat-manifest.json')+'；机器可读汇总：'+_link(output/'summary.json')+'；校验记录：'+_link(output/'verification.json')+'。','',
            '## 7. 结论适用范围','']
    if len(seeds)==1:
        lines+=[f'本次是 {case}、正式种子 {seeds[0]} 的三组配对验证，不是十次平均。单个种子不能估计方差或置信区间，不能据此声称普遍改善。新种子与此前运行分别记录，不混成同一实验均值。']
    else:
        lines+=[f'本次包含 {len(seeds)} 个配对正式种子，各组按同一列表计算均值与样本标准差。']
    if manifest.get('policyId')=='meaningful-gain-v2-0.0002':
        lines+=[f'种子 103 已用于提出新阈值，应作为开发案例；本轮种子 `{seeds}` 用于新增验证。本组三组估计新规则相对固定参数的增量。判断阈值修改本身是否优于旧规则，还需要同种子的旧规则对照，不能直接归因于不同种子之间的结果差异。']
    lines+=['当前三组可检验默认配置、离线选参和在线控制的各自增量；对规则控制器的优势需要单独设置相应实验。控制策略、阈值和步长未按本次测试结果重新挑选。','']
    report=output/'analysis.md';report.write_text('\n'.join(lines),encoding='utf-8')
    for arm in ['A','B','C']:
        (output/'reports'/arm/'analysis.md').write_text('# '+LABELS[arm]+'\n\n本组实际输入、参数、指标、运行与模型来源见 '+_link(report)+'。\n',encoding='utf-8')
    write_json(output/'verification.json',dict(status='passed',design='abc-ablation',sourceEvidencePreserved=True,bAndCShareInitialConfig=True,promptVersion=expected_prompt,gainThreshold=effective_policy.get('gainThreshold') if effective_policy else None,
        bAndCIdenticalPopulations=all(runs['B',s]['run']['population']==runs['C',s]['run']['population'] for s in seeds),rows=checks))
    return report


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--case',required=True);p.add_argument('--input',type=Path,required=True);p.add_argument('--seeds',required=True)
    args=p.parse_args();print(generate(args.output,args.case,args.input,[int(s) for s in args.seeds.split(',')]))
