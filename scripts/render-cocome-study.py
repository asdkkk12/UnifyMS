#!/usr/bin/env python3
"""Render a measured CoCoME run; never fills missing JEV output with a synthetic choice."""
import argparse,datetime,html,json,math
from zoneinfo import ZoneInfo
from pathlib import Path


def read(path):
    return json.loads(path.read_text())

def dump(path,obj):
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def esc(value):
    return html.escape(str(value),quote=True)

def fmt(value):
    return '未取得' if value is None else format(value,'.10g') if isinstance(value,(int,float)) else str(value)

def svg_view(model):
    services=model['services']; colors=['#2563eb','#0f766e','#7c3aed','#c2410c','#be185d','#4d7c0f']
    cols=min(3,max(1,len(services))); width=max(1060,cols*480+60)
    positions={}; y=220; cards=[]
    for start in range(0,len(services),cols):
        group=services[start:start+cols]; row_height=max(270, max(140+23*(len(s['useCases'])+len(s['entities'])) for s in group))
        for col,s in enumerate(group):
            x=30+col*(width-60)/cols; w=(width-60)/cols-26
            positions[s['id']]=(x,y,w,row_height)
            color=colors[s['id']%len(colors)]
            text=f'<g data-service="{s["id"]}" tabindex="0"><rect x="{x}" y="{y}" width="{w}" height="{row_height}" rx="12" fill="#fff" stroke="{color}" stroke-width="2"/>'
            text+=f'<rect x="{x}" y="{y}" width="{w}" height="46" rx="10" fill="{color}"/><text x="{x+16}" y="{y+29}" fill="white" font-size="20" font-weight="bold">MS{s["id"]+1} · {len(s["systemOperations"])} 个系统操作</text>'
            ty=y+73
            text+=f'<text x="{x+16}" y="{ty}" fill="{color}" font-weight="bold">用例 / Use cases</text>';ty+=24
            for uc in s['useCases']:
                text+=f'<text x="{x+22}" y="{ty}" font-size="14">{esc(uc)}</text>';ty+=23
            ty+=8;text+=f'<text x="{x+16}" y="{ty}" fill="{color}" font-weight="bold">实体 / Entities</text>';ty+=24
            for entity in s['entities']:
                text+=f'<text x="{x+22}" y="{ty}" font-size="14">{esc(entity)}</text>';ty+=23
            text+='</g>';cards.append(text)
        y+=row_height+100
    edges=model['interfaces']; bottom=y+max(140,len(edges)*22)
    head=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {bottom}" role="img" aria-label="CoCoME MERM 视图"><defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="#64748b"/></marker></defs><rect width="100%" height="100%" fill="#f1f5f9"/><g font-family="PingFang SC,Microsoft YaHei,Arial,sans-serif" fill="#0f172a" font-size="16"><text x="30" y="42" font-size="26" font-weight="bold">CoCoME · MERM 服务边界与接口视图</text><text x="30" y="74" fill="#475569">{esc(model["provenance"])} · seed {model["seed"]} · {len(services)} 个服务</text>'
    head+='<text x="30" y="108" font-size="14" fill="#475569">框内展示用例和实体；跨服务箭头表示需求接口 → 提供该实体的服务。编号是分组标签，不是业务命名。</text>'
    actor_text='；'.join(a+': '+', '.join(ucs) for a,ucs in model['actors'].items())
    # Actor membership is fully listed in HTML; keep the overview legible.
    head+=f'<text x="30" y="148" font-size="15">参与者：{esc(" / ".join(model["actors"]))}；参与者到用例的关联见下方交互明细。</text>'
    lines=[]
    for i,edge in enumerate(edges):
        a=positions[edge['consumer']]; b=positions[edge['provider']]
        sx=a[0]+a[2]*.55; sy=a[1]+a[3];tx=b[0]+b[2]*.45;ty=b[1]+b[3]
        rail=y+i*20
        lines.append(f'<g class="edge" data-consumer="{edge["consumer"]}" data-provider="{edge["provider"]}"><path d="M {sx} {sy} V {rail} H {tx} V {ty}" fill="none" stroke="#64748b" stroke-width="1.5" marker-end="url(#arrow)" opacity=".65"/><text x="30" y="{rail+16}" font-size="13" fill="#475569">MS{edge["consumer"]+1} → MS{edge["provider"]+1}：{esc(edge["entity"])}</text></g>')
    if not edges:
        lines.append(f'<text x="30" y="{y+10}" fill="#475569">此代表方案没有跨服务实体接口；这不表示真实部署无需通信或单体方案已具备微服务价值。</text>')
    return head+''.join(lines)+''.join(cards)+'</g></svg>'

def make_model(run,snapshot,provenance):
    rep=run['representative'];services=[];owners={}
    for service in rep['services']:
        s=dict(service);s['systemOperations']=sorted({op for uc in s['useCases'] for op in snapshot['useCaseOperations'].get(uc,[])})
        services.append(s)
        for entity in s['entities']:owners[entity]=s['id']
    edges=[]
    for s in services:
        for entity in s['requiredEntityInterfaces']:
            if entity not in owners:raise ValueError('Interface has no entity owner')
            if owners[entity]==s['id']:raise ValueError('Unexpected self interface')
            if entity not in services[owners[entity]]['providedEntityInterfaces']:raise ValueError('Unmatched provided interface')
            edges.append(dict(consumer=s['id'],provider=owners[entity],entity=entity))
    return dict(kind='MERM-derived-view',provenance=provenance,seed=run['run']['config']['seed'],inputHash=snapshot['inputHash'],
        metricVersion=run['run']['metricVersion'],taskKey=run['run']['taskKey'],configId=run['run']['config']['id'],services=services,interfaces=edges,actors=snapshot['actors'],
        useCaseOperations=snapshot['useCaseOperations'],operationEntities=snapshot['operationEntities'],
        entityNames=snapshot['entityNames'],entityRelationMatrix=snapshot['entityRelationMatrix'],
        note='Derived from the selected chromosome and current parser. JSON/SVG/HTML are visualization artifacts, not an EMF .req serialization.')

def render(output,run,snapshot,provenance):
    model=make_model(run,snapshot,provenance);dump(output/'cocome-merm.json',model)
    svg=svg_view(model);(output/'cocome-merm.svg').write_text(svg,encoding='utf-8')
    sections=[]
    for s in model['services']:
        operations=''.join('<li>'+esc(op)+' → '+esc(', '.join(snapshot['operationEntities'].get(op,[])))+'</li>' for op in s['systemOperations'])
        sections.append(f'<section data-detail="{s["id"]}"><h2>MS{s["id"]+1}</h2><p>用例：{esc(", ".join(s["useCases"]))}</p><p>实体：{esc(", ".join(s["entities"])) or "无"}</p><p>提供实体接口：{esc(", ".join(s["providedEntityInterfaces"])) or "无"}</p><p>需求实体接口：{esc(", ".join(s["requiredEntityInterfaces"])) or "无"}</p><details><summary>系统操作及其涉及实体（{len(s["systemOperations"])} 项）</summary><ul>{operations}</ul></details></section>')
    metrics=run['representative']['metrics'];names=['IFN ↓','CHD ↑','CMQ ↑','Instability（按代码最大化）↑','UCM ↑']
    table=''.join(f'<tr><th>{esc(name)}</th><td>{fmt(value)}</td></tr>' for name,value in zip(names,metrics))
    table+=f'<tr><th>解集五维 HV ↑</th><td>{fmt(run["hv"])}</td></tr>'
    buttons='<button data-filter="all">全部服务</button>'+''.join(f'<button data-filter="{s["id"]}">MS{s["id"]+1}</button>' for s in model['services'])
    actors=''.join('<li>'+esc(actor)+': '+esc(', '.join(ucs))+'</li>' for actor,ucs in model['actors'].items())
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CoCoME MERM</title><style>body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;max-width:1500px;margin:24px auto;padding:0 20px;color:#0f172a;background:#f8fafc}h1{font-size:28px}svg{width:100%;height:auto}section,article{background:white;border:1px solid #cbd5e1;border-radius:12px;padding:18px;margin:16px 0}button{padding:9px 16px;margin:5px;border:1px solid #94a3b8;border-radius:7px;cursor:pointer;background:white}table{border-collapse:collapse}th,td{padding:8px 20px;border:1px solid #cbd5e1;text-align:left}summary{cursor:pointer}li{overflow-wrap:anywhere}.note{color:#475569}@media print{button{display:none}section{break-inside:avoid}}</style><h1>CoCoME 拆分结果与 MERM 可视化</h1>'''
    page+=f'<p>{esc(provenance)}。此页面完全离线可用；按钮可聚焦服务，操作详情可展开。</p><article><table>{table}</table></article><nav>{buttons}</nav>'+svg+''.join(sections)+'<article><h2>参与者与用例</h2><ul>'+actors+'</ul><p class="note">跨服务边来自现有接口推导规则，不包含程序尚未建模的时序行为、事务一致性和网络协议。MERM 可视化不等同于部署验证。此文件不是 EMF/Sirius 编辑器的 .req 文件。</p></article>'
    page+='''<script>document.querySelectorAll('[data-filter]').forEach(button=>button.addEventListener('click',()=>{const id=button.dataset.filter;document.querySelectorAll('[data-detail]').forEach(s=>s.hidden=id!=='all'&&s.dataset.detail!==id);document.querySelectorAll('[data-service]').forEach(s=>s.style.opacity=id==='all'||s.dataset.service===id?'1':'.2');document.querySelectorAll('.edge').forEach(s=>s.style.opacity=id==='all'||s.dataset.consumer===id||s.dataset.provider===id?'1':'.1')}));document.querySelectorAll('[data-service]').forEach(s=>s.addEventListener('click',()=>document.querySelector('[data-filter="'+s.dataset.service+'"]').click()));</script></html>'''
    (output/'cocome-merm.html').write_text(page,encoding='utf-8')
    mmd=['flowchart LR']
    for s in model['services']:
        mmd.extend([f'  subgraph MS{s["id"]+1}["MS{s["id"]+1}"]',f'    U{s["id"]}["用例：'+ '<br/>'.join(s['useCases'])+'"]',f'    E{s["id"]}["实体：'+'<br/>'.join(s['entities'])+'"]','  end'])
    for e in model['interfaces']:mmd.append(f'  U{e["consumer"]} -->|"{e["entity"]}"| E{e["provider"]}')
    (output/'cocome-merm.mmd').write_text('\n'.join(mmd)+'\n',encoding='utf-8')
    return model


def main():
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--backend',choices=['jev','numeric'],default='jev');parser.add_argument('--seed',type=int,default=101);parser.add_argument('--previous-study',type=Path)
    args=parser.parse_args();out=args.directory.resolve();d=out/'experiment'/'CoCoME'
    cal=read(d/'calibration.json');selection_path=d/f'selection-{args.backend}.json';selection=read(selection_path) if selection_path.exists() else None
    run_path=d/f'run-{args.backend}-{args.seed}.json';run=read(run_path) if run_path.exists() else None
    if args.backend=='jev' and (not selection or selection.get('status')!='success'):raise SystemExit('No successful real JEV selection. Refusing to fabricate a choice.')
    snapshot=read(out/'model-snapshot.json')
    if cal['inputHash']!=snapshot['inputHash']:raise ValueError('Snapshot input mismatch')
    generated=datetime.datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds')
    lines=['# CoCoME：JEV 辅助 UnifyMS 运行结果分析','', '## 1. 结论与运行身份','',f'报告生成时间：`{generated}`（Asia/Shanghai）。目录日期为本次运行的开始日期。','']
    real=args.backend=='jev';choice=selection['choice'] if selection else None
    policy=selection.get('selectionPolicyVersion','legacy-hv-emphasis-v1') if selection else None
    if real:
        lines+= [f'真实 JEV 选择 **{choice}**；正式搜索使用独立种子 **{args.seed}**。',f'请求模型：`{selection.get("requestedModel")}`；实际响应模型：`{selection.get("actualModel")}`。',f'调用地址：`{read(out/"experiment"/"manifest.json")["jev"]["endpoint"]}`。',f'选择规则版本：`{policy}`。','']
    else:
        lines+= ['**真实 JEV 选择未完成：所提供密钥调用官方 API 返回 HTTP 401（authentication_error）。因此不能报告 JEV 选择了哪一组或捏造其原因。**',f'以下正式搜索结果属于数值基线 **{choice}**，不是 JEV 的选择；保留完整校准数据，获得有效密钥后可继续选择。','']
    if run:
        values=run['representative']['metrics']
        lines += ['正式结果摘要：'+ '；'.join(name+'='+fmt(value) for name,value in zip(['IFN','CHD','CMQ','Instability','UCM'],values))+f'；解集 HV={fmt(run["hv"])}；代表方案 {run["representative"]["serviceCount"]} 个服务。','']
    lines+= ['本次使用正式 C0–C5 配置，校准种子 `[11]`，不是先前 smoke 结果。仅 IFN 改为接口总数除以服务数量，其余四项沿用代码，不能将该口径描述为全面复现论文公式。',f'输入：`cocome.remodel`；SHA-256：`{cal["inputHash"]}`。',f'模型结构：{snapshot["structure"]["useCases"]} 个用例、{snapshot["structure"]["entities"]} 个实体、{snapshot["structure"]["actors"]} 个参与者、{snapshot["structure"]["operations"]} 个操作映射。',f'统一评价上限：**{cal["evaluationBudget"]:,} 次目标向量评价**；实际代数依完整代预算而定。','', '## 2. 六组校准结果','', '|配置|N|名义 G|p_c|crossP|实际代数|评价次数|校准 HV|代表 IFN|CHD|CMQ|Instability|UCM|服务数|搜索耗时/s|','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    ordered=sorted(cal['candidates']);ranking=sorted(ordered,key=lambda cid:(-cal['candidates'][cid]['meanHv'],cid))
    for cid in ordered:
        entry=cal['candidates'][cid];c=entry['config'];t=entry['trials'][0];rep=t['representative']
        values=[cid,c['populationSize'],c['generations'],c['crossoverProbability'],c['crossP'],t['completedGenerations'],t['evaluations'],t['hv']]+rep['metrics']+[rep['serviceCount'],t['searchMillis']/1000]
        lines.append('|'+ '|'.join(fmt(v) for v in values)+'|')
    lines+= ['', '表中五指标属于各配置按统一规则选出的代表方案；HV 属于整个有效非支配解集，不能称为该代表方案自身的 HV。单校准种子不能估计结果稳定性。','', '## 3. 配置选择及原因分析','',f'程序数值基线按平均校准 HV 选择 **{ranking[0]}**；HV 排序为：'+ ' > '.join(ranking)+'（相等值按编号排序）。']
    if real:
        lines+=[f'JEV 的实际输出：`choice={choice}`，`confidence={fmt(selection.get("confidence"))}`。',f'选项分布：`{json.dumps(selection.get("probabilities"),ensure_ascii=False)}`。',f'JEV 所选配置的校准 HV 排名为 **{ranking.index(choice)+1}/6**。']
        if policy=='five-metric-hv-v2':
            lines+=['本次提示词要求同等关注代表方案 IFN、CHD、CMQ、Instability、UCM，以解集 HV 补充覆盖评价；不预设加权总分、优先级或改善数量投票。程序提供名称、定义、方向、冻结归一化值及 C0 与候选两两对照。',
                'JEV Choice 未返回自然语言理由，不能确认它对各指标采用了什么隐含权重；以下说明是基于实际校准数据的权衡分析，不是读取到的模型推理。']
            state=selection['request']['state']
            lines+=['本次发送格式：`'+state.get('evidenceFormatVersion','full-evidence')+'`。指标定义和上下界只共享一次，原始值、归一化值、HV 和全部候选对照仍保留；完整前沿和完整证据保存在本地。']
            lines+=['','### 指标定义与方向','','|指标|当前代码定义|原始方向|','|---|---|---|']
            for definition in state['metricDefinitions']:
                lines.append('|'+definition['name']+'|'+definition['definition']+'|'+('越小越好' if definition['rawDirection']=='minimize' else '越大越好')+'|')
            lines+=['','### 归一化的代表方案指标（normalizedLoss，均越小越好）','','|配置|IFN|CHD|CMQ|Instability|UCM|','|---|---:|---:|---:|---:|---:|']
            for cid in ordered:
                values=state['candidates'][cid]['meanRepresentativeMetrics']
                lines.append('|'+cid+'|'+'|'.join(fmt(values[name]['normalizedLoss']) for name in ['IFN','CHD','CMQ','Instability','UCM'])+'|')
            if choice==ranking[0]:
                lines+=['',f'本次综合选择与最大 HV 数值基线均为 {choice}。这只说明本次结果一致，不能据此证明 JEV 有额外收益，也不能反推它只看了 HV。']
            else:
                lines+=['',f'本次 JEV 选择 {choice}，最大 HV 数值基线选择 {ranking[0]}。二者不同；应结合下面的五指标得失和独立测试判断，不能把不同选择自动解释成更好。']
            lines+=['','### 所选配置相对其余候选的校准权衡','','差值为“所选配置 − 对照”，改善／退化按各指标方向由程序判断。','','|对照|IFN Δ|CHD Δ|CMQ Δ|Instability Δ|UCM Δ|HV Δ|','|---|---|---|---|---|---|---|']
            labels={'improved':'改善','degraded':'退化','unchanged':'持平'}
            for comparison in selection['programExplanation']['comparisons']:
                cells=[comparison['baseline']]
                for name in ['IFN','CHD','CMQ','Instability','UCM']:
                    item=comparison['metrics'][name];cells.append(fmt(item['rawDelta'])+' '+labels[item['status']])
                item=comparison['hv'];cells.append(fmt(item['rawDelta'])+' '+labels[item['status']])
                lines.append('|'+'|'.join(cells)+'|')
            lines+=['','完整逐种子对照及上下界见 [本次 JEV 选择证据](experiment/CoCoME/selection-jev.json.evidence.json)；程序生成的选参分析见 [选择分析](experiment/CoCoME/selection-jev.json.analysis.md)。']
        elif choice==ranking[0]:
            lines+=[f'该选择与校准 HV 最大的数值基线一致：比 C0 的校准 HV 高 **{(cal["candidates"][choice]["meanHv"]/cal["candidates"]["C0"]["meanHv"]-1)*100:.4f}%**。这不是独立测试改善的证据。']
        else:
            lines+=['该选择没有最大化此次校准 HV。接口未说明其文字理由，需根据五指标和独立结果评价。']
        baseline_metrics=cal['candidates']['C0']['trials'][0]['representative']['metrics']
        chosen_metrics=cal['candidates'][choice]['trials'][0]['representative']['metrics']
        differences=[a-b for a,b in zip(chosen_metrics,baseline_metrics)]
        lines+=['', '代表方案的逐指标变化也说明选参是折中：', '', '|校准代表方案指标|C0|JEV 所选配置|差值（所选 − C0）|', '|---|---:|---:|---:|']
        for name,a,b,difference in zip(['IFN ↓','CHD ↑','CMQ ↑','Instability（按代码）↑','UCM ↑'],baseline_metrics,chosen_metrics,differences):
            lines.append('|'+name+'|'+fmt(a)+'|'+fmt(b)+'|'+fmt(difference)+'|')
        lines+=['', '不能把最高解集 HV 理解为所选代表方案的每一项指标都最好；以上变化来自校准种子，不能代替独立种子的效果比较。']
        c=cal['candidates'][choice]['config'];lines+=[f'参数作用可解释为：种群规模 N={c["populationSize"]} 决定同时保留的候选数量；在固定评价预算下影响可完成代数；p_c={c["crossoverProbability"]} 决定交叉触发频率，crossP={c["crossP"]} 决定触发后分配调整频率。', '**以上是根据参数定义和校准记录作出的证据分析，不是 JEV 返回的文字理由。** 当前 Choice 接口返回选项、分布和置信度，不返回自然语言推理链。置信度也不是测试改善概率。']
    else:lines+=['HTTP 401 是认证失败；本次没有真实 JEV Choice 响应，所以选择理由和分布均为“未取得”。数值选择的理由是校准平均 HV 最大，不能替 JEV 作出相同声明。']
    if run:
        provenance=f'真实 JEV 选择 {choice}' if real else f'数值基线选择 {choice}（JEV 认证未通过）'
        model=render(out,run,snapshot,provenance);cfg=run['run']['config'];rep=run['representative']
        model['selectionPolicyVersion']=policy;dump(out/'cocome-merm.json',model)
        lines+= ['', '## 4. 独立种子的正式搜索结果','',f'配置 **{cfg["id"]}**；种子 **{cfg["seed"]}**；实际代数 **{run["run"]["completedGenerations"]}**；评价次数 **{run["run"]["evaluations"]:,}**；搜索耗时 **{run["run"]["searchMillis"]/1000:.3f} 秒**。','', '|评价对象|指标|值|','|---|---|---:|']
        for name,value in zip(['IFN（越小越好）','CHD','CMQ','Instability（代码取最大化）','UCM'],rep['metrics']):lines.append(f'|代表方案|{name}|{fmt(value)}|')
        lines+= [f'|代表方案|服务数量|{rep["serviceCount"]}|',f'|解集|五维 HV|{fmt(run["hv"])}|',f'|最终保留种群|合法率|{fmt(run["validRate"])}|',f'|最终保留种群|有效唯一拆分数|{run["validUniqueCount"]}|',f'|非支配解集|不同目标向量数|{run["nonDominatedCount"]}|','', '精度说明：依赖库 `Chromosome.addObjectiveValue()` 在搜索时将目标值保留到四位小数，因此本表五指标和 HV 使用该次运行实际存储的向量，未擅自替换优化口径。另提供代表方案未经该存储舍入的重算结果，见 [raw-representative-metrics.json](raw-representative-metrics.json)。', '五目标先转换为最小化再归一化，上下界由全部六组校准最终种群确定并冻结，参考点固定 `[1.1, 1.1, 1.1, 1.1, 1.1]`。零跨度维度采用单位尺度；测试点不裁剪到校准范围。代表方案为距冻结校准理想向量的归一化欧氏距离最小者，并列按规范化染色体字典序。',f'归一化配置见 [calibration.json](experiment/CoCoME/calibration.json) 的 `normalization`；正式结果原文见 [run-{args.backend}-{args.seed}.json](experiment/CoCoME/run-{args.backend}-{args.seed}.json)。','', '## 5. 对应 MERM 的可视化','', '[打开离线交互视图](cocome-merm.html) · [SVG 图](cocome-merm.svg) · [MERM 派生 JSON](cocome-merm.json) · [Mermaid 源码](cocome-merm.mmd)','', f'![CoCoME MERM]({out / "cocome-merm.svg"})','', '|服务|用例|实体|系统操作数|','|---|---|---|---:|']
        for s in model['services']:lines.append('|MS'+str(s['id']+1)+'|'+', '.join(s['useCases'])+'|'+(', '.join(s['entities']) or '无')+'|'+str(len(s['systemOperations']))+'|')
        lines+= ['', '可视化由当前染色体解码得到：前 10 位对应当前解析器的用例顺序，后 13 位对应实体顺序；同编号放入同一服务边界。系统操作从用例到操作映射恢复；实体归属确定提供方，再将需求接口连接到提供方。', '打开 HTML 后可点击 MS 按钮聚焦服务、展开系统操作列表，查看参与者关联及实体接口。SVG 可直接用于组会报告，Mermaid 源码可编辑布局。', '**文件边界：** 当前 JSON、HTML、SVG 是按实际输出生成的 MERM 派生视图，不是可直接导入 EMF/Sirius 的 `.req` 序列化。仓库旧 `.req` 文件可能对应其他运行，不能冒充本次结果。若需专用 MERM 编辑器导入，还需依据其 Ecore 元模型生成并验证引用路径。', '视图不保证业务事务、调用时序或部署一致性；服务名 MS1 等仅为算法分组编号。']
        if rep['serviceCount']>1:
            largest=max(model['services'],key=lambda service:len(service['entities']))
            lines+=['', f'本次边界需要业务复核：MS{largest["id"]+1} 包含 {len(largest["useCases"])} 个用例、{len(largest["entities"])} 个实体，并非均匀划分。']
            directed={(edge['consumer'],edge['provider']) for edge in model['interfaces']}
            cycles=sorted({tuple(sorted((a,b))) for a,b in directed if (b,a) in directed and a!=b})
            if cycles:lines+=['实体级接口中存在双向依赖：'+ '、'.join(f'MS{a+1} ↔ MS{b+1}' for a,b in cycles)+'。这不是染色体格式非法，但需要核对业务边界与接口职责；本次没有执行事务或行为正确性验证。']
        if rep['serviceCount']==1:lines+=['本次代表方案只有一个服务，意味着按当前五指标及代表规则选择出的方案退化为单体边界。不能因为其合法或 HV 较高便宣称实现了有效的微服务拆分；应检查目标函数和代表方案偏好是否符合业务粒度要求。']
    lines+= ['', '## 6. 调用成本和结论边界','']
    if real:lines+=[f'JEV 总 tokens：**{fmt(selection.get("totalTokens"))}**；总响应时间：**{selection.get("totalResponseMillis",0)/1000:.3f} 秒**；尝试次数：**{len(selection.get("attempts",[]))}**。缺失用量不按零处理。']
    else:lines+=['认证请求为只读模型列表查询，未取得模型推理的 token 用量；不能将其填写为零 token 的成功 JEV 调用。认证状态见 [api-auth-status.json](api-auth-status.json)。']
    if real:
        responses=[attempt.get('response',{}) for attempt in selection.get('attempts',[])]
        if responses and all('usage' not in response for response in responses):
            lines+=['本次原始响应未包含 `usage` 字段，因此输入、输出和总 tokens 均无法从该响应核实；不能用字符数或估算替代计费用量。']
        probe_path=out/'model-version-probe.json'
        if probe_path.exists():lines+=['本目录另有版本确认请求，见 [版本确认记录](model-version-probe.json)，与配置选择分开记录。']

    failed_path=d/'selection-jev-context-limit-failed.json'
    if real and failed_path.exists():
        failed=read(failed_path);attempts=len(failed.get('attempts',[]))+len(selection.get('attempts',[]))
        total_ms=failed.get('totalResponseMillis',0)+selection.get('totalResponseMillis',0)
        lines+=['','### 本次上下文限制与恢复记录','',
            f'首次完整证据请求被接口以 `max_tokens_exceeded` 拒绝，原有自动重试后共 {len(failed.get("attempts",[]))} 次 HTTP 400。随后仅压缩重复定义和重复值，保留五指标、归一化和候选对照，再发出成功请求。',
            f'计入这批失败尝试后，本任务配置选择共 **{attempts} 次请求**，累计响应耗时 **{total_ms/1000:.3f} 秒**；上面的成功选择耗时仅属于成功批次。失败和成功响应均未返回可核实的 usage，总 tokens 仍为未取得。',
            '校准已完成，因此未重算六组搜索，也未修改校准原始指标、HV 或归一化；只修复传输格式。原校准 manifest 的实现绑定保留，选择记录单独保存新选择器实现哈希，正式运行记录保存当前实现哈希。',
            '[首次失败请求记录](experiment/CoCoME/selection-jev-context-limit-failed.json) · [恢复辅助程序](reproduction/CocomeV2Retry.java)。']
    validation_path=d/f'validation-{args.backend}.json'
    if validation_path.exists():
        v=read(validation_path);lines+=['','已运行独立验证，详细比较见 '+f'[validation-{args.backend}.json](experiment/CoCoME/validation-{args.backend}.json)。','', '|分支|配置|与 C0 的平均配对 HV 差|95% 配对 bootstrap 区间|','|---|---|---:|---|']
        for arm,cid in v['arms'].items():
            p=v['vsDefault'][arm];lines.append('|'+arm+'|'+cid+'|'+fmt(p['meanDifference'])+'|'+str(p['ci95'])+'|')
    else:lines+=['当前给出的正式搜索仅为一个独立种子示例，不足以声称统计显著改善；尚未完成十个独立验证种子的比较。']
    if args.previous_study and run:
        previous_dir=args.previous_study.resolve();previous_path=previous_dir/'experiment'/'CoCoME'/f'run-jev-{args.seed}.json'
        previous=read(previous_path);previous_cal=read(previous_dir/'experiment'/'CoCoME'/'calibration.json')
        comparable=(previous['run']['inputHash']==run['run']['inputHash'] and previous['run']['metricVersion']==run['run']['metricVersion'] and previous_cal['normalization']==cal['normalization'])
        lines+=['','## 与旧规则示例的同种子对照','']
        if comparable:
            old_choice=read(previous_dir/'experiment'/'CoCoME'/'selection-jev.json')['choice']
            lines +=[f'旧规则选择 {old_choice}，本次规则 `{policy}` 选择 {choice}。以下均为种子 {args.seed}，输入与归一化口径一致。旧文件保留。','','|项目|旧规则示例|新版示例|差值（新 − 旧）|','|---|---:|---:|---:|']
            for name,old_value,new_value in zip(['IFN','CHD','CMQ','Instability','UCM'],previous['representative']['metrics'],run['representative']['metrics']):
                lines.append('|'+name+'|'+fmt(old_value)+'|'+fmt(new_value)+'|'+fmt(new_value-old_value)+'|')
            lines.append('|解集 HV|'+fmt(previous['hv'])+'|'+fmt(run['hv'])+'|'+fmt(run['hv']-previous['hv'])+'|')
            lines.append('|实际评价次数|'+str(previous['run']['evaluations'])+'|'+str(run['run']['evaluations'])+'|'+str(run['run']['evaluations']-previous['run']['evaluations'])+'|')
            lines.append('|搜索耗时/秒|'+fmt(previous['run']['searchMillis']/1000)+'|'+fmt(run['run']['searchMillis']/1000)+'|'+fmt((run['run']['searchMillis']-previous['run']['searchMillis'])/1000)+'|')
            lines+=['','两次运行共用评价上限，但种群大小不同、按完整代停止，实际评价次数允许有少量差异；上表明确列出，不能描述为实际评价次数完全相等。']
            if previous['representative']['chromosome']==run['representative']['chromosome']:
                lines+=['本次选出的规范化代表染色体与旧示例完全相同，因此五指标和 MERM 服务边界相同；两次完整非支配解集不同，HV 也不同。这不是复用了旧的正式搜索结果。']
            lines+=['','这是单个独立种子的描述性比较，不是统计显著性证据，也不能归因于某个未经观测的模型理由。']
        else:lines+=['旧结果的输入、指标版本或归一化口径不同，拒绝直接计算差值。']
    lines+= ['', 'HV 数值只在相同模型、指标版本、归一化和参考点下可比；不能与其他系统的绝对 HV 或不同公式的论文数值直接比较。实体级接口由现有程序推导，不等同于已实现 REST/RPC 接口。','', '## 7. 原始证据','', '- [配置快照和实现哈希](experiment/manifest.json)','- [六组校准 JSON](experiment/CoCoME/calibration.json)','- [六组校准 CSV](experiment/CoCoME/calibration.csv)','- [当前模型结构快照](model-snapshot.json)']
    if selection:lines+= [f'- [选择记录](experiment/CoCoME/selection-{args.backend}.json)']
    raw_path=out/'raw-representative-metrics.json'
    if raw_path.exists():
        raw=read(raw_path);lines+=['', '## 附录：代表方案直接重算的未舍入指标', '', '|指标|直接调用目标函数的值|', '|---|---:|']
        for name,value in zip(['IFN','CHD','CMQ','Instability','UCM'],raw['rawMetrics']):lines.append('|'+name+'|'+format(value,'.16g')+'|')
        lines+=['', '本附录用于核对原始公式值，未替换搜索中保留四位小数的向量，也未用它重算或冒充本次搜索 HV。']
    (out/'cocome-jev-analysis.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print(out/'cocome-jev-analysis.md')

if __name__=='__main__':main()
