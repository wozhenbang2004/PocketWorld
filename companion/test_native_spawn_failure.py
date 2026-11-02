"""Direct construction-failure regressions, not simulated Win32 acceptance.

POSIX cases exercise the ORIGINAL mTLS/SQLite/JobManager with a real child
whose effect already happened before the failing RHS returns. Real Win32 pipe
and ResumeThread injections are separately gated and SKIP on Linux.
"""
import ast
import inspect
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

from platform_backend import NativeProcessStartError
from platform_windows import WindowsBackend, WindowsJobProcess
from pocket_companion import JobManager
import test_device_companion as original


class ConstructionOrderContract(unittest.TestCase):
    def test_all_parent_pipe_wrapping_precedes_the_only_resume(self):
        tree = ast.parse(inspect.getsource(WindowsJobProcess))
        constructor = next(node for node in tree.body[0].body if isinstance(node, ast.FunctionDef) and node.name == "__init__")
        calls = [node for node in ast.walk(constructor) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)]
        wraps = [node.lineno for node in calls if node.func.attr == "fdopen"]
        resumes = [node.lineno for node in calls if node.func.attr == "ResumeThread"]
        self.assertEqual(1, len(resumes))
        self.assertTrue(wraps)
        self.assertGreater(resumes[0], max(wraps))  # Source contract, NOT native execution proof.


@unittest.skipIf(os.name == "nt", "Real POSIX original-supervisor fault fixture, not Win32 evidence")
class OriginalManagerConstructionFailure(unittest.TestCase):
    setUp = original.NativeCompanionIntegrationTests.setUp
    tearDown = original.NativeCompanionIntegrationTests.tearDown
    request = original.NativeCompanionIntegrationTests.request
    receive = original.NativeCompanionIntegrationTests.receive
    success = original.NativeCompanionIntegrationTests.success
    finished = original.NativeCompanionIntegrationTests.finished

    def injected_start(self, verified):
        marker = self.workspace / "already-executed.txt"
        actual_spawn, starts = self.manager._spawn, []
        def fail_after_creation(*args, **kwargs):
            process = actual_spawn(*args, **kwargs)
            starts.append(process)
            deadline = time.monotonic() + 3
            while not marker.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            if not marker.exists():
                JobManager._terminate(process, hard=True)
                raise AssertionError("Controlled real child failed to reach its marker")
            proof = JobManager._terminate(process, hard=True) if verified else {
                "terminationRequested": True, "processGroupStoppedVerified": False,
                "message": "Controlled cleanup-query fault; this is not Win32 evidence"}
            raise NativeProcessStartError(process, {
                "processCreated": True, "parentPipesReady": True,
                "primaryThreadResumeAttempted": True, "primaryThreadResumeSucceeded": False,
                "userCodeMayHaveRun": True}, proof)
        raw = self.request("command.run", {"argv": ["python", "-c", "from pathlib import Path; import time; Path('already-executed.txt').write_text('中文真实效果',encoding='utf-8'); time.sleep(30)"]})
        with patch.object(self.manager, "_spawn", side_effect=fail_after_creation):
            job = self.finished(self.success(self.receive(raw)))
            deadline = time.monotonic() + 3
            while job["id"] in self.manager.native_device_admissions and time.monotonic() < deadline:
                time.sleep(.01)
            job = self.manager.get(job["id"])
            repeated = self.success(self.receive(raw))
        self.assertEqual(job["id"], repeated["id"])
        self.assertEqual(1, len(starts))
        self.assertEqual("中文真实效果", marker.read_text(encoding="utf-8"))
        self.assertEqual("unknown", job["status"])
        self.assertTrue(job["processStarted"])
        self.assertFalse(job["notStartedConfirmed"])
        self.assertTrue(job["processStartFacts"]["userCodeMayHaveRun"])
        self.assertEqual(verified, job["processCleanupProof"]["processGroupStoppedVerified"])
        self.assertEqual(verified, job["dispatchFenceFinal"])
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        return job, starts[0]

    def test_created_then_constructor_failed_retains_real_stop_but_never_not_started(self):
        job, process = self.injected_start(True)
        self.assertIsNotNone(process.poll())
        self.assertNotIn(self.manager.jobs[job["id"]]["resourceId"], self.manager.resources)

    def test_unverified_constructor_cleanup_keeps_original_process_resource_and_unknown(self):
        job, process = self.injected_start(False)
        self.assertIsNone(process.poll())
        self.assertIn(self.manager.jobs[job["id"]]["resourceId"], self.manager.resources)
        self.assertIs(process, self.manager.processes[job["id"]])
        # tearDown performs the explicit ORIGINAL manager close/stop, not retry.

    def test_precreation_failure_can_still_report_confirmed_not_started(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "raise AssertionError('must not run')"]})
        with patch.object(self.manager, "_spawn", side_effect=OSError("controlled pre-CreateProcess failure")):
            job = self.finished(self.success(self.receive(raw)))
            deadline = time.monotonic() + 3
            while job["id"] in self.manager.native_device_admissions and time.monotonic() < deadline:
                time.sleep(.01)
        job = self.manager.get(job["id"])
        self.assertEqual("failed", job["status"])
        self.assertTrue(job["notStartedConfirmed"])
        self.assertFalse(job["processStarted"])


@unittest.skipUnless(os.name == "nt", "Requires actual Win32 process/Job APIs; Linux is not native proof")
class NativeWindowsConstructionFailure(unittest.TestCase):
    def test_real_pipe_wrapper_failure_never_resumes_created_child(self):
        with tempfile.TemporaryDirectory(prefix="pocket-native-construction-") as directory:
            marker = Path(directory) / "must-not-run.txt"
            backend = WindowsBackend()
            with patch("platform_windows.os.fdopen", side_effect=OSError("controlled parent fdopen failure")):
                with self.assertRaises(NativeProcessStartError) as raised:
                    backend.spawn_command([sys.executable, "-c", "from pathlib import Path; Path('must-not-run.txt').write_text('bad')"],
                                          cwd=directory, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE)
            failure = raised.exception
            self.assertTrue(failure.start_facts["processCreated"])
            self.assertFalse(failure.start_facts["parentPipesReady"])
            self.assertFalse(failure.start_facts["primaryThreadResumeAttempted"])
            self.assertFalse(failure.start_facts["userCodeMayHaveRun"])
            self.assertTrue(failure.cleanup_proof["processGroupStoppedVerified"], failure.cleanup_proof)
            self.assertEqual(0, failure.cleanup_proof["activeProcesses"])
            self.assertFalse(marker.exists())

    def test_real_resume_then_wrapper_exception_preserves_created_effect_and_job_proof(self):
        with tempfile.TemporaryDirectory(prefix="pocket-native-resume-") as directory:
            marker = Path(directory) / "already-run.txt"
            backend = WindowsBackend()
            api, k = backend.api, backend.api.k
            resume = k.ResumeThread
            resume.argtypes, resume.restype = [api.w.HANDLE], api.w.DWORD
            def resumed_then_failed(thread):
                result = resume(thread)
                deadline = time.monotonic() + 5
                while result != 0xFFFFFFFF and not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                raise OSError("controlled Python exception AFTER actual ResumeThread")
            with patch.object(k, "ResumeThread", resumed_then_failed), patch("platform_windows.Win32", return_value=api):
                with self.assertRaises(NativeProcessStartError) as raised:
                    backend.spawn_command([sys.executable, "-c", "from pathlib import Path; import time; Path('already-run.txt').write_text('actual-effect'); time.sleep(30)"],
                                          cwd=directory, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE)
            failure = raised.exception
            self.assertTrue(marker.exists())
            self.assertTrue(failure.start_facts["parentPipesReady"])
            self.assertTrue(failure.start_facts["primaryThreadResumeAttempted"])
            self.assertTrue(failure.start_facts["userCodeMayHaveRun"])
            self.assertTrue(failure.cleanup_proof["processGroupStoppedVerified"], failure.cleanup_proof)
            self.assertEqual(0, failure.cleanup_proof["activeProcesses"])


if __name__ == "__main__":
    unittest.main()
