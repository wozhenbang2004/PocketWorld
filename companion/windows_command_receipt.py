"""One read-only projection of a directly received, allowlisted Windows command.

A local Windows job is not a receiver TaskStore run or evidence that a repo
changed. No source directory, program, phone, shell or job is opened/started.
"""
import unicodedata

import device_protocol as protocol
from platform_backend import PlatformError
from windows_received_job import COMMAND, retained

PROFILE = "windows-local-command-receipt-v1"


def _output(text):
    # Same tail that fits the existing operation.get frame, or less. Never
    # recover missing bytes, reopen a log or render control markup.
    raw = text.encode("utf-8")
    value = raw[-1024:].decode("utf-8", "ignore").replace("\r\n", "\n").replace("\r", "\n")
    return "".join(c if c in "\n\t" or not unicodedata.category(c).startswith("C") else "�" for c in value), len(raw) > 1024


def project(manager, operation, row, original):
    """Under original manager.lock. No cache-only output or live command probes."""
    source, accepted = original
    fingerprint, job_id, status, _ = row
    value = {"profile": PROFILE, "operationId": operation, "argumentsDigest": fingerprint, "jobId": job_id,
             "environmentId": accepted["resourceBinding"]["environmentId"], "phase": "accepted_only",
             "updatedAt": accepted["acceptedAt"], "businessVerified": False}
    job = manager.jobs.get(job_id)
    if not job: return value
    try:
        fixed = retained(manager, operation, row, original)
        if not fixed or source["toolName"] != COMMAND or any(fixed.get(k) != job.get(k) for k in
                ("processStarted", "notStartedConfirmed", "dispatchFenceFinal", "processCleanupProof", "exitCode")):
            return value
        when, started, code = fixed["updatedAt"], fixed.get("processStarted"), fixed.get("exitCode")
        if type(when) is not int or when <= 0: return value
        clean = fixed.get("dispatchFenceFinal") is True and (fixed.get("processCleanupProof") or {}).get("processGroupStoppedVerified") is True
        phase = "unknown"
        if status == "queued" and started is not True: phase = "queued"
        elif status == "running" and started is True: phase = "running"
        elif clean and started is False and fixed.get("notStartedConfirmed") is True and not fixed.get("processStartFacts"): phase = "not_started"
        elif clean and started is True and status == "cancelled": phase = "stopped"
        elif clean and started is True and type(code) is int:
            if status == "completed" and code == 0: phase = "exited_zero"
            elif status == "failed" and code != 0: phase = "exited_nonzero"
        evidence = {**value, "phase": phase, "updatedAt": when, "revision": fixed["nativeDeviceRevision"],
                    "cleanupVerified": clean}
        if phase in {"exited_zero", "exited_nonzero", "stopped"}:
            if type(code) is int: evidence["exitCode"] = code
            if (fixed.get("outputLosslesslyDecoded") is True and isinstance(fixed.get("output"), str)
                    and fixed["output"] == job.get("output") and type(fixed.get("outputTruncated")) is bool):
                tail, clipped = _output(fixed["output"])
                evidence.update(output=tail, outputPartial=clipped or fixed["outputTruncated"])
        return evidence
    except (KeyError, OSError, AttributeError, TypeError, ValueError, RecursionError, PlatformError, protocol.ProtocolError):
        return value  # Only the original acceptance was verified; no output/exit proof.
