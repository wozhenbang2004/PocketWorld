"""One complete, local Git-format text patch for an explicitly chosen tracked file.

Bytes are compared only across the already-bound original Git blob and one
separately chosen copy-side version. No Git CLI, subprocess, apply or transfer.
"""
from contextlib import ExitStack
import difflib
import hashlib
import ntpath
import os
import re
import time

from device_project_packages import path as project_path
from platform_backend import PlatformError
from platform_windows import WindowsHandleReader, relative_parts
from windows_git_copy import _json, _read, _store, slots
from windows_git_source import registered
from windows_source_inventory import _origin, load, show_bound_child
from windows_source_publisher import DirectoryTree, _ext

PROFILE = "pocketworld.windows-local-single-file-patch.v1"
BANK, LIMIT = 8, 320*1024
FILENAME = "change.patch"


def _bad(): raise PlatformError("Original single-file change or publication cannot be verified")


def _blob(raw,algo):
    return hashlib.new(algo,b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()


def _quoted(prefix,path):
    # Git C-quotes arbitrary UTF-8 path bytes. No unquoted whitespaces,
    # accidental headers, native separator, /dev/null, or decoded filenames.
    raw=(prefix+path).encode("utf-8")
    def octet(c):
        if c in (34,92): return bytes((92,c))
        if c<32 or c>=127: return ("\\%03o"%c).encode("ascii")
        return bytes([c])
    return b'"'+b"".join(map(octet,raw))+b'"'


def _records(raw):
    parts=raw.split(b"\n")  # Git's separator is LF, not Unicode splitlines().
    return [p+b"\n" for p in parts[:-1]]+([parts[-1]] if parts[-1] else [])


def _span(i,j):
    count=j-i
    return ("%d,%d"%(i+1 if count else i,count)).encode("ascii")


def render(path,mode,oid,algo,old,new,checkpoint):
    from windows_source_diff import TEXT_LIMIT,_lines
    if (len(old)>TEXT_LIMIT or len(new)>TEXT_LIMIT or old==new or _lines(old) is None or _lines(new) is None
            or mode not in ("100644","100755") or algo not in ("sha1","sha256") or _blob(old,algo)!=oid): _bad()
    project_path(path,generated=False); relative_parts(path)
    target=_blob(new,algo)
    a,b=_quoted("a/",path),_quoted("b/",path)
    output=[b"diff --git "+a+b" "+b+b"\n",b"index "+oid.encode()+b".."+target.encode()+b" "+mode.encode()+b"\n",
            b"--- "+a+b"\n",b"+++ "+b+b"\n"]
    original,edited=_records(old),_records(new)
    def emit(tag,lines):
        for line in lines:
            output.append(tag+line)
            if not line.endswith(b"\n"): output.append(b"\n\\ No newline at end of file\n")
    for group in difflib.SequenceMatcher(None,original,edited).get_grouped_opcodes(3):
        checkpoint()
        output.append(b"@@ -"+_span(group[0][1],group[-1][2])+b" +"+_span(group[0][3],group[-1][4])+b" @@\n")
        for tag,oi,oj,ni,nj in group:
            if tag=="equal": emit(b" ",original[oi:oj])
            if tag in ("delete","replace"): emit(b"-",original[oi:oj])
            if tag in ("insert","replace"): emit(b"+",edited[ni:nj])
    raw=b"".join(output)
    if len(output)<=4 or len(raw)>LIMIT: _bad()  # Never publish just a header or a clipped hunk.
    return raw,target


def _key(fields): return hashlib.sha256(_json([PROFILE,fields])).hexdigest()[:32]


def _slot(op,n): return f"source-patch-v1-{op}-slot-{n}.json"


def _receipt(rid): return "source-patch-v1-"+rid+"-result.json"


def _target(ctx,rid): return ntpath.join(ctx.state,"review-"+rid)


def intent(ctx,op,item,result):
    raw=result.get("patch")
    if not isinstance(raw,bytes) or not 0<len(raw)<=LIMIT or result.get("status")!="patch": _bad()
    return {"install":ctx.install_state["installId"],"copy":op,"path":result["path"],"commit":result["commit"],
            "originalOid":result["originalOid"],"currentOid":result["currentOid"],"baseSha":result["baseSha"],
            "currentSha":result["currentSha"],"replaced":result["replaced"],"selectedOrigin":item["origin"],
            "patchSha":hashlib.sha256(raw).hexdigest(),"bytes":len(raw)}


def _valid(ctx,op,fields,original):
    if not isinstance(fields,dict): return False
    required={"install","copy","path","commit","originalOid","currentOid","baseSha","currentSha",
              "replaced","selectedOrigin","patchSha","bytes"}
    fmt=original["source"]["objectFormat"]
    if (set(fields)!=required or fields["install"]!=ctx.install_state["installId"] or fields["copy"]!=op
            or fields["commit"]!=original["source"]["commit"] or type(fields["replaced"]) is not bool
            or not _origin(fields["selectedOrigin"]) or type(fields["bytes"]) is not int or not 0<fields["bytes"]<=LIMIT
            or not all(isinstance(fields[f],str) and re.fullmatch(r"[a-f0-9]{64}",fields[f]) for f in ("patchSha","baseSha","currentSha"))
            or not all(isinstance(fields[f],str) and re.fullmatch(r"[a-f0-9]{"+str(40 if fmt=="sha1" else 64)+r"}",fields[f])
                       for f in ("originalOid","currentOid")) or fields["baseSha"]==fields["currentSha"]): return False
    # Original paths were checked during committed tree mapping. Check display
    # controls and filesystem syntax again for untrusted on-disk receipt data.
    from windows_git_tree import _component
    prefix=""
    for part in relative_parts(fields["path"]):
        name,_=_component(prefix,part.encode("utf-8")); prefix=name+"/"
    return True


def records(ctx,op):
    if not isinstance(op,str) or not re.fullmatch(r"[0-9a-f]{32}",op): _bad()
    original=next((r for _,r in slots(ctx) if r["operation"]==op),None)
    if original is None: _bad()
    rows={}
    seen=set()
    for n in range(BANK):
        row=_read(ctx,_slot(op,n),16384)
        if row is None: continue
        f=row.get("fields"); rid=row.get("id")
        if (row.get("format")!=PROFILE or not _valid(ctx,op,f,original) or rid!=_key(f) or rid in seen
                or type(row.get("at")) is not int or not 0<=row["at"]<=4102444800000): _bad()
        seen.add(rid); rows[n]=row
    return rows


def _read_file(ctx,tree,stack,expected,f):
    h,origin=tree.open_file(FILENAME); stack.callback(tree.api.k.CloseHandle,h)
    if expected is not None and origin!=expected: _bad()
    reader=WindowsHandleReader(tree.api,h,LIMIT)
    if reader.size!=f["bytes"]: _bad()
    reader.seek(0); sha=hashlib.sha256()
    while reader.position<reader.size: sha.update(reader.read(min(262144,reader.size-reader.position)))
    if not reader.verify_unchanged() or sha.hexdigest()!=f["patchSha"]: _bad()
    return origin


def check(ctx,op,row,locked,*,show=False):
    if locked() or row not in records(ctx,op).values(): _bad()
    rid,f=row["id"],row["fields"]
    end=_read(ctx,_receipt(rid),4096)
    if end is None: return "pending"
    if (end.get("format")!=PROFILE or end.get("id")!=rid or end.get("copy")!=op or end.get("patchSha")!=f["patchSha"]
            or not _origin(end.get("folder")) or not _origin(end.get("file"))): _bad()
    base=_target(ctx,rid)
    with ExitStack() as stack:
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state)); ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        tree=DirectoryTree(ctx,base,stack,end["folder"])
        _read_file(ctx,tree,stack,end["file"],f)
        if show:
            # Don't open a private state parent, an unknown sibling or an association.
            # Check the exact single entry without scanning unbounded foreign names.
            with os.scandir(_ext(base)) as members:
                first=next(members,None)
                if first is None or first.name!=FILENAME or next(members,None) is not None: _bad()
            show_bound_child(ctx,stack,base,ntpath.join(base,FILENAME),locked)
    return "saved"


def save(ctx,settings,op,item,result,locked,checkpoint):
    f=intent(ctx,op,item,result); rid=_key(f)
    rows=records(ctx,op)
    owner=next((r for _,r in slots(ctx) if r["operation"]==op),None)
    if owner is None or not _valid(ctx,op,f,owner): _bad()
    _,_,originals,_,_,origin_ids=load(ctx,op)
    path=f["path"]; entry=originals.get(path)
    if (entry is None or path!=item["path"] or f["baseSha"]!=entry["sha256"] or f["originalOid"]!=entry["object"]
            or f["replaced"]!=(f["selectedOrigin"]!=origin_ids[path])): _bad()
    old=next((row for row in rows.values() if row["id"]==rid),None)
    if old is not None: return old  # Never write again under that identity, even if incomplete.
    if len(rows)>=BANK: raise PlatformError("Original single-file patch receipt bank full")
    def current():
        checkpoint()
        if locked(): _bad()
        if owner["source"]["environmentId"] not in dict(registered(ctx,settings)): _bad()
    def factual():
        ctx.check_current_pointer()
        ctx.backend.assert_private_directory(ctx.state,ctx.sid)
    row={"format":PROFILE,"id":rid,"fields":f,"at":int(time.time()*1000)}
    with ExitStack() as stack:
        _,state,_=stack.enter_context(ctx.backend.directory(ctx.state)); ctx.backend.api.private_access(state,ctx.sid,require_protected=True)
        current()
        _store(ctx,_slot(op,next(n for n in range(BANK) if n not in rows)),row,16384,current)
        # Private, unique, new folder: never adopt even an empty old folder.
        current()
        path=_target(ctx,rid); tree=DirectoryTree(ctx,path,stack,None,create_root=True)
        capture=ctx.backend.create_private_snapshot(path,FILENAME,result["patch"],maximum=LIMIT,current=current)
        if (capture.get("parentIdentity")!=tree.nodes[""][2] or capture.get("sha256")!=f["patchSha"]
                or capture.get("bytes")!=f["bytes"]): _bad()
        file_id=_read_file(ctx,tree,stack,None,f)
        if capture.get("fileIdentity")!=file_id[:3]: _bad()
        # Publication may already have happened if a window closes here. Write
        # only its factual original receipt, not another patch or any repair.
        _store(ctx,_receipt(rid),{"format":PROFILE,"id":rid,"copy":op,"patchSha":f["patchSha"],
                                 "folder":tree.origins[""],"file":file_id},4096,factual)
    return row
