"""Read-only recovery of the original locally supervised Codex turn.
The original job document is the only durable record. No PID supplied by a request is used.
A stopped transport/PID alone never proves a turn terminal or authorizes a new turn.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid

PROTOCOL = "codex_stored_turn_recovery_v1"
GROUP_PROTOCOL = "original_posix_process_group_v1"
COLD_ROOT_RETIREMENT = "explicit_original_codex_cold_root_retirement_v1"
CONFIG_KEYS = ("kind", "root", "host", "user", "port", "writeAllowed", "workers", "commands", "knownHosts", "identityFile", "sshConfig")


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def _boot():
    value = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    if not re.fullmatch(r"[a-f0-9-]{36}", value):
        raise ValueError("Kernel boot identity is unavailable")
    return value


def _stat(pid):
    path = Path("/proc") / str(pid)
    text = (path / "stat").read_text()
    values = text.rsplit(")", 1)[1].split()
    return {"pid": int(text.split("(", 1)[0]), "state": values[0], "pgid": int(values[2]), "sessionId": int(values[3]), "startTicks": int(values[19]), "uid": path.stat().st_uid}


def _scope(job, env, configuration_home=True):
    scope = {"jobId": job["id"], "jobOperationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"],
            "resourceId": job["resourceId"], "workspaceIdentity": job.get("workspaceIdentity"), "requestFingerprint": job["fingerprint"],
            "environmentConfigHash": _digest({key: env.get(key) for key in CONFIG_KEYS})}
    if configuration_home and _configured_home(job):
        scope.update(configurationHomeHash=_digest(job.get("_coldConfigHome")), harnessDefinitionsHash=_digest(env.get("harnesses", [])))
    return scope


def _capture_group(job, env, process, protocol):
    try:
        identity = _stat(process.pid)
        if identity["pid"] != process.pid or identity["pgid"] != process.pid or identity["sessionId"] != process.pid or identity["uid"] != os.getuid():
            return None
        return {"format": protocol, "bootId": _boot(), "pidNamespace": [os.stat("/proc/self/ns/pid").st_dev, os.stat("/proc/self/ns/pid").st_ino], "process": identity, "scope": _scope(job, env, configuration_home=protocol == PROTOCOL)}
    except (OSError, ValueError, IndexError):
        return None


def _configured_home(job):
    return any(key in job for key in ("harnessConfigOperationId", "_harnessConfigRequest"))


def _unproven_config_home(job):
    pin = job.get("_coldConfigHome")
    return _configured_home(job) and (not isinstance(pin, dict) or pin.get("format") != "codex_config_home_v1" or pin.get("operationId") != job.get("harnessConfigOperationId") or pin.get("ownerBindingDigest") != _digest(job.get("_harnessConfigRequest")))


def pin_configuration(manager, job, env, argv, cwd, child_env):
    """Freeze the original admitted home before spawn, without changing a lease.

    An unprovable/nonstandard launch simply has no cold continuation support. The
    generic original process-group capture remains available for read/cancel.
    """
    if not _configured_home(job) or job.get("worker") != "codex_app_server" or os.name == "nt" or env.get("kind", "local") != "local":
        return None
    from harness_config import ConfigError
    try:
        pin = manager.harness_configs.cold_codex_source(job, env)
        search_path = os.pathsep.join(str(Path(part) if os.path.isabs(part) else Path(cwd) / part) for part in os.get_exec_path(child_env))
        executable = shutil.which(argv[0], path=search_path) if not os.path.isabs(argv[0]) else argv[0]
        if argv[1:] != ["app-server"] or cwd != env["root"] or executable is None or str(Path(executable).resolve()) != pin["executable"] or child_env.get("CODEX_HOME") != pin["home"]:
            return None
        return pin
    except (ConfigError, OSError, ValueError, KeyError, TypeError):
        return None


def _configuration_environment(manager, job, env, error):
    if not _configured_home(job):
        return {}
    from harness_config import ConfigError
    try:
        if _unproven_config_home(job) or manager.harness_configs.cold_codex_source(job, env) != job["_coldConfigHome"]:
            raise ConfigError("Original configuration home/source pin changed")
        return {"CODEX_HOME": job["_coldConfigHome"]["home"]}
    except (ConfigError, OSError, ValueError, KeyError, TypeError) as failure:
        job["coldRecoveryRead"] = {"status": "unconfirmed", "checkedAt": manager.now(), "reason": "Original configuration home/source is not proven; no turn was started"}
        manager._save(job)
        raise error("Original Harness configuration home/source is not proven; read/cancel only, no default CODEX_HOME probe was started", 409) from failure


def same_configuration_home(source, child):
    if not _configured_home(source):
        return not _configured_home(child)
    first, second = source.get("_coldConfigHome"), child.get("_coldConfigHome")
    return isinstance(first, dict) and isinstance(second, dict) and all(first.get(key) == second.get(key) for key in ("home", "rootIdentity", "bindingId", "executable", "executableIdentity"))


def capture(job, env, process):
    """Called only with the Popen created by the original _run, before its first running save."""
    if os.name == "nt" or env.get("kind", "local") != "local" or job.get("worker") != "codex_app_server" or job.get("assignmentWorkspace") or job.get("inputState") or _unproven_config_home(job):
        return None
    return _capture_group(job, env, process, PROTOCOL)


def capture_process_group(job, env, process):
    """Containment facts only. This never grants stored-turn inspection/resume/fork."""
    if os.name == "nt" or env.get("kind", "local") != "local" or job.get("assignmentWorkspace") or job.get("inputState"):
        return None
    return _capture_group(job, env, process, GROUP_PROTOCOL)


def original_process_group_stopped(job, env):
    return _captured_group_stopped(job.get("_originalProcessGroup"), GROUP_PROTOCOL, job, env, require_absent=True)


def process_stopped(job, env):
    """Fail closed. Never signal a cold PID/group, and never call a reused PID our worker."""
    return _captured_group_stopped(job.get("_coldProcess"), PROTOCOL, job, env)


def _captured_group_stopped(captured, protocol, job, env, require_absent=False):
    if not isinstance(captured, dict) or captured.get("format") != protocol or captured.get("scope") != _scope(job, env, configuration_home=protocol == PROTOCOL):
        return False
    try:
        original = captured["process"]
        if not isinstance(original.get("pid"), int) or original["pid"] <= 1 or original["pgid"] != original["pid"] or original["sessionId"] != original["pid"]:
            return False
        if captured["bootId"] != _boot():
            return False  # A different host/container copied the state? Do not invent same-host proof.
        if captured.get("pidNamespace") != [os.stat("/proc/self/ns/pid").st_dev, os.stat("/proc/self/ns/pid").st_ino]:
            return False
        try:
            current = _stat(original["pid"])
            if all(current[key] == original[key] for key in ("pid", "pgid", "sessionId", "startTicks", "uid")) and current["state"] not in {"Z", "X"}:
                return False
        except FileNotFoundError:
            pass
        try:
            os.killpg(original["pgid"], 0)  # Existence check only; no termination authority is inferred.
        except ProcessLookupError:
            return True
        except OSError:
            return False
        if require_absent:
            return False  # A cold generic group needs ESRCH, never a racing empty proc scan.
        entries = [path for path in Path("/proc").iterdir() if path.name.isdigit()]
        if len(entries) > 4096:
            return False
        for path in entries:
            try:
                actual = _stat(int(path.name))
            except FileNotFoundError:
                continue
            if actual["pgid"] == original["pgid"] and actual["state"] not in {"Z", "X"}:
                return False
        return True
    except (OSError, ValueError, KeyError, IndexError, TypeError):
        return False


def eligible(job):
    origin = job.get("_coldOrigin", {})
    return not _unproven_config_home(job) and job.get("worker") == "codex_app_server" and job.get("status") == "interrupted" and origin.get("previousStatus") in {"running", "awaiting_approval", "awaiting_input"} and isinstance(job.get("_coldProcess"), dict) and not job.get("assignmentWorkspace") and not job.get("inputState") and isinstance(job.get("threadId"), str) and isinstance(job.get("turnId"), str)


def proof_matches(job, env):
    proof = job.get("recoveryProof")
    read = job.get("coldRecoveryRead", {})
    if read.get("status") != "verified" or not isinstance(proof, dict) or read.get("proofId") != proof.get("proofId") or any(value.get("status") in {"queued", "sending", "unknown"} for value in job.get("approvalReceipts", [])):
        return False
    if not eligible(job) or not isinstance(proof, dict) or proof.get("format") != PROTOCOL or not process_stopped(job, env):
        return False
    expected = {"jobId": job["id"], "jobOperationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"], "threadId": job["threadId"], "turnId": job["turnId"], "workspaceIdentity": job["workspaceIdentity"], "environmentConfigHash": _scope(job, env)["environmentConfigHash"]}
    if _configured_home(job):
        expected["configurationHomeHash"] = _digest(job["_coldConfigHome"])
    return all(proof.get(key) == value for key, value in expected.items()) and proof.get("processGroupStoppedVerified") is True and proof.get("storedLatestTurnVerified") is True and proof.get("readOnlyInspection") is True and proof.get("turnStartedByInspection") is False and proof.get("observedTurnStatus") in {"completed", "failed", "interrupted"} and isinstance(proof.get("proofId"), str) and re.fullmatch(r"[a-f0-9-]{36}", proof["proofId"]) is not None


def root_retirement_evidence(manager, job, proof_id, error):
    """Final read-only gate for the explicit owner's original cancellation.

    Caller holds manager.lock. The ordinary interrupted status and every cold
    continuation gate remain intact; this is not a task-completion transition.
    """
    env = manager._environment(job["environmentId"])
    if manager.closed or job["id"] in manager.stopping or job.get("status") != "interrupted" or not _configured_home(job) or not proof_matches(job, env) or job["recoveryProof"].get("proofId") != proof_id:
        raise error("Original configured cold proof changed; root retirement is not authorized", 409)
    if job["id"] in manager.processes or job["id"] in manager.drivers or job.get("resourcesHeld") is True or job["resourceId"] in manager.resources or manager.workspace_identity(env) != job["workspaceIdentity"]:
        raise error("Original worker/workspace is still held; no configuration root was retired", 409)
    first, second = job.get("_coldProcess", {}), job.get("_originalProcessGroup", {})
    if any(first.get(key) != second.get(key) for key in ("bootId", "pidNamespace")) or any(first.get("process", {}).get(key) != second.get("process", {}).get(key) for key in ("pid", "pgid", "sessionId", "startTicks", "uid")) or not original_process_group_stopped(job, env):
        raise error("Exact original group absence requires ESRCH; no cold PID was signalled or root retired", 409)
    if any(value.get("status") in {"queued", "sending", "unknown"} for value in job.get("approvalReceipts", []) + job.get("questionReceipts", [])) or job.get("steeringUnknown"):
        raise error("Original approval/input acknowledgement remains unresolved; no root was retired", 409)
    if any(json.loads(raw).get("status") in {"queued", "sending", "unknown"} for (raw,) in manager.ledger.execute("SELECT receipt FROM steer_messages WHERE job_id=?", (job["id"],)).fetchall()):
        raise error("Original turn input remains unresolved; no root was retired", 409)
    if any(other["id"] != job["id"] and other.get("threadId") == job["threadId"] and (other.get("status") not in {"completed", "failed", "cancelled"} or other.get("resumeJobId") == job["id"]) for other in manager.jobs.values()):
        raise error("Original thread already has another invocation; no root was retired", 409)
    _configuration_environment(manager, job, env, error)
    return {"protocol": COLD_ROOT_RETIREMENT, "retirementId": str(uuid.uuid4()), "jobId": job["id"], "jobOperationId": job["operationId"],
            "jobStatus": "interrupted", "explicitOriginalOwnerCancellation": True, "configurationHomePin": job["_coldConfigHome"],
            "storedTurnProof": job["recoveryProof"], "coldProcess": first, "originalProcessGroup": second,
            "processCleanupProof": {"processGroupStoppedVerified": True, "verificationMethod": "original_pgid_esrch", "detachedDescendantIsolationVerified": False},
            "oldEndpointRevived": False, "newExecutionAuthorized": False, "effectsReversed": False}


def retire_configuration_root(manager, jobid, operation, error):
    """Only the exact cancel-original endpoint may call this owner action."""
    with manager.lock:
        job = manager._original_cancel_job_locked(jobid, operation)
        finished = manager.harness_configs.cold_root_retirement(job)
        if finished is not None:
            job["harnessColdRootRetirement"] = finished
            manager._save(job)
            return manager._public(job)
        expected = manager.harness_configs.active_cold_root(job)
        if expected is None:
            raise error("Original active configuration root identity is unavailable; no root was retired", 409)
        previous_proof = job.get("recoveryProof")
        proof_id = previous_proof.get("proofId") if isinstance(previous_proof, dict) else None
        root_retirement_evidence(manager, job, proof_id, error)
        original = (job["operationId"], _scope(job, manager._environment(job["environmentId"])))
    # Refresh the exact stored terminal turn without retaining the manager monitor
    # across CLI IO. Inspection itself never releases or reauthorizes the root.
    inspected = inspect(manager, jobid, error)
    with manager.lock:
        job = manager._original_cancel_job_locked(jobid, operation)
        if original != (job["operationId"], _scope(job, manager._environment(job["environmentId"]))):
            raise error("Original configuration/job changed during cancellation review; no root was retired", 409)
        proof_id = inspected["recoveryProof"]["proofId"]
        evidence = root_retirement_evidence(manager, job, proof_id, error)
        finished = manager.harness_configs.retire_cold_root(job, expected, evidence)
        job.update(harnessColdRootRetirement=finished, message="Owner explicitly retired the stopped original configuration root; job remains interrupted and prior effects/usage unverified. New owner restore/configuration/scope/budget are still required", updatedAt=manager.now())
        manager._save(job)
        return manager._public(job)


def inspect(manager, jobid, error):
    """Explicit authenticated owner read. No thread/resume, turn/start or old decision is sent."""
    process = None
    driver = None
    acquired = False
    resource = None
    with manager.lock:
        job = manager.jobs.get(jobid)
        if job is not None and _unproven_config_home(job):
            raise error("Original Harness configuration home is not proven for cold recovery; read/cancel only, no default CODEX_HOME probe was started", 409)
        if job is None or not eligible(job):
            raise error("Original cold recovery requires a newly pinned interrupted local Codex invocation; this worker/old job is read/cancel only", 409)
        env = dict(manager._environment(job["environmentId"]))
        if manager.closed or jobid in manager.stopping or manager.workspace_identity(env) != job["workspaceIdentity"] or not process_stopped(job, env):
            raise error("Original worker/group or workspace is not confirmed stopped and unchanged; inspect/stop it on the computer, never start a replacement", 409)
        overrides = _configuration_environment(manager, job, env, error)
        if any(value.get("status") in {"queued", "sending", "unknown"} for value in job.get("approvalReceipts", []) + job.get("questionReceipts", [])) or job.get("steeringUnknown"):
            raise error("Original approval/input/steering acknowledgement is unknown; read recovery cannot clear it", 409)
        for (raw,) in manager.ledger.execute("SELECT receipt FROM steer_messages WHERE job_id=?", (jobid,)).fetchall():
            if json.loads(raw).get("status") in {"queued", "sending", "unknown"}:
                raise error("Original turn input is unresolved; no replacement turn is authorized", 409)
        resource = job["resourceId"]
        if resource in manager.resources or any(other["id"] != jobid and other.get("threadId") == job["threadId"] and (other.get("status") not in {"completed", "failed", "cancelled"} or other.get("resumeJobId") == jobid) for other in manager.jobs.values()):
            raise error("Original workspace or stored thread already has another invocation; query its original job", 409)
        if not manager.probe_slots.acquire(blocking=False):
            raise error("Read-only worker inspections are busy", 429)
        acquired = True
        manager.resources.add(resource)
        original = (job["operationId"], job["threadId"], job["turnId"], _scope(job, env))
    try:
        from codex_driver import CodexAppServerDriver, DriverError
        import subprocess
        argv = manager._worker_argv({"worker": "codex_app_server", "prompt": "Read original stored turn only"}, env)
        if overrides:
            argv[0] = job["_coldConfigHome"]["executable"]
        launch, cwd = manager._launch(env, argv)
        child_env = dict(os.environ)
        child_env.pop("POCKETENGINE_COMPANION_TOKEN", None)
        child_env.update(overrides)
        process = subprocess.Popen(launch, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=child_env, bufsize=0, start_new_session=True)
        driver = CodexAppServerDriver(process, env["root"], False, lambda _value: None, lambda: manager.closed, 10)
        try:
            observed = driver.read_original_turn(job["threadId"], job["turnId"])
        except DriverError as failure:
            with manager.lock:
                if not manager.closed and original == (job["operationId"], job["threadId"], job["turnId"], _scope(job, manager._environment(job["environmentId"]))):
                    # Keep prior observations as history, but do not present old proof as a fresh
                    # continuation option after a new unsupported/changed/failed protocol read.
                    job["coldRecoveryRead"] = {"status": "unconfirmed", "checkedAt": manager.now(), "reason": "Original stored thread/turn is unavailable, active, changed, or unsupported; no turn was started"}
                    manager._save(job)
            raise error("Original stored thread/turn is unavailable, active, changed, or unsupported; read/cancel only, no replacement was started", 409) from failure
        with manager.lock:
            if manager.closed or jobid in manager.stopping or not eligible(job) or original != (job["operationId"], job["threadId"], job["turnId"], _scope(job, manager._environment(job["environmentId"]))) or manager.workspace_identity(env) != job["workspaceIdentity"] or not process_stopped(job, env):
                raise error("Original job/process/configuration changed during read-only inspection; no recovery was authorized", 409)
            _configuration_environment(manager, job, env, error)
            proof = {"format": PROTOCOL, "proofId": str(uuid.uuid4()), "jobId": jobid, "jobOperationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"], "threadId": job["threadId"], "turnId": job["turnId"], "workspaceIdentity": job["workspaceIdentity"], "environmentConfigHash": original[3]["environmentConfigHash"], "processIdentityHash": _digest(job["_coldProcess"]), "processGroupStoppedVerified": True, "storedLatestTurnVerified": True, "readOnlyInspection": True, "turnStartedByInspection": False, "observedTurnStatus": observed["status"], "observedThreadStatus": observed["threadStatus"], "checkedAt": manager.now(), "internalUsageKnown": False, "businessSuccessVerified": False, "detachedDescendantIsolationVerified": False}
            if overrides:
                proof["configurationHomeHash"] = _digest(job["_coldConfigHome"])
            job["coldRecoveryRead"] = {"status": "verified", "proofId": proof["proofId"], "checkedAt": proof["checkedAt"], "reason": "Only original process/turn termination verified; old results/usage were not recovered"}
            job.update(recoveryProof=proof, message="Original process/group stopped and exact latest stored Codex turn is terminal; original job remains interrupted. A NEW explicit owner scope/run/budget is required to continue", updatedAt=manager.now())
            try:
                manager._save(job)
            except Exception:
                job.pop("recoveryProof", None)
                raise
            return manager._public(job)
    finally:
        if driver is not None:
            driver.close_steering()
        if process is not None:
            manager._terminate(process)
            for stream in (process.stdin, process.stdout):
                if stream is not None:
                    stream.close()
        if acquired:
            with manager.lock:
                manager.resources.discard(resource)
                manager.resource_changed.notify_all()
            manager.probe_slots.release()
