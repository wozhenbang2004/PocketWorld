"""Prove a completed Codex final answer already present in the original job's output.

No extra output bytes, endpoint, provider call, or conversation ID. Old protocol items
without a final phase, uncertain turns and archived jobs never have a proof.
"""
import hashlib
import re

FIELD = "_privateHostFinalProof"
FORMAT = "pocketworld.codex-final-in-output.v1"
MAX_BYTES = 12_000
_ID = re.compile(r"[A-Za-z0-9_.:-]{1,200}\Z")
_SECRET = re.compile(
    r"(?im)-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----|\bBearer\s+[A-Za-z0-9._~+/-]{16,}|"
    r"(?:sk-(?:proj-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|"
    r"xox[baprs]-[A-Za-z0-9-]{16,})|"
    r"(?:api[_ -]?key|access[_ -]?token|client[_ -]?secret|password)\s*[:=]\s*[\x22\x27]?[A-Za-z0-9._~+/-]{12,}"
)


def _bytes(value):
    if not isinstance(value, str):
        return None
    try:
        return value.encode("utf-8", errors="strict")
    except UnicodeError:
        return None


def _id(value):
    return isinstance(value, str) and _ID.fullmatch(value) is not None


def _safe_text(value):
    raw = _bytes(value)
    return (raw if raw and len(raw) <= MAX_BYTES and value.strip() and not _SECRET.search(value)
            and not any(ord(c) < 32 and c not in "\r\n\t" or 127 <= ord(c) <= 159 for c in value)
            else None)


class Capture:
    def __init__(self):
        self.item = None
        self.success = False
        self.invalid = False

    def observe(self, method, params, thread, turn):
        if not (_id(thread) and _id(turn) and params.get("threadId") == thread):
            return
        if method == "item/completed" and params.get("turnId") == turn:
            item = params.get("item")
            if not isinstance(item, dict) or item.get("type") != "agentMessage" or item.get("phase") != "final_answer":
                return
            if self.item is not None or not _id(item.get("id")) or not _safe_text(item.get("text")):
                self.invalid = True
                return
            self.item = (thread, turn, item["id"], item["text"])
        elif method == "turn/completed":
            actual = params.get("turn")
            if isinstance(actual, dict) and actual.get("id") == turn:
                self.success = actual.get("status") == "completed"

    def proof(self, job, request, before_redaction, after_redaction):
        # Scoped to a fresh private read-only Linux host job. No continuation, paired
        # device, team, import, selected private input or another execution profile.
        if (not self.success or self.invalid or self.item is None
                or job.get("worker") != "codex_app_server" or job.get("readOnly") is not True
                or not isinstance(job.get("hostWorkspaceAnchor"), str)
                or request.get("readOnly") is not True
                or any(k in request for k in ("threadId", "resumeJobId", "forkSource", "inputFiles", "inputContinuationProtocol",
                                               "projectHistory", "assignmentWorkspace", "teamContext", "inferenceAudience",
                                               "harnessConfigOperationId", "ccSwitchImport"))
                or any(job.get(k) is not None for k in ("inputState", "assignmentWorkspace", "teamContext", "nativeDeviceOperation"))
                or after_redaction.get("status") != "completed"
                or any(after_redaction.get(k) is not True for k in ("readOnlyConfirmed", "externalToolsDisabled", "protocolVerified", "turnStartAcknowledged"))
                or after_redaction.get("outputTruncated") is not False
                or before_redaction.get("output") != after_redaction.get("output")):
            return None
        thread, turn, item, text = self.item
        if (thread, turn) != (after_redaction.get("threadId"), after_redaction.get("turnId")):
            return None
        raw, final = _bytes(after_redaction.get("output")), _safe_text(text)
        if raw is None or final is None or len(raw) > 786_432:
            return None
        offset = raw.find(final)
        if offset < 0 or raw.find(final, offset + 1) >= 0:
            return None  # No new bytes; no ambiguous commentary match or truncation.
        return {"format": FORMAT, "threadId": thread, "turnId": turn, "itemId": item, "startByte": offset,
                "bytes": len(final), "sha256": hashlib.sha256(final).hexdigest()}


def original_operation_proof(job, response):
    proof = job.get(FIELD)
    if (not isinstance(proof, dict) or proof.get("format") != FORMAT
            or job.get("status") != "completed" or response.get("status") != "completed"
            or response.get("protocol") != "codex_app_server" or response.get("readOnly") is not True
            or response.get("outputTruncated") is not False
            or not all(response.get(k) == proof.get(k) and _id(proof.get(k)) for k in ("threadId", "turnId"))
            or not _id(proof.get("itemId"))):
        return None
    body, start, count = _bytes(response.get("output")), proof.get("startByte"), proof.get("bytes")
    if (body is None or len(body) > 786_432 or type(start) is not int or type(count) is not int
            or start < 0 or not 1 <= count <= MAX_BYTES or start + count > len(body)):
        return None
    actual = body[start:start + count]
    try:
        text = actual.decode("utf-8", errors="strict")
    except UnicodeError:
        return None
    if _safe_text(text) != actual or hashlib.sha256(actual).hexdigest() != proof.get("sha256"):
        return None
    return {k: proof[k] for k in ("format", "threadId", "turnId", "itemId", "startByte", "bytes", "sha256")}
