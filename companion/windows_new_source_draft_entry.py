"""Owner-native proposal for a new path. Only the private Git patch is published, on a separate explicit decision."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import ask_new_source_path, source_text
from desktop_sessions import WindowsDesktopHealth
from windows_git_source import registered
import windows_new_source_draft as model


def detail(ctx,op,row,locked,*,read_patch=None):
    f=row["fields"]
    try: state=model.check(ctx,op,row,locked)
    except Exception: state="unavailable"
    if locked(): return
    if state!="saved":
        _dialog("新增源码提议尚不能核实","有原保存意图但没有最终回执。" if state=="pending" else
                "原补丁文件、身份或字节现在不能核到。不会重新生成或认领同名文件。",[],retire=locked); return
    text=(f"{f['path']} · 原提交 {f['commit'][:12]}\n这是机主拟定的新增源码（{f['draftBytes']:,} 字节），不是已经在项目中创建了该文件。"
          f"\n已核补丁：{model.dest(ctx,op,f)}")
    actions=[("locate","在文件资源管理器中定位原补丁，不运行"),("ids","查看原父树、拟议内容和补丁摘要")]
    if read_patch is not None: actions.insert(0,("read","在伴侣中阅读完整原补丁"))
    pick=_dialog("已保存的新增源码提议",text,actions,retire=locked)
    if locked(): return
    if pick=="read" and read_patch is not None: read_patch()
    elif pick=="locate":
        try: model.check(ctx,op,row,locked,show=True)
        except Exception: _dialog("未定位原补丁","原件或专属输出目录不能再次核实，不改开父目录。",[],retire=locked)
    elif pick=="ids":
        _dialog("这份提议的版本",f"原提交：{f['commit']}\n当时确证无目标名的原父树：{f['parentTree']}\n拟议 Git blob：{f['draftOid']}"
                f"\n拟议正文 SHA-256：{f['draftSha']}\n原补丁 SHA-256：{f['patchSha']}",[],retire=locked)


def start(ctx,settings,op,base_locked,observe):
    health=WindowsDesktopHealth(); first=health.probe(session_only=True); session=first.get("sessionId")
    if not settings or first.get("available") is not True or type(session) is not int or session<=0 or base_locked(): return
    def current():
        try:
            live=health.probe(session_only=True); registered(ctx,settings)
            return not base_locked() and live.get("available") is True and live.get("sessionId")==session
        except Exception: return False
    def locked(): return not current()
    def run(title,note,fn):
        def read(check,_):
            def gate():
                check()
                if locked(): model.block()
            return fn(gate)
        return observe(title,note,locked,read,60)
    path=ask_new_source_path(current)
    if path is None or locked(): return
    visible,reviewed=run("核对拟用的新源码路径",path+"\n只核对原 Git 树和这个原副本父目录的名称及身份，不查看其他文件正文。",
        lambda check:model.scope(ctx,settings,op,path,check))
    if not visible: return
    if reviewed is None:
        _dialog("不能在这份底稿下提出该新路径","需采用已有原目录和支持的源码文件名。原父目录/仓库不明、同名或映射冲突、受保护路径、旧范围证据不足均不采用。未创建文件。",[],retire=locked); return
    proof,_=reviewed
    try: existing=model.records(ctx,op)
    except Exception:
        _dialog("没有打开临时稿","原保存记录不可核实，无法确认还有空位，不让你写到最后才发现不能保存。",[],retire=locked); return
    if len(existing)>=model.BANK:
        _dialog("这个底稿的新增提议已满","已经有四份独立原记录。不会挤掉旧物；从这份底稿的「已另存的单文件变更」可查原提议。",[],retire=locked); return
    if _dialog("为这个新路径起草？",f"{path}\n原提交 {proof['commit'][:12]} 的父树没有此项；刚才核对的原副本父目录也未见该名称。"
               "不检查源仓库当前未提交的工作树。只写临时稿，本窗口不会创建项目文件。",
               [("edit","从空白临时稿开始")],retire=locked)!="edit" or locked(): return
    display=""
    while current():
        display=source_text(current,"拟新增 · "+path[-65:],"只在内存起草，UTF-8 无 BOM、LF、普通非执行文件。预览后另行决定是否保存新增补丁。",display)
        if display is None or locked(): return
        try: f,raw=model.proposal(reviewed,display)
        except Exception:
            if _dialog("这份新稿还不能生成补丁","只支持完整的 ≤32 KiB 普通 UTF-8 源码；不截断或写项目。",
                       [("edit","返回当前临时稿")],retire=locked)=="edit": continue
            return
        patch=raw.decode("utf-8").replace("\n","\r\n")
        while current():
            if source_text(current,"完整新增差异 · "+path[-65:],"只读拟议补丁；减侧是 /dev/null。这不是已创建的源文件。",patch,review=True) is None or locked(): return
            body=(f"{path} · 原提交 {proof['commit'][:12]}\n拟议正文 {f['draftBytes']:,} 字节；完整补丁 {f['bytes']:,} 字节。"
                  f"\n只在以下新的本机位置保存 change.patch：\n{model.dest(ctx,op,f)}\n不在仓库或副本中新建任何文件，不覆盖、不应用、执行或发送。")
            pick=_dialog("只另存这份新增源码提议？",body,[("save","明确提议空文件，只另存补丁" if f["draftBytes"]==0 else "确认只另存新增补丁"),
                        ("edit","回到刚才的临时稿"),("again","再读完整差异")],retire=locked)
            if pick=="again": continue
            if pick=="edit": break
            if pick!="save" or locked(): return
            visible,row=run("核对并另存新增源码补丁","重新核对原 Git、父目录、拟用名和完整草稿；不会写到拟用的项目路径。",
                            lambda check:model.save(ctx,settings,op,reviewed,display,(f,raw),check))
            if not visible: return
            if row is None:
                while current():
                    act=_dialog("未确认新增补丁已保存","原范围、拟用名、名额或最终保存证据不足。临时稿还在这个窗口；不会覆盖或自动重试。",
                                [("text","只读查看刚才的完整临时稿"),("check","只核对这次原保存记录")],retire=locked)
                    if act is None or locked(): return
                    if act=="text":
                        source_text(current,"尚未确认保存 · "+path[-65:],"这是刚才的临时文字，不是已保存的文件。",display,review=True); continue
                    try: prior=next((r for r in model.records(ctx,op).values() if r["id"]==model.key(f) and r["fields"]==f),None)
                    except Exception: prior=None
                    if prior is not None: detail(ctx,op,prior,locked)
                    else: _dialog("没有核到这次原保存记录","未采用同名文件，也没有重新派发保存。",[],retire=locked)
                return
            detail(ctx,op,row,locked); return
