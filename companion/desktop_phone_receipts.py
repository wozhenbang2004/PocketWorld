"""Opt-in, in-process, read-only Windows owner view of this PC's original phone work."""
from __future__ import annotations

from datetime import datetime
import threading
import time
import unicodedata

from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from phone_task_bridge import PROTOCOL
from phone_task_outcome import is_phone_action_goal


_STATE = {"succeeded":"已完成", "failed":"失败", "cancelled":"已取消", "interrupted":"已中断", "expired":"已到期",
          "running":"执行中", "queued":"已入队", "waiting_approval":"等手机确认", "waiting_dependency":"等待依赖",
          "accepted":"对端曾接受", "completed":"曾记录完成", "dispatch_prepared":"准备派发", "unknown":"状态未确认"}
_BATCH = """SELECT rid,operation_id,fingerprint,updated_at,CASE WHEN
       json_extract(doc,'$.protocol')=? AND json_extract(doc,'$.kind')='task.submitGoal'
       AND json_extract(doc,'$.peerDeviceId')=? AND json_extract(doc,'$.instanceId')=?
       AND json_extract(doc,'$.operationId')=operation_id AND json_extract(doc,'$.resourceBinding.screenMode') IN ('main_screen','background')
       AND json_type(doc,'$.resourceBinding.androidUserId')='integer' AND json_array_length(doc,'$.resourceBinding.packageNames')>0
       THEN json_extract(doc,'$.status') END FROM
       (SELECT rowid AS rid,operation_id,fingerprint,updated_at,CASE WHEN json_valid(details) THEN details END AS doc
        FROM operations WHERE rowid<? ORDER BY rowid DESC LIMIT 64) ORDER BY rid DESC"""


def _stamp(ms):
    try: return datetime.fromtimestamp(ms / 1000).strftime("%m-%d %H:%M")
    except (OSError, OverflowError, TypeError, ValueError): return "时间未核对"


def _parts(value):
    pages, text, lines = [], "", 1
    for char in value:
        if len(text) >= 360 or lines >= 8 and char == "\n":
            pages.append(text); text, lines = "", 1
        text += char
        if char == "\n": lines += 1
    if text or not pages: pages.append(text)
    return pages


class ResultView:
    def __init__(self, manager):
        self.manager = manager
        self.service = manager.device_companion
        self.router = self.service.phone_tasks
        self.end = threading.Event()
        self.presenting, self.reading = threading.Lock(), threading.Lock()
        self.receiver = self.service.receiver

    def active(self):
        return not (self.end.is_set() or self.manager.closed or self.service.closed or self.router.closed) and self.manager.device_companion is self.service

    def _peers(self):
        return [(p["deviceId"], p["certificateFingerprint"], p["instanceId"], p["authorizationEpoch"])
                for p in self.receiver.trust.peers(limit=128) if not p["revoked"]]

    def _peer_current(self, peer):
        if not self.active(): return False
        current = self.receiver.trust.peer(peer[0])
        return current and not current["revoked"] and (current["fingerprint"], current["instanceId"], current["authorizationEpoch"]) == peer[1:4]

    def _page(self, peer, before=None):
        # Index seek, bounded projection, release the business manager lock
        # between batches. Empty matched pages still expose the older cursor.
        selected, cursor = [], before if before is not None else 9223372036854775807
        for _ in range(4):
            with self.manager.lock:
                batch = self.manager.ledger.execute(_BATCH, (PROTOCOL, peer[0], peer[2], cursor)).fetchall()
            for index, row in enumerate(batch):
                cursor = row[0]
                if isinstance(row[4], str):
                    selected.append(row)
                    if len(selected) == 6:
                        return selected, cursor if index + 1 < len(batch) or len(batch) == 64 and cursor > 1 else None
            if len(batch) < 64 or cursor <= 1: return selected, None
        return selected, cursor

    def _source_current(self, peer, row):
        if not self._peer_current(peer): return False
        fingerprint, value = self.router._row(row[1])
        return (fingerprint == row[2] and value.get("operationId") == row[1] and value.get("peerDeviceId") == peer[0]
                and value.get("instanceId") == peer[2] and is_phone_action_goal(value))

    def _dialog(self, heading, body, choices, current=lambda: True):
        last, lost = 0.0, False
        def retire():
            nonlocal last, lost
            if lost or not self.active(): return True
            if time.monotonic() - last < 0.6: return False
            last = time.monotonic()
            try: lost = not current() or WindowsDesktopHealth().probe(session_only=True).get("available") is not True
            except Exception: lost = True
            return lost
        if retire(): return None
        result = _dialog(heading, body, choices, retire=retire)
        if not self.active() or lost: return None
        try: return result if current() else None
        except Exception: return None

    def _read(self, peer, row):
        if not self.reading.acquire(blocking=False):
            self._dialog("上一笔读取仍在返回", "没有再发一笔读取。稍后再进入原工作查看。", []); return
        done, box = threading.Event(), {}
        def read():
            try:
                if self._source_current(peer, row):
                    result, guard = self.router.read_for_local_receipt(peer[0], row[1])
                    if self._source_current(peer, row) and result.get("operationId") == row[1] and result.get("peerDeviceId") == peer[0]:
                        box.update(result=result, guard=guard)
            except Exception: pass  # No URI, tokens, raw receipts or phone text in logs/errors.
            finally: self.reading.release(); done.set()
        threading.Thread(target=read, name="owner-original-phone-read", daemon=True).start()
        if not done.wait(0.1):
            completed = False
            def finish_wait():
                nonlocal completed
                if not self._source_current(peer, row) or WindowsDesktopHealth().probe(session_only=True).get("available") is not True: return True
                completed = done.is_set()
                return completed
            _dialog("正在向原手机读取这项工作", "只读取所选原派单；关闭可离开等待，不取消手机任务。", [], retire=finish_wait)
            if not completed: return
        if not self._source_current(peer, row): return
        result, guard = box.get("result", {}), box.get("guard")
        outcome, task = result.get("phoneGoalOutcome"), result.get("task")
        body = f"原操作：{row[1]}\n目标设备：{peer[0]}\n"
        if (result.get("phoneStateUnconfirmed") or not callable(guard) or not isinstance(outcome, dict) or not isinstance(task, dict)
                or outcome.get("operationId") != row[1] or outcome.get("peerDeviceId") != peer[0] or outcome.get("instanceId") != peer[2]
                or outcome.get("taskId") != task.get("taskId") or outcome.get("runId") != task.get("runId") or outcome.get("executorEpoch") != task.get("executorEpoch")):
            self._dialog("尚未取得可核对的原手机结果", body + "电脑的派单记录仍在。手机离线、原授权不允许读取或运行身份未对上时，不能判为失败或没有成果。", [], lambda: self._source_current(peer, row))
            return
        status = _STATE.get(task.get("state"), "状态已返回")
        identity = body + f"手机任务：{task['taskId']}\n本轮：{task['runId']} · {task['executorEpoch']}"
        body = f"手机 …{peer[0][-12:]} · 原工作 …{row[1][-12:]}\n手机状态：{status} · {_stamp(task.get('updatedAt'))}\n\n"
        if outcome.get("review") == "confirmed":
            # Native plain text only. Keep paragraphs; do not turn result content into links/actions.
            summary = "".join(ch if ch in "\n\t" or not unicodedata.category(ch).startswith("C") else "�" for ch in outcome["summary"])
            parts = _parts((summary or "手机已确认结束，未提供文字摘要。") + ("\n（手机只返回了部分摘要）" if outcome["truncated"] else ""))
            title = "原手机已确认这份成果"
        else:
            title = {"pending":"等待原手机确认成果", "none":"本轮没有已核实的成果", "not_confirmed":"原手机未确认成果",
                     "unverified":"原手机未能核对成果"}.get(outcome.get("review"), "原手机没有可核对的成果回执")
            parts = ["以上是原任务状态，不是已确认的成果摘要。"]
        index = 0
        def valid(): return self._source_current(peer, row) and guard()
        while self.active():
            choices = ([("prev", "上一段")] if index else []) + ([("next", "下一段")] if index + 1 < len(parts) else []) + [("identity", "查看原操作和运行编号")]
            number = f" · {index+1}/{len(parts)}" if len(parts) > 1 else ""
            selected = self._dialog(title + number, body + parts[index] + "\n\n仅在本次原读取的短时范围内显示。", choices, valid)
            if selected == "prev": index -= 1
            elif selected == "next": index += 1
            elif selected == "identity":
                if self._dialog("同一笔原派单", identity, [("back","返回这份原回执")], valid) != "back": return
            else: return

    def show(self):
        if not self.presenting.acquire(blocking=False): return
        def show():
            try:
                if WindowsDesktopHealth().probe(session_only=True).get("available") is not True: return
                peer_page = 0
                while self.active():
                    peers = self._peers()
                    peer_page = min(peer_page, max(0, (len(peers) - 1) // 6))
                    current = peers[peer_page * 6:peer_page * 6 + 6]
                    choices = [(p, "设备 …" + p[0][-12:] + "\n配对指纹 …" + p[1][-12:]) for p in current]
                    if peer_page: choices.append(("newer", "上一页设备"))
                    if (peer_page + 1) * 6 < len(peers): choices.append(("older", "下一页设备"))
                    peer = self._dialog("这台电脑派给手机的工作", "选择手机上对应的配对身份，接下来只看这台电脑上的原派单。这里不检查手机在线，也不能接管手机。"
                                        if current else "本机没有可选的配对设备。这里不能新建工作或接管手机。", choices)
                    if peer is None: return
                    if peer in ("older", "newer"): peer_page += 1 if peer == "older" else -1; continue
                    pages = [None]
                    while self._peer_current(peer):
                        rows, older = self._page(peer, pages[-1])
                        choices = [(row, _STATE.get(row[4], "原派单") + " · " + _stamp(row[3]) + "\n操作 …" + row[1][-16:]) for row in rows]
                        if len(pages) > 1: choices.append(("newer", "较新派单"))
                        if older is not None: choices.append(("older", "查看较早的原记录"))
                        choices.append(("back", "返回设备"))
                        row = self._dialog("选择一笔原派单" if rows else "这批记录没有给该设备的手机动作任务",
                                           "这里是电脑记录的状态。点选一笔才向同一手机回读；不会运行、恢复或重试任务。", choices,
                                           lambda: self._peer_current(peer))
                        if row is None: return
                        if row == "back": break
                        if row == "older": pages.append(older); continue
                        if row == "newer": pages.pop(); continue
                        self._read(peer, row)
            except Exception:
                if self.active():
                    try: self._dialog("本次原回执窗口已结束", "原设备、桌面、读取授权或任务状态已变化；没有重放任何操作。", [])
                    except Exception: pass
            finally: self.presenting.release()
        threading.Thread(target=show, name="owner-phone-receipt-window", daemon=True).start()


def open_local_entry(manager):
    from desktop_receipt_icon import LocalReceiptIcon
    view = ResultView(manager)
    return LocalReceiptIcon(view).start()
