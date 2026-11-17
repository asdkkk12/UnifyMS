"""Tests of ten-run statistics and single-arm orchestration; no API requests."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import json
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('single_summary',ROOT/'scripts/summarize-guidance-single.py')
summary=importlib.util.module_from_spec(spec);spec.loader.exec_module(summary)

class SingleSummaryTests(unittest.TestCase):
    def test_mean_uses_all_ten_and_sample_sd(self):
        values=list(range(10));d=summary.describe(values)
        self.assertEqual(d['mean'],4.5)
        self.assertAlmostEqual(d['sampleStandardDeviation'],(82.5/9)**.5)
        self.assertEqual(d['minimum'],0);self.assertEqual(d['maximum'],9)
        with self.assertRaises(ValueError): summary.describe(values[:9])

    def test_unknown_usage_is_not_zero(self):
        self.assertIsNone(summary.usage_total([{'totalTokens':20},{'totalTokens':None}]))
        self.assertEqual(summary.usage_total([{'totalTokens':20},{'totalTokens':30}]),50)

    def test_incomplete_seed_set_is_rejected_before_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory);(path/'run-config.json').write_text(json.dumps({'validationSeeds':[101]}))
            with self.assertRaisesRegex(ValueError,'ten distinct'):
                summary.build(path)

    def test_single_arm_flag_does_not_require_three_arms(self):
        result=subprocess.run([sys.executable,str(ROOT/'scripts/run-guidance-study.py'),'--help'],capture_output=True,text=True)
        self.assertEqual(result.returncode,0);self.assertIn('--arms',result.stdout)
        self.assertIn('jev for only LLM',result.stdout)

if __name__=='__main__': unittest.main()
