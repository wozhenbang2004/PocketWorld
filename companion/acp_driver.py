"""One fixed Gemini CLI 0.66.0 ACP1 invocation on the original owned process.

Protocol source: google-gemini/gemini-cli
970413207732f5d4d8c50540c7fc359c44fdaaf2, acpRpcDispatcher/acpSession.
No client filesystem/terminal service, login, continuation, steering or replay.
"""
from __future__ import annotations

from contextlib import nullcontext
import json
import queue
import threading
import time
import uuid

from codex_driver import DriverError
from driver_dispatch import DispatchError, PipeWriter
from gemini_acp_profile import BACKEND, PROTOCOL, VERSION, WIRE_VERSION, ProfileError, canonical, digest
from remote_interactions import Progress


MAX_EVENT = 1_048_576
MAX_OUTPUT = 196_608
STATE_FIELDS = frozenset({"protocol", "protocolVerified", "sessionId", "threadId", "promptRequestId",
    "reportedModel", "modeId", "modelInferenceVerified", "readOnlyConfirmed", "osReadIsolationVerified",
    "pendingApprovals", "approvalReceipts", "pendingQuestions", "questionReceipts", "progress",
    "output", "outputTruncated", "status", "message", "stopReason", "promptTerminalConfirmed",
    "cancelNotificationSent", "modelRuntime"})


def _text(value, maximum=200):
    if not isinstance(value, str) or not value or "\0" in value:
        return False
    try:
        return len(value.encode("utf-16-le")) // 2 <= maximum
    except UnicodeError:
        return False


def _wire_id(value):
    return (isinstance(value, str) and 0 < len(value) <= 200) or type(value) is int and abs(value) <= 2**53 - 1


def lossless_owner_payload(value, depth=0):
    """The existing TaskStore sanitizer must preserve every reviewed byte/value."""
    if depth > 16:
        return False
    if isinstance(value, dict):
        forbidden = {"apikey", "authorization", "token", "secret", "imagebase64", "imagedataurl"}
        return all(isinstance(k, str) and k.lower() not in forbidden and lossless_owner_payload(k, depth + 1)
                   and lossless_owner_payload(v, depth + 1) for k, v in value.items())
    if isinstance(value, list):
        return len(value) <= 200 and all(lossless_owner_payload(v, depth + 1) for v in value)
    if isinstance(value, str):
        try:
            return len(value.encode("utf-16-le")) // 2 <= 12000
        except UnicodeError:
            return False
    return value is None or type(value) in {bool, int, float}


def normalized_hello(result):
    if not isinstance(result, dict) or type(result.get("protocolVersion")) is not int or result["protocolVersion"] != WIRE_VERSION:
        raise DriverError("acp_protocol_mismatch: 必须是固定 Gemini ACP1 握手。")
    info = result.get("agentInfo")
    if not isinstance(info, dict) or (info.get("name"), info.get("version")) != ("gemini-cli", VERSION):
        raise DriverError("acp_version_mismatch: 实际 Gemini 身份或版本与原固定来源不符。")
    auth = result.get("authMethods")
    if not isinstance(auth, list) or not 1 <= len(auth) <= 8:
        raise DriverError("acp_hello_invalid: 认证方法观察不完整。")
    identifiers = [value.get("id") if isinstance(value, dict) else None for value in auth]
    if any(not _text(value, 80) for value in identifiers) or len(set(identifiers)) != len(identifiers) or "gemini-api-key" not in identifiers:
        raise DriverError("acp_hello_invalid: 未观察到固定 API key 认证方法。")
    capabilities = result.get("agentCapabilities", {})
    if not isinstance(capabilities, dict) or len(canonical(capabilities).encode()) > 16_384:
        raise DriverError("acp_hello_invalid: 能力观察超出范围。")
    # These bytes participate only in currentness; they grant no client methods.
    return {"protocolVersion": WIRE_VERSION, "agentInfo": {"name": "gemini-cli", "version": VERSION},
            "authMethodIds": sorted(identifiers), "agentCapabilities": json.loads(canonical(capabilities))}


def safe_state(value):
    if not isinstance(value, dict) or set(value) - STATE_FIELDS:
        raise DriverError("acp_state_invalid: 驱动不能替换原任务身份或后端选择。")
    return json.loads(canonical(value))


class GeminiAcpDriver:
    def __init__(self, process, cwd, on_state, stopped, timeout, dispatch_guard=None,
                 current=None, expected_hello=None, redact=None):
        self.process, self.cwd = process, cwd
        self.on_state, self.stopped = on_state, stopped
        self.dispatch_guard = dispatch_guard or nullcontext
        self.current = current or (lambda **_kwargs: None)
        self.expected_hello = expected_hello
        self.redact = redact or (lambda value: value)
        self.writer = PipeWriter(process)
        self.deadline = time.monotonic() + timeout
        self.events = queue.Queue(maxsize=128)
        self.decisions = queue.Queue(maxsize=12)
        self.lock = threading.RLock()
        self.closed = threading.Event()
        self.serial = 0
        self.turn_dispatched = self.finished = self.protocol_verified = False
        self.started = False
        self.session_id = self.prompt_id = self.reported_model = None
        self.mode_id = None
        self.hello = None
        self.pending, self.seen_requests, self.approval_receipts = {}, set(), []
        self.output, self.truncated = "", False
        self.cancel_sent = self.cancel_requested = False
        self.cancel_deadline = None
        self.read_error = None
        self.progress = Progress(PROTOCOL)
        self.early_updates = []
        self.waiting_session = False
        self.reader = threading.Thread(target=self._read, daemon=True, name="pocket-gemini-acp")
        self.reader.start()

    def _read(self):
        try:
            while not self.closed.is_set():
                raw = self.process.stdout.readline(MAX_EVENT + 1)
                if not raw:
                    self.events.put(None, timeout=.5)
                    return
                if len(raw) > MAX_EVENT or not raw.endswith(b"\n"):
                    raise DriverError("acp_frame_invalid")
                value = json.loads(raw, parse_constant=lambda _value: (_ for _ in ()).throw(ValueError()))
                if not isinstance(value, dict) or value.get("jsonrpc") != "2.0":
                    raise DriverError("acp_frame_invalid")
                self.events.put(value, timeout=.5)
        except (OSError, ValueError, DriverError, RecursionError, queue.Full):
            self.read_error = DriverError("acp_transport_unknown: 协议帧未确认；不会重发原请求。")

    def _id(self):
        self.serial += 1
        return "pocketworld-" + str(self.serial)

    def _current(self, require_unexpired=False, deep=False):
        try:
            self.current(require_unexpired=require_unexpired, deep=deep)
        except (OSError, ValueError, ProfileError) as error:
            raise DriverError("acp_source_changed: 原启动来源未能再次核对。") from error

    def _ticket(self, value, stopping=False):
        return self.writer.reserve(value, self.cancel_deadline or time.monotonic() + 3 if stopping else self.deadline,
                                   (lambda: False) if stopping else self.stopped)

    def _send(self, ticket):
        try:
            self.writer.send(ticket)
        except DispatchError as error:
            raise DriverError("acp_write_unknown: 原管道写入未确认；不会重放。") from error

    def _request(self, method, params):
        request_id = self._id()
        with self.dispatch_guard():
            if self.stopped() or self.closed.is_set():
                raise DriverError("acp_stopped_before_dispatch")
            ticket = self._ticket({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        self._send(ticket)
        return self._response(request_id)

    def _response(self, request_id):
        while True:
            value = self._next()
            if value.get("id") != request_id:
                raise DriverError("acp_response_identity_mismatch: 回执不属于原请求；不会补发。")
            if "error" in value:
                # Generic Gemini -32000 also covers configuration/network errors.
                # No matching error text is promoted to authentication proof.
                raise DriverError("acp_request_unconfirmed: 原协议请求返回错误；认证与效果仍未确认。")
            if set(value) - {"jsonrpc", "id", "result"} or not isinstance(value.get("result"), dict):
                raise DriverError("acp_response_invalid: 原请求没有有效结构化回执。")
            return value["result"]

    def _next(self):
        while True:
            now = time.monotonic()
            if self.stopped() or now >= self.deadline:
                if not self.turn_dispatched:
                    raise DriverError("acp_stopped_before_prompt")
                if not self.cancel_requested:
                    self._cancel()
                if now >= self.cancel_deadline:
                    raise DriverError("acp_cancel_unknown: 原 prompt 尚无匹配最终回执；不会重放。")
            try:
                value = self.events.get_nowait()
            except queue.Empty:
                self._decisions()
                try:
                    value = self.events.get(timeout=.05)
                except queue.Empty:
                    if self.read_error:
                        raise self.read_error
                    continue
            if value is None:
                raise DriverError("acp_transport_unknown: 进程结束但原 prompt 未返回最终回执。")
            if "method" in value:
                self._event(value)
                continue
            return value

    def probe(self):
        self._current(require_unexpired=True)
        result = self._request("initialize", {"protocolVersion": WIRE_VERSION,
            "clientInfo": {"name": "PocketWorld Companion", "version": "0.3.0"},
            "clientCapabilities": {"fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False}})
        self.hello = normalized_hello(result)
        if self.expected_hello is not None and self.hello != self.expected_hello:
            raise DriverError("acp_hello_changed: 原审阅后的实际握手能力已经改变。")
        self.protocol_verified = True
        self._current(require_unexpired=True)
        return self.hello

    def run(self, prompt, thread_id=None, model=None):
        if thread_id is not None:
            raise DriverError("acp_continuation_unsupported")
        with self.dispatch_guard():
            if self.started:
                raise DriverError("acp_original_prompt_already_attempted: 只能查询原任务，不能重放。")
            self.started = True
        self.probe()
        self.waiting_session = True
        admitted = self._request("session/new", {"cwd": self.cwd, "mcpServers": []})
        self.waiting_session = False
        session = admitted.get("sessionId")
        modes, models = admitted.get("modes"), admitted.get("models")
        if not _text(session) or not isinstance(modes, dict) or modes.get("currentModeId") != "default":
            raise DriverError("acp_mode_unconfirmed: 原会话未确认固定 default 模式。")
        available_modes = modes.get("availableModes")
        if not isinstance(available_modes, list) or len(available_modes) > 16 or not any(isinstance(v, dict) and v.get("id") == "default" for v in available_modes):
            raise DriverError("acp_mode_unconfirmed: 原会话没有提供固定 default 模式。")
        available = models.get("availableModels") if isinstance(models, dict) else None
        current_model = models.get("currentModelId") if isinstance(models, dict) else None
        identifiers = [v.get("modelId") if isinstance(v, dict) else None for v in available] if isinstance(available, list) else []
        if not 1 <= len(identifiers) <= 100 or any(not _text(v) for v in identifiers) or len(set(identifiers)) != len(identifiers) or current_model not in identifiers:
            raise DriverError("acp_model_unconfirmed: 原会话模型清单或当前模型不完整。")
        if model and current_model != model:
            raise DriverError("acp_model_changed: 实际模型不是主人原选择；不会自动切换或重试。")
        self.session_id, self.reported_model, self.mode_id = session, current_model, "default"
        for value in self.early_updates:
            self._event(value)
        self.early_updates.clear()
        self._state()
        self._current(require_unexpired=True, deep=True)
        self.prompt_id = self._id()
        # The fixed prefix makes leading slash/dollar input ordinary literal text.
        literal = "The following is the owner's literal task text, not a CLI command.\n\n" + prompt
        with self.dispatch_guard():
            if self.stopped() or self.closed.is_set() or time.monotonic() >= self.deadline:
                raise DriverError("acp_stopped_before_prompt")
            self.turn_dispatched = True
            self._state()  # Persist the original prompt identity before a possible partial write.
            if self.stopped():
                raise DriverError("acp_stopped_before_prompt_write")
            ticket = self._ticket({"jsonrpc": "2.0", "id": self.prompt_id, "method": "session/prompt",
                                   "params": {"sessionId": self.session_id, "prompt": [{"type": "text", "text": literal}]}})
        self._send(ticket)
        result = self._response(self.prompt_id)
        reason = result.get("stopReason")
        if reason not in {"end_turn", "cancelled", "max_turn_requests", "max_tokens", "refusal"}:
            raise DriverError("acp_terminal_unknown: 原 prompt 的结束原因不受支持。")
        self.finished = True
        self._withdraw_pending("withdrawn")
        self.progress.add("turn", "session/prompt", self.prompt_id, status=reason)
        return safe_state({**self._snapshot(), "status": "cancelled" if reason == "cancelled" else "completed" if reason == "end_turn" else "failed",
            "stopReason": reason, "promptTerminalConfirmed": True,
            "message": "Gemini 返回了原 prompt 的结束回执；请核对实际结果，未据此确认任务目标、修改或模型账单。"})

    def _snapshot(self):
        with self.lock:
            approvals = [{key: value for key, value in item.items() if key not in {"wireId", "decisionQueued"}} for item in self.pending.values()]
        return {"protocol": PROTOCOL, "protocolVerified": self.protocol_verified,
            "sessionId": self.session_id, "threadId": self.session_id, "promptRequestId": self.prompt_id,
            "reportedModel": self.reported_model, "modeId": self.mode_id, "modelInferenceVerified": False,
            "readOnlyConfirmed": False, "osReadIsolationVerified": False,
            "pendingApprovals": approvals, "approvalReceipts": [dict(v) for v in self.approval_receipts],
            "pendingQuestions": [], "questionReceipts": [], "progress": self.progress.snapshot(),
            "output": self.output, "outputTruncated": self.truncated, "cancelNotificationSent": self.cancel_sent,
            "status": "awaiting_approval" if approvals else "running"}

    def _state(self):
        self.on_state(safe_state(self._snapshot()))

    def _reply(self, wire, value, stopping=False):
        with self.dispatch_guard():
            ticket = self._ticket({"jsonrpc": "2.0", "id": wire, **value}, stopping=stopping)
        self._send(ticket)

    def _event(self, value):
        method, params = value.get("method"), value.get("params", {})
        if not isinstance(method, str) or not isinstance(params, dict):
            raise DriverError("acp_event_invalid")
        if "id" in value:
            wire = value["id"]
            if not _wire_id(wire):
                raise DriverError("acp_request_identity_invalid")
            marker = canonical([type(wire).__name__, wire])
            if marker in self.seen_requests or len(self.seen_requests) >= 128:
                raise DriverError("acp_request_identity_reused: 不能替换原主人审批。")
            self.seen_requests.add(marker)
            if method != "session/request_permission":
                self._reply(wire, {"error": {"code": -32601, "message": "Unsupported client method; no host action or permission was granted"}}, stopping=self.cancel_sent)
                return
            self._permission(wire, params)
            return
        if method == "session/update":
            if self.session_id is None and self.waiting_session:
                if len(self.early_updates) >= 8:
                    raise DriverError("acp_early_update_limit")
                self.early_updates.append(value)
                return
            if params.get("sessionId") != self.session_id or self.session_id is None:
                raise DriverError("acp_session_identity_mismatch")
            if self.finished:
                return
            update = params.get("update")
            if not isinstance(update, dict):
                raise DriverError("acp_update_invalid")
            kind = update.get("sessionUpdate")
            if kind == "agent_message_chunk":
                content = update.get("content")
                if not isinstance(content, dict) or content.get("type") != "text" or not isinstance(content.get("text"), str):
                    raise DriverError("acp_text_update_invalid")
                raw = (self.output + content["text"]).encode()
                self.truncated |= len(raw) > MAX_OUTPUT
                self.output = raw[:MAX_OUTPUT].decode("utf-8", errors="ignore")
            elif kind in {"tool_call", "tool_call_update"}:
                if not _text(update.get("toolCallId")):
                    raise DriverError("acp_tool_observation_invalid")
                self.progress.add("tool", kind, update["toolCallId"], title=str(update.get("title", "")), status=str(update.get("status", "unknown")))
            elif kind == "current_mode_update" and update.get("currentModeId") != "default":
                raise DriverError("acp_mode_changed")
            # available_commands_update and arbitrary peer capabilities are never host grants.
            self._state()
        elif method == "$/cancel_request":
            with self.lock:
                for key, pending in list(self.pending.items()):
                    if pending["wireId"] == params.get("requestId"):
                        self.pending.pop(key)
            self._state()

    def _permission(self, wire, params):
        if (params.get("sessionId") != self.session_id or self.session_id is None or not self.turn_dispatched or self.finished
                or self.cancel_requested or self.stopped()):
            self._reply(wire, {"result": {"outcome": {"outcome": "cancelled"}}}, stopping=True)
            return
        if (set(params) != {"sessionId", "toolCall", "options"} or not lossless_owner_payload(params)
                or self.redact(params) != params
                or len(canonical(params).encode()) > 32768):
            self._reject_permission(wire, "acp_permission_unreviewable: 完整审批内容超出支持范围，或包含不可公开的凭据。")
        tool, options = params.get("toolCall"), params.get("options")
        if not isinstance(tool, dict) or not _text(tool.get("toolCallId")) or not isinstance(options, list) or not 1 <= len(options) <= 8:
            self._reject_permission(wire, "acp_permission_invalid")
        seen = set()
        for option in options:
            if (not isinstance(option, dict) or set(option) != {"optionId", "kind", "name"}
                    or not _text(option.get("optionId")) or option["optionId"] in seen or not _text(option.get("name"), 1000)
                    or option.get("kind") not in {"allow_once", "reject_once", "allow_always", "reject_always"}):
                self._reject_permission(wire, "acp_permission_option_invalid")
            seen.add(option["optionId"])
        allow = [v["optionId"] for v in options if v["kind"] == "allow_once"]
        reject = [v["optionId"] for v in options if v["kind"] == "reject_once"]
        if len(allow) > 1 or len(reject) > 1:
            self._reject_permission(wire, "acp_permission_option_ambiguous")
        request_id = str(uuid.uuid4())
        entry = {"requestId": request_id, "requestHash": digest(params), "wireId": wire,
            "protocol": PROTOCOL, "method": "acp/session/request_permission", "backendId": BACKEND,
            "sessionId": self.session_id, "promptRequestId": self.prompt_id, "toolCallId": tool["toolCallId"],
            "toolCall": json.loads(canonical(tool)), "options": json.loads(canonical(options)),
            "canAccept": bool(allow), "allowOnceOptionId": allow[0] if allow else "",
            "rejectOnceOptionId": reject[0] if reject else "", "scopeEnforced": False, "osReadIsolationVerified": False}
        with self.lock:
            full = len(self.pending) >= 12
            if not full:
                self.pending[request_id] = entry
        if full:
            self._reject_permission(wire, "acp_permission_budget")
        self._state()

    def _reject_permission(self, wire, reason):
        self._reply(wire, {"result": {"outcome": {"outcome": "cancelled"}}}, stopping=True)
        raise DriverError(reason)

    def decide(self, request_id, decision):
        if not isinstance(decision, str) or decision not in {"accept", "decline", "cancel"}:
            raise DriverError("acp_decision_invalid")
        with self.lock:
            item = self.pending.get(request_id)
            if not item or item.get("decisionQueued") or self.finished or self.closed.is_set() or self.stopped() or self.cancel_requested:
                raise DriverError("acp_approval_stale")
            if decision == "accept" and not item["canAccept"]:
                raise DriverError("acp_once_permission_unavailable")
            item["decisionQueued"] = True
            self.decisions.put_nowait((request_id, decision))

    def _decisions(self):
        while True:
            try:
                request_id, decision = self.decisions.get_nowait()
            except queue.Empty:
                return
            self._current()
            with self.dispatch_guard():
                with self.lock:
                    item = self.pending.pop(request_id, None)
                if item is None or self.finished:
                    continue
                if self.stopped() or self.cancel_requested:
                    decision = "cancel"
                receipt = {"requestId": request_id, "requestHash": item["requestHash"], "sessionId": self.session_id,
                    "promptRequestId": self.prompt_id, "decision": decision, "status": "sending"}
                self.approval_receipts.append(receipt)
                self._state()
                if self.stopped() or self.cancel_requested:
                    decision = receipt["decision"] = "cancel"
                option = item["allowOnceOptionId"] if decision == "accept" else item["rejectOnceOptionId"] if decision == "decline" else ""
                outcome = {"outcome": "selected", "optionId": option} if option else {"outcome": "cancelled"}
                ticket = self._ticket({"jsonrpc": "2.0", "id": item["wireId"], "result": {"outcome": outcome}}, stopping=decision != "accept")
            try:
                self._send(ticket)
            except DriverError:
                receipt["status"] = "unknown"
                self._state()
                raise
            receipt["status"] = "sent"
            self._state()

    def _withdraw_pending(self, status):
        with self.lock:
            entries = list(self.pending.values())
            self.pending.clear()
        for entry in entries:
            self.approval_receipts.append({"requestId": entry["requestId"], "requestHash": entry["requestHash"],
                "sessionId": self.session_id, "promptRequestId": self.prompt_id, "status": status, "decision": "cancel"})
        return entries

    def _cancel(self):
        self.cancel_deadline = time.monotonic() + 3
        with self.dispatch_guard():
            self.cancel_requested = True
            entries = self._withdraw_pending("sending")
            self._state()
            ticket = self._ticket({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": self.session_id}}, stopping=True)
        self._send(ticket)
        self.cancel_sent = True
        for entry in entries:
            receipt = next(v for v in reversed(self.approval_receipts) if v["requestId"] == entry["requestId"])
            try:
                self._reply(entry["wireId"], {"result": {"outcome": {"outcome": "cancelled"}}}, stopping=True)
            except DriverError:
                receipt["status"] = "unknown"
                self._state()
                raise
            receipt["status"] = "sent"
        self._state()

    def can_steer(self, _expected=None):
        return False

    def answer_question(self, *_args):
        raise DriverError("acp_questions_unsupported: 此固定后端没有类型化问询输入。")

    def close_steering(self):
        self.closed.set()
        self._withdraw_pending("unknown" if self.turn_dispatched and not self.finished else "withdrawn")
        for receipt in self.approval_receipts:
            if receipt["status"] == "sending":
                receipt["status"] = "unknown"
        # Preserve the manager's terminal classification; cleanup never reopens running.
        value = self._snapshot()
        value.pop("status")
        self.on_state(safe_state(value))
