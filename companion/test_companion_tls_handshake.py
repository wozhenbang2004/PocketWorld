"""Original HTTPS listener admission with ephemeral loopback TLS peers only."""
from contextlib import contextmanager
import http.client
import json
from pathlib import Path
import queue
import socket
import ssl
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import pocket_companion as companion
from device_transport_fixtures import certificate, outer_client


TOKEN = "fixture-only-https-owner-token-more-than-32-characters"


class CompanionTlsHandshakeTests(unittest.TestCase):
    @contextmanager
    def listener(self, *, timeout=15, slots=16):
        ready, accepting = threading.Event(), threading.Event()
        workers, finished = queue.Queue(), queue.Queue()
        servers, errors, peers = [], [], []

        class ObservedServer(companion.CompanionServer):
            tls_handshake_timeout = timeout
            max_tls_handshakes = slots

            def serve_forever(self, **kwargs):
                servers.append(self)
                ready.set()
                super().serve_forever(poll_interval=.01)

            def get_request(self):
                accepting.set()
                return super().get_request()

            def process_request_thread(self, request, address):
                workers.put(address)
                try:
                    super().process_request_thread(request, address)
                finally:
                    finished.put(address)

        with tempfile.TemporaryDirectory(prefix="companion-tls-admission-") as directory:
            root = Path(directory)
            workspace = root / "workspace"
            workspace.mkdir()
            cert = certificate(directory, "listener")
            config = {"listen": "127.0.0.1", "port": 0, "tlsCert": str(cert["cert"]),
                      "tlsKey": str(cert["key"]), "stateDir": str(root / "state"),
                      "environments": [{"id": "local", "kind": "local", "root": str(workspace), "workers": ["command"]}]}
            manager = companion.JobManager(config, TOKEN)

            def serve():
                try:
                    companion.serve_original_manager(manager)
                except BaseException as error:
                    errors.append(error)
                    ready.set()

            with patch.object(companion, "CompanionServer", ObservedServer):
                thread = threading.Thread(target=serve, daemon=True)
                thread.start()
                try:
                    self.assertTrue(ready.wait(3), "Original listener did not start")
                    self.assertEqual([], errors)
                    server = servers[0]

                    def idle_peer():
                        peer = socket.create_connection(server.server_address, timeout=2)
                        peers.append(peer)
                        self.assertTrue(accepting.wait(2))
                        return peer

                    def request(path="/v1/status", token=TOKEN):
                        client = http.client.HTTPSConnection(*server.server_address, timeout=1, context=outer_client(cert))
                        try:
                            client.request("GET", path, headers={"Authorization": "Bearer " + token})
                            response = client.getresponse()
                            return response.status, json.loads(response.read())
                        finally:
                            client.close()

                    yield idle_peer, request, workers, finished
                finally:
                    for peer in peers:
                        peer.close()  # Also releases the vulnerable pre-fix accept handshake.
                    if servers:
                        servers[0].shutdown()
                    thread.join(3)
                    self.assertFalse(thread.is_alive())
                    self.assertEqual([], errors)
                    if not manager.closed:
                        manager.close()

    def test_idle_tcp_peer_does_not_block_original_https_auth_and_routes(self):
        with self.listener() as (idle_peer, request, _workers, _finished):
            idle_peer()
            for path, token, status in [("/v1/status", TOKEN, 200), ("/v1/status", "wrong", 401),
                                        ("/v1/missing", TOKEN, 404)]:
                with self.subTest(status=status):
                    actual, payload = request(path, token)
                    self.assertEqual(status, actual)
                    self.assertEqual(status == 200, payload["ok"])

    def test_idle_handshake_expires_and_releases_admission_slot(self):
        with self.listener(timeout=.2, slots=1) as (idle_peer, request, workers, finished):
            peer = idle_peer()
            workers.get(timeout=2)
            self.assertEqual(b"", peer.recv(1))
            finished.get(timeout=2)
            self.assertEqual(200, request()[0])

    def test_handshake_capacity_rejects_excess_without_new_worker_and_recovers(self):
        with self.listener(slots=1) as (idle_peer, request, workers, finished):
            first = idle_peer()
            workers.get(timeout=2)
            second = idle_peer()
            self.assertEqual(b"", second.recv(1))
            self.assertTrue(workers.empty(), "Excess TLS handshake spawned a worker")
            first.close()
            finished.get(timeout=2)
            self.assertEqual(200, request()[0])

    def test_invalid_tls_releases_slot_without_accepting_plaintext_http(self):
        with self.listener(slots=1) as (idle_peer, request, workers, finished):
            peer = idle_peer()
            workers.get(timeout=2)
            peer.sendall(b"GET /v1/status HTTP/1.0\r\n\r\n")
            try:
                self.assertEqual(b"", peer.recv(1))
            except ConnectionResetError:
                pass
            finished.get(timeout=2)
            self.assertEqual(200, request()[0])

    def test_worker_start_failure_returns_tls_admission_slot(self):
        server = object.__new__(companion.CompanionServer)
        server._tls_handshake_slots = threading.BoundedSemaphore(1)
        peer = Mock(spec=ssl.SSLSocket)
        with patch.object(companion.ThreadingHTTPServer, "process_request", side_effect=RuntimeError("cannot start worker")):
            with self.assertRaisesRegex(RuntimeError, "cannot start worker"):
                server.process_request(peer, ("127.0.0.1", 1))
        self.assertTrue(server._tls_handshake_slots.acquire(blocking=False))
        self.assertFalse(server._tls_handshake_slots.acquire(blocking=False))


if __name__ == "__main__":
    unittest.main()
