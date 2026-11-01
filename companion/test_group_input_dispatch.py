"""Actual original CLI processes/files/ledger, synthetic account mode, no external login."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch
import input_files
import group_input_audience
from pocket_companion import JobManager, RequestError
from project_history_fixture_server import PEER

GROUP_PEER=PEER.replace("'config':{'mcp_servers':{}}", "'config':{'mcp_servers':{},'cli_auth_credentials_store':'file','model_provider':'openai'}").replace("'account':{'type':'apiKey'}", "'account':{'type':(home/'mode.txt').read_text() if (home/'mode.txt').exists() else 'apiKey'}")

GROUP_PEER=GROUP_PEER.replace("  emit({'method':'turn/completed','params':{'threadId':thread,'turn':{'id':turn,'status':'completed'}}})", "  emit({'method':'turn/completed','params':{'threadId':thread,'turn':{'id':turn,'status':'completed'}}})\n  if(home/'change-mode-after-turn').exists():(home/'mode.txt').write_text('chatgpt')")

class GroupInputDispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='c3-group-api-original-job-');self.root=Path(self.temp.name);self.work=self.root/'workspace';self.work.mkdir();self.peer=self.root/'peer.py';self.peer.write_text(GROUP_PEER);self.protocol=self.root/'protocol';self.protocol.mkdir();self.cliHome=self.root/'codex-home';self.cliHome.mkdir()
        (self.cliHome/'auth.json').write_text(json.dumps({'OPENAI_API_KEY':'synthetic-group-mode-never-real-key'}));(self.cliHome/'config.toml').write_text('cli_auth_credentials_store="file"\nmodel_provider="openai"\n')
        binary=self.root/'bin';binary.mkdir();cli=binary/'codex';cli.write_text('#!'+sys.executable+'\nimport os,sys\nos.execv('+repr(sys.executable)+',['+repr(sys.executable)+','+repr(str(self.peer))+','+repr(str(self.protocol))+',*sys.argv[1:]])\n');cli.chmod(0o700)
        self.path=patch.dict(os.environ,{'PATH':str(binary)+os.pathsep+os.environ['PATH']});self.path.start();self.config={'stateDir':str(self.root/'state'),'environments':[{'id':'group-api','kind':'local','root':str(self.work),'workers':['codex_app_server'],'writeAllowed':True,'codexHome':str(self.cliHome),'codexProviderVariables':[]}]};self.manager=JobManager(self.config,'synthetic-group-input-token-never-real-more-than-32')
    def tearDown(self):
        self.manager.close();self.assertEqual({},self.manager.processes);self.assertEqual({},self.manager.drivers);self.assertEqual(set(),self.manager.resources);self.path.stop();self.temp.cleanup()
    def body(self):
        proof=self.manager.probe_worker({'environmentId':'group-api','worker':'codex_app_server','environmentBinding':self.manager.environment_binding('group-api'),'inferenceAudience':'EXTERNAL_GROUP'})
        self.assertTrue(proof['available'],proof);self.assertEqual('apiKey',proof['authMode']);self.assertNotIn('thread/start',self.methods());self.assertNotIn('turn/start',self.methods())
        self.raw=b'\x00\xffACTUAL IMMUTABLE GROUP BYTES\x80';file={'attachmentId':str(uuid.uuid4()),'name':'group-input.bin','mimeType':'application/octet-stream','bytes':len(self.raw),'sha256':hashlib.sha256(self.raw).hexdigest()}
        return {'operationId':str(uuid.uuid4()),'environmentId':'group-api','environmentBinding':self.manager.environment_binding('group-api'),'worker':'codex_app_server','prompt':'Read only the exact authorized group bytes. Do not ask for wider permissions.','readOnly':True,'timeoutSeconds':30,'inputProtocol':input_files.PROTOCOL,'inputFiles':[file],'inferenceAudience':'EXTERNAL_GROUP','externalGroupApiMode':proof['externalGroupApiMode'],'groupInputSource':{'inferenceAudience':'EXTERNAL_GROUP','privateBookImported':False,'spaceId':'group:'+str(uuid.uuid4()),'requestId':str(uuid.uuid4()),'taskId':str(uuid.uuid4()),'actorId':'original-group-requester'}}
    def methods(self):return [json.loads(line)['method']for line in(self.protocol/'methods.jsonl').read_text().splitlines()]if(self.protocol/'methods.jsonl').exists()else[]
    def ready(self,body):
        job=self.manager.submit(body);self.assertFalse(job['inputState']['workerStarted']);actual=input_files.upload(self.manager,job['id'],0,body['operationId'],len(self.raw),io.BytesIO(self.raw),RequestError);self.assertEqual('ready',actual['inputState']['phase']);self.assertNotIn('turn/start',self.methods());return actual
    def start(self,job):return input_files.start(self.manager,job['id'],{'jobOperationId':job['operationId'],'startOperationId':str(uuid.uuid4()),'manifestHash':job['inputState']['manifestHash']},RequestError)
    def settled(self,job):
        until=time.monotonic()+5
        while time.monotonic()<until:
            value=self.manager.get(job['id'])
            if value['status']in {'completed','failed','unknown','blocked','cancelled'}and not self.manager.processes:return value
            time.sleep(.01)
        self.fail('Original group worker did not settle: '+str(value))
    def test_actual_api_read_current_generation_exact_binary_ready_then_one_original_read_only_worker(self):
        body=self.body();ready=self.ready(body);self.start(ready);actual=self.settled(ready);self.assertEqual('completed',actual['status'],actual);self.assertTrue(actual['readOnlyConfirmed']);self.assertTrue(actual['externalToolsDisabled']);self.assertEqual('apiKey',actual['externalGroupApiRead']['authMode']);self.assertFalse(actual['externalGroupApiRead']['billingBackendVerified']);self.assertTrue(actual['externalGroupApiRead']['postTurnAccountReadVerified']);self.assertTrue(actual['externalGroupApiRead']['generationCheckedAfterTurn']);self.assertEqual(1,self.methods().count('turn/start'));slot=actual['inputState']['files'][0];self.assertEqual(self.raw,(self.work/slot['path']).read_bytes());self.assertEqual(body['groupInputSource'],actual['inputState']['groupInputSource']);self.assertNotIn('synthetic-group-mode-never-real-key',json.dumps(actual));self.assertEqual(actual['id'],self.manager.submit(body)['id'])
    def test_rotated_actual_credential_source_rejects_before_any_second_body_or_model_prompt(self):
        body=self.body();job=self.manager.submit(body);(self.cliHome/'auth.json').write_text(json.dumps({'OPENAI_API_KEY':'rotated-synthetic-only'}))
        class NeverRead:
            def read(self,_):raise AssertionError('Credential drift must reject before reading group body')
        with self.assertRaises(RequestError):input_files.upload(self.manager,job['id'],0,body['operationId'],len(self.raw),NeverRead(),RequestError)
        self.assertNotIn('turn/start',self.methods());self.assertEqual('pending',self.manager.get(job['id'])['inputState']['files'][0]['state']);self.assertFalse(self.manager.get(job['id'])['inputState']['workerStarted'])
    def test_same_app_server_non_api_account_cannot_use_prior_configured_or_owner_acknowledged_proof(self):
        body=self.body();ready=self.ready(body);(self.protocol/'mode.txt').write_text('chatgpt');self.start(ready);actual=self.settled(ready);self.assertEqual('blocked',actual['status'],actual);self.assertNotIn('thread/start',self.methods());self.assertNotIn('turn/start',self.methods());self.assertTrue(actual.get('processStarted'),'Actual protocol inspection process ran; zero model prompt is not zero process')
    def test_forged_mode_receipt_or_cold_process_generation_never_restarts_original_ready_job(self):
        body=self.body();forged=json.loads(json.dumps(body));forged['externalGroupApiMode']['expiresAt']+=1000
        with self.assertRaises(RequestError):self.manager.submit(forged)
        ready=self.ready(body);self.manager.close();self.manager=JobManager(self.config,'synthetic-group-input-token-never-real-more-than-32')
        with self.assertRaises(RequestError):self.start(ready)
        self.assertNotIn('turn/start',self.methods());self.assertEqual(ready['id'],self.manager.submit(body)['id']);self.assertNotEqual('running',self.manager.get(ready['id'])['status'])

    def test_actual_same_server_post_turn_mode_drift_is_unknown_not_api_verified_result(self):
        body=self.body();ready=self.ready(body);(self.protocol/'change-mode-after-turn').write_text('only controlled peer changes observed account after the terminal turn')
        self.start(ready);actual=self.settled(ready);self.assertEqual('unknown',actual['status'],actual);self.assertFalse(actual['externalGroupApiRead']['postTurnAccountReadVerified']);self.assertEqual('unknown',actual['externalGroupApiRead']['authMode']);self.assertEqual(1,self.methods().count('turn/start'));self.assertGreaterEqual(self.methods().count('account/read'),4)

if __name__=='__main__':unittest.main()
