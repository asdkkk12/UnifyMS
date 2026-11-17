// Presentation-only dragging. Semantic model IDs, memberships and metrics are immutable.
function makeMermDrag(canvas, svg, view) {
    const layout = model.layout[view === 'entities' ? 'domain' : 'usecases'];
    const nodeElements = [...canvas.querySelectorAll('.node')];
    const services = [...canvas.querySelectorAll('.service')];
    const edgeElements = [...canvas.querySelectorAll('.edge')];
    const offsets = new Map();
    const originals = new Map();
    let moving = null;
    const offset = element => offsets.get(element) || {x: 0, y: 0};
    const zero = d => Math.abs(d.x) + Math.abs(d.y) < 1e-8;
    const same = (a, b) => Math.abs(a.x-b.x) + Math.abs(a.y-b.y) < 1e-8;
    for (const element of [...nodeElements, ...services]) {
        originals.set(element, element.getAttribute('transform') || '');
    }
    const edgeState = edgeElements.map(element => {
        const edge = edges.get(element.dataset.id);
        const path = element.querySelector('path');
        const geometry = layout.edges && layout.edges[edge.id];
        return {element, edge, path, original: path && path.getAttribute('d'),
                points: geometry && geometry.points,
                labels: [...element.querySelectorAll('.edge-label'), ...element.querySelectorAll('.aggregate-label')]};
    });
    function endpoint(edge, source) {
        const id = source ? edge.source : edge.target;
        const semantic = nodes.get(id);
        if (edge.kind === 'interface') {
            // Overview interfaces attach to service boundaries, not to the port cards.
            return offset(services.find(element => element.dataset.service === semantic.service));
        }
        const targetService = nodes.get(edge.target).service;
        const element = nodeElements.find(element => element.dataset.id === id &&
            (edge.kind !== 'actor-usecase' || !source || element.dataset.service === targetService));
        return offset(element);
    }
    function redraw() {
        for (const state of edgeState) {
            if (!state.path || !state.points || !state.points.length) continue;
            const a = endpoint(state.edge, true), b = endpoint(state.edge, false);
            state.element.removeAttribute('transform');
            for (const label of state.labels) label.removeAttribute('transform');
            if (same(a, b)) {
                state.path.setAttribute('d', state.original);
                if (!zero(a)) state.element.setAttribute('transform', `translate(${a.x} ${a.y})`);
                continue;
            }
            const pts = state.points;
            const start = [pts[0][0]+a.x, pts[0][1]+a.y];
            const last = pts[pts.length-1];
            const end = [last[0]+b.x, last[1]+b.y];
            let c1, c2;
            if (state.edge.kind === 'actor-usecase') {
                const bend = Math.max(32, Math.min(120, Math.abs(end[0]-start[0])/2));
                c1 = [start[0]+bend, start[1]];
                c2 = [end[0]-bend, end[1]];
            } else {
                const first = pts.find(p => p[0] !== pts[0][0] || p[1] !== pts[0][1]) || last;
                const before = [...pts].reverse().find(p => p[0] !== last[0] || p[1] !== last[1]) || pts[0];
                const length = Math.max(36, Math.min(160, Math.hypot(end[0]-start[0], end[1]-start[1])*.3));
                const direction = (from, to) => {
                    const size = Math.hypot(to[0]-from[0], to[1]-from[1]) || 1;
                    return [(to[0]-from[0])/size, (to[1]-from[1])/size];
                };
                const u = direction(pts[0], first), v = direction(last, before);
                c1 = [start[0]+u[0]*length, start[1]+u[1]*length];
                c2 = [end[0]+v[0]*length, end[1]+v[1]*length];
            }
            state.path.setAttribute('d', `M ${start[0]} ${start[1]} C ${c1[0]} ${c1[1]} ${c2[0]} ${c2[1]} ${end[0]} ${end[1]}`);
            for (const label of state.labels) label.setAttribute('transform', `translate(${a.x} ${a.y})`);
        }
    }
    function box(element) {
        if (!element.dataset.id) return layout.services[element.dataset.service];
        const instance = (layout.instances || []).find(i => i.id === element.dataset.id &&
            (i.service || '') === (element.dataset.service || ''));
        return instance ? instance.box : layout.nodes[element.dataset.id];
    }
    return {
        begin(target) {
            const node = target.closest('.node');
            const service = target.closest('.service');
            let elements;
            if (nodeElements.includes(node)) elements = [node];
            else if (services.includes(service)) {
                elements = [service, ...nodeElements.filter(n => n.dataset.service === service.dataset.service)];
            } else return false;
            const entries = elements.map(element => ({element, start: {...offset(element)}, box: box(element)}));
            const bounds = entries.filter(row => row.box).map(({start,box: b}) => [b[0]+start.x,b[1]+start.y,b[2],b[3]]);
            const v = svg.viewBox.baseVal;
            moving = {entries,
                minX: 8-Math.min(...bounds.map(b => b[0])),
                minY: 8-Math.min(...bounds.map(b => b[1])),
                maxX: v.width-8-Math.max(...bounds.map(b => b[0]+b[2])),
                maxY: v.height-8-Math.max(...bounds.map(b => b[1]+b[3]))};
            canvas.style.cursor = 'grabbing';
            return true;
        },
        move(dx, dy) {
            if (!moving) return;
            dx = Math.max(moving.minX, Math.min(moving.maxX, dx));
            dy = Math.max(moving.minY, Math.min(moving.maxY, dy));
            for (const {element, start} of moving.entries) {
                const d = {x: start.x+dx, y: start.y+dy};
                offsets.set(element, d);
                element.setAttribute('transform', `${originals.get(element)} translate(${d.x} ${d.y})`.trim());
            }
            redraw();
        },
        end() { moving = null; canvas.style.cursor = ''; },
        serviceBox(id) {
            const box = layout.services[id];
            if (!box) return null;
            const delta = offset(services.find(element => element.dataset.service === id));
            return [box[0]+delta.x, box[1]+delta.y, box[2], box[3]];
        },
        reset() {
            moving = null;
            offsets.clear();
            for (const [element, original] of originals) {
                if (original) element.setAttribute('transform', original);
                else element.removeAttribute('transform');
            }
            redraw();
        }
    };
}
