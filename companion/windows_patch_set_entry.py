"""Local owner UI for choosing, saving, and revisiting a multi-file Git patch."""
import ntpath

from desktop_owner_entry import _dialog
import windows_patch_set as sets
from windows_source_patch import SET_FILE,checked_output,output_names


def _short(s,n=94): return s if len(s)<=n else "…"+s[-n+1:]


def _title(m): return {"add":"新增 · ","edit":"修改 · ","remove":"移除原路径 · "}[m["kind"]]+_short(m["path"])


def _end(m,length=None): return "移除原路径（不是空文件）" if m["kind"]=="remove" else m["to"][:length]


def _warning(members):
    n=sum(m["kind"]=="remove" for m in members)
    return f"\n其中 {n} 项会在你日后手动应用补丁时移除原路径。现在不删除任何文件。" if n else ""


def inspect_members(members,locked):
    page=0
    while not locked():
        actions=[]
        for i,m in enumerate(members[page*4:page*4+4],page*4):
            before="原提交无此项" if m["kind"]=="add" else m["base"]["oid"][:10]
            actions.append((("item",i),_title(m)+"\n"+before+" → "+_end(m,10)+f"，单项补丁 {m['bytes']} 字节"))
        if page: actions.append(("prev","上一页"))
        if 4*(page+1)<len(members): actions.append(("next","下一页"))
        click=_dialog("这一个补丁包含哪些文件？","选择一项查看其准确来源；不会打开源代码或查现在的工作树。",actions,retire=locked)
        if click=="next": page+=1
        elif click=="prev": page-=1
        elif click is not None and not locked():
            m=members[click[1]]; base=m["base"]
            before="原提交中无此名称，父树："+base["tree"] if m["kind"]=="add" else "原 blob："+base["oid"]
            _dialog("这项补丁的原版本",f"{m['path']}\n{before}\n目标：{_end(m)}\n原单项记录：{m['id']}\n原补丁 SHA-256：{m['sha256']}",[],retire=locked)
        else: return


def detail(ctx,op,row,locked):
    if locked(): return
    f=row["fields"]
    def check(show=False):
        if row not in sets.records(ctx,op).values(): raise ValueError("Missing original patch set")
        return checked_output(ctx,op,row,locked,show=show,added=False,bundle=True)
    try: state=check()
    except Exception: state="unavailable"
    if locked(): return
    text=f"原提交 {f['commit'][:12]} · {len(f['members'])} 个文件 · 完整组合补丁 {f['bytes']} 字节。"+_warning(f["members"])
    actions=[("members","查看成员、原版本和每项摘要"),("hash","查看原提交和完整补丁摘要")]
    if state=="saved":
        text+="\n原文件夹、changes.patch 的文件身份、整件摘要以及各段原摘要均已核对。"
        actions.insert(0,("locate","在资源管理器选中 changes.patch\n不打开、应用或发送；系统文件夹由你关闭。"))
    elif state=="pending": text+="\n只有保存意图，不能确认完整文件已写出，也不会自动重做。"
    else: text+="\n现在无法核实原组合产物；不改用同名文件或重组一份。"
    pick=_dialog("本机多文件补丁",text,actions,retire=locked)
    if locked(): return
    if pick=="members": inspect_members(f["members"],locked)
    if pick=="hash": _dialog("组合补丁的固定编号",f"原提交：{f['commit']}\n原提交根树：{f['tree']}\n本机组合记录：{row['id']}\n完整补丁 SHA-256：{f['patchSha']}",[],retire=locked)
    if pick=="locate":
        try: check(True)
        except Exception:
            if not locked(): _dialog("没有定位原组合补丁","原件或唯一目录成员未能再核实，不打开上级目录。",[],retire=locked)


def _choose(ctx,rows,locked):
    selected=set(); page=0
    while not locked():
        actions=[]
        for i,(kind,r) in enumerate(rows[4*page:4*page+4],4*page):
            m=sets._member(kind,r); symbol="已选　" if i in selected else ""
            actions.append((("pick",i),symbol+_title(m)+f"\n目标：{_end(m,12)}，原单项补丁 {m['bytes']} 字节"))
        if len(selected)>=2: actions.append(("prepare",f"核对选中的 {len(selected)} 份原补丁"))
        if page: actions.append(("prev","上一页"))
        if 4*(page+1)<len(rows): actions.append(("next","下一页"))
        click=_dialog("哪些文件属于这次交付？",f"已选 {len(selected)}/8。再次点同一项取消。只从这个副本的已留存单项原件组合，不会读取当前代码。",actions,retire=locked)
        if click=="next": page+=1
        elif click=="prev": page-=1
        elif click=="prepare" and not locked(): return [(rows[i][0],rows[i][1]["id"]) for i in sorted(selected)]
        elif isinstance(click,tuple) and click[0]=="pick" and not locked():
            index=click[1]
            if index in selected: selected.remove(index); continue
            if len(selected)>=sets.MAX:
                _dialog("最多选择八份","请先取消一项再换选。",[],retire=locked); continue
            members=[sets._member(*rows[i]) for i in sorted(selected|{index})]
            try: sets._paths(ctx,members,ctx.check_current_pointer)
            except Exception:
                _dialog("不能同时纳入这两项","可能是同一文件的两个版本、路径别名或文件与父目录冲突。没有替你决定使用哪版。",[],retire=locked); continue
            selected.add(index)
        else: return None


def menu(ctx,op,locked,observe):
    if locked(): return
    rows,damaged=sets.catalog(ctx,op)
    try: kept=sorted(sets.records(ctx,op).values(),key=lambda r:r["at"],reverse=True)
    except Exception:
        _dialog("无法核对这个副本的组合记录","没有创建或覆盖文件；原有单文件补丁入口不受影响。",[],retire=locked); return
    actions=[("choose","选择单文件产物\n选 2–8 份同一原提交、不同路径的补丁，确认后才另存。")] if len(rows)>=2 and len(kept)<sets.BANK else []
    for i,r in enumerate(kept):
        f=r["fields"]; actions.append((("kept",i),f"查看已有组合 · {len(f['members'])} 个文件\n"+_short("、".join(m["path"] for m in f["members"]),88)))
    text="组合产物保存在本机，不能代替完整项目或运行回执。"
    if len(rows)<2: text+=" 当前不足两份可选择的单文件记录。"
    if len(kept)>=sets.BANK: text+=" 本副本的三个组合位置均有原记录，不自动覆盖。"
    if damaged: text+=" 有一组单文件记录不可核实，未作为候选。"
    click=_dialog("多文件交付补丁",text,actions,retire=locked)
    if locked(): return
    if isinstance(click,tuple) and click[0]=="kept": detail(ctx,op,kept[click[1]],locked); return
    if click!="choose": return
    selected=_choose(ctx,rows,locked)
    if not selected: return
    visible,result=observe("核对选中的单文件原件","正在逐个核实原私有目录、补丁身份、字节摘要、单一路径和补丁段……",locked,
                           lambda point,_:sets.prepare(ctx,op,selected,locked,point),45)
    if not visible: return
    if not result:
        _dialog("没有准备组合补丁","可能有原件未写成或已变化、路径/版本冲突、补丁格式不符，或合计超过 2 MiB。没有丢掉一项后假作完整。",[],retire=locked); return
    f=result["fields"]; rid=sets.key(f)
    prior=next((r for r in kept if r["id"]==rid),None)
    if prior: detail(ctx,op,prior,locked); return
    _,_,_,base=output_names(ctx,op,rid,0,added=False,bundle=True)
    text=(f"原提交 {f['commit'][:12]} · {len(f['members'])} 个不同文件 · {f['bytes']} 字节。"
          f"\n只会写到新位置：{ntpath.join(base,SET_FILE)}\n保存前再核全部原件。不会提交、应用到仓库、运行程序或发送给手机。")+_warning(f["members"])
    while not locked():
        click=_dialog("保存这次完整交付补丁？",text,[("members","逐项核对文件和版本"),("save","确认保存一份新的 changes.patch")],retire=locked)
        if click=="members": inspect_members(f["members"],locked); continue
        if click!="save" or locked(): return
        visible,row=observe("保存多文件补丁","正在复核各原件并回读整份补丁及每个边界……",locked,
                            lambda point,_:sets.save(ctx,op,result,locked,point),45)
        if visible:
            if row: detail(ctx,op,row,locked)
            else: _dialog("没有取得完整保存回执","可回来查看这次组合的原记录；没有替你再生成或覆盖。",[],retire=locked)
        return
