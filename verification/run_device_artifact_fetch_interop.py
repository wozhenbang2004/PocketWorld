#!/usr/bin/env python3
"""Real Java SSLEngine/Fetch -> Python SSLObject/artifact endpoint on isolated localhost.

Only temporary TEST credentials/native receive approval. The original Python Pairing SQLite
grant is checked for every request; the fixture's resource/export approval is not Android
TaskStore audience, Windows I/O, model HTTP, user provisioning or public-network evidence.
"""
import argparse
import asyncio
import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "companion"))
import device_artifacts as artifacts
import device_protocol as protocol
import device_transport as transport
import test_device_pairing as fixtures


async def run(java, classpath, capture, only_case=None):
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.serialization import pkcs12
    fixture = fixtures.DevicePairingTests(); fixture.setUp(); results = []
    try:
        grant = fixture.grant(); grant.update(capabilities=["artifact.describe", "artifact.readChunk"], toolBudget=0, modelBudget=0)
        fixture.grant = lambda: grant; fixture.pair()
        with tempfile.TemporaryDirectory(prefix="pw-only-fetch-interop-") as directory:
            root = Path(directory)
            (root / "a.p12").write_bytes(pkcs12.serialize_key_and_certificates(b"fixture-a", serialization.load_pem_private_key(fixture.a._key(), password=None),
                x509.load_der_x509_certificate(fixture.a.certificate_der), None, serialization.BestAvailableEncryption(b"localhost-test-only")))
            (root / "b.der").write_bytes(fixture.b.certificate_der)
            template = fixture.read_query(method="artifact.readChunk", arguments={"artifactId": "fixture-artifact", "contentDigest": "0" * 64, "offset": 0, "length": 1}).json()
            (root / "read.json").write_bytes(protocol.canonical_bytes(template))
            modes = ("normal", "resume", "image", "bad-ready", "wrong-request", "corrupt", "revoke", "wrong-offset", "same-transfer-cleanup", "bulk-gate-revoke")
            if only_case is not None:
                if only_case not in modes: raise ValueError("Unknown fixture case")
                modes = (only_case,)
            for mode in modes:
                listeners, actual_bulk, offsets = [], [], []
                write_gate_entered, release_write_gate = asyncio.Event(), asyncio.Event()
                try:
                    raw = bytes(range(256)) * (3 * 1024) + b"fixed-final-tail"
                    if mode == "image": raw = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jYZkAAAAASUVORK5CYII=")
                    now = time.time_ns() // 1_000_000
                    fixed = dict(transferId=str(uuid.uuid4()), sourceDeviceId=fixture.b.device_id, artifactId="fixture-artifact", versionId="fixed-v1", taskId="native-fixture-target",
                        operationId="native-fixture-publication", sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw), kind="file", mimeType="application/octet-stream", name="actual-bytes.bin")
                    if mode == "image": fixed.update(kind="screenshot", mimeType="image/png", name="actual-frame.png", observationRef={"capturedAt": now, "frameId": "native-fixture-frame"}, resourceBinding=fixture.binding, expiresAt=now + 60000)
                    current_calls = []
                    store = artifacts.ArtifactTransferStore(source_device_id=fixture.b.device_id, current=lambda exact, direction: current_calls.append(direction))
                    store.register_stream(io.BytesIO(raw), fixed)
                    wire = store.wire_descriptor(fixed, instance_id=fixture.b.instance_id, revision=1)
                    (root / "descriptor.json").write_bytes(protocol.canonical_bytes(wire))
                    async def bulk_bound(connection, peer):
                        if peer.device_id != fixture.a.device_id: raise AssertionError("Wrong actual fixture bulk peer")
                        actual_bulk.append((connection, peer))
                    async def unused(peer, frame): raise AssertionError("Unexpected data on fixture bulk receiver")
                    bulk = await transport.listen_lan("127.0.0.1", 0, owner_enabled=True,
                        context=transport.native_device_context(fixture.b, [fixture.a.certificate_der], server_side=True), expected_certificate_der=fixture.a.certificate_der,
                        lane="bulk", on_frame=unused, on_connection=bulk_bound)
                    listeners.append(bulk)
                    def access(permission, exact):
                        permission.check_current(time.time_ns() // 1_000_000)
                        if exact != fixed or permission.envelope.get("resourceBinding") != fixture.binding: raise PermissionError("Native fixture manifest/export approval differs")
                    async def send_bulk(permission, exact, original_read, transfer, offset, data):
                        access(permission, fixed)
                        if len(actual_bulk) != 1 or actual_bulk[0][1].device_id != fixture.a.device_id: raise PermissionError("Actual paired bulk channel absent")
                        if mode == "corrupt" and offset + len(data) == len(raw): data = data[:-1] + bytes([data[-1] ^ 1])
                        if mode == "wrong-offset": offset += 1
                        async def current_write(actual):
                            if mode == "bulk-gate-revoke": write_gate_entered.set(); await release_write_gate.wait()
                            original_read()
                            if (actual.device_id, actual.certificate_fingerprint) != (actual_bulk[0][1].device_id, actual_bulk[0][1].certificate_fingerprint): raise PermissionError("Actual bulk write target changed")
                        return actual_bulk[0][0].send_chunk(transfer, offset, data, before_write=current_write)
                    endpoint = artifacts.ArtifactEndpoint(store, native_instance_id=fixture.b.instance_id, revision=1, access=access, bulk_sender=send_bulk)
                    async def control_bound(connection, actual_peer):
                        async def receive(peer, frame):
                            query = protocol.parse_request(frame.control)
                            permission = fixture.coordinator.check_permission(peer, query, time.time_ns() // 1_000_000)
                            offsets.append(query.arguments["offset"])
                            result = await endpoint.receive(permission)
                            if mode == "bulk-gate-revoke":
                                await asyncio.wait_for(write_gate_entered.wait(), 3)
                                fixture.coordinator.revoke_locally(fixture.a.device_id, time.time_ns() // 1000000)
                                release_write_gate.set()
                            if mode == "bad-ready": result["contentDigest"] = "0" * 64
                            response = dict(protocolVersion=protocol.VERSION, protocolProfile=protocol.PROFILE, messageType="DeviceResponse", requestId=query.get("requestId"),
                                method=query.method, executorDeviceId=fixture.b.device_id, instanceId=fixture.b.instance_id, result=result)
                            if mode == "wrong-request": response["requestId"] = "unrelated-original-query"
                            response = protocol.validate_message(response)
                            if not connection.send_control(protocol.canonical_bytes(response)): raise AssertionError("Fixture control backpressure")
                        connection.on_frame = receive
                    control = await transport.listen_lan("127.0.0.1", 0, owner_enabled=True,
                        context=transport.native_device_context(fixture.b, [fixture.a.certificate_der], server_side=True), expected_certificate_der=fixture.a.certificate_der,
                        lane="control", on_frame=unused, on_connection=control_bound)
                    listeners.append(control)
                    command = [java, "-Xmx384m", "-cp", classpath, "dev.pocketengine.features.devices.transport.ArtifactFetchInteropProcess", "127.0.0.1",
                        str(control.sockets[0].getsockname()[1]), str(bulk.sockets[0].getsockname()[1]), str(root / "a.p12"), str(root / "b.der"), str(root / "read.json"), str(root / "descriptor.json"), mode]
                    logs = capture or root; logs.mkdir(parents=True, exist_ok=True)
                    out_path, err_path = logs / (mode + ".stdout"), logs / (mode + ".stderr")
                    with out_path.open("wb") as stdout, err_path.open("wb") as stderr:
                        child = await asyncio.create_subprocess_exec(*command, stdout=stdout, stderr=stderr)
                        try: await asyncio.wait_for(child.wait(), 25)
                        except BaseException:
                            if child.returncode is None: child.kill()
                            await child.wait(); raise
                    if child.returncode: raise AssertionError("Actual artifact fetch interop failed: " + mode + " exit=" + str(child.returncode) + "; raw streams retained")
                    result = json.loads(out_path.read_text()); result.update(javaExit=child.returncode, actualAuthorizedReadOffsets=offsets, nativeExportChecks=len(current_calls))
                    if result["bytesVerified"] != (mode in {"normal", "resume", "image", "same-transfer-cleanup"}): raise AssertionError("Wrong verified-byte truth")
                    if mode == "resume" and offsets.count(0) != 1: raise AssertionError("Explicit fixed-data resume repeated its acknowledged prefix")
                    results.append(result)
                finally:
                    for listener in listeners: await listener.close()
    finally:
        fixture.tearDown()
    return {"status": "PASS", "temporaryFixtureCredentialsRemoved": True, "androidProviderVerified": False, "originalAndroidSqlAudienceVerified": False,
        "modelInputDelivered": False, "cases": results}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--java", required=True); parser.add_argument("--classpath", required=True); parser.add_argument("--capture-dir", type=Path); parser.add_argument("--only-case")
    args = parser.parse_args(); print(json.dumps(asyncio.run(run(args.java, args.classpath, args.capture_dir, args.only_case)), ensure_ascii=False, indent=2))


if __name__ == "__main__": main()
