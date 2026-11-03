"""Receiving-device owner grants, one-use pairing and live revocation fences.

Uses the original Companion operations.sqlite3 connection and lock. It never
opens another task database, starts a model, or accepts JSON as native authority.
"""
from __future__ import annotations

import base64
from contextlib import contextmanager
import hashlib
import json
import secrets
import threading
import uuid

import device_identity as identity
import device_protocol as protocol

_PERMISSION = object()
_CONFIRMATION = object()
MAX_GRANT_MS = 30 * 86_400_000
HISTORY_METHODS = frozenset({"operation.get", "task.get", "task.events", "artifact.describe", "artifact.readChunk"})
PERSISTED_GRANT_PROFILE = "pwp1_e58652456ec50f920a0036626e11782bf0a8805712d018e55f57d2a7c9b97fdb"
# Fixed to that signed profile's methods. Future current-wire methods must never
# give an earlier receipt's unrecognized capability string new authority.
PERSISTED_GRANT_METHODS = frozenset({"device.describe", "tool.invoke", "operation.get", "operation.cancel", "task.submitGoal",
                                     "task.get", "task.events", "task.respond", "task.pause", "task.cancel", "task.acquireControl",
                                     "artifact.describe", "artifact.readChunk"})


def _copy(value):
    return json.loads(protocol.canonical_bytes(value))


def _require(condition, message):
    if not condition:
        raise PermissionError(message)


def validate_grant(grant, local_device_id, peer_device_id, now_ms, *, for_history=False):
    protocol._keys(grant, set("grantId peerDeviceId localDeviceId authorizationEpoch issuedAt expiresAt capabilities toolNames resources allowedOrigins allowDelegation maxDelegationDepth toolBudget modelBudget".split()), {"revoked", "forwardingScope", "historyRead"})
    if "revoked" in grant:
        _require(type(grant["revoked"]) is bool, "Current revoked flag must be boolean")
    protocol.identifier(grant["grantId"], "grantId")
    protocol.device_id(grant["peerDeviceId"], "peerDeviceId"); protocol.device_id(grant["localDeviceId"], "localDeviceId")
    _require(grant["peerDeviceId"] == peer_device_id and grant["localDeviceId"] == local_device_id, "Grant audience mismatch")
    protocol.integer(grant["authorizationEpoch"], "authorizationEpoch", 1)
    issued = protocol.integer(grant["issuedAt"], "issuedAt", 1)
    expires = protocol.integer(grant["expiresAt"], "expiresAt", 1)
    _require(issued <= now_ms and (for_history or now_ms < expires) and 0 < expires - issued <= MAX_GRANT_MS, "Grant expired or exceeds maximum lifetime")
    for key in ("capabilities", "toolNames", "resources", "allowedOrigins"):
        values = grant[key]
        _require(isinstance(values, list) and len(values) <= 128 and (key == "toolNames" or len(values) > 0), "Invalid grant scope")
        for item in values:
            if key == "resources":
                _require(isinstance(item, dict), "Resource must be an exact binding")
            else:
                protocol.text(item, key, 128)
                if key == "allowedOrigins":
                    protocol.device_id(item, key)
        _require(len({protocol.canonical_bytes(item) for item in values}) == len(values), "Duplicate grant scope")
    _require(type(grant["allowDelegation"]) is bool, "Delegation must be explicitly granted")
    depth = protocol.integer(grant["maxDelegationDepth"], "maxDelegationDepth", 0, 2)
    _require(grant["allowDelegation"] or depth == 0, "Unconfirmed delegation depth")
    protocol.integer(grant["toolBudget"], "toolBudget", 0, 400)
    protocol.integer(grant["modelBudget"], "modelBudget", 0, 1000)
    if "historyRead" in grant:
        history = grant["historyRead"]
        protocol._keys(history, {"expiresAt", "methods"})
        end = protocol.integer(history["expiresAt"], "historyRead.expiresAt", 1)
        methods = history["methods"]
        _require(issued < end <= issued + MAX_GRANT_MS and isinstance(methods, list) and 1 <= len(methods) <= 5
                 and all(isinstance(method, str) and method in HISTORY_METHODS for method in methods)
                 and len(set(methods)) == len(methods), "Invalid explicit owner result-read policy")
    protocol.canonical_bytes(grant)
    if "forwardingScope" in grant:
        import delegation_policy
        delegation_policy.validate_forwarding_scope(grant["forwardingScope"])


class DeviceTrust:
    """Identity metadata on the manager's original SQLite connection, not TaskStore duplication."""
    def __init__(self, connection, lock):
        if connection is None or lock is None:
            raise ValueError("Original Companion ledger and owner lock required")
        self.db, self.lock = connection, lock

    def create_schema(self):
        with self._transaction():
            for sql in (
                "CREATE TABLE IF NOT EXISTS device_peers (device_id TEXT PRIMARY KEY,certificate_der BLOB NOT NULL,fingerprint TEXT NOT NULL,instance_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),revoked INTEGER NOT NULL CHECK(revoked IN (0,1)),updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS device_grants (grant_id TEXT PRIMARY KEY,peer_device_id TEXT NOT NULL,local_device_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),expires_at INTEGER NOT NULL,body_json TEXT NOT NULL,revoked INTEGER NOT NULL CHECK(revoked IN (0,1)))",
                "CREATE INDEX IF NOT EXISTS device_grants_peer ON device_grants(peer_device_id)",
                "CREATE TABLE IF NOT EXISTS device_pairing_invites (invite_id TEXT PRIMARY KEY,token_digest TEXT NOT NULL,inviter_device_id TEXT NOT NULL,instance_id TEXT NOT NULL,expected_peer_fingerprint TEXT NOT NULL,expires_at INTEGER NOT NULL,state TEXT NOT NULL CHECK(state IN ('pending','consumed','revoked')),transcript_digest TEXT,peer_device_id TEXT,receipt_json TEXT)",
                "CREATE TABLE IF NOT EXISTS device_revocation_acks (peer_device_id TEXT PRIMARY KEY,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),acknowledged_epoch INTEGER NOT NULL DEFAULT 0 CHECK(acknowledged_epoch>=0),updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS device_outgoing_grants (target_device_id TEXT NOT NULL,grant_id TEXT NOT NULL,receipt_json TEXT NOT NULL,received_at INTEGER NOT NULL,PRIMARY KEY(target_device_id,grant_id))",
                "CREATE TABLE IF NOT EXISTS device_identity_rotations (rotation_id TEXT PRIMARY KEY,old_device_id TEXT NOT NULL,new_device_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL CHECK(authorization_epoch>=1),receipt_json TEXT NOT NULL,created_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS device_pairing_candidates (invite_id TEXT PRIMARY KEY,attempts INTEGER NOT NULL CHECK(attempts>=0),candidate_digest TEXT,offer_digest TEXT,record_json TEXT,state TEXT NOT NULL CHECK(state IN ('checking','presented','confirmed','consumed','rejected')),expires_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)",
            ):
                self.db.execute(sql)

    @contextmanager
    def _transaction(self):
        with self.lock:
            nested = self.db.in_transaction
            savepoint = "device_trust_" + uuid.uuid4().hex
            self.db.execute("SAVEPOINT " + savepoint if nested else "BEGIN IMMEDIATE")
            try:
                yield
                self.db.execute("RELEASE SAVEPOINT " + savepoint) if nested else self.db.commit()
            except BaseException:
                if nested:
                    self.db.execute("ROLLBACK TO SAVEPOINT " + savepoint)
                    self.db.execute("RELEASE SAVEPOINT " + savepoint)
                else:
                    self.db.rollback()
                raise

    def peer(self, device_id):
        with self.lock:
            row = self.db.execute("SELECT certificate_der,fingerprint,instance_id,authorization_epoch,revoked,updated_at FROM device_peers WHERE device_id=?", (device_id,)).fetchone()
            return None if row is None else {"deviceId": device_id, "certificateDer": base64.b64encode(row[0]).decode(), "fingerprint": row[1], "instanceId": row[2], "authorizationEpoch": row[3], "revoked": bool(row[4]), "updatedAt": row[5]}

    def grant(self, grant_id):
        with self.lock:
            row = self.db.execute("SELECT body_json,authorization_epoch,revoked FROM device_grants WHERE grant_id=?", (grant_id,)).fetchone()
            if row is None:
                return None
            grant = json.loads(row[0])
            grant.update(authorizationEpoch=row[1], revoked=bool(row[2]))
            return grant

    def grants_for_peer(self, peer_device_id, limit=16):
        protocol.integer(limit,"limit",1,16)
        with self.lock:
            rows=self.db.execute("SELECT grant_id FROM device_grants WHERE peer_device_id=? AND revoked=0 AND authorization_epoch=(SELECT authorization_epoch FROM device_peers WHERE device_id=?) ORDER BY grant_id LIMIT ?",(peer_device_id,peer_device_id,limit)).fetchall()
            return [self.grant(row[0]) for row in rows]

    def put_invitation(self, value):
        with self._transaction():
            import time
            active = self.db.execute("SELECT COUNT(*) FROM device_pairing_invites WHERE state='pending' AND expires_at>?", (value.get("createdAt", int(time.time() * 1000)),)).fetchone()[0]
            _require(active < 32, "Too many pending local pairing invitations")
            self.db.execute("INSERT INTO device_pairing_invites(invite_id,token_digest,inviter_device_id,instance_id,expected_peer_fingerprint,expires_at,state) VALUES(?,?,?,?,?,?,'pending')", tuple(value[key] for key in ("inviteId", "tokenDigest", "inviterDeviceId", "instanceId", "expectedPeerFingerprint", "expiresAt")))

    def _insert_grant(self, grant):
        self.db.execute("INSERT INTO device_grants(grant_id,peer_device_id,local_device_id,authorization_epoch,expires_at,body_json,revoked) VALUES(?,?,?,?,?,?,0)", (grant["grantId"], grant["peerDeviceId"], grant["localDeviceId"], grant["authorizationEpoch"], grant["expiresAt"], protocol.canonical_bytes(grant).decode()))

    def install_confirmed_peer(self, invite_id, transcript, peer, grant, receipt, now_ms):
        digest = hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest()
        with self._transaction():
            review=self.local_pairing_offer(invite_id)
            review_state=self.local_pairing_offer_state(invite_id)
            if review is not None:
                _require(review_state in ("confirmed", "consumed") and review["candidateDigest"]==digest
                         and review["reviewedOwnerGrantDigest"]==_pairing_grant_digest(grant), "Original local offer was abandoned or its scope changed before reciprocal install")
            row = self.db.execute("SELECT transcript_digest,peer_device_id,receipt_json FROM device_pairing_invites WHERE invite_id=?", (invite_id,)).fetchone()
            if row is not None:
                _require(row[0] == digest and row[1] == peer["deviceId"], "Reciprocal invitation was already consumed")
                return json.loads(row[2])
            _require(review is None or review_state=="confirmed", "Missing original reciprocal commit cannot be inferred from a consumed review")
            _require(self.peer(peer["deviceId"]) is None, "Existing peer requires grant change or rotation")
            self.db.execute("INSERT INTO device_peers VALUES(?,?,?,?,1,0,?)", (peer["deviceId"], base64.b64decode(peer["certificateDer"], validate=True), peer["fingerprint"], peer["instanceId"], now_ms))
            self._insert_grant(grant)
            self.db.execute("UPDATE device_pairing_candidates SET state='consumed',updated_at=? WHERE invite_id=? AND attempts=0", (now_ms, invite_id))
            self.db.execute("INSERT INTO device_pairing_invites VALUES(?,?,?,?,?,?,'consumed',?,?,?)", (invite_id, transcript["tokenDigest"], transcript["inviterDeviceId"], transcript["inviterInstanceId"], peer["fingerprint"], transcript["expiresAt"], digest, peer["deviceId"], protocol.canonical_bytes(receipt).decode()))
            return _copy(receipt)

    def put_outgoing_grant(self, target_device_id, grant_id, receipt, now_ms):
        with self._transaction():
            self.db.execute("INSERT OR REPLACE INTO device_outgoing_grants VALUES(?,?,?,?)", (target_device_id, grant_id, protocol.canonical_bytes(receipt).decode(), now_ms))

    def outgoing_grant(self, target_device_id, grant_id):
        with self.lock:
            row = self.db.execute("SELECT receipt_json FROM device_outgoing_grants WHERE target_device_id=? AND grant_id=?", (target_device_id, grant_id)).fetchone()
            return None if row is None else json.loads(row[0])

    def rotate_peer(self, previous, replacement, receipt, now_ms):
        with self._transaction():
            old_id, new_id = previous["deviceId"], replacement["deviceId"]
            actual = self.peer(old_id)
            _require(actual is not None and actual["authorizationEpoch"] == previous["authorizationEpoch"] and actual["fingerprint"] == previous["fingerprint"] and actual["revoked"] is previous["revoked"], "Original identity changed before rotation")
            _require(old_id == new_id or self.peer(new_id) is None, "Replacement is already a separate paired device")
            if receipt["confirmation"] == "local_owner":
                _require(receipt["expectedEpoch"] == actual["authorizationEpoch"] and receipt["expectedRevoked"] is actual["revoked"]
                         and receipt["issuedAt"] <= now_ms < receipt["expiresAt"] and receipt["rotationId"] == receipt["confirmationId"],
                         "Native rotation confirmation expired or was superseded")
                _require(self.db.execute("SELECT 1 FROM device_identity_rotations WHERE rotation_id=?", (receipt["confirmationId"],)).fetchone() is None,
                         "Native rotation confirmation already consumed")
            self.db.execute("UPDATE device_peers SET authorization_epoch=?,revoked=1,updated_at=? WHERE device_id=?", (replacement["authorizationEpoch"], now_ms, old_id))
            self.db.execute("UPDATE device_grants SET revoked=1 WHERE peer_device_id=?", (old_id,))
            values = (base64.b64decode(replacement["certificateDer"], validate=True), replacement["fingerprint"], replacement["instanceId"], replacement["authorizationEpoch"], now_ms, new_id)
            if old_id == new_id:
                self.db.execute("UPDATE device_peers SET certificate_der=?,fingerprint=?,instance_id=?,authorization_epoch=?,revoked=0,updated_at=? WHERE device_id=?", values)
            else:
                self.db.execute("INSERT INTO device_peers(certificate_der,fingerprint,instance_id,authorization_epoch,revoked,updated_at,device_id) VALUES(?,?,?,?,0,?,?)", values)
            self.db.execute("INSERT INTO device_identity_rotations VALUES(?,?,?,?,?,?)", (receipt["rotationId"], old_id, new_id, replacement["authorizationEpoch"], protocol.canonical_bytes(receipt).decode(), now_ms))
            self.db.execute("INSERT OR REPLACE INTO device_revocation_acks VALUES(?,?,0,?)", (old_id, replacement["authorizationEpoch"], now_ms))
            return _copy(receipt)

    def consume_invitation(self, invite_id, token_digest, transcript_digest, peer, grant, receipt, now_ms):
        with self._transaction():
            row = self.db.execute("SELECT token_digest,inviter_device_id,instance_id,expected_peer_fingerprint,expires_at,state,transcript_digest,peer_device_id,receipt_json FROM device_pairing_invites WHERE invite_id=?", (invite_id,)).fetchone()
            _require(row is not None and secrets.compare_digest(row[0], token_digest) and row[1] == grant["localDeviceId"], "Invitation belongs to another device")
            if row[5] == "consumed":
                _require(row[6] == transcript_digest and row[7] == peer["deviceId"], "Invitation already consumed")
                return json.loads(row[8])
            _require(row[5] == "pending" and row[4] > now_ms and (not row[3] or row[3] == peer["fingerprint"]), "Invitation expired, revoked or for another peer")
            candidate = self.candidate(invite_id)
            _require(candidate is None or not candidate["candidateDigest"] or candidate["candidateDigest"] == transcript_digest and candidate["state"] == "confirmed", "The one-time candidate has not been natively confirmed")
            _require(self.peer(peer["deviceId"]) is None, "Existing device requires explicit grant change or rotation")
            self.db.execute("INSERT INTO device_peers VALUES(?,?,?,?,1,0,?)", (peer["deviceId"], base64.b64decode(peer["certificateDer"], validate=True), peer["fingerprint"], peer["instanceId"], now_ms))
            self._insert_grant(grant)
            changed = self.db.execute("UPDATE device_pairing_invites SET state='consumed',transcript_digest=?,peer_device_id=?,receipt_json=? WHERE invite_id=? AND state='pending'", (transcript_digest, peer["deviceId"], protocol.canonical_bytes(receipt).decode(), invite_id)).rowcount
            _require(changed == 1, "Invitation concurrently consumed")
            self.db.execute("UPDATE device_pairing_candidates SET state='consumed',updated_at=? WHERE invite_id=?", (now_ms, invite_id))
            return _copy(receipt)

    def commit_candidate_completion(self, invite_id, token_digest, transcript_digest, expected_revision, peer, grant, receipt, completion, now_ms):
        """One ORIGINAL transaction: invitation/review CAS + peer/grant + immutable result."""
        with self._transaction():
            known = self.candidate(invite_id)
            _require(known is not None and known["candidateDigest"] == transcript_digest, "Original candidate changed")
            if known["state"] == "consumed":
                _require(known.get("committedCompletion") is not None and known["peerDeviceId"] == peer["deviceId"], "No immutable original completion")
                return _copy(known["committedCompletion"])
            _require(known["state"] == "confirmed" and known.get("confirmationRevision") == expected_revision
                     and known.get("reviewedOwnerGrantDigest") == _pairing_grant_digest(grant)
                     and protocol.canonical_bytes(known.get("localConfirmation")) == protocol.canonical_bytes(receipt["localConfirmation"]),
                     "Native owner confirmation revision or exact scope changed before commit")
            validate_grant(grant, grant["localDeviceId"], peer["deviceId"], now_ms)
            wrapped = _copy(receipt)
            wrapped["_immutableCompletion"] = _copy(completion)
            self.consume_invitation(invite_id, token_digest, transcript_digest, peer, grant, wrapped, now_ms)
            return _copy(completion)

    def committed_completion(self, invite_id):
        value = self.candidate(invite_id)
        return None if value is None or value["state"] != "consumed" else _copy(value.get("committedCompletion"))

    def save_local_pairing_offer(self, record, now_ms):
        """Public native-reviewed recovery facts only. NEVER stores invitation token."""
        with self._transaction():
            known = self.local_pairing_offer(record["inviteId"])
            if known is not None:
                _require(self.local_pairing_offer_state(record["inviteId"]) in ("confirmed", "consumed"), "Original local offer was abandoned")
                _require(protocol.canonical_bytes(known) == protocol.canonical_bytes(record), "Original local offer cannot be replaced")
                return
            existing = self.db.execute("SELECT 1 FROM device_pairing_candidates WHERE invite_id=?", (record["inviteId"],)).fetchone()
            _require(existing is None and record.get("role") == "local_offer", "Invitation ID already belongs to another onboarding role")
            count = self.db.execute("SELECT COUNT(*) FROM device_pairing_candidates WHERE state='confirmed' AND (attempts=0 OR expires_at>?)", (now_ms,)).fetchone()[0]
            _require(count < 8, "Too many original pending local offers")
            self.db.execute("INSERT INTO device_pairing_candidates VALUES(?,0,?,?,?,'confirmed',?,?)", (record["inviteId"], record["candidateDigest"], record["offerDigest"], protocol.canonical_bytes(record).decode(), record["publicInvitation"]["expiresAt"], now_ms))

    def committed_pairing_candidates(self, limit=8):
        protocol.integer(limit, "limit", 1, 8)
        with self.lock:
            rows=self.db.execute("SELECT c.invite_id FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.state='consumed' AND i.state='consumed' AND c.attempts>0 ORDER BY c.updated_at DESC,c.invite_id LIMIT ?", (limit,)).fetchall()
            return [self.candidate(row[0]) for row in rows]

    def local_pairing_offer(self, invite_id):
        with self.lock:
            row = self.db.execute("SELECT record_json FROM device_pairing_candidates WHERE invite_id=?", (invite_id,)).fetchone()
            if row is None or row[0] is None:
                return None
            record = json.loads(row[0])
            return record if record.get("role") == "local_offer" else None

    def local_pairing_offer_state(self, invite_id):
        with self.lock:
            row=self.db.execute("SELECT state,attempts,record_json FROM device_pairing_candidates WHERE invite_id=?", (invite_id,)).fetchone()
            return row[0] if row is not None and row[1]==0 and row[2] is not None and json.loads(row[2]).get("role")=="local_offer" else None

    def abandon_local_pairing_offer(self, invite_id, candidate_digest, record_digest, now_ms):
        with self._transaction():
            record=self.local_pairing_offer(invite_id);state=self.local_pairing_offer_state(invite_id)
            _require(record is not None and record["candidateDigest"]==candidate_digest
                     and hashlib.sha256(protocol.canonical_bytes(record)).hexdigest()==record_digest,
                     "Original local owner-reviewed offer changed")
            _require(state in ("confirmed", "rejected"), "This local pairing already applied; use explicit owner revocation, not pending abandonment")
            if state=="rejected":return
            changed=self.db.execute("UPDATE device_pairing_candidates SET state='rejected',updated_at=? WHERE invite_id=? AND attempts=0 AND state='confirmed'", (now_ms,invite_id)).rowcount
            _require(changed==1,"Local pairing changed before abandonment")

    def local_pairing_offers(self, limit=8):
        protocol.integer(limit, "limit", 1, 8)
        with self.lock:
            rows = self.db.execute("SELECT record_json FROM device_pairing_candidates WHERE state='confirmed' AND attempts=0 ORDER BY updated_at,invite_id LIMIT ?", (limit,)).fetchall()
            return [json.loads(row[0]) for row in rows if json.loads(row[0]).get("role") == "local_offer"]

    def revoke(self, peer_device_id, now_ms):
        with self._transaction():
            peer = self.peer(peer_device_id)
            _require(peer is not None, "Device is not paired")
            epoch = protocol.integer(peer["authorizationEpoch"] + 1, "authorizationEpoch", 1)
            self.db.execute("UPDATE device_peers SET authorization_epoch=?,revoked=1,updated_at=? WHERE device_id=?", (epoch, now_ms, peer_device_id))
            self.db.execute("UPDATE device_grants SET revoked=1 WHERE peer_device_id=?", (peer_device_id,))
            self.db.execute("INSERT OR REPLACE INTO device_revocation_acks VALUES(?,?,0,?)", (peer_device_id, epoch, now_ms))
            return epoch

    def replace_grant(self, grant, now_ms):
        with self._transaction():
            peer_id = grant["peerDeviceId"]
            peer = self.peer(peer_id)
            _require(peer is not None and not peer["revoked"], "Device must be paired before changing grants")
            epoch = protocol.integer(peer["authorizationEpoch"] + 1, "authorizationEpoch", 1)
            _require(grant["authorizationEpoch"] == epoch, "Grant must use the next original device epoch")
            validate_grant(grant, grant["localDeviceId"], peer_id, now_ms)
            self.db.execute("UPDATE device_peers SET authorization_epoch=?,updated_at=? WHERE device_id=?", (epoch, now_ms, peer_id))
            self.db.execute("DELETE FROM device_grants WHERE grant_id=? AND peer_device_id=?", (grant["grantId"], peer_id))
            self._insert_grant(grant)

    def acknowledge_revocation(self, peer_device_id, epoch, now_ms):
        with self._transaction():
            changed = self.db.execute("UPDATE device_revocation_acks SET acknowledged_epoch=?,updated_at=? WHERE peer_device_id=? AND authorization_epoch=?", (epoch, now_ms, peer_device_id, epoch)).rowcount
            _require(changed == 1, "Acknowledgement is not for the current revocation epoch")

    def revocation_status(self, peer_device_id):
        with self.lock:
            row = self.db.execute("SELECT authorization_epoch,acknowledged_epoch,updated_at FROM device_revocation_acks WHERE peer_device_id=?", (peer_device_id,)).fetchone()
            return {"peerDeviceId": peer_device_id, "locallyRevoked": row is not None, "peerAcknowledged": row is not None and row[0] == row[1], **({"authorizationEpoch": row[0], "acknowledgedEpoch": row[1], "updatedAt": row[2]} if row else {})}

    def candidate(self, invite_id):
        with self.lock:
            row = self.db.execute("SELECT c.candidate_digest,c.offer_digest,c.record_json,c.state,c.expires_at,c.attempts,i.receipt_json FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.invite_id=?", (invite_id,)).fetchone()
            if row is None:
                return None
            value = {} if row[2] is None else json.loads(row[2])
            value.update(candidateDigest=row[0] or "", offerDigest=row[1] or "", state=row[3], expiresAt=row[4], attempts=row[5])
            if row[6] is not None:
                stored = json.loads(row[6])
                value["pairingReceipt"] = {key: item for key, item in stored.items() if key != "_immutableCompletion"}
                if "_immutableCompletion" in stored:
                    value["committedCompletion"] = stored["_immutableCompletion"]
            return value

    def begin_candidate_check(self, invite_id, token_digest, offer_digest, now_ms):
        with self._transaction():
            row = self.db.execute("SELECT token_digest,inviter_device_id,instance_id,expected_peer_fingerprint,expires_at,state,receipt_json FROM device_pairing_invites WHERE invite_id=?", (invite_id,)).fetchone()
            _require(row is not None and secrets.compare_digest(row[0], token_digest), "Pairing invitation is unavailable")
            invitation = {"inviteId": invite_id, "inviterDeviceId": row[1], "instanceId": row[2], "expectedPeerFingerprint": row[3], "expiresAt": row[4], "state": row[5]}
            if row[6] is not None:
                invitation["pairingReceipt"] = json.loads(row[6])
            known = self.candidate(invite_id)
            if known is not None and known["candidateDigest"] and known["offerDigest"] == offer_digest:
                _require(row[5] != "pending" or row[4] > now_ms, "Pairing invitation expired")
                invitation["cachedCandidate"] = known
                return invitation
            _require(row[5] == "pending" and row[4] > now_ms, "Pairing invitation expired or consumed")
            attempts = 0 if known is None else known["attempts"]
            _require(attempts < 8, "Pairing signature attempts exhausted")
            if known is None:
                active = self.db.execute("SELECT COUNT(*) FROM device_pairing_candidates WHERE state IN ('checking','presented','confirmed') AND expires_at>?", (now_ms,)).fetchone()[0]
                _require(active < 8, "Too many active pairing candidates")
                self.db.execute("INSERT INTO device_pairing_candidates VALUES(?,1,NULL,NULL,NULL,'checking',?,?)", (invite_id, row[4], now_ms))
            else:
                self.db.execute("UPDATE device_pairing_candidates SET attempts=?,updated_at=? WHERE invite_id=?", (attempts + 1, now_ms, invite_id))
            return invitation

    def save_candidate(self, value, now_ms):
        with self._transaction():
            invite_id = value["inviteId"]
            known = self.candidate(invite_id)
            invitation = self.db.execute("SELECT state FROM device_pairing_invites WHERE invite_id=?", (invite_id,)).fetchone()
            _require(known is not None and known["attempts"] > 0 and known["expiresAt"] > now_ms and invitation is not None and invitation[0] == "pending" and known["state"] in ("checking", "presented", "confirmed"), "Pairing candidate is unavailable")
            _require(not known["candidateDigest"] or known["candidateDigest"] == value["candidateDigest"], "Invitation is bound to another exact candidate")
            if known["candidateDigest"]:
                self.db.execute("UPDATE device_pairing_candidates SET offer_digest=? WHERE invite_id=?", (value["offerDigest"], invite_id))
            else:
                self.db.execute("UPDATE device_pairing_candidates SET candidate_digest=?,offer_digest=?,record_json=?,state='presented',updated_at=? WHERE invite_id=?", (value["candidateDigest"], value["offerDigest"], protocol.canonical_bytes(value).decode(), now_ms, invite_id))
            return self.candidate(invite_id)

    def confirm_candidate(self, invite_id, digest, local_confirmation, now_ms, reviewed_grant_digest=None):
        with self._transaction():
            known = self.candidate(invite_id)
            _require(known is not None and known["candidateDigest"] == digest and known["expiresAt"] > now_ms and known["state"] in ("presented", "confirmed"), "Pairing candidate expired or changed")
            known["localConfirmation"] = _copy(local_confirmation)
            known.pop("reviewedOwnerGrantDigest", None)
            if reviewed_grant_digest is not None:
                _require(isinstance(reviewed_grant_digest, str) and protocol.DIGEST.fullmatch(reviewed_grant_digest), "Invalid native grant review digest")
                known["reviewedOwnerGrantDigest"] = reviewed_grant_digest
            revision = protocol.integer(known.get("confirmationRevision", 0) + 1, "confirmationRevision", 1)
            known["confirmationRevision"] = revision
            self.db.execute("UPDATE device_pairing_candidates SET state='confirmed',record_json=?,updated_at=? WHERE invite_id=?", (protocol.canonical_bytes(known).decode(), now_ms, invite_id))
            return revision

    def reject_candidate(self, invite_id, digest, now_ms):
        with self._transaction():
            known = self.candidate(invite_id)
            _require(known is not None and known["candidateDigest"] == digest and known["state"] != "consumed", "Pairing candidate changed")
            self.db.execute("UPDATE device_pairing_candidates SET state='rejected',updated_at=? WHERE invite_id=?", (now_ms, invite_id))
            self.db.execute("UPDATE device_pairing_invites SET state='revoked' WHERE invite_id=? AND state='pending'", (invite_id,))

    def pending_candidates(self, now_ms):
        with self.lock:
            rows = self.db.execute("SELECT c.invite_id FROM device_pairing_candidates c JOIN device_pairing_invites i ON i.invite_id=c.invite_id WHERE c.state IN ('presented','confirmed') AND c.expires_at>? ORDER BY c.updated_at,c.invite_id LIMIT 8", (now_ms,)).fetchall()
            return [self.candidate(row[0]) for row in rows]

    def peers(self, limit=128):
        protocol.integer(limit, "limit", 1, 128)
        with self.lock:
            return [{"deviceId": row[0], "certificateFingerprint": row[1], "instanceId": row[2], "authorizationEpoch": row[3], "revoked": bool(row[4]), "updatedAt": row[5]} for row in self.db.execute("SELECT device_id,fingerprint,instance_id,authorization_epoch,revoked,updated_at FROM device_peers ORDER BY updated_at DESC,device_id LIMIT ?", (limit,))]


class Permission:
    __slots__ = ("_owner", "_peer", "_envelope", "_grant")

    def __init__(self, proof, owner, peer, envelope, grant):
        if proof is not _PERMISSION:
            raise TypeError("Use receiving-device PairingCoordinator.check_permission")
        self._owner, self._peer, self._envelope, self._grant = owner, peer, envelope, _copy(grant)

    @property
    def envelope(self):
        return self._envelope

    @property
    def grant(self):
        return _copy(self._grant)

    @property
    def resources(self):
        return _copy(self._grant.get("resources", []))

    @property
    def tool_budget(self):
        return self._grant["toolBudget"]

    @property
    def model_budget(self):
        return self._grant["modelBudget"]

    @property
    def grant_expires_at(self):
        return self._grant["expiresAt"]

    @property
    def grant_fingerprint(self):
        return hashlib.sha256(protocol.canonical_bytes(self._grant)).hexdigest()

    @property
    def source_share(self):
        import delegation_policy
        record = delegation_policy.admitted_source_share(self._envelope)
        return None if record is None else SourceShare(_PERMISSION, self, record)

    def check_current(self, now_ms):
        self._owner._evaluate(self._peer, self._envelope, now_ms)

    def reauthorize(self, request, now_ms):
        # The native peer comes from this still-live original TLS handle, not JSON.
        return self._owner.check_permission(self._peer, request, now_ms)


class DiscoveryPermission:
    """Formal paired identity may see its own grants/capability metadata. Never execute."""
    def __init__(self,proof,owner,peer,query,epoch):
        if proof is not _PERMISSION:raise TypeError("Current native paired discovery admission required")
        self._owner,self._peer,self._query,self._epoch=owner,peer,query,epoch
    def check_current(self,now_ms):
        _require(self._owner._evaluate_discovery(self._peer,self._query,now_ms)==self._epoch,"Discovery authorization epoch changed")
    @property
    def caller_device_id(self):return self._peer.device_id
    @property
    def target_device_id(self):return self._owner.local_device_id
    @property
    def authorization_epoch(self):return self._epoch
    @property
    def deadline(self):return self._query.get("deadline")
    @property
    def envelope(self):return self._query


class DiscoveryQuery:
    def __init__(self,proof,owner,target,envelope):
        if proof is not _PERMISSION:raise TypeError("Native paired discovery query required")
        self._owner,self._target,self._envelope=owner,target,envelope
    @property
    def envelope(self):return self._envelope
    def require_target_peer(self,actual,now_ms):
        known=self._owner.store.peer(self._target.device_id)
        _require(now_ms<self._envelope.get("deadline") and isinstance(actual,identity.AuthenticatedPeer)
                 and actual.device_id==self._target.device_id and actual.certificate_fingerprint==self._target.certificate_fingerprint
                 and known is not None and not known["revoked"] and known["fingerprint"]==actual.certificate_fingerprint,"Discovery query target changed or expired")


class NativeHistoryAudienceSource:
    """Native adapter over the ORIGINAL accepted-object SQL, never a network JSON factory.

    Implementations must select by query's fixed original object ID and return the
    immutable audience committed with acceptance, not reconstruct it from a job in
    memory. Missing old audience is local-only. Source withdrawal is separate from
    cancellation/execution expiry and is checked from current native records.
    """
    def accepted_audience(self, query):
        raise NotImplementedError

    def source_visible(self, audience, now_ms):
        return ("sourceShareId" not in audience and audience["originDeviceId"] == audience["callerDeviceId"])


class HistoryPermission:
    """Only a fixed accepted object's read receipt; deliberately NOT Permission."""
    def __init__(self, proof, owner, peer, query, source, accepted):
        if proof is not _PERMISSION:
            raise TypeError("Current native history admission required")
        self._owner, self._peer, self._query, self._source = owner, peer, query, source
        self._accepted = _copy(accepted)

    def check_current(self, now_ms):
        accepted = self._owner._evaluate_history(self._peer, self._query, self._source, now_ms)
        _require(protocol.canonical_bytes(accepted["audience"]) == protocol.canonical_bytes(self._accepted["audience"]),
                 "Original accepted history audience changed")

    @property
    def envelope(self):
        return self._query

    @property
    def audience(self):
        return _copy(self._accepted["audience"])

    @property
    def resource_binding(self):
        return _copy(self._accepted["audience"]["resourceBinding"])

    @property
    def resources(self):
        return [self.resource_binding]

    @property
    def origin_device_id(self):
        return self._peer.device_id

    @property
    def original_origin_device_id(self):
        return self._accepted["audience"]["originDeviceId"]

    @property
    def caller_device_id(self):
        return self._peer.device_id

    @property
    def target_device_id(self):
        return self._owner.local_device_id

    @property
    def grant_id(self):
        return self._accepted["audience"]["grantId"]

    @property
    def authorization_epoch(self):
        return self._query.get("authorizationEpoch")

    @property
    def accepted_authorization_epoch(self):
        return self._accepted["audience"]["authorizationEpoch"]

    @property
    def deadline(self):
        return self._accepted["deadline"]

    @property
    def certificate_fingerprint(self):
        return self._peer.certificate_fingerprint


class OperationManagementPermission:
    """Cancel one original job; NOT a tool/model Permission or a HistoryPermission."""
    def __init__(self, proof, owner, peer, request, source, accepted):
        if proof is not _PERMISSION:raise TypeError("Native fixed-object management admission required")
        self._owner,self._peer,self._request,self._source,self._accepted=owner,peer,request,source,_copy(accepted)
    def check_current(self,now_ms):
        accepted=self._owner._evaluate_history(self._peer,self._request,self._source,now_ms,_management=True)
        _require(protocol.canonical_bytes(accepted["audience"])==protocol.canonical_bytes(self._accepted["audience"]),"Original management audience changed")
    @property
    def envelope(self):return self._request
    @property
    def audience(self):return _copy(self._accepted["audience"])
    @property
    def original_origin_device_id(self):return self._accepted["audience"]["originDeviceId"]
    @property
    def caller_device_id(self):return self._peer.device_id
    @property
    def target_device_id(self):return self._owner.local_device_id
    @property
    def resource_binding(self):return _copy(self._accepted["audience"]["resourceBinding"])
    @property
    def deadline(self):return self._accepted["deadline"]


class SourceShare:
    """Opaque, verified A source share from a current native Permission; never JSON input."""
    def __init__(self, proof, permission, record):
        if proof is not _PERMISSION:
            raise TypeError("Verified native permission required")
        self._permission, self._record = permission, _copy(record)

    def check_current(self, now_ms):
        self._permission.check_current(now_ms)

    @property
    def record(self):
        return _copy(self._record)

    @property
    def share_id(self):
        return self._record["shareId"]

    @property
    def source_allocation_id(self):
        return self._record["sourceAllocationId"]

    @property
    def source_grant_id(self):
        return self._record["sourceGrantId"]

    @property
    def source_authorization_epoch(self):
        return self._record["sourceAuthorizationEpoch"]

    @property
    def source_proof_fingerprint(self):
        return self._record["sourceProofFingerprint"]

    @property
    def issuer_device_id(self):
        return self._record["issuerDeviceId"]

    @property
    def target_device_id(self):
        return self._record["targetDeviceId"]

    @property
    def resource_binding(self):
        return _copy(self._record["resourceBinding"])

    @property
    def tool_budget(self):
        return self._record["toolBudget"]

    @property
    def model_budget(self):
        return self._record["modelBudget"]

    @property
    def deadline(self):
        return self._record["deadline"]


class LocalConfirmation:
    def __init__(self, proof, local, transcript):
        if proof is not _CONFIRMATION:
            raise TypeError("Explicit native local confirmation required")
        self._device_id, self._transcript = local.device_id, _copy(transcript)
        self._signature = local.sign(protocol.canonical_bytes(transcript))

    def wire(self):
        return {"deviceId": self._device_id, "transcript": _copy(self._transcript), "signature": base64.b64encode(self._signature).decode()}


class CandidateOffer:
    def __init__(self, proof, invitation, wire, local_confirmation):
        if proof is not _CONFIRMATION:
            raise TypeError("Native owner-confirmed onboarding factory required")
        self._invitation, self._wire, self._local = _copy(invitation), _copy(wire), local_confirmation
        self.candidate_digest = hashlib.sha256(protocol.canonical_bytes(local_confirmation._transcript)).hexdigest()
        self.inviter_device_id, self.joiner_device_id = invitation["inviterDeviceId"], local_confirmation._device_id
        self._persistence_owner, self._record_digest = None, None

    def _require_original_fence(self):
        if self._persistence_owner is not None:
            try:
                self._persistence_owner._require_local_offer_fence(self._invitation["inviteId"],self.candidate_digest,self._record_digest)
            except PermissionError:
                raise
            except Exception as unavailable:
                raise PermissionError("Original local owner review unavailable") from unavailable

    def wire(self):
        # Contains a short-lived secret: transport only, never prompts/logs/task journals.
        self._require_original_fence()
        return _copy(self._wire)

    def wire_bytes(self):
        return protocol.canonical_bytes(self.wire())


def create_candidate_offer(joiner, invitation, verified_target, owner_confirmed_both_fingerprints, now_ms, *, reviewed_owner_grant=None):
    _require(isinstance(verified_target, identity.VerifiedPairingTarget) and joiner.device_id != verified_target.device_id and len(joiner.certificate_der) <= 8192, "Distinct bounded onboarding identities required")
    transcript = pairing_transcript(invitation, verified_target, joiner.device_id, joiner.instance_id, joiner.certificate_fingerprint, now_ms)
    confirmation = confirm_locally(joiner, transcript, owner_confirmed_both_fingerprints)
    wire = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingCandidate", "inviteId": invitation["inviteId"], "token": invitation["token"], "joinerCertificateDer": base64.b64encode(joiner.certificate_der).decode(), "joinerInstanceId": joiner.instance_id, "confirmation": confirmation.wire()}
    _require(len(protocol.canonical_bytes(wire)) <= protocol.MAX_PAIRING_BYTES, "Onboarding offer exceeds 16384 bytes")
    offer = CandidateOffer(_CONFIRMATION, invitation, wire, confirmation)
    offer._created_at = now_ms
    offer._reviewed_grant = None
    if reviewed_owner_grant is not None:
        validate_grant(reviewed_owner_grant, joiner.device_id, verified_target.device_id, now_ms)
        _require(reviewed_owner_grant["authorizationEpoch"] == 1 and len(protocol.canonical_bytes(reviewed_owner_grant)) <= 8192, "Initial reciprocal grant requires bounded exact scope review")
        offer._reviewed_grant = _copy(reviewed_owner_grant)
    return offer


def confirm_locally(local, transcript, owner_confirmed_both_fingerprints):
    protocol.require_profile(transcript)
    _require(owner_confirmed_both_fingerprints is True and local.device_id in (transcript.get("inviterDeviceId"), transcript.get("joinerDeviceId")), "Both fingerprints require explicit local owner confirmation")
    return LocalConfirmation(_CONFIRMATION, local, transcript)


def validate_invitation_for_preview(invitation, now_ms):
    """Safe scan preview only: no key creation, trust, TLS peer, token use or authorization."""
    value=protocol.parse_json_object(protocol.canonical_bytes(invitation))
    _require(len(protocol.canonical_bytes(value))<=protocol.MAX_PAIRING_BYTES,"Invitation exceeds preview bounds")
    protocol.require_profile(value)
    protocol._keys(value,set("protocolVersion protocolProfile messageType inviteId inviterDeviceId instanceId certificateDer certificateFingerprint token expiresAt".split()),{"connectionHint"})
    _require(value["protocolVersion"]==protocol.VERSION and value["messageType"]=="PairingInvitation","Unsupported pairing invitation")
    protocol.identifier(value["inviteId"],"inviteId");protocol.identifier(value["instanceId"],"instanceId")
    der=base64.b64decode(protocol.text(value["certificateDer"],"certificateDer",11000),validate=True)
    _require(len(der)<=8192 and base64.b64encode(der).decode()==value["certificateDer"] and identity.device_id(der)==value["inviterDeviceId"] and identity.certificate_fingerprint(der)==value["certificateFingerprint"],"Invitation certificate identity or exact pin differs")
    from cryptography import x509
    import datetime
    now=datetime.datetime.fromtimestamp(now_ms/1000,datetime.timezone.utc);cert=x509.load_der_x509_certificate(der)
    _require(cert.not_valid_before_utc<=now<cert.not_valid_after_utc,"Invitation certificate expired")
    expires=protocol.integer(value["expiresAt"],"expiresAt",1)
    _require(0<expires-now_ms<=300000,"Pairing invitation expired")
    token=protocol.text(value["token"],"token",43)
    _require(len(token)==43 and all(c.isascii() and (c.isalnum() or c in "-_") for c in token),"Invalid invitation secret")
    if "connectionHint" in value:
        hint=protocol.validate_connection_hint(value["connectionHint"])
        _require(hint["kind"]!="relay" or hint["inviterDeviceId"]==value["inviterDeviceId"],"Relay hint belongs to another inviter")
    return value


def pairing_transcript(invitation, inviter, joiner_device_id, joiner_instance_id, joiner_fingerprint, now_ms):
    invitation=validate_invitation_for_preview(invitation,now_ms)
    protocol.require_profile(invitation)
    protocol._keys(invitation, set("protocolVersion protocolProfile messageType inviteId inviterDeviceId instanceId certificateDer certificateFingerprint token expiresAt".split()), {"connectionHint"})
    _require(invitation["protocolVersion"] == protocol.VERSION and invitation["messageType"] == "PairingInvitation" and isinstance(inviter, (identity.AuthenticatedPeer, identity.VerifiedPairingTarget)), "Invalid authenticated pairing invitation")
    _require(inviter.device_id == invitation["inviterDeviceId"] and inviter.certificate_fingerprint == invitation["certificateFingerprint"], "Pairing target differs from scanned fingerprint")
    _require(secrets.compare_digest(inviter.certificate_der, base64.b64decode(invitation["certificateDer"], validate=True)), "Scanned certificate changed")
    if "connectionHint" in invitation:
        hint = protocol.validate_connection_hint(invitation["connectionHint"])
        _require(hint["kind"] != "relay" or hint["inviterDeviceId"] == inviter.device_id, "Relay hint belongs to another inviter")
    expires = protocol.integer(invitation["expiresAt"], "expiresAt", 1)
    _require(0 < expires - now_ms <= 300000, "Pairing invitation expired")
    for key in ("inviteId", "instanceId"):
        protocol.identifier(invitation[key], key)
    protocol.device_id(joiner_device_id)
    protocol.identifier(joiner_instance_id, "joinerInstanceId")
    _require(isinstance(joiner_fingerprint, str) and protocol.DIGEST.fullmatch(joiner_fingerprint), "Invalid joining certificate fingerprint")
    token = protocol.text(invitation["token"], "token", 64)
    _require(len(token) == 43 and all(c.isascii() and (c.isalnum() or c in "-_") for c in token), "Invalid pairing invitation secret")
    return {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingTranscript", "inviteId": invitation["inviteId"], "inviterDeviceId": inviter.device_id, "inviterInstanceId": invitation["instanceId"], "inviterFingerprint": inviter.certificate_fingerprint, "joinerDeviceId": joiner_device_id, "joinerInstanceId": joiner_instance_id, "joinerFingerprint": joiner_fingerprint, "tokenDigest": hashlib.sha256(token.encode("ascii")).hexdigest(), "expiresAt": expires}


def verify_confirmation(remote, peer, transcript):
    return _verify_confirmation_certificate(remote, peer.certificate_der, transcript)


def _verify_confirmation_certificate(remote, certificate_der, transcript):
    protocol.require_profile(transcript)
    protocol._keys(remote, {"deviceId", "transcript", "signature"})
    _require(remote["deviceId"] == identity.device_id(certificate_der) and protocol.canonical_bytes(remote["transcript"]) == protocol.canonical_bytes(transcript), "Peer pairing confirmation does not match both devices")
    signature = base64.b64decode(protocol.text(remote["signature"], "signature", 200), validate=True)
    _require(8 <= len(signature) <= 80, "Invalid pairing signature size")
    identity.verify_signature(certificate_der, protocol.canonical_bytes(transcript), signature)


class PairingCandidate:
    """Verified public onboarding data only. Never a peer, Permission or admission."""
    def __init__(self, proof, owner, record):
        if proof is not _CONFIRMATION:
            raise TypeError("Use isolated onboarding inspection")
        protocol.require_profile(record.get("transcript"))
        self._owner, self._record = owner, _copy(record)
        self.invite_id, self.candidate_digest = record["inviteId"], record["candidateDigest"]
        self.device_id, self.certificate_fingerprint = record["peerDeviceId"], record["peerFingerprint"]
        self.expires_at = record["expiresAt"]

    def display(self):
        transcript = self._record["transcript"]
        return {"inviteId": self.invite_id, "inviterDeviceId": transcript["inviterDeviceId"], "inviterFingerprint": transcript["inviterFingerprint"], "joinerDeviceId": self.device_id, "joinerFingerprint": self.certificate_fingerprint, "expiresAt": self.expires_at}


class PendingPairing:
    def __init__(self, proof, owner, candidate, local, confirmation, revision):
        if proof is not _CONFIRMATION:
            raise TypeError("Explicit native two-fingerprint confirmation required")
        self._owner, self._candidate, self._local, self._confirmation = owner, candidate, local, confirmation
        self._revision, self._reviewed_grant = revision, None
        self.invite_id, self.candidate_digest = candidate.invite_id, candidate.candidate_digest
        self.expected_device_id, self.certificate_fingerprint, self.expires_at = candidate.device_id, candidate.certificate_fingerprint, candidate.expires_at

    @property
    def peer_certificate_der(self):
        return base64.b64decode(self._candidate._record["peerCertificateDer"], validate=True)

    def check_current(self, now_ms):
        known = self._owner.store.candidate(self.invite_id)
        _require(now_ms < self.expires_at and known is not None and known.get("confirmationRevision", 0) == self._revision and known["candidateDigest"] == self.candidate_digest and known["state"] in ("confirmed", "consumed"), "Native pairing confirmation expired or changed")


def verify_candidate_receipt(offer, raw):
    _require(isinstance(offer, CandidateOffer) and isinstance(raw, bytes) and len(raw) <= protocol.MAX_PAIRING_BYTES, "Bounded original onboarding receipt required")
    receipt = protocol.parse_json_object(raw)
    protocol.require_profile(receipt)
    protocol._keys(receipt, set("protocolVersion protocolProfile messageType inviteId candidateDigest inviterDeviceId joinerDeviceId expiresAt state".split()), {"confirmation", "pairingReceipt", "currentlyTrusted"})
    _require(receipt["protocolVersion"] == protocol.VERSION and receipt["messageType"] == "PairingCandidateReceipt" and receipt["inviteId"] == offer._invitation["inviteId"] and receipt["candidateDigest"] == offer.candidate_digest and receipt["inviterDeviceId"] == offer.inviter_device_id and receipt["joinerDeviceId"] == offer.joiner_device_id and receipt["expiresAt"] == offer._invitation["expiresAt"] and receipt["state"] in ("pending_owner_confirmation", "confirmed_for_mtls", "paired", "rejected"), "Onboarding receipt belongs to another candidate")
    inviter_der = base64.b64decode(offer._invitation["certificateDer"], validate=True)
    if receipt["state"] == "confirmed_for_mtls":
        _verify_confirmation_certificate(receipt["confirmation"], inviter_der, offer._local._transcript)
    if receipt["state"] == "paired":
        _verify_confirmation_certificate(receipt["pairingReceipt"]["localConfirmation"], inviter_der, offer._local._transcript)
        _require(type(receipt["currentlyTrusted"]) is bool, "Historical pairing must report current local trust separately")
    return receipt


def parse_pairing_finalize(raw):
    """Closed bounded codec ONLY. It never creates a native confirmation or permission."""
    if not isinstance(raw, bytes) or len(raw)>4096:
        protocol._fail("limit_exceeded", "Pairing finalize exceeds 4096 bytes")
    value=protocol.parse_json_object(raw)
    protocol.require_profile(value)
    protocol._keys(value,set("protocolVersion protocolProfile messageType inviteId candidateDigest purpose".split()))
    if value["protocolVersion"]!=protocol.VERSION or value["messageType"]!="PairingFinalize" or value["purpose"] not in ("commit","recover"):
        protocol._fail("invalid_request","Unknown pairing finalize purpose")
    protocol.identifier(value["inviteId"],"inviteId")
    if not isinstance(value["candidateDigest"],str) or not protocol.DIGEST.fullmatch(value["candidateDigest"]):
        protocol._fail("invalid_request","Invalid original candidate digest")
    return value


def candidate_finalize_request(offer):
    _require(isinstance(offer, CandidateOffer), "Original native offer required")
    offer._require_original_fence()
    return {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingFinalize", "inviteId": offer._invitation["inviteId"], "candidateDigest": offer.candidate_digest, "purpose": "commit"}


def _sign_record(signer, kind, body):
    value = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": kind, "issuerDeviceId": signer.device_id, "issuerCertificateFingerprint": signer.certificate_fingerprint, "body": _copy(body)}
    value["signature"] = base64.b64encode(signer.sign(protocol.canonical_bytes(value))).decode()
    return value


def _verify_record(signer, record, kind):
    return _verify_record_certificate(signer.certificate_der, record, kind)


def _verify_record_certificate(certificate_der, record, kind):
    protocol.require_profile(record)
    return _verify_record_signature(certificate_der, record, kind)


def _verify_record_signature(certificate_der, record, kind):
    # Callers first select their profile policy. Never rewrite the signed bytes
    # to make a persisted record look like a newly received network record.
    protocol._keys(record, {"protocolVersion", "protocolProfile", "messageType", "issuerDeviceId", "issuerCertificateFingerprint", "body", "signature"})
    _require(record["protocolVersion"] == protocol.VERSION and record["messageType"] == kind and record["issuerDeviceId"] == identity.device_id(certificate_der) and record["issuerCertificateFingerprint"] == identity.certificate_fingerprint(certificate_der) and isinstance(record["body"], dict), "Signed record has wrong device or purpose")
    identity.verify_signature(certificate_der, protocol.canonical_bytes({key: value for key, value in record.items() if key != "signature"}), base64.b64decode(record["signature"], validate=True))
    return _copy(record["body"])


def issue_rotation_proof(previous, next_certificate_der, next_instance_id, now_ms, expires_at):
    protocol.identifier(next_instance_id, "instanceId")
    _require(0 < expires_at - now_ms <= 300000, "Rotation proof must be short-lived")
    return _sign_record(previous, "DeviceKeyRotation", {"newDeviceId": identity.device_id(next_certificate_der), "newFingerprint": identity.certificate_fingerprint(next_certificate_der), "newInstanceId": next_instance_id, "issuedAt": now_ms, "expiresAt": expires_at})


class RotationConfirmation:
    def __init__(self, proof, old, replacement, next_instance_id, now_ms, ttl_ms):
        if proof is not _CONFIRMATION:
            raise TypeError("Explicit native local rotation confirmation required")
        self.old_id, self.old_fingerprint = old["deviceId"], old["fingerprint"]
        self.expected_epoch, self.expected_revoked = old["authorizationEpoch"], old["revoked"]
        self.confirmation_id, self.issued_at, self.expires_at = str(uuid.uuid4()), now_ms, now_ms + ttl_ms
        self.new_id, self.new_fingerprint, self.new_instance = replacement.device_id, replacement.certificate_fingerprint, next_instance_id


class HistoryQuery:
    """Native outgoing direct read only; never clears an origin on an executable request."""
    def __init__(self, proof, route, envelope):
        if proof is not _PERMISSION:
            raise TypeError("Use the native outgoing history_query factory")
        self._route, self._envelope = route, envelope

    @property
    def envelope(self):
        return self._envelope

    def require_target_peer(self, actual, now_ms):
        self._route._check_history_current(self._envelope.method, self._envelope.get("resourceBinding"), now_ms)
        _require(now_ms < self._envelope.get("deadline") and isinstance(actual, identity.AuthenticatedPeer)
                 and actual.device_id == self._route.target_device_id
                 and actual.certificate_fingerprint == self._route._target.certificate_fingerprint,
                 "History query expired or belongs to another authenticated target")


class OperationManagementRequest:
    def __init__(self,proof,route,envelope):
        if proof is not _PERMISSION:raise TypeError("Native operation_cancel factory required")
        self._route,self._envelope=route,envelope
    @property
    def envelope(self):return self._envelope
    def require_target_peer(self,actual,now_ms):
        self._route.require_target_peer(actual,now_ms)
        _require(now_ms<self._envelope.get("deadline"),"Operation management request expired")


class HistoryRoute:
    """Read-only restored signed route; cannot be used as an execution OutgoingGrant."""
    def __init__(self, proof, native_route):
        if proof is not _PERMISSION:
            raise TypeError("Use current native paired history_route")
        self._native_route = native_route
        self.target_device_id, self.target_instance_id = native_route.target_device_id, native_route.target_instance_id

    def history_query(self, local, method, object_id, exact_resource, now_ms, ttl_ms=15000, *, options=None):
        return self._native_route.history_query(local, method, object_id, exact_resource, now_ms, ttl_ms, options=options)


class OutgoingGrant:
    """Opaque native route metadata; the remote target still rechecks its own current grant."""
    def __init__(self, proof, owner, target, receipt, body):
        if proof is not _PERMISSION:
            raise TypeError("Use authenticated receiving-device grant receipt")
        self._owner, self._target, self._receipt, self._grant = owner, target, _copy(receipt), _copy(body["grant"])
        self.target_device_id, self.target_instance_id = target.device_id, body["targetInstanceId"]
        self.grant_id, self.authorization_epoch = self._grant["grantId"], self._grant["authorizationEpoch"]
        self.deadline = self._grant["expiresAt"]

    @property
    def grant(self):
        return _copy(self._grant)

    def operation_cancel(self,local,target_operation_id,target_arguments_digest,cancel_operation_id,exact_resource,now_ms,ttl_ms=15000):
        _require(isinstance(local,identity.LocalIdentity) and local.device_id==self._owner.local_device_id,"Current native local identity required")
        protocol.integer(ttl_ms,"managementTtl",1,30000)
        protocol.identifier(target_operation_id,"targetOperationId");protocol.identifier(cancel_operation_id,"cancelOperationId")
        _require(isinstance(target_arguments_digest,str) and protocol.DIGEST.fullmatch(target_arguments_digest),"Use the original SQL/opaque accepted target digest, never a caller authority claim")
        _require(target_operation_id!=cancel_operation_id,"Cancellation must keep its own original control operation identity")
        self.check_current(now_ms)
        _require("operation.cancel" in self._grant["capabilities"] and any(protocol.resource_equal(exact_resource,resource) for resource in self._grant["resources"]),"No current owner management scope")
        value={"protocolVersion":protocol.VERSION, "protocolProfile": protocol.PROFILE,"messageType":"DeviceRequest","method":"operation.cancel","requestId":str(uuid.uuid4()),"operationId":cancel_operation_id,"originDeviceId":local.device_id,"callerDeviceId":local.device_id,"targetDeviceId":self.target_device_id,"instanceId":self.target_instance_id,"grantId":self.grant_id,"authorizationEpoch":self.authorization_epoch,"resourceBinding":_copy(exact_resource),"deadline":now_ms+ttl_ms,"arguments":{"operationId":target_operation_id,"argumentsDigest":target_arguments_digest}}
        value["argumentsDigest"]=protocol.arguments_digest(value)
        return OperationManagementRequest(_PERMISSION,self,protocol.parse_request(protocol.canonical_bytes(value)))

    def _check_history_current(self, method, binding, now_ms):
        known = self._owner.store.peer(self.target_device_id)
        stored = self._owner.store.outgoing_grant(self.target_device_id, self.grant_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"] == self._target.certificate_fingerprint
                 and known["instanceId"] == self.target_instance_id
                 and stored is not None and protocol.canonical_bytes(stored["body"]["grant"]) == protocol.canonical_bytes(self._grant)
                 and stored["body"]["targetInstanceId"] == self.target_instance_id, "Native history route changed or revoked")
        validate_grant(self._grant, self.target_device_id, self._owner.local_device_id, now_ms, for_history=True)
        history = self._grant.get("historyRead", {})
        _require(method in HISTORY_METHODS and (now_ms < self._grant["expiresAt"] and method in self._grant["capabilities"]
                 or now_ms < history.get("expiresAt", 0) and method in history.get("methods", []))
                 and any(protocol.resource_equal(binding, resource) for resource in self._grant["resources"]),
                 "No current signed route policy permits this fixed-result read")

    def history_query(self, local, method, object_id, exact_resource, now_ms, ttl_ms=15000, *, options=None):
        """Construct only a new direct fixed-result query; original identity stays in SQL/receipt."""
        _require(isinstance(local, identity.LocalIdentity) and local.device_id == self._owner.local_device_id,
                 "Current native local identity required")
        protocol.integer(ttl_ms, "queryTtl", 1, 30000)
        protocol.identifier(object_id, "objectId")
        self._check_history_current(method, exact_resource, now_ms)
        args = {} if options is None else _copy(options)
        protocol._keys(args, {"contentDigest", "offset", "length"} if method == "artifact.readChunk" else set(),
                       {"cursor", "limit"} if method == "task.events" else set())
        args["operationId" if method == "operation.get" else "taskId" if method.startswith("task.") else "artifactId"] = object_id
        query = {"protocolVersion":protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType":"DeviceRequest", "method":method,
                 "requestId":str(uuid.uuid4()), "originDeviceId":local.device_id, "callerDeviceId":local.device_id,
                 "targetDeviceId":self.target_device_id, "instanceId":self.target_instance_id,
                 "grantId":self.grant_id, "authorizationEpoch":self.authorization_epoch, "resourceBinding":_copy(exact_resource),
                 "deadline":now_ms+ttl_ms, "arguments":args}
        return HistoryQuery(_PERMISSION, self, protocol.parse_request(protocol.canonical_bytes(query)))

    def require_target_peer(self, actual, now_ms=None):
        """Immediately before write: bind the native ticket to the actual mTLS peer."""
        if now_ms is None:
            import time
            now_ms = int(time.time() * 1000)
        self.check_current(now_ms)
        _require(isinstance(actual, identity.AuthenticatedPeer) and actual.device_id == self.target_device_id
                 and actual.certificate_fingerprint == self._target.certificate_fingerprint,
                 "Outgoing grant belongs to another authenticated target")

    def check_current(self, now_ms):
        known = self._owner.store.peer(self.target_device_id)
        stored = self._owner.store.outgoing_grant(self.target_device_id, self.grant_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"] == self._target.certificate_fingerprint and known["instanceId"] == self.target_instance_id and stored is not None and protocol.canonical_bytes(stored["body"]["grant"]) == protocol.canonical_bytes(self._grant) and stored["body"]["targetInstanceId"] == self.target_instance_id, "Native outgoing route changed or revoked")
        validate_grant(self._grant, self.target_device_id, self._owner.local_device_id, now_ms)


def _pairing_grant_digest(grant):
    # SQL exposes revoked as a separate current fence; it is not part of the
    # originally reviewed scope digest. Every other grant byte is bound.
    value = _copy(grant)
    value.pop("revoked", None)
    return hashlib.sha256(protocol.canonical_bytes(value)).hexdigest()


def _verify_committed_completion(certificate_der, completion, transcript, now_ms):
    protocol.require_profile(completion)
    protocol._keys(completion, set("protocolVersion protocolProfile messageType inviteId candidateDigest executorDeviceId instanceId pairingReceipt grantReceipt".split()))
    protocol.require_profile(transcript)
    protocol._keys(transcript, set("protocolVersion protocolProfile messageType inviteId inviterDeviceId inviterInstanceId inviterFingerprint joinerDeviceId joinerInstanceId joinerFingerprint tokenDigest expiresAt".split()))
    _require(completion["protocolVersion"] == transcript["protocolVersion"] == protocol.VERSION
             and completion["messageType"] == "PairingCompletion" and transcript["messageType"] == "PairingTranscript", "Wrong pairing commit purpose")
    protocol.integer(transcript["expiresAt"], "expiresAt", 1)
    for name in ("inviterDeviceId", "joinerDeviceId"):
        protocol.device_id(transcript[name], name)
    for name in ("inviteId", "inviterInstanceId", "joinerInstanceId"):
        protocol.identifier(transcript[name], name)
    for name in ("inviterFingerprint", "joinerFingerprint", "tokenDigest"):
        _require(isinstance(transcript[name], str) and protocol.DIGEST.fullmatch(transcript[name]), "Invalid complete pairing binding")
    receipt = completion["pairingReceipt"]
    protocol._keys(receipt, set("inviteId peerDeviceId authorizationEpoch grantId localConfirmation status commitReceipt".split()))
    commit = _verify_record_certificate(certificate_der, receipt["commitReceipt"], "PairingCommitReceipt")
    fields = set("commitId committedAt inviteId transcriptDigest grantDigest grantId authorizationEpoch inviterDeviceId inviterInstanceId inviterFingerprint joinerDeviceId joinerInstanceId joinerFingerprint".split())
    protocol._keys(commit, fields)
    protocol.identifier(commit["commitId"], "commitId")
    protocol.integer(commit["authorizationEpoch"], "authorizationEpoch", 1, 1)
    protocol.integer(receipt["authorizationEpoch"], "authorizationEpoch", 1, 1)
    committed_at = protocol.integer(commit["committedAt"], "committedAt", 1)
    digest = hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest()
    grant_body = _verify_record_certificate(certificate_der, completion["grantReceipt"], "PairGrantReceipt")
    protocol._keys(grant_body, {"grant", "targetInstanceId", "issuedAt"})
    protocol.integer(grant_body["issuedAt"], "issuedAt", 1)
    validate_grant(grant_body["grant"], transcript["inviterDeviceId"], transcript["joinerDeviceId"], committed_at)
    _require(grant_body["grant"].get("revoked", False) is False, "Original committed grant cannot be revoked")
    _require(committed_at <= now_ms and committed_at < transcript["expiresAt"]
             and grant_body["grant"]["issuedAt"] <= committed_at == grant_body["issuedAt"]
             and commit["transcriptDigest"] == digest == completion["candidateDigest"]
             and commit["inviteId"] == transcript["inviteId"] == completion["inviteId"] == receipt["inviteId"]
             and commit["grantDigest"] == _pairing_grant_digest(grant_body["grant"])
             and commit["grantId"] == receipt["grantId"] == grant_body["grant"]["grantId"]
             and commit["authorizationEpoch"] == receipt["authorizationEpoch"] == grant_body["grant"]["authorizationEpoch"] == 1
             and receipt["peerDeviceId"] == transcript["joinerDeviceId"] and receipt["status"] == "paired",
             "Immutable pairing commit is not the original in-TTL transaction and exact grant")
    for name in ("inviterDeviceId", "inviterInstanceId", "inviterFingerprint", "joinerDeviceId", "joinerInstanceId", "joinerFingerprint"):
        _require(commit[name] == transcript[name], "Immutable pairing commit changed a confirmed identity")
    _require(identity.device_id(certificate_der) == transcript["inviterDeviceId"] == completion["executorDeviceId"]
             and identity.certificate_fingerprint(certificate_der) == transcript["inviterFingerprint"]
             and completion["instanceId"] == transcript["inviterInstanceId"] == grant_body["targetInstanceId"], "Commit belongs to another server identity")
    _verify_confirmation_certificate(receipt["localConfirmation"], certificate_der, transcript)
    return commit


class LocalOfferAbandonmentReview:
    """Private owner UI selection. It cannot cancel a remote grant or issue authority."""
    def __init__(self,proof,owner,record,expires_at):
        if proof is not _CONFIRMATION:raise TypeError("Native original local offer selection required")
        self._owner,self._record,self._expires_at=owner,_copy(record),expires_at
        self._record_digest=hashlib.sha256(protocol.canonical_bytes(record)).hexdigest()
    def display(self):
        invite=self._record["publicInvitation"]
        return {"inviteId":self._record["inviteId"],"candidateDigest":self._record["candidateDigest"],"inviterDeviceId":invite["inviterDeviceId"],
                "inviterFingerprint":invite["certificateFingerprint"],"createdAt":self._record["createdAt"],"invitationExpiresAt":invite["expiresAt"],
                "originalGrantExpiresAt":self._record["reviewedOwnerGrant"]["expiresAt"],"reviewedGrantDigest":self._record["reviewedOwnerGrantDigest"],
                "localOnly":True,"remoteRevocationAcknowledged":False}


class CommittedPairingRecovery:
    """One short-lived, original committed-result read. NEVER Pending/Permission."""
    def __init__(self, proof, owner, record, completion, expires_at):
        if proof is not _CONFIRMATION:
            raise TypeError("Original consumed SQL pairing transaction required")
        self._owner, self._record, self._completion = owner, _copy(record), _copy(completion)
        self.invite_id, self.candidate_digest = record["inviteId"], record["candidateDigest"]
        self.expected_device_id, self.certificate_fingerprint = record["peerDeviceId"], record["peerFingerprint"]
        self.expires_at = expires_at
    @property
    def peer_certificate_der(self):
        return base64.b64decode(self._record["peerCertificateDer"], validate=True)
    def check_current(self, now_ms):
        _require(now_ms < self.expires_at, "Pairing recovery read deadline expired")
        stored = self._owner.store.committed_completion(self.invite_id)
        _require(stored is not None and protocol.canonical_bytes(stored) == protocol.canonical_bytes(self._completion), "Original immutable commit unavailable or changed")
        self._owner._require_committed_current(stored, now_ms)


class RecoveryOffer:
    """Original local SQL owner review, usable ONLY to recover an already committed peer result."""
    def __init__(self, proof, owner, local, record, confirmation):
        if proof is not _CONFIRMATION:
            raise TypeError("Original native owner-reviewed SQL offer required")
        self._owner, self._local_identity, self._record, self._confirmation = owner, local, _copy(record), confirmation
        self.invite_id, self.candidate_digest = record["inviteId"], record["candidateDigest"]
        self.inviter_device_id = record["publicInvitation"]["inviterDeviceId"]
        self.certificate_fingerprint = record["publicInvitation"]["certificateFingerprint"]
    @property
    def target_certificate_der(self):
        return base64.b64decode(self._record["publicInvitation"]["certificateDer"], validate=True)
    def check_current(self, now_ms):
        self._owner._evaluate_recovery_offer(self, now_ms)
    def require_target_peer(self, actual, now_ms):
        self.check_current(now_ms)
        _require(isinstance(actual, identity.AuthenticatedPeer) and actual.device_id == self.inviter_device_id
                 and actual.certificate_fingerprint == self.certificate_fingerprint, "Recovery target differs from the original reviewed exact pin")


def candidate_recovery_request(offer):
    _require(isinstance(offer, RecoveryOffer), "Original native read-only RecoveryOffer required")
    offer._owner._require_local_offer_fence(offer.invite_id,offer.candidate_digest,hashlib.sha256(protocol.canonical_bytes(offer._record)).hexdigest())
    return {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingFinalize", "inviteId": offer.invite_id,
            "candidateDigest": offer.candidate_digest, "purpose": "recover"}


class PairingCoordinator:
    def __init__(self, store, local_device_id, local_instance_id):
        self.store = store
        self.local_device_id = protocol.device_id(local_device_id)
        self.local_instance_id = protocol.identifier(local_instance_id, "instanceId")
        self._pending_pairings = {}
        self._history_read_times = {}
        self._management_times = {}
        self._discovery_times = {}

    def prepare_candidate_offer(self, local, invitation, verified_target, owner_confirmed, now_ms, *, reviewed_owner_grant):
        offer = create_candidate_offer(local, invitation, verified_target, owner_confirmed, now_ms, reviewed_owner_grant=reviewed_owner_grant)
        self.remember_candidate_offer(offer, now_ms)
        return offer

    def remember_candidate_offer(self, offer, now_ms):
        _require(isinstance(offer, CandidateOffer) and offer.joiner_device_id == self.local_device_id
                 and offer._reviewed_grant is not None and now_ms < offer._invitation["expiresAt"], "Original in-TTL local scope review required")
        validate_grant(offer._reviewed_grant, self.local_device_id, offer.inviter_device_id, now_ms)
        public = {key: item for key, item in offer._invitation.items() if key != "token"}
        record = {"role": "local_offer", "inviteId": public["inviteId"], "candidateDigest": offer.candidate_digest,
                  "offerDigest": hashlib.sha256(offer.wire_bytes()).hexdigest(), "publicInvitation": public,
                  "localConfirmation": offer._local.wire(), "reviewedOwnerGrant": _copy(offer._reviewed_grant),
                  "reviewedOwnerGrantDigest": _pairing_grant_digest(offer._reviewed_grant), "createdAt": offer._created_at}
        _require(len(protocol.canonical_bytes(record)) <= 32768, "Native public offer review exceeds bounded storage")
        self.store.save_local_pairing_offer(record, now_ms)
        offer._persistence_owner, offer._record_digest = self, hashlib.sha256(protocol.canonical_bytes(record)).hexdigest()

    def _require_local_offer_fence(self, invite_id, candidate_digest, record_digest):
        record=self.store.local_pairing_offer(invite_id)
        _require(record is not None and self.store.local_pairing_offer_state(invite_id) in ("confirmed","consumed")
                 and record["candidateDigest"]==candidate_digest and hashlib.sha256(protocol.canonical_bytes(record)).hexdigest()==record_digest,
                 "Original local pairing review was abandoned or changed")

    def review_local_pairing_offer_abandonment(self, invite_id, candidate_digest, now_ms):
        protocol.identifier(invite_id,"inviteId");protocol.integer(now_ms,"nowMs",0)
        record=self.store.local_pairing_offer(invite_id)
        _require(record is not None and record["candidateDigest"]==candidate_digest and self.store.local_pairing_offer_state(invite_id)=="confirmed", "Select an original not-yet-applied local pairing review")
        return LocalOfferAbandonmentReview(_CONFIRMATION,self,record,now_ms+300000)

    def abandon_local_pairing_offer(self, review, owner_confirmed, now_ms):
        _require(isinstance(review,LocalOfferAbandonmentReview) and review._owner is self and owner_confirmed is True
                 and now_ms<review._expires_at, "Explicit current native local-only abandonment review required")
        self.store.abandon_local_pairing_offer(review._record["inviteId"],review._record["candidateDigest"],review._record_digest,now_ms)
        return {"state":"locally_abandoned","inviteId":review._record["inviteId"],"candidateDigest":review._record["candidateDigest"],
                "localOnly":True,"historyRetained":True,"remoteRevocationAcknowledged":False}

    def pending_local_pairing_offers(self, now_ms):
        return tuple({"inviteId": row["inviteId"], "candidateDigest": row["candidateDigest"], "inviterDeviceId": row["publicInvitation"]["inviterDeviceId"],
                      "inviterFingerprint": row["publicInvitation"]["certificateFingerprint"], "expiresAt": row["publicInvitation"]["expiresAt"],
                      "recoveryOnly": now_ms >= row["publicInvitation"]["expiresAt"], "abandonAvailable": True} for row in self.store.local_pairing_offers(8))

    def restore_pairing_offer(self, local, invite_id, now_ms):
        protocol.identifier(invite_id, "inviteId")
        _require(local.device_id == self.local_device_id and local.instance_id == self.local_instance_id, "Original local identity is required; never provision a replacement")
        record = self.store.local_pairing_offer(invite_id)
        _require(record is not None, "No original persisted local owner review")
        signed = record["localConfirmation"]
        transcript = signed["transcript"]
        _require(transcript["joinerDeviceId"] == local.device_id and transcript["joinerInstanceId"] == local.instance_id
                 and transcript["joinerFingerprint"] == local.certificate_fingerprint, "Stored local offer identity changed")
        _verify_confirmation_certificate(signed, local.certificate_der, transcript)
        confirmation = object.__new__(LocalConfirmation)
        confirmation._device_id, confirmation._transcript = local.device_id, _copy(transcript)
        confirmation._signature = base64.b64decode(signed["signature"], validate=True)
        offer = RecoveryOffer(_CONFIRMATION, self, local, record, confirmation)
        offer.check_current(now_ms)
        return offer

    def _evaluate_recovery_offer(self, offer, now_ms):
        _require(isinstance(offer, RecoveryOffer) and offer._owner is self, "Native original read-only recovery offer required")
        record = self.store.local_pairing_offer(offer.invite_id)
        _require(record is not None and self.store.local_pairing_offer_state(offer.invite_id) in ("confirmed","consumed")
                 and protocol.canonical_bytes(record) == protocol.canonical_bytes(offer._record), "Original local owner review was abandoned or changed")
        public, transcript, grant = record["publicInvitation"], record["localConfirmation"]["transcript"], record["reviewedOwnerGrant"]
        protocol.require_profile(public)
        _require(record["createdAt"] < public["expiresAt"] and transcript["expiresAt"] == public["expiresAt"]
                 and record["candidateDigest"] == hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest()
                 and record["reviewedOwnerGrantDigest"] == _pairing_grant_digest(grant)
                 and identity.device_id(offer.target_certificate_der) == public["inviterDeviceId"] == transcript["inviterDeviceId"]
                 and identity.certificate_fingerprint(offer.target_certificate_der) == transcript["inviterFingerprint"] == public["certificateFingerprint"], "Stored public offer or exact native review changed")
        validate_grant(grant, self.local_device_id, offer.inviter_device_id, now_ms)
        current = self.store.peer(offer.inviter_device_id)
        _require(current is None or not current["revoked"] and current["authorizationEpoch"] == 1
                 and current["fingerprint"] == offer.certificate_fingerprint and current["instanceId"] == public["instanceId"], "Current local owner revoked or superseded this original pairing")

    def _require_committed_current(self, completion, now_ms):
        receipt, body = completion["pairingReceipt"], completion["pairingReceipt"]["commitReceipt"]["body"]
        current, grant = self.store.peer(body["joinerDeviceId"]), self.store.grant(body["grantId"])
        _require(current is not None and not current["revoked"] and current["fingerprint"] == body["joinerFingerprint"]
                 and current["instanceId"] == body["joinerInstanceId"] and current["authorizationEpoch"] == body["authorizationEpoch"]
                 and grant is not None and not grant["revoked"] and grant["authorizationEpoch"] == body["authorizationEpoch"]
                 and _pairing_grant_digest(grant) == body["grantDigest"], "Pairing recovery denied: current owner revoked or superseded the original committed scope")
        validate_grant(grant, self.local_device_id, current["deviceId"], now_ms)
        return grant

    def committed_pairing_recovery_candidates(self, now_ms, limit=8):
        """Owner UI metadata only; no secret, DER, grant/receipt body or read ticket."""
        protocol.integer(now_ms, "nowMs", 0)
        result=[]
        for record in self.store.committed_pairing_candidates(limit):
            completion=record.get("committedCompletion")
            if completion is None:
                continue  # Legacy receipts without immutable commits are local history only.
            body=completion["pairingReceipt"]["commitReceipt"]["body"]
            state="available"
            try:
                protocol.require_profile(completion)
                self._require_committed_current(completion, now_ms)
            except protocol.ProtocolError as denied:
                state="incompatible_profile" if denied.code=="incompatible_profile" else "unavailable"
            except PermissionError:
                peer=self.store.peer(record["peerDeviceId"]);grant=self.store.grant(body["grantId"])
                state="revoked" if peer is not None and peer["revoked"] else "expired" if grant is not None and now_ms>=grant["expiresAt"] else "superseded"
            result.append({"inviteId":record["inviteId"],"candidateDigest":record["candidateDigest"],"peerDeviceId":record["peerDeviceId"],
                           "peerFingerprint":record["peerFingerprint"],"localFingerprint":body["inviterFingerprint"],"commitId":body["commitId"],
                           "committedAt":body["committedAt"],"recoveryState":state})
        return tuple(result)

    def committed_pairing_recovery(self, local, invite_id, candidate_digest, now_ms, ttl_ms=15000):
        protocol.identifier(invite_id, "inviteId")
        protocol.integer(ttl_ms, "recoveryTtl", 1, 30000)
        _require(local.device_id == self.local_device_id and local.instance_id == self.local_instance_id, "Original native receiving identity required")
        record = self.store.candidate(invite_id)
        completion = self.store.committed_completion(invite_id)
        _require(record is not None and completion is not None and record["state"] == "consumed" and record["candidateDigest"] == candidate_digest, "No same already-committed original transaction; expired pending cannot recover as a grant")
        _verify_committed_completion(local.certificate_der, completion, record["transcript"], now_ms)
        grant = self._require_committed_current(completion, now_ms)
        return CommittedPairingRecovery(_CONFIRMATION, self, record, completion, min(now_ms + ttl_ms, grant["expiresAt"]))

    def recover_candidate_pairing(self, recovery, peer, raw, now_ms):
        _require(isinstance(recovery, CommittedPairingRecovery) and recovery._owner is self and isinstance(peer, identity.AuthenticatedPeer)
                 and isinstance(raw, bytes) and len(raw) <= 4096 and peer.device_id == recovery.expected_device_id
                 and peer.certificate_fingerprint == recovery.certificate_fingerprint, "Original read-only committed recovery and exact mTLS peer required")
        recovery.check_current(now_ms)
        request = parse_pairing_finalize(raw)
        _require(request["protocolVersion"] == protocol.VERSION and request["messageType"] == "PairingFinalize" and request["purpose"] == "recover"
                 and request["inviteId"] == recovery.invite_id and request["candidateDigest"] == recovery.candidate_digest, "Recovery is only the original fixed committed transaction")
        result = self.store.committed_completion(recovery.invite_id)
        recovery.check_current(now_ms)
        return _copy(result)

    def accept_recovered_candidate_completion(self, offer, inviter, raw, now_ms):
        _require(isinstance(offer, RecoveryOffer) and offer._owner is self and isinstance(raw, bytes)
                 and len(raw) <= protocol.MAX_PAIRING_BYTES, "Original native recovery review and bounded immutable completion required")
        offer.require_target_peer(inviter, now_ms)
        return self._accept_immutable_completion(offer._record["publicInvitation"], offer._confirmation, offer.candidate_digest, inviter, raw, offer._record["reviewedOwnerGrant"], now_ms)

    def inspect_candidate(self, local, raw, now_ms):
        self._pending_pairings = {key: value for key, value in self._pending_pairings.items() if now_ms < value.expires_at}
        _require(local.device_id == self.local_device_id and local.instance_id == self.local_instance_id and isinstance(raw, bytes) and len(raw) <= protocol.MAX_PAIRING_BYTES, "Bounded local onboarding context required")
        wire = protocol.parse_json_object(raw)
        protocol.require_profile(wire)
        protocol._keys(wire, set("protocolVersion protocolProfile messageType inviteId token joinerCertificateDer joinerInstanceId confirmation".split()))
        _require(wire["protocolVersion"] == protocol.VERSION and wire["messageType"] == "PairingCandidate", "Invalid onboarding message")
        invite_id = protocol.identifier(wire["inviteId"], "inviteId")
        token = protocol.text(wire["token"], "token", 43)
        _require(len(token) == 43 and all(c.isascii() and (c.isalnum() or c in "-_") for c in token), "Invalid pairing invitation secret")
        token_digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        offer_digest = hashlib.sha256(protocol.canonical_bytes(wire)).hexdigest()
        invitation = self.store.begin_candidate_check(invite_id, token_digest, offer_digest, now_ms)
        _require(invitation["inviterDeviceId"] == self.local_device_id and invitation["instanceId"] == self.local_instance_id, "Invitation belongs to another local identity")
        cached = invitation.get("cachedCandidate")
        if cached is not None:
            _require(cached["transcript"]["inviterFingerprint"] == local.certificate_fingerprint, "Invitation certificate changed")
            return PairingCandidate(_CONFIRMATION, self, cached)
        encoded = protocol.text(wire["joinerCertificateDer"], "joinerCertificateDer", 11000)
        der = base64.b64decode(encoded, validate=True)
        _require(len(der) <= 8192 and base64.b64encode(der).decode() == encoded, "Invalid canonical candidate certificate")
        from cryptography import x509
        import datetime
        certificate = x509.load_der_x509_certificate(der)
        now = datetime.datetime.fromtimestamp(now_ms / 1000, datetime.timezone.utc)
        _require(certificate.not_valid_before_utc <= now < certificate.not_valid_after_utc, "Candidate identity certificate expired")
        peer_id, fingerprint = identity.device_id(der), identity.certificate_fingerprint(der)
        _require(peer_id != self.local_device_id and (not invitation["expectedPeerFingerprint"] or invitation["expectedPeerFingerprint"] == fingerprint), "Candidate is not the invited device")
        instance_id = protocol.identifier(wire["joinerInstanceId"], "joinerInstanceId")
        transcript = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingTranscript", "inviteId": invite_id, "inviterDeviceId": self.local_device_id, "inviterInstanceId": self.local_instance_id, "inviterFingerprint": local.certificate_fingerprint, "joinerDeviceId": peer_id, "joinerInstanceId": instance_id, "joinerFingerprint": fingerprint, "tokenDigest": token_digest, "expiresAt": invitation["expiresAt"]}
        _verify_confirmation_certificate(wire["confirmation"], der, transcript)
        _require(self.store.peer(peer_id) is None, "Existing device requires explicit grant management or rotation")
        record = {"inviteId": invite_id, "candidateDigest": hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest(), "offerDigest": offer_digest, "transcript": transcript, "peerConfirmation": wire["confirmation"], "peerDeviceId": peer_id, "peerFingerprint": fingerprint, "peerCertificateDer": encoded, "peerInstanceId": instance_id}
        return PairingCandidate(_CONFIRMATION, self, self.store.save_candidate(record, now_ms))

    def confirm_candidate_locally(self, local, candidate, owner_confirmed_both_fingerprints, now_ms, *, reviewed_owner_grant=None):
        _require(isinstance(candidate, PairingCandidate) and candidate._owner is self and local.device_id == self.local_device_id and local.instance_id == self.local_instance_id and local.certificate_fingerprint == candidate._record["transcript"]["inviterFingerprint"], "Original native candidate and local identity required")
        if reviewed_owner_grant is not None:
            validate_grant(reviewed_owner_grant, self.local_device_id, candidate.device_id, now_ms)
            _require(reviewed_owner_grant["authorizationEpoch"] == 1 and len(protocol.canonical_bytes(reviewed_owner_grant)) <= 8192, "Initial grant requires bounded exact scope review")
        confirmation = confirm_locally(local, candidate._record["transcript"], owner_confirmed_both_fingerprints)
        revision = self.store.confirm_candidate(candidate.invite_id, candidate.candidate_digest, confirmation.wire(), now_ms, None if reviewed_owner_grant is None else _pairing_grant_digest(reviewed_owner_grant))
        pending = PendingPairing(_CONFIRMATION, self, candidate, local, confirmation, revision)
        if reviewed_owner_grant is not None:
            pending._reviewed_grant = _copy(reviewed_owner_grant)
        self._pending_pairings[candidate.invite_id] = pending
        return pending

    def pending_candidates(self, now_ms):
        self._pending_pairings = {key: value for key, value in self._pending_pairings.items() if now_ms < value.expires_at}
        return tuple(PairingCandidate(_CONFIRMATION, self, row) for row in self.store.pending_candidates(now_ms))

    def paired_devices(self, now_ms):
        return [dict(peer, revocation=self.store.revocation_status(peer["deviceId"])) for peer in self.store.peers(128)]

    def replace_grant_locally(self, native_owner_grant, now_ms):
        peer = protocol.device_id(native_owner_grant.get("peerDeviceId"))
        validate_grant(native_owner_grant, self.local_device_id, peer, now_ms)
        self.store.replace_grant(native_owner_grant, now_ms)

    def reject_candidate_locally(self, candidate, now_ms):
        _require(isinstance(candidate, PairingCandidate) and candidate._owner is self, "Original native candidate required")
        self.store.reject_candidate(candidate.invite_id, candidate.candidate_digest, now_ms)
        self._pending_pairings.pop(candidate.invite_id, None)

    def candidate_receipt(self, candidate, now_ms):
        _require(isinstance(candidate, PairingCandidate) and candidate._owner is self, "Original native candidate required")
        current = self.store.candidate(candidate.invite_id)
        _require(current is not None and current["candidateDigest"] == candidate.candidate_digest, "Pairing candidate changed")
        state, pending = "pending_owner_confirmation", self._pending_pairings.get(candidate.invite_id)
        if current["state"] == "consumed":
            state = "paired"
        elif current["state"] == "rejected":
            state = "rejected"
        elif pending is not None:
            pending.check_current(now_ms)
            if pending._reviewed_grant is not None:
                state = "confirmed_for_mtls"
        else:
            _require(now_ms < candidate.expires_at, "Pairing candidate expired")
        receipt = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingCandidateReceipt", "inviteId": candidate.invite_id, "candidateDigest": candidate.candidate_digest, "inviterDeviceId": self.local_device_id, "joinerDeviceId": candidate.device_id, "expiresAt": candidate.expires_at, "state": state}
        if state == "confirmed_for_mtls":
            receipt["confirmation"] = pending._confirmation.wire()
        if state == "paired":
            _require("pairingReceipt" in current, "Original committed pairing receipt unavailable")
            receipt["pairingReceipt"] = current["pairingReceipt"]
            peer = self.store.peer(candidate.device_id)
            receipt["currentlyTrusted"] = peer is not None and not peer["revoked"] and peer["fingerprint"] == candidate.certificate_fingerprint
        _require(len(protocol.canonical_bytes(receipt)) <= protocol.MAX_PAIRING_BYTES, "Onboarding receipt exceeds 16384 bytes")
        return receipt

    def finalize_candidate_pairing(self, pending, peer, raw, native_owner_grant, now_ms):
        _require(isinstance(pending, PendingPairing) and pending._owner is self and isinstance(peer, identity.AuthenticatedPeer) and isinstance(raw, bytes) and len(raw) <= 4096 and peer.device_id == pending.expected_device_id and peer.certificate_fingerprint == pending.certificate_fingerprint, "Native pending confirmation and exact mTLS client required")
        _require(pending._reviewed_grant is not None and protocol.canonical_bytes(pending._reviewed_grant) == protocol.canonical_bytes(native_owner_grant), "Exact native owner grant-scope review is required")
        pending.check_current(now_ms)
        request = parse_pairing_finalize(raw)
        _require(request["protocolVersion"] == protocol.VERSION and request["messageType"] == "PairingFinalize" and request["purpose"] == "commit" and request["inviteId"] == pending.invite_id and request["candidateDigest"] == pending.candidate_digest, "Pairing finalize changed original candidate")
        _require(len(protocol.canonical_bytes(native_owner_grant)) <= 8192, "Native onboarding grant exceeds bounded scope")
        validate_grant(native_owner_grant, self.local_device_id, peer.device_id, now_ms)
        _require(native_owner_grant["authorizationEpoch"] == 1, "New pairing starts at epoch one")
        record, transcript = pending._candidate._record, pending._candidate._record["transcript"]
        verify_confirmation(record["peerConfirmation"], peer, transcript)
        replacement = {"deviceId": peer.device_id, "certificateDer": base64.b64encode(peer.certificate_der).decode(), "fingerprint": peer.certificate_fingerprint, "instanceId": record["peerInstanceId"], "authorizationEpoch": 1, "revoked": False, "updatedAt": now_ms}
        proposed = {"inviteId": pending.invite_id, "peerDeviceId": peer.device_id, "authorizationEpoch": 1, "grantId": native_owner_grant["grantId"], "localConfirmation": pending._confirmation.wire(), "status": "paired"}
        already = self.store.committed_completion(pending.invite_id)
        if already is not None:
            self._require_committed_current(already, max(now_ms, int(__import__("time").time() * 1000)))
            return already
        # Prepare standard signatures outside the ORIGINAL SQLite transaction. The
        # commit method rechecks native review revision/scope/TTL under its write lock.
        commit = {"commitId": str(uuid.uuid4()), "committedAt": now_ms, "inviteId": pending.invite_id,
                  "transcriptDigest": pending.candidate_digest, "grantDigest": _pairing_grant_digest(native_owner_grant),
                  "grantId": native_owner_grant["grantId"], "authorizationEpoch": 1}
        for name in ("inviterDeviceId", "inviterInstanceId", "inviterFingerprint", "joinerDeviceId", "joinerInstanceId", "joinerFingerprint"):
            commit[name] = transcript[name]
        proposed["commitReceipt"] = _sign_record(pending._local, "PairingCommitReceipt", commit)
        grant = _sign_record(pending._local, "PairGrantReceipt", {"grant": dict(_copy(native_owner_grant), revoked=False), "targetInstanceId": self.local_instance_id, "issuedAt": now_ms})
        result = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingCompletion", "inviteId": pending.invite_id, "candidateDigest": pending.candidate_digest, "executorDeviceId": self.local_device_id, "instanceId": self.local_instance_id, "pairingReceipt": proposed, "grantReceipt": grant}
        _require(len(protocol.canonical_bytes(result)) <= protocol.MAX_PAIRING_BYTES, "Pairing completion exceeds 16384 bytes")
        saved = self.store.commit_candidate_completion(pending.invite_id, transcript["tokenDigest"], pending.candidate_digest, pending._revision, replacement, native_owner_grant, proposed, result, max(now_ms, int(__import__("time").time() * 1000)))
        # A repeated call cannot return an executable grant after revocation/revision.
        self._require_committed_current(saved, max(now_ms, int(__import__("time").time() * 1000)))
        return saved

    def accept_candidate_completion(self, offer, inviter, raw, native_owner_grant, now_ms):
        _require(isinstance(offer, CandidateOffer) and isinstance(inviter, identity.AuthenticatedPeer) and isinstance(raw, bytes) and len(raw) <= protocol.MAX_PAIRING_BYTES and offer.joiner_device_id == self.local_device_id and inviter.device_id == offer.inviter_device_id, "Original owner offer and exact mTLS target required")
        _require(offer._reviewed_grant is not None and protocol.canonical_bytes(offer._reviewed_grant) == protocol.canonical_bytes(native_owner_grant), "Exact reciprocal owner grant-scope review is required")
        offer._require_original_fence()
        return self._accept_immutable_completion(offer._invitation, offer._local, offer.candidate_digest, inviter, raw, native_owner_grant, now_ms)

    def _accept_immutable_completion(self, invitation, local_confirmation, candidate_digest, inviter, raw, native_owner_grant, now_ms):
        result = protocol.parse_json_object(raw)
        protocol.require_profile(result)
        protocol._keys(result, set("protocolVersion protocolProfile messageType inviteId candidateDigest executorDeviceId instanceId pairingReceipt grantReceipt".split()))
        _require(result["protocolVersion"] == protocol.VERSION and result["messageType"] == "PairingCompletion" and result["inviteId"] == invitation["inviteId"] and result["candidateDigest"] == candidate_digest and result["executorDeviceId"] == inviter.device_id and result["instanceId"] == invitation["instanceId"], "Completion belongs to another device or candidate")
        grant_body = _verify_record(inviter, result["grantReceipt"], "PairGrantReceipt")
        validate_grant(grant_body["grant"], inviter.device_id, self.local_device_id, now_ms)
        _require(grant_body["targetInstanceId"] == invitation["instanceId"], "Receiving grant instance changed")
        _verify_committed_completion(inviter.certificate_der, result, local_confirmation._transcript, now_ms)
        self.accept_pairing_receipt(invitation, inviter, local_confirmation, result["pairingReceipt"], native_owner_grant, now_ms)
        return self.accept_outgoing_grant(inviter, result["grantReceipt"], now_ms)

    def history_route(self, target, grant_id, now_ms):
        """Restore ONLY a result-read route from original SQL and a new actual mTLS peer."""
        return HistoryRoute(_PERMISSION, self._restore_outgoing_grant(target, grant_id, now_ms, for_history=True))

    def restore_outgoing_grant(self, target, grant_id, now_ms):
        """Restore the original SQL grant; this never accepts a network receipt."""
        return self._restore_outgoing_grant(target, grant_id, now_ms)

    def _restore_outgoing_grant(self, target, grant_id, now_ms, *, for_history=False):
        _require(isinstance(target, identity.AuthenticatedPeer), "Current actual outgoing target required")
        protocol.identifier(grant_id, "grantId")
        known = self.store.peer(target.device_id)
        receipt = self.store.outgoing_grant(target.device_id, grant_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"] == target.certificate_fingerprint and receipt is not None,
                 "No paired persisted outgoing route")
        _require(receipt.get("protocolProfile") in (protocol.PROFILE, PERSISTED_GRANT_PROFILE), "Unsupported persisted grant profile")
        body = _verify_record_signature(target.certificate_der, receipt, "PairGrantReceipt")
        protocol._keys(body, {"grant", "targetInstanceId", "issuedAt"})
        grant = body["grant"]
        validate_grant(grant, target.device_id, self.local_device_id, now_ms, for_history=for_history)
        if receipt["protocolProfile"] == PERSISTED_GRANT_PROFILE:
            _require(set(grant["capabilities"]).issubset(PERSISTED_GRANT_METHODS),
                     "Persisted earlier grant contains an unsupported capability")
        protocol.identifier(body["targetInstanceId"], "targetInstanceId")
        _require(grant["grantId"] == grant_id and not grant.get("revoked",False) and known["instanceId"] == body["targetInstanceId"]
                 and protocol.integer(body["issuedAt"], "issuedAt", 1) <= now_ms, "Persisted outgoing route changed")
        return OutgoingGrant(_PERMISSION, self, target, receipt, body)

    def discovery_query(self,local,target,now_ms,ttl_ms=15000):
        _require(isinstance(local,identity.LocalIdentity) and local.device_id==self.local_device_id and isinstance(target,identity.AuthenticatedPeer),"Current local identity and actual paired target required")
        protocol.integer(ttl_ms,"discoveryTtl",1,30000)
        known=self.store.peer(target.device_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"]==target.certificate_fingerprint,"Discovery target is not formally paired")
        value={"protocolVersion":protocol.VERSION, "protocolProfile": protocol.PROFILE,"messageType":"DeviceRequest","method":"device.describe","requestId":str(uuid.uuid4()),"originDeviceId":local.device_id,"callerDeviceId":local.device_id,"targetDeviceId":target.device_id,"instanceId":known["instanceId"],"deadline":now_ms+ttl_ms,"arguments":{}}
        return DiscoveryQuery(_PERMISSION,self,target,protocol.parse_request(protocol.canonical_bytes(value)))

    def _evaluate_discovery(self,peer,query,now_ms):
        _require(isinstance(peer,identity.AuthenticatedPeer) and isinstance(query,protocol.DeviceTaskEnvelope),"Actual paired discovery context required")
        request=protocol.validate_request(query._encoded,peer,self.local_device_id,now_ms)
        _require(request.method=="device.describe" and request.get("originDeviceId")==peer.device_id and not request.get("delegationChain",[])
                 and 0<request.get("deadline")-now_ms<=30000 and request.get("instanceId",self.local_instance_id)==self.local_instance_id
                 and not any(request.get(field) is not None for field in ("grantId","authorizationEpoch","budget","rootTaskId","parentTaskId","localTaskId","runId","executorEpoch")),"Discovery is paired identity metadata, not a dummy execution grant")
        current=self.store.peer(peer.device_id)
        _require(current is not None and not current["revoked"] and current["fingerprint"]==peer.certificate_fingerprint,"Discovery peer unpaired or revoked")
        return current["authorizationEpoch"]

    def check_discovery_permission(self,peer,query,now_ms):
        epoch=self._evaluate_discovery(peer,query,now_ms)
        # Same bounded metadata/read guard; no execution budget is consumed.
        with self.store.lock:
            self._discovery_times={key:[at for at in times if now_ms-at<60000] for key,times in self._discovery_times.items()}
            self._discovery_times={key:times for key,times in self._discovery_times.items() if times}
            times=self._discovery_times.setdefault(peer.device_id,[])
            _require(len(times)<24 and sum(map(len,self._discovery_times.values()))<128,"Discovery rate exceeded")
            times.append(now_ms)
        return DiscoveryPermission(_PERMISSION,self,peer,query,epoch)

    def discovery_grant_receipts(self,local,permission,now_ms):
        _require(isinstance(permission,DiscoveryPermission) and permission._owner is self and local.device_id==self.local_device_id and local.instance_id==self.local_instance_id,"Original native paired discovery permission and local signer required")
        permission.check_current(now_ms);receipts=[]
        for grant in self.store.grants_for_peer(permission.caller_device_id,16):
            if grant["revoked"] or grant["authorizationEpoch"]!=permission.authorization_epoch:continue
            validate_grant(grant,self.local_device_id,permission.caller_device_id,now_ms,for_history=True)
            if now_ms>=grant["expiresAt"] and now_ms>=grant.get("historyRead",{}).get("expiresAt",0):continue
            permission.check_current(now_ms)
            receipts.append(_sign_record(local,"PairGrantReceipt",{"grant":grant,"targetInstanceId":self.local_instance_id,"issuedAt":now_ms}))
        permission.check_current(now_ms)
        return receipts

    def accept_history_grant_receipt(self,target,signed_receipt,now_ms):
        _require(isinstance(target,identity.AuthenticatedPeer),"Actual paired history target required")
        known=self.store.peer(target.device_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"]==target.certificate_fingerprint,"History target is not paired")
        body=_verify_record(target,signed_receipt,"PairGrantReceipt");protocol._keys(body,{"grant","targetInstanceId","issuedAt"})
        grant=body["grant"];validate_grant(grant,target.device_id,self.local_device_id,now_ms,for_history=True)
        _require(not grant.get("revoked",False) and known["instanceId"]==body["targetInstanceId"] and protocol.integer(body["issuedAt"],"issuedAt",1)<=now_ms
                 and now_ms<grant.get("historyRead",{}).get("expiresAt",0),"No current explicit owner result-read receipt")
        self.store.put_outgoing_grant(target.device_id,grant["grantId"],signed_receipt,now_ms)
        return HistoryRoute(_PERMISSION,OutgoingGrant(_PERMISSION,self,target,signed_receipt,body))

    def check_operation_management_permission(self, peer, request, source, now_ms):
        _require(isinstance(peer, identity.AuthenticatedPeer) and isinstance(source, NativeHistoryAudienceSource), "Current native operation management context required")
        current=self.store.peer(peer.device_id)
        _require(current is not None and not current["revoked"] and current["fingerprint"] == peer.certificate_fingerprint, "Operation manager peer revoked or absent")
        with self.store.lock:
            self._management_times={key:[at for at in times if now_ms-at<60000] for key,times in self._management_times.items()}
            self._management_times={key:times for key,times in self._management_times.items() if times}
            times=self._management_times.setdefault(peer.device_id,[])
            _require(len(times)<60 and sum(map(len,self._management_times.values()))<240,"Operation management rate exceeded")
            times.append(now_ms)
        accepted=self._evaluate_history(peer,request,source,now_ms,_management=True)
        return OperationManagementPermission(_PERMISSION,self,peer,request,source,accepted)

    def check_history_permission(self, peer, query, source, now_ms):
        """Fresh bounded read only. Execution expiry never becomes execution authority."""
        _require(isinstance(source, NativeHistoryAudienceSource), "Original native accepted-audience source required")
        # Independent ingress resource guard, never an execution-budget debit.
        _require(isinstance(peer, identity.AuthenticatedPeer), "Actual paired TLS peer required")
        current = self.store.peer(peer.device_id)
        _require(current is not None and not current["revoked"] and current["fingerprint"] == peer.certificate_fingerprint,
                 "History device unpaired or revoked")
        with self.store.lock:
            self._history_read_times = {key: [at for at in times if now_ms - at < 60000] for key, times in self._history_read_times.items()}
            self._history_read_times = {key: times for key, times in self._history_read_times.items() if times}
            times = self._history_read_times.setdefault(peer.device_id, [])
            _require(len(times) < 240 and sum(map(len, self._history_read_times.values())) < 1024,
                     "History query rate exceeded")
            times.append(now_ms)
        accepted = self._evaluate_history(peer, query, source, now_ms)
        return HistoryPermission(_PERMISSION, self, peer, query, source, accepted)

    def _evaluate_history(self, peer, query, source, now_ms, *, _management=False):
        _require(isinstance(peer, identity.AuthenticatedPeer) and isinstance(query, protocol.DeviceTaskEnvelope)
                 and isinstance(source, NativeHistoryAudienceSource), "Native history data required")
        request = protocol.validate_request(query._encoded, peer, self.local_device_id, now_ms)
        _require(request.get("originDeviceId") == peer.device_id and not request.get("delegationChain", [])
                 and (request.method == "operation.cancel" if _management else request.method in HISTORY_METHODS) and 0 < request.get("deadline") - now_ms <= 30000
                 and request.get("instanceId") == self.local_instance_id, "History query must be a fresh bounded fixed-result read")
        current = self.store.peer(peer.device_id)
        _require(current is not None and not current["revoked"] and current["fingerprint"] == peer.certificate_fingerprint,
                 "History peer unpaired or revoked")
        audience = source.accepted_audience(request)
        required = set("kind objectId originDeviceId callerDeviceId targetDeviceId instanceId rootTaskId localTaskId grantId authorizationEpoch resourceBinding acceptedAt retainedUntil".split())
        source_fields = set("sourceShareId sourceIssuerDeviceId sourceGrantId sourceAuthorizationEpoch sourceProofFingerprint".split())
        run_fields = {"runId", "executorEpoch", "requestRunId", "requestExecutorEpoch", "taskId", "argumentsDigest"}
        protocol._keys(audience, required, source_fields | run_fields)
        for field in ("originDeviceId", "callerDeviceId", "targetDeviceId"):
            protocol.device_id(audience[field], field)
        for field in ("objectId", "instanceId", "rootTaskId", "localTaskId", "runId", "requestRunId", "taskId", "grantId"):
            if field in audience:
                protocol.identifier(audience[field], field)
        for field in ("executorEpoch", "requestExecutorEpoch", "authorizationEpoch", "acceptedAt", "retainedUntil"):
            if field in audience:
                protocol.integer(audience[field], field, 1)
        for run, epoch in (("runId", "executorEpoch"), ("requestRunId", "requestExecutorEpoch")):
            _require((run in audience) == (epoch in audience), "History run and epoch must be a genuine paired binding")
        kind = "operation" if request.method in ("operation.get", "operation.cancel") else "task" if request.method.startswith("task.") else "artifact"
        ref = {"operation": "operationId", "task": "taskId", "artifact": "artifactId"}[kind]
        _require(audience["kind"] == kind and audience["objectId"] == request.arguments[ref]
                 and audience["callerDeviceId"] == peer.device_id and request.get("originDeviceId") == peer.device_id
                 and audience["targetDeviceId"] == self.local_device_id and audience["instanceId"] == self.local_instance_id
                 and audience["grantId"] == request.get("grantId")
                 and audience["acceptedAt"] <= now_ms and (_management or now_ms < audience["retainedUntil"]), "Original object audience or retention does not allow this query")
        _require(protocol.resource_equal(audience["resourceBinding"], request.get("resourceBinding", {})), "History query changed original resource")
        for field in ("rootTaskId", "localTaskId", "runId", "executorEpoch"):
            _require(field not in request.json() or request.get(field) == audience.get(field), "History query changed original task/run scope")
        present = source_fields & audience.keys()
        _require(not present or present == source_fields, "Incomplete immutable source history reference")
        if present:
            protocol.identifier(audience["sourceShareId"], "sourceShareId")
            protocol.device_id(audience["sourceIssuerDeviceId"], "sourceIssuerDeviceId")
            protocol.identifier(audience["sourceGrantId"], "sourceGrantId")
            protocol.integer(audience["sourceAuthorizationEpoch"], "sourceAuthorizationEpoch", 1)
            _require(isinstance(audience["sourceProofFingerprint"], str) and protocol.DIGEST.fullmatch(audience["sourceProofFingerprint"]), "Invalid source history fingerprint")
        if "argumentsDigest" in audience:
            _require(isinstance(audience["argumentsDigest"],str) and protocol.DIGEST.fullmatch(audience["argumentsDigest"]),"Invalid immutable original operation digest")
        if _management:
            _require(audience.get("argumentsDigest") is not None and audience["argumentsDigest"]==request.arguments["argumentsDigest"],"Cancellation changed original SQL target digest")
        _require(audience["originDeviceId"] == audience["callerDeviceId"] or present == source_fields,
                 "Forwarded accepted object lacks immutable source reference")
        if not _management:
            _require(source.source_visible(_copy(audience), now_ms) is True, "Original source history visibility was withdrawn or is unknown")
        grant = self.store.grant(audience["grantId"])
        _require(grant is not None and not grant["revoked"] and current["authorizationEpoch"] == grant["authorizationEpoch"] == request.get("authorizationEpoch") and audience["authorizationEpoch"] <= grant["authorizationEpoch"], "History owner grant epoch changed or was revoked")
        validate_grant(grant, self.local_device_id, peer.device_id, now_ms, for_history=not _management)
        _require((_management or audience["originDeviceId"] in grant["allowedOrigins"]) and any(protocol.resource_equal(audience["resourceBinding"], resource) for resource in grant["resources"]), "Current owner no longer permits this exact original resource")
        history = grant.get("historyRead", {})
        ordinary = (_management or audience["authorizationEpoch"] == grant["authorizationEpoch"]) and now_ms < grant["expiresAt"] and request.method in grant["capabilities"]
        retained = not _management and now_ms < history.get("expiresAt", 0) and request.method in history.get("methods", [])
        _require(ordinary or retained, "No current owner policy permits original result reading")
        policy_end = max(grant["expiresAt"] if ordinary else 0, history["expiresAt"] if retained else 0)
        return {"audience": _copy(audience), "deadline": min(request.get("deadline"), policy_end) if _management else min(request.get("deadline"), audience["retainedUntil"], policy_end)}

    def check_permission(self, peer, envelope, now_ms):
        grant = self._evaluate(peer, envelope, now_ms)
        return Permission(_PERMISSION, self, peer, envelope, grant)

    def _evaluate(self, peer, envelope, now_ms):
        _require(isinstance(peer, identity.AuthenticatedPeer) and isinstance(envelope, protocol.DeviceTaskEnvelope), "Authenticated native device data required")
        request = protocol.validate_request(envelope._encoded, peer, self.local_device_id, now_ms)
        _require(request.method != "operation.cancel", "Use native fixed-object operation management admission")
        _require(not request.get("instanceId") or request.get("instanceId") == self.local_instance_id, "Target device instance changed")
        current = self.store.peer(peer.device_id)
        _require(current is not None and not current["revoked"] and current["fingerprint"] == peer.certificate_fingerprint, "Device unpaired or revoked")
        _require(request.method != "device.describe", "Use native paired identity DiscoveryPermission, not an execution Permission")
        grant = self.store.grant(request.get("grantId"))
        _require(grant is not None and not grant["revoked"], "Device grant absent or revoked")
        _require(current["authorizationEpoch"] == request.get("authorizationEpoch") == grant["authorizationEpoch"], "Device authorization epoch changed")
        validate_grant(grant, self.local_device_id, peer.device_id, now_ms)
        _require(request.method in grant["capabilities"] and request.get("originDeviceId") in grant["allowedOrigins"], "Method or original device not granted")
        if request.get("originDeviceId") != peer.device_id:
            import delegation_policy
            delegation_policy.verify(request, grant, now_ms)
        if request.method == "tool.invoke":
            _require(request.arguments["toolName"] in grant["toolNames"], "Device tool not granted")
        if request.method in ("tool.invoke", "task.submitGoal"):
            protocol.validate_resource_binding(request.get("capabilityVersion"), request.get("resourceBinding"))
            _require(any(protocol.resource_equal(request.get("resourceBinding", {}), resource) for resource in grant["resources"]), "Device resource not granted")
            budget = request.get("budget", {})
            protocol._keys(budget, {"allocationId", "toolCalls", "modelCalls"})
            tools = protocol.integer(budget["toolCalls"], "toolCalls", 0, 400)
            models = protocol.integer(budget["modelCalls"], "modelCalls", 0, 1000)
            _require(tools <= grant["toolBudget"] and models <= grant["modelBudget"], "Budget exceeds receiving-device grant")
            _require(request.method != "tool.invoke" or models == 0, "Explicit tools cannot request model calls")
            if request.method == "task.submitGoal":
                import delegation_policy
                delegation_policy.verify_forwarding_authorization(peer, request, grant, now_ms)
        return grant

    def create_invitation(self, local, expected_peer_fingerprint, now_ms, ttl_ms=120000, *, connection_hint=None):
        _require(local.device_id == self.local_device_id and local.instance_id == self.local_instance_id and type(ttl_ms) is int and 1000 <= ttl_ms <= 300000, "Invalid local pairing invitation")
        _require(expected_peer_fingerprint is None or protocol.DIGEST.fullmatch(expected_peer_fingerprint), "Invalid expected peer fingerprint")
        if connection_hint is not None:
            connection_hint = protocol.validate_connection_hint(connection_hint)
            _require(connection_hint.get("kind") != "relay" or connection_hint["inviterDeviceId"] == self.local_device_id, "Relay hint belongs to another inviter")
        token, invite_id = secrets.token_urlsafe(32), str(uuid.uuid4())
        self.store.put_invitation({"inviteId": invite_id, "tokenDigest": hashlib.sha256(token.encode()).hexdigest(), "inviterDeviceId": self.local_device_id, "instanceId": self.local_instance_id, "expectedPeerFingerprint": expected_peer_fingerprint or "", "expiresAt": now_ms + ttl_ms, "createdAt": now_ms, "state": "pending"})
        invitation = {"protocolVersion": protocol.VERSION, "protocolProfile": protocol.PROFILE, "messageType": "PairingInvitation", "inviteId": invite_id, "inviterDeviceId": self.local_device_id, "instanceId": self.local_instance_id, "certificateDer": base64.b64encode(local.certificate_der).decode(), "certificateFingerprint": local.certificate_fingerprint, "token": token, "expiresAt": now_ms + ttl_ms}
        if connection_hint is not None:
            invitation["connectionHint"] = connection_hint
        return invitation

    def complete_pairing(self, invitation, joiner, local_confirmation, remote_confirmation, local_owner_grant, now_ms):
        _require(isinstance(joiner, identity.AuthenticatedPeer) and isinstance(local_confirmation, LocalConfirmation) and local_confirmation._device_id == self.local_device_id and invitation.get("inviterDeviceId") == self.local_device_id, "Original native pairing confirmation required")
        transcript = local_confirmation._transcript
        token_digest = hashlib.sha256(invitation["token"].encode("ascii")).hexdigest()
        _require(transcript.get("inviteId") == invitation["inviteId"] and transcript.get("tokenDigest") == token_digest and transcript.get("joinerDeviceId") == joiner.device_id and transcript.get("joinerFingerprint") == joiner.certificate_fingerprint and transcript.get("inviterDeviceId") == self.local_device_id and transcript.get("inviterInstanceId") == self.local_instance_id and transcript.get("expiresAt", 0) > now_ms, "Pairing transcript expired or changed")
        verify_confirmation(remote_confirmation, joiner, transcript)
        validate_grant(local_owner_grant, self.local_device_id, joiner.device_id, now_ms)
        _require(local_owner_grant["authorizationEpoch"] == 1, "New pairing starts at epoch one")
        peer = {"deviceId": joiner.device_id, "certificateDer": base64.b64encode(joiner.certificate_der).decode(), "fingerprint": joiner.certificate_fingerprint, "instanceId": transcript["joinerInstanceId"], "authorizationEpoch": 1, "revoked": False, "updatedAt": now_ms}
        receipt = {"inviteId": invitation["inviteId"], "peerDeviceId": joiner.device_id, "authorizationEpoch": 1, "localConfirmation": local_confirmation.wire(), "status": "paired"}
        return self.store.consume_invitation(invitation["inviteId"], token_digest, hashlib.sha256(protocol.canonical_bytes(transcript)).hexdigest(), peer, local_owner_grant, receipt, now_ms)

    def accept_pairing_receipt(self, invitation, inviter, local_confirmation, receipt, local_owner_grant, now_ms):
        _require(isinstance(local_confirmation, LocalConfirmation) and local_confirmation._device_id == self.local_device_id and isinstance(inviter, identity.AuthenticatedPeer), "Original local joiner confirmation required")
        transcript = local_confirmation._transcript
        _require(transcript.get("joinerDeviceId") == self.local_device_id and transcript.get("inviterDeviceId") == inviter.device_id and transcript.get("inviterFingerprint") == inviter.certificate_fingerprint and transcript.get("inviteId") == invitation.get("inviteId") == receipt.get("inviteId") and receipt.get("status") == "paired" and receipt.get("peerDeviceId") == self.local_device_id, "Receipt is not for both locally confirmed devices")
        verify_confirmation(receipt["localConfirmation"], inviter, transcript)
        validate_grant(local_owner_grant, self.local_device_id, inviter.device_id, now_ms)
        _require(local_owner_grant["authorizationEpoch"] == 1, "Reciprocal pairing starts at epoch one")
        peer = {"deviceId": inviter.device_id, "certificateDer": base64.b64encode(inviter.certificate_der).decode(), "fingerprint": inviter.certificate_fingerprint, "instanceId": transcript["inviterInstanceId"], "authorizationEpoch": 1, "revoked": False, "updatedAt": now_ms}
        return self.store.install_confirmed_peer(invitation["inviteId"], transcript, peer, local_owner_grant, receipt, now_ms)

    def issue_grant_receipt(self, local, grant_id, now_ms):
        _require(local.device_id == self.local_device_id and local.instance_id == self.local_instance_id, "Original receiving identity required")
        grant = self.store.grant(grant_id)
        _require(grant is not None and not grant["revoked"], "Local grant unavailable")
        peer = self.store.peer(grant["peerDeviceId"])
        _require(peer is not None and not peer["revoked"] and peer["authorizationEpoch"] == grant["authorizationEpoch"], "Local grant epoch changed")
        validate_grant(grant, self.local_device_id, peer["deviceId"], now_ms)
        return _sign_record(local, "PairGrantReceipt", {"grant": grant, "targetInstanceId": self.local_instance_id, "issuedAt": now_ms})

    def accept_outgoing_grant(self, target, signed_receipt, now_ms):
        _require(isinstance(target, identity.AuthenticatedPeer), "Authenticated outgoing target required")
        known = self.store.peer(target.device_id)
        _require(known is not None and not known["revoked"] and known["fingerprint"] == target.certificate_fingerprint, "Outgoing target is not paired")
        body = _verify_record(target, signed_receipt, "PairGrantReceipt")
        protocol._keys(body, {"grant", "targetInstanceId", "issuedAt"})
        grant = body["grant"]
        validate_grant(grant, target.device_id, self.local_device_id, now_ms)
        protocol.identifier(body["targetInstanceId"], "targetInstanceId")
        _require(body["targetInstanceId"] == known["instanceId"] and protocol.integer(body["issuedAt"], "issuedAt", 1) <= now_ms and not grant.get("revoked", False), "Outgoing target instance or grant receipt changed")
        old = self.store.outgoing_grant(target.device_id, grant["grantId"])
        if old is not None:
            previous = old["body"]["grant"]
            _require(previous["authorizationEpoch"] < grant["authorizationEpoch"] or previous["authorizationEpoch"] == grant["authorizationEpoch"] and protocol.canonical_bytes(previous) == protocol.canonical_bytes(grant), "Outgoing grant cannot downgrade or change within one epoch")
        self.store.put_outgoing_grant(target.device_id, grant["grantId"], signed_receipt, now_ms)
        return OutgoingGrant(_PERMISSION, self, target, signed_receipt, body)

    def confirm_rotation_locally(self, old_device_id, replacement, next_instance_id, owner_confirmed_both_fingerprints, now_ms=None, ttl_ms=300000):
        if now_ms is None:
            import time
            now_ms = int(time.time() * 1000)
        protocol.integer(now_ms, "issuedAt", 1)
        protocol.integer(ttl_ms, "ttl", 1000, 300000)
        old = self.store.peer(old_device_id)
        _require(owner_confirmed_both_fingerprints is True and old is not None and isinstance(replacement, identity.AuthenticatedPeer), "Exact old/new fingerprints require explicit local owner confirmation")
        protocol.identifier(next_instance_id, "instanceId")
        return RotationConfirmation(_CONFIRMATION, old, replacement, next_instance_id, now_ms, ttl_ms)

    def rotate_peer(self, old_device_id, replacement, next_instance_id, old_key_proof, local_confirmation, now_ms):
        old = self.store.peer(protocol.device_id(old_device_id))
        _require(old is not None and isinstance(replacement, identity.AuthenticatedPeer), "Original paired identity and authenticated replacement required")
        protocol.identifier(next_instance_id, "instanceId")
        if local_confirmation is not None:
            _require(isinstance(local_confirmation, RotationConfirmation) and local_confirmation.old_id == old_device_id and local_confirmation.old_fingerprint == old["fingerprint"] and local_confirmation.new_id == replacement.device_id and local_confirmation.new_fingerprint == replacement.certificate_fingerprint and local_confirmation.new_instance == next_instance_id and local_confirmation.expected_epoch == old["authorizationEpoch"] and local_confirmation.expected_revoked is old["revoked"] and local_confirmation.issued_at <= now_ms < local_confirmation.expires_at, "Local identity rotation confirmation expired or original epoch/revocation changed")
            confirmation = "local_owner"
        else:
            _require(not old["revoked"] and old_key_proof is not None, "Rotation requires current old key or explicit local reconfirmation")
            body = _verify_record_certificate(base64.b64decode(old["certificateDer"], validate=True), old_key_proof, "DeviceKeyRotation")
            protocol._keys(body, {"newDeviceId", "newFingerprint", "newInstanceId", "issuedAt", "expiresAt"})
            issued = protocol.integer(body["issuedAt"], "issuedAt", 1)
            expires = protocol.integer(body["expiresAt"], "expiresAt", 1)
            _require(body["newDeviceId"] == replacement.device_id and body["newFingerprint"] == replacement.certificate_fingerprint and body["newInstanceId"] == next_instance_id and issued <= now_ms < expires and expires - issued <= 300000, "Old-key rotation proof expired or changed")
            confirmation = "old_key"
        epoch = protocol.integer(old["authorizationEpoch"] + 1, "authorizationEpoch", 1)
        peer = {"deviceId": replacement.device_id, "certificateDer": base64.b64encode(replacement.certificate_der).decode(), "fingerprint": replacement.certificate_fingerprint, "instanceId": next_instance_id, "authorizationEpoch": epoch, "revoked": False, "updatedAt": now_ms}
        receipt = {"oldDeviceId": old_device_id, "oldFingerprint": old["fingerprint"], "newDeviceId": replacement.device_id, "newFingerprint": replacement.certificate_fingerprint, "authorizationEpoch": epoch, "confirmation": confirmation, "capabilitiesRegrantRequired": True, "updatedAt": now_ms}
        if local_confirmation is not None:
            receipt.update(confirmationId=local_confirmation.confirmation_id, expectedEpoch=local_confirmation.expected_epoch,
                           expectedRevoked=local_confirmation.expected_revoked, issuedAt=local_confirmation.issued_at,
                           expiresAt=local_confirmation.expires_at)
            receipt["rotationId"] = local_confirmation.confirmation_id
        else:
            receipt["rotationId"] = hashlib.sha256(protocol.canonical_bytes(receipt)).hexdigest()
        return self.store.rotate_peer(old, peer, receipt, now_ms)

    def revoke_locally(self, peer_device_id, now_ms):
        return self.store.revoke(protocol.device_id(peer_device_id), now_ms)

    def revocation_status(self, peer_device_id):
        return self.store.revocation_status(peer_device_id)

    def acknowledge_revocation(self, peer, epoch, now_ms):
        known = self.store.peer(peer.device_id)
        _require(known is not None and known["fingerprint"] == peer.certificate_fingerprint, "Unknown revocation peer")
        self.store.acknowledge_revocation(peer.device_id, epoch, now_ms)
