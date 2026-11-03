"""Deterministic SDK failure interleavings and optional public CLI checks."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pocket_js
import pocket_plugin


class InitOutputOwnershipTest(unittest.TestCase):
    def test_replaced_directory_and_nested_contents_survive_failure(self):
        for sdk in (pocket_js, pocket_plugin):
            with self.subTest(sdk=sdk.__name__), tempfile.TemporaryDirectory() as root:
                target = Path(root) / "project"
                original = Path(root) / "original-project"
                write_text = Path.write_text

                def replace_and_fail(path, text, **kwargs):
                    write_text(path, text, **kwargs)
                    target.rename(original)
                    (target / "nested").mkdir(parents=True)
                    write_text(target / "nested/keep.txt", "not owned by this init", encoding="utf-8")
                    raise OSError("injected init write failure")

                with mock.patch.object(sdk, "bridge", return_value={}), mock.patch.object(Path, "write_text", replace_and_fail):
                    with self.assertRaises(Exception) as failure:
                        sdk.main(["init", str(target), "--id", "test.plugin", "--name", "Test"])
                self.assertEqual("not owned by this init", (target / "nested/keep.txt").read_text())
                self.assertTrue(original.is_dir())
                self.assertIn("no output was removed", str(failure.exception))
                self.assertIn("partial or replaced", str(failure.exception))
                self.assertIn(str(target), str(failure.exception))
                self.assertIsInstance(failure.exception.__cause__, OSError)

    def test_unconfirmed_original_output_is_retained_with_failure_details(self):
        for sdk in (pocket_js, pocket_plugin):
            with self.subTest(sdk=sdk.__name__), tempfile.TemporaryDirectory() as root:
                target = Path(root) / "project"
                write_text = Path.write_text

                def write_then_fail(path, text, **kwargs):
                    write_text(path, text, **kwargs)
                    raise OSError("injected init write failure")

                with mock.patch.object(sdk, "bridge", return_value={}), mock.patch.object(Path, "write_text", write_then_fail):
                    with self.assertRaises(Exception) as failure:
                        sdk.main(["init", str(target), "--id", "test.plugin", "--name", "Test"])
                self.assertTrue(target.is_dir())
                self.assertTrue(any(target.iterdir()))
                self.assertIn("injected init write failure", str(failure.exception))
                self.assertIn("Inspect", str(failure.exception))

    def test_existing_directory_is_rejected_and_unchanged(self):
        for sdk in (pocket_js, pocket_plugin):
            with self.subTest(sdk=sdk.__name__), tempfile.TemporaryDirectory() as root:
                target = Path(root) / "project"
                target.mkdir()
                sentinel = target / "keep.txt"
                sentinel.write_text("existing project", encoding="utf-8")
                with mock.patch.object(sdk, "bridge", return_value={}):
                    with self.assertRaises(FileExistsError):
                        sdk.main(["init", str(target), "--id", "test.plugin", "--name", "Test"])
                self.assertEqual("existing project", sentinel.read_text())
                self.assertEqual([sentinel], list(target.iterdir()))

    @unittest.skipUnless(os.name == "posix" and os.environ.get("POCKET_PLUGIN_TEST_CLASSPATH")
                         and os.environ.get("POCKET_PLUGIN_TEST_JAVA"), "Requires POSIX limits and compiled SDK")
    def test_public_pack_cli_reports_real_write_failure_and_retains_output(self):
        import resource

        def prevent_file_writes():
            resource.setrlimit(resource.RLIMIT_FSIZE, (0, 0))

        sdk_directory = Path(__file__).resolve().parents[1]
        for cli in ("pocket_js.py", "pocket_plugin.py"):
            with self.subTest(cli=cli), tempfile.TemporaryDirectory() as root:
                project, target = Path(root) / "project", Path(root) / "package.json"
                command = [sys.executable, "-B", str(sdk_directory / cli), "--java", os.environ["POCKET_PLUGIN_TEST_JAVA"],
                           "--classpath", os.environ["POCKET_PLUGIN_TEST_CLASSPATH"]]
                subprocess.run(command + ["init", str(project), "--id", "test.cli", "--name", "Test"],
                               check=True, capture_output=True, timeout=15)
                failure = subprocess.run(command + ["pack", str(project), "--output", str(target)],
                                         capture_output=True, text=True, timeout=15, preexec_fn=prevent_file_writes)
                self.assertEqual(2, failure.returncode, failure.stderr)
                self.assertEqual("", failure.stdout)
                self.assertTrue(target.is_file())
                self.assertEqual(0, target.stat().st_size)
                self.assertIn("no output was removed", failure.stderr)
                self.assertIn("partial or replaced", failure.stderr)
                self.assertIn(str(target), failure.stderr)


if __name__ == "__main__":
    unittest.main()
