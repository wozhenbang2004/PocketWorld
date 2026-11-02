"""Local-only recovery confirmation, never a JSON/remote 'ownerConfirmed' flag.

Only this temporary foreground dialog's challenge keys are considered; no other
window's input is recorded. LLKHF_INJECTED rejects ordinary SendInput/Cua input.
This is NOT a security boundary against arbitrary malicious same-user native
code with access to process memory/DPAPI/state. Command authorization is not an
OS sandbox, and the dialog says so. No credentials or new signing keys are used.

API references: learn.microsoft.com/en-us/windows/win32/winmsg/lowlevelkeyboardproc
and /windows/win32/api/winuser/ns-winuser-kbdllhookstruct.
"""
from dataclasses import dataclass
import ctypes
import hashlib
import os
import secrets
import time

from platform_backend import PlatformError

_LOCAL_UI = object()


@dataclass(frozen=True)
class PhysicalOwnerConfirmation:
    _seal: object
    confirmed_at: int
    challenge_digest: str
    event_count: int
    purpose: str = "desktop_recovery"
    summary_digest: str = ""


def confirmation_record(proof, *, purpose="desktop_recovery", summary=None):
    if not isinstance(proof, PhysicalOwnerConfirmation) or proof._seal is not _LOCAL_UI or proof.purpose != purpose:
        raise PlatformError("Real local interactive recovery confirmation required", 403)
    if summary is not None and proof.summary_digest != hashlib.sha256(summary.encode("utf-8")).hexdigest():
        raise PlatformError("Local confirmation belongs to a different exact reviewed action", 403)
    return {"kind": "foreground_noninjected_key_challenge", "confirmedAt": proof.confirmed_at,
            "challengeSha256": proof.challenge_digest, "nonInjectedChallengeEvents": proof.event_count,
            "sameUserArbitraryCodeIsolationVerified": False}


def confirm_local_recovery(summary, *, manual_attestation=False):
    warning = ("系统终止证据不可用。你正在亲自声明：已退出旧桥接并注销/重启，旧执行者已停。此声明不会写成OS verified。\r\n" if manual_attestation else "已取得旧host与原Job终止证据；仍需本人决定释放精确旧lease。\r\n")
    return _confirm(summary, purpose="desktop_recovery", title="PocketWorld · 本机恢复确认（不会重放旧操作）", button="确认恢复资源（不重放）",
                    warning=warning + "旧操作仍可能已经发生，保持unknown，不会重放。新任务必须重新观察和取得新lease。\r\n")


def confirm_local_pairing(summary):
    return _confirm(summary, purpose="device_pairing", title="PocketWorld · 核对两端指纹和本机授权", button="确认此设备和准确授权",
                    warning="请在手机和本机逐一核对下面两个完整指纹。只允许下列准确能力、资源、额度和期限。\r\n候选设备尚无业务权限；完成后仍需独立双向TLS核验。\r\n")


def confirm_local_pairing_recovery(summary):
    return _confirm(summary, purpose="device_pairing_recovery", title="PocketWorld · 仅恢复原已提交配对回执", button="开放30秒原回执只读通道",
                    warning="此操作仅让原已核验的同一设备取回原不可变回执，不重新配对、续期、加额度或启动业务。\r\n撤销、换代、scope变更或到期后不能借恢复重新授权。\r\n")


def _confirm(summary, *, purpose, title, button, warning):
    if os.name != "nt":
        raise PlatformError("Local owner recovery confirmation requires a real Windows desktop")
    if not isinstance(summary, str) or not summary or len(summary) > 16000:
        raise PlatformError("Exact bounded old-lease summary is required", 400)
    from ctypes import wintypes as w
    user, kernel = ctypes.WinDLL("user32", use_last_error=True), ctypes.WinDLL("kernel32", use_last_error=True)
    wndproc = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    hookproc = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, w.WPARAM, w.LPARAM)
    class WindowClass(ctypes.Structure):
        _fields_ = [("style", w.UINT), ("procedure", wndproc), ("classExtra", ctypes.c_int), ("windowExtra", ctypes.c_int),
                    ("instance", w.HINSTANCE), ("icon", w.HICON), ("cursor", w.HANDLE), ("background", w.HBRUSH),
                    ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]
    class Keyboard(ctypes.Structure):
        _fields_ = [("vk", w.DWORD), ("scan", w.DWORD), ("flags", w.DWORD), ("time", w.DWORD), ("extra", ctypes.c_size_t)]
    signatures = {
        "DefWindowProcW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
        "RegisterClassW": ([ctypes.POINTER(WindowClass)], w.WORD),
        "CreateWindowExW": ([w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p], w.HWND),
        "GetForegroundWindow": ([], w.HWND), "SetForegroundWindow": ([w.HWND], w.BOOL), "ShowWindow": ([w.HWND, ctypes.c_int], w.BOOL),
        "DestroyWindow": ([w.HWND], w.BOOL), "PostQuitMessage": ([ctypes.c_int], None),
        "GetWindowTextW": ([w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int), "GetWindowTextLengthW": ([w.HWND], ctypes.c_int),
        "SetWindowTextW": ([w.HWND, w.LPCWSTR], w.BOOL), "SetFocus": ([w.HWND], w.HWND),
        "SendMessageW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
        "GetMessageW": ([ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], ctypes.c_int),
        "TranslateMessage": ([ctypes.POINTER(w.MSG)], w.BOOL), "DispatchMessageW": ([ctypes.POINTER(w.MSG)], ctypes.c_ssize_t),
        "SetWindowsHookExW": ([ctypes.c_int, hookproc, w.HINSTANCE, w.DWORD], w.HANDLE),
        "UnhookWindowsHookEx": ([w.HANDLE], w.BOOL), "CallNextHookEx": ([w.HANDLE, ctypes.c_int, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
    }
    for name, (args, result) in signatures.items():
        getattr(user, name).argtypes, getattr(user, name).restype = args, result
    kernel.GetModuleHandleW.argtypes, kernel.GetModuleHandleW.restype = [w.LPCWSTR], w.HINSTANCE
    challenge = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    sequence, events, confirmed = "", 0, False
    window = edit = status = hook = None
    @hookproc
    def keyboard(code, kind, pointer):
        nonlocal sequence, events
        if code >= 0 and kind == 0x100 and window and user.GetForegroundWindow() == window:
            key = ctypes.cast(pointer, ctypes.POINTER(Keyboard)).contents
            if not key.flags & (0x10 | 0x2):
                if key.vk == 8:
                    sequence = sequence[:-1]
                elif 0x30 <= key.vk <= 0x39 or 0x41 <= key.vk <= 0x5A:
                    sequence += chr(key.vk)
                    events += 1
                    sequence = sequence[-32:]
        return user.CallNextHookEx(hook, code, kind, pointer)
    @wndproc
    def procedure(hwnd, message, wparam, lparam):
        nonlocal confirmed, sequence
        if message == 0x111 and (wparam & 0xFFFF) == 1001 and ((wparam >> 16) & 0xFFFF) == 0x300 and edit and user.GetWindowTextLengthW(edit) == 0:
            sequence = ""
        if message == 0x111 and (wparam & 0xFFFF) == 1002:
            text = ctypes.create_unicode_buffer(64)
            user.GetWindowTextW(edit, text, len(text))
            if sequence == challenge and text.value.upper() == challenge and user.GetForegroundWindow() == hwnd:
                confirmed = True; user.DestroyWindow(hwnd)
            else:
                user.SetWindowTextW(status, "未确认：请在本窗口亲自输入完整挑战码；远端输入、粘贴和自动点击不能代替确认。")
            return 0
        if message == 2:
            user.PostQuitMessage(0); return 0
        return user.DefWindowProcW(hwnd, message, wparam, lparam)
    instance = kernel.GetModuleHandleW(None)
    class_name = "PocketWorldLocalRecovery-" + secrets.token_hex(8)
    klass = WindowClass(0, procedure, 0, 0, instance, None, None, w.HBRUSH(6), None, class_name)
    if not user.RegisterClassW(ctypes.byref(klass)):
        raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, class_name, title, 0x00CF0000, 40, 40, 900, 690, None, None, instance, None)
    if not window:
        raise ctypes.WinError(ctypes.get_last_error())
    message = warning + "同用户任意代码执行不等于OS沙箱隔离；请先停掉不可信命令。\r\n\r\n" + summary
    user.CreateWindowExW(0x200, "EDIT", message, 0x40000000 | 0x10000000 | 0x00200000 | 0x4 | 0x800, 16, 16, 850, 450, window, None, instance, None)
    user.CreateWindowExW(0, "STATIC", "请在本窗口亲自键入：" + challenge, 0x40000000 | 0x10000000, 16, 480, 850, 30, window, None, instance, None)
    edit = user.CreateWindowExW(0x200, "EDIT", "", 0x40000000 | 0x10000000 | 0x10000, 16, 514, 260, 32, window, w.HMENU(1001), instance, None)
    user.SendMessageW(edit, 0xC5, 8, 0)  # EM_LIMITTEXT
    user.CreateWindowExW(0, "BUTTON", button, 0x40000000 | 0x10000000 | 0x10000, 300, 514, 280, 34, window, w.HMENU(1002), instance, None)
    status = user.CreateWindowExW(0, "STATIC", "关闭窗口即取消。", 0x40000000 | 0x10000000, 16, 565, 850, 60, window, None, instance, None)
    try:
        hook = user.SetWindowsHookExW(13, keyboard, instance, 0)
        if not hook:
            raise PlatformError("Native non-injected owner confirmation is unavailable; no fallback auto-confirm")
        user.ShowWindow(window, 5); user.SetForegroundWindow(window); user.SetFocus(edit)
        message = w.MSG()
        while True:
            result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
            if result == 0:
                break
            if result < 0:
                raise ctypes.WinError(ctypes.get_last_error())
            user.TranslateMessage(ctypes.byref(message)); user.DispatchMessageW(ctypes.byref(message))
    finally:
        if hook:
            user.UnhookWindowsHookEx(hook)
        if not confirmed:
            user.DestroyWindow(window)
    if not confirmed:
        raise PlatformError("Local owner canceled confirmation; no new authority granted", 409)
    return PhysicalOwnerConfirmation(_LOCAL_UI, int(time.time() * 1000), hashlib.sha256(challenge.encode()).hexdigest(), events,
                                     purpose, hashlib.sha256(summary.encode("utf-8")).hexdigest())
