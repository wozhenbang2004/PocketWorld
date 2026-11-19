"""Owner's local recognition of one accepted command alias. No arguments, paths, launch or wire data."""
import hashlib
import json
import re

import device_protocol as protocol
from windows_received_job import COMMAND, retained

FIELD="windowsOwnerCommandAliasSealV1"
ALIAS=re.compile(r"[A-Za-z][A-Za-z0-9_-]{0,47}")
HEX=re.compile(r"[a-f0-9]{64}")


def _direct(source,accepted):
    return (source.get("toolName")==COMMAND and source.get("capabilityVersion")=="workspace-v1"
            and accepted.get("kind")=="operation" and "sourceShareId" not in accepted
            and source.get("originDeviceId")==source.get("callerDeviceId")==accepted.get("callerDeviceId")
            and bool(source.get("rootTaskId")) and source["rootTaskId"]==source.get("parentTaskId")==source.get("localTaskId")
            and source["rootTaskId"]==accepted.get("rootTaskId")==accepted.get("localTaskId")
            and source.get("requestRunId")==accepted.get("requestRunId") and bool(accepted.get("requestRunId"))
            and type(accepted.get("requestExecutorEpoch")) is int and source.get("requestExecutorEpoch")==accepted["requestExecutorEpoch"])


def _names(manager,accepted):
    # Keys only, in THIS already-loaded local environment. Never resolve a path, stat a program or run discovery.
    binding=accepted["resourceBinding"]
    env=manager.environments.get(binding["environmentId"])
    if (binding.get("kind")!="workspace" or not isinstance(env,dict) or env.get("kind","local")!="local"
            or "command" not in env.get("workers",["command"])): return ()
    mapping=env.get("commands")
    return mapping.keys() if isinstance(mapping,dict) and len(mapping)<=128 else ()


def _seal(source,accepted,job_id,name):
    return hashlib.sha256(protocol.canonical_bytes([FIELD,accepted,source,job_id,name])).hexdigest()


def witnessed(manager,request,job,detail):
    """Just after actual allowlist resolution, inside the ORIGINAL admission transaction. Optional local evidence only."""
    try:
        source,accepted=detail["audience"],detail["historyAudience"]
        if (manager.platform.name!="windows" or not _direct(source,accepted) or job.get("worker")!="command"
                or request.get("worker")!="command" or job["operationId"]!=accepted["objectId"]
                or job["fingerprint"]!=accepted["argumentsDigest"] or job["environmentId"]!=accepted["resourceBinding"]["environmentId"]
                or request.get("operationId")!=job["operationId"] or request.get("environmentId")!=job["environmentId"]
                or not protocol.resource_equal(job["nativeDeviceOperation"],source)): return None
        name=request["argv"][0]
        if isinstance(name,str) and ALIAS.fullmatch(name) and name in _names(manager,accepted):
            return _seal(source,accepted,job["id"],name)
    except (AttributeError,IndexError,KeyError,TypeError,ValueError,RecursionError):
        pass  # Never change acceptance or command dispatch if the optional receipt cannot be recorded.
    return None


def available(raw,original,grant):
    source,accepted=original
    if not _direct(source,accepted) or COMMAND not in grant.get("toolNames",[]): return False
    try: seal=json.loads(raw).get(FIELD)
    except (AttributeError,TypeError,ValueError,RecursionError): return False
    return isinstance(seal,str) and HEX.fullmatch(seal) is not None


def local(manager,op,row,original,grant,revision):
    # Original peer, grant, withdrawal, expiry, operation, fingerprint and revision checked by local_record.
    if type(revision) is not int or revision<=0 or not available(row[3],original,grant): return None
    source,accepted=original
    try:
        fixed=retained(manager,op,row,original)
        if not fixed or revision!=fixed["nativeDeviceRevision"]: return None
        seal=json.loads(row[3])[FIELD]
        # This computes candidate hashes, not a guess from the currently configured executable or its path.
        matches=[n for n in _names(manager,accepted) if isinstance(n,str) and ALIAS.fullmatch(n)
                 and _seal(source,accepted,row[1],n)==seal]
        return matches[0] if len(matches)==1 else None
    except (AttributeError,KeyError,TypeError,ValueError,RecursionError):
        return None
