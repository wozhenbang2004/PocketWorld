"""Read-only pages of the ORIGINAL job's persisted normalized driver observations.

No stream, second journal, provider transcript, or execution authority is created.
The retention window is the existing Progress window. Missing rows stay missing.
"""
import copy
import json
import re

PROTOCOL = "original_job_events_v1"
FILTER = "normalized_driver_observations_v1"
MAX_PAGE = 32


def capability(worker):
    return {"protocol": PROTOCOL, "supported": worker in {"codex_app_server", "claude", "pi"},
            "filter": FILTER, "maxPageEvents": MAX_PAGE, "retainedEvents": 64,
            "retainedEventBytes": 24576, "cursorPersistedByClient": True,
            "delivery": "bounded_cursor_get_not_sse", "completeProviderHistory": False,
            "childSourceProtocol": "claude_complete_child_v1" if worker == "claude" else None,
            "childSourceDelivery": "received_complete_public_blocks_only" if worker == "claude" else "unsupported",
            "childTokenStreaming": False, "childSessionControl": False}


def _integer(query, key, default, maximum, error):
    raw = query.get(key, str(default))
    if not isinstance(raw, str) or not re.fullmatch(r"0|[1-9][0-9]{0,15}", raw):
        raise error("Invalid original event cursor " + key)
    value = int(raw)
    if value > maximum:
        raise error("Original event cursor exceeds its bounded range")
    return value


def read(manager, jobid, query, error):
    if set(query) - {"generation", "after", "head", "limit", "filter", "jobOperationId"} or query.get("filter") != FILTER:
        raise error("Event reads require the exact supported observation filter")
    after = _integer(query, "after", 0, 9_007_199_254_740_991, error)
    head = _integer(query, "head", 0, 9_007_199_254_740_991, error)
    limit = _integer(query, "limit", MAX_PAGE, MAX_PAGE, error)
    generation = query.get("generation", "")
    if not limit or not isinstance(generation, str) or generation and not re.fullmatch(r"[a-f0-9-]{36}", generation) or not generation and (after or head):
        raise error("Original event generation/cursor is incomplete")
    with manager.lock:
        job = manager.jobs.get(jobid)
        if job is None:
            raise error("Original job not found", 404)
        if query.get("jobOperationId") != job["operationId"]:
            raise error("Event cursor is not bound to the original job operation", 409)
        if not capability(job.get("worker"))["supported"]:
            raise error("This original worker does not expose normalized event replay", 409)
        # Read only successfully persisted job bytes, never a driver tail which has not committed.
        try:
            stored = json.loads((manager.state / ("job-" + jobid + ".json")).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise error("Original persisted observations are unavailable; no cursor was acknowledged", 409)
        if stored.get("id") != jobid or stored.get("operationId") != job["operationId"]:
            raise error("Original persisted job identity changed", 409)
        progress = stored.get("progress")
        base = {"protocol": PROTOCOL, "filter": FILTER, "id": jobid, "operationId": job["operationId"],
                "environmentId": stored["environmentId"], "worker": stored["worker"],
                "nativeProtocol": stored.get("protocol", ""),
                **{key: stored[key] for key in ("threadId", "sessionId", "promptRequestId") if key in stored},
                "requestedGeneration": generation, "requestedAfter": after, "requestedHead": head,
                "events": [], "hasMore": False, "gap": None, "liveStreaming": False,
                "completeHistory": False, "executionAuthorized": False,
                "observation": "durably_saved_normalized_driver_events_not_provider_transcript"}
        if not progress:
            return {**base, "status": "not_available", "generation": "", "headSequence": 0,
                    "nextSequence": 0, "lastSequence": 0, "retainedFromSequence": 0}
        current = progress["generation"]
        last = progress["lastSequence"]
        rows = progress["events"]
        first = rows[0]["sequence"] if rows else last + 1
        if not re.fullmatch(r"[a-f0-9-]{36}", current) or len(rows) > 64 or len(json.dumps(rows, ensure_ascii=False).encode("utf-8")) > 24576:
            raise error("Original persisted event window is unsupported", 409)
        if generation and generation != current:
            return {**base, "status": "generation_changed", "generation": current,
                    "headSequence": last, "lastSequence": last, "nextSequence": 0,
                    "retainedFromSequence": first,
                    "gap": {"reason": "generation_changed", "previousGeneration": generation}}
        if after > last or head and (head > last or head < after):
            raise error("Original event cursor is ahead of this persisted job; not reset or recreated", 409)
        ceiling = head or last
        start = after
        gap = None
        if after < first - 1:
            missing_end = min(first - 1, ceiling)
            if missing_end > after:
                gap = {"reason": "retention_gap", "fromSequence": after + 1, "throughSequence": missing_end}
                start = missing_end
        selected = [copy.deepcopy(row) for row in rows if start < row["sequence"] <= ceiling][:limit]
        next_sequence = selected[-1]["sequence"] if selected else start
        return {**base, "status": "page", "generation": current, "headSequence": ceiling,
                "lastSequence": last, "nextSequence": next_sequence, "retainedFromSequence": first,
                "events": selected, "hasMore": next_sequence < ceiling, "gap": gap}
