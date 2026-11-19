"""Windows owner's metadata-only receipt for ONE already admitted workspace.write. Never consult today's file."""
import hashlib
import json
import re
import sqlite3

import device_protocol as protocol
from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_received_file import verified_replacement
from windows_received_job import TEXT_WRITE, retained
from windows_workspace_read import _plain

PROFILE = "windows-original-text-write-v1"
VERSION = re.compile(r"absent|sha256:[a-f0-9]{64}")
SHA = re.compile(r"[a-f0-9]{64}")


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def accepted(manager, envelope, operation, env):
    # Do not alter original write admission, persist duplicate text or save the workspace's absolute path.
    if (envelope.arguments.get("effect")!="write" or envelope.get("originDeviceId")!=envelope.get("callerDeviceId")
            or envelope.get("parentTaskId")!=envelope.get("rootTaskId") or not callable(getattr(manager.files,"write_fingerprint",None))): return {}
    try:
        args=envelope.arguments["arguments"]; path=args["path"]; expected=args["expectedVersion"]
        if set(args)!={"path","expectedVersion","content"} or not isinstance(expected,str) or not VERSION.fullmatch(expected): return {}
        relative_parts(path); data=args["content"].encode("utf-8")
        if len(data)>262144: return {}
        file_fingerprint=manager.files.write_fingerprint(envelope.get("resourceBinding")["environmentId"],env,{**args,"operationId":operation})
        return {"windowsOriginalTextWrite":{"format":PROFILE,"wireDigest":protocol.arguments_digest(envelope.json()),
                "pathDigest":_sha(path.encode()),"expectedVersion":expected,"sha256":_sha(data),"bytes":len(data),
                "fileOperationFingerprint":file_fingerprint}}
    except (AttributeError,ValueError,TypeError,KeyError,PlatformError,protocol.ProtocolError): return {}


def project(manager, operation, row, original):
    source, accepted_scope=original
    base={"profile":PROFILE,"phase":"receipt_only","operationId":operation,"jobId":row[1],"environmentId":accepted_scope["resourceBinding"]["environmentId"],
          "updatedAt":accepted_scope["acceptedAt"]}
    if source.get("toolName")!=TEXT_WRITE or "sourceShareId" in accepted_scope: return base
    try:
        w=json.loads(row[3])["windowsOriginalTextWrite"]; before=w["expectedVersion"]; sha=w["sha256"]; size=w["bytes"]; file_fp=w["fileOperationFingerprint"]
        if (w["format"]!=PROFILE or w["wireDigest"]!=row[0] or not VERSION.fullmatch(before) or not SHA.fullmatch(sha)
                or type(size) is not int or not 0<=size<=262144 or not SHA.fullmatch(file_fp) or not SHA.fullmatch(w["pathDigest"])): return base
        fixed=retained(manager,operation,row,original)
        if not fixed or type(fixed["updatedAt"]) is not int or fixed["updatedAt"]<base["updatedAt"]: return base
        original_state={**base,"phase":"write_unconfirmed","updatedAt":fixed["updatedAt"],"revision":fixed["nativeDeviceRevision"]}
        file_row=manager.files.database.execute("SELECT fingerprint,environment_id,path,desired_version,receipt FROM file_operations WHERE operation_id=?",(operation,)).fetchone()
        want="sha256:"+sha
        if not file_row: return original_state  # Includes early version mismatch with no original file WAL; never infer it.
        path=file_row[2]; relative_parts(path)
        if tuple(file_row[:2])!=(file_fp,base["environmentId"]) or file_row[3]!=want or _sha(path.encode())!=w["pathDigest"]: return base
        f=json.loads(file_row[4])
        if (f["operationId"]!=operation or f["environmentId"]!=base["environmentId"] or f["path"]!=path or f["desiredVersion"]!=want
                or f["previousVersion"]!=before or f["consistency"]!="optimistic_raw_bytes_cas"): return base
        ev={**original_state,"path":_plain(path),"expectedVersion":before,"desiredVersion":want,"bytes":size}
        cached=manager.jobs[row[1]]
        if ("nativeToolResult" in fixed)!=("nativeToolResult" in cached): return base
        result=fixed.get("nativeToolResult")
        if "nativeToolResult" in fixed and (not isinstance(result,dict) or not protocol.resource_equal(result,cached.get("nativeToolResult"))): return base
        exact=isinstance(result,dict) and protocol.resource_equal(result,f)
        if fixed["status"]=="failed" and exact and f.get("status")=="failed" and f.get("replacementAttempted") is False and f.get("verified") is False and "nativeReplacement" not in f:
            conflict=f.get("message") in {"Original-byte CAS mismatch; no replacement dispatched","File changed before replacement; no replacement dispatched"}
            return {**ev,"phase":"version_conflict" if conflict else "not_dispatched"}
        if f.get("status")=="unknown" and f.get("verified") is not True: return ev
        if not verified_replacement(f,path,want,sha,size,before): return base
        native=f["nativeReplacement"]; objects=native["objects"]
        # For this new projection compare the actual typed identities; Python bool/int equality is not a Windows file identity.
        if (not protocol.resource_equal({"file":objects["target"]["fileIdentity"],"parent":f["parentIdentity"]},
                                         {"file":native["temporaryIdentity"],"parent":native["parentIdentity"]})
                or before!="absent" and not protocol.resource_equal({"file":objects["backup"]["fileIdentity"]},{"file":native["beforeObject"]["fileIdentity"]})): return base
        if fixed["status"]=="completed" and exact: return {**ev,"phase":"verified"}
        if fixed["status"] in {"running","unknown","interrupted"}: return {**ev,"phase":"write_verified_only"}
        return base
    except (AttributeError,ValueError,TypeError,KeyError,UnicodeError,RecursionError,sqlite3.Error,PlatformError,protocol.ProtocolError):
        return base


def show(view, receipt, current):
    from desktop_phone_receipts import _stamp
    phase=receipt["phase"]
    title={"receipt_only":"只核实到原写入接单","write_unconfirmed":"原文本写入结果仍未核实",
           "version_conflict":"替换前原文件版本已变","not_dispatched":"原执行器确认没有派发替换",
           "verified":"当时写入的文本版本已核实","write_verified_only":"文件回读已核实，设备作业收尾未知"}[phase]
    body=f"Windows 本机工作区：{receipt['environmentId']} · {_stamp(receipt['updatedAt'])}"
    if "path" in receipt:
        body+="\n原请求的相对文件："+receipt["path"]
        body+="\n原条件："+("目标不存在" if receipt["expectedVersion"]=="absent" else "文件版本 …"+receipt["expectedVersion"][-16:])
    if phase in {"verified","write_verified_only"}: body+=f"\n原文件事务已回读当时的 {receipt['bytes']} 字节，版本 …{receipt['desiredVersion'][-16:]}。不代表今天文件仍相同。"
    elif phase in {"version_conflict","not_dispatched"}: body+="\n原文件事务确认没有派发这笔目标替换；没有查询文件目前的内容。"
    else: body+="\n缺少足够的原写入或回读证据。不能据此判断文件已改、未改或版本冲突。"
    body+="\n只查已有回执，不展示正文、不重写文件，也不说明整个仓库任务已完成。"
    while current():
        choice=view._dialog(title,body,[("ids","核对原操作与版本编号")],current)
        if choice!="ids": return
        more=f"原操作：{receipt['operationId']}\nWindows 工作：{receipt['jobId']}"
        if "path" in receipt: more+=f"\n原条件：{receipt['expectedVersion']}\n请求写入的版本：{receipt['desiredVersion']}"
        view._dialog("这一次文本写入",more,[],current)
