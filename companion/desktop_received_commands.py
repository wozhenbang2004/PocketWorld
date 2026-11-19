"""Local read-only entry into commands this Windows supervisor itself accepted."""
import re
import time

import device_protocol as protocol
from device_pairing import validate_grant
from windows_command_receipt import project as command_receipt
from windows_received_job import COMMAND, EXPORT, FILE, TEXT_WRITE, PROJECT_RECEIVE, PROJECT_CONFIRM, PROJECT_EXPORT, admission
from windows_workspace_export import PROFILE as EXPORT_PROFILE, project as workspace_export, show as show_export
from windows_received_file import PROFILE as FILE_PROFILE, project as workspace_file
from windows_workspace_read import PROFILE as READ_PROFILE, project as workspace_read
import windows_read_work as read_work
import windows_project_return as project_return

BATCH = """SELECT rid,operation_id,fingerprint,updated_at,CASE WHEN
    ((json_extract(doc,'$.protocol')='native-device-command-v1' AND json_extract(doc,'$.audience.toolName')='command.run')
     OR (json_extract(doc,'$.protocol')='native-device-tool-v1' AND json_extract(doc,'$.audience.toolName') IN ('workspace.list','workspace.read','workspace.receive','workspace.export','workspace.write','workspace.project.receive','workspace.project.confirm','workspace.project.export')))
    AND json_extract(doc,'$.audience.callerDeviceId')=?
    AND json_extract(doc,'$.audience.originDeviceId')=json_extract(doc,'$.audience.callerDeviceId')
    THEN json_extract(doc,'$.audience.toolName') END FROM
    (SELECT rowid rid,operation_id,fingerprint,last_status,updated_at,CASE WHEN json_valid(details) THEN details END AS doc
     FROM operations WHERE rowid<? ORDER BY rowid DESC LIMIT 64) ORDER BY rid DESC"""

TOOLS = {PROJECT_EXPORT: "手机请求取回项目副本", PROJECT_RECEIVE: "接收手机选定的项目包", PROJECT_CONFIRM: "确认导入新的项目副本", TEXT_WRITE: "写入文本文件", COMMAND: "白名单命令", FILE: "接收所选文件", EXPORT: "手机请求取走文件", "workspace.list": "查看目录", "workspace.read": "读取文本"}
LABEL = {"queued": "已在这台电脑排队", "running": "这台电脑已启动命令", "exited_zero": "原命令以 0 退出",
         "exited_nonzero": "原命令以非零码退出", "stopped": "原命令已停止，之前的影响未撤回",
         "not_started": "原执行器确认未启动进程", "unknown": "原命令的执行结果仍待核对",
         "accepted_only": "已接单，原执行文件未能核对"}


def local_record(view, peer, item, *, read=False, revision=None, archive=False, locate=False, ticket=None, held=None, export_guard=None, work_key=False, required_work=None, read_root=False, project_inventory=None, match_source=False, command_name=False):
    # This is the unlocked receiver owner's local view, never a synthetic MTLS
    # history permission. Admit only the direct source and its still-current original read scope.
    if not view._peer_current(peer): return None
    receiver, manager, operation = view.receiver, view.manager, item[1]
    with manager.lock:
        row = manager.ledger.execute("SELECT fingerprint,job_id,last_status,details FROM operations WHERE rowid=? AND operation_id=?",
                                     (item[0], operation)).fetchone()
        if not row or row[0] != item[2]: return None
        original = admission(manager, operation, row, owner_export=True, owner_text_write=True, owner_project=True)
        if not original: return None
        source, accepted = original
        if (source["toolName"] in (PROJECT_RECEIVE, PROJECT_CONFIRM, PROJECT_EXPORT) or item[4] in (PROJECT_RECEIVE, PROJECT_CONFIRM, PROJECT_EXPORT)) and item[4] != source["toolName"]: return None
        work = read_work.from_original(original) if work_key or required_work is not None else None
        if (work_key and work is None) or (required_work is not None and work != required_work): return None
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
        if source["toolName"] in (TEXT_WRITE, PROJECT_RECEIVE, PROJECT_CONFIRM, PROJECT_EXPORT) and (source["toolName"] not in grant["toolNames"] or "sourceShareId" in accepted): return None
        if revision is not None and manager.jobs.get(row[1], {}).get("nativeDeviceRevision") != revision:
            return None
        if command_name:
            if item[4]!=COMMAND or source["toolName"]!=COMMAND: return None
            from windows_command_name import local
            return local(manager,operation,row,original,grant,revision)
        if project_inventory is not None:
            from windows_project_inventory import access
            return access(manager, peer, operation, row, original, grant, now, revision, project_inventory, ticket)
        if read_root or match_source:
            from windows_read_root import local
            return local(manager, operation, row, original, grant, now, revision, display=read,match_source=match_source)
        if work_key: return work
        if export_guard is not None or read and source["toolName"] in (EXPORT, PROJECT_EXPORT):
            if source["toolName"] not in (EXPORT, PROJECT_EXPORT) or locate or archive: return None
            summary=workspace_export(receiver,manager,operation,row,original,grant,now)
            return summary.get("_guard")==export_guard if export_guard is not None else summary
        if locate:
            from windows_received_location import access
            return access(manager,operation,row,original,grant,now,revision,ticket,read)
        if archive:
            from windows_received_file_view import access
            return access(manager, operation, row, original, grant, now, revision, ticket, held)
        if not read: return True
        if source["toolName"] in (PROJECT_RECEIVE, PROJECT_CONFIRM):
            from windows_received_project import project
            return project(manager, peer, operation, row, original)
        if source["toolName"]==TEXT_WRITE:
            from windows_text_write_receipt import project
            return project(manager,operation,row,original)
        if source["toolName"] == COMMAND:
            from windows_command_name import available
            result=command_receipt(manager,operation,row,original)
            if item[4]==COMMAND and result.get("revision") and available(row[3],original,grant):
                result["_canIdentifyCommandName"]=True  # local owner window only; peer uses the unmodified command_receipt projection
            return result
        return (workspace_file if source["toolName"] == FILE else workspace_read)(manager, operation, row, original)


def open_original_number(view, peer):
    from desktop_phone_goal_text import ask_original_operation
    from desktop_sessions import WindowsDesktopHealth
    def current():
        return view._peer_current(peer) and WindowsDesktopHealth().probe(session_only=True).get("available") is True
    if not current(): return
    operation = ask_original_operation(current)
    if operation is None or not current(): return
    if not re.fullmatch("[A-Za-z0-9][A-Za-z0-9_-]{0,79}", operation):
        view._dialog("需要完整原操作编号", "请使用手机原派发里「原操作」后的完整编号，不用 Windows 工作号、短号或文件名。", [], current)
        return
    with view.manager.lock:
        row = view.manager.ledger.execute("""SELECT rowid,operation_id,fingerprint,updated_at,json_extract(details,'$.audience.toolName')
              FROM operations WHERE operation_id=? AND CASE WHEN json_valid(details) THEN
              json_extract(details,'$.audience.callerDeviceId')=? AND json_extract(details,'$.audience.originDeviceId')=? ELSE 0 END""",
              (operation, peer[0], peer[0])).fetchone()
    if row and row[4] in TOOLS and local_record(view, peer, row) and current():
        show(view, peer, row, owning_current=current)
    elif current():
        view._dialog("没有可核对的原回执", "未找到属于所选原设备、这台 Windows 仍有权查看的这次操作。不能据此断定它没有执行；不会改查其他设备或重发。", [], current)


def show(view, peer, item, *, required_work=None, owning_current=lambda: True):
    from desktop_phone_receipts import _parts, _stamp
    if not owning_current(): return
    receipt = local_record(view, peer, item, read=True, required_work=required_work)
    if not receipt: return
    phase = receipt["phase"]
    until = time.monotonic() + 60
    def current(): return time.monotonic() < until and owning_current() and bool(local_record(view, peer, item, revision=receipt.get("revision"), required_work=required_work))
    if item[4] in (PROJECT_RECEIVE, PROJECT_CONFIRM):
        from windows_received_project import walk
        walk(view, peer, item, receipt, current); return
    if item[4] == TEXT_WRITE:
        from windows_text_write_receipt import show as show_text_write
        show_text_write(view, receipt, current); return
    if receipt["profile"] in (EXPORT_PROFILE, project_return.PROFILE):
        def export_current():
            return owning_current() and time.monotonic()<until and bool(local_record(view,peer,item,revision=receipt.get("revision"),export_guard=receipt.get("_guard")))
        if receipt["profile"] == project_return.PROFILE: project_return.show(view, peer, receipt, export_current)
        else: show_export(view,receipt,export_current)
        return
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
        if phase in ("delivered","write_verified_only"):
            from windows_received_file_view import offer
            offer(view,peer,item,receipt,titles[phase],text,current)
        else: view._dialog(titles[phase],text,[],current)
        return
    if receipt["profile"] == READ_PROFILE:
        _show_read(view, peer, item, receipt, current, offer_work=required_work is None)
        return
    body = f"配对设备 …{peer[0][-12:]} → 这台 Windows\n当时的本机工作区：{receipt['environmentId']} · {_stamp(receipt['updatedAt'])}"
    if "exitCode" in receipt: body += f"\n进程退出码：{receipt['exitCode']}"
    if receipt.get("cleanupVerified"): body += "\n原进程组清理已核实。"
    if phase == "accepted_only": body += "\n保留接收编号，不用缓存的文字冒充原工作结果。"
    body += "\n以上只说明这条命令，不证明仓库改动、文件交付或整个目标完成。无需手机在线。"
    while current():
        choices = ([("output", "查看这次命令留存的输出")] if "output" in receipt else []) + [("ids", "核对原操作与本机工作编号")]
        if receipt.get("_canIdentifyCommandName"): choices.insert(0,("name","核对这次请求指定的白名单名称"))
        action = view._dialog(LABEL[phase], body, choices, current)
        if action=="name" and current():
            name=local_record(view,peer,item,command_name=True,revision=receipt.get("revision"),required_work=required_work)
            if name:
                view._dialog("这次原请求指定的命令名",name+"\n已按本机接单时留下的证据核对。不是程序路径，不显示其他参数，也不证明程序现在可用。",[],current)
            elif current():
                view._dialog("本次没有核到原命令名","原工作、别名留存或当前登记无法对应。没有根据输出、文件名或如今的唯一命令猜测。",[],current)
            continue
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


def _show_read(view, peer, item, receipt, current, *, offer_work):
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
    revision = receipt.get("revision")
    def can_check_root():
        return bool(current() and local_record(view, peer, item, revision=revision, read_root=True))
    if kind != "receipt_only" and not can_check_root():
        body += "\n只有当时的相对位置；缺少原目录凭据或可用的位置授权，不按同名目录推断。"
    while current():
        choices = ([] if kind == "receipt_only" else [("content", "查看原文本片段" if kind == "text_snapshot" else "查看原目录项")])
        if offer_work: choices.append(("work", "查看这次手机任务在此工作区的读取"))
        if kind != "receipt_only" and can_check_root(): choices.append(("root", "核对原工作区在这台电脑的位置"))
        if kind=="text_snapshot" and can_check_root():
            choices.append(("marks","查看机主为这次原读取标记的本机补丁"))
            choices.append(("compare","对照这次读取和一个本机源码副本\n仅核对这份文件的两个历史版本，不读取现在的项目。"))
        choice = view._dialog(title, body, choices + [("ids","核对原操作和 Windows 工作编号")], current)
        if choice in ("compare","marks") and current():
            from windows_task_source_match import show
            def stamp(*,full,expected_work=None):
                return local_record(view,peer,item,revision=revision,match_source=True,read=full,required_work=expected_work)
            show(view,stamp,current,bookmarks=choice=="marks")
            continue
        if choice == "root":
            path = local_record(view, peer, item, revision=revision, read_root=True, read=True)
            if path and can_check_root():
                view._dialog("刚刚核对的 Windows 工作目录", f"{path}\n当时登记号：{receipt['environmentId']}。"
                             "原授权绑定和本机原目录身份本次相符。这不是目录浏览，不证明其中的文件现在仍在；位置没有发给手机。", [], can_check_root)
            elif current():
                view._dialog("没有核实原工作目录", "原目录或当前授权已无法核对。保留当时的读取回执；没有改用同名位置或打开文件夹。", [], current)
            continue
        if choice == "work":
            group = local_record(view, peer, item, work_key=True)
            if group and current(): read_work.show(view, peer, item, group, current)
            continue
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
