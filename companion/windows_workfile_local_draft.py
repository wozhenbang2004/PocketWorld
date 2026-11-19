"""Take one explicitly reviewed local workfile into the existing human draft editor, never a device command."""
from contextlib import contextmanager

from desktop_owner_entry import _dialog
import windows_git_copy as copies
from windows_git_one_copy import descriptor
from windows_git_workfile import _content, same_observation
import windows_owner_draft as draft


def start(ctx,settings,src,file,live,observed,current,observe):
    if not current() or observed["diff"] is None or max(file.size,live.version[0])>draft.MAX_TEXT: return
    def locked(): return not current()
    source={**src,"selectedFile":descriptor(file)}
    try:
        op,_=copies.plan(ctx,source)
        matches=[r for _,r in copies.slots(ctx,one=True) if r["operation"]==op]
        if len(matches)>1: draft.block()
        row=matches[0] if matches else None
        state=copies.inspect(ctx,row) if row is not None else None
        if row is not None and row["source"]!=copies.source_facts(source): draft.block()
    except Exception:
        _dialog("不能核对这次原底稿","未能确定是否已有这份原提交的单文件副本；不转用其他底稿或新建。",[],retire=locked); return
    if locked(): return
    if state is None:
        pick=_dialog("先保留该文件的原提交版本",f"{file.path} · {src['commit'][:12]}\n还没有与本次选择完全一致的单文件底稿。"
                     "可以进入原保存流程，只保存这一个 Git 原件，不含工作文件或未提交内容。完成后回到本次对照，再决定是否起草。",
                     [("copy","去核对原提交文件的独立副本与新位置")],retire=locked)
        if pick=="copy" and current():
            from windows_git_copy_entry import confirm_copy
            confirm_copy(ctx,settings,source,locked)
        return
    if state["status"]!="saved" or not state.get("itemsRecorded"):
        _dialog("没有可用于起草的原底稿","已有这次原制作记录，但不足以核实其留存文件。不重做、不选同名副本。",[],retire=locked); return
    @contextmanager
    def hold(check):
        def gate():
            check()
            if locked(): draft.block()
        with same_observation(ctx,settings,src,file,live,observed["currentSha"],gate) as raw:
            # Size and SHA come from the original byte comparison. The full display must also be
            # reproducible from this exact version, not a copy/paste, clipped diff or replacement.
            text,fmt=_content(raw)
            if text.replace("\n","\r\n")!=observed["current"] or fmt!=observed["newFormat"]: draft.block()
            gate(); yield raw
    def prepare(check,_):
        with hold(check) as raw:
            def gate():
                check()
                if locked(): draft.block()
            base=draft.prepare(ctx,settings,op,file.path,gate)
            if base.oid!=file.oid or base.commit!=src["commit"] or draft.digest(base.raw)!=file.sha: draft.block()
            display=observed["current"]; candidate=draft.encode(base,display)
            if candidate==base.raw: draft.block()
            return base,display,raw==candidate,draft.digest(candidate),_content(candidate)[1]
    visible,ready=observe("核对工作文件和原副本底稿",file.path+"\n核对刚才读到的全部字节与同一原提交，不修改文件。",locked,prepare,45)
    if not visible: return
    if ready is None:
        _dialog("没有以这份工作文件起草","原工作文件可能已变，或原底稿与完整可编辑文本不能同时核实。不会把后来出现的版本代入。",[],retire=locked); return
    base,display,exact,sha,convention=ready
    details=(f"{file.path} · 原提交 {src['commit'][:12]}\n工作文件：{observed['newFormat']}\n原提交：{observed['oldFormat']}\n"
             +("草稿起点逐字节等于刚才的工作文件。" if exact else
               "草稿起点实际格式："+convention+"。起始字节与工作文件不同，原提交未含换行时新行使用 LF。\n工作文件 SHA-256："+observed["currentSha"]+"\n起草 SHA-256："+sha)
             +"\n只打开临时编辑框。最后还要查看完整差异并另行确认；原工作文件若换版，不会自动采用新版本。")
    pick=_dialog("用这份工作文本作为起点？",details,[("edit","按已核字节开始起草" if exact else "接受原提交的字节格式，开始起草")],retire=locked)
    if pick=="edit" and current():
        from windows_owner_draft_entry import edit
        edit(ctx,settings,op,base,locked,observe,work_text=display,work_scope=hold)
