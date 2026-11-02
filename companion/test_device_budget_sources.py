"""Real ephemeral signatures/mTLS and original SQLite PREPARE, no GUI effects."""
import copy
import json
import uuid
import unittest

import delegation_policy
import device_identity as identity
import device_protocol as wire
from device_operation_budget import NativeOperationBudget
import test_desktop_jobs as contracts


class SourceSharePrepareTests(unittest.TestCase):
    setUp = contracts.DesktopOriginalJobIntegrationTests.setUp
    tearDown = contracts.DesktopOriginalJobIntegrationTests.tearDown
    envelope = contracts.DesktopOriginalJobIntegrationTests.envelope

    def forwarded(self):
        self.origin = identity.provision_identity(__import__("pathlib").Path(self.temp.name) / "ephemeral-source-origin")
        self.bindings = [self.binding, {**self.binding, "windowId": 789}]
        self.grant.update(authorizationEpoch=2, allowedOrigins=[self.origin.device_id], allowDelegation=True, maxDelegationDepth=2, resources=self.bindings)
        self.trust.replace_grant(self.grant, self.manager.now())
        self.statement = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="DelegationProof", issuerDeviceId=self.origin.device_id,
                              delegateDeviceId=self.a.device_id, targetResources=[{"targetDeviceId": self.b.device_id, "resourceBinding": binding} for binding in self.bindings], rootTaskId="root-task", parentTaskId="parent-task",
                              grantId="original-A-grant", authorizationEpoch=1, capabilities=["tool.invoke"], toolNames=["desktop.act"],
                              toolBudget=2, modelBudget=0, deadline=self.now + 600000, remainingDepth=0, previousProofDigest="",
                              sourceAllocationId="ba1_original-A-native-reservation",
                              targetShares=[delegation_policy.target_share(self.origin.device_id, "ba1_original-A-native-reservation", self.b.device_id, binding, 1, 0) for binding in self.bindings])
        self.budget = NativeOperationBudget(self.manager)

    def action(self, resource, **updates):
        proof = delegation_policy.sign(self.origin, self.statement)
        return self.envelope("desktop.act", resourceBinding=resource, authorizationEpoch=self.grant["authorizationEpoch"], grantId=self.grant["grantId"],
                             originDeviceId=self.origin.device_id, visitedDevices=[self.origin.device_id, self.a.device_id], delegationChain=[proof],
                             budget={"toolCalls": 1, "modelCalls": 0}, **updates)

    def prepare(self, envelope):
        permission = self.coordinator.check_permission(self.peer_a, envelope, self.manager.now())
        self.assertIsNotNone(permission.source_share)
        operation = envelope.get("operationId")
        from device_protocol import arguments_digest
        with self.manager.lock:
            self.manager.ledger.execute("SAVEPOINT original_prepare")
            try:
                result = self.budget.claim(operation, envelope, permission, self.manager.ledger)
                self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at) VALUES (?,?,?,?,?)",
                                            (operation, arguments_digest(envelope.json()), str(uuid.uuid4()), "queued", self.manager.now()))
                self.manager.ledger.execute("RELEASE SAVEPOINT original_prepare")
                return result
            except BaseException:
                self.manager.ledger.execute("ROLLBACK TO SAVEPOINT original_prepare")
                self.manager.ledger.execute("RELEASE SAVEPOINT original_prepare")
                raise

    def test_A_total_two_is_not_copied_into_both_resource_allocations_or_new_owner_grants(self):
        self.forwarded()
        for resource in self.bindings:
            self.assertIsNotNone(self.prepare(self.action(resource))["originalSourceSharePool"])
        self.assertEqual(2, self.manager.ledger.execute("SELECT SUM(tool_spent) FROM device_budget_source_shares").fetchone()[0])
        for resource in self.bindings:
            with self.assertRaises(Exception):
                self.prepare(self.action(resource))
        # A new local owner grant is NOT a new A source reservation. Re-signing
        # the SAME semantic A proof also must not create a pool from signature bytes.
        self.grant.update(grantId="new-local-owner-grant", authorizationEpoch=3)
        self.trust.replace_grant(self.grant, self.manager.now())
        with self.assertRaises(Exception):
            self.prepare(self.action(self.binding))
        self.assertEqual(2, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(2, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])
        self.assertEqual(2, self.manager.ledger.execute("SELECT SUM(tool_spent) FROM device_budget_source_shares").fetchone()[0])

    def test_source_owner_allocation_and_original_prepare_rollback_together(self):
        self.forwarded()
        self.manager.ledger.execute("CREATE TRIGGER reject_source_prepare BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'controlled original prepare failure'); END")
        self.manager.ledger.commit()
        with self.assertRaises(Exception):
            self.prepare(self.action(self.binding))
        for table in ("operations", "device_budget_spends", "device_budget_source_shares", "device_budget_pools", "device_budget_allocations"):
            self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM " + table).fetchone()[0], table)

    def test_owner_narrowing_five_after_one_to_three_preserves_two_remaining(self):
        native = contracts.DesktopJobs(self.manager, self.adapter)
        first = self.envelope(parameters={"pid": 123, "windowId": 456}, budget={"toolCalls": 5, "modelCalls": 0})
        native.invoke(first, self.coordinator.check_permission(self.peer_a, first, self.manager.now()))
        self.trust.replace_grant({**self.grant, "authorizationEpoch": 2, "toolBudget": 3}, self.manager.now())
        for _ in range(2):
            request = self.envelope(parameters={"pid": 123, "windowId": 456}, authorizationEpoch=2, budget={"toolCalls": 3, "modelCalls": 0})
            native.invoke(request, self.coordinator.check_permission(self.peer_a, request, self.manager.now()))
        exhausted = self.envelope(parameters={"pid": 123, "windowId": 456}, authorizationEpoch=2, budget={"toolCalls": 3, "modelCalls": 0})
        with self.assertRaises(Exception):
            native.invoke(exhausted, self.coordinator.check_permission(self.peer_a, exhausted, self.manager.now()))
        row = self.manager.ledger.execute("SELECT tool_limit,tool_spent,revision,amendment_evidence FROM device_budget_allocations").fetchone()
        self.assertEqual((3, 3, 2), row[:3])
        self.assertEqual("native_permission_monotonic_attenuation", json.loads(row[3])["kind"])
        self.assertEqual(3, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.trust.replace_grant({**self.grant, "grantId": "new-local-grant-not-new-parent-hold", "authorizationEpoch": 3, "toolBudget": 3}, self.manager.now())
        retry = self.envelope(parameters={"pid": 123, "windowId": 456}, grantId="new-local-grant-not-new-parent-hold", authorizationEpoch=3, budget={"toolCalls": 3, "modelCalls": 0})
        with self.assertRaises(Exception):
            native.invoke(retry, self.coordinator.check_permission(self.peer_a, retry, self.manager.now()))
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_allocations").fetchone()[0])
        self.assertEqual(3, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_allocations").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
