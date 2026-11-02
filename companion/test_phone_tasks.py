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
from unittest.mock import patch

import device_pairing as pairing
import device_protocol as wire
from phone_task_bridge import PhoneTaskError, PhoneTaskRouter
from phone_task_client import Client
from phone_task_desk import TaskDesk
from phone_task_mcp import rpc, stdio, VERSION
from phone_task_usb import OwnedUsbForward, UsbForwardError
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
        original["capabilities"] += ["task.get", "task.events", "operation.cancel"]
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
        self.mode = "accepted"
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

    def send(self, raw):
        request = wire.parse_request(raw)
        self.sent.append(request)
        if self.mode == "disconnect":
            self.router.connection_closed(self.connection)
            return True
        f = self.fixture
        now = self.manager.now()
        if request.method == "task.get":
            payload = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="TaskSnapshot", taskId="actual-phone-task",executorDeviceId=f.b.device_id,
                           instanceId=f.b.instance_id,state="running",revision=2,eventCursor="0",updatedAt=now,runId="actual-phone-run",executorEpoch=2,result={"summary":"fixture result read, never cached"})
        else:
            operation = request.get("operationId") or request.arguments["operationId"]
            source = request if request.get("operationId") else self.sent[0]
            audience = dict(kind="operation", objectId=operation, originDeviceId=f.a.device_id, callerDeviceId=f.a.device_id,
                            targetDeviceId=f.b.device_id, instanceId=f.b.instance_id,rootTaskId=source.get("rootTaskId"),localTaskId=source.get("localTaskId"),
                            grantId="grant-1",authorizationEpoch=1,resourceBinding=f.binding,acceptedAt=now,retainedUntil=now+60000,
                            runId="actual-phone-run",executorEpoch=2,taskId="actual-phone-task")
            payload = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="OperationReceipt",executorDeviceId=f.b.device_id,
                           instanceId=f.b.instance_id,operationId=operation,argumentsDigest=source.get("argumentsDigest"),status="running",accepted=True,effectState="reported",
                           payload={},acceptedAt=now,audience=audience,localTaskId="actual-phone-task",runId="actual-phone-run",executorEpoch=2)
        response = dict(protocolVersion=wire.VERSION,protocolProfile=wire.PROFILE,messageType="DeviceResponse", requestId=request.get("requestId"),method=request.method,
                        executorDeviceId=f.b.device_id,instanceId=f.b.instance_id,result=payload)
        if self.mode == "wrong-instance":
            response["instanceId"] = "another-phone-boot"
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
        source=io.BytesIO((json.dumps(message)+"\n"+json.dumps({"jsonrpc":"2.0","id":3,"method":"tools/list"})+"\n").encode()); out=io.BytesIO()
        stdio(self.client,source,out)
        records=[json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual(2,len(records)); self.assertIn("phone_task_run",[tool["name"] for tool in records[1]["result"]["tools"]])
    def test_write_failure_returns_original_unknown_without_retry_or_proxy(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"token";path.write_text("fixture-companion-token-only-1234567890");path.chmod(0o600)
            client=Client("http://127.0.0.1:1",path)
            with patch.dict(os.environ,{"HTTP_PROXY":"http://untrusted.invalid:99","HTTPS_PROXY":"http://untrusted.invalid:99"}):
                value=client.execute("run",{"operationId":"kept-original-id"})
            self.assertEqual("unknown",value["status"]);self.assertEqual("kept-original-id",value["operationId"]);self.assertFalse(value["automaticRetry"])


class UsbOwnershipTests(unittest.TestCase):
    def test_serial_boot_and_cleanup_only_our_exact_forward(self):
        with tempfile.TemporaryDirectory() as root:
            adb=Path(root)/"adb-fixture";adb.touch();calls=[];mapping={};boot=[7]
            def run(argv,**kwargs):
                calls.append(argv);self.assertEqual([str(adb),"-s","selected-serial"],argv[:3]);args=argv[3:]
                if args==["shell","settings","get","global","boot_count"]:out=str(boot[0])
                elif args[:2]==["forward","--no-rebind"]:mapping[40123]="tcp:9991";out="40123"
                elif args==["forward","--list"]:out="\n".join("selected-serial tcp:"+str(port)+" "+target for port,target in mapping.items())
                elif args==["forward","--remove","tcp:40123"]:mapping.pop(40123,None);out=""
                else:raise AssertionError(args)
                return subprocess.CompletedProcess(argv,0,out.encode(),b"")
            forward=OwnedUsbForward(adb,"selected-serial",7,9991,run=run)
            self.assertEqual(40123,forward.open());mapping[49999]="tcp:8888"
            boot[0]=8
            with self.assertRaises(UsbForwardError):forward.check_boot()
            self.assertTrue(forward.close());self.assertEqual({49999:"tcp:8888"},mapping)
            self.assertFalse(any("--remove-all" in argv for argv in calls))
            stale=OwnedUsbForward(adb,"selected-serial",7,9991,run=run)
            with self.assertRaises(UsbForwardError):stale.open()
            self.assertEqual("not_started",stale.state)
    def test_unknown_adb_creation_never_retries_or_removes_other_forwards(self):
        with tempfile.TemporaryDirectory() as root:
            adb=Path(root)/"adb-fixture";adb.touch();calls=[]
            def run(argv,**kwargs):
                calls.append(argv)
                if "boot_count" in argv:return subprocess.CompletedProcess(argv,0,b"3",b"")
                raise subprocess.TimeoutExpired(argv,6)
            forward=OwnedUsbForward(adb,"fixed",3,9991,run=run)
            with self.assertRaises(subprocess.TimeoutExpired):forward.open()
            self.assertEqual("unknown",forward.state);self.assertFalse(forward.close())
            with self.assertRaises(UsbForwardError):forward.open()
            self.assertEqual(2,len(calls))


if __name__ == "__main__":unittest.main()
