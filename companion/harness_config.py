"""Reviewed Harness configuration transactions on the original Companion supervisor.

No runner, account login, provider credential store, or inference probe is implemented here.
Plans/before-images use the original protected operations database; the phone receives only
redacted plans and immutable receipts. File consistency is explicitly OPTIMISTIC.
"""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import select
import subprocess
import time
try:
    import tomllib
except ModuleNotFoundError:  # Existing non-config Companion features remain usable on Python 3.10.
    tomllib = None
import uuid
from urllib.parse import urlsplit

from workspace_files import FileError

ADAPTER = "pocketworld-config-1"
SUPPORTED = {"codex": ("0.164.0-alpha.11", "codex-config-v1", "config.toml", "CODEX_HOME", "codex_app_server"),
             "claude": ("2.1.259", "claude-settings-v1", "settings.json", "CLAUDE_CONFIG_DIR", "claude")}
ABSENT = {"absent": True}
AUTH = {"codex": ("model_provider", "model_providers.pocketworld"),
        "claude": ("env.ANTHROPIC_BASE_URL", "env.ANTHROPIC_AUTH_TOKEN", "env.ANTHROPIC_API_KEY", "env.ANTHROPIC_MODEL")}
LIMIT = 262144
PROBE_TIMEOUT = 5


class ConfigError(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else encoded(value).encode()).hexdigest()


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value):
        raise ConfigError("An exact native identifier is required", 400)
    return value


def bounded(value, maximum=200):
    if not isinstance(value, str) or not value or len(value) > maximum or any(ord(c) < 32 for c in value):
        raise ConfigError("Invalid bounded configuration value", 400)
    return value


class JsonFields:
    """Small structural JSON span editor. Untouched bytes are never reserialized.

    Duplicate keys at every depth are rejected. Only known scalar leaves are edited;
    an absent parent is created, and an existing non-object parent is never replaced.
    """
    def __init__(self, raw):
        self.raw = raw
        try:
            self.value = json.loads(raw, object_pairs_hook=self.unique)
        except (ValueError, TypeError):
            raise ConfigError("Configuration JSON is invalid or has duplicate keys")
        if not isinstance(self.value, dict):
            raise ConfigError("Configuration must be an object")

    @staticmethod
    def unique(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                raise ValueError("duplicate key")
            out[key] = value
        return out

    def get(self, path):
        value = self.value
        for key in path.split("."):
            if not isinstance(value, dict):
                raise ConfigError("Known configuration parent is not an object")
            if key not in value:
                return ABSENT
            value = value[key]
        return {"value": value}

    def _object(self, start):
        decoder = json.JSONDecoder()
        entries, i = [], start + 1
        while True:
            while self.raw[i].isspace():
                i += 1
            if self.raw[i] == "}":
                return entries, i
            key_start = i
            key, i = decoder.raw_decode(self.raw, i)
            while self.raw[i].isspace():
                i += 1
            i += 1  # colon; full JSON was already validated
            while self.raw[i].isspace():
                i += 1
            value_start = i
            _, value_end = decoder.raw_decode(self.raw, i)
            i = value_end
            while self.raw[i].isspace():
                i += 1
            entries.append((key, key_start, value_start, value_end, i))
            if self.raw[i] == "}":
                return entries, i
            i += 1

    def set(self, path, desired):
        keys = path.split(".")
        start = len(self.raw) - len(self.raw.lstrip())
        for depth, key in enumerate(keys):
            entries, close = self._object(start)
            found = next((e for e in entries if e[0] == key), None)
            if depth < len(keys) - 1:
                if found is None:
                    if desired == ABSENT:
                        return self.raw
                    nested = desired["value"]
                    for child in reversed(keys[depth + 1:]):
                        nested = {child: nested}
                    return self._insert(entries, close, key, nested)
                if self.raw[found[2]] != "{":
                    raise ConfigError("Known configuration parent is not an object")
                start = found[2]
                continue
            if found is None:
                return self.raw if desired == ABSENT else self._insert(entries, close, key, desired["value"])
            if desired != ABSENT:
                return self.raw[:found[2]] + encoded(desired["value"]) + self.raw[found[3]:]
            index = entries.index(found)
            if len(entries) == 1:
                left, right = found[1], found[3]
            elif index < len(entries) - 1:
                left, right = found[1], entries[index + 1][1]
            else:
                left, right = entries[index - 1][4], found[3]
            return self.raw[:left] + self.raw[right:]
        raise AssertionError("empty path")

    def _insert(self, entries, close, key, value):
        addition = encoded(key) + ": " + encoded(value)
        # Keep original contents/indentation intact, even for compact documents.
        position = entries[-1][3] if entries else close
        return self.raw[:position] + (", " if entries else "") + addition + self.raw[position:]


class CodexFields:
    """Known Codex TOML leaves plus one exclusively owned provider table.

    tomllib validates all original syntax/duplicates. Complex alternative spellings of
    edited fields are rejected instead of guessing or replacing the whole document.
    """
    def __init__(self, raw):
        self.raw = raw
        if tomllib is None:
            raise ConfigError("Codex TOML configuration control requires Python 3.11 or newer")
        try:
            self.value = tomllib.loads(raw)
        except (ValueError, TypeError):
            raise ConfigError("Configuration TOML is invalid or has duplicate keys")
        if self.value.get("profile") is not None:
            raise ConfigError("An active Codex profile overlay must be reviewed separately")

    def get(self, path):
        if path == "model_providers.pocketworld":
            value = self.value.get("model_providers", {})
            if not isinstance(value, dict):
                raise ConfigError("Invalid provider namespace")
            return {"value": value["pocketworld"]} if "pocketworld" in value else ABSENT
        return {"value": self.value[path]} if path in self.value else ABSENT

    def provider_block(self):
        match = re.search(r"(?m)^\[model_providers\.pocketworld\][ \t]*(?:#[^\n]*)?\r?$", self.raw)
        if not match:
            return None
        tail = re.search(r"(?m)^\[", self.raw[match.end():])
        end = match.end() + tail.start() if tail else len(self.raw)
        return self.raw[match.start():end]

    def set(self, path, desired):
        if path == "model_providers.pocketworld":
            pattern = r"(?m)^\[model_providers\.pocketworld\][ \t]*(?:#[^\n]*)?\r?$"
            match = re.search(pattern, self.raw)
            present = self.get(path) != ABSENT
            if present and match is None:
                raise ConfigError("Provider namespace uses an unsupported TOML spelling")
            if match:
                tail = re.search(r"(?m)^\[", self.raw[match.end():])
                end = match.end() + tail.start() if tail else len(self.raw)
                if desired == ABSENT:
                    return self.raw[:match.start()] + self.raw[end:]
                return self.raw[:match.start()] + self._table(desired["value"]) + self.raw[end:]
            return self.raw if desired == ABSENT else self.raw + ("" if self.raw.endswith("\n") else "\n") + self._table(desired["value"])
        first_table = re.search(r"(?m)^\[", self.raw)
        end = first_table.start() if first_table else len(self.raw)
        head = self.raw[:end]
        match = re.search(r"(?m)^" + re.escape(path) + r"[ \t]*=[ \t]*([^\r\n]*)", head)
        if self.get(path) != ABSENT and match is None:
            raise ConfigError("Known field uses an unsupported TOML spelling")
        if match:
            if desired == ABSENT:
                finish = match.end() + (1 if self.raw[match.end():].startswith("\n") else 0)
                return self.raw[:match.start()] + self.raw[finish:]
            # A comment is outside JSON-compatible TOML basic string quotes.
            text = match.group(1)
            if text.lstrip().startswith(('"""', "'''")):
                raise ConfigError("Multiline spellings of edited TOML fields are read-only")
            quote, escaped, comment = None, False, len(text)
            for i, c in enumerate(text):
                if c in {'"', "'"} and (not escaped or quote == "'"):
                    quote = None if quote == c else c if quote is None else quote
                if c == "#" and quote is None:
                    comment = i
                    break
                escaped = c == "\\" and not escaped and quote == '"'
            suffix = text[comment:]
            return self.raw[:match.start(1)] + encoded(desired["value"]) + (" " if suffix else "") + suffix + self.raw[match.end():]
        if desired == ABSENT:
            return self.raw
        return self.raw[:end] + ("" if not end or self.raw[:end].endswith("\n") else "\n") + path + " = " + encoded(desired["value"]) + "\n" + self.raw[end:]

    @staticmethod
    def _table(value):
        keys = ("name", "base_url", "wire_api", "experimental_bearer_token")
        if set(value) != set(keys):
            raise ConfigError("Owned provider table has unreviewed fields")
        return "[model_providers.pocketworld]\n" + "".join(key + " = " + encoded(value[key]) + "\n" for key in keys)


class HarnessConfigs:
    def __init__(self, manager):
        self.manager = manager
        self.db = manager.ledger
        self.endpoints = {}  # ephemeral, never provider credentials or ChatGPT session tokens
        self.probed_identities = {}
        self.db.execute("CREATE TABLE IF NOT EXISTS harness_plans(plan_id TEXT PRIMARY KEY, operation_id TEXT UNIQUE NOT NULL, digest TEXT NOT NULL, private_plan TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS harness_receipts(operation_id TEXT PRIMARY KEY, plan_digest TEXT NOT NULL, receipt TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS harness_root_leases(resource_key TEXT PRIMARY KEY, operation_id TEXT NOT NULL, binding_id TEXT NOT NULL, job_id TEXT, state TEXT NOT NULL, proof TEXT NOT NULL DEFAULT '{}')")
        if "proof" not in {row[1] for row in self.db.execute("PRAGMA table_info(harness_root_leases)").fetchall()}:
            self.db.execute("ALTER TABLE harness_root_leases ADD COLUMN proof TEXT NOT NULL DEFAULT '{}'")
        self.db.commit()

    def _target(self, request, current=True):
        eid, bid = identifier(request.get("environmentId")), identifier(request.get("bindingId"))
        env = self.manager._environment(eid)
        if env.get("kind", "local") != "local" or os.name == "nt" or not hasattr(os, "O_NOFOLLOW"):
            raise ConfigError("Configuration control currently requires a POSIX local Companion target")
        binding = self.manager.environment_binding(eid)
        if current and request.get("environmentBinding") != binding:
            raise ConfigError("Environment generation changed; review the exact target again")
        entries = env.get("harnesses", [])
        target = next((entry for entry in entries if entry.get("bindingId") == bid), None)
        if target is None:
            raise ConfigError("Harness root is not explicitly admitted by this Companion", 403)
        target = dict(target)
        family = target.get("family")
        if family not in SUPPORTED:
            raise ConfigError("Unknown Harness family; configuration is read-only")
        raw_root = Path(bounded(target.get("configRoot"), 2048))
        if not raw_root.is_absolute() or raw_root != raw_root.resolve():
            raise ConfigError("Configuration root must be canonical and must not contain symlinks", 403)
        # The inherited secure directory walker also rejects symbolic-link ancestors.
        fd = self.manager.files.directory({"root": "/", "kind": "local"}, list(raw_root.parts[1:]))
        try:
            info = os.fstat(fd)
        finally:
            os.close(fd)
        target.update(root=str(raw_root), environmentId=eid, environmentBinding=binding,
                      rootIdentity=[info.st_dev, info.st_ino], bindingId=bid)
        target["resourceKey"] = digest([binding, str(raw_root)])
        target["physicalRootKey"] = digest([info.st_dev, info.st_ino])
        return env, target

    @staticmethod
    def _executable(target):
        executable = Path(bounded(target.get("executable"), 2048))
        if not executable.is_absolute() or not executable.is_file():
            raise ConfigError("Explicit Harness executable is missing")
        executable = executable.resolve()
        info = executable.stat()
        return executable, [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns]

    def _identity(self, target):
        """External --version IO. Caller must NOT hold the original manager lock."""
        executable, stamp = self._executable(target)
        # This non-inference probe is bounded and cannot inherit session credentials.
        env = {key: value for key, value in os.environ.items() if key in {"PATH", "SYSTEMROOT", "LANG", "LC_ALL"}}
        process, output, proof = None, bytearray(), None
        try:
            # No Popen context manager: its __exit__ could wait before our timeout handler.
            process = subprocess.Popen([str(executable), "--version"], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, start_new_session=True, bufsize=0)
            descriptor = process.stdout.fileno()
            os.set_blocking(descriptor, False)
            deadline = time.monotonic() + PROBE_TIMEOUT
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ConfigError("Harness version probe timed out")
                if not select.select([descriptor], [], [], min(.05, remaining))[0]:
                    continue
                part = os.read(descriptor, 512)
                if not part:
                    try:
                        process.wait(timeout=max(.001, deadline - time.monotonic()))
                    except subprocess.TimeoutExpired:
                        raise ConfigError("Harness version probe timed out")
                    break
                output.extend(part)
                if len(output) > 4096:
                    raise ConfigError("Harness version probe output exceeds the supported bound")
            if process.returncode != 0:
                raise ConfigError("Harness version probe did not return a version")
        finally:
            if process is not None:
                # This is exactly the process group we created above. Reuse the original
                # supervisor's bounded cleanup, including descendants holding stdout.
                proof = self.manager._terminate(process, hard=True)
                if process.stdout is not None:
                    process.stdout.close()
        if not proof or not proof.get("processGroupStoppedVerified"):
            raise ConfigError("Harness probe process-group cleanup was not verified")
        if self._executable(target) != (executable, stamp):
            raise ConfigError("Harness executable changed during its version probe")
        version = output.decode("utf-8", "strict").strip()
        family = target["family"]
        expected, schema, _, _, _ = SUPPORTED[family]
        pattern = r"codex(?:-cli)? " if family == "codex" else ""
        known = bool(re.fullmatch(pattern + re.escape(expected) + (r" \(Claude Code\)" if family == "claude" else ""), version))
        return {"family": family, "executable": str(executable), "executableIdentity": stamp,
                "version": version, "configSchemaVersion": schema if known else "UNKNOWN_SCHEMA", "adapterRevision": ADAPTER,
                "executionSite": "COMPANION", "environmentBinding": target["environmentBinding"], "bindingId": target["bindingId"],
                "rootIdentity": target["rootIdentity"], "scope": target.get("scope", "PROJECT_SESSION")}

    def _same_identity_now(self, target, identity):
        executable, stamp = self._executable(target)
        return identity["executable"] == str(executable) and identity["executableIdentity"] == stamp and identity["rootIdentity"] == target["rootIdentity"] and identity["environmentBinding"] == target["environmentBinding"] and identity["family"] == target["family"] and identity["bindingId"] == target["bindingId"] and identity["adapterRevision"] == ADAPTER

    def _approved_identity(self, request, target):
        identity = self.probed_identities.get(request.get("identityDigest"))
        if identity is None or not self._same_identity_now(target, identity):
            raise ConfigError("Exact version probe is missing/stale; probe the current Harness first")
        return copy.deepcopy(identity)

    def _read(self, target):
        name = SUPPORTED[target["family"]][2]
        try:
            raw = self.manager.files.raw({"root": target["root"], "kind": "local"}, name)
        except FileError as error:
            raise ConfigError(str(error), error.status)
        try:
            return None if raw is None else raw.decode("utf-8")
        except UnicodeDecodeError:
            raise ConfigError("Harness configuration is not UTF-8")

    @staticmethod
    def _editor(family, raw):
        return CodexFields(raw or "") if family == "codex" else JsonFields(raw if raw is not None else "{}\n")

    def _overrides(self, env, target):
        family = target["family"]
        variables = ("CODEX_PROFILE",) if family == "codex" else ("ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL", "CLAUDE_CODE_SETTINGS_PATH")
        if any(os.environ.get(key) for key in variables):
            raise ConfigError("Inherited configuration/auth override makes effective state unverified")
        if family == "claude":
            for root, name in ((target["root"], "managed-settings.json"), (env["root"], ".claude/settings.json"), (env["root"], ".claude/settings.local.json")):
                if not os.path.lexists(Path(root) / name.split("/")[0]):
                    continue
                if self.manager.files.raw({"root": root, "kind": "local"}, name) is not None:
                    raise ConfigError("A managed/project settings override requires a separate reviewed schema")
        if family == "codex" and self.manager.files.raw({"root": target["root"], "kind": "local"}, "auth.json") is not None:
            raise ConfigError("API-only configuration root must not contain a ChatGPT/account auth file")
        if target.get("scope", "PROJECT_SESSION") == "USER_CONFIG" and target.get("sharedConsumersManaged") is not True:
            raise ConfigError("Unknown shared config consumers; target remains read-only")

    def probe(self, request):
        if not self.manager.probe_slots.acquire(blocking=False):
            raise ConfigError("Harness capability probes are busy", 429)
        try:
            with self.manager.lock:
                env, target = self._target(request)
                snapshot = copy.deepcopy(target)
            identity = self._identity(snapshot)  # external process IO is outside the manager lock
            with self.manager.lock:
                env, target = self._target(request)
                if target != snapshot or not self._same_identity_now(target, identity) or self.manager.closed:
                    raise ConfigError("Harness environment/executable changed while probing")
                self.probed_identities[digest(identity)] = copy.deepcopy(identity)
                while len(self.probed_identities) > 80:
                    self.probed_identities.pop(next(iter(self.probed_identities)))
                return self._probe_result(env, target, identity)
        finally:
            self.manager.probe_slots.release()

    def _probe_result(self, env, target, identity):
        with self.manager.lock:
            raw = self._read(target)
            readable, reason = True, ""
            try:
                self._editor(target["family"], raw)
                self._overrides(env, target)
            except (ConfigError, FileError) as error:
                readable, reason = False, str(error)
            known = identity["configSchemaVersion"] != "UNKNOWN_SCHEMA"
            return {"identity": identity, "identityDigest": digest(identity), "bindingId": target["bindingId"],
                    "configRoot": target["root"], "fileDigest": self._file_digest(raw), "reason": reason,
                    "capabilities": {"detected": True, "configReadable": readable, "configWritable": readable and known and bool(env.get("writeAllowed")),
                                     "protocolVerified": False, "modelInferenceVerified": False, "toolRoundTripVerified": False,
                                     "runtimeDriverSupported": target["family"] in {"codex", "claude"}, "approvalBridgeVerified": False,
                                     "resumeVerified": False, "cancelVerified": False, "assetProjection": False},
                    "writeConsistency": "OPTIMISTIC", "effectiveReadback": False, "runtimeVerified": False}

    @staticmethod
    def _file_digest(raw):
        return "absent" if raw is None else digest(raw.encode())

    def register_endpoint(self, lease):
        """Called only by the original gateway bridge owner, never a Companion HTTP API.

        This does not issue/extend authority. T05 must supply its original bounded native
        gateway lease. Credentials are memory-only, and must be re-registered on restart.
        """
        keys = {"leaseId", "endpoint", "accessToken", "expiresAt", "environmentBinding", "profileDigest", "taskId", "runId", "epoch", "audience", "purpose"}
        if set(lease) not in (keys, keys | {"configurationOwner", "generation", "bindingId"}) or lease["expiresAt"] <= self.manager.now() or not isinstance(lease["epoch"], int):
            raise ConfigError("An unexpired exact gateway lease is required", 403)
        if lease["audience"] != "OWNER_PRIVATE" or lease["purpose"] != "EXTERNAL_RELAY":
            raise ConfigError("Configuration control cannot admit external-group or APP_OWNER gateway authority; use the original verified relay owner", 403)
        endpoint = urlsplit(lease["endpoint"])
        if endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1" or not endpoint.port or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment or endpoint.path not in {"", "/v1"}:
            raise ConfigError("Only the target's reviewed loopback gateway endpoint is accepted", 403)
        identifier(lease["leaseId"])
        if len(bounded(lease["accessToken"], 512)) < 32:
            raise ConfigError("A bounded scoped gateway credential is required", 403)
        with self.manager.lock:
            self.endpoints[lease["leaseId"]] = dict(lease)

    def _lease(self, request, target):
        lease = self.endpoints.get(identifier(request.get("endpointLeaseRef")))
        if lease is None or lease["expiresAt"] <= self.manager.now():
            raise ConfigError("Gateway lease is missing, expired, or revoked", 403)
        if "gatewayOwnerBinding" in request:
            owner = lease.get("configurationOwner")
            expected = (owner | {"leaseId": lease["leaseId"], "generation": lease["generation"]}) if owner else None
            if expected is None or request["gatewayOwnerBinding"] != expected or any(request.get(k) != owner[k] for k in ("taskId", "runId", "epoch")) or request.get("bindingId") != lease["bindingId"]:
                raise ConfigError("Configuration owner does not match the registered native parent/child lease", 403)
        elif "configurationOwner" in lease:
            raise ConfigError("This parent lease requires its exact registered child configuration owner", 403)
        else:
            for key in ("taskId", "runId", "epoch"):
                if request.get(key) != lease[key]:
                    raise ConfigError("Configuration request does not match the original gateway lease", 403)
        for key in ("environmentBinding", "profileDigest"):
            if request.get(key) != lease[key]:
                raise ConfigError("Configuration request changed gateway environment or Profile", 403)
        if lease["environmentBinding"] != target["environmentBinding"]:
            raise ConfigError("Gateway lease target generation changed", 403)
        return lease

    def plan(self, request):
        allowed = {"operationId", "bindingId", "environmentId", "environmentBinding", "identityDigest", "profileDigest", "endpointLeaseRef", "taskId", "runId", "epoch", "model"}
        if "gatewayOwnerBinding" in request:
            allowed.add("gatewayOwnerBinding")
        if set(request) != allowed:
            raise ConfigError("Plan accepts exact native references, not paths, credentials, arbitrary fields or execution grants", 400)
        with self.manager.lock:
            previous = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (identifier(request["operationId"]),)).fetchone()
            if previous:
                original = json.loads(previous[0])
                if original["requestFingerprint"] != digest(request):
                    raise ConfigError("Original operationId was already bound to another exact plan")
                return self._public_plan(original)
            env, target = self._target(request)
            if not env.get("writeAllowed"):
                raise ConfigError("Harness configuration writes are not enabled", 403)
            identity = self._approved_identity(request, target)
            if digest(identity) != request["identityDigest"] or identity["configSchemaVersion"] == "UNKNOWN_SCHEMA":
                raise ConfigError("Harness identity/schema changed; no configuration write is supported")
            self._overrides(env, target)
            lease = self._lease(request, target)
            model = bounded(request["model"])
            raw = self._read(target)
            editor = self._editor(target["family"], raw)
            ownership = self.db.execute("SELECT operation_id FROM harness_root_leases WHERE resource_key=?", (target["resourceKey"],)).fetchone()
            previous = None
            if ownership:
                previous = json.loads(self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (ownership[0],)).fetchone()[0])
                if self.operation(ownership[0])["state"] != "COMMITTED":
                    raise ConfigError("Original configuration operation requires recovery, not a new apply")
                if any(editor.get(key) != value for key, value in previous["after"].items()):
                    raise ConfigError("Previously managed fields drifted; review recovery before reconfiguration")
                if target["family"] == "codex" and editor.provider_block() != self._editor("codex", previous["afterRaw"]).provider_block():
                    raise ConfigError("Owned provider block has outside edits/comments; preserve them for recovery review")
            if target["family"] == "codex":
                if editor.get("model_providers.pocketworld") != ABSENT and previous is None:
                    raise ConfigError("The pocketworld provider namespace already exists; ownership cannot be assumed")
                endpoint = lease["endpoint"].removesuffix("/v1") + "/v1"
                changes = {"model_provider": {"value": "pocketworld"}, "model": {"value": model},
                           "model_providers.pocketworld": {"value": {"name": "PocketWorld", "base_url": endpoint, "wire_api": "responses", "experimental_bearer_token": lease["accessToken"]}}}
            else:
                changes = {"env.ANTHROPIC_BASE_URL": {"value": lease["endpoint"].removesuffix("/v1")}, "env.ANTHROPIC_AUTH_TOKEN": {"value": lease["accessToken"]},
                           "env.ANTHROPIC_API_KEY": ABSENT, "env.ANTHROPIC_MODEL": ABSENT, "model": {"value": model}}
            before = {key: editor.get(key) for key in changes}
            after = raw
            for key, value in changes.items():
                after = self._editor(target["family"], after).set(key, value)
            plan = {"operationId": identifier(request["operationId"]), "requestFingerprint": digest(request), "planId": uuid.uuid4().hex,
                    "identity": identity, "target": target, "request": copy.deepcopy(request), "beforeRaw": raw, "afterRaw": after,
                    "before": before, "after": changes, "authGroups": [list(AUTH[target["family"]])], "expiresAt": min(lease["expiresAt"], self.manager.now() + 300000),
                    "restoreOf": None, "conflicts": [], "writeConsistency": "OPTIMISTIC",
                    "gatewayScope": {"audience": lease["audience"], "purpose": lease["purpose"], "billingBackendVerified": False}}
            plan["replacesOperation"] = ownership[0] if ownership else None
            plan["restoreBefore"] = previous.get("restoreBefore", previous["before"]) if previous else copy.deepcopy(before)
            plan["restoreBeforeRaw"] = previous.get("restoreBeforeRaw", previous["beforeRaw"]) if previous else raw
            return self._save_plan(plan)

    def _save_plan(self, plan):
        previous = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (plan["operationId"],)).fetchone()
        if previous:
            old = json.loads(previous[0])
            if old["requestFingerprint"] != plan["requestFingerprint"]:
                raise ConfigError("Original operationId was already bound to another exact plan")
            return self._public_plan(old)
        for table in ("operations", "file_operations"):
            if self.db.execute("SELECT 1 FROM " + table + " WHERE operation_id=?", (plan["operationId"],)).fetchone():
                raise ConfigError("Original operationId belongs to another operation kind")
        plan["planDigest"] = digest(plan)
        self.db.execute("INSERT INTO harness_plans VALUES (?,?,?,?)", (plan["planId"], plan["operationId"], plan["planDigest"], encoded(plan)))
        self.db.commit()
        return self._public_plan(plan)

    def _public_plan(self, plan):
        def masked(path, value):
            if value == ABSENT:
                return ABSENT
            if path in AUTH[plan["identity"]["family"]]:
                return {"value": "[protected authentication tuple]"}
            return value
        return {"planId": plan["planId"], "operationId": plan["operationId"], "planDigest": plan["planDigest"], "identity": plan["identity"],
                "environmentId": plan["target"]["environmentId"], "bindingId": plan["target"]["bindingId"], "expectedFileDigest": self._file_digest(plan["beforeRaw"]),
                "profileDigest": plan["request"]["profileDigest"], "endpointLeaseRef": plan["request"]["endpointLeaseRef"], "credentialRefType": "GATEWAY_ACCESS",
                "filePlans": [{"pathRef": SUPPORTED[plan["identity"]["family"]][2], "operations": [{"kind": "remove_field" if value == ABSENT else "set_field", "field": key,
                               "before": masked(key, plan["before"][key]), "after": masked(key, plan["after"][key])} for key in sorted(plan["after"]) for value in [plan["after"][key]]]}],
                "scopeDelta": {"scope": plan["identity"]["scope"], "configRoot": plan["target"]["root"], "executionAuthorized": False},
                "gatewayScope": plan["gatewayScope"],
                **({"gatewayOwnerBinding": plan["request"]["gatewayOwnerBinding"]} if "gatewayOwnerBinding" in plan["request"] else {}),
                "authRestoreGroups": [{"name": "effective_authentication", "fields": group} for group in plan["authGroups"]],
                "expiresAt": plan["expiresAt"], "restoreOf": plan["restoreOf"], "conflicts": plan["conflicts"], "writeConsistency": "OPTIMISTIC",
                "runtimeVerified": False, "nextSessionRequired": True}

    def operation(self, operation):
        with self.manager.lock:
            return self._operation(operation)

    def _operation(self, operation):
        row = self.db.execute("SELECT receipt FROM harness_receipts WHERE operation_id=?", (identifier(operation),)).fetchone()
        if row:
            return json.loads(row[0])
        plan = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (operation,)).fetchone()
        if plan:
            return {"operationId": operation, "state": "PREPARED", "plan": self._public_plan(json.loads(plan[0])), "configApplied": False, "runtimeVerified": False}
        return {"operationId": operation, "state": "not_found", "configApplied": False, "runtimeVerified": False}

    def _receipt(self, plan, state, **values):
        receipt = {"operationId": plan["operationId"], "planId": plan["planId"], "planDigest": plan["planDigest"], "state": state,
                   "restoreOf": plan["restoreOf"],
                   "environmentId": plan["target"]["environmentId"], "environmentBinding": plan["identity"]["environmentBinding"], "bindingId": plan["target"]["bindingId"],
                   "configApplied": False, "effectiveReadback": False, "runtimeVerified": False, "nextSessionRequired": True, "writeConsistency": "OPTIMISTIC",
                   "files": [{"pathRef": SUPPORTED[plan["identity"]["family"]][2], "state": state}], **values}
        self.db.execute("INSERT OR REPLACE INTO harness_receipts VALUES (?,?,?)", (plan["operationId"], plan["planDigest"], encoded(receipt)))
        self.db.commit()
        return receipt

    def apply(self, plan_id, request):
        if set(request) != {"operationId", "planDigest", "environmentId", "environmentBinding", "bindingId"}:
            raise ConfigError("Apply accepts only the approved original plan identity", 400)
        with self.manager.lock:
            row = self.db.execute("SELECT private_plan FROM harness_plans WHERE plan_id=?", (identifier(plan_id),)).fetchone()
            if row is None:
                raise ConfigError("Original plan not found", 404)
            plan = json.loads(row[0])
            if request["operationId"] != plan["operationId"] or request["planDigest"] != plan["planDigest"]:
                raise ConfigError("Approved operation/plan digest does not match")
            for key in ("bindingId", "environmentId", "environmentBinding"):
                if request.get(key) != plan["target"][key]:
                    raise ConfigError("Approved plan target does not match")
            old = self.db.execute("SELECT receipt FROM harness_receipts WHERE operation_id=?", (plan["operationId"],)).fetchone()
            if old:
                return json.loads(old[0])  # in-flight/unknown is reconciled, never blindly replayed
            env, target = self._target(request)
            if self.manager.now() >= plan["expiresAt"] or not self._same_identity_now(target, plan["identity"]) or target["rootIdentity"] != plan["target"]["rootIdentity"]:
                raise ConfigError("Approved plan expired or exact executable/root changed")
            self._overrides(env, target)
            if plan["conflicts"]:
                return self._receipt(plan, "RECOVERY_REQUIRED", conflicts=plan["conflicts"])
            if plan["restoreOf"] is None:
                self._lease(plan["request"], target)
            elif plan.get("restoreResourceKey") != target["resourceKey"]:
                original = json.loads(self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (plan["restoreOf"],)).fetchone()[0])
                self._cold_restore_proof(original, target)
            if self._active_job(target["resourceKey"]):
                raise ConfigError("CONFIG_ROOT_IN_USE: original job has not settled")
            held = self.db.execute("SELECT operation_id FROM harness_root_leases WHERE resource_key=?", (target["resourceKey"],)).fetchone()
            if held and held[0] != (plan["restoreOf"] or plan.get("replacesOperation") or plan["operationId"]):
                raise ConfigError("CONFIG_ROOT_IN_USE: another original operation owns this root")
            for (other_operation,) in self.db.execute("SELECT operation_id FROM harness_root_leases WHERE resource_key!=?", (target["resourceKey"],)).fetchall():
                other = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (other_operation,)).fetchone()
                other_lease = self.db.execute("SELECT state FROM harness_root_leases WHERE operation_id=?", (other_operation,)).fetchone()
                if other and other_lease and other_lease[0] != "CONFIGURED" and json.loads(other[0])["target"]["physicalRootKey"] == target["physicalRootKey"]:
                    raise ConfigError("CONFIG_ROOT_IN_USE: an environment alias owns this physical root")
            if self._read(target) != plan["beforeRaw"]:
                return self._receipt(plan, "CONFLICT", drifted=True)
            if not held and plan["restoreOf"] and plan.get("restoreResourceKey") != target["resourceKey"]:
                changed = self.db.execute("UPDATE harness_root_leases SET resource_key=?,state='HELD' WHERE resource_key=? AND operation_id=? AND job_id IS NULL AND state='CONFIGURED'", (target["resourceKey"], plan["restoreResourceKey"], plan["restoreOf"])).rowcount
                if changed != 1:
                    raise ConfigError("Original settled configuration lease changed before cold restoration")
                self.db.commit()
                held = (plan["restoreOf"],)
            if not held:
                self.db.execute("INSERT INTO harness_root_leases(resource_key,operation_id,binding_id,job_id,state,proof) VALUES (?,?,?,NULL,'HELD','{}')", (target["resourceKey"], plan["operationId"], target["bindingId"]))
                self.db.commit()
            else:
                self.db.execute("UPDATE harness_root_leases SET state='HELD' WHERE resource_key=?", (target["resourceKey"],))
                self.db.commit()
            self._receipt(plan, "PREPARED")
            self._receipt(plan, "APPLYING")
            try:
                self._replace(target, plan["beforeRaw"], plan["afterRaw"])
                actual = self._read(target)
                if actual != plan["afterRaw"]:
                    return self._receipt(plan, "RECOVERY_REQUIRED", drifted=True, unknown=True)
                editor = self._editor(target["family"], actual)
                if any(editor.get(key) != value for key, value in plan["after"].items()):
                    return self._receipt(plan, "RECOVERY_REQUIRED", unknown=True)
                self._receipt(plan, "READBACK_VERIFIED", configApplied=True, effectiveReadback=True, actualFileDigest=self._file_digest(actual))
                receipt = self._receipt(plan, "COMMITTED", configApplied=True, effectiveReadback=True, actualFileDigest=self._file_digest(actual), restored=plan["restoreOf"] is not None)
                if plan["restoreOf"]:
                    self.db.execute("DELETE FROM harness_root_leases WHERE resource_key=? AND operation_id=?", (target["resourceKey"], plan["restoreOf"]))
                else:
                    proof = {"configurationOperationId": plan["operationId"], "configReceiptDigest": digest(receipt), "jobNeverBound": True, "notStartedConfirmed": True}
                    self.db.execute("UPDATE harness_root_leases SET operation_id=?,job_id=NULL,state='CONFIGURED',proof=? WHERE resource_key=?", (plan["operationId"], encoded(proof), target["resourceKey"]))
                self.db.commit()
                return receipt
            except (OSError, ConfigError, FileError):
                return self._receipt(plan, "RECOVERY_REQUIRED", unknown=True)

    def _replace(self, target, before, after):
        directory = self.manager.files.directory({"root": target["root"], "kind": "local"}, [])
        name = SUPPORTED[target["family"]][2]
        temporary = ".pocketworld-config-" + uuid.uuid4().hex
        try:
            if after is not None:
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
                with os.fdopen(fd, "wb") as stream:
                    stream.write(after.encode())
                    stream.flush()
                    os.fsync(stream.fileno())
            if self._read(target) != before:
                raise ConfigError("Configuration drifted before replacement")
            if after is None:
                os.unlink(name, dir_fd=directory)
            else:
                os.replace(temporary, name, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass
            os.close(directory)

    def restore_plan(self, operation, request):
        if set(request) != {"operationId", "environmentId", "environmentBinding", "bindingId", "identityDigest"}:
            raise ConfigError("Restore requires an exact new native operation and current target identity", 400)
        with self.manager.lock:
            row = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (identifier(operation),)).fetchone()
            if row is None or self.operation(operation)["state"] not in {"COMMITTED", "RECOVERY_REQUIRED", "APPLYING", "READBACK_VERIFIED"}:
                raise ConfigError("No original apply receipt can be restored")
            original = json.loads(row[0])
            env, target = self._target(request)
            identity = self._approved_identity(request, target)
            if digest(identity) != request["identityDigest"] or any(identity[k] != original["identity"][k] for k in ("family", "executable", "executableIdentity", "configSchemaVersion", "adapterRevision", "bindingId", "rootIdentity")):
                raise ConfigError("Restoration target identity differs from the original apply")
            if target["environmentBinding"] != original["target"]["environmentBinding"]:
                self._cold_restore_proof(original, target)
            self._overrides(env, target)
            raw = self._read(target)
            editor = self._editor(target["family"], raw)
            conflicts, changes, grouped = [], {}, set()
            for group in original["authGroups"]:
                grouped.update(group)
                block_drift = target["family"] == "codex" and editor.provider_block() != self._editor("codex", original["afterRaw"]).provider_block()
                if block_drift or any(editor.get(key) != original["after"][key] for key in group):
                    conflicts.append({"group": "effective_authentication", "fields": group, "reason": "authentication tuple drifted; no member restored"})
                elif any(self._revoked_before(key, original.get("restoreBefore", original["before"])[key]) for key in group):
                    conflicts.append({"group": "effective_authentication", "fields": group, "reason": "restoration would revive a managed/expired credential"})
                else:
                    changes.update({key: original.get("restoreBefore", original["before"])[key] for key in group})
            for key in original["after"]:
                if key not in grouped:
                    if editor.get(key) == original["after"][key]:
                        changes[key] = original.get("restoreBefore", original["before"])[key]
                    else:
                        conflicts.append({"field": key, "reason": "user/external change retained"})
            after = raw
            for key, value in changes.items():
                after = self._editor(target["family"], after).set(key, value)
            if target["family"] == "claude" and self._editor("claude", original.get("restoreBeforeRaw", original["beforeRaw"])).get("env") == ABSENT and self._editor("claude", after).get("env") == {"value": {}}:
                after = self._editor("claude", after).set("env", ABSENT)
                changes["env"] = ABSENT
            if original.get("restoreBeforeRaw", original["beforeRaw"]) is None:
                # Delete our new file only if no outside addition occurred.
                if raw == original["afterRaw"]:
                    after = None
                elif not conflicts:
                    conflicts.append({"field": "owned_file", "reason": "new user content retained; file must not be deleted"})
            plan = {**original, "planId": uuid.uuid4().hex, "operationId": identifier(request["operationId"]), "requestFingerprint": digest(request), "identity": identity,
                    "target": target, "beforeRaw": raw, "afterRaw": after, "before": {key: editor.get(key) for key in changes}, "after": changes,
                    "expiresAt": self.manager.now() + 300000, "restoreOf": operation, "restoreResourceKey": original["target"]["resourceKey"], "conflicts": conflicts}
            plan.pop("planDigest", None)
            return self._save_plan(plan)

    def _cold_restore_proof(self, original, target):
        receipt = self.operation(original["operationId"])
        row = self.db.execute("SELECT operation_id,job_id,state,proof FROM harness_root_leases WHERE resource_key=?", (original["target"]["resourceKey"],)).fetchone()
        if receipt["state"] != "COMMITTED" or not row or row[0] != original["operationId"] or row[1] is not None or row[2] != "CONFIGURED" or target["physicalRootKey"] != original["target"]["physicalRootKey"]:
            raise ConfigError("Restarted target lacks original COMMITTED/CONFIGURED no-active-job evidence")
        proof = json.loads(row[3])
        if proof.get("configurationOperationId") != original["operationId"] or proof.get("configReceiptDigest") != digest(receipt):
            raise ConfigError("Original configuration receipt/cleanup proof is missing or changed")
        never_started = proof.get("jobNeverBound") is True and proof.get("notStartedConfirmed") is True
        settled = proof.get("jobStatus") in {"completed", "failed", "cancelled", "blocked"} and proof.get("processCleanupProof", {}).get("processGroupStoppedVerified") is True
        if not (never_started or settled):
            raise ConfigError("Original process exit was not verified; missing PID does not release the root")
        # No endpoint is registered and no grant/token lifetime is extended here. Only
        # an explicitly reviewed compensation plan may move to the new generation.

    def _revoked_before(self, key, value):
        # Original provider references may be restored, but never a prior managed token.
        if value == ABSENT:
            return False
        for (raw,) in self.db.execute("SELECT private_plan FROM harness_plans").fetchall():
            historical = json.loads(raw)
            if historical["restoreOf"] is not None:
                continue
            for field, after in historical["after"].items():
                if ("token" in field.lower() or field == "model_providers.pocketworld") and after != ABSENT and (value == after or isinstance(value.get("value"), str) and value["value"] in encoded(after)):
                    return True
        return False

    def _active_job(self, resource):
        row = self.db.execute("SELECT job_id,state FROM harness_root_leases WHERE resource_key=?", (resource,)).fetchone()
        if not row:
            return False
        # Only the original finally hook with real resource-exit proof releases ACTIVE.
        # A status string, absent PID, missing job document or restart is not that proof.
        return row[1] in {"ACTIVE", "UNKNOWN"}

    def job_environment(self, job, request, env):
        """Original submit/_run hook. Lease remains held across the whole original job.

        This returns environment overrides, not a new execution mechanism. No endpoint,
        token, model or config content supplied by the submitter is accepted here.
        """
        operation = request.get("harnessConfigOperationId")
        group = request.get("inferenceAudience") == "EXTERNAL_GROUP" or "groupInputSource" in request or "externalGroupApiMode" in request
        if group and (operation is not None or "harnessConfigOperationId" in request or "harnessOwnerBinding" in request):
            raise ConfigError("Group API-only account proof cannot be reused with a different Harness configuration/home", 403)
        if operation is None:
            for entry in env.get("harnesses", []):
                if entry.get("scope") == "USER_CONFIG" and SUPPORTED.get(entry.get("family"), (None,) * 5)[4] == job.get("worker"):
                    target_request = {"environmentId": job["environmentId"], "bindingId": entry["bindingId"], "environmentBinding": self.manager.environment_binding(job["environmentId"])}
                    _, target = self._target(target_request)
                    if self.db.execute("SELECT 1 FROM harness_root_leases WHERE resource_key=?", (target["resourceKey"],)).fetchone():
                        raise ConfigError("CONFIG_ROOT_IN_USE: unbound shared consumer cannot dispatch")
                    for (op,) in self.db.execute("SELECT operation_id FROM harness_root_leases").fetchall():
                        other = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (op,)).fetchone()
                        if other and json.loads(other[0])["target"]["physicalRootKey"] == target["physicalRootKey"]:
                            raise ConfigError("CONFIG_ROOT_IN_USE: unbound alias shares a managed root")
            return {}
        row = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (identifier(operation),)).fetchone()
        if not row:
            raise ConfigError("Original config operation is missing")
        plan = json.loads(row[0])
        receipt = self.operation(operation)
        _, target = self._target(plan["request"])
        if receipt["state"] != "COMMITTED" or plan["restoreOf"] or job["environmentId"] != target["environmentId"] or job["worker"] != SUPPORTED[target["family"]][4]:
            raise ConfigError("Original job does not match the committed Harness configuration")
        self._lease(plan["request"], target)
        if request.get("harnessOwnerBinding") != {key: plan["request"][key] for key in ("taskId", "runId", "epoch", "profileDigest", "endpointLeaseRef")}:
            raise ConfigError("Original job lacks the exact native configuration owner binding", 403)
        held = self.db.execute("SELECT operation_id,job_id FROM harness_root_leases WHERE resource_key=?", (target["resourceKey"],)).fetchone()
        if held is None or held[0] != operation or held[1] not in {None, job["id"]}:
            raise ConfigError("CONFIG_ROOT_IN_USE: original root is already bound to another job")
        if not self._same_identity_now(target, plan["identity"]) or self._read(target) != plan["afterRaw"]:
            self._receipt(plan, "RECOVERY_REQUIRED", drifted=True)
            raise ConfigError("Harness identity/config drifted; managed dispatch blocked")
        for resource, operation_id, state in self.db.execute("SELECT resource_key,operation_id,state FROM harness_root_leases WHERE resource_key!=?", (target["resourceKey"],)).fetchall():
            other = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (operation_id,)).fetchone()
            if state != "CONFIGURED" and other and json.loads(other[0])["target"]["physicalRootKey"] == target["physicalRootKey"]:
                raise ConfigError("CONFIG_ROOT_IN_USE: active environment alias shares this root")
        proof = {"configurationOperationId": operation, "jobId": job["id"], "jobNeverBound": False}
        self.db.execute("UPDATE harness_root_leases SET job_id=?,state='ACTIVE',proof=? WHERE resource_key=?", (job["id"], encoded(proof), target["resourceKey"]))
        self.db.commit()
        job["harnessConfigOperationId"] = operation
        callback = getattr(self.manager, "harness_endpoint_job_bound", None)
        if callback is not None:
            callback(plan["request"]["endpointLeaseRef"], job, plan["request"])
        return {SUPPORTED[target["family"]][3]: target["root"]}

    def settle_job(self, job, process_group_stopped):
        """Original supervisor finally hook, after its real cleanup proof, not PID absence."""
        if process_group_stopped is not True or job.get("status") not in {"completed", "failed", "cancelled", "blocked"}:
            return
        cleanup = job.get("processCleanupProof", {})
        if cleanup.get("processGroupStoppedVerified") is not True:
            return
        operation = job.get("harnessConfigOperationId")
        proof = {"configurationOperationId": operation, "configReceiptDigest": digest(self.operation(operation)), "jobId": job["id"], "jobStatus": job["status"], "processCleanupProof": copy.deepcopy(cleanup), "jobNeverBound": False}
        self.db.execute("UPDATE harness_root_leases SET job_id=NULL,state='CONFIGURED',proof=? WHERE job_id=? AND state='ACTIVE'", (encoded(proof), job["id"]))
        self.db.commit()
        operation = job.get("harnessConfigOperationId")
        row = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (operation,)).fetchone() if operation else None
        if row:
            lease_id = json.loads(row[0])["request"]["endpointLeaseRef"]
            self.endpoints.pop(lease_id, None)
            job["harnessEndpointLeaseState"] = "revocation_required"
            callback = getattr(self.manager, "harness_endpoint_finished", None)
            if callback is not None:
                revoked = callback(lease_id, job["id"])
                job["harnessEndpointLeaseState"] = "revoked_by_original_bridge" if revoked is True else "not_bridge_registered"

    def job_live(self, job):
        operation = job.get("harnessConfigOperationId")
        if not operation:
            return True
        row = self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (operation,)).fetchone()
        if not row:
            return False
        plan = json.loads(row[0])
        try:
            _, target = self._target(plan["request"])
            self._lease(plan["request"], target)
            if self._read(target) != plan["afterRaw"]:
                self._receipt(plan, "RECOVERY_REQUIRED", drifted=True)
                return False
            return True
        except (ConfigError, FileError, OSError):
            return False

    def redact(self, text):
        # Only scoped gateway values are available here; no provider/login credential
        # is imported into this service. Before-images are never a logging source.
        for lease in list(self.endpoints.values()):
            text = text.replace(lease["accessToken"], "[gateway credential hidden]")
        bridge = getattr(self.manager, "harness_gateway", None)
        return bridge.redact(text) if bridge is not None else text

    def redaction_tail(self):
        bridge = getattr(self.manager, "harness_gateway", None)
        return max(max([len(self.manager.token), *(len(lease["accessToken"]) for lease in self.endpoints.values())]) - 1, bridge.redaction_tail() if bridge is not None else 0)

    def effective_state(self, request):
        with self.manager.lock:
            _, target = self._target(request)
            rows = self.db.execute("SELECT operation_id,job_id,state FROM harness_root_leases WHERE resource_key=?", (target["resourceKey"],)).fetchone()
            if not rows:
                return {"bindingId": target["bindingId"], "managed": False, "runtimeVerified": False, "effectiveReadback": False}
            plan = json.loads(self.db.execute("SELECT private_plan FROM harness_plans WHERE operation_id=?", (rows[0],)).fetchone()[0])
            drift = self._read(target) != plan["afterRaw"] or not self._same_identity_now(target, plan["identity"])
            return {"bindingId": target["bindingId"], "operationId": rows[0], "jobId": rows[1], "managed": True, "drifted": drift,
                    "configApplied": self.operation(rows[0])["configApplied"], "effectiveReadback": not drift,
                    "nextSessionRequired": True, "runtimeVerified": False, "writeConsistency": "OPTIMISTIC"}


def route(manager, parts, post, request, query, error_type):
    """API01–06, called inside the original authenticated CompanionHandler only."""
    service = manager.harness_configs
    try:
        if post and parts == ["v1", "harnesses", "probe"]:
            return service.probe(request)
        if post and parts == ["v1", "harness-config", "plans"]:
            return service.plan(request)
        if post and len(parts) == 5 and parts[:3] == ["v1", "harness-config", "plans"] and parts[4] == "apply":
            return service.apply(parts[3], request)
        if len(parts) == 4 and parts[:3] == ["v1", "harness-config", "operations"] and not post:
            return service.operation(parts[3])
        if len(parts) == 5 and parts[:3] == ["v1", "harness-config", "operations"] and parts[4] == "restore-plan" and post:
            return service.restore_plan(parts[3], request)
        if len(parts) == 4 and parts[:2] == ["v1", "harnesses"] and parts[3] == "effective-state" and not post:
            return service.effective_state({**query, "bindingId": parts[2]})
        raise ConfigError("Harness configuration endpoint not found", 404)
    except (ConfigError, FileError) as error:
        raise error_type(str(error), error.status)
