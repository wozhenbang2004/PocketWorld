"""Bounded dual-era MCP tools, adapting the same task Client as CLI/Desk.

No model sampling, approval tool, device-token export or independent execution.
An MCP connection closing stops local reads only; phone cancellation is explicit.
"""
import base64
import json

import device_protocol as wire

VERSION = "2025-11-25"
MODERN_VERSION = "2026-07-28"
SUPPORTED_VERSIONS = [MODERN_VERSION, VERSION]
META_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
META_CLIENT = "io.modelcontextprotocol/clientInfo"
META_SERVER = "io.modelcontextprotocol/serverInfo"
SERVER_INFO = {"name": "pocketworld-phone-tasks", "version": "1.1.0"}
INSTRUCTIONS = ("Phone TaskStore is the only executor. Keep original operation/control identities. "
                "Native approvals and takeover stay on phone. Answer only a native ordinary question; "
                "steer only the original active goal. Read current native task/run/allocation and explicitly "
                "acquire its control lease first. Unknown writes are never replayed.")


def protocol_support():
    """A local support matrix; discovery does not claim phone connectivity or permission."""
    return {"inboundPhoneTaskServer": {"versions": SUPPORTED_VERSIONS.copy(), "transports": ["stdio", "loopback_streamable_http"],
                                       "capabilities": ["tools"], "cacheScope": "private", "ttlMs": 0},
            "outboundAndroidPluginClient": {"httpsStreamableHttp": {"versions": SUPPORTED_VERSIONS.copy()},
                                             "boundedStdio": {"versions": [VERSION]},
                                             "scope": "Reviewed plugin capabilities only; independent of the phone task server"},
            "executionAuthority": "Existing native phone TaskStore, grants and run control", "executionAuthorized": False}


class RequestError(ValueError):
    def __init__(self, message, code=-32602, data=None):
        super().__init__(message)
        self.code, self.data = code, data


def error_response(request_id, failure):
    error = {"code": failure.code, "message": str(failure)}
    if failure.data is not None:
        error["data"] = failure.data
    return {"jsonrpc": "2.0", "id": request_id, "error": error}


def modern_request(message):
    if not isinstance(message, dict):
        return False
    params = message.get("params")
    meta = params.get("_meta") if isinstance(params, dict) else None
    return message.get("method") == "server/discover" or isinstance(meta, dict) and any(
        key in meta for key in (META_VERSION, META_CAPABILITIES, META_CLIENT))


def checked_parameters(message):
    params = message.get("params", {})
    if not isinstance(params, dict):
        raise RequestError("Object parameters required")
    meta = params.get("_meta", {})
    if not isinstance(meta, dict):
        raise RequestError("Object request metadata required")
    modern = modern_request(message)
    if modern:
        version = meta.get(META_VERSION)
        if not isinstance(version, str) or not isinstance(meta.get(META_CAPABILITIES), dict):
            raise RequestError("Every modern request requires protocolVersion and object clientCapabilities metadata")
        if version != MODERN_VERSION:
            raise RequestError("Unsupported protocol version", -32022, {"supported": SUPPORTED_VERSIONS.copy(), "requested": version})
        if META_CLIENT in meta:
            client = meta[META_CLIENT]
            if not isinstance(client, dict) or not isinstance(client.get("name"), str) or not isinstance(client.get("version"), str):
                raise RequestError("Client identity must have a string name and version")
    return modern, {key: value for key, value in params.items() if key != "_meta"}


def modern_http_error(message, headers):
    """Validate mirrored headers before dispatching any original phone action."""
    request_id = message.get("id")
    if (message.get("jsonrpc") != "2.0" or not isinstance(message.get("method"), str)
            or message.keys() - {"jsonrpc", "id", "method", "params"}
            or "id" in message and (type(request_id) not in (str, int) or isinstance(request_id, str) and len(request_id) > 128)):
        return error_response(None, RequestError("Invalid bounded JSON-RPC request", -32600))
    try:
        modern, params = checked_parameters(message)
        if not modern:
            raise RequestError("Modern HTTP requests require per-request metadata")
        required = {"MCP-Protocol-Version": message["params"]["_meta"][META_VERSION], "Mcp-Method": message.get("method")}
        if message.get("method") in {"tools/call", "prompts/get", "resources/read"}:
            required["Mcp-Name"] = params.get("uri" if message["method"] == "resources/read" else "name")
        for name, expected in required.items():
            values = headers.get_all(name, [])
            if len(values) != 1 or not isinstance(expected, str):
                raise RequestError("Missing or malformed mirrored MCP header", -32020)
            value = values[0]
            if not value or value != value.strip() or any(ord(char) < 32 and char != "\t" or ord(char) > 126 for char in value):
                raise RequestError("Malformed mirrored MCP header", -32020)
            if name == "Mcp-Name" and value.startswith("=?base64?") and value.endswith("?="):
                try:
                    value = base64.b64decode(value[9:-2], validate=True).decode("utf-8")
                except (ValueError, UnicodeError):
                    raise RequestError("Malformed encoded MCP name", -32020) from None
            if value != expected:
                raise RequestError("MCP header does not match the request body", -32020)
        return None
    except RequestError as failure:
        return error_response(request_id, failure)


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
        "events": ({**identity, "cursor": {"type": "string"}, "limit": {"type": "integer", "minimum": 1, "maximum": 32}}, list(identity), True),
        "cancel": ({**identity, "cancelOperationId": {"type": "string"}}, [*identity, "cancelOperationId"], False),
        "acquire_control": ({**control, "ttlMs": {"type": "integer", "minimum": 1, "maximum": 300000}, "previousLeaseId": {"type": "string"}}, list(control), False),
        "answer": ({**input_fields, "originalRequestId": {"type": "string"}, "waitRevision": {"type": "integer", "minimum": 1}}, [*input_fields, "originalRequestId", "waitRevision"], False),
        "steer": (input_fields, list(input_fields), False),
        "resume": ({**control, "controlLeaseId": {"type": "string"}, "expectedRevision": {"type": "integer", "minimum": 1}},
                   [*control, "controlLeaseId", "expectedRevision"], False),
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
        "resume": "Request phone-owner review of resuming the same paused task. Acquire control, read the current revision again, then pass that exact revision. This request never authorizes execution or creates a new run/grant. The owner must confirm in the original phone UI. Unknown is queried by the same controlOperationId, never replayed.",
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
        modern, params = checked_parameters(message)
        if method == "server/discover":
            if params:
                raise ValueError("Discovery accepts only request metadata")
            result = {"supportedVersions": SUPPORTED_VERSIONS.copy(), "capabilities": {"tools": {"listChanged": False}},
                      "instructions": INSTRUCTIONS, "ttlMs": 0, "cacheScope": "private"}
        elif method == "initialize" and not modern:
            if not isinstance(params.get("protocolVersion"), str) or not isinstance(params.get("capabilities"), dict) or not isinstance(params.get("clientInfo"), dict):
                raise ValueError("Initialize requires protocol version, capabilities and clientInfo")
            result = {"protocolVersion": VERSION, "capabilities": {"tools": {"listChanged": False}},
                      "serverInfo": SERVER_INFO.copy(), "instructions": INSTRUCTIONS}
        elif method == "ping" and not modern:
            result = {}
        elif method == "tools/list":
            if params:
                raise ValueError("This bounded tool list has no cursor")
            result = {"tools": tool_definitions()}
            if modern:
                result.update(ttlMs=0, cacheScope="private")
        elif method == "tools/call":
            if set(params) != {"name", "arguments"} or not isinstance(params["arguments"], dict):
                raise ValueError("Use one named tool with object arguments")
            definition = next((item for item in tool_definitions() if item["name"] == params["name"]), None)
            if definition is None:
                raise ValueError("Unknown task tool; phone approval cannot be supplied as answer text")
            arguments, schema = params["arguments"], definition["inputSchema"]
            if not set(schema["required"]) <= arguments.keys() or arguments.keys() - schema["properties"].keys():
                raise ValueError("Missing or unknown task arguments")
            for name, value in arguments.items():
                expected = schema["properties"][name]
                kind = expected["type"]
                if ((kind == "string" and not isinstance(value, str)) or (kind == "integer" and type(value) is not int)
                        or (kind == "object" and not isinstance(value, dict))):
                    raise ValueError("Task argument type differs from the published schema")
                if kind == "integer" and not expected.get("minimum", -wire.MAX_SAFE_INTEGER) <= value <= expected.get("maximum", wire.MAX_SAFE_INTEGER):
                    raise ValueError("Task argument is outside the published bounds")
                if kind == "string" and not expected.get("minLength", 0) <= len(value) <= expected.get("maxLength", 4096):
                    raise ValueError("Task text is outside the published bounds")
            if fixed_peer is not None and arguments.get("peerDeviceId") != fixed_peer:
                raise ValueError("This local surface remains bound to its selected phone")
            try:
                value = client.execute(params["name"][len("phone_task_"):], arguments)
                result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False, separators=(",", ":"))}], "structuredContent": value,
                          "isError": value.get("status") in {"unknown", "rejected"} or value.get("currentRead") == "unavailable"}
            except Exception:
                # Do not expose local paths, credentials or backend exception bodies.
                result = {"content": [{"type": "text", "text": "Original phone action unavailable; query the same operation identifier. No automatic retry."}], "isError": True}
        else:
            return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32601, "message": "Method not found"}}
        if modern:
            result.update(resultType="complete", _meta={META_SERVER: SERVER_INFO.copy()})
        return {"jsonrpc": "2.0", "id": request_id, "result": result}
    except RequestError as failure:
        return error_response(request_id, failure)
    except ValueError as failure:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32602, "message": str(failure)}}


def stdio(client, source, destination):
    state = "new"
    while True:
        raw = source.readline(65537)
        if not raw:
            return
        if len(raw) > 65536 or not raw.endswith(b"\n"):
            return  # Framing cannot be safely resumed by treating a suffix as another request.
        try:
            message = wire.parse_json_object(raw)
            if modern_request(message):
                # Modern requests are independent; they neither inherit nor mutate a
                # legacy initialize session and must carry metadata on every request.
                response = rpc(client, message)
            elif message.get("method") == "initialize" and state == "new":
                response = rpc(client, message)
                if response is not None and "result" in response:
                    state = "negotiated"
            elif (message.get("method") == "notifications/initialized" and state == "negotiated"
                    and message.get("jsonrpc") == "2.0" and not message.keys() - {"jsonrpc", "method", "params"}
                    and message.get("params", {}) == {}):
                state, response = "ready", None
            elif state != "ready" and message.get("method") != "ping":
                response = {"jsonrpc": "2.0", "id": message.get("id") if isinstance(message, dict) else None, "error": {"code": -32000, "message": "Initialize first"}}
            elif message.get("method") == "initialize":
                response = {"jsonrpc": "2.0", "id": message.get("id"), "error": {"code": -32600, "message": "This session is already initialized"}}
            else:
                response = rpc(client, message)
            if "id" not in message:
                response = None
        except (ValueError, UnicodeError, wire.ProtocolError):
            response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON"}}
        if response is not None:
            destination.write(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
            destination.flush()
