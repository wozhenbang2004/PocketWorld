"""Optional first local folder AFTER an explicit, signed, device-only setup.

No listener/manager, grant, shell command or job is created for registration.
Only the frozen owner desk may claim its original private empty configuration.
"""
from contextlib import ExitStack, contextmanager
import hashlib
import json
import ntpath
import re
import unicodedata
import uuid

from desktop_sessions import WindowsDesktopHealth
from device_identity import device_id
from platform_backend import PlatformError
from platform_windows import local_root
from windows_installation import _object, same_path
from windows_pair_identity import KEY as DISCLOSURE, PROFILE as REQUIRED

MARKER = "deferredOwnerFolderV1"
PIN = "ownerSelectedDirectoryV1"
SETUP = "device-only-owner-setup-v1.json"
JOURNAL = "first-owner-folder-intent-v1.json"
RECEIPT = "first-owner-folder-verified-v1.json"


def bad():
    raise PlatformError("不能将这项选择绑定到原安装、原空配置和原文件夹；未授权改用其他位置。")


def signed(ctx):
    return (ctx.current.get("allowUnsignedDevelopment") is False
            and isinstance(ctx.current.get("signerThumbprint"), str)
            and re.fullmatch(r"[A-Fa-f0-9]{40}", ctx.current["signerThumbprint"]) is not None
            and ctx.current.get("installId") == ctx.install_state.get("installId"))


def require_owner(ctx):
    ctx.check_current_pointer()
    if (not signed(ctx) or ctx._read(ctx.home, "install-state.json", 32768) != ctx.install_state
            or ctx.backend.private_leaf_present(ctx.home, "installation-transaction.json", ctx.sid)):
        bad()
    session = WindowsDesktopHealth().probe(session_only=True)
    owner = ctx.backend.logon_context()
    if session.get("available") is not True or owner["userSid"] != ctx.sid or owner["sessionId"] != session["sessionId"]:
        bad()
    return (owner["sessionId"], owner["authenticationId"])  # Native logon LUID, not a display name.


def new_marker(ctx, identity):
    return {"installId": ctx.install_state["installId"], "signer": ctx.current["signerThumbprint"].upper(),
            "deviceId": identity.device_id, "instanceId": identity.instance_id, "environmentId": "folder-" + uuid.uuid4().hex}


def setup(ctx, config):
    # Normal saved configs, hand-made empty configs and a consumed choice are never migrated.
    marker = config.get(MARKER)
    if (not signed(ctx) or config.get("environments") != [] or not isinstance(marker, dict)
            or (set(marker) - {DISCLOSURE}) != {"installId", "signer", "deviceId", "instanceId", "environmentId"}
            or (DISCLOSURE in marker and marker[DISCLOSURE] != REQUIRED) or marker["signer"] != ctx.current["signerThumbprint"].upper() or marker["installId"] != ctx.install_state["installId"]
            or not isinstance(marker["environmentId"], str) or not re.fullmatch(r"folder-[a-f0-9]{32}", marker["environmentId"])):
        return None
    witness = ctx.backend.read_optional_private_bytes(ctx.state, SETUP, ctx.sid, maximum=2048)
    return marker if witness == _encoded(marker) else None


def admissible(ctx, config, marker):
    if setup(ctx, config) != marker: bad()
    # Only this product-created layout and its original pairing transition.
    # Never offer a folder through an unreviewed remote legacy listener or relay.
    if (set(config) != {MARKER, "environments", "stateDir", "tokenFile", "devices", "desktop", "listen", "port"}
            or config.get("listen") != "127.0.0.1" or type(config.get("port")) is not int or config["port"] != 8766
            or not same_path(config["stateDir"], ctx.jobs) or not same_path(config["tokenFile"], ntpath.join(ctx.state, "companion-token.txt"))):
        bad()
    settings, desktop = config["devices"], config["desktop"]
    if (not isinstance(settings, dict) or (DISCLOSURE in settings) != (DISCLOSURE in marker)
            or (DISCLOSURE in settings and settings[DISCLOSURE] != REQUIRED) or (set(settings) - {DISCLOSURE}) not in ({"enabled", "identityDir", "routes"}, {"enabled", "identityDir", "routes", "listen"})
            or type(settings["enabled"]) is not bool or settings["routes"] != [] or not same_path(settings["identityDir"], ctx.identity_dir)
            or not isinstance(desktop, dict) or set(desktop) != {"enabled"} or type(desktop["enabled"]) is not bool):
        bad()
    if settings["enabled"] or "listen" in settings:
        from desktop_owner_entry import _local_listeners
        if not settings["enabled"] or _local_listeners(config, settings) is None: bad()
    # Read only original public certificate/instance bytes through their private DACL handles.
    # Do not load the private key or call the provisioning/ACL-normalization API.
    ctx.backend.assert_private_directory(ctx.identity_dir, ctx.sid)
    if (device_id(ctx._raw(ctx.identity_dir, "certificate.der", 32768)) != marker["deviceId"]
            or ctx._raw(ctx.identity_dir, "instance-id", 128).decode("ascii") != marker["instanceId"]
            or not ctx.backend.private_leaf_present(ctx.identity_dir, "private-key.dpapi", ctx.sid, maximum=32768)):
        bad()


@contextmanager
def selected(ctx, path):
    path = local_root(path)
    if len(path) <= 3 or any(unicodedata.category(c)[0] == "C" for c in path): bad()
    mine, private = [], []
    with ExitStack() as held:
        _, folder, _ = held.enter_context(ctx.backend.directory(path, lineage=mine))
        held.enter_context(ctx.backend.directory(ctx.home, lineage=private))
        if not mine or not private or mine[-1] in private or private[-1] in mine: bad()
        info = ctx.backend.api.info(folder)
        # File index + creation FILETIME, not the string name/new contents of the directory.
        birth = [info.created.dwHighDateTime, info.created.dwLowDateTime]
        if info.attributes & 0x441400 or not any(birth): bad()  # also refuse offline/cloud recall
        yield {"installId": ctx.install_state["installId"], "origin": [*mine[-1], *birth]}


def _encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def receipt(ctx, env, intent_raw):
    # Only a post-native-verified receipt binds the published config to its original intent and physical folder.
    if not isinstance(intent_raw, bytes): bad()
    intent = _object(intent_raw)
    if (intent.get("format") != "pocketworld.first-folder-intent.v1" or intent.get("installId") != ctx.install_state["installId"]
            or intent.get("environmentId") != env["id"] or intent.get("folderOrigin") != env[PIN]["origin"]
            or intent.get("replacement", {}).get("target") != "companion.json"): bad()
    if not signed(ctx): bad()
    binding = _encoded([ctx.install_state["installId"], ctx.current["signerThumbprint"].upper(), env["id"], local_root(env["root"]), env[PIN]])
    return _encoded({"format": "pocketworld.first-folder-verified.v1", "intentSha256": hashlib.sha256(intent_raw).hexdigest(),
                     "registeredFolderSha256": hashlib.sha256(binding).hexdigest()})


def recorded(ctx, env):
    try:
        intent = ctx.backend.read_optional_private_bytes(ctx.state, JOURNAL, ctx.sid, maximum=8192)
        native = ctx.backend.read_optional_private_bytes(ctx.state, RECEIPT, ctx.sid, maximum=2048)
        return native is not None and native == receipt(ctx, env, intent)
    except (OSError, KeyError, ValueError, TypeError, AttributeError, PlatformError):
        return False


@contextmanager
def verified_root(ctx, env):
    proof = env[PIN]
    if not recorded(ctx, env): bad()
    if (not isinstance(proof, dict) or set(proof) != {"installId", "origin"} or not isinstance(proof["origin"], list)
            or len(proof["origin"]) != 5 or any(type(n) is not int or not 0 <= n < 2**32 for n in proof["origin"])):
        bad()
    with selected(ctx, env["root"]) as current:
        if current != proof: bad()
        yield


def summary(ctx, config):
    # Local state only; opening the workbench never opens the chosen folder or reads the device key.
    if MARKER in config and config.get("environments") == []:
        try:
            if setup(ctx, config) is None: return "inspect"
            return "inspect" if ctx.backend.private_leaf_present(ctx.state, JOURNAL, ctx.sid, maximum=8192) else "empty"
        except (OSError, ValueError, TypeError, PlatformError):
            return "inspect"
    envs = config.get("environments")
    if isinstance(envs, list) and len(envs) == 1 and isinstance(envs[0], dict) and PIN in envs[0] and not recorded(ctx, envs[0]):
        return "unverified"
    return None


def offer(ctx, settings):
    from desktop_owner_entry import _dialog, _folder, _original_in_use
    from desktop_worker import original_state_idle
    try:
        owner = require_owner(ctx)
        if _original_in_use(ctx): bad()
        raw = ctx._raw(ctx.state, "companion.json", 262144)
        config = _object(raw); marker = setup(ctx, config)
        if not marker or hashlib.sha256(raw).hexdigest() != settings or summary(ctx, config) != "empty": bad()
        admissible(ctx, config, marker)
        path = _folder()
        if path is None: return ""
        with ExitStack() as held:
            proof = held.enter_context(selected(ctx, path))
            # Both this generation and its ancestors stay pinned through confirmation and native CAS.
            def before():
                if (require_owner(ctx) != owner or ctx._raw(ctx.state, "companion.json", 262144) != raw
                        or not ctx.backend.private_leaf_present(ctx.state, "companion-token.txt", ctx.sid, maximum=512)):
                    bad()
                admissible(ctx, config, marker)

            before()
            if _dialog("登记这个本机工作文件夹？", path + "\n\n不读取或改写其中的文件，不启用命令、写入、配对或服务。"
                       "\n以后，本机原服务的持令牌客户端可读取这个工作区；能查看设备能力的伙伴可见范围代号，"
                       "但读取文件仍须另获这份工作区的授权。运行时会固定原目录，换成同名目录不会接替。",
                       [("save", "只登记这一个文件夹")]) != "save":
                return ""
            # Same read-sharing lock as Launch-Companion.ps1; blocks competing installer repair/switch.
            held.enter_context(ctx.backend.open_reader(ctx.home, "install.lock", 65536))
            held.enter_context(original_state_idle(ctx.jobs))
            before()
            if (summary(ctx, config) != "empty"
                    or ctx.backend.private_leaf_present(ctx.state, RECEIPT, ctx.sid, maximum=2048)): bad()
            env = {"id": marker["environmentId"], "kind": "local", "label": "我的工作文件夹", "root": path,
                   "workers": [], "commands": {}, "writeAllowed": False, PIN: proof}
            updated = dict(config, environments=[env]); del updated[MARKER]
            after = (json.dumps(updated, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            if len(after) > 262144: bad()

            def record(intent):
                # Original native object identities/hashes for inspection only. No settings bytes, tokens or public keys.
                body = {"format": "pocketworld.first-folder-intent.v1", "installId": marker["installId"],
                        "environmentId": env["id"], "folderOrigin": proof["origin"], "replacement": intent}
                ctx.backend.create_private_snapshot(ctx.state, JOURNAL, _encoded(body), maximum=8192, current=before)

            result = ctx.backend.compare_and_swap(ctx.state, "companion.json", raw, after, before, record_intent=record)
            if result.get("verified") is not True: bad()

            def landed():
                if require_owner(ctx) != owner or ctx._raw(ctx.state, "companion.json", 262144) != after: bad()

            landed()
            intent_raw = ctx._raw(ctx.state, JOURNAL, 8192)
            original = _object(intent_raw).get("replacement", {})
            if (original.get("phase") != "dispatch_intent_persisted" or original.get("beforeSha256") != settings
                    or original.get("afterSha256") != hashlib.sha256(after).hexdigest()
                    or original.get("temporaryIdentity") != result.get("nativeReplacement", {}).get("temporaryIdentity")): bad()
            ctx.backend.create_private_snapshot(ctx.state, RECEIPT, receipt(ctx, env, intent_raw), maximum=2048, current=landed)
            if not recorded(ctx, env): bad()
            landed()
            return "已从原配置核对：只登记了你选的本机文件夹。当前没有启动服务、运行命令或另授伙伴权限。"
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError, PlatformError):
        return "未能确认登记完成。请刷新核对原安装与配置；如已有登记意图，不会再次替换或还原。"
