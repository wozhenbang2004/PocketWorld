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
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

from pi_driver import PiRpcDriver, DriverError, REQUIRED_FLAGS, SAFE_FLAGS, command, inspect_cli, session_stamp
from pocket_companion import CompanionServer, JobManager, RequestError, worker_capabilities

TOKEN = "pi-fixture-only-not-a-real-companion-credential-123456789"
FIXTURE = r'''
import json, sys, time, threading
from pathlib import Path
mode=sys.argv[1];args=sys.argv[2:]
output_lock=threading.Lock()
def emit(v):
    with output_lock:print(json.dumps(v,ensure_ascii=False),flush=True)
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
steering=[];late_steer=None;prompt_started=False
def done(aborted=False,final_text='public final\u2028continued'):
    if mode!='no-result':emit({'type':'message_end','message':{'role':'assistant','content':[{'type':'text','text':final_text},{'type':'thinking','thinking':'HIDDEN_INTERNAL','signature':'DO_NOT_COPY'}],'stopReason':'aborted' if aborted else 'error' if mode=='model-error' else 'stop'}})
    emit({'type':'agent_end','messages':[{'role':'assistant','content':[{'type':'thinking','thinking':'HIDDEN_END'}]}]})
    if mode=='end-only':sys.exit()
    if mode=='retry':
        time.sleep(.2);emit({'type':'auto_retry_start','errorMessage':'PRIVATE_RETRY_DETAIL'});emit({'type':'agent_start'})
        emit({'type':'message_start','message':{'role':'assistant'}})
        emit({'type':'message_end','message':{'role':'assistant','content':[{'type':'text','text':'after retry'}],'stopReason':'stop'}})
        emit({'type':'agent_end'})
    emit({'type':'agent_settled','aborted':aborted})
    Path('pi-settled-emitted').touch()
for line in sys.stdin:
    value=json.loads(line);kind=value['type'];rid=value['id']
    with Path('pi-command-log').open('a') as log:log.write(kind+'\n')
    def reply(data=None,reply_id=rid,reply_kind=kind):emit({'type':'response','id':'wrong' if mode=='wrong-response' else reply_id,'command':reply_kind,'success':True,'data':data})
    if kind=='get_state':reply({'sessionId':'different-session' if mode=='wrong-session' or mode=='steer-final-state-session' and prompt_started else session,'sessionFile':str(file) if mode!='wrong-file' else '/tmp/foreign.jsonl','isStreaming':False,'isCompacting':False,'pendingMessageCount':1 if mode=='queued-before' or mode=='steer-final-state-pending' and prompt_started else len(steering),'messageCount':0,'model':model,'autoCompactionEnabled':True})
    elif kind=='get_commands':reply({'commands':[{'name':'unsafe-extension'}] if mode=='commands' else []})
    elif kind=='get_available_models':reply({'models':[model,{'provider':'fixture','id':'model-b','name':'Fixture B'}]})
    elif kind=='set_model':
        assert value['provider']=='fixture' and value['modelId']=='model-b';model={**model,'id':'model-b'}
        emit({'type':'entry_appended','entry':{'type':'model_change','provider':'fixture','modelId':'model-b'}});reply(model)
    elif kind=='prompt':
        prompt_started=True
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
        emit({'type':'agent_start'})
        system={'role':'system','content':'','sections':{'identity':'PRIVATE_NATIVE_SYSTEM_SECTIONS'},'timestamp':1}
        if mode=='system-tools':system['toolsAdded']=[{'name':'bash','description':'unapproved','parameters':{}}]
        emit({'type':'message_start','message':system});emit({'type':'message_end','message':system})
        emit({'type':'message_start','message':{'role':'user','content':value['message']}})
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
        if mode in {'wait','abort-ack-only'} or mode.startswith('steer-'):continue
        done()
    elif kind=='steer':
        assert set(value)=={'id','type','message'}
        count=Path('pi-steer-count');count.write_text(count.read_text()+'1' if count.exists() else '1')
        def queued(text=value['message'],ack=reply):
            steering.append(text);emit({'type':'queue_update','steering':list(steering),'followUp':[]})
            ack({'disposition':'queued'})
        if mode=='steer-late-enqueue':late_steer=queued;continue
        if mode=='steer-settled-before-ack':done();queued();continue
        if mode=='steer-missing-ack':
            steering.append(value['message']);emit({'type':'queue_update','steering':list(steering),'followUp':[]});continue
        if mode=='steer-wrong-ack':emit({'type':'response','id':'unowned','command':'steer','success':True,'data':{'disposition':'queued'}});continue
        if mode=='steer-handled':reply({'disposition':'handled'});continue
        if mode=='steer-refused':emit({'type':'response','id':rid,'command':'steer','success':False,'error':'PRIVATE_NATIVE_ERROR'});continue
        if mode=='steer-disconnect':sys.exit()
        if mode=='steer-late-ack':
            def release_ack(action=queued):
                while not Path('release-pi-steer-ack').exists():time.sleep(.005)
                action()
            threading.Thread(target=release_ack,daemon=True).start();continue
        queued()
        if mode=='steer-foreign-queue':emit({'type':'queue_update','steering':['UNREVIEWED_PRIVATE_QUEUE'],'followUp':[]});continue
        if mode in {'steer-live','steer-final-state-pending','steer-final-state-session'}:
            steering.pop(0);emit({'type':'queue_update','steering':list(steering),'followUp':[]})
            emit({'type':'message_start','message':{'role':'user','content':[{'type':'text','text':value['message']}]}})
            emit({'type':'message_end','message':{'role':'user','content':[{'type':'text','text':value['message']}]}})
            emit({'type':'message_start','message':{'role':'assistant','content':[]}})
            done(final_text='actual live fixture response')
    elif kind=='clear_queue':
        if mode=='steer-clear-no-ack':continue
        removed=list(steering);steering.clear()
        emit({'type':'queue_update','steering':[],'followUp':[]});reply({'steering':removed,'followUp':[]})
    elif kind=='abort':
        if mode=='abort-ack-only':reply();continue
        if late_steer:late_steer();late_steer=None
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

    def driver(self,mode='success',stopped=None,probe=False,timeout=5):
        native=str(uuid.uuid4());directory=self.root/native;directory.mkdir(mode=0o700)
        argv=command() if probe else command(native,str(directory))
        process=subprocess.Popen([sys.executable,str(self.fixture),mode,*argv[1:]],cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0)
        self.processes.append(process);states=[]
        driver=PiRpcDriver(process,states.append,stopped or (lambda:False),timeout,native_session=None if probe else native,session_dir=None if probe else str(directory))
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

    def test_fixed_system_prompt_sections_are_private_and_never_add_tool_authority(self):
        driver,states=self.driver();result=driver.run('hello')
        self.assertEqual('completed',result['status']);self.assertNotIn('PRIVATE_NATIVE_SYSTEM_SECTIONS',json.dumps(states))
        driver,_=self.driver('system-tools')
        with self.assertRaises(DriverError):driver.run('hello')
        self.assertTrue(driver.turn_dispatched);self.assertFalse(driver.native_queue_empty_confirmed)

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

    def until(self,predicate):
        deadline=time.monotonic()+2
        while time.monotonic()<deadline:
            if predicate():return
            time.sleep(.005)
        self.assertTrue(predicate(),'original fixture state did not arrive')

    def active(self,mode='steer-wait',stopped=None,timeout=5):
        driver,states=self.driver(mode,stopped,timeout=timeout)
        future=self.pool.submit(driver.run,'original prompt')
        self.until(driver.can_steer)
        return driver,states,future

    @staticmethod
    def input(driver,message_id='owner-message'):
        return {'messageId':message_id,'expectedPromptRequestId':driver.prompt_id,'text':'Explicit owner correction'}

    def test_live_input_uses_native_queue_ack_and_real_response_without_fabricated_turn(self):
        driver,states,future=self.active('steer-live');receipts=[];deadline=driver.deadline
        driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
        result=future.result(timeout=3)
        self.assertEqual('actual live fixture response',result['output'])
        self.assertEqual(['sending','accepted'],[status for status,_ in receipts])
        self.assertEqual('queued',receipts[-1][1]['disposition'])
        self.assertEqual('native_queue',receipts[-1][1]['delivery']);self.assertEqual('unknown',receipts[-1][1]['consumed'])
        self.assertNotIn('turnId',json.dumps(receipts));self.assertNotIn('turnId',result)
        self.assertTrue(result['nativeSettled']);self.assertTrue(result['nativeQueueEmptyConfirmed']);self.assertFalse(driver.can_steer())
        self.assertEqual(deadline,driver.deadline);self.assertIsNone(result['inferenceUsage']['modelCalls'])
        self.assertEqual('1',(self.root/'pi-prompt-count').read_text());self.assertEqual('1',(self.root/'pi-steer-count').read_text())
        self.assertNotIn('HIDDEN',json.dumps(states));self.assertNotIn('Explicit owner correction',json.dumps(states))

    def test_settled_before_steer_ack_clears_unconsumed_queue_without_new_prompt(self):
        driver,_,future=self.active('steer-settled-before-ack');receipts=[]
        driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
        result=future.result(timeout=3)
        self.assertEqual('completed',result['status']);self.assertTrue(result['nativeQueueEmptyConfirmed'])
        self.assertEqual('accepted',receipts[-1][0]);self.assertEqual('unknown',receipts[-1][1]['consumed'])
        commands=(self.root/'pi-command-log').read_text().splitlines()
        self.assertEqual(['steer','clear_queue','get_state'],commands[commands.index('steer'):])
        self.assertEqual('1',(self.root/'pi-prompt-count').read_text())

    def test_stop_clears_pending_and_late_enqueue_after_abort_within_original_deadline(self):
        for mode in ('steer-wait','steer-late-enqueue'):
            with self.subTest(mode=mode):
                stopped=threading.Event();driver,_,future=self.active(mode,stopped.is_set);receipts=[];deadline=driver.deadline
                driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
                self.until(lambda:bool(driver.steer_replies) or any(status=='accepted' for status,_ in receipts))
                stopped.set();result=future.result(timeout=3)
                self.assertEqual('cancelled',result['status']);self.assertTrue(result['interruptAcknowledged'])
                self.assertTrue(result['nativeQueueEmptyConfirmed']);self.assertEqual(deadline,driver.deadline)
                self.assertEqual('accepted',receipts[-1][0]);self.assertEqual('unknown',receipts[-1][1]['consumed'])
                commands=(self.root/'pi-command-log').read_text().splitlines()
                at=len(commands)-1-commands[::-1].index('steer')
                self.assertEqual(['steer','clear_queue','abort','clear_queue','get_state'],commands[at:])

    def test_late_ack_reconciles_original_input_and_unknown_blocks_further_dispatch(self):
        stopped=threading.Event();driver,_,future=self.active('steer-late-ack',stopped.is_set);receipts=[]
        driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
        self.until(lambda:bool(driver.steer_replies))
        next(iter(driver.steer_replies.values()))['deadline']=time.monotonic()-1
        self.until(lambda:any(status=='unknown' for status,_ in receipts))
        self.assertFalse(driver.can_steer())
        with self.assertRaises(DriverError):driver.steer(self.input(driver,'replacement'),lambda *_:None)
        (self.root/'release-pi-steer-ack').touch()
        self.until(lambda:any(status=='accepted' for status,_ in receipts))
        self.assertEqual(['sending','unknown','accepted'],[status for status,_ in receipts]);self.assertTrue(driver.can_steer(driver.prompt_id))
        stopped.set();self.assertTrue(future.result(timeout=3)['nativeQueueEmptyConfirmed'])
        self.assertEqual('1',(self.root/'pi-steer-count').read_text())

    def test_stale_prompt_and_stop_during_durable_sending_never_touch_input_pipe(self):
        stopped=threading.Event();driver,_,future=self.active(stopped=stopped.is_set);receipts=[]
        with self.assertRaises(DriverError):driver.steer({**self.input(driver),'expectedPromptRequestId':'old-prompt'},lambda *_:None)
        def receipt(status,fields):
            receipts.append(status)
            if status=='sending':stopped.set()
        driver.steer(self.input(driver),receipt)
        self.assertEqual('cancelled',future.result(timeout=3)['status'])
        self.assertEqual(['sending','rejected'],receipts);self.assertFalse((self.root/'pi-steer-count').exists())

    def test_native_settlement_received_while_dispatch_lock_waits_rejects_local_queue(self):
        driver,_,future=self.active('wait-success');receipts=[];lock=threading.RLock();waiting=threading.Event()
        class Gate:
            def __enter__(self):waiting.set();lock.acquire()
            def __exit__(self,*_):lock.release()
        with lock:
            driver.dispatch_guard=Gate
            driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
            self.assertTrue(waiting.wait(1))
            (self.root/'release-pi-turn').touch()
            self.until(lambda:(self.root/'pi-settled-emitted').exists() and not driver.events.empty())
        self.assertEqual('completed',future.result(timeout=3)['status'])
        self.assertEqual(['rejected'],[status for status,_ in receipts]);self.assertFalse((self.root/'pi-steer-count').exists())

    def test_wrong_handled_refused_or_lost_ack_is_unknown_and_never_replayed(self):
        for mode in ('steer-wrong-ack','steer-handled','steer-refused','steer-disconnect','steer-foreign-queue'):
            with self.subTest(mode=mode):
                driver,states,future=self.active(mode);receipts=[]
                driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
                with self.assertRaises(DriverError):future.result(timeout=3)
                driver.close_steering()
                self.assertEqual('unknown' if mode!='steer-foreign-queue' else 'accepted',receipts[-1][0])
                self.assertFalse(driver.can_steer());self.assertFalse(driver.native_queue_empty_confirmed)
                self.assertNotIn('PRIVATE_NATIVE_ERROR',json.dumps(receipts));self.assertNotIn('UNREVIEWED_PRIVATE_QUEUE',json.dumps(states))
        self.assertEqual('1'*5,(self.root/'pi-steer-count').read_text())

    def test_final_state_requires_empty_original_session_after_queue_cleanup(self):
        for mode in ('steer-final-state-pending','steer-final-state-session'):
            with self.subTest(mode=mode):
                driver,_,future=self.active(mode);receipts=[]
                driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
                with self.assertRaises(DriverError):future.result(timeout=3)
                self.assertEqual('accepted',receipts[-1][0]);self.assertFalse(driver.native_queue_empty_confirmed)

    def test_cleanup_ack_loss_is_not_stop_or_empty_queue_confirmation(self):
        stopped=threading.Event();driver,_,future=self.active('steer-clear-no-ack',stopped.is_set)
        stopped.set();self.until(lambda:driver.clear_id is not None)
        self.assertFalse(driver.native_queue_empty_confirmed);self.assertFalse(driver.abort_ack)
        driver.abort_deadline=time.monotonic()-1
        with self.assertRaises(DriverError):future.result(timeout=2)

    def test_partial_input_write_is_unknown_without_replay(self):
        driver,_,future=self.active();receipts=[];send=driver._send
        def partial(kind,request_id,**values):
            if kind!='steer':return send(kind,request_id,**values)
            driver.process.stdin.write(b'{"type":"steer",');driver.process.stdin.flush()
            raise DriverError('Controlled partial pipe write')
        driver._send=partial
        driver.steer(self.input(driver),lambda status,fields:receipts.append((status,fields)))
        with self.assertRaises(DriverError):future.result(timeout=2)
        self.assertEqual(['sending','unknown'],[status for status,_ in receipts]);self.assertFalse(driver.can_steer())
        self.assertFalse((self.root/'pi-steer-count').exists());self.assertEqual('1',(self.root/'pi-prompt-count').read_text())

    def test_native_queue_frame_limit_rejects_before_write_without_truncating_input(self):
        stopped=threading.Event();driver,_,future=self.active(stopped=stopped.is_set,timeout=15)
        for index in range(22):
            receipts=[]
            driver.steer({**self.input(driver,'message-'+str(index)),'text':'\U0001f600'*12_000},
                lambda status,fields:receipts.append((status,fields)))
            self.until(lambda:receipts and receipts[-1][0] in {'accepted','rejected','unknown'})
            self.assertEqual('accepted' if index<21 else 'rejected',receipts[-1][0])
        self.assertFalse(receipts[-1][1]['dispatched']);self.assertFalse(driver.steering_unknown)
        self.assertEqual('1'*21,(self.root/'pi-steer-count').read_text())
        stopped.set();self.assertTrue(future.result(timeout=3)['nativeQueueEmptyConfirmed'])


class PiCompanionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-pi-original-manager-');self.root=Path(self.temp.name)
        self.workspace=self.root/'workspace';self.workspace.mkdir();self.fixture=self.root/'fixture.py';self.fixture.write_text(FIXTURE)
        self.config={'stateDir':str(self.root/'state'),'environments':[{'id':'computer','root':str(self.workspace),'workers':['pi'],'writeAllowed':True}]}
        self.manager=JobManager(self.config,TOKEN);self.mode='success';self.server=None
        # Substitute only vendor executable; preserve actual original Popen/group/ledger/pool.
        self.launch=patch.object(self.manager,'_launch',side_effect=lambda env,argv:([sys.executable,str(self.fixture),self.mode,*argv[1:]],str(self.workspace)));self.launch.start()

    def tearDown(self):
        if self.server:
            self.server.shutdown();self.server.server_close();self.server_thread.join(timeout=1)
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

    def http(self,path,body=None):
        if self.server is None:
            self.server=CompanionServer(('127.0.0.1',0),self.manager)
            self.server_thread=threading.Thread(target=self.server.serve_forever,kwargs={'poll_interval':.01},daemon=True)
            self.server_thread.start()
        request=Request('http://127.0.0.1:'+str(self.server.server_port)+path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
        try:
            with urlopen(request,timeout=3) as response:return response.status,json.loads(response.read())
        except HTTPError as error:return error.code,json.loads(error.read())

    def active(self,mode='steer-wait'):
        self.mode=mode;status,result=self.http('/v1/jobs',self.request())
        self.assertEqual(200,status,result);job=result['result'];deadline=time.monotonic()+2
        while time.monotonic()<deadline:
            job=self.manager.get(job['id'])
            if job['controls']['inputSteering']:return job
            time.sleep(.005)
        self.fail('Original Pi native input capability did not become available')

    @staticmethod
    def input(job,message='owner-message'):
        return {'messageId':message,'protocol':'pi_rpc_v1','expectedPromptRequestId':job['promptRequestId'],
            'sessionId':job['sessionId'],'threadId':job['threadId'],'jobOperationId':job['operationId'],'text':'Owner private correction'}

    def message(self,job,message='owner-message',expected=None):
        deadline=time.monotonic()+2
        while time.monotonic()<deadline:
            status,response=self.http('/v1/jobs/'+job['id']+'/messages/'+message)
            self.assertEqual(200,status,response);result=response['result']
            if (result['status']==expected if expected is not None else result['status'] not in {'queued','sending','not_found'}):return result
            time.sleep(.005)
        self.fail('Original Pi message receipt did not settle')

    def test_http_live_input_dedup_uses_same_original_sql_job_and_exact_prompt_receipt(self):
        job=self.active('steer-live');body=self.input(job);path='/v1/jobs/'+job['id']+'/steer'
        self.assertEqual(200,self.http(path,body)[0]);receipt=self.message(job);final=self.finish(job)
        self.assertEqual('accepted',receipt['status']);self.assertEqual('native_queue',receipt['delivery']);self.assertEqual('unknown',receipt['consumed'])
        for field in ('sessionId','threadId','jobOperationId','expectedPromptRequestId','protocol'):
            self.assertEqual(body[field],receipt[field])
        self.assertEqual(job['id'],receipt['jobId']);self.assertEqual(body['messageId'],receipt['operationId']);self.assertNotIn('turnId',receipt)
        self.assertEqual('completed',final['status']);self.assertEqual('actual live fixture response',final['output'])
        self.assertTrue(final['nativeQueueEmptyConfirmed']);self.assertTrue(final['processCleanupProof']['processGroupStoppedVerified'])
        self.assertTrue(final['controls']['threadResume']);self.assertFalse(final['controls']['inputSteering'])
        self.assertEqual(receipt,self.http(path,body)[1]['result'])
        self.assertEqual(409,self.http(path,{**body,'text':'Different input'})[0])
        self.assertEqual('1',(self.workspace/'pi-steer-count').read_text());self.assertEqual('1',(self.workspace/'pi-prompt-count').read_text())
        stored=self.manager.ledger.execute('SELECT fingerprint,receipt FROM steer_messages').fetchall()
        self.assertEqual(1,len(stored));self.assertNotIn('Owner private correction',str(stored))
        self.assertEqual(1,len(self.manager.jobs));self.assertFalse(self.manager.resources)

    def test_http_stale_prompt_session_or_mixed_turn_cannot_reserve_or_send_input(self):
        job=self.active();body=self.input(job);path='/v1/jobs/'+job['id']+'/steer'
        for field in ('sessionId','threadId','jobOperationId','expectedPromptRequestId'):
            with self.subTest(field=field):self.assertEqual(409,self.http(path,{**body,field:'stale-identity'})[0])
        self.assertEqual(409,self.http(path,{**body,'expectedTurnId':job['promptRequestId']})[0])
        self.assertEqual(409,self.http(path,{**body,'protocol':'codex_app_server'})[0])
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM steer_messages').fetchone()[0])
        self.assertFalse((self.workspace/'pi-steer-count').exists())
        with self.manager.lock:
            stopping=self.manager.cancel(job['id'])
            self.assertFalse(stopping['controls']['inputSteering']);self.assertFalse(stopping['capabilities']['inputSteering'])
        self.assertEqual('cancelled',self.finish(job)['status'])

    def test_http_late_ack_resolves_same_unknown_receipt_without_new_input_or_budget(self):
        job=self.active('steer-late-ack');body=self.input(job);path='/v1/jobs/'+job['id']+'/steer'
        driver=self.manager.drivers[job['id']];deadline=driver.deadline
        self.assertEqual(200,self.http(path,body)[0]);until=time.monotonic()+2
        while not driver.steer_replies and time.monotonic()<until:time.sleep(.005)
        self.assertTrue(driver.steer_replies);next(iter(driver.steer_replies.values()))['deadline']=time.monotonic()-1
        self.assertEqual('unknown',self.message(job,expected='unknown')['status'])
        self.assertFalse(self.manager.get(job['id'])['controls']['inputSteering'])
        self.assertEqual(409,self.http(path,{**body,'messageId':'replacement-message'})[0])
        self.assertEqual('unknown',self.http(path,body)[1]['result']['status'])
        (self.workspace/'release-pi-steer-ack').touch();self.message(job,expected='accepted')
        self.assertTrue(self.manager.get(job['id'])['controls']['inputSteering']);self.assertEqual(deadline,driver.deadline)
        self.assertEqual('1',(self.workspace/'pi-steer-count').read_text());self.assertEqual(1,len(self.manager.jobs))
        self.manager.cancel(job['id']);self.assertTrue(self.finish(job)['nativeQueueEmptyConfirmed'])

    def test_http_lost_ack_remains_unknown_after_original_process_cleanup_and_restart(self):
        job=self.active('steer-disconnect');body=self.input(job);path='/v1/jobs/'+job['id']+'/steer'
        self.assertEqual(200,self.http(path,body)[0]);self.assertEqual('unknown',self.message(job)['status'])
        final=self.finish(job);self.assertEqual('unknown',final['status']);self.assertFalse(final['controls']['threadResume'])
        self.assertTrue(final['processCleanupProof']['processGroupStoppedVerified'])
        with self.assertRaises(RequestError):self.manager.submit(self.request('replacement-job',threadId=job['threadId'],resumeJobId=job['id']))
        self.manager.close();self.launch.stop();self.manager=JobManager(self.config,TOKEN);self.server.manager=self.manager
        self.launch=patch.object(self.manager,'_launch',side_effect=lambda env,argv:([sys.executable,str(self.fixture),self.mode,*argv[1:]],str(self.workspace)));self.launch.start()
        self.assertEqual('unknown',self.http(path,body)[1]['result']['status'])
        self.assertEqual(409,self.http(path,{**body,'messageId':'replacement-message'})[0])
        self.assertEqual('1',(self.workspace/'pi-steer-count').read_text());self.assertEqual('1',(self.workspace/'pi-prompt-count').read_text())

    def test_original_sql_64_limit_and_unknown_receipt_fence_apply_to_pi(self):
        job=self.active();body=self.input(job);path='/v1/jobs/'+job['id']+'/steer'
        with self.manager.lock:
            for index in range(64):
                self.manager.ledger.execute('INSERT INTO steer_messages VALUES (?,?,?,?,?)',
                    ('earlier-'+str(index),job['id'],'fixture-fingerprint',json.dumps({'status':'accepted'}),1))
            self.manager.ledger.commit()
        self.assertEqual(429,self.http(path,body)[0]);self.assertFalse((self.workspace/'pi-steer-count').exists())
        with self.manager.lock:
            self.manager.ledger.execute('UPDATE steer_messages SET receipt=? WHERE message_id=?',(json.dumps({'status':'unknown'}),'earlier-0'))
            self.manager.ledger.commit()
        self.assertFalse(self.manager.get(job['id'])['controls']['inputSteering'])
        self.assertEqual(409,self.http(path,body)[0]);self.manager.cancel(job['id'])
        final=self.finish(job);self.assertTrue(final['nativeQueueEmptyConfirmed']);self.assertFalse(final['controls']['threadResume'])
        self.assertEqual(64,self.manager.ledger.execute('SELECT count(*) FROM steer_messages').fetchone()[0])


if __name__=='__main__':unittest.main()
