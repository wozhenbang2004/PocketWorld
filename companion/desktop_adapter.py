"""Observe -> one fenced action -> observe on the original Companion.

This is deterministic tool execution, not an Agent. Input dispatch is never a
business-success receipt. Durable action identities belong to DesktopJobs and
the original operations/jobs ledger; this class only adds resource leases.
"""
from __future__ import annotations

import base64
import binascii
import copy
from decimal import Decimal
import hashlib
import io
import json
import re
import struct
import threading
import time
import uuid

from cua_desktop_adapter import CuaError, CAPABILITY_VERSION, input_outcome
from platform_backend import PlatformError
from device_control_projection import project
from device_protocol import resource_equal


class DesktopError(PlatformError):
    pass


def _integer(value, low, high, field):
    if type(value) is not int or not low <= value <= high:
        raise DesktopError("Invalid " + field, 400)
    return value


def _coordinate(value, dimension, field):
    if type(value) is int:
        if not 0 <= value < dimension:
            raise DesktopError("Coordinate is outside the exact observed image: " + field, 400)
        return value
    # Shared device-v1 decimal text, explicitly converted to Driver's f64
    # coordinate contract, never silently truncated to an integer.
    if not isinstance(value, str) or len(value) > 32 or not re.fullmatch(r"(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?", value):
        raise DesktopError("Coordinate must be an integer or bounded decimal string: " + field, 400)
    number = Decimal(value)
    if not 0 <= number < dimension:
        raise DesktopError("Coordinate is outside the exact observed image: " + field, 400)
    return float(number)


def _wire(value):
    # Device v1 deliberately forbids floating-point ambiguity. Driver decimals
    # remain exact decimal TEXT rather than being silently rounded.
    if isinstance(value, float):
        return str(value)
    if type(value) is int and abs(value) > 9007199254740991:
        return str(value)
    if isinstance(value, list):
        return [_wire(item) for item in value]
    if isinstance(value, dict):
        return {key: _wire(item) for key, item in value.items()}
    return value


class DesktopAdapter:
    def __init__(self, manager, driver, health, capture_store, source_device_id, *, clock=None):
        if not callable(capture_store):
            raise DesktopError("A permission-bound, expiring artifact store is required for desktop captures")
        self.manager, self.driver, self.health = manager, driver, health
        self.instance_id = manager.instance_id
        self.capture_store, self.source_device_id = capture_store, source_device_id
        self.clock = clock if clock is not None else lambda: int(time.time() * 1000)
        self.generation = uuid.uuid4().hex
        self.lock = threading.RLock()
        self.snapshots = {}
        self.refusals = {}  # Bounded process-local evidence, not task authority/retry queue.
        self.paused = False
        self.stop_marker = manager.state / "desktop-local-stop"
        worker_binding = driver.recovery_binding() if callable(getattr(driver, "recovery_binding", None)) else {"format": "unavailable", "reason": "no_native_worker_binding"}
        with manager.lock:
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS desktop_resource_leases(resource_key TEXT PRIMARY KEY, owner TEXT NOT NULL, instance_id TEXT NOT NULL, lease_id TEXT NOT NULL, fence INTEGER NOT NULL, expires INTEGER NOT NULL, state TEXT NOT NULL)")
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS desktop_human_controls(resource_key TEXT PRIMARY KEY, stream_id TEXT NOT NULL, lease_id TEXT NOT NULL, binding_json TEXT NOT NULL, point_json TEXT)")
            # No guessing that an old executor stopped, and no expiration-based
            # handover after restart. A real local recovery review is required.
            manager.ledger.execute("UPDATE desktop_resource_leases SET state='NEEDS_RECONCILE' WHERE state!='RELEASED' AND instance_id!=?", (manager.instance_id,))
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS desktop_worker_instances(instance_id TEXT PRIMARY KEY, local_device_id TEXT NOT NULL, binding_json TEXT NOT NULL, cleanup_proof TEXT)")
            manager.ledger.execute("INSERT OR IGNORE INTO desktop_worker_instances VALUES (?,?,?,NULL)", (manager.instance_id, source_device_id, json.dumps(worker_binding)))
            manager.ledger.commit()
        self._watchdog_stop = threading.Event()
        self._watchdog = threading.Thread(target=self._watch_local_stop, name="pocket-desktop-local-stop", daemon=True)
        self._watchdog.start()

    def _watch_local_stop(self):
        while not self._watchdog_stop.wait(0.05):
            if self.stop_marker.exists():
                self.local_stop()
                return

    def local_stop(self):
        # Does not need a phone, token or internet connection. Already queued OS
        # input cannot be retracted; interrupted original actions remain unknown.
        self.paused = True
        proof = self.driver.stop()
        with self.manager.lock:
            self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='PAUSED',fence=fence+1 WHERE instance_id=? AND state!='RELEASED'", (self.instance_id,))
            self.manager.ledger.execute("UPDATE desktop_worker_instances SET cleanup_proof=? WHERE instance_id=?", (json.dumps(proof), self.instance_id))
            self.manager.ledger.commit()
        return {"paused": True, "driverCleanupProof": proof, "priorEffectsVerified": False,
                "message": "Local stop takes priority; fresh observation and a new owner-reviewed lease are required"}

    def close(self):
        self._watchdog_stop.set()
        return self.local_stop()

    def _current(self, current):
        current()
        if self.manager.instance_id != self.instance_id:
            raise DesktopError("Original desktop supervisor generation changed; old adapter cannot resume")
        if self.paused or self.stop_marker.exists():
            self.paused = True
            raise DesktopError("Computer input is paused by the local user; no automatic resume", 409)

    def _health(self, target=None):
        target = target or {}
        health = self.health.probe(target.get("pid"), target.get("windowId"))
        if not health.get("available"):
            raise DesktopError("Desktop unavailable: " + health.get("reason", "unknown"), 409)
        if "window" in health:
            # Fixed Cua desktop capture is primary-only. Do not mislabel a
            # secondary monitor or mixed-DPI spanning window as that display.
            primary = next((item for item in health["displays"] if item["primary"]), None)
            bounds = health["window"]["bounds"]
            if primary is None or not (primary["bounds"][0] <= bounds[0] < bounds[2] <= primary["bounds"][2]
                                       and primary["bounds"][1] <= bounds[1] < bounds[3] <= primary["bounds"][3]):
                raise DesktopError("Window is not wholly on the supported primary display; move it locally then observe again")
        return health

    def _scope(self, binding, health, target=None):
        if not isinstance(binding, dict) or set(binding) not in ({"kind", "sessionId", "displayId"}, {"kind", "sessionId", "displayId", "windowId"}):
            raise DesktopError("An exact owner-granted desktop resourceBinding is required", 403)
        if binding.get("kind") != "desktop" or binding.get("displayId") != "primary" or type(binding.get("sessionId")) is not int or binding["sessionId"] != health["sessionId"]:
            raise DesktopError("Desktop resource no longer matches this interactive session/display", 403)
        if "windowId" in binding and (type(binding["windowId"]) is not int or not 1 <= binding["windowId"] <= 9007199254740991 or (target or {}).get("windowId") != binding["windowId"]):
            raise DesktopError("Window is outside the exact granted desktop resource", 403)
        return "windows-session:" + str(health["sessionId"]) + ":primary"

    def describe(self):
        health = self.health.probe()
        driver = self.driver.capabilities()
        resource = "windows-session:" + str(health.get("sessionId")) + ":primary"
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT state FROM desktop_resource_leases WHERE resource_key=?", (resource,)).fetchone()
        lease_state = row[0] if row else "RELEASED"
        return {"available": bool(health.get("available")) and driver.get("installed", False) and not driver.get("lifecycleBroken", False) and not self.paused and not self.stop_marker.exists() and lease_state not in {"NEEDS_RECONCILE", "PAUSED"},
                "health": _wire(health), "driver": driver, "capabilityVersion": CAPABILITY_VERSION, "resourceLeaseState": lease_state,
                "observationModes": ["semantic", "visual", "hybrid"], "defaultWindowObservationMode": "semantic",
                "humanControl": {"available": lease_state not in {"NEEDS_RECONCILE", "PAUSED"}, "active": lease_state in {"HUMAN", "HUMAN_EXECUTING"},
                                 "ownerOnly": True, "streamBound": True, "leaseMaximumMs": 300000, "automaticResume": False},
                "deliveryModes": {"default": "background", "foreground": "explicit_new_operation_after_exact_known_pre_dispatch_refusal"},
                "runtimeProbeRequired": driver.get("runtimeContract") != "verified_against_pinned_process",
                "localStop": True, "resumeAutomatic": False, "actionResultRequiresBusinessVerification": True,
                "scope": {"kind": "desktop", "sessionId": health.get("sessionId"), "displayId": "primary"},
                "limitations": ["primary display only; secondary/spanning windows refused", "Session 0, lock screen, UAC/secure desktop refused",
                                "drag and automatic human-intent detection are not claimed", "unknown/in-flight leases require reconciliation, not expiry-based takeover"]}

    def control(self, action, binding, owner, arguments, current):
        self._current(current)
        health = self._health()
        # A single-window grant can acquire the display lease without widening
        # its permitted action window; the actual window stays in binding.
        resource = self._scope(binding, health, {"windowId": binding.get("windowId")})
        with self.lock, self.manager.lock:
            self._current(current)
            row = self.manager.ledger.execute("SELECT owner,instance_id,lease_id,fence,expires,state FROM desktop_resource_leases WHERE resource_key=?", (resource,)).fetchone()
            if action == "acquire":
                if set(arguments) - {"action", "durationMs"}:
                    raise DesktopError("Unknown desktop control field", 400)
                duration = _integer(arguments.get("durationMs", 30000), 1000, 30000, "durationMs")
                if row and row[5] != "RELEASED":
                    # Expiry is not proof that a dispatched input stopped.
                    raise DesktopError("Desktop resource is busy or requires reconciliation; expiry does not transfer execution", 409)
                fence, lease = (row[3] + 1 if row else 1), uuid.uuid4().hex
                expires = self.clock() + duration
                self.manager.ledger.execute("INSERT OR REPLACE INTO desktop_resource_leases VALUES (?,?,?,?,?,?,?)", (resource, owner, self.instance_id, lease, fence, expires, "HELD"))
                self.manager.ledger.commit()
                return {"status": "completed", "lease": {"id": lease, "fence": fence, "expiresAt": expires, "resource": resource}, "remoteEffectsVerified": False}
            if action == "release":
                if set(arguments) != {"action", "lease"}:
                    raise DesktopError("release requires the exact lease", 400)
                self._lease(resource, owner, arguments["lease"], {"HELD"}, allow_expired=True)
                self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='RELEASED',fence=fence+1 WHERE resource_key=?", (resource,))
                self.manager.ledger.commit()
                return {"status": "completed", "released": True, "priorEffectsVerified": False}
            raise DesktopError("Unknown desktop control action; local stop cannot be remotely cleared", 400)

    def _lease(self, resource, owner, lease, states, allow_expired=False):
        if not isinstance(lease, dict) or set(lease) != {"id", "fence", "expiresAt", "resource"}:
            raise DesktopError("Exact desktop input lease is required", 403)
        if not isinstance(lease["id"], str) or not re.fullmatch(r"[a-f0-9]{32}", lease["id"]) or type(lease["fence"]) is not int or type(lease["expiresAt"]) is not int:
            raise DesktopError("Invalid desktop lease identity/fencing type", 403)
        row = self.manager.ledger.execute("SELECT owner,instance_id,lease_id,fence,expires,state FROM desktop_resource_leases WHERE resource_key=?", (resource,)).fetchone()
        if not row or row[0] != owner or row[1] != self.instance_id or row[2] != lease["id"] or row[3] != lease["fence"] or row[4] != lease["expiresAt"] or lease["resource"] != resource or row[5] not in states or not allow_expired and row[4] <= self.clock():
            raise DesktopError("Desktop lease is stale, expired, busy, fenced or owned by another executor", 409)
        return row

    @staticmethod
    def _stream_id(stream_id):
        if not isinstance(stream_id, str) or not re.fullmatch(r"[a-f0-9]{32}", stream_id):
            raise DesktopError("Exact original desktop viewer stream is required", 400)

    def _human_lease(self, resource, owner, stream_id, lease, binding, *, allow_expired=False):
        self._stream_id(stream_id)
        if not isinstance(lease, dict) or set(lease) != {"id", "fence", "expiresAt", "resource", "streamId"} or lease["streamId"] != stream_id:
            raise DesktopError("Exact stream-bound human control lease is required", 403)
        row = self._lease(resource, owner, {key: value for key, value in lease.items() if key != "streamId"}, {"HUMAN"}, allow_expired=allow_expired)
        human = self.manager.ledger.execute("SELECT stream_id,lease_id,binding_json,point_json FROM desktop_human_controls WHERE resource_key=?", (resource,)).fetchone()
        if not human or human[:2] != (stream_id, lease["id"]) or not resource_equal(json.loads(human[2]), binding):
            raise DesktopError("Human control belongs to another stream or original resource grant", 403)
        return row, json.loads(human[3]) if human[3] else None

    def acquire_human(self, binding, owner, stream_id, current):
        """Explicit viewer takeover, serialized behind the original AI action.

        Expiry never proves an unknown executor stopped. Only this adapter's
        idle HELD lease can be fenced; an in-flight action owns this same lock.
        """
        self._stream_id(stream_id)
        with self.lock, self.manager.lock:
            self._current(current)
            health = self._health()
            resource = self._scope(binding, health, {"windowId": binding.get("windowId")})
            row = self.manager.ledger.execute("SELECT owner,instance_id,lease_id,fence,expires,state FROM desktop_resource_leases WHERE resource_key=?", (resource,)).fetchone()
            if row and row[5] != "RELEASED":
                if row[0] != owner or row[1] != self.instance_id or row[5] not in {"HELD", "HUMAN"}:
                    raise DesktopError("Original desktop input is busy, paused, unknown or owned by another executor; takeover cannot force recovery", 409)
                if row[5] == "HUMAN":
                    human = self.manager.ledger.execute("SELECT stream_id,binding_json FROM desktop_human_controls WHERE resource_key=?", (resource,)).fetchone()
                    if not human or human[0] != stream_id or not resource_equal(json.loads(human[1]), binding):
                        raise DesktopError("Another viewer holds human control; release that original view first", 409)
            fence, identifier, expires = (row[3] + 1 if row else 1), uuid.uuid4().hex, self.clock() + 300000
            self._current(current)
            self.manager.ledger.execute("INSERT OR REPLACE INTO desktop_resource_leases VALUES (?,?,?,?,?,?,?)", (resource, owner, self.instance_id, identifier, fence, expires, "HUMAN"))
            self.manager.ledger.execute("INSERT OR REPLACE INTO desktop_human_controls VALUES (?,?,?,?,NULL)", (resource, stream_id, identifier, json.dumps(binding, sort_keys=True)))
            self.manager.ledger.commit()
            self.snapshots.clear(); self.refusals.clear()
            return {"status": "completed", "control": "human", "observationsInvalidated": True,
                    "lease": {"id": identifier, "fence": fence, "expiresAt": expires, "resource": resource, "streamId": stream_id}}

    def release_human(self, binding, owner, stream_id, lease, current):
        with self.lock, self.manager.lock:
            self._current(current)
            # Revoke only the exact durable idle lease, including when lock,
            # sign-out or a closed window makes GUI inspection unavailable.
            # This cannot dispatch input or release EXECUTING/unknown state.
            session = binding.get("sessionId") if isinstance(binding, dict) else None
            target = {"windowId": binding.get("windowId")} if isinstance(binding, dict) else None
            resource = self._scope(binding, {"sessionId": session}, target)
            self._human_lease(resource, owner, stream_id, lease, binding, allow_expired=True)
            self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='RELEASED',fence=fence+1 WHERE resource_key=?", (resource,))
            self.manager.ledger.execute("DELETE FROM desktop_human_controls WHERE resource_key=?", (resource,))
            self.manager.ledger.commit()
            self.snapshots.clear(); self.refusals.clear()
            return {"status": "completed", "control": "agent", "released": True, "requiresFreshObservation": True}

    def _human_geometry(self, binding, geometry, current):
        if not isinstance(geometry, dict) or not isinstance(geometry.get("healthBinding"), dict):
            raise DesktopError("Trusted recent viewer geometry is required", 409)
        now, captured, expires = self.clock(), geometry.get("capturedAt"), geometry.get("expiresAt")
        if type(captured) is not int or type(expires) is not int or not captured <= now < expires <= captured + 2000:
            raise DesktopError("Viewer frame expired before input; wait for a current frame", 409)
        _integer(geometry.get("width"), 1, 1200, "viewer width")
        _integer(geometry.get("height"), 1, 1200, "viewer height")
        bounds, target = geometry.get("captureBounds"), geometry.get("target")
        if not isinstance(bounds, list) or len(bounds) != 4 or any(type(v) is not int for v in bounds) or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]:
            raise DesktopError("Viewer capture bounds are invalid", 409)
        if target is not None and (not isinstance(target, dict) or set(target) != {"pid", "windowId"}
                or type(target["pid"]) is not int or type(target["windowId"]) is not int):
            raise DesktopError("Viewer target is invalid", 409)
        health = self._health(target)
        resource = self._scope(binding, health, target)
        actual_bounds = health["window"]["bounds"] if target else next(item["bounds"] for item in health["displays"] if item["primary"])
        original = geometry["healthBinding"]
        if actual_bounds != bounds or health.get("binding") != original.get("binding") or health.get("lastInputTick") != original.get("lastInputTick"):
            raise DesktopError("Viewer session, window, foreground or input changed before dispatch; use a fresh frame", 409)
        self._current(current)
        return resource, health

    def _human_payload(self, input_value, geometry, point):
        if not isinstance(input_value, dict):
            raise DesktopError("Invalid viewer input", 400)
        kind, target = input_value.get("kind"), geometry["target"]
        fields = {"click": {"kind", "x", "y", "button", "count"}, "scroll": {"kind", "direction", "amount", "by", "x", "y"},
                  "insert_text": {"kind", "text"}, "hotkey": {"kind", "keys"}, "press_key": {"kind", "key"}}
        if kind not in fields or set(input_value) - fields[kind]:
            raise DesktopError("Unsupported viewer input fields", 400)
        payload = {"scope": "window", "pid": target["pid"], "window_id": target["windowId"]} if target else {"scope": "desktop"}
        payload["delivery_mode"] = "foreground"
        if kind == "click":
            if input_value.get("button", "left") not in {"left", "right"}:
                raise DesktopError("Viewer click supports left or right button", 400)
            payload.update(button=input_value.get("button", "left"), count=_integer(input_value.get("count", 1), 1, 2, "count"))
            px = _coordinate(input_value.get("x"), geometry["width"], "x")
            py = _coordinate(input_value.get("y"), geometry["height"], "y")
            _, state, raw = self._capture(target, preflight=True, mode="visual")
            if target:
                box = state.get("window_bounds", {})
                bounds = [box.get("x"), box.get("y"), None, None]
                if all(type(box.get(key)) is int for key in ("x", "y", "width", "height")):
                    bounds[2:] = [box["x"] + box["width"], box["y"] + box["height"]]
                if bounds != geometry["captureBounds"]:
                    raise DesktopError("Native click capture geometry differs from the displayed window", 409)
            capture_id = state.get("capture_id")
            if not isinstance(capture_id, str) or not capture_id:
                raise DesktopError("Native driver did not return a one-use click capture identity", 409)
            dimensions = self._png_dimensions(raw)
            payload.update(x=float(px) * dimensions["width"] / geometry["width"],
                           y=float(py) * dimensions["height"] / geometry["height"], capture_id=capture_id)
            bounds = geometry["captureBounds"]
            next_point = {"bounds": bounds, "target": target, "geometryRevision": geometry.get("geometryRevision"),
                          "point": [bounds[0] + float(px) * (bounds[2] - bounds[0]) / geometry["width"],
                                    bounds[1] + float(py) * (bounds[3] - bounds[1]) / geometry["height"]]}
            return "click", payload, next_point
        if kind == "scroll":
            if input_value.get("direction") not in {"up", "down", "left", "right"} or input_value.get("by", "line") != "line":
                raise DesktopError("Viewer scroll requires an explicit direction and line units", 400)
            payload.update(direction=input_value["direction"], by="line", amount=_integer(input_value.get("amount", 3), 1, 5, "amount"))
            if "x" in input_value or "y" in input_value:
                px = _coordinate(input_value.get("x"), geometry["width"], "x")
                py = _coordinate(input_value.get("y"), geometry["height"], "y")
                bounds = geometry["captureBounds"]
                if target is None:
                    payload.update(x=bounds[0] + float(px) * (bounds[2] - bounds[0]) / geometry["width"],
                                   y=bounds[1] + float(py) * (bounds[3] - bounds[1]) / geometry["height"])
                else:
                    _, state, raw = self._capture(target, preflight=True, mode="visual")
                    box = state.get("window_bounds", {})
                    if (any(type(box.get(key)) is not int for key in ("x", "y", "width", "height"))
                            or [box["x"], box["y"], box["x"] + box["width"], box["y"] + box["height"]] != bounds):
                        raise DesktopError("Native scroll capture geometry differs from the displayed window", 409)
                    dimensions = self._png_dimensions(raw)
                    payload.update(x=float(px) * dimensions["width"] / geometry["width"],
                                   y=float(py) * dimensions["height"] / geometry["height"])
            elif target is None:
                if (not point or point.get("target") is not None or point.get("bounds") != geometry["captureBounds"]
                        or not geometry.get("geometryRevision") or point.get("geometryRevision") != geometry["geometryRevision"]):
                    raise DesktopError("Click the desktop scroll target first, or open a specific window view", 409)
                payload.update(x=point["point"][0], y=point["point"][1])
            return "scroll", payload, point
        if kind == "insert_text":
            text = input_value.get("text")
            if not isinstance(text, str) or not text or len(text.encode("utf-8")) > 12000 or "\x00" in text:
                raise DesktopError("Viewer text must be nonempty, bounded UTF-8", 400)
            payload.update(text=text, delay_ms=0)
            return "type_text", payload, point
        if kind == "press_key":
            key = input_value.get("key")
            if key not in {"enter", "escape", "tab"}:
                raise DesktopError("Viewer single key must be enter, escape or tab", 400)
            payload["key"] = "return" if key == "enter" else key
            return "press_key", payload, point
        keys = input_value.get("keys")
        if (not isinstance(keys, list) or not 2 <= len(keys) <= 5 or any(not isinstance(k, str) for k in keys)
                or any(k.upper() not in {"CTRL", "ALT", "SHIFT", "WIN"} for k in keys[:-1])
                or len({k.upper() for k in keys[:-1]}) != len(keys) - 1
                or not re.fullmatch(r"[A-Za-z0-9]", keys[-1])):
            raise DesktopError("Viewer hotkey requires distinct CTRL/ALT/SHIFT/WIN modifiers and one letter or digit", 400)
        payload["keys"] = [key.lower() for key in keys]
        return "hotkey", payload, point

    def human_act(self, binding, owner, stream_id, lease, input_value, geometry, current, dispatch_marker):
        """Only the owner viewer router can call this, after durable deduplication.

        Geometry proves a recent visible viewport, not identical moving pixels.
        Each call performs exactly one native input; no queued-success receipt.
        """
        with self.lock:
            self._current(current)
            resource, _ = self._human_geometry(binding, geometry, current)
            with self.manager.lock:
                _, point = self._human_lease(resource, owner, stream_id, lease, binding)
            tool, payload, next_point = self._human_payload(input_value, geometry, point)
            admit = getattr(self.driver, "validate_call", None)
            if callable(admit):
                admit(tool, payload)
            self._human_geometry(binding, geometry, current)
            with self.manager.lock:
                self._human_lease(resource, owner, stream_id, lease, binding)
                self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='HUMAN_EXECUTING' WHERE resource_key=?", (resource,))
                self.manager.ledger.commit()
            self.snapshots.clear(); self.refusals.clear()
            dispatched = False
            try:
                self._current(current)
                dispatch_marker()
                dispatched = True
                result = self.driver.call(tool, payload)
                outcome = input_outcome(result)
                self._current(current)
                refused = outcome["kind"] == "refused_pre_dispatch"
                settled = refused or outcome["kind"] == "dispatch_reported" and not result.get("isError")
                status = "failed" if refused else "completed" if settled else "unknown"
                with self.manager.lock:
                    updated = self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=? WHERE resource_key=? AND lease_id=? AND fence=? AND state='HUMAN_EXECUTING'",
                        ("HUMAN" if settled else "NEEDS_RECONCILE", resource, lease["id"], lease["fence"])).rowcount
                    if settled and not refused and next_point is not None:
                        self.manager.ledger.execute("UPDATE desktop_human_controls SET point_json=? WHERE resource_key=? AND lease_id=?", (json.dumps(next_point), resource, lease["id"]))
                    self.manager.ledger.commit()
                if updated != 1:
                    raise DesktopError("Original human lease was fenced during input")
                return {"status": status, "inputDispatched": outcome["inputDispatched"], "inputDispatchAttempted": True,
                        "actionOutcome": outcome, "deliveryMode": "foreground", "control": "human", "streamId": stream_id,
                        "driverResult": self._bounded(result.get("structuredContent", {}), maximum=4096),
                        "verificationScope": "native_input_dispatch_only", "userGoalVerified": False,
                        "businessResultVerified": False, "noAutomaticReplay": True, "observationsInvalidated": True}
            except BaseException:
                with self.manager.lock:
                    self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=? WHERE resource_key=? AND lease_id=? AND fence=? AND state='HUMAN_EXECUTING'",
                        ("NEEDS_RECONCILE" if dispatched else "HUMAN", resource, lease["id"], lease["fence"]))
                    self.manager.ledger.commit()
                if dispatched:
                    return {"status": "unknown", "inputDispatched": None, "inputDispatchAttempted": True,
                            "businessResultVerified": False, "userGoalVerified": False, "noAutomaticReplay": True,
                            "observationsInvalidated": True, "message": "Original human input may have reached the desktop; inspect this operation and do not replay"}
                raise

    def _image(self, result):
        images = [item for item in result.get("content", []) if item.get("type") == "image" and item.get("mimeType") == "image/png"]
        if len(images) != 1 or len(images[0].get("data", "")) > 28 * 1024 * 1024:
            raise DesktopError("Driver did not return exactly one bounded real PNG capture")
        try:
            raw = base64.b64decode(images[0]["data"], validate=True)
        except (ValueError, TypeError) as failure:
            raise DesktopError("Driver PNG encoding is invalid") from failure
        if not raw.startswith(b"\x89PNG\r\n\x1a\n") or len(raw) > 20 * 1024 * 1024:
            raise DesktopError("Driver capture is not a bounded PNG")
        self._png_dimensions(raw)
        return raw

    @staticmethod
    def _png_dimensions(raw):
        # Actual immutable PNG bytes, not tree fields that projection may omit
        # or model-generated text. This validates the container, not business
        # success; the receiving native image decoder still verifies its bytes.
        if not isinstance(raw, bytes) or raw[:8] != b"\x89PNG\r\n\x1a\n":
            raise DesktopError("Capture PNG signature is invalid")
        offset, chunks, dimensions, data_seen = 8, 0, None, False
        while offset + 12 <= len(raw):
            length = struct.unpack(">I", raw[offset:offset + 4])[0]
            kind, finish = raw[offset + 4:offset + 8], offset + 12 + length
            chunks += 1
            if chunks > 4096 or finish > len(raw) or binascii.crc32(raw[offset + 4:finish - 4]) & 0xFFFFFFFF != struct.unpack(">I", raw[finish - 4:finish])[0]:
                raise DesktopError("Capture PNG is truncated, corrupt or exceeds its chunk bound")
            if chunks == 1:
                if kind != b"IHDR" or length != 13:
                    raise DesktopError("Capture PNG has no complete original IHDR")
                width, height, depth, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", raw[offset + 8:finish - 4])
                if not (1 <= width <= 1200 and 1 <= height <= 1200) or depth not in {1, 2, 4, 8, 16} or colour not in {0, 2, 3, 4, 6} or compression or filtering or interlace not in {0, 1}:
                    raise DesktopError("Capture PNG dimensions/format differ from the requested 1200px bounded image")
                dimensions = {"width": width, "height": height, "mediaType": "image/png"}
            elif kind == b"IHDR":
                raise DesktopError("Capture PNG contains a second image header")
            if kind == b"IDAT": data_seen = True
            if kind == b"IEND":
                if length or finish != len(raw) or not data_seen:
                    raise DesktopError("Capture PNG has invalid final image data")
                return dimensions
            offset = finish
        raise DesktopError("Capture PNG did not contain a complete image")

    def _capture(self, target, *, preflight=False, mode="hybrid"):
        if mode not in {"semantic", "visual", "hybrid"} or mode == "semantic" and target is None:
            raise DesktopError("Semantic observation requires an exact process/window", 400)
        if target is None:
            arguments, tool = {"max_image_dimension": 1200}, "get_desktop_state"
        else:
            arguments, tool = {"pid": target["pid"], "window_id": target["windowId"], "max_image_dimension": 1200,
                               "include_accessibility_tree": not preflight and mode != "visual", "include_screenshot": mode != "semantic"}, "get_window_state"
            if arguments["include_accessibility_tree"]:
                arguments.update(tree_format="elements", max_elements=150)
        result = self.driver.call(tool, arguments)
        if result.get("isError"):
            raise DesktopError("Pinned Driver could not observe the requested desktop/window")
        structured = result.get("structuredContent")
        if not isinstance(structured, dict):
            raise DesktopError("Driver observation has no structured provenance")
        if target is not None and (structured.get("pid") != target["pid"] or structured.get("window_id") != target["windowId"]):
            raise DesktopError("Driver observed a different process/window", 403)
        if mode == "semantic":
            if any(item.get("type") == "image" for item in result.get("content", [])):
                raise DesktopError("Semantic observation unexpectedly returned image content; no capture is published")
            if not isinstance(structured.get("elements"), list) or not isinstance(structured.get("snapshot_id"), str):
                raise DesktopError("Driver did not provide a real structured UIA snapshot")
            raw = None
        else:
            raw = self._image(result)
        return result, structured, raw

    @staticmethod
    def _target_element(state, token):
        matches = [item for item in state.get("elements", []) if item.get("element_token") == token]
        if len(matches) != 1:
            raise DesktopError("Exact original UIA target is unavailable; observe again")
        return matches[0]

    @staticmethod
    def _text_element(state, token):
        element = DesktopAdapter._target_element(state, token)
        if (element.get("role") not in {"Edit", "Document"} or element.get("enabled") is not True
                or "set_value" not in element.get("actions", []) or state.get("adapter_truncated")):
            raise DesktopError("Full text replacement requires an enabled UIA text element advertising ValuePattern; no keystroke fallback")
        return element

    def _fresh_target(self, snapshot, payload, current):
        """Public verify_state reads live UIA without replacing retained tokens.

        The semantic selector is only an assertion about the ORIGINAL target,
        never permission to select/rebind a different token. The original COM
        identity, native frame, pixel region and snapshot epoch remain required.
        """
        element = self._target_element(snapshot["state"], payload["element_token"])
        if not isinstance(element.get("frame"), dict) or set(element["frame"]) != {"x", "y", "w", "h"}:
            raise DesktopError("Original text target lacks exact native UIA bounds; no input dispatched")
        selector = {"role": element["role"]}
        if isinstance(element.get("label"), str) and element["label"].strip():
            selector["label_contains"] = element["label"]
        predicate = {"selector": selector, "exists": True, "enabled": True}
        if isinstance(element.get("value"), str) and element["value"]:
            predicate["value_equals"] = element["value"]
        target = snapshot["target"]
        result = self.driver.call("verify_state", {"pid": target["pid"], "window_id": target["windowId"],
                                  "expect": [{"element": predicate}], "timeout_ms": 0, "stable_samples": 1, "include_screenshot": False})
        self._current(current)
        state = result.get("structuredContent", {})
        if not isinstance(state, dict):
            raise DesktopError("Fresh UIA semantic verification has no structured provenance; no input dispatched")
        outcomes = state.get("predicates", [])
        if (result.get("isError") or state.get("status") != "satisfied" or state.get("stable") is not True
                or type(state.get("samples")) is not int or state["samples"] != 1
                or not isinstance(outcomes, list) or len(outcomes) != 1 or not isinstance(outcomes[0], dict) or outcomes[0].get("index") != 0
                or outcomes[0].get("status") != "satisfied" or outcomes[0].get("unknown_reason") is not None):
            raise DesktopError("Fresh original UIA target semantics are changed, ambiguous or unverifiable; no input dispatched")
        try:
            evidence = outcomes[0]["observed_json"]
            if not isinstance(evidence, str) or len(evidence.encode("utf-8")) > 2000:
                raise ValueError("Pinned semantic evidence bound exceeded")
            observed = json.loads(evidence)
        except (KeyError, TypeError, ValueError) as failure:
            raise DesktopError("Fresh UIA target evidence is unavailable/truncated; no input dispatched") from failure
        if (not isinstance(observed, dict) or any(observed.get(key) != element.get(key) for key in ("role", "label", "enabled", "selected", "frame"))
                or observed.get("value", "") != element.get("value", "")):
            raise DesktopError("Fresh UIA target value/identity semantics/bounds changed; no input dispatched")

    @staticmethod
    def _region_fingerprints(raw, elements):
        """Decode with Pillow, never compare compressed PNG bytes as target pixels.

        Only exact in-capture rectangles supplied by the pinned Driver qualify.
        This is a preflight guard, NOT a new target resolver: input retains the
        original snapshot's COM-backed token and never uses these coordinates.
        """
        dimensions = DesktopAdapter._png_dimensions(raw)
        boxes = {}
        for element in elements:
            rectangle, token = element.get("screenshot_frame"), element.get("element_token")
            if not isinstance(rectangle, dict) or set(rectangle) != {"x", "y", "w", "h"} or any(type(v) is not int for v in rectangle.values()):
                continue
            x, y, w, h = (rectangle[key] for key in ("x", "y", "w", "h"))
            if not (0 <= x < x + w <= dimensions["width"] and 0 <= y < y + h <= dimensions["height"]) or not isinstance(token, str) or token in boxes:
                continue
            boxes[token] = (x, y, x + w, y + h)
        if not boxes:
            return {}
        try:
            from PIL import Image
            with Image.open(io.BytesIO(raw), formats=["PNG"]) as image:
                if image.size != (dimensions["width"], dimensions["height"]) or getattr(image, "n_frames", 1) != 1:
                    raise DesktopError("Target guard requires one exact static capture")
                image.load()
                with image.convert("RGBA") as pixels:
                    result = {}
                    for token, box in boxes.items():
                        with pixels.crop(box) as region:
                            result[token] = {"box": box, "sha256": hashlib.sha256(region.tobytes()).hexdigest()}
                    return result
        except (ImportError, OSError, ValueError) as failure:
            raise DesktopError("PNG target guard decoder unavailable or invalid; no input dispatched") from failure

    def observe(self, binding, arguments, metadata, current):
        self._current(current)
        if set(arguments) - {"pid", "windowId", "discovery", "observationMode"}:
            raise DesktopError("Unknown desktop observation field", 400)
        target = None
        if "pid" in arguments or "windowId" in arguments:
            target = {"pid": _integer(arguments.get("pid"), 1, 0xFFFFFFFF, "pid"),
                      "windowId": _integer(arguments.get("windowId"), 1, 9007199254740991, "windowId")}
        mode = arguments.get("observationMode", "semantic" if target else "hybrid")
        if mode not in {"semantic", "visual", "hybrid"}:
            raise DesktopError("Unknown desktop observation mode", 400)
        with self.lock:
            health = self._health(target)
            self._scope(binding, health, target)
            self._current(current)
            if arguments.get("discovery") is True:
                if target is not None or "windowId" in binding:
                    raise DesktopError("Session-wide window discovery is outside a single-window grant", 403)
                result = self.driver.call("get_accessibility_tree", {})
                if result.get("isError"):
                    raise DesktopError("Driver window discovery failed")
                # This tool lists processes/windows; it is not an inner UIA tree
                # and is never labeled a screenshot or action-ready observation.
                return {"kind": "window_discovery", "actionReady": False, "result": self._bounded(result.get("structuredContent", {})),
                        "summary": self._bounded([item.get("text", "") for item in result.get("content", []) if item.get("type") == "text"])}
            _, structured, raw = self._capture(target, mode=mode)
            dimensions = self._png_dimensions(raw) if raw is not None else None
            if dimensions is not None:
                for key, actual in (("screenshot_width", dimensions["width"]), ("screenshot_height", dimensions["height"])):
                    if key in structured and structured[key] != actual:
                        raise DesktopError("Driver screenshot dimensions differ from the actual original PNG bytes")
            after = self._health(target)
            if health["binding"] != after["binding"]:
                raise DesktopError("Desktop geometry/session/window changed during capture; observe again")
            self._current(current)
            identifier = uuid.uuid4().hex
            captured = self.clock()
            reference = {"id": identifier, "sessionId": after["sessionId"], "displayId": "primary",
                         "windowId": target["windowId"] if target else None, "binding": after["binding"],
                         "driverGeneration": self.generation, "capturedAt": captured, "expiresAt": captured + 30000}
            state = self._bounded(structured)
            value = {"observationRef": reference, "target": target, "state": state, "observationMode": mode,
                     "coordinateSpace": "uia_elements", "businessResultVerified": False}
            if raw is not None:
                descriptor = self.capture_store(raw, {**metadata, "resourceBinding": binding, "observationRef": reference}, captured + 600000)
                state.update(screenshot_width=dimensions["width"], screenshot_height=dimensions["height"])
                value.update(image=dimensions, capture=descriptor, captureSha256=hashlib.sha256(raw).hexdigest(),
                             coordinateSpace="window_capture_pixels" if target else "primary_desktop_capture_pixels")
            else:
                for key in ("screenshot_width", "screenshot_height", "capture_id", "screenshot_frame"):
                    state.pop(key, None)
                for element in state.get("elements", []):
                    element.pop("screenshot_frame", None)
            self.snapshots = {key: item for key, item in self.snapshots.items() if item["ref"]["expiresAt"] > captured}
            if len(self.snapshots) >= 16:
                self.snapshots.pop(next(iter(self.snapshots)))
            self.snapshots[identifier] = {"ref": reference, "health": after, "target": target, "binding": copy.deepcopy(binding),
                                          "hash": value.get("captureSha256"), "state": copy.deepcopy(value["state"]), "consumed": False, "mode": mode,
                                          "audience": {key: metadata[key] for key in ("taskId", "requestRunId", "requestExecutorEpoch")},
                                          "targetRegions": self._region_fingerprints(raw, state.get("elements", [])) if raw is not None else {}}
            return value

    @staticmethod
    def _bounded(value, maximum=24576):
        return project(value, maximum=maximum)

    def act(self, binding, observation_ref, owner, arguments, metadata, current, dispatching):
        self._current(current)
        if not isinstance(observation_ref, dict) or not isinstance(arguments, dict) or set(arguments) - {"kind", "lease", "elementToken", "x", "y", "button", "count", "text", "keys", "key", "direction", "amount", "by", "verification", "deliveryMode", "foregroundAfterOperationId", "observationMode"}:
            raise DesktopError("Invalid desktop action envelope", 400)
        with self.lock:
            snapshot = self.snapshots.get(observation_ref.get("id"))
            if not snapshot or not resource_equal(snapshot["ref"], observation_ref) or snapshot["consumed"] or observation_ref["expiresAt"] <= self.clock() or not resource_equal(snapshot["binding"], binding):
                raise DesktopError("Observation is missing, consumed, stale or belongs to another resource; observe again")
            if observation_ref["driverGeneration"] != self.generation or not resource_equal(snapshot["audience"], {key: metadata[key] for key in ("taskId", "requestRunId", "requestExecutorEpoch")}):
                raise DesktopError("Observation belongs to another original task/run/executor epoch; observe again", 403)
            mode = snapshot["mode"]
            if "observationMode" in arguments and arguments["observationMode"] != mode:
                raise DesktopError("Action observation mode differs from the original delivered observation", 403)
            target = snapshot["target"]
            health = self._health(target)
            resource = self._scope(binding, health, target)
            if health["binding"] != snapshot["health"]["binding"]:
                raise DesktopError("Window, DPI, display or session changed; old frame refused")
            if health["lastInputTick"] != snapshot["health"]["lastInputTick"]:
                raise DesktopError("Computer received other input; local user may have taken over. Observe and review again")
            with self.manager.lock:
                self._lease(resource, owner, arguments.get("lease"), {"HELD"})
            tool, payload = self._action(arguments, snapshot)
            strict_semantic = snapshot["state"].get("semantic_provider") == "agent-desktop" and "element_token" in payload
            if mode == "semantic" and (payload["delivery_mode"] != "background" or "element_token" not in payload
                    or arguments["kind"] != "set_text" and not (strict_semantic and arguments["kind"] == "click")):
                raise DesktopError("This action requires an explicit visual/hybrid observation; semantic input cannot fall back to pixels", 409)
            semantic_target = arguments["kind"] == "set_text"
            if arguments["kind"] == "click" and "element_token" in payload:
                element = self._target_element(snapshot["state"], payload["element_token"])
                # UIA click can fall back to its cached center. Relax pixels
                # only with a fresh assertion of IDENTICAL native geometry and
                # a real semantic action; otherwise keep the full-frame guard.
                semantic_target = (element.get("enabled") is True and not snapshot["state"].get("adapter_truncated")
                                   and bool(set(element.get("actions", [])) & {"invoke", "toggle", "select", "expand", "collapse"})
                                   and "verify_state" in self.driver.capabilities().get("tools", []))
            semantic = self._semantic_action(tool, payload, snapshot)
            prior_refusal = None
            if payload["delivery_mode"] == "foreground":
                reference = arguments.get("foregroundAfterOperationId")
                prior_refusal = self.refusals.get(reference)
                if not prior_refusal or reference == metadata.get("operationId") or not resource_equal(prior_refusal, {"owner": owner, "binding": binding, "healthBinding": health["binding"], "semantic": semantic,
                                                                                                                     "taskId": metadata["taskId"], "requestRunId": metadata["requestRunId"], "requestExecutorEpoch": metadata["requestExecutorEpoch"]}):
                    raise DesktopError("Foreground needs a NEW operation and fresh observation matching an exact original known-no-input background refusal", 403)
            # Fresh capture-only read preserves the exact retained COM target.
            # A new UIA walk would stale its token; never rebind by name/index.
            if semantic_target or strict_semantic:
                self._fresh_target(snapshot, payload, current)
            # Pure UIA actions are protected by live target identity/state and
            # the original native ref at dispatch. They never grab a screenshot.
            # Cua element clicks may fall back to pixels, so those retain the
            # full visual guard; agent-desktop is always invoked headless.
            if mode != "semantic" and not strict_semantic:
                _, live_state, live_png = self._capture(target, preflight=True)
                if "element_token" in payload and (live_state.get("snapshot_id") != snapshot["state"].get("snapshot_id")
                        or live_state.get("screenshot_refreshed_snapshot") is not True
                        or not payload["element_token"].startswith(str(live_state.get("snapshot_id")) + ":")):
                    raise DesktopError("Original UIA snapshot was replaced; no input dispatched, observe again")
                if self._png_dimensions(live_png) != {"width": snapshot["state"]["screenshot_width"], "height": snapshot["state"]["screenshot_height"], "mediaType": "image/png"}:
                    raise DesktopError("Capture dimensions changed; no input dispatched, observe again")
                if semantic_target:
                    token = payload["element_token"]
                    element = self._target_element(snapshot["state"], token)
                    original = snapshot["targetRegions"].get(token)
                    live = self._region_fingerprints(live_png, [element]).get(token)
                    if original is None or live != original or any(live_state.get(key) != snapshot["state"].get(key) for key in ("window_title", "window_bounds", "capture_coverage")):
                        raise DesktopError("Original UIA target pixels/window semantics changed or lack an exact region; no input dispatched, observe again")
                elif hashlib.sha256(live_png).hexdigest() != snapshot["hash"]:
                    raise DesktopError("Target pixels changed after approval; no input dispatched, observe again")
                if arguments["kind"] == "click" and "x" in arguments:
                    capture_id = live_state.get("capture_id")
                    if not isinstance(capture_id, str) or not capture_id:
                        raise DesktopError("Pinned Driver did not provide a one-use capture identity for pixel input")
                    payload["capture_id"] = capture_id
            health = self._health(target)
            if health["binding"] != snapshot["health"]["binding"] or health["lastInputTick"] != snapshot["health"]["lastInputTick"]:
                raise DesktopError("Desktop changed during preflight; no input dispatched")
            admit = getattr(self.driver, "validate_call", None)
            if callable(admit):
                admit(tool, payload)
            self._current(current)
            with self.manager.lock:
                self._lease(resource, owner, arguments["lease"], {"HELD"})
                self.manager.ledger.execute("UPDATE desktop_resource_leases SET state='EXECUTING' WHERE resource_key=?", (resource,))
                self.manager.ledger.commit()
            snapshot["consumed"] = True
            dispatched = False
            try:
                # Persist original operation's dispatch intent BEFORE the only
                # input call. A crash at this boundary is unknown, never replay.
                self._current(current)
                dispatching()
                dispatched = True
                if prior_refusal is not None:
                    self.refusals.pop(arguments["foregroundAfterOperationId"], None)
                action_result = self.driver.call(tool, payload)
                outcome = input_outcome(action_result)
                self._current(current)
                after = self.observe(binding, {**(target or {}), "observationMode": mode}, metadata, current)
                matched = self._verify(arguments.get("verification"), after)
                replaced = arguments["kind"] != "set_text" or self._replacement_verified(payload, snapshot, after)
                verified = matched and replaced and outcome["kind"] == "dispatch_reported" and not action_result.get("isError")
                effect = action_result.get("structuredContent", {})
                refused = outcome["kind"] == "refused_pre_dispatch"
                status = "failed" if refused else "completed" if verified else "unknown"
                if payload["delivery_mode"] == "background" and outcome["foregroundEscalationPermitted"]:
                    if len(self.refusals) >= 16:
                        self.refusals.pop(next(iter(self.refusals)))
                    self.refusals[metadata["operationId"]] = {"owner": owner, "binding": copy.deepcopy(binding), "healthBinding": health["binding"], "semantic": semantic,
                                                             "taskId": metadata["taskId"], "requestRunId": metadata["requestRunId"], "requestExecutorEpoch": metadata["requestExecutorEpoch"]}
                receipt = {"status": status, "beforeObservationRef": observation_ref, "afterObservationRef": after["observationRef"],
                           "afterObservation": after, "observationMode": mode, "inputDispatched": outcome["inputDispatched"], "inputDispatchAttempted": True,
                           "actionOutcome": outcome, "observedConditionMatched": matched, "deliveryMode": payload["delivery_mode"],
                           "textReplacementVerified": replaced if arguments["kind"] == "set_text" else None,
                           "driverResult": self._bounded(effect, maximum=4096), "driverIsError": bool(action_result.get("isError")),
                           "businessResultVerified": verified, "remoteEffectsVerified": verified,
                           "verificationScope": "observed_window_condition_only", "userGoalVerified": False,
                           "verificationCondition": self._condition_receipt(arguments.get("verification")), "noAutomaticReplay": True,
                           "message": "Driver refused before input; inspect original operation. Foreground requires a separately approved NEW operation and fresh observation" if refused else "Requested window condition verified by a new observation" if verified else "Input was attempted; user result is not proven. Inspect original operation and new observation, never repeat automatically"}
                with self.manager.lock:
                    updated = self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=? WHERE resource_key=? AND lease_id=? AND fence=? AND state='EXECUTING'",
                        ("HELD" if verified or refused else "NEEDS_RECONCILE", resource, arguments["lease"]["id"], arguments["lease"]["fence"])).rowcount
                    self.manager.ledger.commit()
                if updated != 1:
                    raise DesktopError("Original desktop lease was fenced during input")
                return receipt
            except BaseException as failure:
                with self.manager.lock:
                    self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=? WHERE resource_key=? AND lease_id=? AND fence=? AND state='EXECUTING'",
                        ("NEEDS_RECONCILE" if dispatched else "HELD", resource, arguments["lease"]["id"], arguments["lease"]["fence"]))
                    self.manager.ledger.commit()
                if dispatched:
                    return {"status": "unknown", "inputDispatchAttempted": True, "inputDispatched": None,
                            "beforeObservationRef": observation_ref, "businessResultVerified": False, "noAutomaticReplay": True,
                            "message": "Driver/capture/permission/local-stop failed after possible input. Query this original operation and observe; do not replay"}
                raise

    @staticmethod
    def _semantic_action(tool, payload, snapshot):
        semantic = {key: item for key, item in payload.items() if key not in {"element_token", "capture_id", "delivery_mode"}}
        if "element_token" in payload:
            element = next(item for item in snapshot["state"].get("elements", []) if item.get("element_token") == payload["element_token"])
            semantic["element"] = {key: item for key, item in element.items() if key != "element_token"}
        return hashlib.sha256(json.dumps({"tool": tool, "arguments": semantic}, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()

    @staticmethod
    def _condition_receipt(condition):
        if condition is None:
            return None
        value = {key: copy.deepcopy(item) for key, item in condition.items() if key != "value"}
        if "value" in condition:
            raw = condition["value"].encode("utf-8")
            value.update(expectedValueSha256=hashlib.sha256(raw).hexdigest(), expectedValueBytes=len(raw))
        return value

    def _action(self, arguments, snapshot):
        kind, target = arguments.get("kind"), snapshot["target"]
        extras = {"click": {"elementToken", "x", "y", "button", "count"},
                  "insert_text": {"text"}, "set_text": {"text", "elementToken"},
                  "hotkey": {"keys"}, "press_key": {"key"}, "scroll": {"direction", "amount", "by"}}
        if kind not in extras or set(arguments) - {"kind", "lease", "verification", "deliveryMode", "foregroundAfterOperationId", "observationMode"} - extras[kind]:
            raise DesktopError("Action contains unsupported or unrelated fields", 400)
        self._validate_verification(arguments.get("verification"))
        payload = {"scope": "window", "pid": target["pid"], "window_id": target["windowId"]} if target else {"scope": "desktop"}
        mode = arguments.get("deliveryMode", "background")
        if mode not in {"background", "foreground"} or mode == "background" and "foregroundAfterOperationId" in arguments:
            raise DesktopError("Unknown/mismatched explicit delivery mode", 400)
        payload["delivery_mode"] = mode
        token = arguments.get("elementToken")
        if token is not None:
            tokens = {item.get("element_token") for item in snapshot["state"].get("elements", [])}
            if target is None or not isinstance(token, str) or not re.fullmatch(r"s[0-9a-f]{8}:[0-9]+", token) or token not in tokens:
                raise DesktopError("Element token is not present in this exact observed window", 403)
            payload["element_token"] = token
        has_pixels = "x" in arguments or "y" in arguments
        if token is not None and has_pixels:
            raise DesktopError("Choose an observed UIA element OR a capture-bound pixel target", 400)
        if has_pixels:
            state = snapshot["state"]
            width, height = state.get("screenshot_width"), state.get("screenshot_height")
            if type(width) is not int or type(height) is not int:
                raise DesktopError("Observed capture has no exact pixel dimensions")
            payload.update(x=_coordinate(arguments.get("x"), width, "x"), y=_coordinate(arguments.get("y"), height, "y"))
        if kind == "click":
            if token is None and not has_pixels:
                raise DesktopError("Click requires an observed element or pixel target", 400)
            button = arguments.get("button", "left")
            if button not in {"left", "right", "middle"}:
                raise DesktopError("Unsupported click button", 400)
            payload.update(button=button, count=_integer(arguments.get("count", 1), 1, 3, "count"))
            return "click", payload
        # No compound pixel-click+type/hotkey: the original operation must own
        # exactly one visible action, followed by a fresh observation.
        if has_pixels:
            raise DesktopError("Pixel focus must be a separate click and re-observation", 400)
        if kind in {"insert_text", "set_text"}:
            text = arguments.get("text")
            if not isinstance(text, str) or (not text and kind != "set_text") or len(text.encode("utf-8")) > 12000 or "\x00" in text:
                raise DesktopError("Text must be bounded UTF-8", 400)
            if (kind == "set_text") != (token is not None):
                raise DesktopError("set_text requires UIA element; insert_text never silently replaces an element value", 400)
            if kind == "set_text" and mode != "background":
                raise DesktopError("Full UIA value replacement cannot become foreground insertion; use separately observed explicit insert_text semantics", 409)
            if kind == "set_text":
                self._text_element(snapshot["state"], token)
                if not {"set_value", "verify_state"} <= set(self.driver.capabilities().get("tools", [])):
                    raise DesktopError("Pinned Driver did not advertise full ValuePattern replacement and non-mutating semantic verification; no insertion or hotkey fallback")
                # Exact f595 schema has value, not text/delay_ms/scope. The
                # original operation owns one direct SetValue, including clear.
                payload.pop("scope")
                payload["value"] = text
                return "set_value", payload
            payload["text"] = text
            payload["delay_ms"] = 0
            return "type_text", payload
        if token is not None:
            raise DesktopError("This action does not claim Windows element-token targeting", 409)
        if kind == "press_key":
            key = arguments.get("key")
            if target is None or not isinstance(key, str) or key.casefold() not in {"enter", "escape", "tab"}:
                raise DesktopError("Single keys require an exact observed window and Enter, Escape or Tab", 400)
            capability = self.driver.capabilities()
            if capability.get("runtimeContract") == "verified_against_pinned_process" and "press_key" not in capability.get("tools", []):
                raise DesktopError("Pinned Driver did not advertise the actual press_key tool; no input dispatched")
            # Audited f595 press_key.rs accepts return (not enter), with no
            # modifiers, pixel-focus compound action or fallback hotkey.
            payload["key"] = {"enter": "return", "escape": "escape", "tab": "tab"}[key.casefold()]
            return "press_key", payload
        if kind == "hotkey":
            keys = arguments.get("keys")
            if not isinstance(keys, list) or not 2 <= len(keys) <= 5 or any(not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,20}", key) for key in keys):
                raise DesktopError("Invalid bounded hotkey", 400)
            payload["keys"] = keys
            return "hotkey", payload
        if kind == "scroll":
            if target is None:
                raise DesktopError("Desktop-wide scroll is not admitted without an exact capture-bound target", 409)
            if arguments.get("direction") not in {"up", "down", "left", "right"} or arguments.get("by", "line") not in {"line", "page"}:
                raise DesktopError("Invalid scroll direction/granularity", 400)
            payload.update(direction=arguments["direction"], by=arguments.get("by", "line"), amount=_integer(arguments.get("amount", 3), 1, 50, "amount"))
            return "scroll", payload
        raise DesktopError("Unsupported desktop action; drag is deliberately unavailable", 409)

    @staticmethod
    def _replacement_verified(payload, snapshot, after):
        original = DesktopAdapter._text_element(snapshot["state"], payload["element_token"])
        state = after.get("state", {})
        # Tokens are intentionally fresh after input. A unique semantic text
        # target plus the exact complete value is readback, not dispatch success.
        selector = {"role": original["role"], "frame": original.get("frame")}
        if original.get("label") and original.get("label") != original.get("value"):
            selector["label"] = original["label"]
        matches = [item for item in state.get("elements", []) if all(item.get(key) == value for key, value in selector.items())]
        if len(matches) != 1 or state.get("adapter_truncated"):
            return False
        element = matches[0]
        return ("set_value" in element.get("actions", []) and element.get("enabled") is True
                and isinstance(element.get("value"), str) and element["value"] == payload["value"])

    @staticmethod
    def _validate_verification(condition):
        if condition is None:
            return
        if not isinstance(condition, dict):
            raise DesktopError("Invalid explicit window verification condition", 400)
        kind = condition.get("kind")
        required = {"window_title_equals": {"kind", "value"}, "element_value_equals": {"kind", "selector", "value"},
                    "element_present": {"kind", "selector"}, "element_absent": {"kind", "selector"}}
        if kind not in required or set(condition) != required[kind]:
            raise DesktopError("Unknown window verification condition", 400)
        if "value" in condition and (not isinstance(condition["value"], str) or len(condition["value"].encode()) > 12000):
            raise DesktopError("Invalid bounded expected window value", 400)
        if "selector" in condition:
            selector = condition["selector"]
            if not isinstance(selector, dict) or not selector or set(selector) - {"role", "label"} or any(not isinstance(value, str) or not value or len(value) > 500 for value in selector.values()):
                raise DesktopError("Verification requires a bounded role/label selector", 400)

    @staticmethod
    def _verify(condition, observation):
        # A minimal, explicit WINDOW postcondition, not a claim that a remote
        # send/save/business goal succeeded. File-save verification belongs to
        # the existing bounded file/artifact tools with an expected content hash.
        if condition is None:
            return False
        state, kind = observation.get("state", {}), condition["kind"]
        if kind == "window_title_equals":
            return state.get("window_title") == condition["value"]
        matches = [item for item in state.get("elements", []) if all(item.get(key) == value for key, value in condition["selector"].items())]
        if kind == "element_absent":
            return state.get("elements_complete") is True and not state.get("truncated") and not matches
        # Never identify a post-action element by an old token/index. Require a
        # unique semantic selector in the NEW actual window snapshot.
        if len(matches) != 1:
            return False
        # The pinned UIA builder omits empty/whitespace values AND failed or
        # unsupported reads. Pattern availability cannot distinguish them.
        # Missing value is unknown, never proof of a successful clear.
        return kind == "element_present" or (isinstance(matches[0].get("value"), str) and matches[0]["value"] == condition.get("value"))
