"""One transient native Windows text draft. No files, clipboard API, network or dispatch."""
from __future__ import annotations
import ctypes as c
from ctypes import wintypes as w
import struct


def ask_goal(current):
    return _ask(current, lookup=False)


def ask_new_source_path(current):
    return _ask(current,lookup=True,project_path="new-source")


def ask_original_operation(current):
    return _ask(current, lookup=True)


def source_text(current, title, hint, initial, *, review=False):
    """An owner-local source draft or a read-only full diff, never a phone goal."""
    return _ask(current, lookup=False, source=(title,hint,initial,review))


def ask_committed_path(current):
    return _ask(current,lookup=True,project_path=True)


def _ask(current, *, lookup, source=None, project_path=False):
    # Standard dialog template: scale in dialog units, native tab order, IME,
    # multiline edit, Escape and system-close. IDOK only returns a LOCAL draft.
    def string(text): return (text + "\0").encode("utf-16le")
    width,height = (365,192) if source else (292,82 if lookup else 147)
    title = source[0] if source else "原提交中的相对文件路径" if project_path else ("找回这台电脑接过的原操作" if lookup else "交给手机 · 写下这次目标")
    if project_path=="new-source": title="新源码拟用的相对路径"
    template = bytearray(struct.pack("<IIHhhhh", 0x80C808C0, 0, 4, 0, 0, width,height))
    template.extend(b"\0\0\0\0" + string(title) + struct.pack("<H", 9) + string("Segoe UI"))
    def item(style, x, y, width, height, ident, atom, title):
        template.extend(b"\0" * (-len(template) % 4))
        template.extend(struct.pack("<IIhhhhHHH", style | 0x50000000, 0, x, y, width, height, ident, 0xffff, atom))
        template.extend(string(title) + b"\0\0")
    hint = "在手机「原派发」里查看完整的原操作编号。只查这台电脑，不联系手机。" if lookup else "只发送这里的文字，不附带电脑文件、项目或历史。"
    if project_path: hint="填写从项目根开始的确切文件路径，如 src/Main.kt。不会按文件名搜索或打开当前工作树。"
    if project_path=="new-source": hint="在原目录下为一个新文件取名，如 src/NewFeature.kt。只核对是否可提议，不会创建该项目文件。"
    review = bool(source and source[3])
    item(0, 9, 7, width-19, 24 if source else 19, 100, 0x82, source[1] if source else hint)
    control_style = (0x003010c4 | (0x800 if review else 0)) if source else (0x80 if lookup else 0x00201044)
    item(0x00800000 | 0x10000 | control_style, 9, 33 if source else 27, width-19, 130 if source else 17 if lookup else 86, 101, 0x81, "")
    buttons_y = height-26 if not lookup else 54
    item(0x10001, width-137, buttons_y, 73, 16, 1, 0x80, "返回核对" if review else "预览差异" if source else "核对路径" if project_path else "找原回执" if lookup else "核对草稿")
    item(0x10000, width-60, buttons_y, 51, 16, 2, 0x80, "取消")
    user, kernel = c.WinDLL("user32", use_last_error=True), c.WinDLL("kernel32")
    proc = c.WINFUNCTYPE(c.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    def signature(lib, name, args, result):
        fn = getattr(lib, name); fn.argtypes, fn.restype = args, result
        return fn
    display = signature(user, "DialogBoxIndirectParamW", [w.HINSTANCE, c.c_void_p, w.HWND, proc, w.LPARAM], c.c_ssize_t)
    control = signature(user, "GetDlgItem", [w.HWND, c.c_int], w.HWND)
    write = signature(user, "SetDlgItemTextW", [w.HWND, c.c_int, w.LPCWSTR], w.BOOL)
    read = signature(user, "GetDlgItemTextW", [w.HWND, c.c_int, w.LPWSTR, c.c_int], w.UINT)
    send = signature(user, "SendMessageW", [w.HWND, w.UINT, w.WPARAM, w.LPARAM], c.c_ssize_t)
    finish = signature(user, "EndDialog", [w.HWND, c.c_ssize_t], w.BOOL)
    timer = signature(user, "SetTimer", [w.HWND, c.c_size_t, w.UINT, c.c_void_p], c.c_size_t)
    focus = signature(user, "SetFocus", [w.HWND], w.HWND)
    instance = signature(kernel, "GetModuleHandleW", [w.LPCWSTR], w.HINSTANCE)(None)
    value, overflow = None, False
    @proc
    def handle(hwnd, message, wp, lp):
        nonlocal value, overflow
        try:
            if message == 0x110:  # WM_INITDIALOG
                edit = control(hwnd, 101); send(edit, 0xc5, 262144 if source else 2000, 0)
                if source:
                    units = len(source[2].encode("utf-16le"))//2
                    if not current() or units>262144 or not write(hwnd,101,source[2]) or send(edit,0x0e,0,0)!=units:
                        finish(hwnd,2); return 0
                    send(edit,0xb1,0,0)  # caret at start, without selecting or copying text
                focus(edit)
                if not timer(hwnd, 1, 700, None): finish(hwnd, 2)
                return 0
            if message == 0x10 or message == 0x113 and not current():
                finish(hwnd, 2); return 1
            if (source or project_path) and message==0x111 and wp&0xffff==101 and (wp>>16)&0xffff==0x501:  # EN_MAXTEXT
                overflow=True
                write(hwnd,100,"输入超出上限；请取消后重新填写确切路径。" if project_path else "输入超过编辑框上限，不能把截断文字保存为补丁。请取消后重新起草。")
                return 1
            if message == 0x111 and wp & 0xffff in (1, 2):
                if wp & 0xffff == 1 and current():
                    if source:
                        if overflow: return 1
                        if review: value=""
                        else:
                            units=send(control(hwnd,101),0x0e,0,0)
                            if not 0<=units<=65536:
                                write(hwnd,100,"草稿过长，尚未写入。请缩短后再预览。"); return 1
                            text=c.create_unicode_buffer(units+1)
                            if read(hwnd,101,text,len(text))!=units: finish(hwnd,2); return 1
                            value=text.value  # Empty file, whitespace and final newline are meaningful.
                    else:
                        if project_path and overflow: return 1
                        text=c.create_unicode_buffer(2001); count=read(hwnd,101,text,len(text))
                        if not text.value.strip(): return 1
                        if project_path and (count!=send(control(hwnd,101),0x0e,0,0) or text.value!=text.value.strip()):
                            write(hwnd,100,"请填写完整的确切相对路径，不要有首尾空白。"); return 1
                        value=text.value if project_path else text.value.strip()
                finish(hwnd, 1 if value is not None else 2); return 1
        except Exception:
            value = None; finish(hwnd, 2); return 1
        return 0
    storage = c.create_string_buffer(bytes(template))
    return value if display(instance, c.addressof(storage), None, handle, 0) == 1 and current() else None
