"""Owner-configured external harness commands and observed capability identities.

Presets describe installed executable entry points. Discovery never downloads or
runs a package manager, and a job request never supplies a command/environment.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shutil

SELECTION_PROTOCOL = "pocketworld.harness-selection.v1"
ACP_PROTOCOL = "acp_v1"
MSP_PROTOCOL = "muse_msp_v1"
WORKER_ID = re.compile(r"^acp_[a-z][a-z0-9_-]{0,64}$")

# Native Codex, Claude and Pi keep their existing drivers and identifiers.
_PRESETS = (
    ("gemini", "Gemini CLI", "gemini", ["--acp"]),
    ("auggie", "Auggie", "auggie", ["--acp"]),
    ("codebuddy", "CodeBuddy", "codebuddy", ["--acp"]),
    ("copilot", "GitHub Copilot", "copilot", ["--acp", "--stdio", "--no-auto-update"]),
    ("cursor", "Cursor", "cursor-agent", ["acp"]),
    ("droid", "Droid", "droid", ["acp-daemon"]),
    ("goose", "Goose", "goose", ["acp"]),
    ("hermes", "Hermes", "hermes", ["acp"]),
    ("kimi", "Kimi", "kimi", ["acp"]),
    ("kiro", "Kiro", "kiro-cli-chat", ["acp"]),
    ("opencode", "OpenCode", "opencode", ["acp"]),
    ("pi", "Pi ACP", "pi-acp", []),
    ("qoder", "Qoder", "qodercli", ["--acp"]),
    ("qwen", "Qwen Code", "qwen", ["--acp"]),
    ("snow", "Snow", "snow", ["--acp"]),
    ("vibe", "Vibe", "vibe-acp", []),
    ("autohand", "Autohand Code", "autohand-acp", []),
    ("openclaw", "OpenClaw", "openclaw", ["acp"]),
    ("deepagents", "DeepAgents", "deepagents-acp", []),
    ("dimcode", "DimCode", "dim", ["acp"]),
    ("dirac", "Dirac", "dirac", ["--acp"]),
    ("glm", "GLM Agent", "glm-acp-agent", []),
    ("grok", "Grok Build", "grok", ["--no-auto-update", "agent", "stdio"]),
    ("kilo", "Kilo", "kilo", ["acp"]),
    ("nova", "Nova", "nova", ["acp"]),
    ("sigit", "siGit Code", "sigit", []),
    ("amp", "Amp", "amp-acp", []),
    ("cortex", "Cortex Code", "cortex", ["acp", "serve"]),
    ("corust", "Corust Agent", "corust-agent-acp", []),
    ("devin", "Devin", "devin", ["acp"]),
    ("harn", "Harn", "harn", ["serve", "acp"]),
    ("junie", "Junie", "junie", ["--acp=true"]),
    ("poolside", "Poolside", "pool", ["acp"]),
    ("stakpak", "Stakpak", "stakpak", ["acp"]),
    ("vtcode", "VT Code", "vtcode", ["acp"]),
    ("mimo", "MiMo Code", "mimo", ["acp"]),
    ("omp", "Oh My Pi", "omp", ["acp"]),
    ("cline", "Cline", "cline", ["--acp"]),
    ("antigravity", "Antigravity ACP", "localharness_external", ["--uid="] if os.name == "posix" and os.uname().sysname == "Linux" else []),
)
PRESETS = {"acp_" + key: {"label": label, "command": command, "args": args, "adapter": "acp"}
           for key, label, command, args in _PRESETS}
PRESETS["muse_msp"] = {"label": "Muse Code", "command": "muse", "args": ["serve"], "adapter": "msp"}
_DEFAULT_ENV = {
    "acp_cline": {"CLINE_SESSION_BACKEND_MODE": "local", "CLINE_NO_AUTO_UPDATE": "1"},
    "acp_opencode": {"OPENCODE_DISABLE_AUTOUPDATE": "1"},
    "acp_kimi": {"KIMI_CLI_NO_AUTO_UPDATE": "1"},
    "acp_copilot": {"COPILOT_AUTO_UPDATE": "false"},
    "acp_antigravity": {"AGY_ACP_FORCE_FILE_STORAGE": "1", "BROWSER": "true"},
    "acp_vtcode": {"VT_ACP_ENABLED": "1", "VT_ACP_ZED_ENABLED": "1"},
}


def external(worker):
    return isinstance(worker, str) and (bool(WORKER_ID.fullmatch(worker)) or worker == "muse_msp")


def production_worker(worker):
    return worker in {"codex_app_server", "claude"} or external(worker)


def production_workers(environment):
    return [worker for worker in environment.get("workers", []) if production_worker(worker)]


def production_available(environment):
    return (environment.get("kind", "local") == "local" and os.name != "nt" and hasattr(os, "O_NOFOLLOW")
            and bool(environment.get("writeAllowed")) and bool(production_workers(environment)))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def _string(value, maximum=2048, empty=False):
    return isinstance(value, str) and (empty or bool(value)) and len(value) <= maximum and "\0" not in value and "\n" not in value and "\r" not in value


def validate_environment(environment):
    entries = environment.get("harnessWorkers", {})
    if not isinstance(entries, dict) or len(entries) > 80:
        raise ValueError("harnessWorkers must map at most 80 worker IDs to owner configurations")
    for key, value in entries.items():
        if not external(key) or not isinstance(value, dict) or set(value) - {"command", "args", "env", "label", "authMethod", "ownerManagedExecution"}:
            raise ValueError("Invalid external harness configuration")
        if "command" in value and not _string(value["command"]):
            raise ValueError("Harness command must be an installed executable name or absolute path")
        if "command" in value and not os.path.isabs(value["command"]) and ("/" in value["command"] or "\\" in value["command"]):
            raise ValueError("Harness command paths must be absolute; installed bare executable names are also supported")
        if key not in PRESETS and "command" not in value:
            raise ValueError("Custom ACP agents require an owner-configured command")
        if "args" in value and (not isinstance(value["args"], list) or len(value["args"]) > 80 or any(not _string(v, 12000, True) for v in value["args"])):
            raise ValueError("Harness args must be a bounded argument array")
        variables = value.get("env", {})
        if (not isinstance(variables, dict) or len(variables) > 80 or
                any(not isinstance(k, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", k) or
                    not isinstance(v, str) or len(v) > 16384 or "\0" in v for k, v in variables.items())):
            raise ValueError("Harness env must contain bounded environment variable strings")
        if any(k.startswith("POCKET") for k in variables):
            raise ValueError("Harness configuration cannot replace Companion authority variables")
        if "label" in value and not _string(value["label"], 100):
            raise ValueError("Harness label is invalid")
        if "authMethod" in value and not _string(value["authMethod"], 200):
            raise ValueError("Harness authMethod must identify an advertised authentication method")
        if "ownerManagedExecution" in value and value["ownerManagedExecution"] is not True:
            raise ValueError("External harness execution is owner managed")
    for key in environment.get("workers", []):
        if external(key) and key not in PRESETS and key not in entries:
            raise ValueError("Custom ACP workers require an owner configuration")


def configuration(environment, worker):
    if not external(worker) or worker not in environment.get("workers", []):
        raise ValueError("External harness is not enabled for this environment")
    value = {**PRESETS.get(worker, {"label": worker, "adapter": "acp", "args": []}),
             **environment.get("harnessWorkers", {}).get(worker, {})}
    if not value.get("command"):
        raise ValueError("External harness has no owner-configured installed command")
    value["env"] = {**_DEFAULT_ENV.get(worker, {}), **value.get("env", {})}
    value["workerId"] = worker
    value["protocol"] = MSP_PROTOCOL if worker == "muse_msp" else ACP_PROTOCOL
    value["executionPolicy"] = "owner_managed"
    return value


def executable_identity(config):
    command = config["command"]
    resolved = command if os.path.isabs(command) else shutil.which(command)
    if not resolved:
        return {"available": False, "reason": "executable_missing"}
    path = Path(resolved)
    try:
        # A native Windows supervisor requires a .exe. No shell/batch fallback.
        if not path.is_file() or os.name == "nt" and path.suffix.lower() != ".exe":
            return {"available": False, "reason": "native_executable_required"}
        if os.name != "nt" and not os.access(path, os.X_OK):
            return {"available": False, "reason": "executable_not_executable"}
        info = path.stat()
        return {"available": True, "path": str(path.resolve()), "device": info.st_dev,
                "inode": info.st_ino, "bytes": info.st_size, "modifiedNs": info.st_mtime_ns}
    except OSError:
        return {"available": False, "reason": "executable_unreadable"}


def revision(environment, worker, salt):
    config = configuration(environment, worker)
    identity = executable_identity(config)
    # A process-local HMAC keeps low-entropy credential values out of public hashes.
    script_inputs = []
    for arg in config["args"]:
        if not os.path.isabs(arg):
            continue
        try:
            path = Path(arg)
            if path.is_file():
                info = path.stat()
                script_inputs.append([str(path.resolve()), info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns])
        except OSError:
            script_inputs.append([arg, "unavailable"])
    return hmac.new(salt, canonical([config, identity, script_inputs]).encode(), hashlib.sha256).hexdigest()


def command(environment, worker):
    config = configuration(environment, worker)
    identity = executable_identity(config)
    if not identity.get("available"):
        raise ValueError("Configured harness executable is missing or unsupported on this platform")
    return [identity["path"], *config["args"]]


def child_environment(environment, worker, inherited):
    result = {k: v for k, v in inherited.items() if not k.startswith("POCKET")}
    result.update(configuration(environment, worker)["env"])
    return result


def redact(environment, worker, value):
    secrets = [v for k, v in configuration(environment, worker)["env"].items()
               if any(p in k.upper() for p in ("KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH")) and v]
    def scrub(item):
        if isinstance(item, str):
            for secret in sorted(secrets, key=len, reverse=True):
                item = item.replace(secret, "[credential hidden]")
            return item
        if isinstance(item, list):
            return [scrub(v) for v in item]
        if isinstance(item, dict):
            return {k: scrub(v) for k, v in item.items()}
        return item
    return scrub(value)


def capabilities(worker, hello=None, team_tools=False):
    result = {"protocol": MSP_PROTOCOL if worker == "muse_msp" else ACP_PROTOCOL,
              "wireProtocolVersion": 1, "protocolVerified": hello is not None,
              "modelInferenceVerified": False, "modelSelection": True,
              "threadResume": False, "supportsResume": None, "interactiveApprovals": True,
              "turnInterrupt": True, "inputSteering": False, "steerMode": "unsupported",
              "sessionReattach": False, "readOnlySandbox": False, "osReadIsolationVerified": False,
              "hostFileSystem": False, "hostTerminal": False, "executionPolicy": "owner_managed",
              "structuredUserInput": False, "progressSnapshot": True, "operationReconciliation": True,
              "teamTools": bool(team_tools), "liveStreaming": False}
    if hello is not None and worker != "muse_msp":
        caps = hello.get("agentCapabilities", {})
        sessions = caps.get("sessionCapabilities", {})
        resume = caps.get("loadSession") is True or isinstance(sessions, dict) and isinstance(sessions.get("resume"), dict)
        result.update(threadResume=resume, supportsResume=resume)
    elif hello is not None:
        result.update(threadResume=True, supportsResume=True)
    return result


def descriptor(environment, worker, salt):
    config = configuration(environment, worker)
    identity = executable_identity(config)
    supported = environment.get("kind", "local") == "local"
    return {"id": worker, "label": config["label"], "adapter": config["adapter"],
            "protocol": config["protocol"], "available": identity["available"] and supported,
            "availability": "requires_protocol_probe" if identity["available"] and supported else "local_companion_required" if not supported else identity["reason"],
            "configurationRevision": revision(environment, worker, salt),
            "executionPolicy": "owner_managed", "autonomousTools": True,
            "capabilities": {**capabilities(worker), "assignmentWorkspace": production_available(environment),
                             "projectProduction": production_available(environment) and shutil.which("git") is not None}}
