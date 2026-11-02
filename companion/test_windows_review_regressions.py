"""Portable negative/fault models from the fixed-source review, NOT Win32 proof."""
from contextlib import contextmanager
import copy
import hashlib
import json
import ntpath
from types import SimpleNamespace
import unittest

import device_protocol as wire
from cua_desktop_adapter import input_outcome
from desktop_adapter import DesktopAdapter, DesktopError
from platform_windows import WindowsBackend
import test_desktop_adapter as contracts


REFUSAL = {"isError": True, "structuredContent": {"code": "background_unavailable", "target_class": "Chrome_WidgetWin_1", "event_kind": "key_combo",
           "suggestion": 'Retry this action with delivery_mode:"foreground".', "escalation": {"recommended": "foreground", "reason": 'background input is dropped by this surface; retry this action with delivery_mode:"foreground".'}}}


class DesktopReviewContracts(unittest.TestCase):
    setUp = contracts.DesktopContractTests.setUp
    tearDown = contracts.DesktopContractTests.tearDown
    observe = contracts.DesktopContractTests.observe
    lease = contracts.DesktopContractTests.lease
    def test_exact_upstream_background_refusal_cannot_complete_already_matching_condition(self):
        observation, lease = self.observe(), self.lease()
        original = self.driver.call
        self.driver.call = lambda tool, args: copy.deepcopy(REFUSAL) if tool == "hotkey" else original(tool, args)
        args = {"kind": "hotkey", "keys": ["ctrl", "s"], "lease": lease, "deliveryMode": "background",
                "verification": {"kind": "window_title_equals", "value": "Controlled Test Editor"}}
        result = self.adapter.act(self.binding, observation["observationRef"], "peer-A", args, self.metadata, self.current, lambda: None)
        self.assertEqual("failed", result["status"])
        self.assertFalse(result["inputDispatched"])
        self.assertTrue(result["observedConditionMatched"])
        self.assertFalse(result["businessResultVerified"])
        self.assertTrue(result["actionOutcome"]["foregroundEscalationPermitted"])

    def test_foreground_is_new_exact_operation_after_refusal_never_automatic_retry(self):
        observation, lease = self.observe(), self.lease()
        original = self.driver.call; actions = []
        def driver(tool, args):
            if tool == "hotkey":
                actions.append(copy.deepcopy(args))
                return copy.deepcopy(REFUSAL) if args["delivery_mode"] == "background" else {"structuredContent": {"path": "key_events", "effect": "unverifiable", "verified": False}}
            return original(tool, args)
        self.driver.call = driver
        action = {"kind": "hotkey", "keys": ["ctrl", "s"], "lease": lease, "verification": {"kind": "window_title_equals", "value": "Controlled Test Editor"}}
        with self.assertRaises(DesktopError):
            self.adapter.act(self.binding, observation["observationRef"], "peer-A", {**action, "deliveryMode": "foreground"}, self.metadata, self.current, lambda: None)
        self.assertEqual([], actions)
        refused = self.adapter.act(self.binding, observation["observationRef"], "peer-A", action, self.metadata, self.current, lambda: None)
        fresh = self.observe()
        foreground = {**action, "deliveryMode": "foreground", "foregroundAfterOperationId": self.metadata["operationId"]}
        result = self.adapter.act(self.binding, fresh["observationRef"], "peer-A", foreground, {**self.metadata, "operationId": "new-explicit-foreground-op"}, self.current, lambda: None)
        self.assertEqual("completed", result["status"])
        self.assertEqual(["background", "foreground"], [item["delivery_mode"] for item in actions])
        self.assertFalse(refused["inputDispatched"])
        self.assertFalse(result["userGoalVerified"])

    def test_uia_timeout_same_error_code_and_unknown_errors_never_authorize_foreground(self):
        ambiguous = copy.deepcopy(REFUSAL)
        ambiguous["structuredContent"].update(uia_status="timeout", path="ax", effect="unverifiable", verified=False)
        result = input_outcome(ambiguous)
        self.assertEqual("possible_effect_unknown", result["kind"])
        self.assertIsNone(result["inputDispatched"])
        self.assertFalse(result["foregroundEscalationPermitted"])
        self.assertEqual("possible_effect_unknown", input_outcome({"isError": True, "structuredContent": {"code": "new_driver_failure"}})["kind"])


class ControlProjectionContracts(unittest.TestCase):
    def frame(self, result):
        receipt = wire.operation_receipt("pw1_" + "1" * 64, "fixture-instance", None, None, "unknown", False, "unknown", result)
        return {"protocolVersion": wire.VERSION, "protocolProfile": wire.PROFILE, "messageType": "DeviceResponse", "requestId": "fixture-request", "method": "tool.invoke",
                "executorDeviceId": "pw1_" + "1" * 64, "instanceId": "fixture-instance", "result": receipt}

    def test_review_4105_node_discovery_and_5000_windows_fit_whole_shared_frame(self):
        for count in (240, 5000):
            value = {"processes": [{"pid": 1000 + 4 * i, "name": "a.exe"} for i in range(100)],
                     "windows": [{"window_id": 65536 + 4 * i, "pid": 1000, "title": "A", "x": 0, "y": 0, "width": 10, "height": 10} for i in range(count)]}
            projected = DesktopAdapter._bounded(value)
            self.assertTrue(projected["adapter_truncated"])
            frame = self.frame({"kind": "window_discovery", "actionReady": False, "result": projected, "summary": DesktopAdapter._bounded(["中文" * 20000])})
            wire.validate_message(frame)
            self.assertLessEqual(len(wire.canonical_bytes(frame)), wire.MAX_CONTROL_BYTES)

    def test_depth_and_truncated_elements_never_prove_absence(self):
        nested = "too deep"
        for _ in range(30):
            nested = {"next": nested}
        value = DesktopAdapter._bounded({"elements_complete": True, "elements": [{"role": "Edit", "label": "x"}] * 5000, "nested": nested})
        self.assertFalse(value["elements_complete"])
        self.assertFalse(DesktopAdapter._verify({"kind": "element_absent", "selector": {"role": "Dialog"}}, {"state": value}))
        wire.validate_message(self.frame({"state": value}))


class ReplaceFailureModel:
    """Explicit Win32 documented-state model; deliberately no native PASS claim."""
    def __init__(self, error):
        self.error, self.objects, self.handles, self.next_id = error, {"C:\\fixture\\config": {"raw": b"old", "identity": [1, 0, 1]}}, {}, 2
        self.deleted, self.calls = [], []
        self.k = SimpleNamespace(ReplaceFileW=self.replace, MoveFileExW=self.move, CloseHandle=lambda handle: self.handles.pop(handle, None))
    def open(self, path, **options):
        self.objects[path] = {"raw": b"", "identity": [1, 0, self.next_id]}; self.next_id += 1
        self.handles[path] = self.objects[path]
        return path
    def write(self, handle, raw): self.handles[handle]["raw"] = raw
    def info(self, handle): return self.handles[handle]
    def identity(self, info): return info["identity"]
    def disposition(self, handle): self.deleted.append(self.handles[handle]["identity"])
    def checked(self, value):
        if value: return value
        failure = OSError("documented ReplaceFile fault fixture"); failure.winerror = self.error
        raise failure
    def move(self, source, target, flags): self.objects[target] = self.objects.pop(source); return True
    def replace(self, target, source, backup, *unused):
        self.calls.append((target, source, backup))
        if self.error == 1177:
            self.objects[backup] = self.objects.pop(target)
        elif self.error == "concurrent_edit":
            self.objects[backup] = {"raw": b"new external edit", "identity": [1, 0, 99]}
            self.objects[target] = self.objects.pop(source)
            return True
        elif self.error is None:
            self.objects[backup] = self.objects.pop(target); self.objects[target] = self.objects.pop(source)
            return True
        return False


class ReplacementRecoveryModels(unittest.TestCase):
    def backend(self, error):
        api = ReplaceFailureModel(error); backend = WindowsBackend(api=api)
        @contextmanager
        def directory(root, parts=()): yield "C:\\fixture", 123, [1, 0, 55]
        backend.directory = directory
        backend.read_bytes = lambda root, name: api.objects.get(ntpath.join(root, name), {}).get("raw")
        def fact(root, name):
            item = api.objects.get(ntpath.join(root, name))
            return {"state": "absent"} if item is None else {"state": "present", "fileIdentity": item["identity"], "sha256": hashlib.sha256(item["raw"]).hexdigest(), "bytes": len(item["raw"])}
        backend._replacement_fact = fact
        return backend, api

    def test_revoked_during_snapshot_write_never_publishes_original_handle(self):
        backend, api = self.backend(None)
        publications = []
        api.publish_handle = lambda *args: publications.append(args)
        allow = [True]
        original_write = api.write
        def write(handle, raw):
            original_write(handle, raw)
            allow[0] = False
        api.write = write
        def current():
            if not allow[0]: raise PermissionError("cancelled during snapshot write")
        with self.assertRaises(PermissionError):
            backend.create_private_snapshot("C:\\fixture", "input-cache", b"private bytes", current=current)
        self.assertEqual([], publications)
        self.assertEqual(1, len(api.deleted))

    def test_revoked_during_intent_persistence_cannot_cross_native_replace_boundary(self):
        backend, api = self.backend(None)
        allow = [True]
        def current():
            if not allow[0]: raise PermissionError("revoked during original intent persistence")
        def record(value): allow[0] = False
        backend._delete_owned_bytes = lambda *args: None
        with self.assertRaises(PermissionError):
            backend.compare_and_swap("C:\\fixture", "config", b"old", b"new", current, record_intent=record)
        self.assertEqual([], api.calls)
        self.assertEqual(b"old", api.objects["C:\\fixture\\config"]["raw"])

    def test_documented_partial_failures_keep_all_material_and_persist_intent_before_dispatch(self):
        for error in (1175, 1176, 1177, 5):
            backend, api = self.backend(error); intents = []
            result = backend.compare_and_swap("C:\\fixture", "config", b"old", b"new", record_intent=lambda value: intents.append(copy.deepcopy(value)))
            self.assertEqual("unknown", result["status"])
            self.assertEqual(error, result["nativeReplacement"]["win32Error"])
            self.assertEqual("dispatch_intent_persisted", intents[0]["phase"])
            self.assertIsNotNone(api.calls[0][2])
            self.assertEqual([], api.deleted)
            self.assertTrue(any(item["raw"] == b"new" for item in api.objects.values()))
            self.assertTrue(any(item["raw"] == b"old" for item in api.objects.values()))

    def test_concurrent_displaced_version_retained_and_never_rolled_over_later_edit(self):
        backend, api = self.backend("concurrent_edit")
        result = backend.compare_and_swap("C:\\fixture", "config", b"old", b"new")
        self.assertEqual("unknown", result["status"])
        self.assertTrue(any(item["raw"] == b"new external edit" for item in api.objects.values()))
        api.objects["C:\\fixture\\config"]["raw"] = b"third user edit"
        self.assertEqual(b"third user edit", api.objects["C:\\fixture\\config"]["raw"])
        self.assertEqual(1, len(api.calls))
        self.assertEqual([], api.deleted)


if __name__ == "__main__":
    unittest.main()
