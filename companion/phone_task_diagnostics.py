"""Explicit, bounded diagnostics projected from the original paired TaskStore reads.

No raw event body, model text, goal, tool arguments, stdout, URL, header, file or
image is copied. This is a disclosure format, never a task/state authority.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat

import device_protocol as wire
from phone_task_client import ClientError

MAX_PAGES = 8
MAX_EXPORT_BYTES = 512_000
SETTLED = {"succeeded", "failed", "cancelled", "interrupted"}
STATES = wire.TASK_STATES | {"unknown", "not_found", "rejected", "prepared", "not_dispatched", "reported", "completed", "observed"}
EVENT_KINDS = {"tool", "model", "error", "status", "state", "run", "task_event", "model_attempt_interrupted",
               "native_completion_fact", "native_phone_route_source", "native_phone_route_candidate", "native_phone_route_withdrawn"}
TOOL_FAMILIES = {"phone", "browser", "memory", "history", "project", "local", "remote", "skill", "skills", "plugin", "team", "document"}
ERROR_CATEGORIES = {"approval_required", "grant_denied", "permission_denied", "invalid_request", "invalid_state", "timeout",
                    "cancelled", "unavailable", "source_changed", "source_unavailable", "not_dispatched", "unknown",
                    "budget_exhausted", "resource_busy", "needs_reconcile", "stale_revision", "schema_invalid"}
EXCLUDED = ["goals_and_chat_text", "model_requests_and_responses", "raw_event_bodies", "tool_arguments_and_text_results",
            "stdout_stderr", "urls_and_headers", "screenshots_and_images", "file_contents_and_paths", "credentials_and_grants"]


def _integer(value, low=0):
    return type(value) is int and low <= value <= wire.MAX_SAFE_INTEGER


def _identifier(value):
    return isinstance(value, str) and wire.ID.fullmatch(value) is not None


def _enum(value, choices):
    return isinstance(value, str) and value in choices


def _cursor(value):
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{1,16}", value) is None or int(value) > wire.MAX_SAFE_INTEGER:
        raise ClientError("Original event cursor cannot be verified")
    return int(value)


def _identity(snapshot, peer):
    if (not isinstance(snapshot, dict) or snapshot.get("executorDeviceId") != peer
            or snapshot.get("protocolVersion") != wire.VERSION or snapshot.get("protocolProfile") != wire.PROFILE
            or snapshot.get("messageType") != "TaskSnapshot"
            or not all(_identifier(snapshot.get(key)) for key in ("instanceId", "taskId", "runId"))
            or not _integer(snapshot.get("executorEpoch"), 1) or not _integer(snapshot.get("revision"), 1)
            or not _enum(snapshot.get("state"), wire.TASK_STATES)):
        raise ClientError("Original task/run/epoch snapshot cannot be verified")
    return {key: snapshot[key] for key in ("executorDeviceId", "instanceId", "taskId", "runId", "executorEpoch")}


def _event(event, identity):
    if (not isinstance(event, dict) or event.get("protocolVersion") != wire.VERSION or event.get("protocolProfile") != wire.PROFILE
            or event.get("messageType") != "TaskEvent" or not _integer(event.get("sequence"), 1)
            or not _integer(event.get("createdAt"), 1)
            or any(event.get(key) != identity[key] for key in ("executorDeviceId", "instanceId", "taskId"))
            or _cursor(event.get("cursor")) != event["sequence"] or not isinstance(event.get("data"), dict)):
        raise ClientError("An original event identity or cursor cannot be verified")
    data = event["data"]
    if data.get("runId") is not None and data["runId"] != identity["runId"]:
        return None
    epoch = data.get("coordinatorEpoch", data.get("executorEpoch"))
    if epoch is not None and (not _integer(epoch, 1) or epoch != identity["executorEpoch"]):
        return None
    row = {key: event[key] for key in ("sequence", "cursor", "createdAt")}
    row["kind"] = event.get("kind") if _enum(event.get("kind"), EVENT_KINDS) else "other"
    row["runAttribution"] = "exact" if data.get("runId") == identity["runId"] and epoch == identity["executorEpoch"] else "not_recorded"
    if row["runAttribution"] != "exact":
        return row  # Do not assign an old task-wide result to the selected run.
    tool = data.get("tool")
    if isinstance(tool, str):
        prefix = tool.split("_", 1)[0]
        row["toolFamily"] = prefix if prefix in TOOL_FAMILIES else "other"
    # Only literal flags/enumerated categories from the original event/receipt.
    # No generic recursive sanitizer can safely turn arbitrary text into shareable text.
    for target, source in (("eventResult", data), ("receipt", data.get("receipt"))):
        if not isinstance(source, dict):
            continue
        selected = {}
        for key in ("ok", "accepted", "requiresReconciliation", "executionStarted", "processExited", "resourcesReleased", "businessVerified"):
            if type(source.get(key)) is bool:
                selected[key] = source[key]
        for key in ("status", "effectState"):
            if _enum(source.get(key), STATES):
                selected[key] = source[key]
        if _enum(source.get("code"), ERROR_CATEGORIES):
            selected["errorCategory"] = source["code"]
        elif "code" in source:
            selected["errorCategory"] = "other_or_unavailable"
        if type(source.get("exitCode")) is int and -255 <= source["exitCode"] <= 255:
            selected["exitCode"] = source["exitCode"]
        if selected:
            row[target] = selected
    return row


def _error_outcome(failure):
    return "not_found" if failure.status == 404 else "not_authorized" if failure.status in (401, 403) else "source_unavailable"


def _redact(value, redactor):
    if isinstance(value, str):
        return redactor(value)
    if isinstance(value, list):
        return [_redact(item, redactor) for item in value]
    if isinstance(value, dict):
        return {key: _redact(item, redactor) for key, item in value.items()}
    return value


def collect(client, peer, operation_id):
    """At most ten original reads (two snapshots + eight event pages), zero writes/retries."""
    if not isinstance(peer, str) or wire.DEVICE_ID.fullmatch(peer) is None or not _identifier(operation_id):
        raise ClientError("Select the exact paired phone and original operation identifier")
    result = {"format": "pocketworld.phone-task-diagnostic", "schemaVersion": 1,
              "source": {"kind": "original_taskstore_via_paired_phone", "protocolVersion": wire.VERSION, "protocolProfile": wire.PROFILE},
              "operationId": operation_id, "outcome": "source_unavailable", "partial": True,
              "events": [], "gaps": [], "excluded": EXCLUDED.copy(), "rawTextIncluded": False,
              "redactionPolicy": "strict_field_allowlist_v1", "businessOutcomeAssessed": False, "automaticRetry": False, "uploaded": False}
    args = {"peerDeviceId": peer, "operationId": operation_id}
    gaps = result["gaps"]
    redactor = getattr(client, "redact_diagnostic_text", lambda text: text)
    try:
        first = client.execute("status", args)
    except ClientError as failure:
        result["outcome"] = _error_outcome(failure); gaps.append(result["outcome"])
        return _redact(result, redactor)
    if not isinstance(first, dict) or first.get("operationId") != operation_id:
        raise ClientError("Original operation identity changed; no diagnostic was produced")
    if first.get("currentRead") == "unavailable" or first.get("phoneStateUnconfirmed") is True:
        gaps.append("current_source_unavailable")
        return _redact(result, redactor)
    if first.get("status") == "not_found":
        result["outcome"] = "not_found"; gaps.append("not_found")
        return _redact(result, redactor)
    task = first.get("task")
    if not isinstance(task, dict) or not task.get("runId"):
        result["outcome"] = "identity_unconfirmed"; gaps.append("original_task_run_identity_unconfirmed")
        return _redact(result, redactor)
    identity = _identity(task, peer)
    horizon = _cursor(task.get("eventCursor"))
    result.update(identity=identity, outcome="captured", startState=task["state"], endState="unavailable",
                  startRevision=task["revision"], capturedThroughCursor=str(horizon))
    for source, target in (("status", "operationState"), ("effectState", "operationEffectState")):
        if _enum(first.get(source), STATES): result[target] = first[source]
    if task["state"] not in SETTLED:
        gaps.append("task_not_settled")
    cursor = 0
    other_runs = 0
    try:
        for _ in range(MAX_PAGES):
            if cursor >= horizon:
                break
            page = client.execute("events", {**args, "cursor": str(cursor), "limit": 32})
            if (not isinstance(page, dict) or not isinstance(page.get("events"), list) or len(page["events"]) > 32
                    or type(page.get("hasMore")) is not bool or type(page.get("snapshotRequired")) is not bool):
                raise ClientError("Original event page cannot be verified")
            next_cursor = _cursor(page.get("nextCursor"))
            if page["snapshotRequired"]:
                gaps.append("event_snapshot_required"); break
            if next_cursor < cursor or page["hasMore"] and next_cursor == cursor:
                raise ClientError("Original event cursor did not advance")
            sequence = cursor
            for event in page["events"]:
                projected = _event(event, identity)
                if event["sequence"] <= sequence or event["sequence"] > next_cursor:
                    raise ClientError("Original event ordering cannot be verified")
                if event["sequence"] > sequence + 1:
                    if "event_sequence_gap" not in gaps: gaps.append("event_sequence_gap")
                sequence = event["sequence"]
                if sequence > horizon:
                    continue
                if projected is None:
                    other_runs += 1
                else:
                    result["events"].append(projected)
            if next_cursor > sequence:
                gaps.append("event_cursor_without_records")
            cursor = next_cursor
            if not page["hasMore"]:
                if cursor < horizon: gaps.append("event_range_unavailable")
                break
        else:
            if cursor < horizon: gaps.append("event_page_limit")
    except ClientError as failure:
        gaps.append("events_" + _error_outcome(failure))
    result["otherRunEventsOmitted"] = other_runs
    try:
        last = client.execute("status", args)
        if not isinstance(last, dict) or last.get("operationId") != operation_id:
            raise ClientError("Original operation identity changed")
        if last.get("phoneStateUnconfirmed") or last.get("currentRead") == "unavailable":
            gaps.append("final_source_unavailable")
        elif _identity(last.get("task"), peer) != identity:
            gaps.append("original_run_changed_during_capture")
        else:
            end = last["task"]
            if end["revision"] < task["revision"]:
                raise ClientError("Original snapshot revision regressed")
            result.update(endState=end["state"], endRevision=end["revision"])
            if _cursor(end.get("eventCursor")) > horizon: gaps.append("events_added_after_capture_boundary")
            if end["state"] not in SETTLED and "task_not_settled" not in gaps: gaps.append("task_not_settled")
    except ClientError as failure:
        gaps.append("final_" + _error_outcome(failure))
    if any(row["runAttribution"] != "exact" for row in result["events"]):
        gaps.append("some_event_run_attribution_not_recorded")
    result["partial"] = bool(gaps)
    return _redact(result, redactor)


def preview_bytes(report):
    raw = (json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")
    if len(raw) > MAX_EXPORT_BYTES:
        raise ClientError("Diagnostic preview exceeds the disclosure bound; no partial file was exported")
    return raw


def save_reviewed(raw, destination, confirmed_sha256):
    """Only the exact preview bytes, to a new explicit local file; never overwrite/upload."""
    if not isinstance(raw, bytes) or len(raw) > MAX_EXPORT_BYTES or hashlib.sha256(raw).hexdigest() != confirmed_sha256:
        raise ClientError("Diagnostic preview confirmation changed; nothing was exported")
    path = Path(destination).expanduser().absolute()
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    owned = os.fstat(descriptor)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(raw); output.flush(); os.fsync(output.fileno())
    except BaseException:
        try:
            current = path.lstat()
            if stat.S_ISREG(current.st_mode) and (current.st_dev, current.st_ino) == (owned.st_dev, owned.st_ino): path.unlink()
        except OSError:
            pass
        raise
    return str(path)
