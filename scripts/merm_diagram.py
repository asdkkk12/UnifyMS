"""Readable, local MERM diagrams: service panels, actor proxies and obstacle routing."""
import heapq
import math
from collections import defaultdict
from merm_view import esc, wrap, text

PALETTE = ['#2563eb', '#0d9488', '#7c3aed', '#d97706', '#db2777', '#475569']


def blocked(a, b, boxes):
    """Axis-aligned segment intersects the open interior of an obstacle."""
    for x,y,w,h in boxes:
        if a[0] == b[0]:
            if x < a[0] < x+w and max(min(a[1],b[1]),y) < min(max(a[1],b[1]),y+h):
                return True
        elif y < a[1] < y+h and max(min(a[0],b[0]),x) < min(max(a[0],b[0]),x+w):
            return True
    return False


def route(a, b, boxes, used):
    """Deterministic rectilinear visibility-grid A*, with a shared-segment penalty."""
    xs = sorted({a[0],b[0]} | {v for x,y,w,h in boxes for v in (x-18,x+w+18)})
    ys = sorted({a[1],b[1]} | {v for x,y,w,h in boxes for v in (y-18,y+h+18)})
    start, goal = (xs.index(a[0]),ys.index(a[1])), (xs.index(b[0]),ys.index(b[1]))
    queue = [(0,0,start,None)]
    costs, previous = {(start,None):0}, {}
    finish = None
    while queue:
        _, cost, point, heading = heapq.heappop(queue)
        if cost != costs.get((point,heading)):
            continue
        if point == goal:
            finish = (point,heading)
            break
        for dx,dy,axis in [(1,0,0),(-1,0,0),(0,1,1),(0,-1,1)]:
            q = (point[0]+dx,point[1]+dy)
            if not (0 <= q[0] < len(xs) and 0 <= q[1] < len(ys)):
                continue
            pxy, qxy = (xs[point[0]],ys[point[1]]),(xs[q[0]],ys[q[1]])
            if blocked(pxy,qxy,boxes):
                continue
            segment = tuple(sorted((pxy,qxy)))
            extra = abs(pxy[0]-qxy[0])+abs(pxy[1]-qxy[1])+(18 if heading is not None and heading != axis else 0)
            extra += used.get(segment,0)*45
            new = cost+extra
            state = (q,axis)
            if new < costs.get(state,float('inf')):
                costs[state] = new
                previous[state] = (point,heading)
                estimate = abs(qxy[0]-b[0])+abs(qxy[1]-b[1])
                heapq.heappush(queue,(new+estimate,new,q,axis))
    if finish is None:
        raise ValueError('No obstacle-free route; diagram must not silently cross service panels')
    points = []
    while finish:
        p = finish[0]
        points.append((xs[p[0]],ys[p[1]]))
        finish = previous.get(finish)
    points.reverse()
    for p,q in zip(points,points[1:]):
        key = tuple(sorted((p,q)))
        used[key] = used.get(key,0)+1
    simplified = []
    for p in points:
        if len(simplified)>1 and ((simplified[-2][0]==simplified[-1][0]==p[0]) or (simplified[-2][1]==simplified[-1][1]==p[1])):
            simplified[-1] = p
        else:
            simplified.append(p)
    return simplified


def rounded(points):
    result = f'M {points[0][0]} {points[0][1]}'
    for i,p in enumerate(points[1:-1],1):
        before, after = points[i-1], points[i+1]
        d1, d2 = math.dist(before,p), math.dist(p,after)
        radius = min(10,d1/2,d2/2)
        a = (p[0]+(before[0]-p[0])*radius/d1,p[1]+(before[1]-p[1])*radius/d1) if d1 else p
        b = (p[0]+(after[0]-p[0])*radius/d2,p[1]+(after[1]-p[1])*radius/d2) if d2 else p
        result += f' L {a[0]} {a[1]} Q {p[0]} {p[1]} {b[0]} {b[1]}'
    return result+f' L {points[-1][0]} {points[-1][1]}'


def anchor(box, side, fraction=.5, gap=18):
    x,y,w,h = box
    if side=='E':return (x+w,y+h*fraction),(x+w+gap,y+h*fraction)
    if side=='W':return (x,y+h*fraction),(x-gap,y+h*fraction)
    if side=='N':return (x+w*fraction,y),(x+w*fraction,y-gap)
    return (x+w*fraction,y+h),(x+w*fraction,y+h+gap)


def connection(a,b,obstacles,used,offset=0):
    ac,bc = (a[0]+a[2]/2,a[1]+a[3]/2),(b[0]+b[2]/2,b[1]+b[3]/2)
    dx,dy = bc[0]-ac[0],bc[1]-ac[1]
    sa,sb = ('E','W') if dx>=0 else ('W','E')
    if abs(dy)>abs(dx)*1.3:
        sa,sb = ('S','N') if dy>=0 else ('N','S')
    fraction = .22+(offset % 4)*.17
    p,ap = anchor(a,sa,fraction)
    q,bp = anchor(b,sb,fraction)
    # Allocate distinct channels inside a clear inter-panel gutter. This avoids a
    # shared vertical spine even when several consumers point to the same service.
    if {sa,sb} == {'E','W'}:
        low,high=sorted((p[0],q[0]))
        if high-low>75:
            channel=(low+high)/2+(offset%4-1.5)*18
            candidate=[p,(channel,p[1]),(channel,q[1]),q]
            if all(not blocked(s,t,obstacles) for s,t in zip(candidate,candidate[1:])):
                return candidate
    return [p]+route(ap,bp,obstacles,used)+[q]


def short_port(n):
    name=n['name']
    for prefix in ('ProvideEntityof','ReceiveEntityof'):
        if name.startswith(prefix):name=name[len(prefix):]
    return ('提供 · ' if n['interfaceType']=='ProvideInterface' else '需求 · ')+name


def draw(model, view):
    nodes={n['id']:n for n in model['nodes']}
    services={s['id']:s for s in model['services']}
    groups={sid:[n for n in model['nodes'] if n['service']==sid] for sid in services}
    selected={'uc'} if view=='usecases' else {'entity','userinterface','systeminterface'}
    order=sorted(services,key=lambda sid:(-sum(n['kind'] in selected for n in groups[sid]),list(services).index(sid)))
    positions,boxes,instances,details={}, {}, [], {}
    actor_instances={}
    actor_nodes=[n for n in model['nodes'] if n['kind']=='actor']
    actor_color={n['id']:PALETTE[i%len(PALETTE)] for i,n in enumerate(actor_nodes)}
    panel_width=660
    columns=min(2,max(1,len(order)))
    levels=[170]*columns
    for sid in order:
        col=min(range(columns),key=lambda c:(levels[c],c))
        ox,oy=52+col*(panel_width+130),levels[col]
        if view=='usecases':
            x,w=ox+182,460
            ucs=[n for n in groups[sid] if n['kind']=='uc']
            used_actors=[a for a in actor_nodes if any(e['kind']=='actor-usecase' and e['source']==a['id'] and nodes[e['target']]['service']==sid for e in model['edges'])]
            actor_heights={a['id']:max(96,78+18*len(wrap(a['name'],20))) for a in used_actors}
            h=max(180,110+len(ucs)*88,110+sum(height+14 for height in actor_heights.values()))
            boxes[sid]=(x,oy,w,h)
            for i,n in enumerate(ucs):
                nh=max(62,len(wrap(n['name'],34))*20+26)
                positions[n['id']]=(x+40,oy+82+i*88,380,nh)
            py=oy+85
            for actor in used_actors:
                actor_height=actor_heights[actor['id']]
                box=(ox+4,py,145,actor_height)
                py+=actor_height+14
                actor_instances[(actor['id'],sid)]=box
                positions.setdefault(actor['id'],box)
                instances.append(dict(id=actor['id'],service=sid,box=box,proxy=True))
        else:
            x,w=ox,panel_width
            cursor=oy+88
            for kind in ('entity','userinterface','systeminterface'):
                items=[n for n in groups[sid] if n['kind']==kind]
                if not items:continue
                cursor+=28
                ncols=2
                row_y=cursor
                for start in range(0,len(items),ncols):
                    row=items[start:start+ncols]
                    heights=[]
                    for i,n in enumerate(row):
                        label=short_port(n) if kind=='systeminterface' else n['name']
                        lines=([a['name']+': '+a['type'] for a in n['attributes']] if kind=='entity' else
                               [f'{len(n["operations"])} operations · 点击查看'] if kind=='userinterface' else [])
                        header=max(42,len(wrap(label,32))*20+20)
                        height=header+16+sum(len(wrap(line,32))*18 for line in lines) if lines else header+12
                        positions[n['id']]=(x+24+i*310,row_y,288,height)
                        details[n['id']]=(label,header,lines)
                        heights.append(height)
                    row_y+=max(heights)+20
                cursor=row_y+10
            h=max(210,cursor-oy+15)
            boxes[sid]=(x,oy,w,h)
        levels[col]=oy+h+110
    # Every visual proxy retains its original semantic ID; unassociated actors remain visible.
    if view=='usecases':
        lonely=[a for a in actor_nodes if a['id'] not in positions]
        footer=max(levels,default=170)
        for i,n in enumerate(lonely):
            box=(64+(i%columns)*790,footer+(i//columns)*125,220,96)
            positions[n['id']]=box
            instances.append(dict(id=n['id'],service=None,box=box,proxy=False))
        if lonely:levels=[footer+math.ceil(len(lonely)/columns)*125]
    width=max([x+w for x,y,w,h in boxes.values()]+[800])+76
    height=max([y+h for x,y,w,h in boxes.values()]+levels+[320])+40
    svg=[f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="{esc(model["name"])} {view}" data-view="{view}">',
         '<defs>'+''.join(f'<marker id="{name}-{view}" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="9" markerHeight="9" orient="auto"><path d="{path}" fill="{fill}" stroke="#64748b" stroke-width="1.3"/></marker>' for name,path,fill in [('arrow','M2 2 L10 6 L2 10','none'),('triangle','M1 1 L11 6 L1 11 Z','white'),('diamond','M1 6 L6 1 L11 6 L6 11 Z','#64748b'),('hollow','M1 6 L6 1 L11 6 L6 11 Z','white')])+'</defs>',
         '<style type="text/css">text{font:15px Arial,"PingFang SC","Microsoft YaHei",sans-serif;fill:#172b4d}.title{font-size:28px;font-weight:bold}.heading{font-size:20px;font-weight:bold}.small{font-size:13px;fill:#64748b}.section-title{font-size:12px;font-weight:bold;fill:#64748b;letter-spacing:1px}.node-title{font-size:15px;font-weight:bold}.detail-line{font-size:13px;fill:#52657a}.edge{cursor:pointer}.edge-label{font-size:12px;fill:#52657a}.node{cursor:pointer}.muted{opacity:.12}.highlight rect,.highlight ellipse{stroke:#ec4899;stroke-width:3}.hide-details .detail-line{visibility:hidden}.relation{display:none}.interface-extra{display:none}.node:hover rect,.node:hover ellipse{stroke-width:2.5}.selected rect,.selected ellipse{stroke:#2563eb;stroke-width:2.5}</style>',
         '<rect width="100%" height="100%" fill="#f6f8fc"/><g class="stage">',
         text(52,48,model['name']+' / '+('微服务用例图' if view=='usecases' else '实体与接口图'),'title',180),
         text(52,78,('各服务旁的参与者为同一模型元素的局部副本；颜色区分参与者，完整关联全部保留。' if view=='usecases' else '概览合并同一服务对的接口；实体关系按需展开。箭头：需求服务 → 提供服务。'),'small',180),
         text(52,103,f'{len(services)} 个服务 · {model["counts"].get("uc",0)} 个用例 · {model["counts"].get("entity",0)} 个实体 · 点击查看来源与完整属性','small',180)]
    section_labels={'entity':'ENTITIES / 实体','userinterface':'OPERATIONS / 操作','systeminterface':'INTERFACES / 接口'}
    for index,sid in enumerate(order):
        x,y,w,h=boxes[sid]
        color=PALETTE[list(services).index(sid)%len(PALETTE)]
        svg.append(f'<g class="service" data-service="{sid}"><rect x="{x}" y="{y+4}" width="{w}" height="{h}" rx="18" fill="#e8edf5"/><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="18" fill="white" stroke="#dbe3ef"/><rect x="{x+20}" y="{y+22}" width="5" height="28" rx="2" fill="{color}"/>')
        svg.append(text(x+40,y+44,services[sid]['name'],'heading',64))
        stats=f'{sum(n["kind"]=="uc" for n in groups[sid])} 用例' if view=='usecases' else f'{sum(n["kind"]=="entity" for n in groups[sid])} 实体 · {sum(n["kind"]=="systeminterface" for n in groups[sid])} 接口'
        svg.append(text(x+24,y+70,stats,'small',70))
        if view=='domain':
            for kind,label in section_labels.items():
                items=[n for n in groups[sid] if n['kind']==kind]
                if items:svg.append(text(x+24,min(positions[n['id']][1] for n in items)-12,label,'section-title',80))
        svg.append('</g>')
    view_edges=[e for e in model['edges'] if e['source'] in positions and e['target'] in positions]
    interface_groups=defaultdict(list)
    for e in view_edges:
        if e['kind']=='interface':interface_groups[(nodes[e['source']]['service'],nodes[e['target']]['service'])].append(e)
    usage={}
    edge_layout={}
    local_obstacles=defaultdict(list)
    for nid,p in positions.items():
        if nodes[nid]['service']:local_obstacles[nodes[nid]['service']].append(p)
    local_usage=defaultdict(dict)
    for i,e in enumerate(view_edges):
        src,dst=nodes[e['source']],nodes[e['target']]
        is_interface=e['kind']=='interface'
        relation=view=='domain' and not is_interface
        group=interface_groups[(src['service'],dst['service'])] if is_interface else []
        extra=is_interface and e != group[0]
        color=actor_color.get(e['source'],'#94a3b8') if e['kind']=='actor-usecase' else '#d97706' if is_interface else '#94a3b8'
        marker='triangle' if e['kind']=='generalization' else 'arrow'
        decoration='' if e['kind']=='actor-usecase' else f' marker-end="url(#{marker}-{view})"'
        if e['kind'] in {'include','extend'}:decoration+=' stroke-dasharray="6 5"'
        if e['kind'] in {'composition','aggregation'}:decoration+=f' marker-start="url(#{"diamond" if e["kind"]=="composition" else "hollow"}-{view})"'
        if e['kind']=='actor-usecase':
            a=actor_instances[(e['source'],dst['service'])];b=positions[e['target']]
            sx,sy=a[0]+104,a[1]+35
            tx,ty=b[0],b[1]+b[3]/2
            path=f'M {sx} {sy} C {sx+52} {sy}, {tx-52} {ty}, {tx} {ty}'
            pts=[(sx,sy),(tx,ty)]
        else:
            a,b=positions[e['source']],positions[e['target']]
            same=src['service'] and src['service']==dst['service']
            if is_interface or not same:
                a=boxes.get(src['service'],a);b=boxes.get(dst['service'],b)
                obstacles=list(boxes.values())
                lane=list(interface_groups).index((src['service'],dst['service'])) if is_interface else i
                # The raw per-interface lines fan out when expanded; overview keeps
                # the first route for each pair, with distinct pair-level channels.
                lane=lane if not extra else lane+4+group.index(e)
                pts=connection(a,b,obstacles,usage,lane)
                # Entity/UC relations still terminate on their actual semantic nodes.
                # Only interface overview arrows intentionally attach to service boundaries.
                if not is_interface:
                    def inside(n,box,gateway):
                        own=n['service']
                        if not own:return [gateway]
                        panel=boxes[own]
                        side='W' if gateway[0]==panel[0] else 'E' if gateway[0]==panel[0]+panel[2] else 'N' if gateway[1]==panel[1] else 'S'
                        point,outside=anchor(box,side)
                        return [point]+route(outside,gateway,local_obstacles[own],local_usage[own])
                    front=inside(src,positions[e['source']],pts[0])
                    back=inside(dst,positions[e['target']],pts[-1])
                    pts=front+pts[1:-1]+list(reversed(back))
            else:
                pts=connection(a,b,local_obstacles[src['service']],local_usage[src['service']],i)
            path=rounded(pts)
        edge_layout[e['id']]=dict(points=pts,defaultVisible=not relation and not extra)
        cls=f'edge {e["kind"]}'+(' relation' if relation else '')+(' interface-extra' if extra else '')
        attrs=f'data-id="{esc(e["id"])}" data-source="{esc(e["source"])}" data-target="{esc(e["target"])}" data-services="{esc((src["service"] or dst["service"] or "")+" "+(dst["service"] or ""))}"'
        svg.append(f'<g class="{cls}" {attrs}><title>{esc(e["name"])} | {esc(e["provenance"]["element"])}</title><path d="{path}" fill="none" stroke="{color}" stroke-width="{2 if not relation else 1.2}" stroke-opacity="{.8 if not relation else .5}"{decoration}/>')
        # Interface badges sit on the first route segment; no giant external bottom rails.
        if is_interface:
            px,py=pts[0]
            x=px+5 if pts[1][0]>px else px-125
            y=py-25
            if pts[0][0]==pts[1][0]:
                x=px+5
                y=py+25 if pts[1][1]>py else py-58
            label=short_port(src).split(' · ',1)[-1]
            aggregate=[f'{len(group)} 接口']+[short_port(nodes[g['source']]).split(' · ',1)[-1] for g in group]
            for css,values,hidden in [('edge-label',[label],True),('aggregate-label',aggregate,False)]:
                lines=[part for value in values for part in wrap(value,16)]
                svg.append(f'<g class="{css}" style="display:{"none" if hidden else "inline"}"><rect x="{x}" y="{y-16}" width="120" height="{len(lines)*18+12}" rx="8" fill="#fff8ed" stroke="#f6d5a8"/>')
                for j,line in enumerate(lines):svg.append(text(x+7,y+j*18,line,'small',16))
                svg.append('</g>')
        elif e['kind'] in {'include','extend'}:
            px,py=pts[len(pts)//2]
            svg.append(text(px+6,py-7,e['kind'],'small'))
        svg.append('</g>')
    def node(n,p,proxy_service=None,proxy=False):
        x,y,w,h=p
        service=proxy_service if proxy else n['service']
        svg.append(f'<g class="node" data-id="{esc(n["id"])}" data-service="{esc(service or "")}" data-name="{esc(n["name"])}" tabindex="0"><title>{esc(n["name"])} | {esc(n["id"])}</title>')
        if n['kind']=='actor':
            cx=x+72;color=actor_color[n['id']]
            svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="white" stroke="#dbe3ef"/><circle cx="{cx}" cy="{y+19}" r="10" fill="white" stroke="{color}" stroke-width="2"/><path d="M{cx} {y+29}v27m-22-18h44m-22 18l-17 15m17-15l17 15" fill="none" stroke="{color}" stroke-width="2"/>')
            svg.append(text(x+10,y+88,n['name'],'small',20))
        elif n['kind']=='uc':
            color=PALETTE[list(services).index(n['service'])%len(PALETTE)]
            svg.append(f'<ellipse cx="{x+w/2}" cy="{y+h/2+3}" rx="{w/2}" ry="{h/2}" fill="#edf2fa"/><ellipse cx="{x+w/2}" cy="{y+h/2}" rx="{w/2}" ry="{h/2}" fill="#f8fbff" stroke="{color}" stroke-opacity=".55"/>')
            svg.append(text(x+24,y+28,n['name'],'node-title',34))
        else:
            label,head,lines=details[n['id']]
            fill='#fff8ed' if n['kind']=='systeminterface' and n['interfaceType']=='ReceiveInterface' else '#effaf7' if n['kind'] in {'systeminterface','userinterface'} else '#fbfcff'
            stroke='#f4d8b5' if fill=='#fff8ed' else '#c8e6df' if fill=='#effaf7' else '#dbe3ef'
            svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{fill}" stroke="{stroke}"/>')
            svg.append(text(x+14,y+25,label,'node-title',32))
            line_y=y+head+8
            for line in lines:
                svg.append(text(x+14,line_y,line,'detail-line',32));line_y+=len(wrap(line,32))*18
        svg.append('</g>')
    for key,p in positions.items():
        if nodes[key]['kind']!='actor':node(nodes[key],p)
    for instance in instances:node(nodes[instance['id']],instance['box'],instance['service'],instance['proxy'])
    svg.append('</g></svg>')
    return ''.join(svg),dict(version='readable-panels-v2',nodes={k:list(v) for k,v in positions.items()},
                            services={k:list(v) for k,v in boxes.items()},instances=instances,edges=edge_layout)
