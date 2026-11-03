"""Actual original JSON listener sockets; no CLI, model, account or remote target."""
import http.client
import json
from pathlib import Path
import socket
import tempfile
import threading
import unittest

from pocket_companion import CompanionServer, JobManager


class JsonConnectionCloseTests(unittest.TestCase):
    def test_every_original_json_response_declares_its_actual_socket_close(self):
        with tempfile.TemporaryDirectory(prefix="original-json-connection-") as directory:
            root = Path(directory); workspace = root / "workspace"; workspace.mkdir()
            token = "owner-native-http-close-fixture-token-more-than-32"
            manager = JobManager({"stateDir": str(root / "state"), "environments": [{"id": "local", "kind": "local", "root": str(workspace), "workers": ["command"]}]}, token)
            server = CompanionServer(("127.0.0.1", 0), manager)
            thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.01), daemon=True);thread.start()
            try:
                for path, access, expected in [("/v1/status", token, 200), ("/v1/not-an-endpoint", token, 404), ("/v1/status", "not-owner-token", 401)]:
                    with self.subTest(status=expected), socket.create_connection(server.server_address, timeout=3) as peer:
                        peer.settimeout(3)
                        peer.sendall(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + access + "\r\nConnection: keep-alive\r\n\r\n").encode())
                        response = http.client.HTTPResponse(peer);response.begin();payload = json.loads(response.read())
                        closed = peer.recv(1) == b""
                        print(json.dumps({"status": response.status, "responseProtocol": response.version, "connectionHeader": response.getheader("Connection"), "socketEofWhileListenerAlive": closed, "listenerAlive": thread.is_alive()}), flush=True)
                        self.assertEqual(expected, response.status);self.assertEqual(10, response.version)
                        self.assertTrue(closed);self.assertTrue(thread.is_alive());self.assertEqual("close", response.getheader("Connection"))
                        self.assertEqual(expected == 200, payload["ok"])
                self.assertEqual({}, manager.jobs);self.assertEqual({}, manager.processes)
            finally:
                server.shutdown();server.server_close();thread.join(timeout=3);manager.close()
                self.assertFalse(thread.is_alive());self.assertEqual(-1, server.socket.fileno())


if __name__ == "__main__": unittest.main()
