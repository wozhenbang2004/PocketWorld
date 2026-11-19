"""Owner reads exactly ONE path at ONE actual direct parent, from the same local Git object store."""
from contextlib import contextmanager
from dataclasses import dataclass
import difflib
import hashlib
import re

from platform_backend import PlatformError
from windows_git_one_file import MAX_TEXT,child,node,pieces
from windows_git_source import _object_body,_tree_id
from windows_git_tree import DIR,FILE,EXEC,LINK,SUB
from windows_git_workfile import _content
from windows_source_publisher import _origin


def bad(): raise PlatformError("Selected ordinary source and direct Git parent cannot be bound")


def _parents(raw,algo,this):
    # Canonical tree, ordered direct parent headers, then author. Indented mergetag/gpgsig lines
    # or commit-message prose are NOT parent declarations. No ref names, revision expressions or replacements.
    _tree_id(raw,algo)
    lines=raw.split(b"\n\n",1)[0].split(b"\n"); i=1; parents=[]
    pattern=rb"[0-9a-f]{"+str(hashlib.new(algo).digest_size*2).encode()+b"}"
    while i<len(lines) and lines[i].startswith(b"parent "):
        value=lines[i][7:]
        if not re.fullmatch(pattern,value) or len(parents)>=8: bad()
        p=value.decode("ascii")
        if p==this or p in parents or not any(ch!="0" for ch in p): bad()
        parents.append(p); i+=1
    rest=lines[i:]
    if (not rest or not rest[0].startswith(b"author ") or sum(v.startswith(b"author ") for v in rest)!=1
            or sum(v.startswith(b"committer ") for v in rest)!=1 or any(v.lower().split(None,1)[:1]==[b"parent"] and not v.startswith(b" ") for v in rest)): bad()
    return tuple(parents)


@contextmanager
def _selected(ctx,settings,src,file,check):
    if ("selectedFile" in src or src.get("sourceVerified") is not True or not file.repository or not file.git
            or file.mode not in ("100644","100755")): bad()
    check()
    with node(ctx,settings,src,file.path,check) as (pins,packs,mode,oid):
        origin=lambda h:tuple(_origin(pins.api,pins.api.info(h)))
        if (origin(pins.nodes[()][1])!=file.repository or origin(pins.nodes[(".git",)][1])!=file.git
                or mode.decode("ascii")!=file.mode or oid!=file.oid): bad()
        raw,_=_object_body(pins,packs,src["commit"],b"commit",256*1024)
        if _tree_id(raw,packs.algo)!=src["tree"]: bad()
        parents=_parents(raw,packs.algo,src["commit"]); check()
        yield pins,packs,parents
        check()


def parents(ctx,settings,src,file,check):
    with _selected(ctx,settings,src,file,check) as (_,__,ids): return ids


@dataclass(frozen=True)
class Entry:
    parent: str
    tree: str
    state: str
    mode: str|None=None
    oid: str|None=None


def _entry(pins,packs,ids,index,expected,path,check):
    if ids!=expected or type(index) is not int or not 0<=index<len(ids): bad()
    parent=ids[index]; raw,_=_object_body(pins,packs,parent,b"commit",256*1024)
    top=_tree_id(raw,packs.algo); oid=top; mode=DIR
    for part in pieces(path):
        check()
        if mode!=DIR: return Entry(parent,top,"ancestor_not_directory")
        result=child(pins,packs,oid,part,check,missing_ok=True)
        if result is None: return Entry(parent,top,"absent")
        mode,oid=result
    if mode not in (FILE,EXEC): return Entry(parent,top,{DIR:"directory",LINK:"symlink",SUB:"submodule"}[mode])
    return Entry(parent,top,"file",mode.decode("ascii"),oid)


def inspect(ctx,settings,src,file,expected,index,check):
    with _selected(ctx,settings,src,file,check) as (pins,packs,ids):
        return _entry(pins,packs,ids,index,expected,file.path,check)


def compare(ctx,settings,src,file,expected,index,reviewed,check):
    with _selected(ctx,settings,src,file,check) as (pins,packs,ids):
        if reviewed.state!="file" or _entry(pins,packs,ids,index,expected,file.path,check)!=reviewed: bad()
        old,_=_object_body(pins,packs,reviewed.oid,b"blob",MAX_TEXT); check()
        new,_=_object_body(pins,packs,file.oid,b"blob",MAX_TEXT); check()
        if len(new)!=file.size or hashlib.sha256(new).hexdigest()!=file.sha: bad()
        a,af=_content(old); b,bf=_content(new)
        # Separate content identity/mode from the normalized reading view; don't hide a missing trailing newline.
        diff="\r\n".join(difflib.unified_diff(a.split("\n"),b.split("\n"),fromfile="所选父提交（文本）",tofile="原选提交（文本）",
                                             n=3,lineterm="")) if a!=b else None
        if diff is not None and len(diff.encode("utf-16le"))//2>262144: bad()
        check()
        return {"oldBytes":len(old),"oldSha":hashlib.sha256(old).hexdigest(),"oldFormat":af,"newFormat":bf,
                "same":old==new,"diff":diff,"oldText":a.replace("\n","\r\n")}
