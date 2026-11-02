"""Windows implementation of the original WorkspaceFiles public contract.

Uses the SAME file_operations table and revision store. Legacy POSIX directory
descriptor callers remain explicitly unsupported until individually ported.
"""
import hashlib
import json
import ntpath
import os
import re
import stat

from platform_backend import PlatformError, bytes_version
from platform_windows import relative_parts
from workspace_files import FileError, WorkspaceFiles, LIMIT


class WindowsWorkspaceFiles(WorkspaceFiles):
    def __init__(self, state, database, backend):
        super().__init__(state, database)
        self.backend = backend

    @staticmethod
    def parts(value, empty=False):
        try:
            return relative_parts(value, empty)
        except PlatformError as failure:
            raise FileError(str(failure), failure.status) from failure

    def directory(self, env, parts):
        raise FileError("This caller requires POSIX dir_fd semantics; its Windows feature has not been ported", 409)

    def _local(self, env):
        if env.get("kind", "local") != "local":
            raise FileError("Windows bounded file operations require a local native workspace; SSH second hop is unsupported", 409)

    def raw(self, env, path):
        self._local(env)
        try:
            return self.backend.read_bytes(env["root"], path)
        except PlatformError as failure:
            raise FileError(str(failure), failure.status) from failure

    def list(self, env, relative=""):
        self._local(env)
        parts = self.parts(relative, empty=True)
        try:
            with self.backend.directory(env["root"], parts) as (directory, _, identity):
                names = sorted(os.listdir(directory))
                entries = []
                for name in names[:200]:
                    info = os.stat(ntpath.join(directory, name), follow_symlinks=False)
                    reparse = bool(getattr(info, "st_file_attributes", 0) & 0x400)
                    kind = "reparse_point" if reparse else "directory" if stat.S_ISDIR(info.st_mode) else "file" if stat.S_ISREG(info.st_mode) else "unsupported"
                    entries.append({"name": name, "path": relative + "/" + name if relative else name,
                                    "kind": kind, "bytes": info.st_size, "modifiedAt": info.st_mtime_ns // 1000000})
                return {"files": entries, "truncated": len(names) > 200, "path": relative, "directoryIdentity": identity}
        except (OSError, PlatformError) as failure:
            raise FileError("Directory cannot be listed safely on Windows", 403) from failure

    def restore_original(self, environment_id, env, request, original_bytes, current):
        # project_history also needs mode/metadata and snapshot identity semantics.
        # Do not weaken its original Windows refusal merely to reuse this writer.
        raise FileError("Windows project-history restoration is not yet supported", 409)

    def _write_bytes(self, environment_id, env, request, value, current=lambda: None):
        self._local(env)
        if not env.get("writeAllowed", False):
            raise FileError("File replacement is not enabled for this workspace", 403)
        if request.get("projectHistoryProtocol"):
            raise FileError("Windows project-history mode semantics are not supported", 409)
        path, operation = request.get("path"), request.get("operationId")
        self.parts(path)
        if not isinstance(operation, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", operation):
            raise FileError("A valid operationId is required")
        if not isinstance(value, bytes) or len(value) > LIMIT:
            raise FileError("File exceeds the existing byte bound", 413)
        fingerprint = hashlib.sha256(json.dumps({"request": request, "environmentId": environment_id, "root": env["root"]}, sort_keys=True).encode()).hexdigest()
        previous = self.database.execute("SELECT fingerprint,receipt FROM file_operations WHERE operation_id=?", (operation,)).fetchone()
        if previous:
            if previous[0] != fingerprint:
                raise FileError("operationId was already used for another file write", 409)
            return json.loads(previous[1])
        current()
        before = self.raw(env, path)
        version, desired = bytes_version(before), bytes_version(value)
        if request.get("expectedVersion") != version:
            raise FileError("File changed; read and review the actual bytes before writing", 409)
        receipt = {"operationId": operation, "environmentId": environment_id, "path": path,
                   "status": "unknown", "desiredVersion": desired, "previousVersion": version,
                   "consistency": "optimistic_raw_bytes_cas", "message": "Original write intent persisted; no replacement result confirmed"}
        self.database.execute("INSERT INTO file_operations VALUES (?,?,?,?,?,?)", (operation, fingerprint, environment_id, path, desired, json.dumps(receipt)))
        self.database.commit()
        try:
            if before is not None:
                self.revision(env, path, before)
            def record_intent(value):
                receipt["nativeReplacement"] = value
                self.database.execute("UPDATE file_operations SET receipt=? WHERE operation_id=?", (json.dumps(receipt), operation))
                self.database.commit()
            result = self.backend.compare_and_swap(env["root"], path, before, value, current, record_intent=record_intent)
            receipt.update(result)
            if result.get("verified"):
                actual = self.raw(env, path)
                if actual == value:
                    receipt.update(version=self.revision(env, path, actual), bytes=len(actual), message="Actual file bytes match the committed version")
                else:
                    receipt.update(status="unknown", verified=False, message="File changed after replacement; do not replay")
        except PlatformError as failure:
            receipt.update(status="failed", replacementAttempted=False, verified=False, message=str(failure))
        except BaseException:
            # The original intent stays reserved if post-dispatch persistence,
            # verification or cancellation fails. No automatic replacement retry.
            receipt.update(status="unknown", verified=False, message="Inspect the original operation and actual file bytes; never replay")
            raise
        finally:
            self.database.execute("UPDATE file_operations SET receipt=? WHERE operation_id=?", (json.dumps(receipt), operation))
            self.database.commit()
        return receipt
