"""Actual mTLS peer, original budget/SQL/root. Native Windows branch is separate."""
import asyncio
import json
import unittest
from unittest.mock import patch

import test_device_companion as fixture
import device_transport as transport


class NativeWorkspaceLocationTests(unittest.TestCase):
    setUp = fixture.NativeCompanionIntegrationTests.setUp
    tearDown = fixture.NativeCompanionIntegrationTests.tearDown
    request = fixture.NativeCompanionIntegrationTests.request
    receive = fixture.NativeCompanionIntegrationTests.receive
    success = fixture.NativeCompanionIntegrationTests.success

    def allow_location(self):
        self.grant.update(authorizationEpoch=2, toolNames=[*self.grant["toolNames"], "workspace.location"])
        self.receiver.trust.replace_grant(self.grant, self.manager.now())

    def test_only_explicit_current_exact_workspace_grant_returns_native_location_and_spends_once(self):
        discovery = self.receive(self.request(messageType="DeviceRequest", method="device.describe", arguments={}))
        self.assertEqual("DeviceDescription", discovery["result"]["messageType"])
        declarations = discovery["result"]["capabilities"][0]["toolDeclarations"]
        self.assertIn({"toolName": "workspace.location", "effect": "read"}, declarations)
        self.assertNotIn(str(self.workspace), json.dumps(discovery))
        # A new declaration never silently expands an old owner's exact names.
        self.assertEqual("permission_denied", self.receive(self.request("workspace.location"))["error"]["code"])
        self.assertEqual(0, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.allow_location()
        request = self.request("workspace.location", authorizationEpoch=2)
        first = self.receive(request); result = self.success(first)
        self.assertEqual(str(self.workspace), result["rootPath"])
        self.assertEqual(self.binding, result["resourceBinding"])
        self.assertTrue(result["locationOnly"]); self.assertFalse(result["guiDirectorySandbox"])
        self.assertEqual("succeeded", first["result"]["status"])
        self.assertEqual(result, self.success(self.receive(request)))
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
        self.assertFalse(list(self.workspace.iterdir()))

    def test_arbitrary_path_wrong_binding_or_revoked_scope_never_returns_a_location(self):
        self.allow_location()
        for params, updates in (({"path": ".."}, {}), ({"root": str(self.root)}, {}),
                                ({}, {"resourceBinding": {**self.binding, "binding": "0" * 64}})):
            value = self.receive(self.request("workspace.location", params, authorizationEpoch=2, **updates))
            self.assertEqual("DeviceError", value["messageType"])
            self.assertNotIn("rootPath", value)
            self.assertNotIn(str(self.workspace), json.dumps(value))
        self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
        value = self.receive(self.request("workspace.location", authorizationEpoch=2))
        self.assertEqual("permission_denied", value["error"]["code"])
        self.assertNotIn(str(self.workspace), json.dumps(value))

    def test_actual_root_replacement_between_admission_and_native_location_refuses_without_guessing(self):
        self.allow_location(); original = self.manager.platform.workspace_location
        def replaced(root, *, current):
            self.workspace.rename(self.root / "original-approved-root")
            self.workspace.mkdir()
            return original(root, current=current)
        with patch.object(self.manager.platform, "workspace_location", side_effect=replaced):
            value = self.receive(self.request("workspace.location", authorizationEpoch=2))
        self.assertEqual("permission_denied", value["error"]["code"])
        self.assertNotIn("rootPath", value); self.assertNotIn(str(self.workspace), json.dumps(value))
        # Original read admission/spend remains auditable; never reset an
        # allocation or manufacture a new path after native identity changed.
        self.assertEqual(1, self.manager.ledger.execute("SELECT COUNT(*) FROM operations").fetchone()[0])
        self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])

    def test_actual_same_mtls_connection_does_not_gain_location_until_exact_owner_tool_grant(self):
        self.service.start()
        port = next(item["port"] for item in self.service.public_status()["lanes"] if item["lane"] == "control")
        async def exercise():
            pending, responses = {}, []
            online, closed = asyncio.Event(), asyncio.Event()
            async def bound(connection, peer): online.set()
            async def frame(peer, value):
                response = json.loads(value.control); responses.append(response)
                future = pending.pop(response["requestId"], None)
                if future is not None: future.set_result(response)
            async def ended(unknown, error): closed.set()
            connection = await transport.open_lan("127.0.0.1", port,
                context=transport.native_device_context(self.a, [self.b.certificate_der], server_side=False),
                expected_certificate_der=self.b.certificate_der, lane="control", on_frame=frame, on_connection=bound, on_close=ended)
            pump = asyncio.create_task(connection.run())
            async def send(raw):
                request_id = json.loads(raw)["requestId"]; future = asyncio.get_running_loop().create_future(); pending[request_id] = future
                self.assertTrue(connection.send_control(raw)); return await asyncio.wait_for(future, 3)
            try:
                await asyncio.wait_for(online.wait(), 3)
                denied = await send(self.request("workspace.location"))
                self.assertEqual("permission_denied", denied["error"]["code"])
                self.assertNotIn(str(self.workspace), json.dumps(denied))
                self.allow_location()
                result = self.success(await send(self.request("workspace.location", authorizationEpoch=2)))
                self.assertEqual(str(self.workspace), result["rootPath"])
                self.assertFalse(result["guiDirectorySandbox"])
                wrong = await send(self.request("workspace.location", authorizationEpoch=2, resourceBinding={**self.binding, "binding": "0" * 64}))
                self.assertEqual("permission_denied", wrong["error"]["code"])
                count = len(responses)
                self.receiver.pairing.revoke_locally(self.a.device_id, self.manager.now())
                self.assertTrue(connection.send_control(self.request("workspace.location", authorizationEpoch=2)))
                await asyncio.wait_for(closed.wait(), 3)
                self.assertEqual(count, len(responses))  # Exact native peer revoked: close, no path response.
                self.assertEqual(1, self.manager.ledger.execute("SELECT tool_spent FROM device_budget_pools").fetchone()[0])
            finally:
                await connection.close(); await pump
        asyncio.run(exercise())


if __name__ == "__main__": unittest.main()
