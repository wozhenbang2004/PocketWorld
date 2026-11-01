import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

SDK = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SDK))
from pocket_plugin_server import LocalToolServer, Tool
sys.path.insert(0, str(SDK / "examples"))
from text_tools import normalize_text, word_count


class ToolingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classpath = os.environ["POCKET_PLUGIN_TEST_CLASSPATH"]
        cls.java = os.environ["POCKET_PLUGIN_TEST_JAVA"]

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pocket-sdk-test-")
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)

    def cli(self, *arguments, expected=0):
        result = subprocess.run([sys.executable, "-B", str(SDK / "pocket_plugin.py"), "--java", self.java,
                                 "--classpath", self.classpath, *map(str, arguments)], capture_output=True, text=True)
        self.assertEqual(expected, result.returncode, result.stderr)
        return json.loads(result.stdout) if expected == 0 else result.stderr

    def project(self, kind="echo", endpoint=None):
        path = self.path / "project"
        args = ["init", path, "--id", "user.custom", "--name", "Custom user plugin", "--kind", kind]
        if endpoint:
            args += ["--endpoint", endpoint]
        result = self.cli(*args)
        self.assertFalse(result["installed"])
        self.assertFalse(result["serverStarted"])
        return path

    def test_cli_creates_validates_packs_and_reads_real_package(self):
        project = self.project()
        self.assertFalse(self.cli("validate", project)["receipt"]["installedStateCompared"])
        target = self.path / "plugin.pwp.json"
        result = self.cli("pack", project, "--output", target, "--package-url", "https://example.test/plugin.pwp.json")
        raw = target.read_bytes()
        self.assertTrue(result["saved"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), result["receipt"]["packageSha256"])
        self.assertEqual(len(raw), result["catalogEntry"]["byteSize"])
        self.assertEqual(result["receipt"]["declarationDigest"], result["catalogEntry"]["declarationDigest"])
        self.assertEqual(result["receipt"]["packageSha256"], self.cli("inspect", target)["receipt"]["packageSha256"])
        before = target.read_bytes()
        self.cli("pack", project, "--output", target, expected=2)
        self.assertEqual(before, target.read_bytes())

    def test_cli_strict_json_and_argument_diagnostics(self):
        project = self.project()
        args = project / "arguments.json"
        self.assertTrue(self.cli("diagnose", project, "--tool", "echo", "--arguments", args)["valid"])
        args.write_text('{"text":42}', encoding="utf-8")
        self.assertFalse(self.cli("diagnose", project, "--tool", "echo", "--arguments", args)["valid"])
        (project / "manifest.json").write_text('{"id":"one","id":"two"}', encoding="utf-8")
        self.cli("validate", project, expected=2)

    def server(self, handler=normalize_text, limit=256):
        server = LocalToolServer(("127.0.0.1", 0), [Tool("normalize_text", handler)], operation_limit=limit)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def close():
            server.shutdown(); server.server_close(); thread.join(timeout=3)
        self.addCleanup(close)
        return server

    def request(self, server, operation_id="op-1", arguments=None, method="POST"):
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        try:
            if method == "GET":
                conn.request("GET", "/operations/" + operation_id)
            else:
                body = json.dumps({"operationId": operation_id, "tool": "normalize_text", "arguments": arguments or {"text": " a   b "}})
                conn.request("POST", "/tools", body=body.encode(), headers={"Content-Type": "application/json"})
            response = conn.getresponse()
            return response.status, json.loads(response.read())
        finally:
            conn.close()

    def test_actual_cli_java_client_to_python_server_and_exact_approval(self):
        server = self.server()
        project = self.project("http", f"http://127.0.0.1:{server.server_port}/tools")
        arguments = project / "arguments.json"
        review = self.cli("review-debug", project, "--tool", "normalize_text", "--arguments", arguments, "--operation-id", "developer-op")
        self.assertTrue(review["mayHaveSideEffects"])
        self.cli("debug", project, "--tool", "normalize_text", "--arguments", arguments, "--operation-id", "developer-op", "--approve", "wrong", expected=2)
        self.assertEqual(404, self.request(server, "developer-op", method="GET")[0])
        result = self.cli("debug", project, "--tool", "normalize_text", "--arguments", arguments, "--operation-id", "developer-op", "--approve", review["reviewDigest"])
        self.assertEqual("returned", result["status"])
        self.assertEqual("real local text", result["output"]["output"]["text"])
        self.assertFalse(result["replayAllowed"])
        self.assertEqual("returned", self.request(server, "developer-op", method="GET")[1]["status"])

    def test_server_once_receipts_conflicts_and_capacity(self):
        calls = []
        def handler(arguments):
            calls.append(arguments)
            return normalize_text(arguments)
        server = self.server(handler, limit=1)
        self.assertEqual("returned", self.request(server)[1]["status"])
        self.assertEqual("returned", self.request(server)[1]["status"])
        self.assertEqual(1, len(calls))
        self.assertEqual(409, self.request(server, arguments={"text": "changed"})[0])
        self.assertEqual(503, self.request(server, "another")[0])
        self.assertEqual(1, len(calls))

    def test_handler_failure_and_oversize_are_unknown_never_replayed(self):
        for response in ("failure", "oversize"):
            calls = []
            def handler(_):
                calls.append(1)
                if response == "failure":
                    raise RuntimeError("effect may already exist")
                return {"huge": "x" * 262145}
            server = self.server(handler)
            self.assertEqual("unknown", self.request(server)[1]["status"])
            self.assertEqual("unknown", self.request(server)[1]["status"])
            self.assertEqual(1, len(calls))

    def test_server_rejects_non_loopback_duplicate_keys_and_extra_fields(self):
        with self.assertRaises(ValueError):
            LocalToolServer(("0.0.0.0", 0), [Tool("normalize_text", normalize_text)])
        server = self.server()
        for body in ('{"operationId":"a","operationId":"b"}',
                     '{"operationId":"x","tool":"normalize_text","arguments":{},"token":"secret"}'):
            conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
            try:
                conn.request("POST", "/tools", body=body, headers={"Content-Type": "application/json"})
                response = conn.getresponse(); response.read()
                self.assertEqual(400, response.status)
            finally:
                conn.close()

    def test_multiple_example_tools_compute_real_values(self):
        self.assertEqual({"count": 4}, word_count({"text": " actual  text\nwith words "}))
        self.assertEqual({"text": "actual text"}, normalize_text({"text": " actual  text "}))
        with self.assertRaises(ValueError):
            word_count({"text": "text", "extra": True})

    def test_server_rejects_browser_origin_before_dispatch(self):
        server = self.server()
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
        try:
            conn.request("POST", "/tools", body=b"{}", headers={"Content-Type": "application/json", "Origin": "https://example.test"})
            response = conn.getresponse(); response.read()
            self.assertEqual(403, response.status)
            self.assertEqual({}, server.operations)
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
