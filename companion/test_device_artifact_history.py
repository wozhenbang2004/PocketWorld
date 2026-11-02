"""Actual mTLS identity + original Companion SQLite audience + fixed artifact-only history.

Native source visibility is a revocable fixture; this is not Windows/Android GUI evidence.
"""
import contextvars
import copy
import hashlib
import io
import json
from pathlib import Path
import time
import unittest
from unittest.mock import patch
import device_artifacts as artifacts
import device_pairing as pairing
import pocket_companion
import test_device_pairing as fixture_module
from test_device_artifacts import ref


class ArtifactHistoryTests(unittest.IsolatedAsyncioTestCase):
    async def test_original_sql_history_reads_expired_execution_without_minting_permission(self):
        fixture = fixture_module.DevicePairingTests(); fixture.setUp()
        manager = pocket_companion.JobManager({"stateDir": str(Path(fixture.scratch.name) / "original-history-ledger")}, "test-only-" + "x" * 40)
        try:
            fixture.trust = pairing.DeviceTrust(manager.ledger, manager.lock); fixture.trust.create_schema()
            fixture.coordinator = pairing.PairingCoordinator(fixture.trust, fixture.b.device_id, fixture.b.instance_id)
            grant = fixture.grant(); grant.update(expiresAt=fixture.now + 1000, toolBudget=0, modelBudget=0,
                capabilities=["artifact.describe", "artifact.readChunk"], historyRead={"expiresAt": fixture.now + 60000, "methods": ["artifact.describe", "artifact.readChunk"]})
            fixture.grant = lambda: grant; fixture.pair()
            raw = "原成果完整固定字节".encode(); descriptor = dict(ref(raw), sourceDeviceId=fixture.b.device_id)
            sealed_read = contextvars.ContextVar("native-artifact-history", default=None)
            late = fixture.now + 2000
            def source_current(exact, direction):
                if direction == "publish": return
                native = sealed_read.get()
                if not isinstance(native, pairing.HistoryPermission): raise PermissionError("Expired execution is not a native history read")
                native.check_current(late)
            store = artifacts.ArtifactTransferStore(source_device_id=fixture.b.device_id, current=source_current)
            store.register_stream(io.BytesIO(raw), descriptor)
            audience = dict(kind="artifact", objectId=descriptor["artifactId"], originDeviceId=fixture.a.device_id, callerDeviceId=fixture.a.device_id,
                targetDeviceId=fixture.b.device_id, instanceId=fixture.b.instance_id, rootTaskId="root-1", localTaskId="local-1", taskId="native-receiver-task",
                requestRunId="original-source-run", requestExecutorEpoch=1, grantId="grant-1", authorizationEpoch=1, resourceBinding=fixture.binding,
                acceptedAt=fixture.now, retainedUntil=fixture.now + 60000)
            details = {"acceptedArtifactAudience": audience, "fixedDescriptor": descriptor, "historyVisibilityWithdrawn": False}
            manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)",
                ("native-original-publication", "native-fixed-descriptor", "archived-original-job", "unknown", fixture.now, json.dumps(details)))
            manager.ledger.commit(); manager.jobs.clear()
            queued = []
            def original_audience(query, native_exact):
                row = manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", ("native-original-publication",)).fetchone()
                saved = json.loads(row[0]); fixed = saved["fixedDescriptor"]
                if fixed != native_exact or query.arguments["artifactId"] != fixed["artifactId"]: raise PermissionError("Native accepted descriptor changed")
                return copy.deepcopy(saved["acceptedArtifactAudience"])
            def require_history(native, exact):
                self.assertIsInstance(native, pairing.HistoryPermission); native.check_current(late)
                self.assertEqual(descriptor, exact); self.assertEqual(exact["artifactId"], native.audience["objectId"])
            async def send_history(native, exact, original_read, transfer, offset, actual):
                original_read()
                require_history(native, descriptor); queued.append(actual); return True
            def no_execution(*args): raise AssertionError("History was upgraded to execution")
            endpoint = artifacts.ArtifactEndpoint(store, native_instance_id=fixture.b.instance_id, revision=1, executor_epoch=1,
                access=no_execution, bulk_sender=no_execution, history_access=require_history, history_bulk_sender=send_history, history_audience=original_audience)
            class OriginalSource(pairing.NativeHistoryAudienceSource):
                def accepted_audience(self, query): return endpoint.accepted_history_audience(query)
                def source_visible(self, value, now):
                    row = manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", ("native-original-publication",)).fetchone()
                    return not json.loads(row[0])["historyVisibilityWithdrawn"]
            source = OriginalSource()
            def query(method, **arguments): return fixture.read_query(method=method, deadline=late + 10000, arguments=arguments)
            with patch.object(time, "time_ns", return_value=late * 1000000):
                read = query("artifact.readChunk", artifactId=descriptor["artifactId"], contentDigest=descriptor["sha256"], offset=0, length=len(raw))
                with self.assertRaises(PermissionError): fixture.coordinator.check_permission(fixture.peer_a, read, late)
                native = fixture.coordinator.check_history_permission(fixture.peer_a, read, source, late)
                self.assertNotIsInstance(native, pairing.Permission)
                with self.assertRaises(artifacts.ArtifactError): await endpoint.receive(native)
                token = sealed_read.set(native)
                try:
                    result = await endpoint.receive_history(native)
                    self.assertEqual("ready", result["status"]); self.assertEqual([raw], queued)
                finally: sealed_read.reset(token)
                describe = fixture.coordinator.check_history_permission(fixture.peer_a, query("artifact.describe", artifactId=descriptor["artifactId"]), source, late)
                token = sealed_read.set(describe)
                try:
                    record = await endpoint.receive_history(describe); self.assertEqual(hashlib.sha256(raw).hexdigest(), record["contentDigest"])
                finally: sealed_read.reset(token)
                details["historyVisibilityWithdrawn"] = True
                manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(details), "native-original-publication")); manager.ledger.commit()
                with self.assertRaises(PermissionError): await endpoint.receive_history(native)
                self.assertEqual([raw], queued)
            self.assertEqual(1, manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
            self.assertEqual("unknown", manager.ledger.execute("SELECT last_status FROM operations").fetchone()[0])
        finally:
            manager.close(); fixture.tearDown()
