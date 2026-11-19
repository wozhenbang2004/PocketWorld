"""Local-only project source review; no replica, service, install or authorization."""
import threading
import time
import unicodedata

from desktop_owner_entry import _dialog, _folder
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from windows_git_source import Incomplete, capture, registered
from windows_git_copy import storage_limit
from windows_git_copy_entry import browse as browse_copies, confirm_copy

REASONS = {
    "not_repository": "这个已登记目录本身没有 .git；不会向上、向下搜索，也不把普通文件夹当作项目。",
    "outside_selected_root":"只接受这一个原登记工作区内的下级文件夹；不会使用外部路径或改选其他工作区。",
    "selected_project_changed":"原工作区、所选路径、项目目录或原 .git 已经不能同时核对；不会改读同名项目。",
    "head_unresolved": "未找到可验证的当前提交。分支可能尚无首个提交，或原引用已不可用。",
    "external_layout": "本阶段不跟随外置工作树、子模块 gitfile、对象替代库或配置包含路径。",
    "config_format": "不能在此处核实这个仓库格式；不会调用 Git、读取外部配置或改写配置。",
    "ref_format": "当前引用并非本阶段可准确读取的本地分支或分离提交。",
    "object_unavailable": "没有在这份仓库的本地对象或原对象包中核对到所需对象，可能未留存。不会向别处查找或联网补取。",
    "pack_layout": "不能从这份本地对象包独立还原所需对象，例如旧索引或缺少本包内的增量基底。没有使用外部来源。",
    "invalid_pack": "这份对象包与原索引、压缩记录或实际 Git 对象摘要对不上，不能确认源版本。",
    "pack_limit": "核对原对象包已到本机读取或增量还原上限。没有扫描整包或转为后台运行。",
    "invalid_object": "实际 Git 对象的类型、长度、内容摘要或根树结构对不上，不能确认源版本。",
    "unsafe_layout": "原 Git 路径包含别名、多链接、重解析点或不是预期对象，未读取其他位置。",
    "configuration_changed": "已登记的工作区或安装配置已改变，请回到准备页重新选择。",
    "changed": "读取期间原 Git 引用或布局已改变，请在你需要时重新核对。",
    "limit": "原 Git 元数据超过本机预检范围，不能据此确认项目版本。",
    "original_source_changed": "刚才所选仓库、分支或提交已改变。没有改查新版本，请回准备页重新选择。",
    "unmapped_name": "有目录条目无法按本机 Windows 文件名规则原样表示。不会重命名、替换字符或跳过。",
    "protected_or_unselected": "提交内有本机项目策略排除的私有路径或未选择的生成目录。没有展开它，也不显示私有名称。",
    "name_collision": "同一目录中的不同名字会在 Windows 大小写比较或保守的 Unicode 别名检查中冲突。不会覆盖或合并。",
    "symbolic_entry": "原树中有符号链接，不是普通文件；未跟随，也未把它当成文件。",
    "submodule_entry": "原树中有独立子模块的提交引用，不是当前仓库的普通目录；未展开或拉取。",
    "name_comparison_unavailable": "未取得本机原生的文件名比较结果，不据此宣称路径可以落盘。",
    "tree_limit": "目录数量、深度、元数据或读取时间超过本次限定，没有将截断的清单当成完整结果。",
    "original_mapping_changed": "原仓库、原提交和刚才的完整目录映射未能再次对上。没有读取另一个文件集。",
    "content_limit": "已提交文件数、单个文件、总量或本机核对时间超过这次的界限，没有把部分文件当作完整源码。",
    "review_cancelled": "已结束此次本地核对。",
    "native_read_unavailable": "当前无法拿到准确的原生只读句柄，可能文件正被修改或不可读。没有改变仓库。",
}


def _display(value):
    return "".join(ch if not unicodedata.category(ch).startswith("C") else "�" for ch in value)


def _review_source(context,settings,source,locked,*,content=False):
    stopped, done = threading.Event(), threading.Event()
    result, auto_finish = {}, False
    expires = time.monotonic()+(120 if content else 30)
    heading = "核对已提交的文件正文" if content else "核对这次提交的目录和文件名"
    status = f"{source['commit'][:12]} · 正在核对原仓库和已提交目录。关闭即可停止，不会在后台重试。"
    def count(n,total,size):
        nonlocal status
        status = f"{source['commit'][:12]} · 已核对 {n}/{total} 个文件（{size/1048576:.1f} MiB）。关闭即可停止。"
    def checkpoint():
        if stopped.is_set(): raise Incomplete("review_cancelled")
        if time.monotonic()>expires: raise Incomplete("content_limit" if content else "tree_limit")
    def worker():
        try: result.update(capture(context,settings,source["environmentId"],layout_for=source,content=content,checkpoint=checkpoint,on_count=count))
        except Exception: result.update(sourceVerified=False,reason="native_read_unavailable")
        finally: done.set()
    def close_progress():
        nonlocal auto_finish
        if locked(): stopped.set(); return True
        if done.is_set(): auto_finish = True; return True
        return False
    task = threading.Thread(target=worker,name="owner-selected-git-name-review")
    task.start()
    try:
        _dialog(heading,status,[],retire=close_progress,progress=(lambda: status) if content else None)
    finally:
        stopped.set()
        task.join()
    if not auto_finish or locked(): return
    check = result.get("committedNameReview") if result.get("sourceVerified") else None
    if not check or not check["complete"]:
        code = check["reason"] if check else result.get("reason")
        why = REASONS.get(code,REASONS["native_read_unavailable"])
        if check and check.get("example"):
            # Private/unprintable names have no example; do not log any path.
            sample = _display(check["example"])
            why += "\n原提交中的条目：" + (sample if len(sample)<=176 else "…"+sample[-175:])
        _dialog("没有确认完整的目录映射",why+"\n没有创建文件、目录或副本。",[],retire=locked)
        return
    if content:
        actual = result.get("committedContentReview",{})
        if not actual.get("complete"):
            why = REASONS.get(actual.get("reason"),REASONS["native_read_unavailable"])
            _dialog("没有确认全部已提交文件的正文",why+"\n没有保存部分文件清单、写入或启动工作。",[],retire=locked)
            return
        text = f"{source['commit'][:12]} · {actual['files']} 个文件，{actual['logicalBytes']/1048576:.1f} MiB。"
        text += "\n已逐一核实 Git 保存的原始字节；没有读取未提交改动、应用过滤器、复制文件或启动执行器。"
        if actual["lfsHeaderFiles"]: text += f"\n{actual['lfsHeaderFiles']} 个文件含 LFS 指针版本头；已核实的是指针，不是外部大文件。"
        limit = storage_limit(actual)
        choices = [("digest","查看完整内容清单编号")]
        if limit: text += "\n"+limit
        else: choices.insert(0,("copy","另存本机独立源码副本\n先查看确切新位置与范围；不会启动项目。"))
        action = _dialog("原提交的文件正文已核对",text,choices,retire=locked)
        if locked(): return
        if action=="copy": confirm_copy(context,settings,result,locked)
        elif action=="digest":
            _dialog("已提交文件内容编号",actual["contentManifestDigest"]+"\n按原路径、模式、Git 对象号、长度和 SHA-256 绑定。只是本次本机核对，不是副本、权限或交付回执。",[],retire=locked)
        return
    text = f"{source['commit'][:12]} · {check['directories']} 个子目录，{check['files']} 个普通文件。"
    text += "\n已核对各层 Git 目录对象、原文件名与本机命名规则；未打开文件正文，未验证目标卷，未创建副本。"
    action = _dialog("原提交的目录映射已核对",text,
        [("content","核对这些文件的已提交正文\n仅本机读取原 Git 对象并计算摘要，不显示文件内容或创建副本。"),
         ("digest","查看这次目录映射的完整编号")],retire=locked)
    if locked(): return
    if action=="content":
        _review_source(context,settings,result,locked,content=True)
    elif action=="digest":
        _dialog("目录记录编号",check["mappingDigest"]+"\n绑定该根树的原路径、模式和对象编号，不是文件正文或交付回执。",[],retire=locked)


def _project_parent(choices,locked):
    if len(choices)==1: return choices[0][0]
    page=0
    while not locked():
        buttons=[(("root",key),key+"\n"+_display(root)) for key,root in choices[page*5:page*5+5]]
        if page: buttons.append(("prev","上一页"))
        if (page+1)*5<len(choices): buttons.append(("next","下一页"))
        selected=_dialog("项目属于哪个已登记工作区？","只在选定根内手动选择，不扫描其他工作区。",buttons,retire=locked)
        if selected=="prev": page-=1; continue
        if selected=="next": page+=1; continue
        return selected[1] if selected and not locked() else None


def _nested(context,settings,key,locked):
    from windows_git_subproject import pick
    try: selected=pick(context,settings,key,_folder,locked)
    except Exception as exc:
        if not locked(): _dialog("没有选定可核对的项目",REASONS.get(getattr(exc,"reason",None),REASONS["unsafe_layout"])
                                 +" 没有登记新位置或读取文件正文。",[],retire=locked)
        return None
    if selected is None or locked(): return None
    action=_dialog("核对这个项目的原提交？",key+" / "+selected["path"]+
                   "\n只读取这个文件夹自身的普通 Git 元数据；不读取工作树、启动服务或改变手机授权。"
                   "只核对不会在伴侣中登记此次选择；另行确认制作私有副本才会保存必要的来源证明。",
                   [("inspect","仅核对这个子仓")],retire=locked)
    if action=="inspect" and not locked(): return capture(context,settings,key,selected_project=selected)
    return None


def open_source(context, settings):
    def locked(): return not WindowsDesktopHealth().probe(session_only=True).get("available")
    history = ("copies","本机独立副本与原回执\n只核对已登记的本机制作记录，不依赖原仓库仍在当前提交。")
    try:
        choices = registered(context, settings)
    except (Incomplete, PlatformError, OSError, ValueError):
        if _dialog("本机登记暂不可核对", REASONS["configuration_changed"], [history], retire=locked)=="copies" and not locked():
            browse_copies(context,locked,settings)
        return
    if not choices:
        if _dialog("没有可供预检的本机工作区", "仅接受原安装已登记的 Windows 本机目录；不接受 SSH/网络位置或伴侣私有安装目录。",[history],retire=locked)=="copies" and not locked():
            browse_copies(context,locked,settings)
        return
    page = 0
    while not locked():
        blocks = [(key, key+"\n"+_display(root)) for key,root in choices[page*5:page*5+5]]
        # Tagged choices keep even an owner's environment named 'next' or 'previous' distinct.
        buttons = [(("workspace",key),label) for key,label in blocks]
        if page: buttons.append(("previous","上一页工作区"))
        if (page+1)*5 < len(choices): buttons.append(("next","下一页工作区"))
        buttons.extend([("nested","选择工作区中的一个 Git 项目文件夹\n适用于一个工作区里放了多个仓库；不会自动搜索。"),history])
        selected = _dialog("从哪个已登记的本机工作区核对？", "只读取这个根目录自身的 Git 元数据。不会运行 Git、读取工作文件正文或允许写入。",buttons,retire=locked)
        if selected == "next": page += 1; continue
        if selected == "previous": page -= 1; continue
        if selected is None or locked(): return
        if selected=="copies": browse_copies(context,locked,settings); return
        if selected=="nested":
            key=_project_parent(choices,locked)
            if key is None: return
            result=_nested(context,settings,key,locked)
        else:
            key=selected[1]; result=capture(context,settings,key)
            if not locked() and not result["sourceVerified"] and result.get("reason")=="not_repository":
                again=_dialog("这个工作区根不是 Git 仓库",key+"\n不会扫描子目录。若项目位于这里的下级文件夹，请选择确切的一个。",
                              [("child","手动选择这个工作区里的 Git 项目")],retire=locked)
                result=_nested(context,settings,key,locked) if again=="child" and not locked() else None
        if result is None or locked(): return
        if not result["sourceVerified"]:
            _dialog("没有确认这个项目的源版本", key+"\n"+REASONS.get(result.get("reason"),REASONS["native_read_unavailable"]),[],retire=locked)
            return
        branch = result["branch"]
        when = time.strftime("%Y-%m-%d %H:%M:%S",time.localtime(result["capturedAt"]/1000))
        where = "分离 HEAD" if branch is None else "分支 "+_display(branch.removeprefix("refs/heads/"))
        source_label=key+(" / "+result["selectedProject"]["path"] if "selectedProject" in result else "")
        summary = f"{source_label} · {where}\n{when} 核实提交 {result['commit'][:12]} 与其根树。"
        summary += "\n这是已提交的版本；未检查未提交改动、子目录或所有对象，未创建副本，也未开放项目生产。"
        action = _dialog("已核对这台 Windows 上的 Git 来源",summary,
             [("names","检查这次提交的目录映射\n逐层核对文件名和目录；不读取文件正文或创建副本。"),
              ("identity","查看完整仓库与源版本编号")],retire=locked)
        if locked(): return
        if action=="names":
            _review_source(context,settings,result,locked)
        elif action=="identity":
            details = f"本机仓库标识：{result['repositoryAnchor']}\n提交 ({result['objectFormat']})：{result['commit']}\n根树：{result['tree']}"
            stores = result["objectStorage"]
            label = {"loose":"散对象","pack":"本地对象包"}
            details += f"\n原件保存位置：提交（{label[stores['commit']]}），根树（{label[stores['rootTree']]}）。"
            _dialog("此次预检的不可变对象",details+"\n标识仅对应这份已登记仓库及此次观察；不是他台设备的项目权限。",[],retire=locked)
        return
