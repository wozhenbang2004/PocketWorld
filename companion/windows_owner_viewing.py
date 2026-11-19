"""What THIS running Windows Companion can prove to its local owner. No signalling, capture, cleanup or input."""
from contextlib import contextmanager, ExitStack
from dataclasses import dataclass
from datetime import datetime
import hashlib
import time

from desktop_runtime import DesktopRuntime
from desktop_sessions import WindowsDesktopHealth
from desktop_stream import TERMINAL_STATES
from device_pairing import Permission
from device_protocol import canonical_bytes, resource_equal


@dataclass(frozen=True)
class Row:
    key: tuple              # local guard only; never show or hand out a viewer/stream/lease handle
    peer: str
    scope: str
    media: str
    control: str


@dataclass(frozen=True)
class Seen:
    state: str
    rows: tuple = ()
    uncertain: bool = False


class Unclear(Exception): pass


def require(ok):
    if not ok: raise Unclear()


@contextmanager
def briefly(viewers):
    # Never wait on a connecting peer or stall the media loop for a UI read.
    with ExitStack() as held:
        for lock in (viewers.lock, viewers.streams.lock, viewers.manager.lock):
            require(lock.acquire(blocking=False)); held.callback(lock.release)
        yield


def runtime(desk):
    r = desk.receiver.desktop
    return r.viewers if (desk.active() and not desk.receiver.closed and desk.service.receiver is desk.receiver
                         and isinstance(r, DesktopRuntime) and r.jobs.manager is desk.manager and r.viewers.manager is desk.manager) else None


def still_on(owner_session):
    live = WindowsDesktopHealth().probe(session_only=True)
    return type(owner_session) is int and owner_session > 0 and live.get("available") is True and live.get("sessionId") == owner_session


def key(desk, v, s, owner_session, now):
    permission, audience = s.permission, s.audience
    require(isinstance(permission, Permission) and permission._owner is desk.receiver.pairing)
    e = permission.envelope
    binding = s.binding; identity = desk.receiver.identity
    require(not s.closed and s.expires > now and not v.closed and not v.streams.closed and not desk.manager.closed
            and v.adapter.instance_id == desk.manager.instance_id and v.adapter.source_device_id == identity.device_id
            and e.method == "desktop.viewer" and e.arguments["action"] == "configuration" and e.arguments["viewerSessionId"] == s.id
            and e.get("originDeviceId") == e.get("callerDeviceId") and not e.get("delegationChain", [])
            and e.get("targetDeviceId") == identity.device_id and e.get("instanceId") == identity.instance_id
            and audience == v._audience(e, permission) and s.owner == "viewer:" + hashlib.sha256(canonical_bytes(audience)).hexdigest()
            and resource_equal(binding, audience["resourceBinding"]) and binding.get("kind") == "desktop"
            and binding.get("sessionId") == owner_session and binding.get("displayId") == "primary")
    permission.check_current(now)  # original receiving authority, no spending, networking or ledger writes
    current_grant = desk.receiver.trust.grant(e.get("grantId"))
    require(current_grant is not None and hashlib.sha256(canonical_bytes(current_grant)).hexdigest() == permission.grant_fingerprint)
    return s.id, s.owner, e.get("callerDeviceId"), permission.grant_fingerprint, v.adapter.instance_id


def state_of_media(v, s, now):
    if s.stream_id is None: return "仅建立观看会话，尚无已协商的媒体连接。"
    m = v.streams.sessions.get(s.stream_id)
    require(m is not None and m.id == s.stream_id and m.owner == s.owner and resource_equal(m.binding, s.binding)
            and m.identity == s.identity and m.target == s.target and m.expires_at > now and m.state not in TERMINAL_STATES)
    pc = m.pc
    if pc is None: return "媒体正在准备，未核实已连接。"
    connection = pc.connectionState  # current in-memory aiortc state, not copied SDP, ICE addresses or a status request
    if connection == "connected" and m.state == "connected" and m.track is not None and m.track.readyState == "live":
        # A local post-RTP frame record is not receipt/display by a person. Never return its usable frame reference.
        frame = m.last_frame
        recent = bool(isinstance(frame, dict) and frame.get("streamId") == m.id
                      and type(frame.get("capturedAt")) is int and type(frame.get("expiresAt")) is int
                      and m.created_at <= frame["capturedAt"] <= now < frame["expiresAt"] <= m.expires_at
                      and (m.frames.get(frame.get("frameId")) or {}).get("reference") == frame)
        require(m.pc is pc and pc.connectionState == "connected" and m.state == "connected" and not s.closed)
        return "本机媒体连接已建立；有最近一帧的本机发送记录。" if recent else "本机媒体连接已建立；没有核实最近发送帧。"
    if connection in {"new", "connecting"} and m.state in {"negotiating", "connecting"}: return "正在协商媒体，尚未核实已连接。"
    return "本机媒体连接状态待核对；不能据此确认手机仍在观看或已断开。"


def state_of_control(v, s, now):
    held = v._lease_row(s)  # original, read-only lease row. Never call a take/release/stop method to discover state.
    if s.uncertain or s.cleanup_pending or held and held[5] == "NEEDS_RECONCILE":
        return "有输入或租约待核对，不推断操作已生效。"
    if held and held[0] == s.owner and held[1] == v.adapter.instance_id and held[4] > now and s.lease == {
            "id":held[2], "fence":held[3], "expiresAt":held[4], "resource":s.resource}:
        if held[5] == "HELD": return "这一个观看会话持有人工输入权；不代表正在操作。"
        if held[5] == "EXECUTING": return "同一人工输入租约有操作正在处理；结果未核实。"
    return "未核实这一个观看会话持有人工输入权；这不是撤销配对。"


def observe(desk, v, owner_session):
    if v is None: return Seen("not_attached")
    try:
        require(runtime(desk) is v and still_on(owner_session))
        with briefly(v):
            require(not v.closed and not v.streams.closed and not v.adapter.paused and not v.adapter.stop_marker.exists()
                    and len(v.sessions) <= 32 and len(v.streams.sessions) <= 32)
            now = v.adapter.clock(); rows = []; matched = set(); uncertain = False
            for name, s in v.sessions.items():
                if s.closed or s.expires <= now: continue
                try:
                    require(s.id == name)
                    ref = key(desk, v, s, owner_session, now)
                    media = state_of_media(v, s, now)
                    control = state_of_control(v, s, now)
                    require(not s.closed and s.expires > v.adapter.clock())
                    rows.append(Row(ref, ref[2], "原授权单个窗口" if "windowId" in s.binding else "原授权整个主屏", media, control))
                    if s.stream_id: matched.add(s.stream_id)
                except Exception: uncertain = True  # do not disclose identity from an expired/inconsistent record
            # Include unmatched media or unsettled transport cleanup as UNKNOWN, never as "nobody is viewing".
            uncertain |= any(m.id not in matched and (m.state not in TERMINAL_STATES or not m.transport_closed)
                             for m in v.streams.sessions.values())
            require(len(rows) <= 2 and runtime(desk) is v)
            return Seen("observed", tuple(rows), uncertain)
    except Exception: return Seen("unknown")


def show(desk):
    owner_session = WindowsDesktopHealth().probe(session_only=True)
    if owner_session.get("available") is not True: return
    sid = owner_session["sessionId"]; v = runtime(desk)
    while desk.active() and still_on(sid):
        seen = observe(desk, v, sid)
        at = datetime.now().astimezone().strftime("%H:%M:%S %z"); started = time.monotonic()
        lines = ["本机读取于 " + at + "。仅指这次伴侣，不检查其他软件。"]
        if seen.state == "not_attached": lines.append("本次服务没有接入可核对的本机桌面模块；已有配对不等于能观看。")
        elif seen.state == "unknown": lines.append("本机桌面未就绪、正在处理操作、已暂停或状态改变；本次无法核实连接。")
        else:
            if not seen.rows and not seen.uncertain: lines.append("这次伴侣没有登记中的有效观看会话。")
            for i, row in enumerate(seen.rows):
                lines.append(f"\n观看会话 {i+1} · 设备 …{row.peer[-12:]} · {row.scope}\n{row.media}\n{row.control}")
            if seen.uncertain: lines.append("\n另有会话或传输收尾未核实，不能断定已全部停止。")
        lines.append("\n本机没有手机已显示或有人在看的证明。这里只读；要暂停，请从原电脑工作台另行确认。")
        def present():
            if not still_on(sid) or runtime(desk) is not v or time.monotonic()-started >= 45: return False
            if not seen.rows: return True
            # Retire device details when its original authorization/session ends. A live media read is manual only.
            try:
                with briefly(v):
                    if v.closed or v.streams.closed or v.adapter.paused or v.adapter.stop_marker.exists(): return False
                    now = v.adapter.clock()
                    return all(key(desk, v, v.sessions[r.key[0]], sid, now) == r.key for r in seen.rows)
            except Exception: return False
        actions = [("refresh", "重新读取本机状态")]
        if seen.rows: actions.append(("identities", "核对以上会话的完整设备标识"))
        selected = desk._dialog("这台 Windows 的桌面连接", "\n".join(lines), actions, present)
        if selected == "identities" and present():
            details = "\n\n".join(f"观看会话 {i+1} · {row.scope}\n设备：{row.peer}" for i, row in enumerate(seen.rows))
            if desk._dialog("这次本机核对的原设备", details, [("back", "返回并重新读取连接状态")], present) == "back": continue
        if selected != "refresh": return
