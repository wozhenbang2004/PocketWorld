"""Production drivers/server + deliberate local protocol peers, NOT real model/CLI/device acceptance."""
import base64
import concurrent.futures
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from unittest.mock import patch

from codex_driver import CodexAppServerDriver, DriverError
from claude_driver import ClaudeStreamDriver, command
from remote_interactions import Progress, QuestionMailbox, question, answers_for
from pocket_companion import JobManager
import test_companion as server_fixture


CODEX = r'''
import json,sys
from pathlib import Path
mode=sys.argv[1]
turn='turn-1'
def emit(x): print(json.dumps(x),flush=True)
for line in sys.stdin:
    x=json.loads(line);m=x.get('method');p=x.get('params',{})
    if m=='initialize': emit({'id':x['id'],'result':{'userAgent':'question-fixture'}})
    elif m=='turn/steer': emit({'id':x['id'],'error':{'code':-32600,'message':'thread not found: '+p['threadId']}})
    elif m=='config/read': emit({'id':x['id'],'result':{'config':{'mcp_servers':{}}}})
    elif m in {'thread/start','thread/resume'}: emit({'id':x['id'],'result':{'thread':{'id':'question-thread'},'sandbox':{'type':'workspaceWrite'},'cwd':str(Path.cwd())}})
    elif m=='turn/start':
        count=Path('interaction-turn-count');n=len(count.read_text())+1 if count.exists() else 1;count.write_text('1'*n);turn='turn-'+str(n)
        emit({'id':x['id'],'result':{'turn':{'id':turn}}})
        emit({'method':'turn/started','params':{'threadId':'question-thread','turn':{'id':turn}}})
        emit({'method':'item/started','params':{'threadId':'question-thread','turnId':turn,'item':{'id':'tool-1','type':'commandExecution','command':'fixture reads selected source','status':'inProgress'}}})
        emit({'method':'item/commandExecution/outputDelta','params':{'threadId':'question-thread','turnId':turn,'itemId':'tool-1','delta':'read original source\n'}})
        emit({'method':'turn/plan/updated','params':{'threadId':'question-thread','turnId':turn,'plan':[{'step':'Use the owner choice','status':'inProgress'}]}})
        emit({'id':'wire-question','method':'item/tool/requestUserInput','params':{'threadId':'question-thread','turnId':turn,'itemId':'question-item','isBlocking':True,'autoResolutionMs':None,'questions':[{'id':'style','header':'Style','question':'Choose result style','isSecret':mode=='secret','isOther':True,'options':[{'label':'Brief','description':'Short output'},{'label':'Detailed','description':'Full output'}]}]}})
    elif x.get('id')=='wire-question' and 'result' in x:
        assert x['result']=={'answers':{'style':{'answers':['Brief']}}}
        count=Path('interaction-answer-count');count.write_text(count.read_text()+'1' if count.exists() else '1')
        Path('result.txt').write_text('ACTUAL FROZEN BYTES: Brief')
        emit({'method':'serverRequest/resolved','params':{'threadId':'question-thread','turnId':turn,'requestId':'wire-question'}})
        emit({'method':'item/completed','params':{'threadId':'question-thread','turnId':turn,'item':{'id':'tool-1','type':'commandExecution','status':'completed','exitCode':0}}})
        emit({'method':'item/agentMessage/delta','params':{'threadId':'question-thread','turnId':turn,'delta':'Question consumed; inspect result.txt'}})
        emit({'method':'turn/diff/updated','params':{'threadId':'question-thread','turnId':turn,'diff':'+ ACTUAL FROZEN BYTES: Brief'}})
        emit({'method':'turn/completed','params':{'threadId':'question-thread','turn':{'id':turn,'status':'completed'}}})
    elif m=='turn/interrupt':
        emit({'id':x['id'],'result':{}})
        emit({'method':'turn/completed','params':{'threadId':'question-thread','turn':{'id':turn,'status':'interrupted'}}})
'''

CLAUDE = r'''
import json,sys
from pathlib import Path
mode=sys.argv[1];args=sys.argv[2:]
if '--version' in args:
    print('2.1.316 (controlled question protocol fixture)');sys.exit()
if '--help' in args:
    print('--safe-mode --restricted --settings --input-format --output-format --verbose --include-partial-messages --permission-prompts --permission-prompt-tool --permission-mode --tools --allowedTools --disallowedTools --session-id --resume --max-turns --model');sys.exit()
session=args[args.index('--resume' if '--resume' in args else '--session-id')+1];tools=args[args.index('--tools')+1].split(',')
assert 'AskUserQuestion' in tools and args[args.index('--allowedTools')+1]=='Read,Glob,Grep'
assert json.loads(args[args.index('--settings')+1])['permissions']['ask']==['Edit','Write','Bash','AskUserQuestion']
def emit(x): print(json.dumps(x),flush=True)
for line in sys.stdin:
    x=json.loads(line)
    if x.get('type')=='control_request' and x['request']['subtype']=='initialize': emit({'type':'control_response','response':{'subtype':'success','request_id':x['request_id'],'response':{}}})
    elif x.get('type')=='user':
        prompt=x['uuid']
        emit({'type':'system','subtype':'init','session_id':session,'permissionMode':'default','tools':tools,'mcp_servers':[]})
        questions=[{'header':'Sections','question':'Which sections?','multiSelect':True,'options':[{'label':'A','description':'First'},{'label':'B','description':'Second'}]},{'header':'Name','question':'Name the result','multiSelect':False,'options':[{'label':'One','description':'First','preview':'<div>not rendered</div>'},{'label':'Two','description':'Second'}]}]
        emit({'type':'assistant','session_id':session,'user_message_uuid':prompt,'message':{'content':[{'type':'tool_use','id':'ask-tool','name':'AskUserQuestion','input':{'questions':questions}}]}})
        emit({'type':'control_request','request_id':'ask-wire','request':{'subtype':'can_use_tool','tool_name':'AskUserQuestion','tool_use_id':'ask-tool','input':{'questions':questions}}})
    elif x.get('type')=='control_response':
        response=x['response']['response'];assert response['behavior']=='allow' and 'updatedPermissions' not in response
        assert response['updatedInput']=={'questions':questions,'answers':{'Which sections?':['A','B'],'Name the result':'Custom name'}}
        Path('claude-question-answer').write_text('1')
        emit({'type':'user','session_id':session,'message':{'content':[{'type':'tool_result','tool_use_id':'ask-tool','content':'Owner choices returned'}]}})
        emit({'type':'result','session_id':session,'user_message_uuid':str(__import__('uuid').uuid4()) if mode=='wrong-prompt' else prompt,'is_error':False,'subtype':'success','result':'Actual fixture answered','permission_denials':[]})
'''


class InteractionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-interaction-');self.root=Path(self.temp.name)
        self.processes=[];self.drivers=[];self.pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def tearDown(self):
        for driver in self.drivers:
            if isinstance(driver,ClaudeStreamDriver):driver.close_steering()
        for process in self.processes:
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            for stream in (process.stdin,process.stdout,process.stderr):stream.close()
        self.pool.shutdown(wait=True);self.temp.cleanup()

    def driver(self,protocol,mode='normal',callback=None,stopped=None):
        path=self.root/(protocol+'.py');path.write_text(CODEX if protocol=='codex' else CLAUDE)
        session=str(uuid.uuid4());argv=[sys.executable,str(path),mode]+(command(session)[1:] if protocol=='claude' else [])
        process=subprocess.Popen(argv,cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0);self.processes.append(process)
        states=[]
        def state(value):
            states.append(value)
            if callback:callback(value)
        driver=CodexAppServerDriver(process,str(self.root),True,state,stopped or (lambda:False),5) if protocol=='codex' else ClaudeStreamDriver(process,session,state,stopped or (lambda:False),5,cwd=self.root)
        self.drivers.append(driver);return driver,states

    def pending(self,states):
        until=time.monotonic()+3
        while time.monotonic()<until:
            if states and states[-1].get('pendingQuestions'):return states[-1]['pendingQuestions'][0]
            time.sleep(.005)
        self.fail('No exact original question arrived')

    def test_codex_question_and_progress_answer_once_without_tool_permission(self):
        driver,states=self.driver('codex');future=self.pool.submit(driver.run,'Use the explicit choice');q=self.pending(states);answer=str(uuid.uuid4())
        with self.assertRaises(DriverError):driver.answer_question(q['requestId'],'0'*64,answer,{'style':['Brief']})
        driver.answer_question(q['requestId'],q['requestHash'],answer,{'style':['Brief']})
        driver.answer_question(q['requestId'],q['requestHash'],answer,{'style':['Brief']})
        actual=future.result(timeout=4);self.assertEqual('completed',actual['status']);self.assertEqual('1',(self.root/'interaction-answer-count').read_text())
        self.assertEqual('resolved',actual['questionReceipts'][0]['status']);self.assertFalse(actual['questionReceipts'][0]['businessCompleted'])
        self.assertTrue(any(v['kind']=='tool_output' for v in actual['progress']['events']));self.assertFalse(actual['progress']['liveStreaming'])
        self.assertEqual([],actual['approvalReceipts']);self.assertEqual('ACTUAL FROZEN BYTES: Brief',(self.root/'result.txt').read_text())

    def test_claude_preserves_original_multi_question_and_real_prompt_identity(self):
        driver,states=self.driver('claude');future=self.pool.submit(driver.run,'Choose');q=self.pending(states)
        self.assertTrue(q['previewOmitted']);self.assertFalse(q['grantsPermissions'])
        driver.answer_question(q['requestId'],q['requestHash'],str(uuid.uuid4()),{'q0':['A','B'],'q1':['Custom name']})
        actual=future.result(timeout=4);self.assertEqual('completed',actual['status']);self.assertEqual('exact_user_message_uuid',actual['promptResultIdentity'])
        self.assertEqual('resolved',actual['questionReceipts'][0]['status']);self.assertEqual([],actual['approvalReceipts'])

    def test_claude_wrong_prompt_result_cannot_complete_original_request(self):
        driver,states=self.driver('claude','wrong-prompt');future=self.pool.submit(driver.run,'Choose');q=self.pending(states)
        driver.answer_question(q['requestId'],q['requestHash'],str(uuid.uuid4()),{'q0':['A','B'],'q1':['Custom name']})
        with self.assertRaises(DriverError):future.result(timeout=4)

    def test_stop_after_persisted_sending_prevents_question_wire_write(self):
        stop=threading.Event()
        def state(value):
            if any(v.get('status')=='sending' for v in value.get('questionReceipts',[])):stop.set()
        driver,states=self.driver('codex',callback=state,stopped=stop.is_set);future=self.pool.submit(driver.run,'Choose');q=self.pending(states)
        driver.answer_question(q['requestId'],q['requestHash'],str(uuid.uuid4()),{'style':['Brief']})
        actual=future.result(timeout=4);self.assertEqual('cancelled',actual['status']);self.assertFalse((self.root/'interaction-answer-count').exists())
        self.assertEqual('rejected',actual['questionReceipts'][0]['status']);self.assertTrue(actual['questionReceipts'][0]['notDispatchedConfirmed'])

    def test_secret_and_unknown_options_refuse_without_reinterpreting_answer_as_grant(self):
        source={'id':'x','header':'Choice','question':'Choose','isOther':False,'isSecret':False,'options':[{'label':'A','description':'first'}]}
        q=question('codex_app_server',{'threadId':'thread','turnId':'turn'},[source])
        with self.assertRaises(ValueError):answers_for(q,{'x':['acceptForSession']})
        with self.assertRaises(ValueError):answers_for(q,{'x':['A'],'grantRoot':['/']})
        source['isSecret']=True;q=question('codex_app_server',{},[source]);self.assertFalse(q['canAnswer'])
        with self.assertRaises(ValueError):answers_for(q,{'x':['private']})
        progress=Progress('codex_app_server')
        for n in range(100):progress.add('tool_output','delta',str(n),text='text'*1000)
        view=progress.snapshot();self.assertGreater(view['droppedCount'],0);self.assertLessEqual(len(json.dumps(view['events']).encode()),24576);self.assertFalse(view['completeHistory'])


class OriginalServerInteractionTests(unittest.TestCase):
    def setUp(self):
        self.f=server_fixture.CompanionTests();self.f.setUp();self.script=self.f.root/'question-peer.py';self.script.write_text(CODEX)
        self.f.manager.environments['computer']['workers']=['codex_app_server'];self.f.manager.environments['computer']['writeAllowed']=True
        self.f.config['environments'][0]['workers']=['codex_app_server'];self.f.config['environments'][0]['writeAllowed']=True

    def tearDown(self):self.f.tearDown()

    def start(self,operation='ordinary-question-job',**extra):
        body={'operationId':operation,'environmentId':'computer','worker':'codex_app_server','prompt':'Choose and produce selected file',**extra}
        with patch.object(self.f.manager,'_launch',return_value=([sys.executable,str(self.script),'normal'],str(self.f.workspace))):
            status,value=self.f.request('/v1/jobs',body)
        self.assertEqual(200,status,value);return value['result']

    def pending(self,job):
        until=time.monotonic()+3
        while time.monotonic()<until:
            current=self.f.request('/v1/jobs/'+job['id'])[1]['result']
            if current.get('pendingQuestions'):return current,current['pendingQuestions'][0]
            time.sleep(.01)
        self.fail('No original server question')

    def test_original_http_job_question_snapshot_and_same_session_continuation(self):
        job=self.start();current,q=self.pending(job)
        self.assertEqual('awaiting_input',current['status']);self.assertFalse(current['controls']['approve']);self.assertTrue(current['controls']['answerQuestion'])
        body={'answerId':str(uuid.uuid4()),'jobOperationId':job['operationId'],'requestId':q['requestId'],'requestHash':q['requestHash'],'answers':{'style':['Brief']}}
        path='/v1/jobs/'+job['id']+'/question-answer';self.assertEqual(200,self.f.request(path,body)[0]);self.assertEqual(200,self.f.request(path,body)[0])
        actual=self.f.finish(job['id']);self.assertEqual('completed',actual['status']);self.assertEqual('1',(self.f.workspace/'interaction-answer-count').read_text())
        bad=copy.deepcopy(body);bad['answers']['style']=['Detailed'];self.assertEqual(409,self.f.request(path,bad)[0])
        capture={'operationId':'question-result-capture','jobOperationId':job['operationId'],'environmentId':'computer','worker':'codex_app_server','threadId':actual['threadId'],'turnId':actual['turnId'],'paths':['result.txt']}
        status,saved=self.f.request('/v1/jobs/'+job['id']+'/snapshots',capture);self.assertEqual(200,status,saved);snapshot=saved['result']
        status,saved=self.f.request('/v1/jobs/'+job['id']+'/snapshots/'+snapshot['snapshotId']+'?version='+snapshot['snapshotVersion']+'&path=result.txt')
        self.assertEqual(b'ACTUAL FROZEN BYTES: Brief',base64.b64decode(saved['result']['base64']))
        until=time.monotonic()+2
        while self.f.manager.resources and time.monotonic()<until:time.sleep(.005)
        next_job=self.start('explicit-question-continue',threadId=actual['threadId'],resumeJobId=job['id'],resultSnapshotId=snapshot['snapshotId'],resultSnapshotVersion=snapshot['snapshotVersion'])
        current,q=self.pending(next_job);self.assertEqual(actual['threadId'],current['threadId']);self.assertNotEqual(actual['turnId'],current['turnId'])
        body.update(answerId=str(uuid.uuid4()),jobOperationId=next_job['operationId'],requestId=q['requestId'],requestHash=q['requestHash'])
        self.assertEqual(200,self.f.request('/v1/jobs/'+next_job['id']+'/question-answer',body)[0]);self.assertEqual('completed',self.f.finish(next_job['id'])['status'])
        self.assertEqual('11',(self.f.workspace/'interaction-turn-count').read_text())
