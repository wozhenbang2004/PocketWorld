"""Bounded MCP 2025-11-25 tools, adapting the same task Client as CLI/Desk.

No model sampling, approval tool, device-token export or independent execution.
An MCP connection closing stops local reads only; phone cancellation is explicit.
"""
import json

VERSION = "2025-11-25"


def tool_definitions():
    identity = {"operationId": {"type": "string"}, "peerDeviceId": {"type": "string"}}
    control = {**identity, "controlOperationId": {"type": "string"}, "taskId": {"type": "string"}, "runId": {"type": "string"},
               "executorEpoch": {"type": "integer", "minimum": 1}, "allocationId": {"type": "string"}}
    input_fields = {**control, "controlLeaseId": {"type": "string"}, "text": {"type": "string", "minLength": 1, "maxLength": 4000}}
    shapes = {
        "doctor": ({"peerDeviceId": {"type": "string"}}, ["peerDeviceId"], True),
        "list": ({"peerDeviceId": {"type": "string"}}, ["peerDeviceId"], True),
        "run": ({**identity, "grantId": {"type": "string"}, "resourceBinding": {"type": "object"}, "goal": {"type": "string", "minLength": 1, "maxLength": 12000},
                 "toolCalls": {"type": "integer", "minimum": 0, "maximum": 400}, "modelCalls": {"type": "integer", "minimum": 1, "maximum": 1000},
                 "ttlMs": {"type": "integer", "minimum": 1000, "maximum": 86400000}, "capabilityVersion": {"type": "string"}},
                ["operationId", "peerDeviceId", "grantId", "resourceBinding", "goal", "toolCalls", "modelCalls", "ttlMs", "capabilityVersion"], False),
        "status": (identity, list(identity), True),
        "wait": ({**identity, "timeoutMs": {"type": "integer", "minimum": 0, "maximum": 45000}}, list(identity), True),
        "events": ({**identity, "cursor": {"type": "string"}, "limit": {"type": "integer", "minimum": 1, "maximum": 256}}, list(identity), True),
        "cancel": ({**identity, "cancelOperationId": {"type": "string"}}, [*identity, "cancelOperationId"], False),
        "acquire_control": ({**control, "ttlMs": {"type": "integer", "minimum": 1, "maximum": 300000}, "previousLeaseId": {"type": "string"}}, list(control), False),
        "answer": ({**input_fields, "originalRequestId": {"type": "string"}, "waitRevision": {"type": "integer", "minimum": 1}}, [*input_fields, "originalRequestId", "waitRevision"], False),
        "steer": (input_fields, list(input_fields), False),
    }
    descriptions = {
        "doctor": "Read the selected paired phone's bounded native description. Does not verify model inference or authorize execution.",
        "list": "List this owner's original phone dispatch identities, with no phone result cache.",
        "run": "Submit one bounded goal to the existing phone TaskStore. Generate operationId once before this call. Unknown or repeated writes are never replayed; query that same operationId. Native phone confirmation remains on the phone.",
        "status": "Read the same original phone operation/task using current paired history authorization.",
        "wait": "Read the same phone task for up to 45 seconds. Timeout or disconnect does not fail/cancel the task and never starts a replacement.",
        "events": "Read original phone task events with the original event cursor.",
        "cancel": "Request cancellation of the exact original operation using a distinct cancelOperationId. Requested is not proof of stopped. Unknown is never replayed.",
        "acquire_control": "Explicitly acquire control for the exact original native task/run/epoch/allocation using a separate controlOperationId. Existing controller identity is required to renew. Does not approve effects or create a run.",
        "answer": "Save an answer to the exact native ordinary question UUID/waitRevision in its original task. Requires current paired write grant and control lease. Never use for approval/takeover. Retains original run, model, allocation and remaining budget; unknown writes are never replayed.",
        "steer": "Append bounded guidance to the original active phone goal through its existing Runner input polling. Requires exact run/epoch/allocation/current control lease. Does not approve effects, renew grants or start a replacement task.",
    }
    return [{"name": "phone_task_" + action, "description": descriptions[action],
             "inputSchema": {"type": "object", "properties": properties, "required": required, "additionalProperties": False},
             "annotations": {"readOnlyHint": read_only, "destructiveHint": action == "cancel", "idempotentHint": read_only, "openWorldHint": True}}
            for action, (properties, required, read_only) in shapes.items()]


def rpc(client, message, *, fixed_peer=None):
    if (not isinstance(message, dict) or message.get("jsonrpc") != "2.0" or not isinstance(message.get("method"), str)
            or message.keys() - {"jsonrpc", "id", "method", "params"}):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid bounded JSON-RPC request"}}
    request_id = message.get("id")
    if "id" in message and (type(request_id) not in (str, int) or isinstance(request_id, str) and len(request_id) > 128):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request identifier"}}
    method, params = message["method"], message.get("params", {})
    if "id" not in message:
        # A notification never executes a task tool. Transport cancellation is not
        # a request to cancel the already accepted original phone task.
        return None
    try:
        if not isinstance(params, dict):
            raise ValueError("Object parameters required")
        if method == "initialize":
            if not isinstance(params.get("protocolVersion"), str) or not isinstance(params.get("capabilities"), dict) or not isinstance(params.get("clientInfo"), dict):
                raise ValueError("Initialize requires protocol version, capabilities and clientInfo")
            result = {"protocolVersion": VERSION, "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": {"name": "pocketworld-phone-tasks", "version": "1.0.0"},
                      "instructions": "Phone TaskStore is the only executor. Keep original operation/control identities. Native approvals and takeover stay on phone. Answer only a native ordinary question; steer only the original active goal. Read current native task/run/allocation and explicitly acquire its control lease first. Unknown writes are never replayed."}
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            if params:
                raise ValueError("This bounded tool list has no cursor")
            result = {"tools": tool_definitions()}
        elif method == "tools/call":
            if set(params) != {"name", "arguments"} or not isinstance(params["arguments"], dict):
                raise ValueError("Use one named tool with object arguments")
            definition = next((item for item in tool_definitions() if item["name"] == params["name"]), None)
            if definition is None:
                raise ValueError("Unknown task tool; phone approval cannot be supplied as answer text")
            arguments, schema = params["arguments"], definition["inputSchema"]
            if not set(schema["required"]) <= arguments.keys() or arguments.keys() - schema["properties"].keys():
                raise ValueError("Missing or unknown task arguments")
            if fixed_peer is not None and arguments.get("peerDeviceId") != fixed_peer:
                raise ValueError("This local surface remains bound to its selected phone")
            try:
                value = client.execute(params["name"][len("phone_task_"):], arguments)
                result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, separators=(",", ":"))}], "structuredContent": value, "isError": False}
            except Exception:
                # Do not expose local paths, credentials or backend exception bodies.
                result = {"content": [{"type": "text", "text": "Original phone action unavailable; query the same operation identifier. No automatic retry."}], "isError": True}
        else:
            return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": "Method not found"}}
        return {"jsonrpc": "2.0", "id": request_id, "result": result}
    except ValueError as failure:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": str(failure)}}


def stdio(client, source, destination):
    initialized = False
    while True:
        raw = source.readline(65537)
        if not raw:
            return
        if len(raw) > 65536 or not raw.endswith(b"\n"):
            return  # Framing cannot be safely resumed by treating a suffix as another request.
        try:
            message = json.loads(raw)
            if isinstance(message, dict) and message.get("method") == "initialize":
                response = rpc(client, message)
                initialized = response is not None and "result" in response
            elif not initialized:
                response = {"jsonrpc": "2.0", "id": message.get("id") if isinstance(message, dict) else None, "error": {"code": -32000, "message": "Initialize first"}}
            else:
                response = rpc(client, message)
        except (ValueError, UnicodeError):
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}}
        if response is not None:
            destination.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
            destination.flush()
