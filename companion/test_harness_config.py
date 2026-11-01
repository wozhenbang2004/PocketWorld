"""Controlled real-file Harness transactions. No real CLI account or provider is used."""
import copy
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from pocket_companion import JobManager
from harness_config import HarnessConfigs, ConfigError, JsonFields, CodexFields, ABSENT, digest

TOKEN = "fixture-only-companion-token-not-a-real-secret-123456789"
ACCESS = "fixture-only-scoped-gateway-token-1234567890123456"
ORIGINAL = "fixture-only-original-api-key-never-exported"


class HarnessConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-harness-config-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.config_root = self.root / "config"
        self.config_root.mkdir()
        self.executable = self.root / "codex-fixture"
        self.executable.write_text("#!/bin/sh\nprintf 'codex-cli 0.164.0-alpha.11\\n'\n")
        self.executable.chmod(0o700)
        self.target = {"bindingId": "controlled-codex", "family": "codex", "configRoot": str(self.config_root),
                       "executable": str(self.executable), "scope": "PROJECT_SESSION"}
        self.config = {"stateDir": str(self.root / "state"), "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace),
                      "writeAllowed": True, "workers": ["command", "codex_app_server", "claude"], "commands": {"python": sys.executable}, "harnesses": [self.target]}]}
        self.clean_env = patch.dict(os.environ, {key: "" for key in ("CODEX_PROFILE", "ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL", "CLAUDE_CODE_SETTINGS_PATH")})
        self.clean_env.start()
        self.manager = JobManager(self.config, TOKEN)
        self.service = HarnessConfigs(self.manager)
        self.manager.harness_configs = self.service
        self.file = self.config_root / "config.toml"
        self.file.write_text('# owner comment\nmodel = "original" # keep this comment\nmodel_provider = "original-provider"\n\n[model_providers.original]\nname = "Original"\nbase_url = "https://original.invalid/v1"\n')
        self.initial = self.file.read_text()

    def tearDown(self):
        self.manager.close()
        self.clean_env.stop()
        self.temp.cleanup()

    def binding(self):
        return {"environmentId": "computer", "environmentBinding": self.manager.environment_binding("computer"), "bindingId": self.target["bindingId"]}

    def lease(self, lease_id="gateway-one", access=ACCESS):
        lease = {"leaseId": lease_id, "endpoint": "http://127.0.0.1:43117", "accessToken": access, "expiresAt": self.manager.now() + 240000,
                 "environmentBinding": self.binding()["environmentBinding"], "profileDigest": "a" * 64, "taskId": "task-original", "runId": "run-original", "epoch": 3,
                 "audience": "OWNER_PRIVATE", "purpose": "EXTERNAL_RELAY"}
        self.service.register_endpoint(lease)
        return lease

    def request(self, operation="config-original", **overrides):
        lease = self.lease()
        probe = self.service.probe(self.binding())
        return {**self.binding(), "operationId": operation, "identityDigest": probe["identityDigest"], "profileDigest": lease["profileDigest"],
                "endpointLeaseRef": lease["leaseId"], "taskId": lease["taskId"], "runId": lease["runId"], "epoch": lease["epoch"], "model": "fixture-model", **overrides}

    def apply(self, plan):
        return self.service.apply(plan["planId"], {**self.binding(), "operationId": plan["operationId"], "planDigest": plan["planDigest"]})

    def applied(self):
        plan = self.service.plan(self.request())
        receipt = self.apply(plan)
        self.assertEqual("COMMITTED", receipt["state"], receipt)
        return plan, receipt

    def restore(self, original="config-original", operation="restore-original"):
        probe = self.service.probe(self.binding())
        plan = self.service.restore_plan(original, {**self.binding(), "operationId": operation, "identityDigest": probe["identityDigest"]})
        return plan, self.apply(plan)

    def claude(self, raw=None):
        self.target.update(bindingId="controlled-claude", family="claude")
        self.manager.environments["computer"]["harnesses"] = [self.target]
        self.executable.write_text("#!/bin/sh\nprintf '2.1.259 (Claude Code)\\n'\n")
        self.file = self.config_root / "settings.json"
        self.file.write_text(raw or ('{\n  "env": {"ANTHROPIC_BASE_URL": "https://old.invalid", "ANTHROPIC_API_KEY": "' + ORIGINAL + '", "UNCHANGED": "owner-value"},\n  "model": "original",\n  "permissions": {"allow": ["Read"]}\n}\n'))
        self.initial = self.file.read_text()

    def test_probe_is_version_only_and_does_not_claim_protocol_or_inference(self):
        result = self.service.probe(self.binding())
        self.assertTrue(result["capabilities"]["configWritable"])
        self.assertFalse(result["capabilities"]["protocolVerified"])
        self.assertFalse(result["runtimeVerified"])
        self.assertEqual({}, self.manager.jobs)
        self.assertEqual(self.initial, self.file.read_text())

    def test_codex_preview_apply_real_readback_and_restore_preserves_other_provider_and_comment(self):
        request = self.request()
        plan = self.service.plan(request)
        self.assertEqual(self.initial, self.file.read_text())
        self.assertNotIn(ACCESS, json.dumps(plan))
        self.assertEqual("OPTIMISTIC", plan["writeConsistency"])
        receipt = self.apply(plan)
        self.assertTrue(receipt["configApplied"])
        self.assertTrue(receipt["effectiveReadback"])
        self.assertFalse(receipt["runtimeVerified"])
        actual = self.file.read_text()
        self.assertIn('# owner comment', actual)
        self.assertIn('# keep this comment', actual)
        self.assertIn('[model_providers.original]\nname = "Original"', actual)
        self.assertEqual("pocketworld", CodexFields(actual).value["model_provider"])
        _, restored = self.restore()
        self.assertTrue(restored["restored"])
        value = CodexFields(self.file.read_text()).value
        self.assertEqual("original-provider", value["model_provider"])
        self.assertNotIn("pocketworld", value["model_providers"])
        self.assertIn('# keep this comment', self.file.read_text())

    def test_claude_auth_group_restores_original_key_and_retains_unrelated_bytes(self):
        self.claude()
        plan, _ = self.applied()
        self.assertNotIn(ORIGINAL, json.dumps(plan))
        value = json.loads(self.file.read_text())
        self.assertNotIn("ANTHROPIC_API_KEY", value["env"])
        self.assertEqual(ACCESS, value["env"]["ANTHROPIC_AUTH_TOKEN"])
        self.assertIn('"permissions": {"allow": ["Read"]}', self.file.read_text())
        _, receipt = self.restore()
        self.assertEqual("COMMITTED", receipt["state"])
        value = json.loads(self.file.read_text())
        self.assertEqual(ORIGINAL, value["env"]["ANTHROPIC_API_KEY"])
        self.assertNotIn("ANTHROPIC_AUTH_TOKEN", value["env"])
        self.assertEqual("owner-value", value["env"]["UNCHANGED"])

    def test_endpoint_only_drift_blocks_entire_auth_restore_and_never_sends_old_key_to_new_endpoint(self):
        self.claude()
        self.applied()
        self.file.write_text(JsonFields(self.file.read_text()).set("env.ANTHROPIC_BASE_URL", {"value": "https://external.invalid"}))
        external = self.file.read_text()
        plan, receipt = self.restore()
        self.assertEqual("effective_authentication", plan["conflicts"][0]["group"])
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(external, self.file.read_text())
        self.assertNotIn(ORIGINAL, self.file.read_text())

    def test_credential_only_drift_also_blocks_whole_auth_group(self):
        self.claude()
        self.applied()
        self.file.write_text(JsonFields(self.file.read_text()).set("env.ANTHROPIC_AUTH_TOKEN", {"value": "fixture-external-token"}))
        external = self.file.read_text()
        _, receipt = self.restore()
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(external, self.file.read_text())

    def test_preview_file_drift_returns_conflict_without_overwriting_external_change(self):
        plan = self.service.plan(self.request())
        self.file.write_text(self.initial + "\n# external edit\n")
        receipt = self.apply(plan)
        self.assertEqual("CONFLICT", receipt["state"])
        self.assertTrue(self.file.read_text().endswith("# external edit\n"))

    def test_same_operation_same_digest_is_immutable_and_different_digest_rejected(self):
        request = self.request()
        plan = self.service.plan(request)
        self.assertEqual(plan, self.service.plan(request))
        receipt = self.apply(plan)
        with patch.object(self.service, "_replace", side_effect=AssertionError("must not rewrite")):
            self.assertEqual(receipt, self.apply(plan))
        changed = {**self.binding(), "operationId": plan["operationId"], "planDigest": "f" * 64}
        with self.assertRaises(ConfigError):
            self.service.apply(plan["planId"], changed)

    def test_different_after_content_cannot_be_applied_from_phone(self):
        plan = self.service.plan(self.request())
        with self.assertRaises(ConfigError):
            self.service.apply(plan["planId"], {**self.binding(), "operationId": plan["operationId"], "planDigest": plan["planDigest"], "model": "injected"})
        self.assertEqual(self.initial, self.file.read_text())

    def test_unknown_schema_and_executable_replacement_refuse_writes(self):
        self.executable.write_text("#!/bin/sh\nprintf 'codex-cli 99.0.0\\n'\n")
        probe = self.service.probe(self.binding())
        self.assertEqual("UNKNOWN_SCHEMA", probe["identity"]["configSchemaVersion"])
        self.assertFalse(probe["capabilities"]["configWritable"])
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_duplicate_json_and_toml_keys_rejected_without_modification(self):
        for raw in ('{"env": {"A": "x", "A": "y"}}', '{"x": 1, "x": 2}'):
            with self.assertRaises(ConfigError):
                JsonFields(raw)
        with self.assertRaises(ConfigError):
            CodexFields('model = "one"\nmodel = "two"\n')

    def test_codex_third_party_namespace_is_not_assumed_owned(self):
        self.file.write_text(self.initial + '\n[model_providers.pocketworld]\nname = "owner"\n')
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_symlink_root_and_file_and_arbitrary_request_path_are_rejected(self):
        self.file.unlink()
        self.file.symlink_to(self.root / "not-allowed")
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())
        self.file.unlink()
        self.file.write_text(self.initial)
        bad = self.request()
        bad["configRoot"] = "/unreviewed/path"
        with self.assertRaises(ConfigError):
            self.service.plan(bad)
        link = self.root / "link"
        link.symlink_to(self.config_root)
        self.target["configRoot"] = str(link)
        with self.assertRaises(ConfigError):
            self.service.probe(self.binding())

    def test_existing_managed_override_and_chatgpt_auth_file_are_rejected(self):
        (self.config_root / "auth.json").write_text('{"fixture": "not-a-real-account"}')
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())
        (self.config_root / "auth.json").unlink()
        self.claude()
        (self.config_root / "managed-settings.json").write_text('{}')
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_expired_or_revoked_endpoint_and_changed_generation_refuse_apply(self):
        plan = self.service.plan(self.request())
        self.service.endpoints.clear()
        with self.assertRaises(ConfigError):
            self.apply(plan)
        self.lease()
        self.manager.instance_id = "new-instance"
        with self.assertRaises(ConfigError):
            self.apply(plan)
        self.assertEqual(self.initial, self.file.read_text())

    def test_readback_mismatch_is_recovery_required_never_false_success(self):
        plan = self.service.plan(self.request())
        actual_replace = self.service._replace
        def interfering(*args):
            actual_replace(*args)
            self.file.write_text(self.file.read_text() + "\n# raced external writer\n")
        with patch.object(self.service, "_replace", side_effect=interfering):
            receipt = self.apply(plan)
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertFalse(receipt["configApplied"])
        self.assertFalse(receipt["runtimeVerified"])

    def test_write_unknown_is_never_replayed_when_original_operation_is_queried_or_reapplied(self):
        plan = self.service.plan(self.request())
        with patch.object(self.service, "_replace", side_effect=OSError("fixture disconnect")):
            receipt = self.apply(plan)
        with patch.object(self.service, "_replace", side_effect=AssertionError("must not replay")):
            self.assertEqual(receipt, self.service.operation(plan["operationId"]))
            self.assertEqual(receipt, self.apply(plan))

    def test_protected_before_images_are_only_in_original_target_database(self):
        self.claude()
        plan, receipt = self.applied()
        self.assertNotIn(ORIGINAL, json.dumps(plan))
        self.assertNotIn(ACCESS, json.dumps(receipt))
        self.assertEqual(0o600, (self.manager.state / "operations.sqlite3").stat().st_mode & 0o777)
        private = self.manager.ledger.execute("SELECT private_plan FROM harness_plans").fetchone()[0]
        self.assertIn(ORIGINAL, private)
        self.assertFalse(self.manager.files.state.exists())

    def test_new_owned_file_deleted_only_when_unchanged(self):
        self.file.unlink()
        self.applied()
        _, receipt = self.restore()
        self.assertEqual("COMMITTED", receipt["state"])
        self.assertFalse(self.file.exists())

    def test_user_added_content_in_new_owned_file_is_never_deleted(self):
        self.file.unlink()
        self.applied()
        self.file.write_text(self.file.read_text() + '\n[owner]\nkeep = "yes"\n')
        before = self.file.read_text()
        _, receipt = self.restore()
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(before, self.file.read_text())

    def job(self, job_id="job-original"):
        request = {"harnessConfigOperationId": "config-original", "harnessOwnerBinding": {"taskId": "task-original", "runId": "run-original", "epoch": 3, "profileDigest": "a" * 64, "endpointLeaseRef": "gateway-one"}}
        job = {"id": job_id, "environmentId": "computer", "worker": "codex_app_server", "status": "queued"}
        self.manager.jobs[job_id] = job
        return job, request

    def test_config_root_lease_spans_queued_running_unknown_job_and_only_real_terminal_cleanup_releases(self):
        self.applied()
        job, request = self.job()
        overrides = self.service.job_environment(job, request, self.manager.environments["computer"])
        self.assertEqual({"CODEX_HOME": str(self.config_root)}, overrides)
        for state in ("queued", "running", "unknown"):
            job["status"] = state
            with self.assertRaises(ConfigError):
                self.restore(operation="restore-" + state)
            self.service.settle_job(job, True)
            self.assertTrue(self.service._active_job(next(iter(self.manager.ledger.execute("SELECT resource_key FROM harness_root_leases")))[0]))
        job["status"] = "completed"
        self.service.settle_job(job, False)
        with self.assertRaises(ConfigError):
            self.restore(operation="restore-no-proof")
        job["processCleanupProof"] = {"processGroupStoppedVerified": True, "source": "controlled_unit_cleanup_fact"}
        self.service.settle_job(job, True)
        _, receipt = self.restore(operation="restore-after-cleanup")
        self.assertEqual("COMMITTED", receipt["state"])

    def test_config_not_permanently_locked_after_apply_and_native_job_binding_is_required(self):
        self.applied()
        second = self.service.plan(self.request(operation="config-second", model="second-model"))
        self.assertEqual("COMMITTED", self.apply(second)["state"])
        job, request = self.job()
        request["harnessConfigOperationId"] = "config-second"
        request.pop("harnessOwnerBinding")
        with self.assertRaises(ConfigError):
            self.service.job_environment(job, request, self.manager.environments["computer"])
        _, restored = self.restore("config-second", "restore-second")
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("original", CodexFields(self.file.read_text()).value["model"])

    def test_external_drift_blocks_next_managed_dispatch(self):
        self.applied()
        job, request = self.job()
        self.file.write_text(self.file.read_text() + '\n# external drift\n')
        with self.assertRaises(ConfigError):
            self.service.job_environment(job, request, self.manager.environments["computer"])
        self.assertTrue(self.service.effective_state(self.binding())["drifted"])

    def test_unknown_shared_consumers_are_read_only(self):
        self.target["scope"] = "USER_CONFIG"
        with self.assertRaises(ConfigError):
            self.service.plan(self.request())

    def test_gateway_credential_is_not_external_group_billing_proof_or_app_owner_authority(self):
        lease = self.lease()
        for changed in ({"audience": "EXTERNAL_GROUP"}, {"purpose": "APP_OWNER"}):
            with self.assertRaises(ConfigError):
                self.service.register_endpoint({**lease, **changed})
        plan = self.service.plan(self.request())
        self.assertFalse(plan["gatewayScope"]["billingBackendVerified"])

    def test_external_comment_inside_owned_provider_block_is_not_deleted_on_restore(self):
        self.applied()
        self.file.write_text(self.file.read_text() + "# owner added inside managed provider block\n")
        external = self.file.read_text()
        _, receipt = self.restore()
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(external, self.file.read_text())

    def test_toml_hash_in_literal_string_is_not_mistaken_for_a_comment(self):
        editor = CodexFields("model = 'old#literal' # real owner comment\n")
        after = editor.set("model", {"value": "new"})
        self.assertEqual("new", CodexFields(after).value["model"])
        self.assertIn("# real owner comment", after)
        self.assertNotIn("#literal", after)

    def assert_owned_processes_stopped(self, marker):
        for pid in marker.read_text().split():
            state = Path("/proc") / pid / "stat"
            if state.exists():
                self.assertEqual("Z", state.read_text().split(") ", 1)[1][0], "our probe child must not remain running")

    def test_version_timeout_kills_only_original_probe_process_group_and_is_bounded(self):
        marker = self.root / "owned-probe-pids"
        self.executable.write_text("#!/bin/sh\necho $$ > " + str(marker) + "\nsleep 60 &\necho $! >> " + str(marker) + "\nwait\n")
        started = time.monotonic()
        with patch("harness_config.PROBE_TIMEOUT", .15):
            with self.assertRaisesRegex(ConfigError, "timed out"):
                self.service.probe(self.binding())
        self.assertLess(time.monotonic() - started, 2.5)
        self.assert_owned_processes_stopped(marker)
        self.assertEqual({}, self.manager.jobs)

    def test_exited_version_leader_with_descendant_holding_stdout_cannot_block_pipe_cleanup(self):
        marker = self.root / "owned-pipe-child"
        self.executable.write_text("#!/bin/sh\nsleep 60 &\necho $! > " + str(marker) + "\nprintf 'codex-cli 0.164.0-alpha.11\\n'\nexit 0\n")
        started = time.monotonic()
        with patch("harness_config.PROBE_TIMEOUT", .15):
            with self.assertRaisesRegex(ConfigError, "timed out"):
                self.service.probe(self.binding())
        self.assertLess(time.monotonic() - started, 2.5)
        self.assert_owned_processes_stopped(marker)

    def test_version_io_releases_original_manager_lock_and_rechecks_changed_generation(self):
        marker, release = self.root / "probe-started", self.root / "release-probe"
        self.executable.write_text("#!/bin/sh\necho started > " + str(marker) + "\nwhile [ ! -f " + str(release) + " ]; do sleep .01; done\nprintf 'codex-cli 0.164.0-alpha.11\\n'\n")
        request, errors = self.binding(), []
        def probe():
            try:
                self.service.probe(request)
            except ConfigError as error:
                errors.append(str(error))
        thread = threading.Thread(target=probe)
        thread.start()
        deadline = time.monotonic() + 2
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertTrue(marker.exists())
        # This lock acquisition must complete while the child is still waiting.
        entered = threading.Event()
        def change():
            with self.manager.lock:
                self.manager.instance_id = "changed-during-probe"
                entered.set()
        modifier = threading.Thread(target=change)
        modifier.start()
        self.assertTrue(entered.wait(.5), "external version IO held the original manager lock")
        release.touch()
        modifier.join(timeout=2)
        thread.join(timeout=3)
        self.assertFalse(thread.is_alive())
        self.assertTrue(errors)
        self.assertEqual({}, self.service.probed_identities)

    def restart(self):
        self.manager.close()
        self.manager = JobManager(self.config, TOKEN)
        self.service = HarnessConfigs(self.manager)
        self.manager.harness_configs = self.service

    def test_cold_restart_explicit_restore_uses_original_committed_no_job_receipt_without_reviving_endpoint(self):
        _, receipt = self.applied()
        self.restart()
        self.assertEqual({}, self.service.endpoints)
        plan, restored = self.restore()
        self.assertEqual("COMMITTED", restored["state"])
        self.assertEqual("config-original", restored["restoreOf"])
        self.assertEqual(receipt, self.service.operation("config-original"))
        self.assertEqual("original-provider", CodexFields(self.file.read_text()).value["model_provider"])
        self.assertEqual({}, self.service.endpoints)
        self.assertEqual(0, self.manager.ledger.execute("SELECT count(*) FROM harness_root_leases").fetchone()[0])

    def test_cold_restart_unknown_active_lease_and_missing_cleanup_proof_stay_blocked(self):
        self.applied()
        job, request = self.job()
        self.service.job_environment(job, request, self.manager.environments["computer"])
        job["status"] = "unknown"
        self.restart()
        with self.assertRaises(ConfigError):
            self.restore()
        # Even a forged CONFIGURED label / empty job pointer is insufficient without
        # the original immutable receipt and actual persisted cleanup proof.
        self.manager.ledger.execute("UPDATE harness_root_leases SET state='CONFIGURED',job_id=NULL,proof='{}'")
        self.manager.ledger.commit()
        with self.assertRaises(ConfigError):
            self.restore(operation="restore-no-proof-cold")

    def test_cold_restart_external_auth_drift_is_preserved_without_restoring_old_key(self):
        self.claude()
        self.applied()
        self.file.write_text(JsonFields(self.file.read_text()).set("env.ANTHROPIC_BASE_URL", {"value": "https://changed-after-restart.invalid"}))
        changed = self.file.read_text()
        self.restart()
        _, receipt = self.restore()
        self.assertEqual("RECOVERY_REQUIRED", receipt["state"])
        self.assertEqual(changed, self.file.read_text())
        self.assertNotIn(ORIGINAL, changed)

    def test_claude_created_env_parent_is_removed_on_clean_restore(self):
        self.claude('{"model": "original", "permissions": {"allow": ["Read"]}}\n')
        self.applied()
        _, receipt = self.restore()
        self.assertEqual("COMMITTED", receipt["state"])
        self.assertNotIn("env", json.loads(self.file.read_text()))


if __name__ == "__main__":
    unittest.main()
