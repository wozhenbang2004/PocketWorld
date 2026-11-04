"""Pinned upstream JSON/argv contracts with fixtures; no native GUI is simulated as verified."""
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

from agent_desktop_driver import AgentDesktopCli, AgentDesktopDriver, CONTRACT_VERSION
from cua_desktop_adapter import CuaError, input_outcome
from desktop_adapter import DesktopError
import test_desktop_adapter as fixtures


def envelope(command, data=None, *, error=None):
    return {"version": CONTRACT_VERSION, "command": command, "ok": error is None,
            "data" if error is None else "error": data if error is None else error}


class NativeJsonFixture:
    """Emits the audited upstream shape, not a claim of Win32 execution."""
    def __init__(self):
        self.calls, self.counter = [], 0
        self.value, self.title = "before", "Controlled Test Editor"
        self.delivery, self.retry, self.error = "delivered_unverified", "unsafe", False
        self.complete, self.identity_unproven, self.secure = True, False, False
        self.sha256, self.binary = "a" * 64, Path(__file__)
        self._version_checked, self._broken = True, False

    def check_version(self):
        pass

    def execute(self, argv, *, mutation=False):
        self.calls.append((list(argv), mutation))
        if argv[0] == "snapshot":
            self.counter += 1
            identifier = "sabc" + str(self.counter)
            nodes = [{"role": "textfield", "name": "Document", "value": self.value,
                      "ref_id": "@" + identifier + ":e1", "states": ["secure"] if self.secure else [],
                      "available_actions": ["SetValue"], "bounds": {"x": 10, "y": 10, "width": 1, "height": 1}},
                     {"role": "button", "name": "Save", "ref_id": "@" + identifier + ":e2",
                      "available_actions": ["Click"], "bounds": {"x": 20, "y": 30, "width": 40, "height": 20}}]
            return envelope("snapshot", {"app": "fixture.exe", "window": {"id": "w-456", "title": self.title},
                "snapshot_id": identifier, "ref_count": 2, "complete": self.complete,
                "identity_unproven": self.identity_unproven, "tree": {"role": "window", "children": nodes}})
        if argv[0] == "set-value": self.value = argv[2]
        if argv[0] == "click": self.title = "Saved"
        result = {"action": argv[0], "disposition": {"delivery": self.delivery, "retry": self.retry},
                  "steps": [{"label": "fixture", "mechanism": "semantic_api", "outcome": "success"}]}
        return envelope(argv[0], result) if not self.error else envelope(argv[0], error={**result, "code": "ACTION_FAILED"})

    def stop(self):
        self._broken = True
        return {"processGroupStoppedVerified": True, "contractFixture": True}

    def recovery_binding(self):
        return {"format": "windows_original_job_v1", "jobName": "fixture-agent-job"}


class SemanticCompositionTests(unittest.TestCase):
    def setUp(self):
        fixtures.DesktopContractTests.setUp(self)
        self.cua, self.native = self.driver, NativeJsonFixture()
        self.driver = AgentDesktopDriver(self.cua, self.native)
        self.adapter.driver = self.driver

    tearDown = fixtures.DesktopContractTests.tearDown
    lease = fixtures.DesktopContractTests.lease
    action = fixtures.DesktopContractTests.action

    def observe(self, mode="semantic"):
        return self.adapter.observe(self.binding, {"pid": 123, "windowId": 456, "observationMode": mode}, self.metadata, self.current)

    def element(self, observation, role):
        return next(item for item in observation["state"]["elements"] if item["role"] == role)

    def test_pure_semantic_set_value_uses_qualified_original_ref_and_no_cua_capture(self):
        observation, lease = self.observe(), self.lease()
        edit = self.element(observation, "Edit")
        self.assertRegex(edit["element_token"], r"^s[0-9a-f]{8}:1$")
        original_ref = self.driver._refs[edit["element_token"]]["ref"]
        receipt = self.adapter.act(self.binding, observation["observationRef"], "peer-A",
            {"kind": "set_text", "lease": lease, "elementToken": edit["element_token"], "text": "中文 replacement",
             "verification": {"kind": "element_value_equals", "selector": {"role": "Edit", "label": "Document"}, "value": "中文 replacement"}},
            self.metadata, self.current, lambda: self.dispatched.append(True))
        self.assertEqual("completed", receipt["status"], receipt)
        inputs = [(args, mutation) for args, mutation in self.native.calls if mutation]
        self.assertEqual([(["set-value", original_ref, "中文 replacement", "--timeout-ms", "0"], True)], inputs)
        self.assertFalse(self.cua.calls)
        self.assertFalse(self.captures)
        self.assertTrue(all("--headed" not in args for args, _ in self.native.calls))
        self.assertTrue(all("--window-id" in args and "w-456" in args and "--include-bounds" in args for args, mutation in self.native.calls if not mutation))

    def test_semantic_click_is_headless_and_preserves_native_ref_across_fresh_snapshots(self):
        observation, lease = self.observe(), self.lease()
        button = self.element(observation, "Button")
        original_ref = self.driver._refs[button["element_token"]]["ref"]
        receipt = self.adapter.act(self.binding, observation["observationRef"], "peer-A",
            {"kind": "click", "lease": lease, "elementToken": button["element_token"],
             "verification": {"kind": "window_title_equals", "value": "Saved"}}, self.metadata, self.current, lambda: None)
        self.assertEqual("completed", receipt["status"], receipt)
        self.assertEqual([["click", original_ref, "--timeout-ms", "0"]], [args for args, mutation in self.native.calls if mutation])
        self.assertFalse(self.cua.calls)
        self.assertFalse(receipt["userGoalVerified"])

    def test_hybrid_observation_composes_real_cua_capture_without_publishing_native_refs(self):
        observation = self.observe("hybrid")
        self.assertIn("image", observation)
        self.assertNotIn("@s", json.dumps(observation))
        self.assertEqual("agent-desktop", observation["state"]["semantic_provider"])
        captures = [args for name, args in self.cua.calls if name == "get_window_state"]
        self.assertEqual(1, len(captures))
        self.assertFalse(captures[0]["include_accessibility_tree"])
        self.assertTrue(captures[0]["include_screenshot"])

    def test_uncertain_native_delivery_cannot_be_completed_by_matching_postcondition(self):
        observation, lease = self.observe(), self.lease()
        self.native.delivery, self.native.retry = "delivery_uncertain", "unsafe"
        receipt = self.adapter.act(self.binding, observation["observationRef"], "peer-A",
            {"kind": "click", "lease": lease, "elementToken": self.element(observation, "Button")["element_token"],
             "verification": {"kind": "window_title_equals", "value": "Saved"}}, self.metadata, self.current, lambda: None)
        self.assertTrue(receipt["observedConditionMatched"])
        self.assertEqual("unknown", receipt["status"])
        self.assertIsNone(receipt["inputDispatched"])
        self.assertFalse(receipt["businessResultVerified"])

    def test_secure_or_unproven_or_changed_target_never_dispatches(self):
        lease = self.lease()
        self.native.secure = True
        observed = self.observe()
        secret = self.element(observed, "Edit")
        self.assertNotIn("value", secret)
        self.assertEqual([], secret["actions"])
        self.native.secure = False
        self.native.identity_unproven = True
        with self.assertRaisesRegex(DesktopError, "Complete native UIA"):
            self.observe()
        observed = self.observe("hybrid")
        with self.assertRaises(CuaError):
            self.adapter.act(self.binding, observed["observationRef"], "peer-A",
                {"kind": "click", "lease": lease, "elementToken": self.element(observed, "Button")["element_token"]}, self.metadata, self.current, lambda: None)
        self.native.identity_unproven = False
        observed = self.observe(); self.native.value = "changed"
        with self.assertRaises(DesktopError):
            self.adapter.act(self.binding, observed["observationRef"], "peer-A",
                {"kind": "set_text", "lease": lease, "elementToken": self.element(observed, "Edit")["element_token"], "text": "x"}, self.metadata, self.current, lambda: None)
        self.assertFalse(any(mutation for _, mutation in self.native.calls))

    def test_incomplete_semantic_tree_requires_explicit_image_observation(self):
        self.native.complete = False
        with self.assertRaisesRegex(DesktopError, "hybrid.*visual"):
            self.observe()
        observed = self.observe("hybrid")
        self.assertIn("image", observed)
        self.assertFalse(observed["state"]["elements_complete"])
        self.assertFalse(any(mutation for _, mutation in self.native.calls))

    def test_actual_dispositions_do_not_turn_error_into_no_effect(self):
        cases = [("not_delivered", "safe", False), ("delivered_unverified", "unsafe", True),
                 ("delivered_verified", "unsafe", True), ("delivery_uncertain", "unsafe", None), ("unknown", "unknown", None)]
        for delivery, retry, dispatched in cases:
            result = AgentDesktopDriver._action_result(envelope("click", error={"code": "ACTION_FAILED", "disposition": {"delivery": delivery, "retry": retry}}))
            self.assertIs(dispatched, input_outcome(result)["inputDispatched"])
        with self.assertRaises(CuaError):
            AgentDesktopDriver._action_result(envelope("click", {"disposition": {"delivery": "delivered_verified", "retry": "safe"}}))
        with self.assertRaises(CuaError):
            AgentDesktopDriver._action_result(envelope("click", {"disposition": {"delivery": "delivered_unverified", "retry": "unsafe"}, "steps": [{"mechanism": "physical_synthetic"}]}))


class ProcessFixture:
    def __init__(self, raw, code=0):
        self.stdout, self.code, self.closed = io.BytesIO(raw), code, False

    def wait(self, timeout):
        return self.code

    def close(self):
        self.closed = True


class TransportFixtureBackend:
    name = "windows-fixture-not-native"
    def __init__(self):
        self.calls, self.processes, self.responses = [], [], []
        self.stopped = True

    def spawn_command(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if argv[1] == "version":
            raw = json.dumps(envelope("version", {"version": "1.1.0", "os": "windows", "target": "x86_64"})).encode()
            code = 0
        else:
            raw, code = self.responses.pop(0)
        process = ProcessFixture(raw, code); self.processes.append(process)
        return process

    def terminate(self, process, *, hard):
        return {"processGroupStoppedVerified": self.stopped, "contractFixture": True}


class JsonCliFixture(AgentDesktopCli):
    """Only skips artifact admission. Actual bounded JSON transport is exercised."""
    def _prepare(self):
        if self._broken:
            raise CuaError("Fixture lifecycle is closed", effect_unknown=True)


class NativeTransportContractTests(unittest.TestCase):
    def client(self):
        backend = TransportFixtureBackend()
        return JsonCliFixture(Path(__file__).resolve(), "a" * 64, backend, private_state=Path(__file__).parent.resolve()), backend

    def test_exact_argv_and_private_environment_no_shell_or_credentials(self):
        client, backend = self.client()
        backend.responses = [(json.dumps(envelope("set-value", {"disposition": {"delivery": "delivered_unverified", "retry": "unsafe"}})).encode(), 0)]
        old = os.environ.get("POCKET_TEST_SECRET")
        os.environ["POCKET_TEST_SECRET"] = "must-not-inherit"
        try:
            result = client.execute(["set-value", "@sabc:e1", "$(not-a-command) 中文", "--timeout-ms", "0"], mutation=True)
        finally:
            if old is None: os.environ.pop("POCKET_TEST_SECRET", None)
            else: os.environ["POCKET_TEST_SECRET"] = old
            client.stop()
        self.assertTrue(result["ok"])
        self.assertEqual("version", backend.calls[0][0][1])
        self.assertEqual("$(not-a-command) 中文", backend.calls[1][0][3])
        self.assertTrue(all("shell" not in args and "POCKET_TEST_SECRET" not in args["env"] for _, args in backend.calls))
        self.assertTrue(all(process.closed for process in backend.processes))
        self.assertEqual(backend.calls[0][1]["job_name"], backend.calls[1][1]["job_name"])

    def test_malformed_or_wrong_version_reply_is_unknown_after_dispatch_and_never_retried(self):
        for raw in (b'{"version":"2.6","version":"2.6"}', b'{"ok":true}',
                    json.dumps({**envelope("click", {}), "version": "2.7"}).encode()):
            client, backend = self.client(); backend.responses = [(raw, 0)]
            with self.assertRaises(CuaError) as failure:
                client.execute(["click", "@sabc:e1", "--timeout-ms", "0"], mutation=True)
            self.assertTrue(failure.exception.effect_unknown)
            with self.assertRaises(CuaError):
                client.execute(["click", "@sabc:e1", "--timeout-ms", "0"], mutation=True)
            self.assertEqual(2, len(backend.calls))  # version plus the one input

    def test_real_client_does_not_claim_windows_on_this_host(self):
        if os.name == "nt": self.skipTest("This assertion is Linux-specific")
        with tempfile.TemporaryDirectory() as root:
            client = AgentDesktopCli(Path(root) / "agent-desktop.exe", "a" * 64, TransportFixtureBackend(), private_state=Path(root))
            with self.assertRaises(CuaError): client.check_version()


if __name__ == "__main__":
    unittest.main()
