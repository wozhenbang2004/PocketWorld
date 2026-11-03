"""Synthetic ADB control plus real loopback TLS; no attached phone is selected."""
import asyncio
import copy
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest

import device_pairing as pairing
import device_transport as transport
import test_device_pairing as pairing_fixture
from phone_task_usb import OwnedUsbForward, UsbForwardError, attach


class AdbFixture:
    def __init__(self, directory, port=40123):
        self.executable = Path(directory) / "adb-fixture"
        self.executable.touch()
        self.port, self.boot, self.serial = port, 7, "fixture-selected-phone"
        self.rows, self.calls = {}, []
        self.create_entered, self.create_release = None, None

    def run(self, argv, **kwargs):
        self.calls.append(argv)
        if argv[:3] != [str(self.executable), "-s", self.serial]:
            raise AssertionError("ADB lost its explicitly selected serial")
        args = argv[3:]
        if args == ["shell", "settings", "get", "global", "boot_count"]:
            output = str(self.boot)
        elif args[:3] == ["forward", "--no-rebind", "tcp:0"]:
            if self.create_entered:
                self.create_entered.set()
                if not self.create_release.wait(3):
                    raise AssertionError("Fixture creation was not released")
            self.rows[self.port] = (self.serial, args[3])
            output = str(self.port)
        elif args == ["forward", "--list"]:
            output = "\n".join(f"{serial} tcp:{port} {target}" for port, (serial, target) in self.rows.items())
        elif args == ["forward", "--remove", f"tcp:{self.port}"]:
            self.rows.pop(self.port, None)
            output = ""
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(argv, 0, output.encode(), b"")

    def forward(self):
        return OwnedUsbForward(self.executable, self.serial, 7, 9991, run=self.run)


class UsbCurrentOwnershipTests(unittest.TestCase):
    def test_replaced_mapping_fences_next_write_and_cleanup_preserves_new_owner(self):
        with tempfile.TemporaryDirectory() as root:
            adb = AdbFixture(root)
            forward = adb.forward()
            forward.open()
            adb.rows[adb.port] = ("another-explicit-device", "tcp:9991")
            self.assertFalse(forward.is_current())
            self.assertEqual("invalidated", forward.safe_status()["state"])
            self.assertTrue(forward.close())
            self.assertIn(adb.port, adb.rows)
            self.assertFalse(any("--remove" in call or "--remove-all" in call for call in adb.calls))
            with self.assertRaises(UsbForwardError):
                forward.open()
            self.assertNotIn(adb.serial, str(forward.safe_status()))


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
        self.adb.port = self.server.sockets[0].getsockname()[1]
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
        self.assertNotIn(self.adb.port, self.adb.rows)
        self.assertEqual(1, sum("--no-rebind" in call for call in self.adb.calls))

    async def test_revoked_native_pairing_fences_next_usb_packet_without_reconnect(self):
        await self.open_server()
        self.db.execute("UPDATE device_peers SET revoked=1")
        self.db.commit()
        self.client.send_control(b"must-not-dispatch-after-revocation")
        await asyncio.wait_for(asyncio.gather(*self.service.tasks), 4)
        self.assertEqual([], self.sent_to_phone)
        self.assertTrue(self.forward.cleanup_confirmed)
        self.assertEqual(1, sum("--no-rebind" in call for call in self.adb.calls))

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
