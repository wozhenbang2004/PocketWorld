"""Pre-config media smoke branch; fixtures are not native Windows evidence."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import desktop_stream
import desktop_worker


REAL_RTC = all(importlib.util.find_spec(name) is not None for name in ("aiortc", "av"))


class WorkerStreamProbeContracts(unittest.TestCase):
    def invoke(self, result=None, failure=None):
        output = io.StringIO()
        forbidden = lambda *args, **kwargs: self.fail("Probe touched user state, identity, desktop or installation")
        installation = SimpleNamespace(WindowsInstalledContext=forbidden)
        with patch.object(sys, "argv", ["desktop_worker.py", "--stream-self-test", "--config", "must-not-read.json"]), \
                patch.object(desktop_worker, "_state", side_effect=forbidden), \
                patch.object(desktop_worker, "_mutex", side_effect=forbidden), \
                patch.object(desktop_worker, "WindowsDesktopHealth", side_effect=forbidden), \
                patch.dict(sys.modules, {"windows_installation": installation}), \
                patch.object(desktop_stream, "runtime_probe", return_value=result, side_effect=failure), \
                contextlib.redirect_stdout(output):
            try:
                desktop_worker.main()
                code = 0
            except SystemExit as error:
                code = error.code
        return code, json.loads(output.getvalue())

    def test_probe_runs_before_config_installation_identity_or_capture(self):
        fixture = {"format": "inert-test-probe", "fixture": True}
        self.assertEqual((0, fixture), self.invoke(result=fixture))

    def test_native_import_or_codec_failure_is_nonzero_and_no_success_claim(self):
        code, result = self.invoke(failure=ImportError("inert native codec failure"))
        self.assertEqual(2, code)
        self.assertFalse(result["available"])
        self.assertFalse(result["networkOpened"])
        self.assertFalse(result["desktopCaptured"])
        self.assertNotIn("encodedBytes", result)

    @unittest.skipIf(REAL_RTC, "Missing-dependency counterexample requires absent SDKs")
    def test_actual_source_cli_missing_dependencies_does_not_initialize_state(self):
        with tempfile.TemporaryDirectory(prefix="desktop-stream-cli-") as directory:
            result = subprocess.run([sys.executable, "-B", str(Path(desktop_worker.__file__).resolve()), "--stream-self-test"],
                                    cwd=directory, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=15, check=False)
            self.assertEqual(2, result.returncode, result.stderr.decode())
            self.assertFalse(json.loads(result.stdout)["available"])
            self.assertEqual([], list(Path(directory).iterdir()))

    @unittest.skipUnless(REAL_RTC, "Prepared aiortc/PyAV not installed; no native codec success claimed")
    def test_actual_prepared_codec_probe_uses_no_peer_capture_or_socket(self):
        import aiortc
        with patch.object(aiortc, "RTCPeerConnection", side_effect=AssertionError("No peer allowed")), \
                patch.object(desktop_stream.PillowDesktopCapture, "__call__", side_effect=AssertionError("No desktop capture allowed")), \
                patch("socket.socket", side_effect=AssertionError("No network allowed")):
            result = desktop_stream.runtime_probe()
        self.assertTrue(result["available"])
        self.assertGreater(result["encodedBytes"], 0)
        self.assertEqual((64, 48), (result["width"], result["height"]))


if __name__ == "__main__":
    unittest.main()
