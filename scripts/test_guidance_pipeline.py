"""Offline tests of paired guidance reporting; no API calls or search."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('guidance_pipeline', ROOT/'scripts/run-case.py')
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)

class GuidancePipelineTests(unittest.TestCase):
    def test_only_explicit_completed_paired_subset_is_aggregated(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for arm in ('off', 'numeric', 'jev'):
                target = root/'CoCoME/online-jev'
                if arm != 'off': target = target/('guidance-'+arm)
                target.mkdir(parents=True)
                row = dict(hv=.5, representative=dict(metrics=[2,.4,.3,.6,.2]),
                           run=dict(config=dict(seed=101, generations=20), completedGenerations=20, evaluations=366))
                (target/'run-jev-101.json').write_text(json.dumps(row))
            summary = pipeline.summarize_guidance(root, 'CoCoME', 'jev', [101])
            self.assertEqual(summary['seeds'], [101])
            self.assertEqual(len(summary['rows']), 3)
            self.assertEqual(summary['perArm']['jev']['meanMetrics']['IFN'], 2)
            self.assertEqual(summary['samplesPerArm'], 1)
            self.assertIn('not equal', summary['note'])
            with self.assertRaises(FileNotFoundError):
                pipeline.summarize_guidance(root, 'CoCoME', 'jev', [101,103])
            row['run']['completedGenerations'] = 19
            (target/'run-jev-101.json').write_text(json.dumps(row))
            with self.assertRaisesRegex(ValueError, 'Incomplete'):
                pipeline.summarize_guidance(root, 'CoCoME', 'jev', [101])

    def test_guidance_config_does_not_serialize_runtime_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(profile='formal', selector='jev', input=ROOT/'cocome.remodel', case='CoCoME',
                                      calibration_seeds=[11], test_seeds=[101], endpoint='https://example.invalid/v1', model='fixed-jev',
                                      control_options=dict(mode='jev', intervalGenerations=10),
                                      guidance_options=dict(mode='jev', generator=dict(apiKeyEnv='CHATFIRE_API_KEY')))
            config = pipeline.configuration(args, Path(directory))
            self.assertEqual(config['evaluationBudget'], 0)
            self.assertEqual(config['guidance']['generator']['apiKeyEnv'], 'CHATFIRE_API_KEY')
            self.assertNotIn('apiKey', config['guidance']['generator'])
            self.assertEqual(config['onlineControl']['intervalGenerations'], 10)

if __name__ == '__main__':
    unittest.main()
