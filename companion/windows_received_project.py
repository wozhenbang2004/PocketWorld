"""Owner-only receipts for a phone's original Windows project import. No ZIP/content, current directory or remote reads."""
import hashlib
import json
import re
import sqlite3

import device_protocol as protocol
from assignment_projects import manifest_identity
from device_artifacts import ArtifactError, from_wire_descriptor
from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_received_file import _id
from windows_received_job import PROJECT_CONFIRM, PROJECT_RECEIVE, retained
from windows_workspace_read import _plain

PROFILE = "windows-original-project-import-v1"
SHA = re.compile("[a-f0-9]{64}")
OP = re.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")
READ_ERRORS = (KeyError, ValueError, TypeError, AttributeError, RecursionError, OSError, ArtifactError, PlatformError)
LOOKUP_ERRORS = (sqlite3.Error, PermissionError) + READ_ERRORS


def _digest(args):
    return hashlib.sha256(protocol.canonical_bytes(args)).hexdigest()


def accepted(envelope):
    # Record only digests and (for confirm) the referenced operation. This witness is not admission or a publication.
    try:
        tool, args = envelope.arguments["toolName"], envelope.arguments["arguments"]
        if (tool not in (PROJECT_RECEIVE, PROJECT_CONFIRM) or envelope.arguments["effect"] != "write"
                or envelope.get("originDeviceId") != envelope.get("callerDeviceId") or envelope.get("parentTaskId") != envelope.get("rootTaskId")
                or envelope.get("delegationChain", [])
                or args["expectedVersion"] != "absent"):
            return {}
        relative_parts(args["path"])
        witness = {"format": PROFILE, "inputDigest": _digest(args), "wireDigest": protocol.arguments_digest(envelope.json())}
        if tool == PROJECT_CONFIRM:
            if (set(args) != {"path", "expectedVersion", "receiveOperationId", "manifestDigest"}
                    or not OP.fullmatch(args["receiveOperationId"]) or not SHA.fullmatch(args["manifestDigest"])):
                return {}
            witness.update(receiveOperationId=args["receiveOperationId"], manifestDigest=args["manifestDigest"])
        elif set(args) != {"path", "expectedVersion", "artifact"}:
            return {}
        return {"windowsProjectImport": witness}
    except READ_ERRORS:
        return {}  # never change the original writer's acceptance/error path


def _intent(value, path, manifest, *, published=False):
    if (not isinstance(value, dict) or value.get("target") != path or value.get("expectedVersion") != "absent"
            or value.get("manifestDigest") != manifest or not _id(value.get("parentIdentity"))
            or value.get("recoveryPolicy") != "inspect_original_objects_never_automatic_replay"):
        return False
    stage, target = relative_parts(value["temporary"]), relative_parts(path)
    return (stage[:-1] == target[:-1] and bool(re.fullmatch(r"\.pocketworld-project-[a-f0-9]{32}", stage[-1]))
            and (not published or value.get("phase") == "readback_verified" and _id(value.get("temporaryIdentity"))))


def _published(result, path, manifest, receive):
    return (isinstance(manifest, str) and SHA.fullmatch(manifest) and result.get("sourceReceiveOperationId") == receive
            and result.get("path") == path and result.get("manifestDigest") == manifest and result.get("status") == "completed"
            and all(result.get(k) is True for k in ("verified", "delivered", "projectImported", "publicationAttempted"))
            and all(result.get(k) is False for k in ("executionStarted", "restoresHistory", "restoresEnvironment", "restoresExecutionAuthority"))
            and _intent(result.get("nativeProjectImport"), path, manifest, published=True)
            and _id(result.get("directoryIdentity")) and result["directoryIdentity"] == result["nativeProjectImport"]["temporaryIdentity"])


def _receive(base, peer, fixed, result, witness, detail, source, op):
    args = fixed["nativeInput"]; asset = from_wire_descriptor(args["artifact"]); path = args["path"]
    relative_parts(path)
    if (set(args) != {"path", "expectedVersion", "artifact"} or args["expectedVersion"] != "absent"
            or witness["inputDigest"] != _digest(args) or asset["kind"] != "project_bundle" or asset["mimeType"] != "application/zip"
            or asset["operationId"] != op or asset["sourceDeviceId"] != peer[0] or args["artifact"]["instanceId"] != peer[2]
            or asset["taskId"] != source["localTaskId"] or asset.get("requestRunId") != source["requestRunId"]
            or asset.get("requestExecutorEpoch") != source["requestExecutorEpoch"] or "runId" in asset or "executorEpoch" in asset
            or asset["versionId"] != asset["sha256"] or asset["transferId"] != detail["inputTransferId"]
            or type(asset["bytes"]) is not int or not 22 <= asset["bytes"] <= 20971520):
        return base
    value = {**base, "packageBytes": asset["bytes"], "packageSha256": asset["sha256"]}
    if _plain(path) == path: value["target"] = path
    same = result.get("transferId") == asset["transferId"] and result.get("sha256") == asset["sha256"] and result.get("bytes") == asset["bytes"]
    offset, phase = result.get("verifiedOffset"), result.get("phase")
    if (fixed["status"] == "running" and result.get("status") == "running" and result.get("delivered") is False
            and not fixed.get("nativeInputDispatchStarted") and same and type(offset) is int and 0 <= offset <= asset["bytes"]):
        if phase == "awaiting_bytes" and (offset == asset["bytes"] or offset % 262144 == 0):
            return {**value, "phase": "receiving", "offset": offset}
        overview = result.get("summary") or {}
        if (phase == "awaiting_import_review" and offset == asset["bytes"] and result.get("expectedVersion") == "absent"
                and result.get("path") == path and isinstance(result.get("manifestDigest"), str) and SHA.fullmatch(result["manifestDigest"])
                and overview.get("manifestDigest") == result["manifestDigest"] and overview.get("newCopyOnly") is True
                and all(overview.get(k) is False for k in ("restoresHistory", "restoresEnvironment", "restoresExecutionAuthority"))
                and type(overview.get("fileCount")) is int and 1 <= overview["fileCount"] <= 2000):
            return {**value, "phase": "staged", "manifest": result["manifestDigest"], "files": overview["fileCount"]}
    if (fixed["status"] == result.get("status") == "failed" and phase == "input_rejected" and result.get("delivered") is False
            and not fixed.get("nativeInputDispatchStarted") and result.get("transferId") == asset["transferId"]
            and (result.get("replacementAttempted") is False or result.get("publicationAttempted") is False)):
        return {**value, "phase": "not_published"}
    if fixed.get("nativeInputDispatchStarted") is True:
        manifest = fixed.get("_nativeProjectManifest")
        if (fixed["status"] == "completed" and isinstance(manifest, dict)
                and _published(result, path, result.get("manifestDigest"), op)
                and manifest_identity(manifest) == result["manifestDigest"] and isinstance(manifest.get("files"), list)
                and 1 <= len(manifest["files"]) <= 2000):
            return {**value, "phase": "published", "manifest": result["manifestDigest"], "files": len(manifest["files"])}
        pending = {**value, "phase": "publication_unconfirmed"}
        # This may be the original staged summary or a native recovery record; it binds a link, not a completed publication.
        digest = result.get("manifestDigest")
        if result.get("path") == path and isinstance(digest, str) and SHA.fullmatch(digest):
            pending["_linkManifest"] = digest
        return pending
    return value


def _confirm(base, fixed, result, witness, op):
    source_op, manifest = witness["receiveOperationId"], witness["manifestDigest"]
    if not OP.fullmatch(source_op) or op == source_op or not SHA.fullmatch(manifest): return base
    saved = fixed.get("nativeProjectImportIntent") or {}
    path = result.get("path") or saved.get("target")
    if path is None: return base
    relative_parts(path)
    if _digest({"receiveOperationId": source_op, "manifestDigest": manifest, "path": path, "expectedVersion": "absent"}) != witness["inputDigest"]:
        return base
    value = {**base, "receive": source_op, "manifest": manifest}
    if _plain(path) == path: value["target"] = path
    if (fixed["status"] == "completed" and _published(result, path, manifest, source_op)
            and saved.get("phase") == "dispatch_intent_persisted" and _intent(saved, path, manifest)
            and all(saved[k] == result["nativeProjectImport"][k] for k in ("temporary", "parentIdentity", "temporaryIdentity"))):
        return {**value, "phase": "published"}
    if (fixed["status"] == result.get("status") == "failed" and result.get("verified") is False and result.get("delivered") is False
            and result.get("publicationAttempted") is False and result.get("sourceReceiveOperationId") == source_op
            and _intent(result.get("nativeProjectImport"), path, manifest)):
        return {**value, "phase": "not_published"}
    if _intent(saved, path, manifest) and saved.get("phase") == "dispatch_intent_persisted":
        return {**value, "phase": "publication_unconfirmed"}
    return value


def project(manager, peer, op, row, original):
    source, audience = original
    base = {"profile": PROFILE, "phase": "receipt_only", "operationId": op, "jobId": row[1], "tool": source["toolName"],
            "workspace": audience["resourceBinding"]["environmentId"], "phoneTask": audience["localTaskId"],
            "phoneRun": audience["requestRunId"], "phoneEpoch": audience["requestExecutorEpoch"]}
    try:
        detail = json.loads(row[3]); witness = detail["windowsProjectImport"]
        if witness["format"] != PROFILE or witness["wireDigest"] != row[0] or not SHA.fullmatch(witness["inputDigest"]): return base
        fixed = retained(manager, op, row, original)
        if not fixed: return base
        job = manager.jobs[row[1]]
        if any(fixed.get(k) != job.get(k) for k in ("nativeInput", "nativeToolResult", "nativeProjectImportIntent", "nativeInputDispatchStarted")):
            return base
        base["revision"] = fixed["nativeDeviceRevision"]
        result = fixed.get("nativeToolResult") or {}
        # Confirmation may be a DIFFERENT run/epoch, but must be the very same direct task, grant and resource.
        base["_sameWork"] = _digest([source["parentTaskId"], {k: audience[k] for k in (
            "originDeviceId", "callerDeviceId", "targetDeviceId", "instanceId", "rootTaskId", "localTaskId",
            "grantId", "authorizationEpoch", "resourceBinding")}])
        if source["toolName"] == PROJECT_RECEIVE:
            receipt = _receive(base, peer, fixed, result, witness, detail, audience, op)
            link = fixed.get("_nativeProjectConfirmationOperation")
            if (fixed.get("nativeInputDispatchStarted") is True and isinstance(link, str) and OP.fullmatch(link)
                    and link != op and job.get("_nativeProjectConfirmationOperation") == link
                    and (receipt.get("manifest") or receipt.get("_linkManifest"))):
                receipt["_confirm"] = link
            return receipt
        if source["toolName"] == PROJECT_CONFIRM: return _confirm(base, fixed, result, witness, op)
    except READ_ERRORS:
        pass
    return base


TITLES = {"receipt_only": "已接这次操作，项目发布结果未核实", "receiving": "原项目包处于接收阶段",
          "staged": "项目包当时已核，等待原手机确认导入", "not_published": "原记录确认未尝试发布目录",
          "publication_unconfirmed": "原项目进入发布准备，没有最终交付证明", "published": "Windows 当时已发布并回读新的项目目录"}


def show(view, receipt, current):
    phase = receipt["phase"]
    message = "本机原接包回执。" if receipt["tool"] == PROJECT_RECEIVE else "本机原导入确认回执。这是另一笔操作，不是重复接包。"
    if phase == "receiving": message += f" 当时已收到 {receipt['offset']}/{receipt['packageBytes']} 字节；不是已经解包。"
    elif phase == "staged": message += f" 当时核对了包摘要与 {receipt['files']} 个文件的清单，不等于现在暂存仍可用或目录已发布。"
    elif phase == "published": message += " 当时以必须不存在的新目标发布，并从同一原目录身份回读文件。未检查现在的目录或后续修改。"
    elif phase == "not_published": message += " 未把该包发布为工作目录；不据此判断暂存是否清理。"
    elif phase == "publication_unconfirmed": message += " 没有证明目标未出现，也没有重做发布。"
    else: message += " 没有足够的原请求、原清单或原生发布证据；不从今天的同名目录猜测。"
    message += "\n这里只看原回执。项目副本不是 Git 历史、运行环境、执行权或已运行的项目。"
    if "target" in receipt: message += "\n当时的相对目标：" + receipt["target"]
    links = [("ids", "核对原任务、操作与版本")]
    if receipt.get("_confirm"): links.insert(0, ("link", "核对并打开这次手机确认的原回执"))
    elif receipt.get("receive"): links.insert(0, ("link", "核对并打开此确认指向的原接包回执"))
    choice = view._dialog(TITLES[phase], message, links, current)
    if not current(): return
    if choice == "link": return "link"
    if choice != "ids": return
    evidence = [f"原手机任务：{receipt['phoneTask']}", f"请求轮次：{receipt['phoneRun']} · {receipt['phoneEpoch']}",
                f"原操作：{receipt['operationId']}", f"Windows 工作：{receipt['jobId']}", f"工作区：{receipt['workspace']}"]
    for key, label in (("receive", "原接收操作"), ("packageSha256", "原包 SHA-256"), ("manifest", "原清单摘要")):
        if key in receipt: evidence.append(label + "：" + receipt[key])
    return view._dialog("同一笔原项目工作", "\n".join(evidence), [("back", "返回这份回执")], current)


def _linked(view, peer, anchor, current):
    """The exact foreign op is only a lookup key, never read authority. Re-admit the other operation separately."""
    from desktop_received_commands import local_record
    if not current(): return None
    target = anchor.get("_confirm") if anchor["tool"] == PROJECT_RECEIVE else anchor.get("receive")
    if not isinstance(target, str) or not OP.fullmatch(target): return None
    expected = PROJECT_CONFIRM if anchor["tool"] == PROJECT_RECEIVE else PROJECT_RECEIVE
    with view.manager.lock:
        item = view.manager.ledger.execute("""SELECT rowid,operation_id,fingerprint,updated_at,
            json_extract(details,'$.audience.toolName') FROM operations WHERE operation_id=?
            AND CASE WHEN json_valid(details) THEN json_extract(details,'$.audience.callerDeviceId')=?
            AND json_extract(details,'$.audience.originDeviceId')=? ELSE 0 END""", (target, peer[0], peer[0])).fetchone()
        if not item or item[4] != expected: return None
        other = local_record(view, peer, item, read=True)
    if not other or not current(): return None
    receive, confirm = (anchor, other) if anchor["tool"] == PROJECT_RECEIVE else (other, anchor)
    if (not receive.get("_sameWork") or receive["_sameWork"] != confirm.get("_sameWork")
            or receive.get("_confirm") != confirm["operationId"] or confirm.get("receive") != receive["operationId"]
            or not confirm.get("manifest") or confirm["manifest"] != (receive.get("manifest") or receive.get("_linkManifest"))):
        return None
    return item, other


def walk(view, peer, item, receipt, owner_current):
    from desktop_received_commands import local_record
    # One bounded live window; repeated switches do not recurse, acquire a lease or extend the initial read.
    while owner_current():
        def current():
            return owner_current() and bool(local_record(view, peer, item, revision=receipt.get("revision")))
        action = show(view, receipt, current)
        if action == "back" and current(): continue
        if action != "link" or not current(): return
        try: linked = _linked(view, peer, receipt, current)
        except LOOKUP_ERRORS: linked = None
        if linked is None or not current():
            if current():
                view._dialog("未能打开另一笔原回执", "两端操作号、原工作区、同一清单或各自的读取范围未能同时核实；不按相似时间或文件名找另一份。", [], current)
            return
        item, receipt = linked
