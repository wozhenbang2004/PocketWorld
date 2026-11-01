"""Real loopback raw PUT / original job / local bytes; the coding CLI is an explicit fixture peer."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import sys
import threading
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch
import input_files
from pocket_companion import CompanionHandler, RequestError
from test_companion import CompanionTests, TOKEN
from test_codex_driver import FIXTURE


INPUT_FIXTURE = FIXTURE.replace("    elif method=='turn/start':\n", """    elif method=='turn/start':
        import re,hashlib
        from pathlib import Path
        text=value['params']['input'][0]['text']
        inputs=re.findall(r'^(.pocketworld-inputs/[^\\n]+) \\([0-9]+ bytes; SHA-256 [a-f0-9]{64}\\)$',text,re.M)
        summary=[{'path':path,'bytes':len(Path(path).read_bytes()),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest()} for path in inputs]
        Path('input-summary.json').write_text(json.dumps(summary))
""")


class InputFileTests(unittest.TestCase):
    def setUp(self):
        self.f = CompanionTests()
        self.f.setUp()
        self.f.manager.environments["computer"].update(workers=["codex_app_server"], writeAllowed=True)
        script = self.f.root / "input-codex.py"
        script.write_text(INPUT_FIXTURE)
        self.launch = patch.object(self.f.manager, "_launch", return_value=([sys.executable, str(script), "final-without-start-ack"], str(self.f.workspace)))
        self.launch.start()

    def tearDown(self):
        self.launch.stop()
        self.f.tearDown()
        proof = {"test": self.id(), "listenerClosed": self.f.server.socket.fileno() == -1,
                 "listenerThreadAlive": self.f.thread.is_alive(), "activeProcesses": len(self.f.manager.processes)}
        if os.environ.get("C3_EVIDENCE_DIR"):
            with (Path(os.environ["C3_EVIDENCE_DIR"]) / "resource-releases.jsonl").open("a") as output:
                output.write(json.dumps(proof) + "\n")
        self.assertTrue(proof["listenerClosed"])
        self.assertFalse(proof["listenerThreadAlive"])
        self.assertEqual(0, proof["activeProcesses"])

    def body(self, contents=None):
        contents = contents or [("真实私人原件.bin", bytes(range(256)) * 2 + b"\x00"), ("empty.txt", b"")]
        files = [{"attachmentId": str(uuid.uuid4()), "name": name, "mimeType": "application/octet-stream", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} for name, raw in contents]
        return {"operationId": str(uuid.uuid4()), "environmentId": "computer", "environmentBinding": self.f.manager.environment_binding("computer"),
                "worker": "codex_app_server", "readOnly": True, "prompt": "Read only these explicitly selected inputs", "inputProtocol": input_files.PROTOCOL, "inputFiles": files}, contents

    def create(self, body):
        status, response = self.f.request("/v1/jobs", body)
        self.assertEqual(200, status, response)
        self.assertEqual("awaiting_input", response["result"]["status"])
        return response["result"]

    def put(self, job, index, raw, operation=None):
        request = Request(self.f.base + "/v1/jobs/" + job["id"] + "/inputs/" + str(index), data=raw, method="PUT", headers={
            "Authorization": "Bearer " + TOKEN, "Content-Type": "application/octet-stream",
            "X-Pocket-Job-Operation": operation or job["operationId"]})
        try:
            with urlopen(request, timeout=4) as response:
                return response.status, json.loads(response.read())
        except HTTPError as failure:
            return failure.code, json.loads(failure.read())

    def start(self, job, operation=None):
        return self.f.request("/v1/jobs/" + job["id"] + "/start-with-inputs", {
            "jobOperationId": job["operationId"], "startOperationId": operation or str(uuid.uuid4()),
            "manifestHash": job["inputState"]["manifestHash"]})

    def ready(self):
        body, contents = self.body()
        job = self.create(body)
        for index, (_, raw) in enumerate(contents):
            status, result = self.put(job, index, raw)
            self.assertEqual(200, status, result)
            job = result["result"]
        return job, contents

    def test_raw_unicode_binary_and_empty_files_are_verified_before_one_real_fixture_worker(self):
        body, contents = self.body()
        original_popen = __import__("subprocess").Popen
        with patch("pocket_companion.subprocess.Popen", wraps=original_popen) as processes:
            job = self.create(body)
            self.assertEqual(0, processes.call_count)
            self.assertFalse((self.f.workspace / "codex-turn-count").exists())
            self.assertNotIn("inputRequest", self.f.manager.get(job["id"]))
            self.assertEqual(409, self.start(job)[0])
            status, first = self.put(job, 0, contents[0][1])
            self.assertEqual(200, status, first)
            self.assertEqual("awaiting_upload", first["result"]["inputState"]["phase"])
            self.assertGreater(first["result"]["inputState"]["revision"], job["inputState"]["revision"])
            self.assertEqual(0, processes.call_count)
            status, second = self.put(job, 1, b"")
            self.assertEqual(200, status, second)
            ready = second["result"]
            self.assertEqual("ready", ready["inputState"]["phase"])
            self.assertGreater(ready["inputState"]["revision"], first["result"]["inputState"]["revision"])
            self.assertEqual(0, processes.call_count)
            start_id = str(uuid.uuid4())
            self.assertEqual(200, self.start(ready, start_id)[0])
            result = self.f.finish(job["id"])
            self.assertEqual(1, processes.call_count)
            self.assertEqual("completed", result["status"])
            self.assertTrue(result["inputState"]["workerStarted"])
            self.assertFalse(result["notStartedConfirmed"])
            self.assertEqual(200, self.start(ready, start_id)[0])
            self.assertEqual(409, self.start(ready, str(uuid.uuid4()))[0])
            self.assertEqual(1, processes.call_count)
        summary = json.loads((self.f.workspace / "input-summary.json").read_text())
        self.assertEqual([len(raw) for _, raw in contents], [value["bytes"] for value in summary])
        self.assertEqual([hashlib.sha256(raw).hexdigest() for _, raw in contents], [value["sha256"] for value in summary])
        self.assertEqual(1, len(self.f.manager.jobs))

    def test_lost_put_ack_only_original_get_settles_and_second_body_is_never_read(self):
        body, contents = self.body([("only.bin", b"ACTUAL PRIVATE INPUT")])
        job = self.create(body)
        marker = self.f.root / "drop-put-ack"
        marker.touch()
        class Drop(CompanionHandler):
            def _send(handler, status, value):
                if handler.command == "PUT" and status == 200 and marker.exists():
                    marker.unlink()
                    handler.close_connection = True
                    handler.connection.shutdown(socket.SHUT_RDWR)
                    return
                super()._send(status, value)
        self.f.server.RequestHandlerClass = Drop
        with self.assertRaises((OSError, __import__("http.client").client.RemoteDisconnected)):
            self.put(job, 0, contents[0][1])
        actual = self.f.manager.operation(job["operationId"])
        self.assertEqual("verified", actual["inputState"]["files"][0]["state"])
        class NeverRead:
            def read(self, _):
                raise AssertionError("A repeated original slot must not read another body")
        with self.assertRaises(RequestError):
            input_files.upload(self.f.manager, job["id"], 0, job["operationId"], len(contents[0][1]), NeverRead(), RequestError)
        self.assertEqual(1, len(self.f.manager.jobs))
        self.assertFalse(actual["inputState"]["workerStarted"])

    def test_cancel_while_raw_read_is_blocked_refuses_the_late_file_write(self):
        body, contents = self.body([("cancel.bin", b"LATE BYTES MUST NOT BE WRITTEN")])
        job = self.create(body)
        entered, release = threading.Event(), threading.Event()
        failures = []
        class Blocking:
            def read(self, _):
                entered.set()
                if not release.wait(3):
                    raise TimeoutError()
                return contents[0][1]
        def transfer():
            try:
                input_files.upload(self.f.manager, job["id"], 0, job["operationId"], len(contents[0][1]), Blocking(), RequestError)
            except BaseException as failure:
                failures.append(failure)
        thread = threading.Thread(target=transfer)
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            actual = self.f.manager.cancel(job["id"])
            self.assertTrue(actual["notStartedConfirmed"])
            self.assertFalse(actual["inputState"]["cleanupConfirmed"])
            release.set()
            thread.join(3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(1, len(failures))
            actual = self.f.manager.get(job["id"])
            self.assertEqual(0, actual["inputState"]["files"][0]["receivedBytes"])
            temporary = self.f.workspace / actual["inputState"]["inputDirectory"] / "0.uploading"
            self.assertEqual(0, temporary.stat().st_size)
            self.assertFalse((self.f.workspace / "codex-turn-count").exists())
            self.assertEqual(409, self.start(job)[0])
        finally:
            release.set()
            thread.join(3)

    def test_actual_ready_file_change_denies_start_before_worker_or_start_reservation(self):
        job, _ = self.ready()
        path = self.f.workspace / job["inputState"]["files"][0]["path"]
        path.chmod(0o600)
        path.write_bytes(b"OWNER FIXTURE ACTUAL LATER CHANGE")
        self.assertEqual(409, self.start(job)[0])
        actual = self.f.manager.get(job["id"])
        self.assertFalse(actual["inputState"]["workerStarted"])
        self.assertNotIn("startOperationId", actual["inputState"])
        self.assertFalse((self.f.workspace / "codex-turn-count").exists())

    def test_manifest_path_caps_and_generation_fail_before_original_job_reservation(self):
        body, _ = self.body()
        invalid = []
        missing_generation = dict(body);missing_generation.pop("environmentBinding");invalid.append(missing_generation)
        stale = dict(body);stale["environmentBinding"] = "0" * 64;invalid.append(stale)
        bad = json.loads(json.dumps(body));bad["inputFiles"][0]["name"] = "../outside";invalid.append(bad)
        bad = json.loads(json.dumps(body));bad["inputFiles"][0]["name"] = "长" * 90;invalid.append(bad)
        bad = json.loads(json.dumps(body));bad["inputFiles"][0]["bytes"] = 262145;invalid.append(bad)
        bad = json.loads(json.dumps(body));bad["inputFiles"] = bad["inputFiles"] * 5;invalid.append(bad)
        for value in invalid:
            status, response = self.f.request("/v1/jobs", value)
            self.assertIn(status, (400, 409, 413), response)
        self.assertEqual({}, self.f.manager.jobs)
        self.assertFalse((self.f.workspace / ".pocketworld-inputs").exists())
        with patch.object(input_files.os, "name", "nt"):
            self.assertFalse(input_files.capabilities(self.f.manager.environments["computer"])["supported"])

    def test_physical_workspace_replacement_never_adopts_later_root_for_the_same_job(self):
        body, contents = self.body([("original.bin", b"PRIVATE ORIGINAL")])
        job = self.create(body)
        self.f.workspace.rename(self.f.root / "previous-workspace")
        self.f.workspace.mkdir()
        self.assertEqual(409, self.put(job, 0, contents[0][1])[0])
        self.assertFalse((self.f.workspace / ".pocketworld-inputs").exists())
        self.assertFalse((self.f.workspace / "codex-turn-count").exists())

    def test_reopen_retains_original_input_revision_and_start_gap_never_claims_not_started(self):
        from concurrent.futures import Future
        import pocket_companion
        from pocket_companion import JobManager
        first, _ = self.ready()
        second_body, second_contents = self.body([("start-gap.bin", b"EXACT ORIGINAL START GAP")])
        second = self.create(second_body)
        self.assertEqual(200, self.put(second, 0, second_contents[0][1])[0])
        with patch.object(self.f.manager.pool, "submit", return_value=Future()):
            status, response = self.start(second)
        self.assertEqual(200, status, response)
        gap = response["result"]
        self.assertEqual("starting", gap["inputState"]["phase"])
        self.assertFalse(gap["notStartedConfirmed"])
        start_operation = gap["inputState"]["startOperationId"]
        self.assertTrue(start_operation)
        failures = {}
        original_atomic = pocket_companion.atomic_json
        for stage in ("first_sentinel", "final_confirmed"):
            request, contents = self.body([(stage + ".bin", b"ACTUAL VERIFIED BYTES RETAINED")])
            candidate = self.create(request)
            self.assertEqual(200, self.put(candidate, 0, contents[0][1])[0])
            live = self.f.manager.jobs[candidate["id"]]
            previous_revision = live["inputState"]["revision"]
            def fail(path, value):
                if value.get("id") == candidate["id"] and (stage == "first_sentinel" and value.get("_inputPersistenceUnconfirmed") or stage == "final_confirmed" and not value.get("_inputPersistenceUnconfirmed")):
                    raise OSError("Controlled " + stage + " atomic-json failure")
                return original_atomic(path, value)
            with patch("pocket_companion.atomic_json", side_effect=fail):
                with self.f.manager.lock:
                    live["message"] = "Original job event before controlled persistence failure"
                    with self.assertRaises(OSError):
                        self.f.manager._save(live)
                status, observed = self.f.request("/v1/jobs/" + candidate["id"])
                self.assertEqual(200, status)
                self.assertEqual("unknown", observed["result"]["status"])
                self.assertNotIn("inputState", observed["result"])
                self.assertNotIn("_inputPersistenceUnconfirmed", observed["result"])
                self.assertEqual(409, self.start(candidate)[0])
            disk = json.loads((self.f.manager.state / ("job-" + candidate["id"] + ".json")).read_text())
            self.assertEqual(stage == "final_confirmed", bool(disk.get("_inputPersistenceUnconfirmed")))
            self.assertEqual(previous_revision + (1 if stage == "final_confirmed" else 0), disk["inputState"]["revision"])
            slot = live["inputState"]["files"][0]
            self.assertEqual("verified", slot["state"])
            self.assertEqual(slot["sha256"], hashlib.sha256((self.f.workspace / slot["path"]).read_bytes()).hexdigest())
            failures[stage] = (candidate["id"], disk["inputState"]["revision"])
        old = self.f.manager
        versions = {jobid: old.get(jobid)["inputState"]["revision"] for jobid in (first["id"], second["id"])}
        # Controlled crash checkpoint: no real worker exists; do not fabricate clean cancellation before reopening.
        with patch.object(old, "cancel", side_effect=lambda jobid: old.get(jobid)):
            old.close()
        config = json.loads(json.dumps(self.f.config))
        config["environments"][0].update(workers=["codex_app_server"], writeAllowed=True)
        reopened = JobManager(config, TOKEN)
        self.f.manager = self.f.server.manager = reopened
        for jobid, revision in versions.items():
            self.assertGreater(reopened.get(jobid)["inputState"]["revision"], revision)
            self.assertEqual("interrupted", reopened.get(jobid)["status"])
            self.assertEqual("interrupted", reopened.get(jobid)["inputState"]["phase"])
        for stage, (jobid, revision) in failures.items():
            observed = reopened.get(jobid)
            self.assertGreater(observed["inputState"]["revision"], revision)
            self.assertEqual("unknown" if stage == "final_confirmed" else "interrupted", observed["inputState"]["phase"])
            self.assertEqual("verified", observed["inputState"]["files"][0]["state"])
            self.assertNotIn("_inputPersistenceUnconfirmed", observed)
            self.assertEqual(409, self.start(observed)[0])
        # Even a subsequent ordinary save cannot wash an unresolved START back to ready/running.
        live = reopened.jobs[second["id"]]
        def fail_final(path, value):
            if value.get("id") == second["id"] and not value.get("_inputPersistenceUnconfirmed"):
                raise OSError("Controlled later final confirmation failure")
            return original_atomic(path, value)
        with patch("pocket_companion.atomic_json", side_effect=fail_final), reopened.lock:
            with self.assertRaises(OSError):
                reopened._save(live)
        live.update(status="queued")
        live["inputState"]["phase"] = "ready"
        with reopened.lock:
            reopened._save(live)
        uncertain = reopened.get(second["id"])
        self.assertEqual("unknown", uncertain["status"])
        self.assertEqual("unknown", uncertain["inputState"]["phase"])
        self.assertEqual(start_operation, uncertain["inputState"]["startOperationId"])
        self.assertFalse(uncertain["notStartedConfirmed"])
        self.assertFalse(uncertain["dispatchFenceFinal"])
        self.assertEqual("unknown", reopened.cancel(second["id"])["status"])
        cancelled = reopened.cancel(first["id"])
        self.assertEqual("cancelled", cancelled["status"])
        self.assertTrue(cancelled["notStartedConfirmed"])
        self.assertGreater(cancelled["inputState"]["revision"], versions[first["id"]])
        self.assertFalse((self.f.workspace / "codex-turn-count").exists())
        self.assertEqual(4, len(reopened.jobs))

    def test_existing_no_attachment_text_job_still_starts_through_the_original_path(self):
        status, response = self.f.request("/v1/jobs", {"operationId": str(uuid.uuid4()), "environmentId": "computer", "worker": "codex_app_server", "readOnly": True, "prompt": "Original no-attachment task"})
        self.assertEqual(200, status, response)
        result = self.f.finish(response["result"]["id"])
        self.assertEqual("completed", result["status"])
        self.assertNotIn("inputState", result)
        self.assertEqual("1", (self.f.workspace / "codex-turn-count").read_text())


if __name__ == "__main__":
    unittest.main()
