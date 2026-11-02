"""Small local Win32 owner window; no web page, credentials or second desktop Agent."""
import ctypes
import json
import os

from desktop_owner_confirmation import confirm_local_pairing, confirm_local_pairing_recovery
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError


def show_pairing_window(controller, host):
    if os.name != "nt" or WindowsDesktopHealth().probe().get("available") is not True:
        raise PlatformError("Pairing management requires the real logged-in, unlocked Windows owner desktop")
    from ctypes import wintypes as w
    user, kernel = ctypes.WinDLL("user32", use_last_error=True), ctypes.WinDLL("kernel32", use_last_error=True)
    wndproc = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
    class WindowClass(ctypes.Structure):
        _fields_ = [("style", w.UINT), ("procedure", wndproc), ("classExtra", ctypes.c_int), ("windowExtra", ctypes.c_int),
                    ("instance", w.HINSTANCE), ("icon", w.HICON), ("cursor", w.HANDLE), ("background", w.HBRUSH), ("menu", w.LPCWSTR), ("name", w.LPCWSTR)]
    signatures = {
        "DefWindowProcW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t),
        "RegisterClassW": ([ctypes.POINTER(WindowClass)], w.WORD), "UnregisterClassW": ([w.LPCWSTR, w.HINSTANCE], w.BOOL),
        "CreateWindowExW": ([w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, w.HWND, w.HMENU, w.HINSTANCE, ctypes.c_void_p], w.HWND),
        "DestroyWindow": ([w.HWND], w.BOOL), "PostQuitMessage": ([ctypes.c_int], None),
        "ShowWindow": ([w.HWND, ctypes.c_int], w.BOOL), "SetForegroundWindow": ([w.HWND], w.BOOL), "SetFocus": ([w.HWND], w.HWND),
        "GetWindowTextW": ([w.HWND, w.LPWSTR, ctypes.c_int], ctypes.c_int), "SetWindowTextW": ([w.HWND, w.LPCWSTR], w.BOOL),
        "SendMessageW": ([w.HWND, w.UINT, w.WPARAM, w.LPARAM], ctypes.c_ssize_t), "EnableWindow": ([w.HWND, w.BOOL], w.BOOL),
        "SetTimer": ([w.HWND, ctypes.c_size_t, w.UINT, ctypes.c_void_p], ctypes.c_size_t), "KillTimer": ([w.HWND, ctypes.c_size_t], w.BOOL),
        "SystemParametersInfoW": ([w.UINT, w.UINT, ctypes.c_void_p, w.UINT], w.BOOL),
        "AdjustWindowRectEx": ([ctypes.POINTER(w.RECT), w.DWORD, w.BOOL, w.DWORD], w.BOOL),
        "CreatePopupMenu": ([], w.HMENU), "DestroyMenu": ([w.HMENU], w.BOOL),
        "AppendMenuW": ([w.HMENU, w.UINT, ctypes.c_size_t, w.LPCWSTR], w.BOOL),
        "TrackPopupMenuEx": ([w.HMENU, w.UINT, ctypes.c_int, ctypes.c_int, w.HWND, ctypes.c_void_p], w.UINT),
        "GetCursorPos": ([ctypes.POINTER(w.POINT)], w.BOOL),
        "GetMessageW": ([ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], ctypes.c_int), "TranslateMessage": ([ctypes.POINTER(w.MSG)], w.BOOL),
        "DispatchMessageW": ([ctypes.POINTER(w.MSG)], ctypes.c_ssize_t),
    }
    for name, (arguments, result) in signatures.items():
        getattr(user, name).argtypes, getattr(user, name).restype = arguments, result
    kernel.GetModuleHandleW.argtypes, kernel.GetModuleHandleW.restype = [w.LPCWSTR], w.HINSTANCE
    instance = kernel.GetModuleHandleW(None)
    window = status = invitation_edit = candidate_edit = start_button = review_button = None
    controls, choices = {}, []
    candidate, start_bridge = None, False

    def text(control, maximum=128):
        value = ctypes.create_unicode_buffer(maximum + 1)
        user.GetWindowTextW(control, value, len(value)); return value.value

    def notice(message):
        if status:
            user.SetWindowTextW(status, message[:700])

    def poll():
        nonlocal candidate
        if host.recovery is not None:
            if controller.manager.now() >= host.recovery.expires_at:
                notice("原回执短只读通道已过期；没有续期、重新授信或业务启动。需要时关闭本窗口，再由本机主人明确开启恢复。")
            elif controller.recovery_response_prepared:
                notice("原不可变回执已交给传输通道；手机是否已保存须在手机确认。没有新授、续期、加额或业务启动。")
            return
        if controller.invitation is None:
            return
        now = controller.manager.now()
        rows = [item for item in controller.coordinator.pending_candidates(now) if item.invite_id == controller.invitation["inviteId"]]
        if rows:
            candidate = rows[0]
            value = candidate.display()
            user.SetWindowTextW(candidate_edit, "本机指纹：" + value["inviterFingerprint"] + "\r\n手机指纹：" + value["joinerFingerprint"] + "\r\n设备：" + value["joinerDeviceId"])
            user.EnableWindow(review_button, controller.pending is None and now < candidate.expires_at)
        if controller.completed:
            verified = bool(controller.settings_receipt and controller.settings_receipt.get("settingsVerified"))
            user.EnableWindow(start_button, verified)
            notice(controller.error or "已通过原双向TLS正式配对；尚未启动业务服务。点击“启动桥接”才启用已授权通道，不重放旧任务。")
        elif now >= controller.invitation["expiresAt"]:
            user.EnableWindow(review_button, False)
            notice("邀请已过期。关闭本窗口后可重新创建；过期邀请和候选都不提供业务权限。")

    @wndproc
    def procedure(hwnd, message, wparam, lparam):
        nonlocal start_bridge
        if message == 0x113:
            try: poll()
            except Exception: notice("无法核对当前配对状态；没有自动确认或重试。")
            return 0
        if message == 0x111:
            action = wparam & 0xFFFF
            try:
                if action == 101:
                    invitation = host.start(text(controls["host"], 64), control_port=int(text(controls["control"], 5)), bulk_port=int(text(controls["bulk"], 5)))
                    user.SetWindowTextW(invitation_edit, json.dumps(invitation, ensure_ascii=False, separators=(",", ":")))
                    for key in ("host", "control", "bulk", "create"):
                        user.EnableWindow(controls[key], False)
                    notice("120秒一次性邀请已创建。复制到手机的邀请导入入口；手机提交候选后，本机还必须核对两端指纹和准确授权。")
                elif action == 102:
                    user.SetFocus(invitation_edit)
                    user.SendMessageW(invitation_edit, 0xB1, 0, -1)  # select ALL actual invitation
                    user.SendMessageW(invitation_edit, 0x301, 0, 0)  # owner-clicked WM_COPY, no web/token bridge
                    notice("已复制原回执只读连接信息；在手机的显式恢复入口填写地址，不要当新邀请导入。" if host.recovery is not None else
                           "已复制一次性邀请；不要公开分享。它不是长期设备凭据，不能替代本机scope确认和双向TLS。")
                elif action == 106:
                    if controller.invitation is None:
                        raise PlatformError("先创建本窗口的一次性邀请")
                    from pairing_qr import show_invitation_qr
                    show_invitation_qr(controller.invitation, owner=hwnd)
                elif action == 107:
                    if host.thread is not None:
                        raise PlatformError("当前窗口已有邀请/通道；先关闭，再选择单独的原已提交回执恢复")
                    rows = controller.recovery_candidates()
                    if not rows:
                        raise PlatformError("本机没有可选择的原已提交配对事务；过期但未提交的候选不能恢复成授权")
                    menu, point = user.CreatePopupMenu(), w.POINT()
                    if not menu: raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        for index, row in enumerate(rows):
                            label = row["inviteId"] + " · " + row["peerDeviceId"][-12:] + " · " + row["recoveryState"]
                            if not user.AppendMenuW(menu, 0, 1101 + index, label): raise ctypes.WinError(ctypes.get_last_error())
                        if not user.GetCursorPos(ctypes.byref(point)): raise ctypes.WinError(ctypes.get_last_error())
                        selected = user.TrackPopupMenuEx(menu, 0x100 | 0x80, point.x, point.y, hwnd, None)
                    finally: user.DestroyMenu(menu)
                    if selected == 0: return 0
                    if not 1101 <= selected < 1101 + len(rows): raise PlatformError("原本机配对事务选择无效")
                    review = controller.prepare_recovery_review(rows[selected - 1101])
                    proof = confirm_local_pairing_recovery(review.summary)
                    address = host.start_recovery(text(controls["host"], 64), review, proof)
                    fixed = review.record["originalCommit"]
                    value = ("仅恢复原回执，非新邀请。请在手机原配对记录的恢复入口手动填写：\r\n"
                             + "Host: " + address[0] + "\r\nPort: " + str(address[1]) + "\r\n"
                             + "Invite: " + fixed["inviteId"] + "\r\n本机指纹: " + controller.identity.certificate_fingerprint
                             + "\r\n手机指纹: " + fixed["peerFingerprint"] + "\r\n30秒后自动关闭；当前撤销/到期/scope变动仍会拒绝。")
                    user.SetWindowTextW(invitation_edit, value)
                    user.SetWindowTextW(candidate_edit, review.summary)
                    for control in [controls[key] for key in ("host", "control", "bulk", "create", "qr", "recover", "budget", "minutes", "history")] + [control for item, control in choices]:
                        user.EnableWindow(control, False)
                    user.SetWindowTextW(controls["copy"], "复制恢复地址")
                    user.EnableWindow(review_button, False); user.EnableWindow(start_button, False)
                    notice("仅开放原事务30秒只读回执通道；不会写配置、启动业务或重放旧任务。地址本身不授信，仍须原双向TLS指纹。")
                elif action == 103:
                    if candidate is None:
                        raise PlatformError("先由手机导入邀请并提交候选")
                    selected = [item["id"] for item, control in choices if user.SendMessageW(control, 0xF0, 0, 0) == 1]
                    review = controller.prepare_review(candidate, selected, int(text(controls["budget"], 3)), int(text(controls["minutes"], 2)),
                                                       user.SendMessageW(controls["history"], 0xF0, 0, 0) == 1)
                    proof = confirm_local_pairing(review.summary)
                    host.confirm(review, proof)
                    user.EnableWindow(review_button, False)
                    for control in [control for item, control in choices] + [controls["budget"], controls["minutes"], controls["history"]]:
                        user.EnableWindow(control, False)
                    notice("本机已确认准确授权，正在等待手机通过保留端口完成双向TLS；此时仍没有业务通道。")
                elif action == 104:
                    controller.config_store.current()
                    host.business_reservations()  # actual held sockets, not guessed port numbers
                    start_bridge = True; user.DestroyWindow(hwnd)
                elif action == 105:
                    user.DestroyWindow(hwnd)
            except (ValueError, PermissionError, PlatformError) as error:
                notice(str(error))
            except Exception:
                notice("本机配对操作未确认。请检查原状态；不要自动重试或重放旧操作。")
            return 0
        if message == 2:
            user.PostQuitMessage(0); return 0
        return user.DefWindowProcW(hwnd, message, wparam, lparam)

    area = w.RECT()
    if not user.SystemParametersInfoW(0x30, 0, ctypes.byref(area), 0):
        raise PlatformError("Owner desktop working area is unavailable")
    # All control geometry below is CLIENT space. Caption/frame dimensions
    # cannot eat the status/actions or let a resize clip the reviewed controls.
    style = 0x00CA0000  # caption + system menu + minimize, no silent arbitrary resize
    width, height = min(1020, area.right - area.left - 80), min(800, area.bottom - area.top - 100)
    if width < 720 or height < 620:
        raise PlatformError("Owner scope review needs a usable desktop working area; no clipped confirmation fallback")
    class_name = "PocketWorldDevicePairing-" + __import__("uuid").uuid4().hex
    klass = WindowClass(0, procedure, 0, 0, instance, None, None, w.HBRUSH(6), None, class_name)
    if not user.RegisterClassW(ctypes.byref(klass)):
        raise ctypes.WinError(ctypes.get_last_error())
    frame = w.RECT(0, 0, width, height)
    if not user.AdjustWindowRectEx(ctypes.byref(frame), style, False, 0):
        user.UnregisterClassW(class_name, instance); raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, class_name, "PocketWorld · 本机设备配对与授权", style, area.left + 20, area.top + 20,
                                  frame.right - frame.left, frame.bottom - frame.top, None, None, instance, None)
    if not window:
        user.UnregisterClassW(class_name, instance); raise ctypes.WinError(ctypes.get_last_error())
    def control(kind, value, x, y, ww, hh, ident=0, extra=0):
        handle = user.CreateWindowExW(0x200 if kind == "EDIT" else 0, kind, value, 0x40000000 | 0x10000000 | extra,
                                      x, y, ww, hh, window, w.HMENU(ident) if ident else None, instance, None)
        if not handle: raise ctypes.WinError(ctypes.get_last_error())
        return handle
    try:
        control("STATIC", "电脑只做轻桥接，不需Codex；以下操作仅由本机主人确认，不启动第二Agent。", 16, 12, width - 40, 22)
        control("STATIC", "本机可达LAN地址", 16, 42, 145, 24)
        configured = controller.manager.config.get("devices", {}).get("listen", {})
        initial_host = configured.get("host", "")
        if initial_host in {"0.0.0.0", "::"}: initial_host = ""
        controls["host"] = control("EDIT", initial_host, 165, 39, 210, 28, extra=0x10000)
        control("STATIC", "控制 / 文件端口", 388, 42, 145, 24)
        controls["control"] = control("EDIT", str(configured.get("controlPort") or 8768), 535, 39, 70, 28, extra=0x10000)
        controls["bulk"] = control("EDIT", str(configured.get("bulkPort") or 8769), 615, 39, 70, 28, extra=0x10000)
        controls["create"] = control("BUTTON", "创建一次性邀请", 16, 77, 190, 30, 101, 0x10000)
        controls["copy"] = control("BUTTON", "复制邀请", 220, 77, 145, 30, 102, 0x10000)
        controls["qr"] = control("BUTTON", "显示二维码", 380, 77, 145, 30, 106, 0x10000)
        controls["recover"] = control("BUTTON", "恢复原已提交回执", 540, 77, 170, 30, 107, 0x10000)
        control("STATIC", "选择准确授权范围（默认未选择；包含本机私有状态的工作区不会列出）：", 16, 116, width - 40, 22)
        options = controller.scope_options()
        visible = min(8, max(1, (height - 570) // 24))
        for index, option in enumerate(options[:visible]):
            label = option["id"]
            if option["version"] == "workspace-v1":
                label += " · " + controller.manager._environment(option["resources"][0]["environmentId"])["root"]
            else:
                label = "主屏当前交互会话（所有窗口，非文件沙箱；首次观察仍须驱动实探）"
            checkbox = control("BUTTON", label, 20, 143 + 24 * index, width - 60, 24, 200 + index, 0x3 | 0x10000)
            choices.append((option, checkbox))
        bottom = height - 105
        field_y = min(350, 151 + 24 * max(1, len(choices)))
        if len(options) > visible: control("STATIC", "当前尺寸只显示前" + str(visible) + "项；更多范围请先在本机配置中缩小选择。没有隐式授权。", 20, field_y, width - 50, 22); field_y += 24
        control("STATIC", "工具总额度", 16, field_y, 110, 24)
        controls["budget"] = control("EDIT", "20", 130, field_y - 2, 62, 27, extra=0x10000)
        control("STATIC", "执行授权分钟", 210, field_y, 125, 24)
        controls["minutes"] = control("EDIT", "60", 340, field_y - 2, 62, 27, extra=0x10000)
        controls["history"] = control("BUTTON", "另允许24小时仅查本次成果", 425, field_y - 2, width - 460, 27, 300, 0x3 | 0x10000)
        invitation_edit = control("EDIT", "邀请尚未创建。地址需由手机可达；不会自动打开公开HTTP或把长期令牌交给页面。", 16, field_y + 34, width - 50, 86, extra=0x4 | 0x800 | 0x200000)
        candidate_edit = control("EDIT", "等待手机候选。仅有邀请、候选或pending pin均不能使用业务工具。", 16, field_y + 128, width - 50, max(54, bottom - field_y - 182), extra=0x4 | 0x800 | 0x200000)
        review_button = control("BUTTON", "核对指纹与准确授权", 16, bottom - 8, 235, 31, 103, 0x10000)
        start_button = control("BUTTON", "启动桥接（不重放旧任务）", 264, bottom - 8, 260, 31, 104, 0x10000)
        control("BUTTON", "关闭，不启动服务", width - 255, bottom - 8, 205, 31, 105, 0x10000)
        user.EnableWindow(review_button, False); user.EnableWindow(start_button, False)
        status = control("STATIC", "先填写本机LAN地址并创建邀请。关闭不等于撤销已经确认/完成的配对；服务不会自动启动。", 16, bottom + 32, width - 50, 52)
        user.SetTimer(window, 1, 500, None)
        user.ShowWindow(window, 5); user.SetForegroundWindow(window)
        message = w.MSG()
        while True:
            result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
            if result == 0: break
            if result < 0: raise ctypes.WinError(ctypes.get_last_error())
            user.TranslateMessage(ctypes.byref(message)); user.DispatchMessageW(ctypes.byref(message))
        return start_bridge
    finally:
        user.KillTimer(window, 1)
        user.DestroyWindow(window)
        user.UnregisterClassW(class_name, instance)
