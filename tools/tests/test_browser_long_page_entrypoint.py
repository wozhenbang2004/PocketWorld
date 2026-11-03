"""Lightweight post-step contracts, not a substitute for actually running Chromium."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('browser_long_page', Path(__file__).parents[1] / 'browser-long-page.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BrowserLongPageEntrypointTest(unittest.TestCase):
    def report(self, root, status='passed'):
        run = root / 'run-fresh'
        run.mkdir()
        (run / 'classpath.txt').write_text('/classes/current\n/locked/android.jar\n/locked/json-20240303.jar\n')
        (run / 'result.json').write_text(json.dumps(dict(status=status, executed={'exact.NativeTest': 4})))
        return run

    def test_real_json_precedes_android_stubs_and_keeps_actual_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root)
            self.assertEqual('/locked/json-20240303.jar:/classes/current:/locked/android.jar', MODULE.classpath(root))

    def test_zero_stale_failed_or_missing_real_json_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(RuntimeError):
                MODULE.classpath(root)
            run = self.report(root, 'failed')
            with self.assertRaises(RuntimeError):
                MODULE.classpath(root)
            (run / 'result.json').write_text(json.dumps(dict(status='passed', executed={'exact.NativeTest': 4})))
            (run / 'classpath.txt').write_text('/classes/current\n/locked/android.jar\n')
            with self.assertRaises(RuntimeError):
                MODULE.classpath(root)
            (root / 'run-stale').mkdir()
            (root / 'run-stale/classpath.txt').write_text('/locked/json-20240303.jar\n')
            with self.assertRaises(RuntimeError):
                MODULE.classpath(root)

    def test_browser_install_baseline_and_post_step_are_opt_in_only(self):
        workflow = (MODULE.ROOT / '.github/workflows/build-apk.yml').read_text()
        for name in ['Prepare official isolated Chromium fixture runtime',
                     'Check out fixed pre-fix source for the real Chromium red case',
                     'Run original emitted long-DOM Chromium red and green fixtures']:
            step = workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]
            self.assertIn("if: ${{ inputs.host_groups == '' && inputs.host_group == 'browser-long-page' }}", step)
        self.assertIn('playwright@1.57.0', workflow)
        self.assertIn('ref: afba870564e83034339ad084c2e7247260ea0c9c', workflow)
        self.assertNotIn('2ca6a58', workflow)
        source = (MODULE.ROOT / 'tools/browser-long-page.py').read_text()
        self.assertIn("expected=3", source)
        self.assertIn("red.get('tailMissing') is not True", source)
        self.assertIn("out.mkdir(exist_ok=False)", source)


if __name__ == '__main__':
    unittest.main()
