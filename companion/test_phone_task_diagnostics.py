"""Disclosure/identity tests using original production collector, CLI and real HTTP Client."""
import contextlib
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import device_protocol as wire
from phone_task_client import Client, ClientError
from phone_task_diagnostics import MAX_PAGES, collect, preview_bytes, save_reviewed
from phone_tasks import SelectedPhoneClient, main

PEER = "pw1_" + "a" * 64
OPERATION = "original-operation"
IDENTITY = {"executorDeviceId": PEER, "instanceId": "original-instance", "taskId": "original-task", "runId": "original-run", "executorEpoch": 2}
SECRET = "known-secret-not-allowed-in-any-export-1234567890"


def snapshot(state="succeeded", cursor=2, **changes):
    task = {"protocolVersion": wire.VERSION, "protocolProfile": wire.PROFILE, "messageType": "TaskSnapshot",
            **IDENTITY, "revision": 9, "eventCursor": str(cursor), "updatedAt": 100, "state": state,
            "result": {"summary": SECRET, "goal": SECRET, "imageDataUrl": SECRET, "evidence": {"password": SECRET}}}
    task.update(changes)
    return {"operationId": OPERATION, "peerDeviceId": PEER, "status": state, "task": task}


def event(sequence, **data):
    return {"protocolVersion": wire.VERSION, "protocolProfile": wire.PROFILE, "messageType": "TaskEvent",
            **{key: IDENTITY[key] for key in ("executorDeviceId", "instanceId", "taskId")},
            "sequence": sequence, "cursor": str(sequence), "kind": "tool", "createdAt": sequence,
            "data": {"runId": IDENTITY["runId"], "coordinatorEpoch": IDENTITY["executorEpoch"], "type": "tool",
                     "tool": "phone_click_node", "ok": False, "status": "unknown", "code": "timeout", "stdout": SECRET,
                     "message": SECRET, "arguments": {"url": "https://user:" + SECRET + "@example.invalid/", "headers": {"Authorization": SECRET}},
                     "receipt": {"requiresReconciliation": True, "processExited": False, "message": SECRET, "nested": {"token": SECRET}}, **data}}


class OriginalReads:
    def __init__(self, first=None, last=None, pages=None):
        self.first = snapshot() if first is None else first
        self.last = copy.deepcopy(self.first) if last is None else last
        self.pages = pages or [{"events": [event(1), event(2)], "nextCursor": "2", "hasMore": False, "snapshotRequired": False}]
        self.calls = []
        self.status_count = 0
        self.page_count = 0

    def execute(self, action, params):
        self.calls.append((action, copy.deepcopy(params)))
        if action == "status":
            self.status_count += 1
            value = self.first if self.status_count == 1 else self.last
        elif action == "events":
            value = self.pages[min(self.page_count, len(self.pages) - 1)]
            self.page_count += 1
        else:
            raise AssertionError("Diagnostic attempted a mutation")
        if isinstance(value, Exception): raise value
        return copy.deepcopy(value)

    def redact_diagnostic_text(self, text):
        return text.replace(SECRET, "[redacted-known-secret]")


class DiagnosticProjectionTest(unittest.TestCase):
    def test_fixed_original_run_only_literal_metadata_and_no_text_leak(self):
        client = OriginalReads()
        report = collect(client, PEER, OPERATION)
        text = preview_bytes(report).decode()
        self.assertEqual(IDENTITY, report["identity"])
        self.assertFalse(report["partial"])
        self.assertEqual("unknown", report["events"][0]["eventResult"]["status"])
        self.assertTrue(report["events"][0]["receipt"]["requiresReconciliation"])
        self.assertNotIn(SECRET, text)
        self.assertNotIn("Authorization", text)
        self.assertNotIn("https://", text)
        self.assertFalse(report["businessOutcomeAssessed"])
        self.assertFalse(report["uploaded"])
        self.assertEqual(["status", "events", "status"], [action for action, _ in client.calls])

    def test_active_or_changed_run_is_partial_never_joined_to_new_run(self):
        for last in (snapshot("running"), snapshot(runId="another-run", executorEpoch=3)):
            report = collect(OriginalReads(first=snapshot("running"), last=last), PEER, OPERATION)
            self.assertTrue(report["partial"])
            self.assertEqual("original-run", report["identity"]["runId"])
        self.assertIn("original_run_changed_during_capture", report["gaps"])
        self.assertEqual("unavailable", report["endState"])

    def test_prior_runs_missing_attribution_and_arbitrary_types_cannot_be_current_results(self):
        unknown = event(2); unknown["data"].pop("runId"); unknown["data"].pop("coordinatorEpoch")
        wrong_type = event(3, status={"text": SECRET}, code={"message": SECRET}, ok="true")
        wrong_type["kind"] = {"private": SECRET}
        page = {"events": [event(1, runId="older-run"), unknown, wrong_type], "nextCursor": "3", "hasMore": False, "snapshotRequired": False}
        report = collect(OriginalReads(first=snapshot(cursor=3), pages=[page]), PEER, OPERATION)
        self.assertEqual(1, report["otherRunEventsOmitted"])
        self.assertEqual("not_recorded", report["events"][0]["runAttribution"])
        self.assertNotIn("eventResult", report["events"][0])
        self.assertNotIn("status", report["events"][1]["eventResult"])
        self.assertTrue(report["partial"])
        self.assertNotIn(SECRET, preview_bytes(report).decode())

    def test_not_found_not_authorized_and_unconfirmed_are_distinct_and_do_not_read_events(self):
        for failure, outcome in ((ClientError("hidden", 404), "not_found"), (ClientError("hidden", 403), "not_authorized"),
                                 (ClientError("hidden", 503), "source_unavailable")):
            client = OriginalReads(first=failure)
            report = collect(client, PEER, OPERATION)
            self.assertEqual(outcome, report["outcome"])
            self.assertTrue(report["partial"])
            self.assertEqual(1, len(client.calls))
        client = OriginalReads(first={"operationId": OPERATION, "currentRead": "unavailable", "task": snapshot()["task"]})
        self.assertNotIn("identity", collect(client, PEER, OPERATION))
        self.assertEqual(1, len(client.calls))

    def test_foreign_task_and_nonadvancing_cursor_never_create_mixed_timeline(self):
        foreign = event(1); foreign["taskId"] = "other-task"
        for page in ({"events": [foreign], "nextCursor": "1", "hasMore": False, "snapshotRequired": False},
                     {"events": [], "nextCursor": "0", "hasMore": True, "snapshotRequired": False}):
            report = collect(OriginalReads(pages=[page]), PEER, OPERATION)
            self.assertTrue(report["partial"])
            self.assertEqual([], report["events"])
            self.assertIn("events_source_unavailable", report["gaps"])

    def test_fixed_capture_boundary_and_page_budget_are_explicit(self):
        pages = [{"events": [event(i)], "nextCursor": str(i), "hasMore": True, "snapshotRequired": False} for i in range(1, MAX_PAGES + 1)]
        client = OriginalReads(first=snapshot(cursor=20), pages=pages)
        report = collect(client, PEER, OPERATION)
        self.assertEqual(MAX_PAGES + 2, len(client.calls))
        self.assertIn("event_page_limit", report["gaps"])
        page = {"events": [event(1), event(2), event(3)], "nextCursor": "3", "hasMore": True, "snapshotRequired": False}
        report = collect(OriginalReads(pages=[page], last=snapshot(cursor=3, revision=10)), PEER, OPERATION)
        self.assertEqual([1, 2], [row["sequence"] for row in report["events"]])
        self.assertIn("events_added_after_capture_boundary", report["gaps"])

    def test_export_requires_exact_preview_and_never_overwrites_existing_or_symlink(self):
        raw = preview_bytes(collect(OriginalReads(), PEER, OPERATION))
        digest = hashlib.sha256(raw).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "report.json"
            with self.assertRaises(ClientError): save_reviewed(raw, target, "0" * 64)
            self.assertFalse(target.exists())
            save_reviewed(raw, target, digest)
            self.assertEqual(raw, target.read_bytes())
            with self.assertRaises(FileExistsError): save_reviewed(raw, target, digest)
            self.assertEqual(raw, target.read_bytes())
            link = Path(directory) / "link.json"; link.symlink_to(target)
            with self.assertRaises(FileExistsError): save_reviewed(raw, link, digest)
            self.assertEqual(raw, target.read_bytes())

    def test_cli_previews_before_refusing_unreviewed_noninteractive_destination(self):
        client = OriginalReads()
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "report.json"
            output, errors = io.StringIO(), io.StringIO()
            with patch("phone_tasks.Client", return_value=client), patch("sys.stdin", io.StringIO()), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                code = main(["--endpoint", "http://127.0.0.1:1", "--token-file", "not-read-by-fixture", "--phone", PEER,
                             "diagnostic-export", OPERATION, "--output", str(target)])
            self.assertEqual(2, code)
            self.assertEqual(1, json.loads(output.getvalue())["schemaVersion"])
            self.assertIn(str(target), errors.getvalue())
            self.assertFalse(target.exists())


class DiagnosticRealClientTest(unittest.TestCase):
    def test_actual_http_client_original_read_requests_and_loaded_secret_redaction(self):
        source = OriginalReads()
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                if self.path != "/v1/phone-tasks" or self.headers.get("Authorization") != "Bearer " + SECRET:
                    self.send_response(403); self.end_headers(); return
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(request["action"])
                value = source.execute(request["action"], {**request["params"], "operationId": OPERATION})
                if request["action"] == "status": value["operationId"] = SECRET
                raw = json.dumps({"ok": True, "result": value}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(raw))); self.end_headers(); self.wfile.write(raw)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                token = Path(directory) / "token"; token.write_text(SECRET); token.chmod(0o600)
                client = SelectedPhoneClient(Client("http://127.0.0.1:" + str(server.server_address[1]), token), PEER)
                report = collect(client, PEER, SECRET)
                self.assertEqual("[redacted-known-secret]", report["operationId"])
                self.assertNotIn(SECRET, preview_bytes(report).decode())
                self.assertEqual(["status", "events", "status"], calls)
        finally:
            server.shutdown(); thread.join(2); server.server_close()


if __name__ == "__main__":
    unittest.main()
