#!/usr/bin/env python3
"""Owner-authorized execution bridge; independent implementation, Python stdlib only."""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import codecs
import hashlib
import hmac
import ipaddress
import json
import queue
import os
from pathlib import Path
import re
import secrets
import select
import shlex
import shutil
import signal
import ssl
import sqlite3
import stat
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit
from workspace_files import WorkspaceFiles, FileError
import assignment_workspaces
import result_files
import input_files
from codex_driver import CodexAppServerDriver, DriverError
from claude_driver import ClaudeStreamDriver, command as claude_command, inspect_cli as inspect_claude_cli, session_uuid

MAX_BODY = 65_536
MAX_OUTPUT = 196_608
MAX_ARTIFACT = 262_144
TERMINAL = {"completed", "failed", "cancelled", "interrupted", "unknown", "blocked"}
WORKERS = {"codex_app_server": "codex", "codex": "codex", "claude": "claude", "kimi": "kimi"}
ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$")


def worker_capabilities(worker):
    native = worker == "codex_app_server"
    claude = worker == "claude"
    return {"protocol": "codex_app_server" if native else "claude_stream_json" if claude else "process_exit", "modelSelection": native or claude,
            "threadResume": native or claude, "interactiveApprovals": native or claude, "turnInterrupt": native or claude,
            "inputSteering": False, "sessionReattach": False, "readOnlySandbox": native,
            "operationReconciliation": True, "protocolVerified": False, "modelInferenceVerified": False,
            "structuredUserInput": native or claude, "questionCapability": "validated_when_original_runtime_emits_request",
            "questionAnswerScope": "ordinary_private_session_only", "progressSnapshot": native or claude, "liveStreaming": False}


class RequestError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def atomic_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def text(value, field: str, maximum: int = 12_000) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum or "\x00" in value:
        raise RequestError(f"{field} is missing or invalid")
    return value


def identifier(value, field="id") -> str:
    value = text(value, field, 80)
    if not ID.fullmatch(value):
        raise RequestError(f"{field} is invalid")
    return value


class JobManager:
    def __init__(self, config: dict, token: str):
        if len(token) < 32:
            raise ValueError("Companion token must contain at least 32 characters")
        self.token = token
        self.config = config
        self.instance_id = str(uuid.uuid4())
        self.state = Path(config.get("stateDir", "~/.pocketengine/companion")).expanduser().resolve()
        self.state.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.jobs = {}
        self.processes = {}
        self.drivers = {}
        self.probe_slots = threading.BoundedSemaphore(2)
        self.stopping = set()
        self.resources = set()
        self.resource_changed = threading.Condition(self.lock)
        self.ledger = sqlite3.connect(str(self.state / "operations.sqlite3"), check_same_thread=False)
        if os.name != "nt":
            os.chmod(self.state / "operations.sqlite3", 0o600)
        self.ledger.execute("PRAGMA synchronous=FULL")
        self.ledger.execute("CREATE TABLE IF NOT EXISTS operations (operation_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, job_id TEXT NOT NULL, last_status TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        self.ledger.execute("CREATE TABLE IF NOT EXISTS steer_messages (message_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, fingerprint TEXT NOT NULL, receipt TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        # A restarted supervisor cannot know whether an in-flight pipe write was accepted.
        for message_id, raw in self.ledger.execute("SELECT message_id, receipt FROM steer_messages").fetchall():
            receipt = json.loads(raw)
            if receipt.get("status") in {"queued", "sending"}:
                receipt.update(status="unknown", message="Companion restarted before a steering acknowledgement; not replayed")
                self.ledger.execute("UPDATE steer_messages SET receipt=?, updated_at=? WHERE message_id=?", (json.dumps(receipt), self.now(), message_id))
        self.ledger.commit()
        self.files = WorkspaceFiles(self.state, self.ledger)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=3, thread_name_prefix="pocket-job")
        self.closed = False
        self.environments = self._environments(config.get("environments", []))
        for path in sorted(self.state.glob("job-*.json"))[-500:]:
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
                identifier(job["id"])
                ambiguous_approvals = False
                for receipt in job.get("approvalReceipts", []):
                    if receipt.get("status") == "sending":
                        receipt.update(status="unknown", message="Companion restarted before confirming this reply; not replayed")
                        ambiguous_approvals = True
                for receipt in job.get("questionReceipts", []):
                    if receipt.get("status") in {"queued", "sending"}:
                        receipt.update(status="unknown", dispatched=None, message="Companion restarted before confirming this exact input; not replayed")
                        ambiguous_approvals = True
                if job.get("_inputPersistenceUnconfirmed") and job.get("inputState"):
                    # A crash between the original job and ledger commits cannot resurrect ready/START.
                    job.update(status="unknown", message="Original input state persistence was not confirmed; query/cancel only")
                    job["inputState"]["phase"] = "unknown"
                    self._save(job)  # Persist the whole conservative state, never auto-dispatch after restart.
                if job.get("status") not in TERMINAL:
                    job.update(status="interrupted", pendingApprovals=[], pendingQuestions=[], message="Companion restarted; prior effects are unverified", updatedAt=self.now())
                    if job.get("inputState"):
                        job["inputState"]["phase"] = "interrupted"
                        self._save(job)
                    else:
                        atomic_json(path, job)
                elif ambiguous_approvals:
                    atomic_json(path, job)
                self.jobs[job["id"]] = job
                if job.get("operationId") and job.get("fingerprint"):
                    self.ledger.execute("INSERT OR IGNORE INTO operations VALUES (?, ?, ?, ?, ?)",
                                        (job["operationId"], job["fingerprint"], job["id"], job["status"], job.get("updatedAt", self.now())))
                    self.ledger.commit()
            except (ValueError, KeyError, OSError, RequestError):
                continue

    @staticmethod
    def now():
        return int(time.time() * 1000)

    def _environments(self, entries):
        if not isinstance(entries, list) or len(entries) > 40:
            raise ValueError("environments must be a list with at most 40 entries")
        result = {}
        for entry in entries:
            eid = identifier(entry.get("id"), "environment id")
            if eid in result:
                raise ValueError("Duplicate environment id")
            item = dict(entry)
            if "writeAllowed" in item and not isinstance(item["writeAllowed"], bool):
                raise ValueError("writeAllowed must be an explicit boolean")
            kind = item.get("kind", "local")
            if kind == "local":
                root = Path(text(item.get("root"), "workspace root", 2048)).expanduser().resolve()
                if not root.is_dir():
                    raise ValueError("A configured local workspace is missing")
                item["root"] = str(root)
            elif kind == "ssh":
                host = text(item.get("host"), "SSH host", 253)
                if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", host):
                    raise ValueError("SSH host must be a hostname or configured alias")
                user = text(item.get("user"), "SSH user", 64)
                if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", user):
                    raise ValueError("SSH user is invalid")
                root = text(item.get("root"), "SSH workspace", 2048)
                if not root.startswith("/") or "\n" in root or "\r" in root:
                    raise ValueError("SSH workspace must be an absolute POSIX path")
                known = Path(text(item.get("knownHosts"), "knownHosts", 2048)).expanduser().resolve()
                if not known.is_file():
                    raise ValueError("SSH knownHosts file is missing; verify host keys on the computer first")
                item["knownHosts"] = str(known)
                port = item.get("port", 22)
                if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65_535:
                    raise ValueError("SSH port is invalid")
                item["port"] = port
                for field in ("identityFile", "sshConfig"):
                    if item.get(field):
                        selected = Path(text(item[field], field, 2048)).expanduser().resolve()
                        if not selected.is_file():
                            raise ValueError(f"{field} does not exist")
                        item[field] = str(selected)
            else:
                raise ValueError("Environment kind must be local or ssh")
            allowed = item.get("workers", ["command"])
            if not isinstance(allowed, list) or any(x not in {"command", *WORKERS} for x in allowed):
                raise ValueError("Unknown enabled worker")
            if "kimi" in allowed and not item.get("allowAutonomousWorkers", False):
                raise ValueError("Kimi print mode auto-approves tools; allowAutonomousWorkers must be explicitly enabled")
            commands = item.get("commands", {})
            if not isinstance(commands, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in commands.items()):
                raise ValueError("commands maps allowed command names to executable paths")
            result[eid] = item
        return result

    def list_environments(self):
        entries = []
        for eid, item in self.environments.items():
            workers = []
            for worker in item.get("workers", ["command"]):
                executable = WORKERS.get(worker)
                available = None if item.get("kind") == "ssh" else worker == "command" or bool(shutil.which(executable))
                workers.append({"id": worker, "available": available, "availability": "unverified_remote" if available is None else "installed" if available else "missing", "autonomousTools": worker == "kimi", "capabilities": worker_capabilities(worker)})
            entries.append({"id": eid, "label": item.get("label", eid), "kind": item.get("kind", "local"), "workers": workers,
                            "writeAllowed": bool(item.get("writeAllowed", False)), "sandboxed": False,
                            "bindingHash": self.environment_binding(eid), "workspace": item["root"],
                            "privateInputCapabilities": input_files.capabilities(item),
                            "assignmentWorkspaceCapabilities": {"protocol": "selected_files_v1", "copySupported": item.get("kind", "local") == "local" and os.name != "nt" and hasattr(os, "O_NOFOLLOW") and bool(item.get("writeAllowed")) and "codex_app_server" in item.get("workers", []), "maxFiles": 8, "fullProject": False, "osReadIsolationVerified": False, "commandApprovalEscalationSupported": False, "wallTimeEnforcement": "original_companion_process_group", "detachedDescendantIsolationVerified": False}})
        return {"environments": entries}

    def environment_binding(self, eid):
        item = self._environment(eid)
        # No credential contents or token-derived identity are exposed. A restart invalidates
        # an unsubmitted review; accepted operations still reconcile by their original ID.
        scope = {key: item.get(key) for key in ("kind", "root", "host", "user", "port", "writeAllowed", "workers", "commands", "knownHosts", "identityFile", "sshConfig")}
        scope.update(instance=self.instance_id, environmentId=eid)
        return hashlib.sha256(json.dumps(scope, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    def probe_worker(self, request):
        env = self._environment(identifier(request.get("environmentId"), "environmentId"))
        if "environmentBinding" in request and request["environmentBinding"] != self.environment_binding(request["environmentId"]):
            raise RequestError("Environment configuration or Companion instance changed; probe not started", 409)
        worker = request.get("worker")
        if worker == "claude":
            return {**self._inspect_claude(env), "environmentId": request["environmentId"], "worker": worker,
                    "capabilities": worker_capabilities(worker), "selectedModelAvailable": None}
        if worker != "codex_app_server":
            raise RequestError("A native interactive adapter is not implemented for this worker", 409)
        model = text(request["model"], "model", 200) if "model" in request else None
        argv = self._worker_argv({"worker": worker, "prompt": "Protocol capability probe"}, env)
        launch, cwd = self._launch(env, argv)
        if not self.probe_slots.acquire(blocking=False):
            raise RequestError("Worker capability probes are busy", 429)
        process = None
        try:
            child_env = dict(os.environ)
            child_env.pop("POCKETENGINE_COMPANION_TOKEN", None)
            options = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
            process = subprocess.Popen(launch, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=child_env, bufsize=0, **options)
            driver = CodexAppServerDriver(process, env["root"], False, lambda _state: None, lambda: self.closed, 10)
            result = driver.probe(model)
            result.update(environmentId=request["environmentId"], worker=worker, readOnly=True,
                          capabilities={**worker_capabilities(worker), "protocolVerified": True, "inputSteering": result["inputSteeringVerified"]})
            return result
        except (OSError, ValueError, DriverError):
            return {"environmentId": request["environmentId"], "worker": worker, "available": False,
                    "protocolVerified": False, "accountConfigured": False, "modelInferenceVerified": False,
                    "capabilities": worker_capabilities(worker), "message": "Codex protocol/account probe failed; inspect its installed version and account on the computer"}
        finally:
            try:
                if process is not None:
                    self._terminate(process)
                    for stream in (process.stdin, process.stdout):
                        if stream is not None:
                            stream.close()
            finally:
                self.probe_slots.release()

    def _inspect_claude(self, env):
        if "claude" not in env.get("workers", ["command"]):
            raise RequestError("Worker is not enabled for this environment", 403)
        if not self.probe_slots.acquire(blocking=False):
            raise RequestError("Worker capability probes are busy", 429)
        try:
            return inspect_claude_cli(self._cli_text(env, ["claude", "--version"]),
                                     self._cli_text(env, ["claude", "--help"]))
        except (RequestError, OSError, ValueError):
            return {"available": False, "cliFlagsVerified": False, "protocolVerified": False,
                    "accountConfigured": None, "modelInferenceVerified": False,
                    "message": "Claude CLI version/help unavailable; no session or model turn was started"}
        finally:
            self.probe_slots.release()

    def _cli_text(self, env, argv):
        launch, cwd = self._launch(env, argv)
        child_env = dict(os.environ)
        child_env.pop("POCKETENGINE_COMPANION_TOKEN", None)
        options = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
        process = subprocess.Popen(launch, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, env=child_env, bufsize=0, **options)
        chunks, size = [], [0]
        def drain():
            try:
                while size[0] <= 262_144:
                    chunk = process.stdout.read(4096)
                    if not chunk:
                        return
                    size[0] += len(chunk)
                    chunks.append(chunk)
            except (OSError, ValueError):
                size[0] = 262_145
        reader = threading.Thread(target=drain, daemon=True, name="pocket-cli-probe")
        reader.start()
        try:
            if process.wait(timeout=5) != 0:
                raise ValueError("CLI diagnostic failed")
            reader.join(timeout=1)
            if reader.is_alive() or size[0] > 262_144:
                raise ValueError("CLI diagnostic output exceeds limit")
            return b"".join(chunks).decode("utf-8")
        except subprocess.TimeoutExpired:
            raise ValueError("CLI diagnostic timed out")
        finally:
            self._terminate(process)
            reader.join(timeout=1)
            process.stdout.close()

    def _environment(self, eid):
        if eid not in self.environments:
            raise RequestError("Environment is not configured", 404)
        return self.environments[eid]

    def _worker_argv(self, request, env):
        worker = request.get("worker", "command")
        if not isinstance(worker, str):
            raise RequestError("worker must be a string")
        if worker not in env.get("workers", ["command"]):
            raise RequestError("Worker is not enabled for this environment", 403)
        if worker == "command":
            args = request.get("argv")
            if not isinstance(args, list) or not 1 <= len(args) <= 80:
                raise RequestError("argv must contain 1–80 arguments")
            args = [text(a, "argument", 12_000) if a != "" else "" for a in args]
            executable = env.get("commands", {}).get(args[0])
            if not executable:
                raise RequestError("Command is not in the configured allowlist", 403)
            return [executable, *args[1:]]
        prompt = text(request.get("prompt"), "prompt")
        write = bool(env.get("writeAllowed", False))
        if worker == "codex_app_server":
            return ["codex", "app-server"]
        if worker == "codex":
            return ["codex", "exec", "--json", "--sandbox", "workspace-write" if write else "read-only", prompt]
        if worker == "claude":
            return claude_command(request.get("_sessionId", str(uuid.uuid4())), write,
                                  request.get("model"), bool(request.get("threadId")))
        if worker == "kimi":
            if not env.get("allowAutonomousWorkers", False):
                raise RequestError("Autonomous Kimi tools have not been enabled", 403)
            return ["kimi", "--print", "--output-format", "stream-json", "-p", prompt]
        raise RequestError("Unknown worker")

    @staticmethod
    def ssh_prefix(env):
        args = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ForwardAgent=no",
                "-o", "ClearAllForwardings=yes", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15",
                "-o", "ServerAliveCountMax=2", "-o", "UserKnownHostsFile=" + env["knownHosts"],
                "-F", env.get("sshConfig", "none"), "-p", str(env.get("port", 22)), "-l", env["user"]]
        if env.get("identityFile"):
            args += ["-i", env["identityFile"], "-o", "IdentitiesOnly=yes"]
        return args + [env["host"]]

    def _launch(self, env, argv):
        if env.get("kind", "local") == "ssh":
            remote = "cd -- " + shlex.quote(env["root"]) + " && exec " + shlex.join(argv)
            return self.ssh_prefix(env) + [remote], None
        executable = argv[0]
        if not (Path(executable).is_file() if os.path.isabs(executable) else shutil.which(executable)):
            raise RequestError("Requested executable is not installed", 409)
        return argv, env["root"]

    @staticmethod
    def resource_id(env):
        # Aliases of the same local directory share one execution resource.
        target = ["local", os.path.normcase(env["root"])] if env.get("kind", "local") == "local" else ["ssh", env["host"].lower(), env["user"], env.get("port", 22), env["root"]]
        return hashlib.sha256(json.dumps(target).encode()).hexdigest()

    @staticmethod
    def workspace_identity(env):
        if env.get("kind", "local") != "local":
            return None  # No local inode proof for an SSH workspace.
        info = os.stat(env["root"], follow_symlinks=False)
        if not stat.S_ISDIR(info.st_mode):
            raise RequestError("Configured workspace is no longer the original regular directory", 409)
        return hashlib.sha256(json.dumps([env["root"], info.st_dev, info.st_ino], separators=(",", ":")).encode()).hexdigest()

    def operation(self, operation):
        operation = identifier(operation, "operationId")
        with self.lock:
            found = self.ledger.execute("SELECT job_id, last_status FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found is None:
                return {"operationId": operation, "status": "not_found", "message": "No accepted receipt found; this query did not submit anything"}
            if found[0] in self.jobs:
                return self._public(self.jobs[found[0]])
            return {"id": found[0], "operationId": operation, "status": "unknown", "archived": True, "lastKnownStatus": found[1],
                    "message": "Execution receipt is no longer retained; the operation remains reserved and must not be replayed"}

    def submit(self, request):
        operation = identifier(request.get("operationId"), "operationId")
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        request = dict(request)
        with self.lock:
            found = self.ledger.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found is not None:
                if found[0] != fingerprint:
                    raise RequestError("operationId was already used with different arguments", 409)
                return self.operation(operation)
        eid = identifier(request.get("environmentId"), "environmentId")
        env = dict(self._environment(eid))
        input_state = input_files.manifest(request, env, RequestError)
        if input_state is not None and request.get("environmentBinding") != self.environment_binding(eid):
            raise RequestError("Private input transfer requires the exact reviewed environment generation", 409)
        assignment_record = None
        if "assignmentWorkspace" in request:
            with self.lock:
                env, assignment_record = assignment_workspaces.bound_environment(self, request, env, RequestError)
        if "environmentBinding" in request and request["environmentBinding"] != self.environment_binding(eid):
            raise RequestError("Environment configuration or Companion instance changed; review again before creating work", 409)
        if not isinstance(request.get("worker", "command"), str):
            raise RequestError("worker must be a string")
        if "readOnly" in request and not isinstance(request["readOnly"], bool):
            raise RequestError("readOnly must be a boolean")
        if request.get("readOnly") is True:
            if request.get("worker") != "codex_app_server":
                raise RequestError("Forced read-only protocol execution requires codex_app_server", 409)
            env = {**env, "writeAllowed": False}
        for field in ("model", "threadId"):
            if field in request:
                text(request[field], field, 200)
                if request.get("worker") not in {"codex_app_server", "claude"}:
                    raise RequestError(field + " is unsupported by this worker", 409)
        prior = None
        if request.get("worker") == "codex_app_server" and request.get("threadId"):
            with self.lock:
                sources = [j for j in self.jobs.values() if j.get("worker") == "codex_app_server" and
                           j.get("threadId") == request["threadId"] and self._can_resume(j)]
                prior = self.jobs.get(identifier(request["resumeJobId"], "resumeJobId")) if "resumeJobId" in request else sources[0] if len(sources) == 1 else None
                if prior is None or prior not in sources or prior.get("resourceId") != self.resource_id(env):
                    raise RequestError("Codex resume requires the settled original invocation and execution resource; unknown work must be reconciled", 409)
                request["resumeJobId"] = prior["id"]
                if prior.get("readOnly"):
                    env = {**env, "writeAllowed": False}
                    request["readOnly"] = True
                elif not env.get("writeAllowed"):
                    raise RequestError("Codex resume cannot change the original tool access boundary", 409)
                if "model" not in request and prior.get("model"):
                    request["model"] = prior["model"]
        elif request.get("worker") == "codex_app_server" and "resumeJobId" in request:
            raise RequestError("resumeJobId requires the exact original threadId", 409)
        session_id = None
        if request.get("worker") == "claude":
            text(request.get("prompt"), "prompt")
            if request.get("threadId"):
                try:
                    session_id = session_uuid(request["threadId"])
                except DriverError as error:
                    raise RequestError(str(error), 409)
                with self.lock:
                    prior = self.jobs.get(identifier(request.get("resumeJobId"), "resumeJobId"))
                    if prior is None or prior.get("worker") != "claude" or prior.get("sessionId") != session_id or not prior.get("protocolVerified") or prior.get("status") not in {"completed", "failed", "cancelled"} or prior.get("resourceId") != self.resource_id(env):
                        raise RequestError("Claude resume requires a settled original job in the same workspace; unknown effects must be reconciled", 409)
                    if prior.get("readOnly") != (not bool(env.get("writeAllowed"))):
                        raise RequestError("Claude resume cannot change the original tool access boundary", 409)
                    if "model" not in request and prior.get("model"):
                        request["model"] = prior["model"]
            elif "resumeJobId" in request:
                raise RequestError("resumeJobId requires the exact original threadId", 409)
            session_id = session_id or str(uuid.uuid4())
            checked = self._inspect_claude(env)
            if not checked.get("available"):
                raise RequestError("Claude requires CLI >=2.1.259 and the verified control/session flags; run a worker probe", 409)
        with self.lock:
            if prior is not None and prior.get("workspaceIdentity") and prior["workspaceIdentity"] != self.workspace_identity(env):
                raise RequestError("Original continuation physical workspace changed", 409)
            self._check_snapshot_continuation(request, prior, env)
        argv = self._worker_argv({**request, "_sessionId": session_id}, env) if session_id else self._worker_argv(request, env)
        timeout = request.get("timeoutSeconds", 300)
        if not isinstance(timeout, int) or isinstance(timeout, bool) or not 1 <= timeout <= 3600:
            raise RequestError("timeoutSeconds must be 1–3600")
        launch, cwd = self._launch(env, argv)
        with self.lock:
            found = self.ledger.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found is not None:
                if found[0] != fingerprint:
                    raise RequestError("operationId was already used with different arguments", 409)
                return self.operation(operation)
            if self.closed or sum(j["status"] not in TERMINAL for j in self.jobs.values()) >= 12:
                raise RequestError("Companion queue is full or stopping", 503)
            if assignment_record is not None:
                env, assignment_record = assignment_workspaces.bound_environment(self, request, env, RequestError)
            if request.get("worker") in {"claude", "codex_app_server"} and request.get("threadId"):
                # Recheck atomically with reservation: another submitted invocation
                # (including an unknown one) must not be bypassed via an older job.
                source = self.jobs.get(request["resumeJobId"])
                if source is None or not self._can_resume(source) or source.get("resourceId") != self.resource_id(env):
                    raise RequestError("Continuation requires the latest settled invocation; active or unknown work must be reconciled", 409)
            job = {"id": str(uuid.uuid4()), "operationId": operation, "fingerprint": fingerprint, "environmentId": eid,
                   "worker": request.get("worker", "command"), "resourceId": self.resource_id(env), "status": "queued", "createdAt": self.now(), "updatedAt": self.now(),
                   "output": "", "outputTruncated": False, "exitCode": None, "message": "Queued", "remoteEffectsVerified": False}
            job["workspaceIdentity"] = self.workspace_identity(env)
            if assignment_record is not None:
                job.update(assignmentWorkspace=dict(request["assignmentWorkspace"]), assignmentEnvironmentBinding=request["environmentBinding"], externalJobLimits=dict(assignment_record["limits"]), processStarted=False, dispatchFenceFinal=False, notStartedConfirmed=False,
                           inferenceUsage={"modelCalls": None, "inputTokens": None, "outputTokens": None, "cost": None, "status": "unknown", "source": "external_cli_no_per_request_accounting"},
                           modelCallHardCapSupported=False, tokenHardCapSupported=False, costHardCapSupported=False, wallTimeHardCapSupported=True, wallTimeEnforcement="original_companion_process_group", detachedDescendantIsolationVerified=False,
                           assignmentWriteSandboxVerified=False, osReadIsolationVerified=False, commandAllowlistEnforced=False)
            if request.get("worker") == "codex_app_server":
                job.update(protocol="codex_app_server", readOnly=not bool(env.get("writeAllowed")),
                           model=request.get("model"), threadId=request.get("threadId"),
                           resumeJobId=request.get("resumeJobId"), pendingApprovals=[])
            elif request.get("worker") == "claude":
                job.update(protocol="claude_stream_json", readOnly=not bool(env.get("writeAllowed")),
                           model=request.get("model"), sessionId=session_id, threadId=session_id,
                           resumeJobId=request.get("resumeJobId"), protocolVerified=False, pendingApprovals=[])
            if request.get("resultSnapshotId"):
                job.update(resultSnapshotId=request["resultSnapshotId"], resultSnapshotVersion=request["resultSnapshotVersion"], snapshotPrelaunchVerified=False)
            if input_state is not None:
                input_files.bind_job(self, job, request, input_state)
            # Reserve idempotency before dispatch. If the job document cannot be saved,
            # subsequent queries remain conservative instead of repeating a side effect.
            self.ledger.execute("INSERT INTO operations VALUES (?, ?, ?, ?, ?)", (operation, fingerprint, job["id"], "queued", job["updatedAt"]))
            self.ledger.commit()
            self.jobs[job["id"]] = job
            self._save(job)
            self._prune()
            if input_state is None:
                self.pool.submit(self._run, job["id"], launch, cwd, timeout, env.get("kind") == "ssh", {"request": request, "environment": env, "sessionId": session_id} if request.get("worker") in {"codex_app_server", "claude"} else None)
            return self._public(job)

    def _prune(self):
        if len(self.jobs) <= 500:
            return
        for old in sorted(self.jobs.values(), key=lambda value: value["createdAt"]):
            if len(self.jobs) <= 500:
                break
            if old["status"] in TERMINAL:
                self.jobs.pop(old["id"], None)
                (self.state / ("job-" + old["id"] + ".json")).unlink(missing_ok=True)

    def _save(self, job):
        if not job.get("inputState"):
            atomic_json(self.state / ("job-" + job["id"] + ".json"), job)
            if job.get("operationId"):
                self.ledger.execute("UPDATE operations SET last_status=?, updated_at=? WHERE operation_id=?", (job["status"], job["updatedAt"], job["operationId"]))
                self.ledger.commit()
            return
        with self.lock:
            state = job["inputState"]
            if job.get("_inputPersistenceUnconfirmed") and (job["status"] not in TERMINAL or state["phase"] not in TERMINAL):
                job.update(status="unknown", message="Original input persistence was unconfirmed; ordinary saves do not restore dispatch")
                state["phase"] = "unknown"
            # Every original job save (including other job events) advances this input-state revision.
            # A failed attempt may leave a revision gap, never reuse/reset a previously assigned value.
            state["revision"] = state.get("revision", 0) + 1
            job["_inputPersistenceUnconfirmed"] = True
            path = self.state / ("job-" + job["id"] + ".json")
            def save_original(value):
                atomic_json(path, value)
                directory = os.open(self.state, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                try:
                    os.fsync(directory)
                finally:
                    os.close(directory)
            try:
                # A private sentinel in this SAME original job file survives a crash before ledger/final commit.
                save_original(job)
                if job.get("operationId"):
                    self.ledger.execute("UPDATE operations SET last_status=?, updated_at=? WHERE operation_id=?", (job["status"], job["updatedAt"], job["operationId"]))
                    self.ledger.commit()
                confirmed = {key: value for key, value in job.items() if key != "_inputPersistenceUnconfirmed"}
                save_original(confirmed)
                job.pop("_inputPersistenceUnconfirmed", None)
            except (OSError, sqlite3.Error, ValueError, TypeError):
                # Retain exact bytes/hash/START/worker facts; only dispatch/readiness becomes unconfirmed.
                job.update(status="unknown", message="Original input state persistence was not confirmed; no ready/START success is published")
                state["phase"] = "unknown"
                raise

    def _public(self, job):
        result = {k: v for k, v in job.items() if k not in {"fingerprint", "resourceId", "resultSnapshots", "assignmentWorkspaces", "inputRequest", "inputEnvironmentBinding"}}
        result.pop("_inputPersistenceUnconfirmed", None)
        if job.get("_inputPersistenceUnconfirmed"):
            result.pop("inputState", None)
            result.update(status="unknown", message="Original input state persistence unconfirmed; no successful revision or ready state is published")
        elif job.get("inputState"):
            result["inputState"] = json.loads(json.dumps(job["inputState"]))
        result["resultSnapshots"] = [self._snapshot_public(value) for value in job.get("resultSnapshots", [])]
        for snapshot in result["resultSnapshots"]:
            for file in snapshot.get("assignmentContribution", {}).get("files", []):
                if "diff" in file:
                    file.pop("diff")
                    file["diffAvailableInOriginalSnapshot"] = True
        native = job.get("worker") == "codex_app_server"
        live = job.get("id") in self.drivers and job.get("status") not in TERMINAL
        result["capabilities"] = worker_capabilities(job.get("worker"))
        result["capabilities"]["protocolVerified"] = bool(job.get("protocolVerified"))
        result["capabilities"]["inputSteering"] = native and bool(job.get("inputSteeringVerified"))
        driver = self.drivers.get(job.get("id"))
        result["controls"] = {"cancel": job.get("status") not in TERMINAL,
                              "approve": live and bool(job.get("pendingApprovals")),
                              "answerQuestion": live and not job.get("assignmentWorkspace") and any(value.get("canAnswer") for value in job.get("pendingQuestions", [])),
                              "threadResume": self._can_resume(job),
                              "inputSteering": not job.get("assignmentWorkspace") and live and driver is not None and driver.can_steer(job.get("turnId")), "sessionReattach": False}
        return result

    def _can_resume(self, job):
        if job.get("assignmentWorkspace"):
            return False  # Team follow-up needs native assignment/reservation, not private continuation.
        worker = job.get("worker")
        if worker not in {"claude", "codex_app_server"} or job.get("status") not in {"completed", "failed", "cancelled"} or not job.get("threadId"):
            return False
        if any(value.get("status") in {"queued", "sending", "unknown"} for value in job.get("questionReceipts", [])):
            return False
        if worker == "claude" and not job.get("protocolVerified") or worker == "codex_app_server" and job.get("protocol") != "codex_app_server":
            return False
        if worker == "codex_app_server":
            if job.get("steeringUnknown"):
                return False
            for (raw,) in self.ledger.execute("SELECT receipt FROM steer_messages WHERE job_id=?", (job["id"],)).fetchall():
                if json.loads(raw).get("status") in {"queued", "sending", "unknown"}:
                    return False
        env = self.environments.get(job.get("environmentId"))
        if env is None or self.resource_id(env) != job.get("resourceId"):
            return False
        if job.get("workspaceIdentity"):
            try:
                if job["workspaceIdentity"] != self.workspace_identity(env):
                    return False
            except (OSError, RequestError):
                return False
        return not any(j.get("worker") == worker and j.get("threadId") == job.get("threadId") and
                       (j.get("status") not in {"completed", "failed", "cancelled"} or j.get("resumeJobId") == job["id"])
                       for j in self.jobs.values())

    def get(self, jobid):
        identifier(jobid, "jobId")
        with self.lock:
            if jobid not in self.jobs:
                raise RequestError("Job not found", 404)
            return self._public(self.jobs[jobid])

    def list_jobs(self):
        with self.lock:
            entries = []
            for job in sorted(self.jobs.values(), key=lambda value: value["createdAt"], reverse=True)[:100]:
                item = self._public(job)
                item["outputPreview"] = item.pop("output", "")[-2000:]
                entries.append(item)
            return {"jobs": entries}

    @staticmethod
    def _terminate(process, hard=False):
        lock = getattr(process, "_pocket_cleanup_lock", None)
        if lock is None:
            lock = process._pocket_cleanup_lock = threading.Lock()
        with lock:
            previous = getattr(process, "_pocket_cleanup_proof", None)
            if previous is not None and previous.get("processGroupStoppedVerified"):
                return dict(previous)
            proof = JobManager._terminate_group(process, hard)
            if previous is not None:
                for key in ("terminationRequested", "leaderReaped", "processGroupKillAttempted"):
                    proof[key] = proof.get(key, False) or previous.get(key, False)
            process._pocket_cleanup_proof = dict(proof)
            return proof

    @staticmethod
    def _terminate_group(process, hard=False):
        proof = {"terminationRequested": True, "leaderReaped": False, "processGroupKillAttempted": False, "processGroupStoppedVerified": False}
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            else:
                if not hard:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        pass
                proof["processGroupKillAttempted"] = True
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                process.wait(timeout=1)
                proof["leaderReaped"] = True
            except subprocess.TimeoutExpired:
                pass
        except (ProcessLookupError, OSError, subprocess.TimeoutExpired):
            try:
                process.kill()
            except OSError:
                pass
            proof["leaderReaped"] = process.poll() is not None
        if os.name != "nt" and proof["leaderReaped"]:
            try:
                os.killpg(process.pid, 0)
            except ProcessLookupError:
                proof["processGroupStoppedVerified"] = True
            except OSError:
                pass
            else:
                proc = Path("/proc")
                if proc.is_dir():
                    entries = [path for path in proc.iterdir() if path.name.isdigit()]
                    if len(entries) <= 4096:
                        live = False
                        for path in entries:
                            try:
                                tail = (path / "stat").read_text().rsplit(")", 1)[1].split()
                                if int(tail[2]) == process.pid and tail[0] not in {"Z", "X"}:
                                    live = True
                                    break
                            except (OSError, ValueError, IndexError):
                                continue
                        proof["processGroupStoppedVerified"] = not live
                        proof["verificationMethod"] = "original_pgid_no_live_proc_members"
        return proof

    def cancel(self, jobid):
        with self.lock:
            job = self.jobs.get(identifier(jobid, "jobId"))
            if job is None:
                raise RequestError("Job not found", 404)
            if input_files.cancel_before_worker(self, job):
                return self._public(job)
            if job["status"] in TERMINAL:
                return self._public(job)
            self.stopping.add(jobid)
            job.update(status="stopping", message="Stopping transport/process; prior effects are retained", updatedAt=self.now())
            self._save(job)
            self.resource_changed.notify_all()
            process = self.processes.get(jobid)
            driver = self.drivers.get(jobid)
        if process is not None and (driver is None or job.get("assignmentWorkspace")):
            proof = self._terminate(process, hard=bool(job.get("assignmentWorkspace")))
            if job.get("assignmentWorkspace"):
                with self.lock:
                    self.jobs[jobid]["processCleanupProof"] = proof
                    self._save(self.jobs[jobid])
        return self.get(jobid)

    def _run(self, jobid, argv, cwd, timeout, remote, codex=None):
        process = None
        driver = None
        reader = None
        timed_out = False
        acquired_resource = None
        wall_timer = None
        wall_expired = threading.Event()
        assignment = False
        cleanup = None
        try:
            with self.resource_changed:
                job = self.jobs[jobid]
                assignment = bool(job.get("assignmentWorkspace"))
                resource = job["resourceId"]
                while resource in self.resources and jobid not in self.stopping and not self.closed:
                    self.resource_changed.wait(timeout=0.2)
                if jobid in self.stopping or self.closed:
                    job.update(status="cancelled", message="Cancelled before dispatch", updatedAt=self.now())
                    self._save(job)
                    return
                actual_env = assignment_workspaces.job_environment(self, job, RequestError)
                if assignment and codex["request"].get("environmentBinding") != self.environment_binding(job["environmentId"]):
                    raise RequestError("Original environment generation changed before process dispatch", 409)
                if assignment and job["assignmentWorkspace"]["approvalExpiresAt"] <= self.now():
                    raise RequestError("Native external_job approval expired before process dispatch", 409)
                if job.get("workspaceIdentity") and job["workspaceIdentity"] != self.workspace_identity(actual_env):
                    raise RequestError("Original physical workspace directory changed before process dispatch", 409)
                if codex and codex["request"].get("resultSnapshotId"):
                    original = self.jobs.get(codex["request"].get("resumeJobId"))
                    self._check_snapshot_continuation(codex["request"], original, self._environment(job["environmentId"]))
                    job.update(snapshotPrelaunchVerified=True, snapshotBaseVerifiedAt=self.now())
                if job.get("inputState"):
                    input_files.verify(self, job, RequestError)
                self.resources.add(resource)
                acquired_resource = resource
                child_env = dict(os.environ)
                child_env.pop("POCKETENGINE_COMPANION_TOKEN", None)
                if job.get("worker") == "claude":
                    child_env["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"] = "1"
                options = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
                process = subprocess.Popen(argv, cwd=cwd, stdin=subprocess.PIPE if codex else subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL if codex else subprocess.STDOUT, env=child_env, bufsize=0, **options)
                process._pocket_cleanup_lock = threading.Lock()
                self.processes[jobid] = process
                job.update(status="running", processStarted=True, dispatchFenceFinal=False, message="Remote SSH process started" if remote else "Process started", updatedAt=self.now())
                if job.get("inputState"):
                    job["inputState"].update(phase="running", workerStarted=True)
                    job["notStartedConfirmed"] = False
                self._save(job)
                if assignment:
                    def expire():
                        if getattr(process, "_pocket_cleanup_proof", {}).get("processGroupStoppedVerified"):
                            return
                        wall_expired.set()
                        with self.lock:
                            self.stopping.add(jobid)
                        stopped_proof = self._terminate(process, hard=True)
                        with self.lock:
                            self.jobs[jobid].update(wallTimeExceeded=True, timedOut=True, processCleanupProof=stopped_proof, updatedAt=self.now())
                            self._save(self.jobs[jobid])
                    wall_timer = threading.Timer(timeout, expire)
                    wall_timer.daemon = True
                    wall_timer.start()
            if codex:
                def stopped():
                    with self.lock:
                        if assignment and (job["assignmentWorkspace"]["approvalExpiresAt"] <= self.now() or job.get("assignmentEnvironmentBinding") != self.environment_binding(job["environmentId"])):
                            return True
                        return jobid in self.stopping or self.closed
                last_saved = {"at": 0, "identity": None}
                def state(value):
                    with self.lock:
                        # Raw protocol diagnostics and account credentials never enter receipts.
                        safe = json.loads(json.dumps(value).replace(self.token, "[token hidden]"))
                        if jobid in self.stopping and safe.get("status") in {"running", "awaiting_approval", "awaiting_input"}:
                            safe["status"] = "stopping"
                        self.jobs[jobid].update(safe, updatedAt=self.now())
                        identity = json.dumps([safe.get("threadId"), safe.get("turnId"), safe.get("pendingApprovals"), safe.get("approvalReceipts"), safe.get("pendingQuestions"), safe.get("questionReceipts"), safe.get("status")], sort_keys=True)
                        if identity != last_saved["identity"] or self.now() - last_saved["at"] >= 500:
                            self._save(self.jobs[jobid])
                            last_saved.update(at=self.now(), identity=identity)
                if job.get("worker") == "claude":
                    driver = ClaudeStreamDriver(process, codex["sessionId"], state, stopped, timeout,
                                                lambda: self.lock, codex["environment"]["root"], bool(codex["environment"].get("writeAllowed")))
                else:
                    driver = CodexAppServerDriver(process, codex["environment"]["root"], bool(codex["environment"].get("writeAllowed")), state, stopped, timeout, codex["request"].get("readOnly") is True, lambda: self.lock,
                                                  assignment_write=assignment, output_limit=job.get("externalJobLimits", {}).get("maxOutputBytes", MAX_OUTPUT),
                                                  assignment_paths=[value["path"] for value in assignment_workspaces.lookup(self, job["assignmentWorkspace"]["sourceJobId"], job["assignmentWorkspace"]["checkoutId"])["files"]] if assignment else None)
                with self.lock:
                    self.drivers[jobid] = driver
                result = driver.run(text(codex["request"].get("prompt"), "prompt"), codex["request"].get("threadId"), codex["request"].get("model"))
                with self.lock:
                    self.jobs[jobid].update(json.loads(json.dumps(result).replace(self.token, "[token hidden]")), updatedAt=self.now())
                    self._save(self.jobs[jobid])
                cleanup = self._terminate(process, hard=assignment)
                return
            reader_stop = threading.Event()
            reader = threading.Thread(target=self._read_output, args=(jobid, process, reader_stop), daemon=True)
            reader.start()
            deadline = time.monotonic() + timeout
            while process.poll() is None:
                with self.lock:
                    stopping = jobid in self.stopping or self.closed
                if stopping or time.monotonic() >= deadline:
                    timed_out = not stopping
                    self._terminate(process)
                    break
                time.sleep(0.05)
            try:
                code = process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                code = None
            reader.join(timeout=2)
            reader_stop.set()
            reader.join(timeout=0.3)
            with self.lock:
                job = self.jobs[jobid]
                stopped = jobid in self.stopping or self.closed or timed_out
                if stopped:
                    status = "unknown" if remote else "cancelled"
                    message = "SSH transport stopped; remote process/effects unverified" if remote else "Timed out; process stopped" if timed_out else "Process stopped; prior effects retained"
                else:
                    status = "completed" if code == 0 else "failed"
                    message = "Process exited; inspect output and artifacts" if code == 0 else "Process failed; inspect output"
                job.update(status=status, exitCode=code, message=message, updatedAt=self.now(), timedOut=timed_out)
                self._save(job)
        except (OSError, ValueError, sqlite3.Error, DriverError, RequestError) as error:
            if process is not None:
                cleanup = self._terminate(process, hard=assignment)
            with self.lock:
                before_dispatch = isinstance(error, RequestError) and process is None or isinstance(error, DriverError) and codex and driver is not None and not driver.turn_dispatched
                cancelled = before_dispatch and (jobid in self.stopping or self.closed)
                blocked = before_dispatch and not cancelled
                self.jobs[jobid].update(status="cancelled" if cancelled else "blocked" if blocked else "unknown" if process is not None else "failed", pendingApprovals=[], message="Cancelled before prompt/turn dispatch" if cancelled else str(error) + "; no process/turn was dispatched" if blocked and isinstance(error, RequestError) else "Required protocol capabilities were not confirmed; no turn was dispatched" if blocked else "Worker supervision failed; prior effects are unverified" if process is not None else "Unable to start configured worker: " + type(error).__name__, updatedAt=self.now())
                self._save(self.jobs[jobid])
        finally:
            if wall_timer is not None:
                wall_timer.cancel()
            if driver is not None:
                try:
                    driver.close_steering()
                except (OSError, ValueError, sqlite3.Error):
                    with self.lock:
                        self.jobs[jobid].update(status="unknown", message="Steering receipt could not be persisted; inspect the original operation before continuing")
            with self.lock:
                if assignment:
                    job = self.jobs[jobid]
                    if process is not None and cleanup is None:
                        cleanup = self._terminate(process, hard=True)
                    job.update(dispatchFenceFinal=True, notStartedConfirmed=process is None, processStarted=process is not None)
                    if cleanup is not None:
                        job["processCleanupProof"] = cleanup
                    if process is not None and not cleanup.get("processGroupStoppedVerified"):
                        job.update(status="unknown", message="Original process-group stop unconfirmed; reservation/copy stay held")
                    elif wall_expired.is_set():
                        job.update(status="cancelled", timedOut=True, wallTimeExceeded=True, message="Approved external_job walltime elapsed; original process group was stopped, prior effects retained")
                    elif process is not None and jobid in self.stopping and cleanup.get("processGroupStoppedVerified"):
                        job.update(status="cancelled", message="Original assignment process group stopped; prior effects and external_job reservation retained")
                    job["inferenceUsage"] = {"modelCalls": None, "inputTokens": None, "outputTokens": None, "cost": None, "status": "unknown", "source": "external_cli_no_per_request_accounting"}
                    self._save(job)
                job = self.jobs[jobid]
                if job.get("inputState"):
                    job["inputState"]["phase"] = job["status"]
                    job.update(dispatchFenceFinal=True, notStartedConfirmed=process is None, processStarted=process is not None)
                    self._save(job)
                self.processes.pop(jobid, None)
                self.drivers.pop(jobid, None)
                self.stopping.discard(jobid)
                if acquired_resource is not None and not (assignment and process is not None and not cleanup.get("processGroupStoppedVerified")):
                    self.resources.discard(acquired_resource)
                self.resource_changed.notify_all()
            if process is not None and process.stdout is not None:
                process.stdout.close()
            if process is not None and process.stdin is not None:
                process.stdin.close()

    def _read_output(self, jobid, process, stopped):
        pending = ""
        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        def append(value):
            value = value.replace(self.token, "[token hidden]")
            with self.lock:
                job = self.jobs[jobid]
                room = MAX_OUTPUT - len(job["output"])
                if room > 0:
                    job["output"] += value[:room]
                if len(value) > max(0, room):
                    job["outputTruncated"] = True
                job["updatedAt"] = self.now()
        try:
            while not stopped.is_set():
                if os.name != "nt" and not select.select([process.stdout], [], [], 0.1)[0]:
                    continue
                chunk = process.stdout.read(4096)
                if not chunk:
                    pending += decoder.decode(b"", final=True)
                    break
                pending += decoder.decode(chunk)
                # Redact before splitting; retain enough tail to match a token across reads.
                pending = pending.replace(self.token, "[token hidden]")
                keep = len(self.token) - 1
                if len(pending) > keep:
                    append(pending[:-keep]);pending = pending[-keep:]
        except (OSError, ValueError):
            pass
        finally:
            append(pending)

    def approve(self, jobid, request):
        with self.lock:
            driver = self.drivers.get(identifier(jobid, "jobId"))
            if driver is None:
                raise RequestError("No live protocol approval is pending; inspect the original job", 409)
            job = self.jobs[jobid]
            if job.get("assignmentWorkspace") and request.get("decision") == "accept":
                if job["assignmentWorkspace"]["approvalExpiresAt"] <= self.now() or job.get("assignmentEnvironmentBinding") != self.environment_binding(job["environmentId"]):
                    raise RequestError("Original assignment approval/environment generation expired; no decision was sent", 409)
                assignment_workspaces.job_environment(self, job, RequestError)
            try:
                driver.decide(request.get("requestId"), request.get("decision"))
            except (DriverError, queue.Full) as error:
                raise RequestError(str(error), 409)
        return self.get(jobid)

    def answer_question(self, jobid, request):
        """One exact ordinary input, recorded on this original job before any CLI write."""
        from remote_interactions import fingerprint
        if not isinstance(request, dict) or set(request) != {"answerId", "jobOperationId", "requestId", "requestHash", "answers"}:
            raise RequestError("Question input only accepts the exact original ids and answers")
        jobid, answer_id = identifier(jobid, "jobId"), identifier(request.get("answerId"), "answerId")
        identifier(request.get("requestId"), "requestId")
        identifier(request.get("jobOperationId"), "jobOperationId")
        if not isinstance(request.get("requestHash"), str) or not re.fullmatch(r"[a-f0-9]{64}", request["requestHash"]):
            raise RequestError("Original question fingerprint is invalid")
        if not isinstance(request.get("answers"), dict) or len(json.dumps(request["answers"], ensure_ascii=False).encode("utf-8")) > 16384:
            raise RequestError("Question answers are invalid or exceed the input budget")
        with self.lock:
            job = self.jobs.get(jobid)
            if job is None or job.get("operationId") != request["jobOperationId"]:
                raise RequestError("Question does not belong to the original job operation", 409)
            digest = fingerprint(request["answers"])
            for previous in job.get("questionReceipts", []):
                if previous.get("answerId") == answer_id:
                    if (previous.get("requestId"), previous.get("requestHash"), previous.get("answerHash")) != (request["requestId"], request["requestHash"], digest):
                        raise RequestError("Original answer id is already bound to other input", 409)
                    return self._public(job)  # Even unknown/queued is query-only, never another write.
            driver = self.drivers.get(jobid)
            if driver is None or job.get("assignmentWorkspace") or jobid in self.stopping or job.get("status") in TERMINAL or self.closed:
                raise RequestError("No live ordinary-session question authority; inspect or stop the original job", 409)
            try:
                driver.answer_question(request["requestId"], request["requestHash"], answer_id, request["answers"])
            except (DriverError, ValueError, queue.Full) as error:
                raise RequestError(str(error), 409)
            return self._public(job)

    def steer_message(self, jobid, message_id):
        jobid, message_id = identifier(jobid, "jobId"), identifier(message_id, "messageId")
        with self.lock:
            row = self.ledger.execute("SELECT job_id, receipt FROM steer_messages WHERE message_id=?", (message_id,)).fetchone()
            if row is None:
                return {"jobId": jobid, "operationId": message_id, "messageId": message_id, "status": "not_found", "message": "No message receipt found; this query did not send input"}
            if row[0] != jobid:
                raise RequestError("messageId belongs to another job", 409)
            return json.loads(row[1])

    def steer(self, jobid, request):
        jobid, message_id = identifier(jobid, "jobId"), identifier(request.get("messageId"), "messageId")
        expected = text(request.get("expectedTurnId"), "expectedTurnId", 200)
        content = text(request.get("text"), "text", 12_000)
        fingerprint = hashlib.sha256(json.dumps([jobid, expected, content, request.get("threadId"), request.get("jobOperationId")], ensure_ascii=False).encode()).hexdigest()
        with self.lock:
            row = self.ledger.execute("SELECT job_id, fingerprint FROM steer_messages WHERE message_id=?", (message_id,)).fetchone()
            if row is not None:
                if row != (jobid, fingerprint):
                    raise RequestError("messageId was already used with different input or target", 409)
                return self.steer_message(jobid, message_id)
            job, driver = self.jobs.get(jobid), self.drivers.get(jobid)
            if job is None:
                raise RequestError("Job not found", 404)
            if request.get("threadId", job.get("threadId")) != job.get("threadId") or request.get("jobOperationId", job.get("operationId")) != job.get("operationId"):
                raise RequestError("Steering identity does not match the original job/thread; no input was sent", 409)
            if job.get("worker") != "codex_app_server" or job["status"] not in {"running", "awaiting_approval"} or driver is None or jobid in self.stopping or self.closed or job.get("turnId") != expected or not driver.can_steer(expected):
                raise RequestError("Steering is unavailable or expectedTurnId is stale; no input was sent", 409)
            if self.ledger.execute("SELECT count(*) FROM steer_messages WHERE job_id=?", (jobid,)).fetchone()[0] >= 64:
                raise RequestError("This job has reached its 64-message steering limit", 429)
            value = {"jobId": jobid, "operationId": message_id, "jobOperationId": job.get("operationId"), "threadId": job["threadId"], "expectedTurnId": expected,
                     "messageId": message_id, "status": "queued", "dispatched": False, "updatedAt": self.now(), "message": "Input is durably queued; not yet accepted by Codex"}
            self.ledger.execute("INSERT INTO steer_messages VALUES (?, ?, ?, ?, ?)", (message_id, jobid, fingerprint, json.dumps(value), self.now()))
            self.ledger.commit()

            def receipt(status, fields):
                with self.lock:
                    value.update(fields, status=status, updatedAt=self.now())
                    self.ledger.execute("UPDATE steer_messages SET receipt=?, updated_at=? WHERE message_id=?", (json.dumps(value), self.now(), message_id))
                    self.ledger.commit()
                    job["lastSteerMessage"] = dict(value)
                    self._save(job)
            try:
                driver.steer({"messageId": message_id, "expectedTurnId": expected, "text": content}, receipt)
            except (DriverError, queue.Full):
                receipt("rejected", {"dispatched": False, "message": "Turn/queue became unavailable before input dispatch"})
            return self.steer_message(jobid, message_id)

    def workspace_files(self, eid, action, request):
        with self.lock:
            env = self._environment(identifier(eid, "environmentId"))
            if action == "write" and self.resource_id(env) in self.resources:
                raise RequestError("Workspace is executing another job; inspect its result before editing", 409)
            try:
                if action == "list":
                    return self.files.list(env, request.get("path", ""))
                if action == "read":
                    return self.files.read(env, request.get("path"))
                if action == "diff":
                    return self.files.diff(env, request.get("path"), request.get("fromVersion"))
                if action == "write":
                    return self.files.write(eid, env, request)
                raise RequestError("Unknown workspace file action")
            except FileError as error:
                raise RequestError(str(error), error.status)

    def artifact(self, jobid, relative):
        self.get(jobid)
        with self.lock:
            job = dict(self.jobs[jobid])
        env = assignment_workspaces.job_environment(self, job, RequestError)
        if job.get("resourceId") != self.resource_id(env):
            raise RequestError("Workspace binding changed; artifact access requires the original environment", 409)
        return self._artifact_bytes(env, relative)

    def _artifact_bytes(self, env, relative):
        relative = text(relative, "artifact path", 2048)
        if env.get("kind", "local") == "ssh":
            raise RequestError("Remote SSH artifacts are not yet supported; retrieve output or use a local Companion workspace", 409)
        if os.name == "nt" or not hasattr(os, "O_NOFOLLOW"):
            raise RequestError("Safe local artifact transfer is not available on this platform yet", 409)
        root = Path(env["root"])
        parts = Path(relative).parts
        if Path(relative).is_absolute() or not parts or ".." in parts:
            raise RequestError("Artifact is outside the configured workspace or missing", 403)
        directories = []
        try:
            directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            directories.append(directory)
            for part in parts[:-1]:
                directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                directories.append(directory)
            descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            with os.fdopen(descriptor, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise RequestError("Artifact must be a regular file", 403)
                if info.st_nlink != 1:
                    raise RequestError("Hard-linked files cannot prove an isolated workspace source", 403)
                if info.st_size > MAX_ARTIFACT:
                    raise RequestError("Artifact exceeds 256 KiB transfer limit", 413)
                before = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                value = stream.read(MAX_ARTIFACT + 1)
                after_info = os.fstat(stream.fileno())
                after = (after_info.st_dev, after_info.st_ino, after_info.st_size, after_info.st_mtime_ns, after_info.st_ctime_ns)
                if before != after:
                    raise RequestError("File changed while reading; no stable version was captured", 409)
        except OSError:
            raise RequestError("Artifact is missing or contains a symbolic link", 403)
        finally:
            for descriptor in reversed(directories):
                os.close(descriptor)
        if len(value) > MAX_ARTIFACT:
            raise RequestError("Artifact exceeds transfer limit", 413)
        sha = hashlib.sha256(value).hexdigest()
        version = hashlib.sha256(json.dumps([relative, before, sha], separators=(",", ":")).encode()).hexdigest()
        return {"name": parts[-1], "path": relative, "bytes": len(value), "base64": base64.b64encode(value).decode(), "sha256": sha, "fileVersion": version}

    @staticmethod
    def _snapshot_paths(paths):
        if not isinstance(paths, list) or not 1 <= len(paths) <= 8 or any(not isinstance(path, str) for path in paths) or len(set(paths)) != len(paths):
            raise RequestError("Select 1-8 unique explicit files; directories and recursive capture are not supported")
        denied = {".git", ".ssh", ".aws", ".gnupg", ".codex", ".claude", "node_modules", ".pocketworld-inputs", ".pocket-assignment.json"}
        for path in paths:
            parts = path.split("/")
            if not path or len(path) > 240 or "\\" in path or ":" in path or any(not part or part in {".", ".."} or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in part) for part in parts):
                raise RequestError("Snapshot file is outside the explicitly selected workspace", 403)
            if any(part.lower() in denied for part in parts) or any(part.lower().startswith(".env") for part in parts) or any(marker in parts[-1].lower() for marker in ("secret", "credential", "private_key", "id_rsa", "id_ed25519")):
                raise RequestError("Sensitive or private runtime paths are not eligible for result snapshots", 403)
        return paths

    @staticmethod
    def _snapshot_public(snapshot):
        value = json.loads(json.dumps({key: item for key, item in snapshot.items() if key not in {"fingerprint", "files"}}))
        value["files"] = [{key: item for key, item in file.items() if key != "base64"} for file in snapshot["files"]]
        return value

    @staticmethod
    def _snapshot_job_identity(job):
        return {key: job.get(key) for key in ("id", "operationId", "environmentId", "worker", "resourceId", "workspaceIdentity", "status", "updatedAt", "threadId", "sessionId", "turnId")}

    def _snapshot_environment(self, job):
        if job.get("status") not in {"completed", "failed", "cancelled"} or job.get("pendingApprovals"):
            raise RequestError("Only a settled original job can capture selected results; unknown/active effects must be reconciled", 409)
        env = dict(assignment_workspaces.job_environment(self, job, RequestError))
        if self.resource_id(env) != job.get("resourceId"):
            raise RequestError("The original job workspace binding changed", 409)
        if not job.get("workspaceIdentity") or job["workspaceIdentity"] != self.workspace_identity(env):
            raise RequestError("The original physical workspace identity is missing or changed; old jobs cannot invent it", 409)
        if job["resourceId"] in self.resources or any(other.get("resourceId") == job["resourceId"] and other.get("status") not in {"completed", "failed", "cancelled"} for other in self.jobs.values()):
            raise RequestError("Original workspace has active or unconfirmed work; no result version was frozen", 409)
        return env

    def capture_result_snapshot(self, jobid, request):
        allowed = {"operationId", "jobOperationId", "environmentId", "worker", "threadId", "turnId", "paths", "expectedFiles"}
        if set(request) - allowed:
            raise RequestError("Snapshot capture does not accept routing, credentials or arbitrary workspace parameters")
        operation = identifier(request.get("operationId"), "snapshot operationId")
        paths = self._snapshot_paths(request.get("paths"))
        expected = result_files.expected_files(request, paths, RequestError)
        fingerprint = hashlib.sha256(json.dumps(request, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        with self.lock:
            self.get(jobid)
            job = self.jobs[jobid]
            # A lost ACK is query-only; even changed current files never recapture this operation.
            for snapshot in job.get("resultSnapshots", []):
                if snapshot["operationId"] == operation:
                    if snapshot["fingerprint"] != fingerprint:
                        raise RequestError("Snapshot operationId is already bound to different exact files/source", 409)
                    return self._snapshot_public(snapshot)
            for key in ("environmentId", "worker"):
                if request.get(key) != job.get(key):
                    raise RequestError("Snapshot request does not match the original job environment/worker", 409)
            if request.get("jobOperationId") != job["operationId"] or any(key in request and request[key] != job.get(key) for key in ("threadId", "turnId")):
                raise RequestError("Snapshot request does not match the original job operation/session/turn", 409)
            snapshots = job.get("resultSnapshots", [])
            if len(snapshots) >= 4:
                raise RequestError("Original job already has four retained snapshots; none were silently replaced", 409)
            env = self._snapshot_environment(job)
            identity = self._snapshot_job_identity(job)
            files = [self._artifact_bytes(env, path) for path in paths]
            result_files.require_expected(files, expected, RequestError)
            total = sum(file["bytes"] for file in files)
            if total > 512 * 1024 or sum(snapshot["bytes"] for snapshot in snapshots) + total > 1024 * 1024:
                raise RequestError("Selected snapshot exceeds 512 KiB or original job 1 MiB total snapshot limit", 413)
            # A second exact content/stat read rejects replacement, edits and cross-file races.
            for file in files:
                after = self._artifact_bytes(env, file["path"])
                if any(after[key] != file[key] for key in ("fileVersion", "sha256", "bytes")):
                    raise RequestError("Selected file changed during snapshot capture; no snapshot was published", 409)
            self._snapshot_environment(job)
            if identity != self._snapshot_job_identity(job):
                raise RequestError("Original job identity changed during capture; no snapshot was published", 409)
            snapshot = {"snapshotId": str(uuid.uuid4()), "operationId": operation, "jobId": jobid, "jobOperationId": job["operationId"],
                        "environmentId": job["environmentId"], "worker": job["worker"], "status": "completed", "capturedAt": self.now(),
                        "captureSource": "selected_current_workspace_after_settled_original_job", "authorshipVerified": False,
                        "immutableArtifact": True, "bytes": total, "files": files, "fingerprint": fingerprint, "workspaceIdentity": job["workspaceIdentity"],
                        "consistency": "two_read_content_stat_fence", "atomicWorkspaceSnapshot": False}
            assignment_workspaces.contribution(self, job, snapshot, RequestError)
            for key in ("threadId", "sessionId", "turnId"):
                if job.get(key):
                    snapshot[key] = job[key]
            manifest = self._snapshot_public(snapshot)
            snapshot["snapshotVersion"] = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
            updated = dict(job)
            updated["resultSnapshots"] = snapshots + [snapshot]
            # Original atomic job document is the only durable artifact store for these bytes.
            self._save(updated)
            self.jobs[jobid] = updated
            return self._snapshot_public(snapshot)

    def result_snapshot_operation(self, jobid, operation):
        with self.lock:
            self.get(jobid)
            for snapshot in self.jobs[jobid].get("resultSnapshots", []):
                if snapshot["operationId"] == identifier(operation, "snapshot operationId"):
                    return self._snapshot_public(snapshot)
            return {"operationId": operation, "jobId": jobid, "jobOperationId": self.jobs[jobid]["operationId"], "status": "not_found", "resubmitted": False}

    def result_snapshot(self, jobid, snapshotid, version, path=None):
        with self.lock:
            self.get(jobid)
            snapshot = next((value for value in self.jobs[jobid].get("resultSnapshots", []) if value["snapshotId"] == snapshotid), None)
            if snapshot is None or snapshot["snapshotVersion"] != version:
                raise RequestError("Original immutable snapshot/version not found", 404)
            if path is None:
                return self._snapshot_public(snapshot)
            file = next((file for file in snapshot["files"] if file["path"] == path), None)
            if file is None:
                raise RequestError("File was not among the original explicitly selected snapshot paths", 403)
            result = {key: snapshot[key] for key in ("snapshotId", "snapshotVersion", "operationId", "jobId", "jobOperationId", "environmentId", "worker", "capturedAt", "captureSource", "authorshipVerified", "immutableArtifact", "workspaceIdentity", "consistency", "atomicWorkspaceSnapshot")}
            result.update(file)
            return result

    def _check_snapshot_continuation(self, request, prior, env):
        if "resultSnapshotId" not in request and "resultSnapshotVersion" not in request:
            return None
        if not request.get("resultSnapshotId") or not request.get("resultSnapshotVersion") or prior is None:
            raise RequestError("Snapshot continuation requires the exact original settled job and snapshot/version", 409)
        snapshot = self.result_snapshot(prior["id"], request["resultSnapshotId"], request["resultSnapshotVersion"])
        if prior.get("resourceId") != self.resource_id(env):
            raise RequestError("Snapshot continuation workspace changed", 409)
        if not prior.get("workspaceIdentity") or prior["workspaceIdentity"] != snapshot.get("workspaceIdentity") or prior["workspaceIdentity"] != self.workspace_identity(env):
            raise RequestError("Snapshot continuation physical workspace changed", 409)
        for file in snapshot["files"]:
            actual = self._artifact_bytes(env, file["path"])
            if any(actual[key] != file[key] for key in ("fileVersion", "sha256", "bytes")):
                raise RequestError("Snapshot source version changed; review current files before a new explicit continuation", 409)
        return snapshot

    def verify_result_snapshot_current(self, jobid, snapshotid, version):
        with self.lock:
            self.get(jobid)
            job = self.jobs[jobid]
            env = self._snapshot_environment(job)
            snapshot = self._check_snapshot_continuation({"resultSnapshotId": snapshotid, "resultSnapshotVersion": version}, job, env)
            return self._snapshot_public(snapshot) | {"matchesCurrent": True, "checkedAt": self.now(), "executionAuthorized": False}

    def close(self):
        with self.lock:
            self.closed = True
            running = [j["id"] for j in self.jobs.values() if j["status"] not in TERMINAL]
        for jobid in running:
            self.cancel(jobid)
        self.pool.shutdown(wait=True, cancel_futures=False)
        with self.lock:
            self.ledger.close()


class CompanionServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, manager):
        self.manager = manager
        super().__init__(address, CompanionHandler)


class CompanionHandler(BaseHTTPRequestHandler):
    server_version = "PocketEngineCompanion/0.2"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_args):
        pass

    def _send(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _route(self, post):
        try:
            self.connection.settimeout(15)
            expected = "Bearer " + self.server.manager.token
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected.encode()):
                raise RequestError("Unauthorized", 401)
            url = urlsplit(self.path)
            parts = [unquote(x) for x in url.path.strip("/").split("/")]
            if any("/" in part for part in parts):
                raise RequestError("Invalid path")
            request = {}
            if post:
                length = self.headers.get("Content-Length", "")
                body_limit = 2_097_152 if len(parts) == 4 and parts[:2] == ["v1", "workspaces"] and parts[3] == "file" else MAX_BODY
                if not length.isdigit() or not 0 < int(length) <= body_limit:
                    raise RequestError("Request body is missing or too large", 413)
                try:
                    request = json.loads(self.rfile.read(int(length)))
                except (ValueError, UnicodeDecodeError):
                    raise RequestError("Invalid JSON")
                if not isinstance(request, dict):
                    raise RequestError("Request JSON must be an object")
            manager = self.server.manager
            if not post and parts == ["v1", "status"]:
                result = {"name": "Pocket World Companion", "protocolVersion": 1, "sshTransport": "system_openssh", "directPhoneSsh": False, "jobsPersistent": True, "processSandbox": False,
                          "localArtifactTransfer": os.name != "nt" and hasattr(os, "O_NOFOLLOW"), "sshArtifactTransfer": False,
                          "operationReconciliation": True, "perWorkspaceExecutionLease": True}
            elif not post and parts == ["v1", "environments"]:
                result = manager.list_environments()
            elif post and parts == ["v1", "workers", "probe"]:
                result = manager.probe_worker(request)
            elif len(parts) == 3 and parts[:2] == ["v1", "operations"] and not post:
                result = manager.operation(parts[2])
            elif len(parts) == 3 and parts[:2] == ["v1", "file-operations"] and not post:
                with manager.lock:
                    result = manager.files.operation(identifier(parts[2], "operationId"))
            elif len(parts) == 4 and parts[:2] == ["v1", "workspaces"]:
                query = {key: values[0] for key, values in parse_qs(url.query).items()}
                action = "write" if post and parts[3] == "file" else "list" if not post and parts[3] == "files" else "read" if not post and parts[3] == "file" else "diff" if not post and parts[3] == "diff" else None
                if action is None:
                    raise RequestError("Endpoint not found", 404)
                result = manager.workspace_files(parts[2], action, request if post else query)
            elif parts == ["v1", "jobs"]:
                result = manager.submit(request) if post else manager.list_jobs()
            elif len(parts) == 3 and parts[:2] == ["v1", "jobs"] and not post:
                result = manager.get(parts[2])
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "approval" and post:
                result = manager.approve(parts[2], request)
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "question-answer" and post:
                result = manager.answer_question(parts[2], request)
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "steer" and post:
                result = manager.steer(parts[2], request)
            elif len(parts) == 5 and parts[:2] == ["v1", "jobs"] and parts[3] == "messages" and not post:
                result = manager.steer_message(parts[2], parts[4])
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "cancel" and post:
                result = manager.cancel(parts[2])
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "result-files" and not post:
                result = result_files.list_files(manager, parts[2], {key: values[0] for key, values in parse_qs(url.query).items()}, RequestError)
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "artifact" and not post:
                result = manager.artifact(parts[2], parse_qs(url.query).get("path", [""])[0])
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "start-with-inputs" and post:
                result = input_files.start(manager, parts[2], request, RequestError)
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "snapshots" and post:
                result = manager.capture_result_snapshot(parts[2], request)
            elif len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "assignment-workspaces" and post:
                result = assignment_workspaces.prepare(manager, parts[2], request, RequestError)
            elif len(parts) == 5 and parts[:2] == ["v1", "jobs"] and parts[3] == "assignment-operations" and not post:
                result = assignment_workspaces.operation(manager, parts[2], parts[4], RequestError)
            elif len(parts) == 5 and parts[:2] == ["v1", "jobs"] and parts[3] == "snapshot-operations" and not post:
                result = manager.result_snapshot_operation(parts[2], parts[4])
            elif len(parts) == 5 and parts[:2] == ["v1", "jobs"] and parts[3] == "snapshots" and not post:
                query = parse_qs(url.query)
                version = query.get("version", [""])[0]
                result = manager.verify_result_snapshot_current(parts[2], parts[4], version) if query.get("current", [""])[0] == "true" else manager.result_snapshot(parts[2], parts[4], version, query.get("path", [None])[0])
            else:
                raise RequestError("Endpoint not found", 404)
            self._send(200, {"ok": True, "result": result})
        except RequestError as error:
            self._send(error.status, {"ok": False, "message": str(error)})
        except (OSError, ValueError, TimeoutError, sqlite3.Error):
            self._send(500, {"ok": False, "message": "Companion could not process the request"})

    def do_GET(self):
        self._route(False)

    def do_POST(self):
        self._route(True)

    def do_PUT(self):
        # Exactly one bounded raw body, on the existing authenticated job endpoint.
        try:
            expected = "Bearer " + self.server.manager.token
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), expected.encode()):
                raise RequestError("Unauthorized", 401)
            url = urlsplit(self.path)
            parts = [unquote(value) for value in url.path.strip("/").split("/")]
            if len(parts) != 5 or parts[:2] != ["v1", "jobs"] or parts[3] != "inputs" or not re.fullmatch("[0-7]", parts[4]) or url.query or any("/" in part for part in parts):
                raise RequestError("Exact original input endpoint required", 404)
            length = self.headers.get("Content-Length", "")
            if self.headers.get("Transfer-Encoding") or not length.isdigit() or not 0 <= int(length) <= input_files.MAX_FILE:
                raise RequestError("Raw private input requires an exact bounded Content-Length", 413)
            if self.headers.get_content_type() != "application/octet-stream":
                raise RequestError("Private input must be binary octet-stream")
            operation = self.headers.get("X-Pocket-Job-Operation", "")
            result = input_files.upload(self.server.manager, parts[2], int(parts[4]), operation, int(length), self.rfile, RequestError)
            self._send(200, {"ok": True, "result": result})
        except RequestError as failure:
            self.close_connection = True  # An unaccepted body is never consumed or reused as a request.
            self._send(failure.status, {"ok": False, "message": str(failure)})
        except (OSError, ValueError, TimeoutError, sqlite3.Error):
            self.close_connection = True
            self._send(500, {"ok": False, "message": "Original binary input is unconfirmed; query it without retransmission"})


def initialize(path: Path, workspace: str):
    if path.exists():
        raise ValueError("Configuration already exists")
    root = Path(workspace).expanduser().resolve()
    if not root.is_dir():
        raise ValueError("Workspace directory is missing")
    path.parent.mkdir(parents=True, exist_ok=True)
    token_path = path.parent / "companion-token.txt"
    descriptor = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(secrets.token_urlsafe(36))
    config = {"listen": "127.0.0.1", "port": 8766, "tokenFile": str(token_path), "stateDir": str(path.parent / "jobs"),
              "environments": [{"id": "computer", "label": "我的电脑", "kind": "local", "root": str(root), "workers": ["command"],
                                "commands": {"git": "git"}, "writeAllowed": False}]}
    atomic_json(path, config)
    print("Created configuration and private token file. Enter the token on your phone; it is not printed here.")


def main():
    parser = argparse.ArgumentParser(description="PocketEngine owner Companion")
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--init", action="store_true")
    parser.add_argument("--workspace", default=".")
    args = parser.parse_args()
    if args.init:
        initialize(args.config.expanduser().resolve(), args.workspace)
        return
    config = json.loads(args.config.expanduser().read_text(encoding="utf-8"))
    host = config.get("listen", "127.0.0.1")
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = host == "localhost"
    tls = bool(config.get("tlsCert") and config.get("tlsKey"))
    if not loopback and (not config.get("allowRemote", False) or not tls):
        raise ValueError("Non-loopback binding requires allowRemote=true and TLS certificate/key")
    token_path = Path(config["tokenFile"]).expanduser()
    if os.name != "nt" and token_path.stat().st_mode & 0o077:
        raise ValueError("Token file must not be accessible to other users (chmod 600)")
    manager = JobManager(config, token_path.read_text(encoding="utf-8").strip())
    server = CompanionServer((host, int(config.get("port", 8766))), manager)
    if tls:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(config["tlsCert"], config["tlsKey"])
        server.socket = context.wrap_socket(server.socket, server_side=True)
    print("Pocket World Companion listening; authentication required; press Ctrl+C to stop.")
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        manager.close()


if __name__ == "__main__":
    main()
