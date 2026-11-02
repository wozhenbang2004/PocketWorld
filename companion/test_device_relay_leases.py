"""Operator-local byte resource tests; no public relay, devices, job/permission claims."""
import asyncio
from collections import Counter
import hashlib
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))
import pocket_relay as relay
from device_transport import WssByteChannel
from device_transport_fixtures import certificate, outer_server, outer_client


def lease(name, expires, owner=""):
    return relay.RouteLease(name * 24, hashlib.sha256(b"a" * 48).hexdigest(), hashlib.sha256(b"b" * 48).hexdigest(), expires, owner)


class RelayResourceTests(unittest.IsolatedAsyncioTestCase):
    async def test_native_rotation_two_renewals_bound_old_routes_without_business_state(self):
        now = [100000.0]
        with patch.object(relay, "time", SimpleNamespace(time=lambda: now[0], monotonic=time.monotonic)):
            service = relay.ByteRelay([lease("a", now[0] + 3600)])
            first = service.leases["a" * 24]
            now[0] += 3500
            await service.rotate_leases([lease("b", now[0] + 3600)], overlap_seconds=30)
            self.assertEqual(now[0] + 30, service.leases[first.route_id].expires_at)
            now[0] += 3500
            await service.rotate_leases([lease("c", now[0] + 3600)], overlap_seconds=30)
            self.assertEqual({"b" * 24, "c" * 24}, set(service.leases))
            self.assertFalse(hasattr(service, "tasks")); self.assertFalse(hasattr(service, "allocation"))
            with self.assertRaises(ValueError): await service.rotate_leases([lease("c", now[0] + 7200)])
            with self.assertRaises(ValueError): await service.rotate_leases([lease("d", now[0] + 60)], overlap_seconds=61)
            changed = relay.RouteLease("c" * 24, "f" * 64, "e" * 64, now[0] + 60)
            with self.assertRaises(ValueError): await service.rotate_leases([changed])

    async def test_global_gate_round_robins_routes_and_control_is_not_behind_all_bulk(self):
        gate = relay.FairBandwidth(4 * 65536, 16)
        await gate.acquire(("warmup", "a"), "bulk", 65536)
        completed = []
        async def bulk(key):
            for _ in range(2): await gate.acquire((key, "a"), "bulk", 65536); completed.append(key)
        tasks = [asyncio.create_task(bulk(str(index))) for index in range(4)]
        await asyncio.sleep(0)
        await asyncio.wait_for(gate.acquire(("control", "a"), "control", 10), 1)
        self.assertLess(len(completed), 8)
        await asyncio.wait_for(asyncio.gather(*tasks), 3)
        self.assertEqual({str(index) for index in range(4)}, set(completed[:4]))
        self.assertEqual(Counter({str(index): 2 for index in range(4)}), Counter(completed)); self.assertEqual(0, gate.waiters)

    async def test_minimum_capacity_keeps_two_control_slots_when_bulk_is_full(self):
        temporary = tempfile.TemporaryDirectory(); cert = certificate(temporary.name, "relay-minimum")
        service = relay.ByteRelay([lease("a", time.time() + 60), lease("b", time.time() + 60)], max_connections=4)
        server = await relay.serve_relay(service, "127.0.0.1", 0, outer_server(cert)); channels = []
        base = "wss://127.0.0.1:" + str(server.sockets[0].getsockname()[1]) + "/v1/tunnel/"
        async def pair(route, lane):
            return await asyncio.gather(WssByteChannel.connect(base + route * 24 + "/a/" + lane, "a" * 48, relay_ssl_context=outer_client(cert)),
                                        WssByteChannel.connect(base + route * 24 + "/b/" + lane, "b" * 48, relay_ssl_context=outer_client(cert)))
        try:
            channels += await pair("a", "bulk")
            with self.assertRaises(Exception): await asyncio.wait_for(pair("b", "bulk"), 2)
            channels += await pair("a", "control")
            await channels[2].send(b"\x17\x03\x03\x00\x01\x00")
            self.assertEqual(b"\x17\x03\x03\x00\x01\x00", await channels[3].receive(1))
            self.assertEqual(4, service.connections); self.assertEqual(2, service.noncontrol_connections)
        finally:
            await asyncio.gather(*(channel.close() for channel in channels), return_exceptions=True)
            server.close(); await server.wait_closed(); temporary.cleanup()
        with self.assertRaises(ValueError): relay.ByteRelay([lease("a", time.time() + 60)], max_connections=2)
