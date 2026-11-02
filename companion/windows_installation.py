"""Native installed-entry validation. Never consumes a remote config/path/token.

The installer owns the fixed user layout and current pointer. This adapter
verifies original handles/DACLs and passes local settings to the ORIGINAL main;
it is neither another server nor a credential provisioning-on-startup fallback.
"""
import copy
import hashlib
import json
import ntpath
import os
import re
import secrets
from pathlib import Path

from platform_backend import PlatformError
from platform_windows import WindowsBackend, local_root, relative_parts

_CONFIG = object()


def _matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def same_path(left, right):
    return isinstance(left, str) and isinstance(right, str) and ntpath.normcase(local_root(left)) == ntpath.normcase(local_root(right))


def _object(raw):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise PlatformError("Duplicate native installation/config member", 409)
            result[key] = value
        return result
    value = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=pairs)
    if not isinstance(value, dict):
        raise PlatformError("Native installation/config record must be an object", 409)
    return value


class NativeInstalledConfig:
    def __init__(self, seal, context, value, config_raw):
        if seal is not _CONFIG:
            raise TypeError("Use verified native Windows installation context")
        self.context, self.config_path = context, context.config
        self._value, self._raw = copy.deepcopy(value), config_raw

    def json(self):
        return copy.deepcopy(self._value)

    def check_current(self):
        self.context.check_current_pointer()
        if self.context._raw(self.context.state, "companion.json", 262144) != self._raw:
            raise PlatformError("Owner configuration changed before original supervisor startup", 409)


class WindowsInstalledContext:
    def __init__(self, config=None, *, backend=None, localappdata=None, current_executable=None):
        self.backend = backend if backend is not None else WindowsBackend()
        base = localappdata if localappdata is not None else os.environ.get("LOCALAPPDATA")
        if not isinstance(base, str):
            raise PlatformError("Native current-user LOCALAPPDATA is unavailable", 409)
        self.home = local_root(ntpath.join(base, "PocketWorld", "Companion"))
        self.state, self.config = ntpath.join(self.home, "state"), ntpath.join(self.home, "state", "companion.json")
        self.identity_dir, self.jobs, self.logs = (ntpath.join(self.state, name) for name in ("identity-v1", "jobs", "logs"))
        self.sid = self.backend.logon_context()["userSid"]
        self.backend.assert_private_directory(self.home, self.sid)
        self.backend.assert_private_directory(self.state, self.sid)
        if config is not None and not same_path(os.fspath(config), self.config):
            raise PlatformError("Installed entry only accepts its fixed private companion.json; external state import is not supported", 403)
        state = self._read(self.home, "install-state.json", 32768)
        if type(state.get("schemaVersion")) is not int or state["schemaVersion"] != 2 or state.get("ownerSid") != self.sid or not _matches(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", state.get("installId")):
            raise PlatformError("Native installation owner/schema identity changed", 403)
        expected = {"stateRoot": self.state, "config": self.config, "identityDir": self.identity_dir, "jobs": self.jobs, "logs": self.logs}
        if any(not same_path(state.get(key), path) for key, path in expected.items()):
            raise PlatformError("Native installation state ownership/layout changed; no external files were read", 403)
        self.install_state = state
        self.current = self._read(self.home, "current.json", 32768)
        current = self.current
        version = current.get("version", "")
        if (type(current.get("schemaVersion")) is not int or current["schemaVersion"] != 2 or current.get("ownerSid") != self.sid or current.get("installId") != state["installId"]
                or not _matches(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", version)
                or not same_path(current.get("root"), ntpath.join(self.home, "releases", version))
                or not same_path(current.get("state"), self.state) or not same_path(current.get("config"), self.config)
                or type(current.get("loginStartEnabled")) is not bool or type(current.get("allowUnsignedDevelopment")) is not bool
                or not _matches(r"[a-fA-F0-9]{64}", current.get("manifestSha256"))):
            raise PlatformError("Native current release pointer is incompatible or outside this installation", 403)
        self.release = local_root(current["root"])
        self.backend.assert_private_directory(self.release, self.sid, require_protected=False)
        raw = self._raw(self.release, "bundle-manifest.json", 4 * 1024 * 1024)
        if hashlib.sha256(raw).hexdigest() != current["manifestSha256"].lower():
            raise PlatformError("Current immutable manifest hash changed", 403)
        manifest = _object(raw)
        if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1 or manifest.get("product") != "PocketWorld-Companion" or manifest.get("version") != version or manifest.get("platform") != "windows-x64":
            raise PlatformError("Current immutable release manifest is incompatible", 409)
        cua = manifest.get("cua", {})
        if cua.get("sourceCommit") != "f595a4fb6f524e555e5e422aab81c3457fe4a713" or cua.get("spacesIncluded") is not False or cua.get("perceptionIncluded") is not False:
            raise PlatformError("Current Driver source/license boundary changed", 409)
        declared, folded = {}, set()
        if not isinstance(manifest.get("files"), list) or len(manifest["files"]) > 20000:
            raise PlatformError("Current release file manifest exceeds its bound", 409)
        for file in manifest["files"]:
            if not isinstance(file, dict):
                raise PlatformError("Current native file declaration is not an object", 409)
            relative_parts(file.get("path"))
            name = file["path"]
            if name.casefold() in folded or not _matches(r"[a-fA-F0-9]{64}", file.get("sha256")) or type(file.get("bytes")) is not int or not 0 <= file["bytes"] <= 512 * 1024 * 1024:
                raise PlatformError("Current release has duplicate/invalid native file declarations", 409)
            declared[name] = file; folded.add(name.casefold())
        for name in ("app/PocketWorld-Companion.exe", "driver/cua-driver.exe"):
            if name not in declared:
                raise PlatformError("Current embedded runtime or Driver is missing", 409)
        self.binary = ntpath.join(self.release, "driver", "cua-driver.exe")
        self.driver_sha256 = declared["driver/cua-driver.exe"]["sha256"].lower()
        if self.driver_sha256 != cua.get("sha256", "").lower():
            raise PlatformError("Current Driver pin differs from its immutable manifest", 403)
        if current_executable is not None and not same_path(os.fspath(current_executable), ntpath.join(self.release, "app", "PocketWorld-Companion.exe")):
            raise PlatformError("This installed executable is not the current release; use the fixed launcher", 409)
        # Launcher checks the entire signed package before process start. Also
        # pin these two original handles for direct executable-entry validation.
        for name in ("app/PocketWorld-Companion.exe", "driver/cua-driver.exe"):
            actual = hashlib.sha256()
            with self.backend.open_reader(self.release, name, maximum=512 * 1024 * 1024) as stream:
                if stream.size != declared[name]["bytes"]:
                    raise PlatformError("Current native artifact size changed", 403)
                while True:
                    data = stream.read(262144)
                    if not data:
                        break
                    actual.update(data)
            if actual.hexdigest() != declared[name]["sha256"].lower():
                raise PlatformError("Current native artifact bytes changed", 403)

    def _raw(self, root, relative, maximum):
        return self.backend.read_private_bytes(root, relative, self.sid, maximum=maximum)

    def _read(self, root, relative, maximum):
        return _object(self._raw(root, relative, maximum))

    def check_current_pointer(self):
        if self._read(self.home, "current.json", 32768) != self.current:
            raise PlatformError("Installation version/pointer changed before native owner action or supervisor startup", 409)

    def launch_config(self):
        raw = self._raw(self.state, "companion.json", 262144)
        config = _object(raw)
        if not same_path(config.get("stateDir"), self.jobs) or not same_path(config.get("tokenFile"), ntpath.join(self.state, "companion-token.txt")):
            raise PlatformError("Installed configuration redirects private jobs/token state", 403)
        devices = config.get("devices", {})
        if not isinstance(devices, dict) or not same_path(devices.get("identityDir"), self.identity_dir):
            raise PlatformError("Installed configuration redirects native device identity", 403)
        self.backend.assert_private_directory(self.jobs, self.sid, require_protected=False)
        self._raw(self.state, "companion-token.txt", 512)  # Same handle validates owner/DACL before original token loader.
        desktop = config.get("desktop", {})
        if not isinstance(desktop, dict) or type(desktop.get("enabled", False)) is not bool:
            raise PlatformError("Installed desktop configuration is invalid", 409)
        config["desktop"] = {"enabled": desktop.get("enabled", False), "binary": self.binary, "sha256": self.driver_sha256}
        return NativeInstalledConfig(_CONFIG, self, config, raw)

    def initialize(self, workspace):
        """Only called after the local owner's explicit --init/Manage Initialize."""
        if self.backend.read_bytes(self.state, "companion.json") is not None or self.backend.read_bytes(self.state, "companion-token.txt") is not None:
            raise PlatformError("Existing or partially initialized private state requires local inspection; no overwrite/new identity", 409)
        from device_identity import _private_dir, load_existing_identity, provision_identity
        root = local_root(os.fspath(Path(workspace).expanduser().absolute()))
        with self.backend.directory(root), self.backend.directory(self.state):
            for directory in (self.jobs, self.logs):
                _private_dir(directory)
            identity_path = Path(self.identity_dir)
            if identity_path.exists() and any(identity_path.iterdir()):
                local_identity = load_existing_identity(identity_path)
            else:
                local_identity = provision_identity(identity_path)
            token = secrets.token_urlsafe(36).encode("ascii")
            self.backend.create_private_snapshot(self.state, "companion-token.txt", token)
            config = {"listen": "127.0.0.1", "port": 8766, "tokenFile": ntpath.join(self.state, "companion-token.txt"), "stateDir": self.jobs,
                      "devices": {"enabled": False, "identityDir": self.identity_dir, "routes": []}, "desktop": {"enabled": False},
                      "environments": [{"id": "computer", "label": "我的电脑", "kind": "local", "root": root, "workers": ["command"], "commands": {}, "writeAllowed": False}]}
            self.backend.create_private_snapshot(self.state, "companion.json", (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        return {"initialized": True, "deviceId": local_identity.device_id, "instanceId": local_identity.instance_id,
                "devicesEnabled": False, "desktopEnabled": False, "credentialsPrinted": False}
