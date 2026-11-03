"""Independent adapter for the pinned Pi 1.4.0 RPC contract (MIT upstream).

Official contract: earendil-works/pi tag v1.4.0, packages/coding-agent/docs/rpc.md,
docs/rpc-commands.md, docs/cli.md and src/core/agent-session.ts.
Steer acknowledges queue admission only; events have no native per-input turn ID.
This client contains no upstream implementation code.
Only a no-tools conversation is enabled. Extension UI is NOT tool approval.
The original Companion owns process lifetime, resource leases and persistence.
"""
from collections import Counter
from contextlib import nullcontext
import json
from pathlib import Path
import queue
import re
import threading
import time
import uuid

from codex_driver import DriverError
from driver_dispatch import PipeWriter, DispatchError
from remote_interactions import Progress

VERSION = "1.4.0"
PROTOCOL = "pi_rpc_v1"
MAX_EVENT = 1_048_576
MAX_OUTPUT = 196_608
# Never accept caller-supplied resource paths, extensions, tools or CLI arguments.
SAFE_FLAGS = ("--no-tools", "--no-mcp", "--no-extensions", "--no-skills",
              "--no-prompt-templates", "--no-themes", "--no-context-files", "--no-approve", "--offline")
REQUIRED_FLAGS = (*SAFE_FLAGS, "--mode", "--session-id", "--session-dir", "--session", "--no-session")


def inspect_cli(version, help_text):
    compatible = bool(re.fullmatch(r"(?:pi\s+)?v?1\.4\.0", version.strip()))
    missing = [flag for flag in REQUIRED_FLAGS if not re.search(re.escape(flag) + r"(?=[\s,=<]|$)", help_text)]
    return {"cliVersion": VERSION if compatible else None, "cliFlagsVerified": compatible and not missing,
            "missingFlags": missing, "available": compatible and not missing, "protocolVerified": False,
            "accountConfigured": None, "modelInferenceVerified": False,
            "message": "Pi 1.4.0 version/help checked; no-tools RPC/account/inference still require verification"}


def session_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,78}[A-Za-z0-9])?", value):
        raise DriverError("Pi requires the exact managed native session identity")
    return value


def command(native_session=None, session_dir=None, session_file=None):
    args = ["pi", "--mode", "rpc", *SAFE_FLAGS]
    if native_session is None:
        return [*args, "--no-session"]
    session_id(native_session)
    if not session_dir or not Path(session_dir).is_absolute():
        raise DriverError("Pi session storage must be managed by the original Companion")
    args += ["--session-dir", str(session_dir)]
    if session_file:
        if Path(session_file).parent != Path(session_dir) or not Path(session_file).is_absolute():
            raise DriverError("Pi continuation cannot import an arbitrary transcript path")
        return [*args, "--session", str(session_file)]
    return [*args, "--session-id", native_session]


def _model(value):
    if not isinstance(value, dict):
        raise DriverError("Pi did not report an actual model")
    result = {}
    for key in ("provider", "id"):
        raw = value.get(key)
        if not isinstance(raw, str) or not raw or len(raw) > 160 or any(ord(c) < 32 for c in raw):
            raise DriverError("Pi model identity is invalid")
        result[key] = raw
    # Public configured metadata only: never baseUrl, headers, auth or complete settings.
    name = value.get("name")
    result["name"] = name[:200] if isinstance(name, str) else result["id"]
    result["modelId"] = result["id"]
    result["id"] = result["provider"] + "/" + result["id"]
    for key in ("contextWindow", "maxTokens"):
        number = value.get(key)
        if isinstance(number, int) and not isinstance(number, bool) and 0 < number <= 100_000_000:
            result[key] = number
    return result


class PiRpcDriver:
    def __init__(self, process, on_state, stopped, timeout, dispatch_guard=None,
                 native_session=None, session_dir=None, expected_file=None):
        self.process, self.on_state, self.stopped = process, on_state, stopped
        self.deadline = time.monotonic() + timeout
        self.dispatch_guard = dispatch_guard or nullcontext
        self.writer = PipeWriter(process)
        self.native_session = session_id(native_session) if native_session else None
        self.session_dir = Path(session_dir) if session_dir else None
        self.expected_file = Path(expected_file) if expected_file else None
        self.session_file = None
        self.events = queue.Queue(maxsize=128)
        self.closed = threading.Event()
        self.read_error = None
        self.turn_dispatched = False
        self.prompt_id = str(uuid.uuid4())
        self.abort_id = None
        self.abort_ack = False
        self.abort_deadline = None
        self.stop_requested = False
        self.timed_out = False
        self.protocol_verified = False
        self.prompt_accepted = False
        self.agent_started = False
        self.settled = False
        self.steers = queue.Queue(maxsize=8)
        self.steer_replies = {}
        self.steering_unknown = False
        self.steering_unresolved = False
        self.dispatched_texts = []
        self.native_steering = []
        self.clear_id = None
        self.clear_phase = None
        self.stop_clear_done = False
        self.final_clear_done = False
        self.final_state_id = None
        self.native_queue_empty_confirmed = False
        self.reported_model = None
        self.auto_compaction = None
        self.output, self.truncated = "", False
        self.progress = Progress(PROTOCOL)
        self.event_count = 0
        self.reader = threading.Thread(target=self._read, daemon=True, name="pocket-pi-jsonl")
        self.reader.start()

    def _read(self):
        try:
            while not self.closed.is_set():
                line = self.process.stdout.readline(MAX_EVENT + 1)
                if not line:
                    self.events.put(None, timeout=.5)
                    return
                if len(line) > MAX_EVENT or not line.endswith(b"\n"):
                    raise DriverError("Pi frame is incomplete or exceeds the bounded limit")
                value = json.loads(line, parse_constant=lambda _value: (_ for _ in ()).throw(ValueError()))
                if not isinstance(value, dict) or not isinstance(value.get("type"), str):
                    raise DriverError("Pi frame must be a typed object")
                self.events.put(value, timeout=.5)
        except (OSError, ValueError, DriverError, queue.Full):
            self.read_error = DriverError("Pi RPC stream could not be validated; do not replay")

    def _prepare_send(self, kind, request_id, **values):
        stopping = kind in {"clear_queue", "abort", "get_state"} and (self.stop_requested or self.settled)
        deadline = (self.abort_deadline or self.deadline) if stopping else self.deadline
        return self.writer.reserve({"id": request_id, "type": kind, **values}, deadline,
                                   (lambda: False) if stopping else self.stopped)

    def _send(self, kind, request_id, _ticket=None, **values):
        ticket = _ticket if _ticket is not None else self._prepare_send(kind, request_id, **values)
        try:
            self.writer.send(ticket)
        except DispatchError:
            raise DriverError("Pi pipe write is unconfirmed; do not replay") from None

    def _command_ticket(self, kind, request_id, **values):
        return kind, request_id, values, self._prepare_send(kind, request_id, **values)

    def _next(self):
        while True:
            now = time.monotonic()
            if self.stop_requested or self.stopped() or now >= self.deadline:
                if not self.turn_dispatched:
                    raise DriverError("Pi cancelled before prompt dispatch")
                if not self.stop_requested:
                    self.timed_out = now >= self.deadline
                    self.stop_requested, self.abort_deadline = True, now + 3
                    self._state()
                if now >= self.abort_deadline:
                    raise DriverError("Pi stop did not confirm its native settled and empty-queue barriers; outcome unknown")
            for pending in self.steer_replies.values():
                if now >= pending["deadline"] and not pending.get("timedOut"):
                    pending["timedOut"] = True
                    self.steering_unknown = True
                    pending["receipt"]("unknown", {"dispatched": True, "message": "Pi steering acknowledgement did not arrive; do not resend"})
                    self._state()
            if self.read_error:
                raise self.read_error
            try:
                # Previously received settlement/retraction wins over a new input write.
                value = self.events.get_nowait()
            except queue.Empty:
                self._pump_controls(now)
                try:
                    value = self.events.get(timeout=.05)
                except queue.Empty:
                    continue
            if value is None:
                raise DriverError("Pi exited without the required native settled receipt; do not replay")
            self.event_count += 1
            return value

    def _owned_queue(self, steering, follow_up):
        if (not isinstance(steering, list) or len(steering) > 64 or follow_up != []
                or any(not isinstance(value, str) or len(value) > 12_000 for value in steering)
                or Counter(steering) - Counter(self.dispatched_texts)):
            raise DriverError("Pi has unreviewed queued work; no queue ownership or message consumption was inferred")

    def _session_observation(self, event):
        kind = event.get("type")
        if kind == "entry_appended":
            # Native persistence notifications can contain raw provider history/thinking.
            # Consume the bounded frame, but never copy the entry into progress/receipts.
            entry = event.get("entry")
            if not isinstance(entry, dict) or not isinstance(entry.get("type"), str):
                raise DriverError("Pi native entry notification is malformed")
            return True
        if kind == "queue_update":
            self._owned_queue(event.get("steering"), event.get("followUp"))
            self.native_steering = list(event["steering"])
            return True
        return kind in {"session_info_changed", "thinking_level_changed"}

    def _reject_unsent(self):
        while True:
            try:
                _request, receipt = self.steers.get_nowait()
            except queue.Empty:
                return
            receipt("rejected", {"dispatched": False, "message": "Original Pi prompt is no longer accepting input; nothing was sent"})

    def _clear_queue(self, phase):
        self.clear_id, self.clear_phase = str(uuid.uuid4()), phase
        return self._command_ticket("clear_queue", self.clear_id)

    def _pump_controls(self, now):
        if not self.turn_dispatched:
            return
        with self.dispatch_guard():
            command = self._prepare_controls(now)
        if command is None:
            return
        kind, request_id, values, ticket = command
        try:
            self._send(kind, request_id, _ticket=ticket, **values)
        except DriverError:
            if kind == "steer":
                pending = self.steer_replies.pop(request_id, None)
                self.steering_unknown = self.steering_unresolved = True
                if pending:
                    pending["receipt"]("unknown", {"dispatched": None, "message": "Pi input pipe write is unconfirmed; do not resend"})
            raise

    def _prepare_controls(self, now):
        if self.stop_requested or self.settled:
            self._reject_unsent()
        if self.stop_requested:
            # RPC command handlers are asynchronous. Clear before abort, then clear again
            # AFTER all submitted steer replies and settlement to fence a late enqueue.
            if not self.stop_clear_done:
                if self.clear_id is None:
                    return self._clear_queue("stop")
                return
            if self.abort_id is None:
                self.abort_id = str(uuid.uuid4())
                return self._command_ticket("abort", self.abort_id)
        if self.settled:
            if self.steer_replies:
                if any(value.get("timedOut") for value in self.steer_replies.values()):
                    raise DriverError("Pi settled with an unacknowledged input; queue outcome remains unknown")
                return
            if self.stop_requested and not self.abort_ack:
                return
            if not self.final_clear_done:
                if self.clear_id is None:
                    return self._clear_queue("final")
                return
            if self.final_state_id is None and not self.native_queue_empty_confirmed:
                self.final_state_id = str(uuid.uuid4())
                return self._command_ticket("get_state", self.final_state_id)
            return
        if not self.can_steer() or self.steer_replies or not self.events.empty():
            return
        try:
            request, receipt = self.steers.get_nowait()
        except queue.Empty:
            return
        if not self.can_steer(request["expectedPromptRequestId"]):
            receipt("rejected", {"dispatched": False, "message": "Original Pi prompt identity changed before input dispatch"})
            return
        queue_frame = {"type": "queue_update", "steering": [*self.native_steering, request["text"]], "followUp": []}
        if len((json.dumps(queue_frame, ensure_ascii=False) + "\n").encode("utf-8")) > MAX_EVENT:
            receipt("rejected", {"dispatched": False, "message": "Pi native queue would exceed its bounded protocol frame; input was not sent or truncated"})
            return
        request_id = str(uuid.uuid4())
        receipt("sending", {"nativeRequestId": request_id, "dispatched": None})
        if not self.can_steer(request["expectedPromptRequestId"]):
            receipt("rejected", {"dispatched": False, "message": "Stopped before Pi input dispatch"})
            return
        self.steer_replies[request_id] = {"receipt": receipt, "deadline": min(now + 5, self.deadline)}
        self.dispatched_texts.append(request["text"])
        return self._command_ticket("steer", request_id, message=request["text"])

    def _input_response(self, event):
        request_id = event.get("id")
        pending = self.steer_replies.pop(request_id, None) if isinstance(request_id, str) else None
        if pending is not None:
            if (event.get("command") != "steer" or event.get("success") is not True
                    or not isinstance(event.get("data"), dict) or event["data"].get("disposition") != "queued"):
                self.steering_unknown = self.steering_unresolved = True
                pending["receipt"]("unknown", {"dispatched": True, "message": "Pi did not confirm this exact steering command as queued; do not resend"})
                raise DriverError("Pi steering response is not the fixed no-tools queue contract")
            pending["receipt"]("accepted", {"dispatched": True, "disposition": "queued",
                "delivery": "native_queue", "consumed": "unknown",
                "message": "Original Pi native queue accepted this input; consumption and task completion are not confirmed"})
            self.steering_unknown = self.steering_unresolved or any(value.get("timedOut") for value in self.steer_replies.values())
            return True
        if self.clear_id is not None and request_id == self.clear_id:
            data = event.get("data")
            if event.get("command") != "clear_queue" or event.get("success") is not True or not isinstance(data, dict):
                raise DriverError("Pi queue cleanup was not acknowledged; no empty queue was inferred")
            self._owned_queue(data.get("steering"), data.get("followUp"))
            if self.clear_phase == "stop":
                self.stop_clear_done = True
            else:
                self.final_clear_done = True
            self.clear_id = self.clear_phase = None
            return True
        if self.final_state_id is not None and request_id == self.final_state_id:
            state = event.get("data")
            if (event.get("command") != "get_state" or event.get("success") is not True
                    or not isinstance(state, dict) or state.get("isStreaming") is not False
                    or state.get("isCompacting") is not False or type(state.get("pendingMessageCount")) is not int
                    or state["pendingMessageCount"] != 0 or state.get("sessionId") != self.native_session
                    or state.get("sessionFile") != self.session_file
                    or _model(state.get("model"))["id"] != self.reported_model["id"]):
                raise DriverError("Pi final original session and empty native queue were not confirmed")
            self.native_queue_empty_confirmed = True
            self.final_state_id = None
            return True
        return False

    def _command(self, kind, **values):
        request_id = str(uuid.uuid4())
        self._send(kind, request_id, **values)
        event = self._next()
        while self._session_observation(event):
            event = self._next()
        if event.get("type") != "response" or event.get("id") != request_id or event.get("command") != kind:
            raise DriverError("Pi readonly/session command response identity is unconfirmed")
        if event.get("success") is not True:
            raise DriverError("Pi required native command was refused; no prompt was started")
        return event.get("data")

    def _read_state(self):
        state = self._command("get_state")
        if not isinstance(state, dict) or state.get("isStreaming") is not False or state.get("isCompacting") is not False or type(state.get("pendingMessageCount")) is not int or state["pendingMessageCount"] != 0:
            raise DriverError("Pi session is not idle with an empty original native queue")
        actual_id = session_id(state.get("sessionId"))
        if self.native_session:
            if actual_id != self.native_session:
                raise DriverError("Pi did not open the exact original native session")
            raw_path = state.get("sessionFile")
            if not isinstance(raw_path, str):
                raise DriverError("Pi did not report its managed session file")
            path = Path(raw_path)
            try:
                canonical = path.resolve() == path
            except (OSError, RuntimeError):
                canonical = False
            if not path.is_absolute() or path.parent != self.session_dir or path.suffix != ".jsonl" or not canonical or self.expected_file and path != self.expected_file:
                raise DriverError("Pi session escaped the original managed session directory")
            self.session_file = str(path)
        self.reported_model = _model(state.get("model"))
        self.auto_compaction = state.get("autoCompactionEnabled") if type(state.get("autoCompactionEnabled")) is bool else None
        return state

    def _bootstrap(self, model=None, select=False):
        self._read_state()
        resources = self._command("get_commands")
        if not isinstance(resources, dict) or resources.get("commands") != []:
            raise DriverError("Pi has unreviewed extension/skill/template commands; no prompt was started")
        catalog = self._command("get_available_models")
        values = catalog.get("models") if isinstance(catalog, dict) else None
        if not isinstance(values, list) or len(values) > 2048:
            raise DriverError("Pi model catalog is unavailable or exceeds its explicit bound")
        models = [_model(value) for value in values]
        if len({value["id"] for value in models}) != len(models):
            raise DriverError("Pi model catalog identities are ambiguous")
        selected = next((value for value in models if value["id"] == model), None) if model else None
        if model and selected is None:
            raise DriverError("Pi model requires an exact configured provider/model ID, not an alias")
        if select and selected and self.reported_model["id"] != model:
            # Pi 1.4.0 setModel defaults to session-only; NEVER set auto-retry/compaction
            # here: those setters write the user's SettingsManager/global defaults.
            actual = _model(self._command("set_model", provider=selected["provider"], modelId=selected["modelId"]))
            if actual["id"] != model:
                raise DriverError("Pi selected a different native model; no prompt was started")
            self._read_state()
        if select and model and self.reported_model["id"] != model:
            raise DriverError("Pi native model readback does not match the exact request")
        self.protocol_verified = True
        return models

    def probe(self, model=None):
        models = self._bootstrap(model)
        return {"available": True, "protocolVerified": True, "configReadVerified": True,
                "models": models[:128], "modelsTruncated": len(models) > 128,
                "selectedModelAvailable": any(value["id"] == model for value in models) if model else None,
                "accountConfigured": None, "modelInferenceVerified": False, "noTools": True,
                "autoCompactionEnabled": self.auto_compaction,
                "message": "Pi no-tools RPC/configured model catalog verified; no inference, account refresh or saved session was started"}

    def _state(self):
        value = {"protocol": PROTOCOL, "protocolVerified": self.protocol_verified,
                 "sessionId": self.native_session, "threadId": self.native_session,
                 "promptRequestId": self.prompt_id, "reportedModel": self.reported_model["id"] if self.reported_model else None,
                  "inputSteeringVerified": self.can_steer(), "steeringUnknown": self.steering_unknown,
                  "nativeQueueEmptyConfirmed": self.native_queue_empty_confirmed,
                 "noTools": True, "osReadIsolationVerified": False, "autoCompactionEnabled": self.auto_compaction,
                 "inferenceUsage": {"modelCalls": None, "inputTokens": None, "outputTokens": None, "cost": None,
                                    "status": "unknown", "source": "native_session_may_retry_or_compact_no_per_request_accounting"},
                 "output": self.output, "outputTruncated": self.truncated, "eventCount": self.event_count,
                 "pendingApprovals": [], "pendingQuestions": [], "progress": self.progress.snapshot(), "status": "running"}
        self.on_state(value)
        return value

    def run(self, prompt, thread=None, model=None):
        if not self.native_session or thread and thread != self.native_session:
            raise DriverError("Pi continuation lacks its exact original managed session")
        self._bootstrap(model, select=True)
        self._state()
        with self.dispatch_guard():
            if self.stopped() or time.monotonic() >= self.deadline:
                raise DriverError("Pi cancelled before prompt dispatch")
            self.turn_dispatched = True  # A partial write is UNKNOWN, never a retry license.
            ticket = self._prepare_send("prompt", self.prompt_id, message=prompt)
        self._send("prompt", self.prompt_id, _ticket=ticket, message=prompt)
        aborted = False
        final_reason = None
        while True:
            event = self._next()
            kind = event["type"]
            if kind == "response":
                if self._input_response(event):
                    pass
                elif event.get("id") == self.prompt_id and event.get("command") == "prompt" and not self.prompt_accepted:
                    if event.get("success") is not True or not isinstance(event.get("data"), dict) or event["data"].get("disposition") != "started":
                        raise DriverError("Pi prompt was refused/queued/handled; no completion or replay is inferred")
                    self.prompt_accepted = True
                elif self.abort_id and event.get("id") == self.abort_id and event.get("command") == "abort" and event.get("success") is True and not self.abort_ack:
                    self.abort_ack = True
                else:
                    raise DriverError("Pi response does not match the exact original prompt/abort")
            elif kind == "agent_start":
                if self.settled:
                    raise DriverError("Pi started work after its settled barrier")
                self.agent_started = True
                self.progress.add("status", kind, status="running")
            elif kind == "message_update":
                delta = event.get("assistantMessageEvent")
                if not isinstance(delta, dict):
                    raise DriverError("Pi assistant delta is malformed")
                subtype = delta.get("type", "")
                if subtype == "text_delta":
                    text = delta.get("delta")
                    if not isinstance(text, str):
                        raise DriverError("Pi public text delta is invalid")
                    self.truncated |= len(self.output) + len(text) > MAX_OUTPUT
                    self.output = (self.output + text)[:MAX_OUTPUT]
                elif str(subtype).startswith("toolcall"):
                    raise DriverError("Pi emitted a tool call in no-tools mode; no approval was fabricated")
                # Thinking/signatures/cumulative partial objects are deliberately never projected.
            elif kind in {"message_start", "message_end"}:
                message = event.get("message")
                if not isinstance(message, dict):
                    raise DriverError("Pi message is malformed")
                role = message.get("role")
                if role == "system":
                    # Fixed 1.4.0 emits prompt-section patches as system messages. These
                    # private native metadata are not public text or a tool permission grant.
                    if (not isinstance(message.get("content"), str) or message.get("toolsAdded", []) != []
                            or message.get("sections") is not None and not isinstance(message["sections"], dict)):
                        raise DriverError("Pi system metadata is malformed or adds tools in no-tools mode")
                elif role not in {"user", "assistant"}:
                    raise DriverError("Pi no-tools session emitted an unsupported message role")
                if role == "assistant":
                    if kind == "message_start":
                        self.output, self.truncated, final_reason = "", False, None
                    else:
                        content = message.get("content")
                        if not isinstance(content, list):
                            raise DriverError("Pi complete assistant content is malformed")
                        public = []
                        for block in content:
                            if not isinstance(block, dict) or block.get("type") not in {"text", "thinking"}:
                                raise DriverError("Pi complete message contains tools or unsupported content")
                            if block["type"] == "text":
                                if not isinstance(block.get("text"), str):
                                    raise DriverError("Pi complete public text is malformed")
                                public.append(block["text"])
                        complete = "".join(public)
                        self.output, self.truncated = complete[:MAX_OUTPUT], len(complete) > MAX_OUTPUT
                        final_reason = message.get("stopReason")
                        if final_reason not in {"stop", "length", "error", "aborted"}:
                            raise DriverError("Pi assistant stop reason is unsupported")
                        self.progress.add("text", kind, text=self.output, status=final_reason)
            elif kind == "agent_settled":
                if type(event.get("aborted")) is not bool or self.settled:
                    raise DriverError("Pi native settled receipt is malformed or duplicated")
                with self.dispatch_guard():
                    self.settled, aborted = True, event["aborted"]
                    self._reject_unsent()
                self.progress.add("status", kind, status="aborted" if aborted else "settled")
            elif self._session_observation(event):
                pass
            elif kind in {"turn_start", "turn_end", "agent_end", "compaction_start", "compaction_end", "auto_retry_start", "auto_retry_end",
                          "summarization_retry_scheduled", "summarization_retry_attempt_start", "summarization_retry_finished"}:
                # agent_end is only a loop boundary. Hidden retry/summary text is not displayed.
                self.progress.add("status", kind, status=kind)
            else:
                raise DriverError("Pi emitted unsupported/tool/extension UI events in no-tools mode")
            value = self._state()
            if (self.prompt_accepted and self.agent_started and self.settled and self.native_queue_empty_confirmed
                    and not self.steer_replies and (not self.stop_requested or self.abort_ack)):
                if not aborted and final_reason is None:
                    raise DriverError("Pi settled without an authoritative assistant result")
                status = "cancelled" if aborted else "completed" if final_reason == "stop" else "failed"
                return {**value, "status": status, "nativeSettled": True, "interruptAcknowledged": self.abort_ack,
                        "timedOut": self.timed_out, "exitCode": None,
                        "message": "Pi no-tools conversation reached its native settled barrier; not Pi coding capability"}

    def can_steer(self, expected_prompt_id=None):
        return (self.protocol_verified and self.session_file is not None and self.prompt_accepted
                and self.agent_started and not self.settled and not self.stop_requested
                and not self.closed.is_set() and not self.steering_unknown and not self.stopped()
                and time.monotonic() < self.deadline and len(self.dispatched_texts) < 64
                and (expected_prompt_id is None or expected_prompt_id == self.prompt_id))

    def steer(self, request, receipt):
        if not isinstance(request.get("expectedPromptRequestId"), str) or not self.can_steer(request["expectedPromptRequestId"]):
            raise DriverError("Pi input requires the exact active original prompt request")
        self.steers.put_nowait((dict(request), receipt))

    def close_steering(self):
        # No process termination here: only the original JobManager owns cleanup proof.
        self.closed.set()
        self._reject_unsent()
        for request_id, pending in list(self.steer_replies.items()):
            self.steer_replies.pop(request_id, None)
            self.steering_unknown = True
            pending["receipt"]("unknown", {"dispatched": True, "message": "Pi transport ended without this input acknowledgement; do not resend"})


def session_stamp(path, directory):
    """Private native-file continuity proof; never parse/project provider history."""
    import hashlib
    import os
    import stat
    path, directory = Path(path), Path(directory)
    try:
        canonical = path.resolve() == path
    except (OSError, RuntimeError):
        canonical = False
    if not path.is_absolute() or path.parent != directory or not canonical or path.suffix != ".jsonl":
        raise DriverError("Pi managed session path changed")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    descriptor = os.open(path, flags)
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or not 1 <= before.st_size <= 33_554_432:
            raise DriverError("Pi managed session storage is unavailable or exceeds its bound")
        digest, size = hashlib.sha256(), 0
        while True:
            block = os.read(descriptor, 65_536)
            if not block:
                break
            size += len(block)
            if size > 33_554_432:
                raise DriverError("Pi managed session grew beyond its bound")
            digest.update(block)
        after = os.fstat(descriptor)
        def stamp(value):
            return [value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns]
        if stamp(before) != stamp(after) or stamp(path.stat()) != stamp(after) or size != after.st_size:
            raise DriverError("Pi managed session changed during inspection")
        return {"sha256": digest.hexdigest(), "fileStat": stamp(after)}
    finally:
        os.close(descriptor)
