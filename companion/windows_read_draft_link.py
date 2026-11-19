"""A receiver-owner's explicit, private bookmark. Not a phone return, acknowledgement or execution result."""
from contextlib import ExitStack
from dataclasses import asdict
import hashlib
import re

from platform_backend import PlatformError
from windows_git_copy import _json,_read,_store,slots
from windows_owner_draft import records
from windows_source_patch import checked_output,output_names

PROFILE="windows-owner-read-draft-bookmark-v1"


def bad(): raise PlatformError("The original paired read and local draft bookmark do not match")


def sha(value): return hashlib.sha256(_json(value)).hexdigest()


def filename(op,rid):
    if not all(isinstance(x,str) and re.fullmatch(r"[a-f0-9]{32}",x) for x in (op,rid)): bad()
    # At most one small opt-in bookmark per existing bounded draft slot. No peer name or workspace path in it.
    return "owner-read-note-v1-"+op+"-"+rid+".json"


def matching(ctx,read,owner,path,file,birth,check,*,inventory=None):
    check(); op=owner["operation"]
    roots=[r for _,r in slots(ctx) if r["operation"]==op]
    if len(roots)!=1 or _json(roots[0])!=_json(owner) or owner["source"]["environmentId"]!=read.original_work.environment: bad()
    from windows_task_source_match import project_path
    from windows_source_inventory import load
    _,_,entries,_,_,origins,registered_root=load(ctx,op,work_root=True) if inventory is None else inventory
    if (project_path(owner,read)!=path or registered_root!=read.origin or _json(entries.get(path))!=_json(file)
            or origins.get(path)!=birth): bad()
    if (file["mode"]!="100644" or file["bytes"]!=read.size or file["sha256"]!=read.sha
            or not isinstance(birth,list) or len(birth)!=5): bad()
    return [r for r in records(ctx,op).values() if r["fields"]["path"]==path and r["fields"]["commit"]==owner["source"]["commit"]
            and r["fields"]["baseSha"]==read.sha and r["fields"]["originalOid"]==file["object"] and r["fields"]["originalOrigin"]==birth]


def facts(ctx,read,owner,row,end):
    op=owner["operation"]; f=row["fields"]
    if not isinstance(end,dict) or end.get("copy")!=op or end.get("id")!=row["id"] or end.get("patchSha")!=f["patchSha"]: bad()
    install=ctx.install_state["installId"]
    return {"format":PROFILE,"read":sha([PROFILE,install,op,asdict(read)]),
            "draft":sha([PROFILE,install,owner,row,end])}


def final_file(ctx,op,row):
    receipt=output_names(ctx,op,row["id"],0,added=False,draft=True)[2]
    return _read(ctx,receipt,4096)


def mark(ctx,read,owner,path,file,birth,row,check):
    if not any(_json(r)==_json(row) for r in matching(ctx,read,owner,path,file,birth,check)): bad()
    def locked():
        check()
        return False
    op=owner["operation"]; f=row["fields"]; name=filename(op,row["id"])
    with ExitStack() as pins:
        end=final_file(ctx,op,row)
        raw=checked_output(ctx,op,row,locked,show=False,added=False,draft=True,content=True,hold=pins)
        if (type(raw) is not bytes or len(raw)!=f["bytes"] or hashlib.sha256(raw).hexdigest()!=f["patchSha"]
                or _json(final_file(ctx,op,row))!=_json(end)): bad()
        wanted=facts(ctx,read,owner,row,end); check()
        saved=_read(ctx,name,2048)
        if saved is None:
            _store(ctx,name,wanted,2048,check)  # New private bookkeeping only; never update a peer operation or overwrite another mark.
            saved=_read(ctx,name,2048)
        check()
        if _json(saved)!=_json(wanted): bad()
        return True


def discover(ctx,read,check):
    from windows_task_source_match import project_path
    # Up to 16 owner source slots x 8 existing drafts; read only fixed private metadata. No directory
    # walk, project manifest, file bytes or other patch body to construct names or manufacture relations.
    known=[]; unavailable=False
    for _,owner in slots(ctx):
        check(); op=owner["operation"]; path=project_path(owner,read)
        if not path: continue
        expected=sha([PROFILE,ctx.install_state["installId"],op,asdict(read)])
        try: candidates=records(ctx,op)
        except (OSError,ValueError,KeyError,TypeError,PlatformError):
            unavailable=True; continue
        for row in candidates.values():
            check(); f=row["fields"]
            if f["path"]!=path or f["commit"]!=owner["source"]["commit"] or f["baseSha"]!=read.sha: continue
            try:
                stored=_read(ctx,filename(op,row["id"]),2048)
                if stored is None: continue
                if (set(stored)!={"format","read","draft"} or stored["format"]!=PROFILE
                        or not all(isinstance(stored[k],str) and re.fullmatch(r"[a-f0-9]{64}",stored[k]) for k in ("read","draft"))): bad()
                if stored["read"]!=expected: continue
                if stored!=facts(ctx,read,owner,row,final_file(ctx,op,row)): bad()
                known.append((owner,row,path))
            except (OSError,ValueError,KeyError,TypeError,PlatformError): unavailable=True
    check()
    return sorted(known,key=lambda item:(-item[1]["at"],item[0]["operation"],item[1]["id"])),unavailable


def pin_saved(view,ctx,read,owner,path,file,birth,row,active,exact,observe):
    if not active() or not exact(): return
    f=row["fields"]
    if view._dialog("为这次原读取留一个本机书签？",f"{path} · 草稿 {f['draftSha'][:12]} · 原操作 …{read.operation[-12:]}\n"
                    "补丁已单独保存。机主可以仅在这台 Windows 留下关联，下次从同一原读取找到它。每份补丁至多一个书签；不会覆盖旧标记。"
                    "不向手机或模型发送，不挂到手机任务的成果，也不增加读取权限。",
                    [("mark","只在这台 Windows 记住此关联")],active)!="mark": return
    def work(check,_):
        def bound():
            check()
            if not active() or not exact(): bad()
        return mark(ctx,read,owner,path,file,birth,row,bound)
    visible,done=observe("核对补丁并新建本机书签","同时复核原读取与实际已保存的补丁，不回传内容或变更执行状态。",lambda:not active(),work,35)
    if visible and active():
        view._dialog("已核到这一个本机书签" if done else "未核实这一个本机书签",
                     "以后从同一次原读取的「查看本机标记的补丁」查找，不需要记住底稿。没有向手机发送。" if done else
                     "补丁和标记是两件事。补丁仍可独立从本机项目工作核对。原范围或这份标记未能核实，不覆盖、不自动重做。",[],active)


def open_marked(view,ctx,read,active,exact,observe):
    def work(check,_):
        def bound():
            check()
            if not active(): bad()
        if not exact(): bad()
        value=discover(ctx,read,bound)
        if not exact(): bad()
        return value
    visible,value=observe("查这次原读取的本机书签","只核对有限的本机原登记和明确书签，不按名称、版本相同或时间相近猜测关联。",
                          lambda:not active(),work,35)
    if not visible or not active(): return
    if value is None or not value[0]:
        view._dialog("没有核到已标记的补丁","未找到可核实的显式关联；这不说明机主没有另存过补丁。不会把同名或相似草稿当作本次产物。",[],active); return
    rows,missing=value; page=0
    while active():
        choices=[(("item",i),f"阅读本机标记的原差异 · {r['fields']['draftSha'][:12]}\n底稿 {owner['source']['commit'][:12]} · 补丁 {r['fields']['bytes']:,} 字节")
                 for i,(owner,r,_) in enumerate(rows[page*4:(page+1)*4],page*4)]
        if page: choices.append(("prev","上一页"))
        if (page+1)*4<len(rows): choices.append(("next","下一页"))
        pick=view._dialog("机主为这次原读取标记的本机补丁",read.path+"\n只在本机阅读选中原件；这不是发给手机的任务结果。"
                          +(" 另有无法核对的原记录。" if missing else ""),choices,active)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        if not isinstance(pick,tuple) or pick[0]!="item" or not active() or not exact(): return
        owner,row,path=rows[pick[1]]; op=owner["operation"]
        def current():
            try:
                if not active(): return False
                return _read(ctx,filename(op,row["id"]),2048)==facts(ctx,read,owner,row,final_file(ctx,op,row))
            except Exception: return False
        def selected(check,_):
            from windows_source_inventory import load
            def bound():
                check()
                if not current(): bad()
            if not exact(): bad()
            inventory=load(ctx,op,work_root=True); _,_,entries,_,_,births,origin=inventory
            if origin!=read.origin or not any(_json(r)==_json(row) for r in matching(ctx,read,owner,path,entries[path],births[path],bound,inventory=inventory)): bad()
            return exact() and current()
        visible,ok=observe("核对所选原底稿与书签","只核对这个原文件的留存登记和原补丁引用，未打开当前源码。",lambda:not current(),selected,35)
        if not visible or not active(): return
        if not ok:
            view._dialog("没有打开本机标记的原差异","原授权、书签或所选底稿不能重新核实。不会打开其他草稿或创建替代物。",[],active); return
        from windows_project_workbench import Item
        from windows_saved_patch_reader import show
        show(ctx,Item("draft",owner,row),lambda:not current())  # native exact-file/content reader; not an editor, sender or task result
        return
