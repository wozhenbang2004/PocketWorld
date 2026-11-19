"""Local, non-serving front door to the existing installed Windows Companion."""
from __future__ import annotations

from contextlib import ExitStack
import ctypes
from ctypes import wintypes as w
import hashlib
import ipaddress
import ntpath
import subprocess
import sys
import time

from desktop_sessions import WindowsDesktopHealth
from desktop_stop_receipt import owner_status
from platform_backend import PlatformError
from platform_windows import local_root
from windows_installation import WindowsInstalledContext, _object, same_path


def _dialog(heading, body, choices, *, retire=None):
    # Windows common-controls v6 command links. This is navigation, never a
    # PairGrant, physical confirmation proof or alternative approval dialog.
    class Button(ctypes.Structure):
        _pack_ = 1
        _fields_ = [("id", ctypes.c_int), ("label", w.LPCWSTR)]

    class Config(ctypes.Structure):
        _pack_ = 1  # commctrl.h packs TASKDIALOGCONFIG/BTN at one-byte alignment.
        _fields_ = [("size", w.UINT), ("owner", w.HWND), ("instance", w.HINSTANCE), ("flags", w.UINT),
                    ("common", w.UINT), ("title", w.LPCWSTR), ("icon", ctypes.c_void_p),
                    ("heading", w.LPCWSTR), ("body", w.LPCWSTR), ("count", w.UINT),
                    ("buttons", ctypes.POINTER(Button)), ("default", ctypes.c_int), ("radioCount", w.UINT),
                    ("radios", ctypes.POINTER(Button)), ("defaultRadio", ctypes.c_int), ("verification", w.LPCWSTR),
                    ("expanded", w.LPCWSTR), ("expandLabel", w.LPCWSTR), ("collapseLabel", w.LPCWSTR),
                    ("footerIcon", ctypes.c_void_p), ("footer", w.LPCWSTR), ("callback", ctypes.c_void_p),
                    ("callbackData", w.LPARAM), ("width", w.UINT)]

    table = (Button * len(choices))(*(Button(1100 + index, label) for index, (_, label) in enumerate(choices)))
    config = Config()
    config.size, config.flags, config.common = ctypes.sizeof(config), 0x10 | 0x08, 0x20  # command links, cancel, Close
    config.title, config.heading, config.body = "PocketWorld · 电脑伴侣", heading, body
    config.count, config.buttons, config.default = len(choices), table if choices else None, 8  # IDCLOSE, Enter never starts a service.
    if retire is not None:
        # Local result views close on loss of their original manager/owner scope;
        # never refresh a peer or dispatch a command from a timer callback.
        post = ctypes.WinDLL("user32").PostMessageW
        post.argtypes, post.restype = [w.HWND, w.UINT, w.WPARAM, w.LPARAM], w.BOOL
        callback_type = ctypes.WINFUNCTYPE(ctypes.c_long, w.HWND, w.UINT, w.WPARAM, w.LPARAM, ctypes.c_ssize_t)
        @callback_type
        def on_notice(hwnd, message, _wparam, _lparam, _context):
            if message == 4:  # TDN_TIMER
                try: ending = retire()
                except Exception: ending = True
                if ending: post(hwnd, 0x400 + 102, 8, 0)  # TDM_CLICK_BUTTON / IDCLOSE
            return 0
        config.flags |= 0x0800  # TDF_CALLBACK_TIMER
        config.callback = ctypes.cast(on_notice, ctypes.c_void_p)
    show = ctypes.WinDLL("comctl32").TaskDialogIndirect
    show.argtypes = [ctypes.POINTER(Config), ctypes.POINTER(ctypes.c_int), ctypes.c_void_p, ctypes.c_void_p]
    show.restype = ctypes.c_long
    selected = ctypes.c_int()
    if show(ctypes.byref(config), ctypes.byref(selected), None, None) != 0:
        raise PlatformError("系统未能打开电脑伴侣操作页，这个窗口没有派发操作")
    index = selected.value - 1100
    return choices[index][0] if 0 <= index < len(choices) else None


def _folder():
    # Use the Windows folder picker; never infer a workspace from cwd/home.
    class Browse(ctypes.Structure):
        _fields_ = [("owner", w.HWND), ("root", ctypes.c_void_p), ("display", w.LPWSTR), ("title", w.LPCWSTR),
                    ("flags", w.UINT), ("callback", ctypes.c_void_p), ("data", w.LPARAM), ("image", ctypes.c_int)]
    shell, ole = ctypes.WinDLL("shell32"), ctypes.WinDLL("ole32")
    ole.CoInitializeEx.argtypes, ole.CoInitializeEx.restype = [ctypes.c_void_p, w.DWORD], ctypes.c_long
    ole.CoTaskMemFree.argtypes, ole.CoTaskMemFree.restype = [ctypes.c_void_p], None
    ole.CoUninitialize.argtypes, ole.CoUninitialize.restype = [], None
    if ole.CoInitializeEx(None, 2) not in (0, 1):  # S_OK or S_FALSE, balanced below.
        raise PlatformError("系统文件夹选择器当前不可用，未选择工作区")
    try:
        browse = shell.SHBrowseForFolderW
        browse.argtypes, browse.restype = [ctypes.POINTER(Browse)], ctypes.c_void_p
        path_for = shell.SHGetPathFromIDListEx
        path_for.argtypes, path_for.restype = [ctypes.c_void_p, w.LPWSTR, w.DWORD, w.DWORD], w.BOOL
        label = ctypes.create_unicode_buffer(260)
        config = Browse(None, None, ctypes.cast(label, w.LPWSTR), "选择本地工作文件夹（此步不向手机授权）", 0x01 | 0x40 | 0x200, None, 0, 0)
        pidl = browse(ctypes.byref(config))
        if not pidl: return None
        try:
            folder = ctypes.create_unicode_buffer(32768)
            if not path_for(pidl, folder, len(folder), 0): raise PlatformError("只支持本机普通文件夹，不支持虚拟或网络位置")
            return local_root(folder.value)
        finally:
            ole.CoTaskMemFree(pidl)
    finally:
        ole.CoUninitialize()


def _present(context, root, filename):
    return context.backend.private_leaf_present(root, filename, context.sid, maximum=262144)


def _original_in_use(context):
    # Same name as desktop_worker._mutex; OpenMutex never creates or acquires it.
    name = "Local\\PocketWorldCompanion-" + hashlib.sha256(context.jobs.casefold().encode()).hexdigest()
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenMutexW.argtypes, kernel.OpenMutexW.restype = [w.DWORD, w.BOOL, w.LPCWSTR], w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    handle = kernel.OpenMutexW(0x00100000, False, name)
    if not handle:
        if ctypes.get_last_error() == 2: return False
        raise PlatformError("无法核对本安装的原伴侣占用状态，不会另起一个")
    kernel.CloseHandle(handle)
    return True


def _local_listeners(config, devices):
    # A one-click restart must be reviewable here. Never project relay tokens,
    # peer records or TLS key paths (or silently activate saved outbound routes).
    listen = devices.get("listen")
    if not isinstance(listen, dict) or set(listen) != {"host", "controlPort", "bulkPort"} or devices.get("routes", []) != []:
        return None
    try:
        local = config.get("listen", "127.0.0.1")
        if local != "localhost" and (not isinstance(local, str) or not ipaddress.ip_address(local).is_loopback):
            return None
        ip = ipaddress.ip_address(listen["host"])
        ports = [listen["controlPort"], listen["bulkPort"], config.get("port", 8766)]
        if (not isinstance(listen["host"], str) or "%" in listen["host"] or ip.is_unspecified or ip.is_loopback or ip.is_multicast
                or any(type(port) is not int or not 1 <= port <= 65535 for port in ports) or ports[0] == ports[1]):
            return None
        def endpoint(host, port): return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
        return (endpoint(ip.compressed, ports[0]), endpoint(ip.compressed, ports[1]), endpoint(local, ports[2]))
    except (ValueError, TypeError):
        return None


def _read(context):
    context.check_current_pointer()
    if _present(context, context.home, "installation-transaction.json"):
        raise PlatformError("安装事务尚未完成，请用原安装包修复；不会自行改动安装")
    health = WindowsDesktopHealth().probe(session_only=True)
    session = health.get("sessionId") if health.get("available") is True else None
    facts = {"interactive": session, "configured": False, "partial": False, "devices": False, "desktop": False,
             "busy": False, "stop": ("none",), "settings": None, "listeners": None}
    config_present = _present(context, context.state, "companion.json")
    token_present = _present(context, context.state, "companion-token.txt")  # Metadata only; no token bytes read.
    if config_present != token_present:
        facts["partial"] = True
    elif config_present:
        raw = context._raw(context.state, "companion.json", 262144)
        config = _object(raw); devices, desktop = config.get("devices"), config.get("desktop", {})
        if (not isinstance(devices, dict) or not isinstance(desktop, dict)
                or type(devices.get("enabled", False)) is not bool or type(desktop.get("enabled", False)) is not bool
                or not same_path(devices.get("identityDir"), context.identity_dir)
                or not same_path(config.get("stateDir"), context.jobs)
                or not same_path(config.get("tokenFile"), ntpath.join(context.state, "companion-token.txt"))):
            raise PlatformError("已保存的配置不再属于这个安装，未启用配对或启动")
        facts.update(configured=True, devices=devices.get("enabled") is True, desktop=desktop.get("enabled") is True,
                     settings=hashlib.sha256(raw).hexdigest(), listeners=_local_listeners(config, devices), busy=_original_in_use(context),
                     stop=owner_status(context))
    return facts


def _page(context, facts, notice):
    version = str(context.current["version"])
    body = f"电脑伴侣 {version}" + (" · 未签名开发包" if context.current["allowUnsignedDevelopment"] else "")
    body += "\n" + (f"当前交互桌面：Windows 会话 {facts['interactive']}。" if facts["interactive"] else "当前没有可用的已解锁本机交互桌面。")
    actions = []
    if not facts["interactive"]:
        heading = "回到这台电脑的已解锁桌面"
    elif facts["partial"]:
        heading = "已有部分初始化记录"
        body += "\n请核对本机原配置；这里不会覆盖原身份或重新生成凭据。"
    elif not facts["configured"]:
        heading = "先选一个本机工作文件夹"
        body += "\n只建立本机私有配置，创建或沿用原安装身份；不会开放网络、启动服务或授予手机权限。"
        actions.append(("Initialize", "选择工作文件夹\n选好后再确认初始化。"))
    elif facts["busy"]:
        heading = "原伴侣正在使用本机工作区"
        body += "\n可能正在配对或运行服务，不能据此确认手机已经连接。请回到原配对窗口，或在手机「我的设备」查看这台电脑。不会另起一个。"
        body += "\n如果启动时保留了「设备工作回执」，可在 Windows 时钟旁的通知区域（包括折叠图标）中打开，区分本机接单与交给手机的工作。"
    else:
        heading = "已有连接设置" if facts["devices"] else "可以与手机配对"
        body += "\n桌面能力" + ("已配置" if facts["desktop"] else "未启用") + "，不代表任何手机已获当前权限。"
        if facts["devices"] and facts["listeners"]:
            actions.append(("Serve", "启动已有配对的连接服务\n先核对将开放的本机地址；不代替手机连接、授权或接管。"))
        elif facts["devices"]:
            body += "\n现有服务地址或外连设置不能在这里完整核对。请在原管理配置中逐项核对；此页不会代为启动。"
        actions.append(("PairDevices", "打开本机配对与范围核对\n在原窗口创建一次性邀请，双方分别确认；不会提前开通桌面。"))
    if facts["configured"] and facts["interactive"]:
        status = facts["stop"]
        if status[0] == "acknowledged":
            stamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(status[1] / 1000))
            body += f"\n原桌面执行器已在 {stamp} 处理这次本机暂停。"
            body += "该次驱动进程清理已核实。" if status[2] else "该次驱动进程清理未核实。"
            body += " 暂停不是已派发输入被撤回的证明。"
        elif status[0] == "requested":
            body += "\n本机已保存暂停请求。尚无这次原执行器的处理回执；不会再次派发，也不能据此判断先前输入的结果。"
        elif status[0] == "unidentified":
            body += "\n本机已有暂停标记，未能核对原请求编号；不替换标记，也不将它当作驱动已停止的证明。"
        elif status[0] == "unreadable":
            body += "\n未能核对本机暂停记录。请刷新或到原本机管理入口检查；不假定桌面正在运行或已停。"
        elif facts["desktop"] and facts["busy"]:
            actions.insert(0, ("StopDesktop", "暂停这台电脑的远程桌面\n只请求停止本机桌面输入与观看；不取消手机或电脑上的其他工作。"))
        if status[0] in {"acknowledged", "requested", "unidentified"}:
            body += " 配对或重启不会自行恢复桌面。"
    if facts["configured"] and facts["interactive"]:
        actions.append(("ProjectSource", "核对一个本机 Git 项目的来源\n只从本机已登记工作区读取提交，不启动服务或项目工作。"))
    if notice: body += "\n\n" + notice
    return _dialog(heading, body, [*actions, ("Refresh", "重新读取本机状态")])


def _run_original(context, action, workspace=None, reviewed_config=None, *, phone_receipts=False):
    if (action not in {"Initialize", "PairDevices", "Serve", "StopDesktop"} or (action == "Initialize") != bool(workspace)
            or (action == "Serve") != bool(reviewed_config) or phone_receipts and action != "Serve"):
        raise PlatformError("未选择受支持的本机原动作")
    # Hold exact manifest-pinned script bytes, with write/delete sharing denied,
    # while the fixed launcher itself checks the full release, signer and config.
    with ExitStack() as held:
        for filename in ("Launch-Companion.ps1", "Companion-InstallState.ps1"):
            expected = context.owner_entry_files.get("tools/" + filename)
            file = held.enter_context(context.backend.open_reader(context.home, filename, maximum=1048576))
            digest = hashlib.sha256()
            for block in iter(lambda: file.read(262144), b""): digest.update(block)
            if not expected or file.size != expected["bytes"] or digest.hexdigest() != expected["sha256"].lower():
                raise PlatformError("固定管理入口与当前版本不符，请用原可信安装包修复")
        context.check_current_pointer()
        system_dir = ctypes.WinDLL("kernel32", use_last_error=True).GetSystemDirectoryW
        system_dir.argtypes, system_dir.restype = [w.LPWSTR, w.UINT], w.UINT
        system = ctypes.create_unicode_buffer(32768)
        if not 0 < system_dir(system, len(system)) < len(system): raise PlatformError("本机系统 PowerShell 不可用")
        command = [ntpath.join(system.value, "WindowsPowerShell", "v1.0", "powershell.exe"), "-NoProfile", "-File",
                   ntpath.join(context.home, "Launch-Companion.ps1"), "-Action", action]
        if workspace: command += ["-Workspace", workspace]
        if reviewed_config: command += ["-ReviewedConfigSha256", reviewed_config]
        if phone_receipts: command += ["-PhoneReceipts"]
        return subprocess.run(command, cwd=context.home, shell=False, check=False).returncode


def open_installed_desk():
    try:
        context = WindowsInstalledContext(current_executable=sys.executable)
        notice = ""
        while True:
            facts = _read(context)
            action = _page(context, facts, notice)
            if action is None: return
            if action == "Refresh": notice = ""; continue
            if action == "ProjectSource":
                from windows_git_source_entry import open_source
                open_source(context, facts["settings"])
                notice = ""; continue
            workspace, phone_receipts = None, False
            if action == "Initialize":
                workspace = _folder()
                if workspace is None: continue
                try: shared = ntpath.commonpath([workspace.casefold(), context.home.casefold()])
                except ValueError: shared = None  # Different local drives.
                if shared in {workspace.casefold(), context.home.casefold()}:
                    notice = "请选择不包含伴侣私有安装目录、也不在该目录内的普通工作文件夹。"; continue
                if _dialog("在这台电脑上初始化？", workspace + "\n不会向手机授权，也不会启动服务。", [("yes", "确认初始化")]) != "yes": continue
            if action == "Serve":
                control, bulk, local = facts["listeners"]
                selected = _dialog("按这些已保存的地址启动？", f"已配对设备的控制：{control}\n已配对设备的文件：{bulk}\n仅本机的旧版服务：{local}"
                                   "\n\n手机还须连接；观看和输入均沿用原授权。",
                                   [("yes", "只启动原服务"), ("receipts", "启动并保留「设备工作回执」入口\n在本次 Windows 通知区域随时打开；本机接单在本机读，派给手机的工作才向手机查询。")])
                if selected not in {"yes", "receipts"}: continue
                phone_receipts = selected == "receipts"
            if action == "StopDesktop":
                if _dialog("暂停这台电脑的远程桌面？",
                           "向本机原执行器请求停止桌面输入与观看。已有输入可能已交给 Windows，不能保证撤回。"
                           "\n不取消命令或仓库任务，不撤销配对，不自动恢复。",
                           [("yes", "确认暂停本机远程桌面")]) != "yes": continue
            if _read(context) != facts:
                notice = "刚才的本机状态已经改变，未执行。请重新核对。"; continue
            code = _run_original(context, action, workspace, facts["settings"] if action == "Serve" else None, phone_receipts=phone_receipts)
            if action == "StopDesktop":
                notice = ("只读取本机状态即可查看回执；没有恢复或重放任何操作。" if code == 0
                          else "原管理入口未确认请求完成。请核对本机记录；没有自动重试。")
                continue
            notice = ("原管理动作已返回。这里显示重新读取的本机状态，手机是否已连接请在原设备核对。" if code == 0
                      else "原管理动作未确认完成。请查看原控制台；没有自动重试或恢复。")
    except (OSError, ValueError, PlatformError) as error:
        # Readable even when Explorer closes the frozen console. No fallback
        # executable, external configuration, unsigned installer or auto-retry.
        user = ctypes.WinDLL("user32")
        user.MessageBoxW.argtypes, user.MessageBoxW.restype = [w.HWND, w.LPCWSTR, w.LPCWSTR, w.UINT], ctypes.c_int
        user.MessageBoxW(None, "未能打开原安装或完成所选步骤。请从原安装入口核对，必要时用原可信安装包修复。\n\n" + str(error)[:350],
                         "PocketWorld · 电脑伴侣", 0x30)
        raise SystemExit(1)
