"""Actual loopback bootstrap/service handoff; POSIX storage + owner-UI proof fixtures.

These tests do NOT verify physical Windows UI, DACLs, installation or phone/WAN.
The only bypass is explicitly the local physical-dialog fixture; all TLS pins,
signed candidate/completion records, original SQLite and returned bytes are real.
"""
import asyncio
from collections import deque
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import device_identity as identity
import device_pairing as pairing
import device_protocol as protocol
import device_transport as transport
import device_transport_pairing as bootstrap
import desktop_owner_confirmation as owner_ui
from desktop_pairing import PairingOwnerController, PairingLanHost, NativePairingConfig
from device_companion import DeviceCompanionService
from platform_backend import PlatformError, PosixBackend
from pocket_companion import JobManager


class ExplicitPosixConfigIO:
    """Original no-follow POSIX bytes, NOT a fake successful Win32/DACL test."""
    def __init__(self, manager): self.manager = manager
    def read_private_bytes(self, root, name, sid):
        return self.manager.files.raw({"root": root, "kind": "local"}, name)
    def compare_and_swap(self, root, name, before, after, current, *, record_intent):
        record_intent({"fixture": "POSIX_ONLY_NOT_WINDOWS", "phase": "dispatch_intent_persisted"})
        return PosixBackend().compare_and_swap(root, name, before, after, current)


@unittest.skipIf(os.name == "nt", "This suite has explicit POSIX-only config/physical-proof fixtures, not native Windows validation")
class PairingOriginalManagerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pw-owner-pairing-controlled-")
        self.root = Path(self.temp.name); self.workspace = self.root / "workspace"; self.workspace.mkdir()
        self.a = identity.provision_identity(self.root / "ephemeral-a")
        self.b = identity.provision_identity(self.root / "ephemeral-b")
        config = {"stateDir": str(self.root / "jobs"), "devices": {"enabled": False, "identityDir": str(self.b.directory), "routes": []},
                  "desktop": {"enabled": False}, "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace), "writeAllowed": True,
                                                                      "workers": ["command"], "commands": {}}]}
        self.path = self.root / "companion.json"; self.raw = (json.dumps(config) + "\n").encode(); self.path.write_bytes(self.raw)
        self.manager = JobManager(config, "fixture-owner-token-never-device-authority-1234")
        # Run the REAL owner settings transaction/CAS method over explicitly
        # POSIX storage. Nothing here stands in for actual Win32 permission tests.
        self.config_store = object.__new__(NativePairingConfig)
        self.config_store.manager, self.config_store.backend, self.config_store.path = self.manager, ExplicitPosixConfigIO(self.manager), self.path
        self.config_store.raw, self.config_store.value, self.config_store.sid = self.raw, copy.deepcopy(config), "not-a-Windows-SID-fixture"
        self.config_store.current_installation = lambda: None
        self.controller = PairingOwnerController(self.manager, self.b, self.config_store)
        self.host = PairingLanHost(self.controller)
        self.limits = patch.multiple(bootstrap, _STARTS=deque(), _ACTIVE=0); self.limits.start()
        self.adb_path = self.root / "a-original-trust.db"
        self.adb = sqlite3.connect(self.adb_path, check_same_thread=False)
        self.atrust = pairing.DeviceTrust(self.adb, threading.RLock()); self.atrust.create_schema()
        self.acoordinator = pairing.PairingCoordinator(self.atrust, self.a.device_id, self.a.instance_id)

    def tearDown(self):
        self.host.close(); self.controller.close()
        self.manager.close(); self.adb.close(); self.limits.stop(); self.temp.cleanup()

    def offer(self):
        invitation = self.host.start("127.0.0.1", control_port=0, bulk_port=0)
        self.invitation = invitation
        now = self.manager.now()
        self.reciprocal = {"grantId": "controlled-A-native-owner-grant", "peerDeviceId": self.b.device_id, "localDeviceId": self.a.device_id,
                           "authorizationEpoch": 1, "issuedAt": now, "expiresAt": now + 600000, "capabilities": ["operation.get"], "toolNames": [],
                           "resources": [{"kind": "fixture_readonly_metadata"}], "allowedOrigins": [self.b.device_id], "allowDelegation": False,
                           "maxDelegationDepth": 0, "toolBudget": 0, "modelBudget": 0}
        self.native_offer = None
        def producer(actual_target):
            self.assertIsInstance(actual_target, identity.VerifiedPairingTarget)
            self.native_offer = self.acoordinator.prepare_candidate_offer(self.a, invitation, actual_target, True, self.manager.now(), reviewed_owner_grant=self.reciprocal)
            return self.native_offer.wire_bytes()
        receipt = asyncio.run(bootstrap.open_candidate_lan("127.0.0.1", invitation["connectionHint"]["candidatePort"],
                             context=bootstrap.candidate_client_context(self.b.certificate_der), exact_invitation_certificate_der=self.b.certificate_der,
                             invitation_expires_at_ms=invitation["expiresAt"], offer_factory=producer))
        self.assertEqual("pending_owner_confirmation", pairing.verify_candidate_receipt(self.native_offer, receipt)["state"])
        self.assertIsNone(self.controller.receiver.trust.peer(self.a.device_id))
        return self.controller.coordinator.pending_candidates(self.manager.now())[0]

    def proof(self, review, purpose="device_pairing"):
        return owner_ui.PhysicalOwnerConfirmation(owner_ui._LOCAL_UI, self.manager.now(), "a" * 64, 8, purpose,
                                                  hashlib.sha256(review.summary.encode()).hexdigest())

    def complete(self, review, *, save_on_a=True):
        self.host.confirm(review, self.proof(review))
        self.assertIsNone(self.controller.receiver.trust.peer(self.a.device_id))
        def request(actual):
            self.assertIsInstance(actual, identity.AuthenticatedPeer)
            return protocol.canonical_bytes(pairing.candidate_finalize_request(self.native_offer))
        def accepted(actual, raw):
            self.frozen_completion = raw
            if save_on_a:
                self.route = self.acoordinator.accept_candidate_completion(self.native_offer, actual, raw, self.reciprocal, self.manager.now())
        return asyncio.run(bootstrap.open_completion_lan("127.0.0.1", self.invitation["connectionHint"]["completionPort"],
                           context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.b.certificate_der,
                           invitation_expires_at_ms=self.invitation["expiresAt"], request_factory=request, completion_handler=accepted))

    def test_actual_first_candidate_native_scope_review_mtls_same_socket_service_and_real_file(self):
        candidate = self.offer()
        review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        with self.assertRaises(PlatformError): self.controller.confirm(review, {"ownerConfirmed": True})
        with self.assertRaises(PlatformError): self.controller.confirm(review, self.proof(review, "desktop_recovery"))
        self.complete(review)
        self.assertTrue(self.controller.completed)
        self.assertTrue(self.controller.settings_receipt["settingsVerified"])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        old_instance = self.manager.instance_id
        reservations = self.host.business_reservations()
        self.controller.close()
        service = DeviceCompanionService(self.manager, local_identity=self.b, native_lan_reservations=reservations)
        self.manager.device_companion = service; service.start(); self.host.close()
        self.assertEqual(old_instance, self.manager.instance_id)
        ports = {item["lane"]: item["port"] for item in service.public_status()["lanes"]}
        self.assertEqual(self.invitation["connectionHint"]["controlPort"], ports["control"])
        self.assertEqual(self.invitation["connectionHint"]["bulkPort"], ports["bulk"])
        self.assertFalse(any(item.is_reserved for item in reservations.values()))
        (self.workspace / "中文结果.txt").write_bytes("无Codex首次连接 ✅\n".encode())
        async def read():
            pending, online = {}, asyncio.Event()
            actual = None
            async def connected(connection, peer):
                nonlocal actual
                actual = peer; online.set()
            async def response(peer, frame):
                value = json.loads(frame.control); protocol.validate_message(value)
                pending.pop(value["requestId"]).set_result(value)
            connection = await transport.open_lan("127.0.0.1", ports["control"], context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False),
                                                  expected_certificate_der=self.b.certificate_der, lane="control", on_frame=response, on_connection=connected)
            task = asyncio.create_task(connection.run())
            async def send(value):
                future = asyncio.get_running_loop().create_future(); pending[value["requestId"]] = future
                self.assertTrue(connection.send_control(protocol.canonical_bytes(value)))
                result = await asyncio.wait_for(future, 5)
                self.assertEqual("DeviceResponse", result["messageType"], result)
                return result["result"]
            try:
                await asyncio.wait_for(online.wait(), 5)
                query = self.acoordinator.discovery_query(self.a, actual, self.manager.now())
                description = await send(query.envelope.json())
                self.assertEqual("DeviceDescription", description["messageType"])
                self.assertFalse(description["executionAuthorized"])
                binding = description["capabilities"][0]["resources"][0]
                self.route.require_target_peer(actual, self.manager.now())
                value = dict(protocolVersion=protocol.VERSION, protocolProfile=protocol.PROFILE, messageType="ToolInvocation", method="tool.invoke", requestId="controlled-first-read",
                             rootTaskId="actual-source-root-correlation", parentTaskId="actual-source-parent-correlation", localTaskId="actual-source-local-correlation", runId="actual-source-run-correlation",
                             attempt=1, executorEpoch=1, originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id,
                             instanceId=self.b.instance_id, grantId=self.route.grant_id, authorizationEpoch=self.route.authorization_epoch,
                             capabilityVersion="workspace-v1", resourceBinding=binding, deadline=self.manager.now() + 60000,
                             budget={"allocationId": protocol.allocation_id("actual-source-parent-correlation", self.b.device_id, binding, "tool_session"), "toolCalls": 2, "modelCalls": 0},
                             arguments={"toolName": "workspace.read", "effect": "read", "arguments": {"path": "中文结果.txt"}})
                value["argumentsDigest"] = protocol.arguments_digest(value)
                result = await send(value)
                self.assertEqual("OperationReceipt", result["messageType"])
                self.assertNotIn("runId", result); self.assertNotIn("executorEpoch", result)
                self.assertEqual("无Codex首次连接 ✅\n", result["payload"]["content"])
            finally:
                await connection.close(); await task
        asyncio.run(read())
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_config_or_native_scope_change_after_review_rejects_before_native_confirmation(self):
        candidate = self.offer()
        review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        self.manager.environments["computer"]["writeAllowed"] = False
        with self.assertRaises(PlatformError): self.controller.confirm(review, self.proof(review))
        self.assertIsNone(self.controller.pending)
        self.assertIsNone(self.controller.receiver.trust.peer(self.a.device_id))
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_unknown_settings_result_is_not_automatically_repeated_by_duplicate_completion(self):
        candidate = self.offer(); review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        with patch.object(self.config_store, "enable_after_pairing", side_effect=PlatformError("fixture unknown replacement")) as write:
            self.complete(review)
            self.assertTrue(self.controller.completed)
            self.assertIsNotNone(self.controller.error)
            with self.assertRaises(PlatformError): self.host.business_reservations()
            # Native same retained pending + actual mTLS retry may report the
            # committed identity receipt, but may NEVER repeat settings CAS.
            self.complete_again_after_unknown()
            self.assertEqual(1, write.call_count)

    def test_host_closure_waits_original_already_dispatched_settings_worker(self):
        candidate = self.offer(); review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        original = self.config_store.enable_after_pairing
        errors = []
        def paused(*args, **kwargs):
            entered.set()
            if not release.wait(5): raise AssertionError("Controlled original settings worker was not released")
            self.assertFalse(self.manager.closed)
            return original(*args, **kwargs)
        def caller():
            try: self.complete(review)
            except BaseException as error: errors.append(error)  # Channel may close; effects are not replayed.
        def closer():
            try: self.host.close()
            finally: closed.set()
        with patch.object(self.config_store, "enable_after_pairing", side_effect=paused):
            request = threading.Thread(target=caller); request.start()
            self.assertTrue(entered.wait(5))
            close = threading.Thread(target=closer); close.start()
            self.assertFalse(closed.wait(.05))
            self.assertFalse(self.host.closed)
            release.set(); close.join(5); request.join(5)
        self.assertTrue(closed.is_set()); self.assertTrue(self.host.closed)
        self.assertFalse(request.is_alive()); self.assertFalse(self.manager.closed)
        self.assertTrue(self.controller.settings_receipt["settingsVerified"])
        with self.assertRaises(PlatformError): self.host.business_reservations()

    def reopen_b_and_a_original_stores_without_pending_or_offer(self):
        self.host.close(); self.controller.close(); self.manager.close(); self.adb.close()
        self.a = identity.load_existing_identity(self.a.directory); self.b = identity.load_existing_identity(self.b.directory)
        self.adb = sqlite3.connect(self.adb_path, check_same_thread=False)
        self.atrust = pairing.DeviceTrust(self.adb, threading.RLock())
        self.acoordinator = pairing.PairingCoordinator(self.atrust, self.a.device_id, self.a.instance_id)
        raw = self.path.read_bytes(); config = json.loads(raw)
        self.manager = JobManager(config, "fixture-owner-token-never-device-authority-1234")
        self.config_store = object.__new__(NativePairingConfig)
        self.config_store.manager, self.config_store.backend, self.config_store.path = self.manager, ExplicitPosixConfigIO(self.manager), self.path
        self.config_store.raw, self.config_store.value, self.config_store.sid = raw, config, "not-a-Windows-SID-fixture"
        self.config_store.current_installation = lambda: None
        self.controller = PairingOwnerController(self.manager, self.b, self.config_store); self.host = PairingLanHost(self.controller)
        self.native_offer = None

    def test_reopened_original_stores_owner_selects_only_same_immutable_pairing_receipt_over_real_mtls(self):
        candidate = self.offer(); review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        self.complete(review, save_on_a=False)
        self.assertIsNone(self.atrust.peer(self.b.device_id))
        frozen = self.frozen_completion
        self.reopen_b_and_a_original_stores_without_pending_or_offer()
        self.assertIsNone(self.controller.invitation); self.assertIsNone(self.controller.pending)
        self.assertEqual(0, len(self.controller.coordinator.pending_candidates(self.manager.now())))
        rows = self.controller.recovery_candidates(); self.assertEqual(1, len(rows)); self.assertEqual("available", rows[0]["recoveryState"])
        self.assertNotIn("token", json.dumps(rows)); self.assertNotIn("grantReceipt", json.dumps(rows))
        review = self.controller.prepare_recovery_review(rows[0])
        with self.assertRaises(PlatformError): self.controller.confirm_recovery(review, {"ownerConfirmed": True})
        with self.assertRaises(PlatformError): self.controller.confirm_recovery(review, self.proof(review))
        before = self.path.read_bytes()
        count = self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0]
        address = self.host.start_recovery("127.0.0.1", review, self.proof(review, "device_pairing_recovery"))
        restore = self.acoordinator.restore_pairing_offer(self.a, rows[0]["inviteId"], self.manager.now())
        def request(actual):
            restore.require_target_peer(actual, self.manager.now())
            return protocol.canonical_bytes(pairing.candidate_recovery_request(restore))
        def accepted(actual, raw):
            self.assertEqual(frozen, raw)
            self.route = self.acoordinator.accept_recovered_candidate_completion(restore, actual, raw, self.manager.now())
        received = asyncio.run(bootstrap.open_completion_recovery_lan(address[0], address[1],
            context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.b.certificate_der,
            recovery_expires_at_ms=self.host.recovery.expires_at, request_factory=request, completion_handler=accepted))
        self.assertEqual(frozen, received); self.assertTrue(self.controller.recovery_response_prepared)
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(count, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(1, self.adb.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_spends").fetchone()[0])
        self.assertFalse(self.controller.completed); self.assertEqual({}, self.host.reservations)
        with self.assertRaises(PlatformError): self.host.business_reservations()

    def test_reopened_committed_recovery_never_renews_revoked_scope_or_crosses_confirmation_purpose(self):
        candidate = self.offer(); review = self.controller.prepare_review(candidate, ["workspace.computer"], 2, 10)
        self.complete(review, save_on_a=False); self.reopen_b_and_a_original_stores_without_pending_or_offer()
        row = self.controller.recovery_candidates()[0]; review = self.controller.prepare_recovery_review(row)
        self.controller.coordinator.revoke_locally(self.a.device_id, self.manager.now())
        self.assertEqual("revoked", self.controller.recovery_candidates()[0]["recoveryState"])
        with self.assertRaises(PermissionError): self.controller.confirm_recovery(review, self.proof(review, "device_pairing_recovery"))
        self.assertIsNone(self.host.thread)
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])

    def test_uncommitted_candidate_is_not_presented_as_an_original_recovery_after_restart(self):
        self.offer(); before = self.path.read_bytes()
        self.reopen_b_and_a_original_stores_without_pending_or_offer()
        self.assertEqual((), self.controller.recovery_candidates())
        self.assertIsNone(self.controller.receiver.trust.peer(self.a.device_id))
        self.assertEqual(before, self.path.read_bytes())
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertIsNone(self.host.thread)

    def complete_again_after_unknown(self):
        def request(actual): return protocol.canonical_bytes(pairing.candidate_finalize_request(self.native_offer))
        return asyncio.run(bootstrap.open_completion_lan("127.0.0.1", self.invitation["connectionHint"]["completionPort"],
                           context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.b.certificate_der,
                           invitation_expires_at_ms=self.invitation["expiresAt"], request_factory=request, completion_handler=lambda peer, raw: None))


if __name__ == "__main__": unittest.main()
