import asyncio
import hashlib
from pathlib import Path
import ssl
import socket
import struct
import sys
import tempfile
import time
import unittest
import uuid

import device_transport as d
from device_transport_fixtures import certificate, context, outer_server, outer_client
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))
from pocket_relay import ByteRelay, RouteLease, serve_relay


class DeviceFramesTests(unittest.TestCase):
    def test_fragmentation_binary_and_control_and_hostile_lengths(self):
        transfer = uuid.uuid4(); data = bytes(range(256)) * 1024
        encoded = d.encode_control('真控制'.encode()) + d.encode_chunk(transfer, 2**40, data)
        decoder = d.FrameDecoder(); frames = []
        for start in range(0, len(encoded), 177):
            frames += list(decoder.feed(encoded[start:start + 177]))
        self.assertEqual('真控制'.encode(), frames[0].control)
        self.assertEqual((transfer, 2**40, data), (frames[1].transfer_id, frames[1].offset, frames[1].data))
        self.assertFalse(decoder.incomplete)
        for prefix in [0, d.MAX_CONTROL + 1, 0x80000017, 0x80000000 | (d.MAX_BINARY + 1), 0xffffffff]:
            with self.assertRaises(d.TransportError):
                list(d.FrameDecoder().feed(struct.pack("!I", prefix)))

    def test_one_shared_machine_framing_fixture(self):
        import json
        fixture = json.loads((Path(__file__).resolve().parents[1] / "protocol/device-v1/framing-fixtures.json").read_text())
        self.assertEqual(d.MAX_BINARY, fixture["limits"]["binaryPayloadBytes"])
        for item in fixture["fixtures"]:
            with self.subTest(item["name"]):
                raw = bytes.fromhex(item["wireHex"])
                if not item["valid"]:
                    with self.assertRaises(d.TransportError): list(d.FrameDecoder().feed(raw))
                elif item["kind"] == "control":
                    self.assertEqual(raw, d.encode_control(item["controlUtf8"].encode()))
                    self.assertEqual(item["controlUtf8"].encode(), list(d.FrameDecoder().feed(raw))[0].control)
                else:
                    self.assertEqual(raw, d.encode_chunk(uuid.UUID(item["transferId"]), item["offset"], bytes.fromhex(item["dataHex"])))
                    self.assertEqual(bytes.fromhex(item["dataHex"]), list(d.FrameDecoder().feed(raw))[0].data)

    def test_no_business_retry_or_success_in_connect_backoff(self):
        retry = d.ReconnectBackoff()
        for _ in range(30):
            self.assertLessEqual(retry.disconnected_delay(), 30)
        retry.authenticated(); self.assertLessEqual(retry.disconnected_delay(), 0.5)
        self.assertFalse(hasattr(retry, "pending_requests"))


class MemoryTlsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.a = certificate(self.temp.name, "a"); self.b = certificate(self.temp.name, "b"); self.c = certificate(self.temp.name, "c")

    def tearDown(self):
        self.temp.cleanup()

    def pair(self):
        a = d.MemoryBioTls(context(self.a, self.b, server_side=False), server_side=False, expected_certificate_der=self.b["der"])
        b = d.MemoryBioTls(context(self.b, self.a, server_side=True), server_side=True, expected_certificate_der=self.a["der"])
        for _ in range(100):
            a.handshake(); b.handshake()
            for raw in a.encrypted(): b.feed(raw)
            for raw in b.encrypted(): a.feed(raw)
            if a.authenticated and b.authenticated: break
        self.assertTrue(a.authenticated and b.authenticated)
        return a, b

    def test_real_memorybio_mutual_tls_binds_certificate_not_json(self):
        a, b = self.pair()
        self.assertEqual(a.peer.device_id, __import__('device_identity').device_id(self.b["der"]))
        self.assertEqual(b.peer.device_id, __import__('device_identity').device_id(self.a["der"]))
        secret = b'{"actorId":"spoof","body":"fixture secret bytes"}'
        a.write(secret); encrypted = list(a.encrypted())
        self.assertNotIn(b"fixture secret bytes", b"".join(encrypted))
        for raw in encrypted: b.feed(raw)
        self.assertEqual(secret, b"".join(b.plaintext()))

    def test_ciphertext_tamper_and_replay_fail_before_application(self):
        for tamper in (False, True):
            a, b = self.pair(); a.write(b"not a replayable GUI action")
            raw = b"".join(a.encrypted())
            if tamper:
                bad = bytearray(raw); bad[-1] ^= 1; b.feed(bytes(bad))
            else:
                b.feed(raw); self.assertEqual(b"not a replayable GUI action", b"".join(b.plaintext())); b.feed(raw)
            with self.assertRaises(ssl.SSLError):
                list(b.plaintext())

    def test_wrong_device_certificate_and_unverified_context_fail(self):
        a = d.MemoryBioTls(context(self.a, self.b, server_side=False), server_side=False, expected_certificate_der=self.b["der"])
        c = d.MemoryBioTls(context(self.c, self.a, server_side=True), server_side=True, expected_certificate_der=self.a["der"])
        with self.assertRaises(ssl.SSLError):
            for _ in range(20):
                a.handshake(); c.handshake()
                for raw in a.encrypted(): c.feed(raw)
                for raw in c.encrypted(): a.feed(raw)
        invalid = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); invalid.check_hostname = False; invalid.verify_mode = ssl.CERT_NONE
        with self.assertRaises(d.TransportError):
            d.MemoryBioTls(invalid, server_side=False, expected_certificate_der=self.b["der"])


class RealNetworkTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.a = certificate(self.temp.name, "a"); self.b = certificate(self.temp.name, "b")
        self.connections, self.tasks, self.servers = [], [], []

    async def asyncTearDown(self):
        for connection in self.connections:
            await connection.close()
        if self.tasks:
            await asyncio.wait_for(asyncio.gather(*self.tasks, return_exceptions=True), 5)
        for server in self.servers:
            result = server.close()
            if asyncio.iscoroutine(result): await result
            if hasattr(server, "wait_closed"): await server.wait_closed()
        self.temp.cleanup()

    async def test_lan_only_explicitly_enabled_and_same_tls_identity(self):
        received = asyncio.Event(); online = asyncio.Event(); frames = []
        async def server_frame(peer, frame): frames.append((peer, frame)); received.set()
        async def client_frame(peer, frame): pass
        async def ready(peer): online.set()
        config = dict(context=context(self.b, self.a, server_side=True), expected_certificate_der=self.a["der"], lane="control", on_frame=server_frame)
        with self.assertRaises(d.TransportError):
            await d.listen_lan("127.0.0.1", 0, owner_enabled=False, **config)
        server = await d.listen_lan("127.0.0.1", 0, owner_enabled=True, **config); self.servers.append(server)
        client = await d.open_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=context(self.a, self.b, server_side=False), expected_certificate_der=self.b["der"], lane="control", on_frame=client_frame, on_online=ready)
        self.connections.append(client)
        with self.assertRaises(d.TransportError): client.send_control(b"not-online")
        self.tasks.append(asyncio.create_task(client.run())); await asyncio.wait_for(online.wait(), 3)
        self.assertTrue(client.send_control(b'{"query":"original-operation-only"}'))
        await asyncio.wait_for(received.wait(), 3)
        self.assertEqual(__import__('device_identity').device_id(self.a["der"]), frames[0][0].device_id)
        self.assertEqual(b'{"query":"original-operation-only"}', frames[0][1].control)
        self.assertEqual("online", client.state)
        await client.close()
        with self.assertRaises(d.TransportError): client.send_control(b"must-not-retain")

    async def test_native_reserved_port_transfers_once_to_original_service_on_another_loop(self):
        reservation = await d.reserve_lan_port("127.0.0.1", lane="control", owner_enabled=True,
            expires_at_ms=time.time_ns() // 1_000_000 + 10000)
        self.servers.append(reservation); address = reservation.address
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as other:
            with self.assertRaises(OSError): other.bind(address)
        with self.assertRaises(ConnectionError): await asyncio.wait_for(asyncio.open_connection(*address), 1)
        async def service_on_its_own_loop():
            async def unused(peer, frame): pass
            kwargs = dict(owner_enabled=True, context=context(self.b, self.a, server_side=True),
                expected_certificate_der=self.a["der"], on_frame=unused, reservation=reservation)
            with self.assertRaises(d.TransportError): await d.listen_lan(lane="bulk", **kwargs)
            online, received = asyncio.Event(), asyncio.Event(); actual = []
            async def inbound(peer, frame): actual.append((peer.device_id, frame.control)); received.set()
            kwargs["on_frame"] = inbound
            server = await d.listen_lan(lane="control", **kwargs)
            client, running = None, None
            try:
                self.assertEqual(address, server.sockets[0].getsockname()[:2])
                self.assertFalse(reservation.is_reserved)
                await reservation.close()  # Origin window teardown cannot close the handed-off listener.
                with self.assertRaises(d.TransportError): await d.listen_lan(lane="control", **kwargs)
                async def ready(peer): online.set()
                client = await d.open_lan(*address, context=context(self.a, self.b, server_side=False),
                    expected_certificate_der=self.b["der"], lane="control", on_frame=unused, on_online=ready)
                running = asyncio.create_task(client.run()); await asyncio.wait_for(online.wait(), 3)
                self.assertTrue(client.send_control(b"query-original-id-not-a-side-effect"))
                await asyncio.wait_for(received.wait(), 3)
                return actual
            finally:
                if client is not None: await client.close()
                if running is not None: await asyncio.wait_for(running, 3)
                await server.close()
        # The fixture starts another real event loop/thread, as the original PC service does.
        actual = await asyncio.to_thread(lambda: asyncio.run(service_on_its_own_loop()))
        self.assertEqual([(__import__('device_identity').device_id(self.a["der"]), b"query-original-id-not-a-side-effect")], actual)

    async def test_native_port_reservation_expiry_close_and_explicit_owner_gate(self):
        reservation = await d.reserve_lan_port("127.0.0.1", lane="bulk", owner_enabled=True,
            expires_at_ms=time.time_ns() // 1_000_000 + 30)
        self.servers.append(reservation); address = reservation.address
        await asyncio.sleep(0.06); self.assertFalse(reservation.is_reserved)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as other: other.bind(address)
        for host, lane, enabled in (("localhost", "control", True), ("0.0.0.0", "control", True), ("127.0.0.1", "pairing", True), ("127.0.0.1", "bulk", False)):
            with self.assertRaises(d.TransportError):
                await d.reserve_lan_port(host, lane=lane, owner_enabled=enabled, expires_at_ms=time.time_ns() // 1_000_000 + 1000)

    async def test_actual_bulk_write_gate_revocation_drops_queued_bytes_and_closes_pending_wait(self):
        for allow_first in (False, True):
            with self.subTest(already_dispatched_chunk=allow_first):
                entered, release, queued, first_received = (asyncio.Event() for _ in range(4))
                source_closed, sink_closed = asyncio.Event(), asyncio.Event()
                authority = {"current": True, "checks": 0}; holder, buffers, received = {}, [], []
                pending_read = asyncio.get_running_loop().create_future()
                async def unused(peer, frame): self.fail("Unexpected reverse bulk")
                async def source_bound(connection, peer): holder["source"] = connection
                async def before_write(actual):
                    self.assertEqual(__import__('device_identity').device_id(self.a["der"]), actual.device_id)
                    self.assertEqual(hashlib.sha256(self.a["der"]).hexdigest(), actual.certificate_fingerprint)
                    authority["checks"] += 1
                    if not allow_first or authority["checks"] > 1:
                        entered.set(); await release.wait()
                    if not authority["current"]: raise PermissionError("Native TEST read grant withdrawn between enqueue and TLS write")
                async def source_online(peer):
                    connection = holder["source"]
                    self.assertTrue(connection.send_chunk(uuid.uuid4(), 0, b"original-private-chunk-one", before_write=before_write))
                    self.assertTrue(connection.send_chunk(uuid.uuid4(), 0, b"original-private-chunk-two", before_write=before_write))
                    buffers.extend(frame for frame, gate in connection._queue._queue); queued.set()
                async def source_close(unknown, error): source_closed.set()
                server = await d.listen_lan("127.0.0.1", 0, owner_enabled=True, context=context(self.b, self.a, server_side=True), expected_certificate_der=self.a["der"],
                    lane="bulk", on_frame=unused, on_connection=source_bound, on_online=source_online, on_close=source_close)
                self.servers.append(server)
                async def frame(peer, value): received.append(value.data); first_received.set()
                async def sink_close(unknown, error):
                    if not pending_read.done(): pending_read.set_exception(ConnectionError("Original bulk read ended, not completed"))
                    sink_closed.set()
                sink = await d.open_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=context(self.a, self.b, server_side=False),
                    expected_certificate_der=self.b["der"], lane="bulk", on_frame=frame, on_close=sink_close)
                self.connections.append(sink); self.tasks.append(asyncio.create_task(sink.run()))
                try:
                    await asyncio.wait_for(queued.wait(), 3); await asyncio.wait_for(entered.wait(), 3)
                    if allow_first: await asyncio.wait_for(first_received.wait(), 3)
                    authority["current"] = False; release.set()
                    await asyncio.wait_for(source_closed.wait(), 3); await asyncio.wait_for(sink_closed.wait(), 3)
                    with self.assertRaises(ConnectionError): await pending_read
                    self.assertEqual([b"original-private-chunk-one"] if allow_first else [], received)
                    self.assertEqual(2, len(buffers)); self.assertTrue(all(not any(raw) for raw in buffers))
                    self.assertTrue(holder["source"]._queue.empty()); self.assertEqual("offline", holder["source"].state)
                    self.assertEqual(allow_first, holder["source"].delivery_may_be_unknown)  # Diagnostic only, never task acceptance.
                finally: release.set(); await sink.close(); await server.close()

    async def test_outbound_wss_relay_cannot_read_inner_body_and_does_not_replay(self):
        outer = certificate(self.temp.name, "relay")
        route, a_token, b_token = "r" * 32, "a" * 48, "b" * 48
        relay = ByteRelay([RouteLease(route, hashlib.sha256(a_token.encode()).hexdigest(), hashlib.sha256(b_token.encode()).hexdigest(), time.time() + 60)])
        server = await serve_relay(relay, "127.0.0.1", 0, outer_server(outer)); self.servers.append(server)
        base = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route
        a, b = await asyncio.gather(d.WssByteChannel.connect(base + "/a/control", a_token, relay_ssl_context=outer_client(outer)), d.WssByteChannel.connect(base + "/b/control", b_token, relay_ssl_context=outer_client(outer)))
        captured = []
        original_send = a.send
        async def tap(raw): captured.append(raw); await original_send(raw)
        a.send = tap
        event, ready = asyncio.Event(), asyncio.Event(); seen = []
        async def on_a(peer, frame): pass
        async def on_b(peer, frame): seen.append(frame.control); event.set()
        async def online(peer): ready.set()
        ca = d.DeviceConnection(a, d.MemoryBioTls(context(self.a, self.b, server_side=False), server_side=False, expected_certificate_der=self.b["der"]), lane="control", on_frame=on_a, on_online=online)
        cb = d.DeviceConnection(b, d.MemoryBioTls(context(self.b, self.a, server_side=True), server_side=True, expected_certificate_der=self.a["der"]), lane="control", on_frame=on_b)
        self.connections += [ca, cb]; self.tasks += [asyncio.create_task(ca.run()), asyncio.create_task(cb.run())]
        await asyncio.wait_for(ready.wait(), 3)
        secret = b'{"fixture":"private task screenshot model-key sentinel"}'
        self.assertTrue(ca.send_control(secret)); await asyncio.wait_for(event.wait(), 3)
        self.assertEqual([secret], seen); self.assertNotIn(b"private task screenshot model-key sentinel", b"".join(captured))
        self.assertGreater(relay.forwarded_bytes, len(secret))
        await ca.close(); await asyncio.wait_for(asyncio.gather(*self.tasks), 5)
        self.assertEqual([], list(ca._queue._queue)); self.assertEqual(1, len(seen))
        for _ in range(30):
            if relay.connections == 0: break
            await asyncio.sleep(0.01)
        self.assertEqual(0, relay.connections)

    async def test_frame_backpressure_and_control_reserved_from_bulk(self):
        tls = d.MemoryBioTls(context(self.a, self.b, server_side=False), server_side=False, expected_certificate_der=self.b["der"])
        async def unused(*args): pass
        connection = d.DeviceConnection(None, tls, lane="control", on_frame=unused)
        connection.state = "online"  # Queue-only unit check; real TLS is tested above.
        self.assertEqual([True] * 16 + [False], [connection.send_control(b"q") for _ in range(17)])
        with self.assertRaises(d.TransportError): connection.send_chunk(uuid.uuid4(), 0, b"x", before_write=lambda actual: None)


    async def test_on_online_can_await_its_own_original_id_query_without_stopping_io(self):
        acknowledged, ready = asyncio.Event(), asyncio.Event()
        async def unused(peer, frame): raise AssertionError("Unbound frame callback")
        async def bind_server(connection, peer):
            async def frame(actual, value):
                self.assertEqual(b"query:original-operation", value.control)
                self.assertTrue(connection.send_control(b"receipt:original-operation"))
            connection.on_frame = frame
        server = await d.listen_lan("127.0.0.1", 0, owner_enabled=True, context=context(self.b, self.a, server_side=True),
            expected_certificate_der=self.a["der"], lane="control", on_frame=unused, on_connection=bind_server)
        self.servers.append(server)
        async def received(peer, frame):
            self.assertEqual(b"receipt:original-operation", frame.control); acknowledged.set()
        async def online(peer):
            self.assertTrue(client.send_control(b"query:original-operation"))
            await asyncio.wait_for(acknowledged.wait(), 2); ready.set()
        client = await d.open_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=context(self.a, self.b, server_side=False),
            expected_certificate_der=self.b["der"], lane="control", on_frame=received, on_online=online)
        self.connections.append(client); self.tasks.append(asyncio.create_task(client.run()))
        await asyncio.wait_for(ready.wait(), 3); self.assertEqual("online", client.state)

    async def test_lan_current_native_pins_refresh_without_rebinding_or_closing_old_channels(self):
        c = certificate(self.temp.name, "new-paired-c")
        current = [self.a]; observed = asyncio.Queue()
        async def unused(peer, frame): pass
        async def accepted(connection, peer): await observed.put((connection, peer))
        def native_snapshot():
            if not current: raise PermissionError("No currently paired native certificates")
            return context(self.b, current[0], server_side=True), (current[0]["der"],)
        server = await d.listen_lan("127.0.0.1", 0, owner_enabled=True, native_tls_factory=native_snapshot,
                                    lane="control", on_frame=unused, on_connection=accepted)
        self.servers.append(server); port = server.sockets[0].getsockname()[1]
        async def connect(cert):
            client = await d.open_lan("127.0.0.1", port, context=context(cert, self.b, server_side=False),
                                      expected_certificate_der=self.b["der"], lane="control", on_frame=unused)
            self.connections.append(client); self.tasks.append(asyncio.create_task(client.run())); return client
        await connect(self.a); original, _ = await asyncio.wait_for(observed.get(), 3)
        current[:] = [c]
        await connect(c); latest, peer = await asyncio.wait_for(observed.get(), 3)
        self.assertEqual(__import__('device_identity').device_id(c["der"]), peer.device_id)
        refused = await connect(self.a); await asyncio.wait_for(self.tasks[-1], 3)
        self.assertEqual("offline", refused.state); self.assertTrue(observed.empty())
        current.clear()
        refused = await connect(c); await asyncio.wait_for(self.tasks[-1], 3)
        self.assertEqual("offline", refused.state); self.assertTrue(observed.empty())
        self.assertEqual(port, server.sockets[0].getsockname()[1]); self.assertTrue(server.server.is_serving())
        self.assertEqual("online", original.state); self.assertEqual("online", latest.state)




class ReconnectNetworkTests(unittest.IsolatedAsyncioTestCase):
    async def test_lost_effect_reply_reconnect_queries_same_id_and_never_replays(self):
        temporary = tempfile.TemporaryDirectory(); a = certificate(temporary.name, "a"); b = certificate(temporary.name, "b")
        active, server_tasks = set(), set()
        operation = "original-operation-no-new-id"; invocations, queries, attempts = [], [], []
        reconciled = asyncio.Event()
        async def accepted(reader, writer):
            connection = None
            async def frame(peer, value):
                text = value.control.decode()
                if text == "invoke:" + operation:
                    invocations.append(text)
                    # Real disconnect AFTER the fixture side effect, BEFORE its reply.
                    await connection.close()
                elif text == "query:" + operation:
                    queries.append(text); reconciled.set()
                else:
                    raise AssertionError("Changed operation identity")
            connection = d.DeviceConnection(d.StreamByteChannel(reader, writer), d.MemoryBioTls(context(b, a, server_side=True), server_side=True, expected_certificate_der=a["der"]), lane="control", on_frame=frame)
            active.add(connection)
            try: await connection.run()
            finally: active.discard(connection)
        server = await asyncio.start_server(accepted, "127.0.0.1", 0, limit=d.MAX_PACKET)
        client = None
        task = None
        try:
            async def factory():
                attempts.append(1)
                async def no_response(peer, frame): pass
                async def ready(peer):
                    self.assertEqual(__import__('device_identity').device_id(b["der"]), peer.device_id)
                    message = ("invoke:" if len(attempts) == 1 else "query:") + operation
                    if not connection.send_control(message.encode()): raise AssertionError("Fixture backpressure")
                connection = await d.open_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=context(a, b, server_side=False), expected_certificate_der=b["der"], lane="control", on_frame=no_response, on_online=ready)
                return connection
            client = d.ReconnectingDeviceClient(owner_enabled=True, access_deadline_ms=time.time_ns() // 1_000_000 + 5000, factory=factory)
            task = asyncio.create_task(client.run())
            await asyncio.wait_for(reconciled.wait(), 4)
            self.assertEqual(["invoke:" + operation], invocations)
            self.assertEqual(["query:" + operation], queries)
            self.assertGreaterEqual(len(attempts), 2)
        finally:
            if client: await client.close()
            if task: await asyncio.wait_for(task, 3)
            server.close(); await server.wait_closed()
            for connection in tuple(active): await connection.close()
            await asyncio.sleep(0)
            temporary.cleanup()


class RelayPriorityTests(unittest.IsolatedAsyncioTestCase):
    async def test_control_progresses_while_bulk_consumer_is_blocked(self):
        temporary = tempfile.TemporaryDirectory()
        a, b, outer = (certificate(temporary.name, name) for name in ("a", "b", "relay"))
        route, ta, tb = "priority" * 4, "a" * 48, "b" * 48
        relay = ByteRelay([RouteLease(route, hashlib.sha256(ta.encode()).hexdigest(), hashlib.sha256(tb.encode()).hexdigest(), time.time() + 60)])
        server = await serve_relay(relay, "127.0.0.1", 0, outer_server(outer))
        base = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route
        connections, tasks = [], []
        bulk_entered, unblock_bulk, control_received = asyncio.Event(), asyncio.Event(), asyncio.Event()
        try:
            async def lane(kind):
                wa, wb = await asyncio.gather(d.WssByteChannel.connect(base + "/a/" + kind, ta, relay_ssl_context=outer_client(outer)), d.WssByteChannel.connect(base + "/b/" + kind, tb, relay_ssl_context=outer_client(outer)))
                ready = asyncio.Event()
                async def online(peer): ready.set()
                async def on_a(peer, frame): pass
                async def on_b(peer, frame):
                    if kind == "bulk":
                        bulk_entered.set(); await unblock_bulk.wait()
                    else:
                        self.assertEqual(b"small-control-cancel-or-approval", frame.control); control_received.set()
                ca = d.DeviceConnection(wa, d.MemoryBioTls(context(a, b, server_side=False), server_side=False, expected_certificate_der=b["der"]), lane=kind, on_frame=on_a, on_online=online)
                cb = d.DeviceConnection(wb, d.MemoryBioTls(context(b, a, server_side=True), server_side=True, expected_certificate_der=a["der"]), lane=kind, on_frame=on_b)
                connections.extend([ca, cb]); tasks.extend([asyncio.create_task(ca.run()), asyncio.create_task(cb.run())])
                await asyncio.wait_for(ready.wait(), 3)
                return ca
            control = await lane("control"); bulk = await lane("bulk")
            raw = b"large bounded binary bytes" * (d.MAX_CHUNK // 26)
            self.assertTrue(bulk.send_chunk(uuid.uuid4(), 0, raw, before_write=lambda actual: None)); self.assertTrue(bulk.send_chunk(uuid.uuid4(), 0, raw, before_write=lambda actual: None)); self.assertFalse(bulk.send_chunk(uuid.uuid4(), 0, raw, before_write=lambda actual: None))
            await asyncio.wait_for(bulk_entered.wait(), 3)
            self.assertTrue(control.send_control(b"small-control-cancel-or-approval"))
            await asyncio.wait_for(control_received.wait(), 1)
            self.assertFalse(unblock_bulk.is_set())
        finally:
            unblock_bulk.set()
            for connection in connections: await connection.close()
            if tasks: await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 5)
            server.close(); await server.wait_closed(); temporary.cleanup()

    async def test_bad_route_token_and_outer_untrusted_certificate_are_rejected(self):
        temporary = tempfile.TemporaryDirectory(); outer = certificate(temporary.name, "relay")
        loop = asyncio.get_running_loop(); old_handler = loop.get_exception_handler()
        def expected_reset(loop, info):
            if isinstance(info.get("exception"), ConnectionResetError): return  # Expected outer-TLS rejection below.
            if old_handler: old_handler(loop, info)
            else: loop.default_exception_handler(info)
        loop.set_exception_handler(expected_reset)
        route, ta, tb = "r" * 32, "a" * 48, "b" * 48
        relay = ByteRelay([RouteLease(route, hashlib.sha256(ta.encode()).hexdigest(), hashlib.sha256(tb.encode()).hexdigest(), time.time() + 60)])
        server = await serve_relay(relay, "127.0.0.1", 0, outer_server(outer))
        endpoint = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route + "/a/control"
        try:
            with self.assertRaises(Exception): await asyncio.wait_for(d.WssByteChannel.connect(endpoint, "wrong" * 12, relay_ssl_context=outer_client(outer)), 2)
            with self.assertRaises(ssl.SSLCertVerificationError): await d.WssByteChannel.connect(endpoint, ta)
            self.assertEqual(0, relay.connections)
            with self.assertRaises(d.TransportError): await d.WssByteChannel.connect(endpoint.replace("wss:", "ws:"), ta)
        finally:
            server.close(); await server.wait_closed(); await asyncio.sleep(0)
            loop.set_exception_handler(old_handler); temporary.cleanup()




class ListenerBindingTests(unittest.IsolatedAsyncioTestCase):
    async def test_same_peer_multiple_lan_connections_reply_on_exact_native_connection(self):
        temporary = tempfile.TemporaryDirectory(); a = certificate(temporary.name, "a"); b = certificate(temporary.name, "b")
        connections, tasks, bound = [], [], []
        received, replies = [asyncio.Event(), asyncio.Event()], [[], []]
        async def unbound(peer, frame): raise AssertionError("LAN frame arrived before native connection binding")
        async def bind(connection, peer):
            bound.append(connection)
            self.assertIs(peer, connection.tls.peer)
            self.assertIs(peer, connection.tls.peer)  # Stable native nonce / identity object per channel.
            async def frame(actual_peer, value):
                self.assertIs(peer, actual_peer)
                self.assertTrue(connection.send_control(value.control + b":same-connection"))
            connection.on_frame = frame
        server = await d.listen_lan("127.0.0.1", 0, owner_enabled=True, context=context(b, a, server_side=True),
            expected_certificate_der=a["der"], lane="control", on_frame=unbound, on_connection=bind)
        try:
            for index in range(2):
                ready = asyncio.Event()
                async def online(peer, event=ready): event.set()
                async def frame(peer, value, slot=index): replies[slot].append(value.control); received[slot].set()
                connection = await d.open_lan("127.0.0.1", server.sockets[0].getsockname()[1], context=context(a, b, server_side=False),
                    expected_certificate_der=b["der"], lane="control", on_frame=frame, on_online=online)
                connections.append(connection); tasks.append(asyncio.create_task(connection.run())); await asyncio.wait_for(ready.wait(), 3)
            for index, connection in enumerate(connections):
                self.assertTrue(connection.send_control(("request-" + str(index)).encode()))
            await asyncio.wait_for(asyncio.gather(*(event.wait() for event in received)), 3)
            self.assertEqual([[b"request-0:same-connection"], [b"request-1:same-connection"]], replies)
            self.assertEqual(2, len(bound)); self.assertIsNot(bound[0], bound[1])
        finally:
            for connection in connections: await connection.close()
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 5)
            await server.close(); temporary.cleanup()



if __name__ == "__main__":
    unittest.main()
