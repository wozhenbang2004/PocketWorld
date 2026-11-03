"""Response-correlation counterexamples on the original paired-router fixture.

The shared fixture supplies synthetic mTLS identities, signed native grants and
the existing SQL ledger. It is composed, not inherited, so its own tests are not
silently run or counted a second time here.
"""
import copy
import io
import json
import unittest

import device_pairing as pairing
import device_protocol as wire
import test_phone_tasks as fixtures
from phone_task_bridge import PhoneTaskError
from phone_task_mcp import VERSION, stdio


class PhoneTaskReceiptTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.case = fixtures.PhoneDispatchTests()
        await self.case.asyncSetUp()
        await self.case.router.execute("run", self.case.params)

    async def asyncTearDown(self):
        await self.case.asyncTearDown()

    def original(self, operation_id=None):
        return {"operationId": operation_id or self.case.params["operationId"],
                "peerDeviceId": self.case.params["peerDeviceId"]}

    async def lose_control_ack(self, action, operation_id):
        """The synthetic phone commits its reply but the exact link loses it."""
        case = self.case
        case.response_hook = None
        case.waiting_question = action == "answer"
        params = case.input_params(operation_id)
        method = {"acquire_control": "task.acquireControl", "answer": "task.answerQuestion",
                  "steer": "task.steer", "resume": "task.requestResume", "cancel": "operation.cancel"}[action]
        if action == "answer":
            params.update(originalRequestId=case.question_id, waitRevision=4)
        elif action == "resume":
            params.pop("text")
            params["expectedRevision"] = 2
        elif action == "acquire_control":
            params.pop("text")
            params.pop("controlLeaseId")
        elif action == "cancel":
            params = {**self.original(), "cancelOperationId": operation_id}
        original_receive, saved = case.router.receive, {}

        def disconnected_reply(connection, peer, raw):
            response = wire.parse_json_object(raw)
            if response.get("method") == method:
                saved.update(copy.deepcopy(response["result"]))
                case.router.connection_closed(connection)
                return True
            return original_receive(connection, peer, raw)

        case.router.receive = disconnected_reply
        try:
            result = await case.router.execute(action, params)
        finally:
            case.router.receive = original_receive
        self.assertEqual("unknown", result["status"])
        self.assertEqual(operation_id, result["operationId"])
        self.assertTrue(saved)
        # New authenticated connection identity; the old callback cannot settle it.
        class ReconnectedLink:
            lane, closed, state = "control", False, "online"
        case.connection = ReconnectedLink()
        case.connection.send_control = case.send
        case.router.connection_opened(case.connection, case.fixture.peer_b)
        return params, saved

    def nested_receipt_hook(self, saved, mutate=None):
        def reply(request, response):
            if request.method == "operation.get" and request.arguments["operationId"] == saved["operationId"]:
                response["result"] = copy.deepcopy(saved)
                # A typed cancellation always retains its management-only body;
                # other native control snapshots nest their committed receipt.
                if "cancellation" not in saved:
                    response["result"]["payload"] = {"operationState": "receipt", "receipt": copy.deepcopy(saved["payload"])}
                if mutate is not None:
                    mutate(response["result"])
        return reply

    async def test_original_receipt_remains_readable_under_new_explicit_history_epoch(self):
        case, permissions = self.case, []
        now = case.manager.now()
        grant = case.fixture.trust.grant("grant-1")
        grant["authorizationEpoch"] = 2
        grant["historyRead"] = {"expiresAt": now + 60000, "methods": ["operation.get", "task.get"]}
        case.fixture.trust.replace_grant(grant, now)
        signed = case.fixture.coordinator.issue_grant_receipt(case.fixture.b, "grant-1", now)
        case.service.receiver.pairing.accept_outgoing_grant(case.fixture.peer_b, signed, now)

        def verify_history(request, response):
            if request.method != "operation.get":
                return
            audience = copy.deepcopy(response["result"]["audience"])
            class OriginalAcceptedSource(pairing.NativeHistoryAudienceSource):
                def accepted_audience(self, query):
                    return copy.deepcopy(audience)
            permissions.append(case.fixture.coordinator.check_history_permission(
                case.fixture.peer_a, request, OriginalAcceptedSource(), case.manager.now()))

        case.response_hook = verify_history
        result = await case.router.execute("status", self.original())
        self.assertNotIn("currentRead", result)
        self.assertEqual("actual-phone-task", result["task"]["taskId"])
        self.assertEqual(1, len(permissions))
        self.assertEqual(2, permissions[0].authorization_epoch)
        self.assertEqual(1, permissions[0].accepted_authorization_epoch)
        self.assertEqual(1, case.router._row(self.original()["operationId"])[1]["acceptedAudience"]["authorizationEpoch"])
        self.assertEqual(1, sum(item.method == "task.submitGoal" for item in case.sent))

    async def test_history_cannot_rebind_original_root_local_task_run_or_epoch(self):
        case = self.case
        before = copy.deepcopy(case.router._row(self.original()["operationId"])[1])
        changes = [lambda receipt: receipt["audience"].update(rootTaskId="different-root"),
                   lambda receipt: receipt["audience"].update(localTaskId="different-origin-local-task"),
                   lambda receipt: receipt.update(localTaskId="different-receiver-task"),
                   lambda receipt: (receipt.update(runId="different-run"), receipt["audience"].update(runId="different-run")),
                   lambda receipt: (receipt.update(executorEpoch=3), receipt["audience"].update(executorEpoch=3))]
        for index, change in enumerate(changes):
            with self.subTest(scope_field=index):
                def changed(request, response):
                    if request.method == "operation.get":
                        change(response["result"])
                case.response_hook = changed
                result = await case.router.execute("status", self.original())
                self.assertEqual("unavailable", result["currentRead"])
                self.assertEqual("actual-phone-task", result["taskId"])
                self.assertEqual(before, case.router._row(self.original()["operationId"])[1])
                self.assertNotIn("task", result)

    async def test_event_page_cannot_include_another_task_on_the_same_phone(self):
        case = self.case
        selected = {"taskId": "different-task-on-same-phone"}

        def events(raw, *, before_write=None):
            if before_write is not None:
                before_write(case.fixture.peer_b)
            request = wire.parse_request(raw)
            case.sent.append(request)
            event = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="TaskEvent",
                         taskId=selected["taskId"], executorDeviceId=case.fixture.b.device_id, instanceId=case.fixture.b.instance_id,
                         sequence=1, cursor="1", kind="changed", createdAt=case.manager.now(), data={})
            response = dict(protocolVersion=wire.VERSION, protocolProfile=wire.PROFILE, messageType="DeviceResponse",
                            requestId=request.get("requestId"), method=request.method, executorDeviceId=case.fixture.b.device_id,
                            instanceId=case.fixture.b.instance_id, result={"events": [event], "nextCursor": "1", "hasMore": False, "snapshotRequired": False})
            case.router.receive(case.connection, case.fixture.peer_b, wire.canonical_bytes(response))
            return True

        case.connection.send_control = events
        with self.assertRaises(PhoneTaskError):
            await case.router.execute("events", self.original())
        self.assertEqual({}, case.router.pending)
        selected["taskId"] = "actual-phone-task"
        result = await case.router.execute("events", self.original())
        self.assertEqual(["actual-phone-task"], [event["taskId"] for event in result["events"]])

    async def test_cancel_reply_cannot_change_target_or_digest_and_is_not_replayed(self):
        case = self.case
        for key, value in (("targetOperationId", "different-original-target"), ("targetArgumentsDigest", "a" * 64)):
            with self.subTest(target_field=key):
                def changed(request, response):
                    if request.method == "operation.cancel":
                        response["result"]["cancellation"][key] = value
                case.response_hook = changed
                args = {**self.original(), "cancelOperationId": "cancel-" + key}
                result = await case.router.execute("cancel", args)
                self.assertEqual("unknown", result["status"])
                self.assertNotIn("cancellation", result)
                count = len(case.sent)
                self.assertEqual(result, await case.router.execute("cancel", args))
                self.assertEqual(count, len(case.sent))

    async def test_unknown_control_reads_its_nested_original_receipt_without_replay(self):
        case = self.case
        for action in ("acquire_control", "answer", "steer", "resume"):
            with self.subTest(action=action):
                operation_id = "lost-" + action
                params, saved = await self.lose_control_ack(action, operation_id)
                case.response_hook = self.nested_receipt_hook(saved)
                result = await case.router.execute("status", self.original(operation_id))
                self.assertNotIn("currentRead", result)
                self.assertEqual("actual-phone-task", result["taskId"])
                if action == "acquire_control":
                    self.assertEqual("native-control-lease", result["controlLeaseId"])
                elif action == "resume":
                    self.assertIs(True, result["ownerActionRequired"])
                    self.assertEqual(operation_id, result["resumeRequestId"])
                    self.assertEqual(2, result["requestedRevision"])
                else:
                    self.assertIs(True, result["inputSaved"])
                    self.assertEqual("native-message-" + operation_id, result["inputMessageId"])
                count = len(case.sent)
                duplicate = await case.router.execute(action, params)
                self.assertEqual(result["operationId"], duplicate["operationId"])
                self.assertEqual(count, len(case.sent))
                scope = case.router._row(operation_id)[1]["controlScope"]
                self.assertEqual({"taskId", "runId", "executorEpoch", "allocationId"} |
                                 ({"expectedRevision"} if action == "resume" else set()), set(scope))
        self.assertEqual(1, sum(item.method == "task.submitGoal" for item in case.sent))
        rows = " ".join(row[0] for row in case.db.execute("SELECT details FROM operations"))
        self.assertNotIn("native ordinary answer", rows)

    async def test_nested_recovery_cannot_change_input_scope_resume_approval_or_cancel_target(self):
        case = self.case
        changes = [("answer", "wrong-run", lambda receipt: receipt["payload"]["receipt"].update(runId="unrelated-run")),
                   ("answer", "wrong-allocation", lambda receipt: receipt["payload"]["receipt"].update(allocationId="unrelated-allocation")),
                   ("answer", "wrong-source", lambda receipt: receipt["payload"]["receipt"].update(sourceKind="private_owner_input")),
                   ("answer", "claimed-source", lambda receipt: receipt["payload"]["receipt"].update(formalSourceRefAvailable=True)),
                   ("resume", "bypass-owner", lambda receipt: receipt["payload"]["receipt"].update(ownerActionRequired=False)),
                   ("resume", "claim-execution", lambda receipt: receipt["payload"]["receipt"].update(executionAuthorized=True)),
                   ("resume", "wrong-request", lambda receipt: receipt["payload"]["receipt"].update(resumeRequestId="another-request")),
                   ("resume", "wrong-revision", lambda receipt: receipt["payload"]["receipt"].update(requestedRevision=1)),
                   ("resume", "wrong-review", lambda receipt: receipt["payload"]["receipt"].update(reviewRevision=4)),
                   ("resume", "new-grant", lambda receipt: receipt["payload"]["receipt"].update(newGrant=True)),
                   ("acquire_control", "moved-execution", lambda receipt: receipt["payload"]["receipt"].update(executionMoved=True)),
                   ("cancel", "wrong-target", lambda receipt: receipt["cancellation"].update(targetOperationId="another-operation")),
                   ("cancel", "wrong-digest", lambda receipt: receipt["cancellation"].update(targetArgumentsDigest="b" * 64))]
        for action, label, change in changes:
            with self.subTest(action=action, changed=label):
                operation_id = "unknown-" + label
                _, saved = await self.lose_control_ack(action, operation_id)
                before = copy.deepcopy(case.router._row(operation_id)[1])
                case.response_hook = self.nested_receipt_hook(saved, change)
                result = await case.router.execute("status", self.original(operation_id))
                self.assertEqual("unavailable", result.get("currentRead"))
                self.assertEqual("unknown", result["status"])
                self.assertNotIn("inputSaved", result)
                self.assertNotIn("ownerActionRequired", result)
                self.assertNotIn("cancellation", result)
                self.assertEqual(before, case.router._row(operation_id)[1])


class McpInitializationTests(unittest.TestCase):
    def test_malformed_initialized_notification_cannot_unlock_tools(self):
        malformed = [{"jsonrpc": "wrong", "method": "notifications/initialized"},
                     {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {"unexpected": True}},
                     {"jsonrpc": "2.0", "method": "notifications/initialized", "extra": True},
                     {"jsonrpc": "2.0", "method": "notifications/initialized", "id": 1}]
        for notification in malformed:
            with self.subTest(notification=notification):
                calls = []
                class SelectedClient:
                    def execute(self, action, params):
                        calls.append((action, copy.deepcopy(params)))
                        return {"operations": []}
                args = {"peerDeviceId": "pw1_" + "3" * 64}
                tool = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "phone_task_list", "arguments": args}}
                messages = [{"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {"protocolVersion": VERSION, "capabilities": {}, "clientInfo": {}}},
                            notification, tool, {"jsonrpc": "2.0", "method": "notifications/initialized"}, {**tool, "id": 3}]
                output = io.BytesIO()
                stdio(SelectedClient(), io.BytesIO(("\n".join(json.dumps(item) for item in messages) + "\n").encode()), output)
                replies = {item["id"]: item for item in map(json.loads, output.getvalue().splitlines())}
                self.assertEqual(-32000, replies[2]["error"]["code"])
                self.assertIn("result", replies[3])
                self.assertEqual([("list", args)], calls)


if __name__ == "__main__":
    unittest.main()
