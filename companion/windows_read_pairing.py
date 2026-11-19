"""Local choice for a new Windows pairing; never advertised as a capability or applied to an existing grant."""
from datetime import datetime
import unicodedata

import device_protocol as protocol
from platform_backend import PlatformError

PREFIX = "owner.list-text."
TOOLS = ("workspace.list", "workspace.read")
METHODS = ("tool.invoke", "operation.get")


def declarations():
    return [{"toolName": name, "effect": "read"} for name in TOOLS]


def choice(profile):
    if profile.get("version") != "workspace-v1":
        return None
    resources = profile.get("resources")
    if not isinstance(resources, list) or len(resources) != 1:
        return None
    resource = protocol.validate_resource_binding("workspace-v1", resources[0])
    env_id = resource["environmentId"]
    if profile.get("id") != "workspace." + env_id:
        return None
    declared = profile.get("toolDeclarations", [])
    for name in TOOLS:
        # A changed/ambiguous native effect is not a candidate. Never inherit the other read tools.
        if [d.get("effect") for d in declared if isinstance(d, dict) and d.get("toolName") == name] != ["read"]:
            return None
    return {"id": PREFIX + env_id, "version": "workspace-v1", "resources": [resource], "toolDeclarations": declarations()}


def narrow(ids):
    limited = any(key.startswith(PREFIX) for key in ids)
    if limited and len(ids) != 1:
        raise PlatformError("仅目录和文本须单独选择一个工作区，不能与其他目录、完整工作区或桌面合并。")
    return limited


def guard(selected, grant, history):
    if not narrow([s["id"] for s in selected]):
        return
    scope = selected[0]
    if (len(scope["resources"]) != 1 or scope["id"] != PREFIX + scope["resources"][0]["environmentId"]
            or scope["version"] != "workspace-v1" or scope["toolDeclarations"] != declarations()
            or grant["toolNames"] != list(TOOLS) or grant["capabilities"] != list(METHODS)
            or len(grant["resources"]) != 1 or not protocol.resource_equal(grant["resources"][0], scope["resources"][0])
            or grant["allowedOrigins"] != [grant["peerDeviceId"]] or grant["allowDelegation"] is not False
            or grant["maxDelegationDepth"] != 0 or grant["modelBudget"] != 0 or "forwardingScope" in grant
            or ("historyRead" in grant) != history
            or history and grant["historyRead"] != {"expiresAt": grant["issuedAt"] + 86400000, "methods": ["operation.get"]}):
        raise PlatformError("实际待签授权与仅目录和文本不一致，本次不能确认。")


def summary(record):
    g = record["grant"]
    path = record["workspacePaths"][record["selectedCapabilities"][0]["id"]]
    if not isinstance(path, str) or not path or any(unicodedata.category(c).startswith("C") for c in path):
        raise PlatformError("这个登记目录无法安全、完整地显示，请先在电脑上核对；不会准备更宽的范围。")

    def time_at(ms):
        return datetime.fromtimestamp(ms / 1000).astimezone().strftime("%Y-%m-%d %H:%M:%S %z")

    history = (f"执行到期后，只能查这份授权仍留存的原操作回执，最迟至 {time_at(g['historyRead']['expiresAt'])}。"
               if "historyRead" in g else "未另授到期后的历史读取。")
    return (f"这台 Windows 向以下设备授予一个工作区。请对照两端的完整配对指纹。\r\n设备：{g['peerDeviceId']}\r\n"
            f"目录：{path}\r\n仅可在该目录内列目录、读小型 UTF-8 文本，不是只授权一个文件。"
            f"可查这份授权原操作的回执；工具总额度 {g['toolBudget']} 次，有效至 {time_at(g['expiresAt'])}。\r\n"
            f"{history}\r\n不授文件导出、工作区绝对位置查询、写入、命令、桌面画面或输入、电脑模型调用或转交；不改已有授权。\r\n")
