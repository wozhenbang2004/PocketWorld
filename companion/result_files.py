"""Bounded metadata browsing for one settled original job, never a recursive capture."""
import hashlib
import json
import os
import re
import stat
from workspace_files import FileError

PAGE = 100
MAX_ENTRIES = 10_000


def expected_files(request, paths, error):
    if "expectedFiles" not in request:
        return None  # The existing explicit-path snapshot protocol stays compatible.
    supplied = request["expectedFiles"]
    if not isinstance(supplied, list) or len(supplied) != len(paths):
        raise error("Reviewed file versions must exactly cover the selected paths", 409)
    result = []
    for path, item in zip(paths, supplied):
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256", "fileVersion"} or item.get("path") != path:
            raise error("Reviewed file versions do not match the selected paths", 409)
        size = item["bytes"]
        if isinstance(size, bool) or not isinstance(size, int) or not 0 <= size <= 262_144:
            raise error("Reviewed file size is outside the original transfer bound", 413)
        if any(not isinstance(item[key], str) or not re.fullmatch("[a-f0-9]{64}", item[key]) for key in ("sha256", "fileVersion")):
            raise error("Reviewed file digest/version is invalid", 409)
        result.append(dict(item))
    return result


def require_expected(files, expected, error):
    if expected is not None and (len(files) != len(expected) or any(
        any(actual.get(key) != reviewed[key] for key in ("path", "bytes", "sha256", "fileVersion"))
        for actual, reviewed in zip(files, expected)
    )):
        raise error("A selected file changed after review; no old-version snapshot was captured", 409)


def _physical(manager, job, env, root, error):
    info = os.fstat(root)
    identity = hashlib.sha256(json.dumps([env["root"], info.st_dev, info.st_ino], separators=(",", ":")).encode()).hexdigest()
    if identity != job.get("workspaceIdentity"):
        raise error("The original physical workspace was replaced", 409)


def _version(info):
    return hashlib.sha256(json.dumps(
        [info.st_dev, info.st_ino, info.st_mtime_ns, info.st_ctime_ns], separators=(",", ":")
    ).encode()).hexdigest()


def list_files(manager, jobid, query, error):
    if set(query) - {"path", "offset", "directoryVersion"}:
        raise error("Directory reads accept only an original relative path and bounded page cursor")
    relative = query.get("path", "")
    raw_offset = query.get("offset", "0")
    if not isinstance(raw_offset, str) or not re.fullmatch(r"[0-9]{1,5}", raw_offset):
        raise error("Invalid directory page offset")
    offset = int(raw_offset)
    if offset >= MAX_ENTRIES:
        raise error("Directory inspection limit reached; select a narrower directory", 413)
    supplied_version = query.get("directoryVersion", "")
    if supplied_version and not re.fullmatch("[a-f0-9]{64}", supplied_version):
        raise error("Invalid original directory version")
    if offset and not supplied_version:
        raise error("Continuing a page requires the exact original directory version", 409)
    try:
        parts = manager.files.parts(relative, empty=True)
        if relative:
            manager._snapshot_paths([relative + "/checked-file"])
        with manager.lock:
            manager.get(jobid)
            job = manager.jobs[jobid]
            env = manager._snapshot_environment(job)
            original_identity = manager._snapshot_job_identity(job)
            root = manager.files.directory(env, [])
            try:
                _physical(manager, job, env, root, error)
            finally:
                os.close(root)
            directory = manager.files.directory(env, parts)
            try:
                before = os.fstat(directory)
                version = _version(before)
                if supplied_version and supplied_version != version:
                    raise error("Directory changed; discard the old page and review again", 409)
                files, examined, exhausted = [], 0, False
                budget = min(PAGE, MAX_ENTRIES - offset)
                # Raw directory order is a bounded cursor; sorting is only within this page.
                with os.scandir(directory) as entries:
                    for _ in range(offset):
                        if next(entries, None) is None:
                            exhausted = True
                            break
                    if not exhausted:
                        for _ in range(budget):
                            entry = next(entries, None)
                            if entry is None:
                                exhausted = True
                                break
                            examined += 1
                            path = relative + "/" + entry.name if relative else entry.name
                            try:
                                manager._snapshot_paths([path])
                            except error:
                                continue  # Do not expose secret/runtime names as selectable results.
                            info = entry.stat(follow_symlinks=False)
                            kind = "directory" if stat.S_ISDIR(info.st_mode) else "file" if stat.S_ISREG(info.st_mode) else "symlink" if stat.S_ISLNK(info.st_mode) else "unsupported"
                            selectable = kind == "file" and info.st_nlink == 1 and info.st_size <= 262_144
                            files.append({"name": entry.name, "path": path, "kind": kind, "bytes": info.st_size,
                                          "selectable": selectable, "reason": "" if selectable or kind == "directory" else "Not a bounded single-link regular result file"})
                if version != _version(os.fstat(directory)):
                    raise error("Directory changed during the read; no page was accepted", 409)
                manager._snapshot_environment(job)
                if original_identity != manager._snapshot_job_identity(job):
                    raise error("Original job changed during the directory read", 409)
                # Reopen the configured path too: an unlinked/replaced ancestor is not this page.
                current = manager.files.directory(env, parts)
                try:
                    if _version(os.fstat(current)) != version:
                        raise error("Directory path was replaced during the read", 409)
                finally:
                    os.close(current)
                next_offset = offset + examined
                return {"jobId": jobid, "jobOperationId": job["operationId"], "environmentId": job["environmentId"],
                        "worker": job["worker"], "workspaceIdentity": job["workspaceIdentity"], "path": relative,
                        "directoryVersion": version, "files": sorted(files, key=lambda value: value["name"]),
                        "offset": offset, "examinedEntries": examined, "pageSize": PAGE, "maximumInspectedEntries": MAX_ENTRIES,
                        "listingComplete": exhausted, "inspectionLimitReached": not exhausted and next_offset >= MAX_ENTRIES,
                        "nextOffset": next_offset if not exhausted and next_offset < MAX_ENTRIES else None,
                        "recursive": False, "fileContentsRead": False, "captureSource": "selected_current_workspace_after_settled_original_job",
                        "authorshipVerified": False, "executionAuthorized": False}
            finally:
                os.close(directory)
    except FileError as failure:
        raise error(str(failure), failure.status) from failure
    except OSError as failure:
        raise error("Original directory is unavailable, changed, or contains a symbolic link", 409) from failure
