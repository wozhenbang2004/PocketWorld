"""New signed device-only setup: narrow inbound disclosure, independent of outbound grants.

The local owner's original signed PairGrant, not a client flag/description or
the existence of a TLS relationship, selects this receiver policy.
"""
from datetime import datetime
import re
import uuid

import device_protocol as protocol
from platform_backend import PlatformError

KEY = "requiredDiscoveryProfile"
PROFILE = "windows-owner-scoped-discovery-v1"
OPTION = "owner.pair-identity-only"
PREFIX = "wdi1_"
FULL = "wdf1_"
BINDING = {"screenMode": "capabilities"}


def fenced(config):
    return isinstance(config, dict) and isinstance(config.get("devices"), dict) and config["devices"].get(KEY) == PROFILE


def offer_allowed(installed, value, identity):
    # Only the NEW installed initialization with an original private witness opts in.
    if installed is None or not fenced(value): return False
    from windows_first_workspace import MARKER, PIN, SETUP, recorded, signed
    from windows_installation import _object
    try:
        installed.check_current_pointer()
        if not signed(installed): return False
        raw = installed.backend.read_optional_private_bytes(installed.state, SETUP, installed.sid, maximum=2048)
        if not isinstance(raw, bytes): return False
        witness = _object(raw)
        if MARKER in value:
            if witness != value[MARKER] or value.get("environments") != []: return False
        else:
            envs = value.get("environments")
            if (not isinstance(envs, list) or len(envs) != 1 or not isinstance(envs[0], dict) or PIN not in envs[0]
                    or envs[0].get("id") != witness.get("environmentId") or not recorded(installed, envs[0])): return False
        return (isinstance(witness.get("environmentId"), str) and re.fullmatch(r"folder-[a-f0-9]{32}", witness["environmentId"]) is not None
                and set(witness) == {"installId", "signer", "deviceId", "instanceId", "environmentId", KEY}
                and witness[KEY] == PROFILE and witness["installId"] == installed.install_state["installId"]
                and witness["signer"] == installed.current["signerThumbprint"].upper()
                and witness["deviceId"] == identity.device_id and witness["instanceId"] == identity.instance_id)
    except (OSError, TypeError, ValueError, PlatformError):
        return False


def option():
    # Local owner chooser only; never a tool to advertise on the wire.
    return {"id": OPTION, "version": PROFILE, "resources": [dict(BINDING)], "toolDeclarations": []}


def alone(ids):
    if OPTION not in ids: return False
    if ids != [OPTION]: raise PlatformError("仅配对不能与桌面、工作区或其他范围合并。")
    return True


def grant_id(narrow, supported):
    return ((PREFIX if narrow else FULL) if supported else "owner-") + uuid.uuid4().hex


def exact(g):
    if not isinstance(g, dict) or not isinstance(g.get("grantId"), str): return False
    return (re.fullmatch(PREFIX + r"[a-f0-9]{32}", g["grantId"]) is not None
            and g.get("capabilities") == ["device.describe"] and g.get("toolNames") == []
            and g.get("resources") == [BINDING] and g.get("allowedOrigins") == [g.get("peerDeviceId")]
            and g.get("allowDelegation") is False and type(g.get("maxDelegationDepth")) is int and g["maxDelegationDepth"] == 0
            and all(type(g.get(k)) is int and g[k] == 0 for k in ("toolBudget", "modelBudget"))
            and g.get("revoked", False) is False and "forwardingScope" not in g and "historyRead" not in g)


def guard(selected, grant):
    if not alone([item["id"] for item in selected]): return
    if selected != [option()] or not exact(grant): raise PlatformError("实际待签授权不是仅配对，未确认。")


def summary(g):
    end = datetime.fromtimestamp(g["expiresAt"] / 1000).astimezone().strftime("%Y-%m-%d %H:%M:%S %z")
    return (f"只让手机核对这台 Windows 的设备身份和它自己这份签署回执，有效至 {end}。\r\n"
            f"手机：{g['peerDeviceId']}\r\n不公开工作区、工具目录、运行或屏幕状态；不许查文件、操作历史、观看或接管桌面。"
            "\r\n工具和模型均为 0 次；不转授。Windows 若想把工作交给手机，仍须手机另外明确批准。\r\n")


def restricted(receiver, permission, original_peer):
    """Return (minimal result, release guard), or None for a positively identified ordinary pair."""
    from device_pairing import validate_grant, _verify_record_certificate
    if receiver.manager.platform.name != "windows": return None
    store, local = receiver.trust, receiver.identity
    peer = permission.caller_device_id
    def unavailable(): raise PermissionError("当前没有可核实的本机设备披露授权")
    receiver._current_discovery(permission)
    # Include retired identities and revoked prior grants. A rotation cannot elect a broader policy.
    with store.lock:
        known = store.db.execute("""SELECT CASE WHEN EXISTS(SELECT 1 FROM device_grants WHERE grant_id GLOB ?)
                THEN EXISTS(WITH RECURSIVE lineage(id) AS (SELECT ? UNION
                    SELECT r.old_device_id FROM device_identity_rotations r JOIN lineage l ON r.new_device_id=l.id
                    WHERE r.old_device_id<>r.new_device_id)
                    SELECT 1 FROM lineage l JOIN device_grants g ON g.peer_device_id=l.id
                    WHERE g.local_device_id=? AND g.grant_id GLOB ?) ELSE 0 END""",
                (PREFIX + "*", peer, local.device_id, PREFIX + "*")).fetchone()
        if known is None: unavailable()
        limited = bool(known[0])
        if not limited and not fenced(receiver.manager.config): return None
        now = receiver.manager.now()
        rows = store.grants_for_peer(peer, 2)
        if len(rows) != 1: unavailable()
        grant = rows[0]
        if (grant.get("authorizationEpoch") != permission.authorization_epoch or grant.get("localDeviceId") != local.device_id
                or grant.get("peerDeviceId") != peer or grant.get("revoked") is not False):
            unavailable()
        if not limited:
            # Only a grant issued in this new setup's separate explicit ordinary-scope review selects the original catalog.
            if not isinstance(grant.get("grantId"), str) or not re.fullmatch(FULL + r"[a-f0-9]{32}", grant["grantId"]): unavailable()
            validate_grant(grant, local.device_id, peer, now, for_history=True)
            return None
        if not fenced(receiver.manager.config) or not exact(grant): unavailable()
        validate_grant(grant, local.device_id, peer, now)
        original = protocol.canonical_bytes(grant)
        receipts = receiver.pairing.discovery_grant_receipts(local, permission, now)
        if len(receipts) != 1: unavailable()
        item = receipts[0]
        body = _verify_record_certificate(local.certificate_der, item, "PairGrantReceipt")
        if (item.get("issuerDeviceId") != local.device_id or item.get("issuerCertificateFingerprint") != local.certificate_fingerprint
                or body.get("targetInstanceId") != local.instance_id or body.get("issuedAt") != now
                or protocol.canonical_bytes(body["grant"]) != original): unavailable()
        deadline = min(permission.deadline, now + 5000, grant["expiresAt"])
        if deadline <= now: unavailable()
        result = protocol.device_description(local.device_id, local.instance_id, now, deadline, [], [item],
                                             {"disclosureScope": "pair_identity_only"})

    def release(actual_peer):
        if actual_peer is not original_peer or actual_peer.device_id != peer or receiver.manager.now() >= deadline: unavailable()
        receiver._current_discovery(permission)
        current = store.grants_for_peer(peer, 2)
        if (len(current) != 1 or protocol.canonical_bytes(current[0]) != original
                or permission.target_device_id != local.device_id or not fenced(receiver.manager.config) or receiver.manager.now() >= deadline):
            unavailable()
    return result, release
