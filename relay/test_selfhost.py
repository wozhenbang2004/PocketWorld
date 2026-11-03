"""Private synthetic lease/TLS fixtures; never provisions a public service."""
import asyncio
import hashlib
import json
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "companion"))
from device_companion import DeviceCompanionService
from device_transport import WssByteChannel
from device_transport_fixtures import certificate, outer_client
from pocket_companion import JobManager
import device_identity

import pocket_relay
import pocket_relay_host as host


class NativeRelaySetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="relay-selfhost-fixture-")
        self.root = Path(self.temp.name)
        self.inviter = "pw1_" + "a" * 64

    def tearDown(self):
        self.temp.cleanup()

    def bundle(self):
        return host.create_lease_bundle("https://127.0.0.1:8443", self.inviter, self.root / "bundle", ttl_seconds=90)

    def test_bundle_is_native_importable_short_slot_config_and_original_companion_route(self):
        result = self.bundle()
        a, b = host.read_native_lease(result["endpointA"]), host.read_native_lease(result["endpointB"])
        leases = pocket_relay.load_leases(result["relayConfig"])
        service = pocket_relay.ByteRelay(leases)
        self.assertEqual(1, len(service.leases))
        self.assertEqual(hashlib.sha256(a["routeToken"].encode()).hexdigest(), leases[0].a_token_sha256)
        self.assertEqual(hashlib.sha256(b["routeToken"].encode()).hexdigest(), leases[0].b_token_sha256)
        self.assertNotEqual(a["routeToken"], b["routeToken"])
        server_config = Path(result["relayConfig"]).read_text()
        self.assertNotIn(a["routeToken"], server_config)
        self.assertNotIn("grantId", server_config)
        for path in (self.root / "bundle").iterdir():
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        state = self.root / "original-state"
        state.mkdir(mode=0o700)
        route = host.companion_route(result["endpointA"], "pw1_" + "b" * 64, "already-reviewed-native-grant", state)
        self.assertNotIn(a["routeToken"], json.dumps(route))
        manager = JobManager({"stateDir": str(state), "environments": [], "devices": {"enabled": True, "routes": [route]}}, "synthetic-local-token-not-device-authority")
        identity = device_identity.provision_identity(self.root / "synthetic-identity")
        native = None
        try:
            native = DeviceCompanionService(manager, local_identity=identity)
            self.assertEqual(a["routeToken"], native._secret(route["relay"]["routeTokenSecretRef"]))
            # A runnable route remains powerless without the native pairing DB.
            with self.assertRaises(PermissionError):
                native._paired(route["peerDeviceId"])
            self.assertEqual(set(), native.connections)
        finally:
            if native:
                native.close()
            manager.close()
        original_token = next(state.glob("relay-*.token")).read_bytes()
        with self.assertRaises(FileExistsError):
            host.companion_route(result["endpointA"], route["peerDeviceId"], route["grantId"], state)
        self.assertEqual(original_token, next(state.glob("relay-*.token")).read_bytes())

    def test_check_is_read_only_default_loopback_and_explicit_nonloopback_is_required(self):
        result = self.bundle()
        cert = certificate(self.root, "relay-check")
        arguments = ["check", "--config", result["relayConfig"], "--cert", str(cert["cert"]), "--key", str(cert["key"])]
        args = host.parser().parse_args(arguments)
        ready = host.check_configuration(args)
        self.assertEqual(("127.0.0.1", 8443, False), (ready["host"], ready["port"], ready["publicListenerStarted"]))
        self.assertEqual(1, ready["routeCount"])
        args.host = "0.0.0.0"
        with self.assertRaises(host.SetupError):
            host.check_configuration(args)
        args.allow_non_loopback = True
        self.assertTrue(host.check_configuration(args)["ready"])
        before = {path.name: path.read_bytes() for path in (self.root / "bundle").iterdir()}
        process = subprocess.run([sys.executable, "-B", str(Path(host.__file__)), *arguments], capture_output=True, timeout=10)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(before, {path.name: path.read_bytes() for path in (self.root / "bundle").iterdir()})
        self.assertNotIn(host.read_native_lease(result["endpointA"])["routeToken"].encode(), process.stdout + process.stderr)

    def test_expired_replaced_and_private_lease_fail_without_credential_diagnostics(self):
        result = self.bundle()
        path = Path(result["endpointA"])
        original = host.read_native_lease(path)
        expired = {**original, "expiresAt": 1}
        path.write_text(json.dumps(expired))
        with self.assertRaises(host.SetupError):
            host.read_native_lease(path)
        path.write_text(json.dumps(original))
        path.chmod(0o644)
        with self.assertRaises(host.SetupError):
            host.read_native_lease(path)
        path.chmod(0o600)
        alias = self.root / "lease-link.json"
        alias.symlink_to(path)
        with self.assertRaises(OSError):
            host.read_native_lease(alias)
        with self.assertRaises(host.SetupError):
            host.create_lease_bundle("wss://untrusted.invalid/path?secret=" + original["routeToken"], self.inviter, self.root / "must-not-exist")
        self.assertFalse((self.root / "must-not-exist").exists())
        process = subprocess.run([sys.executable, "-B", str(Path(host.__file__)), "lease", "--service-base", "wss://user:" + original["routeToken"] + "@localhost",
                                  "--inviter-device", self.inviter, "--output-dir", str(self.root / "rejected")], capture_output=True, timeout=10)
        self.assertEqual(1, process.returncode)
        self.assertNotIn(original["routeToken"].encode(), process.stdout + process.stderr)


class NativeRelayTlsTests(unittest.IsolatedAsyncioTestCase):
    async def test_actual_launcher_routes_real_wss_and_disconnect_closes_original_peer(self):
        with tempfile.TemporaryDirectory(prefix="relay-wss-fixture-") as root:
            root = Path(root)
            with socket.socket() as selection:
                selection.bind(("127.0.0.1", 0))
                port = selection.getsockname()[1]
            result = host.create_lease_bundle("wss://localhost:" + str(port), "pw1_" + "c" * 64, root / "bundle", ttl_seconds=90)
            a, b = host.read_native_lease(result["endpointA"]), host.read_native_lease(result["endpointB"])
            cert = certificate(root, "relay-wire")
            process = await asyncio.create_subprocess_exec(sys.executable, "-B", str(Path(host.__file__)), "run",
                "--config", result["relayConfig"], "--cert", str(cert["cert"]), "--key", str(cert["key"]), "--port", str(port),
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            base = "wss://127.0.0.1:" + str(port) + "/v1/tunnel/" + a["routeId"]
            channels = []
            try:
                for _ in range(100):
                    if process.returncode is not None:
                        self.fail("Actual relay launcher exited before listening")
                    try:
                        _, readiness = await asyncio.open_connection("127.0.0.1", port)
                    except OSError:
                        await asyncio.sleep(0.02)
                        continue
                    readiness.close()
                    await readiness.wait_closed()
                    break
                else:
                    self.fail("Actual loopback relay did not bind its selected port")
                channels = await asyncio.gather(WssByteChannel.connect(base + "/a/control", a["routeToken"], relay_ssl_context=outer_client(cert)),
                                                 WssByteChannel.connect(base + "/b/control", b["routeToken"], relay_ssl_context=outer_client(cert)))
                ciphertext_fixture = b"\x17\x03\x03\x00\x05opaque"
                await channels[0].send(ciphertext_fixture)
                self.assertEqual(ciphertext_fixture, await channels[1].receive(1))
                await channels[0].close()
                with self.assertRaises(Exception):
                    await channels[1].receive(2)
                self.assertIsNone(process.returncode)
            finally:
                await asyncio.gather(*(channel.close() for channel in channels), return_exceptions=True)
                if process.returncode is None:
                    process.send_signal(signal.SIGINT)
                stdout, stderr = await asyncio.wait_for(process.communicate(), 5)
                self.assertNotIn(a["routeToken"].encode(), stdout + stderr)
                self.assertNotIn(b["routeToken"].encode(), stdout + stderr)


if __name__ == "__main__":
    unittest.main()
