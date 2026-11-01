"""Production server/copies/process limits with a deliberately fake Codex pipe, never live accounts.
Native approval/reservation envelopes here are protocol fixtures, not evidence of TaskStore admission.
"""
import base64
import json
import os
from pathlib import Path
import sys
import time
import unittest
import uuid
from unittest.mock import patch
import assignment_workspaces
from pocket_companion import JobManager
import test_companion as existing

CODING_PIPE = r'''
import json,os,signal,subprocess,sys
from pathlib import Path
mode=sys.argv[1]
def emit(value): print(json.dumps(value),flush=True)
for line in sys.stdin:
 value=json.loads(line);method=value.get('method');params=value.get('params',{})
 if method=='initialize':
  if mode=='walltime':
   child=subprocess.Popen([sys.executable,'-c','import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(60)'])
   Path('child-pid').write_text(str(child.pid));signal.signal(signal.SIGTERM,signal.SIG_IGN)
   import time;time.sleep(60)
  emit({'id':value['id'],'result':{'userAgent':'explicit-assignment-protocol-fixture'}})
 elif method=='turn/steer': emit({'id':value['id'],'error':{'code':-32600,'message':'thread not found: '+params['threadId']}})
 elif method=='config/read': emit({'id':value['id'],'result':{'config':{'mcp_servers':{'fixture':{'enabled':True}}}}})
 elif method=='account/read': emit({'id':value['id'],'result':{'requiresOpenaiAuth':False}})
 elif method=='model/list': emit({'id':value['id'],'result':{'data':[{'model':'fixture-model','isDefault':True}]}})
 elif method=='thread/start':
  assert params['sandbox']=='workspaceWrite'
  assert params['config']['mcp_servers']['fixture']['enabled'] is False
  assert params['config']['sandbox_workspace_write']['network_access'] is False
  sandbox={'type':'workspaceWrite','writableRoots':[os.getcwd()],'networkAccess':False,'excludeTmpdirEnvVar':True,'excludeSlashTmp':True}
  if mode=='wrong-policy': sandbox['writableRoots']=['/not-this-checkout']
  emit({'id':value['id'],'result':{'thread':{'id':'assignment-thread'},'cwd':os.getcwd(),'sandbox':sandbox}})
 elif method=='mcpServerStatus/list':emit({'id':value['id'],'result':{'data':[]}})
 elif method=='turn/start':
  assert params['sandboxPolicy']['writableRoots']==[os.getcwd()]
  assert params['sandboxPolicy']['excludeSlashTmp'] is True
  Path('turn-started').write_text('one original turn')
  emit({'id':value['id'],'result':{'turn':{'id':'assignment-turn'}}})
  emit({'method':'turn/started','params':{'threadId':'assignment-thread','turn':{'id':'assignment-turn'}}})
  if mode=='overflow': emit({'method':'item/agentMessage/delta','params':{'threadId':'assignment-thread','turnId':'assignment-turn','delta':'中'*4096}});continue
  emit({'method':'item/started','params':{'threadId':'assignment-thread','turnId':'assignment-turn','item':{'type':'fileChange','id':'file-item','changes':[{'path':str(Path.cwd()/'proof.txt'),'kind':{'type':'update','move_path':None},'diff':'-ORIGINAL SOURCE BYTES\\n+REAL BYTES CHANGED ONLY IN THIS COPY\\n'}],'status':'inProgress'}}})
  if mode=='command-approval': emit({'id':42,'method':'item/commandExecution/requestApproval','params':{'threadId':'assignment-thread','turnId':'assignment-turn','itemId':'command-item','command':'touch outside','cwd':os.getcwd(),'grantRoot':None}})
  else: emit({'id':42,'method':'item/fileChange/requestApproval','params':{'threadId':'assignment-thread','turnId':'assignment-turn','itemId':'file-item','grantRoot':os.getcwd() if mode=='grant-root' else None}})
 elif value.get('id')==42 and 'result' in value:
  if value['result']['decision']=='decline':
   emit({'method':'turn/completed','params':{'threadId':'assignment-thread','turn':{'id':'assignment-turn','status':'interrupted'}}});continue
  assert value['result']['decision']=='accept'
  Path('proof.txt').write_text('REAL BYTES CHANGED ONLY IN THIS COPY\n')
  emit({'method':'item/agentMessage/delta','params':{'threadId':'assignment-thread','turnId':'assignment-turn','delta':'actual selected-file copy changed; not a semantic quality claim'}})
  emit({'method':'turn/completed','params':{'threadId':'assignment-thread','turn':{'id':'assignment-turn','status':'completed'}}})
 elif method=='turn/interrupt':
  emit({'id':value['id'],'result':{}});emit({'method':'turn/completed','params':{'threadId':'assignment-thread','turn':{'id':'assignment-turn','status':'interrupted'}}})
'''


class AssignmentWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.f=existing.CompanionTests();self.f.setUp()
        self.script=self.f.root/'assignment-pipe.py';self.script.write_text(CODING_PIPE)
        source=self.f.submit("from pathlib import Path;Path('proof.txt').write_text('ORIGINAL SOURCE BYTES\\n')")
        self.source=self.f.finish(source['id'])
        until=time.monotonic()+2
        while self.f.manager.resources and time.monotonic()<until:time.sleep(.005)
        self.f.manager.environments['computer']['workers']=['command','codex_app_server']
        status,result=self.f.request('/v1/jobs/'+source['id']+'/snapshots',{'operationId':str(uuid.uuid4()),'jobOperationId':source['operationId'],'environmentId':'computer','worker':'command','paths':['proof.txt']})
        self.assertEqual(200,status,result);self.snapshot=result['result']

    def tearDown(self):self.f.tearDown()

    def preparation(self):
        native={key:str(uuid.uuid4()) for key in ['taskId','runId','parentTaskId','parentRunId','teamRunId','assignmentId','attemptId']}
        native.update(coordinatorEpoch=1,parentEpoch=1,teamGeneration=1,assignmentRevision=1,memberId='member-one',memberGeneration=1)
        return {'operationId':str(uuid.uuid4()),'nativeAssignment':native,'snapshotId':self.snapshot['snapshotId'],'snapshotVersion':self.snapshot['snapshotVersion'],
                'files':[{'path':'proof.txt','baseHash':self.snapshot['files'][0]['sha256'],'baseVersion':7}],
                'environmentBinding':self.f.manager.environment_binding('computer'),'limits':{'maxWallTimeSeconds':10,'maxOutputBytes':1024,'maxContributionBytes':8192},'approvalRevision':1,'approvalExpiresAt':self.f.manager.now()+300000}

    def prepare(self, request=None):
        request=request or self.preparation();status,result=self.f.request('/v1/jobs/'+self.source['id']+'/assignment-workspaces',request)
        self.assertEqual(200,status,result);return result['result'],request

    def submit(self, prepared, mode='write'):
        reference={key:prepared[key] for key in ['checkoutId','checkoutVersion','nativeAssignment']}
        reference.update(preparationNativeAssignment=reference['nativeAssignment'],sourceJobId=self.source['id'],reservationId=str(uuid.uuid4()),approvalRevision=2,approvalExpiresAt=self.f.manager.now()+300000)
        body={'operationId':str(uuid.uuid4()),'environmentId':'computer','environmentBinding':self.f.manager.environment_binding('computer'),'worker':'codex_app_server','readOnly':False,'prompt':'Change only the explicitly selected copied file','timeoutSeconds':10,'assignmentWorkspace':reference}
        with patch.object(self.f.manager,'_launch',return_value=([sys.executable,str(self.script),mode],prepared['workspace'])):
            status,result=self.f.request('/v1/jobs',body)
        self.assertEqual(200,status,result);return result['result'],body

    def terminal(self, job, seconds=5):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            actual=self.f.manager.get(job['id'])
            if actual.get('dispatchFenceFinal') and actual['status'] in {'completed','cancelled','failed','blocked','unknown'}:return actual
            time.sleep(.01)
        self.fail(str(self.f.manager.get(job['id'])))

    def approve(self, job):
        until=time.monotonic()+4
        while time.monotonic()<until:
            current=self.f.manager.get(job['id'])
            if current.get('pendingApprovals'):
                request=current['pendingApprovals'][0];self.assertTrue(request['canAccept'])
                status,result=self.f.request('/v1/jobs/'+job['id']+'/approval',{'requestId':request['requestId'],'decision':'accept'})
                self.assertEqual(200,status,result);return
            time.sleep(.01)
        self.fail(str(current))

    def test_two_real_independent_copies_one_original_job_approval_and_actual_base_diff_contribution(self):
        first,_=self.prepare();second,_=self.prepare()
        self.assertNotEqual(first['checkoutIdentity'],second['checkoutIdentity'])
        self.assertNotEqual((Path(first['workspace'])/'proof.txt').stat().st_ino,(self.f.workspace/'proof.txt').stat().st_ino)
        job,_=self.submit(first);self.approve(job);actual=self.terminal(job)
        self.assertEqual('completed',actual['status']);self.assertTrue(actual['assignmentWriteSandboxVerified']);self.assertTrue(actual['externalToolsDisabled']);self.assertFalse(actual['readOnlyConfirmed']);self.assertTrue(actual['processCleanupProof']['processGroupStoppedVerified'])
        self.assertIsNone(actual['inferenceUsage']['modelCalls']);self.assertEqual('unknown',actual['inferenceUsage']['status']);self.assertFalse(actual['tokenHardCapSupported'])
        self.assertEqual('ORIGINAL SOURCE BYTES\n',(self.f.workspace/'proof.txt').read_text());self.assertEqual('ORIGINAL SOURCE BYTES\n',(Path(second['workspace'])/'proof.txt').read_text())
        body={'operationId':str(uuid.uuid4()),'jobOperationId':job['operationId'],'environmentId':'computer','worker':'codex_app_server','threadId':actual['threadId'],'turnId':actual['turnId'],'paths':['proof.txt']}
        status,result=self.f.request('/v1/jobs/'+job['id']+'/snapshots',body);self.assertEqual(200,status,result)
        contribution=result['result']['assignmentContribution'];self.assertEqual(first['checkoutId'],contribution['checkoutId']);self.assertEqual(self.snapshot['snapshotVersion'],contribution['baseSnapshotVersion']);self.assertIn('-ORIGINAL SOURCE BYTES',contribution['files'][0]['diff']);self.assertIn('+REAL BYTES CHANGED',contribution['files'][0]['diff']);self.assertFalse(contribution['integrated']);self.assertFalse(contribution['independentlyReviewed']);self.assertFalse(contribution['osReadIsolationVerified'])
        self.assertNotIn('-ORIGINAL SOURCE BYTES',str(self.f.manager.list_jobs()))
        original=self.f.manager.result_snapshot(job['id'],result['result']['snapshotId'],result['result']['snapshotVersion'])
        self.assertIn('-ORIGINAL SOURCE BYTES',original['assignmentContribution']['files'][0]['diff'])

    def test_prepare_idempotency_restart_readback_never_recopies_and_old_environment_cannot_dispatch(self):
        prepared,request=self.prepare();status,again=self.f.request('/v1/jobs/'+self.source['id']+'/assignment-workspaces',request);self.assertEqual(200,status,again);self.assertEqual(prepared['checkoutId'],again['result']['checkoutId'])
        record=self.f.manager.jobs[self.source['id']]['assignmentWorkspaces'][0];record['status']='preparing';record.pop('checkoutIdentity');record.pop('checkoutVersion');self.f.manager._save(self.f.manager.jobs[self.source['id']])
        self.f.manager.close();self.f.manager=JobManager(self.f.config,existing.TOKEN);self.f.server.manager=self.f.manager
        status,result=self.f.request('/v1/jobs/'+self.source['id']+'/assignment-operations/'+request['operationId']);self.assertEqual(200,status,result);self.assertEqual('ready',result['result']['status']);self.assertEqual(prepared['checkoutVersion'],result['result']['checkoutVersion'])
        changed=json.loads(json.dumps(request));changed['operationId']=str(uuid.uuid4());changed['environmentBinding']=self.f.manager.environment_binding('computer');self.assertEqual(409,self.f.request('/v1/jobs/'+self.source['id']+'/assignment-workspaces',changed)[0])
        self.assertEqual(1,len(self.f.manager.jobs[self.source['id']]['assignmentWorkspaces']))

    def test_base_scope_and_hard_money_or_token_cap_refuse_before_new_copy(self):
        for field in ['base','hardTokenLimit','hardCostLimit']:
            request=self.preparation()
            if field=='base':request['files'][0]['baseHash']='0'*64
            else:request['limits'][field]=1
            status,result=self.f.request('/v1/jobs/'+self.source['id']+'/assignment-workspaces',request);self.assertEqual(409,status,result)
        self.assertFalse((self.f.manager.state/'assignment-workspaces').exists());self.assertEqual([],self.f.manager.jobs[self.source['id']].get('assignmentWorkspaces',[]))

    def test_mismatching_reported_coding_sandbox_is_blocked_before_any_model_turn(self):
        prepared,_=self.prepare();job,_=self.submit(prepared,'wrong-policy');actual=self.terminal(job)
        self.assertEqual('blocked',actual['status']);self.assertFalse(actual['assignmentWriteSandboxVerified']);self.assertFalse((Path(prepared['workspace'])/'turn-started').exists());self.assertTrue(actual['processStarted']);self.assertFalse(actual['notStartedConfirmed'])

    def test_real_output_bytes_cap_stops_original_job_without_fabricating_usage(self):
        prepared,_=self.prepare();job,_=self.submit(prepared,'overflow');actual=self.terminal(job)
        self.assertEqual('unknown',actual['status']);self.assertTrue(actual['outputLimitExceeded']);self.assertLessEqual(len(actual['output'].encode())+len(actual.get('diff','').encode()),1024);self.assertTrue(actual['processCleanupProof']['processGroupStoppedVerified']);self.assertIsNone(actual['inferenceUsage']['cost'])

    def test_command_escalation_and_session_grant_root_cannot_inherit_selected_file_scope(self):
        for mode in ['command-approval','grant-root']:
            with self.subTest(mode=mode):
                prepared,_=self.prepare();job,_=self.submit(prepared,mode)
                until=time.monotonic()+3
                while time.monotonic()<until:
                    actual=self.f.manager.get(job['id'])
                    if actual.get('pendingApprovals'):break
                    time.sleep(.01)
                request=actual['pendingApprovals'][0];self.assertFalse(request['canAccept'])
                status,_=self.f.request('/v1/jobs/'+job['id']+'/approval',{'requestId':request['requestId'],'decision':'accept'});self.assertEqual(409,status)
                status,_=self.f.request('/v1/jobs/'+job['id']+'/approval',{'requestId':request['requestId'],'decision':'decline'});self.assertEqual(200,status)
                self.terminal(job);self.assertEqual('ORIGINAL SOURCE BYTES\n',(Path(prepared['workspace'])/'proof.txt').read_text())

    def test_real_walltime_watchdog_reaps_ignoring_leader_and_child_process_group(self):
        prepared,_=self.prepare();start=time.monotonic();job,_=self.submit(prepared,'walltime');actual=self.terminal(job,seconds=14)
        self.assertLess(time.monotonic()-start,13);self.assertEqual('cancelled',actual['status']);self.assertTrue(actual['wallTimeExceeded']);self.assertTrue(actual['processCleanupProof']['processGroupKillAttempted']);self.assertTrue(actual['processCleanupProof']['leaderReaped']);self.assertTrue(actual['processCleanupProof']['processGroupStoppedVerified']);self.assertFalse(actual['notStartedConfirmed']);self.assertIsNone(actual['inferenceUsage']['inputTokens'])
