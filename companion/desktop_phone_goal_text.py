"""One transient native Windows text draft. No files, clipboard API, network or dispatch."""
from __future__ import annotations
import ctypes as c
from ctypes import wintypes as w
import struct


def ask_goal(current):
    # Standard dialog template: scale in dialog units, native tab order, IME,
    # multiline edit, Escape and system-close. IDOK only returns a LOCAL draft.
    def string(text): return (text + "\0").encode("utf-16le")
    template = bytearray(struct.pack("<IIHhhhh", 0x80C808C0, 0, 4, 0, 0, 292, 147))
    template.extend(b"\0\0\0\0" + string("交给手机 · 写下这次目标") + struct.pack("<H", 9) + string("Segoe UI"))
    def item(style, x, y, width, height, ident, atom, title):
        template.extend(b"\0" * (-len(template) % 4))
        template.extend(struct.pack("<IIhhhhHHH", style | 0x50000000, 0, x, y, width, height, ident, 0xffff, atom))
        template.extend(string(title) + b"\0\0")
    item(0, 9, 7, 273, 19, 100, 0x82, "只发送这里的文字，不附带电脑文件、项目或历史。")
    item(0x00800000 | 0x00200000 | 0x10000 | 0x1044, 9, 27, 273, 86, 101, 0x81, "")
    item(0x10001, 155, 121, 73, 16, 1, 0x80, "核对草稿")
    item(0x10000, 232, 121, 51, 16, 2, 0x80, "取消")
    user, kernel = c.WinDLL("user32", use_last_error=True), c.WinDLL("kernel32")
    proc = c.WINFUNCTYPE(c.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    def signature(lib, name, args, result):
        fn = getattr(lib, name); fn.argtypes, fn.restype = args, result
        return fn
    display = signature(user, "DialogBoxIndirectParamW", [w.HINSTANCE, c.c_void_p, w.HWND, proc, w.LPARAM], c.c_ssize_t)
    control = signature(user, "GetDlgItem", [w.HWND, c.c_int], w.HWND)
    read = signature(user, "GetDlgItemTextW", [w.HWND, c.c_int, w.LPWSTR, c.c_int], w.UINT)
    send = signature(user, "SendMessageW", [w.HWND, w.UINT, w.WPARAM, w.LPARAM], c.c_ssize_t)
    finish = signature(user, "EndDialog", [w.HWND, c.c_ssize_t], w.BOOL)
    timer = signature(user, "SetTimer", [w.HWND, c.c_size_t, w.UINT, c.c_void_p], c.c_size_t)
    focus = signature(user, "SetFocus", [w.HWND], w.HWND)
    instance = signature(kernel, "GetModuleHandleW", [w.LPCWSTR], w.HINSTANCE)(None)
    value = None
    @proc
    def handle(hwnd, message, wp, lp):
        nonlocal value
        try:
            if message == 0x110:  # WM_INITDIALOG
                edit = control(hwnd, 101); send(edit, 0xc5, 2000, 0); focus(edit)
                if not timer(hwnd, 1, 700, None): finish(hwnd, 2)
                return 0
            if message == 0x10 or message == 0x113 and not current():
                finish(hwnd, 2); return 1
            if message == 0x111 and wp & 0xffff in (1, 2):
                if wp & 0xffff == 1 and current():
                    text = c.create_unicode_buffer(2001); read(hwnd, 101, text, len(text))
                    if not text.value.strip(): return 1
                    value = text.value.strip()
                finish(hwnd, 1 if value else 2); return 1
        except Exception:
            value = None; finish(hwnd, 2); return 1
        return 0
    storage = c.create_string_buffer(bytes(template))
    return value if display(instance, c.addressof(storage), None, handle, 0) == 1 and current() else None
