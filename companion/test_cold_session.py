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
        with self.assertRaisesRegex(RequestError, "unconfigured cold proof"):
            self.manager.submit({**request, "harnessConfigOperationId": "different-home-config", "harnessOwnerBinding": {}})
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


    def test_legacy_verified_proof_rejected_when_either_configuration_binding_survives(self):
        self.cold_restart()
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        job = self.manager.jobs[self.job["id"]]
        env = self.manager._environment("computer")
        boundary = len(self.methods())
        for key, value in (("harnessConfigOperationId", "legacy-config-operation"),
                           ("_harnessConfigRequest", {"harnessConfigOperationId": "legacy-config-operation"}),
                           ("harnessConfigOperationId", None), ("_harnessConfigRequest", {})):
            with self.subTest(key=key, value=value):
                job[key] = value
                try:
                    self.assertFalse(cold_session.eligible(job))
                    self.assertFalse(cold_session.proof_matches(job, env))
                    self.assertFalse(self.manager.get(job["id"])["controls"]["threadResume"])
                    with self.assertRaisesRegex(RequestError, "configuration home"):
                        cold_session.inspect(self.manager, job["id"], RequestError)
                    self.assertEqual(verified["recoveryProof"], job["recoveryProof"])
                    with self.assertRaises(RequestError):
                        self.manager.submit({"operationId": "denied-config-" + key + str(value is None), "environmentId": "computer", "worker": "codex_app_server", "prompt": "Fresh owner-reviewed continuation", "threadId": job["threadId"], "resumeJobId": job["id"], "timeoutSeconds": 10, "recoveryProofId": verified["recoveryProof"]["proofId"], "recoveryOwnerAcknowledged": True})
                finally:
                    job.pop(key)
        self.assertEqual(boundary, len(self.methods()))
        self.assertTrue(cold_session.proof_matches(job, env))


@unittest.skipUnless(os.name != "nt" and Path("/proc/sys/kernel/random/boot_id").is_file(), "Requires actual local Linux process identity fixture")
class ConfiguredColdRecoveryTests(unittest.TestCase):
    def test_configured_original_and_legacy_pin_never_probe_default_home_or_rebind_lease(self):
        from unittest.mock import patch
        import test_harness_config as controlled

        fixture = controlled.HarnessConfigurationTests()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        manager = fixture.manager
        plan, _receipt = fixture.applied()
        default_home = fixture.root / "default-codex-home"
        default_home.mkdir()
        cli = fixture.root / "home-aware-controlled-cli.py"
        cli.write_text(PIPE.replace("home=Path(sys.argv[1])", "home=Path(os.environ['CODEX_HOME'])"))
        manager._launch = lambda env, argv: ([sys.executable, str(cli)], env["root"])
        request = {"operationId": "configured-cold-original", "environmentId": "computer", "worker": "codex_app_server", "prompt": "Original configured fixture work", "timeoutSeconds": 10,
                   "harnessConfigOperationId": plan["operationId"], "harnessOwnerBinding": {"taskId": "task-original", "runId": "run-original", "epoch": 3, "profileDigest": "a" * 64, "endpointLeaseRef": "gateway-one"}}
        with patch.dict(os.environ, {"CODEX_HOME": str(default_home)}):
            job = manager.submit(request)
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline:
                original = manager.get(job["id"])
                if original["status"] in {"completed", "failed", "blocked"} and job["id"] not in manager.processes:
                    break
                time.sleep(.01)
            self.assertEqual("completed", original["status"], original)
            self.assertEqual("1", (fixture.config_root / "starts").read_text())
            self.assertFalse((default_home / "methods.jsonl").exists())
            manager.close()
            path = fixture.root / "state" / ("job-" + job["id"] + ".json")
            actual = json.loads(path.read_text())
            # Emulate an old-version cold pin using the original supervisor's actual
            # process identity, plus loss of the final status save. No real CLI/home is read.
            actual["_coldProcess"] = {**actual["_originalProcessGroup"], "format": cold_session.PROTOCOL}
            actual["status"] = "running"
            path.write_text(json.dumps(actual))
            # Even an unrelated default store with matching IDs must never be consulted.
            (default_home / "stored.json").write_text((fixture.config_root / "stored.json").read_text())
            manager = fixture.manager = JobManager(fixture.config, controlled.TOKEN)
            manager._launch = lambda env, argv: ([sys.executable, str(cli)], env["root"])
            self.assertTrue(cold_session.original_process_group_stopped(manager.jobs[job["id"]], manager._environment("computer")))
            leases = manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall()
            operations = manager.ledger.execute("SELECT * FROM harness_receipts").fetchall()
            with self.assertRaisesRegex(RequestError, "configuration home"):
                cold_session.inspect(manager, job["id"], RequestError)
            cold = manager.get(job["id"])
            self.assertEqual("interrupted", cold["status"])
            self.assertFalse(cold["controls"]["readSessionRecovery"])
            self.assertFalse(cold["controls"]["threadResume"])
            self.assertFalse((default_home / "methods.jsonl").exists())
            self.assertEqual("1", (fixture.config_root / "starts").read_text())
            self.assertNotIn("recoveryProof", cold)
            self.assertEqual(leases, manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall())
            self.assertEqual(operations, manager.ledger.execute("SELECT * FROM harness_receipts").fetchall())


@unittest.skipUnless(os.name != "nt" and Path("/proc/sys/kernel/random/boot_id").is_file(), "Requires actual local Linux process identity fixture")
class PinnedConfiguredColdRecoveryTests(unittest.TestCase):
    def setUp(self):
        from unittest.mock import patch
        import test_harness_config as controlled

        self.fixture = fixture = controlled.HarnessConfigurationTests()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        self.default_home = fixture.root / "default-codex-home"
        self.default_home.mkdir()
        self.environment = patch.dict(os.environ, {"CODEX_HOME": str(self.default_home)})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        fixture.executable.write_text("#!/usr/bin/env python3\nimport sys\nif sys.argv[1:] == ['--version']:\n print('codex-cli 0.164.0-alpha.11')\n raise SystemExit\n" + PIPE.replace("home=Path(sys.argv[1])", "home=Path(os.environ['CODEX_HOME'])"))
        self.manager = fixture.manager
        self.other_home = fixture.root / "another-codex-home"
        self.other_home.mkdir()
        self.other_target = {**fixture.target, "bindingId": "another-codex", "configRoot": str(self.other_home)}
        self.manager.environments["computer"]["harnesses"].append(self.other_target)
        self.manager._launch = self.launch
        spawn = self.manager._spawn
        def original_spawn(*args, **kwargs):
            self.active_lease = self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchone()
            return spawn(*args, **kwargs)
        self.manager._spawn = original_spawn
        self.plan, _receipt = fixture.applied()
        self.job = self.manager.submit({"operationId": "pinned-original", "environmentId": "computer", "worker": "codex_app_server", "prompt": "Original approved-home work", "timeoutSeconds": 10,
                                        "harnessConfigOperationId": self.plan["operationId"], "harnessOwnerBinding": self.owner(self.plan)})
        self.assertEqual("completed", self.wait(self.job["id"])["status"])
        self.assertIn("_coldConfigHome", self.manager.jobs[self.job["id"]])
        self.assertIn("_coldProcess", self.manager.jobs[self.job["id"]])
        self.manager.close()
        path = fixture.root / "state" / ("job-" + self.job["id"] + ".json")
        actual = json.loads(path.read_text())
        actual["status"] = "running"  # Loss of the final supervisor save only.
        path.write_text(json.dumps(actual))
        # This unrelated store deliberately contains matching IDs to expose fallback.
        (self.default_home / "stored.json").write_text((fixture.config_root / "stored.json").read_text())
        self.manager = fixture.manager = JobManager(fixture.config, controlled.TOKEN)
        fixture.service = self.manager.harness_configs
        self.manager._launch = self.launch
        self.assertEqual("interrupted", self.manager.get(self.job["id"])["status"])

    def launch(self, env, argv):
        return [str(self.fixture.executable), *argv[1:]], env["root"]

    def wait(self, jobid):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            value = self.manager.get(jobid)
            if value["status"] in {"completed", "failed", "blocked", "cancelled"} and jobid not in self.manager.processes:
                return value
            time.sleep(.01)
        self.fail("Controlled configured process did not settle: " + str(value))

    def methods(self):
        return [json.loads(line)["method"] for line in (self.fixture.config_root / "methods.jsonl").read_text().splitlines()]

    def owner(self, plan):
        row = self.manager.ledger.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (plan["operationId"],)).fetchone()
        request = json.loads(row[0])["request"]
        return {key: request[key] for key in ("taskId", "runId", "epoch", "profileDigest", "endpointLeaseRef")}

    def continuation(self, verified, **extra):
        return {"operationId": "new-reviewed-run", "environmentId": "computer", "worker": "codex_app_server", "prompt": "New reviewed work and budget", "threadId": verified["threadId"], "resumeJobId": verified["id"], "timeoutSeconds": 10,
                "recoveryProofId": verified["recoveryProof"]["proofId"], "recoveryOwnerAcknowledged": True, **extra}

    def test_read_uses_original_home_without_reviving_endpoint_or_mutating_config_lease(self):
        before = {table: self.manager.ledger.execute("SELECT * FROM " + table).fetchall() for table in ("harness_plans", "harness_receipts", "harness_root_leases")}
        boundary = len(self.methods())
        self.assertEqual({}, self.manager.harness_configs.endpoints)
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertNotIn("_coldConfigHome", verified)
        self.assertTrue(verified["controls"]["threadResume"])
        self.assertIn("configurationHomeHash", verified["recoveryProof"])
        self.assertEqual(["initialize", "initialized", "thread/read", "thread/turns/list"], self.methods()[boundary:])
        self.assertFalse((self.default_home / "methods.jsonl").exists())
        self.assertEqual("1", (self.fixture.config_root / "starts").read_text())

        self.assertEqual({}, self.manager.harness_configs.endpoints)
        for table, rows in before.items():
            self.assertEqual(rows, self.manager.ledger.execute("SELECT * FROM " + table).fetchall())
        with self.assertRaisesRegex(RequestError, "NEW current owner configuration"):
            self.manager.submit(self.continuation(verified))
        # The cold operation cannot itself authorize a fresh launch after restart.
        with self.assertRaises(RequestError):
            self.manager.submit(self.continuation(verified, harnessConfigOperationId=self.plan["operationId"], harnessOwnerBinding=self.owner(self.plan)))
        self.assertEqual("1", (self.fixture.config_root / "starts").read_text())

    def test_actual_active_root_snapshot_stays_active_after_read_and_cannot_reauthorize_itself(self):
        # Restore the actual original pre-spawn lease row to emulate loss of the
        # cleanup commit, rather than inventing a new grant or releasing the root.
        self.assertEqual("ACTIVE", self.active_lease[4])
        self.manager.ledger.execute("DELETE FROM harness_root_leases")
        self.manager.ledger.execute("INSERT INTO harness_root_leases VALUES (?,?,?,?,?,?)", self.active_lease)
        self.manager.ledger.commit()
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertTrue(verified["controls"]["threadResume"])
        self.assertEqual(self.active_lease, self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchone())
        with self.assertRaises(RequestError):
            self.manager.submit(self.continuation(verified, harnessConfigOperationId=self.plan["operationId"], harnessOwnerBinding=self.owner(self.plan)))
        from harness_config import ConfigError
        with self.assertRaises(ConfigError):
            self.fixture.restore()
        self.assertEqual(self.active_lease, self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchone())
        self.assertFalse((self.default_home / "methods.jsonl").exists())

    def test_fresh_owner_config_for_another_home_is_rejected_before_binding_its_job(self):
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        fixture = self.fixture
        binding = {**fixture.binding(), "bindingId": self.other_target["bindingId"]}
        lease = fixture.lease("another-gateway")
        probe = fixture.service.probe(binding)
        plan = fixture.service.plan({**binding, "operationId": "another-home-config", "identityDigest": probe["identityDigest"],
                                     "profileDigest": lease["profileDigest"], "endpointLeaseRef": lease["leaseId"], "taskId": lease["taskId"], "runId": lease["runId"], "epoch": lease["epoch"], "model": "another-model"})
        receipt = fixture.service.apply(plan["planId"], {**binding, "operationId": plan["operationId"], "planDigest": plan["planDigest"]})
        self.assertEqual("COMMITTED", receipt["state"])
        leases = self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall()
        boundary = len(self.methods())
        with self.assertRaisesRegex(RequestError, "cannot switch the original home"):
            self.manager.submit(self.continuation(verified, harnessConfigOperationId=plan["operationId"], harnessOwnerBinding=self.owner(plan)))
        self.assertEqual(leases, self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall())
        self.assertEqual(boundary, len(self.methods()))
        self.assertFalse((self.other_home / "methods.jsonl").exists())

    def test_original_restore_and_fresh_owner_configuration_can_continue_same_home_and_thread(self):
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        fixture = self.fixture
        restore, receipt = fixture.restore()
        self.assertEqual("COMMITTED", receipt["state"])
        fresh = fixture.service.plan(fixture.request(operation="fresh-config", model="fresh-reviewed-model"))
        self.assertEqual("COMMITTED", fixture.apply(fresh)["state"])
        boundary = len(self.methods())
        accepted = self.manager.submit(self.continuation(verified, harnessConfigOperationId=fresh["operationId"], harnessOwnerBinding=self.owner(fresh)))
        final = self.wait(accepted["id"])
        self.assertEqual("completed", final["status"], final)
        self.assertEqual(verified["threadId"], final["threadId"])
        self.assertEqual("2", (fixture.config_root / "starts").read_text())
        self.assertFalse((self.default_home / "methods.jsonl").exists())
        methods = self.methods()[boundary:]
        self.assertLess(methods.index("thread/turns/list"), methods.index("thread/resume"))
        self.assertLess(methods.index("thread/turns/list"), methods.index("turn/start"))
        self.assertEqual(1, methods.count("turn/start"))
        self.assertEqual("interrupted", self.manager.get(verified["id"])["status"])

    def test_config_file_drift_fails_before_any_probe_and_invalidates_old_read(self):
        verified = cold_session.inspect(self.manager, self.job["id"], RequestError)
        boundary = len(self.methods())
        before = self.fixture.file.read_text()
        self.fixture.file.write_text(before + "\n# later owner change\n")
        with self.assertRaisesRegex(RequestError, "configuration home/source"):
            cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertEqual(boundary, len(self.methods()))
        failed = self.manager.get(self.job["id"])
        self.assertEqual("unconfirmed", failed["coldRecovery"]["lastInspection"]["status"])
        self.assertFalse(failed["controls"]["threadResume"])
        self.assertEqual(verified["recoveryProof"], failed["recoveryProof"])
        self.fixture.file.write_text(before)
        self.assertTrue(cold_session.inspect(self.manager, self.job["id"], RequestError)["controls"]["threadResume"])

    def test_config_drift_after_probe_admission_cannot_publish_recovery_proof(self):
        boundary = len(self.methods())
        def changed_launch(env, argv):
            self.fixture.file.write_text(self.fixture.file.read_text() + "\n# owner changed during read\n")
            return self.launch(env, argv)
        self.manager._launch = changed_launch
        with self.assertRaisesRegex(RequestError, "configuration home/source"):
            cold_session.inspect(self.manager, self.job["id"], RequestError)
        failed = self.manager.get(self.job["id"])
        self.assertNotIn("recoveryProof", failed)
        self.assertFalse(failed["controls"]["threadResume"])
        self.assertEqual("unconfirmed", failed["coldRecovery"]["lastInspection"]["status"])
        self.assertNotIn("turn/start", self.methods()[boundary:])
        self.assertNotIn("thread/resume", self.methods()[boundary:])
        self.assertFalse((self.default_home / "methods.jsonl").exists())

    def test_executable_drift_and_harness_definition_drift_never_probe(self):
        boundary = len(self.methods())
        self.fixture.executable.write_text(self.fixture.executable.read_text() + "\n# changed executable\n")
        with self.assertRaisesRegex(RequestError, "configuration home/source"):
            cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertEqual(boundary, len(self.methods()))
        self.manager.environments["computer"]["harnesses"][0]["configRoot"] = str(self.default_home)
        with self.assertRaises(RequestError):
            cold_session.inspect(self.manager, self.job["id"], RequestError)
        self.assertEqual(boundary, len(self.methods()))
        self.assertFalse((self.default_home / "methods.jsonl").exists())
