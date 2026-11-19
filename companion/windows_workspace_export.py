"""Owner-side receipt of one direct Windows workspace.export. No byte read or device query.

A private completed job plus the original durable artifact audience records the
snapshot's creation. Neither chunk availability nor a source-side 'completed'
is the Android AttachmentBook publication receipt.
"""
import hashlib
import sqlite3

import device_protocol as protocol
from device_artifacts import ArtifactError,from_wire_descriptor,to_wire_descriptor
from platform_backend import PlatformError
from windows_received_job import EXPORT, PROJECT_EXPORT, retained
import windows_project_return as project_return
from windows_workspace_read import _plain

PROFILE="windows-owner-workspace-export-v1"
SCOPE=("originDeviceId","callerDeviceId","targetDeviceId","instanceId","rootTaskId","localTaskId","requestRunId",
       "requestExecutorEpoch","grantId","authorizationEpoch","resourceBinding")
AUDIENCE={"kind","objectId","acceptedAt","retainedUntil",*SCOPE}


def _describe(grant,now):
    history=grant.get("historyRead",{})
    return (now<grant["expiresAt"] and "artifact.describe" in grant["capabilities"]
            or now<history.get("expiresAt",0) and "artifact.describe" in history.get("methods",[]))


def project(receiver,manager,operation,row,original,grant,now):
    # Under the existing manager lock, after the existing direct operation,
    # peer/fingerprint, unlock, resource, grant, epoch and history-visible checks.
    source,accepted=original
    bundle = source["toolName"] == PROJECT_EXPORT
    value={"profile":project_return.PROFILE if bundle else PROFILE,"phase":"operation_only","operationId":operation,"jobId":row[1],"argumentsDigest":row[0],
           "taskId":accepted["localTaskId"],"requestRunId":accepted["requestRunId"],"requestExecutorEpoch":accepted["requestExecutorEpoch"]}
    if source["toolName"] not in (EXPORT, PROJECT_EXPORT) or not _describe(grant,now): return value
    try:
        if (set(accepted)!=AUDIENCE|{"argumentsDigest"} or accepted["kind"]!="operation" or accepted["objectId"]!=operation
                or accepted["callerDeviceId"]!=accepted["originDeviceId"]): return value
        fixed=retained(manager,operation,row,original)
        if not fixed or fixed["status"]!="completed" or fixed["worker"]!="device_tool": return value
        wire=fixed["nativeToolResult"]
        if not protocol.resource_equal(manager.jobs[row[1]].get("nativeToolResult"),wire): return value
        descriptor=from_wire_descriptor(wire)
        if (descriptor["kind"] != ("project_bundle" if bundle else "file") or descriptor["operationId"]!=operation or descriptor["taskId"]!=accepted["localTaskId"]
                or descriptor.get("requestRunId")!=accepted["requestRunId"] or descriptor.get("requestExecutorEpoch")!=accepted["requestExecutorEpoch"]
                or "runId" in descriptor or "executorEpoch" in descriptor or descriptor["sourceDeviceId"]!=accepted["targetDeviceId"]
                or wire["instanceId"]!=accepted["instanceId"] or descriptor["versionId"]!=descriptor["sha256"]
                or descriptor["mimeType"] != ("application/zip" if bundle else "application/octet-stream")
                or bundle and (descriptor["name"] != "modified-project.pocketworld.zip" or not 22 <= descriptor["bytes"] <= 20971520)): return value
        # Do not infer 'published' from a path, name/hash match, legacy cache, or
        # client-supplied descriptor. This independent row is inserted by the
        # original publisher only AFTER register_stream verified all bytes.
        record=manager.ledger.execute("""SELECT descriptor,audience FROM device_artifact_audiences
            WHERE artifact_id=? AND withdrawn=0 AND length(CAST(descriptor AS BLOB))<=16384
            AND length(CAST(audience AS BLOB))<=16384""",(descriptor["artifactId"],)).fetchone()
        if not record: return value
        registered=protocol.parse_json_object(record[0].encode("utf-8"))
        audience=protocol.validate_accepted_audience(protocol.parse_json_object(record[1].encode("utf-8")))
        if (not protocol.resource_equal(descriptor,registered)
                or not protocol.resource_equal(to_wire_descriptor(registered,instance_id=accepted["instanceId"],revision=1),wire)
                or set(audience)!=AUDIENCE or audience["kind"]!="artifact" or audience["objectId"]!=registered["artifactId"]
                or any(audience[k]!=accepted[k] for k in SCOPE if k!="resourceBinding")
                or not protocol.resource_equal(audience["resourceBinding"],accepted["resourceBinding"])
                or type(fixed.get("updatedAt")) is not int or not accepted["acceptedAt"]<=audience["acceptedAt"]<=fixed["updatedAt"]<=now
                or not audience["acceptedAt"]<=now<audience["retainedUntil"] or not receiver.history.source_visible(audience,now)): return value
        # Memory-store metadata only; no describe/readChunk, file open, lease
        # extension, route creation, or transport side effects. It can disappear
        # at any time, and says nothing about the phone's local bytes.
        try: present=protocol.resource_equal(receiver.artifacts.by_artifact_id(descriptor["artifactId"]),descriptor)
        except ArtifactError: present=False
        phase="frozen_here" if present else "published_history"
        fields = {}
        guard_fields = [row[0], row[1], fixed["nativeDeviceRevision"], wire, audience, phase]
        if bundle:
            ref = project_return.verified_anchor(fixed, manager.jobs[row[1]], wire, accepted, source)
            if ref: fields["_origin"] = ref
            guard_fields.append(ref)
        guard=hashlib.sha256(protocol.canonical_bytes(guard_fields)).hexdigest()
        return {**value,**fields,"phase":phase,"_guard":guard,"revision":fixed["nativeDeviceRevision"],"publishedAt":audience["acceptedAt"],
                "name":_plain(descriptor["name"]),"bytes":descriptor["bytes"],"sha256":descriptor["sha256"],
                "artifactId":descriptor["artifactId"],"transferId":descriptor["transferId"]}
    except (AttributeError,KeyError,IndexError,TypeError,ValueError,OSError,UnicodeError,RecursionError,
            ArtifactError,PlatformError,protocol.ProtocolError,sqlite3.Error):
        return value


def show(view,receipt,current):
    from desktop_phone_receipts import _stamp
    phase=receipt["phase"]
    body="原手机要求从这台 Windows 的工作区取文件，不是向电脑上传，也不是项目改动回传。"
    if phase=="operation_only":
        body+="\n只核实到原操作。可能是导出未完成、原件/授权资料到期或被撤回，不能据此显示文件或判断手机是否收妥。"
    else:
        body+=f"\n当时的文件：{receipt['name']} · {receipt['bytes']} 字节 · {_stamp(receipt['publishedAt'])}。"
        body+=("\n本次仍见该版本的原冻结缓存登记。不代表现在可下载。" if phase=="frozen_here" else
               "\n只能核实当时发布过这个版本；本次未找到匹配的原字节缓存，不再读取工作区或重新导出。")
        body+="\n是否收妥，以原手机任务中已验完整字节、已登记的附件为准。此处不联网，也不查询手机。"
    while current():
        choices=[("job","核对原手机任务、轮次与这次操作")]
        if phase!="operation_only": choices.insert(0,("version","核对导出版本和传输编号"))
        pick=view._dialog("只核实到导出请求" if phase=="operation_only" else "Windows 当时已冻结这份文件",body,choices,current)
        if pick=="job":
            view._dialog("两端这一次取文件的原编号",f"原手机任务：{receipt['taskId']}\n手机原轮次：{receipt['requestRunId']}（第 {receipt['requestExecutorEpoch']} 代）"
                         f"\n原操作：{receipt['operationId']}\nWindows 工作：{receipt['jobId']}\n请求摘要：{receipt['argumentsDigest']}",[],current)
        elif pick=="version" and phase!="operation_only":
            view._dialog("已发布原文件的身份",f"{receipt['name']}\n{receipt['bytes']} 字节 · SHA-256：{receipt['sha256']}"
                         f"\n文件编号：{receipt['artifactId']}\n传输编号：{receipt['transferId']}\n这不是手机已保存的证明。",[],current)
        else: return
