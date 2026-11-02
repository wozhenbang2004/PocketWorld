"""Fixed device artifact bytes, bounded cache and atomic private landing.

This is not a path reader, a task store or an execution grant. Native callers retain the
original artifact/project package authority and run the current pairing/approval gate via
`current` before publication and on EVERY chunk. Descriptors are provenance, not authority.
Existing ProjectPackageArchive/owner import remains the sole project admission path.
"""
from __future__ import annotations
from dataclasses import dataclass
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import threading
import time
import uuid

CHUNK_BYTES = 262_144
MAX_BYTES = 20 * 1024 * 1024
MAX_CACHE_BYTES = 40 * 1024 * 1024
MAX_ENTRIES = 4
BASE_KEYS = frozenset({"transferId", "sourceDeviceId", "artifactId", "versionId", "taskId", "operationId", "sha256", "bytes", "kind", "mimeType", "name"})
RUN_KEYS = frozenset({"runId", "executorEpoch", "requestRunId", "requestExecutorEpoch"})
SCREEN_KEYS = frozenset({"observationRef", "resourceBinding", "expiresAt"})


class ArtifactError(Exception):
    pass


def _uuid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ArtifactError("Canonical artifact UUID required")
    return value


def _sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value):
        raise ArtifactError("Fixed artifact SHA-256 required")
    return value


def canonical(value):
    # Same integer/string-only fixed metadata shape; business envelopes use DeviceProtocol.
    from device_protocol import canonical_bytes
    return canonical_bytes(value)


def reference(value):
    if not isinstance(value, dict):
        raise ArtifactError("Fixed artifact reference required")
    expected = BASE_KEYS | (SCREEN_KEYS if value.get("kind") == "screenshot" else frozenset())
    if not expected <= set(value) or set(value) - expected - RUN_KEYS:
        raise ArtifactError("Artifact reference has unknown or missing fields")
    for name in ("transferId",):
        _uuid(value[name])
    from device_protocol import identifier
    for name in ("artifactId", "taskId", "operationId"):
        identifier(value[name], name)
    for name in ("runId", "requestRunId"):
        if name in value: identifier(value[name], name)
    from device_protocol import integer
    for name in ("executorEpoch", "requestExecutorEpoch"):
        if name in value: integer(value[name], name, 1)
    if ("executorEpoch" in value and "runId" not in value) or (("requestRunId" in value) != ("requestExecutorEpoch" in value)):
        raise ArtifactError("Real target/source request run facts must retain their role and paired epoch")
    if not isinstance(value["sourceDeviceId"], str) or not re.fullmatch(r"pw1_[a-f0-9]{64}", value["sourceDeviceId"]):
        raise ArtifactError("Artifact source device invalid")
    if not isinstance(value["versionId"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value["versionId"]):
        raise ArtifactError("Artifact version invalid")
    _sha(value["sha256"])
    if type(value["bytes"]) is not int or not 0 <= value["bytes"] <= MAX_BYTES:
        raise ArtifactError("Artifact bytes outside bound")
    if value["kind"] not in {"file", "project_bundle", "screenshot"}:
        raise ArtifactError("Unsupported artifact kind")
    name, mime = value["name"], value["mimeType"]
    if not isinstance(name, str) or not 0 < len(name) <= 160 or name in {".", ".."} or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF or c in "/\\:" for c in name):
        raise ArtifactError("Artifact name is display-only and cannot be a path")
    if not isinstance(mime, str) or len(mime) > 200 or not re.fullmatch(r"[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+", mime):
        raise ArtifactError("Artifact MIME invalid")
    if value["kind"] == "screenshot":
        if type(value["expiresAt"]) is not int or value["expiresAt"] > time.time_ns() // 1_000_000 + 600_000 or not isinstance(value["observationRef"], dict) or not isinstance(value["resourceBinding"], dict):
            raise ArtifactError("Screenshot requires fixed observation/resource/expiry provenance")
        captured = value["observationRef"].get("capturedAt")
        if type(captured) is not int or not 0 < value["expiresAt"] - captured <= 600_000:
            raise ArtifactError("Screenshot expiry is bound to its original observation time")
        if len(canonical({key: value[key] for key in SCREEN_KEYS})) > 8192:
            raise ArtifactError("Screenshot provenance exceeds bound")
    return json.loads(canonical(value))


def to_wire_descriptor(fixed_reference, *, instance_id, revision, native_run_id=None, executor_epoch=None, request_run_id=None, request_executor_epoch=None):
    """Map native cache facts to the ONE shared DeviceProtocol ArtifactDescriptor."""
    from device_protocol import validate_message, VERSION, PROFILE
    descriptor = reference(fixed_reference)
    value = {"protocolVersion": VERSION, "protocolProfile": PROFILE, "messageType": "ArtifactDescriptor", "artifactId": descriptor["artifactId"],
             "taskId": descriptor["taskId"], "executorDeviceId": descriptor["sourceDeviceId"], "instanceId": instance_id,
             "contentDigest": descriptor["sha256"], "size": descriptor["bytes"], "mediaType": descriptor["mimeType"],
             "name": descriptor["name"], "revision": revision, "transferId": descriptor["transferId"],
             "versionId": descriptor["versionId"], "kind": descriptor["kind"], "operationId": descriptor["operationId"]}
    # Reference.runId, if present, is a REAL target native run, never source requestRun.
    native_run_id = native_run_id if native_run_id is not None else descriptor.get("runId")
    executor_epoch = executor_epoch if executor_epoch is not None else descriptor.get("executorEpoch")
    request_run_id = request_run_id if request_run_id is not None else descriptor.get("requestRunId")
    request_executor_epoch = request_executor_epoch if request_executor_epoch is not None else descriptor.get("requestExecutorEpoch")
    if (native_run_id is None) != (executor_epoch is None) or (request_run_id is None) != (request_executor_epoch is None):
        raise ArtifactError("Do not invent a target run/epoch from source request correlation")
    for field, actual in (("runId", native_run_id), ("executorEpoch", executor_epoch), ("requestRunId", request_run_id), ("requestExecutorEpoch", request_executor_epoch)):
        if field in descriptor and actual is not None and descriptor[field] != actual:
            raise ArtifactError("Wire mapping changed fixed native run-role facts")
    if native_run_id is not None: value.update(runId=native_run_id, executorEpoch=executor_epoch)
    if request_run_id is not None: value.update(requestRunId=request_run_id, requestExecutorEpoch=request_executor_epoch)
    for key in SCREEN_KEYS:
        if key in descriptor:
            value[key] = descriptor[key]
    return validate_message(value)


def from_wire_descriptor(wire_descriptor):
    """Content/provenance mapping only; caller must independently authorize the current peer."""
    from device_protocol import validate_message
    wire = validate_message(wire_descriptor)
    if wire["messageType"] != "ArtifactDescriptor" or not {"transferId", "versionId", "kind", "operationId"} <= set(wire):
        raise ArtifactError("Device byte transfer requires complete fixed artifact provenance")
    value = {"transferId": wire["transferId"], "sourceDeviceId": wire["executorDeviceId"], "artifactId": wire["artifactId"],
             "versionId": wire["versionId"], "taskId": wire["taskId"], "operationId": wire["operationId"],
             "sha256": wire["contentDigest"], "bytes": wire["size"], "kind": wire["kind"], "mimeType": wire["mediaType"], "name": wire["name"]}
    for key in SCREEN_KEYS | RUN_KEYS:
        if key in wire:
            value[key] = wire[key]
    return reference(value)


def _live(value):
    if value["kind"] == "screenshot" and time.time_ns() // 1_000_000 >= value["expiresAt"]:
        raise ArtifactError("Original observed image expired; observe again")


@dataclass(frozen=True)
class Chunk:
    transfer_id: str
    offset: int
    data: bytes
    sha256: str


class ArtifactTransferStore:
    """Immutable bounded endpoint cache. Snapshot bytes never reach control JSON or relay.

    Screen captures are temporary in-memory (maximum four / 40 MiB), so Windows screenshot
    publication needs no unreviewed Path.open fallback. Native file sources must first use
    the platform's approved handle reader, then pass that STREAM to register_stream.
    """
    def __init__(self, *, source_device_id, current):
        if not re.fullmatch(r"pw1_[a-f0-9]{64}", source_device_id):
            raise ArtifactError("Native local device identity required")
        self.source_device_id, self.current = source_device_id, current
        self._entries = {}
        self._artifact_ids = {}
        self._publishing = {}
        self._pending_transfers = set()
        self._wire = {}
        self._pins = {}
        self._reservations = 0
        self._reserved = 0
        self._lock = threading.RLock()

    def _drop_owned(self, transfer):
        descriptor, raw = self._entries.pop(transfer)
        self._artifact_ids.pop(descriptor["artifactId"], None); self._wire.pop(transfer, None)
        self._reserved -= len(raw)

    def _reserve(self, size):
        with self._lock:
            now = time.time_ns() // 1_000_000
            for key, (descriptor, _) in tuple(self._entries.items()):
                if descriptor["kind"] == "screenshot" and descriptor["expiresAt"] <= now and not self._pins.get(key):
                    self._drop_owned(key)
            # Ephemeral screenshots are a bounded rolling observation cache, not permanent
            # results. Never evict a file/project or a native-pinned active delivery.
            while len(self._entries) + self._reservations >= MAX_ENTRIES or self._reserved + size > MAX_CACHE_BYTES:
                candidates = [key for key, value in self._entries.items()
                              if value[0]["kind"] == "screenshot" and not self._pins.get(key)]
                if not candidates: raise ArtifactError("Artifact cache full; remove an owned file or finish active delivery")
                self._drop_owned(candidates[0])  # Original publication order, deterministic even for same capturedAt.
            self._reserved += size; self._reservations += 1

    @contextmanager
    def pin(self, fixed_reference):
        descriptor = self.describe(fixed_reference); transfer = descriptor["transferId"]
        with self._lock:
            if transfer not in self._entries: raise ArtifactError("Original screenshot cache version unavailable")
            self._pins[transfer] = self._pins.get(transfer, 0) + 1
        try: yield descriptor
        finally:
            with self._lock:
                count = self._pins.get(transfer, 0) - 1
                if count > 0: self._pins[transfer] = count
                else: self._pins.pop(transfer, None)

    def register_snapshot(self, raw, metadata, expires_at_ms):
        """Native capture_store callback; exact task/run/operation and observed frame required."""
        required = {"taskId", "operationId", "observationRef", "resourceBinding"}
        if not isinstance(metadata, dict) or not required <= set(metadata) or set(metadata) - required - RUN_KEYS or not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BYTES:
            raise ArtifactError("Exact native screenshot bytes and provenance required")
        now = time.time_ns() // 1_000_000
        if type(expires_at_ms) is not int or not now < expires_at_ms <= now + 600_000:
            raise ArtifactError("Observed images have at most a ten-minute retention lease")
        sha = hashlib.sha256(raw).hexdigest()
        descriptor = {"transferId": str(uuid.uuid4()), "sourceDeviceId": self.source_device_id,
                      "artifactId": str(uuid.uuid4()), "versionId": sha, "sha256": sha, "bytes": len(raw),
                      "kind": "screenshot", "mimeType": "image/png", "name": "observed-screen.png", **metadata, "expiresAt": expires_at_ms}
        return self.register_stream(io.BytesIO(raw), descriptor)

    def register_stream(self, native_stream, fixed_reference):
        descriptor = reference(fixed_reference); _live(descriptor)
        if descriptor["sourceDeviceId"] != self.source_device_id:
            raise ArtifactError("Native source device differs from fixed artifact")
        self.current(descriptor, "publish")
        artifact, transfer = descriptor["artifactId"], descriptor["transferId"]
        with self._lock:
            previous_id = self._artifact_ids.get(artifact)
            if previous_id is not None:
                previous = self._entries[previous_id][0]
                if canonical(previous) != canonical(descriptor):
                    raise ArtifactError("Artifact id already binds immutable content/provenance; publish a new artifact id")
                return json.loads(canonical(previous))
            if artifact in self._publishing or transfer in self._pending_transfers:
                raise ArtifactError("Native publication in progress; query the original artifact")
            if transfer in self._entries:
                raise ArtifactError("Transfer id already belongs to a different artifact")
            self._reserve(descriptor["bytes"])
            self._publishing[artifact] = descriptor
            self._pending_transfers.add(transfer)
        try:
            chunks, total = [], 0
            sha = hashlib.sha256()
            while total < descriptor["bytes"]:
                self.current(descriptor, "publish")
                raw = native_stream.read(min(CHUNK_BYTES, descriptor["bytes"] - total))
                if not isinstance(raw, bytes) or not raw or len(raw) > min(CHUNK_BYTES, descriptor["bytes"] - total):
                    raise ArtifactError("Original native artifact stream ended/changed")
                chunks.append(raw); sha.update(raw); total += len(raw)
            if native_stream.read(1) != b"" or sha.hexdigest() != descriptor["sha256"]:
                raise ArtifactError("Native artifact no longer matches selected bytes/hash")
            self.current(descriptor, "publish"); _live(descriptor)
            frozen = b"".join(chunks)
            with self._lock:
                if descriptor["transferId"] in self._entries:
                    raise ArtifactError("Transfer already exists; query the original reference")
                self._entries[transfer] = (descriptor, frozen)
                self._artifact_ids[artifact] = transfer
                self._publishing.pop(artifact); self._pending_transfers.remove(transfer)
                self._reservations -= 1
            return json.loads(canonical(descriptor))
        except BaseException:
            with self._lock:
                self._reserved -= descriptor["bytes"]
                self._reservations -= 1
                self._publishing.pop(artifact, None); self._pending_transfers.discard(transfer)
            raise

    def by_artifact_id(self, artifact_id):
        with self._lock:
            transfer = self._artifact_ids.get(artifact_id)
            if transfer is None: raise ArtifactError("Original fixed artifact is unavailable")
            return json.loads(canonical(self._entries[transfer][0]))

    def describe(self, fixed_reference):
        descriptor = reference(fixed_reference)
        with self._lock:
            item = self._entries.get(descriptor["transferId"])
            if item is None or canonical(item[0]) != canonical(descriptor):
                raise ArtifactError("Original artifact/version unavailable")
        self.current(descriptor, "read"); _live(descriptor)
        return json.loads(canonical(descriptor))

    def wire_descriptor(self, fixed_reference, *, instance_id, revision, native_run_id=None, executor_epoch=None, request_run_id=None, request_executor_epoch=None):
        descriptor = self.describe(fixed_reference)
        wire = to_wire_descriptor(descriptor, instance_id=instance_id, revision=revision, native_run_id=native_run_id, executor_epoch=executor_epoch, request_run_id=request_run_id, request_executor_epoch=request_executor_epoch)
        with self._lock:
            previous = self._wire.get(descriptor["transferId"])
            if previous is not None and canonical(previous) != canonical(wire):
                raise ArtifactError("Native wire descriptor facts are already frozen for this content version")
            self._wire[descriptor["transferId"]] = wire
        return json.loads(canonical(wire))

    def read_chunk(self, fixed_reference, offset, length=CHUNK_BYTES):
        descriptor = self.describe(fixed_reference)
        if type(offset) is not int or not 0 <= offset <= descriptor["bytes"] or type(length) is not int or not 1 <= length <= CHUNK_BYTES:
            raise ArtifactError("Chunk cursor outside fixed content")
        with self._lock:
            raw = self._entries[descriptor["transferId"]][1][offset:offset + length]
        self.current(descriptor, "read"); _live(descriptor)
        return Chunk(descriptor["transferId"], offset, raw, hashlib.sha256(raw).hexdigest())

    def remove(self, fixed_reference):
        descriptor = self.describe(fixed_reference)
        self.current(descriptor, "remove")
        with self._lock:
            if self._pins.get(descriptor["transferId"]): raise ArtifactError("Original delivery is pinned")
            self._drop_owned(descriptor["transferId"])


class ArtifactReceiver:
    """POSIX private transfer staging. Windows landing must use the native safe-handle backend.

    The cache tracks only byte offsets/hashes, not remote jobs or execution rights. Resume
    discards a prefix written before its durable checkpoint, never mixes file versions.
    """
    def __init__(self, private_root, *, current):
        if os.name == "nt" or not hasattr(os, "O_NOFOLLOW"):
            raise ArtifactError("Use the Windows native private snapshot landing backend")
        self.root = os.path.abspath(private_root)
        if os.path.realpath(self.root) != self.root or not os.path.isdir(self.root) or os.stat(self.root).st_uid != os.getuid() or stat.S_IMODE(os.stat(self.root).st_mode) & 0o077:
            raise ArtifactError("Native private artifact directory required")
        self.current = current
        self._lock = threading.RLock()
        self._root = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        self._identity = self._dir_identity()
        self._progress = {}  # Bounded by original cache entries; native SHA state, never persisted crypto state.

    def _dir_identity(self):
        info = os.fstat(self._root)
        return info.st_dev, info.st_ino

    def _check_root(self):
        info = os.stat(self.root, follow_symlinks=False)
        if not stat.S_ISDIR(info.st_mode) or os.path.realpath(self.root) != self.root or (info.st_dev, info.st_ino) != self._identity or self._dir_identity() != self._identity:
            raise ArtifactError("Native artifact directory changed")

    def _open(self, name, flags):
        self._check_root()
        fd = os.open(name, flags | os.O_NOFOLLOW, 0o600, dir_fd=self._root)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            os.close(fd); raise ArtifactError("Artifact cache refuses links/special files")
        return fd

    def _exists(self, name):
        try:
            os.stat(name, dir_fd=self._root, follow_symlinks=False); return True
        except FileNotFoundError:
            return False

    def _recover_publication(self, descriptor):
        transfer = descriptor["transferId"]
        if not self._exists(transfer + ".part") or not self._exists(transfer + ".artifact"):
            return
        # Recover ONLY the exact link+unlink crash window for these two owned names.
        descriptors = []
        try:
            for suffix in (".part", ".artifact"):
                descriptors.append(os.open(transfer + suffix, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self._root))
            a, b = (os.fstat(fd) for fd in descriptors)
            if not stat.S_ISREG(a.st_mode) or not stat.S_ISREG(b.st_mode) or a.st_nlink != 2 or b.st_nlink != 2 or (a.st_dev, a.st_ino) != (b.st_dev, b.st_ino):
                raise ArtifactError("Artifact publication collision; no file overwritten")
            sha, size = self._hash(descriptors[0], descriptor)
            if (sha, size) != (descriptor["sha256"], descriptor["bytes"]):
                raise ArtifactError("Interrupted publication content differs")
            self.current(descriptor, "receive"); self._check_root()
            os.unlink(transfer + ".part", dir_fd=self._root); os.fsync(self._root)
        finally:
            for fd in descriptors:
                os.close(fd)

    def _state(self, descriptor):
        fd = self._open(descriptor["transferId"] + ".json", os.O_RDONLY)
        with os.fdopen(fd, "rb") as stream:
            raw = stream.read(16_385)
        if len(raw) > 16_384:
            raise ArtifactError("Artifact checkpoint exceeds bound")
        value = json.loads(raw)
        if canonical(value["reference"]) != canonical(descriptor):
            raise ArtifactError("Transfer already bound to different content/provenance")
        return value

    def _save(self, descriptor, offset, prefix, *, phase="receiving", ownership_nonce=None):
        name = "checkpoint-" + str(uuid.uuid4()) + ".pending"
        target = descriptor["transferId"] + ".json"
        if ownership_nonce is None:
            ownership_nonce = self._state(descriptor).get("ownershipNonce", "")
        fd = self._open(name, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        try:
            with os.fdopen(fd, "wb") as output:
                output.write(canonical({"reference": descriptor, "verifiedOffset": offset, "prefixSha256": prefix, "phase": phase, "ownershipNonce": ownership_nonce})); output.flush(); os.fsync(output.fileno())
            self._check_root(); os.replace(name, target, src_dir_fd=self._root, dst_dir_fd=self._root); os.fsync(self._root)
        finally:
            if self._exists(name):
                os.unlink(name, dir_fd=self._root)

    def _scan(self, fd, descriptor):
        sha, total = hashlib.sha256(), 0
        os.lseek(fd, 0, os.SEEK_SET)
        while True:
            self.current(descriptor, "receive"); _live(descriptor)
            raw = os.read(fd, CHUNK_BYTES)
            if not raw:
                return sha, total
            total += len(raw)
            if total > MAX_BYTES:
                raise ArtifactError("Artifact cache exceeds bound")
            sha.update(raw)

    def _hash(self, fd, descriptor):
        sha, total = self._scan(fd, descriptor)
        return sha.hexdigest(), total

    def begin(self, fixed_reference):
        descriptor = reference(fixed_reference)
        self.current(descriptor, "receive"); _live(descriptor)
        with self._lock:
            transfer = descriptor["transferId"]
            if self._exists(transfer + ".json"):
                state = self._state(descriptor)
                if state.get("phase") == "initializing":
                    if state["verifiedOffset"] != 0 or state["prefixSha256"] != hashlib.sha256(b"").hexdigest():
                        raise ArtifactError("Invalid owned initialization checkpoint")
                    _uuid(state.get("ownershipNonce"))
                    if not self._exists(transfer + ".part"):
                        fd = self._open(transfer + ".part", os.O_CREAT | os.O_EXCL | os.O_WRONLY); os.fsync(fd); os.close(fd); os.fsync(self._root)
                    fd = self._open(transfer + ".part", os.O_RDONLY)
                    try:
                        if os.fstat(fd).st_size != 0: raise ArtifactError("Owned initial part is not empty")
                    finally: os.close(fd)
                    self._save(descriptor, 0, hashlib.sha256(b"").hexdigest())
                self._recover_publication(descriptor)
                complete = self._exists(transfer + ".artifact")
                fd = self._open(transfer + (".artifact" if complete else ".part"), os.O_RDONLY if complete else os.O_RDWR)
                try:
                    if not complete:
                        if os.fstat(fd).st_size < state["verifiedOffset"]:
                            raise ArtifactError("Original partial artifact truncated")
                        os.ftruncate(fd, state["verifiedOffset"]); os.fsync(fd)
                    sha, size = self._scan(fd, descriptor)
                    info = os.fstat(fd)
                finally:
                    os.close(fd)
                if size != (descriptor["bytes"] if complete else state["verifiedOffset"]) or sha.hexdigest() != (descriptor["sha256"] if complete else state["prefixSha256"]):
                    raise ArtifactError("Original partial artifact changed")
                if not complete:
                    self._progress[transfer] = (size, sha, (info.st_dev, info.st_ino), canonical(descriptor))
                return size
            self._check_root()
            names = [name for name in os.listdir(self._root) if re.fullmatch(r"[a-f0-9-]{36}\.json", name)]
            reserved = descriptor["bytes"]
            if len(names) >= MAX_ENTRIES:
                raise ArtifactError("Artifact cache full")
            for name in names:
                fd = self._open(name, os.O_RDONLY)
                with os.fdopen(fd, "rb") as stream:
                    raw = stream.read(16_385)
                if len(raw) > 16_384:
                    raise ArtifactError("Artifact checkpoint exceeds bound")
                reserved += json.loads(raw)["reference"]["bytes"]
            if reserved > MAX_CACHE_BYTES:
                raise ArtifactError("Artifact cache reservation exceeded")
            if self._exists(transfer + ".part") or self._exists(transfer + ".artifact"):
                raise ArtifactError("Unclaimed cache collision; no foreign object removed")
            # Exact owned initialization is durable before .part can exist. A crash on either
            # side is recoverable under the original transfer, without deleting unclaimed names.
            self._save(descriptor, 0, hashlib.sha256(b"").hexdigest(), phase="initializing", ownership_nonce=str(uuid.uuid4()))
            fd = self._open(transfer + ".part", os.O_CREAT | os.O_EXCL | os.O_WRONLY); os.fsync(fd); os.close(fd); os.fsync(self._root)
            self._save(descriptor, 0, hashlib.sha256(b"").hexdigest())
            fd = self._open(transfer + ".part", os.O_RDONLY)
            try: info = os.fstat(fd)
            finally: os.close(fd)
            self._progress[transfer] = (0, hashlib.sha256(), (info.st_dev, info.st_ino), canonical(descriptor))
            return 0

    def accept(self, fixed_reference, offset, raw, chunk_sha256):
        descriptor = reference(fixed_reference)
        self.current(descriptor, "receive"); _live(descriptor)
        if not isinstance(raw, bytes) or len(raw) > CHUNK_BYTES or hashlib.sha256(raw).hexdigest() != _sha(chunk_sha256):
            raise ArtifactError("Artifact chunk checksum mismatch")
        with self._lock:
            state = self._state(descriptor)
            if type(offset) is not int or offset != state["verifiedOffset"] or offset + len(raw) > descriptor["bytes"] or not raw and offset != descriptor["bytes"]:
                raise ArtifactError("Chunk does not extend the original fixed version/cursor")
            transfer = descriptor["transferId"]
            progress = self._progress.get(transfer)
            if progress is None or progress[0] != offset or progress[3] != canonical(descriptor):
                self.begin(descriptor)  # One full prefix verification per resume, not per chunk.
                progress = self._progress.get(transfer)
            fd = self._open(transfer + ".part", os.O_RDWR)
            try:
                info = os.fstat(fd)
                if progress is None or info.st_size != offset or progress[2] != (info.st_dev, info.st_ino):
                    raise ArtifactError("Owned artifact part/cursor changed; resume original transfer")
                os.lseek(fd, offset, os.SEEK_SET)
                view = memoryview(raw)
                while view:
                    n = os.write(fd, view)
                    if n <= 0: raise ArtifactError("Artifact write made no progress")
                    view = view[n:]
                os.fsync(fd)
                sha = progress[1].copy(); sha.update(raw); size = offset + len(raw)
                self.current(descriptor, "receive"); self._save(descriptor, size, sha.hexdigest())
                self._progress[transfer] = (size, sha, progress[2], progress[3])
                # Checkpoints describe the authenticated byte stream. The final full DISK
                # hash remains mandatory; mutable prefix corruption never becomes delivery.
                return size
            except BaseException:
                self._progress.pop(transfer, None); raise
            finally:
                os.close(fd)

    def finish(self, fixed_reference):
        descriptor = reference(fixed_reference)
        self.current(descriptor, "receive"); _live(descriptor)
        with self._lock:
            state = self._state(descriptor); transfer = descriptor["transferId"]
            self._recover_publication(descriptor)
            complete = self._exists(transfer + ".artifact")
            if state["verifiedOffset"] != descriptor["bytes"]:
                raise ArtifactError("Partial bytes are not a delivered artifact")
            fd = self._open(transfer + (".artifact" if complete else ".part"), os.O_RDONLY)
            try:
                sha, size = self._hash(fd, descriptor)
            finally:
                os.close(fd)
            if size != descriptor["bytes"] or sha != descriptor["sha256"]:
                raise ArtifactError("Full artifact content hash differs")
            self.current(descriptor, "receive"); self._check_root()
            if not complete:
                # Unique transfer names; unlike replace(), link fails on an existing destination.
                os.link(transfer + ".part", transfer + ".artifact", src_dir_fd=self._root, dst_dir_fd=self._root, follow_symlinks=False)
                os.unlink(transfer + ".part", dir_fd=self._root); os.fsync(self._root)
            # Actually reopen and verify published data before returning a delivery fact.
            fd = self._open(transfer + ".artifact", os.O_RDONLY)
            try:
                published_sha, published_size = self._hash(fd, descriptor)
            finally:
                os.close(fd)
            if (published_sha, published_size) != (sha, size):
                raise ArtifactError("Published artifact changed")
            return {"reference": descriptor, "contentVerified": True, "savedBytes": size, "sha256": sha,
                    "readable": True, "atomicPublication": True, "businessVerified": False,
                    "executionAuthorityRestored": False, "taskOrGrantRestored": False}

    def open_verified(self, fixed_reference):
        descriptor = reference(fixed_reference); self.finish(descriptor)
        fd = self._open(descriptor["transferId"] + ".artifact", os.O_RDONLY)
        return _CurrentReader(os.fdopen(fd, "rb"), descriptor, self.current)  # Native only; no path exposed in JSON.

    def remove(self, fixed_reference):
        descriptor = reference(fixed_reference)
        self.current(descriptor, "remove")
        with self._lock:
            self._state(descriptor); self._recover_publication(descriptor)
            self._progress.pop(descriptor["transferId"], None)
            for suffix in (".part", ".artifact", ".json"):
                name = descriptor["transferId"] + suffix
                if self._exists(name):
                    fd = self._open(name, os.O_RDONLY); os.close(fd)
                    os.unlink(name, dir_fd=self._root)
            os.fsync(self._root)

    def close(self):
        os.close(self._root)


class _CurrentReader:
    def __init__(self, stream, descriptor, current):
        self._stream, self._descriptor, self._current = stream, descriptor, current

    def read(self, size=CHUNK_BYTES):
        self._current(self._descriptor, "receive"); _live(self._descriptor)
        if type(size) is not int or size < 0 or size > CHUNK_BYTES:
            raise ArtifactError("Native artifact reads remain chunk-bounded")
        raw = self._stream.read(size)
        self._current(self._descriptor, "receive"); _live(self._descriptor)
        return raw

    def close(self):
        self._stream.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class ArtifactSession:
    """Binds a bulk peer to a native-approved fixed control manifest, never a JSON actor."""
    def __init__(self, receiver):
        self.receiver, self._incoming = receiver, {}
        self._lock = threading.RLock()

    def prepare_incoming(self, control_peer, fixed_reference, current):
        from device_identity import AuthenticatedPeer
        if not isinstance(control_peer, AuthenticatedPeer):
            raise ArtifactError("Authenticated control TLS peer required")
        descriptor = reference(fixed_reference); current()
        if descriptor["sourceDeviceId"] != control_peer.device_id:
            raise ArtifactError("Artifact executor differs from the authenticated manifest peer")
        transfer = descriptor["transferId"]
        with self._lock:
            prior = self._incoming.get(transfer)
            peer = (control_peer.device_id, control_peer.certificate_fingerprint)
            if prior is None and len(self._incoming) >= MAX_ENTRIES:
                raise ArtifactError("Too many approved artifact manifests")
            if prior is not None and (canonical(prior[0]) != canonical(descriptor) or prior[1] != peer):
                raise ArtifactError("Transfer already bound to another manifest/peer")
            offset = self.receiver.begin(descriptor)
            self._incoming[transfer] = (descriptor, peer, current)
            return offset

    def _require(self, peer, transfer_id):
        from device_identity import AuthenticatedPeer
        transfer_id = str(transfer_id)
        item = self._incoming.get(transfer_id)
        if not isinstance(peer, AuthenticatedPeer) or item is None or item[1] != (peer.device_id, peer.certificate_fingerprint):
            raise ArtifactError("Bulk bytes lack the exact current control-channel manifest")
        item[2]()  # Fresh native grant/epoch/receive approval check on each chunk.
        return item[0]

    def accept(self, peer, transfer_id, offset, checksum_verified_frame_bytes):
        with self._lock:
            descriptor = self._require(peer, transfer_id)
            return self.receiver.accept(descriptor, offset, checksum_verified_frame_bytes, hashlib.sha256(checksum_verified_frame_bytes).hexdigest())

    def finish(self, peer, transfer_id):
        with self._lock:
            return self.receiver.finish(self._require(peer, transfer_id))

    def disconnected(self, peer):
        with self._lock:
            self._incoming = {key: value for key, value in self._incoming.items() if value[1] != (peer.device_id, peer.certificate_fingerprint)}


class ArtifactEndpoint:
    """Artifact methods behind the ONE validated permission / DeviceResponse router.

    `access(permission, descriptor)` verifies original task/audience/accurate export approval.
    `bulk_sender(permission, exact, original_read, UUID, offset, raw)` must retain the
    original native scope/SQL/expiry check for the ACTUAL TLS-write gate, not only enqueue.
    Neither descriptors nor route tokens can replace these native callbacks.
    """
    def __init__(self, store, *, native_instance_id, revision, native_run_id=None, executor_epoch=None, access, bulk_sender, history_access=None, history_bulk_sender=None, history_audience=None):
        from device_protocol import identifier, integer
        identifier(native_instance_id, "instanceId"); integer(revision, "revision", 1)
        if native_run_id is not None: identifier(native_run_id, "runId")
        if executor_epoch is not None: integer(executor_epoch, "executorEpoch", 1)
        if native_run_id is not None and executor_epoch is None: raise ArtifactError("Native target run requires its real epoch")
        self.native_run_id = native_run_id
        self.store, self.instance_id = store, native_instance_id
        self.revision, self.executor_epoch = revision, executor_epoch
        self.access, self.bulk_sender = access, bulk_sender
        self.history_access, self.history_bulk_sender, self.history_audience = history_access, history_bulk_sender, history_audience

    def accepted_history_audience(self, query):
        # Native fixed catalog -> original accepted-object SQL. No file IO or execution
        # Permission is consulted before the independent typed history admission exists.
        if query.method not in {"artifact.describe", "artifact.readChunk"} or self.history_audience is None:
            raise ArtifactError("Native artifact history audience adapter unavailable")
        exact = self.store.by_artifact_id(query.arguments["artifactId"])
        return self.history_audience(query, exact)

    async def receive(self, permission):
        from device_pairing import Permission
        if not isinstance(permission, Permission):
            raise ArtifactError("Native certificate-bound execution permission required")
        return await self._receive_read(permission, self.access, self.bulk_sender)

    async def receive_history(self, permission):
        from device_pairing import HistoryPermission
        if not isinstance(permission, HistoryPermission) or self.history_access is None or self.history_bulk_sender is None:
            raise ArtifactError("Independent native fixed-result history adapter required")
        return await self._receive_read(permission, self.history_access, self.history_bulk_sender)

    async def _receive_read(self, permission, access, bulk_sender):
        now = time.time_ns() // 1_000_000
        permission.check_current(now)
        envelope = permission.envelope
        request = envelope.json()
        method, arguments = request["method"], request["arguments"]
        if method not in {"artifact.describe", "artifact.readChunk"}:
            raise ArtifactError("Not an artifact method")
        # Resolve only a registered native content object, never a message-supplied path.
        entry = self.store.by_artifact_id(arguments["artifactId"])
        if entry is None:
            raise ArtifactError("Original fixed artifact unavailable")
        descriptor = self.store.describe(entry)
        access(permission, descriptor)
        def original_read():
            permission.check_current(time.time_ns() // 1_000_000); access(permission, descriptor); _live(descriptor)
        if method == "artifact.describe":
            with self.store._lock:
                existing_wire = self.store._wire.get(descriptor["transferId"])
            return json.loads(canonical(existing_wire)) if existing_wire is not None else self.store.wire_descriptor(descriptor, instance_id=self.instance_id, revision=self.revision, native_run_id=self.native_run_id, executor_epoch=self.executor_epoch)
        if arguments["contentDigest"] != descriptor["sha256"]:
            raise ArtifactError("Chunk request changed the fixed content version")
        if arguments["offset"] >= descriptor["bytes"]:
            raise ArtifactError("No bytes at this fixed content cursor")
        with self.store.pin(descriptor):
            chunk = self.store.read_chunk(descriptor, arguments["offset"], arguments["length"])
            permission.check_current(time.time_ns() // 1_000_000); access(permission, descriptor)
            queued = await bulk_sender(permission, descriptor, original_read, uuid.UUID(descriptor["transferId"]), chunk.offset, chunk.data)
            if not queued:
                raise ArtifactError("Bulk channel backpressure; no chunk claimed delivered")
        return {"artifactId": descriptor["artifactId"], "transferId": descriptor["transferId"], "contentDigest": descriptor["sha256"],
                "offset": chunk.offset, "length": len(chunk.data), "status": "ready"}


class WindowsArtifactReceiver:
    """Durable Windows landing through native safe handles ONLY (no pathlib/open fallback).

    The index records byte checkpoints, not tasks, grants, keys or run handles. Chunk objects
    are absent-only immutable snapshots. A crash after a native write is resolved by reading
    the SAME name/hash, not by overwriting it. Installer must provision the native cache ACL.
    """
    INDEX = "device-transfer-index-v1.json"
    MAX_INDEX = 262_144
    MAX_PARTS = (MAX_BYTES + CHUNK_BYTES - 1) // CHUNK_BYTES

    def __init__(self, native_private_root, *, backend, current):
        if getattr(backend, "name", None) != "windows" or not all(callable(getattr(backend, method, None)) for method in
                ("root_binding", "read_bytes", "open_reader", "create_private_snapshot", "compare_and_swap", "delete_private_snapshot")):
            raise ArtifactError("Windows native safe-handle artifact backend required")
        self.root, self.backend, self.current = native_private_root, backend, current
        self.binding = backend.root_binding(native_private_root)
        self._lock = threading.RLock()

    def _root_current(self):
        if self.backend.root_binding(self.root) != self.binding:
            raise ArtifactError("Original native artifact cache root was replaced")

    def _load(self):
        self._root_current()
        raw = self.backend.read_bytes(self.root, self.INDEX, self.MAX_INDEX)
        if raw is None:
            return {"format": "pocketworld.artifact-byte-cache.v1", "transfers": {}}, None
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result: raise ArtifactError("Duplicate native artifact checkpoint field")
                result[key] = value
            return result
        index = json.loads(raw, object_pairs_hook=unique)
        if set(index) != {"format", "transfers"} or index["format"] != "pocketworld.artifact-byte-cache.v1" or not isinstance(index["transfers"], dict) or len(index["transfers"]) > MAX_ENTRIES:
            raise ArtifactError("Native artifact checkpoint index invalid")
        total = 0
        for transfer, value in index["transfers"].items():
            _uuid(transfer)
            if not isinstance(value, dict) or set(value) != {"reference", "verifiedOffset", "chunks", "complete"}:
                raise ArtifactError("Native artifact checkpoint invalid")
            descriptor = reference(value["reference"])
            if descriptor["transferId"] != transfer or type(value["verifiedOffset"]) is not int or not 0 <= value["verifiedOffset"] <= descriptor["bytes"] or type(value["complete"]) is not bool:
                raise ArtifactError("Native artifact checkpoint identity invalid")
            if not isinstance(value["chunks"], list) or len(value["chunks"]) > self.MAX_PARTS:
                raise ArtifactError("Native artifact chunk index exceeds bound")
            offset = 0
            for chunk in value["chunks"]:
                if not isinstance(chunk, dict) or set(chunk) != {"offset", "bytes", "sha256"} or chunk["offset"] != offset or type(chunk["bytes"]) is not int or chunk["bytes"] != min(CHUNK_BYTES, descriptor["bytes"] - offset):
                    raise ArtifactError("Native artifact chunk checkpoint invalid")
                _sha(chunk["sha256"]); offset += chunk["bytes"]
            if offset != value["verifiedOffset"] or value["complete"] and offset != descriptor["bytes"]:
                raise ArtifactError("Native artifact byte cursor invalid")
            total += descriptor["bytes"]
        if total > MAX_CACHE_BYTES:
            raise ArtifactError("Native artifact cache byte reservation exceeded")
        return index, raw

    def _save(self, index, original_bytes, descriptor, direction="receive"):
        # Native cache serialization, not a second network envelope/authority.
        raw = json.dumps(index, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        if len(raw) > self.MAX_INDEX:
            raise ArtifactError("Native artifact index exceeds bound")
        self.current(descriptor, direction); self._root_current()
        self.backend.compare_and_swap(self.root, self.INDEX, original_bytes, raw, current=lambda: self.current(descriptor, direction))
        # Unknown CAS is resolved by exact native bytes. Never retry the replacement blindly.
        self._root_current()
        if self.backend.read_bytes(self.root, self.INDEX, self.MAX_INDEX) != raw:
            raise ArtifactError("Native artifact checkpoint update not confirmed; query the original transfer")
        self.current(descriptor, direction)

    @staticmethod
    def _chunk_name(transfer, offset):
        return _uuid(transfer) + ".chunk-" + str(offset)

    def _read_fixed(self, name, size, sha256, descriptor, *, allow_absent=False):
        self.current(descriptor, "receive"); _live(descriptor); self._root_current()
        # Native read_bytes enforces file/parent handles, non-reparse, no hardlinks and the bound.
        raw = self.backend.read_bytes(self.root, name, MAX_BYTES)
        if raw is None and allow_absent:
            return None
        if not isinstance(raw, bytes) or len(raw) != size or hashlib.sha256(raw).hexdigest() != sha256:
            raise ArtifactError("Native cached artifact bytes/hash differ")
        self.current(descriptor, "receive"); return raw

    def _entry(self, index, descriptor):
        item = index["transfers"].get(descriptor["transferId"])
        if item is None or canonical(item["reference"]) != canonical(descriptor):
            raise ArtifactError("Transfer not registered for this exact content/provenance")
        return item

    def begin(self, fixed_reference):
        descriptor = reference(fixed_reference); self.current(descriptor, "receive"); _live(descriptor)
        with self._lock:
            index, old = self._load(); transfer = descriptor["transferId"]
            if transfer not in index["transfers"]:
                if len(index["transfers"]) >= MAX_ENTRIES or sum(item["reference"]["bytes"] for item in index["transfers"].values()) + descriptor["bytes"] > MAX_CACHE_BYTES:
                    raise ArtifactError("Native artifact cache full")
                index["transfers"][transfer] = {"reference": descriptor, "verifiedOffset": 0, "chunks": [], "complete": False}
                self._save(index, old, descriptor)
                return 0
            item = self._entry(index, descriptor)
            for chunk in item["chunks"]:
                self._read_fixed(self._chunk_name(transfer, chunk["offset"]), chunk["bytes"], chunk["sha256"], descriptor)
            if item["complete"]:
                self._read_fixed(transfer + ".artifact", descriptor["bytes"], descriptor["sha256"], descriptor)
            return item["verifiedOffset"]

    def accept(self, fixed_reference, offset, raw, chunk_sha256):
        descriptor = reference(fixed_reference); self.current(descriptor, "receive"); _live(descriptor)
        if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != _sha(chunk_sha256) or type(offset) is not int:
            raise ArtifactError("Artifact chunk checksum invalid")
        with self._lock:
            index, old = self._load(); item = self._entry(index, descriptor)
            if item["complete"] or offset != item["verifiedOffset"] or len(item["chunks"]) >= self.MAX_PARTS or len(raw) != min(CHUNK_BYTES, descriptor["bytes"] - offset) or not raw:
                raise ArtifactError("Windows chunks must extend the same fixed 256KiB cursor")
            name = self._chunk_name(descriptor["transferId"], offset)
            existing = self._read_fixed(name, len(raw), chunk_sha256, descriptor, allow_absent=True)
            if existing is None:
                self.current(descriptor, "receive")
                self.backend.create_private_snapshot(self.root, name, raw, maximum=CHUNK_BYTES)
                self._read_fixed(name, len(raw), chunk_sha256, descriptor)
            # If a preceding native write happened before its index ACK, reading the exact same
            # object establishes byte completion. No new name, overwrite or business side effect.
            item["chunks"].append({"offset": offset, "bytes": len(raw), "sha256": chunk_sha256})
            item["verifiedOffset"] += len(raw); self._save(index, old, descriptor)
            return item["verifiedOffset"]

    def finish(self, fixed_reference):
        descriptor = reference(fixed_reference); self.current(descriptor, "receive"); _live(descriptor)
        with self._lock:
            index, old = self._load(); item = self._entry(index, descriptor)
            if item["verifiedOffset"] != descriptor["bytes"]:
                raise ArtifactError("Partial bytes are not a delivered artifact")
            pieces = [self._read_fixed(self._chunk_name(descriptor["transferId"], chunk["offset"]), chunk["bytes"], chunk["sha256"], descriptor) for chunk in item["chunks"]]
            raw = b"".join(pieces)
            if len(raw) != descriptor["bytes"] or hashlib.sha256(raw).hexdigest() != descriptor["sha256"]:
                raise ArtifactError("Native complete artifact hash differs")
            name = descriptor["transferId"] + ".artifact"
            existing = self._read_fixed(name, len(raw), descriptor["sha256"], descriptor, allow_absent=True)
            if existing is None:
                self.current(descriptor, "receive"); self.backend.create_private_snapshot(self.root, name, raw, maximum=MAX_BYTES)
            self._read_fixed(name, len(raw), descriptor["sha256"], descriptor)
            item["complete"] = True; self._save(index, old, descriptor)
            return {"reference": descriptor, "contentVerified": True, "savedBytes": len(raw), "sha256": descriptor["sha256"],
                    "readable": True, "atomicPublication": True, "businessVerified": False, "executionAuthorityRestored": False, "taskOrGrantRestored": False}

    def open_verified(self, fixed_reference):
        from contextlib import contextmanager
        descriptor = reference(fixed_reference); self.finish(descriptor)
        @contextmanager
        def opened():
            self.current(descriptor, "receive"); self._root_current()
            with self.backend.open_reader(self.root, descriptor["transferId"] + ".artifact", maximum=MAX_BYTES) as native:
                yield _CurrentReader(native, descriptor, self.current)
                self.current(descriptor, "receive"); native.verify_unchanged()
        return opened()

    def remove(self, fixed_reference):
        descriptor = reference(fixed_reference); self.current(descriptor, "remove")
        with self._lock:
            index, old = self._load(); item = self._entry(index, descriptor)
            names = [(self._chunk_name(descriptor["transferId"], part["offset"]), part["sha256"], part["bytes"]) for part in item["chunks"]]
            # A final file may have published immediately before a checkpoint ACK was lost.
            names.append((descriptor["transferId"] + ".artifact", descriptor["sha256"], descriptor["bytes"]))
            for name, sha, size in names:
                result = self.backend.delete_private_snapshot(self.root, name, sha, size, lambda: self.current(descriptor, "remove"))
                if not isinstance(result, dict) or result.get("deleted") is not True or result.get("verified") is not True:
                    raise ArtifactError("Native cache deletion unconfirmed; original entry retained")
            del index["transfers"][descriptor["transferId"]]; self._save(index, old, descriptor, "remove")

    def close(self):
        pass  # All native handles are scoped by the backend's context managers.
