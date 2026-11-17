"""Offline semantic, export and diagram regression tests (no NSGA-II or API calls)."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from merm_model import parse_req, export_req, remodel_entities, digest, input_digest, XMI, XSI
from merm_view import render_svg, render_html, service_order

ROOT = Path(__file__).resolve().parents[1]
STUDY = ROOT / 'output/cocome-jev-v2-real-20261031'
RUN = STUDY / 'experiment/CoCoME/run-jev-101.json'
SNAP = STUDY / 'model-snapshot.json'


def fixture(contents, domain=''):
    return ('<xmi:XMI xmlns:xmi="'+XMI+'" xmlns:req="http://www.example.org/req" xmlns:xsi="'+XSI+'">'
            '<req:RequirementModel><UseCaseModel><usecasecontext name="Microservice 0">'+contents+
            '</usecasecontext></UseCaseModel><DomainModel><entitycontext name="EntityContext0">'+domain+
            '</entitycontext></DomainModel></req:RequirementModel></xmi:XMI>')


class MermTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def parse(self, text):
        p = self.path / 'fixture.req'
        p.write_text(text, encoding='utf-8')
        return parse_req(p)

    def test_all_eight_legacy_files(self):
        expected = dict(airms=(4,22,15), atm=(2,6,3), cargotrackingsystem2=(2,9,9),
                        cocome=(3,10,13), library=(4,14,11), loan=(3,10,8), onlineedu=(3,7,6), trainticket=(4,50,58))
        for name, counts in expected.items():
            with self.subTest(name=name):
                path = ROOT / 'generatedSolution' / (name+'.req')
                before = digest(path)
                m = parse_req(path)
                self.assertEqual(tuple(m['counts'][k] for k in ['services','uc','entity']), counts)
                self.assertFalse([d for d in m['diagnostics'] if d['severity']=='error'])
                self.assertEqual(digest(path), before)
                self.assertIn('missing-xmi-namespace', [d['code'] for d in m['diagnostics']])

    def test_duplicate_names_preserve_distinct_paths_and_empty_service(self):
        m = self.parse(fixture('<uc name="same"/><uc name="same"/>'))
        self.assertEqual(len({n['id'] for n in m['nodes']}), 2)
        self.assertEqual(len(m['services']), 1)
        self.assertIn('duplicate-name', [d['code'] for d in m['diagnostics']])

    def test_dangling_and_external_reference(self):
        m = self.parse(fixture('<uc name="a" relateduc="/0/@missing foreign.req#/0"/>'))
        self.assertEqual({d['code'] for d in m['diagnostics'] if d['severity']=='error'}, {'dangling-reference','external-reference'})
        self.assertEqual(m['edges'], [])

    def test_dtd_forbidden(self):
        with self.assertRaisesRegex(ValueError, 'forbidden'):
            self.parse('<!DOCTYPE a [<!ENTITY external SYSTEM "file:///etc/passwd">]>'+fixture(''))

    def test_inheritance_and_usecase_relations(self):
        m = self.parse(fixture('<uc name="a" UCRelation="include" relateduc="/0/@UseCaseModel/@usecasecontext.0/@uc.1"/><uc name="b"/>',
                              '<entity name="Parent"/><entity name="Child" superEntity="/0/@DomainModel/@entitycontext.0/@entity.0"/>'))
        self.assertEqual({e['kind'] for e in m['edges']}, {'include','generalization'})
        self.assertTrue(all(e['provenance']['value'] for e in m['edges']))

    def test_ambiguous_contexts_are_not_guessed(self):
        m = self.parse(fixture('<uc name="a"/>').replace('EntityContext0','BusinessData'))
        self.assertEqual(len(m['services']), 2)
        self.assertIn('ambiguous-service-mapping', [d['code'] for d in m['diagnostics']])

    def test_wrong_reference_type(self):
        m = self.parse(fixture('<uc name="a" relateduc="/0/@DomainModel/@entitycontext.0"/>'))
        self.assertIn('reference-type', [d['code'] for d in m['diagnostics']])

    def test_unpaired_interface(self):
        req = fixture('').replace('<DomainModel>', '<DomainModel><systeminterface xsi:type="req:ReceiveInterface" name="need" entitycontext="/0/@DomainModel/@entitycontext.0"/>')
        m = self.parse(req)
        self.assertEqual(m['counts']['interfaceEdges'], 0)
        self.assertIn('unpaired-interface', [d['code'] for d in m['diagnostics']])

    def export(self, run=RUN, source=None):
        return export_req(run, source or ROOT/'cocome.remodel', SNAP, self.path/'latest.req')

    def test_latest_export_membership_interfaces_and_metadata(self):
        m = parse_req(self.export())
        self.assertEqual([m['counts'][k] for k in ['services','uc','entity','interfaceEdges','serviceEdges']], [4,10,13,5,4])
        self.assertEqual(m['diagnostics'], [])
        self.assertEqual(m['metadata']['configId'], 'C3')
        self.assertEqual(m['metadata']['inputHash'], digest(ROOT/'cocome.remodel'))
        run = json.loads(RUN.read_text())
        for s in run['representative']['services']:
            for key, kind in [('useCases','uc'),('entities','entity')]:
                self.assertEqual({n['name'] for n in m['nodes'] if n['service']=='service-'+str(s['id']) and n['kind']==kind}, set(s[key]))
        entity = next(n for n in m['nodes'] if n['name']=='Store' and n['kind']=='entity')
        self.assertIn(dict(name='Id', type='Integer'), entity['attributes'])
        self.assertTrue(any(e['kind']=='generalization' for e in m['edges']))

    def test_export_refuses_wrong_input_hash(self):
        source = self.path/'changed.remodel'
        source.write_bytes((ROOT/'cocome.remodel').read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError, 'hashes'):
            self.export(source=source)

    def test_export_refuses_wrong_chromosome_and_interfaces(self):
        run = json.loads(RUN.read_text())
        for kind in ['chromosome','interfaces']:
            with self.subTest(kind=kind):
                altered = copy.deepcopy(run)
                if kind=='chromosome':
                    altered['representative']['chromosome'][0] = 1
                else:
                    altered['representative']['services'][0]['requiredEntityInterfaces'] = []
                path = self.path/(kind+'.json')
                path.write_text(json.dumps(altered))
                with self.assertRaisesRegex(ValueError, 'mismatch|matrix rules'):
                    self.export(run=path)

    def test_export_strict_source_subset(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            remodel_entities('Entity A {\nunsupported syntax\n}')

    def test_java_directory_hash_and_export(self):
        import hashlib
        source=self.path/'java';source.mkdir()
        file=source/'A.java';file.write_text('class A { private Long id; }')
        expected=hashlib.sha256(b'A.java\0'+file.read_bytes()+b'\0').hexdigest()
        self.assertEqual(input_digest(source),expected)
        (source/'config.yml').write_text('not part of source hash')
        self.assertEqual(input_digest(source),expected)
        snapshot=dict(inputHash=expected,sourceKind='java-directory-legacy',useCaseNames={'0':'browse'},entityNames={'0':'A'},
                      useCaseEntityMatrix=[[1]],entityRelationMatrix=[[1]],actors={'Sample':['browse']},useCaseOperations={'browse':['load']},
                      entitySchemas={'A':dict(name='A',sourceFile='A.java',qualifiedName='A',javaSuperType=None,attributes=[dict(name='id',type='Long')])},
                      sourceAudit=dict(methodSources={'browse':['SampleController.java']},actorSources={'Sample':['SampleController.java']}))
        record=dict(run=dict(inputHash=expected,taskKey='test',config=dict(id='C0',seed=101)),representative=dict(valid=True,serviceCount=1,chromosome=[0,0],
                     services=[dict(id=0,useCases=['browse'],entities=['A'],providedEntityInterfaces=[],requiredEntityInterfaces=[])]))
        sp,rp=self.path/'snapshot.json',self.path/'run.json'
        sp.write_text(json.dumps(snapshot));rp.write_text(json.dumps(record))
        result=export_req(rp,source,sp,self.path/'java.req');m=parse_req(result)
        self.assertFalse(m['diagnostics'])
        self.assertEqual(m['metadata']['sourceKind'],'java-directory-legacy')
        reference=next(e for e in m['edges'] if e['kind']=='association')
        self.assertEqual(reference['provenance']['attributes']['evidenceKind'],'legacy-lexical-matrix')
        self.assertEqual(input_digest(source),expected)

    def test_cycles_and_deterministic_geometry_large_model(self):
        m = parse_req(ROOT/'generatedSolution/trainticket.req')
        for view in ['usecases','domain']:
            svg, layout = render_svg(m, view)
            self.assertEqual(svg, render_svg(m, view)[0])
            root = ET.fromstring(svg)
            _, _, width, height = map(float, root.get('viewBox').split())
            for x,y,w,h in layout['nodes'].values():
                self.assertGreaterEqual(x,0);self.assertGreaterEqual(y,0)
                self.assertLessEqual(x+w,width);self.assertLessEqual(y+h,height)
            boxes = list(layout['services'].values())
            for i,a in enumerate(boxes):
                for b in boxes[i+1:]:
                    self.assertTrue(a[0]+a[2]<=b[0] or b[0]+b[2]<=a[0] or a[1]+a[3]<=b[1] or b[1]+b[3]<=a[1])
        latest = parse_req(self.export())
        order = service_order(latest)
        self.assertEqual(abs(order.index('service-0')-order.index('service-3')),1)

    def test_html_has_no_external_assets_and_safe_payload(self):
        m = self.parse(fixture('<uc name="&lt;/script&gt;&lt;script&gt;bad()"/>'))
        page = render_html(m, render_svg(m,'usecases')[0], render_svg(m,'domain')[0])
        self.assertNotIn('<script>bad()',page)
        self.assertNotIn('<script src=',page)
        self.assertNotIn('<link ',page)
        self.assertIn('\\u003c/script>',page)

    def test_readable_overview_preserves_edges_and_uses_clear_gutters(self):
        from merm_diagram import blocked
        m=parse_req(self.export())
        nodes={n['id']:n for n in m['nodes']}
        svg,layout=render_svg(m,'domain')
        interfaces=[e for e in m['edges'] if e['kind']=='interface']
        self.assertEqual(sum(layout['edges'][e['id']]['defaultVisible'] for e in interfaces),4)
        self.assertEqual(len(interfaces),5)
        for edge in interfaces:
            points=layout['edges'][edge['id']]['points']
            self.assertTrue(all(not blocked(a,b,list(layout['services'].values())) for a,b in zip(points,points[1:])))
        for edge in m['edges']:
            if edge['kind']=='interface' or edge['id'] not in layout['edges']:continue
            self.assertFalse(layout['edges'][edge['id']]['defaultVisible'])
            for endpoint,nid in [(layout['edges'][edge['id']]['points'][0],edge['source']),
                                 (layout['edges'][edge['id']]['points'][-1],edge['target'])]:
                x,y,w,h=layout['nodes'][nid]
                self.assertTrue(x<=endpoint[0]<=x+w and y<=endpoint[1]<=y+h)
                self.assertTrue(endpoint[0] in (x,x+w) or endpoint[1] in (y,y+h))
        self.assertIn('readable-panels-v2',layout['version'])

    def test_actor_proxies_have_original_ids_and_stay_outside_services(self):
        m=parse_req(self.export())
        _,layout=render_svg(m,'usecases')
        actors={n['id']:n for n in m['nodes'] if n['kind']=='actor'}
        manager=next(n for n in actors.values() if n['name']=='StoreManager')
        proxies=[p for p in layout['instances'] if p['id']==manager['id']]
        self.assertEqual(len(proxies),4)
        for instance in layout['instances']:
            self.assertIn(instance['id'],actors)
            a=instance['box']
            for b in layout['services'].values():
                self.assertTrue(a[0]+a[2]<=b[0] or b[0]+b[2]<=a[0] or a[1]+a[3]<=b[1] or b[1]+b[3]<=a[1])
        self.assertEqual(sum(e['kind']=='actor-usecase' for e in m['edges']),10)

    def test_cli_preserves_existing_outputs(self):
        out = self.path/'cli'
        cmd = [sys.executable,str(ROOT/'scripts/render-merm.py'),'--input',str(ROOT/'generatedSolution/atm.req'),'--output',str(out)]
        first = subprocess.run(cmd,capture_output=True,text=True)
        self.assertEqual(first.returncode,0,first.stderr)
        before = digest(out/'atm-merm.html')
        second = subprocess.run(cmd,capture_output=True,text=True)
        self.assertNotEqual(second.returncode,0)
        self.assertEqual(before,digest(out/'atm-merm.html'))


if __name__ == '__main__':
    unittest.main()
