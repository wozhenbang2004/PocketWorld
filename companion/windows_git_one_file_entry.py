"""Owner-local committed-file reader. No output, copy or remote project authority."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import ask_committed_path, source_text
from desktop_sessions import WindowsDesktopHealth
from windows_git_source import registered
import windows_git_one_file as model


def path_choice(ctx,settings,src,current,observe):
    def locked(): return not current()
    detail=(f"原提交 {src['commit'][:12]}。只还原你明确选择的一份普通 UTF-8 文本（≤64 KiB），"
            "不因其他 LFS 文件或整仓副本体积上限而要求先复制整个项目。不会读取当前工作树、运行 Git 或下载外部对象。")
    pick=_dialog("查看哪一个已提交文件？",detail,[("path","输入确切相对路径"),("browse","逐层选择原提交中的文件")],retire=locked)
    folder=""; page=0; listing=None
    while current():
        if pick=="path":
            raw=ask_committed_path(current)
            if raw is None or not current(): return None
            path=raw.replace("\\","/")
            try: model.pieces(path)
            except Exception:
                pick=_dialog("没有接受这个路径","仅接受这个项目中的准确相对路径。不解析外部位置、私有名称或父路径。",
                             [("path","重新输入"),("browse","从原提交目录选择")],retire=locked)
                continue
            return path,None
        if pick!="browse": return None
        if listing is None:
            visible,listing=observe("读取这一层原 Git 目录",folder or "原提交的根目录；不会展开子树或读取文件正文。",locked,
                                    lambda check,_:model.folder(ctx,settings,src,folder,check),30)
            if not visible: return None
            if listing is None:
                _dialog("这一层原目录无法核对","不会转为扫描工作文件夹，也不会显示被排除的名称。",[],retire=locked); return None
        actions=[]
        for i,(branch,name,_,_) in enumerate(listing[page*5:page*5+5],page*5):
            actions.append((("item",i),("文件夹 · " if branch else "")+name.replace("&","&&")))
        actions.append(("path","直接输入原文件的相对路径"))
        if folder: actions.append(("up","上一级原目录"))
        if page: actions.append(("prev","上一页"))
        if (page+1)*5<len(listing): actions.append(("next","下一页"))
        choose=_dialog("原提交 · 选择文件",f"{folder or '项目根目录'}\n只列这一层可核对的普通文件与目录；不显示私有或不可安全映射的名称。",actions,retire=locked)
        if choose is None or not current(): return None
        if choose=="path": pick="path"; continue
        if choose=="prev": page-=1; continue
        if choose=="next": page+=1; continue
        if choose=="up": folder=folder.rpartition("/")[0]
        else:
            branch,_,path,oid=listing[choose[1]]
            if not branch: return path,oid
            folder=path
        page,listing=0,None


def show(ctx,settings,src,locked):
    from windows_git_copy_entry import _observe
    health=WindowsDesktopHealth(); session=health.probe(session_only=True)
    if (session.get("available") is not True or type(session.get("sessionId")) is not int
            or session["sessionId"]<=0 or locked()): return
    try: root=dict(registered(ctx,settings))[src["environmentId"]]
    except Exception: return
    def current():
        try:
            now=health.probe(session_only=True)
            return (not locked() and now.get("available") is True and now.get("sessionId")==session.get("sessionId")
                    and dict(registered(ctx,settings)).get(src["environmentId"])==root)
        except Exception: return False
    def closed(): return not current()
    choice=path_choice(ctx,settings,src,current,_observe)
    if choice is None or not current(): return
    path,expected=choice
    if _dialog("只读取这个已提交文件？",f"{path}\n原提交：{src['commit']}\n只从原 Git 对象还原该文件的原始字节。"
               "不读取同级文件正文，不创建副本，不发送到手机。",[("read","读取这一份原文本")],retire=closed)!="read" or closed(): return
    visible,file=_observe("核对选中的 Git 原件",path+"\n正在核对提交、逐级目录对象和这一份文件的内容编号。",closed,
                          lambda check,_:model.read(ctx,settings,src,path,expected,check),40)
    if not visible: return
    if file is None:
        _dialog("没有打开这个原文件","原提交或确切路径可能已不可核对；二进制、LFS 指针、文本或对象超限、链接及同名映射冲突也不会打开。没有从工作树补读。",[],retire=closed); return
    while current():
        actions=[("show","查看完整原文本"),("ids","查看原提交、Git 对象和字节摘要")]
        if file.repository and file.git:
            actions.insert(1,("save","只保存这一份文件的独立副本\n先核对原选择和新位置，不复制整个项目。"))
        if file.repository and file.git and file.mode=="100644":
            actions.insert(1,("work","与这台电脑上的工作文件对照\n先核对同一路径，再决定是否读取当前文件。"))
        selected=_dialog("已核实一份已提交原文件",f"{file.path}\n{file.size:,} 字节 · 原提交 {src['commit'][:12]}\n"
                         "这不是当前磁盘文件，也不是整仓核验或副本。",actions,retire=closed)
        if selected is None or not current(): return
        if selected=="work":
            from windows_git_workfile_entry import compare
            compare(ctx,settings,src,file,current,_observe); continue
        if selected=="save":
            from windows_git_copy_entry import confirm_copy
            from windows_git_one_copy import descriptor
            confirm_copy(ctx,settings,{**src,"selectedFile":descriptor(file)},closed); return
        if selected=="ids":
            _dialog("这一份原文件的编号",f"原提交：{src['commit']}\nGit blob：{file.oid}\n原始字节 SHA-256：{file.sha}",[],retire=closed)
        else:
            if source_text(current,"已提交原文件 · "+file.path.split("/")[-1],
                           "本机只读；换行按系统显示，摘要对应 Git 原始字节。不写文件或发送。",file.text,review=True) is None: return
