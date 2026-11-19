"""Review a proposed Git removal; this Windows owner flow never deletes anything."""
import ntpath

from desktop_owner_entry import _dialog
import windows_removed_source as removed
from windows_added_source_entry import _choose,_short
from windows_source_patch import FILENAME,checked_output,output_names


def detail(ctx,op,row,locked,*,read_patch=None):
    if locked(): return
    f=row["fields"]
    def evidence(show=False):
        if row not in removed.records(ctx,op).values(): raise ValueError("Missing original removal patch record")
        return checked_output(ctx,op,row,locked,show=show,added=False,removed=True)
    try: state=evidence()
    except Exception: state="unavailable"
    if locked(): return
    body=(f"{f['path']}\n原提交 {f['commit'][:12]} 中有此文件（{f['originalBytes']} 字节）。当时在副本原父目录核实时未找到这个路径。"
          "这不是现在的文件状态，也不是文件被删除或改名的证明。")
    actions=[("id","查看原版本和补丁摘要")]
    if state=="saved":
        body+="\n已核实当时保存的补丁。日后手动应用它才会从目标项目移除该路径。"
        actions.insert(0,("show","在资源管理器选中 change.patch\n不打开或应用；系统文件夹由你关闭。"))
    elif state=="pending": body+="\n只有保存意图，没有完整回执；不会自动重写。"
    else: body+="\n不能再核实原补丁，不查找替代文件，也不重新生成。"
    if state=="saved" and read_patch is not None: actions.insert(0,("read-patch","在伴侣中阅读完整原补丁"))
    click=_dialog("本机原路径移除补丁",body,actions,retire=locked)
    if locked(): return
    if click=="read-patch" and read_patch is not None: read_patch(); return
    if click=="id":
        _dialog("这项补丁的固定编号",f"原提交：{f['commit']}\n原 Git blob：{f['originalOid']}\n原始 SHA-256：{f['originalSha']}"
                f"\n原文件模式：{f['mode']}\n完整补丁 SHA-256：{f['patchSha']}",[],retire=locked)
    if click=="show":
        try: evidence(True)
        except Exception:
            if not locked(): _dialog("没有定位原补丁","原文件或唯一目录成员未能核实，没有改开上级目录。",[],retire=locked)


def choose(ctx,settings,op,report,locked,observe):
    items=report["missingCandidates"]
    if not items or locked(): return
    item=_choose("核实哪个未见的原路径？","只列原父目录仍可绑定、上次枚举未见的已跟踪条目（最多 64 项、原文件 ≤128 KiB）。"
                 "可能已移动、改名，或这次又出现了；未见本身不能生成补丁。",items,
                 lambda x:_short(x["path"])+f"\n原提交 {x['bytes']} 字节",locked)
    if item is None or locked(): return
    body=(f"{item['path']}\n只检查副本中的原位置，不找移到别处的文件。还将从同一已登记仓库的原提交读出这一个文本版本。"
          "若核实原路径确实不在，可查看一份建议移除此路径的 Git 补丁。")
    if _dialog("核实原位置并读取原提交？",body,[("prepare","核实这一项，不写文件")],retire=locked)!="prepare" or locked(): return
    shown,result=observe("核实缺失的原路径","检查原父目录、名字别名、系统返回和原提交文本……",locked,
                         lambda point,_:removed.prepare(ctx,settings,op,item,point),45)
    if not shown: return
    if not result:
        _dialog("不能生成移除补丁","文件重新出现、存在别名、目录/权限有变化、读取失败或原文本不可核实，都不会算作已移除。",[],retire=locked); return
    f=result["fields"]; rid=removed.key(f)
    try: kept=removed.records(ctx,op)
    except Exception:
        _dialog("原记录不可读取","没有写入或清空记录。",[],retire=locked); return
    previous=next((r for r in kept.values() if r["id"]==rid),None)
    if previous: detail(ctx,op,previous,locked); return
    if len(kept)>=removed.BANK:
        _dialog("四个移除补丁位置均已有记录","不会覆盖已有补丁。",[],retire=locked); return
    _,_,_,folder=output_names(ctx,op,rid,0,added=False,removed=True)
    text=(f"{f['path']}\n原提交 {f['commit'][:12]} 有这个文件；刚核实副本原位置没有它。"
          f"\n这份 {f['bytes']} 字节的补丁会包含原文件全部内容。手动应用到目标项目时会移除该路径，不保留为空文件。"
          f"\n现在只另存：{ntpath.join(folder,FILENAME)}\n不删除、副本不回写、不发送给手机。")
    while not locked():
        click=_dialog("只保存这份移除补丁？",text,[("read","查看原提交文本开头"),("save","确认只将完整补丁另存到本机")],retire=locked)
        if click=="read":
            _dialog("原提交正文节选",result["excerpt"] or "原文件没有可显示的文本行。",[],retire=locked); continue
        if click!="save" or locked(): return
        shown,row=observe("另存移除补丁","再次核实原位置仍不存在，之后只写回执和新的补丁文件……",locked,
                          lambda point,_:removed.save(ctx,settings,op,result,locked,point),35)
        if shown:
            if row: detail(ctx,op,row,locked)
            else: _dialog("没有取得完整回执","可能原路径已恢复或原件不可再核；也可能已留下保存意图。可在本次副本的单文件变更中查看，不会自动重试。",[],retire=locked)
        return
