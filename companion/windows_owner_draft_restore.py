"""Reopen only a verified output of our own draft renderer, in memory. Not a general patch importer."""
from contextlib import ExitStack, contextmanager
import re

from windows_owner_draft import MAX_TEXT, block, digest, encode, original, records, unpack
from windows_source_patch import LIMIT, _blob, _quoted, _records, checked_output, render

HUNK=re.compile(rb"@@ -([0-9]{1,6}),([0-9]{1,6}) \+([0-9]{1,6}),([0-9]{1,6}) @@")
NO_LF=b"\\ No newline at end of file"


def target(base,f,raw,check):
    if (type(raw) is not bytes or not 0<len(raw)<=LIMIT or not raw.endswith(b"\n") or len(raw)!=f["bytes"]
            or digest(raw)!=f["patchSha"] or base.path!=f["path"] or base.commit!=f["commit"] or base.oid!=f["originalOid"]
            or list(base.origin)!=f["originalOrigin"] or digest(base.raw)!=f["baseSha"]): block()
    a,b=_quoted("a/",base.path),_quoted("b/",base.path)
    lines=raw[:-1].split(b"\n")
    expected=[b"diff --git "+a+b" "+b,b"index "+base.oid.encode()+b".."+f["draftOid"].encode()+b" 100644",
              b"--- "+a,b"+++ "+b]
    if lines[:4]!=expected or len(lines)<=4: block()
    old=_records(base.raw); at=0; i=4; length=0; new=[]
    def keep(line):
        nonlocal length
        length+=len(line)
        if length>MAX_TEXT or length>f["draftBytes"]: block()
        new.append(line)
    while i<len(lines):
        check(); h=HUNK.fullmatch(lines[i])
        if h is None: block()
        o,count_o,n,count_n=(int(v) for v in h.groups())
        o-=int(count_o>0); n-=int(count_n>0); i+=1
        if o<at or o+count_o>len(old) or n<0 or not (count_o or count_n): block()
        for line in old[at:o]: keep(line)
        if len(new)!=n: block()
        at=o
        while i<len(lines) and not lines[i].startswith(b"@@ "):
            entry=lines[i]; code=entry[:1]; i+=1
            if code not in (b" ",b"-",b"+"): block()
            data=entry[1:]+b"\n"
            if i<len(lines) and lines[i]==NO_LF: data=data[:-1]; i+=1
            if code!=b"+":
                if at>=o+count_o or old[at]!=data: block()
                at+=1
            if code!=b"-":
                if len(new)>=n+count_n: block()
                keep(data)
        if at!=o+count_o or len(new)!=n+count_n: block()
    for line in old[at:]: keep(line)
    result=b"".join(new)
    if len(result)!=f["draftBytes"] or digest(result)!=f["draftSha"] or _blob(result,base.algo)!=f["draftOid"]: block()
    display=unpack(result)[2]
    if encode(base,display)!=result or render(base.path,"100644",base.oid,base.algo,base.raw,result,check)!=(raw,f["draftOid"]):
        block()  # Exact self-render including quoting, BOM, line endings and no-newline markers. Never apply with fuzz.
    return display


@contextmanager
def held_draft(ctx,settings,op,row,locked,checkpoint):
    def gate():
        checkpoint()
        if locked(): block()
    gate()
    if row not in records(ctx,op).values(): block()
    f=row["fields"]
    with ExitStack() as pins, original(ctx,settings,op,f["path"],gate) as (base,source):
        raw=checked_output(ctx,op,row,locked,show=False,added=False,draft=True,content=True,hold=pins)
        draft=target(base,f,raw,gate)
        gate()
        yield base,draft,source


def restore(ctx,settings,op,row,locked,checkpoint):
    with held_draft(ctx,settings,op,row,locked,checkpoint) as (base,draft,_): return base,draft
