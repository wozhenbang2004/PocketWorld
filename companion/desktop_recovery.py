"""Explicit LOCAL resource recovery, preserving every unknown original operation.

Regular recovery requires original host PID-generation exit and the ORIGINAL
named Job Object drained/destroyed. Access failures/legacy missing bindings are
not stop proof. A separate physically confirmed owner-attestation route is
honestly labeled and never claims OS verification. No network method invokes it.
"""
import base64
import copy
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import uuid

from desktop_owner_confirmation import confirm_local_recovery, confirmation_record
from platform_backend import PlatformError

FORMAT = "pocketworld.desktop-local-recovery.v1"
PENDING = "desktop-recovery-request.json"


def _canonical(value):
    from device_protocol import canonical_bytes
    return canonical_bytes(value)


def _table(database, name):
    return database.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None


def _leases(database, local_device_id, context):
    if not _table(database, "desktop_resource_leases"):
        raise PlatformError("No original desktop resource ledger exists")
    key = "windows-session:" + str(context["sessionId"]) + ":primary"
    rows = database.execute("SELECT resource_key,owner,instance_id,lease_id,fence,expires,state FROM desktop_resource_leases WHERE resource_key=? AND state!='RELEASED'", (key,)).fetchall()
    leases = []
    for row in rows:
        worker = database.execute("SELECT local_device_id,binding_json FROM desktop_worker_instances WHERE instance_id=?", (row[2],)).fetchone() if _table(database, "desktop_worker_instances") else None
        if worker and worker[0] != local_device_id:
            raise PlatformError("Original GUI lease belongs to another native device identity", 403)
        binding = json.loads(worker[1]) if worker else {"format": "unavailable_legacy_binding"}
        leases.append(dict(zip(("resourceKey", "owner", "instanceId", "leaseId", "fence", "expiresAt", "state"), row), workerBinding=binding))
    if not leases:
        raise PlatformError("No fenced original primary-display lease needs recovery in this Windows session")
    return leases


def _stop_evidence(lease, backend, context):
    binding = lease["workerBinding"]
    if binding.get("format") != "windows_original_job_v1" or binding.get("sessionId") != context["sessionId"]:
        return {"nativeStopVerified": False, "knownActive": False, "reason": "original_native_job_or_session_binding_unavailable"}
    host = backend.original_host_stopped(binding["hostPid"], binding["hostCreated"], binding["sessionId"])
    job = backend.named_job_stopped(binding["jobName"])
    actual = host.get("currentProcessIdentity", {})
    host_active = actual.get("available") is True and actual.get("exists") is True and actual.get("created") == binding["hostCreated"] and actual.get("exited") is False
    return {"nativeStopVerified": host.get("originalHostStoppedVerified") is True and job.get("processGroupStoppedVerified") is True,
            "knownActive": host_active or job.get("activeProcesses", 0) > 0, "host": host, "job": job,
            "logonContextChanged": binding.get("logonContext") is not None and binding["logonContext"] != context}


def _uncertain_operations(database):
    values = []
    for operation, fingerprint, status, detail in database.execute("SELECT operation_id,fingerprint,last_status,details FROM operations WHERE last_status NOT IN ('completed','failed','cancelled') ORDER BY updated_at"):
        try:
            value = json.loads(detail) if detail else {}
        except ValueError:
            value = {}
        if value.get("protocol") == "desktop-v1":
            values.append({"operationId": operation, "argumentsDigest": fingerprint, "lastKnownStatus": status})
    return {"count": len(values), "shown": values[:32], "truncated": len(values) > 32,
            "allOriginalIdsDigest": hashlib.sha256(_canonical(values)).hexdigest(), "outcomesRemainUnverified": True}


def prepare_local_request(state, local_identity, backend, *, manual_after_os_restart=False):
    """Called ONLY by local CLI after its original user-session mutex is free.

    The UI itself mints a non-JSON confirmation. No --yes, environment variable,
    remote operation, command payload flag or model text substitutes for input.
    Same-user arbitrary native code is explicitly outside that assurance.
    """
    state = Path(state)
    context = backend.logon_context()
    marker = backend.read_bytes(str(state), "desktop-local-stop")
    if marker is None:
        raise PlatformError("Request local stop and exit the original bridge before recovery")
    with backend.directory(str(state)):
        database = sqlite3.connect((state / "operations.sqlite3").as_uri() + "?mode=ro", uri=True)
        try:
            leases = _leases(database, local_identity.device_id, context)
            evidence = [_stop_evidence(lease, backend, context) for lease in leases]
            uncertain = _uncertain_operations(database)
        finally:
            database.close()
    if any(item["knownActive"] for item in evidence):
        raise PlatformError("An original host/Job is STILL ACTIVE. Stop that exact original executor first; manual confirmation cannot override positive active evidence")
    native_verified = all(item["nativeStopVerified"] for item in evidence)
    if not native_verified and not manual_after_os_restart:
        raise PlatformError("Original stop evidence unavailable. No lease released. Exit old executors and sign out/restart Windows, then choose the separately labeled local manual-takeover route")
    summary = "\r\n".join("Lease=" + lease["leaseId"] + "  fence=" + str(lease["fence"]) + "\r\nresource=" + lease["resourceKey"] + "\r\noriginalInstance=" + lease["instanceId"] for lease in leases)
    summary += "\r\n\r\n原未知桌面操作共 " + str(uncertain["count"]) + " 项；保持原事实，不重放。\r\n"
    summary += "\r\n".join(item["operationId"] + "  " + item["lastKnownStatus"] for item in uncertain["shown"])
    if uncertain["truncated"]:
        summary += "\r\n仅显示前32项；其余原回执保留，未被批量判为完成/失败。"
    proof = confirm_local_recovery(summary, manual_attestation=not native_verified)
    confirmation = confirmation_record(proof)
    created = confirmation["confirmedAt"]
    body = {"format": FORMAT, "recoveryId": str(uuid.uuid4()), "localDeviceId": local_identity.device_id,
            "localInstanceId": local_identity.instance_id, "createdAt": created, "expiresAt": created + 300000,
            "logonContext": context, "leases": leases, "stopEvidenceAtConfirmation": evidence, "uncertainOperations": uncertain,
            "mode": "native_stop_verified" if native_verified else "owner_attested_after_signout_or_restart",
            "ownerConfirmation": confirmation, "priorEffectsVerified": False, "replayAuthorized": False}
    body["stopMarker"] = {"sha256": hashlib.sha256(marker).hexdigest(), "bytes": len(marker)}
    envelope = {"body": body, "signature": base64.b64encode(local_identity.sign(_canonical(body))).decode()}
    raw = _canonical(envelope)
    if len(raw) > 65536:
        raise PlatformError("Local recovery scope exceeds the fixed review bound; no request saved")
    before = backend.read_bytes(str(state), PENDING)
    if before not in (None, b""):
        raise PlatformError("A prior local recovery request is still pending; inspect it instead of replacing approval")
    receipt = backend.compare_and_swap(str(state), PENDING, before, raw)
    if not receipt.get("verified"):
        raise PlatformError("Local recovery approval persistence unconfirmed; no lease released")
    return {"recoveryId": body["recoveryId"], "status": "pending_local_startup_review", "mode": body["mode"],
            "nativeStopVerified": native_verified, "oldOperationsReplayed": False}


def apply_pending_local_request(manager, local_identity, backend):
    """Run during native startup BEFORE creating a new DesktopAdapter/driver.

    Existing local identity verifies the exact durable approval; every current
    lease/fence/native stop fact is rechecked. Only the resource lease is reset.
    Unknown original jobs and their budgets are never completed/refunded/replayed.
    """
    raw = backend.read_bytes(str(manager.state), PENDING)
    if raw in (None, b""):
        return None
    if len(raw) > 65536:
        raise PlatformError("Pending local recovery approval exceeds its fixed bound")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise PlatformError("Duplicate local recovery member")
            result[key] = value
        return result
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    if not isinstance(value, dict) or set(value) != {"body", "signature"}:
        raise PlatformError("Invalid local recovery approval")
    body = value["body"]
    required = {"format", "recoveryId", "localDeviceId", "localInstanceId", "createdAt", "expiresAt", "logonContext", "leases", "stopEvidenceAtConfirmation", "uncertainOperations", "mode", "ownerConfirmation", "priorEffectsVerified", "replayAuthorized", "stopMarker"}
    if not isinstance(body, dict) or set(body) != required or body["format"] != FORMAT or body["localDeviceId"] != local_identity.device_id or body["localInstanceId"] != local_identity.instance_id or body["priorEffectsVerified"] is not False or body["replayAuthorized"] is not False:
        raise PlatformError("Local recovery approval does not bind this native identity and no-replay rule", 403)
    from device_identity import verify_signature
    verify_signature(local_identity.certificate_der, _canonical(body), base64.b64decode(value["signature"], validate=True))
    uuid.UUID(body["recoveryId"])
    now, context = manager.now(), backend.logon_context()
    if type(body["createdAt"]) is not int or type(body["expiresAt"]) is not int or not body["createdAt"] <= now < body["expiresAt"] <= body["createdAt"] + 300000 or body["logonContext"] != context:
        raise PlatformError("Local recovery approval expired or interactive logon changed; confirm locally again")
    confirmation = body["ownerConfirmation"]
    if not isinstance(confirmation, dict) or confirmation.get("kind") != "foreground_noninjected_key_challenge" or confirmation.get("confirmedAt") != body["createdAt"] or type(confirmation.get("nonInjectedChallengeEvents")) is not int or confirmation["nonInjectedChallengeEvents"] < 8:
        raise PlatformError("Local physical-owner confirmation receipt is missing", 403)
    fingerprint = hashlib.sha256(_canonical(body)).hexdigest()
    with manager.lock:
        if manager.processes:
            raise PlatformError("New native commands are active; stop original executors before local resource recovery")
        existing = manager.ledger.execute("SELECT fingerprint,details FROM operations WHERE operation_id=?", (body["recoveryId"],)).fetchone()
        if existing:
            if existing[0] != fingerprint:
                raise PlatformError("Local recovery ID was reused for different scope", 409)
            receipt = json.loads(existing[1])["receipt"]
            for lease in body["leases"]:
                row = manager.ledger.execute("SELECT lease_id,fence,state,instance_id FROM desktop_resource_leases WHERE resource_key=?", (lease["resourceKey"],)).fetchone()
                if row != (lease["leaseId"], lease["fence"] + 1, "RELEASED", lease["instanceId"]):
                    # A consumed approval can NEVER clear a newer pause marker
                    # or release a later lease, even if its signature is valid.
                    return {**copy.deepcopy(receipt), "cleanupSkipped": "newer_resource_generation_exists"}
        else:
            current_leases = _leases(manager.ledger, local_identity.device_id, context)
            if current_leases != body["leases"]:
                raise PlatformError("Exact original lease/fence/worker changed after local confirmation; no resource released", 409)
            evidence = [_stop_evidence(lease, backend, context) for lease in current_leases]
            if any(item["knownActive"] for item in evidence):
                raise PlatformError("Original host or named Job became active; no resource released")
            native_verified = all(item["nativeStopVerified"] for item in evidence)
            if body["mode"] not in {"native_stop_verified", "owner_attested_after_signout_or_restart"} or body["mode"] == "native_stop_verified" and not native_verified:
                raise PlatformError("Original native stop proof is unavailable; local approval cannot silently downgrade to attestation")
            receipt = {"recoveryId": body["recoveryId"], "status": "resource_released", "nativeStopVerified": native_verified,
                       "ownerAttestedStopped": body["mode"] == "owner_attested_after_signout_or_restart", "stopEvidence": evidence,
                       "releasedLeaseIds": [lease["leaseId"] for lease in current_leases], "oldOperationsReplayed": False,
                       "priorEffectsVerified": False, "oldOperationStatusesChanged": False, "oldBudgetsRefunded": False,
                       "freshObservationAndLeaseRequired": True, "sameUserArbitraryCodeIsolationVerified": False}
            manager.ledger.execute("SAVEPOINT desktop_local_recovery")
            try:
                manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                       (body["recoveryId"], fingerprint, body["recoveryId"], "completed", now, json.dumps({"protocol": FORMAT, "body": body, "receipt": receipt})))
                for lease in current_leases:
                    changed = manager.ledger.execute("UPDATE desktop_resource_leases SET state='RELEASED',fence=fence+1 WHERE resource_key=? AND lease_id=? AND fence=? AND instance_id=? AND state=?",
                                                     (lease["resourceKey"], lease["leaseId"], lease["fence"], lease["instanceId"], lease["state"])).rowcount
                    if changed != 1:
                        raise PlatformError("Original resource fencing CAS failed; no recovery committed")
                manager.ledger.execute("RELEASE SAVEPOINT desktop_local_recovery")
            except BaseException:
                manager.ledger.execute("ROLLBACK TO SAVEPOINT desktop_local_recovery")
                manager.ledger.execute("RELEASE SAVEPOINT desktop_local_recovery")
                raise
            job = {"id": body["recoveryId"], "operationId": body["recoveryId"], "fingerprint": fingerprint, "worker": "desktop_recovery",
                   "protocol": FORMAT, "environmentId": "desktop-session", "status": "completed", "createdAt": now, "updatedAt": now,
                   "output": "", "outputTruncated": False, "exitCode": None, "remoteEffectsVerified": False,
                   "message": "Local owner released resource fences only; old unknown outcomes retained", "desktopRecovery": receipt}
            manager.jobs[job["id"]] = job
            manager._save(job)
    # A failed marker cleanup keeps input paused, not silently enabled. The
    # original local operation remains idempotent even if this cleanup fails.
    marker = backend.read_bytes(str(manager.state), "desktop-local-stop")
    if marker is not None:
        if not isinstance(body["stopMarker"], dict) or body["stopMarker"] != {"sha256": hashlib.sha256(marker).hexdigest(), "bytes": len(marker)}:
            raise PlatformError("A newer local pause exists; old recovery approval cannot clear it")
        result = backend.delete_local_control_file(str(manager.state), "desktop-local-stop", hashlib.sha256(marker).hexdigest(), len(marker))
        if not result.get("verified"):
            raise PlatformError("Resource recovery committed but local stop marker cleanup is unconfirmed; host stays paused")
    result = backend.compare_and_swap(str(manager.state), PENDING, raw, b"")
    if not result.get("verified"):
        raise PlatformError("Resource recovery committed; approval cleanup unconfirmed. Do not reissue old actions")
    return copy.deepcopy(receipt)
