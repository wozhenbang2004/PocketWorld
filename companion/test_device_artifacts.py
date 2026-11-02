import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import uuid

from device_artifacts import ArtifactError, ArtifactTransferStore, ArtifactReceiver, CHUNK_BYTES, MAX_BYTES, reference


def ref(raw, kind="file"):
    return {"transferId": str(uuid.uuid4()), "sourceDeviceId": "pw1_" + "a" * 64, "artifactId": "original-artifact", "versionId": "v-original",
            "taskId": "task-original", "runId": "run-original", "operationId": "operation-original", "sha256": hashlib.sha256(raw).hexdigest(),
            "bytes": len(raw), "kind": kind, "mimeType": "application/octet-stream", "name": "真实结果.bin"}


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.allowed = True
        self.checks = 0
        def current(descriptor, direction):
            self.checks += 1
            if not self.allowed: raise PermissionError("revoked")
        self.current = current
        self.receiver = ArtifactReceiver(self.root, current=current)
        self.store = ArtifactTransferStore(source_device_id="pw1_" + "a" * 64, current=current)

    def tearDown(self):
        self.receiver.close(); self.temp.cleanup()

    def transfer(self, descriptor, raw):
        self.assertEqual(0, self.receiver.begin(descriptor))
        for offset in range(0, len(raw), CHUNK_BYTES):
            part = raw[offset:offset + CHUNK_BYTES]
            self.receiver.accept(descriptor, offset, part, hashlib.sha256(part).hexdigest())
        return self.receiver.finish(descriptor)

    def test_actual_chunked_bytes_hash_atomic_publish_and_native_read(self):
        raw = bytes(range(256)) * 3000
        descriptor = ref(raw)
        self.assertEqual(descriptor, self.store.register_stream(io.BytesIO(raw), descriptor))
        self.assertEqual(0, self.receiver.begin(descriptor))
        offset = 0
        while offset < len(raw):
            chunk = self.store.read_chunk(descriptor, offset)
            self.assertLessEqual(len(chunk.data), CHUNK_BYTES)
            offset = self.receiver.accept(descriptor, offset, chunk.data, chunk.sha256)
        receipt = self.receiver.finish(descriptor)
        self.assertTrue(receipt["readable"]); self.assertTrue(receipt["contentVerified"]); self.assertFalse(receipt["businessVerified"])
        self.assertFalse(receipt["taskOrGrantRestored"])
        with self.receiver.open_verified(descriptor) as stream:
            parts = []
            while True:
                part = stream.read(CHUNK_BYTES)
                if not part: break
                parts.append(part)
        self.assertEqual(raw, b"".join(parts))
        self.assertGreater(self.checks, 10)

    def test_restart_resume_discards_uncheckpointed_tail_and_version_drift_fails(self):
        raw = b"A" * CHUNK_BYTES + b"B" * 100
        descriptor = ref(raw); self.receiver.begin(descriptor)
        first = raw[:CHUNK_BYTES]; self.receiver.accept(descriptor, 0, first, hashlib.sha256(first).hexdigest())
        with (self.root / (descriptor["transferId"] + ".part")).open("ab") as output: output.write(b"uncommitted tail")
        self.receiver.close(); self.receiver = ArtifactReceiver(self.root, current=self.current)
        self.assertEqual(CHUNK_BYTES, self.receiver.begin(descriptor))
        changed = copy.deepcopy(descriptor); changed["versionId"] = "changed"
        with self.assertRaises(ArtifactError): self.receiver.begin(changed)
        rest = raw[CHUNK_BYTES:]; self.receiver.accept(descriptor, CHUNK_BYTES, rest, hashlib.sha256(rest).hexdigest())
        self.assertEqual(len(raw), self.receiver.finish(descriptor)["savedBytes"])

    def test_partial_bad_hash_or_metadata_is_never_delivery(self):
        raw = b"approved content"; descriptor = ref(raw); self.receiver.begin(descriptor)
        with self.assertRaises(ArtifactError): self.receiver.finish(descriptor)
        with self.assertRaises(ArtifactError): self.receiver.accept(descriptor, 0, raw, "0" * 64)
        malicious = b"different bytes!"
        with self.assertRaises(ArtifactError): self.store.register_stream(io.BytesIO(malicious), descriptor)
        self.assertFalse((self.root / (descriptor["transferId"] + ".artifact")).exists())
        self.receiver.accept(descriptor, 0, b"X" * len(raw), hashlib.sha256(b"X" * len(raw)).hexdigest())
        with self.assertRaises(ArtifactError): self.receiver.finish(descriptor)

    def test_links_escape_replacement_and_existing_destination_are_refused(self):
        descriptor = ref(b"x"); self.receiver.begin(descriptor)
        path = self.root / (descriptor["transferId"] + ".part")
        path.unlink(); path.symlink_to(self.root / "not-approved")
        with self.assertRaises((ArtifactError, OSError)): self.receiver.begin(descriptor)
        with self.assertRaises(ArtifactError): reference({**ref(b"x"), "name": "../escape"})
        for name in ("C:secret", "a\\b", ".."):
            with self.assertRaises(ArtifactError): reference({**ref(b"x"), "name": name})
        descriptor = ref(b"y"); self.receiver.begin(descriptor)
        part = self.root / (descriptor["transferId"] + ".part")
        os.link(part, self.root / "foreign-hardlink")
        with self.assertRaises(ArtifactError): self.receiver.begin(descriptor)

    def test_current_grant_revocation_applies_to_read_resume_and_open_handle(self):
        raw = b"immutable"; descriptor = ref(raw); self.store.register_stream(io.BytesIO(raw), descriptor); self.transfer(descriptor, raw)
        stream = self.receiver.open_verified(descriptor)
        self.allowed = False
        for action in (lambda: self.store.read_chunk(descriptor, 0), lambda: self.receiver.begin(descriptor), lambda: stream.read(1)):
            with self.assertRaises(PermissionError): action()
        stream.close()

    def test_native_screenshot_source_provenance_bounds_and_expiry(self):
        metadata = {"taskId": "task-original", "runId": "run-original", "operationId": "readonly-request-original",
                    "observationRef": {"frameId": "original", "displayId": 0, "capturedAt": time.time_ns() // 1_000_000}, "resourceBinding": {"session": "interactive-original"}}
        descriptor = self.store.register_snapshot(b"\x89PNG\r\n\x1a\nfixture-real-capture-bytes", metadata, time.time_ns() // 1_000_000 + 1000)
        self.assertEqual("screenshot", descriptor["kind"]); self.assertEqual(self.store.source_device_id, descriptor["sourceDeviceId"])
        self.assertNotIn("imageBase64", json.dumps(descriptor)); self.assertNotIn("path", descriptor)
        with self.assertRaises(ArtifactError): self.store.register_snapshot(b"x", metadata, time.time_ns() // 1_000_000 + 600_001)
        wrong = copy.deepcopy(descriptor); wrong["sourceDeviceId"] = "pw1_" + "b" * 64
        with self.assertRaises(ArtifactError): self.store.register_stream(io.BytesIO(b"x"), wrong)

    def test_one_shared_wire_descriptor_roundtrip_preserves_fixed_content(self):
        from device_artifacts import to_wire_descriptor, from_wire_descriptor
        from device_protocol import PROFILE, ProtocolError
        descriptor = ref(b"one fixed version")
        wire = to_wire_descriptor(descriptor, instance_id="native-instance", revision=3, executor_epoch=2)
        self.assertEqual("ArtifactDescriptor", wire["messageType"])
        self.assertEqual(PROFILE, wire["protocolProfile"])
        self.assertEqual(dict(descriptor, executorEpoch=2), from_wire_descriptor(wire))  # Explicit known native epoch is now retained, not a source-request alias.
        self.assertEqual(descriptor["sha256"], wire["contentDigest"])
        self.assertNotIn("sha256", wire)
        for profile in (None, "pwp1_" + "0" * 64):
            stale = dict(wire)
            if profile is None: stale.pop("protocolProfile")
            else: stale["protocolProfile"] = profile
            with self.assertRaises(ProtocolError) as rejected: from_wire_descriptor(stale)
            self.assertEqual("incompatible_profile", rejected.exception.code)

    def test_source_request_run_is_not_invented_as_a_target_native_runner(self):
        from device_artifacts import to_wire_descriptor, from_wire_descriptor
        descriptor = ref(b"actual PC fixed bytes"); descriptor.pop("runId")
        descriptor.update(requestRunId="actual-source-run", requestExecutorEpoch=7)
        store = ArtifactTransferStore(source_device_id=descriptor["sourceDeviceId"], current=self.current)
        store.register_stream(io.BytesIO(b"actual PC fixed bytes"), descriptor)
        wire = store.wire_descriptor(descriptor, instance_id="native-pc-instance", revision=1,
            native_run_id=None, executor_epoch=None, request_run_id="actual-source-run", request_executor_epoch=7)
        self.assertNotIn("runId", wire); self.assertNotIn("executorEpoch", wire)
        self.assertEqual(("actual-source-run", 7), (wire["requestRunId"], wire["requestExecutorEpoch"]))
        self.assertEqual(descriptor, from_wire_descriptor(wire))
        with self.assertRaises(ArtifactError): to_wire_descriptor(descriptor, instance_id="native-pc-instance", revision=1, executor_epoch=1)
        incomplete = dict(descriptor); incomplete.pop("requestExecutorEpoch")
        with self.assertRaises(ArtifactError): reference(incomplete)

    def test_bounded_cache_and_empty_file(self):
        for _ in range(4):
            descriptor = ref(b""); descriptor["artifactId"] = "capacity-" + str(_); self.store.register_stream(io.BytesIO(b""), descriptor)
        with self.assertRaises(ArtifactError): self.store.register_stream(io.BytesIO(b""), ref(b""))
        self.store.remove(descriptor)
        descriptor = ref(b""); self.store.register_stream(io.BytesIO(b""), descriptor)
        self.assertTrue(self.transfer(descriptor, b"")["contentVerified"])
        with self.assertRaises(ArtifactError): reference({**ref(b""), "bytes": MAX_BYTES + 1})

    def test_interrupted_atomic_publication_is_recovered_only_for_owned_pair(self):
        raw = b"recoverable same exact bytes"; descriptor = ref(raw)
        self.receiver.begin(descriptor); self.receiver.accept(descriptor, 0, raw, hashlib.sha256(raw).hexdigest())
        os.link(self.root / (descriptor["transferId"] + ".part"), self.root / (descriptor["transferId"] + ".artifact"))
        self.receiver.close(); self.receiver = ArtifactReceiver(self.root, current=self.current)
        self.assertEqual(len(raw), self.receiver.begin(descriptor)); self.assertTrue(self.receiver.finish(descriptor)["contentVerified"])
        self.assertFalse((self.root / (descriptor["transferId"] + ".part")).exists())


if __name__ == "__main__":
    unittest.main()


class ArtifactEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_tls_permission_gate_and_exact_manifest_chunk_not_path_or_metadata(self):
        import device_protocol as wire
        import test_device_pairing as pairing_fixture
        from device_artifacts import ArtifactEndpoint, ArtifactSession, to_wire_descriptor
        fixture = pairing_fixture.DevicePairingTests(); fixture.setUp()
        receiver_temp = tempfile.TemporaryDirectory()
        receiver = None
        try:
            grant = fixture.grant()
            grant["capabilities"] += ["artifact.describe", "artifact.readChunk"]
            fixture.grant = lambda: grant
            fixture.pair()
            raw = b"actual approved artifact octets"
            descriptor = ref(raw); descriptor["sourceDeviceId"] = fixture.b.device_id
            store = ArtifactTransferStore(source_device_id=fixture.b.device_id, current=lambda desc, direction: None)
            store.register_stream(io.BytesIO(raw), descriptor)
            store.wire_descriptor(descriptor, instance_id=fixture.b.instance_id, revision=3, executor_epoch=1)
            queued = []
            def access(permission, exact):
                self.assertEqual("task-original", exact["taskId"])
                self.assertEqual(exact["taskId"], permission.envelope.get("localTaskId"))
            async def sender(permission, exact, original_read, transfer_id, offset, data):
                original_read()
                permission.check_current(time.time_ns() // 1_000_000)
                queued.append((transfer_id, offset, data)); return True
            endpoint = ArtifactEndpoint(store, native_instance_id=fixture.b.instance_id, revision=1, executor_epoch=1, access=access, bulk_sender=sender)
            def permission(method, arguments):
                envelope = fixture.envelope(messageType="DeviceRequest", method=method, localTaskId="task-original", arguments=arguments)
                return fixture.coordinator.check_permission(fixture.peer_a, envelope, fixture.now)
            describe = await endpoint.receive(permission("artifact.describe", {"artifactId": descriptor["artifactId"]}))
            self.assertEqual(3, describe["revision"]); self.assertEqual(descriptor["sha256"], describe["contentDigest"])
            self.assertNotIn("path", describe)
            read_permission = permission("artifact.readChunk", {"artifactId": descriptor["artifactId"], "contentDigest": descriptor["sha256"], "offset": 0, "length": 5})
            header = await endpoint.receive(read_permission)
            self.assertEqual({"artifactId", "transferId", "offset", "length", "contentDigest", "status"}, set(header))
            self.assertEqual("ready", header["status"]); self.assertEqual(5, header["length"]); self.assertEqual(raw[:5], queued[0][2])
            with self.assertRaises(ArtifactError):
                await endpoint.receive(permission("artifact.readChunk", {"artifactId": descriptor["artifactId"], "contentDigest": "f" * 64, "offset": 0, "length": 5}))
            # Incoming bulk must belong to the SAME authenticated peer that supplied the approved manifest.
            receiver = ArtifactReceiver(receiver_temp.name, current=lambda exact, direction: read_permission.check_current(time.time_ns() // 1_000_000))
            session = ArtifactSession(receiver)
            forged_source = dict(descriptor, sourceDeviceId=fixture.a.device_id)
            with self.assertRaises(ArtifactError):
                session.prepare_incoming(fixture.peer_b, forged_source, lambda: read_permission.check_current(time.time_ns() // 1_000_000))
            self.assertFalse((Path(receiver_temp.name) / (descriptor["transferId"] + ".json")).exists())
            session.prepare_incoming(fixture.peer_b, descriptor, lambda: read_permission.check_current(time.time_ns() // 1_000_000))
            with self.assertRaises(ArtifactError): session.accept(fixture.peer_a, descriptor["transferId"], 0, raw)
            session.accept(fixture.peer_b, descriptor["transferId"], 0, raw)
            self.assertTrue(session.finish(fixture.peer_b, descriptor["transferId"])["contentVerified"])
            fixture.coordinator.revoke_locally(fixture.a.device_id, fixture.now + 2)
            with self.assertRaises(PermissionError): await endpoint.receive(read_permission)
            with self.assertRaises(PermissionError): session.finish(fixture.peer_b, descriptor["transferId"])
        finally:
            if receiver: receiver.close()
            receiver_temp.cleanup(); fixture.tearDown()
