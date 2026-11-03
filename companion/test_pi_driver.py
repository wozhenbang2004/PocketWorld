"""Independent protocol fixtures; no installed vendor CLI/account/inference claim."""
import concurrent.futures
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

from pi_driver import PiRpcDriver, DriverError, REQUIRED_FLAGS, SAFE_FLAGS, command, inspect_cli, session_stamp
from pocket_companion import JobManager, RequestError, worker_capabilities

TOKEN = "pi-fixture-only-not-a-real-companion-credential-123456789"
FIXTURE = r'''
import json, sys, time, threading
from pathlib import Path
mode=sys.argv[1];args=sys.argv[2:]
def emit(v):print(json.dumps(v,ensure_ascii=False),flush=True)
if '--version' in args:
    print('1.3.0' if mode=='old-cli' else '1.4.0');sys.exit()
if '--help' in args:
    flags='--no-tools --no-mcp --no-extensions --no-skills --no-prompt-templates --no-themes --no-context-files --no-approve --offline --mode --session-id --session-dir --session --no-session'
    print(flags.replace('--no-tools','') if mode=='missing-flags' else flags);sys.exit()
assert args[args.index('--mode')+1]=='rpc'
for flag in ('--no-tools','--no-mcp','--no-extensions','--no-skills','--no-prompt-templates','--no-themes','--no-context-files','--no-approve','--offline'):assert flag in args
session='probe-session';file=None
if '--session-dir' in args:
    directory=Path(args[args.index('--session-dir')+1])
    if '--session' in args:
        file=Path(args[args.index('--session')+1]);session=json.loads(file.read_text().splitlines()[0])['id']
    else:
        session=args[args.index('--session-id')+1];file=directory/('fixture_'+session+'.jsonl')
    if not file.exists():file.write_text(json.dumps({'type':'session','version':3,'id':session,'cwd':str(Path.cwd())})+'\n')
model={'provider':'fixture','id':'model-a','name':'Fixture A','contextWindow':200000,'maxTokens':8192,'headers':{'secret':'NEVER_COPY_CONFIG'}}
def done(aborted=False):
    if mode!='no-result':emit({'type':'message_end','message':{'role':'assistant','content':[{'type':'text','text':'public final\u2028continued'},{'type':'thinking','thinking':'HIDDEN_INTERNAL','signature':'DO_NOT_COPY'}],'stopReason':'aborted' if aborted else 'error' if mode=='model-error' else 'stop'}})
    emit({'type':'agent_end','messages':[{'role':'assistant','content':[{'type':'thinking','thinking':'HIDDEN_END'}]}]})
    if mode=='end-only':sys.exit()
    if mode=='retry':
        time.sleep(.2);emit({'type':'auto_retry_start','errorMessage':'PRIVATE_RETRY_DETAIL'});emit({'type':'agent_start'})
        emit({'type':'message_start','message':{'role':'assistant'}})
        emit({'type':'message_end','message':{'role':'assistant','content':[{'type':'text','text':'after retry'}],'stopReason':'stop'}})
        emit({'type':'agent_end'})
    emit({'type':'agent_settled','aborted':aborted})
for line in sys.stdin:
    value=json.loads(line);kind=value['type'];rid=value['id']
    def reply(data=None):emit({'type':'response','id':'wrong' if mode=='wrong-response' else rid,'command':kind,'success':True,'data':data})
    if kind=='get_state':reply({'sessionId':'different-session' if mode=='wrong-session' else session,'sessionFile':str(file) if mode!='wrong-file' else '/tmp/foreign.jsonl','isStreaming':False,'isCompacting':False,'pendingMessageCount':1 if mode=='queued-before' else 0,'messageCount':0,'model':model,'autoCompactionEnabled':True})
    elif kind=='get_commands':reply({'commands':[{'name':'unsafe-extension'}] if mode=='commands' else []})
    elif kind=='get_available_models':reply({'models':[model,{'provider':'fixture','id':'model-b','name':'Fixture B'}]})
    elif kind=='set_model':
        assert value['provider']=='fixture' and value['modelId']=='model-b';model={**model,'id':'model-b'}
        emit({'type':'entry_appended','entry':{'type':'model_change','provider':'fixture','modelId':'model-b'}});reply(model)
    elif kind=='prompt':
        assert set(value)=={'id','type','message'}
        count=Path('pi-prompt-count');count.write_text(count.read_text()+'1' if count.exists() else '1')
        if file:
            with file.open('a') as stream:stream.write(json.dumps({'type':'message','message':{'role':'user','content':value['message']}})+'\n')
        if mode=='disconnect':sys.exit()
        if mode=='invalid-json':print('not-json',flush=True);sys.exit()
        if mode=='large-frame':print('x'*1048577,flush=True);sys.exit()
        emit({'type':'entry_appended','entry':{'type':'message','message':{'role':'user','content':'HIDDEN_RAW_HISTORY'}}})
        emit({'type':'queue_update','steering':['unreviewed'] if mode=='foreign-queue' else [],'followUp':[]})
        reply({'disposition':'queued' if mode=='queued' else 'started'})
        emit({'type':'agent_start'});emit({'type':'message_start','message':{'role':'user','content':value['message']}})
        emit({'type':'message_end','message':{'role':'user','content':value['message']}})
        emit({'type':'message_start','message':{'role':'assistant','content':[]}})
        emit({'type':'message_update','assistantMessageEvent':{'type':'thinking_delta','delta':'HIDDEN_THINKING','partial':{'private':'never copied'}}})
        emit({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':'public partial'}})
        if mode=='tool':emit({'type':'tool_execution_start','toolCallId':'bad','toolName':'bash','args':{'command':'DO_NOT_EXECUTE'}});continue
        if mode=='tool-message':emit({'type':'message_end','message':{'role':'assistant','content':[{'type':'toolCall','name':'bash','arguments':{'command':'DO_NOT_EXECUTE'}}],'stopReason':'toolUse'}});continue
        if mode=='extension-ui':emit({'type':'extension_ui_request','id':'ui','method':'confirm','message':'not approval'});continue
        if mode=='wait-success':
            def release_after_explicit_fixture_marker():
                while not Path('release-pi-turn').exists():time.sleep(.01)
                done()
            threading.Thread(target=release_after_explicit_fixture_marker,daemon=True).start()
            continue
        if mode in {'wait','abort-ack-only'}:continue
        done()
    elif kind=='abort':
        if mode=='abort-ack-only':reply();continue
        done(True);reply()
    else:raise AssertionError('UNREVIEWED_MUTATION '+kind)
'''


class PiDriverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="pocket-pi-rpc-")
        self.root=Path(self.temp.name);self.fixture=self.root/'fixture.py';self.fixture.write_text(FIXTURE)
        self.processes=[];self.drivers=[];self.pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def tearDown(self):
        for driver in self.drivers:driver.close_steering()
        for process in self.processes:
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            for stream in (process.stdin,process.stdout):stream.close()
        self.pool.shutdown(wait=True);self.temp.cleanup()

    def driver(self,mode='success',stopped=None,probe=False):
        native=str(uuid.uuid4());directory=self.root/native;directory.mkdir(mode=0o700)
        argv=command() if probe else command(native,str(directory))
        process=subprocess.Popen([sys.executable,str(self.fixture),mode,*argv[1:]],cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0)
        self.processes.append(process);states=[]
        driver=PiRpcDriver(process,states.append,stopped or (lambda:False),5,native_session=None if probe else native,session_dir=None if probe else str(directory))
        self.drivers.append(driver);return driver,states

    def test_fixed_version_and_all_no_tools_flags_required(self):
        help_text=' '.join(REQUIRED_FLAGS)
        self.assertTrue(inspect_cli('1.4.0',help_text)['available'])
        for version in ('1.3.0','1.4.1','1.4.0-dev','1.4.0 extra'):self.assertFalse(inspect_cli(version,help_text)['available'])
        for flag in REQUIRED_FLAGS:self.assertFalse(inspect_cli('1.4.0',help_text.replace(flag,''))['available'],flag)
        argv=command('managed-id','/tmp/managed');self.assertTrue(set(SAFE_FLAGS)<=set(argv));self.assertNotIn('--model',argv)
        self.assertEqual('--no-session',command()[-1])
        with self.assertRaises(DriverError):command('id','/tmp/managed','/tmp/foreign.jsonl')

    def test_readonly_probe_has_no_prompt_or_saved_session_or_secret_metadata(self):
        driver,_=self.driver(probe=True);result=driver.probe('fixture/model-b')
        self.assertTrue(result['protocolVerified']);self.assertTrue(result['selectedModelAvailable'])
        self.assertIsNone(result['accountConfigured']);self.assertFalse(result['modelInferenceVerified'])
        self.assertNotIn('NEVER_COPY',json.dumps(result));self.assertFalse((self.root/'pi-prompt-count').exists());self.assertFalse(driver.turn_dispatched)

    def test_actual_public_result_requires_settled_and_drops_hidden_content(self):
        driver,states=self.driver();result=driver.run('hello')
        self.assertEqual('completed',result['status']);self.assertTrue(result['nativeSettled'])
        self.assertEqual('public final\u2028continued',result['output']);self.assertNotIn('HIDDEN',json.dumps(states));self.assertNotIn('DO_NOT_COPY',json.dumps(result))
        self.assertTrue(result['autoCompactionEnabled']);self.assertIsNone(result['inferenceUsage']['modelCalls']);self.assertFalse(driver.can_steer(None))
        self.assertEqual('1',(self.root/'pi-prompt-count').read_text())

    def test_exact_catalog_model_selection_uses_session_only_set_model_readback(self):
        driver,_=self.driver();self.assertEqual('fixture/model-b',driver.run('hello',model='fixture/model-b')['reportedModel'])

    def test_unknown_alias_commands_queue_or_wrong_identity_never_dispatch(self):
        for mode,model in (('success','alias'),('commands',None),('queued-before',None),('wrong-response',None),('wrong-session',None),('wrong-file',None)):
            with self.subTest(mode=mode):
                driver,_=self.driver(mode)
                with self.assertRaises(DriverError):driver.run('hello',model=model)
                self.assertFalse(driver.turn_dispatched)
        self.assertFalse((self.root/'pi-prompt-count').exists())

    def test_end_is_not_terminal_and_retry_can_change_final_public_result(self):
        driver,states=self.driver('retry');future=self.pool.submit(driver.run,'hello');deadline=time.monotonic()+2
        while not any(any(v['nativeType']=='agent_end' for v in s['progress']['events']) for s in states) and time.monotonic()<deadline:time.sleep(.01)
        self.assertFalse(future.done());result=future.result(timeout=3)
        self.assertEqual('after retry',result['output']);self.assertEqual('completed',result['status']);self.assertNotIn('PRIVATE_RETRY_DETAIL',json.dumps(states))

    def test_disconnect_malformed_missing_settlement_or_tool_ui_are_never_completion(self):
        for mode in ('end-only','disconnect','invalid-json','large-frame','queued','tool','tool-message','extension-ui','no-result','foreign-queue'):
            with self.subTest(mode=mode):
                driver,_=self.driver(mode)
                with self.assertRaises(DriverError):driver.run('hello')
                self.assertTrue(driver.turn_dispatched)
        self.assertEqual('1'*10,(self.root/'pi-prompt-count').read_text())

    def test_known_native_error_is_not_completed(self):
        driver,_=self.driver('model-error');self.assertEqual('failed',driver.run('hello')['status'])

    def test_cancel_requires_both_abort_response_and_native_settled(self):
        stopped=threading.Event();driver,states=self.driver('wait',stopped.is_set);future=self.pool.submit(driver.run,'hello');deadline=time.monotonic()+2
        while not any(s['output'] for s in states) and time.monotonic()<deadline:time.sleep(.01)
        stopped.set();result=future.result(timeout=3)
        self.assertEqual('cancelled',result['status']);self.assertTrue(result['interruptAcknowledged']);self.assertTrue(result['nativeSettled'])

    def test_abort_ack_alone_stays_unknown(self):
        stopped=threading.Event();driver,states=self.driver('abort-ack-only',stopped.is_set);future=self.pool.submit(driver.run,'hello');deadline=time.monotonic()+2
        while not any(s['output'] for s in states) and time.monotonic()<deadline:time.sleep(.01)
        stopped.set()
        while not driver.abort_ack and time.monotonic()<deadline:time.sleep(.01)
        self.assertTrue(driver.abort_ack);self.assertFalse(future.done());driver.abort_deadline=time.monotonic()-1
        with self.assertRaises(DriverError):future.result(timeout=2)

    def test_private_file_continuity_rejects_mutation_and_symlink(self):
        path=self.root/'managed.jsonl';path.write_text('native private history');before=session_stamp(path,self.root);path.write_text('modified private history')
        self.assertNotEqual(before,session_stamp(path,self.root));link=self.root/'foreign.jsonl';link.symlink_to(path)
        with self.assertRaises(DriverError):session_stamp(link,self.root)


class PiCompanionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-pi-original-manager-');self.root=Path(self.temp.name)
        self.workspace=self.root/'workspace';self.workspace.mkdir();self.fixture=self.root/'fixture.py';self.fixture.write_text(FIXTURE)
        self.manager=JobManager({'stateDir':str(self.root/'state'),'environments':[{'id':'computer','root':str(self.workspace),'workers':['pi'],'writeAllowed':True}]},TOKEN);self.mode='success'
        # Substitute only vendor executable; preserve actual original Popen/group/ledger/pool.
        self.launch=patch.object(self.manager,'_launch',side_effect=lambda env,argv:([sys.executable,str(self.fixture),self.mode,*argv[1:]],str(self.workspace)));self.launch.start()

    def tearDown(self):
        self.manager.close();self.launch.stop();self.temp.cleanup()

    def request(self,op='first',**extra):
        return {'operationId':op,'environmentId':'computer','environmentBinding':self.manager.environment_binding('computer'),'worker':'pi','prompt':'public owner goal','timeoutSeconds':5,**extra}

    def finish(self,job):
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            actual=self.manager.get(job['id'])
            if actual['status'] in {'completed','failed','cancelled','unknown','blocked'} and actual.get('dispatchFenceFinal'):return actual
            time.sleep(.01)
        self.fail('original manager did not settle: '+str(actual))

    def test_original_probe_start_resume_and_dedup_use_same_managed_session(self):
        probe=self.manager.probe_worker({'environmentId':'computer','worker':'pi','model':'fixture/model-a'});self.assertTrue(probe['protocolVerified']);self.assertFalse((self.workspace/'pi-prompt-count').exists())
        body=self.request();first=self.finish(self.manager.submit(body));self.assertEqual('completed',first['status'])
        self.assertTrue(first['processCleanupProof']['processGroupStoppedVerified']);self.assertTrue(first['controls']['threadResume']);self.assertFalse(first['controls']['approve']);self.assertFalse(first['controls']['inputSteering'])
        self.assertNotIn('_piSession',first);self.assertNotIn('pi-sessions',json.dumps(first));self.assertNotIn('HIDDEN',json.dumps(first));self.assertEqual(first['id'],self.manager.submit(body)['id'])
        second=self.finish(self.manager.submit(self.request('second',threadId=first['threadId'],resumeJobId=first['id'])))
        self.assertEqual(first['threadId'],second['threadId']);self.assertEqual('completed',second['status']);self.assertFalse(self.manager.get(first['id'])['controls']['threadResume'])
        self.assertEqual('11',(self.workspace/'pi-prompt-count').read_text());self.assertEqual(2,len(self.manager.jobs))

    def test_native_file_change_unknown_work_or_unproven_cleanup_cannot_resume(self):
        first=self.finish(self.manager.submit(self.request()));internal=self.manager.jobs[first['id']];internal['processCleanupProof']['processGroupStoppedVerified']=False
        self.assertFalse(self.manager.get(first['id'])['controls']['threadResume']);internal['processCleanupProof']['processGroupStoppedVerified']=True
        path=Path(internal['_piSession']['file']);path.write_text(path.read_text()+'changed\n');self.assertFalse(self.manager.get(first['id'])['controls']['threadResume'])
        with self.assertRaises(RequestError):self.manager.submit(self.request('bad',threadId=first['threadId'],resumeJobId=first['id']))
        self.assertEqual('1',(self.workspace/'pi-prompt-count').read_text())

    def test_queued_continuation_rechecks_original_source_before_any_new_process(self):
        first=self.finish(self.manager.submit(self.request()));resource=self.manager.jobs[first['id']]['resourceId']
        with self.manager.resource_changed:self.manager.resources.add(resource)
        child=self.manager.submit(self.request('queued-child',threadId=first['threadId'],resumeJobId=first['id']))
        with self.manager.resource_changed:
            self.manager.jobs.pop(first['id'])  # Controlled original-source expiry while its child waits for the real lease.
            self.manager.resources.discard(resource);self.manager.resource_changed.notify_all()
        result=self.finish(child);self.assertEqual('blocked',result['status']);self.assertFalse(result['controls']['threadResume'])
        self.assertEqual('1',(self.workspace/'pi-prompt-count').read_text());self.assertFalse(result.get('processStarted',False))

    def test_exit_zero_without_settlement_is_unknown_and_not_replayed(self):
        self.mode='end-only';body=self.request();actual=self.finish(self.manager.submit(body));self.assertEqual('unknown',actual['status']);self.assertFalse(actual['controls']['threadResume'])
        self.assertEqual(actual['id'],self.manager.submit(body)['id']);self.assertEqual('1',(self.workspace/'pi-prompt-count').read_text())

    def test_unsupported_version_flags_scope_and_false_sandbox_refused_before_job(self):
        for mode in ('old-cli','missing-flags'):
            self.mode=mode
            with self.assertRaises(RequestError):self.manager.submit(self.request(mode))
        self.mode='success'
        for fields in ({'readOnly':True},{'argv':['pi','--tools','bash']},{'_piSession':{}},{'ccSwitchImport':{}},{'resumeJobId':'other'}):
            with self.assertRaises(RequestError):self.manager.submit(self.request('bad',**fields))
        self.assertEqual({},self.manager.jobs);self.assertFalse((self.workspace/'pi-prompt-count').exists())
        caps=worker_capabilities('pi');self.assertTrue(caps['noTools']);self.assertFalse(caps['interactiveApprovals']);self.assertFalse(caps['codingToolsSupported'])


if __name__=='__main__':unittest.main()
