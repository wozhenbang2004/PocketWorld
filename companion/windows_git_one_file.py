"""Read one committed regular text file. No worktree file, recursive tree walk or output creation."""
from contextlib import contextmanager
from dataclasses import dataclass
from functools import cmp_to_key
import hashlib
import unicodedata

from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_git_source import _object_body
from windows_git_tree import DIR, FILE, EXEC, TreeIssue, _component, _native_compare, entries
from windows_source_diff import _lines, original_commit
from windows_source_publisher import _origin

MAX_TEXT=64*1024
MAX_DEPTH=32


def reject(): raise PlatformError("Selected committed file or explicit path cannot be verified")


def pieces(path,*,root=False):
    if path=="" and root: return []
    parts=relative_parts(path)
    if not parts or len(parts)>MAX_DEPTH or "/".join(parts)!=path: reject()
    prefix=""
    for name in parts: prefix=_component(prefix,name.encode("utf-8"))[0]+"/"
    return parts


def child(pins,packs,oid,name,check,*,missing_ok=False):
    raw,_=_object_body(pins,packs,oid,b"tree",1024*1024)
    exact=None; opaque=False; fold=unicodedata.normalize("NFKC",name).casefold(); compare=_native_compare(pins.api,check)
    for mode,label,object_id in entries(raw,packs.width):
        if label==name.encode("utf-8"):
            if exact is not None: reject()
            exact=(mode,object_id); continue
        try: spelling=label.decode("utf-8")
        except UnicodeError:
            opaque=True; continue
        if unicodedata.normalize("NFKC",spelling).casefold()==fold or compare(spelling,name)==0: reject()
    if exact is None and (not missing_ok or opaque): reject()
    return exact  # original positive reader unchanged; no absence if the complete spelling check was inconclusive


@contextmanager
def node(ctx,settings,src,path,check):
    if "selectedFile" in src and path!=src["selectedFile"]["path"]: reject()
    parts=pieces(path,root=True)
    with original_commit(ctx,settings,src,check) as (pins,packs,oid):
        mode=DIR
        for name in parts:
            check()
            if mode!=DIR: reject()  # never follow a submodule, link or file as a directory
            mode,oid=child(pins,packs,oid,name,check)
        yield pins,packs,mode,oid


def folder(ctx,settings,src,path,check):
    # One explicitly selected committed TREE. No OS directory scan and no child blobs.
    result=[]
    with node(ctx,settings,src,path,check) as (pins,packs,mode,oid):
        if mode!=DIR: reject()
        raw,_=_object_body(pins,packs,oid,b"tree",1024*1024)
        names=[]; folds={}; ambiguous=set()
        for index,(kind,raw_name,child_id) in enumerate(entries(raw,packs.width)):
            if index%128==0: check()
            try: spelling=raw_name.decode("utf-8")
            except UnicodeError: continue
            names.append(spelling); folded=unicodedata.normalize("NFKC",spelling).casefold()
            if folded in folds: ambiguous.update((folds[folded],spelling))
            else: folds[folded]=spelling
            if kind not in (DIR,FILE,EXEC): continue
            try: full,name=_component(path+"/" if path else "",raw_name)
            except TreeIssue: continue  # No names or omitted counts for private/unsafe members.
            result.append((kind==DIR,name,full,child_id))
        compare=_native_compare(pins.api,check); native=sorted(names,key=cmp_to_key(compare))
        for left,right in zip(native,native[1:]):
            if compare(left,right)==0: ambiguous.update((left,right))
    return sorted((r for r in result if r[1] not in ambiguous),key=lambda r:(not r[0],r[1].casefold(),r[1]))


@dataclass(frozen=True)
class TextFile:
    path: str
    oid: str
    size: int
    sha: str
    text: str
    mode: str
    repository: tuple | None
    git: tuple | None


def read(ctx,settings,src,path,expected,check,*,with_bytes=False):
    if not path: reject()
    with node(ctx,settings,src,path,check) as (pins,packs,mode,oid):
        if mode not in (FILE,EXEC) or (expected is not None and expected!=oid): reject()
        check()
        raw,_=_object_body(pins,packs,oid,b"blob",MAX_TEXT)
        if _lines(raw) is None: reject()
        text=raw.decode("utf-8-sig")
        if text.startswith("version https://git-lfs.github.com/spec/v1") or any(c in text for c in ("\u2028","\u2029")): reject()
        # CR, LF and BOM are presentation only; the size, Git OID and SHA are for unmodified original bytes.
        shown=text.replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n")
        check()
        origin=lambda h: tuple(_origin(pins.api,pins.api.info(h)))
        try: root_id,git_id=origin(pins.nodes[()][1]),origin(pins.nodes[(".git",)][1])
        except (OSError,PlatformError):
            if with_bytes: reject()
            root_id=git_id=None  # Optional save evidence must not break the original text reader.
        result=TextFile(path,oid,len(raw),hashlib.sha256(raw).hexdigest(),shown,mode.decode("ascii"),root_id,git_id)
        return (result,raw) if with_bytes else result
