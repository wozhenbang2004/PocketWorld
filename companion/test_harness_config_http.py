"""Real original HTTP handler/supervisor with explicitly fake installed CLI peers."""
import json
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from pocket_companion import CompanionServer, JobManager
from harness_config import HarnessConfigs
from test_codex_driver import FIXTURE
import test_harness_config as controlled


class HarnessConfigurationHttpTests(unittest.TestCase):
    def setUp(self):
        self.fixture = controlled.HarnessConfigurationTests()
        self.fixture.setUp()
        self.manager, self.service = self.fixture.manager, self.fixture.service
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .02}, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.fixture.tearDown()

    def http(self, path, body=None, token=controlled.TOKEN):
        request = Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as failure:
            return failure.code, json.loads(failure.read())

    def result(self, path, body=None):
        status, value = self.http(path, body)
        self.assertEqual(200, status, value)
        return value["result"]

    def prepare(self):
        request = self.fixture.request()
        probe = self.result("/v1/harnesses/probe", self.fixture.binding())
        request["identityDigest"] = probe["identityDigest"]
        return self.result("/v1/harness-config/plans", request)

    def apply(self, plan):
        return self.result("/v1/harness-config/plans/" + plan["planId"] + "/apply", {**self.fixture.binding(), "operationId": plan["operationId"], "planDigest": plan["planDigest"]})

    def test_original_authenticated_api01_to06_real_files_and_immutable_operation(self):
        plan = self.prepare()
        self.assertEqual(self.fixture.initial, self.fixture.file.read_text())
        pending = self.result("/v1/harness-config/operations/" + plan["operationId"])
        self.assertEqual("PREPARED", pending["state"])
        self.assertEqual(plan["planDigest"], pending["plan"]["planDigest"])
        receipt = self.apply(plan)
        self.assertEqual("COMMITTED", receipt["state"])
        self.assertEqual(receipt, self.result("/v1/harness-config/operations/" + plan["operationId"]))
        query = dict(self.fixture.binding())
        bid = query.pop("bindingId")
        state = self.result("/v1/harnesses/" + bid + "/effective-state?" + urlencode(query))
        self.assertTrue(state["effectiveReadback"])
        self.assertFalse(state["runtimeVerified"])
        probe = self.result("/v1/harnesses/probe", self.fixture.binding())
        restore = self.result("/v1/harness-config/operations/" + plan["operationId"] + "/restore-plan", {**self.fixture.binding(), "operationId": "restore-http", "identityDigest": probe["identityDigest"]})
        restored = self.apply(restore)
        self.assertEqual("COMMITTED", restored["state"])
        self.assertTrue(restored["restored"])
        self.assertNotIn("pocketworld", self.fixture.file.read_text())
        self.assertEqual(0, len(self.manager.jobs))

    def test_authentication_closed_request_shape_and_digest_are_enforced_by_original_handler(self):
        self.assertEqual(401, self.http("/v1/harnesses/probe", self.fixture.binding(), "wrong-token")[0])
        plan = self.prepare()
        status, _ = self.http("/v1/harness-config/plans/" + plan["planId"] + "/apply", {**self.fixture.binding(), "operationId": plan["operationId"], "planDigest": "f" * 64})
        self.assertEqual(409, status)
        status, _ = self.http("/v1/harness-config/plans", {**self.fixture.request("new-op"), "path": "/not-allowed"})
        self.assertEqual(400, status)
        self.assertEqual(self.fixture.initial, self.fixture.file.read_text())

    def test_original_job_gets_exact_root_holds_it_until_real_cancel_cleanup_then_restore(self):
        plan = self.prepare()
        self.apply(plan)
        script = self.fixture.root / "original-driver-peer.py"
        capture = self.fixture.workspace / "observed-harness-root"
        script.write_text("import os\nfrom pathlib import Path\nPath(" + repr(str(capture)) + ").write_text(os.environ.get('CODEX_HOME','missing'))\n" + FIXTURE.replace("'requiresOpenaiAuth':True", "'requiresOpenaiAuth':False").replace("{'type':'chatgpt','email':'private-fixture@example.invalid','planType':'plus'}", "None"))
        body = {"operationId": "original-controlled-job", "environmentId": "computer", "environmentBinding": self.fixture.binding()["environmentBinding"],
                "worker": "codex_app_server", "prompt": "Controlled API-only protocol fixture, no model turn", "readOnly": True,
                "harnessConfigOperationId": plan["operationId"], "harnessOwnerBinding": {"taskId": "task-original", "runId": "run-original", "epoch": 3, "profileDigest": "a" * 64, "endpointLeaseRef": "gateway-one"}}
        with patch.object(self.manager, "_launch", return_value=([sys.executable, str(script), "steer-wait"], str(self.fixture.workspace))):
            job = self.result("/v1/jobs", body)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            job = self.result("/v1/jobs/" + job["id"])
            if job["status"] == "running" and job.get("turnId"):
                break
            time.sleep(.02)
        self.assertEqual("running", job["status"], job)
        self.assertEqual(str(self.fixture.config_root), capture.read_text())
        self.assertEqual("ACTIVE", self.manager.ledger.execute("SELECT state FROM harness_root_leases").fetchone()[0])
        probe = self.result("/v1/harnesses/probe", self.fixture.binding())
        restore = self.result("/v1/harness-config/operations/" + plan["operationId"] + "/restore-plan", {**self.fixture.binding(), "operationId": "restore-running-http", "identityDigest": probe["identityDigest"]})
        self.assertEqual(409, self.http("/v1/harness-config/plans/" + restore["planId"] + "/apply", {**self.fixture.binding(), "operationId": restore["operationId"], "planDigest": restore["planDigest"]})[0])
        self.result("/v1/jobs/" + job["id"] + "/cancel", {})
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            job = self.result("/v1/jobs/" + job["id"])
            if job.get("processCleanupProof", {}).get("processGroupStoppedVerified"):
                break
            time.sleep(.02)
        self.assertEqual("cancelled", job["status"], job)
        self.assertTrue(job["processCleanupProof"]["processGroupStoppedVerified"])
        self.assertEqual("CONFIGURED", self.manager.ledger.execute("SELECT state FROM harness_root_leases").fetchone()[0])
        proof = json.loads(self.manager.ledger.execute("SELECT proof FROM harness_root_leases").fetchone()[0])
        self.assertTrue(proof["processCleanupProof"]["processGroupStoppedVerified"])
        original_receipt = self.service.operation(plan["operationId"])
        old_binding = self.fixture.binding()["environmentBinding"]
        self.manager.close()
        self.manager = JobManager(self.fixture.config, controlled.TOKEN)
        self.service = HarnessConfigs(self.manager)
        self.manager.harness_configs = self.service
        self.fixture.manager, self.fixture.service = self.manager, self.service
        self.server.manager = self.manager
        self.assertNotEqual(old_binding, self.fixture.binding()["environmentBinding"])
        self.assertEqual({}, self.service.endpoints)
        probe = self.result("/v1/harnesses/probe", self.fixture.binding())
        cold = self.result("/v1/harness-config/operations/" + plan["operationId"] + "/restore-plan", {**self.fixture.binding(), "operationId": "restore-cold-real-job", "identityDigest": probe["identityDigest"]})
        self.assertEqual("COMMITTED", self.apply(cold)["state"])
        self.assertEqual(original_receipt, self.service.operation(plan["operationId"]))
        self.assertEqual({}, self.service.endpoints)
        self.assertEqual(1, len(self.manager.jobs))

    def test_group_api_only_markers_cannot_inherit_another_effective_home_even_when_null(self):
        self.fixture.applied()
        job, request = self.fixture.job()
        for marker in ({"inferenceAudience": "EXTERNAL_GROUP"}, {"groupInputSource": None}, {"externalGroupApiMode": None}):
            with self.assertRaises(controlled.ConfigError):
                self.service.job_environment(job, {**request, **marker}, self.manager.environments["computer"])


if __name__ == "__main__":
    unittest.main()
