"""Represent one confirmed missing tracked path as a private patch, not a deletion.

Not found at this precise copy path says nothing about a rename or the cause.
No attempt to locate the body elsewhere, mutate the copy/repository or send it.
"""
from contextlib import contextmanager
import hashlib
import ntpath
import os
import re

from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_added_source import SCAN,_directory,_fold,_owner,_stamp
from windows_git_copy import _json,_read
from windows_git_source import registered
from windows_git_tree import _component,_native_compare
from windows_source_diff import TEXT_LIMIT,_lines,historical_blob
from windows_source_inventory import _origin,load
from windows_source_patch import (REMOVED_BANK as BANK,REMOVED_PROFILE as PROFILE,
                                  LIMIT,_blob,_quoted,_records,output_names,publish_output)
from windows_source_publisher import _ext


def _bad(): raise PlatformError("Original tracked path, original Git blob or current leaf absence not established")


def _path(path):
    prefix=""
    for part in relative_parts(path):
        path2,_=_component(prefix,part.encode("utf-8")); prefix=path2+"/"
    if prefix!=path+"/": _bad()
    return path.rpartition("/")[::2]


@contextmanager
def _missing(ctx,op,path,checkpoint):
    parent,name=_path(path)
    with _directory(ctx,op,parent) as (tree,wanted):
        if name not in wanted: _bad()
        physical,handle,_=tree.nodes[parent]; api=tree.api; at=_stamp(api,handle)
        absolute=_ext(ntpath.join(physical,name)); by=_native_compare(api,checkpoint)
        def unchanged():
            checkpoint()
            if at!=_stamp(api,handle): _bad()
            # Only ERROR_FILE_NOT_FOUND under a still-pinned original parent;
            # not path missing, access denied, sharing violation, broken link, etc.
            try: child=api.open(absolute,directory=True)
            except OSError as error:
                if getattr(error,"winerror",None)!=2: raise
            else:
                api.k.CloseHandle(child); _bad()  # metadata probe only, never read an existing body
        unchanged()
        folded=_fold(name)
        with os.scandir(_ext(physical)) as entries:
            for count,entry in enumerate(entries,1):
                if count>SCAN: _bad()
                if not count%128: checkpoint()
                if entry.name==name or _fold(entry.name)==folded or by(entry.name,name)==0: _bad()
        unchanged()
        yield tree.origins[parent],unchanged
        # No claim of a permanent absence: evidence is for the observed interval only.


def _render(path,row,algo,raw):
    lines=_lines(raw)
    if (lines is None or len(raw)>TEXT_LIMIT or row["mode"] not in ("100644","100755")
            or _blob(raw,algo)!=row["object"] or hashlib.sha256(raw).hexdigest()!=row["sha256"] or len(raw)!=row["bytes"]): _bad()
    oid=row["object"].encode(); a,b=_quoted("a/",path),_quoted("b/",path)
    out=[b"diff --git "+a+b" "+b+b"\n",b"deleted file mode "+row["mode"].encode()+b"\n",
         b"index "+oid+b".."+b"0"*len(oid)+b"\n"]
    records=_records(raw)
    if records:
        out += [b"--- "+a+b"\n",b"+++ /dev/null\n",b"@@ -1,"+str(len(records)).encode()+b" +0,0 @@\n"]
        for part in records:
            out.append(b"-"+part)
            if not part.endswith(b"\n"): out.append(b"\n\\ No newline at end of file\n")
    patch=b"".join(out)
    if len(patch)>LIMIT: _bad()
    return patch,lines


def prepare(ctx,settings,op,item,checkpoint):
    owner=_owner(ctx,op); source=owner["source"]; path=item["path"]; _path(path)
    _,_,rows,_,_,origins=load(ctx,op)
    row=rows.get(path)
    if row is None or item["bytes"]!=row["bytes"] or row["bytes"]>TEXT_LIMIT: _bad()
    with _missing(ctx,op,path,checkpoint) as (folder,unchanged):
        raw=historical_blob(ctx,settings,source,path,row,checkpoint)
        patch,lines=_render(path,row,source["objectFormat"],raw); unchanged()
    f={"install":ctx.install_state["installId"],"copy":op,"manifest":owner["manifestDigest"],"commit":source["commit"],
       "tree":source["tree"],"path":path,"parentOrigin":folder,"originalOrigin":origins[path],"mode":row["mode"],
       "observed":"not_found_at_original_path","originalBytes":row["bytes"],"originalOid":row["object"],
       "originalSha":row["sha256"],"bytes":len(patch),"patchSha":hashlib.sha256(patch).hexdigest()}
    return {"fields":f,"patch":patch,"excerpt":"\n".join(v[:120] for v in lines[:12])}


def key(f): return hashlib.sha256(_json([PROFILE,f])).hexdigest()[:32]


def _valid(ctx,op,f,owner):
    if not isinstance(f,dict) or set(f)!={"install","copy","manifest","commit","tree","path","parentOrigin","originalOrigin",
            "mode","observed","originalBytes","originalOid","originalSha","bytes","patchSha"}: return False
    source=owner["source"]
    if not isinstance(f["path"],str) or source["objectFormat"] not in ("sha1","sha256"): return False
    _path(f["path"]); width=40 if source["objectFormat"]=="sha1" else 64
    return (f["install"]==ctx.install_state["installId"] and f["copy"]==op and f["manifest"]==owner["manifestDigest"]
            and f["commit"]==source["commit"] and f["tree"]==source["tree"] and f["observed"]=="not_found_at_original_path"
            and f["mode"] in ("100644","100755") and _origin(f["parentOrigin"]) and _origin(f["originalOrigin"])
            and all(isinstance(f[k],str) and re.fullmatch("[a-f0-9]{"+str(width)+"}",f[k]) for k in ("tree","commit","originalOid"))
            and all(isinstance(f[k],str) and re.fullmatch("[a-f0-9]{64}",f[k]) for k in ("originalSha","patchSha"))
            and type(f["originalBytes"]) is int and 0<=f["originalBytes"]<=TEXT_LIMIT and type(f["bytes"]) is int and 0<f["bytes"]<=LIMIT)


def records(ctx,op):
    owner=_owner(ctx,op); rows={}; seen=set()
    for n in range(BANK):
        _,slot,_,_=output_names(ctx,op,"0"*32,n,added=False,removed=True)
        r=_read(ctx,slot,16384)
        if r is None: continue
        f=r["fields"]; rid=r["id"]
        if (r.get("format")!=PROFILE or not _valid(ctx,op,f,owner) or rid!=key(f) or rid in seen
                or type(r.get("at")) is not int or not 0<=r["at"]<=4102444800000): _bad()
        seen.add(rid); rows[n]=r
    return rows


def save(ctx,settings,op,prepared,locked,checkpoint):
    f=prepared["fields"]; rid=key(f); owner=_owner(ctx,op)
    if not _valid(ctx,op,f,owner): _bad()
    kept=records(ctx,op)
    for row in kept.values():
        if row["id"]==rid: return row  # no replay of a pending publication
    if len(kept)>=BANK: _bad()
    _,_,originals,_,_,origins=load(ctx,op); path=f["path"]; entry=originals.get(path)
    if (not entry or origins[path]!=f["originalOrigin"] or entry["object"]!=f["originalOid"] or entry["mode"]!=f["mode"]
            or entry["sha256"]!=f["originalSha"] or entry["bytes"]!=f["originalBytes"]): _bad()
    with _missing(ctx,op,path,checkpoint) as (parent,unchanged):
        if parent!=f["parentOrigin"]: _bad()
        def current():
            if locked() or owner["source"]["environmentId"] not in dict(registered(ctx,settings)): _bad()
            unchanged()
        current()
        return publish_output(ctx,op,rid,f,prepared["patch"],next(n for n in range(BANK) if n not in kept),
                              current,added=False,removed=True)
