"""Current official field shapes over real original pipes; no vendor inference."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import time
from pocket_companion import JobManager, RequestError

from codex_driver import CodexAppServerDriver, DriverError
from test_codex_driver import FIXTURE

PEER=FIXTURE.replace("if 'config' in value['params']:","if 'mcp_servers' in value['params'].get('config',{}):",1)
PEER=PEER.replace("{'userAgent':'protocol-fixture'}","{'userAgent':'codex_cli_rs/0.166.0-alpha.1'}",1)
PEER=PEER.replace("{'model':'fixture-model','displayName':'Fixture model','isDefault':True}","{'model':'fixture-model','displayName':'Fixture model','isDefault':True,**({} if mode=='missing-options' else {'defaultReasoningEffort':'medium','supportedReasoningEfforts':[{'reasoningEffort':'medium','description':'normal'},{'reasoningEffort':'ultra','description':'native advertised, not hard coded'}]})}",1)
needle="        emit({'id':value['id'],'result':{'thread':{'id':'fixture-thread'},'sandbox':{'type':'workspaceWrite' if mode=='wrong-sandbox' else 'readOnly'}}})"
replacement="""        selected=value['params'].get('config',{}).get('model_reasoning_effort')
        assert value['params'].get('model')=='fixture-model'
        emit({'id':value['id'],'result':{'thread':{'id':'fixture-thread'},'sandbox':{'type':'readOnly'},
             'model':'different-native-model' if mode=='wrong-model' else 'fixture-model',
             'reasoningEffort':'medium' if mode=='wrong-effort' else selected}})"""
assert needle in PEER;PEER=PEER.replace(needle,replacement,1)
needle="    elif method=='turn/start':"
replacement="""    elif method=='turn/start':
        assert value['params']['model']=='fixture-model'
        if mode!='context-only':assert value['params']['effort']=='ultra'
"""
PEER=PEER.replace(needle,replacement,1)
needle="        if mode=='final-without-start-ack':"
replacement="""        emit({'method':'thread/tokenUsage/updated','params':{'threadId':'fixture-thread','turnId':'fixture-turn','tokenUsage':{'modelContextWindow':None if mode=='unknown-window' else 262144,'total':{'inputTokens':33,'totalTokens':44}}}})
        emit({'method':'thread/tokenUsage/updated','params':{'threadId':'foreign-thread','turnId':'fixture-turn','tokenUsage':{'modelContextWindow':999999}}})
        if mode=='rerouted':emit({'method':'model/rerouted','params':{'threadId':'fixture-thread','turnId':'fixture-turn','fromModel':'fixture-model','toModel':'native-fallback','reason':'rateLimit'}})
        if mode=='settings':emit({'method':'thread/settings/updated','params':{'threadId':'fixture-thread','threadSettings':{'model':'native-next-model','effort':'medium','sandboxPolicy':{'type':'dangerFullAccess'}}}})
"""+needle
PEER=PEER.replace(needle,replacement,1)
PEER=PEER.replace("        if mode=='final-without-start-ack':", "        if mode in {'rerouted','settings'}:emit({'method':'thread/tokenUsage/updated','params':{'threadId':'fixture-thread','turnId':'fixture-turn','tokenUsage':{'modelContextWindow':262144}}})\n        if mode=='final-without-start-ack':",1)

PEER=PEER.replace("        if mode=='final-without-start-ack':", "        emit({'method':'turn/completed','params':{'threadId':'fixture-thread','turn':{'id':'fixture-turn','status':'completed'}}})\n        continue\n        if mode=='final-without-start-ack':",1)


class CodexModelOptionsTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-codex-model-options-');self.root=Path(self.temp.name);self.peer=self.root/'peer.py';self.peer.write_text(PEER);self.processes=[];self.drivers=[]

    def tearDown(self):
        for driver in self.drivers:driver.close_steering()
        for process in self.processes:
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            for stream in (process.stdin,process.stdout):stream.close()
        self.temp.cleanup()

    def driver(self,mode='success'):
        process=subprocess.Popen([sys.executable,str(self.peer),mode],cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0);self.processes.append(process);states=[]
        driver=CodexAppServerDriver(process,str(self.root),False,states.append,lambda:False,5);self.drivers.append(driver);return driver,states

    def test_probe_enumerates_actual_native_efforts_and_runtime_source_without_starting_work(self):
        driver,_=self.driver('probe');actual=driver.probe('fixture-model','ultra');self.assertTrue(actual['available']);self.assertTrue(actual['selectedEffortAvailable']);self.assertEqual('0.166.0-alpha.1',actual['modelOptions']['runtimeVersion']);self.assertRegex(actual['modelOptions']['digest'],r'^[a-f0-9]{64}$');self.assertEqual(['medium','ultra'],[v['id'] for v in actual['models'][0]['efforts']]);self.assertFalse((self.root/'codex-turn-count').exists());self.assertNotIn('private-fixture',json.dumps(actual))

    def test_unadvertised_effort_is_not_invented_or_mapped_to_a_nearby_option(self):
        driver,_=self.driver('probe');actual=driver.probe('fixture-model','max');self.assertFalse(actual['available']);self.assertFalse(actual['selectedEffortAvailable']);self.assertFalse((self.root/'codex-turn-count').exists())

    def test_missing_effort_fields_stay_unknown_not_an_empty_claim_of_full_support(self):
        driver,_=self.driver('missing-options');actual=driver.probe('fixture-model','ultra');self.assertFalse(actual['available']);self.assertEqual('not_reported',actual['models'][0]['effortCapability']);self.assertEqual([],actual['models'][0]['efforts'])

    def test_exact_model_effort_is_read_back_before_dispatch_and_sent_to_original_turn(self):
        driver,_=self.driver();actual=driver.run('actual typed request',model='fixture-model',effort='ultra');runtime=actual['modelRuntime'];self.assertEqual('completed',actual['status']);self.assertEqual('fixture-model',runtime['reportedModel']);self.assertEqual('ultra',runtime['reportedEffort']);self.assertEqual('thread/start.response',runtime['configurationSource']);self.assertEqual('1',(self.root/'codex-turn-count').read_text());self.assertEqual(262144,runtime['contextWindow']['tokens']);self.assertEqual('known',runtime['contextWindow']['status']);self.assertEqual('fixture-model',runtime['contextWindow']['reportedModel']);self.assertFalse(driver.write_allowed)

    def test_different_native_configuration_or_unadvertised_value_never_starts_a_turn(self):
        for mode,effort in [('wrong-model','ultra'),('wrong-effort','ultra'),('success','max')]:
            with self.subTest(mode=mode):
                driver,_=self.driver(mode)
                with self.assertRaises(DriverError):driver.run('must not start',model='fixture-model',effort=effort)
                self.assertFalse(driver.turn_dispatched)
        self.assertFalse((self.root/'codex-turn-count').exists())

    def test_window_unknown_is_not_zero_or_a_guessed_default(self):
        driver,_=self.driver('unknown-window');actual=driver.run('typed request',model='fixture-model',effort='ultra');window=actual['modelRuntime']['contextWindow'];self.assertIsNone(window['tokens']);self.assertEqual('unknown',window['status']);self.assertIsNone(window['source'])

    def test_native_model_change_clears_old_window_and_never_changes_tool_authority(self):
        for mode,model in [('rerouted','native-fallback'),('settings','native-next-model')]:
            with self.subTest(mode=mode):
                driver,_=self.driver(mode);actual=driver.run('typed request',model='fixture-model',effort='ultra');runtime=actual['modelRuntime'];self.assertEqual(model,runtime['reportedModel']);self.assertIsNone(runtime['contextWindow']['tokens']);self.assertEqual('unknown',runtime['contextWindow']['status']);self.assertEqual('model_changed_within_turn_usage_has_no_model_tag',runtime['contextWindow']['unknownReason']);self.assertFalse(driver.write_allowed)

    def test_unset_effort_preserves_original_run_but_context_still_uses_native_model(self):
        driver,_=self.driver('context-only');actual=driver.run('typed request',model='fixture-model');self.assertIsNone(actual['modelRuntime']['requestedEffort']);self.assertIsNone(actual['modelRuntime']['reportedEffort']);self.assertEqual(262144,actual['modelRuntime']['contextWindow']['tokens'])


class CodexModelManagerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-codex-native-model-manager-');self.root=Path(self.temp.name);self.workspace=self.root/'workspace';self.workspace.mkdir();self.peer=self.root/'peer.py';self.peer.write_text(PEER);self.mode='success'
        self.manager=JobManager({'stateDir':str(self.root/'state'),'environments':[{'id':'computer','root':str(self.workspace),'workers':['codex_app_server']}]},'native-model-options-fixture-token-longer-than-32-characters')
        self.launch=patch.object(self.manager,'_launch',side_effect=lambda env,argv:([sys.executable,str(self.peer),self.mode],str(self.workspace)));self.launch.start()
    def tearDown(self):
        self.manager.close();self.launch.stop();self.temp.cleanup()
    def request(self,operation='first',**extra):
        return {'operationId':operation,'environmentId':'computer','worker':'codex_app_server','model':'fixture-model','effort':'ultra','prompt':'Owner selected actual coding model effort','timeoutSeconds':5,**extra}
    def finish(self,job):
        until=time.monotonic()+5
        while time.monotonic()<until:
            actual=self.manager.get(job['id'])
            if actual['status'] in {'completed','blocked','unknown','failed','cancelled'} and actual.get('dispatchFenceFinal'):return actual
            time.sleep(.01)
        self.fail(str(actual))
    def test_original_probe_and_managed_first_then_settled_continuation_recheck_native_options(self):
        probe=self.manager.probe_worker({'environmentId':'computer','worker':'codex_app_server','model':'fixture-model','effort':'ultra'});self.assertTrue(probe['selectedEffortAvailable']);self.assertFalse((self.workspace/'codex-turn-count').exists())
        body=self.request();first=self.finish(self.manager.submit(body));self.assertEqual('completed',first['status']);self.assertEqual('ultra',first['modelRuntime']['reportedEffort']);self.assertEqual(262144,first['modelRuntime']['contextWindow']['tokens']);self.assertEqual(first['id'],self.manager.submit(body)['id'])
        next_request=self.request('second',threadId=first['threadId'],resumeJobId=first['id']);next_request.pop('effort');next_request.pop('model');second=self.finish(self.manager.submit(next_request));self.assertEqual('completed',second['status']);self.assertEqual('ultra',second['effort']);self.assertEqual('thread/resume.response',second['modelRuntime']['configurationSource']);self.assertEqual('11',(self.workspace/'codex-turn-count').read_text());self.assertEqual(2,len(self.manager.jobs))
    def test_unadvertised_effort_blocks_original_job_before_any_model_turn_and_is_not_replayed(self):
        body=self.request(effort='unadvertised');actual=self.finish(self.manager.submit(body));self.assertEqual('blocked',actual['status']);self.assertEqual(actual['id'],self.manager.submit(body)['id']);self.assertFalse((self.workspace/'codex-turn-count').exists());self.assertFalse(actual['controls']['threadResume'])


if __name__=='__main__':unittest.main()
