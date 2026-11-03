"""Original authenticated HTTP / JobManager / native process integration; fixture CLI only."""
import copy
import json
import os
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import cc_switch_import
from pocket_companion import CompanionServer, JobManager
import test_cc_switch_import as controlled
from test_claude_driver import FIXTURE

TOKEN, CREDENTIAL = controlled.TOKEN, controlled.CREDENTIAL


class CCSwitchOriginalJobHttpTests(unittest.TestCase):
    def setUp(self):
        self.fixture = controlled.CCSwitchImportTests()
        self.fixture.setUp()
        self.manager = self.fixture.manager
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .02}, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.fixture.tearDown()

    def http(self, path, body=None, token=TOKEN):
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(),
                          headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as failure:
            return failure.code, json.loads(failure.read())

    def result(self, path, body=None):
        status, value = self.http(path, body)
        self.assertEqual(200, status, value)
        return value["result"]

    def review(self):
        return self.result("/v1/cc-switch/review", self.fixture.selection())

    def body(self, review, operation="original-imported-job", **changes):
        return {"operationId": operation, "environmentId": "computer", "worker": "claude",
                "prompt": "Inspect this fixture only", "environmentBinding": self.manager.environment_binding("computer"),
                "ccSwitchImport": {key: review[key] for key in ("sourceId", "providerId", "configHash")}, **changes}

    def cli(self, mode="success", credential=CREDENTIAL):
        script = self.fixture.root / "original-selected-claude-peer.py"
        capture = self.fixture.workspace / "selected-environment-facts.json"
        prefix = "import json, os, sys\nfrom pathlib import Path\n"
        prefix += "if '--version' not in sys.argv and '--help' not in sys.argv:\n"
        prefix += "    Path(" + repr(str(capture)) + ").write_text(json.dumps({'selectedCredentialDelivered': os.environ.get('ANTHROPIC_API_KEY') == " + repr(credential) + ", 'blockedEnvAbsent': 'UNKNOWN_SECRET' not in os.environ, 'companionTokenAbsent': 'POCKETENGINE_COMPANION_TOKEN' not in os.environ, 'backgroundTasksDisabled': os.environ.get('CLAUDE_CODE_DISABLE_BACKGROUND_TASKS') == '1'}))\n"
        script.write_text(prefix + FIXTURE.replace("'真实 fixture response'", repr("fixture response " + credential)))
        def launch(env, argv):
            self.assertEqual("claude", argv[0])
            return [sys.executable, str(script), mode, *argv[1:]], env["root"]
        return patch.object(self.manager, "_launch", side_effect=launch)

    def finish(self, jobid):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            job = self.result("/v1/jobs/" + jobid)
            if job["status"] in {"completed", "failed", "cancelled", "blocked", "unknown"} and job.get("dispatchFenceFinal"):
                return job
            time.sleep(.02)
        self.fail("Original worker did not settle: " + str(job))

    def clean_environment(self):
        original = {key: value for key, value in os.environ.items() if key not in cc_switch_import.ENVIRONMENT_KEYS}
        original["POCKETENGINE_COMPANION_TOKEN"] = TOKEN
        return patch.dict(os.environ, original, clear=True)

    def test_authenticated_exact_review_is_read_only_and_not_a_job_or_permission(self):
        self.assertEqual(401, self.http("/v1/cc-switch/review", self.fixture.selection(), "wrong-token")[0])
        before = self.fixture.settings.read_bytes(), self.fixture.database.read_bytes()
        review = self.review()
        self.assertFalse(review["runtimeVerified"])
        self.assertNotIn(CREDENTIAL, json.dumps(review))
        self.assertEqual(before, (self.fixture.settings.read_bytes(), self.fixture.database.read_bytes()))
        self.assertEqual({}, self.manager.jobs)
        self.assertEqual(0, self.manager.ledger.execute("SELECT count(*) FROM operations").fetchone()[0])
        self.assertEqual({}, self.manager.processes)
        with patch.object(self.manager.cc_switch_imports, "_read", side_effect=AssertionError("No source read for an invalid request shape")):
            self.assertEqual(400, self.http("/v1/cc-switch/review", {**self.fixture.selection(), "root": "/unapproved"})[0])

    def test_original_job_receipts_persistence_and_cleanup_contain_no_credential(self):
        review = self.review()
        with self.clean_environment(), self.cli():
            job = self.result("/v1/jobs", self.body(review))
            final = self.finish(job["id"])
        self.assertEqual("completed", final["status"], final)
        facts = json.loads((self.fixture.workspace / "selected-environment-facts.json").read_text())
        self.assertTrue(all(facts.values()), facts)
        self.assertEqual("fixture-model", final["ccSwitchModelProvenance"]["effectiveModel"])
        self.assertEqual("configured-sonnet", final["ccSwitchModelProvenance"]["configuredModelSlots"]["default"])
        self.assertFalse(final["ccSwitchModelProvenance"]["inferenceVerified"])
        self.assertFalse(final["ccSwitchImport"]["processEnvironmentDeliveryVerified"])
        self.assertFalse(final["controls"]["threadResume"])
        self.assertTrue(final["processCleanupProof"]["processGroupStoppedVerified"])
        self.assertNotIn(CREDENTIAL, json.dumps(final))
        self.assertNotIn("_ccSwitchImportRequest", final)
        self.assertNotIn(CREDENTIAL, (self.manager.state / ("job-" + job["id"] + ".json")).read_text())
        self.assertEqual({}, self.manager.cc_switch_imports._jobs)
        self.assertNotIn(review["configHash"], self.manager.cc_switch_imports._reviews)
        self.assertEqual(1, self.manager.ledger.execute("SELECT count(*) FROM operations").fetchone()[0])

    def test_escaped_credential_echo_is_redacted_before_json_serialization(self):
        credential = 'fixture-only-"quoted\\credential-雪'
        config = copy.deepcopy(self.fixture.config)
        config["env"]["ANTHROPIC_API_KEY"] = credential
        self.fixture.update_config(config)
        review = self.review()
        with self.clean_environment(), self.cli(credential=credential):
            job = self.result("/v1/jobs", self.body(review))
            final = self.finish(job["id"])
        self.assertEqual("completed", final["status"], final)
        self.assertNotIn(credential, final["output"])
        self.assertIn("[CC Switch credential hidden]", final["output"])
        self.assertNotIn(json.dumps(credential)[1:-1], (self.manager.state / ("job-" + job["id"] + ".json")).read_text())

    def test_original_operation_reconciles_after_source_rotation_without_dispatch_or_review(self):
        review = self.review()
        body = self.body(review)
        with self.clean_environment(), self.cli():
            job = self.result("/v1/jobs", body)
            self.finish(job["id"])
        changed = copy.deepcopy(self.fixture.config)
        changed["env"]["ANTHROPIC_API_KEY"] = "fixture-only-rotated-credential"
        self.fixture.update_config(changed)
        with patch.object(self.manager, "_launch", side_effect=AssertionError("Never replay accepted work")), patch.object(self.manager.cc_switch_imports, "_read", side_effect=AssertionError("No source read on operation reconciliation")):
            self.assertEqual(job["id"], self.result("/v1/jobs", body)["id"])
            self.assertEqual(job["id"], self.result("/v1/operations/" + body["operationId"])["id"])
        self.assertEqual("1", (self.fixture.workspace / "claude-prompt-count").read_text())

    def test_queued_original_pre_spawn_recheck_blocks_changed_database_without_replay(self):
        review = self.review()
        resource = self.manager.resource_id(self.manager._environment("computer"))
        with self.manager.resource_changed:
            self.manager.resources.add(resource)
        try:
            with self.clean_environment(), self.cli():
                job = self.result("/v1/jobs", self.body(review))
                self.assertEqual("queued", job["status"])
                changed = copy.deepcopy(self.fixture.config)
                changed["env"]["ANTHROPIC_API_KEY"] = "fixture-only-changed-while-queued"
                self.fixture.update_config(changed)
                with self.manager.resource_changed:
                    self.manager.resources.discard(resource)
                    self.manager.resource_changed.notify_all()
                final = self.finish(job["id"])
            self.assertEqual("blocked", final["status"], final)
            self.assertFalse((self.fixture.workspace / "selected-environment-facts.json").exists())
            self.assertEqual(job["id"], self.result("/v1/operations/original-imported-job")["id"])
            self.assertEqual({}, self.manager.cc_switch_imports._jobs)
        finally:
            with self.manager.resource_changed:
                self.manager.resources.discard(resource)
                self.manager.resource_changed.notify_all()

    def test_source_definition_change_is_in_original_environment_binding_not_secret_contents(self):
        original = self.manager.environment_binding("computer")
        changed = copy.deepcopy(self.fixture.config)
        changed["env"]["ANTHROPIC_API_KEY"] = "fixture-only-rotated-content"
        self.fixture.update_config(changed)
        self.assertEqual(original, self.manager.environment_binding("computer"))
        self.manager.environments["computer"]["ccSwitchSources"][0]["root"] = str(self.fixture.root / "different-explicit-root")
        self.assertNotEqual(original, self.manager.environment_binding("computer"))

    def test_unknown_review_cannot_reserve_or_launch_an_original_operation(self):
        body = self.body({"sourceId": "selected-source", "providerId": "selected-provider", "configHash": "a" * 64})
        with self.clean_environment(), self.cli():
            self.assertEqual(409, self.http("/v1/jobs", body)[0])
        self.assertEqual("not_found", self.manager.operation(body["operationId"])["status"])
        self.assertFalse((self.fixture.workspace / "selected-environment-facts.json").exists())

    def test_imported_job_cannot_silently_resume_using_an_unreviewed_default_provider(self):
        review = self.review()
        with self.clean_environment(), self.cli():
            job = self.result("/v1/jobs", self.body(review))
            final = self.finish(job["id"])
        body = {"operationId": "unreviewed-resume", "environmentId": "computer", "worker": "claude",
                "prompt": "Must not silently use the default provider", "threadId": final["threadId"], "resumeJobId": job["id"]}
        with patch.object(self.manager, "_launch", side_effect=AssertionError("No unreviewed provider continuation")):
            self.assertEqual(409, self.http("/v1/jobs", body)[0])
        self.assertEqual("not_found", self.manager.operation(body["operationId"])["status"])

    def test_restart_reconciles_original_operation_but_cannot_restore_a_native_review(self):
        review = self.review()
        body = self.body(review)
        with self.clean_environment(), self.cli():
            job = self.result("/v1/jobs", body)
            self.finish(job["id"])
        config = copy.deepcopy(self.manager.config)
        self.manager.close()
        self.manager = JobManager(config, TOKEN)
        self.server.manager = self.manager
        self.fixture.manager = self.manager
        self.fixture.service = self.manager.cc_switch_imports
        with patch.object(self.manager, "_launch", side_effect=AssertionError("Reconciliation is not dispatch")):
            self.assertEqual(job["id"], self.result("/v1/jobs", body)["id"])
        body.update(operationId="cannot-restore-review", environmentBinding=self.manager.environment_binding("computer"))
        with self.clean_environment(), self.cli():
            self.assertEqual(409, self.http("/v1/jobs", body)[0])
        self.assertEqual("not_found", self.manager.operation(body["operationId"])["status"])
        self.assertEqual("1", (self.fixture.workspace / "claude-prompt-count").read_text())

    def test_unknown_native_cleanup_keeps_redaction_until_original_process_is_really_stopped(self):
        review = self.review()
        uncertain = {"processGroupStoppedVerified": False, "observationUnknown": True}
        job = None
        try:
            with self.clean_environment(), self.cli(), patch.object(self.manager, "_terminate", return_value=uncertain):
                job = self.result("/v1/jobs", self.body(review))
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    with self.manager.lock:
                        if self.manager.jobs[job["id"]].get("resourcesHeld") and job["id"] not in self.manager.drivers and getattr(self.manager.processes.get(job["id"]), "_pocket_worker_finished", False):
                            break
                    time.sleep(.01)
                with self.manager.lock:
                    original = self.manager.jobs[job["id"]]
                    self.assertEqual("unknown", original["status"], original)
                    self.assertIn(job["id"], self.manager.cc_switch_imports._jobs)
                    self.manager.cc_switch_imports.close()
                    self.assertNotIn(CREDENTIAL, self.manager.cc_switch_imports.redact("late original output " + CREDENTIAL))
            # Same retained original Popen / resource; real native cleanup does not
            # change unknown business effects into completed or a replayable operation.
            self.manager.cancel(job["id"])
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                final = self.result("/v1/jobs/" + job["id"])
                if final.get("processCleanupProof", {}).get("processGroupStoppedVerified") and not final.get("resourcesHeld"):
                    break
                time.sleep(.01)
            self.assertEqual("unknown", final["status"], final)
            self.assertFalse(final["resourcesHeld"])
            self.assertTrue(final["processCleanupProof"]["processGroupStoppedVerified"])
            self.assertNotIn(job["id"], self.manager.cc_switch_imports._jobs)
        finally:
            if job is not None and job["id"] in self.manager.processes:
                self.manager.cancel(job["id"])

    def test_foreign_inherited_provider_is_blocked_before_original_spawn(self):
        review = self.review()
        with self.clean_environment(), patch.dict(os.environ, {"ANTHROPIC_AUTH_TOKEN": "fixture-only-other-provider"}), self.cli():
            job = self.result("/v1/jobs", self.body(review))
            final = self.finish(job["id"])
        self.assertEqual("blocked", final["status"], final)
        self.assertFalse((self.fixture.workspace / "selected-environment-facts.json").exists())

    def test_ordinary_original_job_has_no_source_reads_or_imported_configuration(self):
        with self.clean_environment(), self.cli(), patch.object(self.manager.cc_switch_imports, "_read", side_effect=AssertionError("No implicit import")):
            job = self.result("/v1/jobs", {"operationId": "ordinary-original", "environmentId": "computer", "worker": "claude", "prompt": "Fixture only"})
            final = self.finish(job["id"])
        self.assertEqual("completed", final["status"], final)
        self.assertNotIn("ccSwitchImport", final)
        self.assertNotIn("ccSwitchModelProvenance", final)


if __name__ == "__main__":
    unittest.main()
