"""Actual localhost mTLS original-pairing recovery; no real device/account/user trust."""
import asyncio
from collections import deque
from pathlib import Path
import sqlite3
import ssl
import threading
import time
import unittest
from unittest.mock import patch

import device_identity as identity
import device_pairing as pairing
import device_protocol as protocol
import device_transport as transport
import device_transport_pairing as boot
import test_device_pairing as fixtures


class PairingRecoveryTransportTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = fixtures.DevicePairingTests(); self.fixture.setUp()
        self.limits = patch.multiple(boot, _STARTS=deque(), _ACTIVE=0); self.limits.start()
        self.listeners = []; self.offers = []; self.candidates = []
        self.a_path, self.b_path = (Path(self.fixture.scratch.name) / name for name in ("original-a.db", "original-b.db"))
        self.a_db, self.b_db = sqlite3.connect(self.a_path), sqlite3.connect(self.b_path)
        self._owners(create=True)
        self.invitation = self.owner_b.create_invitation(self.fixture.b, self.fixture.a.certificate_fingerprint, self.fixture.now, 120000)

    def _owners(self, create=False):
        self.a_store, self.b_store = pairing.DeviceTrust(self.a_db, threading.RLock()), pairing.DeviceTrust(self.b_db, threading.RLock())
        if create: self.a_store.create_schema(); self.b_store.create_schema()
        self.owner_a = pairing.PairingCoordinator(self.a_store, self.fixture.a.device_id, self.fixture.a.instance_id)
        self.owner_b = pairing.PairingCoordinator(self.b_store, self.fixture.b.device_id, self.fixture.b.instance_id)

    async def asyncTearDown(self):
        for item in self.listeners: await item.close()
        self.a_db.close(); self.b_db.close(); self.fixture.tearDown(); self.limits.stop()

    async def prepare(self):
        def offer(target):
            value = self.owner_a.prepare_candidate_offer(self.fixture.a, self.invitation, target, True, time.time_ns() // 1000000,
                reviewed_owner_grant=self.fixture.reciprocal_grant())
            self.offers.append(value); return value.wire_bytes()
        def candidate(raw):
            value = self.owner_b.inspect_candidate(self.fixture.b, raw, time.time_ns() // 1000000); self.candidates.append(value)
            return protocol.canonical_bytes(self.owner_b.candidate_receipt(value, time.time_ns() // 1000000))
        server = await boot.listen_candidate_lan("127.0.0.1", 0, context=boot.candidate_server_context(self.fixture.b), owner_enabled=True,
            invitation_expires_at_ms=self.invitation["expiresAt"], candidate_handler=candidate); self.listeners.append(server)
        await boot.open_candidate_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=boot.candidate_client_context(self.fixture.b.certificate_der),
            exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=offer)
        pending = self.owner_b.confirm_candidate_locally(self.fixture.b, self.candidates[-1], True, time.time_ns() // 1000000,
            reviewed_owner_grant=self.fixture.grant())
        return self.offers[-1], pending

    async def drop_after_commit(self, offer, pending):
        saved = []
        def commit_then_drop(peer, raw):
            saved.append(protocol.canonical_bytes(self.owner_b.finalize_candidate_pairing(pending, peer, raw, self.fixture.grant(), time.time_ns() // 1000000)))
            raise ConnectionError("Deliberate TEST connection loss AFTER original SQL commit, BEFORE receipt write")
        server = await boot.listen_completion_lan("127.0.0.1", 0, context=transport.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True),
            native_pending=pending, completion_handler=commit_then_drop); self.listeners.append(server)
        with self.assertRaises((EOFError, ConnectionError, ssl.SSLError, transport.TransportError)):
            await boot.open_completion_lan("127.0.0.1", server.sockets[0].getsockname()[1],
                context=transport.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.fixture.b.certificate_der,
                invitation_expires_at_ms=self.invitation["expiresAt"], request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_finalize_request(offer)),
                completion_handler=lambda peer, raw: self.fail("Lost completion unexpectedly reached A acceptance"))
        self.assertEqual(1, len(saved)); self.assertEqual(1, self.b_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
        self.assertEqual(0, self.a_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0]); return saved[0]

    async def test_actual_commit_loss_both_sql_reopen_and_expired_invitation_use_only_typed_read_lane(self):
        offer, pending = await self.prepare()
        cached_commit = protocol.canonical_bytes(pairing.candidate_finalize_request(offer))  # Untrusted cached DTO, NOT a live opaque offer after restart.
        frozen = await self.drop_after_commit(offer, pending)
        late = self.invitation["expiresAt"] + 1
        with patch.object(time, "time_ns", return_value=late * 1000000):
            with self.assertRaises(PermissionError):
                await boot.listen_completion_lan("127.0.0.1", 0, context=transport.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True),
                    native_pending=pending, completion_handler=lambda *args: self.fail("Expired Pending was revived"))
            for server in self.listeners: await server.close()
            self.listeners.clear(); self.a_db.close(); self.b_db.close()
            self.a_db, self.b_db = sqlite3.connect(self.a_path), sqlite3.connect(self.b_path)
            self.fixture.a = identity.load_existing_identity(self.fixture.a.directory); self.fixture.b = identity.load_existing_identity(self.fixture.b.directory); self._owners()
            restore = self.owner_a.restore_pairing_offer(self.fixture.a, self.invitation["inviteId"], late)
            recovery = self.owner_b.committed_pairing_recovery(self.fixture.b, restore.invite_id, restore.candidate_digest, late)
            self.assertNotIsInstance(recovery, pairing.PendingPairing); self.assertNotIsInstance(recovery, pairing.Permission)
            with self.assertRaises(transport.TransportError): await boot.reserve_completion_recovery_lan("127.0.0.1", native_recovery=pending)
            with self.assertRaises(transport.TransportError): await boot.listen_completion_lan("127.0.0.1", 0, context=None, native_pending=recovery, completion_handler=lambda *a: None)
            calls = []
            def read_committed(peer, raw):
                calls.append(peer.device_id); return protocol.canonical_bytes(self.owner_b.recover_candidate_pairing(recovery, peer, raw, late))
            reserved = await boot.reserve_completion_recovery_lan("127.0.0.1", native_recovery=recovery); self.listeners.append(reserved)
            endpoint = reserved.address
            server = await boot.listen_completion_recovery_lan(context=transport.native_device_context(self.fixture.b, [recovery.peer_certificate_der], server_side=True),
                native_recovery=recovery, completion_handler=read_committed, reservation=reserved); self.listeners.append(server)
            self.assertEqual(endpoint, server.sockets[0].getsockname()[:2])
            # Even a real correct A mTLS peer cannot submit COMMIT on the read-only lane.
            with self.assertRaises((EOFError, ConnectionError, ssl.SSLError, transport.TransportError)):
                await boot.open_completion_lan(*endpoint, context=transport.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False),
                    exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=recovery.expires_at,
                    request_factory=lambda peer: cached_commit, completion_handler=lambda *a: self.fail("Commit admitted through recovery"))
            self.assertEqual([], calls)
            def request(peer): restore.require_target_peer(peer, late); return protocol.canonical_bytes(pairing.candidate_recovery_request(restore))
            accepted = []
            def accept(peer, raw): accepted.append(self.owner_a.accept_recovered_candidate_completion(restore, peer, raw, late))
            result = await boot.open_completion_recovery_lan(*endpoint, context=transport.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False),
                exact_invitation_certificate_der=self.fixture.b.certificate_der, recovery_expires_at_ms=recovery.expires_at, request_factory=request, completion_handler=accept)
            self.assertEqual(frozen, result); self.assertEqual([self.fixture.a.device_id], calls); self.assertEqual(1, len(accepted)); accepted[0].check_current(late)
            self.assertEqual(1, self.a_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0]); self.assertEqual(1, self.b_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
            self.assertEqual(frozen, protocol.canonical_bytes(self.b_store.committed_completion(restore.invite_id)))
            self.assertNotIn(self.invitation["token"], "\n".join(self.a_db.iterdump()) + "\n".join(self.b_db.iterdump()))
            self.owner_b.revoke_locally(self.fixture.a.device_id, late)
            with self.assertRaises(PermissionError):
                await boot.listen_completion_recovery_lan("127.0.0.1", 0, context=None, native_recovery=recovery, completion_handler=read_committed)
            self.assertEqual(frozen, protocol.canonical_bytes(self.b_store.committed_completion(restore.invite_id)))

    async def test_uncommitted_expired_candidate_never_obtains_recovery_or_live_pending_listener(self):
        offer, pending = await self.prepare(); late = self.invitation["expiresAt"] + 1
        with patch.object(time, "time_ns", return_value=late * 1000000):
            with self.assertRaises(PermissionError): self.owner_b.committed_pairing_recovery(self.fixture.b, self.invitation["inviteId"], offer.candidate_digest, late)
            with self.assertRaises(PermissionError):
                await boot.listen_completion_lan("127.0.0.1", 0, context=None, native_pending=pending, completion_handler=lambda *a: self.fail("Expired uncommitted candidate admitted"))
        self.assertEqual(0, self.a_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0]); self.assertEqual(0, self.b_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])

    async def test_recovery_revocation_during_async_native_read_drops_receipt_without_rolling_back_commit(self):
        offer, pending = await self.prepare(); frozen = await self.drop_after_commit(offer, pending)
        now = time.time_ns() // 1000000
        restore = self.owner_a.restore_pairing_offer(self.fixture.a, self.invitation["inviteId"], now)
        recovery = self.owner_b.committed_pairing_recovery(self.fixture.b, restore.invite_id, restore.candidate_digest, now)
        entered, release = asyncio.Event(), asyncio.Event(); receipts = []
        async def slow_native_read(peer, raw):
            result = protocol.canonical_bytes(self.owner_b.recover_candidate_pairing(recovery, peer, raw, time.time_ns() // 1000000))
            entered.set(); await release.wait(); return result
        listener = await boot.listen_completion_recovery_lan("127.0.0.1", 0,
            context=transport.native_device_context(self.fixture.b, [recovery.peer_certificate_der], server_side=True), native_recovery=recovery, completion_handler=slow_native_read)
        self.listeners.append(listener)
        async def client():
            return await boot.open_completion_recovery_lan("127.0.0.1", listener.sockets[0].getsockname()[1],
                context=transport.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.fixture.b.certificate_der,
                recovery_expires_at_ms=recovery.expires_at, request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_recovery_request(restore)),
                completion_handler=lambda peer, raw: receipts.append(raw))
        task = asyncio.create_task(client())
        try:
            await asyncio.wait_for(entered.wait(), 3)
            self.owner_b.revoke_locally(self.fixture.a.device_id, time.time_ns() // 1000000); release.set()
            with self.assertRaises((EOFError, ConnectionError, ssl.SSLError, transport.TransportError)): await asyncio.wait_for(task, 3)
            self.assertEqual([], receipts); self.assertEqual(0, self.a_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
            self.assertTrue(self.b_store.grant("grant-1")["revoked"])
            self.assertEqual(frozen, protocol.canonical_bytes(self.b_store.committed_completion(restore.invite_id)))
        finally:
            release.set()
            if not task.done(): task.cancel(); await asyncio.gather(task, return_exceptions=True)
