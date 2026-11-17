"""Deterministic SVG layouts and a self-contained, offline HTML viewer."""
import html
import json
from pathlib import Path


def esc(value):
    return html.escape(str(value), quote=True)


def wrap(value, limit=34):
    lines, line, size = [], '', 0
    for char in str(value):
        weight = 2 if ord(char) > 255 else 1
        if size + weight > limit:
            lines.append(line)
            line, size = '', 0
        line += char
        size += weight
    return lines + [line]


def text(x, y, value, cls='', limit=34):
    return ''.join(f'<text x="{x}" y="{y+i*18}" class="{cls}">{esc(line)}</text>'
                   for i, line in enumerate(wrap(value, limit)))


def service_order(model):
    """Stable SCC condensation layers keep cycles together, including isolated services."""
    nodes = [s['id'] for s in model['services']]
    owner = {n['id']: n['service'] for n in model['nodes']}
    adjacency = {n: set() for n in nodes}
    for e in model['edges']:
        if e['kind'] == 'interface' and owner[e['source']] and owner[e['target']]:
            adjacency[owner[e['source']]].add(owner[e['target']])
    index, low, stack, active, groups = {}, {}, [], set(), []
    def visit(v):
        index[v] = low[v] = len(index)
        stack.append(v)
        active.add(v)
        for w in sorted(adjacency[v]):
            if w not in index:
                visit(w)
                low[v] = min(low[v], low[w])
            elif w in active:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            group = []
            while True:
                w = stack.pop()
                active.remove(w)
                group.append(w)
                if w == v:
                    break
            groups.append(sorted(group))
    for v in nodes:
        if v not in index:
            visit(v)
    membership = {v: i for i, group in enumerate(groups) for v in group}
    memo = {}
    def level(g):
        if g not in memo:
            targets = {membership[t] for v in groups[g] for t in adjacency[v] if membership[t] != g}
            memo[g] = 1 + max((level(t) for t in targets), default=-1)
        return memo[g]
    return [v for g in sorted(range(len(groups)), key=lambda g: (level(g), min(groups[g]))) for v in groups[g]]


def render_svg(model, view):
    from merm_diagram import draw
    return draw(model, view)


def render_html(model, usecases, domain):
    payload = json.dumps(model, ensure_ascii=False).replace('<', '\\u003c').replace('&', '\\u0026')
    options = '<option value="">全部服务</option>' + ''.join(f'<option value="{esc(s["id"])}">{esc(s["name"])}</option>' for s in model['services'])
    return '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>MERM 离线查看器</title><style>
body{margin:0;font:15px/1.6 Arial,"PingFang SC",sans-serif;color:#172b4d;background:#f6f8fc}header{padding:16px 24px;background:white}h1{margin:0;font-size:24px}nav{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}button,select,input{font:inherit;padding:6px 10px;border:1px solid #dbe3ef;border-radius:9px;background:white;color:#334155}main{display:block;padding:16px 24px;max-width:1700px;margin:auto}.canvas{height:78vh;min-height:560px;overflow:hidden;background:#f6f8fc;border:1px solid #dbe3ef;border-radius:18px;touch-action:none}.canvas svg{width:100%;height:100%;user-select:none}.canvas[hidden]{display:none}.canvas .node,.canvas .service{cursor:grab}aside{margin-top:16px;padding:20px 24px;background:white;border:1px solid #dbe3ef;border-radius:16px;max-height:320px;overflow:auto}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}.note{font-size:13px;color:#52657a}a{color:#2455a4}@media(max-width:850px){main{grid-template-columns:1fr}aside{max-height:300px}}@media print{nav,aside{display:none}main{display:block}.canvas{height:auto;overflow:visible}.canvas svg{height:auto}}</style></head><body><header><h1>''' + esc(model['name']) + ''' · MERM 拆分模型</h1><p class="note">完全离线 · 自动布局 · 来源为 .req。展示模型语义，不代表已通过原生 Sirius 兼容性验证。</p><nav><button id="uc">用例图</button><button id="domain">实体与接口图</button><select id="service">''' + options + '''</select><input id="search" placeholder="搜索元素名称" aria-label="搜索元素名称"><button id="reset">重置视图</button><button id="plus">放大</button><button id="minus">缩小</button><label><input type="checkbox" id="details" checked>属性与操作</label><label><input type="checkbox" id="interfaces">接口明细</label><label><input type="checkbox" id="relations">全部实体关联</label><button id="download">下载当前 SVG</button></nav><p class="note">拖动元素或服务框调整布局，拖动空白处平移，滚轮缩放；点击查看来源。拖拽仅改变显示位置，下载 SVG 可保留当前布局。默认合并接口；点击实体仅展开其关联，勾选“全部实体关联”查看完整关系。参与者局部副本指向同一原始元素。</p></header><main><section><div id="usecases" class="canvas">''' + usecases + '''</div><div id="entities" class="canvas" hidden>''' + domain + '''</div></section><aside><h2>模型与来源</h2><div id="summary"></div><h3>选择详情</h3><pre id="selection">点击元素或关系查看 .req 路径、属性及引用。</pre><details><summary>诊断信息</summary><pre id="diagnostics"></pre></details></aside></main><script type="application/json" id="model">''' + payload + '''</script><script>
const model=JSON.parse(document.getElementById('model').textContent), $=id=>document.getElementById(id);
const nodes=new Map(model.nodes.map(n=>[n.id,n])), edges=new Map(model.edges.map(e=>[e.id,e]));
$('summary').textContent=`${model.counts.services} 个服务 · ${model.counts.uc||0} 个用例 · ${model.counts.entity||0} 个实体 · ${model.counts.interfaceEdges} 条接口配对 · ${model.counts.serviceEdges} 条服务依赖`;$('diagnostics').textContent=JSON.stringify(model.diagnostics,null,2);
let activeNode='',current='usecases';const transforms={usecases:{x:0,y:0,k:1},entities:{x:0,y:0,k:1}},nodeDrags={};
function update(){const t=transforms[current];$(current).querySelector('.stage').setAttribute('transform',`translate(${t.x} ${t.y}) scale(${t.k})`)}
function filter(){const service=$('service').value,q=$('search').value.toLowerCase();document.querySelectorAll('.node').forEach(n=>{n.classList.toggle('muted',!!service&&n.dataset.service!==service);n.classList.toggle('highlight',!!q&&n.dataset.name.toLowerCase().includes(q));n.classList.toggle('selected',!!activeNode&&n.dataset.id===activeNode)});document.querySelectorAll('.service').forEach(n=>n.classList.toggle('muted',!!service&&n.dataset.service!==service));document.querySelectorAll('.edge').forEach(n=>n.classList.toggle('muted',!!service&&!n.dataset.services.split(' ').includes(service)));document.querySelectorAll('svg').forEach(n=>n.classList.toggle('hide-details',!$('details').checked));
const seen=new Set();document.querySelectorAll('.interface').forEach(n=>{const a=nodes.get(n.dataset.source),b=nodes.get(n.dataset.target),key=a.service+'>'+b.service;const merged=!$('interfaces').checked;n.style.display=merged&&seen.has(key)?'none':'inline';n.querySelectorAll('.edge-label').forEach(t=>t.style.display=merged?'none':'');n.querySelectorAll('.aggregate-label').forEach(t=>t.style.display=merged?'':'none');seen.add(key)});document.querySelectorAll('.relation').forEach(n=>{const incident=activeNode&&(n.dataset.source===activeNode||n.dataset.target===activeNode);n.style.display=$('relations').checked||incident?'inline':'none'})}
function focusService(){const id=$('service').value,box=nodeDrags[current].serviceBox(id);if(!box)return;const v=$(current).querySelector('svg').viewBox.baseVal,k=Math.min(12,v.width/(box[2]+160),v.height/(box[3]+100));transforms[current]={k,x:v.width/2-(box[0]+box[2]/2)*k,y:v.height/2-(box[1]+box[3]/2)*k};update()}
function show(id){current=id;$('usecases').hidden=id!=='usecases';$('entities').hidden=id!=='entities';focusService();update()}
$('uc').onclick=()=>show('usecases');$('domain').onclick=()=>show('entities');['service','search','details','interfaces','relations'].forEach(id=>$(id).addEventListener('input',filter));
$('service').addEventListener('change',focusService);
$('reset').onclick=()=>{nodeDrags[current].reset();transforms[current]={x:0,y:0,k:1};$('service').value='';$('search').value='';activeNode='';filter();update()};
function zoom(f,cx,cy){const t=transforms[current],k=Math.max(.15,Math.min(12,t.k*f)),ratio=k/t.k;t.x=cx-(cx-t.x)*ratio;t.y=cy-(cy-t.y)*ratio;t.k=k;update()}
function centerZoom(f){const v=$(current).querySelector('svg').viewBox.baseVal;zoom(f,v.width/2,v.height/2)}$('plus').onclick=()=>centerZoom(1.2);$('minus').onclick=()=>centerZoom(1/1.2);
''' + Path(__file__).with_name('merm_drag.js').read_text(encoding='utf-8') + '''
document.querySelectorAll('.canvas').forEach(canvas=>{const svg=canvas.querySelector('svg'),view=svg.dataset.view==='domain'?'entities':'usecases',nodeDrag=makeMermDrag(canvas,svg,view);nodeDrags[view]=nodeDrag;let drag=null,moved=false;
function point(e){const p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;return p.matrixTransform(svg.getScreenCTM().inverse())}
canvas.addEventListener('wheel',e=>{e.preventDefault();const p=point(e);zoom(e.deltaY<0?1.12:1/1.12,p.x,p.y)},{passive:false});
canvas.addEventListener('pointerdown',e=>{if(e.button!==0)return;const p=point(e);drag={x:p.x,y:p.y,tx:transforms[current].x,ty:transforms[current].y,scale:transforms[current].k,target:e.target,node:nodeDrag.begin(e.target)};if(drag.node){const n=e.target.closest('.node');if(n){activeNode=n.dataset.id;$('selection').textContent=JSON.stringify(nodes.get(activeNode),null,2);filter()}}moved=false;canvas.setPointerCapture(e.pointerId)});
canvas.addEventListener('pointermove',e=>{if(!drag)return;const p=point(e),dx=p.x-drag.x,dy=p.y-drag.y;if(Math.abs(dx)+Math.abs(dy)>3)moved=true;if(!moved)return;if(drag.node){nodeDrag.move(dx/drag.scale,dy/drag.scale)}else{transforms[current].x=drag.tx+dx;transforms[current].y=drag.ty+dy;update()}});
canvas.addEventListener('pointerup',()=>{if(drag&&!moved){const n=drag.target.closest('[data-id]');if(n){activeNode=nodes.has(n.dataset.id)?n.dataset.id:activeNode;$('selection').textContent=JSON.stringify(nodes.get(n.dataset.id)||edges.get(n.dataset.id),null,2);filter()}}nodeDrag.end();drag=null});canvas.addEventListener('pointercancel',()=>{nodeDrag.end();drag=null});
canvas.addEventListener('lostpointercapture',()=>{nodeDrag.end();drag=null});
canvas.addEventListener('keydown',e=>{if(e.key==='Enter'){const n=e.target.closest('[data-id]');if(n){activeNode=nodes.has(n.dataset.id)?n.dataset.id:activeNode;$('selection').textContent=JSON.stringify(nodes.get(n.dataset.id)||edges.get(n.dataset.id),null,2);filter()}}})});
$('download').onclick=()=>{const copy=$(current).querySelector('svg').cloneNode(true);copy.querySelector('.stage').removeAttribute('transform');const blob=new Blob([new XMLSerializer().serializeToString(copy)],{type:'image/svg+xml'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=model.name+'-'+current+'.svg';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};
filter();
</script></body></html>'''
