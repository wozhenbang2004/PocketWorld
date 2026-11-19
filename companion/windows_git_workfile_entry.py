"""Native owner-only comparison. Checking location is not consent to read the worktree contents."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import source_text
import windows_git_workfile as work


def compare(ctx,settings,src,file,current,observe):
    def locked(): return not current()
    def run(label,message,fn):
        def read(check,_):
            def gate():
                check()
                if locked(): work.bad()
            return fn(gate)
        return observe(label,message,locked,read,45)
    visible,live=run("核对工作文件的位置",file.path+"\n只核对原仓库和同一路径的本机文件身份、长度，不读取工作文件正文。",
                     lambda check:work.plan(ctx,settings,src,file,check))
    if not visible: return
    if live is None:
        _dialog("没有可对照的本机工作文件","没有找到可核实的同一路径小文件，也可能是文件超限或正在使用。不会把找不到说成删除，不改读别的路径。",[],retire=locked); return
    request=(f"原提交 {src['commit'][:12]}：{file.path}（{file.size:,} 字节）\n"
             f"本机工作文件：{live.path}（{live.version[0]:,} 字节）\n只在这台电脑读取和对照。未保存到磁盘的编辑器内容不在其中。"
             "不扫描、运行 Git、上传或修改文件。")
    if _dialog("读取这个工作文件来对照？",request,[("read","只读取这份工作文件并比较")],retire=locked)!="read" or locked(): return
    visible,result=run("对照两份实际字节",file.path+"\n重新核对原提交和刚才选中的工作文件，不从其他来源替补。",
                       lambda check:work.compare(ctx,settings,src,file,live,check))
    if not visible: return
    if result is None:
        _dialog("这次没有取得可用对照","文件身份、版本、范围或受支持文本无法重新核实。没有保存结果或自动重试。",[],retire=locked); return
    same=result["same"]; diff=result["diff"]
    label="两个版本逐字节相同" if same else "文字相同，字节格式不同" if diff is None else "两个版本的文本不同"
    body=(f"{file.path}\n原提交 {src['commit'][:12]} · {result['oldFormat']}\n本机工作文件 · {result['newFormat']}"
          "\n仅指刚才读到的文件。不读取暂存区，也不推断 Git 状态。")
    while current():
        choices=[("text","阅读这次实际读到的工作文件"),("ids","查看两份字节的 SHA-256")]
        if diff is not None: choices.insert(0,("diff","查看完整行差异\n换行和 BOM 按文字显示；原字节编号另列。"))
        pick=_dialog(label,body,choices,retire=locked)
        if not current() or pick is None: return
        if pick=="ids":
            _dialog("这次字节对照",f"原提交：{file.sha}\n工作文件：{result['currentSha']}\n不对应整个仓库或当前暂存区。",[],retire=locked); continue
        display=diff if pick=="diff" else result["current"]
        if source_text(current,("原提交与工作文件的行差异 · " if pick=="diff" else "本机工作文件 · ")+file.path[-60:],
                       "只读当时核对的文本，不是实时跟踪，不会写回或生成补丁。",display,review=True) is None: return
