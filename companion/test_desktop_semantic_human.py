"""Migrated main semantic/human contracts on the original viewer/lease authority.

The legacy acquire_human/human_act table path is replaced by authenticated
DesktopViewer ingress. These are GUI boundary fixtures, not Windows acceptance.
"""
import threading
import unittest

from desktop_adapter import DesktopError
from desktop_stream import DesktopStreamError
import device_protocol as wire
import test_desktop_adapter as fixtures
import test_desktop_viewer as viewer_fixtures


class SemanticContractTests(unittest.TestCase):
    setUp = fixtures.DesktopContractTests.setUp
    tearDown = fixtures.DesktopContractTests.tearDown
    lease = fixtures.DesktopContractTests.lease
    action = fixtures.DesktopContractTests.action

    def semantic(self):
        return self.adapter.observe(self.binding, {"pid": 123, "windowId": 456}, self.metadata, self.current)

    def test_window_default_is_structure_only_and_semantic_set_value_never_captures(self):
        observation, lease = self.semantic(), self.lease()
        self.assertEqual("semantic", observation["observationMode"])
        self.assertEqual("uia_elements", observation["coordinateSpace"])
        for key in ("image", "capture", "captureSha256"):
            self.assertNotIn(key, observation)
        self.assertNotIn("screenshot_width", observation["state"])
        self.assertEqual([], self.captures)
        receipt = self.action(observation, lease, observationMode="semantic")
        self.assertEqual("completed", receipt["status"])
        self.assertEqual("semantic", receipt["afterObservation"]["observationMode"])
        self.assertEqual([], self.captures)
        self.assertTrue(all(args.get("include_screenshot") is False for tool, args in self.driver.calls if tool == "get_window_state"))
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_semantic_observation_cannot_be_widened_to_visual_or_keystroke_input(self):
        observation, lease = self.semantic(), self.lease()
        for args in ({"kind": "click", "x": 0, "y": 0}, {"kind": "insert_text", "text": "x"},
                     {"kind": "press_key", "key": "tab"}, {"kind": "scroll", "direction": "down"},
                     {"kind": "click", "elementToken": observation["state"]["elements"][0]["element_token"]}):
            with self.subTest(args=args), self.assertRaises(DesktopError):
                self.adapter.act(self.binding, observation["observationRef"], "peer-A", {**args, "lease": lease}, self.metadata, self.current, lambda: self.dispatched.append(True))
        with self.assertRaises(DesktopError):
            self.action(observation, lease, observationMode="hybrid")
        self.assertEqual([], self.dispatched)

    def test_semantic_preflight_rejects_changed_live_value_and_unknown_after_dispatch_is_fenced(self):
        observation, lease = self.semantic(), self.lease()
        self.driver.value = "local change without input tick fixture"
        with self.assertRaises(DesktopError):
            self.action(observation, lease)
        self.assertEqual([], self.dispatched)
        observation = self.semantic()
        self.driver.fail_action = True
        receipt = self.action(observation, lease)
        self.assertEqual("unknown", receipt["status"])
        with self.assertRaises(DesktopError):
            self.lease()
        self.assertEqual("NEEDS_RECONCILE", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def test_visual_mode_has_png_but_no_element_tree(self):
        value = self.adapter.observe(self.binding, {"pid": 123, "windowId": 456, "observationMode": "visual"}, self.metadata, self.current)
        self.assertEqual("visual", value["observationMode"])
        self.assertIn("capture", value)
        self.assertNotIn("elements", value["state"])
        call = next(args for tool, args in self.driver.calls if tool == "get_window_state")
        self.assertFalse(call["include_accessibility_tree"])

class HumanViewerContractTests(unittest.TestCase):
    setUp = viewer_fixtures.DesktopViewerIntegrationTests.setUp
    tearDown = viewer_fixtures.DesktopViewerIntegrationTests.tearDown
    attach_runtime = viewer_fixtures.DesktopViewerIntegrationTests.attach_runtime
    envelope = viewer_fixtures.DesktopViewerIntegrationTests.envelope
    invoke = viewer_fixtures.DesktopViewerIntegrationTests.invoke
    configure = viewer_fixtures.DesktopViewerIntegrationTests.configure
    open = viewer_fixtures.DesktopViewerIntegrationTests.open
    take = viewer_fixtures.DesktopViewerIntegrationTests.take
    frame = viewer_fixtures.DesktopViewerIntegrationTests.frame
    input_envelope = viewer_fixtures.DesktopViewerIntegrationTests.input_envelope
    lease_state = viewer_fixtures.DesktopViewerIntegrationTests.lease_state

    def semantic_agent(self):
        # Retain main's native semantic fixture for the actual Adapter act path;
        # viewer admission/stream/ledger stay the real paired production objects.
        self.driver = fixtures.ContractDriver()
        self.adapter.driver = self.driver
        metadata = {"taskId": "agent-task", "requestRunId": "agent-run", "requestExecutorEpoch": 1, "operationId": "agent-input"}
        observation = self.adapter.observe(self.binding, {"pid": 123, "windowId": 456}, metadata, lambda: None)
        lease = self.adapter.control("acquire", self.binding, "agent-fixture", {"action": "acquire"}, lambda: None)["lease"]
        args = {"kind": "set_text", "lease": lease, "elementToken": observation["state"]["elements"][0]["element_token"],
                "text": "changed fixture", "verification": {"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": "changed fixture"}}
        return observation, lease, lambda: self.adapter.act(self.binding, observation["observationRef"], "agent-fixture", args, metadata, lambda: None, lambda: None)

    def test_human_takeover_fences_idle_ai_and_streams_cannot_borrow_control(self):
        observed, ai, act = self.semantic_agent()
        view = self.take(self.open())
        self.assertGreater(view["control"]["revision"], ai["fence"])
        self.assertEqual({}, self.adapter.snapshots)
        with self.assertRaises(DesktopError): act()
        with self.assertRaises(DesktopError):
            self.adapter.control("acquire", self.binding, "agent-fixture", {"action": "acquire"}, lambda: None)
        second = self.open(session_id="another-native-viewer")
        self.assertEqual("other_human", second["control"]["owner"])
        with self.assertRaises(DesktopError): self.take(second, session_id="another-native-viewer")
        with self.assertRaises(DesktopError):
            self.invoke(self.envelope("input", session_id="another-native-viewer", streamId=second["streamId"], controlLease=view["control"]["lease"],
                                     frameRef=self.frame(second), input={"kind": "press_key", "key": "tab"}))
        self.invoke(self.envelope("release", streamId=view["streamId"], expectedRevision=view["control"]["revision"]))
        new_ai = self.adapter.control("acquire", self.binding, "agent-fixture", {"action": "acquire"}, lambda: None)["lease"]
        self.assertGreater(new_ai["fence"], view["control"]["revision"])
        with self.assertRaises(DesktopError): act()
        self.assertNotIn(observed["observationRef"]["id"], self.adapter.snapshots)

    def test_human_waits_for_inflight_ai_action_and_refuses_its_unknown_outcome(self):
        _, _, act = self.semantic_agent()
        view = self.open()
        entered, finish, started, done = (threading.Event() for _ in range(4))
        original = self.driver.call
        def blocked(tool, args):
            if tool == "set_value":
                entered.set()
                if not finish.wait(3): raise RuntimeError("Fixture input wait expired")
                self.driver.fail_action = True
            return original(tool, args)
        self.driver.call = blocked
        results = []
        ai = threading.Thread(target=lambda: results.append(act()))
        def take():
            started.set()
            try:
                results.append(self.take(view))
            except DesktopError:
                results.append("refused")
            finally:
                done.set()
        ai.start()
        try:
            self.assertTrue(entered.wait(2))
            takeover = threading.Thread(target=take)
            takeover.start()
            self.assertTrue(started.wait(2))
            self.assertEqual("EXECUTING", self.lease_state())
            self.assertFalse(done.is_set(), "Human ownership cannot transfer while the actual Agent driver call is in flight")
        finally:
            finish.set()
            ai.join(2)
        takeover.join(2)
        self.assertFalse(ai.is_alive())
        self.assertFalse(takeover.is_alive())
        self.assertEqual("unknown", next(value for value in results if isinstance(value, dict))["status"])
        self.assertIn("refused", results)
        self.assertEqual("NEEDS_RECONCILE", self.lease_state())
        status = self.invoke(self.envelope("status", streamId=view["streamId"]))
        self.assertTrue(status["control"]["needsReconcile"])
        self.assertEqual("agent", status["control"]["owner"])

    def test_human_click_uses_recent_geometry_and_real_capture_identity_once(self):
        self.streams.max_edge = 160
        view = self.take(self.open())
        request = self.input_envelope(view, {"kind": "click", "x": 80, "y": 25, "button": "left", "count": 2})
        result = self.invoke(request)
        self.assertEqual("completed", result["inputReceipt"]["status"])
        self.assertFalse(result["inputReceipt"]["remoteEffectsVerified"])
        self.assertEqual(result, self.invoke(request))
        clicks = [args for tool, args in self.driver.calls if tool == "click"]
        self.assertEqual(1, len(clicks))
        self.assertEqual((160, 50, 2), (clicks[0]["x"], clicks[0]["y"], clicks[0]["count"]))
        self.assertEqual("foreground", clicks[0]["delivery_mode"])
        self.assertEqual("fixture-native-capture", clicks[0]["capture_id"])

    def test_human_stale_frame_or_changed_input_refuses_before_dispatch(self):
        view = self.take(self.open())
        request = self.input_envelope(view, {"kind": "press_key", "key": "enter"})
        original_clock = self.streams.clock
        self.streams.clock = lambda: original_clock() + 2100
        with self.assertRaises(DesktopStreamError): self.invoke(request)
        self.streams.clock = original_clock
        request = self.input_envelope(view, {"kind": "press_key", "key": "enter"})
        self.health.value["lastInputTick"] += 1
        with self.assertRaises(DesktopError): self.invoke(request)
        self.assertEqual([], self.driver.calls)

    def test_human_invalid_input_is_not_dispatched_and_key_contract_is_exact(self):
        self.grant.update(authorizationEpoch=2, toolBudget=25)
        self.trust.replace_grant(self.grant, self.manager.now())
        view = self.take(self.open())
        for bad in ({"kind": "press_key", "key": "ARROWUP"}, {"kind": "click", "x": 320, "y": 0, "count": 1, "button": "left"},
                    {"kind": "click", "x": 0, "y": 0, "count": 3, "button": "left"}, {"kind": "scroll", "direction": "up", "amount": 21, "by": "line"},
                    {"kind": "insert_text", "text": "\x00"}, {"kind": "hotkey", "keys": ["CTRL", "A", "B"]},
                    {"kind": "hotkey", "keys": ["CTRL", "ctrl", "A"]}, {"kind": "set_text", "text": "x"},
                    {"kind": "hotkey", "keys": ["CTRL", "ALT", "SHIFT", "WIN", "A"]},
                    {"kind": "press_key", "key": "enter", "elementToken": "s00000001:0"}):
            with self.subTest(bad=bad), self.assertRaises((DesktopError, wire.ProtocolError)):
                self.invoke(self.input_envelope(view, bad))
        self.assertEqual([], self.driver.calls)
        self.assertEqual("completed", self.invoke(self.input_envelope(view, {"kind": "hotkey", "keys": ["CTRL", "A"]}))["inputReceipt"]["status"])
        self.assertEqual(["ctrl", "a"], [args for tool, args in self.driver.calls if tool == "hotkey"][-1]["keys"])
        self.assertEqual("completed", self.invoke(self.input_envelope(view, {"kind": "press_key", "key": "enter"}))["inputReceipt"]["status"])
        self.assertEqual("return", [args for tool, args in self.driver.calls if tool == "press_key"][-1]["key"])

    def test_human_unknown_is_not_released_renewed_or_replayed(self):
        first = self.take(self.open())
        second = self.open(session_id="another-native-viewer")
        def failed(*args): raise RuntimeError("fixture response lost after possible native input")
        self.driver.action = failed
        request = self.input_envelope(first, {"kind": "insert_text", "text": "once"})
        result = self.invoke(request)
        self.assertEqual("unknown", result["inputReceipt"]["status"])
        self.assertFalse(result["inputReceipt"]["dispatchConfirmed"])
        self.assertTrue(result["inputReceipt"]["inputDispatchAttempted"])
        self.assertEqual(result, self.invoke(request))
        for action in (lambda: self.invoke(self.input_envelope(first, {"kind": "insert_text", "text": "once"})), lambda: self.take(result)):
            with self.assertRaises(DesktopError): action()
        other = self.invoke(self.envelope("status", session_id="another-native-viewer", streamId=second["streamId"]))
        self.assertEqual("other_human", other["control"]["owner"])
        self.assertTrue(other["control"]["needsReconcile"])
        self.assertNotIn("lease", other["control"])
        with self.assertRaises(DesktopError): self.take(other, session_id="another-native-viewer")
        released = self.invoke(self.envelope("release", streamId=first["streamId"], expectedRevision=result["control"]["revision"]))
        self.assertEqual("none", released["control"]["owner"])
        self.assertTrue(released["control"]["needsReconcile"])
        other = self.invoke(self.envelope("status", session_id="another-native-viewer", streamId=second["streamId"]))
        self.assertEqual("none", other["control"]["owner"])
        self.assertTrue(other["control"]["needsReconcile"])
        self.assertEqual("NEEDS_RECONCILE", self.lease_state())
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_idle_human_release_after_lock_uses_durable_identity_not_live_gui(self):
        first = self.take(self.open())
        second = self.open(session_id="another-native-viewer")
        with self.adapter.lock:
            self.health.value["available"] = False
            self.invoke(self.envelope("close", session_id="another-native-viewer", streamId=second["streamId"]))
            self.assertEqual("HELD", self.lease_state())
            # Session close is the explicit terminal owner route after a lock;
            # it can retire a settled HELD lease without requesting GUI input.
            closed = self.invoke(self.envelope("close", streamId=first["streamId"]))
            self.assertEqual("closed", closed["status"])
            self.assertEqual("RELEASED", self.lease_state())
        self.health.value["available"] = True
        new = self.take(self.open(session_id="after-lock-viewer"), session_id="after-lock-viewer")
        self.assertGreater(new["control"]["revision"], first["control"]["revision"])

    def test_locked_desktop_cannot_release_executing_or_unknown_human_input(self):
        view = self.take(self.open())
        for state in ("EXECUTING", "NEEDS_RECONCILE"):
            with self.subTest(state=state), self.adapter.lock:
                self.health.value["available"] = False
                with self.manager.lock:
                    self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=?", (state,))
                    self.manager.ledger.commit()
                self.invoke(self.envelope("close", streamId=view["streamId"]))
                self.viewer.cleanup()
                self.assertEqual(state, self.lease_state())

    def test_local_stop_during_human_input_preserves_pause_fence(self):
        view = self.take(self.open())
        def stopped(*args):
            self.adapter.local_stop()
            return {"structuredContent": {"fixture": True}}
        self.driver.action = stopped
        result = self.invoke(self.input_envelope(view, {"kind": "insert_text", "text": "once"}))
        self.assertEqual("unknown", result["inputReceipt"]["status"])
        self.assertEqual("PAUSED", self.lease_state())
        self.assertFalse(self.streams.sessions[view["streamId"]].frames)

    def test_desktop_scroll_requires_current_geometry_and_original_foreground_window(self):
        view = self.take(self.open(binding=self.primary), binding=self.primary)
        scroll = {"kind": "scroll", "direction": "down", "amount": 1, "by": "line"}
        request = self.input_envelope(view, scroll, binding=self.primary)
        self.health.value["foregroundWindowId"] += 1
        with self.assertRaises(DesktopError): self.invoke(request)
        self.health.value["foregroundWindowId"] -= 1
        request = self.input_envelope(view, scroll, binding=self.primary)
        bounds = self.health.value["displays"][0]["bounds"]
        before = list(bounds)
        bounds[2] -= 20
        with self.assertRaises(DesktopStreamError): self.invoke(request)
        bounds[:] = before
        # The shared wire no longer accepts pointer x/y or a stored last click.
        # The real pinned foreground HWND route provides native scroll targeting.
        with self.assertRaises(DesktopError): self.invoke(self.input_envelope(view, {**scroll, "x": 100, "y": 100}, binding=self.primary))
        result = self.invoke(self.input_envelope(view, scroll, binding=self.primary))
        self.assertEqual("completed", result["inputReceipt"]["status"])
        sent = [args for tool, args in self.driver.calls if tool == "scroll"]
        self.assertEqual(1, len(sent))
        self.assertEqual(("window", 123, 456), (sent[0]["scope"], sent[0]["pid"], sent[0]["window_id"]))
        self.assertNotIn("x", sent[0])
        self.assertNotIn("y", sent[0])


if __name__ == "__main__":
    unittest.main()
