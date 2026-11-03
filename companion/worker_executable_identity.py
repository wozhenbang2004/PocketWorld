"""Cheap entrypoint observations for original worker metadata; no process or registry.

The digest binds native-file STAT metadata, not file bytes or a CLI version. A native
entrypoint may itself be a launcher: execution-chain/content verification is NEVER
inferred. SSH, scripts, known interpreters and unsupported platforms stay unknown.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys

_NAMES = {"codex_app_server": "codex", "codex": "codex", "claude": "claude", "pi": "pi"}
_INTERPRETER = re.compile(r"(?:python[0-9.]*|pypy[0-9.]*|node(?:js)?|ruby[0-9.]*|perl[0-9.]*|(?:ba|da|z|fi|k)?sh|busybox|env|java|dotnet|pwsh|php[0-9.]*|deno|bun)", re.I)
_NATIVE = {b"\x7fELF"}


def unknown(reason):
    return {"executableIdentity": None, "executableIdentityKind": "unverified",
            "executableIdentityVerified": False, "executableIdentityReason": reason,
            "versionContentVerified": False, "executionChainVerified": False}


def _stamp(info):
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode]


def inspect(environment, worker, launch=None):
    """Observe only the original local worker entrypoint, resolving PATH at its cwd.

    Optional actual launch argv lets the Codex probe reject an unbound launcher.
    Only a 64-hex opaque digest leaves this function: never path, environment or stat.
    """
    if environment.get("kind", "local") != "local":
        return unknown("ssh_entrypoint_unverified")
    if os.name != "posix" or not sys.platform.startswith("linux") or not hasattr(os, "O_NOFOLLOW"):
        return unknown("platform_entrypoint_unverified")
    name = _NAMES.get(worker)
    if name is None:
        return unknown("worker_entrypoint_unimplemented")
    try:
        root = Path(environment["root"]).resolve(strict=True)
        if not root.is_dir():
            return unknown("worker_cwd_unavailable")
        # execvp interprets relative and empty PATH entries after the child chdir.
        search = os.pathsep.join(str(root / part) if not os.path.isabs(part) else part
                                 for part in os.get_exec_path())
        located = shutil.which(name, path=search)
        if not located:
            return unknown("worker_entrypoint_missing")
        selected = Path(located).resolve(strict=True)
        if _INTERPRETER.fullmatch(selected.name):
            return unknown("interpreter_entrypoint_unverified")
        if launch is not None and (not launch or launch[0] not in (name, str(selected), str(Path(located).absolute()))):
            return unknown("worker_launch_unbound")
        if not stat.S_ISREG(selected.stat().st_mode) or not os.access(selected, os.X_OK):
            return unknown("worker_entrypoint_not_executable")
        descriptor = os.open(selected, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0))
        try:
            before = os.fstat(descriptor)
            if not stat.S_ISREG(before.st_mode) or not os.access(selected, os.X_OK):
                return unknown("worker_entrypoint_not_executable")
            if os.read(descriptor, 4) not in _NATIVE:
                return unknown("script_or_unknown_entrypoint")
            after = os.fstat(descriptor)
            if _stamp(before) != _stamp(after) or _stamp(selected.stat()) != _stamp(after):
                return unknown("worker_entrypoint_changed_during_read")
            value = ["pocketworld/native-entrypoint-stat/v1", worker, str(root), str(selected), _stamp(after)]
            opaque = hashlib.sha256(json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()
            return {"executableIdentity": opaque, "executableIdentityKind": "native_file_stat_v1",
                    "executableIdentityVerified": True, "executableIdentityReason": "native_entrypoint_stat_observed",
                    "versionContentVerified": False, "executionChainVerified": False}
        finally:
            os.close(descriptor)
    except (OSError, KeyError, ValueError, RuntimeError):
        return unknown("worker_entrypoint_unavailable")


def finish_probe(before, after):
    if (before.get("executableIdentityVerified") is True and after.get("executableIdentityVerified") is True
            and before.get("executableIdentity") == after.get("executableIdentity")):
        return dict(after)
    if before.get("executableIdentityVerified") is True or after.get("executableIdentityVerified") is True:
        return unknown("worker_entrypoint_changed_during_probe")
    return dict(after)
