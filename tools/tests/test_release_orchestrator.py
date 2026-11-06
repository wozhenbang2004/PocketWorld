"""Offline release regressions; no APK build, credentials or external HTTP.

Every artifact/manifest/provider reply here is synthetic. Local Git fixtures only
fetch from another directory in the same E-drive temporary tree.
"""
from __future__ import annotations

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import socketserver
import subprocess
import tempfile
import threading
import types
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release_orchestrator_under_test", ROOT / "tools/release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)
HEAD = "a" * 40
BASE_SHA = "b" * 64
SYNTHETIC_TOKEN = "synthetic_publisher_credential_" + "x" * 40


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Response(io.BytesIO):
    def __init__(self, data=b"", status=200, headers=None):
        super().__init__(data)
        self.status = status
        self.headers = headers or {}


class PublisherFixture:
    """In-memory publisher protocol, including a reply lost after activation."""

    def __init__(self, config, bundle, *, fault=None, lost_reply=False, activate_applied=True):
        self.origin = config["origin"]
        self.manifest_path = config["manifest_path"]
        self.pin = config["public_pin_sha256"]
        self.token = SYNTHETIC_TOKEN
        self.raw = (bundle / "release.json").read_bytes()
        self.release_id = sha(self.raw)
        self.prefix = "/__publisher/releases/" + self.release_id
        self.artifacts = {"/" + entry["file"]: (bundle / entry["file"]).read_bytes()
                          for entry in RELEASE.read_json(bundle / "release-plan.json")["artifactUploads"]}
        self.events = []
        self.registered_sources = []
        self.ready = set()
        self.fault = fault
        self.lost_reply = lost_reply
        self.activate_applied = activate_applied
        self.active = False

    def json(self, path, method="GET", data=None):
        self.events.append((method, path))
        if path == "/__publisher/releases" and method == "POST":
            self.registered_sources.append(data["sourceCommit"])
            if data["expectedCurrentManifestSha256"] != BASE_SHA:
                raise AssertionError("Wrong baseline CAS")
            self.descriptors = data["artifacts"]
            return {"releaseId": self.release_id, "manifestSha256": self.release_id}
        if path == self.prefix and method == "GET":
            return {"artifacts": [{"sha256": row["sha256"], "ready": row["sha256"] in self.ready,
                                   "missingParts": list(range(len(row["parts"]))) if row["sha256"] not in self.ready else []}
                                  for row in self.descriptors]}
        if path.startswith(self.prefix + "/blobs/") and path.endswith("/complete"):
            self.ready.add(path.split("/")[-2])
            return {"ready": True}
        if path == self.prefix + "/activate":
            self.active = self.activate_applied
            if self.lost_reply:
                raise RELEASE.ReleaseError("Synthetic lost activation reply")
            return {"active": self.active}
        if path == "/__publisher/status":
            return {"activeReleaseId": self.release_id if self.active else "older-release",
                    "current": {"manifestSha256": self.release_id if self.active else BASE_SHA},
                    "pinSpkiSha256": self.pin, "partBytes": RELEASE.PART_BYTES,
                    "digestStreamAvailable": True}
        raise AssertionError("Unexpected fixture operation: " + method + " " + path)

    def request(self, path, method="GET", data=None, auth=False, headers=None):
        kind = "RANGE" if headers and "Range" in headers else method
        self.events.append((kind, path))
        if method == "PUT":
            if not auth or not path.startswith(self.prefix + "/blobs/"):
                raise AssertionError("Unexpected upload target")
            return Response()
        if path == self.manifest_path:
            if not self.active:
                raise AssertionError("Manifest requested before activation")
            return Response(self.raw)
        if path not in self.artifacts:
            raise AssertionError("Unexpected public artifact")
        body = self.artifacts[path]
        if kind == "RANGE":
            size = min(64, len(body))
            if self.fault == "range-status":
                return Response(body[:size], status=200)
            content_range = "bytes 0-" + str(size - 1) + "/" + str(len(body))
            if self.fault == "range-header":
                content_range = "bytes 0-" + str(size - 1) + "/999999"
            selected = body[:size] if self.fault != "range-bytes" else b"!" * size
            return Response(selected, status=206, headers={"Content-Range": content_range})
        if self.fault == "full-sha":
            body = b"!" * len(body)
        return Response(body)


class ReleaseOrchestratorTest(unittest.TestCase):
    def test_node_fetch_uses_configured_proxy_without_external_network(self):
        seen = []
        Path(self.c["repo"]).mkdir()
        self.c["node"] = shutil.which("node")
        self.assertTrue(self.c["node"], "An existing Node runtime is required")

        class Proxy(socketserver.StreamRequestHandler):
            def handle(self):
                seen.append(self.rfile.readline().decode("ascii").strip())
                self.wfile.write(b"HTTP/1.1 502 Synthetic proxy response\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Proxy) as proxy:
            worker = threading.Thread(target=proxy.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
            worker.start()
            try:
                self.c["proxy"] = "http://127.0.0.1:" + str(proxy.server_address[1])
                with mock.patch.dict(os.environ, {"NODE_USE_ENV_PROXY": ""}):
                    runner = self.runner()
                code, _ = runner.command([self.c["node"], "-e",
                    "fetch('https://font-release.invalid/font.ttf',{signal:AbortSignal.timeout(2500)}).catch(()=>process.exit(0));"], capture=True)
                self.assertEqual(0, code)
                self.assertEqual(["CONNECT font-release.invalid:443 HTTP/1.1"], seen)
            finally:
                proxy.shutdown()
                worker.join(2)

    def setUp(self):
        parent = Path(os.environ.get("POCKETWORLD_RELEASE_TEST_TEMP", "E:/PocketWorld-dev/temp/release-orchestrator-tests"))
        if not parent.is_absolute() or parent.drive.lower() == "c:":
            raise AssertionError("Release test temporary files must stay off C drive")
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="case-", dir=parent)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.environment = mock.patch.dict(os.environ, {key: str(self.root) for key in ("TEMP", "TMP", "TMPDIR")})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.c = RELEASE.read_json(ROOT / "tools/release.config.example.json")
        self.c.update(repo=str(self.root / "repo"), work_root=str(self.root / "runs"),
                      artifact_root=str(self.root / "apks"), cache_root=str(self.root / "cache"),
                      core_acceptance_file=str(self.root / "functional-results.json"), proxy="", aria2=None)
        for key in ("work_root", "artifact_root", "cache_root"):
            Path(self.c[key]).mkdir()

    def runner(self):
        directory = self.root / "run"
        directory.mkdir(exist_ok=True)
        return RELEASE.Runner(self.c, directory)

    def git(self, directory, *arguments):
        executable = shutil.which("git")
        if not executable:
            self.fail("Existing Git executable is required for local preservation fixtures")
        result = subprocess.run([executable, *arguments], cwd=directory, text=True, encoding="utf-8",
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        return result.stdout.strip()

    def git_repo(self, directory):
        directory.mkdir()
        self.git(directory, "init", "--initial-branch=main")
        self.git(directory, "config", "user.email", "local-release-test@example.invalid")
        self.git(directory, "config", "user.name", "Local release fixture")
        (directory / "keep.txt").write_text("base\n", encoding="utf-8")
        self.git(directory, "add", "keep.txt")
        self.git(directory, "commit", "-m", "Synthetic local base")

    def bundle(self):
        bundle = Path(self.c["work_root"]) / "prepared" / "bundle"
        bundle.mkdir(parents=True)
        rows = []
        for suffix, body, content_type in (("app-255.apk", b"synthetic APK bytes\n" * 9, "application/vnd.android.package-archive"),
                                            ("from-253-to-255.patch.gz", b"synthetic PATCH bytes\n" * 5, "application/gzip")):
            relative = "artifacts/" + sha(body) + "/" + suffix
            target = bundle / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(body)
            rows.append({"file": relative, "url": self.c["origin"] + "/" + relative, "bytes": len(body),
                         "sha256": sha(body), "contentType": content_type})
        RELEASE.write_json(bundle / "release-plan.json", {"artifactUploads": rows})
        (bundle / "release.json").write_bytes(b'{"payload":"synthetic-test-only","signature":"synthetic-test-only"}\n')
        return bundle

    def verify_bundle_tool(self, runner, name, *args):
        self.assertNotIn(SYNTHETIC_TOKEN, str(args))
        if name == "verify-envelope":
            bundle = Path(args[args.index("--envelope") + 1]).parent
            row = next(item for item in RELEASE.read_json(bundle / "release-plan.json")["artifactUploads"]
                       if item["contentType"] == "application/vnd.android.package-archive")
            RELEASE.write_json(args[args.index("--output") + 1], {
                "versionCode": 255, "signatureVerified": True, "pinSpkiSha256": self.c["public_pin_sha256"],
                "apk": {key: row[key] for key in ("url", "bytes", "sha256")}})
        elif name == "verify-apk":
            self.assertEqual(self.c["apk_signer_sha256"], args[args.index("--expected-signer") + 1])
            self.assertTrue(Path(args[args.index("--apk") + 1]).is_file())
        else:
            self.fail("Unexpected native bundle check: " + name)

    def test_dirty_source_keeps_staged_unstaged_and_untracked_work_without_build(self):
        repo = Path(self.c["repo"])
        self.git_repo(repo)
        (repo / "keep.txt").write_text("staged user work\n", encoding="utf-8")
        self.git(repo, "add", "keep.txt")
        (repo / "keep.txt").write_text("additional unstaged user work\n", encoding="utf-8")
        (repo / "untracked.txt").write_text("private local draft\n", encoding="utf-8")
        before = self.git(repo, "status", "--porcelain=v1"), self.git(repo, "show", ":keep.txt"), (repo / "keep.txt").read_bytes()
        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE.Runner, "build") as build, \
                mock.patch.object(RELEASE, "baseline") as baseline:
            with self.assertRaises(RELEASE.ReleaseError):
                RELEASE.run_release(self.c)
        self.assertEqual(before, (self.git(repo, "status", "--porcelain=v1"), self.git(repo, "show", ":keep.txt"), (repo / "keep.txt").read_bytes()))
        self.assertEqual("private local draft\n", (repo / "untracked.txt").read_text(encoding="utf-8"))
        build.assert_not_called()
        baseline.assert_not_called()

    def test_real_local_merge_conflict_preserves_both_commits_and_conflict_without_build(self):
        upstream, repo = self.root / "upstream", Path(self.c["repo"])
        self.git_repo(upstream)
        self.git(self.root, "clone", str(upstream), str(repo))
        self.git(repo, "config", "user.email", "local-release-test@example.invalid")
        self.git(repo, "config", "user.name", "Local release fixture")
        (repo / "keep.txt").write_text("local user fix\n", encoding="utf-8")
        self.git(repo, "commit", "-am", "Preserved local fix")
        local_head = self.git(repo, "rev-parse", "HEAD")
        (upstream / "keep.txt").write_text("upstream main fix\n", encoding="utf-8")
        self.git(upstream, "commit", "-am", "Upstream fix")
        upstream_head = self.git(upstream, "rev-parse", "HEAD")
        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE.Runner, "build") as build, \
                mock.patch.object(RELEASE, "baseline") as baseline:
            with self.assertRaises(RELEASE.ReleaseError):
                RELEASE.run_release(self.c)
        self.assertEqual(local_head, self.git(repo, "rev-parse", "HEAD"))
        self.assertEqual(upstream_head, self.git(repo, "rev-parse", "MERGE_HEAD"))
        conflict = (repo / "keep.txt").read_text(encoding="utf-8")
        self.assertIn("local user fix", conflict)
        self.assertIn("upstream main fix", conflict)
        build.assert_not_called()
        baseline.assert_not_called()

    def test_version_uses_actual_apks_and_online_max_not_stale_latest_json_or_filenames(self):
        artifact = Path(self.c["artifact_root"]) / "PocketWorld-v999999-misleading-name.apk"
        artifact.write_bytes(b"synthetic APK")
        built = Path(self.c["repo"]) / "app/build/outputs/apk/debug/app-debug.apk"
        built.parent.mkdir(parents=True)
        built.write_bytes(b"newer synthetic APK")
        RELEASE.write_json(Path(self.c["artifact_root"]) / "PocketWorld-latest-build.json", {"versionCode": 999999})
        versions = {artifact: 254, built: 259}
        with mock.patch.object(RELEASE, "package_info", side_effect=lambda runner, path: {"packageName": "dev.pocketengine", "versionCode": versions[path]}) as inspect:
            self.assertEqual(260, RELEASE.next_version(self.runner(), 256))
            self.assertEqual(301, RELEASE.next_version(self.runner(), 300))
        self.assertEqual({artifact, built}, {call.args[1] for call in inspect.call_args_list})

    def test_activation_acceptance_rejects_incomplete_or_changed_production_inputs(self):
        for overall, agent in (("blocked", "failed"), ("passed", "pending")):
            with self.subTest(overall=overall, agent=agent):
                cases = {case: {"status": "passed"} for case in RELEASE.CORE_CASES}
                cases["agent_execution"] = {"status": agent}
                RELEASE.write_json(self.c["core_acceptance_file"], {"releaseAcceptance": {"status": overall, "sourceCommit": HEAD, "cases": cases}})
                with self.assertRaises(RELEASE.ReleaseError):
                    RELEASE.core_acceptance(self.runner(), HEAD)

        # This directory is registered as a production source set in app/build.gradle.
        # An accepted ancestor must not authorize a changed pendingAdb implementation.
        repo = Path(self.c["repo"])
        self.git_repo(repo)
        accepted = self.git(repo, "rev-parse", "HEAD")
        source = repo / "app/src/pendingAdb/java/SyntheticProduction.java"
        source.parent.mkdir(parents=True)
        source.write_text("class SyntheticProduction {}\n", encoding="utf-8")
        self.git(repo, "add", "app/src/pendingAdb/java/SyntheticProduction.java")
        self.git(repo, "commit", "-m", "Changed production source registered by AGP")
        current = self.git(repo, "rev-parse", "HEAD")
        RELEASE.write_json(self.c["core_acceptance_file"], {"releaseAcceptance": {
            "status": "passed", "sourceCommit": accepted,
            "cases": {case: {"status": "passed"} for case in RELEASE.CORE_CASES}}})
        with self.assertRaises(RELEASE.ReleaseError):
            RELEASE.core_acceptance(self.runner(), current)

    def test_unaccepted_candidate_stages_once_then_activates_same_bundle_after_acceptance(self):
        bundle = self.bundle()
        original_manifest = (bundle / "release.json").read_bytes()
        original_artifacts = {row["file"]: row["sha256"]
                              for row in RELEASE.read_json(bundle / "release-plan.json")["artifactUploads"]}
        http = PublisherFixture(self.c, bundle)
        previous = Path(self.c["work_root"]) / "last-run.json"
        accepted = False

        def acceptance(_runner, head):
            self.assertEqual(HEAD, head)
            if not accepted:
                raise RELEASE.ReleaseError("Synthetic core batch still awaiting acceptance")
            return {"status": "passed", "sourceCommit": head}

        def verify(_runner, name, *args):
            self.verify_bundle_tool(_runner, name, *args)

        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE, "sync_source", return_value=HEAD), \
                mock.patch.object(RELEASE, "source_state", return_value=HEAD), \
                mock.patch.object(RELEASE, "core_acceptance", side_effect=acceptance), \
                mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 253}, self.root / "base.apk", BASE_SHA)), \
                mock.patch.object(RELEASE, "Http", return_value=http), \
                mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                mock.patch.object(RELEASE.Runner, "tool", autospec=True, side_effect=verify), \
                mock.patch.object(RELEASE, "make_bundle", return_value=bundle) as build, \
                mock.patch.object(RELEASE, "next_version", return_value=255) as version:
            staged = RELEASE.run_release(self.c)
            self.assertEqual("staged_awaiting_acceptance", staged["status"])
            self.assertIs(False, staged["activated"])
            self.assertFalse(http.active)
            self.assertFalse(any(path.endswith("/activate") for _, path in http.events))
            saved = RELEASE.read_json(previous)
            self.assertEqual("uploaded_waiting_acceptance", saved["stage"])
            self.assertEqual(BASE_SHA, saved["baselineManifestSha256"])
            self.assertEqual(sha(original_manifest), saved["manifestSha256"])
            for path in http.artifacts:
                self.assertIn(("GET", path), http.events)
                self.assertIn(("RANGE", path), http.events)
            self.assertEqual(len(http.artifacts), sum(method == "PUT" for method, _ in http.events))
            first_events = list(http.events)

            waiting = RELEASE.run_release(self.c)
            self.assertEqual("staged_awaiting_acceptance", waiting["status"])
            self.assertIs(False, waiting["activated"])
            self.assertTrue(waiting["reused"])
            # Baseline/status reads are permitted; none of the candidate upload or
            # public artifact operations should repeat while acceptance is pending.
            repeated_events = http.events[len(first_events):]
            self.assertTrue(all(method == "GET" and path == "/__publisher/status"
                                for method, path in repeated_events))
            self.assertEqual(1, build.call_count)
            self.assertEqual(1, version.call_count)
            self.assertEqual("uploaded_waiting_acceptance", RELEASE.read_json(previous)["stage"])

            accepted = True
            before_activation = len(http.events)
            published = RELEASE.run_release(self.c)
            self.assertEqual("published", published["status"])
            self.assertIs(True, published["activated"])
            self.assertTrue(http.active)
            self.assertEqual(1, build.call_count)
            self.assertEqual(1, version.call_count)
            final_events = http.events[before_activation:]
            self.assertFalse(any(method == "PUT" or path.endswith("/complete")
                                 for method, path in final_events))
            self.assertEqual(1, sum(path.endswith("/activate") for _, path in http.events))
            self.assertEqual("published", RELEASE.read_json(previous)["stage"])

        self.assertEqual(original_manifest, (bundle / "release.json").read_bytes())
        for relative, expected in original_artifacts.items():
            self.assertEqual(expected, RELEASE.digest(bundle / relative))

    def test_junit_zero_or_skipped_bodies_cannot_be_reported_as_passed(self):
        report = self.root / "host"
        (report / "junit").mkdir(parents=True)
        xml = report / "junit/TEST-synthetic.xml"
        cases = (
            '<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
            '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase name="case"><skipped/></testcase></testsuite>',
            '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="case"><skipped/></testcase></testsuite>',
            '<testsuite tests="1" failures="0" errors="0" skipped="0"/>',
        )
        for value in cases:
            with self.subTest(xml=value):
                xml.write_text(value, encoding="utf-8")
                with self.assertRaises(RELEASE.ReleaseError):
                    RELEASE.check_host_xml(report)
        xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="Synthetic" name="case"/></testsuite>', encoding="utf-8")
        self.assertEqual(1, RELEASE.check_host_xml(report))

    def test_zero_or_skipped_web_tests_stop_before_apk_build(self):
        web = Path(self.c["repo"]) / "web-ui"
        (web / "node_modules").mkdir(parents=True)
        (web / "package-lock.json").write_text("{}", encoding="utf-8")
        (Path(self.c["cache_root"]) / "release-web-lock.sha256").write_text(RELEASE.digest(web / "package-lock.json"))
        for tests, skipped in ((0, 0), (3, 1)):
            with self.subTest(tests=tests, skipped=skipped):
                runner = self.runner()
                runner.command = mock.Mock(return_value=(0, f"# tests {tests}\n# fail 0\n# cancelled 0\n# skipped {skipped}\n"))
                runner.build = mock.Mock()
                with self.assertRaises(RELEASE.ReleaseError):
                    RELEASE.make_bundle(runner, HEAD, 255, self.root / "base.apk")
                runner.build.assert_not_called()
                self.assertEqual(1, runner.command.call_count)

    def test_python_gate_uses_final_summary_instead_of_negative_fixture_stdout(self):
        web = Path(self.c["repo"]) / "web-ui"
        (web / "node_modules").mkdir(parents=True)
        (web / "package-lock.json").write_text("{}", encoding="utf-8")
        (Path(self.c["cache_root"]) / "release-web-lock.sha256").write_text(RELEASE.digest(web / "package-lock.json"))
        RELEASE.write_json(Path(self.c["repo"]) / "app/src/main/assets/pocket-ui/build-identity.json", {"sourceRevision": HEAD})
        valid_web = "# tests 1\n# fail 0\n# cancelled 0\n# skipped 0\n"
        separator = "-" * 70 + "\n"
        outputs = (
            (True, "test_zero_or_skipped_web_tests_stop_before_apk_build ... # skipped 1\nok\n\n"
                   + separator + "Ran 91 tests in 10.776s\n\nOK"),
            (True, separator + "Ran 2 tests in 0.001s\n\nOK (skipped=1)\n"
                   + separator + "Ran 91 tests in 10.776s\n\nOK\n"),
            (True, "test_one ... ok\n" + separator.replace("\n", "\r\n") + "Ran 1 test in 0.001s\r\n\r\nOK\r\n"),
            (False, separator + "Ran 91 tests in 10.776s\n\nOK (skipped=1)"),
            (False, separator + "Ran 91 tests in 10.776s\n\nFAILED (failures=1)"),
            (False, separator + "Ran 0 tests in 0.000s\n\nOK"),
            (False, "test_one ... ok\n"),
            (False, separator + "Ran 91 tests in 10.776s\n"),
            (False, "OK"),
            (False, separator + "Ran 91 tests in 10.776s\n\nOK\nunconfirmed trailing output"),
        )

        class ReachedBuild(Exception):
            pass

        for allowed, output in outputs:
            with self.subTest(allowed=allowed, output=output):
                runner = self.runner()
                runner.command = mock.Mock(side_effect=[(0, valid_web), (0, "synthetic Web build"), (0, output)])
                runner.build = mock.Mock(side_effect=ReachedBuild)
                try:
                    RELEASE.make_bundle(runner, HEAD, 255, self.root / "base.apk")
                except ReachedBuild:
                    reached_build = True
                except RELEASE.ReleaseError:
                    reached_build = False
                else:
                    self.fail("The mocked APK build boundary must stop the test")
                self.assertEqual(allowed, reached_build)
                self.assertEqual(allowed, runner.build.called)
                self.assertEqual(3, runner.command.call_count)

    def test_manifest_is_activated_only_after_all_uploaded_full_hash_and_range_checks(self):
        bundle, runner = self.bundle(), self.runner()
        http = PublisherFixture(self.c, bundle)
        with mock.patch.object(RELEASE, "source_state", return_value=HEAD), mock.patch.object(RELEASE, "core_acceptance", return_value={}):
            result = RELEASE.upload_release(runner, http, bundle, BASE_SHA, HEAD)
        self.assertEqual("published", result["status"])
        activate = http.events.index(("POST", http.prefix + "/activate"))
        for path in http.artifacts:
            self.assertLess(http.events.index(("GET", path)), activate)
            self.assertLess(http.events.index(("RANGE", path)), activate)
        self.assertEqual(("GET", self.c["manifest_path"]), http.events[-1])
        self.assertEqual(1, sum(path.endswith("/activate") for _, path in http.events))

    def test_lost_activation_reply_requires_exact_active_release_and_manifest_reconciliation(self):
        bundle, runner = self.bundle(), self.runner()
        for applied in (True, False):
            with self.subTest(activation_applied=applied):
                http = PublisherFixture(self.c, bundle, lost_reply=True, activate_applied=applied)
                with mock.patch.object(RELEASE, "source_state", return_value=HEAD), mock.patch.object(RELEASE, "core_acceptance", return_value={}):
                    if applied:
                        self.assertEqual("published", RELEASE.upload_release(runner, http, bundle, BASE_SHA, HEAD)["status"])
                    else:
                        with self.assertRaises(RELEASE.ReleaseError):
                            RELEASE.upload_release(runner, http, bundle, BASE_SHA, HEAD)
                self.assertIn(("GET", "/__publisher/status"), http.events)
                self.assertEqual(1, sum(path.endswith("/activate") for _, path in http.events))

    def test_retry_same_source_reuses_signed_bundle_after_upload_failure_without_rebuild(self):
        bundle = self.bundle()
        previous = Path(self.c["work_root"]) / "last-run.json"
        RELEASE.write_json(previous, {"stage": "artifacts_ready", "sourceCommit": HEAD, "bundle": str(bundle), "baselineManifestSha256": BASE_SHA})
        original = (bundle / "release.json").read_bytes()
        http = types.SimpleNamespace(token=None, json=mock.Mock(return_value={"current": {"manifestSha256": BASE_SHA},
                                      "pinSpkiSha256": self.c["public_pin_sha256"], "partBytes": RELEASE.PART_BYTES,
                                      "digestStreamAvailable": True}))

        def verify(_runner, name, *args):
            self.verify_bundle_tool(_runner, name, *args)

        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE, "sync_source", return_value=HEAD), \
                mock.patch.object(RELEASE, "source_state", return_value=HEAD), \
                mock.patch.object(RELEASE, "core_acceptance", return_value={}), \
                mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 253}, self.root / "base.apk", BASE_SHA)), \
                mock.patch.object(RELEASE, "Http", return_value=http), \
                mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                mock.patch.object(RELEASE.Runner, "tool", autospec=True, side_effect=verify), \
                mock.patch.object(RELEASE, "make_bundle") as build, \
                mock.patch.object(RELEASE, "next_version") as version, \
                mock.patch.object(RELEASE, "upload_release", side_effect=[RELEASE.ReleaseError("synthetic upload interruption"), {"status": "published", "sourceCommit": HEAD}]) as upload:
            with self.assertRaises(RELEASE.ReleaseError):
                RELEASE.run_release(self.c)
            self.assertEqual("artifacts_ready", RELEASE.read_json(previous)["stage"])
            self.assertEqual("published", RELEASE.run_release(self.c)["status"])
        self.assertEqual(2, upload.call_count)
        self.assertEqual(original, (bundle / "release.json").read_bytes())
        build.assert_not_called()
        version.assert_not_called()

    def test_bad_range_or_full_sha_never_reaches_activation(self):
        bundle, runner = self.bundle(), self.runner()
        for fault in ("range-status", "range-header", "range-bytes", "full-sha"):
            with self.subTest(fault=fault):
                http = PublisherFixture(self.c, bundle, fault=fault)
                with mock.patch.object(RELEASE, "source_state", return_value=HEAD), mock.patch.object(RELEASE, "core_acceptance", return_value={}):
                    with self.assertRaises(RELEASE.ReleaseError):
                        RELEASE.upload_release(runner, http, bundle, BASE_SHA, HEAD)
                self.assertFalse(any(path.endswith("/activate") for _, path in http.events))
                self.assertFalse(http.active)

    def test_aria2_public_download_has_bounded_verified_arguments_and_no_credentials(self):
        runner = self.runner()
        runner.c["aria2"] = str(self.root / "aria2c.exe")
        Path(runner.c["aria2"]).write_bytes(b"synthetic executable, never run")
        body = b"synthetic public download bytes"
        target = runner.directory / "public.verified"
        url = self.c["origin"] + "/artifacts/synthetic.apk"
        http = types.SimpleNamespace(origin=self.c["origin"], token=SYNTHETIC_TOKEN,
                                     request=mock.Mock(return_value=Response(status=200, headers={"Content-Length": str(len(body))})))

        def execute(arguments, **kwargs):
            target.write_bytes(body)
            return 0, "Synthetic aria2 completed"

        runner.command = mock.Mock(side_effect=execute)
        RELEASE.download_exact(http, url, target, {"bytes": len(body), "sha256": sha(body)}, runner=runner)
        http.request.assert_called_once_with("/artifacts/synthetic.apk", method="HEAD")
        args = list(map(str, runner.command.call_args.args[0]))
        for option in ("--no-conf=true", "--check-certificate=true", "--split=4", "--max-connection-per-server=4",
                       "--min-split-size=1M", "--continue=true", "--auto-file-renaming=false", "--allow-overwrite=false",
                       "--file-allocation=none", "--check-integrity=true", "--checksum=sha-256=" + sha(body),
                       "--max-tries=3", "--retry-wait=1", "--connect-timeout=15", "--timeout=30", "--stop=600",
                       "--summary-interval=10", "--enable-color=false"):
            self.assertIn(option, args)
        self.assertTrue(any(arg.startswith("--user-agent=PocketWorld") for arg in args))
        self.assertIn("--dir=" + str(target.parent), args)
        self.assertIn("--out=" + target.name, args)
        self.assertEqual(url, args[-1])
        self.assertNotIn(SYNTHETIC_TOKEN, repr(args))
        self.assertFalse(any("proxy" in arg.lower() or "authorization" in arg.lower() or "max-redirect" in arg.lower() for arg in args))
        self.assertEqual(body, target.read_bytes())

    def test_aria2_failures_bad_head_size_or_hash_are_rejected(self):
        body = b"verified synthetic bytes"
        for fault in ("exit", "missing", "size", "sha", "head-status", "head-length"):
            with self.subTest(fault=fault):
                runner = self.runner()
                runner.c["aria2"] = str(self.root / "aria2c.exe")
                target = runner.directory / (fault + ".verified")
                http = types.SimpleNamespace(origin=self.c["origin"], request=mock.Mock(return_value=Response(
                    status=302 if fault == "head-status" else 200,
                    headers={"Content-Length": "999999" if fault == "head-length" else str(len(body))})))

                def execute(arguments, **kwargs):
                    if fault != "missing":
                        target.write_bytes(body[:-1] if fault == "size" else b"!" * len(body) if fault == "sha" else body)
                    return (1 if fault == "exit" else 0), "Synthetic download result"

                runner.command = mock.Mock(side_effect=execute)
                with self.assertRaises(RELEASE.ReleaseError):
                    RELEASE.download_exact(http, self.c["origin"] + "/public.apk", target,
                                           {"bytes": len(body), "sha256": sha(body)}, runner=runner)
                if fault.startswith("head-"):
                    runner.command.assert_not_called()

    def test_unconfigured_aria2_keeps_the_original_exact_urllib_download(self):
        runner = self.runner()
        runner.command = mock.Mock()
        body = b"portable original urllib bytes"
        http = types.SimpleNamespace(origin=self.c["origin"], request=mock.Mock(return_value=Response(body)))
        target = runner.directory / "fallback.verified"
        RELEASE.download_exact(http, self.c["origin"] + "/public.apk", target,
                               {"bytes": len(body), "sha256": sha(body)}, runner=runner)
        self.assertEqual(body, target.read_bytes())
        runner.command.assert_not_called()
        http.request.assert_called_once_with("/public.apk")

    def test_configured_aria2_must_be_absolute_and_existing(self):
        self.c["aria2"] = "relative/aria2c.exe"
        config = self.root / "config.json"
        RELEASE.write_json(config, self.c)
        with self.assertRaises(RELEASE.ReleaseError):
            RELEASE.load_config(config)
        self.c["aria2"] = str(self.root / "missing-aria2c.exe")
        self.assertIn("aria2", RELEASE.runtime_paths(self.c))

    def test_script_only_ancestor_reuses_native_verified_bundle_with_original_source(self):
        repo = Path(self.c["repo"])
        self.git_repo(repo)
        artifact_source = self.git(repo, "rev-parse", "HEAD")
        script = repo / "tools/release-fixture.txt"
        script.parent.mkdir()
        script.write_text("synthetic release-only fix\n", encoding="utf-8")
        self.git(repo, "add", "tools/release-fixture.txt")
        self.git(repo, "commit", "-m", "Script-only change")
        current = self.git(repo, "rev-parse", "HEAD")
        bundle = self.bundle()
        RELEASE.write_json(Path(self.c["work_root"]) / "last-run.json", {"stage": "artifacts_ready", "sourceCommit": artifact_source,
                           "bundle": str(bundle), "baselineManifestSha256": BASE_SHA})
        http = PublisherFixture(self.c, bundle)
        http.ready = {sha(body) for body in http.artifacts.values()}
        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE, "sync_source", return_value=current), \
                mock.patch.object(RELEASE, "source_state", return_value=current), \
                mock.patch.object(RELEASE, "core_acceptance", return_value={}) as acceptance, \
                mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 253}, self.root / "base.apk", BASE_SHA)), \
                mock.patch.object(RELEASE, "Http", return_value=http), \
                mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                mock.patch.object(RELEASE.Runner, "tool", autospec=True, side_effect=self.verify_bundle_tool) as verify, \
                mock.patch.object(RELEASE, "make_bundle", side_effect=AssertionError("Must reuse the unchanged application")) as build:
            result = RELEASE.run_release(self.c)
        self.assertEqual("published", result["status"])
        self.assertEqual(artifact_source, result["sourceCommit"])
        self.assertEqual(current, result["currentCheckout"])
        self.assertEqual([artifact_source], http.registered_sources)
        self.assertEqual({"verify-envelope", "verify-apk"}, {call.args[1] for call in verify.call_args_list})
        self.assertTrue(all(call.args[1] == current for call in acceptance.call_args_list))
        self.assertFalse(any(method == "PUT" for method, _ in http.events))
        build.assert_not_called()

    def test_application_change_nonancestor_or_invalid_source_never_reuses_old_bundle(self):
        bundle = self.bundle()
        for fault in ("app-change", "nonancestor", "invalid-source"):
            with self.subTest(fault=fault):
                RELEASE.write_json(Path(self.c["work_root"]) / "last-run.json", {
                    "stage": "artifacts_ready", "sourceCommit": "not-a-commit" if fault == "invalid-source" else "b" * 40,
                    "bundle": str(bundle), "baselineManifestSha256": BASE_SHA})
                http = PublisherFixture(self.c, bundle)

                def git(_runner, *args, **kwargs):
                    self.assertIn(args[0], ("merge-base", "diff"))
                    return (1 if fault == "nonancestor" or args[0] == "diff" else 0), ""

                with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                        mock.patch.object(RELEASE, "sync_source", return_value=HEAD), \
                        mock.patch.object(RELEASE, "core_acceptance", return_value={}), \
                        mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 253}, self.root / "base.apk", BASE_SHA)), \
                        mock.patch.object(RELEASE, "Http", return_value=http), \
                        mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                        mock.patch.object(RELEASE.Runner, "git", autospec=True, side_effect=git), \
                        mock.patch.object(RELEASE.Runner, "tool") as verify, \
                        mock.patch.object(RELEASE, "next_version", return_value=256), \
                        mock.patch.object(RELEASE, "make_bundle", side_effect=RELEASE.ReleaseError("Synthetic new-build boundary")) as build:
                    with self.assertRaises(RELEASE.ReleaseError):
                        RELEASE.run_release(self.c)
                build.assert_called_once()
                verify.assert_not_called()
                self.assertEqual([], http.registered_sources)

    def test_reuse_stops_on_changed_baseline_artifacts_or_native_verification(self):
        bundle = self.bundle()
        artifact = next((bundle / "artifacts").rglob("*.apk"))
        original = artifact.read_bytes()
        for fault in ("baseline", "artifact", "manifest-native", "pin", "apk-native"):
            with self.subTest(fault=fault):
                artifact.write_bytes(b"changed artifact" if fault == "artifact" else original)
                RELEASE.write_json(Path(self.c["work_root"]) / "last-run.json", {
                    "stage": "artifacts_ready", "sourceCommit": HEAD, "bundle": str(bundle),
                    "baselineManifestSha256": "c" * 64 if fault == "baseline" else BASE_SHA})
                http = PublisherFixture(self.c, bundle)

                def verify(runner, name, *args):
                    if (name == "verify-envelope" and fault == "manifest-native") or (name == "verify-apk" and fault == "apk-native"):
                        raise RELEASE.ReleaseError("Synthetic native verification refusal")
                    self.verify_bundle_tool(runner, name, *args)
                    if name == "verify-envelope" and fault == "pin":
                        output = args[args.index("--output") + 1]
                        value = RELEASE.read_json(output)
                        value["pinSpkiSha256"] = "c" * 64
                        RELEASE.write_json(output, value)

                with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                        mock.patch.object(RELEASE, "sync_source", return_value=HEAD), \
                        mock.patch.object(RELEASE, "source_state", return_value=HEAD), \
                        mock.patch.object(RELEASE, "core_acceptance", return_value={}), \
                        mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 253}, self.root / "base.apk", BASE_SHA)), \
                        mock.patch.object(RELEASE, "Http", return_value=http), \
                        mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                        mock.patch.object(RELEASE.Runner, "tool", autospec=True, side_effect=verify), \
                        mock.patch.object(RELEASE, "make_bundle") as build, \
                        mock.patch.object(RELEASE, "upload_release") as upload:
                    with self.assertRaises(RELEASE.ReleaseError):
                        RELEASE.run_release(self.c)
                build.assert_not_called()
                upload.assert_not_called()

    def test_already_published_unchanged_application_does_not_build_again(self):
        previous = Path(self.c["work_root"]) / "last-run.json"
        RELEASE.write_json(previous, {"stage": "published", "sourceCommit": HEAD, "manifestSha256": BASE_SHA})
        http = types.SimpleNamespace(token=None, json=mock.Mock(return_value={
            "current": {"manifestSha256": BASE_SHA}, "pinSpkiSha256": self.c["public_pin_sha256"],
            "partBytes": RELEASE.PART_BYTES, "digestStreamAvailable": True}))
        with mock.patch.object(RELEASE, "runtime_paths", return_value=[]), \
                mock.patch.object(RELEASE, "sync_source", return_value=HEAD), \
                mock.patch.object(RELEASE, "core_acceptance", return_value={}), \
                mock.patch.object(RELEASE, "baseline", return_value=({"versionCode": 255, "validNow": True}, self.root / "base.apk", BASE_SHA)), \
                mock.patch.object(RELEASE, "Http", return_value=http), \
                mock.patch.object(RELEASE, "read_token", return_value=SYNTHETIC_TOKEN), \
                mock.patch.object(RELEASE.Runner, "git", return_value=(0, "")), \
                mock.patch.object(RELEASE, "make_bundle") as build, \
                mock.patch.object(RELEASE, "next_version") as version, \
                mock.patch.object(RELEASE, "upload_release") as upload:
            result = RELEASE.run_release(self.c)
        self.assertEqual("up_to_date", result["status"])
        self.assertTrue(result["noBuildOrPublication"])
        build.assert_not_called()
        version.assert_not_called()
        upload.assert_not_called()

    def test_cross_origin_and_redirects_cannot_receive_publisher_credential(self):
        http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
        http.opener = mock.Mock()
        http.opener.open.return_value = Response(b"{}")
        with http.request("/__publisher/status", auth=True) as response:
            self.assertEqual(b"{}", response.read())
        request = http.opener.open.call_args.args[0]
        self.assertEqual("Bearer " + SYNTHETIC_TOKEN, request.get_header("Authorization"))
        self.assertNotIn(SYNTHETIC_TOKEN, request.full_url)
        for path in ("https://other.example/steal", "//other.example/steal"):
            with self.subTest(path=path), self.assertRaises(RELEASE.ReleaseError):
                http.request(path, auth=True)
        with self.assertRaises(RELEASE.ReleaseError):
            RELEASE.download_exact(http, "https://other.example/app.apk", self.root / "not-created.apk", {"bytes": 1, "sha256": "0" * 64})
        self.assertFalse((self.root / "not-created.apk").exists())
        self.assertEqual(1, http.opener.open.call_count)
        self.assertIsNone(RELEASE.NoRedirect().redirect_request(request, None, 302, "redirect", {}, "https://other.example/steal"))

    def test_read_only_site_status_retries_a_transient_timeout_before_building(self):
        http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
        http.opener = mock.Mock()
        http.opener.open.side_effect = [TimeoutError("The read operation timed out"), Response(b'{"current":{"versionCode":253}}')]
        with mock.patch.object(RELEASE.time, "sleep"):
            self.assertEqual(253, http.json("/__publisher/status")["current"]["versionCode"])
        self.assertEqual(2, http.opener.open.call_count)
        self.assertEqual(30, http.opener.open.call_args.kwargs["timeout"])

    def test_site_json_retries_read_failures_only_for_read_only_get(self):
        class BrokenResponse(Response):
            def read(self, *_args):
                raise TimeoutError("Synthetic Bearer " + SYNTHETIC_TOKEN)

        for method, expected_attempts in (("GET", 2), ("POST", 1)):
            with self.subTest(method=method):
                http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
                http.opener = mock.Mock()
                http.opener.open.side_effect = [BrokenResponse(b""), Response(b'{"ok":true}')]
                with mock.patch.object(RELEASE.time, "sleep"):
                    if method == "GET":
                        self.assertTrue(http.json("/__publisher/status")["ok"])
                    else:
                        with self.assertRaisesRegex(RELEASE.ReleaseError, r"POST /__publisher/releases.*read.*TimeoutError"):
                            http.json("/__publisher/releases", method, {"synthetic": True})
                self.assertEqual(expected_attempts, http.opener.open.call_count)
                self.assertEqual(30 if method == "GET" else 180, http.opener.open.call_args.kwargs["timeout"])

    def test_read_only_site_retries_are_bounded_and_network_logs_exclude_secrets(self):
        path = self.root / "network.log"
        http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN, log_path=path)
        http.opener = mock.Mock()
        http.opener.open.side_effect = TimeoutError("Synthetic Bearer " + SYNTHETIC_TOKEN)
        with mock.patch.object(RELEASE.time, "sleep") as sleep, self.assertRaises(RELEASE.ReleaseError):
            http.json("/__publisher/status?synthetic-secret-query")
        self.assertEqual(3, http.opener.open.call_count)
        self.assertEqual(2, sleep.call_count)
        log = path.read_text(encoding="utf-8")
        self.assertIn("GET /__publisher/status", log)
        self.assertIn("TimeoutError", log)
        self.assertNotIn(SYNTHETIC_TOKEN, log)
        self.assertNotIn("synthetic-secret-query", log)

    def test_site_authorization_certificate_and_protocol_failures_are_not_retried(self):
        for error in (RELEASE.urllib.error.HTTPError("https://synthetic.invalid", 401, "Unauthorized", {}, None),
                      RELEASE.urllib.error.URLError(RELEASE.ssl.SSLCertVerificationError(1, "synthetic certificate failure"))):
            with self.subTest(error=type(error).__name__):
                http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
                http.opener = mock.Mock()
                http.opener.open.side_effect = error
                with mock.patch.object(RELEASE.time, "sleep") as sleep, self.assertRaises(RELEASE.ReleaseError):
                    http.json("/__publisher/status")
                self.assertEqual(1, http.opener.open.call_count)
                sleep.assert_not_called()
        for raw in (b"not JSON", b" " * (128 * 1024 + 1)):
            with self.subTest(body_length=len(raw)):
                http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
                http.opener = mock.Mock()
                http.opener.open.return_value = Response(raw)
                with mock.patch.object(RELEASE.time, "sleep") as sleep, self.assertRaises((RELEASE.ReleaseError, ValueError)):
                    http.json("/__publisher/status")
                self.assertEqual(1, http.opener.open.call_count)
                sleep.assert_not_called()

    def test_mutating_site_requests_are_not_replayed_and_report_safe_context(self):
        for method, path in (("POST", "/__publisher/releases"), ("PUT", "/__publisher/releases/fixture/parts/0"),
                             ("POST", "/__publisher/releases/fixture/activate")):
            with self.subTest(method=method, path=path):
                http = RELEASE.Http(self.c["origin"], token=SYNTHETIC_TOKEN)
                http.opener = mock.Mock()
                http.opener.open.side_effect = TimeoutError("Synthetic Bearer " + SYNTHETIC_TOKEN)
                with mock.patch.object(RELEASE.time, "sleep") as sleep, self.assertRaises(RELEASE.ReleaseError) as failure:
                    http.request(path, method, b"synthetic write", auth=True)
                self.assertEqual(1, http.opener.open.call_count)
                sleep.assert_not_called()
                self.assertIn(method + " " + path, str(failure.exception))
                self.assertIn("TimeoutError", str(failure.exception))
                self.assertNotIn(SYNTHETIC_TOKEN, str(failure.exception))

    def test_bearer_credential_is_not_in_subprocess_argv_capture_or_disk_log(self):
        runner = self.runner()
        process = mock.MagicMock()
        process.__enter__.return_value = process
        process.stdout = io.StringIO("Authorization: Bearer " + SYNTHETIC_TOKEN + "\n")
        process.wait.return_value = 0
        with mock.patch.object(RELEASE.subprocess, "Popen", return_value=process) as spawn:
            _, captured = runner.command(["synthetic-cli", "--public-input", "fixture"], capture=True)
        self.assertNotIn(SYNTHETIC_TOKEN, repr(spawn.call_args.args[0]))
        self.assertNotIn(SYNTHETIC_TOKEN, captured)
        self.assertNotIn(SYNTHETIC_TOKEN, (runner.directory / "commands.log").read_text(encoding="utf-8"))
        self.assertIn("[redacted]", captured)


if __name__ == "__main__":
    unittest.main()
