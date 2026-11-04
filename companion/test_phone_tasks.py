"""Controlled local protocol/HTTP/ADB fixtures; no real phone, model or ADB use."""
import asyncio
import copy
import http.client
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
import uuid
from unittest.mock import patch

import device_pairing as pairing
import device_protocol as wire
from phone_task_bridge import PhoneTaskError, PhoneTaskRouter
from phone_task_client import Client
from phone_task_desk import TaskDesk
from phone_task_mcp import rpc, stdio, VERSION
from phone_task_usb import UsbForwardError
import test_device_pairing as pairing_fixtures


class PhoneDispatchTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fixture = pairing_fixtures.DevicePairingTests()
        self.fixture.setUp()
        f = self.fixture
        invitation, transcript, _, remote, receipt = f.pair()
        self.db = sqlite3.connect(":memory:", check_same_thread=False)
        self.db.execute("CREATE TABLE operations(operation_id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,job_id TEXT NOT NULL,last_status TEXT NOT NULL,updated_at INTEGER NOT NULL,details TEXT)")
        self.manager = SimpleNamespace(ledger=self.db, lock=threading.RLock(), now=lambda: int(time.time() * 1000))
        trust = pairing.DeviceTrust(self.db, self.manager.lock); trust.create_schema()
        owner = pairing.PairingCoordinator(trust, f.a.device_id, f.a.instance_id)
        reverse = copy.deepcopy(f.grant())
        reverse.update(grantId="reverse-grant", peerDeviceId=f.b.device_id, localDeviceId=f.a.device_id, allowedOrigins=[f.b.device_id])
        owner.accept_pairing_receipt(invitation, f.peer_b, remote, receipt, reverse, f.now)
        # This fixture explicitly adds read/cancel capabilities on the receiving
        # owner grant, through its native persisted grant record before signing.
        original = f.trust.grant("grant-1")
        original["capabilities"] += ["task.get", "task.events", "operation.cancel", "task.acquireControl", "task.answerQuestion", "task.steer", "task.requestResume"]
        f.db.execute("UPDATE device_grants SET body_json=? WHERE grant_id='grant-1'", (json.dumps(original),)); f.db.commit()
        signed = f.coordinator.issue_grant_receipt(f.b, "grant-1", f.now)
        owner.accept_outgoing_grant(f.peer_b, signed, f.now)
        def paired(peer):
            current = trust.peer(peer)
            if current is None or current["revoked"]:
                raise PermissionError("revoked")
            return current
        self.service = SimpleNamespace(manager=self.manager, identity=f.a, receiver=SimpleNamespace(pairing=owner, trust=trust),
                                       loop=asyncio.get_running_loop(), closed=False, _paired=paired)
        self.router = PhoneTaskRouter(self.service)
        self.sent = []
        self.accepted_times = {}
        self.response_hook = None
        self.mode = "accepted"
        self.waiting_question = False
        self.question_id = "0cb39508-9c87-4cd5-bcf0-b8cf2c97f016"
        self.connection = SimpleNamespace(lane="control", closed=False, state="online")
        # A normal object is used as the native connection identity (hash key).
        class Link:
            lane, closed, state = "control", False, "online"
        self.connection = Link()
        self.connection.send_control = self.send
        self.router.connection_opened(self.connection, f.peer_b)
        self.params = dict(operationId="original-phone-op", peerDeviceId=f.b.device_id, grantId="grant-1", resourceBinding=f.binding,
                           goal="fixture only; never call a model", toolCalls=2, modelCalls=1, ttlMs=30000, capabilityVersion="1.0")

    async def asyncTearDown(self):
        self.db.close(); self.fixture.tearDown()

    def send(self, raw, *, before_write=None):
        if before_write is not None:
            before_write(self.fixture.peer_b)
        request = wire.parse_request(raw)
        self.sent.append(request)
        if self.mode == "rejected":
            self.router.receive(self.connection, self.fixture.peer_b, wire.canonical_bytes(wire.ProtocolError("grant_denied", "Native owner refused").response(request.get("requestId"))))
            return True
        if self.mode == "disconnect" or self.mode == "disconnect-write" and request.method in {"task.acquireControl", "task.answerQuestion", "task.steer", "task.requestResume"}:
            self.router.connection_closed(self.connection)
            return True
        f = self.fixture
        now = self.manager.now()
        if request.method == "device.describe":
            signed = f.coordinator.issue_grant_receipt(f.b,"grant-1",now)
            payload = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="DeviceDescription",deviceId=f.b.device_id,
                           instanceId=f.b.instance_id,capturedAt=now,validUntil=now+30000,capabilities=[{"id":"phone","version":"1.0"}],grantReceipts=[signed],metadata={"platform":"android"},executionAuthorized=False)
        elif request.method == "task.get":
            allocation = self.sent[0].get("budget")["allocationId"]
            native = dict(format="pocketworld.device-owner-input.v1", nativePortPresent=True, originalSessionPresent=True,
                          taskId="actual-phone-task", runId="actual-phone-run", executorEpoch=2, allocationId=allocation,
                          sourceKind="native_paired_task_input", formalSourceRefAvailable=False, executionAuthorized=False,
                          runningInputState="unavailable" if self.waiting_question else "requires_current_write_grant_and_control_lease")
            if self.waiting_question:
                native["ordinaryQuestion"] = dict(requestId=self.question_id, waitRevision=4, question="Which fixture section?", approval=False, takeover=False)
            payload = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="TaskSnapshot", taskId="actual-phone-task",executorDeviceId=f.b.device_id,
                           instanceId=f.b.instance_id,state="waiting_dependency" if self.waiting_question else "running",revision=2,eventCursor="0",updatedAt=now,runId="actual-phone-run",executorEpoch=2,
                           result={"summary":"fixture result read, never cached", "ownerInput":native, "origin":{"grantId":"grant-1"}})
        else:
            operation = request.get("operationId") or request.arguments["operationId"]
            source = request if request.get("operationId") else next(item for item in self.sent if item.get("operationId") == operation)
            goal = self.sent[0]
            accepted_at = self.accepted_times.setdefault(operation, now)
            audience = dict(kind="operation", objectId=operation, originDeviceId=f.a.device_id, callerDeviceId=f.a.device_id,
                            targetDeviceId=f.b.device_id, instanceId=f.b.instance_id,rootTaskId=source.get("rootTaskId") or goal.get("rootTaskId"),localTaskId=source.get("localTaskId") or goal.get("localTaskId"),
                            grantId="grant-1",authorizationEpoch=source.get("authorizationEpoch"),resourceBinding=f.binding,acceptedAt=accepted_at,retainedUntil=accepted_at+60000,
                            runId="actual-phone-run",executorEpoch=2,taskId="actual-phone-task")
            payload = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="OperationReceipt",executorDeviceId=f.b.device_id,
                           instanceId=f.b.instance_id,operationId=operation,argumentsDigest=source.get("argumentsDigest"),status="running",accepted=True,effectState="reported",
                           payload={},acceptedAt=accepted_at,audience=audience,localTaskId="actual-phone-task",runId="actual-phone-run",executorEpoch=2)
            if request.method == "task.acquireControl":
                payload.update(status="succeeded", payload=dict(leaseId="native-control-lease", expiresAt=now+30000, executionMoved=False))
            elif request.method == "operation.cancel":
                payload.update(status="succeeded", payload={"managementOnly":True}, cancellation={"targetOperationId":request.arguments["operationId"],
                    "targetArgumentsDigest":request.arguments["argumentsDigest"], "requested":True, "stopped":False})
            elif request.method in {"task.answerQuestion", "task.steer"}:
                payload["payload"] = dict(inputSaved=True, messageId="native-message-"+operation, taskId="actual-phone-task", runId="actual-phone-run", executorEpoch=2,
                                          allocationId=request.arguments["allocationId"], sourceKind="native_paired_task_input", formalSourceRefAvailable=False, executionAuthorized=False)
            elif request.method == "task.requestResume":
                payload.update(status="succeeded", payload=dict(requested=True, ownerActionRequired=True, resumeRequestId=operation,
                    taskId="actual-phone-task", runId="actual-phone-run", executorEpoch=2, allocationId=request.arguments["allocationId"],
                    requestedRevision=request.arguments["expectedRevision"], reviewRevision=3, newRun=False, newGrant=False, executionAuthorized=False))
        response = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="DeviceResponse", requestId=request.get("requestId"),method=request.method,
                        executorDeviceId=f.b.device_id,instanceId=f.b.instance_id,result=payload)
        if self.mode == "wrong-instance":
            response["instanceId"] = "another-phone-boot"
        if self.response_hook is not None:
            self.response_hook(request, response)
        self.router.receive(self.connection, f.peer_b, wire.canonical_bytes(response))
        return True

    async def test_same_original_task_status_and_duplicate_never_submit_twice(self):
        result = await self.router.execute("run", self.params)
        self.assertEqual("actual-phone-task", result.get("taskId"), result)
        self.assertEqual(1, len(self.sent))
        status = await self.router.execute("status", {"operationId":self.params["operationId"],"peerDeviceId":self.params["peerDeviceId"]})
        self.assertEqual("actual-phone-task", status["task"]["taskId"])
        self.assertEqual("actual-phone-run", status["task"]["runId"])
        self.router.connection_closed(self.connection)
        duplicate = await self.router.execute("run", self.params)
        self.assertEqual("actual-phone-task", duplicate["taskId"])
        self.assertEqual(1, sum(request.method == "task.submitGoal" for request in self.sent))
        with self.assertRaises(PhoneTaskError):
            await self.router.execute("run", dict(self.params, goal="different goal, same ID"))
        raw = self.db.execute("SELECT details FROM operations").fetchone()[0]
        self.assertNotIn(self.params["goal"], raw)
        self.assertNotIn("fixture result read", raw)

    async def test_disconnect_and_wrong_instance_remain_unknown_and_restart_does_not_replay(self):
        self.mode = "disconnect"
        first = await self.router.execute("run", self.params)
        self.assertEqual("unknown", first["status"])
        reopened = PhoneTaskRouter(self.service)
        self.assertEqual("unknown", (await reopened.execute("run", self.params))["status"])
        self.assertEqual(1, len(self.sent))
        self.router.connection_opened(self.connection, self.fixture.peer_b)
        self.mode = "wrong-instance"
        second = await self.router.execute("run", dict(self.params, operationId="second-original-op"))
        self.assertEqual("unknown", second["status"])

    async def test_cold_private_stream_and_legacy_forward_receipts_never_claim_a_live_connection(self):
        for protocol in ("pocketworld.usb-forward.v1", "pocketworld.usb-stream.v1"):
            for status in ("dispatch_prepared", "usb_connected"):
                operation="usb-"+str(uuid.uuid4())
                value=dict(protocol=protocol,operationId=operation,peerDeviceId=self.params["peerDeviceId"],status=status,cleanupConfirmed=False)
                self.db.execute("INSERT INTO operations VALUES(?,?,?,?,?,?)",(operation,"fixed","usb:"+operation,status,self.manager.now(),json.dumps(value)))
                self.db.commit()
                cold=PhoneTaskRouter(self.service)
                answer=await cold.execute("usb_status",dict(operationId=operation,peerDeviceId=self.params["peerDeviceId"]))
                self.assertEqual("unknown",answer["status"]);self.assertFalse(answer["cleanupConfirmed"])
                self.assertEqual({},cold.usb);self.assertEqual([],self.sent)

    async def test_revoke_before_next_new_request_and_wait_never_cancel(self):
        await self.router.execute("run", self.params)
        result = await self.router.execute("wait", {"operationId":self.params["operationId"],"peerDeviceId":self.params["peerDeviceId"],"timeoutMs":0})
        self.assertEqual("running", result["task"]["state"])
        self.assertTrue(result["waitTimedOut"])
        self.assertFalse(any(request.method in {"task.cancel","operation.cancel"} for request in self.sent))
        self.db.execute("UPDATE device_peers SET revoked=1"); self.db.commit()
        with self.assertRaises(PermissionError):
            await self.router.execute("run", dict(self.params, operationId="after-revocation"))
        self.assertEqual(1, sum(request.method == "task.submitGoal" for request in self.sent))

    async def test_discovery_verifies_current_signed_receipts_without_task_execution(self):
        self.db.execute("DELETE FROM device_outgoing_grants");self.db.commit()
        result=await self.router.execute("doctor",{"peerDeviceId":self.fixture.b.device_id})
        self.assertFalse(result["description"]["executionAuthorized"])
        self.assertFalse(result["modelInferenceVerified"])
        self.assertIsNotNone(self.service.receiver.trust.outgoing_grant(self.fixture.b.device_id,"grant-1"))
        self.assertEqual(["device.describe"],[item.method for item in self.sent])

    def input_params(self, operation="ordinary-input", **updates):
        value = dict(operationId=self.params["operationId"], peerDeviceId=self.params["peerDeviceId"], controlOperationId=operation,
                     taskId="actual-phone-task", runId="actual-phone-run", executorEpoch=2, allocationId=self.sent[0].get("budget")["allocationId"],
                     controlLeaseId="native-control-lease", text="native ordinary answer, never source cached")
        value.update(updates)
        return value

    async def test_original_control_answer_and_steer_use_exact_native_scope_without_resubmission(self):
        await self.router.execute("run", self.params)
        acquire = self.input_params("native-acquire")
        acquire.pop("controlLeaseId"); acquire.pop("text")
        lease = await self.router.execute("acquire_control", acquire)
        self.assertEqual("native-control-lease", lease["controlLeaseId"], lease)
        self.assertEqual("actual-phone-run", lease["runId"])
        self.waiting_question = True
        answer = self.input_params(originalRequestId=self.question_id, waitRevision=4)
        saved = await self.router.execute("answer", answer)
        self.assertTrue(saved["inputSaved"], saved); self.assertEqual("actual-phone-task", saved["taskId"])
        self.assertEqual(answer["allocationId"], saved["allocationId"])
        self.waiting_question = False
        await self.router.execute("steer", self.input_params("native-steer", text="Use the second fixture section"))
        self.assertEqual(1, sum(item.method == "task.submitGoal" for item in self.sent))
        self.assertEqual(["task.acquireControl", "task.answerQuestion", "task.steer"], [item.method for item in self.sent if item.method in {"task.acquireControl", "task.answerQuestion", "task.steer"}])
        for item in self.sent:
            if item.method in {"task.acquireControl", "task.answerQuestion", "task.steer"}:
                self.assertEqual([self.fixture.a.device_id], item.get("visitedDevices"))
                self.assertEqual(1, item.get("maxDelegationDepth")); self.assertEqual([], item.get("delegationChain"))
        raw = " ".join(row[0] for row in self.db.execute("SELECT details FROM operations"))
        self.assertNotIn(answer["text"], raw); self.assertNotIn("Which fixture section?", raw)

    async def test_wrong_run_allocation_question_or_approval_cannot_dispatch_input(self):
        await self.router.execute("run", self.params); self.waiting_question = True
        for updates in ({"runId":"obsolete-run"}, {"executorEpoch":1}, {"allocationId":"wrong-allocation"}, {"waitRevision":3}, {"originalRequestId":"another-question"}, {"approval":True}, {"decision":"approved"}, {"takeover":True}):
            arguments = self.input_params(originalRequestId=self.question_id, waitRevision=4)
            arguments.update(updates)
            with self.assertRaises((PhoneTaskError, wire.ProtocolError)):
                await self.router.execute("answer", arguments)
        self.assertFalse(any(item.method == "task.answerQuestion" for item in self.sent))
        self.assertEqual(1, self.db.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    async def test_unknown_input_survives_restart_without_preflight_or_write_replay(self):
        await self.router.execute("run", self.params); self.waiting_question = True; self.mode = "disconnect-write"
        answer = self.input_params(originalRequestId=self.question_id, waitRevision=4)
        first = await self.router.execute("answer", answer)
        self.assertEqual("unknown", first["status"]); self.assertEqual(answer["controlOperationId"], first["operationId"])
        before = len(self.sent); reopened = PhoneTaskRouter(self.service)
        self.assertEqual("unknown", (await reopened.execute("answer", answer))["status"]); self.assertEqual(before, len(self.sent))
        with self.assertRaises(PhoneTaskError):
            await reopened.execute("answer", dict(answer, text="changed after unknown"))
        self.assertEqual(before, len(self.sent))

    async def test_receiving_owner_old_grant_does_not_gain_new_input_verbs(self):
        await self.router.execute("run", self.params)
        # Reset only the controlled fixture's source receipt; receiving owner signs
        # the unchanged old capability set. This is not a production grant edit.
        original = self.fixture.trust.grant("grant-1"); original["capabilities"].remove("task.steer")
        self.fixture.db.execute("UPDATE device_grants SET body_json=? WHERE grant_id='grant-1'", (json.dumps(original),)); self.fixture.db.commit()
        signed = self.fixture.coordinator.issue_grant_receipt(self.fixture.b, "grant-1", self.manager.now())
        self.db.execute("DELETE FROM device_outgoing_grants"); self.db.commit()
        self.service.receiver.pairing.accept_outgoing_grant(self.fixture.peer_b, signed, self.manager.now())
        with self.assertRaises(PhoneTaskError):
            await self.router.execute("steer", self.input_params())
        self.assertFalse(any(item.method == "task.steer" for item in self.sent))

    async def test_resume_requests_native_review_at_current_revision_without_new_goal(self):
        await self.router.execute("run", self.params)
        resume=self.input_params("native-resume", expectedRevision=2);resume.pop("text")
        with self.assertRaises(PhoneTaskError):
            await self.router.execute("resume", dict(resume, expectedRevision=1))
        self.assertFalse(any(item.method=="task.requestResume" for item in self.sent))
        value=await self.router.execute("resume", resume)
        self.assertTrue(value["ownerActionRequired"]);self.assertEqual(2,value["requestedRevision"]);self.assertEqual(3,value["reviewRevision"])
        before=len(self.sent);self.router.connection_closed(self.connection)
        self.assertEqual(value,await self.router.execute("resume",resume));self.assertEqual(before,len(self.sent))
        self.assertEqual(1,sum(item.method=="task.submitGoal" for item in self.sent))

    async def test_error_frame_binds_exact_authenticated_request_and_is_never_replayed(self):
        self.mode="rejected"
        value=await self.router.execute("run",self.params)
        self.assertEqual("rejected",value["status"]);self.assertEqual("not_dispatched",value["effectState"])
        self.assertFalse(value["accepted"])
        self.assertEqual(value,await self.router.execute("run",self.params));self.assertEqual(1,len(self.sent))

    async def test_current_pairing_is_checked_after_queue_before_any_mutation_bytes(self):
        queued=[]
        def enqueue(raw,*,before_write):queued.append((raw,before_write));return True
        self.connection.send_control=enqueue
        running=asyncio.create_task(self.router.execute("run",self.params))
        await asyncio.sleep(0)
        self.assertEqual(1,len(queued));self.assertEqual([],self.sent)
        self.db.execute("UPDATE device_peers SET revoked=1");self.db.commit()
        with self.assertRaises(PermissionError):queued[0][1](self.fixture.peer_b)
        self.router.connection_closed(self.connection)
        value=await running;self.assertEqual("unknown",value["status"])
        self.assertEqual(value,await self.router.execute("run",self.params));self.assertEqual(1,len(queued))

    async def test_old_channel_reply_cannot_settle_a_new_generation_and_close_never_cancels(self):
        queued=[]
        def enqueue(raw,*,before_write):queued.append((wire.parse_request(raw),before_write));return True
        self.connection.send_control=enqueue
        running=asyncio.create_task(self.router.execute("run",self.params));await asyncio.sleep(0)
        old=self.connection
        class NewLink:
            lane,closed,state="control",False,"online"
        current=NewLink();current.send_control=enqueue
        self.router.connection_opened(current,self.fixture.peer_b)
        value=await running;self.assertEqual("unknown",value["status"])
        late=wire.ProtocolError("denied","Late former channel").response(queued[0][0].get("requestId"))
        self.assertTrue(self.router.receive(old,self.fixture.peer_b,wire.canonical_bytes(late)))
        self.assertEqual("unknown",self.router._row(self.params["operationId"])[1]["status"])
        with self.assertRaises(PermissionError):queued[0][1](self.fixture.peer_b)
        await self.router.close()
        with self.assertRaises(PhoneTaskError):await self.router.execute("run",dict(self.params,operationId="closed-new"))
        self.assertEqual(1,len(queued));self.assertFalse(any(item.method=="operation.cancel" for item in self.sent))

    async def test_cancel_uses_separate_receipt_does_not_claim_stopped_or_resubmit_goal(self):
        await self.router.execute("run",self.params)
        cancel={"operationId":self.params["operationId"],"peerDeviceId":self.params["peerDeviceId"],"cancelOperationId":"cancel-original"}
        value=await self.router.execute("cancel",cancel)
        self.assertTrue(value["cancellation"]["requested"]);self.assertFalse(value["cancellation"]["stopped"])
        self.assertEqual(self.params["operationId"],value["targetOperationId"])
        before=len(self.sent);self.router.connection_closed(self.connection)
        self.assertEqual(value,await self.router.execute("cancel",cancel));self.assertEqual(before,len(self.sent))
        self.assertEqual(1,sum(item.method=="task.submitGoal" for item in self.sent));self.assertEqual(1,sum(item.method=="operation.cancel" for item in self.sent))
        with self.assertRaises(PhoneTaskError):
            await self.router.execute("status",{"operationId":self.params["operationId"],"peerDeviceId":"pw1_"+"b"*64})


class LocalSurfaceTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.client = SimpleNamespace(execute=lambda action, params: self.calls.append((action,params)) or {"status":"running","operationId":params.get("operationId")})
        self.peer = "pw1_" + "a" * 64
        self.desk = TaskDesk(self.client, self.peer)
        self.desk.start()
    def tearDown(self): self.desk.close()
    def request(self, path="/api", body=None, **headers):
        connection = http.client.HTTPConnection(*self.desk.server_address, timeout=3)
        try:
            connection.request("POST", path, json.dumps(body or {"action":"status","params":{"peerDeviceId":self.peer,"operationId":"original"}}),
                               {"Content-Type":"application/json","Authorization":"Bearer "+self.desk.session_token,**headers})
            response=connection.getresponse(); return response.status,response.read()
        finally: connection.close()
    def test_desk_blocks_origin_host_token_and_fixed_phone_switch(self):
        self.assertEqual(403,self.request(Origin="https://untrusted.invalid")[0])
        self.assertEqual(403,self.request(Host="untrusted.invalid")[0])
        self.assertEqual(403,self.request(Authorization="Bearer incorrect")[0])
        self.assertEqual(403,self.request(body={"action":"status","params":{"peerDeviceId":"pw1_"+"b"*64,"operationId":"original"}})[0])
        self.assertEqual([],self.calls)
        self.assertEqual(200,self.request(Origin=self.desk.origin)[0])
        self.assertEqual(1,len(self.calls))
    def test_mcp_http_json_lifecycle_and_stdio_do_not_accept_phone_approval_text(self):
        message={"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":VERSION,"capabilities":{},"clientInfo":{"name":"controlled-fixture","version":"1"}}}
        headers={"Accept":"application/json, text/event-stream"}
        status,raw=self.request("/mcp",message,**headers)
        self.assertEqual(200,status); self.assertEqual(VERSION,json.loads(raw)["result"]["protocolVersion"])
        status,_=self.request("/mcp",{"jsonrpc":"2.0","method":"notifications/initialized"},**headers,**{"MCP-Protocol-Version":VERSION})
        self.assertEqual(202,status)
        response=rpc(self.client,{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"phone_task_answer","arguments":{"decision":"approve"}}})
        self.assertIn("error",response); self.assertEqual([],self.calls)
        source=io.BytesIO((json.dumps(message)+"\n"+json.dumps({"jsonrpc":"2.0","method":"notifications/initialized"})+"\n"+json.dumps({"jsonrpc":"2.0","id":3,"method":"tools/list"})+"\n").encode()); out=io.BytesIO()
        stdio(self.client,source,out)
        records=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual(2,len(records)); self.assertIn("phone_task_run",[tool["name"] for tool in records[1]["result"]["tools"]])
        names={tool["name"] for tool in records[1]["result"]["tools"]}
        self.assertTrue({"phone_task_acquire_control", "phone_task_answer", "phone_task_steer"} <= names)
        ordinary=dict(operationId="original", peerDeviceId=self.peer, controlOperationId="answer-write", taskId="original-task", runId="original-run", executorEpoch=1,
                      allocationId="original-allocation", controlLeaseId="original-lease", text="owner text", originalRequestId="native-question", waitRevision=4)
        for field in ("decision", "approval", "takeover"):
            response=rpc(self.client,{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"phone_task_answer","arguments":dict(ordinary, **{field:True})}})
            self.assertIn("error", response)
        self.assertEqual([], self.calls)
    def test_write_failure_returns_original_unknown_without_retry_or_proxy(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"token";path.write_text("fixture-companion-token-only-1234567890");path.chmod(0o600)
            client=Client("http://127.0.0.1:1",path)
            with patch.dict(os.environ,{"HTTP_PROXY":"http://untrusted.invalid:99","HTTPS_PROXY":"http://untrusted.invalid:99"}):
                value=client.execute("run",{"operationId":"kept-original-id"})
            self.assertEqual("unknown",value["status"]);self.assertEqual("kept-original-id",value["operationId"]);self.assertFalse(value["automaticRetry"])
            for action in ("acquire_control", "answer", "steer"):
                value=client.execute(action,{"operationId":"original-goal", "controlOperationId":"original-input-write"})
                self.assertEqual("unknown",value["status"]);self.assertEqual("original-input-write",value["operationId"]);self.assertFalse(value["automaticRetry"])

    def test_actual_desk_controller_preserves_each_original_draft_when_selection_changes_during_write(self):
        result=subprocess.run(["node", str(Path(__file__).with_name("test_phone_task_desk.cjs"))], capture_output=True, text=True, timeout=10)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn("2 cases passed",result.stdout)


class UsbOwnershipTests(unittest.TestCase):
    def test_serial_boot_and_cleanup_only_our_private_stream(self):
        from test_phone_task_usb import AdbFixture
        with tempfile.TemporaryDirectory() as root:
            adb=AdbFixture(root);self.addCleanup(adb.close);forward=adb.forward()
            stream=forward.open();adb.rows[49999]=(adb.serial,"tcp:8888")
            adb.boot=8
            with self.assertRaises(UsbForwardError):forward.check_boot()
            self.assertTrue(forward.close());self.assertEqual(-1,stream.fileno())
            self.assertEqual({49999:(adb.serial,"tcp:8888")},adb.rows)
            self.assertFalse(any("forward" in argv for argv in adb.calls))
            stale=adb.forward()
            with self.assertRaises(UsbForwardError):stale.open()
            self.assertTrue(stale.cleanup_confirmed);self.assertEqual("closed",stale.state)
            self.assertEqual(1,adb.connect_count)
    def test_unavailable_adb_stream_never_retries_or_removes_other_forwards(self):
        from test_phone_task_usb import AdbFixture
        with tempfile.TemporaryDirectory() as root:
            adb=AdbFixture(root);self.addCleanup(adb.close);adb.connect_failure=True;forward=adb.forward()
            with self.assertRaises(TimeoutError):forward.open()
            self.assertEqual("closed",forward.state);self.assertTrue(forward.close())
            with self.assertRaises(UsbForwardError):forward.open()
            self.assertEqual(1,adb.connect_count);self.assertEqual(1,len(adb.calls))


if __name__ == "__main__":unittest.main()
