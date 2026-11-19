"""One selected *new* source under an original, physically bound copy directory.

No source-directory writes. In particular, missing/unreadable original Git
objects do NOT mean that a name was absent from the original commit.
"""
from contextlib import ExitStack,contextmanager
from functools import cmp_to_key
import hashlib
import ntpath
import os
import re
import unicodedata

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader,relative_parts
from windows_git_copy import _json,_read,slots
from windows_git_source import _object_body,registered
from windows_git_tree import DIR,TreeIssue,_component,_native_compare,entries
from windows_source_diff import TEXT_LIMIT,_lines,original_commit
from windows_source_inventory import _origin,load
from windows_source_patch import ADDED_BANK as BANK,ADDED_PROFILE as PROFILE,LIMIT,_blob,_quoted,_records,output_names,publish_output
from windows_source_publisher import DirectoryTree,_ext

SOURCE=frozenset(".java .kt .kts .py .go .rs .ts .tsx .js .jsx .c .cc .cpp .h .hpp .cs .swift .m .mm .sh .ps1 .sql .css .html .md .txt .json .xml .yaml .yml .toml".split())
SCAN,LIST=4096,64


def _bad(): raise PlatformError("Original directory, original Git absence, or chosen new version not verified")


def _source_name(parent,name):
    if not isinstance(name,str) or name.startswith(".") or ntpath.splitext(name)[1].casefold() not in SOURCE: _bad()
    prefix=""
    for part in relative_parts(parent,empty=True):
        checked,_=_component(prefix,part.encode("utf-8")); prefix=checked+"/"
    path,_=_component(prefix,name.encode("utf-8"))
    return path


def _owner(ctx,op):
    if not isinstance(op,str) or not re.fullmatch("[a-f0-9]{32}",op): _bad()
    for _,row in slots(ctx,one=False):  # Whole-copy additions/sets only; a selected file grants no siblings.
        if row["operation"]==op: return row
    _bad()


@contextmanager
def _directory(ctx,op,parent):
    root,_,_,children,dirs,_=load(ctx,op)
    if parent not in children: _bad()
    with ExitStack() as stack:
        _,s,_=stack.enter_context(ctx.backend.directory(ctx.state)); ctx.backend.api.private_access(s,ctx.sid,require_protected=True)
        tree=DirectoryTree(ctx,ntpath.join(ctx.state,"source-"+op),stack,root)
        parts=relative_parts(parent,empty=True)
        for n in range(1,len(parts)+1):
            path="/".join(parts[:n])
            if path not in dirs: _bad()
            tree.bind_dir(path,dirs[path])
        yield tree,children[parent]


def _stamp(api,handle):
    x=api.info(handle).written
    return [x.dwHighDateTime,x.dwLowDateTime]


def _fold(name): return unicodedata.normalize("NFKC",name).casefold()


def _names(tree,parent,wanted,checkpoint):
    physical,handle,_=tree.nodes[parent]; start=_stamp(tree.api,handle)
    result=set()
    with os.scandir(_ext(physical)) as listing:
        for e in listing:
            if len(result)>=SCAN or e.name in result: _bad()  # ambiguous/incomplete directory, never infer absence
            if not len(result)%128: checkpoint()
            result.add(e.name)
    if start!=_stamp(tree.api,handle): _bad()
    combined=result|wanted; by=_native_compare(tree.api,checkpoint)
    ordered=sorted(combined,key=cmp_to_key(by)); aliases=set()
    for a,b in zip(ordered,ordered[1:]):
        if by(a,b)==0: aliases.update((a,b))
    folds={}
    for name in combined:
        key=_fold(name)
        if key in folds: aliases.update((name,folds[key]))
        else: folds[key]=name
    return result-wanted-aliases,start


def _meta(tree,path):
    handle,identity=tree.open_file(path)
    try:
        info=tree.api.info(handle)
        return {"path":path,"origin":identity,"written":_stamp(tree.api,handle),"bytes":info.sizeHigh*2**32+info.sizeLow}
    finally: tree.api.k.CloseHandle(handle)


def candidates(ctx,op,parent,checkpoint):
    with _directory(ctx,op,parent) as (tree,wanted):
        names,stamp=_names(tree,parent,wanted,checkpoint); picked=[]
        for name in sorted(names,key=lambda v:(_fold(v),v)):
            checkpoint()
            try:
                path=_source_name(parent,name); m=_meta(tree,path)  # metadata only, never read an unchosen body
                if m["bytes"]>TEXT_LIMIT: continue
            except (OSError,PlatformError,TreeIssue,UnicodeError): continue
            if len(picked)==LIST:
                if stamp!=_stamp(tree.api,tree.nodes[parent][1]): _bad()
                return {"items":picked,"more":True}
            picked.append(m)
        if stamp!=_stamp(tree.api,tree.nodes[parent][1]): _bad()
        return {"items":picked,"more":False}


@contextmanager
def held_original_absence(ctx,settings,source,path,checkpoint):
    parts=relative_parts(path)
    with original_commit(ctx,settings,source,checkpoint) as (pins,packs,oid):
        by=_native_compare(ctx.backend.api,checkpoint)
        for i,part in enumerate(parts):
            checkpoint(); body,_=_object_body(pins,packs,oid,b"tree",1024*1024)
            prefix="/".join(parts[:i]); prefix=prefix+"/" if prefix else ""
            exact=None
            for mode,encoded,child in entries(body,packs.width):
                checkpoint(); _,name=_component(prefix,encoded)
                # A case/normalization alias is not proof of a new path.
                if by(name,part)==0 or _fold(name)==_fold(part):
                    if name!=part or exact is not None: _bad()
                    exact=(mode,child)
            if i==len(parts)-1:
                if exact is not None: _bad()
                yield oid,pins  # verified original parent tree contained no such entry
                return
            if exact is None or exact[0]!=DIR: _bad()
            oid=exact[1]
    _bad()


def original_absence(ctx,settings,source,path,checkpoint):
    with held_original_absence(ctx,settings,source,path,checkpoint) as (oid,_): return oid


def _new_patch(path,algo,raw):
    if _lines(raw) is None or len(raw)>TEXT_LIMIT: _bad()
    oid=_blob(raw,algo); a,b=_quoted("a/",path),_quoted("b/",path)
    parts=[b"diff --git "+a+b" "+b+b"\n",b"new file mode 100644\n",b"index "+b"0"*len(oid)+b".."+oid.encode()+b"\n"]
    lines=_records(raw)
    if lines:
        parts += [b"--- /dev/null\n",b"+++ "+b+b"\n",b"@@ -0,0 +1,"+str(len(lines)).encode()+b" @@\n"]
        for line in lines:
            parts.append(b"+"+line)
            if not line.endswith(b"\n"): parts.append(b"\n\\ No newline at end of file\n")
    patch=b"".join(parts)
    if len(patch)>LIMIT: _bad()
    return oid,patch


def prepare(ctx,settings,op,parent,item,checkpoint):
    owner=_owner(ctx,op); source=owner["source"]; path=item["path"]
    folder,_,name=path.rpartition("/")
    if folder!=parent or _source_name(parent,name)!=path or not 0<=item["bytes"]<=TEXT_LIMIT: _bad()
    tree_oid=original_absence(ctx,settings,source,path,checkpoint)
    with _directory(ctx,op,parent) as (tree,wanted):
        names,start=_names(tree,parent,wanted,checkpoint)
        if name not in names: _bad()
        handle,identity=tree.open_file(path)
        try:
            if identity!=item["origin"] or _stamp(tree.api,handle)!=item["written"]: _bad()
            reader=WindowsHandleReader(tree.api,handle,TEXT_LIMIT)
            if reader.size!=item["bytes"]: _bad()
            checkpoint(); reader.seek(0); raw=reader.read(reader.size)
            if not reader.verify_unchanged() or len(raw)!=reader.size or start!=_stamp(tree.api,tree.nodes[parent][1]): _bad()
        finally: tree.api.k.CloseHandle(handle)
        parent_id=tree.origins[parent]
    checkpoint(); oid,patch=_new_patch(path,source["objectFormat"],raw)
    fields={"install":ctx.install_state["installId"],"copy":op,"manifest":owner["manifestDigest"],"path":path,
            "commit":source["commit"],"tree":source["tree"],"parentTree":tree_oid,"baseEntry":"proven_absent","mode":"100644",
            "parentOrigin":parent_id,"selectedOrigin":identity,"sourceBytes":len(raw),"sourceSha":hashlib.sha256(raw).hexdigest(),
            "sourceOid":oid,"bytes":len(patch),"patchSha":hashlib.sha256(patch).hexdigest()}
    lines=_lines(raw)
    return {"fields":fields,"patch":patch,"excerpt":"\n".join(v[:120] for v in lines[:12]),
            "partial":len(lines)>12 or any(len(v)>120 for v in lines[:12])}


def key(f): return hashlib.sha256(_json([PROFILE,f])).hexdigest()[:32]


def _valid(ctx,op,f,owner):
    if not isinstance(f,dict) or set(f)!={"install","copy","manifest","path","commit","tree","parentTree","baseEntry","mode",
        "parentOrigin","selectedOrigin","sourceBytes","sourceSha","sourceOid","bytes","patchSha"}: return False
    source=owner["source"]
    if not isinstance(f["path"],str) or source["objectFormat"] not in ("sha1","sha256"): return False
    parent,_,name=f["path"].rpartition("/")
    oid=r"[0-9a-f]{"+str(40 if source["objectFormat"]=="sha1" else 64)+"}"
    return (f["install"]==ctx.install_state["installId"] and f["copy"]==op and f["manifest"]==owner["manifestDigest"]
            and f["commit"]==source["commit"] and f["tree"]==source["tree"] and f["baseEntry"]=="proven_absent" and f["mode"]=="100644"
            and _origin(f["parentOrigin"]) and _origin(f["selectedOrigin"]) and _source_name(parent,name)==f["path"]
            and all(isinstance(f[k],str) and re.fullmatch(oid,f[k]) for k in ("tree","commit","parentTree","sourceOid"))
            and all(isinstance(f[k],str) and re.fullmatch("[a-f0-9]{64}",f[k]) for k in ("sourceSha","patchSha"))
            and type(f["sourceBytes"]) is int and 0<=f["sourceBytes"]<=TEXT_LIMIT and type(f["bytes"]) is int and 0<f["bytes"]<=LIMIT)


def records(ctx,op):
    owner=_owner(ctx,op); rows={}; seen=set()
    for n in range(BANK):
        _,slot,_,_=output_names(ctx,op,"0"*32,n,added=True)
        r=_read(ctx,slot,16384)
        if r is None: continue
        f=r["fields"]; rid=r["id"]
        if (r.get("format")!=PROFILE or not _valid(ctx,op,f,owner) or rid!=key(f) or rid in seen
                or type(r.get("at")) is not int or not 0<=r["at"]<=4102444800000): _bad()
        seen.add(rid); rows[n]=r
    return rows


def save(ctx,settings,op,result,locked,checkpoint):
    owner=_owner(ctx,op); f=result["fields"]; rid=key(f)
    if not _valid(ctx,op,f,owner): _bad()
    rows=records(ctx,op)
    for r in rows.values():
        if r["id"]==rid: return r  # never replay an unfinished save
    if len(rows)>=BANK: _bad()
    def current():
        checkpoint()
        if locked() or owner["source"]["environmentId"] not in dict(registered(ctx,settings)): _bad()
    current()
    # Save only the already-reviewed, immutable patch bytes. A later source-file
    # edit is not part of that confirmation; never go back for a replacement body.
    return publish_output(ctx,op,rid,f,result["patch"],next(n for n in range(BANK) if n not in rows),current,added=True)
