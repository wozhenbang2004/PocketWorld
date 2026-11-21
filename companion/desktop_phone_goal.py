"""Human-only, in-process Windows entry to ONE original Android phone goal.

No new transport, RPC method, shell, screen access, project sharing or lease.
The Android receiver remains the only task/run authority; this PC retains only
its ordinary source operation. Closing the entry cannot stop the phone.
"""
from __future__ import annotations

import threading
import unicodedata
import uuid

import device_protocol as wire
from desktop_owner_confirmation import confirm_local_phone_goal, confirmation_record
from desktop_phone_goal_text import ask_goal
from desktop_phone_receipts import ResultView
from desktop_sessions import WindowsDesktopHealth
from phone_task_bridge import PhoneTaskError


ZERO_TOOL_CONTRACT = "pocketworld.zero-tool-phone.no-screen-reviewed.v2"


class BeforeSend(Exception):
    """A failed precondition, before any goal submission."""


def choices_for(description, peer, caller, now):
    if (description.get("deviceId") != peer[0] or description.get("instanceId") != peer[2]
            or not description["capturedAt"] <= now < description["validUntil"]): return []
    profile = next((p for p in description.get("capabilities", []) if p.get("id") == "agent.goal"
                    and p.get("installed") is True and p.get("implemented") is True), {})
    review = profile.get("ready") is not True
    if (profile.get("zeroToolPhoneContract") != ZERO_TOOL_CONTRACT or profile.get("version") != wire.VERSION or profile.get("busy") is True or profile.get("blocked") is True
            or review and profile.get("canSubmitForLocalReview") is not True): return []
    scopes = []
    for receipt in description.get("grantReceipts", []):  # Already signature-checked by the original router doctor.
        body, grant = receipt["body"], receipt["body"]["grant"]
        if (receipt["issuerDeviceId"] != peer[0] or receipt["issuerCertificateFingerprint"] != peer[1]
                or body["targetInstanceId"] != peer[2] or grant["localDeviceId"] != peer[0] or grant["peerDeviceId"] != caller
                or grant.get("revoked") or not grant["issuedAt"] <= now < grant["expiresAt"] - 1000
                or "task.submitGoal" not in grant["capabilities"] or grant["modelBudget"] < 1): continue
        history = grant.get("historyRead") or {}
        readable = set(grant["capabilities"])
        if now < history.get("expiresAt", 0): readable.update(history.get("methods", []))
        if not {"operation.get", "task.get"} <= readable: continue
        for binding in grant["resources"]:
            if binding == {"screenMode":"capabilities"}:
                # This is the original context_goal grant, not a project/checkout
                # and not an implicit screen grant. An unmarked old phone cannot receive it here.
                if (profile.get("directTextReturnContract") == wire.TEXT_RETURN and "capabilities" in profile.get("supportedExecutionModes", [])
                        and "task.get" in grant["capabilities"] and not grant.get("allowDelegation") and not grant.get("forwardingScope")
                        and set(grant["toolNames"]) <= {"phone_request_user","phone_finish"}):
                    scopes.append((grant, binding, review))
                continue
            if (binding.get("screenMode") not in ("main_screen","background") or binding["screenMode"] not in profile.get("supportedExecutionModes", [])
                    or type(binding.get("androidUserId")) is not int or len(binding.get("packageNames", [])) != 1
                    or binding.get("allowWholeDisplayInput") or binding.get("allowWholeDisplayCapture")): continue
            wire.validate_resource_binding(profile["version"], binding)
            # A general grant is never silently turned into a read-only one. No
            # tool calls are possible from this entry; even app/file/screen reads
            # need their separate existing workflow. Only the typed text travels.
            scopes.append((grant, binding, review))
    return sorted(scopes,key=lambda item:item[1].get("screenMode")!="capabilities")


class PhoneWorkEntry:
    def __init__(self, manager):
        self.receipts = ResultView(manager, owner_stop=True)  # Only this explicitly started native entry may offer stop.
        self.end, self.active = self.receipts.end, self.receipts.active
        self.presenting, self.past = threading.Lock(), None

    def current(self, peer=None):
        return (self.active() and WindowsDesktopHealth().probe(session_only=True).get("available") is True
                and (peer is None or self.receipts._peer_current(peer)))

    def pick(self, title, detail, rows, current):
        page = 0
        while self.active():
            choices = [(i, label) for i, label in enumerate(rows) if page * 5 <= i < (page + 1) * 5]
            if page: choices.append(("prev", "上一页"))
            if (page+1)*5 < len(rows): choices.append(("next", "下一页"))
            index = self.receipts._dialog(title, detail, choices, current)
            if index == "prev": page -= 1
            elif index == "next": page += 1
            else: return index

    def scopes(self, peer):
        if not self.current(peer): raise BeforeSend("原手机身份或本机入口已变化。")
        try:
            verified, channel = self.receipts.router.discover_for_owner_text(peer[0]); desc = verified["description"]
        except PhoneTaskError as error:
            reason = ("已配对，但没有这台设备的可用连接。请在原手机检查连接。" if error.status == 503 else
                      "原手机没有确认这次能力读取或原授权已失效；不会重新授权。" if error.status == 403 else
                      "没能取得属于这台手机的当前能力回执；不会切换设备。")
            raise BeforeSend(reason) from error
        profile = next((p for p in desc.get("capabilities", []) if p.get("id") == "agent.goal"), None)
        if profile and profile.get("busy") is True: raise BeforeSend("选中的手机能响应，但本机执行器正忙。没有新建任务。")
        if not profile or not profile.get("ready") and not profile.get("canSubmitForLocalReview"):
            raise BeforeSend("已连接，但手机未报告可以接收目标或由机主现场审阅。请在手机检查接收开关或本机模型。")
        if profile.get("zeroToolPhoneContract") != ZERO_TOOL_CONTRACT:
            raise BeforeSend("请先更新原手机。它尚未证明文字工作不会使用屏幕、只会交回机主核对的摘要，因此不会发送。")
        if not self.current(peer): raise BeforeSend("原手机身份或本机入口已变化。")
        return choices_for(desc, peer, self.receipts.service.identity.device_id, self.receipts.manager.now()), channel

    def _original_current(self, original):
        if original is None: return False
        peer, row = original
        try:
            return bool(self.current(peer) and self.receipts._source_current(peer, row))
        except Exception:
            return False  # Missing or unreadable local evidence is not an outcome.

    def _original_reference(self, peer, operation, grant_id):
        # Neither the confirmation UUID nor an uncommitted insert is a dispatch record.
        manager = self.receipts.manager
        try:
            with manager.lock:
                if manager.ledger.in_transaction: return None
                fingerprint, saved = self.receipts.router._row(operation)  # The manager lock is reentrant.
            if saved.get("grantId") != grant_id: return None
        except Exception: return None
        original = (peer, (None, operation, fingerprint))
        return original if self._original_current(original) else None

    def read_original(self, original):
        if self._original_current(original): self.receipts.choose_outgoing(*original)

    def prepare(self):
        peers = self.receipts._peers()
        selected = self.pick("把文字工作交给哪台手机？", "这是本机配对名单，不代表在线、可读或可操作。选中后再决定是否核对。",
                             ["设备 …" + p[0][-16:] + "\n实例 " + p[2] for p in peers], self.current) if peers else None
        if not peers: self.receipts._dialog("还没有配对伙伴", "请先在原伴侣和手机上配对。本入口不会启动配对或授权。", [])
        if selected is None: return
        peer = peers[selected]
        current = lambda: self.current(peer)
        if self.receipts._dialog("核对选中手机是否接收目标？", "设备 " + peer[0] + "\n实例 " + peer[2] +
                   "\n只向这一个已配对身份读取当前能力和原签发授权。不发送目标、不读取屏幕或项目。", [("check","核对这台设备")], current) != "check": return
        scopes, _ = self.scopes(peer)
        if not scopes:
            self.receipts._dialog("没有可用的手机目标范围", "对端未报告可用于本次文字工作的原授权。可在手机原配对范围中核对「处理目标，不访问本机项目」；旧版不会用普通任务替代。", [], current); return
        def scope_label(scope):
            grant, binding, review = scope
            if binding["screenMode"]=="capabilities":
                return "仅文字 · 不关联应用或屏幕\n" + ("机主现场审阅" if review else "可接收") + " · 授权 …" + grant["grantId"][-8:]
            return ("后台屏" if binding["screenMode"] == "background" else "主屏") + " · " + binding["packageNames"][0] + \
                   "\nAndroid 用户 " + str(binding["androidUserId"]) + (" · 机主现场审阅" if review else " · 可接收") + " · 授权 …" + grant["grantId"][-8:]
        index = self.pick("用哪项原授权处理文字？", "优先使用仅文字范围，无需开屏幕权限；不会读取或操作应用、共享项目。", list(map(scope_label, scopes)), current)
        if index is None: return
        chosen = scopes[index]; grant, binding, review = chosen
        text = ask_goal(current)
        if not text: return
        # Escape control/format characters in the local review to prevent a typed
        # goal from visually rewriting the destination, budget or operation.
        visible = "".join(ch if ch in "\n\t" or not unicodedata.category(ch).startswith("C") else f"\\u{ord(ch):04x}" for ch in text)
        operation = str(uuid.uuid4())
        models = min(3, grant["modelBudget"])
        target = scope_label(chosen).replace("\n", " · ")
        summary = (f"目标设备：{peer[0]}\n实例：{peer[2]}\n范围：{target}\n"
                   f"授权：{grant['grantId']} / {grant['authorizationEpoch']}\n操作：{operation}\n"
                   f"工具 0 次、模型最多 {models} 次；最长 10 分钟，不超过原授权截止时间。\n"
                   "只可凭原授权读取机主核对的摘要（最多 2,048 个字）；不会读取依据或执行记录。\n"
                   + ("对端只报告可由机主现场审阅，不代表已接手。" if review else "对端报告可接收；按手机已有策略，提交后可能开始。")
                   + "\n\n将发送的目标全文：\n" + visible)
        try:
            proof = confirm_local_phone_goal(summary)
            confirmation_record(proof, purpose="original_phone_goal", summary=summary)
        except Exception as error: raise BeforeSend("未取得针对这一次原目标的本机确认。") from error
        if not current(): return
        # Re-read on THIS confirmed send only; do not refresh automatically.
        fresh, channel = self.scopes(peer)
        if not any(wire.canonical_bytes(list(choice)) == wire.canonical_bytes(list(chosen)) for choice in fresh):
            self.receipts._dialog("原范围或接收状态已变", "尚未发送；不会换设备、换授权、续期或改为整屏。", [], current); return
        now = self.receipts.manager.now()
        ttl = min(600000, grant["expiresAt"] - now)
        if not 0 <= now - proof.confirmed_at < 30000 or ttl < 1000:
            raise BeforeSend("这次确认或原授权已到期。没有续期或派单。")
        # This nonserializable guard travels only within the original process.
        # Pin the actual ticket after restore and immediately before original SQL.
        def fence(connection, actual, ticket):
            if (connection is not channel[0] or actual is not channel[1] or self.receipts.manager.now() >= channel[2] or not current() or not 0 <= self.receipts.manager.now() - proof.confirmed_at < 30000
                    or actual.device_id != peer[0] or actual.certificate_fingerprint != peer[1]
                    or ticket.target_instance_id != peer[2] or wire.canonical_bytes(ticket.grant) != wire.canonical_bytes(grant)):
                raise PermissionError("The reviewed original phone scope changed")
        try:
            value = self.receipts.router.submit_owner_goal(dict(peerDeviceId=peer[0], operationId=operation, goal=text,
                    grantId=grant["grantId"],resourceBinding=binding,capabilityVersion=wire.VERSION,toolCalls=0,modelCalls=models,ttlMs=ttl), fence)
            matched = value.get("operationId") == operation and value.get("peerDeviceId") == peer[0]
            accepted = matched and value.get("accepted") is True
            refused = matched and value.get("accepted") is False and value.get("status") == "rejected"
        except Exception: accepted = refused = False
        original = self._original_reference(peer, operation, grant["grantId"])
        if original is None:
            self.receipts._dialog("这笔派单的接收未能核对", f"本次准备编号：{operation}\n尚未核对到本机原派单。不能据此认定手机已接收、拒绝或没有执行。不会自动回查或重发。", [], current)
            return
        self.past = original
        title = "手机已回报接受这笔原派单" if accepted else "手机拒绝这笔工作" if refused else "这笔派单的接收未能核对"
        if self.receipts._dialog(title, f"原操作：{operation}\n这不是机主已读、执行完成或结果。不会自动回查、重试或重派。",
                                [("read","核对这笔原派单\n按原历史权限向同一手机核对。")], current) == "read": self.read_original(original)

    def show(self):
        if not self.presenting.acquire(blocking=False): return
        def display():
            try:
                options = [("new", "准备新的文字工作\n选择已配对手机并逐步核对；现在不联系设备。")]
                previous = self.past
                if self._original_current(previous): options.insert(0, ("read", "核对上次原派单\n只核对原设备和原派单，不会再发。"))
                options.append(("history","本机原工作回执\n从已有记录选择，不会新建或重派。"))
                selected = self.receipts._dialog("交给手机", "手机保有原任务和结果。这里不开放终端、设备管理员或远程输入。", options, self.current)
                if selected == "new": self.prepare()
                if selected == "read": self.read_original(previous)
                if selected == "history": self.receipts.show()
            except BeforeSend as error:
                if self.current(): self.receipts._dialog("尚未发送目标", str(error), [])
            except Exception:
                if self.current():
                    self.receipts._dialog("本次操作未取得确认", "可能已关闭、离线，或原权限发生变化。若已产生原派单，请从原回执核对；不会自动重试。", [])
            finally: self.presenting.release()
        threading.Thread(target=display, name="owner-original-phone-goal", daemon=True).start()


def open_owner_work(manager):
    from desktop_receipt_icon import LocalReceiptIcon
    return LocalReceiptIcon(PhoneWorkEntry(manager), phone_work=True).start()
