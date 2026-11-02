"""Controlled native Windows editor fixture, NOT a desktop input driver.

Only used by the opt-in GUI acceptance test. It never opens a user application,
loads a model, connects to a service, or writes outside its explicit test output.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path


def main():
    if os.name != "nt":
        raise SystemExit("This fixture requires a real Windows interactive desktop")
    from ctypes import wintypes as w
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists() or not output.parent.is_dir():
        raise SystemExit("Fixture output must be an absent file in an existing controlled test directory")
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    user = ctypes.WinDLL("user32", use_last_error=True)
    user.SetProcessDpiAwarenessContext.argtypes, user.SetProcessDpiAwarenessContext.restype = [w.HANDLE], w.BOOL
    user.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    callback = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    class WindowClass(ctypes.Structure):
        _fields_ = [("style", w.UINT), ("procedure", callback), ("classExtra", ctypes.c_int), ("windowExtra", ctypes.c_int),
                    ("instance", w.HINSTANCE), ("icon", w.HICON), ("cursor", w.HANDLE), ("background", w.HBRUSH),
                    ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]
    signatures = {
        "DefWindowProcW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
        "RegisterClassW": ([ctypes.POINTER(WindowClass)], w.WORD),
        "CreateWindowExW": ([w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p], w.HWND),
        "ShowWindow": ([w.HWND, ctypes.c_int], w.BOOL), "UpdateWindow": ([w.HWND], w.BOOL),
        "GetMessageW": ([ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], ctypes.c_int),
        "TranslateMessage": ([ctypes.POINTER(w.MSG)], w.BOOL), "DispatchMessageW": ([ctypes.POINTER(w.MSG)], ctypes.c_ssize_t),
        "GetWindowTextLengthW": ([w.HWND], ctypes.c_int), "GetWindowTextW": ([w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int),
        "SetWindowTextW": ([w.HWND, w.LPCWSTR], w.BOOL), "PostQuitMessage": ([ctypes.c_int], None),
        "GetKeyState": ([ctypes.c_int], ctypes.c_short),
    }
    for name, (parameters, result) in signatures.items():
        getattr(user, name).argtypes, getattr(user, name).restype = parameters, result
    kernel.GetModuleHandleW.argtypes, kernel.GetModuleHandleW.restype = [w.LPCWSTR], w.HINSTANCE
    instance, edit = kernel.GetModuleHandleW(None), None
    window = None
    def save():
        length = user.GetWindowTextLengthW(edit)
        buffer = ctypes.create_unicode_buffer(length + 1)
        user.GetWindowTextW(edit, buffer, length + 1)
        raw = buffer.value.encode("utf-8")
        # This exact temporary test path was chosen by the harness, not by Cua
        # or a remote prompt. Product file I/O uses PlatformBackend instead.
        descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        user.SetWindowTextW(window, "PocketWorld Test Editor - 已保存")
    @callback
    def procedure(hwnd, message, wparam, lparam):
        if message == 0x111 and (wparam & 0xFFFF) == 1002:  # WM_COMMAND / Save test file
            save(); return 0
        if message == 2:
            user.PostQuitMessage(0); return 0
        return user.DefWindowProcW(hwnd, message, wparam, lparam)
    # This controlled accelerator fixture intentionally uses the VCL/SAL class
    # family recognized by fixed Cua's background-drop detector. Like that
    # family, Ctrl+S below needs REAL GetKeyState modifier state, not PostMessage.
    # It is not a claim that this fixture is LibreOffice or a generic GUI test.
    klass = WindowClass(0, procedure, 0, 0, instance, None, None, w.HBRUSH(6), None, "SALPocketWorldControlledEditorV2")
    if not user.RegisterClassW(ctypes.byref(klass)):
        raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, klass.name, "PocketWorld Test Editor", 0x00CF0000, 40, 40, 640, 480, None, None, instance, None)
    if not window:
        raise ctypes.WinError(ctypes.get_last_error())
    edit = user.CreateWindowExW(0x200, "EDIT", "OLD 旧文", 0x40000000 | 0x10000000 | 0x00010000 | 0x00200000 | 0x4 | 0x40,
                              16, 16, 590, 340, window, w.HMENU(1001), instance, None)
    button = user.CreateWindowExW(0, "BUTTON", "保存测试文件", 0x40000000 | 0x10000000 | 0x00010000,
                                16, 370, 180, 36, window, w.HMENU(1002), instance, None)
    decoration = user.CreateWindowExW(0, "STATIC", "DECORATION A", 0x40000000 | 0x10000000,
                                    230, 375, 310, 24, window, w.HMENU(1003), instance, None)
    if not edit or not button or not decoration:
        raise ctypes.WinError(ctypes.get_last_error())
    user.ShowWindow(window, 5); user.UpdateWindow(window)
    print(json.dumps({"pid": os.getpid(), "windowId": window, "editWindowId": edit, "decorationWindowId": decoration,
                      "fixture": "native_controlled_editor"}), flush=True)
    message = w.MSG()
    while True:
        result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
        if result == 0:
            break
        if result < 0:
            raise ctypes.WinError(ctypes.get_last_error())
        if message.message == 0x100 and message.wParam == ord("S") and user.GetKeyState(0x11) < 0:
            save(); continue
        user.TranslateMessage(ctypes.byref(message)); user.DispatchMessageW(ctypes.byref(message))


if __name__ == "__main__":
    main()
