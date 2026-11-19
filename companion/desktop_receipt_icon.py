"""Transient Windows notification-area entry, owned only by the opted-in live Companion."""
import ctypes as c
from ctypes import wintypes as w
import sys
import threading
import uuid


class LocalReceiptIcon:
    def __init__(self, view, *, phone_work=False):
        self.view, self.phone_work = view, phone_work
        self.ready, self.installed, self.hwnd, self.post = threading.Event(), False, None, None

    def start(self):
        threading.Thread(target=self._run, name="owner-phone-receipt-icon", daemon=True).start()
        if self.ready.wait(3) and self.installed: return self
        self.close()
        print("Local phone receipt icon unavailable. The original Companion continues without it.", file=sys.stderr)
        return None

    def close(self):
        self.view.end.set()
        if self.hwnd and self.post: self.post(self.hwnd, 0x10, 0, 0)  # WM_CLOSE, this private window only.

    def _run(self):
        try: self._native()
        except Exception: pass
        finally: self.hwnd = None; self.view.end.set(); self.ready.set()

    def _native(self):
        u, s, k = c.WinDLL("user32", use_last_error=True), c.WinDLL("shell32", use_last_error=True), c.WinDLL("kernel32", use_last_error=True)
        proc = c.WINFUNCTYPE(c.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
        class WC(c.Structure):
            _fields_ = [("style", w.UINT), ("proc", proc), ("cx", c.c_int), ("wx", c.c_int), ("instance", w.HINSTANCE),
                        ("icon", w.HICON), ("cursor", w.HANDLE), ("bg", w.HBRUSH), ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]
        class NID(c.Structure):  # NOTIFYICONDATAW on Windows x64, through the Vista hBalloonIcon.
            _fields_ = [("size", w.DWORD), ("hwnd", w.HWND), ("id", w.UINT), ("flags", w.UINT), ("message", w.UINT), ("icon", w.HICON),
                        ("tip", w.WCHAR * 128), ("state", w.DWORD), ("mask", w.DWORD), ("info", w.WCHAR * 256), ("version", w.UINT),
                        ("title", w.WCHAR * 64), ("infoFlags", w.DWORD), ("guid", c.c_ubyte * 16), ("balloon", w.HICON)]
        def signature(lib, name, args, result):
            fn = getattr(lib, name); fn.argtypes, fn.restype = args, result; return fn
        register = signature(u, "RegisterClassW", [c.POINTER(WC)], w.WORD)
        unregister = signature(u, "UnregisterClassW", [w.LPCWSTR, w.HINSTANCE], w.BOOL)
        create = signature(u, "CreateWindowExW", [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, c.c_int, c.c_int, c.c_int, c.c_int,
                                                   w.HWND, w.HMENU, w.HINSTANCE, c.c_void_p], w.HWND)
        destroy = signature(u, "DestroyWindow", [w.HWND], w.BOOL)
        default = signature(u, "DefWindowProcW", [w.HWND, w.UINT, w.WPARAM, w.LPARAM], c.c_ssize_t)
        quit_loop = signature(u, "PostQuitMessage", [c.c_int], None)
        self.post = signature(u, "PostMessageW", [w.HWND, w.UINT, w.WPARAM, w.LPARAM], w.BOOL)
        get = signature(u, "GetMessageW", [c.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], c.c_int)
        dispatch = signature(u, "DispatchMessageW", [c.POINTER(w.MSG)], c.c_ssize_t)
        register_message = signature(u, "RegisterWindowMessageW", [w.LPCWSTR], w.UINT)
        load_icon = signature(u, "LoadIconW", [w.HINSTANCE, c.c_void_p], w.HICON)
        destroy_icon = signature(u, "DestroyIcon", [w.HICON], w.BOOL)
        extract = signature(s, "ExtractIconExW", [w.LPCWSTR, c.c_int, c.POINTER(w.HICON), c.POINTER(w.HICON), w.UINT], w.UINT)
        notify = signature(s, "Shell_NotifyIconW", [w.DWORD, c.POINTER(NID)], w.BOOL)
        instance = signature(k, "GetModuleHandleW", [w.LPCWSTR], w.HINSTANCE)(None)
        # Re-add this explicitly requested local icon if Explorer restarts; it
        # never queries, starts or reconnects anything on the phone.
        shell_created = register_message("TaskbarCreated")
        note, native_icon = NID(), w.HICON()
        def add():
            if not notify(0, c.byref(note)): return False  # NIM_ADD
            if not notify(4, c.byref(note)):  # NIM_SETVERSION, v4 keyboard and mouse semantics.
                notify(2, c.byref(note)); return False
            return True
        @proc
        def handler(hwnd, message, wp, lp):
            if message == 0x8001:
                if self.view.active() and lp & 0xffff in (0x400, 0x401, 0x202, 0x7b): self.view.show()
                return 0
            if shell_created and message == shell_created and note.hwnd and self.view.active():
                add(); return 0
            if message == 0x10:
                destroy(hwnd); return 0
            if message == 2: quit_loop(0); return 0
            return default(hwnd, message, wp, lp)
        name = "PocketWorldPhoneReceipt-" + uuid.uuid4().hex
        wc = WC(0, handler, 0, 0, instance, None, None, None, None, name)
        if not register(c.byref(wc)): return
        try:
            hwnd = create(0, name, "PocketWorld 设备工作回执", 0, 0, 0, 0, 0, None, None, instance, None)
            if not hwnd: return
            self.hwnd = hwnd
            try:
                extract(sys.executable, 0, None, c.byref(native_icon), 1)
                note.size, note.hwnd, note.id, note.flags, note.message = c.sizeof(note), hwnd, 1, 0x01 | 0x02 | 0x04 | 0x80, 0x8001
                note.icon, note.tip, note.version = native_icon.value or load_icon(None, c.c_void_p(32512)), "PocketWorld · 设备工作回执（只读）", 4
                if self.phone_work: note.tip = "PocketWorld · 交给手机（本机确认）"
                if not note.icon or not self.view.active() or not add(): return
                self.installed = True; self.ready.set()
                message = w.MSG()
                while not self.view.end.is_set() and get(c.byref(message), None, 0, 0) > 0:
                    dispatch(c.byref(message))
            finally:
                if self.installed: notify(2, c.byref(note))  # NIM_DELETE, no persistent system preference.
                self.hwnd = None
                destroy(hwnd)
                if native_icon: destroy_icon(native_icon)
        finally: unregister(name, instance)
