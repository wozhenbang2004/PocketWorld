"""Actual loopback HTTP plus read-only progress fixtures; no real phone/credentials."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import io
import json
import os
from pathlib import Path, PosixPath
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from phone_task_client import Client, ClientError
from phone_task_desk import TaskDesk
from phone_task_mcp import VERSION
from phone_tasks import SelectedPhoneClient, main, pair_native, parser, watch

PEER = "pw1_" + "a" * 64
OTHER = "pw1_" + "b" * 64
TOKEN = "fixture-only-companion-token-not-a-user-credential"


@contextmanager
def companion_reply(status=200, result=None, raw=None, headers=None):
    calls = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            body = self.rfile.read(int(self.headers["Content-Length"]))
            calls.append((self.path, dict(self.headers), json.loads(body)))
            value = raw if raw is not None else json.dumps({"ok": True, "result": result or {"operationId":"operation-A", "peerDeviceId":PEER, "status":"running"}}).encode()
            self.send_response(status)
            for key, item in (headers or {}).items(): self.send_header(key, item)
            self.send_header("Content-Length", str(len(value))); self.send_header("Content-Type", "application/json"); self.end_headers()
            try: self.wfile.write(value)
            except (BrokenPipeError, ConnectionResetError): pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try: yield "http://127.0.0.1:" + str(server.server_address[1]), calls
    finally: server.shutdown(); server.server_close(); thread.join(2)


def snapshot(state="running", revision=1, instance="boot-A"):
    return {"operationId":"operation-A", "status":state, "effectState":"reported", "task":{
        "executorDeviceId":PEER, "instanceId":instance, "taskId":"task-A", "runId":"run-A", "executorEpoch":1,
        "revision":revision, "eventCursor":"3", "state":state, "result":{"summary":"fixture task result"}}}


def event(sequence=1, peer=PEER, instance="boot-A"):
    return {"executorDeviceId":peer, "instanceId":instance, "taskId":"task-A", "sequence":sequence,
            "kind":"progress", "data":{"summary":"fixture progress"}}


class ClientHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.path = Path(self.temp.name) / "private-token"
        self.path.write_text(TOKEN); self.path.chmod(0o600)
    def tearDown(self): self.temp.cleanup()
    def test_current_loopback_request_ignores_ambient_proxy_and_binds_identity(self):
        with companion_reply() as (endpoint, calls), patch.dict(os.environ, {"HTTP_PROXY":"http://invalid.example:1", "HTTPS_PROXY":"http://invalid.example:1"}):
            value = SelectedPhoneClient(Client(endpoint, self.path), PEER).execute("status", {"peerDeviceId":PEER,"operationId":"operation-A"})
            self.assertEqual("operation-A", value["operationId"])
        self.assertEqual(1, len(calls)); self.assertEqual("/v1/phone-tasks", calls[0][0])
        self.assertEqual("Bearer " + TOKEN, calls[0][1]["Authorization"]); self.assertNotIn("Origin", calls[0][1])
    def test_nonloopback_and_selected_phone_switch_fail_before_request(self):
        for endpoint in ("http://invalid.example", "http://127.0.0.1/path", "http://user@localhost", "http://127.0.0.1:0", "http://127.0.0.1:bad", "http://127.0.0.1#fragment"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ClientError): Client(endpoint, self.path)
        with companion_reply() as (endpoint, calls):
            with self.assertRaises(ClientError): SelectedPhoneClient(Client(endpoint,self.path),PEER).execute("run",{"peerDeviceId":OTHER,"operationId":"operation-A"})
            self.assertEqual([], calls)
    def test_mutation_bad_or_ambiguous_replies_keep_write_id_without_retry(self):
        scenarios = [(302,None,{"Location":"http://invalid.example"}), (503,None,None), (200,b"not-json",None), (200,b"x"*65537,None),
                     (200,b'{"ok":true,"ok":false,"result":{}}',None), (200,json.dumps({"ok":True,"result":{"operationId":"other","status":"succeeded"}}).encode(),None)]
        for status, raw, headers in scenarios:
            with self.subTest(status=status, size=len(raw or b"")), companion_reply(status,raw=raw,headers=headers) as (endpoint,calls):
                value=Client(endpoint,self.path).execute("resume",{"peerDeviceId":PEER,"operationId":"operation-A","controlOperationId":"resume-A"})
                self.assertEqual("unknown",value["status"]); self.assertEqual("resume-A",value["operationId"])
                self.assertEqual("operation-A",value["targetOperationId"]); self.assertFalse(value["automaticRetry"]); self.assertEqual(1,len(calls))
    def test_native_refusal_is_distinct_from_unknown_and_does_not_echo_body(self):
        with companion_reply(403,raw=("secret: "+TOKEN).encode()) as (endpoint,calls):
            with self.assertRaises(ClientError) as failure: Client(endpoint,self.path).execute("run",{"operationId":"operation-A"})
            self.assertEqual(403,failure.exception.status); self.assertNotIn(TOKEN,str(failure.exception)); self.assertEqual(1,len(calls))
    def test_cli_exit_status_distinguishes_native_rejection_unconfirmed_read_and_acceptance(self):
        cases = [("run", {"status":"rejected","accepted":False,"effectState":"not_dispatched"}, 4),
                 ("status", {"status":"running","accepted":True,"effectState":"reported","currentRead":"unavailable","phoneStateUnconfirmed":True}, 3),
                 ("run", {"status":"unknown","accepted":False,"effectState":"unknown"}, 3),
                 ("run", {"status":"waiting_approval","accepted":True,"effectState":"not_dispatched"}, 0)]
        for action, receipt, code in cases:
            result={"operationId":"operation-A","peerDeviceId":PEER,**receipt}
            with self.subTest(action=action, status=receipt["status"]), companion_reply(result=result) as (endpoint,calls), patch("sys.stdout",new_callable=io.StringIO) as output, patch("sys.stderr",new_callable=io.StringIO):
                arguments=["--endpoint",endpoint,"--token-file",str(self.path),"--phone",PEER,action]
                arguments += (["fixture goal","--operation-id","operation-A","--grant","fixture-grant","--resource",'{"screenMode":"capabilities"}',"--capability-version","1.0"] if action=="run" else ["operation-A"])
                self.assertEqual(code,main(arguments));self.assertEqual(result,json.loads(output.getvalue()))
                self.assertEqual(1,len(calls));self.assertEqual("operation-A",calls[0][2]["params"]["operationId"])
    def test_wrong_phone_read_and_unsafe_token_are_rejected_without_secret_errors(self):
        with companion_reply(result={"operationId":"operation-A","peerDeviceId":OTHER,"status":"running"}) as (endpoint,_):
            with self.assertRaises(ClientError): Client(endpoint,self.path).execute("status",{"operationId":"operation-A","peerDeviceId":PEER})
        if os.name != "nt":
            self.path.chmod(0o644)
            with self.assertRaises(ClientError): Client("http://127.0.0.1:1",self.path)
            self.path.chmod(0o600); link=self.path.with_name("link"); link.symlink_to(self.path)
            with self.assertRaises(ClientError) as failure: Client("http://127.0.0.1:1",link)
            self.assertNotIn(self.temp.name,str(failure.exception))
    def test_invalid_wait_and_nonfinite_body_are_never_sent(self):
        with companion_reply() as (endpoint,calls):
            client=Client(endpoint,self.path)
            for parameters in ({"timeoutMs":True},{"timeoutMs":float("nan")},{"timeoutMs":45001},{"extra":float("inf")}):
                with self.assertRaises(ClientError): client.execute("status",parameters)
            self.assertEqual([],calls)


class DeskBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.calls=[]
        def execute(action,params):
            self.calls.append((action,params)); return {"operationId":params.get("operationId"),"status":"running"}
        self.desk=TaskDesk(SimpleNamespace(execute=execute),PEER); self.url=self.desk.start()
    def tearDown(self): self.desk.close()
    def request(self,body,path="/api",headers=None):
        connection=http.client.HTTPConnection(*self.desk.server_address,timeout=3)
        try:
            connection.request("POST",path,body,{"Content-Type":"application/json","Authorization":"Bearer "+self.desk.session_token,**(headers or {})})
            response=connection.getresponse(); return response.status,response.read(),dict(response.getheaders())
        finally: connection.close()
    def test_duplicate_host_or_authorization_cannot_dispatch(self):
        body=json.dumps({"action":"status","params":{"peerDeviceId":PEER,"operationId":"operation-A"}})
        for header in ("Host","Authorization","Origin","Content-Length"):
            connection=http.client.HTTPConnection(*self.desk.server_address,timeout=3); connection.putrequest("POST","/api",skip_host=True)
            connection.putheader("Host",self.desk.origin.removeprefix("http://")); connection.putheader("Authorization","Bearer "+self.desk.session_token)
            connection.putheader("Origin",self.desk.origin); connection.putheader("Content-Type","application/json"); connection.putheader("Content-Length",str(len(body)))
            connection.putheader(header,"untrusted"); connection.endheaders(body.encode())
            response=connection.getresponse(); self.assertEqual(403,response.status); response.read(); connection.close()
        self.assertEqual([],self.calls)
    def test_duplicate_json_cross_site_fetch_and_arbitrary_action_are_rejected(self):
        base=json.dumps({"action":"status","params":{"peerDeviceId":PEER,"operationId":"operation-A"}})
        self.assertEqual(403,self.request(base,headers={"Sec-Fetch-Site":"cross-site"})[0])
        self.assertEqual(400,self.request('{"action":"run","action":"status","params":{}}')[0])
        self.assertEqual(400,self.request(json.dumps({"action":"pair","params":{"peerDeviceId":PEER,"command":"fixture-only"}}))[0]); self.assertEqual([],self.calls)
    def test_http_mcp_requires_initialize_acknowledgement_and_version(self):
        headers={"Accept":"application/json, text/event-stream","MCP-Protocol-Version":VERSION}; request={"jsonrpc":"2.0","id":2,"method":"tools/list"}
        self.assertEqual(400,self.request(json.dumps(request),"/mcp",headers)[0])
        initialize={"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":VERSION,"capabilities":{},"clientInfo":{"name":"fixture","version":"1"}}}
        self.assertEqual(200,self.request(json.dumps(initialize),"/mcp",headers)[0]); self.assertEqual(400,self.request(json.dumps(request),"/mcp",headers)[0])
        status,body,_=self.request(json.dumps({"jsonrpc":"2.0","method":"notifications/initialized"}),"/mcp",headers)
        self.assertEqual((202,b""),(status,body)); self.assertEqual(200,self.request(json.dumps(request),"/mcp",headers)[0])
        self.assertEqual(400,self.request(json.dumps(request),"/mcp",{**headers,"MCP-Protocol-Version":"wrong"})[0]); self.assertEqual([],self.calls)
    def test_browser_assets_have_no_companion_or_session_credential(self):
        for name in ("phone_task_desk.html","phone_task_desk.js","phone_task_desk.css"):
            content=Path(__file__).with_name(name).read_text(); self.assertNotIn(TOKEN,content); self.assertNotIn(self.desk.session_token,content)
        status,raw,headers=self.request(json.dumps({"action":"selection","params":{}}))
        self.assertEqual(200,status); self.assertEqual(PEER,json.loads(raw)["result"]["peerDeviceId"]); self.assertNotIn(self.desk.session_token,raw.decode())
        self.assertEqual("no-store",headers["Cache-Control"]); self.assertIn("frame-ancestors 'none'",headers["Content-Security-Policy"])


class CliProgressTests(unittest.TestCase):
    def test_watch_streams_cursor_deduplicates_events_and_finishes_same_task(self):
        calls=[]; out=[]; reads=0
        def execute(action,params):
            nonlocal reads
            calls.append((action,params.copy()))
            if action=="status": reads+=1; return snapshot("running" if reads==1 else "succeeded",reads)
            return {"events":[event(1),event(2)] if reads==1 else [event(2),event(3)],"nextCursor":str(2 if reads==1 else 3),"hasMore":False,"snapshotRequired":False}
        self.assertEqual(0,watch(SimpleNamespace(execute=execute),PEER,"operation-A",interval=.2,emit=out.append))
        self.assertEqual([1,2,3],[row["event"]["sequence"] for row in out if row["type"]=="event"])
        self.assertEqual("2",[params for action,params in calls if action=="events"][1]["cursor"])
        self.assertEqual({"status","events"},{action for action,_ in calls}); self.assertTrue(all(p["peerDeviceId"]==PEER and p["operationId"]=="operation-A" for _,p in calls))
    def test_reconnect_only_queries_selected_operation_and_changed_source_resets_cursor(self):
        calls=[]; out=[]; reads=0
        def execute(action,params):
            nonlocal reads
            calls.append((action,params.copy()))
            if action=="status":
                reads+=1
                if reads==2: raise ClientError("fixture disconnect")
                return snapshot("succeeded" if reads==3 else "running",reads,"boot-B" if reads==3 else "boot-A")
            return {"events":[],"nextCursor":params.get("cursor","0"),"hasMore":False,"snapshotRequired":False}
        self.assertEqual(0,watch(SimpleNamespace(execute=execute),PEER,"operation-A",interval=.2,reconnect=True,emit=out.append))
        self.assertEqual(1,sum(row["type"]=="disconnected" for row in out)); self.assertEqual(1,sum(row["type"]=="reconnected" for row in out))
        self.assertEqual(1,sum(row["type"]=="source_changed" for row in out)); self.assertEqual("3",[params for action,params in calls if action=="events"][-1]["cursor"])
        self.assertTrue(all(action in {"status","events"} for action,_ in calls))
    def test_wrong_phone_event_and_revocation_stop_without_dispatch(self):
        out=[]
        def execute(action,params): return snapshot() if action=="status" else {"events":[event(peer=OTHER)],"nextCursor":"1","hasMore":False,"snapshotRequired":False}
        self.assertEqual(3,watch(SimpleNamespace(execute=execute),PEER,"operation-A",emit=out.append)); self.assertFalse(any(row["type"]=="event" for row in out))
        revoked=SimpleNamespace(execute=lambda *_: (_ for _ in ()).throw(ClientError("revoked",403)))
        self.assertEqual(3,watch(revoked,PEER,"operation-A",reconnect=True,emit=out.append))
    def test_cli_native_pair_delegates_exact_entry_and_existing_config_only(self):
        args=SimpleNamespace(config=PosixPath("/fixture/existing.json"),development_state=True,identity_dir=PosixPath("/fixture/existing-identity"))
        with patch("phone_tasks.os.name","nt"),patch("phone_tasks.Path",PosixPath),patch("phone_tasks.subprocess.call",return_value=0) as run: self.assertEqual(0,pair_native(args))
        command=run.call_args.args[0]; self.assertTrue(command[1].endswith("desktop_worker.py")); self.assertIn("--pairing",command); self.assertIn("--development-state",command)
        self.assertNotIn("--init",command); self.assertEqual({},run.call_args.kwargs)
    def test_cli_parser_matches_event_bound_and_resume_contract(self):
        self.assertEqual(32,parser().parse_args(["events","operation-A"]).limit)
        value=parser().parse_args(["resume","operation-A","--task","task-A","--run","run-A","--epoch","2","--allocation","allocation-A","--control-lease","lease-A","--expected-revision","7"])
        self.assertEqual(7,value.expected_revision)
    def test_cli_acquire_reports_revision_after_lease_write(self):
        calls=[]
        class FixtureClient:
            def __init__(self,*_): pass
            def execute(self,action,params):
                calls.append((action,params)); return {"operationId":params.get("controlOperationId"),"status":"succeeded","effectState":"reported"} if action=="acquire_control" else snapshot(revision=7)
        argv=["--endpoint","http://127.0.0.1:1","--token-file","fixture","--phone",PEER,"acquire-control","operation-A","--task","task-A","--run","run-A","--epoch","1","--allocation","allocation-A"]
        out=io.StringIO()
        with patch("phone_tasks.Client",FixtureClient),patch("sys.stdout",out),patch("sys.stderr",io.StringIO()): self.assertEqual(0,main(argv))
        self.assertEqual(["acquire_control","status"],[action for action,_ in calls]); self.assertEqual(7,json.loads(out.getvalue())["task"]["revision"])


if __name__ == "__main__": unittest.main()
