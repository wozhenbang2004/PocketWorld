"""Owner-only comparison of one historic paired read with one selected original copy inventory."""
import hashlib
import sys
import time

from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_file_revisions import _identity, _origin
from windows_git_copy import _phase, _read, inspect, slots
from windows_git_source import registered
from windows_installation import WindowsInstalledContext, same_path
from windows_owner_draft import MAX_TEXT
from windows_source_inventory import load


def stop(): raise PlatformError("Cannot bind both original sources for local comparison")


def project_path(row,read):
    src=row["source"]
    if src.get("environmentId")!=read.original_work.environment: return None
    selected=src.get("selectedProject")
    prefix=(selected["path"]+"/") if selected else ""
    if ((selected and tuple(selected["parent"])!=read.origin) or not read.path.startswith(prefix)
            or len(read.path)==len(prefix)): return None
    path=read.path[len(prefix):]
    if "selectedFile" in src and src["selectedFile"]["path"]!=path: return None
    return path if "/".join(relative_parts(path))==path else None


def choices(ctx,read,current):
    # Only small private receipts here. Do not open other inventories, files, Git objects or worktrees.
    rows=[]
    for _,row in slots(ctx):
        if not current(): stop()
        if project_path(row,read) is None: continue
        result=inspect(ctx,row)
        if result["status"]!="saved" or not result.get("itemsRecorded"): continue
        final=_read(ctx,_phase(row["operation"],"result"),4096)
        origin=final.get("registeredRootOrigin",row["source"].get("selectedProject",{}).get("parent"))
        if _origin(origin) and tuple(origin)==read.origin: rows.append(row)
    return sorted(rows,key=lambda r:r["at"],reverse=True)


def choose(view,rows,current):
    page=0
    while current():
        actions=[]
        for i,row in enumerate(rows[4*page:4*page+4],4*page):
            src=row["source"]; label=src.get("selectedProject",{}).get("path","登记根项目").replace("&","&&")
            if "selectedFile" in src: label+=" · 单文件 "+src["selectedFile"]["path"].replace("&","&&")
            when=time.strftime("%m-%d %H:%M",time.localtime(row["at"]/1000))
            actions.append((("item",i),f"{label} · {src['commit'][:12]}\n本机制作记录 {when}"))
        if page: actions.append(("prev","上一页"))
        if 4*(page+1)<len(rows): actions.append(("next","下一页"))
        pick=view._dialog("选一份已保存的本机源码副本","只列同一原工作区内、保留了来源身份的副本。仅对照手机这次读到的那一个路径。",actions,current)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        return rows[pick[1]] if pick is not None and current() else None


def show(view,stamp,current,*,bookmarks=False):
    try:
        if not current(): return
        read=stamp(full=True)
        if read is None: stop()
        manager=view.manager
        ctx=WindowsInstalledContext(backend=manager.platform,current_executable=sys.executable)
        if (not same_path(str(manager.state),ctx.jobs) or not same_path(str(view.receiver.identity.directory),ctx.identity_dir)
                or view.receiver.identity.device_id!=read.original_work.target
                or view.receiver.identity.instance_id!=read.original_work.installation): stop()
        settings=hashlib.sha256(ctx._raw(ctx.state,"companion.json",262144)).hexdigest()
        eid=read.original_work.environment
        root=dict(registered(ctx,settings)).get(eid)
        if not root or not same_path(root,manager.environments[eid]["root"]): stop()
        def active():
            try:
                return bool(current() and stamp(full=False,expected_work=read.original_work) and dict(registered(ctx,settings)).get(eid)==root)
            except Exception: return False
        def local_active():
            try:
                if not active(): return False
                with ctx.backend.directory(root) as (_,bound,_): return tuple(_identity(ctx.backend.api,bound))==read.origin
            except Exception: return False
        def exact_request(): return local_active() and stamp(full=True,expected_work=read.original_work)==read
        if bookmarks:
            from windows_git_copy_entry import _observe
            from windows_read_draft_link import open_marked
            if exact_request(): open_marked(view,ctx,read,local_active,exact_request,_observe)
            return
        handoff=None
        with ctx.backend.directory(root) as (_,h,_):
            if tuple(_identity(ctx.backend.api,h))!=read.origin or not active() or stamp(full=True)!=read: stop()
            rows=choices(ctx,read,active)
            if not rows:
                if active(): view._dialog("没有可对照的原副本","这次没有找到同时保留同一工作区身份和原清单的已保存副本。旧记录不会按目录名补认，也不会找其他项目。",[],active)
                return
            row=choose(view,rows,active)
            if row is None: return
            path=project_path(row,read)
            if not path or not active() or stamp(full=True)!=read: stop()
            _,_,items,_,_,file_ids,origin=load(ctx,row["operation"],work_root=True)
            if origin!=read.origin or not active() or stamp(full=True)!=read: stop()
            file=items.get(path)
            if file is None:
                view._dialog("原清单未收录这个准确路径",read.path+"\n没有判定两个版本相同或不同，不做同名搜索。",[],active); return
            equal=file["bytes"]==read.size and file["sha256"]==read.sha
            title="两个历史版本的文件字节一致" if equal else "两个历史版本的文件字节不同"
            text=(f"原手机任务这次从 Windows 读取：{read.path}（{read.size:,} 字节）。\n"
                  f"所选本机副本保存于原提交 {row['source']['commit'][:12]}：{path}（{file['bytes']:,} 字节）。"
                  "\n比较这两次留存的大小和完整 SHA-256，不是当前工作树状态，也不证明手机当时使用了该提交或读取了整个项目。")
            actions=[("ids","核对原操作、任务轮次和两个版本")]
            if equal and file["mode"]=="100644" and file["bytes"]<=MAX_TEXT:
                actions.insert(0,("draft","单独在这台电脑起草这个文件的补丁\n另行核对机主原文件，不把草稿带回手机任务。"))
            choice=view._dialog(title,text,actions,active)
            if choice=="draft" and exact_request():
                permission=view._dialog("转到机主自己的本机项目？",f"{path} · 原提交 {row['source']['commit'][:12]}\n"
                        f"将从原仓库与机主私有副本读取这一份完整文件（{file['bytes']:,} 字节），不是展开手机回执片段。"
                        "只编辑内存中的临时文本。以后若再确认另存，只创建机主本地的补丁，不写任何仓库或副本，不上传、发回或结束原手机任务。",
                        [("local","同意读取这个本机原文件并起草")],active)
                if permission=="local" and exact_request():
                    handoff=(row["operation"],path,file_ids[path],row["source"]["commit"],file["object"],read.size,read.sha)
            if choice=="ids" and exact_request():
                work=read.original_work
                view._dialog("这一个文件的两份原证据",f"原操作：{read.operation}\nWindows 工作：{read.job}\n手机原轮次：{work.run} / {work.executor_epoch}"
                             f"\n原副本：{row['operation']}\n原提交：{row['source']['commit']}\n手机读取 SHA-256：{read.sha}"
                             f"\n副本原清单 SHA-256：{file['sha256']}",[],active)
        if handoff is not None:
            # Release the original handles between owner interactions. Revalidate the same root and live receipt.
            from windows_git_copy_entry import _observe
            from windows_owner_draft_entry import exact
            from windows_read_draft_link import pin_saved
            def bookmark(saved):
                pin_saved(view,ctx,read,row,path,file,file_ids[path],saved,local_active,exact_request,_observe)
            try: exact(ctx,settings,*handoff,local_active,exact_request,_observe,bookmark=bookmark)
            except Exception:
                if current(): view._dialog("本机草稿不能继续","不会重开其他目录或把手机回执正文当作原文件。如果已确认另存，请到原副本的补丁记录核实；不会重做。",[],current)
    except Exception:
        if current(): view._dialog("现在无法对照原文件","原读取、当前授权、安装、原工作区或所选私有清单不能同时核对。没有改查实时文件、联系手机或写项目。",[],current)
