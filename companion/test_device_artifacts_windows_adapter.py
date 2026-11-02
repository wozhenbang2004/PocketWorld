"""Shared landing adapter tests. Filesystem backend is a POSIX fixture, NOT Win32 proof."""
from contextlib import contextmanager
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
import uuid

from device_artifacts import ArtifactError, WindowsArtifactReceiver, CHUNK_BYTES
from test_device_artifacts import ref


class FixtureNativeBackend:
    name = "windows"  # Explicit native-API fixture, not a claim of Windows execution.
    def __init__(self):
        self.calls = []
        self.snapshot_count = 0
        self.fail_publication = False
        self.unknown_cas = False

    def root_binding(self, root):
        info = Path(root).stat(); return str((info.st_dev, info.st_ino))

    def read_bytes(self, root, relative, maximum=262144):
        self.calls.append(("read", relative))
        path = Path(root) / relative
        if not path.exists(): return None
        raw = path.read_bytes()
        if len(raw) > maximum: raise ArtifactError("Fixture native size bound")
        return raw

    @contextmanager
    def open_reader(self, root, relative, maximum=20 * 1024 * 1024):
        raw = self.read_bytes(root, relative, maximum)
        class Reader(io.BytesIO):
            def verify_unchanged(self): return True
        if raw is None: raise FileNotFoundError("Fixture missing native object")
        yield Reader(raw)

    def create_private_snapshot(self, root, relative, raw, maximum=20 * 1024 * 1024, *, current=lambda: None):
        self.calls.append(("publish", relative)); self.snapshot_count += 1
        if self.fail_publication:
            return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}  # Metadata lie must not pass.
        if len(raw) > maximum: raise ArtifactError("Fixture native size bound")
        current()
        with (Path(root) / relative).open("xb") as stream: stream.write(raw)
        return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}

    def compare_and_swap(self, root, relative, before, after, current):
        self.calls.append(("cas", relative)); current()
        if self.read_bytes(root, relative) != before: raise ArtifactError("Fixture native CAS mismatch")
        (Path(root) / relative).write_bytes(after)
        return {"status": "unknown" if self.unknown_cas else "completed", "verified": not self.unknown_cas}

    def delete_private_snapshot(self, root, relative, expected_sha256, expected_bytes, current):
        self.calls.append(("delete", relative)); current()
        raw = self.read_bytes(root, relative, 20 * 1024 * 1024)
        if raw is None: return {"status": "completed", "deleted": True, "verified": True, "alreadyAbsent": True}
        if len(raw) != expected_bytes or hashlib.sha256(raw).hexdigest() != expected_sha256: raise ArtifactError("Fixture native delete identity mismatch")
        (Path(root) / relative).unlink()
        return {"status": "completed", "deleted": True, "verified": True}


class WindowsLandingAdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.backend = FixtureNativeBackend(); self.allow = True
        def current(descriptor, direction):
            if not self.allow: raise PermissionError("Native grant revoked")
        self.current = current
        self.receiver = WindowsArtifactReceiver(self.temp.name, backend=self.backend, current=current)

    def tearDown(self):
        self.receiver.close(); self.temp.cleanup()

    def test_native_only_chunk_checkpoint_restart_fixed_hash_and_atomic_delivery(self):
        raw = b"A" * CHUNK_BYTES + b"original-final-version"; descriptor = ref(raw)
        self.assertEqual(0, self.receiver.begin(descriptor))
        first = raw[:CHUNK_BYTES]
        self.receiver.accept(descriptor, 0, first, hashlib.sha256(first).hexdigest())
        self.receiver = WindowsArtifactReceiver(self.temp.name, backend=self.backend, current=self.current)
        self.assertEqual(len(first), self.receiver.begin(descriptor))
        tail = raw[len(first):]
        self.receiver.accept(descriptor, len(first), tail, hashlib.sha256(tail).hexdigest())
        receipt = self.receiver.finish(descriptor)
        self.assertTrue(receipt["contentVerified"]); self.assertFalse(receipt["businessVerified"])
        with self.receiver.open_verified(descriptor) as reader:
            self.assertEqual(first, reader.read(CHUNK_BYTES)); self.assertEqual(tail, reader.read(CHUNK_BYTES))
        self.assertEqual(3, self.backend.snapshot_count)  # two bounded chunks, one absent-only final.
        self.assertTrue(all('/' not in name and '\\' not in name for kind, name in self.backend.calls))
        self.receiver.remove(descriptor)
        self.assertEqual({}, self.receiver._load()[0]["transfers"])

    def test_native_metadata_without_bytes_is_never_delivered(self):
        raw = b"actual bytes"; descriptor = ref(raw); self.receiver.begin(descriptor)
        self.backend.fail_publication = True
        with self.assertRaises(ArtifactError): self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        with self.assertRaises(ArtifactError): self.receiver.finish(descriptor)
        self.assertFalse((Path(self.temp.name) / (descriptor["transferId"] + ".artifact")).exists())

    def test_unknown_native_checkpoint_is_queried_not_blindly_retried(self):
        raw = b"native original bytes"; descriptor = ref(raw)
        self.backend.unknown_cas = True
        self.assertEqual(0, self.receiver.begin(descriptor))
        self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        self.assertTrue(self.receiver.finish(descriptor)["contentVerified"])
        self.assertEqual(2, self.backend.snapshot_count)
        # Original immutable chunk happened, but its following index ACK did not.
        raw = b"second same original content"; descriptor = ref(raw); self.receiver.begin(descriptor)
        self.backend.create_private_snapshot(self.temp.name, descriptor["transferId"] + ".chunk-0", raw)
        count = self.backend.snapshot_count
        self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        self.assertEqual(count, self.backend.snapshot_count)

    def test_native_grant_revocation_and_exact_version_bounds(self):
        raw = b"approved"; descriptor = ref(raw); self.receiver.begin(descriptor)
        with self.assertRaises(ArtifactError): self.receiver.accept(descriptor, 0, b"app", hashlib.sha256(b"app").hexdigest())
        changed = dict(descriptor, versionId="changed")
        with self.assertRaises(ArtifactError): self.receiver.begin(changed)
        self.allow = False
        with self.assertRaises(PermissionError): self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        self.assertEqual(0, self.backend.snapshot_count)
