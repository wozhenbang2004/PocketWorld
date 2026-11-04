"""Real private sockets and loopback TLS behind a synthetic ADB smart-socket server."""
import asyncio
import copy
import os
from pathlib import Path
import sqlite3
import select
import socket
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import device_pairing as pairing
import device_transport as transport
import test_device_pairing as pairing_fixture
from phone_task_usb import OwnedUsbForward, UsbForwardError, attach


class AdbFixture:
    def __init__(self, directory, port=40123, *, server=("localhost", 5037)):
        self.executable = Path(directory) / "adb-fixture"
        self.executable.touch()
        self.port, self.boot, self.serial = port, 7, "fixture-selected-phone"
        self.server = server
        self.rows, self.calls, self.services = {}, [], []
        self.sockets, self.threads, self.clients = [], [], []
        self.backend_port = None
        self.reject_service = None
        self.truncate_service = None
        self.connect_failure = False
        self.fragment_io = False
        self.connect_count = 0
        self.create_entered, self.create_release = None, None

    def run(self, argv, **kwargs):
        self.calls.append(argv)
        host, port = self.server
        cli_host = f"[{host}]" if ":" in host else host
        if argv[:7] != [str(self.executable), "-H", cli_host, "-P", str(port), "-s", self.serial]:
            raise AssertionError("ADB lost its frozen server or explicitly selected serial")
        args = argv[7:]
        if args == ["shell", "settings", "get", "global", "boot_count"]:
            output = str(self.boot)
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(argv, 0, output.encode(), b"")

    def connect(self, address, timeout):
        if address != self.server or timeout != 6:
            raise AssertionError("Private stream moved to another server")
        self.connect_count += 1
        if self.connect_failure:
            raise TimeoutError("synthetic server unavailable")
        if self.create_entered:
            self.create_entered.set()
            if not self.create_release.wait(3):
                raise AssertionError("Fixture creation was not released")
        client, server = socket.socketpair()
        self.clients.append(client)
        self.sockets.append(server)

        def read_exact(count):
            result = bytearray()
            while len(result) < count:
                raw = server.recv(count - len(result))
                if not raw:
                    raise EOFError("client closed")
                result.extend(raw)
            return bytes(result)

        def serve():
            backend = None
            try:
                server.settimeout(3)
                for expected in ("host:transport:" + self.serial, "tcp:9991"):
                    length = int(read_exact(4), 16)
                    service = read_exact(length).decode("ascii")
                    self.services.append(service)
                    if service != expected:
                        raise AssertionError(service)
                    if service == self.reject_service:
                        server.sendall(b"FAIL")
                        return
                    if service == self.truncate_service:
                        server.sendall(b"OK")
                        return
                    for value in b"OKAY":
                        server.sendall(bytes([value]))
                server.settimeout(None)
                if self.backend_port is None:
                    while server.recv(4096):
                        pass
                else:
                    backend = socket.create_connection(("127.0.0.1", self.backend_port), timeout=3)
                    backend.settimeout(None)
                    while True:
                        ready, _, _ = select.select([server, backend], [], [], 3)
                        if not ready:
                            continue
                        for source in ready:
                            data = source.recv(65536)
                            if not data:
                                return
                            (backend if source is server else server).sendall(data)
            except (OSError, EOFError):
                pass
            finally:
                server.close()
                if backend is not None:
                    backend.close()

        thread = threading.Thread(target=serve, daemon=True)
        self.threads.append(thread)
        thread.start()
        if self.fragment_io:
            class FragmentedSocket:
                sends = 0
                def __getattr__(self, name):
                    return getattr(client, name)
                def send(self, data):
                    self.sends += 1
                    return client.send(data[:1])
                def recv(self, count):
                    return client.recv(min(1, count))
            return FragmentedSocket()
        return client

    def close(self):
        for stream in self.clients + self.sockets:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()
        for thread in self.threads:
            thread.join(1)

    def forward(self, environment=None):
        return OwnedUsbForward(self.executable, self.serial, 7, 9991, run=self.run, connect=self.connect,
                               environment={} if environment is None else environment)


class UsbCurrentOwnershipTests(unittest.TestCase):
    def fixture(self, directory, *, server=("localhost", 5037)):
        adb = AdbFixture(directory, server=server)
        self.addCleanup(adb.close)
        return adb

    def test_explicit_tcp_server_socket_selects_the_same_cli_and_private_stream_target(self):
        for configured, server in (
                ("tcp:5047", ("localhost", 5047)),
                ("tcp:127.0.0.2:5048", ("127.0.0.2", 5048)),
                ("tcp:adb.example.test:5049", ("adb.example.test", 5049)),
                ("tcp:[::1]:5050", ("::1", 5050))):
            with self.subTest(configured=configured), tempfile.TemporaryDirectory() as root:
                adb = self.fixture(root, server=server)
                forward = adb.forward({"ADB_SERVER_SOCKET": configured})
                forward.open()
                self.assertTrue(forward.is_current())
                self.assertTrue(forward.close())
                self.assertEqual(1, adb.connect_count)
                self.assertEqual(3, len(adb.calls))
                self.assertFalse(any("forward" in call for call in adb.calls))

    def test_server_socket_takes_precedence_over_legacy_address_and_port(self):
        with tempfile.TemporaryDirectory() as root:
            adb = self.fixture(root, server=("selected.example.test", 5051))
            forward = adb.forward({"ADB_SERVER_SOCKET": "tcp:selected.example.test:5051",
                                   "ANDROID_ADB_SERVER_ADDRESS": "ignored.example.test", "ANDROID_ADB_SERVER_PORT": "invalid"})
            forward.open()
            self.assertTrue(forward.is_current())
            self.assertTrue(forward.close())

    def test_legacy_android_adb_server_address_and_port_are_preserved(self):
        for environment, server in (
                ({"ANDROID_ADB_SERVER_PORT": "5052"}, ("localhost", 5052)),
                ({"ANDROID_ADB_SERVER_ADDRESS": "adb.example.test", "ANDROID_ADB_SERVER_PORT": "5053"}, ("adb.example.test", 5053)),
                ({"ANDROID_ADB_SERVER_ADDRESS": "127.0.0.2", "ANDROID_ADB_SERVER_PORT": ""}, ("127.0.0.2", 5037)),
                ({"ANDROID_ADB_SERVER_ADDRESS": "[::1]", "ANDROID_ADB_SERVER_PORT": "5054"}, ("::1", 5054))):
            with self.subTest(environment=environment), tempfile.TemporaryDirectory() as root:
                adb = self.fixture(root, server=server)
                forward = adb.forward(environment)
                forward.open()
                self.assertTrue(forward.is_current())
                self.assertTrue(forward.close())

    def test_environment_changes_after_construction_cannot_move_either_server_check_or_stream(self):
        for original, server in (
                ({"ADB_SERVER_SOCKET": "tcp:127.0.0.2:5055"}, ("127.0.0.2", 5055)),
                ({"ANDROID_ADB_SERVER_ADDRESS": "127.0.0.2", "ANDROID_ADB_SERVER_PORT": "5055"}, ("127.0.0.2", 5055)),
                ({}, ("localhost", 5037))):
            with self.subTest(original=original), tempfile.TemporaryDirectory() as root, patch.dict(os.environ, original, clear=True):
                adb = self.fixture(root, server=server)
                # Exercise the actual process-environment path, not the fixture's injected map.
                forward = OwnedUsbForward(adb.executable, adb.serial, 7, 9991, run=adb.run, connect=adb.connect)
                os.environ.update(ADB_SERVER_SOCKET="localfilesystem:/changed-after-selection",
                                  ANDROID_ADB_SERVER_ADDRESS="different.example.test", ANDROID_ADB_SERVER_PORT="65534")
                forward.open()
                os.environ.clear()
                self.assertTrue(forward.is_current())
                self.assertEqual(3, len(adb.calls))
                self.assertEqual(1, adb.connect_count)
                self.assertTrue(forward.close())

    def test_unsupported_or_invalid_server_configuration_fails_before_any_adb_dispatch(self):
        configurations = [{"ADB_SERVER_SOCKET": value} for value in (
            "", "localabstract:adb", "localfilesystem:/tmp/adb.sock", "vsock:2:5037",
            "tcp:", "tcp:missing-port.example.test", "tcp:0", "tcp:65536", "tcp:-1",
            "tcp:host:port", "tcp::5037", "tcp:[::1]:0", "tcp:[invalid:v6]:5037",
            "tcp:[fe80::1%bad\nzone]:5037", "tcp:host\nname:5037")]
        configurations += [{"ANDROID_ADB_SERVER_PORT": value} for value in ("0", "65536", "x", "-1")]
        configurations += [{"ANDROID_ADB_SERVER_ADDRESS": value} for value in ("", "host:5037", "bad address")]
        for environment in configurations:
            with self.subTest(environment=environment), tempfile.TemporaryDirectory() as root:
                adb = self.fixture(root)
                with self.assertRaisesRegex(UsbForwardError, "ADB"):
                    adb.forward(environment)
                self.assertEqual([], adb.calls)
                self.assertEqual(0, adb.connect_count)

    def test_replaced_shared_mapping_cannot_redirect_stream_or_be_removed_by_cleanup(self):
        with tempfile.TemporaryDirectory() as root:
            adb = self.fixture(root)
            adb.rows[adb.port] = (adb.serial, "tcp:9991")
            forward = adb.forward()
            stream = forward.open()
            adb.rows[adb.port] = (adb.serial, "tcp:9992")
            self.assertTrue(forward.is_current())
            self.assertEqual("ready", forward.safe_status()["state"])
            self.assertTrue(forward.close())
            self.assertEqual(-1, stream.fileno())
            self.assertIn(adb.port, adb.rows)
            self.assertEqual((adb.serial, "tcp:9992"), adb.rows[adb.port])
            self.assertFalse(any("forward" in call for call in adb.calls))
            with self.assertRaises(UsbForwardError):
                forward.open()
            self.assertNotIn(adb.serial, str(forward.safe_status()))
            self.assertFalse(forward.safe_status()["sharedForwardCreated"])

    def test_short_writes_and_fragmented_status_do_not_truncate_or_repeat_a_service(self):
        with tempfile.TemporaryDirectory() as root:
            adb = self.fixture(root)
            adb.fragment_io = True
            forward = adb.forward()
            stream = forward.open()
            self.assertGreater(stream.sends, 30)
            self.assertEqual(["host:transport:" + adb.serial, "tcp:9991"], adb.services)
            self.assertEqual(1, adb.connect_count)
            self.assertTrue(forward.close())

    def test_refused_transport_or_tcp_open_closes_only_the_original_socket_without_retry(self):
        for selected in ("host:transport:fixture-selected-phone", "tcp:9991"):
            with self.subTest(selected=selected), tempfile.TemporaryDirectory() as root:
                adb = self.fixture(root)
                adb.reject_service = selected
                adb.rows[adb.port] = (adb.serial, "tcp:9992")
                forward = adb.forward()
                with self.assertRaises(UsbForwardError):
                    forward.open()
                self.assertTrue(forward.cleanup_confirmed)
                self.assertEqual(-1, adb.clients[0].fileno())
                self.assertEqual(1, adb.connect_count)
                self.assertEqual(selected, adb.services[-1])
                self.assertEqual((adb.serial, "tcp:9992"), adb.rows[adb.port])
                with self.assertRaises(UsbForwardError):
                    forward.open()

    def test_incomplete_status_and_connect_timeout_have_no_global_cleanup_side_effect(self):
        for connect_failure in (False, True):
            with self.subTest(connect_failure=connect_failure), tempfile.TemporaryDirectory() as root:
                adb = self.fixture(root)
                adb.connect_failure = connect_failure
                adb.truncate_service = "tcp:9991"
                forward = adb.forward()
                with self.assertRaises((UsbForwardError, TimeoutError)):
                    forward.open()
                self.assertTrue(forward.cleanup_confirmed)
                self.assertEqual(1, adb.connect_count)
                self.assertFalse(any("forward" in call for call in adb.calls))

    def test_repeat_close_cannot_close_a_later_socket_reusing_the_old_descriptor(self):
        with tempfile.TemporaryDirectory() as root:
            adb = self.fixture(root)
            forward = adb.forward()
            forward.open()
            self.assertTrue(forward.close())
            later, peer = socket.socketpair()
            try:
                self.assertTrue(forward.close())
                later.sendall(b"successor-owner")
                self.assertEqual(b"successor-owner", peer.recv(40))
            finally:
                later.close()
                peer.close()


class UsbTlsLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = pairing_fixture.DevicePairingTests()
        self.fixture.setUp()
        f = self.fixture
        invitation, _, _, remote, receipt = f.pair()
        self.db = sqlite3.connect(":memory:", check_same_thread=False)
        lock = threading.RLock()
        self.trust = pairing.DeviceTrust(self.db, lock)
        self.trust.create_schema()
        owner = pairing.PairingCoordinator(self.trust, f.a.device_id, f.a.instance_id)
        reverse = copy.deepcopy(f.grant())
        reverse.update(grantId="reverse-grant", peerDeviceId=f.b.device_id, localDeviceId=f.a.device_id, allowedOrigins=[f.b.device_id])
        owner.accept_pairing_receipt(invitation, f.peer_b, remote, receipt, reverse, f.now)
        owner.accept_outgoing_grant(f.peer_b, f.coordinator.issue_grant_receipt(f.b, "grant-1", f.now), f.now)
        manager = SimpleNamespace(now=lambda: int(time.time() * 1000), closed=False)
        self.bound, self.received = [], []
        self.server, self.client = None, None

        def current(peer_id):
            value = self.trust.peer(peer_id)
            if value is None or value["revoked"]:
                raise PermissionError("Original synthetic pairing revoked")
            return value

        async def bound(connection, peer):
            self.bound.append(peer.device_id)
            self.service.connections.add(connection)

            async def receive(actual_peer, frame):
                self.received.append(frame.control)

            async def closed(unknown, error):
                self.service.connections.discard(connection)
                if getattr(self, "fail_close_callback", False):
                    raise RuntimeError("Synthetic earlier callback failure")

            connection.on_frame, connection.on_close = receive, closed

        self.service = SimpleNamespace(identity=f.a, receiver=SimpleNamespace(trust=self.trust, pairing=owner),
                                       manager=manager, closed=False, tasks=[], connections=set(), _paired=current, _bind=bound)
        self.adb = AdbFixture(f.scratch.name)

    async def asyncTearDown(self):
        if self.client:
            await self.client.close()
        if self.service.tasks:
            await asyncio.wait_for(asyncio.gather(*self.service.tasks, return_exceptions=True), 4)
        if self.server:
            await self.server.close()
        self.adb.close()
        self.db.close()
        self.fixture.tearDown()

    async def open_server(self):
        f = self.fixture
        self.sent_to_phone = []
        self.server_received = asyncio.Event()

        async def frame(peer, value):
            self.sent_to_phone.append(value.control)
            self.server_received.set()

        self.server = await transport.listen_lan("127.0.0.1", 0, owner_enabled=True,
            context=transport.native_device_context(f.b, [f.a.certificate_der], server_side=True),
            expected_certificate_der=f.a.certificate_der, lane="control", on_frame=frame)
        self.adb.backend_port = self.server.sockets[0].getsockname()[1]
        self.forward = self.adb.forward()
        self.client = await attach(self.service, f.b.device_id, "grant-1", self.forward)

    async def test_real_tls_boot_change_prevents_queued_new_bytes_and_cleans_owned_forward(self):
        await self.open_server()
        self.assertEqual([self.fixture.b.device_id], self.bound)
        self.client.send_control(b"query-the-same-original-operation")
        await asyncio.wait_for(self.server_received.wait(), 3)
        self.adb.boot = 8
        self.client.send_control(b"must-not-dispatch-after-reboot")
        await asyncio.wait_for(asyncio.gather(*self.service.tasks), 4)
        self.assertEqual([b"query-the-same-original-operation"], self.sent_to_phone)
        self.assertTrue(self.client.closed)
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertFalse(any("forward" in call for call in self.adb.calls))
        self.assertEqual(1, self.adb.connect_count)

    async def test_revoked_native_pairing_fences_next_usb_packet_without_reconnect(self):
        await self.open_server()
        self.db.execute("UPDATE device_peers SET revoked=1")
        self.db.commit()
        self.client.send_control(b"must-not-dispatch-after-revocation")
        await asyncio.wait_for(asyncio.gather(*self.service.tasks), 4)
        self.assertEqual([], self.sent_to_phone)
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertEqual(1, self.adb.connect_count)

    async def test_shared_mapping_rebind_cannot_redirect_the_existing_tls_stream_or_its_cleanup(self):
        await self.open_server()
        self.adb.rows[self.adb.port] = (self.adb.serial, "tcp:9992")
        self.client.send_control(b"original-private-stream")
        await asyncio.wait_for(self.server_received.wait(), 3)
        self.assertEqual([b"original-private-stream"], self.sent_to_phone)
        await self.client.close()
        self.assertEqual((self.adb.serial, "tcp:9992"), self.adb.rows[self.adb.port])
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertFalse(any("forward" in call for call in self.adb.calls))

    async def test_cancellation_during_adb_creation_waits_for_exact_owned_cleanup(self):
        self.adb.create_entered, self.adb.create_release = threading.Event(), threading.Event()
        self.forward = self.adb.forward()
        attempt = asyncio.create_task(attach(self.service, self.fixture.b.device_id, "grant-1", self.forward))
        self.assertTrue(await asyncio.to_thread(self.adb.create_entered.wait, 2))
        attempt.cancel()
        self.adb.create_release.set()
        with self.assertRaises(asyncio.CancelledError):
            await asyncio.wait_for(attempt, 4)
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertEqual({}, self.adb.rows)
        self.assertEqual([], self.bound)

    async def test_earlier_native_close_callback_failure_still_cleans_forward(self):
        await self.open_server()
        self.fail_close_callback = True
        await self.client.close()
        results = await asyncio.wait_for(asyncio.gather(*self.service.tasks, return_exceptions=True), 4)
        self.assertTrue(any(isinstance(result, RuntimeError) for result in results))
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertEqual({}, self.adb.rows)


if __name__ == "__main__":
    unittest.main()
