"""Real paired mTLS identity / grant / original SQLite ingress, fixture media/input.

No Windows GUI, WebRTC transport, Android device or user desktop is simulated as
accepted here. Media and OS input are explicitly controlled boundary fixtures.
"""
import copy
import json
from types import SimpleNamespace
import unittest

import device_protocol as wire
from desktop_viewer import DesktopViewer
from platform_backend import PlatformError
import test_device_companion as companion_fixture
from test_desktop_stream import BINDING, TARGET, FixtureHealth, OFFER


class FixtureStream:
    def __init__(self):
        self.opens = self.closes = self.invalidations = 0
        self.stale = False
    def describe(self): return {"available": True}
    def client_configuration(self): return {"iceServers": [{"urls": ["turn:fixture.invalid:3478"], "username": "fixture", "credential": "fixture-private-turn-value"}]}
    def open(self, binding, target, owner, offer, expires_at, current):
        current(); self.opens += 1
        return {"streamId": "a" * 32, "answer": {"type": "answer", "sdp": "fixture-private-sdp"}, "expiresAt": expires_at, "state": "connected"}
    def status(self, stream_id, owner, binding, current):
        current(); return {"streamId": stream_id, "state": "connected", "lastFrame": None}
    def validate_input(self, stream_id, owner, binding, frame_ref, current):
        current()
        if self.stale: raise PlatformError("Fixture stale frame", 409)
        return {"target": TARGET, "captureBounds": [20, 30, 340, 230], "width": 320, "height": 200}
    def invalidate_inputs(self, stream_id, owner, binding, current): current(); self.invalidations += 1
    def close(self, stream_id, owner, binding, current): self.closes += 1
    def close_all(self): pass


class DesktopViewerIngressTests(unittest.TestCase):
    receive = companion_fixture.NativeCompanionIntegrationTests.receive
    success = companion_fixture.NativeCompanionIntegrationTests.success
    tearDown = companion_fixture.NativeCompanionIntegrationTests.tearDown
    def setUp(self):
        companion_fixture.NativeCompanionIntegrationTests.setUp(self)
        self.receiver.desktop_viewer.close()
        self.native_calls = self.takes = self.releases = 0
        self.unknown = False
        self.adapter = SimpleNamespace(health=FixtureHealth(), acquire_human=self.acquire, release_human=self.release, human_act=self.act)
        self.receiver.desktop = SimpleNamespace(jobs=SimpleNamespace(adapter=self.adapter), close=lambda: None)
        self.stream = FixtureStream()
        self.receiver.desktop_viewer = DesktopViewer(self.receiver, stream=self.stream)
        self.grant.update(authorizationEpoch=2, capabilities=["desktop.viewer"], resources=[BINDING], toolNames=[])
        self.receiver.trust.replace_grant(self.grant, self.now)
        self.session = "native-owner-fixture"
        self.operation = 0
    def acquire(self, binding, owner, stream_id, current):
        current(); self.takes += 1
        return {"status": "completed", "lease": {"id": "fixture-human", "fence": self.takes, "expiresAt": self.now + 300000, "resource": "fixture-desktop", "streamId": stream_id}}
    def release(self, binding, owner, stream_id, lease, current):
        current()
        if self.unknown: raise PlatformError("Fixture native lease needs reconciliation", 409)
        self.releases += 1
        return {"status": "completed", "released": True}
    def act(self, binding, owner, stream_id, lease, input_value, geometry, current, marker):
        current(); marker(); self.native_calls += 1
        if self.unknown: raise RuntimeError("fixture native result lost after dispatch")
        return {"status": "completed", "inputDispatched": True, "noAutomaticReplay": True, "userGoalVerified": False}
    def request(self, action, **arguments):
        self.operation += 1
        args = {"action": action, "viewerSessionId": self.session, **arguments}
        if action not in {"configuration", "negotiate", "close"}: args.setdefault("streamId", "a" * 32)
        value = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="DeviceRequest", method="desktop.viewer",
                     requestId="viewer-request-" + str(self.operation), operationId="viewer-operation-" + str(self.operation),
                     originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id,
                     instanceId=self.b.instance_id, grantId=self.grant["grantId"], authorizationEpoch=self.grant["authorizationEpoch"],
                     capabilityVersion="desktop-v2", resourceBinding=BINDING, deadline=self.now + 240000, arguments=args)
        value["argumentsDigest"] = wire.arguments_digest(value)
        return wire.canonical_bytes(wire.parse_request(wire.canonical_bytes(value)).json())
    def open(self):
        configuration = self.success(self.receive(self.request("configuration", target=TARGET)))
        self.assertEqual("completed", configuration["status"])
        negotiated = self.success(self.receive(self.request("negotiate", offer=OFFER)))
        self.assertEqual("completed", negotiated["status"])
        taken = self.success(self.receive(self.request("take", expectedRevision=0)))
        self.assertEqual("human", taken["control"]["owner"])
        return taken["control"]["lease"]
    def input_request(self, lease):
        return self.request("input", controlLease=lease, frameRef={"fixture": "fresh"}, input={"kind": "insert_text", "text": "private typed fixture"})

    def test_owner_journey_same_native_ledger_dedup_and_no_secret_persistence(self):
        lease = self.open()
        raw = self.input_request(lease)
        first = self.success(self.receive(raw)); second = self.success(self.receive(raw))
        self.assertEqual(first, second)
        self.assertEqual("completed", first["inputReceipt"]["status"])
        self.assertEqual(1, self.native_calls)
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        stored = " ".join(str(row) for row in self.manager.ledger.execute("SELECT * FROM desktop_viewer_operations"))
        for private in ("fixture-private-turn-value", "fixture-private-sdp", "private typed fixture"):
            self.assertNotIn(private, stored)
        released = self.success(self.receive(self.request("release", expectedRevision=1)))
        self.assertEqual("none", released["control"]["owner"])
        self.assertEqual(2, released["control"]["revision"])
        closed = self.success(self.receive(self.request("close")))
        self.assertEqual("closed", closed["state"])
        self.assertEqual(1, self.releases)

    def test_unknown_os_input_never_replays_same_or_fresh_operation(self):
        lease = self.open(); self.unknown = True
        raw = self.input_request(lease)
        self.assertEqual("unknown", self.success(self.receive(raw))["inputReceipt"]["status"])
        self.assertEqual("unknown", self.success(self.receive(raw))["status"])
        self.assertEqual("failed", self.success(self.receive(self.input_request(lease)))["inputReceipt"]["status"])
        self.assertEqual(1, self.native_calls)
        self.success(self.receive(self.request("close")))
        self.assertEqual(0, self.releases, "Unknown input may not silently return control to the Agent")

    def test_stale_frame_is_not_dispatched(self):
        lease = self.open(); self.stream.stale = True
        receipt = self.success(self.receive(self.input_request(lease)))["inputReceipt"]
        self.assertEqual("failed", receipt["status"]); self.assertFalse(receipt["inputDispatched"])
        self.assertEqual(0, self.native_calls)

    def test_old_grant_cannot_gain_viewer_method(self):
        self.grant.update(authorizationEpoch=3, capabilities=["operation.get"])
        self.receiver.trust.replace_grant(self.grant, self.now)
        response = self.receive(self.request("configuration", target=TARGET))
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual("not_dispatched", response["error"]["effectState"])
        self.assertEqual(0, self.stream.opens)

    def test_revocation_fences_live_owner_before_native_input(self):
        lease = self.open(); raw = self.input_request(lease)
        self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
        response = self.receive(raw)
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual(0, self.native_calls)

    def test_revoke_after_native_completion_never_reports_not_dispatched(self):
        lease = self.open(); viewer = self.receiver.desktop_viewer
        original = viewer.invoke
        def revoke_after(envelope, permission):
            result = original(envelope, permission)
            self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
            return result
        viewer.invoke = revoke_after
        response = self.receive(self.input_request(lease))
        self.assertEqual(1, self.native_calls)
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual("unknown", response["error"]["effectState"])

    def test_changed_digest_same_operation_cannot_dispatch(self):
        lease = self.open(); raw = self.input_request(lease)
        self.success(self.receive(raw))
        changed = json.loads(raw); changed["arguments"]["input"]["text"] = "changed private fixture"
        changed["argumentsDigest"] = wire.arguments_digest(changed)
        self.assertEqual("DeviceError", self.receive(wire.canonical_bytes(changed))["messageType"])
        self.assertEqual(1, self.native_calls)

    def test_shared_original_grant_pool_exhaustion_is_not_reset(self):
        lease = self.open(); self.success(self.receive(self.input_request(lease)))
        self.manager.ledger.execute("UPDATE device_budget_pools SET tool_spent=tool_limit"); self.manager.ledger.commit()
        response = self.receive(self.input_request(lease))
        self.assertEqual("DeviceError", response["messageType"]); self.assertEqual(1, self.native_calls)
        self.assertEqual(self.grant["toolBudget"], self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_close_before_negotiation_and_duplicate_signalling_are_safe(self):
        raw = self.request("configuration", target=TARGET)
        self.assertEqual("completed", self.success(self.receive(raw))["status"])
        duplicate = self.success(self.receive(raw))
        self.assertEqual("unknown", duplicate["status"]); self.assertNotIn("iceServers", duplicate)
        self.assertEqual("closed", self.success(self.receive(self.request("close")))["state"])
        self.assertEqual(0, self.stream.opens)

    def test_protocol_rejects_mixed_action_fields_and_forwarded_viewer(self):
        with self.assertRaises(wire.ProtocolError): self.request("status", input={"kind": "click", "x": 1, "y": 1})
        raw = json.loads(self.request("configuration", target=TARGET)); raw["originDeviceId"] = "pw1_" + "f" * 64
        raw["argumentsDigest"] = wire.arguments_digest(raw)
        with self.assertRaises(wire.ProtocolError): wire.parse_request(wire.canonical_bytes(raw))

    def test_window_scoped_view_resolves_pid_from_original_native_window(self):
        requested = []
        def resolve(window_id):
            requested.append(window_id); return copy.deepcopy(TARGET)
        self.adapter.health.resolve_target = resolve
        result = self.success(self.receive(self.request("configuration")))
        self.assertEqual("completed", result["status"])
        self.assertEqual([BINDING["windowId"]], requested)
        session = next(iter(self.receiver.desktop_viewer.sessions.values()))
        self.assertEqual(TARGET, session["target"])


if __name__ == "__main__": unittest.main()
