#!/usr/bin/env python3
"""Publish a fresh, paired monitored-static/JEV comparison without running search."""
import argparse
import importlib.util
import json
import statistics
import sys
from pathlib import Path
from types import SimpleNamespace


ARMS = ('off', 'jev')
LABELS = {'off': '静态参数（同等监测）', 'jev': 'JEV 在线历史控制'}
# RunResult stores CMQ before Instability/UCM; presentation keeps the familiar order.
METRICS = [('IFN', 0, -1), ('CHD', 1, 1), ('Instability', 3, 1),
           ('UCM', 4, 1), ('CMQ', 2, 1)]


def _read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def _link(path, title=None):
    return '[' + (title or Path(path).name) + '](<' + str(Path(path).resolve()) + '>)'


def _fmt(value):
    return '未取得' if value is None else format(value, '.12g') if isinstance(value, (int, float)) else str(value)


def _table(headers, rows):
    return ['|' + '|'.join(headers) + '|', '|' + '|'.join(['---'] * len(headers)) + '|'] + [
        '|' + '|'.join(_fmt(value) for value in row) + '|' for row in rows]


def _publisher():
    scripts = Path(__file__).resolve().parent
    if str(scripts) not in sys.path:
        sys.path.insert(0, str(scripts))
    spec = importlib.util.spec_from_file_location('unifyms_case_report', scripts/'run-case.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _status(delta, direction=1):
    return '持平' if abs(delta) < 1e-12 else '改善' if delta * direction > 0 else '退化'


def generate(output, case, input_path, seeds):
    """Return output/analysis.md; retain each run, export and detailed arm report.

    Inputs are completed Worker/Quality results in the new output directory.
    This function never calls JEV, runs NSGA-II, or reads credentials.
    """
    output, input_path = Path(output).resolve(), Path(input_path).resolve()
    seeds = list(seeds)
    if not seeds or len(seeds) != len(set(seeds)):
        raise ValueError('Comparison requires a nonempty list of unique seeds')
    manifest = _read(output/'repeat-manifest.json')
    if manifest.get('arms') != list(ARMS):
        raise ValueError('This report requires exactly the off and jev arms')
    if manifest.get('seeds') != seeds:
        raise ValueError('Requested seeds differ from repeat-manifest.json')
    experiment = output/'experiment'
    directory = experiment/case
    calibration = _read(directory/'calibration.json')
    selection = _read(directory/'selection-jev.json')
    if manifest.get('initialChoice') != selection.get('choice'):
        raise ValueError('Frozen initial choice differs from the repeat manifest')
    runs, paths, decisions = {}, {}, {}
    for seed in seeds:
        if seed in calibration['calibrationSeeds']:
            raise ValueError('Formal seed overlaps the frozen calibration seeds')
        for arm in ARMS:
            path = directory/('online-'+arm)/f'run-jev-{seed}.json'
            row = _read(path)
            run, online = row['run'], row['run'].get('onlineControl', {})
            if run.get('status') != 'success' or online.get('mode') != arm:
                raise ValueError('Incomplete or wrong-arm result: ' + str(path))
            if run['config']['seed'] != seed or run['config']['id'] != selection['choice']:
                raise ValueError('Result uses a different seed/initial configuration')
            if run['inputHash'] != calibration['inputHash']:
                raise ValueError('Result input differs from frozen calibration')
            if arm == 'off' and online.get('requestCount') != 0:
                raise ValueError('Monitored static arm must not call JEV')
            audit = Path(online['auditDirectory']).resolve()
            if output not in audit.parents:
                raise ValueError('Result points outside the fresh run audit directory')
            records = [json.loads(line) for line in (audit/'control-decisions.jsonl').read_text().splitlines() if line.strip()]
            if len(records) != online['decisionCount']:
                raise ValueError('Decision log count differs from result summary')
            runs[arm, seed], paths[arm, seed], decisions[arm, seed] = row, path, records
        left, right = [runs[arm, seed]['run'] for arm in ARMS]
        if left['config'] != right['config'] or left['evaluations'] != right['evaluations']:
            raise ValueError('Paired arms do not share initial parameters and evaluation budget')

    publish = _publisher()
    for arm in ARMS:
        destination = output/'reports'/arm
        destination.mkdir(parents=True, exist_ok=True)
        args = SimpleNamespace(case=case, selector='jev', input=input_path,
                               control=arm, monitor_control=True)
        report = publish.publish_report(args, destination, output/'model-snapshot.json', experiment, seeds)
        content = report.read_text(encoding='utf-8')
        title, rest = content.split('\n', 1)
        content = title + '\n\n> 本报告的离线校准、初始配置选择和归一化沿用已冻结记录；本次重新执行正式搜索。离线 JEV 新增调用为 0，历史选择成本不计入本次成本。\n' + rest
        content = content.replace('本次选择记录：总 tokens', '沿用的离线选择历史记录（不计入本次调用成本）：总 tokens')
        report.write_text(content, encoding='utf-8')

    means = {arm: {
        'metrics': {name: statistics.mean(runs[arm, s]['representative']['metrics'][index] for s in seeds)
                    for name, index, _ in METRICS},
        'hv': statistics.mean(runs[arm, s]['hv'] for s in seeds),
        'services': statistics.mean(runs[arm, s]['representative']['serviceCount'] for s in seeds),
    } for arm in ARMS}
    hv_delta = means['jev']['hv'] - means['off']['hv']
    changes = [(name, means['jev']['metrics'][name] - means['off']['metrics'][name], direction)
               for name, _, direction in METRICS]
    improvements = [name for name, delta, direction in changes if _status(delta, direction) == '改善']
    regressions = [name for name, delta, direction in changes if _status(delta, direction) == '退化']
    cfg = runs['off', seeds[0]]['run']['config']
    total_requests = sum(runs['jev', s]['run']['onlineControl']['requestCount'] for s in seeds)
    fallbacks = sum(runs['jev', s]['run']['onlineControl']['fallbackCount'] for s in seeds)
    response_ms = sum(runs['jev', s]['run']['onlineControl'].get('totalResponseMillis', 0) for s in seeds)
    tokens = [runs['jev', s]['run']['onlineControl'].get('totalTokens') for s in seeds]
    total_tokens = sum(tokens) if all(value is not None for value in tokens) else None
    lines = [f'# {case} 正式预算重新对比：静态参数与 JEV 在线控制', '',
             f'本次仅比较 **静态参数（off＋监测）** 与 **JEV 在线历史控制**。两组使用相同初始配置 **{selection["choice"]}**、种子 `{seeds}`、冻结归一化和评价预算，正式搜索结果保存在新的输出目录。', '',
             f'JEV 相对静态的平均解集 HV 差值为 **{_fmt(hv_delta)}（{_status(hv_delta)}）**。代表方案五指标改善：**{"、".join(improvements) or "无"}**；退化：**{"、".join(regressions) or "无"}**。', '',
             '## 1. 实验设置与冻结证据', '',
             f'初始配置：N={cfg["populationSize"]}，名义 G={cfg["generations"]}，初始 p_var={cfg["crossoverProbability"]}，crossP={cfg["crossP"]}。统一评价预算 **{calibration["evaluationBudget"]:,}**；校准种子 `{calibration["calibrationSeeds"]}`。',
             '两组均逐代计算新颖率与 Gain，使监测开销口径一致。静态组始终保持初始概率；在线组在窗口结束且仍有后续代时调用 JEV，选择 INCREASE / KEEP / DECREASE，仅影响后续子代。',
             '本次沿用原离线 JEV 的 C0–C5 选择及其原始证据，**不重新调用离线选参**。新运行通过新的 taskKey、请求文件和审计目录执行；旧正式结果不作为本次搜索缓存。',
             f'原始配置来源：{_link(manifest["sourceConfig"])}；原实验清单：{_link(manifest["sourceManifest"])}。',
             f'冻结校准 SHA-256：`{manifest["calibrationSha256"]}`。本次运行清单：{_link(output/"repeat-manifest.json")}。', '',
             '## 2. 正式预算验证结果', '',
             'IFN 越小越好；CHD、Instability、UCM、CMQ 按当前实现越大越好。Instability 采用当前 provided/(required+provided) 口径。五指标与服务数来自按既定规则选出的代表方案，HV 衡量整组有效非支配方案。', '']
    table_rows = []
    for seed in seeds:
        for arm in ARMS:
            row = runs[arm, seed]
            rep = row['representative']
            table_rows.append([LABELS[arm], seed, rep['serviceCount']] + [rep['metrics'][i] for _, i, _ in METRICS] + [row['hv']])
    lines += _table(['方法', '种子', 'MS_Num', 'IFN ↓', 'CHD ↑', 'Instability ↑', 'UCM ↑', 'CMQ ↑', 'HV ↑'], table_rows)
    if len(seeds) > 1:
        lines += ['', '### 多种子均值', ''] + _table(
            ['方法', '样本量', '平均 MS_Num', 'IFN ↓', 'CHD ↑', 'Instability ↑', 'UCM ↑', 'CMQ ↑', 'HV ↑'],
            [[LABELS[arm], len(seeds), means[arm]['services']] + [means[arm]['metrics'][name] for name, _, _ in METRICS] + [means[arm]['hv']] for arm in ARMS])
    lines += ['', '差值均为 **JEV − 静态**：', ''] + _table(
        ['指标', '静态均值', 'JEV 均值', '差值', '按指标方向判断'],
        [[name, means['off']['metrics'][name], means['jev']['metrics'][name], delta, _status(delta, direction)] for name, delta, direction in changes] +
        [['HV', means['off']['hv'], means['jev']['hv'], hv_delta, _status(hv_delta)]])
    lines += ['', '### 实际预算与时间', '',
              '下表为各阶段实测墙钟时间；搜索计算时间为搜索循环总时间扣除已记录的监测、控制与审计时间，不是 CPU 时间。Worker 总耗时还包括模型解析与结果处理。', '']
    timing_rows = []
    for seed in seeds:
        for arm in ARMS:
            run = runs[arm, seed]['run']
            timing_rows.append([LABELS[arm], seed, run['completedGenerations'], run['evaluations']] +
                               [run.get(k, 0)/1000 for k in ['workerMillis', 'searchMillis', 'searchComputeMillis', 'monitoringMillis', 'controlMillis', 'auditMillis']])
    lines += _table(['方法', '种子', '完成代数', '评价次数', 'Worker 秒', '搜索总秒', '搜索计算秒', '监测秒', '控制秒', '审计秒'], timing_rows)
    lines += ['', '## 3. 本次在线 JEV 的实际动作与调用成本', '',
              f'本次离线 JEV 新增调用 **0** 次；在线 JEV 请求 **{total_requests}** 次；回退 **{fallbacks}** 次；在线总响应时间 **{_fmt(response_ms/1000)} 秒**；在线总 tokens **{_fmt(total_tokens)}**。',
              '响应未返回完整 usage 时，总 tokens 保持“未取得”，不能记为 0。总响应时间包括本次在线失败请求；历史离线选参费用与耗时不叠加到本次。', '']
    actions = []
    for seed in seeds:
        online = runs['jev', seed]['run']['onlineControl']
        audit = Path(online['auditDirectory'])
        for decision in decisions['jev', seed]:
            window = decision['evidence']['currentWindow']
            actions.append([seed, decision['generation'], decision['effectiveGeneration'], window.get('meanNovelty'),
                            window.get('meanGain'), decision['action'], decision['probabilityBefore'], decision['probabilityAfter']])
        lines += [f'seed {seed}：初始概率 {_fmt(cfg["crossoverProbability"])} → 最终概率 {_fmt(online["finalProbability"])}；状态 `{online["status"]}`。'+
                  _link(audit/'control-analysis.md', '窗口分析')+'；'+_link(audit/'probability-trajectory.svg', '概率轨迹')+'；'+
                  _link(audit/'control-decisions.jsonl', '完整决策与证据')+'。', '']
    lines += _table(['种子', '决策代', '生效代', '窗口平均新颖率', '窗口平均 Gain', '实际动作', '调整前 p_var', '调整后 p_var'], actions)
    lines += ['', 'JEV 接口返回实际选择、概率和置信度；接口没有提供文字理由。窗口新颖率、Gain 和历史动作是程序提交的证据，不能由输出动作倒推出 JEV 的内部推理。',
              'Gain = HV(ND(P∪Q)) − HV(ND(P))，在环境选择截断之前计算，使用冻结归一化和参考点。Gain 表示新增覆盖，不等同于下一代保留解集的 HV 增长。', '',
              '## 4. 输出文件与拆分图', '',
              '两组与每个种子的原始输出、代表方案 `.req` 和拆分图分别保存。', '']
    outputs = []
    for seed in seeds:
        for arm in ARMS:
            vis = output/'reports'/arm/'visualizations'/f'seed-{seed}'
            outputs.append([LABELS[arm], seed, _link(paths[arm, seed], '正式结果 JSON'),
                            _link(vis/(input_path.stem+'.req'), 'MERM .req'),
                            _link(vis/(input_path.stem+'-merm.html'), '离线拆分图'),
                            _link(vis/(input_path.stem+'-usecases.svg'), '用例 SVG'),
                            _link(vis/(input_path.stem+'-domain.svg'), '实体接口 SVG')])
    lines += _table(['方法', '种子', '原始结果', '模型', '交互查看', '用例图', '实体接口图'], outputs)
    lines += ['', '打开对应 HTML，在顶部切换“用例图”和“实体与接口图”；支持缩放、拖动、搜索、服务聚焦和接口明细。文件完全离线，无需 Eclipse。两个 SVG 可直接用于排版。',
              '完整分模式分析：'+_link(output/'reports/off/analysis.md', '静态参数报告')+'；'+_link(output/'reports/jev/analysis.md', 'JEV 在线控制报告')+'。', '',
              '## 5. 结果解释范围', '']
    if len(seeds) == 1:
        lines += [f'本次是 **{case}、种子 {seeds[0]} 的一次正式预算配对验证**。没有多种子方差或置信区间，不能据此声称稳定性或统计显著改善。']
    else:
        lines += [f'本次有 {len(seeds)} 个配对种子；主表均值是描述性统计，未把单次最优值当作整体效果。']
    if fallbacks:
        lines += [f'在线控制存在 {fallbacks} 次回退，实际执行包含 KEEP 回退动作，解读时应同时检查调用审计。']
    if hv_delta <= 1e-12:
        lines += ['本次在线 JEV 未提高平均最终解集 HV；代表方案各项改善和退化见上表，不能据此声称整体拆分质量提升。']
    else:
        lines += ['本次在线 JEV 的平均最终解集 HV 高于静态组；代表方案各指标仍须分别检查，且该结果不能自动推广到其他种子或 Case。']
    lines += ['本次范围为静态参数与在线 JEV 两组；不评价其他控制器。五指标公式、代表方案选择规则、初始化和 HV 算法沿用现有实现。', '']
    report = output/'analysis.md'
    report.write_text('\n'.join(lines), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', required=True)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--seeds', required=True)
    args = parser.parse_args()
    print(generate(args.output, args.case, args.input, [int(s) for s in args.seeds.split(',')]))
