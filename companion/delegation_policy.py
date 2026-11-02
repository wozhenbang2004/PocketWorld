"""Signed, attenuated per-hop statements; receiving-device grants remain authoritative."""
from __future__ import annotations

import base64
import hashlib

import device_identity as identity
import device_protocol as protocol

FIELDS = set("protocolVersion protocolProfile messageType issuerDeviceId delegateDeviceId targetResources rootTaskId parentTaskId grantId authorizationEpoch capabilities toolNames toolBudget modelBudget deadline remainingDepth previousProofDigest sourceAllocationId targetShares issuerCertificateDer issuerCertificateFingerprint signature".split())


def _require(condition, message):
    if not condition:
        raise PermissionError(message)


def validate_target_resources(values):
    """Exact target/resource pairs; independent target/resource arrays are forbidden."""
    _require(isinstance(values,list) and 1<=len(values)<=128,"Invalid exact forwarding target/resources")
    seen=set()
    for pair in values:
        protocol._keys(pair,{"targetDeviceId","resourceBinding"})
        protocol.device_id(pair["targetDeviceId"],"targetDeviceId")
        _require(isinstance(pair["resourceBinding"],dict),"Forwarding requires an exact resource object")
        key=protocol.canonical_bytes(pair)
        _require(key not in seen,"Duplicate exact target/resource pair")
        seen.add(key)


def contains_target_resource(values,target,binding):
    return any(pair["targetDeviceId"]==target and protocol.resource_equal(pair["resourceBinding"],binding) for pair in values)


def share_id(issuer_device_id, source_allocation_id, target_device_id, resource_binding):
    """Stable A source/target/exact-resource correlation, never proof or authority."""
    if not isinstance(resource_binding, dict):
        protocol._fail("invalid_request", "Source share requires an exact resource")
    scope = {"protocolVersion": protocol.VERSION, "issuerDeviceId": protocol.device_id(issuer_device_id),
             "sourceAllocationId": protocol.identifier(source_allocation_id, "sourceAllocationId"),
             "targetDeviceId": protocol.device_id(target_device_id), "resourceBinding": resource_binding}
    return "ds1_" + hashlib.sha256(protocol.canonical_bytes(scope)).hexdigest()


def target_share(issuer_device_id, source_allocation_id, target_device_id, resource_binding, tool_budget, model_budget):
    """Unsigned native A allocation intent only. Its original reserve must precede signing."""
    return {"shareId": share_id(issuer_device_id, source_allocation_id, target_device_id, resource_binding),
            "targetDeviceId": target_device_id, "resourceBinding": protocol.parse_json_object(protocol.canonical_bytes(resource_binding)),
            "toolBudget": protocol.integer(tool_budget, "toolBudget", 0, 400),
            "modelBudget": protocol.integer(model_budget, "modelBudget", 0, 1000)}


def _validate_shares(proof):
    source = protocol.identifier(proof["sourceAllocationId"], "sourceAllocationId")
    shares = proof["targetShares"]
    _require(isinstance(shares, list) and 1 <= len(shares) <= 129, "Invalid signed source target shares")
    seen, tools, models = set(), 0, 0
    for share in shares:
        protocol._keys(share, {"shareId", "targetDeviceId", "resourceBinding", "toolBudget", "modelBudget"})
        expected = share_id(proof["issuerDeviceId"], source, share["targetDeviceId"], share["resourceBinding"])
        _require(share["shareId"] == expected and expected not in seen, "Duplicate or changed source share identity")
        seen.add(expected)
        _require(share["targetDeviceId"] == proof["delegateDeviceId"] or contains_target_resource(proof["targetResources"],share["targetDeviceId"],share["resourceBinding"]), "Share target outside signed route")
        if share["targetDeviceId"] != proof["delegateDeviceId"]:
            _require(contains_target_resource(proof["targetResources"],share["targetDeviceId"],share["resourceBinding"]), "Share expanded exact forwarding resource")
        tools += protocol.integer(share["toolBudget"], "toolBudget", 0, 400)
        models += protocol.integer(share["modelBudget"], "modelBudget", 0, 1000)
    _require(tools <= proof["toolBudget"] and models <= proof["modelBudget"], "Source target shares exceed the one original reservation")


def _matching_share(proof, target_device_id, resource_binding):
    matching = [share for share in proof["targetShares"] if share["targetDeviceId"] == target_device_id
                and protocol.resource_equal(share["resourceBinding"], resource_binding)]
    _require(len(matching) == 1, "A did not sign this exact target/resource share")
    return matching[0]


def admitted_source_share(request):
    """Internal metadata ONLY after Permission verified A; this function grants no authority."""
    proof = request.arguments.get("delegationAuthorization") if request.method == "task.submitGoal" else None
    if proof is None:
        chain = request.get("delegationChain", [])
        proof = chain[0] if chain else None
    if proof is None:
        return None
    share = _matching_share(proof, request.get("targetDeviceId"), request.get("resourceBinding", {}))
    return dict(share, issuerDeviceId=proof["issuerDeviceId"], sourceAllocationId=proof["sourceAllocationId"],
                deadline=proof["deadline"], sourceGrantId=proof["grantId"], sourceAuthorizationEpoch=proof["authorizationEpoch"],
                sourceProofFingerprint=hashlib.sha256(protocol.canonical_bytes({key: value for key, value in proof.items() if key != "signature"})).hexdigest())


def _shape(value, signed):
    protocol.require_profile(value)
    required = FIELDS if signed else FIELDS - {"signature"}
    protocol._keys(value, required)
    _require(value["protocolVersion"] == protocol.VERSION and value["messageType"] == "DelegationProof", "Unsupported delegation proof")
    for key in ("issuerDeviceId", "delegateDeviceId"):
        protocol.device_id(value[key], key)
    for key in ("rootTaskId", "parentTaskId", "grantId"):
        protocol.identifier(value[key], key)
    for key in ("authorizationEpoch", "deadline"):
        protocol.integer(value[key], key, 1)
    protocol.integer(value["toolBudget"], "toolBudget", 0, 400)
    protocol.integer(value["modelBudget"], "modelBudget", 0, 1000)
    protocol.integer(value["remainingDepth"], "remainingDepth", 0, 2)
    _require(value["previousProofDigest"] == "" or isinstance(value["previousProofDigest"], str) and protocol.DIGEST.fullmatch(value["previousProofDigest"]), "Invalid previous proof digest")
    _require(isinstance(value["issuerCertificateFingerprint"], str) and protocol.DIGEST.fullmatch(value["issuerCertificateFingerprint"]), "Invalid issuer fingerprint")
    for name, minimum, maximum, text_limit in (("issuerCertificateDer", 64, 16384, 22000), ("signature", 8, 80, 200)):
        if name == "signature" and not signed:
            continue
        encoded = protocol.text(value[name], name, text_limit)
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except ValueError:
            raise PermissionError("Invalid canonical base64 " + name) from None
        _require(minimum <= len(decoded) <= maximum and base64.b64encode(decoded).decode() == encoded, "Invalid canonical base64 " + name)
    validate_target_resources(value["targetResources"])
    _require(all(pair["targetDeviceId"] not in (value["issuerDeviceId"],value["delegateDeviceId"]) for pair in value["targetResources"]),"Forwarding target scope creates a cycle")
    for key in ("capabilities","toolNames"):
        values=value[key]
        _require(isinstance(values,list) and len(values)<=128 and (key=="toolNames" or len(values)>0),"Invalid delegation scope")
        for item in values:protocol.text(item,key,128)
        _require(len(set(values))==len(values),"Duplicate delegation scope")
    _validate_shares(value)
    protocol.canonical_bytes(value)


def validate_wire_proof(proof):
    try:
        _shape(proof, True)
    except protocol.ProtocolError:
        raise
    except (PermissionError, TypeError, ValueError, KeyError):
        protocol._fail("invalid_request", "Invalid signed delegation proof shape")


def sign(local, statement):
    """Native parent budget reservation MUST precede signing. This allocates no budget."""
    _require(not any(key in statement for key in ("signature", "issuerCertificateDer", "issuerCertificateFingerprint")) and statement.get("issuerDeviceId") == local.device_id, "Native signer owns certificate/signature fields")
    value = dict(statement, issuerCertificateDer=base64.b64encode(local.certificate_der).decode(), issuerCertificateFingerprint=local.certificate_fingerprint)
    _shape(value, False)
    value["signature"] = base64.b64encode(local.sign(protocol.canonical_bytes(value))).decode()
    return value


def validate_forwarding_scope(scope):
    protocol._keys(scope, set("targetResources capabilities toolNames toolBudget modelBudget maxDelegationDepth".split()))
    protocol.integer(scope["maxDelegationDepth"], "maxDelegationDepth", 2, 2)
    protocol.integer(scope["toolBudget"], "toolBudget", 0, 400)
    protocol.integer(scope["modelBudget"], "modelBudget", 0, 1000)
    validate_target_resources(scope["targetResources"])
    for name in ("capabilities","toolNames"):
        values=scope[name]
        _require(isinstance(values,list) and len(values)<=128 and (name=="toolNames" or len(values)>0),"Invalid explicit forwarding scope")
        for item in values:protocol.text(item,name,128)
        _require(len(set(values))==len(values),"Duplicate forwarding scope")
    protocol.canonical_bytes(scope)


def _verify_signer(proof, expected_issuer, now_ms):
    from cryptography import x509
    import datetime
    der = base64.b64decode(proof["issuerCertificateDer"], validate=True)
    cert = x509.load_der_x509_certificate(der)
    now = datetime.datetime.fromtimestamp(now_ms / 1000, datetime.timezone.utc)
    _require(cert.not_valid_before_utc <= now < cert.not_valid_after_utc, "Delegation identity certificate expired")
    _require(identity.device_id(der) == expected_issuer == proof["issuerDeviceId"] and identity.certificate_fingerprint(der) == proof["issuerCertificateFingerprint"], "Signer does not match original route")
    unsigned = {key: value for key, value in proof.items() if key != "signature"}
    identity.verify_signature(der, protocol.canonical_bytes(unsigned), base64.b64decode(proof["signature"], validate=True))


def verify_forwarding_authorization(peer, goal, receiving_grant, now_ms):
    proof = goal.arguments.get("delegationAuthorization")
    if proof is None:
        return None
    scope = receiving_grant.get("forwardingScope")
    validate_forwarding_scope(scope)
    _shape(proof, True)
    visited = goal.get("visitedDevices", [goal.get("originDeviceId")])
    _require(goal.method == "task.submitGoal" and goal.get("originDeviceId") == goal.get("callerDeviceId") and len(visited) == 1 and goal.get("maxDelegationDepth", 2) == 2, "Initial forwarding authorizes only A to B to one final device")
    _verify_signer(proof, goal.get("originDeviceId"), now_ms)
    _require(peer.certificate_fingerprint == proof["issuerCertificateFingerprint"] and goal.get("targetDeviceId") == proof["delegateDeviceId"] and goal.get("rootTaskId") == proof["rootTaskId"] and protocol.receiver_task_id(goal) == proof["parentTaskId"] and goal.get("grantId") == proof["grantId"] and goal.get("authorizationEpoch") == proof["authorizationEpoch"] and proof["previousProofDigest"] == "" and proof["remainingDepth"] == 0, "Initial proof changed exact accepted parent or source grant")
    budget = goal.get("budget")
    _require(now_ms < proof["deadline"] <= min(goal.get("deadline"), receiving_grant["expiresAt"]) and proof["toolBudget"] <= budget["toolCalls"] and proof["modelBudget"] <= budget["modelCalls"] and proof["sourceAllocationId"] == budget["allocationId"], "Initial forwarding expanded task limits")
    local_share = _matching_share(proof, goal.get("targetDeviceId"), goal.get("resourceBinding", {}))
    _require(local_share["toolBudget"] <= budget["toolCalls"] and local_share["modelBudget"] <= budget["modelCalls"], "Local delegate share expanded goal budget")
    forwarded = [share for share in proof["targetShares"] if share["targetDeviceId"] != goal.get("targetDeviceId")]
    _require(all(contains_target_resource(scope["targetResources"],share["targetDeviceId"],share["resourceBinding"]) for share in forwarded),"A share expanded B owner exact target/resource pair")
    _require(sum(share["toolBudget"] for share in forwarded) <= scope["toolBudget"] and sum(share["modelBudget"] for share in forwarded) <= scope["modelBudget"], "Forwarded shares expanded B owner forwarding scope")
    # B may own only its exact accepted goal resource, not arbitrary local resources.
    _require(all(share is local_share for share in proof["targetShares"] if share["targetDeviceId"] == goal.get("targetDeviceId")), "A signed an unbound delegate resource")
    for name in ("targetResources", "capabilities", "toolNames"):
        allowed = {protocol.canonical_bytes(value) for value in scope[name]}
        _require(all(protocol.canonical_bytes(value) in allowed for value in proof[name]), "Initial forwarding expanded owner scope")
    _require(not any(pair["targetDeviceId"] in (goal.get("originDeviceId"), goal.get("targetDeviceId")) for pair in proof["targetResources"]), "Initial forwarding creates a device cycle")
    return proof


def verify(request, receiving_grant, now_ms):
    envelope = request.json()
    chain, visited = envelope.get("delegationChain", []), envelope.get("visitedDevices", [])
    _require(receiving_grant["allowDelegation"] and 0 < len(chain) == len(visited) - 1 and len(visited) <= receiving_grant["maxDelegationDepth"], "Receiver did not grant this delegation route")
    previous = None
    for index, proof in enumerate(chain):
        _shape(proof, True)
        _verify_signer(proof, visited[index], now_ms)
        _require(proof["delegateDeviceId"] == visited[index + 1], "Signer does not match original route")
        _require(contains_target_resource(proof["targetResources"],request.get("targetDeviceId"),request.get("resourceBinding",{})) and request.get("rootTaskId") == proof["rootTaskId"] and request.method in proof["capabilities"] and now_ms < request.get("deadline") <= proof["deadline"] and proof["remainingDepth"] >= len(chain) - index - 1, "Delegation does not authorize this request")
        if request.method == "tool.invoke":
            _require(request.arguments["toolName"] in proof["toolNames"], "Delegated tool not granted")
        budget = request.get("budget", {})
        share = _matching_share(proof, request.get("targetDeviceId"), request.get("resourceBinding", {}))
        _require(contains_target_resource(proof["targetResources"],request.get("targetDeviceId"),request.get("resourceBinding",{}))
                 and budget.get("toolCalls", 0) <= share["toolBudget"] and budget.get("modelCalls", 0) <= share["modelBudget"],
                 "Delegated request exceeds A's non-overlapping exact target share")
        expected = "" if previous is None else hashlib.sha256(protocol.canonical_bytes(previous)).hexdigest()
        _require(proof["previousProofDigest"] == expected, "Delegation proof chain changed")
        if previous is not None:
            for scope in ("targetResources", "capabilities", "toolNames"):
                allowed = {protocol.canonical_bytes(value) for value in previous[scope]}
                _require(all(protocol.canonical_bytes(value) in allowed for value in proof[scope]), "Delegation scope expanded")
            _require(proof["deadline"] <= previous["deadline"] and proof["toolBudget"] <= previous["toolBudget"] and proof["modelBudget"] <= previous["modelBudget"] and proof["remainingDepth"] < previous["remainingDepth"], "Delegation limits expanded")
        previous = proof
    _require(previous is not None and previous["parentTaskId"] == request.get("parentTaskId"), "Delegation parent changed")
