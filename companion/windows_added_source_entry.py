"""Machine owner chooses names, a single eligible new source, then a specific save."""
import ntpath

from desktop_owner_entry import _dialog
import windows_added_source as added
from windows_source_patch import FILENAME,checked_output,output_names


def _short(value,n=96): return value if len(value)<=n else "…"+value[-n+1:]


def _choose(heading,body,items,label,locked):
    page=0
    while not locked():
        actions=[(("pick",i),label(v)) for i,v in enumerate(items[page*4:page*4+4],page*4)]
        if page: actions.append(("prev","上一页"))
        if 4*(page+1)<len(items): actions.append(("next","下一页"))
        selected=_dialog(heading,body,actions,retire=locked)
        if selected=="prev": page-=1
        elif selected=="next": page+=1
        elif selected is not None and not locked(): return items[selected[1]]
        else: return None


def detail(ctx,op,row,locked,*,read_patch=None):
    if locked(): return
    f=row["fields"]
    def evidence(show=False):
        if row not in added.records(ctx,op).values(): raise ValueError("Unbound added-file output")
        return checked_output(ctx,op,row,locked,show=show,added=True)
    try: state=evidence()
    except Exception: state="unavailable"
    if locked(): return
    text=(f"{f['path']}\n基准提交 {f['commit'][:12]} 的实际父树中没有这个名称或映射别名。"
          f"选定源码 {f['sourceBytes']} 字节，新文件模式 100644；不是从空文件修改。")
    actions=[("ids","查看原提交、新文件与补丁编号")]
    if state=="saved":
        text+="\n本次核实到当时保存的完整补丁。"
        actions.insert(0,("show","在资源管理器定位 change.patch\n仅选中原本机产物，不打开、执行或应用。"))
    elif state=="pending": text+="\n只有保存意图，可能已留下材料，未补写或认领同名文件。"
    else: text+="\n无法核实原补丁；没有从现在的代码重新生成。"
    if state=="saved" and read_patch is not None: actions.insert(0,("read-patch","在伴侣中阅读完整原补丁"))
    pick=_dialog("新增文件的本机补丁",text,actions,retire=locked)
    if locked(): return
    if pick=="read-patch" and read_patch is not None: read_patch(); return
    if pick=="show":
        try: evidence(True)
        except Exception:
            if not locked(): _dialog("没有打开文件夹","原补丁或唯一条目无法再核实，没有改开上级目录。",[],retire=locked)
    if pick=="ids":
        _dialog("这次新增文件的固定编号",f"原提交：{f['commit']}\n证明当时不存在的父树：{f['parentTree']}\n选定内容的 Git blob：{f['sourceOid']}"
                f"\n源码 SHA-256：{f['sourceSha']}\n完整补丁 SHA-256：{f['patchSha']}",[],retire=locked)


def choose(ctx,settings,op,report,locked,observe):
    folders=report["newParents"]
    if not folders or locked(): return
    text="只列这次原副本中已能核实、且观察到新入口的原目录。先选目录才查看其中合规的新文件名，不进入新文件夹。"
    if report["newParentCount"]>len(folders): text+=f" 此处列出前 {len(folders)} 个原目录。"
    chosen=_choose("从哪个原目录选新增源码？",text,folders,lambda v:_short(v["path"] or "副本根目录")+f"\n曾见 {v['count']} 个新入口",locked)
    if chosen is None: return
    parent=chosen["path"]
    visible,listing=observe("核对这个目录的新增候选","只列出普通源码或文本的名称、身份及大小；不读取正文。",locked,
                            lambda point,_:added.candidates(ctx,op,parent,point),30)
    if not visible: return
    if not listing or not listing["items"]:
        _dialog("没有可选的新增源码","目录可能已变化或过大，或只有新目录、受保护名称、别名、链接、非源码类型或超出 128 KiB 的文件。没有展开其他位置。",[],retire=locked); return
    text="只选一份作为新文件。原提交的实际目录树还要另行核实，不能从“没看到”推断它原本不存在。"
    if listing["more"]: text+=" 此处列出前 64 项。"
    item=_choose("选择一份新增文件",text,listing["items"],lambda x:_short(x["path"])+f"\n{x['bytes']} 字节 · 正文未读",locked)
    if item is None: return
    text=(f"{item['path']}\n先核对同一已登记仓库原提交中的父树，再读取选定文件的这一版本。"
          "只准备本机文本补丁，不执行或保存。请确认这是要交付的源码而非私密内容。")
    if _dialog("核对并准备新增补丁？",text,[("review","读取这一个版本并查看补丁信息")],retire=locked)!="review" or locked(): return
    visible,result=observe("核对这份新增源码","正在核实原提交、原目录和已选择的文件身份……",locked,
                           lambda point,_:added.prepare(ctx,settings,op,parent,item,point),45)
    if not visible: return
    if not result:
        _dialog("没有准备新增补丁","原提交或父树可能不可读取、已有同名或别名，或当前文件身份、文本范围已变化。不会换用当前分支或同名替代物。",[],retire=locked); return
    f=result["fields"]; rid=added.key(f)
    try:
        prior=added.records(ctx,op); saved=next((r for r in prior.values() if r["id"]==rid),None)
    except Exception:
        _dialog("不能核对原产物记录","没有写入或清空任何记录。",[],retire=locked); return
    if saved is not None: detail(ctx,op,saved,locked); return
    if len(prior)>=added.BANK:
        _dialog("新增源码补丁位置已满","每个副本最多保留四份新增文件补丁记录，不覆盖原产物。",[],retire=locked); return
    _,_,_,base=output_names(ctx,op,rid,0,added=True)
    text=(f"{f['path']}\n原提交 {f['commit'][:12]} 的父树已证明没有此项。现在选定的版本是 {f['sourceBytes']} 字节。"
          f"\n完整新增补丁 {f['bytes']} 字节，普通文件 100644。只把刚确认的版本保存在：\n{ntpath.join(base,FILENAME)}"
          "\n不写回源仓库，不授予执行权，不发给手机。")
    while not locked():
        pick=_dialog("保存这份新增文件补丁？",text,[("preview","查看选定源码的开头"),("save","确认将完整补丁保存到这个新位置")],retire=locked)
        if pick=="preview":
            _dialog("选定源码节选",result["excerpt"] or "没有可显示的文本行。",[],retire=locked)
            continue
        if pick!="save" or locked(): return
        visible,row=observe("保存已确认的新增补丁","在新私有位置发布并回读原补丁，不修改代码目录。",locked,
                            lambda point,_:added.save(ctx,settings,op,result,locked,point),30)
        if not visible: return
        if row: detail(ctx,op,row,locked)
        else: _dialog("没有完整保存回执","可能已留下文件；可从本次副本的「已另存的单文件变更」查原操作，不会再写一份。",[],retire=locked)
        return
