"""Controlled local stdio/Companion fixtures. Not a real Codex, account, device or billing test."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.request import Request, urlopen
import cold_session
from codex_driver import DriverError
from pocket_companion import CompanionServer, JobManager, RequestError

TOKEN = "cold-recovery-fixture-no-real-credential-more-than-32"
PIPE = r'''
import json,os,sys
from pathlib import Path
home=Path(sys.argv[1]);home.mkdir(exist_ok=True)
def emit(v):print(json.dumps(v),flush=True)
for line in sys.stdin:
 v=json.loads(line);method=v.get('method')
 if method:
  with(home/'methods.jsonl').open('a')as output:output.write(json.dumps({'method':method,'params':v.get('params')})+'\n')
 if method=='initialize':emit({'id':v['id'],'result':{'userAgent':'controlled-cold-rpc-fixture'}})
 elif method=='initialized':pass
 elif method=='turn/steer':emit({'id':v['id'],'error':{'code':-32600,'message':'thread not found: '+v['params']['threadId']}})
 elif method=='thread/read':
  state=json.loads((home/'stored.json').read_text())
  if state.get('unsupported'):emit({'id':v['id'],'error':{'code':-32601,'message':'Unsupported actual installed protocol'}})
  else:emit({'id':v['id'],'result':{'thread':{'id':state.get('threadId','fixture-thread'),'cwd':state.get('cwd',os.getcwd()),'status':{'type':state.get('threadStatus','notLoaded')}}}})
 elif method=='thread/turns/list':
  assert v['params']=={'threadId':'fixture-thread','limit':1,'sortDirection':'desc','itemsView':'notLoaded'}
  state=json.loads((home/'stored.json').read_text());emit({'id':v['id'],'result':{'data':[{'id':state['turnId'],'status':state['status']}],'nextCursor':None,'backwardsCursor':None}})
 elif method in {'thread/start','thread/resume'}:
  emit({'id':v['id'],'result':{'thread':{'id':'fixture-thread'}}})
 elif method=='turn/start':
  count=home/'starts';number=int(count.read_text())+1 if count.exists()else 1;count.write_text(str(number));turn='fixture-turn-'+str(number)
  (home/'stored.json').write_text(json.dumps({'threadId':'fixture-thread','turnId':turn,'status':'completed','cwd':os.getcwd()}))
  emit({'id':v['id'],'result':{'turn':{'id':turn}}});emit({'method':'turn/started','params':{'threadId':'fixture-thread','turn':{'id':turn}}})
  emit({'method':'item/agentMessage/delta','params':{'threadId':'fixture-thread','turnId':turn,'delta':'Actual controlled original output '+str(number)}})
  emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':turn,'status':'completed'}}})
 else:emit({'id':v.get('id'),'error':{'code':-32601,'message':'Not a fixture method'}})
'''


@unittest.skipUnless(os.name != "nt" and Path("/proc/sys/kernel/random/boot_id").is_file(), "Requires actual local Linux process identity fixture")
class ColdRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocket-cold-original-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.cli = self.root / "controlled-cli.py"
        self.cli.write_text(PIPE)
        self.protocol = self.root / "protocol"
        self.protocol.mkdir()
        self.config = {"stateDir": str(self.root / "state"), "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace), "workers": ["codex_app_server"], "writeAllowed": True}]}
        self.manager = self.open_manager()
        self.server = None
        self.server_thread = None
        self.job = self.manager.submit({"operationId": "original-operation", "environmentId": "computer", "worker": "codex_app_server", "prompt": "Original controlled work", "timeoutSeconds": 30})
        self.wait(self.job["id"])
        self.original = self.manager.get(self.job["id"])
        self.assertEqual("completed", self.original["status"])
        self.assertEqual("fixture-turn-1", self.original["turnId"])
        self.assertIn("_coldProcess", self.manager.jobs[self.job["id"]])

    def open_manager(self):
        manager = JobManager(self.config, TOKEN)
        original = manager._launch
        manager._launch = lambda env, argv: ([sys.executable, str(self.cli), str(self.protocol)], env["root"]) if argv[0] == "codex" else original(env, argv)
        return manager

    def wait(self, jobid):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            value = self.manager.get(jobid)
            if value["status"] in {"completed", "failed", "cancelled", "unknown", "blocked"} and jobid not in self.manager.processes:
                return value
            time.sleep(.01)
        self.fail("Controlled original process did not settle: " + str(self.manager.get(jobid)))

    def cold_restart(self, state="running"):
        self.manager.close()
        path = self.root / "state" / ("job-" + self.job["id"] + ".json")
        actual = json.loads(path.read_text())
        # Emulate loss of the final supervisor save, not a synthetic proof or model-created job.
        # All original process/request/thread/turn/bytes identities came from the real pipeline.
        actual["status"] = state
        actual.pop("recoveryProof", None)
        path.write_text(json.dumps(actual))
        self.manager = self.open_manager()
        return self.manager.get(self.job["id"])

    def methods(self):
        return [json.loads(line)["method"] for line in (self.protocol / "methods.jsonl").read_text().splitlines()]

    def change_stored(self, **updates):
        path = self.protocol / "stored.json"
        value = json.loads(path.read_text())
        value.update(updates)
        path.write_text(json.dumps(value))

    def tearDown(self):
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
            self.server_thread.join(timeout=1)
        self.manager.close()
        self.assertFalse(self.manager.processes)
        self.assertFalse(self.manager.drivers)
        self.assertFalse(self.manager.resources)
        self.temp.cleanup()

    def test_actual_original_http_read_proves_exact_terminated_turn_without_reconstructing_old_output_or_running_a_turn(self):
        before = self.cold_restart()
        self.assertEqual("interrupted", before["status"])
        self.assertFalse(before["controls"]["threadResume"])
        self.assertTrue(before["controls"]["readSessionRecovery"])
        self.assertNotIn("_coldProcess", before)
        boundary = len(self.methods())
        self.server = CompanionServer(("127.0.0.1", 0), self.manager)
        self.server_thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        self.server_thread.start()
        request = Request("http://127.0.0.1:" + str(self.server.server_port) + "/v1/jobs/" + self.job["id"] + "/session-recovery", headers={"Authorization": "Bearer " + TOKEN})
        with urlopen(request, timeout=3) as response:
            actual = json.loads(response.read())["result"]
        self.assertEqual("interrupted", actual["status"])
        self.assertEqual(self.original["output"], actual["output"])
        self.assertTrue(actual["controls"]["threadResume"])
        proof = actual["recoveryProof"]
        self.assertEqual(self.job["id"], proof["jobId"])
        self.assertEqual("fixture-turn-1", proof["turnId"])
        self.assertTrue(proof["processGroupStoppedVerified"])
        self.assertTrue(proof["storedLatestTurnVerified"])
        self.assertFalse(proof["businessSuccessVerified"])
        self.assertFalse(proof["internalUsageKnown"])
        self.assertEqual(["initialize", "initialized", "thread/read", "thread/turns/list"], self.methods()[boundary:])
        self.assertEqual("1", (self.protocol / "starts").read_text())
        self.manager.close()
        self.manager = self.open_manager()
        self.assertEqual(proof, self.manager.get(self.job["id"])["recoveryProof"])
        self.assertTrue(self.manager.get(self.job["id"])["controls"]["threadResume"])

    def test_live_original_process_or_changed_namespace_config_and_unknown_input_never_create_a_probe_or_clear_unknowns(self):
        self.cold_restart()
        job = self.manager.jobs[self.job["id"]]
        env = self.manager._environment("computer")
        before = len(self.methods())
        worker = subprocess.Popen([sys.executable, "-c", "import time;time.sleep(10)"], start_new_session=True)
        try:
            saved = job["_coldProcess"]
            job["_coldProcess"] = cold_session.capture(job, env, worker)
            with self.assertRaises(RequestError):
                cold_session.inspect(self.manager, job["id"], RequestError)
            job["_coldProcess"] = saved
        finally:
            worker.terminate()
            worker.wait(timeout=2)
        original_boot = job["_coldProcess"]["bootId"]
        job["_coldProcess"]["bootId"] = "00000000-0000-0000-0000-000000000000"
        self.assertFalse(cold_session.process_stopped(job, env))
        job["_coldProcess"]["bootId"] = original_boot
        original_namespace = list(job["_coldProcess"]["pidNamespace"])
        job["_coldProcess"]["pidNamespace"] = [0, 0]
        self.assertFalse(cold_session.process_stopped(job, env))
        job["_coldProcess"]["pidNamespace"] = original_namespace
        job["approvalReceipts"] = [{"status": "unknown", "requestId": "original-pending"}]
        with self.assertRaises(RequestError):
            cold_session.inspect(self.manager, job["id"], RequestError)
        self.assertEqual("unknown", job["approvalReceipts"][0]["status"])
        job["approvalReceipts"] = []
        env["writeAllowed"] = False
        with self.assertRaises(RequestError):
            cold_session.inspect(self.manager, job["id"], RequestError)
        self.assertEqual(before, len(self.methods()))
        self.assertNotIn("recoveryProof", job)

    def test_installed_old_rpc_active_or_different_latest_turn_and_cwd_fail_closed_despite_absent_original_process(self):
        self.cold_restart()
        original = json.loads((self.protocol / "stored.json").read_text())
        for change in ({"unsupported": True}, {"threadStatus": "active"}, {"turnId": "another-later-turn"}, {"status": "inProgress"}, {"cwd": "/different-workspace"}):
            with self.subTest(change=change):
                (self.protocol / "stored.json").write_text(json.dumps({**original, **change}))
                boundary = len(self.methods())
                with self.assertRaises(RequestError):
                    cold_session.inspect(self.manager, self.job["id"], RequestError)
                self.assertFalse(self.manager.get(self.job["id"])["controls"]["threadResume"])
                self.assertNotIn("turn/start", self.methods()[boundary:])
                self.assertNotIn("thread/resume", self.methods()[boundary:])
        self.assertEqual("1", (self.protocol / "starts").read_text())
        (self.protocol / "stored.json").write_text(json.dumps(original))
        good = cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertTrue(good["controls"]["threadResume"])
        self.change_stored(turnId="another-new-turn")
        with self.assertRaises(RequestError):
            cold_session.inspect(self.manager, self.job["id"], RequestError)
        stale = self.manager.get(self.job["id"])
        self.assertEqual(good["recoveryProof"], stale["recoveryProof"])
        self.assertFalse(stale["controls"]["threadResume"])
        self.assertEqual("unconfirmed", stale["coldRecovery"]["lastInspection"]["status"])

    def test_new_owner_timeout_and_proof_required_then_same_session_has_a_new_original_job_and_exact_preflight_read(self):
        self.cold_restart()
        actual = cold_session.inspect(self.manager, self.job["id"], RequestError)
        proof = actual["recoveryProof"]
        request = {"operationId": "new-reviewed-operation", "environmentId": "computer", "worker": "codex_app_server", "prompt": "New owner reviewed work", "threadId": actual["threadId"], "resumeJobId": actual["id"], "timeoutSeconds": 10, "recoveryProofId": proof["proofId"], "recoveryOwnerAcknowledged": True}
        for change in ({"recoveryOwnerAcknowledged": False}, {"recoveryProofId": "wrong"}, {"timeoutSeconds": 901}):
            with self.assertRaises(RequestError):
                self.manager.submit({**request, **change})
        boundary = len(self.methods())
        accepted = self.manager.submit(request)
        final = self.wait(accepted["id"])
        self.assertEqual("completed", final["status"])
        self.assertNotEqual(actual["id"], accepted["id"])
        self.assertEqual(actual["id"], final["resumeJobId"])
        self.assertEqual(actual["threadId"], final["threadId"])
        methods = self.methods()[boundary:]
        self.assertLess(methods.index("thread/read"), methods.index("thread/resume"))
        self.assertLess(methods.index("thread/turns/list"), methods.index("turn/start"))
        self.assertEqual(1, methods.count("turn/start"))
        self.assertEqual("interrupted", self.manager.get(actual["id"])["status"])
        self.assertEqual(self.original["output"], self.manager.get(actual["id"])["output"])
        self.assertFalse(self.manager.get(actual["id"])["controls"]["threadResume"])
        self.assertEqual(accepted["id"], self.manager.submit(request)["id"])
        self.assertEqual("2", (self.protocol / "starts").read_text())

    def test_latest_turn_changed_after_owner_review_blocks_the_queued_new_invocation_before_any_resume_or_start(self):
        self.cold_restart()
        actual = cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.change_stored(turnId="external-later-turn")
        boundary = len(self.methods())
        accepted = self.manager.submit({"operationId": "new-but-stale-scope", "environmentId": "computer", "worker": "codex_app_server", "prompt": "Explicit new owner work", "threadId": actual["threadId"], "resumeJobId": actual["id"], "timeoutSeconds": 10, "recoveryProofId": actual["recoveryProof"]["proofId"], "recoveryOwnerAcknowledged": True})
        final = self.wait(accepted["id"])
        self.assertEqual("blocked", final["status"])
        self.assertNotIn("thread/resume", self.methods()[boundary:])
        self.assertNotIn("turn/start", self.methods()[boundary:])
        self.assertEqual("1", (self.protocol / "starts").read_text())
