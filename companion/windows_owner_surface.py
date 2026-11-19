"""Lay out an already-created, hidden local owner window on its own monitor. No screen or application capture."""
import ctypes as c
import os
from ctypes import wintypes as w
from platform_backend import PlatformError

OWNER_STYLE = 0x00CA0000  # caption, system menu, minimize; user resizing cannot clip a review
USE_DEFAULT = -2147483648


class MonitorInfo(c.Structure):
    _fields_ = [("cbSize", w.DWORD), ("bounds", w.RECT), ("work", w.RECT), ("flags", w.DWORD)]


class LogFont(c.Structure):
    _fields_ = [(n, w.LONG) for n in ("height", "width", "escapement", "orientation", "weight")] + [
        (n, w.BYTE) for n in ("italic", "underline", "strike", "charset", "outPrecision",
                             "clipPrecision", "quality", "pitch")] + [("face", w.WCHAR * 32)]


class Metrics(c.Structure):
    _fields_ = [("cbSize", w.UINT)] + [(n, c.c_int) for n in (
        "border", "scrollW", "scrollH", "captionW", "captionH")] + [
        ("caption", LogFont), ("smallW", c.c_int), ("smallH", c.c_int), ("small", LogFont),
        ("menuW", c.c_int), ("menuH", c.c_int), ("menu", LogFont), ("status", LogFont),
        ("message", LogFont), ("paddedBorder", c.c_int)]


def api(dll, name, args, out):
    f = getattr(dll, name)
    f.argtypes, f.restype = args, out
    return f


def rectangle(rect):
    return rect.left, rect.top, rect.right, rect.bottom


def anchor_origin(user, hwnd):
    """Put the separate physical confirmation on the original pairing display, not at primary-screen (40,40)."""
    if hwnd is None: return USE_DEFAULT, 0
    tid = api(user, "GetWindowThreadProcessId", (w.HWND, c.POINTER(w.DWORD)), w.DWORD)
    rect_for = api(user, "GetWindowRect", (w.HWND, c.POINTER(w.RECT)), w.BOOL)
    pid, box = w.DWORD(), w.RECT()
    if not tid(hwnd, c.byref(pid)) or pid.value != os.getpid() or not rect_for(hwnd, c.byref(box)):
        raise PlatformError("原本机配对窗口不再可核对。")
    return (box.left+box.right)//2 - 100, (box.top+box.bottom)//2 - 50


def columns(width, count, margin=16, gap=10):
    """Logical positions share the available row exactly; no overlapping footer buttons at 720 DIPs."""
    content = width - 2 * margin - (count - 1) * gap
    return [(margin + i * gap + i * content // count, (i + 1) * content // count - i * content // count)
            for i in range(count)]


class OwnerSurface:
    def __init__(self, user, hwnd, style, desired, minimum):
        self.user, self.hwnd, self.font = user, hwnd, None
        self.get_monitor = api(user, "MonitorFromWindow", (w.HWND, w.DWORD), w.HANDLE)
        self.get_info = api(user, "GetMonitorInfoW", (w.HANDLE, c.POINTER(MonitorInfo)), w.BOOL)
        self.get_window = api(user, "GetWindowRect", (w.HWND, c.POINTER(w.RECT)), w.BOOL)
        self.get_client = api(user, "GetClientRect", (w.HWND, c.POINTER(w.RECT)), w.BOOL)
        self.is_visible = api(user, "IsWindowVisible", (w.HWND,), w.BOOL)
        self.is_iconic = api(user, "IsIconic", (w.HWND,), w.BOOL)
        self.get_dpi = api(user, "GetDpiForWindow", (w.HWND,), w.UINT)
        frame_for = api(user, "AdjustWindowRectExForDpi", (c.POINTER(w.RECT), w.DWORD, w.BOOL, w.DWORD, w.UINT), w.BOOL)
        move = api(user, "SetWindowPos", (w.HWND, w.HWND, c.c_int, c.c_int, c.c_int, c.c_int, w.UINT), w.BOOL)
        metrics_for = api(user, "SystemParametersInfoForDpi", (w.UINT, w.UINT, c.c_void_p, w.UINT, w.UINT), w.BOOL)
        self.gdi = c.WinDLL("gdi32", use_last_error=True)
        make_font = api(self.gdi, "CreateFontIndirectW", (c.POINTER(LogFont),), w.HANDLE)
        self.drop_font = api(self.gdi, "DeleteObject", (w.HANDLE,), w.BOOL)
        self.monitor, (left, top, right, bottom) = self.work()
        self.dpi = self.get_dpi(hwnd)
        metrics = Metrics(); metrics.cbSize = c.sizeof(metrics)
        if not 72 <= self.dpi <= 960 or not metrics_for(0x29, metrics.cbSize, c.byref(metrics), 0, self.dpi):
            raise PlatformError("无法核对本机显示比例或系统文字大小，未显示不完整的授权窗口。")
        # Respect the owner's message font, including large text. Do not squeeze down font or controls to pass.
        glyph = abs(metrics.message.height)
        if not glyph: raise PlatformError("本机正文大小不可核对。")
        self.scale = max(self.dpi, (glyph * 96 + 17) // 18)
        frame = w.RECT()
        if not frame_for(c.byref(frame), style, False, 0, self.dpi): raise c.WinError(c.get_last_error())
        non_w, non_h = frame.right - frame.left, frame.bottom - frame.top
        pad = self.px(6)
        self.width, self.height = (min(want, (avail - border - 2 * pad) * 96 // self.scale)
                                   for want, avail, border in zip(desired, (right-left, bottom-top), (non_w, non_h)))
        if self.width < minimum[0] or self.height < minimum[1]:
            raise PlatformError("这块屏幕的可用空间放不下可读的完整配对与核对操作。请在更大的可用桌面重开；没有缩小文字或代为确认。")
        self.client = self.px(self.width), self.px(self.height)
        outer_w, outer_h = self.client[0] + non_w, self.client[1] + non_h
        if not move(hwnd, None, left + (right-left-outer_w)//2, top + (bottom-top-outer_h)//2,
                    outer_w, outer_h, 0x4 | 0x10 | 0x200): raise c.WinError(c.get_last_error())
        if not self.fits(): raise PlatformError("实际本机窗口与核对的可用区域不符，未打开裁剪的界面。")
        self.font = make_font(c.byref(metrics.message))
        if not self.font: raise c.WinError(c.get_last_error())

    def px(self, dip):
        return (dip * self.scale + 48) // 96

    def work(self):
        monitor = self.get_monitor(self.hwnd, 0)  # no default substitution if the window is off-screen
        info = MonitorInfo(); info.cbSize = c.sizeof(info)
        if not monitor or not self.get_info(monitor, c.byref(info)):
            raise PlatformError("未能核对配对窗口所在显示器的实际工作区。")
        return monitor, rectangle(info.work)

    def fits(self, *, showing=False):
        try:
            monitor, (left, top, right, bottom) = self.work()
            frame, client = w.RECT(), w.RECT()
            return ((not showing or (self.is_visible(self.hwnd) and not self.is_iconic(self.hwnd)))
                    and monitor == self.monitor and self.get_dpi(self.hwnd) == self.dpi
                    and self.get_window(self.hwnd, c.byref(frame)) and self.get_client(self.hwnd, c.byref(client))
                    and left <= frame.left < frame.right <= right and top <= frame.top < frame.bottom <= bottom
                    and rectangle(client) == (0, 0, *self.client))
        except (OSError, PlatformError): return False

    def child(self, instance, kind, value, x, y, width, height, ident=0, extra=0, extended=0):
        if min(x, y) < 0 or min(width, height) < 1 or x+width > self.width or y+height > self.height:
            raise PlatformError("必要的本机配对控件会被裁剪，未显示。")
        left, top = self.px(x), self.px(y)
        hwnd = self.user.CreateWindowExW(extended, kind, value, 0x50000000 | extra,
                left, top, self.px(x+width)-left, self.px(y+height)-top, self.hwnd,
                w.HMENU(ident) if ident else None, instance, None)
        if not hwnd: raise c.WinError(c.get_last_error())
        self.user.SendMessageW(hwnd, 0x30, self.font, 0)  # WM_SETFONT; no interaction with any other application
        return hwnd

    def close(self):
        if self.font: self.drop_font(self.font); self.font = None
