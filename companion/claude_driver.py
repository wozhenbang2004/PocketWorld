"""Independent Claude JSONL/control client; no upstream application code.

Wire shapes are checked against Anthropic's Agent SDK types and CLI contract.
A resumed session is an explicit new invocation, never a replay of an unknown one.
"""
from contextlib import nullcontext
import json
import hashlib
from pathlib import Path
import queue
import re
import threading
import time
import uuid

from codex_driver import DriverError
from remote_interactions import QuestionMailbox, Progress, question

MAX_EVENT = 1_048_576
MAX_OUTPUT = 196_608
CHILD_SOURCE_PROTOCOL = "claude_complete_child_v1"
REQUIRED_FLAGS = ("--safe-mode", "--restricted", "--settings", "--input-format", "--output-format", "--verbose",
                  "--include-partial-messages", "--permission-prompts", "--permission-prompt-tool",
                  "--permission-mode", "--tools", "--allowedTools", "--disallowedTools",
                  "--session-id", "--resume", "--max-turns", "--model")


def session_uuid(value):
    try:
        parsed = uuid.UUID(value)
    except (ValueError, TypeError, AttributeError):
        raise DriverError("Claude session must be an exact UUID, not a name or transcript path")
    if str(parsed) != value:
        raise DriverError("Claude session UUID must use canonical lower-case form")
    return value


def command(session_id, write_allowed=False, model=None, resume=False, effort=None):
    tools = "Read,Glob,Grep,AskUserQuestion,Edit,Write,Bash" if write_allowed else "Read,Glob,Grep,AskUserQuestion"
    args = ["claude", "--safe-mode", "--restricted", "--settings",
            '{"permissions":{"ask":["Edit","Write","Bash","AskUserQuestion"]}}', "-p", "--input-format", "stream-json",
            "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--tools", tools, "--allowedTools", "Read,Glob,Grep",
            "--disallowedTools", "mcp__*", "--permission-mode", "default",
            "--permission-prompts", "host", "--permission-prompt-tool", "stdio", "--max-turns", "20",
            "--resume" if resume else "--session-id", session_uuid(session_id)]
    if model:
        args += ["--model", model]
    if effort is not None:
        args += ["--effort", effort]
    return args


def inspect_cli(version, help_text):
    match = re.search(r"(?<!\d)(\d+)\.(\d+)\.(\d+)(?!\d)", version)
    missing = [flag for flag in REQUIRED_FLAGS if flag not in help_text]
    compatible = bool(match) and tuple(map(int, match.groups())) >= (2, 1, 259) and not missing
    return {"cliVersion": match.group(0) if match else None, "cliFlagsVerified": compatible,
            "missingFlags": missing, "protocolVerified": False, "accountConfigured": None,
            "modelInferenceVerified": False, "available": compatible,
            "effortFlagVerified": "--effort" in help_text,
            "message": "Local CLI version/help checked only; account, stream protocol, model/effort catalog and inference remain unverified; no new initialize probe was started"}


class ClaudeStreamDriver:
    def __init__(self, process, session_id, on_state, stopped, timeout,
                 dispatch_guard=None, cwd=None, write_allowed=False):
        self.process = process
        self.session_id = session_uuid(session_id)
        self.on_state, self.stopped = on_state, stopped
        self.dispatch_guard = dispatch_guard or nullcontext
        self.cwd = Path(cwd or ".").resolve()
        self.write_allowed = write_allowed
        self.deadline = time.monotonic() + timeout
        self.events = queue.Queue(maxsize=128)
        self.closed = threading.Event()
        self.read_error = None
        self.turn_dispatched = False
        self.protocol_verified = False
        self.initialized = False
        self.finished = False
        self.prompt_id = str(uuid.uuid4())
        self.init_id = str(uuid.uuid4())
        self.interrupt_id = None
        self.interrupt_acknowledged = False
        self.interrupt_deadline = None
        self.pending = {}
        self.seen_requests = set()
        self.approval_receipts = []
        self.questions = QuestionMailbox()
        self.question_tools = {}
        self.progress = Progress("claude_stream_json")
        self.prompt_result_identity = "session_only_legacy_no_uuid"
        self.decisions = queue.Queue(maxsize=12)
        self.lock = threading.RLock()
        self.output = ""
        self.truncated = False
        self.assistant_error = False
        self.event_count = 0
        self.reported_model = None
        self.runtime_version = None
        self.requested_model = self.requested_effort = None
        self.model_options = None
        self.context_window = self.context_source = None
        self.reader = threading.Thread(target=self._read, daemon=True, name="pocket-claude-jsonl")
        self.reader.start()

    def _read(self):
        try:
            while not self.closed.is_set():
                line = self.process.stdout.readline(MAX_EVENT + 1)
                if not line:
                    value = None
                elif len(line) > MAX_EVENT or not line.endswith(b"\n"):
                    raise DriverError("Claude stream frame is incomplete or exceeds the limit")
                else:
                    value = json.loads(line)
                    if not isinstance(value, dict):
                        raise DriverError("Claude stream frame must be an object")
                self.events.put(value, timeout=.5)
                if value is None:
                    return
        except (OSError, ValueError, DriverError, queue.Full):
            self.read_error = DriverError("Claude stream could not be validated; do not replay")

    def _state(self):
        with self.lock:
            approvals = [{k: v for k, v in entry.items() if k not in {"wireId", "decisionQueued"}}
                         for entry in self.pending.values()]
            receipts = [dict(value) for value in self.approval_receipts]
        questions, answers = self.questions.snapshot()
        self.on_state({"modelOptions": self.model_options, "modelRuntime": self._model_runtime(), "protocol": "claude_stream_json", "sessionId": self.session_id,
                       "threadId": self.session_id, "protocolVerified": self.protocol_verified,
                       "sessionIdentityConfirmed": self.protocol_verified,
                       "controlProtocolVerified": self.initialized, "promptRequestId": self.prompt_id,
                       "reportedModel": self.reported_model,
                       "output": self.output, "outputTruncated": self.truncated,
                       "eventCount": self.event_count, "pendingApprovals": approvals,
                       "pendingQuestions": questions, "questionReceipts": answers, "progress": self.progress.snapshot(),
                       "promptResultIdentity": self.prompt_result_identity,
                       "approvalReceipts": receipts, "interruptAcknowledged": self.interrupt_acknowledged,
                       "status": "awaiting_approval" if approvals else "awaiting_input" if questions else "running"})

    def _append(self, value):
        if not isinstance(value, str):
            raise DriverError("Claude text delta is invalid")
        self.truncated |= len(self.output) + len(value) > MAX_OUTPUT
        self.output = (self.output + value)[:MAX_OUTPUT]

    def _child_source(self, event):
        """Only complete, session-confirmed public blocks carry native child lineage.

        Agent SDK docs expose parent_tool_use_id on complete assistant/user messages,
        not child token streams. No Agent tool, child session, resume or grant is added.
        """
        parent = event.get("parent_tool_use_id")
        if parent is None:
            return {}
        if not self.protocol_verified or event.get("session_id") != self.session_id:
            raise DriverError("Child observation lacks its confirmed original parent session")
        def identifier(value):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}", value):
                raise DriverError("Child observation identity is unsupported; not attached to the parent")
            return value
        source = {"childSourceProtocol": CHILD_SOURCE_PROTOCOL, "parentToolUseId": identifier(parent),
                  "parentSessionId": self.session_id, "parentPromptRequestId": self.prompt_id}
        message = event.get("message")
        for key, value in (("nativeAgentId", event.get("agent_id")), ("nativeEventId", event.get("uuid")),
                           ("nativeMessageId", message.get("id") if isinstance(message, dict) else None)):
            if value is not None:
                source[key] = identifier(value)
        return source

    def _send(self, value):
        data = (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            view = memoryview(data)
            while view:
                written = self.process.stdin.write(view)
                if not written:
                    raise OSError("Pipe did not accept data")
                view = view[written:]
            self.process.stdin.flush()
        except (OSError, ValueError):
            raise DriverError("Claude pipe write is unconfirmed; do not replay")

    def _control(self, request_id, request):
        self._send({"type": "control_request", "request_id": request_id, "request": request})

    def _answer(self, wire_id, response):
        self._send({"type": "control_response", "response": {"subtype": "success",
                    "request_id": wire_id, "response": response}})

    def _next(self):
        while True:
            now = time.monotonic()
            if self.stopped() or now >= self.deadline:
                if not self.turn_dispatched:
                    raise DriverError("Claude stopped before prompt dispatch")
                if self.interrupt_id is None:
                    with self.dispatch_guard():
                        self.interrupt_id = str(uuid.uuid4())
                        self.interrupt_deadline = now + 3
                        self._control(self.interrupt_id, {"subtype": "interrupt"})
                if now >= self.interrupt_deadline:
                    raise DriverError("Claude interruption lacks a final result; effects are unknown")
            try:
                # Already-received retractions/results take precedence over queued approvals.
                return self.events.get_nowait()
            except queue.Empty:
                self._decisions()
                with self.dispatch_guard():
                    self.questions.pump(self._question_live, self._answer, self._state)
            try:
                return self.events.get(timeout=.05)
            except queue.Empty:
                if self.read_error:
                    raise self.read_error

    def _inside(self, value):
        if not isinstance(value, str) or not value or "\x00" in value:
            return False
        try:
            candidate = Path(value)
            (candidate if candidate.is_absolute() else self.cwd / candidate).resolve().relative_to(self.cwd)
            return True
        except (ValueError, OSError):
            return False

    def _permission(self, event):
        wire_id, request = event.get("request_id"), event.get("request")
        if not isinstance(wire_id, str) or not wire_id or len(wire_id) > 200 or wire_id in self.seen_requests:
            raise DriverError("Claude permission request identity is invalid or reused")
        if len(self.seen_requests) >= 128 or not isinstance(request, dict):
            raise DriverError("Claude permission request budget or shape is invalid")
        self.seen_requests.add(wire_id)
        if request.get("subtype") != "can_use_tool":
            self._send({"type": "control_response", "response": {"subtype": "error",
                        "request_id": wire_id, "error": "Unsupported interaction; no permission granted"}})
            raise DriverError("Claude requested an unsupported host interaction")
        tool, data, use_id = request.get("tool_name"), request.get("input"), request.get("tool_use_id")
        if not isinstance(tool, str) or not isinstance(data, dict) or not isinstance(use_id, str) or not use_id or len(use_id) > 200:
            raise DriverError("Claude permission cannot be bound to an exact tool call")
        if len(json.dumps(data)) > 32_768:
            raise DriverError("Claude permission input exceeds review budget")
        safe = self.protocol_verified and self.turn_dispatched and not self.finished and not request.get("agent_id")
        safe = safe and not self.stopped() and self.interrupt_id is None
        safe = safe and (request.get("blocked_path") is None or self._inside(request["blocked_path"]))
        if tool == "AskUserQuestion":
            if not safe or request.get("blocked_path") is not None or use_id in self.question_tools:
                self._answer(wire_id, {"behavior": "deny", "message": "Original question is outside this live invocation"})
                return
            try:
                value = question("claude_stream_json", {"method": "claude/AskUserQuestion", "sessionId": self.session_id,
                                 "promptRequestId": self.prompt_id, "toolUseId": use_id, "isBlocking": True}, data.get("questions"))
                self.questions.register(value, wire_id, data["questions"])
            except ValueError as error:
                raise DriverError(str(error))
            self.question_tools[use_id] = wire_id
            self.progress.add("question", "claude/AskUserQuestion", use_id, summary="Original agent clarifying question; not execution permission")
            self._state()
            return
        if tool in {"Read", "Glob", "Grep"}:
            path = data.get("file_path") if tool == "Read" else data.get("path", ".")
            safe = safe and self._inside(path)
        elif tool in {"Write", "Edit"}:
            safe = safe and self.write_allowed and self._inside(data.get("file_path"))
        elif tool == "Bash":
            # A reviewed shell command is not a filesystem sandbox. This is exposed
            # only in an explicitly writable environment, never an analysis team.
            safe = safe and self.write_allowed and isinstance(data.get("command"), str) and bool(data["command"])
            safe = safe and not data.get("run_in_background") and not data.get("dangerouslyDisableSandbox")
        else:
            safe = False
        if not safe or len(self.pending) >= 12:
            self._answer(wire_id, {"behavior": "deny", "message": "Outside this invocation's authorized scope"})
            return
        public_id = str(uuid.uuid4())
        with self.lock:
            self.pending[public_id] = {"requestId": public_id, "wireId": wire_id, "toolUseId": use_id,
                "method": "claude/can_use_tool", "tool": tool, "input": data, "canAccept": True,
                "reason": str(request.get("decision_reason") or request.get("title") or "Tool permission")[:2000],
                "cwd": str(self.cwd), "sessionId": self.session_id, "promptRequestId": self.prompt_id,
                "scopeEnforced": False}
            self.progress.add("approval", "claude/can_use_tool", use_id, requestId=public_id,
                              summary="Original single tool permission request; history is not approval authority")

    def _decisions(self):
        while True:
            try:
                request_id, decision = self.decisions.get_nowait()
            except queue.Empty:
                return
            with self.dispatch_guard():
                with self.lock:
                    entry = self.pending.pop(request_id, None)
                if entry is None or self.finished:
                    continue
                allow = decision == "accept" and not self.stopped() and self.interrupt_id is None
                receipt = {"requestId": request_id, "toolUseId": entry["toolUseId"],
                           "decision": "accept" if allow else "decline", "status": "sending"}
                self.approval_receipts.append(receipt)
                self._state()  # Persist exact reply intent before touching stdio.
                allow = allow and not self.stopped() and self.interrupt_id is None
                receipt["decision"] = "accept" if allow else "decline"
                response = {"behavior": "allow", "updatedInput": entry["input"]} if allow else {
                    "behavior": "deny", "message": "Owner declined or invocation is stopping"}
                try:
                    self._answer(entry["wireId"], response)
                except DriverError:
                    receipt["status"] = "unknown"
                    self._state()
                    raise
                receipt["status"] = "sent"
                self._state()

    def _question_live(self):
        return self.initialized and self.protocol_verified and self.turn_dispatched and not self.finished and not self.closed.is_set() and self.interrupt_id is None and not self.stopped()

    def answer_question(self, request_id, request_hash, answer_id, answers):
        try:
            return self.questions.answer(request_id, request_hash, answer_id, answers, self._question_live, self._state)
        except (ValueError, queue.Full) as error:
            raise DriverError(str(error))

    @staticmethod
    def _option_text(value, maximum=200):
        return isinstance(value, str) and 0 < len(value) <= maximum and value == value.strip() and not any(ord(c) < 32 for c in value)

    def _read_model_options(self, initialized):
        version = initialized.get("claude_code_version")
        self.runtime_version = version if isinstance(version, str) and re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", version) else None
        raw = initialized.get("models")
        if raw is None:
            return  # Original older control handshake remains usable, but no model/effort support is invented.
        if not isinstance(raw, list) or len(raw) > 100:
            raise DriverError("Claude model options are malformed or exceed the explicit bound")
        models = []
        for row in raw:
            if not isinstance(row, dict) or not self._option_text(row.get("value")):
                raise DriverError("Claude model option identity is malformed")
            raw_efforts = row.get("supportedEffortLevels")
            efforts = []
            if raw_efforts is not None:
                if not isinstance(raw_efforts, list) or len(raw_efforts) > 32 or any(not self._option_text(value, 80) for value in raw_efforts) or len(set(raw_efforts)) != len(raw_efforts):
                    raise DriverError("Claude native effort enumeration is unsupported")
                if row.get("supportsEffort") is True:
                    efforts = [{"id": value, "description": "Native advertised effort; effective stream readback unavailable"} for value in raw_efforts]
            model = {"id": row["value"], "name": row["displayName"][:200] if isinstance(row.get("displayName"), str) else row["value"],
                     "efforts": efforts, "effortCapability": "advertised" if raw_efforts is not None and row.get("supportsEffort") is True else "not_reported"}
            if self._option_text(row.get("resolvedModel")):
                model["resolvedModel"] = row["resolvedModel"]
            models.append(model)
        if len({row["id"] for row in models}) != len(models):
            raise DriverError("Claude native model enumeration is ambiguous")
        source = {"protocol": "claude_initialize_model_options_v1", "runtimeVersion": self.runtime_version,
                  "models": models, "truncated": False, "changeModes": ["settled_continuation"],
                  "runningTurnChanges": False, "observation": "existing_owner_approved_job_initialize_not_a_readonly_startup_probe"}
        source["digest"] = hashlib.sha256(json.dumps(source, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        self.model_options = source

    def _model_runtime(self):
        return {"protocol": "native_model_runtime_v1", "worker": "claude", "runtimeVersion": self.runtime_version,
                "sessionId": self.session_id, "promptRequestId": self.prompt_id,
                "requestedModel": self.requested_model, "requestedEffort": self.requested_effort,
                "reportedModel": self.reported_model, "reportedEffort": None,
                "modelReadback": "known" if self.reported_model else "unknown",
                "configurationSource": "system/init.model" if self.reported_model else None,
                "effortReadback": "unknown_stream_sdk_omits_effective_effort",
                "effortRequestSource": "original_cli_session_flag" if self.requested_effort else None,
                "contextWindow": {"status": "known" if self.context_window else "unknown", "tokens": self.context_window,
                                  "reportedModel": self.reported_model, "source": self.context_source,
                                  "scope": "original_native_model_not_phone_budget_or_invoice"}}

    def _read_context_window(self, result):
        # A result's usage can mix parent/child/fallback models. Never borrow one model's window.
        self.context_window = self.context_source = None
        rows = result.get("modelUsage")
        if not self.reported_model or not isinstance(rows, dict) or set(rows) != {self.reported_model}:
            return
        actual = rows[self.reported_model]
        window = actual.get("contextWindow") if isinstance(actual, dict) else None
        if type(window) is int and 0 < window <= 100_000_000:
            self.context_window, self.context_source = window, "result.modelUsage.exact_native_model.contextWindow"

    def run(self, prompt, thread_id=None, model=None, effort=None, model_options_digest=None):
        if thread_id is not None and thread_id != self.session_id:
            raise DriverError("Claude continuation does not match its launch session")
        self.requested_model, self.requested_effort = model, effort
        self._control(self.init_id, {"subtype": "initialize", "hooks": None})
        reply = self._next()
        response = reply.get("response") if isinstance(reply, dict) else None
        if not isinstance(response, dict) or reply.get("type") != "control_response" or response.get("request_id") != self.init_id or response.get("subtype") != "success" or not isinstance(response.get("response"), dict):
            raise DriverError("Claude control handshake was not confirmed; no prompt was sent")
        self.initialized = True
        self._read_model_options(response["response"])
        if model_options_digest is not None and (self.model_options is None or self.model_options["digest"] != model_options_digest):
            raise DriverError("Claude native CLI/model options changed since owner review; no prompt was sent")
        if effort is not None or model_options_digest is not None:
            selected = next((row for row in self.model_options["models"] if model in {row["id"], row.get("resolvedModel")}), None) if self.model_options else None
            if selected is None or effort is not None and not any(row["id"] == effort for row in selected["efforts"]):
                raise DriverError("Claude model/effort is not advertised by this actual native initialize; no prompt was sent")
        with self.dispatch_guard():
            if self.stopped():
                raise DriverError("Claude stopped before prompt dispatch")
            self.turn_dispatched = True  # Even a partial pipe write must never be replayed.
            self._send({"type": "user", "uuid": self.prompt_id, "session_id": self.session_id,
                        "parent_tool_use_id": None, "message": {"role": "user", "content": prompt}})
        while True:
            event = self._next()
            if event is None:
                raise DriverError("Claude exited without a structured result; do not replay")
            self.event_count += 1
            kind = event.get("type")
            # Session-scoped events cannot change the selected session. A mismatch is
            # a protocol failure, never a reason to select a different conversation.
            session = event.get("session_id")
            if session is not None and session != self.session_id:
                raise DriverError("Claude event belongs to a different session")
            if "user_message_uuid" in event:
                if event["user_message_uuid"] != self.prompt_id:
                    raise DriverError("Claude event belongs to another prompt; no replacement invocation was started")
                self.prompt_result_identity = "exact_user_message_uuid"
            if "user_message_uuids" in event:
                identifiers = event["user_message_uuids"]
                if not isinstance(identifiers, list) or not identifiers or any(value != self.prompt_id for value in identifiers):
                    raise DriverError("Claude answered another or mixed prompt; input was not replayed")
                self.prompt_result_identity = "exact_user_message_uuids"
            if kind == "system" and event.get("subtype") == "init":
                if session != self.session_id or self.protocol_verified:
                    raise DriverError("Claude initialization identity is missing or repeated")
                if event.get("permissionMode") != "default":
                    raise DriverError("Claude did not confirm the requested permission mode")
                tools = event.get("tools")
                allowed = {"Read", "Glob", "Grep", "EndConversation", "AskUserQuestion"}
                if self.write_allowed:
                    allowed.update({"Edit", "Write", "Bash"})
                if not isinstance(tools, list) or any(not isinstance(tool, str) or tool not in allowed for tool in tools) or event.get("mcp_servers"):
                    raise DriverError("Claude advertised tools outside the requested tool scope")
                self.protocol_verified = True
                self.reported_model = event.get("model") if self._option_text(event.get("model")) else None
                version = event.get("claude_code_version")
                if self.runtime_version and version is not None and version != self.runtime_version:
                    raise DriverError("Claude runtime version changed after initialization; prior prompt outcome is unverified")
            elif kind == "control_request":
                self._permission(event)
            elif kind == "control_cancel_request":
                self.questions.resolve(event.get("request_id"), "withdrawn")
                with self.lock:
                    for key, pending in list(self.pending.items()):
                        if pending["wireId"] == event.get("request_id"):
                            self.pending.pop(key)
            elif kind == "control_response":
                response = event.get("response")
                if not isinstance(response, dict) or response.get("request_id") != self.interrupt_id or self.interrupt_id is None:
                    raise DriverError("Claude returned an unmatched control response")
                if response.get("subtype") != "success":
                    raise DriverError("Claude rejected interruption; effects remain unknown")
                self.interrupt_acknowledged = True
            elif kind == "stream_event":
                if not self.protocol_verified or session != self.session_id:
                    raise DriverError("Claude streamed before confirming its session")
                nested = event.get("event")
                if not isinstance(nested, dict):
                    raise DriverError("Claude stream event is invalid")
                if event.get("parent_tool_use_id") is None and nested.get("type") == "content_block_delta":
                    delta = nested.get("delta")
                    if isinstance(delta, dict) and delta.get("type") == "text_delta":
                        self._append(delta.get("text"))
            elif kind in {"assistant", "user"}:
                child = self._child_source(event)
                if kind == "assistant" and event.get("error") and not child:
                    self.assistant_error = True
                message = event.get("message")
                content = message.get("content") if isinstance(message, dict) else None
                for index, block in enumerate(content[:100] if isinstance(content, list) else []):
                    if not isinstance(block, dict):
                        continue
                    if child and block.get("type") == "text":
                        if not isinstance(block.get("text"), str):
                            raise DriverError("Child public text block is invalid")
                        observation = child.get("nativeEventId") or child.get("nativeMessageId") or "observation-" + str(self.event_count)
                        self.progress.add("child_text", kind + "/text", observation + ":" + str(index), text=block["text"], role=kind, **child)
                    elif block.get("type") == "tool_use":
                        self.progress.add("tool", "assistant/tool_use", block.get("id", ""), tool=str(block.get("name", "unknown")), status="started", **child)
                    elif block.get("type") == "tool_result":
                        tool_id = block.get("tool_use_id")
                        if not child and tool_id in self.question_tools:
                            self.questions.resolve(self.question_tools[tool_id], "tool_result")
                        self.progress.add("tool_result", "user/tool_result", tool_id or "", status="failed" if block.get("is_error") else "completed", text=block.get("content") if isinstance(block.get("content"), str) else "Structured tool result; no full payload displayed", **child)
            elif kind == "tool_progress":
                self.progress.add("tool", kind, event.get("tool_use_id", ""), tool=str(event.get("tool_name", "unknown")), status="running")
            elif kind == "result":
                if not self.protocol_verified or session != self.session_id or not isinstance(event.get("is_error"), bool):
                    raise DriverError("Claude result lacks confirmed session or error status")
                subtype = event.get("subtype")
                known = {"success", "error_during_execution", "error_max_turns", "error_max_budget_usd", "error_max_structured_output_retries"}
                if subtype not in known or (subtype == "success") == event["is_error"]:
                    raise DriverError("Claude result subtype and error status are unsupported or inconsistent")
                result = event.get("result")
                if result is not None:
                    if not isinstance(result, str):
                        raise DriverError("Claude result text is invalid")
                    self.truncated |= len(result) > MAX_OUTPUT
                    self.output = result[:MAX_OUTPUT]
                denied = event.get("permission_denials", [])
                if not isinstance(denied, list):
                    raise DriverError("Claude permission denial result is invalid")
                self._read_context_window(event)
                self.finished = True
                self.questions.finish()
                self.progress.add("turn", "result", self.prompt_id, status=subtype)
                with self.lock:
                    self.pending.clear()
                    for receipt in self.approval_receipts:
                        if receipt["status"] == "sending":
                            receipt["status"] = "unknown"
                final = {"status": "cancelled" if self.interrupt_acknowledged else "failed" if event["is_error"] or self.assistant_error else "completed",
                         "resultSubtype": subtype, "permissionDenialCount": len(denied),
                         "message": "Claude returned a structured result; inspect output and actual changes"}
                return {**final, "modelOptions": self.model_options, "modelRuntime": self._model_runtime(), "protocol": "claude_stream_json", "sessionId": self.session_id,
                        "threadId": self.session_id, "sessionIdentityConfirmed": True,
                        "protocolVerified": True, "controlProtocolVerified": True,
                        "reportedModel": self.reported_model, "promptRequestId": self.prompt_id,
                         "promptResultIdentity": self.prompt_result_identity,
                         "pendingQuestions": [], "questionReceipts": self.questions.snapshot()[1], "progress": self.progress.snapshot(),
                        "interruptAcknowledged": self.interrupt_acknowledged,
                        "output": self.output, "outputTruncated": self.truncated,
                        "eventCount": self.event_count, "pendingApprovals": [],
                        "approvalReceipts": self.approval_receipts}
            self._state()

    def can_steer(self, _expected=None):
        return False

    def decide(self, request_id, decision):
        if not isinstance(decision, str) or decision not in {"accept", "decline", "cancel"}:
            raise DriverError("Approval decision must be accept, decline or cancel")
        with self.lock:
            pending = self.pending.get(request_id) if isinstance(request_id, str) else None
            if not pending or pending.get("decisionQueued") or self.finished or self.closed.is_set():
                raise DriverError("Claude approval is stale or already answered")
            if self.stopped() or self.interrupt_id is not None:
                raise DriverError("Claude is stopping; no approval can be sent")
            self.decisions.put_nowait((request_id, decision))
            pending["decisionQueued"] = True

    def close_steering(self):
        self.closed.set()
        with self.lock:
            self.pending.clear()
