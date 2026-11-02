"""R4 regressions through production adapters/jobs; fixture UIA is NOT Win32 proof."""
import binascii
import json
import struct
import threading
import time
import unittest
import zlib
from unittest.mock import patch

from cua_desktop_adapter import CuaError, McpCuaDriver
from desktop_adapter import DesktopError
import test_desktop_adapter as contracts
import test_desktop_jobs as job_contracts


def frame(first=(255, 255, 255, 255), second=(255, 255, 255, 255), level=6):
    def chunk(kind, raw):
        return struct.pack(">I", len(raw)) + kind + raw + struct.pack(">I", binascii.crc32(kind + raw) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\0" + bytes(first + second), level)) + chunk(b"IEND", b""))


class WindowsActionContracts(unittest.TestCase):
    setUp = contracts.DesktopContractTests.setUp
    tearDown = contracts.DesktopContractTests.tearDown
    observe = contracts.DesktopContractTests.observe
    lease = contracts.DesktopContractTests.lease
    action = contracts.DesktopContractTests.action

    def test_nonempty_english_and_chinese_are_replaced_not_appended_once(self):
        lease = self.lease()
        for before, value in (("OLD", "NEW"), ("旧文", "新文")):
            self.driver.value = before
            observed = self.observe()
            result = self.action(observed, lease, text=value,
                                 verification={"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": value})
            self.assertEqual("completed", result["status"], result)
            self.assertEqual(value, self.driver.value)
        inputs = [(tool, args) for tool, args in self.driver.calls if tool in {"set_value", "type_text", "press_key", "hotkey"}]
        self.assertEqual(["set_value", "set_value"], [tool for tool, _ in inputs])
        self.assertEqual({"pid": 123, "window_id": 456, "element_token": observed["state"]["elements"][0]["element_token"],
                          "delivery_mode": "background", "value": "新文"}, inputs[-1][1])

    def test_unrelated_valid_png_pixel_change_does_not_block_background_replacement(self):
        self.driver.raw = frame()
        lease, observed = self.lease(), self.observe()
        self.driver.raw = frame(second=(1, 2, 3, 255))
        result = self.action(observed, lease)
        self.assertEqual("completed", result["status"], result)
        self.assertEqual(1, len(self.dispatched))
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_same_pixels_different_png_encoding_are_not_a_target_change(self):
        self.driver.raw = frame(level=0)
        lease, observed = self.lease(), self.observe()
        self.driver.raw = frame(level=9)
        self.assertEqual("completed", self.action(observed, lease)["status"])

    def test_empty_set_text_is_dispatched_once_but_missing_readback_remains_unknown(self):
        self.driver.value = "OLD 旧内容"
        lease, observed = self.lease(), self.observe()
        result = self.action(observed, lease, text="", verification={"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": ""})
        self.assertEqual("unknown", result["status"], result)
        self.assertFalse(result["textReplacementVerified"])
        self.assertFalse(result["observedConditionMatched"])
        self.assertEqual("", self.driver.value)
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))
        self.assertFalse(any(tool in {"type_text", "hotkey", "press_key"} for tool, _ in self.driver.calls))
        with self.assertRaises(DesktopError): self.action(observed, lease, text="")
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_failed_or_whitespace_value_reads_cannot_masquerade_as_verified_clear(self):
        self.assertFalse(self.adapter._verify({"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": ""},
                                              {"state": {"elements": [{"role": "Edit", "label": "Document", "enabled": True, "actions": ["set_value"]}]}}))
        lease, observed = self.lease(), self.observe()
        original = self.driver.call
        def call(tool, args):
            result = original(tool, args)
            if tool == "get_window_state" and args.get("include_accessibility_tree", True) and any(name == "set_value" for name, _ in self.driver.calls):
                result["structuredContent"]["elements"][0].pop("value", None)
            return result
        self.driver.call = call
        result = self.action(observed, lease, text="NEW", verification={"kind": "window_title_equals", "value": "Controlled Test Editor"})
        self.assertEqual("unknown", result["status"])
        self.assertTrue(result["observedConditionMatched"])
        self.assertFalse(result["textReplacementVerified"])

    def test_insert_text_keeps_append_semantics_and_does_not_clear(self):
        self.driver.value = "OLD"
        lease, observed = self.lease(), self.observe()
        result = self.adapter.act(self.binding, observed["observationRef"], "peer-A",
                                  {"kind": "insert_text", "lease": lease, "text": "NEW", "verification": {"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": "OLDNEW"}},
                                  self.metadata, self.current, lambda: self.dispatched.append(True))
        self.assertEqual("completed", result["status"], result)
        self.assertEqual("OLDNEW", self.driver.value)
        self.assertEqual(1, sum(tool == "type_text" for tool, _ in self.driver.calls))
        self.assertFalse(any(tool == "set_value" for tool, _ in self.driver.calls))

    def test_unavailable_valuepattern_or_tool_has_no_intent_input_or_fallback(self):
        lease = self.lease()
        for unavailable in ("pattern", "disabled", "tool", "region", "schema"):
            observed = self.observe()
            snapshot = self.adapter.snapshots[observed["observationRef"]["id"]]
            with self.subTest(unavailable=unavailable):
                if unavailable == "pattern": snapshot["state"]["elements"][0]["actions"] = ["text"]
                if unavailable == "disabled": snapshot["state"]["elements"][0]["enabled"] = False
                if unavailable == "region": snapshot["targetRegions"] = {}
                if unavailable == "schema": self.driver.validate_call = lambda *args: (_ for _ in ()).throw(CuaError("Exact pinned schema unavailable"))
                original = self.driver.capabilities
                if unavailable == "tool": self.driver.capabilities = lambda: {"tools": ["type_text"]}
                try:
                    with self.assertRaises((DesktopError, CuaError)):
                        self.action(observed, lease)
                finally:
                    self.driver.capabilities = original
                    if hasattr(self.driver, "validate_call"): del self.driver.validate_call
                self.assertFalse(self.dispatched)
                self.assertFalse(any(tool in {"set_value", "type_text", "hotkey", "press_key"} for tool, _ in self.driver.calls))

    def test_wrong_readback_cannot_complete_from_only_matching_window_title(self):
        original = self.driver.call
        def call(tool, args):
            result = original(tool, args)
            if tool == "set_value": self.driver.value = "WRONG"
            return result
        self.driver.call = call
        lease, observed = self.lease(), self.observe()
        result = self.action(observed, lease, verification={"kind": "window_title_equals", "value": "Controlled Test Editor"})
        self.assertEqual("unknown", result["status"], result)
        self.assertTrue(result["observedConditionMatched"])
        self.assertFalse(result["textReplacementVerified"])
        self.assertFalse(result["businessResultVerified"])
        self.assertEqual("NEEDS_RECONCILE", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def test_lost_replacement_response_is_unknown_never_replayed(self):
        lease, observed = self.lease(), self.observe()
        self.driver.fail_action = True
        result = self.action(observed, lease)
        self.assertEqual("unknown", result["status"])
        self.assertEqual("中文实际条件", self.driver.value)
        with self.assertRaises(DesktopError): self.action(observed, lease)
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_new_run_or_executor_epoch_cannot_reuse_original_observation(self):
        lease, observed = self.lease(), self.observe()
        for updates in ({"requestRunId": "new-run"}, {"requestExecutorEpoch": 2}, {"taskId": "other-task"}):
            with self.subTest(updates=updates), self.assertRaises(DesktopError):
                self.adapter.act(self.binding, observed["observationRef"], "peer-A", {"kind": "set_text", "text": "NEW", "elementToken": observed["state"]["elements"][0]["element_token"], "lease": lease},
                                 {**self.metadata, **updates}, self.current, lambda: self.dispatched.append(True))
        self.assertFalse(self.dispatched)

    def test_live_uia_semantics_or_native_geometry_change_refuses_without_rebinding(self):
        lease = self.lease()
        original = self.driver.call
        for changed in ("role", "label", "value", "enabled", "selected", "frame", "ambiguous", "untrusted", "truncated", "missing_frame"):
            observed = self.observe()
            snapshot = self.adapter.snapshots[observed["observationRef"]["id"]]
            before_epoch = self.driver.snapshot
            if changed == "missing_frame": snapshot["state"]["elements"][0].pop("frame")
            def call(tool, args):
                result = original(tool, args)
                if tool == "verify_state":
                    state = result["structuredContent"]
                    outcome = state["predicates"][0]
                    element = json.loads(outcome["observed_json"])
                    if changed in {"ambiguous", "untrusted"}:
                        state.update(status="unknown", stable=False)
                        outcome.update(status="unknown", unknown_reason="multi_match" if changed == "ambiguous" else "untrusted_source")
                    elif changed == "truncated":
                        outcome["observed_json"] = '{"role":"Edit",...'
                    elif changed == "frame":
                        element["frame"]["x"] += 1
                        outcome["observed_json"] = json.dumps(element)
                    elif changed != "missing_frame":
                        element[changed] = not element.get(changed, False) if changed in {"enabled", "selected"} else "replaced"
                        outcome["observed_json"] = json.dumps(element)
                return result
            self.driver.call = call
            try:
                with self.subTest(changed=changed), self.assertRaises(DesktopError): self.action(observed, lease)
                self.assertEqual(before_epoch, self.driver.snapshot, "Read-only semantic verification must not mint/rebind action tokens")
                self.assertFalse(self.dispatched)
                self.assertFalse(any(tool in {"set_value", "type_text"} for tool, _ in self.driver.calls))
            finally:
                self.driver.call = original

    def test_changed_target_region_or_replaced_snapshot_refuses_with_zero_intent_and_input(self):
        lease = self.lease()
        for reason in ("target_pixels", "snapshot_epoch"):
            with self.subTest(reason=reason):
                self.driver.raw = frame()
                observed = self.observe()
                if reason == "target_pixels":
                    self.driver.raw = frame(first=(1, 2, 3, 255))
                else:
                    self.driver.snapshot += 1
                with self.assertRaises(DesktopError):
                    self.action(observed, lease)
                self.assertFalse(self.dispatched)
                self.assertFalse(any(tool in {"set_value", "type_text"} for tool, _ in self.driver.calls))

    def test_pixel_click_still_rejects_changed_whole_capture(self):
        self.driver.raw = frame()
        lease, observed = self.lease(), self.observe()
        self.driver.raw = frame(second=(1, 2, 3, 255))
        with self.assertRaises(DesktopError):
            self.adapter.act(self.binding, observed["observationRef"], "peer-A", {"kind": "click", "x": 0, "y": 0, "lease": lease},
                             self.metadata, self.current, lambda: self.dispatched.append(True))
        self.assertFalse(self.dispatched)
        self.assertFalse(any(tool == "click" for tool, _ in self.driver.calls))

    def test_semantic_click_tolerates_unrelated_pixels_only_with_fresh_same_native_bounds(self):
        self.driver.raw = frame()
        original = self.driver.call
        def call(tool, args):
            result = original(tool, args)
            if tool == "get_window_state" and args.get("include_accessibility_tree", True):
                result["structuredContent"]["elements"][0]["actions"] = ["invoke"]
            return result
        self.driver.call = call
        lease, observed = self.lease(), self.observe()
        self.driver.raw = frame(second=(1, 2, 3, 255))
        result = self.adapter.act(self.binding, observed["observationRef"], "peer-A", {
            "kind": "click", "lease": lease, "elementToken": observed["state"]["elements"][0]["element_token"],
            "verification": {"kind": "window_title_equals", "value": "Controlled Test Editor"}},
            self.metadata, self.current, lambda: self.dispatched.append(True))
        self.assertEqual("completed", result["status"], result)
        clicks = [args for name, args in self.driver.calls if name == "click"]
        self.assertEqual(1, len(clicks))
        self.assertEqual(observed["state"]["elements"][0]["element_token"], clicks[0]["element_token"])
        self.assertNotIn("x", clicks[0]); self.assertNotIn("y", clicks[0])
        self.assertEqual(1, len([name for name, _ in self.driver.calls if name == "verify_state"]))

    def test_cancel_or_permission_loss_during_preflight_never_dispatches(self):
        lease = self.lease()
        for stopping in ("cancel", "permission", "manual_input", "geometry", "local_stop"):
            with self.subTest(stopping=stopping):
                observed = self.observe()
                original = self.driver.call
                denied = []
                def call(tool, args):
                    result = original(tool, args)
                    if tool == "get_window_state" and args.get("include_accessibility_tree") is False:
                        if stopping == "local_stop": self.adapter.local_stop()
                        elif stopping == "manual_input": self.health.tick += 1
                        elif stopping == "geometry": self.health.binding = "replaced-window-generation"
                        else: denied.append(True)
                    return result
                def current():
                    if denied: raise DesktopError(stopping)
                self.driver.call = call
                try:
                    with self.assertRaises(DesktopError):
                        self.adapter.act(self.binding, observed["observationRef"], "peer-A", {"kind": "set_text", "text": "NEW", "elementToken": observed["state"]["elements"][0]["element_token"], "lease": lease},
                                         self.metadata, current, lambda: self.dispatched.append(True))
                finally:
                    self.driver.call = original
                self.assertFalse(self.dispatched)
                if stopping == "local_stop": break  # Native stop is intentionally irreversible.


class PinnedMcpValueContracts(unittest.TestCase):
    def test_actual_mcp_adapter_admits_exact_set_value_schema_not_text_scope_or_delay(self):
        driver = McpCuaDriver("/inert-never-launched-driver.exe", "0" * 64, None)
        driver.tools = {"set_value": {"inputSchema": {"type": "object", "required": ["value"], "properties": {
            "session": {}, "pid": {"type": "integer"}, "window_id": {"type": "integer"},
            "element_token": {"type": "string"}, "value": {"type": "string"}, "delivery_mode": {"type": "string"}}}}}
        args = {"pid": 123, "window_id": 456, "element_token": "s00000001:0", "value": "NEW 新文", "delivery_mode": "background"}
        with patch.object(driver, "start"), patch.object(driver, "_request", return_value={"content": []}) as request:
            driver.validate_call("set_value", args)
            request.assert_not_called()
            driver.call("set_value", args)
            request.assert_called_once_with("tools/call", {"name": "set_value", "arguments": args})
            for field in ("scope", "text", "delay_ms"):
                with self.subTest(field=field), self.assertRaises(CuaError): driver.validate_call("set_value", {**args, field: "not in exact schema"})

    def test_semantic_preflight_uses_public_bounded_verify_state_not_internal_flags(self):
        case = contracts.DesktopContractTests()
        case.setUp()
        try:
            observed, lease = case.observe(), case.lease()
            case.action(observed, lease)
            calls = [args for name, args in case.driver.calls if name == "verify_state"]
            self.assertEqual(1, len(calls))
            self.assertEqual({"pid": 123, "window_id": 456, "expect": [{"element": {
                "selector": {"role": "Edit", "label_contains": "Document"}, "exists": True, "enabled": True, "value_equals": "before"}}],
                "timeout_ms": 0, "stable_samples": 1, "include_screenshot": False}, calls[0])
            self.assertFalse(any(key.startswith("_") for _, args in case.driver.calls for key in args))
        finally:
            case.tearDown()


@unittest.skipUnless(job_contracts.SHARED_CONTRACT, "Requires original identity/protocol integration")
class WindowsOriginalJobContracts(unittest.TestCase):
    setUp = job_contracts.DesktopOriginalJobIntegrationTests.setUp
    tearDown = job_contracts.DesktopOriginalJobIntegrationTests.tearDown
    envelope = job_contracts.DesktopOriginalJobIntegrationTests.envelope
    invoke = job_contracts.DesktopOriginalJobIntegrationTests.invoke
    finish = job_contracts.DesktopOriginalJobIntegrationTests.finish
    observed_and_lease = job_contracts.DesktopOriginalJobIntegrationTests.observed_and_lease
    act_envelope = job_contracts.DesktopOriginalJobIntegrationTests.act_envelope

    def test_changed_unrelated_pixel_replaces_nonempty_value_in_original_operation_once(self):
        self.driver.raw, self.driver.value = frame(), "旧内容 OLD"
        observed, lease = self.observed_and_lease()
        self.driver.raw = frame(second=(1, 2, 3, 255))
        action = self.act_envelope(observed, lease)
        result = self.finish(self.invoke(action))
        self.assertEqual("completed", result["status"], result)
        self.assertEqual("中文结果", self.driver.value)
        self.assertTrue(result["desktopReceipt"]["textReplacementVerified"])
        self.assertEqual(result["id"], self.invoke(action)["id"])
        self.assertEqual(1, self.claims[action.get("operationId")])
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))

    def test_target_change_refuses_original_job_without_dispatch_intent(self):
        self.driver.raw = frame()
        observed, lease = self.observed_and_lease()
        self.driver.raw = frame(first=(1, 2, 3, 255))
        result = self.finish(self.invoke(self.act_envelope(observed, lease)))
        self.assertEqual("failed", result["status"], result)
        self.assertFalse(result.get("desktopDispatchAttempted", False))
        self.assertFalse(any(tool in {"set_value", "type_text"} for tool, _ in self.driver.calls))
        self.assertEqual("HELD", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])

    def _settled(self, job_id):
        end = time.monotonic() + 2
        while time.monotonic() < end:
            result = self.manager.get(job_id)
            if result.get("executionSettlement", {}).get("adapterFinished"):
                return result
            time.sleep(.005)
        self.fail("Original GUI operation did not settle")

    def test_original_job_cancel_during_fresh_capture_has_no_dispatch_or_global_stop(self):
        observed, lease = self.observed_and_lease()
        entered, release = threading.Event(), threading.Event()
        original = self.driver.call
        def call(tool, args):
            result = original(tool, args)
            if tool == "get_window_state" and args.get("include_accessibility_tree") is False:
                entered.set()
                if not release.wait(2): raise AssertionError("Fixture barrier timed out")
            return result
        self.driver.call = call
        job = self.invoke(self.act_envelope(observed, lease))
        try:
            self.assertTrue(entered.wait(2))
            cancelled = self.jobs.cancel(job["id"])
            self.assertEqual("cancelled", cancelled["status"])
            self.assertFalse(cancelled["desktopCleanupProof"]["globalDesktopStopRequested"])
        finally:
            release.set()
        result = self._settled(job["id"])
        self.assertEqual("cancelled", result["status"], result)
        self.assertFalse(result.get("desktopDispatchAttempted", False))
        self.assertFalse(any(tool in {"set_value", "type_text"} for tool, _ in self.driver.calls))
        self.assertFalse(self.adapter.paused)

    def test_original_job_cancel_after_value_write_preserves_unknown_and_no_replay(self):
        observed, lease = self.observed_and_lease()
        entered, release = threading.Event(), threading.Event()
        original = self.driver.call
        def call(tool, args):
            result = original(tool, args)
            if tool == "set_value":
                entered.set()
                if not release.wait(2): raise AssertionError("Fixture barrier timed out")
            return result
        self.driver.call = call
        action = self.act_envelope(observed, lease)
        job = self.invoke(action)
        try:
            self.assertTrue(entered.wait(2))
            cancelled = self.jobs.cancel(job["id"])
            self.assertEqual("unknown", cancelled["status"])
        finally:
            release.set()
        result = self._settled(job["id"])
        self.assertEqual("unknown", result["status"], result)
        self.assertTrue(result["desktopDispatchAttempted"])
        self.assertEqual("中文结果", self.driver.value)
        self.assertEqual(result["id"], self.invoke(action)["id"])
        self.assertEqual(1, sum(tool == "set_value" for tool, _ in self.driver.calls))
        self.assertEqual("NEEDS_RECONCILE", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
