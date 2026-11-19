"""A single install-bound Start-menu command link; no Startup folder, URL, shortcut import or auto-run."""
import ctypes as c
from contextlib import ExitStack
import ntpath
import uuid

from platform_backend import PlatformError

P=c.c_void_p
HRESULT=c.c_long
MAX_LINK=24*1024


def bad(): raise PlatformError("Cannot verify a normal local Windows owner Start-menu link")


def guid(text): return (c.c_ubyte*16).from_buffer_copy(uuid.UUID(text).bytes_le)


def method(obj, slot, result, *args):
    address=c.cast(obj,c.POINTER(c.POINTER(P))).contents[slot]
    return c.WINFUNCTYPE(result,P,*args)(address)


def ok(result):
    if result<0: bad()


def location():
    # DONT_VERIFY: get the registered current-user paths first; don't create/explore a redirected folder.
    api=c.WinDLL("shell32").SHGetKnownFolderPath
    api.argtypes=(P,c.c_uint32,P,c.POINTER(P)); api.restype=HRESULT
    free=c.WinDLL("ole32").CoTaskMemFree; free.argtypes=(P,); free.restype=None
    def known(value):
        raw=P()
        try:
            ok(api(c.byref(guid(value)),0x4000,None,c.byref(raw)))
            if not raw.value: bad()
            return c.wstring_at(raw)
        finally:
            if raw.value: free(raw)
    profile=known("5e6c858f-0e22-4760-9afe-ea3317b67173")
    programs=known("a77f5d77-2e2b-44c3-a6a2-aba601054a51")
    local=known("f1b32785-6fba-4fcf-9d55-7b8e7f157091")
    system=known("1ac14e77-02e7-4e5d-b744-2eb1ae5198b7")
    expected=ntpath.join(profile,"AppData","Roaming","Microsoft","Windows","Start Menu","Programs")
    if ntpath.normcase(programs)!=ntpath.normcase(expected) or ntpath.normcase(local)!=ntpath.normcase(ntpath.join(profile,"AppData","Local")): bad()
    return programs,local,system


def bytes_for_desk(shell,arguments,working):
    """Serialize and read back ONLY the link just constructed in memory; never Resolve/launch a target."""
    ole=c.WinDLL("ole32")
    ole.CoInitializeEx.argtypes=(P,c.c_uint32); ole.CoInitializeEx.restype=HRESULT
    ole.CoUninitialize.argtypes=(); ole.CoUninitialize.restype=None
    ole.CoCreateInstance.argtypes=(P,P,c.c_uint32,P,c.POINTER(P)); ole.CoCreateInstance.restype=HRESULT
    ole.CreateStreamOnHGlobal.argtypes=(P,c.c_int,c.POINTER(P)); ole.CreateStreamOnHGlobal.restype=HRESULT
    with ExitStack() as held:
        ok(ole.CoInitializeEx(None,2)); held.callback(ole.CoUninitialize)
        def keep(obj):
            if not obj.value: bad()
            held.callback(method(obj,2,c.c_uint32),obj); return obj
        def link():
            obj=P(); ok(ole.CoCreateInstance(c.byref(guid("00021401-0000-0000-c000-000000000046")),None,1,
                c.byref(guid("000214f9-0000-0000-c000-000000000046")),c.byref(obj))); return keep(obj)
        def persistence(obj):
            p=P(); ok(method(obj,0,HRESULT,P,c.POINTER(P))(obj,c.byref(guid("00000109-0000-0000-c000-000000000046")),c.byref(p))); return keep(p)
        obj=link()
        for slot,text in ((20,shell),(11,arguments),(9,working),(7,"PocketWorld · 只打开这台电脑的本机工作台")):
            ok(method(obj,slot,HRESULT,c.c_wchar_p)(obj,text))
        stream=P(); ok(ole.CreateStreamOnHGlobal(None,1,c.byref(stream))); keep(stream)
        save=persistence(obj); ok(method(save,6,HRESULT,P,c.c_int)(save,stream,1))
        def seek(origin):
            offset=c.c_uint64()
            ok(method(stream,5,HRESULT,c.c_int64,c.c_uint32,c.POINTER(c.c_uint64))(stream,0,origin,c.byref(offset)))
            return offset.value
        length=seek(2)
        if not 76<=length<=MAX_LINK or seek(0)!=0: bad()
        block=c.create_string_buffer(length); count=c.c_uint32()
        ok(method(stream,3,HRESULT,P,c.c_uint32,c.POINTER(c.c_uint32))(stream,block,length,c.byref(count)))
        if count.value!=length or seek(0)!=0: bad()
        checked=link(); load=persistence(checked); ok(method(load,5,HRESULT,P)(load,stream))
        path=c.create_unicode_buffer(2048)
        ok(method(checked,3,HRESULT,c.c_wchar_p,c.c_int,P,c.c_uint32)(checked,path,len(path),None,4))  # SLGP_RAWPATH
        if ntpath.normcase(path.value)!=ntpath.normcase(shell): bad()
        for slot,expected in ((10,arguments),(8,working)):
            value=c.create_unicode_buffer(2048); ok(method(checked,slot,HRESULT,c.c_wchar_p,c.c_int)(checked,value,len(value)))
            if value.value!=expected: bad()
        hotkey=c.c_uint16(); ok(method(checked,12,HRESULT,c.POINTER(c.c_uint16))(checked,c.byref(hotkey)))
        if hotkey.value: bad()
        return block.raw
