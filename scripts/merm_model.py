"""MERM semantic adapter. No search, network access, or metric recomputation."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from urllib.parse import quote

XMI = 'http://www.omg.org/XMI'
XSI = 'http://www.w3.org/2001/XMLSchema-instance'
REQ = 'http://www.example.org/req'


def local(name):
    return name.rsplit('}', 1)[-1].split(':')[-1]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def input_digest(path):
    """Match FilesIO.inputHash, including relative paths and NUL delimiters for Java."""
    path=Path(path)
    if not path.is_dir():return digest(path)
    files=sorted(path.rglob('*.java'),key=lambda p:p.relative_to(path).as_posix())
    if not files:raise ValueError('No Java source files')
    value=hashlib.sha256()
    for source in files:
        value.update(source.relative_to(path).as_posix().encode('utf-8'));value.update(b'\0')
        value.update(source.read_bytes());value.update(b'\0')
    return value.hexdigest()


def java_entities(snapshot):
    """Preserve the search model's legacy matrix; AST fields are display metadata only."""
    if snapshot.get('sourceKind')!='java-directory-legacy' or 'entitySchemas' not in snapshot:
        raise ValueError('Java export needs a directory snapshot with AST entity metadata')
    names=[snapshot['entityNames'][str(i)] for i in range(len(snapshot['entityNames']))]
    schemas=snapshot['entitySchemas']
    if set(schemas)!=set(names):raise ValueError('Java entity schema membership mismatch')
    result={}
    for i,name in enumerate(names):
        spec=dict(schemas[name]);spec['parent']=spec.get('javaSuperType') if spec.get('javaSuperType') in schemas else None
        spec['references']=[dict(name='legacyLexical_'+target,type=target,multiple=False,reverse=False,relation='Association',evidenceKind='legacy-lexical-matrix')
                            for j,target in enumerate(names) if snapshot['entityRelationMatrix'][i][j]==1]
        result[name]=spec
    return result


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def parse_req(path):
    data = Path(path).read_bytes()
    text = data.decode('utf-8-sig')
    diagnostics = []

    def note(code, message, source='', severity='warning'):
        diagnostics.append(dict(code=code, message=message, source=source, severity=severity))

    if re.search(r'<!\s*(DOCTYPE|ENTITY)\b', text, re.I):
        raise ValueError('DTD and entity declarations are forbidden')
    if '<xmi:XMI' in text and not re.search(r'xmlns:xmi\s*=', text):
        text = text.replace('<xmi:XMI', '<xmi:XMI xmlns:xmi="' + XMI + '"', 1)
        note('missing-xmi-namespace', 'Added the XMI namespace in memory; input was not modified.')
    root = ET.fromstring(text)
    roots = list(root) if local(root.tag) == 'XMI' else [root]
    requirements = [e for e in roots if local(e.tag) == 'RequirementModel']
    if len(requirements) != 1:
        raise ValueError('Expected exactly one RequirementModel')
    req = requirements[0]
    base = '/' + str(roots.index(req))
    by_id, ids, parents = {}, {}, {}
    many = {'usecasecontext', 'entitycontext', 'uc', 'actor', 'systeminterface',
            'entity', 'attributes', 'reference', 'userinterface', 'operation',
            'relation', 'ucRelation', 'include', 'extend', 'invariance'}

    def index(e, key):
        by_id[key], ids[e] = e, key
        counts, seen = Counter(local(c.tag) for c in e), Counter()
        for c in e:
            tag = local(c.tag)
            suffix = '.' + str(seen[tag]) if tag in many or counts[tag] > 1 else ''
            seen[tag] += 1
            parents[c] = e
            index(c, key + '/@' + tag + suffix)
    index(req, base)

    def children(e, tag):
        return [c for c in e if local(c.tag) == tag]

    def resolve(e, attr, allowed):
        out = []
        for token in e.get(attr, '').split():
            original = token
            if token.startswith('#'):
                token = token[1:]
            if not token.startswith('/') or '#' in token or ':' in token:
                note('external-reference', 'Unsupported external reference: ' + original, ids[e], 'error')
                continue
            target = by_id.get(token)
            if target is None:
                note('dangling-reference', attr + ': ' + original, ids[e], 'error')
            elif local(target.tag) not in allowed:
                note('reference-type', attr + ': unexpected ' + local(target.tag), ids[e], 'error')
            else:
                out.append(token)
        return out

    models = {local(e.tag): e for e in req}
    if 'UseCaseModel' not in models or 'DomainModel' not in models:
        raise ValueError('Expected UseCaseModel and DomainModel')
    if any(sum(local(e.tag) == tag for e in req) != 1 for tag in ('UseCaseModel', 'DomainModel')):
        raise ValueError('Expected one UseCaseModel and one DomainModel')
    ucmodel, domain = models['UseCaseModel'], models['DomainModel']
    ucontexts, econtexts = children(ucmodel, 'usecasecontext'), children(domain, 'entitycontext')
    if not ucontexts and not econtexts:
        note('missing-service-contexts', 'This file contains no MERM service contexts.', severity='error')
    services, ownership = [], {}
    # Explicit serviceId is authoritative. Legacy exporter defines matching numeric suffixes.
    def service_key(e, pattern):
        if e.get('serviceId') is not None:
            return e.get('serviceId')
        match = re.fullmatch(pattern, e.get('name', ''))
        return match.group(1) if match else None
    uk = [service_key(e, r'Microservice\s*(\d+)') for e in ucontexts]
    ek = [service_key(e, r'EntityContext\s*(\d+)') for e in econtexts]
    if None in uk + ek or len(set(uk)) != len(uk) or len(set(ek)) != len(ek) or set(uk) != set(ek):
        note('ambiguous-service-mapping', 'Contexts need matching unique serviceId or legacy numeric names.', severity='error')
        # Keep every context visible, but never guess a correspondence.
        pairs = [(e, None, 'uc-' + str(i)) for i, e in enumerate(ucontexts)]
        pairs += [(None, e, 'domain-' + str(i)) for i, e in enumerate(econtexts)]
    else:
        emap = dict(zip(ek, econtexts))
        pairs = [(u, emap[k], k) for u, k in zip(ucontexts, uk)]
        if any(e.get('serviceId') is None for e in ucontexts + econtexts):
            note('legacy-service-mapping', 'Contexts matched by the legacy numeric naming convention.')
    for u, d, key in pairs:
        sid = 'service-' + quote(key, safe='')
        service = dict(id=sid, key=key, name=u.get('name') if u is not None else d.get('name'),
                       usecaseContext=ids[u] if u is not None else None,
                       entityContext=ids[d] if d is not None else None)
        services.append(service)
        for context in (u, d):
            if context is not None:
                for e in context.iter():
                    ownership[e] = sid
    nodes, edges = [], []
    for key, e in by_id.items():
        tag = local(e.tag)
        if tag not in {'uc', 'actor', 'entity', 'systeminterface', 'userinterface'}:
            continue
        n = dict(id=key, kind=tag, name=e.get('name', tag), service=ownership.get(e),
                 source=dict(path=key, attributes=dict(e.attrib)))
        if tag == 'entity':
            n['attributes'] = [dict(name=a.get('name', ''), type=(list(a)[0].get('name', '?') if len(a) else a.get('type', '?')))
                               for a in children(e, 'attributes')]
        if tag == 'userinterface':
            n['operations'] = [dict(name=o.get('name', ''), source=ids[o]) for o in children(e, 'operation')]
        if tag == 'systeminterface':
            n['interfaceType'] = local(e.get('{' + XSI + '}type', 'UnknownInterface'))
            owners = resolve(e, 'entitycontext', {'entitycontext'})
            if len(owners) == 1:
                n['service'] = ownership.get(by_id[owners[0]])
            else:
                note('interface-owner', 'Interface needs exactly one valid entitycontext.', key, 'error')
        nodes.append(n)
    node_map = {n['id']: n for n in nodes}

    def edge(source, target, kind, e, attr, name=''):
        if source not in node_map or target not in node_map:
            return
        edges.append(dict(id=ids[e] + ':' + attr + ':' + str(len(edges)), source=source,
                          target=target, kind=kind, name=name,
                          provenance=dict(element=ids[e], attribute=attr, value=e.get(attr, ''), attributes=dict(e.attrib))))

    for key, e in by_id.items():
        tag = local(e.tag)
        if tag == 'actor':
            for t in resolve(e, 'uc', {'uc'}):
                edge(key, t, 'actor-usecase', e, 'uc')
            for t in resolve(e, 'superActor', {'actor'}):
                edge(key, t, 'generalization', e, 'superActor')
        if tag == 'entity':
            for t in resolve(e, 'superEntity', {'entity'}):
                edge(key, t, 'generalization', e, 'superEntity', 'extends')
        if tag == 'reference':
            for t in resolve(e, 'entity', {'entity'}):
                kind = 'composition' if e.get('isComposition') == 'true' else 'aggregation' if e.get('isAggregation') == 'true' else 'association'
                label = e.get('name', '') + (' [*]' if e.get('ismultiple') == 'true' else '')
                edge(ids[parents[e]], t, kind, e, 'entity', label)
        if tag == 'systeminterface':
            typ = node_map[key]['interfaceType']
            if typ not in {'ReceiveInterface', 'ProvideInterface'}:
                note('interface-type', 'Unsupported interface type: ' + typ, key, 'error')
            if typ == 'ReceiveInterface':
                targets = resolve(e, 'provideinterface', {'systeminterface'})
                if not targets:
                    note('unpaired-interface', 'Receive interface has no valid provider.', key, 'error')
                for t in targets:
                    if node_map[t]['interfaceType'] != 'ProvideInterface':
                        note('interface-type', 'Target is not a ProvideInterface.', key, 'error')
                        continue
                    reverse = resolve(by_id[t], 'receiveinterface', {'systeminterface'})
                    if key not in reverse:
                        note('nonreciprocal-interface', 'Provider does not point back to receiver.', key, 'error')
                    edge(key, t, 'interface', e, 'provideinterface', e.get('name', ''))
            elif typ == 'ProvideInterface':
                for t in resolve(e, 'receiveinterface', {'systeminterface'}):
                    reverse = resolve(by_id[t], 'provideinterface', {'systeminterface'})
                    if node_map[t]['interfaceType'] != 'ReceiveInterface' or key not in reverse:
                        note('nonreciprocal-interface', 'Receiver does not point back to provider.', key, 'error')
        if tag == 'entitycontext':
            for t in resolve(e, 'systeminterface', {'systeminterface'}):
                if node_map[t]['service'] != ownership.get(e):
                    note('interface-membership', 'Context references a port owned by another service.', key, 'error')
        # REMODEL VSM uses relation objects with relateduc targets.
        if e.get('relateduc') is not None:
            owner = e if tag == 'uc' else parents.get(e)
            if owner is not None and local(owner.tag) == 'uc':
                typ = local(e.get('UCRelation', e.get('{' + XSI + '}type', tag))).lower()
                kind = 'include' if 'include' in typ else 'extend' if 'extend' in typ else 'usecase-relation'
                for t in resolve(e, 'relateduc', {'uc'}):
                    edge(ids[owner], t, kind, e, 'relateduc', kind)
    for n in nodes:
        if n['kind'] == 'systeminterface' and n['service']:
            context = next((d for d in econtexts if ownership.get(d) == n['service']), None)
            if context is not None and n['id'] not in context.get('systeminterface', '').split():
                note('missing-port-membership', 'Port not listed on its owning context.', n['id'])
    counts = dict(Counter(n['kind'] for n in nodes))
    counts['services'] = len(services)
    counts['interfaceEdges'] = sum(e['kind'] == 'interface' for e in edges)
    counts['serviceEdges'] = len({(node_map[e['source']]['service'], node_map[e['target']]['service'])
                                  for e in edges if e['kind'] == 'interface'})
    for s in services:
        for kind in ('uc', 'entity'):
            names = Counter(n['name'] for n in nodes if n['service'] == s['id'] and n['kind'] == kind)
            for name, count in names.items():
                if count > 1:
                    note('duplicate-name', 'Distinct paths retained for duplicate name: ' + name, s['id'])
    return dict(schemaVersion='merm-view-v1', name=Path(path).stem, input=str(Path(path).resolve()),
                inputHash=hashlib.sha256(data).hexdigest(), metadata=dict(req.attrib),
                services=services, nodes=nodes, edges=edges, counts=counts, diagnostics=diagnostics)


def remodel_entities(text):
    """Strict structural subset: entity properties, references, inheritance; no OCL evaluation."""
    text = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
    text = re.sub(r'@\w+\s*\(\s*"(?:[^"\\]|\\.)*"\s*\)', '', text)
    result = {}
    for match in re.finditer(r'\bEntity\s+(\w+)(?:\s+extends\s+(\w+))?\s*\{', text):
        start, depth, end = match.end(), 1, match.end()
        while end < len(text) and depth:
            depth += (text[end] == '{') - (text[end] == '}')
            end += 1
        if depth:
            raise ValueError('Unclosed entity ' + match.group(1))
        body = text[start:end-1]
        mode, attrs, refs = 'attributes', [], []
        for raw in body.splitlines():
            line = raw.strip()
            if not line or line.startswith('@'):
                continue
            if line == '[Refer]':
                mode = 'references'
                continue
            if line == '[INV]':
                mode = 'invariants'
                continue
            if mode == 'invariants':
                continue
            prop = re.fullmatch(r'(\w+)\s*:\s*(\w+(?:\[[\w|\s]+\]|\(\w+\))?)\s*(\*)?\s*(!)?\s*'
                                r'(?:(Association|Aggregation|Composition)|\(\s*(Association|Aggregation|Composition)\s*\))?\s*', line)
            if not prop:
                raise ValueError('Unsupported entity declaration (not silently omitted): ' + line)
            row = dict(name=prop[1], type=prop[2], multiple=bool(prop[3]), reverse=bool(prop[4]), relation=prop[5] or prop[6] or 'Association')
            (refs if mode == 'references' else attrs).append(row)
        name = match[1]
        if name in result:
            raise ValueError('Duplicate source entity: ' + name)
        result[name] = dict(name=name, parent=match[2], attributes=attrs, references=refs)
    return result


def export_req(run_path, source_path, snapshot_path, output_path):
    """Export a saved formal result using its bound parser snapshot and source metadata."""
    run = json.loads(Path(run_path).read_text(encoding='utf-8'))
    snap = json.loads(Path(snapshot_path).read_text(encoding='utf-8'))
    input_hash = input_digest(source_path)
    if snap['inputHash'] != input_hash or run['run']['inputHash'] != input_hash:
        raise ValueError('Source/run/snapshot input hashes do not match')
    rep = run['representative']
    if not rep.get('valid', False):
        raise ValueError('Cannot export an invalid representative')
    uc_names = [snap['useCaseNames'][str(i)] for i in range(len(snap['useCaseNames']))]
    en_names = [snap['entityNames'][str(i)] for i in range(len(snap['entityNames']))]
    genes = rep['chromosome']
    services = sorted(rep['services'], key=lambda s: s['id'])
    if [s['id'] for s in services] != list(range(rep['serviceCount'])) or len(genes) != len(uc_names) + len(en_names):
        raise ValueError('Invalid service IDs or chromosome dimensions')
    for s in services:
        for key, names, offset in [('useCases', uc_names, 0), ('entities', en_names, len(uc_names))]:
            expected = [name for i, name in enumerate(names) if genes[offset+i] == s['id']]
            if expected != s[key]:
                raise ValueError('Chromosome/service membership mismatch: ' + key)
    if set(genes) != set(range(len(services))):
        raise ValueError('Chromosome contains unknown services')
    # Validate interfaces with the same binary matrix rules as QualityCalculate.generateInterface.
    ucount, ecount = len(uc_names), len(en_names)
    ue, ee = snap['useCaseEntityMatrix'], snap['entityRelationMatrix']
    if len(ue) != ucount or len(ee) != ecount or any(len(row) != ecount for row in ue + ee):
        raise ValueError('Snapshot matrix dimensions do not match chromosome')
    required = [set() for _ in services]
    for i, row in enumerate(ee):
        for j, value in enumerate(row):
            if value == 1 and genes[ucount+i] != genes[ucount+j]:
                required[genes[ucount+i]].add(en_names[j])
    for i, row in enumerate(ue):
        for j, value in enumerate(row):
            if value == 1 and genes[i] != genes[ucount+j]:
                required[genes[i]].add(en_names[j])
    provided = [set() for _ in services]
    for names in required:
        for name in names:
            provided[genes[ucount+en_names.index(name)]].add(name)
    for s in services:
        if set(s['requiredEntityInterfaces']) != required[s['id']] or set(s['providedEntityInterfaces']) != provided[s['id']]:
            raise ValueError('Run interfaces do not match bound snapshot matrix rules')
    is_java=Path(source_path).is_dir()
    source = '' if is_java else Path(source_path).read_text(encoding='utf-8')
    entities = java_entities(snap) if is_java else remodel_entities(source)
    if set(entities) != set(en_names):
        raise ValueError('Source entity declarations do not match parser snapshot')
    ET.register_namespace('xmi', XMI)
    ET.register_namespace('req', REQ)
    ET.register_namespace('xsi', XSI)
    root = ET.Element('{' + XMI + '}XMI', {'{' + XMI + '}version': '2.0'})
    req = ET.SubElement(root, '{' + REQ + '}RequirementModel', dict(
        inputHash=input_hash, runHash=digest(run_path), snapshotHash=digest(snapshot_path),
        taskKey=str(run['run']['taskKey']), configId=str(run['run']['config']['id']),
        seed=str(run['run']['config']['seed']), exportVersion='merm-export-v1'))
    if is_java:req.set('sourceKind','java-directory-legacy')
    um = ET.SubElement(req, 'UseCaseModel', name=Path(source_path).name)
    dm = ET.SubElement(req, 'DomainModel', name=Path(source_path).name)
    ucpaths, enpaths, contexts, ports, ucelements = {}, {}, {}, {}, {}
    for s in services:
        sid = s['id']
        u = ET.SubElement(um, 'usecasecontext', name='Microservice ' + str(sid), serviceId=str(sid))
        d = ET.SubElement(dm, 'entitycontext', name='EntityContext' + str(sid), serviceId=str(sid))
        contexts[sid] = d
        for i, name in enumerate(s['useCases']):
            ucelements[name] = ET.SubElement(u, 'uc', name=name)
            if is_java:ucelements[name].set('javaControllerSources',' '.join(snap['sourceAudit']['methodSources'].get(name,[])))
            ucpaths[name] = f'/0/@UseCaseModel/@usecasecontext.{sid}/@uc.{i}'
        for i, name in enumerate(s['entities']):
            enpaths[name] = f'/0/@DomainModel/@entitycontext.{sid}/@entity.{i}'
    # Preserve UC include/extend and actor inheritance from the supported source declarations.
    for match in re.finditer(r'^\s*UC::(\w+)[^\n]*?\b(include|extend)\s+(\w+)', source, re.M):
        if match[1] not in ucpaths or match[3] not in ucpaths:
            raise ValueError('Unknown source use case relation endpoint')
        uc = ucelements[match[1]]
        if uc.get('UCRelation') not in {None, match[2]}:
            raise ValueError('Mixed UC relation kinds need an extended schema')
        uc.set('UCRelation', match[2])
        uc.set('relateduc', (uc.get('relateduc', '') + ' ' + ucpaths[match[3]]).strip())
    actors = {}
    for name, ucs in sorted(snap['actors'].items()):
        if any(uc not in ucpaths for uc in ucs):
            raise ValueError('Actor references unknown use case')
        actors[name] = ET.SubElement(um, 'actor', name=name, uc=' '.join(ucpaths[uc] for uc in sorted(ucs)))
        if is_java:actors[name].set('javaControllerSources',' '.join(snap['sourceAudit']['actorSources'].get(name,[])))
    actor_paths = {name: '/0/@UseCaseModel/@actor.' + str(i) for i, name in enumerate(actors)}
    for match in re.finditer(r'\bActor\s+(\w+)\s+extends\s+(\w+)', source):
        if match[1] not in actors or match[2] not in actors:
            raise ValueError('Unknown source actor inheritance endpoint')
        actors[match[1]].set('superActor', actor_paths[match[2]])
    # Use explicit interfaces already computed by QualityCalculate for this representative.
    owners = {name: s['id'] for s in services for name in s['entities']}
    port_count = 0
    def port(sid, name, typ):
        nonlocal port_count
        e = ET.SubElement(dm, 'systeminterface', {'{' + XSI + '}type': 'req:' + typ,
                          'name': name, 'entitycontext': f'/0/@DomainModel/@entitycontext.{sid}'})
        path = f'/0/@DomainModel/@systeminterface.{port_count}'
        port_count += 1
        contexts[sid].set('systeminterface', (contexts[sid].get('systeminterface', '') + ' ' + path).strip())
        return e, path
    for s in services:
        for name in s['providedEntityInterfaces']:
            if owners.get(name) != s['id']:
                raise ValueError('Provided entity is not owned by service')
            ports[name] = port(s['id'], 'ProvideEntityof' + name, 'ProvideInterface')
    consumed = set()
    for s in services:
        for name in s['requiredEntityInterfaces']:
            if name not in ports or owners.get(name) == s['id']:
                raise ValueError('Invalid required interface')
            p, pp = ports[name]
            receiver, rp = port(s['id'], 'ReceiveEntityof' + name, 'ReceiveInterface')
            receiver.set('provideinterface', pp)
            p.set('receiveinterface', (p.get('receiveinterface', '') + ' ' + rp).strip())
            consumed.add(name)
    if consumed != set(ports):
        raise ValueError('Provided interface without a consumer')
    for s in services:
        context = contexts[s['id']]
        ui = ET.SubElement(context, 'userinterface', name='UserInterface')
        ops = sorted({op for uc in s['useCases'] for op in snap['useCaseOperations'][uc]})
        for op in ops:
            ET.SubElement(ui, 'operation', name=op + '()')
        provided = ET.SubElement(context, 'userinterface', name='InterSystemInterface')
        for name in s['providedEntityInterfaces']:
            ET.SubElement(provided, 'operation', name='find' + name + 'byID()')
        for name in s['entities']:
            spec = entities[name]
            ent = ET.SubElement(context, 'entity', name=name)
            if is_java:
                ent.set('javaSource',spec['sourceFile']);ent.set('javaQualifiedName',spec['qualifiedName'])
            if spec['parent']:
                ent.set('superEntity', enpaths[spec['parent']])
            for a in spec['attributes']:
                prop = ET.SubElement(ent, 'attributes', name=a['name'])
                typ = 'req:JavaTypeCS' if is_java else 'req:EnumTypeCS' if '[' in a['type'] else 'req:CollectionTypeCS' if '(' in a['type'] else 'req:PrimitiveTypeCS'
                ET.SubElement(prop, 'type', {'{' + XSI + '}type': typ, 'name': a['type']})
            for r in spec['references']:
                reference=ET.SubElement(ent, 'reference', name=r['name'], entity=enpaths[r['type']], type=r['relation'],
                              ismultiple=str(r['multiple']).lower(), isReverseAssoication=str(r['reverse']).lower(),
                              isComposition=str(r['relation'] == 'Composition').lower(),
                              isAggregation=str(r['relation'] == 'Aggregation').lower())
                if is_java:reference.set('evidenceKind',r['evidenceKind'])
    ET.indent(root, space='  ')
    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(output, encoding='utf-8', xml_declaration=True)
    return output
