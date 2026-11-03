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
