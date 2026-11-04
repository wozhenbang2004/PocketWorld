"""Original-job mailbox and real stdio MCP transport regression tests."""
import concurrent.futures
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import team_harness
import team_mcp


class RequestError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class Manager:
    def __init__(self):
        self.lock = threading.RLock()
        self.stopping = set()
        self.jobs = {"job-a": {"id": "job-a", "operationId": "operation-a", "status": "running"}}
        self.saved = []

    def _save(self, job):
        self.saved.append(copy.deepcopy(job))

    def _public(self, job):
        return copy.deepcopy(job)

    def now(self):
        return int(time.time() * 1000)


def context():
    return {"protocol": team_harness.PROTOCOL, "hostTaskId": "task-a", "runId": "run-a", "turnId": "turn-a",
            "memberId": "researcher", "round": 1, "generation": 0, "runGeneration": 0,
            "roster": [{"id": "researcher", "name": "研究员"}, {"id": "reviewer", "name": "审核员"}]}


class TeamHarnessTest(unittest.TestCase):
    def setUp(self):
        self.manager = Manager()
        self.broker = team_harness.TeamHarnessBroker(self.manager)
        self.handle = self.broker.create(self.manager.jobs["job-a"], context())
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)

    def tearDown(self):
        self.broker.close()
        self.pool.shutdown(wait=True)

    def pending(self):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            with self.manager.lock:
                rows = self.manager.jobs["job-a"]["pendingTeamTools"]
                if rows:
                    return copy.deepcopy(rows[0])
            threading.Event().wait(.01)
        self.fail("No original tool request was persisted")

    def answer(self, pending, result):
        return self.broker.respond("job-a", pending["requestId"], {"jobOperationId": "operation-a", "result": result}, RequestError)

    def test_context_is_bound_and_cannot_carry_grants(self):
        original = context()
        checked = team_harness.validate_context({"teamContext": original}, {"kind": "local"}, RequestError)
        self.assertEqual(original, checked)
        for extra in ("argv", "permissions", "token"):
            with self.assertRaises(RequestError):
                team_harness.validate_context({"teamContext": {**original, extra: "unexpected"}}, {}, RequestError)
        with self.assertRaises(RequestError):
            team_harness.validate_context({"teamContext": {**original, "memberId": "outsider"}}, {}, RequestError)
        with self.assertRaises(RequestError):
            team_harness.validate_context({"teamContext": original, "worker": "claude"}, {"kind": "ssh"}, RequestError)

    def test_message_receipt_is_durable_before_delivery_and_idempotent(self):
        future = self.pool.submit(self.handle.call, "team_send_message", {"targetMemberId": "reviewer", "text": "请核对结论"})
        pending = self.pending()
        self.assertEqual(context(), self.manager.saved[-1]["teamContext"])
        self.assertFalse(future.done())
        with self.assertRaises(RequestError):
            self.broker.respond("job-a", pending["requestId"], {"jobOperationId": "another-operation", "result": {}}, RequestError)
        actual = {"status": "queued", "messageId": "original-message", "untrusted": True}
        self.answer(pending, actual)
        self.assertEqual(actual, future.result(timeout=2))
        self.answer(pending, actual)
        self.assertEqual(1, len(self.manager.jobs["job-a"]["teamToolReceipts"]))
        self.assertNotIn("result", self.manager.jobs["job-a"]["teamToolReceipts"][0])
        with self.assertRaises(RequestError):
            self.answer(pending, {"status": "queued", "messageId": "different"})

    def test_cancellation_invalidates_pending_answer_and_local_token(self):
        environment = self.handle.environment()
        future = self.pool.submit(self.handle.call, "team_read_messages", {})
        pending = self.pending()
        self.manager.stopping.add("job-a")
        self.broker.finish("job-a", "cancelled")
        self.assertEqual("unknown", future.result(timeout=2)["status"])
        with self.assertRaises(RequestError):
            self.answer(pending, {})
        body = json.dumps({"name": "team_list_members", "arguments": {}}).encode()
        request = Request(environment["POCKET_TEAM_URL"], body, {"Authorization": "Bearer " + environment["POCKET_TEAM_TOKEN"]})
        with self.assertRaises(HTTPError) as denied:
            urlopen(request, timeout=2)
        self.assertEqual(403, denied.exception.code)

    def test_model_cannot_claim_another_sender_or_call_execution_tools(self):
        for name, arguments in (("bash", {"command": "pwd"}), ("team_read_messages", {"memberId": "reviewer"}),
                                ("team_send_message", {"targetMemberId": "outsider", "text": "x"}),
                                ("team_send_message", {"targetMemberId": "researcher", "text": "x"})):
            with self.assertRaises(ValueError):
                self.handle.call(name, arguments)
        self.assertEqual([], self.manager.jobs["job-a"]["pendingTeamTools"])

    def test_team_message_cannot_persist_or_forward_its_transport_credential(self):
        secret = self.handle.token
        future = self.pool.submit(self.handle.call, "team_send_message", {"targetMemberId": "reviewer", "text": "Reflected " + secret})
        pending = self.pending()
        self.assertEqual("Reflected [team credential hidden]", pending["arguments"]["text"])
        self.assertNotIn(secret, json.dumps(self.manager.saved))
        self.answer(pending, {"status": "queued"})
        self.assertEqual("queued", future.result(timeout=2)["status"])

    def test_real_mcp_process_round_trip_and_duplicate_call(self):
        descriptor = self.handle.mcp_descriptor()
        environment = {**os.environ, **{item["name"]: item["value"] for item in descriptor["env"]}}
        process = subprocess.Popen([descriptor["command"], *descriptor["args"]], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
        def send(identity, method, params):
            process.stdin.write((json.dumps({"jsonrpc": "2.0", "id": identity, "method": method, "params": params}) + "\n").encode())
            process.stdin.flush()
        try:
            send(1, "initialize", {"protocolVersion": "2025-06-18"})
            self.assertEqual("2025-06-18", json.loads(process.stdout.readline())["result"]["protocolVersion"])
            send(2, "tools/list", {})
            self.assertEqual(team_harness.TOOL_NAMES, {item["name"] for item in json.loads(process.stdout.readline())["result"]["tools"]})
            params = {"name": "team_send_message", "arguments": {"targetMemberId": "reviewer", "text": "核对完成"}}
            send(3, "tools/call", params)
            pending = self.pending()
            self.answer(pending, {"status": "queued", "messageId": "message-a", "untrusted": True})
            first = json.loads(process.stdout.readline())
            self.assertIn("message-a", first["result"]["content"][0]["text"])
            send(3, "tools/call", params)
            self.assertEqual(first, json.loads(process.stdout.readline()))
            self.assertEqual(1, len(self.manager.jobs["job-a"]["teamToolReceipts"]))
            self.assertNotIn(environment["POCKET_TEAM_TOKEN"], json.dumps(self.manager.saved))
        finally:
            process.stdin.close()
            process.wait(timeout=3)
            self.assertEqual(b"", process.stderr.read())
            process.stdout.close()
            process.stderr.close()

    def test_frozen_companion_uses_its_bundled_stdio_entrypoint(self):
        with patch.object(sys, "frozen", True, create=True), patch.object(sys, "executable", "C:/PocketWorld/PocketWorld-Companion.exe"):
            descriptor = self.handle.mcp_descriptor()
        self.assertEqual("C:/PocketWorld/PocketWorld-Companion.exe", descriptor["command"])
        self.assertEqual(["--team-mcp-stdio"], descriptor["args"])
        environment = {item["name"]: item["value"] for item in descriptor["env"]}
        with patch.dict(os.environ, environment):
            self.assertEqual((environment["POCKET_TEAM_URL"], environment["POCKET_TEAM_TOKEN"]), team_mcp.endpoint_configuration())
        for endpoint in ("https://127.0.0.1:9000/tool", "http://example.com:9000/tool", "http://127.0.0.1:9000/tool?redirect=1"):
            with patch.dict(os.environ, {**environment, "POCKET_TEAM_URL": endpoint}), self.assertRaises(ValueError):
                team_mcp.endpoint_configuration()

    def test_packaged_stdio_entrypoint_does_not_initialize_a_desktop_or_config(self):
        entry = Path(team_harness.__file__).with_name("desktop_worker.py")
        request = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}}
        process = subprocess.run([sys.executable, str(entry), "--team-mcp-stdio"],
            input=(json.dumps(request) + "\n").encode(), capture_output=True, timeout=5,
            env={**os.environ, **self.handle.environment()})
        self.assertEqual(0, process.returncode, process.stderr.decode())
        self.assertEqual("PocketWorld team", json.loads(process.stdout)["result"]["serverInfo"]["name"])
        self.assertEqual(b"", process.stderr)

    def test_invalid_tool_request_gets_a_protocol_error(self):
        incoming = io.BytesIO(b'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}\n'
                             b'{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"bash"}}\n')
        outgoing = io.BytesIO()
        team_mcp.serve(incoming, outgoing)
        replies = [json.loads(line) for line in outgoing.getvalue().splitlines()]
        self.assertEqual(-32602, replies[1]["error"]["code"])


if __name__ == "__main__":
    unittest.main()
