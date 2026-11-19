"""Local owner pause evidence. Never a remote method, lease or recovery proof."""
import hashlib
import json
import time
import uuid

from platform_backend import PlatformError
from windows_installation import _object

MARKER = "desktop-local-stop"
RECEIPT = "desktop-local-stop-receipt.json"
MAXIMUM = 2048
_ACK = "pocketworld.desktop-local-stop-receipt.v1"


def _uuid(value):
    try: return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError: return False


def _date(value):
    return type(value) is int and 0 < value < 4102444800000  # before 2100; bounded native time


def _request(raw):
    if not isinstance(raw, bytes): raise ValueError("Original local record unavailable")
    value = _object(raw)
    if value.get("format") != "pocketworld.desktop-local-stop.v1" or not _uuid(value.get("requestId")) or not _date(value.get("requestedAt")):
        raise ValueError("The local pause marker has no recognizable original request")
    return value["requestId"], hashlib.sha256(raw).hexdigest()


def _receipt(raw):
    if not isinstance(raw, bytes): raise ValueError("Original local record unavailable")
    value = _object(raw)
    if (set(value) != {"format", "requestId", "markerSha256", "managerInstanceId", "observedAt", "driverStoppedVerified"}
            or value["format"] != _ACK or not _uuid(value["requestId"]) or not _uuid(value["managerInstanceId"])
            or not isinstance(value["markerSha256"], str) or len(value["markerSha256"]) != 64
            or any(c not in "0123456789abcdef" for c in value["markerSha256"])
            or not _date(value["observedAt"]) or type(value["driverStoppedVerified"]) is not bool):
        raise ValueError("Not an original desktop pause receipt")
    return value


def _read(backend, root, sid, name):
    return backend.read_optional_private_bytes(root, name, sid, maximum=MAXIMUM)


def new_request():
    return json.dumps({"format": "pocketworld.desktop-local-stop.v1", "requestId": str(uuid.uuid4()),
                       "requestedAt": int(time.time() * 1000), "message": "Local owner paused input; do not auto-resume"}).encode()


def save_installed_request(context, current):
    # The registered directory is private. Publish the complete marker at once;
    # never truncate an old marker (recovery checks its exact bytes).
    context.backend.assert_private_directory(context.jobs, context.sid, require_protected=False)
    if _read(context.backend, context.jobs, context.sid, MARKER) is not None:
        return  # Already requested, even if older marker bytes are not recognizable.
    context.backend.create_private_snapshot(context.jobs, MARKER, new_request(), MAXIMUM, current=current)


def owner_status(context):
    backend, root, sid = context.backend, context.jobs, context.sid
    try:
        backend.assert_private_directory(root, sid, require_protected=False)
    except (OSError, PlatformError):
        return ("unreadable",)
    try:
        raw = _read(backend, root, sid, MARKER)
    except (OSError, PlatformError):
        return ("unreadable",)
    if raw is None: return ("none",)
    try:
        request, digest = _request(raw)
    except (ValueError, PlatformError, RecursionError):
        return ("unidentified",)
    try:
        receipt = _receipt(_read(backend, root, sid, RECEIPT))
        if (receipt["requestId"], receipt["markerSha256"]) == (request, digest) and _read(backend, root, sid, MARKER) == raw:
            return ("acknowledged", receipt["observedAt"], receipt["driverStoppedVerified"])
    except (OSError, PlatformError, ValueError, RecursionError):
        pass  # Missing, changed, old-format or unreadable is not proof of processing.
    return ("requested",)


def witness(manager):
    """Best effort only. The watchdog still stops even with a broken marker."""
    backend, root = manager.platform, str(manager.state)
    if backend.name != "windows": return None
    sid = backend.logon_context()["userSid"]
    backend.assert_private_directory(root, sid, require_protected=False)
    raw = _read(backend, root, sid, MARKER)
    request, digest = _request(raw)
    if not _uuid(manager.instance_id): return None
    return backend, root, sid, request, digest, manager.instance_id


def acknowledge(manager, original, outcome):
    backend, root, sid, request, digest, instance = original
    def current():
        if manager.instance_id != instance or _request(_read(backend, root, sid, MARKER)) != (request, digest):
            raise PlatformError("Local pause identity changed; no receipt publication", 409)
    if outcome.get("paused") is not True or outcome.get("priorEffectsVerified") is not False: return
    current()
    before = _read(backend, root, sid, RECEIPT)
    if before is not None:
        old = _receipt(before)  # Do not replace unrelated/unrecognized private contents.
        if (old["requestId"], old["markerSha256"]) == (request, digest): return
        if not backend.discard_previous_stop_receipt(root, before, current).get("verified"): return
    proof = outcome.get("driverCleanupProof")
    raw = json.dumps({"format": _ACK, "requestId": request, "markerSha256": digest, "managerInstanceId": instance,
                      "observedAt": int(time.time()*1000),
                      "driverStoppedVerified": isinstance(proof, dict) and proof.get("processGroupStoppedVerified") is True}).encode()
    backend.create_private_snapshot(root, RECEIPT, raw, MAXIMUM, current=current)
