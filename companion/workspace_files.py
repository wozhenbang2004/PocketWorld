"""Bounded real workspace files and immutable write receipts; independently authored."""
import difflib
import hashlib
import json
import os
from pathlib import Path
import stat
import uuid

LIMIT = 262_144


class FileError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class WorkspaceFiles:
    def __init__(self, state, database):
        self.state = Path(state) / "file-revisions"
        self.database = database
        self.database.execute("CREATE TABLE IF NOT EXISTS file_operations(operation_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, environment_id TEXT NOT NULL, path TEXT NOT NULL, desired_version TEXT NOT NULL, receipt TEXT NOT NULL)")
        self.database.commit()

    @staticmethod
    def parts(value, empty=False):
        if not isinstance(value, str) or len(value) > 2048 or "\\" in value or "\x00" in value or value.startswith("/"):
            raise FileError("Invalid workspace-relative path")
        if empty and not value:
            return []
        parts = value.split("/")
        if any(not part or part in {".", ".."} or any(ord(char) < 32 for char in part) for part in parts):
            raise FileError("Path must remain within the workspace", 403)
        return parts

    @staticmethod
    def version(value):
        return "sha256:" + hashlib.sha256(value).hexdigest()

    def directory(self, env, parts):
        if env.get("kind", "local") != "local" or os.name == "nt" or not hasattr(os, "O_NOFOLLOW"):
            raise FileError("Bounded workspace file operations currently require a POSIX local Companion environment", 409)
        descriptor = os.open(env["root"], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in parts:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
            return descriptor
        except OSError:
            os.close(descriptor)
            raise FileError("Directory is missing or contains a symbolic link", 403)

    def raw(self, env, path):
        parts = self.parts(path)
        directory = self.directory(env, parts[:-1])
        try:
            try:
                descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            except FileNotFoundError:
                return None
            with os.fdopen(descriptor, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise FileError("Only regular files can be transferred", 403)
                if info.st_size > LIMIT:
                    raise FileError("File exceeds 256 KiB", 413)
                value = stream.read(LIMIT + 1)
                if len(value) > LIMIT:
                    raise FileError("File exceeds 256 KiB", 413)
                return value
        except OSError:
            raise FileError("File cannot be opened without following a symbolic link", 403)
        finally:
            os.close(directory)

    def revision(self, env, path, value):
        version = self.version(value)
        location = self.state / hashlib.sha256(env["root"].encode()).hexdigest() / hashlib.sha256(path.encode()).hexdigest()
        location.mkdir(parents=True, exist_ok=True, mode=0o700)
        target = location / version[7:]
        if not target.exists():
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
        # Keep actual bounded historical contents, never invented diffs.
        os.utime(target, None)
        revisions = sorted((entry for entry in location.iterdir() if entry.is_file()), key=lambda entry: entry.stat().st_mtime_ns, reverse=True)
        for expired in revisions[8:]:
            expired.unlink()
        return version

    def list(self, env, relative=""):
        directory = self.directory(env, self.parts(relative, empty=True))
        try:
            names = sorted(os.listdir(directory))
            result = []
            for name in names[:200]:
                info = os.stat(name, dir_fd=directory, follow_symlinks=False)
                kind = "symlink" if stat.S_ISLNK(info.st_mode) else "directory" if stat.S_ISDIR(info.st_mode) else "file" if stat.S_ISREG(info.st_mode) else "unsupported"
                result.append({"name": name, "path": relative + "/" + name if relative else name, "kind": kind, "bytes": info.st_size, "modifiedAt": info.st_mtime_ns // 1_000_000})
            return {"files": result, "truncated": len(names) > 200, "path": relative}
        finally:
            os.close(directory)

    def read(self, env, path):
        value = self.raw(env, path)
        if value is None:
            raise FileError("File does not exist", 404)
        try:
            content = value.decode("utf-8")
        except UnicodeDecodeError:
            raise FileError("File is not UTF-8 text; use the binary artifact transfer", 409)
        return {"path": path, "content": content, "version": self.revision(env, path, value), "bytes": len(value)}

    def diff(self, env, path, baseline):
        if not isinstance(baseline, str) or not baseline.startswith("sha256:") or len(baseline) != 71 or any(c not in "0123456789abcdef" for c in baseline[7:]):
            raise FileError("A retained sha256 file version is required")
        self.parts(path)
        location = self.state / hashlib.sha256(env["root"].encode()).hexdigest() / hashlib.sha256(path.encode()).hexdigest() / baseline[7:]
        if not location.is_file():
            raise FileError("Requested file revision is not retained", 404)
        before = location.read_text(encoding="utf-8")
        current = self.read(env, path)
        difference = "".join(difflib.unified_diff(before.splitlines(keepends=True), current["content"].splitlines(keepends=True), fromfile=baseline + "/" + path, tofile=current["version"] + "/" + path))
        return {"path": path, "fromVersion": baseline, "version": current["version"], "diff": difference[:LIMIT], "truncated": len(difference) > LIMIT}

    def operation(self, operation):
        row = self.database.execute("SELECT receipt FROM file_operations WHERE operation_id=?", (operation,)).fetchone()
        return json.loads(row[0]) if row else {"operationId": operation, "status": "not_found", "message": "No file write receipt was accepted; no write was submitted by this query"}

    def write(self, environment_id, env, request):
        if not env.get("writeAllowed", False):
            raise FileError("File writes are not enabled for this environment", 403)
        path = request.get("path")
        parts = self.parts(path)
        content = request.get("content")
        if not isinstance(content, str) or len(content.encode()) > LIMIT:
            raise FileError("UTF-8 text content must be at most 256 KiB", 413)
        operation = request.get("operationId")
        if not isinstance(operation, str) or not 1 <= len(operation) <= 80 or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in operation):
            raise FileError("A valid operationId is required")
        fingerprint = hashlib.sha256(json.dumps({"request": request, "environmentId": environment_id, "root": env["root"]}, sort_keys=True).encode()).hexdigest()
        previous = self.database.execute("SELECT fingerprint,receipt FROM file_operations WHERE operation_id=?", (operation,)).fetchone()
        if previous:
            if previous[0] != fingerprint:
                raise FileError("operationId was already used for another file write", 409)
            return json.loads(previous[1])
        before = self.raw(env, path)
        version = "absent" if before is None else self.version(before)
        if request.get("expectedVersion") != version:
            raise FileError("File version changed; read and inspect the actual file before writing", 409)
        value = content.encode("utf-8")
        desired = self.version(value)
        receipt = {"operationId": operation, "environmentId": environment_id, "path": path, "status": "unknown", "desiredVersion": desired, "previousVersion": version, "message": "Write intent recorded; no confirmed result yet"}
        self.database.execute("INSERT INTO file_operations VALUES (?,?,?,?,?,?)", (operation, fingerprint, environment_id, path, desired, json.dumps(receipt)))
        self.database.commit()
        directory = self.directory(env, parts[:-1])
        temporary = ".pocketworld-write-" + uuid.uuid4().hex
        dispatched = False
        try:
            if before is not None:
                self.revision(env, path, before)
            descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
            latest = self.raw(env, path)
            if ("absent" if latest is None else self.version(latest)) != version:
                raise FileError("File changed before commit; no replacement was dispatched", 409)
            dispatched = True
            os.replace(temporary, parts[-1], src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
            actual = self.raw(env, path)
            if actual is None or self.version(actual) != desired:
                raise FileError("File changed after replacement; inspect current content and do not retry blindly", 409)
            receipt.update(status="completed", version=self.revision(env, path, actual), bytes=len(actual), verified=True, message="Actual file content matches the committed version")
        except (OSError, FileError):
            receipt.update(status="unknown" if dispatched else "failed", message="File write result requires inspection; the operation will not be replayed" if dispatched else "No replacement was dispatched; inspect the file before a new operation")
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass
            os.close(directory)
            self.database.execute("UPDATE file_operations SET receipt=? WHERE operation_id=?", (json.dumps(receipt), operation))
            self.database.commit()
        return receipt
