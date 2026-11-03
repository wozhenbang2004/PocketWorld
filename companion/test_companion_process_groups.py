"""Real Linux descendants of the ORIGINAL JobManager, not Windows/Android evidence.

The test runner temporarily becomes a subreaper so its own orphaned fixture
children are reaped by exact PID. No unknown process or group is signalled.
"""
import ctypes
import errno
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import types
import unittest
from unittest.mock import patch

from pocket_companion import JobManager

TOKEN = "fixture-only-process-group-token-more-than-32-chars"


@unittest.skipUnless(sys.platform.startswith("linux"), "Requires actual Linux waitid/proc and fixture subreaper; not Windows/Android evidence")
class OriginalProcessGroupTests(unittest.TestCase):
    def setUp(self):
        self.libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        if self.libc.prctl(37, ctypes.byref(previous), 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "Unable to inspect fixture subreaper")
        self.was_subreaper = previous.value
        if self.libc.prctl(36, 1, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "Unable to own orphan fixture cleanup")
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-original-group-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.config = {"stateDir": str(self.root / "state"), "environments": [{
            "id": "local", "kind": "local", "root": str(self.workspace), "workers": ["command"],
            "commands": {"python": sys.executable}}]}
        self.manager = JobManager(self.config, TOKEN)
        self.child_pids = []

    def tearDown(self):
        try:
            self.manager.close()
        finally:
            for pid in self.child_pids:
                try:
                    os.kill(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                try:
                    os.waitpid(pid, 0)
                except ChildProcessError:
                    pass
            self.temp.cleanup()
            self.libc.prctl(36, self.was_subreaper, 0, 0, 0)

    def submit(self, script, operation="original-once"):
        return self.manager.submit({"operationId": operation, "environmentId": "local", "worker": "command",
                                    "argv": ["python", "-c", script], "timeoutSeconds": 10})

    def wait(self, check, message, limit=5):
        until = time.monotonic() + limit
        while time.monotonic() < until:
            value = check()
            if value:
                return value
            time.sleep(.02)
        self.fail(message)

    def start_orphan(self):
        child = ("import os,time; from pathlib import Path; Path('child.pid').write_text(str(os.getpid())); "
                 "stop=Path('stop-child'); beat=Path('child-effect'); "
                 "exec(\"while not stop.exists():\\n with beat.open('a') as f: f.write('effect\\\\n')\\n time.sleep(.02)\")")
        parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); time.sleep(.15)"
        job = self.submit(parent)
        self.wait(lambda: (self.workspace / "child.pid").exists(), "Owned child was not started")
        self.child_pids.append(int((self.workspace / "child.pid").read_text()))
        self.wait(lambda: (self.workspace / "child-effect").exists(), "Owned child did not produce its actual effect")
        return job

    def test_reaped_parent_cannot_release_workspace_while_its_child_still_writes(self):
        original = self.start_orphan()
        # Old code reaped the leader, waited 2.3s for stdout, then released the
        # resource despite this same-group child continuing to mutate files.
        time.sleep(2.8)
        current = self.manager.get(original["id"])
        self.assertNotIn(current["status"], {"completed", "failed", "cancelled"}, current)
        self.assertIn(self.manager.jobs[original["id"]]["resourceId"], self.manager.resources)
        second = self.submit("from pathlib import Path; Path('next-effect').write_text('next')", "next-once")
        time.sleep(.2)
        self.assertFalse((self.workspace / "next-effect").exists(), "A second writer entered the still-owned workspace")
        (self.workspace / "stop-child").touch()
        settled = self.wait(lambda: (r if r["status"] == "completed" else None) if (r := self.manager.get(original["id"])) else None, "Original group did not settle")
        self.assertTrue(settled["processCleanupProof"]["processGroupStoppedVerified"])
        self.wait(lambda: self.manager.get(second["id"])["status"] == "completed", "Queued original job did not run after actual release")
        self.assertEqual("next", (self.workspace / "next-effect").read_text())

    def test_unknown_observation_retains_exact_process_and_get_only_reconciles_without_replay(self):
        def unknown(process):
            return {"leaderReaped": False, "processGroupStoppedVerified": False, "observationUnknown": True,
                    "verificationMethod": "controlled_query_failure_not_absence"}
        with patch.object(JobManager, "_observe_process_group", side_effect=unknown):
            job = self.submit("from pathlib import Path; Path('once').write_text('actual effect')")
            self.wait(lambda: getattr(self.manager.processes.get(job["id"]), "_pocket_worker_finished", False), "Unknown group did not retain its finished original worker")
            original_process = self.manager.processes[job["id"]]
            self.assertTrue(getattr(original_process, "_pocket_worker_finished"))
            with self.manager.lock:
                for index in range(500):
                    key = "settled-retention-fixture-" + str(index)
                    self.manager.jobs[key] = {"id": key, "status": "completed", "createdAt": self.manager.now() + index,
                                              "resourcesHeld": False, "dispatchFenceFinal": True, "notStartedConfirmed": True}
                self.manager._prune()
            self.assertTrue(job["id"] in self.manager.jobs, "Retention lost the original process cleanup/query entry")
            self.assertTrue((self.manager.state / ("job-" + job["id"] + ".json")).exists())
            observed = self.manager.get(job["id"])
            self.assertEqual("unknown", observed["status"])
            self.assertTrue(observed["controls"]["cancel"], "The retained original must still expose its exact stop")
            self.assertIs(original_process, self.manager.processes[job["id"]])
            self.assertEqual(job["id"], self.submit("from pathlib import Path; Path('once').write_text('actual effect')")["id"])
            self.assertEqual(500, len(self.manager.jobs))
        reconciled = self.manager.get(job["id"])
        self.assertTrue(reconciled["processCleanupProof"]["processGroupStoppedVerified"])
        self.assertFalse(reconciled["resourcesHeld"])
        self.assertEqual("unknown", reconciled["status"], "Process absence cannot prove business effects")
        self.assertNotIn(job["id"], self.manager.processes)
        self.assertNotIn(self.manager.jobs[job["id"]]["resourceId"], self.manager.resources)
        self.assertEqual("actual effect", (self.workspace / "once").read_text())
        self.assertEqual(500, len(self.manager.jobs))

    def test_unknown_original_can_be_explicitly_stopped_without_replacement_process(self):
        with patch.object(JobManager, "_observe_process_group", return_value={"leaderReaped": False, "processGroupStoppedVerified": False, "observationUnknown": True}):
            job = self.start_orphan()
            self.wait(lambda: getattr(self.manager.processes.get(job["id"]), "_pocket_worker_finished", False), "Original unknown worker was not retained after supervision ended")
        stopped = self.manager.cancel(job["id"])
        self.assertTrue(stopped["processCleanupProof"]["processGroupStoppedVerified"], stopped)
        self.assertFalse(stopped["resourcesHeld"])
        self.assertEqual("unknown", stopped["status"])
        size = (self.workspace / "child-effect").stat().st_size
        time.sleep(.1)
        self.assertEqual(size, (self.workspace / "child-effect").stat().st_size)
        self.assertEqual(1, len(self.manager.jobs))

    def test_cold_restart_restores_original_fence_and_only_read_proof_can_release_it(self):
        job = self.start_orphan()
        restarted = JobManager(self.config, TOKEN)
        try:
            self.assertIn(self.manager.jobs[job["id"]]["resourceId"], restarted.resources)
            self.assertEqual({}, restarted.processes, "Cold metadata is not a live Popen")
            observed = restarted.get(job["id"])
            self.assertTrue(observed["resourcesHeld"])
            self.assertFalse(observed["controls"]["threadResume"])
            self.assertNotIn("_originalProcessGroup", observed)
            with patch("cold_session.os.killpg", side_effect=PermissionError(errno.EPERM, "fixture denied")):
                self.assertTrue(restarted.get(job["id"])["resourcesHeld"])
            (self.workspace / "stop-child").touch()
            self.wait(lambda: self.manager.get(job["id"])["status"] == "completed", "Original group did not finish")
            os.waitpid(self.child_pids[-1], 0)  # Reap this exact fixture orphan; cold proof requires actual ESRCH.
            settled = restarted.get(job["id"])
            self.assertFalse(settled["resourcesHeld"])
            self.assertTrue(settled["processCleanupProof"]["processGroupStoppedVerified"])
            self.assertEqual("interrupted", settled["status"], "OS absence is not a recovered command result")
            self.assertEqual(1, len(restarted.jobs))
            self.assertEqual(set(), restarted.resources)
        finally:
            restarted.close()

    def test_cold_group_identity_mismatch_is_not_release_or_stored_turn_authority(self):
        import cold_session
        job = self.start_orphan()
        original = self.manager.jobs[job["id"]]
        self.assertIn("_originalProcessGroup", original)
        self.assertNotIn("_coldProcess", original, "A command group is not a Codex stored-turn source")
        origin = original["_originalProcessGroup"]
        saved = origin["bootId"]
        origin["bootId"] = "00000000-0000-0000-0000-000000000000"
        try:
            self.assertFalse(cold_session.original_process_group_stopped(original, self.manager.environments["local"]))
            self.assertFalse(cold_session.eligible(original))
        finally:
            origin["bootId"] = saved

    def test_eperm_never_becomes_esrch_and_never_falls_through_to_an_empty_scan(self):
        process = types.SimpleNamespace(pid=12345, returncode=0)
        with patch("pocket_companion.os.killpg", side_effect=PermissionError(errno.EPERM, "fixture denied")), patch.object(Path, "iterdir", side_effect=AssertionError("EPERM is not absent")):
            proof = JobManager._observe_process_group(process)
        self.assertFalse(proof["processGroupStoppedVerified"])
        self.assertTrue(proof["observationUnknown"])

    def test_unreadable_membership_is_unknown_not_an_empty_group(self):
        process = types.SimpleNamespace(pid=12345, returncode=0)
        with patch("pocket_companion.os.killpg"), patch.object(Path, "is_dir", return_value=True), patch.object(Path, "iterdir", return_value=[Path("/proc/12345")]), patch.object(Path, "read_text", side_effect=PermissionError("fixture unreadable")):
            proof = JobManager._observe_process_group(process)
        self.assertFalse(proof["processGroupStoppedVerified"])
        self.assertTrue(proof["observationUnknown"])

    def test_lost_child_wait_identity_never_signals_a_reused_numeric_group(self):
        process = types.SimpleNamespace(pid=12345, returncode=None)
        with patch("pocket_companion.os.waitid", side_effect=ChildProcessError("fixture already reaped elsewhere")), patch("pocket_companion.os.killpg") as signal_group:
            proof = JobManager._terminate_group(process, hard=True)
        signal_group.assert_not_called()
        self.assertFalse(proof["processGroupStoppedVerified"])
        self.assertTrue(proof["observationUnknown"])

    def test_reaped_numeric_pid_never_becomes_new_signal_authority(self):
        process = types.SimpleNamespace(pid=12345, returncode=0)
        with patch.object(JobManager, "_observe_process_group", return_value={"leaderReaped": True, "processGroupStoppedVerified": False}), patch("pocket_companion.os.killpg") as signal_group:
            proof = JobManager._terminate_group(process, hard=True)
        signal_group.assert_not_called()
        self.assertFalse(proof["processGroupStoppedVerified"])
        self.assertFalse(proof["terminationRequested"])


if __name__ == "__main__":
    unittest.main()
