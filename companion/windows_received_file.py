"""Read-only view of a DIRECT, original phone-selected file delivered to Windows.

Uses the immutable admission, retained job and ORIGINAL native file CAS ledger.
No scan/read of today's destination, source attachment, byte cache or secret; no replay.
"""
import hashlib
import json
import re
import sqlite3

import device_protocol as protocol
from device_artifacts import ArtifactError, from_wire_descriptor
from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_received_job import FILE, retained
from windows_workspace_read import _plain

PROFILE = "windows-original-file-delivery-v1"


def _digest(raw): return hashlib.sha256(raw).hexdigest()


def accepted(manager,envelope,op,env):
    if (manager.platform.name!="windows" or envelope.arguments["toolName"]!=FILE or envelope.arguments["effect"]!="write"
            or envelope.get("originDeviceId")!=envelope.get("callerDeviceId") or envelope.get("parentTaskId")!=envelope.get("rootTaskId")): return {}
    if not callable(getattr(manager.files,"write_fingerprint",None)): return {}
    args=envelope.arguments["arguments"]; eid=envelope.get("resourceBinding")["environmentId"]
    request={"path":args["path"],"expectedVersion":args["expectedVersion"],"operationId":op,"inputArtifact":args["artifact"]}
    return {"windowsIncomingFile":{"format":PROFILE,"inputDigest":_digest(protocol.canonical_bytes(args)),
            "fileOperationFingerprint":manager.files.write_fingerprint(eid,env,request)}}


def _id(value):
    return isinstance(value,list) and len(value)==3 and all(type(n) is int and 0<=n<2**32 for n in value) and any(value[1:])


def project(manager,op,row,original):
    source,acceptance=original
    value={"profile":PROFILE,"operationId":op,"jobId":row[1],"argumentsDigest":row[0],"tool":FILE,
           "environmentId":acceptance["resourceBinding"]["environmentId"],"phase":"receipt_only","updatedAt":acceptance["acceptedAt"],
           "targetReread":False,"transferRepeated":False}
    if source["toolName"]!=FILE: return value
    try:
        detail=json.loads(row[3]); witness=detail.get("windowsIncomingFile")
        if not witness or witness.get("format")!=PROFILE: return value
        fixed=retained(manager,op,row,original)
        if not fixed: return value
        args=fixed["nativeInput"]; result=fixed["nativeToolResult"]
        if (not protocol.resource_equal(args,manager.jobs[row[1]].get("nativeInput"))
                or not protocol.resource_equal(result,manager.jobs[row[1]].get("nativeToolResult"))
                or witness["inputDigest"]!=_digest(protocol.canonical_bytes(args))): return value
        asset=from_wire_descriptor(args["artifact"])
        path=args["path"]; relative_parts(path)
        sha,size,transfer=asset["sha256"],asset["bytes"],asset["transferId"]
        want="sha256:"+sha; before=args["expectedVersion"]; when=fixed["updatedAt"]
        if (asset["kind"]!="file" or asset["operationId"]!=op or asset["taskId"]!=acceptance["localTaskId"]
                or asset["sourceDeviceId"]!=acceptance["callerDeviceId"] or asset["requestRunId"]!=acceptance["requestRunId"]
                or asset["requestExecutorEpoch"]!=acceptance["requestExecutorEpoch"] or "runId" in asset or "executorEpoch" in asset
                or type(size) is not int or not 0<=size<=262144 or not re.fullmatch("absent|sha256:[0-9a-f]{64}",before)
                or transfer!=detail["inputTransferId"] or type(when) is not int or when<acceptance["acceptedAt"]
                or not re.fullmatch("[0-9a-f]{64}",witness["fileOperationFingerprint"])): return value
        ev={**value,"path":_plain(path),"pathDigest":_digest(path.encode()),"sha256":sha,"bytes":size,"expectedVersion":before,
            "transferId":transfer,"inputDigest":witness["inputDigest"],"updatedAt":when,"revision":fixed["nativeDeviceRevision"]}
        # No call to files.read()/raw(), no retry of an earlier CAS when a phone reconnects.
        file_row=manager.files.database.execute("SELECT fingerprint,environment_id,path,desired_version,receipt FROM file_operations WHERE operation_id=?",(op,)).fetchone()
        if file_row is None:
            if (fixed["status"]=="running" and fixed.get("nativeInputDispatchStarted") is not True
                    and result.get("phase")=="awaiting_bytes" and result.get("delivered") is False
                    and (result.get("transferId"),result.get("bytes"),result.get("sha256"))==(transfer,size,sha)
                    and type(result.get("verifiedOffset")) is int and 0<=result["verifiedOffset"]<=size):
                return {**ev,"phase":"receiving","verifiedOffset":result["verifiedOffset"]}
            return value
        if tuple(file_row[:4])!=(witness["fileOperationFingerprint"],ev["environmentId"],path,want): return value
        f=json.loads(file_row[4]); native=f.get("nativeReplacement") or {}; objects=native.get("objects") or {}
        target=objects.get("target") or {}; prev=native.get("beforeObject") or {}; backup=objects.get("backup") or {}
        if (fixed.get("nativeInputDispatchStarted") is not True or f.get("operationId")!=op or f.get("environmentId")!=ev["environmentId"]
                or f.get("path")!=path or f.get("desiredVersion")!=want or f.get("consistency")!="optimistic_raw_bytes_cas"): return value
        mismatch=f.get("targetConditionMatched") is False and f.get("reviewedVersion")==before and "previousVersion" not in f
        if not mismatch and f.get("previousVersion")!=before: return value
        if (f.get("status")=="failed" and f.get("replacementAttempted") is False and f.get("verified") is False and not native
                and fixed["status"]=="failed" and result.get("delivered") is False and result.get("transferId")==transfer
                and result.get("sha256")==sha and protocol.canonical_bytes({k:result[k] for k in f})==protocol.canonical_bytes(f)):
            return {**ev,"phase":"not_dispatched","targetConditionFailed":mismatch}
        if mismatch: return value
        if f.get("status")=="unknown" and f.get("verified") is not True:
            return {**ev,"phase":"target_unconfirmed"}
        if (f.get("status")!="completed" or f.get("verified") is not True
                or f.get("replacementAttempted") is not True or f.get("version")!=want or type(f.get("bytes")) is not int or f["bytes"]!=size
                or native.get("phase")!="readback_verified" or native.get("target")!=path or native.get("afterSha256")!=sha
                or not _id(native.get("temporaryIdentity")) or target.get("fileIdentity")!=native["temporaryIdentity"]
                or target.get("state")!="present" or target.get("sha256")!=sha or type(target.get("bytes")) is not int or target["bytes"]!=size
                or not _id(f.get("parentIdentity")) or f["parentIdentity"]!=native.get("parentIdentity")): return value
        # The native writer always persisted this key, with explicit JSON null ONLY for an absent original.
        old_sha=native["beforeSha256"]  # missing/invalid is unknown, never normalized to absent
        if before=="absent":
            if old_sha is not None or prev!={"state":"absent"} or backup!={"state":"not_requested"}: return value
        elif (not isinstance(old_sha,str) or old_sha!=before[7:] or prev.get("state")!="present" or prev.get("sha256")!=before[7:]
                or backup.get("sha256")!=before[7:] or not _id(prev.get("fileIdentity")) or backup.get("fileIdentity")!=prev["fileIdentity"]): return value
        if fixed["status"]=="completed":
            if (result.get("delivered") is not True or result.get("transferId")!=transfer or result.get("sha256")!=sha
                    or protocol.canonical_bytes({k:result[k] for k in f})!=protocol.canonical_bytes(f)): return value
            return {**ev,"phase":"delivered","version":want}
        if fixed["status"] in ("unknown","interrupted","running"):
            return {**ev,"phase":"write_verified_only","version":want}
        return value
    except (AttributeError,ValueError,TypeError,KeyError,UnicodeError,RecursionError,sqlite3.Error,ArtifactError,PlatformError,protocol.ProtocolError):
        return value
