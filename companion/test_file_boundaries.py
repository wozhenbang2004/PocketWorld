"""Focused descriptor ownership and optimistic workspace parent-binding regressions."""
import errno
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import input_files
from workspace_files import FileError, WorkspaceFiles


class FileBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-file-boundary-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.database = sqlite3.connect(":memory:")
        self.addCleanup(self.database.close)
        self.files = WorkspaceFiles(self.root / "state", self.database)
        self.env = {"root": str(self.workspace), "writeAllowed": True}

    def request(self, path="sub/new.txt", before=None):
        return {"operationId": "original-operation", "path": path, "content": "approved bytes",
                "expectedVersion": "absent" if before is None else self.files.version(before)}

    def input_job(self, operation="original-input"):
        info = self.workspace.stat()
        identity = hashlib.sha256(json.dumps([str(self.workspace), info.st_dev, info.st_ino], separators=(",", ":")).encode()).hexdigest()
        return {"operationId": operation, "workspaceIdentity": identity, "inputState": {}}

    def test_first_input_directory_save_failure_closes_target_descriptor(self):
        for index, failure in enumerate((OSError("controlled IO failure"), sqlite3.OperationalError("controlled persistence failure"))):
            with self.subTest(failure=type(failure).__name__):
                job = self.input_job("original-" + str(index))
                manager = SimpleNamespace(files=self.files, _save=Mock(side_effect=failure))
                opened = []
                real_open = os.open

                def record_open(path, *args, **kwargs):
                    descriptor = real_open(path, *args, **kwargs)
                    if path == job["operationId"]:
                        opened.append(descriptor)
                    return descriptor

                try:
                    with patch("input_files.os.open", side_effect=record_open):
                        with self.assertRaises(FileError if isinstance(failure, OSError) else type(failure)):
                            input_files._directory(manager, job, self.env, True, FileError)
                    manager._save.assert_called_once_with(job)
                    self.assertEqual(1, len(opened))
                    with self.assertRaises(OSError) as closed:
                        os.fstat(opened[0])
                    self.assertEqual(errno.EBADF, closed.exception.errno)
                finally:
                    # The failing baseline must not leave its intentionally exposed leak behind.
                    for descriptor in opened:
                        try:
                            os.close(descriptor)
                        except OSError:
                            pass

    def test_input_directory_success_transfers_live_descriptor_and_reopen_checks_identity(self):
        job = self.input_job()
        manager = SimpleNamespace(files=self.files, _save=Mock())
        descriptor = input_files._directory(manager, job, self.env, True, FileError)
        try:
            original = os.fstat(descriptor)
            manager._save.assert_called_once_with(job)
            self.assertIn("directoryIdentity", job["inputState"])
        finally:
            os.close(descriptor)
        descriptor = input_files._directory(manager, job, self.env, False, FileError)
        try:
            self.assertEqual(original.st_ino, os.fstat(descriptor).st_ino)
        finally:
            os.close(descriptor)
        job["inputState"]["directoryIdentity"] = "different-directory"
        with patch("input_files.os.close", wraps=os.close) as close:
            with self.assertRaisesRegex(FileError, "directory was replaced"):
                input_files._directory(manager, job, self.env, False, FileError)
        self.assertEqual(3, close.call_count)  # Target, prefix and workspace root, each once.

    def test_parent_renamed_after_temporary_write_does_not_replace_wrong_path(self):
        parent = self.workspace / "sub"
        parent.mkdir()
        moved = self.root / "moved-outside-workspace"
        real_fsync = os.fsync
        replaced = []

        def move_after_flush(descriptor):
            real_fsync(descriptor)
            if not replaced:
                parent.rename(moved)
                parent.mkdir()
                replaced.append(True)

        request = self.request()
        with patch("workspace_files.os.fsync", side_effect=move_after_flush), patch("workspace_files.os.replace", wraps=os.replace) as replace:
            receipt = self.files.write("computer", self.env, request)
        self.assertEqual([True], replaced)
        self.assertFalse((moved / "new.txt").exists(), "The renamed directory must not receive the approved path's bytes")
        replace.assert_not_called()
        self.assertEqual("failed", receipt["status"])
        self.assertFalse((parent / "new.txt").exists())
        self.assertEqual([], list(moved.iterdir()))
        with patch("workspace_files.os.replace") as replay:
            self.assertEqual(receipt, self.files.write("computer", self.env, request))
        replay.assert_not_called()
        self.assertEqual(receipt, self.files.operation(request["operationId"]))

    def test_existing_file_parent_replaced_in_current_check_is_not_written(self):
        parent = self.workspace / "sub"
        parent.mkdir()
        before = b"reviewed original bytes"
        (parent / "new.txt").write_bytes(before)
        moved = self.workspace / "renamed"
        checks = []

        def current():
            checks.append(True)
            if len(checks) == 3:  # After the last content check, before replacement.
                parent.rename(moved)
                parent.mkdir()
                (parent / "new.txt").write_bytes(before)

        request = self.request(before=before)
        with patch("workspace_files.os.replace", wraps=os.replace) as replace:
            receipt = self.files.write("computer", self.env, request, current=current)
        self.assertEqual(3, len(checks))
        replace.assert_not_called()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(before, (moved / "new.txt").read_bytes())
        self.assertEqual(before, (parent / "new.txt").read_bytes())
        self.assertEqual(["new.txt"], [item.name for item in moved.iterdir()])

    def test_parent_changed_after_replace_is_unknown_even_with_matching_new_path(self):
        parent = self.workspace / "sub"
        parent.mkdir()
        moved = self.workspace / "renamed"
        request = self.request()
        real_replace = os.replace
        attempts = []

        def replace_then_rebind(source, target, **options):
            attempts.append(True)
            real_replace(source, target, **options)
            parent.rename(moved)
            parent.mkdir()
            (parent / "new.txt").write_text(request["content"])

        with patch("workspace_files.os.replace", side_effect=replace_then_rebind):
            receipt = self.files.write("computer", self.env, request)
            self.assertEqual(receipt, self.files.write("computer", self.env, request))
        self.assertEqual([True], attempts)
        self.assertEqual("unknown", receipt["status"])
        self.assertNotIn("verified", receipt)
        self.assertEqual(request["content"], (moved / "new.txt").read_text())
        self.assertEqual(receipt, self.files.operation(request["operationId"]))

    def test_stale_version_closes_pinned_directory_before_any_intent(self):
        (self.workspace / "sub").mkdir()
        (self.workspace / "sub/new.txt").write_bytes(b"already exists")
        opened = []
        real_directory = self.files.directory

        def record_directory(*args):
            descriptor = real_directory(*args)
            opened.append(descriptor)
            return descriptor

        with patch.object(self.files, "directory", side_effect=record_directory), patch("workspace_files.os.replace") as replace:
            with self.assertRaisesRegex(FileError, "version changed"):
                self.files.write("computer", self.env, self.request())
        replace.assert_not_called()
        self.assertEqual("not_found", self.files.operation("original-operation")["status"])
        self.assertEqual(1, len(opened))
        with self.assertRaises(OSError) as closed:
            os.fstat(opened[0])
        self.assertEqual(errno.EBADF, closed.exception.errno)


if __name__ == "__main__":
    unittest.main()
