"""Original directly received workspace READ receipt; never a fresh folder/file reader."""
import hashlib
import json
import unicodedata

import device_protocol as protocol
from platform_backend import PlatformError
from platform_windows import relative_parts
from windows_received_job import READS, retained

PROFILE = "windows-workspace-read-receipt-v1"


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def bind_original(manager, envelope, operation, job, result):
    """Called only by the actual reader at completion, inside the original save transaction.
    Pin digests of the exact requested relative path and already-bounded wire result.
    No path/content duplication and no backfill from a later directory or cache read.
    """
    tool = envelope.arguments["toolName"]
    if (manager.platform.name != "windows" or tool not in READS or envelope.arguments["effect"] != "read"
            or envelope.get("callerDeviceId") != envelope.get("originDeviceId") or envelope.get("parentTaskId") != envelope.get("rootTaskId")):
        return
    path = envelope.arguments["arguments"].get("path", "")
    if not isinstance(path, str) or result.get("path") != path: return
    raw = protocol.canonical_bytes(result)
    if len(raw) > 24576: return
    witness = {"format": PROFILE, "pathDigest": _sha(path.encode()), "resultDigest": _sha(raw)}
    manager.ledger.execute("""UPDATE operations SET details=json_set(details,'$.windowsReadWitness',json(?))
        WHERE operation_id=? AND job_id=? AND fingerprint=? AND json_extract(details,'$.protocol')='native-device-tool-v1'
        AND json_extract(details,'$.audience.toolName')=? AND json_extract(details,'$.historyAudience.argumentsDigest')=fingerprint
        AND json_type(details,'$.windowsReadWitness') IS NULL""",
        (json.dumps(witness), operation, job["id"], job["fingerprint"], tool))


def _plain(text, *, lines=False):
    if lines: text = text.replace("\r\n", "\n").replace("\r", "\n")
    return "".join(ch if (lines and ch in "\n\t") or not unicodedata.category(ch).startswith("C") else "�" for ch in text)


def project(manager, operation, row, original):
    source, accepted = original
    tool = source["toolName"]
    value = {"profile": PROFILE, "tool": tool, "operationId": operation, "jobId": row[1], "argumentsDigest": row[0],
             "environmentId": accepted["resourceBinding"]["environmentId"], "phase": "receipt_only",
             "updatedAt": accepted["acceptedAt"], "readAgain": False}
    if tool not in READS: return value
    try:
        fixed = retained(manager, operation, row, original)
        if not fixed or fixed["status"] != "completed": return value
        result = fixed["nativeToolResult"]
        if not isinstance(result, dict) or not protocol.resource_equal(result, manager.jobs[row[1]].get("nativeToolResult")):
            return value
        witness = json.loads(row[3]).get("windowsReadWitness")
        path = result["path"]
        if not isinstance(path, str) or not (path or tool == "workspace.list"): return value
        relative_parts(path, empty=tool == "workspace.list")
        if witness != {"format": PROFILE, "resultDigest": _sha(protocol.canonical_bytes(result)), "pathDigest": _sha(path.encode())}:
            return value
        evidence = {**value, "path": _plain(path), "pathDigest": witness["pathDigest"], "revision": fixed["nativeDeviceRevision"], "updatedAt": fixed["updatedAt"]}
        if tool == "workspace.read":
            text, version, size = result["content"], result["version"], result["bytes"]
            raw = text.encode("utf-8")
            if type(size) is not int or size != len(raw) or version != "sha256:" + _sha(raw): return value
            return {**evidence, "phase": "text_snapshot", "bytes": size, "version": version,
                    "part": _plain(raw[:1024].decode("utf-8", "ignore"), lines=True), "partial": len(raw) > 1024}
        entries, cut = result["files"], result["truncated"]
        if not isinstance(entries, list) or len(entries) > 200 or type(cut) is not bool: return value
        names = []
        for entry in entries[:8]:
            name, kind = entry["name"], entry["kind"]
            if (not isinstance(name, str) or not 0 < len(name) <= 255 or "/" in name or "\\" in name
                    or kind not in {"directory", "file", "reparse_point", "unsupported"} or entry["path"] != (path+"/" if path else "")+name):
                return value
            names.append({"name": _plain(name), "kind": kind})
        return {**evidence, "phase": "directory_snapshot", "returned": len(entries), "sourceTruncated": cut, "entries": names}
    except (ValueError, KeyError, TypeError, AttributeError, RecursionError, PlatformError):
        return value
