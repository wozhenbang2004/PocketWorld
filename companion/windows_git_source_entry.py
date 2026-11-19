"""Local-only project source review; no replica, service, install or authorization."""
import time
import unicodedata

from desktop_owner_entry import _dialog
from desktop_sessions import WindowsDesktopHealth
from platform_backend import PlatformError
from windows_git_source import Incomplete, capture, registered

REASONS = {
    "not_repository": "这个已登记目录本身没有 .git；不会向上、向下搜索，也不把普通文件夹当作项目。",
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
    "native_read_unavailable": "当前无法拿到准确的原生只读句柄，可能文件正被修改或不可读。没有改变仓库。",
}


def _display(value):
    return "".join(ch if not unicodedata.category(ch).startswith("C") else "�" for ch in value)


def open_source(context, settings):
    def locked(): return not WindowsDesktopHealth().probe(session_only=True).get("available")
    try:
        choices = registered(context, settings)
    except (Incomplete, PlatformError, OSError, ValueError):
        _dialog("本机登记暂不可核对", REASONS["configuration_changed"], [], retire=locked); return
    if not choices:
        _dialog("没有可供预检的本机工作区", "仅接受原安装已登记的 Windows 本机目录；不接受 SSH/网络位置或伴侣私有安装目录。", [], retire=locked); return
    page = 0
    while not locked():
        blocks = [(key, key+"\n"+_display(root)) for key,root in choices[page*5:page*5+5]]
        # Tagged choices keep even an owner's environment named 'next' or 'previous' distinct.
        buttons = [(("workspace",key),label) for key,label in blocks]
        if page: buttons.append(("previous","上一页工作区"))
        if (page+1)*5 < len(choices): buttons.append(("next","下一页工作区"))
        selected = _dialog("从哪个已登记的本机工作区核对？", "只读取这个根目录自身的 Git 元数据。不会运行 Git、读取工作文件正文或允许写入。",buttons,retire=locked)
        if selected == "next": page += 1; continue
        if selected == "previous": page -= 1; continue
        if selected is None or locked(): return
        key = selected[1]
        result = capture(context, settings, key)
        if locked(): return
        if not result["sourceVerified"]:
            _dialog("没有确认这个项目的源版本", key+"\n"+REASONS.get(result.get("reason"),REASONS["native_read_unavailable"]),[],retire=locked)
            return
        branch = result["branch"]
        when = time.strftime("%Y-%m-%d %H:%M:%S",time.localtime(result["capturedAt"]/1000))
        where = "分离 HEAD" if branch is None else "分支 "+_display(branch.removeprefix("refs/heads/"))
        summary = f"{key} · {where}\n{when} 核实提交 {result['commit'][:12]} 与其根树。"
        summary += "\n这是已提交的版本；未检查未提交改动、子目录或所有对象，未创建副本，也未开放项目生产。"
        if _dialog("已核对这台 Windows 上的 Git 来源",summary,[("identity","查看完整仓库与源版本编号")],retire=locked)=="identity" and not locked():
            details = f"本机仓库标识：{result['repositoryAnchor']}\n提交 ({result['objectFormat']})：{result['commit']}\n根树：{result['tree']}"
            stores = result["objectStorage"]
            label = {"loose":"散对象","pack":"本地对象包"}
            details += f"\n原件保存位置：提交（{label[stores['commit']]}），根树（{label[stores['rootTree']]}）。"
            _dialog("此次预检的不可变对象",details+"\n标识仅对应这份已登记仓库及此次观察；不是他台设备的项目权限。",[],retire=locked)
        return
