"""Read-only Win32 pins for an exact, non-recalled ordinary local entry. No enumeration by wildcard."""
import ctypes
import ntpath
from platform_backend import PlatformError
from windows_source_publisher import _ext, _origin

# Local material only. Before opening, check exact-entry enumeration attributes (RECALL_ON_OPEN
# is advertised there). No wildcards. Also reject reparse/offline, likely recall and protected items.
EXCLUDED=0x2|0x4|0x40|0x400|0x1000|0x4000|0x40000|0x100000|0x400000
NO_RECALL,OPEN_REPARSE,BACKUP=0x00100000,0x00200000,0x02000000


def bad(): raise PlatformError("Cannot pin this exact local non-recalled entry")


def attributes(api,path,*,absent=False):
    find,close=api.k.FindFirstFileW,api.k.FindClose
    find.argtypes,find.restype=[api.w.LPCWSTR,api.w.LPWIN32_FIND_DATAW],api.w.HANDLE
    close.argtypes,close.restype=[api.w.HANDLE],api.w.BOOL
    item=api.w.WIN32_FIND_DATAW()
    h=find(_ext(path),ctypes.byref(item))
    if h==ctypes.c_void_p(-1).value:
        error=ctypes.get_last_error()
        if absent and error==2: return None
        raise ctypes.WinError(error)
    try:
        if item.cFileName!=ntpath.basename(path): bad()  # refuse alternate 8.3 or case aliases
        return item.dwFileAttributes
    finally: api.checked(close(h))


def open_fixed(api,path,directory,stack,*,body=False,owner_acl=False):
    flags=attributes(api,path)
    if flags&EXCLUDED or bool(flags&0x10)!=directory: bad()
    # GetSecurityInfo for one private leaf needs READ_CONTROL, not file-body access or an ACL change.
    access=0x80000000 if body else 0x80|(0x00020000 if owner_acl else 0)
    h=api.k.CreateFileW(_ext(path),access,1,None,3,NO_RECALL|OPEN_REPARSE|(BACKUP if directory else 0),None)
    if h==ctypes.c_void_p(-1).value: raise ctypes.WinError(ctypes.get_last_error())
    stack.callback(api.k.CloseHandle,h)
    info=api.info(h); actual=api.final(h)
    if (actual.casefold()!=_ext(path).casefold() or ntpath.basename(actual)!=ntpath.basename(path)
            or info.attributes&EXCLUDED or bool(info.attributes&0x10)!=directory or not directory and info.links!=1): bad()
    return h,info,tuple(_origin(api,info))
