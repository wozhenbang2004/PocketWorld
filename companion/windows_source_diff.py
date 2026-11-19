"""Explicit byte/text comparison of ONE chosen file in an original local copy.

A replacement is a NEW observed comparison side, never historical authority.
The baseline, if readable, comes from the exact original commit, never HEAD.
"""
from contextlib import ExitStack, contextmanager
import difflib
import hashlib
import ntpath
import unicodedata

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, relative_parts
from windows_git_contents import MAX_FILE
from windows_git_pack import PackUnavailable, PinnedPacks
from windows_git_source import Incomplete, _format, _object_body, _tree_id, registered, repository_anchor
from windows_git_tree import DIR, TreeIssue, entries
from windows_source_inventory import load
from windows_source_publisher import DirectoryTree

TEXT_LIMIT = 128*1024


def _bad(): raise PlatformError("Selected source or current copy observation changed")


@contextmanager
def original_commit(ctx,settings,source,checkpoint):
    """Pinned original repository and COMMIT. Never resolve HEAD for a historical claim."""
    if not settings: _bad()
    root = dict(registered(ctx,settings)).get(source["environmentId"])
    if not root: _bad()
    with ExitStack() as stack:
        from windows_git_subproject import source_pins
        if "selectedProject" in source and source["selectedProject"] is None: _bad()
        pins=source_pins(ctx,root,source["environmentId"],source.get("selectedProject"),stack)
        dot = pins.node(".git")
        if not dot or not dot[2] or repository_anchor(ctx,source["environmentId"],pins,dot)!=source["repositoryAnchor"]: _bad()
        if "selectedFile" in source:
            from windows_git_one_copy import verify_source
            verify_source(pins,source)
        if pins.node(".git/commondir") is not None or pins.node(".git/objects/info/alternates") is not None: _bad()
        algo = _format(pins.file(".git/config",128*1024))
        if algo!=source["objectFormat"]: _bad()
        packs = PinnedPacks(pins,algo,checkpoint=checkpoint)
        commit,_ = _object_body(pins,packs,source["commit"],b"commit",256*1024)
        oid = _tree_id(commit,algo)
        if oid!=source["tree"]: _bad()
        yield pins,packs,oid
        if dict(registered(ctx,settings)).get(source["environmentId"])!=root: _bad()
        pins.recheck_absence(checkpoint)


@contextmanager
def bound_blob(ctx,settings,source,path,row,checkpoint):
    if "selectedFile" in source:
        from windows_git_one_copy import selection
        _,_,fixed=selection(source)
        if path!=fixed["path"] or row!=fixed: _bad()
    with original_commit(ctx,settings,source,checkpoint) as (pins,packs,oid):
        pieces = relative_parts(path)
        # Verify each path component inside the immutable commit, not a file
        # currently bearing that path in the original working directory.
        for n,part in enumerate(pieces):
            checkpoint()
            raw,_ = _object_body(pins,packs,oid,b"tree",1024*1024)
            hits = [(mode,child) for mode,name,child in entries(raw,packs.width) if name==part.encode("utf-8")]
            if len(hits)!=1: _bad()
            mode,oid=hits[0]
            if n<len(pieces)-1 and mode!=DIR: _bad()
        if mode!=row["mode"].encode("ascii") or oid!=row["object"] or row["bytes"]>TEXT_LIMIT: _bad()
        body,_ = _object_body(pins,packs,oid,b"blob",MAX_FILE)
        if len(body)!=row["bytes"] or hashlib.sha256(body).hexdigest()!=row["sha256"]: _bad()
        yield body


def historical_blob(ctx,settings,source,path,row,checkpoint):
    with bound_blob(ctx,settings,source,path,row,checkpoint) as body:
        return body


def _lines(data):
    try: text=data.decode("utf-8-sig")
    except UnicodeError: return None
    if any(ch not in "\r\n\t" and unicodedata.category(ch) in ("Cf","Cc","Cs") for ch in text): return None
    lines = text.splitlines()
    if len(lines)>1500 or any(len(line)>2000 for line in lines): return None
    return lines


def selected(ctx,settings,op,item,checkpoint,*,patch=False):
    from windows_git_copy import slots
    root,_,rows,_,dirs,files = load(ctx,op)
    original = next((r["source"] for _,r in slots(ctx) if r["operation"]==op),None)
    path=item["path"]; row=rows.get(path)
    if row is None or original is None or item["state"] not in ("edited","replacement"): _bad()
    fields = {"path":path,"commit":original["commit"],"baseBytes":row["bytes"],"baseSha":row["sha256"],
              "replaced":item["state"]=="replacement","currentBytes":item["bytes"]}
    current = bytearray() if item["bytes"]<=TEXT_LIMIT else None
    with ExitStack() as stack:
        stack.enter_context(ctx.backend.directory(ctx.state))
        tree = DirectoryTree(ctx,ntpath.join(ctx.state,"source-"+op),stack,root)
        parts=relative_parts(path)
        for n in range(1,len(parts)):
            parent="/".join(parts[:n])
            if parent not in dirs: _bad()
            tree.bind_dir(parent,dirs[parent])
        checkpoint()
        handle,identity=tree.open_file(path); stack.callback(tree.api.k.CloseHandle,handle)
        info=tree.api.info(handle)
        if (identity!=item["origin"] or (identity!=files[path])!=fields["replaced"]
                or [info.written.dwHighDateTime,info.written.dwLowDateTime]!=item["written"]
                or (info.sizeHigh<<32)+info.sizeLow!=item["bytes"]): _bad()
        if item["bytes"]>MAX_FILE: return {**fields,"status":"current_oversize"}
        reader=WindowsHandleReader(tree.api,handle,MAX_FILE); reader.seek(0)
        sha=hashlib.sha256()
        while reader.position<reader.size:
            checkpoint()
            block=reader.read(min(262144,reader.size-reader.position)); sha.update(block)
            if current is not None: current.extend(block)
        if not reader.verify_unchanged(): _bad()
        fields["currentSha"]=sha.hexdigest()
    if fields["currentSha"]==fields["baseSha"]: return {**fields,"status":"same_bytes"}
    now = None if current is None else _lines(current)
    if now is None or row["bytes"]>TEXT_LIMIT: return {**fields,"status":"hash_only"}
    try: earlier=historical_blob(ctx,settings,original,path,row,checkpoint)
    except (OSError,ValueError,TypeError,Incomplete,TreeIssue,PackUnavailable,PlatformError):
        checkpoint()
        return {**fields,"status":"historical_unavailable"}
    base=_lines(earlier)
    if base is None: return {**fields,"status":"hash_only"}
    if patch:
        from windows_source_patch import render
        raw,new_oid=render(path,row["mode"],row["object"],original["objectFormat"],earlier,bytes(current),checkpoint)
        return {**fields,"status":"patch","patch":raw,"originalOid":row["object"],"currentOid":new_oid}
    if base==now: return {**fields,"status":"text_lines_equal"}
    checkpoint()
    changes=difflib.unified_diff(base,now,fromfile="原提交",tofile="当前选定文件",n=2,lineterm="")
    visible,clipped=[],False
    for n,line in enumerate(changes):
        if n==36: clipped=True; break
        if len(line)>160: line=line[:157]+"…"; clipped=True
        visible.append(line)
    checkpoint()
    return {**fields,"status":"text","excerpt":"\n".join(visible),"clipped":clipped}
