"""Installed ABI/schema tests with INERT bytes and explicit access fixtures.

These tests do not prove Windows DACLs, Authenticode, installation or GUI input.
The native ACL case lives in the visibly Windows-only backend test class.
"""
from contextlib import contextmanager
import copy
import hashlib
import io
import json
import ntpath
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from platform_backend import PlatformError
from windows_installation import WindowsInstalledContext, NativeInstalledConfig


class AccessFixture:
    sid = "S-1-5-21-100-200-300-1001"
    def __init__(self):
        self.data, self.reads, self.acls = {}, [], []
        self.private = True
    def logon_context(self): return {"userSid": self.sid}
    def assert_private_directory(self, path, sid, require_protected=True):
        self.acls.append((path, sid, require_protected))
        if not self.private or sid != self.sid:
            raise PlatformError("EXPLICIT ACCESS FIXTURE REFUSAL", 403)
    def read_private_bytes(self, root, name, sid, maximum):
        self.assert_private_directory(root, sid, require_protected=False)
        path = ntpath.join(root, name.replace("/", "\\")); self.reads.append(path)
        raw = self.data[path]
        if len(raw) > maximum: raise PlatformError("fixture byte bound", 413)
        return raw
    @contextmanager
    def open_reader(self, root, name, maximum):
        raw = self.data[ntpath.join(root, name.replace("/", "\\"))]
        if len(raw) > maximum: raise PlatformError("fixture byte bound", 413)
        stream = io.BytesIO(raw); stream.size = len(raw)
        yield stream
    def create_private_snapshot(self, root, name, raw, maximum=20971520, *, current=lambda: None):
        current()
        path = ntpath.join(root, name)
        if path in self.data or len(raw) > maximum:
            raise PlatformError("fixture snapshot collision/bound", 409)
        self.data[path] = raw
    def compare_and_swap(self, root, name, before, after, current=lambda: None, *, record_intent=lambda _value: None):
        current()
        path = ntpath.join(root, name)
        if self.data.get(path) != before:
            raise PlatformError("fixture original bytes changed", 409)
        record_intent({"phase": "dispatch_intent_persisted", "beforeSha256": hashlib.sha256(before).hexdigest()})
        current()
        self.data[path] = after
        return {"status": "completed", "verified": True, "consistency": "fixture_only_no_windows_acl_evidence"}


class InstalledEntryContracts(unittest.TestCase):
    def setUp(self):
        self.backend = AccessFixture()
        self.base = "C:\\Users\\Fixture\\AppData\\Local"
        self.home = ntpath.join(self.base, "PocketWorld", "Companion")
        self.state = ntpath.join(self.home, "state")
        self.config = ntpath.join(self.state, "companion.json")
        self.release = ntpath.join(self.home, "releases", "inert-fixture-1")
        self.exe = ntpath.join(self.release, "app", "PocketWorld-Companion.exe")
        self.install = {"schemaVersion": 2, "ownerSid": self.backend.sid, "installId": "test-fixture-install", "stateRoot": self.state, "config": self.config,
                        "identityDir": ntpath.join(self.state, "identity-v1"), "jobs": ntpath.join(self.state, "jobs"), "logs": ntpath.join(self.state, "logs")}
        files = {"app/PocketWorld-Companion.exe": b"INERT EMBEDDED RUNTIME FIXTURE", "driver/cua-driver.exe": b"INERT CUA FIXTURE", "driver/agent-desktop.exe": b"INERT SEMANTIC FIXTURE"}
        self.manifest = {"schemaVersion": 1, "product": "PocketWorld-Companion", "version": "inert-fixture-1", "platform": "windows-x64",
                         "cua": {"sourceCommit": "f595a4fb6f524e555e5e422aab81c3457fe4a713", "spacesIncluded": False, "perceptionIncluded": False,
                                 "sha256": hashlib.sha256(files["driver/cua-driver.exe"]).hexdigest()},
                         "agentDesktop": {"sourceCommit": "6660fbac768dc7e7332837b90ed465684859afa3", "contractVersion": "2.6",
                                          "runtime": "headless-semantic-cli", "sha256": hashlib.sha256(files["driver/agent-desktop.exe"]).hexdigest()},
                         "files": [{"path": path, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)} for path, raw in files.items()]}
        manifest_raw = json.dumps(self.manifest).encode()
        self.current = {"schemaVersion": 2, "ownerSid": self.backend.sid, "installId": self.install["installId"], "version": self.manifest["version"],
                        "previousVersion": None, "previousRelease": None, "root": self.release, "state": self.state, "config": self.config,
                        "loginStartEnabled": False, "allowUnsignedDevelopment": True, "signerThumbprint": None, "manifestSha256": hashlib.sha256(manifest_raw).hexdigest()}
        self.settings = {"stateDir": self.install["jobs"], "tokenFile": ntpath.join(self.state, "companion-token.txt"),
                         "devices": {"enabled": False, "identityDir": self.install["identityDir"]},
                         "desktop": {"enabled": True, "binary": "C:\\UNTRUSTED-OLD-RELEASE\\cua-driver.exe", "sha256": "0" * 64}, "environments": []}
        for name, value in (("install-state.json", self.install), ("current.json", self.current)):
            self.backend.data[ntpath.join(self.home, name)] = json.dumps(value).encode()
        self.backend.data[ntpath.join(self.release, "bundle-manifest.json")] = manifest_raw
        self.backend.data[self.config] = json.dumps(self.settings).encode()
        self.backend.data[ntpath.join(self.state, "companion-token.txt")] = b"INERT-NOT-A-REAL-USER-TOKEN"
        for name, raw in files.items(): self.backend.data[ntpath.join(self.release, name.replace("/", "\\"))] = raw

    def context(self, config=None):
        return WindowsInstalledContext(config or self.config, backend=self.backend, localappdata=self.base, current_executable=self.exe)

    def test_fixed_private_ABI_overrides_only_current_driver_not_user_configuration(self):
        launch = self.context().launch_config()
        actual = launch.json()
        self.assertEqual(self.install["jobs"], actual["stateDir"])
        self.assertEqual(ntpath.join(self.release, "driver", "cua-driver.exe"), actual["desktop"]["binary"])
        self.assertEqual(self.manifest["cua"]["sha256"], actual["desktop"]["sha256"])
        self.assertEqual(ntpath.join(self.release, "driver", "agent-desktop.exe"), actual["desktop"]["agentDesktop"]["binary"])
        self.assertEqual(self.manifest["agentDesktop"]["sha256"], actual["desktop"]["agentDesktop"]["sha256"])
        self.assertEqual(self.settings, json.loads(self.backend.data[self.config]))
        launch.check_current()
        with self.assertRaises(TypeError): NativeInstalledConfig(None, None, None, None)

    def test_external_config_state_owner_and_manifest_tampering_fail_closed(self):
        with self.assertRaises(PlatformError): self.context("C:\\other\\config.json")
        self.assertFalse(any(path.startswith("C:\\other") for path in self.backend.reads))
        self.settings["stateDir"] = "C:\\other\\jobs"
        self.backend.data[self.config] = json.dumps(self.settings).encode()
        with self.assertRaises(PlatformError): self.context().launch_config()
        self.assertFalse(any(path.startswith("C:\\other") for path in self.backend.reads))
        self.backend.private = False
        with self.assertRaises(PlatformError): self.context()

    def test_stale_release_and_runtime_bytes_cannot_start_via_new_pointer(self):
        with self.assertRaises(PlatformError):
            WindowsInstalledContext(self.config, backend=self.backend, localappdata=self.base, current_executable=self.exe.replace("inert-fixture-1", "old"))
        self.backend.data[self.exe] += b"TAMPERED"
        with self.assertRaises(PlatformError): self.context()

    def test_changed_pointer_or_config_after_native_review_invalidates_launch(self):
        launch = self.context().launch_config()
        current = copy.deepcopy(self.current); current["loginStartEnabled"] = True
        self.backend.data[ntpath.join(self.home, "current.json")] = json.dumps(current).encode()
        with self.assertRaises(PlatformError): launch.check_current()
        self.backend.data[ntpath.join(self.home, "current.json")] = json.dumps(self.current).encode()
        self.backend.data[self.config] += b" "
        with self.assertRaises(PlatformError): launch.check_current()

    def test_startup_never_provisions_missing_identity_or_synthesizes_configuration(self):
        original = dict(self.backend.data)
        del self.backend.data[self.config]
        with patch("device_identity.provision_identity", side_effect=AssertionError("startup provisioning forbidden")):
            with self.assertRaises(KeyError): self.context().launch_config()
        self.assertEqual(set(original) - {self.config}, set(self.backend.data))

    def test_owner_harness_enable_keeps_registered_state_and_other_settings(self):
        import harness_cli
        self.settings["environments"] = [{"id": "computer", "kind": "local", "root": "C:\\Workspace", "workers": ["command"], "writeAllowed": False}]
        self.backend.data[self.config] = json.dumps(self.settings).encode()
        context = self.context()
        launch = context.launch_config()
        with self.assertRaises(ValueError):
            harness_cli.enable_installed(context, launch, "computer", "acp_opencode")
        result = harness_cli.enable_installed(context, launch, "computer", "acp_opencode", allow_write=True)
        self.assertTrue(result["configurationChanged"])
        actual = json.loads(self.backend.data[self.config])
        self.assertEqual(self.settings["desktop"], actual["desktop"])
        self.assertEqual(self.settings["devices"], actual["devices"])
        self.assertEqual(self.settings["stateDir"], actual["stateDir"])
        self.assertEqual(self.settings["tokenFile"], actual["tokenFile"])
        self.assertEqual(["command", "acp_opencode"], actual["environments"][0]["workers"])
        with self.assertRaises(PlatformError):
            harness_cli.enable_installed(context, launch, "computer", "muse_msp", allow_write=True)

    def test_packaged_owner_entry_routes_discovery_and_enable_without_starting_manager(self):
        import desktop_worker
        self.settings["environments"] = [{"id": "computer", "kind": "local", "root": "C:\\Workspace", "workers": ["command"], "writeAllowed": False}]
        self.backend.data[self.config] = json.dumps(self.settings).encode()
        def original_context(config, *, current_executable):
            return WindowsInstalledContext(config, backend=self.backend, localappdata=self.base, current_executable=current_executable)
        with tempfile.TemporaryDirectory() as directory, patch.object(desktop_worker, "_state", return_value=Path(directory)), \
                patch.object(desktop_worker, "_mutex", return_value=None) as mutex, \
                patch("windows_installation.WindowsInstalledContext", side_effect=original_context), \
                patch.object(sys, "frozen", True, create=True), patch.object(sys, "executable", self.exe), \
                patch("pocket_companion.JobManager", side_effect=AssertionError("owner setup must not start a runtime")):
            output = io.StringIO()
            with patch.object(sys, "argv", [self.exe, "--list-harnesses", "--environment", "computer"]), patch("sys.stdout", output):
                desktop_worker.main()
            self.assertEqual(40, len(json.loads(output.getvalue())["harnesses"]))
            mutex.assert_not_called()
            output = io.StringIO()
            with patch.object(sys, "argv", [self.exe, "--enable-harness", "acp_opencode", "--environment", "computer", "--allow-harness-write"]), patch("sys.stdout", output):
                desktop_worker.main()
            self.assertTrue(json.loads(output.getvalue())["configurationChanged"])
            mutex.assert_called_once()
            self.assertIn("acp_opencode", json.loads(self.backend.data[self.config])["environments"][0]["workers"])


if __name__ == "__main__": unittest.main()
