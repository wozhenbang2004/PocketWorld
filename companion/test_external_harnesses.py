"""Real bounded child-process fixtures for harness admission and original jobs.

These fixtures exercise transport and ownership; they do not claim a vendor
installation, authenticated model call, or operating-system sandbox.
"""
import json
import base64
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

import harness_registry
import harness_cli
import assignment_projects
import assignment_workspaces
from pocket_companion import JobManager, RequestError


PEER = r'''
import json,os,sys,uuid,subprocess
from pathlib import Path
flavor=os.environ.get('FIXTURE_PROTOCOL','acp')
mode=os.environ.get('FIXTURE_MODE','complete')
session='fixture-acp-session'
turn=None
prompt=None
selected='fixture-a'
mcp=None
team_token=''
def emit(v):
 print(json.dumps({'jsonrpc':'2.0',**v}),flush=True)
def notify(method,params): emit({'method':method,'params':params})
def answer(v,result): emit({'id':v['id'],'result':result})
def finished(status='completed'):
 if flavor=='msp':
  if status=='completed':
   notify('item/started',{'sessionId':session,'viewCursor':'cursor-2','item':{'itemId':'message-1','turnId':turn,'kind':'agentMessage','revision':1,'status':'inProgress','text':''}})
   notify('item/delta',{'sessionId':session,'viewCursor':'cursor-3','itemId':'message-1','delta':'fixture result'})
   notify('item/completed',{'sessionId':session,'viewCursor':'cursor-4','item':{'itemId':'message-1','turnId':turn,'kind':'agentMessage','revision':2,'status':'completed','text':'fixture result'}})
  notify('turn/completed',{'sessionId':session,'viewCursor':'cursor-5','turnId':turn,'terminal':status,'sourceRange':{}})
 else:
  if status=='completed':
   notify('session/update',{'sessionId':'foreign-session' if mode=='wrong-session' else session,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'fixture result'+(' '+team_token if mode=='echo-team-token' else '')}}})
  emit({'id':prompt,'result':{'stopReason':'end_turn' if status=='completed' else 'cancelled','id':'cannot-replace-original-job'}})
def request_approval():
 if flavor=='msp':
  params={'sessionId':session,'turnId':turn,'approvalId':'approval-1','toolCallId':'tool-1','toolName':'write_file','rawArgs':'{"path":"result.txt"}',
   'subject':{'kind':'fileAccess','path':str(Path.cwd()/'result.txt'),'access':'write'},'currentRequirementId':{'approvalId':'approval-1','sourceIndex':0},
   'availableChoices':[{'choiceId':'native-once','label':'Once','decision':'approved','scope':'once'},{'choiceId':'native-abort','label':'Reject','decision':'abort','scope':'once'}],
   'taskId':'task-1','itemId':'tool-item','judgeEscalated':False,'protectedWrite':False,'sourceRange':{},'viewCursor':'approval-cursor'}
  emit({'id':'peer-request','method':'approval/request','params':params})
 else:
  emit({'id':'peer-request','method':'session/request_permission','params':{'sessionId':session,
   'toolCall':{'toolCallId':'tool-1','title':'Write fixture result','kind':'edit','rawInput':{'path':'result.txt'}},
   'options':[{'optionId':'native-once','name':'Once','kind':'allow_once'},{'optionId':'native-no','name':'Reject','kind':'reject_once'}]}})
def connect_team(servers):
 global mcp,team_token
 if not servers:return
 server=servers[0] if isinstance(servers,list) else next(iter(servers.values()))
 env=dict(os.environ)
 extra=server.get('env',{})
 env.update({v['name']:v['value'] for v in extra} if isinstance(extra,list) else extra)
 team_token=env.get('POCKET_TEAM_TOKEN','')
 mcp=subprocess.Popen([server['command'],*server.get('args',[])],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=env,text=True)
 def call(v):
  mcp.stdin.write(json.dumps({'jsonrpc':'2.0',**v})+'\n');mcp.stdin.flush()
  return json.loads(mcp.stdout.readline())
 assert 'result' in call({'id':1,'method':'initialize','params':{'protocolVersion':'2024-11-05','clientInfo':{'name':'fixture','version':'1'},'capabilities':{}}})
 mcp.stdin.write(json.dumps({'jsonrpc':'2.0','method':'notifications/initialized'})+'\n');mcp.stdin.flush()
 tools=call({'id':2,'method':'tools/list','params':{}})
 assert any(x['name']=='team_list_members' for x in tools['result']['tools'])
 reply=call({'id':3,'method':'tools/call','params':{'name':'team_list_members','arguments':{}}})
 assert 'result' in reply
 mcp.terminate();mcp.wait(timeout=3)
for line in sys.stdin:
 v=json.loads(line); method=v.get('method'); p=v.get('params',{})
 with open(os.environ['FIXTURE_LOG'],'a') as stream: stream.write(json.dumps({'method':method,'id':v.get('id'),'cwd':str(Path.cwd()),'sessionId':p.get('sessionId'),'commandId':p.get('commandId'),'result':v.get('result')})+'\n')
 if method=='initialize':
  if flavor=='msp':
   answer(v,{'serverInfo':{'name':'fixture-muse','version':'1'},'schema':{'version':1,'fingerprint':'fixture-schema'},'grantedCapabilities':['sessionMcp'],'sessionDurability':'durable'})
  else:
   answer(v,{'protocolVersion':2 if mode=='wrong-version' else 1,'agentInfo':{'name':'fixture-acp','version':'1'},
    'agentCapabilities':{'loadSession':mode!='no-resume'},'authMethods':[{'id':'owner-auth','name':'Owner configured'}]})
 elif method=='authenticate':
  assert p['methodId']=='owner-auth';answer(v,{})
 elif method in ('session/new','session/load'):
  if method=='session/load':
   assert p['sessionId']==session
   notify('session/update',{'sessionId':session,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'old replay must not appear'}}})
  connect_team(p.get('mcpServers',[]))
  answer(v,{'sessionId':session,'models':{'currentModelId':selected,'availableModels':[{'modelId':'fixture-a','name':'A'},{'modelId':'fixture-b','name':'B'}]},
   'modes':{'currentModeId':'default','availableModes':[{'id':'default','name':'Default'},{'id':'plan','name':'Plan'}]}})
 elif method=='session/set_model':
  assert p['modelId'] in ('fixture-a','fixture-b');selected=p['modelId'];answer(v,{})
 elif method=='session/set_mode':answer(v,{})
 elif method=='model/list':
  answer(v,{'providerId':'fixture','profileId':None,'source':'fixture',
   'models':[{'modelId':'fixture-a','displayLabel':'A','providerId':'fixture','isActive':selected=='fixture-a','isDefault':True},
             {'modelId':'fixture-b','displayLabel':'B','providerId':'fixture','isActive':selected=='fixture-b','isDefault':False}]})
 elif method in ('session/start','session/resume','session/read'):
  if method=='session/start':session=str(uuid.uuid4())
  elif method=='session/resume':session=p['sessionId']
  connect_team(p.get('config',{}).get('mcpServers',{}))
  answer(v,{'session':{'sessionId':session,'status':'idle','activeTurnId':None,'workspaceRoot':str(Path.cwd()),'modelId':selected},'pendingRequests':[],'viewCursor':'cursor-0'})
 elif method=='session/setModel':
  selected=p['model']['modelId'];answer(v,{'commandId':p['commandId'],'status':'accepted'})
 elif method in ('session/prompt','turn/start'):
  prompt=v['id']
  if flavor=='msp':
   turn=str(uuid.uuid4())
   answer(v,{'commandId':p['commandId'],'status':'accepted','turnId':turn,'disposition':'started','startedNewTurn':True})
   notify('turn/started',{'sessionId':session,'turnId':turn,'commandId':p['commandId'],'viewCursor':'cursor-1'})
  if mode=='disconnect':sys.exit(0)
  if mode in ('approval','cancel'):request_approval()
  elif mode=='wait':pass
  else:
   (Path.cwd()/'result.txt').write_text('actual fixture artifact\n' if Path.cwd().name=='workspace' else 'isolated fixture artifact\n')
   finished()
 elif method=='approval/decide':
  assert p['choiceId']=='native-once' and p['requirementId']=={'approvalId':'approval-1','sourceIndex':0}
  answer(v,{'commandId':p['commandId'],'status':'accepted'})
  (Path.cwd()/'result.txt').write_text('approved fixture artifact\n');finished()
 elif v.get('id')=='peer-request' and flavor=='acp':
  if mode=='cancel':continue
  assert v['result']['outcome']=={'outcome':'selected','optionId':'native-once'}
  (Path.cwd()/'result.txt').write_text('approved fixture artifact\n');finished()
 elif method in ('session/cancel','turn/interrupt'):
  if flavor=='msp':answer(v,{'commandId':p['commandId'],'status':'accepted'})
  finished('cancelled')
'''


class HarnessTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.peer = self.root / "peer.py"
        self.peer.write_text(PEER)
        self.manager = None

    def tearDown(self):
        if self.manager:
            self.manager.close()
        self.directory.cleanup()

    def make(self, flavor="acp", mode="complete", auth=False):
        self.worker = "muse_msp" if flavor == "msp" else "acp_fixture"
        config = {"command": sys.executable, "args": [str(self.peer)],
            "env": {"FIXTURE_PROTOCOL": flavor, "FIXTURE_MODE": mode, "FIXTURE_LOG": str(self.root / "wire.jsonl")}}
        if auth:
            config["authMethod"] = "owner-auth"
        self.manager = JobManager({"stateDir": str(self.root / "state"), "environments": [{"id": "computer",
            "kind": "local", "root": str(self.workspace), "writeAllowed": True, "workers": [self.worker],
            "harnessWorkers": {self.worker: config}}]}, "fixture-owner-token-0123456789abcdef0123456789")

    def probe(self, **extra):
        return self.manager.probe_worker({"environmentId": "computer", "environmentBinding": self.manager.environment_binding("computer"),
            "worker": self.worker, **extra})

    def request(self, probe, **extra):
        return {"operationId": str(uuid.uuid4()), "environmentId": "computer", "environmentBinding": probe["environmentBinding"],
            "worker": self.worker, "harnessSelection": probe["harnessSelection"], "readOnly": False,
            "externalExecutionRiskAcknowledged": True, "prompt": "Create result.txt", "timeoutSeconds": 15, **extra}

    def wait(self, job, predicate=lambda j: j["status"] in {"completed", "failed", "cancelled", "unknown", "blocked"}, timeout=12):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            value = self.manager.get(job["id"])
            if predicate(value):
                return value
            time.sleep(.02)
        self.fail("original job did not reach expected state: " + json.dumps(value))

    def settled(self, job):
        return self.wait(job, lambda j: j["status"] in {"completed", "failed", "cancelled", "unknown", "blocked"} and j.get("dispatchFenceFinal"))

    def wire(self):
        return [json.loads(line) for line in (self.root / "wire.jsonl").read_text().splitlines()]

    def test_acp_real_probe_model_run_resume_and_artifact(self):
        self.make(auth=True)
        observed = self.probe(model="fixture-b", modeId="plan")
        self.assertTrue(observed["available"], observed)
        self.assertTrue(observed["capabilities"]["supportsResume"])
        request = self.request(observed, model="fixture-b", modeId="plan")
        job = self.settled(self.manager.submit(request))
        self.assertEqual("completed", job["status"], job)
        self.assertEqual("fixture result", job["output"])
        self.assertEqual("fixture-b", job["reportedModel"])
        self.assertTrue(job["controls"]["threadResume"])
        self.assertEqual(b"actual fixture artifact\n", base64.b64decode(self.manager.artifact(job["id"], "result.txt")["base64"]))
        resumed = self.settled(self.manager.submit(self.request(observed, model="fixture-b", modeId="plan", threadId=job["threadId"], resumeJobId=job["id"])))
        self.assertEqual("completed", resumed["status"], resumed)
        self.assertEqual("fixture result", resumed["output"])
        self.assertEqual(2, sum(v["method"] == "session/prompt" for v in self.wire()))
        self.assertEqual(job["id"], self.manager.submit(request)["id"])

    def test_selection_change_and_ack_are_rejected_before_dispatch(self):
        self.make()
        probe = self.probe()
        for extra in ({"externalExecutionRiskAcknowledged": False}, {"readOnly": True}, {"model": "fixture-b"}, {"argv": ["sh"]}):
            with self.assertRaises(RequestError):
                self.manager.submit(self.request(probe, **extra))
        self.peer.write_text(PEER + "\n# owner changed source\n")
        with self.assertRaises(RequestError):
            self.manager.submit(self.request(probe))
        self.assertFalse(any(row["method"] == "session/prompt" for row in self.wire()))

    def test_malformed_protocol_and_model_are_not_available(self):
        self.make(mode="wrong-version")
        self.assertFalse(self.probe()["available"])

    def test_unadvertised_model_is_not_available(self):
        self.make()
        observed = self.probe(model="invented")
        self.assertFalse(observed["available"])
        self.assertTrue(observed["protocolVerified"])
        self.assertEqual("model_or_mode_unconfirmed", observed["reason"]["code"])

    def test_disconnect_is_unknown_and_never_replayed(self):
        self.make(mode="disconnect")
        probe = self.probe()
        request = self.request(probe)
        job = self.settled(self.manager.submit(request))
        self.assertEqual("unknown", job["status"])
        self.assertFalse(job["controls"]["threadResume"])
        self.assertEqual(job["id"], self.manager.submit(request)["id"])
        self.assertEqual(1, sum(row["method"] == "session/prompt" for row in self.wire()))

    def approval_flow(self, flavor):
        self.make(flavor, "approval")
        probe = self.probe()
        self.assertTrue(probe["available"], probe)
        job = self.manager.submit(self.request(probe))
        pending = self.wait(job, lambda value: bool(value.get("pendingApprovals")))
        item = pending["pendingApprovals"][0]
        request = {"requestId": item["requestId"], "requestHash": item["requestHash"], "decision": "accept",
            "jobOperationId": job["operationId"], "sessionId": pending["sessionId"], "promptRequestId": pending["promptRequestId"],
            "harnessSelection": pending["harnessSelection"]}
        with self.assertRaises(RequestError):
            self.manager.approve(job["id"], {**request, "requestHash": "wrong"})
        self.manager.approve(job["id"], request)
        result = self.settled(job)
        self.assertEqual("completed", result["status"], result)
        self.assertEqual("sent", result["approvalReceipts"][0]["status"])

    def test_acp_exact_permission(self):
        self.approval_flow("acp")

    def test_msp_exact_permission(self):
        self.approval_flow("msp")

    def test_msp_acceptance_deltas_and_terminal_are_distinct(self):
        self.make("msp")
        probe = self.probe(model="fixture-b")
        self.assertTrue(probe["available"], probe)
        self.assertFalse(probe["sessionAccessVerified"])
        result = self.settled(self.manager.submit(self.request(probe, model="fixture-b")))
        self.assertEqual("completed", result["status"], result)
        self.assertEqual("fixture result", result["output"])
        self.assertTrue(result["promptTerminalConfirmed"])
        self.assertEqual("muse_msp_v1", result["protocol"])

    def test_cancel_keeps_original_job_and_terminal(self):
        self.make(mode="cancel")
        job = self.manager.submit(self.request(self.probe()))
        self.wait(job, lambda value: bool(value.get("pendingApprovals")))
        self.manager.cancel_original(job["id"], {"jobOperationId": job["operationId"]})
        result = self.settled(job)
        self.assertEqual("cancelled", result["status"], result)
        self.assertTrue(result["processCleanupProof"]["processGroupStoppedVerified"])

    def test_team_mcp_uses_original_job_and_owner_reply(self):
        self.make(mode="echo-team-token")
        context = {"protocol": "pocketworld.team-tools.v1", "hostTaskId": "host", "runId": "run", "memberId": "coder",
            "turnId": "original-turn", "round": 1, "generation": 0, "runGeneration": 0,
            "roster": [{"id": "coder", "name": "Coder"}, {"id": "reviewer", "name": "Reviewer"}]}
        job = self.manager.submit(self.request(self.probe(), teamContext=context))
        pending = self.wait(job, lambda value: bool(value.get("pendingTeamTools")))
        original_token = self.manager.team_harness.handles[job["id"]].token
        tool = pending["pendingTeamTools"][0]
        self.assertEqual("team_list_members", tool["name"])
        response = {"jobOperationId": job["operationId"], "result": {"ok": True, "members": context["roster"]}}
        self.manager.team_harness.respond(job["id"], tool["requestId"], response, RequestError)
        result = self.settled(job)
        self.assertEqual("completed", result["status"], result)
        self.assertEqual("answered", result["teamToolReceipts"][0]["status"])
        self.assertTrue(result["teamTools"])
        self.assertNotIn("POCKET_TEAM_TOKEN", json.dumps(result))
        self.assertNotIn(original_token, json.dumps(result))
        self.assertIn("[team credential hidden]", result["output"])
        self.assertNotIn(original_token, (self.manager.state / ("job-" + job["id"] + ".json")).read_text())
        self.assertNotIn(original_token, json.dumps(self.manager.jobs[job["id"]]))

    def test_native_claude_team_ack_enters_original_manager_and_keeps_private_launch(self):
        from test_claude_driver import FIXTURE
        self.make()
        binary = self.root / "claude"
        binary.write_text("#!" + sys.executable + "\nimport sys\nsys.argv.insert(1,'team-success')\n" + FIXTURE)
        binary.chmod(0o700)
        self.manager.environments["computer"]["workers"] = ["claude"]
        context = {"protocol": "pocketworld.team-tools.v1", "hostTaskId": "host", "runId": "run", "memberId": "coder",
            "turnId": "native-turn", "round": 1, "generation": 0, "runGeneration": 0,
            "roster": [{"id": "coder", "name": "Coder"}]}
        request = {"operationId": "native-team", "environmentId": "computer", "worker": "claude", "prompt": "Inspect",
            "externalExecutionRiskAcknowledged": True, "teamReadOnlyTools": True, "teamContext": context}
        with patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ.get("PATH", "")}):
            result = self.settled(self.manager.submit(request))
        self.assertEqual("completed", result["status"], result)
        self.assertTrue(result["teamTools"])
        self.assertTrue(result["teamReadOnlyTools"])
        self.assertNotIn("POCKET_TEAM_TOKEN", json.dumps(self.manager.jobs[result["id"]]))
        request.update(operationId="unscoped-ack")
        request.pop("teamContext")
        with self.assertRaises(RequestError):
            self.manager.submit(request)
        self.assertEqual(1, len(self.manager.jobs))

    def test_native_codex_resume_cannot_attach_team_tools_to_untooled_session(self):
        from test_codex_driver import FIXTURE
        self.make()
        binary = self.root / "codex"
        binary.write_text("#!" + sys.executable + "\nimport sys\nsys.argv.insert(1,'success')\n" + FIXTURE)
        binary.chmod(0o700)
        self.manager.environments["computer"]["workers"] = ["codex_app_server"]
        request = {"operationId": "native-source", "environmentId": "computer", "worker": "codex_app_server", "prompt": "Inspect", "readOnly": True}
        with patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ.get("PATH", "")}):
            job = self.manager.submit(request)
            pending = self.wait(job, lambda value: bool(value.get("pendingApprovals")))
            self.manager.approve(job["id"], {"requestId": pending["pendingApprovals"][0]["requestId"], "decision": "accept"})
            result = self.settled(job)
            self.assertEqual("completed", result["status"], result)
            self.assertFalse(result["teamTools"])
            request.update(operationId="native-resume", threadId=result["threadId"], resumeJobId=result["id"],
                teamContext={"protocol": "pocketworld.team-tools.v1", "hostTaskId": "host", "runId": "run", "memberId": "coder",
                    "turnId": "native-turn", "round": 2, "generation": 0, "runGeneration": 0,
                    "roster": [{"id": "coder", "name": "Coder"}]})
            with self.assertRaisesRegex(RequestError, "persisted team tools"):
                self.manager.submit(request)
        self.assertEqual(1, len(self.manager.jobs))

    def test_registry_covers_installed_presets_without_package_installation(self):
        self.assertEqual(40, len(harness_registry.PRESETS))
        self.assertFalse(any(v["command"] in {"npx", "uvx"} for v in harness_registry.PRESETS.values()))
        self.assertFalse(harness_registry.external("gemini_acp"))
        self.assertFalse(harness_registry.production_worker("pi"))
        with self.assertRaises(ValueError):
            harness_registry.validate_environment({"workers": ["acp_unknown"]})

    def test_owner_discovery_enablement_and_exact_config_replacement(self):
        config = {"environments": [{"id": "computer", "root": str(self.workspace), "workers": ["command"], "writeAllowed": False}]}
        discovered = harness_cli.discover(config, "computer")
        self.assertFalse(discovered["executablesStarted"])
        self.assertEqual(40, len(discovered["harnesses"]))
        with self.assertRaises(ValueError):
            harness_cli.enable(config, "computer", "acp_gemini")
        enabled = harness_cli.enable(config, "computer", "acp_fixture", {"command": sys.executable, "args": [str(self.peer)]}, True)
        self.assertTrue(enabled["environments"][0]["writeAllowed"])
        self.assertIn("acp_fixture", enabled["environments"][0]["workers"])
        target = self.root / "config.json"
        raw = json.dumps(config).encode()
        target.write_bytes(raw)
        harness_cli.write_configuration(target, raw, enabled)
        self.assertEqual(enabled, json.loads(target.read_bytes()))
        with self.assertRaises(ValueError):
            harness_cli.write_configuration(target, raw, config)

    def native_assignment(self, full=False):
        result = {key: str(uuid.uuid4()) for key in ("taskId", "runId", "parentTaskId", "parentRunId", "teamRunId", "assignmentId", "attemptId")}
        result.update(coordinatorEpoch=1, parentEpoch=1, teamGeneration=0, assignmentRevision=1, memberId="coder", memberGeneration=0)
        if full:
            result.update(workItemId=str(uuid.uuid4()), itemRevision=1, ownerGeneration=0)
        return result

    def test_generic_selected_copy_actual_artifact_and_original_is_unchanged(self):
        self.make()
        observed = self.probe()
        source = self.settled(self.manager.submit(self.request(observed)))
        snapshot = self.manager.capture_result_snapshot(source["id"], {"operationId": str(uuid.uuid4()), "jobOperationId": source["operationId"],
            "environmentId": "computer", "worker": self.worker, "paths": ["result.txt"]})
        preparation = {"operationId": str(uuid.uuid4()), "nativeAssignment": self.native_assignment(),
            "snapshotId": snapshot["snapshotId"], "snapshotVersion": snapshot["snapshotVersion"],
            "files": [{"path": "result.txt", "baseHash": snapshot["files"][0]["sha256"], "baseVersion": 1}],
            "environmentBinding": observed["environmentBinding"], "limits": {"maxWallTimeSeconds": 10, "maxOutputBytes": 8192, "maxContributionBytes": 8192},
            "approvalRevision": 1, "approvalExpiresAt": self.manager.now() + 300000}
        copy = assignment_workspaces.prepare(self.manager, source["id"], preparation, RequestError)
        reference = {k: copy[k] for k in ("checkoutId", "checkoutVersion", "nativeAssignment")}
        reference.update(sourceJobId=source["id"], preparationNativeAssignment=reference["nativeAssignment"], reservationId=str(uuid.uuid4()),
            approvalRevision=2, approvalExpiresAt=self.manager.now() + 300000)
        job = self.settled(self.manager.submit(self.request(observed, assignmentWorkspace=reference, timeoutSeconds=10)))
        self.assertEqual("completed", job["status"], job)
        self.assertTrue(job["assignmentWorkspaceConfirmed"])
        self.assertFalse(job["assignmentWriteSandboxVerified"])
        self.assertEqual("owner_managed", job["executionPolicy"])
        self.assertEqual("actual fixture artifact\n", (self.workspace / "result.txt").read_text())
        self.assertEqual("isolated fixture artifact\n", (Path(copy["workspace"]) / "result.txt").read_text())

    def test_msp_full_project_copy_and_frozen_hashes_use_original_job(self):
        self.make("msp")
        observed = self.probe()
        self.assertTrue(observed["capabilities"]["projectProduction"])
        raw = b"original package bytes\n"
        sha = hashlib.sha256(raw).hexdigest()
        manifest = {"format": "pocketworld.project-package.v1", "mode": "source_project",
            "project": {"id": str(uuid.uuid4()), "name": "fixture"},
            "source": {"projectId": str(uuid.uuid4()), "checkoutId": str(uuid.uuid4()), "sourceDigest": "a" * 64},
            "entryPath": "", "previewRoot": "", "directories": [],
            "files": [{"path": "result.txt", "bytes": len(raw), "sha256": sha, "executable": False, "kind": "file", "mimeType": "text/plain"}],
            "bytes": len(raw), "omitted": [], "includesHistory": False, "includesToolEnvironment": False, "atomicSourceFence": False,
            "policy": {"version": 1, "privateFilesExcluded": True, "generatedRootsIncluded": False}}
        identity = assignment_projects.manifest_identity(manifest)
        request = {"operationId": str(uuid.uuid4()), "nativeAssignment": self.native_assignment(True),
            "environmentId": "computer", "environmentBinding": observed["environmentBinding"],
            "packageIdentityEncoding": assignment_projects.MANIFEST_ENCODING, "packageIdentity": identity, "packageManifest": manifest,
            "packageReference": {"id": str(uuid.uuid4()), "sha256": "b" * 64, "manifestIdentity": identity},
            "files": [{"path": "result.txt", "baseHash": sha, "baseVersion": 1}],
            "limits": {"maxWallTimeSeconds": 10, "maxOutputBytes": 8192, "maxContributionBytes": 1_048_576},
            "commandScope": {key: True for key in ("allowCommands", "sameUserExecutionRiskAcknowledged", "privateSourceTransferAcknowledged", "internalInferenceBillingUnboundedAcknowledged")},
            "approvalRevision": 1, "approvalExpiresAt": self.manager.now() + 300000}
        prepared = assignment_projects.prepare(self.manager, request, RequestError)
        assignment_projects.upload(self.manager, request["operationId"], 0, len(raw), io.BytesIO(raw), RequestError)
        prepared = assignment_projects.finalize(self.manager, request["operationId"], {"finalizeOperationId": str(uuid.uuid4()), "packageIdentity": identity}, RequestError)
        reference = {k: prepared[k] for k in ("mode", "prepareOperationId", "checkoutId", "checkoutVersion", "nativeAssignment")}
        reference.update(preparationNativeAssignment=reference["nativeAssignment"], reservationId=str(uuid.uuid4()),
            approvalRevision=2, approvalExpiresAt=self.manager.now() + 300000)
        submitted = self.request(observed, assignmentWorkspace=reference, timeoutSeconds=10)
        job = self.settled(self.manager.submit(submitted))
        self.assertEqual("completed", job["status"], job)
        frozen = assignment_projects.freeze(self.manager, job["id"], {"operationId": str(uuid.uuid4()), "jobOperationId": job["operationId"],
            "prepareOperationId": prepared["prepareOperationId"], "checkoutVersion": prepared["checkoutVersion"]}, RequestError)
        self.assertEqual(hashlib.sha256(b"isolated fixture artifact\n").hexdigest(), frozen["files"][0]["sha256"])
        self.assertFalse((self.workspace / "result.txt").exists())
        self.assertEqual(job["id"], self.manager.submit(submitted)["id"])
        self.assertEqual(1, len(self.manager.jobs))


if __name__ == "__main__":
    unittest.main()
