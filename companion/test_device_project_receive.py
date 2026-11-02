"""Original Companion/SQL + Windows adapter fixtures for reviewed project round trips.

Controlled native API model, not a Windows kernel or Android physical-device result.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid
import os

import device_protocol as wire
import device_transport as transport
from device_companion import workspace_binding
from device_artifacts import WindowsArtifactReceiver
from platform_backend import bytes_version
from workspace_files_windows import WindowsWorkspaceFiles
from test_device_artifacts_windows_adapter import FixtureNativeBackend
from test_device_companion_input import NativeInputTests
from test_device_project_packages import package, archive, TreeApiModel, TreeBackendModel
import device_project_packages as packages


class NativeProjectReceiveTests(unittest.TestCase):
    receive=NativeInputTests.receive
    success=NativeInputTests.success
    tearDown=NativeInputTests.tearDown
    admission=NativeInputTests.admission
    def request(self,*args,**kwargs):
        kwargs.setdefault('authorizationEpoch',self.grant['authorizationEpoch'])
        return NativeInputTests.request(self,*args,**kwargs)
    def setUp(self):
        NativeInputTests.setUp(self)
        tree=TreeBackendModel(api=TreeApiModel(self.workspace));self.tree=tree
        class Backend(FixtureNativeBackend):
            def logon_context(self): return {'userSid':'fixture-not-windows'}
            def assert_private_directory(self,*args,**kwargs): return {'fixture':True}
            @contextmanager
            def directory(self,root,parts=()):
                actual=Path(root).joinpath(*parts); info=actual.stat();yield str(actual),None,[info.st_dev,0,info.st_ino]
            def compare_and_swap(self,root,relative,before,after,current=lambda:None,*,record_intent=lambda value:None):
                current()
                if self.read_bytes(root,relative)!=before: raise PermissionError('Fixture original CAS mismatch')
                record_intent({'phase':'dispatch_intent_persisted','fixture':True});current()
                (Path(root)/relative).write_bytes(after)
                return {'status':'completed','verified':True,'version':bytes_version(after)}
            def import_project_copy(self,root,relative,manifest,files,current,*,record_intent):
                original=os.listdir
                with patch('platform_windows.os.listdir',side_effect=lambda value:original(tree.api.local(value)) if str(value).startswith('C:\\fixture') else original(value)):
                    return tree.import_project_copy('C:\\fixture',relative,manifest,files,current,record_intent=record_intent)
            def capture_project_copy(self,root,relative,identity,manifest,current):
                original=os.listdir
                with patch('platform_windows.os.listdir',side_effect=lambda value:original(tree.api.local(value)) if str(value).startswith('C:\\fixture') else original(value)):
                    return tree.capture_project_copy('C:\\fixture',relative,identity,manifest,current)
        backend=Backend();self.manager.platform=backend
        self.manager.files=WindowsWorkspaceFiles(self.manager.state,self.manager.ledger,backend)
        self.binding=workspace_binding(self.manager,'computer')
        self.grant.update(authorizationEpoch=3,resources=[self.binding])
        self.grant['toolNames']+=['workspace.project.receive','workspace.project.confirm','workspace.project.export']
        self.receiver.trust.replace_grant(self.grant,self.manager.now())

    def stage(self,raw,path='reviewed-copy'):
        request,descriptor=self.admission(raw,path=path,kind='project_bundle')
        body=json.loads(request);body['arguments']['toolName']='workspace.project.receive';body['argumentsDigest']=wire.arguments_digest(body)
        request=wire.canonical_bytes(body)
        accepted=self.success(self.receive(request));self.assertEqual('awaiting_bytes',accepted['phase'])
        self.assertFalse((self.workspace/path).exists())
        for offset in range(0,len(raw),262144):
            frame=transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=offset,data=raw[offset:offset+262144])
            self.receiver.receive_bulk(self.peer_a,frame)
            self.receiver.receive_bulk(self.peer_a,frame)  # Prior cursor query raced an already queued bulk frame.
        query=self.request(messageType='DeviceRequest',method='operation.get',arguments={'operationId':descriptor['operationId']})
        review=self.success(self.receive(query))
        self.assertEqual('awaiting_import_review',review['phase']);self.assertFalse(review['delivered'])
        self.assertFalse((self.workspace/path).exists())
        return request,descriptor,review

    def confirmation(self,descriptor,review,**changes):
        args=dict(receiveOperationId=descriptor['operationId'],manifestDigest=review['manifestDigest'],path=review['path'],expectedVersion='absent')
        args.update(changes)
        return self.request('workspace.project.confirm',operationId='confirm-'+uuid.uuid4().hex,
                            arguments={'toolName':'workspace.project.confirm','effect':'write','arguments':args})

    def test_multichunk_stage_native_review_confirm_edit_and_original_package_export(self):
        _,manifest,files=package(b'original source')
        binary=os.urandom(600017);files['assets.bin']=binary
        manifest['files'].append(dict(path='assets.bin',bytes=len(binary),sha256=hashlib.sha256(binary).hexdigest(),kind='binary',mimeType='application/octet-stream',executable=False))
        manifest['bytes']+=len(binary);raw=archive(manifest,files)
        self.assertGreater(len(raw),262144)
        request,descriptor,review=self.stage(raw)
        self.assertIsInstance(self.receiver._input_cache,WindowsArtifactReceiver)
        self.assertEqual(packages.manifest_identity(manifest),review['manifestDigest'])
        confirm=json.loads(self.confirmation(descriptor,review))
        confirm.update(runId="native-owner-review-next-run",executorEpoch=2)
        confirm["argumentsDigest"]=wire.arguments_digest(confirm);confirm=wire.canonical_bytes(confirm)
        result=self.success(self.receive(confirm));self.assertTrue(result['delivered'],result);self.assertTrue(result['projectImported'])
        self.assertEqual(binary,(self.workspace/'reviewed-copy'/'assets.bin').read_bytes())
        self.assertTrue((self.workspace/'reviewed-copy'/'empty').is_dir())
        self.assertEqual(result,self.success(self.receive(confirm)))
        self.assertEqual(1,len(self.tree.api.publications))
        write=self.request('workspace.write',{'path':'reviewed-copy/src/main.txt','content':'modified on the PC','expectedVersion':bytes_version(files['src/main.txt'])})
        self.assertTrue(self.success(self.receive(write))['verified'])
        exported=self.success(self.receive(self.request('workspace.project.export',{'receiveOperationId':descriptor['operationId']})))
        self.assertEqual('project_bundle',exported['kind'])
        fixed=self.receiver.artifacts.by_artifact_id(exported['artifactId'])
        chunks=[]
        for offset in range(0,fixed['bytes'],262144): chunks.append(self.receiver.artifacts.read_chunk(fixed,offset,min(262144,fixed['bytes']-offset)).data)
        modified,actual=packages.read(b''.join(chunks))
        self.assertEqual(b'modified on the PC',actual['src/main.txt']);self.assertEqual(binary,actual['assets.bin'])
        self.assertNotEqual(manifest['source']['sourceDigest'],modified['source']['sourceDigest'])
        self.assertFalse(modified['includesHistory']);self.assertFalse(modified['includesToolEnvironment'])
        self.assertEqual(4,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])
        original=self.manager.operation(descriptor['operationId'])
        self.assertEqual('original-run',original['nativeDeviceOperation']['requestRunId'])
        self.assertEqual(1,original['nativeDeviceOperation']['requestExecutorEpoch'])

    def test_new_review_attempt_cannot_change_original_task_parent_or_grant_scope(self):
        raw,_,_=package();_,descriptor,review=self.stage(raw)
        for field,value in (("localTaskId","other-task"),("parentTaskId","other-parent"),("rootTaskId","other-root")):
            request=json.loads(self.confirmation(descriptor,review))
            request.update(runId="new-review-run",executorEpoch=2)
            request[field]=value
            if field=="parentTaskId":
                request["budget"]["allocationId"]=wire.allocation_id(value,self.b.device_id,self.binding,"tool_session")
            request["argumentsDigest"]=wire.arguments_digest(request)
            self.assertEqual("DeviceError",self.receive(wire.canonical_bytes(request))["messageType"])
        self.assertEqual([],self.tree.api.publications)

    def test_export_refuses_new_private_or_unreviewed_files_in_original_copy(self):
        raw,_,_=package();_,descriptor,review=self.stage(raw)
        self.assertTrue(self.success(self.receive(self.confirmation(descriptor,review)))["verified"])
        (self.workspace/"reviewed-copy"/".env").write_text("controlled fixture never exported")
        exported=self.receive(self.request("workspace.project.export",{"receiveOperationId":descriptor["operationId"]}))
        self.assertEqual("DeviceError",exported["messageType"])
        self.assertEqual(0,self.manager.ledger.execute("SELECT count(*) FROM device_artifact_audiences").fetchone()[0])

    def test_changed_review_cancel_and_foreign_source_never_publish(self):
        raw,_,_=package();_,descriptor,review=self.stage(raw)
        self.assertEqual('DeviceError',self.receive(self.confirmation(descriptor,review,manifestDigest='0'*64))['messageType'])
        self.assertFalse((self.workspace/'reviewed-copy').exists())
        job=self.manager.operation(descriptor['operationId']);self.manager.cancel(job['id'])
        self.assertEqual('DeviceError',self.receive(self.confirmation(descriptor,review))['messageType'])
        self.assertEqual([],self.tree.api.publications)

    def test_unknown_directory_publication_stays_original_and_never_replays(self):
        raw,_,_=package();_,descriptor,review=self.stage(raw)
        self.tree.api.fail_after_publish=True
        request=self.confirmation(descriptor,review)
        result=self.success(self.receive(request));self.assertEqual('unknown',result['status'])
        self.assertEqual(result,self.success(self.receive(request)))
        self.assertEqual(1,len(self.tree.api.publications))
        self.assertEqual('DeviceError',self.receive(self.confirmation(descriptor,review))['messageType'])
        self.assertEqual(1,len(self.tree.api.publications))

    def test_missing_parent_confirm_failure_retires_source_slots_without_replaying(self):
        raw,_,_=package()
        for number in range(4):
            _,descriptor,review=self.stage(raw,path='missing-parent-'+str(number)+'/copy')
            request=self.confirmation(descriptor,review)
            self.assertEqual('DeviceError',self.receive(request)['messageType'])
            source=self.manager.operation(descriptor['operationId'])
            self.assertEqual('unknown',source['status'])
            self.assertEqual({},self.receiver._inputs)
            self.assertEqual('unknown',self.manager.cancel(source['id'])['status'])
            self.assertEqual({},self.receiver._inputs)
        _,descriptor,review=self.stage(raw,path='valid-after-failures')
        self.assertTrue(self.success(self.receive(self.confirmation(descriptor,review)))['verified'])
        self.assertEqual(1,len(self.tree.api.publications))
        self.assertEqual(10,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_confirmation_cache_read_and_logon_failure_settle_source_and_release_bytes(self):
        raw,_,_=package()
        for mode in ('read','logon'):
            _,descriptor,review=self.stage(raw,path='copy-'+mode)
            request=self.confirmation(descriptor,review)
            target=self.receiver._input_cache if mode=='read' else self.tree
            method='open_verified' if mode=='read' else 'logon_context'
            with patch.object(target,method,side_effect=OSError('controlled '+mode+' failure')):
                self.assertEqual('DeviceError',self.receive(request)['messageType'])
            source=self.manager.operation(descriptor['operationId'])
            self.assertEqual('failed' if mode=='read' else 'unknown',source['status'])
            self.assertEqual({},self.receiver._inputs)
            self.assertEqual('DeviceError',self.receive(self.confirmation(descriptor,review))['messageType'])
        self.assertEqual([],self.tree.api.publications)
