"""Selected-file assignment copies and contributions on the original Companion job records.

No team scheduler/database, project scan, merge, push or OS-isolation claim lives here.
The authenticated native host owns assignment approval/reservation; this module enforces the
bound physical copy, exact source/files, per-job limits, and original operation identities.
"""
import base64
import difflib
import hashlib
import json
import os
from pathlib import Path
import stat
import uuid
import harness_registry


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


def _copy(value):
    return json.loads(json.dumps(value))


def _id(value, field, error):
    try:
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise error("Original native UUID missing: " + field)
    return value


def limits(value, error):
    if not isinstance(value, dict) or set(value) - {"maxWallTimeSeconds", "maxOutputBytes", "maxContributionBytes", "hardTokenLimit", "hardCostLimit"}:
        raise error("Explicit supported external_job limits are required")
    if value.get("hardTokenLimit") is not None or value.get("hardCostLimit") is not None:
        raise error("This coding CLI cannot hard-limit internal model token/cost; an independent external_job approval is required", 409)
    selected = {}
    for key, low, high in [("maxWallTimeSeconds", 10, 900), ("maxOutputBytes", 1024, 196608), ("maxContributionBytes", 1024, 524288)]:
        number = value.get(key)
        if isinstance(number, bool) or not isinstance(number, int) or not low <= number <= high:
            raise error("Unsupported external_job bound: " + key)
        selected[key] = number
    return selected


def native_tuple(value, error):
    required = {"taskId", "runId", "coordinatorEpoch", "parentTaskId", "parentRunId", "parentEpoch", "teamRunId", "teamGeneration", "assignmentId", "assignmentRevision", "memberId", "memberGeneration", "attemptId"}
    if not isinstance(value, dict) or set(value) != required:
        raise error("Original assignment/parent/generation tuple is incomplete; it cannot downgrade to private execution")
    for key in ["taskId", "runId", "parentTaskId", "parentRunId", "teamRunId", "assignmentId", "attemptId"]:
        _id(value[key], key, error)
    for key, low in [("coordinatorEpoch", 1), ("parentEpoch", 1), ("teamGeneration", 0), ("assignmentRevision", 1), ("memberGeneration", 0)]:
        if isinstance(value[key], bool) or not isinstance(value[key], int) or value[key] < low:
            raise error("Invalid native assignment generation: " + key)
    member = value["memberId"]
    if not isinstance(member, str) or not 1 <= len(member) <= 80 or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for char in member):
        raise error("Invalid original member ID")
    return _copy(value)


def _records(manager, source_job):
    return manager.jobs[source_job].get("assignmentWorkspaces", [])


def _root(manager, checkout_id):
    return manager.state / "assignment-workspaces" / checkout_id


def _save(manager, source_job, record):
    job = dict(manager.jobs[source_job])
    previous = _records(manager, source_job)
    job["assignmentWorkspaces"] = [record if row["checkoutId"] == record["checkoutId"] else row for row in previous]
    if not any(row["checkoutId"] == record["checkoutId"] for row in previous):
        job["assignmentWorkspaces"].append(record)
    manager._save(job)
    manager.jobs[source_job] = job


def public(record):
    return {key: _copy(value) for key, value in record.items() if key not in {"requestFingerprint", "baseFiles", "marker", "root"}}


def lookup(manager, source_job, checkout_id):
    manager.get(source_job)
    return next((row for row in _records(manager, source_job) if row["checkoutId"] == checkout_id), None)


def environment(manager, record, error):
    root = _root(manager, record["checkoutId"])
    owner = manager._environment(record["environmentId"])
    env = {**owner, "id": record["environmentId"], "kind": "local", "root": str(root),
           "workers": harness_registry.production_workers(owner), "writeAllowed": True, "_fullProjectNative": True}
    if record.get("checkoutIdentity") != manager.workspace_identity(env):
        raise error("Original assignment physical checkout changed; no job or write is authorized", 409)
    descriptor = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        marker = os.open(".pocket-assignment.json", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        with os.fdopen(marker, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 16384:
                raise error("Assignment marker is not the original bounded native proof", 409)
            actual = json.loads(stream.read(16385))
        if actual != record["marker"]:
            raise error("Assignment source/operation marker changed", 409)
    finally:
        os.close(descriptor)
    return env


def _write_new(root, relative, raw):
    parts = relative.split("/")
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            try:
                os.mkdir(part, 0o700, dir_fd=directory)
            except FileExistsError:
                pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(parts[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.fsync(directory)
    finally:
        os.close(directory)


def prepare(manager, source_job, request, error):
    allowed = {"operationId", "nativeAssignment", "snapshotId", "snapshotVersion", "files", "environmentBinding", "limits", "approvalRevision", "approvalExpiresAt"}
    if set(request) != allowed:
        raise error("Assignment preparation requires exact native selection/approval, no arbitrary route or directory")
    operation = _id(request["operationId"], "prepare operation", error)
    binding = native_tuple(request["nativeAssignment"], error)
    bounds = limits(request["limits"], error)
    fingerprint = digest(request)
    with manager.lock:
        manager.get(source_job)
        for prior in _records(manager, source_job):
            if prior["prepareOperationId"] == operation:
                if prior["requestFingerprint"] != fingerprint:
                    raise error("Original prepare operation is already bound to a different scope/base", 409)
                return public(prior)
            if prior["nativeAssignment"] == binding:
                raise error("This assignment attempt already owns prepare operation " + prior["prepareOperationId"] + "; query it, do not create another copy", 409)
        if len(_records(manager, source_job)) >= 8:
            raise error("Original job has eight retained assignment copies; none were silently discarded", 409)
        source = manager.jobs[source_job]
        if source.get("status") not in {"completed", "failed", "cancelled"}:
            raise error("Assignment base must come from a settled original job", 409)
        if request["environmentBinding"] != manager.environment_binding(source["environmentId"]):
            raise error("Selected environment generation changed", 409)
        original_env = manager._environment(source["environmentId"])
        if original_env.get("kind", "local") != "local" or os.name == "nt" or not hasattr(os, "O_NOFOLLOW"):
            raise error("Selected-file assignment copies currently require a POSIX local Companion environment", 409)
        snapshot = next((value for value in source.get("resultSnapshots", []) if value["snapshotId"] == request["snapshotId"] and value["snapshotVersion"] == request["snapshotVersion"]), None)
        if snapshot is None:
            raise error("Original immutable base snapshot/version not found", 409)
        files = request["files"]
        if not isinstance(files, list) or not 1 <= len(files) <= 8:
            raise error("Select 1-8 exact existing text files, not a full-project scan")
        paths = manager._snapshot_paths([file.get("path") for file in files if isinstance(file, dict)])
        if len(paths) != len(files):
            raise error("Assignment file scope is malformed")
        if any(path.split("/")[-1] == ".pocket-assignment.json" for path in paths):
            raise error("Assignment marker is reserved native metadata, not a contribution file", 403)
        selected = []
        for file in files:
            if set(file) != {"path", "baseHash", "baseVersion"} or isinstance(file["baseVersion"], bool) or not isinstance(file["baseVersion"], int) or file["baseVersion"] < 0:
                raise error("Native original file baseline/version is incomplete")
            base = next((value for value in snapshot["files"] if value["path"] == file["path"]), None)
            if base is None or file["baseHash"] != base["sha256"]:
                raise error("Native project baseline does not match the selected immutable remote bytes", 409)
            raw = base64.b64decode(base["base64"], validate=True)
            if hashlib.sha256(raw).hexdigest() != base["sha256"] or len(raw) != base["bytes"]:
                raise error("Stored immutable base bytes no longer match their manifest", 409)
            try:
                raw.decode("utf-8")
            except UnicodeDecodeError:
                raise error("This selected-file contribution slice requires actual UTF-8 text; binary/Git projects remain unsupported", 409)
            selected.append({**_copy(base), "appBaseVersion": file["baseVersion"]})
        if isinstance(request["approvalRevision"], bool) or not isinstance(request["approvalRevision"], int) or request["approvalRevision"] < 1 or not isinstance(request["approvalExpiresAt"], int) or request["approvalExpiresAt"] <= manager.now():
            raise error("Native selected-copy approval is missing or expired", 409)
        checkout = str(uuid.uuid5(uuid.NAMESPACE_URL, "pocket-engine/assignment-copy/v1/" + source_job + "/" + operation))
        marker = {"checkoutId": checkout, "sourceJobId": source_job, "prepareOperationId": operation, "assignmentHash": digest(binding), "baseSnapshotId": snapshot["snapshotId"], "baseSnapshotVersion": snapshot["snapshotVersion"]}
        record = {"checkoutId": checkout, "prepareOperationId": operation, "operationId": operation, "sourceJobId": source_job, "sourceJobOperationId": source["operationId"], "environmentId": source["environmentId"], "environmentBinding": request["environmentBinding"], "nativeAssignment": binding,
                  "baseSnapshotId": snapshot["snapshotId"], "baseSnapshotVersion": snapshot["snapshotVersion"], "sourceWorkspaceIdentity": snapshot["workspaceIdentity"], "limits": bounds, "files": [{key: value for key, value in file.items() if key != "base64"} for file in selected], "baseFiles": selected,
                  "requestFingerprint": fingerprint, "marker": marker, "status": "preparing", "workspace": str(_root(manager, checkout)), "mode": "selected_file_copy_not_git_project", "osReadIsolationVerified": False, "fullProject": False, "createdAt": manager.now(), "approvalRevision": request["approvalRevision"]}
        _save(manager, source_job, record)  # Original durable job owns the intent before file creation.
        try:
            parent = manager.state / "assignment-workspaces"
            parent.mkdir(mode=0o700, exist_ok=True)
            if parent.is_symlink():
                raise error("Assignment storage parent cannot be a symlink", 403)
            root = _root(manager, checkout)
            root.mkdir(mode=0o700)
            for file in selected:
                _write_new(root, file["path"], base64.b64decode(file["base64"], validate=True))
            _write_new(root, ".pocket-assignment.json", json.dumps(marker, sort_keys=True).encode())
            record["checkoutIdentity"] = manager.workspace_identity({"kind": "local", "root": str(root)})
            env = environment(manager, record, error)
            for file in selected:
                actual = manager._artifact_bytes(env, file["path"])
                if actual["sha256"] != file["sha256"] or actual["bytes"] != file["bytes"]:
                    raise error("Prepared assignment copy differs from immutable base", 409)
            record["status"] = "ready"
            record["checkoutVersion"] = digest({key: record[key] for key in ("checkoutId", "checkoutIdentity", "nativeAssignment", "baseSnapshotId", "baseSnapshotVersion", "files", "limits")})
            _save(manager, source_job, record)
        except (OSError, ValueError, error):
            record["status"] = "unknown"
            _save(manager, source_job, record)
            raise
        return public(record)


def operation(manager, source_job, operation_id, error):
    with manager.lock:
        manager.get(source_job)
        value = next((row for row in _records(manager, source_job) if row["prepareOperationId"] == operation_id), None)
        if value is None:
            return {"operationId": operation_id, "sourceJobId": source_job, "status": "not_found", "resubmitted": False}
        if value["status"] in {"preparing", "unknown"}:
            # Reconcile only existing exact marker+files. Never create, repair or re-copy on GET.
            recovered = _copy(value)
            try:
                recovered["checkoutIdentity"] = manager.workspace_identity({"kind": "local", "root": str(_root(manager, value["checkoutId"]))})
                env = environment(manager, recovered, error)
                for file in recovered["baseFiles"]:
                    actual = manager._artifact_bytes(env, file["path"])
                    if actual["sha256"] != file["sha256"] or actual["bytes"] != file["bytes"]:
                        raise error("Incomplete assignment copy remains unknown; GET never repairs it", 409)
                recovered["checkoutVersion"] = digest({key: recovered[key] for key in ("checkoutId", "checkoutIdentity", "nativeAssignment", "baseSnapshotId", "baseSnapshotVersion", "files", "limits")})
                recovered["status"] = "ready"
                _save(manager, source_job, recovered)
                value = recovered
            except (OSError, ValueError, error):
                value = {**value, "status": "unknown"}
        if value["status"] == "ready":
            environment(manager, value, error)
        return public(value)


def record_for_reference(manager, reference, error):
    if reference.get("mode") == "phone_project_package_independent_git":
        import assignment_projects
        return assignment_projects.record_for_reference(manager, reference, error)
    record = lookup(manager, reference["sourceJobId"], reference["checkoutId"])
    if record is None:
        raise error("Original assignment checkout missing", 409)
    return record


def bound_environment(manager, request, original_env, error):
    reference = request.get("assignmentWorkspace")
    if isinstance(reference, dict) and reference.get("mode") == "phone_project_package_independent_git":
        import assignment_projects
        return assignment_projects.bound_environment(manager, request, error)
    if not isinstance(reference, dict) or set(reference) != {"sourceJobId", "checkoutId", "checkoutVersion", "nativeAssignment", "preparationNativeAssignment", "reservationId", "approvalRevision", "approvalExpiresAt"}:
        raise error("Assignment execution requires native approved exact checkout/job reservation", 409)
    record = lookup(manager, reference["sourceJobId"], reference["checkoutId"])
    if record is None or record["status"] != "ready" or record["checkoutVersion"] != reference["checkoutVersion"] or record["nativeAssignment"] != native_tuple(reference["preparationNativeAssignment"], error):
        raise error("Assignment checkout or native generation changed", 409)
    dispatch = native_tuple(reference["nativeAssignment"], error)
    for key in ["taskId", "parentTaskId", "parentRunId", "parentEpoch", "teamRunId", "assignmentId", "assignmentRevision", "memberId"]:
        if dispatch[key] != record["nativeAssignment"][key]:
            raise error("A resumed dispatch cannot change the original assignment/member/parent/base scope", 409)
    if request.get("environmentId") != record["environmentId"] or request.get("environmentBinding") != manager.environment_binding(record["environmentId"]):
        raise error("Original selected environment generation changed", 409)
    worker = request.get("worker")
    if worker not in harness_registry.production_workers(original_env) or request.get("readOnly") is not False or request.get("threadId") or request.get("resumeJobId"):
        raise error("Assignment execution requires a new enabled coding-harness job in this exact isolated copy", 409)
    if worker != "codex_app_server" and request.get("externalExecutionRiskAcknowledged") is not True:
        raise error("This assignment harness requires explicit owner-managed execution acknowledgement", 409)
    _id(reference["reservationId"], "external_job reservation", error)
    if isinstance(reference["approvalRevision"], bool) or not isinstance(reference["approvalRevision"], int) or reference["approvalRevision"] < record["approvalRevision"] or isinstance(reference["approvalExpiresAt"], bool) or not isinstance(reference["approvalExpiresAt"], int) or reference["approvalExpiresAt"] <= manager.now():
        raise error("Original native external_job approval expired or changed", 409)
    if request.get("environmentBinding") != record["environmentBinding"] and reference["approvalRevision"] <= record["approvalRevision"]:
        raise error("A restarted/changed environment requires a new explicit native approval, not restoration of the old one", 409)
    if request.get("timeoutSeconds") != record["limits"]["maxWallTimeSeconds"]:
        raise error("Job walltime differs from original independent external_job approval", 409)
    for previous in manager.jobs.values():
        if previous.get("assignmentWorkspace", {}).get("checkoutId") == record["checkoutId"]:
            raise error("This assignment copy already has an original coding job; query its operation instead of spawning another", 409)
    return environment(manager, record, error), record


def job_environment(manager, job, error):
    reference = job.get("assignmentWorkspace")
    if reference is None:
        return manager._environment(job["environmentId"])
    if reference.get("mode") == "phone_project_package_independent_git":
        import assignment_projects
        return assignment_projects.environment(manager, assignment_projects.record_for_reference(manager, reference, error), error)
    record = lookup(manager, reference["sourceJobId"], reference["checkoutId"])
    if record is None or record["checkoutVersion"] != reference["checkoutVersion"] or record["nativeAssignment"] != reference["preparationNativeAssignment"]:
        raise error("Original assignment job lost its exact physical checkout binding", 409)
    return environment(manager, record, error)


def contribution(manager, job, snapshot, error):
    reference = job.get("assignmentWorkspace")
    if reference is None:
        return
    if reference.get("mode") == "phone_project_package_independent_git":
        raise error("Use the original full-project contribution protocol; no UTF-8 selected-file fallback", 409)
    record = lookup(manager, reference["sourceJobId"], reference["checkoutId"])
    if record is None or record["checkoutVersion"] != reference["checkoutVersion"]:
        raise error("Original contribution checkout/base proof is missing", 409)
    allowed = {file["path"]: file for file in record["baseFiles"]}
    changes = []
    total = snapshot["bytes"]
    for file in snapshot["files"]:
        base = allowed.get(file["path"])
        if base is None:
            raise error("Contribution path is outside this native assignment", 403)
        try:
            before = base64.b64decode(base["base64"], validate=True).decode("utf-8")
            after = base64.b64decode(file["base64"], validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError):
            raise error("Contribution is not the explicitly supported UTF-8 text scope", 409)
        patch = "".join(difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True), fromfile="base/" + file["path"], tofile="contribution/" + file["path"]))
        total += len(patch.encode())
        changes.append({"path": file["path"], "baseSha256": base["sha256"], "baseFileVersion": base["fileVersion"], "appBaseVersion": base["appBaseVersion"], "sha256": file["sha256"], "fileVersion": file["fileVersion"], "bytes": file["bytes"], "diff": patch, "changed": base["sha256"] != file["sha256"]})
    if total > record["limits"]["maxContributionBytes"]:
        raise error("Actual contribution bytes plus real diff exceed the approved bound; no partial contribution was frozen", 413)
    snapshot["assignmentContribution"] = {"nativeAssignment": reference["nativeAssignment"], "preparationNativeAssignment": record["nativeAssignment"], "checkoutId": record["checkoutId"], "checkoutVersion": record["checkoutVersion"], "checkoutIdentity": record["checkoutIdentity"], "baseSnapshotId": record["baseSnapshotId"], "baseSnapshotVersion": record["baseSnapshotVersion"], "reservationId": reference["reservationId"], "files": changes,
                                           "source": "actual_selected_file_base_to_frozen_bytes", "integrated": False, "independentlyReviewed": False, "fullProject": False, "osReadIsolationVerified": False}
