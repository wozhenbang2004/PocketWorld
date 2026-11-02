"""Transport tests only; native TaskStore/ModelGateway evidence lives in the host journey."""
import base64
import copy
import hashlib
import http.client
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from urllib.parse import urlencode
from pocket_companion import JobManager, CompanionServer
from harness_gateway import BridgeError, MAX_FRAME, MAX_QUEUE

TOKEN = "fixture-only-paired-token-not-a-real-credential-123456789"
ACCESS = "fixture_only_gateway_token_not_a_real_credential_123456789"

class HarnessGatewayTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = {"stateDir": str(self.root / "state"), "environments": [{"id": "fixture", "kind": "local", "root": str(self.root), "workers": ["command"], "commands": {}}]}
        self.manager = JobManager(self.config, TOKEN)
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
        self.lease = {"leaseId": "original-lease", "clientBindingId": "original-cli", "generation": "generation-original", "environmentId": "fixture", "environmentBinding": self.manager.environment_binding("fixture"), "bindingId": "fixture-codex", "profileDigest": "a" * 64, "taskId": "original-task", "runId": "original-run", "epoch": 1, "audience": "OWNER_PRIVATE", "purpose": "EXTERNAL_RELAY", "expiresAt": self.manager.now() + 60000, "accessToken": ACCESS, "protocols": ["openai_responses", "anthropic"]}
        self.gateway = self.manager.harness_gateway
        self.gateway.register(self.lease, self.base)
        self.clients = []
    def tearDown(self):
        self.manager.harness_gateway.close()
        for client in self.clients:
            client.close()
        self.server.shutdown(); self.server.server_close(); self.manager.close(); self.temp.cleanup()
    def pending(self, path="/v1/responses", body=b'{"model":"fixture","input":"hello","stream":true}'):
        result = {}
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=4)
        self.clients.append(conn)
        def send():
            try:
                conn.request("POST", path, body, {"Authorization": "Bearer " + ACCESS, "Content-Type": "application/json"})
                response = conn.getresponse(); result.update(status=response.status, body=response.read())
            except OSError as error:
                result["error"] = type(error).__name__
        worker = threading.Thread(target=send, daemon=True); worker.start()
        page = self.gateway.poll(self.lease["leaseId"], {"generation": self.lease["generation"], "waitMs": "1000"})
        self.assertEqual(1, len(page["requests"]))
        req = page["requests"][0]
        bound = {key: req[key] for key in ("leaseId", "generation", "requestDigest")}
        self.gateway.claim(req["bridgeRequestId"], bound | {"claimId": "original-native-claim"})
        return req, bound | {"claimId": "original-native-claim"}, result, worker
    def frame(self, bound, seq, offset, payload, last=True, eof=False):
        return bound | {"eventSeq": seq, "offset": offset, "payload": base64.b64encode(payload).decode(), "isLastFragment": last, "eof": eof, "status": 200, "contentType": "text/event-stream; charset=utf-8"}
    def test_real_http_large_event_distinct_offsets_duplicate_ack_and_eof(self):
        req, bound, result, worker = self.pending()
        event = ('event: response.output_text.delta\ndata: {"delta":"' + "中" * 18000 + '"}\n\n').encode()
        for offset in range(0, len(event), MAX_FRAME):
            value = self.frame(bound, 1, offset, event[offset:offset + MAX_FRAME], offset + MAX_FRAME >= len(event))
            first = self.gateway.frame(req["bridgeRequestId"], value)
            self.assertFalse(first["duplicate"])
            self.assertTrue(self.gateway.frame(req["bridgeRequestId"], value)["duplicate"])
        final = b'event: response.completed\ndata: {"type":"response.completed"}\n\n'
        self.gateway.frame(req["bridgeRequestId"], self.frame(bound, 2, 0, final))
        self.gateway.frame(req["bridgeRequestId"], self.frame(bound, 3, 0, b"", eof=True))
        worker.join(3)
        self.assertFalse(worker.is_alive()); self.assertEqual(200, result["status"]); self.assertEqual(event + final, result["body"])
        stored = self.manager.ledger.execute("SELECT state,claim_id FROM gateway_requests WHERE request_id=?", (req["bridgeRequestId"],)).fetchone()
        self.assertEqual(("COMPLETE", "original-native-claim"), stored)
        self.assertLessEqual(self.gateway.requests[req["bridgeRequestId"]]["highWater"], MAX_QUEUE)
    def test_same_full_identity_different_payload_conflicts_and_other_claim_rejected(self):
        req, bound, _, _ = self.pending(); value = self.frame(bound, 1, 0, b"first", False)
        self.gateway.frame(req["bridgeRequestId"], value)
        with self.assertRaises(BridgeError) as changed:
            self.gateway.frame(req["bridgeRequestId"], value | {"payload": base64.b64encode(b"other").decode()})
        self.assertEqual(409, changed.exception.status)
        with self.assertRaises(BridgeError):
            self.gateway.claim(req["bridgeRequestId"], bound | {"claimId": "different-native-claim"})
        with self.assertRaises(BridgeError):
            self.gateway.frame(req["bridgeRequestId"], self.frame(bound, 1, 99, b"overlap"))
    def test_failure_freezes_new_cli_retry_query_never_requeues_or_claims(self):
        req, bound, result, worker = self.pending()
        self.gateway.failure(req["bridgeRequestId"], bound)
        query = {key: bound[key] for key in ("leaseId", "generation", "requestDigest")}
        self.assertEqual("UNKNOWN", self.gateway.query(req["bridgeRequestId"], query)["state"])
        self.assertEqual("UNKNOWN", self.gateway.query(req["bridgeRequestId"], query)["state"])
        self.assertEqual(1, len(self.gateway.requests))
        with self.assertRaises(BridgeError):
            self.gateway.poll(self.lease["leaseId"], {"generation": self.lease["generation"], "waitMs": "0"})
        worker.join(3); self.assertFalse(worker.is_alive()); self.assertEqual(503, result["status"])
        self.assertNotIn(ACCESS, json.dumps(list(self.manager.ledger.execute("SELECT descriptor FROM gateway_leases"))))
    def test_revocation_removes_actual_t04_endpoint_no_new_claim_and_no_revival(self):
        req, bound, _, _ = self.pending()
        self.assertIn(self.lease["leaseId"], self.manager.harness_configs.endpoints)
        self.gateway.cancel(self.lease["leaseId"], {"generation": self.lease["generation"], "operationId": "cancel-original"})
        self.assertNotIn(self.lease["leaseId"], self.manager.harness_configs.endpoints)
        with self.assertRaises(BridgeError):self.gateway.claim(req["bridgeRequestId"], bound)
        with self.assertRaises(BridgeError):self.gateway.register(self.lease, self.base)
        self.assertNotIn(ACCESS,self.manager.harness_configs.redact("late revoked echo "+ACCESS))
        self.assertGreaterEqual(self.manager.harness_configs.redaction_tail(),len(ACCESS)-1)
    def test_protocol_scope_and_three_group_markers_cannot_become_private_authority(self):
        for marker in ({"inferenceAudience": "EXTERNAL_GROUP"}, {"groupInputSource": None}, {"externalGroupApiMode": None}):
            with self.assertRaises(BridgeError):self.gateway.register(self.lease | marker, self.base)
        with self.assertRaises(BridgeError):self.gateway.register(self.lease | {"audience": "EXTERNAL_GROUP"}, self.base)
        with self.assertRaises(BridgeError):self.gateway.register(self.lease | {"accessToken": TOKEN}, self.base)
    def test_body_independent_limit_and_offset_exact_bytes(self):
        raw = ('{"input":"' + 'a' * 100000 + '"}').encode()
        req, bound, _, _ = self.pending(body=raw)
        query = {key: bound[key] for key in ("leaseId", "generation", "requestDigest")}
        read = bytearray()
        while len(read) < len(raw):
            part = self.gateway.query(req["bridgeRequestId"], query | {"offset": str(len(read))}, True)
            self.assertEqual(len(read), part["offset"]); value = base64.b64decode(part["body"]); self.assertLessEqual(len(value), MAX_FRAME); read.extend(value)
        self.assertEqual(raw, read); self.assertEqual(hashlib.sha256(raw).hexdigest(), req["requestDigest"])
    def test_backpressure_is_real_slow_socket_then_drains_with_no_lost_bytes(self):
        import socket
        raw = socket.socket(); raw.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024); raw.settimeout(5)
        raw.connect(("127.0.0.1", self.server.server_port)); self.addCleanup(raw.close)
        body = b'{"input":"slow downstream","stream":true}'
        raw.sendall(("POST /v1/responses HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + ACCESS + "\r\nContent-Type: application/json\r\nContent-Length: " + str(len(body)) + "\r\n\r\n").encode() + body)
        req = self.gateway.poll(self.lease["leaseId"], {"generation": self.lease["generation"], "waitMs": "1000"})["requests"][0]
        bound = {key: req[key] for key in ("leaseId", "generation", "requestDigest")} | {"claimId": "real-slow-socket-claim"}
        self.gateway.claim(req["bridgeRequestId"], bound)
        done = threading.Event(); failures = []
        payload = b"data: " + b"x" * (MAX_FRAME - 8) + b"\n\n"
        def produce():
            try:
                for seq in range(1, 97):
                    value = self.frame(bound, seq, 0, payload)
                    while True:
                        try:self.gateway.frame(req["bridgeRequestId"], value);break
                        except BridgeError as error:
                            if error.status != 429:raise
                self.gateway.frame(req["bridgeRequestId"], self.frame(bound, 97, 0, b"", eof=True))
            except Exception as error:failures.append(error)
            finally:done.set()
        producer = threading.Thread(target=produce, daemon=True);producer.start()
        end = time.monotonic() + 2
        while time.monotonic() < end:
            with self.gateway.changed:
                full = self.gateway.requests[req["bridgeRequestId"]]["queueBytes"] == MAX_QUEUE
            if full:break
            time.sleep(.01)
        self.assertTrue(full, "real stopped socket must fill the bounded queue")
        self.assertFalse(done.is_set())
        received = bytearray()
        while True:
            chunk = raw.recv(32768)
            if not chunk:break
            received.extend(chunk)
        self.assertTrue(done.wait(2));self.assertEqual([],failures)
        self.assertEqual(payload * 96, bytes(received).split(b"\r\n\r\n",1)[1])
        self.assertEqual(MAX_QUEUE,self.gateway.requests[req["bridgeRequestId"]]["highWater"])
    def test_eof_ack_is_not_a_flush_receipt_and_late_socket_failure_freezes_new_requests(self):
        import socket
        raw=socket.socket();raw.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024);raw.settimeout(5);raw.connect(("127.0.0.1",self.server.server_port));self.addCleanup(raw.close)
        body=b'{"input":"late EOF downstream","stream":true}'
        raw.sendall(("POST /v1/responses HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+ACCESS+"\r\nContent-Type: application/json\r\nContent-Length: "+str(len(body))+"\r\n\r\n").encode()+body)
        req=self.gateway.poll(self.lease["leaseId"],{"generation":self.lease["generation"],"waitMs":"1000"})["requests"][0]
        bound={key:req[key] for key in ("leaseId","generation","requestDigest")}|{"claimId":"late-eof-native-claim"};self.gateway.claim(req["bridgeRequestId"],bound)
        payload=b"data: "+b"x"*(MAX_FRAME-8)+b"\n\n";seq=1
        while True:
            try:self.gateway.frame(req["bridgeRequestId"],self.frame(bound,seq,0,payload));seq+=1
            except BridgeError as error:
                self.assertEqual(429,error.status);break
            with self.gateway.changed:
                if self.gateway.requests[req["bridgeRequestId"]]["queueBytes"]==MAX_QUEUE:break
        self.gateway.frame(req["bridgeRequestId"],self.frame(bound,seq,0,b"",eof=True))
        self.assertEqual("FINISHING",self.gateway.requests[req["bridgeRequestId"]]["state"])
        raw.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,__import__('struct').pack('ii',1,0));raw.close()
        end=time.monotonic()+3
        while self.gateway.requests[req["bridgeRequestId"]]["state"]!="UNKNOWN" and time.monotonic()<end:time.sleep(.01)
        # Observe the complete production transition, not a midpoint while
        # SQLite releases the GIL inside the same changed monitor.
        with self.gateway.changed:
            self.assertEqual("UNKNOWN",self.gateway.requests[req["bridgeRequestId"]]["state"])
            self.assertEqual("REQUIRES_RECONCILE",self.gateway.leases[self.lease["leaseId"]]["state"])
            self.assertNotIn(self.lease["leaseId"],self.manager.harness_configs.endpoints)
    def test_tls_remote_control_uses_same_manager_managed_loopback_listener_and_reclaims_it(self):
        # The actual original fixture stays bound to loopback. Only its address descriptor here
        # models the TLS/non-loopback branch; this test never exposes an external listener.
        from types import SimpleNamespace
        self.gateway.cancel(self.lease["leaseId"], {"generation": self.lease["generation"], "operationId": "cancel-old"})
        server = SimpleNamespace(socket=self.server.socket, server_address=("0.0.0.0", self.server.server_port))
        new = self.lease | {"leaseId": "managed-local-lease", "expiresAt": self.manager.now() + 2000}
        value = self.gateway.register(new, lambda: self.gateway.endpoint(server))
        child = self.gateway.listener
        self.assertIsNotNone(child);self.assertIs(self.manager,child.manager);self.assertTrue(child.gateway_only);self.assertEqual("127.0.0.1",child.server_address[0])
        conn=http.client.HTTPConnection("127.0.0.1",child.server_port,timeout=2)
        conn.request("GET","/v1/status",headers={"Authorization":"Bearer "+TOKEN})
        self.assertEqual(404,conn.getresponse().status);conn.close()
        self.gateway.cancel(new["leaseId"], {"generation": new["generation"], "operationId": "cancel-managed"})
        self.assertIsNone(self.gateway.listener);self.assertEqual(-1,child.socket.fileno())
    def test_restart_keeps_unknown_never_recreates_live_lease_or_body(self):
        req, bound, _, _ = self.pending(); self.gateway.close()
        from harness_gateway import HarnessGateway
        restarted = HarnessGateway(self.manager)
        try:
            self.assertEqual("UNKNOWN", self.manager.ledger.execute("SELECT state FROM gateway_requests WHERE request_id=?", (req["bridgeRequestId"],)).fetchone()[0])
            with self.assertRaises(BridgeError):restarted.register(self.lease, self.base)
            with self.assertRaises(BridgeError):restarted.query(req["bridgeRequestId"], {key: bound[key] for key in ("leaseId", "generation", "requestDigest")})
        finally:restarted.close()
