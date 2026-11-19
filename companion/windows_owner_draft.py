"""Owner-authored text on one original Git blob. Never write the repository or its copy."""
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
import hashlib
import ntpath
import re

from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, relative_parts
from windows_git_copy import _json, _read, slots
from windows_git_source import registered
from windows_git_tree import _component
from windows_source_diff import _lines, bound_blob
from windows_source_inventory import _origin, load
from windows_source_patch import (DRAFT_BANK, DRAFT_PROFILE, FILENAME, LIMIT, _blob, checked_output,
                                  output_names, publish_output, render)
from windows_source_publisher import DirectoryTree

MAX_TEXT = 32*1024
BOM = b"\xef\xbb\xbf"


def block():
    raise PlatformError("Cannot verify the selected original or owner draft")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def path_ok(path):
    prefix=""
    for part in relative_parts(path):
        prefix=_component(prefix,part.encode("utf-8"))[0]+"/"
    return prefix[:-1]==path


def index(ctx,op):
    # Only the already-retained manifest; never scan the current repository/copy.
    _,_,rows,_,_,_=load(ctx,op)
    return {p:r["bytes"] for p,r in rows.items() if r["mode"]=="100644" and r["bytes"]<=MAX_TEXT and path_ok(p)}


def owner(ctx,op):
    if not isinstance(op,str) or not re.fullmatch("[a-f0-9]{32}",op): block()
    original=next((r for _,r in slots(ctx) if r["operation"]==op),None)
    if original is None: block()
    return original


@dataclass(frozen=True)
class Base:
    path: str
    commit: str
    algo: str
    oid: str
    origin: tuple
    raw: bytes


@contextmanager
def original(ctx,settings,op,path,checkpoint):
    checkpoint()
    root,_,rows,_,dirs,files=load(ctx,op)
    row=rows.get(path); src=owner(ctx,op)["source"]
    if not row or row["mode"]!="100644" or row["bytes"]>MAX_TEXT or not path_ok(path): block()
    with ExitStack() as stack:
        # Consult that registered repository's original commit, not its HEAD/working tree.
        blob=stack.enter_context(bound_blob(ctx,settings,src,path,row,checkpoint))
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state))
        ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        tree=DirectoryTree(ctx,ntpath.join(ctx.state,"source-"+op),stack,root)
        parts=relative_parts(path)
        for n in range(1,len(parts)):
            parent="/".join(parts[:n]); tree.bind_dir(parent,dirs[parent])
        h,birth=tree.open_file(path); stack.callback(tree.api.k.CloseHandle,h)
        reader=WindowsHandleReader(tree.api,h,MAX_TEXT)
        if birth!=files[path] or reader.size!=row["bytes"]: block()
        reader.seek(0); raw=reader.read(MAX_TEXT)
        if (not reader.verify_unchanged() or raw!=blob or digest(raw)!=row["sha256"]
                or _blob(raw,src["objectFormat"])!=row["object"]): block()
        unpack(raw)
        checkpoint()
        yield Base(path,src["commit"],src["objectFormat"],row["object"],tuple(birth),raw),src


def prepare(ctx,settings,op,path,checkpoint):
    with original(ctx,settings,op,path,checkpoint) as (base,_): return base


def unpack(raw):
    # The Windows EDIT uses CRLF; never silently normalize mixed endings, BOM or trailing LF on disk.
    if len(raw)>MAX_TEXT or _lines(raw) is None: block()
    bom=BOM if raw.startswith(BOM) else b""
    text=raw[len(bom):].decode("utf-8")
    if text.startswith("version https://git-lfs.github.com/spec/v1") or any(c in text for c in ("\u2028","\u2029")): block()
    rest=text.replace("\r\n","")
    if "\r" in rest or ("\r\n" in text and "\n" in rest): block()
    ending="\r\n" if "\r\n" in text else "\n"
    return bom,ending,text.replace("\r\n","\n").replace("\n","\r\n")


def encode(base,display):
    bom,eol,_=unpack(base.raw)
    text=display.replace("\r\n","\n")
    if "\r" in text or text.startswith("\ufeff"): block()
    result=bom+text.replace("\n",eol).encode("utf-8")
    unpack(result)
    return result


def proposal(ctx,op,base,display,checkpoint):
    new=encode(base,display)
    patch,target=render(base.path,"100644",base.oid,base.algo,base.raw,new,checkpoint)
    f={"install":ctx.install_state["installId"],"copy":op,"path":base.path,"commit":base.commit,
       "originalOid":base.oid,"draftOid":target,"originalOrigin":list(base.origin),"baseSha":digest(base.raw),
       "draftSha":digest(new),"draftBytes":len(new),"patchSha":digest(patch),"bytes":len(patch)}
    return f,patch


def key(f): return digest(_json([DRAFT_PROFILE,f]))[:32]


def destination(ctx,op,f):
    return ntpath.join(output_names(ctx,op,key(f),0,added=False,draft=True)[3],FILENAME)


def records(ctx,op):
    src=owner(ctx,op)["source"]
    result={}; seen=set()
    for n in range(DRAFT_BANK):
        row=_read(ctx,f"source-draft-v1-{op}-slot-{n}.json",16384)
        if row is None: continue
        f=row.get("fields")
        if (not isinstance(f,dict) or set(f)!={"install","copy","path","commit","originalOid","draftOid","originalOrigin",
                "baseSha","draftSha","draftBytes","patchSha","bytes"} or f["install"]!=ctx.install_state["installId"]
                or f["copy"]!=op or f["commit"]!=src["commit"] or row.get("format")!=DRAFT_PROFILE or row.get("id")!=key(f)
                or type(row.get("at")) is not int or not 0<=row["at"]<=4102444800000 or row["id"] in seen
                or not _origin(f["originalOrigin"]) or not path_ok(f["path"])
                or type(f["bytes"]) is not int or not 0<f["bytes"]<=LIMIT
                or type(f["draftBytes"]) is not int or not 0<=f["draftBytes"]<=MAX_TEXT
                or any(not isinstance(f[k],str) or not re.fullmatch("[a-f0-9]{64}",f[k]) for k in ("baseSha","draftSha","patchSha"))
                or any(not isinstance(f[k],str) or not re.fullmatch("[a-f0-9]{"+str(40 if src["objectFormat"]=="sha1" else 64)+"}",f[k]) for k in ("originalOid","draftOid"))
                or f["originalOid"]==f["draftOid"] or f["baseSha"]==f["draftSha"]): block()
        seen.add(row["id"]); result[n]=row
    return result


def save(ctx,settings,op,base,display,reviewed,locked,checkpoint):
    def gate():
        checkpoint()
        if locked(): block()
    # Hold original source/copy handles during publication. Recompute the COMPLETE patch and match
    # the exact reviewed bytes, filename and receipt identity before reserving any output slot.
    with original(ctx,settings,op,base.path,gate) as (again,src):
        if again!=base: block()
        fields,raw=proposal(ctx,op,again,display,gate)
        if (fields,raw)!=reviewed: block()
        rid=key(fields); rows=records(ctx,op)
        old=next((r for r in rows.values() if r["id"]==rid),None)
        if old is not None: return old
        if len(rows)>=DRAFT_BANK: raise PlatformError("All original draft slots are occupied")
        def current():
            gate()
            if not dict(registered(ctx,settings)).get(src["environmentId"]): block()
        return publish_output(ctx,op,rid,fields,raw,next(n for n in range(DRAFT_BANK) if n not in rows),
                              current,added=False,draft=True)


def check(ctx,op,row,locked,*,show=False):
    if locked() or row not in records(ctx,op).values(): block()
    return checked_output(ctx,op,row,locked,show=show,added=False,draft=True)
