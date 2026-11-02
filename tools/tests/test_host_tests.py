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

SPEC = importlib.util.spec_from_file_location('host_tests', Path(__file__).parents[1] / 'host-tests.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
REGISTRY = json.loads(HOST.REGISTRY.read_text())


class HostTestEntrypointTest(unittest.TestCase):
    def test_selection_is_explicit_and_deduplicated(self):
        name = REGISTRY['device-receiver']['class']
        self.assertEqual(['device-receiver'], HOST.select(REGISTRY, ['device-receiver'], [name]))
        for groups, classes in [([], []), (['typo'], []), ([], ['*Test']), ([], ['missing.Test'])]:
            with self.subTest(groups=groups, classes=classes), self.assertRaises(HOST.PrerequisiteError):
                HOST.select(REGISTRY, groups, classes)

    def test_registered_real_sources_and_helpers_exist(self):
        for entry in REGISTRY.values():
            for name in [entry['class']] + entry['helpers']:
                relative = name.replace('.', '/') + '.java'
                found = [HOST.ROOT / root / relative for root in ('app/src/hostTest/java', 'app/src/test/java')
                         if (HOST.ROOT / root / relative).is_file()]
                self.assertEqual(1, len(found), 'expected one exact registered source for ' + name)
                if name == entry['class']:
                    self.assertIn('@Test', found[0].read_text())

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
