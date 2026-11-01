"""Launcher contract/process fixtures. These tests do not claim successful bubblewrap isolation."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

# Resolve from the repository rather than the current working directory used by execute().
SOURCE = Path(__file__).resolve().parents[9] / "app/src/main/java/dev/pocketengine/features/skills/companion/pocketworld_skill_executor.py"
spec = importlib.util.spec_from_file_location("skill_executor", SOURCE)
skill = importlib.util.module_from_spec(spec)
spec.loader.exec_module(skill)


def fixture_bundle(policy_sha="b" * 64):
    files = {"SKILL.md": b"Untrusted reference", "scripts/main.py": b"DO NOT EXECUTE USER CODE", "assets/input.bin": b"\x00\xff\x80"}
    package = __import__("hashlib").sha256()
    for path in sorted(files):
        package.update(path.encode() + b"\0" + files[path] + b"\0")
    now = int(time.time() * 1000)
    config = {"installationId": "install1", "grantIdentity": "grant1", "ownerId": "owner", "spaceId": "private", "workspaceId": "workspace1",
              "runtime": "python3", "runtimeSha256": "b" * 64, "runtimeVersion": "fixture", "runtimeImageDigest": "a" * 64,
              "launcher": ["/usr/bin/python3", "-I", "-B"], "processPolicy": "single_process", "policySha256": policy_sha, "maxSeconds": 120, "maxOutputBytes": 65536,
              "grantExpiresAt": now + 60000, "workingDirectory": ".", "readPaths": ["input/data.txt"], "writePaths": ["output/result.bin"], "outputPaths": ["output/result.bin"]}
    q = {"scriptPath": "scripts/main.py", "runtimeId": "python3", "workspaceId": "workspace1", "workingDirectory": ".", "arguments": ["中文🙂", "</script>", ""],
         "readPaths": config["readPaths"], "writePaths": config["writePaths"], "outputPaths": config["outputPaths"], "network": "deny", "stdin": "closed", "environment": "empty",
         "timeoutSeconds": 10, "maxOutputBytes": 128, "maxResultChars": 2048}
    backend = {"backendId": "companion-skill-canonical-v1", "installationId": "install1", "runtimeId": "python3", "runtimeVersion": "fixture", "runtimeSha256": "b" * 64,
               "runtimeImageSha256": "a" * 64, "runtimePrefix": config["launcher"], "workspaceId": "workspace1", "workspaceRevision": "revision1", "workspaceRoot": "/skill-workspace",
               "packageRoot": "/skill-package", "enforcementId": "sha256:" + policy_sha, "workspaceMode": "isolated_copy", "fileResultsRequireImport": True, "processPolicy": "single_process",
               "fences": sorted(["EXACT_BYTES", "EXACT_ARGV", "EMPTY_ENVIRONMENT", "FILE_READS", "FILE_WRITES", "NETWORK_DENY", "OUTPUT_LIMIT", "TIMEOUT", "CANCELLATION"])}
    run = {"ownerId": "owner", "spaceId": "private", "taskId": "1" * 8 + "-" + "1" * 4 + "-" + "1" * 4 + "-" + "1" * 4 + "-" + "1" * 12,
           "runId": "2" * 8 + "-" + "2" * 4 + "-" + "2" * 4 + "-" + "2" * 4 + "-" + "2" * 12, "epoch": 1, "capabilities": []}
    review = {"format": "pocketworld.skill-execution.v1", "run": run, "package": {"id": "package1", "revision": 1, "digest": package.hexdigest(), "executionGeneration": 2, "approvedDigest": package.hexdigest(), "requiredCapabilities": [], "expiresAt": 0},
              "scriptSha256": skill.digest(files["scripts/main.py"]), "resources": [{"path": name, "sha256": skill.digest(value), "bytes": len(value)} for name, value in sorted(files.items())],
              "request": q, "backend": backend, "argv": config["launcher"] + ["/skill-package/scripts/main.py"] + q["arguments"], "cwd": "/skill-workspace", "expiresAt": now + 60000,
              "packageReadOnly": True, "requiresOwnerApproval": True, "untrustedReference": True, "businessVerified": False, "grantsAdded": []}
    encoded = skill.canonical(review); review["planDigest"] = skill.digest(encoded)
    grant = {"operationId": "3" * 8 + "-" + "3" * 4 + "-" + "3" * 4 + "-" + "3" * 4 + "-" + "3" * 12, "approvalId": "approval1", "backendGrantId": "grant1", "planDigest": review["planDigest"],
             "taskId": run["taskId"], "runId": run["runId"], "epoch": 1, "environmentBinding": skill.digest(skill.canonical(backend)), "expiresAt": now + 60000}
    bundle = {"format": "pocketworld.skill-canonical-bundle.v1", "review": review, "grant": grant, "operationKey": skill.operation_key(review, grant),
              "canonicalReviewBase64": base64.b64encode(encoded).decode(), "canonicalBackendBase64": base64.b64encode(skill.canonical(backend)).decode(),
              "resources": {name: base64.b64encode(value).decode() for name, value in files.items()}}
    return config, bundle, files


class SkillExecutorTest(unittest.TestCase):
    def test_failed_admin_check_removes_previous_ready_before_policy_validation(self):
        with tempfile.TemporaryDirectory() as root:
            ready = Path(root, skill.READY)
            ready.write_bytes(b'{"available":true,"marker":"synthetic-old-readiness"}')
            Path(root, skill.POLICY).write_bytes(b"{")
            result = subprocess.run([sys.executable, "-I", "-B", str(SOURCE), "check"], cwd=root,
                                    capture_output=True, timeout=5)
            self.assertEqual(2, result.returncode)
            self.assertEqual(b"", result.stdout)
            self.assertFalse(ready.exists(), "A failed administrator check retained stale readiness")

    def test_exact_bundle_hash_and_binary_resources(self):
        config, bundle, files = fixture_bundle()
        p, key, decoded = skill.validate_bundle(config, skill.canonical(bundle))
        self.assertEqual(files, decoded)
        self.assertEqual(bundle["operationKey"], key)
        self.assertEqual(bundle["review"]["planDigest"], p["planDigest"])
        bundle["resources"]["assets/input.bin"] = base64.b64encode(b"changed").decode()
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            skill.validate_bundle(config, skill.canonical(bundle))

    def test_launcher_rejects_host_paths_before_opening_any_file(self):
        with patch.object(skill, "read_regular", side_effect=AssertionError("No host file should be opened")):
            for path in ("/etc/passwd", "../escape", ".pwskill-../escape.json"):
                with self.assertRaisesRegex(ValueError, "flat staged bundle"):
                    skill.execute({}, path, "a" * 64)

    def test_edited_nested_review_and_grant_fail_before_launch(self):
        for change in ("argv", "environmentBinding", "runtimeSha256", "expiresAt"):
            config, bundle, _ = fixture_bundle()
            if change == "environmentBinding": bundle["grant"][change] = "f" * 64
            elif change == "runtimeSha256": bundle["review"]["backend"][change] = "f" * 64
            else: bundle["review"][change] = [] if change == "argv" else 1
            with self.assertRaises(ValueError):
                skill.validate_bundle(config, skill.canonical(bundle))

    def test_exact_file_rules_are_not_subtrees_and_mounts_never_widen_them(self):
        config, bundle, _ = fixture_bundle()
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root, "workspace"); (workspace / "input").mkdir(parents=True); (workspace / "output").mkdir()
            (workspace / "input/data.txt").write_bytes(b"data"); (workspace / "output/result.bin").write_bytes(b"")
            config.update(bwrap="/usr/bin/bwrap", runtimeRoot=Path(root, "image"))
            argv = skill.sandbox_argv(config, Path(root, "package"), workspace, bundle["review"], 12)
            self.assertIn(str(workspace / "input/data.txt"), argv); self.assertNotIn(str(workspace / "input"), argv)
            self.assertIn(str(workspace / "output/result.bin"), argv); self.assertNotIn(str(workspace / "output"), argv)
            for flag in ("--unshare-user", "--unshare-all", "--disable-userns", "--assert-userns-disabled", "--cap-drop", "--clearenv", "--seccomp", "--die-with-parent", "--remount-ro"):
                self.assertIn(flag, argv)
            self.assertFalse(skill.matches(["input/data.txt"], "input/data.txt/child"))
            self.assertTrue(skill.matches(["input/"], "input/data.txt"))
            wrong = dict(config); wrong["readPaths"] = ["input"]
            with self.assertRaisesRegex(ValueError, "one exact"):
                skill.validate_layout(wrong, workspace)
            wrong["readPaths"] = ["input/"]; skill.validate_layout(wrong, workspace)

    def test_write_only_original_contents_and_names_are_not_copied(self):
        with tempfile.TemporaryDirectory() as root:
            source, target = Path(root, "source"), Path(root, "copy"); (source / "input").mkdir(parents=True); (source / "output/private").mkdir(parents=True); target.mkdir()
            (source / "input/allowed.txt").write_bytes(b"allowed"); (source / "input/other.txt").write_bytes(b"denied"); (source / "output/private/secret.txt").write_bytes(b"secret")
            (source / "input/allowed.txt.backup").write_bytes(b"same-prefix secret")
            revision = skill.tree(source, target, read_rules=["input/allowed.txt"])
            self.assertEqual(revision, skill.tree(source)); self.assertEqual(b"allowed", (target / "input/allowed.txt").read_bytes())
            self.assertFalse((target / "input/other.txt").exists()); self.assertFalse((target / "output").exists())
            self.assertFalse((target / "input/allowed.txt.backup").exists())

    def test_workspace_snapshot_revision_and_no_symlink_or_hardlink(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root, "source"); target = Path(root, "target"); source.mkdir(); target.mkdir()
            (source / "input").write_bytes(b"pinned")
            revision = skill.tree(source, target)
            self.assertEqual(revision, skill.tree(target)); self.assertEqual(b"pinned", (target / "input").read_bytes())
            (source / "link").symlink_to("input")
            with self.assertRaisesRegex(ValueError, "Symlinks"):
                skill.tree(source)
            (source / "link").unlink(); os.link(source / "input", source / "hardlink")
            with self.assertRaisesRegex(ValueError, "hardlinks"):
                skill.tree(source)

    def test_actual_execution_copy_filesystem_is_bounded_tmpfs(self):
        if not Path("/dev/shm").is_dir() or os.statvfs("/dev/shm").f_blocks * os.statvfs("/dev/shm").f_frsize > 256 * 1024 * 1024 or not 1 <= os.statvfs("/dev/shm").f_files <= 8192:
            self.skipTest("No bounded tmpfs provisioned in this environment")
        with tempfile.TemporaryDirectory(prefix="pwskill-state-", dir="/dev/shm") as root:
            skill.state_filesystem(root)

    def test_prelaunch_tombstone_is_durable_and_cannot_launch_again(self):
        config, bundle, _ = fixture_bundle()
        with tempfile.TemporaryDirectory() as root:
            cwd = Path.cwd()
            try:
                os.chdir(root)
                state = Path(root, "state"); state.mkdir()
                config.update(stateRoot=state, spoolRoot=Path(root))
                key = bundle["operationKey"]; raw = skill.canonical(bundle); path = ".pwskill-" + key + ".json"
                Path(path).write_bytes(raw); Path(".pwskill-cancel-" + key).write_text(bundle["review"]["planDigest"])
                with patch.object(skill, "supervise", side_effect=AssertionError("No process may start")):
                    receipt = skill.execute(config, path, skill.digest(raw))
                    self.assertEqual("CANCELLED", receipt["state"])
                    Path(".pwskill-cancel-" + key).unlink()
                    self.assertEqual(receipt, skill.execute(config, path, skill.digest(raw)))
            finally:
                os.chdir(cwd)

    def test_crash_after_receipt_reservation_stays_unknown_without_replay(self):
        config, bundle, _ = fixture_bundle()
        with tempfile.TemporaryDirectory() as root:
            cwd = Path.cwd()
            try:
                os.chdir(root)
                state = Path(root, "state"); state.mkdir()
                config.update(stateRoot=state, spoolRoot=Path(root), workspaceRoot=Path(root, "missing"))
                raw = skill.canonical(bundle); path = ".pwskill-" + bundle["operationKey"] + ".json"; Path(path).write_bytes(raw)
                with patch.object(skill, "tree", side_effect=ValueError("Interrupted staging")) as tree:
                    with self.assertRaisesRegex(ValueError, "Interrupted staging"):
                        skill.execute(config, path, skill.digest(raw))
                    self.assertEqual("UNKNOWN", skill.execute(config, path, skill.digest(raw))["state"])
                    self.assertEqual(1, tree.call_count)
            finally:
                os.chdir(cwd)

    def test_actual_output_capture_is_bounded_and_timeout_kills_process(self):
        fd = os.open(os.devnull, os.O_RDONLY)
        try:
            state, code, output, truncated = skill.supervise([sys.executable, "-I", "-c", "import sys;sys.stdout.buffer.write(b'x'*200000)"], fd, 2, 64, lambda: False)
        finally:
            os.close(fd)
        self.assertEqual(("COMPLETED", 0, b"x" * 64, True), (state, code, output, truncated))
        fd = os.open(os.devnull, os.O_RDONLY)
        try:
            started = time.monotonic()
            state, _, _, _ = skill.supervise([sys.executable, "-I", "-c", "import time;time.sleep(5)"], fd, .1, 64, lambda: False)
            self.assertEqual("TIMED_OUT", state); self.assertLess(time.monotonic() - started, 2)
        finally:
            os.close(fd)

    def test_artifact_export_is_exact_file_only_binary_and_bounded(self):
        config, bundle, _ = fixture_bundle(); p, grant, key = bundle["review"], bundle["grant"], bundle["operationKey"]
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root, "workspace"); (workspace / "output").mkdir(parents=True)
            (workspace / "output/result.bin").write_bytes(b"\0\xff"); (workspace / "output/unapproved.bin").write_bytes(b"do not export")
            config["spoolRoot"] = Path(root)
            path, sha, truncated = skill.export_artifacts(config, p, key, workspace, 3, grant)
            raw = Path(root, path).read_bytes(); manifest = json.loads(raw)
            self.assertEqual(sha, skill.digest(raw)); self.assertFalse(truncated); self.assertFalse(manifest["canonicalWorkspaceModified"])
            self.assertEqual(["output/result.bin"], [f["path"] for f in manifest["files"]]); self.assertEqual(b"\0\xff", base64.b64decode(manifest["files"][0]["base64"]))
            _, _, truncated = skill.export_artifacts(config, p, key, workspace, 1, grant); self.assertTrue(truncated)
            (workspace / "output/result.bin").unlink(); (workspace / "output/result.bin").symlink_to("/etc/passwd")
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                skill.export_artifacts(config, p, key, workspace, 128, grant)

    def test_real_libseccomp_filter_denies_network_fork_and_threads_in_fixture_process(self):
        # Loads the exact exported BPF in THIS disposable child only. No network connection is made.
        script = """import ctypes,importlib.util,os,socket,sys,threading
spec=importlib.util.spec_from_file_location('skill',sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
fd=m.seccomp_fd();data=os.read(fd,65536);os.close(fd)
class F(ctypes.Structure): _fields_=[('code',ctypes.c_ushort),('jt',ctypes.c_ubyte),('jf',ctypes.c_ubyte),('k',ctypes.c_uint)]
class P(ctypes.Structure): _fields_=[('len',ctypes.c_ushort),('filter',ctypes.POINTER(F))]
filters=(F*(len(data)//8)).from_buffer_copy(data);p=P(len(filters),filters);libc=ctypes.CDLL(None,use_errno=True)
assert libc.prctl(38,1,0,0,0)==0;assert libc.prctl(22,2,ctypes.byref(p),0,0)==0
for family in (socket.AF_INET,socket.AF_INET6,socket.AF_UNIX):
 try: socket.socket(family);raise AssertionError('socket was allowed')
 except PermissionError: pass
try: pid=os.fork()
except PermissionError: pass
else:
 if pid==0: os._exit(0)
 os.waitpid(pid,0);raise AssertionError('fork was allowed')
thread=threading.Thread(target=lambda:None)
try: thread.start()
except RuntimeError: pass
else: thread.join();raise AssertionError('thread creation was allowed')
print('real-seccomp-denied-network-fork-threads')
"""
        result = subprocess.run([sys.executable, "-I", "-B", "-c", script, str(SOURCE)], capture_output=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual(b"real-seccomp-denied-network-fork-threads\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
