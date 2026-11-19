"""Local display only of an original Windows read's exact registered root; no Explorer or peer API."""
from contextlib import ExitStack
from dataclasses import dataclass
import hashlib
import json
import re
import unicodedata

import device_protocol as protocol
from platform_backend import PlatformError
from windows_file_revisions import _identity
from windows_received_job import READS
from windows_workspace_read import project


def _seal(manager, handle, binding, operation, fingerprint, job):
    # File indexes can be reused; also require the original directory creation identity.
    return hashlib.sha256(protocol.canonical_bytes(["windows-read-root-v1", operation, fingerprint, job, binding,
                                                    _identity(manager.platform.api, handle)])).hexdigest()


def same_read(receiver, envelope, permission, env, operation, job):
    manager = receiver.manager
    # Hold the actual non-reparse ancestors throughout the ONE previously admitted list/read.
    # No extra directory listing or file read, and no location/path in its wire result.
    with ExitStack() as held:
        try: _, root, _ = held.enter_context(manager.platform.directory(env["root"]))
        except (OSError, KeyError, TypeError, ValueError, PlatformError): root = None
        result = receiver._workspace(envelope, permission)
        if root is None: return result, None  # Optional local evidence never retries or replaces the actual read.
        try: seal = _seal(manager, root, envelope.get("resourceBinding"), operation, job["fingerprint"], job["id"])
        except (OSError, ValueError, TypeError, AttributeError, PlatformError, protocol.ProtocolError): seal = None
        return result, seal


@dataclass(frozen=True)
class FileRead:
    original_work: object
    operation: str
    job: str
    fingerprint: str
    revision: int
    path: str
    size: int
    sha: str
    origin: tuple


def local(manager, operation, row, original, grant, now, revision, *, display, match_source=False):
    source, accepted = original
    if (source["toolName"] not in READS or "sourceShareId" in accepted or type(revision) is not int or revision <= 0
            or now >= grant["expiresAt"] or "tool.invoke" not in grant["capabilities"]
            or not {source["toolName"], "workspace.location"}.issubset(grant["toolNames"])): return None
    if match_source and source["toolName"]!="workspace.read": return None
    try:
        seal = json.loads(row[3]).get("windowsReadRootSeal")
        if not isinstance(seal, str) or not re.fullmatch("[a-f0-9]{64}", seal): return None
        if not display: return True  # Offering/retiring this local view does not touch the workspace.
        snapshot = project(manager, operation, row, original)
        if snapshot["phase"] not in {"directory_snapshot","text_snapshot"} or snapshot["revision"] != revision: return None
        binding = accepted["resourceBinding"]
        eid = binding["environmentId"]
        env = manager.environments.get(eid)
        if env is None or env.get("kind","local") != "local": return None
        from device_companion import workspace_binding
        with manager.platform.directory(env["root"]) as (_, root, _):
            if (not protocol.resource_equal(workspace_binding(manager, eid), binding)
                    or seal != _seal(manager, root, binding, operation, row[0], row[1])): return None
            if match_source:
                from windows_read_work import from_original
                work=from_original(original); p=snapshot["path"]
                if (snapshot["phase"]!="text_snapshot" or not work or hashlib.sha256(p.encode()).hexdigest()!=snapshot["pathDigest"]
                        or not re.fullmatch("sha256:[a-f0-9]{64}",snapshot["version"])): return None
                return FileRead(work,operation,row[1],row[0],revision,p,snapshot["bytes"],snapshot["version"][7:],
                                tuple(_identity(manager.platform.api,root)))
            path = manager.platform.workspace_location(env["root"])
            return path if not any(unicodedata.category(ch).startswith("C") for ch in path) else None
    except (OSError, KeyError, TypeError, ValueError, AttributeError, RecursionError, PlatformError, protocol.ProtocolError):
        return None
