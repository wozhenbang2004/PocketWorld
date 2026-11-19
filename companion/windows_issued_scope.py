"""Read the Windows-issued original grant locally, without contacting peers, signing, or changing settings."""
import base64
from datetime import datetime
import time

import device_identity
import device_pairing as pairing
import device_protocol as protocol
import windows_pair_identity as pair_identity
from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError

LABELS = {
    "workspace.list": "列目录", "workspace.read": "读文本", "workspace.export": "导出文件",
    "workspace.location": "查目录位置", "workspace.write": "写文本", "workspace.receive": "收文件",
    "workspace.project.receive": "接收项目文件", "workspace.project.confirm": "确认项目文件",
    "workspace.project.export": "导出项目文件", "command.run": "运行已配置命令",
    "desktop.observe": "观察桌面", "desktop.act": "操作桌面", "desktop.control": "桌面控制",
    "desktop.viewer": "桌面观看与人工接管", "operation.get": "查原操作", "operation.cancel": "取消原操作",
    "device.describe": "核对该设备身份与自己的授权回执", "tool.invoke": "调用获准工具", "artifact.describe": "查原制品描述", "artifact.readChunk": "分块读取原制品",
}


def stamp(ms):
    return datetime.fromtimestamp(ms / 1000).astimezone().strftime("%Y-%m-%d %H:%M:%S %z")


def matches(receiver, peer):
    current = receiver.trust.peer(peer[0])
    return current and not current["revoked"] and (current["fingerprint"], current["instanceId"], current["authorizationEpoch"]) == peer[1:]


def original(receiver, peer):
    local, store = receiver.identity, receiver.trust
    # No global "last pairing" or outgoing grants: rows must have Windows as issuer and the selected peer as recipient.
    with store.lock:
        ids = store.db.execute("""SELECT i.invite_id FROM device_pairing_invites i JOIN device_pairing_candidates c
            ON c.invite_id=i.invite_id WHERE i.inviter_device_id=? AND i.instance_id=? AND i.peer_device_id=?
            AND i.state='consumed' AND c.state='consumed' AND c.attempts>0 LIMIT 2""",
            (local.device_id, local.instance_id, peer[0])).fetchall()
        if len(ids) != 1 or not matches(receiver, peer):
            raise PlatformError("No unambiguous original request")
        record = store.candidate(ids[0][0])
        accepted_peer = store.peer(peer[0])
    completion, transcript = record["committedCompletion"], record["transcript"]
    commit = pairing._verify_committed_completion(local.certificate_der, completion, transcript, receiver.manager.now())
    peer_cert = base64.b64decode(accepted_peer["certificateDer"], validate=True)
    if ((commit["inviterDeviceId"], commit["inviterInstanceId"], commit["inviterFingerprint"]) !=
            (local.device_id, local.instance_id, local.certificate_fingerprint)
            or (commit["joinerDeviceId"], commit["joinerFingerprint"], commit["joinerInstanceId"]) != peer[:3]
            or (device_identity.device_id(peer_cert), device_identity.certificate_fingerprint(peer_cert)) != peer[:2]
            or commit["inviteId"] != ids[0][0] or commit["transcriptDigest"] != record["candidateDigest"]
            or commit["grantDigest"] != record.get("reviewedOwnerGrantDigest")):
        raise PlatformError("Original identities or owner-reviewed scope changed")
    pairing._verify_confirmation_certificate(record["peerConfirmation"], peer_cert, transcript)
    # Never render the invitation, DER, signature or a workspace path from today's configuration.
    grant = completion["grantReceipt"]["body"]["grant"]
    for resource in grant["resources"]:
        if pair_identity.exact(grant) and resource == pair_identity.BINDING: continue
        kind = {"workspace": "workspace-v1", "desktop": "desktop-v2"}.get(resource.get("kind"))
        if not kind: raise PlatformError("Unrecognized original Windows resource")
        protocol.validate_resource_binding(kind, resource)
    return grant, commit


def state(receiver, peer, grant, digest):
    if not matches(receiver, peer):
        return "unavailable"
    now, current = receiver.manager.now(), receiver.trust.grant(grant["grantId"])
    if (current is None or current["revoked"] or current["authorizationEpoch"] != peer[3]
            or pairing._pairing_grant_digest(current) != digest or grant["issuedAt"] > now):
        return "changed"
    if now < grant["expiresAt"]:
        return "within"
    if now < grant.get("historyRead", {}).get("expiresAt", 0):
        return "history"
    return "expired"


STATUS = {
    "within": "与本机当前登记一致，未到执行期限。",
    "history": "执行期限已过，仅另授的原结果读取期限尚未到；能否读还要核对原操作及实际留存。",
    "expired": "原执行期限已到，没有仍在有效期的延长读取声明。",
    "changed": "这份原授权不再与本机当前有效登记吻合。下面只说明当时签了什么。",
}


def details(grant, commit):
    lines = ["电脑：" + grant["localDeviceId"], "电脑实例：" + commit["inviterInstanceId"],
             "电脑指纹：" + commit["inviterFingerprint"], "对方设备：" + grant["peerDeviceId"],
             "对方实例：" + commit["joinerInstanceId"], "对方指纹：" + commit["joinerFingerprint"],
             "原授权：" + grant["grantId"], "签发范围摘要：" + commit["grantDigest"]]
    for r in grant["resources"]:
        if pair_identity.exact(grant) and r == pair_identity.BINDING:
            lines.append("仅配对：只核对本机身份和这份回执，不开放工作区或桌面。")
            continue
        if r["kind"] == "workspace":
            lines.append("工作区 " + r["environmentId"] + " · 原绑定 " + r["binding"])
        else:
            scope = f"窗口 0x{r['windowId']:X}" if "windowId" in r else "主屏（所有窗口）"
            lines.append(f"桌面会话 {r['sessionId']} · {scope}")
    for category, items in (("协议方法", grant["capabilities"]), ("工作工具", grant["toolNames"]),
                            ("原结果延长读取", grant.get("historyRead", {}).get("methods", []))):
        for name in items:
            if not name.isascii() or not name.replace(".", "").replace("_", "").replace("-", "").isalnum():
                raise PlatformError("Unrecognized original tool or method")
            lines.append(f"{category}：{LABELS.get(name, '原登记项')}（{name}）")
    lines.extend("允许的发起设备：" + origin for origin in grant["allowedOrigins"])
    lines.append(f"可转交：{'是' if grant['allowDelegation'] else '否'}；最大层级 {grant['maxDelegationDepth']}。")
    if "forwardingScope" in grant:
        # This native Windows pairing path never issues forwarding authority. Do not silently omit a different signed contract.
        raise PlatformError("This original forwarding contract cannot be displayed here")
    return [lines[n:n + 6] for n in range(0, len(lines), 6)]


def show(receiver, allowed, peer=None):
    manager = receiver.manager
    health = WindowsDesktopHealth()
    initial = health.probe(session_only=True)
    if receiver.closed or manager.closed or not allowed() or initial.get("available") is not True: return
    session = initial["sessionId"]

    def present(title, text, actions, still=lambda: True):
        last, lost = 0.0, False
        def live(force=False):
            nonlocal last, lost
            if not force and time.monotonic() - last < 0.6: return not lost
            last = time.monotonic()
            try:
                facts = health.probe(session_only=True)
                lost = lost or receiver.closed or manager.closed or not allowed() or facts.get("available") is not True or facts.get("sessionId") != session or not still()
            except Exception: lost = True
            return not lost
        if not live(True): return None
        choice = _dialog(title, text, actions, retire=lambda: not live())
        return choice if live(True) else None

    if peer is None:
        page = 0
        while True:
            rows = receiver.trust.peers(limit=128)
            peers = [(p["deviceId"], p["certificateFingerprint"], p["instanceId"], p["authorizationEpoch"]) for p in rows if not p["revoked"]]
            page = min(page, max(0, (len(peers) - 1) // 6))
            choices = [(p, f"设备 …{p[0][-12:]}\n配对指纹 …{p[1][-12:]}") for p in peers[page * 6:page * 6 + 6]]
            if page: choices.append(("prev", "上一页"))
            if (page + 1) * 6 < len(peers): choices.append(("next", "下一页"))
            peer = present("查看这台 Windows 曾向哪台设备签发权限？", "只读本机配对记录，不联系设备，不创建邀请，也不授予新权限。"
                           + (" 本页只列最近的 128 条设备登记中的有效配对。" if len(rows) == 128 else "")
                           + ("" if peers else "\n没有可供核对的当前配对设备。"), choices)
            if peer == "prev": page -= 1
            elif peer == "next": page += 1
            else: break
    if not isinstance(peer, tuple) or len(peer) != 4 or not allowed() or receiver.closed or manager.closed or not matches(receiver, peer): return
    try:
        grant, commit = original(receiver, peer)
        pages = details(grant, commit)
        reading = state(receiver, peer, grant, commit["grantDigest"])
        if reading not in STATUS: return
        history = grant.get("historyRead")
        timeline = (f"签发于 {stamp(commit['committedAt'])}。执行至 {stamp(grant['expiresAt'])}。"
                    + (f"原结果另可读至 {stamp(history['expiresAt'])}。" if history else "未另授到期后的原结果读取。"))
    except Exception:
        present("未能核对这台电脑的原签发范围", "未找到唯一、可验签并绑定同一设备的本机签发原件，或不能完整展示其范围。旧记录和反向授权不能替代它；没有更改配对。",
                [], lambda: matches(receiver, peer))
        return
    check = lambda: state(receiver, peer, grant, commit["grantDigest"]) == reading
    tools = [LABELS.get(name, name) for name in grant["toolNames"]]
    scope = grant["resources"]
    names = [r["environmentId"] if r["kind"] == "workspace" else "Windows 桌面" for r in scope]
    summary = (f"收方设备 …{peer[0][-12:]}。{STATUS[reading]}\n原范围：{'、'.join(names[:4])}"
               + (f" 等 {len(scope)} 项" if len(scope) > 4 else "")
               + f"。\n签发的工具：{'、'.join(tools[:5]) or '无'}"
               + (f" 等 {len(tools)} 项" if len(tools) > 5 else "")
               + f"。\n{timeline}\n工具/模型原额度：{grant['toolBudget']}/{grant['modelBudget']}，不是剩余额度。"
               + "\n仅这一笔首次授权，不含后来另签的权限或反向授权。未核实设备在线、原目录仍可读或桌面可接管。")
    index = -1
    while check():
        if index < 0:
            chosen = present("首次配对时，这台 Windows 签出了什么？", summary, [("next", "逐项看实际签发的范围")], check)
        else:
            choices = [("prev", "上一页" if index else "返回原授权概览")]
            if index + 1 < len(pages): choices.append(("next", "下一页"))
            chosen = present(f"原签发明细 · {index + 1}/{len(pages)}", "\n".join(pages[index]), choices, check)
        if chosen == "next": index += 1
        elif chosen == "prev": index -= 1
        else: break
