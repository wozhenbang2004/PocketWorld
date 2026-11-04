"""Hermetic lease/observation contracts, not physical Windows GUI acceptance."""
import threading
import unittest

from desktop_adapter import DesktopError
import test_desktop_adapter as fixtures


class SemanticAndHumanContractTests(unittest.TestCase):
    setUp = fixtures.DesktopContractTests.setUp
    tearDown = fixtures.DesktopContractTests.tearDown
    lease = fixtures.DesktopContractTests.lease
    action = fixtures.DesktopContractTests.action
    observe = fixtures.DesktopContractTests.observe
    stream_id = "a" * 32

    def semantic(self):
        return self.adapter.observe(self.binding, {"pid": 123, "windowId": 456}, self.metadata, self.current)

    def geometry(self, target=True):
        health = self.health.probe(123, 456) if target else self.health.probe()
        return {"target": {"pid": 123, "windowId": 456} if target else None,
                "healthBinding": health, "captureBounds": [10, 10, 600, 600] if target else [0, 0, 1920, 1080],
                "width": 590 if target else 1200, "height": 590 if target else 675,
                "geometryRevision": "fixture-geometry", "frameId": "f" * 32,
                "capturedAt": self.now, "expiresAt": self.now + 2000}

    def human(self):
        return self.adapter.acquire_human(self.binding, "peer-A", self.stream_id, self.current)["lease"]

    def human_input(self, lease, value, geometry=None, stream_id=None):
        return self.adapter.human_act(self.binding, "peer-A", stream_id or self.stream_id, lease, value,
                                      geometry or self.geometry(), self.current, lambda: self.dispatched.append(True))

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
            self.human()
        self.assertEqual("NEEDS_RECONCILE", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def test_visual_mode_has_png_but_no_element_tree(self):
        value = self.adapter.observe(self.binding, {"pid": 123, "windowId": 456, "observationMode": "visual"}, self.metadata, self.current)
        self.assertEqual("visual", value["observationMode"])
        self.assertIn("capture", value)
        self.assertNotIn("elements", value["state"])
        call = next(args for tool, args in self.driver.calls if tool == "get_window_state")
        self.assertFalse(call["include_accessibility_tree"])

    def test_human_takeover_fences_idle_ai_and_streams_cannot_borrow_control(self):
        ai, observed = self.lease(), self.observe()
        human = self.human()
        self.assertGreater(human["fence"], ai["fence"])
        self.assertEqual({}, self.adapter.snapshots)
        with self.assertRaises(DesktopError): self.action(observed, ai)
        with self.assertRaises(DesktopError): self.lease()
        with self.assertRaises(DesktopError):
            self.adapter.acquire_human(self.binding, "peer-A", "b" * 32, self.current)
        with self.assertRaises(DesktopError):
            self.human_input(human, {"kind": "press_key", "key": "tab"}, stream_id="b" * 32)
        released = self.adapter.release_human(self.binding, "peer-A", self.stream_id, human, self.current)
        self.assertTrue(released["requiresFreshObservation"])
        self.assertGreater(self.lease()["fence"], human["fence"])

    def test_human_waits_for_inflight_ai_action_and_refuses_its_unknown_outcome(self):
        lease, observed = self.lease(), self.observe()
        entered, finish, started = threading.Event(), threading.Event(), threading.Event()
        original = self.driver.call
        def blocked(tool, args):
            if tool == "set_value":
                entered.set()
                self.assertTrue(finish.wait(2))
                self.driver.fail_action = True
            return original(tool, args)
        self.driver.call = blocked
        results = []
        ai = threading.Thread(target=lambda: results.append(self.action(observed, lease)))
        def take():
            started.set()
            try:
                results.append(self.human())
            except DesktopError:
                results.append("refused")
        ai.start(); self.assertTrue(entered.wait(2))
        takeover = threading.Thread(target=take); takeover.start(); self.assertTrue(started.wait(2))
        self.assertEqual("EXECUTING", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
        finish.set(); ai.join(2); takeover.join(2)
        self.assertFalse(ai.is_alive()); self.assertFalse(takeover.is_alive())
        self.assertEqual("unknown", next(result for result in results if isinstance(result, dict))["status"])
        self.assertIn("refused", results)

    def test_human_click_uses_recent_geometry_and_real_capture_identity_once(self):
        human = self.human()
        result = self.human_input(human, {"kind": "click", "x": 295, "y": 147, "button": "left", "count": 2})
        self.assertEqual("completed", result["status"])
        self.assertEqual("native_input_dispatch_only", result["verificationScope"])
        self.assertFalse(result["businessResultVerified"])
        click = [args for tool, args in self.driver.calls if tool == "click"]
        self.assertEqual(1, len(click))
        self.assertEqual(0.5, click[0]["x"])
        self.assertAlmostEqual(147 / 590, click[0]["y"])
        self.assertEqual("foreground", click[0]["delivery_mode"])
        self.assertTrue(click[0]["capture_id"])
        self.assertEqual([True], self.dispatched)

    def test_human_stale_frame_or_changed_input_refuses_before_dispatch(self):
        human, geometry = self.human(), self.geometry()
        self.now += 2000
        with self.assertRaises(DesktopError): self.human_input(human, {"kind": "press_key", "key": "enter"}, geometry)
        geometry = self.geometry()
        self.health.tick += 1
        with self.assertRaises(DesktopError): self.human_input(human, {"kind": "press_key", "key": "enter"}, geometry)
        self.assertEqual([], self.dispatched)

    def test_human_invalid_input_is_not_dispatched_and_key_contract_is_exact(self):
        human = self.human()
        for bad in ({"kind": "press_key", "key": "ARROWUP"}, {"kind": "click", "x": 590, "y": 0},
                    {"kind": "click", "x": 0, "y": 0, "count": 3}, {"kind": "scroll", "direction": "up", "amount": 6},
                    {"kind": "insert_text", "text": "\x00"}, {"kind": "hotkey", "keys": ["CTRL", "A", "B"]},
                    {"kind": "hotkey", "keys": ["CTRL", "CTRL", "A"]}, {"kind": "set_text", "text": "x"},
                    {"kind": "press_key", "key": "enter", "elementToken": "s00000001:0"}):
            with self.subTest(bad=bad), self.assertRaises(DesktopError): self.human_input(human, bad)
        self.assertEqual([], self.dispatched)
        self.assertEqual("completed", self.human_input(human, {"kind": "hotkey", "keys": ["CTRL", "A"]})["status"])
        self.assertEqual(["ctrl", "a"], [args for tool, args in self.driver.calls if tool == "hotkey"][-1]["keys"])
        self.assertEqual("completed", self.human_input(human, {"kind": "press_key", "key": "enter"})["status"])
        self.assertEqual("return", [args for tool, args in self.driver.calls if tool == "press_key"][-1]["key"])

    def test_human_unknown_is_not_released_renewed_or_replayed(self):
        human = self.human(); self.driver.fail_action = True
        result = self.human_input(human, {"kind": "insert_text", "text": "once"})
        self.assertEqual("unknown", result["status"])
        self.assertIsNone(result["inputDispatched"])
        for action in (lambda: self.human_input(human, {"kind": "insert_text", "text": "once"}), self.human,
                       lambda: self.adapter.release_human(self.binding, "peer-A", self.stream_id, human, self.current)):
            with self.assertRaises(DesktopError): action()
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))

    def test_idle_human_release_after_lock_uses_durable_identity_not_live_gui(self):
        human = self.human()
        self.health.available = False
        with self.assertRaises(DesktopError):
            self.adapter.release_human(self.binding, "another-owner", self.stream_id, human, self.current)
        released = self.adapter.release_human(self.binding, "peer-A", self.stream_id, human, self.current)
        self.assertTrue(released["released"])
        self.assertEqual("RELEASED", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM desktop_human_controls").fetchone()[0])
        self.health.available = True
        self.assertGreater(self.human()["fence"], human["fence"])

    def test_locked_desktop_cannot_release_executing_or_unknown_human_input(self):
        human = self.human()
        self.health.available = False
        for state in ("HUMAN_EXECUTING", "NEEDS_RECONCILE"):
            self.manager.ledger.execute("UPDATE desktop_resource_leases SET state=?", (state,))
            self.manager.ledger.commit()
            with self.assertRaises(DesktopError):
                self.adapter.release_human(self.binding, "peer-A", self.stream_id, human, self.current)
            self.assertEqual(state, self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def test_local_stop_during_human_input_preserves_pause_fence(self):
        human = self.human()
        original = self.driver.call
        def stopped(tool, args):
            result = original(tool, args)
            if tool == "type_text": self.adapter.local_stop()
            return result
        self.driver.call = stopped
        self.assertEqual("unknown", self.human_input(human, {"kind": "insert_text", "text": "once"})["status"])
        self.assertEqual("PAUSED", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def test_desktop_scroll_requires_pointer_or_same_geometry_last_click(self):
        self.binding.pop("windowId")
        human, geometry = self.human(), self.geometry(False)
        scroll = {"kind": "scroll", "direction": "down", "amount": 1}
        with self.assertRaises(DesktopError): self.human_input(human, scroll, geometry)
        self.assertEqual("completed", self.human_input(human, {**scroll, "x": 600, "y": 300}, geometry)["status"])
        sent = [args for tool, args in self.driver.calls if tool == "scroll"][-1]
        self.assertEqual((960.0, 480.0), (sent["x"], sent["y"]))
        self.human_input(human, {"kind": "click", "x": 600, "y": 300}, geometry)
        self.assertEqual("completed", self.human_input(human, scroll, geometry)["status"])
        changed = {**geometry, "geometryRevision": "new-geometry"}
        with self.assertRaises(DesktopError): self.human_input(human, scroll, changed)


if __name__ == "__main__":
    unittest.main()
