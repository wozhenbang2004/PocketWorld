"""Owner-only desktop media and input on the original paired-device transport.

The video channel contains pixels and frame metadata only. Every input crosses
the existing mTLS / PairGrant gate and is durably claimed before native dispatch.
This is an owner page session, not an Agent task or another task database.
"""
from __future__ import annotations

import copy
import hashlib
import json
import threading

import device_protocol as protocol
from device_pairing import Permission
from desktop_stream import DesktopStreamService, _resource
from platform_backend import PlatformError


class DesktopViewer:
    def __init__(self, receiver, *, stream=None):
        self.receiver, self.manager = receiver, receiver.manager
        self.adapter = getattr(getattr(receiver.desktop, "jobs", None), "adapter", None)
        self.lock = threading.RLock()
        self.sessions = {}
        self.closed = False
        self.stop = threading.Event()
        self.worker = None
        settings = self.manager.config.get("desktop", {}).get("streaming", {})
        self.reason = None
        if not isinstance(settings, dict) or set(settings) - {"enabled", "iceServers"}:
            self.reason = "invalid_desktop_stream_configuration"
        elif settings.get("enabled", True) is not True:
            self.reason = "desktop_stream_explicitly_disabled"
        self.stream = stream
        if self.stream is None and self.adapter is not None and self.reason is None:
            try:
                self.stream = DesktopStreamService(self.adapter.health, ice_servers=settings.get("iceServers", ()), clock=self.manager.now)
            except (ValueError, PlatformError):
                self.reason = "invalid_desktop_stream_configuration"
        with self.manager.lock:
            self.manager.ledger.execute("CREATE TABLE IF NOT EXISTS desktop_viewer_operations(operation_id TEXT PRIMARY KEY,digest TEXT NOT NULL,owner TEXT NOT NULL,session_id TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,deadline INTEGER NOT NULL,receipt TEXT)")
            self.manager.ledger.commit()

    def describe(self):
        if self.reason or self.adapter is None or self.stream is None:
            return {"available": False, "reason": self.reason or "native_desktop_unavailable", "transport": "webrtc"}
        return {**self.stream.describe(), "method": "desktop.viewer", "requiresExplicitGrant": True,
                "inputTransport": "original_paired_mutual_tls", "automaticInputReplay": False}

    def _current(self, permission):
        self.receiver._current(permission)
        if self.closed:
            raise PlatformError("Desktop viewer is closed", 409)

    @staticmethod
    def _owner(envelope):
        return ":".join(str(envelope.get(key)) for key in ("callerDeviceId", "grantId", "authorizationEpoch"))

    def _start_watchdog(self):
        if self.worker is not None:
            return
        def run():
            while not self.stop.wait(1):
                with self.lock:
                    for session in list(self.sessions.values()):
                        if session["closed"]:
                            continue
                        try:
                            session["current"]()
                            if session.get("streamId"):
                                status = self.stream.status(session["streamId"], session["owner"], session["binding"], session["current"])
                                if status["state"] in {"closed", "failed", "expired"}:
                                    self._close_session(session)
                        except Exception:
                            self._close_session(session)
        self.worker = threading.Thread(target=run, name="pocket-desktop-owner-lifecycle", daemon=True)
        self.worker.start()

    def _close_session(self, session):
        # This path only reduces authority. It can run after grant expiry or
        # page loss, and never converts an unknown native input into success.
        session["closed"] = True
        if session.get("lease"):
            try:
                # The adapter distinguishes an idle HUMAN lease from a native
                # HUMAN_EXECUTING/NEEDS_RECONCILE state. A lost media/status
                # receipt alone must not strand a known idle control lease.
                self.adapter.release_human(session["binding"], session["caller"], session["streamId"], session["lease"], lambda: None)
                session["lease"] = None
                session["revision"] += 1
            except Exception:
                session["unknown"] = True
        if session.get("streamId"):
            try:
                self.stream.close(session["streamId"], session["owner"], session["binding"], lambda: None)
            except Exception:
                pass  # Media also has its own bounded expiry watchdog.

    def _session(self, envelope, permission, *, create=False):
        args, now = envelope.arguments, self.manager.now()
        key = (self._owner(envelope), args["viewerSessionId"])
        session = self.sessions.get(key)
        if session is None:
            if not create:
                raise PlatformError("Desktop owner session is absent; open it explicitly", 409)
            # Expired original sessions cannot be resurrected with a new op ID.
            self.sessions = {key: item for key, item in self.sessions.items() if item["expiresAt"] + 60000 > now}
            if len(self.sessions) >= 32 or sum(not item["closed"] for item in self.sessions.values()) >= 2:
                raise PlatformError("Desktop viewer session limit reached", 409)
            binding, target = envelope.get("resourceBinding"), args.get("target")
            if target is None and "windowId" in binding:
                target = self.adapter.health.resolve_target(binding["windowId"])
            health = self.adapter.health.probe(target.get("pid") if isinstance(target, dict) else None,
                                              target.get("windowId") if isinstance(target, dict) else None)
            _, bounds, _ = _resource(binding, target, health)
            expires = min(envelope.get("deadline"), permission.grant_expires_at, now + 300000)
            session = {"id": args["viewerSessionId"], "owner": key[0], "caller": envelope.get("callerDeviceId"),
                       "binding": copy.deepcopy(binding), "target": copy.deepcopy(target), "expiresAt": expires,
                       "width": bounds[2] - bounds[0], "height": bounds[3] - bounds[1], "streamId": None,
                       "lease": None, "revision": 0, "unknown": False, "closed": False,
                       "current": lambda: self._current(permission)}
            self.sessions[key] = session
            self._start_watchdog()
        if (session["closed"] or session["expiresAt"] <= now
                or not protocol.resource_equal(session["binding"], envelope.get("resourceBinding"))
                or "target" in args and args["target"] != session["target"]):
            raise PlatformError("Original desktop owner session expired or changed", 409)
        if "streamId" in args and args["streamId"] != session["streamId"]:
            raise PlatformError("Desktop stream identity changed", 409)
        session["current"]()
        return session

    @staticmethod
    def _control(session):
        result = {"owner": "human" if session.get("lease") else "none", "revision": session["revision"]}
        if session.get("lease"):
            result["lease"] = copy.deepcopy(session["lease"])
        if session.get("unknown"):
            result["needsReconcile"] = True
        return result

    def _view(self, session):
        result = {"viewerSessionId": session["id"], "expiresAt": session["expiresAt"], "width": session["width"],
                  "height": session["height"], "control": self._control(session), "state": "closed" if session["closed"] else "ready"}
        if session.get("streamId"):
            result["streamId"] = session["streamId"]
        return result

    def _claim(self, envelope, permission):
        operation, digest, now = envelope.get("operationId"), protocol.arguments_digest(envelope.json()), self.manager.now()
        with self.manager.lock:
            database = self.manager.ledger
            row = database.execute("SELECT digest,owner,state,receipt FROM desktop_viewer_operations WHERE operation_id=?", (operation,)).fetchone()
            if row:
                if row[0] != digest or row[1] != self._owner(envelope):
                    raise PlatformError("Desktop viewer operation identity was reused", 409)
                return json.loads(row[3]) if row[3] else {"viewerSessionId": envelope.arguments["viewerSessionId"], "operationId": operation,
                    "status": "unknown", "noAutomaticReplay": True, "message": "Original viewer request was already claimed; reopen explicitly"}
            database.execute("SAVEPOINT desktop_viewer_claim")
            try:
                database.execute("DELETE FROM desktop_viewer_operations WHERE deadline < ?", (now - 86400000,))
                if database.execute("SELECT count(*) FROM desktop_viewer_operations").fetchone()[0] >= 16384:
                    raise PlatformError("Desktop owner receipt limit reached", 409)
                if envelope.arguments["action"] == "input":
                    self._spend_input(envelope, permission, database)
                database.execute("INSERT INTO desktop_viewer_operations VALUES (?,?,?,?,?,?,?,NULL)",
                                 (operation, digest, self._owner(envelope), envelope.arguments["viewerSessionId"], envelope.arguments["action"], "claimed", envelope.get("deadline")))
                database.execute("RELEASE SAVEPOINT desktop_viewer_claim")
                database.commit()
            except BaseException:
                database.execute("ROLLBACK TO SAVEPOINT desktop_viewer_claim")
                database.execute("RELEASE SAVEPOINT desktop_viewer_claim")
                raise
        return None

    def _spend_input(self, envelope, permission, database):
        # The same finite owner grant pool is shared with Agent tools. A viewer,
        # new op/session/root/run/epoch cannot refill it. No fake task allocation.
        grant = permission.grant
        parts = [grant["peerDeviceId"], grant["localDeviceId"], grant["grantId"]]
        key = hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        row = database.execute("SELECT tool_limit,tool_spent FROM device_budget_pools WHERE pool_key=?", (key,)).fetchone()
        if row is None:
            database.execute("INSERT INTO device_budget_pools VALUES (?,?,?,?,?,?,?)", (key, *parts, permission.tool_budget, 0, self.manager.now()))
            row = (permission.tool_budget, 0)
        if row[1] >= min(row[0], permission.tool_budget):
            raise PlatformError("Original owner grant input/tool budget is exhausted", 409)
        database.execute("UPDATE device_budget_pools SET tool_spent=tool_spent+1 WHERE pool_key=?", (key,))

    def _persist(self, envelope, state, result=None):
        safe = copy.deepcopy(result) if result else None
        # Never persist ICE passwords, SDP or user-entered text in diagnostics.
        if safe:
            for field in ("answer", "iceServers", "input"):
                safe.pop(field, None)
            if envelope.arguments["action"] in {"configuration", "negotiate"}:
                safe = {"viewerSessionId": envelope.arguments["viewerSessionId"], "operationId": envelope.get("operationId"),
                        "status": "unknown", "noAutomaticReplay": True, "message": "Signalling was already claimed; reopen explicitly"}
        with self.manager.lock:
            self.manager.ledger.execute("UPDATE desktop_viewer_operations SET state=?,receipt=? WHERE operation_id=?",
                                       (state, protocol.canonical_bytes(safe).decode() if safe else None, envelope.get("operationId")))
            self.manager.ledger.commit()

    def effect_unknown(self, envelope):
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT digest,state FROM desktop_viewer_operations WHERE operation_id=?", (envelope.get("operationId"),)).fetchone()
            # A final permission/transport check may fail after a completed OS
            # effect. A matching committed row cannot become "not dispatched".
            return row is not None and row[0] == protocol.arguments_digest(envelope.json()) and row[1] != "failed"

    def invoke(self, envelope, permission):
        if not isinstance(permission, Permission) or permission.envelope.json() != envelope.json() or envelope.method != "desktop.viewer":
            raise PermissionError("Original owner-only native viewer permission required")
        self._current(permission)
        if not self.describe().get("available"):
            raise PlatformError("Desktop streaming dependencies or native desktop are unavailable", 409)
        args, action = envelope.arguments, envelope.arguments["action"]
        with self.lock:
            previous = self._claim(envelope, permission)
            if previous is not None:
                return previous
            dispatched = False
            session = None
            def marker():
                nonlocal dispatched
                self._current(permission)
                self._persist(envelope, "dispatching")
                dispatched = True
            try:
                session = self._session(envelope, permission, create=action == "configuration")
                current = lambda: (self._current(permission), session["current"]())
                stream_id, binding, owner = session["streamId"], session["binding"], session["owner"]
                result = {}
                if action == "configuration":
                    result.update(self.stream.client_configuration())
                elif action == "negotiate":
                    if stream_id is not None:
                        raise PlatformError("Desktop stream was already negotiated; reopen explicitly", 409)
                    marker()
                    opened = self.stream.open(binding, session["target"], owner, args.get("offer"), session["expiresAt"], session["current"])
                    session["streamId"] = opened["streamId"]
                    result.update(opened)
                elif action == "close":
                    marker()
                    self._close_session(session)
                elif stream_id is None:
                    raise PlatformError("Desktop stream is not negotiated", 409)
                elif action == "status":
                    status = self.stream.status(stream_id, owner, binding, current)
                    result.update(status)
                    if status.get("lastFrame"):
                        result["frameRef"] = status["lastFrame"]
                    if status["state"] in {"closed", "failed", "expired"}:
                        self._close_session(session)
                elif action in {"take", "release"}:
                    if args.get("expectedRevision") != session["revision"] or session["unknown"]:
                        raise PlatformError("Desktop control changed or requires reconciliation", 409)
                    if action == "take":
                        marker()
                        receipt = self.adapter.acquire_human(binding, session["caller"], stream_id, current)
                        session["lease"] = receipt["lease"]
                    else:
                        if session["lease"] is None:
                            raise PlatformError("This viewer does not hold human control", 409)
                        marker()
                        self.adapter.release_human(binding, session["caller"], stream_id, session["lease"], current)
                        session["lease"] = None
                    session["revision"] += 1
                    self.stream.invalidate_inputs(stream_id, owner, binding, current)
                elif action == "input":
                    if session["unknown"] or session["lease"] is None or not protocol.resource_equal(session["lease"], args.get("controlLease", {})):
                        raise PlatformError("Original human-control lease required", 409)
                    geometry = self.stream.validate_input(stream_id, owner, binding, args.get("frameRef"), current)
                    receipt = self.adapter.human_act(binding, session["caller"], stream_id, session["lease"], args.get("input"), geometry, current, marker)
                    result["inputReceipt"] = receipt
                    if receipt.get("status") == "unknown":
                        session["unknown"] = True
                    self.stream.invalidate_inputs(stream_id, owner, binding, current)
                else:
                    raise PlatformError("Unknown desktop viewer action", 400)
                self._current(permission)
                # Native session identity wins over media internals; stream
                # state is retained separately and never grants input authority.
                result = {**self._view(session), **result, "viewerSessionId": session["id"],
                          "operationId": envelope.get("operationId"), "control": self._control(session),
                          "status": result.get("inputReceipt", {}).get("status", "completed")}
                self._persist(envelope, result["status"], result)
                return result
            except Exception:
                status = "unknown" if dispatched else "failed"
                if dispatched and session is not None and action in {"input", "take", "release"}:
                    session["unknown"] = True
                result = {"viewerSessionId": args["viewerSessionId"], "operationId": envelope.get("operationId"), "status": status,
                          "noAutomaticReplay": True, "message": "Desktop request could not be confirmed; inspect the current desktop before another action"}
                if action == "input":
                    result["inputReceipt"] = {"status": status, "inputDispatched": None if dispatched else False, "noAutomaticReplay": True, "userGoalVerified": False}
                if session is not None:
                    result.update(self._view(session))
                self._persist(envelope, status, result)
                return result

    def close(self):
        self.stop.set()
        with self.lock:
            for session in self.sessions.values():
                self._close_session(session)
            self.closed = True
        if self.stream is not None:
            self.stream.close_all()
        if self.worker is not None and self.worker is not threading.current_thread():
            self.worker.join(timeout=2)
