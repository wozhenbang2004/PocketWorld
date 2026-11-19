"""Bundle original, selected one-file Git patches. Never apply, run or transfer them."""
import hashlib
import re
import unicodedata

from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_added_source import _owner,records as added_records
from windows_git_copy import _json,_read
from windows_removed_source import records as removed_records
from windows_owner_draft import records as owner_drafts
from windows_new_source_draft import records as new_drafts
from windows_git_tree import _component,_native_compare
from windows_source_patch import (LIMIT,SET_BANK as BANK,SET_PROFILE as PROFILE,SET_LIMIT,PROPOSED_SET_BANK,PROPOSED_SET_PROFILE,
                                  _blob,_quoted,checked_output,output_names,publish_output,records as tracked_records)

READERS=(("edit",tracked_records),("add",added_records),("remove",removed_records),("draft",owner_drafts),("newdraft",new_drafts))
MAX=8
MARKER=b"\\ No newline at end of file"


def _bad(): raise PlatformError("Original selected single-file patch set cannot be verified")


def _sha(v): return hashlib.sha256(v).hexdigest()


def _hash(value,width): return isinstance(value,str) and re.fullmatch(r"[a-f0-9]{"+str(width)+"}",value) is not None


def _paths(ctx,members,checkpoint):
    ordered=[]; by=_native_compare(ctx.backend.api,checkpoint)
    def fold(x): return unicodedata.normalize("NFKC",x).casefold()
    for member in members:
        parts=relative_parts(member["path"]); prefix=""
        for part in parts:
            path,_=_component(prefix,part.encode("utf-8")); prefix=path+"/"
        for prev in ordered:
            for a,b in zip(parts,prev):
                if a==b: continue
                if by(a,b)==0 or fold(a)==fold(b): _bad()  # includes aliased parent folder spellings
                break
            else: _bad()  # same file twice, or a selected file is another selected file's parent
        ordered.append(parts)


def catalog(ctx,op):
    _owner(ctx,op)  # Only the original whole-copy bank; a one-file selection is never a multi-file scope.
    rows=[]; missing=False
    for kind,reader in READERS:
        try: rows.extend((kind,row) for row in reader(ctx,op).values())
        except Exception: missing=True
    return sorted(rows,key=lambda p:p[1]["at"],reverse=True),missing


def _member(kind,row):
    if kind not in ("edit","add","remove","draft","newdraft"): _bad()
    f=row["fields"]
    return {"kind":kind,"id":row["id"],"path":f["path"],"sha256":f["patchSha"],"bytes":f["bytes"],
            "base":({"state":"absent","tree":f["parentTree"]} if kind in ("add","newdraft") else {"state":"tracked","oid":f["originalOid"]}),
            "to":({"state":"remove_original_path"} if kind=="remove" else f["sourceOid"] if kind=="add" else
                  f["draftOid"] if kind in ("draft","newdraft") else f["currentOid"])}


def _patch(raw,member,fields,algo):
    # Accept only the original bounded writer's one-file format. A valid-looking
    # first header does not admit a second hidden file, a binary block or extra directives.
    if type(raw) is not bytes or len(raw)!=member["bytes"] or _sha(raw)!=member["sha256"] or not raw.endswith(b"\n"): _bad()
    p=member["path"]; a,b=_quoted("a/",p),_quoted("b/",p)
    lines=raw[:-1].split(b"\n")
    if lines.pop(0)!=b"diff --git "+a+b" "+b: _bad()
    kind=member["kind"]; added=kind in ("add","newdraft"); removed=kind=="remove"; whole=added or removed
    target=(member["base"]["oid"] if removed else member["to"]).encode()
    if whole:
        mode=b"deleted file mode "+fields["mode"].encode() if removed else b"new file mode 100644"
        base_oid,new_oid=(target,b"0"*len(target)) if removed else (b"0"*len(target),target)
        if len(lines)<2 or lines[:2]!=[mode,b"index "+base_oid+b".."+new_oid]: _bad()
        del lines[:2]
        count=fields["originalBytes" if removed else "draftBytes" if kind=="newdraft" else "sourceBytes"]
        digest=fields["originalSha" if removed else "draftSha" if kind=="newdraft" else "sourceSha"]
        if not lines:
            if count!=0 or digest!=_sha(b"") or _blob(b"",algo).encode()!=target: _bad()
            return
    else:
        stem=b"index "+member["base"]["oid"].encode()+b".."+target+b" "
        allowed=(stem+b"100644",) if kind=="draft" else (stem+b"100644",stem+b"100755")
        if not lines or lines.pop(0) not in allowed: _bad()
    if len(lines)<3 or lines[:2]!=[b"--- "+(b"/dev/null" if added else a),b"+++ "+(b"/dev/null" if removed else b)]: _bad()
    cursor=2; seen=0; was=[0,0]; reconstructed=bytearray()
    while cursor<len(lines):
        h=re.fullmatch(rb"@@ -([0-9]{1,6}),([0-9]{1,6}) \+([0-9]{1,6}),([0-9]{1,6}) @@",lines[cursor])
        if not h: _bad()
        old,before,new,after=map(int,h.groups()); cursor+=1
        if (not before and not after or old<was[0] or new<was[1] or before>1501 or after>1501
                or before and old<1 or after and new<1 or added and (seen or old or before or new!=1)
                or removed and (seen or old!=1 or new or after)): _bad()
        seen+=1; was=[old+before,new+after]; prior=None
        while before or after:
            if cursor>=len(lines): _bad()
            line=lines[cursor]; cursor+=1; tag=line[:1]
            if tag not in (b" ",b"-",b"+") or added and tag!=b"+" or removed and tag!=b"-": _bad()
            before-=int(tag!=b"+"); after-=int(tag!=b"-")
            if before<0 or after<0: _bad()
            prior=tag
            if whole: reconstructed.extend(line[1:]+b"\n")
            if cursor<len(lines) and lines[cursor]==MARKER:
                if prior is None: _bad()
                cursor+=1; prior=None
                if whole:
                    if (before if removed else after)!=0: _bad()
                    del reconstructed[-1:]
    if not seen: _bad()
    if whole and (len(reconstructed)!=count or _sha(reconstructed)!=digest
                  or _blob(bytes(reconstructed),algo).encode()!=target): _bad()


def prepare(ctx,op,selected,locked,checkpoint):
    if not isinstance(selected,(tuple,list)) or not 2<=len(selected)<=MAX or len(set(selected))!=len(selected): _bad()
    owner=_owner(ctx,op); source=owner["source"]; sources={}
    if source["objectFormat"] not in ("sha1","sha256"): _bad()
    for kind,reader in READERS:
        if any(pair[0]==kind for pair in selected):
            sources.update(((kind,row["id"]),row) for row in reader(ctx,op).values())
    def current():
        checkpoint()
        if locked(): _bad()
        ctx.check_current_pointer()
    current()
    planned=[]
    for kind,rid in selected:
        row=sources.get((kind,rid)); f=row["fields"] if row else {}
        if f.get("commit")!=source["commit"] or f.get("copy")!=op or f.get("install")!=ctx.install_state["installId"]: _bad()
        planned.append((_member(kind,row),row))
    planned.sort(key=lambda p:p[0]["path"].encode("utf-8"))
    refs=[p[0] for p in planned]; _paths(ctx,refs,current)
    total=sum(m["bytes"] for m in refs)
    if not 0<total<=SET_LIMIT: _bad()
    chunks=[]
    for m,row in planned:
        current()
        data=checked_output(ctx,op,row,locked,show=False,added=m["kind"]=="add",removed=m["kind"]=="remove",
                            draft=m["kind"]=="draft",newdraft=m["kind"]=="newdraft",content=True)
        _patch(data,m,row["fields"],source["objectFormat"])
        chunks.append(data)
    current(); raw=b"".join(chunks)
    fields={"install":ctx.install_state["installId"],"copy":op,"manifest":owner["manifestDigest"],
            "commit":source["commit"],"tree":source["tree"],"members":refs,"bytes":total,"patchSha":_sha(raw)}
    return {"fields":fields,"patch":raw}


def has_proposal(fields): return any(m["kind"]=="newdraft" for m in fields["members"])


def key(fields): return _sha(_json([PROPOSED_SET_PROFILE if has_proposal(fields) else PROFILE,fields]))[:32]


def _valid(ctx,op,f,owner):
    source=owner["source"]
    if source["objectFormat"] not in ("sha1","sha256") or not isinstance(f,dict) or set(f)!={"install","copy","manifest","tree","commit","members","bytes","patchSha"}: return False
    if (f["install"]!=ctx.install_state["installId"] or f["copy"]!=op or f["manifest"]!=owner["manifestDigest"]
            or f["tree"]!=source["tree"] or f["commit"]!=source["commit"] or type(f["bytes"]) is not int or not 0<f["bytes"]<=SET_LIMIT
            or not _hash(f["patchSha"],64) or not isinstance(f["members"],list) or not 2<=len(f["members"])<=MAX): return False
    width=40 if source["objectFormat"]=="sha1" else 64; seen=set(); last=b""; total=0
    for m in f["members"]:
        if not isinstance(m,dict) or set(m)!={"kind","id","path","sha256","bytes","base","to"}: return False
        if (m["kind"] not in ("add","edit","remove","draft","newdraft") or not _hash(m["id"],32) or (m["kind"],m["id"]) in seen or not _hash(m["sha256"],64)
                or type(m["bytes"]) is not int or not 0<m["bytes"]<=LIMIT or not isinstance(m["path"],str)): return False
        if (m["to"]!={"state":"remove_original_path"} if m["kind"]=="remove" else not _hash(m["to"],width)): return False
        base=m["base"]
        if not isinstance(base,dict): return False
        addition=m["kind"] in ("add","newdraft")
        expected={"state","tree"} if addition else {"state","oid"}
        if (set(base)!=expected or addition and (base["state"]!="absent" or not _hash(base["tree"],width))
                or not addition and (base["state"]!="tracked" or not _hash(base["oid"],width))): return False
        path=m["path"].encode()
        if path<=last: return False
        last=path; total+=m["bytes"]; seen.add((m["kind"],m["id"]))
    _paths(ctx,f["members"],ctx.check_current_pointer)
    return total==f["bytes"]


def records(ctx,op,*,proposed=False):
    if type(proposed) is not bool: _bad()
    owner=_owner(ctx,op); rows={}; seen=set()
    profile=PROPOSED_SET_PROFILE if proposed else PROFILE
    for n in range(PROPOSED_SET_BANK if proposed else BANK):
        _,slot,_,_=output_names(ctx,op,"0"*32,n,added=False,bundle=True,proposed_set=proposed)
        # Eight bounded 2 KiB UTF-8 paths and their version IDs can exceed the
        # original single-file 16 KiB receipt bound.
        r=_read(ctx,slot,49152)
        if r is None: continue
        f=r["fields"]; rid=r["id"]
        if (r.get("format")!=profile or not _valid(ctx,op,f,owner) or has_proposal(f)!=proposed or rid!=key(f) or rid in seen
                or type(r.get("at")) is not int or not 0<=r["at"]<=4102444800000): _bad()
        seen.add(rid); rows[n]=r
    return rows


def proposed_records(ctx,op): return records(ctx,op,proposed=True)


def save(ctx,op,prepared,locked,checkpoint):
    expected=prepared["fields"]; rid=key(expected)
    proposed=has_proposal(expected); capacity=PROPOSED_SET_BANK if proposed else BANK
    rows=records(ctx,op,proposed=proposed)
    for r in rows.values():
        if r["id"]==rid: return r  # including an interrupted save: no repeat
    if len(rows)>=capacity: _bad()
    # Materialize every confirmed member again by its original private handle
    # and SHA. Missing members never yield a silently partial multi-file patch.
    fixed=prepare(ctx,op,[(m["kind"],m["id"]) for m in expected["members"]],locked,checkpoint)
    if _json(fixed["fields"])!=_json(expected) or fixed["patch"]!=prepared["patch"]: _bad()
    def current():
        checkpoint(); ctx.check_current_pointer()
        if locked(): _bad()
    return publish_output(ctx,op,rid,expected,fixed["patch"],next(n for n in range(capacity) if n not in rows),current,
                          added=False,bundle=True,proposed_set=proposed)
