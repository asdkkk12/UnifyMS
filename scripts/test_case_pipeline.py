"""Report/binding tests use existing measured data; never request JEV or rerun searches."""
import argparse
import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('case_pipeline', ROOT/'scripts/run-case.py')
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)
STUDY = ROOT/'output/cocome-jev-v2-real-20261031'


class CasePipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.output = Path(self.tmp.name)
        self.args = argparse.Namespace(input=ROOT/'cocome.remodel', case='CoCoME', selector='jev')

    def tearDown(self):
        self.tmp.cleanup()

    def test_real_report_matches_measured_result_and_new_req(self):
        report = pipeline.publish_report(self.args,self.output,STUDY/'model-snapshot.json',STUDY/'experiment',[101])
        text = report.read_text()
        self.assertIn('选择 **C3**',text)
        self.assertIn('0.886637349312',text)
        self.assertIn('0.7668',text)
        self.assertIn('4 条有向服务依赖',text)
        self.assertIn('不是 JEV 原文',text)
        self.assertIn('退化指标为 CMQ',text)
        self.assertIn('未取得',text)
        self.assertTrue((self.output/'visualizations/seed-101/cocome.req').exists())

    def test_selection_version_and_missing_binding_rejected(self):
        cal = pipeline.read(STUDY/'experiment/CoCoME/calibration.json')
        original = pipeline.read(STUDY/'experiment/CoCoME/selection-jev.json')
        directory = self.output/'CoCoME'
        directory.mkdir()
        pipeline.write_json(directory/'calibration.json',cal)
        for change in [dict(selectionPolicyVersion='old'),dict(evidenceHash=None),dict(mock=True)]:
            record=copy.deepcopy(original);record.update(change)
            pipeline.write_json(directory/'selection-jev.json',record)
            with self.assertRaises(ValueError):
                pipeline.evidence_context(self.output,'CoCoME','jev')

    def test_calibration_seed_not_allowed_in_report(self):
        with self.assertRaisesRegex(ValueError,'overlaps'):
            pipeline.publish_report(self.args,self.output,STUDY/'model-snapshot.json',STUDY/'experiment',[11])

    def test_seed_parsing_and_overlap(self):
        self.assertEqual(pipeline.seeds('11,13'),[11,13])
        with self.assertRaises(argparse.ArgumentTypeError):pipeline.seeds('11,11')
        args=argparse.Namespace(calibration_seeds=[11],test_seeds=[11])
        with self.assertRaisesRegex(ValueError,'disjoint'):pipeline.configuration(args,self.output)

    def test_failed_preflight_does_not_start_pipeline(self):
        source=self.output/'unsupported.remodel'
        source.write_text('Entity Bad {\nthis is unsupported\n}')
        destination=self.output/'never-created'
        result=subprocess.run([sys.executable,str(ROOT/'scripts/run-case.py'),'run','--input',str(source),
                               '--output',str(destination),'--selector','mock'],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('preflight failed',result.stderr)
        self.assertFalse(destination.exists())

    def test_preserves_enum_and_parenthesized_association(self):
        from merm_model import remodel_entities
        entities=remodel_entities((ROOT/'library.remodel').read_text())
        user=entities['User']
        self.assertIn('Sex[M|F]',[a['type'] for a in user['attributes']])
        self.assertEqual(user['references'][0]['relation'],'Association')
        self.assertTrue(user['references'][0]['multiple'])


if __name__=='__main__':
    unittest.main()
