"""Authenticated owner viewing/input on the original desktop lease and ledger.

Viewer sessions are bounded native UI resources, not Agent tasks. Operations use
JobManager.operations for their single dispatch claim; input and Agent actions
share DesktopAdapter's lock, driver, local stop and desktop_resource_leases.
"""
from __future__ import annotations

import base64
from collections import OrderedDict
import copy
from dataclasses import dataclass
import hashlib
import io
import json
import re
import threading
import uuid

from cua_desktop_adapter import input_outcome
from desktop_adapter import DesktopError
from desktop_jobs import DesktopJobs
from desktop_stream import DesktopStreamService, MAX_NATIVE_PIXELS, MAX_SESSION_MS, _resource
from device_protocol import arguments_digest, canonical_bytes


PROTOCOL = "desktop-viewer-v1"
LEGACY_TOMBSTONE = "desktop-viewer-legacy-tombstone-v1"


@dataclass
class _Viewer:
    id: str
    audience: dict
    owner: str
    binding: dict
    target: dict | None
    identity: dict
    resource: str
    permission: object
    expires: int
    width: int
    height: int
    stream_id: str | None = None
    lease: dict | None = None
    closed: bool = False
    uncertain: bool = False
    cleanup_pending: bool = False


class DesktopViewer:
    def __init__(self, jobs, *, streams=None):
        self.jobs, self.adapter, self.manager = jobs, jobs.adapter, jobs.manager
        self.streams = streams if streams is not None else DesktopStreamService(self.adapter.health, clock=self.adapter.clock)
        # Serializes native UI requests only; the media watchdog never waits on it.
        self.lock = threading.RLock()
        self.sessions = OrderedDict()
        self.replies = OrderedDict()
        self.closed = False
        self._retain_legacy_claims()
        self.streams.on_terminal = self._stream_ended
        self.adapter.viewer_stopper = self.close
        self.adapter.viewer_cleanup = self.cleanup

    def describe(self):
        return {**self.streams.describe(), "method": "desktop.viewer", "requiresExplicitGrant": True,
                "inputTransport": "original_paired_mutual_tls", "automaticInputReplay": False}

    def _retain_legacy_claims(self):
        """Keep main's already-claimed identities in the original operations.

        The prior table remains read-only historical evidence. Neither its
        receipt nor elapsed time proves input settlement; resource/owner/fence
        rows and the original grant counters are deliberately left unchanged.
        """
        with self.manager.lock:
            database = self.manager.ledger
            if not database.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='desktop_viewer_operations'").fetchone():
                return
            columns = {row[1] for row in database.execute("PRAGMA table_info(desktop_viewer_operations)")}
            if columns != {"operation_id", "digest", "owner", "session_id", "action", "state", "deadline", "receipt"}:
                raise DesktopError("Legacy viewer operation layout is unknown; local reconciliation is required", 409)
            rows = database.execute("SELECT operation_id,digest,owner,session_id,action,state,deadline FROM desktop_viewer_operations ORDER BY operation_id LIMIT 16385").fetchall()
            if len(rows) > 16384 or database.in_transaction:
                raise DesktopError("Legacy viewer claims cannot be safely retained in the original ledger", 409)
            database.execute("SAVEPOINT desktop_viewer_legacy_claims")
            try:
                for operation, digest, owner, session_id, action, state, deadline in rows:
                    identity = {"owner": owner, "viewerSessionId": session_id, "action": action, "deadline": deadline}
                    previous = database.execute("SELECT fingerprint,job_id,details FROM operations WHERE operation_id=?", (operation,)).fetchone()
                    if previous is not None:
                        details = json.loads(previous[2] or "{}")
                        if (previous[:2] != (digest, "desktop-viewer:" + session_id)
                                or details.get("protocol") != LEGACY_TOMBSTONE or details.get("legacyViewer") != identity):
                            raise DesktopError("Legacy viewer operation conflicts with an original operation; no identity was overwritten", 409)
                        continue
                    details = {"protocol": LEGACY_TOMBSTONE, "legacyViewer": identity, "legacyState": state,
                               "status": state, "noAutomaticReplay": True}
                    database.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                     (operation, digest, "desktop-viewer:" + session_id, state, self.manager.now(), json.dumps(details)))
                database.execute("RELEASE SAVEPOINT desktop_viewer_legacy_claims")
            except BaseException:
                if database.in_transaction:
                    database.execute("ROLLBACK TO SAVEPOINT desktop_viewer_legacy_claims")
                    database.execute("RELEASE SAVEPOINT desktop_viewer_legacy_claims")
                raise

    @staticmethod
    def _audience(envelope, permission):
        return {"callerDeviceId": envelope.get("callerDeviceId"), "originDeviceId": envelope.get("originDeviceId"),
                "targetDeviceId": envelope.get("targetDeviceId"), "instanceId": envelope.get("instanceId"),
                "grantId": envelope.get("grantId"), "authorizationEpoch": envelope.get("authorizationEpoch"),
                "grantFingerprint": permission.grant_fingerprint, "resourceBinding": envelope.get("resourceBinding"),
                "viewerSessionId": envelope.arguments["viewerSessionId"]}

    def _current(self, session, permission=None, *, closing=False):
        if self.closed or self.manager.closed:
            raise DesktopError("Desktop viewer runtime is stopping", 409)
        now = self.adapter.clock()
        if permission is not None:
            permission.check_current(now)
        if not closing:
            session.permission.check_current(now)
            if session.closed or session.expires <= now:
                raise DesktopError("Desktop viewer session has ended; open a new native session", 409)
            self.adapter._current(lambda: None)
            health = self.adapter._health(session.target)
            identity, _, _ = _resource(session.binding, session.target, health)
            if identity != session.identity:
                raise DesktopError("The original interactive desktop/process changed", 409)
        elif self.adapter.instance_id != self.manager.instance_id:
            raise DesktopError("Desktop execution instance changed", 409)

    def _claim(self, envelope, audience, permission):
        operation = envelope.get("operationId")
        digest = arguments_digest(envelope.json())
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if row:
                details = json.loads(row[1] or "{}")
                if row[0] != digest or details.get("protocol") != PROTOCOL or details.get("audience") != audience:
                    raise DesktopError("Original operation identity conflicts with another request", 409)
                reply = self.replies.get(operation)
                if reply is not None:
                    return copy.deepcopy(reply)
                raise DesktopError("Original viewer operation was already claimed; its effect is unknown and will not be replayed", 409)
            details = {"protocol": PROTOCOL, "audience": audience, "action": envelope.arguments["action"],
                       "status": "claimed", "inputDispatchAttempted": False, "instanceId": self.manager.instance_id}
            database = self.manager.ledger
            if database.in_transaction:
                raise DesktopError("Original ledger has an unsettled transaction; no viewer input prepared", 409)
            database.execute("SAVEPOINT desktop_viewer_claim")
            try:
                if envelope.arguments["action"] == "input":
                    details["ownerGrantPool"] = self._spend_input(permission, database)
                    details.update(toolUnits=1, refundOnUnknown=False)
                database.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                 (operation, digest, "desktop-viewer:" + audience["viewerSessionId"], "claimed", self.adapter.clock(), json.dumps(details)))
                database.execute("RELEASE SAVEPOINT desktop_viewer_claim")
            except BaseException:
                if database.in_transaction:
                    database.execute("ROLLBACK TO SAVEPOINT desktop_viewer_claim")
                    database.execute("RELEASE SAVEPOINT desktop_viewer_claim")
                raise
        return None

    def _spend_input(self, permission, database):
        # Preserve the original owner-grant pool shared with Agent tools. This
        # claim is atomic with operations INSERT, without a fake task allocation.
        permission.check_current(self.adapter.clock())
        grant = permission.grant
        parts = [grant["peerDeviceId"], grant["localDeviceId"], grant["grantId"]]
        key = hashlib.sha256(canonical_bytes(parts)).hexdigest()
        row = database.execute("SELECT tool_limit,tool_spent FROM device_budget_pools WHERE pool_key=?", (key,)).fetchone()
        if row is None:
            database.execute("INSERT INTO device_budget_pools VALUES (?,?,?,?,?,?,?)", (key, *parts, permission.tool_budget, 0, self.manager.now()))
            row = (permission.tool_budget, 0)
        if row[1] >= min(row[0], permission.tool_budget):
            raise DesktopError("Original owner grant input/tool budget is exhausted", 409)
        database.execute("UPDATE device_budget_pools SET tool_spent=tool_spent+1 WHERE pool_key=?", (key,))
        return key

    def _record(self, operation, status, **fields):
        # Called under manager.lock, including together with every lease transition.
        row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()
        if row is None:
            raise DesktopError("Original viewer operation claim is unavailable", 409)
        details = json.loads(row[0])
        details.update(status=status, **fields)
        self.manager.ledger.execute("UPDATE operations SET last_status=?,updated_at=?,details=? WHERE operation_id=?",
                                    (status, self.adapter.clock(), json.dumps(details), operation))

    def _configuration(self, envelope, permission, audience):
        session_id = audience["viewerSessionId"]
        session = self.sessions.get(session_id)
        if session is not None:
            if session.audience != audience:
                raise DesktopError("Viewer session belongs to another original owner/grant/resource", 403)
            self._current(session, permission)
            return session
        if sum(not item.closed and item.expires > self.adapter.clock() for item in self.sessions.values()) >= 2:
            raise DesktopError("Maximum active desktop viewers reached", 409)
        with self.manager.lock:
            # An old native session must never regain authority after a restart.
            if self.manager.ledger.execute("SELECT 1 FROM operations WHERE job_id=? LIMIT 1", ("desktop-viewer:" + session_id,)).fetchone():
                raise DesktopError("Original viewer session already ended or its runtime was lost; open a new session", 409)
        binding = envelope.get("resourceBinding")
        target = None
        if "windowId" in binding:
            resolver = getattr(self.adapter.health, "resolve_window", None)
            if not callable(resolver):
                raise DesktopError("Local process identity for this window is unavailable", 409)
            target = resolver(binding["windowId"])
        health = self.adapter._health(target)
        identity, bounds, _ = _resource(binding, target, health)
        resource = self.adapter._scope(binding, health, target)
        expires = min(envelope.get("deadline"), permission.grant_expires_at, self.adapter.clock() + MAX_SESSION_MS)
        if expires <= self.adapter.clock() + 1000:
            raise DesktopError("Viewer authorization expires before negotiation can start", 409)
        width, height = bounds[2] - bounds[0], bounds[3] - bounds[1]
        ratio = min(1, self.streams.max_edge / max(width, height))
        owner = "viewer:" + hashlib.sha256(canonical_bytes(audience)).hexdigest()
        session = _Viewer(session_id, audience, owner, copy.deepcopy(binding), copy.deepcopy(target), identity,
                          resource, permission, expires, max(2, int(width * ratio) // 2 * 2), max(2, int(height * ratio) // 2 * 2))
        self._current(session, permission)
        # Load actual prepared dependencies before returning a usable configuration.
        self.streams.rtc_loader()
        while len(self.sessions) >= 32:
            old = next((key for key, value in self.sessions.items() if value.closed or value.expires <= self.adapter.clock()), None)
            if old is None:
                raise DesktopError("Desktop viewer retention bound reached", 409)
            del self.sessions[old]
        self.sessions[session_id] = session
        return session

    def _session(self, envelope, permission, audience):
        session = self.sessions.get(audience["viewerSessionId"])
        if session is None or session.audience != audience:
            raise DesktopError("Unknown viewer session or changed original owner/grant/resource", 403)
        self._current(session, permission, closing=envelope.arguments["action"] == "close")
        supplied = envelope.arguments.get("streamId")
        if supplied is not None and supplied != session.stream_id:
            raise DesktopError("Viewer stream belongs to a different native session", 403)
        if envelope.arguments["action"] in {"take", "release", "input"} and (session.stream_id is None or supplied != session.stream_id):
            raise DesktopError("The exact negotiated viewer stream is required", 409)
        return session

    def _lease_row(self, session):
        return self.manager.ledger.execute("SELECT owner,instance_id,lease_id,fence,expires,state FROM desktop_resource_leases WHERE resource_key=?", (session.resource,)).fetchone()

    def _control(self, session):
        with self.manager.lock:
            row = self._lease_row(session)
        value = {"owner": "none", "revision": row[3] if row else 0,
                 "needsReconcile": session.uncertain or bool(row and row[5] == "NEEDS_RECONCILE")}
        if row:
            # A human may acknowledge unknown input with release. Once that
            # exact UI lease is retired, report none + needsReconcile while the
            # original global fence remains. Other viewers never receive it.
            holder = next((item for item in list(self.sessions.values())
                           if item.owner == row[0] and not item.closed and row[1] == self.adapter.instance_id
                           and item.lease == {"id": row[2], "fence": row[3], "expiresAt": row[4], "resource": item.resource}), None)
            if row[5] in {"HELD", "EXECUTING"} or row[5] == "NEEDS_RECONCILE" and holder is not None:
                if holder is session and row[4] > self.adapter.clock():
                    value.update(owner="human", lease=copy.deepcopy(session.lease))
                elif row[0] != session.owner:
                    value["owner"] = "other_human" if row[0].startswith("viewer:") else "agent"
            elif row[5] == "NEEDS_RECONCILE" and row[1] == self.adapter.instance_id and not row[0].startswith("viewer:"):
                value["owner"] = "agent"
        return value

    def _view(self, session, envelope):
        value = {"viewerSessionId": session.id, "operationId": envelope.get("operationId"),
                 "kind": "desktopSession", "deviceId": self.adapter.source_device_id, "sessionId": session.id,
                 "resourceBinding": copy.deepcopy(session.binding), "expiresAt": min(session.expires, envelope.get("deadline")),
                 "width": session.width, "height": session.height, "control": self._control(session),
                 "iceServers": self.streams.client_configuration()["iceServers"]}
        if session.stream_id is not None:
            value["streamId"] = session.stream_id
        return value

    def _invalidate(self, session):
        self.adapter.invalidate_observations()
        if session.stream_id is not None and not session.closed:
            self.streams.invalidate_inputs(session.stream_id, session.owner, session.binding, lambda: self._current(session))

    def _unsettled_agent(self, resource):
        # An act may have returned to its worker but not persisted its final
        # settlement yet. Neither elapsed time nor a HELD row proves settlement.
        for job in self.manager.jobs.values():
            if job.get("protocol") != "desktop-v1":
                continue
            audience = job.get("desktopOperation", {})
            binding = audience.get("resourceBinding", {})
            if "windows-session:" + str(binding.get("sessionId")) + ":primary" != resource:
                continue
            settlement = job.get("executionSettlement", {})
            if (job.get("desktopDispatchAttempted") or audience.get("toolName") == "desktop.control") and not settlement.get("noFurtherDispatchForOperation"):
                return True
        return False

    def _take(self, session, envelope, permission):
        with self.adapter.lock, self.manager.lock:
            self._current(session, permission)
            if session.uncertain:
                raise DesktopError("A prior input is unconfirmed; release or close before continuing", 409)
            row = self._lease_row(session)
            revision = row[3] if row else 0
            if type(envelope.arguments["expectedRevision"]) is not int or envelope.arguments["expectedRevision"] != revision:
                raise DesktopError("Desktop control changed; refresh its current revision", 409)
            if (row and (row[1] != self.adapter.instance_id or row[5] not in {"HELD", "RELEASED"})
                    or self._unsettled_agent(session.resource)):
                raise DesktopError("Desktop input is executing or requires reconciliation; ownership cannot transfer", 409)
            if row and row[0].startswith("viewer:") and row[0] != session.owner and row[5] == "HELD":
                raise DesktopError("Another native viewer currently owns desktop input", 409)
            self._invalidate(session)
            lease = {"id": uuid.uuid4().hex, "fence": revision + 1,
                     "expiresAt": min(session.expires, envelope.get("deadline")), "resource": session.resource}
            self.manager.ledger.execute("INSERT OR REPLACE INTO desktop_resource_leases VALUES (?,?,?,?,?,?,?)",
                                        (session.resource, session.owner, self.adapter.instance_id, lease["id"], lease["fence"], lease["expiresAt"], "HELD"))
            self._record(envelope.get("operationId"), "completed", leaseFence=lease["fence"], executionSettlement={"noFurtherDispatchForOperation": True, "inputDispatched": False})
            self.manager.ledger.commit()
            session.lease = lease
        return self._view(session, envelope)

    def _release(self, session, envelope, permission):
        with self.adapter.lock, self.manager.lock:
            self._current(session, permission)
            row = self._lease_row(session)
            if row is None or type(envelope.arguments["expectedRevision"]) is not int or envelope.arguments["expectedRevision"] != row[3]:
                raise DesktopError("Desktop control changed; refresh its current revision", 409)
            if row[0] != session.owner or row[1] != self.adapter.instance_id or row[5] not in {"HELD", "NEEDS_RECONCILE"}:
                raise DesktopError("This viewer does not own a releasable human lease", 409)
            self._invalidate(session)
            # Acknowledging uncertainty retires this UI lease, never clears the
            # original resource's reconciliation requirement.
            state = "NEEDS_RECONCILE" if row[5] == "NEEDS_RECONCILE" else "RELEASED"
            self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=?,fence=fence+1 WHERE resource_key=?", (state, session.resource))
            self._record(envelope.get("operationId"), "completed", executionSettlement={"noFurtherDispatchForOperation": True, "inputDispatched": False})
            self.manager.ledger.commit()
            session.lease, session.uncertain = None, False
        return self._view(session, envelope)

    def _click(self, session, event, frame):
        x, y = event.get("x"), event.get("y")
        if (type(x) is not int or type(y) is not int or not 0 <= x < frame["width"] or not 0 <= y < frame["height"]
                or event.get("button") not in {"left", "right"} or type(event.get("count")) is not int or not 1 <= event["count"] <= 2):
            raise DesktopError("Viewer click is outside its actual displayed frame", 400)
        args = {"max_image_dimension": 0 if session.target else 1200}
        if session.target:
            args.update(pid=session.target["pid"], window_id=session.target["windowId"], include_accessibility_tree=False, include_screenshot=True)
        captured = self.adapter.driver.call("get_window_state" if session.target else "get_desktop_state", args)
        if captured.get("isError"):
            raise DesktopError("Current native input geometry could not be captured", 409)
        data = captured.get("structuredContent")
        if not isinstance(data, dict):
            raise DesktopError("Native input capture has no structured provenance", 409)
        capture_id = data.get("capture_id")
        if not isinstance(capture_id, str) or not 1 <= len(capture_id) <= 256:
            raise DesktopError("Native capture did not return its original input mapping", 409)
        images = [item for item in captured.get("content", []) if item.get("type") == "image" and item.get("mimeType") == "image/png"]
        if len(images) != 1 or not isinstance(images[0].get("data"), str) or len(images[0]["data"]) > 28 * 1024 * 1024:
            raise DesktopError("Native input capture is not one bounded PNG", 409)
        try:
            raw = base64.b64decode(images[0]["data"], validate=True)
            if len(raw) > 20 * 1024 * 1024 or raw[:8] != b"\x89PNG\r\n\x1a\n":
                raise ValueError("PNG bound")
            from PIL import Image
            with Image.open(io.BytesIO(raw)) as image:
                width, height = image.size
                if not 1 <= width * height <= MAX_NATIVE_PIXELS:
                    raise ValueError("pixel bound")
                image.verify()
        except Exception as failure:
            raise DesktopError("Native input capture geometry could not be verified", 409) from failure
        bounds = frame["captureBounds"]
        native_width, native_height = bounds[2] - bounds[0], bounds[3] - bounds[1]
        if session.target:
            if data.get("pid") != session.target["pid"] or data.get("window_id") != session.target["windowId"]:
                raise DesktopError("Native input capture belongs to another process/window", 409)
            window_bounds = data.get("window_bounds")
            if window_bounds != {"x": bounds[0], "y": bounds[1], "width": native_width, "height": native_height}:
                raise DesktopError("Native window geometry changed since the viewer frame", 409)
            rectangles = [bounds]
            dwm = frame["healthBinding"]["window"].get("frameBounds")
            if dwm is not None and dwm != bounds:
                rectangles.append(dwm)
            candidates = [rect for rect in rectangles if [rect[2] - rect[0], rect[3] - rect[1]] == [width, height]]
            if len(candidates) != 1:
                raise DesktopError("Native window capture crop cannot be mapped exactly", 409)
            crop = candidates[0]
            mapped_x = x * native_width / frame["width"] + bounds[0] - crop[0]
            mapped_y = y * native_height / frame["height"] + bounds[1] - crop[1]
        else:
            if data.get("screen_width") != native_width or data.get("screen_height") != native_height:
                raise DesktopError("Native primary display geometry changed", 409)
            mapped_x, mapped_y = x * width / frame["width"], y * height / frame["height"]
        if not 0 <= mapped_x < width or not 0 <= mapped_y < height:
            raise DesktopError("Viewer click falls outside the native actionable capture", 409)
        return {"capture_id": capture_id, "x": mapped_x, "y": mapped_y, "button": event["button"], "count": event["count"]}

    def _input_call(self, session, event, frame):
        if not isinstance(event, dict):
            raise DesktopError("One explicit bounded human input is required", 400)
        kind = event.get("kind")
        fields = {"click": {"kind", "x", "y", "button", "count"}, "scroll": {"kind", "direction", "amount", "by"},
                  "insert_text": {"kind", "text"}, "press_key": {"kind", "key"}, "hotkey": {"kind", "keys"}}
        if kind not in fields or set(event) != fields[kind]:
            raise DesktopError("Unknown human desktop input or extra fields", 400)
        target = session.target
        args = {"scope": "window" if target else "desktop", "delivery_mode": "foreground"}
        if target:
            args.update(pid=target["pid"], window_id=target["windowId"])
        if kind == "click":
            args.update(self._click(session, event, frame))
            name = "click"
        elif kind == "insert_text":
            text = event["text"]
            if not isinstance(text, str) or not 1 <= len(text.encode("utf-8")) <= 12000 or "\x00" in text:
                raise DesktopError("Human text exceeds its bound", 400)
            name = "type_text"
            args.update(text=text, delay_ms=0)
        elif kind == "press_key":
            if event["key"] not in {"enter", "escape", "tab"}:
                raise DesktopError("Unsupported human desktop key", 400)
            name = "press_key"
            args["key"] = {"enter": "return", "escape": "escape", "tab": "tab"}[event["key"]]
        elif kind == "hotkey":
            keys = event["keys"]
            if (not isinstance(keys, list) or not 2 <= len(keys) <= 4
                    or any(not isinstance(key, str) for key in keys)
                    or any(key.upper() not in {"CTRL", "ALT", "SHIFT", "WIN"} for key in keys[:-1])
                    or len({key.upper() for key in keys[:-1]}) != len(keys) - 1 or not re.fullmatch(r"[A-Za-z0-9]", keys[-1])):
                raise DesktopError("Unsupported human desktop hotkey", 400)
            name, args["keys"] = "hotkey", [key.lower() for key in keys]
        else:
            if event["direction"] not in {"up", "down", "left", "right"} or event["by"] != "line" or type(event["amount"]) is not int or not 1 <= event["amount"] <= 20:
                raise DesktopError("Unsupported human scroll", 400)
            foreground = frame["healthBinding"].get("foregroundWindowId")
            if target is None:
                resolver = getattr(self.adapter.health, "resolve_window", None)
                if not callable(resolver):
                    raise DesktopError("Foreground scroll process identity is unavailable", 409)
                target = resolver(foreground)
                # The pinned driver's desktop scroll requires explicit x/y.
                # With a keyboard-style scroll gesture use its real foreground
                # window route, never invented screen coordinates.
                args.update(scope="window", pid=target["pid"], window_id=target["windowId"])
            if target["windowId"] != foreground:
                raise DesktopError("Scroll requires the shown foreground window", 409)
            name = "scroll"
            args.update(direction=event["direction"], amount=event["amount"], by="line")
        self.adapter.driver.validate_call(name, args)
        return name, args

    def _input(self, session, envelope, permission):
        operation = envelope.get("operationId")
        attempted = False
        reply_observed = False
        receipt = None
        with self.adapter.lock:
            try:
                self._current(session, permission)
                if session.uncertain:
                    raise DesktopError("Prior human input is unconfirmed; no further dispatch is allowed", 409)
                with self.manager.lock:
                    self.adapter._lease(session.resource, session.owner, envelope.arguments["controlLease"], {"HELD"})
                frame = self.streams.validate_input(session.stream_id, session.owner, session.binding, envelope.arguments["frameRef"], lambda: self._current(session, permission))
                if any(frame["observedHealth"].get(key) != frame["healthBinding"].get(key)
                       for key in ("lastInputTick", "foregroundWindowId")):
                    raise DesktopError("Local input or foreground changed since the displayed frame; wait for a current frame", 409)
                name, args = self._input_call(session, envelope.arguments["input"], frame)
                # Capture/driver preflight can block. Recheck the original frame,
                # focus, local input and process identity immediately before input.
                checked = self.streams.validate_input(session.stream_id, session.owner, session.binding, envelope.arguments["frameRef"], lambda: self._current(session, permission))
                if checked["healthBinding"] != frame["healthBinding"]:
                    raise DesktopError("Desktop focus, local input or geometry changed during input preflight", 409)
                self._current(session, permission)
                with self.manager.lock:
                    self.adapter._lease(session.resource, session.owner, envelope.arguments["controlLease"], {"HELD"})
                    self._record(operation, "dispatching", inputDispatchAttempted=True, leaseFence=envelope.arguments["controlLease"]["fence"])
                    self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='EXECUTING' WHERE resource_key=?", (session.resource,))
                    self.manager.ledger.commit()
                    attempted = True
                response = self.adapter.driver.call(name, args)  # Exactly one original input call.
                reply_observed = True
                outcome = input_outcome(response)
                self._current(session, permission)
                confirmed = outcome.get("inputDispatched") is True
                settled = outcome.get("inputDispatched") is not None
                receipt = {"status": "completed" if settled else "unknown", "inputDispatched": confirmed,
                           "dispatchConfirmed": confirmed, "inputDispatchAttempted": True,
                           "remoteEffectsVerified": False}
            except Exception:
                if not attempted:
                    raise
                receipt = {"status": "unknown", "inputDispatched": False, "dispatchConfirmed": False,
                           "inputDispatchAttempted": True, "remoteEffectsVerified": False}
            finally:
                if attempted:
                    # Retire both model observations and viewer refs, including
                    # captures that began before this input/control revision.
                    try:
                        self._invalidate(session)
                    except Exception:
                        self.adapter.invalidate_observations()
                    session.uncertain = receipt is None or receipt["status"] != "completed" or not receipt["inputDispatched"]
                    with self.manager.lock:
                        unknown = receipt is None or receipt["status"] == "unknown"
                        self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=? WHERE resource_key=? AND owner=? AND instance_id=? AND lease_id=? AND fence=? AND state='EXECUTING'",
                                                    ("NEEDS_RECONCILE" if unknown else "HELD", session.resource, session.owner, self.adapter.instance_id,
                                                     envelope.arguments["controlLease"]["id"], envelope.arguments["controlLease"]["fence"]))
                        self._record(operation, "unknown" if unknown else "completed", inputDispatchAttempted=True,
                                     inputReceipt=receipt, executionSettlement={"noFurtherDispatchForOperation": True,
                                                                               "driverReplyObserved": reply_observed,
                                                                               "osInputQueueDrained": False})
                        self.manager.ledger.commit()
                    if session.closed:
                        self._retire_lease(session)
        return {**self._view(session, envelope), "inputReceipt": receipt}

    def _retire_lease(self, session):
        # Caller holds adapter.lock. Only this exact session's safe HELD lease is
        # releasable; global unknown/EXECUTING/PAUSED fencing remains untouched.
        with self.manager.lock:
            row = self._lease_row(session)
            if row and row[0] == session.owner and row[1] == self.adapter.instance_id and row[5] == "HELD":
                self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='RELEASED',fence=fence+1 WHERE resource_key=?", (session.resource,))
                self.manager.ledger.commit()
        session.lease = None
        session.cleanup_pending = False
        self.adapter.invalidate_observations()

    def _stream_ended(self, stream_id, owner):
        session = next((item for item in list(self.sessions.values()) if item.owner == owner and item.stream_id == stream_id), None)
        if session is None:
            return
        session.closed = True
        session.cleanup_pending = True
        if self.adapter.lock.acquire(blocking=False):
            try:
                self._retire_lease(session)
            finally:
                self.adapter.lock.release()

    def cleanup(self):
        """Original local-stop watchdog retries bounded safe-lease retirement.

        Media callbacks cannot block on a capture/input worker. A missed lock
        therefore remains pending here; unknown/executing states are untouched.
        """
        if self.manager.closed or not self.adapter.lock.acquire(blocking=False):
            return
        try:
            for session in list(self.sessions.values()):
                if session.closed and session.cleanup_pending:
                    self._retire_lease(session)
        finally:
            self.adapter.lock.release()

    def invoke(self, envelope, permission):
        DesktopJobs._permission(envelope, permission)
        if (envelope.method != "desktop.viewer" or envelope.get("capabilityVersion") != "desktop-v2"
                or envelope.get("originDeviceId") != envelope.get("callerDeviceId") or envelope.get("delegationChain", [])):
            raise DesktopError("Desktop viewing requires the directly authenticated original owner", 403)
        permission.check_current(self.adapter.clock())
        audience = self._audience(envelope, permission)
        action = envelope.arguments["action"]
        with self.lock:
            # Configuration's prior-session lookup precedes its first operation claim.
            session = self._configuration(envelope, permission, audience) if action == "configuration" else self._session(envelope, permission, audience)
            cached = self._claim(envelope, audience, permission)
            if cached is not None:
                return cached
            try:
                if action == "configuration":
                    result = self._view(session, envelope)
                elif action == "negotiate":
                    if session.stream_id is not None:
                        raise DesktopError("This native viewer already negotiated; close it before opening another", 409)
                    opened = self.streams.open(session.binding, session.target, session.owner, envelope.arguments["offer"],
                                               min(session.expires, envelope.get("deadline")), lambda: self._current(session))
                    session.stream_id = opened["streamId"]
                    result = {**self._view(session, envelope), "answer": opened["answer"]}
                elif action == "status":
                    if session.stream_id:
                        status = self.streams.status(session.stream_id, session.owner, session.binding, lambda: self._current(session))
                        if status.get("lastFrame"):
                            session.width, session.height = status["lastFrame"]["width"], status["lastFrame"]["height"]
                    result = self._view(session, envelope)
                elif action == "take":
                    result = self._take(session, envelope, permission)
                elif action == "release":
                    result = self._release(session, envelope, permission)
                elif action == "input":
                    result = self._input(session, envelope, permission)
                elif action == "close":
                    session.closed = True
                    session.cleanup_pending = True
                    if session.stream_id:
                        self.streams.close(session.stream_id, session.owner, session.binding, lambda: self._current(session, permission, closing=True))
                    with self.adapter.lock:
                        self._retire_lease(session)
                    result = {"viewerSessionId": session.id, "operationId": envelope.get("operationId"), "status": "closed"}
                else:
                    raise DesktopError("Unknown desktop viewer action", 400)
                with self.manager.lock:
                    self._record(envelope.get("operationId"), "unknown" if result.get("inputReceipt", {}).get("status") == "unknown" else "completed")
                    self.manager.ledger.commit()
                self.replies[envelope.get("operationId")] = copy.deepcopy(result)
                while len(self.replies) > 128:
                    self.replies.popitem(last=False)
                return result
            except Exception:
                with self.manager.lock:
                    self._record(envelope.get("operationId"), "unknown", noAutomaticReplay=True)
                    self.manager.ledger.commit()
                raise

    def close(self):
        self.closed = True
        for session in list(self.sessions.values()):
            session.closed = True
            session.cleanup_pending = True
        proof = self.streams.close_all()
        if self.adapter.lock.acquire(blocking=False):
            try:
                for session in list(self.sessions.values()):
                    self._retire_lease(session)
            finally:
                self.adapter.lock.release()
        return proof
