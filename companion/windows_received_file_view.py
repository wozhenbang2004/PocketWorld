"""Unlocked Windows owner's optional original received-file reader, never an RPC."""
from contextlib import ExitStack
import hashlib
import json
import re
import sqlite3
import time

import device_protocol as protocol
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from windows_received_file import PROFILE, project
from windows_received_job import FILE

def _sha(raw): return hashlib.sha256(raw).hexdigest()


def access(manager,op,row,original,grant,now,revision,ticket,held):
    """Invoked ONLY after local_record's existing original-peer/history checks.
    A write grant, an expired execution scope, or operation.get by itself does not
    admit a byte read. Do not synthesize a permission or contact the sender.
    """
    try:
        source,accepted=original
        if (source["toolName"]!=FILE or type(revision) is not int or revision<=0
                or grant["expiresAt"]<=now or "tool.invoke" not in grant["capabilities"]): return None
        witness=json.loads(row[3])["windowsIncomingFile"]; archive=witness["archive"]
        fp,kind,seal=witness["fileOperationFingerprint"],archive["class"],archive["seal"]
        if (witness["format"]!=PROFILE or set(archive)!={"class","seal"} or kind not in ("text","file")
                or not re.fullmatch("[a-f0-9]{64}",fp) or not re.fullmatch("[a-f0-9]{64}",seal)): return None
        names=grant["toolNames"]
        if "workspace.export" not in names and not (kind=="text" and "workspace.read" in names): return None
        # The original physical workspace still has to be registered for the
        # same pair/instance/configuration. Never open today's destination file.
        from device_companion import workspace_binding
        eid=accepted["resourceBinding"]["environmentId"]
        if eid not in manager.environments or not protocol.resource_equal(accepted["resourceBinding"],workspace_binding(manager,eid)): return None
        fixed=(op,row[0],row[1],revision,fp,kind,seal)
        if ticket is not None and ticket!=fixed: return None
        if held is None: return fixed
        if ticket!=fixed or not isinstance(held,ExitStack): return None
        evidence=project(manager,op,row,original)
        if evidence["phase"] not in ("delivered","write_verified_only") or evidence["revision"]!=revision: return None
        args=manager.jobs[row[1]]["nativeInput"]
        if (_sha(protocol.canonical_bytes(args))!=evidence["inputDigest"] or _sha(args["path"].encode())!=evidence["pathDigest"]
                or evidence["version"]!="sha256:"+evidence["sha256"]): return None
        body=manager.files.versions.delivery_bytes(manager._environment(eid),args["path"],evidence["version"],(op,fp),kind,seal,held)
        return body if len(body)==evidence["bytes"] and _sha(body)==evidence["sha256"] else None
    except (KeyError,TypeError,ValueError,AttributeError,OSError,RecursionError,sqlite3.Error,PlatformError,protocol.ProtocolError):
        return None


def offer(view,peer,item,receipt,title,text,current):
    from desktop_received_commands import local_record
    revision=receipt["revision"]
    def use(**kwargs): return local_record(view,peer,item,revision=revision,archive=True,**kwargs)
    ticket=use()
    if not ticket:
        view._dialog(title,text+"\n当前只有回执：没有同时核对到原留存记录和现行工作区文件读取范围。",[],current)
        return
    action=view._dialog(title,text+"\n可另行核对这次接收时留下的版本；不会打开工作区里现在的同名文件。",
                        [("open","在这台电脑查看当时的原文件\n只读，不按文档类型启动程序或发回手机。")],current)
    if action!="open" or not current(): return
    until=time.monotonic()+600  # view lifetime, never a grant extension
    def active():
        try:
            return (time.monotonic()<until and view.active() and use(ticket=ticket)==ticket
                    and WindowsDesktopHealth().probe(session_only=True).get("available") is True)
        except Exception: return False
    if not active(): return
    with ExitStack() as held:
        body=use(ticket=ticket,held=held)
        if not active(): return
        if body is None:
            view._dialog("原文件内容未能取回","已保留当时的落盘回执。原版本可能已经轮换、移动或损坏；未读取现在的目标、其他缓存或手机。",[],active)
            return
        from windows_original_file_reader import show
        try: show(receipt,body,active)
        except (OSError,AttributeError,ValueError,PlatformError):
            if active(): view._dialog("没有打开本机阅读器","系统未能完整显示所选原件。没有交给外部应用、保存副本或重发。",[],active)
