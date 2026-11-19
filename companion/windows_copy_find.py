"""Literal owner search of named DIRECT members of one retained whole-copy directory. No filesystem scan."""
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import hashlib
import ntpath
import unicodedata

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader
from windows_git_copy import slots
from windows_local_read_handle import EXCLUDED, attributes, open_fixed
from windows_source_diff import _lines
from windows_source_inventory import load
from windows_source_patch import _blob
from windows_source_publisher import DirectoryTree

FILES=32
TOTAL=1024*1024
LEAF=64*1024
VISIBLE=80


def bad(): raise PlatformError("Cannot verify the selected original local search scope")


def valid(word):
    return (isinstance(word,str) and 1<=len(word)<=96 and bool(word.strip())
            and not any(unicodedata.category(c) in ("Cc","Cf","Cs","Zl","Zp") for c in word) and len(word.encode("utf-16le"))<=192)


@dataclass(frozen=True)
class Member:
    path: str
    size: int
    sha: str
    oid: str
    birth: tuple


@dataclass(frozen=True)
class Scope:
    install: str
    operation: str
    manifest: str
    commit: str
    algo: str
    root: tuple
    folder: str
    chain: tuple
    members: tuple


def catalog(ctx,op):
    hits=[r for _,r in slots(ctx) if r["operation"]==op]
    if len(hits)!=1 or "selectedFile" in hits[0]["source"]: bad()  # never expand a one-file reservation
    row=hits[0]; src=row["source"]
    root,_,rows,_,dirs,leaves=load(ctx,op)
    items={p:Member(p,r["bytes"],r["sha256"],r["object"],tuple(leaves[p])) for p,r in rows.items()
           if r["mode"]=="100644" and r["bytes"]<=LEAF}
    return (ctx.install_state["installId"],op,row["manifestDigest"],src["commit"],src["objectFormat"],tuple(root)),dirs,items,tuple(rows)


def scope(book,folder,paths):
    fixed,dirs,items,_=book
    if folder and folder not in dirs: bad()
    if not 1<=len(paths)<=FILES or len(set(paths))!=len(paths): bad()
    members=tuple(items[p] for p in sorted(paths))
    if any(m.path.rpartition("/")[0]!=folder for m in members) or sum(m.size for m in members)>TOTAL: bad()
    parts=folder.split("/") if folder else []
    chain=tuple(("/".join(parts[:n]),tuple(dirs["/".join(parts[:n])])) for n in range(1,len(parts)+1))
    return Scope(*fixed,folder,chain,members)


@contextmanager
def bound(ctx,approved,check):
    check()
    actual=scope(catalog(ctx,approved.operation),approved.folder,[m.path for m in approved.members])
    if actual!=approved: bad()
    with ExitStack() as stack:
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state))
        ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        base=ntpath.join(ctx.state,"source-"+actual.operation)
        if attributes(ctx.backend.api,base)&EXCLUDED: bad()
        tree=DirectoryTree(ctx,base,stack,list(actual.root))
        for d,identity in actual.chain:
            check()
            if attributes(tree.api,ntpath.join(base,*d.split("/")))&EXCLUDED: bad()
            tree.bind_dir(d,list(identity))
        check(); yield tree,actual
        check(); ctx.check_current_pointer()


def lines(ctx,tree,item,algo):
    # Inspect the expected physical birth BEFORE opening any body's stream. Never inspect the
    # contents of a replacement. Same-size changes to the original are discarded before decoding.
    path=ntpath.join(tree.base,*item.path.split("/")); api=tree.api
    with ExitStack() as pins:
        h,info,birth=open_fixed(api,path,False,pins,owner_acl=True)
        api.private_access(h,ctx.sid)
        if birth!=item.birth: return "replaced",None
        if (info.sizeHigh<<32)+info.sizeLow!=item.size: return "changed",None
        h,_,birth=open_fixed(api,path,False,pins,body=True); api.private_access(h,ctx.sid)
        if birth!=item.birth: return "replaced",None
        reader=WindowsHandleReader(api,h,LEAF)
        reader.seek(0); raw=reader.read(LEAF)
        if (not reader.verify_unchanged() or len(raw)!=item.size or hashlib.sha256(raw).hexdigest()!=item.sha
                or _blob(raw,algo)!=item.oid): return "changed",None
    if _lines(raw) is None: return "notText",None
    text=raw.decode("utf-8-sig")
    if text.startswith("version https://git-lfs.github.com/spec/v1") or "\u2028" in text or "\u2029" in text: return "notText",None
    return "checked",text.replace("\r\n","\n").replace("\r","\n").split("\n")


def find(ctx,approved,word,check,progress):
    if not valid(word): bad()
    report={"checked":0,"changed":0,"replaced":0,"notText":0,"unreadable":0,"hitFiles":0,"hitLines":0,"hits":[]}
    with bound(ctx,approved,check) as (tree,actual):
        for idx,item in enumerate(actual.members,1):
            check()
            try: state,body=lines(ctx,tree,item,actual.algo)
            except (OSError,PlatformError): state,body="unreadable",None
            check(); report[state]+=1
            if body is not None:
                found=False
                for n,line in enumerate(body,1):
                    pos=line.find(word)
                    if pos<0: continue
                    found=True; report["hitLines"]+=1
                    if len(report["hits"])<VISIBLE:
                        left=max(0,pos-25); right=min(len(line),pos+max(len(word),65))
                        excerpt=("…" if left else "")+line[left:right]+("…" if right<len(line) else "")
                        report["hits"].append((item,n,pos,excerpt))
                report["hitFiles"]+=int(found)
            progress(f"已核对 {idx}/{len(actual.members)} 个所选原入口。")
    return report


def open_hit(ctx,approved,word,hit,check):
    if not valid(word): bad()
    item,n,position,_=hit
    if item not in approved.members: bad()
    # Original range/receipt stays bound, but only the particular selected hit is read again.
    with bound(ctx,approved,check) as (tree,actual):
        state,body=lines(ctx,tree,item,actual.algo)
        if state!="checked" or not 1<=n<=len(body) or body[n-1].find(word)!=position: bad()
        before="\r\n".join(body[:n-1])+("\r\n" if n>1 else "")+body[n-1][:position]
        start=len(before.encode("utf-16le"))//2
        return "\r\n".join(body),(start,start+len(word.encode("utf-16le"))//2)
