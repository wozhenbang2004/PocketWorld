"""Original unresolved jobs and owned assignment records survive soft retention."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import assignment_workspaces
from pocket_companion import JobManager, RequestError


TOKEN = "fixture-only-job-retention-token-more-than-32-characters"


class JobRetentionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-job-retention-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        workspace = self.root / "workspace"
        workspace.mkdir()
        self.config = {"stateDir": str(self.root / "state"), "environments": [{
            "id": "local", "root": str(workspace), "kind": "local", "workers": ["command"],
            "commands": {"python": sys.executable}}]}
        self.manager = JobManager(self.config, TOKEN)
        self.addCleanup(lambda: self.manager.close() if not self.manager.closed else None)

    def job(self, number, status="completed", **fields):
        jobid = "fixture-" + str(number).zfill(4)
        job = {"id": jobid, "operationId": "operation-" + str(number), "fingerprint": "fixture-original-" + str(number),
               "createdAt": number, "updatedAt": number, "status": status, "worker": "command",
               "environmentId": "local", "resourceId": "resource-" + str(number),
               "resourcesHeld": False, "dispatchFenceFinal": True, **fields}
        self.manager.jobs[jobid] = job
        return job

    def fill(self, count=500, status="completed", persist=False):
        for index in range(1, count + 1):
            job = self.job(index, status)
            if persist:
                self.manager._save(job)

    def test_unresolved_terminal_records_are_never_pruned_to_meet_soft_limit(self):
        for status in ("unknown", "interrupted"):
            with self.subTest(status=status):
                self.manager.jobs.clear()
                original = self.job(0, status)
                self.manager._save(original)
                self.fill()
                with self.manager.lock:
                    self.manager._prune()
                self.assertTrue(original["id"] in self.manager.jobs, "Unresolved original was pruned")
                self.assertTrue((self.manager.state / ("job-" + original["id"] + ".json")).exists())
                self.assertEqual(status, self.manager.get(original["id"])["status"])
                self.assertEqual(500, len(self.manager.jobs))
                self.assertNotIn("fixture-0001", self.manager.jobs)

    def test_terminal_record_with_owned_resources_or_live_tracking_is_retained(self):
        for reason in ("resources", "process", "driver", "stopping", "cleanup"):
            with self.subTest(reason=reason):
                self.manager.jobs.clear()
                original = self.job(0)
                if reason == "resources":
                    original["resourcesHeld"] = True
                if reason == "cleanup":
                    original["dispatchFenceFinal"] = False
                self.fill()
                if reason == "process":
                    self.manager.processes[original["id"]] = object()
                if reason == "driver":
                    self.manager.drivers[original["id"]] = object()
                if reason == "stopping":
                    self.manager.stopping.add(original["id"])
                try:
                    with self.manager.lock:
                        self.manager._prune()
                    self.assertTrue(original["id"] in self.manager.jobs, "Original live ownership was pruned")
                    self.assertEqual(500, len(self.manager.jobs))
                finally:
                    self.manager.processes.clear()
                    self.manager.drivers.clear()
                    self.manager.stopping.clear()

    def test_settled_source_keeps_original_assignment_lookup_even_without_live_child(self):
        record = {"checkoutId": "fixture-original-checkout", "status": "ready"}
        source = self.job(0, assignmentWorkspaces=[record])
        self.manager._save(source)
        self.fill()
        with self.manager.lock:
            self.manager._prune()
        self.assertIs(record, assignment_workspaces.lookup(self.manager, source["id"], record["checkoutId"]))
        self.assertTrue((self.manager.state / ("job-" + source["id"] + ".json")).exists())

    def test_legacy_fences_private_inputs_and_unresolved_decisions_are_retained(self):
        cases = [{"dispatchFenceFinal": None}, {"dispatchFenceFinal": False},
                 {"inputState": {"phase": "cancelled"}}, {"steeringUnknown": True},
                 {"approvalReceipts": [{"status": "unknown"}]}, {"questionReceipts": [{"status": "sending"}]}]
        for fields in cases:
            with self.subTest(fields=fields):
                self.manager.jobs.clear()
                original = self.job(0, "blocked", **fields)
                if fields.get("dispatchFenceFinal", True) is None:
                    original.pop("dispatchFenceFinal")
                self.fill()
                with self.manager.lock:
                    self.manager._prune()
                self.assertTrue(original["id"] in self.manager.jobs, "Original cleanup/decision proof was lost")
                self.assertEqual(500, len(self.manager.jobs))

    def test_restart_loads_protected_records_older_than_last_500_filenames_without_dispatch(self):
        original = self.job(0, "unknown", resourcesHeld=True)
        self.manager._save(original)
        self.fill(status="unknown", persist=True)
        self.manager.close()
        with patch("pocket_companion.subprocess.Popen") as launch:
            self.manager = JobManager(self.config, TOKEN)
            with self.manager.lock:
                self.manager._prune()
            launch.assert_not_called()
        self.assertEqual(501, len(self.manager.jobs))
        self.assertIn(original["id"], self.manager.jobs)
        self.assertIn(original["resourceId"], self.manager.resources)
        self.assertTrue(self.manager.get(original["id"])["resourcesHeld"])
        self.assertEqual(501, len(list(self.manager.state.glob("job-*.json"))))

    def test_safe_settled_records_still_prune_without_freeing_operation_ledger(self):
        original = self.job(0)
        self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at) VALUES(?,?,?,?,?)",
                                    (original["operationId"], original["fingerprint"], original["id"], "completed", 0))
        self.manager._save(original)
        self.fill()
        with self.manager.lock:
            self.manager._prune()
        with self.assertRaises(RequestError) as missing:
            self.manager.get(original["id"])
        self.assertEqual(404, missing.exception.status)
        self.assertFalse((self.manager.state / ("job-" + original["id"] + ".json")).exists())
        self.assertEqual(original["id"], self.manager.operation(original["operationId"])["id"])
        self.assertTrue(self.manager.operation(original["operationId"])["archived"])


if __name__ == "__main__":
    unittest.main()
