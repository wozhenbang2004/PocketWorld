"""Explicit ordinary-job project baselines on the original job/snapshot/file-operation stores.

Captures public source under one owner-selected configured project, not a disk backup,
an exclusive author claim, an OS sandbox, or a conversation/file rollback transaction.
"""
import base64
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid
import assignment_projects
import cold_session
from workspace_files import FileError

PROTOCOL = "ordinary_project_history_v1"
MAX_FILES, MAX_FILE, MAX_BYTES = 2000, 262144, 8388608
MAX_RETAINED, MAX_MANIFEST = 67108864, 524288
PRIVATE = assignment_projects.PRIVATE | {".pocketworld-inputs", ".idea", ".vscode", "build", "dist", "target"}


def capability(env):
    return {"protocol": PROTOCOL, "supported": env.get("kind", "local") == "local" and os.name != "nt" and hasattr(os, "O_NOFOLLOW"),
            "maxFiles": MAX_FILES, "maxFileBytes": MAX_FILE, "maxTotalBytes": MAX_BYTES,
            "maxRetainedBytes": MAX_RETAINED, "privateFilesExcluded": True, "binaryVersions": True,
            "prelaunchBaseline": True, "selectedFileRestore": bool(env.get("writeAllowed")),
            "conversationForkRestoresFiles": False, "atomicWorkspaceSnapshot": False,
            "osReadIsolationVerified": False, "exclusiveAuthorshipVerified": False}


def spec(request, env, error):
    if "projectHistory" not in request:
        return None
    value = request["projectHistory"]
    if not capability(env)["supported"] or request.get("worker") not in {"codex_app_server", "claude"} or request.get("assignmentWorkspace") or request.get("inputFiles"):
        raise error("Explicit ordinary project history is unavailable for this environment/job mode", 409)
    if not isinstance(value, dict) or set(value) != {"protocol", "projectRoot", "publicSourceReadAcknowledged", "externalChangesMayBeUnattributed"} or value.get("protocol") != PROTOCOL or value.get("publicSourceReadAcknowledged") is not True or value.get("externalChangesMayBeUnattributed") is not True:
        raise error("An exact owner-reviewed public project baseline scope is required", 409)
    root = value.get("projectRoot")
    if root != "":
        assignment_projects.path(root, error)
    if not isinstance(root, str) or len(root.encode("utf-8")) > 240:
        raise error("Explicit project root must be a bounded configured-workspace-relative path")
    selected = Path(env["root"]) / root
    if selected == Path(selected.anchor) or selected == Path.home() or Path(env["root"]) == Path.home():
        raise error("A user home or whole disk is not a project-history scope", 403)
    return json.loads(json.dumps(value))


def _copy(value):
    return json.loads(json.dumps(value))


def _file_version(info, raw):
    return hashlib.sha256(json.dumps([info.st_dev, info.st_ino, info.st_mode, info.st_mtime_ns, info.st_ctime_ns, len(raw), hashlib.sha256(raw).hexdigest()], separators=(",", ":")).encode()).hexdigest()


def _read(parent, name, error):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    with os.fdopen(descriptor, "rb") as source:
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > MAX_FILE:
            raise error("Project source is linked, special, or exceeds 256KiB", 409)
        raw = source.read(MAX_FILE + 1)
        after = os.fstat(source.fileno())
        current = os.stat(name, dir_fd=parent, follow_symlinks=False)
        same = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        if len(raw) != before.st_size or same(before) != same(after) or same(after) != same(current):
            raise error("Project file changed while capturing actual bytes", 409)
        return raw, {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "fileVersion": _file_version(after, raw), "executable": bool(after.st_mode & 0o111)}


def _allowed(relative, error):
    if any(part.lower() in PRIVATE or part.lower().startswith(".pocket-") for part in relative.split("/")):
        return False
    try:
        assignment_projects.path(relative, error)
        if any(part.lower().startswith(".env") for part in relative.split("/")) or any(word in relative.rsplit("/", 1)[-1].lower() for word in ("secret", "credential", "private_key")):
            return False
        return True
    except error as failure:
        if failure.status == 403:
            return False
        raise


def _root(manager, job, error):
    env = dict(manager._environment(job["environmentId"]))
    if job.get("projectEnvironmentConfigHash") != cold_session._scope(job, env)["environmentConfigHash"] or job.get("workspaceIdentity") != manager.workspace_identity(env):
        raise error("Original project environment/physical workspace changed", 409)
    parts = manager.files.parts(job["projectHistoryScope"]["projectRoot"], empty=True)
    root = manager.files.directory(env, parts)
    actual = os.fstat(root)
    identity = hashlib.sha256(json.dumps([actual.st_dev, actual.st_ino], separators=(",", ":")).encode()).hexdigest()
    original = job.get("projectHistory", {}).get("projectIdentity")
    if original is not None and original != identity:
        os.close(root)
        raise error("Original selected physical project directory was replaced", 409)
    return root, identity


def scan(manager, job, error, current):
    root, identity = _root(manager, job, error)
    files, excluded, total, visited = [], 0, 0, 0
    deadline = time.monotonic() + 10
    def visit(directory, prefix, depth):
        nonlocal excluded, total, visited
        current()
        if depth > 40 or time.monotonic() > deadline:
            raise error("Project scan exceeds its bounded depth/read window; no partial baseline is accepted", 413)
        names = os.listdir(directory)
        if len(names) > 5000:
            raise error("Project directory exceeds the explicit bounded file window", 413)
        before = os.fstat(directory)
        for name in sorted(names):
            current()
            visited += 1
            if visited > 10000:
                raise error("Project source entry bound exceeded; no truncated baseline", 413)
            relative = prefix + name
            if not _allowed(relative, error):
                excluded += 1
                continue
            full = job["projectHistoryScope"]["projectRoot"] + "/" + relative if job["projectHistoryScope"]["projectRoot"] else relative
            if ":" in full or len(full.encode("utf-8")) > 240:
                raise error("Public project path exceeds the native exact-version path contract; no partial baseline", 413)
            info = os.stat(name, dir_fd=directory, follow_symlinks=False)
            if stat.S_ISLNK(info.st_mode):
                raise error("Symbolic links inside selected public source are not captured", 409)
            if stat.S_ISDIR(info.st_mode):
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                try:
                    if (os.fstat(child).st_dev, os.fstat(child).st_ino) != (info.st_dev, info.st_ino):
                        raise error("Project directory changed during capture", 409)
                    visit(child, relative + "/", depth + 1)
                finally:
                    os.close(child)
            elif stat.S_ISREG(info.st_mode):
                raw, metadata = _read(directory, name, error)
                total += len(raw)
                files.append({"path": relative, **metadata, "base64": base64.b64encode(raw).decode("ascii")})
                if len(files) > MAX_FILES or total > MAX_BYTES:
                    raise error("Project public source exceeds 2000 files/8MiB; nothing is silently omitted", 413)
            else:
                raise error("Special project filesystem entries are unsupported", 409)
        after = os.fstat(directory)
        if (before.st_dev, before.st_ino, before.st_mtime_ns, before.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns):
            raise error("Project directory changed during capture", 409)
    try:
        visit(root, "", 0)
        current()
        manifest = [{key: value for key, value in file.items() if key != "base64"} for file in files]
        encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        if len(encoded) > MAX_MANIFEST:
            raise error("Project manifest exceeds its bound; no truncated baseline", 413)
        return {"projectIdentity": identity, "files": files, "bytes": total, "fileCount": len(files), "excludedEntries": excluded, "manifestHash": hashlib.sha256(encoded).hexdigest()}
    except OSError as failure:
        raise error("Project source changed, disappeared, or cannot be read without following links", 409) from failure
    finally:
        os.close(root)


def _snapshot(manager, job, captured, phase):
    selected = job["projectHistoryScope"]["projectRoot"]
    files = [{**file, "path": selected + "/" + file["path"] if selected else file["path"]} for file in captured["files"]]
    return {"protocol": PROTOCOL, "projectPhase": phase, "snapshotId": str(uuid.uuid4()), "snapshotVersion": captured["manifestHash"],
            "operationId": job["operationId"], "jobId": job["id"], "jobOperationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"],
            "capturedAt": manager.now(), "captureSource": "owner_selected_public_project_" + phase,
            "authorshipVerified": False, "immutableArtifact": True, "workspaceIdentity": job["workspaceIdentity"],
            "projectIdentity": captured["projectIdentity"], "consistency": "bounded_double_read_not_atomic_workspace_or_os_isolation", "atomicWorkspaceSnapshot": False,
            "files": files, "bytes": captured["bytes"], "excludedEntries": captured["excludedEntries"], "fileCount": captured["fileCount"]}


def before(manager, job, error):
    def current():
        with manager.lock:
            if manager.closed or job["id"] in manager.stopping or job.get("processStarted") or job["status"] not in {"queued", "preparing_baseline"} or job["projectEnvironmentBinding"] != manager.environment_binding(job["environmentId"]):
                raise error("Project baseline cancelled before dispatch; no worker was started", 409)
    with manager.lock:
        retained = 0
        for value in manager.jobs.values():
            if value["id"] != job["id"] and value.get("resourceId") == job["resourceId"] and value.get("status") in {"unknown", "interrupted"} and not cold_session.proof_matches(value, manager._environment(value["environmentId"])):
                raise error("Original workspace has unconfirmed work; reconcile that original job before capturing a new baseline", 409)
            actual = sum(snapshot.get("bytes", 0) for snapshot in value.get("resultSnapshots", []) if snapshot.get("protocol") == PROTOCOL)
            # Existing resource/process/preparing state reserves the unfinished comparison,
            # not a second byte-budget table. Queued jobs have not captured anything yet.
            active = value["id"] != job["id"] and value.get("projectHistoryScope") and (value.get("status") == "preparing_baseline" or value["id"] in manager.processes)
            retained += max(actual, MAX_BYTES * 2) if active else actual
        if retained + MAX_BYTES * 2 > MAX_RETAINED:
            raise error("Retained project versions reached 64MiB; no old immutable version is evicted", 413)
        job.update(status="preparing_baseline", message="Capturing the explicitly selected public project before worker dispatch")
        manager._save(job)
    first = scan(manager, job, error, current)
    second = scan(manager, job, error, current)
    if first != second:
        raise error("Project changed between baseline reads; no worker was started", 409)
    with manager.lock:
        current()
        frozen = _snapshot(manager, job, first, "before_worker")
        job.setdefault("resultSnapshots", []).append(frozen)
        job["projectHistory"] = {"protocol": PROTOCOL, "projectRoot": job["projectHistoryScope"]["projectRoot"], "projectIdentity": first["projectIdentity"], "baseline": {key: value for key, value in frozen.items() if key not in {"files", "fingerprint"}}, "comparisonStatus": "awaiting_original_worker", "authorshipVerified": False, "sameUserExternalWritersMayExist": True}
        manager._save(job)


def after(manager, job, error):
    if not job.get("projectHistory"):
        return
    def current():
        with manager.lock:
            if manager.closed or job["status"] not in {"completed", "failed", "cancelled"} or job.get("pendingApprovals") or job["id"] not in manager.jobs:
                raise error("Original project execution is not settled for comparison", 409)
    try:
        first, second = scan(manager, job, error, current), scan(manager, job, error, current)
        if first != second:
            raise error("Project changed between final reads; no atomic/complete diff is claimed", 409)
        frozen = _snapshot(manager, job, first, "after_worker")
        with manager.lock:
            current()
            job.setdefault("resultSnapshots", []).append(frozen)
            job["projectHistory"].update(after={key: value for key, value in frozen.items() if key not in {"files", "fingerprint"}}, comparisonStatus="available", comparedAt=manager.now())
            manager._save(job)
    except (OSError, error) as failure:
        with manager.lock:
            job["projectHistory"].update(comparisonStatus="unavailable", reason=str(failure)[:300])
            manager._save(job)


def summary(job):
    value = _copy(job.get("projectHistory", {"protocol": PROTOCOL, "comparisonStatus": "not_selected"}))
    for key in ("baseline", "after"):
        if key in value:
            value[key].pop("files", None)
    return value


def read(manager, jobid, query, error):
    if set(query) - {"snapshotId", "snapshotVersion", "path", "offset", "limit", "currentPath", "diffPath", "pageVersion"}:
        raise error("Project history read accepts no replacement workspace or execution scope")
    with manager.lock:
        job = manager.jobs.get(jobid)
        if job is None or not job.get("projectHistory"):
            raise error("No original pre-worker project baseline is retained", 404)
        history = job["projectHistory"]
        before = next(value for value in job["resultSnapshots"] if value["snapshotId"] == history["baseline"]["snapshotId"])
        after = next((value for value in job["resultSnapshots"] if value["snapshotId"] == history.get("after", {}).get("snapshotId")), None) if history.get("comparisonStatus") == "available" else None
        if "currentPath" in query:
            return current_file(manager, job, query["currentPath"], error)
        if query.get("snapshotId"):
            snapshot = next((value for value in (before, after) if value and value["snapshotId"] == query["snapshotId"] and value["snapshotVersion"] == query.get("snapshotVersion")), None)
            if snapshot is None:
                raise error("Original exact project snapshot/version does not exist", 404)
            file = next((value for value in snapshot["files"] if value["path"] == query.get("path")), None)
            if file is None:
                raise error("File is not in the original selected project version", 403)
            return {"jobId": jobid, "jobOperationId": job["operationId"], "snapshotId": snapshot["snapshotId"], "snapshotVersion": snapshot["snapshotVersion"], "phase": snapshot["projectPhase"], **_copy(file)}
        offset, limit = query.get("offset", "0"), query.get("limit", "100")
        if not re.fullmatch(r"[0-9]{1,5}", offset) or not re.fullmatch(r"[0-9]{1,3}", limit) or not 1 <= int(limit) <= 100:
            raise error("Project history page must use a bounded original offset/limit")
        old, new = {file["path"]: file for file in before["files"]}, {file["path"]: file for file in after["files"]} if after else {}
        if "diffPath" in query:
            path = query["diffPath"]
            if after is None or path not in old.keys() | new.keys():
                raise error("No immutable before/after pair exists for this original path", 409)
            try:
                first = base64.b64decode(old[path]["base64"], validate=True).decode("utf-8") if path in old else ""
                second = base64.b64decode(new[path]["base64"], validate=True).decode("utf-8") if path in new else ""
                delta = "".join(difflib.unified_diff(first.splitlines(keepends=True), second.splitlines(keepends=True), fromfile=before["snapshotId"] + "/" + path, tofile=after["snapshotId"] + "/" + path))
                return {"jobId": jobid, "jobOperationId": job["operationId"], "path": path, "beforeSnapshotId": before["snapshotId"], "afterSnapshotId": after["snapshotId"], "binary": False, "diff": delta[:65536], "diffTruncated": len(delta) > 65536, "authorshipVerified": False}
            except UnicodeDecodeError:
                return {"jobId": jobid, "jobOperationId": job["operationId"], "path": path, "binary": True, "diff": None, "beforeSha256": old.get(path, {}).get("sha256", "absent"), "afterSha256": new.get(path, {}).get("sha256", "absent"), "authorshipVerified": False}
        page_version = hashlib.sha256(json.dumps([before["snapshotId"], before["snapshotVersion"], after["snapshotId"] if after else None, after["snapshotVersion"] if after else None], separators=(",", ":")).encode()).hexdigest()
        if int(offset) > 0 and query.get("pageVersion") != page_version or query.get("pageVersion") is not None and query["pageVersion"] != page_version:
            raise error("Original immutable project comparison head changed; read from offset zero, never guess missing rows", 409)
        rows = []
        reported = set(job.get("reportedFileChanges", []))
        for path in sorted(old.keys() | new.keys()):
            baseline, actual = old.get(path), new.get(path)
            changed = after is not None and (baseline or {}).get("sha256") != (actual or {}).get("sha256")
            row = {"path": path, "change": "added" if actual and not baseline else "deleted" if baseline and after and not actual else "modified" if changed else "unchanged" if after else "baseline_only",
                   "before": {key: value for key, value in baseline.items() if key != "base64"} if baseline else None, "after": {key: value for key, value in actual.items() if key != "base64"} if actual else None,
                   "workerReportedPath": path in reported, "authorshipVerified": False, "attribution": "worker_protocol_report_not_exclusive_author" if path in reported else "unattributed_observed_workspace_change" if changed else "no_authorship_claim"}
            rows.append(row)
        return {"jobId": jobid, "jobOperationId": job["operationId"], "history": summary(job), "pageVersion": page_version, "offset": int(offset), "nextOffset": min(len(rows), int(offset) + int(limit)), "hasMore": int(offset) + int(limit) < len(rows), "totalFiles": len(rows), "files": rows[int(offset):int(offset) + int(limit)], "source": "original_immutable_before_after_snapshots", "executionAuthorized": False, "fileRestoreAuthorized": False}


def current_file(manager, job, path, error):
    """Only a path already frozen by this exact native-selected project can be read."""
    if not any(path == file["path"] for snapshot in job.get("resultSnapshots", []) if snapshot.get("protocol") == PROTOCOL for file in snapshot["files"]):
        raise error("Current path is not in an original immutable project version", 403)
    root = job["projectHistoryScope"]["projectRoot"]
    relative = path[len(root) + 1:] if root and path.startswith(root + "/") else path if not root else None
    if relative is None or not _allowed(relative, error):
        raise error("Current file escaped the original selected public project", 403)
    directory, _identity = _root(manager, job, error)
    parent = None
    try:
        parent, name = assignment_projects._parent(directory, relative, False)
        try:
            _raw, metadata = _read(parent, name, error)
            return {"jobId": job["id"], "jobOperationId": job["operationId"], "path": path, "workspaceIdentity": job["workspaceIdentity"], **metadata, "contentVersion": "sha256:" + metadata["sha256"], "readAt": manager.now()}
        except FileNotFoundError:
            return {"jobId": job["id"], "jobOperationId": job["operationId"], "path": path, "workspaceIdentity": job["workspaceIdentity"], "bytes": 0, "sha256": "absent", "contentVersion": "absent", "fileVersion": "absent", "readAt": manager.now()}
    except OSError as failure:
        raise error("Current original file is linked, special, or its parent no longer exists", 409) from failure
    finally:
        if parent is not None:
            os.close(parent)
        os.close(directory)


def restore(manager, jobid, request, error):
    keys = {"operationId", "jobOperationId", "snapshotId", "snapshotVersion", "path", "expectedVersion", "expectedFileVersion", "environmentBinding", "approvalExpiresAt"}
    if not isinstance(request, dict) or set(request) != keys or not re.fullmatch(r"[a-f0-9-]{36}", request.get("operationId", "")):
        raise error("One exact native-reviewed original file/version restore is required")
    with manager.resource_changed:
        job = manager.jobs.get(jobid)
        if job is None or job["operationId"] != request["jobOperationId"] or not job.get("projectHistory") or job.get("assignmentWorkspace"):
            raise error("File restore does not belong to this original ordinary project job", 409)
        snapshot = next((value for value in job.get("resultSnapshots", []) if value.get("protocol") == PROTOCOL and value["snapshotId"] == request["snapshotId"] and value["snapshotVersion"] == request["snapshotVersion"]), None)
        file = next((value for value in snapshot["files"] if value["path"] == request["path"]), None) if snapshot else None
        if file is None:
            raise error("Selected exact immutable project file/version is not retained", 404)
        raw = base64.b64decode(file["base64"], validate=True)
        if len(raw) != file["bytes"] or hashlib.sha256(raw).hexdigest() != file["sha256"]:
            raise error("Retained original binary version failed its actual hash", 409)
        write = {**request, "projectHistoryProtocol": PROTOCOL, "jobId": jobid, "desiredSha256": file["sha256"], "desiredExecutable": file["executable"]}
        # Original file_operations handles both acknowledged and uncertain duplicates. Do not
        # re-read/re-approve a new current version, re-create a temp file, or resend bytes.
        prior = manager.files.operation(request["operationId"])
        env = dict(manager._environment(job["environmentId"]))
        if prior.get("status") != "not_found":
            return manager.files.restore_original(job["environmentId"], env, write, raw, lambda: None)
        if not isinstance(request["approvalExpiresAt"], int) or isinstance(request["approvalExpiresAt"], bool) or not manager.now() < request["approvalExpiresAt"] <= manager.now() + 120000:
            raise error("One-time native file restore review expired", 409)
        manager._snapshot_environment(job)
        if request["environmentBinding"] != manager.environment_binding(job["environmentId"]) or not env.get("writeAllowed"):
            raise error("Original environment generation/write permission changed", 409)
        def current():
            if manager.closed or request["approvalExpiresAt"] <= manager.now() or request["environmentBinding"] != manager.environment_binding(job["environmentId"]) or job["status"] not in {"completed", "failed", "cancelled"}:
                raise FileError("Original file restore scope expired/revoked before replacement", 409)
            try:
                actual = current_file(manager, job, request["path"], error)
            except error as failure:
                raise FileError(str(failure), failure.status) from failure
            if actual["contentVersion"] != request["expectedVersion"] or actual["fileVersion"] != request["expectedFileVersion"]:
                raise FileError("Original current file drifted from the native-reviewed CAS baseline", 409)
        resource = job["resourceId"]
        manager.resources.add(resource)
        try:
            return manager.files.restore_original(job["environmentId"], env, write, raw, current)
        except FileError as failure:
            raise error(str(failure), failure.status) from failure
        finally:
            manager.resources.discard(resource)
            manager.resource_changed.notify_all()
