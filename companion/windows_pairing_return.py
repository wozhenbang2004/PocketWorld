"""Local owner navigation to ONE original installed pairing window. No desktop search, GUI input or pairing action."""
from contextlib import ExitStack
import ctypes as c
from ctypes import wintypes as w
import hashlib
import ntpath
import os
import re

from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from windows_installation import same_path


READY = "PocketWorld.Local.OwnerPairing.CanReturn.v1"


def supported(ctx):
    return (ctx.current.get("allowUnsignedDevelopment") is False and all(
        isinstance(value, str) and re.fullmatch(pattern, value) is not None for pattern, value in (
            ("[a-f0-9]{32}", ctx.install_state.get("installId")),
            ("[A-Fa-f0-9]{64}", ctx.current.get("manifestSha256")),
            ("[A-Fa-f0-9]{40}", ctx.current.get("signerThumbprint")))))


def bad(): raise PlatformError("Original installed pairing window cannot be identified")


def active(ctx):
    health=WindowsDesktopHealth().probe(session_only=True); sid=health.get("sessionId")
    if health.get("available") is not True or type(sid) is not int or sid<=0 or not supported(ctx): bad()
    ctx.check_current_pointer()
    raw="\0".join(("pocketworld.local.owner-pairing-window.v1",ctx.sid,str(sid),ctx.install_state["installId"],ctx.current["manifestSha256"].lower()))
    return "PocketWorldOriginalPairing-"+hashlib.sha256(raw.encode("utf-8")).hexdigest(),sid


def window_class(ctx):
    # Old, development and unavailable installs retain their original random local class; pairing itself need not fail.
    if ctx is None: return None
    try: return active(ctx)[0]
    except Exception: return None


def fn(dll,name,args,result):
    f=getattr(dll,name); f.argtypes=args; f.restype=result; return f


def same_owner(k,remote,stack):
    # Token user AND logon generation; no other account's pairing UI is returned. No token is duplicated or exposed.
    adv=c.WinDLL("advapi32",use_last_error=True)
    op=fn(adv,"OpenProcessToken",(w.HANDLE,w.DWORD,c.POINTER(w.HANDLE)),w.BOOL)
    info=fn(adv,"GetTokenInformation",(w.HANDLE,c.c_int,c.c_void_p,w.DWORD,c.POINTER(w.DWORD)),w.BOOL)
    equal=fn(adv,"EqualSid",(c.c_void_p,c.c_void_p),w.BOOL)
    def token(process):
        h=w.HANDLE()
        if not op(process,8,c.byref(h)): bad()    # TOKEN_QUERY only
        stack.callback(k.CloseHandle,h)
        def read(kind):
            needed=w.DWORD(); info(h,kind,None,0,c.byref(needed))
            if c.get_last_error()!=122 or not 16<=needed.value<=8192: bad()
            buf=c.create_string_buffer(needed.value)
            if not info(h,kind,buf,len(buf),c.byref(needed)): bad()
            return buf
        user=read(1); stats=read(10)  # TOKEN_STATISTICS begins with TokenId LUID then AuthenticationId LUID
        return user,stats.raw[8:16]
    own=fn(k,"GetCurrentProcess",(),w.HANDLE)()
    a,la=token(own); b,lb=token(remote)
    if la!=lb or not equal(c.cast(a,c.POINTER(c.c_void_p))[0],c.cast(b,c.POINTER(c.c_void_p))[0]): bad()


def find_or_focus(ctx,*,want=None):
    key,session=active(ctx); u=c.WinDLL("user32",use_last_error=True); k=ctx.backend.api.k
    find=fn(u,"FindWindowExW",(w.HWND,w.HWND,w.LPCWSTR,w.LPCWSTR),w.HWND)
    thread=fn(u,"GetWindowThreadProcessId",(w.HWND,c.POINTER(w.DWORD)),w.DWORD)
    klass=fn(u,"GetClassNameW",(w.HWND,w.LPWSTR,c.c_int),c.c_int)
    visible=fn(u,"IsWindowVisible",(w.HWND,),w.BOOL); enabled=fn(u,"IsWindowEnabled",(w.HWND,),w.BOOL)
    popup=fn(u,"GetLastActivePopup",(w.HWND,),w.HWND); root=fn(u,"GetAncestor",(w.HWND,w.UINT),w.HWND)
    idle=fn(u,"GetPropW",(w.HWND,w.LPCWSTR),w.HANDLE)
    minimized=fn(u,"IsIconic",(w.HWND,),w.BOOL)
    shell=ntpath.join(ctx.release,"app","PocketWorld-Companion.exe")
    hwnd=find(None,None,key,None)
    if not hwnd or find(None,hwnd,key,None): return "no_unique",None
    def original_window():
        pid=w.DWORD(); name=c.create_unicode_buffer(128)
        if (not thread(hwnd,c.byref(pid)) or not pid.value or pid.value==os.getpid() or klass(hwnd,name,len(name))<=0 or name.value!=key
                or root(hwnd,2)!=hwnd or not visible(hwnd)): bad()
        return pid.value
    pid=original_window(); native_session=w.DWORD()
    opens=fn(k,"OpenProcess",(w.DWORD,w.BOOL,w.DWORD),w.HANDLE)
    times=fn(k,"GetProcessTimes",(w.HANDLE,*([c.POINTER(w.FILETIME)]*4)),w.BOOL)
    full=fn(k,"QueryFullProcessImageNameW",(w.HANDLE,w.DWORD,w.LPWSTR,c.POINTER(w.DWORD)),w.BOOL)
    session_of=fn(k,"ProcessIdToSessionId",(w.DWORD,c.POINTER(w.DWORD)),w.BOOL)
    wait=fn(k,"WaitForSingleObject",(w.HANDLE,w.DWORD),w.DWORD)
    with ExitStack() as held:
        proc=opens(0x101000,False,pid)  # SYNCHRONIZE + QUERY_LIMITED_INFORMATION, never process modification
        if not proc: bad()
        held.callback(k.CloseHandle,proc)
        same_owner(k,proc,held)
        a,b,d,e=(w.FILETIME() for _ in range(4))
        path=c.create_unicode_buffer(32768); n=w.DWORD(len(path))
        if (wait(proc,0)!=0x102 or not times(proc,c.byref(a),c.byref(b),c.byref(d),c.byref(e))
                or not session_of(pid,c.byref(native_session)) or native_session.value!=session or not full(proc,0,path,c.byref(n)) or not same_path(path.value,shell)): bad()
        held.enter_context(ctx.backend.directory(ntpath.dirname(shell)))
        file=ctx.backend.api.open(shell); held.callback(k.CloseHandle,file)
        info=ctx.backend.api.info(file)
        if info.attributes & (0x441400 | 0x10) or ntpath.normcase(ctx.backend.api.final(file))!=ntpath.normcase("\\\\?\\"+shell): bad()
        proof=(key,session,hwnd,pid,a.dwHighDateTime,a.dwLowDateTime,
               tuple(ctx.backend.api.identity(info)),info.created.dwHighDateTime,info.created.dwLowDateTime)
        def recheck():
            if (active(ctx)!=(key,session) or original_window()!=pid or wait(proc,0)!=0x102
                    or find(None,None,key,None)!=hwnd or find(None,hwnd,key,None)): bad()
            return idle(hwnd,READY)==1 and bool(enabled(hwnd)) and popup(hwnd)==hwnd
        if not recheck(): return "modal",None  # never restore/dismiss a QR or owner-confirmation dialog
        if want is None: return "found",proof
        if want!=proof: return "changed",None
        # Normal foreground policy only: never attach input threads, simulate keys, send commands or focus child controls.
        if minimized(hwnd):
            restore=fn(u,"ShowWindowAsync",(w.HWND,c.c_int),w.BOOL)
            if not recheck() or not restore(hwnd,9): return "refused",None
        if not recheck(): return "changed",None
        focus=fn(u,"SetForegroundWindow",(w.HWND,),w.BOOL); front=fn(u,"GetForegroundWindow",(),w.HWND)
        if not focus(hwnd) or not recheck() or front()!=hwnd: return "refused",None
        return "foreground",None


def offer(ctx):
    from desktop_owner_entry import _dialog
    try: status,wanted=find_or_focus(ctx)
    except Exception: status,wanted="unknown",None
    if status!="found":
        note=("原配对主窗口暂不接受切换，可能正在处理选择、二维码或独立确认。请从 Windows 任务栏回到原提示；这里不打开或确认它。" if status=="modal" else
              "没有找到唯一、能核实的同安装配对窗口。仅服务正在运行、旧版窗口或原窗口已关闭都不能用忙碌状态代替。不会另开配对或重发邀请。")
        _dialog("未切换本机窗口",note,[]); return False
    def retired():
        try: return active(ctx)!=wanted[:2]
        except Exception: return True
    if _dialog("回到已经打开的本机配对？","只将同一安装在这个 Windows 会话中的原配对主窗口带到前面。不会读出、重建或延长邀请，也不会确认指纹、启动桥接。",
               [("show","回到这一个原窗口")],retire=retired)!="show" or retired(): return False
    try: status,_=find_or_focus(ctx,want=wanted)
    except Exception: status="unknown"
    if status!="foreground":
        _dialog("没有确认原窗口已到前面","窗口、会话或独立确认状态可能已改变，或 Windows 拒绝切换。请在任务栏选择原窗口；没有代你进行配对操作。",[])
    return status=="foreground"
