"""Paired device ingress on the ORIGINAL Companion supervisor and transport.

No second Agent, token-to-device authority conversion, public HTTP endpoint or
task database. Every business request starts with the actual mTLS peer and the
single DeviceProtocol/PairingCoordinator gate; ordinary legacy HTTP stays legacy.
"""
from __future__ import annotations

import asyncio
import base64
import copy
from contextvars import ContextVar
import hashlib
import io
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid
from urllib.parse import urlsplit

import device_identity
import device_protocol as protocol
from device_pairing import DeviceTrust, PairingCoordinator, Permission, HistoryPermission, OperationManagementPermission, DiscoveryPermission
from device_companion_history import CompanionHistory, accepted_audience, native_operation_id, operation_receipt_for
from device_companion_management import OperationManagement
from device_operation_budget import NativeOperationBudget
from device_artifacts import ArtifactTransferStore, ArtifactEndpoint, ArtifactSession, ArtifactReceiver, WindowsArtifactReceiver, from_wire_descriptor
from desktop_jobs import DesktopJobs
from desktop_runtime import attach_desktop
from platform_backend import PlatformError

_NATIVE_COMMAND = object()
COMMAND_PROTOCOL = "native-device-command-v1"
TOOL_PROTOCOL = "native-device-tool-v1"
WORKSPACE_VERSION = "workspace-v1"
WORKSPACE_TOOLS = {"workspace.list": "read", "workspace.read": "read", "workspace.export": "read", "workspace.write": "write", "workspace.receive": "write", "workspace.location": "read"}
PROJECT_TOOLS = {"workspace.project.receive": "write", "workspace.project.confirm": "write", "workspace.project.export": "read"}
_HISTORY_READ = ContextVar("companion_fixed_artifact_history", default=None)


class NativeArtifactStore(ArtifactTransferStore):
    """Publish verified bytes first; retain their immutable audience in original SQL."""
    def __init__(self, receiver):
        self.receiver = receiver
        super().__init__(source_device_id=receiver.identity.device_id, current=receiver._artifact_current)

    def register_stream(self, native_stream, fixed_reference):
        fixed = super().register_stream(native_stream, fixed_reference)
        permission = self.receiver._source_permission(fixed)
        self.receiver.history.record_artifact(fixed, permission.envelope, permission)
        return fixed


def _audience(envelope):
    return {**{key: envelope.get(key) for key in ("originDeviceId", "callerDeviceId", "targetDeviceId", "rootTaskId", "parentTaskId", "localTaskId", "instanceId", "grantId", "authorizationEpoch", "capabilityVersion", "resourceBinding")},
            "requestRunId": envelope.get("runId"), "requestExecutorEpoch": envelope.get("executorEpoch"),
            "receiverTaskId": protocol.receiver_task_id(envelope), "toolName": envelope.arguments["toolName"]}


def workspace_binding(manager, environment_id):
    identity = getattr(manager, "_native_device_resource_instance", None)
    if identity is None:
        raise PlatformError("Persistent native local identity is required for device workspace scope")
    environment = manager._environment(environment_id)
    if environment.get("kind", "local") != "local":
        raise PlatformError("Native device workspace scope does not admit an SSH second hop")
    if manager.platform.name == "windows":
        with manager.platform.directory(environment["root"]) as (_, _, physical):
            physical = [str(value) for value in physical]
    else:
        descriptor = manager.files.directory(environment, [])
        try:
            info = os.fstat(descriptor)
            physical = [str(info.st_dev), str(info.st_ino)]
        finally:
            os.close(descriptor)
    # Only public deterministic workspace/tool configuration. Original legacy
    # environment_binding STILL includes manager generation and is unchanged.
    # No timestamp, process nonce, credential or model/provider secret is used.
    configuration = {key: environment.get(key) for key in ("kind", "root", "writeAllowed", "workers", "commands", "commandOutputEncoding")}
    body = {"localDeviceId": identity[0], "localIdentityInstance": identity[1], "environmentId": environment_id,
            "platform": manager.platform.name, "rootFileIdentity": physical, "configuration": configuration}
    return {"kind": "workspace", "environmentId": environment_id, "binding": hashlib.sha256(protocol.canonical_bytes(body)).hexdigest()}


class NativeCommandAdmission:
    """Opaque process-lifetime authority; never serialized or constructed from HTTP JSON."""
    def __init__(self, seal, receiver, envelope, permission, request):
        if seal is not _NATIVE_COMMAND or not isinstance(permission, Permission) or permission.envelope.json() != envelope.json():
            raise TypeError("Use authenticated CompanionDeviceReceiver command admission")
        self.receiver, self.manager, self.envelope, self.permission = receiver, receiver.manager, envelope, permission
        self.request = copy.deepcopy(request)
        self.fingerprint = protocol.arguments_digest(envelope.json())
        self.instance = self.manager.instance_id

    @staticmethod
    def require(value, manager, request):
        from pocket_companion import RequestError
        if not isinstance(value, NativeCommandAdmission) or value.manager is not manager or value.request != request or request.get("operationId") != value.envelope.get("operationId"):
            raise RequestError("Invalid native device command authority", 403)
        value.check_current()

    def check_current(self):
        from pocket_companion import RequestError
        try:
            if self.manager.closed or self.manager.instance_id != self.instance:
                raise PermissionError("Original device supervisor changed or stopped")
            self.permission.check_current(self.manager.now())
            self.receiver.require_workspace(self.envelope, self.permission)
        except (PermissionError, PlatformError, ValueError) as failure:
            raise RequestError("Original paired-device command authority expired, changed or was revoked", 403) from failure

    def audience(self):
        return _audience(self.envelope)

    @staticmethod
    def child_environment(original):
        # Deterministic native tools do not inherit cloud/model/Companion/relay
        # secrets. This is not an OS sandbox: an explicitly authorized same-user
        # executable can still read files allowed to that Windows user.
        allowed = {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "PROGRAMDATA", "HOME", "LANG", "LC_ALL", "LC_CTYPE"}
        return {key: value for key, value in original.items() if key.upper() in allowed}

    def prepare(self, job):
        self.check_current()
        database = self.manager.ledger
        if database.in_transaction:
            raise PlatformError("Original operation prepare has an unsettled transaction")
        savepoint = "native_command_" + uuid.uuid4().hex
        database.execute("SAVEPOINT " + savepoint)
        try:
            claim = self.receiver.budget.claim(job["operationId"], self.envelope, self.permission, database)
            database.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                             (job["operationId"], self.fingerprint, job["id"], "queued", job["updatedAt"],
                              json.dumps({"protocol": COMMAND_PROTOCOL, "audience": self.audience(),
                                          "historyAudience": accepted_audience(self.envelope, self.permission, job["operationId"], job["updatedAt"])})))
            database.execute("RELEASE SAVEPOINT " + savepoint)
            return claim
        except BaseException:
            database.execute("ROLLBACK TO SAVEPOINT " + savepoint)
            database.execute("RELEASE SAVEPOINT " + savepoint)
            raise


class CompanionDeviceReceiver:
    def __init__(self, manager, local_identity, *, bulk_sender=None, native_management_only=False):
        if not isinstance(local_identity, device_identity.LocalIdentity):
            raise TypeError("Actual existing native device identity is required")
        binding_identity = (local_identity.device_id, local_identity.instance_id)
        if getattr(manager, "_native_device_resource_instance", binding_identity) != binding_identity:
            raise PlatformError("Original manager cannot switch its native device identity while jobs/permissions exist")
        manager._native_device_resource_instance = binding_identity
        if type(native_management_only) is not bool:
            raise TypeError("Native owner management mode must be selected by the local entry")
        self.manager, self.identity = manager, local_identity
        self.closed = False
        self.trust = DeviceTrust(manager.ledger, manager.lock)
        self.trust.create_schema()
        self.pairing = PairingCoordinator(self.trust, local_identity.device_id, local_identity.instance_id)
        self.budget = NativeOperationBudget(manager)
        self.history = CompanionHistory(manager)
        self.management = OperationManagement(manager)
        self._exports = {}
        self._export_lock = threading.RLock()
        self._bulk_sender = bulk_sender
        self._inputs = {}  # Ephemeral permissions only; original jobs/operations remain authority.
        self._input_cache = None
        self._input_session = None
        self.artifacts = NativeArtifactStore(self)
        if native_management_only:
            # Scope review must not create a GUI worker-generation record and
            # then silently replace that worker when the original service starts.
            from desktop_runtime import UnavailableDesktop
            self.desktop = UnavailableDesktop("native_pairing_management_only_no_execution_worker")
        else:
            self.desktop = attach_desktop(manager, local_identity, self.artifacts, budget_claim=self.budget.claim)
        from desktop_viewer import DesktopViewer
        self.desktop_viewer = DesktopViewer(self)
        self.artifact_endpoint = ArtifactEndpoint(self.artifacts, native_instance_id=local_identity.instance_id, revision=1,
                                                  access=self._artifact_access, bulk_sender=self._send_bulk,
                                                  history_access=self._history_artifact_access, history_bulk_sender=self._send_history_bulk,
                                                  history_audience=self._history_artifact_audience)

    def _current(self, permission):
        if not isinstance(permission, Permission) or self.manager.closed or self.closed:
            raise PermissionError("Original native device receiver is unavailable")
        permission.check_current(self.manager.now())

    def _current_read(self, permission):
        if not isinstance(permission, (Permission, HistoryPermission)) or self.manager.closed or self.closed:
            raise PermissionError("Original native fixed-result receiver is unavailable")
        permission.check_current(self.manager.now())

    def _current_discovery(self, permission):
        if not isinstance(permission, DiscoveryPermission) or self.manager.closed or self.closed:
            raise PermissionError("Native current paired discovery permission required")
        permission.check_current(self.manager.now())

    def require_workspace(self, envelope, permission):
        self._current(permission)
        binding = envelope.get("resourceBinding")
        if not isinstance(binding, dict) or set(binding) != {"kind", "environmentId", "binding"} or binding.get("kind") != "workspace":
            raise PlatformError("An exact current owner-granted workspace binding is required", 403)
        environment = self.manager._environment(binding["environmentId"])
        if environment.get("kind", "local") != "local" or not protocol.resource_equal(binding, workspace_binding(self.manager, binding["environmentId"])) or not any(protocol.resource_equal(binding, item) for item in permission.resources):
            raise PlatformError("Original native workspace changed or Windows SSH second hop is unsupported", 403)
        if envelope.get("capabilityVersion") != WORKSPACE_VERSION:
            raise PlatformError("Workspace capability version changed", 409)
        return binding["environmentId"], environment

    def describe(self):
        desktop = self.desktop.describe()
        desktop["streaming"] = self.desktop_viewer.describe()
        commands = any(worker.get("id") == "command" and worker.get("available") is True for item in self.manager.list_environments()["environments"] for worker in item["workers"] if item["kind"] == "local")
        workspace_capabilities = []
        for key, environment in self.manager.environments.items():
            if environment.get("kind", "local") != "local":
                continue
            native_tools = {**WORKSPACE_TOOLS, **(PROJECT_TOOLS if self.manager.platform.name == "windows" else {})}
            declarations = [{"toolName": tool, "effect": effect} for tool, effect in native_tools.items()
                            if effect == "read" or environment.get("writeAllowed") is True]
            available_command = any(worker.get("id") == "command" and worker.get("available") is True
                                    for item in self.manager.list_environments()["environments"] if item["id"] == key for worker in item["workers"])
            if available_command:
                declarations.append({"toolName": "command.run", "effect": "write"})
            workspace_capabilities.append({"id": "workspace." + key, "version": WORKSPACE_VERSION, "toolDeclarations": declarations,
                                           "resources": [workspace_binding(self.manager, key)]})
        if desktop.get("available") is True:
            scope = protocol.validate_resource_binding(desktop["capabilityVersion"], desktop["scope"])
            workspace_capabilities.append({"id": "desktop", "version": desktop["capabilityVersion"], "resources": [scope],
                                           "toolDeclarations": [{"toolName": "desktop.observe", "effect": "read"}, {"toolName": "desktop.act", "effect": "write"}, {"toolName": "desktop.control", "effect": "write"}]})
        return {"deviceId": self.identity.device_id, "instanceId": self.identity.instance_id, "protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE,
                "kind": "lightweight_companion", "modelRequired": False, "externalAgentRequired": False,
                "goalAgent": False, "taskControl": False, "operationCancel": True, "legacyHttpAuthority": "separate_owner_token_not_a_device_permission",
                "platform": self.manager.platform.capabilities(), "desktop": desktop,
                "workspaceCapabilityVersion": WORKSPACE_VERSION, "toolNames": [*(["command.run"] if commands else []), *WORKSPACE_TOOLS, *(PROJECT_TOOLS if self.manager.platform.name == "windows" else {}), *({"desktop.observe", "desktop.act", "desktop.control"} if desktop.get("available") else set())],
                "workspaceBindings": [workspace_binding(self.manager, key) for key, env in self.manager.environments.items() if env.get("kind", "local") == "local"],
                "capabilities": workspace_capabilities,
                "workspaceReceive": {"toolName": "workspace.receive", "maximumBytes": 262144, "kinds": ["file"], "projectImport": False},
                "workspaceProjectReceive": {"available": self.manager.platform.name == "windows", "maximumBytes": 20971520,
                    "maxExpandedBytes": 16777216, "maxFileBytes": 2097152, "maxFiles": 2000, "requiresImportReview": True, "newCopyOnly": True},
                "budget": self.budget.describe(), "artifactTransport": "separate_current_mutual_tls_bulk_lane"}

    @staticmethod
    def _source_key(envelope):
        return (native_operation_id(envelope), envelope.get("localTaskId") or envelope.get("rootTaskId"), envelope.get("runId"))

    def _register_export_context(self, envelope, permission):
        key = self._source_key(envelope)
        with self._export_lock:
            prior = self._exports.get(key)
            if prior is not None and protocol.arguments_digest(prior.envelope.json()) != protocol.arguments_digest(envelope.json()):
                raise PlatformError("Artifact publication correlation was reused by another request/audience", 409)
            if prior is None and len(self._exports) >= 512:
                raise PlatformError("Original export context bound reached; no unbounded capture authority retained", 503)
            self._exports[key] = permission

    def _source_permission(self, descriptor):
        key = (descriptor["operationId"], descriptor["taskId"], descriptor.get("requestRunId"))
        with self._export_lock:
            permission = self._exports.get(key)
        if permission is None:
            raise PermissionError("Artifact lacks its original native export authority")
        self._current(permission)
        return permission

    def _artifact_current(self, descriptor, direction):
        history = _HISTORY_READ.get()
        if direction == "read" and history is not None:
            self._current_read(history)
            self.history.validate_descriptor(history, descriptor)
            return
        permission = self._source_permission(descriptor)
        envelope = permission.envelope
        if (descriptor["sourceDeviceId"] != self.identity.device_id or descriptor["taskId"] != (envelope.get("localTaskId") or envelope.get("rootTaskId"))
                or descriptor.get("requestRunId") != envelope.get("runId") or descriptor.get("requestExecutorEpoch") != envelope.get("executorEpoch")
                or "runId" in descriptor or "executorEpoch" in descriptor):
            raise PermissionError("Artifact is outside its original task/run/source")
        if descriptor["kind"] == "screenshot" and not protocol.resource_equal(descriptor["resourceBinding"], envelope.get("resourceBinding")):
            raise PermissionError("Observed frame resource changed")

    def _artifact_access(self, permission, descriptor):
        self._current(permission)
        source = self._source_permission(descriptor).envelope
        request = permission.envelope
        if any(request.get(key) != source.get(key) for key in ("callerDeviceId", "originDeviceId", "rootTaskId", "localTaskId", "runId")) or not protocol.resource_equal(request.get("resourceBinding"), source.get("resourceBinding")) or not any(protocol.resource_equal(source.get("resourceBinding"), item) for item in permission.resources):
            raise PermissionError("Current artifact read grant/task/resource audience differs from the native export")

    def _history_artifact_audience(self, query, descriptor):
        # Admission reads only the native cache catalog + original SQL facts;
        # no file path, old execution authority or JSON supplied audience.
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT descriptor FROM device_artifact_audiences WHERE artifact_id=?", (descriptor["artifactId"],)).fetchone()
        if row is None or protocol.canonical_bytes(json.loads(row[0])) != protocol.canonical_bytes(descriptor):
            raise PermissionError("Original accepted artifact descriptor unavailable or changed")
        return self.history.accepted_audience(query)

    def _history_artifact_access(self, permission, descriptor):
        self._current_read(permission)
        self.history.validate_descriptor(permission, descriptor)

    async def _send_history_bulk(self, permission, descriptor, original_read, transfer, offset, raw):
        if not isinstance(permission, HistoryPermission):
            raise PermissionError("Fixed artifact history authority required")
        self._current_read(permission)
        if self._bulk_sender is None:
            raise PlatformError("Current authenticated bulk lane is unavailable")
        original_read()
        return await self._bulk_sender(permission, descriptor, original_read, transfer, offset, raw)

    async def _send_bulk(self, permission, descriptor, original_read, transfer, offset, raw):
        self._current(permission)
        if self._bulk_sender is None:
            raise PlatformError("Current authenticated bulk lane is unavailable")
        original_read()
        return await self._bulk_sender(permission, descriptor, original_read, transfer, offset, raw)

    def _command(self, envelope, permission):
        environment_id, _ = self.require_workspace(envelope, permission)
        args = envelope.arguments["arguments"]
        if envelope.arguments["effect"] != "write" or set(args) - {"argv", "timeoutSeconds"} or not isinstance(args.get("argv"), list):
            raise PlatformError("command.run requires exact explicit argv/timeout; no external Agent or prompt inferred", 400)
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", envelope.get("operationId", "")):
            raise PlatformError("Command needs an original Companion operationId of at most 80 characters", 400)
        request = {"operationId": envelope.get("operationId"), "environmentId": environment_id, "environmentBinding": self.manager.environment_binding(environment_id),
                   "worker": "command", "argv": args["argv"], "timeoutSeconds": args.get("timeoutSeconds", 300)}
        admission = NativeCommandAdmission(_NATIVE_COMMAND, self, envelope, permission, request)
        return self.manager.submit(request, native_device_admission=admission)

    def _workspace(self, envelope, permission):
        environment_id, environment = self.require_workspace(envelope, permission)
        tool, args = envelope.arguments["toolName"], envelope.arguments["arguments"]
        if tool not in WORKSPACE_TOOLS or envelope.arguments["effect"] != WORKSPACE_TOOLS[tool]:
            raise PlatformError("Workspace tool/effect mismatch", 400)
        allowed = {"workspace.list": {"path"}, "workspace.read": {"path"}, "workspace.export": {"path"}, "workspace.write": {"path", "content", "expectedVersion"}, "workspace.location": set()}
        if set(args) - allowed[tool] or tool not in {"workspace.list", "workspace.location"} and "path" not in args:
            raise PlatformError("Unknown/missing native workspace tool arguments", 400)
        if tool == "workspace.location":
            current = lambda: self.require_workspace(envelope, permission)
            location = self.manager.platform.workspace_location(environment["root"], current=current)
            current()
            return {"environmentId": environment_id, "resourceBinding": copy.deepcopy(envelope.get("resourceBinding")),
                    "rootPath": location, "pathStyle": "windows_absolute" if self.manager.platform.name == "windows" else "posix_absolute",
                    "locationOnly": True, "guiDirectorySandbox": False}
        if tool == "workspace.list":
            result = self.manager.files.list(environment, args.get("path", ""))
            while len(protocol.canonical_bytes(result)) > 24576 and result["files"]:
                result["files"].pop(); result["truncated"] = True
            return result
        if tool == "workspace.read":
            result = self.manager.files.read(environment, args["path"])
            if len(protocol.canonical_bytes(result)) > 24576:
                raise PlatformError("Text exceeds control bound; use workspace.export and verified artifact chunks", 413)
            return result
        if tool == "workspace.write":
            return self.manager.files.write(environment_id, environment, {**args, "operationId": envelope.get("operationId")}, current=lambda: self._current(permission))
        path = args["path"]
        self.manager.files.parts(path)
        if self.manager.platform.name == "windows":
            with self.manager.platform.open_reader(environment["root"], path) as stream:
                digest = hashlib.sha256()
                while True:
                    self._current(permission)
                    raw = stream.read(262144)
                    if not raw:
                        break
                    digest.update(raw)
                stream.seek(0)
                reference = self._file_reference(envelope, path, stream.size, digest.hexdigest())
                fixed = self.artifacts.register_stream(stream, reference)
        else:
            # Preserve the existing POSIX no-follow artifact implementation and
            # its stricter 256KiB bound; do not broaden that legacy path here.
            captured = self.manager._artifact_bytes(environment, path)
            raw = base64.b64decode(captured["base64"], validate=True)
            fixed = self.artifacts.register_stream(io.BytesIO(raw), self._file_reference(envelope, path, len(raw), captured["sha256"]))
        return self.artifacts.wire_descriptor(fixed, instance_id=self.identity.instance_id, revision=1)

    def _file_reference(self, envelope, path, size, digest):
        return {"transferId": str(uuid.uuid4()), "sourceDeviceId": self.identity.device_id, "artifactId": str(uuid.uuid4()),
                "versionId": digest, "sha256": digest, "bytes": size, "kind": "file", "mimeType": "application/octet-stream", "name": path.split("/")[-1],
                "taskId": envelope.get("localTaskId") or envelope.get("rootTaskId"), "requestRunId": envelope.get("runId"),
                "requestExecutorEpoch": envelope.get("executorEpoch"), "operationId": native_operation_id(envelope)}

    def _input_current(self, descriptor, direction="receive"):
        item = self._inputs.get(descriptor["transferId"])
        if item is None or item[0] != descriptor:
            raise PermissionError("Exact original input admission is unavailable")
        _, permission, job = item
        if direction == "remove":
            # Native cache retirement only: exact locally registered transfer/job,
            # never workspace/source I/O or a remote execution permission. Revocation
            # must not retain failed private byte caches forever.
            if self.manager.closed or self.closed:
                raise PermissionError("Original native cache owner is unavailable")
            return
        self.require_workspace(permission.envelope, permission)
        if job["status"] != "running" or job["id"] in self.manager.stopping:
            raise PermissionError("Original input operation stopped; no bytes or replacement admitted")

    def _input_manifest(self, envelope):
        args = envelope.arguments["arguments"]
        if envelope.arguments["effect"] != "write" or set(args) != {"path", "expectedVersion", "artifact"}:
            raise PlatformError("workspace.receive requires exact path, expectedVersion and native artifact", 400)
        self.manager.files.parts(args["path"])
        if not isinstance(args["expectedVersion"], str) or not re.fullmatch(r"absent|sha256:[a-f0-9]{64}", args["expectedVersion"]):
            raise PlatformError("Exact reviewed target version required", 400)
        descriptor = from_wire_descriptor(args["artifact"])
        # This first native file slice intentionally inherits the existing target CAS bound.
        # Project archives require independent import review, not merely a file named .zip.
        project = envelope.arguments["toolName"] == "workspace.project.receive"
        if project:
            if self.manager.platform.name != "windows" or not callable(getattr(self.manager.platform, "import_project_copy", None)):
                raise PlatformError("Reviewed native project copy import requires the Windows directory backend", 409)
            if args["expectedVersion"] != "absent" or descriptor["kind"] != "project_bundle" or not 22 <= descriptor["bytes"] <= 20971520:
                raise PlatformError("Project import requires a new copy and an original package of at most 20MiB", 413)
        elif descriptor["kind"] != "file" or descriptor["bytes"] > 262144:
            raise PlatformError("workspace.receive supports ordinary files up to 262144 bytes; project import is separate", 413)
        if (descriptor["sourceDeviceId"] != envelope.get("callerDeviceId")
                or descriptor["taskId"] != (envelope.get("localTaskId") or envelope.get("rootTaskId"))
                or descriptor["operationId"] != envelope.get("operationId")
                or descriptor.get("requestRunId") != envelope.get("runId")
                or descriptor.get("requestExecutorEpoch") != envelope.get("executorEpoch")):
            raise PermissionError("Selected native input provenance differs from this original operation/run")
        return descriptor

    def _prepare_input(self, peer, envelope, permission, job):
        descriptor = self._input_manifest(envelope)
        transfer = descriptor["transferId"]
        previous = self._inputs.get(transfer)
        if previous is not None and (previous[0] != descriptor or previous[2]["id"] != job["id"]):
            raise PermissionError("Original transfer belongs to another operation")
        if previous is None and len(self._inputs) >= 4:
            raise PlatformError("Native input cache full", 503)
        self._inputs[transfer] = (descriptor, permission, job)
        self._input_current(descriptor)
        if self._input_cache is None:
            # The original state root is provisioned privately by the native installer/manager.
            # Windows uses ONLY safe handles; never a POSIX directory or pathlib fallback.
            if self.manager.platform.name == "windows":
                backend = self.manager.platform
                backend.assert_private_directory(str(self.manager.state), backend.logon_context()["userSid"], require_protected=False)
                self._input_cache = WindowsArtifactReceiver(str(self.manager.state), backend=self.manager.platform, current=self._input_current)
            else:
                root = self.manager.state / "device-input-cache"
                root.mkdir(mode=0o700, exist_ok=True)
                self._input_cache = ArtifactReceiver(str(root), current=self._input_current)
            self._input_session = ArtifactSession(self._input_cache)
        offset = self._input_session.prepare_incoming(peer, descriptor, lambda: self._input_current(descriptor))
        result = {"status": "running", "phase": "awaiting_bytes", "transferId": transfer,
                  "sha256": descriptor["sha256"], "bytes": descriptor["bytes"], "verifiedOffset": offset,
                  "maximumBytes": 20971520 if descriptor["kind"] == "project_bundle" else 262144, "delivered": False}
        job.update(nativeToolResult=result, nativeInput=copy.deepcopy(envelope.arguments["arguments"]), updatedAt=self.manager.now())
        job.setdefault("_nativeInputChunks", {})
        job.setdefault("_nativeInputPeer", {"deviceId": peer.device_id, "fingerprint": peer.certificate_fingerprint})
        self.manager._save(job)
        if offset == descriptor["bytes"]:
            self._commit_input(peer, transfer)
        return copy.deepcopy(job["nativeToolResult"])

    def _commit_input(self, peer, transfer):
        descriptor, permission, job = self._inputs[transfer]
        self._input_current(descriptor)
        self._input_session.finish(peer, transfer)
        with self._input_cache.open_verified(descriptor) as stream:
            pieces, total = [], 0
            while True:
                chunk=stream.read(262144)
                if not chunk: break
                total+=len(chunk)
                if total>descriptor["bytes"]: raise PermissionError("Input exceeds reviewed byte count")
                pieces.append(chunk)
            raw=b"".join(pieces)
        if len(raw) != descriptor["bytes"] or hashlib.sha256(raw).hexdigest() != descriptor["sha256"]:
            raise PermissionError("Original verified input bytes changed")
        args = permission.envelope.arguments["arguments"]
        environment_id, environment = self.require_workspace(permission.envelope, permission)
        if descriptor["kind"] == "project_bundle":
            from device_project_packages import read, summary
            manifest, _ = read(raw)
            review=summary(manifest)
            job["nativeToolResult"].update(phase="awaiting_import_review", path=args["path"], expectedVersion="absent",
                                           manifestDigest=review["manifestDigest"], summary=review, delivered=False)
            job.update(updatedAt=self.manager.now(), message="Verified package staged; original owner import review still required")
            self.manager._save(job)
            return
        request = {"path": args["path"], "expectedVersion": args["expectedVersion"], "operationId": job["operationId"],
                   "inputArtifact": copy.deepcopy(args["artifact"])}
        try:
            job["nativeInputDispatchStarted"] = True
            self.manager._save(job)
            result = self.manager.files._write_bytes(environment_id, environment, request, raw, current=lambda: self._input_current(descriptor))
            # Cleanup is still gated by the original live admission, before settling it.
            # A cleanup failure must not erase a factual target write receipt.
            try:
                self._input_cache.remove(descriptor)
            except Exception:
                result = {**result, "inputCacheCleanupPending": True}
            self._input_session.release(transfer)
            job.update(status=result.get("status", "unknown"), nativeToolResult={**result, "transferId": transfer,
                       "sha256": descriptor["sha256"], "delivered": result.get("verified") is True}, updatedAt=self.manager.now())
            self.manager._save(job)
            self._inputs.pop(transfer, None)
        except BaseException:
            self._release_input(transfer)
            job.update(status="unknown", nativeToolResult={"status": "unknown", "transferId": transfer, "delivered": False,
                       "message": "Input replacement unconfirmed; query original operation, never replay"}, updatedAt=self.manager.now())
            self.manager._save(job)
            raise

    def _confirm_project(self, envelope, permission, confirmation_job):
        from device_project_packages import read, manifest_identity
        args=envelope.arguments["arguments"]
        if (envelope.arguments["effect"]!="write" or set(args)!={"receiveOperationId","manifestDigest","path","expectedVersion"}
                or args["expectedVersion"]!="absent" or not isinstance(args["manifestDigest"],str)
                or not re.fullmatch(r"[a-f0-9]{64}",args["manifestDigest"])):
            raise PlatformError("Exact original project import review is required",400)
        self.manager.files.parts(args["path"])
        row=self.manager.ledger.execute("SELECT job_id FROM operations WHERE operation_id=?",(args["receiveOperationId"],)).fetchone()
        source=self.manager.jobs.get(row[0]) if row else None
        audience=source.get("nativeDeviceOperation",{}) if source else {}
        if (source is None or audience.get("toolName")!="workspace.project.receive"
                or any(envelope.get(key)!=audience.get(key) for key in ("originDeviceId","callerDeviceId","rootTaskId","parentTaskId","localTaskId","grantId","authorizationEpoch"))
                or not protocol.resource_equal(envelope.get("resourceBinding"),audience.get("resourceBinding"))):
            raise PermissionError("Project review is outside the original received owner/task/grant/workspace")
        # Native owner review ends the original source attempt (awaiting_user).
        # Its confirmation is a NEW original run/op of the SAME task/scope; never
        # rewrite the receive operation's retained requestRunId/epoch as that run.
        review=source.get("nativeToolResult",{})
        if source.get("status")!="running" or source.get("nativeInputDispatchStarted") or review.get("phase")!="awaiting_import_review" or review.get("manifestDigest")!=args["manifestDigest"] or review.get("path")!=args["path"]:
            raise PermissionError("Original project review changed or is no longer pending")
        transfer=review["transferId"]
        try:
            item=self._inputs.get(transfer)
            if item is None or item[2] is not source: raise PermissionError("Original verified project cache unavailable; do not replay")
            descriptor=item[0]
            def current():
                self._input_current(descriptor)
                self.require_workspace(envelope,permission)
                if confirmation_job["status"]!="running" or confirmation_job["id"] in self.manager.stopping:
                    raise PermissionError("Original project confirmation stopped")
            current()
            with self._input_cache.open_verified(descriptor) as stream:
                pieces=[]
                while True:
                    raw=stream.read(262144)
                    if not raw: break
                    pieces.append(raw)
                manifest,files=read(b"".join(pieces))
            if manifest_identity(manifest)!=args["manifestDigest"]: raise PermissionError("Reviewed package manifest changed")
            environment_id,environment=self.require_workspace(envelope,permission)
            source["nativeInputDispatchStarted"]=True; self.manager._save(source)
            def intent(value):
                confirmation_job["nativeProjectImportIntent"]=value
                self.manager._save(confirmation_job)
            result=self.manager.platform.import_project_copy(environment["root"],args["path"],manifest,files,current,record_intent=intent)
            result.update(sourceReceiveOperationId=args["receiveOperationId"],manifestDigest=args["manifestDigest"],path=args["path"])
            try: self._input_cache.remove(descriptor)
            except Exception: result["inputCacheCleanupPending"]=True
            self._input_session.release(transfer); self._inputs.pop(transfer,None)
            if result.get("verified") is True:
                source["_nativeProjectManifest"] = manifest
            source.update(status=result.get("status","unknown"),nativeToolResult=result,updatedAt=self.manager.now())
            self.manager._save(source)
            return result
        except BaseException:
            owned=self._inputs.get(transfer)
            if owned is not None and owned[2] is source:
                self._release_input(transfer)
            status="unknown" if source.get("nativeInputDispatchStarted") else "failed"
            retained=copy.deepcopy(source.get("nativeToolResult",{}))
            retained.update(status=status, phase="import_unconfirmed" if status=="unknown" else "input_rejected",
                            delivered=False, verified=False,
                            message="Original project confirmation failed; inspect original operations without replay")
            if confirmation_job.get("nativeProjectImportIntent") is not None:
                retained["nativeProjectImport"]=copy.deepcopy(confirmation_job["nativeProjectImportIntent"])
            if status=="failed": retained["publicationAttempted"]=False
            source.update(status=status,nativeToolResult=retained,updatedAt=self.manager.now())
            self.manager._save(source)
            raise

    def _export_project(self, envelope, permission):
        from device_project_packages import write_modified
        args=envelope.arguments["arguments"]
        if envelope.arguments["effect"]!="read" or set(args)!={"receiveOperationId"}:
            raise PlatformError("Export requires one original reviewed project reference",400)
        row=self.manager.ledger.execute("SELECT job_id FROM operations WHERE operation_id=?",(args["receiveOperationId"],)).fetchone()
        source=self.manager.jobs.get(row[0]) if row else None
        audience=source.get("nativeDeviceOperation",{}) if source else {}
        if (source is None or audience.get("toolName")!="workspace.project.receive" or source.get("status")!="completed"
                or any(envelope.get(key)!=audience.get(key) for key in ("originDeviceId","callerDeviceId","rootTaskId","localTaskId"))
                or not protocol.resource_equal(envelope.get("resourceBinding"),audience.get("resourceBinding"))):
            raise PermissionError("Export is outside original owner-selected imported project")
        manifest=source.get("_nativeProjectManifest")
        imported=source.get("nativeToolResult",{})
        if manifest is None or imported.get("verified") is not True or imported.get("projectImported") is not True:
            raise PermissionError("Original imported project provenance unavailable")
        _,environment=self.require_workspace(envelope,permission)
        current=lambda:self.require_workspace(envelope,permission)
        files=self.manager.platform.capture_project_copy(environment["root"],imported["path"],imported["directoryIdentity"],manifest,current)
        raw=write_modified(manifest,files)
        reference=self._file_reference(envelope,"modified-project.pocketworld.zip",len(raw),hashlib.sha256(raw).hexdigest())
        reference.update(kind="project_bundle",mimeType="application/zip")
        fixed=self.artifacts.register_stream(io.BytesIO(raw),reference)
        return self.artifacts.wire_descriptor(fixed,instance_id=self.identity.instance_id,revision=1)

    def _release_input(self, transfer):
        item = self._inputs.get(transfer)
        if item is None: return
        descriptor, _, job = item
        try:
            if self._input_cache is not None: self._input_cache.remove(descriptor)
        except Exception:
            job["inputCacheCleanupPending"] = True  # Changed objects are retained, never deleted blindly.
        if self._input_session is not None: self._input_session.release(transfer)
        self._inputs.pop(transfer, None)

    def discard_pending_input(self, job):
        """Original owner cancellation before replacement; cache is not a business ledger."""
        for transfer, (descriptor, _, owner) in list(self._inputs.items()):
            if owner is not job:
                continue
            try:
                self._input_cache.remove(descriptor)
            except Exception:
                job["inputCacheCleanupPending"] = True
            self._input_session.release(transfer)
            self._inputs.pop(transfer, None)

    def _duplicate_input_chunk(self, peer, frame):
        """An exact already-received chunk is a no-op, never another write/admission.

        Queueing is not delivery: a source may query an old cursor while the first
        frame is in flight. Receipts stay on the ORIGINAL job, including after its
        byte cache has been retired. Unknown business effects are never replayed.
        """
        if not isinstance(peer, device_identity.AuthenticatedPeer) or self.manager.closed or self.closed:
            raise PermissionError("Native bulk peer unavailable")
        transfer=str(frame.transfer_id)
        for job in self.manager.jobs.values():
            artifact=job.get("nativeInput",{}).get("artifact",{})
            if artifact.get("transferId")!=transfer: continue
            prior=job.get("_nativeInputChunks",{}).get(str(frame.offset))
            if prior is None: return False
            audience=job.get("nativeDeviceOperation",{})
            known=self.trust.peer(peer.device_id)
            grant=self.trust.grant(audience.get("grantId"))
            if (job.get("_nativeInputPeer")!={"deviceId":peer.device_id,"fingerprint":peer.certificate_fingerprint}
                    or known is None or known["revoked"] or known["fingerprint"]!=peer.certificate_fingerprint
                    or grant is None or grant["revoked"] or grant["expiresAt"]<=self.manager.now()
                    or grant["authorizationEpoch"]!=audience.get("authorizationEpoch") or known["authorizationEpoch"]!=grant["authorizationEpoch"]):
                raise PermissionError("Original duplicate input peer/grant is no longer current")
            if prior!={"bytes":len(frame.data),"sha256":hashlib.sha256(frame.data).hexdigest()}:
                raise PermissionError("Repeated chunk differs from the original verified byte receipt")
            return True
        return False

    def receive_bulk(self, peer, frame):
        with self.manager.lock:
            if self._input_session is None or frame.control is not None or frame.transfer_id is None:
                raise PermissionError("Unsolicited bytes lack original input admission")
            transfer = str(frame.transfer_id)
            if self._duplicate_input_chunk(peer,frame): return
            item=self._inputs.get(transfer)
            if item is None: raise PermissionError("Unsolicited transfer has no original admission")
            descriptor, _, job = item
            if (type(frame.offset) is not int or frame.offset<0 or frame.offset%262144
                    or not isinstance(frame.data,bytes) or len(frame.data)!=min(262144,descriptor["bytes"]-frame.offset) or not frame.data):
                raise PermissionError("Input chunk must match its original fixed 256KiB cursor")
            offset = self._input_session.accept(peer, transfer, frame.offset, frame.data)
            job["_nativeInputChunks"][str(frame.offset)]={"bytes":len(frame.data),"sha256":hashlib.sha256(frame.data).hexdigest()}
            job["nativeToolResult"]["verifiedOffset"] = offset
            self.manager._save(job)
            if offset == descriptor["bytes"]:
                try:
                    self._commit_input(peer, transfer)
                except BaseException:
                    if not job.get("nativeInputDispatchStarted"):
                        self._release_input(transfer)
                        job.update(status="failed", nativeToolResult={"status": "failed", "phase": "input_rejected",
                                   "transferId": transfer, "delivered": False, "replacementAttempted": False,
                                   "message": "Received input did not verify; target replacement was not attempted"}, updatedAt=self.manager.now())
                        self.manager._save(job)
                    raise

    def _native_tool(self, envelope, permission, peer=None):
        self.require_workspace(envelope, permission)
        incoming = envelope.arguments["toolName"] in {"workspace.receive", "workspace.project.receive"}
        if incoming:
            if self.manager._environment(envelope.get("resourceBinding")["environmentId"]).get("writeAllowed") is not True:
                raise PermissionError("Original workspace does not permit input writes")
            self._input_manifest(envelope)  # Reject unsupported size/kind before original reservation.
        operation = native_operation_id(envelope)
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", operation):
            raise PlatformError("Original Companion operation identity exceeds its fixed bound", 400)
        fingerprint = protocol.arguments_digest(envelope.json())
        with self.manager.lock:
            self._current(permission)
            found = self.manager.ledger.execute("SELECT fingerprint,job_id FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found:
                if found[0] != fingerprint:
                    raise PlatformError("Operation ID already reserved for different native tool semantics", 409)
                job = self.manager.jobs.get(found[1])
                if incoming and job and job.get("status") == "running" and job.get("nativeToolResult", {}).get("phase") == "awaiting_bytes":
                    return self._prepare_input(peer, envelope, permission, job)
                return copy.deepcopy(job["nativeToolResult"]) if job and "nativeToolResult" in job else self.manager.operation(operation)
            if incoming:
                selected=self._input_manifest(envelope)
                retained=self.manager.ledger.execute(
                    "SELECT 1 FROM operations WHERE CASE WHEN json_valid(details) THEN json_extract(details,'$.inputTransferId') ELSE NULL END=? LIMIT 1",
                    (selected["transferId"],)).fetchone()
                if selected["transferId"] in self._inputs or retained:
                    raise PlatformError("Transfer already belongs to another original operation; request was not reserved",409)
                if len(self._inputs)>=4 or sum(item[0]["bytes"] for item in self._inputs.values())+selected["bytes"]>40*1024*1024:
                    raise PlatformError("Native input cache full; request was not reserved",503)
            now, job_id = self.manager.now(), str(uuid.uuid4())
            job = {"id": job_id, "operationId": operation, "fingerprint": fingerprint, "worker": "device_tool", "status": "running",
                   "environmentId": envelope.get("resourceBinding")["environmentId"], "createdAt": now, "updatedAt": now, "output": "", "outputTruncated": False,
                   "exitCode": None, "remoteEffectsVerified": False, "message": "One current native workspace tool", "nativeDeviceOperation": _audience(envelope)}
            savepoint = "native_tool_" + uuid.uuid4().hex
            if self.manager.ledger.in_transaction:
                raise PlatformError("Original native tool ledger has an unsettled transaction")
            self.manager.ledger.execute("SAVEPOINT " + savepoint)
            try:
                job["budgetClaim"] = self.budget.claim(operation, envelope, permission, self.manager.ledger)
                self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                            (operation, fingerprint, job_id, "running", now, json.dumps({"protocol": TOOL_PROTOCOL, "audience": _audience(envelope),
                                                                                                      **({"inputTransferId": selected["transferId"]} if incoming else {}),
                                                                                                      "historyAudience": accepted_audience(envelope, permission, operation, now)})))
                self.manager.ledger.execute("RELEASE SAVEPOINT " + savepoint)
            except BaseException:
                self.manager.ledger.execute("ROLLBACK TO SAVEPOINT " + savepoint)
                self.manager.ledger.execute("RELEASE SAVEPOINT " + savepoint)
                raise
            self.manager.jobs[job_id] = job
            self.manager._save(job)
        try:
            # WorkspaceFiles shares the original SQLite connection/revision
            # authority. Match the original HTTP handler's monitor discipline;
            # another thread must not commit our file receipt mid-transaction.
            with self.manager.lock:
                if incoming:
                    return self._prepare_input(peer, envelope, permission, job)
                result = (self._confirm_project(envelope, permission, job) if envelope.arguments["toolName"] == "workspace.project.confirm"
                          else self._export_project(envelope, permission) if envelope.arguments["toolName"] == "workspace.project.export"
                          else self._workspace(envelope, permission))
            self._current(permission)
            with self.manager.lock:
                status = result.get("status", "completed") if envelope.arguments["toolName"] in {"workspace.write", "workspace.project.confirm"} else "completed"
                job.update(status=status, nativeToolResult=result, updatedAt=self.manager.now(), message="Original native tool receipt stored; user goal still requires verification")
                self.manager._save(job)
            return result
        except BaseException:
            with self.manager.lock:
                job.update(status="unknown" if envelope.side_effecting else "failed", updatedAt=self.manager.now(), message="Original native tool result unconfirmed; inspect this operation without replay")
                if incoming:
                    self._release_input(envelope.arguments["arguments"]["artifact"]["transferId"])
                    job["nativeToolResult"] = {"status": "unknown", "delivered": False,
                                               "message": "Input admission unconfirmed; inspect original operation without replay"}
                self.manager._save(job)
            raise

    def _query(self, envelope, permission):
        operation = envelope.arguments["operationId"]
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT job_id,details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if not row:
                return {"operationId": operation, "status": "not_found", "submitted": False}
            job = self.manager.jobs.get(row[0])
            detail = json.loads(row[1]) if row[1] else {}
            if job and job.get("protocol") == "desktop-v1" or detail.get("protocol") == "desktop-v1":
                return DesktopJobs.query_original(self.manager, envelope, permission)
            audience = job.get("nativeDeviceOperation") if job else detail.get("audience")
            if (not audience or any(envelope.get(key) != audience.get(key) for key in ("callerDeviceId", "originDeviceId", "rootTaskId", "localTaskId"))
                    or envelope.get("runId") != audience.get("requestRunId") or envelope.get("executorEpoch") != audience.get("requestExecutorEpoch")
                    or not protocol.resource_equal(envelope.get("resourceBinding"), audience.get("resourceBinding")) or not any(protocol.resource_equal(audience.get("resourceBinding"), item) for item in permission.resources)):
                raise PermissionError("Original operation is outside the current grant/task/resource audience")
            self._current(permission)
            result = self.manager.operation(operation)
            if audience.get("toolName") in {"workspace.receive", "workspace.project.receive", "workspace.project.confirm"}:
                result.update(copy.deepcopy(result.get("nativeToolResult", {})))
            if audience.get("toolName") in {"workspace.write", "workspace.receive"}:
                result["originalFileReceipt"] = self.manager.files.operation(operation)
            return result

    async def receive(self, peer, raw):
        envelope = None
        try:
            if not isinstance(peer, device_identity.AuthenticatedPeer):
                raise PermissionError("Actual native mutual-TLS peer required; legacy token is not device authority")
            envelope = protocol.validate_request(raw, peer, self.identity.device_id, self.manager.now())
            history_query = (envelope.method in {"operation.get", "artifact.describe", "artifact.readChunk"}
                             and envelope.get("originDeviceId") == envelope.get("callerDeviceId")
                             and not envelope.get("delegationChain", [])
                             and 0 < envelope.get("deadline") - self.manager.now() <= 30000)
            if envelope.method == "device.describe":
                permission = self.pairing.check_discovery_permission(peer, envelope, self.manager.now())
                self._current_discovery(permission)
            elif envelope.method == "operation.cancel":
                permission = self.pairing.check_operation_management_permission(peer, envelope, self.history, self.manager.now())
                self.management._current(permission)
            else:
                permission = (self.pairing.check_history_permission(peer, envelope, self.history, self.manager.now()) if history_query
                              else self.pairing.check_permission(peer, envelope, self.manager.now()))
                self._current_read(permission)
            if envelope.method == "device.describe":
                result = self.describe()
                receipts = self.pairing.discovery_grant_receipts(self.identity, permission, self.manager.now())
                result["grantReceipts"] = []
                for receipt in receipts:
                    if len(protocol.canonical_bytes(result["grantReceipts"] + [receipt])) > 16384:
                        result["grantReceiptsTruncated"] = True
                        break
                    result["grantReceipts"].append(receipt)
                metadata = {key: value for key, value in result.items() if key not in {"capabilities", "grantReceipts"}}
                now = self.manager.now()
                result = protocol.device_description(self.identity.device_id, self.identity.instance_id, now,
                                                     min(permission.deadline, now + 15000), result["capabilities"], result["grantReceipts"], metadata)
            elif envelope.method == "desktop.viewer":
                result = await asyncio.to_thread(self.desktop_viewer.invoke, envelope, permission)
            elif envelope.method == "tool.invoke":
                tool = envelope.arguments["toolName"]
                if tool in {"desktop.observe", "desktop.act", "workspace.export", "workspace.project.export"}:
                    self._register_export_context(envelope, permission)
                if tool.startswith("desktop."):
                    result = await asyncio.to_thread(self.desktop.invoke, envelope, permission)
                elif tool == "command.run":
                    result = await asyncio.to_thread(self._command, envelope, permission)
                elif tool in WORKSPACE_TOOLS or tool in PROJECT_TOOLS:
                    result = await asyncio.to_thread(self._native_tool, envelope, permission, peer)
                else:
                    raise PlatformError("Unknown native PC tool; no goal/Agent/shell inference", 409)
            elif envelope.method == "operation.get":
                result = await asyncio.to_thread(self.history.query_operation, permission) if history_query else await asyncio.to_thread(self._query, envelope, permission)
            elif envelope.method == "operation.cancel":
                result = await asyncio.to_thread(self.management.cancel, permission)
            elif envelope.method in {"artifact.describe", "artifact.readChunk"}:
                if history_query:
                    token = _HISTORY_READ.set(permission)
                    try:
                        result = await self.artifact_endpoint.receive_history(permission)
                    finally:
                        _HISTORY_READ.reset(token)
                else:
                    result = await self.artifact_endpoint.receive(permission)
            else:
                raise PlatformError("This lightweight PC bridge exposes explicit tools/original operations, not a second goal Agent", 409)
            if isinstance(permission, DiscoveryPermission):
                self._current_discovery(permission)
            elif isinstance(permission, OperationManagementPermission):
                self.management._current(permission)
            else:
                self._current_read(permission)
            if isinstance(result.get("output"), str) and len(result["output"].encode("utf-8")) > 16384:
                result = copy.deepcopy(result)
                result["output"] = result["output"].encode("utf-8")[-16384:].decode("utf-8", errors="ignore")
                result["outputFrameTruncated"] = True  # Original job remains unchanged.
            if envelope.method in {"tool.invoke", "operation.get", "operation.cancel"}:
                operation = envelope.arguments["operationId"] if envelope.method == "operation.get" else native_operation_id(envelope)
                result = operation_receipt_for(self.manager, self.identity.device_id, self.identity.instance_id, operation, result,
                                               include_cancellation=envelope.method == "operation.cancel")
            response = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "DeviceResponse", "requestId": envelope.get("requestId"),
                        "method": envelope.method, "executorDeviceId": self.identity.device_id, "instanceId": self.identity.instance_id, "result": result}
            return protocol.canonical_bytes(protocol.validate_message(response))
        except Exception as failure:
            unknown = False
            if envelope is not None and envelope.side_effecting:
                with self.manager.lock:
                    row = self.manager.ledger.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (envelope.get("operationId"),)).fetchone()
                    unknown = row is not None and row[0] == protocol.arguments_digest(envelope.json())
                if envelope.method == "desktop.viewer":
                    unknown = self.desktop_viewer.effect_unknown(envelope)
            code = failure.code if isinstance(failure, protocol.ProtocolError) else "permission_denied" if isinstance(failure, PermissionError) or isinstance(failure, PlatformError) and failure.status == 403 else "native_tool_unavailable"
            message = str(failure)[:300] if isinstance(failure, (protocol.ProtocolError, PermissionError, PlatformError)) else "Native device request could not be confirmed; inspect the original operation"
            response = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "DeviceError", "requestId": envelope.get("requestId") if envelope else None,
                        "error": {"code": code, "message": message or "Native device request refused", "retryable": False, "effectState": "unknown" if unknown else "not_dispatched"}}
            return protocol.canonical_bytes(protocol.validate_message(response))

    def cancel_desktop(self, job_id):
        return self.desktop.cancel(job_id)

    def close(self):
        self.desktop_viewer.close()
        self.closed = True
        if self._input_cache is not None:
            self._input_cache.close()
        return self.desktop.close()


class DeviceCompanionService:
    """User-enabled network lifecycle around the original deterministic receiver.

    Native route configuration is a reachability hint, never identity/grant.
    LAN and outbound WSS both use the transport domain's exact-pinned inner TLS.
    A disconnect clears channels only and never replays/cancels accepted work.
    """
    def __init__(self, manager, *, local_identity=None, native_lan_reservations=None):
        settings = manager.config.get("devices")
        if not isinstance(settings, dict) or settings.get("enabled") is not True or set(settings) - {"enabled", "identityDir", "listen", "routes"}:
            raise PlatformError("Explicit closed native devices configuration is required")
        self.manager, self.settings = manager, copy.deepcopy(settings)
        if local_identity is None:
            directory = Path(settings.get("identityDir", manager.state / "devices" / "identity-v1")).expanduser()
            if not directory.is_absolute():
                raise PlatformError("Existing native identity directory must be absolute")
            # This shared helper MUST exist in the integrated identity backend.
            # Loading never provisions a new private key/certificate at startup.
            loader = getattr(device_identity, "load_existing_identity", None)
            if loader is None:
                raise PlatformError("Native identity loader requires the current integrated backend; no automatic provisioning fallback")
            local_identity = loader(directory)
        if not isinstance(local_identity, device_identity.LocalIdentity):
            raise PlatformError("Existing native local identity required", 403)
        self.identity = local_identity
        self.receiver = CompanionDeviceReceiver(manager, local_identity, bulk_sender=self._send_bulk)
        self.loop = None
        self.thread = None
        self.ready = threading.Event()
        self.failure = None
        self.closed = False
        self.listeners, self.clients, self.tasks, self.connections = [], [], [], set()
        self.bulk_connections = {}
        self.bound_addresses = []
        self._validate_settings()
        # Inbound phone-task surfaces share this transport and original SQL. They
        # never create a second phone runner, pairing database or authorization.
        from phone_task_bridge import PhoneTaskRouter
        self.phone_tasks = PhoneTaskRouter(self)
        self.native_lan_reservations = {}
        if native_lan_reservations is not None:
            from device_transport import LanPortReservation
            if not isinstance(native_lan_reservations, dict) or set(native_lan_reservations) != {"control", "bulk"} or not isinstance(self.settings.get("listen"), dict):
                raise PlatformError("Native owner handoff requires both exact reserved LAN lanes")
            for lane, key in (("control", "controlPort"), ("bulk", "bulkPort")):
                reservation = native_lan_reservations[lane]
                if (not isinstance(reservation, LanPortReservation) or reservation.lane != lane or not reservation.is_reserved
                        or reservation.address != (self.settings["listen"]["host"], self.settings["listen"][key])):
                    raise PlatformError("Native LAN reservation differs from the reviewed current owner endpoint")
            self.native_lan_reservations = dict(native_lan_reservations)

    def _validate_settings(self):
        listen = self.settings.get("listen")
        if listen is not None:
            if not isinstance(listen, dict) or set(listen) != {"host", "controlPort", "bulkPort"}:
                raise PlatformError("Native LAN listener requires exact host/controlPort/bulkPort")
            import ipaddress
            ipaddress.ip_address(listen["host"])
            for key in ("controlPort", "bulkPort"):
                protocol.integer(listen[key], key, 0, 65535)
            if listen["controlPort"] and listen["controlPort"] == listen["bulkPort"]:
                raise PlatformError("Native control and bulk lanes require distinct ports")
        routes = self.settings.get("routes", [])
        if not isinstance(routes, list) or len(routes) > 32:
            raise PlatformError("Native route list exceeds its fixed bound")
        for route in routes:
            if not isinstance(route, dict) or not {"peerDeviceId", "grantId", "expiresAt"} <= route.keys() or route.keys() - {"peerDeviceId", "grantId", "expiresAt", "direct", "relay"} or ("direct" in route) == ("relay" in route):
                raise PlatformError("Native route requires one exact direct or relay hint")
            protocol.device_id(route["peerDeviceId"]); protocol.identifier(route["grantId"], "grantId"); protocol.integer(route["expiresAt"], "expiresAt", 1)
            if "direct" in route:
                direct = route["direct"]
                if not isinstance(direct, dict) or set(direct) != {"host", "controlPort", "bulkPort"} or not isinstance(direct["host"], str) or not re.fullmatch(r"[A-Za-z0-9._:-]{1,253}", direct["host"]):
                    raise PlatformError("Invalid native direct route hint")
                for key in ("controlPort", "bulkPort"):
                    protocol.integer(direct[key], key, 1, 65535)
            else:
                relay = route["relay"]
                if not isinstance(relay, dict) or set(relay) != {"baseUrl", "routeTokenSecretRef"}:
                    raise PlatformError("Relay route requires exact WSS baseUrl and protected secret reference")
                uri = urlsplit(relay["baseUrl"])
                if uri.scheme != "wss" or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or not re.fullmatch(r"/v1/tunnel/[A-Za-z0-9_-]{22,80}/[ab]", uri.path):
                    raise PlatformError("Invalid exact outbound WSS route hint")
                secret = relay["routeTokenSecretRef"]
                if not isinstance(secret, dict) or set(secret) != {"kind", "path"} or secret["kind"] != "state_file":
                    raise PlatformError("Only an original private state-file route secret reference is supported")
                self.manager.files.parts(secret["path"])

    def _paired(self, peer_id):
        known = self.receiver.trust.peer(peer_id)
        if known is None or known["revoked"]:
            raise PermissionError("Native route peer is unpaired or revoked")
        return known

    def _pins(self):
        with self.manager.lock:
            peers = [item for item in self.receiver.trust.peers(limit=128) if not item["revoked"]]
            if len(peers) > 64:
                raise PlatformError("Native server pin set exceeds its fixed bound")
            return tuple(base64.b64decode(self._paired(peer["deviceId"])["certificateDer"], validate=True) for peer in peers)

    def _secret(self, reference):
        path = reference["path"]
        if self.manager.platform.name == "windows":
            raw = self.manager.platform.read_bytes(str(self.manager.state), path, maximum=512)
        else:
            # Original no-follow directory traversal, not pathlib/open fallback.
            from workspace_files import WorkspaceFiles
            import stat
            parts = WorkspaceFiles.parts(path)
            directory = self.manager.files.directory({"root": str(self.manager.state), "kind": "local"}, parts[:-1])
            try:
                descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                with os.fdopen(descriptor, "rb") as stream:
                    info = os.fstat(stream.fileno())
                    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid() or info.st_mode & 0o077:
                        raise PlatformError("Route secret must be an original owner-only regular file")
                    raw = stream.read(513)
            finally:
                os.close(directory)
        if not isinstance(raw, bytes) or len(raw) > 512:
            raise PlatformError("Route secret missing or outside its fixed bound")
        token = raw.decode("ascii").strip()
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
            raise PlatformError("Invalid short-lived private relay lease")
        return token

    async def _bind(self, connection, peer, *, route=None):
        try:
            known = self._paired(peer.device_id)
        except PermissionError:
            await connection.close("revoked"); return
        if known["fingerprint"] != peer.certificate_fingerprint:
            await connection.close("revoked"); return
        if route is not None:
            if route["peerDeviceId"] != peer.device_id or route["expiresAt"] <= self.manager.now():
                await connection.close("revoked"); return
            if self.receiver.trust.outgoing_grant(peer.device_id, route["grantId"]) is None:
                await connection.close("revoked"); return
            outgoing = self.receiver.pairing.restore_outgoing_grant(peer, route["grantId"], self.manager.now())
            outgoing.require_target_peer(peer, self.manager.now())
        self.connections.add(connection)
        self.phone_tasks.connection_opened(connection, peer)
        if connection.lane == "bulk":
            self.bulk_connections[(peer.device_id, peer.certificate_fingerprint)] = connection
        async def incoming(actual_peer, frame):
            # The closure captures THIS native connection. Same-peer parallel
            # links cannot misroute a response by JSON/request/peer ID guessing.
            if actual_peer is not peer or not isinstance(actual_peer, device_identity.AuthenticatedPeer):
                await connection.close("incompatible"); return
            try:
                current = self._paired(peer.device_id)
            except PermissionError:
                await connection.close("revoked"); return
            if current["fingerprint"] != peer.certificate_fingerprint:
                await connection.close("revoked"); return
            if connection.lane != "control" or frame.control is None:
                if connection.lane == "bulk" and frame.control is None:
                    try:
                        await asyncio.to_thread(self.receiver.receive_bulk, peer, frame)
                    except Exception:
                        await connection.close("incompatible")
                    return
                await connection.close("incompatible"); return
            if self.phone_tasks.receive(connection, peer, frame.control):
                return
            response = await self.receiver.receive(peer, frame.control)
            if not connection.send_control(response):
                await connection.close()
        async def disconnected(delivery_may_be_unknown, error):
            self.connections.discard(connection)
            self.phone_tasks.connection_closed(connection)
            key = (peer.device_id, peer.certificate_fingerprint)
            if self.bulk_connections.get(key) is connection:
                self.bulk_connections.pop(key, None)
            # Accepted original jobs keep their own authority and dependencies.
            # No application request is queued for reconnect or cancelled here.
        connection.on_frame, connection.on_close = incoming, disconnected

    async def _send_bulk(self, permission, descriptor, original_read, transfer, offset, raw):
        self.receiver._current_read(permission)
        if permission.envelope.method != "artifact.readChunk" or not callable(original_read):
            raise PermissionError("Native original fixed byte-read gate is required, not a generic transfer grant")
        descriptor = copy.deepcopy(descriptor)
        fixed_bytes = protocol.canonical_bytes(descriptor)
        args = permission.envelope.arguments
        if (descriptor["artifactId"] != args["artifactId"] or descriptor["sha256"] != args["contentDigest"]
                or descriptor["transferId"] != str(transfer) or offset != args["offset"] or not isinstance(raw, bytes)
                or len(raw) != min(args["length"], descriptor["bytes"] - offset) or not raw):
            raise PermissionError("Queued bytes differ from the original native fixed artifact/cursor")
        peer_id = permission.envelope.get("callerDeviceId")
        known = self._paired(peer_id)
        connection = self.bulk_connections.get((peer_id, known["fingerprint"]))
        if connection is None or connection.closed or connection.state not in {"online", "degraded"} or not connection.tls.authenticated:
            raise PlatformError("No current authenticated bulk lane; chunk was not claimed delivered")
        def before_write(actual_peer):
            # This closure runs at the ACTUAL TLS write, not the control
            # request's task/context. Capture native authority + immutable
            # descriptor explicitly; never inherit a stale History ContextVar.
            self.receiver._current_read(permission)
            if (not isinstance(actual_peer, device_identity.AuthenticatedPeer) or actual_peer is not connection.tls.peer
                    or actual_peer.device_id != peer_id or actual_peer.certificate_fingerprint != known["fingerprint"]
                    or connection.closed or not connection.tls.authenticated
                    or self.bulk_connections.get((peer_id, actual_peer.certificate_fingerprint)) is not connection):
                raise PermissionError("Original bulk TLS connection or exact authenticated recipient changed")
            current = self._paired(peer_id)
            if current["fingerprint"] != actual_peer.certificate_fingerprint:
                raise PermissionError("Original bulk recipient pin was revoked or replaced")
            if protocol.canonical_bytes(self.receiver.artifacts.by_artifact_id(descriptor["artifactId"])) != fixed_bytes:
                raise PermissionError("Original immutable artifact cache version unavailable or changed")
            with self.manager.lock:
                accepted = self.manager.ledger.execute("SELECT descriptor,withdrawn FROM device_artifact_audiences WHERE artifact_id=?", (descriptor["artifactId"],)).fetchone()
            if accepted is None or accepted[1] != 0 or protocol.canonical_bytes(json.loads(accepted[0])) != fixed_bytes:
                raise PermissionError("Original accepted artifact audience/content was withdrawn or changed")
            original_read()  # Captured typed permission, original SQL access/source scope and screenshot TTL.
            return True
        before_write(connection.tls.peer)
        return connection.send_chunk(transfer, offset, raw, before_write=before_write)

    async def _start(self):
        import device_transport as transport
        listen = self.settings.get("listen")
        if listen is not None:
            def current_tls():
                # One lock / one current trust snapshot builds BOTH trust store
                # and exact-DER pins, including newly paired peers without any
                # listener restart. Empty/revoked snapshots refuse each accept.
                with self.manager.lock:
                    pins = self._pins()
                    return transport.native_device_context(self.identity, pins, server_side=True), pins
            for lane, key in (("control", "controlPort"), ("bulk", "bulkPort")):
                listener = await transport.listen_lan(listen["host"], listen[key], owner_enabled=True, native_tls_factory=current_tls,
                                                      lane=lane, on_frame=lambda peer, frame: None,
                                                      on_connection=self._bind, reservation=self.native_lan_reservations.get(lane))
                self.listeners.append(listener)
                self.bound_addresses.extend({"lane": lane, "address": socket.getsockname()[0], "port": socket.getsockname()[1]} for socket in listener.sockets)
        for route in self.settings.get("routes", []):
            if route["expiresAt"] <= self.manager.now():
                continue
            for lane in ("control", "bulk"):
                async def factory(route=route, lane=lane):
                    known = self._paired(route["peerDeviceId"])
                    if self.receiver.trust.outgoing_grant(route["peerDeviceId"], route["grantId"]) is None:
                        raise PermissionError("Native outgoing route lacks its current signed grant receipt")
                    pin = base64.b64decode(known["certificateDer"], validate=True)
                    async def bound(connection, peer):
                        await self._bind(connection, peer, route=route)
                    if "direct" in route:
                        direct = route["direct"]
                        context = transport.native_device_context(self.identity, [pin], server_side=False)
                        return await transport.open_lan(direct["host"], direct["controlPort" if lane == "control" else "bulkPort"], context=context,
                                                        expected_certificate_der=pin, lane=lane, on_frame=lambda peer, frame: None, on_connection=bound)
                    # Network TLS role only; never identity/grant from this ordering.
                    server_side = self.identity.device_id > route["peerDeviceId"]
                    context = transport.native_device_context(self.identity, [pin], server_side=server_side)
                    relay = route["relay"]
                    return await transport.open_relay(relay["baseUrl"] + "/" + lane, self._secret(relay["routeTokenSecretRef"]),
                                                      context=context, expected_certificate_der=pin, server_side=server_side, lane=lane,
                                                      on_frame=lambda peer, frame: None, on_connection=bound)
                client = transport.ReconnectingDeviceClient(owner_enabled=True, access_deadline_ms=route["expiresAt"], factory=factory)
                self.clients.append(client); self.tasks.append(asyncio.create_task(client.run()))

    def start(self):
        if self.thread is not None:
            raise PlatformError("Native device lifecycle was already started")
        def run():
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            try:
                self.loop.run_until_complete(self._start())
            except Exception as failure:
                self.failure = type(failure).__name__  # No body/token/path diagnostics.
                self.ready.set()
                self.loop.run_until_complete(self._stop_network())
                self.loop.close(); return
            self.ready.set()
            self.loop.run_forever()
            self.loop.run_until_complete(self._stop_network())
            self.loop.run_until_complete(self.loop.shutdown_asyncgens())
            self.loop.close()
        self.thread = threading.Thread(target=run, name="pocket-device-transport", daemon=True)
        self.thread.start()
        if not self.ready.wait(15) or self.failure:
            self.close()
            raise PlatformError("Native paired-device lifecycle failed to start; no unverified fallback listener")
        return self.public_status()

    async def _stop_network(self):
        await self.phone_tasks.close()
        await asyncio.gather(*(client.close() for client in self.clients), return_exceptions=True)
        await asyncio.gather(*(listener.close() for listener in self.listeners), return_exceptions=True)
        await asyncio.gather(*(connection.close() for connection in tuple(self.connections)), return_exceptions=True)
        if self.tasks:
            await asyncio.gather(*self.tasks, return_exceptions=True)
        await asyncio.gather(*(item.close() for item in self.native_lan_reservations.values()), return_exceptions=True)
        self.bulk_connections.clear(); self.connections.clear()

    def public_status(self):
        return {"enabled": True, "deviceId": self.identity.device_id, "instanceId": self.identity.instance_id,
                "state": "offline" if self.closed or self.failure else "online" if self.bound_addresses or self.connections else "waiting_for_paired_route",
                "lanes": copy.deepcopy(self.bound_addresses), "activeAuthenticatedChannels": len(self.connections),
                "acceptedJobsReplayedOnReconnect": False, "modelRequired": False}

    def cancel_desktop(self, job_id):
        return self.receiver.cancel_desktop(job_id)

    def close(self):
        if self.closed:
            return
        self.closed = True
        self.receiver.close()
        if self.loop is not None and not self.loop.is_closed() and self.thread is not threading.current_thread():
            future = asyncio.run_coroutine_threadsafe(self._stop_network(), self.loop)
            try:
                future.result(timeout=10)
            finally:
                self.loop.call_soon_threadsafe(self.loop.stop)
                self.thread.join(timeout=5)
