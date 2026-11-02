#!/usr/bin/env python3
"""Maximum frame/JDK/Python localhost component gate. Synthetic bytes, no business effects.

Only explicit java/classpath are used, serial JVM <=384MiB; every key/cert is temporary.
WSS uses a temporary localhost outer CA with real hostname validation, not trust-all.
"""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "companion"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))
from device_transport import DeviceConnection, MemoryBioTls, StreamByteChannel, WssByteChannel
from device_transport_fixtures import certificate, context, outer_client, outer_server
from pocket_relay import ByteRelay, RouteLease, serve_relay


async def run(java, classpath, capture):
    results = []
    with tempfile.TemporaryDirectory(prefix="pw-boundary-only-") as temporary:
        a, b, outer = (certificate(temporary, name) for name in ("a", "b", "outer"))
        for route_type in ("lan", "wss"):
            for lane, size in (("control", 65536), ("bulk", 262144)):
                active, server_task, outer_service, server = set(), None, None, None
                echoed = []
                async def accepted(channel):
                    connection = None
                    async def frame(peer, value):
                        if lane == "control":
                            echoed.append(len(value.control)); queued = connection.send_control(value.control)
                        else:
                            echoed.append(len(value.data)); await asyncio.sleep(0.1)
                            queued = connection.send_chunk(value.transfer_id, value.offset, value.data, before_write=lambda actual: None)
                        if not queued: raise AssertionError("Unexpected one-frame fixture backpressure")
                    connection = DeviceConnection(channel, MemoryBioTls(context(b, a, server_side=True), server_side=True, expected_certificate_der=a["der"]), lane=lane, on_frame=frame)
                    active.add(connection)
                    try: await connection.run()
                    finally: active.discard(connection)
                try:
                    if route_type == "lan":
                        async def accept_stream(reader, writer): await accepted(StreamByteChannel(reader, writer))
                        server = await asyncio.start_server(accept_stream, "127.0.0.1", 0, limit=65536)
                        target, other = "127.0.0.1", str(server.sockets[0].getsockname()[1])
                    else:
                        route, ta, tb = "boundary" * 3, "a" * 48, "b" * 48
                        service = ByteRelay([RouteLease(route, hashlib.sha256(ta.encode()).hexdigest(), hashlib.sha256(tb.encode()).hexdigest(), time.time() + 60)])
                        outer_service = await serve_relay(service, "127.0.0.1", 0, outer_server(outer))
                        base = "wss://127.0.0.1:" + str(outer_service.sockets[0].getsockname()[1]) + "/v1/tunnel/" + route
                        target, other = base + "/a/" + lane, ta
                        async def accept_wss():
                            channel = await WssByteChannel.connect(base + "/b/" + lane, tb, relay_ssl_context=outer_client(outer))
                            await accepted(channel)
                        server_task = asyncio.create_task(accept_wss())
                    command = [java, "-Xmx384m", "-cp", classpath, "dev.pocketengine.features.devices.transport.TlsBoundaryInteropProcess",
                        target, other, str(a["p12"]), str(b["der_path"]), lane, str(size), str(outer["der_path"])]
                    logs = capture or Path(temporary)
                    logs.mkdir(parents=True, exist_ok=True)
                    out_path, err_path = logs / (route_type + "-" + lane + ".stdout"), logs / (route_type + "-" + lane + ".stderr")
                    with out_path.open("wb") as out_file, err_path.open("wb") as err_file:
                        child = await asyncio.create_subprocess_exec(*command, stdout=out_file, stderr=err_file)
                        try: await asyncio.wait_for(child.wait(), 25)
                        except BaseException:
                            if child.returncode is None: child.kill()
                            await child.wait(); raise
                    stdout, stderr = out_path.read_bytes(), err_path.read_bytes()
                    if child.returncode != 0 or echoed != [size]: raise AssertionError("Boundary interop failed: " + route_type + "/" + lane + " exit=" + str(child.returncode) + "; raw stderr retained")
                    result = json.loads(stdout); result["javaExit"] = child.returncode; results.append(result)
                finally:
                    if server: server.close(); await server.wait_closed()
                    for connection in tuple(active): await connection.close()
                    if server_task:
                        server_task.cancel(); await asyncio.gather(server_task, return_exceptions=True)
                    if outer_service: outer_service.close(); await outer_service.wait_closed()
    return {"status": "PASS", "androidProviderVerified": False, "businessJourneysVerified": False, "temporaryFixtureCredentialsRemoved": True, "cases": results}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--java", required=True); parser.add_argument("--classpath", required=True); parser.add_argument("--capture-dir", type=Path)
    args = parser.parse_args(); print(json.dumps(asyncio.run(run(args.java, args.classpath, args.capture_dir)), indent=2))


if __name__ == "__main__": main()
