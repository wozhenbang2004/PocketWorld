"""Actual loopback Companion/jobs/files; coding CLI/account/device behavior is fixture-only."""
import base64
import os
import json
from pathlib import Path
from urllib.parse import quote
import unittest
from unittest.mock import patch
from test_result_snapshots import ResultSnapshotTests


class ResultFileTests(unittest.TestCase):
    def setUp(self):
        self.original = ResultSnapshotTests()
        self.original.setUp()
        self.f = self.original.f

    def tearDown(self):
        self.original.tearDown()
        proof = {"test": self.id(), "listenerClosed": self.f.server.socket.fileno() == -1,
                 "listenerThreadAlive": self.f.thread.is_alive(), "activeProcesses": len(self.f.manager.processes)}
        if os.environ.get("C3_EVIDENCE_DIR"):
            with (Path(os.environ["C3_EVIDENCE_DIR"]) / "resource-releases.jsonl").open("a") as output:
                output.write(json.dumps(proof) + "\n")
        self.assertTrue(proof["listenerClosed"])
        self.assertFalse(proof["listenerThreadAlive"])
        self.assertEqual(0, proof["activeProcesses"])

    def browse(self, job, path="", offset=0, version=""):
        return self.f.request("/v1/jobs/" + job["id"] + "/result-files?path=" + quote(path) + "&offset=" + str(offset) + ("&directoryVersion=" + version if version else ""))

    def reviewed(self, job, paths):
        result = []
        for path in paths:
            status, actual = self.f.request("/v1/jobs/" + job["id"] + "/artifact?path=" + quote(path))
            self.assertEqual(200, status, actual)
            result.append({key: actual["result"][key] for key in ("path", "bytes", "sha256", "fileVersion")})
        return result

    def capture(self, job, paths, expected, operation="selected-file-version"):
        return self.f.request("/v1/jobs/" + job["id"] + "/snapshots", {
            "operationId": operation, "jobOperationId": job["operationId"],
            "environmentId": job["environmentId"], "worker": job["worker"],
            "paths": paths, "expectedFiles": expected})

    def test_directory_is_metadata_only_original_job_bound_and_hides_private_runtime(self):
        job = self.original.source()
        (self.f.workspace / ".env").write_text("SHOULD NEVER BE READ")
        (self.f.workspace / ".pocketworld-inputs").mkdir()
        (self.f.workspace / "subdirectory").mkdir()
        with patch.object(self.f.manager, "_artifact_bytes", wraps=self.f.manager._artifact_bytes) as content:
            status, response = self.browse(job)
        self.assertEqual(200, status, response)
        actual = response["result"]
        self.assertEqual(job["operationId"], actual["jobOperationId"])
        self.assertEqual(job["workspaceIdentity"], actual["workspaceIdentity"])
        self.assertEqual(0, content.call_count)
        self.assertFalse(actual["recursive"])
        self.assertFalse(actual["fileContentsRead"])
        self.assertFalse(actual["authorshipVerified"])
        self.assertFalse(actual["executionAuthorized"])
        self.assertNotIn(".env", str(actual))
        self.assertNotIn(".pocketworld-inputs", str(actual))
        self.assertNotIn("ORIGINAL RESULT BYTES", str(actual))
        self.assertTrue(next(x for x in actual["files"] if x["path"] == "proof.txt")["selectable"])

    def test_reviewed_binary_freezes_exact_original_bytes_and_lost_ack_never_recaptures(self):
        job = self.original.source()
        path = "真实结果.bin"
        raw = bytes(range(256)) * 2 + b"\x00"
        (self.f.workspace / path).write_bytes(raw)
        expected = self.reviewed(job, [path])
        status, captured = self.capture(job, [path], expected)
        self.assertEqual(200, status, captured)
        snapshot = captured["result"]
        self.assertEqual(expected, [{key: value[key] for key in ("path", "bytes", "sha256", "fileVersion")} for value in snapshot["files"]])
        self.assertEqual(path, snapshot["files"][0]["name"])
        self.assertNotIn("base64", snapshot["files"][0])
        (self.f.workspace / path).write_bytes(b"CHANGED AFTER CAPTURE")
        _, repeated = self.capture(job, [path], expected)
        self.assertEqual(snapshot["snapshotVersion"], repeated["result"]["snapshotVersion"])
        status, original = self.f.request("/v1/jobs/" + job["id"] + "/snapshots/" + snapshot["snapshotId"] + "?version=" + snapshot["snapshotVersion"] + "&path=" + quote(path))
        self.assertEqual(200, status, original)
        self.assertEqual(raw, base64.b64decode(original["result"]["base64"]))
        self.assertEqual(1, len(self.f.manager.jobs[job["id"]]["resultSnapshots"]))

    def test_changed_content_or_same_bytes_replaced_identity_rejects_reviewed_baseline(self):
        job = self.original.source()
        expected = self.reviewed(job, ["proof.txt"])
        (self.f.workspace / "proof.txt").write_text("ACTUAL LATER CONTENT")
        self.assertEqual(409, self.capture(job, ["proof.txt"], expected)[0])
        expected = self.reviewed(job, ["proof.txt"])
        replacement = self.f.workspace / "replacement"
        replacement.write_text("ACTUAL LATER CONTENT")
        os.replace(replacement, self.f.workspace / "proof.txt")
        self.assertEqual(409, self.capture(job, ["proof.txt"], expected, "other-old-baseline")[0])
        self.assertEqual([], self.f.manager.jobs[job["id"]].get("resultSnapshots", []))
        self.assertEqual(409, self.capture(job, ["proof.txt"], None, "null-reviewed-baseline")[0])

    def test_page_is_version_pinned_and_last_allowed_page_never_crosses_inspection_cap(self):
        job = self.original.source()
        for index in range(10001):
            (self.f.workspace / ("item-" + str(index))).touch()
        status, first = self.browse(job)
        self.assertEqual(200, status, first)
        page = first["result"]
        self.assertEqual(100, page["examinedEntries"])
        self.assertFalse(page["listingComplete"])
        self.assertEqual(409, self.browse(job, offset=100)[0])
        status, last = self.browse(job, offset=9999, version=page["directoryVersion"])
        self.assertEqual(200, status, last)
        self.assertEqual(1, last["result"]["examinedEntries"])
        self.assertTrue(last["result"]["inspectionLimitReached"])
        self.assertIsNone(last["result"]["nextOffset"])
        self.assertFalse(last["result"]["listingComplete"])
        self.assertEqual(413, self.browse(job, offset=10000, version=page["directoryVersion"])[0])
        (self.f.workspace / "ACTUAL NEW ENTRY").touch()
        self.assertEqual(409, self.browse(job, offset=100, version=page["directoryVersion"])[0])

    def test_symlink_hardlink_oversize_and_private_paths_are_not_selectable(self):
        job = self.original.source()
        (self.f.workspace / "symbolic").symlink_to(self.f.workspace / "proof.txt")
        outside = self.f.root / "only-fixture-outside"
        outside.write_text("DO NOT IMPORT VIA HARD LINK")
        os.link(outside, self.f.workspace / "hardlinked")
        (self.f.workspace / "large.bin").write_bytes(b"x" * 262145)
        status, response = self.browse(job)
        self.assertEqual(200, status, response)
        files = {value["path"]: value for value in response["result"]["files"]}
        for path in ("symbolic", "hardlinked", "large.bin"):
            self.assertFalse(files[path]["selectable"])
        self.assertEqual(403, self.browse(job, "symbolic")[0])
        self.assertEqual(403, self.browse(job, "../outside")[0])
        self.assertEqual(403, self.browse(job, ".pocketworld-inputs")[0])

    def test_active_job_and_replaced_physical_workspace_cannot_borrow_original_identity(self):
        job = self.original.source()
        self.f.manager.jobs[job["id"]]["status"] = "running"
        self.assertEqual(409, self.browse(job)[0])
        self.f.manager.jobs[job["id"]]["status"] = "completed"
        self.f.workspace.rename(self.f.root / "previous-workspace")
        self.f.workspace.mkdir()
        (self.f.workspace / "proof.txt").write_text("DIFFERENT PHYSICAL WORKSPACE")
        self.assertEqual(409, self.browse(job)[0])

    def test_reviewed_snapshot_continues_original_codex_session_only_at_same_version(self):
        job, launch = self.original.codex()
        expected = self.reviewed(job, ["proof.txt"])
        status, captured = self.capture(job, ["proof.txt"], expected)
        self.assertEqual(200, status, captured)
        snapshot = captured["result"]
        with patch.object(self.f.manager, "_launch", return_value=launch):
            status, response = self.f.request("/v1/jobs", self.original.continuation(job, snapshot))
        self.assertEqual(200, status, response)
        actual = self.f.finish(response["result"]["id"])
        self.assertEqual("completed", actual["status"])
        self.assertTrue(actual["snapshotPrelaunchVerified"])
        self.assertEqual(job["id"], actual["resumeJobId"])
        self.assertEqual(snapshot["snapshotVersion"], actual["resultSnapshotVersion"])


if __name__ == "__main__":
    unittest.main()
