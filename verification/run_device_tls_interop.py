#!/usr/bin/env python3
"""Run exact product Java SSLEngine <-> Python SSLObject/MemoryBIO on localhost.

Pass a precompiled classpath; no Gradle/process is launched implicitly. Certificates are
one-hour fixtures in an auto-removed temporary directory, not user pairing trust. This
reports its actual Java provider and cannot establish Android-device interop by itself.
"""
import argparse
import asyncio
import json
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "companion"))
from device_transport import DeviceConnection, MemoryBioTls, StreamByteChannel
from device_transport_fixtures import certificate, context


async def run(java, classpath):
    with tempfile.TemporaryDirectory(prefix="pw-localhost-tls-only-") as temporary:
        a, b, wrong = (certificate(temporary, name) for name in ("a", "b", "wrong"))
        results = []
        for mismatch in (False, True):
            echoed, peer_ids, active = [], [], set()
            async def accepted(reader, writer):
                connection = None
                async def on_frame(peer, frame):
                    if frame.control is None:
                        raise AssertionError("Unexpected artifact during control interop")
                    echoed.append(frame.control)
                    if not connection.send_control(frame.control):
                        raise AssertionError("Unexpected backpressure in one-frame fixture")
                async def on_online(peer):
                    peer_ids.append(peer.device_id)
                connection = DeviceConnection(StreamByteChannel(reader, writer), MemoryBioTls(context(b, a, server_side=True), server_side=True, expected_certificate_der=a["der"]), lane="control", on_frame=on_frame, on_online=on_online)
                active.add(connection)
                try:
                    await connection.run()
                finally:
                    active.discard(connection)
            server = await asyncio.start_server(accepted, "127.0.0.1", 0, limit=65_536)
            try:
                port = server.sockets[0].getsockname()[1]
                command = [java, "-Xmx384m", "-cp", classpath, "dev.pocketengine.features.devices.transport.TlsInteropProcess",
                           "127.0.0.1", str(port), str(a["p12"]), str((wrong if mismatch else b)["der_path"])]
                process = await asyncio.create_subprocess_exec(*command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                stdout, stderr = await asyncio.wait_for(process.communicate(), 15)
                if not mismatch:
                    if process.returncode != 0 or len(echoed) != 1 or len(peer_ids) != 1:
                        raise AssertionError("Product Java/Python interop failed: " + stderr.decode(errors="replace"))
                    result = json.loads(stdout)
                    if not result["verifiedEcho"]:
                        raise AssertionError("Java did not verify exact original echo")
                    results.append({"case": "mutual_tls_java_to_python_echo", "javaExit": process.returncode, "actualProvider": result["provider"], "exactBytesEchoed": True, "peerBoundToCertificate": True})
                else:
                    if process.returncode == 0 or echoed:
                        raise AssertionError("Wrong pinned certificate reached application data")
                    results.append({"case": "wrong_target_certificate", "javaExit": process.returncode, "applicationFrames": len(echoed), "rejected": True})
            finally:
                server.close(); await server.wait_closed()
                for connection in tuple(active):
                    await connection.close()
        return {"status": "PASS", "providerScope": "JVM platform SSLEngine / CPython SSLObject MemoryBIO, localhost only", "androidProviderVerified": False, "realNetworkMatrixVerified": False, "temporaryFixtureCredentialsRemoved": True, "cases": results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    args = parser.parse_args()
    print(json.dumps(asyncio.run(run(args.java, args.classpath)), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
