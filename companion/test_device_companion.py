"""Original supervisor + real ephemeral mTLS/SQLite/process/file integration.

Linux loopback evidence, not a Windows GUI, signed installation, phone or WAN
acceptance claim. All identities/files/commands here are controlled fixtures.
"""
import asyncio
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import device_identity as identity
import device_pairing as pairing
import device_protocol as wire
import device_transport as transport
from device_companion import DeviceCompanionService, NativeCommandAdmission, workspace_binding
from pocket_companion import JobManager, RequestError
from test_device_pairing import tls_peers


class NativeCompanionIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-companion-fixture-")
        self.root = Path(self.temp.name)
        self.workspace = self.root / "workspace"; self.workspace.mkdir()
        self.manager = JobManager({"stateDir": str(self.root / "state"), "devices": {"enabled": True, "listen": {"host": "127.0.0.1", "controlPort": 0, "bulkPort": 0}},
                                   "environments": [{"id": "computer", "kind": "local", "root": str(self.workspace), "writeAllowed": True,
                                                     "workers": ["command"], "commands": {"python": sys.executable}}]}, "fixture-only-legacy-token-never-device-authority")
        self.a = identity.provision_identity(self.root / "ephemeral-a")
        self.b = identity.provision_identity(self.root / "ephemeral-b")
        self.peer_a, peer_b = tls_peers(self.a, self.b)
        self.service = DeviceCompanionService(self.manager, local_identity=self.b)
        self.manager.device_companion = self.service
        self.receiver = self.service.receiver
        self.binding = workspace_binding(self.manager, "computer")
        self.now = self.manager.now()
        self.grant = dict(grantId="native-fixture-grant", peerDeviceId=self.a.device_id, localDeviceId=self.b.device_id, authorizationEpoch=1,
                          issuedAt=self.now, expiresAt=self.now + 3600000, capabilities=["tool.invoke", "operation.get", "artifact.describe", "artifact.readChunk"],
                          toolNames=["command.run", "workspace.list", "workspace.read", "workspace.write", "workspace.export"], resources=[self.binding],
                          allowedOrigins=[self.a.device_id], allowDelegation=False, maxDelegationDepth=0, toolBudget=40, modelBudget=0)
        invitation = self.receiver.pairing.create_invitation(self.b, self.a.certificate_fingerprint, self.now)
        transcript = pairing.pairing_transcript(invitation, peer_b, self.a.device_id, self.a.instance_id, self.a.certificate_fingerprint, self.now)
        self.receiver.pairing.complete_pairing(invitation, self.peer_a, pairing.confirm_locally(self.b, transcript, True),
                                               pairing.confirm_locally(self.a, transcript, True).wire(), self.grant, self.now)

    def tearDown(self):
        self.manager.close()
        self.temp.cleanup()

    def request(self, tool="workspace.list", args=None, **updates):
        effect = "write" if tool in {"command.run", "workspace.write"} else "read"
        value = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="ToolInvocation", method="tool.invoke", requestId="request-" + str(time.monotonic_ns()),
                     rootTaskId="original-root", parentTaskId="original-parent", localTaskId="original-task", runId="original-run", attempt=1,
                     executorEpoch=1, instanceId=self.b.instance_id, originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id,
                     targetDeviceId=self.b.device_id, grantId=self.grant["grantId"], authorizationEpoch=1, capabilityVersion="workspace-v1",
                     resourceBinding=self.binding, deadline=self.now + 600000, budget={"allocationId": wire.allocation_id("original-parent", self.b.device_id, self.binding, "tool_session"), "toolCalls": 40, "modelCalls": 0},
                     arguments={"toolName": tool, "effect": effect, "arguments": args or {}})
        if effect == "write":
            value["operationId"] = "native-op-" + str(time.monotonic_ns())
        value.update(updates)
        if value["method"] == "device.describe":
            for field in ("grantId", "authorizationEpoch", "budget", "rootTaskId", "parentTaskId", "localTaskId", "runId", "attempt", "executorEpoch", "capabilityVersion", "resourceBinding", "operationId"):
                value.pop(field, None)
            value["deadline"] = self.manager.now() + 15000
        value["argumentsDigest"] = wire.arguments_digest(value)
        return wire.canonical_bytes(wire.parse_request(wire.canonical_bytes(value)).json())

    def receive(self, request, peer=None):
        return json.loads(asyncio.run(self.receiver.receive(peer or self.peer_a, request)))

    def success(self, response):
        self.assertEqual("DeviceResponse", response["messageType"], response)
        self.assertLessEqual(len(wire.canonical_bytes(response)), 65536)
        wire.validate_message(response)
        result = response["result"]
        if response["method"] in {"tool.invoke", "operation.get", "operation.cancel"}:
            self.assertEqual("OperationReceipt", result["messageType"])
            self.assertNotIn("runId", result)  # PC does not invent a TaskStore run.
            self.assertNotIn("executorEpoch", result)
            if result["accepted"]:
                self.assertEqual(result["operationId"], result["audience"]["objectId"])
                self.assertEqual(result["acceptedAt"], result["audience"]["acceptedAt"])
            return result["payload"]
        if response["method"] == "device.describe":
            self.assertEqual("DeviceDescription", result["messageType"])
            self.assertFalse(result["executionAuthorized"])
            return {**result["metadata"], "capabilities": result["capabilities"], "grantReceipts": result["grantReceipts"]}
        return result

    def finished(self, job):
        limit = time.monotonic() + 5
        while time.monotonic() < limit:
            result = self.manager.get(job["id"])
            if result["status"] in {"completed", "failed", "unknown", "cancelled", "blocked"}:
                return result
            time.sleep(.01)
        self.fail("Original process did not settle")

    def test_no_codex_explicit_command_chinese_output_and_dedup_share_original_budget(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "from pathlib import Path; Path('result.txt').write_text('中文保存结果',encoding='utf-8')"]})
        job = self.success(self.receive(raw))
        self.assertEqual("completed", self.finished(job)["status"])
        self.assertEqual("中文保存结果", (self.workspace / "result.txt").read_text())
        self.assertEqual(job["id"], self.success(self.receive(raw))["id"])
        with self.manager.lock:
            self.assertEqual((1, 1), (self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0], self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0]))
        description = self.success(self.receive(self.request(messageType="DeviceRequest", method="device.describe", arguments={})))
        self.assertFalse(description["externalAgentRequired"])
        self.assertIn("command.run", description["toolNames"])
        self.assertFalse(description["desktop"]["available"])
        self.assertFalse(description["taskControl"])
        self.assertEqual("workspace-v1", description["capabilities"][0]["version"])
        self.assertIn({"toolName": "command.run", "effect": "write"}, description["capabilities"][0]["toolDeclarations"])
        self.assertEqual(self.receiver.trust.grant(self.grant["grantId"]), description["grantReceipts"][0]["body"]["grant"])
        forged_discovery = json.loads(self.request(messageType="DeviceRequest", method="device.describe", arguments={}))
        forged_discovery.update(grantId=self.grant["grantId"], authorizationEpoch=1)
        forged_discovery["argumentsDigest"] = wire.arguments_digest(forged_discovery)
        self.assertEqual("permission_denied", self.receive(wire.canonical_bytes(forged_discovery))["error"]["code"])

    def test_untyped_peer_legacy_token_and_forged_native_admission_fail_before_reservation(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "raise Exception('must not run')"]})
        result = self.receive(raw, peer={"actorId": self.a.device_id, "token": self.manager.token})
        self.assertEqual("permission_denied", result["error"]["code"])
        with self.assertRaises(RequestError):
            self.manager.submit({"operationId": "forged"}, native_device_admission=object())
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])

    def test_file_write_query_audience_revocation_and_original_file_receipt(self):
        raw = self.request("workspace.write", {"path": "中文.txt", "content": "真实UTF-8字节", "expectedVersion": "absent"})
        receipt = self.success(self.receive(raw))
        self.assertTrue(receipt["verified"])
        query = self.request(messageType="DeviceRequest", method="operation.get", arguments={"operationId": json.loads(raw)["operationId"]})
        self.assertEqual(receipt, self.success(self.receive(query))["originalFileReceipt"])
        wrong = self.request(messageType="DeviceRequest", method="operation.get", localTaskId="different-task", arguments={"operationId": json.loads(raw)["operationId"]})
        self.assertEqual("permission_denied", self.receive(wrong)["error"]["code"])
        self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
        self.assertEqual("permission_denied", self.receive(query)["error"]["code"])
        self.assertEqual("真实UTF-8字节".encode(), (self.workspace / "中文.txt").read_bytes())
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_native_command_environment_has_no_ambient_model_relay_or_companion_secrets(self):
        with patch.dict(os.environ, {"POCKETENGINE_COMPANION_TOKEN": "fixture-secret", "OPENAI_API_KEY": "fixture-model-secret", "PW_ROUTE_TOKEN": "fixture-route-secret"}):
            raw = self.request("command.run", {"argv": ["python", "-c", "import os; print('|'.join(str(os.getenv(k)) for k in ['POCKETENGINE_COMPANION_TOKEN','OPENAI_API_KEY','PW_ROUTE_TOKEN']))"]})
            result = self.finished(self.success(self.receive(raw)))
        self.assertIn("None|None|None", result["output"])
        self.assertNotIn("fixture-secret", result["output"])

    def test_revoke_live_command_stops_original_group_and_retains_unknown_and_spend(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "import time; print('started',flush=True); time.sleep(30)"]})
        job = self.success(self.receive(raw))
        limit = time.monotonic() + 2
        while job["id"] not in self.manager.processes and time.monotonic() < limit:
            time.sleep(.01)
        self.assertIn(job["id"], self.manager.processes)
        self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
        result = self.finished(job)
        self.assertEqual("unknown", result["status"], result)
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertEqual("permission_denied", self.receive(raw)["error"]["code"])

    def test_startup_missing_existing_identity_never_provisions(self):
        missing = self.root / "missing-identity"
        self.manager.config["devices"]["identityDir"] = str(missing)
        with patch.object(identity, "provision_identity", side_effect=AssertionError("startup must not provision")):
            with self.assertRaises(FileNotFoundError):
                DeviceCompanionService(self.manager)
        self.assertFalse(missing.exists())

    def test_explicit_cp936_output_decodes_chinese_and_is_bound_to_environment_review(self):
        self.manager.environments["computer"]["commandOutputEncoding"] = "cp936"
        self.binding = workspace_binding(self.manager, "computer")
        self.grant.update(authorizationEpoch=2, resources=[self.binding])
        self.receiver.trust.replace_grant(self.grant, self.manager.now())
        raw = self.request("command.run", {"argv": ["python", "-c", "import sys;sys.stdout.buffer.write('中文代码页'.encode('gbk'))"]}, authorizationEpoch=2)
        result = self.finished(self.success(self.receive(raw)))
        self.assertEqual("中文代码页", result["output"])
        self.assertEqual("cp936", result["outputEncoding"])
        self.assertTrue(result["outputLosslesslyDecoded"])

    def test_invalid_declared_encoding_is_visible_and_all_raw_output_still_drains(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "import sys;sys.stdout.buffer.write(b'\\xff'*300000)"]})
        result = self.finished(self.success(self.receive(raw)))
        self.assertEqual("completed", result["status"])
        self.assertEqual("invalid_declared_encoding", result["outputDecodeStatus"])
        self.assertFalse(result["outputLosslesslyDecoded"])
        self.assertEqual(300000, result["rawOutputBytesDrained"])
        self.assertNotIn("\ufffd", result["output"])

    def test_control_response_bounds_original_long_output_without_mutating_job(self):
        raw = self.request("command.run", {"argv": ["python", "-c", "print('中文'*20000)"]})
        job = self.finished(self.success(self.receive(raw)))
        self.assertGreater(len(job["output"].encode()), 16384)
        query = self.request(messageType="DeviceRequest", method="operation.get", arguments={"operationId": job["operationId"]})
        result = self.success(self.receive(query))
        self.assertTrue(result["outputFrameTruncated"])
        self.assertLessEqual(len(result["output"].encode()), 16384)
        self.assertEqual(job["output"], self.manager.get(job["id"])["output"])

    def test_actual_loopback_mutual_tls_command_and_separate_verified_bulk_file_return(self):
        self.service.start()
        ports = {item["lane"]: item["port"] for item in self.service.public_status()["lanes"]}
        async def exercise():
            pending, chunks = {}, asyncio.Queue()
            online = {lane: asyncio.Event() for lane in ("control", "bulk")}
            async def reply(peer, frame):
                self.assertEqual(self.b.device_id, peer.device_id)
                response = json.loads(frame.control)
                pending.pop(response["requestId"]).set_result(response)
            async def chunk(peer, frame):
                self.assertEqual(self.b.device_id, peer.device_id)
                await chunks.put(frame)
            clients, tasks = {}, []
            for lane in ("control", "bulk"):
                async def connected(connection, peer, lane=lane):
                    self.assertEqual(self.b.device_id, peer.device_id); online[lane].set()
                clients[lane] = await transport.open_lan("127.0.0.1", ports[lane], context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False),
                                                        expected_certificate_der=self.b.certificate_der, lane=lane, on_frame=reply if lane == "control" else chunk, on_connection=connected)
                tasks.append(asyncio.create_task(clients[lane].run()))
            async def invoke(raw):
                request_id = json.loads(raw)["requestId"]
                future = asyncio.get_running_loop().create_future(); pending[request_id] = future
                self.assertTrue(clients["control"].send_control(raw))
                return self.success(await asyncio.wait_for(future, 5))
            try:
                await asyncio.wait_for(asyncio.gather(*(event.wait() for event in online.values())), 5)
                job = await invoke(self.request("command.run", {"argv": ["python", "-c", "from pathlib import Path; Path('returned.txt').write_text('跨设备中文成果',encoding='utf-8')"]}))
                result = await asyncio.to_thread(self.finished, job)
                self.assertEqual("completed", result["status"])
                descriptor = await invoke(self.request("workspace.export", {"path": "returned.txt"}))
                self.assertEqual("ArtifactDescriptor", descriptor["messageType"])
                self.assertNotIn(str(self.workspace), wire.canonical_bytes(descriptor).decode())
                acknowledgment = await invoke(self.request(messageType="DeviceRequest", method="artifact.readChunk", arguments={"artifactId": descriptor["artifactId"], "contentDigest": descriptor["contentDigest"], "offset": 0, "length": descriptor["size"]}))
                frame = await asyncio.wait_for(chunks.get(), 5)
                self.assertEqual("ready", acknowledgment["status"])
                self.assertEqual(descriptor["transferId"], str(frame.transfer_id))
                self.assertEqual("跨设备中文成果".encode(), frame.data)
                self.assertEqual(descriptor["contentDigest"], hashlib.sha256(frame.data).hexdigest())
                # Same authenticated control+bulk channels, now a NEW explicit
                # owner history policy after the old execution grant expired.
                self.grant.update(authorizationEpoch=2, expiresAt=self.manager.now() + 80,
                                  historyRead={"expiresAt": self.manager.now() + 86400000, "methods": ["artifact.describe", "artifact.readChunk"]})
                self.receiver.trust.replace_grant(self.grant, self.manager.now())
                await asyncio.sleep(.1)
                self.assertLess(self.grant["expiresAt"], self.manager.now())
                before = self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall()
                fresh = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="DeviceRequest", method="artifact.readChunk", requestId="fresh-fixed-history",
                             originDeviceId=self.a.device_id, callerDeviceId=self.a.device_id, targetDeviceId=self.b.device_id,
                             instanceId=self.b.instance_id, grantId=self.grant["grantId"], authorizationEpoch=2,
                             resourceBinding=self.binding, deadline=self.manager.now() + 15000,
                             arguments={"artifactId": descriptor["artifactId"], "contentDigest": descriptor["contentDigest"], "offset": 0, "length": descriptor["size"]})
                self.assertEqual("ready", (await invoke(wire.canonical_bytes(fresh)))["status"])
                historical = await asyncio.wait_for(chunks.get(), 5)
                self.assertEqual(frame.data, historical.data)
                self.assertEqual(frame.transfer_id, historical.transfer_id)
                self.assertEqual(before, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchall())
                self.manager.ledger.execute("UPDATE device_artifact_audiences SET withdrawn=1 WHERE artifact_id=?", (descriptor["artifactId"],)); self.manager.ledger.commit()
                fresh.update(requestId="withdrawn-fixed-history", deadline=self.manager.now() + 15000)
                request_id = fresh["requestId"]; future = asyncio.get_running_loop().create_future(); pending[request_id] = future
                self.assertTrue(clients["control"].send_control(wire.canonical_bytes(fresh)))
                denied = await asyncio.wait_for(future, 5)
                self.assertEqual("permission_denied", denied["error"]["code"])
            finally:
                await asyncio.gather(*(client.close() for client in clients.values()))
                await asyncio.gather(*tasks)
        asyncio.run(exercise())


if __name__ == "__main__":
    unittest.main()
