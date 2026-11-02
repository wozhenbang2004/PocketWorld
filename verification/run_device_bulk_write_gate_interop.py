#!/usr/bin/env python3
"""Actual JDK -> Python TLS byte-gate counterexample. Only temporary localhost fixtures."""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "companion"))
import device_transport as transport
from device_transport_fixtures import certificate, context


async def run(java, classpath, capture, expect_legacy=False):
    with tempfile.TemporaryDirectory(prefix="pw-bulk-gate-only-") as directory:
        a, b = certificate(directory, "a"), certificate(directory, "b")
        received, actual, finished = [], [], asyncio.Event()
        async def bind(connection, peer):
            actual.append(peer.device_id)
            async def frame(actual_peer, value):
                received.append(value.data)
                if len(received) >= 2: await connection.close()
            connection.on_frame = frame
        async def unused(peer, frame): raise AssertionError("Unbound fixture bulk")
        async def closed(diagnostic, error): finished.set()
        server = await transport.listen_lan("127.0.0.1", 0, owner_enabled=True, context=context(b, a, server_side=True), expected_certificate_der=a["der"],
            lane="bulk", on_frame=unused, on_connection=bind, on_close=closed)
        try:
            command = [java, "-Xmx384m", "-cp", classpath, "dev.pocketengine.features.devices.transport.BulkWriteGateInteropProcess", "127.0.0.1",
                str(server.sockets[0].getsockname()[1]), str(a["p12"]), str(b["der_path"])]
            logs = capture or Path(directory); logs.mkdir(parents=True, exist_ok=True)
            with (logs / "java.stdout").open("wb") as stdout, (logs / "java.stderr").open("wb") as stderr:
                child = await asyncio.create_subprocess_exec(*command, stdout=stdout, stderr=stderr)
                try: await asyncio.wait_for(child.wait(), 20)
                except BaseException:
                    if child.returncode is None: child.kill()
                    await child.wait(); raise
            await asyncio.wait_for(finished.wait(), 3)
            if child.returncode != 0: raise AssertionError("Bulk write-gate JVM failed; exact raw streams retained")
            outcome = json.loads((logs / "java.stdout").read_text())
            if len(actual) != 1: raise AssertionError("Missing exact actual mTLS source")
            expected_chunks = 2 if expect_legacy else 0
            if len(received) != expected_chunks or any(value != b"I" * 1024 for value in received): raise AssertionError("Post-revocation actual payload truth differs")
            outcome.update(actualDecodedPayloadChunks=len(received), actualDecodedPayloadBytes=sum(map(len, received)), legacyVulnerabilityObserved=expect_legacy,
                javaExit=child.returncode, androidProviderVerified=False, originalAndroidSqlAudienceVerified=False, temporaryFixtureCredentialsRemoved=True)
            return outcome
        finally: await server.close()


def main():
    p = argparse.ArgumentParser(); p.add_argument("--java", required=True); p.add_argument("--classpath", required=True); p.add_argument("--capture-dir", type=Path); p.add_argument("--expect-legacy", action="store_true")
    a = p.parse_args(); print(json.dumps(asyncio.run(run(a.java, a.classpath, a.capture_dir, a.expect_legacy)), indent=2))


if __name__ == "__main__": main()
