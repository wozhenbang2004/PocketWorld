"""Actual subprocess crash boundaries and immutable artifact identity regressions."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid
from device_artifacts import ArtifactError, ArtifactReceiver, ArtifactTransferStore, reference
from test_device_artifacts import ref


class ArtifactRecoveryTests(unittest.TestCase):
    def test_actual_process_crashes_keep_original_transfer_recoverable_without_delivery(self):
        script = r'''
import json,os,sys
from device_artifacts import ArtifactReceiver
r=ArtifactReceiver(sys.argv[1],current=lambda *a:None); descriptor=json.loads(sys.argv[2]); mode=sys.argv[3]
save,opened,replaced,fsynced=r._save,r._open,os.replace,os.fsync
if mode in ('before-init','after-init','before-receiving','after-receiving'):
 def checkpoint(*a,**kw):
  initial=kw.get('phase')=='initializing'
  hit=initial if mode.endswith('init') else not initial
  if hit and mode.startswith('before'): os._exit(73)
  result=save(*a,**kw)
  if hit: os._exit(73)
  return result
 r._save=checkpoint
if mode in ('created-pending','created-part'):
 def create(name,flags):
  fd=opened(name,flags)
  if flags & os.O_CREAT and ((mode=='created-pending' and name.endswith('.pending')) or (mode=='created-part' and name.endswith('.part'))): os._exit(73)
  return fd
 r._open=create
if mode in ('before-rename','after-rename'):
 def rename(*a,**kw):
  if mode=='before-rename': os._exit(73)
  result=replaced(*a,**kw); os._exit(73)
 os.replace=rename
if mode in ('before-fsync','after-fsync'):
 def sync(fd):
  if mode=='before-fsync': os._exit(73)
  result=fsynced(fd); os._exit(73)
 os.fsync=sync
r.begin(descriptor)
raise AssertionError('fault boundary not reached')
'''
        raw = b"only real complete bytes count"; descriptor = ref(raw)
        for mode in ("before-init", "after-init", "created-pending", "created-part", "before-receiving", "after-receiving", "before-rename", "after-rename", "before-fsync", "after-fsync"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as root:
                child = subprocess.run([sys.executable, "-c", script, root, json.dumps(descriptor), mode], capture_output=True, timeout=5,
                    cwd=Path(__file__).resolve().parent)
                self.assertEqual(73, child.returncode, child.stderr)
                receiver = ArtifactReceiver(root, current=lambda *a: None)
                try:
                    self.assertEqual(0, receiver.begin(descriptor))
                    with self.assertRaises(ArtifactError): receiver.finish(descriptor)
                    receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
                    self.assertTrue(receiver.finish(descriptor)["contentVerified"])
                    receiver.remove(descriptor)
                    self.assertFalse((Path(root) / (descriptor["transferId"] + ".part")).exists())
                finally: receiver.close()

    def test_unclaimed_collision_is_never_adopted_or_deleted(self):
        descriptor = ref(b"new"); sentinel = b"foreign private object; not ours"
        with tempfile.TemporaryDirectory() as root:
            part = Path(root) / (descriptor["transferId"] + ".part"); part.write_bytes(sentinel)
            receiver = ArtifactReceiver(root, current=lambda *a: None)
            try:
                with self.assertRaises(ArtifactError): receiver.begin(descriptor)
                with self.assertRaises(FileNotFoundError): receiver.remove(descriptor)
                self.assertEqual(sentinel, part.read_bytes())
                self.assertFalse((Path(root) / (descriptor["transferId"] + ".json")).exists())
            finally: receiver.close()

    def test_unicode_scalar_fixture_matches_native_reference(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / "protocol/device-v1/artifact-name-fixtures.json").read_text())
        for item in fixture["fixtures"]:
            with self.subTest(item["id"]):
                value = dict(ref(b"x"), name=item["name"])
                if item["valid"]: self.assertEqual(item["name"], reference(value)["name"])
                else:
                    with self.assertRaises((ArtifactError, ValueError)): reference(value)

    def test_artifact_id_is_atomic_immutable_and_exact_publication_is_idempotent(self):
        raw = b"original bytes"; descriptor = ref(raw)
        store = ArtifactTransferStore(source_device_id=descriptor["sourceDeviceId"], current=lambda *a: None)
        entered, release = threading.Event(), threading.Event(); failures = []
        class Paused(io.BytesIO):
            def read(self, length=-1): entered.set(); self_wait = release.wait(3); assert self_wait; return super().read(length)
        def publish():
            try: store.register_stream(Paused(raw), descriptor)
            except Exception as failure: failures.append(failure)
        worker = threading.Thread(target=publish); worker.start(); self.assertTrue(entered.wait(3))
        try:
            conflict = dict(ref(b"other"), artifactId=descriptor["artifactId"], versionId="v-conflict")
            with self.assertRaises(ArtifactError): store.register_stream(io.BytesIO(b"other"), conflict)
            with self.assertRaises(ArtifactError): store.by_artifact_id(descriptor["artifactId"])
        finally: release.set(); worker.join(3)
        self.assertFalse(worker.is_alive()); self.assertEqual([], failures)
        class NoReplay:
            def read(self, length): raise AssertionError("Native exporter re-read on exact publication")
        self.assertEqual(descriptor, store.register_stream(NoReplay(), descriptor))
        self.assertEqual(1, len(store._entries)); self.assertEqual(0, store._reservations)
        self.assertEqual(descriptor, store.by_artifact_id(descriptor["artifactId"]))
        with self.assertRaises(ArtifactError): store.register_stream(io.BytesIO(b"other"), conflict)
        conflict["artifactId"] = "new-independent-artifact"
        store.register_stream(io.BytesIO(b"other"), conflict)
        self.assertEqual(b"other", store.read_chunk(store.by_artifact_id(conflict["artifactId"]), 0).data)
        restarted = ArtifactTransferStore(source_device_id=descriptor["sourceDeviceId"], current=lambda *a: None)
        with self.assertRaises(ArtifactError): restarted.by_artifact_id(descriptor["artifactId"])

    def test_linear_hash_scans_for_4_and_20_mib_and_final_disk_tamper_still_fails(self):
        class Counting(ArtifactReceiver):
            hashed_bytes = 0
            def _scan(self, fd, descriptor):
                sha, size = super()._scan(fd, descriptor); self.hashed_bytes += size; return sha, size
        for size in (4 * 1024 * 1024, 20 * 1024 * 1024):
            with self.subTest(size=size), tempfile.TemporaryDirectory() as root:
                raw = b"A" * size; descriptor = ref(raw)
                receiver = Counting(root, current=lambda *a: None)
                try:
                    receiver.begin(descriptor)
                    for offset in range(0, size, 262144):
                        chunk = raw[offset:offset + 262144]
                        receiver.accept(descriptor, offset, chunk, hashlib.sha256(chunk).hexdigest())
                    self.assertEqual(0, receiver.hashed_bytes, "accept must not reread every prefix")
                    self.assertTrue(receiver.finish(descriptor)["contentVerified"])
                    self.assertEqual(2 * size, receiver.hashed_bytes, "final source+published disk hashes remain mandatory")
                finally: receiver.close()
        with tempfile.TemporaryDirectory() as root:
            raw = b"A" * 524288; descriptor = ref(raw)
            receiver = Counting(root, current=lambda *a: None)
            try:
                receiver.begin(descriptor); receiver.accept(descriptor, 0, raw[:262144], hashlib.sha256(raw[:262144]).hexdigest())
                with (Path(root) / (descriptor["transferId"] + ".part")).open("r+b") as changed: changed.write(b"X")
                receiver.accept(descriptor, 262144, raw[262144:], hashlib.sha256(raw[262144:]).hexdigest())
                with self.assertRaises(ArtifactError): receiver.finish(descriptor)
                receiver.close(); receiver = Counting(root, current=lambda *a: None)
                with self.assertRaises(ArtifactError): receiver.begin(descriptor)
            finally: receiver.close()

    def test_screenshot_cache_rolls_only_unpinned_old_observations_and_never_files(self):
        import time
        store = ArtifactTransferStore(source_device_id="pw1_" + "a" * 64, current=lambda *a: None)
        def capture(index):
            now = time.time_ns() // 1_000_000
            return store.register_snapshot(b"actual fixture capture " + bytes([index]),
                dict(taskId="task", runId="run", operationId="observe-" + str(index), observationRef={"capturedAt": now}, resourceBinding={"displayId": 0}), now + 60000)
        first = capture(0)
        with store.pin(first):
            second, third, fourth = capture(1), capture(2), capture(3)
            latest = capture(4)
            self.assertEqual(first, store.describe(first))
            with self.assertRaises(ArtifactError): store.describe(second)
            self.assertEqual(latest, store.describe(latest)); self.assertEqual(4, len(store._entries))
        permanent = ref(b"file"); permanent["artifactId"] = "permanent-result"
        store.register_stream(io.BytesIO(b"file"), permanent)
        for index in range(5, 12): capture(index)
        self.assertEqual(permanent, store.describe(permanent)); self.assertEqual(4, len(store._entries))
