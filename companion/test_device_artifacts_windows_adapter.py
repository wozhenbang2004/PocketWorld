"""Shared landing adapter tests. Filesystem backend is a POSIX fixture, NOT Win32 proof."""
from contextlib import contextmanager
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

from device_artifacts import ArtifactError, WindowsArtifactReceiver, CHUNK_BYTES, MAX_ENTRIES
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
        if raw is None: return {"status": "completed", "deleted": False, "verified": True, "alreadyAbsent": True}
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

    def test_cancelled_unfinished_receives_release_durable_cache_slots(self):
        raw = b"A" * CHUNK_BYTES + b"unfinished-tail"
        for offset in (0, CHUNK_BYTES):
            for attempt in range(MAX_ENTRIES + 1):
                with self.subTest(offset=offset, attempt=attempt):
                    descriptor = ref(raw); self.receiver.begin(descriptor)
                    if offset:
                        part = raw[:offset]
                        self.receiver.accept(descriptor, 0, part, hashlib.sha256(part).hexdigest())
                    self.receiver.remove(descriptor)
                    self.receiver = WindowsArtifactReceiver(self.temp.name, backend=self.backend, current=self.current)
                    self.assertEqual({}, self.receiver._load()[0]["transfers"])
                    self.assertEqual([self.receiver.INDEX], [path.name for path in Path(self.temp.name).iterdir()])

    def test_cleanup_retry_accepts_verified_absent_chunks_after_interruption(self):
        raw = b"approved bytes"; descriptor = ref(raw); self.receiver.begin(descriptor)
        self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        before = self.receiver._load()[1]
        delete = self.backend.delete_private_snapshot
        def interrupted(root, name, sha, size, current):
            if name.endswith(".artifact"):
                return {"status": "unknown", "deleted": None, "verified": False}
            return delete(root, name, sha, size, current)
        with patch.object(self.backend, "delete_private_snapshot", side_effect=interrupted):
            with self.assertRaises(ArtifactError): self.receiver.remove(descriptor)
        self.assertEqual(before, self.receiver._load()[1])
        self.assertFalse((Path(self.temp.name) / (descriptor["transferId"] + ".chunk-0")).exists())
        self.receiver.remove(descriptor)
        self.assertEqual({}, self.receiver._load()[0]["transfers"])

    def test_cleanup_rejects_unverified_or_malformed_absence_receipts(self):
        descriptor = ref(b"not received"); self.receiver.begin(descriptor)
        before = self.receiver._load()[1]
        receipts = [None, {}, {"deleted": False, "verified": True},
                    {"deleted": False, "alreadyAbsent": 1, "verified": True},
                    {"deleted": False, "alreadyAbsent": True, "verified": 1},
                    {"deleted": False, "alreadyAbsent": True},
                    {"status": "unknown", "deleted": None, "alreadyAbsent": True, "verified": False},
                    {"deleted": True, "verified": False}]
        for receipt in receipts:
            with self.subTest(receipt=receipt), patch.object(self.backend, "delete_private_snapshot", return_value=receipt):
                with self.assertRaises(ArtifactError): self.receiver.remove(descriptor)
                self.assertEqual(before, self.receiver._load()[1])

    def test_cleanup_retains_changed_chunk_and_exact_reference_gate(self):
        raw = b"approved bytes"; descriptor = ref(raw); self.receiver.begin(descriptor)
        self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        before = self.receiver._load()[1]
        for changed in (dict(descriptor, versionId="changed"), dict(descriptor, transferId=str(uuid.uuid4()))):
            with self.subTest(changed=changed):
                with patch.object(self.backend, "delete_private_snapshot") as delete:
                    with self.assertRaises(ArtifactError): self.receiver.remove(changed)
                    delete.assert_not_called()
        path = Path(self.temp.name) / (descriptor["transferId"] + ".chunk-0")
        changed = b"X" * len(raw); path.write_bytes(changed)
        with self.assertRaises(ArtifactError): self.receiver.remove(descriptor)
        self.assertEqual(changed, path.read_bytes())
        self.assertEqual(before, self.receiver._load()[1])

    def test_cleanup_still_checks_final_object_before_completion_checkpoint(self):
        raw = b"approved final"; descriptor = ref(raw); self.receiver.begin(descriptor)
        before = self.receiver._load()[1]
        name = descriptor["transferId"] + ".artifact"
        changed = b"X" * len(raw)
        self.backend.create_private_snapshot(self.temp.name, name, changed)
        with self.assertRaises(ArtifactError): self.receiver.remove(descriptor)
        self.assertEqual(changed, (Path(self.temp.name) / name).read_bytes())
        self.assertEqual(before, self.receiver._load()[1])
        (Path(self.temp.name) / name).write_bytes(raw)
        self.receiver.remove(descriptor)
        self.assertFalse((Path(self.temp.name) / name).exists())
        self.assertEqual({}, self.receiver._load()[0]["transfers"])

    def test_cleanup_preserves_concurrent_index_cas_and_other_transfers(self):
        descriptor, other = ref(b"first"), ref(b"second")
        self.receiver.begin(descriptor)
        concurrent = WindowsArtifactReceiver(self.temp.name, backend=self.backend, current=self.current)
        delete = self.backend.delete_private_snapshot
        def concurrent_begin(*args):
            result = delete(*args)
            concurrent.begin(other)
            return result
        with patch.object(self.backend, "delete_private_snapshot", side_effect=concurrent_begin):
            with self.assertRaisesRegex(ArtifactError, "CAS mismatch"): self.receiver.remove(descriptor)
        self.assertEqual({descriptor["transferId"], other["transferId"]}, set(self.receiver._load()[0]["transfers"]))
        self.backend.unknown_cas = True  # Lost ACK is resolved from exact index bytes, not a second write.
        self.receiver.remove(descriptor)
        self.assertEqual({other["transferId"]}, set(self.receiver._load()[0]["transfers"]))
        concurrent.close()

    def test_cleanup_rechecks_grant_before_index_removal(self):
        descriptor = ref(b"not received"); self.receiver.begin(descriptor)
        before = self.receiver._load()[1]
        self.allow = False
        with patch.object(self.backend, "delete_private_snapshot") as delete:
            with self.assertRaises(PermissionError): self.receiver.remove(descriptor)
            delete.assert_not_called()
        self.allow = True
        delete = self.backend.delete_private_snapshot
        def revoked_after_absence(*args):
            result = delete(*args); self.allow = False
            return result
        with patch.object(self.backend, "delete_private_snapshot", side_effect=revoked_after_absence):
            with self.assertRaises(PermissionError): self.receiver.remove(descriptor)
        self.assertEqual(before, self.receiver._load()[1])
