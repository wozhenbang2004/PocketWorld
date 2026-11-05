"""Selection/reporting behavior only; process fixtures never prove App tests passed."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('host_tests', Path(__file__).parents[1] / 'host-tests.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
REGISTRY = json.loads(HOST.REGISTRY.read_text())


class MainChainEntrypointTest(unittest.TestCase):
    def recorded(self, output):
        reports = list(output.glob('run-*/result.json'))
        self.assertEqual(1, len(reports))
        return json.loads(reports[0].read_text())

    def test_fixed_suite_covers_login_model_message_tools_and_lifecycle_on_any_branch(self):
        required = {
            'dev.pocketengine.features.proxy.accounts.ChatGptLoginLifetimeTest',
            'dev.pocketengine.features.proxy.accounts.AccountInferenceRenewalTest',
            'dev.pocketengine.features.proxy.accounts.AccountRefreshCheckpointTest',
            'dev.pocketengine.features.proxy.accounts.AccountMainStreamTest',
            'dev.pocketengine.features.proxy.accounts.AccountGatewayResponsesTest',
            'dev.pocketengine.features.proxy.accounts.AccountModelContinuityTest',
            'dev.pocketengine.features.proxy.accounts.AccountInferenceRecoveryJourneyTest',
            'dev.pocketengine.runtime.models.ModelSelectionCoordinatorTest',
            'dev.pocketengine.runtime.ProjectReadReceiptRunnerTest',
            'dev.pocketengine.runtime.ProviderProtocolTest',
            'dev.pocketengine.runtime.RuntimeProtocolTest',
            'dev.pocketengine.NativeModelStreamPresentationTest',
            'dev.pocketengine.ui.OwnerChatCancelGuardTest',
            'dev.pocketengine.features.local.project.ProjectFileQueriesTest',
            'dev.pocketengine.features.local.project.ProjectHexReadTest',
            'dev.pocketengine.features.local.project.ProjectImageRunnerNativeTest',
            'dev.pocketengine.features.proxy.NativeRelayRedactorTest',
            'dev.pocketengine.core.RuntimeDiagnosticsTest',
            'dev.pocketengine.ui.ModelDiagnosticsMountedNativeTest',
            'dev.pocketengine.features.proxy.providers.ChatGptReadDeadlineTest',
            'dev.pocketengine.features.proxy.providers.ResponsesEmptyAssistantTest',
            'dev.pocketengine.features.proxy.providers.ChatGptTokenReceiptTimeTest',
            'dev.pocketengine.features.proxy.providers.ResponsesIncompleteItemTest',
            'dev.pocketengine.runtime.AccountContextLimitRecoveryTest',
            'dev.pocketengine.features.assistant.OwnerComposerPendingIdentityTest',
            'dev.pocketengine.runtime.ReferenceRefusalRunnerTest',
            'dev.pocketengine.runtime.ToolBatchTailReceiptTest',
            'dev.pocketengine.runtime.providers.ProtocolHistoryBudgetTest',
            'dev.pocketengine.core.ToolOutcomeTest',
            'dev.pocketengine.core.CapabilityPortReceiptAfterStopTest',
            'dev.pocketengine.features.plugins.PluginToolReceiptsTest',
            'dev.pocketengine.store.TaskRecoveryTest',
            'dev.pocketengine.store.TaskStoreMcpTasksTest',
            'dev.pocketengine.features.plugins.McpLocalInvocationNativeTest',
            'dev.pocketengine.features.FeatureHostStateTest',
            'dev.pocketengine.runtime.ExecutionPlanningRunnerTest',
            'dev.pocketengine.ui.web.PocketWebTaskActionsTest',
        }
        selections = []
        for branch in ('main', 'fix/new-unlisted-reliability-branch'):
            with self.subTest(branch=branch), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)
                with patch.dict(HOST.os.environ, {'GITHUB_HEAD_REF': branch}), \
                        patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                        patch.object(HOST.subprocess, 'run') as launch, \
                        contextlib.redirect_stdout(io.StringIO()):
                    code = HOST.main(['--suite', 'main-chain', '--suite', 'main-chain',
                                      '--group', 'mika-models', '--test',
                                      'dev.pocketengine.runtime.models.ModelSelectionCoordinatorTest',
                                      '--check', '--output', str(output)])
                self.assertEqual(0, code)
                launch.assert_not_called()
                report = self.recorded(output)
                self.assertEqual(['main-chain'], report['suites'])
                self.assertTrue(required.issubset(report['classes']))
                self.assertEqual(len(report['groups']), len(set(report['groups'])))
                self.assertEqual(len(report['classes']), len(set(report['classes'])))
                self.assertLess(len(report['groups']), len(REGISTRY))
                selections.append((report['groups'], report['classes']))
        self.assertEqual(selections[0], selections[1])

    def test_account_fixture_sources_are_selected_with_their_original_tests(self):
        entry = REGISTRY['chatgpt-provider-catalog']
        names = [entry['class']] + entry['additionalClasses'] + entry['helpers']
        for name in ('dev.pocketengine.features.proxy.accounts.AccountGatewayTest',
                     'dev.pocketengine.features.proxy.providers.FakeChatGpt',
                     'dev.pocketengine.features.proxy.providers.ResponsesFixtures',
                     'dev.pocketengine.features.proxy.AppAccountResponsesFixture',
                     'dev.pocketengine.features.proxy.providers.RelayDelayedFixture'):
            self.assertIn(name, names)
        # These Android lifecycle tests must retain the actual Robolectric prerequisite.
        self.assertTrue(REGISTRY['private-task-run-control']['robolectric'])

    def test_unknown_suite_does_not_launch_or_create_a_success_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            with patch.object(HOST.subprocess, 'run') as launch, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, HOST.main(['--suite', 'typo', '--output', str(output)]))
            launch.assert_not_called()
            self.assertEqual([], list(output.iterdir()))

    def test_successful_preflight_is_explicitly_not_a_test_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            stdout = io.StringIO()
            with patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                    patch.object(HOST.subprocess, 'run') as launch, contextlib.redirect_stdout(stdout):
                self.assertEqual(0, HOST.main(['--suite', 'main-chain', '--check', '--output', str(output)]))
            launch.assert_not_called()
            report = self.recorded(output)
            self.assertEqual('not_run', report['status'])
            self.assertEqual('check_only', report['reason'])
            self.assertEqual('not_checked', report['dependencyResolution'])
            self.assertFalse(report['executionVerified'])
            self.assertFalse(report['gradleAttempted'])
            self.assertEqual({}, report['executed'])
            self.assertIn('No tests executed', stdout.getvalue())

    def test_missing_dependency_returns_nonzero_and_records_no_execution(self):
        for check in (False, True):
            with self.subTest(check=check), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)
                with patch.object(HOST, 'preflight', side_effect=HOST.PrerequisiteError('Missing pinned dependency')), \
                        patch.object(HOST.subprocess, 'run') as launch, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    argv = ['--suite', 'main-chain', '--output', str(output)] + (['--check'] if check else [])
                    self.assertEqual(2, HOST.main(argv))
                launch.assert_not_called()
                report = self.recorded(output)
                self.assertEqual('not_run', report['status'])
                self.assertEqual('preflight_failed', report['reason'])
                self.assertEqual(2, report['exitCode'])
                self.assertFalse(report['executionVerified'])
                self.assertEqual({}, report['executed'])
                self.assertIn('Missing pinned dependency', report['error'])

    def test_gradle_failure_or_signal_cannot_be_reported_as_verified_tests(self):
        for process_code, cli_code in ((17, 17), (-15, 143)):
            with self.subTest(process_code=process_code), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)
                with patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                        patch.object(HOST.subprocess, 'run', return_value=SimpleNamespace(returncode=process_code)) as launch, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(cli_code, HOST.main(['--group', 'gateway-continuation', '--output', str(output)]))
                launch.assert_called_once()
                report = self.recorded(output)
                self.assertEqual('failed', report['status'])
                self.assertEqual('gradle_failed', report['reason'])
                self.assertEqual(process_code, report['processExitCode'])
                self.assertEqual(cli_code, report['exitCode'])
                self.assertTrue(report['gradleAttempted'])
                self.assertFalse(report['executionVerified'])
                self.assertIsNone(report['executed'])  # Gradle may have partially executed tests.

    def test_interruption_keeps_a_nonpassing_machine_readable_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            with patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                    patch.object(HOST.subprocess, 'run', side_effect=KeyboardInterrupt), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(130, HOST.main(['--group', 'gateway-continuation', '--output', str(output)]))
            report = self.recorded(output)
            self.assertEqual('failed', report['status'])
            self.assertEqual('interrupted', report['reason'])
            self.assertEqual(130, report['exitCode'])
            self.assertFalse(report['executionVerified'])


if __name__ == '__main__':
    unittest.main()
