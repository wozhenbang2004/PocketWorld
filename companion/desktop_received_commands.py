"""Local read-only entry into commands this Windows supervisor itself accepted."""
import time

import device_protocol as protocol
from device_pairing import validate_grant
from windows_command_receipt import project as command_receipt
from windows_received_job import COMMAND, FILE, admission
from windows_received_file import PROFILE as FILE_PROFILE, project as workspace_file
from windows_workspace_read import PROFILE as READ_PROFILE, project as workspace_read

BATCH = """SELECT rid,operation_id,fingerprint,updated_at,CASE WHEN
    ((json_extract(doc,'$.protocol')='native-device-command-v1' AND json_extract(doc,'$.audience.toolName')='command.run')
     OR (json_extract(doc,'$.protocol')='native-device-tool-v1' AND json_extract(doc,'$.audience.toolName') IN ('workspace.list','workspace.read','workspace.receive')))
    AND json_extract(doc,'$.audience.callerDeviceId')=?
    AND json_extract(doc,'$.audience.originDeviceId')=json_extract(doc,'$.audience.callerDeviceId')
    THEN json_extract(doc,'$.audience.toolName') END FROM
    (SELECT rowid rid,operation_id,fingerprint,last_status,updated_at,CASE WHEN json_valid(details) THEN details END AS doc
     FROM operations WHERE rowid<? ORDER BY rowid DESC LIMIT 64) ORDER BY rid DESC"""

TOOLS = {COMMAND: "白名单命令", FILE: "接收所选文件", "workspace.list": "查看目录", "workspace.read": "读取文本"}
LABEL = {"queued": "已在这台电脑排队", "running": "这台电脑已启动命令", "exited_zero": "原命令以 0 退出",
         "exited_nonzero": "原命令以非零码退出", "stopped": "原命令已停止，之前的影响未撤回",
         "not_started": "原执行器确认未启动进程", "unknown": "原命令的执行结果仍待核对",
         "accepted_only": "已接单，原执行文件未能核对"}


def local_record(view, peer, item, *, read=False, revision=None):
    # This is the unlocked receiver owner's local view, never a synthetic MTLS
    # history permission. Admit only the direct source and its still-current original read scope.
    if not view._peer_current(peer): return None
    receiver, manager, operation = view.receiver, view.manager, item[1]
    with manager.lock:
        row = manager.ledger.execute("SELECT fingerprint,job_id,last_status,details FROM operations WHERE rowid=? AND operation_id=?",
                                     (item[0], operation)).fetchone()
        if not row or row[0] != item[2]: return None
        original = admission(manager, operation, row)
        if not original: return None
        source, accepted = original
        now = manager.now()
        if (accepted["callerDeviceId"] != peer[0] or accepted["authorizationEpoch"] != peer[3]
                or not accepted["acceptedAt"] <= now < accepted["retainedUntil"] or not receiver.history.source_visible(accepted, now)):
            return None
        grant = receiver.trust.grant(accepted["grantId"])
        if not grant or grant["revoked"] or grant["authorizationEpoch"] != peer[3]: return None
        try: validate_grant(grant, receiver.identity.device_id, peer[0], now, for_history=True)
        except (ValueError, PermissionError, protocol.ProtocolError): return None
        retention = grant.get("historyRead", {})
        if not (peer[0] in grant["allowedOrigins"] and any(protocol.resource_equal(source["resourceBinding"], r) for r in grant["resources"])
                and ((now < grant["expiresAt"] and "operation.get" in grant["capabilities"])
                     or (now < retention.get("expiresAt", 0) and "operation.get" in retention.get("methods", [])))):
            return None
        if revision is not None and manager.jobs.get(row[1], {}).get("nativeDeviceRevision") != revision:
            return None
        if not read: return True
        return (command_receipt if source["toolName"] == COMMAND else workspace_file if source["toolName"] == FILE else workspace_read)(manager, operation, row, original)


def show(view, peer, item):
    from desktop_phone_receipts import _parts, _stamp
    receipt = local_record(view, peer, item, read=True)
    if not receipt: return
    phase = receipt["phase"]
    until = time.monotonic() + 60
    def current(): return time.monotonic() < until and bool(local_record(view, peer, item, revision=receipt.get("revision")))
    if receipt["profile"] == FILE_PROFILE:
        titles={"receipt_only":"接过这次文件投递，落盘未核实","receiving":"原传输正在等候或接收字节",
                "target_unconfirmed":"原文件写入结果未知，请勿补发","not_dispatched":"Windows 确认未派发目标替换",
                "write_verified_only":"有原文件落盘回执，传输收尾未确认","delivered":"已核实当时写入的文件"}
        text="这是从原手机选来的一份文件，不是整仓导入，也不代表整个任务已完成。"
        if "path" in receipt:
            text+=f"\n当时目标：{receipt['path']}\n{receipt['bytes']} 字节，SHA-256 {receipt['sha256']}。"
            if phase=="receiving": text+=f"\n只核实接收进度 {receipt['verifiedOffset']}/{receipt['bytes']}，不是目标已写。"
            elif phase=="target_unconfirmed": text+="\n有原写入意图，没有最终字节证明。不能据此断言目标没写。"
            elif phase=="not_dispatched": text+=("\nWindows 在替换前发现目标不再符合原条件；没有覆盖或读取其新内容给手机。" if receipt["targetConditionFailed"] else "\n原文件事务在目标替换前停止；接收字节不等于落盘。")
            elif phase in ("delivered","write_verified_only"): text+="\n原生写入已回读同一文件身份与字节；不代表现在未被改动。"
        else: text+="\n未取得一致的原选材、转移或落盘证明；未按同名路径打开或补写。"
        text+=f"\n原操作：{item[1]}\nWindows 工作：{receipt['jobId']}"
        view._dialog(titles[phase],text,[],current)
        return
    if receipt["profile"] == READ_PROFILE:
        _show_read(view, peer, item, receipt, current)
        return
    body = f"配对设备 …{peer[0][-12:]} → 这台 Windows\n当时的本机工作区：{receipt['environmentId']} · {_stamp(receipt['updatedAt'])}"
    if "exitCode" in receipt: body += f"\n进程退出码：{receipt['exitCode']}"
    if receipt.get("cleanupVerified"): body += "\n原进程组清理已核实。"
    if phase == "accepted_only": body += "\n保留接收编号，不用缓存的文字冒充原工作结果。"
    body += "\n以上只说明这条命令，不证明仓库改动、文件交付或整个目标完成。无需手机在线。"
    while current():
        choices = ([("output", "查看这次命令留存的输出")] if "output" in receipt else []) + [("ids", "核对原操作与本机工作编号")]
        action = view._dialog(LABEL[phase], body, choices, current)
        if action == "ids":
            view._dialog("可在原发起设备的操作回执中核对", f"原操作：{item[1]}\nWindows 工作：{receipt['jobId']}\n请求摘要：{item[2]}", [], current)
        elif action == "output":
            parts, page = _parts(receipt["output"] or "原命令没有留存可显示的字符。"), 0
            while current():
                choices = ([("prev", "上一段")] if page else []) + ([("next", "下一段")] if page+1 < len(parts) else [])
                choice = view._dialog("原 Windows 命令输出" + (f" · {page+1}/{len(parts)}" if len(parts)>1 else ""),
                                      parts[page] + ("\n\n只显示留存输出的一部分。" if receipt["outputPartial"] else ""), choices, current)
                if choice == "prev": page -= 1
                elif choice == "next": page += 1
                else: break
        else: return


def _show_read(view, peer, item, receipt, current):
    from desktop_phone_receipts import _parts, _stamp
    kind = receipt["phase"]
    title = {"text_snapshot": "上次从这台 Windows 读到的文本", "directory_snapshot": "上次从这台 Windows 读到的目录"}.get(kind, "只核对到原读取回执")
    body = f"{TOOLS[receipt['tool']]} · 本机工作区：{receipt['environmentId']} · {_stamp(receipt['updatedAt'])}"
    if kind == "receipt_only":
        body += "\n缺少同一原工作已保存并核对的读取结果；可能尚未读取完或原件已不在。不会打开目录或文件补读。"
    else:
        path = receipt["path"] or "（工作区根目录）"
        body += "\n原相对位置：" + (path if len(path) <= 120 else "…" + path[-119:])
        if kind == "text_snapshot":
            body += f"\n当时读取 {receipt['bytes']} 字节；文件版本 …{receipt['version'][-16:]}"
        else:
            body += f"\n原回执返回 {receipt['returned']} 项" + ("（原列表未完全返回）。" if receipt["sourceTruncated"] else "。")
        body += "\n这是当时的读取，不是当前目录或文件；没有重新扫描，也没有导入或修改项目。"
    while current():
        choices = ([] if kind == "receipt_only" else [("content", "查看原文本片段" if kind == "text_snapshot" else "查看原目录项")])
        choice = view._dialog(title, body, choices + [("ids","核对原操作和 Windows 工作编号")], current)
        if choice == "ids":
            view._dialog("这一次读取的原编号", f"原操作：{item[1]}\nWindows 工作：{receipt['jobId']}\n请求摘要：{item[2]}", [], current)
        elif choice == "content":
            if kind == "text_snapshot":
                text = (receipt["part"] or "当时的文件没有可显示的文本。") + ("\n仅显示原文本开头的一部分。" if receipt["partial"] else "")
            else:
                kinds = {"file":"文件","directory":"目录","reparse_point":"重解析点（未跟随）","unsupported":"其他"}
                text = "\n".join(kinds[e["kind"]] + "：" + e["name"] for e in receipt["entries"]) or "原回执没有返回目录项。"
                if receipt["returned"] > len(receipt["entries"]): text += "\n此处最多显示原回执的前 8 项。"
            parts, page = _parts(text), 0
            while current():
                buttons = ([("prev","上一段")] if page else []) + ([("next","下一段")] if page+1 < len(parts) else [])
                action = view._dialog(title + (f" · {page+1}/{len(parts)}" if len(parts)>1 else ""), parts[page], buttons, current)
                if action == "prev": page -= 1
                elif action == "next": page += 1
                else: break
        else: return
