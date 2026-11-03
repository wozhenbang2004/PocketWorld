"""Codex app-server JSON-RPC adapter, authored from official protocol contracts."""
import json
import hashlib
import re
import os
from contextlib import nullcontext
from pathlib import Path
import queue
import threading
import time
import uuid
from remote_interactions import QuestionMailbox, Progress, question
import group_input_audience


class DriverError(Exception):
    pass


class CodexAppServerDriver:
    def __init__(self, process, cwd, write_allowed, on_state, stopped, timeout, restricted_read_only=False, dispatch_guard=None, assignment_write=False, output_limit=196608, assignment_paths=None, assignment_project=False):
        self.process, self.cwd, self.write_allowed = process, cwd, write_allowed
        self.on_state, self.stopped = on_state, stopped
        self.restricted_read_only = restricted_read_only
        self.assignment_write = assignment_write
        self.assignment_project = bool(assignment_project)
        self.assignment_paths = set(assignment_paths or [])
        self.assignment_file_items = {}
        self.assignment_write_verified = False
        self.external_tools_disabled = False
        self.output_limit = output_limit
        self.output_limit_exceeded = False
        self.dispatch_guard = dispatch_guard or nullcontext
        self.deadline = time.monotonic() + timeout
        self.messages = queue.Queue(maxsize=128)
        self.decisions = queue.Queue(maxsize=32)
        self.steers = queue.Queue(maxsize=8)
        self.steer_replies = {}
        self.input_steering_verified = False
        self.steering_unknown = False
        self.steering_unresolved = False
        self.pending = {}
        self.seen_approvals = set()
        self.approval_receipts = []
        self.questions = QuestionMailbox()
        self.progress = Progress("codex_app_server", enabled=not assignment_write or assignment_project)
        self.output_truncated = self.diff_truncated = False
        self.closed = False
        self.lock = threading.RLock()
        self.serial = 0
        self.thread_id = self.turn_id = None
        self.completed = None
        self.turn_dispatched = False
        self.turn_start_acknowledged = False
        self.protocol_verified = False
        self.initialized = False
        self.restricted_profile_confirmed = False
        self.interrupt_sent = False
        self.interrupt_deadline = None
        self.output = ""
        self.diff = ""
        self.reported_file_changes = set()
        self.runtime_version = None
        self.model_options = None
        self.requested_model = self.requested_effort = None
        self.reported_model = self.reported_effort = None
        self.configuration_source = None
        self.context_window = None
        self.context_source = None
        self.context_model_ambiguous = False
        self.fork_dispatched = self.fork_refused = False
        self.fork_proof = None
        self.group_generation = self.group_expected_generation = self.group_runtime_generation = None
        self.group_last_source_check = 0
        self.group_api_revoked = False
        self.group_api_facts = None
        self.reader = threading.Thread(target=self._read, daemon=True, name="pocket-codex-rpc")
        self.reader.start()

    def _read(self):
        try:
            while not self.closed:
                line = self.process.stdout.readline(1_048_577)
                if not line:
                    self.messages.put(DriverError("Codex transport closed before a final receipt"), timeout=.5)
                    return
                if len(line) > 1_048_576:
                    self.messages.put(DriverError("Codex protocol message exceeds limit"), timeout=.5)
                    return
                try:
                    value = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    self.messages.put(DriverError("Codex app-server returned invalid JSON"), timeout=.5)
                    return
                if not isinstance(value, dict):
                    self.messages.put(DriverError("Codex protocol message must be an object"), timeout=.5)
                    return
                self.messages.put(value, timeout=.5)
        except (OSError, ValueError, queue.Full):
            try:
                self.messages.put(DriverError("Codex transport could not be read"), timeout=.5)
            except queue.Full:
                pass

    def _send(self, value):
        try:
            with self.lock:
                remaining = memoryview((json.dumps(value, ensure_ascii=False) + "\n").encode())
                while remaining:
                    written = self.process.stdin.write(remaining)
                    if not written:
                        raise OSError("Pipe did not accept data")
                    remaining = remaining[written:]
                self.process.stdin.flush()
        except (OSError, ValueError):
            raise DriverError("Codex request dispatch is unconfirmed")

    def _id(self):
        self.serial += 1
        return self.serial

    def _pump(self):
        now = time.monotonic()
        if self.group_runtime_generation is not None and now-self.group_last_source_check>=1:
            self.group_last_source_check=now
            try:
                self.group_api_revoked |= self.group_runtime_generation()!=self.group_expected_generation
            except group_input_audience.Unavailable:
                self.group_api_revoked=True
        if self.stopped() or now >= self.deadline or self.group_api_revoked:
            if not self.turn_dispatched:
                raise DriverError("Codex stopped before turn dispatch")
            if self.interrupt_deadline is None:
                self.interrupt_deadline = now + 3
            if self.turn_id and not self.interrupt_sent:
                with self.dispatch_guard():
                    self._send({"id": self._id(), "method": "turn/interrupt", "params": {"threadId": self.thread_id, "turnId": self.turn_id}})
                    self.interrupt_sent = True
            if now >= self.interrupt_deadline:
                raise DriverError("Codex cancellation has no matching final turn receipt; effects are unknown")
        for request_id, pending in list(self.steer_replies.items()):
            if now >= pending["deadline"] and not pending.get("timedOut"):
                # Keep the original RPC correlation so a late ACK can settle this exact message.
                pending["timedOut"] = True
                self.steering_unknown = True
                pending["receipt"]("unknown", {"dispatched": True, "message": "Steer acknowledgement did not arrive; do not resend"})
                self._state()
        while True:
            try:
                request, receipt = self.steers.get_nowait()
            except queue.Empty:
                break
            # Serializes the final eligibility check and write with Companion cancellation.
            with self.dispatch_guard():
                if not self.can_steer(request["expectedTurnId"]):
                    receipt("rejected", {"dispatched": False, "message": "Original turn is no longer available for steering"})
                    continue
                request_id = self._id()
                # Persistence must succeed before touching the pipe; it can itself observe a stop.
                receipt("sending", {"dispatched": None})
                if not self.can_steer(request["expectedTurnId"]):
                    receipt("rejected", {"dispatched": False, "message": "Stopped before input dispatch"})
                    continue
                self.steer_replies[request_id] = {"receipt": receipt, "turnId": request["expectedTurnId"], "deadline": now + 5}
                try:
                    self._send({"id": request_id, "method": "turn/steer", "params": {"threadId": self.thread_id,
                                "expectedTurnId": request["expectedTurnId"], "clientUserMessageId": request["messageId"],
                                "input": [{"type": "text", "text": request["text"]}]}})
                except DriverError:
                    self.steer_replies.pop(request_id, None)
                    self.steering_unknown = True
                    receipt("unknown", {"dispatched": None, "message": "Steer write is unconfirmed; do not resend"})
                    raise
        while True:
            try:
                request_id, decision = self.decisions.get_nowait()
            except queue.Empty:
                break
            with self.dispatch_guard():
                with self.lock:
                    request = self.pending.pop(str(request_id), None)
                if request and self.completed is None:
                    if self.interrupt_sent or self.stopped():
                        decision = "decline"
                    receipt = {"requestId": str(request_id), "decision": decision, "status": "sending"}
                    self.approval_receipts.append(receipt)
                    self._state()  # Durable intent precedes this exact one-time answer.
                    if self.interrupt_sent or self.stopped():
                        decision = receipt["decision"] = "decline"
                    try:
                        self._send({"id": request["wireId"], "result": {"decision": decision}})
                    except DriverError:
                        receipt["status"] = "unknown"
                        self._state()
                        raise
                    receipt["status"] = "sent"
                    self._state()
        # Previously received request retractions/terminal events win over new answers.
        if self.messages.empty():
            with self.dispatch_guard():
                self.questions.pump(self._question_live, self._question_send, self._state)
        try:
            value = self.messages.get(timeout=.1)
        except queue.Empty:
            return None
        if isinstance(value, Exception):
            raise value
        if "method" in value:
            self._event(value)
            return None
        wire_id = value.get("id")
        pending = self.steer_replies.pop(wire_id, None) if isinstance(wire_id, int) and not isinstance(wire_id, bool) else None
        if pending:
            if isinstance(value.get("error"), dict):
                refused = value["error"].get("code") in {-32600, -32601, -32602}
                self.steering_unresolved = self.steering_unresolved or not refused
                pending["receipt"]("rejected" if refused else "unknown", {"dispatched": True, "errorCode": value["error"].get("code"), "message": "Codex returned a steering error; no fallback turn or replay was started"})
                if value["error"].get("code") == -32601:
                    self.input_steering_verified = False
            elif isinstance(value.get("result"), dict) and value["result"].get("turnId") == pending["turnId"]:
                pending["receipt"]("accepted", {"dispatched": True, "turnId": pending["turnId"], "message": "Input accepted by the original turn; this is not a completion receipt"})
            else:
                self.steering_unresolved = True
                pending["receipt"]("unknown", {"dispatched": True, "message": "Steer acknowledgement does not identify the original turn; do not resend"})
            self.steering_unknown = self.steering_unresolved or any(item.get("timedOut") for item in self.steer_replies.values())
            self._state()
            return None
        return value

    def _exchange(self, method, params):
        request_id = self._id()
        self._send({"id": request_id, "method": method, "params": params})
        while True:
            response = self._pump()
            if response and response.get("id") == request_id:
                return response

    def _request(self, method, params):
        response = self._exchange(method, params)
        if "error" in response:
            raise DriverError("Codex rejected " + method + "; check its installed protocol version and account configuration")
        if not isinstance(response.get("result"), dict):
            raise DriverError("Codex response lacks a result object")
        return response["result"]

    def _start_turn(self, params):
        self._check_group_api_mode()
        request_id = self._id()
        with self.dispatch_guard():
            if self.stopped() or time.monotonic() >= self.deadline:
                raise DriverError("Codex stopped before turn dispatch")
            if self.group_generation is not None and (self.group_api_revoked or self.group_generation() != self.group_expected_generation):
                raise DriverError("API-key source changed at the original turn dispatch boundary; no group prompt sent")
            self.turn_dispatched = True
            self._send({"id": request_id, "method": "turn/start", "params": params})
        while True:
            response = self._pump()
            if response and response.get("id") == request_id:
                if "error" in response or not isinstance(response.get("result"), dict):
                    raise DriverError("Codex turn/start was not acknowledged; do not replay")
                self.turn_start_acknowledged = True
                return response["result"].get("turn", {})
            # A matching actual turn/completed can reconcile a missing start ACK.
            # This is the received terminal notification, not a synthesized RPC ACK.
            if self.completed is not None and self.turn_id is not None:
                return self.completed

    def _probe_steering(self):
        # No thread has been loaded in this connection. The valid random ID cannot name our turn.
        missing_thread = str(uuid.uuid4())
        response = self._exchange("turn/steer", {"threadId": missing_thread, "expectedTurnId": str(uuid.uuid4()), "input": []})
        error = response.get("error")
        self.input_steering_verified = isinstance(error, dict) and error.get("code") == -32600 and error.get("message") == "thread not found: " + missing_thread

    def can_steer(self, expected_turn_id=None):
        return self.input_steering_verified and not self.steering_unknown and self.turn_id is not None and self.completed is None and not self.interrupt_sent and not self.stopped() and (expected_turn_id is None or expected_turn_id == self.turn_id)

    def steer(self, request, receipt):
        if not self.can_steer(request.get("expectedTurnId")):
            raise DriverError("Steering requires the verified, active original turn")
        self.steers.put_nowait((dict(request), receipt))

    def close_steering(self):
        self.closed = True
        with self.lock:
            self.pending.clear()
        while True:
            try:
                _request, receipt = self.steers.get_nowait()
                receipt("rejected", {"dispatched": False, "message": "Turn ended before this input was dispatched"})
            except queue.Empty:
                break
        for request_id, pending in list(self.steer_replies.items()):
            self.steer_replies.pop(request_id, None)
            pending["receipt"]("unknown", {"dispatched": True, "message": "Turn/transport ended without a steering acknowledgement; do not resend"})

    def _inside(self, value):
        if not value:
            return True
        try:
            Path(value).resolve().relative_to(Path(self.cwd).resolve())
            return True
        except (ValueError, TypeError, OSError):
            return False

    def _event(self, message):
        method, params = message["method"], message.get("params", {})
        if not isinstance(params, dict):
            raise DriverError("Codex event params must be an object")
        if method == "account/updated" and self.group_generation is not None:
            if params.get("authMode") != "apikey":
                self.group_api_revoked = True
            return
        if "id" in message:
            if method == "item/tool/requestUserInput":
                wire = message["id"]
                key = str(wire)
                if key in self.seen_approvals or len(self.seen_approvals) >= 128:
                    raise DriverError("Codex reused a request identity or exceeded the interaction budget")
                self.seen_approvals.add(key)
                if (params.get("threadId"), params.get("turnId")) != (self.thread_id, self.turn_id) or not self._question_live():
                    self._send({"id": wire, "error": {"code": -32600, "message": "Original question scope is no longer live"}})
                    return
                try:
                    if not isinstance(params.get("itemId"), str) or not params["itemId"] or not isinstance(params.get("isBlocking"), bool):
                        raise ValueError("Question lacks item identity/blocking capability")
                    value = question("codex_app_server", {"method": method, "threadId": self.thread_id,
                                     "turnId": self.turn_id, "itemId": params["itemId"], "isBlocking": params["isBlocking"]}, params.get("questions"))
                    self.questions.register(value, wire)
                except ValueError as error:
                    raise DriverError(str(error))
                self.progress.add("question", method, params["itemId"], summary="Original agent is asking for user input; not permission approval")
                self._state()
            elif method in {"item/commandExecution/requestApproval", "item/fileChange/requestApproval"}:
                approval_id = str(message["id"])
                if approval_id in self.seen_approvals or len(self.seen_approvals) >= 128:
                    raise DriverError("Codex reused an approval identity or exceeded the interaction budget")
                self.seen_approvals.add(approval_id)
                if params.get("threadId") != self.thread_id or params.get("turnId") != self.turn_id or self.interrupt_sent or self.stopped():
                    self._send({"id": message["id"], "result": {"decision": "decline"}})
                    return
                safe = self._inside(params.get("cwd")) and self._inside(params.get("grantRoot")) and not params.get("additionalPermissions") and not params.get("networkApprovalContext") and (method != "item/fileChange/requestApproval" or self.write_allowed)
                item = self.assignment_file_items.get(str(params.get("itemId")))
                if self.assignment_write:
                    # A command approval may authorize an unsandboxed retry. cwd is not confinement.
                    # Only a known exact selected-file change can be approved once; never grantRoot.
                    exact_file = method == "item/fileChange/requestApproval" and item is not None
                    explicit_command = self.assignment_project and method == "item/commandExecution/requestApproval" and isinstance(params.get("command"), str) and 0 < len(params["command"]) <= 8000 and params.get("cwd") == self.cwd
                    # Full project owner scope explicitly acknowledges same-user command risk. Still one exact
                    # pending request, never session grants, execpolicy amendments, network/grantRoot expansion.
                    safe = (exact_file or explicit_command) and self.assignment_write_verified and not params.get("grantRoot") and not params.get("proposedExecpolicyAmendment") and not params.get("proposedNetworkPolicyAmendments") and safe
                request = {"requestId": str(message["id"]), "wireId": message["id"], "method": method, "reason": str(params.get("reason", ""))[:2000], "command": params.get("command"), "cwd": params.get("cwd"), "canAccept": safe, "threadId": params.get("threadId"), "turnId": params.get("turnId"), "itemId": params.get("itemId"), "grantRoot": params.get("grantRoot")}
                if self.assignment_write:
                    request["files"] = item or []
                    request["scopeReason"] = "Explicit full-project same-user command scope; approve this exact request once, no session/network/grantRoot expansion" if self.assignment_project else "Exact selected-file change only; command/sandbox escalation, session grants and unknown paths are not supported"
                    request["projectCommandRisk"] = self.assignment_project and method == "item/commandExecution/requestApproval"
                with self.lock:
                    if len(self.pending) >= 12:
                        self._send({"id": message["id"], "result": {"decision": "decline"}})
                    else:
                        self.pending[str(message["id"])] = request
                        self.progress.add("approval", method, str(params.get("itemId", "")), requestId=approval_id,
                                          summary="Original single tool/file permission request; history is not approval authority")
                self._state()
            elif method == "item/permissions/requestApproval":
                self._send({"id": message["id"], "result": {"permissions": {}}})
            elif method == "mcpServer/elicitation/request":
                self._send({"id": message["id"], "result": {"action": "decline", "content": None}})
            else:
                self._send({"id": message["id"], "error": {"code": -32601, "message": "Unsupported interactive request; no tool was approved"}})
            return
        if params.get("threadId") is not None and self.thread_id is not None and params["threadId"] != self.thread_id:
            return
        if params.get("turnId") is not None and self.turn_id is not None and params["turnId"] != self.turn_id:
            return
        if method in {"item/started", "item/completed"}:
            item = params.get("item")
            if isinstance(item, dict) and isinstance(item.get("id"), str):
                self.progress.add("tool", method, item["id"], tool=str(item.get("type", "unknown")),
                                  status=str(item.get("status", "started" if method == "item/started" else "completed")),
                                  command=item.get("command") if isinstance(item.get("command"), str) else None,
                                  exitCode=item.get("exitCode") if isinstance(item.get("exitCode"), int) else None)
                self._state()
        if method == "item/completed" and isinstance(params.get("item"), dict) and params["item"].get("type") == "fileChange":
            for change in params["item"].get("changes", [])[:2000]:
                if isinstance(change, dict) and isinstance(change.get("path"), str) and len(self.reported_file_changes) < 2000:
                    try:
                        candidate = Path(change["path"])
                        relative = (candidate if candidate.is_absolute() else Path(self.cwd) / candidate).resolve().relative_to(Path(self.cwd).resolve())
                        self.reported_file_changes.add(str(relative))
                    except (OSError, ValueError):
                        pass  # Out-of-project protocol paths are not attribution proof.
        elif method == "item/commandExecution/outputDelta":
            self.progress.add("tool_output", method, params.get("itemId", ""), text=params.get("delta") if isinstance(params.get("delta"), str) else "")
            self._state()
        elif method == "turn/plan/updated":
            plan = params.get("plan")
            if isinstance(plan, list):
                lines = [str(step.get("step", ""))[:500] + " [" + str(step.get("status", "unknown"))[:40] + "]" for step in plan[:20] if isinstance(step, dict)]
                self.progress.add("plan", method, summary="\n".join(lines), explanation=params.get("explanation") if isinstance(params.get("explanation"), str) else "")
                self._state()
        elif method == "thread/settings/updated" and params.get("threadId") == self.thread_id:
            actual = params.get("threadSettings")
            if isinstance(actual, dict):
                self._read_configuration(actual, "thread/settings/updated")
                self._state()
        elif method == "model/rerouted" and (params.get("threadId"), params.get("turnId")) == (self.thread_id, self.turn_id):
            if self._option_text(params.get("toModel")):
                self.context_model_ambiguous = True
                self.context_window = self.context_source = None
                self._read_configuration({"model": params["toModel"]}, "model/rerouted")
                self._state()
        elif method == "thread/tokenUsage/updated":
            usage = params.get("tokenUsage")
            if isinstance(usage, dict) and self.reported_model and not self.context_model_ambiguous and self.thread_id and self.turn_id and (params.get("threadId"), params.get("turnId")) == (self.thread_id, self.turn_id):
                window = usage.get("modelContextWindow")
                self.context_window = window if type(window) is int and 0 < window <= 100_000_000 else None
                self.context_source = "thread/tokenUsage/updated.tokenUsage.modelContextWindow" if self.context_window else None
            if isinstance(usage, dict):
                total = usage.get("total")
                counters = {key: total[key] for key in ("inputTokens", "cachedInputTokens", "outputTokens", "reasoningOutputTokens", "totalTokens") if isinstance(total, dict) and isinstance(total.get(key), int) and not isinstance(total.get(key), bool) and 0 <= total[key] <= 10**15}
                self.progress.add("usage", method, counters=json.dumps(counters, sort_keys=True), usageScope="provider_thread_cumulative_not_job_budget_or_invoice")
                self._state()
        if self.assignment_write and method == "item/started":
            item = params.get("item", {})
            if isinstance(item, dict) and item.get("type") == "fileChange" and isinstance(item.get("id"), str):
                changes = item.get("changes")
                checked = []
                if isinstance(changes, list) and 0 < len(changes) <= (2000 if self.assignment_project else 8) and len(self.assignment_file_items) < 128:
                    for change in changes:
                        if not isinstance(change, dict) or not isinstance(change.get("path"), str):
                            break
                        try:
                            path = Path(change["path"])
                            if not path.is_absolute():
                                path = Path(self.cwd) / path
                            path = Path(os.path.normpath(str(path)))
                            relative = path.relative_to(Path(self.cwd).resolve()).as_posix()
                            if path != path.resolve():
                                break
                        except (OSError, ValueError):
                            break
                        kind = change.get("kind")
                        if not isinstance(kind, dict) or kind.get("type") not in ({"add", "update", "delete"} if self.assignment_project else {"update"}) or kind.get("move_path") is not None:
                            break
                        if relative not in self.assignment_paths or not isinstance(change.get("diff"), str) or len(change["diff"].encode()) > self.output_limit:
                            break
                        checked.append({"path": relative, "kind": change.get("kind"), "diff": change["diff"]})
                    else:
                        if sum(len(change["diff"].encode()) for change in checked) <= self.output_limit:
                            self.assignment_file_items[item["id"]] = checked
        byte_bounded_output = self.assignment_write or self.group_generation is not None
        if method == "item/agentMessage/delta":
            text = self.output + str(params.get("delta", ""))
            self.output_truncated |= len(text) > 196608
            combined = text.encode("utf-8")
            available = max(0, self.output_limit - len(self.diff.encode("utf-8")))
            self.output = combined[:available].decode("utf-8", errors="ignore") if byte_bounded_output else text[:196608]
            self.output_truncated |= byte_bounded_output and len(combined) > available
            if byte_bounded_output and len(combined) + len(self.diff.encode("utf-8")) > self.output_limit:
                self.output_limit_exceeded = True
                self._state()
                raise DriverError("Approved external_job output byte limit exceeded; no later turn is dispatched")
            self._state()
        elif method == "turn/diff/updated":
            raw = str(params.get("diff", "")).encode("utf-8")
            available = max(0, self.output_limit - len(self.output.encode("utf-8")))
            self.diff = raw[:available].decode("utf-8", errors="ignore") if byte_bounded_output else str(params.get("diff", ""))[:196608]
            self.diff_truncated |= len(raw) > available if byte_bounded_output else len(str(params.get("diff", ""))) > 196608
            if byte_bounded_output and len(raw) + len(self.output.encode("utf-8")) > self.output_limit:
                self.output_limit_exceeded = True
                self._state()
                raise DriverError("Approved external_job output/diff byte limit exceeded")
            self._state()
        elif method == "turn/started":
            turn = params.get("turn", {})
            if isinstance(turn, dict) and isinstance(turn.get("id"), str) and turn["id"] and (self.turn_id is None or self.turn_id == turn["id"]):
                self.turn_id = turn["id"]
                self._state()
        elif method == "turn/completed":
            turn = params.get("turn", {})
            if isinstance(turn, dict) and turn.get("id") == self.turn_id:
                self.completed = turn
                self.questions.finish()
                self.progress.add("turn", method, self.turn_id, status=str(turn.get("status", "unknown")))
                with self.lock:
                    self.pending.clear()
                self._state()
        elif method == "serverRequest/resolved":
            self.questions.resolve(params.get("requestId"))
            with self.lock:
                self.pending.pop(str(params.get("requestId")), None)
                for receipt in self.approval_receipts:
                    if receipt["requestId"] == str(params.get("requestId")):
                        receipt["status"] = "resolved"
            self._state()

    def _state(self):
        with self.lock:
            approvals = [{key: value for key, value in entry.items() if key != "wireId"} for entry in self.pending.values()]
        questions, answers = self.questions.snapshot()
        self.on_state({"modelRuntime": self._model_runtime(), "modelOptions": self.model_options, "reportedModel": self.reported_model, "protocol": "codex_app_server", "protocolVerified": self.protocol_verified, "threadId": self.thread_id, "turnId": self.turn_id, "turnStartAcknowledged": self.turn_start_acknowledged, "pendingApprovals": approvals, "approvalReceipts": [dict(value) for value in self.approval_receipts], "pendingQuestions": questions, "questionReceipts": answers, "progress": self.progress.snapshot(), "output": self.output, "diff": self.diff, "reportedFileChanges": sorted(self.reported_file_changes), "outputTruncated": self.output_truncated, "diffTruncated": self.diff_truncated, "inputSteeringVerified": self.input_steering_verified, "steeringUnknown": self.steering_unknown, "readOnlyConfirmed": self.restricted_profile_confirmed, "externalToolsDisabled": self.external_tools_disabled or self.restricted_profile_confirmed, "assignmentWriteSandboxVerified": self.assignment_write_verified, "outputLimitExceeded": self.output_limit_exceeded, "status": "awaiting_approval" if approvals else "awaiting_input" if any(value.get("isBlocking") for value in questions) else "running"})

    def _question_live(self):
        return self.protocol_verified and self.turn_dispatched and self.thread_id is not None and self.turn_id is not None and self.completed is None and not self.closed and not self.interrupt_sent and not self.stopped() and not self.assignment_write

    def _question_send(self, wire_id, response):
        self._send({"id": wire_id, "result": response})

    def answer_question(self, request_id, request_hash, answer_id, answers):
        try:
            return self.questions.answer(request_id, request_hash, answer_id, answers, self._question_live, self._state)
        except (ValueError, queue.Full) as error:
            raise DriverError(str(error))

    def decide(self, request_id, decision):
        if not isinstance(decision, str) or decision not in {"accept", "decline", "cancel"}:
            raise DriverError("Approval decision must be accept, decline or cancel")
        with self.lock:
            request = self.pending.get(str(request_id))
            if not request or request.get("decisionQueued") or self.completed is not None or self.closed:
                raise DriverError("Approval request is no longer pending")
            if decision == "accept" and (not request["canAccept"] or self.stopped() or self.interrupt_sent):
                raise DriverError("This request exceeds the configured workspace; no permission was granted")
            self.decisions.put_nowait((str(request_id), decision))
            request["decisionQueued"] = True

    def _initialize(self):
        if self.initialized:
            return
        actual = self._request("initialize", {"clientInfo": {"name": "pocket_world", "title": "Pocket World", "version": "0.3.0"}})
        agent = actual.get("userAgent")
        version = re.search(r"(?<![0-9])(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)(?![0-9])", agent) if isinstance(agent, str) else None
        self.runtime_version = version.group(1) if version else None
        self._send({"method": "initialized", "params": {}})
        self.initialized = True

    def read_original_turn(self, thread_id, turn_id):
        """Only official stored metadata/history reads. Unsupported current RPC shapes fail closed.
        A new app-server notLoaded status is NOT process quiescence; the original supervisor
        separately requires its persisted native process identity and stopped group.
        """
        self._initialize()
        result = self._request("thread/read", {"threadId": thread_id, "includeTurns": False})
        thread = result.get("thread")
        if not isinstance(thread, dict) or thread.get("id") != thread_id or thread.get("cwd") != self.cwd or not isinstance(thread.get("status"), dict) or thread["status"].get("type") not in {"notLoaded", "idle"}:
            raise DriverError("Stored original thread identity/cwd/runtime status is unavailable or active; no turn was started")
        history = self._request("thread/turns/list", {"threadId": thread_id, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})
        data = history.get("data")
        if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict) or data[0].get("id") != turn_id or data[0].get("status") not in {"completed", "failed", "interrupted"}:
            raise DriverError("Latest stored turn differs or is not terminal; no old turn/answer/approval was replayed")
        return {"threadId": thread_id, "turnId": turn_id, "status": data[0]["status"], "threadStatus": thread["status"]["type"], "readOnlyInspection": True}

    @staticmethod
    def _option_text(value, maximum=200):
        return isinstance(value, str) and 0 < len(value) <= maximum and value == value.strip() and not any(ord(c) < 32 for c in value)

    def _model_catalog(self):
        catalog = self._request("model/list", {"limit": 100, "includeHidden": False})
        if not isinstance(catalog.get("data"), list) or len(catalog["data"]) > 100:
            raise DriverError("Codex model discovery has an unsupported shape")
        models = []
        for item in catalog["data"]:
            if not isinstance(item, dict) or not self._option_text(item.get("model")):
                raise DriverError("Codex model discovery identity is invalid")
            choices = item.get("supportedReasoningEfforts")
            efforts = []
            if choices is not None:
                if not isinstance(choices, list) or len(choices) > 32:
                    raise DriverError("Codex advertised effort options exceed the explicit bound")
                for choice in choices:
                    if not isinstance(choice, dict) or not self._option_text(choice.get("reasoningEffort"), 80):
                        raise DriverError("Codex effort options are malformed")
                    efforts.append({"id": choice["reasoningEffort"], "description": str(choice.get("description", ""))[:160]})
                if len({value["id"] for value in efforts}) != len(efforts):
                    raise DriverError("Codex advertised ambiguous effort identities")
            default = item.get("defaultReasoningEffort")
            models.append({"id": item["model"], "name": str(item.get("displayName", item["model"]))[:200],
                           "isDefault": item.get("isDefault") is True, "efforts": efforts,
                           "effortCapability": "advertised" if choices is not None else "not_reported",
                           "defaultEffort": default if any(value["id"] == default for value in efforts) else None})
        if len({item["id"] for item in models}) != len(models):
            raise DriverError("Codex advertised ambiguous model identities")
        source = {"protocol": "codex_model_options_v1", "runtimeVersion": self.runtime_version,
                  "models": models, "truncated": bool(catalog.get("nextCursor")),
                  "changeModes": ["new_job", "settled_continuation"], "runningTurnChanges": False}
        source["digest"] = hashlib.sha256(json.dumps(source, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        self.model_options = source
        return models

    def _read_configuration(self, value, source):
        model, effort = value.get("model"), value.get("reasoningEffort", value.get("effort"))
        if not self._option_text(model):
            self.reported_model = self.reported_effort = self.configuration_source = None
            self.context_window = self.context_source = None
            return
        if model != self.reported_model:
            if self.turn_dispatched and self.reported_model is not None:
                self.context_model_ambiguous = True
            self.context_window = self.context_source = None
        self.reported_model = model
        self.reported_effort = effort if self._option_text(effort, 80) else None
        self.configuration_source = source

    def _model_runtime(self):
        return {"protocol": "native_model_runtime_v1", "worker": "codex_app_server",
                "runtimeVersion": self.runtime_version, "threadId": self.thread_id, "turnId": self.turn_id,
                "requestedModel": self.requested_model, "requestedEffort": self.requested_effort,
                "reportedModel": self.reported_model, "reportedEffort": self.reported_effort,
                "configurationSource": self.configuration_source,
                "modelReadback": "known" if self.reported_model else "unknown",
                "effortReadback": "known" if self.reported_effort else "unknown",
                "contextWindow": {"status": "known" if self.context_window else "unknown", "tokens": self.context_window,
                                  "reportedModel": self.reported_model, "source": self.context_source,
                                  "unknownReason": "model_changed_within_turn_usage_has_no_model_tag" if self.context_model_ambiguous else "not_reported" if self.context_window is None else None,
                                  "scope": "original_native_model_not_phone_budget_or_invoice"}}

    def probe(self, model=None, effort=None):
        """Read protocol/account/catalog only. Never creates a thread or starts a turn."""
        self._initialize()
        self._probe_steering()
        self._read_only_overrides()
        account = self._request("account/read", {"refreshToken": False})
        if not isinstance(account.get("requiresOpenaiAuth"), bool):
            raise DriverError("Codex account response has an unsupported shape")
        identity = account.get("account")
        configured = not account["requiresOpenaiAuth"] or isinstance(identity, dict) and identity.get("type") in {"apiKey", "chatgpt", "amazonBedrock", "gcp"}
        models = self._model_catalog()
        model_available = bool(models) if model is None else any(item["id"] == model for item in models)
        effort_available = effort is None or any(item["id"] == model and any(value["id"] == effort for value in item["efforts"]) for item in models)
        return {"available": configured and model_available and effort_available, "protocolVerified": True, "accountConfigured": configured, "authMode": identity.get("type", "unknown") if isinstance(identity, dict) and identity.get("type") in {"apiKey", "chatgpt", "amazonBedrock", "gcp"} else "unknown", "authModeObservedFrom": "original_app_server_account_read", "billingBackendVerified": False,
                "modelInferenceVerified": False, "configReadVerified": True, "inputSteeringVerified": self.input_steering_verified, "models": models, "modelCatalogTruncated": self.model_options["truncated"], "modelOptions": self.model_options, "selectedEffortAvailable": effort_available if effort is not None else None,
                "selectedModelAvailable": model_available,
                "message": "The selected effort is not advertised for this exact native model; no turn was executed" if not effort_available else "Protocol and configured account/catalog checked; no model turn was executed" if configured and model_available else "Configure the Codex account and select a model advertised by its catalog"}

    def require_group_api_mode(self, expected_generation, current_generation, runtime_generation=None):
        self.group_expected_generation, self.group_generation = expected_generation, current_generation
        self.group_runtime_generation=runtime_generation or current_generation
        return self._check_group_api_mode()

    def _check_group_api_mode(self, runtime=False):
        if self.group_generation is None:
            return None
        self._initialize()
        try:
            current_generation = self.group_runtime_generation if runtime else self.group_generation
            before = current_generation()
            facts = group_input_audience.actual_api_mode(self._request("account/read", {"refreshToken": False}), self._request("config/read", {}))
            after = current_generation()
        except group_input_audience.Unavailable as failure:
            raise DriverError(str(failure)) from failure
        if self.group_api_revoked or before != after or after != self.group_expected_generation:
            raise DriverError("Actual API-key mode/source generation changed; no group prompt or fallback was dispatched")
        self.group_api_facts = {**facts, "credentialGeneration": after}
        return dict(self.group_api_facts)

    def fork_original_thread(self, thread_id, turn_id):
        """One real thread/fork, not a fallback thread/start or a filesystem rollback.

        Only the latest known terminal turn is supported here. This also makes an old
        peer ignoring lastTurnId fail safe: no unreviewed later turn may be copied.
        Missing provenance or lost acknowledgement is not permission to try again.
        """
        self.read_original_turn(thread_id, turn_id)
        with self.dispatch_guard():
            if self.stopped() or time.monotonic() >= self.deadline:
                raise DriverError("Stopped before original conversation fork")
            self.on_state({"sessionBranch": {"phase": "sending", "sourceThreadId": thread_id, "sourceTurnId": turn_id, "filesRestored": False}})
            self.fork_dispatched = True
            request_id = self._id()
            self._send({"id": request_id, "method": "thread/fork", "params": {"threadId": thread_id, "lastTurnId": turn_id, "excludeTurns": True}})
        # Do not hold the Companion cancellation monitor while waiting for peer IO.
        while True:
            response = self._pump()
            if response and response.get("id") == request_id:
                break
        if isinstance(response.get("error"), dict):
            self.fork_refused = response["error"].get("code") in {-32600, -32601, -32602}
            raise DriverError("Installed Codex refused exact thread/fork; no new thread/start fallback or prompt was sent")
        admitted = response.get("result", {}).get("thread")
        if not isinstance(admitted, dict) or not isinstance(admitted.get("id"), str) or not admitted["id"] or admitted["id"] == thread_id or admitted.get("forkedFromId") != thread_id:
            raise DriverError("Conversation fork lacks a distinct original-provenance receipt; creation is unconfirmed")
        branch = admitted["id"]
        # Verify actual stored history of the new ID before the first model turn.
        self.read_original_turn(branch, turn_id)
        self.fork_proof = {"phase": "created", "sourceThreadId": thread_id, "sourceTurnId": turn_id, "threadId": branch,
                           "forkedFromId": admitted["forkedFromId"], "sessionId": admitted.get("sessionId"),
                           "historyVerifiedThroughOriginalTerminalTurn": True, "filesRestored": False, "oldApprovalOrBudgetInherited": False}
        self.thread_id = branch
        self.on_state({"threadId": branch, "sessionBranch": dict(self.fork_proof)})
        return branch

    def _read_only_overrides(self):
        # Effective configuration stays inside the CLI transport; it can contain credentials.
        effective = self._request("config/read", {"includeLayers": False, "cwd": self.cwd}).get("config")
        if not isinstance(effective, dict):
            raise DriverError("Codex does not expose the effective read-only configuration")
        servers = effective.get("mcp_servers", {})
        if not isinstance(servers, dict) or len(servers) > 64:
            raise DriverError("Codex MCP configuration cannot be bounded for this read-only run")
        disabled = {}
        for name, server in servers.items():
            if not isinstance(name, str) or not isinstance(server, dict):
                raise DriverError("Codex MCP configuration has an unsupported shape")
            disabled[name] = {**server, "enabled": False}
        return {"mcp_servers": disabled, "features": {"apps": False, "plugins": False, "hooks": False,
                "codex_hooks": False, "plugin_hooks": False, "browser_use": False, "browser_use_external": False,
                "computer_use": False, "multi_agent": False}, "web_search": "disabled"}

    def run(self, prompt, thread_id=None, model=None, effort=None, model_options_digest=None):
        self._initialize()
        self.protocol_verified = True
        self.requested_model, self.requested_effort = model, effort
        if effort is not None or model_options_digest is not None or self.runtime_version is not None:
            models = self._model_catalog()
            if model_options_digest is not None and (self.model_options["digest"] != model_options_digest or not any(row["id"] == model for row in models)):
                raise DriverError("Codex native CLI/model options changed since owner review; no turn started")
            if effort is not None and (not model or not self._option_text(effort, 80) or not any(item["id"] == model and any(value["id"] == effort for value in item["efforts"]) for item in models)):
                raise DriverError("Codex effort requires an exact model and its currently advertised native option; no turn started")
        self._check_group_api_mode()
        self._probe_steering()
        params = {"cwd": self.cwd, "approvalPolicy": "unlessTrusted", "sandbox": "workspaceWrite" if self.write_allowed else "readOnly"}
        if self.restricted_read_only or self.assignment_write:
            if self.write_allowed:
                if not self.assignment_write:
                    raise DriverError("A restricted read-only team run cannot use a writable sandbox")
            params["config"] = self._read_only_overrides()
            if self.assignment_write:
                params["config"]["sandbox_workspace_write"] = {"writable_roots": [self.cwd], "network_access": False, "exclude_tmpdir_env_var": True, "exclude_slash_tmp": True}
        if model:
            params["model"] = model
        if effort is not None:
            params.setdefault("config", {})["model_reasoning_effort"] = effort
        if thread_id:
            params["threadId"] = thread_id
            params["excludeTurns"] = True
        admitted = self._request("thread/resume" if thread_id else "thread/start", params)
        thread = admitted.get("thread", {})
        if not isinstance(thread, dict) or not isinstance(thread.get("id"), str):
            raise DriverError("Codex did not return a thread identity")
        if thread_id is not None and thread["id"] != thread_id:
            raise DriverError("Codex resumed a different thread; no turn was started")
        self.thread_id = thread["id"]
        self._read_configuration(admitted, "thread/resume.response" if thread_id else "thread/start.response")
        if effort is not None and (self.reported_model != model or self.reported_effort != effort):
            raise DriverError("Codex did not read back the exact requested model/effort; no turn was started")
        self._state()
        if self.restricted_read_only or self.assignment_write:
            sandbox = admitted.get("sandbox")
            if self.restricted_read_only and (not isinstance(sandbox, dict) or sandbox.get("type") != "readOnly"):
                raise DriverError("Codex did not confirm the required read-only sandbox; no turn was started")
            if self.assignment_write and (not isinstance(sandbox, dict) or sandbox.get("type") != "workspaceWrite" or sandbox.get("writableRoots") != [self.cwd] or sandbox.get("networkAccess") is not False or sandbox.get("excludeTmpdirEnvVar") is not True or sandbox.get("excludeSlashTmp") is not True or admitted.get("cwd") != self.cwd):
                raise DriverError("Codex did not confirm the exact selected-copy writable root/network/tmp policy; no turn was started")
            inventory = self._request("mcpServerStatus/list", {"threadId": self.thread_id, "limit": 100})
            if not isinstance(inventory.get("data"), list) or inventory.get("nextCursor") or any(not isinstance(item, dict) or not isinstance(item.get("tools"), dict) or item["tools"] or item.get("toolsError") for item in inventory["data"]):
                raise DriverError("Codex external tools remain available; no read-only team turn was started")
            self.restricted_profile_confirmed = self.restricted_read_only
            self.assignment_write_verified = self.assignment_write
            self.external_tools_disabled = True
        policy = {"type": "workspaceWrite", "writableRoots": [self.cwd], "networkAccess": False} if self.write_allowed else {"type": "readOnly"}
        if self.assignment_write:
            policy.update(excludeTmpdirEnvVar=True, excludeSlashTmp=True)
        turn_request = {"threadId": self.thread_id, "input": [{"type": "text", "text": prompt}], "cwd": self.cwd, "approvalPolicy": "unlessTrusted", "sandboxPolicy": policy}
        if model:
            turn_request["model"] = model
        if effort is not None:
            turn_request["effort"] = effort
        turn = self._start_turn(turn_request)
        if not isinstance(turn, dict) or not isinstance(turn.get("id"), str):
            raise DriverError("Codex did not confirm the turn identity; do not replay")
        if self.turn_id is not None and self.turn_id != turn["id"]:
            raise DriverError("Codex turn identity changed during admission; do not replay")
        self.turn_id = turn["id"]
        self._state()
        while self.completed is None:
            self._pump()
        status = self.completed.get("status")
        if status not in {"completed", "failed", "interrupted"}:
            raise DriverError("Codex turn ended with an unsupported status")
        if self.group_generation is not None:
            # Same actual app-server after the final turn, not another CLI/model request.
            # Admission TTL is not renewed and cannot cancel a legitimately long accepted run.
            try:
                self._check_group_api_mode(runtime=True)
                self.group_api_facts.update(postTurnAccountReadVerified=True, generationCheckedAfterTurn=True)
            except DriverError:
                self.group_api_revoked = True
                self.group_api_facts = {"authMode": "unknown", "postTurnAccountReadVerified": False,
                                        "billingBackendVerified": False, "internalTokensKnown": False,
                                        "internalCostKnown": False}
        return {"modelRuntime": self._model_runtime(), "modelOptions": self.model_options, "reportedModel": self.reported_model, "status": "unknown" if self.group_api_revoked else "completed" if status == "completed" else "cancelled" if status == "interrupted" else "failed", "threadId": self.thread_id, "turnId": self.turn_id, "turnStartAcknowledged": self.turn_start_acknowledged, "output": self.output, "diff": self.diff, "reportedFileChanges": sorted(self.reported_file_changes), "outputTruncated": self.output_truncated, "diffTruncated": self.diff_truncated, "readOnlyConfirmed": self.restricted_profile_confirmed, "externalToolsDisabled": self.external_tools_disabled or self.restricted_profile_confirmed, "assignmentWriteSandboxVerified": self.assignment_write_verified, "outputLimitExceeded": self.output_limit_exceeded, "message": "Codex returned a final turn receipt; inspect its actual changes", "protocol": "codex_app_server", "protocolVerified": True, "approvalReceipts": self.approval_receipts, "pendingApprovals": [], "pendingQuestions": [], "questionReceipts": self.questions.snapshot()[1], "progress": self.progress.snapshot()}
