"""Portable safety contracts plus explicitly Windows-only native acceptance tests.

Linux success is NOT Windows runtime evidence. Native tests are visibly skipped
unless actually running on Windows; no fake Win32 object makes them green.
"""
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from platform_backend import PlatformError, PosixBackend, bytes_version, get_backend
from platform_windows import WindowsBackend, local_root, relative_parts
from workspace_files import FileError


class WindowsPathContractTests(unittest.TestCase):
    def test_case_and_absolute_drive(self):
        self.assertEqual("C:\\Work\\Child", local_root("c:/Work/Child"))
        self.assertEqual(["目录", "中文.txt"], relative_parts("目录/中文.txt"))

    def test_escapes_aliases_and_unc_are_explicitly_rejected(self):
        for value in ("C:relative", "\\\\host\\share", "\\\\?\\C:\\work", "relative", "C:/work/../other"):
            with self.subTest(root=value), self.assertRaises(PlatformError):
                local_root(value)
        for value in ("../x", "/x", "x//y", "C:/x", "x:stream", "x\\y", "x.", "x ", "NUL", "COM1.txt", "LPT9", "a/../b", "CON.out", "x\x00y"):
            with self.subTest(path=value), self.assertRaises(PlatformError):
                relative_parts(value)

    def test_non_windows_cannot_construct_native_support(self):
        if os.name != "nt":
            with self.assertRaises(PlatformError):
                WindowsBackend()
            self.assertEqual("posix", get_backend().name)


@unittest.skipIf(os.name == "nt", "POSIX regression, not a Windows runtime test")
class PosixBackendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.backend = PosixBackend()

    def tearDown(self):
        self.temp.cleanup()

    def test_config_raw_bytes_cas_preserves_crlf_bom_comments_and_mode(self):
        before = b"\xef\xbb\xbf# owner comment\r\nkey = 'original'\r\n"
        after = before.replace(b"original", b"changed")
        target = self.root / "config.toml"
        target.write_bytes(before); target.chmod(0o640)
        receipt = self.backend.compare_and_swap(self.root, "config.toml", before, after)
        self.assertTrue(receipt["verified"])
        self.assertEqual(after, target.read_bytes())
        self.assertEqual(0o640, target.stat().st_mode & 0o777)
        with self.assertRaises(PlatformError):
            self.backend.compare_and_swap(self.root, "config.toml", before, b"bad overwrite")
        self.assertEqual(after, target.read_bytes())

    def test_config_rejects_symlinks_hardlinks_and_parent_escape(self):
        outside = self.root / "outside"; outside.write_bytes(b"keep")
        (self.root / "link").symlink_to(outside)
        with self.assertRaises((FileError, PlatformError)):
            self.backend.compare_and_swap(self.root, "link", b"keep", b"bad")
        os.link(outside, self.root / "hard")
        with self.assertRaises(PlatformError):
            self.backend.compare_and_swap(self.root, "hard", b"keep", b"bad")
        with self.assertRaises(FileError):
            self.backend.compare_and_swap(self.root, "../outside", b"keep", b"bad")
        self.assertEqual(b"keep", outside.read_bytes())

    def test_before_commit_revocation_does_not_replace(self):
        (self.root / "config").write_bytes(b"old")
        calls = []
        def current():
            calls.append(True)
            if len(calls) == 2:
                raise PlatformError("revoked", 403)
        with self.assertRaises(PlatformError):
            self.backend.compare_and_swap(self.root, "config", b"old", b"new", current)
        self.assertEqual(b"old", (self.root / "config").read_bytes())
        self.assertEqual([], list(self.root.glob(".pocketworld-config-*")))

    def test_command_is_real_argv_without_shell(self):
        process = self.backend.spawn_command([sys.executable, "-c", "print('中文; not shell')"], cwd=self.root,
                                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        output, _ = process.communicate(timeout=5)
        self.assertEqual(0, process.returncode)
        self.assertEqual("中文; not shell", output.decode().strip())
        with self.assertRaises(PlatformError):
            self.backend.spawn_command([sys.executable], cwd=self.root, shell=True)

    def test_workspace_display_location_uses_original_nofollow_root_and_current_checks(self):
        checks = []
        self.assertEqual(str(self.root), self.backend.workspace_location(self.root, current=lambda: checks.append(True)))
        self.assertEqual(2, len(checks))
        with self.assertRaises(PlatformError): self.backend.workspace_location("relative")
        alias = self.root / "alias"; alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises((PlatformError, OSError)): self.backend.workspace_location(alias)
        def revoked(): raise PlatformError("controlled original permission revoked", 403)
        with self.assertRaises(PlatformError): self.backend.workspace_location(self.root, current=revoked)


@unittest.skipUnless(os.name == "nt", "Requires real native Windows APIs; Linux is not proof")
class NativeWindowsBackendTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.backend = WindowsBackend()

    def tearDown(self):
        self.temp.cleanup()

    def test_native_workspace_location_comes_from_original_final_handle(self):
        checks = []
        actual = self.backend.workspace_location(str(self.root), current=lambda: checks.append(True))
        with self.backend.directory(str(self.root)) as (_, handle, _identity):
            expected = self.backend.api.final(handle)[4:]
        self.assertEqual(expected, actual)
        self.assertEqual(2, len(checks))
        self.assertTrue(Path(actual).is_absolute())

    def test_native_handle_raw_bytes_cas_and_case(self):
        before = b"\xef\xbb\xbf# keep CRLF\r\nvalue = 'old'\r\n"
        after = before.replace(b"old", b"new")
        target = self.root / "CONFIG.toml"; target.write_bytes(before)
        self.assertEqual(before, self.backend.read_bytes(str(self.root), "config.toml"))
        receipt = self.backend.compare_and_swap(str(self.root), "config.toml", before, after)
        self.assertTrue(receipt["verified"])
        with self.assertRaises(PlatformError):
            self.backend.compare_and_swap(str(self.root), "config.toml", before, b"lost update")
        self.assertEqual(after, target.read_bytes())

    def test_native_private_state_owner_DACL_and_same_handle_read(self):
        from device_identity import _private_dir
        root = _private_dir(self.root / "private-state-fixture")
        sid = self.backend.logon_context()["userSid"]
        info = self.backend.assert_private_directory(str(root), sid)
        self.assertTrue(info["protectedDacl"])
        (root / "fixture.txt").write_bytes(b"private fixture bytes")
        self.assertEqual(b"private fixture bytes", self.backend.read_private_bytes(str(root), "fixture.txt", sid))

    def test_native_stream_and_absent_snapshot_publication(self):
        raw = "中文成果".encode() * 60000
        result = self.backend.create_private_snapshot(str(self.root), "artifact.bin", raw)
        self.assertEqual(len(raw), result["bytes"])
        with self.backend.open_reader(str(self.root), "artifact.bin") as reader:
            self.assertEqual(len(raw), reader.size)
            reader.seek(262144)
            self.assertEqual(raw[262144:262151], reader.read(7))
            self.assertTrue(reader.verify_unchanged())
            with self.assertRaises(OSError):
                (self.root / "artifact.bin").write_bytes(b"must not mutate a pinned reader")
        with self.assertRaises(OSError):
            self.backend.create_private_snapshot(str(self.root), "artifact.bin", b"cannot overwrite")

    def test_native_reviewed_project_directory_copy_uses_absent_only_original_handle(self):
        from test_device_project_packages import package
        _, manifest, files = package(b"native Windows controlled source")
        intents = []
        result = self.backend.import_project_copy(str(self.root), "reviewed-project", manifest, files,
                                                   lambda: None, record_intent=intents.append)
        self.assertTrue(result["verified"], result)
        self.assertEqual(files["src/main.txt"], (self.root / "reviewed-project" / "src" / "main.txt").read_bytes())
        self.assertTrue((self.root / "reviewed-project" / "empty").is_dir())
        self.assertEqual("dispatch_intent_persisted", intents[0]["phase"])
        captured = self.backend.capture_project_copy(str(self.root), "reviewed-project", result["directoryIdentity"], manifest, lambda: None)
        self.assertEqual(files, captured)
        second = self.backend.import_project_copy(str(self.root), "reviewed-project", manifest, files,
                                                   lambda: None, record_intent=lambda value: None)
        self.assertFalse(second["verified"])
        self.assertEqual(files["src/main.txt"], (self.root / "reviewed-project" / "src" / "main.txt").read_bytes())

    def test_native_hardlink_and_reparse_refusal(self):
        original = self.root / "original.txt"; original.write_bytes(b"original")
        os.link(original, self.root / "hard.txt")
        with self.assertRaises(PlatformError):
            self.backend.read_bytes(str(self.root), "hard.txt")
        other = self.root / "other"; other.mkdir(); (other / "a").write_bytes(b"outside")
        try:
            (self.root / "junction").symlink_to(other, target_is_directory=True)
        except OSError as failure:
            self.skipTest("Native symlink creation permission absent; junction/symlink gate not proven: " + str(failure))
        with self.assertRaises(PlatformError):
            self.backend.read_bytes(str(self.root), "junction/a")

    def test_native_job_stops_descendants_after_leader_exit(self):
        code = "import subprocess,sys; child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(90)']);print(child.pid,flush=True)"
        process = self.backend.spawn_command([sys.executable, "-c", code], cwd=str(self.root),
                                             stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            child_pid = int(process.stdout.readline().decode().strip())
            self.assertGreater(child_pid, 0)
            self.assertEqual(0, process.wait(timeout=5))
            proof = self.backend.terminate(process)
            self.assertTrue(proof["leaderReaped"])
            self.assertTrue(proof["processGroupStoppedVerified"], proof)
            self.assertEqual(0, proof["activeProcesses"])
        finally:
            self.backend.terminate(process)
            process.close()

    def test_original_file_ledger_is_not_replayed(self):
        database = sqlite3.connect(":memory:")
        files = self.backend.workspace_files(self.root, database)
        env = {"root": str(self.root), "writeAllowed": True}
        request = {"path": "中文.txt", "content": "第一份", "operationId": "native-write-1", "expectedVersion": "absent"}
        first = files.write("windows", env, request)
        self.assertEqual("completed", first["status"])
        (self.root / "中文.txt").write_text("external edit", encoding="utf-8")
        self.assertEqual(first, files.write("windows", env, request))
        self.assertEqual("external edit", (self.root / "中文.txt").read_text(encoding="utf-8"))
        with self.assertRaises(FileError):
            files.write("windows", env, {**request, "content": "different"})
        database.close()


if __name__ == "__main__":
    unittest.main()
