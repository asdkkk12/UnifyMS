/* Offline controller checks with a minimal DOM adapter; no browser or network. */
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const page=fs.readFileSync(process.argv[2],'utf8');
const model=JSON.parse(page.match(/<script type="application\/json" id="model">([\s\S]*?)<\/script>/)[1]);
const code=page.match(/<script>([\s\S]*?)<\/script>/)[1];
function element(dataset={}){
 const classes=new Set(),listeners={};
 return {dataset,style:{},hidden:false,value:'',checked:true,textContent:'',attributes:{},listeners,
  classList:{toggle(k,on){if(on)classes.add(k);else classes.delete(k)},contains(k){return classes.has(k)}},
  addEventListener(k,f){(listeners[k]??=[]).push(f)},emit(k,e={}){for(const f of listeners[k]||[])f(e)},
  setAttribute(k,v){this.attributes[k]=v},getAttribute(k){return this.attributes[k]??null},removeAttribute(k){delete this.attributes[k]},
  closest(selector){return selector==='[data-id]'?this.dataset.id?this:null:selector==='.node'?this.dataset.name?this:null:selector==='.service'?this.dataset.service&&!this.dataset.id?this:null:null},querySelectorAll(){return []},click(){this.clicked=true},setPointerCapture(){}};
}
const controls=Object.fromEntries(['model','summary','diagnostics','selection','service','search','details','interfaces','relations','reset','plus','minus','uc','domain','download'].map(id=>[id,element()]));
controls.interfaces.checked=false;controls.relations.checked=false;
controls.model.textContent=JSON.stringify(model);
const nodeMap=new Map(model.nodes.map(n=>[n.id,n])),allNodes=[],allServices=[],allEdges=[],svgs=[],canvases=[];
for(const [i,id] of ['usecases','entities'].entries()){
 const key=i?'domain':'usecases',layout=model.layout[key];
 const stage=element(),svg=element({view:key}),canvas=element();
 const scopeNodes=[],scopeServices=[],scopeEdges=[];
 const bounds=[...page.matchAll(/<svg[^>]*viewBox="0 0 ([\d.]+) ([\d.]+)"/g)][i];
 svg.viewBox={baseVal:{width:+bounds[1],height:+bounds[2]}};
 svg.querySelector=q=>q==='.stage'?stage:null;
 svg.createSVGPoint=()=>({x:0,y:0,matrixTransform(){return {x:this.x,y:this.y}}});
 svg.getScreenCTM=()=>({inverse(){return {}}});
 canvas.querySelector=q=>q==='svg'?svg:q==='.stage'?stage:null;
 canvas.querySelectorAll=q=>q==='.node'?scopeNodes:q==='.service'?scopeServices:q==='.edge'?scopeEdges:[];
 controls[id]=canvas;canvases.push(canvas);svgs.push(svg);
 for(const nid of Object.keys(layout.nodes)){
  const n=nodeMap.get(nid);if(n.kind==='actor'&&layout.instances)continue;
  const entry=element({id:nid,name:n.name,service:n.service||''});allNodes.push(entry);scopeNodes.push(entry);
 }
 for(const instance of layout.instances||[]){const n=nodeMap.get(instance.id),entry=element({id:n.id,name:n.name,service:instance.service||''});allNodes.push(entry);scopeNodes.push(entry)}
 for(const sid of Object.keys(layout.services)){const entry=element({service:sid});allServices.push(entry);scopeServices.push(entry)}
 for(const e of model.edges.filter(e=>layout.nodes[e.source]&&layout.nodes[e.target])){
  const a=nodeMap.get(e.source),b=nodeMap.get(e.target),n=element({id:e.id,source:e.source,target:e.target,services:(a.service||'')+' '+(b.service||'')});
  n.kind=e.kind;n.relation=key==='domain'&&e.kind!=='interface';n.label=element();n.aggregate=element();n.querySelectorAll=q=>q==='.edge-label'?[n.label]:q==='.aggregate-label'?[n.aggregate]:[];
  n.path=element();n.path.setAttribute('d','M 0 0 L 1 1');n.querySelector=q=>q==='path'?n.path:null;
  allEdges.push(n);
  scopeEdges.push(n);
 }
}
const lists={'.node':allNodes,'.service':allServices,'.edge':allEdges,'.interface':allEdges.filter(e=>e.kind==='interface'),'.relation':allEdges.filter(e=>e.relation),'svg':svgs,'.canvas':canvases};
const context={document:{getElementById:id=>controls[id],querySelectorAll:q=>lists[q]||[],createElement:()=>element()},
 console,Blob,URL,setTimeout,fetch(){throw Error('Network access forbidden in offline test')}};
vm.createContext(context);vm.runInContext(code,context);
assert(controls.summary.textContent.includes(model.counts.services+' 个服务'));
assert(lists['.relation'].every(n=>n.style.display==='none'));
controls.interfaces.checked=true;controls.interfaces.emit('input');
assert.equal(lists['.interface'].filter(n=>n.style.display!=='none').length,model.counts.interfaceEdges);
controls.domain.onclick();assert.equal(controls.usecases.hidden,true);assert.equal(controls.entities.hidden,false);
controls.service.value=model.services[0].id;controls.service.emit('input');controls.service.emit('change');
assert(allNodes.some(n=>n.classList.contains('muted')));
assert.match(canvases[1].querySelector('.stage').attributes.transform,/translate/);
const target=allNodes.find(n=>nodeMap.get(n.dataset.id).kind==='entity');
controls.search.value=target.dataset.name;controls.search.emit('input');assert(allNodes.some(n=>n.classList.contains('highlight')));
controls.details.checked=false;controls.details.emit('input');assert(svgs.every(s=>s.classList.contains('hide-details')));
controls.interfaces.checked=false;controls.interfaces.emit('input');
assert.equal(lists['.interface'].filter(n=>n.style.display!=='none').length,model.counts.serviceEdges);
const canvas=canvases[1];
canvas.emit('pointerdown',{button:0,target,clientX:10,clientY:20,pointerId:1});canvas.emit('pointerup');
assert.equal(JSON.parse(controls.selection.textContent).id,target.dataset.id);
assert(lists['.relation'].some(n=>n.style.display==='inline'));
assert(lists['.relation'].filter(n=>n.style.display==='inline').every(n=>n.dataset.source===target.dataset.id||n.dataset.target===target.dataset.id));
controls.relations.checked=true;controls.relations.emit('input');assert(lists['.relation'].every(n=>n.style.display==='inline'));
controls.relations.checked=false;controls.relations.emit('input');
const edge=lists['.interface'][0];canvas.emit('pointerdown',{button:0,target:edge,clientX:10,clientY:20,pointerId:1});canvas.emit('pointerup');
assert.equal(JSON.parse(controls.selection.textContent).kind,'interface');
let prevented=false;canvas.emit('wheel',{clientX:100,clientY:100,deltaY:-1,preventDefault(){prevented=true}});assert(prevented);
const before=canvas.querySelector('.stage').attributes.transform;
const dragScale=vm.runInContext('transforms.entities.k',context);
canvas.emit('pointerdown',{button:0,target,clientX:10,clientY:20,pointerId:1});canvas.emit('pointermove',{clientX:40,clientY:50});canvas.emit('pointerup');
assert.equal(canvas.querySelector('.stage').attributes.transform,before,'node dragging must not pan the canvas');
assert.match(target.attributes.transform,/translate/);
const draggedCoordinates=target.attributes.transform.match(/translate\(([-\d.e]+) ([-\d.e]+)\)/);
assert(Math.abs(+draggedCoordinates[1]-30/dragScale)<1e-8,'node drag accounts for current zoom');
assert(Math.abs(+draggedCoordinates[2]-30/dragScale)<1e-8);
const connected=allEdges.find(n=>n.relation&&(n.dataset.source===target.dataset.id||n.dataset.target===target.dataset.id));
assert(connected);assert(connected.path.getAttribute('d')!=='M 0 0 L 1 1'||connected.getAttribute('transform'),
 'moving a node updates its incident edge; self-links can move with a group transform');
canvas.emit('pointerdown',{button:0,target:canvas,clientX:10,clientY:20,pointerId:1});canvas.emit('pointermove',{clientX:40,clientY:50});canvas.emit('pointerup');
assert.notEqual(canvas.querySelector('.stage').attributes.transform,before,'background dragging must pan the canvas');
controls.reset.onclick();assert.equal(controls.service.value,'');assert.equal(controls.search.value,'');
assert.equal(target.getAttribute('transform'),null,'reset restores node positions');
assert.equal(connected.path.getAttribute('d'),'M 0 0 L 1 1','reset restores edge geometry');
assert.equal(connected.getAttribute('transform'),null,'reset restores self-link group position');
const semanticBefore=vm.runInContext('JSON.stringify(model)',context);
const service=canvas.querySelectorAll('.service').find(n=>n.dataset.service===target.dataset.service);
canvas.emit('pointerdown',{button:0,target:service,clientX:10,clientY:20,pointerId:1});canvas.emit('pointermove',{clientX:26,clientY:32});canvas.emit('pointerup');
assert.match(service.getAttribute('transform'),/translate/);
assert.match(target.getAttribute('transform'),/translate/,'service dragging moves its children');
const shiftedInterface=lists['.interface'].find(n=>n.dataset.services.split(' ').includes(service.dataset.service));
assert(shiftedInterface.path.getAttribute('d')!=='M 0 0 L 1 1'||shiftedInterface.getAttribute('transform'),'service dragging updates interface endpoints');
assert.equal(vm.runInContext('JSON.stringify(model)',context),semanticBefore,'dragging must not change semantic data');
controls.uc.onclick();assert.equal(controls.usecases.hidden,false);
const actor=canvases[0].querySelectorAll('.node').find(n=>nodeMap.get(n.dataset.id).kind==='actor'&&n.dataset.service);
const otherCopies=canvases[0].querySelectorAll('.node').filter(n=>n.dataset.id===actor.dataset.id&&n!==actor);
const ucBefore=canvases[0].querySelector('.stage').getAttribute('transform');
canvases[0].emit('pointerdown',{button:0,target:actor,clientX:10,clientY:20,pointerId:1});canvases[0].emit('pointermove',{clientX:30,clientY:40});canvases[0].emit('pointerup');
assert.match(actor.getAttribute('transform'),/translate/);
assert(otherCopies.every(n=>n.getAttribute('transform')===null),'dragging a local actor copy does not move its other copies');
assert.equal(canvases[0].querySelector('.stage').getAttribute('transform'),ucBefore);
console.log('PASS: offline view controls, node/service dragging, zoom-aware movement, edge updates, actor copies, reset and pan');
