"""Pinned Cua Driver stdio adapter. No model, network server or GUI driver clone.

Source contract: trycua/cua f595a4fb6f524e555e5e422aab81c3457fe4a713,
Driver 0.38.0 (prerelease; not a stable-runtime assurance). The MIT Driver is
used without Spaces or the optional AGPL perception extension.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import uuid

from platform_backend import NativeProcessStartError, PlatformError

UPSTREAM_COMMIT = "f595a4fb6f524e555e5e422aab81c3457fe4a713"
UPSTREAM_VERSION = "0.38.0"
MCP_VERSION = "2025-06-18"  # Actual fixed upstream's supported legacy initialize.
MAX_RESPONSE = 24 * 1024 * 1024
ALLOWED = frozenset({"get_accessibility_tree", "get_window_state", "get_desktop_state", "click", "type_text", "hotkey", "press_key", "scroll"})
CAPABILITY_VERSION = "desktop-v2"


def input_outcome(result):
    """Audited f595 return paths, not 'isError means no effect'.

    Windows delivery.rs class-level refusal has exactly these fields. A UIA
    deadline/cause/path/effect=unverifiable means possible input, even when the
    SAME background_unavailable code is present. No automatic escalation.
    """
    effect = result.get("structuredContent", {})
    if not isinstance(effect, dict):
        effect = {}
    if result.get("isError") is not True:
        return {"kind": "dispatch_reported", "inputDispatched": True, "foregroundEscalationPermitted": False}
    known_refusal = effect.get("effect") == "refused" or effect.get("code") in {"invalid_arguments", "desktop_coordinate_scope_required"}
    class_refusal = (effect.get("code") == "background_unavailable" and set(effect) == {"code", "suggestion", "escalation", "target_class", "event_kind"}
                     and isinstance(effect.get("target_class"), str) and effect.get("event_kind") in {"mouse_click", "mouse_move", "mouse_scroll", "keystroke", "key_combo", "text_input"}
                     and isinstance(effect.get("escalation"), dict) and effect["escalation"].get("recommended") == "foreground")
    if effect.get("effect") in {"unverifiable", "unknown"} or "uia_status" in effect or "failed_calls" in effect:
        known_refusal = class_refusal = False
    refused = known_refusal or class_refusal
    return {"kind": "refused_pre_dispatch" if refused else "possible_effect_unknown", "inputDispatched": False if refused else None,
            "foregroundEscalationPermitted": bool(class_refusal)}


class CuaError(PlatformError):
    def __init__(self, message, status=409, *, effect_unknown=False):
        super().__init__(message, status)
        self.effect_unknown = effect_unknown


class McpCuaDriver:
    """One private child, one stdio lifecycle, no reconnect-and-replay path."""
    def __init__(self, binary, sha256, backend, *, private_state=None):
        self.binary, self.sha256, self.backend = Path(binary), sha256, backend
        self.private_state = Path(private_state) if private_state is not None else None
        state_key = hashlib.sha256(str(self.private_state).casefold().encode()).hexdigest()[:32]
        self.job_name = "Local\\PocketWorldDesktop-" + state_key + "-" + uuid.uuid4().hex
        self._stop_proof = None
        self.process = None
        self._calls = threading.RLock()
        self._lifecycle = threading.RLock()
        self._replies = queue.Queue(maxsize=16)
        self._sequence = 0
        self._broken = False
        self.tools = {}

    def start(self):
        with self._lifecycle:
            if self._broken:
                raise CuaError("Original Driver lifecycle is closed; only explicit local recovery may create a new worker", effect_unknown=True)
            if self.process is not None:
                return
            if os.name != "nt":
                raise CuaError("This desktop adapter currently requires a Windows interactive user session")
            if not self.binary.is_absolute() or not re.fullmatch(r"[a-f0-9]{64}", self.sha256 or ""):
                raise CuaError("An exact source-verified Driver executable and SHA-256 pin are required")
            if self.private_state is None or not self.private_state.is_absolute():
                raise CuaError("The original Companion must provide a dedicated private Driver state directory")
            self.private_state.mkdir(mode=0o700, exist_ok=True)
            with self.backend.directory(str(self.private_state)):
                pass
            # Package installers own the private install directory. Refuse a
            # reparse-point binary; never download or resolve a PATH executable.
            info = self.binary.lstat()
            if getattr(info, "st_file_attributes", 0) & 0x400 or not self.binary.is_file():
                raise CuaError("Driver executable is not a regular pinned local artifact")
            # Do not pass model, CLI, cloud or Companion credentials to Driver.
            keys = {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA"}
            environment = {key: value for key, value in os.environ.items() if key.upper() in keys}
            environment["CUA_DRIVER_PERMISSION_MODE"] = "standard"
            # Actual audited telemetry.rs environment precedence, confined to
            # our child; no global user preference is changed.
            environment["CUA_DRIVER_RS_TELEMETRY_ENABLED"] = "false"
            environment["DO_NOT_TRACK"] = "1"
            environment["CUA_DRIVER_RS_UPDATE_CHECK"] = "false"
            environment["CUA_DRIVER_RS_HOME"] = str(self.private_state)
            environment["CUA_DRIVER_TELEMETRY_HOME"] = str(self.private_state)
            # Keep the exact native file handle open without write/delete
            # sharing from hashing through CreateProcess (no hash/launch race).
            with self.backend.open_reader(str(self.binary.parent), self.binary.name, maximum=512 * 1024 * 1024) as stream:
                digest = hashlib.sha256()
                while True:
                    chunk = stream.read(262144)
                    if not chunk:
                        break
                    digest.update(chunk)
                if digest.hexdigest() != self.sha256:
                    raise CuaError("Driver artifact hash mismatch; no process started")
                try:
                    self.process = self.backend.spawn_command([str(self.binary), "mcp", "--direct"], cwd=str(self.binary.parent),
                                                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                                               env=environment, bufsize=0, job_name=self.job_name)
                except NativeProcessStartError as failure:
                    self.process, self._broken = failure.process, True
                    if failure.cleanup_proof.get("processGroupStoppedVerified"):
                        self._stop_proof = dict(failure.cleanup_proof)
                    raise CuaError("Original Driver creation/pipe construction failed; no automatic new worker or input replay", effect_unknown=True) from failure
            threading.Thread(target=self._reader, name="pocket-cua-stdio", daemon=True).start()
        try:
            initialization = self._request("initialize", {"protocolVersion": MCP_VERSION, "capabilities": {},
                                                          "clientInfo": {"name": "pocketworld-companion", "version": "1"}})
            if initialization.get("protocolVersion") != MCP_VERSION:
                raise CuaError("Pinned Driver MCP negotiation mismatch")
            server = initialization.get("serverInfo", {})
            if server.get("version") != UPSTREAM_VERSION:
                raise CuaError("Pinned Driver runtime version mismatch; no desktop tool admitted")
            self._write({"jsonrpc": "2.0", "method": "notifications/initialized"})
            listed = self._request("tools/list", {})
            self.tools = {item["name"]: item for item in listed.get("tools", []) if isinstance(item, dict) and item.get("name") in ALLOWED}
            if not {"get_window_state", "get_accessibility_tree"} <= self.tools.keys():
                raise CuaError("Pinned Driver does not expose the required Windows observation contract")
        except BaseException:
            self.stop()
            raise

    def _reader(self):
        try:
            while True:
                line = self.process.stdout.readline(MAX_RESPONSE + 1)
                if not line:
                    raise CuaError("Driver exited; no response is confirmed", effect_unknown=True)
                if len(line) > MAX_RESPONSE or not line.endswith(b"\n"):
                    raise CuaError("Driver response exceeded its bounded framing", effect_unknown=True)
                response = json.loads(line.decode("utf-8"))
                if not isinstance(response, dict) or response.get("jsonrpc") != "2.0":
                    raise CuaError("Driver returned an invalid JSON-RPC envelope", effect_unknown=True)
                if "id" not in response:
                    continue  # Content-free MCP notifications are not action receipts.
                if "method" in response:
                    # Driver cannot elicit/grant further authority on its own.
                    self._write({"jsonrpc": "2.0", "id": response["id"], "error": {"code": -32601, "message": "Host requests are unsupported"}})
                    continue
                self._replies.put(response, timeout=1)
        except BaseException as failure:
            self._broken = True
            try:
                self._replies.put_nowait(failure)
            except queue.Full:
                pass

    def _write(self, value):
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8") + b"\n"
        if len(raw) > 65536:
            raise CuaError("Driver request exceeds the control-message bound", 413)
        try:
            self.process.stdin.write(raw)
            self.process.stdin.flush()
        except (OSError, ValueError) as failure:
            self._broken = True
            raise CuaError("Driver pipe write is unconfirmed; do not retry an action", effect_unknown=True) from failure

    def _request(self, method, params, timeout=12):
        with self._calls:
            if self._broken:
                raise CuaError("Driver lifecycle is broken; no replay is allowed", effect_unknown=True)
            self._sequence += 1
            sequence = self._sequence
            self._write({"jsonrpc": "2.0", "id": sequence, "method": method, "params": params})
            try:
                reply = self._replies.get(timeout=timeout)
            except queue.Empty as failure:
                self._broken = True
                self.stop()
                raise CuaError("Driver timed out after possible dispatch; inspect original operation", effect_unknown=True) from failure
            if isinstance(reply, BaseException):
                raise CuaError("Driver response was lost; inspect the original operation", effect_unknown=True) from reply
            if reply.get("id") != sequence or "error" in reply or not isinstance(reply.get("result"), dict):
                self._broken = True
                raise CuaError("Driver response identity or protocol failed; no action replay", effect_unknown=True)
            return reply["result"]

    def call(self, tool, arguments):
        self.start()
        if tool not in ALLOWED or tool not in self.tools:
            raise CuaError("This Driver tool is not admitted by the desktop adapter")
        schema = self.tools[tool].get("inputSchema", {})
        if not isinstance(arguments, dict) or set(arguments) - set(schema.get("properties", {})) or not set(schema.get("required", [])) <= arguments.keys():
            raise CuaError("Desktop arguments do not match the actual pinned Driver schema", 400)
        # Business code builds this exact, narrow argument set. No arbitrary Cua
        # tool name, output path, JS, shell, perception or browser hook is exposed.
        return self._request("tools/call", {"name": tool, "arguments": arguments})

    def stop(self):
        with self._lifecycle:
            self._broken = True
            if self._stop_proof is not None:
                return dict(self._stop_proof)
            if self.process is None:
                self._stop_proof = {"processGroupStoppedVerified": True, "notStarted": True}
                return dict(self._stop_proof)
            proof = self.backend.terminate(self.process, hard=True)
            if proof.get("processGroupStoppedVerified"):
                self.process.close()
                self._stop_proof = dict(proof)
            return proof

    def recovery_binding(self):
        identity = self.backend.process_identity(os.getpid())
        if not identity.get("available") or not identity.get("exists") or identity.get("exited"):
            raise CuaError("Original supervisor process generation is unavailable; no recoverable desktop worker admitted")
        return {"format": "windows_original_job_v1", "jobName": self.job_name,
                "hostPid": identity["pid"], "hostCreated": identity["created"], "sessionId": identity["sessionId"],
                "logonContext": self.backend.logon_context()}

    def capabilities(self):
        return {"driver": "cua-driver", "version": UPSTREAM_VERSION, "sourceCommit": UPSTREAM_COMMIT,
                "artifactSha256": self.sha256, "releaseStatus": "prerelease",
                "installed": self.binary.is_file(), "tools": sorted(self.tools), "requiresExternalAgent": False,
                "runtimeContract": "verified_against_pinned_process" if self.tools and not self._broken else "not_probed",
                "channelHealthy": self.process is not None and not self._broken,
                "lifecycleBroken": self._broken,
                "modelRequired": False, "nativeGuiVerified": False,
                "desktopCaptureDisplays": ["primary"], "spacesIncluded": False, "perceptionIncluded": False,
                "singleKeys": {"admitted": ["Enter", "Escape", "Tab"], "runtimeAvailable": "press_key" in self.tools and not self._broken,
                               "target": "exact_observed_window", "compoundFocusClick": False, "nativeGuiVerified": False},
                "drag": False, "reasonDrag": "Not admitted until the pinned Windows drag contract and physical behavior are separately verified"}
