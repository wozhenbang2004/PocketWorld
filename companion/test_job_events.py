"""Original Companion/driver replay over local HTTP; CLI peers and fault rows are synthetic."""
import json
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from unittest.mock import patch

import job_events
from pocket_companion import JobManager, RequestError
from remote_interactions import Progress

TOKEN = "native-read-reconnect-fixture-token-longer-than-32-characters"


class EventReplayTests(unittest.TestCase):
    def start(self, root):
        log = (root / "stderr.log").open("wb")
        process = subprocess.Popen([sys.executable, "-B", "-u", str(Path(__file__).with_name("event_replay_fixture_server.py")), str(root)], stdout=subprocess.PIPE, stderr=log)
        self.addCleanup(log.close)
        def stop():
            process.terminate()
            try: process.wait(timeout=7)
            except subprocess.TimeoutExpired: process.kill();process.wait(timeout=2)
            process.stdout.close()
            self.assertEqual(0, process.returncode, (root / "stderr.log").read_text())
            released = json.loads((root / "resource-release.json").read_text())
            self.assertEqual({"listenerClosed": True, "activeProcesses": 0}, released)
        self.addCleanup(stop)
        self.assertTrue(select.select([process.stdout], [], [], 5)[0], (root / "stderr.log").read_text())
        base = json.loads(process.stdout.readline())["baseUrl"]
        def request(path, body=None):
            payload = None if body is None else json.dumps(body).encode()
            with urlopen(Request(base + path, payload, headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}), timeout=4) as response:
                return json.load(response)["result"]
        return request

    def test_actual_codex_and_claude_saved_events_page_disconnect_catchup_and_current_request(self):
        for worker in ("codex_app_server", "claude"):
            with self.subTest(worker=worker), tempfile.TemporaryDirectory(prefix="c3-replay-peer-") as directory:
                root = Path(directory); request = self.start(root)
                job = request("/v1/jobs", {"operationId": "original-" + worker, "environmentId": "workspace", "worker": worker, "prompt": "Observe original protocol only", "timeoutSeconds": 40})
                until = time.monotonic() + 6
                while time.monotonic() < until:
                    job = request("/v1/jobs/" + job["id"])
                    if job.get("pendingApprovals") or job.get("pendingQuestions"): break
                    time.sleep(.01)
                self.assertTrue(job.get("pendingApprovals") or job.get("pendingQuestions"), job)
                self.assertTrue(job["capabilities"]["originalJobEvents"]["supported"])
                query = {"jobOperationId": job["operationId"], "filter": job_events.FILTER, "generation": "", "after": 0, "head": 0, "limit": 1}
                url = "/v1/jobs/" + job["id"] + "/events?"
                first = request(url + urlencode(query)); self.assertEqual("retention_gap", first["gap"]["reason"])
                self.assertEqual(first, request(url + urlencode(query)))  # Exact same durable page, not new events.
                rows = list(first["events"]); head = first["headSequence"]
                query.update(generation=first["generation"], after=first["nextSequence"], head=head, limit=32)
                (root / "drop-job-reads").touch()
                with self.assertRaises(Exception): request(url + urlencode(query))
                (root / "drop-job-reads").unlink()
                for _ in range(4):
                    page = request(url + urlencode(query)); rows.extend(page["events"])
                    self.assertEqual(head, page["headSequence"])
                    query["after"] = page["nextSequence"]
                    if not page["hasMore"]: break
                self.assertEqual(head, query["after"])
                self.assertEqual(list(range(rows[0]["sequence"], head + 1)), [row["sequence"] for row in rows])
                self.assertTrue(any(row["kind"] == ("approval" if worker == "codex_app_server" else "question") for row in rows))
                current = request("/v1/jobs/" + job["id"])
                self.assertEqual(job["pendingApprovals"], current["pendingApprovals"])
                self.assertEqual(job["pendingQuestions"], current["pendingQuestions"])
                self.assertEqual("1", (root / "submit-count").read_text())
                self.assertFalse((root / "approval-post-count").exists()); self.assertFalse((root / "question-post-count").exists())
                self.doCleanups()  # Terminate owned server before TemporaryDirectory teardown.

    def synthetic(self):
        temp = tempfile.TemporaryDirectory(prefix="c3-original-event-file-");self.addCleanup(temp.cleanup)
        root = Path(temp.name);workspace = root / "workspace";workspace.mkdir()
        config = {"stateDir": str(root / "state"), "environments": [{"id": "workspace", "kind": "local", "root": str(workspace), "workers": ["codex_app_server"]}]}
        manager = JobManager(config, TOKEN);self.addCleanup(lambda: manager.close() if not manager.closed else None)
        progress = Progress("codex_app_server")
        for n in range(100): progress.add("tool_output", "controlled-test-observation", str(n), text="row-" + str(n))
        job = {"id": "original-job", "operationId": "original-op", "worker": "codex_app_server", "status": "awaiting_input", "updatedAt": manager.now(), "createdAt": manager.now(), "environmentId": "workspace", "pendingQuestions": [{"requestId": "synthetic-request"}], "progress": progress.snapshot()}
        manager.jobs[job["id"]] = job;manager._save(job)
        query = {"jobOperationId": job["operationId"], "filter": job_events.FILTER, "generation": progress.generation, "after": "0", "head": "0", "limit": "32"}
        return manager, job, progress, query, config

    def test_actual_claude_child_sources_survive_authenticated_original_persisted_pages_without_actions(self):
        with tempfile.TemporaryDirectory(prefix="alma-original-child-events-") as directory:
            root = Path(directory); request = self.start(root)
            job = request("/v1/jobs", {"operationId": "original-child-source", "environmentId": "workspace", "worker": "claude", "prompt": "Read original public observations", "timeoutSeconds": 40})
            until = time.monotonic() + 6
            while time.monotonic() < until:
                job = request("/v1/jobs/" + job["id"])
                if job.get("pendingQuestions"): break
                time.sleep(.01)
            self.assertTrue(job.get("pendingQuestions"), job)
            query = {"jobOperationId": job["operationId"], "filter": job_events.FILTER, "generation": "", "after": 0, "head": 0, "limit": 32}
            url = "/v1/jobs/" + job["id"] + "/events?"; rows = []
            while True:
                page = request(url + urlencode(query)); rows.extend(page["events"])
                self.assertFalse(page["executionAuthorized"])
                if not page["hasMore"]: break
                query.update(generation=page["generation"], after=page["nextSequence"], head=page["headSequence"])
            children = [row for row in rows if "childSourceProtocol" in row]
            self.assertEqual(6, len(children))
            self.assertEqual({"toolu_child_a", "toolu_child_b"}, {row["parentToolUseId"] for row in children})
            for row in children:
                self.assertEqual(job["sessionId"], row["parentSessionId"])
                self.assertEqual(job["promptRequestId"], row["parentPromptRequestId"])
            self.assertNotIn("NEVER_EXPOSE_CHILD_THINKING", json.dumps(page))
            stored = json.loads((root / "state" / ("job-" + job["id"] + ".json")).read_text())
            self.assertEqual(children, [row for row in stored["progress"]["events"] if "childSourceProtocol" in row])
            self.assertEqual("1", (root / "submit-count").read_text())
            self.assertFalse((root / "approval-post-count").exists()); self.assertFalse((root / "question-post-count").exists())
            self.assertEqual(job["pendingQuestions"], request("/v1/jobs/" + job["id"])["pendingQuestions"])
            self.doCleanups()

    def test_fixed_head_retention_gap_generation_and_invalid_cursor_are_explicit(self):
        manager, job, progress, query, _ = self.synthetic()
        page = job_events.read(manager, job["id"], {**query, "after": "10", "head": "20"}, RequestError)
        self.assertEqual([], page["events"]);self.assertEqual(20, page["nextSequence"]);self.assertFalse(page["hasMore"])
        self.assertEqual({"reason": "retention_gap", "fromSequence": 11, "throughSequence": 20}, page["gap"])
        changed = job_events.read(manager, job["id"], {**query, "generation": "00000000-0000-0000-0000-000000000001"}, RequestError)
        self.assertEqual("generation_changed", changed["status"]);self.assertEqual([], changed["events"])
        for delta in ({"after": "101"}, {"head": "101"}, {"jobOperationId": "other-op"}, {"filter": "all-private-content"}, {"limit": "33"}, {"after": "1", "generation": ""}):
            with self.subTest(delta=delta), self.assertRaises(RequestError):job_events.read(manager, job["id"], {**query, **delta}, RequestError)

    def test_restart_preserves_generation_sequence_and_marks_prior_execution_interrupted(self):
        manager, job, _, query, config = self.synthetic()
        before = job_events.read(manager, job["id"], query, RequestError)
        manager.close(); restarted = JobManager(config, TOKEN);self.addCleanup(restarted.close)
        after = job_events.read(restarted, job["id"], query, RequestError)
        self.assertEqual(before, after);self.assertEqual("interrupted", restarted.get(job["id"])["status"])
        self.assertEqual([], restarted.get(job["id"])["pendingQuestions"]);self.assertEqual({}, restarted.processes)

    def test_unsaved_driver_tail_is_not_acknowledged_and_no_new_file_or_effect_is_created(self):
        manager, job, progress, query, _ = self.synthetic()
        before = job_events.read(manager, job["id"], query, RequestError)
        progress.add("tool_output", "controlled-test-observation", "new", text="not yet durably saved")
        job["progress"] = progress.snapshot()
        with patch("pocket_companion.atomic_json", side_effect=OSError("controlled original save failure")), self.assertRaises(OSError):manager._save(job)
        after = job_events.read(manager, job["id"], query, RequestError)
        self.assertEqual(before, after);self.assertNotIn("not yet durably saved", json.dumps(after))
        self.assertEqual(1, len(manager.jobs));self.assertEqual({}, manager.processes)


if __name__ == "__main__": unittest.main()
