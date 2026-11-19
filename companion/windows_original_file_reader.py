"""Native plain-text / hexadecimal reader for one already authorized, pinned version.

No shell open, temp document, automatic clipboard write, HTML, file associations or network.
This is a read-only view of original bytes, not an editor or restore operation.
"""
import ctypes as c
from ctypes import wintypes as w
import hashlib
import uuid

from platform_backend import PlatformError
from windows_file_revisions import MAX_BYTES, display_text


def _bad(): raise PlatformError("Original received file could not be displayed completely",409)


def _presentation(raw):
    text=display_text(raw)
    if text is None:
        return "十六进制", "\r\n".join(f"{i:08X}    "+raw[i:i+16].hex(" ").upper() for i in range(0,len(raw),16))
    rows=text.replace("\r\n","\n").replace("\r","\n").split("\n")
    # Break extremely long records for the native EDIT; the displayed line
    # layout is not a newly saved version and is never used to compute its SHA.
    kind="UTF-8 BOM 文本" if raw.startswith(b"\xef\xbb\xbf") else "UTF-8 文本"
    return kind+"（换行按窗口显示）", "\r\n".join(part for line in rows for part in
                 ([line[i:i+240] for i in range(0,len(line),240)] or [""]))


def show(receipt,raw,active):
    if (not isinstance(raw,bytes) or len(raw)>MAX_BYTES or len(raw)!=receipt["bytes"]
            or hashlib.sha256(raw).hexdigest()!=receipt["sha256"]): _bad()
    if not active(): return
    kind,text=_presentation(raw)
    u,g,k=c.WinDLL("user32",use_last_error=True),c.WinDLL("gdi32"),c.WinDLL("kernel32")
    def fn(dll,name,args,result):
        f=getattr(dll,name); f.argtypes,f.restype=args,result; return f
    i,p=c.c_int,c.c_void_p
    proc=c.WINFUNCTYPE(c.c_ssize_t,w.HWND,w.UINT,w.WPARAM,w.LPARAM)
    class WC(c.Structure):
        _fields_=[("style",w.UINT),("proc",proc),("classExtra",i),("windowExtra",i),("instance",w.HINSTANCE),
                  ("icon",w.HICON),("cursor",w.HANDLE),("brush",w.HBRUSH),("menu",w.LPCWSTR),("name",w.LPCWSTR)]
    reg=fn(u,"RegisterClassW",[c.POINTER(WC)],w.WORD)
    unreg=fn(u,"UnregisterClassW",[w.LPCWSTR,w.HINSTANCE],w.BOOL)
    create=fn(u,"CreateWindowExW",[w.DWORD,w.LPCWSTR,w.LPCWSTR,w.DWORD,i,i,i,i,w.HWND,w.HMENU,w.HINSTANCE,p],w.HWND)
    destroy=fn(u,"DestroyWindow",[w.HWND],w.BOOL); exists=fn(u,"IsWindow",[w.HWND],w.BOOL)
    default=fn(u,"DefWindowProcW",[w.HWND,w.UINT,w.WPARAM,w.LPARAM],c.c_ssize_t)
    send=fn(u,"SendMessageW",[w.HWND,w.UINT,w.WPARAM,w.LPARAM],c.c_ssize_t)
    move=fn(u,"MoveWindow",[w.HWND,i,i,i,i,w.BOOL],w.BOOL)
    rect=fn(u,"GetClientRect",[w.HWND,c.POINTER(w.RECT)],w.BOOL)
    focus=fn(u,"SetFocus",[w.HWND],w.HWND)
    timer=fn(u,"SetTimer",[w.HWND,c.c_size_t,w.UINT,p],c.c_size_t)
    get=fn(u,"GetMessageW",[c.POINTER(w.MSG),w.HWND,w.UINT,w.UINT],i)
    translate=fn(u,"TranslateMessage",[c.POINTER(w.MSG)],w.BOOL)
    dispatch=fn(u,"DispatchMessageW",[c.POINTER(w.MSG)],c.c_ssize_t)
    key=fn(u,"GetKeyState",[i],c.c_short)
    screen=fn(u,"GetSystemMetrics",[i],i)
    stock=fn(g,"GetStockObject",[i],w.HANDLE)
    show_window=fn(u,"ShowWindow",[w.HWND,i],w.BOOL)
    set_text=fn(u,"SetWindowTextW",[w.HWND,w.LPCWSTR],w.BOOL)
    length=fn(u,"GetWindowTextLengthW",[w.HWND],i)
    instance=fn(k,"GetModuleHandleW",[w.LPCWSTR],w.HINSTANCE)(None)
    cursor=fn(u,"LoadCursorW",[w.HINSTANCE,p],w.HANDLE)(None,p(32512))
    header,edit,foot=None,None,None
    def size(hwnd):
        if not header or not edit or not foot: return
        area=w.RECT()
        if not rect(hwnd,c.byref(area)): return
        dpi=96
        if hasattr(u,"GetDpiForWindow"): dpi=fn(u,"GetDpiForWindow",[w.HWND],w.UINT)(hwnd) or dpi
        pad=max(8,12*dpi//96); top=48*dpi//96; bottom=42*dpi//96
        width=max(1,area.right-pad*2); body=max(1,area.bottom-2*pad-top-bottom)
        move(header,pad,pad,width,top,True); move(edit,pad,pad+top,width,body,True)
        move(foot,pad,pad+top+body+4,width,bottom,True)
    @proc
    def handler(hwnd,msg,wp,lp):
        if msg==5: size(hwnd); return 0
        if msg==7 and edit: focus(edit); return 0
        if msg==0x113:
            try: allowed=active()
            except Exception: allowed=False
            if not allowed: destroy(hwnd)
            return 0
        if msg==0x10: destroy(hwnd); return 0
        return default(hwnd,msg,wp,lp)
    name="PocketWorldOriginalReceivedFile"+uuid.uuid4().hex
    wc=WC(0,handler,0,0,instance,None,cursor,6,None,name)  # COLOR_WINDOW+1; system high-contrast colors
    if not reg(c.byref(wc)): _bad()
    hwnd=None
    try:
        hwnd=create(0,name,"PocketWorld · 当时收到的原文件",0x02cf0000,-2147483648,0,
                    min(880,max(360,screen(0)-40)),min(620,max(260,screen(1)-80)),None,None,instance,None)
        if not hwnd: _bad()
        path=receipt["path"]; path=path if len(path)<=112 else "…"+path[-111:]
        header=create(0,"STATIC",path+"\r\n"+kind+f" · {len(raw):,} 字节 · 这是交付时的版本，不是现在的文件",0x50000080,
                      0,0,1,1,hwnd,None,instance,None)
        edit=create(0x200,"EDIT","",0x503108C4,0,0,1,1,hwnd,None,instance,None)  # multiline, readonly, both scrollbars
        foot=create(0,"STATIC","原文件 SHA-256："+receipt["sha256"]+"\r\nEsc 关闭。锁屏或原读取范围失效时会退出。",
                    0x50000080,0,0,1,1,hwnd,None,instance,None)
        if not all((header,edit,foot)): _bad()
        send(header,0x30,stock(17),1); send(foot,0x30,stock(17),1); send(edit,0x30,stock(11 if kind=="十六进制" else 17),1)
        units=len(text.encode("utf-16-le"))//2
        send(edit,0xC5,max(1,units+1),0)  # EM_SETLIMITTEXT; never silently show a clipped version as complete
        if not set_text(edit,text) or length(edit)!=units: _bad()
        if not timer(hwnd,1,750,None): _bad()
        if not active(): return
        size(hwnd); show_window(hwnd,5); focus(edit)
        msg=w.MSG()
        while exists(hwnd):
            status=get(c.byref(msg),None,0,0)
            if status<0: _bad()
            if status==0: break
            if msg.message==0x100 and msg.wParam==27: destroy(hwnd); break
            if msg.hwnd==edit and msg.message==0x100 and msg.wParam==65 and key(17)<0:
                send(edit,0xB1,0,-1); continue  # explicit Ctrl+A for keyboard users
            translate(c.byref(msg)); dispatch(c.byref(msg))
    finally:
        if hwnd and exists(hwnd): destroy(hwnd)
        unreg(name,instance)
