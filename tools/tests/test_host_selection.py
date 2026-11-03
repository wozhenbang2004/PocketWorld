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

    def test_workpack_pr_entries_are_same_repository_and_bounded_separately(self):
        import json
        import re
        s = (M.ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertRegex(s, r'(?m)^  pull_request:\n    branches: \[main\]$')
        self.assertNotIn('pull_request_target:', s)
        host = s.split('  host-tests:\n', 1)[1]
        guard = re.search(r'(?m)^    if: (.+)$', host).group(1)
        self.assertEqual(
            "${{ github.event.repository.private && (github.event_name == 'workflow_dispatch' && inputs.target == 'host-tests' || github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && (github.head_ref == 'pro/dsh-runtime-continuity' || github.head_ref == 'pro/workpack-06-model-quality' || github.head_ref == 'pro/workpack-05-memory-quality' || github.head_ref == 'pro/workpack-03-phone-api-cli' || github.head_ref == 'pro/workpack-02-phone-control' || github.head_ref == 'pro/mika-full-takeover-20261103')) }}",
            guard)
        model = 'model-quality-routing,model-quality-observability,model-quality-sources,model-evaluation,gateway-continuation,runner-budget-stream'
        runtime = 'native-normal-cleanup,runtime-development-continuity,runtime-preparation-cancel,runtime-recovery-admission,native-duplex-pipes,runtime-activity,runtime-thermal-log-policy,runtime-diagnostic-metadata,request-clock,runtime-output-display,runtime-health,script-artifact-publication,managed-skill-private-artifacts,runner-repeat-notice,runtime-log-tail-recovery,legacy-command-receipt-recovery'
        memory = 'memory-recall-quality,memory-evidence-plan,memory-method-quality'
        phone = 'phone-task-platform,' + memory
        advanced = 'phone-setup-recovery,phone-input-extensions,phone-semantic-profile,phone-access-history,phone-observation-boundaries,phone-sensitive-owner,local-phone-leases,phone-audio-owner,phone-task-platform,aion-conversations,' + model
        self.assertIn("github.head_ref == 'pro/workpack-06-model-quality' && '" + model + "' || github.head_ref == 'pro/workpack-03-phone-api-cli' && '" + phone + "' || '" + memory + "') || inputs.host_groups }}", host)
        self.assertIn("github.head_ref == 'pro/workpack-02-phone-control' && '" + advanced + "'", host)
        self.assertIn("github.head_ref == 'pro/dsh-runtime-continuity' && '" + runtime + "'", host)
        registry = json.loads((M.ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual(model.split(','), M.selected(registry, '', model))
        self.assertEqual(runtime.split(','), M.selected(registry, '', runtime))
        self.assertLessEqual(len(runtime.split(',')), 16)
        self.assertEqual(memory.split(','), M.selected(registry, '', memory))
        self.assertEqual(phone.split(','), M.selected(registry, '', phone))
        self.assertEqual(advanced.split(','), M.selected(registry, '', advanced))
        self.assertFalse(set(model.split(',')) & set(phone.split(',')))
        self.assertFalse(set(model.split(',')) & set(memory.split(',')))
        self.assertIn("HOST_TEST_GROUP: ${{ github.event_name == 'pull_request' && (github.head_ref == 'pro/dsh-runtime-continuity' && 'native-normal-cleanup' || github.head_ref == 'pro/workpack-02-phone-control' && 'phone-setup-recovery' || github.head_ref == 'pro/workpack-06-model-quality' && 'model-quality-routing' || github.head_ref == 'pro/workpack-03-phone-api-cli' && 'phone-task-platform' || 'memory-recall-quality') || inputs.host_group }}", host)
        apk = s.split('  apk:\n', 1)[1].split('  host-tests:\n', 1)[0]
        self.assertIn("if: ${{ github.event.repository.private && github.event_name != 'pull_request' && (github.event_name != 'workflow_dispatch' || inputs.target != 'host-tests') }}", apk)
