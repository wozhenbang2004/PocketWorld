"""Actual local Companion/worker/files; synthetic protocol peer, no external accounts."""
import base64
import hashlib
import json
import os
from pathlib import Path
import time
import unittest
import uuid
from unittest.mock import patch
import project_history
from pocket_companion import RequestError
from test_cold_session import ColdRecoveryTests, PIPE

PROJECT_PIPE = PIPE.replace(" elif method=='turn/start':\n", """ elif method=='turn/start':
  Path('source.txt').write_text('worker version two\\n')
  Path('image.bin').write_bytes(bytes([0,255,128,3]))
  if Path('deleted.txt').exists():Path('deleted.txt').unlink()
  emit({'method':'item/completed','params':{'threadId':'fixture-thread','turnId':'fixture-turn-'+str(int((home/'starts').read_text())+1),'item':{'id':'actual-write','type':'fileChange','changes':[{'path':'source.txt'}]}}})
""").replace("'fixture-thread'", "'project-thread'")

FORK_PIPE = r'''
import json,os,sys
from pathlib import Path
home=Path(sys.argv[1])
def emit(v):print(json.dumps(v),flush=True)
for line in sys.stdin:
 v=json.loads(line);method=v.get('method');params=v.get('params',{})
 if method:
  with(home/'methods.jsonl').open('a')as output:output.write(json.dumps({'method':method,'params':params})+'\n')
 if method=='initialize':emit({'id':v['id'],'result':{'userAgent':'controlled-fork-protocol-fixture'}})
 elif method=='initialized':pass
 elif method=='turn/steer':emit({'id':v['id'],'error':{'code':-32600,'message':'thread not found: '+params['threadId']}})
 elif method in {'thread/read','thread/turns/list'}:
  state=json.loads((home/('branch.json' if params['threadId']=='fixture-branch' else 'stored.json')).read_text())
  if method=='thread/read':emit({'id':v['id'],'result':{'thread':{'id':state['threadId'],'cwd':os.getcwd(),'status':{'type':'notLoaded'}}}})
  else:emit({'id':v['id'],'result':{'data':[{'id':state['turnId'],'status':state['status']}],'nextCursor':None,'backwardsCursor':None}})
 elif method=='thread/fork':
  count=home/'forks';count.write_text(str(int(count.read_text())+1 if count.exists()else 1))
  if(home/'unsupported-fork').exists():emit({'id':v['id'],'error':{'code':-32601,'message':'actual installed peer has no fork'}});continue
  assert params=={'threadId':'fixture-thread','lastTurnId':'fixture-turn-1','excludeTurns':True}
  source=json.loads((home/'stored.json').read_text());source['threadId']='fixture-branch';(home/'branch.json').write_text(json.dumps(source))
  if(home/'drop-fork-ack').exists():sys.exit(0)
  emit({'id':v['id'],'result':{'thread':{'id':'fixture-branch','forkedFromId':'fixture-thread','sessionId':'fixture-thread'}}})
 elif method=='thread/resume':emit({'id':v['id'],'result':{'thread':{'id':params['threadId']}}})
 elif method=='turn/start':
  assert params['threadId']=='fixture-branch'
  count=home/'starts';count.write_text(str(int(count.read_text())+1));turn='branch-turn-2'
  (home/'branch.json').write_text(json.dumps({'threadId':'fixture-branch','turnId':turn,'status':'completed','cwd':os.getcwd()}))
  emit({'id':v['id'],'result':{'turn':{'id':turn}}});emit({'method':'turn/started','params':{'threadId':'fixture-branch','turn':{'id':turn}}})
  emit({'method':'item/agentMessage/delta','params':{'threadId':'fixture-branch','turnId':turn,'delta':'Actual branch output, no file restore'}})
  emit({'method':'turn/completed','params':{'threadId':'fixture-branch','turn':{'id':turn,'status':'completed'}}})
 else:emit({'id':v.get('id'),'error':{'code':-32601,'message':'Not a controlled fork method'}})
'''


class ProjectHistoryTests(unittest.TestCase):
    def setUp(self):
        self.f = ColdRecoveryTests()
        self.f.setUp()
        self.f.cli.write_text(PROJECT_PIPE)
        (self.f.workspace / "source.txt").write_text("owner baseline\n")
        (self.f.workspace / "deleted.txt").write_text("explicit old file\n")
        (self.f.workspace / ".env").write_text("DO_NOT_CAPTURE=private\n")

    def tearDown(self):
        self.f.tearDown()

    def body(self):
        return {"operationId": str(uuid.uuid4()), "environmentId": "computer", "environmentBinding": self.f.manager.environment_binding("computer"), "worker": "codex_app_server", "prompt": "Modify the selected public project", "timeoutSeconds": 30,
                "projectHistory": {"protocol": project_history.PROTOCOL, "projectRoot": "", "publicSourceReadAcknowledged": True, "externalChangesMayBeUnattributed": True}}

    def final(self, body=None):
        job = self.f.manager.submit(body or self.body())
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            current = self.f.manager.get(job["id"])
            if current["status"] in {"blocked", "failed", "cancelled", "unknown"} or current.get("projectHistory", {}).get("comparisonStatus") in {"available", "unavailable"}:
                if not self.f.manager.resources:
                    return current
            time.sleep(.01)
        self.fail("Original project worker/comparison did not reach a bounded settled state: " + str(current))

    def restore(self, job, path="source.txt", snapshot=None, operation=None):
        baseline = snapshot or job["projectHistory"]["baseline"]
        actual = project_history.current_file(self.f.manager, self.f.manager.jobs[job["id"]], path, RequestError)
        request = {"operationId": operation or str(uuid.uuid4()), "jobOperationId": job["operationId"], "snapshotId": baseline["snapshotId"], "snapshotVersion": baseline["snapshotVersion"], "path": path,
                   "expectedVersion": actual["contentVersion"], "expectedFileVersion": actual["fileVersion"], "environmentBinding": self.f.manager.environment_binding("computer"), "approvalExpiresAt": self.f.manager.now() + 60000}
        return request

    def test_actual_prelaunch_public_baseline_binary_add_delete_and_unattributed_change_diff(self):
        job = self.final()
        self.assertEqual("completed", job["status"], job)
        self.assertEqual("available", job["projectHistory"]["comparisonStatus"])
        self.assertFalse(job["projectHistory"]["authorshipVerified"])
        page = project_history.read(self.f.manager, job["id"], {}, RequestError)
        files = {file["path"]: file for file in page["files"]}
        self.assertEqual({"source.txt", "deleted.txt", "image.bin"}, set(files))
        self.assertEqual("modified", files["source.txt"]["change"])
        self.assertTrue(files["source.txt"]["workerReportedPath"])
        self.assertFalse(files["image.bin"]["workerReportedPath"])
        self.assertEqual("unattributed_observed_workspace_change", files["image.bin"]["attribution"])
        self.assertEqual("deleted", files["deleted.txt"]["change"])
        self.assertNotIn("DO_NOT_CAPTURE", json.dumps(self.f.manager.jobs[job["id"]]))
        difference = project_history.read(self.f.manager, job["id"], {"diffPath": "source.txt"}, RequestError)
        self.assertIn("-owner baseline", difference["diff"])
        self.assertIn("+worker version two", difference["diff"])
        self.assertEqual([], job["resultSnapshots"], "Ordinary selected-result snapshots must not silently gain whole-project contents")
        self.assertEqual(job["id"], self.f.manager.submit(self.body() | {"operationId": job["operationId"]})["id"])

    def test_exact_original_binary_file_restore_uses_original_ledger_and_does_not_restore_session(self):
        (self.f.workspace / "binary.bin").write_bytes(b"\x00\xffowner-binary\x80")
        job = self.final()
        (self.f.workspace / "binary.bin").write_bytes(b"later-public-bytes")
        request = self.restore(job, "binary.bin")
        receipt = project_history.restore(self.f.manager, job["id"], request, RequestError)
        self.assertEqual("completed", receipt["status"], receipt)
        self.assertEqual(b"\x00\xffowner-binary\x80", (self.f.workspace / "binary.bin").read_bytes())
        self.assertFalse(receipt["restoresConversation"])
        self.assertFalse(receipt["atomicWorkspaceTransaction"])
        self.assertEqual(receipt, project_history.restore(self.f.manager, job["id"], request, RequestError))
        self.assertEqual(1, self.f.manager.ledger.execute("SELECT count(*) FROM file_operations WHERE operation_id=?", (request["operationId"],)).fetchone()[0])
        self.assertEqual("2", (self.f.protocol / "starts").read_text())

    def test_reviewed_current_file_drift_or_environment_generation_rejects_before_replace(self):
        job = self.final()
        request = self.restore(job)
        (self.f.workspace / "source.txt").write_text("independent external author\n")
        rejected = project_history.restore(self.f.manager, job["id"], request, RequestError)
        self.assertEqual("failed", rejected["status"])
        self.assertTrue(rejected["noReplacementDispatchConfirmed"])
        self.assertFalse(rejected["replacementAttempted"])
        self.assertEqual("independent external author\n", (self.f.workspace / "source.txt").read_text())
        self.assertEqual("failed", self.f.manager.files.operation(request["operationId"])["status"])
        request = self.restore(job)
        self.f.manager.environments["computer"]["writeAllowed"] = False  # Real execution binding, not a cosmetic label.
        with self.assertRaises(RequestError):
            project_history.restore(self.f.manager, job["id"], request, RequestError)

    def test_public_symlink_or_overbound_file_blocks_worker_without_partial_success(self):
        (self.f.workspace / "linked").symlink_to(self.f.root / "outside-not-authorized")
        job = self.final()
        self.assertEqual("blocked", job["status"], job)
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        self.assertEqual("not_selected", job["projectHistory"]["comparisonStatus"])
        (self.f.workspace / "linked").unlink()
        (self.f.workspace / "over.bin").write_bytes(b"x" * (project_history.MAX_FILE + 1))
        job = self.final()
        self.assertEqual("blocked", job["status"])
        self.assertEqual("1", (self.f.protocol / "starts").read_text())

    def test_cancel_while_baseline_is_capturing_cannot_dispatch_late_worker(self):
        original = project_history.scan
        def cancelled(manager, job, error, current):
            with manager.lock:
                manager.stopping.add(job["id"])
            return original(manager, job, error, current)
        with patch.object(project_history, "scan", cancelled):
            job = self.final()
        self.assertEqual("cancelled", job["status"], job)
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        self.assertEqual(0, len(self.f.manager.processes))
        self.assertEqual(0, len(self.f.manager.resources))

    def fork_body(self):
        source = self.f.original
        return {"operationId": str(uuid.uuid4()), "environmentId": "computer", "environmentBinding": self.f.manager.environment_binding("computer"), "worker": "codex_app_server", "prompt": "Explicit NEW branch work without changing files", "readOnly": False, "timeoutSeconds": 30,
                "forkSource": {"jobId": source["id"], "jobOperationId": source["operationId"], "threadId": source["threadId"], "turnId": source["turnId"]}}

    def test_actual_conversation_fork_has_distinct_thread_new_job_one_prompt_and_no_file_restore(self):
        self.f.cli.write_text(FORK_PIPE)
        body = self.fork_body()
        original = dict(self.f.original)
        (self.f.workspace / "source.txt").write_text("independent latest public bytes\n")
        boundary = len(self.f.methods())
        job = self.f.wait(self.f.manager.submit(body)["id"])
        self.assertEqual("completed", job["status"], job)
        self.assertEqual("fixture-branch", job["threadId"])
        self.assertEqual("branch-turn-2", job["turnId"])
        self.assertEqual(original["id"], job["sessionBranchSource"]["jobId"])
        self.assertFalse(job["sessionBranch"]["filesRestored"])
        self.assertFalse(job["sessionBranch"]["oldApprovalOrBudgetInherited"])
        self.assertEqual("independent latest public bytes\n", (self.f.workspace / "source.txt").read_text())
        self.assertEqual(original["output"], self.f.manager.get(original["id"])["output"])
        self.assertNotIn("thread/start", self.f.methods()[boundary:])
        self.assertEqual(1, self.f.methods()[boundary:].count("thread/fork"))
        self.assertEqual(1, self.f.methods()[boundary:].count("turn/start"))
        self.assertEqual(job["id"], self.f.manager.submit(body)["id"])
        self.assertEqual("1", (self.f.protocol / "forks").read_text())

    def test_unsupported_fork_or_changed_latest_turn_never_falls_back_to_create(self):
        self.f.cli.write_text(FORK_PIPE)
        (self.f.protocol / "unsupported-fork").touch()
        boundary = len(self.f.methods())
        body = self.fork_body()
        job = self.f.wait(self.f.manager.submit(body)["id"])
        self.assertEqual("blocked", job["status"], job)
        self.assertNotIn("turn/start", self.f.methods()[boundary:])
        self.assertNotIn("thread/start", self.f.methods()[boundary:])
        self.assertEqual(job["id"], self.f.manager.submit(body)["id"])

    def test_changed_latest_source_turn_blocks_before_any_fork_or_prompt(self):
        self.f.cli.write_text(FORK_PIPE)
        self.f.change_stored(turnId="unreviewed-later-turn")
        boundary = len(self.f.methods())
        job = self.f.wait(self.f.manager.submit(self.fork_body())["id"])
        self.assertEqual("blocked", job["status"], job)
        self.assertNotIn("thread/fork", self.f.methods()[boundary:])
        self.assertNotIn("turn/start", self.f.methods()[boundary:])

    def test_lost_fork_ack_keeps_exact_new_job_unknown_without_repeating_fork_or_prompt(self):
        self.f.cli.write_text(FORK_PIPE)
        (self.f.protocol / "drop-fork-ack").touch()
        body = self.fork_body()
        boundary = len(self.f.methods())
        job = self.f.wait(self.f.manager.submit(body)["id"])
        self.assertEqual("unknown", job["status"], job)
        self.assertEqual("sending", job["sessionBranch"]["phase"])
        self.assertEqual(job["id"], self.f.manager.submit(body)["id"])
        self.assertNotIn("turn/start", self.f.methods()[boundary:])
        self.assertNotIn("thread/start", self.f.methods()[boundary:])
        self.assertEqual("1", (self.f.protocol / "forks").read_text())

    def test_cold_original_versions_and_new_reviewed_environment_read_do_not_start_any_worker(self):
        job = self.final()
        first = project_history.read(self.f.manager, job["id"], {}, RequestError)
        self.f.manager.close()
        self.f.manager = self.f.open_manager()
        cold = project_history.read(self.f.manager, job["id"], {}, RequestError)
        self.assertEqual(first["pageVersion"], cold["pageVersion"])
        self.assertEqual(first["history"]["baseline"], cold["history"]["baseline"])
        request = self.restore(job)
        receipt = project_history.restore(self.f.manager, job["id"], request, RequestError)
        self.assertEqual("completed", receipt["status"], receipt)
        self.assertEqual("owner baseline\n", (self.f.workspace / "source.txt").read_text())
        self.assertEqual("2", (self.f.protocol / "starts").read_text())

    def test_uncertain_replacement_is_durable_on_original_file_ledger_and_never_replayed(self):
        job = self.final()
        request = self.restore(job)
        real_replace = os.replace
        attempts = []
        def lost_result(source, target, **options):
            if str(source).startswith(".pocketworld-write-"):
                attempts.append(str(source))
                real_replace(source, target, **options)
                raise OSError("Controlled loss AFTER actual selected replacement")
            return real_replace(source, target, **options)
        with patch("workspace_files.os.replace", lost_result):
            unknown = project_history.restore(self.f.manager, job["id"], request, RequestError)
        self.assertEqual("unknown", unknown["status"])
        self.assertEqual("owner baseline\n", (self.f.workspace / "source.txt").read_text())
        self.assertEqual(unknown, project_history.restore(self.f.manager, job["id"], request, RequestError))
        self.assertEqual(1, len(attempts))
        self.assertEqual("unknown", self.f.manager.files.operation(request["operationId"])["status"])

    def test_fixed_comparison_head_rejects_offset_without_its_exact_original_version(self):
        job = self.final()
        page = project_history.read(self.f.manager, job["id"], {"limit": "1"}, RequestError)
        self.assertTrue(page["hasMore"])
        for query in ({"offset": "1"}, {"offset": "1", "pageVersion": "f" * 64}):
            with self.assertRaises(RequestError):
                project_history.read(self.f.manager, job["id"], query, RequestError)
        next_page = project_history.read(self.f.manager, job["id"], {"offset": "1", "pageVersion": page["pageVersion"], "limit": "1"}, RequestError)
        self.assertNotEqual(page["files"][0]["path"], next_page["files"][0]["path"])
        self.assertEqual(page["pageVersion"], next_page["pageVersion"])

    def test_original_unknown_workspace_cannot_be_bypassed_by_new_project_baseline(self):
        job = self.final()
        with self.f.manager.lock:
            self.f.manager.jobs[job["id"]]["status"] = "unknown"
            self.f.manager._save(self.f.manager.jobs[job["id"]])
        starts = (self.f.protocol / "starts").read_text()
        blocked = self.final()
        self.assertEqual("blocked", blocked["status"], blocked)
        self.assertIn("unconfirmed work", blocked["message"])
        self.assertEqual(starts, (self.f.protocol / "starts").read_text())
        self.assertEqual("unknown", self.f.manager.get(job["id"])["status"])


if __name__ == "__main__":
    unittest.main()
