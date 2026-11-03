"""Persisted grant upgrades: real ephemeral mTLS/SQLite, no phone/model execution."""
import asyncio
import base64
import copy
import json
from pathlib import Path
import sqlite3
import threading
from types import SimpleNamespace
import unittest

from cryptography.exceptions import InvalidSignature
import device_pairing as pairing
import device_protocol as wire
import device_transport as transport
from device_companion import DeviceCompanionService
from device_companion_history import CompanionHistory, accepted_audience, operation_receipt_for
from phone_task_bridge import PhoneTaskRouter
from phone_task_usb import attach
import test_device_pairing as fixtures
from test_phone_task_usb import AdbFixture


class PersistedPhoneGrantUpgradeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = fixtures.DevicePairingTests()
        self.fixture.setUp()
        f = self.fixture
        phone_db = sqlite3.connect(Path(f.scratch.name) / "original-phone-operations.sqlite3", check_same_thread=False)
        f.db.backup(phone_db)
        f.db.close()
        f.db = phone_db
        f.trust = pairing.DeviceTrust(f.db, threading.RLock())
        f.coordinator = pairing.PairingCoordinator(f.trust, f.b.device_id, f.b.instance_id)
        invitation, _, _, confirmation, receipt = f.pair(history=True)
        self.path = Path(f.scratch.name) / "original-companion-operations.sqlite3"
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.db.execute("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,job_id TEXT NOT NULL,last_status TEXT NOT NULL,updated_at INTEGER NOT NULL,details TEXT)")
        self.manager = SimpleNamespace(ledger=self.db, lock=threading.RLock(), now=lambda: f.now, closed=False)
        self._open_store()
        reciprocal = copy.deepcopy(f.grant())
        reciprocal.update(grantId="reverse-grant", peerDeviceId=f.b.device_id, localDeviceId=f.a.device_id, allowedOrigins=[f.b.device_id])
        self.owner.accept_pairing_receipt(invitation, f.peer_b, confirmation, receipt, reciprocal, f.now)
        current = f.coordinator.issue_grant_receipt(f.b, "grant-1", f.now)
        self.old = self._signed(current, protocolProfile=pairing.PERSISTED_GRANT_PROFILE)
        original = f.envelope(operationId="original-before-upgrade-op")
        admitted = f.coordinator.check_permission(f.peer_a, original, f.now)
        audience = accepted_audience(original, admitted, original.get("operationId"), f.now)
        f.db.execute("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,job_id TEXT NOT NULL,last_status TEXT NOT NULL,updated_at INTEGER NOT NULL,details TEXT)")
        # A controlled old UNKNOWN result: history may read its original SQL
        # identity but never replay or claim execution succeeded.
        f.db.execute("INSERT INTO operations VALUES(?,?,?,?,?,?)", (original.get("operationId"), original.get("argumentsDigest"),
                     "original-unknown-job", "unknown", f.now, json.dumps({"historyAudience": audience})))
        f.db.commit()
        # Simulate the original release's already-accepted signed SQL row. New
        # network accept deliberately cannot be used to seed this old receipt.
        self._persist(self.old)
        self.before = self._stored()
        self.db.close()
        self.db = sqlite3.connect(self.path, check_same_thread=False)
        self.manager.ledger = self.db
        self._open_store()
        self.peer_a, self.peer_b = fixtures.tls_peers(f.a, f.b)
        self.service = DeviceCompanionService.__new__(DeviceCompanionService)
        self.service.manager, self.service.identity = self.manager, f.a
        self.service.receiver = SimpleNamespace(trust=self.trust, pairing=self.owner)
        self.service.closed, self.service.loop = False, asyncio.get_running_loop()
        self.service.connections, self.service.tasks, self.service.bulk_connections = set(), [], {}
        self.service.phone_tasks = PhoneTaskRouter(self.service)
        self.server, self.direct, self.usb = None, None, None
        self.run_task = None

    def _open_store(self):
        self.trust = pairing.DeviceTrust(self.db, self.manager.lock)
        self.trust.create_schema()
        f = self.fixture
        self.owner = pairing.PairingCoordinator(self.trust, f.a.device_id, f.a.instance_id)

    def _signed(self, receipt, **updates):
        value = copy.deepcopy(receipt)
        value.update(updates)
        value.pop("signature", None)
        value["signature"] = base64.b64encode(self.fixture.b.sign(wire.canonical_bytes(value))).decode()
        return value

    def _persist(self, receipt):
        self.trust.put_outgoing_grant(self.fixture.b.device_id, "grant-1", receipt, self.fixture.now - 1)

    def _stored(self):
        return (self.db.execute("SELECT receipt_json,received_at FROM device_outgoing_grants ORDER BY target_device_id,grant_id").fetchall(),
                self.db.execute("SELECT device_id,instance_id,authorization_epoch,revoked,updated_at FROM device_peers ORDER BY device_id").fetchall())

    async def asyncTearDown(self):
        for connection in (self.direct, self.usb):
            if connection is not None:
                await connection.close()
        tasks = self.service.tasks + ([self.run_task] if self.run_task is not None else [])
        if tasks:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 4)
        if self.server is not None:
            await self.server.close()
        self.db.close()
        self.fixture.tearDown()

    async def test_persisted_old_receipt_connects_without_rewrite_and_keeps_original_scope(self):
        f = self.fixture
        received, arrival, bound = [], asyncio.Event(), asyncio.Event()

        async def receive(peer, frame):
            request = wire.parse_request(frame.control)
            # The receiving owner's original grant admission actually runs. No
            # success receipt or phone Runner is simulated by this fixture.
            permission = f.coordinator.check_permission(peer, request, f.now)
            permission.check_current(f.now)
            received.append(request)
            arrival.set()

        self.server = await transport.listen_lan("127.0.0.1", 0, owner_enabled=True,
            context=transport.native_device_context(f.b, [f.a.certificate_der], server_side=True),
            expected_certificate_der=f.a.certificate_der, lane="control", on_frame=receive)
        port = self.server.sockets[0].getsockname()[1]

        async def connected(connection, peer):
            await self.service._bind(connection, peer, route={"peerDeviceId": f.b.device_id, "grantId": "grant-1", "expiresAt": f.now + 60000})
            bound.set()

        self.direct = await transport.open_lan("127.0.0.1", port,
            context=transport.native_device_context(f.a, [f.b.certificate_der], server_side=False),
            expected_certificate_der=f.b.certificate_der, lane="control", on_frame=lambda peer, frame: None, on_connection=connected)
        self.run_task = asyncio.create_task(self.direct.run())
        await asyncio.wait_for(bound.wait(), 3)
        self.assertIn(self.direct, self.service.connections)
        _, actual_peer, route = self.service.phone_tasks._route(f.b.device_id, "grant-1")
        route.require_target_peer(actual_peer, f.now)
        self.assertEqual(self.old["body"]["grant"], route.grant)
        request = f.envelope()
        self.assertTrue(self.direct.send_control(request._encoded, before_write=lambda peer: route.require_target_peer(peer, f.now)))
        await asyncio.wait_for(arrival.wait(), 3)
        self.assertEqual(["tool.invoke"], [item.method for item in received])
        with self.assertRaises(PermissionError):
            f.coordinator.check_permission(self.peer_a, f.envelope(resourceBinding={"screenMode": "main_screen", "packageNames": ["other.fixture"]}), f.now)
        for method in ("task.answerQuestion", "task.steer", "task.requestResume"):
            args = dict(taskId="original-task", runId="original-run", executorEpoch=1,
                        allocationId="original-allocation", controlLeaseId="original-lease")
            if method == "task.requestResume":
                args["expectedRevision"] = 1
            else:
                args["text"] = "ordinary input"
            if method == "task.answerQuestion":
                args.update(originalRequestId="original-question", waitRevision=1)
            with self.subTest(method=method):
                self.assertNotIn(method, route.grant["capabilities"])
                with self.assertRaises(PermissionError):
                    f.coordinator.check_permission(self.peer_a, f.envelope(messageType="DeviceRequest", method=method, arguments=args), f.now)
        await self.direct.close()
        await asyncio.wait_for(self.run_task, 3)
        adb = AdbFixture(f.scratch.name, port)
        forward = adb.forward()
        self.usb = await attach(self.service, f.b.device_id, "grant-1", forward)
        self.assertIn(self.usb, self.service.connections)
        self.assertIs(self.usb, self.service.phone_tasks._route(f.b.device_id, "grant-1")[0])
        await self.usb.close()
        self.assertTrue(forward.cleanup_confirmed)

        late = self.old["body"]["grant"]["expiresAt"] + 1
        with self.assertRaises(PermissionError):
            self.owner.restore_outgoing_grant(self.peer_b, "grant-1", late)
        history = self.owner.history_route(self.peer_b, "grant-1", late)
        query = history.history_query(f.a, "operation.get", "original-before-upgrade-op", f.binding, late)
        query.require_target_peer(self.peer_b, late)
        self.assertEqual("original-before-upgrade-op", query.envelope.arguments["operationId"])
        self.assertEqual(wire.PROFILE, query.envelope.get("protocolProfile"))
        self.assertIsNone(query.envelope.get("budget"))
        native = SimpleNamespace(ledger=f.db, lock=f.trust.lock, now=lambda: late, jobs={})
        source = CompanionHistory(native)
        permission = f.coordinator.check_history_permission(self.peer_a, query.envelope, source, late)
        permission.check_current(late)
        result = operation_receipt_for(native, f.b.device_id, f.b.instance_id, permission.audience["objectId"], {})
        self.assertEqual("original-before-upgrade-op", result["operationId"])
        self.assertEqual(("unknown", "unknown"), (result["status"], result["effectState"]))
        self.assertEqual(1, f.db.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        with self.assertRaises(PermissionError):
            history.history_query(f.a, "tool.invoke", "original-before-upgrade-op", f.binding, late)
        with self.assertRaises(PermissionError):
            history.history_query(f.a, "operation.get", "original-before-upgrade-op", f.binding, late + 3600000)
        self.assertEqual(self.before, self._stored())

    async def test_restore_rejects_changed_identity_scope_and_signature_while_wire_stays_strict(self):
        f = self.fixture
        for strict in (lambda: pairing._verify_record(self.peer_b, self.old, "PairGrantReceipt"),
                       lambda: pairing._verify_record_certificate(f.b.certificate_der, self.old, "PairGrantReceipt"),
                       lambda: self.owner.accept_outgoing_grant(self.peer_b, self.old, f.now),
                       lambda: self.owner.accept_history_grant_receipt(self.peer_b, self.old, f.now)):
            with self.assertRaises(wire.ProtocolError) as rejected:
                strict()
            self.assertEqual("incompatible_profile", rejected.exception.code)
        old_wire = f.envelope().json()
        old_wire["protocolProfile"] = pairing.PERSISTED_GRANT_PROFILE
        with self.assertRaises(wire.ProtocolError) as rejected:
            wire.parse_request(wire.canonical_bytes(old_wire))
        self.assertEqual("incompatible_profile", rejected.exception.code)
        self.assertEqual(self.before, self._stored())

        variants = []
        variants.append(("unknown-profile", self._signed(self.old, protocolProfile="pwp1_" + "0" * 64)))
        variants.append(("wrong-purpose", self._signed(self.old, messageType="PairingConfirmation")))
        variants.append(("wrong-issuer", self._signed(self.old, issuerDeviceId=f.a.device_id)))
        variants.append(("wrong-certificate", self._signed(self.old, issuerCertificateFingerprint=f.a.certificate_fingerprint)))
        changed = copy.deepcopy(self.old)
        changed["signature"] = base64.b64encode(b"synthetic-invalid-signature").decode()
        variants.append(("bad-signature", changed))
        changed = copy.deepcopy(self.old)
        changed["body"]["grant"]["resources"] = [{"screenMode": "changed-without-signature"}]
        variants.append(("changed-signed-bytes", changed))
        for field, value in (("targetInstanceId", "another-phone-instance"), ("issuedAt", f.now + 1)):
            changed = copy.deepcopy(self.old)
            changed["body"][field] = value
            variants.append((field, self._signed(changed)))
        for method in ("task.answerQuestion", "task.steer", "task.requestResume", "future.ownerControl"):
            changed = copy.deepcopy(self.old)
            changed["body"]["grant"]["capabilities"].append(method)
            variants.append((method, self._signed(changed)))
        changed = copy.deepcopy(self.old)
        changed["body"]["grant"]["grantId"] = "another-grant"
        variants.append(("wrong-grant-id", self._signed(changed)))
        for label, changed in variants:
            with self.subTest(record=label):
                self._persist(changed)
                before = self._stored()
                for restore in (self.owner.restore_outgoing_grant, self.owner.history_route):
                    with self.assertRaises((PermissionError, ValueError, InvalidSignature)):
                        restore(self.peer_b, "grant-1", f.now)
                self.assertEqual(before, self._stored())

        self._persist(self.old)
        route = self.owner.restore_outgoing_grant(self.peer_b, "grant-1", f.now)
        history = self.owner.history_route(self.peer_b, "grant-1", f.now)
        query = history.history_query(f.a, "operation.get", "original-before-upgrade-op", f.binding, f.now)
        with self.assertRaises(PermissionError):
            self.owner.restore_outgoing_grant(self.peer_a, "grant-1", f.now)
        self.db.execute("UPDATE device_peers SET instance_id='changed-after-restore' WHERE device_id=?", (f.b.device_id,))
        self.db.commit()
        for check in (lambda: route.require_target_peer(self.peer_b, f.now), lambda: query.require_target_peer(self.peer_b, f.now),
                      lambda: self.owner.restore_outgoing_grant(self.peer_b, "grant-1", f.now)):
            with self.assertRaises(PermissionError):
                check()
        self.db.execute("UPDATE device_peers SET instance_id=? WHERE device_id=?", (f.b.instance_id, f.b.device_id))
        self.db.commit()
        changed = copy.deepcopy(self.old)
        changed["body"]["grant"]["resources"] = [{"screenMode": "new-owner-scope"}]
        self._persist(self._signed(changed))
        for check in (lambda: route.check_current(f.now), lambda: query.require_target_peer(self.peer_b, f.now)):
            with self.assertRaises(PermissionError):
                check()

        # An explicitly received CURRENT signed receipt still uses the original
        # network acceptance and may refresh the same grant, never this restore.
        self._persist(self.old)
        current = self._signed(self.old, protocolProfile=wire.PROFILE)
        self.owner.accept_outgoing_grant(self.peer_b, current, f.now)
        before = self._stored()
        self.owner.restore_outgoing_grant(self.peer_b, "grant-1", f.now).require_target_peer(self.peer_b, f.now)
        self.assertEqual(before, self._stored())
        self.owner.revoke_locally(f.b.device_id, f.now + 1)
        before = self._stored()
        for check in (lambda: route.check_current(f.now + 1), lambda: query.require_target_peer(self.peer_b, f.now + 1),
                      lambda: self.owner.restore_outgoing_grant(self.peer_b, "grant-1", f.now + 1),
                      lambda: self.owner.history_route(self.peer_b, "grant-1", f.now + 1)):
            with self.assertRaises(PermissionError):
                check()
        self.assertEqual(before, self._stored())


if __name__ == "__main__":
    unittest.main()
