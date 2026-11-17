#!/usr/bin/env python3
"""One command per .remodel/Java case: tune, select, search, export, visualize and report."""
import argparse
import copy
import datetime
import getpass
import json
import os
import re
import shlex
import subprocess
import statistics
import sys
import time
from pathlib import Path
from merm_model import digest, input_digest, export_req, parse_req, write_json, remodel_entities
from merm_view import render_svg, render_html

ROOT = Path(__file__).resolve().parents[1]
METRICS = ['IFN', 'CHD', 'CMQ', 'Instability', 'UCM']
VERSION = 'five-metric-hv-paper-v4-isolated-zero'
PAPER_METRIC_VERSION = 'paper-ieee-eq5-21-v2-isolated-zero'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def seeds(value):
    try:
        values = [int(x.strip()) for x in value.split(',')]
    except ValueError:
        raise argparse.ArgumentTypeError('Seeds must be comma-separated integers')
    if not values or len(set(values)) != len(values) or any(not -(2**63) <= x < 2**63 for x in values):
        raise argparse.ArgumentTypeError('Seeds must be unique signed 64-bit integers')
    return values


def configuration(args, output):
    if set(args.calibration_seeds) & set(args.test_seeds):
        raise ValueError('Calibration and test seeds must be disjoint')
    cfg = dict(profile=args.profile, selector=args.selector, metricVersion=PAPER_METRIC_VERSION,
               outputDirectory=str(output/'experiment'), calibrationSeeds=args.calibration_seeds,
               validationSeeds=args.test_seeds, evaluationBudget=0,
               systems=[dict(id=args.case, input=str(args.input))],
               jev=dict(endpoint=args.endpoint, model=args.model, timeoutMillis=60000))
    cfg['onlineControl'] = getattr(args, 'control_options', {'mode': 'off', 'intervalGenerations': 10})
    cfg['guidance'] = getattr(args, 'guidance_options', {'mode': 'off'})
    if args.profile == 'smoke':
        # The existing smoke table, explicitly unsuitable for scientific quality conclusions.
        cfg['candidates'] = read(getattr(args, 'candidate_config', None) or ROOT/'config/jev-tuning-smoke.json')['candidates']
    return cfg


def fmt(value):
    return '未取得' if value is None else format(value, '.12g') if isinstance(value, (int, float)) else str(value)


def link(path, label=None):
    return '[' + (label or Path(path).name) + '](<' + str(Path(path).resolve()) + '>)'


def candidate_mean(entry):
    values = [t['representative']['metrics'] for t in entry['trials']]
    if not values or any(len(v) != 5 for v in values):
        raise ValueError('Missing calibration representative metrics')
    return [sum(v[i] for v in values)/len(values) for i in range(5)]


def evidence_context(experiment, case, selector):
    directory = experiment/case
    cal = read(directory/'calibration.json')
    selection = read(directory/('selection-'+selector+'.json'))
    if selection.get('status') != 'success' or selection.get('choice') not in {'C'+str(i) for i in range(6)}:
        raise ValueError('Selection is not a successful C0-C5 decision')
    expected_policy = VERSION if cal.get('metricVersion') == PAPER_METRIC_VERSION else 'five-metric-hv-v2'
    if selector == 'jev' and (selection.get('mock') is not False or selection.get('selectionPolicyVersion') != expected_policy):
        raise ValueError('Report requires a real JEV result with the current five-metric/HV rule')
    if cal['inputHash'] != selection.get('inputHash'):
        raise ValueError('Calibration and selection input hashes do not match')
    expected = selection.get('evidenceHash')
    # Legacy recovery records bind the original calibration separately; never rewrite their binding.
    source_sha = selection.get('sourceCalibrationSha256')
    if source_sha and source_sha != digest(directory/'calibration.json'):
        raise ValueError('Recovered selection calibration SHA-256 mismatch')
    if not expected:
        raise ValueError('Selection is missing its evidence binding')
    return directory, cal, selection


def publish_report(args, output, snapshot, experiment, test_seeds):
    control = getattr(args, 'control', 'off')
    monitor_control = getattr(args, 'monitor_control', False)
    guidance = getattr(args, 'guidance', 'off')
    directory, cal, selection = evidence_context(experiment, args.case, args.selector)
    if input_digest(args.input) != cal['inputHash']:
        raise ValueError('Source differs from calibration input')
    chosen = selection['choice']
    paper_metrics = cal.get('metricVersion') == PAPER_METRIC_VERSION
    candidates = cal['candidates']
    baseline = max(sorted(candidates), key=lambda key: candidates[key]['meanHv'])
    means = {key: candidate_mean(entry) for key, entry in candidates.items()}
    runs, diagrams = [], []
    for seed in test_seeds:
        if seed in cal['calibrationSeeds']:
            raise ValueError('Formal seed overlaps calibration')
        result_directory = directory/('online-'+control) if control != 'off' or monitor_control else directory
        if guidance != 'off':
            result_directory = result_directory/('guidance-'+guidance)
        runpath = result_directory/f'run-{args.selector}-{seed}.json'
        run = read(runpath)
        if run['run']['config']['id'] != chosen or run['run']['config']['seed'] != seed:
            raise ValueError('Formal run does not use selected configuration/seed')
        config = run['run']['config']
        candidate = candidates[chosen]['config']
        for key in ('populationSize', 'generations', 'crossoverProbability', 'crossP'):
            if config[key] != candidate[key]:
                raise ValueError('Formal parameters differ from chosen candidate')
        if run['run']['inputHash'] != cal['inputHash']:
            raise ValueError('Formal input differs from calibration input')
        vis = output/'visualizations'/('seed-'+str(seed))
        vis.mkdir(parents=True)
        name = args.input.stem
        req = export_req(runpath, args.input, snapshot, vis/(name+'.req'))
        model = parse_req(req)
        if any(d['severity'] == 'error' for d in model['diagnostics']):
            write_json(vis/(name+'-diagnostics.json'), model['diagnostics'])
            raise ValueError('Exported MERM has semantic errors; see diagnostics')
        uc, ul = render_svg(model, 'usecases')
        domain, dl = render_svg(model, 'domain')
        model['layout'] = dict(usecases=ul, domain=dl)
        for suffix, value in [('-usecases.svg', uc), ('-domain.svg', domain), ('-merm.html', render_html(model, uc, domain))]:
            (vis/(name+suffix)).write_text(value, encoding='utf-8')
        write_json(vis/(name+'-merm.json'), model)
        write_json(vis/(name+'-diagnostics.json'), dict(inputHash=model['inputHash'], counts=model['counts'], diagnostics=model['diagnostics']))
        runs.append((seed, runpath, run))
        diagrams.append((seed, vis, name, model))
    label = '真实 JEV' if args.selector == 'jev' else 'mock（固定返回，仅验证流程）' if args.selector == 'mock' else '最大平均 HV 数值基线'
    lines = [f'# {args.case}：逐系统调优、正式结果与 MERM 拆分图', '',
             f'生成时间：{datetime.datetime.now().astimezone().isoformat(timespec="seconds")}。数据来源：{link(directory)}。', '',
             '## 1. 结论与数据身份', '',
             f'选择器：**{label}**；选择 **{chosen}**。最大平均校准 HV 基线选择 **{baseline}**。',
             f'实验 profile：`{cal.get("profile")}`；选择规则：`{selection.get("selectionPolicyVersion")}`。',
             f'输入：{link(args.input)}；SHA-256：`{cal["inputHash"]}`。',
             f'校准种子：`{cal["calibrationSeeds"]}`；本报告正式运行种子：`{test_seeds}`；停止模式：**{cal.get("stoppingMode", "legacy-budget")}**；显式评价预算：`{cal["evaluationBudget"]}`（0 表示按名义代数）。', '',
             '单校准种子无法判断选参稳定性；少量正式种子仅为示例，不能据此声称统计显著改善。',
             ('指标版本：`'+cal.get('metricVersion','unknown')+'`。五指标按 IEEE 稿第 9–10 页公式 (5)–(21) 计算；属性使用区分大小写的声明名称，空属性并集相似度约定为 0。' if paper_metrics else '本报告展示归档旧指标版本：仅 IFN 已改为接口总数除以服务数，其余公式属于旧实现，不能与新版论文公式结果混用。'), '',
             '## 2. 六组配置及校准结果', '',
             '|配置|N|名义 G|交叉触发概率|crossP|IFN ↓|CHD ↑|CMQ ↑|Instability ↑|UCM ↑|平均解集 HV ↑|',
             '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key in sorted(candidates):
        entry = candidates[key]
        c = entry['config']
        values = [key, c['populationSize'], c['generations'], c['crossoverProbability'], c['crossP']]+means[key]+[entry['meanHv']]
        lines.append('|'+'|'.join(fmt(v) for v in values)+'|')
    if args.input.is_dir():
        source_snapshot=read(snapshot)
        audit=source_snapshot['sourceAudit']
        source_report=output/'source-audit.json'
        write_json(source_report,audit)
        counts=source_snapshot['structure']
        zero_dependency=[source_snapshot['useCaseNames'][str(i)] for i,row in enumerate(source_snapshot['useCaseEntityMatrix']) if not any(row)]
        nondeclarations={row['sourceFile']:row['nonDeclarationNames'] for row in audit['controllers'] if row['nonDeclarationNames']}
        lines += ['', '### Java 源码输入的提取边界', '',
                  f'输入是现有多模块 Java 工程，提取 {audit["javaFileCount"]} 个 Java 文件、{len(audit["controllers"])} 个 Controller 源类；搜索模型保留 {counts["useCases"]} 个用例、{counts["entities"]} 个实体、{counts["actors"]} 个参与者及 {counts["operations"]} 项操作映射。',
                  ('**Java 用例、实体和依赖图仍由原启发式解析器提取，并非完整语义调用图；AST 提取的非引用声明字段用于论文 CMQ，Java 未显式声明 include/extend 时 CorrF 取 0。**' if paper_metrics else '**本次归档结果使用原 Java 启发式解析器，并非完整语义调用图。AST 在该版本仅用于补充字段和诊断，不改变归档搜索指标。**'),
                  '用例以 Controller 中正则提取的方法简单名标识；同名方法会合并／覆盖，参与者以 Controller 简单名去后缀标识，同名类也可能覆盖。实体由原 @Data／Mapper 规则和可达操作决定，可能包含 DTO、响应包装类并遗漏其他候选。',
                  f'同名方法冲突 {len(audit["methodNameCollisions"])} 组，同名参与者冲突 {len(audit["actorNameCollisions"])} 组。未进入搜索的实体候选：`{audit["unselectedEntityCandidates"]}`。详见 {link(source_report)}。',
                  f'**{len(zero_dependency)}/{counts["useCases"]} 个用例没有提取到实体依赖**：`{zero_dependency}`。正则提取到的非方法声明名称：`{nondeclarations}`。这些是输入提取的局限，不能用更高的 HV 掩盖。',
                  '`.req` 属性来自 Java 语法树声明（不导出字段初始值），实体关联使用搜索中原有的词法依赖矩阵，包含原矩阵的对角项，并标注 `legacy-lexical-matrix`；它们不能直接等同于已验证的业务实体关联。原项目模块只用于来源审计，不当作算法的新拆分答案。', '',
                  '|原代码模块|Java 文件数|','|---|---:|']
        lines += ['|'+module+'|'+str(count)+'|' for module,count in audit['moduleJavaFiles'].items()]
        lines += ['', '|冲突方法简单名|涉及 Controller 数量|', '|---|---:|']
        lines += ['|'+name+'|'+str(len(paths))+'|' for name,paths in audit['methodNameCollisions'].items()]
        lines += ['', '此实验是对已提取模型的重新分组，不能单凭本次指标声称完整业务架构得到改善，也不能把本项目已存在的微服务模块当作已验证的金标准。']
    lines += ['', '五指标为逐次校准代表方案指标的均值；HV 为每次运行完整有效非支配解集的 HV，再对校准种子取均值。新版严格执行各自名义代数。C1 的 N×G=22500，其余五组为 20000，并非等预算实验；实际次数读取计数器。', '',
              '## 3. JEV 的作用与选择原因', '']
    if args.selector == 'jev':
        lines += [f'实际模型：`{selection.get("actualModel")}`；置信度：`{selection.get("confidence")}`；选项分布：`{json.dumps(selection.get("probabilities"),ensure_ascii=False)}`。',
                  '离线 JEV 接收校准证据并返回一个初始配置编号。正式搜索中的 JEV 调用取决于在线 control 模式；JEV 不直接修改染色体。',
                  '**接口没有返回自然语言理由。以下为程序根据校准结果生成的得失解释，不是 JEV 原文或其推理链，不能确认其隐含权重。**']
    else:
        lines += ['本报告没有真实 JEV 决策。numeric 只按平均校准 HV 选择；mock 固定选择 C0，不能作为 JEV 效果证据。']
    lines += ['', f'下表为 **{chosen} − 对照配置**，按各指标方向标记改善、退化或持平；不把改善数量作为选参规则。', '',
              '|对照|IFN Δ|CHD Δ|CMQ Δ|Instability Δ|UCM Δ|HV Δ|', '|---|---|---|---|---|---|---|']
    for other in sorted(candidates):
        if other == chosen:
            continue
        ds = [a-b for a,b in zip(means[chosen], means[other])] + [candidates[chosen]['meanHv']-candidates[other]['meanHv']]
        cells = []
        for i, delta in enumerate(ds):
            gain = -delta if i == 0 else delta
            cells.append(fmt(delta)+' '+('持平' if abs(delta)<1e-12 else '改善' if gain>0 else '退化'))
        lines.append('|'+other+'|'+'|'.join(cells)+'|')
    for other in dict.fromkeys(['C0', baseline]):
        if other == chosen:
            continue
        gains, costs, ties = [], [], []
        for i, name in enumerate(METRICS):
            delta = means[chosen][i]-means[other][i]
            advantage = -delta if i==0 else delta
            (ties if abs(advantage)<1e-12 else gains if advantage>0 else costs).append(name)
        hvdelta = candidates[chosen]['meanHv']-candidates[other]['meanHv']
        lines += ['', f'**相对 {other} 的证据解释：** 改善指标为 {", ".join(gains) or "无"}；退化指标为 {", ".join(costs) or "无"}；持平指标为 {", ".join(ties) or "无"}。解集 HV 差值 {fmt(hvdelta)}。这说明所选配置体现上述得失，不能断言所有指标最优或获知 JEV 的内部选择理由。']
    lines += ['', '同等关注五指标，以 HV 补充解集覆盖；没有固定加权总分或最高 HV 优先规则。所选代表方案可能在某些指标上退化。', '',
              '## 4. 独立种子正式结果', '',
              '|种子|配置|IFN ↓|CHD ↑|CMQ ↑|Instability ↑|UCM ↑|解集 HV ↑|服务数|评价次数|搜索秒|',
              '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for seed, path, run in runs:
        rep = run['representative']
        values = [seed, chosen]+rep['metrics']+[run['hv'], rep['serviceCount'], run['run']['evaluations'], run['run']['searchMillis']/1000]
        lines.append('|'+'|'.join(fmt(v) for v in values)+'|')
    summary = dict(samples=len(runs), seeds=test_seeds, initialChoice=chosen, controlMode=control, guidanceMode=guidance,
                   means={name:statistics.mean(r['representative']['metrics'][i] for _,_,r in runs) for i,name in enumerate(METRICS)},
                   sampleStandardDeviations={name:(statistics.stdev(r['representative']['metrics'][i] for _,_,r in runs) if len(runs)>1 else None) for i,name in enumerate(METRICS)},
                   meanHv=statistics.mean(r['hv'] for _,_,r in runs),
                   meanServiceCount=statistics.mean(r['representative']['serviceCount'] for _,_,r in runs),
                   meanSearchSeconds=statistics.mean(r['run']['searchMillis']/1000 for _,_,r in runs),
                   runFiles=[str(path) for _,path,_ in runs])
    write_json(output/'summary.json', summary)
    lines += ['', '### 多种子汇总', '', '|指标|算术均值|样本标准差|', '|---|---:|---:|']
    for name in METRICS:
        lines.append('|'+name+'|'+fmt(summary['means'][name])+'|'+fmt(summary['sampleStandardDeviations'][name])+'|')
    lines += ['', f'样本数 {len(runs)}；平均服务数 {fmt(summary["meanServiceCount"])}；平均解集 HV {fmt(summary["meanHv"])}。完整汇总：'+link(output/'summary.json')+'。单样本标准差不可估计，不能据此声称稳定性。']
    lines += ['', '### 在线控制与审计', '',
              f'在线模式：`{control}`；仅更新 `p_var`（现有 crossoverProbability）；crossP 与初始配置固定。',
              'Gain 为截断前父子代合并集合的新增 HV 覆盖，不是退化率；新颖率按当前父代及唯一子代划分计算。',
              '|种子|观察代数|决策数|JEV 调用数|回退数|最后概率|在线 tokens|监测秒|控制秒|审计目录|',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|---|']
    audit_details = []
    for seed, path, run in runs:
        r = run['run']
        c = r.get('onlineControl')
        if c:
            audit = Path(c['auditDirectory'])
            cells = [seed, c['observedGenerations'], c['decisionCount'], c['requestCount'], c['fallbackCount'],
                     c['finalProbability'], c.get('totalTokens'), r.get('monitoringMillis',0)/1000,
                     r.get('controlMillis',0)/1000]
            lines.append('|'+'|'.join(fmt(v) for v in cells)+'|'+link(audit)+'|')
            audit_details += ['', f'seed {seed}：'+link(audit/'control-analysis.md','参数轨迹与窗口记录')+'；'+link(audit/'probability-trajectory.svg','概率曲线')+'；'+
                      link(audit/'generation-stats.jsonl','逐代统计')+'；'+link(audit/'control-decisions.jsonl','决策与回放记录')+'。']
        else:
            lines.append(f'|{seed}|—|0|0|0|固定|—|—|—|未启用监测|')
    lines += audit_details
    lines += ['', '### 小模型定向生成与批量优选', '', f'候选模式：`{guidance}`；每 10 代观察，末代不请求。引导候选占用下一代子代位置，总数保持 N，统一环境选择。',
              '生成 LLM 与优选 JEV 独立统计。冻结首窗口参考，目标严格改善，其他损失增加不超过配置门槛；结果不保证优于原搜索。',
              '|种子|窗口|候选额外评价|优选数|下一代留存数|LLM 请求/tokens|优选 JEV 请求/tokens|审计|', '|---|---:|---:|---:|---:|---|---|---|']
    for seed, path, run in runs:
        g = run['run'].get('guidance')
        if not g:
            lines.append(f'|{seed}|0|0|0|0|—|—|未启用|')
            continue
        audit = Path(g['auditDirectory'])
        values = [seed, g['windowCount'], g['candidateEvaluations'], g['selectedCount'], g['retainedCount'],
                  str(g['llm']['requestCount'])+'/'+fmt(g['llm'].get('totalTokens')), str(g['jev']['requestCount'])+'/'+fmt(g['jev'].get('totalTokens'))]
        lines.append('|'+'|'.join(fmt(v) for v in values)+'|'+link(audit/'guidance-analysis.md')+'|')
        lines += ['', '依赖快照：'+link(audit/'current-model.dep')+'；完整窗口与回放：'+link(audit/'guidance-windows.jsonl')+'。']
    comparison_guidance = output/'guidance-comparison.json'
    if not comparison_guidance.exists():
        comparison_guidance = directory/'guidance-comparison.json'
    if comparison_guidance.exists():
        lines += ['', '三组对照（原概率控制／数值与多样性优选／JEV 优选）：'+link(comparison_guidance)+'。使用配对种子和相同初始配置，各自状态下生成候选，不宣称等预算。']
    comparison = directory/'online-comparison.json'
    if comparison.exists():
        modes = read(comparison).get('arms', ['off', 'rule', 'jev'])
        labels = {'off': '静态', 'rule': '规则', 'jev': 'JEV'}
        lines += ['', '同起点、同预算、同种子的 '+ '/'.join(labels.get(mode, mode) for mode in modes) +' 对照：'+link(comparison)+'。各模式报告与图的具体位置见本次汇总报告。']
    lines += ['', 'tokens 缺失不记为 0；回退次数大于 0 时需检查真实调用状态。在线运行使用独立算法版本，旧固定参数结果不能冒充动态结果。']
    lines += ['', '**五指标评价代表方案；HV 评价整组非支配方案，不是某个代表方案的 HV。**',
              'IFN 越小越好；其余四项按当前代码越大越好。Instability 的当前实现为 provided/(required+provided)，仅在提供与需求接口均为 0 时取 0，再对服务平均，其方向不能直接套用其他论文定义。',
              '搜索目标顺序为 `[-IFN, CHD, CMQ, Instability, UCM]`，库将目标存储到四位小数。HV 使用六组校准结果冻结的归一化、五维参考点 `[1.1,1.1,1.1,1.1,1.1]`，测试值不裁剪；零跨度使用单位尺度。',
              '代表方案沿用到冻结校准理想点的归一化欧氏距离最小规则，并列按规范化染色体排序。正式结果的五指标与 HV 直接读取原运行记录，绘图不重算。', '',
              '## 5. 输出文件与对应拆分图', '',
              '|内容|实际位置|', '|---|---|',
              '|六组校准结果与归一化|'+link(directory/'calibration.json')+'|',
              '|选择记录、请求与响应|'+link(directory/f'selection-{args.selector}.json')+'|',
              '|输入结构快照|'+link(snapshot)+'|']
    for seed, path, run in runs:
        lines.append(f'|正式结果 seed {seed}|{link(path)}|')
    for seed, vis, name, m in diagrams:
        lines += [f'|`.req` seed {seed}|{link(vis/(name+".req"))}|',
                  f'|离线拆分图 seed {seed}|{link(vis/(name+"-merm.html"))}|',
                  f'|用例 SVG seed {seed}|{link(vis/(name+"-usecases.svg"))}|',
                  f'|实体／接口 SVG seed {seed}|{link(vis/(name+"-domain.svg"))}|',
                  f'|节点、引用来源与布局 seed {seed}|{link(vis/(name+"-merm.json"))}|',
                  f'|诊断 seed {seed}|{link(vis/(name+"-diagnostics.json"))}|']
    lines += ['', '### 操作步骤', '',
              '1. 打开上表 HTML，在顶部切换“用例图”和“实体与接口图”。文件完全离线，无需 Eclipse。',
              '2. 滚轮缩放、拖动画布，选择服务自动聚焦，搜索元素；点击节点或连线查看 `.req` 路径和引用。',
              '3. 切换属性／操作显示，取消“接口明细”可合并同一服务对的接口边；SVG 可直接用于排版。',
              '4. 只有 `.req` 时运行以下命令，无需再次调用 JEV 或运行搜索：', '', '```bash',
              shlex.join(['python3','scripts/render-merm.py','--input',str(diagrams[0][1]/(diagrams[0][2]+'.req')),'--output','output/my-req-view']), '```', '']
    for seed, vis, name, m in diagrams:
        lines += [f'### seed {seed} 拆分组成', '',
                  f'{m["counts"]["services"]} 个服务、{m["counts"].get("uc",0)} 个用例、{m["counts"].get("entity",0)} 个实体；**{m["counts"]["interfaceEdges"]} 条接口配对，合并为 {m["counts"]["serviceEdges"]} 条有向服务依赖**。', '',
                  '|文件服务编号|用例|实体|', '|---|---|---|']
        for s in m['services']:
            us = [n['name'] for n in m['nodes'] if n['service']==s['id'] and n['kind']=='uc']
            es = [n['name'] for n in m['nodes'] if n['service']==s['id'] and n['kind']=='entity']
            lines.append('|'+s['name']+'|'+', '.join(us)+'|'+(', '.join(es) or '无')+'|')
    lines += ['', '服务编号使用文件中的零起始编号；旧报告中的 MS1 对应 Microservice 0。实体关联不自动转成接口边，接口连接依据显式配对。`.req` 是本次代表方案生成的拆分视图结构，不能混用 `generatedSolution` 中其他运行的旧文件。',
              '导出与查看器不保证事务、调用时序或部署正确性；不生成 `.aird`，尚未验证原生 Sirius 导入。', '',
              '## 6. 调用成本和实验边界', '',
              f'本次选择记录：总 tokens **{fmt(selection.get("totalTokens"))}**；响应耗时 **{fmt(selection.get("totalResponseMillis"))} ms**；尝试 **{len(selection.get("attempts",[]))}** 次。',
              '若响应不含 usage，tokens 记为未取得，不能视为 0。置信度不是测试改善概率。']
    failed = directory/'selection-jev-context-limit-failed.json'
    if args.selector == 'jev' and failed.exists():
        old = read(failed)
        total_ms = (old.get('totalResponseMillis') or 0)+(selection.get('totalResponseMillis') or 0)
        lines += [f'另有先前上下文超限失败记录 {link(failed)}；计入该记录后共 {len(old.get("attempts",[]))+len(selection.get("attempts",[]))} 次请求，累计响应 {fmt(total_ms)} ms。此项与成功批次耗时分别列示，避免漏算重试。']
    if cal.get('profile') == 'smoke' or args.selector == 'mock':
        lines += ['**这是 smoke / mock 流程验证，不是正式 JEV 效果实验。**']
    validation = directory/f'validation-{args.selector}.json'
    lines += [f'三组对照验证：{link(validation)}。' if validation.exists() else '尚无本次 default / numeric / JEV 多种子配对验证；需要 `--compare` 才会运行这些对照，不能把当前结果直接当作改善证据。', '',
              '## 7. 指定其他 Case 的一键运行', '', '```bash',
              'python3 scripts/run-case.py run --input cocome.remodel --case CoCoME --output output/my-cocome-run',
              'python3 scripts/run-case.py run --input library.remodel --case Library --output output/my-library-run',
              '```', '',
              '真实服务密钥只从 `TYPESAFE_API_KEY` 读取；交互终端未设置时隐藏输入，不写入配置或报告。默认校准种子 11、正式种子 101/103/107/109/113/127/131/137/139/149；通过 `--calibration-seeds` 和 `--test-seeds` 指定列表。',
              '当前完整链路支持 UTF-8 `.remodel` 及 Java 源目录（原启发式提取＋AST 字段元数据）；`.dep` 尚需适配。Java 模式提取边界见输入审计，不能当作完整业务调用图。',
              f'完整参数与操作说明：{link(ROOT/"scripts/README-run-case.md")}。', '']
    report = output/'analysis.md'
    report.write_text('\n'.join(lines), encoding='utf-8')
    return report


def summarize_guidance(experiment, case, selector, test_seeds):
    """Read measured arms only; partial studies explicitly name the completed paired subset."""
    root = experiment/case/'online-jev'
    rows = []
    per_arm = {}
    for arm in ('off', 'numeric', 'jev'):
        directory = root if arm == 'off' else root/('guidance-'+arm)
        samples = []
        for seed in test_seeds:
            source = directory/f'run-{selector}-{seed}.json'
            run = read(source)
            if run['run']['config']['seed'] != seed or run['run']['completedGenerations'] != run['run']['config']['generations']:
                raise ValueError('Incomplete/mismatched nominal run: '+str(source))
            row = dict(arm=arm, seed=seed, sourceFile=str(source.resolve()), hv=run['hv'],
                       representative=run['representative'], evaluations=run['run']['evaluations'],
                       completedGenerations=run['run']['completedGenerations'], config=run['run']['config'],
                       guidance=run['run'].get('guidance'), onlineControl=run['run'].get('onlineControl'))
            rows.append(row)
            samples.append(row)
        per_arm[arm] = dict(samples=len(samples), meanHv=statistics.mean(r['hv'] for r in samples),
                           meanEvaluations=statistics.mean(r['evaluations'] for r in samples),
                           meanMetrics={name:statistics.mean(r['representative']['metrics'][i] for r in samples) for i,name in enumerate(METRICS)},
                           selectedCandidates=sum((r.get('guidance') or {}).get('selectedCount',0) for r in samples),
                           retainedCandidates=sum((r.get('guidance') or {}).get('retainedCount',0) for r in samples))
    return dict(seeds=test_seeds, samplesPerArm=len(test_seeds), rows=rows, perArm=per_arm,
                stoppingMode='nominal-generations',
                note='Only the explicitly listed completed paired seeds; independent state-bound generation, not equal evaluation/API budgets. No superiority claim.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['run','report'])
    p.add_argument('--input', type=Path, required=True)
    p.add_argument('--case')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--selector', choices=['jev','numeric','mock'], default='jev')
    p.add_argument('--profile', choices=['formal','smoke'], default='formal')
    p.add_argument('--candidate-config', type=Path, help='Explicit candidate table for smoke profile only')
    p.add_argument('--calibration-seeds', type=seeds, default=[11])
    p.add_argument('--test-seeds', type=seeds, default=None)
    p.add_argument('--compare', action='store_true', help='Run paired C0/numeric/selected validation arms')
    p.add_argument('--control', choices=['off','rule','jev','replay'], default=None)
    p.add_argument('--control-config', type=Path, help='JSON object containing onlineControl options')
    p.add_argument('--reuse-run-config', type=Path, help='Reuse a compatible existing experiment config; do not recalibrate or reselect')
    p.add_argument('--monitor-control', action='store_true', help='Collect identical monitoring with a fixed probability')
    p.add_argument('--replay-file', type=Path, help='Source control-decisions.jsonl for one seed')
    p.add_argument('--guidance', choices=['off','numeric','jev','replay'], default=None)
    p.add_argument('--guidance-config', type=Path, help='JSON guidance options; credentials only from environment')
    p.add_argument('--guidance-replay-file', type=Path)
    p.add_argument('--compare-guidance', action='store_true', help='Paired probability-only / numeric-guidance / JEV-guidance arms')
    p.add_argument('--compare-online', action='store_true', help='Run monitored off/rule/jev using the same initial choice and seeds')
    p.add_argument('--endpoint', default='https://api.jevgo.cn/v1/systemone')
    p.add_argument('--model', default='jev-1.14.0')
    p.add_argument('--experiment', type=Path, help='Existing experiment root, required in report mode')
    p.add_argument('--snapshot', type=Path, help='Existing model snapshot (report mode only)')
    p.add_argument('--skip-build', action='store_true')
    p.add_argument('--online-build', action='store_true', help='Allow Maven dependency downloads (default offline)')
    args = p.parse_args()
    requested_control, requested_guidance = args.control, args.guidance
    if args.candidate_config and args.profile != 'smoke':
        p.error('--candidate-config is only allowed with --profile smoke')
    args.control_options = read(args.control_config) if args.control_config else {}
    if 'onlineControl' in args.control_options:
        args.control_options = args.control_options['onlineControl']
    args.control = args.control or ('jev' if args.compare_online else args.control_options.get('mode','off'))
    args.monitor_control = args.monitor_control or args.control_options.get('monitor',False) or args.compare_online
    args.control_options.update(mode=args.control, monitor=args.monitor_control)
    args.control_options.setdefault('intervalGenerations', 10)
    args.guidance_options = read(args.guidance_config) if args.guidance_config else {}
    args.guidance_options = args.guidance_options.get('guidance', args.guidance_options)
    args.guidance = args.guidance or ('jev' if args.compare_guidance else args.guidance_options.get('mode','off'))
    args.guidance_options['mode'] = args.guidance
    if args.guidance_replay_file:
        args.guidance_options['replayFile'] = str(args.guidance_replay_file.resolve())
    elif args.guidance_options.get('replayFile') and args.guidance_config:
        args.guidance_options['replayFile'] = str((args.guidance_config.resolve().parent/args.guidance_options['replayFile']).resolve())
    if args.guidance == 'replay' and not args.guidance_options.get('replayFile'):
        p.error('guidance replay requires --guidance-replay-file')
    if args.guidance != 'off' and args.control_options['intervalGenerations'] != 10:
        p.error('Both mechanisms require the same 10-generation window')
    if args.compare_guidance:
        if args.compare or args.compare_online:
            p.error('Choose only one comparison mode')
        args.control = 'jev'
        args.control_options['mode'] = 'jev'
    if args.replay_file:
        args.control_options['replayFile'] = str(args.replay_file.resolve())
    elif args.control_options.get('replayFile'):
        args.control_options['replayFile'] = str((args.control_config.resolve().parent/args.control_options['replayFile']).resolve())
    if args.mode == 'run' and args.control == 'replay' and not args.control_options.get('replayFile'):
        p.error('--control replay requires --replay-file or replayFile in config')
    if args.compare and args.compare_online:
        p.error('Choose either --compare or --compare-online')
    args.input = args.input.resolve()
    args.case = args.case or args.input.stem
    if not args.input.is_dir() and (not args.input.is_file() or args.input.suffix != '.remodel'):
        p.error('Expected an existing .remodel source or Java directory')
    if not re.fullmatch('[A-Za-z0-9_-]+', args.case):
        p.error('Case ID must use letters, digits, underscore or hyphen')
    try:
        # Reject unsupported export syntax/encoding before expensive calibration or paid requests.
        if args.input.is_dir():
            if not list(args.input.rglob('*.java')):p.error('No Java source files')
        elif not remodel_entities(args.input.read_text(encoding='utf-8-sig')):
            p.error('No supported Entity declarations in source')
    except (ValueError, UnicodeError) as error:
        p.error('Source preflight failed (UTF-8 .remodel required): '+str(error))
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        p.error('Choose a new/empty output directory; previous reports are preserved')
    if args.mode == 'report' and not args.experiment:
        p.error('report requires --experiment')
    if args.mode == 'run' and (args.experiment or args.snapshot):
        p.error('--experiment/--snapshot are only for report mode')
    args.test_seeds = args.test_seeds or ([101,103,107,109,113,127,131,137,139,149] if args.mode=='run' else None)
    cfg = configuration(args, output) if args.mode=='run' else None
    if args.reuse_run_config:
        if args.mode != 'run' or args.control_config:
            p.error('--reuse-run-config requires run mode and cannot replace the stored control policy')
        cfg = read(args.reuse_run_config)
        if args.guidance_config:
            p.error('Reuse must preserve the stored guidance policy')
        args.guidance_options = cfg.get('guidance', {'mode':'off'})
        if requested_guidance is None:
            args.guidance = args.guidance_options.get('mode','off')
        if requested_control is None:
            args.control = cfg.get('onlineControl',{}).get('mode','off')
        args.monitor_control = args.monitor_control or cfg.get('onlineControl',{}).get('monitor',False)
        config_base = args.reuse_run_config.resolve().parent
        systems = [row for row in cfg['systems'] if row['id']==args.case]
        if len(systems)!=1 or (config_base/systems[0]['input']).resolve()!=args.input or cfg['selector']!=args.selector:
            p.error('Existing config case/input/selector differs from requested run')
        args.profile = cfg['profile']
        if set(cfg['calibrationSeeds']) & set(args.test_seeds):
            p.error('Formal seeds overlap existing calibration')
    elif args.mode == 'run' and (args.control == 'replay' or args.guidance == 'replay'):
        p.error('Replay requires --reuse-run-config to preserve the exact calibration binding')
    env = os.environ.copy()
    if args.mode == 'run' and ((args.selector == 'jev' and not args.reuse_run_config) or args.control == 'jev' or args.compare_online or args.guidance == 'jev') and not env.get('TYPESAFE_API_KEY'):
        if not sys.stdin.isatty():
            p.error('Set TYPESAFE_API_KEY in the runtime environment before invoking JEV')
        env['TYPESAFE_API_KEY'] = getpass.getpass('TYPESAFE_API_KEY (hidden, not saved): ')
        if not env['TYPESAFE_API_KEY'].strip():
            p.error('Empty API key')
    if args.mode == 'run' and args.guidance not in ('off','replay'):
        generator = args.guidance_options.get('generator', {})
        key_env = generator.get('apiKeyEnv', 'CHATFIRE_API_KEY')
        if not env.get(key_env):
            p.error('Set '+key_env+' in the runtime environment for the small-model generator')
    output.mkdir(parents=True, exist_ok=True)
    status = dict(case=args.case, input=str(args.input), mode=args.mode, stages=[], status='running')
    start = time.monotonic()

    def stage(name, command):
        print('['+name+']', flush=True)
        t = time.monotonic()
        log = output/'logs'/(name+'.log')
        log.parent.mkdir(exist_ok=True)
        row = dict(stage=name, log=str(log), status='running')
        status['stages'].append(row)
        write_json(output/'pipeline-status.json', status)
        with log.open('w') as stream:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
        row.update(status='success' if result.returncode==0 else 'failed', seconds=time.monotonic()-t)
        if result.returncode:
            raise RuntimeError(name+' failed; see '+str(log))

    try:
        snapshot = args.snapshot.resolve() if args.snapshot else output/'model-snapshot.json'
        if not args.skip_build and (args.mode == 'run' or not args.snapshot):
            command = ['mvn'] + ([] if args.online_build else ['-o']) + ['-q','-DskipTests','compile',
                'org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath','-DincludeScope=runtime','-Dmdep.outputFile=target/tuning-classpath.txt']
            stage('build', command)
        classpath = ROOT/'target/tuning-classpath.txt'
        if args.mode == 'run' or not args.snapshot:
            cp = str(ROOT/'target/classes')+os.pathsep+classpath.read_text().strip()
            java = ['java','-cp',cp]
        if args.mode == 'run':
            stage('snapshot',java+['tuning.MermSnapshot',str(args.input),str(snapshot)])
        if args.mode == 'run':
            configpath = args.reuse_run_config.resolve() if args.reuse_run_config else output/'run-config.json'
            write_json(output/'run-config.json', cfg)
            cli = java+['tuning.TuningCli']
            if not args.reuse_run_config:
                for name in ['calibrate','select']:
                    stage(name, cli+[name,str(configpath),'--system',args.case])
            if args.compare_guidance:
                stage('compare-guidance',cli+['compare-guidance',str(configpath),'--system',args.case,'--control','jev','--guidance',args.guidance])
            elif args.compare_online:
                stage('compare-online',cli+['compare-online',str(configpath),'--system',args.case])
            else:
                for seed in args.test_seeds:
                    stage('run-'+str(seed),cli+['run',str(configpath),'--system',args.case,'--seed',str(seed),'--control',args.control,'--guidance',args.guidance]+(['--monitor','true'] if args.monitor_control else [])+(['--replay-file',str(args.replay_file.resolve())] if args.replay_file else [])+(['--guidance-replay-file',str(args.guidance_replay_file.resolve())] if args.guidance_replay_file else []))
            if args.compare:
                stage('validate',cli+['validate',str(configpath),'--system',args.case])
            experiment = (configpath.parent/cfg['outputDirectory']).resolve() if args.reuse_run_config else output/'experiment'
        else:
            experiment = args.experiment.resolve()
            if args.test_seeds is None:
                result_directory = experiment/args.case
                if args.control != 'off' or args.monitor_control:
                    result_directory = result_directory/('online-'+args.control)
                if args.guidance != 'off':
                    result_directory = result_directory/('guidance-'+args.guidance)
                paths = result_directory.glob('run-'+args.selector+'-*.json')
                args.test_seeds = sorted({int(p.stem.split(args.selector+'-',1)[1]) for p in paths})
                if not args.test_seeds:
                    raise ValueError('No formal result files; provide --test-seeds or the correct experiment root')
        if not args.snapshot and args.mode != 'run':
            stage('snapshot',java+['tuning.MermSnapshot',str(args.input),str(snapshot)])
        print('[export-visualize-report]', flush=True)
        row = dict(stage='export-visualize-report', status='running')
        status['stages'].append(row)
        t = time.monotonic()
        try:
            if args.compare_guidance:
                write_json(output/'guidance-comparison.json', summarize_guidance(experiment, args.case, args.selector, args.test_seeds))
            report = publish_report(args, output, snapshot, experiment, args.test_seeds)
            if args.compare_guidance:
                for mode in ('off','numeric','jev'):
                    if mode == args.guidance:
                        continue
                    other = copy.copy(args)
                    other.guidance = mode
                    destination = output/'comparison-reports'/('guidance-'+mode)
                    destination.mkdir(parents=True, exist_ok=True)
                    publish_report(other, destination, snapshot, experiment, args.test_seeds)
            if args.compare_online:
                for mode in ('off','rule','jev'):
                    if mode == args.control:
                        continue
                    other = copy.copy(args)
                    other.control = mode
                    other.monitor_control = True
                    destination = output/'comparison-reports'/mode
                    destination.mkdir(parents=True, exist_ok=True)
                    publish_report(other, destination, snapshot, experiment, args.test_seeds)
            row['status'] = 'success'
        except Exception:
            row['status'] = 'failed'
            raise
        finally:
            row['seconds'] = time.monotonic()-t
        status.update(status='success', report=str(report))
        print('REPORT '+str(report),flush=True)
    except Exception as error:
        status.update(status='failed', error=str(error))
        raise
    finally:
        status['elapsedSeconds'] = time.monotonic()-start
        write_json(output/'pipeline-status.json',status)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError, KeyError) as error:
        sys.exit('Case pipeline: '+str(error))
