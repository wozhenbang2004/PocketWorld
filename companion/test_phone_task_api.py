"""Real original Companion HTTP sockets with a synthetic paired-service boundary.

These tests exercise the production listener, authentication, strict body parser
and adapter mount. The recorder is deliberately not a phone execution fixture;
native task admission, receipts and cancellation have separate bridge tests.
"""
import copy
import http.client
import json
from pathlib import Path
import socket
import tempfile
import threading
from types import SimpleNamespace
import unittest

from phone_task_bridge import PhoneTaskError
from pocket_companion import CompanionServer, JobManager


TOKEN = "synthetic-loopback-phone-adapter-session-fixture-123456789"
PEER = "pw1_" + "2" * 64
PARAMS = {"operationId": "fixture-original-operation", "peerDeviceId": PEER}
BODY = json.dumps({"action": "status", "params": PARAMS}, separators=(",", ":")).encode()


class RecordedPhoneSurface:
    def __init__(self):
        self.calls = []
        self.failure = None
        self.result = {**PARAMS, "status": "unknown", "effectState": "unknown"}

    def invoke(self, action, params):
        self.calls.append((action, copy.deepcopy(params)))
        if self.failure is not None:
            raise self.failure
        return copy.deepcopy(self.result)


class PhoneTaskApiSocketTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="phone-adapter-http-")
        root = Path(self.temp.name)
        workspace = root / "workspace"
        workspace.mkdir()
        self.manager = JobManager({"stateDir": str(root / "state"), "environments": [
            {"id": "fixture", "kind": "local", "root": str(workspace), "workers": ["command"]}
        ]}, TOKEN)
        self.phone = RecordedPhoneSurface()
        self.manager.device_companion = SimpleNamespace(phone_tasks=self.phone, close=lambda: None)
        self.listeners = []
        self.server = self.listener()

    def listener(self, *, gateway_only=False):
        server = CompanionServer(("127.0.0.1", 0), self.manager, gateway_only=gateway_only)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        thread.start()
        self.listeners.append((server, thread))
        return server

    def tearDown(self):
        for server, thread in reversed(self.listeners):
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
        self.assertEqual({}, self.manager.jobs)
        self.assertEqual({}, self.manager.processes)
        self.manager.close()
        self.temp.cleanup()

    def headers(self, body=BODY, *, server=None):
        server = server or self.server
        return [("Host", "127.0.0.1:" + str(server.server_port)),
                ("Authorization", "Bearer " + TOKEN), ("Content-Type", "application/json"),
                ("Content-Length", str(len(body))), ("Connection", "keep-alive")]

    def exchange(self, *, path="/v1/phone-tasks", method="POST", body=BODY,
                 headers=None, server=None, shutdown_write=False, pipeline=b""):
        server = server or self.server
        if headers is None:
            headers = self.headers(body, server=server)
        raw = (method + " " + path + " HTTP/1.1\r\n" +
               "".join(name + ": " + value + "\r\n" for name, value in headers) + "\r\n").encode("ascii")
        with socket.create_connection(server.server_address, timeout=3) as peer:
            peer.settimeout(3)
            peer.sendall(raw + body + pipeline)
            if shutdown_write:
                peer.shutdown(socket.SHUT_WR)
            response = http.client.HTTPResponse(peer)
            response.begin()
            payload = json.loads(response.read())
            self.assertEqual("close", response.getheader("Connection"))
            self.assertEqual("no-store", response.getheader("Cache-Control"))
            self.assertNotIn(TOKEN, json.dumps(payload))
            # Rejected bytes must not become another request on this socket.
            try:
                self.assertEqual(b"", peer.recv(1))
            except ConnectionResetError:
                pass  # The server may reset when it closes an unread invalid body.
            return response.status, payload

    def refused(self, expected, **request):
        before = len(self.phone.calls)
        status, payload = self.exchange(**request)
        self.assertEqual(expected, status, payload)
        self.assertIs(False, payload["ok"])
        self.assertEqual(before, len(self.phone.calls), "A rejected HTTP request reached the phone surface")

    def test_good_route_uses_original_manager_and_returns_unknown_once(self):
        status, payload = self.exchange()
        self.assertEqual(200, status)
        self.assertEqual({"ok": True, "result": self.phone.result}, payload)
        self.assertEqual([("status", PARAMS)], self.phone.calls)
        self.assertIs(self.manager, self.server.manager)
        # The adapter forwards one explicit query; it does not retry an unknown.
        self.assertEqual("unknown", payload["result"]["effectState"])

    def test_native_service_must_already_exist_and_gateway_listener_cannot_route(self):
        native = self.manager.device_companion
        try:
            self.manager.device_companion = None
            self.refused(503)
        finally:
            self.manager.device_companion = native
        self.refused(404, server=self.listener(gateway_only=True))
        self.assertEqual({}, self.manager.jobs)

    def test_exact_session_is_required_before_any_phone_call(self):
        for authorization in (None, "Bearer invalid-synthetic-session", "Basic " + TOKEN):
            with self.subTest(authorization_present=authorization is not None):
                headers = [(name, value) for name, value in self.headers() if name != "Authorization"]
                if authorization is not None:
                    headers.append(("Authorization", authorization))
                self.refused(401, headers=headers)

    def test_duplicate_session_host_and_framing_headers_are_rejected(self):
        for name in ("Host", "Authorization", "Content-Length", "Content-Type"):
            with self.subTest(header=name):
                headers = self.headers()
                duplicate = next(value for key, value in headers if key == name)
                headers.append((name.lower(), duplicate))
                self.refused(400, headers=headers)
        for missing in ("Host", "Content-Length", "Content-Type"):
            with self.subTest(missing=missing):
                self.refused(400, headers=[(name, value) for name, value in self.headers() if name != missing])

    def test_origin_and_host_cannot_redirect_a_valid_local_session(self):
        for origin_headers in ([('Origin', 'https://untrusted.example')], [('Origin', 'null')],
                               [('Origin', 'http://127.0.0.1:' + str(self.server.server_port))],
                               [('Origin', 'null'), ('origin', 'null')]):
            with self.subTest(origin=origin_headers):
                self.refused(403, headers=self.headers() + origin_headers)
        for host in ("untrusted.example", "localhost", "127.0.0.1:1", "127.0.0.1:" + str(self.server.server_port) + ".untrusted.example"):
            with self.subTest(host=host):
                self.refused(403, headers=[(name, host if name == "Host" else value) for name, value in self.headers()])

    def test_encoded_path_query_fragment_or_subroute_never_dispatches(self):
        rejected = {"/v1/%70hone-tasks": 403, "/%76%31/phone-tasks": 403,
                    "/v1/phone-tasks/": 403, "/v1/phone-tasks?operationId=another": 403,
                    "/v1/phone-tasks?%61ction=run": 403, "/v1/phone-tasks#fragment": 403,
                    "/v1/phone-tasks/other": 403, "/v1/phone-tasks%2fother": 400,
                    "http://127.0.0.1:" + str(self.server.server_port) + "/v1/phone-tasks": 403}
        for path, expected in rejected.items():
            with self.subTest(path=path):
                self.refused(expected, path=path)
        self.refused(405, method="GET")

    def test_duplicate_json_keys_and_nonfinite_values_never_reach_service(self):
        invalid = [b'{"action":"status","action":"run","params":{}}',
                   b'{"action":"status","params":{"operationId":"a","operationId":"b"}}',
                   b'{"action":"status","params":{},"params":{"operationId":"other"}}',
                   b'{"action":"status","params":{"value":NaN}}',
                   b'{"action":"status","params":{"value":Infinity}}',
                   b'{"action":"status","params":{"value":1e400}}',
                   b'{"action":"status","params":{"value":"\xff"}}', b'[]', b'{']
        for body in invalid:
            with self.subTest(body=body):
                self.refused(400, body=body)

    def test_ambiguous_outer_action_or_client_auth_fields_are_rejected(self):
        for value in ({"action": "status"}, {"params": PARAMS}, {"action": [], "params": PARAMS},
                      {"action": "status", "params": PARAMS, "grant": {"approved": True}}):
            with self.subTest(keys=list(value)):
                self.refused(400, body=json.dumps(value).encode())

    def test_json_type_and_body_limit_are_checked_before_waiting_for_bytes(self):
        for content_type in ("text/plain", "application/json;charset=latin1"):
            with self.subTest(content_type=content_type):
                self.refused(415, headers=[(name, content_type if name == "Content-Type" else value) for name, value in self.headers()])
        for length in ("0", "-1", "+1", "1, 1", "65537", "9999999999999999999999"):
            with self.subTest(length=length):
                self.refused(413, body=b"", headers=[(name, length if name == "Content-Length" else value) for name, value in self.headers()])
        self.refused(403, body=b"", headers=self.headers() + [("Transfer-Encoding", "chunked")])
        boundary = BODY + b" " * (65536 - len(BODY))
        headers = [(name, "application/json; charset=utf-8" if name == "Content-Type" else value) for name, value in self.headers(boundary)]
        self.assertEqual(200, self.exchange(body=boundary, headers=headers)[0])
        self.assertEqual([("status", PARAMS)], self.phone.calls)

    def test_short_body_fails_closed_and_listener_remains_usable(self):
        self.refused(400, body=BODY[:-1], headers=self.headers(), shutdown_write=True)
        self.assertEqual(200, self.exchange()[0])
        self.assertEqual([("status", PARAMS)], self.phone.calls)

    def test_rejected_unread_body_cannot_pipeline_a_second_dispatch(self):
        second = ("POST /v1/phone-tasks HTTP/1.1\r\n" +
                  "".join(name + ": " + value + "\r\n" for name, value in self.headers()) + "\r\n").encode() + BODY
        self.refused(403, headers=self.headers() + [("Origin", "https://untrusted.example")], pipeline=second)
        self.assertEqual(200, self.exchange()[0])
        self.assertEqual(1, len(self.phone.calls))

    def test_native_rejection_and_sensitive_exception_are_not_success_or_leaks(self):
        self.phone.failure = PhoneTaskError("Current native phone review is required", 409)
        status, payload = self.exchange()
        self.assertEqual(409, status)
        self.assertIs(False, payload["ok"])
        sensitive = "synthetic-private-diagnostic-not-for-http"
        self.phone.failure = ValueError(sensitive)
        status, payload = self.exchange()
        self.assertEqual(403, status)
        self.assertIs(False, payload["ok"])
        self.assertNotIn(sensitive, json.dumps(payload))
        self.assertEqual([("status", PARAMS)] * 2, self.phone.calls)


if __name__ == "__main__":
    unittest.main()
