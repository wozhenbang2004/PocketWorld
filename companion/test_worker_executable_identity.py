"""Real entrypoint metadata and original probe contracts; no real CLI/account."""
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

import worker_executable_identity as identity
from pocket_companion import JobManager

TOKEN = "worker-identity-fixture-only-token-longer-than-32-characters"


@unittest.skipUnless(os.name == "posix" and sys.platform.startswith("linux"), "Linux native-file observation scope")
class WorkerExecutableIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-worker-identity-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.executable = self.bin / "codex"
        shutil.copy2(shutil.which("true"), self.executable)
        self.environment = {"id": "computer", "kind": "local", "root": str(self.workspace), "workers": ["codex_app_server", "claude"]}
        self.path = patch.dict(os.environ, {"PATH": str(self.bin)})
        self.path.start()

    def tearDown(self):
        self.path.stop()
        self.temp.cleanup()

    def facts(self):
        return identity.inspect(self.environment, "codex_app_server")

    def test_native_stat_is_stable_public_and_never_content_version_or_chain_proof(self):
        with patch("subprocess.Popen", side_effect=AssertionError("metadata must not spawn")):
            first = self.facts()
            self.assertEqual(first, self.facts())
        self.assertTrue(first["executableIdentityVerified"])
        self.assertRegex(first["executableIdentity"], r"^[a-f0-9]{64}$")
        self.assertEqual("native_file_stat_v1", first["executableIdentityKind"])
        self.assertFalse(first["versionContentVerified"])
        self.assertFalse(first["executionChainVerified"])
        self.assertNotIn(str(self.root), json.dumps(first))
        self.assertNotIn(TOKEN, json.dumps(first))

    def test_same_path_hot_replace_changes_entrypoint_identity_without_a_new_registry(self):
        first = self.facts()
        replacement = self.bin / "replacement"
        shutil.copy2(self.executable, replacement)
        os.replace(replacement, self.executable)
        after = self.facts()
        self.assertTrue(after["executableIdentityVerified"])
        self.assertNotEqual(first["executableIdentity"], after["executableIdentity"])
        self.assertFalse(identity.finish_probe(first, after)["executableIdentityVerified"])
        self.assertEqual("worker_entrypoint_changed_during_probe", identity.finish_probe(first, after)["executableIdentityReason"])

    def test_mtime_restore_is_not_version_content_proof_and_ctime_still_changes(self):
        first = self.facts()
        info = self.executable.stat()
        with self.executable.open("ab") as output:
            output.write(b"not-an-executed-fixture-change")
        os.utime(self.executable, ns=(info.st_atime_ns, info.st_mtime_ns))
        after = self.facts()
        self.assertNotEqual(first["executableIdentity"], after["executableIdentity"])
        self.assertFalse(after["versionContentVerified"])

    def test_scripts_interpreters_fifo_remote_and_unknown_workers_stay_unverified(self):
        self.executable.write_text("#!/bin/sh\nexit 0\n")
        self.assertEqual("script_or_unknown_entrypoint", self.facts()["executableIdentityReason"])
        self.executable.unlink()
        self.executable.symlink_to(sys.executable)
        self.assertEqual("interpreter_entrypoint_unverified", self.facts()["executableIdentityReason"])
        self.executable.unlink()
        os.mkfifo(self.executable, 0o700)
        self.assertFalse(self.facts()["executableIdentityVerified"])
        self.assertEqual("ssh_entrypoint_unverified", identity.inspect({**self.environment, "kind": "ssh"}, "codex_app_server")["executableIdentityReason"])
        self.assertFalse(identity.inspect(self.environment, "command")["executableIdentityVerified"])
        self.assertFalse(identity.finish_probe(identity.unknown("missing"), identity.unknown("missing"))["executableIdentityVerified"])

    def test_relative_path_is_resolved_at_the_original_child_cwd_and_symlink_retargets_invalidate(self):
        local = self.workspace / "local-bin"
        local.mkdir()
        target = local / "codex"
        shutil.copy2(self.executable, target)
        with patch.dict(os.environ, {"PATH": "local-bin"}):
            first = self.facts()
            self.assertTrue(first["executableIdentityVerified"])
            target.unlink()
            target.symlink_to(self.executable)
            self.assertNotEqual(first["executableIdentity"], self.facts()["executableIdentity"])
        self.assertFalse(identity.inspect(self.environment, "codex_app_server", [sys.executable, "unbound.py"])["executableIdentityVerified"])

    def test_original_environments_publish_additive_identity_without_process_or_ledger(self):
        manager = JobManager({"stateDir": str(self.root / "state"), "environments": [self.environment]}, TOKEN)
        try:
            with patch.object(manager, "_spawn", side_effect=AssertionError("GET must never spawn")):
                result = manager.list_environments()["environments"][0]
            worker = next(row for row in result["workers"] if row["id"] == "codex_app_server")
            self.assertTrue(worker["available"])
            self.assertEqual(self.facts(), {key: worker[key] for key in self.facts()})
            self.assertEqual(manager.environment_binding("computer"), result["bindingHash"])
            self.assertEqual({}, manager.jobs)
            self.assertEqual(0, manager.ledger.execute("SELECT count(*) FROM operations").fetchone()[0])
            self.assertNotIn(str(self.executable), json.dumps(worker))
        finally:
            manager.close()

    def test_original_probe_keeps_old_protocol_success_but_never_binds_a_replaced_launcher(self):
        from test_codex_driver import FIXTURE
        fixture = self.root / "protocol-peer.py"
        fixture.write_text(FIXTURE)
        manager = JobManager({"stateDir": str(self.root / "probe-state"), "environments": [self.environment]}, TOKEN)
        try:
            with patch.object(manager, "_launch", return_value=([sys.executable, str(fixture), "probe"], str(self.workspace))):
                result = manager.probe_worker({"environmentId": "computer", "worker": "codex_app_server", "model": "fixture-model"})
            self.assertTrue(result["available"])
            self.assertTrue(result["protocolVerified"])
            self.assertFalse(result["executableIdentityVerified"])
            self.assertFalse(result["versionContentVerified"])
            self.assertFalse(result["executionChainVerified"])
            self.assertIsNone(result["executableIdentity"])
            self.assertEqual({}, manager.jobs)
            self.assertEqual(0, manager.ledger.execute("SELECT count(*) FROM operations").fetchone()[0])
        finally:
            manager.close()
