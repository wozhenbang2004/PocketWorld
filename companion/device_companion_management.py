"""Separately authorized cancellation on original operations/jobs, with no refill.

Cancelling an operation is not a global GUI emergency stop, a control lease or
proof that its prior effects were undone. The cancellation itself has a new
durable operation ID; retries inspect it and never issue the stop twice.
"""
import copy
import json
import re
import uuid

import device_protocol as protocol
from device_pairing import OperationManagementPermission
from platform_backend import PlatformError
from device_companion_history import RETENTION_MS

MANAGEMENT_PROTOCOL = "native-device-management-v1"


class OperationManagement:
    def __init__(self, manager):
        self.manager = manager

    def _current(self, permission):
        if not isinstance(permission, OperationManagementPermission) or self.manager.closed:
            raise PermissionError("Native current original-operation management permission required")
        permission.check_current(self.manager.now())

    def cancel(self, permission):
        self._current(permission)
        envelope, database = permission.envelope, self.manager.ledger
        operation, target = envelope.get("operationId"), envelope.arguments["operationId"]
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", operation or "") or operation == target:
            raise PlatformError("Cancellation requires its own new original Companion operation ID", 400)
        fingerprint = protocol.arguments_digest(envelope.json())
        with self.manager.lock:
            self._current(permission)
            found = database.execute("SELECT fingerprint FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found:
                if found[0] != fingerprint:
                    raise PlatformError("Cancellation ID is already reserved for different semantics")
                return {"managementOnly": True}  # NEVER repeat the stop or expose original content.
            target_row = database.execute("SELECT job_id FROM operations WHERE operation_id=?", (target,)).fetchone()
            if target_row is None:
                raise PermissionError("Original accepted operation is unavailable")
            now, job_id = self.manager.now(), str(uuid.uuid4())
            audience = permission.audience
            audience.update(objectId=operation, acceptedAt=now, retainedUntil=now + RETENTION_MS,
                            authorizationEpoch=envelope.get("authorizationEpoch"), argumentsDigest=fingerprint,
                            originDeviceId=permission.caller_device_id, callerDeviceId=permission.caller_device_id)
            for key in ("sourceShareId", "sourceIssuerDeviceId", "sourceGrantId", "sourceAuthorizationEpoch", "sourceProofFingerprint"):
                audience.pop(key, None)  # This is B's new management action, not resumed A work.
            job = {"id": job_id, "operationId": operation, "worker": "device_management", "status": "running",
                   "environmentId": self.manager.jobs.get(target_row[0], {}).get("environmentId", "original-archived-operation"),
                   "createdAt": now, "updatedAt": now, "output": "", "outputTruncated": False, "exitCode": None,
                   "remoteEffectsVerified": False, "message": "Stopping only the original accepted operation; prior effects retained",
                   "nativeDeviceOperation": {**copy.deepcopy(audience), "toolName": "operation.cancel"}}
            if database.in_transaction:
                raise PlatformError("Original cancellation prepare has an unsettled transaction")
            savepoint = "native_cancel_" + uuid.uuid4().hex
            database.execute("SAVEPOINT " + savepoint)
            try:
                database.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                 (operation, fingerprint, job_id, "running", now, json.dumps({"protocol": MANAGEMENT_PROTOCOL,
                                  "audience": job["nativeDeviceOperation"], "historyAudience": audience, "targetOperationId": target,
                                  "cancellation": {"targetOperationId": target, "targetArgumentsDigest": envelope.arguments["argumentsDigest"], "requested": False, "stopped": False}})))
                database.execute("RELEASE SAVEPOINT " + savepoint)
            except BaseException:
                database.execute("ROLLBACK TO SAVEPOINT " + savepoint); database.execute("RELEASE SAVEPOINT " + savepoint)
                raise
            self.manager.jobs[job_id] = job
            self.manager._save(job)
        requested, stopped = False, False
        try:
            self._current(permission)
            with self.manager.lock:
                target_job = self.manager.jobs.get(target_row[0])
            if target_job is not None:
                requested = True
                self._record_cancellation(operation, target, requested, stopped)
                result = self.manager.cancel(target_row[0])
                proof = result.get("processCleanupProof", {})
                stopped = proof.get("processGroupStoppedVerified") is True
                if result.get("protocol") == "desktop-v1":
                    stopped = result.get("status") == "cancelled" and result.get("desktopDispatchAttempted") is False
                self._record_cancellation(operation, target, requested, stopped)
            self._current(permission)
            receipt = {"targetOperationId": target, "requested": requested, "stopped": stopped,
                       "status": "completed" if stopped else "unknown", "priorEffectsRetained": True,
                       "noAutomaticReplay": True, "zeroExecutionBudgetCharge": True,
                       "globalDesktopStopRequested": False}
            with self.manager.lock:
                job.update(status=receipt["status"], nativeToolResult=receipt, updatedAt=self.manager.now(),
                           message="Original stop proof retained" if stopped else "Only a stop was requested; original effects or process stop remain unconfirmed")
                self.manager._save(job)
            return {"managementOnly": True}
        except BaseException:
            with self.manager.lock:
                job.update(status="unknown", updatedAt=self.manager.now(),
                           message="Original cancellation remains reserved; inspect it, do not repeat the stop")
                self.manager._save(job)
            raise

    def _record_cancellation(self, operation, target, requested, stopped):
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            detail = json.loads(row[0])
            detail["cancellation"] = {"targetOperationId": target, "targetArgumentsDigest": detail["cancellation"]["targetArgumentsDigest"], "requested": requested, "stopped": stopped}
            self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), operation))
            self.manager.ledger.commit()
