"""Bounded transport on the original Companion. No model codec, provider key or task authority.

Only the paired native owner registers a descriptor; the CLI credential grants inference
transport only. SQLite rows are transport receipts in the original operations database,
not another task/budget ledger. Restart never re-creates a socket or re-queues a request.
"""
from __future__ import annotations
import base64
from collections import deque
import hashlib
import hmac
import ipaddress
import json
import re
import select
import socket
import threading
import time
import uuid

MAX_BODY = 2 * 1024 * 1024
MAX_FRAME = 16 * 1024
MAX_QUEUE = 64 * 1024
MAX_REQUESTS = 128
MAX_FRAMES = 16384
ACTIVE = {"QUEUED", "CLAIMED", "FINISHING"}
FIELDS = {"leaseId", "clientBindingId", "generation", "environmentId", "environmentBinding", "bindingId", "profileDigest", "taskId", "runId", "epoch", "audience", "purpose", "expiresAt", "accessToken", "protocols"}
ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")

class BridgeError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status

def ident(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise BridgeError("Exact bridge identity required")
    return value

def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise BridgeError("Bridge integer is out of bounds")
    return value

def sha(value):
    return hashlib.sha256(value).hexdigest()

class HarnessGateway:
    def __init__(self, manager):
        self.manager = manager
        self.db = manager.ledger
        self.changed = threading.Condition(manager.lock)
        self.leases = {}
        self.requests = {}
        self.body_slots = threading.BoundedSemaphore(2)
        self.listener = None
        self.listener_thread = None
        self.listener_factory = None
        self.stop_watch = threading.Event()
        self.watch = threading.Thread(target=self._watch, daemon=True, name="pocket-gateway-leases")
        self.watch.start()
        self.db.execute("CREATE TABLE IF NOT EXISTS gateway_leases(lease_id TEXT PRIMARY KEY, descriptor TEXT NOT NULL, state TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS gateway_requests(request_id TEXT PRIMARY KEY, lease_id TEXT NOT NULL, digest TEXT NOT NULL, state TEXT NOT NULL, claim_id TEXT, metadata TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS gateway_frames(request_id TEXT NOT NULL,event_seq INTEGER NOT NULL,byte_offset INTEGER NOT NULL,fingerprint TEXT NOT NULL, PRIMARY KEY(request_id,event_seq,byte_offset))")
        # Lost live sockets/body/credentials are unknowable, never restartable work.
        self.db.execute("UPDATE gateway_requests SET state='UNKNOWN' WHERE state IN ('QUEUED','CLAIMED','FINISHING')")
        self.db.execute("UPDATE gateway_leases SET state='REQUIRES_RECONCILE' WHERE state='ACTIVE'")
        self.db.commit()

    def _watch(self):
        while not self.stop_watch.wait(.1):
            try:
                with self.changed:
                    for lease in list(self.leases.values()):
                        self._lease(lease["leaseId"], active=False)
                self._close_idle_listener()
            except (BridgeError, OSError, ValueError):
                pass

    def endpoint(self, server):
        import ssl
        if not isinstance(server.socket, ssl.SSLSocket) and ipaddress.ip_address(server.server_address[0]).is_loopback:
            return "http://127.0.0.1:" + str(server.server_address[1])
        with self.changed:
            if self.listener is None:
                if self.listener_factory is None:
                    raise BridgeError("Original Companion loopback listener is unavailable", 503)
                self.listener = self.listener_factory()
                self.listener_thread = threading.Thread(target=self.listener.serve_forever, kwargs={"poll_interval": .1}, daemon=True, name="pocket-gateway-loopback")
                self.listener_thread.start()
            return "http://127.0.0.1:" + str(self.listener.server_address[1])

    def _close_idle_listener(self):
        with self.changed:
            if any(x["state"] == "ACTIVE" for x in self.leases.values()):
                return
            listener, self.listener = self.listener, None
        if listener is not None:
            listener.shutdown()
            listener.server_close()

    @staticmethod
    def capability():
        return {"version": 1, "maxBodyBytes": MAX_BODY, "maxFrameBytes": MAX_FRAME,
                "maxQueueBytes": MAX_QUEUE, "maxPollMs": 10000, "maxConcurrent": 2,
                "apiOnly": True, "toolExecution": False, "socketResume": False,
                "registrationAuthority": "original_paired_native_owner"}

    def bind_job(self, lease_id, job, original):
        with self.changed:
            if lease_id not in self.leases and not self.db.execute("SELECT 1 FROM gateway_leases WHERE lease_id=?", (lease_id,)).fetchone():
                # Existing T04 configuration-only native endpoints are not an active bridge.
                # Preserve their config transaction contract; no CLI token can reach this bridge.
                return False
            lease = self._lease(lease_id)
            if "configurationOwner" in lease:
                owner = lease["configurationOwner"]
                expected = owner | {"leaseId": lease["leaseId"], "generation": lease["generation"]}
                if original.get("gatewayOwnerBinding") != expected or any(original.get(k) != owner[k] for k in ("taskId", "runId", "epoch")) or original.get("bindingId") != lease["bindingId"]:
                    raise BridgeError("Original driver job changed registered native configuration owner", 403)
            elif "gatewayOwnerBinding" in original:
                raise BridgeError("Unregistered parent/child binding cannot authorize a job", 403)
            else:
                for key in ("taskId", "runId", "epoch"):
                    if lease[key] != original.get(key):
                        raise BridgeError("Original driver job does not match the native gateway owner", 403)
            for key in ("profileDigest", "environmentBinding"):
                if lease[key] != original.get(key):
                    raise BridgeError("Original driver job changed gateway Profile or environment", 403)
            if lease.get("jobId") not in (None, job["id"]):
                raise BridgeError("Original gateway lease already belongs to another job", 409)
            if job["environmentId"] != lease["environmentId"]:
                raise BridgeError("Original gateway job target changed", 409)
            lease["jobId"] = job["id"]
            self._persist_lease(lease)
            return True

    def job_finished(self, lease_id, job_id):
        with self.changed:
            lease = self.leases.get(lease_id)
            if lease is not None:
                if lease.get("jobId") != job_id:
                    raise BridgeError("Cannot revoke a different original job's gateway", 409)
                if lease["state"] == "ACTIVE":
                    self._cancel(lease, "REVOKED")
        self._close_idle_listener()
        return lease is not None

    def _persist_lease(self, lease):
        public = {k: v for k, v in lease.items() if k in (FIELDS - {"accessToken"}) | {"jobId", "configurationOwner"}}
        self.db.execute("INSERT OR REPLACE INTO gateway_leases VALUES(?,?,?)", (lease["leaseId"], json.dumps(public, sort_keys=True), lease["state"]))
        self.db.commit()

    def _persist_request(self, req):
        metadata = {k: req[k] for k in ("path", "clientBindingId", "generation", "receivedAt", "bodyBytes")}
        self.db.execute("INSERT OR REPLACE INTO gateway_requests VALUES(?,?,?,?,?,?)", (req["bridgeRequestId"], req["leaseId"], req["requestDigest"], req["state"], req["claimId"], json.dumps(metadata)))
        self.db.commit()

    def _lease(self, lease_id, generation=None, active=True):
        lease = self.leases.get(ident(lease_id))
        if lease is None:
            raise BridgeError("Original live bridge lease is unavailable; no request was re-queued", 410)
        if generation is not None and lease["generation"] != generation:
            raise BridgeError("Bridge lease generation changed", 409)
        if lease["state"] == "ACTIVE" and (self.manager.closed or lease["expiresAt"] <= self.manager.now() or self.manager.environment_binding(lease["environmentId"]) != lease["environmentBinding"]):
            self._cancel(lease, "EXPIRED" if lease["expiresAt"] <= self.manager.now() else "REVOKED")
        if active and lease["state"] != "ACTIVE":
            raise BridgeError("Bridge lease is not active; prior unknown work was retained", 409)
        return lease

    def register(self, value, endpoint):
        if set(value) not in (FIELDS, FIELDS | {"configurationOwner"}):
            raise BridgeError("Bridge accepts only the exact original native lease descriptor")
        lease = dict(value)
        for key in ("leaseId", "clientBindingId", "generation", "environmentId", "bindingId", "taskId", "runId"):
            ident(lease[key])
        integer(lease["epoch"], 1, 2**31 - 1)
        if "configurationOwner" in lease:
            owner = lease["configurationOwner"]
            if not isinstance(owner, dict) or set(owner) != {"taskId", "runId", "epoch", "parentTaskId", "parentRunId", "parentEpoch", "sourceDigest"}:
                raise BridgeError("Exact native configuration owner relation required", 403)
            for key in ("taskId", "runId", "parentTaskId", "parentRunId"):
                ident(owner[key])
            # Original remote children start at epoch 0; the native billable parent starts at 1.
            integer(owner["epoch"], 0, 2**31 - 1)
            integer(owner["parentEpoch"], 1, 2**31 - 1)
            if any(owner[k] != lease[p] for k, p in (("parentTaskId", "taskId"), ("parentRunId", "runId"), ("parentEpoch", "epoch"))) or not isinstance(owner["sourceDigest"], str) or not re.fullmatch("[a-f0-9]{64}", owner["sourceDigest"]):
                raise BridgeError("Configuration owner is not bound to the registered parent lease", 403)

        integer(lease["expiresAt"], self.manager.now() + 1, self.manager.now() + 600000)
        if not isinstance(lease["profileDigest"], str) or not re.fullmatch("[a-f0-9]{64}", lease["profileDigest"]):
            raise BridgeError("Frozen Profile digest required")
        if lease["audience"] != "OWNER_PRIVATE" or lease["purpose"] != "EXTERNAL_RELAY":
            raise BridgeError("Gateway access cannot establish APP_OWNER or external-group billing authority", 403)
        if not isinstance(lease["accessToken"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", lease["accessToken"]):
            raise BridgeError("Bounded native gateway access credential required")
        if hmac.compare_digest(lease["accessToken"].encode(), self.manager.token.encode()):
            raise BridgeError("Gateway access and paired Companion control credentials must be distinct", 403)
        if not isinstance(lease["protocols"], list) or not lease["protocols"] or len(set(lease["protocols"])) != len(lease["protocols"]) or not set(lease["protocols"]) <= {"openai_responses", "anthropic", "openai_chat"}:
            raise BridgeError("Explicit supported ingress protocols required")
        with self.changed:
            if self.manager.closed or self.manager.environment_binding(lease["environmentId"]) != lease["environmentBinding"]:
                raise BridgeError("Original environment generation changed", 409)
            old = self.db.execute("SELECT descriptor,state FROM gateway_leases WHERE lease_id=?", (lease["leaseId"],)).fetchone()
            if old:
                current = self.leases.get(lease["leaseId"])
                if current is None or any(current.get(k) != lease.get(k) for k in FIELDS | {"configurationOwner"}):
                    raise BridgeError("Lease identity cannot be rebound or revived after restart", 409)
                self._lease(lease["leaseId"])
                return self._view_lease(current)
            if len(self.leases) >= 16 or any(x["state"] == "ACTIVE" and x["environmentBinding"] == lease["environmentBinding"] and x["bindingId"] == lease["bindingId"] for x in self.leases.values()):
                raise BridgeError("Original target already owns a live gateway lease", 409)
            endpoint = endpoint() if callable(endpoint) else endpoint
            lease.update(state="ACTIVE", endpoint=endpoint)
            # This also binds T04's endpointLeaseRef to this actual host-issued lifecycle.
            self.manager.harness_configs.register_endpoint({k: lease[k] for k in ("leaseId", "accessToken", "expiresAt", "environmentBinding", "profileDigest", "taskId", "runId", "epoch", "audience", "purpose")} | {"endpoint": endpoint} | ({"configurationOwner": lease["configurationOwner"], "generation": lease["generation"], "bindingId": lease["bindingId"]} if "configurationOwner" in lease else {}))
            self.leases[lease["leaseId"]] = lease
            self._persist_lease(lease)
            return self._view_lease(lease)

    def _view_lease(self, lease):
        return {k: lease[k] for k in ("leaseId", "generation", "clientBindingId", "state", "endpoint", "expiresAt")} | {"gatewayBridge": self.capability(), "runtimeVerified": False}

    def _cancel(self, lease, state):
        lease["state"] = state
        self.manager.harness_configs.endpoints.pop(lease["leaseId"], None)
        for req in self.requests.values():
            if req["leaseId"] == lease["leaseId"] and req["state"] in ACTIVE:
                req["state"] = "UNKNOWN" if req["claimId"] else "CANCELLED"
                self._persist_request(req)
        self._persist_lease(lease)
        self.changed.notify_all()

    def cancel(self, lease_id, value):
        if set(value) != {"generation", "operationId"}:
            raise BridgeError("Cancellation cannot replace scope")
        ident(value["operationId"])
        with self.changed:
            lease = self._lease(lease_id, value["generation"], False)
            if lease["state"] == "ACTIVE":
                self._cancel(lease, "REVOKED")
            result = self._view_lease(lease)
        self._close_idle_listener()
        return result

    def _request(self, request_id, value):
        req = self.requests.get(ident(request_id))
        if req is None:
            row = self.db.execute("SELECT state FROM gateway_requests WHERE request_id=?", (request_id,)).fetchone()
            raise BridgeError("Original live request unavailable; durable state=" + (row[0] if row else "absent") + "; never re-queued", 410)
        for key in ("leaseId", "generation", "requestDigest"):
            if value.get(key) != req[key]:
                raise BridgeError("Not the exact original bridge request", 409)
        self._lease(req["leaseId"], req["generation"], False)
        return req

    def _view(self, req):
        return {k: req[k] for k in ("bridgeRequestId", "leaseId", "generation", "clientBindingId", "requestDigest", "path", "state", "claimId", "bodyBytes", "acceptedThrough")} | {"clientAlive": req["clientAlive"], "resubmitted": False}

    def poll(self, lease_id, query):
        if set(query) != {"generation", "waitMs"}:
            raise BridgeError("Poll requires exact generation and bounded wait")
        try:
            wait = int(query["waitMs"])
        except (TypeError, ValueError):
            raise BridgeError("Invalid poll wait")
        integer(wait, 0, 10000)
        end = time.monotonic() + wait / 1000
        with self.changed:
            while True:
                lease = self._lease(lease_id, query["generation"])
                pending = [self._view(x) for x in self.requests.values() if x["leaseId"] == lease_id and x["state"] in {"QUEUED", "CLAIMED"}]
                if pending or time.monotonic() >= end:
                    return {"leaseId": lease_id, "generation": lease["generation"], "requests": pending}
                self.changed.wait(min(.25, end - time.monotonic()))

    def query(self, request_id, value, body=False):
        expected = {"leaseId", "generation", "requestDigest"} | ({"offset"} if body else set())
        if set(value) != expected:
            raise BridgeError("Query accepts only the original exact request binding")
        with self.changed:
            req = self._request(request_id, value)
            result = self._view(req)
            if body:
                try:
                    offset = int(value["offset"])
                except (TypeError, ValueError):
                    raise BridgeError("Invalid original body offset")
                integer(offset, 0, req["bodyBytes"])
                if not req["body"]:
                    raise BridgeError("Original live body is no longer available; never re-queued", 410)
                raw = req["body"][offset:offset + MAX_FRAME]
                result.update(body=base64.b64encode(raw).decode("ascii"), offset=offset, eof=offset + len(raw) == req["bodyBytes"])
            return result

    def claim(self, request_id, value):
        if set(value) != {"leaseId", "generation", "requestDigest", "claimId"}:
            raise BridgeError("Exact native claim required")
        ident(value["claimId"])
        with self.changed:
            req = self._request(request_id, value)
            self._lease(req["leaseId"], req["generation"])
            if req["claimId"] not in (None, value["claimId"]):
                raise BridgeError("Original request already has a different native claim", 409)
            if req["state"] not in ACTIVE or not req["clientAlive"]:
                raise BridgeError("Original client is no longer waiting; no dispatch", 409)
            req.update(claimId=value["claimId"], state="CLAIMED")
            self._persist_request(req)
            return self._view(req)

    def frame(self, request_id, value):
        allowed = {"leaseId", "generation", "requestDigest", "claimId", "eventSeq", "offset", "payload", "isLastFragment", "eof", "status", "contentType"}
        if set(value) != allowed or type(value["isLastFragment"]) is not bool or type(value["eof"]) is not bool:
            raise BridgeError("Exact bounded byte frame required")
        seq = integer(value["eventSeq"], 1, MAX_FRAMES)
        offset = integer(value["offset"], 0, 8 * 1024 * 1024)
        try:
            payload = base64.b64decode(value["payload"], validate=True)
        except (ValueError, TypeError):
            raise BridgeError("Frame payload must be base64 bytes")
        if len(payload) > MAX_FRAME or offset + len(payload) > 8 * 1024 * 1024 or (value["eof"] and (payload or not value["isLastFragment"])):
            raise BridgeError("Frame exceeds the byte limit or invalid EOF", 413)
        if value["status"] not in (200, 400, 403, 409, 422, 429, 500, 502, 503, 504) or value["contentType"] not in ("text/event-stream; charset=utf-8", "application/json; charset=utf-8"):
            raise BridgeError("Response metadata is not allowlisted")
        fingerprint = sha(json.dumps(value, sort_keys=True, separators=(",", ":")).encode())
        end = time.monotonic() + 2
        with self.changed:
            req = self._request(request_id, value)
            if value["claimId"] != req["claimId"] or req["claimId"] is None:
                raise BridgeError("Frame does not belong to the original native claim", 409)
            prior = self.db.execute("SELECT fingerprint FROM gateway_frames WHERE request_id=? AND event_seq=? AND byte_offset=?", (request_id, seq, offset)).fetchone()
            if prior:
                if prior[0] != fingerprint:
                    raise BridgeError("Same full fragment identity has different bytes", 409)
                return {"acceptedThrough": {"eventSeq": seq, "offset": offset}, "duplicate": True, "transportOnly": True}
            while req["queueBytes"] + len(payload) > MAX_QUEUE:
                self._lease(req["leaseId"], req["generation"])
                if req["state"] != "CLAIMED" or not req["clientAlive"]:
                    raise BridgeError("Original response stream has stopped", 409)
                if time.monotonic() >= end:
                    raise BridgeError("Response window full; exact unaccepted frame may be retried within the same deadline", 429)
                self.changed.wait(min(.1, end - time.monotonic()))
            self._lease(req["leaseId"], req["generation"])
            if req["state"] != "CLAIMED" or not req["clientAlive"]:
                raise BridgeError("Original response stream has stopped", 409)
            if (seq, offset) != req["next"]:
                raise BridgeError("Non-contiguous, overlapping or skipped fragment", 409)
            if req["headers"] is not None and req["headers"] != (value["status"], value["contentType"]):
                raise BridgeError("Response metadata changed mid-stream", 409)
            self.db.execute("INSERT INTO gateway_frames VALUES(?,?,?,?)", (request_id, seq, offset, fingerprint))
            self.db.commit()  # ACK durability precedes exposing the fragment exactly once.
            req["body"] = b""  # native decoding/claim already completed before its first output
            req["headers"] = (value["status"], value["contentType"])
            req["queue"].append((payload, value["eof"]))
            req["queueBytes"] += len(payload)
            req["highWater"] = max(req["highWater"], req["queueBytes"])
            req["acceptedThrough"] = {"eventSeq": seq, "offset": offset}
            req["next"] = (seq + 1, 0) if value["isLastFragment"] else (seq, offset + len(payload))
            if value["eof"]:
                req["state"] = "FINISHING"
                self._persist_request(req)
            self.changed.notify_all()
            return {"acceptedThrough": req["acceptedThrough"], "duplicate": False, "transportOnly": True}

    def failure(self, request_id, value):
        if set(value) != {"leaseId", "generation", "requestDigest", "claimId"}:
            raise BridgeError("Failure requires original claim binding")
        with self.changed:
            req = self._request(request_id, value)
            if req["claimId"] != value["claimId"]:
                raise BridgeError("Failure claim mismatch", 409)
            if req["state"] != "COMPLETE":
                req["state"] = "UNKNOWN"
                self._persist_request(req)
                self._cancel(self.leases[req["leaseId"]], "REQUIRES_RECONCILE")
            return self._view(req)

    def client(self, handler):
        """Raw CLI API entry on this same original server; paired APIs cannot use this token."""
        if not ipaddress.ip_address(handler.client_address[0]).is_loopback or "Origin" in handler.headers or handler.headers.get("Transfer-Encoding") or handler.headers.get("Content-Encoding"):
            raise BridgeError("Gateway inference accepts only an uncompressed loopback native client", 403)
        if len(handler.headers.get_all("Authorization", [])) > 1 or len(handler.headers.get_all("x-api-key", [])) > 1 or len(handler.headers.get_all("Content-Length", [])) != 1:
            raise BridgeError("Duplicate or missing gateway framing/authentication headers", 400)
        auth, api = handler.headers.get("Authorization"), handler.headers.get("x-api-key")
        supplied = auth[7:] if auth and auth.startswith("Bearer ") and not api else api if api and not auth else ""
        with self.changed:
            matches = [x for x in self.leases.values() if hmac.compare_digest(x["accessToken"].encode(), supplied.encode())]
            if len(matches) != 1:
                raise BridgeError("Invalid gateway access credential", 401)
            lease = self._lease(matches[0]["leaseId"])
            protocol = {"/v1/responses": "openai_responses", "/v1/messages": "anthropic", "/v1/chat/completions": "openai_chat"}.get(handler.path)
            if protocol not in lease["protocols"]:
                raise BridgeError("Ingress protocol is not in the native lease", 403)
            if sum(x["state"] in ACTIVE for x in self.requests.values()) >= 2 or any(x["leaseId"] == lease["leaseId"] and x["state"] in ACTIVE for x in self.requests.values()):
                raise BridgeError("Original bounded inference slot is occupied", 429)
            if len(self.requests) >= MAX_REQUESTS:
                raise BridgeError("Bridge transport history bound reached; close this original session", 429)
            length = handler.headers.get("Content-Length", "")
            if not length.isdigit() or not 0 < int(length) <= MAX_BODY:
                raise BridgeError("Gateway request must have a bounded Content-Length <= 2MiB", 413)
            if handler.headers.get_content_type() != "application/json":
                raise BridgeError("Gateway request must be JSON", 415)
        if not self.body_slots.acquire(blocking=False):
            raise BridgeError("Original bounded body slots are occupied", 429)
        try:
            handler.connection.settimeout(10)
            handler.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, MAX_FRAME)
            body = handler.rfile.read(int(length))
            if len(body) != int(length):
                raise BridgeError("Incomplete request body")
            with self.changed:
                self._lease(lease["leaseId"])
                if sum(x["state"] in ACTIVE for x in self.requests.values()) >= 2 or any(x["leaseId"] == lease["leaseId"] and x["state"] in ACTIVE for x in self.requests.values()):
                    raise BridgeError("Original bounded inference slot is occupied", 429)
                req = {"bridgeRequestId": str(uuid.uuid4()), "leaseId": lease["leaseId"], "generation": lease["generation"], "clientBindingId": lease["clientBindingId"], "requestDigest": sha(body), "path": handler.path, "state": "QUEUED", "claimId": None, "body": body, "bodyBytes": len(body), "receivedAt": self.manager.now(), "acceptedThrough": None, "next": (1, 0), "queue": deque(), "queueBytes": 0, "highWater": 0, "headers": None, "clientAlive": True}
                self.requests[req["bridgeRequestId"]] = req
                self._persist_request(req)
                self.changed.notify_all()
        finally:
            self.body_slots.release()
        body = b""  # only the bounded original request cache retains the body after admission
        started = False
        handler.close_connection = True
        try:
            while True:
                with self.changed:
                    self._lease(lease["leaseId"], active=False)
                    if req["queue"]:
                        payload, eof = req["queue"].popleft()
                        req["queueBytes"] -= len(payload)
                        self.changed.notify_all()
                    elif req["state"] not in ACTIVE:
                        raise BridgeError("Original response is interrupted; not replayed", 503)
                    else:
                        self.changed.wait(.1)
                        if select.select([handler.connection], [], [], 0)[0] and not handler.connection.recv(1, socket.MSG_PEEK):
                            raise ConnectionResetError("original client disconnected")
                        continue
                    headers = req["headers"]
                if not started:
                    handler.send_response(headers[0])
                    handler.send_header("Content-Type", headers[1])
                    handler.send_header("Connection", "close")
                    handler.send_header("Cache-Control", "no-store")
                    handler.send_header("X-Pocket-Bridge-Request", req["bridgeRequestId"])
                    handler.end_headers()
                    started = True
                if payload:
                    handler.wfile.write(payload)
                    handler.wfile.flush()
                if eof:
                    with self.changed:
                        if req["state"] != "FINISHING":
                            raise BridgeError("Original final flush was revoked; not a complete stream", 409)
                        req["state"] = "COMPLETE"
                        self._persist_request(req)
                        self.changed.notify_all()
                    return
        except (OSError, BridgeError):
            with self.changed:
                if req["state"] != "COMPLETE":
                    req["state"] = "UNKNOWN" if req["claimId"] else "CANCELLED"
                    self._persist_request(req)
                    if req["claimId"]:
                        self._cancel(lease, "REQUIRES_RECONCILE")
            if not started:
                handler._send(503, {"error": {"type": "gateway_interrupted", "message": "Original inference stream unavailable; not automatically replayed"}})
        finally:
            with self.changed:
                req["clientAlive"] = False
                req["body"] = b""
                req["queue"].clear()
                req["queueBytes"] = 0
                self.changed.notify_all()

    def redact(self, value):
        for lease in list(self.leases.values()):
            value = value.replace(lease["accessToken"], "[gateway credential hidden]")
        return value

    def redaction_tail(self):
        return max([0, *(len(lease["accessToken"]) - 1 for lease in self.leases.values())])

    def close(self):
        self.stop_watch.set()
        with self.changed:
            for lease in self.leases.values():
                if lease["state"] == "ACTIVE":
                    self._cancel(lease, "REVOKED")
        self._close_idle_listener()


def route(manager, parts, post, value, query, endpoint):
    gateway = manager.harness_gateway
    if parts == ["v1", "gateway", "leases"] and post:
        return gateway.register(value, endpoint)
    if len(parts) == 4 and parts[:3] == ["v1", "gateway", "leases"] and not post:
        if set(query) != {"generation"}:
            raise BridgeError("Exact original lease generation required")
        with gateway.changed:
            return gateway._view_lease(gateway._lease(parts[3], query["generation"], False))
    if len(parts) == 5 and parts[:3] == ["v1", "gateway", "leases"]:
        if parts[4] == "requests" and not post:
            return gateway.poll(parts[3], query)
        if parts[4] == "cancel" and post:
            return gateway.cancel(parts[3], value)
    if len(parts) in (4, 5) and parts[:3] == ["v1", "gateway", "requests"]:
        if len(parts) == 4 and not post:
            return gateway.query(parts[3], query)
        if len(parts) == 5:
            if parts[4] == "body" and not post:
                return gateway.query(parts[3], query, True)
            if parts[4] == "claim" and post:
                return gateway.claim(parts[3], value)
            if parts[4] == "frames" and post:
                return gateway.frame(parts[3], value)
            if parts[4] == "failure" and post:
                return gateway.failure(parts[3], value)
    raise BridgeError("Unknown original bridge endpoint", 404)
