import base64
import copy
import hashlib
import json
from pathlib import Path
import sqlite3
import ssl
import subprocess
import sys
import os
import tempfile
import threading
import time
import unittest

import delegation_policy
import device_identity as i
import device_pairing as p
import device_protocol as wire


def tls_peers(a, b, expected_client_certificate_der=None):
    ca, cb = i.pinned_context(b.certificate_der), i.pinned_context(expected_client_certificate_der or a.certificate_der, server_side=True)
    a.load_context(ca)
    b.load_context(cb)
    ai, ao, bi, bo = ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO()
    ac, bc = ca.wrap_bio(ai, ao, server_side=False), cb.wrap_bio(bi, bo, server_side=True)
    adone = bdone = False
    for _ in range(100):
        for connection, which in ((ac, "a"), (bc, "b")):
            try:
                connection.do_handshake()
                if which == "a":
                    adone = True
                else:
                    bdone = True
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                pass
        data = ao.read()
        if data:
            bi.write(data)
        data = bo.read()
        if data:
            ai.write(data)
        if adone and bdone:
            return i.authenticated_peer(bc), i.authenticated_peer(ac)
    raise AssertionError("Mutual TLS handshake did not complete")


def onboarding_target(server):
    client_context = i.pinned_context(server.certificate_der)
    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.minimum_version = ssl.TLSVersion.TLSv1_2
    # Isolated onboarding only. The server has NO authenticated client/permission.
    server.load_context(server_context)
    ci, co, si, so = ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO()
    client = client_context.wrap_bio(ci, co, server_side=False)
    host = server_context.wrap_bio(si, so, server_side=True)
    done_client = done_host = False
    for _ in range(100):
        for connection, side in ((client, "client"), (host, "host")):
            try:
                connection.do_handshake()
                if side == "client":
                    done_client = True
                else:
                    done_host = True
            except (ssl.SSLWantReadError, ssl.SSLWantWriteError):
                pass
        data = co.read()
        if data:
            si.write(data)
        data = so.read()
        if data:
            ci.write(data)
        if done_client and done_host:
            return i.verified_pairing_target(client, server.certificate_der), host
    raise AssertionError("Isolated server-auth onboarding TLS did not complete")


class DevicePairingTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.a = i.provision_identity(Path(self.scratch.name) / "test-only-a")
        self.b = i.provision_identity(Path(self.scratch.name) / "test-only-b")
        self.peer_a, self.peer_b = tls_peers(self.a, self.b)
        self.db = sqlite3.connect(":memory:")
        self.trust = p.DeviceTrust(self.db, threading.RLock())
        self.trust.create_schema()
        self.coordinator = p.PairingCoordinator(self.trust, self.b.device_id, self.b.instance_id)
        self.now = int(time.time() * 1000)
        self.binding = {"screenMode": "main_screen", "packageNames": ["test.pocketworld"]}

    def tearDown(self):
        self.db.close()
        self.scratch.cleanup()

    def grant(self, epoch=1):
        return dict(grantId="grant-1", peerDeviceId=self.a.device_id, localDeviceId=self.b.device_id, authorizationEpoch=epoch, issuedAt=self.now, expiresAt=self.now + 12 * 3600000, capabilities=["tool.invoke", "task.submitGoal", "operation.get"], toolNames=["phone.tap", "phone.observe"], resources=[self.binding], allowedOrigins=[self.a.device_id], allowDelegation=False, maxDelegationDepth=0, toolBudget=4, modelBudget=2)

    def pair(self, ttl=120000, history=False):
        invitation = self.coordinator.create_invitation(self.b, self.a.certificate_fingerprint, self.now, ttl)
        transcript = p.pairing_transcript(invitation, self.peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
        local = p.confirm_locally(self.b, transcript, True)
        remote = p.confirm_locally(self.a, transcript, True)
        owner_grant = self.grant()
        if history:
            owner_grant["historyRead"] = {"expiresAt":self.now+13*3600000,"methods":["operation.get"]}
        receipt = self.coordinator.complete_pairing(invitation, self.peer_a, local, remote.wire(), owner_grant, self.now)
        return invitation, transcript, local, remote, receipt

    def envelope(self, **updates):
        value = dict(protocolVersion="1.0", protocolProfile=wire.PROFILE, messageType="ToolInvocation", method="tool.invoke", requestId="request-1", operationId="operation-1", rootTaskId="root-1", localTaskId="local-1", parentTaskId="parent-1", runId="run-1", attempt=1, executorEpoch=1, instanceId=self.b.instance_id, originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id, grantId="grant-1", authorizationEpoch=1, capabilityVersion="1.0", resourceBinding=self.binding, deadline=self.now + 100000, budget={"allocationId": "allocation-1", "toolCalls": 2, "modelCalls": 0}, arguments={"toolName": "phone.tap", "effect": "write", "arguments": {"x": 10, "y": 20}})
        value.update(updates)
        value["argumentsDigest"] = wire.arguments_digest(value)
        return wire.parse_request(json.dumps(value).encode())

    def test_explicit_android_user_scope_cannot_authorize_other_or_omitted_user(self):
        self.binding["androidUserId"] = 0
        self.pair()
        self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        omitted = {key: value for key, value in self.binding.items() if key != "androidUserId"}
        for requested in (dict(self.binding, androidUserId=1), omitted):
            with self.subTest(resource=requested):
                with self.assertRaises(PermissionError):
                    self.coordinator.check_permission(self.peer_a, self.envelope(resourceBinding=requested), self.now)

    def test_omitted_android_user_scope_keeps_exact_legacy_semantics(self):
        self.pair()
        self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        for user in (0, 1):
            with self.subTest(androidUserId=user):
                with self.assertRaises(PermissionError):
                    self.coordinator.check_permission(self.peer_a, self.envelope(resourceBinding=dict(self.binding, androidUserId=user)), self.now)

    def test_tls_loading_crash_leaves_only_password_encrypted_owned_temporary(self):
        from cryptography.hazmat.primitives import serialization
        script = """import os,sys; import device_identity as i
class CrashBeforeLoad:
    def load_cert_chain(self,cert,key,password):
        assert callable(password)
        os._exit(73)
i.load_existing_identity(sys.argv[1]).load_context(CrashBeforeLoad())
"""
        result = subprocess.run([sys.executable, "-c", script, str(self.a.directory)],
                                env=dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parent)),
                                capture_output=True, timeout=30)
        self.assertEqual(73, result.returncode, result.stderr.decode())
        leftovers = list(self.a.directory.glob("tls-key-v1-*"))
        self.assertEqual(1, len(leftovers))
        encrypted = (leftovers[0] / "key.pem").read_bytes()
        self.assertTrue(encrypted.startswith(b"-----BEGIN ENCRYPTED PRIVATE KEY-----"))
        with self.assertRaises(TypeError):
            serialization.load_pem_private_key(encrypted, password=None)
        self.assertEqual({"owner.lock", "key.pem", "cert.pem"}, {f.name for f in leftovers[0].iterdir()})
        self.a.load_context(i.pinned_context(self.b.certificate_der))
        self.assertFalse(list(self.a.directory.glob("tls-key-v1-*")))
        self.assertEqual(self.a.device_id, tls_peers(self.a, self.b)[0].device_id)

    def test_tls_leftover_cleanup_proves_ownership_and_refuses_links_and_unknown_files(self):
        legacy = self.a.directory / "tls-abc12345"
        legacy.mkdir(mode=0o700)
        for name, content in (("key.pem", self.a._key()), ("cert.pem", ssl.DER_cert_to_PEM_cert(self.a.certificate_der).encode())):
            path = legacy / name
            path.write_bytes(content)
            path.chmod(0o600)
        self.assertEqual(1, i.cleanup_tls_temporaries(self.a.directory, self.a.certificate_der)["cleaned"])
        unknown = self.a.directory / "tls-user-not-product"
        unknown.mkdir(mode=0o700)
        (unknown / "notes").write_text("keep")
        lookalike = self.a.directory / "tls-abc12345"
        lookalike.mkdir(mode=0o700)
        (lookalike / "notes").write_text("also keep")
        result = i.cleanup_tls_temporaries(self.a.directory, self.a.certificate_der)
        self.assertEqual(1, result["unsafe"])
        self.assertTrue((lookalike / "notes").exists())
        self.assertTrue((unknown / "notes").exists())
        if os.name != "nt":
            linked = self.a.directory / ("tls-key-v1-" + "f" * 32)
            linked.symlink_to(unknown, target_is_directory=True)
            self.assertEqual(2, i.cleanup_tls_temporaries(self.a.directory, self.a.certificate_der)["unsafe"])
            self.assertTrue((unknown / "notes").exists())

    def test_history_receipt_uses_original_sql_audience_after_execution_expiry_not_a_new_execution(self):
        import pocket_companion
        manager = pocket_companion.JobManager({"stateDir": str(Path(self.scratch.name) / "history-original-manager")}, "test-only-" + "x" * 40)
        class OriginalAudience(p.NativeHistoryAudienceSource):
            def accepted_audience(native, query):
                row = manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (query.arguments.get("operationId"),)).fetchone()
                return None if row is None else json.loads(row[0])["acceptedAudience"]
            def source_visible(native, audience, now_ms):
                row = manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (audience["objectId"],)).fetchone()
                return row is not None and not json.loads(row[0]).get("historyVisibilityWithdrawn", False)
        try:
            original = p.DeviceTrust(manager.ledger, manager.lock)
            original.create_schema()
            self.trust = original
            self.coordinator = p.PairingCoordinator(original, self.b.device_id, self.b.instance_id)
            # Persisted old binding is readable, not promoted to a supported execution profile.
            self.binding = {"screenMode":"main_screen", "displayId":0}
            grant = self.grant()
            grant.update(expiresAt=self.now + 1000, toolBudget=0, modelBudget=0,
                         historyRead={"expiresAt":self.now + 86400000, "methods":["operation.get"]})
            invitation = self.coordinator.create_invitation(self.b, None, self.now)
            transcript = p.pairing_transcript(invitation, self.peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
            self.coordinator.complete_pairing(invitation, self.peer_a, p.confirm_locally(self.b, transcript, True), p.confirm_locally(self.a, transcript, True).wire(), grant, self.now)
            audience = dict(kind="operation",objectId="operation-1",originDeviceId=self.a.device_id,callerDeviceId=self.a.device_id,targetDeviceId=self.b.device_id,instanceId=self.b.instance_id,rootTaskId="root-1",localTaskId="local-1",requestRunId="source-run-1",requestExecutorEpoch=1,grantId="grant-1",authorizationEpoch=1,resourceBinding=self.binding,acceptedAt=self.now,retainedUntil=self.now+86400000)
            details = {"acceptedAudience":audience,"status":"unknown"}
            manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)", ("operation-1","test-only-frozen-original-digest","archived-job","unknown",self.now,json.dumps(details)))
            manager.ledger.commit()
            manager.jobs.clear()  # Query does not require the original job in memory.
            query = self.read_query(deadline=self.now+12000,arguments={"operationId":"operation-1"})
            now = self.now+2000
            with self.assertRaises(PermissionError):
                self.coordinator.check_permission(self.peer_a, query, now)
            history = self.coordinator.check_history_permission(self.peer_a, query, OriginalAudience(), now)
            self.assertIsInstance(history,p.HistoryPermission)
            self.assertNotIsInstance(history,p.Permission)
            history.check_current(now)
            self.assertEqual("operation-1", history.audience["objectId"])
            self.assertEqual(1, manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
            typed_alias = self.read_query(deadline=self.now+12000,arguments={"operationId":"operation-1"},resourceBinding=dict(self.binding,displayId=False))
            with self.assertRaises(PermissionError):
                self.coordinator.check_history_permission(self.peer_a, typed_alias, OriginalAudience(), now)
            unsafe = self.envelope(deadline=self.now+12000)
            with self.assertRaises(PermissionError):
                self.coordinator.check_history_permission(self.peer_a, unsafe, OriginalAudience(), now)
            # Native owner visibility changes are read from the same original record.
            details["historyVisibilityWithdrawn"] = True
            manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id='operation-1'",(json.dumps(details),));manager.ledger.commit()
            with self.assertRaises(PermissionError):
                history.check_current(now)
            details["historyVisibilityWithdrawn"] = False
            manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id='operation-1'",(json.dumps(details),));manager.ledger.commit()
            self.coordinator.revoke_locally(self.a.device_id,now)
            with self.assertRaises(PermissionError):
                history.check_current(now)
            self.assertEqual("unknown", manager.ledger.execute("SELECT last_status FROM operations WHERE operation_id='operation-1'").fetchone()[0])
        finally:
            manager.close()

    def test_history_requires_explicit_independent_policy_and_fresh_query_and_rate_guard(self):
        self.pair()
        audience = dict(kind="operation",objectId="operation-1",originDeviceId=self.a.device_id,callerDeviceId=self.a.device_id,targetDeviceId=self.b.device_id,instanceId=self.b.instance_id,rootTaskId="root-1",localTaskId="local-1",runId="run-1",executorEpoch=1,grantId="grant-1",authorizationEpoch=1,resourceBinding=self.binding,acceptedAt=self.now,retainedUntil=self.now+13*3600000)
        class NativeSource(p.NativeHistoryAudienceSource):
            def accepted_audience(native,query):return copy.deepcopy(audience)
        source=NativeSource()
        late=self.now+12*3600000+1
        query=self.read_query(deadline=late+10000,arguments={"operationId":"operation-1"})
        with self.assertRaises(PermissionError):self.coordinator.check_history_permission(self.peer_a,query,source,late)
        self.coordinator._history_read_times.clear()  # next independent clock/rate scenario
        early=self.read_query(deadline=self.now+10000,arguments={"operationId":"operation-1"})
        self.coordinator.check_history_permission(self.peer_a,early,source,self.now)
        for _ in range(239):self.coordinator.check_history_permission(self.peer_a,early,source,self.now)
        with self.assertRaises(PermissionError):self.coordinator.check_history_permission(self.peer_a,early,source,self.now)
        self.coordinator._history_read_times.clear()  # isolated guard test, not an execution budget refund
        too_long=self.read_query(deadline=self.now+30001,arguments={"operationId":"operation-1"})
        with self.assertRaises(PermissionError):self.coordinator.check_history_permission(self.peer_a,too_long,source,self.now)

    def test_history_epoch_change_invalidates_old_handle_and_requires_new_explicit_read_policy(self):
        self.pair()
        audience=dict(kind="operation",objectId="operation-1",originDeviceId=self.a.device_id,callerDeviceId=self.a.device_id,targetDeviceId=self.b.device_id,instanceId=self.b.instance_id,rootTaskId="root-1",localTaskId="local-1",grantId="grant-1",authorizationEpoch=1,resourceBinding=self.binding,acceptedAt=self.now,retainedUntil=self.now+86400000)
        class Source(p.NativeHistoryAudienceSource):
            def accepted_audience(native,query):return copy.deepcopy(audience)
        source=Source();old=self.coordinator.check_history_permission(self.peer_a,self.read_query(),source,self.now)
        changed=self.grant(2);changed["historyRead"]={"expiresAt":self.now+86400000,"methods":["operation.get"]}
        self.trust.replace_grant(changed,self.now)
        with self.assertRaises(PermissionError):old.check_current(self.now)
        fresh=self.coordinator.check_history_permission(self.peer_a,self.read_query(authorizationEpoch=2),source,self.now)
        self.assertEqual(2,fresh.authorization_epoch);self.assertEqual(1,fresh.accepted_authorization_epoch)
        self.trust.replace_grant(self.grant(3),self.now)
        with self.assertRaises(PermissionError):self.coordinator.check_history_permission(self.peer_a,self.read_query(authorizationEpoch=3),source,self.now)
        changed=self.grant(4);changed["historyRead"]={"expiresAt":self.now+86400000,"methods":["operation.get"]};self.trust.replace_grant(changed,self.now)
        current=self.coordinator.check_history_permission(self.peer_a,self.read_query(authorizationEpoch=4),source,self.now)
        incomplete=copy.deepcopy(audience);incomplete["runId"]="not-a-real-pair"
        class Incomplete(Source):
            def accepted_audience(native,query):return incomplete
        with self.assertRaises(PermissionError):self.coordinator.check_history_permission(self.peer_a,self.read_query(authorizationEpoch=4),Incomplete(),self.now)
        audience.update(runId="native-target-run-bound-later",executorEpoch=1)
        with self.assertRaises(PermissionError):current.check_current(self.now)
        refreshed=self.coordinator.check_history_permission(self.peer_a,self.read_query(authorizationEpoch=4),source,self.now)
        self.coordinator.revoke_locally(self.a.device_id,self.now)
        with self.assertRaises(PermissionError):refreshed.check_current(self.now)
        self.assertEqual(1,audience["authorizationEpoch"])

    def test_operation_management_stops_original_callers_job_despite_source_withdrawal_but_never_reads(self):
        self.pair()
        original_digest="d"*64
        grant=self.grant(2);grant.update(capabilities=["operation.cancel"],toolBudget=0,modelBudget=0);self.trust.replace_grant(grant,self.now)
        # Native accepted-object fixture. Source authority is deliberately unavailable;
        # this test is management AUTHORIZATION only, not an executed OS stop.
        origin="pw1_"+"c"*64
        audience=dict(kind="operation",objectId="original-operation",argumentsDigest=original_digest,originDeviceId=origin,callerDeviceId=self.a.device_id,targetDeviceId=self.b.device_id,instanceId=self.b.instance_id,rootTaskId="root-1",localTaskId="local-1",grantId="grant-1",authorizationEpoch=1,resourceBinding=self.binding,acceptedAt=self.now-1000,retainedUntil=self.now-1,sourceShareId="ds1_source-original",sourceIssuerDeviceId=origin,sourceGrantId="origin-grant",sourceAuthorizationEpoch=1,sourceProofFingerprint="c"*64)
        class Source(p.NativeHistoryAudienceSource):
            visibility_calls=0
            def accepted_audience(native,request):return copy.deepcopy(audience)
            def source_visible(native,audience,now_ms):native.visibility_calls+=1;return False
        source=Source()
        def request_for(epoch,control_id,digest=original_digest,**changes):
            value=self.read_query(authorizationEpoch=epoch,**changes).json();value.update(method="operation.cancel",operationId=control_id,arguments={"operationId":"original-operation","argumentsDigest":digest});value["argumentsDigest"]=wire.arguments_digest(value);return wire.parse_request(wire.canonical_bytes(value))
        request=request_for(2,"cancel-original-1")
        self.coordinator._history_read_times[self.a.device_id]=[self.now]*240
        management=self.coordinator.check_operation_management_permission(self.peer_a,request,source,self.now)
        self.assertNotIsInstance(management,p.Permission);self.assertNotIsInstance(management,p.HistoryPermission)
        management.check_current(self.now);self.assertEqual(0,source.visibility_calls)
        with self.assertRaises(PermissionError):self.coordinator.check_permission(self.peer_a,request,self.now)
        with self.assertRaises(PermissionError):self.coordinator.check_operation_management_permission(self.peer_a,request_for(2,"wrong-target-digest","e"*64),source,self.now)
        # Current C owner removes A/C origin execution visibility but explicitly
        # keeps original-caller operation.cancel. Stop remains a de-escalation.
        withdrawn=self.grant(3);withdrawn.update(capabilities=["operation.cancel"],allowedOrigins=[self.b.device_id],toolBudget=0,modelBudget=0);self.trust.replace_grant(withdrawn,self.now)
        with self.assertRaises(PermissionError):management.check_current(self.now)
        stop=self.coordinator.check_operation_management_permission(self.peer_a,request_for(3,"cancel-after-source-withdrawal"),source,self.now)
        stop.check_current(self.now);self.assertEqual(0,source.visibility_calls)
        other=i.provision_identity(Path(self.scratch.name)/"other-caller-cancel-test-only");actual_other,actual_b=tls_peers(other,self.b)
        other_grant=self.grant();other_grant.update(grantId="other-grant",peerDeviceId=other.device_id,allowedOrigins=[other.device_id],capabilities=["operation.cancel"],toolBudget=0,modelBudget=0)
        invitation=self.coordinator.create_invitation(self.b,None,self.now);transcript=p.pairing_transcript(invitation,actual_b,other.device_id,other.instance_id,other.certificate_fingerprint,self.now)
        self.coordinator.complete_pairing(invitation,actual_other,p.confirm_locally(self.b,transcript,True),p.confirm_locally(other,transcript,True).wire(),other_grant,self.now)
        other_request=request_for(1,"other-caller-cancel",originDeviceId=other.device_id,callerDeviceId=other.device_id,grantId="other-grant")
        with self.assertRaises(PermissionError):self.coordinator.check_operation_management_permission(actual_other,other_request,source,self.now)
        self.assertEqual(0,source.visibility_calls)
        self.coordinator.revoke_locally(self.a.device_id,self.now)
        with self.assertRaises(PermissionError):stop.check_current(self.now)


    def test_invitation_preview_has_no_key_creation_token_consumption_or_business_authority(self):
        invitation=self.coordinator.create_invitation(self.b,None,self.now,connection_hint={"kind":"lan","host":"127.0.0.1","candidatePort":37101,"completionPort":37102,"controlPort":37103,"bulkPort":37104})
        before=tuple(self.db.execute("SELECT state,transcript_digest FROM device_pairing_invites").fetchone())
        preview=p.validate_invitation_for_preview(invitation,self.now)
        self.assertEqual(self.b.certificate_fingerprint,preview["certificateFingerprint"])
        self.assertEqual(before,tuple(self.db.execute("SELECT state,transcript_digest FROM device_pairing_invites").fetchone()))
        self.assertIsNone(self.trust.peer(self.a.device_id))
        for changes in ({"certificateFingerprint":"f"*64},{"expiresAt":self.now},{"inviterDeviceId":self.a.device_id},{"unexpected":True}):
            with self.assertRaises((PermissionError,wire.ProtocolError,ValueError)):p.validate_invitation_for_preview(dict(invitation,**changes),self.now)

    def test_pairgrant_resource_types_are_exact_before_any_execution(self):
        self.pair()
        self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        for display in (False, True, "0", None):
            with self.subTest(display=display), self.assertRaises((PermissionError, wire.ProtocolError)):
                self.coordinator.check_permission(self.peer_a, self.envelope(resourceBinding=dict(self.binding, displayId=display)), self.now)

    def test_current_execution_profiles_reject_boolean_numeric_scope_before_admission(self):
        self.pair()
        for binding in (dict(self.binding, allowWholeDisplayCapture=1), dict(self.binding, packageNames=[False]),
                        {"kind":"desktop","sessionId":False,"displayId":"primary"}):
            capability = "desktop-v2" if "kind" in binding else "1.0"
            with self.subTest(binding=binding), self.assertRaises(wire.ProtocolError):
                self.coordinator.check_permission(self.peer_a, self.envelope(capabilityVersion=capability, resourceBinding=binding), self.now)
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])


    def read_query(self, **updates):
        # Untrusted wire fixture, not a product origin-clearing API. Real clients
        # use the private native HistoryQuery factory and an actual target route.
        value=dict(protocolVersion="1.0", protocolProfile=wire.PROFILE,messageType="DeviceRequest",method="operation.get",requestId="read-fixture-1",originDeviceId=self.a.device_id,callerDeviceId=self.a.device_id,targetDeviceId=self.b.device_id,instanceId=self.b.instance_id,grantId="grant-1",authorizationEpoch=1,resourceBinding=self.binding,deadline=self.now+15000,arguments={"operationId":"operation-1"})
        value.update(updates)
        return wire.parse_request(wire.canonical_bytes(value))

    def test_loading_missing_identity_never_provisions_or_creates_a_directory(self):
        missing = Path(self.scratch.name) / "not-onboarded"
        with self.assertRaises(FileNotFoundError):
            i.load_existing_identity(missing)
        self.assertFalse(missing.exists())
        loaded = i.load_existing_identity(self.a.directory)
        self.assertEqual(self.a.device_id, loaded.device_id)
        self.assertEqual(self.a.instance_id, loaded.instance_id)

    def test_actual_mutual_tls_spki_identity_is_not_a_json_name(self):
        self.assertEqual(self.a.device_id, self.peer_a.device_id)
        self.assertEqual(self.b.device_id, self.peer_b.device_id)
        self.assertEqual(self.a.certificate_fingerprint, self.peer_a.certificate_fingerprint)
        with self.assertRaises(TypeError):
            i.AuthenticatedPeer(None, self.a.certificate_der, "json-owner")
        with self.assertRaises(PermissionError):
            self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        with self.assertRaises(ValueError):
            i.require_pin(self.peer_a, self.b.certificate_der)

    def test_isolated_server_auth_offer_is_never_a_business_peer_or_permission(self):
        target, server_channel = onboarding_target(self.b)
        self.assertIsInstance(target, i.VerifiedPairingTarget)
        self.assertNotIsInstance(target, i.AuthenticatedPeer)
        with self.assertRaises(ValueError):
            i.authenticated_peer(server_channel)
        invitation = self.coordinator.create_invitation(self.b, None, self.now)
        with self.assertRaises(PermissionError):
            p.create_candidate_offer(self.a, invitation, target, False, self.now)
        offer = p.create_candidate_offer(self.a, invitation, target, True, self.now)
        self.assertLessEqual(len(offer.wire_bytes()), 16384)
        self.assertEqual("PairingCandidate", offer.wire()["messageType"])
        with self.assertRaises(wire.ProtocolError):
            wire.parse_request(offer.wire_bytes())
        self.assertIsNone(self.trust.peer(self.a.device_id))
        with self.assertRaises(PermissionError):
            self.coordinator.check_permission(target, self.envelope(), self.now)

    def candidate_offer(self, ttl_ms=120000):
        target, _ = onboarding_target(self.b)
        invitation = self.coordinator.create_invitation(self.b, None, self.now, ttl_ms)
        return p.create_candidate_offer(self.a, invitation, target, True, self.now, reviewed_owner_grant=self.reciprocal_grant())

    def reciprocal_grant(self):
        grant = self.grant()
        grant.update(grantId="grant-a", peerDeviceId=self.b.device_id, localDeviceId=self.a.device_id, allowedOrigins=[self.b.device_id])
        return grant

    def test_first_pairing_candidate_requires_owner_then_exact_mtls_before_any_grant(self):
        offer = self.candidate_offer()
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        self.assertNotIsInstance(candidate, i.AuthenticatedPeer)
        self.assertNotIsInstance(candidate, p.Permission)
        receipt = self.coordinator.candidate_receipt(candidate, self.now)
        self.assertEqual("pending_owner_confirmation", p.verify_candidate_receipt(offer, wire.canonical_bytes(receipt))["state"])
        self.assertIsNone(self.trust.peer(self.a.device_id))
        self.assertIsNone(self.trust.grant("grant-1"))
        with self.assertRaises(PermissionError):
            self.coordinator.confirm_candidate_locally(self.b, candidate, False, self.now)
        pending = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now, reviewed_owner_grant=self.grant())
        self.assertEqual(self.a.certificate_der, pending.peer_certificate_der)
        confirmed = self.coordinator.candidate_receipt(candidate, self.now)
        self.assertEqual("confirmed_for_mtls", p.verify_candidate_receipt(offer, wire.canonical_bytes(confirmed))["state"])
        self.assertIsNone(self.trust.peer(self.a.device_id))
        peer_a, peer_b = tls_peers(self.a, self.b, pending.peer_certificate_der)
        finalize = wire.canonical_bytes(p.candidate_finalize_request(offer))
        with self.assertRaises(PermissionError):
            self.coordinator.finalize_candidate_pairing(candidate, peer_a, finalize, self.grant(), self.now)
        with self.assertRaises(PermissionError):
            self.coordinator.finalize_candidate_pairing(pending, peer_a, finalize, dict(self.grant(), toolBudget=400), self.now)
        completion = self.coordinator.finalize_candidate_pairing(pending, peer_a, finalize, self.grant(), self.now)
        self.assertEqual("consumed", self.trust.candidate(candidate.invite_id)["state"])
        self.assertEqual("paired", completion["pairingReceipt"]["status"])
        replay = self.coordinator.finalize_candidate_pairing(pending, peer_a, finalize, self.grant(), self.now + 1)
        self.assertEqual(completion["pairingReceipt"], replay["pairingReceipt"])
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
        db_a = sqlite3.connect(":memory:")
        try:
            trust_a = p.DeviceTrust(db_a, threading.RLock())
            trust_a.create_schema()
            owner_a = p.PairingCoordinator(trust_a, self.a.device_id, self.a.instance_id)
            reciprocal = self.grant()
            reciprocal.update(grantId="grant-a", peerDeviceId=self.b.device_id, localDeviceId=self.a.device_id, allowedOrigins=[self.b.device_id])
            with self.assertRaises(PermissionError):
                owner_a.accept_candidate_completion(offer, peer_b, wire.canonical_bytes(completion), dict(reciprocal, toolBudget=400), self.now)
            self.assertIsNone(trust_a.peer(self.b.device_id))
            outgoing = owner_a.accept_candidate_completion(offer, peer_b, wire.canonical_bytes(completion), reciprocal, self.now)
            outgoing.check_current(self.now)
            self.assertEqual(self.b.device_id, outgoing.target_device_id)
            self.assertIsNotNone(trust_a.peer(self.b.device_id))
        finally:
            db_a.close()
        cached = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now + 2)
        self.assertEqual("paired", self.coordinator.candidate_receipt(cached, self.now + 2)["state"])
        self.coordinator.revoke_locally(self.a.device_id, self.now + 3)
        self.assertFalse(self.coordinator.candidate_receipt(cached, self.now + 4)["currentlyTrusted"])
        self.assertNotIn(offer.wire()["token"], "\n".join(self.db.iterdump()))

    def test_committed_pairing_loss_recovers_same_bytes_after_ttl_and_both_original_sql_reopens(self):
        a_path, b_path = Path(self.scratch.name)/"a-original-trust.db", Path(self.scratch.name)/"b-original-trust.db"
        a_db, b_db = sqlite3.connect(a_path), sqlite3.connect(b_path)
        try:
            a_store, b_store = p.DeviceTrust(a_db, threading.RLock()), p.DeviceTrust(b_db, threading.RLock())
            a_store.create_schema(); b_store.create_schema()
            owner_a = p.PairingCoordinator(a_store, self.a.device_id, self.a.instance_id)
            owner_b = p.PairingCoordinator(b_store, self.b.device_id, self.b.instance_id)
            target, _ = onboarding_target(self.b)
            invitation = owner_b.create_invitation(self.b, None, self.now, 1000)
            offer = owner_a.prepare_candidate_offer(self.a, invitation, target, True, self.now, reviewed_owner_grant=self.reciprocal_grant())
            original_candidate_wire=offer.wire_bytes()
            self.assertEqual((), tuple(owner_a.pending_candidates(self.now)))
            self.assertIsNone(a_store.peer(self.b.device_id))
            candidate = owner_b.inspect_candidate(self.b, offer.wire_bytes(), self.now)
            pending = owner_b.confirm_candidate_locally(self.b, candidate, True, self.now, reviewed_owner_grant=self.grant())
            peer_a, peer_b = tls_peers(self.a, self.b, pending.peer_certificate_der)
            finalize = wire.canonical_bytes(p.candidate_finalize_request(offer))
            completion = owner_b.finalize_candidate_pairing(pending, peer_a, finalize, self.grant(), self.now+100)
            self.assertIs(False, completion["grantReceipt"]["body"]["grant"]["revoked"])
            choices=owner_b.committed_pairing_recovery_candidates(self.now+100)
            self.assertEqual(1,len(choices));self.assertEqual("available",choices[0]["recoveryState"])
            self.assertNotIn("certificateDer", wire.canonical_bytes(list(choices)).decode());self.assertNotIn("grantReceipt",wire.canonical_bytes(list(choices)).decode())
            frozen = wire.canonical_bytes(completion)
            self.assertEqual(frozen, wire.canonical_bytes(owner_b.finalize_candidate_pairing(pending, peer_a, finalize, self.grant(), self.now+101)))
            self.assertEqual(1, b_db.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
            self.assertEqual(0, a_db.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
            for db in (a_db, b_db):self.assertNotIn(invitation["token"], "\n".join(db.iterdump()))
            a_db.close(); b_db.close()
            # Both identities load EXISTING keys; neither native Pending nor secret
            # CandidateOffer survives. Only the original SQL public review/commit does.
            a_db, b_db = sqlite3.connect(a_path), sqlite3.connect(b_path)
            a_store, b_store = p.DeviceTrust(a_db, threading.RLock()), p.DeviceTrust(b_db, threading.RLock())
            local_a, local_b = i.load_existing_identity(self.a.directory), i.load_existing_identity(self.b.directory)
            owner_a = p.PairingCoordinator(a_store, local_a.device_id, local_a.instance_id)
            owner_b = p.PairingCoordinator(b_store, local_b.device_id, local_b.instance_id)
            recovery_time = self.now+1001
            saved_offers = owner_a.pending_local_pairing_offers(recovery_time)
            self.assertEqual(1, len(saved_offers));self.assertTrue(saved_offers[0]["recoveryOnly"])
            restore = owner_a.restore_pairing_offer(local_a, invitation["inviteId"], recovery_time)
            recovery = owner_b.committed_pairing_recovery(local_b, restore.invite_id, restore.candidate_digest, recovery_time)
            self.assertNotIsInstance(recovery, p.PendingPairing);self.assertNotIsInstance(recovery, p.Permission)
            self.assertNotIsInstance(restore, p.CandidateOffer);self.assertNotIsInstance(restore, p.Permission)
            peer_a, peer_b = tls_peers(local_a, local_b, recovery.peer_certificate_der)
            request = wire.canonical_bytes(p.candidate_recovery_request(restore))
            received = owner_b.recover_candidate_pairing(recovery, peer_a, request, recovery_time)
            self.assertEqual(frozen, wire.canonical_bytes(received))
            outgoing = owner_a.accept_recovered_candidate_completion(restore, peer_b, wire.canonical_bytes(received), recovery_time)
            outgoing.check_current(recovery_time)
            # Same-epoch discovery signs the original SQL grant shape. Refresh is
            # allowed without changing authority; old native route remains current.
            refreshed=owner_b.issue_grant_receipt(local_b,"grant-1",recovery_time)
            owner_a.accept_outgoing_grant(peer_b,refreshed,recovery_time)
            outgoing.check_current(recovery_time)
            owner_a.accept_recovered_candidate_completion(restore, peer_b, frozen, recovery_time+1)
            self.assertEqual(1, a_db.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
            self.assertEqual(1, a_db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
            self.assertEqual(self.reciprocal_grant()["toolBudget"], a_store.grant("grant-a")["toolBudget"])
            with self.assertRaises(PermissionError):owner_b.recover_candidate_pairing(recovery, peer_a, finalize, recovery_time)
            with self.assertRaises(PermissionError):owner_b.committed_pairing_recovery(local_b, restore.invite_id, "f"*64, recovery_time)
            stranger = i.provision_identity(Path(self.scratch.name)/"test-only-unrelated-recovery")
            actual_stranger, _ = tls_peers(stranger, local_b)
            with self.assertRaises(PermissionError):owner_b.recover_candidate_pairing(recovery, actual_stranger, request, recovery_time)
            tampered = copy.deepcopy(received);tampered["pairingReceipt"]["commitReceipt"]["body"]["committedAt"] = self.now+1001
            from cryptography.exceptions import InvalidSignature
            with self.assertRaises(InvalidSignature):owner_a.accept_recovered_candidate_completion(restore, peer_b, wire.canonical_bytes(tampered), recovery_time)
            owner_b.revoke_locally(local_a.device_id, recovery_time+2)
            with self.assertRaises(PermissionError):recovery.check_current(recovery_time+3)
            with self.assertRaises(PermissionError):owner_b.committed_pairing_recovery(local_b, restore.invite_id, restore.candidate_digest, recovery_time+3)
            self.assertEqual(frozen, wire.canonical_bytes(b_store.committed_completion(restore.invite_id)))
            self.assertEqual("revoked",owner_b.committed_pairing_recovery_candidates(recovery_time+3)[0]["recoveryState"])
            historic = owner_b.candidate_receipt(owner_b.inspect_candidate(local_b, original_candidate_wire, recovery_time+3), recovery_time+3)
            self.assertEqual("paired", historic["state"]);self.assertFalse(historic["currentlyTrusted"])
            owner_a.revoke_locally(local_b.device_id, recovery_time+4)
            with self.assertRaises(PermissionError):owner_a.restore_pairing_offer(local_a, restore.invite_id, recovery_time+5)
        finally:
            a_db.close(); b_db.close()

    def test_uncommitted_expiry_and_atomic_commit_failure_never_become_recovery_authority(self):
        offer = self.candidate_offer(1000)
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        pending = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now, reviewed_owner_grant=self.grant())
        finalize = wire.canonical_bytes(p.candidate_finalize_request(offer))
        self.db.execute("CREATE TEMP TRIGGER fail_original_pair_commit BEFORE UPDATE OF state ON device_pairing_invites WHEN NEW.state='consumed' BEGIN SELECT RAISE(ABORT,'original transaction injected failure'); END")
        with self.assertRaises(sqlite3.IntegrityError):self.coordinator.finalize_candidate_pairing(pending, self.peer_a, finalize, self.grant(), self.now+100)
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM device_peers").fetchone()[0])
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM device_grants").fetchone()[0])
        self.assertIsNone(self.trust.committed_completion(candidate.invite_id))
        with self.assertRaises(PermissionError):self.coordinator.finalize_candidate_pairing(pending, self.peer_a, finalize, self.grant(), self.now+1001)
        with self.assertRaises(PermissionError):self.coordinator.committed_pairing_recovery(self.b, candidate.invite_id, candidate.candidate_digest, self.now+1001)
        self.assertEqual("confirmed", self.trust.candidate(candidate.invite_id)["state"])

    def test_native_local_offer_abandonment_fences_old_handles_late_install_and_releases_limit_without_remote_ack(self):
        a_db=sqlite3.connect(":memory:");c_db=sqlite3.connect(":memory:")
        try:
            a_store=p.DeviceTrust(a_db,threading.RLock());a_store.create_schema();owner_a=p.PairingCoordinator(a_store,self.a.device_id,self.a.instance_id)
            target,_=onboarding_target(self.b);offers=[]
            for _ in range(8):
                invite=self.coordinator.create_invitation(self.b,None,self.now,1000)
                offers.append(owner_a.prepare_candidate_offer(self.a,invite,target,True,self.now,reviewed_owner_grant=self.reciprocal_grant()))
            ninth_invite=self.coordinator.create_invitation(self.b,None,self.now,1000)
            with self.assertRaises(PermissionError):owner_a.prepare_candidate_offer(self.a,ninth_invite,target,True,self.now,reviewed_owner_grant=self.reciprocal_grant())
            original=offers[0];original_wire=original.wire_bytes();finalize=wire.canonical_bytes(p.candidate_finalize_request(original))
            candidate=self.coordinator.inspect_candidate(self.b,original_wire,self.now)
            pending=self.coordinator.confirm_candidate_locally(self.b,candidate,True,self.now,reviewed_owner_grant=self.grant())
            completion=self.coordinator.finalize_candidate_pairing(pending,self.peer_a,finalize,self.grant(),self.now+100)
            before=wire.canonical_bytes(a_store.local_pairing_offer(candidate.invite_id))
            recovery=owner_a.restore_pairing_offer(self.a,candidate.invite_id,self.now+1001)
            choice=owner_a.review_local_pairing_offer_abandonment(candidate.invite_id,candidate.candidate_digest,self.now+1001)
            self.assertFalse(choice.display()["remoteRevocationAcknowledged"]);self.assertTrue(choice.display()["localOnly"])
            with self.assertRaises(TypeError):p.LocalOfferAbandonmentReview(None,owner_a,{},self.now)
            with self.assertRaises(PermissionError):owner_a.abandon_local_pairing_offer(choice,False,self.now+1001)
            self.assertEqual(8,len(owner_a.pending_local_pairing_offers(self.now+1001)))
            outcome=owner_a.abandon_local_pairing_offer(choice,True,self.now+1001)
            self.assertEqual("locally_abandoned",outcome["state"]);self.assertFalse(outcome["remoteRevocationAcknowledged"])
            self.assertEqual("rejected",a_store.local_pairing_offer_state(candidate.invite_id));self.assertEqual(before,wire.canonical_bytes(a_store.local_pairing_offer(candidate.invite_id)))
            self.assertEqual(7,len(owner_a.pending_local_pairing_offers(self.now+1001)))
            with self.assertRaises(PermissionError):original.wire_bytes()
            with self.assertRaises(PermissionError):p.candidate_finalize_request(original)
            with self.assertRaises(PermissionError):recovery.check_current(self.now+1001)
            with self.assertRaises(PermissionError):p.candidate_recovery_request(recovery)
            with self.assertRaises(PermissionError):owner_a.restore_pairing_offer(self.a,candidate.invite_id,self.now+1001)
            with self.assertRaises(PermissionError):owner_a.accept_candidate_completion(original,self.peer_b,wire.canonical_bytes(completion),self.reciprocal_grant(),self.now+1001)
            with self.assertRaises(PermissionError):owner_a.accept_recovered_candidate_completion(recovery,self.peer_b,wire.canonical_bytes(completion),self.now+1001)
            # Final ORIGINAL SQL fence also rejects a previously verified late install
            # even if cancellation won after the caller's earlier native checks.
            peer_b={"deviceId":self.b.device_id,"certificateDer":base64.b64encode(self.b.certificate_der).decode(),"fingerprint":self.b.certificate_fingerprint,"instanceId":self.b.instance_id,"authorizationEpoch":1,"revoked":False,"updatedAt":self.now}
            with self.assertRaises(PermissionError):a_store.install_confirmed_peer(candidate.invite_id,original._local._transcript,peer_b,self.reciprocal_grant(),completion["pairingReceipt"],self.now+1001)
            self.assertIsNone(a_store.peer(self.b.device_id));self.assertIsNone(a_store.grant("grant-a"))
            # The other side may already have committed. Local abandonment is NOT
            # its revoke/ACK and cannot erase the other owner's independent grant.
            self.assertFalse(self.trust.peer(self.a.device_id)["revoked"])
            self.assertEqual("locally_abandoned",owner_a.abandon_local_pairing_offer(choice,True,self.now+1002)["state"])
            c=i.provision_identity(Path(self.scratch.name)/"test-only-new-target-after-local-abandon")
            c_store=p.DeviceTrust(c_db,threading.RLock());c_store.create_schema();owner_c=p.PairingCoordinator(c_store,c.device_id,c.instance_id)
            c_target,_=onboarding_target(c);c_invite=owner_c.create_invitation(c,None,self.now+1001)
            a_grant_c=self.reciprocal_grant();a_grant_c.update(grantId="grant-a-c",peerDeviceId=c.device_id,allowedOrigins=[c.device_id])
            new_offer=owner_a.prepare_candidate_offer(self.a,c_invite,c_target,True,self.now+1001,reviewed_owner_grant=a_grant_c)
            self.assertEqual(8,len(owner_a.pending_local_pairing_offers(self.now+1001)))
            active_choice=owner_a.review_local_pairing_offer_abandonment(c_invite["inviteId"],new_offer.candidate_digest,self.now+1001)
            c_candidate=owner_c.inspect_candidate(c,new_offer.wire_bytes(),self.now+1001);c_grant=self.grant();c_grant.update(grantId="grant-c-a",localDeviceId=c.device_id)
            c_pending=owner_c.confirm_candidate_locally(c,c_candidate,True,self.now+1001,reviewed_owner_grant=c_grant)
            actual_a,actual_c=tls_peers(self.a,c,c_pending.peer_certificate_der)
            c_completion=owner_c.finalize_candidate_pairing(c_pending,actual_a,wire.canonical_bytes(p.candidate_finalize_request(new_offer)),c_grant,self.now+1001)
            owner_a.accept_candidate_completion(new_offer,actual_c,wire.canonical_bytes(c_completion),a_grant_c,self.now+1001)
            with self.assertRaises(PermissionError):owner_a.abandon_local_pairing_offer(active_choice,True,self.now+1002)
            self.assertFalse(a_store.peer(c.device_id)["revoked"])
            self.assertEqual(7,len(owner_a.pending_local_pairing_offers(self.now+1002)))
        finally:a_db.close();c_db.close()

    def test_candidate_cache_does_not_resign_or_replace_one_time_identity(self):
        offer = self.candidate_offer()
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        for _ in range(12):
            cached = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
            self.assertEqual(candidate.candidate_digest, cached.candidate_digest)
        self.assertEqual(1, self.trust.candidate(candidate.invite_id)["attempts"])
        c = i.provision_identity(Path(self.scratch.name) / "test-only-other-candidate")
        target, _ = onboarding_target(self.b)
        competing = p.create_candidate_offer(c, offer._invitation, target, True, self.now)
        with self.assertRaises(PermissionError):
            self.coordinator.inspect_candidate(self.b, competing.wire_bytes(), self.now)
        self.assertEqual(self.a.device_id, self.trust.candidate(candidate.invite_id)["peerDeviceId"])
        self.assertIsNone(self.trust.peer(self.a.device_id))

    def test_bad_signatures_are_bounded_and_token_is_not_persisted(self):
        offer = self.candidate_offer()
        bad = offer.wire()
        bad["confirmation"]["signature"] = base64.b64encode(b"invalid-signature").decode()
        for _ in range(8):
            with self.assertRaises(Exception):
                self.coordinator.inspect_candidate(self.b, wire.canonical_bytes(bad), self.now)
        with self.assertRaisesRegex(PermissionError, "attempts exhausted"):
            self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        self.assertEqual(8, self.trust.candidate(offer._invitation["inviteId"])["attempts"])
        self.assertIsNone(self.trust.peer(self.a.device_id))
        self.assertNotIn(offer.wire()["token"], "\n".join(self.db.iterdump()))

    def test_native_candidate_confirmation_cannot_resurrect_from_sql_after_restart(self):
        offer = self.candidate_offer()
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        pending = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now)
        restored = p.PairingCoordinator(self.trust, self.b.device_id, self.b.instance_id)
        cached = restored.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        self.assertEqual("pending_owner_confirmation", restored.candidate_receipt(cached, self.now)["state"])
        with self.assertRaises(PermissionError):
            restored.finalize_candidate_pairing(pending, self.peer_a, wire.canonical_bytes(p.candidate_finalize_request(offer)), self.grant(), self.now)
        replacement = restored.confirm_candidate_locally(self.b, cached, True, self.now)
        replacement.check_current(self.now)
        restored.reject_candidate_locally(cached, self.now)
        with self.assertRaises(PermissionError):
            replacement.check_current(self.now)
        self.assertEqual("rejected", restored.candidate_receipt(cached, self.now)["state"])

    def test_fingerprint_only_and_superseded_scope_review_cannot_commit_a_grant(self):
        offer = self.candidate_offer()
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        pin_only = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now)
        self.assertEqual("pending_owner_confirmation", self.coordinator.candidate_receipt(candidate, self.now)["state"])
        finalize = wire.canonical_bytes(p.candidate_finalize_request(offer))
        with self.assertRaises(PermissionError):
            self.coordinator.finalize_candidate_pairing(pin_only, self.peer_a, finalize, self.grant(), self.now)
        reviewed = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now, reviewed_owner_grant=self.grant())
        with self.assertRaises(PermissionError):
            pin_only.check_current(self.now)
        narrower = dict(self.grant(), toolBudget=1)
        current = self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now, reviewed_owner_grant=narrower)
        with self.assertRaises(PermissionError):
            reviewed.check_current(self.now)
        current.check_current(self.now)
        self.assertIsNone(self.trust.peer(self.a.device_id))

    def test_candidate_deadline_and_bounded_closed_wire_are_checked_before_trust(self):
        offer = self.candidate_offer(1000)
        for raw in (b" " * 16385, wire.canonical_bytes(dict(offer.wire(), ownerId="owner"))):
            with self.assertRaises((PermissionError, wire.ProtocolError)):
                self.coordinator.inspect_candidate(self.b, raw, self.now)
        candidate = self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now)
        with self.assertRaises(PermissionError):
            self.coordinator.confirm_candidate_locally(self.b, candidate, True, self.now + 1001)
        with self.assertRaises(PermissionError):
            self.coordinator.inspect_candidate(self.b, offer.wire_bytes(), self.now + 1001)
        self.assertIsNone(self.trust.peer(self.a.device_id))

    def test_both_explicit_confirmations_single_use_and_durable_receipt(self):
        invitation, transcript, local, remote, receipt = self.pair()
        self.assertEqual("paired", receipt["status"])
        self.assertNotIn(invitation["token"], " ".join(str(row) for row in self.db.execute("SELECT * FROM device_pairing_invites")))
        repeated = self.coordinator.complete_pairing(invitation, self.peer_a, local, remote.wire(), self.grant(), self.now)
        self.assertEqual(receipt, repeated)
        with self.assertRaises(PermissionError):
            p.confirm_locally(self.a, transcript, False)
        changed = copy.deepcopy(remote.wire())
        changed["transcript"]["joinerFingerprint"] = "f" * 64
        with self.assertRaises(PermissionError):
            self.coordinator.complete_pairing(invitation, self.peer_a, local, changed, self.grant(), self.now)

    def test_expired_and_wrong_invitation_secret_do_not_pair(self):
        invitation = self.coordinator.create_invitation(self.b, self.a.certificate_fingerprint, self.now, 1000)
        with self.assertRaises(PermissionError):
            p.pairing_transcript(invitation, self.peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now + 1001)
        transcript = p.pairing_transcript(invitation, self.peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
        local, remote = p.confirm_locally(self.b, transcript, True), p.confirm_locally(self.a, transcript, True)
        changed = dict(invitation, token="A" * 43)
        with self.assertRaises(PermissionError):
            self.coordinator.complete_pairing(changed, self.peer_a, local, remote.wire(), self.grant(), self.now)
        self.assertIsNone(self.trust.peer(self.a.device_id))

    def test_same_live_connection_cannot_act_after_revoke_and_ack_is_separate(self):
        self.pair()
        permission = self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        permission.check_current(self.now + 1)
        epoch = self.coordinator.revoke_locally(self.a.device_id, self.now + 2)
        with self.assertRaises(PermissionError):
            permission.check_current(self.now + 3)
        self.assertFalse(self.coordinator.revocation_status(self.a.device_id)["peerAcknowledged"])
        self.coordinator.acknowledge_revocation(self.peer_a, epoch, self.now + 4)
        self.assertTrue(self.coordinator.revocation_status(self.a.device_id)["peerAcknowledged"])

    def test_scope_epoch_instance_budget_and_tool_are_live_fences(self):
        self.pair()
        for changes in ({"authorizationEpoch": 2}, {"instanceId": "reinstalled"}, {"resourceBinding": {"screenMode": "background", "packageNames": ["test.pocketworld"]}}, {"budget": {"allocationId": "allocation-1", "toolCalls": 5, "modelCalls": 0}}, {"arguments": {"toolName": "phone.send", "effect": "write", "arguments": {}}}):
            with self.subTest(changes):
                with self.assertRaises(PermissionError):
                    self.coordinator.check_permission(self.peer_a, self.envelope(**changes), self.now)
        permission = self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        changed = self.grant(2)
        self.trust.replace_grant(changed, self.now)
        with self.assertRaises(PermissionError):
            permission.check_current(self.now)

    def test_multi_hour_goal_is_not_limited_by_command_worker_timeout(self):
        self.pair()
        goal = self.envelope(messageType="GoalSubmission", method="task.submitGoal", deadline=self.now + 8 * 3600000, arguments={"goal": "Run the selected local project", "executionMode": "agent", "modelSource": {"kind": "local"}}, budget={"allocationId": "allocation-goal-1", "toolCalls": 4, "modelCalls": 2})
        permission = self.coordinator.check_permission(self.peer_a, goal, self.now)
        permission.check_current(self.now + 1)

    def test_reciprocal_persistence_and_signed_native_outgoing_route(self):
        invitation, transcript, local_b, local_a, receipt = self.pair(history=True)
        db_a = sqlite3.connect(":memory:")
        try:
            trust_a = p.DeviceTrust(db_a, threading.RLock())
            trust_a.create_schema()
            coordinator_a = p.PairingCoordinator(trust_a, self.a.device_id, self.a.instance_id)
            reciprocal = self.grant()
            reciprocal.update(grantId="grant-a", peerDeviceId=self.b.device_id, localDeviceId=self.a.device_id, allowedOrigins=[self.b.device_id])
            coordinator_a.accept_pairing_receipt(invitation, self.peer_b, local_a, receipt, reciprocal, self.now)
            discovery_query=self.coordinator.discovery_query(self.b,self.peer_a,self.now)
            self.assertIsNone(discovery_query.envelope.get("grantId"))
            discovered=coordinator_a.check_discovery_permission(self.peer_b,discovery_query.envelope,self.now)
            self.assertIsInstance(discovered,p.DiscoveryPermission);self.assertNotIsInstance(discovered,p.Permission)
            receipts=coordinator_a.discovery_grant_receipts(self.a,discovered,self.now)
            self.assertEqual(1,len(receipts));self.assertEqual(self.b.device_id,receipts[0]["body"]["grant"]["peerDeviceId"])
            reverse=self.coordinator.accept_outgoing_grant(self.peer_a,receipts[0],self.now)
            reverse.require_target_peer(self.peer_a,self.now)
            with self.assertRaises(PermissionError):coordinator_a.check_permission(self.peer_b,discovery_query.envelope,self.now)

            self.assertEqual(self.b.certificate_fingerprint, trust_a.peer(self.b.device_id)["fingerprint"])
            signed = self.coordinator.issue_grant_receipt(self.b, "grant-1", self.now)
            route = coordinator_a.accept_outgoing_grant(self.peer_b, signed, self.now)
            route.require_target_peer(self.peer_b, self.now)
            with self.assertRaises(PermissionError):
                route.require_target_peer(self.peer_a, self.now)
            self.assertEqual(self.b.device_id, route.target_device_id)
            self.assertEqual(self.b.instance_id, route.target_instance_id)
            route.check_current(self.now)
            refreshed = self.coordinator.issue_grant_receipt(self.b, "grant-1", self.now + 1)
            coordinator_a.accept_outgoing_grant(self.peer_b, refreshed, self.now + 1)
            route.check_current(self.now + 1)  # Same native grant, new signature/time is not revocation.
            tampered = copy.deepcopy(signed)
            tampered["body"]["grant"]["toolBudget"] = 400
            with self.assertRaises(Exception):
                coordinator_a.accept_outgoing_grant(self.peer_b, tampered, self.now)
            late=self.now+12*3600000+1
            with self.assertRaises(PermissionError):route.check_current(late)
            readonly=coordinator_a.history_route(self.peer_b,"grant-1",late)
            self.assertNotIsInstance(readonly,p.OutgoingGrant)
            query=readonly.history_query(self.a,"operation.get","operation-1",self.binding,late)
            self.assertEqual(self.a.device_id,query.envelope.get("originDeviceId"))
            self.assertEqual(self.a.device_id,query.envelope.get("callerDeviceId"))
            self.assertIsNone(query.envelope.get("budget"))
            query.require_target_peer(self.peer_b,late)
            with self.assertRaises(PermissionError):query.require_target_peer(self.peer_a,late)
            with self.assertRaises(PermissionError):readonly.history_query(self.a,"tool.invoke","operation-1",self.binding,late)
            coordinator_a.revoke_locally(self.b.device_id, self.now + 1)
            with self.assertRaises(PermissionError):
                route.check_current(self.now + 2)
        finally:
            db_a.close()

    def test_persisted_phone_task_grant_restores_only_original_sql_and_frozen_methods(self):
        from cryptography.exceptions import InvalidSignature
        old_profile = "pwp1_953641fabc7e4316996f69c2c3ac9b74db1a30f3750ee83adbfd95768730ae8b"
        original_methods = {"device.describe", "tool.invoke", "operation.get", "operation.cancel", "task.submitGoal",
                            "task.get", "task.events", "task.respond", "task.pause", "task.cancel", "task.acquireControl",
                            "artifact.describe", "artifact.readChunk", "task.answerQuestion", "task.steer", "task.requestResume"}
        previous_methods = original_methods - {"task.answerQuestion", "task.steer", "task.requestResume"}
        self.assertEqual(old_profile, p.PERSISTED_PHONE_TASK_GRANT_PROFILE)
        self.assertEqual(original_methods, p.PERSISTED_PHONE_TASK_GRANT_METHODS)
        invitation, _, _, local_a, receipt = self.pair(history=True)
        database = Path(self.scratch.name) / "fixture-only-retained-caller.sqlite3"
        connection = sqlite3.connect(database)
        try:
            trust = p.DeviceTrust(connection, threading.RLock())
            trust.create_schema()
            caller = p.PairingCoordinator(trust, self.a.device_id, self.a.instance_id)
            reciprocal = self.reciprocal_grant()
            caller.accept_pairing_receipt(invitation, self.peer_b, local_a, receipt, reciprocal, self.now)
            grant = self.grant()
            grant["capabilities"] = sorted(original_methods)
            grant["historyRead"] = {"expiresAt": self.now + 13 * 3600000, "methods": ["operation.get"]}
            received_at = self.now - 500

            def signed(value, profile=old_profile, signer=None, instance=None):
                signer = self.b if signer is None else signer
                record = {"protocolVersion": wire.VERSION, "protocolProfile": profile, "messageType": "PairGrantReceipt",
                          "issuerDeviceId": signer.device_id, "issuerCertificateFingerprint": signer.certificate_fingerprint,
                          "body": {"grant": copy.deepcopy(value), "targetInstanceId": self.b.instance_id if instance is None else instance,
                                   "issuedAt": self.now}}
                record["signature"] = base64.b64encode(signer.sign(wire.canonical_bytes(record))).decode()
                return record

            original = signed(grant)
            trust.put_outgoing_grant(self.b.device_id, "grant-1", original, received_at)
            connection.close()
            connection = sqlite3.connect(database)
            trust = p.DeviceTrust(connection, threading.RLock())
            caller = p.PairingCoordinator(trust, self.a.device_id, self.a.instance_id)
            route = caller.restore_outgoing_grant(self.peer_b, "grant-1", self.now)
            route.require_target_peer(self.peer_b, self.now)
            self.assertEqual(grant, route.grant)
            self.assertEqual(original_methods, set(route.grant["capabilities"]))
            stop = route.operation_cancel(self.a, "original-operation", "a" * 64, "original-stop", self.binding, self.now)
            stop.require_target_peer(self.peer_b, self.now)
            self.assertEqual(wire.PROFILE, stop.envelope.get("protocolProfile"))
            self.assertNotIn("budget", stop.envelope.json())
            late = grant["expiresAt"] + 1
            history = caller.history_route(self.peer_b, "grant-1", late)
            query = history.history_query(self.a, "operation.get", "original-operation", self.binding, late)
            query.require_target_peer(self.peer_b, late)
            self.assertEqual(wire.PROFILE, query.envelope.get("protocolProfile"))
            self.assertNotIn("budget", query.envelope.json())
            with self.assertRaises(PermissionError):
                caller.restore_outgoing_grant(self.peer_b, "grant-1", late)
            with self.assertRaises(PermissionError):
                history.history_query(self.a, "operation.get", "original-operation", self.binding, grant["historyRead"]["expiresAt"] + 1)
            self.assertEqual((wire.canonical_bytes(original).decode(), received_at), connection.execute(
                "SELECT receipt_json,received_at FROM device_outgoing_grants WHERE target_device_id=? AND grant_id=?",
                (self.b.device_id, "grant-1")).fetchone())
            for accept in (caller.accept_outgoing_grant, caller.accept_history_grant_receipt):
                with self.assertRaises(wire.ProtocolError) as error:
                    accept(self.peer_b, original, self.now)
                self.assertEqual("incompatible_profile", error.exception.code)
            old_wire = query.envelope.json()
            old_wire["protocolProfile"] = old_profile
            with self.assertRaises(wire.ProtocolError) as error:
                wire.parse_request(wire.canonical_bytes(old_wire))
            self.assertEqual("incompatible_profile", error.exception.code)
            with self.assertRaises(PermissionError):
                caller.restore_outgoing_grant(self.peer_a, "grant-1", self.now)
            with self.assertRaises(PermissionError):
                route.require_target_peer(self.peer_a, self.now)
            tampered = copy.deepcopy(original)
            tampered["body"]["grant"]["toolBudget"] += 1
            invalid = [(InvalidSignature, tampered), (PermissionError, signed(grant, signer=self.a)),
                       (PermissionError, signed(grant, instance="wrong-instance")),
                       (PermissionError, signed(dict(grant, authorizationEpoch=2))),
                       (PermissionError, signed(dict(grant, revoked=True))),
                       (PermissionError, signed(dict(grant, capabilities=sorted(original_methods | {"future.ownerControl"}))))]
            for expected, row in invalid:
                with self.subTest(invalid=row["body"]["grant"]):
                    trust.put_outgoing_grant(self.b.device_id, "grant-1", row, received_at)
                    with self.assertRaises(expected):
                        caller.restore_outgoing_grant(self.peer_b, "grant-1", self.now)
            # The older pre-phone-input profile keeps exactly its original 13 methods.
            self.assertEqual(previous_methods, p.PERSISTED_GRANT_METHODS)
            earlier = dict(grant, capabilities=sorted(previous_methods))
            trust.put_outgoing_grant(self.b.device_id, "grant-1", signed(earlier, p.PERSISTED_GRANT_PROFILE), received_at)
            self.assertEqual(previous_methods, set(caller.restore_outgoing_grant(self.peer_b, "grant-1", self.now).grant["capabilities"]))
            for added in ("task.answerQuestion", "task.steer", "task.requestResume"):
                trust.put_outgoing_grant(self.b.device_id, "grant-1", signed(dict(earlier, capabilities=sorted(previous_methods | {added})), p.PERSISTED_GRANT_PROFILE), received_at)
                with self.assertRaises(PermissionError):
                    caller.restore_outgoing_grant(self.peer_b, "grant-1", self.now)
            trust.put_outgoing_grant(self.b.device_id, "grant-1", original, received_at)
            connection.execute("UPDATE device_peers SET authorization_epoch=2 WHERE device_id=?", (self.b.device_id,))
            for action in (lambda: caller.restore_outgoing_grant(self.peer_b, "grant-1", self.now),
                           lambda: caller.history_route(self.peer_b, "grant-1", self.now),
                           lambda: route.check_current(self.now),
                           lambda: query.require_target_peer(self.peer_b, late)):
                with self.assertRaises(PermissionError):
                    action()
            connection.execute("UPDATE device_peers SET authorization_epoch=1 WHERE device_id=?", (self.b.device_id,))
            connection.execute("DELETE FROM device_outgoing_grants WHERE target_device_id=? AND grant_id=?", (self.b.device_id, "grant-1"))
            connection.commit()
            for restore in (caller.restore_outgoing_grant, caller.history_route):
                with self.assertRaises(PermissionError):
                    restore(self.peer_b, "grant-1", self.now)
            with self.assertRaises(wire.ProtocolError):
                caller.accept_outgoing_grant(self.peer_b, original, self.now)
            self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM device_outgoing_grants").fetchone()[0])
        finally:
            connection.close()

    def test_two_pairings_do_not_implicitly_grant_delegation(self):
        self.pair()
        c = i.provision_identity(Path(self.scratch.name) / "test-only-c")
        statement = dict(protocolVersion="1.0", protocolProfile=wire.PROFILE, messageType="DelegationProof", issuerDeviceId=c.device_id, delegateDeviceId=self.a.device_id, targetResources=[{"targetDeviceId":self.b.device_id,"resourceBinding":self.binding}], rootTaskId="root-1", parentTaskId="parent-1", grantId="origin-grant-1", authorizationEpoch=1, capabilities=["tool.invoke"], toolNames=["phone.tap"], toolBudget=2, modelBudget=0, deadline=self.now + 200000, remainingDepth=0, previousProofDigest="")
        statement.update(sourceAllocationId="source-test-1", targetShares=[delegation_policy.target_share(c.device_id, "source-test-1", self.b.device_id, self.binding, 2, 0)])
        delegated = self.envelope(originDeviceId=c.device_id, visitedDevices=[c.device_id, self.a.device_id], delegationChain=[delegation_policy.sign(c, statement)])
        with self.assertRaises(PermissionError):
            self.coordinator.check_permission(self.peer_a, delegated, self.now)

    def test_old_key_rotation_requires_new_possession_and_never_inherits_grants(self):
        self.pair()
        old_permission = self.coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        next_identity = i.provision_identity(Path(self.scratch.name) / "test-only-rotated")
        next_peer, _ = tls_peers(next_identity, self.b)
        proof = p.issue_rotation_proof(self.a, next_identity.certificate_der, next_identity.instance_id, self.now, self.now + 60000)
        with self.assertRaises(PermissionError):
            self.coordinator.rotate_peer(self.a.device_id, next_peer, next_identity.instance_id, None, None, self.now)
        receipt = self.coordinator.rotate_peer(self.a.device_id, next_peer, next_identity.instance_id, proof, None, self.now)
        self.assertEqual("old_key", receipt["confirmation"])
        self.assertTrue(receipt["capabilitiesRegrantRequired"])
        with self.assertRaises(PermissionError):
            old_permission.check_current(self.now)
        self.assertTrue(self.trust.grant("grant-1")["revoked"])
        self.assertEqual(next_identity.certificate_fingerprint, self.trust.peer(next_identity.device_id)["fingerprint"])
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM device_identity_rotations").fetchone()[0])

    def test_lost_old_key_requires_new_native_owner_confirmation(self):
        self.pair()
        next_identity = i.provision_identity(Path(self.scratch.name) / "test-only-owner-reconfirmed")
        next_peer, _ = tls_peers(next_identity, self.b)
        self.coordinator.revoke_locally(self.a.device_id, self.now)
        with self.assertRaises(PermissionError):
            self.coordinator.confirm_rotation_locally(self.a.device_id, next_peer, next_identity.instance_id, False)
        confirmation = self.coordinator.confirm_rotation_locally(self.a.device_id, next_peer, next_identity.instance_id, True, now_ms=self.now)
        receipt = self.coordinator.rotate_peer(self.a.device_id, next_peer, next_identity.instance_id, None, confirmation, self.now)
        self.assertEqual("local_owner", receipt["confirmation"])

    def test_rotation_confirmation_fences_original_epoch_ttl_and_single_consumption(self):
        self.pair()
        replacement = i.provision_identity(Path(self.scratch.name) / "rotation-regression-only")
        next_peer, _ = tls_peers(replacement, self.b)
        old = self.coordinator.confirm_rotation_locally(self.a.device_id, next_peer, replacement.instance_id, True, self.now, 1000)
        self.coordinator.revoke_locally(self.a.device_id, self.now + 1)
        with self.assertRaises(PermissionError):
            self.coordinator.rotate_peer(self.a.device_id, next_peer, replacement.instance_id, None, old, self.now + 2)
        self.assertIsNone(self.trust.peer(replacement.device_id))
        self.assertTrue(self.trust.grant("grant-1")["revoked"])
        fresh = self.coordinator.confirm_rotation_locally(self.a.device_id, next_peer, replacement.instance_id, True, self.now + 2, 1000)
        with self.assertRaises(PermissionError):
            self.coordinator.rotate_peer(self.a.device_id, next_peer, replacement.instance_id, None, fresh, self.now + 1002)
        receipt = self.coordinator.rotate_peer(self.a.device_id, next_peer, replacement.instance_id, None, fresh, self.now + 3)
        self.assertEqual(fresh.confirmation_id, receipt["rotationId"])
        with self.assertRaises(PermissionError):
            self.coordinator.rotate_peer(self.a.device_id, next_peer, replacement.instance_id, None, fresh, self.now + 4)
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM device_identity_rotations").fetchone()[0])

    def test_rotation_confirmation_has_one_winner_across_two_original_sqlite_connections(self):
        self.pair()
        replacement=i.provision_identity(Path(self.scratch.name)/"concurrent-rotation-test-only")
        replacement_peer,_=tls_peers(replacement,self.b)
        confirmation=self.coordinator.confirm_rotation_locally(self.a.device_id,replacement_peer,replacement.instance_id,True,self.now)
        path=Path(self.scratch.name)/"same-original-trust.sqlite3"
        with sqlite3.connect(path) as copied:self.db.backup(copied)
        connections=[sqlite3.connect(path,check_same_thread=False) for _ in range(2)]
        barrier=threading.Barrier(2);results=[];result_lock=threading.Lock()
        def rotate(connection):
            owner=p.PairingCoordinator(p.DeviceTrust(connection,threading.RLock()),self.b.device_id,self.b.instance_id)
            barrier.wait()
            try:owner.rotate_peer(self.a.device_id,replacement_peer,replacement.instance_id,None,confirmation,self.now+1);result="committed"
            except PermissionError:result="rejected"
            with result_lock:results.append(result)
        workers=[threading.Thread(target=rotate,args=(connection,)) for connection in connections]
        try:
            for worker in workers:worker.start()
            for worker in workers:worker.join(10);self.assertFalse(worker.is_alive())
            self.assertCountEqual(["committed","rejected"],results)
            self.assertEqual(1,connections[0].execute("SELECT COUNT(*) FROM device_identity_rotations").fetchone()[0])
            self.assertEqual(1,connections[0].execute("SELECT revoked FROM device_grants WHERE grant_id='grant-1'").fetchone()[0])
        finally:
            for connection in connections:connection.close()

    def test_same_p256_key_certificate_renewal_replaces_only_exact_pin_not_old_grants(self):
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization,hashes
        from cryptography.x509.oid import NameOID
        import datetime,uuid
        self.pair();old_permission=self.coordinator.check_permission(self.peer_a,self.envelope(),self.now)
        key=serialization.load_pem_private_key(self.a._key(),password=None)
        subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"PocketWorld test-only renewed certificate")])
        current=datetime.datetime.fromtimestamp(self.now/1000,datetime.timezone.utc)
        cert=x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(current-datetime.timedelta(minutes=1)).not_valid_after(current+datetime.timedelta(days=30)).sign(key,hashes.SHA256())
        directory=i._private_dir(Path(self.scratch.name)/"same-key-renewal-test-only")
        (directory/"certificate.der").write_bytes(cert.public_bytes(serialization.Encoding.DER))
        (directory/"instance-id").write_text(self.a.instance_id,encoding="ascii")
        raw=self.a._key();name="private-key.dpapi" if os.name=="nt" else "private-key.pem"
        descriptor=os.open(directory/name,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(descriptor,"wb") as stream:stream.write(i._dpapi(raw,False) if os.name=="nt" else raw)
        renewed=i.load_existing_identity(directory);actual,_=tls_peers(renewed,self.b)
        self.assertEqual(self.a.device_id,actual.device_id);self.assertNotEqual(self.a.certificate_fingerprint,actual.certificate_fingerprint)
        confirmation=self.coordinator.confirm_rotation_locally(self.a.device_id,actual,renewed.instance_id,True,self.now)
        self.coordinator.rotate_peer(self.a.device_id,actual,renewed.instance_id,None,confirmation,self.now+1)
        self.assertEqual(renewed.certificate_fingerprint,self.trust.peer(self.a.device_id)["fingerprint"])
        self.assertFalse(self.trust.peer(self.a.device_id)["revoked"]);self.assertTrue(self.trust.grant("grant-1")["revoked"])
        with self.assertRaises(PermissionError):old_permission.check_current(self.now+2)
        with self.assertRaises(PermissionError):self.coordinator.check_permission(actual,self.envelope(authorizationEpoch=2),self.now+2)

    def test_sqlite_reopen_retains_pairing_and_revocation_fences(self):
        self.pair()
        self.coordinator.revoke_locally(self.a.device_id, self.now)
        path = Path(self.scratch.name) / "test-only-ledger.sqlite3"
        with sqlite3.connect(path) as exported:
            self.db.backup(exported)
        reopened = sqlite3.connect(path)
        try:
            trust = p.DeviceTrust(reopened, threading.RLock())
            coordinator = p.PairingCoordinator(trust, self.b.device_id, self.b.instance_id)
            self.assertTrue(trust.peer(self.a.device_id)["revoked"])
            self.assertFalse(trust.revocation_status(self.a.device_id)["peerAcknowledged"])
            with self.assertRaises(PermissionError):
                coordinator.check_permission(self.peer_a, self.envelope(), self.now)
        finally:
            reopened.close()

    def test_signed_delegation_is_independently_verified_and_cannot_expand(self):
        self.pair()
        c = i.provision_identity(Path(self.scratch.name) / "test-only-c")
        grant = self.grant(2)
        grant.update(allowedOrigins=[c.device_id], allowDelegation=True, maxDelegationDepth=2)
        self.trust.replace_grant(grant, self.now)
        statement = dict(protocolVersion="1.0", protocolProfile=wire.PROFILE, messageType="DelegationProof", issuerDeviceId=c.device_id, delegateDeviceId=self.a.device_id, targetResources=[{"targetDeviceId":self.b.device_id,"resourceBinding":self.binding}], rootTaskId="root-1", parentTaskId="parent-1", grantId="origin-grant-1", authorizationEpoch=1, capabilities=["tool.invoke"], toolNames=["phone.tap"], toolBudget=2, modelBudget=0, deadline=self.now + 200000, remainingDepth=0, previousProofDigest="")
        statement.update(sourceAllocationId="source-test-1", targetShares=[delegation_policy.target_share(c.device_id, "source-test-1", self.b.device_id, self.binding, 2, 0)])
        proof = delegation_policy.sign(c, statement)
        envelope = self.envelope(authorizationEpoch=2, originDeviceId=c.device_id, visitedDevices=[c.device_id, self.a.device_id], delegationChain=[proof])
        self.coordinator.check_permission(self.peer_a, envelope, self.now)
        proof["toolBudget"] = 3
        tampered = self.envelope(authorizationEpoch=2, originDeviceId=c.device_id, visitedDevices=[c.device_id, self.a.device_id], delegationChain=[proof])
        with self.assertRaises(Exception):
            self.coordinator.check_permission(self.peer_a, tampered, self.now)

    def test_forwarding_exact_target_resource_pairs_do_not_form_a_cartesian_product(self):
        self.pair()
        c=i.provision_identity(Path(self.scratch.name)/"target-pair-c-only")
        d=i.provision_identity(Path(self.scratch.name)/"target-pair-d-only")
        local_binding={"screenMode":"capabilities"}
        c_file={"kind":"workspace","environmentId":"project-C","binding":"c"*64}
        d_desktop={"kind":"desktop","sessionId":1,"displayId":"primary"}
        allowed=[{"targetDeviceId":c.device_id,"resourceBinding":c_file},{"targetDeviceId":d.device_id,"resourceBinding":d_desktop}]
        grant=self.grant(2);grant["resources"]=[local_binding]
        grant["forwardingScope"]={"targetResources":allowed,"capabilities":["tool.invoke"],"toolNames":["workspace.read","desktop.act"],"toolBudget":2,"modelBudget":0,"maxDelegationDepth":2}
        self.trust.replace_grant(grant,self.now)
        payload={"goal":"Use only B owner's exact downstream target/resource pair","executionMode":"agent"}
        budget={"allocationId":"source-pair-allocation","toolCalls":4,"modelCalls":2}
        draft=self.envelope(method="task.submitGoal",messageType="GoalSubmission",authorizationEpoch=2,resourceBinding=local_binding,arguments=payload,budget=budget)
        def goal_for(target_binding,tool):
            statement=dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="DelegationProof",issuerDeviceId=self.a.device_id,delegateDeviceId=self.b.device_id,targetResources=[{"targetDeviceId":c.device_id,"resourceBinding":target_binding}],rootTaskId=draft.get("rootTaskId"),parentTaskId=wire.receiver_task_id(draft),grantId="grant-1",authorizationEpoch=2,capabilities=["tool.invoke"],toolNames=[tool],toolBudget=4,modelBudget=2,deadline=draft.get("deadline"),remainingDepth=0,previousProofDigest="",sourceAllocationId=budget["allocationId"],targetShares=[delegation_policy.target_share(self.a.device_id,budget["allocationId"],self.b.device_id,local_binding,2,2),delegation_policy.target_share(self.a.device_id,budget["allocationId"],c.device_id,target_binding,2,0)])
            proof=delegation_policy.sign(self.a,statement)
            return self.envelope(method="task.submitGoal",messageType="GoalSubmission",authorizationEpoch=2,resourceBinding=local_binding,arguments=dict(payload,delegationAuthorization=proof),budget=budget)
        self.coordinator.check_permission(self.peer_a,goal_for(c_file,"workspace.read"),self.now)
        # C/file and D/desktop explicitly do NOT authorize C/desktop, even with a genuine A signature.
        with self.assertRaises(PermissionError):self.coordinator.check_permission(self.peer_a,goal_for(d_desktop,"desktop.act"),self.now)
        self.assertFalse(delegation_policy.contains_target_resource(allowed,c.device_id,d_desktop))
        self.assertTrue(delegation_policy.contains_target_resource(allowed,d.device_id,d_desktop))

    def test_initial_goal_proof_persists_exact_b_parent_and_c_independently_authorizes(self):
        self.pair()
        c = i.provision_identity(Path(self.scratch.name) / "test-only-final-c")
        c_binding = {"kind": "desktop", "sessionId": 1, "displayId": "primary"}
        forward_scope = dict(targetResources=[{"targetDeviceId":c.device_id,"resourceBinding":c_binding}], capabilities=["tool.invoke"], toolNames=["desktop.act"], toolBudget=2, modelBudget=0, maxDelegationDepth=2)
        grant_b = self.grant(2)
        grant_b["forwardingScope"] = forward_scope
        self.trust.replace_grant(grant_b, self.now)
        payload = {"goal": "Use only the authorized C test desktop", "executionMode": "agent", "modelSource": {"kind": "local"}}
        goal = self.envelope(method="task.submitGoal", messageType="GoalSubmission", authorizationEpoch=2, arguments=payload, budget={"allocationId": "allocation-goal-1", "toolCalls": 4, "modelCalls": 2})
        b_task = wire.receiver_task_id(goal)
        statement = dict(protocolVersion="1.0", protocolProfile=wire.PROFILE, messageType="DelegationProof", issuerDeviceId=self.a.device_id, delegateDeviceId=self.b.device_id, targetResources=[{"targetDeviceId":c.device_id,"resourceBinding":c_binding}], rootTaskId=goal.get("rootTaskId"), parentTaskId=b_task, grantId="grant-1", authorizationEpoch=2, capabilities=["tool.invoke"], toolNames=["desktop.act"], toolBudget=4, modelBudget=2, deadline=goal.get("deadline"), remainingDepth=0, previousProofDigest="")
        statement.update(sourceAllocationId=goal.get("budget")["allocationId"], targetShares=[delegation_policy.target_share(self.a.device_id, goal.get("budget")["allocationId"], self.b.device_id, self.binding, 2, 2), delegation_policy.target_share(self.a.device_id, goal.get("budget")["allocationId"], c.device_id, c_binding, 2, 0)])
        proof = delegation_policy.sign(self.a, statement)
        goal = self.envelope(method="task.submitGoal", messageType="GoalSubmission", authorizationEpoch=2, arguments=dict(payload, delegationAuthorization=proof), budget={"allocationId": "allocation-goal-1", "toolCalls": 4, "modelCalls": 2})
        self.assertEqual(b_task, wire.receiver_task_id(goal))
        admitted = self.coordinator.check_permission(self.peer_a, goal, self.now)
        self.assertEqual(2, admitted.source_share.tool_budget)
        self.assertEqual(2, admitted.source_share.model_budget)
        self.assertEqual(goal.get("budget")["allocationId"], admitted.source_share.source_allocation_id)
        for changes in ({"parentTaskId": "other-parent"}, {"toolBudget": 5}, {"deadline": goal.get("deadline") + 1}):
            with self.subTest(changes):
                changed = delegation_policy.sign(self.a, dict(statement, **changes))
                invalid = self.envelope(method="task.submitGoal", messageType="GoalSubmission", authorizationEpoch=2, arguments=dict(payload, delegationAuthorization=changed), budget={"allocationId": "allocation-goal-1", "toolCalls": 4, "modelCalls": 2})
                with self.assertRaises(PermissionError):
                    self.coordinator.check_permission(self.peer_a, invalid, self.now)
        unreserved = copy.deepcopy(statement)
        unreserved["sourceAllocationId"] = "source-not-native-held"
        unreserved["targetShares"] = [delegation_policy.target_share(self.a.device_id, unreserved["sourceAllocationId"], share["targetDeviceId"], share["resourceBinding"], share["toolBudget"], share["modelBudget"]) for share in statement["targetShares"]]
        invalid_source_goal = self.envelope(method="task.submitGoal", messageType="GoalSubmission", authorizationEpoch=2, arguments=dict(payload, delegationAuthorization=delegation_policy.sign(self.a, unreserved)), budget=goal.get("budget"))
        with self.assertRaises(PermissionError):
            self.coordinator.check_permission(self.peer_a, invalid_source_goal, self.now)
        peer_b_on_c, peer_c_on_b = tls_peers(self.b, c)
        c_db = sqlite3.connect(":memory:")
        try:
            c_store = p.DeviceTrust(c_db, threading.RLock())
            c_store.create_schema()
            c_owner = p.PairingCoordinator(c_store, c.device_id, c.instance_id)
            c_grant = self.grant()
            c_grant.update(peerDeviceId=self.b.device_id, localDeviceId=c.device_id, allowedOrigins=[self.a.device_id], allowDelegation=True, maxDelegationDepth=2, toolNames=["desktop.act"], resources=[c_binding], expiresAt=self.now+50000, historyRead={"expiresAt":self.now+200000,"methods":["operation.get"]})
            invitation = c_owner.create_invitation(c, self.b.certificate_fingerprint, self.now)
            transcript = p.pairing_transcript(invitation, peer_c_on_b, self.b.device_id, self.b.instance_id, self.b.certificate_fingerprint, self.now)
            c_owner.complete_pairing(invitation, peer_b_on_c, p.confirm_locally(c, transcript, True), p.confirm_locally(self.b, transcript, True).wire(), c_grant, self.now)
            request = self.envelope(callerDeviceId=self.b.device_id, targetDeviceId=c.device_id, originDeviceId=self.a.device_id, parentTaskId=b_task, instanceId=c.instance_id, resourceBinding=c_binding, capabilityVersion="desktop-v2", visitedDevices=[self.a.device_id, self.b.device_id], maxDelegationDepth=2, delegationChain=[proof], arguments={"toolName": "desktop.act", "effect": "write", "arguments": {}}, budget={"allocationId": wire.allocation_id(b_task, c.device_id, c_binding, "tool_session"), "toolCalls": 2, "modelCalls": 0})
            permission = c_owner.check_permission(peer_b_on_c, request, self.now)
            self.assertEqual(2, permission.source_share.tool_budget)
            self.assertEqual(0, permission.source_share.model_budget)
            self.assertEqual(c.device_id, permission.source_share.target_device_id)
            over_share = request.json()
            over_share["budget"]["toolCalls"] = 3
            over_share["argumentsDigest"] = wire.arguments_digest(over_share)
            with self.assertRaises(PermissionError):
                c_owner.check_permission(peer_b_on_c, wire.parse_request(wire.canonical_bytes(over_share)), self.now)
            resigned = delegation_policy.sign(self.a, statement)
            self.assertEqual([share["shareId"] for share in proof["targetShares"]], [share["shareId"] for share in resigned["targetShares"]])
            record = permission.source_share.record
            record["toolBudget"] = 400
            self.assertEqual(2, permission.source_share.tool_budget)
            share=permission.source_share
            audience=dict(kind="operation",objectId=request.get("operationId"),originDeviceId=self.a.device_id,callerDeviceId=self.b.device_id,targetDeviceId=c.device_id,instanceId=c.instance_id,rootTaskId=request.get("rootTaskId"),localTaskId=request.get("localTaskId"),runId=request.get("runId"),executorEpoch=1,grantId="grant-1",authorizationEpoch=1,resourceBinding=c_binding,acceptedAt=self.now,retainedUntil=self.now+200000,sourceShareId=share.share_id,sourceIssuerDeviceId=share.issuer_device_id,sourceGrantId=share.source_grant_id,sourceAuthorizationEpoch=share.source_authorization_epoch,sourceProofFingerprint=share.source_proof_fingerprint)
            class AcceptedSource(p.NativeHistoryAudienceSource):
                visible=True
                def accepted_audience(native,query):return copy.deepcopy(audience)
                def source_visible(native,audience,now_ms):return native.visible
            source=AcceptedSource()
            late=self.now+100001  # both A execution proof and C execution grant have expired
            direct=self.read_query(originDeviceId=self.b.device_id,callerDeviceId=self.b.device_id,targetDeviceId=c.device_id,instanceId=c.instance_id,resourceBinding=c_binding,deadline=late+10000,arguments={"operationId":request.get("operationId")})
            read=c_owner.check_history_permission(peer_b_on_c,direct,source,late)
            self.assertEqual(self.b.device_id,read.origin_device_id)
            self.assertEqual(self.a.device_id,read.original_origin_device_id)
            self.assertNotIsInstance(read,p.Permission)
            source.visible=False
            with self.assertRaises(PermissionError):read.check_current(late)
            c_store.revoke(self.b.device_id, self.now)
            with self.assertRaises(PermissionError):
                c_owner.check_permission(peer_b_on_c, request, self.now)
        finally:
            c_db.close()


if __name__ == "__main__":
    unittest.main()
