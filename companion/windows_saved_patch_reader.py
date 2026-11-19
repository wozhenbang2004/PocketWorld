"""Native read-only viewer for exact retained original patch bytes. No shell open, apply, fetch, index or write."""
import hashlib
import unicodedata

from desktop_owner_entry import _dialog
from desktop_phone_goal_text import source_text
from windows_git_copy import _json
from windows_git_copy_entry import _observe
from windows_project_workbench import READERS, bad, original
from windows_source_patch import LIMIT, checked_output


def pages(raw):
    if type(raw) is not bytes or not 0<len(raw)<=LIMIT or not raw.startswith(b"diff --git ") or not raw.endswith(b"\n"): bad()
    lines=raw[:-1].decode("utf-8").split("\n")
    def visible(text):
        def glyph(c):
            if c=="\r": return "␍"
            if c=="\ufeff": return "⟦BOM⟧"
            if c!="\t" and unicodedata.category(c) in ("Cf","Cc","Cs","Zl","Zp"): return "\\u{"+format(ord(c),"X")+"}"
            return c
        return "".join(glyph(c) for c in text)
    segments=[]; buf=[]; units=0; start=1
    for n,line in enumerate(lines,1):
        shown=visible(line); cost=len(shown.encode("utf-16le"))//2+2
        if cost>190000: bad()
        if units+cost>190000:
            segments.append((start,n-1,"\r\n".join(buf))); start=n; buf=[]; units=0
        units+=cost; buf.append(shown)
    segments.append((start,len(lines),"\r\n".join(buf)))
    return segments


def capture(ctx,item,index,locked,check):
    check()
    owner=original(ctx,item.owner); op=owner["operation"]; kind=item.kind
    if (locked() or kind not in READERS or ("selectedFile" in owner["source"] and kind!="draft")
            or (kind in ("set","proposedSet"))!=(index is not None)): bad()
    rows=[r for r in READERS[kind](ctx,op).values() if r["id"]==item.record["id"]]
    if len(rows)!=1 or _json(rows[0])!=_json(item.record): bad()
    r=rows[0]; f=r["fields"]
    if index is not None:
        if type(index) is not int or not 0<=index<len(f["members"]): bad()
        part=f["members"][index]; name,sha,size=part["path"],part["sha256"],part["bytes"]
    else: name,sha,size=f["path"],f["patchSha"],f["bytes"]
    def stale():
        check()
        return locked()
    raw=checked_output(ctx,op,r,stale,show=False,added=kind=="added",removed=kind=="removed",draft=kind=="draft",
                       bundle=kind in ("set","proposedSet"),content=index is None,member_index=index,newdraft=kind=="newdraft")
    if type(raw) is not bytes or len(raw)!=size or hashlib.sha256(raw).hexdigest()!=sha: bad()
    result=pages(raw); check()
    if locked(): bad()
    return name,sha,size,result


def choose(members,locked):
    page=0
    while not locked():
        choices=[]
        for i,m in enumerate(members[page*4:(page+1)*4],page*4):
            name=m["path"].replace("&","&&")
            choices.append((("item",i),(name if len(name)<=100 else "…"+name[-99:])+f"\n组合中的原区段 · {m['bytes']:,} 字节"))
        if page: choices.append(("prev","上一页"))
        if (page+1)*4<len(members): choices.append(("next","下一页"))
        pick=_dialog("阅读组合中哪一份完整差异？","会读取真实留存的 changes.patch 核对整件及每个区段，只显示选中那一份。不读取旧单项文件或现有源码。",choices,retire=locked)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        return pick[1] if pick is not None else None


def show(ctx,item,locked):
    if locked(): return
    member=None
    if item.kind in ("set","proposedSet"):
        member=choose(item.record["fields"]["members"],locked)
        if member is None or locked(): return
    visible,result=_observe("读取真实留存的原补丁","核对原目录、文件出生身份和字节 SHA；不会应用差异或从现有源码重组。",
        locked,lambda check,_:capture(ctx,item,member,locked,check),45)
    if not visible: return
    if result is None:
        _dialog("没有打开原差异","原回执、实际文件/区段或完整可显示文本不能核实。不以节选、现成单项或当前源码补齐。",[],retire=locked); return
    name,sha,size,segments=result; count=len(segments); i=0
    while not locked():
        first,last,body=segments[i]
        title=("组合原区段" if member is not None else "原补丁")+" · "+name[-65:]
        note=f"{size:,} 原字节 · SHA-256 {sha[:12]} · 第 {i+1}/{count} 段（行 {first}–{last}）。␍ 是 CR；⟦BOM⟧ 是 BOM，其他控制符转义。只读差异。"
        if source_text(lambda:not locked(),title,note,body,review=True) is None or locked() or count==1: return
        choices=[]
        if i+1<count: choices.append(("next",f"读第 {i+2} 段"))
        if i: choices.append(("prev",f"回第 {i} 段"))
        choices.append(("again","重读本段"))
        pick=_dialog("完整差异已核实，按段阅读",f"{name}\n原字节 SHA-256：{sha}\n刚读的是第 {i+1}/{count} 段；没有截断文件。",choices,retire=locked)
        if pick is None or locked(): return
        i+=1 if pick=="next" else -1 if pick=="prev" else 0
