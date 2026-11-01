"""Closed external-group CLI auth facts. No credential value or general account identity is exported.

This only proves the observed Codex API-key mode and reviewed local configuration
revision. It does not prove a final billing backend, licensing permission, cost, or OS
isolation. Unknown keychain/custom-provider sources are deliberately unsupported.
"""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
try:
    import tomllib
except ImportError:
    tomllib = None

VARIABLES = frozenset({"OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL"})
CREDENTIALS = frozenset({"OPENAI_API_KEY", "CODEX_API_KEY"})
CLEAR_UNSELECTED = VARIABLES | {"CODEX_ACCESS_TOKEN", "OPENAI_IDENTITY_TOKEN_FILE", "OPENAI_WORKLOAD_IDENTITY_CONTEXT", "AZURE_OPENAI_API_KEY"}
MAX_AUTH_FILE = 1_048_576


class Unavailable(Exception):
    pass


def _configuration(env):
    home, variables = env.get("codexHome"), env.get("codexProviderVariables", [])
    if os.name == "nt" or not hasattr(os, "O_NOFOLLOW") or tomllib is None or env.get("kind", "local") != "local" or not isinstance(home, str) or not Path(home).is_absolute() or len(home.encode()) > 2048:
        raise Unavailable("External group inputs require an explicitly configured local Codex home and supported exact-file reads")
    if not isinstance(variables, list) or len(variables) > 3 or any(not isinstance(name, str) or name not in VARIABLES for name in variables) or len(set(variables)) != len(variables):
        raise Unavailable("Only explicitly selected supported Codex provider variables can participate")
    return home, sorted(variables)


def configured(env):
    try:
        _configuration(env)
        return True
    except Unavailable:
        return False


def _read(directory, name):
    try:
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
    except FileNotFoundError:
        return None
    with os.fdopen(descriptor, "rb") as source:
        before = os.fstat(source.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > MAX_AUTH_FILE:
            raise Unavailable("Configured Codex auth/config original is linked, special, or exceeds its bounded read")
        raw = source.read(MAX_AUTH_FILE + 1)
        after = os.fstat(source.fileno())
        named = os.stat(name, dir_fd=directory, follow_symlinks=False)
        identity = lambda value: (value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
        if len(raw) != before.st_size or identity(before) != identity(after) or identity(after) != identity(named):
            raise Unavailable("Configured Codex auth/config changed during the exact read")
        return raw, identity(after)


def generation(manager, env):
    """Salted process-local opaque generation. Reads two exact names, not HOME or all env."""
    home, variables = _configuration(env)
    directory = None
    try:
        directory = os.open(home, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        original = os.fstat(directory)
        auth, configuration = _read(directory, "auth.json"), _read(directory, "config.toml")
        if configuration is None:
            raise Unavailable("Explicit file-based Codex auth/config is not available; no group bytes can be dispatched")
        config = tomllib.loads(configuration[0].decode("utf-8"))
        if config.get("cli_auth_credentials_store") != "file" or config.get("model_provider", "openai") != "openai":
            raise Unavailable("This bounded group route requires explicit file credential storage and the actual OpenAI provider; keychain/custom providers remain unconfirmed")
        values = {name: os.environ.get(name) for name in variables}
        stored = json.loads(auth[0]) if auth is not None else {}
        if not isinstance(stored, dict) or not (isinstance(stored.get("OPENAI_API_KEY"), str) and stored["OPENAI_API_KEY"] or any(isinstance(values.get(name), str) and values[name] for name in CREDENTIALS if name in variables)):
            raise Unavailable("No bounded original API-key source is configured; configured/account-unknown is not an API-mode proof")
        named = os.stat(home, follow_symlinks=False)
        if (original.st_dev, original.st_ino) != (named.st_dev, named.st_ino) or not stat.S_ISDIR(named.st_mode):
            raise Unavailable("Configured physical Codex home changed")
        digest = hmac.new(manager.auth_generation_salt, digestmod=hashlib.sha256)
        digest.update(b"pocketworld/external-group/codex-api-source/v1\0")
        digest.update(json.dumps([home, original.st_dev, original.st_ino, variables, values], sort_keys=True, separators=(",", ":")).encode())
        for name, item in (("auth.json", auth), ("config.toml", configuration)):
            digest.update(name.encode()+b"\0")
            if item is None:
                digest.update(b"absent\0")
            else:
                digest.update(json.dumps(item[1], separators=(",", ":")).encode()+b"\0")
                digest.update(item[0])
        return digest.hexdigest()
    except (OSError, ValueError, TypeError) as failure:
        raise Unavailable("Original configured Codex API-key source cannot be verified; no group dispatch") from failure
    finally:
        if directory is not None:
            os.close(directory)


def child_environment(env, original):
    """Only the explicitly configured sources are active for this opt-in group route."""
    home, variables = _configuration(env)
    value = dict(original)
    value["CODEX_HOME"] = home
    for name in CLEAR_UNSELECTED:
        if name not in variables:
            value.pop(name, None)
    return value


def actual_api_mode(account, config):
    """Only sanitized closed facts. Never forward account email, tokens, or raw config."""
    identity = account.get("account") if isinstance(account, dict) else None
    configuration = config.get("config") if isinstance(config, dict) else None
    if not isinstance(account, dict) or not isinstance(account.get("requiresOpenaiAuth"), bool) or not isinstance(identity, dict) or identity.get("type") != "apiKey" or not isinstance(configuration, dict) or configuration.get("cli_auth_credentials_store") != "file" or configuration.get("model_provider", "openai") != "openai":
        raise Unavailable("Actual app-server account/config did not confirm this exact API-key/file-source mode")
    return {"authMode": "apiKey", "accountReadVerified": True, "configuredCredentialSourceVerified": True,
            "billingBackendVerified": False, "internalTokensKnown": False, "internalCostKnown": False}


def capability(env):
    return {"supported": configured(env), "apiModeRequired": True, "actualAccountReadRequired": True,
            "opaqueCredentialGeneration": True, "explicitCodexHomeRequired": True,
            "keychainOrUnknownSourceSupported": False, "workers": ["codex_app_server"],
            "readOnlyAnalysisOnly": True, "billingBackendVerified": False}


def issue(manager, env_id, generation_value, facts):
    value = {**facts, "environmentId": env_id, "environmentBinding": manager.environment_binding(env_id),
             "credentialGeneration": generation_value, "observedAt": manager.now(), "expiresAt": manager.now()+120000}
    value["proofToken"] = _proof_token(manager,value)
    return value


def _proof_token(manager, value):
    canonical = {key: item for key,item in value.items() if key != "proofToken"}
    return hmac.new(manager.auth_generation_salt,b"pocketworld/external-group/api-mode-review/v1\0"+json.dumps(canonical,sort_keys=True,separators=(",", ":")).encode(),hashlib.sha256).hexdigest()


def check(manager, job_or_request, env):
    """Only opt-in group input dispatch. Private/DM requests never acquire this restriction."""
    request = job_or_request.get("inputRequest",job_or_request)
    if request.get("inferenceAudience") != "EXTERNAL_GROUP":
        if "externalGroupApiMode" in request or "groupInputSource" in request:
            raise Unavailable("External group source cannot be relabelled as private input")
        return None
    if request.get("worker") != "codex_app_server" or request.get("readOnly") is not True or isinstance(request.get("timeoutSeconds"),bool) or not isinstance(request.get("timeoutSeconds"),int) or not 10 <= request["timeoutSeconds"] <= 900 or not request.get("inputFiles") or any(key in request for key in ("resumeJobId","threadId","assignmentWorkspace","projectHistory","forkSource")):
        raise Unavailable("This explicit group route is a new read-only Codex analysis job, not private/full-project or resumed authority")
    value = request.get("externalGroupApiMode")
    keys={"authMode","accountReadVerified","configuredCredentialSourceVerified","billingBackendVerified","internalTokensKnown","internalCostKnown","environmentId","environmentBinding","credentialGeneration","observedAt","expiresAt","proofToken"}
    if not isinstance(value,dict) or set(value)!=keys or value.get("authMode")!="apiKey" or value.get("accountReadVerified")is not True or value.get("configuredCredentialSourceVerified")is not True or any(value.get(key)is not False for key in ("billingBackendVerified","internalTokensKnown","internalCostKnown")):
        raise Unavailable("Actual signed API-mode read is missing; configured/unknown or owner acknowledgement is not a substitute")
    if any(isinstance(value.get(key),bool)or not isinstance(value.get(key),int)for key in ("observedAt","expiresAt")) or not value["observedAt"]<=manager.now()<value["expiresAt"]<=value["observedAt"]+120000:
        raise Unavailable("Exact API-mode review has expired")
    if value["environmentId"]!=request.get("environmentId") or value["environmentBinding"]!=request.get("environmentBinding") or value["environmentBinding"]!=manager.environment_binding(value["environmentId"]) or not isinstance(value.get("proofToken"),str) or not hmac.compare_digest(value["proofToken"],_proof_token(manager,value)):
        raise Unavailable("Original environment or API-mode receipt changed; no group bytes or model turn can be dispatched")
    if generation(manager,env)!=value.get("credentialGeneration"):
        raise Unavailable("Configured Codex credential/configuration generation changed; no group dispatch")
    source=request.get("groupInputSource")
    if not isinstance(source,dict)or source.get("inferenceAudience")!="EXTERNAL_GROUP" or source.get("privateBookImported")is not False or not re.fullmatch(r"group:[a-f0-9-]{36}",source.get("spaceId","")) or not re.fullmatch(r"[a-f0-9-]{36}",source.get("requestId","")) or not re.fullmatch(r"[a-f0-9-]{36}",source.get("taskId","")):
        raise Unavailable("Original native authenticated group input provenance is missing")
    return value
