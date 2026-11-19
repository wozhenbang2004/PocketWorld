"""Owner-only Linux config inventory. No Manager, listener, secret contents, worker launch or write."""
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys
import harness_registry

PROTOCOL = "pocketworld.owner-workspace-check.v1"
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}")


class CheckError(ValueError):
    pass


def _metadata(raw):
    if not isinstance(raw, str) or not raw or chr(0) in raw:
        return None
    try:
        return Path(raw).expanduser().stat()
    except (OSError, ValueError, RuntimeError):
        return None


def _read_config(path):
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > 1048576:
            raise CheckError("Owner check requires a regular configuration file of at most 1 MiB")
        raw = source.read(1048577)
        if len(raw) > 1048576:
            raise CheckError("Configuration changed or exceeded 1 MiB")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise CheckError("Expected one Companion configuration object")
        return value


def inspect(path, environment, native_workers, native_presence):
    if not sys.platform.startswith("linux"):
        raise CheckError("This local workspace check is for the Linux Companion owner")
    cfg = _read_config(path)
    envs = cfg.get("environments")
    if not isinstance(envs, list) or len(envs) > 40:
        raise CheckError("Expected up to 40 configured environments")
    ids, results = set(), []
    for item in envs:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not _ID.fullmatch(item["id"]) or item["id"] in ids:
            raise CheckError("Invalid or duplicate environment identity")
        eid = item["id"]
        ids.add(eid)
        if environment and eid != environment:
            continue
        if item.get("kind", "local") != "local":
            results.append({"id": eid, "state": "not_local_to_this_host"})
            continue
        root = _metadata(item.get("root"))
        if root is None or not stat.S_ISDIR(root.st_mode):
            results.append({"id": eid, "state": "workspace_directory_missing"})
            continue
        enabled = item.get("workers", ["command"])
        if not isinstance(enabled, list) or len(enabled) > 128 or any(not isinstance(k, str) or not _ID.fullmatch(k) for k in enabled):
            results.append({"id": eid, "state": "invalid_worker_list"})
            continue
        try:
            if ("writeAllowed" in item and not isinstance(item["writeAllowed"], bool)) or "kimi" in enabled and item.get("allowAutonomousWorkers") is not True:
                raise ValueError("Existing execution policy does not admit this definition")
            harness_registry.validate_environment(item)
        except (ValueError, KeyError, TypeError):
            results.append({"id": eid, "state": "invalid_worker_definition"})
            continue
        worker_rows = []
        for k in enabled:
            if k == "command" or k in native_workers:
                present = native_presence(item, k)  # Same executable observations as the HTTP environment catalog.
                reason = "requires_native_probe" if present is None else "file_observed" if present else "not_runnable_in_this_account"
            elif harness_registry.external(k):
                try:
                    observed = harness_registry.executable_identity(harness_registry.configuration(item, k))
                    present = observed["available"]
                    reason = "requires_protocol_probe" if present else observed["reason"]
                except (KeyError, ValueError, TypeError, OSError):
                    present, reason = False, "invalid_harness_definition"
            else:
                present, reason = False, "unrecognized_worker"
            worker_rows.append({"id": k, "executableObserved": present, "stage": reason})
        agents = [r for r in worker_rows if r["id"] != "command"]
        state = "command_only" if not agents else "agent_requires_probe" if any(r["executableObserved"] is not False for r in agents) else "no_observed_agent"
        results.append({"id": eid, "state": state, "allowsWrites": item.get("writeAllowed") is True, "workers": worker_rows})
    if environment and environment not in ids:
        raise CheckError("Selected environment does not exist")
    host = cfg.get("listen", "127.0.0.1")
    try:
        local = ipaddress.ip_address(host).is_loopback
    except ValueError:
        local = host == "localhost"
    token = _metadata(cfg.get("tokenFile"))
    private = token is not None and stat.S_ISREG(token.st_mode) and not token.st_mode & 0o077
    tls = [_metadata(cfg.get(k)) for k in ("tlsCert", "tlsKey")]
    endpoint = "loopback_only" if local else "direct_bind_configured_not_verified" if cfg.get("allowRemote") is True and all(x and stat.S_ISREG(x.st_mode) for x in tls) else "direct_bind_prerequisites_missing"
    return {"protocol": PROTOCOL, "endpoint": endpoint, "privateTokenFileObserved": bool(private), "tokenContentsRead": False,
            "environments": results, "serverContacted": False, "workerStarted": False, "configurationChanged": False,
            "operationLedgerInspected": False, "accountOrModelVerified": False}
