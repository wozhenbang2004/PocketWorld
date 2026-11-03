"""PocketWorld device v1 wire contract. Parsing never creates execution authority.

The operation digest binds the complete semantic request, not its transport requestId.
No automatic retry, task database or model loop belongs in this module.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import re

VERSION = "1.0"
PROFILE = "pwp1_953641fabc7e4316996f69c2c3ac9b74db1a30f3750ee83adbfd95768730ae8b"
MAX_CONTROL_BYTES = 65_536
MAX_PAIRING_BYTES = 16_384
MAX_DEPTH = 16
MAX_NODES = 4096
MAX_SAFE_INTEGER = 9_007_199_254_740_991
DEVICE_ID = re.compile(r"pw1_[a-f0-9]{64}\Z")
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}\Z")
DIGEST = re.compile(r"[a-f0-9]{64}\Z")
TASK_STATES = frozenset("queued_local submitting accepted waiting_approval waiting_dependency waiting_resource running pause_requested paused cancel_requested cancelled succeeded failed interrupted needs_reconcile".split())
TRANSPORT_STATES = frozenset("connecting online degraded offline revoked incompatible".split())
METHODS = {
    "device.describe": ("DeviceRequest", "read"),
    "tool.invoke": ("ToolInvocation", "tool"),
    "operation.get": ("DeviceRequest", "read"),
    "operation.cancel": ("DeviceRequest", "write"),
    "task.submitGoal": ("GoalSubmission", "write"),
    "task.get": ("DeviceRequest", "read"),
    "task.events": ("DeviceRequest", "read"),
    "task.respond": ("DeviceRequest", "write"),
    "task.pause": ("DeviceRequest", "write"),
    "task.cancel": ("DeviceRequest", "write"),
    "task.acquireControl": ("DeviceRequest", "write"),
    "task.answerQuestion": ("DeviceRequest", "write"),
    "task.steer": ("DeviceRequest", "write"),
    "task.requestResume": ("DeviceRequest", "write"),
    "artifact.describe": ("DeviceRequest", "read"),
    "artifact.readChunk": ("DeviceRequest", "read"),
}
FIELDS = frozenset("protocolVersion protocolProfile messageType method requestId operationId argumentsDigest rootTaskId parentTaskId localTaskId originDeviceId callerDeviceId targetDeviceId runId attempt executorEpoch instanceId grantId authorizationEpoch delegationChain capabilityVersion resourceBinding observationRef deadline budget visitedDevices maxDelegationDepth dependencySnapshot traceId replyCursor arguments".split())
NON_SEMANTIC = frozenset({"requestId", "operationId", "argumentsDigest", "traceId", "replyCursor"})


class ProtocolError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code

    def response(self, request_id=None):
        # Do not include the rejected body, tokens, paths or model credentials.
        return {"protocolVersion": VERSION, "protocolProfile": PROFILE, "messageType": "DeviceError", "requestId": request_id,
                "error": {"code": self.code, "message": str(self), "retryable": False, "effectState": "not_dispatched"}}


def _fail(code, message):
    raise ProtocolError(code, message)


def require_profile(value):
    if not isinstance(value,dict):_fail("invalid_request","Device message must be an object")
    if "protocolVersion" not in value:_fail("invalid_request","Device protocol version is required")
    if value.get("protocolVersion")!=VERSION:_fail("incompatible_version","Unsupported device protocol version")
    if value.get("protocolProfile")!=PROFILE:_fail("incompatible_profile","Unsupported or missing authoritative device protocol profile")


def _pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            _fail("invalid_json", "Duplicate JSON member")
        value[key] = item
    return value


def _tree(value, depth=0, count=None):
    count = [0] if count is None else count
    count[0] += 1
    if depth > MAX_DEPTH or count[0] > MAX_NODES:
        _fail("limit_exceeded", "JSON structure exceeds device limits")
    if value is None or type(value) is bool:
        return
    if type(value) is int:
        if abs(value) > MAX_SAFE_INTEGER:
            _fail("invalid_number", "Number is outside the v1 safe integer range")
        return
    if isinstance(value, str):
        if any(0xD800 <= ord(c) <= 0xDFFF for c in value):
            _fail("invalid_json", "Unpaired Unicode surrogate")
        return
    if isinstance(value, list):
        for item in value:
            _tree(item, depth + 1, count)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                _fail("invalid_json", "JSON member name must be text")
            _tree(key, depth + 1, count)
            _tree(item, depth + 1, count)
        return
    _fail("invalid_number", "v1 numbers must be safe integers; encode decimals as strings")


def canonical_bytes(value):
    _tree(value)
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")


def resource_equal(left, right):
    """Exact JSON resource equality; bool/int, null/missing and array order never alias.

    Callers still validate the selected capability's resource schema. This helper
    deliberately does not coerce, normalize, reorder arrays or invent authority.
    """
    if not isinstance(left, dict) or not isinstance(right, dict):
        _fail("invalid_request", "Resource bindings must be JSON objects")
    return canonical_bytes(left) == canonical_bytes(right)


def _parse_integer_token(token):
    # Enforce BEFORE int(): Python's independent huge-integer guard stays enabled.
    digits = token[1:] if token.startswith("-") else token
    if len(digits) > 16:
        _fail("invalid_number", "Number is outside the v1 safe integer range")
    value = int(token)
    if abs(value) > MAX_SAFE_INTEGER:
        _fail("invalid_number", "Number is outside the v1 safe integer range")
    return value


def validate_resource_binding(capability_version, binding):
    """The three currently implemented execution profiles, not legacy history shapes."""
    if not isinstance(binding, dict):
        _fail("invalid_request", "Resource binding must be an object")
    if capability_version == "1.0":
        mode = binding.get("screenMode")
        if mode in ("main_screen", "background"):
            _keys(binding, {"screenMode", "packageNames"}, {"allowWholeDisplayCapture", "allowWholeDisplayInput"})
            packages = binding["packageNames"]
            if not isinstance(packages, list) or not 1 <= len(packages) <= 128 or any(not isinstance(item, str) or len(item) > 255 or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+", item) for item in packages) or len(set(packages)) != len(packages):
                _fail("invalid_request", "Invalid exact Android package scope")
            for field in ("allowWholeDisplayCapture", "allowWholeDisplayInput"):
                if field in binding and type(binding[field]) is not bool:
                    _fail("invalid_request", "Display capture/input scope must be explicit boolean")
        elif mode == "capabilities":
            _keys(binding, {"screenMode"}, {"projectId", "checkoutId", "runtimeId", "sourceDigest"})
            chosen = {key for key in ("projectId", "checkoutId", "runtimeId") if key in binding}
            if chosen and len(chosen) != 3:
                _fail("invalid_request", "Project execution requires the complete native binding")
            if chosen:
                for name in ("projectId", "checkoutId"):
                    if not isinstance(binding[name], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", binding[name]):
                        _fail("invalid_request", "Invalid native project identifier")
                if binding["runtimeId"] not in ("termux", "managed_process_v1"):
                    _fail("invalid_request", "Unsupported project runtime")
            if "sourceDigest" in binding and (not isinstance(binding["sourceDigest"], str) or not DIGEST.fullmatch(binding["sourceDigest"])):
                _fail("invalid_request", "Invalid project source digest")
        else:
            _fail("invalid_request", "Invalid phone execution screen mode")
    elif capability_version == "desktop-v2":
        _keys(binding, {"kind", "sessionId", "displayId"}, {"windowId"})
        if binding["kind"] != "desktop" or binding["displayId"] != "primary":
            _fail("invalid_request", "Unsupported desktop resource")
        integer(binding["sessionId"], "sessionId", 0, 0xffffffff)
        if "windowId" in binding:
            integer(binding["windowId"], "windowId", 1)
    elif capability_version == "workspace-v1":
        _keys(binding, {"kind", "environmentId", "binding"})
        if binding["kind"] != "workspace" or not isinstance(binding["environmentId"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", binding["environmentId"]) or not isinstance(binding["binding"], str) or not DIGEST.fullmatch(binding["binding"]):
            _fail("invalid_request", "Invalid exact native workspace")
    else:
        _fail("incompatible_capability", "Unsupported execution capability profile")
    return parse_json_object(canonical_bytes(binding))


def validate_connection_hint(hint):
    """Address metadata ONLY. Never adds relay trust, identity, grant or credentials."""
    if not isinstance(hint, dict):
        _fail("invalid_request", "Connection hint must be an object")
    if hint.get("kind") == "lan":
        import ipaddress
        _keys(hint, {"kind", "host", "candidatePort", "completionPort", "controlPort", "bulkPort"})
        host = text(hint["host"], "host", 64)
        try:
            if "%" in host:
                raise ValueError()
            ip = ipaddress.ip_address(host)
            if ip.is_unspecified or ip.is_multicast or ip.version == 6 and (ip.is_link_local or ip.ipv4_mapped is not None):
                raise ValueError()
        except ValueError:
            _fail("invalid_request", "LAN hint requires a routable numeric IP literal")
        ports = [integer(hint[name], name, 1, 65535) for name in ("candidatePort", "completionPort", "controlPort", "bulkPort")]
        if len(set(ports)) != 4:
            _fail("invalid_request", "Pairing and business lanes require distinct ports")
    elif hint.get("kind") == "relay":
        from urllib.parse import urlsplit
        _keys(hint, {"kind", "serviceBase", "inviterDeviceId"})
        device_id(hint["inviterDeviceId"], "inviterDeviceId")
        base = text(hint["serviceBase"], "serviceBase", 512)
        try:
            uri = urlsplit(base)
            if (uri.scheme not in ("https", "wss") or not uri.hostname or uri.username is not None or uri.password is not None
                    or uri.query or uri.fragment or "?" in base or "#" in base or "\\" in base
                    or any(ord(c) <= 32 or ord(c) >= 127 for c in base) or uri.port == 0):
                raise ValueError()
        except ValueError:
            _fail("invalid_request", "Relay hint must reference a configured TLS service base")
    else:
        _fail("invalid_request", "Unknown connection hint kind")
    return parse_json_object(canonical_bytes(hint))


def arguments_digest(envelope):
    return hashlib.sha256(canonical_bytes({key: value for key, value in envelope.items() if key not in NON_SEMANTIC})).hexdigest()


def receiver_task_id(envelope):
    """Deterministic receiver correlation only; it does not authenticate or authorize."""
    value = envelope.json() if isinstance(envelope, DeviceTaskEnvelope) else envelope
    kind = value.get("messageType")
    if kind not in ("ToolInvocation", "GoalSubmission"):
        _fail("invalid_request", "Only tool sessions and goals have receiver task identities")
    fields = {"protocolVersion": VERSION, "messageType": kind}
    for name in ("originDeviceId", "callerDeviceId", "targetDeviceId"):
        fields[name] = device_id(value.get(name), name)
    for name in ("rootTaskId", "localTaskId"):
        fields[name] = identifier(value.get(name), name)
    if kind == "GoalSubmission":
        fields["operationId"] = identifier(value.get("operationId"), "operationId")
    return "dt1_" + hashlib.sha256(canonical_bytes(fields)).hexdigest()


def allocation_id(parent_task_id, target_device_id, resource_binding, kind, parent_prepared_operation_id=None):
    """Native original-parent allocation correlation. Never a grant or a spend receipt."""
    if kind not in ("tool_session", "goal") or not isinstance(resource_binding, dict):
        _fail("invalid_request", "Invalid native allocation kind or exact resource")
    value = {"protocolVersion": VERSION, "parentTaskId": identifier(parent_task_id, "parentTaskId"),
             "targetDeviceId": device_id(target_device_id), "resourceBinding": resource_binding, "kind": kind}
    if kind == "goal":
        value["parentOperationId"] = identifier(parent_prepared_operation_id, "parentOperationId")
    return "ba1_" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def text(value, field, maximum=128):
    if not isinstance(value, str) or not value or len(value) > maximum or "\x00" in value:
        _fail("invalid_request", "Invalid " + field)
    return value


def identifier(value, field="id"):
    value = text(value, field)
    if not ID.fullmatch(value):
        _fail("invalid_request", "Invalid " + field)
    return value


def device_id(value, field="deviceId"):
    if not isinstance(value, str) or not DEVICE_ID.fullmatch(value):
        _fail("invalid_request", "Invalid " + field)
    return value


def integer(value, field, low=0, high=MAX_SAFE_INTEGER):
    if type(value) is not int or not low <= value <= high:
        _fail("invalid_request", "Invalid " + field)
    return value


def _keys(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= value.keys() or value.keys() - set(required) - set(optional):
        _fail("invalid_request", "Missing or unknown fields")


@dataclass(frozen=True)
class DeviceTaskEnvelope:
    _encoded: bytes

    def json(self):
        return json.loads(self._encoded)

    @property
    def method(self):
        return self.json()["method"]

    @property
    def arguments(self):
        return self.json()["arguments"]

    @property
    def side_effecting(self):
        return effect_of(self.json()) != "read"

    def get(self, name, default=None):
        return self.json().get(name, default)


def effect_of(value):
    fixed = METHODS[value["method"]][1]
    return value["arguments"]["effect"] if fixed == "tool" else fixed


def parse_json_object(raw):
    """Strict bounded RFC8259 object parsing for isolated non-business lanes."""
    if not isinstance(raw, bytes) or len(raw) > MAX_CONTROL_BYTES:
        _fail("limit_exceeded", "Control message exceeds 65536 bytes")
    try:
        value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_pairs, parse_int=_parse_integer_token,
                           parse_constant=lambda _: _fail("invalid_json", "Non-finite JSON number"))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        _fail("invalid_json", "Invalid UTF-8 JSON")
    _tree(value)
    if not isinstance(value, dict):
        _fail("invalid_request", "JSON object required")
    return value


def parse_request(raw):
    if not isinstance(raw, bytes) or len(raw) > MAX_CONTROL_BYTES:
        _fail("limit_exceeded", "Control message exceeds 65536 bytes")
    try:
        value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_pairs, parse_int=_parse_integer_token,
                           parse_constant=lambda _: _fail("invalid_json", "Non-finite JSON number"))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        _fail("invalid_json", "Invalid UTF-8 JSON")
    _tree(value)
    require_profile(value)
    _keys(value, {"protocolVersion", "protocolProfile", "messageType", "method", "requestId", "originDeviceId", "callerDeviceId", "targetDeviceId", "deadline", "arguments"}, FIELDS)
    if value["protocolVersion"] != VERSION:
        _fail("incompatible_version", "Unsupported device protocol version")
    method = value["method"]
    if not isinstance(method, str) or method not in METHODS:
        _fail("unsupported_method", "Unknown device method")
    if value["messageType"] != METHODS[method][0]:
        _fail("invalid_request", "Message type does not match method")
    for name in ("requestId", "operationId", "rootTaskId", "parentTaskId", "localTaskId", "runId", "instanceId", "grantId", "traceId"):
        if name in value:
            identifier(value[name], name)
    for name in ("originDeviceId", "callerDeviceId", "targetDeviceId"):
        device_id(value[name], name)
    integer(value["deadline"], "deadline", 1)
    for name in ("attempt", "executorEpoch", "authorizationEpoch"):
        if name in value:
            integer(value[name], name, 1)
    for name in ("capabilityVersion", "replyCursor"):
        if name in value:
            text(value[name], name, 512)
    for name in ("resourceBinding", "observationRef", "budget", "dependencySnapshot"):
        if name in value and not isinstance(value[name], dict):
            _fail("invalid_request", "Invalid " + name)
    visited = value.get("visitedDevices", [value["originDeviceId"]])
    if not isinstance(visited, list) or not visited or len(visited) > 3:
        _fail("delegation_denied", "Invalid delegation route")
    for item in visited:
        device_id(item, "visitedDevices")
    if len(set(visited)) != len(visited) or visited[0] != value["originDeviceId"] or visited[-1] != value["callerDeviceId"] or value["targetDeviceId"] in visited:
        _fail("delegation_denied", "Cyclic or inconsistent delegation route")
    depth = integer(value.get("maxDelegationDepth", 2), "maxDelegationDepth", 0, 2)
    if len(visited) > depth:
        _fail("delegation_denied", "Delegation depth exceeded")
    chain = value.get("delegationChain", [])
    if not isinstance(chain, list) or len(chain) != len(visited) - 1:
        _fail("delegation_denied", "Delegation proof chain is missing")
    # Cryptographic verification and local attenuation are separate admission steps.
    for proof in chain:
        if not isinstance(proof, dict):
            _fail("delegation_denied", "Invalid delegation proof")
        import delegation_policy
        delegation_policy.validate_wire_proof(proof)
    args = value["arguments"]
    if not isinstance(args, dict):
        _fail("invalid_request", "arguments must be an object")
    if method == "tool.invoke":
        _keys(args, {"toolName", "effect", "arguments"})
        text(args["toolName"], "toolName", 128)
        if args["effect"] not in ("read", "write", "external"):
            _fail("unsupported_effect", "Unknown tool effect")
        if not isinstance(args["arguments"], dict):
            _fail("invalid_request", "Tool parameters must be an object")
        if not value.get("capabilityVersion") or not value.get("resourceBinding"):
            _fail("invalid_request", "Tool invocation requires a bound capability/resource")
    elif method == "task.submitGoal":
        _keys(args, {"goal", "executionMode"}, {"contextSnapshot", "modelSource", "delegationAuthorization"})
        if args["executionMode"] != "agent":
            _fail("invalid_request", "Goal submission requires executionMode=agent")
        text(args["goal"], "goal", 12000)
        for name in ("contextSnapshot", "modelSource", "delegationAuthorization"):
            if name in args and not isinstance(args[name], dict):
                _fail("invalid_request", "Invalid " + name)
        if "delegationAuthorization" in args:
            import delegation_policy
            delegation_policy.validate_wire_proof(args["delegationAuthorization"])
    elif method == "device.describe":
        _keys(args, set())
    elif method in ("operation.get", "operation.cancel"):
        _keys(args, {"operationId", "argumentsDigest"} if method == "operation.cancel" else {"operationId"})
        if method == "operation.cancel" and (not isinstance(args["argumentsDigest"],str) or not DIGEST.fullmatch(args["argumentsDigest"])):
            _fail("invalid_request","Cancellation needs the exact original target semantic digest")
        identifier(args["operationId"], "operationId")
        if method == "operation.cancel" and value.get("operationId") == args["operationId"]:
            _fail("invalid_request", "Cancellation requires its own original control operation identity")
    elif method.startswith("task."):
        required = {"taskId"}
        optional = {"cursor", "limit"} if method == "task.events" else set()
        if method == "task.respond":
            required |= {"originalRequestId", "argumentsDigest", "runId", "executorEpoch", "controlLeaseId", "decision"}
        elif method in ("task.pause", "task.cancel"):
            required |= {"runId", "executorEpoch", "controlLeaseId"}
            optional = {"reason"}
        elif method == "task.acquireControl":
            required |= {"runId", "executorEpoch", "ttlMs"}
            optional = {"previousLeaseId"}
        elif method in ("task.answerQuestion", "task.steer", "task.requestResume"):
            required |= {"runId", "executorEpoch", "allocationId", "controlLeaseId"}
            required |= {"expectedRevision"} if method == "task.requestResume" else {"text"}
            if method == "task.answerQuestion":
                required |= {"originalRequestId", "waitRevision"}
        _keys(args, required, optional)
        for name in ("taskId", "originalRequestId", "runId", "allocationId", "controlLeaseId", "previousLeaseId"):
            if name in args:
                identifier(args[name], name)
        if "executorEpoch" in args:
            integer(args["executorEpoch"], "executorEpoch", 1)
        for name in ("waitRevision", "expectedRevision"):
            if name in args:
                integer(args[name], name, 1)
        if "text" in args:
            text(args["text"], "text", 4000)
            if not args["text"].strip():
                _fail("invalid_request", "Ordinary task input cannot be blank")
        if "argumentsDigest" in args and not DIGEST.fullmatch(str(args["argumentsDigest"])):
            _fail("invalid_request", "Invalid original argumentsDigest")
        if "limit" in args:
            integer(args["limit"], "limit", 1, 256)
        if "ttlMs" in args:
            integer(args["ttlMs"], "ttlMs", 1, 300000)
        for name in ("cursor", "reason", "decision"):
            if name in args:
                text(args[name], name, 512)
    elif method.startswith("artifact."):
        _keys(args, {"artifactId"} | ({"offset", "length", "contentDigest"} if method.endswith("readChunk") else set()))
        identifier(args["artifactId"], "artifactId")
        if method.endswith("readChunk"):
            integer(args["offset"], "offset")
            integer(args["length"], "length", 1, 262144)
            if not DIGEST.fullmatch(str(args["contentDigest"])):
                _fail("invalid_request", "Invalid artifact content digest")
    if method in ("tool.invoke", "task.submitGoal"):
        for name in ("rootTaskId", "parentTaskId", "localTaskId", "runId", "instanceId"):
            identifier(value.get(name), name)
        for name in ("attempt", "executorEpoch"):
            integer(value.get(name), name, 1)
        budget = value.get("budget")
        _keys(budget, {"allocationId", "toolCalls", "modelCalls"})
        identifier(budget["allocationId"], "allocationId")
        integer(budget["toolCalls"], "toolCalls", 0, 400)
        integer(budget["modelCalls"], "modelCalls", 0, 1000)
    if method != "device.describe":
        identifier(value.get("grantId"), "grantId")
        integer(value.get("authorizationEpoch"), "authorizationEpoch", 1)
    if effect_of(value) != "read":
        identifier(value.get("operationId"), "operationId")
        if not isinstance(value.get("argumentsDigest"), str) or value["argumentsDigest"] != arguments_digest(value):
            _fail("digest_mismatch", "Semantic request digest does not match")
    elif "argumentsDigest" in value and value["argumentsDigest"] != arguments_digest(value):
        _fail("digest_mismatch", "Semantic request digest does not match")
    return DeviceTaskEnvelope(canonical_bytes(value))


def validate_request(raw, peer, local_device_id, now_ms):
    envelope = parse_request(raw)
    if peer is None or peer.device_id != envelope.get("callerDeviceId"):
        _fail("unauthenticated", "Caller does not match the authenticated TLS peer")
    if envelope.get("targetDeviceId") != device_id(local_device_id):
        _fail("wrong_audience", "Request is for another device")
    ttl = envelope.get("deadline") - integer(now_ms, "nowMs")
    if ttl <= 0:
        _fail("expired", "Request business deadline is expired")
    return envelope


def validate_accepted_audience(value):
    required=set("kind objectId originDeviceId callerDeviceId targetDeviceId instanceId rootTaskId localTaskId grantId authorizationEpoch resourceBinding acceptedAt retainedUntil".split())
    source=set("sourceShareId sourceIssuerDeviceId sourceGrantId sourceAuthorizationEpoch sourceProofFingerprint".split())
    _keys(value,required,source|{"runId","executorEpoch","requestRunId","requestExecutorEpoch","taskId","argumentsDigest"})
    if value["kind"] not in ("operation","task","artifact") or not isinstance(value["resourceBinding"],dict):_fail("invalid_request","Invalid accepted object audience")
    for field in ("originDeviceId","callerDeviceId","targetDeviceId"):device_id(value[field],field)
    for field in ("objectId","instanceId","rootTaskId","localTaskId","grantId","runId","requestRunId","taskId","sourceShareId","sourceGrantId"):
        if field in value:identifier(value[field],field)
    for field in ("authorizationEpoch","acceptedAt","retainedUntil","executorEpoch","requestExecutorEpoch","sourceAuthorizationEpoch"):
        if field in value:integer(value[field],field,1)
    for run,epoch in (("runId","executorEpoch"),("requestRunId","requestExecutorEpoch")):
        if (run in value)!=(epoch in value):_fail("invalid_request","A real run must have its paired executor epoch")
    present=source & value.keys()
    if present and present!=source:_fail("invalid_request","Incomplete original source audience")
    if present:
        device_id(value["sourceIssuerDeviceId"])
        if not isinstance(value["sourceProofFingerprint"],str) or not DIGEST.fullmatch(value["sourceProofFingerprint"]):_fail("invalid_request","Invalid source audience fingerprint")
    if "argumentsDigest" in value and (not isinstance(value["argumentsDigest"],str) or not DIGEST.fullmatch(value["argumentsDigest"])):_fail("invalid_request","Invalid immutable original object digest")
    if value["originDeviceId"]!=value["callerDeviceId"] and not present:_fail("invalid_request","Forwarded accepted object lacks native source reference")
    return parse_json_object(canonical_bytes(value))


def operation_receipt(executor_device_id, instance_id, operation_id, semantic_digest, status, accepted, effect_state, payload,
                      *, accepted_at=None, audience=None, job_id=None, local_task_id=None, run_id=None, executor_epoch=None, cancellation=None):
    """Format ORIGINAL ledger facts only. This never commits acceptance or verifies an effect."""
    value={"protocolVersion":VERSION, "protocolProfile": PROFILE,"messageType":"OperationReceipt","executorDeviceId":executor_device_id,"instanceId":instance_id,
           "operationId":operation_id,"argumentsDigest":semantic_digest,"status":status,"accepted":accepted,"effectState":effect_state,"payload":payload}
    for key,item in (("acceptedAt",accepted_at),("audience",audience),("jobId",job_id),("localTaskId",local_task_id),("runId",run_id),("executorEpoch",executor_epoch),("cancellation",cancellation)):
        if item is not None:value[key]=item
    return validate_message(value)


def device_description(device_id_value, instance_id, captured_at, valid_until, capabilities, grant_receipts, metadata):
    return validate_message({"protocolVersion":VERSION, "protocolProfile": PROFILE,"messageType":"DeviceDescription","deviceId":device_id_value,"instanceId":instance_id,
                             "capturedAt":captured_at,"validUntil":valid_until,"capabilities":capabilities,"grantReceipts":grant_receipts,
                             "metadata":metadata,"executionAuthorized":False})


def _grant_receipt_shape(receipt):
    import base64
    import device_pairing
    require_profile(receipt)
    _keys(receipt,{"protocolVersion","protocolProfile","messageType","issuerDeviceId","issuerCertificateFingerprint","body","signature"})
    if receipt["protocolVersion"]!=VERSION or receipt["messageType"]!="PairGrantReceipt":_fail("invalid_request","Invalid signed owner grant receipt")
    device_id(receipt["issuerDeviceId"])
    if not isinstance(receipt["issuerCertificateFingerprint"],str) or not DIGEST.fullmatch(receipt["issuerCertificateFingerprint"]):_fail("invalid_request","Invalid issuer certificate fingerprint")
    body=receipt["body"];_keys(body,{"grant","targetInstanceId","issuedAt"});identifier(body["targetInstanceId"]);integer(body["issuedAt"],"issuedAt",1)
    grant=body["grant"]
    try:device_pairing.validate_grant(grant,receipt["issuerDeviceId"],grant["peerDeviceId"],grant["issuedAt"],for_history=True)
    except (PermissionError,KeyError,TypeError):_fail("invalid_request","Invalid receiving owner grant")
    try:
        encoded=text(receipt["signature"],"signature",200);raw=base64.b64decode(encoded,validate=True)
        if not 8<=len(raw)<=72 or base64.b64encode(raw).decode()!=encoded:raise ValueError()
    except ValueError:_fail("invalid_request","Invalid canonical owner receipt signature")


def validate_message(value):
    """Validate bounded response records; neither a snapshot nor an event is authority."""
    raw = canonical_bytes(value)
    if len(raw) > MAX_CONTROL_BYTES:
        _fail("limit_exceeded", "Control message exceeds 65536 bytes")
    if not isinstance(value, dict):
        _fail("invalid_request", "Message must be an object")
    require_profile(value)
    if value.get("protocolVersion") != VERSION:
        _fail("incompatible_version", "Unsupported device protocol version")
    kind = value.get("messageType")
    if kind == "DeviceError":
        _keys(value, {"protocolVersion", "protocolProfile", "messageType", "requestId", "error"}, {"method", "executorDeviceId", "instanceId"})
        if value["requestId"] is not None:
            identifier(value["requestId"], "requestId")
        error = value["error"]
        _keys(error, {"code", "message", "retryable", "effectState"})
        identifier(error["code"], "errorCode")
        text(error["message"], "message", 300)
        if error["retryable"] is not False or error["effectState"] not in ("not_dispatched", "unknown"):
            _fail("invalid_request", "Error must preserve unknown effects and prohibit whole-call retry")
        if "method" in value and (not isinstance(value["method"], str) or value["method"] not in METHODS):
            _fail("invalid_request", "Unknown response method")
        if "executorDeviceId" in value:
            device_id(value["executorDeviceId"])
        if "instanceId" in value:
            identifier(value["instanceId"], "instanceId")
        return json.loads(raw)
    if kind == "DeviceResponse":
        _keys(value, {"protocolVersion", "protocolProfile", "messageType", "requestId", "method", "executorDeviceId", "instanceId", "result"})
        identifier(value["requestId"], "requestId")
        device_id(value["executorDeviceId"])
        identifier(value["instanceId"], "instanceId")
        method, result = value["method"], value["result"]
        if not isinstance(method, str) or method not in METHODS or not isinstance(result, dict):
            _fail("invalid_request", "Unknown response method or invalid result")
        if method == "device.describe":
            if result.get("messageType")!="DeviceDescription":_fail("invalid_request","Describe requires typed paired metadata")
            validate_message(result)
            if result["deviceId"]!=value["executorDeviceId"] or result["instanceId"]!=value["instanceId"]:_fail("wrong_audience","Description belongs to another native device instance")
        elif method in ("tool.invoke","task.submitGoal","operation.get","operation.cancel","task.respond","task.pause","task.cancel","task.acquireControl","task.answerQuestion","task.steer","task.requestResume"):
            if result.get("messageType")!="OperationReceipt":_fail("invalid_request","Method requires an original typed operation receipt")
            validate_message(result)
            if result["executorDeviceId"]!=value["executorDeviceId"] or result["instanceId"]!=value["instanceId"]:_fail("wrong_audience","Receipt belongs to another native executor instance")
            if method=="operation.cancel" and "cancellation" not in result or method not in ("operation.cancel","operation.get") and "cancellation" in result:_fail("invalid_request","Cancellation result is not this method's receipt")
            if method=="task.submitGoal" and result["accepted"] and "localTaskId" not in result:_fail("invalid_request","Accepted Goal requires its actual receiver task")
            if method=="task.acquireControl" and result["accepted"] and result["status"]=="succeeded":
                identifier(result["payload"].get("leaseId"),"leaseId");integer(result["payload"].get("expiresAt"),"expiresAt",1)
        elif method in ("task.get", "artifact.describe"):
            expected = "TaskSnapshot" if method == "task.get" else "ArtifactDescriptor"
            if result.get("messageType") != expected:
                _fail("invalid_request", "Response result has the wrong typed record")
            validate_message(result)
            if result["executorDeviceId"] != value["executorDeviceId"] or result["instanceId"] != value["instanceId"]:
                _fail("wrong_audience", "Response record belongs to another device instance")
        elif method == "task.events":
            _keys(result, {"events", "nextCursor", "hasMore", "snapshotRequired"})
            text(result["nextCursor"], "nextCursor", 512)
            if type(result["hasMore"]) is not bool or type(result["snapshotRequired"]) is not bool or not isinstance(result["events"], list) or len(result["events"]) > 256:
                _fail("invalid_request", "Invalid bounded event page")
            for event in result["events"]:
                if not isinstance(event, dict) or event.get("messageType") != "TaskEvent":
                    _fail("invalid_request", "Event page requires TaskEvent records")
                validate_message(event)
                if event["executorDeviceId"] != value["executorDeviceId"] or event["instanceId"] != value["instanceId"]:
                    _fail("wrong_audience", "Event belongs to another device instance")
        elif method == "artifact.readChunk":
            _keys(result, {"artifactId", "transferId", "offset", "length", "contentDigest", "status"})
            identifier(result["artifactId"], "artifactId")
            import uuid
            try:
                if str(uuid.UUID(result["transferId"])) != result["transferId"]:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                _fail("invalid_request", "Invalid canonical transfer UUID")
            integer(result["offset"], "offset")
            integer(result["length"], "length", 1, 262144)
            if not isinstance(result["contentDigest"], str) or not DIGEST.fullmatch(result["contentDigest"]) or result["status"] != "ready":
                _fail("invalid_request", "Invalid chunk transfer acknowledgement")
        return json.loads(raw)
    if kind == "OperationReceipt":
        _keys(value,set("protocolVersion protocolProfile messageType executorDeviceId instanceId operationId argumentsDigest status accepted effectState payload".split()),set("acceptedAt audience jobId localTaskId runId executorEpoch cancellation".split()))
        device_id(value["executorDeviceId"]);identifier(value["instanceId"])
        if value["operationId"] is not None:identifier(value["operationId"],"operationId")
        if value["argumentsDigest"] is not None and (not isinstance(value["argumentsDigest"],str) or not DIGEST.fullmatch(value["argumentsDigest"])):_fail("invalid_request","Invalid original semantic digest")
        if not isinstance(value["status"],str) or value["status"] not in TASK_STATES|{"prepared","unknown","not_found","rejected"} or type(value["accepted"]) is not bool or value["effectState"] not in ("not_dispatched","unknown","reported") or not isinstance(value["payload"],dict):_fail("invalid_request","Invalid original operation state")
        if value["status"] in ("unknown","not_found") and value["effectState"]!="unknown":_fail("invalid_request","Unknown or missing original operation cannot imply no effect")
        if value["accepted"]:
            if value["operationId"] is None or value["argumentsDigest"] is None or value["status"] in ("not_found","rejected"):_fail("invalid_request","Accepted receipt needs original immutable operation identity")
            integer(value.get("acceptedAt"),"acceptedAt",1);audience=validate_accepted_audience(value.get("audience"))
            if "argumentsDigest" in audience and audience["argumentsDigest"]!=value["argumentsDigest"]:_fail("wrong_audience","Receipt digest differs from its committed object")
            if audience["kind"]!="operation" or audience["objectId"]!=value["operationId"] or audience["targetDeviceId"]!=value["executorDeviceId"] or audience["instanceId"]!=value["instanceId"] or audience["acceptedAt"]!=value["acceptedAt"]:_fail("wrong_audience","Receipt changed its original committed audience")
        elif value["status"] not in ("unknown","not_found","rejected") or "acceptedAt" in value or "audience" in value:_fail("invalid_request","Unaccepted receipt cannot manufacture accepted task facts")
        for field in ("jobId","localTaskId","runId"):
            if field in value:identifier(value[field],field)
        if ("runId" in value)!=("executorEpoch" in value):_fail("invalid_request","Actual target run must have paired epoch")
        if "executorEpoch" in value:
            integer(value["executorEpoch"],"executorEpoch",1)
            if not value["accepted"] or value["audience"].get("runId")!=value["runId"] or value["audience"].get("executorEpoch")!=value["executorEpoch"]:_fail("invalid_request","Target run is not the original committed binding")
        if "cancellation" in value:
            cancellation=value["cancellation"];_keys(cancellation,{"targetOperationId","targetArgumentsDigest","requested","stopped"});identifier(cancellation["targetOperationId"],"targetOperationId")
            if not isinstance(cancellation["targetArgumentsDigest"],str) or not DIGEST.fullmatch(cancellation["targetArgumentsDigest"]):_fail("invalid_request","Cancellation lacks exact original target digest")
            _keys(value["payload"],{"managementOnly"})
            if value["payload"]["managementOnly"] is not True:_fail("invalid_request","Stop response is management-only, not original content")
            if type(cancellation["requested"]) is not bool or type(cancellation["stopped"]) is not bool or cancellation["targetOperationId"]==value["operationId"]:_fail("invalid_request","Invalid distinct original job cancellation receipt")
        return json.loads(raw)
    if kind == "DeviceDescription":
        _keys(value,set("protocolVersion protocolProfile messageType deviceId instanceId capturedAt validUntil capabilities grantReceipts metadata executionAuthorized".split()))
        device_id(value["deviceId"]);identifier(value["instanceId"]);integer(value["capturedAt"],"capturedAt",1);integer(value["validUntil"],"validUntil",1)
        if not 0<value["validUntil"]-value["capturedAt"]<=30000 or value["executionAuthorized"] is not False or not isinstance(value["metadata"],dict):_fail("invalid_request","Description is bounded metadata, never execution authority")
        if not isinstance(value["capabilities"],list) or len(value["capabilities"])>128 or not isinstance(value["grantReceipts"],list) or len(value["grantReceipts"])>16:_fail("invalid_request","Description exceeds native scope limits")
        for cap in value["capabilities"]:
            if not isinstance(cap,dict):_fail("invalid_request","Invalid capability metadata")
            cap_id=text(cap.get("id"),"capabilityId",128)
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}",cap_id):_fail("invalid_request","Invalid capability metadata identifier")
            text(cap.get("version"),"capabilityVersion",128)
        for receipt in value["grantReceipts"]:
            _grant_receipt_shape(receipt)
            if receipt["issuerDeviceId"]!=value["deviceId"] or receipt["body"]["targetInstanceId"]!=value["instanceId"]:_fail("wrong_audience","Grant receipt belongs to another described native instance")
        return json.loads(raw)
    if kind == "TaskSnapshot":
        _keys(value, {"protocolVersion", "protocolProfile", "messageType", "taskId", "executorDeviceId", "instanceId", "state", "revision", "eventCursor", "updatedAt"}, {"runId", "executorEpoch", "result", "dependencies"})
        if not isinstance(value["state"], str) or value["state"] not in TASK_STATES:
            _fail("invalid_state", "Unknown task state")
        integer(value["revision"], "revision", 1)
        integer(value["updatedAt"], "updatedAt", 1)
        text(value["eventCursor"], "eventCursor", 512)
    elif kind == "TaskEvent":
        _keys(value, {"protocolVersion", "protocolProfile", "messageType", "taskId", "executorDeviceId", "instanceId", "sequence", "cursor", "kind", "createdAt", "data"})
        integer(value["sequence"], "sequence", 1)
        integer(value["createdAt"], "createdAt", 1)
        text(value["cursor"], "cursor", 512)
        text(value["kind"], "kind", 128)
        if not isinstance(value["data"], dict):
            _fail("invalid_request", "Event data must be an object")
    elif kind == "ArtifactDescriptor":
        _keys(value, {"protocolVersion", "protocolProfile", "messageType", "artifactId", "taskId", "executorDeviceId", "instanceId", "contentDigest", "size", "mediaType", "name", "revision"}, {"transferId", "versionId", "kind", "runId", "executorEpoch", "operationId", "observationRef", "resourceBinding", "expiresAt", "requestRunId", "requestExecutorEpoch"})
        identifier(value["artifactId"], "artifactId")
        if not DIGEST.fullmatch(str(value["contentDigest"])):
            _fail("invalid_request", "Invalid artifact content digest")
        integer(value["size"], "size")
        integer(value["revision"], "revision", 1)
        text(value["mediaType"], "mediaType", 128)
        name = text(value["name"], "name", 160)
        if name in (".", "..") or any(ord(c)<32 or c in "/\\:" for c in name):
            _fail("invalid_request", "Artifact name is not a path")
        for field in ("versionId", "operationId"):
            if field in value:
                identifier(value[field], field)
        if ("requestRunId" in value)!=("requestExecutorEpoch" in value):_fail("invalid_request","Source request run must have paired epoch")
        if "requestRunId" in value:identifier(value["requestRunId"],"requestRunId");integer(value["requestExecutorEpoch"],"requestExecutorEpoch",1)
        if "transferId" in value:
            import uuid
            try:
                if str(uuid.UUID(value["transferId"])) != value["transferId"]:
                    raise ValueError()
            except (ValueError, TypeError, AttributeError):
                _fail("invalid_request", "Invalid canonical transfer UUID")
        if "kind" in value and value["kind"] not in ("file", "project_bundle", "screenshot"):
            _fail("invalid_request", "Invalid artifact kind")
        for field in ("observationRef", "resourceBinding"):
            if field in value and not isinstance(value[field], dict):
                _fail("invalid_request", "Invalid " + field)
        if "expiresAt" in value:
            integer(value["expiresAt"], "expiresAt", 1)
        if value.get("kind") == "screenshot":
            if not value.get("resourceBinding") or not isinstance(value.get("observationRef"), dict):
                _fail("invalid_request", "Screenshot requires its exact observation and resource")
            captured = integer(value["observationRef"].get("capturedAt"), "capturedAt", 1)
            expires = integer(value.get("expiresAt"), "expiresAt", 1)
            if not 0 < expires - captured <= 600000:
                _fail("invalid_request", "Screenshot lifetime exceeds policy")
    else:
        _fail("invalid_request", "Unknown response message type")
    identifier(value["taskId"], "taskId")
    identifier(value["instanceId"], "instanceId")
    device_id(value["executorDeviceId"], "executorDeviceId")
    if ("runId" in value) != ("executorEpoch" in value):
        _fail("invalid_request", "Run and executor epoch must be bound together")
    if "runId" in value:
        identifier(value["runId"], "runId")
        integer(value.get("executorEpoch"), "executorEpoch", 1)
    return json.loads(raw)
