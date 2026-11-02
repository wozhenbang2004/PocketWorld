"""Immutable result audience in the ORIGINAL Companion ledger, not execution authority.

Old rows without acceptance metadata stay local-only. Target TaskStore runs are
not invented for deterministic PC jobs: source correlation has explicit request*
names and can never authorize target task control.
"""
from __future__ import annotations

import copy
import hashlib
import json

import device_protocol as protocol
from device_pairing import HistoryPermission, NativeHistoryAudienceSource, Permission
from platform_backend import PlatformError

RETENTION_MS = 7 * 24 * 60 * 60 * 1000
MAX_ARTIFACT_AUDIENCES = 4096
_STATUS = {"queued": "accepted", "completed": "succeeded", "blocked": "failed", "stopping": "cancel_requested"}


def operation_receipt_for(manager, executor_device_id, instance_id, operation, payload, *, include_cancellation=False, status_override=None):
    """Unique wire formatter over durable ORIGINAL operation acceptance facts."""
    with manager.lock:
        row = manager.ledger.execute("SELECT fingerprint,job_id,last_status,details FROM operations WHERE operation_id=?", (operation,)).fetchone()
        if row is None:
            return protocol.operation_receipt(executor_device_id, instance_id, None, None, "not_found", False, "unknown", payload)
        detail = json.loads(row[3]) if row[3] else {}
        audience = detail.get("historyAudience")
        if not isinstance(audience, dict):
            raise PermissionError("Original immutable acceptance metadata is missing; result remains local-only")
        audience = copy.deepcopy(audience)
        if audience.get("argumentsDigest", row[0]) != row[0]:
            raise PermissionError("Original committed operation digest/audience changed")
        audience["argumentsDigest"] = row[0]
        job = manager.jobs.get(row[1], {})
        native_status = status_override or payload.get("status") or row[2]
        status = _STATUS.get(native_status, native_status)
        if status not in protocol.TASK_STATES | {"prepared", "unknown", "not_found", "rejected"}:
            status = "unknown"
        effect = "unknown" if status in {"unknown", "not_found", "needs_reconcile"} else "reported"
        if status != "unknown" and (native_status == "queued" or job.get("notStartedConfirmed") is True
                                    or job.get("desktopReceipt", {}).get("inputDispatched") is False
                                    or payload.get("replacementAttempted") is False):
            effect = "not_dispatched"
        management = detail.get("protocol") == "native-device-management-v1"
        cancellation = detail.get("cancellation") if include_cancellation or management else None
        if include_cancellation and cancellation is None:
            raise PermissionError("Original durable cancellation intent is missing")
        if management:
            payload = {"managementOnly": True}  # Never return original job/output/source content.
        return protocol.operation_receipt(executor_device_id, instance_id, operation, row[0], status, True, effect, payload,
                                          accepted_at=audience["acceptedAt"], audience=audience, job_id=row[1], cancellation=cancellation)


def native_operation_id(envelope):
    """Original PC operation correlation, including paid deterministic reads."""
    if envelope.get("operationId"):
        return envelope.get("operationId")
    prefix = "desktop-read-" if envelope.arguments.get("toolName") == "desktop.observe" else "device-read-"
    return prefix + hashlib.sha256((envelope.get("callerDeviceId") + "/" + envelope.get("requestId")).encode()).hexdigest()


def accepted_audience(envelope, permission, object_id, now_ms, *, kind="operation", retained_until=None):
    if not isinstance(permission, Permission) or permission.envelope.json() != envelope.json():
        raise TypeError("Original native execution admission required at object acceptance")
    permission.check_current(now_ms)
    value = {"kind": kind, "objectId": object_id,
             **{key: envelope.get(key) for key in ("originDeviceId", "callerDeviceId", "targetDeviceId", "instanceId", "rootTaskId", "grantId", "authorizationEpoch", "resourceBinding")},
             "localTaskId": envelope.get("localTaskId") or envelope.get("rootTaskId"),
             "acceptedAt": now_ms, "retainedUntil": min(now_ms + RETENTION_MS, retained_until or now_ms + RETENTION_MS)}
    if kind == "operation":
        value["argumentsDigest"] = protocol.arguments_digest(envelope.json())
    # Incoming run/epoch belong to the SOURCE request, never a target PC run.
    if envelope.get("runId") is not None and envelope.get("executorEpoch") is not None:
        value.update(requestRunId=envelope.get("runId"), requestExecutorEpoch=envelope.get("executorEpoch"))
    share = permission.source_share
    if share is not None:
        value.update(sourceShareId=share.share_id, sourceIssuerDeviceId=share.issuer_device_id,
                     sourceGrantId=share.source_grant_id, sourceAuthorizationEpoch=share.source_authorization_epoch,
                     sourceProofFingerprint=share.source_proof_fingerprint)
    return copy.deepcopy(value)


class CompanionHistory(NativeHistoryAudienceSource):
    """Read accepted audiences only from durable SQL, even after job-cache eviction."""
    def __init__(self, manager):
        self.manager = manager
        with manager.lock:
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS device_artifact_audiences(artifact_id TEXT PRIMARY KEY, descriptor TEXT NOT NULL, audience TEXT NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0)")
            manager.ledger.commit()

    def accepted_audience(self, query):
        with self.manager.lock:
            if query.method in {"operation.get", "operation.cancel"}:
                row = self.manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (query.arguments["operationId"],)).fetchone()
                detail = json.loads(row[1]) if row and row[1] else {}
                audience = detail.get("historyAudience")
                if isinstance(audience, dict):
                    audience = copy.deepcopy(audience)
                    if audience.get("argumentsDigest", row[0]) != row[0]:
                        raise PermissionError("Original SQL object digest/audience changed")
                    audience["argumentsDigest"] = row[0]
            elif query.method in {"artifact.describe", "artifact.readChunk"}:
                row = self.manager.ledger.execute("SELECT audience FROM device_artifact_audiences WHERE artifact_id=?", (query.arguments["artifactId"],)).fetchone()
                audience = json.loads(row[0]) if row else None
            else:
                audience = None
        if not isinstance(audience, dict):
            raise PermissionError("Original durable accepted audience unavailable; this result is local-only")
        return copy.deepcopy(audience)

    # Deliberately retain the shared default: direct source is visible; forwarded
    # results without an independently current native source-visibility record are
    # denied, not revived by an old signed execution proof or an in-memory peer.

    def source_visible(self, audience, now_ms):
        if not super().source_visible(audience, now_ms):
            return False
        with self.manager.lock:
            if audience["kind"] == "operation":
                row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (audience["objectId"],)).fetchone()
                return bool(row and row[0]) and json.loads(row[0]).get("historyVisibilityWithdrawn") is not True
            if audience["kind"] == "artifact":
                row = self.manager.ledger.execute("SELECT withdrawn FROM device_artifact_audiences WHERE artifact_id=?", (audience["objectId"],)).fetchone()
                return row is not None and row[0] == 0
        return False

    def record_artifact(self, descriptor, envelope, permission):
        now = self.manager.now()
        audience = accepted_audience(envelope, permission, descriptor["artifactId"], now, kind="artifact",
                                     retained_until=descriptor.get("expiresAt"))
        with self.manager.lock:
            database = self.manager.ledger
            old = database.execute("SELECT descriptor,audience FROM device_artifact_audiences WHERE artifact_id=?", (descriptor["artifactId"],)).fetchone()
            if old:
                if protocol.canonical_bytes(json.loads(old[0])) != protocol.canonical_bytes(descriptor):
                    raise PlatformError("Original artifact identity was already accepted with different bytes")
                return  # Preserve original acceptance time, audience and retention.
            database.execute("DELETE FROM device_artifact_audiences WHERE CAST(json_extract(audience,'$.retainedUntil') AS INTEGER)<=?", (now,))
            if database.execute("SELECT COUNT(*) FROM device_artifact_audiences").fetchone()[0] >= MAX_ARTIFACT_AUDIENCES:
                database.commit()
                raise PlatformError("Retained artifact audience bound reached; no object published as available", 503)
            database.execute("INSERT INTO device_artifact_audiences(artifact_id,descriptor,audience) VALUES (?,?,?)", (descriptor["artifactId"], json.dumps(descriptor), json.dumps(audience)))
            database.commit()

    def validate_descriptor(self, permission, descriptor):
        if not isinstance(permission, HistoryPermission) or permission.audience["kind"] != "artifact" or permission.audience["objectId"] != descriptor["artifactId"]:
            raise PermissionError("Fixed artifact history permission required")
        permission.check_current(self.manager.now())
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT descriptor FROM device_artifact_audiences WHERE artifact_id=?", (descriptor["artifactId"],)).fetchone()
        if row is None or protocol.canonical_bytes(json.loads(row[0])) != protocol.canonical_bytes(descriptor):
            raise PermissionError("Original accepted artifact version/source/correlation changed")

    def query_operation(self, permission):
        if not isinstance(permission, HistoryPermission) or permission.envelope.method != "operation.get":
            raise PermissionError("A fixed original operation history permission is required")
        permission.check_current(self.manager.now())
        operation = permission.audience["objectId"]
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            detail = json.loads(row[0]) if row and row[0] else {}
            result = copy.deepcopy(self.manager.operation(operation))
            if detail.get("audience", {}).get("toolName") in {"workspace.receive", "workspace.project.receive", "workspace.project.confirm"}:
                result.update(copy.deepcopy(result.get("nativeToolResult", {})))
            if detail.get("audience", {}).get("toolName") in {"workspace.write", "workspace.receive"}:
                result["originalFileReceipt"] = self.manager.files.operation(operation)
            if detail.get("protocol") == "desktop-v1" and result.get("archived"):
                result["desktopReceipt"] = detail.get("desktopReceipt")
        result.update(historicalReadOnly=True, originalOriginDeviceId=permission.original_origin_device_id,
                      acceptedAuthorizationEpoch=permission.accepted_authorization_epoch)
        # A result read is never a task-control lease or a resumed execution.
        result["taskControl"] = False
        if isinstance(result.get("controls"), dict):
            result["controls"] = {key: False for key in result["controls"]}
        permission.check_current(self.manager.now())
        return result
