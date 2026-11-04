#!/usr/bin/env python3
"""Migrate public protocol fixtures after an explicitly reviewed profile change.

Only fixtures.json is written. The signed pairing corpus gets a NEW in-memory
P-256 test key/certificate; its private key is never persisted or printed and
never represents a real paired device. Negative cases retain their exact old
semantic mutations and are re-signed, so failures cannot hide behind stale
signatures. Already-current fixtures are unchanged. Run protocol tests afterward.
"""
import base64
import copy
import datetime
import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace

DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORY.parents[1] / "companion"))

import device_identity as identity
import device_pairing as pairing
import device_protocol as protocol
from verify_profile import verify


def _refresh(value, old_profile, new_profile, digests):
    if isinstance(value, str):
        return value.replace(old_profile, new_profile)
    if isinstance(value, list):
        return [_refresh(item, old_profile, new_profile, digests) for item in value]
    if not isinstance(value, dict):
        return value
    # Preserve deliberately missing or incorrect request digests. Invalid JSON
    # numbers/shape must still fail for their original reason, before hashing.
    old_digest = None
    if "method" in value and "arguments" in value and "argumentsDigest" in value:
        try:
            candidate = protocol.arguments_digest(value)
            if candidate == value["argumentsDigest"]:
                old_digest = candidate
        except protocol.ProtocolError:
            pass
    result = {key: _refresh(item, old_profile, new_profile, digests) for key, item in value.items()}
    if old_digest is not None:
        result["argumentsDigest"] = protocol.arguments_digest(result)
        digests[old_digest] = result["argumentsDigest"]
    return result


def _replace_exact(value, replacements):
    if isinstance(value, str):
        return replacements.get(value, value)
    if isinstance(value, list):
        return [_replace_exact(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: _replace_exact(item, replacements) for key, item in value.items()}
    return value


def _apply_original_delta(before, after, target):
    """Replay each negative case's typed difference against the migrated base."""
    if not isinstance(before, dict) or not isinstance(after, dict):
        return copy.deepcopy(after) if type(before) is not type(after) or before != after else target
    result = copy.deepcopy(target)
    for key in before.keys() | after.keys():
        if key in {"signature", "certificateDer"}:
            continue
        if key not in after:
            result.pop(key, None)
        elif key not in before:
            result[key] = copy.deepcopy(after[key])
        else:
            result[key] = _apply_original_delta(before[key], after[key], result.get(key))
    return result


def _test_signer():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PocketWorld PUBLIC TEST FIXTURE - NOT A DEVICE")])
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                   .serial_number(x509.random_serial_number())
                   .not_valid_before(datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc))
                   .not_valid_after(datetime.datetime(2040, 1, 1, tzinfo=datetime.timezone.utc))
                   .sign(key, hashes.SHA256()))
    der = certificate.public_bytes(serialization.Encoding.DER)
    return SimpleNamespace(certificate_der=der, device_id=identity.device_id(der),
                           certificate_fingerprint=identity.certificate_fingerprint(der),
                           sign=lambda raw: key.sign(raw, ec.ECDSA(hashes.SHA256())))


def _sign_existing(signer, record):
    # Do not normalize protocolProfile: one negative record intentionally has a
    # valid signature from this signer over an UNKNOWN profile.
    body = {key: value for key, value in record.items() if key != "signature"}
    record["signature"] = base64.b64encode(signer.sign(protocol.canonical_bytes(body))).decode("ascii")


def _pairing_corpus(original, migrated):
    if not original or original[0].get("valid") is not True:
        raise ValueError("Original valid signed pairing base is required")
    signer = _test_signer()
    old_base = original[0]
    base = _replace_exact(migrated[0], {
        old_base["transcript"]["inviterDeviceId"]: signer.device_id,
        old_base["transcript"]["inviterFingerprint"]: signer.certificate_fingerprint,
    })
    base["certificateDer"] = base64.b64encode(signer.certificate_der).decode("ascii")
    transcript, completion = base["transcript"], base["completion"]
    transcript_digest = hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest()
    completion["candidateDigest"] = transcript_digest
    receipt, grant = completion["pairingReceipt"], completion["grantReceipt"]
    receipt["localConfirmation"] = {"deviceId": signer.device_id, "transcript": copy.deepcopy(transcript),
                                    "signature": base64.b64encode(signer.sign(protocol.canonical_bytes(transcript))).decode("ascii")}
    commit = receipt["commitReceipt"]["body"]
    commit["transcriptDigest"] = transcript_digest
    commit["grantDigest"] = pairing._pairing_grant_digest(grant["body"]["grant"])
    rows = []
    for old in original:
        row = _apply_original_delta(old_base, old, base)
        completion, transcript = row["completion"], row["transcript"]
        _sign_existing(signer, completion["grantReceipt"])
        if "commitReceipt" in completion["pairingReceipt"]:
            _sign_existing(signer, completion["pairingReceipt"]["commitReceipt"])
        pairing._verify_record_signature(signer.certificate_der, completion["grantReceipt"], "PairGrantReceipt")
        if "commitReceipt" in completion["pairingReceipt"]:
            pairing._verify_record_signature(signer.certificate_der, completion["pairingReceipt"]["commitReceipt"], "PairingCommitReceipt")
        pairing._verify_confirmation_certificate(completion["pairingReceipt"]["localConfirmation"], signer.certificate_der, transcript)
        try:
            pairing._verify_committed_completion(signer.certificate_der, completion, transcript, row["nowMs"])
            accepted = True
        except (protocol.ProtocolError, PermissionError, ValueError):
            accepted = False
        if accepted is not row["valid"]:
            raise ValueError("Pairing fixture's original failure/success changed: " + row["name"])
        rows.append(row)
    return rows


def main():
    checked = verify()
    path = DIRECTORY / "fixtures.json"
    original = json.loads(path.read_text(encoding="utf-8"))
    old_profile = original["profileManifest"]["profileId"]
    if old_profile == checked["profileId"]:
        print(json.dumps({"status": "already_current", "profileId": checked["profileId"]}))
        return
    digests = {}
    updated = _refresh(original, old_profile, checked["profileId"], digests)
    updated = _replace_exact(updated, digests)
    updated["profileManifest"] = json.loads((DIRECTORY / "profile.json").read_text(encoding="utf-8"))
    updated["pairingCommits"] = _pairing_corpus(original["pairingCommits"], updated["pairingCommits"])
    base = updated["pairingCommits"][0]["completion"]
    for row in updated["pairingFinalizeRequests"]:
        request = row["message"]
        if request.get("inviteId") == base["inviteId"] and isinstance(request.get("candidateDigest"), str):
            request["candidateDigest"] = base["candidateDigest"]
    for row in updated["fixtures"]:
        if row["valid"] and row["kind"] != "message":
            request = protocol.parse_request(row["raw"].encode() if row["kind"] == "raw" else protocol.canonical_bytes(row["message"]))
            row["canonical"] = request._encoded.decode("utf-8")
            row["argumentsDigest"] = protocol.arguments_digest(request.json())
            if "receiverTaskId" in row:
                row["receiverTaskId"] = protocol.receiver_task_id(request)
    temporary = path.with_suffix(".new.json")
    temporary.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    print(json.dumps({"status": "updated", "profileId": checked["profileId"],
                      "pairingCommitFixturesResigned": len(updated["pairingCommits"]), "privateKeyPersisted": False}))


if __name__ == "__main__":
    main()
