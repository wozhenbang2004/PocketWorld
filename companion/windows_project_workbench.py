"""Installed owner's recent local project work: bounded receipt slots only, not a new execution or indexing service."""
from dataclasses import dataclass
import time

from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from windows_git_copy import _json, inspect, slots
from windows_git_copy_entry import _observe, show_receipt
from windows_git_source import registered
from windows_owner_draft import records as drafts
from windows_new_source_draft import records as new_drafts
from windows_source_patch import records as tracked
from windows_added_source import records as additions
from windows_removed_source import records as removals
from windows_patch_set import records as sets, proposed_records as proposed_sets

READERS={"draft":drafts,"newdraft":new_drafts,"tracked":tracked,"added":additions,"removed":removals,"set":sets,"proposedSet":proposed_sets}
LABEL={"copy":"项目底稿","file":"单文件底稿","draft":"机主草稿","newdraft":"机主拟新增","tracked":"修改补丁","added":"新增补丁","removed":"移除建议","set":"多文件补丁","proposedSet":"多文件补丁（含拟新增）"}


def bad(): raise PlatformError("Original installed-owner record cannot be bound")


@dataclass(frozen=True)
class Item:
    kind: str
    owner: dict
    record: dict

    @property
    def source(self): return self.owner["source"]

    @property
    def group(self):
        # A presentation filter for STORED source labels, never evidence of a current filesystem
        # identity. Opening always checks the exact copy operation and exact originating row.
        s=self.source
        return (s["environmentId"],s.get("selectedProject",{}).get("path",""),s["repositoryAnchor"])

    @property
    def name(self):
        if self.kind=="copy": return "整项目独立副本"
        if self.kind=="file": return self.source["selectedFile"]["path"]
        f=self.record["fields"]
        return f"{len(f['members'])} 个已选文件" if self.kind in ("set","proposedSet") else f["path"]

    @property
    def key(self): return (self.owner["operation"],self.kind,self.owner["operation"] if self.kind in ("copy","file") else self.record["id"])


def original(ctx,owner):
    rows=[r for _,r in slots(ctx) if r["operation"]==owner["operation"]]
    if len(rows)!=1 or _json(rows[0])!=_json(owner): bad()
    return rows[0]


def catalog(ctx,check,progress):
    rows=[r for _,r in slots(ctx)]
    if len({r["operation"] for r in rows})!=len(rows): bad()
    values=[]; missed=0
    for i,r in enumerate(rows,1):
        check(); progress(f"正在读取第 {i}/{len(rows)} 份底稿的原登记；没有打开项目文件。")
        one="selectedFile" in r["source"]
        values.append(Item("file" if one else "copy",r,r))
        for kind in (("draft",) if one else READERS):
            check()
            try: batch=READERS[kind](ctx,r["operation"])
            except Exception:
                missed+=1; continue  # One broken bank cannot silently substitute for another.
            values.extend(Item(kind,r,row) for row in batch.values())
    check()
    if len({r.key for r in values})!=len(values): bad()
    return sorted(values,key=lambda it:(-it.record["at"],it.key)),missed


def group_label(group):
    workspace,subproject,anchor=group
    return workspace+(" / "+subproject if subproject else "")+" · 来源 "+anchor[:8]


def compact(text,maxlen=82):
    return (text if len(text)<=maxlen else "…"+text[-maxlen+1:]).replace("&","&&")


def groups(keys,locked):
    page=0
    while not locked():
        choices=[("all","全部登记来源")]
        choices.extend((("group",g),compact(group_label(g))) for g in keys[page*5:(page+1)*5])
        if page: choices.append(("prev","上一页"))
        if (page+1)*5<len(keys): choices.append(("next","下一页"))
        pick=_dialog("筛选本机项目工作","按当时登记的来源选择；不查找磁盘上的目录。",choices,retire=locked)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        return pick


def reopen(ctx,settings,item,locked):
    if locked(): return
    op=item.owner["operation"]
    def read(check,_):
        check(); owner=original(ctx,item.owner)
        if item.kind in ("copy","file"):
            receipt=inspect(ctx,owner); check(); return receipt
        if "selectedFile" in owner["source"] and item.kind!="draft": bad()
        if item.kind not in READERS: bad()
        rows=[r for r in READERS[item.kind](ctx,op).values() if r["id"]==item.record["id"]]
        if len(rows)!=1 or _json(rows[0])!=_json(item.record): bad()
        check(); return rows[0]
    visible,record=_observe("核对选中的原工作",item.name+"\n核对原安装、底稿、类型与这一个原编号，不查相似名称。",locked,read,35)
    if not visible: return
    if record is None:
        _dialog("原记录不能再核实","没有改开别的同名项目或文件，不重做原工作。",[],retire=locked); return
    if item.kind in ("copy","file"):
        show_receipt(ctx,record,locked,settings); return
    def read_patch():
        from windows_saved_patch_reader import show
        show(ctx,item,locked)
    if item.kind=="draft":
        from windows_owner_draft_entry import detail
        detail(ctx,op,record,locked,settings=settings,read_patch=read_patch); return
    if item.kind=="newdraft":
        from windows_new_source_draft_entry import detail
        detail(ctx,op,record,locked,read_patch=read_patch,settings=settings); return
    if item.kind in ("set","proposedSet"): from windows_patch_set_entry import detail
    elif item.kind=="added": from windows_added_source_entry import detail
    elif item.kind=="removed": from windows_removed_source_entry import detail
    elif item.kind=="tracked": from windows_source_patch_entry import detail
    else: bad()
    detail(ctx,op,record,locked,read_patch=read_patch)  # Existing native exact artifact checks; no direct path opener.


def open_work(ctx,settings):
    health=WindowsDesktopHealth(); first=health.probe(session_only=True); sid=first.get("sessionId")
    if not settings or first.get("available") is not True or type(sid) is not int or sid<=0: return
    def locked():
        try:
            live=health.probe(session_only=True); registered(ctx,settings)
            return live.get("available") is not True or live.get("sessionId")!=sid
        except Exception: return True
    group=None; outputs=False
    while not locked():
        def read(check,note):
            def gate():
                check()
                if locked(): bad()
            return catalog(ctx,gate,note)
        visible,value=_observe("读取本机已有的项目工作","只读有限的本机制作记录。不会核对每个成果正文、打开仓库或启动连接服务。",locked,read,90)
        if not visible: return
        if value is None:
            _dialog("没有取得可信的本机项目记录","未从目录名、文件修改时间或手机任务推断历史。",[],retire=locked); return
        rows,missed=value
        if not rows:
            if _dialog("这里还没有本机项目工作","从已登记的本机 Git 来源开始；只有明确确认制作材料才留下工作记录。",
                       [("source","去核对一个本机 Git 来源")],retire=locked)=="source" and not locked():
                from windows_git_source_entry import open_source
                open_source(ctx,settings)
            return
        keys=sorted({r.group for r in rows})
        aliases={k[:2] for k in keys if sum(g[:2]==k[:2] for g in keys)>1}
        if group not in keys: group=None
        page=0
        while not locked():
            shown=[r for r in rows if (group is None or r.group==group) and (not outputs or r.kind not in ("copy","file"))]
            choices=[]
            for i,item in enumerate(shown[page*4:(page+1)*4],page*4):
                stamp=time.strftime("%m-%d %H:%M:%S",time.localtime(item.record["at"]/1000))
                origin=item.group[0]+(" / "+item.group[1] if item.group[1] else "")
                if item.group[:2] in aliases: origin+=" · 来源 "+item.group[2][:8]
                choices.append((("item",i),f"{LABEL[item.kind]} · {compact(item.name)}\n{stamp} · {compact(origin,42)} · {item.source['commit'][:10]}"))
            choices.append(("types","显示底稿和变更" if outputs else "只看已登记的草稿与补丁"))
            if len(keys)>1: choices.append(("groups","筛选登记来源" if group is None else "更改或清除来源筛选"))
            if page: choices.append(("prev","上一页"))
            if 4*(page+1)<len(shown): choices.append(("next","下一页"))
            choices.append(("refresh","重新读取原登记"))
            note=(group_label(group) if group else "这台电脑的全部登记来源")+f" · {len(shown)} 条\n按原保存请求时间排列；有记录不等于文件仍在。点开只核对这一项。"
            if missed: note+=f" 有 {missed} 组原资料无法读取，未作无记录。"
            pick=_dialog("继续本机项目工作",note,choices,retire=locked)
            if pick is None or locked(): return
            if pick=="refresh": break
            if pick=="prev": page-=1; continue
            if pick=="next": page+=1; continue
            if pick=="types": outputs=not outputs; page=0; continue
            if pick=="groups":
                selected=groups(keys,locked)
                if selected is not None: group=None if selected=="all" else selected[1]; page=0
                continue
            reopen(ctx,settings,shown[pick[1]],locked)
