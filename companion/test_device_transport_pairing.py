"""Real isolated localhost bootstrap TLS, no real devices or user trust provisioning."""
import asyncio
import hashlib
import json
import sqlite3
import threading
from collections import deque
from unittest.mock import patch
from pathlib import Path
import ssl
import socket
import struct
import sys
import time
import unittest
import uuid

import device_identity as identity
import device_pairing as pairing
import device_protocol as protocol
import device_transport as d
import device_transport_pairing as boot
import test_device_pairing as fixtures
from device_transport_fixtures import certificate, outer_client, outer_server
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))
from pocket_relay import ByteRelay, RouteLease, serve_relay


class PairingTransportTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.limits = patch.multiple(boot, _STARTS=deque(), _ACTIVE=0); self.limits.start()
        self.fixture = fixtures.DevicePairingTests(); self.fixture.setUp()
        self.invitation = self.fixture.coordinator.create_invitation(self.fixture.b, self.fixture.a.certificate_fingerprint, self.fixture.now, 120000)
        self.listeners = []
        self.seen = []
        self.offers = []
        self.candidates = []

    async def asyncTearDown(self):
        for listener in self.listeners:
            result = listener.close()
            if asyncio.iscoroutine(result): await result
            if hasattr(listener, "wait_closed"): await listener.wait_closed()
        await asyncio.sleep(0)
        self.fixture.tearDown()
        self.assertEqual(0, boot._ACTIVE)
        self.limits.stop()

    def offer(self, target):
        self.assertIsInstance(target, identity.VerifiedPairingTarget)
        self.assertNotIsInstance(target, identity.AuthenticatedPeer)
        self.assertEqual(self.fixture.b.device_id, target.device_id)
        offer = pairing.create_candidate_offer(self.fixture.a, self.invitation, target, True, time.time_ns() // 1_000_000,
            reviewed_owner_grant=self.fixture.reciprocal_grant())
        self.offers.append(offer)
        return offer.wire_bytes()

    def native_candidate_handler(self, raw):
        candidate = self.fixture.coordinator.inspect_candidate(self.fixture.b, raw, time.time_ns() // 1_000_000)
        self.seen.append(raw); self.candidates.append(candidate)
        return protocol.canonical_bytes(self.fixture.coordinator.candidate_receipt(candidate, time.time_ns() // 1_000_000))

    async def listener(self):
        listener = await boot.listen_candidate_lan("127.0.0.1", 0, context=boot.candidate_server_context(self.fixture.b), owner_enabled=True,
            invitation_expires_at_ms=self.invitation["expiresAt"], candidate_handler=self.native_candidate_handler)
        self.listeners.append(listener); return listener

    async def test_real_server_auth_tls_verified_target_can_only_submit_untrusted_candidate(self):
        listener = await self.listener()
        receipt = await boot.open_candidate_lan("127.0.0.1", listener.sockets[0].getsockname()[1], context=boot.candidate_client_context(self.fixture.b.certificate_der),
            exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=self.offer)
        self.assertEqual("pending_owner_confirmation", json.loads(receipt)["state"])
        self.assertEqual(1, len(self.seen)); self.assertEqual(1, len(self.offers))
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))
        self.assertEqual(0, self.fixture.db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
        with self.assertRaises(protocol.ProtocolError): protocol.parse_request(self.offers[0].wire_bytes())
        self.assertEqual(1, self.fixture.db.execute("SELECT COUNT(*) FROM device_pairing_candidates").fetchone()[0])
        self.assertNotIn(self.invitation["token"], "\n".join(self.fixture.db.iterdump()))

    async def test_exact_wrong_invitation_pin_fails_before_offer_factory_and_plaintext(self):
        listener = await self.listener()
        with self.assertRaises((ValueError, PermissionError, d.TransportError)):
            await boot.open_candidate_lan("127.0.0.1", listener.sockets[0].getsockname()[1], context=boot.candidate_client_context(self.fixture.b.certificate_der),
                exact_invitation_certificate_der=self.fixture.a.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=self.offer)
        self.assertEqual([], self.offers); self.assertEqual([], self.seen)
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))

    async def test_owner_disabled_expired_and_oversized_binary_are_not_business_ingress(self):
        kwargs = dict(context=boot.candidate_server_context(self.fixture.b), invitation_expires_at_ms=self.invitation["expiresAt"], candidate_handler=self.native_candidate_handler)
        with self.assertRaises(d.TransportError): await boot.listen_candidate_lan("127.0.0.1", 0, owner_enabled=False, **kwargs)
        with self.assertRaises(d.TransportError): boot._deadline(time.time_ns() // 1_000_000 - 1)
        for raw in (struct.pack("!I", 16385), d.encode_chunk(uuid.uuid4(), 0, b"not onboarding")):
            decoder = d.FrameDecoder(max_control_bytes=16384, allow_binary=False)
            with self.assertRaises(d.TransportError): list(decoder.feed(raw))
            self.assertEqual(0, len(decoder._body))
        self.assertEqual([], self.seen)

    async def test_outbound_relay_pairing_lane_remains_ciphertext_and_has_no_peer_authority(self):
        outer = certificate(self.fixture.scratch.name, "bootstrap-relay")
        route, ta, tb = "bootstrap" * 3, "a" * 48, "b" * 48
        relay = ByteRelay([RouteLease(route, hashlib.sha256(ta.encode()).hexdigest(), hashlib.sha256(tb.encode()).hexdigest(), time.time() + 60)])
        server = await serve_relay(relay, "127.0.0.1", 0, outer_server(outer)); self.listeners.append(server)
        base = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route
        a, b = await asyncio.gather(d.WssByteChannel.connect(base + "/a/pairing", ta, relay_ssl_context=outer_client(outer)),
                                    d.WssByteChannel.connect(base + "/b/pairing", tb, relay_ssl_context=outer_client(outer)))
        tapped = []; original_send = a.send
        async def tap(raw): tapped.append(raw); await original_send(raw)
        a.send = tap
        serve = asyncio.create_task(boot.serve_candidate(b, context=boot.candidate_server_context(self.fixture.b), owner_enabled=True,
            invitation_expires_at_ms=self.invitation["expiresAt"], candidate_handler=self.native_candidate_handler))
        receipt = await boot.exchange_candidate(a, context=boot.candidate_client_context(self.fixture.b.certificate_der),
            exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=self.offer)
        await asyncio.wait_for(serve, 3)
        self.assertEqual("pending_owner_confirmation", json.loads(receipt)["state"])
        self.assertNotIn(self.invitation["token"].encode(), b"".join(tapped))
        self.assertEqual(1, len(self.seen)); self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))

    async def test_process_gate_is_bounded_before_native_signature_work(self):
        # Isolated limiter unit check; restore process bookkeeping for other tests.
        from unittest.mock import patch
        from collections import deque
        with patch.object(boot, "_STARTS", deque()), patch.object(boot, "_ACTIVE", 0):
            self.assertTrue(boot._enter()); self.assertTrue(boot._enter()); self.assertFalse(boot._enter())
            boot._leave(); boot._leave()
            for _ in range(10): self.assertTrue(boot._enter()); boot._leave()
            self.assertFalse(boot._enter())
        self.assertEqual([], self.seen)


    async def prepare_native_confirmed(self):
        listener = await self.listener()
        receipt = await boot.open_candidate_lan("127.0.0.1", listener.sockets[0].getsockname()[1],
            context=boot.candidate_client_context(self.fixture.b.certificate_der), exact_invitation_certificate_der=self.fixture.b.certificate_der,
            invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=self.offer)
        self.assertEqual("pending_owner_confirmation", pairing.verify_candidate_receipt(self.offers[-1], receipt)["state"])
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))
        pending = self.fixture.coordinator.confirm_candidate_locally(self.fixture.b, self.candidates[-1], True,
            time.time_ns() // 1_000_000, reviewed_owner_grant=self.fixture.grant())
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))
        return listener, self.offers[-1], pending

    async def test_real_lan_candidate_owner_scope_review_separate_mtls_then_both_original_stores(self):
        listener, offer, pending = await self.prepare_native_confirmed()
        # Exact retained offer queries state without resigning, confirming or granting again.
        receipt = await boot.open_candidate_lan("127.0.0.1", listener.sockets[0].getsockname()[1],
            context=boot.candidate_client_context(self.fixture.b.certificate_der), exact_invitation_certificate_der=self.fixture.b.certificate_der,
            invitation_expires_at_ms=self.invitation["expiresAt"], offer_factory=lambda target: offer.wire_bytes())
        self.assertEqual("confirmed_for_mtls", pairing.verify_candidate_receipt(offer, receipt)["state"])
        self.assertEqual(1, len(self.offers)); self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))
        db_a = sqlite3.connect(":memory:")
        try:
            trust_a = pairing.DeviceTrust(db_a, threading.RLock()); trust_a.create_schema()
            owner_a = pairing.PairingCoordinator(trust_a, self.fixture.a.device_id, self.fixture.a.instance_id)
            outgoing, peer_proofs = [], []
            def complete_b(peer, raw):
                peer_proofs.append(peer)
                return protocol.canonical_bytes(self.fixture.coordinator.finalize_candidate_pairing(pending, peer, raw,
                    self.fixture.grant(), time.time_ns() // 1_000_000))
            def accept_a(peer, raw):
                outgoing.append(owner_a.accept_candidate_completion(offer, peer, raw, self.fixture.reciprocal_grant(), time.time_ns() // 1_000_000))
            server = await boot.listen_completion_lan("127.0.0.1", 0,
                context=d.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True), native_pending=pending, completion_handler=complete_b)
            self.listeners.append(server)
            result = await boot.open_completion_lan("127.0.0.1", server.sockets[0].getsockname()[1],
                context=d.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False),
                exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"],
                request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_finalize_request(offer)), completion_handler=accept_a)
            self.assertEqual("PairingCompletion", json.loads(result)["messageType"])
            self.assertEqual(1, len(peer_proofs)); self.assertIsInstance(peer_proofs[0], identity.AuthenticatedPeer)
            self.assertIsNotNone(self.fixture.trust.peer(self.fixture.a.device_id)); self.assertIsNotNone(trust_a.peer(self.fixture.b.device_id))
            self.assertEqual(1, len(outgoing)); outgoing[0].check_current(time.time_ns() // 1_000_000)
            self.assertEqual(1, self.fixture.db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
            self.assertEqual(1, db_a.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
            self.assertNotIn(self.invitation["token"], "\n".join(self.fixture.db.iterdump()))
        finally:
            db_a.close()

    async def test_completion_reservation_is_exclusive_unlistened_then_same_socket_real_mtls(self):
        reservation = await boot.reserve_completion_lan("127.0.0.1", owner_enabled=True,
            invitation_expires_at_ms=self.invitation["expiresAt"])
        self.listeners.append(reservation)
        original_address = reservation.address
        self.assertTrue(reservation.is_reserved)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as other:
            with self.assertRaises(OSError): other.bind(original_address)
        with self.assertRaises(ConnectionError):
            await asyncio.wait_for(asyncio.open_connection(*original_address), 1)
        _, offer, pending = await self.prepare_native_confirmed()
        completed = []
        def native_complete(peer, raw):
            completed.append(peer)
            return protocol.canonical_bytes(self.fixture.coordinator.finalize_candidate_pairing(pending, peer, raw,
                self.fixture.grant(), time.time_ns() // 1_000_000))
        arguments = dict(context=d.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True),
            native_pending=pending, completion_handler=native_complete, reservation=reservation)
        listener = await boot.listen_completion_lan(**arguments); self.listeners.append(listener)
        self.assertEqual(original_address, listener.sockets[0].getsockname()[:2])
        self.assertFalse(reservation.is_reserved)
        with self.assertRaises(d.TransportError): await boot.listen_completion_lan(**arguments)
        await boot.open_completion_lan(*original_address,
            context=d.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False),
            exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"],
            request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_finalize_request(offer)),
            completion_handler=lambda peer, raw: self.assertEqual("PairingCompletion", json.loads(raw)["messageType"]))
        self.assertEqual(1, len(completed)); self.assertIsNotNone(self.fixture.trust.peer(self.fixture.a.device_id))

    async def test_completion_reservation_expires_or_closes_without_listening_or_pin_authority(self):
        reservation = await boot.reserve_completion_lan("127.0.0.1", owner_enabled=True,
            invitation_expires_at_ms=time.time_ns() // 1_000_000 + 30)
        self.listeners.append(reservation)
        address = reservation.address
        await asyncio.sleep(0.06)
        self.assertFalse(reservation.is_reserved)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as other: other.bind(address)
        for host, port, enabled in (("localhost", 0, True), ("0.0.0.0", 0, True), ("127.0.0.1", True, True), ("127.0.0.1", 0, False)):
            with self.assertRaises(d.TransportError):
                await boot.reserve_completion_lan(host, port, owner_enabled=enabled, invitation_expires_at_ms=self.invitation["expiresAt"])
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))

    async def test_completion_wrong_mtls_client_rejected_before_native_finalize(self):
        _, offer, pending = await self.prepare_native_confirmed()
        other = identity.provision_identity(Path(self.fixture.scratch.name) / "test-only-unconfirmed-other")
        called = []
        def should_not_complete(peer, raw): called.append(peer); raise AssertionError("Unconfirmed client reached native finalize")
        server = await boot.listen_completion_lan("127.0.0.1", 0,
            context=d.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True), native_pending=pending, completion_handler=should_not_complete)
        self.listeners.append(server)
        with self.assertRaises((EOFError, ssl.SSLError, d.TransportError, ConnectionError)):
            await boot.open_completion_lan("127.0.0.1", server.sockets[0].getsockname()[1],
                context=d.native_device_context(other, [self.fixture.b.certificate_der], server_side=False), exact_invitation_certificate_der=self.fixture.b.certificate_der,
                invitation_expires_at_ms=self.invitation["expiresAt"], request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_finalize_request(offer)),
                completion_handler=lambda peer, raw: self.fail("Unconfirmed client got completion"))
        self.assertEqual([], called); self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))
        self.assertEqual(0, self.fixture.db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])

    async def test_superseded_native_review_cannot_open_completion_listener(self):
        _, _, pending = await self.prepare_native_confirmed()
        self.fixture.coordinator.confirm_candidate_locally(self.fixture.b, self.candidates[-1], True, time.time_ns() // 1_000_000,
            reviewed_owner_grant=dict(self.fixture.grant(), toolBudget=1))
        with self.assertRaises(PermissionError):
            await boot.listen_completion_lan("127.0.0.1", 0,
                context=d.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True), native_pending=pending,
                completion_handler=lambda peer, raw: self.fail("Superseded review finalized"))
        self.assertIsNone(self.fixture.trust.peer(self.fixture.a.device_id))

    async def test_completion_uses_separate_outbound_wss_mtls_lane_with_real_native_commit(self):
        _, offer, pending = await self.prepare_native_confirmed()
        outer = certificate(self.fixture.scratch.name, "completion-relay")
        route, ta, tb = "completion" * 3, "a" * 48, "b" * 48
        relay = ByteRelay([RouteLease(route, hashlib.sha256(ta.encode()).hexdigest(), hashlib.sha256(tb.encode()).hexdigest(), time.time() + 60)])
        server = await serve_relay(relay, "127.0.0.1", 0, outer_server(outer)); self.listeners.append(server)
        base = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route
        a, b = await asyncio.gather(d.WssByteChannel.connect(base + "/a/pairing-complete", ta, relay_ssl_context=outer_client(outer)),
                                    d.WssByteChannel.connect(base + "/b/pairing-complete", tb, relay_ssl_context=outer_client(outer)))
        tapped, accepted = [], []; original_send = a.send
        async def tap(raw): tapped.append(raw); await original_send(raw)
        a.send = tap
        def native_complete(peer, raw):
            return protocol.canonical_bytes(self.fixture.coordinator.finalize_candidate_pairing(pending, peer, raw, self.fixture.grant(), time.time_ns() // 1_000_000))
        def native_accept(peer, raw):
            self.assertEqual(self.fixture.b.device_id, peer.device_id)
            accepted.append(json.loads(raw)["pairingReceipt"]["status"])
        serve = asyncio.create_task(boot.serve_completion(b,
            context=d.native_device_context(self.fixture.b, [pending.peer_certificate_der], server_side=True), native_pending=pending, completion_handler=native_complete))
        result = await boot.exchange_completion(a, context=d.native_device_context(self.fixture.a, [self.fixture.b.certificate_der], server_side=False),
            exact_invitation_certificate_der=self.fixture.b.certificate_der, invitation_expires_at_ms=self.invitation["expiresAt"],
            request_factory=lambda peer: protocol.canonical_bytes(pairing.candidate_finalize_request(offer)), completion_handler=native_accept)
        await asyncio.wait_for(serve, 3)
        self.assertEqual("PairingCompletion", json.loads(result)["messageType"]); self.assertEqual(["paired"], accepted)
        self.assertIsNotNone(self.fixture.trust.peer(self.fixture.a.device_id))
        self.assertNotIn(offer.candidate_digest.encode(), b"".join(tapped))
        self.assertNotIn(b"PairingFinalize", b"".join(tapped)); self.assertGreater(relay.forwarded_bytes, 0)
