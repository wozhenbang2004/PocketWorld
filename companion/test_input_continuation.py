"""Controlled original session + HTTP binary ready/start; never a real provider/account."""
import hashlib
import io
import json
from pathlib import Path
import socket
import threading
import time
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import input_files
from pocket_companion import CompanionHandler, CompanionServer, RequestError
from test_cold_session import ColdRecoveryTests, PIPE, TOKEN

# The CLI genuinely reads only the new native-listed paths, but remains a controlled protocol peer.
INPUT_PIPE = PIPE.replace(" elif method=='turn/start':\n", """ elif method=='turn/start':
  import re,hashlib
  text=v['params']['input'][0]['text']
  paths=re.findall(r'^(.pocketworld-inputs/[^\\n]+) \\([0-9]+ bytes; SHA-256 [a-f0-9]{64}\\)$',text,re.M)
  (home/'input-bytes.json').write_text(json.dumps([{'path':path,'bytes':len(Path(path).read_bytes()),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest()}for path in paths]))
""")


class InputContinuationTests(unittest.TestCase):
    def setUp(self):
        self.f = ColdRecoveryTests()
        self.f.setUp()
        self.f.cli.write_text(INPUT_PIPE)
        self.f.server = CompanionServer(("127.0.0.1", 0), self.f.manager)
        self.f.server_thread = threading.Thread(target=self.f.server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        self.f.server_thread.start()
        self.base = "http://127.0.0.1:" + str(self.f.server.server_port)

    def tearDown(self):
        self.f.tearDown()

    def request(self, path, data=None, method=None, headers=None):
        request = Request(self.base + path, data=json.dumps(data).encode() if data is not None else None, method=method, headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json", **(headers or {})})
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())["result"]
        except HTTPError as failure:
            return failure.code, json.loads(failure.read())

    def body(self, name="本次原件.bin", raw=b"\x00\xffnew-private-exact-bytes\x80"):
        source = self.f.original
        file = {"attachmentId": str(uuid.uuid4()), "name": name, "mimeType": "application/octet-stream", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        return {"operationId": str(uuid.uuid4()), "environmentId": "computer", "environmentBinding": self.f.manager.environment_binding("computer"), "worker": source["worker"], "prompt": "Owner explicitly selected these NEW bytes for this exact session", "timeoutSeconds": 30, "threadId": source["threadId"], "resumeJobId": source["id"], "sourceJobOperationId": source["operationId"], "inputProtocol": input_files.PROTOCOL, "inputContinuationProtocol": input_files.CONTINUATION_PROTOCOL, "inputFiles": [file]}, raw

    def put(self, job, body, raw):
        request = Request(self.base + "/v1/jobs/" + job["id"] + "/inputs/0", data=raw, method="PUT", headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/octet-stream", "X-Pocket-Job-Operation": body["operationId"]})
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read())["result"]

    def start(self, job, operation=None):
        return self.request("/v1/jobs/" + job["id"] + "/start-with-inputs", {"jobOperationId": job["operationId"], "startOperationId": operation or str(uuid.uuid4()), "manifestHash": job["inputState"]["manifestHash"]})

    def test_new_exact_binary_inputs_only_start_a_new_original_job_in_same_session_after_ready(self):
        body, raw = self.body()
        status, job = self.request("/v1/jobs", body)
        self.assertEqual(200, status, job)
        self.assertEqual("awaiting_input", job["status"])
        self.assertEqual(self.f.original["id"], job["resumeJobId"])
        self.assertEqual(self.f.original["threadId"], job["threadId"])
        self.assertFalse(job["inputState"]["workerStarted"])
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        self.assertEqual(self.f.original["turnId"], job["inputState"]["continuationSource"]["turnId"])
        ready = self.put(job, body, raw)
        self.assertEqual("ready", ready["inputState"]["phase"])
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        start = str(uuid.uuid4())
        status, running = self.start(ready, start)
        self.assertEqual(200, status, running)
        final = self.f.wait(job["id"])
        self.assertEqual("completed", final["status"])
        self.assertEqual("fixture-turn-2", final["turnId"])
        self.assertEqual(self.f.original["threadId"], final["threadId"])
        actual = json.loads((self.f.protocol / "input-bytes.json").read_text())
        self.assertEqual(1, len(actual))
        self.assertEqual(hashlib.sha256(raw).hexdigest(), actual[0]["sha256"])
        self.assertEqual(raw, (self.f.workspace / actual[0]["path"]).read_bytes())
        self.assertEqual(self.f.original["output"], self.f.manager.get(self.f.original["id"])["output"])
        self.assertEqual(job["id"], self.start(ready, start)[1]["id"])
        self.assertEqual("2", (self.f.protocol / "starts").read_text())
        self.assertEqual(job["id"], self.request("/v1/jobs", body)[1]["id"])

    def test_missing_protocol_wrong_original_operation_and_pending_source_fail_before_reservation(self):
        body, _raw = self.body()
        before = len(self.f.manager.jobs)
        for change in ({"inputContinuationProtocol": "legacy"}, {"sourceJobOperationId": "another-original-op"}, {"threadId": "different-session"}):
            with self.subTest(change=change):
                status, error = self.request("/v1/jobs", {**body, **change})
                self.assertEqual(409, status, error)
                self.assertEqual(before, len(self.f.manager.jobs))
        original = self.f.manager.jobs[self.f.original["id"]]
        original["questionReceipts"] = [{"status": "unknown"}]
        self.assertEqual(409, self.request("/v1/jobs", body)[0])
        self.assertEqual(before, len(self.f.manager.jobs))
        self.assertEqual("1", (self.f.protocol / "starts").read_text())

    def test_lost_binary_ack_queries_same_slot_and_never_reads_a_second_body(self):
        body, raw = self.body()
        status, job = self.request("/v1/jobs", body)
        self.assertEqual(200, status)
        class Drop(CompanionHandler):
            def _send(self, status, value):
                if self.command == "PUT" and status == 200:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                super()._send(status, value)
        self.f.server.RequestHandlerClass = Drop
        with self.assertRaises(Exception):
            self.put(job, body, raw)
        self.f.server.RequestHandlerClass = CompanionHandler
        status, actual = self.request("/v1/jobs/" + job["id"])
        self.assertEqual(200, status)
        self.assertEqual("ready", actual["inputState"]["phase"])
        self.assertEqual(1, len(self.f.manager.jobs) - 1)
        class SecondBody(io.BytesIO):
            def read(self, *_args):
                raise AssertionError("Second body must never be read")
        with self.assertRaises(RequestError):
            input_files.upload(self.f.manager, job["id"], 0, body["operationId"], len(raw), SecondBody(raw), RequestError)
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        self.assertEqual(200, self.start(actual)[0])
        self.assertEqual("completed", self.f.wait(job["id"])["status"])
        self.assertEqual("2", (self.f.protocol / "starts").read_text())

    def test_changed_latest_turn_after_upload_blocks_worker_turn_and_keeps_actual_copies_unknown_cleanup(self):
        body, raw = self.body()
        status, job = self.request("/v1/jobs", body)
        self.assertEqual(200, status)
        ready = self.put(job, body, raw)
        self.f.change_stored(turnId="different-newer-session-turn")
        boundary = len(self.f.methods())
        self.assertEqual(200, self.start(ready)[0])
        final = self.f.wait(job["id"])
        self.assertEqual("blocked", final["status"])
        self.assertNotIn("thread/resume", self.f.methods()[boundary:])
        self.assertNotIn("turn/start", self.f.methods()[boundary:])
        self.assertEqual("1", (self.f.protocol / "starts").read_text())
        self.assertFalse(final["inputState"]["cleanupConfirmed"])
        self.assertEqual(raw, (self.f.workspace / final["inputState"]["files"][0]["path"]).read_bytes())

    def test_claude_same_verified_session_receives_actual_new_binary_only_after_ready(self):
        from test_claude_driver import FIXTURE as CLAUDE
        import sys
        self.f.manager.environments["computer"]["workers"].append("claude")
        script=self.f.root/"controlled-claude.py"
        script.write_text(CLAUDE.replace("    elif kind == 'user':\n", """    elif kind == 'user':
        import re,hashlib
        text=value['message']['content']
        paths=re.findall(r'^(.pocketworld-inputs/[^\\n]+) \\([0-9]+ bytes; SHA-256 [a-f0-9]{64}\\)$',text,re.M)
        Path('claude-input-bytes.json').write_text(json.dumps([{'path':path,'bytes':len(Path(path).read_bytes()),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest()}for path in paths]))
"""))
        original_launch=self.f.manager._launch
        self.f.manager._launch=lambda env,argv:([sys.executable,str(script),'normal',*argv[1:]],env['root'])if argv[0]=='claude'else original_launch(env,argv)
        source=self.f.manager.submit({'operationId':'original-claude-session','environmentId':'computer','worker':'claude','prompt':'Original Claude explicit work','timeoutSeconds':30})
        source=self.f.wait(source['id']);self.assertEqual('completed',source['status']);self.assertTrue(source['sessionIdentityConfirmed']);self.f.original=source
        body,raw=self.body('后续文件.bin',b'\x00actual-Claude-binary\xff')
        status,job=self.request('/v1/jobs',body);self.assertEqual(200,status,job);self.assertEqual(source['sessionId'],job['sessionId']);self.assertFalse(job['inputState']['workerStarted']);self.assertEqual('1',(self.f.workspace/'claude-prompt-count').read_text())
        ready=self.put(job,body,raw);self.assertEqual('ready',ready['inputState']['phase']);self.assertEqual('1',(self.f.workspace/'claude-prompt-count').read_text());self.assertEqual(200,self.start(ready)[0]);final=self.f.wait(job['id']);self.assertEqual('completed',final['status']);self.assertEqual(source['sessionId'],final['sessionId']);self.assertEqual(source['id'],final['resumeJobId']);actual=json.loads((self.f.workspace/'claude-input-bytes.json').read_text());self.assertEqual(hashlib.sha256(raw).hexdigest(),actual[0]['sha256']);self.assertEqual('11',(self.f.workspace/'claude-prompt-count').read_text())
