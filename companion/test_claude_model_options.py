"""Native existing-init observations only; no new Claude probe/process or model calls."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from pocket_companion import JobManager, RequestError
from claude_driver import ClaudeStreamDriver, DriverError, command, inspect_cli, REQUIRED_FLAGS
from test_claude_driver import FIXTURE
import uuid

PEER=FIXTURE.replace("'request_id':'wrong-id' if mode=='wrong-handshake' else value['request_id'],'response':{}", "'request_id':'wrong-id' if mode=='wrong-handshake' else value['request_id'],'response':{'claude_code_version':'2.1.259','account':{'email':'NEVER_COPY_ACCOUNT'},'models':[{'value':'fixture-model','resolvedModel':'fixture-model','displayName':'Fixture model','supportsEffort':True,'supportedEffortLevels':['medium','high']}]} ",1)
PEER=PEER.replace("'result':'真实 fixture response', 'permission_denials':[]", "'result':'真实 fixture response', 'permission_denials':[], 'modelUsage':{'other-model':{'contextWindow':99999}} if mode=='different-model-window' else {'fixture-model':{'contextWindow':200000},'child-model':{'contextWindow':100000}} if mode=='mixed-window' else {'fixture-model':{'contextWindow':None if mode=='unknown-window' else 200000,'maxOutputTokens':8192,'costUSD':123.45}}",1)


class ClaudeModelOptionsTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='pocket-claude-existing-init-model-');self.root=Path(self.temp.name);self.peer=self.root/'peer.py';self.peer.write_text(PEER);self.processes=[];self.drivers=[]
    def tearDown(self):
        for driver in self.drivers:driver.close_steering()
        for process in self.processes:
            if process.poll() is None:process.terminate()
            try:process.wait(timeout=2)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            for stream in (process.stdin,process.stdout):stream.close()
        self.temp.cleanup()
    def driver(self,mode='success',effort=None):
        native=str(uuid.uuid4());argv=command(native,False,'fixture-model',False,effort)
        process=subprocess.Popen([sys.executable,str(self.peer),mode,*argv[1:]],cwd=self.root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0);self.processes.append(process);states=[]
        driver=ClaudeStreamDriver(process,native,states.append,lambda:False,5,cwd=str(self.root));self.drivers.append(driver);return driver,states
    def test_only_the_original_initialize_supplies_native_catalog_and_request_is_not_effective_readback(self):
        driver,states=self.driver(effort='high');actual=driver.run('original approved job',model='fixture-model',effort='high');self.assertEqual('completed',actual['status']);options=actual['modelOptions'];self.assertEqual('2.1.259',options['runtimeVersion']);self.assertEqual(['medium','high'],[row['id'] for row in options['models'][0]['efforts']]);self.assertRegex(options['digest'],r'^[a-f0-9]{64}$');runtime=actual['modelRuntime'];self.assertEqual('high',runtime['requestedEffort']);self.assertIsNone(runtime['reportedEffort']);self.assertEqual('unknown_stream_sdk_omits_effective_effort',runtime['effortReadback']);self.assertEqual(200000,runtime['contextWindow']['tokens']);self.assertNotIn('NEVER_COPY_ACCOUNT',json.dumps(states));self.assertNotIn('123.45',json.dumps(actual));self.assertEqual('1',(self.root/'claude-prompt-count').read_text())
    def test_session_effort_flag_is_optional_and_never_persisted_as_settings(self):
        args=command(str(uuid.uuid4()),False,'fixture-model',False,'high');self.assertEqual(['--effort','high'],args[-2:]);settings=json.loads(args[args.index('--settings')+1]);self.assertNotIn('effortLevel',settings);old=inspect_cli('2.1.259',' '.join(REQUIRED_FLAGS));self.assertTrue(old['available']);self.assertFalse(old['effortFlagVerified']);self.assertTrue(inspect_cli('2.1.259',' '.join(REQUIRED_FLAGS)+' --effort')['effortFlagVerified'])
    def test_unadvertised_effort_or_stale_catalog_never_sends_prompt(self):
        for effort,digest in [('max',None),('high','0'*64)]:
            with self.subTest(effort=effort):
                driver,_=self.driver(effort=effort)
                with self.assertRaises(DriverError):driver.run('not yet dispatched',model='fixture-model',effort=effort,model_options_digest=digest)
                self.assertFalse(driver.turn_dispatched)
        self.assertFalse((self.root/'claude-prompt-count').exists())
    def test_exact_existing_catalog_digest_is_revalidated_by_next_original_initialize(self):
        first,_=self.driver();actual=first.run('first',model='fixture-model');digest=actual['modelOptions']['digest'];second,_=self.driver(effort='medium');next_result=second.run('second',model='fixture-model',effort='medium',model_options_digest=digest);self.assertEqual('completed',next_result['status']);self.assertEqual(digest,next_result['modelOptions']['digest']);self.assertEqual('11',(self.root/'claude-prompt-count').read_text())
    def test_missing_or_multi_model_window_is_unknown_not_borrowed_from_parent_child_or_defaults(self):
        for mode in ['unknown-window','mixed-window','different-model-window']:
            with self.subTest(mode=mode):
                driver,_=self.driver(mode);actual=driver.run('original job',model='fixture-model');window=actual['modelRuntime']['contextWindow'];self.assertEqual('unknown',window['status']);self.assertIsNone(window['tokens']);self.assertIsNone(window['source'])
    def test_claude_effort_catalog_probe_is_rejected_before_any_extra_cli_start(self):
        manager=JobManager({'stateDir':str(self.root/'state'),'environments':[{'id':'computer','root':str(self.root),'workers':['claude']}]},'native-model-catalog-probe-fixture-token-longer-than-32-characters')
        try:
            with patch.object(manager,'_cli_text',side_effect=AssertionError('must not start a new CLI')) as cli:
                with self.assertRaises(RequestError):manager.probe_worker({'environmentId':'computer','worker':'claude','effort':'high'})
                cli.assert_not_called()
            self.assertEqual({},manager.jobs)
        finally:manager.close()

    def test_legacy_initialize_without_models_does_not_invent_effort_support(self):
        self.peer.write_text(FIXTURE);driver,_=self.driver();actual=driver.run('legacy approved job',model='fixture-model');self.assertIsNone(actual['modelOptions']);self.assertEqual('unknown',actual['modelRuntime']['contextWindow']['status'])


if __name__=='__main__':unittest.main()
