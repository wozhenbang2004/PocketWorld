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
        self.assertIn("format('host-{0}', inputs.host_group) || 'apk'", block)
        self.assertIn('github.ref', block)
        self.assertIn('cancel-in-progress: false', block)

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

    def test_registered_real_sources_and_helpers_exist(self):
        for entry in REGISTRY.values():
            for name in [entry['class']] + entry['helpers']:
                relative = name.replace('.', '/') + '.java'
                found = [HOST.ROOT / root / relative for root in ('app/src/hostTest/java', 'app/src/test/java')
                         if (HOST.ROOT / root / relative).is_file()]
                self.assertEqual(1, len(found), 'expected one exact registered source for ' + name)
                if name == entry['class']:
                    self.assertIn('@Test', found[0].read_text())

    def test_phone_sensitive_owner_registers_exact_sources_and_fifty_original_methods(self):
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
        self.assertEqual(6, len(actual))
        for item in manifest['classes']:
            source = (HOST.ROOT / item['source']).read_text()
            methods = re.findall(r'@Test\s+public\s+void\s+(\w+)\s*\(', source)
            self.assertEqual(len(methods), len(set(methods)), item['class'])
            self.assertEqual(sorted(item['methods']), sorted(methods), item['class'])
            self.assertNotIn('@Ignore', source)
        self.assertEqual([12, 30, 8], [len(item['methods']) for item in manifest['classes']])

    def test_phone_sensitive_owner_explicit_group_does_not_expand_the_shared_native_class(self):
        group = 'phone-sensitive-owner'
        primary = REGISTRY[group]['class']
        self.assertEqual([group], HOST.select(REGISTRY, [group], []))
        self.assertEqual([group], HOST.select(REGISTRY, [], [primary]))
        self.assertEqual([group], HOST.select(REGISTRY, [group], [primary]))
        # A helper has @Test bodies, but is compile-only, not another selected suite.
        self.assertIn('dev.pocketengine.phone.observation.PhoneTreeSequenceTest', REGISTRY[group]['helpers'])
        with self.assertRaises(HOST.PrerequisiteError):
            HOST.select(REGISTRY, [], ['dev.pocketengine.phone.observation.PhoneTreeSequenceTest'])

    def test_phone_sensitive_owner_cli_keeps_only_its_fifty_xml_cases_and_original_task(self):
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
            self.assertEqual(50, sum(counts.values()))

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
        self.assertNotIn('matrix:', host_job)
        self.assertIn('        default: apk', workflow)
        title = next(line for line in workflow.splitlines() if line.startswith('run-name:'))
        self.assertIn("inputs.target == 'host-tests'", title)
        self.assertIn("format('Host tests · {0}', inputs.host_group)", title)
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
