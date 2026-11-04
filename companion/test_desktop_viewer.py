"""Actual original ledger + mTLS owner admission, synthetic GUI/media fixtures.

The inputs below exercise only fixture drivers. Windows capture/input and phone
playback remain separate platform acceptance; real WebRTC has its own loopback.
"""
import asyncio
import base64
import copy
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest

from PIL import Image

import device_identity as identity
import device_pairing as pairing
import device_protocol as wire
from desktop_adapter import DesktopAdapter, DesktopError
from desktop_jobs import DesktopJobs
from desktop_runtime import DesktopRuntime
from desktop_stream import DesktopStreamError, DesktopStreamService
from desktop_viewer import DesktopViewer
from device_companion import CompanionDeviceReceiver
from pocket_companion import JobManager
from test_device_pairing import tls_peers
from test_desktop_stream import FixtureHealth, FixtureCapture, FixturePeer, OFFER, fixture_rtc


class ViewerHealth(FixtureHealth):
    def resolve_window(self, window_id):
        if window_id != self.value["window"]["windowId"]:
            raise ValueError("Fixture window is not the granted HWND")
        return {"pid": self.value["window"]["pid"], "windowId": window_id}


class ViewerDriver:
    def __init__(self, health):
        self.health, self.calls = health, []
        self.action = None
        self.crop = False

    def capabilities(self):
        return {"installed": True, "contractFixture": True, "nativeGuiVerified": False}

    def stop(self):
        return {"processGroupStoppedVerified": True, "contractFixture": True}

    def validate_call(self, name, args):
        pass

    def call(self, name, args):
        self.calls.append((name, copy.deepcopy(args)))
        if name in {"get_window_state", "get_desktop_state"}:
            health = self.health.value
            bounds = health["window"]["bounds"] if name == "get_window_state" else health["displays"][0]["bounds"]
            native_width, native_height = bounds[2] - bounds[0], bounds[3] - bounds[1]
            capture_bounds = health["window"].get("frameBounds", bounds) if self.crop else bounds
            width, height = capture_bounds[2] - capture_bounds[0], capture_bounds[3] - capture_bounds[1]
            if args["max_image_dimension"]:
                ratio = min(1, args["max_image_dimension"] / max(width, height))
                width, height = int(width * ratio), int(height * ratio)
            with Image.new("RGB", (width, height), (20, 90, 140)) as image:
                buffer = io.BytesIO()
                image.save(buffer, format="PNG")
            value = {"capture_id": "fixture-native-capture", "pid": 123, "window_id": 456,
                     "screen_width": native_width, "screen_height": native_height,
                     "window_bounds": {"x": bounds[0], "y": bounds[1], "width": native_width, "height": native_height}}
            return {"structuredContent": value, "content": [{"type": "image", "mimeType": "image/png", "data": base64.b64encode(buffer.getvalue()).decode()}]}
        if self.action:
            return self.action(name, args)
        return {"structuredContent": {"fixture": True}, "content": []}


class DesktopViewerIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.manager = JobManager({"stateDir": str(root / "state"), "environments": []}, "test-only-viewer-token-000000000000000000000000")
        self.a, self.b = identity.provision_identity(root / "a"), identity.provision_identity(root / "b")
        self.peer, peer_b = tls_peers(self.a, self.b)
        self.receiver = CompanionDeviceReceiver(self.manager, self.b, native_management_only=True)
        self.coordinator, self.trust = self.receiver.pairing, self.receiver.trust
        self.now = self.manager.now()
        self.binding = {"kind": "desktop", "sessionId": 1, "displayId": "primary", "windowId": 456}
        self.primary = {"kind": "desktop", "sessionId": 1, "displayId": "primary"}
        self.grant = dict(grantId="viewer-grant", peerDeviceId=self.a.device_id, localDeviceId=self.b.device_id,
                          authorizationEpoch=1, issuedAt=self.now, expiresAt=self.now + 3600000,
                          capabilities=["desktop.viewer", "tool.invoke", "operation.get"], toolNames=["desktop.observe", "desktop.act", "desktop.control"],
                          resources=[self.binding, self.primary], allowedOrigins=[self.a.device_id], allowDelegation=False,
                          maxDelegationDepth=0, toolBudget=10, modelBudget=0)
        invitation = self.coordinator.create_invitation(self.b, self.a.certificate_fingerprint, self.now, 120000)
        transcript = pairing.pairing_transcript(invitation, peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
        self.coordinator.complete_pairing(invitation, self.peer, pairing.confirm_locally(self.b, transcript, True),
                                          pairing.confirm_locally(self.a, transcript, True).wire(), self.grant, self.now)
        self.attach_runtime()
        self.sequence = 0

    def attach_runtime(self):
        self.health = ViewerHealth()
        self.driver = ViewerDriver(self.health)
        self.adapter = DesktopAdapter(self.manager, self.driver, self.health, lambda *args: self.fail("Viewer must not publish model images"), self.b.device_id)
        self.jobs = DesktopJobs(self.manager, self.adapter)
        self.streams = DesktopStreamService(self.health, capture=FixtureCapture(), rtc_loader=fixture_rtc)
        self.runtime = DesktopRuntime(self.jobs, streams=self.streams)
        self.viewer = self.runtime.viewers
        self.receiver.desktop = self.runtime

    def tearDown(self):
        self.receiver.close()
        self.manager.close()
        self.temp.cleanup()

    def envelope(self, action, *, session_id="native-viewer-1", binding=None, **args):
        self.sequence += 1
        request = {"protocolVersion": wire.VERSION, "protocolProfile": wire.PROFILE, "messageType": "DeviceRequest",
                   "method": "desktop.viewer", "requestId": "viewer-request-" + str(self.sequence), "operationId": "viewer-operation-" + str(self.sequence),
                   "callerDeviceId": self.a.device_id, "originDeviceId": self.a.device_id, "targetDeviceId": self.b.device_id,
                   "instanceId": self.b.instance_id, "grantId": self.grant["grantId"], "authorizationEpoch": self.grant["authorizationEpoch"], "capabilityVersion": "desktop-v2",
                   "resourceBinding": binding or self.binding, "deadline": self.now + 120000,
                   "arguments": {"action": action, "viewerSessionId": session_id, **args}}
        request["argumentsDigest"] = wire.arguments_digest(request)
        return wire.parse_request(wire.canonical_bytes(request))

    def invoke(self, envelope):
        return self.runtime.viewer(envelope, self.coordinator.check_permission(self.peer, envelope, self.manager.now()))

    def receive(self, envelope):
        response = json.loads(asyncio.run(self.receiver.receive(self.peer, wire.canonical_bytes(envelope.json()))))
        wire.validate_message(response)
        return response

    def receive_success(self, envelope):
        response = self.receive(envelope)
        self.assertEqual("DeviceResponse", response["messageType"], response)
        return response["result"]

    def configure(self, *, session_id="native-viewer-1", binding=None):
        return self.invoke(self.envelope("configuration", session_id=session_id, binding=binding))

    def open(self, *, session_id="native-viewer-1", binding=None):
        self.configure(session_id=session_id, binding=binding)
        return self.invoke(self.envelope("negotiate", session_id=session_id, binding=binding, offer=OFFER))

    def take(self, view, *, session_id="native-viewer-1", binding=None):
        return self.invoke(self.envelope("take", session_id=session_id, binding=binding, streamId=view["streamId"], expectedRevision=view["control"]["revision"]))

    def frame(self, view):
        stream = self.streams.sessions[view["streamId"]]
        self.streams._call(stream.track.recv())
        return copy.deepcopy(stream.last_frame)

    def input_envelope(self, view, event=None, **updates):
        return self.envelope("input", streamId=view["streamId"], controlLease=view["control"]["lease"], frameRef=self.frame(view),
                             input=event or {"kind": "click", "x": 40, "y": 60, "button": "left", "count": 1}, **updates)

    def lease_state(self):
        return self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0]

    def agent_envelope(self, action="acquire", *, parent="budget-parent"):
        self.sequence += 1
        request = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="ToolInvocation", method="tool.invoke",
                       requestId="agent-request-" + str(self.sequence), operationId="agent-operation-" + str(self.sequence),
                       rootTaskId="budget-root", localTaskId="budget-local", parentTaskId=parent, runId="budget-run", attempt=1, executorEpoch=1,
                       callerDeviceId=self.a.device_id, originDeviceId=self.a.device_id, targetDeviceId=self.b.device_id,
                       instanceId=self.b.instance_id, grantId=self.grant["grantId"], authorizationEpoch=self.grant["authorizationEpoch"],
                       capabilityVersion="desktop-v2", resourceBinding=self.binding, deadline=self.now + 120000,
                       budget={"allocationId": wire.allocation_id(parent, self.b.device_id, self.binding, "tool_session"), "toolCalls": 1, "modelCalls": 0},
                       arguments={"toolName": "desktop.control", "effect": "write", "arguments": {"action": action}})
        request["argumentsDigest"] = wire.arguments_digest(request)
        return wire.parse_request(wire.canonical_bytes(request))

    def invoke_agent(self, request):
        return self.jobs.invoke(request, self.coordinator.check_permission(self.peer, request, self.manager.now()))

    def finish_agent(self, job):
        end = time.monotonic() + 2
        while time.monotonic() < end:
            value = self.manager.get(job["id"])
            if value["status"] in {"completed", "failed", "unknown", "cancelled"}:
                return value
            time.sleep(0.005)
        self.fail("Original Agent control job did not settle")

    def test_receiver_routes_owner_configuration_without_an_agent_task_or_model_budget(self):
        envelope = self.envelope("configuration")
        response = json.loads(asyncio.run(self.receiver.receive(self.peer, wire.canonical_bytes(envelope.json()))))
        self.assertEqual("DeviceResponse", response["messageType"], response)
        result = response["result"]
        self.assertEqual(envelope.get("operationId"), result["operationId"])
        self.assertEqual("native-viewer-1", result["viewerSessionId"])
        self.assertEqual(self.b.device_id, result["deviceId"])
        self.assertEqual(self.binding, result["resourceBinding"])
        self.assertEqual((320, 200), (result["width"], result["height"]))
        self.assertLessEqual(result["expiresAt"], envelope.get("deadline"))
        self.assertFalse(self.manager.jobs)
        self.assertFalse(self.driver.calls)
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_pools").fetchone()[0])

    def test_requires_actual_permission_and_exact_native_audience(self):
        envelope = self.envelope("configuration")
        with self.assertRaises(DesktopError):
            self.runtime.viewer(envelope, SimpleNamespace(envelope=envelope, check_current=lambda now: None))
        self.configure()
        with self.assertRaises(DesktopError):
            self.invoke(self.envelope("status", binding=self.primary))
        self.trust.replace_grant({**self.grant, "authorizationEpoch": 2}, self.manager.now())
        with self.assertRaises(PermissionError):
            self.invoke(self.envelope("status"))

    def test_receiver_validates_complete_owner_journey_without_persisting_media_or_typed_secrets(self):
        self.streams.ice_servers = [{"urls": ["turn:fixture.invalid:3478"], "username": "fixture-user", "credential": "fixture-private-turn-secret"}]
        configured = self.receive_success(self.envelope("configuration"))
        self.assertEqual("none", configured["control"]["owner"])
        self.assertFalse(configured["control"]["needsReconcile"])
        view = self.receive_success(self.envelope("negotiate", offer=OFFER))
        self.assertEqual("answer", view["answer"]["type"])
        taken = self.receive_success(self.envelope("take", streamId=view["streamId"], expectedRevision=view["control"]["revision"]))
        result = self.receive_success(self.input_envelope(taken, {"kind": "insert_text", "text": "fixture-private-typed-secret"}))
        self.assertEqual("completed", result["inputReceipt"]["status"])
        self.assertTrue(result["inputReceipt"]["inputDispatched"])
        status = self.receive_success(self.envelope("status", streamId=view["streamId"]))
        self.assertEqual("human", status["control"]["owner"])
        released = self.receive_success(self.envelope("release", streamId=view["streamId"], expectedRevision=status["control"]["revision"]))
        self.assertEqual("none", released["control"]["owner"])
        self.assertEqual("closed", self.receive_success(self.envelope("close"))["status"])
        stored = str(self.manager.ledger.execute("SELECT details FROM operations").fetchall())
        for secret in ("fixture-private-turn-secret", "fixture-private-typed-secret", view["answer"]["sdp"]):
            self.assertNotIn(secret, stored)
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertFalse(self.manager.jobs)

    def test_receiver_unknown_control_and_other_human_pass_typed_response_validation(self):
        first = self.take(self.open())
        other = self.receive_success(self.envelope("configuration", session_id="native-viewer-2"))
        self.assertEqual("other_human", other["control"]["owner"])
        self.assertNotIn("lease", other["control"])
        def failed(*args): raise OSError("fixture possible input lost reply")
        self.driver.action = failed
        result = self.receive_success(self.input_envelope(first))
        self.assertEqual("human", result["control"]["owner"])
        self.assertTrue(result["control"]["needsReconcile"])
        self.assertEqual("unknown", result["inputReceipt"]["status"])
        released = self.receive_success(self.envelope("release", streamId=first["streamId"], expectedRevision=result["control"]["revision"]))
        self.assertEqual("none", released["control"]["owner"])
        self.assertTrue(released["control"]["needsReconcile"])

    def test_old_grant_cannot_gain_viewer_method(self):
        self.grant.update(authorizationEpoch=2, capabilities=["operation.get"], toolNames=[])
        self.trust.replace_grant(self.grant, self.manager.now())
        response = self.receive(self.envelope("configuration"))
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual("not_dispatched", response["error"]["effectState"])
        self.assertFalse(self.viewer.sessions)

    def test_revocation_after_native_completion_never_reports_not_dispatched(self):
        view = self.take(self.open())
        request = self.input_envelope(view)
        original = self.runtime.viewer
        def revoke_after(envelope, permission):
            result = original(envelope, permission)
            self.coordinator.revoke_locally(self.a.device_id, self.manager.now())
            return result
        self.runtime.viewer = revoke_after
        response = self.receive(request)
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual("unknown", response["error"]["effectState"])
        self.assertEqual(1, sum(tool == "click" for tool, _ in self.driver.calls))

    def test_changed_digest_same_operation_cannot_dispatch(self):
        view = self.take(self.open())
        request = self.input_envelope(view, {"kind": "insert_text", "text": "fixture once"})
        self.receive_success(request)
        changed = request.json()
        changed["arguments"]["input"]["text"] = "fixture changed text"
        changed["argumentsDigest"] = wire.arguments_digest(changed)
        response = self.receive(wire.parse_request(wire.canonical_bytes(changed)))
        self.assertEqual("DeviceError", response["messageType"])
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_close_before_negotiation_and_duplicate_signalling_are_safe(self):
        request = self.envelope("configuration")
        first = self.receive_success(request)
        self.assertEqual(first, self.receive_success(request))
        self.assertEqual("closed", self.receive_success(self.envelope("close"))["status"])
        self.assertFalse(self.streams.sessions)

    def test_protocol_rejects_mixed_action_fields_and_forwarded_viewer(self):
        with self.assertRaises(wire.ProtocolError):
            self.envelope("status", input={"kind": "press_key", "key": "enter"})
        value = self.envelope("configuration").json()
        value["originDeviceId"] = "pw1_" + "f" * 64
        value["argumentsDigest"] = wire.arguments_digest(value)
        with self.assertRaises(wire.ProtocolError):
            wire.parse_request(wire.canonical_bytes(value))

    def test_window_scoped_view_resolves_pid_from_original_native_window(self):
        calls, original = [], self.health.resolve_window
        def resolve(window_id):
            calls.append(window_id)
            return original(window_id)
        self.health.resolve_window = resolve
        self.configure()
        self.assertEqual([456], calls)
        self.assertEqual({"pid": 123, "windowId": 456}, self.viewer.sessions["native-viewer-1"].target)
        described = self.runtime.describe()
        self.assertIn("streaming", described)
        self.assertEqual(["semantic", "visual", "hybrid"], described["observationModes"])
        self.assertEqual("semantic", described["defaultWindowObservationMode"])

    def test_complete_input_is_claimed_once_and_uses_exact_native_capture(self):
        view = self.take(self.open())
        request = self.input_envelope(view)
        first = self.invoke(request)
        self.assertEqual(first, self.invoke(request))
        self.assertEqual({"status": "completed", "inputDispatched": True, "dispatchConfirmed": True,
                          "inputDispatchAttempted": True, "remoteEffectsVerified": False}, first["inputReceipt"])
        inputs = [args for name, args in self.driver.calls if name == "click"]
        self.assertEqual(1, len(inputs))
        self.assertEqual((40, 60, "fixture-native-capture"), (inputs[0]["x"], inputs[0]["y"], inputs[0]["capture_id"]))
        self.assertEqual("foreground", inputs[0]["delivery_mode"])
        self.assertEqual("HELD", self.lease_state())
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_allocations").fetchone()[0])
        stored = json.loads(self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (request.get("operationId"),)).fetchone()[0])
        self.assertTrue(stored["executionSettlement"]["noFurtherDispatchForOperation"])
        self.assertTrue(stored["inputDispatchAttempted"])
        self.assertNotIn("frameRef", stored)
        self.assertFalse(self.streams.sessions[view["streamId"]].frames)

    def test_dwm_border_crop_is_mapped_from_full_window_stream(self):
        self.health.value["window"]["frameBounds"] = [25, 35, 335, 225]
        self.driver.crop = True
        view = self.take(self.open())
        self.invoke(self.input_envelope(view))
        click = next(args for name, args in self.driver.calls if name == "click")
        self.assertEqual((35, 55), (click["x"], click["y"]))
        with self.assertRaises(DesktopError):
            self.invoke(self.input_envelope(view, {"kind": "click", "x": 0, "y": 0, "button": "left", "count": 1}))
        self.assertEqual(1, sum(name == "click" for name, _ in self.driver.calls))

    def test_handoff_retires_model_and_viewer_observations(self):
        view = self.open()
        old = self.frame(view)
        self.adapter.snapshots["fixture-ai-observation"] = {"fixture": True}
        self.adapter.refusals["fixture-refusal"] = {"fixture": True}
        taken = self.take(view)
        self.assertFalse(self.adapter.snapshots)
        self.assertFalse(self.adapter.refusals)
        with self.assertRaises(DesktopStreamError):
            self.invoke(self.envelope("input", streamId=view["streamId"], controlLease=taken["control"]["lease"], frameRef=old,
                                     input={"kind": "press_key", "key": "enter"}))
        fresh = self.input_envelope(taken, {"kind": "press_key", "key": "enter"})
        self.invoke(fresh)
        self.assertEqual("return", next(args for name, args in self.driver.calls if name == "press_key")["key"])

    def test_idle_agent_lease_transfers_but_dispatched_unsettled_operation_does_not(self):
        agent = self.adapter.control("acquire", self.binding, self.a.device_id, {"action": "acquire"}, lambda: None)["lease"]
        view = self.open()
        self.assertEqual("agent", view["control"]["owner"])
        self.manager.jobs["fixture-agent-input"] = {"protocol": "desktop-v1", "desktopOperation": {"resourceBinding": self.binding, "toolName": "desktop.act"}, "desktopDispatchAttempted": True}
        with self.assertRaises(DesktopError):
            self.take(view)
        self.manager.jobs["fixture-agent-input"]["executionSettlement"] = {"noFurtherDispatchForOperation": True}
        taken = self.take(view)
        self.assertEqual("human", taken["control"]["owner"])
        self.assertGreater(taken["control"]["revision"], agent["fence"])
        with self.assertRaises(DesktopError):
            self.adapter._lease(agent["resource"], self.a.device_id, agent, {"HELD"})
        del self.manager.jobs["fixture-agent-input"]

    def test_unknown_input_blocks_take_and_release_or_close_does_not_clear_reconcile(self):
        view = self.take(self.open())
        def failed(*_args):
            raise OSError("fixture driver lost reply after possible input")
        self.driver.action = failed
        request = self.input_envelope(view)
        result = self.invoke(request)
        self.assertEqual("unknown", result["inputReceipt"]["status"])
        self.assertFalse(result["inputReceipt"]["dispatchConfirmed"])
        self.assertTrue(result["inputReceipt"]["inputDispatchAttempted"])
        self.assertEqual("human", result["control"]["owner"])
        self.assertEqual(view["control"]["lease"], result["control"]["lease"])
        self.assertTrue(result["control"]["needsReconcile"])
        self.assertEqual(result, self.invoke(request))
        with self.assertRaises(DesktopError):
            self.take(result)
        self.assertEqual("NEEDS_RECONCILE", self.lease_state())
        released = self.invoke(self.envelope("release", streamId=view["streamId"], expectedRevision=result["control"]["revision"]))
        self.assertEqual("none", released["control"]["owner"])
        self.assertTrue(released["control"]["needsReconcile"])
        status = self.invoke(self.envelope("status", streamId=view["streamId"]))
        self.assertTrue(status["control"]["needsReconcile"])
        with self.assertRaises(DesktopError):
            self.take(status)
        self.assertEqual("NEEDS_RECONCILE", self.lease_state())
        self.invoke(self.envelope("close", streamId=view["streamId"]))
        self.assertEqual("NEEDS_RECONCILE", self.lease_state())
        self.assertEqual(1, sum(name == "click" for name, _ in self.driver.calls))
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_manual_and_agent_inputs_share_the_original_finite_grant_pool(self):
        self.grant.update(authorizationEpoch=2, toolBudget=3)
        self.trust.replace_grant(self.grant, self.manager.now())
        view = self.take(self.open())
        self.invoke(self.input_envelope(view))
        released = self.invoke(self.envelope("release", streamId=view["streamId"], expectedRevision=view["control"]["revision"]))
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        agent = self.finish_agent(self.invoke_agent(self.agent_envelope()))
        self.assertEqual("completed", agent["status"], agent)
        status = self.invoke(self.envelope("status", streamId=released["streamId"]))
        view = self.take(status)
        self.invoke(self.input_envelope(view))
        self.assertEqual([(3, 3)], self.manager.ledger.execute("SELECT tool_limit,tool_spent FROM device_budget_pools").fetchall())
        self.assertEqual([(1,)], self.manager.ledger.execute("SELECT tool_spent FROM device_budget_allocations").fetchall())
        with self.assertRaisesRegex(DesktopError, "exhausted"):
            self.invoke(self.input_envelope(view))
        with self.assertRaisesRegex(DesktopError, "exhausted"):
            self.invoke_agent(self.agent_envelope(parent="new-budget-parent"))
        self.assertEqual(2, sum(name == "click" for name, _ in self.driver.calls))

    def test_input_budget_and_original_operation_claim_roll_back_together(self):
        view = self.take(self.open())
        request = self.input_envelope(view)
        with self.manager.lock:
            self.manager.ledger.execute("CREATE TEMP TRIGGER reject_viewer_claim BEFORE INSERT ON operations WHEN NEW.operation_id='" + request.get("operationId") + "' BEGIN SELECT RAISE(ABORT, 'fixture original operation insert failure'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            self.invoke(request)
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM device_budget_pools").fetchone()[0])
        self.assertIsNone(self.manager.ledger.execute("SELECT 1 FROM operations WHERE operation_id=?", (request.get("operationId"),)).fetchone())
        self.assertFalse(any(name == "click" for name, _ in self.driver.calls))
        self.assertFalse(self.manager.ledger.in_transaction)

    def test_spent_pool_survives_manager_restart_new_viewer_and_new_grant_epoch(self):
        self.grant.update(authorizationEpoch=2, toolBudget=1)
        self.trust.replace_grant(self.grant, self.manager.now())
        view = self.take(self.open())
        original_request = self.input_envelope(view)
        self.invoke(original_request)
        original_lease = copy.deepcopy(view["control"]["lease"])
        self.receiver.close()
        self.manager.close()
        self.manager = JobManager({"stateDir": str(Path(self.temp.name) / "state"), "environments": []}, "test-only-viewer-token-000000000000000000000000")
        self.receiver = CompanionDeviceReceiver(self.manager, self.b, native_management_only=True)
        self.coordinator, self.trust = self.receiver.pairing, self.receiver.trust
        self.attach_runtime()
        self.assertEqual([(1, 1)], self.manager.ledger.execute("SELECT tool_limit,tool_spent FROM device_budget_pools").fetchall())
        with self.assertRaises(DesktopError):
            self.invoke(original_request)
        self.grant.update(authorizationEpoch=3, toolBudget=10)
        self.trust.replace_grant(self.grant, self.manager.now())
        new_view = self.open(session_id="after-original-runtime-restart")
        request = self.envelope("input", session_id="after-original-runtime-restart", streamId=new_view["streamId"],
                                controlLease=original_lease, frameRef=self.frame(new_view), input={"kind": "press_key", "key": "tab"})
        with self.assertRaisesRegex(DesktopError, "exhausted"):
            self.invoke(request)
        with self.assertRaisesRegex(DesktopError, "exhausted"):
            self.invoke_agent(self.agent_envelope(parent="after-restart-parent"))
        self.assertEqual([(1, 1)], self.manager.ledger.execute("SELECT tool_limit,tool_spent FROM device_budget_pools").fetchall())
        self.assertFalse(self.driver.calls)

    def test_close_releases_only_its_own_safe_human_lease(self):
        first = self.take(self.open())
        second = self.open(session_id="native-viewer-2")
        self.assertEqual("other_human", second["control"]["owner"])
        self.assertNotIn("lease", second["control"])
        with self.assertRaisesRegex(DesktopError, "Another native viewer"):
            self.take(second, session_id="native-viewer-2")
        self.invoke(self.envelope("close", session_id="native-viewer-2", streamId=second["streamId"]))
        self.assertEqual("HELD", self.lease_state())
        closed = self.invoke(self.envelope("close"))
        self.assertEqual("closed", closed["status"])
        self.assertEqual("RELEASED", self.lease_state())
        self.assertEqual("closed", self.streams.sessions[first["streamId"]].state)

    def test_other_human_status_becomes_none_after_exact_owner_releases(self):
        first = self.take(self.open())
        second = self.open(session_id="native-viewer-2")
        self.assertEqual("other_human", second["control"]["owner"])
        self.invoke(self.envelope("release", streamId=first["streamId"], expectedRevision=first["control"]["revision"]))
        refreshed = self.invoke(self.envelope("status", session_id="native-viewer-2", streamId=second["streamId"]))
        self.assertEqual("none", refreshed["control"]["owner"])
        taken = self.take(refreshed, session_id="native-viewer-2")
        self.assertEqual("human", taken["control"]["owner"])
        self.assertNotEqual(first["control"]["lease"]["id"], taken["control"]["lease"]["id"])

    def test_stream_close_busy_lock_is_retired_by_original_watchdog_once(self):
        view = self.take(self.open())
        entered, unlock = threading.Event(), threading.Event()
        def busy_observer():
            with self.adapter.lock:
                entered.set()
                unlock.wait(3)
        thread = threading.Thread(target=busy_observer)
        thread.start()
        self.assertTrue(entered.wait(1))
        try:
            stream = self.streams.sessions[view["streamId"]]
            self.streams._call(self.streams._end(stream, "closed", "fixture-peer-close"))
            self.assertEqual("HELD", self.lease_state())
        finally:
            unlock.set()
            thread.join(2)
        end = time.monotonic() + 2
        while self.lease_state() != "RELEASED" and time.monotonic() < end:
            time.sleep(0.01)
        self.assertEqual("RELEASED", self.lease_state())
        self.adapter.snapshots["new-agent-observation"] = {"fixture": True}
        self.viewer.cleanup()
        self.assertIn("new-agent-observation", self.adapter.snapshots)

    def test_revocation_stops_stream_and_retires_safe_lease(self):
        view = self.take(self.open())
        self.frame(view)
        self.coordinator.revoke_locally(self.a.device_id, self.manager.now())
        end = time.monotonic() + 2
        stream = self.streams.sessions[view["streamId"]]
        while stream.state not in {"expired", "closed", "failed"} and time.monotonic() < end:
            time.sleep(0.01)
        self.assertEqual("expired", stream.state)
        self.assertFalse(stream.frames)
        self.assertEqual("RELEASED", self.lease_state())

    def test_local_stop_during_input_preserves_pause_fence(self):
        view = self.take(self.open())
        def stop(*_args):
            self.adapter.local_stop()
            return {"structuredContent": {"fixture": True}}
        self.driver.action = stop
        result = self.invoke(self.input_envelope(view))
        self.assertEqual("unknown", result["inputReceipt"]["status"])
        self.assertEqual("PAUSED", self.lease_state())
        self.assertFalse(self.streams.sessions[view["streamId"]].frames)

    def test_local_input_changed_after_frame_is_rejected_without_dispatch(self):
        view = self.take(self.open())
        request = self.input_envelope(view)
        self.health.value["lastInputTick"] += 1
        with self.assertRaises(DesktopError):
            self.invoke(request)
        self.assertFalse(any(name == "click" for name, _ in self.driver.calls))

    def test_primary_keys_and_scroll_use_actual_foreground_scope(self):
        view = self.take(self.open(binding=self.primary), binding=self.primary)
        for event in ({"kind": "insert_text", "text": "fixture private text"}, {"kind": "hotkey", "keys": ["CTRL", "A"]},
                      {"kind": "scroll", "direction": "down", "amount": 2, "by": "line"}):
            self.invoke(self.input_envelope(view, event, binding=self.primary))
        text = next(args for name, args in self.driver.calls if name == "type_text")
        scroll = next(args for name, args in self.driver.calls if name == "scroll")
        self.assertEqual("desktop", text["scope"])
        self.assertEqual(("window", 123, 456), (scroll["scope"], scroll["pid"], scroll["window_id"]))
        self.assertNotIn("fixture private text", str(self.manager.ledger.execute("SELECT details FROM operations").fetchall()))

    def test_lost_runtime_or_lost_operation_reply_never_replays_input(self):
        view = self.take(self.open())
        request = self.input_envelope(view)
        self.invoke(request)
        self.viewer.replies.clear()
        with self.assertRaises(DesktopError):
            self.invoke(request)
        self.assertEqual(1, sum(name == "click" for name, _ in self.driver.calls))
        self.viewer.sessions.clear()
        with self.assertRaises(DesktopError):
            self.configure()

    def legacy_claims(self, rows):
        with self.manager.lock:
            self.manager.ledger.execute("CREATE TABLE desktop_viewer_operations(operation_id TEXT PRIMARY KEY,digest TEXT NOT NULL,owner TEXT NOT NULL,session_id TEXT NOT NULL,action TEXT NOT NULL,state TEXT NOT NULL,deadline INTEGER NOT NULL,receipt TEXT)")
            self.manager.ledger.executemany("INSERT INTO desktop_viewer_operations VALUES (?,?,?,?,?,?,?,?)", rows)
            self.manager.ledger.commit()

    def test_legacy_completed_unknown_and_session_claims_survive_idempotent_migration(self):
        owner = self.a.device_id + ":viewer-grant:1"
        rows = [("legacy-" + state, "a" * 64, owner, "legacy-session-" + state, "input", state, self.now + 1000,
                 json.dumps({"fixtureSecret": "never-copy-legacy-receipt"})) for state in ("completed", "unknown")]
        self.legacy_claims(rows)
        lease = ("windows-session:1:primary", self.a.device_id, "old-original-instance", "b" * 32, 7, self.now + 1000, "NEEDS_RECONCILE")
        self.manager.ledger.execute("INSERT INTO desktop_resource_leases VALUES (?,?,?,?,?,?,?)", lease)
        self.manager.ledger.commit()
        for _ in range(2):
            self.viewer = self.runtime.viewers = DesktopViewer(self.jobs, streams=self.streams)
        retained = self.manager.ledger.execute("SELECT operation_id,fingerprint,last_status,details FROM operations ORDER BY operation_id").fetchall()
        self.assertEqual(2, len(retained))
        self.assertEqual(lease, self.manager.ledger.execute("SELECT * FROM desktop_resource_leases").fetchone())
        self.assertEqual(rows, self.manager.ledger.execute("SELECT * FROM desktop_viewer_operations ORDER BY operation_id").fetchall())
        for operation, digest, state, raw in retained:
            details = json.loads(raw)
            self.assertEqual("a" * 64, digest)
            self.assertEqual(state, details["legacyState"])
            self.assertTrue(details["noAutomaticReplay"])
            self.assertNotIn("executionSettlement", details)
            self.assertNotIn("inputDispatched", details)
            self.assertNotIn("never-copy-legacy-receipt", raw)
            with self.assertRaises(DesktopError):
                self.configure(session_id="legacy-session-" + state)
        view = self.configure(session_id="fresh-after-main-upgrade")
        self.assertEqual("none", view["control"]["owner"])
        self.assertTrue(view["control"]["needsReconcile"])
        for state in ("completed", "unknown"):
            reused = self.envelope("status", session_id="fresh-after-main-upgrade").json()
            reused["operationId"] = "legacy-" + state
            reused["argumentsDigest"] = wire.arguments_digest(reused)
            with self.assertRaises(DesktopError):
                self.invoke(wire.parse_request(wire.canonical_bytes(reused)))
        self.assertFalse(self.driver.calls)

    def test_legacy_operation_collision_rolls_back_the_whole_migration(self):
        owner = self.a.device_id + ":viewer-grant:1"
        rows = [(operation, "a" * 64, owner, "legacy-session", "input", "completed", self.now + 1000, None)
                for operation in ("aaa-legacy", "zzz-collision")]
        self.legacy_claims(rows)
        existing = ("zzz-collision", "b" * 64, "original-other-job", "unknown", self.now, json.dumps({"protocol": "original-other-operation"}))
        self.manager.ledger.execute("INSERT INTO operations VALUES (?,?,?,?,?,?)", existing)
        self.manager.ledger.commit()
        with self.assertRaisesRegex(DesktopError, "conflicts"):
            DesktopViewer(self.jobs, streams=self.streams)
        self.assertEqual([existing], self.manager.ledger.execute("SELECT * FROM operations").fetchall())
        self.assertEqual(rows, self.manager.ledger.execute("SELECT * FROM desktop_viewer_operations ORDER BY operation_id").fetchall())
        self.assertFalse(self.manager.ledger.in_transaction)
        self.assertFalse(self.driver.calls)


if __name__ == "__main__":
    unittest.main()
