"""Desktop tools on the ORIGINAL Companion operation/job/approval authority.

No HTTP listener, token routing, extra Agent or separate task database. A caller
must already hold the shared device-v1 Permission created from the mTLS peer
and current owner grant. The same permission is rechecked before dispatch.
"""
from __future__ import annotations

import copy
import hashlib
import json
import re
import uuid

from desktop_adapter import DesktopError
from platform_backend import PlatformError
from device_control_projection import validate_tool_result
from cua_desktop_adapter import CAPABILITY_VERSION
from device_companion_history import native_operation_id, operation_receipt_for

PROTOCOL = "desktop-v1"
TOOLS = {"desktop.observe": "read", "desktop.act": "write", "desktop.control": "write"}
TERMINAL = {"completed", "failed", "cancelled", "interrupted", "unknown", "blocked"}


class DesktopJobs:
    def __init__(self, manager, adapter, *, budget_claim=None):
        self.manager, self.adapter = manager, adapter
        from device_operation_budget import NativeOperationBudget
        self.native_budget = NativeOperationBudget(manager)
        # The receiving spend index is in the ORIGINAL ledger. An optional
        # integrated native authority may replace this claim, not bypass it.
        self.budget_claim = self.native_budget.claim if budget_claim is None else budget_claim

    @staticmethod
    def _permission(envelope, permission):
        # Import the single identity-domain type. A JSON actor or duck-typed
        # check_current callable cannot create remote execution authority.
        from device_protocol import DeviceTaskEnvelope
        from device_pairing import Permission
        if not isinstance(envelope, DeviceTaskEnvelope) or not isinstance(permission, Permission):
            raise DesktopError("An authenticated device-v1 Permission is required", 403)
        if permission.envelope.json() != envelope.json():
            raise DesktopError("Permission belongs to a different exact request", 403)

    def _current(self, envelope, permission, job_id=None):
        self._permission(envelope, permission)
        permission.check_current(self.manager.now())
        if self.manager.closed:
            raise DesktopError("Companion is stopping", 503)
        if job_id is not None:
            with self.manager.lock:
                if job_id in self.manager.stopping or self.manager.jobs[job_id]["status"] in {"cancelled", "stopping"}:
                    raise DesktopError("Original desktop job was locally stopped; prior effects remain unverified", 409)

    @staticmethod
    def _parts(envelope):
        if envelope.method != "tool.invoke" or not isinstance(envelope.arguments, dict):
            raise DesktopError("Desktop tools require a device-v1 ToolInvocation", 400)
        payload = envelope.arguments
        tool = payload.get("toolName")
        if tool not in TOOLS or payload.get("effect") != TOOLS[tool] or set(payload) != {"toolName", "effect", "arguments"} or not isinstance(payload.get("arguments"), dict):
            raise DesktopError("Unknown desktop tool/effect/schema; no inferred action", 400)
        if envelope.get("capabilityVersion") != CAPABILITY_VERSION:
            raise DesktopError("Desktop capability version changed; review and renegotiate", 409)
        if not isinstance(envelope.get("localTaskId") or envelope.get("rootTaskId"), str) or not isinstance(envelope.get("runId"), str):
            raise DesktopError("Desktop observations/actions require the original task and run identity", 400)
        return tool, payload["arguments"]

    def _metadata(self, envelope):
        return {"taskId": envelope.get("localTaskId") or envelope.get("rootTaskId"), "requestRunId": envelope.get("runId"), "requestExecutorEpoch": envelope.get("executorEpoch"),
                "operationId": native_operation_id(envelope)}

    def _claim(self, operation, envelope, permission):
        if not callable(self.budget_claim):
            raise DesktopError("Original task tool-budget reservation is not connected; desktop execution is unavailable", 409)
        try:
            proof = self.budget_claim(operation, envelope, permission, self.manager.ledger)
        except PlatformError as failure:
            raise DesktopError(str(failure), failure.status) from failure
        if not isinstance(proof, dict) or proof.get("operationId") != operation or proof.get("reserved") is not True or proof.get("refundOnUnknown") is not False:
            raise DesktopError("Original budget authority did not reserve this exact operation", 409)
        return copy.deepcopy(proof)

    def _prepare_operation(self, operation, envelope, permission, job_id, state, now, details=None):
        from device_protocol import arguments_digest
        database = self.manager.ledger
        if database.in_transaction:
            raise DesktopError("Original ledger has an unsettled transaction; no desktop operation prepared")
        savepoint = "desktop_prepare_" + uuid.uuid4().hex
        database.execute("SAVEPOINT " + savepoint)
        try:
            claim = self._claim(operation, envelope, permission)
            from device_companion_history import accepted_audience
            details = copy.deepcopy(details) if details is not None else {}
            details["historyAudience"] = accepted_audience(envelope, permission, operation, now)
            database.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                             (operation, arguments_digest(envelope.json()), job_id, state, now,
                              json.dumps(details) if details is not None else None))
            database.execute("RELEASE SAVEPOINT " + savepoint)
            return claim
        except BaseException:
            # The original operation ID and native spend are a single atomic
            # decision. No pending/unknown/cancel path later refunds this spend.
            if database.in_transaction:
                database.execute("ROLLBACK TO SAVEPOINT " + savepoint)
                database.execute("RELEASE SAVEPOINT " + savepoint)
            raise

    def invoke(self, envelope, permission):
        from device_protocol import arguments_digest
        self._current(envelope, permission)
        tool, arguments = self._parts(envelope)
        if tool == "desktop.observe":
            return self._observe(envelope, permission, arguments)
        operation = envelope.get("operationId")
        if not isinstance(operation, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", operation):
            raise DesktopError("Desktop mutations require an original Companion operationId (1-80 characters)", 400)
        fingerprint = arguments_digest(envelope.json())
        if envelope.get("argumentsDigest") != fingerprint:
            raise DesktopError("Desktop operation arguments digest does not match", 400)
        with self.manager.lock:
            self._current(envelope, permission)
            found = self.manager.ledger.execute("SELECT fingerprint,job_id FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if found:
                if found[0] != fingerprint:
                    raise DesktopError("Original operationId is already reserved for different semantics", 409)
                return self.operation(operation)
            if sum(job.get("status") not in TERMINAL for job in self.manager.jobs.values()) >= 12:
                raise DesktopError("Original Companion queue is full", 503)
            now, job_id = self.manager.now(), str(uuid.uuid4())
            job = {"id": job_id, "operationId": operation, "fingerprint": fingerprint, "worker": "desktop",
                   "protocol": PROTOCOL, "environmentId": "desktop-session", "status": "queued", "createdAt": now, "updatedAt": now,
                   "output": "", "outputTruncated": False, "exitCode": None, "message": "Queued in original Companion job supervisor",
                   "remoteEffectsVerified": False, "desktopDispatchAttempted": False,
                   "desktopOperation": {"toolName": tool, "argumentsDigest": fingerprint, "resourceBinding": envelope.get("resourceBinding"),
                                        "observationRef": envelope.get("observationRef"), **self._metadata(envelope),
                                        **{key: envelope.get(key) for key in ("originDeviceId", "callerDeviceId", "rootTaskId", "localTaskId", "parentTaskId", "grantId", "authorizationEpoch", "instanceId", "capabilityVersion")}}}
            # Existing operations table is the sole mutation identity authority.
            job["budgetClaim"] = self._prepare_operation(operation, envelope, permission, job_id, "queued", now,
                                                         {"protocol": PROTOCOL, "jobId": job_id, "status": "queued", "audience": job["desktopOperation"]})
            self.manager.jobs[job_id] = job
            try:
                self.manager._save(job)
                self.manager.pool.submit(self._run, job_id, envelope, permission)
            except BaseException:
                # The ID stays consumed even if saving/submitting fails. There
                # is no compensating deletion followed by a fresh input attempt.
                job.update(status="unknown", message="Original operation reserved but dispatch scheduling unconfirmed", updatedAt=self.manager.now())
                self._persist(job)
                raise
            return self.manager._public(job)

    def _observe(self, envelope, permission, arguments):
        """A paid, correlated read uses the SAME operation/job store, not a new ledger."""
        from device_protocol import arguments_digest
        operation = native_operation_id(envelope)
        fingerprint = arguments_digest(envelope.json())
        with self.manager.lock:
            self._current(envelope, permission)
            row = self.manager.ledger.execute("SELECT fingerprint,job_id FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if row:
                if row[0] != fingerprint:
                    raise DesktopError("Observation request identity was reused with different semantics", 409)
                job = self.manager.jobs.get(row[1])
                if job and job.get("desktopObservation"):
                    return copy.deepcopy(job["desktopObservation"])
                return self.operation(operation)
            if sum(job.get("status") not in TERMINAL for job in self.manager.jobs.values()) >= 12:
                raise DesktopError("Original Companion queue is full", 503)
            now, job_id = self.manager.now(), str(uuid.uuid4())
            job = {"id": job_id, "operationId": operation, "fingerprint": fingerprint, "worker": "desktop", "protocol": PROTOCOL,
                   "environmentId": "desktop-session", "status": "running", "createdAt": now, "updatedAt": now,
                   "output": "", "outputTruncated": False, "exitCode": None, "remoteEffectsVerified": False,
                   "message": "Reading one authorized desktop observation",
                   "desktopOperation": {"toolName": "desktop.observe", "resourceBinding": envelope.get("resourceBinding"), **self._metadata(envelope),
                                        **{key: envelope.get(key) for key in ("originDeviceId", "callerDeviceId", "rootTaskId", "localTaskId", "parentTaskId", "grantId", "authorizationEpoch", "instanceId", "capabilityVersion")}}}
            job["budgetClaim"] = self._prepare_operation(operation, envelope, permission, job_id, "running", now,
                                                         {"protocol": PROTOCOL, "jobId": job_id, "status": "running", "audience": job["desktopOperation"]})
            self.manager.jobs[job_id] = job
            self._persist(job)
        try:
            observation = self.adapter.observe(envelope.get("resourceBinding"), arguments, self._metadata(envelope), lambda: self._current(envelope, permission, job_id))
            settlement = {"proofScope": "synchronous_desktop_observation", "operationId": operation,
                          "adapterFinished": True, "noFurtherDispatchForOperation": True, "inputDispatched": False,
                          "osInputQueueDrained": False, "sharedDriverStopped": False, "terminalVerified": True}
            observation.update(jobId=job_id, readOperationId=operation, executionSettlement=settlement)
            validate_tool_result(envelope, operation_receipt_for(self.manager, envelope.get("targetDeviceId"), envelope.get("instanceId"), operation,
                                                                 observation, status_override="completed"))
            with self.manager.lock:
                job.update(status="completed", desktopObservation=observation, executionSettlement=settlement,
                           message="Actual observation captured; no input was dispatched", updatedAt=self.manager.now())
                self._persist(job)
            return copy.deepcopy(observation)
        except BaseException:
            with self.manager.lock:
                job.update(status="failed", message="Desktop observation unconfirmed; request a fresh read with a new requestId", updatedAt=self.manager.now())
                self._persist(job)
            raise

    def query(self, envelope, permission):
        return self.query_original(self.manager, envelope, permission)

    @staticmethod
    def query_original(manager, envelope, permission):
        DesktopJobs._permission(envelope, permission)
        permission.check_current(manager.now())
        if envelope.method != "operation.get":
            raise DesktopError("Original desktop query requires operation.get", 400)
        operation = envelope.arguments.get("operationId")
        with manager.lock:
            row = manager.ledger.execute("SELECT job_id,last_status,details FROM operations WHERE operation_id=?", (operation,)).fetchone()
            if not row:
                return {"operationId": operation, "status": "not_found", "submitted": False}
            job = manager.jobs.get(row[0])
            detail = json.loads(row[2]) if row[2] else {}
            audience = job.get("desktopOperation") if job and job.get("protocol") == PROTOCOL else detail.get("audience") if detail.get("protocol") == PROTOCOL else None
            if not isinstance(audience, dict):
                raise DesktopError("Original desktop audience metadata is unavailable; local owner reconciliation required", 403)
            if (any(envelope.get(key) != audience.get(key) for key in ("callerDeviceId", "originDeviceId", "rootTaskId", "localTaskId"))
                    or envelope.get("runId") != audience.get("requestRunId") or envelope.get("executorEpoch") != audience.get("requestExecutorEpoch")):
                raise DesktopError("Operation is outside this authenticated task audience", 403)
            binding = audience["resourceBinding"]
            from device_protocol import resource_equal
            if not resource_equal(envelope.get("resourceBinding"), binding) or not any(resource_equal(binding, item) for item in permission.resources):
                raise DesktopError("Current owner grant does not include the original operation resource", 403)
            permission.check_current(manager.now())
            if job:
                return manager._public(job)
            return {"operationId": operation, "id": row[0], "status": "unknown", "lastKnownStatus": row[1], "archived": True,
                    "desktopReceipt": detail.get("desktopReceipt"), "executionSettlement": detail.get("executionSettlement"),
                    "noAutomaticReplay": True, "historicalReadOnly": True}

    def cancel(self, job_id):
        with self.manager.lock:
            job = self.manager.jobs.get(job_id)
            if not job or job.get("protocol") != PROTOCOL:
                raise DesktopError("Not an original desktop job", 404)
            if job.get("status") in TERMINAL:
                return self.manager._public(job)
            self.manager.stopping.add(job_id)
            job.update(status="unknown" if job.get("desktopDispatchAttempted") else "cancelled",
                       desktopCleanupProof={"taskOnly": True, "globalDesktopStopRequested": False, "alreadyQueuedOsInputRetracted": False},
                       message="Only this original job is cancelled; queued OS input cannot be retracted, shared/new-fence Driver is not stopped", updatedAt=self.manager.now())
            self._persist(job)
            return self.manager._public(job)

    def close(self):
        return self.adapter.close()

    def operation(self, operation_id):
        with self.manager.lock:
            row = self.manager.ledger.execute("SELECT job_id,last_status,details FROM operations WHERE operation_id=?", (operation_id,)).fetchone()
            if not row:
                return {"operationId": operation_id, "status": "not_found", "submitted": False}
            job = self.manager.jobs.get(row[0])
            if job:
                return self.manager._public(job)
            detail = json.loads(row[2]) if row[2] else {}
            if detail.get("protocol") != PROTOCOL:
                raise DesktopError("Operation belongs to a different original Companion tool", 409)
            return {"operationId": operation_id, "id": row[0], "status": "unknown", "lastKnownStatus": row[1],
                    "archived": True, "desktopReceipt": detail.get("desktopReceipt"), "executionSettlement": detail.get("executionSettlement"), "noAutomaticReplay": True}

    def _persist(self, job):
        self.manager._save(job)
        original = self.manager.ledger.execute("SELECT details FROM operations WHERE operation_id=?", (job["operationId"],)).fetchone()
        original = json.loads(original[0]) if original and original[0] else {}
        detail = {"protocol": PROTOCOL, "jobId": job["id"], "status": job["status"],
                  "desktopDispatchAttempted": job.get("desktopDispatchAttempted", False), "desktopReceipt": job.get("desktopReceipt"),
                  "executionSettlement": copy.deepcopy(job.get("executionSettlement")),
                  "audience": copy.deepcopy(job["desktopOperation"])}
        if "historyAudience" in original:
            detail["historyAudience"] = original["historyAudience"]
        self.manager.ledger.execute("UPDATE operations SET details=? WHERE operation_id=?", (json.dumps(detail), job["operationId"]))
        self.manager.ledger.commit()

    def _run(self, job_id, envelope, permission):
        tool, arguments = self._parts(envelope)
        current = lambda: self._current(envelope, permission, job_id)
        dispatched = False
        def dispatching():
            nonlocal dispatched
            current()
            with self.manager.lock:
                current()
                job = self.manager.jobs[job_id]
                job.update(desktopDispatchAttempted=True, message="Original input intent persisted; result not confirmed", updatedAt=self.manager.now())
                self._persist(job)
                dispatched = True
        try:
            current()
            with self.manager.lock:
                job = self.manager.jobs[job_id]
                job.update(status="running", message="Executing one authorized desktop tool", updatedAt=self.manager.now())
                self._persist(job)
            if tool == "desktop.control":
                receipt = self.adapter.control(arguments.get("action"), envelope.get("resourceBinding"), envelope.get("callerDeviceId"), arguments, current)
            else:
                receipt = self.adapter.act(envelope.get("resourceBinding"), envelope.get("observationRef"), envelope.get("callerDeviceId"),
                                           arguments, self._metadata(envelope), current, dispatching)
            with self.manager.lock:
                job = self.manager.jobs[job_id]
                job.update(status=receipt["status"], desktopReceipt=receipt, remoteEffectsVerified=receipt.get("remoteEffectsVerified", False),
                           message=receipt.get("message", "Desktop resource-control receipt stored"), updatedAt=self.manager.now())
                validate_tool_result(envelope, operation_receipt_for(self.manager, envelope.get("targetDeviceId"), envelope.get("instanceId"), job["operationId"], self.manager._public(job)))
                self._persist(job)
        except BaseException as failure:
            with self.manager.lock:
                job = self.manager.jobs[job_id]
                attempted = dispatched or job.get("desktopDispatchAttempted", False)
                job.update(status="unknown" if attempted else "cancelled" if job_id in self.manager.stopping else "failed", remoteEffectsVerified=False,
                           message="Possible input effect is unknown; query this original operation and observe, never replay" if attempted else "Desktop tool refused before input dispatch: " + str(failure),
                           updatedAt=self.manager.now())
                self._persist(job)
        finally:
            # This is the LAST point of this original operation's adapter
            # worker. It never means the shared Driver was killed or that the
            # OS input queue was drained, and unknown effects remain unresolved.
            with self.manager.lock:
                job = self.manager.jobs[job_id]
                receipt = job.get("desktopReceipt") or {}
                replied = isinstance(receipt.get("actionOutcome"), dict) and isinstance(receipt.get("afterObservation"), dict)
                verified = (receipt.get("businessResultVerified") is True or
                            replied and receipt.get("actionOutcome", {}).get("kind") == "refused_pre_dispatch"
                            and receipt.get("inputDispatched") is False)
                control = tool == "desktop.control" and job.get("status") == "completed" and not job.get("desktopDispatchAttempted")
                job["executionSettlement"] = {"proofScope": "desktop_resource_control" if control else "single_gui_operation",
                                              "operationId": job["operationId"], "adapterFinished": True,
                                              "noFurtherDispatchForOperation": True, "driverReplyObserved": replied,
                                              "conditionVerified": receipt.get("businessResultVerified") is True,
                                              "postObservationAvailable": isinstance(receipt.get("afterObservation"), dict),
                                              "osInputQueueDrained": False, "sharedDriverStopped": False,
                                              "terminalVerified": control or job.get("status") in {"completed", "failed"} and replied and verified}
                try:
                    validate_tool_result(envelope, operation_receipt_for(self.manager, envelope.get("targetDeviceId"), envelope.get("instanceId"), job["operationId"], self.manager._public(job)))
                except BaseException:
                    job["executionSettlement"]["terminalVerified"] = False
                    job.update(status="unknown", message="Original single-operation settlement could not fit the shared control contract; no replay or refund")
                self._persist(job)
