"""ACP v1 over one supervised child process, with exact request ownership.

The protocol contract is https://agentclientprotocol.com/protocol/v1/ .
This client offers permissions and session updates, not host filesystem or
terminal methods. Native tools run under the owner's external process policy.
"""
from __future__ import annotations

from contextlib import nullcontext
import json
import queue
import threading
import time
import uuid

from acp_driver import lossless_owner_payload
from codex_driver import DriverError
from driver_dispatch import DispatchError, PipeWriter
from harness_registry import ACP_PROTOCOL, canonical, digest
from remote_interactions import Progress

MAX_FRAME = 1_048_576
MAX_OUTPUT = 196_608


def text(value, maximum=200):
    return isinstance(value, str) and bool(value) and len(value) <= maximum and "\0" not in value


def wire_id(value):
    return text(value) or type(value) is int and abs(value) <= 2**53 - 1


def normalize_hello(value):
    if not isinstance(value, dict) or type(value.get("protocolVersion")) is not int or value["protocolVersion"] != 1:
        raise DriverError("acp_protocol_mismatch: the installed agent did not negotiate ACP v1")
    info, caps, methods = value.get("agentInfo", {}), value.get("agentCapabilities", {}), value.get("authMethods", [])
    if not isinstance(info, dict) or not isinstance(caps, dict) or not isinstance(methods, list) or len(methods) > 64:
        raise DriverError("acp_initialize_invalid")
    if any(key in caps and not isinstance(caps[key], dict) for key in ("sessionCapabilities", "promptCapabilities", "mcpCapabilities")):
        raise DriverError("acp_initialize_capabilities_invalid")
    safe_info = {k: info[k] for k in ("name", "title", "version") if isinstance(info.get(k), str) and len(info[k]) <= 200}
    safe_methods = []
    for method in methods:
        if not isinstance(method, dict) or not text(method.get("id")):
            raise DriverError("acp_auth_method_invalid")
        safe_methods.append({k: method[k] for k in ("id", "name", "description") if isinstance(method.get(k), str) and len(method[k]) <= 2000})
    if len(canonical(caps).encode()) > 32768 or len({m["id"] for m in safe_methods}) != len(safe_methods):
        raise DriverError("acp_initialize_invalid")
    return {"protocolVersion": 1, "agentInfo": safe_info, "agentCapabilities": caps,
            "authMethods": safe_methods}


class JsonRpcProcess:
    """A bounded JSONL channel; write ambiguity is never retried."""
    def __init__(self, process, stopped, timeout, dispatch_guard=None):
        self.process, self.stopped = process, stopped
        self.guard = dispatch_guard or nullcontext
        self.deadline = time.monotonic() + timeout
        self.writer = PipeWriter(process)
        self.events = queue.Queue(maxsize=256)
        self.closed = threading.Event()
        self.serial = 0
        self.read_error = None
        self.reader = threading.Thread(target=self._read, daemon=True, name="pocket-harness-jsonl")
        self.reader.start()

    def _read(self):
        try:
            while not self.closed.is_set():
                raw = self.process.stdout.readline(MAX_FRAME + 1)
                if not raw:
                    self.events.put(None, timeout=.5)
                    return
                if len(raw) > MAX_FRAME or not raw.endswith(b"\n"):
                    raise ValueError("frame limit")
                value = json.loads(raw, parse_constant=lambda _v: (_ for _ in ()).throw(ValueError()))
                if not isinstance(value, dict) or value.get("jsonrpc") != "2.0":
                    raise ValueError("jsonrpc frame")
                self.events.put(value, timeout=.5)
        except (OSError, ValueError, RecursionError, queue.Full):
            self.read_error = DriverError("harness_transport_unknown: invalid or interrupted protocol stream; no automatic replay")

    def next_id(self):
        self.serial += 1
        return "pocket-" + str(self.serial)

    def send(self, value, stopping=False):
        with self.guard():
            if not stopping and (self.closed.is_set() or self.stopped() or time.monotonic() >= self.deadline):
                raise DriverError("harness_stopped_before_dispatch")
            ticket = self.writer.reserve(value, time.monotonic() + 3 if stopping else self.deadline,
                                         (lambda: False) if stopping else self.stopped)
        try:
            self.writer.send(ticket)
        except DispatchError as failure:
            raise DriverError("harness_write_unknown: original write was not confirmed; no replay") from failure

    def read(self, wait=.05):
        try:
            value = self.events.get(timeout=wait)
        except queue.Empty:
            if self.read_error:
                raise self.read_error
            return ...
        if value is None:
            raise DriverError("harness_transport_unknown: process ended before a matching terminal receipt")
        return value

    def close(self):
        self.closed.set()


class AcpDriver:
    def __init__(self, process, cwd, on_state, stopped, timeout, dispatch_guard=None, *,
                 expected_hello=None, auth_method=None, mcp_servers=None, current=None,
                 redact=None, mode=None, worker=None, expected_options=None):
        self.rpc = JsonRpcProcess(process, stopped, timeout, dispatch_guard)
        self.cwd, self.on_state, self.stopped = cwd, on_state, stopped
        self.guard = dispatch_guard or nullcontext
        self.expected_hello, self.auth_method = expected_hello, auth_method
        self.mcp_servers, self.current = mcp_servers or [], current or (lambda: None)
        self.redact = redact or (lambda v: v)
        self.requested_mode, self.worker = mode, worker
        self.expected_options = expected_options
        self.lock = threading.RLock()
        self.decisions = queue.Queue(maxsize=16)
        self.pending, self.approval_receipts, self.seen_requests = {}, [], set()
        self.progress = Progress(ACP_PROTOCOL)
        self.hello = self.session_id = self.prompt_id = self.reported_model = self.mode_id = None
        self.session_options = {}
        self.output, self.truncated = "", False
        self.turn_dispatched = self.started = self.finished = self.protocol_verified = False
        self.cancel_sent = self.cancel_requested = False
        self.cancel_deadline = None
        self.replaying = self.opening = self.probing = False
        self.early_updates = []

    def _request(self, method, params):
        identifier = self.rpc.next_id()
        self.rpc.send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
        return self._response(identifier)

    def _response(self, identifier):
        while True:
            self._tick()
            value = self.rpc.read()
            if value is ...:
                continue
            if "method" in value:
                self._event(value)
                continue
            if value.get("id") != identifier or set(value) - {"jsonrpc", "id", "result", "error"}:
                raise DriverError("acp_response_identity_mismatch")
            if "error" in value:
                code = value["error"].get("code") if isinstance(value["error"], dict) else None
                raise DriverError("acp_request_failed: code " + str(code if type(code) is int else "unknown"))
            if not isinstance(value.get("result"), dict):
                raise DriverError("acp_response_invalid")
            return value["result"]

    def _tick(self):
        now = time.monotonic()
        if self.stopped() or now >= self.rpc.deadline:
            if not self.turn_dispatched:
                raise DriverError("acp_stopped_before_prompt")
            if not self.cancel_requested:
                self._cancel()
            if now >= self.cancel_deadline:
                raise DriverError("acp_cancel_unknown: cancellation has no matching terminal receipt")
        self._decisions()

    def probe(self, create_session=False):
        self.current()
        result = self._request("initialize", {"protocolVersion": 1,
            "clientInfo": {"name": "PocketWorld Companion", "version": "0.4.0"},
            "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}})
        self.hello = normalize_hello(result)
        if self.expected_hello is not None and self.hello != self.expected_hello:
            raise DriverError("acp_capabilities_changed: inspect the installed agent again before dispatch")
        self.protocol_verified = True
        if self.auth_method:
            if self.auth_method not in {m["id"] for m in self.hello["authMethods"]}:
                raise DriverError("acp_auth_method_not_advertised")
            self._request("authenticate", {"methodId": self.auth_method})
        self.current()
        if create_session:
            self.probing = True
            try:
                self._session(None)
            finally:
                self.probing = False
        return self.hello

    def _session(self, thread_id):
        self.opening = True
        self.replaying = bool(thread_id)
        if thread_id:
            if not text(thread_id, 500):
                raise DriverError("acp_session_identity_invalid")
            self.session_id = thread_id
            caps = self.hello["agentCapabilities"]
            # Loading replays a transcript that the host can identify and ignore.
            if caps.get("loadSession") is True:
                method = "session/load"
            elif isinstance(caps.get("sessionCapabilities", {}).get("resume"), dict):
                method = "session/resume"
            else:
                raise DriverError("acp_resume_not_advertised")
            params = {"sessionId": thread_id, "cwd": self.cwd, "mcpServers": self.mcp_servers}
        else:
            method = "session/new"
            params = {"cwd": self.cwd, "mcpServers": [] if self.probing else self.mcp_servers}
        result = self._request(method, params)
        found = result.get("sessionId", thread_id)
        if not text(found, 500) or thread_id and found != thread_id:
            raise DriverError("acp_session_identity_mismatch")
        self.session_id = found
        self.session_options = self._options(result)
        self.reported_model = self.session_options.get("models", {}).get("currentModelId")
        self.mode_id = self.session_options.get("modes", {}).get("currentModeId")
        for option in self.session_options.get("configOptions", []):
            if isinstance(option, dict) and option.get("category") == "model" and text(option.get("currentValue")):
                self.reported_model = option["currentValue"]
            if isinstance(option, dict) and option.get("category") == "mode" and text(option.get("currentValue")):
                self.mode_id = option["currentValue"]
        for update in self.early_updates:
            self._event(update)
        self.early_updates.clear()
        self.opening = self.replaying = False
        return result

    @staticmethod
    def _options(result):
        fields = {key: result[key] for key in ("models", "modes", "configOptions") if key in result}
        if len(canonical(fields).encode()) > 65536:
            raise DriverError("acp_session_options_limit")
        if not isinstance(fields.get("models", {}), dict) or not isinstance(fields.get("modes", {}), dict) or not isinstance(fields.get("configOptions", []), list):
            raise DriverError("acp_session_options_invalid")
        for key, available, identity in (("models", "availableModels", "modelId"), ("modes", "availableModes", "id")):
            entries = fields.get(key, {}).get(available, [])
            if not isinstance(entries, list) or len(entries) > 1000 or any(not isinstance(v, dict) or not text(v.get(identity)) for v in entries):
                raise DriverError("acp_session_options_invalid")
        if len(fields.get("configOptions", [])) > 100 or any(not isinstance(v, dict) for v in fields.get("configOptions", [])):
            raise DriverError("acp_session_options_invalid")
        return fields

    def _select(self, category, requested):
        if not requested:
            return
        options = self.session_options.get("configOptions", [])
        config = next((o for o in options if isinstance(o, dict) and o.get("category") == category and o.get("type") == "select"), None)
        if config is not None:
            values = []
            choices = config.get("options", [])
            if not isinstance(choices, list):
                raise DriverError("acp_config_options_invalid")
            for option in choices:
                if isinstance(option, dict):
                    values.extend(v.get("value") for v in option.get("options", []) if isinstance(v, dict)) if isinstance(option.get("options"), list) else values.append(option.get("value"))
            if requested not in values or not text(config.get("id")):
                raise DriverError("acp_selected_option_not_advertised")
            if config.get("currentValue") != requested:
                result = self._request("session/set_config_option", {"sessionId": self.session_id, "configId": config["id"], "value": requested})
                changed = result.get("configOptions")
                if not isinstance(changed, list) or not any(isinstance(o, dict) and o.get("id") == config["id"] and o.get("currentValue") == requested for o in changed):
                    raise DriverError("acp_selected_option_unconfirmed")
                self.session_options["configOptions"] = changed
        else:
            key, current, available, identity, method = (("models", "currentModelId", "availableModels", "modelId", "session/set_model")
                if category == "model" else ("modes", "currentModeId", "availableModes", "id", "session/set_mode"))
            data = self.session_options.get(key, {})
            if requested not in [v.get(identity) for v in data.get(available, []) if isinstance(v, dict)]:
                raise DriverError("acp_selected_option_not_advertised")
            if data.get(current) != requested:
                self._request(method, {"sessionId": self.session_id, "modelId" if category == "model" else "modeId": requested})
                data[current] = requested
        if category == "model":
            self.reported_model = requested
        else:
            self.mode_id = requested

    def run(self, prompt, thread_id=None, model=None):
        with self.guard():
            if self.started:
                raise DriverError("acp_original_prompt_already_attempted")
            self.started = True
        self.probe()
        self._session(thread_id)
        self._select("model", model)
        self._select("mode", self.requested_mode)
        if self.expected_options is not None and self.session_options != self.expected_options:
            raise DriverError("acp_session_options_changed: inspect the original model/mode selection again")
        self.current()
        self.prompt_id = self.rpc.next_id()
        with self.guard():
            if self.stopped():
                raise DriverError("acp_stopped_before_prompt")
            self.turn_dispatched = True
            self._state()  # Persist identity before an ambiguous pipe write.
        literal = "The following is the owner's literal task text.\n\n" + prompt
        self.rpc.send({"jsonrpc": "2.0", "id": self.prompt_id, "method": "session/prompt",
                       "params": {"sessionId": self.session_id, "prompt": [{"type": "text", "text": literal}]}})
        result = self._response(self.prompt_id)
        reason = result.get("stopReason")
        if reason not in {"end_turn", "cancelled", "max_tokens", "max_turn_requests", "refusal"}:
            raise DriverError("acp_terminal_unknown")
        self.finished = True
        self._withdraw("withdrawn")
        self.progress.add("turn", "session/prompt", self.prompt_id, status=reason)
        return {**self._snapshot(), "status": "completed" if reason == "end_turn" else "cancelled" if reason == "cancelled" else "failed",
                "stopReason": reason, "promptTerminalConfirmed": True, "nativeSettled": True,
                "message": "The original ACP prompt returned its terminal receipt; inspect the resulting files and output."}

    def _event(self, value):
        method, params = value.get("method"), value.get("params", {})
        if not isinstance(method, str) or not isinstance(params, dict):
            raise DriverError("acp_event_invalid")
        if "id" in value:
            identifier = value["id"]
            marker = canonical([type(identifier).__name__, identifier])
            if not wire_id(identifier) or marker in self.seen_requests or len(self.seen_requests) >= 512:
                raise DriverError("acp_server_request_identity_invalid")
            self.seen_requests.add(marker)
            if method == "session/request_permission":
                self._permission(identifier, params)
            else:
                self._reply(identifier, {"error": {"code": -32601, "message": "Client method not supported"}})
            return
        if method == "session/update":
            if self.opening and self.session_id is None:
                if len(self.early_updates) >= 128:
                    raise DriverError("acp_early_update_limit")
                self.early_updates.append(value)
                return
            if self.session_id is None or params.get("sessionId") != self.session_id:
                raise DriverError("acp_update_session_mismatch")
            if self.replaying or self.probing or self.finished:
                return
            update = params.get("update")
            if not isinstance(update, dict):
                raise DriverError("acp_update_invalid")
            kind = update.get("sessionUpdate")
            if kind == "agent_message_chunk":
                content = update.get("content", {})
                if isinstance(content, dict) and content.get("type") == "text" and isinstance(content.get("text"), str):
                    raw = (self.output + content["text"]).encode()
                    self.truncated |= len(raw) > MAX_OUTPUT
                    self.output = raw[:MAX_OUTPUT].decode("utf-8", errors="ignore")
            elif kind in {"tool_call", "tool_call_update"}:
                if not text(update.get("toolCallId")):
                    raise DriverError("acp_tool_identity_invalid")
                self.progress.add("tool", kind, update["toolCallId"], title=str(update.get("title", ""))[:200], status=str(update.get("status", "unknown"))[:40])
            elif kind == "current_mode_update" and text(update.get("currentModeId")):
                self.mode_id = update["currentModeId"]
            elif kind == "config_option_update":
                self.session_options.update(self._options(update))
            elif kind == "plan":
                self.progress.add("plan", "session/update", "plan", title="Agent plan updated", status="running")
            self._state()
        elif method == "$/cancel_request":
            with self.lock:
                for key, entry in list(self.pending.items()):
                    if entry["wireId"] == params.get("requestId"):
                        del self.pending[key]
            self._state()

    def _reply(self, identifier, value):
        self.rpc.send({"jsonrpc": "2.0", "id": identifier, **value}, stopping=self.cancel_requested or self.stopped())
        self.seen_requests.discard(canonical([type(identifier).__name__, identifier]))

    def _permission(self, identifier, params):
        if (params.get("sessionId") != self.session_id or not self.turn_dispatched or self.finished or
                self.replaying or self.probing or self.cancel_requested or self.stopped()):
            self._reply(identifier, {"result": {"outcome": {"outcome": "cancelled"}}})
            return
        tool, options = params.get("toolCall"), params.get("options")
        valid = (set(params) <= {"sessionId", "toolCall", "options", "_meta"} and lossless_owner_payload(params)
                 and self.redact(params) == params and len(canonical(params).encode()) <= 32768
                 and isinstance(tool, dict) and text(tool.get("toolCallId"))
                 and isinstance(options, list) and 1 <= len(options) <= 16)
        if valid:
            valid = all(isinstance(o, dict) and text(o.get("optionId")) and text(o.get("name"), 1000)
                        and o.get("kind") in {"allow_once", "reject_once", "allow_always", "reject_always"} for o in options)
        if not valid or len({o["optionId"] for o in options}) != len(options):
            self._reply(identifier, {"result": {"outcome": {"outcome": "cancelled"}}})
            raise DriverError("acp_permission_unreviewable")
        allow = [o["optionId"] for o in options if o["kind"] == "allow_once"]
        reject = [o["optionId"] for o in options if o["kind"] == "reject_once"]
        if len(allow) > 1 or len(reject) > 1:
            self._reply(identifier, {"result": {"outcome": {"outcome": "cancelled"}}})
            raise DriverError("acp_permission_options_ambiguous")
        request_id = str(uuid.uuid4())
        entry = {"requestId": request_id, "requestHash": digest(params), "wireId": identifier,
                 "protocol": ACP_PROTOCOL, "method": "acp/session/request_permission",
                 "sessionId": self.session_id, "promptRequestId": self.prompt_id,
                 "toolCallId": tool["toolCallId"], "toolCall": tool, "options": options,
                 "canAccept": bool(allow), "allowOnceOptionId": allow[0] if allow else "",
                 "rejectOnceOptionId": reject[0] if reject else "", "scopeEnforced": False,
                 "osReadIsolationVerified": False}
        with self.lock:
            if len(self.pending) >= 16 or len(self.approval_receipts) >= 256:
                self._reply(identifier, {"result": {"outcome": {"outcome": "cancelled"}}})
                raise DriverError("acp_permission_limit")
            self.pending[request_id] = entry
        self._state()

    def decide(self, request_id, decision):
        if decision not in {"accept", "decline", "cancel"}:
            raise DriverError("acp_decision_invalid")
        with self.lock:
            entry = self.pending.get(request_id)
            if not entry or entry.get("queued") or self.finished or self.rpc.closed.is_set() or self.stopped() or self.cancel_requested:
                raise DriverError("acp_permission_stale")
            if decision == "accept" and not entry["canAccept"]:
                raise DriverError("acp_allow_once_not_available")
            entry["queued"] = True
            self.decisions.put_nowait((request_id, decision))

    def _decisions(self):
        while True:
            try:
                identifier, decision = self.decisions.get_nowait()
            except queue.Empty:
                return
            self.current()
            with self.guard():
                with self.lock:
                    entry = self.pending.pop(identifier, None)
                if entry is None:
                    continue
                if self.stopped() or self.cancel_requested:
                    decision = "cancel"
                receipt = {"requestId": identifier, "requestHash": entry["requestHash"], "sessionId": self.session_id,
                           "promptRequestId": self.prompt_id, "decision": decision, "status": "sending"}
                self.approval_receipts.append(receipt)
                self._state()
            option = entry["allowOnceOptionId"] if decision == "accept" else entry["rejectOnceOptionId"] if decision == "decline" else ""
            try:
                self._reply(entry["wireId"], {"result": {"outcome": {"outcome": "selected", "optionId": option} if option else {"outcome": "cancelled"}}})
            except DriverError:
                receipt["status"] = "unknown"
                self._state()
                raise
            receipt["status"] = "sent"
            self._state()

    def _withdraw(self, status):
        with self.lock:
            entries = list(self.pending.values())
            self.pending.clear()
        for entry in entries:
            self.approval_receipts.append({"requestId": entry["requestId"], "requestHash": entry["requestHash"],
                "sessionId": self.session_id, "promptRequestId": self.prompt_id, "decision": "cancel", "status": status})
        return entries

    def _cancel(self):
        self.cancel_requested = True
        self.cancel_deadline = time.monotonic() + 3
        entries = self._withdraw("sending")
        self.rpc.send({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": self.session_id}}, stopping=True)
        self.cancel_sent = True
        for entry in entries:
            self._reply(entry["wireId"], {"result": {"outcome": {"outcome": "cancelled"}}})
            self.approval_receipts[-len(entries) + entries.index(entry)]["status"] = "sent"
        self._state()

    def _snapshot(self):
        with self.lock:
            approvals = [{k: v for k, v in entry.items() if k not in {"wireId", "queued"}} for entry in self.pending.values()]
        caps = (self.hello or {}).get("agentCapabilities", {})
        resumable = caps.get("loadSession") is True or isinstance(caps.get("sessionCapabilities", {}).get("resume"), dict)
        return {"protocol": ACP_PROTOCOL, "protocolVerified": self.protocol_verified,
                "sessionId": self.session_id, "threadId": self.session_id, "promptRequestId": self.prompt_id,
                "reportedModel": self.reported_model, "modeId": self.mode_id, "modelInferenceVerified": False,
                "readOnlyConfirmed": False, "osReadIsolationVerified": False, "executionPolicy": "owner_managed",
                "pendingApprovals": approvals, "approvalReceipts": list(self.approval_receipts),
                "pendingQuestions": [], "questionReceipts": [], "progress": self.progress.snapshot(),
                "output": self.output, "outputTruncated": self.truncated, "cancelNotificationSent": self.cancel_sent,
                "sessionResumeSupported": resumable, "resumeSupported": resumable, "agentInfo": (self.hello or {}).get("agentInfo", {}),
                "sessionOptions": self.session_options, "teamTools": bool(self.mcp_servers),
                "status": "awaiting_approval" if approvals else "running"}

    def _state(self):
        self.on_state(self.redact(self._snapshot()))

    def can_steer(self, _expected=None):
        return False

    def answer_question(self, *_args):
        raise DriverError("acp_structured_questions_not_negotiated")

    def close_steering(self):
        self.rpc.close()
        self._withdraw("withdrawn" if self.finished else "unknown")
        for receipt in self.approval_receipts:
            if receipt.get("status") == "sending":
                receipt["status"] = "unknown"
        value = self._snapshot()
        value.pop("status")
        self.on_state(self.redact(value))
