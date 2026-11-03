"""Real MCP/loopback adapters with a controlled original-client boundary; no phone/model claim."""
import base64
import contextlib
import copy
import http.client
import io
import json
import unittest

from phone_task_desk import TaskDesk
from phone_task_mcp import (VERSION, MODERN_VERSION, META_VERSION, META_CAPABILITIES,
                            META_CLIENT, META_SERVER, rpc, stdio)
from phone_tasks import main


def modern(method, params=None, identifier=1):
    return {"jsonrpc": "2.0", "id": identifier, "method": method, "params": {
        **(params or {}), "_meta": {META_VERSION: MODERN_VERSION, META_CAPABILITIES: {},
                                  META_CLIENT: {"name": "local-protocol-fixture", "version": "1"}}}}


class OriginalClient:
    def __init__(self):
        self.calls = []
        self.fail = False

    def execute(self, action, params):
        self.calls.append((action, copy.deepcopy(params)))
        if self.fail:
            raise OSError("private backend path and credential must not be published")
        return {"status": "running", "operationId": params.get("operationId")}


class ProtocolVersionsTest(unittest.TestCase):
    def setUp(self):
        self.client = OriginalClient()
        self.peer = "pw1_" + "a" * 64

    def test_discovery_and_list_have_private_cache_contract_without_execution(self):
        for method in ("server/discover", "tools/list"):
            result = rpc(self.client, modern(method))["result"]
            self.assertEqual("complete", result["resultType"])
            self.assertEqual(0, result["ttlMs"])
            self.assertEqual("private", result["cacheScope"])
            self.assertEqual("pocketworld-phone-tasks", result["_meta"][META_SERVER]["name"])
        self.assertEqual([MODERN_VERSION, VERSION], rpc(self.client, modern("server/discover"))["result"]["supportedVersions"])
        self.assertEqual([], self.client.calls)

    def test_metadata_is_required_on_every_modern_request_and_unknown_versions_fail_before_dispatch(self):
        call = modern("tools/call", {"name": "phone_task_status", "arguments": {"peerDeviceId": self.peer, "operationId": "original"}})
        for field in (META_VERSION, META_CAPABILITIES):
            wrong = copy.deepcopy(call); del wrong["params"]["_meta"][field]
            self.assertEqual(-32602, rpc(self.client, wrong)["error"]["code"])
        wrong = copy.deepcopy(call); wrong["params"]["_meta"][META_VERSION] = "2099-01-01"
        error = rpc(self.client, wrong)["error"]
        self.assertEqual(-32022, error["code"])
        self.assertEqual([MODERN_VERSION, VERSION], error["data"]["supported"])
        self.assertEqual([], self.client.calls)

    def test_stdio_can_serve_modern_requests_and_preserves_legacy_acknowledgement_gate(self):
        call = modern("tools/call", {"name": "phone_task_status", "arguments": {"peerDeviceId": self.peer, "operationId": "original"}}, 3)
        legacy_call = copy.deepcopy(call); del legacy_call["params"]["_meta"]; legacy_call["id"] = 4
        messages = [modern("server/discover", identifier=1), modern("tools/list", identifier=2), call, legacy_call,
                    {"jsonrpc": "2.0", "id": 5, "method": "initialize", "params": {"protocolVersion": VERSION, "capabilities": {}, "clientInfo": {}}},
                    {"jsonrpc": "2.0", "method": "notifications/initialized"}, {**legacy_call, "id": 6}]
        output = io.BytesIO()
        stdio(self.client, io.BytesIO(("\n".join(json.dumps(item) for item in messages) + "\n").encode()), output)
        replies = {value["id"]: value for value in map(json.loads, output.getvalue().splitlines())}
        self.assertEqual("complete", replies[3]["result"]["resultType"])
        self.assertEqual(-32000, replies[4]["error"]["code"])
        self.assertEqual(VERSION, replies[5]["result"]["protocolVersion"])
        self.assertNotIn("resultType", replies[6]["result"])
        self.assertEqual([("status", {"peerDeviceId": self.peer, "operationId": "original"})] * 2, self.client.calls)

    def test_modern_calls_keep_peer_schema_and_unknown_write_boundaries(self):
        args = {"operationId": "original", "peerDeviceId": self.peer, "cancelOperationId": "cancel-once"}
        call = modern("tools/call", {"name": "phone_task_cancel", "arguments": args})
        self.assertIn("error", rpc(self.client, call, fixed_peer="pw1_" + "b" * 64))
        wrong = copy.deepcopy(call); wrong["params"]["arguments"]["approval"] = True
        self.assertIn("error", rpc(self.client, wrong))
        self.assertEqual([], self.client.calls)
        self.client.fail = True
        response = rpc(self.client, call, fixed_peer=self.peer)
        self.assertTrue(response["result"]["isError"])
        self.assertEqual("complete", response["result"]["resultType"])
        self.assertNotIn("private backend", json.dumps(response))
        self.assertEqual([("cancel", args)], self.client.calls)

    def test_modern_discovery_cursor_and_removed_legacy_method_are_not_invented(self):
        self.assertEqual(-32602, rpc(self.client, modern("tools/list", {"cursor": "pretend"}))["error"]["code"])
        self.assertEqual(-32602, rpc(self.client, modern("server/discover", {"unexpected": True}))["error"]["code"])
        self.assertEqual(-32601, rpc(self.client, modern("ping"))["error"]["code"])
        self.assertEqual([], self.client.calls)

    def test_cli_matrix_does_not_require_or_create_a_device_connection(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, main(["mcp-protocols"]))
        value = json.loads(output.getvalue())
        self.assertEqual([MODERN_VERSION, VERSION], value["inboundPhoneTaskServer"]["versions"])
        self.assertIn("outboundAndroidPluginClient", value)
        self.assertEqual([VERSION], value["outboundAndroidPluginClient"]["boundedStdio"]["versions"])
        self.assertFalse(value["executionAuthorized"])


class ModernHttpTest(unittest.TestCase):
    def setUp(self):
        self.client = OriginalClient()
        self.peer = "pw1_" + "a" * 64
        self.desk = TaskDesk(self.client, self.peer)
        self.desk.start()

    def tearDown(self):
        self.desk.close()

    def request(self, message, overrides=None):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "Authorization": "Bearer " + self.desk.session_token, "MCP-Protocol-Version": MODERN_VERSION,
                   "Mcp-Method": message["method"]}
        if message["method"] == "tools/call":
            headers["Mcp-Name"] = message["params"]["name"]
        headers.update(overrides or {})
        headers = {key: value for key, value in headers.items() if value is not None}
        connection = http.client.HTTPConnection(*self.desk.server_address, timeout=3)
        try:
            connection.request("POST", "/mcp", json.dumps(message), headers)
            response = connection.getresponse(); raw = response.read()
            return response.status, json.loads(raw) if raw else None
        finally:
            connection.close()

    def test_http_modern_discovery_list_and_call_need_no_legacy_session(self):
        self.assertEqual(200, self.request(modern("server/discover"))[0])
        self.assertEqual(200, self.request(modern("tools/list"))[0])
        args = {"peerDeviceId": self.peer, "operationId": "original"}
        call = modern("tools/call", {"name": "phone_task_status", "arguments": args})
        encoded = "=?base64?" + base64.b64encode(b"phone_task_status").decode() + "?="
        status, response = self.request(call, {"Mcp-Name": encoded})
        self.assertEqual(200, status)
        self.assertEqual("complete", response["result"]["resultType"])
        self.assertEqual([("status", args)], self.client.calls)
        self.assertFalse(self.desk.mcp_initialized)
        self.assertFalse(self.desk.mcp_ready)

    def test_http_headers_auth_and_unknown_methods_fail_without_phone_actions(self):
        call = modern("tools/call", {"name": "phone_task_status", "arguments": {"peerDeviceId": self.peer, "operationId": "original"}})
        for headers in ({"Mcp-Method": None}, {"Mcp-Method": "tools/list"}, {"Mcp-Name": None},
                        {"Mcp-Name": "phone_task_run"}, {"Mcp-Name": "=?base64?!!?="}, {"MCP-Protocol-Version": VERSION}):
            status, response = self.request(call, headers)
            self.assertEqual(400, status, headers)
            self.assertEqual(-32020, response["error"]["code"], headers)
        self.assertEqual(403, self.request(call, {"Authorization": "Bearer incorrect"})[0])
        status, response = self.request(modern("unimplemented/operation"))
        self.assertEqual(404, status); self.assertEqual(-32601, response["error"]["code"])
        wrong = modern("tools/list"); wrong["params"]["_meta"][META_VERSION] = "2099-01-01"
        status, response = self.request(wrong, {"MCP-Protocol-Version": "2099-01-01"})
        self.assertEqual(400, status); self.assertEqual(-32022, response["error"]["code"])
        self.assertEqual([], self.client.calls)

    def test_duplicate_protocol_headers_are_a_modern_header_error_not_an_executable_request(self):
        raw = json.dumps(modern("tools/list")).encode()
        connection = http.client.HTTPConnection(*self.desk.server_address, timeout=3)
        try:
            connection.putrequest("POST", "/mcp")
            for name, value in [("Content-Type", "application/json"), ("Content-Length", str(len(raw))),
                                ("Accept", "application/json, text/event-stream"), ("Authorization", "Bearer " + self.desk.session_token),
                                ("MCP-Protocol-Version", MODERN_VERSION), ("MCP-Protocol-Version", MODERN_VERSION),
                                ("Mcp-Method", "tools/list")]:
                connection.putheader(name, value)
            connection.endheaders(raw)
            response = connection.getresponse(); body = json.loads(response.read())
            self.assertEqual(400, response.status); self.assertEqual(-32020, body["error"]["code"])
            self.assertEqual([], self.client.calls)
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
