"""Real ephemeral mTLS/SQLite and original file operations; no Windows GUI claim."""
import json
import time
import unittest
from unittest.mock import patch

import device_pairing as pairing
import device_protocol as protocol
from device_companion import DeviceCompanionService, workspace_binding
from pocket_companion import JobManager
import test_device_companion as fixture


class NativeCompanionHistoryTests(unittest.TestCase):
    setUp = fixture.NativeCompanionIntegrationTests.setUp
    tearDown = fixture.NativeCompanionIntegrationTests.tearDown
    request = fixture.NativeCompanionIntegrationTests.request
    receive = fixture.NativeCompanionIntegrationTests.receive
    success = fixture.NativeCompanionIntegrationTests.success
    finished = fixture.NativeCompanionIntegrationTests.finished

    def query(self, operation, **updates):
        value = dict(protocolVersion=protocol.VERSION, protocolProfile=protocol.PROFILE, messageType="DeviceRequest", method="operation.get", requestId="fresh-read",
                     originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id,
                     instanceId=self.b.instance_id, grantId=self.grant["grantId"], authorizationEpoch=self.grant["authorizationEpoch"],
                     resourceBinding=self.binding, deadline=self.manager.now() + 15000, arguments={"operationId": operation})
        value.update(updates)
        return protocol.canonical_bytes(value)

    def expire_execution(self):
        self.grant.update(authorizationEpoch=2, expiresAt=self.now + 1000,
                          historyRead={"expiresAt": self.now + 86400000, "methods": ["operation.get", "artifact.describe", "artifact.readChunk"]})
        self.receiver.trust.replace_grant(self.grant, self.now)

    def cancellation(self, target, operation="cancel-original-one", **updates):
        value = json.loads(self.query(target))
        value.update(method="operation.cancel", operationId=operation)
        value["arguments"]["argumentsDigest"] = self.manager.ledger.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (target,)).fetchone()[0]
        value.update(updates)
        value["argumentsDigest"] = protocol.arguments_digest(value)
        return protocol.canonical_bytes(value)

    def test_cancel_exhausted_budget_stops_original_tree_once_and_has_own_receipt(self):
        self.grant.update(authorizationEpoch=2, toolBudget=1, capabilities=self.grant["capabilities"] + ["operation.cancel"])
        self.receiver.trust.replace_grant(self.grant, self.now)
        request = self.request("command.run", {"argv": ["python", "-c", "import time;print('started',flush=True);time.sleep(30)"]},
                               authorizationEpoch=2, budget={"allocationId": protocol.allocation_id("original-parent", self.b.device_id, self.binding, "tool_session"), "toolCalls": 1, "modelCalls": 0})
        job = self.success(self.receive(request))
        limit = time.monotonic() + 3
        while job["id"] not in self.manager.processes and time.monotonic() < limit:
            time.sleep(.01)
        self.assertIn(job["id"], self.manager.processes)
        raw = self.cancellation(job["operationId"])
        with patch.object(self.manager, "cancel", wraps=self.manager.cancel) as stop:
            result = self.receive(raw)
            repeated = self.receive(raw)
            self.success(result); self.success(repeated)
            self.assertEqual(1, stop.call_count)
        result, repeated = result["result"], repeated["result"]
        self.assertEqual(result["jobId"], repeated["jobId"])
        self.assertEqual("cancel-original-one", result["operationId"])
        self.assertTrue(result["cancellation"]["requested"])
        self.assertTrue(result["cancellation"]["stopped"])
        self.assertEqual({"managementOnly": True}, result["payload"])
        self.assertEqual(json.loads(raw)["arguments"]["argumentsDigest"], result["cancellation"]["targetArgumentsDigest"])
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertEqual(2, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertFalse((self.manager.state / "desktop-local-stop").exists())
        self.assertEqual("cancelled", self.finished(job)["status"])

    def test_cancel_needs_native_current_owner_management_not_history_or_old_execution(self):
        raw = self.request("workspace.write", {"path": "already.txt", "content": "prior effects", "expectedVersion": "absent"})
        self.success(self.receive(raw)); operation = json.loads(raw)["operationId"]
        self.assertEqual("permission_denied", self.receive(self.cancellation(operation))["error"]["code"])
        self.grant.update(authorizationEpoch=2, capabilities=self.grant["capabilities"] + ["operation.cancel"])
        self.receiver.trust.replace_grant(self.grant, self.now)
        # A read retention deadline is not an execution/management deadline.
        detail = json.loads(self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()[0])
        detail["historyAudience"]["retainedUntil"] = self.now
        self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), operation)); self.manager.ledger.commit()
        self.success(self.receive(self.cancellation(operation)))
        self.assertEqual("prior effects", (self.workspace / "already.txt").read_text())
        self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
        self.assertEqual("permission_denied", self.receive(self.cancellation(operation, "new-cancel-after-revoke"))["error"]["code"])

    def test_expired_execution_fresh_read_uses_durable_audience_after_eviction_and_no_budget(self):
        raw = self.request("workspace.write", {"path": "原文件.txt", "content": "保留的中文字节", "expectedVersion": "absent"})
        receipt = self.success(self.receive(raw))
        operation = json.loads(raw)["operationId"]
        before = self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall()
        self.manager.jobs.clear()  # NOT the audience source.
        self.expire_execution()
        with patch.object(self.manager, "now", return_value=self.now + 2000):
            result = self.success(self.receive(self.query(operation)))
            self.assertTrue(result["historicalReadOnly"])
            self.assertEqual(receipt, result["originalFileReceipt"])
            self.assertEqual(1, result["acceptedAuthorizationEpoch"])
            self.assertFalse(result["taskControl"])
            self.assertEqual("permission_denied", self.receive(self.query(operation, authorizationEpoch=1))["error"]["code"])
            self.assertEqual("permission_denied", self.receive(raw)["error"]["code"])
        audience = json.loads(self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()[0])["historyAudience"]
        self.assertEqual("original-run", audience["requestRunId"])
        self.assertNotIn("runId", audience)
        self.assertNotIn("executorEpoch", audience)
        self.assertEqual(before, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall())

    def test_history_exact_scope_visibility_revocation_retention_and_missing_old_rows_deny(self):
        raw = self.request("workspace.write", {"path": "owned.txt", "content": "fixed", "expectedVersion": "absent"})
        self.success(self.receive(raw)); operation = json.loads(raw)["operationId"]
        self.expire_execution()
        with patch.object(self.manager, "now", return_value=self.now + 2000):
            wrong = dict(self.binding, binding="0" * 64)
            self.assertEqual("permission_denied", self.receive(self.query(operation, resourceBinding=wrong))["error"]["code"])
            self.assertEqual("permission_denied", self.receive(self.query(operation, runId="original-run", executorEpoch=1))["error"]["code"])
            row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            detail = json.loads(row[0]); detail["historyVisibilityWithdrawn"] = True
            self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), operation)); self.manager.ledger.commit()
            self.assertEqual("permission_denied", self.receive(self.query(operation))["error"]["code"])
            detail.pop("historyVisibilityWithdrawn"); detail["historyAudience"]["retainedUntil"] = self.now + 1500
            self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), operation)); self.manager.ledger.commit()
            self.assertEqual("permission_denied", self.receive(self.query(operation))["error"]["code"])
            detail.pop("historyAudience")
            self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), operation)); self.manager.ledger.commit()
            self.assertEqual("permission_denied", self.receive(self.query(operation))["error"]["code"])
            self.receiver.pairing.revoke_locally(self.a.device_id, self.now + 2000)
            self.assertEqual("permission_denied", self.receive(self.query(operation))["error"]["code"])
        self.assertEqual(b"fixed", (self.workspace / "owned.txt").read_bytes())

    def test_query_permission_remains_nonexecution_and_seven_day_retention_is_native(self):
        raw = self.request("workspace.write", {"path": "owned.txt", "content": "fixed", "expectedVersion": "absent"})
        self.success(self.receive(raw)); operation = json.loads(raw)["operationId"]
        typed = protocol.parse_request(self.query(operation))
        permission = self.receiver.pairing.check_history_permission(self.peer_a, typed, self.receiver.history, self.manager.now())
        self.assertIsInstance(permission, pairing.HistoryPermission)
        self.assertNotIsInstance(permission, pairing.Permission)
        self.assertEqual(7 * 86400000, permission.audience["retainedUntil"] - permission.audience["acceptedAt"])
        with self.assertRaises(PermissionError):
            self.receiver._current(permission)
        with self.assertRaises(TypeError):
            from device_companion_history import accepted_audience
            accepted_audience(typed, permission, operation, self.manager.now())

    def test_pairing_manager_close_then_new_original_serve_keeps_scope_and_spend_not_generation(self):
        raw = self.request("workspace.write", {"path": "persisted.txt", "content": "重启后读取", "expectedVersion": "absent"})
        self.success(self.receive(raw))
        binding, generation, configuration = self.binding, self.manager.environment_binding("computer"), self.manager.config
        self.manager.close()
        self.manager = JobManager(configuration, "fixture-only-legacy-token-never-device-authority")
        self.service = DeviceCompanionService(self.manager, local_identity=self.b)
        self.manager.device_companion = self.service
        self.receiver = self.service.receiver
        self.assertNotEqual(generation, self.manager.environment_binding("computer"))
        self.assertEqual(binding, workspace_binding(self.manager, "computer"))
        self.assertEqual("重启后读取", self.success(self.receive(self.request("workspace.read", {"path": "persisted.txt"})))["content"])
        self.assertEqual(2, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_allocations").fetchone()[0])

    def test_same_path_new_directory_or_configuration_invalidates_old_grant_without_refill(self):
        self.success(self.receive(self.request("workspace.list")))
        self.manager.environments["computer"]["commandOutputEncoding"] = "cp936"
        self.assertNotEqual(self.binding, workspace_binding(self.manager, "computer"))
        denied = self.receive(self.request("workspace.list"))
        self.assertEqual("permission_denied", denied["error"]["code"])
        self.manager.environments["computer"].pop("commandOutputEncoding")
        self.assertEqual(self.binding, workspace_binding(self.manager, "computer"))
        self.workspace.rename(self.root / "original-workspace-object")
        self.workspace.mkdir()
        self.assertNotEqual(self.binding, workspace_binding(self.manager, "computer"))
        denied = self.receive(self.request("workspace.list"))
        self.assertEqual("DeviceError", denied["messageType"])
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_source_withdrawal_or_origin_removal_cannot_block_current_owner_stop_or_expose_target_content(self):
        self.grant.update(authorizationEpoch=2, toolBudget=1, capabilities=self.grant["capabilities"] + ["operation.cancel"])
        self.receiver.trust.replace_grant(self.grant, self.now)
        raw = self.request("command.run", {"argv": ["python", "-c", "import time;print('PRIVATE_TARGET_BODY_NOT_IN_CANCEL',flush=True);time.sleep(30)"]},
                           authorizationEpoch=2, budget={"allocationId": protocol.allocation_id("original-parent", self.b.device_id, self.binding, "tool_session"), "toolCalls": 1, "modelCalls": 0})
        job = self.success(self.receive(raw)); target = job["operationId"]
        end = time.monotonic() + 3
        while job["id"] not in self.manager.processes and time.monotonic() < end:
            time.sleep(.01)
        self.assertIn(job["id"], self.manager.processes)
        row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (target,)).fetchone()
        detail = json.loads(row[0]); detail["historyVisibilityWithdrawn"] = True
        self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), target)); self.manager.ledger.commit()
        self.grant.update(authorizationEpoch=3, allowedOrigins=[self.b.device_id])  # Original A no longer allowed.
        self.receiver.trust.replace_grant(self.grant, self.manager.now())
        wrong = json.loads(self.cancellation(target, "bad-target-digest")); wrong["arguments"]["argumentsDigest"] = "0" * 64
        wrong["argumentsDigest"] = protocol.arguments_digest(wrong)
        self.assertEqual("permission_denied", self.receive(protocol.canonical_bytes(wrong))["error"]["code"])
        self.assertEqual("permission_denied", self.receive(self.query(target))["error"]["code"])
        response = self.receive(self.cancellation(target)); self.success(response)
        self.assertTrue(response["result"]["cancellation"]["requested"])
        self.assertEqual({"managementOnly": True}, response["result"]["payload"])
        self.assertNotIn("PRIVATE_TARGET_BODY_NOT_IN_CANCEL", protocol.canonical_bytes(response).decode())
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        # Read just the NEW caller's management receipt under a new explicit
        # current owner read policy; the original target stays withdrawn.
        self.grant.update(authorizationEpoch=4, allowedOrigins=[self.a.device_id],
                          historyRead={"expiresAt": self.now + 3600000, "methods": ["operation.get"]})
        self.receiver.trust.replace_grant(self.grant, self.manager.now())
        receipt = self.receive(self.query("cancel-original-one")); self.success(receipt)
        self.assertEqual({"managementOnly": True}, receipt["result"]["payload"])
        self.assertIn("cancellation", receipt["result"])
        self.assertEqual("permission_denied", self.receive(self.query(target))["error"]["code"])


if __name__ == "__main__":
    unittest.main()
