"""Portable original-transaction fault models AND separately gated Win32 tests.

The POSIX adapter below is explicitly a plumbing fixture, not Win32 evidence.
Only NativeWindowsHarnessTests uses real Windows APIs; Linux SKIPs it.
"""
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

import test_harness_config as fixture
from harness_config import ConfigError, HarnessConfigs
from harness_config_windows import WindowsHarnessStorage
from platform_backend import PlatformError, PosixBackend
from pocket_companion import JobManager


class PosixOnlyPortModel:
    """Real POSIX bytes; models native callback/receipt shape, never native I/O."""
    def __init__(self, manager):
        self.manager, self.backend = manager, self
        self._lock, self._unsettled, self._closed = threading.RLock(), [], False
        self.unknown = False
    require_available = WindowsHarnessStorage.require_available
    version_probe = WindowsHarnessStorage.version_probe
    replace = WindowsHarnessStorage.replace
    close = WindowsHarnessStorage.close

    def bind_root(self, root):
        self.require_available()
        path = Path(root)
        if path != path.resolve():
            raise PlatformError("Fixture root alias refused")
        fd = self.manager.files.directory({"root": "/", "kind": "local"}, list(path.parts[1:]))
        try:
            info = os.fstat(fd)
            return str(path), [info.st_dev, info.st_ino]
        finally:
            os.close(fd)

    def executable(self, value):
        self.require_available()
        path = Path(value)
        info = path.stat(follow_symlinks=False)
        return path, [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns]

    def read_optional(self, root, relative):
        first = Path(root) / relative.split("/")[0]
        return self.read_bytes(root, relative) if first.exists() or first.is_symlink() else None

    def read_bytes(self, root, relative):
        return self.manager.files.raw({"root": root, "kind": "local"}, relative)

    def compare_and_swap(self, root, relative, before, after, current, *, record_intent):
        record_intent({"phase": "dispatch_intent_persisted", "fixture": "POSIX_ONLY_NOT_WIN32", "target": relative})
        result = PosixBackend().compare_and_swap(root, relative, before, after, current)
        info = (Path(root) / relative).stat()
        result["nativeReplacement"] = {"phase": "recovery_required" if self.unknown else "readback_verified",
                                        "fixture": "POSIX_ONLY_NOT_WIN32", "objects": {"target": {"fileIdentity": [info.st_dev, info.st_ino]}}}
        if self.unknown:
            result.update(status="unknown", verified=False, recoveryMaterialsRetained=True)
        return result

    def delete_harness_configuration(self, root, relative, before, original_identity, current):
        info = (Path(root) / relative).stat(follow_symlinks=False)
        if [info.st_dev, info.st_ino] != original_identity or self.read_bytes(root, relative) != before:
            raise PlatformError("Original object changed; no deletion")
        fd = self.manager.files.directory({"root": root, "kind": "local"}, [])
        try:
            current()
            os.unlink(relative, dir_fd=fd)
            os.fsync(fd)
        finally:
            os.close(fd)
        return {"status": "completed", "verified": True, "deleted": True}


@unittest.skipIf(os.name == "nt", "Explicit POSIX-only transaction model, not native evidence")
class HarnessWindowsPortContracts(unittest.TestCase):
    setUp = fixture.HarnessConfigurationTests.setUp
    tearDown = fixture.HarnessConfigurationTests.tearDown
    binding = fixture.HarnessConfigurationTests.binding
    lease = fixture.HarnessConfigurationTests.lease
    request = fixture.HarnessConfigurationTests.request
    apply = fixture.HarnessConfigurationTests.apply
    restore = fixture.HarnessConfigurationTests.restore

    def activate_model(self):
        model = PosixOnlyPortModel(self.manager)
        self.service.windows_storage = model
        return model

    def test_same_original_plan_lease_raw_crlf_before_image_and_compensation(self):
        self.file.write_bytes(b'\xef\xbb\xbfmodel = "old"\r\n# retained CRLF\r\n')
        before = self.file.read_bytes()
        self.activate_model()
        plan = self.service.plan(self.request())
        receipt = self.apply(plan)
        self.assertEqual("COMMITTED", receipt["state"])
        self.assertIn(b"# retained CRLF\r\n", self.file.read_bytes())
        self.assertTrue(self.file.read_bytes().startswith(b"\xef\xbb\xbf"))
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM harness_root_leases").fetchone()[0])
        _, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual(before, self.file.read_bytes())
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM harness_root_leases").fetchone()[0])

    def test_created_file_restore_uses_original_object_and_never_deletes_new_user_bytes(self):
        self.file.unlink(); self.activate_model()
        plan = self.service.plan(self.request()); self.apply(plan)
        _, restored = self.restore()
        self.assertTrue(restored["restored"])
        self.assertFalse(self.file.exists())
        self.assertIn("nativeDeletion", restored["nativeStorage"])

    def test_unknown_native_result_retains_original_intent_and_does_not_replay(self):
        model = self.activate_model(); model.unknown = True
        plan = self.service.plan(self.request()); receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertFalse(receipt["configApplied"])
        self.assertTrue(receipt["nativeStorage"]["recoveryMaterialsRetained"])
        with patch.object(model, "replace", side_effect=AssertionError("must never replay native CAS")):
            self.assertEqual(receipt, self.apply(plan))

    def test_readback_failure_keeps_persisted_native_identity_and_config_root_in_use(self):
        model = self.activate_model()
        plan = self.service.plan(self.request())
        original = model.compare_and_swap
        def replace(*args, **kwargs):
            result = original(*args, **kwargs)
            # After replacement, a separate read fails. Keep original intent.
            self.service._read = lambda target: (_ for _ in ()).throw(ConfigError("fixture readback unavailable"))
            return result
        # The apply preflight still needs its original read, before this hook.
        model.compare_and_swap = replace
        receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertIn("nativeReplacement", receipt["nativeStorage"])
        self.assertEqual("HELD", self.manager.ledger.execute("SELECT state FROM harness_root_leases").fetchone()[0])


@unittest.skipUnless(os.name == "nt", "Requires actual Win32 handles/Job Objects; Linux is not proof")
class NativeWindowsHarnessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pw-native-harness-fixture-")
        self.root = Path(self.temp.name)
        self.config_root = self.root / "config"; self.config_root.mkdir()
        self.manager = JobManager({"stateDir": str(self.root / "jobs"), "environments": [{"id": "computer", "root": str(self.root), "workers": ["command"], "commands": {"python": sys.executable}}]}, "controlled-fixture-token-no-device-authority")
        self.storage = self.manager.harness_configs.windows_storage
        self.target = {"root": str(self.config_root)}
    def tearDown(self):
        self.manager.close(); self.temp.cleanup()

    def test_actual_native_version_probe_unknown_schema_does_not_infer_cli_support(self):
        executable, before = self.storage.executable(sys.executable)
        output = self.storage.version_probe(self.target, executable, 5)
        self.assertTrue(output.decode("utf-8").startswith("Python "))
        self.assertEqual((executable, before), self.storage.executable(sys.executable))

    def test_created_config_raw_bytes_handle_cas_and_exact_object_deletion(self):
        raw, intents = b'# owner CRLF\r\nmodel = "controlled"\r\n', []
        result = self.storage.replace(self.target, "config.toml", None, raw, lambda: None, intents.append)
        self.assertTrue(result["verified"])
        identity = result["nativeReplacement"]["objects"]["target"]["fileIdentity"]
        deleted = self.storage.replace(self.target, "config.toml", raw, None, lambda: None, intents.append, original_file_identity=identity)
        self.assertTrue(deleted["verified"])
        self.assertFalse((self.config_root / "config.toml").exists())

    def test_same_bytes_at_replaced_file_identity_do_not_authorize_delete(self):
        raw = b'{"model":"controlled"}\r\n'
        old = self.storage.replace(self.target, "settings.json", None, raw, lambda: None, lambda intent: None)
        identity = old["nativeReplacement"]["objects"]["target"]["fileIdentity"]
        replacement = self.storage.replace(self.target, "settings.json", raw, raw, lambda: None, lambda intent: None)
        self.assertTrue(replacement["verified"])
        with self.assertRaises(PlatformError):
            self.storage.replace(self.target, "settings.json", raw, None, lambda: None, lambda intent: None, original_file_identity=identity)
        self.assertEqual(raw, (self.config_root / "settings.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
