"""Dual identity: real original Companion registry/config SQL and isolated files only."""
import copy
import unittest
from test_harness_config import HarnessConfigurationTests, ACCESS
from harness_config import ConfigError
from harness_gateway import BridgeError

class HarnessGatewayConfigurationOwnerTests(unittest.TestCase):
    setUp = HarnessConfigurationTests.setUp
    tearDown = HarnessConfigurationTests.tearDown
    binding = HarnessConfigurationTests.binding
    apply = HarnessConfigurationTests.apply

    def register(self):
        owner = {"taskId": "original-child", "runId": "original-child-run", "epoch": 0,
                 "parentTaskId": "original-parent", "parentRunId": "original-parent-run", "parentEpoch": 5, "sourceDigest": "c" * 64}
        lease = {"leaseId": "original-parent-lease", "clientBindingId": "original-cli", "generation": "original-generation",
                 **self.binding(), "profileDigest": "a" * 64, "taskId": owner["parentTaskId"], "runId": owner["parentRunId"], "epoch": 5,
                 "audience": "OWNER_PRIVATE", "purpose": "EXTERNAL_RELAY", "expiresAt": self.manager.now() + 60000,
                 "accessToken": ACCESS, "protocols": ["openai_responses"], "configurationOwner": owner}
        self.manager.harness_gateway.register(lease, "http://127.0.0.1:43117")
        probe = self.service.probe(self.binding())
        request = {**self.binding(), "operationId": "original-child-config", "identityDigest": probe["identityDigest"],
                   "profileDigest": lease["profileDigest"], "endpointLeaseRef": lease["leaseId"], "model": "fixture-model",
                   **{k: owner[k] for k in ("taskId", "runId", "epoch")},
                   "gatewayOwnerBinding": owner | {"leaseId": lease["leaseId"], "generation": lease["generation"]}}
        return lease, request

    def test_registered_parent_child_plan_apply_and_exact_job_bind_use_original_sql(self):
        lease, request = self.register(); plan = self.service.plan(request)
        self.assertEqual(request["gatewayOwnerBinding"], plan["gatewayOwnerBinding"])
        self.assertEqual("COMMITTED", self.apply(plan)["state"])
        job = {"id": "exact-controlled-job", "environmentId": "computer", "worker": "codex_app_server"}
        owner = {k: request[k] for k in ("taskId", "runId", "epoch", "profileDigest", "endpointLeaseRef")}
        overrides = self.service.job_environment(job, {"harnessConfigOperationId": request["operationId"], "harnessOwnerBinding": owner}, self.config["environments"][0])
        self.assertEqual(str(self.config_root), overrides["CODEX_HOME"])
        self.assertEqual(job["id"], self.manager.harness_gateway.leases[lease["leaseId"]]["jobId"])
        self.assertEqual((job["id"], "ACTIVE"), self.service.db.execute("SELECT job_id,state FROM harness_root_leases").fetchone())
        self.assertNotIn(ACCESS, str(list(self.manager.ledger.execute("SELECT descriptor FROM gateway_leases"))))

    def test_forged_parent_child_generation_epoch_source_do_not_become_lease_authority(self):
        _, request = self.register()
        for key, value in (("parentTaskId", "different-parent"), ("parentEpoch", 6), ("runId", "different-child-run"), ("generation", "different-generation"), ("sourceDigest", "f" * 64)):
            changed = copy.deepcopy(request); changed["gatewayOwnerBinding"][key] = value
            with self.assertRaises(ConfigError, msg=key): self.service.plan(changed)
        with self.assertRaises(ConfigError): self.service.plan(request | {"epoch": 4})
        self.assertEqual(self.initial, self.file.read_text())
        self.assertEqual(0, self.service.db.execute("SELECT count(*) FROM harness_plans").fetchone()[0])

    def test_registration_cannot_lie_about_original_parent_epoch(self):
        lease, _ = self.register(); changed = copy.deepcopy(lease)
        changed["leaseId"] = "forged-lease"; changed["configurationOwner"]["parentEpoch"] += 1
        with self.assertRaises(BridgeError): self.manager.harness_gateway.register(changed, "http://127.0.0.1:43117")
        self.assertEqual(1, self.manager.ledger.execute("SELECT count(*) FROM gateway_leases").fetchone()[0])

    def test_revocation_blocks_plan_apply_retaining_original_receipt(self):
        lease, request = self.register(); plan = self.service.plan(request)
        self.manager.harness_gateway.cancel(lease["leaseId"], {"generation": lease["generation"], "operationId": "original-stop"})
        with self.assertRaises(ConfigError): self.apply(plan)
        with self.assertRaises(ConfigError): self.service.plan(request | {"operationId": "another-plan"})
        self.assertEqual(self.initial, self.file.read_text())
        self.assertEqual(1, self.service.db.execute("SELECT count(*) FROM harness_plans").fetchone()[0])

    def test_old_child_owner_cannot_migrate_to_new_child_epoch_or_other_job(self):
        lease, request = self.register(); changed = request | {"runId": "new-child-run", "epoch": 4}
        job = {"id": "new-job", "environmentId": "computer"}
        with self.assertRaises(BridgeError): self.manager.harness_gateway.bind_job(lease["leaseId"], job, changed)
        self.assertNotIn("jobId", self.manager.harness_gateway.leases[lease["leaseId"]])
        self.assertTrue(self.manager.harness_gateway.bind_job(lease["leaseId"], job, request))
        with self.assertRaises(BridgeError): self.manager.harness_gateway.bind_job(lease["leaseId"], job | {"id": "different-job"}, request)

    def test_missing_registered_child_binding_and_legacy_lease_remain_strict(self):
        lease, request = self.register(); changed = copy.deepcopy(request); changed.pop("gatewayOwnerBinding")
        with self.assertRaises(ConfigError): self.service.plan(changed)
        self.manager.harness_gateway.cancel(lease["leaseId"], {"generation": lease["generation"], "operationId": "stop-before-legacy"})
        legacy = HarnessConfigurationTests.lease(self)
        changed = request | {"endpointLeaseRef": legacy["leaseId"], "taskId": legacy["taskId"], "runId": legacy["runId"], "epoch": legacy["epoch"]}
        with self.assertRaises(ConfigError): self.service.plan(changed)

if __name__ == "__main__": unittest.main()
