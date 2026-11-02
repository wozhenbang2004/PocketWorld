"""Opt-in REAL Windows 11 x64 GUI gate. Never enabled by Linux fixture success.

Operator must explicitly set PW_CONTROLLED_WINDOWS_GUI=1 and provide the
source-built pinned Cua executable/hash on a controlled interactive test host.
This verifies local GUI + file bytes, NOT the phone/异网 release journey J01.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest

from cua_desktop_adapter import McpCuaDriver
from desktop_adapter import DesktopAdapter
from desktop_sessions import WindowsDesktopHealth
from platform_windows import WindowsBackend

OPT_IN = os.name == "nt" and os.environ.get("PW_CONTROLLED_WINDOWS_GUI") == "1"


@unittest.skipUnless(OPT_IN, "Actual interactive Windows GUI gate requires explicit controlled-host opt-in; not executed")
class RealWindowsGuiAcceptance(unittest.TestCase):
    def test_native_editor_chinese_hotkey_save_and_safe_file_readback_without_codex(self):
        self.assertGreaterEqual(sys.getwindowsversion().build, 22000, "This gate targets Windows 11, not hosted Windows Server")
        self.assertEqual("AMD64", platform.machine().upper())
        health = WindowsDesktopHealth()
        self.assertTrue(health.probe().get("available"), health.probe())
        binary, pin = os.environ.get("PW_CUA_BINARY"), os.environ.get("PW_CUA_SHA256")
        self.assertTrue(binary and pin, "An actual audited source-build artifact and SHA-256 are required")
        from device_artifacts import ArtifactTransferStore
        source = "pw1_" + "1" * 64  # Explicitly synthetic local-test identity, not a user's key.
        snapshots = ArtifactTransferStore(source_device_id=source, current=lambda descriptor, direction: None)
        backend = WindowsBackend()
        with tempfile.TemporaryDirectory(prefix="pocketworld-controlled-gui-") as temporary:
            root = Path(temporary)
            manager = SimpleNamespace(state=root, instance_id="controlled-local-gui-test", lock=threading.RLock(),
                                      ledger=sqlite3.connect(":memory:", check_same_thread=False))
            editor = backend.spawn_command([sys.executable, str(Path(__file__).with_name("windows_gui_fixture.py")), "--output", str(root / "saved-chinese.txt")],
                                            cwd=str(root), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            adapter = DesktopAdapter(manager, McpCuaDriver(binary, pin, backend, private_state=root / "cua-driver"), health, snapshots.register_snapshot, source)
            try:
                # The child prints only after its actual Win32 window exists.
                ready_queue = __import__("queue").Queue(maxsize=1)
                threading.Thread(target=lambda: ready_queue.put(editor.stdout.readline(65536)), daemon=True).start()
                target = json.loads(ready_queue.get(timeout=10))
                binding = {"kind": "desktop", "sessionId": health.probe()["sessionId"], "displayId": "primary", "windowId": target["windowId"]}
                metadata = {"taskId": "controlled-test-task", "requestRunId": "controlled-test-run", "requestExecutorEpoch": 1, "operationId": "controlled-read-1"}
                current = lambda: None  # Local explicit tester, not a remote execution path.
                before = adapter.observe(binding, {"pid": target["pid"], "windowId": target["windowId"]}, metadata, current)
                edits = [item for item in before["state"].get("elements", []) if item.get("role") in {"Edit", "Document"} and item.get("element_token")]
                self.assertEqual(1, len(edits), "The controlled edit must have one real UIA identity; do not fall back to blind typing")
                lease = adapter.control("acquire", binding, "explicit-local-test-operator", {"action": "acquire"}, current)["lease"]
                value = "你好，PocketWorld！\r\n手机是主端，电脑只做轻量桥接。"
                # R4-F003: a genuinely NONEMPTY native control, then English,
                # Chinese and final value. No select-all/typing fallback.
                self.assertIn("OLD", edits[0].get("value", ""))
                current_observation = before
                for index, replacement in enumerate(("NEW", "新文", value)):
                    target_edit = [item for item in current_observation["state"].get("elements", []) if item.get("role") == edits[0]["role"] and item.get("element_token")]
                    self.assertEqual(1, len(target_edit))
                    if index == 0:
                        # R4-F007: mutate ONLY the controlled fixture's native
                        # decoration. This is not foreground/user input, and is
                        # never used against a real user's application.
                        import ctypes
                        from ctypes import wintypes
                        user = ctypes.WinDLL("user32", use_last_error=True)
                        user.SetWindowTextW.argtypes, user.SetWindowTextW.restype = [wintypes.HWND, wintypes.LPCWSTR], wintypes.BOOL
                        user.UpdateWindow.argtypes, user.UpdateWindow.restype = [wintypes.HWND], wintypes.BOOL
                        self.assertTrue(user.SetWindowTextW(target["decorationWindowId"], "DECORATION B changed"))
                        user.UpdateWindow(target["decorationWindowId"])
                        time.sleep(0.1)
                        _, _, changed_png = adapter._capture({"pid": target["pid"], "windowId": target["windowId"]}, preflight=True)
                        self.assertNotEqual(before["captureSha256"], hashlib.sha256(changed_png).hexdigest(), "The actual unrelated region must really change")
                    typed = adapter.act(binding, current_observation["observationRef"], "explicit-local-test-operator",
                                        {"kind": "set_text", "lease": lease, "elementToken": target_edit[0]["element_token"], "text": replacement,
                                         "verification": {"kind": "element_value_equals", "selector": {"role": edits[0]["role"]}, "value": replacement}},
                                        {**metadata, "operationId": "controlled-replace-" + str(index)}, current, lambda: None)
                    self.assertEqual("completed", typed["status"], typed)
                    self.assertTrue(typed["textReplacementVerified"], typed)
                    current_observation = typed["afterObservation"]
                background_operation = "controlled-save-background-1"
                refused = adapter.act(binding, typed["afterObservationRef"], "explicit-local-test-operator",
                                    {"kind": "hotkey", "lease": lease, "keys": ["ctrl", "s"],
                                     "verification": {"kind": "window_title_equals", "value": "PocketWorld Test Editor - 已保存"}},
                                    {**metadata, "operationId": background_operation}, current, lambda: None)
                self.assertEqual("failed", refused["status"], refused)
                self.assertFalse(refused["inputDispatched"])
                self.assertTrue(refused["actionOutcome"]["foregroundEscalationPermitted"])
                self.assertIsNone(backend.read_bytes(str(root), "saved-chinese.txt"))
                fresh = adapter.observe(binding, {"pid": target["pid"], "windowId": target["windowId"]}, {**metadata, "operationId": "controlled-read-foreground"}, current)
                saved = adapter.act(binding, fresh["observationRef"], "explicit-local-test-operator",
                                    {"kind": "hotkey", "lease": lease, "keys": ["ctrl", "s"], "deliveryMode": "foreground", "foregroundAfterOperationId": background_operation,
                                     "verification": {"kind": "window_title_equals", "value": "PocketWorld Test Editor - 已保存"}},
                                    {**metadata, "operationId": "controlled-save-foreground-1"}, current, lambda: None)
                self.assertEqual("completed", saved["status"], saved)
                actual = backend.read_bytes(str(root), "saved-chinese.txt")
                self.assertEqual(value.encode("utf-8"), actual)
                self.assertEqual(hashlib.sha256(value.encode()).hexdigest(), hashlib.sha256(actual).hexdigest())
                self.assertFalse(saved["userGoalVerified"], "The adapter's window condition cannot itself certify a whole phone/network journey")
                cleared = adapter.act(binding, saved["afterObservationRef"], "explicit-local-test-operator",
                                      {"kind": "set_text", "lease": lease, "elementToken": next(item["element_token"] for item in saved["afterObservation"]["state"]["elements"] if item.get("role") == edits[0]["role"]), "text": "",
                                       "verification": {"kind": "element_value_equals", "selector": {"role": edits[0]["role"]}, "value": ""}},
                                      {**metadata, "operationId": "controlled-clear-once"}, current, lambda: None)
                self.assertEqual("unknown", cleared["status"], "Pinned UIA omits empty AND failed value reads; absence cannot prove clear")
                self.assertFalse(cleared["textReplacementVerified"])
                # Separate native TEST-ONLY readback proves whether the fixture
                # really cleared; it is not injected into the product receipt.
                user.GetWindowTextLengthW.argtypes, user.GetWindowTextLengthW.restype = [wintypes.HWND], ctypes.c_int
                ctypes.set_last_error(0)
                self.assertEqual(0, user.GetWindowTextLengthW(target["editWindowId"]))
                self.assertEqual(0, ctypes.get_last_error(), "Zero length with a native error is not a successful empty read")
                print(json.dumps({"scope": "controlled_local_windows_gui_and_file_only", "windowsBuild": sys.getwindowsversion().build,
                                  "driverSha256": pin, "bytes": len(actual), "fileSha256": hashlib.sha256(actual).hexdigest(),
                                  "beforeObservationRef": before["observationRef"], "afterObservationRef": saved["afterObservationRef"],
                                  "codexRequired": False, "phoneCrossNetworkVerified": False}, ensure_ascii=False))
            finally:
                adapter.close()
                proof = backend.terminate(editor)
                editor.close()
                manager.ledger.close()
                self.assertTrue(proof.get("processGroupStoppedVerified"), proof)


if __name__ == "__main__":
    unittest.main()
