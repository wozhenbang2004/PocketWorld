"""Owner-native single-path commit history. No commands, checkout, network, working-tree file access or saved data."""
from desktop_owner_entry import _dialog
from desktop_phone_goal_text import source_text
import windows_git_parent_file as model

PATH_STATE={
    "absent":"所选父提交的树中没有这个准确路径。未搜索其他名称，不据此断定作者新建或重命名了文件。",
    "ancestor_not_directory":"所选父提交的一个父路径是非目录项；没有穿过它、跟随链接或切换到别的仓库。",
    "directory":"这个准确位置当时是目录，不是普通文件；未展开它。", "symlink":"这个位置当时是符号链接；未读取或跟随链接目标。",
    "submodule":"这个位置当时是子模块记录；未读取或联系子仓库。"}


def show_parent(ctx,settings,src,file,current,observe):
    def locked(): return not current()
    def run(title,note,op):
        def read(check,_):
            def gate():
                check()
                if locked(): model.bad()
            return op(gate)
        return observe(title,note,locked,read,40)
    visible,ids=run("核对原提交的直接父节点",file.path+"\n只看已选提交对象的直接父编号，不展示提交说明，也不读父文件正文。",
                    lambda check:model.parents(ctx,settings,src,file,check))
    if not visible: return
    if ids is None:
        _dialog("未取得明确的直接父提交","原仓库、提交、父编号格式或上限（8 个）无法核实。没有借用当前分支历史。",[],retire=locked); return
    if not ids:
        _dialog("这个提交没有直接父提交","已核原提交对象中的父记录为空，没有把空文件当作上一版本。",[],retire=locked); return
    page=0
    while current():
        choices=[(("parent",i),f"与第 {i+1} 个父提交对照 · {oid[:16]}") for i,oid in enumerate(ids[page*4:(page+1)*4],page*4)]
        if page: choices.append(("prev","上一页"))
        if (page+1)*4<len(ids): choices.append(("next","下一页"))
        pick=_dialog("与哪个直接父提交对照？",f"{file.path}\n原提交 {src['commit']} 有 {len(ids)} 个直接父提交。"
                     "只核对选中父提交的这一路径和对象号；不查旁支、重命名或旧文件正文。",choices,retire=locked)
        if pick=="prev": page-=1; continue
        if pick=="next": page+=1; continue
        if not isinstance(pick,tuple) or pick[0]!="parent" or locked(): return
        index=pick[1]
        visible,entry=run("核对父提交中的同一路径","只从同一个本机对象库核对父提交、逐级原树和这一项目；缺对象不联网获取。",
                          lambda check:model.inspect(ctx,settings,src,file,ids,index,check))
        if not visible: return
        if entry is None:
            _dialog("父提交的这一路径未能核实","可能缺少本地对象、树记录不完整或名称冲突。没有把读取失败当作不存在，也不搜工作树。",[],retire=locked)
            if len(ids)>1: continue
            return
        label=f"{file.path}\n第 {index+1} 个父提交 {entry.parent[:16]} → 原选提交 {src['commit'][:16]}。"
        if entry.state!="file":
            _dialog("所选父版本没有可对照的普通源码",label+"\n"+PATH_STATE[entry.state],[],retire=locked)
            if len(ids)>1: continue
            return
        modes=f"Git 文件模式：{entry.mode} → {file.mode}。"
        if entry.oid==file.oid:
            _dialog("两份树项指向同一 Git 文件内容",label+f"\n两端 blob：{file.oid}\n"+modes+" 即使有模式改动，也不是正文被改。",[],retire=locked)
            if len(ids)>1: continue
            return
        ask=_dialog("读取两个版本的完整文本作对照？",label+f"\n{modes}\n父 blob：{entry.oid}\n原 blob：{file.oid}\n"
                    "仅各读这一份普通 UTF-8 源码（每份 ≤64 KiB）。不解析代码、执行、保存补丁或发送。",
                    [("read","读取这两份原文本并对照")],retire=locked)
        if ask!="read" or locked(): return
        visible,data=run("比对两个明确提交的原文件","重新核对父关系、路径与 Git 内容身份，不取工作树、缓存节选或其他文件替代。",
                         lambda check:model.compare(ctx,settings,src,file,ids,index,entry,check))
        if not visible: return
        if data is None:
            _dialog("没有生成完整文本对照","缺对象、二进制、LFS 指针、超限、权限或来源变化都可能阻止读取。不能据此说没有改动。",[],retire=locked); return
        diff=data["diff"]
        reason=("原始字节完全相同。" if data["same"] else "原始字节不同；统一 BOM 和换行后的正文相同。" if diff is None else "两个固定版本的正文有差异。")
        while current():
            actions=([("diff","阅读完整行差异")] if diff is not None else [])+[("old","阅读所选父提交的原文本"),("new","阅读原选提交的文本"),("ids","核对两个提交与原始字节摘要")]
            if len(ids)>1: actions.append(("others","另选这个提交的直接父节点"))
            action=_dialog("相同路径的两个已提交版本",label+"\n"+reason+f"\n{data['oldBytes']:,} → {file.size:,} 原字节。{modes}"
                           f"\n父：{data['oldFormat']}；原选：{data['newFormat']}。不涉及当前工作树。",actions,retire=locked)
            if action=="others": break
            if action is None or locked(): return
            if action=="ids":
                _dialog("这两份提交与字节的身份",f"父提交：{entry.parent}\n父 blob：{entry.oid}\n父文件 SHA-256：{data['oldSha']}\n"
                        f"原选提交：{src['commit']}\n原选 blob：{file.oid}\n原选文件 SHA-256：{file.sha}",[],retire=locked); continue
            content=diff if action=="diff" else data["oldText"] if action=="old" else file.text
            title={"diff":"父版本 → 原选版本","old":"父提交原文件","new":"原选提交文件"}[action]
            if source_text(current,title+" · "+file.path[-54:],"只读已核的原文本；行差异统一 BOM 和换行，字节摘要对应未改原文。",content,review=True) is None: return
