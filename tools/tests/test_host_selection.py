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
            "${{ github.event.repository.private && (github.event_name == 'workflow_dispatch' && inputs.target == 'host-tests' || github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && (github.head_ref == 'pro/operit-full-takeover-20261103' || github.head_ref == 'pro/workpack-04-mcp-skills' || github.head_ref == 'pro/dsh-runtime-continuity' || github.head_ref == 'pro/workpack-06-model-quality' || github.head_ref == 'pro/workpack-05-memory-quality' || github.head_ref == 'pro/workpack-03-phone-api-cli' || github.head_ref == 'pro/workpack-02-phone-control' || github.head_ref == 'pro/mika-full-takeover-20261103' || github.head_ref == 'integrate/pr8-pr15-20261103' || github.head_ref == 'pro/memory-full-report-takeover')) }}",
            guard)
        ecosystem = 'mcp-ecosystem,mcp-local-discovery,mcp-stdio-framing,mcp-prompts,mcp-health,aion-mcp-skills,managed-skills,managed-skill-private-artifacts,magpie-work-profile,aion-work-profile-targets,memory-recall-quality,memory-evidence-plan,memory-method-quality,phone-task-platform,gateway-continuation,project-git-isolation'
        files = 'project-file-search,project-file-operations'
        compatibility = 'model-quality-routing,model-quality-observability,model-quality-sources,model-evaluation,runner-budget-stream'
        runtime_compatibility = 'native-normal-cleanup,native-duplex-pipes,runtime-preparation-cancel,runtime-development-continuity,runtime-output-display,request-clock'
        model = 'model-quality-routing,model-quality-observability,model-quality-sources,model-evaluation,gateway-continuation,runner-budget-stream'
        runtime = 'native-normal-cleanup,runtime-development-continuity,runtime-preparation-cancel,runtime-recovery-admission,native-duplex-pipes,runtime-activity,runtime-thermal-log-policy,runtime-diagnostic-metadata,request-clock,runtime-output-display,runtime-health,script-artifact-publication,managed-skill-private-artifacts,runner-repeat-notice,runtime-log-tail-recovery,legacy-command-receipt-recovery'
        memory = 'memory-recall-quality,memory-evidence-plan,memory-method-quality'
        phone = 'phone-task-platform,' + memory
        advanced = 'phone-setup-recovery,phone-input-extensions,phone-semantic-profile,phone-access-history,phone-observation-boundaries,phone-sensitive-owner,local-phone-leases,phone-audio-owner,phone-task-platform,aion-conversations,model-quality-routing,model-quality-sources,gateway-continuation,runner-budget-stream,request-clock,runtime-output-display'
        self.assertIn("github.head_ref == 'pro/workpack-06-model-quality' && '" + model + "' || github.head_ref == 'pro/workpack-03-phone-api-cli' && '" + phone + "' || '" + memory + "') || inputs.host_groups }}", host)
        self.assertIn("(github.head_ref == 'pro/workpack-02-phone-control' || github.head_ref == 'integrate/pr8-pr15-20261103') && '" + advanced + "'", host)
        self.assertIn("github.head_ref == 'pro/dsh-runtime-continuity' && '" + runtime + "'", host)
        self.assertIn("github.head_ref == 'pro/memory-full-report-takeover' && 'memory-assertion-recall'", host)
        takeover = 'phone-setup-recovery,phone-input-extensions,phone-semantic-profile,phone-access-history,phone-observation-boundaries,phone-sensitive-owner,local-phone-leases,phone-audio-owner,phone-task-platform,aion-conversations,memory-recall-quality,memory-evidence-plan,memory-method-quality,model-quality-routing,model-quality-sources,gateway-continuation'
        self.assertIn("HOST_TEST_GROUPS: ${{ github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && (github.head_ref == 'pro/memory-full-report-takeover' && 'memory-assertion-recall' || github.head_ref == 'pro/mika-full-takeover-20261103' && '" + takeover + "' || github.head_ref == 'pro/operit-full-takeover-20261103' && (matrix.batch == 'project-files' && '" + files + "' || matrix.batch == 'runtime-compatibility' && '" + runtime_compatibility + "' || '" + ecosystem + "') || github.head_ref == 'pro/workpack-04-mcp-skills' && (matrix.batch == 'model-compatibility' && '" + compatibility + "' || matrix.batch == 'runtime-compatibility' && '" + runtime_compatibility + "' || '" + ecosystem + "') || github.head_ref == 'pro/dsh-runtime-continuity' && '" + runtime + "' || (github.head_ref == 'pro/workpack-02-phone-control' || github.head_ref == 'integrate/pr8-pr15-20261103') && '" + advanced + "' || github.head_ref == 'pro/workpack-06-model-quality' && '" + model + "' || github.head_ref == 'pro/workpack-03-phone-api-cli' && '" + phone + "' || '" + memory + "') || inputs.host_groups }}", host)
        registry = json.loads((M.ROOT / 'tools/host-tests.json').read_text())
        self.assertEqual(ecosystem.split(','), M.selected(registry, '', ecosystem))
        self.assertEqual(compatibility.split(','), M.selected(registry, '', compatibility))
        self.assertEqual(16, len(ecosystem.split(',')))
        self.assertEqual(files.split(','), M.selected(registry, '', files))
        self.assertEqual(2, len(files.split(',')))
        self.assertFalse(set(files.split(',')) & set(ecosystem.split(',')))
        self.assertEqual(5, len(compatibility.split(',')))
        self.assertEqual(runtime_compatibility.split(','), M.selected(registry, '', runtime_compatibility))
        self.assertEqual(6, len(runtime_compatibility.split(',')))
        self.assertFalse(set(runtime_compatibility.split(',')) & set(ecosystem.split(',')))
        self.assertFalse(set(runtime_compatibility.split(',')) & set(compatibility.split(',')))
        self.assertFalse(set(ecosystem.split(',')) & set(compatibility.split(',')))
        with self.assertRaises(ValueError):
            M.selected(registry, '', ecosystem + ',' + compatibility)
        self.assertEqual(model.split(','), M.selected(registry, '', model))
        self.assertEqual(runtime.split(','), M.selected(registry, '', runtime))
        self.assertLessEqual(len(runtime.split(',')), 16)
        self.assertEqual(memory.split(','), M.selected(registry, '', memory))
        self.assertEqual(phone.split(','), M.selected(registry, '', phone))
        self.assertEqual(advanced.split(','), M.selected(registry, '', advanced))
        self.assertEqual(takeover.split(','), M.selected(registry, '', takeover))
        self.assertEqual(16, len(takeover.split(',')))
        self.assertFalse(set(model.split(',')) & set(phone.split(',')))
        self.assertFalse(set(model.split(',')) & set(memory.split(',')))
        self.assertIn("HOST_TEST_GROUP: ${{ github.event_name == 'pull_request' && (github.head_ref == 'pro/operit-full-takeover-20261103' && (matrix.batch == 'project-files' && 'project-file-search' || matrix.batch == 'runtime-compatibility' && 'native-normal-cleanup' || 'mcp-ecosystem') || github.head_ref == 'pro/workpack-04-mcp-skills' && (matrix.batch == 'model-compatibility' && 'model-quality-routing' || matrix.batch == 'runtime-compatibility' && 'native-normal-cleanup' || 'mcp-ecosystem') || github.head_ref == 'pro/dsh-runtime-continuity' && 'native-normal-cleanup' || (github.head_ref == 'pro/workpack-02-phone-control' || github.head_ref == 'integrate/pr8-pr15-20261103') && 'phone-setup-recovery' || github.head_ref == 'pro/workpack-06-model-quality' && 'model-quality-routing' || github.head_ref == 'pro/workpack-03-phone-api-cli' && 'phone-task-platform' || 'memory-recall-quality') || inputs.host_group }}", host)
        self.assertIn("batch: ${{ fromJSON(github.event_name == 'pull_request' && (github.head_ref == 'pro/operit-full-takeover-20261103' && '[\"ecosystem\",\"project-files\",\"runtime-compatibility\"]' || github.head_ref == 'pro/workpack-04-mcp-skills' && '[\"ecosystem\",\"model-compatibility\",\"runtime-compatibility\"]') || '[\"selected\"]') }}", host)
        self.assertIn('fail-fast: false', host)
        self.assertIn("${{ matrix.batch != 'selected' && format('-{0}', matrix.batch) || '' }}", host)
        self.assertIn('python3 -B -m unittest discover -s tools/tests -p test_host_selection.py -v', host)
        apk = s.split('  apk:\n', 1)[1].split('  host-tests:\n', 1)[0]
        self.assertIn("if: ${{ github.event.repository.private && github.event_name != 'pull_request' && (github.event_name != 'workflow_dispatch' || inputs.target != 'host-tests') }}", apk)
