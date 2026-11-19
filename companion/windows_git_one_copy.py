"""Scope of one explicit, committed-file copy. Never a complete repository/tree review."""
import hashlib
import json
import re

from platform_backend import PlatformError

PROFILE="pocketworld.windows-one-committed-file.v1"


def bad(): raise PlatformError("Original selected file and scope cannot be verified")


def fingerprint(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(",",":"),allow_nan=False).encode()).hexdigest()


def descriptor(file):
    return {"format":PROFILE,"path":file.path,"mode":file.mode,"object":file.oid,"bytes":file.size,"sha256":file.sha,
            "repository":list(file.repository),"git":list(file.git)}


def selection(source):
    from windows_git_one_file import MAX_TEXT,pieces
    from windows_source_inventory import _origin
    f=source["selectedFile"]
    if (not isinstance(f,dict) or set(f)!={"format","path","mode","object","bytes","sha256","repository","git"}
            or f["format"]!=PROFILE or f["mode"] not in ("100644","100755") or not _origin(f["repository"])
            or not _origin(f["git"]) or type(f["bytes"]) is not int or not 0<=f["bytes"]<=MAX_TEXT
            or not isinstance(f["sha256"],str) or not re.fullmatch("[a-f0-9]{64}",f["sha256"])
            or source["objectFormat"] not in ("sha1","sha256") or not isinstance(f["object"],str)
            or not re.fullmatch("[a-f0-9]{"+str(40 if source["objectFormat"]=="sha1" else 64)+"}",f["object"])): bad()
    parts=pieces(f["path"])
    dirs=["/".join(parts[:n]) for n in range(1,len(parts))]
    row={k:f[k] for k in ("path","mode","object","bytes","sha256")}
    return f,dirs,row


def facts(source):
    f,dirs,row=selection(source); size=f["bytes"]
    return {"selectedFile":f,"mapping":fingerprint([PROFILE,"path",source["tree"],dirs,f["path"],f["mode"],f["object"]]),
            "content":fingerprint([PROFILE,"content",source["tree"],row]),"directories":len(dirs),
            "files":1,"bytes":size,"largest":size,"lfsHeaders":0}


def matches(f):
    try: return all(f.get(k)==v for k,v in facts(f).items())
    except Exception: return False


def validate_manifest(src,declared,files):
    _,dirs,row=selection(src)
    if declared!=dirs or files!=[row] or not matches(src): bad()


def verify_source(pins,source):
    from windows_source_publisher import _origin
    f,_,_=selection(source)
    if (_origin(pins.api,pins.api.info(pins.nodes[()][1]))!=f["repository"]
            or _origin(pins.api,pins.api.info(pins.nodes[(".git",)][1]))!=f["git"]): bad()


def hold_original(ctx,settings,source,hold,checkpoint):
    from windows_source_diff import original_commit
    hold.enter_context(original_commit(ctx,settings,source,checkpoint))  # Original root and .git remain bound through publication.


def materialize(ctx,settings,source,material,checkpoint,progress):
    from windows_git_one_file import read
    f,dirs,_=selection(source)
    file,raw=read(ctx,settings,source,f["path"],f["object"],checkpoint,with_bytes=True)
    if descriptor(file)!=f: bad()
    material.directories.extend(dirs)
    material.keep(f["path"],file.mode,file.oid,raw,file.sha)
    progress(1,1,len(raw))


def saved(ctx,op,locked):
    """Read back just the originally published leaf; do not list its current parent or use Git."""
    from contextlib import ExitStack
    import ntpath
    from platform_windows import WindowsHandleReader
    from windows_git_copy import slots
    from windows_git_one_file import MAX_TEXT
    from windows_source_diff import _lines
    from windows_source_inventory import load
    from windows_source_publisher import DirectoryTree
    if locked(): bad()
    record=next((r for _,r in slots(ctx,one=True) if r["operation"]==op),None)
    if record is None: bad()
    src=record["source"]; f,parents,row=selection(src)
    root,_,entries,_,dirs,files=load(ctx,op)
    if entries!={f["path"]:row}: bad()
    with ExitStack() as held:
        _,h,_=held.enter_context(ctx.backend.directory(ctx.state)); ctx.backend.api.private_access(h,ctx.sid,require_protected=True)
        tree=DirectoryTree(ctx,ntpath.join(ctx.state,"source-"+op),held,root)
        for d in parents: tree.bind_dir(d,dirs[d])
        if locked(): bad()
        handle,identity=tree.open_file(f["path"]); held.callback(tree.api.k.CloseHandle,handle)
        reader=WindowsHandleReader(tree.api,handle,MAX_TEXT)
        if identity!=files[f["path"]] or reader.size!=f["bytes"]: bad()
        reader.seek(0); raw=reader.read(MAX_TEXT)
        if (not reader.verify_unchanged() or len(raw)!=f["bytes"] or hashlib.sha256(raw).hexdigest()!=f["sha256"]
                or hashlib.new(src["objectFormat"],b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()!=f["object"]
                or _lines(raw) is None or locked()): bad()
    return raw.decode("utf-8-sig").replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n")
