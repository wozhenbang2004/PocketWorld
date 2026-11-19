"""An owner-written /dev/null -> new ordinary source proposal; never create or edit that project file."""
from contextlib import ExitStack,contextmanager
import ntpath
import os
import re

from windows_added_source import SCAN, _directory, _fold, _native_compare, _new_patch, _owner, _source_name, _stamp, held_original_absence
from windows_git_copy import _json, _read
from windows_git_source import anchor_for_ids, registered
from windows_git_workfile import _attributes
from windows_owner_draft import MAX_TEXT, block, digest, unpack
from windows_source_inventory import _origin, load
from windows_source_patch import (FILENAME,LIMIT,NEW_DRAFT_BANK as BANK,NEW_DRAFT_PROFILE as PROFILE,
                                  checked_output,output_names,publish_output)
from windows_source_publisher import _ext, _origin as physical


def path_parts(path):
    if not isinstance(path,str): block()
    parent,_,name=path.rpartition("/")
    if _source_name(parent,name)!=path: block()
    return parent,name


@contextmanager
def original(ctx,settings,op,path,check):
    check(); parent,name=path_parts(path); owner=_owner(ctx,op); src=owner["source"]
    *_,workspace=load(ctx,op,work_root=True)  # no old/same-name directory evidence is backfilled
    with held_original_absence(ctx,settings,src,path,check) as (parent_tree,pins), _directory(ctx,op,parent) as (tree,wanted):
        repo=physical(pins.api,pins.api.info(pins.nodes[()][1])); git=physical(pins.api,pins.api.info(pins.nodes[(".git",)][1]))
        sub=src.get("selectedProject")
        if (sub and (sub["parent"]!=list(workspace) or sub["directories"][-1]!=repo or sub["git"]!=git)
                or not sub and tuple(repo)!=workspace): block()
        directory,h,_=tree.nodes[parent]; stamp=_stamp(tree.api,h)
        by=_native_compare(tree.api,check)
        def collision(n): return _fold(n)==_fold(name) or by(n,name)==0
        if any(collision(n) for n in wanted): block()
        # Metadata in THIS explicitly named original parent only. Never read an existing child,
        # recurse, or infer absence from an unreadable/cropped listing.
        def current():
            check()
            if src["environmentId"] not in dict(registered(ctx,settings)) or stamp!=_stamp(tree.api,h): block()
            with os.scandir(_ext(directory)) as listing:
                for i,entry in enumerate(listing):
                    if not i%64: check()
                    if i>=SCAN or collision(entry.name): block()
            if (stamp!=_stamp(tree.api,h) or _attributes(tree.api,ntpath.join(directory,name),absent=True) is not None): block()
        current()
        yield {"install":ctx.install_state["installId"],"copy":op,"manifest":owner["manifestDigest"],"path":path,
               "commit":src["commit"],"tree":src["tree"],"parentTree":parent_tree,"parentOrigin":tree.origins[parent],
               "repositoryObserved":repo,"gitObserved":git,"baseEntry":"proven_absent","mode":"100644"},src["objectFormat"],current


def scope(ctx,settings,op,path,check):
    with original(ctx,settings,op,path,check) as (proof,algo,_): return proof,algo


def proposal(reviewed,display):
    if not isinstance(display,str) or display.startswith("\ufeff"): block()
    text=display.replace("\r\n","\n")
    if "\r" in text: block()
    raw=text.encode("utf-8"); unpack(raw)  # strict bounded UTF-8; empty source is valid, requires explicit approval
    proof,algo=reviewed; target,patch=_new_patch(proof["path"],algo,raw)
    fields={**proof,"draftBytes":len(raw),"draftSha":digest(raw),"draftOid":target,"bytes":len(patch),"patchSha":digest(patch)}
    return fields,patch


def key(f): return digest(_json([PROFILE,f]))[:32]


def dest(ctx,op,f): return ntpath.join(output_names(ctx,op,key(f),0,added=False,newdraft=True)[3],FILENAME)


def records(ctx,op):
    owner=_owner(ctx,op); src=owner["source"]; rows={}; seen=set()
    fields={"install","copy","manifest","path","commit","tree","parentTree","parentOrigin","repositoryObserved","gitObserved",
            "baseEntry","mode","draftBytes","draftSha","draftOid","bytes","patchSha"}
    width=40 if src["objectFormat"]=="sha1" else 64
    for n in range(BANK):
        slot=output_names(ctx,op,"0"*32,n,added=False,newdraft=True)[1]
        r=_read(ctx,slot,16384)
        if r is None: continue
        f=r.get("fields")
        if (not isinstance(f,dict) or set(f)!=fields or f["install"]!=ctx.install_state["installId"] or f["copy"]!=op
                or f["manifest"]!=owner["manifestDigest"] or f["commit"]!=src["commit"] or f["tree"]!=src["tree"]
                or f["baseEntry"]!="proven_absent" or f["mode"]!="100644"
                or not all(_origin(f[k]) for k in ("parentOrigin","repositoryObserved","gitObserved"))
                or type(f["draftBytes"]) is not int or not 0<=f["draftBytes"]<=MAX_TEXT
                or type(f["bytes"]) is not int or not 0<f["bytes"]<=LIMIT
                or any(not isinstance(f[k],str) or not re.fullmatch("[a-f0-9]{"+str(size)+"}",f[k])
                       for k,size in (("parentTree",width),("draftOid",width),("draftSha",64),("patchSha",64)))
                or r.get("format")!=PROFILE or r.get("id")!=key(f) or r["id"] in seen or type(r.get("at")) is not int
                or not 0<=r["at"]<=4102444800000): block()
        sub=src.get("selectedProject")
        if (anchor_for_ids(ctx,src["environmentId"],f["repositoryObserved"][:3],f["gitObserved"][:3])!=src["repositoryAnchor"]
                or sub and (f["repositoryObserved"]!=sub["directories"][-1] or f["gitObserved"]!=sub["git"])): block()
        path_parts(f["path"]); seen.add(r["id"]); rows[n]=r
    return rows


_BODY_FIELDS=frozenset(("draftBytes","draftSha","draftOid","bytes","patchSha"))


@contextmanager
def held_saved(ctx,op,row,check):
    # Only OUR fixed new-file profile can supply an empty base. No working file,
    # generic patch importer, arbitrary path, fuzzy application, or other member of a set.
    from windows_patch_set import _member,_patch
    def locked():
        check()
        return False
    check()
    if not any(_json(r)==_json(row) for r in records(ctx,op).values()): block()
    src=_owner(ctx,op)["source"]; algo=src["objectFormat"]
    f=row["fields"]; proof={k:v for k,v in f.items() if k not in _BODY_FIELDS}
    with ExitStack() as pins:
        raw=checked_output(ctx,op,row,locked,show=False,added=False,newdraft=True,content=True,hold=pins)
        target=_patch(raw,_member("newdraft",row),f,algo,proposal_text=True)
        bom,eol,display=unpack(target)
        if bom or eol!="\n" or proposal((proof,algo),display)!=(f,raw): block()
        check()
        yield (proof,algo),display


def read_saved(ctx,op,row,check):
    with held_saved(ctx,op,row,check) as (_,display): return display  # historic text only; no repository access


def restore(ctx,settings,op,row,check):
    with held_saved(ctx,op,row,check) as (old,display):
        with original(ctx,settings,op,old[0]["path"],check) as (proof,algo,current):
            if (proof,algo)!=old: block()
            current()
            return (proof,algo),display


def save(ctx,settings,op,reviewed,display,expected,check,*,previous=None):
    proof,algo=reviewed
    with ExitStack() as held:
        again,current_algo,current=held.enter_context(original(ctx,settings,op,proof["path"],check))
        if (again,current_algo)!=(proof,algo): block()
        f,raw=proposal((again,current_algo),display)
        if (f,raw)!=expected: block()
        if previous is not None:
            prior,old_text=held.enter_context(held_saved(ctx,op,previous,check))
            if prior!=(again,current_algo) or f["draftSha"]==previous["fields"]["draftSha"] or old_text==display: block()
            # The old physically verified read-only file stays pinned until the independent new receipt is published.
        rows=records(ctx,op); rid=key(f)
        old=next((r for r in rows.values() if r["id"]==rid),None)
        if old is not None: return old
        if len(rows)>=BANK: block()
        current()
        return publish_output(ctx,op,rid,f,raw,next(n for n in range(BANK) if n not in rows),
                              current,added=False,newdraft=True)


def check(ctx,op,row,locked,*,show=False):
    if locked() or row not in records(ctx,op).values(): block()
    return checked_output(ctx,op,row,locked,show=show,added=False,newdraft=True)
