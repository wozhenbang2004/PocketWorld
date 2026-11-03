"""Thin owner-local phone task surface over the existing paired byte service.

The original operations ledger records dispatch identity only. Phone TaskStore owns
execution, waiting, cancellation and results. Neither reconnect nor duplicate calls
send an earlier mutation again. Cached source receipts never contain goal/result text.
"""
from __future__ import annotations

import asyncio
import concurrent.futures
import copy
import hashlib
import json
import uuid

import device_identity as identity
import device_protocol as wire

PROTOCOL = "pocketworld.phone-task-dispatch.v1"
SETTLED = {"succeeded", "failed", "cancelled", "interrupted", "expired"}
WAITING = {"waiting_approval", "waiting_dependency"}


class PhoneTaskError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class PhoneTaskRouter:
    def __init__(self, service):
        self.service, self.manager = service, service.manager
        self.connections, self.pending, self.usb = {}, {}, {}
        self.closed = False
        # Existing SQL only. A restart cannot confirm a prepared control write.
        with self.manager.lock:
            rows = self.manager.ledger.execute("SELECT operation_id,details FROM operations WHERE details IS NOT NULL").fetchall()
            for operation_id, raw in rows:
                value = json.loads(raw)
                if value.get("protocol") in {PROTOCOL, "pocketworld.usb-forward.v1"} and value.get("status") in {"dispatch_prepared", "usb_connected"}:
                    value.update(status="unknown", effectState="unknown")
                    self._save(operation_id, value)

    def connection_opened(self, connection, peer):
        if connection.lane == "control" and isinstance(peer, identity.AuthenticatedPeer):
            # Retire this surface's old generation without closing a shared
            # channel which may still carry the opposite-direction service.
            for old, previous in list(self.connections.items()):
                if previous.device_id == peer.device_id and old is not connection:
                    self.connection_closed(old)
            self.connections[connection] = peer

    def connection_closed(self, connection):
        self.connections.pop(connection, None)
        for request_id, (actual, _, _, future) in list(self.pending.items()):
            if actual is connection and not future.done():
                future.set_exception(PhoneTaskError("Phone link closed; query the original operation, never resubmit", 503))

    def receive(self, connection, peer, raw):
        """Called by the original service loop after its current pairing check."""
        value = wire.parse_json_object(raw)
        if value.get("messageType") not in {"DeviceResponse", "DeviceError"}:
            return False
        item = self.pending.get(value.get("requestId"))
        if item is None:
            return True  # Expired response is not an inbound command or a retry trigger.
        actual, expected_peer, request, future = item
        if actual is not connection or expected_peer is not peer or future.done():
            return True
        try:
            result = wire.validate_message(value)
            if result["messageType"] == "DeviceError":
                # Error frames have no claimed method/device/instance fields.
                # The exact authenticated channel and requestId bind this reply.
                future.set_result(result)
                return True
            if (result.get("method") != request.method
                    or result.get("executorDeviceId") != peer.device_id
                    or result.get("instanceId") != request.get("instanceId")):
                raise PhoneTaskError("Response does not belong to the original paired request", 502)
            if result["messageType"] == "DeviceResponse":
                payload = result["result"]
                if request.get("operationId") and (payload.get("operationId") != request.get("operationId")
                        or payload.get("argumentsDigest") != request.get("argumentsDigest")):
                    raise PhoneTaskError("Response changed the original operation identity", 502)
                if payload.get("accepted"):
                    audience = payload["audience"]
                    for name in ("originDeviceId", "callerDeviceId", "targetDeviceId", "instanceId", "grantId", "rootTaskId", "localTaskId"):
                        if request.get(name) is not None and audience.get(name) != request.get(name):
                            raise PhoneTaskError("Response changed the original accepted audience", 502)
                    if not wire.resource_equal(audience.get("resourceBinding"), request.get("resourceBinding")):
                        raise PhoneTaskError("Response changed the original resource scope", 502)
                    epoch = audience["authorizationEpoch"]
                    if (request.method != "operation.get" and epoch != request.get("authorizationEpoch")
                            or request.method == "operation.get" and epoch > request.get("authorizationEpoch")):
                        raise PhoneTaskError("Response changed the original authorization generation", 502)
                if request.method == "task.get" and payload.get("taskId") != request.arguments["taskId"]:
                    raise PhoneTaskError("Task snapshot belongs to another original task", 502)
                if request.method == "operation.get" and payload.get("operationId") not in (None, request.arguments["operationId"]):
                    raise PhoneTaskError("Receipt belongs to another original operation", 502)
                if request.method == "task.events" and any(event["taskId"] != request.arguments["taskId"] for event in payload["events"]):
                    raise PhoneTaskError("Event page belongs to another original task", 502)
                if request.method == "operation.cancel":
                    cancelled = payload.get("cancellation", {})
                    if (cancelled.get("targetOperationId") != request.arguments["operationId"]
                            or cancelled.get("targetArgumentsDigest") != request.arguments["argumentsDigest"]):
                        raise PhoneTaskError("Cancellation receipt changed its original target", 502)
                if request.method in {"task.acquireControl", "task.answerQuestion", "task.steer", "task.requestResume"} and payload.get("accepted"):
                    if payload.get("localTaskId") != request.arguments["taskId"]:
                        raise PhoneTaskError("Control receipt belongs to another original task", 502)
                    if request.method in {"task.answerQuestion", "task.steer"}:
                        native = payload.get("payload", {})
                        if (native.get("inputSaved") is not True or native.get("taskId") != request.arguments["taskId"]
                                or native.get("runId") != request.arguments["runId"] or native.get("executorEpoch") != request.arguments["executorEpoch"]
                                or native.get("allocationId") != request.arguments["allocationId"] or native.get("sourceKind") != "native_paired_task_input"):
                            raise PhoneTaskError("Input receipt changed the original native scope", 502)
                    if request.method == "task.requestResume":
                        native = payload.get("payload", {})
                        if (native.get("requested") is not True or native.get("ownerActionRequired") is not True
                                or native.get("resumeRequestId") != request.get("operationId")
                                or native.get("taskId") != request.arguments["taskId"]
                                or native.get("runId") != request.arguments["runId"]
                                or native.get("executorEpoch") != request.arguments["executorEpoch"]
                                or native.get("allocationId") != request.arguments["allocationId"]
                                or native.get("requestedRevision") != request.arguments["expectedRevision"]
                                or native.get("executionAuthorized") is not False):
                            raise PhoneTaskError("Resume receipt changed the original phone review", 502)
            future.set_result(result)
        except Exception:
            future.set_exception(PhoneTaskError("Invalid original response; its outcome remains unknown", 502))
        return True

    def invoke(self, action, params):
        if not isinstance(params, dict):
            raise PhoneTaskError("Parameters must be an object")
        if self.closed or self.service.loop is None or self.service.closed:
            raise PhoneTaskError("Enable the existing paired device service first", 503)
        future = asyncio.run_coroutine_threadsafe(self.execute(action, copy.deepcopy(params)), self.service.loop)
        try:
            return future.result(timeout=58)
        except concurrent.futures.TimeoutError:
            # Stops waiting locally; never maps an RPC timeout to phone cancellation.
            raise PhoneTaskError("Reply unconfirmed; query the same original operation", 504)

    def _row(self, operation_id):
        wire.identifier(operation_id, "operationId")
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
        if row is None:
            raise PhoneTaskError("Unknown original phone operation", 404)
        value = json.loads(row[1] or "{}")
        if value.get("protocol") != PROTOCOL:
            raise PhoneTaskError("Operation belongs to another original surface", 409)
        return row[0], value

    def _save(self, operation_id, value):
        self.manager.ledger.execute("UPDATE operations SET last_status=?,updated_at=?,details=? WHERE operation_id=?",
                                    (value["status"], self.manager.now(), json.dumps(value, separators=(",", ":")), operation_id))
        self.manager.ledger.commit()

    @staticmethod
    def _public(value):
        return {key: copy.deepcopy(value[key]) for key in ("operationId", "targetOperationId", "peerDeviceId", "grantId", "status", "effectState", "taskId", "runId", "executorEpoch", "allocationId", "accepted", "updatedAt", "kind", "cancellation", "controlLeaseId", "controlLeaseExpiresAt", "inputMessageId", "inputSaved", "continuationRequested", "continuationState", "ownerActionRequired", "resumeRequestId", "requestedRevision", "reviewRevision") if key in value}

    def _connection(self, peer_id):
        wire.device_id(peer_id)
        known = self.service._paired(peer_id)
        for connection, peer in self.connections.items():
            if (peer.device_id == peer_id and peer.certificate_fingerprint == known["fingerprint"]
                    and not connection.closed and connection.state in {"online", "degraded"}):
                return connection, peer
        raise PhoneTaskError("Selected phone is offline; target remains fixed", 503)

    def _route(self, peer_id, grant_id, history=False):
        connection, peer = self._connection(peer_id)
        pairing = self.service.receiver.pairing
        if history:
            ticket = pairing.history_route(peer, grant_id, self.manager.now())
        else:
            receipt = self.service.receiver.trust.outgoing_grant(peer_id, grant_id)
            if receipt is None:
                raise PhoneTaskError("Selected phone has no current native signed grant", 403)
            ticket = pairing.accept_outgoing_grant(peer, receipt, self.manager.now())
        return connection, peer, ticket

    async def _exchange(self, connection, peer, ticket, envelope, timeout=12):
        if self.closed or len(self.pending) >= 32:
            raise PhoneTaskError("Phone request capacity is unavailable; no new request was queued", 503)
        ticket.require_target_peer(peer, self.manager.now())
        if self.connections.get(connection) is not peer:
            raise PhoneTaskError("Original authenticated channel changed", 503)
        request_id = envelope.get("requestId")
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = (connection, peer, envelope, future)
        try:
            def before_write(actual_peer):
                if self.closed or self.service.closed or actual_peer is not peer or self.connections.get(connection) is not peer:
                    raise PermissionError("Original phone channel generation changed before dispatch")
                current = self.service._paired(peer.device_id)
                if current["fingerprint"] != peer.certificate_fingerprint:
                    raise PermissionError("Original paired phone identity changed before dispatch")
                ticket.require_target_peer(peer, self.manager.now())
                if envelope.get("deadline") <= self.manager.now():
                    raise PermissionError("Original request expired before dispatch")
            if not connection.send_control(wire.canonical_bytes(envelope.json()), before_write=before_write):
                raise PhoneTaskError("Phone control lane is full; original write is not replayed", 503)
            return await asyncio.wait_for(future, timeout)
        except asyncio.TimeoutError:
            raise PhoneTaskError("Phone acknowledgement timed out; query the original operation", 504)
        finally:
            self.pending.pop(request_id, None)

    async def _read(self, value, method, object_id, options=None):
        connection, peer, route = self._route(value["peerDeviceId"], value["grantId"], True)
        ticket = route.history_query(self.service.identity, method, object_id, value["resourceBinding"], self.manager.now(), options=options)
        response = await self._exchange(connection, peer, ticket, ticket.envelope)
        if response["messageType"] == "DeviceError":
            raise PhoneTaskError("Phone denied the current original-result read", 403)
        return response["result"]

    async def _mutation(self, operation_id, semantic, connection, peer, ticket, envelope, original=None):
        fingerprint = hashlib.sha256(wire.canonical_bytes(semantic)).hexdigest()
        value = dict(protocol=PROTOCOL, operationId=operation_id, peerDeviceId=peer.device_id,
                     grantId=envelope.get("grantId"), resourceBinding=envelope.get("resourceBinding"),
                     argumentsDigest=envelope.get("argumentsDigest"), instanceId=envelope.get("instanceId"),
                     status="dispatch_prepared", effectState="unknown", kind=envelope.method, updatedAt=self.manager.now())
        # Dispatch/receipt correlation metadata lives in the existing operation
        # row. It is never consulted as a grant or substituted for current mTLS.
        value["dispatchAudience"] = {name: copy.deepcopy(envelope.get(name)) for name in
            ("originDeviceId", "callerDeviceId", "targetDeviceId", "instanceId", "grantId", "authorizationEpoch", "rootTaskId", "localTaskId", "resourceBinding")
            if envelope.get(name) is not None}
        if original is not None:
            value["targetOperationId"] = original["operationId"]
        allocation = envelope.get("budget", {}).get("allocationId") or envelope.arguments.get("allocationId")
        if allocation:
            value["allocationId"] = allocation
        with self.manager.lock:
            previous = self.manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
            if previous:
                old = json.loads(previous[1] or "{}")
                if previous[0] != fingerprint or old.get("protocol") != PROTOCOL:
                    raise PhoneTaskError("Original operation identifier has different immutable parameters", 409)
                return self._public(old)  # Never replay, even when an acknowledgement was lost.
            self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)",
                                        (operation_id, fingerprint, "phone:" + operation_id, value["status"], value["updatedAt"], json.dumps(value)))
            self.manager.ledger.commit()  # Durable identity precedes the first possible control write.
        try:
            response = await self._exchange(connection, peer, ticket, envelope)
            if response["messageType"] == "DeviceError":
                value.update(status="rejected" if response["error"]["effectState"] == "not_dispatched" else "unknown",
                             effectState=response["error"]["effectState"], accepted=False)
            else:
                self._remember_receipt(value, response["result"])
        except Exception:
            value.update(status="unknown", effectState="unknown")
        value["updatedAt"] = self.manager.now()
        with self.manager.lock:
            self._save(operation_id, value)
        return self._public(value)

    @staticmethod
    def _remember_receipt(value, receipt):
        if receipt.get("operationId") != value["operationId"] or receipt.get("argumentsDigest") not in (None, value["argumentsDigest"]):
            raise PhoneTaskError("Original operation receipt changed", 502)
        if receipt.get("accepted"):
            audience = receipt["audience"]
            for key, expected in value.get("dispatchAudience", {}).items():
                if wire.canonical_bytes(audience.get(key)) != wire.canonical_bytes(expected):
                    raise PhoneTaskError("Receipt changed the immutable original dispatch audience", 502)
            fixed = {key: copy.deepcopy(item) for key, item in audience.items() if key not in {"runId", "executorEpoch", "retainedUntil"}}
            if value.get("acceptedAudience") is not None and not wire.resource_equal(value["acceptedAudience"], fixed):
                raise PhoneTaskError("Receipt changed the original accepted task scope", 502)
            for saved, received in (("taskId", "localTaskId"), ("runId", "runId"), ("executorEpoch", "executorEpoch")):
                if saved in value and received in receipt and value[saved] != receipt[received]:
                    raise PhoneTaskError("Receipt changed the original receiver task or run", 502)
            value["acceptedAudience"] = fixed
        for key in ("status", "effectState", "accepted", "runId", "executorEpoch"):
            if key in receipt:
                value[key] = receipt[key]
        if "cancellation" in receipt:
            value["cancellation"] = copy.deepcopy(receipt["cancellation"])
        if receipt.get("accepted") and receipt.get("localTaskId"):
            value["taskId"] = receipt["localTaskId"]
        native = receipt.get("payload", {})
        # A fresh operation.get returns the original native snapshot, whose
        # committed control receipt is nested. Lost ACK recovery reads these
        # exact facts; it never resends the input or manufactures a continuation.
        if isinstance(native, dict) and native.get("operationState") == "receipt" and isinstance(native.get("receipt"), dict):
            native = native["receipt"]
        if receipt.get("accepted") and isinstance(native, dict):
            if value.get("kind") == "task.acquireControl" and native.get("leaseId"):
                value.update(controlLeaseId=native["leaseId"], controlLeaseExpiresAt=native["expiresAt"])
            if value.get("kind") in {"task.answerQuestion", "task.steer"}:
                for name in ("inputSaved", "continuationRequested", "continuationState"):
                    if name in native:
                        value[name] = native[name]
                if native.get("messageId"):
                    value["inputMessageId"] = native["messageId"]
            if value.get("kind") == "task.requestResume":
                for name in ("ownerActionRequired", "resumeRequestId", "requestedRevision", "reviewRevision"):
                    if name in native:
                        value[name] = native[name]

    async def execute(self, action, params):
        if self.closed:
            raise PhoneTaskError("Phone task surface is closed; accepted phone work remains on the phone", 503)
        if action in {"acquire_control", "answer", "steer", "resume"}:
            return await self._ordinary_input(action, params)
        if action in {"usb_open", "usb_close", "usb_status"}:
            return await self._usb_action(action, params)
        if action == "list":
            wire._keys(params, {"peerDeviceId"})
            wire.device_id(params["peerDeviceId"])
            with self.manager.lock:
                rows = self.manager.ledger.execute("SELECT details FROM operations WHERE details IS NOT NULL ORDER BY updated_at DESC LIMIT 1000").fetchall()
            values = [json.loads(row[0]) for row in rows]
            return {"operations": [self._public(value) for value in values if value.get("protocol") == PROTOCOL and value.get("peerDeviceId") == params["peerDeviceId"]][:100], "scope": "this_native_dispatch_surface"}
        if action == "doctor":
            wire._keys(params, {"peerDeviceId"})
            connection, peer = self._connection(params["peerDeviceId"])
            ticket = self.service.receiver.pairing.discovery_query(self.service.identity, peer, self.manager.now())
            response = await self._exchange(connection, peer, ticket, ticket.envelope)
            if response["messageType"] == "DeviceError":
                raise PhoneTaskError("Selected paired phone denied discovery", 403)
            description = response["result"]
            if not description["capturedAt"] <= self.manager.now() < description["validUntil"]:
                raise PhoneTaskError("Native paired description expired; no grant was imported", 403)
            # Signed receiving-owner grants update the SAME native pairing cache.
            # Description/connection metadata alone never becomes execution permission.
            try:
                for receipt in description["grantReceipts"]:
                    if self.manager.now() < receipt["body"]["grant"]["expiresAt"]:
                        self.service.receiver.pairing.accept_outgoing_grant(peer, receipt, self.manager.now())
                    else:
                        self.service.receiver.pairing.accept_history_grant_receipt(peer, receipt, self.manager.now())
            except Exception:
                raise PhoneTaskError("Receiving-owner signed grant receipt could not be verified", 403)
            capabilities = {capability for receipt in description["grantReceipts"] for capability in receipt["body"]["grant"]["capabilities"]
                            if self.manager.now() < receipt["body"]["grant"]["expiresAt"]}
            return {"description": response["result"], "modelInferenceVerified": False, "phoneConfirmationLocalOnly": True,
                    "ordinaryAnswerAvailable": "task.answerQuestion" in capabilities, "steerAvailable": "task.steer" in capabilities,
                    "resumeReviewAvailable": "task.requestResume" in capabilities,
                    "inputStillRequiresOriginalRunAndControlLease": True}
        if action == "run":
            wire._keys(params, {"operationId", "peerDeviceId", "grantId", "resourceBinding", "goal", "toolCalls", "modelCalls", "ttlMs", "capabilityVersion"})
            operation_id = wire.identifier(params["operationId"], "operationId")
            wire.text(params["goal"], "goal", 12000)
            if not params["goal"].strip():
                raise PhoneTaskError("The task goal cannot be blank")
            wire.integer(params["toolCalls"], "toolCalls", 0, 400)
            wire.integer(params["modelCalls"], "modelCalls", 1, 1000)
            wire.integer(params["ttlMs"], "ttlMs", 1000, 86400000)
            # Return the original receipt before any connectivity attempt on a duplicate.
            with self.manager.lock:
                prior = self.manager.ledger.execute("SELECT 1 FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
            if prior:
                fingerprint, previous = self._row(operation_id)
                if fingerprint != hashlib.sha256(wire.canonical_bytes(params)).hexdigest():
                    raise PhoneTaskError("Original operation parameters changed", 409)
                return self._public(previous)
            connection, peer, ticket = self._route(params["peerDeviceId"], params["grantId"])
            grant = ticket.grant
            if ("task.submitGoal" not in grant["capabilities"] or not any(wire.resource_equal(params["resourceBinding"], item) for item in grant["resources"])
                    or params["toolCalls"] > grant["toolBudget"] or params["modelCalls"] > grant["modelBudget"]):
                raise PhoneTaskError("Requested task exceeds the current native grant", 403)
            now = self.manager.now()
            value = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="GoalSubmission", method="task.submitGoal",
                         requestId=str(uuid.uuid4()), operationId=operation_id, originDeviceId=self.service.identity.device_id,
                         callerDeviceId=self.service.identity.device_id, targetDeviceId=peer.device_id, instanceId=ticket.target_instance_id,
                         grantId=ticket.grant_id, authorizationEpoch=ticket.authorization_epoch, resourceBinding=params["resourceBinding"],
                         rootTaskId=operation_id, parentTaskId=operation_id, localTaskId=operation_id, runId=str(uuid.uuid4()), attempt=1,
                         executorEpoch=1, capabilityVersion=params["capabilityVersion"], deadline=min(now + params["ttlMs"], ticket.deadline),
                         budget={"allocationId": wire.allocation_id(operation_id, peer.device_id, params["resourceBinding"], "goal", operation_id), "toolCalls": params["toolCalls"], "modelCalls": params["modelCalls"]},
                         arguments={"goal": params["goal"], "executionMode": "agent", "modelSource": {"kind": "local"}})
            value["argumentsDigest"] = wire.arguments_digest(value)
            envelope = wire.parse_request(wire.canonical_bytes(value))
            return await self._mutation(operation_id, params, connection, peer, ticket, envelope)
        if action in {"status", "wait", "events", "cancel"}:
            optional = {"timeoutMs"} if action == "wait" else {"cursor", "limit"} if action == "events" else {"cancelOperationId"} if action == "cancel" else set()
            wire._keys(params, {"operationId", "peerDeviceId"}, optional)
            _, value = self._row(params["operationId"])
            if value["peerDeviceId"] != params["peerDeviceId"]:
                raise PhoneTaskError("Original operation belongs to a different fixed phone", 403)
            if action == "cancel":
                cancel_id = wire.identifier(params.get("cancelOperationId"), "cancelOperationId")
                semantic = {"operationId": cancel_id, "targetOperationId": value["operationId"], "peerDeviceId": value["peerDeviceId"]}
                with self.manager.lock:
                    prior = self.manager.ledger.execute("SELECT 1 FROM operations WHERE operation_id=?", (cancel_id,)).fetchone()
                if prior:
                    fingerprint, old = self._row(cancel_id)
                    if fingerprint != hashlib.sha256(wire.canonical_bytes(semantic)).hexdigest():
                        raise PhoneTaskError("Original cancellation parameters changed", 409)
                    return self._public(old)
                connection, peer, route = self._route(value["peerDeviceId"], value["grantId"])
                ticket = route.operation_cancel(self.service.identity, value["operationId"], value["argumentsDigest"], cancel_id, value["resourceBinding"], self.manager.now())
                return await self._mutation(cancel_id, semantic, connection, peer, ticket, ticket.envelope, value)
            if action == "events":
                if not value.get("taskId"):
                    raise PhoneTaskError("Query the original operation until the phone task identity is confirmed", 409)
                return await self._read(value, "task.events", value["taskId"], {key: params[key] for key in ("cursor", "limit") if key in params})
            timeout = wire.integer(params.get("timeoutMs", 0), "timeoutMs", 0, 45000)
            end = asyncio.get_running_loop().time() + timeout / 1000
            async def current():
                receipt = await self._read(value, "operation.get", value["operationId"])
                self._remember_receipt(value, receipt)
                value["updatedAt"] = self.manager.now()
                with self.manager.lock:
                    self._save(value["operationId"], value)
                snapshot = self._public(value)
                if value.get("taskId"):
                    snapshot["task"] = await self._read(value, "task.get", value["taskId"])
                return snapshot
            while True:
                try:
                    allowance = 25 if action != "wait" or timeout == 0 else min(25, max(0.001, end - asyncio.get_running_loop().time()))
                    result = await asyncio.wait_for(current(), allowance)
                except (PhoneTaskError, PermissionError, OSError, wire.ProtocolError, asyncio.TimeoutError):
                    result = self._public(value)
                    result.update(currentRead="unavailable", phoneStateUnconfirmed=True)
                task_state = result.get("task", {}).get("state", result["status"])
                if action != "wait" or task_state in SETTLED or task_state in WAITING or asyncio.get_running_loop().time() >= end or result.get("currentRead"):
                    result["waitTimedOut"] = action == "wait" and task_state not in SETTLED | WAITING and not result.get("currentRead")
                    return result
                await asyncio.sleep(min(0.5, max(0, end - asyncio.get_running_loop().time())))
        raise PhoneTaskError("Unsupported phone task action; native confirmation cannot be replaced by text", 404)

    async def _ordinary_input(self, action, params):
        required = {"operationId", "peerDeviceId", "controlOperationId", "taskId", "runId", "executorEpoch", "allocationId"}
        optional = {"ttlMs", "previousLeaseId"} if action == "acquire_control" else set()
        if action != "acquire_control":
            required.add("controlLeaseId")
            required.add("expectedRevision" if action == "resume" else "text")
        if action == "answer":
            required |= {"originalRequestId", "waitRevision"}
        wire._keys(params, required, optional)
        original_id = wire.identifier(params["operationId"], "operationId")
        control_id = wire.identifier(params["controlOperationId"], "controlOperationId")
        if control_id == original_id:
            raise PhoneTaskError("A control write needs its own operation identity")
        _, original = self._row(original_id)
        if original.get("peerDeviceId") != params["peerDeviceId"] or original.get("kind") != "task.submitGoal" or original.get("taskId") != params["taskId"]:
            raise PhoneTaskError("Input must target the same original goal and selected phone", 403)
        fingerprint = hashlib.sha256(wire.canonical_bytes(params)).hexdigest()
        with self.manager.lock:
            known = self.manager.ledger.execute("SELECT 1 FROM operations WHERE operation_id=?", (control_id,)).fetchone()
        if known:
            previous_fingerprint, previous = self._row(control_id)
            if fingerprint != previous_fingerprint or previous.get("targetOperationId") != original_id:
                raise PhoneTaskError("Original control input parameters changed", 409)
            return self._public(previous)
        for name in ("taskId", "runId", "allocationId"):
            wire.identifier(params[name], name)
        wire.integer(params["executorEpoch"], "executorEpoch", 1)
        snapshot = await self._read(original, "task.get", original["taskId"])
        native = snapshot.get("result", {}).get("ownerInput", {})
        if (snapshot.get("taskId") != params["taskId"] or snapshot.get("runId") != params["runId"]
                or snapshot.get("executorEpoch") != params["executorEpoch"] or native.get("allocationId") != params["allocationId"]
                or native.get("format") != "pocketworld.device-owner-input.v1" or native.get("nativePortPresent") is not True
                or native.get("originalSessionPresent") is not True):
            raise PhoneTaskError("Read the current original native input scope; run/allocation changed", 409)
        method = {"acquire_control": "task.acquireControl", "answer": "task.answerQuestion", "steer": "task.steer", "resume": "task.requestResume"}[action]
        args = {name: params[name] for name in ("taskId", "runId", "executorEpoch")}
        if action == "acquire_control":
            args["ttlMs"] = wire.integer(params.get("ttlMs", 30000), "ttlMs", 1, 300000)
            if "previousLeaseId" in params:
                args["previousLeaseId"] = wire.identifier(params["previousLeaseId"], "previousLeaseId")
        elif action == "resume":
            revision = wire.integer(params["expectedRevision"], "expectedRevision", 1)
            if revision != snapshot.get("revision"):
                raise PhoneTaskError("Original task revision changed; read it again before requesting phone review", 409)
            args.update(controlLeaseId=wire.identifier(params["controlLeaseId"], "controlLeaseId"), allocationId=params["allocationId"], expectedRevision=revision)
        else:
            wire.text(params["text"], "text", 4000)
            if not params["text"].strip():
                raise PhoneTaskError("Ordinary task input cannot be blank")
            args.update(controlLeaseId=wire.identifier(params["controlLeaseId"], "controlLeaseId"), allocationId=params["allocationId"], text=params["text"])
            if action == "answer":
                question = native.get("ordinaryQuestion", {})
                if (question.get("requestId") != params["originalRequestId"] or question.get("waitRevision") != params["waitRevision"]
                        or question.get("approval") is not False or question.get("takeover") is not False):
                    raise PhoneTaskError("This is not the exact current ordinary question; no approval was sent", 409)
                args.update(originalRequestId=wire.identifier(params["originalRequestId"], "originalRequestId"), waitRevision=wire.integer(params["waitRevision"], "waitRevision", 1))
            elif native.get("runningInputState") != "requires_current_write_grant_and_control_lease":
                raise PhoneTaskError("Steering requires the original active native goal", 409)
        connection, peer, route = self._route(original["peerDeviceId"], original["grantId"])
        if method not in route.grant["capabilities"]:
            raise PhoneTaskError("The receiving owner's current grant does not include this input method", 403)
        now = self.manager.now()
        value = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="DeviceRequest", method=method,
                     requestId=str(uuid.uuid4()), operationId=control_id, originDeviceId=self.service.identity.device_id,
                     callerDeviceId=self.service.identity.device_id, targetDeviceId=peer.device_id, instanceId=original["instanceId"],
                     grantId=route.grant["grantId"], authorizationEpoch=route.grant["authorizationEpoch"], resourceBinding=original["resourceBinding"],
                     # The unchanged codec counts the origin in visitedDevices:
                     # this direct hop has length one and no delegation proof.
                     deadline=min(now + 15000, route.deadline), visitedDevices=[self.service.identity.device_id], maxDelegationDepth=1,
                     delegationChain=[], arguments=args)
        value["argumentsDigest"] = wire.arguments_digest(value)
        envelope = wire.parse_request(wire.canonical_bytes(value))
        return await self._mutation(control_id, params, connection, peer, route, envelope, original)

    async def _usb_action(self, action, params):
        from phone_task_usb import OwnedUsbForward, attach
        wire._keys(params, {"operationId", "peerDeviceId"} | ({"grantId", "adbPath", "serial", "bootCount", "phoneControlPort"} if action == "usb_open" else set()))
        operation_id = wire.identifier(params["operationId"], "operationId")
        wire.device_id(params["peerDeviceId"])
        fingerprint = hashlib.sha256(wire.canonical_bytes(params)).hexdigest()
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
        if row:
            value = json.loads(row[1] or "{}")
            if value.get("protocol") != "pocketworld.usb-forward.v1" or value.get("peerDeviceId") != params["peerDeviceId"]:
                raise PhoneTaskError("USB operation belongs to another fixed native resource", 409)
            if action == "usb_open" and row[0] != fingerprint:
                raise PhoneTaskError("Original USB operation parameters changed", 409)
            active = self.usb.get(operation_id)
            live = active is not None and not active[1].closed and await asyncio.to_thread(active[0].is_current)
            if value.get("status") == "usb_connected" and not live:
                value.update(status="unknown", effectState="unknown")
                with self.manager.lock:
                    self._save(operation_id, value)
            if action == "usb_close" and operation_id in self.usb:
                forward, connection = self.usb.pop(operation_id)
                await connection.close()
                cleaned = await asyncio.to_thread(forward.close)
                value.update(status="closed" if cleaned else "unknown", cleanupConfirmed=cleaned)
                with self.manager.lock:
                    self._save(operation_id, value)
            return {key: value[key] for key in ("operationId", "peerDeviceId", "status", "cleanupConfirmed", "effectState") if key in value}
        if action != "usb_open":
            raise PhoneTaskError("Unknown original USB operation", 404)
        forward = OwnedUsbForward(params["adbPath"], params["serial"], params["bootCount"], params["phoneControlPort"])
        # Check existing pairing without creating identity, grant or phone settings.
        self.service._paired(params["peerDeviceId"])
        if self.service.receiver.trust.outgoing_grant(params["peerDeviceId"], params["grantId"]) is None:
            raise PhoneTaskError("USB requires an existing native signed paired grant", 403)
        value = dict(protocol="pocketworld.usb-forward.v1", operationId=operation_id, peerDeviceId=params["peerDeviceId"],
                     status="dispatch_prepared", effectState="unknown", cleanupConfirmed=False)
        with self.manager.lock:
            self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)",
                                        (operation_id, fingerprint, "usb:" + operation_id, value["status"], self.manager.now(), json.dumps(value)))
            self.manager.ledger.commit()
        try:
            connection = await attach(self.service, params["peerDeviceId"], params["grantId"], forward)
            self.usb[operation_id] = (forward, connection)
            value.update(status="usb_connected", effectState="reported", localPort=forward.local_port)
        except Exception:
            value.update(status="unknown", effectState="unknown", cleanupConfirmed=forward.cleanup_confirmed)
        with self.manager.lock:
            self._save(operation_id, value)
        return {key: value[key] for key in ("operationId", "peerDeviceId", "status", "cleanupConfirmed", "effectState") if key in value}

    async def close(self):
        if self.closed:
            return
        self.closed = True
        for connection in tuple(self.connections):
            self.connection_closed(connection)
        # Only these owned forwards are released. Closing a local surface never
        # sends cancel/pause/resume to any accepted phone operation.
        for operation_id, (forward, connection) in list(self.usb.items()):
            await connection.close()
            cleaned = await asyncio.to_thread(forward.close)
            with self.manager.lock:
                row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
                if row:
                    value = json.loads(row[0])
                    value.update(status="closed" if cleaned else "unknown", cleanupConfirmed=cleaned)
                    self._save(operation_id, value)
            self.usb.pop(operation_id, None)
