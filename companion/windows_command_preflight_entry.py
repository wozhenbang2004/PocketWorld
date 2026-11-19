"""Human, local-only command setup view. It cannot register a command, dispatch it or give a peer permission."""
from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from windows_git_copy_entry import _observe
import windows_command_preflight as model

EXPLAIN={
    "not_enabled":"这个工作区保存的 workers 没有 command。这里不会启用执行器。",
    "empty":"允许命令执行器，但这个工作区的命令白名单为空。首次初始化就是这个状态；配对或启动桥不会添加命令。",
    "no_short_names":"有命令配置，但没有本窗口支持的短名称。未猜测、代换或尝试执行。",
    "invalid":"命令或执行器登记不符合原目录采用的格式，不能显示为准备好。",
    "over_limit":"命令登记项超过本地核对上限（128），没有把前一部分当作全部可用。"}
DETAIL={
    "needs_path":"这个名称需由原服务在它自己的 PATH 中解析。本窗口不会搜索 PATH 或试运行。",
    "unsupported":"这个映射不是可在此核对的本机绝对 .exe 路径；不展开参数、不访问网络位置或安装私有目录。",
    "not_exe":"配置指向的不是 .exe。Windows 原生命令执行器不以 shell 或批处理替代它。"}
RESULT={
    "workspace_unknown":"未核实这一个工作目录，没有继续观察程序。可能被替换、不是本机固定磁盘、不可读或不符合安全范围。",
    "program_unknown":"只核到了登记的本机工作目录，未核到所选的同一路径为单链接普通 .exe。不能据此断定程序未安装。",
    **{k:"已核到这一个本机工作目录。"+v for k,v in DETAIL.items()}}


def choose(title,note,items,locked):
    page=0
    while not locked():
        actions=[(("item",k),v) for k,v in items[page*6:(page+1)*6]]
        if page: actions.append(("prev","上一页"))
        if (page+1)*6<len(items): actions.append(("next","下一页"))
        choice=_dialog(title,note,actions,retire=locked)
        if choice=="prev": page-=1; continue
        if choice=="next": page+=1; continue
        return choice[1] if isinstance(choice,tuple) and choice[0]=="item" and not locked() else None


def open_setup(ctx,settings):
    health=WindowsDesktopHealth(); initial=health.probe(session_only=True); session=initial.get("sessionId")
    if not settings or initial.get("available") is not True or type(session) is not int or session<=0: return
    def locked():
        try:
            live=health.probe(session_only=True); model.registered(ctx,settings)
            return live.get("available") is not True or live.get("sessionId")!=session
        except Exception: return True
    while not locked():
        try: envs=model.catalog(ctx,settings)
        except Exception:
            _dialog("无法核对本机命令准备","当前安装指针或原配置无法核对；没有改读其他配置或启动服务。",[],retire=locked); return
        if not envs:
            _dialog("没有可在这里核对的本机工作区","只接受本安装登记的安全本地工作区，不把 SSH 或桌面连接当作 Windows 命令环境。",[],retire=locked); return
        eid=choose("哪个本机工作区要接命令？","只看当前安装已保存的配置，不联系手机、不询问正在运行的服务，也不改变授权。",
                   [(key,key) for key in sorted(envs)],locked)
        if eid is None: return
        root,env=envs[eid]; state,names,hidden=model.inventory(env)
        if state in EXPLAIN:
            _dialog("本机命令准备 · "+eid,EXPLAIN[state]+"\n这只说明命令设置，不代表桌面观看、文件读取或配对状态。",[],retire=locked); continue
        note=f"{eid} · {len(names)} 个可展示的已登记名称。先选一项，下一步才决定是否核对本机文件；没有给手机发送名单。"
        if hidden: note+=f" 另有 {hidden} 个名称不符合本页显示规则，未被当作不存在。"
        while not locked():
            name=choose("选择一个已登记的命令名",note,[(n,n) for n in names],locked)
            if name is None: break
            try: selected=model.select(ctx,settings,eid,name)
            except Exception:
                _dialog("原命令登记不可核","没有改查同名项目或其他工作区。",[],retire=locked); return
            file=selected.program
            description=f"工作区登记：{eid}\n指定的工作目录：{root}\n白名单名称：{name}\n"
            description+=f"已登记程序路径：{file}\n只核对这一目录和这一个文件的原生元数据；不读程序内容或签名。" if file else DETAIL[selected.kind]
            if _dialog("核对这一项本机准备？",description,[("check","只核对所选目录和程序文件" if file else "只核对所选工作目录")],retire=locked)!="check": continue
            def observe(check,_):
                def gate():
                    check()
                    if locked(): model.bad()
                return model.inspect(ctx,settings,selected,gate)
            visible,result=_observe("正在核对这一个本机命令登记","只通过固定磁盘上的原生句柄观察，不启动程序或连接服务。",locked,observe,30)
            if not visible or locked(): return
            if result is None: title,body="这次检查没有完整结果","登记、桌面会话或原件未能再次核实。"
            elif result[0]=="file_observed":
                title,body="核到了已登记的 .exe 文件",f"工作目录和这个普通文件在本次核对时均可识别。文件 {result[1]:,} 字节；没读其内容、验证签名或试运行。"
            else: title,body="这一项本机准备仍有限制",RESULT[result[0]]
            _dialog(title,f"{eid} · {name}\n{body}\n不证明运行服务采用了此配置、手机获准、程序可正常启动或任务执行成功。",[],retire=locked)
