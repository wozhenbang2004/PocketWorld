"""Owner-requested inspection of a previously published LOCAL committed-file copy.

Never reads the current source repository, new files, replaced file bodies or
unrecognized directories. Evidence is a single observation, not a live lease.
"""
from contextlib import ExitStack
import ctypes
import hashlib
import ntpath
import os
import re

from assignment_projects import manifest_identity
from platform_backend import PlatformError
from platform_windows import WindowsHandleReader
from windows_git_contents import MAX_FILE
from windows_installation import _object
from windows_source_publisher import DirectoryTree, _ext, _plan

PROFILE = "pocketworld.windows-source-copy-original-inventory.v1"
LIMIT = 6*1024*1024


def _bad():
    raise PlatformError("Original saved copy inventory or its native identity is unavailable")


def _id(value):
    return isinstance(value,list) and len(value)==3 and all(type(n) is int and 0<=n<2**32 for n in value)


def _origin(value):
    return (isinstance(value,list) and len(value)==5 and _id(value[:3])
            and any(value[1:3]) and any(value[3:]) and all(type(n) is int and 0<=n<2**32 for n in value[3:]))


def claim(data):
    return {"bytes":data["bytes"],"sha256":data["sha256"],"fileIdentity":data["fileIdentity"]}


def _claim_ok(ref):
    return (isinstance(ref,dict) and set(ref)=={"bytes","sha256","fileIdentity"}
            and type(ref["bytes"]) is int and 0<ref["bytes"]<=LIMIT and isinstance(ref["sha256"],str)
            and re.fullmatch("[a-f0-9]{64}",ref["sha256"]) and _id(ref["fileIdentity"]))


def load(ctx,op):
    from windows_git_copy import _phase, _read, inspect, slots
    if not isinstance(op,str) or not re.fullmatch("[a-f0-9]{32}",op): _bad()
    row = next((r for _,r in slots(ctx) if r["operation"]==op),None)
    if row is None or inspect(ctx,row)["status"]!="saved": _bad()
    final = _read(ctx,_phase(op,"result"),4096)
    ref = final.get("items")
    if not _claim_ok(ref) or not _id(final.get("root")): _bad()
    with ctx.backend._private_leaf(ctx.state,_phase(op,"items"),ctx.sid,optional=False) as handle:
        api = ctx.backend.api
        reader = WindowsHandleReader(api,handle,LIMIT)
        if reader.identity!=ref["fileIdentity"] or reader.size!=ref["bytes"]: _bad()
        reader.seek(0)
        value = bytearray()
        while reader.position<reader.size: value.extend(reader.read(min(262144,reader.size-reader.position)))
        if not reader.verify_unchanged() or hashlib.sha256(value).hexdigest()!=ref["sha256"]: _bad()
    saved = _object(bytes(value))
    manifest = saved.get("manifest")
    if (saved.get("format")!=PROFILE or saved.get("operation")!=op or saved.get("root")!=final["root"]
            or not _origin(saved.get("rootOrigin")) or saved["rootOrigin"][:3]!=final["root"]
            or not isinstance(manifest,dict) or manifest.get("source")!=row["source"]
            or manifest_identity(manifest)!=row["manifestDigest"]): _bad()
    dirs, rows, children = _plan(manifest)
    df, ff = saved.get("directories"),saved.get("files")
    if (not isinstance(df,list) or len(df)!=len(dirs) or not all(_origin(i) for i in df)
            or not isinstance(ff,list) or len(ff)!=len(rows) or not all(_origin(i) for i in ff)): _bad()
    return saved["rootOrigin"],dirs,rows,children,dict(zip(manifest["directories"],df)),dict(zip(rows,ff))


def compare(ctx,op,checkpoint,progress):
    root,dirs,rows,children,dir_ids,file_ids = load(ctx,op)
    report = dict(matched=0,different=0,replaced=0,missing=0,unreadable=0,unvisited=0,
                  unboundDirectories=0,newEntries=0,directoryChanged=False)
    names,stamps,observed = {},{},0
    report.update(comparable=0,candidates=[],newParentCount=0,newParents=[])
    def candidate(path,state,identity,info):
        report["comparable"]+=1
        if len(report["candidates"])<64:
            report["candidates"].append({"path":path,"state":state,"origin":identity,"bytes":(info.sizeHigh<<32)+info.sizeLow,
                                         "written":[info.written.dwHighDateTime,info.written.dwLowDateTime]})
    def stamp(api,handle):
        data = api.info(handle).written
        return data.dwHighDateTime,data.dwLowDateTime
    base = ntpath.join(ctx.state,"source-"+op)
    with ExitStack() as stack:
        # Keep all ORIGINAL ancestors (including the protected state and copy
        # root) pinned throughout inspection. Returning an old path alone isn't enough.
        stack.enter_context(ctx.backend.directory(ctx.state))
        tree = DirectoryTree(ctx,base,stack,root)
        for path in ["",*dirs]:
            checkpoint()
            if path:
                parent,_,leaf = path.rpartition("/")
                if parent not in names or leaf not in names[parent]:
                    report["unboundDirectories"]+=1; continue
                try: tree.bind_dir(path,dir_ids[path])
                except (OSError,PlatformError):
                    report["unboundDirectories"]+=1; continue
            physical, handle, _ = tree.nodes[path]
            stamps[path] = stamp(tree.api,handle)
            wanted,seen = children[path],set()
            extras=0
            with os.scandir(_ext(physical)) as listing:
                for entry in listing:
                    observed+=1
                    if observed>24000: _bad()
                    if not observed%128: checkpoint()
                    if entry.name in wanted: seen.add(entry.name)
                    else: extras+=1  # Don't retain/open any NEW name or descend into it.
            if extras:
                report["newEntries"]+=extras; report["newParentCount"]+=1
                if len(report["newParents"])<64: report["newParents"].append({"path":path,"count":extras})
            names[path]=seen
        for n,(path,row) in enumerate(rows.items(),1):
            checkpoint()
            parent,_,leaf = path.rpartition("/")
            if parent not in names: report["unvisited"]+=1
            elif leaf not in names[parent]: report["missing"]+=1
            else:
                try:
                    handle,identity = tree.open_file(path)
                    try:
                        info = tree.api.info(handle)
                        if identity!=file_ids[path]:
                            report["replaced"]+=1
                            candidate(path,"replacement",identity,info)  # metadata only; don't read this replacement.
                        else:
                            if (info.sizeHigh<<32)+info.sizeLow!=row["bytes"]:
                                report["different"]+=1
                                candidate(path,"edited",identity,info)
                            else:
                                reader = WindowsHandleReader(tree.api,handle,MAX_FILE)
                                reader.seek(0)
                                sha = hashlib.sha256()
                                while reader.position<reader.size:
                                    checkpoint()
                                    sha.update(reader.read(min(262144,reader.size-reader.position)))
                                if not reader.verify_unchanged(): _bad()
                                if sha.hexdigest()==row["sha256"]: report["matched"]+=1
                                else:
                                    report["different"]+=1
                                    candidate(path,"edited",identity,info)
                    finally: tree.api.k.CloseHandle(handle)
                except (OSError,PlatformError):
                    report["unreadable"]+=1
            if not n%16 or n==len(rows): progress(n,len(rows))
        for path,at in stamps.items():
            if stamp(tree.api,tree.nodes[path][1])!=at: report["directoryChanged"]=True
        checkpoint()
    report["allOriginal"] = (report["matched"]==len(rows) and not report["directoryChanged"]
                             and not report["newEntries"] and not report["unboundDirectories"])
    return report


def reveal(ctx,op,locked):
    """Open the original copy folder and SELECT one bound child; never run the child.

    cidl=0 would open the parent (protected application STATE), so deliberately
    require one original immediate child. No ShellExecute, URL or CLI fallback.
    """
    root,_,rows,children,dirs,files = load(ctx,op)
    api = ctx.backend.api
    base = ntpath.join(ctx.state,"source-"+op)
    with ExitStack() as stack:
        stack.enter_context(ctx.backend.directory(ctx.state))
        tree = DirectoryTree(ctx,base,stack,root)
        candidate = None
        for name in sorted(children[""],key=lambda s:(s not in dirs,s))[:64]:
            if locked(): _bad()
            try:
                if name in dirs:
                    tree.bind_dir(name,dirs[name])
                elif name in rows:
                    h,identity = tree.open_file(name)
                    if identity!=files[name]: api.k.CloseHandle(h); continue
                    stack.callback(api.k.CloseHandle,h)
                else: continue
            except (OSError,PlatformError): continue
            candidate = ntpath.join(base,name); break
        if candidate is None: _bad()
        show_bound_child(ctx,stack,base,candidate,locked)


def show_bound_child(ctx,stack,base,candidate,locked):
    """Only with original parent + child already pinned by caller. Never open the child."""
    def current():
        ctx.check_current_pointer()
        return not locked()
    return show_selected_child(ctx.backend.api,stack,base,candidate,current)


def show_selected_child(api,stack,base,candidate,current):
    """Selector, not opener. Caller keeps literal parent and file handles pinned throughout."""
    shell,ole = ctypes.WinDLL("shell32"),ctypes.WinDLL("ole32")
    ptr,w = ctypes.c_void_p,api.w
    ole.CoInitializeEx.argtypes,ole.CoInitializeEx.restype = [ptr,w.DWORD],ctypes.c_long
    ole.CoUninitialize.argtypes,ole.CoUninitialize.restype = [],None
    ole.CoTaskMemFree.argtypes,ole.CoTaskMemFree.restype = [ptr],None
    if ole.CoInitializeEx(None,2) not in (0,1): _bad()
    stack.callback(ole.CoUninitialize)
    parse = shell.SHParseDisplayName
    parse.argtypes,parse.restype = [w.LPCWSTR,ptr,ctypes.POINTER(ptr),w.DWORD,ctypes.POINTER(w.DWORD)],ctypes.c_long
    get_path = shell.SHGetPathFromIDListEx
    get_path.argtypes,get_path.restype = [ptr,w.LPWSTR,w.DWORD,w.DWORD],w.BOOL
    immediate = shell.ILIsParent
    immediate.argtypes,immediate.restype = [ptr,ptr,w.BOOL],w.BOOL
    last = shell.ILFindLastID
    last.argtypes,last.restype = [ptr],ptr
    show = shell.SHOpenFolderAndSelectItems
    show.argtypes,show.restype = [ptr,w.UINT,ctypes.POINTER(ptr),w.DWORD],ctypes.c_long
    def physical_pidl(path,flags):
        item,attributes = ptr(),w.DWORD()
        result = parse(path,None,ctypes.byref(item),flags,ctypes.byref(attributes))
        if item.value: stack.callback(ole.CoTaskMemFree,item)
        if result!=0 or not item.value or attributes.value&flags!=flags: _bad()
        bound = ctypes.create_unicode_buffer(32768)
        if not get_path(item,bound,len(bound),0) or ntpath.normcase(bound.value)!=ntpath.normcase(path): _bad()
        return item
    parent = physical_pidl(base,0x60000000)  # filesystem AND folder
    child = physical_pidl(candidate,0x40000000)  # filesystem
    if not immediate(parent,child,True): _bad()
    tail = last(child)
    if not tail: _bad()
    items = (ptr*1)(tail)
    if not current() or show(parent,1,items,0)!=0: _bad()
