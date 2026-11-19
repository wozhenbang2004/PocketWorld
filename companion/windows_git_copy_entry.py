"""Machine-owner local artifact UI. No remote endpoints or project worker."""
import threading
import time

from desktop_owner_entry import _dialog
from platform_backend import PlatformError
from windows_git_copy import BANK, CopyBlocked, inspect, plan, save, slots


def show_receipt(ctx,evidence,locked):
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
    if _dialog(title,message,[("identity","查看原操作和内容编号")],retire=locked)=="identity" and not locked():
        _dialog("原副本编号",f"操作：{evidence['operation']}\n原提交：{facts['commit']}\n原始内容清单：{facts['content']}\n不含执行权、手机授权或 Git 历史。",[],retire=locked)


def browse(ctx,locked):
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
        show_receipt(ctx,result,locked); return


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
            show_receipt(ctx,result,locked); return
    if len(rows)>=BANK:
        _dialog("本机已保留八次原制作记录","没有覆盖已有副本、清除收据或开始新制作。",[],retire=locked); return
    contents = source["committedContentReview"]
    text = (f"{source['environmentId']} · {source['commit'][:12]}\n{contents['files']} 个文件，{contents['logicalBytes']} 字节。"
            f"\n新建本机位置：{path}\n只保存原提交中的源码文件。没有 .git 历史、未提交改动、运行环境或手机权限。"
            "不会改源仓库、覆盖已有目录、执行代码或发送文件。")
    if _dialog("要保存这份独立源码副本吗？",text,[("save","确认新建这个本机副本")],retire=locked)!="save" or locked(): return
    stopped,done = threading.Event(),threading.Event()
    result,auto_finish = {},False
    expires = time.monotonic()+180
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
                   "destination_present":"原定目标或暂存位置已有对象，未采用、覆盖或另找位置。"}
        _dialog("没有开始制作副本",reasons[result["blocked"]],[],retire=locked)
    elif result: show_receipt(ctx,result,locked)
    else: _dialog("没有取得完整的制作回执","可到「本机独立副本与原回执」查看原操作；未自动重试，不据此断言目标已写入或未写入。",[],retire=locked)
