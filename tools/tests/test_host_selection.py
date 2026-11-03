import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('selection', Path(__file__).resolve().parents[1] / 'host-selection.py')
M = importlib.util.module_from_spec(spec)
spec.loader.exec_module(M)

class HostSelectionTest(unittest.TestCase):
    def test_single_preserved(self):
        self.assertEqual(['one'], M.selected({'one': {}}, 'one', ''))
    def test_explicit_batch_replaces_default_and_deduplicates(self):
        self.assertEqual(['two','one'], M.selected({'one': {}, 'two': {}, 'default': {}}, 'default', ' two,one,two '))
    def test_no_unknown_empty_or_shell_input(self):
        for batch in ('one,', ',one', 'all', 'one,$(touch x)', 'one\n--online'):
            with self.subTest(batch=batch), self.assertRaises(ValueError):
                M.selected({'one': {}}, 'one', batch)
    def test_limits(self):
        registry = {str(i): {} for i in range(17)}
        with self.assertRaises(ValueError): M.selected(registry, '0', ','.join(registry))
        with self.assertRaises(ValueError): M.selected({'one': {}}, 'one', 'x'*1025)
    def test_browser_fixture_modes_are_not_silently_skipped(self):
        for name in M.SPECIAL:
            self.assertEqual([name], M.selected({name:{}}, name, ''))
            with self.assertRaises(ValueError): M.selected({name:{}}, name, name)
    def test_workflow_uses_quoted_explicit_arguments_and_single_compile(self):
        s=(M.ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('host_groups:',s)
        self.assertIn('tools/host-selection.py --single "$HOST_TEST_GROUP" --batch "$HOST_TEST_GROUPS"',s)
        self.assertIn('python3 -B tools/host-tests.py "${HOST_ARGS[@]}" --online',s)
        self.assertNotIn('eval "$HOST_TEST_GROUPS"',s)
        self.assertIn("'host-selected-batch'",s)

    def test_batch_companion_checks_use_validated_actual_selection(self):
        s=(M.ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertIn('id: selected_host_tests', s)
        self.assertIn("contains(fromJSON(steps.selected_host_tests.outputs.groups || '[]'), 'remote-transport')", s)
        self.assertIn("inputs.host_groups == '' && inputs.host_group == 'browser-long-page'", s)

    def test_model_quality_pr_entry_is_same_repository_and_exact_branch_only(self):
        import json
        import re
        s = (M.ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertRegex(s, r'(?m)^  pull_request:\n    branches: \[main\]$')
        self.assertNotIn('pull_request_target:', s)
        host = s.split('  host-tests:\n', 1)[1]
        guard = re.search(r'(?m)^    if: (.+)$', host).group(1)
        self.assertEqual(
            "${{ github.event.repository.private && (github.event_name == 'workflow_dispatch' && inputs.target == 'host-tests' || github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && github.head_ref == 'pro/workpack-06-model-quality') }}",
            guard)
        batch = 'model-quality-routing,model-quality-observability,model-quality-sources,model-evaluation,gateway-continuation,runner-budget-stream'
        self.assertIn("HOST_TEST_GROUPS: ${{ github.event_name == 'pull_request' && '" + batch + "' || inputs.host_groups }}", host)
        registry = json.loads((M.ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual(batch.split(','), M.selected(registry, '', batch))
        self.assertIn("HOST_TEST_GROUP: ${{ github.event_name == 'pull_request' && 'model-quality-routing' || inputs.host_group }}", host)
        apk = s.split('  apk:\n', 1)[1].split('  host-tests:\n', 1)[0]
        self.assertIn("if: ${{ github.event.repository.private && github.event_name != 'pull_request' && (github.event_name != 'workflow_dispatch' || inputs.target != 'host-tests') }}", apk)
