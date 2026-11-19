"""Native owner-workbench entry only. Explicit preview, second decision, then a distinct text artifact."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import source_text
from desktop_sessions import WindowsDesktopHealth
from windows_git_copy_entry import _observe
import windows_owner_draft as draft
import windows_owner_draft_text as output


def open_text(ctx,settings,op,row,locked):
    health=WindowsDesktopHealth(); first=health.probe(session_only=True); session=first.get("sessionId")
    if not settings or first.get("available") is not True or type(session) is not int or session<=0: return
    def retired():
        if locked(): return True
        try:
            live=health.probe(session_only=True); draft.registered(ctx,settings)
            return live.get("available") is not True or live.get("sessionId")!=session
        except Exception: return True
    def inspect(action,title):
        def read(check,_):
            def stale():
                check(); return retired()
            return output.inspect(ctx,op,row,stale,action=action)
        return _observe(title,"只核对这个草稿的原补丁、专属文本位置和原留存；没有写入。",retired,read,30)
    visible,result=inspect("state","核对这份完整草稿")
    if not visible: return
    if result is None or result[0]=="pending":
        note=("有原另存请求，但没有足够证据确认文件已保存。" if result else "原补丁、专属位置或留存文件不能共同核实。")
        _dialog("不能确认这份完整文本",note+" 不会取同名文件或重新生成。",[],retire=retired); return
    f=row["fields"]; path=output.destination(ctx,row); source=f"{f['path']} · 原提交 {f['commit'][:12]}"
    if result[0]=="none":
        visible,raw=_observe("还原完整草稿以供核对",source+"\n只用原提交、未改换的原副本和真实补丁。不修改项目。",
            retired,lambda check,_:output.prepare(ctx,settings,op,row,retired,check),45)
        if not visible: return
        if raw is None:
            _dialog("没有另存完整草稿","还不能从原 Git 文件、同一副本和完整补丁核到全部目标字节。不会用片段、现文件或空白稿代替。",[],retire=retired); return
        title="核对完整草稿 · "+f["path"][-65:]
        while not retired():
            if source_text(lambda:not retired(),title,"只读全文。返回后再决定是否另存；这里不会写入。",draft.unpack(raw)[2],review=True) is None or retired(): return
            pick=_dialog("另存这份完整草稿？",source+f"\n这 {len(raw):,} 字节将原样保留在：\n{path}"
                         "\n只创建一份新的本机文本，不覆盖。原补丁仍独立保留；不动仓库或副本，不执行、应用或发送。",
                         [("save","确认仅另存这份 draft.txt"),("preview","再看完整草稿")],retire=retired)
            if pick=="preview": continue
            if pick!="save" or retired(): return
            visible,status=_observe("另存完整草稿文本","重新核对刚才看过的全部字节和两份原件；只在新的本机私有位置保存。",
                retired,lambda check,_:output.save(ctx,settings,op,row,raw,retired,check),60)
            if not visible: return
            if status!="saved":
                _dialog("未确认完整文本已保存","请回到这个原草稿再核对。可能留有原请求；不会覆盖或自动重试。",[],retire=retired); return
            break
    if retired(): return
    while not retired():
        pick=_dialog("已另存的完整草稿",source+f"\n{f['draftBytes']:,} 字节 · {path}"
                     "\n这是上次另存的版本，不代表当前仓库内容、已运行或已经交付。",
                     [("content","在伴侣中阅读已留存的全文"),("locate","在文件资源管理器中定位原 draft.txt\n不启动文件关联程序。")],retire=retired)
        if pick not in ("content","locate") or retired(): return
        visible,result=inspect(pick,"重新核对要读取的原文本" if pick=="content" else "定位刚才选中的原文本")
        if not visible: return
        if result is None or result[0]!="saved":
            _dialog("未打开已留存文本","原目录、文件身份或实际字节不能再次核实。不改开其他文件或上一级目录。",[],retire=retired); return
        if pick=="locate": return
        if source_text(lambda:not retired(),"已留存的完整文本 · "+f["path"][-65:],"只读原 draft.txt，不是重新还原，也不是当前仓库。",
                       draft.unpack(result[1])[2],review=True) is None: return
