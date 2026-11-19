"""Native, owner-only authoring and recovery. No serving, editor process or working-tree write."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import source_text
import windows_owner_draft as model


def pick_file(files,locked):
    folder=""; page=0; size=5
    while not locked():
        entries={}
        for path,length in files.items():
            if path.startswith(folder):
                name,sep,_=path[len(folder):].partition("/")
                entries[(bool(sep),name)]=length
        ordered=sorted(entries,key=lambda k:(not k[0],k[1].casefold(),k[1]))
        menu=[]
        for branch,name in ordered[page*size:(page+1)*size]:
            name_ui=name.replace("&","&&")
            menu.append(((branch,name),("文件夹 · "+name_ui) if branch else f"{name_ui}\n{entries[(branch,name)]:,} 字节"))
        if folder: menu.append(("up","上一级原目录"))
        if page: menu.append(("prev","上一页"))
        if (page+1)*size<len(ordered): menu.append(("next","下一页"))
        pick=_dialog("选择一个原文件",f"{folder or '原副本根目录'}\n只列原提交中 ≤32 KiB 的普通非执行文件；选中后核实它仍是原件和可编辑文本。",menu,retire=locked)
        if pick is None or locked(): return None
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        if pick=="up": folder=folder.rstrip("/").rpartition("/")[0]; folder+=("/" if folder else ""); page=0; continue
        branch,name=pick
        if not branch: return folder+name
        folder+=name+"/"; page=0


def start(ctx,settings,op,locked,observe):
    def retired():
        if locked(): return True
        try: model.registered(ctx,settings)  # original install, configuration digest and approved local roots
        except Exception: return True
        return False
    visible,files=observe("读取原副本清单","只列原先保存的项目文件名；没有读取未选文件的正文。",retired,
                          lambda check,_: model.index(ctx,op))
    if not visible: return
    if not files or not settings:
        _dialog("现在无法起草","没有可核到的原文件清单、登记配置或受支持的小文件。不会打开别的文件，也不写项目。",[],retire=retired); return
    while not retired():
        path=pick_file(files,retired)
        if path is None: return
        visible,base=observe("核对选中的原文件",path+"\n核对已登记仓库的原提交与原副本的这个文件，不读取当前工作树。",retired,
                             lambda check,_: model.prepare(ctx,settings,op,path,check),40)
        if not visible: return
        if base is not None: break
        if _dialog("未打开这份原文件","已修改或替换的副本、二进制、混合换行、LFS 指针或无法核对的原提交均不载入。没有写入。",
                   [("again","返回原清单另选")],retire=retired)!="again": return
    else: return
    edit(ctx,settings,op,base,retired,observe)


def exact(ctx,settings,op,path,original_id,commit,oid,size,sha,active,request,observe):
    # Separate owner entry. No generic inventory/picker, alternate path, remote text, or automatic selection.
    from desktop_sessions import WindowsDesktopHealth
    health=WindowsDesktopHealth()
    first=health.probe(session_only=True)
    session=first.get("sessionId")
    if first.get("available") is not True or type(session) is not int or session<=0: return
    def retired():
        live=health.probe(session_only=True)
        return live.get("available") is not True or live.get("sessionId")!=session or not active()
    if retired() or not request(): return
    visible,base=observe("核对要单独起草的本机原文件",path+"\n从机主已选的原 Git 提交与独立副本读取，不从手机回执取正文。",
                         retired,lambda check,_: model.prepare(ctx,settings,op,path,check),40)
    if not visible or retired(): return
    if (base is None or base.path!=path or base.commit!=commit or base.oid!=oid or base.origin!=tuple(original_id)
            or len(base.raw)!=size or model.digest(base.raw)!=sha or not request()):
        _dialog("未打开本机草稿","没有同时核实原读取和这一份本机原文件。不会改开其他路径、用回执片段代替正文或新建空稿。",[],retire=retired); return
    edit(ctx,settings,op,base,retired,observe,original_request=request)


def edit(ctx,settings,op,base,retired,observe,*,original_request=None):
    bom,eol,initial=model.unpack(base.raw)
    convention=("CRLF" if eol=="\r\n" else "LF")+(" · 保留 UTF-8 BOM" if bom else " · UTF-8 无 BOM")
    label=base.path if len(base.path)<=75 else "…"+base.path[-74:]
    draft=initial
    while not retired():
        draft=source_text(lambda:not retired(),"临时草稿 · "+label,
                          "只编辑内存中的文本，不保存到项目。点「预览差异」后仍可返回。",draft)
        if draft is None or retired(): return
        if draft==initial:
            if _dialog("草稿没有变化","没有产生补丁，源仓库与副本未修改。",
                       [("edit","返回编辑")],retire=retired)=="edit": continue
            return
        def guard():
            if retired(): model.block()
        try: reviewed=model.proposal(ctx,op,base,draft,guard)
        except Exception:
            if _dialog("这份草稿还不能生成补丁","只接受 ≤32 KiB 的普通 UTF-8 文本、单一换行和完整可比较的文本行。没有写入。",
                       [("edit","返回编辑当前草稿")],retire=retired)=="edit": continue
            return
        f,raw=reviewed
        patch_text=raw.decode("utf-8").replace("\r\n","\n").replace("\n","\r\n")
        while not retired():
            if source_text(lambda:not retired(),"完整差异 · "+label,"只读预览：减号为原行，加号为草稿行。返回后仍须另行确认。",
                           patch_text,review=True) is None or retired(): return
            description=(f"{base.path}\n原提交 {base.commit[:12]} · {convention}\n原文 {len(base.raw):,} 字节 → 草稿 {f['draftBytes']:,} 字节。"
                         f"\n另存一份本机补丁（{len(raw):,} 字节）：\n"+model.destination(ctx,op,f)+
                         "\n只创建该新原件，不覆盖同名文件，不修改仓库或副本，不应用、执行或发送。")
            pick=_dialog("把这次草稿另存为补丁？",description,
                         [("save","确认另存这一份补丁"),("edit","返回编辑刚才的草稿"),("inspect","再看完整差异")],retire=retired)
            if pick=="inspect": continue
            if pick=="edit": break
            if pick!="save" or retired(): return
            visible,row=observe("核对并另存这次补丁","再次核对原提交、原副本和刚才看过的全部差异，不替换已有文件。",retired,
                                lambda check,_: model.save(ctx,settings,op,base,draft,reviewed,retired,check,original_request=original_request),60)
            if not visible: return
            if row is None:
                _dialog("未确认这份补丁已保存","原件、可用名额或保存证据不足。可能留有原请求；请从这份副本的「已另存的单文件变更」核对，不会自动重做。",[],retire=retired)
            else: detail(ctx,op,row,retired)
            return


def detail(ctx,op,row,locked):
    try: state=model.check(ctx,op,row,locked)
    except Exception: state="unavailable"
    if locked(): return
    f=row["fields"]
    text=f"{f['path']} · 原提交 {f['commit'][:12]}\n这是机主起草的一份差异，不是仓库或副本已修改的证明。"
    if state!="saved":
        _dialog("原草稿补丁尚不能核实",text+"\n"+("有原保存请求，但没有可核实的最终保存回执。" if state=="pending" else
                "原回执、文件身份或字节现在不能重新核对。")+" 不会重新生成或改开同名项。",[],retire=locked); return
    path=model.destination(ctx,op,f)
    picked=_dialog("机主草稿补丁已核实",text+f"\n{f['bytes']:,} 字节 · "+path,
                   [("show","在文件资源管理器中定位原补丁\n不打开文件、不调用关联程序。"),("ids","查看原文、草稿和补丁编号")],retire=locked)
    if locked(): return
    if picked=="show":
        try: model.check(ctx,op,row,locked,show=True)
        except Exception: _dialog("未定位原补丁","原文件或专属输出目录无法再次核对。不改开上级目录。",[],retire=locked)
    elif picked=="ids":
        _dialog("这份独立补丁的编号",f"原提交：{f['commit']}\n原文件：{f['originalOid']}\n草稿内容编号（未提交）：{f['draftOid']}"
                f"\n原文 SHA-256：{f['baseSha']}\n草稿 SHA-256：{f['draftSha']}\n补丁 SHA-256：{f['patchSha']}",[],retire=locked)
