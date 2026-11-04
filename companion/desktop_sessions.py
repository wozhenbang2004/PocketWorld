"""Actual Windows interactive-session health. Online transport is not GUI health."""
from __future__ import annotations

import ctypes
import hashlib
import json
import os
import time


class WindowsDesktopHealth:
    def resolve_window(self, window_id):
        """Resolve an owner-granted HWND locally, never accept a remote PID claim."""
        if os.name != "nt" or type(window_id) is not int or not 1 <= window_id <= 9007199254740991:
            raise ValueError("An actual Windows window is required")
        from ctypes import wintypes as w
        u = ctypes.WinDLL("user32", use_last_error=True)
        u.GetWindowThreadProcessId.argtypes, u.GetWindowThreadProcessId.restype = [w.HANDLE, ctypes.POINTER(w.DWORD)], w.DWORD
        pid = w.DWORD()
        if not u.GetWindowThreadProcessId(window_id, ctypes.byref(pid)) or not pid.value:
            raise ValueError("The original window no longer exists")
        target = {"pid": pid.value, "windowId": window_id}
        if not self.probe(pid.value, window_id).get("available"):
            raise ValueError("The original window is not available in this interactive session")
        return target

    def probe(self, pid=None, window_id=None):
        if os.name != "nt":
            return {"available": False, "reason": "windows_interactive_session_required", "platform": os.name,
                    "nativeGuiVerified": False}
        from ctypes import wintypes as w
        k = ctypes.WinDLL("kernel32", use_last_error=True)
        u = ctypes.WinDLL("user32", use_last_error=True)
        wt = ctypes.WinDLL("wtsapi32", use_last_error=True)
        # Physical-pixel OS evidence must use the same awareness as Cua's
        # capture. An already-initialized incompatible host fails closed.
        u.SetProcessDpiAwarenessContext.argtypes, u.SetProcessDpiAwarenessContext.restype = [w.HANDLE], w.BOOL
        u.GetThreadDpiAwarenessContext.argtypes, u.GetThreadDpiAwarenessContext.restype = [], w.HANDLE
        u.AreDpiAwarenessContextsEqual.argtypes, u.AreDpiAwarenessContextsEqual.restype = [w.HANDLE, w.HANDLE], w.BOOL
        u.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
        if not u.AreDpiAwarenessContextsEqual(u.GetThreadDpiAwarenessContext(), ctypes.c_void_p(-4)):
            return {"available": False, "reason": "physical_pixel_dpi_awareness_unavailable"}
        k.ProcessIdToSessionId.argtypes, k.ProcessIdToSessionId.restype = [w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL
        session = w.DWORD()
        if not k.ProcessIdToSessionId(os.getpid(), ctypes.byref(session)) or session.value == 0:
            return {"available": False, "reason": "session_0_or_unknown", "sessionId": session.value}
        wt.WTSQuerySessionInformationW.argtypes = [w.HANDLE, w.DWORD, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(w.DWORD)]
        wt.WTSQuerySessionInformationW.restype = w.BOOL
        wt.WTSFreeMemory.argtypes = [ctypes.c_void_p]
        value, size = ctypes.c_void_p(), w.DWORD()
        if not wt.WTSQuerySessionInformationW(None, session.value, 8, ctypes.byref(value), ctypes.byref(size)):
            return {"available": False, "reason": "session_state_unknown", "sessionId": session.value}
        try:
            connected = size.value >= 4 and ctypes.cast(value, ctypes.POINTER(w.DWORD)).contents.value == 0
        finally:
            wt.WTSFreeMemory(value)
        if not connected:
            return {"available": False, "reason": "interactive_session_disconnected", "sessionId": session.value}
        u.OpenInputDesktop.argtypes, u.OpenInputDesktop.restype = [w.DWORD, w.BOOL, w.DWORD], w.HANDLE
        u.CloseDesktop.argtypes, u.CloseDesktop.restype = [w.HANDLE], w.BOOL
        u.GetUserObjectInformationW.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.POINTER(w.DWORD)]
        u.GetUserObjectInformationW.restype = w.BOOL
        desktop = u.OpenInputDesktop(0, False, 1)  # DESKTOP_READOBJECTS, never switch/unlock
        if not desktop:
            return {"available": False, "reason": "locked_secure_or_inaccessible_desktop", "sessionId": session.value}
        try:
            name, needed = ctypes.create_unicode_buffer(256), w.DWORD()
            if not u.GetUserObjectInformationW(desktop, 2, name, ctypes.sizeof(name), ctypes.byref(needed)):
                return {"available": False, "reason": "input_desktop_identity_unknown", "sessionId": session.value}
            if name.value.casefold() != "default":
                return {"available": False, "reason": "locked_or_secure_desktop", "sessionId": session.value}
            desktop_name = name.value
        finally:
            u.CloseDesktop(desktop)
        class MonitorInfo(ctypes.Structure):
            _fields_ = [("size", w.DWORD), ("monitor", w.RECT), ("work", w.RECT), ("flags", w.DWORD), ("device", w.WCHAR * 32)]
        callback_type = ctypes.WINFUNCTYPE(w.BOOL, w.HANDLE, w.HDC, ctypes.POINTER(w.RECT), w.LPARAM)
        u.GetMonitorInfoW.argtypes, u.GetMonitorInfoW.restype = [w.HANDLE, ctypes.POINTER(MonitorInfo)], w.BOOL
        u.EnumDisplayMonitors.argtypes, u.EnumDisplayMonitors.restype = [w.HDC, ctypes.POINTER(w.RECT), callback_type, w.LPARAM], w.BOOL
        displays = []
        try:
            shcore = ctypes.WinDLL("shcore", use_last_error=True)
            shcore.GetDpiForMonitor.argtypes = [w.HANDLE, ctypes.c_int, ctypes.POINTER(w.UINT), ctypes.POINTER(w.UINT)]
            shcore.GetDpiForMonitor.restype = ctypes.c_long
        except OSError:
            shcore = None
        @callback_type
        def add_monitor(handle, hdc, rectangle, data):
            info = MonitorInfo()
            info.size = ctypes.sizeof(info)
            if not u.GetMonitorInfoW(handle, ctypes.byref(info)):
                return False
            dx, dy = w.UINT(), w.UINT()
            dpi = None
            if shcore is not None and shcore.GetDpiForMonitor(handle, 0, ctypes.byref(dx), ctypes.byref(dy)) == 0:
                dpi = [dx.value, dy.value]
            displays.append({"id": info.device, "primary": bool(info.flags & 1), "dpi": dpi,
                             "bounds": [info.monitor.left, info.monitor.top, info.monitor.right, info.monitor.bottom]})
            return True
        if not u.EnumDisplayMonitors(None, None, add_monitor, 0) or not displays:
            return {"available": False, "reason": "display_geometry_unknown", "sessionId": session.value}
        class LastInput(ctypes.Structure):
            _fields_ = [("size", w.UINT), ("tick", w.DWORD)]
        u.GetLastInputInfo.argtypes, u.GetLastInputInfo.restype = [ctypes.POINTER(LastInput)], w.BOOL
        last = LastInput()
        last.size = ctypes.sizeof(last)
        if not u.GetLastInputInfo(ctypes.byref(last)):
            return {"available": False, "reason": "input_activity_unknown", "sessionId": session.value}
        result = {"available": True, "platform": "windows", "sessionId": session.value,
                  "inputDesktop": desktop_name, "displays": sorted(displays, key=lambda item: item["id"]),
                  "lastInputTick": last.tick, "manualDetection": "unexpected_input_change_conservative_plus_local_stop",
                  "observedAt": int(time.time() * 1000)}
        u.GetForegroundWindow.argtypes, u.GetForegroundWindow.restype = [], w.HANDLE
        result["foregroundWindowId"] = u.GetForegroundWindow()
        if pid is not None or window_id is not None:
            if type(pid) is not int or type(window_id) is not int or min(pid, window_id) <= 0:
                return {"available": False, "reason": "invalid_window_binding"}
            u.GetWindowThreadProcessId.argtypes, u.GetWindowThreadProcessId.restype = [w.HANDLE, ctypes.POINTER(w.DWORD)], w.DWORD
            u.GetWindowRect.argtypes, u.GetWindowRect.restype = [w.HANDLE, ctypes.POINTER(w.RECT)], w.BOOL
            u.GetDpiForWindow.argtypes, u.GetDpiForWindow.restype = [w.HANDLE], w.UINT
            owner, rectangle = w.DWORD(), w.RECT()
            thread = u.GetWindowThreadProcessId(window_id, ctypes.byref(owner))
            if not thread or owner.value != pid or not u.GetWindowRect(window_id, ctypes.byref(rectangle)):
                return {"available": False, "reason": "window_replaced_or_wrong_process", "sessionId": session.value}
            k.OpenProcess.argtypes, k.OpenProcess.restype = [w.DWORD, w.BOOL, w.DWORD], w.HANDLE
            k.GetProcessTimes.argtypes = [w.HANDLE] + [ctypes.POINTER(w.FILETIME)] * 4
            k.GetProcessTimes.restype = w.BOOL
            k.CloseHandle.argtypes = [w.HANDLE]
            handle = k.OpenProcess(0x1000, False, pid)  # QUERY_LIMITED_INFORMATION
            if not handle:
                return {"available": False, "reason": "window_process_identity_unavailable"}
            try:
                created, exited, kernel, user = w.FILETIME(), w.FILETIME(), w.FILETIME(), w.FILETIME()
                if not k.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user)):
                    return {"available": False, "reason": "window_process_generation_unknown"}
                process_session = w.DWORD()
                if not k.ProcessIdToSessionId(pid, ctypes.byref(process_session)) or process_session.value != session.value:
                    return {"available": False, "reason": "window_is_in_another_session"}
                result["window"] = {"pid": pid, "windowId": window_id, "threadId": thread,
                                    "processCreated": str((created.dwHighDateTime << 32) | created.dwLowDateTime),
                                    "bounds": [rectangle.left, rectangle.top, rectangle.right, rectangle.bottom],
                                    "dpi": u.GetDpiForWindow(window_id)}
                # Cua's native PNG can omit the invisible DWM resize border.
                # Keep both physical rectangles so viewer clicks can map a
                # full-window video point to an exact native capture, or refuse.
                try:
                    dwm = ctypes.WinDLL("dwmapi", use_last_error=True)
                    dwm.DwmGetWindowAttribute.argtypes = [w.HANDLE, w.DWORD, ctypes.c_void_p, w.DWORD]
                    dwm.DwmGetWindowAttribute.restype = ctypes.c_long
                    frame = w.RECT()
                    if dwm.DwmGetWindowAttribute(window_id, 9, ctypes.byref(frame), ctypes.sizeof(frame)) == 0:
                        if rectangle.left <= frame.left < frame.right <= rectangle.right and rectangle.top <= frame.top < frame.bottom <= rectangle.bottom:
                            result["window"]["frameBounds"] = [frame.left, frame.top, frame.right, frame.bottom]
                except OSError:
                    pass  # No guessed crop origin; only an exact full-window PNG may be used.
                if not result["window"]["dpi"]:
                    return {"available": False, "reason": "window_dpi_unknown"}
            finally:
                k.CloseHandle(handle)
        # LastInput is NOT part of the binding; it is checked separately as a
        # conservative takeover signal. Display/window changes invalidate it.
        identity = {key: value for key, value in result.items() if key in {"sessionId", "inputDesktop", "displays", "window", "foregroundWindowId"}}
        result["binding"] = hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()
        return result
