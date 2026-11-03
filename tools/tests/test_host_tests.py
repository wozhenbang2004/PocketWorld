"""CLI/reporting contract tests; these fixtures do not stand in for product test execution."""
import contextlib
import importlib.util
import io
import json
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location('host_tests', Path(__file__).parents[1] / 'host-tests.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
REGISTRY = json.loads(HOST.REGISTRY.read_text())


class HostTestEntrypointTest(unittest.TestCase):
    def test_independent_host_groups_do_not_share_the_apk_queue(self):
        workflow = (HOST.ROOT / '.github/workflows/build-apk.yml').read_text()
        block = re.search(r'(?ms)^concurrency:\n(.*?)(?=^jobs:)', workflow).group(1)
        self.assertIn("github.event_name == 'workflow_dispatch'", block)
        self.assertIn("inputs.target == 'host-tests'", block)
        self.assertIn("(inputs.host_groups != '' && 'host-selected-batch' || format('host-{0}', inputs.host_group)) || 'apk'", block)
        self.assertIn('github.ref', block)
        cancellation = re.findall(r'^  cancel-in-progress: (.+)$', block, re.M)
        self.assertEqual(1, len(cancellation))
        self.assertIn(cancellation[0], (
            'false',
            "${{ startsWith(github.ref, 'refs/heads/uiux/') && "
            "(github.event_name != 'workflow_dispatch' || inputs.target != 'host-tests') }}",
        ))

    def test_selection_is_explicit_and_deduplicated(self):
        name = REGISTRY['device-receiver']['class']
        self.assertEqual(['device-receiver'], HOST.select(REGISTRY, ['device-receiver'], [name]))
        for groups, classes in [([], []), (['typo'], []), ([], ['*Test']), ([], ['missing.Test'])]:
            with self.subTest(groups=groups, classes=classes), self.assertRaises(HOST.PrerequisiteError):
                HOST.select(REGISTRY, groups, classes)

    def test_memory_batch_selects_both_exact_classes_without_a_suite_or_all_tests(self):
        entry = REGISTRY['memory-experience']
        self.assertEqual(['memory-experience'], HOST.select(REGISTRY, [], entry['additionalClasses']))
        registration = (HOST.ROOT / 'tools/host-tests.gradle').read_text()
        self.assertIn("(it.additionalClasses ?: [])", registration)
        for name in [entry['class']] + entry['additionalClasses']:
            self.assertTrue((HOST.ROOT / 'app/src/hostTest/java' / (name.replace('.', '/') + '.java')).is_file())

    def test_private_skill_callback_group_is_explicit_and_keeps_real_unix_assertions(self):
        entry = REGISTRY['managed-skill-private-artifacts']
        self.assertTrue(entry['native'])
        self.assertTrue(entry['robolectric'])
        self.assertEqual('dev.pocketengine.features.skills.ManagedScriptCallbackNativeJourneyTest', entry['class'])
        self.assertEqual([
            'dev.pocketengine.features.skills.ManagedScriptCallbackNativeGateTest',
            'dev.pocketengine.features.skills.ManagedScriptCallbackProtocolTest',
            'dev.pocketengine.features.skills.ManagedScriptCallbackClientPeerTest',
            'dev.pocketengine.features.skills.OwnerSkillManagedJourneyTest',
        ], entry['additionalClasses'])
        self.assertIn('dev.pocketengine.features.skills.ManagedScriptCallbackClientPeerTest', entry['additionalClasses'])
        self.assertIn('dev.pocketengine.features.skills.ManagedScriptCallbackTestSupport', entry['helpers'])
        helper = (HOST.ROOT / 'app/src/test/java/dev/pocketengine/features/skills/ManagedScriptCallbackTestSupport.java').read_text()
        self.assertIn('System.getProperty("pocket.gateway.source")', helper)
        self.assertIn('System.getProperty("pocketworldHostClasspath"', helper)
        self.assertIn('tools/host-fixtures/SkillCallbackUnixTransport.java', helper)
        fixture = HOST.ROOT / 'tools/host-fixtures/SkillCallbackUnixTransport.java'
        self.assertTrue(fixture.is_file())
        self.assertIn('StandardProtocolFamily.UNIX', fixture.read_text())
        self.assertIn('getField("SO_PEERCRED")', fixture.read_text())
        self.assertIn('ClassLoader.getPlatformClassLoader()', fixture.read_text())
        self.assertIn('channel.getOption(peerCredentials())', fixture.read_text())
        self.assertIn('ProcessHandle.current().pid()', fixture.read_text())
        self.assertTrue((HOST.ROOT / 'tools/host-fixtures/skill_callback_client_fixture.py').is_file())
        self.assertEqual(['managed-skill-private-artifacts'], HOST.select(REGISTRY, [], [entry['class']]))

    def test_runtime_recovery_admission_is_explicit_and_keeps_actual_native_boundary(self):
        entry = REGISTRY['runtime-recovery-admission']
        self.assertTrue(entry['native'])
        self.assertEqual(['runtime-recovery-admission'], HOST.select(REGISTRY, [], [entry['class']]))
        source = (HOST.ROOT / 'app/src/hostTest/java' / (entry['class'].replace('.', '/') + '.java')).read_text()
        self.assertEqual(5, len(re.findall(r'@Test\s+public\s+void\s+\w+\s*\(', source)))
        self.assertIn('new NativeProcessSupervisor().available()', source)
        self.assertIn('f.coldObserver.requireCheckoutWritable(NEW_CHECKOUT)', source)
        self.assertIn('f.coldJobs.existing(next.executionRef())', source)
        self.assertIn('own_fixture_download_not_opened', source)
        self.assertNotIn('@Ignore', source)
        self.assertIn('dev.pocketengine.features.local.runtime.AndroidRuntimePreparationTest', entry['helpers'])

    def test_thermal_log_policy_is_explicit_and_does_not_add_an_execution_or_npm_lane(self):
        entry = REGISTRY['runtime-thermal-log-policy']
        self.assertTrue(entry['native'])
        self.assertTrue(entry['robolectric'])
        names = [entry['class']] + entry['additionalClasses']
        self.assertEqual(4, len(names))
        self.assertEqual(['runtime-thermal-log-policy'], HOST.select(REGISTRY, [], names))
        methods = 0
        for name in names:
            matches = [HOST.ROOT / root / (name.replace('.', '/') + '.java')
                       for root in ['app/src/hostTest/java', 'app/src/test/java']]
            sources = [path for path in matches if path.is_file()]
            self.assertEqual(1, len(sources))
            methods += len(re.findall(r'@Test\s+public\s+void\s+\w+\s*\(', sources[0].read_text()))
        self.assertEqual(23, methods)
        helper = (HOST.ROOT / 'app/src/hostTest/java/dev/pocketengine/features/local/project/ProjectManagedRunnerJourneyTest.java').read_text()
        self.assertIn('createFixture(true)', helper)
        self.assertIn('createTerminalFixture()throws Exception {createFixture(false);}', helper)
        self.assertNotIn('Files.readString(', helper)
        terminal = (HOST.ROOT / 'app/src/main/java/dev/pocketengine/features/local/project/ProjectOwnerTerminal.java').read_text()
        self.assertIn('automaticProjectLogPolicyForHost', terminal)
        self.assertIn('cursor,pageBytes', terminal)
        ui = (HOST.ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/ProjectTerminalSection.kt').read_text()
        self.assertIn('key(taskId, execution, metadata)', ui)
        self.assertIn('NativeProjectExecutionObservation(task.id, execution)', ui)
        self.assertIn('app.features.openProjectExecutionForOwner(taskId, execution, observation)', ui)
        self.assertIn('logPolicy = page.optJSONObject("logObservationPolicy")', ui)
        self.assertNotIn('metadata = page', ui)

    def test_development_continuity_selects_only_its_original_native_journeys(self):
        entry = REGISTRY['runtime-development-continuity']
        self.assertTrue(entry['native'])
        self.assertTrue(entry['robolectric'])
        self.assertEqual(['dev.pocketengine.ui.RuntimeActivityCaptionTest',
                          'dev.pocketengine.features.local.project.ProjectCommandSpecTest',
                          'dev.pocketengine.ui.TaskExecutionPresentationTest',
                          'dev.pocketengine.store.TaskAuditStoreTest',
                          'dev.pocketengine.store.TaskJobProjectionTest',
                          'dev.pocketengine.ui.TaskJobMountedTest'], entry.get('additionalClasses', []))
        self.assertEqual(['dev.pocketengine.features.local.project.ProjectManagedRunnerJourneyTest',
                          'dev.pocketengine.testutil.HttpServer'], entry['helpers'])
        self.assertEqual(['runtime-development-continuity'], HOST.select(REGISTRY, [], [entry['class']]))
        source = (HOST.ROOT / 'app/src/hostTest/java' / (entry['class'].replace('.', '/') + '.java')).read_text()
        self.assertEqual(5, len(re.findall(r'@Test\s+public\s+void\s+\w+\s*\(', source)))
        self.assertNotIn('@Ignore', source)

    def test_owner_diagnostic_metadata_is_explicit_and_consumes_the_shared_phone_whitelist(self):
        entry = REGISTRY['runtime-diagnostic-metadata']
        self.assertTrue(entry['robolectric'])
        self.assertFalse(entry.get('native', False))
        names = [entry['class']] + entry['additionalClasses']
        self.assertEqual(3, len(names))
        self.assertEqual(['runtime-diagnostic-metadata'], HOST.select(REGISTRY, [], names))
        methods = 0
        for name in names:
            source = (HOST.ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')).read_text()
            methods += len(re.findall(r'@Test(?:\([^)]*\))?\s+public\s+void\s+\w+\s*\(', source))
            self.assertNotIn('@Ignore', source)
        self.assertEqual(15, methods)
        source = (HOST.ROOT / 'app/src/main/java/dev/pocketengine/features/local/runtime/NativeDiagnosticSources.java').read_text()
        self.assertIn('AndroidPhoneDiagnostics.preview(context,null)', source)
        self.assertIn('report.readiness()', source)
        self.assertNotIn('AndroidPhoneReadiness', source)
        ui = (HOST.ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/ProjectStorageSection.kt').read_text()
        self.assertIn('ActivityResultContracts.CreateDocument("application/json")', ui)
        self.assertIn('flow.diagnosticExportResult(original, uri)', ui)
        self.assertIn('state.diagnosticReviewed && !state.busy', ui)
        coordinator = (HOST.ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/ProjectStorageCoordinator.kt').read_text()
        self.assertIn('ticket !== diagnosticTicket', coordinator)
        self.assertIn('diagnosticWindow.write(ticket', coordinator)
        self.assertIn('openOutputStream(uri, "wt")', coordinator)
        self.assertIn('diagnosticWindow.foreground(visible)', coordinator)
        self.assertIn('state.diagnosticPickerPending || state.diagnosticExportEntered) return null', coordinator)
        self.assertIn('flow.diagnosticPickerUnavailable(ticket)', ui)

    def test_legacy_receipt_recovery_keeps_original_bytes_and_does_not_add_a_runtime(self):
        entry = REGISTRY['legacy-command-receipt-recovery']
        self.assertTrue(entry['robolectric'])
        self.assertTrue(entry['native'])
        names = [entry['class']] + entry['additionalClasses']
        self.assertEqual(8, len(names))
        # The original LocalWorkbench guard also belongs to file operations.
        self.assertEqual(['legacy-command-receipt-recovery', 'project-file-operations'], HOST.select(REGISTRY, [], names))
        methods = 0
        for name in names:
            source = (HOST.ROOT / 'app/src/test/java' / (name.replace('.', '/') + '.java')).read_text()
            methods += len(re.findall(r'@Test(?:\([^)]*\))?\s+public\s+void\s+\w+\s*\(', source))
            self.assertNotIn('@Ignore', source)
        self.assertEqual(29, methods)
        source = (HOST.ROOT / 'app/src/main/java/dev/pocketengine/features/local/NativeCommandExecutor.java').read_text()
        loader = source[source.index('File[] saved='):source.index('private static JSONObject readOriginal')]
        self.assertNotIn('JsonFiles.write', loader)
        self.assertNotIn('Files.delete', loader)
        self.assertIn('file.getName().equals(id+".json")', loader)
        self.assertIn('coldProcessUnconfirmed', loader)
        self.assertIn('public synchronized boolean busy(String id){return originalInventoryUnconfirmed||busy.contains(id);}', source)
        self.assertIn('NativeProcessSupervisor.Exit exit=supervisor.execute(', source)
        self.assertIn('exit.groupAbsent&&exit.outputComplete', source)
        self.assertNotIn('ProcessBuilder', source)
        self.assertNotIn('Process process=', source)
        self.assertIn('&&supervised&&!originalInventoryUnconfirmed', source)
        self.assertIn('dev.pocketengine.features.local.NativeCommandUnavailableProbe', entry['helpers'])
        self.assertIn('compactOriginal(job)', source)
        self.assertIn('new String(tail.toCharArray())', source)
        self.assertNotIn('public synchronized JSONObject get(', source)
        self.assertIn('Do not hold the supervisor/cancel monitor during filesystem IO.', source)
        ui = (HOST.ROOT / 'app/src/main/kotlin/dev/pocketengine/ui/LocalWorkbench.kt').read_text()
        self.assertIn('local.optJSONObject("legacyReceiptRecovery")', ui)
        self.assertIn('!LocalWorkbenchGuard.pollable(', ui)

    def test_registered_real_sources_and_helpers_exist(self):
        for entry in REGISTRY.values():
            for name in list(dict.fromkeys([entry['class']] + entry.get('additionalClasses', []) + entry['helpers'])):
                relative = name.replace('.', '/')
                found = [HOST.ROOT / root / (relative + extension)
                         for root, extension in (('app/src/hostTest/java', '.java'), ('app/src/test/java', '.java'), ('app/src/test/kotlin', '.kt'))
                         if (HOST.ROOT / root / (relative + extension)).is_file()]
                self.assertEqual(1, len(found), 'expected one exact registered source for ' + name)
                if name in [entry['class']] + entry.get('additionalClasses', []):
                    self.assertIn('@Test', found[0].read_text())

    def test_phone_sensitive_owner_registers_exact_sources_and_preserves_fifty_original_methods(self):
        # This is an entry/source inventory contract, not execution of these product bodies.
        manifest = json.loads((HOST.ROOT / 'tools/tests/fixtures/phone-sensitive-owner-tests.json').read_text())
        self.assertEqual(1, manifest['schemaVersion'])
        entry = REGISTRY[manifest['group']]
        classes = [item['class'] for item in manifest['classes']]
        helpers = [item['class'] for item in manifest['helpers']]
        self.assertEqual(classes, [entry['class']] + entry['additionalClasses'])
        self.assertEqual(helpers, entry['helpers'])
        self.assertTrue(entry['robolectric'])
        self.assertFalse(entry.get('native', False))
        self.assertFalse(entry.get('python', False))
        expected = {item['source'] for item in manifest['classes'] + manifest['helpers']}
        includes = {name.replace('.', '/') + '.java' for name in classes + helpers}
        actual = {path.relative_to(HOST.ROOT).as_posix()
                  for root in ('app/src/hostTest/java', 'app/src/test/java')
                  for path in (HOST.ROOT / root).rglob('*.java')
                  if path.relative_to(HOST.ROOT / root).as_posix() in includes}
        self.assertEqual(expected, actual)
        self.assertEqual(len(manifest['classes']) + len(manifest['helpers']), len(actual))
        for item in manifest['classes']:
            source = (HOST.ROOT / item['source']).read_text()
            methods = re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source)
            self.assertEqual(len(methods), len(set(methods)), item['class'])
            self.assertEqual(sorted(item['methods']), sorted(methods), item['class'])
            self.assertNotIn('@Ignore', source)
        self.assertEqual([12, 30, 8], [len(item['methods']) for item in manifest['classes'][:3]])
        self.assertEqual([6, 7, 13, 1, 7, 5, 9, 4], [len(item['methods']) for item in manifest['classes'][3:]])

    def test_phone_sensitive_owner_explicit_group_does_not_expand_the_shared_native_class(self):
        group = 'phone-sensitive-owner'
        primary = REGISTRY[group]['class']
        self.assertEqual([group], HOST.select(REGISTRY, [group], []))
        self.assertEqual([group], HOST.select(REGISTRY, [], [primary]))
        self.assertEqual([group], HOST.select(REGISTRY, [group], [primary]))
        # This class remains compile-only in the explicit sensitive group. It is now
        # runnable in the separately selected browser-plan regression group.
        helper = 'dev.pocketengine.phone.observation.PhoneTreeSequenceTest'
        self.assertIn(helper, REGISTRY[group]['helpers'])
        self.assertNotIn(helper, [REGISTRY[group]['class']] + REGISTRY[group]['additionalClasses'])
        self.assertEqual(['browser-native-sequence'], HOST.select(REGISTRY, [], [helper]))
        self.assertEqual([group], HOST.select(REGISTRY, [group], []))

    def test_phone_sensitive_owner_cli_keeps_all_exact_xml_cases_and_original_task(self):
        # Explicit fake process/XML boundary only; no Gradle, JVM or App body is run here.
        manifest = json.loads((HOST.ROOT / 'tools/tests/fixtures/phone-sensitive-owner-tests.json').read_text())
        classes = [item['class'] for item in manifest['classes']]
        counts = {item['class']: len(item['methods']) for item in manifest['classes']}
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)

            def gradle_result(command, **kwargs):
                self.assertIn('-PpocketworldHostTests=phone-sensitive-owner', command)
                self.assertIn('--no-parallel', command)
                self.assertIn('--max-workers=1', command)
                self.assertEqual(['-x', ':app:buildAndroidRuntime', '-x', ':app:buildDisplayHelper', ':app:testDebugUnitTest'], command[-5:])
                report = Path(next(arg.split('=', 1)[1] for arg in command if arg.startswith('-PpocketworldHostReport=')))
                xml = report / 'junit'
                xml.mkdir()
                (report / 'gradle-selection.json').write_text(json.dumps({'junitXmlDirectory': str(xml), 'junitXmlRequired': True}))
                for item in manifest['classes']:
                    cases = ''.join('<testcase classname="' + item['class'] + '" name="' + name + '"/>' for name in item['methods'])
                    (xml / ('TEST-' + item['class'] + '.xml')).write_text('<testsuite>' + cases + '</testsuite>')
                return SimpleNamespace(returncode=0)

            with patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                    patch.object(HOST.subprocess, 'run', side_effect=gradle_result) as launch, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(0, HOST.main(['--group', manifest['group'], '--output', str(output)]))
                self.assertEqual(1, launch.call_count)
            runs = list(output.glob('run-*'))
            self.assertEqual(1, len(runs))
            invocation = json.loads((runs[0] / 'invocation.json').read_text())
            self.assertEqual([manifest['group']], invocation['groups'])
            self.assertEqual(classes, invocation['classes'])
            self.assertEqual(counts, json.loads((runs[0] / 'result.json').read_text())['executed'])
            self.assertEqual(102, sum(counts.values()))

    def test_advanced_phone_groups_register_exact_bodies_and_compile_only_helpers(self):
        # Registry/source closure only; SDK35 production/test compilation remains the host Gradle gate.
        manifest = json.loads((HOST.ROOT / 'tools/tests/fixtures/phone-advanced-execution-tests.json').read_text())
        self.assertEqual(1, manifest['schemaVersion'])
        expected_counts = {'phone-setup-recovery': 28, 'phone-input-extensions': 30,
                           'phone-semantic-profile': 45, 'phone-access-history': 38}
        self.assertEqual(set(expected_counts), set(manifest['groups']))
        for group, inventory in manifest['groups'].items():
            entry = REGISTRY[group]
            with self.subTest(group=group):
                self.assertTrue(entry['robolectric'])
                self.assertFalse(entry.get('native', False))
                self.assertEqual([item['class'] for item in inventory['classes']], [entry['class']] + entry['additionalClasses'])
                self.assertEqual([item['class'] for item in inventory['helpers']], entry['helpers'])
                self.assertEqual([group], HOST.select(REGISTRY, [group], []))
                self.assertEqual(expected_counts[group], sum(len(item['methods']) for item in inventory['classes']))
                for item in inventory['classes'] + inventory['helpers']:
                    source = HOST.ROOT / item['source']
                    self.assertTrue(source.is_file(), item['class'])
                    self.assertEqual(item['class'].replace('.', '/') + '.java', '/'.join(source.parts[-len(item['class'].split('.')):]))
                    if 'methods' in item:
                        text = source.read_text()
                        methods = re.findall(r'@Test(?:\([^)]*\))?\s+public\s+void\s+(\w+)\s*\(', text)
                        self.assertEqual(sorted(item['methods']), sorted(methods), item['class'])
                        self.assertEqual(len(methods), len(set(methods)))
                        self.assertNotIn('@Ignore', text)
        self.assertIn('dev.pocketengine.phone.setup.AndroidAppCopiesNativeTest', REGISTRY['phone-setup-recovery']['helpers'])
        self.assertNotIn('dev.pocketengine.phone.setup.AndroidAppCopiesNativeTest', REGISTRY['phone-setup-recovery']['additionalClasses'])
        self.assertIn('dev.pocketengine.features.semantic.SemanticBackgroundDisplayFixture', REGISTRY['phone-sensitive-owner']['helpers'])
        self.assertIn('dev.pocketengine.displayhelper.DisplayTypedIntentNativeTest', REGISTRY['phone-sensitive-owner']['helpers'])

    def test_private_pull_request_host_gate_preserves_memory_jobs_without_signing(self):
        workflow = (HOST.ROOT / '.github/workflows/build-apk.yml').read_text()
        self.assertNotIn('pull_request_target:', workflow)
        self.assertIn('permissions:\n  contents: read', workflow)
        self.assertIn('  pull_request:\n    branches: [main]', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        apk, host = workflow.split('  apk:\n', 1)[1].split('  host-tests:\n', 1)
        self.assertNotIn('secrets.', host)
        self.assertNotIn('apksigner', host)
        self.assertNotIn(':app:assembleDebug', host)
        self.assertIn('Apply stable project signing key', apk)
        host_condition = re.search(r'(?m)^    if: \$\{\{ (.*) \}\}$', host).group(1)
        apk_condition = re.search(r'(?m)^    if: \$\{\{ (.*) \}\}$', apk).group(1)

        def selected(expression, event, private=True, branch='pro/workpack-02-phone-control', same_repo=True, target=''):
            repository = 'owner/private-pocketworld'
            github = SimpleNamespace(event_name=event, repository=repository, head_ref=branch,
                event=SimpleNamespace(repository=SimpleNamespace(private=private),
                    pull_request=SimpleNamespace(head=SimpleNamespace(repo=SimpleNamespace(full_name=repository if same_repo else 'fork/other')))))
            return bool(eval(expression.replace('&&', ' and ').replace('||', ' or '),
                             {'__builtins__': {}}, {'github': github, 'inputs': SimpleNamespace(target=target)}))

        cases = [({'event': 'pull_request'}, (False, True)),
                 # Preserve latest main's same-repository branch guard for host execution.
                 ({'event': 'pull_request', 'same_repo': False}, (False, False)),
                 ({'event': 'pull_request', 'branch': 'uiux/workroom-20261102'}, (False, False)),
                 ({'event': 'pull_request', 'branch': 'pro/dsh-runtime-continuity'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'pro/workpack-06-model-quality'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'pro/workpack-05-memory-quality'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'pro/workpack-03-phone-api-cli'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'pro/mika-full-takeover-20261103'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'integrate/pr8-pr15-20261103'}, (False, True)),
                 ({'event': 'pull_request', 'branch': 'integrate/pr8-pr15-20261103', 'same_repo': False}, (False, False)),
                 ({'event': 'pull_request', 'private': False}, (False, False)),
                 ({'event': 'push'}, (True, False)),
                 ({'event': 'workflow_dispatch', 'target': 'apk'}, (True, False)),
                 ({'event': 'workflow_dispatch', 'target': 'host-tests'}, (False, True))]
        for arguments, expected in cases:
            with self.subTest(**arguments):
                self.assertEqual(expected, (selected(apk_condition, **arguments), selected(host_condition, **arguments)))

    def test_phone_pr_uses_one_exact_bounded_batch_and_records_base_head_and_tested_commit(self):
        workflow = (HOST.ROOT / '.github/workflows/build-apk.yml').read_text()
        host = workflow.split('  host-tests:\n', 1)[1]
        selection = re.search(r"HOST_TEST_GROUPS: \$\{\{ (.*) \}\}", host)
        self.assertIsNotNone(selection)
        guard = re.search(r'(?m)^    if: \$\{\{ (.*) \}\}$', host).group(1)
        def batch(event='pull_request', branch='pro/workpack-02-phone-control', same_repo=True, manual=''):
            repository = 'owner/private-pocketworld'
            github = SimpleNamespace(event_name=event, repository=repository, head_ref=branch,
                event=SimpleNamespace(repository=SimpleNamespace(private=True),pull_request=SimpleNamespace(head=SimpleNamespace(
                    repo=SimpleNamespace(full_name=repository if same_repo else 'fork/other')))))
            context = {'github': github, 'inputs': SimpleNamespace(host_groups=manual, target='host-tests')}
            if not eval(guard.replace('&&', ' and ').replace('||', ' or '), {'__builtins__': {}}, context):
                return None
            return eval(selection.group(1).replace('&&', ' and ').replace('||', ' or '), {'__builtins__': {}}, context)
        groups = batch().split(',')
        self.assertEqual(['phone-setup-recovery', 'phone-input-extensions', 'phone-semantic-profile', 'phone-access-history',
                          'phone-observation-boundaries', 'phone-sensitive-owner', 'local-phone-leases', 'phone-audio-owner',
                          'phone-task-platform', 'aion-conversations', 'model-quality-routing', 'model-quality-sources',
                          'gateway-continuation', 'runner-budget-stream', 'request-clock', 'runtime-output-display'], groups)
        self.assertEqual(groups, HOST.select(REGISTRY, groups, []))
        self.assertEqual(groups, batch(branch='integrate/pr8-pr15-20261103').split(','))
        self.assertIsNone(batch(branch='integrate/pr8-pr15-20261103', same_repo=False))
        main_groups = ['phone-task-platform', 'memory-recall-quality', 'memory-evidence-plan', 'memory-method-quality']
        self.assertEqual(main_groups, HOST.select(REGISTRY, main_groups, []))
        self.assertEqual(main_groups, batch(branch='pro/workpack-03-phone-api-cli').split(','))
        self.assertEqual(main_groups[1:], batch(branch='pro/workpack-05-memory-quality').split(','))
        runtime_groups = ['native-normal-cleanup', 'runtime-development-continuity', 'runtime-preparation-cancel',
                          'runtime-recovery-admission', 'native-duplex-pipes', 'runtime-activity', 'runtime-thermal-log-policy',
                          'runtime-diagnostic-metadata', 'request-clock', 'runtime-output-display', 'runtime-health',
                          'script-artifact-publication', 'managed-skill-private-artifacts', 'runner-repeat-notice',
                          'runtime-log-tail-recovery', 'legacy-command-receipt-recovery']
        self.assertEqual(runtime_groups, batch(branch='pro/dsh-runtime-continuity').split(','))
        self.assertEqual(runtime_groups, HOST.select(REGISTRY, runtime_groups, []))
        self.assertIsNone(batch(branch='uiux/workroom-20261102'))
        model = ['model-quality-routing', 'model-quality-observability', 'model-quality-sources',
                 'model-evaluation', 'gateway-continuation', 'runner-budget-stream']
        self.assertEqual(model, batch(branch='pro/workpack-06-model-quality').split(','))
        self.assertEqual(model, HOST.select(REGISTRY, model, []))
        takeover = groups[:10] + main_groups[1:] + ['model-quality-routing', 'model-quality-sources', 'gateway-continuation']
        self.assertEqual(takeover, batch(branch='pro/mika-full-takeover-20261103').split(','))
        self.assertEqual(takeover, HOST.select(REGISTRY, takeover, []))
        self.assertEqual(16, len(takeover))
        self.assertIsNone(batch(branch='pro/mika-full-takeover-20261103', same_repo=False))
        self.assertIsNone(batch(same_repo=False))
        self.assertEqual('request-clock', batch(event='workflow_dispatch', manual='request-clock'))
        # MCP branches retain their own bounded compatibility matrices. The
        # phone branch must still resolve to one selected job, not fan out.
        matrix_selection = re.search(r"batch: \$\{\{ fromJSON\((.+)\) \}\}", host)
        self.assertIsNotNone(matrix_selection)
        for event in ('pull_request', 'workflow_dispatch'):
            context = {'github': SimpleNamespace(event_name=event, head_ref='pro/workpack-02-phone-control')}
            value = eval(matrix_selection.group(1).replace('&&', ' and ').replace('||', ' or '), {'__builtins__': {}}, context)
            self.assertEqual(['selected'], json.loads(value))
        for field in ('testedCommit', 'prBaseCommit', 'prHeadCommit', 'deviceTestsPerformed', 'apkBuiltOrSigned'):
            self.assertIn("'" + field + "':", host)
        self.assertIn("['git', 'rev-parse', 'HEAD']", host)
        self.assertIn('persist-credentials: false', host)
        self.assertIn('phone-control-pr', host)

    def test_gateway_uses_exact_android_compatible_http_helper_and_byte_io(self):
        entry = REGISTRY['gateway-continuation']
        self.assertEqual(['dev.pocketengine.testutil.HttpServer'], entry['helpers'])
        source = (HOST.ROOT / 'app/src/hostTest/java/dev/pocketengine/GatewayContinuationAppJourneyTest.java').read_text()
        self.assertIn('import dev.pocketengine.testutil.HttpServer;', source)
        self.assertNotIn('com.sun.net.httpserver', source)
        self.assertNotIn('Files.writeString', source)
        self.assertNotIn('Files.readString', source)
        self.assertIn('"GatewayFixtureVault",1.0,', source)
        self.assertIn('exchange.sendResponseHeaders(200,body.length)', source)
        self.assertIn('exchange.getResponseBody().flush()', source)

    def test_gateway_java_selection_is_only_the_original_class_and_shared_http_helper(self):
        entry = REGISTRY['gateway-continuation']
        includes = {name.replace('.', '/') + '.java' for name in [entry['class']] + entry['helpers']}
        selected = []
        for root in ('app/src/hostTest/java', 'app/src/test/java'):
            base = HOST.ROOT / root
            selected.extend(path.relative_to(HOST.ROOT).as_posix() for path in base.rglob('*.java')
                            if path.relative_to(base).as_posix() in includes)
        self.assertEqual([
            'app/src/hostTest/java/dev/pocketengine/GatewayContinuationAppJourneyTest.java',
            'app/src/test/java/dev/pocketengine/testutil/HttpServer.java'
        ], sorted(selected))

    def test_bad_input_does_not_launch_gradle(self):
        with patch.object(HOST.subprocess, 'run') as launch, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, HOST.main(['--group', 'not-a-group']))
            launch.assert_not_called()

    def test_no_xml_or_zero_tests_is_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'No JUnit XML'):
                HOST.junit_results(root, ['example.Test'])
            (root / 'TEST-example.Test.xml').write_text('<testsuite name="example.Test" tests="0"/>')
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'did not execute'):
                HOST.junit_results(root, ['example.Test'])

    def test_failed_skipped_missing_or_unselected_test_is_not_success(self):
        for child in ['<failure message="real failure"/>', '<error/>', '<skipped/>']:
            with self.subTest(child=child), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                (root / 'TEST-example.Test.xml').write_text('<testsuite><testcase classname="example.Test" name="assertions">' + child + '</testcase></testsuite>')
                with self.assertRaisesRegex(HOST.PrerequisiteError, 'failed or skipped'):
                    HOST.junit_results(root, ['example.Test'])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'TEST-unselected.xml').write_text('<testsuite><testcase classname="other.Test" name="assertions"/></testsuite>')
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'Unexpected test'):
                HOST.junit_results(root, ['example.Test'])

    def test_real_junit_class_counts_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'TEST-example.Test.xml').write_text('<testsuite><testcase classname="example.Test" name="one"/><testcase classname="example.Test" name="two"/></testsuite>')
            self.assertEqual({'example.Test': 2}, HOST.junit_results(root, ['example.Test']))

    def test_junit_destination_is_bound_after_agp_and_actual_location_is_recorded(self):
        registration = (HOST.ROOT / 'tools/host-tests.gradle').read_text()
        callback = registration.split('android.testOptions.unitTests.all(', 1)
        self.assertEqual(2, len(callback), 'AGP overwrites early configureEach report destinations')
        self.assertIn("xml.outputLocation.set(new File(hostReport, 'junit'))", callback[1])
        self.assertIn('xml.outputLocation.disallowChanges()', callback[1])
        self.assertIn('junitXmlDirectory: reports.junitXml.outputLocation.get().asFile.absolutePath', registration)
        self.assertIn('junitXmlRequired: reports.junitXml.required.get()', registration)

    def test_junit_location_handshake_rejects_missing_disabled_or_other_run_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / 'current-run'
            report.mkdir()
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'report location'):
                HOST.junit_directory(report)
            other = Path(tmp) / 'old-run/junit'
            other.mkdir(parents=True)
            (other / 'TEST-example.Test.xml').write_text('<testsuite><testcase classname="example.Test" name="old"/></testsuite>')
            for location, required in [(str(other), True), ('junit', True), (str(report / 'junit'), False)]:
                (report / 'gradle-selection.json').write_text(json.dumps({'junitXmlDirectory': location, 'junitXmlRequired': required}))
                with self.subTest(location=location, required=required), self.assertRaisesRegex(HOST.PrerequisiteError, 'JUnit XML'):
                    HOST.junit_directory(report)

    def test_current_run_location_still_requires_real_nonempty_successful_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp)
            xml = report / 'junit'
            xml.mkdir()
            (report / 'gradle-selection.json').write_text(json.dumps({'junitXmlDirectory': str(xml), 'junitXmlRequired': True}))
            self.assertEqual(xml, HOST.junit_directory(report))
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'No JUnit XML'):
                HOST.junit_results(HOST.junit_directory(report), ['example.Test'])
            result = xml / 'TEST-example.Test.xml'
            result.write_text('<testsuite><testcase classname="example.Test" name="one"/><testcase classname="example.Test" name="two"/></testsuite>')
            self.assertEqual({'example.Test': 2}, HOST.junit_results(HOST.junit_directory(report), ['example.Test']))
            result.write_text('<testsuite><testcase classname="example.Test" name="failed"><failure/></testcase></testsuite>')
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'failed or skipped'):
                HOST.junit_results(HOST.junit_directory(report), ['example.Test'])

    def test_cli_gradle_success_without_xml_is_not_a_pass_and_real_xml_is_required(self):
        # Process-boundary contract fixture only; this never executes App test bodies.
        name = REGISTRY['gateway-continuation']['class']
        for write_xml in (False, True):
            with self.subTest(write_xml=write_xml), tempfile.TemporaryDirectory() as tmp:
                output = Path(tmp)

                def gradle_result(command, **kwargs):
                    report = Path(next(arg.split('=', 1)[1] for arg in command if arg.startswith('-PpocketworldHostReport=')))
                    xml = report / 'junit'
                    xml.mkdir()
                    (report / 'gradle-selection.json').write_text(json.dumps({'junitXmlDirectory': str(xml), 'junitXmlRequired': True}))
                    if write_xml:
                        (xml / ('TEST-' + name + '.xml')).write_text('<testsuite><testcase classname="' + name + '" name="xml-contract"/></testsuite>')
                    return SimpleNamespace(returncode=0)

                with patch.object(HOST, 'preflight', return_value=(output / 'jdk', output / 'sdk', 'gradle', {})), \
                        patch.object(HOST.subprocess, 'run', side_effect=gradle_result), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(0 if write_xml else 2, HOST.main(['--group', 'gateway-continuation', '--output', str(output)]))
                results = list(output.glob('run-*/result.json'))
                self.assertEqual(1 if write_xml else 0, len(results))
                if write_xml:
                    self.assertEqual({name: 1}, json.loads(results[0].read_text())['executed'])

    def test_missing_offline_wrapper_is_reported_before_gradle_can_download(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'jdk/bin').mkdir(parents=True)
            (root / 'sdk/platforms/android-35').mkdir(parents=True)
            (root / 'sdk/platforms/android-35/android.jar').touch()
            args = type('Args', (), dict(java_home=str(root / 'jdk'), android_home=str(root / 'sdk'),
                                        gradle=str(HOST.ROOT / 'gradlew'), online=False))()
            with patch.object(HOST, 'executable', side_effect=lambda value, label: value), \
                    patch.object(HOST.subprocess, 'check_output', return_value='openjdk version "17.0.1"'), \
                    patch.dict(HOST.os.environ, {'GRADLE_USER_HOME': str(root / 'empty-cache')}):
                with self.assertRaisesRegex(HOST.PrerequisiteError, 'Missing offline Gradle 8.9 Wrapper distribution'):
                    HOST.preflight(args, REGISTRY, ['managed-runtime'])

    def test_opt_in_registration_excludes_unrelated_java_and_kotlin_test_roots(self):
        registration = (HOST.ROOT / 'tools/host-tests.gradle').read_text()
        self.assertIn("def sourceRoots = ['src/hostTest/java', 'src/test/java']", registration)
        self.assertIn("android.sourceSets.test.java.setSrcDirs(sourceRoots)", registration)
        self.assertIn('android.sourceSets.test.java.setIncludes(sources as Set)', registration)
        self.assertIn("it.name in ['test', 'testDebug', 'testRelease']", registration)
        self.assertIn('kotlin.setSrcDirs([])', registration)
        self.assertIn("def kotlinSourceRoots = ['src/test/kotlin']", registration)
        self.assertIn("if (name == 'test' && !kotlinSources.isEmpty())", registration)
        self.assertIn('kotlin.setIncludes(kotlinSources as Set)', registration)
        self.assertIn('if (javaCount + kotlinCount != 1)', registration)

    def test_aion_research_and_pi_register_exact_kotlin_bodies_and_native_helpers(self):
        research = REGISTRY['aion-team-research-read']
        self.assertEqual(6, 1 + len(research['additionalClasses']))
        self.assertTrue(research['robolectric'])
        self.assertFalse(research.get('native', False))
        self.assertEqual(['dev.pocketengine.testutil.HttpServer'], research['helpers'])
        expected = {
            'dev.pocketengine.features.teams.TeamResearchScopedReadNativeJourneyTest': 4,
            'dev.pocketengine.features.teams.TeamResearchReadAccessTest': 2,
            'dev.pocketengine.features.teams.TeamResearchReadScopeTest': 3,
            'dev.pocketengine.features.teams.TeamResearchSelectionPageTest': 2,
            'dev.pocketengine.ui.ResearchReadAccessDraftTest': 3,
            'dev.pocketengine.ui.ResearchReadAccessMountedNativeTest': 5,
            'dev.pocketengine.features.remote.RemotePiDraftOwnerJourneyTest': 4,
            'dev.pocketengine.features.remote.RemoteInputDeliveryTest': 8,
            'dev.pocketengine.features.remote.RemoteTaskDraftsTest': 3,
            'dev.pocketengine.ui.RemoteDraftDeliveryMountedTest': 8,
            'dev.pocketengine.features.automation.AutomationIntervalTest': 4,
        }
        for name, count in expected.items():
            self.assertEqual(1, len(HOST.select(REGISTRY, [], [name])), name)
            relative = name.replace('.', '/')
            source = next((HOST.ROOT / root / (relative + extension)).read_text()
                          for root, extension in (('app/src/hostTest/java', '.java'), ('app/src/test/java', '.java'), ('app/src/test/kotlin', '.kt'))
                          if (HOST.ROOT / root / (relative + extension)).is_file())
            self.assertEqual(count, len(re.findall(r'@Test\b', source)), name)
            self.assertNotIn('@Ignore', source, name)
        self.assertTrue(REGISTRY['alma-pi-no-tools']['python'])
        self.assertTrue(REGISTRY['alma-pi-no-tools']['robolectric'])

    def test_android_resource_paths_use_module_cwd_and_gateway_keeps_repository_root(self):
        registration = (HOST.ROOT / 'tools/host-tests.gradle').read_text()
        selected = re.findall(r'^\s*workingDir (project|rootProject)\.projectDir\s*$', registration, re.M)
        self.assertEqual(['project'], selected, 'AGP test_config paths are relative to the app module')
        self.assertIn("systemProperty 'pocket.gateway.source', rootProject.projectDir.absolutePath", registration)
        # Model AGP's module-relative test_config paths; these are path fixtures,
        # not APK contents or a substitute for running the real Robolectric tests.
        with tempfile.TemporaryDirectory(prefix='host checkout ') as tmp:
            repository = Path(tmp)
            module = repository / 'app'
            for relative in (
                'build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml',
                'build/intermediates/apk_for_local_test/debugUnitTest/packageDebugUnitTestForUnitTest/apk-for-local-test.ap_',
            ):
                artifact = module / relative
                artifact.parent.mkdir(parents=True, exist_ok=True)
                artifact.touch()
                cwd = module if selected == ['project'] else repository
                self.assertEqual(artifact, cwd / relative)
                self.assertTrue((cwd / relative).is_file())
                self.assertFalse((repository / relative).exists())
            companion = repository / 'companion/gateway_fixture_server.py'
            companion.parent.mkdir()
            companion.touch()
            self.assertTrue((repository / 'companion/gateway_fixture_server.py').is_file())
            self.assertFalse((module / 'companion/gateway_fixture_server.py').exists())

    def test_registered_fixtures_do_not_require_repository_as_jvm_working_directory(self):
        for entry in REGISTRY.values():
            for name in [entry['class']] + entry['helpers']:
                relative = name.replace('.', '/') + '.java'
                source = next((HOST.ROOT / root / relative).read_text()
                              for root in ('app/src/hostTest/java', 'app/src/test/java')
                              if (HOST.ROOT / root / relative).is_file())
                self.assertNotIn('System.getProperty("user.dir")', source, name)
                for literal in re.findall(r'(?:new File|Path\.of|Paths\.get)\(\s*"([^"\n]*)"', source):
                    self.assertTrue(Path(literal).is_absolute(), name + ': implicit cwd path ' + literal)
        gateway = (HOST.ROOT / 'app/src/hostTest/java/dev/pocketengine/GatewayContinuationAppJourneyTest.java').read_text()
        self.assertIn('System.getProperty("pocket.gateway.source")', gateway)
        self.assertIn('source+"/companion/gateway_fixture_server.py"', gateway)

    def test_manual_workflow_group_choices_match_registry(self):
        workflow = (HOST.ROOT / '.github/workflows/build-apk.yml').read_text()
        options = re.search(r'(?s)      host_group:.*?        options: \[(.*?)\]', workflow)
        self.assertIsNotNone(options)
        self.assertEqual(set(REGISTRY), {name.strip() for name in options[1].split(',')})
        host_job = workflow.split('  host-tests:\n', 1)[1]
        self.assertIn("github.event_name == 'workflow_dispatch'", host_job)
        self.assertIn("inputs.target == 'host-tests'", host_job)
        # Manual selection stays one job; the two explicit MCP PRs have bounded batches.
        self.assertEqual(1, host_job.count('      matrix:'))
        self.assertIn("batch: ${{ fromJSON(github.event_name == 'pull_request' && (github.head_ref == 'pro/operit-full-takeover-20261103' && '[\"ecosystem\",\"project-files\",\"runtime-compatibility\"]' || github.head_ref == 'pro/workpack-04-mcp-skills' && '[\"ecosystem\",\"model-compatibility\",\"runtime-compatibility\"]') || '[\"selected\"]') }}", host_job)
        self.assertIn('        default: apk', workflow)
        title = next(line for line in workflow.splitlines() if line.startswith('run-name:'))
        self.assertIn("inputs.target == 'host-tests'", title)
        self.assertIn("format('Host tests · {0}', inputs.host_groups != '' && 'selected batch' || inputs.host_group)", title)
        self.assertIn("|| 'Debug APK'", title)
        self.assertIn('github.ref_name', title)
        self.assertIn('github.run_number', title)

    def test_list_cannot_silently_discard_a_selector(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            HOST.main(['--list', '--group', 'typo'])
        self.assertEqual(2, raised.exception.code)

    def test_list_does_not_claim_execution_or_need_local_dependencies(self):
        output = io.StringIO()
        with patch.object(HOST, 'preflight') as check, contextlib.redirect_stdout(output):
            self.assertEqual(0, HOST.main(['--list']))
            check.assert_not_called()
        self.assertIn('managed-runtime', output.getvalue())
        self.assertNotIn('passed', output.getvalue())


if __name__ == '__main__':
    unittest.main()
