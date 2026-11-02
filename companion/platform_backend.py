"""Narrow platform boundary for the existing Companion, not a second supervisor.

Unsupported legacy features still use their original refusal paths.  In particular,
native Windows support here does not make a POSIX SSH second hop safe.
"""
from __future__ import annotations

import hashlib
import os
import stat
import subprocess
from typing import Protocol


class PlatformError(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status


class NativeProcessStartError(PlatformError):
    """Creation succeeded, but construction did not return a usable process.

    This carries the ORIGINAL process/Job handle and actual construction/cleanup
    facts. A failed Python assignment is never evidence that CreateProcess did
    not run. Callers must retain an unverified original, not retry the command.
    """
    def __init__(self, process, start_facts, cleanup_proof):
        super().__init__("Native process construction failed after process creation; inspect the original operation")
        self.process = process
        self.start_facts = dict(start_facts)
        self.cleanup_proof = dict(cleanup_proof)


def bytes_version(value):
    return "absent" if value is None else "sha256:" + hashlib.sha256(value).hexdigest()


class PlatformBackend(Protocol):
    name: str
    def capabilities(self): ...
    def workspace_files(self, state, database): ...
    def spawn_command(self, argv, *, cwd, **kwargs): ...
    def terminate(self, process, hard=False): ...
    def compare_and_swap(self, root, path, before, after, current=lambda: None): ...
    def workspace_location(self, root, *, current=lambda: None): ...


class PosixBackend:
    name = "posix"

    def capabilities(self):
        bounded = hasattr(os, "O_NOFOLLOW") and hasattr(os, "O_DIRECTORY")
        return {"platform": self.name, "command": True, "boundedFiles": bounded,
                "rawConfigCas": bounded, "processTree": "original_process_group",
                "detachedDescendantIsolationVerified": False, "windowsSshSecondHop": False}

    def workspace_files(self, state, database):
        from workspace_files import WorkspaceFiles
        return WorkspaceFiles(state, database)

    def workspace_location(self, root, *, current=lambda: None):
        """Display path of an ALREADY approved root, checked under its original fd."""
        from workspace_files import WorkspaceFiles
        path = os.fspath(root)
        if not os.path.isabs(path):
            raise PlatformError("An original absolute native workspace root is required", 403)
        files = object.__new__(WorkspaceFiles)
        descriptor = files.directory({"root": path, "kind": "local"}, [])
        try:
            current()
            if not stat.S_ISDIR(os.fstat(descriptor).st_mode):
                raise PlatformError("Original approved workspace directory is unavailable", 403)
            # JobManager has already canonicalized its configured POSIX root.
            # Do not resolve a new caller path or follow any additional link.
            location = os.path.abspath(path)
            current()
            return location
        finally:
            os.close(descriptor)

    def spawn_command(self, argv, *, cwd, **kwargs):
        if not isinstance(argv, list) or not argv or any(not isinstance(x, str) or "\x00" in x for x in argv):
            raise PlatformError("A bounded argv array is required", 400)
        if kwargs.pop("shell", False):
            raise PlatformError("Shell command strings are not accepted", 400)
        return subprocess.Popen(argv, cwd=cwd, start_new_session=True, **kwargs)

    def terminate(self, process, hard=False):
        # Keep the existing, tested POSIX proof and its detached-child limitation.
        from pocket_companion import JobManager
        return JobManager._terminate_group(process, hard)

    def compare_and_swap(self, root, path, before, after, current=lambda: None):
        """Raw bytes, original permissions; optimistic against non-cooperating writers.

        This is a storage primitive. The original HarnessConfigs transaction still
        owns intent persistence, approval, root leases, before-images and recovery.
        """
        from workspace_files import WorkspaceFiles
        import uuid
        if before is not None and not isinstance(before, bytes) or not isinstance(after, bytes):
            raise PlatformError("Configuration CAS requires original bytes", 400)
        if max(len(before or b""), len(after)) > 262144:
            raise PlatformError("Configuration bytes exceed the existing bound", 413)
        # Use the old no-follow, handle-relative traversal, never Path.resolve().
        files = object.__new__(WorkspaceFiles)
        env = {"root": os.fspath(root), "kind": "local"}
        parts = files.parts(path)
        directory = files.directory(env, parts[:-1])
        temporary = ".pocketworld-config-" + uuid.uuid4().hex
        dispatched = False
        try:
            current()
            actual = files.raw(env, path)
            if actual != before:
                raise PlatformError("Configuration changed; original-byte CAS did not match")
            mode = 0o600
            if actual is not None:
                info = os.stat(parts[-1], dir_fd=directory, follow_symlinks=False)
                if info.st_nlink != 1:
                    raise PlatformError("Configuration hard links are not accepted", 403)
                mode = stat.S_IMODE(info.st_mode)
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode, dir_fd=directory)
            with os.fdopen(fd, "wb") as stream:
                stream.write(after)
                os.fchmod(stream.fileno(), mode)
                stream.flush()
                os.fsync(stream.fileno())
            if files.raw(env, path) != before:
                raise PlatformError("Configuration changed before replacement; no replacement dispatched")
            current()
            dispatched = True
            os.replace(temporary, parts[-1], src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
            verified = files.raw(env, path) == after
            return {"replacementAttempted": True, "status": "completed" if verified else "unknown",
                    "verified": verified, "version": bytes_version(after), "consistency": "optimistic_raw_bytes_cas"}
        except OSError as failure:
            if dispatched:
                return {"replacementAttempted": True, "status": "unknown", "verified": False,
                        "consistency": "optimistic_raw_bytes_cas", "message": "Inspect the original operation; never replay a replacement"}
            raise PlatformError("Configuration could not be safely replaced") from failure
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass
            os.close(directory)


def get_backend() -> PlatformBackend:
    if os.name == "nt":
        from platform_windows import WindowsBackend
        return WindowsBackend()
    return PosixBackend()
