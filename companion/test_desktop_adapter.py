"""Hermetic state-machine tests ONLY. These are not Windows GUI acceptance."""
import base64
import binascii
import copy
import json
import os
from pathlib import Path
import sqlite3
import struct
import tempfile
import threading
import unittest
import zlib
from types import SimpleNamespace

from cua_desktop_adapter import CuaError, McpCuaDriver
from desktop_adapter import DesktopAdapter, DesktopError, _coordinate
from desktop_sessions import WindowsDesktopHealth

def _fixture_png():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff\xff")) + chunk(b"IEND", b"")


PNG = _fixture_png()  # Controlled valid 1x1 fixture, never a claimed Windows capture.


class ContractHealth:
    def __init__(self):
        self.tick, self.binding, self.available = 100, "actual-os-binding-fixture", True

    def probe(self, pid=None, window_id=None):
        result = {"available": self.available, "reason": "locked-fixture", "sessionId": 1, "binding": self.binding,
                  "lastInputTick": self.tick, "displays": [{"id": "display-1", "primary": True, "dpi": [144, 144], "bounds": [0, 0, 1920, 1080]}]}
        if pid is not None:
            result["window"] = {"pid": pid, "windowId": window_id, "dpi": 144, "bounds": [10, 10, 600, 600]}
        return result


class ContractDriver:
    def __init__(self):
        self.calls, self.value, self.snapshot = [], "before", 0
        self.fail_action = False
        self.raw = PNG

    def capabilities(self):
        return {"contractFixture": True, "nativeGuiVerified": False, "tools": ["set_value", "verify_state", "type_text", "click", "press_key", "hotkey", "scroll"]}

    def stop(self):
        return {"processGroupStoppedVerified": True, "contractFixture": True}

    def call(self, tool, arguments):
        self.calls.append((tool, copy.deepcopy(arguments)))
        if tool == "get_accessibility_tree":
            return {"structuredContent": {"processes": [{"pid": 123, "windows": [456]}]}, "content": []}
        if tool == "verify_state":
            # Model the pinned PUBLIC semantic probe: a real fresh read, but no
            # snapshot publication/invalidation and no input dispatch.
            element = {"element_index": 0, "role": "Edit", "label": "Document", "enabled": True,
                       "frame": {"x": 10, "y": 10, "w": 1, "h": 1}}
            if self.value.strip(): element["value"] = self.value
            predicate = arguments["expect"][0]["element"]
            matched = (predicate["selector"].get("role") == element["role"] and
                       predicate["selector"].get("label_contains", "").casefold() in element["label"].casefold() and
                       predicate.get("enabled", True) == element["enabled"] and
                       ("value_equals" not in predicate or predicate["value_equals"] == element.get("value")))
            status = "satisfied" if matched else "unsatisfied"
            return {"structuredContent": {"status": status, "stable": matched, "samples": 1, "elapsed_ms": 0,
                    "predicates": [{"index": 0, "status": status, "unknown_reason": None, "observed_json": json.dumps(element)}]}}
        if tool in {"get_window_state", "get_desktop_state"}:
            if arguments.get("include_accessibility_tree", True):
                self.snapshot += 1
            width, height = struct.unpack(">II", self.raw[16:24])
            state = {"pid": 123, "window_id": 456, "window_title": "Controlled Test Editor", "screenshot_width": width, "screenshot_height": height,
                     "window_bounds": {"x": 10, "y": 10, "width": 590, "height": 590},
                     "capture_id": "capture-" + str(len(self.calls)), "snapshot_id": "s%08x" % self.snapshot,
                     "elements_complete": True, "elements": [{"element_token": "s%08x:0" % self.snapshot,
                                                               "element_index": 0, "role": "Edit", "label": "Document", "value": self.value,
                                                               "enabled": True, "actions": ["set_value"], "frame": {"x": 10, "y": 10, "w": 1, "h": 1},
                                                               "screenshot_frame": {"x": 0, "y": 0, "w": 1, "h": 1}}]}
            if not self.value.strip():
                state["elements"][0].pop("value")  # Pinned UIA omits empty/whitespace (also failed reads).
            if not arguments.get("include_accessibility_tree", True):
                state.pop("elements"); state.pop("elements_complete")
                state["screenshot_refreshed_snapshot"] = True
            if arguments.get("include_screenshot") is False:
                for key in ("screenshot_width", "screenshot_height", "capture_id"):
                    state.pop(key, None)
                for element in state.get("elements", []):
                    element.pop("screenshot_frame", None)
                return {"structuredContent": state, "content": []}
            return {"structuredContent": state, "content": [{"type": "image", "mimeType": "image/png", "data": base64.b64encode(self.raw).decode()}]}
        if tool == "type_text":
            # Pinned f595 Windows UIA type_text appends CurrentValue + text.
            self.value += arguments["text"]
            if self.fail_action:
                raise CuaError("Fixture response lost after input", effect_unknown=True)
            return {"structuredContent": {"effect": "unverifiable", "verified": False, "path": "contract_fixture"}}
        if tool == "set_value":
            self.value = arguments["value"]
            if self.fail_action:
                raise CuaError("Fixture response lost after input", effect_unknown=True)
            return {"structuredContent": {"effect": "unverifiable", "verified": False, "path": "contract_fixture"}}
        return {"structuredContent": {"effect": "unverifiable", "verified": False}}


class DesktopContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.manager = SimpleNamespace(state=Path(self.temp.name), instance_id="fixture-original-instance", lock=threading.RLock(),
                                       ledger=sqlite3.connect(":memory:", check_same_thread=False))
        self.driver, self.health = ContractDriver(), ContractHealth()
        self.now = 100000
        self.captures = []
        def captures(raw, metadata, expires):
            self.captures.append((raw, metadata, expires))
            return {"artifactId": "fixture-only", "sha256": "fixture-only", "bytes": len(raw)}
        self.adapter = DesktopAdapter(self.manager, self.driver, self.health, captures, "pw1_" + "1" * 64, clock=lambda: self.now)
        self.binding = {"kind": "desktop", "sessionId": 1, "displayId": "primary", "windowId": 456}
        self.metadata = {"taskId": "root-1", "requestRunId": "run-1", "requestExecutorEpoch": 1, "operationId": "operation-1"}
        self.current = lambda: None
        self.dispatched = []

    def test_single_keys_use_actual_press_key_once_with_exact_window_and_delivery(self):
        lease = self.lease()
        for key, expected in (("Enter", "return"), ("Escape", "escape"), ("Tab", "tab")):
            with self.subTest(key=key):
                observation = self.observe()
                receipt = self.adapter.act(self.binding, observation["observationRef"], "peer-A",
                                           {"kind": "press_key", "key": key, "lease": lease,
                                            "verification": {"kind": "window_title_equals", "value": "Controlled Test Editor"}},
                                           {**self.metadata, "operationId": "single-key-" + key.casefold()}, self.current, lambda: None)
                calls = [(name, args) for name, args in self.driver.calls if name == "press_key"]
                self.assertEqual(len(calls), ("Enter", "Escape", "Tab").index(key) + 1)
                self.assertEqual({"scope": "window", "pid": 123, "window_id": 456, "delivery_mode": "background", "key": expected}, calls[-1][1])
                self.assertFalse(receipt["userGoalVerified"])
        self.assertFalse(any(tool == "hotkey" for tool, args in self.driver.calls))

    def test_single_key_rejects_compound_targets_unknown_keys_and_stale_observation(self):
        observation, lease = self.observe(), self.lease()
        for extra in ({"key": "ctrl+s"}, {"key": "Enter", "x": 0, "y": 0}, {"key": "Tab", "elementToken": observation["state"]["elements"][0]["element_token"]}):
            with self.assertRaises(DesktopError):
                self.adapter.act(self.binding, observation["observationRef"], "peer-A", {"kind": "press_key", "lease": lease, **extra}, self.metadata, self.current, lambda: None)
        self.health.tick += 1
        with self.assertRaises(DesktopError):
            self.adapter.act(self.binding, observation["observationRef"], "peer-A", {"kind": "press_key", "lease": lease, "key": "Escape"}, self.metadata, self.current, lambda: None)
        self.assertFalse(any(tool == "press_key" for tool, args in self.driver.calls))

    def tearDown(self):
        self.adapter.close()
        self.manager.ledger.close()
        self.temp.cleanup()

    def observe(self):
        return self.adapter.observe(self.binding, {"pid": 123, "windowId": 456, "observationMode": "hybrid"}, self.metadata, self.current)

    def lease(self):
        return self.adapter.control("acquire", self.binding, "peer-A", {"action": "acquire"}, self.current)["lease"]

    def action(self, observation, lease, **updates):
        args = {"kind": "set_text", "lease": lease, "elementToken": observation["state"]["elements"][0]["element_token"],
                "text": "中文实际条件", "verification": {"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": "中文实际条件"}}
        args.update(updates)
        return self.adapter.act(self.binding, observation["observationRef"], "peer-A", args, self.metadata, self.current, lambda: self.dispatched.append(True))

    def test_observation_uses_artifact_bytes_not_control_base64(self):
        result = self.observe()
        self.assertEqual({"width": 1, "height": 1, "mediaType": "image/png"}, result["image"])
        self.assertEqual(PNG, self.captures[0][0])
        self.assertEqual({"taskId", "requestRunId", "requestExecutorEpoch", "operationId", "observationRef", "resourceBinding"}, set(self.captures[0][1]))
        self.assertNotIn("base64", json.dumps(result))
        self.assertNotIn("data", result)
        self.assertEqual(1, result["state"]["screenshot_width"])
        self.assertFalse(result["businessResultVerified"])

    def test_png_container_corruption_or_dimension_mismatch_is_not_capture_evidence(self):
        broken = bytearray(PNG); broken[-5] ^= 1
        with self.assertRaises(DesktopError): self.adapter._png_dimensions(bytes(broken))
        with self.assertRaises(DesktopError): self.adapter._png_dimensions(PNG[:-1])
        with self.assertRaises(DesktopError): self.adapter._png_dimensions(PNG + b"extra")
        original = self.driver.call
        def wrong(tool, args):
            value = original(tool, args)
            if tool == "get_window_state": value["structuredContent"]["screenshot_width"] = 2
            return value
        self.driver.call = wrong
        with self.assertRaises(DesktopError): self.observe()
        self.assertEqual([], self.captures)

    def test_one_input_then_fresh_observation_verifies_exact_window_condition(self):
        lease, observation = self.lease(), self.observe()
        result = self.action(observation, lease)
        self.assertEqual("completed", result["status"])
        self.assertTrue(result["businessResultVerified"])
        self.assertEqual(1, len(self.dispatched))
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))
        self.assertNotEqual(result["beforeObservationRef"]["id"], result["afterObservationRef"]["id"])
        with self.assertRaises(DesktopError):
            self.action(observation, lease)
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_wrong_window_old_frame_geometry_and_manual_input_refuse_before_dispatch(self):
        lease, observed = self.lease(), self.observe()
        self.health.binding = "different-dpi-or-hwnd"
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        self.health.binding = "actual-os-binding-fixture"
        self.health.tick += 1
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        self.assertEqual([], self.dispatched)
        self.assertFalse(any(tool in {"type_text", "set_value"} for tool, _ in self.driver.calls))

    def test_changed_pixels_are_not_silently_accepted_as_same_capture(self):
        lease, observed = self.lease(), self.observe()
        self.driver.raw = PNG + b"different fixture frame"
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        self.assertEqual([], self.dispatched)

    def test_expired_lease_can_only_be_explicitly_released_not_used_or_taken_over(self):
        lease, observed = self.lease(), self.observe()
        self.now += 30001
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        with self.assertRaises(DesktopError):
            self.adapter.control("acquire", self.binding, "peer-B", {"action": "acquire"}, self.current)
        self.adapter.control("release", self.binding, "peer-A", {"action": "release", "lease": lease}, self.current)
        next_lease = self.lease()
        self.assertGreater(next_lease["fence"], lease["fence"])

    def test_unknown_input_keeps_original_resource_and_never_replays(self):
        lease, observed = self.lease(), self.observe()
        self.driver.fail_action = True
        result = self.action(observed, lease)
        self.assertEqual("unknown", result["status"])
        self.assertTrue(result["noAutomaticReplay"])
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        with self.assertRaises(DesktopError):
            self.adapter.control("acquire", self.binding, "peer-B", {"action": "acquire"}, self.current)
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_local_stop_and_lock_screen_are_not_transport_success(self):
        observed, lease = self.observe(), self.lease()
        self.health.available = False
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        self.health.available = True
        proof = self.adapter.local_stop()
        self.assertTrue(proof["paused"])
        with self.assertRaises(DesktopError):
            self.action(observed, lease)
        self.assertEqual([], self.dispatched)

    def test_unknown_action_or_unrelated_fields_never_dispatch(self):
        lease, observed = self.lease(), self.observe()
        for updates in ({"kind": "drag"}, {"keys": ["ctrl", "s"]}, {"verification": {"kind": "llm_says_done"}}, {"elementToken": "s00000000:9"}):
            with self.subTest(updates), self.assertRaises(DesktopError):
                self.action(observed, lease, **updates)
        self.assertEqual([], self.dispatched)

    def test_discovery_is_not_inner_tree_or_screenshot_and_window_grant_cannot_enumerate(self):
        with self.assertRaises(DesktopError):
            self.adapter.observe(self.binding, {"discovery": True}, self.metadata, self.current)
        binding = {key: value for key, value in self.binding.items() if key != "windowId"}
        result = self.adapter.observe(binding, {"discovery": True}, self.metadata, self.current)
        self.assertEqual("window_discovery", result["kind"])
        self.assertFalse(result["actionReady"])
        self.assertEqual([], self.captures)

    def test_restart_does_not_expire_unknown_lease_into_new_authority(self):
        self.lease()
        self.adapter.close()
        self.manager.instance_id = "new-instance"
        adapter = DesktopAdapter(self.manager, ContractDriver(), self.health, lambda *a: {}, "pw1_" + "1" * 64, clock=lambda: self.now)
        try:
            self.assertEqual("NEEDS_RECONCILE", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
            with self.assertRaises(DesktopError):
                adapter.control("acquire", self.binding, "peer-A", {"action": "acquire"}, self.current)
        finally:
            adapter.close()

    def test_linux_health_and_missing_driver_do_not_claim_native_support(self):
        if os.name != "nt":
            self.assertFalse(WindowsDesktopHealth().probe()["available"])
            with self.assertRaises(CuaError):
                McpCuaDriver("/missing-driver.exe", "0" * 64, None).start()

    def test_wire_decimal_coordinates_are_explicit_not_truncated(self):
        self.assertEqual(10.25, _coordinate("10.25", 100, "x"))
        self.assertEqual(10, _coordinate(10, 100, "x"))
        for value in (True, 1.2, "NaN", "1e10", "-1", "100", "0.1234567"):
            with self.subTest(value=value), self.assertRaises(DesktopError):
                _coordinate(value, 100, "x")


if __name__ == "__main__":
    unittest.main()
