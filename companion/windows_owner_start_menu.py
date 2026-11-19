"""Only the signed install's unlocked local owner may opt into one ordinary current-user Programs shortcut."""
from contextlib import ExitStack,contextmanager
from dataclasses import dataclass
import hashlib
import ntpath
import re
import uuid

from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from platform_windows import WindowsHandleReader,local_root
from windows_owner_start_link import MAX_LINK,bad,bytes_for_desk,location
from windows_source_publisher import _origin

NAME="PocketWorld 工作台.lnk"
MANAGE="Manage-Companion.ps1"
POSH="powershell.exe"
NO_RECALL=0x441400


@dataclass(frozen=True)
class Target:
    menu: str
    parent: tuple
    powershell: str
    executable_birth: tuple
    working: str
    manage: str
    install: str
    signer: str


def same(a,b): return ntpath.normcase(a)==ntpath.normcase(b)


def supported(ctx):
    return (ctx.current.get("allowUnsignedDevelopment") is False and bool(re.fullmatch(r"[0-9a-f]{32}",ctx.install_state.get("installId","")))
            and isinstance(ctx.current.get("signerThumbprint"),str) and bool(re.fullmatch(r"[0-9a-fA-F]{40}",ctx.current["signerThumbprint"])))


@contextmanager
def target(ctx,check):
    check()
    if not supported(ctx): bad()
    api=ctx.backend.api; menu,local,system=map(local_root,location())
    if not same(ctx.home,ntpath.join(local,"PocketWorld","Companion")) or not same(ntpath.splitdrive(ctx.state)[0],ntpath.splitdrive(menu)[0]): bad()
    binary=ntpath.join(system,"WindowsPowerShell","v1.0",POSH); manage=ntpath.join(ctx.home,MANAGE)
    if any(x in binary+manage for x in ('"','%','\r','\n')): bad()
    with ExitStack() as held:
        _,state,_=held.enter_context(ctx.backend.directory(ctx.state)); api.private_access(state,ctx.sid,require_protected=True)
        _,parent,_=held.enter_context(ctx.backend.directory(menu))
        pinfo=api.info(parent); birth=tuple(_origin(api,pinfo))
        if pinfo.attributes&NO_RECALL: bad()
        fn=ntpath.dirname(binary); held.enter_context(ctx.backend.directory(fn))
        h=api.open(binary); held.callback(api.k.CloseHandle,h)
        native=api.info(h)
        if native.attributes&(NO_RECALL|0x10) or not same(api.final(h),"\\\\?\\"+binary): bad()
        # Keep the exact manifest-pinned stable entry AND its helper/launcher through publication.
        for name in (MANAGE,"Launch-Companion.ps1","Companion-InstallState.ps1"):
            expected=ctx.owner_entry_files.get("tools/"+name,{})
            reader=held.enter_context(ctx.backend.open_reader(ctx.home,name,1048576))
            digest=hashlib.sha256()
            for block in iter(lambda:reader.read(262144),b""): digest.update(block)
            if reader.size!=expected.get("bytes") or digest.hexdigest()!=str(expected.get("sha256","")).lower() or not reader.verify_unchanged(): bad()
        proof=Target(menu,birth,binary,tuple(_origin(api,native)),system,manage,ctx.install_state["installId"],ctx.current["signerThumbprint"].upper())
        check()
        yield proof,parent


def absent(api,dest):
    try: h=api.open(dest)
    except OSError as e:
        if getattr(e,"winerror",None)==2: return True
        raise
    api.k.CloseHandle(h); return False  # An occupied name is never parsed, resolved or adopted.


def preview(ctx,check):
    with target(ctx,check) as (p,_): return p,absent(ctx.backend.api,ntpath.join(p.menu,NAME))


def publish(ctx,proof,check):
    api=ctx.backend.api
    with target(ctx,check) as (p,parent):
        if p!=proof: bad()
        dest=ntpath.join(p.menu,NAME)
        if not absent(api,dest): bad()
        # -File is one literal script argument, not -Command. No profile, execution-policy switch,
        # hidden window, network argument, environment expansion, arbitrary executable or service action.
        args=f'-NoLogo -NoProfile -File "{p.manage}" -Action OpenDesk -OwnerDeskInstallId {p.install} -OwnerDeskSigner {p.signer}'
        raw=bytes_for_desk(p.powershell,args,p.working); check()
        if not 76<=len(raw)<=MAX_LINK: bad()
        stage=ntpath.join(ctx.state,".pocketworld-owner-menu-"+uuid.uuid4().hex+".tmp")
        h=api.open(stage,create=True,write=True,delete=True); attempted=False
        try:
            api.write(h,raw); info=api.info(h); born=tuple(_origin(api,info))
            if not same(api.final(h),"\\\\?\\"+stage) or info.links!=1: bad()
            check(); attempted=True
            # Native handle-relative rename across directories on the same volume; destination is absent-only.
            api.publish_handle(h,parent,NAME)
            if not same(api.final(h),"\\\\?\\"+dest): bad()
        finally:
            try:
                if not attempted: api.disposition(h)
            finally: api.k.CloseHandle(h)
        out=api.open(dest)
        try:
            if not same(api.final(out),"\\\\?\\"+dest) or tuple(_origin(api,api.info(out)))!=born: bad()
            reader=WindowsHandleReader(api,out,MAX_LINK)
            if reader.size!=len(raw) or reader.read(MAX_LINK)!=raw or not reader.verify_unchanged(): bad()
        finally: api.k.CloseHandle(out)
        check()
        return True


def offer(ctx):
    from windows_git_copy_entry import _observe
    health=WindowsDesktopHealth(); original=health.probe(session_only=True); sid=original.get("sessionId")
    if original.get("available") is not True or type(sid) is not int or sid<=0 or not supported(ctx): return
    def locked():
        try:
            now=health.probe(session_only=True); ctx.check_current_pointer()
            return now.get("available") is not True or now.get("sessionId")!=sid
        except Exception: return True
    def run(title,note,fn):
        def work(check,_):
            def gate():
                check()
                if locked(): bad()
            return fn(gate)
        return _observe(title,note,locked,work,40)
    visible,choice=run("核对这个用户的开始菜单","只核对固定程序目录和本机签名安装入口，不搜索其他快捷方式。",lambda check:preview(ctx,check))
    if not visible: return
    if choice is None:
        _dialog("不能放入这个开始菜单","仅支持当前用户实际位于本机标准位置的程序菜单。重定向、原入口或目录身份不明时不改用桌面、启动文件夹或公共菜单。",[],retire=locked); return
    p,empty=choice
    if not empty:
        _dialog("开始菜单中已经有同名项",ntpath.join(p.menu,NAME)+"\n没有打开、解析或覆盖它，也未认定它是本应用创建的。",[],retire=locked); return
    if _dialog("以后从开始菜单打开工作台？",ntpath.join(p.menu,NAME)+
        "\n只创建当前用户的普通快捷方式。点击时核对同一签名安装，只打开原工作台；不会随登录运行、启动服务、配对或固定到任务栏。可日后自己从开始菜单移除。",
        [("save","创建「PocketWorld 工作台」快捷方式")],retire=locked)!="save" or locked(): return
    visible,done=run("创建所选的工作台入口","再次核对原安装和目录，不覆盖；回读的真实快捷方式与刚生成的内容一致才确认。",
                     lambda check:publish(ctx,p,check))
    if not visible: return
    if done:
        _dialog("已创建「PocketWorld 工作台」","可在开始菜单按应用名找到它。搜索索引何时出现由 Windows 决定；刚才没有启动程序或服务。",[],retire=locked)
    else:
        _dialog("未能确认工作台快捷方式已创建","原目录、签名安装或落盘回读可能已变化。可在开始菜单核对；没有覆盖、删除同名项或自动重试。",[],retire=locked)
