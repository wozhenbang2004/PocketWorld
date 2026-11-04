"""Muse Session Protocol v1 on the original supervised stdio process.

Wire definitions: https://github.com/meta-models/muse-code-sdk . Commands have
their own IDs; accepting a turn is not a terminal result. No permissive CLI
flags, policy profiles, login, retry or second process are supplied here.
"""
from __future__ import annotations

import json
from pathlib import Path
import queue
import secrets
import time
import uuid

from acp_driver import lossless_owner_payload
from codex_driver import DriverError
from generic_acp import AcpDriver, MAX_OUTPUT, text, wire_id
from harness_registry import MSP_PROTOCOL, canonical, digest
from remote_interactions import Progress


def command_id():
    # UUIDv7 is the native command-id format; no server result is derived from it.
    value = ((int(time.time() * 1000) & ((1 << 48) - 1)) << 80)
    value |= 7 << 76 | secrets.randbits(12) << 64 | 2 << 62 | secrets.randbits(62)
    return str(uuid.UUID(int=value))


class MuseDriver(AcpDriver):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.progress = Progress(MSP_PROTOCOL)
        self.turn_id = None
        self.terminal = None
        self.control_replies = {}
        self.items = {}
        self.item_order = []
        self.cursors = set()
        self.provider_id = None
        self.model_catalog = []
        self.view_cursor = None

    def probe(self, create_session=False):
        self.current()
        result = self._request("initialize", {"clientInfo": {"name": "pocketworld_companion", "version": "0.4.0"},
            "capabilities": {"requestedCapabilities": ["sessionMcp"], "userInputDialogs": False}})
        schema, server = result.get("schema"), result.get("serverInfo")
        granted = result.get("grantedCapabilities", [])
        if (not isinstance(schema, dict) or type(schema.get("version")) is not int or schema["version"] != 1
                or not isinstance(server, dict) or not text(server.get("name")) or not isinstance(granted, list)
                or not all(isinstance(v, str) for v in granted) or result.get("sessionDurability", "durable") != "durable"):
            raise DriverError("msp_initialize_unconfirmed")
        self.hello = {"protocolVersion": 1, "agentInfo": {k: server[k] for k in ("name", "version") if isinstance(server.get(k), str)},
            "schema": schema, "grantedCapabilities": sorted(granted), "sessionDurability": "durable", "authMethods": [],
            "agentCapabilities": {"loadSession": True, "sessionCapabilities": {"resume": {}}}}
        if self.auth_method:
            raise DriverError("msp_auth_is_owner_configured: sign in with the installed CLI before probing")
        if self.expected_hello is not None and self.hello != self.expected_hello:
            raise DriverError("msp_capabilities_changed")
        self.rpc.send({"jsonrpc": "2.0", "method": "initialized"})
        self.protocol_verified = True
        self._catalog()
        self.current()
        # MSP can enumerate models without creating a session in an untrusted
        # temporary directory. Workspace access is confirmed by the actual job.
        return self.hello

    def _catalog(self):
        value = self._request("model/list", {"sessionId": self.session_id} if self.session_id else {})
        models = value.get("models")
        if not isinstance(models, list) or len(models) > 1000 or not text(value.get("providerId")):
            raise DriverError("msp_model_catalog_invalid")
        self.model_catalog = models
        self.provider_id = value["providerId"]
        available = []
        current = self.reported_model
        for row in models:
            if not isinstance(row, dict) or not text(row.get("modelId")):
                raise DriverError("msp_model_catalog_invalid")
            available.append({"modelId": row["modelId"], "name": str(row.get("displayLabel", row["modelId"]))[:200]})
            if row.get("isActive") or current is None and row.get("isDefault"):
                current = row["modelId"]
        self.reported_model = current
        self.session_options = {"models": {"currentModelId": current, "availableModels": available}}

    def _select(self, category, requested):
        if not requested:
            return
        if category != "model":
            raise DriverError("msp_mode_option_not_supported")
        chosen = next((v for v in self.model_catalog if v["modelId"] == requested), None)
        if chosen is None:
            raise DriverError("msp_model_option_not_advertised")
        if self.session_id and self.reported_model != requested:
            identifier = command_id()
            result = self._request("session/setModel", {"sessionId": self.session_id, "commandId": identifier,
                "model": {"modelId": requested, "providerId": chosen.get("providerId", self.provider_id)}})
            if result.get("commandId") != identifier or result.get("status") != "accepted":
                raise DriverError("msp_model_selection_unconfirmed")
            observed = self._request("session/read", {"sessionId": self.session_id, "excludeItems": True})
            if observed.get("session", {}).get("modelId") != requested:
                raise DriverError("msp_model_selection_unconfirmed")
        self.reported_model = requested
        self.session_options["models"]["currentModelId"] = requested

    def _session(self, thread_id):
        identifier = command_id()
        params = {"commandId": identifier}
        if self.mcp_servers:
            if "sessionMcp" not in self.hello["grantedCapabilities"]:
                raise DriverError("msp_session_mcp_not_granted")
            configs = {}
            for server in self.mcp_servers:
                configs[server["name"]] = {"transport": "stdio", "command": server["command"], "args": server.get("args", []),
                    "env": {v["name"]: v["value"] for v in server.get("env", [])}, "mode": "required", "framing": "lineDelimitedJson"}
            params["config"] = {"mcpServers": configs}
        if thread_id:
            self.session_id = thread_id
            params.update(sessionId=thread_id, excludeItems=True)
            method = "session/resume"
        else:
            params.update(workspaceRoot=self.cwd)
            method = "session/start"
        self.opening = self.replaying = True
        result = self._request(method, params)
        session = result.get("session", {})
        identity = session.get("sessionId")
        if not text(identity, 500) or thread_id and identity != thread_id:
            raise DriverError("msp_session_identity_mismatch")
        if (session.get("status") != "idle" or session.get("activeTurnId") is not None
                or result.get("pendingRequests") or result.get("queuedInputs")):
            raise DriverError("msp_session_not_settled: do not append work to an active or unresolved session")
        if not isinstance(session.get("workspaceRoot"), str) or Path(session["workspaceRoot"]).resolve() != Path(self.cwd).resolve():
            raise DriverError("msp_workspace_identity_mismatch")
        self.session_id = identity
        self.reported_model = session.get("modelId")
        self.view_cursor = result.get("viewCursor")
        self.opening = self.replaying = False
        self._catalog()
        return result

    def run(self, prompt, thread_id=None, model=None):
        with self.guard():
            if self.started:
                raise DriverError("msp_original_turn_already_attempted")
            self.started = True
        self.probe()
        self._session(thread_id)
        self._select("model", model)
        self._select("mode", self.requested_mode)
        if self.expected_options is not None and self.session_options != self.expected_options:
            raise DriverError("msp_session_options_changed")
        self.current()
        self.prompt_id = command_id()
        with self.guard():
            if self.stopped():
                raise DriverError("msp_stopped_before_turn")
            self.turn_dispatched = True
            self._state()
        accepted = self._request("turn/start", {"sessionId": self.session_id, "commandId": self.prompt_id,
            "input": [{"type": "text", "text": "The following is the owner's literal task text.\n\n" + prompt}], "ifBusy": "queue"})
        if (accepted.get("commandId") != self.prompt_id or accepted.get("status") != "accepted"
                or accepted.get("disposition") not in {"started", "queued"} or not text(accepted.get("turnId"), 500)):
            raise DriverError("msp_turn_admission_unconfirmed")
        if self.turn_id is not None and self.turn_id != accepted["turnId"]:
            raise DriverError("msp_turn_identity_mismatch")
        self.turn_id = accepted["turnId"]
        self._state()
        while self.terminal is None:
            self._tick()
            value = self.rpc.read()
            if value is ...:
                continue
            if "method" in value:
                self._event(value)
            elif not self._control(value):
                raise DriverError("msp_response_identity_mismatch")
        self.finished = True
        self._withdraw("withdrawn")
        return {**self._snapshot(), "status": self.terminal, "stopReason": self.terminal,
            "promptTerminalConfirmed": True, "nativeSettled": True,
            "message": "The original Muse turn returned its durable terminal event; inspect the resulting files and output."}

    def _response(self, identifier):
        while True:
            self._tick()
            value = self.rpc.read()
            if value is ...:
                continue
            if "method" in value:
                self._event(value)
                continue
            if value.get("id") != identifier:
                if self._control(value):
                    continue
                raise DriverError("msp_response_identity_mismatch")
            if "error" in value:
                code = value["error"].get("code") if isinstance(value["error"], dict) else None
                raise DriverError("msp_request_failed: code " + str(code if type(code) is int else "unknown"))
            if not isinstance(value.get("result"), dict):
                raise DriverError("msp_response_invalid")
            return value["result"]

    def _control(self, value):
        identifier = value.get("id")
        if not wire_id(identifier) or identifier not in self.control_replies:
            return False
        command = self.control_replies.pop(identifier)
        result = value.get("result", {})
        if "error" in value or result.get("commandId") != command or result.get("status") != "accepted":
            raise DriverError("msp_control_unconfirmed")
        return True

    def _event(self, value):
        method, params = value.get("method"), value.get("params", {})
        if not isinstance(method, str) or not isinstance(params, dict):
            raise DriverError("msp_event_invalid")
        if "id" in value:
            identifier = value["id"]
            if not wire_id(identifier):
                raise DriverError("msp_server_request_identity_invalid")
            if method == "approval/request":
                # Receipt acknowledges delivery; authorization is approval/decide.
                self._reply(identifier, {"result": {}})
                self._msp_permission(params)
            else:
                self._reply(identifier, {"error": {"code": -32601, "message": "Unsupported client method"}})
            return
        if self.opening or self.replaying:
            return
        if method in {"initialized", "session/started", "session/closed"}:
            return
        if params.get("sessionId") != self.session_id:
            return  # Native child sessions never become this job's result.
        if method == "approval/updated":
            self._msp_permission(params)
            return
        if method == "approval/resolved":
            with self.lock:
                for key, entry in list(self.pending.items()):
                    if entry.get("approvalId") == params.get("approvalId"):
                        del self.pending[key]
            self._state()
            return
        cursor = params.get("viewCursor")
        if cursor is not None:
            if not text(cursor, 1000):
                raise DriverError("msp_view_cursor_invalid")
            if cursor in self.cursors:
                return
            if len(self.cursors) >= 50000:
                raise DriverError("msp_view_observation_limit")
            self.cursors.add(cursor)
            self.view_cursor = cursor
        if method == "turn/started":
            if params.get("commandId") != self.prompt_id or not self.turn_dispatched:
                raise DriverError("msp_foreign_turn_started")
            if self.turn_id is not None and params.get("turnId") != self.turn_id:
                raise DriverError("msp_turn_identity_mismatch")
            self.turn_id = params.get("turnId")
            self.progress.add("turn", method, self.turn_id, status="running")
        elif method == "turn/completed":
            if params.get("turnId") != self.turn_id or self.turn_id is None:
                raise DriverError("msp_terminal_identity_mismatch")
            if params.get("terminal") not in {"completed", "failed", "cancelled"} or not isinstance(params.get("sourceRange"), dict):
                raise DriverError("msp_terminal_unconfirmed")
            self.terminal = params["terminal"]
            self.progress.add("turn", method, self.turn_id, status=self.terminal)
        elif method in {"item/started", "item/updated", "item/completed"}:
            item = params.get("item")
            if not isinstance(item, dict) or item.get("turnId") != self.turn_id or self.turn_id is None:
                return
            identifier, revision = item.get("itemId"), item.get("revision")
            if not text(identifier, 500) or type(revision) is not int or revision < 1:
                raise DriverError("msp_item_identity_invalid")
            prior = self.items.get(identifier)
            if prior is not None and revision <= prior["revision"]:
                return
            if identifier not in self.items:
                if len(self.items) >= 2000:
                    raise DriverError("msp_item_observation_limit")
                self.item_order.append(identifier)
            self.items[identifier] = {"kind": item.get("kind"), "revision": revision,
                "text": item.get("text", "") if isinstance(item.get("text", ""), str) else "",
                "complete": method == "item/completed"}
            if item.get("kind") == "toolCall":
                self.progress.add("tool", method, identifier, title=str(item.get("tool", ""))[:200], status=str(item.get("status", "unknown"))[:40])
            self._render_output()
        elif method == "item/delta":
            item = self.items.get(params.get("itemId"))
            if item is None or item["complete"] or item["kind"] != "agentMessage":
                return
            if params.get("field", "text") == "text" and isinstance(params.get("delta"), str):
                raw = (item["text"] + params["delta"]).encode()
                self.truncated |= len(raw) > MAX_OUTPUT
                item["text"] = raw[:MAX_OUTPUT].decode("utf-8", errors="ignore")
                self._render_output()
        self._state()

    def _render_output(self):
        remaining, pieces = MAX_OUTPUT, []
        for key in self.item_order:
            item = self.items[key]
            if item["kind"] != "agentMessage":
                item["text"] = ""  # Tool outputs and reasoning are not retained here.
                continue
            if pieces:
                remaining = max(0, remaining - 1)
            raw = item["text"].encode()
            self.truncated |= len(raw) > remaining
            item["text"] = raw[:remaining].decode("utf-8", errors="ignore")
            remaining -= len(item["text"].encode())
            if item["text"]:
                pieces.append(item["text"])
        self.output = "\n".join(pieces)

    def _msp_permission(self, params):
        if (self.probing or self.opening or not self.turn_dispatched or self.finished or self.cancel_requested
                or self.stopped() or params.get("sessionId") != self.session_id or params.get("turnId") != self.turn_id):
            raise DriverError("msp_permission_outside_original_turn")
        choices, requirement = params.get("availableChoices"), params.get("currentRequirementId")
        if (not text(params.get("approvalId"), 500) or not isinstance(requirement, dict)
                or not isinstance(choices, list) or not 1 <= len(choices) <= 32
                or len(canonical(params).encode()) > 32768 or not lossless_owner_payload(params) or self.redact(params) != params):
            raise DriverError("msp_permission_unreviewable")
        if any(not isinstance(v, dict) or not text(v.get("choiceId")) or not text(v.get("label"), 1000) for v in choices):
            raise DriverError("msp_permission_options_invalid")
        allow = [v["choiceId"] for v in choices if v.get("scope") == "once" and v.get("decision") == "approved"]
        reject = [v["choiceId"] for v in choices if v.get("scope") == "once" and v.get("decision") in {"denied", "abort"}]
        if len(allow) > 1 or not reject:
            raise DriverError("msp_permission_options_unreviewable")
        fingerprint = digest(params)
        with self.lock:
            for key, old in list(self.pending.items()):
                if old.get("approvalId") == params["approvalId"]:
                    if old["requestHash"] == fingerprint:
                        return
                    del self.pending[key]
                    self.approval_receipts.append({"requestId": key, "requestHash": old["requestHash"], "status": "withdrawn", "decision": "cancel"})
            if len(self.pending) >= 16:
                raise DriverError("msp_permission_limit")
            identifier = str(uuid.uuid4())
            self.pending[identifier] = {"requestId": identifier, "requestHash": fingerprint,
                "protocol": MSP_PROTOCOL, "method": "msp/approval/request", "sessionId": self.session_id,
                "promptRequestId": self.prompt_id, "turnId": self.turn_id, "toolCallId": params.get("toolCallId"),
                "toolCall": {k: params[k] for k in ("toolName", "rawArgs", "subject", "protectedWrite", "subagentOrigin") if k in params},
                "options": choices, "canAccept": bool(allow), "allowOnceOptionId": allow[0] if allow else "",
                "rejectOnceOptionId": reject[0], "scopeEnforced": False, "osReadIsolationVerified": False,
                "approvalId": params["approvalId"], "requirementId": requirement}
        self._state()

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
            if decision == "cancel":
                receipt["status"] = "withdrawn"
                self._cancel()
                continue
            command = command_id()
            try:
                result = self._request("approval/decide", {"sessionId": self.session_id, "commandId": command,
                    "approvalId": entry["approvalId"], "requirementId": entry["requirementId"],
                    "choiceId": entry["allowOnceOptionId"] if decision == "accept" else entry["rejectOnceOptionId"]})
                if result.get("commandId") != command or result.get("status") != "accepted":
                    raise DriverError("msp_permission_ack_unconfirmed")
            except DriverError:
                receipt["status"] = "unknown"
                self._state()
                raise
            receipt["status"] = "sent"
            self._state()

    def _cancel(self):
        if self.cancel_requested:
            return
        self.cancel_requested = True
        self.cancel_deadline = time.monotonic() + 3
        self._withdraw("withdrawn")
        identifier, command = self.rpc.next_id(), command_id()
        params = {"sessionId": self.session_id, "commandId": command}
        if self.turn_id is not None:
            params["turnId"] = self.turn_id
        self.control_replies[identifier] = command
        self.rpc.send({"jsonrpc": "2.0", "id": identifier, "method": "turn/interrupt", "params": params}, stopping=True)
        self.cancel_sent = True
        self._state()

    def _snapshot(self):
        result = super()._snapshot()
        result.update(protocol=MSP_PROTOCOL, turnId=self.turn_id, sessionResumeSupported=bool(self.protocol_verified), resumeSupported=bool(self.protocol_verified),
            viewCursor=self.view_cursor, teamTools=bool(self.mcp_servers and self.hello and "sessionMcp" in self.hello.get("grantedCapabilities", [])))
        return result
