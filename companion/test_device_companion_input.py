"""Production receiver ingress, original SQL and native CAS. Fixtures, not Win32 proof."""
import asyncio
import hashlib
import json
import unittest
import uuid

import device_protocol as wire
import device_transport as transport
from device_artifacts import to_wire_descriptor
import test_device_companion as fixture


class NativeInputTests(unittest.TestCase):
    tearDown = fixture.NativeCompanionIntegrationTests.tearDown
    receive = fixture.NativeCompanionIntegrationTests.receive
    success = fixture.NativeCompanionIntegrationTests.success

    def setUp(self):
        fixture.NativeCompanionIntegrationTests.setUp(self)
        self.grant.update(authorizationEpoch=2)
        self.grant['toolNames'].append('workspace.receive')
        self.receiver.trust.replace_grant(self.grant, self.manager.now())

    def request(self, *args, **kwargs):
        kwargs.setdefault('authorizationEpoch', 2)
        return fixture.NativeCompanionIntegrationTests.request(self, *args, **kwargs)

    def admission(self, raw, path='selected.bin', expected='absent', **changes):
        operation = 'input-' + uuid.uuid4().hex
        digest = hashlib.sha256(raw).hexdigest()
        fixed = dict(transferId=str(uuid.uuid4()), sourceDeviceId=self.a.device_id, artifactId='selected-owner-file',
                     versionId=digest, sha256=digest, bytes=len(raw), kind='file', mimeType='application/octet-stream',
                     name='selected.bin', taskId='original-task', operationId=operation,
                     requestRunId='original-run', requestExecutorEpoch=1)
        fixed.update(changes)
        artifact = to_wire_descriptor(fixed, instance_id=self.a.instance_id, revision=1)
        return self.request('workspace.receive', arguments={'toolName':'workspace.receive','effect':'write',
                            'arguments':dict(path=path,expectedVersion=expected,artifact=artifact)}, operationId=operation), fixed

    def test_actual_bulk_service_input_and_same_operation_receipt(self):
        raw = bytes(range(256)) * 1024  # exact native 256KiB target bound, not a text encoding.
        request, descriptor = self.admission(raw)
        self.service.start()
        ports = {item['lane']:item['port'] for item in self.service.public_status()['lanes']}
        async def exercise():
            pending, clients, tasks = {}, {}, []
            online = {lane: asyncio.Event() for lane in ("control", "bulk")}
            async def control(peer, frame):
                response=json.loads(frame.control); pending.pop(response['requestId']).set_result(response)
            async def binary(peer, frame): self.fail('Receiver must not reflect input bytes')
            async def invoke(value):
                future=asyncio.get_running_loop().create_future(); pending[json.loads(value)['requestId']]=future
                self.assertTrue(clients['control'].send_control(value))
                return self.success(await asyncio.wait_for(future, 5))
            try:
                for lane in ('control','bulk'):
                    async def connected(connection, peer, lane=lane): online[lane].set()
                    client=await transport.open_lan('127.0.0.1', ports[lane],
                        context=transport.native_device_context(self.a,[self.b.certificate_der],server_side=False),
                        expected_certificate_der=self.b.certificate_der,lane=lane,on_frame=control if lane=='control' else binary,on_connection=connected)
                    clients[lane]=client; tasks.append(asyncio.create_task(client.run()))
                await asyncio.wait_for(asyncio.gather(*(event.wait() for event in online.values())),5)
                accepted=await invoke(request)
                self.assertEqual('awaiting_bytes',accepted['phase']); self.assertFalse(accepted['delivered'])
                self.assertFalse((self.workspace/'selected.bin').exists())
                self.assertTrue(clients['bulk'].send_chunk(uuid.UUID(descriptor['transferId']),0,raw,before_write=lambda peer: True))
                for _ in range(100):
                    query=self.request(messageType='DeviceRequest',method='operation.get',arguments={'operationId':descriptor['operationId']})
                    result=await invoke(query)
                    if result.get('originalFileReceipt',{}).get('verified'): break
                    await asyncio.sleep(.01)
                self.assertTrue(result['originalFileReceipt']['verified'],result)
                self.assertEqual(raw,(self.workspace/'selected.bin').read_bytes())
                duplicate=await invoke(request)
                self.assertTrue(duplicate['delivered']); self.assertEqual(descriptor['transferId'],duplicate['transferId'])
                self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM file_operations').fetchone()[0])
                self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])
            finally:
                for client in clients.values(): await client.close()
                await asyncio.gather(*tasks,return_exceptions=True)
        asyncio.run(exercise())

    def test_reject_oversize_project_and_source_mismatch_before_reservation(self):
        for raw, change in ((b'x'*262145,{}),(b'zip',{'kind':'project_bundle'}),(b'x',{'taskId':'another-task'})):
            request,_=self.admission(raw,**change)
            self.assertEqual('DeviceError',self.receive(request)['messageType'])
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_revocation_stale_target_and_unknown_never_replayed(self):
        raw=b'owner-selected'
        request,descriptor=self.admission(raw)
        self.success(self.receive(request))
        self.receiver.trust.revoke(self.a.device_id,self.manager.now())
        with self.assertRaises(PermissionError):
            self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw))
        self.assertFalse((self.workspace/'selected.bin').exists())

    def test_same_transfer_resume_and_target_cas_conflict(self):
        raw=b'owner-selected'
        request,descriptor=self.admission(raw)
        first=self.success(self.receive(request))
        self.assertEqual(first,self.success(self.receive(request)))
        (self.workspace/'selected.bin').write_bytes(b'new-owner-content')
        with self.assertRaises(Exception):
            self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw))
        self.assertEqual(b'new-owner-content',(self.workspace/'selected.bin').read_bytes())
        result=self.success(self.receive(request))
        self.assertNotEqual('running',result.get('status'))
        self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_original_manager_cancel_fences_pending_input_without_claiming_later_effects(self):
        request,descriptor=self.admission(b'cancelled')
        self.success(self.receive(request))
        job=self.manager.operation(descriptor['operationId'])
        result=self.manager.cancel(job['id'])
        self.assertTrue(result['nativeInputStoppedBeforeReplacement'])
        with self.assertRaises(PermissionError):
            self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=b'cancelled'))
        self.assertFalse((self.workspace/'selected.bin').exists())
        self.assertEqual('cancelled',self.success(self.receive(request))['status'])

    def test_corrupt_bytes_never_commit_target(self):
        request,descriptor=self.admission(b'approved')
        self.success(self.receive(request))
        with self.assertRaises(Exception):
            self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=b'evilxxxx'))
        self.assertFalse((self.workspace/'selected.bin').exists())
        self.assertEqual(0,self.manager.ledger.execute('SELECT count(*) FROM file_operations').fetchone()[0])
        result=self.success(self.receive(request))
        self.assertEqual('failed',result['status'])
        self.assertFalse(result['replacementAttempted'])

    def test_empty_file_completes_at_admission_and_cache_releases_for_next_files(self):
        for number in range(6):
            request,descriptor=self.admission(b'',path='empty-'+str(number))
            result=self.success(self.receive(request))
            self.assertTrue(result['delivered']); self.assertTrue(result['verified'])
            self.assertEqual(b'',(self.workspace/('empty-'+str(number))).read_bytes())
        self.assertEqual({},self.receiver._inputs)
        self.assertEqual(6,self.manager.ledger.execute('SELECT count(*) FROM file_operations').fetchone()[0])

    def test_windows_receiver_and_original_windows_file_writer_use_native_backend_only(self):
        # This API fixture exercises production Windows adapters, not actual Win32 handles.
        from contextlib import contextmanager
        from test_device_artifacts_windows_adapter import FixtureNativeBackend
        from workspace_files_windows import WindowsWorkspaceFiles
        from device_companion import workspace_binding
        from platform_backend import bytes_version
        from pathlib import Path
        class Backend(FixtureNativeBackend):
            def logon_context(self): return {"userSid": "explicit-fixture-only"}
            def assert_private_directory(self,*args,**kwargs): return {"fixture": "not_windows_acl_proof"}
            @contextmanager
            def directory(self,root,parts=()):
                info=Path(root).stat(); yield str(root),None,[info.st_dev,info.st_ino]
            def compare_and_swap(self,root,relative,before,after,current=lambda:None,*,record_intent=lambda value:None):
                current()
                if self.read_bytes(root,relative) != before: raise PermissionError('Native fixture CAS mismatch')
                record_intent({'phase':'dispatch_intent_persisted','fixture':'not_win32'})
                current()
                (Path(root)/relative).write_bytes(after)
                self.calls.append(('cas',relative))
                return dict(status='completed',verified=True,version=bytes_version(after))
        backend=Backend(); self.manager.platform=backend
        self.manager.files=WindowsWorkspaceFiles(self.manager.state,self.manager.ledger,backend)
        self.binding=workspace_binding(self.manager,'computer')
        self.grant.update(authorizationEpoch=3,resources=[self.binding])
        self.receiver.trust.replace_grant(self.grant,self.manager.now())
        raw=bytes(range(256))*1024
        request,descriptor=self.admission(raw)
        body=json.loads(request);body['authorizationEpoch']=3;body['argumentsDigest']=wire.arguments_digest(body)
        request=wire.canonical_bytes(body)
        self.success(self.receive(request))
        self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw))
        result=self.success(self.receive(request))
        self.assertTrue(result['delivered']);self.assertEqual(raw,(self.workspace/'selected.bin').read_bytes())
        self.assertIn(('cas','selected.bin'),backend.calls)
        self.assertTrue(any(kind=='publish' for kind,name in backend.calls))

    def test_failed_cas_retires_bytes_but_preserves_all_original_operation_audits(self):
        for number in range(4):
            raw=b'approved-'+bytes([number])
            name='collision-'+str(number)
            request,descriptor=self.admission(raw,path=name)
            self.success(self.receive(request))
            (self.workspace/name).write_bytes(b'owner-newer-version')
            with self.assertRaises(Exception):
                self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw))
            self.assertEqual('unknown',self.success(self.receive(request))['status'])
            self.assertEqual(b'owner-newer-version',(self.workspace/name).read_bytes())
            self.assertEqual({},self.receiver._inputs)
        request,descriptor=self.admission(b'next-valid-file',path='after-conflicts')
        self.success(self.receive(request))
        self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=b'next-valid-file'))
        self.assertTrue(self.success(self.receive(request))['delivered'])
        self.assertEqual(5,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_full_pending_cache_refuses_before_original_operation_reservation(self):
        for number in range(4):
            request,_=self.admission(b'pending',path='pending-'+str(number))
            self.success(self.receive(request))
        request,_=self.admission(b'new')
        response=self.receive(request)
        self.assertEqual('DeviceError',response['messageType'])
        self.assertEqual('not_dispatched',response['error']['effectState'])
        self.assertEqual(4,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])

    def test_late_identical_chunk_is_noop_even_after_commit_and_cache_retirement(self):
        raw=b"original native binary"
        request,descriptor=self.admission(raw)
        self.success(self.receive(request))
        frame=transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw)
        self.receiver.receive_bulk(self.peer_a,frame)
        (self.workspace/'selected.bin').write_bytes(b'newer owner edit')
        self.receiver.receive_bulk(self.peer_a,frame)
        self.assertEqual(b'newer owner edit',(self.workspace/'selected.bin').read_bytes())
        with self.assertRaises(PermissionError):
            self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=frame.transfer_id,offset=0,data=b'wrong bytes'))
        self.assertTrue(self.success(self.receive(request))['delivered'])
        self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM file_operations').fetchone()[0])

    def test_settled_transfer_id_cannot_be_rebound_to_a_new_operation(self):
        raw=b"immutable transfer"
        request,descriptor=self.admission(raw)
        self.success(self.receive(request))
        self.receiver.receive_bulk(self.peer_a,transport.Frame(transfer_id=uuid.UUID(descriptor['transferId']),offset=0,data=raw))
        self.assertEqual({},self.receiver._inputs)
        original_job=self.manager.operation(descriptor['operationId'])
        self.manager.jobs.pop(original_job['id'])  # Original SQL reservation survives job retention.
        replacement,_=self.admission(raw,path='another-file',transferId=descriptor['transferId'])
        response=self.receive(replacement)
        self.assertEqual('DeviceError',response['messageType'])
        self.assertEqual('not_dispatched',response['error']['effectState'])
        self.assertEqual(1,self.manager.ledger.execute('SELECT count(*) FROM operations').fetchone()[0])
        self.assertFalse((self.workspace/'another-file').exists())
