"""Real original SQLite/jobs + actual fixture mTLS identity; fake GUI only.

This establishes integration/authorization/at-most-once contracts on Linux. It
does NOT establish Windows input, screen capture, relay or installation success.
"""
import json
from pathlib import Path
import tempfile
import time
import threading
import unittest
from types import SimpleNamespace

from desktop_adapter import DesktopAdapter, DesktopError
from desktop_jobs import DesktopJobs
from pocket_companion import JobManager
from test_desktop_adapter import ContractDriver, ContractHealth

try:
    import device_identity as identity
    import device_pairing as pairing
    import device_protocol as wire
    from test_device_pairing import tls_peers
    SHARED_CONTRACT = True
except ImportError:
    SHARED_CONTRACT = False


@unittest.skipUnless(SHARED_CONTRACT, "Requires the single identity/protocol domain in the integration tree")
class DesktopOriginalJobIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.manager = JobManager({"stateDir": str(root / "original-state"), "environments": []}, "test-only-local-token-32-characters-000000000")
        self.a = identity.provision_identity(root / "ephemeral-test-a")
        self.b = identity.provision_identity(root / "ephemeral-test-b")
        self.peer_a, peer_b = tls_peers(self.a, self.b)
        self.now = self.manager.now()
        self.binding = {"kind": "desktop", "sessionId": 1, "displayId": "primary", "windowId": 456}
        self.trust = pairing.DeviceTrust(self.manager.ledger, self.manager.lock)
        self.trust.create_schema()
        self.coordinator = pairing.PairingCoordinator(self.trust, self.b.device_id, self.b.instance_id)
        grant = dict(grantId="test-desktop-grant", peerDeviceId=self.a.device_id, localDeviceId=self.b.device_id,
                     authorizationEpoch=1, issuedAt=self.now, expiresAt=self.now + 3600000,
                     capabilities=["tool.invoke", "operation.get"], toolNames=["desktop.observe", "desktop.control", "desktop.act"],
                     resources=[self.binding], allowedOrigins=[self.a.device_id], allowDelegation=False, maxDelegationDepth=0,
                     toolBudget=40, modelBudget=0)
        self.grant = grant
        invitation = self.coordinator.create_invitation(self.b, self.a.certificate_fingerprint, self.now, 120000)
        transcript = pairing.pairing_transcript(invitation, peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
        self.coordinator.complete_pairing(invitation, self.peer_a, pairing.confirm_locally(self.b, transcript, True),
                                          pairing.confirm_locally(self.a, transcript, True).wire(), grant, self.now)
        self.driver, self.health = ContractDriver(), ContractHealth()
        self.adapter = DesktopAdapter(self.manager, self.driver, self.health, lambda raw, metadata, expires: {"artifactId": "fixture", "bytes": len(raw)}, self.b.device_id)
        self.claims = {}
        def claim(operation, envelope, permission, database):
            self.assertIs(database, self.manager.ledger)
            self.claims[operation] = self.claims.get(operation, 0) + 1
            return {"operationId": operation, "reserved": True, "refundOnUnknown": False, "testCallbackOnly": True}
        self.jobs = DesktopJobs(self.manager, self.adapter, budget_claim=claim)

    def tearDown(self):
        self.jobs.close()
        self.manager.close()
        self.temp.cleanup()

    def envelope(self, tool="desktop.observe", parameters=None, **updates):
        effect = "read" if tool == "desktop.observe" else "write"
        value = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="ToolInvocation", method="tool.invoke", requestId="request-" + str(time.monotonic_ns()),
                     rootTaskId="root-task", localTaskId="local-task", parentTaskId="parent-task", runId="original-run", attempt=1,
                     executorEpoch=1, instanceId=self.b.instance_id, originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id,
                     targetDeviceId=self.b.device_id, grantId="test-desktop-grant", authorizationEpoch=1,
                     capabilityVersion="desktop-v2", resourceBinding=self.binding, deadline=self.now + 600000,
                     budget={"toolCalls": 1, "modelCalls": 0}, arguments={"toolName": tool, "effect": effect, "arguments": parameters or {}})
        if effect != "read":
            value["operationId"] = "original-op-" + str(time.monotonic_ns())
        value.update(updates)
        if isinstance(value.get("budget"), dict) and "allocationId" not in value["budget"]:
            value["budget"] = {**value["budget"], "allocationId": wire.allocation_id(value["parentTaskId"], value["targetDeviceId"], value["resourceBinding"], "tool_session")}
        value["argumentsDigest"] = wire.arguments_digest(value)
        return wire.parse_request(json.dumps(value).encode())

    def invoke(self, envelope):
        permission = self.coordinator.check_permission(self.peer_a, envelope, self.manager.now())
        return self.jobs.invoke(envelope, permission)

    def finish(self, job):
        end = time.monotonic() + 2
        while time.monotonic() < end:
            result = self.manager.get(job["id"])
            if result["status"] in {"completed", "failed", "unknown", "cancelled"}:
                return result
            time.sleep(0.005)
        self.fail("Original deterministic job failed to settle")

    def observed_and_lease(self):
        observed = self.invoke(self.envelope(parameters={"pid": 123, "windowId": 456}))
        lease_job = self.finish(self.invoke(self.envelope("desktop.control", {"action": "acquire"})))
        self.assertEqual("completed", lease_job["status"], lease_job)
        return observed, lease_job["desktopReceipt"]["lease"]

    def act_envelope(self, observed, lease):
        return self.envelope("desktop.act", {"kind": "set_text", "lease": lease,
                             "elementToken": observed["state"]["elements"][0]["element_token"], "text": "中文结果",
                             "verification": {"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": "中文结果"}},
                             observationRef=observed["observationRef"])

    def test_requires_real_permission_and_original_budget_callback(self):
        envelope = self.envelope(parameters={"pid": 123, "windowId": 456})
        with self.assertRaises(DesktopError):
            self.jobs.invoke(envelope, SimpleNamespace(check_current=lambda now: None, envelope=envelope))
        permission = self.coordinator.check_permission(self.peer_a, envelope, self.manager.now())
        missing = DesktopJobs(self.manager, self.adapter, budget_claim=False)
        with self.assertRaises(DesktopError):
            missing.invoke(envelope, permission)
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_same_original_operation_and_observation_retries_do_not_charge_or_type_twice(self):
        read = self.envelope(parameters={"pid": 123, "windowId": 456})
        first_read = self.invoke(read)
        self.assertEqual(first_read, self.invoke(read))
        observed, lease = self.observed_and_lease()
        action = self.act_envelope(observed, lease)
        first = self.finish(self.invoke(action))
        second = self.invoke(action)
        self.assertEqual(first["id"], second["id"])
        self.assertEqual("completed", first["status"], first)
        self.assertEqual(1, self.claims[action.get("operationId")])
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))
        changed = action.json(); changed["arguments"]["arguments"]["text"] = "different"
        changed["argumentsDigest"] = wire.arguments_digest(changed)
        with self.assertRaises(DesktopError):
            self.invoke(wire.parse_request(json.dumps(changed).encode()))
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_lost_response_original_job_is_unknown_and_not_refunded_or_replayed(self):
        observed, lease = self.observed_and_lease()
        action = self.act_envelope(observed, lease)
        self.driver.fail_action = True
        result = self.finish(self.invoke(action))
        self.assertEqual("unknown", result["status"], result)
        self.assertTrue(result["desktopDispatchAttempted"])
        self.assertEqual(result["id"], self.invoke(action)["id"])
        self.assertEqual(1, self.claims[action.get("operationId")])
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_single_gui_operation_settlement_is_not_shared_driver_or_os_queue_stop(self):
        observed, lease = self.observed_and_lease()
        read_proof = observed["executionSettlement"]
        self.assertEqual("synchronous_desktop_observation", read_proof["proofScope"])
        self.assertTrue(read_proof["terminalVerified"]); self.assertFalse(read_proof["inputDispatched"])
        action = self.act_envelope(observed, lease)
        result = self.finish(self.invoke(action))
        deadline = time.monotonic() + 2
        while not result.get("executionSettlement") and time.monotonic() < deadline:
            time.sleep(.005); result = self.manager.get(result["id"])
        proof = result["executionSettlement"]
        self.assertEqual("single_gui_operation", proof["proofScope"])
        self.assertEqual(action.get("operationId"), proof["operationId"])
        for key in ("adapterFinished", "noFurtherDispatchForOperation", "driverReplyObserved", "postObservationAvailable", "conditionVerified", "terminalVerified"):
            self.assertTrue(proof[key], key)
        self.assertFalse(proof["osInputQueueDrained"]); self.assertFalse(proof["sharedDriverStopped"])
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (action.get("operationId"),)).fetchone()
        self.assertEqual(proof, json.loads(row[0])["executionSettlement"])
        self.assertFalse(any(name == "stop" for name, arguments in self.driver.calls))

    def test_unknown_gui_adapter_can_end_without_terminal_effect_proof_or_refund(self):
        observed, lease = self.observed_and_lease()
        self.driver.fail_action = True
        action = self.act_envelope(observed, lease); result = self.finish(self.invoke(action))
        deadline = time.monotonic() + 2
        while not result.get("executionSettlement") and time.monotonic() < deadline:
            time.sleep(.005); result = self.manager.get(result["id"])
        self.assertEqual("unknown", result["status"])
        proof = result["executionSettlement"]
        self.assertTrue(proof["adapterFinished"]); self.assertTrue(proof["noFurtherDispatchForOperation"])
        self.assertFalse(proof["terminalVerified"]); self.assertFalse(proof["driverReplyObserved"])
        self.assertFalse(proof["osInputQueueDrained"])
        self.assertEqual(1, self.claims[action.get("operationId")])

    def test_query_requires_current_grant_and_same_task_resource_audience(self):
        observed, lease = self.observed_and_lease()
        action = self.act_envelope(observed, lease)
        job = self.finish(self.invoke(action))
        query = self.envelope(messageType="DeviceRequest", method="operation.get", arguments={"operationId": action.get("operationId")})
        permission = self.coordinator.check_permission(self.peer_a, query, self.manager.now())
        self.assertEqual(job["id"], self.jobs.query(query, permission)["id"])
        wrong = self.envelope(messageType="DeviceRequest", method="operation.get", localTaskId="another-task", arguments={"operationId": action.get("operationId")})
        permission = self.coordinator.check_permission(self.peer_a, wrong, self.manager.now())
        with self.assertRaises(DesktopError):
            self.jobs.query(wrong, permission)
        self.coordinator.revoke_locally(self.a.device_id, self.manager.now())
        with self.assertRaises(PermissionError):
            self.jobs.query(query, self.coordinator.check_permission(self.peer_a, query, self.manager.now()))
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_archived_original_audience_supports_current_read_without_live_gui_or_execution(self):
        observed, lease = self.observed_and_lease()
        action = self.act_envelope(observed, lease)
        job = self.finish(self.invoke(action))
        with self.manager.lock:
            self.manager.jobs.pop(job["id"])
        query = self.envelope(messageType="DeviceRequest", method="operation.get", arguments={"operationId": action.get("operationId")})
        permission = self.coordinator.check_permission(self.peer_a, query, self.manager.now())
        result = DesktopJobs.query_original(self.manager, query, permission)
        self.assertTrue(result["archived"])
        self.assertTrue(result["historicalReadOnly"])
        self.assertEqual("completed", result["lastKnownStatus"])
        self.assertEqual(job["desktopReceipt"], result["desktopReceipt"])
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_task_cancel_does_not_call_global_stop_or_change_expired_shared_lease(self):
        _observed, lease = self.observed_and_lease()
        blocker = threading.Event()
        waiting = [self.manager.pool.submit(blocker.wait) for _ in range(3)]
        stops = []
        original_stop = self.driver.stop
        self.driver.stop = lambda: stops.append(True) or original_stop()
        try:
            control = self.envelope("desktop.control", {"action": "release", "lease": lease})
            job = self.invoke(control)
            self.manager.ledger.execute("UPDATE desktop_resource_leases SET expires=0")
            self.manager.ledger.commit()
            result = self.jobs.cancel(job["id"])
            self.assertEqual("cancelled", result["status"])
            self.assertFalse(result["desktopCleanupProof"]["globalDesktopStopRequested"])
            self.assertEqual([], stops)
            self.assertFalse(self.adapter.paused)
            self.assertEqual("HELD", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
        finally:
            blocker.set()
            for future in waiting: future.result(timeout=2)

    def test_native_spend_is_original_ledger_atomic_and_new_run_root_epoch_do_not_refill_pool(self):
        self.trust.replace_grant({**self.grant, "authorizationEpoch": 2, "toolBudget": 2}, self.manager.now())
        native = DesktopJobs(self.manager, self.adapter)
        def read(**updates):
            return self.envelope(parameters={"pid": 123, "windowId": 456}, authorizationEpoch=updates.pop("authorizationEpoch", 2), budget={"toolCalls": 2, "modelCalls": 0}, **updates)
        first = read()
        permission = self.coordinator.check_permission(self.peer_a, first, self.manager.now())
        one = native.invoke(first, permission)
        self.assertEqual(one, native.invoke(first, permission))
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        second = read(runId="new-run", attempt=2)
        native.invoke(second, self.coordinator.check_permission(self.peer_a, second, self.manager.now()))
        self.assertEqual(2, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        third = read(rootTaskId="fresh-root", localTaskId="fresh-local")
        with self.assertRaises(DesktopError):
            native.invoke(third, self.coordinator.check_permission(self.peer_a, third, self.manager.now()))
        fresh_allocation = read(parentTaskId="fresh-native-parent", rootTaskId="fresh-root", localTaskId="fresh-local")
        with self.assertRaises(DesktopError):
            native.invoke(fresh_allocation, self.coordinator.check_permission(self.peer_a, fresh_allocation, self.manager.now()))
        self.trust.replace_grant({**self.grant, "authorizationEpoch": 3, "toolBudget": 40}, self.manager.now())
        renewed = read(authorizationEpoch=3, rootTaskId="yet-another-root", localTaskId="yet-another-local")
        with self.assertRaises(DesktopError):
            native.invoke(renewed, self.coordinator.check_permission(self.peer_a, renewed, self.manager.now()))
        self.assertEqual(2, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(2, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])

    def test_native_allocation_cannot_change_scope_limit_or_deadline(self):
        native = DesktopJobs(self.manager, self.adapter)
        first = self.envelope(parameters={"pid": 123, "windowId": 456}, budget={"toolCalls": 5, "modelCalls": 0})
        native.invoke(first, self.coordinator.check_permission(self.peer_a, first, self.manager.now()))
        for changes in ({"budget": {"toolCalls": 6, "modelCalls": 0}}, {"deadline": first.get("deadline") + 1}, {"rootTaskId": "different-root"}):
            updates = {"budget": {"toolCalls": 5, "modelCalls": 0}, **changes}
            changed = self.envelope(parameters={"pid": 123, "windowId": 456}, **updates)
            with self.subTest(changes), self.assertRaises(DesktopError):
                native.invoke(changed, self.coordinator.check_permission(self.peer_a, changed, self.manager.now()))
        forged = self.envelope(parameters={"pid": 123, "windowId": 456}, budget={"allocationId": "ba1_" + "0" * 64, "toolCalls": 5, "modelCalls": 0})
        with self.assertRaises(DesktopError):
            native.invoke(forged, self.coordinator.check_permission(self.peer_a, forged, self.manager.now()))
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])

    def test_failed_prepare_rolls_back_native_spend_with_original_operation(self):
        native = DesktopJobs(self.manager, self.adapter)
        envelope = self.envelope("desktop.control", {"action": "acquire"}, budget={"toolCalls": 4, "modelCalls": 0})
        permission = self.coordinator.check_permission(self.peer_a, envelope, self.manager.now())
        # A real SQLite INSERT failure after claim must leave BOTH indices empty.
        self.manager.ledger.execute("CREATE TRIGGER reject_desktop_prepare BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'controlled prepare failure'); END")
        self.manager.ledger.commit()
        with self.assertRaises(Exception):
            native.invoke(envelope, permission)
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_pools").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
