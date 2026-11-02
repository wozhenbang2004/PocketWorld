"""Receiving-device tool spends, indexed in the ORIGINAL Companion ledger.

Not a task store or grant authority. PairingCoordinator still authenticates and
attenuates; the originating Android TaskStore still reserves parent/child budget.
This receiving policy additionally treats each owner PairGrant as a finite pool
for its lifetime. New request/run/attempt/root/epoch IDs cannot refill that pool.
An owner must explicitly issue a NEW grantId to authorize a new pool.
"""
import hashlib
import json

from platform_backend import PlatformError


def _key(parts):
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()


class NativeOperationBudget:
    def __init__(self, manager):
        self.manager = manager
        with manager.lock:
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS device_budget_pools(pool_key TEXT PRIMARY KEY, caller_device_id TEXT NOT NULL, target_device_id TEXT NOT NULL, grant_id TEXT NOT NULL, tool_limit INTEGER NOT NULL, tool_spent INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS device_budget_allocations(allocation_key TEXT PRIMARY KEY, pool_key TEXT NOT NULL, allocation_id TEXT NOT NULL, scope_json TEXT NOT NULL, tool_limit INTEGER NOT NULL, model_limit INTEGER NOT NULL, deadline INTEGER NOT NULL, tool_spent INTEGER NOT NULL)")
            if "scope_json" not in {row[1] for row in manager.ledger.execute("PRAGMA table_info(device_budget_allocations)")}:
                raise PlatformError("Unreleased device budget index changed; local owner reconciliation required, never refill/recreate an old pool")
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS device_budget_spends(operation_id TEXT PRIMARY KEY, arguments_digest TEXT NOT NULL, pool_key TEXT NOT NULL, allocation_key TEXT NOT NULL, tool_units INTEGER NOT NULL, state TEXT NOT NULL, reserved_at INTEGER NOT NULL)")
            manager.ledger.execute("CREATE TABLE IF NOT EXISTS device_budget_source_shares(source_key TEXT PRIMARY KEY, issuer_device_id TEXT NOT NULL, source_allocation_id TEXT NOT NULL, share_id TEXT NOT NULL, target_device_id TEXT NOT NULL, resource_json TEXT NOT NULL, tool_limit INTEGER NOT NULL, model_limit INTEGER NOT NULL, deadline INTEGER NOT NULL, tool_spent INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            allocation_columns = {row[1] for row in manager.ledger.execute("PRAGMA table_info(device_budget_allocations)")}
            for name, kind in (("revision", "INTEGER NOT NULL DEFAULT 1"), ("amendment_evidence", "TEXT")):
                if name not in allocation_columns:
                    manager.ledger.execute("ALTER TABLE device_budget_allocations ADD COLUMN " + name + " " + kind)
            if "source_key" not in {row[1] for row in manager.ledger.execute("PRAGMA table_info(device_budget_spends)")}:
                manager.ledger.execute("ALTER TABLE device_budget_spends ADD COLUMN source_key TEXT")
            # An allocation is the original parent reservation, NOT a local
            # PairGrant generation. Preserve old spends when upgrading the
            # pre-release index; ambiguous duplicate legacy pools fail closed.
            rows = manager.ledger.execute("SELECT a.allocation_key,a.allocation_id,p.caller_device_id,p.target_device_id FROM device_budget_allocations a LEFT JOIN device_budget_pools p ON p.pool_key=a.pool_key").fetchall()
            moves, stable_keys = [], set()
            original_keys = {row[0] for row in rows}
            for old_key, allocation_id, caller, target in rows:
                if not caller or not target:
                    raise PlatformError("Original allocation has no native pool identity; local reconciliation required")
                stable_key = _key([caller, target, allocation_id])
                if stable_key in stable_keys or stable_key != old_key and stable_key in original_keys:
                    raise PlatformError("Multiple legacy grant-indexed copies of one allocation require local reconciliation; no counters reset or merged by guess")
                stable_keys.add(stable_key)
                if stable_key != old_key:
                    moves.append((stable_key, old_key))
            manager.ledger.execute("SAVEPOINT device_allocation_key_migration")
            try:
                for stable_key, old_key in moves:
                    manager.ledger.execute("UPDATE device_budget_allocations SET allocation_key=? WHERE allocation_key=?", (stable_key, old_key))
                    manager.ledger.execute("UPDATE device_budget_spends SET allocation_key=? WHERE allocation_key=?", (stable_key, old_key))
                manager.ledger.execute("RELEASE SAVEPOINT device_allocation_key_migration")
            except BaseException:
                manager.ledger.execute("ROLLBACK TO SAVEPOINT device_allocation_key_migration")
                manager.ledger.execute("RELEASE SAVEPOINT device_allocation_key_migration")
                raise
            manager.ledger.commit()

    def claim(self, operation_id, envelope, permission, database):
        """Call INSIDE the same original-operation prepare transaction, before commit.

        Claim and the original operations INSERT must commit/roll back together.
        No independent database/commit, refund, timeout reclamation or retry lives
        here. Every newly accepted native tool attempt costs one tool unit.
        """
        from device_pairing import Permission, SourceShare
        from device_protocol import DeviceTaskEnvelope, allocation_id, arguments_digest, canonical_bytes
        if database is not self.manager.ledger or not database.in_transaction:
            raise PlatformError("Tool budget spend must share the original operation prepare transaction")
        if not isinstance(permission, Permission) or not isinstance(envelope, DeviceTaskEnvelope) or permission.envelope.json() != envelope.json():
            raise PlatformError("Only the current authenticated native Permission may spend a grant pool", 403)
        now = self.manager.now()
        permission.check_current(now)
        grant, budget = permission.grant, envelope.get("budget")
        if envelope.method != "tool.invoke" or not isinstance(budget, dict) or set(budget) != {"allocationId", "toolCalls", "modelCalls"} or type(budget["toolCalls"]) is not int or budget["toolCalls"] <= 0:
            raise PlatformError("A positive owner-authorized original tool allocation is required", 403)
        root, parent = envelope.get("rootTaskId"), envelope.get("parentTaskId")
        if not isinstance(root, str) or not root or not isinstance(parent, str) or not parent:
            raise PlatformError("Receiving tool budget requires stable original root/parent task identities", 403)
        expected = allocation_id(parent, envelope.get("targetDeviceId"), envelope.get("resourceBinding"), "tool_session")
        if budget["allocationId"] != expected:
            raise PlatformError("Budget allocation identity was not derived from the native task/target/exact resource/kind", 403)
        # The allocation ID is correlation, NOT authority. Current Permission /
        # delegation proof still supplies origin, resource, ceiling and expiry.
        # Request/run/attempt/localTask/epoch cannot reset its frozen scope.
        pool_key = _key([grant["peerDeviceId"], grant["localDeviceId"], grant["grantId"]])
        allocation_key = _key([grant["peerDeviceId"], grant["localDeviceId"], expected])
        scope = {"originDeviceId": envelope.get("originDeviceId"), "callerDeviceId": envelope.get("callerDeviceId"),
                 "rootTaskId": root, "parentTaskId": parent, "targetDeviceId": envelope.get("targetDeviceId"),
                 "resourceBinding": envelope.get("resourceBinding"), "capabilityVersion": envelope.get("capabilityVersion"),
                 "kind": "tool_session", "toolLimit": budget["toolCalls"],
                 "modelLimit": budget["modelCalls"], "deadline": envelope.get("deadline")}
        scope_json = canonical_bytes(scope).decode()
        digest = arguments_digest(envelope.json())
        source = permission.source_share
        if source is not None:
            if not isinstance(source, SourceShare):
                raise PlatformError("A verified native A source share is required", 403)
            source.check_current(now)
            from device_protocol import resource_equal
            if source.target_device_id != envelope.get("targetDeviceId") or not resource_equal(source.resource_binding, envelope.get("resourceBinding")):
                raise PlatformError("Original A source share targets another device or resource", 403)
        elif envelope.get("originDeviceId") != envelope.get("callerDeviceId"):
            raise PlatformError("Forwarded tools require an A-signed non-overlapping target share", 403)
        source_key = _key([source.issuer_device_id, source.source_allocation_id, source.share_id]) if source is not None else None
        previous = database.execute("SELECT arguments_digest,pool_key,allocation_key,tool_units,state,source_key FROM device_budget_spends WHERE operation_id=?", (operation_id,)).fetchone()
        if previous:
            if previous[:3] != (digest, pool_key, allocation_key) or previous[5] != source_key:
                raise PlatformError("Original operation budget identity was reused with different semantics", 409)
            return self._proof(operation_id, pool_key, allocation_key, previous[3], source_key)
        pool = database.execute("SELECT tool_limit,tool_spent FROM device_budget_pools WHERE pool_key=?", (pool_key,)).fetchone()
        if pool is None:
            database.execute("INSERT INTO device_budget_pools VALUES (?,?,?,?,?,?,?)", (pool_key, grant["peerDeviceId"], grant["localDeviceId"], grant["grantId"], permission.tool_budget, 0, now))
            pool = (permission.tool_budget, 0)
        # An epoch update can reduce the current ceiling, never silently refill
        # or increase the original pool. A new owner grantId is explicit refill.
        ceiling = min(pool[0], permission.tool_budget)
        allocation = database.execute("SELECT tool_limit,tool_spent,scope_json FROM device_budget_allocations WHERE allocation_key=?", (allocation_key,)).fetchone()
        if allocation is None:
            limit = min(budget["toolCalls"], ceiling)
            database.execute("INSERT INTO device_budget_allocations(allocation_key,pool_key,allocation_id,scope_json,tool_limit,model_limit,deadline,tool_spent) VALUES (?,?,?,?,?,?,?,?)", (allocation_key, pool_key, expected, scope_json, limit, budget["modelCalls"], envelope.get("deadline"), 0))
            allocation = (limit, 0, scope_json)
        elif allocation[2] != scope_json:
            old_scope = json.loads(allocation[2])
            mutable = {"toolLimit", "modelLimit", "deadline"}
            if canonical_bytes({key: item for key, item in old_scope.items() if key not in mutable}) != canonical_bytes({key: item for key, item in scope.items() if key not in mutable}):
                raise PlatformError("Original allocation immutable native audience/resource/kind changed", 409)
            if any(scope[key] > old_scope[key] for key in mutable):
                raise PlatformError("Allocation increase/extension requires a new original owner amendment; request IDs or budget JSON cannot grant it", 403)
            # Voluntary/owner-authorized attenuation is safe. Keep every spent
            # or unknown unit; lowering 5→3 after one spend leaves TWO, not zero
            # and not a fresh pool. The original operation's wire is untouched.
            evidence = canonical_bytes({"kind": "native_permission_monotonic_attenuation", "grantId": grant["grantId"],
                                        "authorizationEpoch": grant["authorizationEpoch"], "grantFingerprint": permission.grant_fingerprint,
                                        "before": {key: old_scope[key] for key in mutable}, "after": {key: scope[key] for key in mutable}, "at": now}).decode()
            database.execute("UPDATE device_budget_allocations SET scope_json=?,tool_limit=min(tool_limit,?),model_limit=min(model_limit,?),deadline=min(deadline,?),revision=revision+1,amendment_evidence=? WHERE allocation_key=?",
                             (scope_json, budget["toolCalls"], budget["modelCalls"], envelope.get("deadline"), evidence, allocation_key))
            allocation = (min(allocation[0], budget["toolCalls"]), allocation[1], scope_json)
        if pool[1] >= ceiling or allocation[1] >= min(allocation[0], budget["toolCalls"]):
            raise PlatformError("Original owner grant pool or task allocation is exhausted; new run/root/epoch cannot refill it", 409)
        if source is not None:
            resource_json = canonical_bytes(source.resource_binding).decode()
            shared = database.execute("SELECT issuer_device_id,source_allocation_id,share_id,target_device_id,resource_json,tool_limit,model_limit,deadline,tool_spent FROM device_budget_source_shares WHERE source_key=?", (source_key,)).fetchone()
            identity = (source.issuer_device_id, source.source_allocation_id, source.share_id, source.target_device_id, resource_json)
            if shared is None:
                database.execute("INSERT INTO device_budget_source_shares VALUES (?,?,?,?,?,?,?,?,?,?,?)", (source_key, *identity, source.tool_budget, source.model_budget, source.deadline, 0, now))
                shared = (*identity, source.tool_budget, source.model_budget, source.deadline, 0)
            if shared[:5] != identity:
                raise PlatformError("Original signed source share binding changed", 403)
            source_ceiling = min(shared[5], source.tool_budget)
            if now >= min(shared[7], source.deadline) or shared[8] >= source_ceiling:
                raise PlatformError("Original A source share is exhausted/expired; new owner grant, run, epoch, proof signature or allocation cannot refill it", 409)
            database.execute("UPDATE device_budget_source_shares SET tool_limit=min(tool_limit,?),model_limit=min(model_limit,?),deadline=min(deadline,?),tool_spent=tool_spent+1 WHERE source_key=?",
                             (source.tool_budget, source.model_budget, source.deadline, source_key))
        database.execute("UPDATE device_budget_pools SET tool_spent=tool_spent+1 WHERE pool_key=?", (pool_key,))
        database.execute("UPDATE device_budget_allocations SET tool_spent=tool_spent+1 WHERE allocation_key=?", (allocation_key,))
        database.execute("INSERT INTO device_budget_spends VALUES (?,?,?,?,?,?,?,?)", (operation_id, digest, pool_key, allocation_key, 1, "spent_or_unknown_no_refund", now, source_key))
        return self._proof(operation_id, pool_key, allocation_key, 1, source_key)

    @staticmethod
    def _proof(operation, pool, allocation, units, source=None):
        return {"operationId": operation, "reserved": True, "refundOnUnknown": False, "toolUnits": units,
                "ownerGrantPool": pool, "originalTaskAllocation": allocation, "budgetAuthority": "original_companion_ledger",
                "poolPolicy": "owner_grant_lifetime_no_implicit_epoch_root_run_refill", "originalSourceSharePool": source}

    def describe(self):
        return {"authority": "original_companion_ledger", "poolScope": "owner_grantId_lifetime",
                "allocationScope": "shared_native_allocationId_frozen_origin_caller_root_parent_target_capability_exact_resource_kind_limits_deadline", "toolUnitsPerAcceptedInvocation": 1,
                "runAttemptRequestEpochRefill": False, "newGrantRefillsOriginalAllocation": False,
                "sourceScope": "A_signed_stable_sourceAllocationId_targetShare_no_signature_root_run_epoch_refill",
                "allocationAmendment": "monotonic_attenuation_preserves_spent_unknown; increase_extension_require_native_source_increment",
                "unknownRefund": False, "newPoolRequiresExplicitNewOwnerGrantId": True}
