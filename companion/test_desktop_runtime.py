"""Actual runtime composition and persisted worker bindings; no Windows process is started."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from agent_desktop_driver import AgentDesktopDriver
from cua_desktop_adapter import McpCuaDriver
from desktop_runtime import attach_desktop, DesktopRuntime, UnavailableDesktop
from pocket_companion import JobManager


class RuntimeFixtureBackend:
    name = "windows"
    def process_identity(self, pid):
        return {"available": True, "exists": True, "exited": False, "pid": pid, "created": "runtime-fixture", "sessionId": 1}

    def logon_context(self):
        return {"sessionId": 1, "userSid": "runtime-fixture", "authenticationId": "runtime-fixture"}

    def read_bytes(self, root, name):
        return None


class DesktopRuntimeContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.manager = JobManager({"stateDir": self.temp.name, "environments": []}, "test-only-desktop-runtime-0000000000000000000000")
        self.identity = SimpleNamespace(device_id="pw1_" + "1" * 64, instance_id=self.manager.instance_id)
        self.artifacts = SimpleNamespace(source_device_id=self.identity.device_id)
        self.runtime = None
        self.configuration = {"enabled": True, "binary": str(Path(__file__).resolve()), "sha256": "a" * 64}

    def tearDown(self):
        if self.runtime: self.runtime.close()
        self.manager.close()
        self.temp.cleanup()

    def attach(self, config):
        with patch("desktop_runtime.get_backend", return_value=RuntimeFixtureBackend()):
            self.runtime = attach_desktop(self.manager, self.identity, self.artifacts, config=config)
        return self.runtime

    def test_old_cua_configuration_remains_supported(self):
        runtime = self.attach(self.configuration)
        self.assertIsInstance(runtime, DesktopRuntime)
        self.assertIsInstance(runtime.jobs.adapter.driver, McpCuaDriver)
        binding = json.loads(self.manager.ledger.execute("SELECT binding_json FROM desktop_worker_instances").fetchone()[0])
        self.assertEqual("windows_original_job_v1", binding["format"])

    def test_composite_configuration_preserves_both_original_jobs_and_stream_settings(self):
        runtime = self.attach({**self.configuration, "agentDesktop": {"binary": str(Path(__file__).resolve()), "sha256": "b" * 64},
                               "streaming": {"enabled": True, "iceServers": []}})
        self.assertIsInstance(runtime, DesktopRuntime)
        driver = runtime.jobs.adapter.driver
        self.assertIsInstance(driver, AgentDesktopDriver)
        self.assertEqual("b" * 64, driver.client.sha256)
        binding = json.loads(self.manager.ledger.execute("SELECT binding_json FROM desktop_worker_instances").fetchone()[0])
        self.assertEqual("windows_original_jobs_v2", binding["format"])
        self.assertEqual(2, len({worker["jobName"] for worker in binding["workers"]}))
        self.assertTrue(all(worker["format"] == "windows_original_job_v1" for worker in binding["workers"]))

    def test_invalid_optional_semantic_configuration_fails_closed(self):
        for semantic in ({"binary": "agent-desktop.exe", "sha256": "b" * 64}, {"binary": str(Path(__file__).resolve()), "sha256": "b" * 64, "headed": True}, []):
            result = self.attach({**self.configuration, "agentDesktop": semantic})
            self.assertIsInstance(result, UnavailableDesktop)
            self.assertFalse(result.describe()["available"])
        self.assertIsInstance(self.attach({**self.configuration, "unknownFlag": True}), UnavailableDesktop)

    def test_source_pin_license_and_runtime_contract_agree(self):
        import hashlib
        from agent_desktop_driver import UPSTREAM_COMMIT, UPSTREAM_VERSION, CONTRACT_VERSION
        root = Path(__file__).resolve().parents[1]
        pin = json.loads((root / "companion/packaging/agent-desktop-pin.json").read_text())
        self.assertEqual((UPSTREAM_COMMIT, UPSTREAM_VERSION, CONTRACT_VERSION), (pin["sourceCommit"], pin["driverVersion"], pin["contractVersion"]))
        self.assertEqual(pin["licenseSha256"], hashlib.sha256((root / pin["licenseFile"]).read_bytes()).hexdigest())
        self.assertFalse(pin["headed"])
        self.assertFalse(pin["automaticInputReplay"])
        self.assertIsNone(pin["artifactSha256"])


if __name__ == "__main__":
    unittest.main()
