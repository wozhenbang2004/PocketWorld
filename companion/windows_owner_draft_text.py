"""One inert, local text artifact from one verified owner draft. Never write to the project or its copy."""
from contextlib import ExitStack
import ntpath
import os
import re

from platform_windows import WindowsHandleReader
import windows_owner_draft as draft
from windows_git_copy import _json, _read, _store
from windows_owner_draft_restore import held_draft
from windows_source_inventory import _origin, show_bound_child
from windows_source_patch import _blob
from windows_source_publisher import DirectoryTree, _ext


NAME="draft.txt"
PROFILE="pocketworld.windows-owner-draft-full-text.v1"


def folder(ctx,rid):
    if not isinstance(rid,str) or not re.fullmatch("[a-f0-9]{32}",rid): draft.block()
    return ntpath.join(ctx.state,"review-draft-text-"+rid)


def destination(ctx,row):
    return ntpath.join(folder(ctx,row["id"]),NAME)


def ref(ctx,op,row):
    # Called only after the immutable original slot has been checked. Its ID binds the complete
    # draft fields incl. install, relative filename, original physical birth, commit and all OIDs.
    f=row["fields"]
    if f["install"]!=ctx.install_state["installId"] or f["copy"]!=op or draft.key(f)!=row["id"]: draft.block()
    return {"format":PROFILE,"install":f["install"],"copy":op,"draft":row["id"],"patchSha":f["patchSha"],
            "contentSha":f["draftSha"],"bytes":f["draftBytes"],"targetOid":f["draftOid"]}


def _phase(rid,phase): return "source-draft-text-v1-"+rid+"-"+phase+".json"


def _empty(ctx,base):
    # Absence is an observation only. Native directory creation and handle-relative file publication
    # also have to be absent-only. An unreceipted pre-existing object is never ours to adopt.
    try: handle=ctx.backend.api.open(_ext(base),directory=True)
    except OSError as exc:
        if getattr(exc,"winerror",None)==2: return
        raise
    ctx.backend.api.k.CloseHandle(handle); draft.block()


def _leaf(tree,stack,f,origin,algo):
    h,identity=tree.open_file(NAME); stack.callback(tree.api.k.CloseHandle,h)
    if not _origin(origin) or identity!=origin: draft.block()
    view=WindowsHandleReader(tree.api,h,draft.MAX_TEXT)
    if view.size!=f["draftBytes"]: draft.block()
    view.seek(0); raw=view.read(draft.MAX_TEXT)
    if (not view.verify_unchanged() or len(raw)!=f["draftBytes"] or draft.digest(raw)!=f["draftSha"]
            or algo not in ("sha1","sha256") or _blob(raw,algo)!=f["draftOid"]): draft.block()
    return raw


def _state(ctx,op,row,locked,*,action="state"):
    if locked() or action not in ("state","content","locate"): draft.block()
    rid,f=row["id"],row["fields"]; base=folder(ctx,rid); proof=ref(ctx,op,row)
    with ExitStack() as stack:
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state))
        ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        intent=_read(ctx,_phase(rid,"intent"),4096); end=_read(ctx,_phase(rid,"result"),4096)
        if intent is None:
            if end is not None: draft.block()
            _empty(ctx,base)
            return "none",None
        if _json(intent)!=_json(proof): draft.block()
        if end is None: return "pending",None
        if (set(end)!={"proof","folder","file"} or _json(end["proof"])!=_json(proof)
                or not _origin(end["folder"]) or not _origin(end["file"])): draft.block()
        tree=DirectoryTree(ctx,base,stack,end["folder"])
        raw=_leaf(tree,stack,f,end["file"],draft.owner(ctx,op)["source"]["objectFormat"])
        if locked(): draft.block()
        if action=="locate":
            with os.scandir(_ext(base)) as files:
                first=next(files,None)
                if first is None or first.name!=NAME or next(files,None) is not None: draft.block()
            show_bound_child(ctx,stack,base,ntpath.join(base,NAME),locked)
        return "saved",raw if action=="content" else None


def inspect(ctx,op,row,locked,*,action="state"):
    if draft.check(ctx,op,row,locked)!="saved": draft.block()
    return _state(ctx,op,row,locked,action=action)


def prepare(ctx,settings,op,row,locked,checkpoint):
    # 'None' and an intentionally empty draft are not interchangeable.
    with held_draft(ctx,settings,op,row,locked,checkpoint) as (base,display,_):
        return draft.encode(base,display)


def save(ctx,settings,op,row,reviewed,locked,checkpoint):
    if type(reviewed) is not bytes or len(reviewed)>draft.MAX_TEXT: draft.block()
    with held_draft(ctx,settings,op,row,locked,checkpoint) as (base,display,src), ExitStack() as stack:
        raw=draft.encode(base,display)
        if raw!=reviewed: draft.block()
        rid,f=row["id"],row["fields"]; proof=ref(ctx,op,row); path=folder(ctx,rid)
        def current():
            checkpoint()
            if locked() or src["environmentId"] not in dict(draft.registered(ctx,settings)): draft.block()
        def factual():
            ctx.check_current_pointer(); ctx.backend.assert_private_directory(ctx.state,ctx.sid)
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state))
        ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        current()
        previous,_=_state(ctx,op,row,locked)
        if previous!="none": return previous  # never retry or adopt uncertain publication
        _store(ctx,_phase(rid,"intent"),proof,4096,current)
        current()
        tree=DirectoryTree(ctx,path,stack,None,create_root=True)
        capture=ctx.backend.create_private_snapshot(path,NAME,raw,maximum=draft.MAX_TEXT,current=current,bind_birth=True)
        origin=capture.get("fileOrigin")
        if (capture.get("parentIdentity")!=tree.nodes[""][2] or capture.get("bytes")!=len(raw)
                or capture.get("sha256")!=f["draftSha"] or not _origin(origin) or capture.get("fileIdentity")!=origin[:3]
                or _leaf(tree,stack,f,origin,base.algo)!=raw): draft.block()
        # The exact bytes may already have arrived if the window closed after the rename. This is
        # evidence for that one publication, not permission to save again.
        _store(ctx,_phase(rid,"result"),{"proof":proof,"folder":tree.origins[""],"file":origin},4096,factual)
        return "saved"
