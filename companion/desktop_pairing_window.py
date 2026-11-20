"""Small local Win32 owner window; no web page, credentials or second desktop Agent."""
import ctypes
import json
import os

from desktop_owner_confirmation import confirm_local_pairing, confirm_local_pairing_recovery
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
import windows_read_pairing as read_pairing
import windows_pair_identity as pair_identity
from windows_pairing_return import READY, window_class
from windows_owner_surface import OwnerSurface, OWNER_STYLE, USE_DEFAULT, columns


def show_pairing_window(controller, host, *, offer_local_receipts=False, installed_owner=None):
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
        "IsDialogMessageW": ([w.HWND, ctypes.POINTER(w.MSG)], w.BOOL),
        "SetPropW": ([w.HWND,w.LPCWSTR,w.HANDLE],w.BOOL), "RemovePropW": ([w.HWND,w.LPCWSTR],w.HANDLE),
        "GetPropW": ([w.HWND,w.LPCWSTR],w.HANDLE), "IsWindow": ([w.HWND],w.BOOL),
        "CreatePopupMenu": ([], w.HMENU), "DestroyMenu": ([w.HMENU], w.BOOL),
        "AppendMenuW": ([w.HMENU, w.UINT, ctypes.c_size_t, w.LPCWSTR], w.BOOL),
        "TrackPopupMenuEx": ([w.HMENU, w.UINT, ctypes.c_int, ctypes.c_int, w.HWND, ctypes.c_void_p], w.UINT),
        "GetCursorPos": ([ctypes.POINTER(w.POINT)], w.BOOL),
        "GetWindowRect": ([w.HWND, ctypes.POINTER(w.RECT)], w.BOOL),
        "GetMessageW": ([ctypes.POINTER(w.MSG), w.HWND, w.UINT, w.UINT], ctypes.c_int), "TranslateMessage": ([ctypes.POINTER(w.MSG)], w.BOOL),
        "DispatchMessageW": ([ctypes.POINTER(w.MSG)], ctypes.c_ssize_t),
    }
    for name, (arguments, result) in signatures.items():
        getattr(user, name).argtypes, getattr(user, name).restype = arguments, result
    kernel.GetModuleHandleW.argtypes, kernel.GetModuleHandleW.restype = [w.LPCWSTR], w.HINSTANCE
    instance = kernel.GetModuleHandleW(None)
    surface = None
    window = status = invitation_edit = candidate_edit = start_button = review_button = None
    controls, choices = {}, []
    extra_options, extra_selected = [], set()
    candidate, start_bridge, chosen_address = None, False, None
    saved_budget = None
    # Android's paired directory shows the same suffix. It identifies a row, not the peer's full certificate.
    local_code = controller.identity.device_id[-6:]

    def text(control, maximum=128):
        value = ctypes.create_unicode_buffer(maximum + 1)
        user.GetWindowTextW(control, value, len(value)); return value.value

    def notice(message):
        if status:
            user.SetWindowTextW(status, message[:700])

    def require_visible():
        if surface is None or not surface.fits(showing=True):
            raise PlatformError("工作区、比例或窗口位置已改变。请移回打开时的显示器并让窗口完整可见，或关闭后重新打开。没有代为确认。")

    def selected_host():
        value = text(controls["host"], 64)
        if chosen_address is not None:
            from desktop_pairing_addresses import local_choices
            if value != chosen_address.address or not any(
                row.address == value and row.adapter == chosen_address.adapter for row in local_choices()
            ):
                raise PlatformError("刚选的网卡或地址已经改变。请重新选择；没有改用其他地址或打开监听。")
        return value

    def scope_label(option):
        if option["id"] == pair_identity.OPTION: return "仅配对 · 手机只核对电脑身份（单独选择，不授桌面）"
        if option["version"] == "workspace-v1":
            path = controller.manager._environment(option["resources"][0]["environmentId"])["root"]
            label = "仅列目录、读小文本（独选）" if option["id"].startswith(read_pairing.PREFIX) else "工作区已配置的全部工具"
            return label + " · " + option["resources"][0]["environmentId"] + " · " + path
        if option["id"] == "desktop":
            resources = option["resources"]
            if len(resources) == 1:
                binding = resources[0]
                scope = (f"窗口 0x{binding['windowId']:X}" if "windowId" in binding else "主屏（所有窗口）")
                return f"Windows 会话 {binding['sessionId']} · {scope} · 手机观看与接管、桌面观察与操作"
            return f"桌面 · {len(resources)} 项范围，核对时逐项显示"
        return option["id"]

    def selected_scopes():
        selected = [item["id"] for item, control in choices if user.SendMessageW(control, 0xF0, 0, 0) == 1]
        return selected + [item["id"] for item in extra_options if item["id"] in extra_selected]

    def review_choices(ids):
        read_pairing.narrow(ids)
        limited = pair_identity.alone(ids)
        if limited and user.SendMessageW(controls["history"], 0xF0, 0, 0) == 1:
            raise PlatformError("仅配对不能开放历史；请先取消原结果延长读取。")
        return limited

    def budget_for_selection():
        nonlocal saved_budget
        limited = pair_identity.alone(selected_scopes())
        if limited and saved_budget is None:
            saved_budget = text(controls["budget"], 3)
            user.SetWindowTextW(controls["budget"], "0")
            user.EnableWindow(controls["budget"], False); user.EnableWindow(controls["history"], False)
        elif not limited and saved_budget is not None:
            user.SetWindowTextW(controls["budget"], saved_budget); saved_budget = None
            user.EnableWindow(controls["budget"], True); user.EnableWindow(controls["history"], True)

    def freeze_scopes():
        for _, checkbox in choices:
            user.EnableWindow(checkbox, False)
        if "more_scopes" in controls:
            user.EnableWindow(controls["more_scopes"], False)

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
            if controller.error:
                notice(controller.error)
            elif not verified:
                notice("Windows 已保存配对，连接设置尚未核实，不能启动连接服务。")
            else:
                notice(f"Windows 已保存配对，连接服务未启动。请在手机「我的设备 → 配对伙伴」核对电脑编号末六位 {local_code}。")
        elif now >= controller.invitation["expiresAt"]:
            user.EnableWindow(review_button, False)
            notice("邀请已过期。关闭本窗口后可重新创建；过期邀请和候选都不提供业务权限。")

    @wndproc
    def procedure(hwnd, message, wparam, lparam):
        nonlocal start_bridge, chosen_address
        if message == 0x113:
            try: poll()
            except Exception: notice("无法核对当前配对状态；没有自动确认或重试。")
            return 0
        if message == 0x111:
            action = wparam & 0xFFFF
            # Some original confirmations have separate top-level windows. Do not make the main window
            # a return target while a command (including any nested confirmation, menu or QR loop) is active.
            restore = return_class and hwnd == window and user.RemovePropW(hwnd, READY) == 1
            if return_class and user.GetPropW(hwnd, READY): return 0
            try:
                if action in (101, 103, 104, 107): require_visible()
                if 200 <= action < 200 + len(choices) and (wparam >> 16) & 0xffff == 0:  # BN_CLICKED
                    checkbox = choices[action - 200][1]
                    if lparam == checkbox and user.SendMessageW(checkbox, 0xF0, 0, 0) == 1:
                        try:
                            review_choices(selected_scopes())
                        except PlatformError:
                            user.SendMessageW(checkbox, 0xF1, 0, 0)  # undo only this new check; keep prior choices
                            raise
                    if lparam == checkbox: budget_for_selection()
                elif action == 110 and (wparam >> 16) & 0xffff == 0x300:  # EN_CHANGE: manual edit restores original explicit path.
                    chosen_address = None
                elif action == 109 and host.thread is None:
                    from desktop_pairing_addresses import local_choices
                    rows = local_choices()
                    if not rows:
                        notice("没有可明确选择的活动 Wi-Fi / 以太网私网地址。请核对电脑和手机所用网络，或手动填写准确地址。")
                        return 0
                    menu, rectangle = user.CreatePopupMenu(), w.RECT()
                    if not menu: raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        for index, item in enumerate(rows):
                            if not user.AppendMenuW(menu, 0, index + 1, item.label.replace("&", "&&")):
                                raise ctypes.WinError(ctypes.get_last_error())
                        if not user.GetWindowRect(controls["choose_address"], ctypes.byref(rectangle)): raise ctypes.WinError(ctypes.get_last_error())
                        picked = user.TrackPopupMenuEx(menu, 0x100 | 0x80, rectangle.left, rectangle.bottom, hwnd, None)
                    finally: user.DestroyMenu(menu)
                    if 1 <= picked <= len(rows):
                        row = rows[picked - 1]
                        if not user.SetWindowTextW(controls["host"], row.address): raise ctypes.WinError(ctypes.get_last_error())
                        chosen_address = row  # After synchronous EN_CHANGE; preserve exact adapter identity.
                        notice("已选 " + row.label + "。尚未监听或探测手机；请确认手机也可达该网络，再创建邀请。")
                elif action == 101:
                    invitation = host.start(selected_host(), control_port=int(text(controls["control"], 5)), bulk_port=int(text(controls["bulk"], 5)))
                    user.SetWindowTextW(invitation_edit, json.dumps(invitation, ensure_ascii=False, separators=(",", ":")))
                    for key in ("choose_address", "host", "control", "bulk", "create", "issued"):
                        user.EnableWindow(controls[key], False)
                    notice("一次性邀请有效 120 秒。在手机选「我的设备 → 配对设备 → 对方已发邀请」，提交后回电脑核对指纹与范围。")
                elif action == 111 and host.thread is None and controller.invitation is None:
                    controller.config_store.current()
                    from windows_issued_scope import show as show_issued_scope
                    show_issued_scope(controller.receiver, lambda: host.thread is None and controller.invitation is None
                                      and controller.config_store.current() is None)
                elif action == 102:
                    user.SetFocus(invitation_edit)
                    user.SendMessageW(invitation_edit, 0xB1, 0, -1)  # select ALL actual invitation
                    user.SendMessageW(invitation_edit, 0x301, 0, 0)  # owner-clicked WM_COPY, no web/token bridge
                    notice("已复制原回执只读连接信息；在手机的显式恢复入口填写地址，不要当新邀请导入。" if host.recovery is not None else
                           "已复制一次性邀请。在手机选「我的设备 → 配对设备 → 对方已发邀请」并粘贴，然后回电脑核对指纹和范围。请勿公开分享。")
                elif action == 106:
                    if controller.invitation is None:
                        raise PlatformError("先创建本窗口的一次性邀请")
                    from pairing_qr import show_invitation_qr
                    show_invitation_qr(controller.invitation, owner=hwnd)
                elif action == 108 and extra_options and controller.pending is None and host.recovery is None:
                    menu, point = user.CreatePopupMenu(), w.POINT()
                    if not menu: raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        for index, item in enumerate(extra_options):
                            # Escape ampersands in real paths; a mnemonic is not part of a reviewed pathname.
                            if not user.AppendMenuW(menu, 0x08 if item["id"] in extra_selected else 0, index + 1, scope_label(item).replace("&", "&&")):
                                raise ctypes.WinError(ctypes.get_last_error())
                        if not user.GetCursorPos(ctypes.byref(point)): raise ctypes.WinError(ctypes.get_last_error())
                        picked = user.TrackPopupMenuEx(menu, 0x100 | 0x80, point.x, point.y, hwnd, None)
                    finally: user.DestroyMenu(menu)
                    if 1 <= picked <= len(extra_options):
                        key = extra_options[picked - 1]["id"]
                        if key in extra_selected:
                            extra_selected.remove(key)
                        else:
                            review_choices(selected_scopes() + [key])
                            extra_selected.add(key)
                        budget_for_selection()
                        user.SetWindowTextW(controls["more_scopes"], f"其他范围（另有 {len(extra_options)} 项，已选 {len(extra_selected)}）")
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
                    proof = confirm_local_pairing_recovery(review.summary, anchor=hwnd)
                    require_visible()
                    address = host.start_recovery(selected_host(), review, proof)
                    fixed = review.record["originalCommit"]
                    value = ("仅恢复原回执，非新邀请。请在手机原配对记录的恢复入口手动填写：\r\n"
                             + "Host: " + address[0] + "\r\nPort: " + str(address[1]) + "\r\n"
                             + "Invite: " + fixed["inviteId"] + "\r\n本机指纹: " + controller.identity.certificate_fingerprint
                             + "\r\n手机指纹: " + fixed["peerFingerprint"] + "\r\n30秒后自动关闭；当前撤销/到期/scope变动仍会拒绝。")
                    user.SetWindowTextW(invitation_edit, value)
                    user.SetWindowTextW(candidate_edit, review.summary)
                    for key in ("choose_address", "host", "control", "bulk", "create", "issued", "qr", "recover", "budget", "minutes", "history"):
                        user.EnableWindow(controls[key], False)
                    user.SetWindowTextW(controls["copy"], "复制恢复地址")
                    freeze_scopes()
                    user.EnableWindow(review_button, False); user.EnableWindow(start_button, False)
                    notice("仅开放原事务30秒只读回执通道；不会写配置、启动业务或重放旧任务。地址本身不授信，仍须原双向TLS指纹。")
                elif action == 103:
                    if candidate is None:
                        raise PlatformError("先由手机导入邀请并提交候选")
                    review = controller.prepare_review(candidate, selected_scopes(), int(text(controls["budget"], 3)), int(text(controls["minutes"], 2)),
                                                       user.SendMessageW(controls["history"], 0xF0, 0, 0) == 1)
                    proof = confirm_local_pairing(review.summary, anchor=hwnd)
                    require_visible()
                    host.confirm(review, proof)
                    user.EnableWindow(review_button, False)
                    freeze_scopes()
                    for control in [controls["budget"], controls["minutes"], controls["history"]]:
                        user.EnableWindow(control, False)
                    notice("电脑已确认。请保留本窗口，到手机「我的设备 → 配对进展」点这次的「继续原配对」。")
                elif action in (301, 302) and offer_local_receipts:
                    key, other = ("phone_work", "phone_receipts") if action == 302 else ("phone_receipts", "phone_work")
                    if user.SendMessageW(controls[key], 0xf0, 0, 0) == 1: user.SendMessageW(controls[other], 0xf1, 0, 0)
                elif action == 104:
                    controller.config_store.current()
                    host.business_reservations()  # actual held sockets, not guessed port numbers
                    with_receipts = offer_local_receipts and user.SendMessageW(controls["phone_receipts"], 0xF0, 0, 0) == 1
                    with_work = offer_local_receipts and user.SendMessageW(controls["phone_work"], 0xf0, 0, 0) == 1
                    if with_work and with_receipts: raise PlatformError("请选择一个本机入口；没有启动")
                    start_bridge = "with_phone_work" if with_work else "with_local_receipts" if with_receipts else True
                    user.DestroyWindow(hwnd)
                elif action == 105:
                    user.DestroyWindow(hwnd)
            except (ValueError, PermissionError, PlatformError) as error:
                notice(str(error))
            except Exception:
                notice("本机配对操作未确认。请检查原状态；不要自动重试或重放旧操作。")
            finally:
                if restore and user.IsWindow(hwnd): user.SetPropW(hwnd, READY, 1)
            return 0
        if message == 2:
            if return_class: user.RemovePropW(hwnd, READY)
            user.PostQuitMessage(0); return 0
        return user.DefWindowProcW(hwnd, message, wparam, lparam)

    # Create hidden, then use the chosen HWND's monitor, physical DPI, title bar and taskbar reservation.
    # A fixed 100px deduction used to reject every normal 720p work area even when the controls would fit.
    style = OWNER_STYLE
    return_class = window_class(installed_owner)
    class_name = return_class or ("PocketWorldDevicePairing-" + __import__("uuid").uuid4().hex)
    klass = WindowClass(0, procedure, 0, 0, instance, None, None, w.HBRUSH(6), None, class_name)
    if not user.RegisterClassW(ctypes.byref(klass)):
        raise ctypes.WinError(ctypes.get_last_error())
    window = user.CreateWindowExW(0, class_name, "PocketWorld · 本机设备配对与授权", style,
                                  USE_DEFAULT, 0, 200, 100, None, None, instance, None)
    if not window:
        user.UnregisterClassW(class_name, instance); raise ctypes.WinError(ctypes.get_last_error())
    def control(kind, value, x, y, ww, hh, ident=0, extra=0):
        return surface.child(instance, kind, value, x, y, ww, hh, ident, extra, 0x200 if kind == "EDIT" else 0)
    try:
        surface = OwnerSurface(user, window, style, (1020, 800), (720, 440))
        width, height = surface.width, surface.height
        compact = height < 680
        control("STATIC", f"本机配对 · 电脑编号末六位 {local_code} · 打开此页不会启动服务。", 16, 12, width - 32, 22)
        controls["choose_address"] = control("BUTTON", "选择本机地址", 16, 39, 140, 28, 109, 0x10000)
        configured = controller.manager.config.get("devices", {}).get("listen", {})
        initial_host = configured.get("host", "")
        if initial_host in {"0.0.0.0", "::"}: initial_host = ""
        controls["host"] = control("EDIT", initial_host, 165, 39, 210, 28, 110, 0x10000)
        control("STATIC", "控制 / 文件端口", 388, 42, 145, 24)
        controls["control"] = control("EDIT", str(configured.get("controlPort") or 8768), 535, 39, 70, 28, extra=0x10000)
        controls["bulk"] = control("EDIT", str(configured.get("bulkPort") or 8769), 615, 39, 70, 28, extra=0x10000)
        controls["create"] = control("BUTTON", "创建一次性邀请", 16, 77, 190, 30, 101, 0x10000)
        controls["copy"] = control("BUTTON", "复制邀请", 220, 77, 145, 30, 102, 0x10000)
        controls["qr"] = control("BUTTON", "显示二维码", 380, 77, 145, 30, 106, 0x10000)
        controls["recover"] = control("BUTTON", "恢复原已提交回执", 540, 77, 170, 30, 107, 0x10000)
        control("STATIC", "新配对：选择范围（默认不选，不列本机私有目录）", 16, 116, width - 210, 24)
        controls["issued"] = control("BUTTON", "查看已签授权（只读）", width - 189, 110, 160, 27, 111, 0x10000)
        # The management-only receiver enumerates workspaces before the desktop.
        # Keep actual desktop scope reachable on a laptop without preselecting it.
        options = sorted(controller.scope_options(), key=lambda item: 0 if item["id"] == pair_identity.OPTION else
                         1 if item["id"] == "desktop" else 2 if item["id"].startswith(read_pairing.PREFIX) else 3)
        visible = min(8, max(1, (height - (445 if compact else 570)) // 27))
        if compact: visible = min(visible, 6)
        for index, option in enumerate(options[:visible]):
            checkbox = control("BUTTON", scope_label(option).replace("&", "&&"), 20, 143 + 24 * index, width - 60, 24, 200 + index, 0x3 | 0x10000)
            choices.append((option, checkbox))
        bottom = height - 105
        field_y = min(350, 151 + 24 * max(1, len(choices)))
        extra_options = options[visible:]
        if extra_options:
            controls["more_scopes"] = control("BUTTON", f"其他范围（另有 {len(extra_options)} 项，已选 0）", 20, field_y, width - 60, 26, 108, 0x10000)
            field_y += 30
        control("STATIC", "工具总额度", 16, field_y, 85, 24)
        controls["budget"] = control("EDIT", "20", 105, field_y - 2, 54, 27, extra=0x10000)
        control("STATIC", "本次授权分钟", 175, field_y, 102, 24)
        controls["minutes"] = control("EDIT", "60", 281, field_y - 2, 54, 27, extra=0x10000)
        controls["history"] = control("BUTTON", "另允许24小时查原结果（见核对范围）", 355, field_y - 2, width - 371, 27, 300, 0x3 | 0x10000)
        # Preserve BOTH complete scrollable readouts; in a short viewport they share a row.
        invite_body = "邀请尚未创建。地址需由手机可达；不会自动打开公开HTTP或把长期令牌交给页面。"
        candidate_body = "等待手机候选。仅有邀请、候选或pending pin均不能使用业务工具。"
        reading = 0x4 | 0x800 | 0x200000 | 0x10000  # multiline, read-only, vertical scroll, tab stop
        if compact:
            (ix, iw), (cx, cw) = columns(width, 2)
            label_y, pane_y = field_y + 30, field_y + 53
            pane_h = bottom - 17 - pane_y
            if pane_h < 48: raise PlatformError("没有足够空间同时显示原邀请与候选，未开放操作。")
            control("STATIC", "本次邀请", ix, label_y, iw, 22)
            control("STATIC", "手机候选与指纹", cx, label_y, cw, 22)
            invitation_edit = control("EDIT", invite_body, ix, pane_y, iw, pane_h, extra=reading)
            candidate_edit = control("EDIT", candidate_body, cx, pane_y, cw, pane_h, extra=reading)
        else:
            invitation_edit = control("EDIT", invite_body, 16, field_y + 34, width - 32, 86, extra=reading)
            candidate_edit = control("EDIT", candidate_body, 16, field_y + 128, width - 32, max(54, bottom - field_y - 182), extra=reading)
        (rx, rw), (sx, sw), (ex, ew) = columns(width, 3)
        review_button = control("BUTTON", "核对指纹与准确授权", rx, bottom - 8, rw, 31, 103, 0x10000)
        start_button = control("BUTTON", "启动桥接（不重放旧任务）", sx, bottom - 8, sw, 31, 104, 0x10000)
        control("BUTTON", "关闭，不启动服务", ex, bottom - 8, ew, 31, 105, 0x10000)
        user.EnableWindow(review_button, False); user.EnableWindow(start_button, False)
        status = control("STATIC", "选择手机也能访问的本机地址，或手动填写，再创建一次性邀请。选地址不会监听；关闭窗口不会撤销已确认的配对。", 16, bottom + 28, width - 50, 44 if offer_local_receipts else 52)
        if offer_local_receipts:
            half = (width - 50) // 2
            controls["phone_receipts"] = control("BUTTON", "保留设备工作回执（只读）", 16, bottom + 72, half, 23, 301, 0x3 | 0x10000)
            controls["phone_work"] = control("BUTTON", "保留「交给手机」入口（仅文字）", 16 + half, bottom + 72, half, 23, 302, 0x3 | 0x10000)
        if return_class: user.SetPropW(window, READY, 1)  # absent on unsupported installs; failure leaves normal pairing intact
        user.SetTimer(window, 1, 500, None)
        user.ShowWindow(window, 5); user.SetForegroundWindow(window)
        message = w.MSG()
        while True:
            result = user.GetMessageW(ctypes.byref(message), None, 0, 0)
            if result == 0: break
            if result < 0: raise ctypes.WinError(ctypes.get_last_error())
            if message.message == 0x100 and message.wParam == 9 and user.IsDialogMessageW(window, ctypes.byref(message)): continue
            user.TranslateMessage(ctypes.byref(message)); user.DispatchMessageW(ctypes.byref(message))
        return start_bridge
    finally:
        user.KillTimer(window, 1)
        user.DestroyWindow(window)
        if surface: surface.close()
        user.UnregisterClassW(class_name, instance)
