"""Fixed Gemini 0.66.0 launch observations, not an executor or an authority store.

Only an owner-configured local installation is read. No package installation,
credential file, inherited Gemini profile or user-selected argv is accepted.
The source is Google's v0.66.0 (970413207732f5d4d8c50540c7fc359c44fdaaf2).
Content identity does not attest every child executable or an OS sandbox.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile


WORKER = "gemini_acp"
BACKEND = "gemini_cli_acp_v1"
VERSION = "0.66.0"
PROTOCOL = "gemini_acp_v1"
WIRE_VERSION = 1
SYSTEM_POLICY_DIRECTORY = Path("/etc/gemini-cli/policies")
MAX_FILES = 20_000
MAX_INSTALL_BYTES = 512 * 1024 * 1024
SETTINGS = {
    "security": {"auth": {"selectedType": "gemini-api-key", "useExternal": False},
                 "folderTrust": {"enabled": True}, "disableYoloMode": True,
                 "disableAlwaysAllow": True, "enablePermanentToolApproval": False,
                 "autoAddToPolicyByDefault": False},
    "general": {"defaultApprovalMode": "default", "plan": {"enabled": False},
                "checkpointing": {"enabled": False}, "sessionRetention": {"enabled": False}},
    "hooksConfig": {"enabled": False}, "advanced": {"ignoreLocalEnv": True},
    "ide": {"enabled": False},
    "experimental": {"enableAgents": False, "extensionReloading": False,
                     "autoMemory": False, "modelSteering": False},
    "skills": {"enabled": False},
    "tools": {"allowed": [], "sandbox": False, "shell": {"enableInteractiveShell": False}},
    "mcpServers": {},
}


class ProfileError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def hex_digest(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def _stamp(info):
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode, info.st_uid]


def _trusted(info):
    return info.st_uid in {0, os.geteuid()} and not info.st_mode & 0o022


def _absolute(value, directory=False):
    if not isinstance(value, str) or not value or "\0" in value:
        raise ProfileError("installation_missing", "主人尚未配置固定的 Gemini 安装路径。")
    path = Path(value)
    try:
        if not path.is_absolute() or path.resolve(strict=True) != path:
            raise ProfileError("installation_unsupported", "固定安装路径必须是现存的绝对真实路径。")
        info = path.lstat()
        if not _trusted(info) or (not stat.S_ISDIR(info.st_mode) if directory else not stat.S_ISREG(info.st_mode)):
            raise ProfileError("installation_unsupported", "固定安装来源的文件类型或写权限不符合要求。")
    except OSError as error:
        raise ProfileError("installation_missing", "固定的 Node 或 Gemini 安装尚不可读取。") from error
    return path


def _file(path, maximum):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0))
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or not _trusted(before) or before.st_size > maximum:
            raise ProfileError("installation_unsupported", "固定来源超出文件大小或权限边界。")
        chunks, count = [], 0
        while True:
            part = os.read(descriptor, min(1024 * 1024, maximum + 1 - count))
            if not part:
                break
            chunks.append(part)
            count += len(part)
            if count > maximum:
                raise ProfileError("installation_unsupported", "固定来源超过大小上限。")
        after = os.fstat(descriptor)
        if _stamp(before) != _stamp(after) or _stamp(path.lstat()) != _stamp(after):
            raise ProfileError("source_changed", "固定安装来源在读取期间发生变化。")
        return b"".join(chunks), _stamp(after)
    finally:
        os.close(descriptor)


def _observe_installation(node_executable, installation_root):
    """The one content algorithm used both to propose and to check owner pins."""
    if os.name != "posix" or not sys.platform.startswith("linux"):
        raise ProfileError("platform_unsupported", "此固定 ACP 后端仅支持本机 Linux。")
    node = _absolute(node_executable)
    root = _absolute(installation_root, directory=True)
    raw, node_stamp = _file(node, 128 * 1024 * 1024)
    if not raw.startswith(b"\x7fELF") or not os.access(node, os.X_OK):
        raise ProfileError("installation_unsupported", "需要主人固定的本机 Node 可执行文件，不能使用脚本启动器。")
    node_hash = hashlib.sha256(raw).hexdigest()
    members, stamps, total = [], {}, 0
    def unreadable(error):
        raise ProfileError("installation_unsupported", "完整安装树中有不可读取的目录，不能省略它继续核对。") from error
    for current, directories, files in os.walk(root, followlinks=False, onerror=unreadable):
        current = Path(current)
        info = current.lstat()
        if not stat.S_ISDIR(info.st_mode) or not _trusted(info):
            raise ProfileError("installation_unsupported", "安装目录包含未知来源或可被其他用户改写。")
        stamps[str(current)] = _stamp(info)
        for name in sorted(directories + files):
            path = current / name
            relative = path.relative_to(root).as_posix()
            info = path.lstat()
            if len(members) + len(stamps) >= MAX_FILES:
                raise ProfileError("installation_unsupported", "固定安装的文件数量超过核对上限。")
            if stat.S_ISLNK(info.st_mode):
                # npm bin links are allowed only inside this fully enumerated tree.
                target = path.resolve(strict=True)
                if not target.is_relative_to(root) or not target.is_file():
                    raise ProfileError("installation_unsupported", "安装链接不能跳出原完整安装树或指向目录。")
                members.append([relative, "link", os.readlink(path)])
                stamps[str(path)] = _stamp(info)
            elif stat.S_ISREG(info.st_mode):
                raw, stamp = _file(path, MAX_INSTALL_BYTES - total)
                total += len(raw)
                members.append([relative, len(raw), hashlib.sha256(raw).hexdigest()])
                stamps[str(path)] = stamp
            elif not stat.S_ISDIR(info.st_mode):
                raise ProfileError("installation_unsupported", "安装树含有不支持的特殊文件。")
    tree_hash = digest(sorted(members, key=lambda row: row[0]))
    package = root / "node_modules/@google/gemini-cli"
    try:
        metadata = json.loads((package / "package.json").read_text(encoding="utf-8"))
        entry = package / "dist/index.js"
        if metadata.get("name") != "@google/gemini-cli" or metadata.get("version") != VERSION or not entry.is_file() or entry.resolve() != entry:
            raise ProfileError("installation_unsupported", "只支持固定的 @google/gemini-cli 0.66.0 原入口。")
    except (OSError, ValueError, AttributeError) as error:
        raise ProfileError("installation_missing", "固定 Gemini 包清单或入口缺失。") from error
    for name, stamp in {str(node): node_stamp, **stamps}.items():
        if _stamp(Path(name).lstat()) != stamp:
            raise ProfileError("source_changed", "完整安装树在观察期间改变；此次摘要无效。")
    identity = digest(["pocketworld.gemini-install.v1", str(node), node_hash, node_stamp,
                       str(root), tree_hash, sorted(stamps.items()), VERSION])
    return {"node": node, "root": root, "entry": entry, "stamps": {str(node): node_stamp, **stamps},
            "configuration": {"nodeExecutable": str(node), "nodeSha256": node_hash,
                              "installationRoot": str(root), "installationSha256": tree_hash},
            "observation": {"format": "pocketworld.remote-install-observation.v1", "state": "observed",
                "identity": identity, "kind": "fixed_node_package_bytes_v1", "packageVersion": VERSION,
                "nodeContentVerified": True, "packageContentVerified": True,
                "executionChainVerified": False, "osSandboxVerified": False, "reason": "fixed_source_bytes_observed"}}


def installation_configuration(node_executable, installation_root):
    """Read-only candidate geminiAcp config. This neither approves nor launches it.

    installationSha256 hashes canonical UTF-8 JSON of all sorted file members:
    [POSIX relative path, byte length, SHA256], or [path, "link", link text] for
    an internal regular-file link. Directories are checked, not hashed as content.
    The runtime uses this exact same traversal, limits, ownership and byte checks.
    """
    return _observe_installation(node_executable, installation_root)["configuration"]


def installation(environment):
    """Check the owner's full-installation pin; nothing is executed."""
    if environment.get("kind", "local") != "local":
        raise ProfileError("platform_unsupported", "此固定 ACP 后端仅支持本机 Linux。")
    config = environment.get("geminiAcp")
    keys = {"nodeExecutable", "nodeSha256", "installationRoot", "installationSha256"}
    if not isinstance(config, dict) or set(config) != keys:
        raise ProfileError("installation_missing", "需要主人配置固定 Node、完整 Gemini 安装及其内容摘要。")
    if not hex_digest(config["nodeSha256"]) or not hex_digest(config["installationSha256"]):
        raise ProfileError("installation_unsupported", "固定安装的内容摘要格式无效。")
    result = _observe_installation(config["nodeExecutable"], config["installationRoot"])
    if result["configuration"] != config:
        raise ProfileError("installation_changed", "Node 或 Gemini 完整安装内容与主人配置的摘要不符。")
    return result


def system_policy():
    path = SYSTEM_POLICY_DIRECTORY
    try:
        try:
            info = path.lstat()
        except FileNotFoundError:
            return {"state": "absent"}
        if not stat.S_ISDIR(info.st_mode):
            raise ProfileError("profile_conflict", "系统 Gemini 策略路径不可确认。")
        entries = list(path.iterdir())
        if not _trusted(info) or len(entries) > 1000 or any(p.suffix.lower() == ".toml" for p in entries):
            raise ProfileError("profile_conflict", "存在系统 Gemini 策略或不可核对的策略来源；不能覆盖它继续运行。")
        return {"state": "no_toml", "identity": _stamp(info), "entries": sorted(p.name for p in entries)}
    except OSError as error:
        raise ProfileError("profile_conflict", "系统 Gemini 策略无法读取；此次启动已保留关闭。") from error


def missing_install(reason):
    state = "missing" if reason == "installation_missing" else "changed" if reason in {"installation_changed", "source_changed"} else "unsupported" if reason.endswith("unsupported") else "unavailable"
    return {"format": "pocketworld.remote-install-observation.v1", "state": state, "identity": "",
            "kind": "fixed_node_package_bytes_v1", "packageVersion": "", "nodeContentVerified": False,
            "packageContentVerified": False, "executionChainVerified": False,
            "osSandboxVerified": False, "reason": reason}


class FixedProfile:
    """A private, fixed source observation borrowed by the original manager jobs."""
    def __init__(self, environment, binding, state_directory, salt, model, observed_install=None):
        if environment.get("writeAllowed") is not True or WORKER not in environment.get("workers", []):
            raise ProfileError("writable_private_only", "此后端需要主人明确启用的本机可写环境。")
        self.install = observed_install if observed_install is not None else installation(environment)
        key = os.environ.get("GEMINI_API_KEY", "")
        if not key.strip() or len(key) > 8192 or "\0" in key or "\n" in key or "\r" in key:
            raise ProfileError("credential_missing", "主人尚未通过主机受保护环境预配置 Gemini API key。")
        self.environment = json.loads(json.dumps(environment))
        self.binding, self.salt, self.model = binding, salt, model
        self.credential_revision = hmac.new(salt, key.encode(), hashlib.sha256).hexdigest()
        self._original_credential = key  # Redaction only; never persisted or returned.
        self.policy = system_policy()
        self.root = Path(tempfile.mkdtemp(prefix="gemini-acp-", dir=state_directory))
        self.users, self.retired, self.closed = 0, False, False
        try:
            self.home, self.policy_directory = self.root / "home", self.root / "policy"
            for path in [self.home, self.home / ".gemini", self.policy_directory, self.root / "tmp"]:
                path.mkdir(mode=0o700)
            self.expected = {
                self.root / "system.json": canonical(SETTINGS) + "\n",
                self.root / "defaults.json": "{}\n",
                self.home / ".gemini/settings.json": "{}\n",
            }
            for path, value in self.expected.items():
                path.write_text(value, encoding="utf-8")
                path.chmod(0o600)
            self.absent = [self.home / name for name in (".env", "GEMINI.md", ".gemini/.env", ".gemini/GEMINI.md",
                          ".gemini/oauth_creds.json", ".gemini/google_accounts.json", ".gemini/policies",
                          ".gemini/extensions", ".gemini/hooks", ".gemini/agents", ".gemini/skills")]
            self.profile_identity = self._profile_identity()
            self.root_identity = self._workspace_identity()
            self.revision = digest(["pocketworld.gemini-profile.v1", self.binding, self.root_identity,
                self.install["observation"], self.profile_identity, self.policy, self.credential_revision,
                self.argv, self._public_environment()])
        except Exception:
            shutil.rmtree(self.root)
            raise

    @property
    def argv(self):
        result = [str(self.install["node"]), str(self.install["entry"]), "--acp", "--approval-mode", "default",
                  "--extensions", "none", "--policy", str(self.policy_directory)]
        if self.model:
            result.append("--model=" + self.model)
        return result

    def _public_environment(self):
        # HOME retains its original value; only the vendor-specific profile is redirected.
        result = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "NO_BROWSER": "1",
                  "GEMINI_CLI_HOME": str(self.home), "GEMINI_CLI_SYSTEM_SETTINGS_PATH": str(self.root / "system.json"),
                  "GEMINI_CLI_SYSTEM_DEFAULTS_PATH": str(self.root / "defaults.json"),
                  "GEMINI_CLI_TRUST_WORKSPACE": "false", "TMPDIR": str(self.root / "tmp")}
        if "HOME" in os.environ:
            result["HOME"] = os.environ["HOME"]
        return result

    def child_environment(self):
        self.check()
        return {**self._public_environment(), "GEMINI_API_KEY": os.environ["GEMINI_API_KEY"]}

    def _workspace_identity(self):
        root = Path(self.environment["root"])
        if root.resolve() != root or not root.is_dir():
            raise ProfileError("source_changed", "原主人工作目录已经改变。")
        info = root.stat()
        return [str(root), info.st_dev, info.st_ino]

    def _profile_identity(self):
        rows = []
        for path, expected in self.expected.items():
            raw, stamp = _file(path, 32768)
            if raw != expected.encode("utf-8"):
                raise ProfileError("profile_changed", "原受控配置内容已经改变。")
            rows.append([str(path), hashlib.sha256(raw).hexdigest(), stamp])
        for path in self.absent:
            if os.path.lexists(path):
                raise ProfileError("profile_conflict", "受控配置中出现未经核对的设置、凭据、扩展或策略来源。")
        if self.policy_directory.is_symlink() or list(self.policy_directory.iterdir()):
            raise ProfileError("profile_conflict", "原专用策略目录必须保持为空。")
        for path in [self.root, self.home, self.home / ".gemini", self.policy_directory]:
            info = path.lstat()
            if not stat.S_ISDIR(info.st_mode) or not _trusted(info):
                raise ProfileError("profile_changed", "受控目录身份或权限已经改变。")
            rows.append([str(path), info.st_dev, info.st_ino, info.st_mode, info.st_uid])
        return rows

    def check(self, deep=False):
        if self.closed:
            raise ProfileError("profile_changed", "原配置观察已经释放，需要重新核对。")
        key = os.environ.get("GEMINI_API_KEY", "")
        credential = hmac.new(self.salt, key.encode(), hashlib.sha256).hexdigest()
        if credential != self.credential_revision or system_policy() != self.policy or self._workspace_identity() != self.root_identity:
            raise ProfileError("source_changed", "原凭据来源、系统策略或工作目录已经改变。")
        if self._profile_identity() != self.profile_identity:
            raise ProfileError("profile_changed", "原受控配置已经改变。")
        for name, previous in self.install["stamps"].items():
            if _stamp(Path(name).lstat()) != previous:
                raise ProfileError("installation_changed", "原固定安装来源已经改变。")
        if deep and installation(self.environment)["observation"] != self.install["observation"]:
            raise ProfileError("installation_changed", "原固定安装内容已经改变。")
        current_revision = digest(["pocketworld.gemini-profile.v1", self.binding, self.root_identity,
            self.install["observation"], self.profile_identity, self.policy, self.credential_revision,
            self.argv, self._public_environment()])
        if current_revision != self.revision:
            raise ProfileError("profile_changed", "原启动环境已经改变。")

    def redact(self, value):
        if isinstance(value, str):
            for key in {self._original_credential, os.environ.get("GEMINI_API_KEY", "")}:
                if key:
                    value = value.replace(key, "[credential hidden]")
            return value
        if isinstance(value, list):
            return [self.redact(item) for item in value]
        if isinstance(value, dict):
            return {self.redact(key_): self.redact(item) for key_, item in value.items()}
        return value

    def retain(self):
        if self.closed:
            raise ProfileError("profile_changed", "原受控配置已释放。")
        self.users += 1

    def release(self):
        if self.users:
            self.users -= 1
        if self.retired and not self.users:
            self.close()

    def close(self):
        self.retired = True
        if self.users or self.closed:
            return
        self.closed = True
        shutil.rmtree(self.root)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Read fixed Gemini installation bytes and print an owner-reviewable config; never execute or install anything.")
    parser.add_argument("--node", required=True, help="Absolute real Node executable path")
    parser.add_argument("--installation-root", required=True, help="Full installation root containing node_modules/@google/gemini-cli")
    args = parser.parse_args()
    try:
        print(json.dumps(installation_configuration(args.node, args.installation_root), ensure_ascii=False, indent=2))
    except (OSError, ValueError, ProfileError) as failure:
        parser.exit(1, "Fixed installation observation unavailable: " + (failure.code if isinstance(failure, ProfileError) else type(failure).__name__) + "\n")
