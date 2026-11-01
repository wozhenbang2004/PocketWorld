"""Protocol fixtures only: these tests never contact a model or claim real Codex execution."""
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from codex_driver import CodexAppServerDriver, DriverError


FIXTURE = r'''
import json,sys
mode=sys.argv[1]
def emit(value):
    print(json.dumps(value),flush=True)
for line in sys.stdin:
    value=json.loads(line)
    method=value.get('method')
    if method=='initialize': emit({'id':value['id'],'result':{'userAgent':'protocol-fixture'}})
    elif method=='turn/steer':
        params=value['params']
        if params['threadId']!='fixture-thread':
            assert params['input']==[]
            emit({'id':value['id'],'error':{'code':-32601 if mode=='probe-no-steer' else -32600,'message':'unsupported' if mode=='probe-no-steer' else 'thread not found: '+params['threadId']}})
            continue
        assert params['expectedTurnId']=='fixture-turn'
        assert params['clientUserMessageId']
        assert params['input'][0]['type']=='text'
        from pathlib import Path
        count=Path('steer-count')
        count.write_text(count.read_text()+'1' if count.exists() else '1')
        if mode=='steer-complete-no-ack':
            emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})
            continue
        if mode=='steer-disconnect': sys.exit(0)
        if mode=='steer-no-ack': continue
        if mode=='steer-late-ack':
            import time
            time.sleep(.5)
        if mode=='steer-refused': emit({'id':value['id'],'error':{'code':-32600,'message':'no active turn to steer'}})
        else: emit({'id':value['id'],'result':{'turnId':'wrong-turn' if mode=='steer-wrong-ack' else 'fixture-turn'}})
        if mode=='steer':
            emit({'method':'item/agentMessage/delta','params':{'threadId':'fixture-thread','delta':'Actual steered fixture text'}})
            emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})
    elif method=='account/read':
        assert value['params']=={'refreshToken':False}
        emit({'id':value['id'],'result':{'requiresOpenaiAuth':True,'account':None if mode=='probe-no-account' else {'type':'chatgpt','email':'private-fixture@example.invalid','planType':'plus'}}})
    elif method=='model/list':
        emit({'id':value['id'],'result':{'data':[{'model':'fixture-model','displayName':'Fixture model','isDefault':True}]}})
    elif method=='config/read':
        if mode=='probe-old': emit({'id':value['id'],'error':{'code':-32601,'message':'fixture unsupported config/read'}})
        else: emit({'id':value['id'],'result':{'config':{'mcp_servers':{'fixture-server':{'command':'never-executed','enabled':True,'env':{'SECRET':'fixture-hidden'}}}}}})
    elif method=='mcpServerStatus/list':
        assert value['params']['threadId']=='fixture-thread'
        emit({'id':value['id'],'result':{'data':[{'tools':{'write':{}}}] if mode=='external-tools' else []}})
    elif method in {'thread/start','thread/resume'}:
        assert not mode.startswith('probe')
        if method=='thread/resume': assert value['params']['threadId']=='fixture-thread'
        assert value['params']['sandbox']=='readOnly'
        assert value['params']['approvalPolicy']=='unlessTrusted'
        if 'config' in value['params']:
            config=value['params']['config']
            assert config['mcp_servers']['fixture-server']['enabled'] is False
            assert config['features']['apps'] is False and config['features']['plugins'] is False
        emit({'id':value['id'],'result':{'thread':{'id':'fixture-thread'},'sandbox':{'type':'workspaceWrite' if mode=='wrong-sandbox' else 'readOnly'}}})
    elif method=='turn/start':
        from pathlib import Path
        count=Path('codex-turn-count')
        count.write_text(count.read_text()+'1' if count.exists() else '1')
        if mode=='start-no-identity': continue
        if mode=='late-start':
            import time
            time.sleep(.25)
        if mode not in {'start-ack-lost','final-without-start-ack'}:
            emit({'id':value['id'],'result':{'turn':{'id':'fixture-turn'}}})
        emit({'method':'turn/started','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn'}}})
        if mode=='final-without-start-ack':
            emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})
            continue
        if mode=='disconnect': sys.exit(0)
        if mode not in {'cancel','late-start','start-ack-lost'} and not mode.startswith('steer'):
            params={'threadId':'fixture-thread','turnId':'fixture-turn','command':'read project','reason':'fixture approval'}
            if mode=='network': params['networkApprovalContext']={'host':'example.invalid','protocol':'https'}
            emit({'id':42,'method':'item/commandExecution/requestApproval','params':params})
            if mode=='duplicate-approval': emit({'id':42,'method':'item/commandExecution/requestApproval','params':params})
    elif method=='turn/interrupt':
        emit({'id':value['id'],'result':{}})
        emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'interrupted'}}})
    elif value.get('id')==42 and 'result' in value:
        from pathlib import Path
        count=Path('codex-approval-count')
        count.write_text(count.read_text()+'1' if count.exists() else '1')
        emit({'method':'item/agentMessage/delta','params':{'delta':'fixture decision='+value['result']['decision']}})
        emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})
'''


class CodexDriverTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-codex-protocol-')
        self.root=Path(self.temp.name)
        self.fixture=self.root/'fixture.py'
        self.fixture.write_text(FIXTURE)
        self.processes=[]
        self.pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None: process.terminate()
            try: process.wait(timeout=2)
            except subprocess.TimeoutExpired: process.kill();process.wait()
            for stream in (process.stdin,process.stdout):
                if stream: stream.close()
        self.pool.shutdown(wait=True)
        self.temp.cleanup()

    def driver(self, mode, stopped=None, restricted=False):
        process=subprocess.Popen([sys.executable,str(self.fixture),mode],cwd=str(self.root),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        self.processes.append(process)
        states=[]
        driver=CodexAppServerDriver(process,str(self.root),False,states.append,stopped or (lambda:False),5,restricted)
        return driver,states

    def pending(self, states):
        deadline=time.monotonic()+3
        while time.monotonic()<deadline:
            if states and states[-1].get('pendingApprovals'): return states[-1]['pendingApprovals'][0]
            time.sleep(.01)
        self.fail('No protocol approval arrived')

    def test_handshake_real_stdio_approval_and_final_turn_receipt(self):
        driver,states=self.driver('approval')
        result=self.pool.submit(driver.run,'Inspect this project')
        request=self.pending(states)
        self.assertEqual('42',request['requestId'])
        self.assertTrue(request['canAccept'])
        self.assertFalse(result.done())
        driver.decide('42','decline')
        receipt=result.result(timeout=3)
        self.assertEqual('completed',receipt['status'])
        self.assertEqual('fixture-thread',receipt['threadId'])
        self.assertEqual('fixture decision=decline',receipt['output'])

    def test_network_escalation_cannot_be_accepted_as_workspace_approval(self):
        driver,states=self.driver('network')
        result=self.pool.submit(driver.run,'Inspect')
        request=self.pending(states)
        self.assertFalse(request['canAccept'])
        with self.assertRaises(DriverError): driver.decide('42','accept')
        driver.decide('42','decline')
        self.assertIn('decision=decline',result.result(timeout=3)['output'])

    def test_cancel_uses_turn_interrupt_and_waits_for_actual_final_receipt(self):
        stopped=threading.Event()
        driver,states=self.driver('cancel',stopped.is_set)
        result=self.pool.submit(driver.run,'Inspect')
        deadline=time.monotonic()+3
        while not any(state.get('turnId') for state in states) and time.monotonic()<deadline: time.sleep(.01)
        stopped.set()
        self.assertEqual('cancelled',result.result(timeout=3)['status'])
        self.assertTrue(driver.interrupt_sent)

    def test_transport_exit_without_final_receipt_is_not_success(self):
        driver,_=self.driver('disconnect')
        with self.assertRaises(DriverError): driver.run('Inspect')

    def test_probe_reads_real_stdio_catalog_without_creating_a_thread_or_leaking_account(self):
        driver,states=self.driver('probe')
        result=driver.probe('fixture-model')
        self.assertTrue(result['available'])
        self.assertTrue(result['protocolVerified'])
        self.assertFalse(result['modelInferenceVerified'])
        self.assertIsNone(driver.thread_id)
        self.assertEqual([],states)
        self.assertNotIn('private-fixture',json.dumps(result))
        self.assertEqual('fixture-model',result['models'][0]['id'])

    def test_probe_rejects_missing_account_and_unadvertised_model(self):
        driver,_=self.driver('probe-no-account')
        self.assertFalse(driver.probe('fixture-model')['available'])
        driver,_=self.driver('probe')
        result=driver.probe('unadvertised')
        self.assertFalse(result['available'])
        self.assertFalse(result['selectedModelAvailable'])

    def test_restricted_team_rejects_unconfirmed_sandbox_and_live_external_tools_before_turn(self):
        for mode in ['wrong-sandbox','external-tools']:
            driver,_=self.driver(mode,restricted=True)
            with self.assertRaises(DriverError): driver.run('Inspect')
            self.assertIsNone(driver.turn_id)

    def active(self, driver, states):
        future=self.pool.submit(driver.run,'Inspect')
        deadline=time.monotonic()+3
        while not driver.can_steer() and time.monotonic()<deadline: time.sleep(.01)
        self.assertTrue(driver.can_steer())
        return future

    def test_steering_uses_exact_turn_and_does_not_start_a_second_turn(self):
        driver,states=self.driver('steer')
        future=self.active(driver,states)
        receipts=[]
        driver.steer({'messageId':'message-1','expectedTurnId':'fixture-turn','text':'Focus on tests'},lambda status,fields:receipts.append((status,fields)))
        self.assertEqual('completed',future.result(timeout=3)['status'])
        self.assertEqual(['sending','accepted'],[state for state,_ in receipts])
        self.assertEqual('fixture-turn',receipts[-1][1]['turnId'])
        self.assertEqual('1',(self.root/'steer-count').read_text())
        self.assertFalse(driver.can_steer())

    def test_steering_rejects_stale_turn_before_pipe_dispatch(self):
        stopped=threading.Event();driver,states=self.driver('steer-wait',stopped.is_set)
        future=self.active(driver,states)
        with self.assertRaises(DriverError): driver.steer({'messageId':'message-1','expectedTurnId':'stale-turn','text':'No'},lambda *_:None)
        self.assertFalse((self.root/'steer-count').exists())
        stopped.set();self.assertEqual('cancelled',future.result(timeout=3)['status'])

    def test_unsupported_steer_method_does_not_become_a_claimed_capability(self):
        driver,_=self.driver('probe-no-steer')
        result=driver.probe('fixture-model')
        self.assertTrue(result['available'])
        self.assertFalse(result['inputSteeringVerified'])

    def test_wrong_turn_ack_is_unknown_and_disables_further_steering(self):
        stopped=threading.Event();driver,states=self.driver('steer-wrong-ack',stopped.is_set)
        future=self.active(driver,states);receipts=[]
        driver.steer({'messageId':'message-1','expectedTurnId':'fixture-turn','text':'Inspect'},lambda status,fields:receipts.append((status,fields)))
        deadline=time.monotonic()+2
        while not any(status=='unknown' for status,_ in receipts) and time.monotonic()<deadline: time.sleep(.01)
        self.assertEqual('unknown',receipts[-1][0]);self.assertFalse(driver.can_steer())
        stopped.set();future.result(timeout=3)

    def test_late_matching_ack_reconciles_original_message_without_redispatch(self):
        stopped=threading.Event();driver,states=self.driver('steer-late-ack',stopped.is_set)
        future=self.active(driver,states);receipts=[]
        driver.steer({'messageId':'message-1','expectedTurnId':'fixture-turn','text':'Inspect'},lambda status,fields:receipts.append((status,fields)))
        try:
            deadline=time.monotonic()+2
            while not driver.steer_replies and time.monotonic()<deadline: time.sleep(.005)
            self.assertTrue(driver.steer_replies)
            next(iter(driver.steer_replies.values()))['deadline']=time.monotonic()-1
            while not any(status=='accepted' for status,_ in receipts) and time.monotonic()<deadline: time.sleep(.01)
            self.assertEqual(['sending','unknown','accepted'],[status for status,_ in receipts])
            self.assertTrue(driver.can_steer('fixture-turn'))
            self.assertEqual('1',(self.root/'steer-count').read_text())
        finally:
            stopped.set();future.result(timeout=3)

    def test_unrelated_started_event_cannot_rebind_owned_turn_or_output(self):
        stopped=threading.Event();driver,states=self.driver('steer-wait',stopped.is_set)
        future=self.active(driver,states)
        try:
            driver._event({'method':'turn/started','params':{'threadId':'fixture-thread','turn':{'id':'unowned-turn'}}})
            driver._event({'method':'item/agentMessage/delta','params':{'threadId':'fixture-thread','turnId':'unowned-turn','delta':'unowned text'}})
            self.assertEqual('fixture-turn',driver.turn_id)
            self.assertEqual('',driver.output)
            self.assertTrue(driver.can_steer('fixture-turn'))
        finally:
            # Cleanup must also work on the deliberately unsafe baseline.
            driver.turn_id='fixture-turn';stopped.set();future.result(timeout=3)

    def test_stop_after_durable_sending_receipt_prevents_pipe_dispatch(self):
        stopped=threading.Event();driver,states=self.driver('steer-wait',stopped.is_set)
        future=self.active(driver,states);receipts=[]
        def receipt(status,fields):
            receipts.append(status)
            if status=='sending': stopped.set()
        driver.steer({'messageId':'message-1','expectedTurnId':'fixture-turn','text':'Must not send'},receipt)
        self.assertEqual('cancelled',future.result(timeout=3)['status'])
        self.assertFalse((self.root/'steer-count').exists())
        self.assertEqual(['sending','rejected'],receipts)

    def test_stop_before_turn_dispatch_does_not_create_a_turn(self):
        stopped=threading.Event()
        driver,states=self.driver('cancel',stopped.is_set)
        def state(value):
            states.append(value)
            if value.get('threadId') and not value.get('turnId'):
                stopped.set()
        driver.on_state=state
        with self.assertRaises(DriverError): driver.run('Must not start')
        self.assertFalse(driver.turn_dispatched)
        self.assertFalse((self.root/'codex-turn-count').exists())

    def test_cancel_before_turn_ack_waits_for_identity_and_interrupts_original_once(self):
        stopped=threading.Event()
        driver,states=self.driver('late-start',stopped.is_set)
        future=self.pool.submit(driver.run,'Inspect once')
        deadline=time.monotonic()+2
        while not (self.root/'codex-turn-count').exists() and time.monotonic()<deadline: time.sleep(.005)
        self.assertTrue(driver.turn_dispatched)
        self.assertIsNone(driver.turn_id)
        stopped.set()
        result=future.result(timeout=3)
        self.assertEqual('cancelled',result['status'])
        self.assertTrue(driver.interrupt_sent)
        self.assertEqual('fixture-turn',result['turnId'])
        self.assertEqual('1',(self.root/'codex-turn-count').read_text())

    def test_cancel_with_missing_turn_identity_remains_unconfirmed_and_never_resends_start(self):
        stopped=threading.Event()
        driver,_=self.driver('start-no-identity',stopped.is_set)
        future=self.pool.submit(driver.run,'Inspect once')
        deadline=time.monotonic()+2
        while not (self.root/'codex-turn-count').exists() and time.monotonic()<deadline: time.sleep(.005)
        stopped.set()
        driver.interrupt_deadline=time.monotonic()+.1
        with self.assertRaises(DriverError): future.result(timeout=2)
        self.assertFalse(driver.interrupt_sent)
        self.assertEqual('1',(self.root/'codex-turn-count').read_text())

    def test_lost_start_ack_can_be_reconciled_by_actual_matching_interrupted_terminal(self):
        stopped=threading.Event()
        driver,states=self.driver('start-ack-lost',stopped.is_set)
        future=self.pool.submit(driver.run,'Inspect once')
        deadline=time.monotonic()+2
        while not driver.turn_id and time.monotonic()<deadline: time.sleep(.005)
        stopped.set()
        result=future.result(timeout=3)
        self.assertEqual('cancelled',result['status'])
        self.assertFalse(result['turnStartAcknowledged'])
        self.assertEqual('fixture-turn',result['turnId'])
        self.assertEqual('1',(self.root/'codex-turn-count').read_text())

    def test_actual_terminal_without_start_ack_is_not_a_fabricated_ack_or_second_turn(self):
        driver,_=self.driver('final-without-start-ack')
        result=driver.run('Inspect once')
        self.assertEqual('completed',result['status'])
        self.assertFalse(result['turnStartAcknowledged'])
        self.assertEqual('1',(self.root/'codex-turn-count').read_text())

    def test_approval_identity_is_single_use_and_records_sent_not_business_success(self):
        driver,states=self.driver('approval')
        future=self.pool.submit(driver.run,'Inspect')
        pending=self.pending(states)
        for invalid in [None,[],{}]:
            with self.assertRaises(DriverError): driver.decide(pending['requestId'],invalid)
        driver.decide(pending['requestId'],'accept')
        with self.assertRaises(DriverError): driver.decide(pending['requestId'],'accept')
        result=future.result(timeout=3)
        self.assertEqual('completed',result['status'])
        self.assertEqual('1',(self.root/'codex-approval-count').read_text())
        self.assertEqual([{'requestId':'42','decision':'accept','status':'sent'}],result['approvalReceipts'])

    def test_duplicate_wire_approval_does_not_replace_an_owner_review(self):
        driver,_=self.driver('duplicate-approval')
        with self.assertRaises(DriverError): driver.run('Inspect')
        self.assertFalse((self.root/'codex-approval-count').exists())

    def test_stop_while_saving_approval_intent_cannot_send_accept(self):
        stopped=threading.Event()
        driver,states=self.driver('approval',stopped.is_set)
        def save(value):
            states.append(value)
            if value.get('approvalReceipts'):
                stopped.set()
        driver.on_state=save
        future=self.pool.submit(driver.run,'Inspect')
        driver.decide(self.pending(states)['requestId'],'accept')
        result=future.result(timeout=3)
        self.assertIn('decision=decline',result['output'])
        self.assertEqual('decline',result['approvalReceipts'][0]['decision'])


if __name__=='__main__': unittest.main()
