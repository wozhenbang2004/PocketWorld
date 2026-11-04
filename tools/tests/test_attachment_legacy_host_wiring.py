"""Selected host wiring only; no JVM, Gradle or product execution is implied."""
import importlib.util
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('attachment_host_tests', Path(__file__).parents[1] / 'host-tests.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)
REGISTRY = json.loads(HOST.REGISTRY.read_text())
GROUP = 'attachment-legacy-integrity'
CLASS = 'dev.pocketengine.features.attachments.LegacyAttachmentIntegrityTest'


class AttachmentLegacyHostWiringTest(unittest.TestCase):
    def test_exact_group_and_class_select_the_same_linux_only_host_fixture(self):
        entry = REGISTRY[GROUP]
        self.assertTrue(entry['linuxPosixFd'])
        self.assertEqual(CLASS, entry['class'])
        self.assertEqual([GROUP], HOST.select(REGISTRY, [GROUP], []))
        self.assertEqual([GROUP], HOST.select(REGISTRY, [], [CLASS]))
        relative = CLASS.replace('.', '/') + '.java'
        self.assertTrue((HOST.ROOT / 'app/src/hostTest/java' / relative).is_file())
        self.assertFalse((HOST.ROOT / 'app/src/test/java' / relative).exists())

    def test_both_jvm_opens_are_inside_the_exact_selected_fixture_condition(self):
        source = (HOST.ROOT / 'tools/host-tests.gradle').read_text()
        guard = "if (selected.contains('" + GROUP + "') || classes.contains('" + CLASS + "'))"
        self.assertEqual(1, source.count(guard))
        block = re.search(re.escape(guard) + r'\s*\{([^{}]*)\}', source).group(1)
        for package in ['java.io', 'sun.nio.fs']:
            self.assertIn("'--add-opens=java.base/" + package + "=ALL-UNNAMED'", block)
        self.assertEqual(1, source.count('--add-opens=java.base/sun.nio.fs=ALL-UNNAMED'))
        self.assertIn('check_posix_descriptor_fixture(entries)', (HOST.ROOT / 'tools/host-tests.py').read_text())

    def test_unselected_groups_do_not_probe_host_descriptors(self):
        with patch.object(HOST.os, 'open', side_effect=AssertionError('unexpected probe')):
            HOST.check_posix_descriptor_fixture([{'robolectric': True}])

    def test_non_linux_is_rejected_before_opening_a_descriptor(self):
        with patch.object(HOST.sys, 'platform', 'darwin'), patch.object(HOST.os, 'open') as opened:
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'requires Linux JDK17'):
                HOST.check_posix_descriptor_fixture([REGISTRY[GROUP]])
            opened.assert_not_called()

    def test_permission_failure_is_bounded_and_closes_the_owned_descriptor(self):
        real_close = HOST.os.close
        with patch.object(HOST.sys, 'platform', 'linux'), \
                patch.object(HOST.os, 'readlink', side_effect=PermissionError('fixture denied')), \
                patch.object(HOST.os, 'close', wraps=real_close) as closed:
            with self.assertRaisesRegex(HOST.PrerequisiteError, 'permission to read /proc/self/fd'):
                HOST.check_posix_descriptor_fixture([REGISTRY[GROUP]])
            closed.assert_called_once()

    @unittest.skipUnless(HOST.sys.platform == 'linux', 'Linux-only prerequisite probe')
    def test_linux_probe_checks_and_closes_its_actual_owned_descriptor(self):
        real_close = HOST.os.close
        with patch.object(HOST.os, 'close', wraps=real_close) as closed:
            HOST.check_posix_descriptor_fixture([REGISTRY[GROUP]])
            closed.assert_called_once()


if __name__ == '__main__':
    unittest.main()
