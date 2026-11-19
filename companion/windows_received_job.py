"""Stored direct receiver and original private Windows job. Never a grant or a workspace reader."""
import json
import uuid

import device_protocol as protocol
from platform_backend import PlatformError
from windows_installation import _object

COMMAND, FILE, EXPORT, TEXT_WRITE = "command.run", "workspace.receive", "workspace.export", "workspace.write"
READS = frozenset({"workspace.list", "workspace.read"})
TOOLS = {COMMAND: "native-device-command-v1", FILE: "native-device-tool-v1", **{k: "native-device-tool-v1" for k in READS}}


def admission(manager, operation, row, *, owner_export=False, owner_text_write=False):
    """Validate stored identities only; caller independently owns the current read authority."""
    if not row or manager.platform.name != "windows": return None
    fingerprint, job_id, _last, raw = row
    try:
        detail = json.loads(raw) if raw else {}
        tool = (detail.get("audience") or {}).get("toolName")
        # Local-only projections must not change the peer's existing operation.get shape.
        expected = "native-device-tool-v1" if owner_export and tool==EXPORT or owner_text_write and tool==TEXT_WRITE else TOOLS.get(tool)
        if expected is None or detail.get("protocol") != expected:
            return None
        source, accepted = detail["audience"], detail["historyAudience"]
        protocol.validate_accepted_audience(accepted)
        binding = protocol.validate_resource_binding("workspace-v1", accepted["resourceBinding"])
        if (source.get("capabilityVersion") != "workspace-v1"
                or source.get("originDeviceId") != source.get("callerDeviceId") or source.get("parentTaskId") != source.get("rootTaskId")
                or accepted.get("kind") != "operation" or accepted.get("objectId") != operation
                or accepted.get("argumentsDigest") != fingerprint or str(uuid.UUID(job_id)) != job_id
                or (accepted["targetDeviceId"], accepted["instanceId"]) != getattr(manager, "_native_device_resource_instance", None)
                or not protocol.resource_equal(binding, source["resourceBinding"])
                or not source.get("requestRunId") or type(source.get("requestExecutorEpoch")) is not int
                or any(accepted.get(k) != source.get(k) for k in ("originDeviceId", "callerDeviceId", "targetDeviceId", "instanceId",
                       "rootTaskId", "localTaskId", "requestRunId", "requestExecutorEpoch", "grantId", "authorizationEpoch"))):
            return None
        return source, accepted
    except (KeyError, TypeError, AttributeError, ValueError, RecursionError, protocol.ProtocolError):
        return None


def retained(manager, operation, row, original):
    source, accepted = original
    fingerprint, job_id, status, _ = row
    job = manager.jobs.get(job_id)
    if not job: return None
    try:
        backend, root = manager.platform, str(manager.state)
        # Bounded same-handle read of the original, owner-private, retained job.
        sid = backend.logon_context()["userSid"]
        backend.assert_private_directory(root, sid, require_protected=False)
        raw = backend.read_private_bytes(root, "job-" + job_id + ".json", sid, maximum=1536*1024 if source["toolName"] == COMMAND else 96*1024)
        fixed = _object(raw)
        if (not 0 < fixed["nativeDeviceRevision"] == job["nativeDeviceRevision"] or type(fixed["nativeDeviceRevision"]) is not int
                or fixed.get("id") != job_id or fixed.get("operationId") != operation or fixed.get("fingerprint") != fingerprint
                or fixed.get("worker") != ("command" if source["toolName"] == COMMAND else "device_tool") or fixed.get("environmentId") != accepted["resourceBinding"]["environmentId"] or fixed.get("status") != status
                or not protocol.resource_equal(fixed["nativeDeviceOperation"], source)
                or any(fixed.get(k) != job.get(k) for k in ("id", "operationId", "fingerprint", "worker", "environmentId", "status", "nativeDeviceOperation"))):
            return None
        return fixed
    except (KeyError, OSError, AttributeError, TypeError, ValueError, RecursionError, PlatformError, protocol.ProtocolError):
        return None
