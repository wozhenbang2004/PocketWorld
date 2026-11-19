"""Machine-owner local artifact UI. No remote endpoints or project worker."""
import threading
import time

from desktop_owner_entry import _dialog
from platform_backend import PlatformError
from windows_git_copy import BANK, CopyBlocked, inspect, plan, save, slots, source_facts
from windows_source_publisher import disk
from windows_source_inventory import compare, reveal
from windows_source_diff import selected as compare_selected
import windows_source_patch_entry as patches


def show_receipt(ctx,evidence,locked,settings=None):
    if locked(): return
    facts, state = evidence["source"],evidence["status"]
    title = "原副本制作回执"
    message = f"{facts['environmentId']} · 提交 {facts['commit'][:12]} · {facts['files']} 个文件"
    if state=="saved":
        message += "\n原发布回执已核实全部文件与目录，当前路径仍是原目录身份。若你后来编辑过，不能据此断言内容未变。"
    elif state=="saved_original_unavailable":
        message += "\n原发布时核实成功；现在未能重新绑定那个原目录。不会把同名目录当成果。"
    elif state=="published_unconfirmed":
        message += "\n目标出现了原暂存目录的身份，但没有完整的最终回执。不将它标为已交付。"
    elif state=="original_stage_retained":
        message += "\n仍可识别原暂存目录，没有确认发布。不会再次发布、清除或覆盖它。"
    elif state=="failed":
        message += "\n已记录未进入发布。"+("此次暂存清理已确认。" if evidence.get("cleanupConfirmed") else "暂存清理未确认。")
    else:
        message += "\n只确认原制作意图。中断可能留下暂存材料，没有可证明的发布或完整副本。不会自动重做。"
    if evidence.get("path"): message += "\n本机位置："+evidence["path"]
    actions = [("identity","查看原操作和内容编号")]
    if state=="saved":
        if evidence.get("itemsRecorded"):
            if settings:
                actions.insert(0,("draft","从原文件起草补丁\n只编辑一份临时文本；原仓库与副本保持不变。"))
            actions[:0] = [("open","在文件资源管理器中打开原副本\n定位原文件夹中的一个原条目，不打开文件或运行项目。"),
                            ("compare","核对现在的原文件\n用此副本保存时的逐项清单比较，不需要源仓库仍然在线。")]
        else: message += "\n旧回执没有保留可重新绑定的逐文件清单；不会用同名文件推断当前内容。"
    actions.append(("patches","已另存的单文件变更\n只查这次副本的本机补丁原件，不需要当前源仓库。"))
    chosen = _dialog(title,message,actions,retire=locked)
    if locked(): return
    if chosen=="open":
        try: reveal(ctx,evidence["operation"],locked)
        except (AttributeError,OSError,ValueError,TypeError,PlatformError):
            _dialog("未打开原副本","没有同时核实原目录、用于定位的原成员和本机系统文件夹入口。不会改开上级目录、同名替代物或执行文件。",[],retire=locked)
    elif chosen=="draft":
        from windows_owner_draft_entry import start
        start(ctx,settings,evidence["operation"],locked,_observe)
    elif chosen=="patches": patches.browse(ctx,evidence["operation"],locked,_observe)
    elif chosen=="compare": _compare(ctx,evidence["operation"],locked,settings)
    elif chosen=="identity":
        _dialog("原副本编号",f"操作：{evidence['operation']}\n原提交：{facts['commit']}\n原始内容清单：{facts['content']}\n不含执行权、手机授权或 Git 历史。",[],retire=locked)


def _observe(heading,initial,locked,read,seconds=120):
    stopped,done = threading.Event(),threading.Event()
    result,display,status = None,False,initial
    expires = time.monotonic()+seconds
    def note(message):
        nonlocal status
        status=message
    def checkpoint():
        if stopped.is_set() or time.monotonic()>expires: raise PlatformError("Stopped local observation")
    def worker():
        nonlocal result
        try: result=read(checkpoint,note)
        except Exception: pass
        finally: done.set()
    def retire():
        nonlocal display
        if locked(): stopped.set(); return True
        if done.is_set(): display=True; return True
        return False
    thread=threading.Thread(target=worker,name="owner-selected-source-observation")
    thread.start()
    try: _dialog(heading,status,[],retire=retire,progress=lambda:status)
    finally: stopped.set(); thread.join()
    return display and not locked(),result


def _compare(ctx,op,locked,settings):
    display,result=_observe("核对副本现在的原文件","正在核实原操作、原清单与当前目录身份……",locked,
        lambda check,note:compare(ctx,op,check,lambda n,t:note(f"已核对 {n}/{t} 个原文件入口。关闭即可停止；不修改副本。")))
    if not display: return
    if not result:
        _dialog("没有完成这次核对","原回执不变。可能是原件、逐项清单或目录记录不可用，或本次读取已到上限。不会改查源仓库或其他目录。",[],retire=locked); return
    if result["allOriginal"]:
        _dialog("本次逐项核对与原件一致",f"{result['matched']} 个文件的原始身份、长度和 SHA-256 相符，没有发现新增入口。\n这是此次逐项观察，不是持续锁定或恢复了未提交工作。",[],retire=locked); return
    counts = f"原文件字节相符 {result['matched']}；字节不同 {result['different']}；文件身份已换 {result['replaced']}；未见原入口 {result['missing']}。"
    counts += f"\n不可读取 {result['unreadable']}，因父目录未绑定而未读 {result['unvisited']}；未绑定的原目录 {result['unboundDirectories']}，在原目录中另见的新入口 {result['newEntries']}。"
    if result["directoryChanged"]: counts += "\n读取期间目录仍在变化，不能宣称核对完整。"
    choices = [("inspect","选择一处修改与原提交对照")] if result["candidates"] and not result["directoryChanged"] else []
    if result["newParents"] and not result["directoryChanged"]: choices.append(("added","从原目录选一份新增源码"))
    if result["missingCandidates"] and not result["directoryChanged"]: choices.append(("missing","核实一个未见的原路径，准备移除补丁"))
    picked=_dialog("不能判作原保存时的完整副本",counts+"\n没有读取替换/新增文件的正文，没有同步、修复或删除。",choices,retire=locked)
    if locked(): return
    if picked=="inspect": _choose_change(ctx,settings,op,result,locked)
    elif picked=="added":
        from windows_added_source_entry import choose
        choose(ctx,settings,op,result,locked,_observe)
    elif picked=="missing":
        from windows_removed_source_entry import choose
        choose(ctx,settings,op,result,locked,_observe)


def _choose_change(ctx,settings,op,report,locked):
    candidates,page = report["candidates"],0
    while not locked():
        actions=[]
        for i,item in enumerate(candidates[page*4:page*4+4],page*4):
            name=item["path"]
            label=name if len(name)<=100 else "…"+name[-99:]
            kind="当前是替代文件，尚未读取正文" if item["state"]=="replacement" else "原文件字节已变化"
            actions.append((("file",i),label+"\n"+kind))
        if page: actions.append(("previous","上一页"))
        if (page+1)*4<len(candidates): actions.append(("next","下一页"))
        body="只列出原清单内、父目录仍可核对的普通文件；不会读取新目录或其他文件。"
        if report["comparable"]>len(candidates): body+=f"\n共有 {report['comparable']} 项；此窗口仅列原清单顺序的前 {len(candidates)} 项。"
        chosen=_dialog("要对照哪一处修改？",body,actions,retire=locked)
        if chosen=="next": page+=1; continue
        if chosen=="previous": page-=1; continue
        if chosen is None or locked(): return
        item=candidates[chosen[1]]
        warning=("当前物理文件与保存时的原件不同。只有你选定后才读取这个替代物作为对照，不会把它追认为历史原件。"
                 if item["state"]=="replacement" else "只比较这一个当前版本与原提交。")
        text=f"{item['path']}\n当前条目 {item['bytes']} 字节。{warning}小段文本可在本机显示，文件不会改写、执行或发送。"
        if _dialog("只读对照这个文件？",text,[("read","读取所选版本并对照")],retire=locked)!="read" or locked(): return
        visible,result=_observe("对照原提交与选定文件","正在重新绑定原副本、所选文件和留存提交……",locked,
            lambda checkpoint,_note:compare_selected(ctx,settings,op,item,checkpoint),30)
        if not visible: return
        if not result:
            _dialog("未取得此次对照","所选的当前文件、原父目录、清单或读取范围未能再核实。没有用同名替代物或最新提交补上。",[],retire=locked); return
        _show_change(ctx,settings,op,item,result,locked); return


def _show_change(ctx,settings,op,item,result,locked):
    description={"current_oversize":"当前选定文件超出 16 MiB 范围，只重新核对了身份和大小，未读取其正文。",
                 "same_bytes":"这次选定文件的原始字节与原保存摘要相符。",
                 "hash_only":"原始字节不同；不对二进制、非 UTF-8、过长或含控制字符的内容生成文本节选。",
                 "historical_unavailable":"当前字节与保存摘要不同。无法从同一已登记仓库重新取得原提交文件；不拿现在的分支或工作树做底稿。",
                 "text_lines_equal":"原始字节不同，但按 UTF-8 读取后的行文本相同，差异可能在换行或 BOM。",
                 "text":"已从原提交和原树路径取回真实 Git 正文，文本行有变化。"}
    text=f"{result['path']}\n原提交 {result['commit'][:12]}：{result['baseBytes']} 字节；当前：{result['currentBytes']} 字节。"
    if result["replaced"]: text+="\n当前一侧是你另行选定的替代文件，不是原件。"
    text+="\n"+description[result["status"]]
    actions=[("hash","查看原始字节摘要")]
    if result["status"]=="text": actions.insert(0,("excerpt","查看变更节选（只读）"))
    if result["status"] in ("text","text_lines_equal"):
        actions.append(("patch","准备完整单文件补丁\n再核实这两个版本；查看实际大小和新位置后才会保存。"))
    choice=_dialog("这次文件对照",text,actions,retire=locked)
    if locked(): return
    if choice=="patch": patches.prepare(ctx,settings,op,item,result,locked,_observe); return
    if choice=="hash":
        _dialog("原始字节 SHA-256","原保存："+result["baseSha"]+"\n当前选定："+result.get("currentSha","未计算"),[],retire=locked)
    if choice=="excerpt":
        lines,page=result["excerpt"].split("\n"),0
        while not locked():
            portion="\n".join(lines[page*9:page*9+9])
            actions=[]
            if page: actions.append(("previous","上一页"))
            if (page+1)*9<len(lines): actions.append(("next","下一页"))
            note="有行或内容被节选，不是完整补丁。" if result["clipped"] else "仅展示差异上下文，不是整个文件。"
            picked=_dialog(f"− 原提交 / + 当前 · {page+1}/{(len(lines)+8)//9}",portion+"\n\n"+note,actions,retire=locked)
            if picked=="next": page+=1
            elif picked=="previous": page-=1
            else: return


def browse(ctx,locked,settings=None):
    try: rows = slots(ctx)
    except (OSError,ValueError,TypeError,PlatformError):
        _dialog("不能核实本机副本回执","没有依据其他目录猜测成果，也未更改任何文件。",[],retire=locked); return
    if not rows:
        _dialog("尚无本机独立源码副本记录","只有机主明确选择保存后才登记。核对来源、目录或正文不会产生副本。",[],retire=locked); return
    rows.sort(key=lambda pair:pair[1]["at"],reverse=True)
    page = 0
    while not locked():
        choices=[]
        for n,r in rows[page*4:page*4+4]:
            source = r["source"]
            stamp = time.strftime("%m-%d %H:%M",time.localtime(r["at"]/1000))
            choices.append((("item",n),f"{source['environmentId']} · {source['commit'][:12]}\n{stamp} · 查看这次原制作回执"))
        if page: choices.append(("previous","上一页"))
        if (page+1)*4<len(rows): choices.append(("next","下一页"))
        chosen = _dialog("这台 Windows 的源码副本","包括未确认或中断的原操作。打开只核对回执与目录身份，不重做。",choices,retire=locked)
        if chosen=="next": page+=1; continue
        if chosen=="previous": page-=1; continue
        if chosen is None or locked(): return
        try: result = inspect(ctx,dict(rows)[chosen[1]])
        except (OSError,ValueError,TypeError,PlatformError):
            _dialog("此次原回执不能核对","不根据相同名字或其他目录推断副本。",[],retire=locked); return
        show_receipt(ctx,result,locked,settings); return


def confirm_copy(ctx,settings,source,locked):
    op,path = plan(ctx,source)
    try: rows = slots(ctx)
    except (OSError,ValueError,TypeError,PlatformError):
        _dialog("本机回执暂不可用","没有制作副本。",[],retire=locked); return
    for _,row in rows:
        if row["operation"]==op:
            try: result = inspect(ctx,row)
            except (OSError,ValueError,TypeError,PlatformError):
                _dialog("原回执不能核对","不会再制作一份或改用别的目录。",[],retire=locked); return
            show_receipt(ctx,result,locked,settings); return
    if len(rows)>=BANK:
        _dialog("本机已保留八次原制作记录","没有覆盖已有副本、清除收据或开始新制作。",[],retire=locked); return
    contents = source["committedContentReview"]
    try: space = disk(ctx,source_facts(source))
    except (OSError,ValueError,TypeError,PlatformError):
        _dialog("无法核对目标磁盘的可用空间","仅检查这台电脑固定的私有成果位置；没有开始制作。",[],retire=locked); return
    if not space["enough"]:
        _dialog("目标磁盘空间不足",f"当前用户可用 {space['available']/1048576:.1f} MiB，预计需要约 {space['estimate']/1048576:.1f} MiB（含余量）。未写入、清理或改用别处。",[],retire=locked); return
    text = (f"{source['environmentId']} · {source['commit'][:12]}\n{contents['files']} 个文件，{contents['logicalBytes']} 字节。"
            f"\n新建本机位置：{path}\n当前可用 {space['available']/1073741824:.1f} GiB，预计需约 {space['estimate']/1048576:.1f} MiB（含余量，不预留）。\n只保存原提交中的源码文件，以及供本机核对的路径、文件身份和摘要清单。没有 .git 历史、未提交改动、运行环境或手机权限。"
            "不会改源仓库、覆盖已有目录、执行代码或发送文件。")
    if _dialog("要保存这份独立源码副本吗？",text,[("save","确认新建这个本机副本")],retire=locked)!="save" or locked(): return
    stopped,done = threading.Event(),threading.Event()
    result,auto_finish = {},False
    expires = time.monotonic()+300
    status = "正在核对已登记的本机原件……"
    def checkpoint():
        if stopped.is_set() or time.monotonic()>expires: raise PlatformError("Local copy stopped or exceeded its time bound")
    def progress(n,total,size):
        nonlocal status
        status = f"已重新核实 {n}/{total} 个原文件（{size/1048576:.1f} MiB）。"
    def phase(value):
        nonlocal status
        status = value+"。"
    def work():
        try: result.update(save(ctx,settings,source,checkpoint,progress,phase,locked))
        except CopyBlocked as error: result["blocked"] = error.reason
        except Exception: pass  # Never display paths/CLI or raw worker exceptions from errors.
        finally: done.set()
    def retire():
        nonlocal auto_finish
        if locked(): stopped.set(); return True
        if done.is_set(): auto_finish=True; return True
        return False
    thread = threading.Thread(target=work,name="owner-confirmed-original-source-copy")
    thread.start()
    try:
        _dialog("制作本机独立源码副本",status,[],retire=retire,
                progress=lambda:status+"\n关闭可请求停止；已发布的副本不会被撤销。原回执不会自动重试。")
    finally: stopped.set(); thread.join()
    if not auto_finish or locked(): return
    if result.get("blocked"):
        reasons = {"source_changed":"原仓库、提交或完整正文未能按刚才的内容清单重核；未写副本。",
                   "bank_full":"八个原记录位置已满，未覆盖或开始新制作。",
                   "outside_limit":"这份完整源码超过现有本机独立落盘范围。",
                   "disk_space":"确认后的可用空间不足。没有开始、删文件或改用别处。",
                   "inventory_limit":"原文件清单超过本机可保留的核对范围。未写入没有原件证明的副本。",
                   "destination_present":"原定目标或暂存位置已有对象，未采用、覆盖或另找位置。"}
        _dialog("没有开始制作副本",reasons[result["blocked"]],[],retire=locked)
    elif result: show_receipt(ctx,result,locked,settings)
    else: _dialog("没有取得完整的制作回执","可到「本机独立副本与原回执」查看原操作；未自动重试，不据此断言目标已写入或未写入。",[],retire=locked)
