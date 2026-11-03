"""Explicit CC Switch reads for the ORIGINAL Companion job supervisor.

No home discovery, configuration writes, account activation, approval store or job
runner lives here. The original authenticated owner flow must call review, then
job_environment after its own admission and immediately before its own spawn.
Credentials and private consistency digests never belong in public receipts.
"""
from contextlib import ExitStack, closing, contextmanager
from dataclasses import dataclass, field
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import stat
from urllib.parse import urlsplit

from platform_backend import PlatformError
from workspace_files import FileError

ADAPTER = "pocketworld-cc-switch-1"
SCHEMA = "cc-switch-claude-providers-v1"
MAX_CONFIG = 262144
MAX_DATABASE = 64 * 1024 * 1024
ENVIRONMENT_KEYS = frozenset({
    "ANTHROPIC_BASE_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
    "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL",
})
CREDENTIAL_KEYS = frozenset({"ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"})
MODEL_KEYS = {"default": "ANTHROPIC_DEFAULT_SONNET_MODEL",
              "opus": "ANTHROPIC_DEFAULT_OPUS_MODEL", "haiku": "ANTHROPIC_DEFAULT_HAIKU_MODEL"}


class ImportErrorState(Exception):
    def __init__(self, message, status=409):
        super().__init__(message)
        self.status = status


def _text(value, maximum=200):
    if not isinstance(value, str) or not value or len(value) > maximum or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise ImportErrorState("Invalid bounded CC Switch selection", 400)
    return value


def _json(raw):
    def unique(pairs):
        value = {}
        for key, entry in pairs:
            if key in value:
                raise ValueError("duplicate key")
            value[key] = entry
        return value
    try:
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8")
        value = json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (UnicodeError, ValueError, TypeError, RecursionError):
        raise ImportErrorState("CC Switch configuration is not a supported JSON object") from None
    if not isinstance(value, dict):
        raise ImportErrorState("CC Switch configuration is not a supported JSON object")
    return value


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def source_definitions_identity(environment):
    """For the original environment binding: source definitions, never contents."""
    sources = environment.get("ccSwitchSources", [])
    if not isinstance(sources, list) or len(sources) > 40:
        raise ImportErrorState("CC Switch source definitions are invalid", 400)
    selected, identifiers = [], set()
    for source in sources:
        if not isinstance(source, dict) or set(source) != {"id", "root", "schemaRevision"}:
            raise ImportErrorState("CC Switch source definitions contain unsupported fields", 400)
        identifier = _text(source["id"], 80)
        if identifier in identifiers:
            raise ImportErrorState("CC Switch source definitions have duplicate identities", 400)
        identifiers.add(identifier)
        selected.append([identifier, _text(source["root"], 2048), _text(source["schemaRevision"], 100)])
    return _digest([ADAPTER, sorted(selected)])


def _safe_values(value, credentials):
    if isinstance(value, dict):
        return {key: _safe_values(entry, credentials) for key, entry in value.items()}
    if isinstance(value, list):
        return [_safe_values(entry, credentials) for entry in value]
    if isinstance(value, str):
        for credential in credentials:
            value = value.replace(credential, "[CC Switch credential hidden]")
    return value


@dataclass(repr=False)
class _Read:
    pin: str
    environment: dict = field(repr=False)
    summary: dict


@dataclass(repr=False)
class _Review:
    binding: str
    source_identity: str
    source_id: str
    provider_id: str
    read: _Read = field(repr=False)


class CCSwitchImports:
    def __init__(self, manager):
        self.manager = manager
        self._reviews = {}
        self._jobs = {}
        self._closed = False

    def _source(self, request):
        eid = _text(request.get("environmentId"), 80)
        env = self.manager._environment(eid)
        binding = self.manager.environment_binding(eid)
        if self._closed or self.manager.closed or request.get("environmentBinding") != binding:
            raise ImportErrorState("CC Switch environment generation changed or source definition changed; review again")
        if env.get("kind", "local") != "local" or "claude" not in env.get("workers", []):
            raise ImportErrorState("CC Switch import requires an admitted local Claude worker", 403)
        sid = _text(request.get("sourceId"), 80)
        entries = env.get("ccSwitchSources", [])
        if not isinstance(entries, list) or len(entries) > 40:
            raise ImportErrorState("CC Switch sources must be explicitly configured locally", 403)
        matches = [entry for entry in entries if isinstance(entry, dict) and entry.get("id") == sid]
        if len(matches) != 1:
            raise ImportErrorState("CC Switch source is not uniquely admitted locally", 403)
        source = dict(matches[0])
        if set(source) != {"id", "root", "schemaRevision"} or source.get("schemaRevision") != SCHEMA:
            raise ImportErrorState("CC Switch source schema revision is unsupported")
        root = _text(source.get("root"), 2048)
        if not os.path.isabs(root):
            raise ImportErrorState("CC Switch source must use an explicit native absolute root", 403)
        # Source definition only: never include file contents, credentials or a
        # credential-derived digest in the public environment/source identity.
        identity = _digest([ADAPTER, eid, sid, root, source["schemaRevision"]])
        return env, source, binding, identity

    @contextmanager
    def _files(self, source):
        """Pin native source objects through the existing filesystem boundary."""
        with ExitStack() as stack:
            if self.manager.platform.name == "windows":
                backend = self.manager.platform
                if not callable(getattr(backend, "open_reader", None)):
                    raise ImportErrorState("Native pinned CC Switch reads are unavailable")
                root, _, root_identity = stack.enter_context(backend.directory(source["root"]))
                readers = {}
                for name, maximum in (("settings.json", MAX_CONFIG), ("cc-switch.db", MAX_DATABASE)):
                    readers[name] = stack.enter_context(backend.open_reader(root, name, maximum))
                for name in ("cc-switch.db-wal", "cc-switch.db-shm", "cc-switch.db-journal"):
                    try:
                        readers[name] = stack.enter_context(backend.open_reader(root, name, MAX_DATABASE))
                    except OSError as failure:
                        if getattr(failure, "winerror", None) not in (2, 3):
                            raise
                # Keep the original non-reparse handles open while SQLite reads
                # the admitted path. Existing writers fail the native share fence.
                db_path = str(Path(root) / "cc-switch.db")
                def read(name):
                    reader = readers[name]
                    reader.seek(0)
                    pieces = []
                    while True:
                        block = reader.read(262144)
                        if not block:
                            break
                        pieces.append(block)
                    reader.verify_unchanged()
                    return b"".join(pieces)
                def verify():
                    for reader in readers.values():
                        reader.verify_unchanged()
                sidecars = {name: read(name) for name in readers if name not in {"settings.json", "cc-switch.db"}}
                if sidecars.get("cc-switch.db-journal") or sidecars.get("cc-switch.db-wal") and "cc-switch.db-shm" not in readers:
                    raise ImportErrorState("Active CC Switch journal has no supported read-only native snapshot")
                native_identity = [root_identity, {name: reader.identity for name, reader in readers.items()},
                                   {name: hashlib.sha256(raw).hexdigest() for name, raw in sidecars.items()}]
                yield db_path, native_identity, read, verify, not bool(sidecars.get("cc-switch.db-wal"))
            else:
                root = Path(source["root"])
                if root != root.resolve() or not hasattr(os, "O_NOFOLLOW") or not Path("/proc/self/fd").is_dir():
                    raise ImportErrorState("Pinned CC Switch reads require a supported native source")
                fd = self.manager.files.directory({"kind": "local", "root": "/"}, list(root.parts[1:]))
                stack.callback(os.close, fd)
                root_info = os.fstat(fd)
                root_identity = [root_info.st_dev, root_info.st_ino]
                streams, stamps = {}, {}
                for name, maximum in (("settings.json", MAX_CONFIG), ("cc-switch.db", MAX_DATABASE)):
                    handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
                    stream = stack.enter_context(os.fdopen(handle, "rb"))
                    info = os.fstat(stream.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > maximum:
                        raise ImportErrorState("CC Switch source must be a bounded regular single-link file")
                    streams[name], stamps[name] = stream, info
                # An immutable fd is a safe read of the exact selected inode,
                # but cannot consume a live adjacent WAL. Do not silently read
                # an older main database or create/copy credential sidecars.
                for suffix in ("-wal", "-journal"):
                    try:
                        sidecar = os.stat("cc-switch.db" + suffix, dir_fd=fd, follow_symlinks=False)
                    except FileNotFoundError:
                        continue
                    if not stat.S_ISREG(sidecar.st_mode) or sidecar.st_size:
                        raise ImportErrorState("Active CC Switch journal requires a supported native snapshot; retry after checkpoint")
                def read(name):
                    stream = streams[name]
                    stream.seek(0)
                    return stream.read((MAX_CONFIG if name == "settings.json" else MAX_DATABASE) + 1)
                def verify():
                    for name, before in stamps.items():
                        after = os.stat(name, dir_fd=fd, follow_symlinks=False)
                        current = os.fstat(streams[name].fileno())
                        fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns", "st_nlink")
                        if any(getattr(before, key) != getattr(after, key) or getattr(before, key) != getattr(current, key) for key in fields):
                            raise ImportErrorState("CC Switch source changed during the read")
                    for suffix in ("-wal", "-journal"):
                        try:
                            extra = os.stat("cc-switch.db" + suffix, dir_fd=fd, follow_symlinks=False)
                        except FileNotFoundError:
                            continue
                        if not stat.S_ISREG(extra.st_mode) or extra.st_size:
                            raise ImportErrorState("CC Switch source changed during the read")
                native_identity = [root_identity, {name: [getattr(info, key) for key in
                                   ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")]
                                   for name, info in stamps.items()}]
                yield "/proc/self/fd/" + str(streams["cc-switch.db"].fileno()), native_identity, read, verify, True

    def _read(self, source, provider_id):
        try:
            with self._files(source) as (db_path, root_identity, read, verify, immutable):
                settings_raw, database_raw = read("settings.json"), read("cc-switch.db")
                if len(settings_raw) > MAX_CONFIG or len(database_raw) > MAX_DATABASE:
                    raise ImportErrorState("CC Switch source exceeds its read bound", 413)
                settings = _json(settings_raw)
                if settings.get("currentProviderClaude") != provider_id:
                    raise ImportErrorState("Explicit profile is not the currently selected CC Switch Claude profile")
                uri = Path(db_path).as_uri() + "?mode=ro" + ("&immutable=1" if immutable else "")
                with closing(sqlite3.connect(uri, uri=True, timeout=0.2)) as database:
                    database.execute("PRAGMA query_only=ON")
                    database.execute("PRAGMA trusted_schema=OFF")
                    database.execute("BEGIN")
                    if database.execute("SELECT type FROM sqlite_master WHERE name='providers'").fetchall() != [("table",)]:
                        raise ImportErrorState("CC Switch providers table schema is unsupported")
                    columns = database.execute("PRAGMA table_info(providers)").fetchall()
                    known = {row[1]: row for row in columns}
                    if any(name not in known or str(known[name][2]).upper() != "TEXT" for name in ("id", "app_type", "settings_config")):
                        raise ImportErrorState("CC Switch providers table schema is unsupported")
                    if [row[1] for row in sorted(columns, key=lambda row: row[5]) if row[5]] != ["id", "app_type"]:
                        raise ImportErrorState("CC Switch provider identity schema is unsupported")
                    rows = database.execute("SELECT settings_config FROM providers WHERE id=? AND app_type=? LIMIT 2", (provider_id, "claude")).fetchall()
                    if len(rows) != 1 or not isinstance(rows[0][0], str) or len(rows[0][0].encode("utf-8")) > MAX_CONFIG:
                        raise ImportErrorState("Selected CC Switch profile is unavailable or exceeds its bound")
                    config = _json(rows[0][0])
                    schema = database.execute("SELECT name,type,sql FROM sqlite_master ORDER BY name,type").fetchall()
                    database.rollback()
                verify()
                if settings_raw != read("settings.json") or database_raw != read("cc-switch.db"):
                    raise ImportErrorState("CC Switch source changed during the read")
                verify()
                environment, keys, blocked = {}, [], []
                raw_env = config.get("env", {})
                if not isinstance(raw_env, dict) or len(raw_env) > 128:
                    raise ImportErrorState("CC Switch environment schema is unsupported")
                for key, value in raw_env.items():
                    if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", key):
                        raise ImportErrorState("CC Switch environment key schema is unsupported")
                    keys.append(key)
                    if key not in ENVIRONMENT_KEYS:
                        blocked.append(key)
                        continue
                    if not isinstance(value, str) or len(value) > 8192 or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in value):
                        raise ImportErrorState("CC Switch supported environment value is invalid")
                    if value.strip():
                        environment[key] = value
                endpoint = environment.get("ANTHROPIC_BASE_URL")
                if endpoint:
                    parsed = urlsplit(endpoint)
                    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment or "\\" in endpoint:
                        raise ImportErrorState("CC Switch endpoint must be an absolute credential-free HTTP endpoint")
                    try:
                        parsed.port
                    except ValueError:
                        raise ImportErrorState("CC Switch endpoint port is invalid") from None
                slots = {slot: environment[key] for slot, key in MODEL_KEYS.items() if key in environment}
                if "default" not in slots and environment.get("ANTHROPIC_MODEL"):
                    slots["default"] = environment["ANTHROPIC_MODEL"]
                configured_model = config.get("model")
                if configured_model is not None:
                    configured_model = _text(configured_model)
                for model in slots.values():
                    _text(model)
                summary = {"providerId": provider_id, "endpoint": endpoint, "modelSlots": slots,
                           "configuredModel": configured_model,
                           "envKeys": sorted(keys), "injectedEnvKeys": sorted(environment), "blockedEnvKeys": sorted(blocked),
                           "schemaRevision": SCHEMA, "schemaValidation": "known_read_projection",
                           "modelSource": "configured_unverified", "runtimeVerified": False}
                # Even a malicious label/model value equal to a credential is
                # redacted before any summary is returned to the phone.
                summary = _safe_values(summary, [environment[key] for key in CREDENTIAL_KEYS if environment.get(key)])
                private_pin = _digest([root_identity, hashlib.sha256(settings_raw).hexdigest(),
                                       hashlib.sha256(database_raw).hexdigest(), schema, config])
                return _Read(private_pin, environment, summary)
        except ImportErrorState:
            raise
        except (OSError, UnicodeError, sqlite3.Error, ValueError, TypeError, PlatformError, FileError):
            raise ImportErrorState("CC Switch source cannot be read through the admitted native boundary") from None

    def review(self, request):
        _, source, binding, source_identity = self._source(request)
        provider_id = _text(request.get("providerId"), 200)
        read = self._read(source, provider_id)
        _, _, current_binding, current_source = self._source(request)
        if binding != current_binding or source_identity != current_source:
            raise ImportErrorState("CC Switch source definition changed while reviewing")
        # Public configHash is an opaque per-review handle, not a hash of a
        # secret-bearing database/config. Private content pins stay native.
        handle = secrets.token_hex(32)
        read.summary.update(configHash=handle, configRevision=handle, sourceId=source["id"], sourceIdentity=source_identity,
                            environmentId=request["environmentId"], environmentBinding=binding,
                            configHashMeaning="opaque_native_review", adapterRevision=ADAPTER)
        read.summary = _safe_values(read.summary, [read.environment[key] for key in CREDENTIAL_KEYS if read.environment.get(key)])
        self._reviews[handle] = _Review(binding, source_identity, source["id"], provider_id, read)
        while len(self._reviews) > 80:
            self._reviews.pop(next(iter(self._reviews)))
        return copy.deepcopy(read.summary)

    def job_environment(self, job, request, env, *, bind=True):
        """Native-only submit/pre-spawn callback, AFTER original owner admission.

        The request carries the reviewed handle, never environment values. This
        neither admits a job nor bypasses the original operation/approval ledger.
        Submit uses bind=False before any original Harness lease effect; the
        final original pre-spawn call binds the native credential/redaction context.
        No JSON request is allowed to reconstruct a lost native review.
        """
        selection = request.get("ccSwitchImport")
        if selection is None and "ccSwitchImport" not in request:
            if job.get("id") in self._jobs:
                raise ImportErrorState("Original CC Switch job lost its native reviewed selection")
            return {}
        if not isinstance(selection, dict) or set(selection) != {"sourceId", "providerId", "configHash"}:
            raise ImportErrorState("CC Switch import requires an exact explicit reviewed selection", 400)
        _text(selection["sourceId"], 80)
        _text(selection["providerId"], 200)
        if not isinstance(selection["configHash"], str) or not re.fullmatch(r"[a-f0-9]{64}", selection["configHash"]):
            raise ImportErrorState("CC Switch review handle is invalid", 400)
        review = self._reviews.get(selection.get("configHash"))
        if review is None:
            raise ImportErrorState("CC Switch native review is missing/stale; review again")
        group = request.get("inferenceAudience") == "EXTERNAL_GROUP" or "groupInputSource" in request or "externalGroupApiMode" in request
        if job.get("worker") != "claude" or env.get("kind", "local") != "local" or request.get("harnessConfigOperationId") or group or request.get("assignmentWorkspace") or request.get("resumeJobId") or request.get("threadId"):
            raise ImportErrorState("CC Switch import requires an original new local Claude job without a conflicting configuration", 403)
        if not job.get("id") or not job.get("operationId") or job.get("operationId") != request.get("operationId") or job.get("worker") != request.get("worker") or job.get("environmentId") != request.get("environmentId"):
            raise ImportErrorState("CC Switch import lacks its original job identity")
        _, source, binding, source_identity = self._source({**request, "sourceId": selection["sourceId"]})
        if binding != review.binding or source_identity != review.source_identity or selection["sourceId"] != review.source_id or selection["providerId"] != review.provider_id:
            raise ImportErrorState("CC Switch source definition or native owner binding changed")
        existing = self._jobs.get(job["id"])
        identity = (job["operationId"], selection["configHash"], job["environmentId"])
        if existing is not None and existing[0] != identity:
            raise ImportErrorState("Original CC Switch job/configuration identity changed")
        current = self._read(source, review.provider_id)
        _, _, current_binding, current_source = self._source({**request, "sourceId": selection["sourceId"]})
        if binding != current_binding or source_identity != current_source:
            raise ImportErrorState("CC Switch source definition changed before dispatch")
        if not hmac.compare_digest(current.pin, review.read.pin):
            raise ImportErrorState("CC Switch configuration changed; old configHash cannot dispatch")
        if bind:
            self._jobs[job["id"]] = (identity, review)
        job["ccSwitchImport"] = {**copy.deepcopy(review.read.summary), "state": "checked_before_spawn",
                                 "consistency": "optimistic_native_recheck", "processEnvironmentDeliveryVerified": False}
        return dict(current.environment)

    def child_environment(self, job, request, env, original_environment):
        """Final original pre-spawn recheck; refuse mixed inherited provider state."""
        delta = self.job_environment(job, request, env)
        if "ccSwitchImport" not in request:
            return dict(original_environment)
        if any(original_environment.get(key) and original_environment[key] != delta.get(key) for key in ENVIRONMENT_KEYS):
            raise ImportErrorState("Inherited Claude provider override conflicts with the reviewed CC Switch source")
        return {**original_environment, **delta}

    def model_provenance(self, job, actual_reported_model):
        """Use only at the original driver receipt callback, never from request JSON."""
        entry = self._jobs.get(job.get("id"))
        if entry is None or entry[0][0] != job.get("operationId"):
            return None
        summary = entry[1].read.summary
        observed = actual_reported_model if isinstance(actual_reported_model, str) and actual_reported_model.strip() else None
        value = {"configRevision": summary["configRevision"], "sourceId": summary["sourceId"],
                 "providerId": summary["providerId"], "configuredModelSlots": copy.deepcopy(summary["modelSlots"]),
                 "configuredModel": summary["configuredModel"], "configuredModelVerified": False,
                 "reportedModel": observed, "effectiveModel": observed,
                 "modelSource": "original_cli_session_receipt" if observed else "unknown",
                 "configurationFallback": "configured_unverified", "inferenceVerified": False}
        return _safe_values(value, self._secrets())

    def _secrets(self):
        reviews = list(self._reviews.values()) + [entry[1] for entry in self._jobs.values()]
        return sorted({review.read.environment[key] for review in reviews for key in CREDENTIAL_KEYS if review.read.environment.get(key)}, key=len, reverse=True)

    def redact(self, text):
        for secret in self._secrets():
            text = text.replace(secret, "[CC Switch credential hidden]")
        return text

    def redact_value(self, value):
        # Redact decoded native strings BEFORE JSON escaping can hide an exact
        # credential containing quotes, backslashes or non-ASCII characters.
        return _safe_values(value, self._secrets())

    def redaction_tail(self):
        return max([0, *(len(secret) - 1 for secret in self._secrets())])

    def settle_job(self, job, process_group_stopped):
        if process_group_stopped is not True or job.get("status") not in {"completed", "failed", "cancelled", "blocked", "unknown", "interrupted"} or job.get("processCleanupProof", {}).get("processGroupStoppedVerified") is not True:
            return
        entry = self._jobs.pop(job.get("id"), None)
        if entry is not None and not any(other[0][1] == entry[0][1] for other in self._jobs.values()):
            self._reviews.pop(entry[0][1], None)

    def close(self):
        # Closing admission is not proof that original workers stopped. Keep
        # private redaction pins for unsettled original jobs until settle_job
        # receives their actual native process-group cleanup proof.
        self._closed = True
        retained = {entry[0][1] for entry in self._jobs.values()}
        self._reviews = {key: review for key, review in self._reviews.items() if key in retained}
