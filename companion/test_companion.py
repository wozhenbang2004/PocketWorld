import base64
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import sys

from pocket_companion import CompanionServer, JobManager, MAX_OUTPUT, RequestError


TOKEN = "fixture-only-companion-token-not-a-real-credential-123456789"


class CompanionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-companion-test-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.config = {"stateDir": str(self.root / "state"), "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace),
                       "workers": ["command"], "commands": {"python": sys.executable}}]}
        self.manager = JobManager(self.config, TOKEN)
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .02}, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.manager.close()
        self.thread.join(timeout=1)
        self.temp.cleanup()

    def test_environment_review_has_opaque_instance_binding_and_actual_workspace(self):
        selected = self.manager.list_environments()["environments"][0]
        self.assertRegex(selected["bindingHash"], r"^[a-f0-9]{64}$")
        self.assertEqual(str(self.workspace.resolve()), selected["workspace"])
        self.assertEqual(selected["bindingHash"], self.manager.environment_binding("computer"))
        self.assertNotIn(TOKEN, json.dumps(selected))

    def test_changed_environment_refuses_old_first_create_before_process_or_ledger(self):
        binding = self.manager.environment_binding("computer")
        self.manager.environments["computer"]["writeAllowed"] = True
        body = {"operationId": "bound-first-request", "environmentId": "computer", "environmentBinding": binding,
                "worker": "command", "argv": ["python", "-c", "open('should-not-exist','w').write('bad')"]}
        status, result = self.request("/v1/jobs", body)
        self.assertEqual(409, status, result)
        self.assertFalse((self.workspace / "should-not-exist").exists())
        self.assertEqual({}, self.manager.jobs)
        self.assertEqual("not_found", self.manager.operation("bound-first-request")["status"])

    def test_bound_first_create_repeated_request_returns_original_job(self):
        binding = self.manager.environment_binding("computer")
        body = {"operationId": "bound-one-job", "environmentId": "computer", "environmentBinding": binding,
                "worker": "command", "argv": ["python", "-c", "print('bound')"]}
        first_status, first = self.request("/v1/jobs", body)
        second_status, second = self.request("/v1/jobs", body)
        self.assertEqual(200, first_status, first)
        self.assertEqual(200, second_status, second)
        self.assertEqual(first["result"]["id"], second["result"]["id"])
        self.assertEqual(1, len(self.manager.jobs))
        self.assertEqual("completed", self.finish(first["result"]["id"])["status"])
        self.manager.environments["computer"]["writeAllowed"] = True
        self.assertEqual(first["result"]["id"], self.manager.submit(body)["id"])
        self.assertEqual(1, len(self.manager.jobs))

    def test_restart_invalidates_unsubmitted_review_and_worker_probe_does_not_launch(self):
        binding = self.manager.environment_binding("computer")
        self.manager.instance_id = "a-new-companion-instance"
        status, result = self.request("/v1/workers/probe", {"environmentId": "computer", "environmentBinding": binding, "worker": "codex_app_server"})
        self.assertEqual(409, status, result)
        self.assertEqual({}, self.manager.processes)
        self.assertEqual({}, self.manager.jobs)

    def request(self, path, body=None, token=TOKEN):
        value = None if body is None else json.dumps(body).encode()
        req = Request(self.base + path, data=value, headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with urlopen(req, timeout=4) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def submit(self, script, operation="fixture-operation", **extra):
        body = {"operationId": operation, "environmentId": "computer", "worker": "command", "argv": ["python", "-c", script]}
        body.update(extra)
        status, result = self.request("/v1/jobs", body)
        self.assertEqual(200, status, result)
        return result["result"]

    def finish(self, jobid, limit=5):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            _, response = self.request("/v1/jobs/" + jobid)
            job = response["result"]
            if job["status"] in {"completed", "failed", "cancelled", "unknown", "interrupted", "blocked"}:
                return job
            time.sleep(.03)
        self.fail("Job did not reach a terminal state")

    def codex_active(self, mode='steer-wait'):
        from test_codex_driver import FIXTURE
        fixture=self.root/'steering-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server']
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),mode],str(self.workspace))):
            status,result=self.request('/v1/jobs',{'environmentId':'computer','worker':'codex_app_server','prompt':'Inspect','readOnly':True,'operationId':'original-job-operation'})
        self.assertEqual(200,status,result)
        job=result['result'];deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            job=self.manager.get(job['id'])
            if job['controls']['inputSteering']: return job
            time.sleep(.01)
        self.fail('No verified active steering capability')

    def message_receipt(self, jobid, message_id='owner-message', limit=7):
        deadline=time.monotonic()+limit
        while time.monotonic()<deadline:
            status,result=self.request('/v1/jobs/'+jobid+'/messages/'+message_id)
            self.assertEqual(200,status,result)
            receipt=result['result']
            if receipt['status'] not in {'not_found','queued','sending'}: return receipt
            time.sleep(.02)
        self.fail('No terminal message acknowledgement')

    def test_steer_http_exact_turn_is_deduplicated_and_ack_is_not_job_completion(self):
        job=self.codex_active()
        body={'messageId':'owner-message','expectedTurnId':job['turnId'],'text':'Focus on actual failing tests'}
        self.assertEqual(200,self.request('/v1/jobs/'+job['id']+'/steer',body)[0])
        receipt=self.message_receipt(job['id'])
        self.assertEqual('accepted',receipt['status'])
        self.assertEqual(job['turnId'],receipt['turnId'])
        self.assertEqual('owner-message',receipt['operationId'])
        self.assertEqual('original-job-operation',receipt['jobOperationId'])
        self.assertEqual('running',self.manager.get(job['id'])['status'])
        self.assertEqual(receipt,self.request('/v1/jobs/'+job['id']+'/steer',body)[1]['result'])
        self.assertEqual('1',(self.workspace/'steer-count').read_text())
        body['text']='Different instructions'
        self.assertEqual(409,self.request('/v1/jobs/'+job['id']+'/steer',body)[0])
        self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM steer_messages').fetchone()[0])
        self.manager.cancel(job['id']);self.finish(job['id'])

    def test_steer_http_refuses_stale_turn_before_reserving_or_sending_message(self):
        job=self.codex_active()
        body={'messageId':'stale-message','expectedTurnId':'old-turn','text':'Must not send'}
        self.assertEqual(409,self.request('/v1/jobs/'+job['id']+'/steer',body)[0])
        self.assertFalse((self.workspace/'steer-count').exists())
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM steer_messages').fetchone()[0])
        self.manager.cancel(job['id']);self.finish(job['id'])

    def test_lost_steer_ack_stays_unknown_and_is_not_resent_after_restart(self):
        job=self.codex_active('steer-disconnect')
        body={'messageId':'owner-message','expectedTurnId':job['turnId'],'text':'Inspect once'}
        self.request('/v1/jobs/'+job['id']+'/steer',body)
        self.assertEqual('unknown',self.message_receipt(job['id'])['status'])
        self.finish(job['id'])
        self.assertEqual('unknown',self.request('/v1/jobs/'+job['id']+'/steer',body)[1]['result']['status'])
        self.manager.close();self.manager=JobManager(self.config,TOKEN);self.server.manager=self.manager
        self.assertEqual('unknown',self.request('/v1/jobs/'+job['id']+'/steer',body)[1]['result']['status'])
        self.assertEqual('1',(self.workspace/'steer-count').read_text())

    def test_steer_timeout_does_not_replay_or_claim_the_original_turn_was_stopped(self):
        job=self.codex_active('steer-no-ack')
        body={'messageId':'owner-message','expectedTurnId':job['turnId'],'text':'Inspect once'}
        self.request('/v1/jobs/'+job['id']+'/steer',body)
        self.assertEqual('unknown',self.message_receipt(job['id'])['status'])
        current=self.manager.get(job['id'])
        self.assertEqual('running',current['status'])
        self.assertFalse(current['controls']['inputSteering'])
        self.assertTrue(current['steeringUnknown'])
        self.assertEqual('unknown',self.request('/v1/jobs/'+job['id']+'/steer',body)[1]['result']['status'])
        self.assertEqual('1',(self.workspace/'steer-count').read_text())
        self.manager.cancel(job['id']);self.assertEqual('cancelled',self.finish(job['id'])['status'])

    def test_restart_turns_queued_steer_receipts_unknown_without_dispatch(self):
        value={'jobId':'missing-original-job','messageId':'pending-message','expectedTurnId':'prior-turn','status':'queued','dispatched':False}
        self.manager.ledger.execute('INSERT INTO steer_messages VALUES (?,?,?,?,?)',('pending-message','missing-original-job','fixture-fingerprint',json.dumps(value),1));self.manager.ledger.commit()
        self.manager.close();self.manager=JobManager(self.config,TOKEN);self.server.manager=self.manager
        status,result=self.request('/v1/jobs/missing-original-job/messages/pending-message')
        self.assertEqual(200,status)
        self.assertEqual('unknown',result['result']['status'])
        self.assertEqual({},self.manager.jobs)

    def test_steer_rejects_mismatched_job_operation_and_thread_without_reserving(self):
        job=self.codex_active()
        body={'messageId':'identity-message','expectedTurnId':job['turnId'],'text':'Private owner input','threadId':job['threadId'],'jobOperationId':job['operationId']}
        for key in ['threadId','jobOperationId']:
            wrong=dict(body);wrong[key]='another-identity'
            self.assertEqual(409,self.request('/v1/jobs/'+job['id']+'/steer',wrong)[0])
        self.assertFalse((self.workspace/'steer-count').exists())
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM steer_messages').fetchone()[0])
        self.request('/v1/jobs/'+job['id']+'/steer',body)
        self.assertEqual('accepted',self.message_receipt(job['id'],'identity-message')['status'])
        stored=self.manager.ledger.execute('SELECT fingerprint,receipt FROM steer_messages').fetchone()
        self.assertNotIn('Private owner input',str(stored))
        self.manager.cancel(job['id']);self.finish(job['id'])

    def test_late_ack_updates_same_durable_message_and_reenables_only_original_turn(self):
        job=self.codex_active('steer-late-ack')
        body={'messageId':'late-message','expectedTurnId':job['turnId'],'text':'One message'}
        self.request('/v1/jobs/'+job['id']+'/steer',body)
        driver=self.manager.drivers[job['id']];deadline=time.monotonic()+2
        while not driver.steer_replies and time.monotonic()<deadline: time.sleep(.005)
        self.assertTrue(driver.steer_replies)
        next(iter(driver.steer_replies.values()))['deadline']=time.monotonic()-1
        self.assertEqual('unknown',self.message_receipt(job['id'],'late-message')['status'])
        while time.monotonic()<deadline:
            receipt=self.manager.steer_message(job['id'],'late-message')
            if receipt['status']=='accepted':break
            time.sleep(.01)
        self.assertEqual('accepted',receipt['status'])
        self.assertEqual('running',self.manager.get(job['id'])['status'])
        self.assertTrue(self.manager.get(job['id'])['controls']['inputSteering'])
        self.assertEqual('1',(self.workspace/'steer-count').read_text())
        self.assertEqual(1,len(self.manager.jobs))
        self.manager.cancel(job['id']);self.finish(job['id'])

    def test_http_authentication_precedes_every_operation(self):
        status, result = self.request("/v1/jobs", {"not": "authorized"}, token="wrong")
        self.assertEqual(401, status)
        self.assertFalse(result["ok"])
        self.assertEqual({}, self.manager.jobs)
        status, result = self.request("/v1/status")
        self.assertEqual(200, status)
        self.assertFalse(result["result"]["directPhoneSsh"])

    def test_real_job_output_artifact_and_exit_status(self):
        job = self.submit("from pathlib import Path; Path('result.txt').write_text('中文结果',encoding='utf-8'); print('done')")
        result = self.finish(job["id"])
        self.assertEqual("completed", result["status"])
        self.assertEqual(0, result["exitCode"])
        self.assertIn("done", result["output"])
        self.assertFalse(result["remoteEffectsVerified"])
        status, artifact = self.request("/v1/jobs/" + job["id"] + "/artifact?path=result.txt")
        self.assertEqual(200, status)
        self.assertEqual("中文结果", base64.b64decode(artifact["result"]["base64"]).decode())

    def test_duplicate_operation_does_not_repeat_execution(self):
        script = "from pathlib import Path; p=Path('count'); p.write_text(p.read_text()+'1' if p.exists() else '1')"
        first = self.submit(script)
        duplicate = self.submit(script)
        self.assertEqual(first["id"], duplicate["id"])
        self.finish(first["id"])
        self.assertEqual("1", (self.workspace / "count").read_text())
        status, _ = self.request("/v1/jobs", {"operationId": "fixture-operation", "environmentId": "computer", "worker": "command", "argv": ["python", "-c", "print('changed')"]})
        self.assertEqual(409, status)

    def test_operation_reconciliation_reads_receipt_without_replaying(self):
        job = self.submit("print('receipt')", operation="reconcile-existing")
        self.finish(job["id"])
        status, found = self.request("/v1/operations/reconcile-existing")
        self.assertEqual(200, status)
        self.assertEqual(job["id"], found["result"]["id"])
        status, absent = self.request("/v1/operations/never-submitted")
        self.assertEqual(200, status)
        self.assertEqual("not_found", absent["result"]["status"])
        self.assertEqual(1, len(self.manager.jobs))

    def test_pruning_receipts_never_releases_operation_id_even_after_restart(self):
        script = "from pathlib import Path; p=Path('once'); p.write_text(p.read_text()+'1' if p.exists() else '1')"
        job = self.submit(script, operation="permanent-operation")
        self.finish(job["id"])
        with self.manager.lock:
            for i in range(500):
                self.manager.jobs["fixture-" + str(i)] = {"id": "fixture-" + str(i), "status": "completed", "createdAt": self.manager.now() + i}
            self.manager._prune()
        self.assertNotIn(job["id"], self.manager.jobs)
        replay = self.submit(script, operation="permanent-operation")
        self.assertEqual(job["id"], replay["id"])
        self.assertEqual("unknown", replay["status"])
        self.assertTrue(replay["archived"])
        recovered = JobManager(self.config, TOKEN)
        try:
            replay = recovered.submit({"operationId": "permanent-operation", "environmentId": "computer", "worker": "command", "argv": ["python", "-c", script]})
            self.assertEqual(job["id"], replay["id"])
            self.assertEqual("unknown", replay["status"])
            self.assertEqual("1", (self.workspace / "once").read_text())
        finally:
            recovered.close()

    def test_aliases_of_one_workspace_cannot_execute_at_the_same_time(self):
        self.manager.environments["alias"] = {**self.manager.environments["computer"], "id": "alias"}
        script = "import os,time; fd=os.open('busy',os.O_CREAT|os.O_EXCL|os.O_WRONLY); time.sleep(.2); os.close(fd); os.unlink('busy'); print('exclusive')"
        first = self.submit(script, operation="exclusive-first")
        second = self.submit(script, operation="exclusive-second", environmentId="alias")
        self.assertEqual("completed", self.finish(first["id"])["status"])
        self.assertEqual("completed", self.finish(second["id"])["status"])

    def test_cancelled_workspace_waiter_never_dispatches(self):
        first = self.submit("import time; time.sleep(.4)", operation="busy-first")
        deadline = time.monotonic() + 2
        while self.manager.get(first["id"])["status"] == "queued" and time.monotonic() < deadline:
            time.sleep(.01)
        waiter = self.submit("from pathlib import Path; Path('must-not-exist').write_text('dispatched')", operation="cancel-waiter")
        self.assertEqual(200, self.request("/v1/jobs/" + waiter["id"] + "/cancel", {})[0])
        self.assertEqual("cancelled", self.finish(waiter["id"])["status"])
        self.finish(first["id"])
        self.assertFalse((self.workspace / "must-not-exist").exists())

    def test_artifact_rejects_environment_rebound_to_another_workspace(self):
        job = self.submit("from pathlib import Path; Path('result').write_text('original')", operation="bound-artifact")
        self.finish(job["id"])
        replacement = self.root / "replacement"
        replacement.mkdir()
        (replacement / "result").write_text("different workspace")
        self.manager.environments["computer"]["root"] = str(replacement)
        status, _ = self.request("/v1/jobs/" + job["id"] + "/artifact?path=result")
        self.assertEqual(409, status)

    def test_real_workspace_list_read_write_diff_and_stale_version(self):
        self.manager.environments["computer"]["writeAllowed"] = True
        (self.workspace / "Main.java").write_text("class Main {}\n", encoding="utf-8")
        status, listing = self.request("/v1/workspaces/computer/files")
        self.assertEqual(200, status)
        self.assertEqual("Main.java", listing["result"]["files"][0]["path"])
        _, before = self.request("/v1/workspaces/computer/file?path=Main.java")
        old = before["result"]["version"]
        body = {"path": "Main.java", "content": "class Main { int n; }\n", "expectedVersion": old, "operationId": "write-real-file"}
        status, changed = self.request("/v1/workspaces/computer/file", body)
        self.assertEqual(200, status)
        self.assertEqual("completed", changed["result"]["status"])
        self.assertTrue(changed["result"]["verified"])
        _, difference = self.request("/v1/workspaces/computer/diff?path=Main.java&fromVersion=" + old)
        self.assertIn("-class Main {}", difference["result"]["diff"])
        self.assertIn("+class Main { int n; }", difference["result"]["diff"])
        body["operationId"] = "stale-file-operation"
        self.assertEqual(409, self.request("/v1/workspaces/computer/file", body)[0])
        self.assertEqual("class Main { int n; }\n", (self.workspace / "Main.java").read_text())

    def test_file_transfer_handles_actual_128k_content_within_its_separate_bound(self):
        self.manager.environments["computer"]["writeAllowed"] = True
        content = "0123456789" * 13107
        body = {"path": "bounded-large.txt", "content": content, "expectedVersion": "absent", "operationId": "bounded-large-file"}
        status, result = self.request("/v1/workspaces/computer/file", body)
        self.assertEqual(200, status)
        self.assertEqual("completed", result["result"]["status"])
        self.assertEqual(content, (self.workspace / "bounded-large.txt").read_text())

    def test_file_write_operation_reconciliation_never_repeats_a_write(self):
        self.manager.environments["computer"]["writeAllowed"] = True
        body = {"path": "new.txt", "content": "first", "expectedVersion": "absent", "operationId": "write-once"}
        status, first = self.request("/v1/workspaces/computer/file", body)
        self.assertEqual(200, status)
        (self.workspace / "new.txt").write_text("changed outside companion")
        status, duplicate = self.request("/v1/workspaces/computer/file", body)
        self.assertEqual(200, status)
        self.assertEqual(first["result"], duplicate["result"])
        self.assertEqual("changed outside companion", (self.workspace / "new.txt").read_text())
        _, receipt = self.request("/v1/file-operations/write-once")
        self.assertEqual(first["result"], receipt["result"])
        body["content"] = "changed request"
        self.assertEqual(409, self.request("/v1/workspaces/computer/file", body)[0])

    def test_workspace_files_reject_symlinks_traversal_and_disabled_writes(self):
        outside = self.root / "private"
        outside.write_text("secret")
        (self.workspace / "escape").symlink_to(outside)
        for path in ["../private", "escape"]:
            self.assertEqual(403, self.request("/v1/workspaces/computer/file?path=" + path)[0])
        body = {"path": "disabled.txt", "content": "no", "expectedVersion": "absent", "operationId": "disabled-write"}
        self.assertEqual(403, self.request("/v1/workspaces/computer/file", body)[0])
        self.assertFalse((self.workspace / "disabled.txt").exists())

    def test_workspace_file_write_cannot_race_active_workspace_job(self):
        self.manager.environments["computer"]["writeAllowed"] = True
        job = self.submit("import time; time.sleep(.3)", operation="workspace-busy")
        deadline = time.monotonic() + 2
        while self.manager.get(job["id"])["status"] == "queued" and time.monotonic() < deadline:
            time.sleep(.01)
        body = {"path": "blocked.txt", "content": "no", "expectedVersion": "absent", "operationId": "blocked-write"}
        self.assertEqual(409, self.request("/v1/workspaces/computer/file", body)[0])
        self.finish(job["id"])
        self.assertFalse((self.workspace / "blocked.txt").exists())

    def original_cancel_active(self, operation="original-cancel-operation"):
        job = self.submit("from pathlib import Path; import time; Path('original-cancel-launches').open('a').write('1'); print('started',flush=True); time.sleep(20)", operation=operation)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            with self.manager.lock:
                process = self.manager.processes.get(job["id"])
                if process is not None and process.poll() is None and (self.workspace / "original-cancel-launches").exists():
                    return job, process
            time.sleep(.01)
        self.fail("Original fixture process did not start")

    def test_original_cancel_capability_and_empty_legacy_cancel_remain_compatible(self):
        status, response = self.request("/v1/status")
        self.assertEqual(200, status)
        self.assertEqual(1, response["result"]["protocolVersion"])
        capability = response["result"]["originalJobCancellation"]
        self.assertEqual("pocketworld.original-job-cancel.v1", capability["protocol"])
        self.assertIs(True, capability["supported"])
        self.assertEqual("/v1/jobs/{jobId}/cancel-original", capability["endpoint"])
        self.assertEqual(["jobOperationId"], capability["requiredFields"])
        self.assertEqual("locked_job_operation_before_mutation", capability["identityCheck"])
        self.assertEqual("job_manager_cli_jobs_only", capability["scope"])
        job, process = self.original_cancel_active()
        self.assertEqual(200, self.request("/v1/jobs/" + job["id"] + "/cancel", {})[0])
        self.assertEqual("cancelled", self.finish(job["id"])["status"])
        self.assertIsNotNone(process.poll())

    def test_legacy_cancel_never_ignores_identity_preconditions(self):
        job, process = self.original_cancel_active()
        status, response = self.request("/v1/jobs/" + job["id"] + "/cancel", {"jobOperationId": "another-original-operation"})
        self.assertEqual(400, status, response)
        with self.manager.lock:
            self.assertNotIn(job["id"], self.manager.stopping)
            self.assertEqual("original-cancel-operation", self.manager.jobs[job["id"]]["operationId"])
        self.assertIsNone(process.poll())

    def test_original_cancel_rejects_missing_wrong_and_extra_identity_before_effect(self):
        job, process = self.original_cancel_active()
        path = "/v1/jobs/" + job["id"] + "/cancel-original"
        for body, expected in [({}, 400), ({"jobOperationId": None}, 400),
                               ({"jobOperationId": "wrong-original-operation"}, 409),
                               ({"jobOperationId": job["operationId"], "replacement": True}, 400)]:
            with self.subTest(body=body):
                status, response = self.request(path, body)
                self.assertEqual(expected, status, response)
                self.assertIsNone(process.poll())
                with self.manager.lock:
                    self.assertNotIn(job["id"], self.manager.stopping)
                    self.assertEqual("running", self.manager.jobs[job["id"]]["status"])
        self.assertEqual("1", (self.workspace / "original-cancel-launches").read_text())
        self.assertEqual(1, len(self.manager.jobs))

    def test_original_cancel_matches_exact_process_and_repetition_never_relaunches(self):
        job, process = self.original_cancel_active()
        path = "/v1/jobs/" + job["id"] + "/cancel-original"
        status, response = self.request(path, {"jobOperationId": job["operationId"]})
        self.assertEqual(200, status, response)
        proof = response["result"]["originalCancellation"]
        self.assertEqual("pocketworld.original-job-cancel.v1", proof["protocol"])
        self.assertEqual(job["id"], proof["jobId"])
        self.assertEqual(job["operationId"], proof["jobOperationId"])
        self.assertIs(True, proof["identityMatched"])
        self.assertIs(False, proof["effectsReversed"])
        self.assertEqual("cancelled", self.finish(job["id"])["status"])
        self.assertIsNotNone(process.poll())
        with patch.object(self.manager, "_launch", side_effect=AssertionError("Cancellation must never submit or launch")):
            self.assertEqual(200, self.request(path, {"jobOperationId": job["operationId"]})[0])
        self.assertEqual("1", (self.workspace / "original-cancel-launches").read_text())
        self.assertEqual(1, len(self.manager.jobs))
        self.assertEqual(1, self.manager.ledger.execute("SELECT count(*) FROM operations").fetchone()[0])

    def test_original_cancel_rechecks_identity_after_waiting_for_manager_lock(self):
        job, process = self.original_cancel_active()
        attempted, finished = threading.Event(), threading.Event()
        failures = []
        def stop():
            attempted.set()
            try:
                self.manager.cancel_original(job["id"], {"jobOperationId": job["operationId"]})
            except Exception as error:
                failures.append(error)
            finally:
                finished.set()
        worker = threading.Thread(target=stop, daemon=True)
        try:
            with self.manager.lock:
                worker.start()
                self.assertTrue(attempted.wait(2))
                self.assertFalse(finished.wait(.05))
                self.manager.jobs[job["id"]]["operationId"] = "replacement-operation-under-lock"
            self.assertTrue(finished.wait(3))
            self.assertEqual(1, len(failures))
            self.assertIsInstance(failures[0], RequestError)
            self.assertEqual(409, failures[0].status)
            self.assertIsNone(process.poll())
            with self.manager.lock:
                self.assertNotIn(job["id"], self.manager.stopping)
                self.assertEqual("running", self.manager.jobs[job["id"]]["status"])
        finally:
            with self.manager.lock:
                self.manager.jobs[job["id"]]["operationId"] = job["operationId"]
            worker.join(timeout=3)
            self.assertFalse(worker.is_alive())

    def test_original_cancel_identity_and_stop_mutation_share_one_lock(self):
        job, process = self.original_cancel_active()
        marked, release, observing, observed = (threading.Event() for _ in range(4))
        snapshot, errors = [], []
        save = self.manager._save
        def held_save(current):
            if current["id"] == job["id"] and current["status"] == "stopping":
                marked.set()
                if not release.wait(3):
                    raise AssertionError("Controlled stop publication was not released")
            return save(current)
        def stop():
            try:
                self.manager.cancel_original(job["id"], {"jobOperationId": job["operationId"]})
            except Exception as error:
                errors.append(error)
        def observe():
            observing.set()
            with self.manager.lock:
                current = self.manager.jobs[job["id"]]
                snapshot.append((current["operationId"], current["status"]))
            observed.set()
        worker, reader = threading.Thread(target=stop, daemon=True), threading.Thread(target=observe, daemon=True)
        try:
            with patch.object(self.manager, "_save", side_effect=held_save):
                worker.start()
                self.assertTrue(marked.wait(3))
                reader.start()
                self.assertTrue(observing.wait(2))
                self.assertFalse(observed.wait(.05))
                release.set()
                worker.join(timeout=4)
                reader.join(timeout=3)
            self.assertFalse(worker.is_alive())
            self.assertFalse(reader.is_alive())
            self.assertEqual([], errors)
            self.assertTrue(observed.is_set())
            self.assertEqual(job["operationId"], snapshot[0][0])
            self.assertIn(snapshot[0][1], {"stopping", "cancelled"})
            self.assertEqual("cancelled", self.finish(job["id"])["status"])
        finally:
            release.set()
            worker.join(timeout=4)
            if reader.ident is not None:
                reader.join(timeout=3)

    def test_original_cancel_does_not_borrow_native_device_or_desktop_authority(self):
        for jobid, special in [("desktop-original", {"protocol": "desktop-v1"}),
                               ("device-original", {"nativeDeviceOperation": {"toolName": "workspace.receive"}}),
                               ("device-incomplete", {"nativeDeviceOperation": {}})]:
            with self.subTest(jobid=jobid):
                with self.manager.lock:
                    self.manager.jobs[jobid] = {"id": jobid, "operationId": "native-operation", "worker": "command", "status": "running", **special}
                try:
                    status, response = self.request("/v1/jobs/" + jobid + "/cancel-original", {"jobOperationId": "native-operation"})
                    self.assertEqual(409, status, response)
                    with self.manager.lock:
                        self.assertNotIn(jobid, self.manager.stopping)
                        self.assertEqual("running", self.manager.jobs[jobid]["status"])
                finally:
                    with self.manager.lock:
                        self.manager.jobs.pop(jobid, None)

    def test_cancel_stops_running_local_process(self):
        job = self.submit("import time; print('started',flush=True); time.sleep(20)")
        deadline = time.monotonic() + 2
        while self.manager.get(job["id"])["status"] == "queued" and time.monotonic() < deadline:
            time.sleep(.01)
        status, _ = self.request("/v1/jobs/" + job["id"] + "/cancel", {})
        self.assertEqual(200, status)
        result = self.finish(job["id"])
        self.assertEqual("cancelled", result["status"])
        self.assertNotIn(job["id"], self.manager.processes)

    def test_timeout_is_a_real_stop_and_not_success(self):
        job = self.submit("import time; time.sleep(20)", timeoutSeconds=1)
        result = self.finish(job["id"])
        self.assertEqual("cancelled", result["status"])
        self.assertTrue(result["timedOut"])

    def test_output_is_bounded_and_token_redaction_crosses_chunks(self):
        token_repr = repr(TOKEN)
        job = self.submit("import sys,time; t=" + token_repr + "; sys.stdout.write(t[:20]);sys.stdout.flush();time.sleep(.05);sys.stdout.write(t[20:]+'中文'+('x'*250000));sys.stdout.flush()")
        result = self.finish(job["id"])
        self.assertEqual("completed", result["status"])
        self.assertNotIn(TOKEN, result["output"])
        self.assertIn("[token hidden]中文", result["output"])
        self.assertLessEqual(len(result["output"]), MAX_OUTPUT)
        self.assertTrue(result["outputTruncated"])

    def test_artifact_cannot_escape_workspace_or_follow_external_symlink(self):
        outside = self.root / "outside.txt"
        outside.write_text("private")
        job = self.submit("print('ready')")
        self.finish(job["id"])
        status, _ = self.request("/v1/jobs/" + job["id"] + "/artifact?path=../outside.txt")
        self.assertEqual(403, status)
        if hasattr(os, "symlink"):
            (self.workspace / "link.txt").symlink_to(outside)
            status, _ = self.request("/v1/jobs/" + job["id"] + "/artifact?path=link.txt")
            self.assertEqual(403, status)

    def test_allowlist_and_invalid_worker_are_rejected_before_dispatch(self):
        base = {"operationId": "rejected", "environmentId": "computer", "worker": "command", "argv": ["not-allowed"]}
        self.assertEqual(403, self.request("/v1/jobs", base)[0])
        base["worker"] = []
        self.assertEqual(400, self.request("/v1/jobs", base)[0])
        self.assertEqual({}, self.manager.jobs)

    def test_restart_marks_unfinished_receipts_interrupted(self):
        jobid = "prior-unfinished-job"
        path = self.root / "restart-state"
        path.mkdir()
        (path / ("job-" + jobid + ".json")).write_text(json.dumps({"id": jobid, "worker": "codex_app_server", "threadId": "prior-thread", "status": "running", "pendingApprovals": [{"requestId": "stale", "canAccept": True}], "createdAt": 1}), encoding="utf-8")
        recovered = JobManager({**self.config, "stateDir": str(path)}, TOKEN)
        try:
            self.assertEqual("interrupted", recovered.get(jobid)["status"])
            self.assertEqual([],recovered.get(jobid)['pendingApprovals'])
            self.assertFalse(recovered.get(jobid)['controls']['approve'])
            self.assertFalse(recovered.get(jobid)['controls']['threadResume'])
        finally:
            recovered.close()

    def test_capability_probe_is_real_stdio_read_only_and_not_a_job(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'probe-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server','claude']
        self.manager.environments['computer']['writeAllowed']=True
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'probe'],str(self.workspace))):
            status,result=self.request('/v1/workers/probe',{'environmentId':'computer','worker':'codex_app_server','model':'fixture-model'})
        self.assertEqual(200,status)
        self.assertTrue(result['result']['available'])
        self.assertTrue(result['result']['protocolVerified'])
        self.assertTrue(result['result']['capabilities']['inputSteering'])
        self.assertNotIn('private-fixture',json.dumps(result))
        self.assertEqual({},self.manager.jobs)
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])
        with patch.object(self.manager, '_launch', side_effect=RequestError('Missing fixture CLI', 409)):
            status,claude=self.request('/v1/workers/probe',{'environmentId':'computer','worker':'claude'})
        self.assertEqual(200,status)
        self.assertFalse(claude['result']['available'])
        self.assertFalse(claude['result']['protocolVerified'])
        self.assertIsNone(claude['result']['accountConfigured'])

    def test_forced_read_only_overrides_writable_environment_before_codex_dispatch(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'readonly-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server']
        self.manager.environments['computer']['writeAllowed']=True
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'cancel'],str(self.workspace))):
            status,result=self.request('/v1/jobs',{'environmentId':'computer','worker':'codex_app_server','prompt':'Inspect', 'model':'fixture-model','readOnly':True,'operationId':'readonly-team-child'})
            self.assertEqual(200,status)
            job=result['result']
            deadline=time.monotonic()+2
            while not self.manager.get(job['id']).get('turnId') and time.monotonic()<deadline: time.sleep(.01)
            current=self.manager.get(job['id'])
            self.assertTrue(current['readOnly'])
            self.assertTrue(current['readOnlyConfirmed'])
            self.assertTrue(current['externalToolsDisabled'])
            self.assertEqual('fixture-model',current['model'])
            self.assertEqual('fixture-turn',current['turnId'])
            self.assertTrue(current['controls']['inputSteering'])
            self.manager.cancel(job['id'])
            self.assertEqual('cancelled',self.finish(job['id'])['status'])

    def test_read_only_team_blocks_live_external_tools_or_wrong_sandbox_before_any_turn(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'blocked-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server']
        for mode in ['external-tools','wrong-sandbox']:
            with self.subTest(mode=mode),patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),mode],str(self.workspace))):
                status,result=self.request('/v1/jobs',{'environmentId':'computer','worker':'codex_app_server','prompt':'Inspect','readOnly':True,'operationId':'blocked-'+mode})
                self.assertEqual(200,status)
                job=self.finish(result['result']['id'])
                self.assertEqual('blocked',job['status'])
                self.assertIsNone(job.get('turnId'))
                self.assertEqual('',job['output'])
                self.assertFalse(job['controls']['cancel'])
                self.assertFalse(job.get('readOnlyConfirmed',False))

    def test_capability_probe_rejects_cli_without_effective_configuration_api(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'old-protocol-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server']
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'probe-old'],str(self.workspace))):
            status,result=self.request('/v1/workers/probe',{'environmentId':'computer','worker':'codex_app_server'})
        self.assertEqual(200,status)
        self.assertFalse(result['result']['available'])
        self.assertFalse(result['result']['protocolVerified'])
        self.assertEqual({},self.manager.jobs)

    def test_ssh_launch_quotes_arguments_and_keeps_host_key_checks(self):
        known = self.root / "known_hosts"
        known.write_text("")
        env = {"id": "server", "kind": "ssh", "host": "server.example", "user": "developer", "root": "/srv/project with spaces",
               "knownHosts": str(known), "workers": ["command"], "commands": {"printf": "printf"}}
        manager = JobManager({"stateDir": str(self.root / "ssh-state"), "environments": [env]}, TOKEN)
        try:
            argv, cwd = manager._launch(manager.environments["server"], ["printf", "'; touch /tmp/never-executed"])
            self.assertIsNone(cwd)
            self.assertIn("StrictHostKeyChecking=yes", argv)
            self.assertIn("BatchMode=yes", argv)
            self.assertIn("ForwardAgent=no", argv)
            self.assertIn("'/srv/project with spaces'", argv[-1])
            self.assertNotIn("; touch /tmp/never-executed &&", argv[-1])
        finally:
            manager.close()

    def test_kimi_auto_approval_requires_explicit_computer_configuration(self):
        config = {**self.config, "environments": [{**self.config["environments"][0], "workers": ["kimi"]}]}
        with self.assertRaises(ValueError):
            JobManager(config, TOKEN)

    def test_claude_read_mode_restricts_available_tools_and_customizations(self):
        env = {**self.config["environments"][0], "workers": ["claude"], "writeAllowed": False}
        argv = self.manager._worker_argv({"worker": "claude", "prompt": "Review this project"}, env)
        self.assertIn("--safe-mode", argv)
        self.assertEqual("Read,Glob,Grep,AskUserQuestion", argv[argv.index("--tools") + 1])
        self.assertEqual("Read,Glob,Grep", argv[argv.index("--allowedTools") + 1])
        self.assertEqual(["Edit", "Write", "Bash", "AskUserQuestion"],
                         json.loads(argv[argv.index("--settings") + 1])["permissions"]["ask"])
        self.assertEqual("mcp__*", argv[argv.index("--disallowedTools") + 1])
        self.assertNotIn("Bash", argv[argv.index("--tools") + 1])

    def claude_fixture(self, mode='success'):
        from test_claude_driver import FIXTURE
        fixture=self.root/'claude-fixture.py'
        fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['claude']
        def launch(env, argv):
            self.assertEqual('claude',argv[0])
            return [sys.executable,str(fixture),mode,*argv[1:]],env['root']
        return patch.object(self.manager,'_launch',side_effect=launch)

    def submit_claude(self, operation='claude-operation', **extra):
        body={'environmentId':'computer','worker':'claude','prompt':'Inspect this project','operationId':operation}
        body.update(extra)
        status,response=self.request('/v1/jobs',body)
        self.assertEqual(200,status,response)
        return response['result'],body

    def wait_for_approval(self, jobid):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            job=self.manager.get(jobid)
            if job.get('pendingApprovals'): return job['pendingApprovals'][0]
            time.sleep(.01)
        self.fail('No original invocation approval arrived')

    def test_claude_probe_checks_version_help_without_sessions_jobs_or_account_claims(self):
        with self.claude_fixture():
            status,response=self.request('/v1/workers/probe',{'environmentId':'computer','worker':'claude','model':'unverified-model'})
        self.assertEqual(200,status,response)
        result=response['result']
        self.assertTrue(result['available'])
        self.assertTrue(result['cliFlagsVerified'])
        self.assertFalse(result['protocolVerified'])
        self.assertIsNone(result['accountConfigured'])
        self.assertIsNone(result['selectedModelAvailable'])
        self.assertFalse(result['capabilities']['inputSteering'])
        self.assertFalse(result['capabilities']['readOnlySandbox'])
        self.assertEqual({},self.manager.jobs)
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])
        self.assertFalse((self.workspace/'claude-prompt-count').exists())

    def test_old_claude_cli_is_blocked_before_ledger_reservation_or_prompt(self):
        with self.claude_fixture('old-cli'):
            status,_=self.request('/v1/jobs',{'environmentId':'computer','worker':'claude','prompt':'Inspect','operationId':'old-cli-operation'})
        self.assertEqual(409,status)
        self.assertEqual({},self.manager.jobs)
        self.assertFalse((self.workspace/'claude-prompt-count').exists())

    def test_claude_structured_job_deduplicates_lost_submit_response_and_survives_restart(self):
        with self.claude_fixture():
            job,body=self.submit_claude(model='fixture-model')
        result=self.finish(job['id'])
        self.assertEqual('completed',result['status'])
        self.assertEqual('claude_stream_json',result['protocol'])
        self.assertTrue(result['capabilities']['protocolVerified'])
        self.assertTrue(result['controls']['threadResume'])
        self.assertFalse(result['controls']['inputSteering'])
        self.assertEqual(result['sessionId'],result['threadId'])
        with patch.object(self.manager,'_launch',side_effect=AssertionError('A duplicate must not launch')):
            self.assertEqual(job['id'],self.request('/v1/jobs',body)[1]['result']['id'])
        self.manager.close();self.manager=JobManager(self.config,TOKEN);self.server.manager=self.manager
        self.assertEqual(job['id'],self.request('/v1/jobs',body)[1]['result']['id'])
        self.assertEqual('1',(self.workspace/'claude-prompt-count').read_text())

    def test_claude_resume_is_explicit_new_operation_with_same_session_and_new_approval(self):
        self.manager.environments['computer']['writeAllowed']=True
        with self.claude_fixture('approval'):
            job,_=self.submit_claude('claude-first',model='fixture-model')
        pending=self.wait_for_approval(job['id'])
        self.assertEqual(200,self.request('/v1/jobs/'+job['id']+'/approval',{'requestId':pending['requestId'],'decision':'accept'})[0])
        first=self.finish(job['id'])
        self.assertEqual('completed',first['status'])
        with self.claude_fixture('approval'):
            resumed,_=self.submit_claude('claude-followup',threadId=first['sessionId'],resumeJobId=first['id'])
        second_permission=self.wait_for_approval(resumed['id'])
        self.assertEqual(first['sessionId'],second_permission['sessionId'])
        self.assertNotEqual(pending['requestId'],second_permission['requestId'])
        self.assertEqual('fixture-model',self.manager.get(resumed['id'])['model'])
        self.assertEqual(409,self.request('/v1/jobs/'+resumed['id']+'/approval',{'requestId':pending['requestId'],'decision':'accept'})[0])
        self.assertEqual(200,self.request('/v1/jobs/'+resumed['id']+'/approval',{'requestId':second_permission['requestId'],'decision':'decline'})[0])
        self.assertEqual('completed',self.finish(resumed['id'])['status'])
        self.assertEqual('11',(self.workspace/'claude-prompt-count').read_text())
        self.assertFalse(self.manager.get(first['id'])['controls']['threadResume'])
        self.assertTrue(self.manager.get(resumed['id'])['controls']['threadResume'])

    def test_claude_unknown_descendant_cannot_be_bypassed_using_older_settled_receipt(self):
        with self.claude_fixture(): first,_=self.submit_claude('claude-settled')
        first=self.finish(first['id'])
        with self.claude_fixture('disconnect'):
            child,_=self.submit_claude('claude-unknown',threadId=first['sessionId'],resumeJobId=first['id'])
        self.assertEqual('unknown',self.finish(child['id'])['status'])
        body={'environmentId':'computer','worker':'claude','prompt':'Must not replay','operationId':'claude-forbidden-resume',
              'threadId':first['sessionId'],'resumeJobId':first['id']}
        with self.claude_fixture():
            self.assertEqual(409,self.request('/v1/jobs',body)[0])
        self.assertFalse(self.manager.get(first['id'])['controls']['threadResume'])
        self.assertEqual('11',(self.workspace/'claude-prompt-count').read_text())
        self.assertEqual('unknown',self.request('/v1/operations/claude-unknown')[1]['result']['status'])

    def test_claude_resume_rejects_free_session_names_resource_rebind_and_access_escalation(self):
        with self.claude_fixture(): first,_=self.submit_claude('resume-source')
        first=self.finish(first['id'])
        body={'environmentId':'computer','worker':'claude','prompt':'Continue','operationId':'guarded-resume',
              'threadId':first['sessionId'],'resumeJobId':first['id']}
        for changed in [{'threadId':'a-session-name'},{'resumeJobId':'unrelated-job'},{'threadId':'/tmp/session.jsonl'}]:
            self.assertEqual(409,self.request('/v1/jobs',{**body,**changed})[0])
        self.manager.environments['computer']['writeAllowed']=True
        self.assertEqual(409,self.request('/v1/jobs',body)[0])
        self.manager.environments['computer']['writeAllowed']=False
        replacement=self.root/'replacement';replacement.mkdir()
        self.manager.environments['computer']['root']=str(replacement)
        self.assertEqual(409,self.request('/v1/jobs',body)[0])
        self.assertFalse(self.manager.get(first['id'])['controls']['threadResume'])
        self.assertEqual('1',(self.workspace/'claude-prompt-count').read_text())

    def test_claude_cancelled_workspace_waiter_never_dispatches_and_forced_readonly_stays_codex_only(self):
        with self.claude_fixture('wait'): first,_=self.submit_claude('claude-busy')
        deadline=time.monotonic()+3
        while not self.manager.get(first['id']).get('protocolVerified') and time.monotonic()<deadline: time.sleep(.01)
        with self.claude_fixture(): waiter,_=self.submit_claude('claude-waiter')
        self.manager.cancel(waiter['id'])
        self.assertEqual('cancelled',self.finish(waiter['id'])['status'])
        with self.claude_fixture():
            status,_=self.request('/v1/jobs',{'environmentId':'computer','worker':'claude','prompt':'Analysis team','operationId':'claude-team','readOnly':True})
        self.assertEqual(409,status)
        self.assertEqual('1',(self.workspace/'claude-prompt-count').read_text())
        self.manager.cancel(first['id'])
        self.assertEqual('cancelled',self.finish(first['id'])['status'])

    def test_lost_claude_result_retains_original_approval_receipt_and_never_reexecutes(self):
        self.manager.environments['computer']['writeAllowed']=True
        with self.claude_fixture('lost-reply'): job,body=self.submit_claude('claude-lost-result')
        pending=self.wait_for_approval(job['id'])
        self.assertEqual(200,self.request('/v1/jobs/'+job['id']+'/approval',{'requestId':pending['requestId'],'decision':'accept'})[0])
        result=self.finish(job['id'])
        self.assertEqual('unknown',result['status'])
        self.assertEqual([],result['pendingApprovals'])
        self.assertEqual('sent',result['approvalReceipts'][0]['status'])
        self.assertFalse(result['controls']['threadResume'])
        self.assertEqual(409,self.request('/v1/jobs/'+job['id']+'/approval',{'requestId':pending['requestId'],'decision':'accept'})[0])
        self.assertEqual('unknown',self.request('/v1/jobs',body)[1]['result']['status'])
        self.assertEqual('fixture effect',(self.workspace/'result.txt').read_text())
        self.assertEqual('1',(self.workspace/'claude-prompt-count').read_text())

    def test_claude_ssh_argv_and_stdio_preserve_resource_binding_without_a_network_login(self):
        from test_claude_driver import FIXTURE
        fixture=self.root/'ssh-claude-fixture.py';fixture.write_text(FIXTURE)
        known=self.root/'fixture_known_hosts';known.write_text('')
        env={'id':'server','kind':'ssh','host':'fixture.example.invalid','user':'developer',
             'root':'/srv/project with spaces','knownHosts':str(known),'workers':['claude'],'writeAllowed':False}
        self.manager.environments.update(self.manager._environments([env]))
        real_launch=self.manager._launch
        captured=[]
        def fixture_transport(environment, argv):
            launch,cwd=real_launch(environment,argv)
            captured.append(launch)
            self.assertIsNone(cwd)
            self.assertEqual('ssh',launch[0])
            self.assertIn('StrictHostKeyChecking=yes',launch)
            self.assertIn('ForwardAgent=no',launch)
            return [sys.executable,str(fixture),'success',*argv[1:]],str(self.workspace)
        body={'environmentId':'server','worker':'claude','prompt':'Inspect','operationId':'ssh-claude'}
        with patch.object(self.manager,'_launch',side_effect=fixture_transport):
            status,response=self.request('/v1/jobs',body)
        self.assertEqual(200,status,response)
        result=self.finish(response['result']['id'])
        self.assertEqual('completed',result['status'])
        self.assertTrue(result['protocolVerified'])
        self.assertEqual(3,len(captured))  # --version, --help, then the structured invocation.
        self.assertIn("cd -- '/srv/project with spaces' && exec claude",captured[-1][-1])
        self.assertIn(result['sessionId'],captured[-1][-1])
        self.manager.environments['server']['host']='other.example.invalid'
        resume={**body,'operationId':'wrong-ssh-origin','threadId':result['sessionId'],'resumeJobId':result['id']}
        self.assertEqual(409,self.request('/v1/jobs',resume)[0])
        self.assertFalse(self.manager.get(result['id'])['controls']['threadResume'])
        self.assertEqual('1',(self.workspace/'claude-prompt-count').read_text())

    def test_restart_marks_inflight_approval_reply_unknown_and_never_reuses_its_grant(self):
        jobid='crashed-claude-invocation'
        raw={'id':jobid,'worker':'claude','status':'running','createdAt':1,
             'pendingApprovals':[{'requestId':'old-review','canAccept':True}],
             'approvalReceipts':[{'requestId':'old-review','decision':'accept','status':'sending'}]}
        (self.manager.state/('job-'+jobid+'.json')).write_text(json.dumps(raw))
        self.manager.close();self.manager=JobManager(self.config,TOKEN);self.server.manager=self.manager
        result=self.manager.get(jobid)
        self.assertEqual('interrupted',result['status'])
        self.assertEqual([],result['pendingApprovals'])
        self.assertEqual('unknown',result['approvalReceipts'][0]['status'])
        self.assertFalse(result['controls']['approve'])
        self.assertEqual(409,self.request('/v1/jobs/'+jobid+'/approval',{'requestId':'old-review','decision':'accept'})[0])
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_write_authorization_configuration_requires_a_boolean(self):
        invalid={**self.config,'environments':[{**self.config['environments'][0],'writeAllowed':'false'}]}
        with self.assertRaisesRegex(ValueError,'explicit boolean'):
            JobManager(invalid,TOKEN)

    def test_codex_resume_keeps_original_readonly_model_resource_and_requires_settled_receipt(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'codex-resume-fixture.py';fixture.write_text(FIXTURE)
        env=self.manager.environments['computer'];env.update(workers=['codex_app_server'],writeAllowed=True)
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'cancel'],str(self.workspace))):
            status,response=self.request('/v1/jobs',{'environmentId':'computer','worker':'codex_app_server','prompt':'Inspect',
                'readOnly':True,'model':'fixture-model','operationId':'codex-resume-source'})
        self.assertEqual(200,status,response)
        source=response['result'];deadline=time.monotonic()+3
        while not self.manager.get(source['id']).get('turnId') and time.monotonic()<deadline: time.sleep(.01)
        self.manager.cancel(source['id']);source=self.finish(source['id'])
        self.assertEqual('cancelled',source['status'])
        body={'environmentId':'computer','worker':'codex_app_server','prompt':'Continue inspection',
              'threadId':source['threadId'],'operationId':'codex-explicit-continuation'}
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'final-without-start-ack'],str(self.workspace))):
            status,response=self.request('/v1/jobs',body)
        self.assertEqual(200,status,response)
        result=self.finish(response['result']['id'])
        self.assertEqual('completed',result['status'])
        self.assertEqual(source['id'],result['resumeJobId'])
        self.assertEqual('fixture-model',result['model'])
        self.assertTrue(result['readOnly'])
        self.assertTrue(result['readOnlyConfirmed'])
        self.assertFalse(self.manager.get(source['id'])['controls']['threadResume'])
        stale={**body,'operationId':'codex-stale-continuation','resumeJobId':source['id']}
        self.assertEqual(409,self.request('/v1/jobs',stale)[0])
        env['root']=str(self.root)
        rebound={**body,'operationId':'codex-rebound-continuation','resumeJobId':result['id']}
        self.assertEqual(409,self.request('/v1/jobs',rebound)[0])
        self.assertEqual('11',(self.workspace/'codex-turn-count').read_text())

    def test_codex_unknown_thread_cannot_be_resumed_with_a_new_operation(self):
        from test_codex_driver import FIXTURE
        fixture=self.root/'codex-unknown-fixture.py';fixture.write_text(FIXTURE)
        self.manager.environments['computer']['workers']=['codex_app_server']
        with patch.object(self.manager,'_launch',return_value=([sys.executable,str(fixture),'disconnect'],str(self.workspace))):
            status,response=self.request('/v1/jobs',{'environmentId':'computer','worker':'codex_app_server',
                'prompt':'Inspect','operationId':'codex-unknown-source'})
        self.assertEqual(200,status,response)
        source=self.finish(response['result']['id'])
        self.assertEqual('unknown',source['status'])
        body={'environmentId':'computer','worker':'codex_app_server','prompt':'Must not replay',
              'threadId':source['threadId'],'operationId':'codex-unknown-replay'}
        with patch.object(self.manager,'_launch',side_effect=AssertionError('Unknown work must not launch')):
            self.assertEqual(409,self.request('/v1/jobs',body)[0])
        self.assertEqual('1',(self.workspace/'codex-turn-count').read_text())

    def test_completed_codex_job_with_unknown_independent_message_cannot_open_a_new_turn(self):
        job=self.codex_active('steer-complete-no-ack')
        body={'messageId':'owner-message','expectedTurnId':job['turnId'],'text':'One exact message'}
        self.assertEqual(200,self.request('/v1/jobs/'+job['id']+'/steer',body)[0])
        self.assertEqual('unknown',self.message_receipt(job['id'])['status'])
        result=self.finish(job['id'])
        self.assertEqual('completed',result['status'])
        self.assertFalse(result['controls']['threadResume'])
        resume={'environmentId':'computer','worker':'codex_app_server','prompt':'Must not bypass message',
                'threadId':result['threadId'],'resumeJobId':result['id'],'operationId':'unreconciled-message-resume'}
        with patch.object(self.manager,'_launch',side_effect=AssertionError('No new turn before message reconciliation')):
            self.assertEqual(409,self.request('/v1/jobs',resume)[0])
        self.assertEqual('1',(self.workspace/'codex-turn-count').read_text())
        self.assertEqual('1',(self.workspace/'steer-count').read_text())
        self.assertEqual('unknown',self.manager.steer_message(job['id'],'owner-message')['status'])


if __name__ == "__main__":
    unittest.main()
