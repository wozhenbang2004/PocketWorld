"""Actual original Companion + mTLS write barriers, never Windows GUI evidence."""
import asyncio
import json
import threading
import unittest

import device_protocol as protocol
import device_transport as transport
import test_device_companion as fixture


class OriginalArtifactWriteFenceTests(unittest.TestCase):
    setUp = fixture.NativeCompanionIntegrationTests.setUp
    tearDown = fixture.NativeCompanionIntegrationTests.tearDown
    request = fixture.NativeCompanionIntegrationTests.request
    success = fixture.NativeCompanionIntegrationTests.success

    def test_actual_queued_bulk_rechecks_original_permission_after_pair_revocation(self):
        self._exercise_boundary(historical=False)

    def test_actual_queued_historical_bulk_rechecks_sql_visibility_outside_request_context(self):
        self._exercise_boundary(historical=True)

    def _exercise_boundary(self, *, historical):
        raw = "受控待发送中文，不是用户数据".encode() * 128
        (self.workspace / "owned-result.bin").write_bytes(raw)
        self.service.start()
        ports = {item["lane"]: item["port"] for item in self.service.public_status()["lanes"]}
        async def exercise():
            pending, received = {}, []
            online = {lane: asyncio.Event() for lane in ("control", "bulk")}
            async def control(peer, frame):
                response = json.loads(frame.control); pending.pop(response["requestId"]).set_result(response)
            async def binary(peer, frame): received.append(frame)
            clients, tasks, release = {}, [], None
            async def invoke(raw_request):
                request_id = json.loads(raw_request)["requestId"]
                future = asyncio.get_running_loop().create_future(); pending[request_id] = future
                self.assertTrue(clients["control"].send_control(raw_request))
                return self.success(await asyncio.wait_for(future, 5))
            try:
                for lane in ("control", "bulk"):
                    async def connected(connection, peer, lane=lane): online[lane].set()
                    client = await transport.open_lan("127.0.0.1", ports[lane],
                        context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False),
                        expected_certificate_der=self.b.certificate_der, lane=lane,
                        on_frame=control if lane == "control" else binary, on_connection=connected)
                    clients[lane] = client; tasks.append(asyncio.create_task(client.run()))
                await asyncio.wait_for(asyncio.gather(*(event.wait() for event in online.values())), 5)
                descriptor = await invoke(self.request("workspace.export", {"path": "owned-result.bin"}))
                if historical:
                    self.grant.update(authorizationEpoch=2, expiresAt=self.manager.now() + 80,
                        historyRead={"expiresAt": self.manager.now() + 86400000, "methods": ["artifact.describe", "artifact.readChunk"]})
                    self.receiver.trust.replace_grant(self.grant, self.manager.now())
                    await asyncio.sleep(.1)
                    self.assertLess(self.grant["expiresAt"], self.manager.now())
                paused = threading.Event(); writes = []
                async def hold_original_bulk_pump():
                    connection = self.service.bulk_connections[(self.a.device_id, self.a.certificate_fingerprint)]
                    original_receive, original_write = connection.channel.receive, connection.tls.write
                    resume = asyncio.Event()
                    async def held(timeout):
                        paused.set(); await resume.wait(); return await original_receive(timeout)
                    def record_write(data):
                        writes.append(len(data)); return original_write(data)
                    connection.channel.receive, connection.tls.write = held, record_write
                    return connection, resume
                server, release = await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(hold_original_bulk_pump(), self.service.loop))
                self.assertTrue(await asyncio.to_thread(paused.wait, 3))
                for index, (offset, length) in enumerate(((0, len(raw) // 2), (len(raw) // 2, len(raw) - len(raw) // 2))):
                    arguments = {"artifactId": descriptor["artifactId"], "contentDigest": descriptor["contentDigest"], "offset": offset, "length": length}
                    if historical:
                        request = dict(protocolVersion=protocol.VERSION, protocolProfile=protocol.PROFILE, messageType="DeviceRequest",
                            method="artifact.readChunk", requestId="queued-native-history-" + str(index), originDeviceId=self.a.device_id,
                            callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id, instanceId=self.b.instance_id,
                            grantId=self.grant["grantId"], authorizationEpoch=2, resourceBinding=self.binding,
                            deadline=self.manager.now() + 15000, arguments=arguments)
                        result = await invoke(protocol.canonical_bytes(request))
                    else:
                        result = await invoke(self.request(messageType="DeviceRequest", method="artifact.readChunk", arguments=arguments))
                    self.assertEqual("ready", result["status"])  # QUEUED, not delivered.
                async def queued_frames(): return [item[0] for item in server._queue._queue]
                frames = await asyncio.wrap_future(asyncio.run_coroutine_threadsafe(queued_frames(), self.service.loop))
                self.assertEqual(2, len(frames)); self.assertTrue(all(isinstance(frame, bytearray) and any(frame) for frame in frames))
                before = self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall()
                if historical:
                    with self.manager.lock:
                        self.manager.ledger.execute("UPDATE device_artifact_audiences SET withdrawn=1 WHERE artifact_id=?", (descriptor["artifactId"],))
                        self.manager.ledger.commit()
                else:
                    self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
                self.service.loop.call_soon_threadsafe(release.set)
                async def original_closed():
                    while not server.closed: await asyncio.sleep(.005)
                await asyncio.wait_for(original_closed(), 3)
                self.assertEqual([], writes); self.assertEqual([], received)
                self.assertTrue(all(not any(frame) for frame in frames))
                self.assertEqual(before, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall())
                self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
            finally:
                if release is not None: self.service.loop.call_soon_threadsafe(release.set)
                await asyncio.gather(*(client.close() for client in clients.values()))
                await asyncio.gather(*tasks, return_exceptions=True)
        asyncio.run(exercise())


if __name__ == "__main__": unittest.main()
