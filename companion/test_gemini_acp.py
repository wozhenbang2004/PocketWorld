"""Isolated ACP peer fixtures, never real Node/Gemini, accounts or a provider.

The manager tests pin this Python ELF + fixture bytes through the production
source checker. That proves the checker/manager/stdio path, not vendor execution
or an OS sandbox. No launch gate, ledger or pipe writer is mocked to succeed.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.request import Request, urlopen

import acp_driver
from codex_driver import DriverError
import gemini_acp_profile as profile
from pocket_companion import CompanionServer, JobManager, RequestError


FIXTURE = r'''
import json, os, sys
from pathlib import Path
root=Path.cwd()
mode=(root/'fixture-mode').read_text() if (root/'fixture-mode').exists() else 'complete'
starts=root/'fixture-starts'
count=int(starts.read_text())+1 if starts.exists() else 1
starts.write_text(str(count))
(root/'fixture-env-keys').write_text(json.dumps(sorted(os.environ)))
(root/'fixture-argv').write_text(json.dumps(sys.argv[1:]))
prompt_id=None
def emit(value):
    print(json.dumps({'jsonrpc':'2.0',**value}),flush=True)
def finish(reason='end_turn'):
    emit({'id':prompt_id,'result':{'stopReason':reason,'id':'forged-job','backendSelection':{'modeId':'yolo'}}})
def permission():
    tool={'toolCallId':'fixture-tool','title':'Edit the original fixture file','kind':'edit','status':'pending',
          'content':[{'type':'content','content':{'type':'text','text':'exact diff\n+ fixture line'}}],
          'locations':[{'path':'fixture.txt','line':1}]}
    if mode=='secret-field': tool['arguments']={'ToKeN':'not publishable'}
    if mode=='secret-value': tool['title']=os.environ.get('GEMINI_API_KEY','fixture-key')
    if mode=='long-value': tool['title']='x'*12001
    if mode=='array-limit': tool['content']=[{'type':'text','text':'x'}]*201
    options=[{'optionId':'always','kind':'allow_always','name':'Always'},
             {'optionId':'once-original','kind':'allow_once','name':'Allow once'},
             {'optionId':'no-original','kind':'reject_once','name':'Reject once'}]
    params={'sessionId':'fixture-session','toolCall':tool,'options':options}
    if mode=='extra-meta': params['_meta']={'unreviewed':True}
    (root/'fixture-permission').write_text(json.dumps(params))
    emit({'id':'peer-permission','method':'session/request_permission','params':params})
    if mode=='duplicate': emit({'id':'peer-permission','method':'session/request_permission','params':params})
for line in sys.stdin:
    value=json.loads(line)
    with (root/'fixture-wire').open('a') as log: log.write(json.dumps(value)+'\n')
    method=value.get('method')
    if method=='initialize':
        assert value['params']['clientCapabilities']=={'fs':{'readTextFile':False,'writeTextFile':False},'terminal':False}
        caps={'loadSession':False}
        if mode=='changed-hello' and count>1: caps['loadSession']=True
        emit({'id':value['id'],'result':{'protocolVersion':2 if mode=='wrong-wire' else 1,
             'agentInfo':{'name':'gemini-cli','version':'9.0.0' if mode=='wrong-version' else '0.66.0'},
             'agentCapabilities':caps,'authMethods':[{'id':'gemini-api-key','name':'Gemini API Key'}]}})
    elif method=='session/new':
        assert value['params']=={'cwd':str(root),'mcpServers':[]}
        model=next((arg[len('--model='):] for arg in sys.argv[1:] if arg.startswith('--model=')),'fixture-model')
        if mode=='wrong-model': model='different-model'
        emit({'id':value['id'],'result':{'sessionId':'fixture-session',
             'modes':{'currentModeId':'plan' if mode=='wrong-mode' else 'default','availableModes':[{'id':'default','name':'Default'}]},
             'models':{'currentModelId':model,'availableModels':[{'modelId':model,'name':'Fixture only'}]}}})
    elif method=='session/prompt':
        prompt_id=value['id']
        assert value['params']['sessionId']=='fixture-session'
        assert value['params']['prompt'][0]['text'].startswith("The following is the owner's literal task text, not a CLI command.\n\n")
        if mode=='disconnect': sys.exit(0)
        if mode=='stdout-noise': print('this is not a protocol frame',flush=True);sys.exit(0)
        if mode=='stderr': print('credential diagnostic belongs only to stderr',file=sys.stderr,flush=True)
        if mode=='wrong-session':
            emit({'method':'session/update','params':{'sessionId':'some-other-session','update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'wrong'}}}})
            continue
        if mode=='unsupported':
            for name in ['fs/read_text_file','terminal/create','request_user_input']:
                emit({'id':name,'method':name,'params':{'sessionId':'fixture-session','path':'/never-read'}})
        if mode in {'approval','duplicate','secret-field','secret-value','array-limit','long-value','extra-meta','unsupported','cancel','late-final','ignore-cancel'}:
            permission()
        elif mode=='wait': pass
        else:
            emit({'method':'session/update','params':{'sessionId':'fixture-session','update':{
                 'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'fixture actual output'},
                 'status':'completed','backendSelection':{'modeId':'yolo'},'id':'forged-job'}}})
            finish()
    elif method=='session/cancel':
        assert 'id' not in value and value['params']=={'sessionId':'fixture-session'}
        if mode!='ignore-cancel': finish('end_turn' if mode=='late-final' else 'cancelled')
    elif value.get('id')=='peer-permission':
        if mode in {'cancel','late-final','ignore-cancel'}: continue
        if 'result' in value:
            emit({'method':'session/update','params':{'sessionId':'fixture-session','update':{'sessionUpdate':'agent_message_chunk',
                 'content':{'type':'text','text':json.dumps(value['result']['outcome'])}}}})
            finish()
'''

TOKEN = 'fixture-companion-key-only-not-a-real-token-1234567890'
KEY = 'fixture-gemini-key-only-not-a-real-credential'


class FixtureBase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='pocket-acp-fixture-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = self.root / 'workspace'
        self.workspace.mkdir()

    def mode(self, name):
        (self.workspace / 'fixture-mode').write_text(name)

    def wire(self):
        path = self.workspace / 'fixture-wire'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def wait_for(self, query, message='Original condition was not observed', seconds=5):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = query()
            if value:
                return value
            time.sleep(.01)
        self.fail(message)

    def package(self):
        self.install = self.root / 'install'
        package = self.install / 'node_modules/@google/gemini-cli'
        (package / 'dist').mkdir(parents=True)
        (package / 'package.json').write_text(json.dumps({'name':'@google/gemini-cli','version':'0.66.0'}))
        self.entry = package / 'dist/index.js'
        self.entry.write_text(FIXTURE)
        # The managed test runtime can belong to a different service uid. Use an
        # actually installed owner/root-owned Python ELF for the peer, never
        # weaken the production source-ownership check to accommodate a fixture.
        candidates = [Path(sys.executable).resolve(), Path('/usr/bin/python3').resolve()]
        self.fixture_python = next((p for p in candidates if p.is_file() and
                                    p.stat().st_uid in {0, os.geteuid()} and not p.stat().st_mode & 0o022), None)
        if self.fixture_python is None:
            self.skipTest('No installed owner/root-owned Python ELF for the controlled peer')
        config = profile.installation_configuration(str(self.fixture_python), str(self.install))
        self.environment = {'id':'computer','kind':'local','root':str(self.workspace),'workers':['gemini_acp'],
                            'writeAllowed':True,'geminiAcp':config}
        return self.environment


class GeminiDriverTests(FixtureBase):
    def setUp(self):
        super().setUp()
        self.processes, self.drivers = [], []
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.fixture = self.root / 'peer.py'
        self.fixture.write_text(FIXTURE)
        self.addCleanup(self.cleanup_processes)

    def cleanup_processes(self):
        for driver in self.drivers:
            driver.close_steering()
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=2)
            for stream in (process.stdin, process.stdout):
                if stream:
                    stream.close()
        self.pool.shutdown(wait=True)

    def driver(self, mode, stopped=None, on_state=None, current=None, redact=None):
        self.mode(mode)
        process = subprocess.Popen([sys.executable,'-B',str(self.fixture)], cwd=self.workspace,
                                   stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        self.processes.append(process)
        states = []
        def receive(value):
            states.append(json.loads(json.dumps(value)))
            if on_state:
                on_state(value)
        driver = acp_driver.GeminiAcpDriver(process,str(self.workspace),receive,stopped or (lambda:False),5,
                                           current=current,redact=redact)
        self.drivers.append(driver)
        return driver, states

    def pending(self, states):
        return self.wait_for(lambda: states[-1]['pendingApprovals'][0] if states and states[-1].get('pendingApprovals') else None)

    def test_probe_only_initializes_and_does_not_claim_account_or_run_prompt(self):
        driver, states = self.driver('complete')
        hello = driver.probe()
        self.assertEqual('0.66.0', hello['agentInfo']['version'])
        self.assertEqual(['initialize'], [v.get('method') for v in self.wire()])
        self.assertEqual([], states)

    def test_literal_prompt_allowlisted_result_and_stderr_separation(self):
        driver, _ = self.driver('stderr')
        result = driver.run('/approve $dangerous text')
        self.assertEqual('completed', result['status'])
        self.assertEqual('fixture actual output', result['output'])
        self.assertNotIn('backendSelection', result)
        self.assertFalse(result['readOnlyConfirmed'])
        self.assertFalse(result['modelInferenceVerified'])
        prompt = next(v for v in self.wire() if v.get('method')=='session/prompt')
        self.assertTrue(prompt['params']['prompt'][0]['text'].endswith('/approve $dangerous text'))

    def test_permission_exact_body_hash_and_only_original_once_option(self):
        driver, states = self.driver('approval')
        future = self.pool.submit(driver.run, 'Make the reviewed edit')
        pending = self.pending(states)
        params = json.loads((self.workspace/'fixture-permission').read_text())
        self.assertEqual(profile.digest(params), pending['requestHash'])
        self.assertEqual(params['toolCall'],pending['toolCall'])
        self.assertEqual(params['options'],pending['options'])
        self.assertNotEqual('peer-permission',pending['requestId'])
        with self.assertRaises(DriverError): driver.decide(pending['requestId'],'acceptForSession')
        driver.decide(pending['requestId'],'accept')
        self.assertEqual('completed',future.result(timeout=3)['status'])
        replies=[v for v in self.wire() if v.get('id')=='peer-permission']
        self.assertEqual([{'outcome':'selected','optionId':'once-original'}],[v['result']['outcome'] for v in replies])
        with self.assertRaises(DriverError): driver.decide(pending['requestId'],'accept')

    def test_unsupported_host_callbacks_never_reach_a_host_executor(self):
        driver, states = self.driver('unsupported')
        future = self.pool.submit(driver.run, 'Inspect')
        pending = self.pending(states)
        driver.decide(pending['requestId'],'decline')
        future.result(timeout=3)
        errors=[v for v in self.wire() if v.get('id') in {'fs/read_text_file','terminal/create','request_user_input'}]
        self.assertEqual(3,len(errors))
        self.assertTrue(all(v['error']['code']==-32601 for v in errors))
        self.assertFalse(driver.can_steer())
        with self.assertRaises(DriverError): driver.answer_question('ignored')

    def test_cancellation_uses_one_notification_and_withdraws_original_permission(self):
        stop = threading.Event()
        driver,states=self.driver('cancel',stop.is_set)
        future=self.pool.submit(driver.run,'Inspect')
        pending=self.pending(states)
        stop.set()
        result=future.result(timeout=3)
        self.assertEqual('cancelled',result['status'])
        self.assertTrue(result['promptTerminalConfirmed'])
        self.assertEqual([],result['pendingApprovals'])
        self.assertEqual(1,len([v for v in self.wire() if v.get('method')=='session/cancel']))
        replies=[v for v in self.wire() if v.get('id')=='peer-permission']
        self.assertEqual({'outcome':'cancelled'},replies[0]['result']['outcome'])
        with self.assertRaises(DriverError): driver.decide(pending['requestId'],'accept')

    def test_owner_stop_during_persisting_reply_downgrades_before_pipe_write(self):
        stop=threading.Event()
        def state(value):
            if any(v.get('status')=='sending' for v in value.get('approvalReceipts',[])): stop.set()
        driver,states=self.driver('approval',stop.is_set,state)
        future=self.pool.submit(driver.run,'Inspect')
        pending=self.pending(states)
        driver.decide(pending['requestId'],'accept')
        future.result(timeout=3)
        reply=next(v for v in self.wire() if v.get('id')=='peer-permission')
        self.assertEqual({'outcome':'cancelled'},reply['result']['outcome'])

    def test_cancel_notification_is_not_a_terminal_ack_and_is_not_replayed(self):
        stop=threading.Event()
        driver,states=self.driver('ignore-cancel',stop.is_set)
        future=self.pool.submit(driver.run,'Inspect')
        self.pending(states); stop.set()
        with self.assertRaisesRegex(DriverError,'cancel_unknown'): future.result(timeout=5)
        self.assertTrue(driver.cancel_sent)
        self.assertFalse(driver.finished)
        self.assertEqual(1,len([v for v in self.wire() if v.get('method')=='session/cancel']))
        with self.assertRaisesRegex(DriverError,'already_attempted'): driver.run('No retry')

    def test_lossless_review_guard_rejects_secret_oversize_and_extra_params(self):
        for mode in ['secret-field','array-limit','long-value','extra-meta']:
            with self.subTest(mode=mode):
                driver,states=self.driver(mode)
                with self.assertRaisesRegex(DriverError,'unreviewable'): driver.run('Inspect')
                self.assertFalse(any(v.get('pendingApprovals') for v in states))
                replies=[v for v in self.wire() if v.get('id')=='peer-permission']
                self.assertEqual({'outcome':'cancelled'},replies[-1]['result']['outcome'])

    def test_key_redaction_cannot_silently_change_a_hashed_approval(self):
        driver,states=self.driver('secret-value',redact=lambda v:json.loads(json.dumps(v).replace('fixture-key','hidden')))
        with self.assertRaisesRegex(DriverError,'unreviewable'): driver.run('Inspect')
        self.assertFalse(any(v.get('pendingApprovals') for v in states))

    def test_source_changed_before_prompt_does_not_dispatch(self):
        def current(**kwargs):
            if kwargs.get('deep'): raise profile.ProfileError('source_changed','fixture mutation')
        driver,_=self.driver('complete',current=current)
        with self.assertRaisesRegex(DriverError,'source_changed'): driver.run('Inspect')
        self.assertFalse(driver.turn_dispatched)
        self.assertNotIn('session/prompt',[v.get('method') for v in self.wire()])

    def test_mismatched_handshake_mode_or_model_stops_before_prompt(self):
        for mode in ['wrong-wire','wrong-version','wrong-mode','wrong-model']:
            with self.subTest(mode=mode):
                driver,_=self.driver(mode)
                with self.assertRaises(DriverError): driver.run('Inspect',model='fixture-model')
                self.assertFalse(driver.turn_dispatched)

    def test_disconnect_wrong_session_or_stdout_noise_remains_unknown_and_not_replayed(self):
        for mode in ['disconnect','wrong-session','stdout-noise']:
            with self.subTest(mode=mode):
                driver,_=self.driver(mode)
                with self.assertRaises(DriverError): driver.run('Inspect')
                self.assertTrue(driver.turn_dispatched)
                before=len([v for v in self.wire() if v.get('method')=='session/prompt'])
                with self.assertRaisesRegex(DriverError,'already_attempted'): driver.run('Do not retry')
                self.assertEqual(before,len([v for v in self.wire() if v.get('method')=='session/prompt']))

    def test_duplicate_peer_request_cannot_replace_native_review(self):
        driver,states=self.driver('duplicate')
        with self.assertRaisesRegex(DriverError,'identity_reused'): driver.run('Inspect')
        self.assertEqual(1,len([v for v in states if v.get('pendingApprovals')]))


class GeminiProfileTests(FixtureBase):
    def setUp(self):
        super().setUp()
        self.package()
        self.addCleanup(patch.stopall)
        patch.dict(os.environ,{'GEMINI_API_KEY':KEY}).start()

    def fixed(self):
        state=self.root/'state'; state.mkdir(exist_ok=True)
        source=profile.FixedProfile(self.environment,'a'*64,state,b'fixture-process-salt','')
        self.addCleanup(source.close)
        return source

    def test_same_manifest_algorithm_proposes_checks_and_rejects_changed_dependency(self):
        source=profile.installation(self.environment)
        self.assertEqual(self.environment['geminiAcp'],source['configuration'])
        self.assertFalse(source['observation']['executionChainVerified'])
        self.assertFalse(source['observation']['osSandboxVerified'])
        (self.install/'new-dependency.js').write_text('changed')
        with self.assertRaisesRegex(profile.ProfileError,'摘要不符'): profile.installation(self.environment)

    def test_read_only_cli_manifest_generation_never_executes_entry(self):
        result=subprocess.run([sys.executable,'-B',str(Path(profile.__file__).resolve()),'--node',str(self.fixture_python),
                               '--installation-root',str(self.install)],capture_output=True,text=True,timeout=5)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(self.environment['geminiAcp'],json.loads(result.stdout))
        self.assertFalse((self.workspace/'fixture-starts').exists())

    def test_full_tree_rejects_external_symlink_and_group_writable_dependency(self):
        link=self.install/'external'; link.symlink_to(Path(sys.executable).resolve())
        with self.assertRaises(profile.ProfileError): profile.installation_configuration(str(self.fixture_python),str(self.install))
        link.unlink(); self.entry.chmod(0o666)
        with self.assertRaises(profile.ProfileError): profile.installation(self.environment)

    def test_profile_and_credential_changes_refuse_original_source(self):
        source=self.fixed()
        source.check(deep=True)
        os.environ['GEMINI_API_KEY']='different-fixture-key'
        with self.assertRaises(profile.ProfileError): source.check()
        os.environ['GEMINI_API_KEY']=KEY
        (source.root/'system.json').write_text('{}')
        with self.assertRaises(profile.ProfileError): source.check()

    def test_fixed_env_excludes_inherited_model_auth_and_profile_overrides(self):
        with patch.dict(os.environ,{'NODE_OPTIONS':'--require /unreviewed','GOOGLE_API_KEY':'not-the-selected-key',
                                   'GEMINI_CLI_HOME':'/unreviewed','GEMINI_CLI_SYSTEM_SETTINGS_PATH':'/unreviewed'}):
            source=self.fixed(); env=source.child_environment()
            self.assertNotIn('NODE_OPTIONS',env)
            self.assertNotIn('GOOGLE_API_KEY',env)
            self.assertEqual(str(source.home),env['GEMINI_CLI_HOME'])
            self.assertEqual('false',env['GEMINI_CLI_TRUST_WORKSPACE'])
            self.assertEqual(KEY,env['GEMINI_API_KEY'])
            self.assertNotIn(KEY,profile.canonical([source.install['observation'],source.revision]))

    def test_system_policy_conflict_or_unreadable_path_is_not_treated_as_absent(self):
        policy=self.root/'system-policies';policy.mkdir()
        (policy/'admin.toml').write_text('fixture policy')
        with patch.object(profile,'SYSTEM_POLICY_DIRECTORY',policy):
            with self.assertRaises(profile.ProfileError): self.fixed()
        with patch.object(Path,'lstat',side_effect=PermissionError('fixture unreadable')):
            with self.assertRaises(profile.ProfileError): profile.system_policy()


class GeminiManagerTests(FixtureBase):
    def setUp(self):
        super().setUp()
        env=self.package()
        self.mode('complete')
        self.env_patch=patch.dict(os.environ,{'GEMINI_API_KEY':KEY})
        self.env_patch.start();self.addCleanup(self.env_patch.stop)
        self.config={'stateDir':str(self.root/'state'),'environments':[env]}
        self.manager=JobManager(self.config,TOKEN)
        self.addCleanup(lambda:self.manager.close())

    def probe(self,model=''):
        return self.manager.probe_worker({'environmentId':'computer','environmentBinding':self.manager.environment_binding('computer'),
                                         'worker':'gemini_acp','backendId':profile.BACKEND,'modeId':'default','model':model})

    def request(self,observation,operation='fixture-operation'):
        value=observation['backendObservation']
        selection={'format':'pocketworld.remote-backend-selection.v1','backendId':profile.BACKEND,'backendVersion':profile.VERSION,
                   'protocolVersion':1,'installObservation':value['installObservation'],'configurationRevision':value['configurationRevision'],
                   'serviceCapabilityRevision':value['serviceCapabilityRevision'],'capabilityRevision':'a'*64,
                   'model':value['requestedModel'],'modeId':'default','readOnly':False}
        return {'operationId':operation,'environmentId':'computer','environmentBinding':value['environmentBinding'],
                'worker':'gemini_acp','prompt':'Inspect the original fixture','model':value['requestedModel'],'readOnly':False,
                'timeoutSeconds':10,'backendSelection':selection}

    def settled(self,jobid):
        return self.wait_for(lambda:self.manager.get(jobid) if jobid not in self.manager.processes and
                            self.manager.jobs[jobid].get('dispatchFenceFinal') is True else None)

    def pending(self,jobid):
        return self.wait_for(lambda:self.manager.get(jobid) if self.manager.jobs[jobid].get('pendingApprovals') else None)

    def approve(self,job):
        pending=job['pendingApprovals'][0]
        return {'requestId':pending['requestId'],'decision':'accept','jobOperationId':job['operationId'],
                'sessionId':pending['sessionId'],'promptRequestId':pending['promptRequestId'],
                'requestHash':pending['requestHash'],'backendSelection':job['backendSelection']}

    def test_probe_is_initialize_only_and_preserves_evidence_boundaries(self):
        result=self.probe();value=result['backendObservation']
        self.assertTrue(result['available'],result)
        self.assertEqual('verified',value['protocol']['state'])
        self.assertFalse(value['authentication']['authenticated'])
        self.assertEqual('not_checked',value['model']['state'])
        self.assertEqual('not_checked',value['mode']['state'])
        self.assertEqual(['initialize'],[v.get('method') for v in self.wire()])
        self.assertEqual({},self.manager.jobs)
        self.assertNotIn(KEY,json.dumps(result));self.assertNotIn(TOKEN,json.dumps(result))
        self.assertFalse(self.manager.resources)

    def test_missing_install_key_and_wrong_protocol_remain_distinct_unavailable(self):
        env=self.manager.environments['computer']; saved=env.pop('geminiAcp')
        self.assertEqual('installation_missing',self.probe()['backendObservation']['reason']['code'])
        env['geminiAcp']=saved
        with patch.dict(os.environ,{'GEMINI_API_KEY':''}):
            missing=self.probe()['backendObservation']
            self.assertEqual('credential_missing',missing['authentication']['state'])
            self.assertEqual('observed',missing['installObservation']['state'])
        self.mode('wrong-version')
        result=self.probe()['backendObservation']
        self.assertFalse(result['available'])
        self.assertEqual('',result['serviceCapabilityRevision'])
        self.assertEqual({'state':'mismatch','agentName':'','agentVersion':'','wireVersion':0},result['protocol'])

    def test_first_job_persists_exact_selection_environment_and_default_requested_model(self):
        body=self.request(self.probe())
        first=self.manager.submit(body); result=self.settled(first['id'])
        self.assertEqual('completed',result['status'],result)
        for receipt in [first,result,self.manager.operation(body['operationId']),self.manager.submit(body),
                        self.manager.cancel_original(first['id'],{'jobOperationId':body['operationId']})]:
            self.assertEqual(body['backendSelection'],receipt['backendSelection'])
            self.assertEqual(body['environmentBinding'],receipt['environmentBinding'])
            self.assertEqual('',receipt['model'])
        self.assertEqual('fixture-model',result['reportedModel'])
        self.assertFalse(result['controls']['threadResume'])
        self.assertFalse(result['controls']['inputSteering'])
        self.assertEqual(1,len([v for v in self.wire() if v.get('method')=='session/prompt']))
        self.assertFalse(self.manager.resources)

    def test_explicit_model_is_original_and_permission_requires_all_exact_fields(self):
        self.mode('approval');body=self.request(self.probe('fixture-owner-model'))
        job=self.manager.submit(body);pending=self.pending(job['id'])
        approval=self.approve(pending)
        bad={**approval,'requestHash':'0'*64}
        with self.assertRaises(RequestError): self.manager.approve(job['id'],bad)
        self.assertFalse(any(v.get('id')=='peer-permission' for v in self.wire()))
        accepted=self.manager.approve(job['id'],approval)
        result=self.settled(job['id'])
        self.assertEqual('completed',result['status'],result)
        self.assertEqual('fixture-owner-model',result['model'])
        for receipt in [accepted,result]:
            self.assertEqual(body['backendSelection'],receipt['backendSelection'])
            self.assertEqual(body['environmentBinding'],receipt['environmentBinding'])
        with self.assertRaises(RequestError): self.manager.approve(job['id'],approval)

    def test_leading_dash_model_is_one_literal_actual_process_argument(self):
        body=self.request(self.probe('--yolo'))
        job=self.manager.submit(body);result=self.settled(job['id'])
        self.assertEqual('completed',result['status'],result)
        self.assertEqual('--yolo',result['model'])
        self.assertEqual('--yolo',result['reportedModel'])
        argv=json.loads((self.workspace/'fixture-argv').read_text())
        self.assertEqual(1,argv.count('--model=--yolo'))
        self.assertNotIn('--yolo',argv)
        self.assertNotIn('--model',argv)
        self.assertEqual('default',argv[argv.index('--approval-mode')+1])

    def test_readonly_omission_special_origin_and_changed_selection_reject_before_reservation(self):
        body=self.request(self.probe())
        variants=[{k:v for k,v in body.items() if k!='readOnly'}, {**body,'readOnly':True},
                  {**body,'inferenceAudience':'EXTERNAL_GROUP'}, {**body,'threadId':'old'},
                  {**body,'backendSelection':{**body['backendSelection'],'modeId':'yolo'}}]
        for variant in variants:
            with self.subTest(keys=list(variant)):
                with self.assertRaises(RequestError): self.manager.submit(variant)
        self.assertEqual('not_found',self.manager.operation(body['operationId'])['status'])
        self.assertEqual({},self.manager.jobs)

    def test_changed_profile_between_queue_and_launch_has_no_process_or_prompt(self):
        body=self.request(self.probe())
        entry=self.manager.backend_observations[body['backendSelection']['serviceCapabilityRevision']]
        resource=self.manager.resource_id(self.manager.environments['computer'])
        with self.manager.resource_changed:
            self.manager.resources.add(resource)
        job=self.manager.submit(body)
        (entry['source'].root/'system.json').write_text('{}')
        with self.manager.resource_changed:
            self.manager.resources.discard(resource);self.manager.resource_changed.notify_all()
        result=self.settled(job['id'])
        self.assertEqual('blocked',result['status'],result)
        self.assertNotIn('session/prompt',[v.get('method') for v in self.wire()])
        self.assertEqual('1',(self.workspace/'fixture-starts').read_text())

    def test_expiry_blocks_new_submit_but_not_existing_query_or_original_cancel(self):
        self.mode('cancel');body=self.request(self.probe())
        entry=self.manager.backend_observations[body['backendSelection']['serviceCapabilityRevision']]
        job=self.manager.submit(body);self.pending(job['id'])
        entry['observation']['expiresAt']=0
        with self.assertRaises(RequestError): self.manager.submit({**body,'operationId':'new-after-expiry'})
        self.assertEqual(job['id'],self.manager.operation(body['operationId'])['id'])
        receipt=self.manager.cancel_original(job['id'],{'jobOperationId':body['operationId']})
        result=self.settled(job['id'])
        self.assertEqual('cancelled',result['status'],result)
        self.assertEqual(body['environmentBinding'],receipt['environmentBinding'])
        self.assertEqual(body['backendSelection'],result['backendSelection'])
        self.assertEqual(1,len([v for v in self.wire() if v.get('method')=='session/cancel']))

    def test_lost_prompt_receipt_is_same_operation_after_restart_and_archive_not_replayed(self):
        self.mode('disconnect');body=self.request(self.probe())
        job=self.manager.submit(body);result=self.settled(job['id'])
        self.assertEqual('unknown',result['status'],result)
        count=len(self.wire())
        self.manager.close();self.manager=JobManager(self.config,TOKEN)
        again=self.manager.submit(body)
        self.assertEqual('unknown',again['status'])
        self.assertEqual(body['environmentBinding'],again['environmentBinding'])
        self.assertEqual(body['backendSelection'],again['backendSelection'])
        # Original ledger-only fallback (e.g. settled presentation record removed).
        self.manager.jobs.pop(job['id'])
        archived=self.manager.operation(body['operationId'])
        self.assertEqual('unknown',archived['status'])
        self.assertEqual(body['environmentBinding'],archived['environmentBinding'])
        self.assertEqual(body['backendSelection'],archived['backendSelection'])
        self.assertEqual(count,len(self.wire()))

    def test_source_and_hello_changes_after_probe_never_get_a_prompt(self):
        self.mode('changed-hello');body=self.request(self.probe())
        job=self.manager.submit(body);result=self.settled(job['id'])
        self.assertEqual('blocked',result['status'],result)
        self.assertNotIn('session/prompt',[v.get('method') for v in self.wire()])
        self.entry.write_text(FIXTURE+'\n# changed source\n')
        with self.assertRaises(RequestError): self.manager.submit({**body,'operationId':'changed-package'})

    def test_late_terminal_after_cancel_belongs_only_to_original_job(self):
        self.mode('late-final');body=self.request(self.probe())
        job=self.manager.submit(body);self.pending(job['id'])
        self.manager.cancel_original(job['id'],{'jobOperationId':body['operationId']})
        result=self.settled(job['id'])
        self.assertEqual('completed',result['status'])  # Actual original end_turn, not inferred cancellation.
        self.assertEqual('end_turn',result['stopReason'])
        self.assertTrue(result['cancelNotificationSent'])
        self.assertEqual(job['id'],result['id'])
        self.assertEqual(body['backendSelection'],result['backendSelection'])
        self.assertEqual(body['environmentBinding'],result['environmentBinding'])
        self.assertEqual(1,len(self.manager.jobs))

    def test_cold_pending_job_drops_live_approval_but_retains_original_target(self):
        self.mode('cancel');body=self.request(self.probe())
        job=self.manager.submit(body);pending=self.pending(job['id'])
        path=self.manager.state/('job-'+job['id']+'.json')
        original_pending_bytes=path.read_bytes()  # A real pre-crash durable checkpoint.
        self.manager.close()
        path.write_bytes(original_pending_bytes)
        self.manager=JobManager(self.config,TOKEN)
        result=self.manager.get(job['id'])
        self.assertEqual('interrupted',result['status'])
        self.assertEqual([],result['pendingApprovals'])
        self.assertEqual(body['backendSelection'],result['backendSelection'])
        self.assertEqual(body['environmentBinding'],result['environmentBinding'])
        with self.assertRaises(RequestError): self.manager.approve(job['id'],self.approve(pending))
        self.assertEqual(1,len([v for v in self.wire() if v.get('method')=='session/prompt']))

    def test_original_authenticated_loopback_http_keeps_probe_job_and_query_identity(self):
        server=CompanionServer(('127.0.0.1',0),self.manager)
        thread=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':.01},daemon=True)
        thread.start()
        try:
            base='http://127.0.0.1:'+str(server.server_port)
            def http(path,body=None):
                req=Request(base+path,data=None if body is None else json.dumps(body).encode(),
                            headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
                with urlopen(req,timeout=5) as response:
                    self.assertEqual(200,response.status)
                    return json.loads(response.read())['result']
            probe=http('/v1/workers/probe',{'environmentId':'computer','environmentBinding':self.manager.environment_binding('computer'),
                                          'worker':'gemini_acp','model':'','modeId':'default','backendId':profile.BACKEND})
            body=self.request(probe)
            job=http('/v1/jobs',body);self.settled(job['id'])
            for value in [http('/v1/jobs/'+job['id']),http('/v1/operations/'+body['operationId']),http('/v1/jobs',body)]:
                self.assertEqual(job['id'],value['id'])
                self.assertEqual(body['backendSelection'],value['backendSelection'])
                self.assertEqual(body['environmentBinding'],value['environmentBinding'])
        finally:
            server.shutdown();server.server_close();thread.join(timeout=1)


if __name__ == '__main__':
    unittest.main()
