"""Search a manually selected original directory locally; choose which original hit to read."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import START_LOCAL_DRAFT, ask_source_query, source_text
from desktop_sessions import WindowsDesktopHealth
from windows_git_source import registered
import windows_copy_find as find


def label(name): return name.replace("&","&&")


def members(items,locked):
    selected=set(); page=0
    while not locked():
        actions=[]
        for item in items[page*5:(page+1)*5]:
            actions.append((("file",item.path),("✓ " if item.path in selected else "")+label(item.path.rpartition("/")[2])+f" · {item.size:,} 字节"))
        if page: actions.append(("prev","上一页"))
        if 5*(page+1)<len(items): actions.append(("next","下一页"))
        total=sum(m.size for m in items if m.path in selected)
        if selected and total<=find.TOTAL: actions.append(("done",f"核对这 {len(selected)} 份文件"))
        pick=_dialog("选择要查的原文件",f"已选 {len(selected)}/{find.FILES} 份 · {total:,}/{find.TOTAL:,} 字节。再次点同一项可取消。",actions,retire=locked)
        if pick is None or locked(): return None
        if pick=="done": return tuple(selected)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        path=pick[1]
        if path in selected: selected.remove(path)
        elif len(selected)<find.FILES: selected.add(path)


def choose(book,locked):
    _,dirs,files,all_paths=book; folder=""; page=0
    while not locked():
        direct=sorted((m for m in files.values() if m.path.rpartition("/")[0]==folder),key=lambda m:m.path)
        children=sorted(d for d in dirs if d.rpartition("/")[0]==folder)
        others=sum(p.rpartition("/")[0]==folder for p in all_paths)-len(direct)
        actions=[]
        if direct and len(direct)<=find.FILES and sum(m.size for m in direct)<=find.TOTAL:
            actions.append(("all",f"只查本层这 {len(direct)} 个候选文件"))
        if direct: actions.append(("some","逐个选本层文件"))
        actions.extend((("dir",d),"文件夹 · "+label(d.rpartition("/")[2])) for d in children[page*4:(page+1)*4])
        if folder: actions.append(("up","上一级原目录"))
        if page: actions.append(("prev","上一页目录"))
        if 4*(page+1)<len(children): actions.append(("next","下一页目录"))
        pick=_dialog("在哪个原目录查找？",f"{folder or '原副本根目录'}\n本层有 {len(direct)} 个普通非执行小文件候选；另有 {others} 个原文件不纳入。"
                     "只用原清单，未读正文；不含子目录。",actions,retire=locked)
        if pick is None or locked(): return None
        if pick in ("all","some"):
            paths=[m.path for m in direct] if pick=="all" else members(direct,locked)
            if paths: return find.scope(book,folder,paths)
            if locked(): return None
            continue
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        folder=folder.rpartition("/")[0] if pick=="up" else pick[1]; page=0


def start(ctx,settings,op,base_locked,observe):
    health=WindowsDesktopHealth(); first=health.probe(session_only=True); sid=first.get("sessionId")
    if first.get("available") is not True or type(sid) is not int or sid<=0 or base_locked(): return
    def current():
        try:
            live=health.probe(session_only=True); registered(ctx,settings)  # installed config, not live source availability
            return not base_locked() and live.get("available") is True and live.get("sessionId")==sid
        except Exception: return False
    def locked(): return not current()
    def run(title,note,fn):
        def read(check,progress):
            def gate():
                check()
                if locked(): find.bad()
            return fn(gate,progress)
        return observe(title,note,locked,read,60)
    visible,book=run("核对原项目清单","只读取本机已保存的目录、文件名和版本证明。",lambda check,_:find.catalog(ctx,op))
    if not visible: return
    if book is None:
        _dialog("没有可查的原项目清单","不会扫描现目录、改搜其他副本或读取工作树。",[],retire=locked); return
    approved=choose(book,locked)
    if approved is None or locked(): return
    word=ask_source_query(current)
    if word is None or locked(): return
    if not find.valid(word):
        _dialog("未开始查找","只接受单行、可见的字面文字，不使用控制字符或截断词。",[],retire=locked); return
    where=approved.folder or "原副本根目录"
    while current():
        answer=_dialog("只查这些本机原文件？",f"原提交 {approved.commit[:12]} · {where}\n只查已选 {len(approved.members)} 份，总计 {sum(m.size for m in approved.members):,} 字节。"
                       f"\n查找「{word}」，区分大小写。不递归、不打开新文件、不查工作树。搜索词与结果不存盘或发送。",
                       [("find","开始本机只读查找"),("list","查看将读取的全部原文件名")],retire=locked)
        if answer is None or locked(): return
        if answer=="find": break
        names="\r\n".join(m.path for m in approved.members)
        if source_text(current,"查找范围","这里只列刚才选择的原文件；返回后仍需确认。",names,review=True) is None: return
    else: return
    visible,report=run("查找原文件中的文字","核实身份和实际字节后才查该文件；关闭可停止。",lambda check,progress:find.find(ctx,approved,word,check,progress))
    if not visible: return
    if report is None:
        _dialog("没有取得可信的查找结果","原范围、目录或读取窗口不能再核实。不改用其他文件。",[],retire=locked); return
    total=report["hitLines"]; hits=report["hits"]; page=0
    body=(f"{where} · 原提交 {approved.commit[:12]}\n已核文本 {report['checked']} 份，其中 {report['hitFiles']} 份有命中，共 {total} 行。"
          f"\n已换文件 {report['replaced']}，字节不同 {report['changed']}，非受支持文本 {report['notText']}，无法核实 {report['unreadable']}；这些不作无命中。")
    if len(hits)<total: body+=f"\n这里只列前 {len(hits)} 个命中行；不是全部行的位置。"
    while current():
        actions=[(("hit",i),label(h[0].path.rpartition("/")[2])+f" · 第 {h[1]} 行\n"+label(h[3])) for i,h in enumerate(hits[page*4:(page+1)*4],page*4)]
        if page: actions.append(("prev","上一页"))
        if (page+1)*4<len(hits): actions.append(("next","下一页"))
        pick=_dialog("所选原文件的查找结果",body,actions,retire=locked)
        if pick is None or locked(): return
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        hit=hits[pick[1]]
        visible,view=run("重新核对命中的原文件",hit[0].path,lambda check,_:find.open_hit(ctx,approved,word,hit,check))
        if not visible: return
        if view is None:
            _dialog("没有打开这个命中","原文件或原行不能再次核实，不用同名文件代替。",[],retire=locked); continue
        eligible=hit[0].size<=32768
        hint=f"原提交 {approved.commit[:12]} · 第 {hit[1]} 行。只读原件。"+("另点起草需再次核实原 Git 来源。" if eligible else "")
        pick=source_text(current,"原文件 · "+hit[0].path[-70:],hint,view[0],review=True,selection=view[1],from_hit=eligible)
        if pick is START_LOCAL_DRAFT and eligible and current():
            from windows_copy_hit_draft import start as start_at_hit
            start_at_hit(ctx,settings,approved,word,hit,view,locked,observe)
