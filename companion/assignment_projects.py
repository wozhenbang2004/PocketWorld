"""Native-approved phone project packages on the original Companion operation/job ledger.

This is a fresh Git baseline made from selected package bytes, not the phone's Git history,
an OS sandbox, a second scheduler, or permission to copy arbitrary remote directories.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import time
import shutil
import stat
import subprocess
import uuid
import harness_registry
import assignment_workspaces as selected

FD_ROOT = "/proc/self/fd" if os.path.isdir("/proc/self/fd") else "/dev/fd" if os.path.isdir("/dev/fd") else None
PROTOCOL = "project_package_assignment_v1"
MODE = "phone_project_package_independent_git"
MANIFEST_ENCODING = "android_jsonstringer_v1"
MAX_FILES, MAX_FILE, MAX_TOTAL, MAX_MANIFEST = 2000, 2_097_152, 16_777_216, 1_048_576
PRIVATE = {".git", ".ssh", ".aws", ".azure", ".gnupg", ".codex", ".claude", ".config", ".npm", ".cache", "node_modules", ".venv", "venv", "__pycache__", ".gradle", ".pocketworld", ".pytest_cache"}
MARKER = ".pocket-project.json"


def capability(env, storage_root=None):
    return {"protocol": PROTOCOL, "supported": harness_registry.production_available(env) and shutil.which("git") is not None and FD_ROOT is not None,
            "workers": harness_registry.production_workers(env),
            "maxFiles": MAX_FILES, "maxFileBytes": MAX_FILE, "maxTotalBytes": MAX_TOTAL, "maxManifestBytes": MAX_MANIFEST, "maxRetainedCopiesPerEnvironment": 16,
            "packageIdentityEncoding": MANIFEST_ENCODING, "binaryStreaming": True, "freshGitBaseline": True, "stableDirectoryHandleCwd": FD_ROOT is not None, "storageRoot": storage_root, "originalGitHistoryIncluded": False, "workerStartsOnlyAfterReady": True,
            "commandsRequireSeparateOriginalOwnerApproval": True, "osReadIsolationVerified": False, "commandAllowlistEnforced": False, "automaticCleanup": False}


def path(value, error):
    if not isinstance(value, str) or not 1 <= len(value.encode("utf-8", errors="surrogatepass")) <= 2048 or "\\" in value or value.startswith("/") or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise error("Project path is not an exact bounded relative UTF-8 filename")
    parts = value.split("/")
    if len(parts) > 40 or any(part in {"", ".", "..", MARKER} or part in PRIVATE or part.startswith(".pocket-") for part in parts):
        raise error("Project scope contains a reserved/private directory or traversal", 403)
    name = parts[-1].lower()
    if name == ".env" or name.startswith(".env.") or name in {".npmrc", ".pypirc", ".netrc", ".git-credentials", "credentials.json", "accounts.json", "secrets.json", "token.json", "tokens.json"} or re.fullmatch(r"id_(rsa|dsa|ecdsa|ed25519)(\.pub)?", name) or re.fullmatch(r".*\.(pem|key|p12|pfx|jks|keystore|ppk|gpg)", name) or name.startswith("service-account"):
        raise error("Private credential/configuration files are not project-package inputs", 403)
    return value


def limits(value, error):
    # Do not widen the selected-file protocol. Full-package contribution has its own explicit cap.
    if not isinstance(value, dict) or set(value) - {"maxWallTimeSeconds", "maxOutputBytes", "maxContributionBytes", "hardTokenLimit", "hardCostLimit"}:
        raise error("Explicit project external_job limits required")
    if value.get("hardTokenLimit") is not None or value.get("hardCostLimit") is not None:
        raise error("External CLI internal model/token/cost hard caps are unavailable", 409)
    result = {}
    for key, low, high in [("maxWallTimeSeconds", 10, 900), ("maxOutputBytes", 1024, 196608), ("maxContributionBytes", 1024, MAX_TOTAL)]:
        number = value.get(key)
        if isinstance(number, bool) or not isinstance(number, int) or not low <= number <= high:
            raise error("Unsupported explicitly reviewed project bound: " + key)
        result[key] = number
    return result


def native_tuple(value, error):
    if not isinstance(value, dict):
        raise error("Original project assignment tuple missing")
    extra = {"workItemId", "itemRevision", "ownerGeneration"}
    if not extra <= set(value):
        raise error("Full project assignment requires the original work-item tuple", 409)
    checked = selected.native_tuple({key: val for key, val in value.items() if key not in extra}, error)
    selected._id(value["workItemId"], "original work item", error)
    for key, low in [("itemRevision", 1), ("ownerGeneration", 0)]:
        if isinstance(value[key], bool) or not isinstance(value[key], int) or value[key] < low:
            raise error("Invalid original project item generation")
    return {**checked, **{key: value[key] for key in extra}}


def manifest_bytes(value):
    """Exact ProjectSources canonical JSON on Android: UTF-16 key order, decimal integers,
    compact syntax, JSONStringer string escapes (including every solidus), UTF-8 Unicode.
    No alternate digest, float formatting, or guessed normalizer is accepted.
    """
    def encode(item):
        if item is None:
            return "null"
        if isinstance(item, bool):
            return "true" if item else "false"
        if isinstance(item, int):
            return str(item)
        if isinstance(item, str):
            return json.dumps(item, ensure_ascii=False, separators=(",", ":")).replace("/", "\\/")
        if isinstance(item, list):
            return "[" + ",".join(encode(part) for part in item) + "]"
        if isinstance(item, dict) and all(isinstance(key, str) for key in item):
            keys = sorted(item, key=lambda key: key.encode("utf-16-be"))
            return "{" + ",".join(encode(key) + ":" + encode(item[key]) for key in keys) + "}"
        raise ValueError("Manifest canonical encoding supports only exact JSON integer/string/boolean/null containers")
    return encode(value).encode("utf-8")


def manifest_identity(value):
    return hashlib.sha256(manifest_bytes(value)).hexdigest()


def _manifest(value, identity, encoding, error):
    if not isinstance(value, dict) or value.get("format") != "pocketworld.project-package.v1" or value.get("mode") != "source_project" or value.get("includesHistory") is not False or value.get("includesToolEnvironment") is not False or value.get("policy", {}).get("privateFilesExcluded") is not True:
        raise error("Original fixed phone source-project package required; no credentials/history/imported grant")
    if encoding != MANIFEST_ENCODING:
        raise error("Original Android package identity encoding is not explicitly supported", 409)
    if not isinstance(identity, str) or not re.fullmatch(r"[a-f0-9]{64}", identity) or manifest_identity(value) != identity or len(manifest_bytes(value)) > MAX_MANIFEST:
        raise error("Original package manifest/identity differs or exceeds the explicit bound", 413)
    files, total, seen = value.get("files"), 0, set()
    if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES:
        raise error("Project package must contain 1-2000 explicitly frozen files", 413)
    for file in files:
        if not isinstance(file, dict) or set(file) != {"path", "bytes", "sha256", "kind", "mimeType", "executable"}:
            raise error("Original package file metadata is incomplete")
        relative = path(file["path"], error)
        if relative in seen or any(relative.startswith(old + "/") or old.startswith(relative + "/") for old in seen):
            raise error("Package has duplicate or colliding file/directory paths")
        seen.add(relative)
        if isinstance(file["bytes"], bool) or not isinstance(file["bytes"], int) or not 0 <= file["bytes"] <= MAX_FILE or not isinstance(file["sha256"], str) or not re.fullmatch(r"[a-f0-9]{64}", file["sha256"]) or not isinstance(file["executable"], bool):
            raise error("Project file byte/hash/mode scope is invalid", 413)
        total += file["bytes"]
    directories = value.get("directories")
    if not isinstance(directories, list) or len(directories) > MAX_FILES or len(set(directories)) != len(directories):
        raise error("Original package directory manifest is invalid")
    for directory in directories:
        path(directory, error)
        if directory in seen or any(directory.startswith(file + "/") for file in seen):
            raise error("Project directory collides with an original file")
    if total != value.get("bytes") or total > MAX_TOTAL:
        raise error("Original project total bytes differ or exceed 16MiB", 413)
    return selected._copy(value)


def _read(manager, operation, error):
    row = manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()
    if row is None or not row[0]:
        raise error("Original project preparation not found", 404)
    value = json.loads(row[0])
    if value.get("protocol") != PROTOCOL:
        raise error("Original operation belongs to a different kind", 409)
    return value


def _save(manager, record):
    record["revision"] = record.get("revision", 0) + 1
    record["updatedAt"] = manager.now()
    try:
        manager.ledger.execute("UPDATE operations SET details=?,last_status=?,updated_at=? WHERE operation_id=?", (json.dumps(record, ensure_ascii=False), record["status"], record["updatedAt"], record["prepareOperationId"]))
        manager.ledger.commit()
    except Exception:
        manager.ledger.rollback()
        raise


def public(record):
    return {key: selected._copy(value) for key, value in record.items() if key not in {"packageManifest", "requestFingerprint", "marker", "registeredJobId", "registeredJobOperationId"}}


def _root(manager, record):
    return manager.state / "assignment-projects" / record["checkoutId"]


def _current(manager, record, error, phases):
    actual = _read(manager, record["prepareOperationId"], error)
    if actual["status"] not in phases or manager.closed:
        raise error("Original project prepare is not dispatchable; query/cancel without replay", 409)
    if actual["approvalExpiresAt"] <= manager.now() or actual["environmentBinding"] != manager.environment_binding(actual["environmentId"]) or not capability(manager._environment(actual["environmentId"]))["supported"]:
        raise error("Original native project approval/environment expired or changed", 409)
    return actual


def _directory(manager, record, error):
    root = _root(manager, record)
    owner = manager._environment(record["environmentId"])
    env = {**owner, "id": record["environmentId"], "root": str(root), "kind": "local",
           "workers": harness_registry.production_workers(owner), "writeAllowed": True, "_fullProjectNative": True}
    if manager.workspace_identity(env) != record.get("checkoutIdentity"):
        raise error("Original independent project checkout was replaced", 409)
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        descriptor = os.open(MARKER, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > 16384 or json.loads(source.read(16385)) != record["marker"]:
                raise error("Original project native marker changed", 409)
        return fd, env
    except Exception:
        os.close(fd)
        raise


def _parent(directory, relative, create):
    parts = relative.split("/")
    current = os.dup(directory)
    try:
        for part in parts[:-1]:
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=current)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
            os.close(current)
            current = child
        return current, parts[-1]
    except Exception:
        os.close(current)
        raise


def _file(directory, file, error):
    parent, name = _parent(directory, file["path"], False)
    try:
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > MAX_FILE:
                raise error("Project file is linked, special or over 2MiB", 409)
            raw = source.read(MAX_FILE + 1)
            after = os.fstat(source.fileno())
            if len(raw) != before.st_size or (before.st_dev, before.st_ino, before.st_mtime_ns, before.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns):
                raise error("Project file changed during exact byte read", 409)
        actual = os.stat(name, dir_fd=parent, follow_symlinks=False)
        if not stat.S_ISREG(actual.st_mode) or (actual.st_dev, actual.st_ino, actual.st_mtime_ns, actual.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_mtime_ns, after.st_ctime_ns):
            raise error("Project filename changed during capture", 409)
        return raw, {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "executable": bool(after.st_mode & 0o111)}
    finally:
        os.close(parent)


def prepare(manager, request, error):
    keys = {"operationId", "nativeAssignment", "environmentId", "environmentBinding", "packageIdentity", "packageIdentityEncoding", "packageManifest", "packageReference", "files", "limits", "commandScope", "approvalRevision", "approvalExpiresAt"}
    if not isinstance(request, dict) or set(request) != keys:
        raise error("Full project prepare requires exact native package/assignment/owner scope")
    operation = selected._id(request["operationId"], "original project prepare", error)
    native = native_tuple(request["nativeAssignment"], error)
    manifest = _manifest(request["packageManifest"], request["packageIdentity"], request["packageIdentityEncoding"], error)
    bounds = limits(request["limits"], error)
    commands = request["commandScope"]
    if not isinstance(commands, dict) or set(commands) != {"allowCommands", "sameUserExecutionRiskAcknowledged", "privateSourceTransferAcknowledged", "internalInferenceBillingUnboundedAcknowledged"} or any(commands[key] is not True for key in commands):
        raise error("Owner must explicitly review source transfer, same-user commands and unknown internal billing", 409)
    reference = request["packageReference"]
    if not isinstance(reference, dict) or reference.get("manifestIdentity") != request["packageIdentity"] or not isinstance(reference.get("id"), str) or not isinstance(reference.get("sha256"), str) or not re.fullmatch(r"[a-f0-9]{64}", reference["sha256"]):
        raise error("Native original project artifact reference missing")
    chosen = request["files"]
    if not isinstance(chosen, list) or not 1 <= len(chosen) <= MAX_FILES:
        raise error("Explicit original project write-path scope required")
    by_path, approved = {file["path"]: file for file in manifest["files"]}, set()
    for file in chosen:
        if not isinstance(file, dict) or set(file) != {"path", "baseHash", "baseVersion"} or path(file.get("path"), error) in approved or isinstance(file.get("baseVersion"), bool) or not isinstance(file.get("baseVersion"), int) or file["baseVersion"] < 0:
            raise error("Original project write-path baseline/version missing")
        approved.add(file["path"])
        expected = by_path.get(file["path"], {}).get("sha256", "absent")
        if file["baseHash"] != expected:
            raise error("Approved phone baseline does not match exact package bytes", 409)
    if isinstance(request["approvalRevision"], bool) or not isinstance(request["approvalRevision"], int) or request["approvalRevision"] < 1 or isinstance(request["approvalExpiresAt"], bool) or not isinstance(request["approvalExpiresAt"], int) or request["approvalExpiresAt"] <= manager.now():
        raise error("Original native project preparation approval expired", 409)
    fingerprint = selected.digest(request)
    with manager.lock:
        prior = manager.ledger.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (operation,)).fetchone()
        if prior:
            if prior[0] != fingerprint:
                raise error("Original prepare operation already binds a different package/scope", 409)
            return public(_read(manager, operation, error))
        env = manager._environment(request["environmentId"])
        if not capability(env)["supported"] or request["environmentBinding"] != manager.environment_binding(request["environmentId"]):
            raise error("This exact environment does not support the reviewed POSIX project-package/Git protocol", 409)
        retained = 0
        for raw, in manager.ledger.execute("SELECT details FROM operations WHERE details IS NOT NULL").fetchall():
            old = json.loads(raw)
            if old.get("protocol") == PROTOCOL and old.get("environmentId") == request["environmentId"]:
                retained += 1
            if old.get("protocol") == PROTOCOL and old.get("nativeAssignment") == native:
                raise error("Original assignment attempt already owns a project prepare; query it, do not create another", 409)
        if retained >= 16:
            raise error("This environment retains sixteen native project copies; none or their unknown bytes were silently deleted", 413)
        checkout = str(uuid.uuid5(uuid.NAMESPACE_URL, "pocketworld/full-project/v1/" + operation))
        marker = {"checkoutId": checkout, "prepareOperationId": operation, "packageIdentity": request["packageIdentity"], "assignmentHash": selected.digest(native)}
        record = {"protocol": PROTOCOL, "mode": MODE, "fullProject": True, "status": "preparing", "prepareOperationId": operation, "operationId": operation, "checkoutId": checkout,
                  "environmentId": request["environmentId"], "environmentBinding": request["environmentBinding"], "nativeAssignment": native,
                  "packageIdentity": request["packageIdentity"], "packageIdentityEncoding": request["packageIdentityEncoding"], "packageReference": selected._copy(reference), "packageManifest": manifest, "packageSource": manifest["source"],
                  "files": selected._copy(chosen), "inputFiles": [{**file, "index": i, "state": "pending", "receivedBytes": 0} for i, file in enumerate(manifest["files"])],
                  "limits": bounds, "commandScope": selected._copy(commands), "approvalRevision": request["approvalRevision"], "approvalExpiresAt": request["approvalExpiresAt"],
                  "marker": marker, "requestFingerprint": fingerprint, "revision": 1, "createdAt": manager.now(), "updatedAt": manager.now(), "copiesMayRemain": False,
                  "preparationStartsWorker": False, "cleanupConfirmed": False, "osReadIsolationVerified": False, "commandAllowlistEnforced": False, "originalGitHistoryIncluded": False}
        # The original durable operation is reserved BEFORE mkdir, first body byte or Git command.
        manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)", (operation, fingerprint, "", record["status"], record["updatedAt"], json.dumps(record, ensure_ascii=False)))
        manager.ledger.commit()
        try:
            parent = manager.state / "assignment-projects"
            parent.mkdir(mode=0o700, exist_ok=True)
            if parent.is_symlink() or not parent.is_dir():
                raise error("Private project preparation root is not an original directory", 409)
            root = _root(manager, record)
            root.mkdir(mode=0o700)  # Never adopt an existing unregistered directory.
            selected._write_new(root, MARKER, json.dumps(marker, sort_keys=True).encode())
            record.update(checkoutIdentity=manager.workspace_identity({"kind": "local", "root": str(root)}), workspace=str(root), status="awaiting_upload", copiesMayRemain=True)
            fd, _ = _directory(manager, record, error)
            try:
                for directory in manifest["directories"]:
                    container, name = _parent(fd, directory + "/.placeholder", True)
                    os.close(container)
            finally:
                os.close(fd)
            _save(manager, record)
        except Exception:
            record.update(status="unknown", copiesMayRemain=True, cleanupConfirmed=False)
            try:
                _save(manager, record)
            finally:
                raise
        return public(record)


def operation(manager, operation_id, error):
    with manager.lock:
        return public(_read(manager, selected._id(operation_id, "original prepare", error), error))


def upload(manager, operation_id, index, length, stream, error):
    with manager.lock:
        record = _current(manager, _read(manager, operation_id, error), error, {"awaiting_upload", "uploading"})
        if isinstance(index, bool) or not isinstance(index, int) or not 0 <= index < len(record["inputFiles"]):
            raise error("Project upload index is outside original package")
        slot = record["inputFiles"][index]
        if slot["state"] != "pending":
            raise error("Original project slot is already reserved; query ACK, never send a second body", 409)
        if isinstance(length, bool) or length != slot["bytes"]:
            raise error("Raw project byte count differs from the explicitly approved package", 413)
        slot.update(state="uploading", receivedBytes=0)
        record["status"] = "uploading"
        _save(manager, record)
    directory = parent = descriptor = None
    received, temporary = 0, ".pocket-upload-" + str(index)
    try:
        with manager.lock:
            _current(manager, record, error, {"uploading"})
            directory, _ = _directory(manager, record, error)
            parent, name = _parent(directory, slot["path"], True)
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
        digest = hashlib.sha256()
        with os.fdopen(descriptor, "wb") as output:
            descriptor = None
            while received < length:
                with manager.lock:
                    _current(manager, record, error, {"uploading"})
                chunk = stream.read(min(32768, length - received))
                if not chunk:
                    raise error("Original binary project file ended before its exact byte count", 409)
                output.write(chunk)
                digest.update(chunk)
                received += len(chunk)
                with manager.lock:
                    latest = _current(manager, record, error, {"uploading"})
                    latest["inputFiles"][index]["receivedBytes"] = received
                    _save(manager, latest)
            output.flush()
            os.fchmod(output.fileno(), 0o700 if slot["executable"] else 0o600)
            os.fsync(output.fileno())
        if digest.hexdigest() != slot["sha256"]:
            raise error("Actual binary project input SHA-256 differs; no worker started", 409)
        with manager.lock:
            latest = _current(manager, record, error, {"uploading"})
            # No replacement/rename overwrite. Our own new name is committed once.
            os.link(temporary, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
            os.unlink(temporary, dir_fd=parent)
            os.fsync(parent)
            latest["inputFiles"][index].update(state="verified", receivedBytes=received, actualSha256=digest.hexdigest())
            latest["status"] = "uploaded" if all(file["state"] == "verified" for file in latest["inputFiles"]) else "uploading" if any(file["state"] == "uploading" for file in latest["inputFiles"]) else "awaiting_upload"
            _save(manager, latest)
            return public(latest)
    except Exception:
        with manager.lock:
            latest = _read(manager, operation_id, error)
            latest["inputFiles"][index].update(state="unknown", receivedBytes=received)
            if latest["status"] != "cancelled":
                latest["status"] = "unknown"
            latest.update(copiesMayRemain=True, cleanupConfirmed=False)
            _save(manager, latest)
        raise
    finally:
        for descriptor in [descriptor, parent, directory]:
            if descriptor is not None:
                os.close(descriptor)


def _git(manager, root, *args, max_output=262144):
    # Never inherit user hooks, credential helpers, pager, global config, alternate object dirs or filters.
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0", GIT_PAGER="cat", PAGER="cat", LC_ALL="C", GIT_AUTHOR_NAME="PocketWorld native baseline", GIT_AUTHOR_EMAIL="baseline@pocketworld.invalid", GIT_COMMITTER_NAME="PocketWorld native baseline", GIT_COMMITTER_EMAIL="baseline@pocketworld.invalid")
    command = [str(Path(shutil.which("git")).resolve()), "-c", "core.hooksPath=" + os.devnull, "-c", "core.fsmonitor=false", "-c", "core.autocrlf=false", "-c", "core.safecrlf=false", "-c", "commit.gpgSign=false", "-c", "credential.helper=", "-c", "protocol.file.allow=never", "-c", "protocol.ext.allow=never", *args]
    process = subprocess.Popen(command, cwd=FD_ROOT + "/" + str(root), pass_fds=(root,), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    captured = {"output": bytearray(), "error": bytearray()}
    deadline = time.monotonic() + 10
    poller = selectors.DefaultSelector()
    try:
        for stream, name in [(process.stdout, "output"), (process.stderr, "error")]:
            os.set_blocking(stream.fileno(), False)
            poller.register(stream, selectors.EVENT_READ, name)
        while poller.get_map():
            if time.monotonic() >= deadline:
                raise OSError("Original native Git command timed out; no replay or lock cleanup")
            for key, _ in poller.select(.1):
                chunk = os.read(key.fileobj.fileno(), 8192)
                if not chunk:
                    poller.unregister(key.fileobj)
                    continue
                target = captured[key.data]
                if len(target) + len(chunk) > (max_output if key.data == "output" else 262144):
                    raise OSError("Original native Git output exceeded its bounded diagnostic/blob scope")
                target.extend(chunk)
        if process.wait(timeout=max(.01, deadline - time.monotonic())) != 0:
            raise OSError("Original native Git command failed; no automatic replay or lock cleanup")
        return bytes(captured["output"])
    finally:
        if process.poll() is None:
            manager._terminate(process, hard=True)
        poller.close()
        process.stdout.close()
        process.stderr.close()



def finalize(manager, operation_id, request, error):
    if set(request) != {"finalizeOperationId", "packageIdentity"}:
        raise error("Exact original finalize identity/package required")
    selected._id(request["finalizeOperationId"], "project finalize", error)
    with manager.lock:
        record = _read(manager, operation_id, error)
        if record.get("finalizeOperationId"):
            if record["finalizeOperationId"] != request["finalizeOperationId"] or record["packageIdentity"] != request["packageIdentity"]:
                raise error("Project finalize already reserved for another exact operation", 409)
            return public(record)
        record = _current(manager, record, error, {"uploaded"})
        if record["packageIdentity"] != request["packageIdentity"]:
            raise error("Original package baseline changed", 409)
        record.update(status="finalizing", finalizeOperationId=request["finalizeOperationId"])
        _save(manager, record)  # reserve BEFORE Git init/index/commit; unknown never repeats them
        directory = None
        try:
            directory, env = _directory(manager, record, error)
            for file in record["packageManifest"]["files"]:
                raw, actual = _file(directory, file, error)
                if any(actual[key] != file[key] for key in ["bytes", "sha256", "executable"]):
                    raise error("Original uploaded package bytes/mode drifted before Git baseline", 409)
            root = Path(env["root"])
            version = _git(manager, directory, "--version").decode().strip()
            _git(manager, directory, "init", "--quiet", "--template=", ".")
            _git(manager, directory, "config", "core.filemode", "true")
            # The private marker is not model-owned project content; explicit force-add ignores user .gitignore.
            _git(manager, directory, "config", "core.excludesfile", os.devnull)
            files = [file["path"] for file in record["packageManifest"]["files"]]
            for at in range(0, len(files), 64):
                _git(manager, directory, "add", "--force", "--", *files[at:at + 64])
            _git(manager, directory, "commit", "--quiet", "--no-verify", "-m", "Native fixed phone project package " + record["packageIdentity"])
            head = _git(manager, directory, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
            if not re.fullmatch(r"(?:[a-f0-9]{40}|[a-f0-9]{64})", head):
                raise error("Native Git did not return an actual base commit", 409)
            # A project .gitattributes may transform blobs. Never call transformed Git bytes the phone baseline.
            for file in record["packageManifest"]["files"]:
                blob = _git(manager, directory, "cat-file", "blob", head + ":" + file["path"], max_output=MAX_FILE)
                if len(blob) != file["bytes"] or hashlib.sha256(blob).hexdigest() != file["sha256"]:
                    raise error("Actual Git base blob differs from exact phone package; no worker started", 409)
            # Re-read exact package bytes after all Git IO, before publishing readiness.
            for file in record["packageManifest"]["files"]:
                _, actual = _file(directory, file, error)
                if any(actual[key] != file[key] for key in ["bytes", "sha256", "executable"]):
                    raise error("Actual project changed while creating its new baseline", 409)
            record.update(status="ready", gitBaseCommit=head, gitVersion=version, gitBaselineKind="fresh_commit_from_fixed_phone_package_not_original_history")
            record["checkoutVersion"] = selected.digest({key: record[key] for key in ["checkoutId", "checkoutIdentity", "packageIdentity", "packageIdentityEncoding", "nativeAssignment", "gitBaseCommit", "files", "limits", "commandScope"]})
            _save(manager, record)
        except Exception:
            record.update(status="unknown", copiesMayRemain=True, cleanupConfirmed=False)
            try:
                _save(manager, record)
            finally:
                raise
        finally:
            if directory is not None:
                os.close(directory)
        return public(record)


def cancel(manager, operation_id, error):
    with manager.lock:
        record = _read(manager, operation_id, error)
        linked = [job for job in manager.jobs.values() if job.get("assignmentWorkspace", {}).get("prepareOperationId") == operation_id]
        if record.get("registeredJobId") or linked:
            raise error("Original project has a registered coding job; cancel that original job, not its preparation", 409)
        if record["status"] != "cancelled":
            record.update(status="cancelled", workerStarted=False, copiesMayRemain=True, cleanupConfirmed=False)
            _save(manager, record)
        return public(record)


def record_for_reference(manager, reference, error):
    record = _read(manager, reference.get("prepareOperationId"), error)
    if record.get("checkoutId") != reference.get("checkoutId") or record.get("checkoutVersion") != reference.get("checkoutVersion") or record.get("status") != "ready":
        raise error("Original independent project checkout/base is not ready", 409)
    return record


def environment(manager, record, error):
    fd, env = _directory(manager, record, error)
    os.close(fd)
    return env


def bound_environment(manager, request, error):
    reference = request.get("assignmentWorkspace")
    required = {"mode", "prepareOperationId", "checkoutId", "checkoutVersion", "nativeAssignment", "preparationNativeAssignment", "reservationId", "approvalRevision", "approvalExpiresAt"}
    if not isinstance(reference, dict) or set(reference) != required or reference["mode"] != MODE:
        raise error("Exact native full-project checkout/job reservation required", 409)
    record = record_for_reference(manager, reference, error)
    prepared, dispatch = native_tuple(reference["preparationNativeAssignment"], error), native_tuple(reference["nativeAssignment"], error)
    if prepared != record["nativeAssignment"]:
        raise error("Original physical preparation tuple was replaced", 409)
    for key in ["taskId", "parentTaskId", "parentRunId", "parentEpoch", "teamRunId", "assignmentId", "assignmentRevision", "memberId", "workItemId", "itemRevision", "ownerGeneration"]:
        if dispatch[key] != prepared[key]:
            raise error("Project dispatch changed its original assignment/work-item scope", 409)
    if request.get("environmentId") != record["environmentId"] or request.get("environmentBinding") != record["environmentBinding"] or record["environmentBinding"] != manager.environment_binding(record["environmentId"]):
        raise error("Original project environment generation changed", 409)
    worker = request.get("worker")
    if worker not in harness_registry.production_workers(manager._environment(record["environmentId"])) or request.get("readOnly") is not False or any(request.get(key) for key in ["threadId", "resumeJobId"]) or request.get("timeoutSeconds") != record["limits"]["maxWallTimeSeconds"]:
        raise error("Full-project dispatch must be a new exact enabled harness job under the reviewed bounds", 409)
    if worker != "codex_app_server" and request.get("externalExecutionRiskAcknowledged") is not True:
        raise error("This project harness requires explicit owner-managed execution acknowledgement", 409)
    selected._id(reference["reservationId"], "original external_job reservation", error)
    if isinstance(reference["approvalRevision"], bool) or not isinstance(reference["approvalRevision"], int) or reference["approvalRevision"] < record["approvalRevision"] or isinstance(reference["approvalExpiresAt"], bool) or not isinstance(reference["approvalExpiresAt"], int) or reference["approvalExpiresAt"] <= manager.now():
        raise error("Original full-project owner job approval expired", 409)
    if record.get("registeredJobId") or any(job.get("assignmentWorkspace", {}).get("checkoutId") == record["checkoutId"] for job in manager.jobs.values()):
        raise error("This checkout already has an original coding job; query it without spawning another", 409)
    fd, env = _directory(manager, record, error)
    try:
        for file in record["packageManifest"]["files"]:
            _, actual = _file(fd, file, error)
            if any(actual[key] != file[key] for key in ["bytes", "sha256", "executable"]):
                raise error("Native package baseline changed before original job dispatch", 409)
        head = _git(manager, fd, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
        if head != record["gitBaseCommit"]:
            raise error("Original actual Git base commit changed before job dispatch", 409)
    finally:
        os.close(fd)
    return env, record


def bind_job(manager, record, job):
    """Same original operations transaction as first job reservation, no separate mapping/store."""
    if record.get("registeredJobId"):
        raise ValueError("Original full-project checkout already has a registered job")
    record.update(registeredJobId=job["id"], registeredJobOperationId=job["operationId"])
    manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(record, ensure_ascii=False), record["prepareOperationId"]))


def _tree(directory, error, prefix="", seen=None, included=None):
    """Bounded names/types only; never follow links or read omitted generated/private content."""
    if seen is None:
        seen = [0]
    included = included or set()
    result, omissions = {}, []
    with os.scandir(directory) as entries:
        for entry in entries:
            seen[0] += 1
            if seen[0] > 10000:
                raise error("Owned checkout has over 10000 entries; no incomplete contribution published", 413)
            relative = prefix + entry.name
            if entry.name == ".git" or entry.name == MARKER:
                continue
            if entry.name in PRIVATE or entry.name.startswith(".pocket-"):
                omissions.append({"path": relative, "reason": "native_or_private_runtime_not_captured"})
                continue
            if entry.is_symlink():
                raise error("Owned project contribution contains a symbolic link: " + relative, 409)
            if entry.is_dir(follow_symlinks=False):
                if relative.split("/")[0] in {"build", "dist", "out", "target", "coverage"} and not any(item == relative or item.startswith(relative + "/") for item in included):
                    omissions.append({"path": relative, "reason": "generated_output_not_automatically_captured"})
                    continue
                if len(relative.split("/")) > 40:
                    raise error("Owned project contribution exceeds original path depth", 413)
                child = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                try:
                    children, omitted = _tree(child, error, relative + "/", seen, included)
                    result.update(children)
                    omissions.extend(omitted)
                finally:
                    os.close(child)
            elif entry.is_file(follow_symlinks=False):
                try:
                    path(relative, error)
                except error as invalid:
                    if invalid.status != 403:
                        raise
                    omissions.append({"path": relative, "reason": "private_configuration_not_captured"})
                    continue
                info = entry.stat(follow_symlinks=False)
                result[relative] = [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]
            else:
                raise error("Owned project contribution contains a special file", 409)
    return result, sorted(omissions, key=lambda row: row["path"])


def freeze(manager, job_id, request, error):
    keys = {"operationId", "jobOperationId", "prepareOperationId", "checkoutVersion"}
    if not isinstance(request, dict) or set(request) != keys:
        raise error("Exact original project job/checkout contribution scope required")
    operation_id = selected._id(request["operationId"], "original project contribution operation", error)
    fingerprint = selected.digest(request)
    with manager.lock:
        job = manager.jobs.get(job_id)
        if job is None or job.get("operationId") != request["jobOperationId"]:
            raise error("Contribution is not bound to the original tracked coding job", 409)
        previous = manager.ledger.execute("SELECT fingerprint,job_id,details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
        if previous:
            if previous[0] != fingerprint or previous[1] != job_id:
                raise error("Original contribution operation belongs to different content/job", 409)
            snapshot = next((value for value in job.get("resultSnapshots", []) if value["operationId"] == operation_id), None)
            return manager._snapshot_public(snapshot) if snapshot else {"operationId": operation_id, "jobId": job_id, "status": "unknown", "resubmitted": False, "message": "Original contribution capture unconfirmed; GET does not capture again"}
        reference = job.get("assignmentWorkspace", {})
        if reference.get("mode") != MODE or reference.get("prepareOperationId") != request["prepareOperationId"] or reference.get("checkoutVersion") != request["checkoutVersion"] or job.get("dispatchFenceFinal") is not True:
            raise error("Original job has not proved the full-project dispatch fence final", 409)
        record = record_for_reference(manager, reference, error)
        env = manager._snapshot_environment(job)
        if len(job.get("resultSnapshots", [])) >= 2:
            raise error("Original full-project job retains at most two immutable contribution versions", 413)
        detail = {"protocol": "project_contribution_v1", "status": "capturing", "operationId": operation_id, "jobId": job_id}
        manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES(?,?,?,?,?,?)", (operation_id, fingerprint, job_id, "capturing", manager.now(), json.dumps(detail)))
        manager.ledger.commit()
        directory = None
        try:
            directory, env = _directory(manager, record, error)
            original_identity = manager._snapshot_job_identity(job)
            included = {file["path"] for file in record["packageManifest"]["files"]} | {file["path"] for file in record["files"]}
            before_tree, omissions = _tree(directory, error, included=included)
            bases = {file["path"]: file for file in record["packageManifest"]["files"]}
            allowed = {file["path"]: file for file in record["files"]}
            # A broad command is not a grant to silently import changes outside the native write scope.
            for relative in set(bases) | set(before_tree):
                if relative in allowed:
                    continue
                base = bases.get(relative)
                if base is None or relative not in before_tree:
                    raise error("Project changed an unapproved path; owner must review a new exact scope", 403)
                _, actual = _file(directory, base, error)
                if any(actual[key] != base[key] for key in ["bytes", "sha256", "executable"]):
                    raise error("Project changed unapproved original package bytes", 403)
            files, changes, total = [], [], 0
            for relative, approved in allowed.items():
                base = bases.get(relative)
                if base is not None:
                    blob = _git(manager, directory, "cat-file", "blob", record["gitBaseCommit"] + ":" + relative, max_output=MAX_FILE)
                    if len(blob) != base["bytes"] or hashlib.sha256(blob).hexdigest() != base["sha256"]:
                        raise error("Actual independent Git baseline no longer matches original package", 409)
                if relative not in before_tree:
                    if base is None:
                        continue  # A new approved path that was never created is not a deletion.
                    metadata = {"path": relative, "bytes": 0, "sha256": "absent", "executable": False, "tombstone": True, "artifactKind": "file_tombstone"}
                    raw = None
                else:
                    raw, actual = _file(directory, {"path": relative}, error)
                    metadata = {"path": relative, **actual, "tombstone": False, "artifactKind": "file"}
                    total += len(raw)
                kind = "delete" if metadata["tombstone"] else "add" if base is None else "modify" if any(metadata[key] != base[key] for key in ["bytes", "sha256", "executable"]) else "unchanged"
                metadata["fileVersion"] = selected.digest({"jobId": job_id, "operationId": operation_id, "checkoutVersion": record["checkoutVersion"], "path": relative, "sha256": metadata["sha256"], "bytes": metadata["bytes"], "executable": metadata["executable"]})
                files.append({**metadata, **({"base64": base64.b64encode(raw).decode()} if raw is not None else {})})
                changes.append({**metadata, "change": kind, "baseSha256": approved["baseHash"], "appBaseVersion": approved["baseVersion"]})
            if total > record["limits"]["maxContributionBytes"] or total > MAX_TOTAL:
                raise error("Actual project contribution bytes exceed the explicitly approved output cap", 413)
            after_tree, after_omissions = _tree(directory, error, included=included)
            if before_tree != after_tree or omissions != after_omissions:
                raise error("Project files changed while freezing; no partial immutable contribution published", 409)
            for file in files:
                if file["tombstone"]:
                    continue
                _, actual = _file(directory, file, error)
                if any(actual[key] != file[key] for key in ["bytes", "sha256", "executable"]):
                    raise error("Project contribution byte/version changed during capture", 409)
            manager._snapshot_environment(job)
            if original_identity != manager._snapshot_job_identity(job):
                raise error("Original job identity changed during contribution capture", 409)
            _directory_fd, _ = _directory(manager, record, error)
            os.close(_directory_fd)
            head = _git(manager, directory, "rev-parse", "--verify", "HEAD^{commit}").decode().strip()
            proof = {"nativeAssignment": reference["nativeAssignment"], "preparationNativeAssignment": record["nativeAssignment"], "checkoutId": record["checkoutId"], "checkoutVersion": record["checkoutVersion"], "checkoutIdentity": record["checkoutIdentity"],
                     "prepareOperationId": record["prepareOperationId"], "packageIdentity": record["packageIdentity"], "packageIdentityEncoding": record["packageIdentityEncoding"], "packageReference": record["packageReference"], "gitBaseCommit": record["gitBaseCommit"], "observedHead": head,
                     "reservationId": reference["reservationId"], "files": changes, "source": "actual_fixed_phone_package_to_original_job_frozen_bytes", "fullProject": True, "integrated": False, "independentlyReviewed": False, "osReadIsolationVerified": False, "omitted": omissions}
            snapshot = {"snapshotId": str(uuid.uuid4()), "operationId": operation_id, "jobId": job_id, "jobOperationId": job["operationId"], "environmentId": job["environmentId"], "worker": job["worker"],
                        "status": "completed", "capturedAt": manager.now(), "captureSource": "native_full_project_assignment_after_settled_original_job", "authorshipVerified": False,
                        "immutableArtifact": True, "bytes": total, "files": files, "fingerprint": fingerprint, "workspaceIdentity": job["workspaceIdentity"], "assignmentContribution": proof,
                        "consistency": "two_read_tree_content_stat_fence", "atomicWorkspaceSnapshot": False, "rawExactFileReadProtocol": PROTOCOL}
            for key in ["threadId", "sessionId", "turnId"]:
                if job.get(key):
                    snapshot[key] = job[key]
            snapshot["snapshotVersion"] = selected.digest(manager._snapshot_public(snapshot))
            updated = dict(job)
            updated["resultSnapshots"] = job.get("resultSnapshots", []) + [snapshot]
            manager._save(updated)
            manager.jobs[job_id] = updated
            detail.update(status="completed", snapshotId=snapshot["snapshotId"], snapshotVersion=snapshot["snapshotVersion"])
            manager.ledger.execute("UPDATE operations SET details=?,last_status='completed',updated_at=? WHERE operation_id=?", (json.dumps(detail), manager.now(), operation_id))
            manager.ledger.commit()
            return manager._snapshot_public(snapshot)
        except Exception:
            manager.ledger.rollback()
            detail["status"] = "unknown"
            manager.ledger.execute("UPDATE operations SET details=?,last_status='unknown',updated_at=? WHERE operation_id=?", (json.dumps(detail), manager.now(), operation_id))
            manager.ledger.commit()
            raise
        finally:
            if directory is not None:
                os.close(directory)


def exact_file(manager, job_id, snapshot_id, query, error):
    if set(query) != {"version", "path", "sha256", "bytes", "jobOperationId"}:
        raise error("Exact immutable contribution version/path/hash/bytes required")
    with manager.lock:
        job = manager.jobs.get(job_id)
        if job is None or job.get("operationId") != query["jobOperationId"]:
            raise error("Original contribution job operation differs", 409)
        snapshot = next((value for value in job.get("resultSnapshots", []) if value["snapshotId"] == snapshot_id), None)
        if snapshot is None or snapshot.get("rawExactFileReadProtocol") != PROTOCOL or snapshot["snapshotVersion"] != query["version"]:
            raise error("Original full-project immutable contribution not found", 404)
        file = next((value for value in snapshot["files"] if value["path"] == query["path"]), None)
        if file is None or file.get("tombstone") or file["sha256"] != query["sha256"] or str(file["bytes"]) != query["bytes"]:
            raise error("Immutable exact file differs or is a deletion (not an empty file)", 409)
        raw = base64.b64decode(file["base64"], validate=True)
        if len(raw) != file["bytes"] or hashlib.sha256(raw).hexdigest() != file["sha256"]:
            raise error("Original immutable contribution bytes no longer match manifest", 409)
        return raw, file["sha256"]
