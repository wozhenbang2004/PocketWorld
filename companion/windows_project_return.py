"""Original Windows return-package identity. Never regenerate a project or infer receipt on Android."""
import hashlib
import re
import sqlite3
import uuid

import device_protocol as protocol
from assignment_projects import manifest_identity
from device_companion_history import native_operation_id
from windows_received_job import PROJECT_EXPORT, PROJECT_RECEIVE

PROFILE = "windows-owner-returned-project-v1"
ANCHOR = "_nativeProjectReturnOrigin"
SHA = re.compile("[a-f0-9]{64}")
OP = re.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")


def bind(job, envelope, source, manifest, wire):
    # Optional LOCAL evidence only, at original successful register_stream. Do not change an export or add a save.
    try:
        args = envelope.arguments["arguments"]; op = args["receiveOperationId"]; result = source["nativeToolResult"]
        fingerprint = source["fingerprint"]; digest = result["manifestDigest"]
        if (envelope.arguments["toolName"] != PROJECT_EXPORT or envelope.arguments["effect"] != "read"
                or envelope.get("originDeviceId") != envelope.get("callerDeviceId") or envelope.get("delegationChain", [])
                or set(args) != {"receiveOperationId"} or source["operationId"] != op or not OP.fullmatch(op)
                or source["nativeDeviceOperation"]["toolName"] != PROJECT_RECEIVE or source["status"] != "completed"
                or result.get("verified") is not True or result.get("projectImported") is not True or result["sourceReceiveOperationId"] != op
                or str(uuid.UUID(source["id"])) != source["id"] or not SHA.fullmatch(fingerprint) or manifest_identity(manifest) != digest
                or job["operationId"] != native_operation_id(envelope) or job["fingerprint"] != protocol.arguments_digest(envelope.json())
                or wire["operationId"] != job["operationId"]):
            return
        job[ANCHOR] = {"receive": op, "sourceJob": source["id"], "sourceFingerprint": fingerprint, "sourceManifest": digest,
                       "wireDigest": job["fingerprint"], "archive": hashlib.sha256(protocol.canonical_bytes(wire)).hexdigest()}
    except (KeyError, TypeError, ValueError, AttributeError, RecursionError):
        return


def verified_anchor(fixed, live, wire, accepted, source):
    try:
        value = fixed.get(ANCHOR)
        if (not isinstance(value, dict) or value != live.get(ANCHOR)
                or set(value) != {"receive", "sourceJob", "sourceFingerprint", "sourceManifest", "wireDigest", "archive"}
                or not OP.fullmatch(value["receive"]) or value["receive"] == wire["operationId"] or str(uuid.UUID(value["sourceJob"])) != value["sourceJob"]
                or any(not SHA.fullmatch(value[k]) for k in ("sourceFingerprint", "sourceManifest", "wireDigest", "archive"))
                or value["wireDigest"] != accepted["argumentsDigest"] or value["archive"] != hashlib.sha256(protocol.canonical_bytes(wire)).hexdigest()):
            return None
        from windows_received_project import same_work
        return {**value, "family": same_work(source, accepted)}
    except (KeyError, TypeError, ValueError, AttributeError, RecursionError):
        return None


def open_source(view, peer, ref, current):
    from desktop_received_commands import local_record, show as show_work
    if not current(): return
    try:
        with view.manager.lock:
            item = view.manager.ledger.execute("""SELECT rowid,operation_id,fingerprint,updated_at,json_extract(details,'$.audience.toolName')
                FROM operations WHERE operation_id=? AND job_id=? AND fingerprint=? AND CASE WHEN json_valid(details) THEN
                json_extract(details,'$.audience.callerDeviceId')=? AND json_extract(details,'$.audience.originDeviceId')=? ELSE 0 END""",
                (ref["receive"], ref["sourceJob"], ref["sourceFingerprint"], peer[0], peer[0])).fetchone()
            original = local_record(view, peer, item, read=True) if item and item[4] == PROJECT_RECEIVE else None
        if (original and original.get("phase") == "published" and original.get("_sameWork") == ref["family"]
                and original.get("manifest") == ref["sourceManifest"] and current()):
            revision = original["revision"]
            def same_source():
                return current() and bool(local_record(view, peer, item, revision=revision))
            if same_source(): show_work(view, peer, item, owning_current=same_source)
            return
    except (sqlite3.Error, KeyError, TypeError, ValueError, PermissionError): pass
    if current():
        view._dialog("没有打开原接包回执", "源操作、清单或它自己的当前读取范围未能核对；不改查近似项目，也不重做归还。", [], current)


def show(view, peer, receipt, current):
    from desktop_phone_receipts import _stamp
    verified = receipt["phase"] != "operation_only"
    title = "Windows 当时冻结了这份返回项目包" if verified else "只核实到原项目取回操作"
    text = "这是手机请求从电脑上那个导入副本取回原选中文件集，不是向电脑交项目。"
    if verified:
        text += f"\n返回包：{receipt['bytes']} 字节 · {_stamp(receipt['publishedAt'])}。"
        text += ("本次尚见同一冻结缓存登记，不代表一定可下载。" if receipt["phase"] == "frozen_here" else
                 "只核实到当时已发布，当前没有匹配的冻结字节缓存；不会重新打包。")
        text += "\n这不能证明一定改过文件，也不能证明手机已收妥。收妥以原手机任务的完整本地登记为准。"
    else:
        text += "\n未同时核实当次制品、留存与读取范围，不能据此判断是否已返还给手机。"
    ref = receipt.get("_origin")
    if verified and not ref: text += "\n未留存可核对的原接包关联，不按项目名或时间猜。"
    while current():
        choices = [("id", "核对这笔返回操作与任务")]
        if verified: choices.insert(0, ("version", "查看返回包版本与传输编号"))
        if ref: choices.append(("source", "核对并打开它来自的原接包回执"))
        selected = view._dialog(title, text, choices, current)
        if not current(): return
        if selected == "source" and ref: open_source(view, peer, ref, current)
        elif selected == "version" and verified:
            view._dialog("同一份原冻结项目包", f"返回包 SHA-256：{receipt['sha256']}\n制品编号：{receipt['artifactId']}\n传输编号：{receipt['transferId']}。"
                         "\n这是归还时打包的版本，不是今天目录的散列。", [], current)
        elif selected == "id":
            view._dialog("原手机任务中的此次返回", f"任务：{receipt['taskId']}\n原轮次：{receipt['requestRunId']} · {receipt['requestExecutorEpoch']}"
                         f"\n返回操作：{receipt['operationId']}\nWindows 工作：{receipt['jobId']}", [], current)
        else: return
