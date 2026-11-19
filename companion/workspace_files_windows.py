"""Windows implementation of the original WorkspaceFiles public contract.

Uses the SAME file_operations table and revision store. Legacy POSIX directory
descriptor callers remain explicitly unsupported until individually ported.
"""
import difflib
import hashlib
import json
import ntpath
import os
import re
import stat
import sqlite3

from platform_backend import PlatformError, bytes_version
from platform_windows import relative_parts
from workspace_files import FileError, WorkspaceFiles, LIMIT
from windows_file_revisions import NativeFileVersions


class WindowsWorkspaceFiles(WorkspaceFiles):
    def __init__(self, state, database, backend):
        super().__init__(state, database)
        self.backend = backend
        self.versions = NativeFileVersions(state,database,backend)

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

    def revision(self,env,path,value):
        try: return self.versions.remember(env,path,value)
        except (OSError,ValueError,TypeError,PlatformError,sqlite3.Error) as error:
            raise FileError("Exact private Windows file version could not be retained",409) from error

    def read(self,env,path):
        self._local(env)
        with self.versions.lock,self.backend.directory(env["root"]):
            return self._read_pinned(env,path)

    def _read_pinned(self,env,path):
        value=self.raw(env,path)
        if value is None: raise FileError("File does not exist",404)
        try: content=value.decode("utf-8")
        except UnicodeError: raise FileError("File is not UTF-8 text; use the binary artifact transfer",409) from None
        version=bytes_version(value)
        # A real current read/version is not a promise the private historical copy was saved.
        try: retained=self.revision(env,path,value)==version
        except FileError: retained=False
        return {"path":path,"content":content,"version":version,"bytes":len(value),"revisionRetained":retained}

    def diff(self,env,path,baseline):
        self._local(env)
        with self.versions.lock,self.backend.directory(env["root"]):
            return self._diff_pinned(env,path,baseline)

    def _diff_pinned(self,env,path,baseline):
        try: before=self.versions.read(env,path,baseline).decode("utf-8")
        except (OSError,ValueError,TypeError,PlatformError,sqlite3.Error) as failure:
            raise FileError("Original bound Windows text baseline is unavailable; no legacy/path fallback",409) from failure
        now=self.read(env,path)
        difference="".join(difflib.unified_diff(before.splitlines(keepends=True),now["content"].splitlines(keepends=True),
            fromfile=baseline+"/"+path,tofile=now["version"]+"/"+path))
        return {"path":path,"fromVersion":baseline,"version":now["version"],"diff":difference[:LIMIT],"truncated":len(difference)>LIMIT}

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

    @staticmethod
    def write_fingerprint(environment_id,env,request):
        return hashlib.sha256(json.dumps({"request": request, "environmentId": environment_id, "root": env["root"]}, sort_keys=True).encode()).hexdigest()

    def receive_bytes(self,environment_id,env,request,value,current):
        # Called only by already-admitted, byte-verified device input.
        return self._write_bytes(environment_id,env,request,value,current,_received=True)

    def _write_bytes(self, environment_id, env, request, value, current=lambda: None, *, _received=False):
        self._local(env)
        with self.versions.lock,self.backend.directory(env["root"]):
            return self._write_pinned(environment_id,env,request,value,current,_received)

    def _write_pinned(self,environment_id,env,request,value,current,_received):
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
        fingerprint = self.write_fingerprint(environment_id,env,request)
        previous = self.database.execute("SELECT fingerprint,receipt FROM file_operations WHERE operation_id=?", (operation,)).fetchone()
        if previous:
            if previous[0] != fingerprint:
                raise FileError("operationId was already used for another file write", 409)
            return json.loads(previous[1])
        current()
        before = self.raw(env, path)
        version, desired = bytes_version(before), bytes_version(value)
        if request.get("expectedVersion") != version:
            if _received:
                # The actual original upload was admitted, but the native target condition failed
                # BEFORE any temp file/replacement. Reserve this exact negative outcome now, not
                # a guess from a later missing row. Don't disclose the substitute file's digest.
                current()
                receipt={"operationId":operation,"environmentId":environment_id,"path":path,"status":"failed",
                         "verified":False,"replacementAttempted":False,"targetConditionMatched":False,
                         "reviewedVersion":request["expectedVersion"],"desiredVersion":desired,"consistency":"optimistic_raw_bytes_cas",
                         "message":"Reviewed destination no longer matches; no target replacement dispatched. Do not replay."}
                self.database.execute("INSERT INTO file_operations VALUES (?,?,?,?,?,?)",(operation,fingerprint,environment_id,path,desired,json.dumps(receipt)))
                self.database.commit()
                return receipt
            raise FileError("File changed; read and review the actual bytes before writing", 409)
        receipt = {"operationId": operation, "environmentId": environment_id, "path": path,
                   "status": "unknown", "desiredVersion": desired, "previousVersion": version,
                   "consistency": "optimistic_raw_bytes_cas", "message": "Original write intent persisted; no replacement result confirmed"}
        self.database.execute("INSERT INTO file_operations VALUES (?,?,?,?,?,?)", (operation, fingerprint, environment_id, path, desired, json.dumps(receipt)))
        self.database.commit()
        try:
            if before is not None:
                try: self.revision(env,path,before)
                except FileError:
                    receipt.update(status="failed",verified=False,replacementAttempted=False,
                                   message="Original previous file version could not be retained; no target replacement dispatched.")
                    return receipt
            def record_intent(value):
                receipt["nativeReplacement"] = value
                self.database.execute("UPDATE file_operations SET receipt=? WHERE operation_id=?", (json.dumps(receipt), operation))
                self.database.commit()
            result = self.backend.compare_and_swap(env["root"], path, before, value, current, record_intent=record_intent)
            receipt.update(result)
            if result.get("verified"):
                actual = self.raw(env, path)
                if actual == value:
                    try: saved=self.revision(env,path,actual)==desired
                    except FileError: saved=False
                    # Target readback already matched. An optional after-copy failing
                    # is not proof the user's original file write failed or never ran.
                    receipt.update(version=desired,bytes=len(actual),revisionRetained=saved,message="Actual file bytes match the committed version")
                else:
                    receipt.update(status="unknown", verified=False, message="File changed after replacement; do not replay")
        except PlatformError as failure:
            if receipt.get("replacementAttempted") is True:
                # Never erase an already returned native dispatch fact on a later failure.
                receipt.update(status="unknown",verified=False,message="Native replacement was attempted; final verification unavailable. Do not replay.")
            else:
                receipt.update(status="failed",replacementAttempted=False,verified=False,message=str(failure))
        except BaseException:
            # The original intent stays reserved if post-dispatch persistence,
            # verification or cancellation fails. No automatic replacement retry.
            receipt.update(status="unknown", verified=False, message="Inspect the original operation and actual file bytes; never replay")
            raise
        finally:
            self.database.execute("UPDATE file_operations SET receipt=? WHERE operation_id=?", (json.dumps(receipt), operation))
            self.database.commit()
        return receipt
