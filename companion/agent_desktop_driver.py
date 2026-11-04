"""Pinned agent-desktop semantics composed with the existing Cua transport.

The CLI owns UIA enumeration, native identity resolution and semantic dispatch.
This module only translates its documented 2.6 JSON contract into PocketWorld's
bounded desktop contract. It never enables --headed, launches an Agent, accepts
shell text, downloads executables, or retries input. Cua retains visual capture
and explicitly image-grounded physical operations.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid

from cua_desktop_adapter import CuaError
from platform_backend import NativeProcessStartError

UPSTREAM_COMMIT = "6660fbac768dc7e7332837b90ed465684859afa3"
UPSTREAM_VERSION = "1.1.0"
CONTRACT_VERSION = "2.6"
MAX_RESPONSE = 4 * 1024 * 1024
_REF = re.compile(r"@s[a-z0-9]+:e[0-9]+")
_TOKEN = re.compile(r"s[0-9a-f]{8}:[0-9]+")
_ROLES = {"textfield": "Edit", "textbox": "Edit", "textarea": "Document",
          "document": "Document", "button": "Button", "checkbox": "CheckBox",
          "combobox": "ComboBox", "menuitem": "MenuItem", "treeitem": "TreeItem",
          "listbox": "List", "option": "ListItem", "tab": "TabItem", "link": "Hyperlink",
          "window": "Window", "group": "Group", "text": "Text", "scrollarea": "Pane"}
_ACTIONS = {"Click": "invoke", "SetValue": "set_value", "Toggle": "toggle", "Select": "select",
            "Expand": "expand", "Collapse": "collapse", "Scroll": "scroll"}


def _json(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("Duplicate JSON member")
            value[key] = item
        return value
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Non-finite JSON")))


def _number(value):
    if type(value) not in {int, float} or not math.isfinite(value) or abs(value) > 10000000:
        raise CuaError("agent-desktop returned invalid native bounds")
    return int(value) if int(value) == value else str(value)


class AgentDesktopCli:
    """Serialized, bounded native child calls inside an original Windows Job.

    One fixed private Job name is reused only after its prior native process
    tree is proved stopped and its handles are closed. Recovery can inspect
    that same name after a host crash; an unknown call breaks this client.
    """
    def __init__(self, binary, sha256, backend, *, private_state):
        self.binary, self.sha256, self.backend = Path(binary), sha256, backend
        self.private_state = Path(private_state)
        key = hashlib.sha256(str(self.private_state).casefold().encode()).hexdigest()[:32]
        self.job_name = "Local\\PocketWorldDesktop-" + key + "-" + uuid.uuid4().hex
        self._calls, self._lifecycle = threading.RLock(), threading.RLock()
        self._broken = False
        self._version_checked = False
        self._guard = self._artifact = self.process = None
        self._stop_proof = None

    def _prepare(self):
        with self._lifecycle:
            if self._broken:
                raise CuaError("Original agent-desktop lifecycle is closed; input is never replayed", effect_unknown=True)
            if self._artifact is not None:
                return
            if os.name != "nt" or self.backend.name != "windows":
                raise CuaError("agent-desktop Companion adapter requires the Windows interactive user session")
            if (not self.binary.is_absolute() or not self.private_state.is_absolute()
                    or not re.fullmatch(r"[a-f0-9]{64}", self.sha256 or "")):
                raise CuaError("Pinned absolute agent-desktop artifact and private state are required")
            self.private_state.mkdir(mode=0o700, exist_ok=True)
            with self.backend.directory(str(self.private_state)):
                pass
            info = self.binary.lstat()
            if not self.binary.is_file() or getattr(info, "st_file_attributes", 0) & 0x400:
                raise CuaError("agent-desktop must be a regular pinned local executable")
            guard = self.backend.open_reader(str(self.binary.parent), self.binary.name, maximum=512 * 1024 * 1024)
            artifact = guard.__enter__()
            try:
                digest = hashlib.sha256()
                while True:
                    raw = artifact.read(262144)
                    if not raw:
                        break
                    digest.update(raw)
                if digest.hexdigest() != self.sha256:
                    raise CuaError("agent-desktop executable hash differs from its installed pin")
            except BaseException:
                guard.__exit__(None, None, None)
                raise
            # Retain the non-write/delete-sharing native file handle until stop.
            self._guard, self._artifact = guard, artifact

    def check_version(self):
        with self._calls:
            if not self._version_checked:
                result = self._execute(["version"], mutation=False)
                data = result.get("data", {})
                if not result.get("ok") or data.get("version") != UPSTREAM_VERSION or data.get("os") != "windows":
                    self.stop()
                    raise CuaError("Pinned agent-desktop package/platform contract differs from its installed binary")
                self._version_checked = True

    def execute(self, argv, *, mutation=False):
        with self._calls:
            self.check_version()
            return self._execute(argv, mutation=mutation)

    def _execute(self, argv, *, mutation):
        self._prepare()
        if (not isinstance(argv, list) or not argv or argv[0] not in {"version", "snapshot", "click", "set-value"}
                or any(not isinstance(part, str) or "\x00" in part for part in argv)):
            raise CuaError("Unsupported agent-desktop command contract", 400)
        keys = {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA"}
        environment = {key: value for key, value in os.environ.items() if key.upper() in keys}
        environment.update(AGENT_DESKTOP_HOME=str(self.private_state), DO_NOT_TRACK="1", NO_COLOR="1")
        with self._lifecycle:
            if self._broken:
                raise CuaError("agent-desktop was stopped before command dispatch", effect_unknown=mutation)
            try:
                self.process = self.backend.spawn_command([str(self.binary), *argv], cwd=str(self.binary.parent),
                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    env=environment, bufsize=0, job_name=self.job_name)
            except NativeProcessStartError as failure:
                self.process, self._broken = failure.process, True
                if failure.cleanup_proof.get("processGroupStoppedVerified"):
                    self._stop_proof = dict(failure.cleanup_proof)
                raise CuaError("agent-desktop process construction is unconfirmed; never retry input", effect_unknown=mutation) from failure
            process = self.process
        replies = queue.Queue(maxsize=1)
        def read():
            try:
                chunks, size = [], 0
                while True:
                    chunk = process.stdout.read(min(65536, MAX_RESPONSE + 1 - size))
                    if not chunk:
                        break
                    chunks.append(chunk); size += len(chunk)
                    if size > MAX_RESPONSE:
                        raise CuaError("agent-desktop output exceeded the bounded JSON contract", effect_unknown=mutation)
                replies.put(b"".join(chunks))
            except BaseException as failure:
                replies.put(failure)
        reader = threading.Thread(target=read, name="pocket-agent-desktop-output", daemon=True)
        reader.start()
        try:
            raw = replies.get(timeout=12)
            if isinstance(raw, BaseException):
                raise raw
            code = process.wait(timeout=2)
            result = _json(raw)
            if (not isinstance(result, dict) or result.get("version") != CONTRACT_VERSION
                    or result.get("command") != argv[0] or type(result.get("ok")) is not bool
                    or code not in {0, 1, 2} or (code == 0) != result["ok"]
                    or not isinstance(result.get("data" if result["ok"] else "error"), dict)):
                raise CuaError("agent-desktop response identity/version is unconfirmed", effect_unknown=mutation)
            with self._lifecycle:
                if self._broken:
                    raise CuaError("agent-desktop was stopped while returning an operation", effect_unknown=mutation)
                proof = self.backend.terminate(process, hard=True)
                if proof.get("processGroupStoppedVerified") is not True:
                    raise CuaError("Original agent-desktop native process tree did not settle", effect_unknown=mutation)
                process.close()
                self.process = None
            return result
        except BaseException as failure:
            self.stop()
            if isinstance(failure, CuaError):
                raise
            raise CuaError("agent-desktop response was lost or invalid; never replay an input", effect_unknown=mutation) from failure

    def stop(self):
        with self._lifecycle:
            self._broken = True
            if self._stop_proof is not None:
                if self._guard is not None:
                    self._guard.__exit__(None, None, None)
                    self._guard = self._artifact = None
                return dict(self._stop_proof)
            if self.process is None:
                proof = {"notStartedOrPriorCallsStopped": True, "processGroupStoppedVerified": True}
            else:
                proof = self.backend.terminate(self.process, hard=True)
                if proof.get("processGroupStoppedVerified"):
                    self.process.close()
                    self.process = None
            if proof.get("processGroupStoppedVerified"):
                self._stop_proof = dict(proof)
                if self._guard is not None:
                    self._guard.__exit__(None, None, None)
                    self._guard = self._artifact = None
            return proof

    def recovery_binding(self):
        identity = self.backend.process_identity(os.getpid())
        if not identity.get("available") or not identity.get("exists") or identity.get("exited"):
            raise CuaError("Original agent-desktop host generation is unavailable")
        return {"format": "windows_original_job_v1", "jobName": self.job_name,
                "hostPid": identity["pid"], "hostCreated": identity["created"], "sessionId": identity["sessionId"],
                "logonContext": self.backend.logon_context()}


class AgentDesktopDriver:
    """One desktop provider: native semantic refs plus Cua visual operations."""
    def __init__(self, cua, client):
        self.cua, self.client = cua, client
        self._lock = threading.RLock()
        self._refs = {}

    def recovery_binding(self):
        return {"format": "windows_original_jobs_v2", "workers": [self.cua.recovery_binding(), self.client.recovery_binding()]}

    def stop(self):
        # Both originals are attempted even if one backend cannot prove stop.
        proofs = []
        for worker in (self.cua, self.client):
            try:
                proofs.append(worker.stop())
            except Exception:
                proofs.append({"processGroupStoppedVerified": False, "cleanupUnconfirmed": True})
        return {"processGroupStoppedVerified": all(p.get("processGroupStoppedVerified") is True for p in proofs), "workers": proofs}

    def capabilities(self):
        base = self.cua.capabilities()
        return {**base, "driver": "agent-desktop+cua-driver", "semanticProvider": "agent-desktop",
                "semanticSourceCommit": UPSTREAM_COMMIT, "semanticVersion": UPSTREAM_VERSION,
                "semanticContractVersion": CONTRACT_VERSION, "semanticArtifactSha256": self.client.sha256,
                "semanticLicense": "Apache-2.0", "visualProvider": "cua-driver",
                "installed": base.get("installed", False) and self.client.binary.is_file(),
                "lifecycleBroken": base.get("lifecycleBroken", False) or self.client._broken,
                "semanticRuntimeContract": "verified_against_pinned_process" if self.client._version_checked and not self.client._broken else "not_probed",
                "tools": sorted(set(base.get("tools", [])) | {"get_window_state", "verify_state", "click", "set_value"}),
                "observationModes": ["semantic", "visual", "hybrid"], "defaultObservationMode": "semantic",
                "semanticActions": ["click", "set_text"], "semanticPhysicalFallback": False,
                "nativeGuiVerified": False}

    def _snapshot(self, arguments):
        pid, window = arguments.get("pid"), arguments.get("window_id")
        if type(pid) is not int or not 1 <= pid <= 0xFFFFFFFF or type(window) is not int or not 1 <= window <= 9007199254740991:
            raise CuaError("Exact native window/process required for agent-desktop", 400)
        result = self.client.execute(["snapshot", "--window-id", "w-" + str(window), "--include-bounds", "--max-depth", "12", "--timeout-ms", "5000"])
        if result.get("ok") is not True:
            raise CuaError("agent-desktop could not read the exact native window: " + str(result.get("error", {}).get("code", "unknown")))
        data = result["data"]
        actual_window, tree, native_id = data.get("window"), data.get("tree"), data.get("snapshot_id")
        if (not isinstance(actual_window, dict) or actual_window.get("id") != "w-" + str(window)
                or not isinstance(tree, dict) or not isinstance(native_id, str) or not re.fullmatch(r"s[a-z0-9]+", native_id)):
            raise CuaError("agent-desktop returned a different or unproven window identity")
        # WindowsDesktopHealth validates the HWND's actual owning PID both before
        # and after this call. The CLI identifies windows by HWND, not --app names.
        maximum = min(150, max(1, arguments.get("max_elements", 150)))
        prefix = "s" + uuid.uuid4().hex[:8]
        while any(token.startswith(prefix + ":") for token in self._refs):
            prefix = "s" + uuid.uuid4().hex[:8]
        elements, stack, cut, refs_seen = [], [(tree, None, 0)], False, set()
        now = time.monotonic()
        self._refs = {token: entry for token, entry in self._refs.items() if now - entry["created"] < 60}
        if len(self._refs) > 4800:
            raise CuaError("Too many retained native desktop refs; wait for observation expiry")
        while stack:
            node, parent, depth = stack.pop()
            if not isinstance(node, dict) or depth > 50:
                raise CuaError("agent-desktop returned an invalid bounded tree")
            if len(elements) >= maximum:
                cut = True
                break
            index = len(elements)
            role, states = node.get("role"), node.get("states", [])
            if not isinstance(role, str) or not isinstance(states, list) or any(not isinstance(s, str) for s in states):
                raise CuaError("agent-desktop returned invalid element metadata")
            element = {"element_index": index, "role": _ROLES.get(role, role), "depth": depth,
                       "enabled": "disabled" not in states, "actions": []}
            if parent is not None:
                element["parent_index"] = parent
            if "selected" in states:
                element["selected"] = True
            for source, destination in (("name", "label"), ("value", "value")):
                if isinstance(node.get(source), str):
                    element[destination] = node[source]
            bounds = node.get("bounds")
            if bounds is not None:
                if not isinstance(bounds, dict) or not {"x", "y", "width", "height"} <= bounds.keys():
                    raise CuaError("agent-desktop returned invalid bounds")
                element["frame"] = {key: _number(bounds[source]) for key, source in (("x", "x"), ("y", "y"), ("w", "width"), ("h", "height"))}
            actions = node.get("available_actions", [])
            if not isinstance(actions, list):
                raise CuaError("agent-desktop returned invalid element actions")
            element["actions"] = [_ACTIONS[action] for action in actions if isinstance(action, str) and action in _ACTIONS]
            # Secure text is neither returned nor writable through this adapter.
            if "secure" in states:
                element.pop("value", None)
                element["actions"] = []
                element["secure"] = True
            native_ref = node.get("ref_id")
            if native_ref is not None:
                if not isinstance(native_ref, str) or not _REF.fullmatch(native_ref) or not native_ref.startswith("@" + native_id + ":") or native_ref in refs_seen:
                    raise CuaError("agent-desktop returned an unqualified, duplicate or cross-snapshot ref")
                refs_seen.add(native_ref)
                token = prefix + ":" + str(index)
                element["element_token"] = token
                self._refs[token] = {"ref": native_ref, "pid": pid, "window_id": window, "created": now,
                                     "element": copy.deepcopy(element), "identity_unproven": data.get("identity_unproven") is True}
            elements.append(element)
            if node.get("subtree_truncated") or node.get("children_count"):
                cut = True
            children = node.get("children", [])
            if not isinstance(children, list):
                raise CuaError("agent-desktop returned invalid child nodes")
            stack.extend((child, index, depth + 1) for child in reversed(children))
        return {"pid": pid, "window_id": window, "window_title": actual_window.get("title", ""),
                "app_name": data.get("app", ""), "snapshot_id": prefix, "semantic_provider": "agent-desktop",
                "elements_complete": data.get("complete") is True and not cut, "truncated": data.get("complete") is not True or cut,
                "identity_unproven": data.get("identity_unproven") is True, "elements": elements}

    def _entry(self, arguments):
        token = arguments.get("element_token")
        entry = self._refs.get(token) if isinstance(token, str) and _TOKEN.fullmatch(token) else None
        if (entry is None or entry["identity_unproven"] or time.monotonic() - entry["created"] >= 60
                or entry["pid"] != arguments.get("pid") or entry["window_id"] != arguments.get("window_id")):
            raise CuaError("Original agent-desktop ref is missing, expired or belongs to another window")
        return entry

    def validate_call(self, tool, arguments):
        if tool in {"click", "set_value"} and "element_token" in arguments:
            entry = self._entry(arguments)
            allowed = ({"scope", "pid", "window_id", "delivery_mode", "element_token", "button", "count"} if tool == "click"
                       else {"pid", "window_id", "delivery_mode", "element_token", "value"})
            if set(arguments) - allowed or arguments.get("delivery_mode") != "background":
                raise CuaError("agent-desktop semantic input cannot enable foreground or pixel fallback", 400)
            if tool == "click" and (arguments.get("button", "left") != "left" or arguments.get("count", 1) != 1 or "invoke" not in entry["element"]["actions"]):
                raise CuaError("Semantic click requires one advertised left activation; use an explicitly observed visual action for other gestures")
            if tool == "set_value" and ("set_value" not in entry["element"]["actions"] or not isinstance(arguments.get("value"), str)
                    or len(arguments["value"].encode("utf-8")) > 10000 or "\x00" in arguments["value"]):
                raise CuaError("Semantic SetValue requires a readable non-secure target and at most 10000 UTF-8 bytes", 400)
            self.client.check_version()
        else:
            self.cua.validate_call(tool, arguments)

    @staticmethod
    def _action_result(result):
        body = result.get("data" if result.get("ok") else "error", {})
        disposition = body.get("disposition") if isinstance(body, dict) else None
        if not isinstance(disposition, dict) or disposition.get("delivery") not in {"not_delivered", "delivered_verified", "delivered_unverified", "delivery_uncertain", "unknown"}:
            raise CuaError("agent-desktop returned no trustworthy input disposition", effect_unknown=True)
        expected_retry = {"not_delivered": "safe", "unknown": "unknown"}.get(disposition["delivery"], "unsafe")
        if disposition.get("retry") != expected_retry:
            raise CuaError("agent-desktop returned an inconsistent input disposition", effect_unknown=True)
        if any(step.get("mechanism") == "physical_synthetic" for step in body.get("steps", []) if isinstance(step, dict)):
            raise CuaError("Pinned semantic action unexpectedly attempted physical input", effect_unknown=True)
        return {"isError": result.get("ok") is not True, "content": [],
                "structuredContent": {"driver": "agent-desktop", "nativeDisposition": disposition, "report": body}}

    def call(self, tool, arguments):
        with self._lock:
            if tool == "get_window_state" and arguments.get("include_accessibility_tree", True):
                state = self._snapshot(arguments)
                content = []
                if arguments.get("include_screenshot", True):
                    capture = self.cua.call(tool, {"pid": arguments["pid"], "window_id": arguments["window_id"],
                        "include_accessibility_tree": False, "include_screenshot": True,
                        "max_image_dimension": arguments.get("max_image_dimension", 1200)})
                    visual = capture.get("structuredContent", {})
                    if (capture.get("isError") or visual.get("pid") != state["pid"] or visual.get("window_id") != state["window_id"]
                            or visual.get("window_title", "") != state["window_title"]):
                        raise CuaError("Cua visual capture and native semantic observation identify different windows")
                    for key in ("screenshot_width", "screenshot_height", "capture_id", "window_bounds", "capture_coverage"):
                        if key in visual:
                            state[key] = visual[key]
                    content = capture.get("content", [])
                return {"structuredContent": state, "content": content}
            if tool == "verify_state":
                state = self._snapshot({**arguments, "max_elements": 150})
                predicates = arguments.get("expect", [])
                if len(predicates) != 1 or "element" not in predicates[0]:
                    raise CuaError("Only a single bounded semantic preflight predicate is admitted", 400)
                predicate = predicates[0]["element"]
                selector = predicate.get("selector", {})
                matches = [item for item in state["elements"] if item["role"] == selector.get("role")
                           and str(selector.get("label_contains", "")).casefold() in str(item.get("label", "")).casefold()]
                matched = (state["elements_complete"] and len(matches) == 1 and matches[0].get("enabled") is predicate.get("enabled", True)
                           and ("value_equals" not in predicate or matches[0].get("value") == predicate["value_equals"]))
                status = "satisfied" if matched else "unsatisfied"
                evidence = {key: value for key, value in matches[0].items() if key in {"role", "label", "value", "frame", "enabled", "selected"}} if len(matches) == 1 else {}
                return {"structuredContent": {"status": status, "stable": bool(matched), "samples": 1,
                        "predicates": [{"index": 0, "status": status, "unknown_reason": None if matched else "changed_ambiguous_or_incomplete",
                                        "observed_json": json.dumps(evidence, ensure_ascii=False)}]}, "content": []}
            if tool in {"click", "set_value"} and "element_token" in arguments:
                self.validate_call(tool, arguments)
                entry = self._entry(arguments)
                argv = ["click", entry["ref"], "--timeout-ms", "0"] if tool == "click" else ["set-value", entry["ref"], arguments["value"], "--timeout-ms", "0"]
                return self._action_result(self.client.execute(argv, mutation=True))
            return self.cua.call(tool, arguments)
