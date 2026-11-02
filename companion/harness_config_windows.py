"""Win32 storage/process port for the ORIGINAL HarnessConfigs transactions.

This adapter owns no plans, credentials, approvals, receipts, roots or jobs. Those
remain in the original manager and protected SQLite ledger. Configuration writes
are original-byte optimistic CAS, never a field-only comparison or auto-replay.
"""
import hashlib
import os
import queue
import subprocess
import threading
import time

from platform_backend import PlatformError
from platform_windows import local_root

CONFIG_NAMES = {"config.toml", "settings.json"}


class WindowsHarnessStorage:
    def __init__(self, manager):
        self.manager, self.backend = manager, manager.platform
        if self.backend.name != "windows" or not all(callable(getattr(self.backend, name, None)) for name in
                ("directory", "harness_executable_identity", "read_bytes", "compare_and_swap", "delete_harness_configuration")):
            raise PlatformError("Complete native Windows Harness storage/process adapter is unavailable")
        self._lock, self._unsettled, self._closed = threading.RLock(), [], False

    def require_available(self):
        with self._lock:
            if self._closed or self.manager.closed or self._unsettled:
                raise PlatformError("Original Harness probe cleanup is unsettled; configuration remains read-only until local restart")

    def bind_root(self, root):
        self.require_available()
        root = local_root(root)
        with self.backend.directory(root) as (path, _, identity):
            return path, identity

    def executable(self, value):
        self.require_available()
        return self.backend.harness_executable_identity(value)

    def read_optional(self, root, relative):
        try:
            return self.backend.read_bytes(root, relative)
        except PlatformError as failure:
            # A missing optional override directory is absent, not a reason to
            # disable all managed CLI configuration. Access failures, reparse
            # points and other traversal errors remain explicit refusals.
            cause = failure.__cause__
            if isinstance(cause, OSError) and getattr(cause, "winerror", None) in (2, 3):
                return None
            raise

    def version_probe(self, target, executable, timeout):
        self.require_available()
        environment = {key: value for key, value in os.environ.items()
                       if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP", "LANG", "LC_ALL"}}
        process = self.manager._spawn({"root": target["root"], "kind": "local"}, [str(executable), "--version"], target["root"],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment)
        parts, done, failure = queue.Queue(maxsize=10), threading.Event(), []
        def read():
            total = 0
            try:
                while total <= 4096:
                    raw = process.stdout.read(512)
                    if not raw:
                        return
                    total += len(raw)
                    if total > 4096:
                        raise PlatformError("Harness version probe output exceeds its bound")
                    parts.put(raw, timeout=.5)
            except BaseException as error:
                failure.append(error)
            finally:
                done.set()
        reader = threading.Thread(target=read, name="pocket-windows-harness-probe", daemon=True)
        reader.start()
        output, deadline, proof = bytearray(), time.monotonic() + timeout, None
        try:
            while not done.is_set() or not parts.empty():
                if time.monotonic() >= deadline:
                    raise PlatformError("Harness version probe timed out")
                try:
                    output.extend(parts.get(timeout=.02))
                except queue.Empty:
                    pass
                if failure:
                    raise PlatformError("Harness version probe output could not be safely read") from failure[0]
            if failure or process.wait(timeout=max(.001, deadline - time.monotonic())) != 0:
                raise PlatformError("Harness version probe did not return a version")
        except subprocess.TimeoutExpired as error:
            raise PlatformError("Harness version probe timed out") from error
        finally:
            # Original Job Object stop proof includes descendants holding stdout.
            proof = self.manager._terminate(process, hard=True)
            reader.join(timeout=1)
            if reader.is_alive() or not proof or proof.get("processGroupStoppedVerified") is not True:
                # At most the original bounded probe slots can get here. Retain
                # their real handles and latch the feature unavailable instead
                # of leaking unbounded reader threads/probes on each retry.
                with self._lock:
                    self._unsettled.append((process, reader))
            else:
                process.stdout.close()
                if callable(getattr(process, "close", None)):
                    process.close()
        if reader.is_alive() or not proof or proof.get("processGroupStoppedVerified") is not True:
            raise PlatformError("Original Harness probe process-tree/pipe cleanup was not verified")
        return bytes(output)

    def replace(self, target, name, before, after, current, record_intent, *, original_file_identity=None):
        self.require_available()
        if name not in CONFIG_NAMES or before is not None and not isinstance(before, bytes) or after is not None and not isinstance(after, bytes):
            raise PlatformError("Only original reviewed Harness configuration bytes may be replaced", 400)
        if after is None:
            if before is None or original_file_identity is None:
                raise PlatformError("Created-file restoration lacks original committed file identity; no deletion dispatched")
            current()
            if self.backend.read_bytes(target["root"], name) != before:
                raise PlatformError("Created Harness file changed; nothing deleted")
            intent = {"phase": "delete_intent_persisted", "target": name, "fileIdentity": original_file_identity,
                      "beforeSha256": hashlib.sha256(before).hexdigest(), "recoveryPolicy": "never_replay_against_a_replacement"}
            def before_dispatch():
                current()
                record_intent(intent)
            result = self.backend.delete_harness_configuration(target["root"], name, before, original_file_identity, before_dispatch)
            return {**result, "nativeDeletion": intent, "consistency": "optimistic_raw_bytes_cas"}
        return self.backend.compare_and_swap(target["root"], name, before, after, current, record_intent=record_intent)

    def close(self):
        with self._lock:
            self._closed = True
            pending = tuple(self._unsettled)
        for process, reader in pending:
            self.manager._terminate(process, hard=True)
            # Closing OUR original KillOnClose job requests final cleanup. This
            # is not represented as verified stop if native querying failed.
            if callable(getattr(process, "close", None)):
                process.close()
            reader.join(timeout=1)
            if not reader.is_alive():
                process.stdout.close()
