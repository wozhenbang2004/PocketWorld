"""Encode the UNIQUE invitation with pinned python-qrcode; native display only.

No shortened/carrier protocol, web service, disk image, token logging, or QR
encoder of our own. Black/white integer-pixel rasterization is presentation.
"""
from __future__ import annotations

import ctypes
import importlib.metadata
import os
import time
import uuid

import device_protocol as protocol
from device_pairing import validate_invitation_for_preview
from platform_backend import PlatformError

QR_VERSION = "8.2"


def invitation_matrix(invitation, now_ms):
    validate_invitation_for_preview(invitation, now_ms)
    raw = protocol.canonical_bytes(invitation)
    if len(raw) > 2200:
        raise PlatformError("Complete invitation exceeds the scannable QR bound; use Copy invitation, not a shortened authority carrier")
    try:
        if importlib.metadata.version("qrcode") != QR_VERSION:
            raise PlatformError("The installed QR dependency differs from its pinned version")
        import qrcode
    except (ImportError, importlib.metadata.PackageNotFoundError) as error:
        raise PlatformError("QR support is not present in this package; use Copy invitation") from error
    code = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=1, border=4)
    code.add_data(raw, optimize=0)
    code.make(fit=True)
    matrix = tuple(tuple(row) for row in code.get_matrix())
    size = len(matrix)
    if not 29 <= size <= 185 or any(len(row) != size or any(type(cell) is not bool for cell in row) for row in matrix):
        raise PlatformError("Pinned QR encoder returned an invalid bounded matrix")
    if any(matrix[y][x] for y in range(size) for x in range(size) if min(y, x, size - 1 - y, size - 1 - x) < 4):
        raise PlatformError("QR quiet zone differs from the required four complete modules")
    return matrix


def raster_bgra(matrix, module_pixels):
    """Unscaled, opaque 32-bit DIB bytes; never apply smoothing or crop modules."""
    if type(module_pixels) is not int or not 3 <= module_pixels <= 8:
        raise PlatformError("QR display requires at least three physical pixels per complete module")
    if not isinstance(matrix, tuple) or not 29 <= len(matrix) <= 185 or any(len(row) != len(matrix) for row in matrix):
        raise PlatformError("Bounded original QR matrix required")
    rows = [b"".join((b"\0\0\0\xff" if cell else b"\xff\xff\xff\xff") * module_pixels for cell in row) for row in matrix]
    return b"".join(row * module_pixels for row in rows)


def show_invitation_qr(invitation, *, owner=None, clock=lambda: time.time_ns() // 1000000):
    if os.name != "nt":
        raise PlatformError("QR display requires the actual local Windows owner window")
    from desktop_sessions import WindowsDesktopHealth
    if WindowsDesktopHealth().probe().get("available") is not True:
        raise PlatformError("The local interactive owner desktop is unavailable")
    matrix = invitation_matrix(invitation, clock())
    from ctypes import wintypes as w
    user, kernel, gdi = (ctypes.WinDLL(name, use_last_error=True) for name in ("user32", "kernel32", "gdi32"))
    wndproc = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    class WindowClass(ctypes.Structure):
        _fields_ = [("style", w.UINT), ("procedure", wndproc), ("classExtra", ctypes.c_int), ("windowExtra", ctypes.c_int),
                    ("instance", w.HINSTANCE), ("icon", w.HICON), ("cursor", w.HANDLE), ("background", w.HBRUSH), ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]
    class Paint(ctypes.Structure):
        _fields_ = [("dc", w.HDC), ("erase", w.BOOL), ("rect", w.RECT), ("restore", w.BOOL), ("update", w.BOOL), ("reserved", ctypes.c_byte * 32)]
    class Bitmap(ctypes.Structure):
        _fields_ = [("size", w.DWORD), ("width", w.LONG), ("height", w.LONG), ("planes", w.WORD), ("bits", w.WORD),
                    ("compression", w.DWORD), ("bytes", w.DWORD), ("xppm", w.LONG), ("yppm", w.LONG), ("used", w.DWORD), ("important", w.DWORD)]
    signatures = {
        "DefWindowProcW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
        "RegisterClassW": ([ctypes.POINTER(WindowClass)], w.WORD), "UnregisterClassW": ([w.LPCWSTR, w.HINSTANCE], w.BOOL),
        "CreateWindowExW": ([w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p], w.HWND),
        "DestroyWindow": ([w.HWND], w.BOOL), "PostQuitMessage": ([ctypes.c_int], None),
        "ShowWindow": ([w.HWND, ctypes.c_int], w.BOOL), "SetForegroundWindow": ([w.HWND], w.BOOL), "EnableWindow": ([w.HWND, w.BOOL], w.BOOL),
        "BeginPaint": ([w.HWND, ctypes.POINTER(Paint)], w.HDC), "EndPaint": ([w.HWND, ctypes.POINTER(Paint)], w.BOOL),
        "SetTimer": ([w.HWND, ctypes.c_size_t, w.UINT, ctypes.c_void_p], ctypes.c_size_t), "KillTimer": ([w.HWND, ctypes.c_size_t], w.BOOL),
        "SystemParametersInfoW": ([w.UINT, w.UINT, ctypes.c_void_p, w.UINT], w.BOOL),
        "GetMessageW": ([ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], ctypes.c_int), "TranslateMessage": ([ctypes.POINTER(w.MSG)], w.BOOL),
        "DispatchMessageW": ([ctypes.POINTER(w.MSG)], ctypes.c_ssize_t),
    }
    for name, (args, result) in signatures.items(): getattr(user, name).argtypes, getattr(user, name).restype = args, result
    kernel.GetModuleHandleW.argtypes, kernel.GetModuleHandleW.restype = [w.LPCWSTR], w.HINSTANCE
    gdi.SetDIBitsToDevice.argtypes = [w.HDC, ctypes.c_int, ctypes.c_int, w.DWORD, w.DWORD, ctypes.c_int, ctypes.c_int, w.UINT, w.UINT, ctypes.c_void_p, ctypes.POINTER(Bitmap), w.UINT]
    gdi.SetDIBitsToDevice.restype = ctypes.c_int
    area = w.RECT()
    if not user.SystemParametersInfoW(0x30, 0, ctypes.byref(area), 0): raise PlatformError("QR owner working area unavailable")
    available = min(area.right - area.left - 80, area.bottom - area.top - 180)
    scale = min(6, available // len(matrix))
    if scale < 3: raise PlatformError("Desktop is too small for a complete scannable QR; use Copy invitation")
    side = len(matrix) * scale
    pixels = ctypes.create_string_buffer(raster_bgra(matrix, scale))
    bitmap = Bitmap(ctypes.sizeof(Bitmap), side, -side, 1, 32, 0, side * side * 4, 0, 0, 0, 0)
    @wndproc
    def procedure(hwnd, message, wparam, lparam):
        if message == 0x113 and clock() >= invitation["expiresAt"]:
            user.DestroyWindow(hwnd); return 0
        if message == 0xF:
            paint = Paint(); dc = user.BeginPaint(hwnd, ctypes.byref(paint))
            try:
                if clock() < invitation["expiresAt"]: gdi.SetDIBitsToDevice(dc, 16, 54, side, side, 0, 0, 0, side, pixels, ctypes.byref(bitmap), 0)
            finally: user.EndPaint(hwnd, ctypes.byref(paint))
            return 0
        if message == 2: user.PostQuitMessage(0); return 0
        return user.DefWindowProcW(hwnd, message, wparam, lparam)
    instance = kernel.GetModuleHandleW(None); name = "PocketWorldPairingQR-" + uuid.uuid4().hex
    klass = WindowClass(0, procedure, 0, 0, instance, None, None, w.HBRUSH(6), None, name)
    if not user.RegisterClassW(ctypes.byref(klass)): raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, name, "PocketWorld · 手机扫描一次性邀请", 0x00C80000, area.left + 30, area.top + 30, side + 50, side + 145, owner, None, instance, None)
    if not window:
        user.UnregisterClassW(name, instance); raise ctypes.WinError(ctypes.get_last_error())
    try:
        user.CreateWindowExW(0, "STATIC", "用手机扫描；过期自动关闭。仍须双指纹与本机准确授权。", 0x50000000, 16, 12, side, 40, window, None, instance, None)
        if owner: user.EnableWindow(owner, False)
        user.SetTimer(window, 1, 250, None); user.ShowWindow(window, 5); user.SetForegroundWindow(window)
        message = w.MSG()
        while True:
            result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
            if result == 0: break
            if result < 0: raise ctypes.WinError(ctypes.get_last_error())
            user.TranslateMessage(ctypes.byref(message)); user.DispatchMessageW(ctypes.byref(message))
    finally:
        user.KillTimer(window, 1); user.DestroyWindow(window); user.UnregisterClassW(name, instance)
        ctypes.memset(pixels, 0, len(pixels))
        if owner: user.EnableWindow(owner, True); user.SetForegroundWindow(owner)
