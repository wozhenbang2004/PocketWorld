"""Local owner discovery and explicit enablement; never installs an executable."""
from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import tempfile
import uuid

import harness_registry


def discover(config=None, environment_id=None):
    environments = (config or {}).get("environments", [])
    environment = next((value for value in environments if value.get("id") == environment_id), None)
    if environment_id and environment is None:
        raise ValueError("Selected environment does not exist")
    definitions = (environment or {}).get("harnessWorkers", {})
    workers = list(harness_registry.PRESETS)
    workers.extend(value for value in definitions if value not in workers)
    rows = []
    for worker in workers:
        probe_env = {**(environment or {}), "workers": [worker]}
        configured = harness_registry.configuration(probe_env, worker)
        observed = harness_registry.executable_identity(configured)
        rows.append({"id": worker, "label": configured["label"], "adapter": configured["adapter"],
            "enabled": worker in (environment or {}).get("workers", []), "installed": observed["available"],
            "availability": "requires_protocol_probe" if observed["available"] else observed["reason"],
            "defaultCommand": harness_registry.PRESETS.get(worker, {}).get("command"),
            "defaultArgs": harness_registry.PRESETS.get(worker, {}).get("args", []),
            "executionPolicy": "owner_managed"})
    return {"protocol": "pocketworld.harness-discovery.v1", "environmentId": environment_id,
        "harnesses": rows, "executablesStarted": False, "packagesInstalled": False,
        "windowsLaunch": "Use an owner-installed native .exe. For npm CLIs configure Node.exe as command and the absolute installed CLI JavaScript file as the first arg; .cmd and shell fallbacks are not executed."}


def enable(config, environment_id, worker, definition=None, allow_write=False):
    value = copy.deepcopy(config)
    env = next((item for item in value.get("environments", []) if item.get("id") == environment_id), None)
    if env is None or env.get("kind", "local") != "local":
        raise ValueError("Enablement requires an existing local owner environment")
    if not harness_registry.external(worker):
        raise ValueError("Select an ACP worker ID or muse_msp from --list-harnesses")
    if not env.get("writeAllowed") and not allow_write:
        raise ValueError("This environment is read-only. Pass --allow-harness-write only when authorizing owner-managed external tools here.")
    if definition is not None:
        if not isinstance(definition, dict):
            raise ValueError("Harness definition must be one configuration object")
        env.setdefault("harnessWorkers", {})[worker] = definition
    if worker not in env.setdefault("workers", ["command"]):
        env["workers"].append(worker)
    if allow_write:
        env["writeAllowed"] = True
    harness_registry.validate_environment(env)
    harness_registry.configuration(env, worker)
    return value


def write_configuration(path, expected_bytes, config):
    path = Path(path).expanduser().absolute()
    if path.is_symlink() or not path.is_file() or path.read_bytes() != expected_bytes:
        raise ValueError("Original owner configuration changed; no configuration was replaced")
    raw = (json.dumps(config, ensure_ascii=False, indent=2) + "\n").encode()
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".new", dir=path.parent)
    try:
        if os.name != "nt":
            os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if path.read_bytes() != expected_bytes:
            raise ValueError("Owner configuration changed while enablement was prepared")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def enable_installed(context, launch, environment_id, worker, definition=None, allow_write=False):
    """Update only the verified installation's fixed owner configuration.

    The caller holds the original user-session mutex. Native raw-byte CAS
    preserves the existing DACL and retains exact recovery material on an
    ambiguous replacement; no runtime or task is started by this action.
    """
    launch.check_current()
    before = context._raw(context.state, "companion.json", 262144)
    value = enable(json.loads(before.decode("utf-8-sig")), environment_id, worker, definition, allow_write)
    after = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(after) > 262144:
        raise ValueError("Installed owner configuration exceeds its existing byte bound")
    operation = str(uuid.uuid4())
    def record_intent(intent):
        raw = json.dumps({"protocol": "pocketworld.harness-owner-config.v1", "operationId": operation,
                          "environmentId": environment_id, "workerId": worker, "replacement": intent}).encode("utf-8")
        context.backend.create_private_snapshot(context.logs, operation + ".intent.json", raw,
                                                maximum=65536, current=launch.check_current)
    receipt = context.backend.compare_and_swap(context.state, "companion.json", before, after,
                                                launch.check_current, record_intent=record_intent)
    result = {"protocol": "pocketworld.harness-owner-config.v1", "operationId": operation,
              "environmentId": environment_id, "workerId": worker, "configurationChanged": receipt.get("verified") is True,
              "replacement": receipt, "packagesInstalled": False, "executablesStarted": False,
              "restartRequired": receipt.get("verified") is True}
    context.backend.create_private_snapshot(context.logs, operation + ".receipt.json",
        json.dumps(result).encode("utf-8"), maximum=65536, current=context.check_current_pointer)
    return result
