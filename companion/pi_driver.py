"""Independent adapter for the pinned Pi 1.4.0 RPC contract (MIT upstream).

Official contract: earendil-works/pi tag v1.4.0, packages/coding-agent/docs/rpc.md
and docs/cli.md. This client contains no upstream implementation code.
Only a no-tools conversation is enabled. Extension UI is NOT tool approval.
The original Companion owns process lifetime, resource leases and persistence.
"""
from contextlib import nullcontext
import json
from pathlib import Path
import queue
import re
import threading
import time
import uuid

from codex_driver import DriverError
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
        self.timed_out = False
        self.protocol_verified = False
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

    def _send(self, kind, request_id, **values):
        data = (json.dumps({"id": request_id, "type": kind, **values}, ensure_ascii=False) + "\n").encode("utf-8")
        try:
            view = memoryview(data)
            while view:
                written = self.process.stdin.write(view)
                if not written:
                    raise OSError("Pipe did not accept data")
                view = view[written:]
            self.process.stdin.flush()
        except (OSError, ValueError):
            raise DriverError("Pi pipe write is unconfirmed; do not replay")

    def _next(self):
        while True:
            now = time.monotonic()
            if self.stopped() or now >= self.deadline:
                if not self.turn_dispatched:
                    raise DriverError("Pi cancelled before prompt dispatch")
                if self.abort_id is None:
                    self.timed_out = now >= self.deadline
                    self.abort_id, self.abort_deadline = str(uuid.uuid4()), now + 3
                    self._send("abort", self.abort_id)
                if now >= self.abort_deadline:
                    raise DriverError("Pi abort did not reach the native settled barrier; outcome unknown")
            if self.read_error:
                raise self.read_error
            try:
                value = self.events.get(timeout=.05)
            except queue.Empty:
                continue
            if value is None:
                raise DriverError("Pi exited without the required native settled receipt; do not replay")
            self.event_count += 1
            return value

    @staticmethod
    def _session_observation(event):
        kind = event.get("type")
        if kind == "entry_appended":
            # Native persistence notifications can contain raw provider history/thinking.
            # Consume the bounded frame, but never copy the entry into progress/receipts.
            entry = event.get("entry")
            if not isinstance(entry, dict) or not isinstance(entry.get("type"), str):
                raise DriverError("Pi native entry notification is malformed")
            return True
        if kind == "queue_update":
            if event.get("steering") != [] or event.get("followUp") != []:
                raise DriverError("Pi has unexpected queued work; no second prompt or queue mutation is allowed")
            return True
        return kind in {"session_info_changed", "thinking_level_changed"}

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
            self._send("prompt", self.prompt_id, message=prompt)
        accepted, started, settled, aborted = False, False, False, False
        final_reason = None
        while True:
            event = self._next()
            kind = event["type"]
            if kind == "response":
                if event.get("id") == self.prompt_id and event.get("command") == "prompt" and not accepted:
                    if event.get("success") is not True or not isinstance(event.get("data"), dict) or event["data"].get("disposition") != "started":
                        raise DriverError("Pi prompt was refused/queued/handled; no completion or replay is inferred")
                    accepted = True
                elif self.abort_id and event.get("id") == self.abort_id and event.get("command") == "abort" and event.get("success") is True and not self.abort_ack:
                    self.abort_ack = True
                else:
                    raise DriverError("Pi response does not match the exact original prompt/abort")
            elif kind == "agent_start":
                if settled:
                    raise DriverError("Pi started work after its settled barrier")
                started = True
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
                if role not in {"user", "assistant"}:
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
                if type(event.get("aborted")) is not bool or settled:
                    raise DriverError("Pi native settled receipt is malformed or duplicated")
                settled, aborted = True, event["aborted"]
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
            if accepted and started and settled and (not self.abort_id or self.abort_ack):
                if not aborted and final_reason is None:
                    raise DriverError("Pi settled without an authoritative assistant result")
                status = "cancelled" if aborted else "completed" if final_reason == "stop" else "failed"
                return {**value, "status": status, "nativeSettled": True, "interruptAcknowledged": self.abort_ack,
                        "timedOut": self.timed_out, "exitCode": None,
                        "message": "Pi no-tools conversation reached its native settled barrier; not Pi coding capability"}

    def can_steer(self, _turn_id):
        return False

    def close_steering(self):
        # No process termination here: only the original JobManager owns cleanup proof.
        self.closed.set()


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
