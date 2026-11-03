"""Real SQLite/file tests for the explicit import leaf; no live CLI/account proof."""
from contextlib import contextmanager
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

from cc_switch_import import CCSwitchImports, ImportErrorState, SCHEMA, source_definitions_identity
from pocket_companion import JobManager

TOKEN = "fixture-only-companion-token-never-a-real-credential"
CREDENTIAL = "fixture-only-selected-provider-credential-never-exported"
OTHER_CREDENTIAL = "fixture-only-unselected-provider-credential"


class CCSwitchImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-cc-switch-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.source = self.root / "explicit-owner-source"
        self.source.mkdir()
        self.settings = self.source / "settings.json"
        self.database = self.source / "cc-switch.db"
        self.settings.write_text(json.dumps({"currentProviderClaude": "selected-provider"}), encoding="utf-8")
        self.config = {"env": {"ANTHROPIC_BASE_URL": "https://relay.invalid/v1",
                               "ANTHROPIC_API_KEY": CREDENTIAL,
                               "ANTHROPIC_DEFAULT_SONNET_MODEL": "configured-sonnet",
                               "ANTHROPIC_DEFAULT_OPUS_MODEL": "configured-opus",
                               "PATH": "/caller/override", "HTTPS_PROXY": "https://private-proxy.invalid",
                               "NODE_OPTIONS": "--require unapproved.js", "UNKNOWN_SECRET": "blocked-secret"},
                       "model": "opus"}
        with sqlite3.connect(self.database) as database:
            database.executescript("CREATE TABLE providers(id TEXT NOT NULL, app_type TEXT NOT NULL, name TEXT NOT NULL, settings_config TEXT NOT NULL, PRIMARY KEY(id,app_type));")
            database.execute("INSERT INTO providers VALUES(?,?,?,?)", ("selected-provider", "claude", "Selected", json.dumps(self.config)))
            database.execute("INSERT INTO providers VALUES(?,?,?,?)", ("other-provider", "claude", "Other", json.dumps({"env": {"ANTHROPIC_API_KEY": OTHER_CREDENTIAL}})))
        self.manager = JobManager({"stateDir": str(self.root / "state"), "environments": [
            {"id": "computer", "kind": "local", "root": str(self.workspace), "workers": ["claude"],
             "ccSwitchSources": [{"id": "selected-source", "root": str(self.source), "schemaRevision": SCHEMA}]}]}, TOKEN)
        self.service = CCSwitchImports(self.manager)

    def tearDown(self):
        self.service.close()
        self.manager.close()
        self.temp.cleanup()

    def selection(self, **changes):
        return {"environmentId": "computer", "environmentBinding": self.manager.environment_binding("computer"),
                "sourceId": "selected-source", "providerId": "selected-provider", **changes}

    def reviewed(self):
        return self.service.review(self.selection())

    def job(self, review, **changes):
        job = {"id": "original-job", "operationId": "original-operation", "environmentId": "computer", "worker": "claude"}
        request = {"operationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"],
                   "environmentBinding": self.manager.environment_binding("computer"),
                   "ccSwitchImport": {key: review[key] for key in ("sourceId", "providerId", "configHash")}, **changes}
        return job, request, self.manager._environment("computer")

    def update_config(self, config):
        with sqlite3.connect(self.database) as database:
            database.execute("UPDATE providers SET settings_config=? WHERE id=? AND app_type='claude'", (json.dumps(config), "selected-provider"))

    def test_no_selection_has_zero_source_reads_and_no_automatic_import(self):
        with patch.object(self.service, "_source", side_effect=AssertionError("source must not be read")):
            self.assertEqual({}, self.service.job_environment({"id": "ordinary-job"}, {}, {}))
        self.assertEqual({}, self.service._reviews)
        with self.assertRaises(ImportErrorState):
            self.service.review({"environmentId": "computer", "environmentBinding": self.manager.environment_binding("computer")})

    def test_real_sqlite_review_and_native_environment_are_read_only_and_private(self):
        original_settings, original_database = self.settings.read_bytes(), self.database.read_bytes()
        files = {path.name for path in self.source.iterdir()}
        review = self.reviewed()
        public = json.dumps(review)
        self.assertNotIn(CREDENTIAL, public)
        self.assertNotIn(OTHER_CREDENTIAL, public)
        self.assertNotIn("blocked-secret", public)
        self.assertEqual("https://relay.invalid/v1", review["endpoint"])
        self.assertEqual({"default": "configured-sonnet", "opus": "configured-opus"}, review["modelSlots"])
        self.assertFalse(review["runtimeVerified"])
        self.assertEqual("configured_unverified", review["modelSource"])
        self.assertEqual("opaque_native_review", review["configHashMeaning"])
        self.assertNotEqual(hashlib.sha256(CREDENTIAL.encode()).hexdigest(), review["configHash"])
        self.assertNotEqual(hashlib.sha256(original_database).hexdigest(), review["configHash"])
        job, request, env = self.job(review)
        environment = self.service.job_environment(job, request, env)
        self.assertEqual(CREDENTIAL, environment["ANTHROPIC_API_KEY"])
        self.assertEqual({"ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL"}, set(environment))
        self.assertEqual(["HTTPS_PROXY", "NODE_OPTIONS", "PATH", "UNKNOWN_SECRET"], review["blockedEnvKeys"])
        self.assertNotIn(CREDENTIAL, json.dumps(job))
        self.assertNotIn(CREDENTIAL, repr(self.service._reviews[review["configHash"]]))
        self.assertFalse(job["ccSwitchImport"]["processEnvironmentDeliveryVerified"])
        self.assertEqual(original_settings, self.settings.read_bytes())
        self.assertEqual(original_database, self.database.read_bytes())
        self.assertEqual(files, {path.name for path in self.source.iterdir()})

    def test_submit_and_pre_spawn_recheck_reject_real_database_change(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        changed = copy.deepcopy(self.config)
        changed["env"]["ANTHROPIC_API_KEY"] = "fixture-only-rotated-provider-credential"
        self.update_config(changed)
        with self.assertRaisesRegex(ImportErrorState, "configuration changed"):
            self.service.job_environment(job, request, env)
        fresh = self.reviewed()
        self.assertNotEqual(review["configHash"], fresh["configHash"])
        self.assertEqual(review["sourceIdentity"], fresh["sourceIdentity"])
        self.assertEqual(review["environmentBinding"], fresh["environmentBinding"])

    def test_same_bytes_replacement_is_a_different_native_source_object(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        replacement = self.source / "replacement.db"
        replacement.write_bytes(self.database.read_bytes())
        replacement.replace(self.database)
        with self.assertRaisesRegex(ImportErrorState, "configuration changed"):
            self.service.job_environment(job, request, env)

    def test_change_during_real_read_is_refused_without_a_review_handle(self):
        original = self.service._files
        changed = copy.deepcopy(self.config)
        changed["env"]["ANTHROPIC_MODEL"] = "changed-during-read"
        @contextmanager
        def racing_read(source):
            with original(source) as (path, identity, read, verify, immutable):
                count = 0
                def raced(name):
                    nonlocal count
                    value = read(name)
                    if name == "cc-switch.db" and count == 0:
                        count += 1
                        self.update_config(changed)
                    return value
                yield path, identity, raced, verify, immutable
        with patch.object(self.service, "_files", racing_read):
            with self.assertRaisesRegex(ImportErrorState, "changed during"):
                self.reviewed()
        self.assertEqual({}, self.service._reviews)

    def test_settings_selection_and_old_environment_binding_cannot_be_reused(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.settings.write_text('{"currentProviderClaude":"other-provider"}')
        with self.assertRaisesRegex(ImportErrorState, "currently selected"):
            self.service.job_environment(job, request, env)
        self.settings.write_text('{"currentProviderClaude":"selected-provider"}')
        self.manager.environments["computer"]["writeAllowed"] = True
        with self.assertRaisesRegex(ImportErrorState, "generation changed"):
            self.service.job_environment(job, request, env)

    def test_source_definition_change_rejects_same_review_even_without_shared_binding_hunk(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        other = self.root / "different-config-root"
        other.mkdir()
        env["ccSwitchSources"][0]["root"] = str(other)
        with self.assertRaisesRegex(ImportErrorState, "source definition"):
            self.service.job_environment(job, request, env)

    def test_unknown_schema_and_provider_view_are_explicit_failures(self):
        self.manager.environments["computer"]["ccSwitchSources"][0]["schemaRevision"] = "unrecognized-upstream-version"
        with self.assertRaisesRegex(ImportErrorState, "schema revision"):
            self.reviewed()
        self.manager.environments["computer"]["ccSwitchSources"][0]["schemaRevision"] = SCHEMA
        with sqlite3.connect(self.database) as database:
            database.execute("ALTER TABLE providers RENAME TO hidden_providers")
            database.execute("CREATE VIEW providers AS SELECT * FROM hidden_providers")
        with self.assertRaisesRegex(ImportErrorState, "table schema"):
            self.reviewed()

    def test_non_string_supported_env_and_duplicate_json_do_not_fall_back(self):
        changed = copy.deepcopy(self.config)
        changed["env"]["ANTHROPIC_API_KEY"] = 42
        self.update_config(changed)
        with self.assertRaises(ImportErrorState):
            self.reviewed()
        self.settings.write_text('{"currentProviderClaude":"other-provider","currentProviderClaude":"selected-provider"}')
        with self.assertRaises(ImportErrorState):
            self.reviewed()
        self.assertEqual({}, self.service._reviews)

    def test_non_utf8_settings_is_refused(self):
        self.settings.write_bytes('{"currentProviderClaude":"selected-provider"}'.encode("utf-16"))
        with self.assertRaises(ImportErrorState):
            self.reviewed()

    def test_credential_in_endpoint_or_label_never_reaches_public_summary(self):
        changed = copy.deepcopy(self.config)
        changed["model"] = CREDENTIAL
        self.update_config(changed)
        review = self.reviewed()
        self.assertNotIn(CREDENTIAL, json.dumps(review))
        self.assertEqual("[CC Switch credential hidden]", review["configuredModel"])
        changed["env"]["ANTHROPIC_BASE_URL"] = "https://username:" + CREDENTIAL + "@relay.invalid/v1"
        self.update_config(changed)
        with self.assertRaises(ImportErrorState) as failure:
            self.reviewed()
        self.assertNotIn(CREDENTIAL, str(failure.exception))

    def test_unknown_env_cannot_be_supplied_through_submit_json(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        request["ccSwitchImport"]["env"] = {"PATH": "/injected"}
        with self.assertRaises(ImportErrorState):
            self.service.job_environment(job, request, env)
        self.assertNotIn("ccSwitchImport", job)

    def test_original_environment_is_preserved_and_conflicting_provider_credentials_refuse(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        original = {"PATH": "/original/cli", "HTTPS_PROXY": "https://original-proxy.invalid"}
        projected = self.service.child_environment(job, request, env, original)
        self.assertEqual("/original/cli", projected["PATH"])
        self.assertEqual(original["HTTPS_PROXY"], projected["HTTPS_PROXY"])
        self.assertEqual(CREDENTIAL, projected["ANTHROPIC_API_KEY"])
        self.assertEqual({"PATH", "HTTPS_PROXY"}, set(original))
        with self.assertRaisesRegex(ImportErrorState, "Inherited Claude provider override"):
            self.service.child_environment(job, request, env, {**original, "ANTHROPIC_AUTH_TOKEN": "fixture-only-different-native-provider"})

    def test_source_binding_identity_has_only_explicit_source_definitions(self):
        env = self.manager._environment("computer")
        original = source_definitions_identity(env)
        changed = copy.deepcopy(self.config)
        changed["env"]["ANTHROPIC_API_KEY"] = "fixture-only-rotated-without-binding-secret-hash"
        self.update_config(changed)
        self.assertEqual(original, source_definitions_identity(env))
        env["ccSwitchSources"][0]["unexpectedCredential"] = CREDENTIAL
        with self.assertRaises(ImportErrorState) as failure:
            source_definitions_identity(env)
        self.assertNotIn(CREDENTIAL, str(failure.exception))

    def test_malformed_review_handle_never_becomes_a_native_cache_lookup(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        request["ccSwitchImport"]["configHash"] = {"fake": "caller-json"}
        with self.assertRaises(ImportErrorState):
            self.service.job_environment(job, request, env)

    def test_submit_read_is_pure_before_original_harness_lease_and_final_spawn_binds_context(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env, bind=False)
        self.assertEqual({}, self.service._jobs)
        self.assertIsNone(self.service.model_provenance(job, "not-an-original-dispatched-session"))
        self.service.child_environment(job, request, env, {})
        self.assertIn(job["id"], self.service._jobs)

    def test_native_cleanup_proof_is_required_before_forgetting_active_credentials(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        job["status"] = "completed"
        self.service.settle_job(job, True)
        self.assertIn(job["id"], self.service._jobs)
        self.assertNotIn(CREDENTIAL, self.service.redact(CREDENTIAL))
        job["processCleanupProof"] = {"processGroupStoppedVerified": True}
        self.service.settle_job(job, True)
        self.assertNotIn(job["id"], self.service._jobs)
        self.assertNotIn(review["configHash"], self.service._reviews)

    def test_close_preserves_unsettled_original_redaction_until_native_cleanup(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        self.service.close()
        self.assertIn(job["id"], self.service._jobs)
        self.assertNotIn(CREDENTIAL, self.service.redact("late original output " + CREDENTIAL))
        self.assertGreaterEqual(self.service.redaction_tail(), len(CREDENTIAL) - 1)
        with self.assertRaises(ImportErrorState):
            self.service.review(self.selection())
        job["status"] = "cancelled"
        self.service.settle_job(job, False)
        self.assertIn(job["id"], self.service._jobs)
        job["processCleanupProof"] = {"processGroupStoppedVerified": True}
        self.service.settle_job(job, True)
        self.assertNotIn(job["id"], self.service._jobs)
        self.assertEqual(0, self.service.redaction_tail())

    def test_cold_json_cannot_restore_native_review_or_import_authority(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        cold_service = CCSwitchImports(self.manager)
        with self.assertRaisesRegex(ImportErrorState, "native review is missing"):
            cold_service.job_environment(job, request, env)
        self.assertEqual({}, cold_service._jobs)

    def test_original_job_operation_worker_and_conflicting_configuration_are_fenced(self):
        review = self.reviewed()
        for change in ({"operationId": "different-operation"}, {"worker": "codex_app_server"},
                       {"harnessConfigOperationId": "other-config"}, {"inferenceAudience": "EXTERNAL_GROUP"},
                       {"groupInputSource": {}}, {"externalGroupApiMode": {}}, {"assignmentWorkspace": {"mode": "original_assignment"}},
                       {"resumeJobId": "old-job"}, {"threadId": "old-session"}):
            with self.subTest(change=change):
                job, request, env = self.job(review, **change)
                with self.assertRaises(ImportErrorState):
                    self.service.job_environment(job, request, env)
                self.assertNotIn("ccSwitchImport", job)

    def test_model_callback_prefers_reported_model_without_claiming_inference_proof(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        facts = self.service.model_provenance(job, "actual-cli-session-model")
        self.assertEqual("actual-cli-session-model", facts["effectiveModel"])
        self.assertEqual("original_cli_session_receipt", facts["modelSource"])
        self.assertEqual("configured-sonnet", facts["configuredModelSlots"]["default"])
        self.assertFalse(facts["configuredModelVerified"])
        self.assertFalse(facts["inferenceVerified"])
        unknown = self.service.model_provenance(job, None)
        self.assertIsNone(unknown["effectiveModel"])
        self.assertEqual("unknown", unknown["modelSource"])

    def test_actual_original_claude_stdio_receipt_preserves_config_model_provenance(self):
        from claude_driver import ClaudeStreamDriver, command
        from test_claude_driver import FIXTURE
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        child = self.service.child_environment(job, request, env,
                {key: value for key, value in os.environ.items() if key not in self.config["env"] and not key.startswith("ANTHROPIC_")})
        fixture = self.root / "original-claude-wire-fixture.py"
        fixture.write_text("import os\nassert os.environ['ANTHROPIC_API_KEY'] == " + repr(CREDENTIAL) + "\n" + FIXTURE)
        session = str(uuid.uuid4())
        process = subprocess.Popen([sys.executable, str(fixture), "success", *command(session)[1:]],
                cwd=self.workspace, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=child)
        states = []
        driver = ClaudeStreamDriver(process, session,
                lambda value: states.append(self.service.model_provenance(job, value.get("reportedModel"))),
                lambda: False, 5, cwd=self.workspace)
        try:
            receipt = driver.run("Explicit fixture-only model source check")
            self.assertTrue(receipt["protocolVerified"])
            self.assertEqual("fixture-model", receipt["reportedModel"])
            provenance = self.service.model_provenance(job, receipt["reportedModel"])
            self.assertEqual("fixture-model", provenance["effectiveModel"])
            self.assertEqual("configured-sonnet", provenance["configuredModelSlots"]["default"])
            self.assertEqual("original_cli_session_receipt", provenance["modelSource"])
            self.assertFalse(provenance["inferenceVerified"])
            self.assertTrue(any(value and value["reportedModel"] == "fixture-model" for value in states))
        finally:
            driver.close_steering()
            process.terminate()
            process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()

    def test_private_credentials_are_redacted_and_retained_for_original_active_job(self):
        review = self.reviewed()
        job, request, env = self.job(review)
        self.service.job_environment(job, request, env)
        self.service._reviews.clear()
        self.assertNotIn(CREDENTIAL, self.service.redact("cli echoed " + CREDENTIAL))
        self.assertEqual(len(CREDENTIAL) - 1, self.service.redaction_tail())
        self.assertNotIn(CREDENTIAL, json.dumps(self.service.model_provenance(job, CREDENTIAL)))
        with self.assertRaisesRegex(ImportErrorState, "lost its native"):
            self.service.job_environment(job, {}, env)

    @unittest.skipIf(os.name == "nt", "POSIX pinned-fd boundary; Windows requires its real native backend")
    def test_symlink_source_files_and_root_are_rejected(self):
        for name in ("settings.json", "cc-switch.db"):
            with self.subTest(name=name):
                original = self.source / name
                hidden = self.root / (name + ".outside")
                original.rename(hidden)
                original.symlink_to(hidden)
                with self.assertRaises(ImportErrorState):
                    self.reviewed()
                original.unlink()
                hidden.rename(original)
        alias = self.root / "source-alias"
        alias.symlink_to(self.source, target_is_directory=True)
        self.manager.environments["computer"]["ccSwitchSources"][0]["root"] = str(alias)
        with self.assertRaises(ImportErrorState):
            self.reviewed()

    @unittest.skipIf(os.name == "nt", "POSIX pinned fd cannot claim a live adjacent WAL snapshot")
    def test_live_wal_is_explicitly_refused_instead_of_reading_stale_main_database(self):
        database = sqlite3.connect(self.database)
        try:
            self.assertEqual("wal", database.execute("PRAGMA journal_mode=WAL").fetchone()[0])
            database.execute("UPDATE providers SET name='changed in live WAL' WHERE id='selected-provider'")
            database.commit()
            with self.assertRaisesRegex(ImportErrorState, "journal requires"):
                self.reviewed()
            self.assertEqual({}, self.service._reviews)
        finally:
            database.close()


if __name__ == "__main__":
    unittest.main()
