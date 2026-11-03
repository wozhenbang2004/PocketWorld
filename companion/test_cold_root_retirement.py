"""Explicit owner retirement of a real original ACTIVE-root snapshot; controlled CLI only."""
import copy
from contextlib import contextmanager
import errno
import json
import os
from pathlib import Path
import sqlite3
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import cold_session
from harness_config import ConfigError
from pocket_companion import CompanionServer, JobManager, RequestError
import test_cold_session as original


@unittest.skipUnless(os.name != "nt" and Path("/proc/sys/kernel/random/boot_id").is_file(), "Requires actual local Linux process identity fixture")
class ColdRootRetirementTests(unittest.TestCase):
    def setUp(self):
        self.peer = original.PinnedConfiguredColdRecoveryTests()
        self.peer.setUp()
        self.addCleanup(self.peer.doCleanups)
        self.manager = self.peer.manager
        self.token = self.manager.token
        self.jobid = self.peer.job["id"]
        self.job = self.manager.jobs[self.jobid]
        self.operation = self.job["operationId"]
        # Replay only the actual original pre-spawn DB row and lost cleanup fence.
        # The process/group/source identities came from the original supervisor.
        self.manager.ledger.execute("DELETE FROM harness_root_leases")
        self.manager.ledger.execute("INSERT INTO harness_root_leases VALUES (?,?,?,?,?,?)", self.peer.active_lease)
        self.manager.ledger.commit()
        self.job.update(resourcesHeld=True, dispatchFenceFinal=False)
        self.job.pop("processCleanupProof", None)
        self.manager.resources.add(self.job["resourceId"])
        self.manager._save(self.job)
        reconciled = self.manager.get(self.jobid)
        self.assertEqual("interrupted", reconciled["status"])
        self.assertFalse(reconciled["resourcesHeld"])
        self.assertEqual("ACTIVE", self.lease()[4])
        self.verified = cold_session.inspect(self.manager, self.jobid, RequestError)
        self.active = self.lease()

    def lease(self):
        return self.manager.ledger.execute("SELECT * FROM harness_root_leases WHERE resource_key=?", (self.peer.active_lease[0],)).fetchone()

    def cancel(self):
        return self.manager.cancel_original(self.jobid, {"jobOperationId": self.operation})

    def assert_not_retired(self):
        self.assertEqual(self.active, self.lease())
        self.assertEqual("interrupted", self.manager.jobs[self.jobid]["status"])
        self.assertEqual({}, self.manager.harness_configs.endpoints)
        self.assertFalse((self.peer.default_home / "methods.jsonl").exists())

    def test_exact_owner_cancel_retires_only_root_and_retry_is_idempotent_across_restart(self):
        self.assertTrue(self.verified["controls"]["cancel"])
        config = self.peer.fixture.file.read_bytes()
        receipt = self.manager.harness_configs.operation(self.peer.plan["operationId"])
        boundary = len(self.peer.methods())
        retired = self.cancel()
        self.assertEqual("interrupted", retired["status"])
        self.assertEqual("CONFIGURED", self.lease()[4])
        self.assertIsNone(self.lease()[3])
        proof = json.loads(self.lease()[5])
        self.assertEqual(cold_session.COLD_ROOT_RETIREMENT, proof["protocol"])
        self.assertTrue(proof["explicitOriginalOwnerCancellation"])
        self.assertFalse(proof["newExecutionAuthorized"])
        self.assertFalse(proof["oldEndpointRevived"])
        self.assertEqual("original_pgid_esrch", proof["processCleanupProof"]["verificationMethod"])
        self.assertNotEqual(self.verified["recoveryProof"]["proofId"], retired["recoveryProof"]["proofId"])
        self.assertEqual(["initialize", "initialized", "thread/read", "thread/turns/list"], self.peer.methods()[boundary:])
        self.assertEqual(config, self.peer.fixture.file.read_bytes())
        self.assertEqual(receipt, self.manager.harness_configs.operation(self.peer.plan["operationId"]))
        self.assertEqual({}, self.manager.harness_configs.endpoints)
        self.assertEqual("1", (self.peer.fixture.config_root / "starts").read_text())
        saved, boundary = self.lease(), len(self.peer.methods())
        self.assertEqual(retired["harnessColdRootRetirement"], self.cancel()["harnessColdRootRetirement"])
        self.assertEqual(saved, self.lease())
        self.assertEqual(boundary, len(self.peer.methods()))
        self.manager.close()
        self.manager = self.peer.manager = self.peer.fixture.manager = JobManager(self.peer.fixture.config, self.token)
        self.peer.fixture.service = self.manager.harness_configs
        replay = self.cancel()
        self.assertEqual("interrupted", replay["status"])
        self.assertEqual(retired["harnessColdRootRetirement"], replay["harnessColdRootRetirement"])
        self.assertEqual(saved, self.lease())
        self.assertEqual(boundary, len(self.peer.methods()))

    def test_retirement_then_reviewed_restore_and_fresh_config_preserve_cold_budget_gates(self):
        retired = self.cancel()
        fixture = self.peer.fixture
        _restore, receipt = fixture.restore()
        self.assertEqual("COMMITTED", receipt["state"])
        fresh = fixture.service.plan(fixture.request(operation="active-retirement-new-config", model="new-reviewed-model"))
        self.assertEqual("COMMITTED", fixture.apply(fresh)["state"])
        request = self.peer.continuation(retired, harnessConfigOperationId=fresh["operationId"], harnessOwnerBinding=self.peer.owner(fresh))
        for change in ({"recoveryOwnerAcknowledged": False}, {"recoveryProofId": "wrong"}, {"timeoutSeconds": 901}, {"timeoutSeconds": 9}):
            with self.subTest(change=change), self.assertRaises(RequestError):
                self.manager.submit({**request, **change})
        without_proof = {key: value for key, value in request.items() if key not in {"recoveryProofId", "recoveryOwnerAcknowledged"}}
        with self.assertRaises(RequestError):
            self.manager.submit(without_proof)
        boundary = len(self.peer.methods())
        accepted = self.manager.submit(request)
        final = self.peer.wait(accepted["id"])
        self.assertEqual("completed", final["status"], final)
        self.assertEqual(retired["threadId"], final["threadId"])
        self.assertEqual("2", (fixture.config_root / "starts").read_text())
        self.assertFalse((self.peer.default_home / "methods.jsonl").exists())
        methods = self.peer.methods()[boundary:]
        self.assertLess(methods.index("thread/turns/list"), methods.index("thread/resume"))
        self.assertEqual(1, methods.count("turn/start"))
        self.assertEqual("interrupted", self.manager.get(self.jobid)["status"])
        new_lease = self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall()
        boundary = len(self.peer.methods())
        self.assertEqual(retired["harnessColdRootRetirement"], self.cancel()["harnessColdRootRetirement"])
        self.assertEqual(new_lease, self.manager.ledger.execute("SELECT * FROM harness_root_leases").fetchall())
        self.assertEqual(boundary, len(self.peer.methods()))

    def test_only_authenticated_exact_owner_route_retires_not_get_inspect_or_legacy_cancel(self):
        server = CompanionServer(("127.0.0.1", 0), self.manager)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        thread.start()
        def request(suffix, body, token=None):
            token = self.token if token is None else token
            req = Request("http://127.0.0.1:" + str(server.server_port) + "/v1/jobs/" + self.jobid + suffix,
                          data=json.dumps(body).encode(), headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            try:
                with urlopen(req, timeout=4) as response:
                    return response.status, json.loads(response.read())
            except HTTPError as failure:
                return failure.code, json.loads(failure.read())
        try:
            self.manager.get(self.jobid)
            cold_session.inspect(self.manager, self.jobid, RequestError)
            self.assert_not_retired()
            for body, token, expected in (({"jobOperationId": self.operation}, "wrong-token", 401), ({"jobOperationId": "wrong-operation"}, self.token, 409), ({"jobOperationId": self.operation, "retire": True}, self.token, 400)):
                self.assertEqual(expected, request("/cancel-original", body, token)[0])
                self.assert_not_retired()
            self.assertEqual(200, request("/cancel", {})[0])
            self.assert_not_retired()
            status, value = request("/cancel-original", {"jobOperationId": self.operation})
            self.assertEqual(200, status, value)
            self.assertEqual("interrupted", value["result"]["status"])
            self.assertEqual("retired", value["result"]["harnessColdRootRetirement"]["status"])
            self.assertEqual("CONFIGURED", self.lease()[4])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=1)

    def test_unknown_inputs_and_missing_pin_or_proof_are_not_cleared_or_retired(self):
        boundary = len(self.peer.methods())
        for key, value in (("approvalReceipts", [{"status": "unknown"}]), ("questionReceipts", [{"status": "sending"}]), ("steeringUnknown", True), ("recoveryProof", None), ("_coldConfigHome", None)):
            with self.subTest(key=key):
                old = self.job.get(key)
                self.job[key] = value
                try:
                    with self.assertRaises(RequestError):
                        self.cancel()
                    self.assertEqual(value, self.job[key])
                    self.assert_not_retired()
                finally:
                    if old is None: self.job.pop(key, None)
                    else: self.job[key] = old
        self.manager.ledger.execute("INSERT INTO steer_messages VALUES (?,?,?,?,?)", ("unknown-original-input", self.jobid, "fixture-fingerprint", json.dumps({"status": "unknown"}), self.manager.now()))
        self.manager.ledger.commit()
        with self.assertRaises(RequestError):
            self.cancel()
        self.assertEqual("unknown", json.loads(self.manager.ledger.execute("SELECT receipt FROM steer_messages").fetchone()[0])["status"])
        self.assertEqual(boundary, len(self.peer.methods()))
        self.assert_not_retired()

    def test_group_absence_requires_esrch_not_eperm_or_an_empty_live_member_scan(self):
        boundary = len(self.peer.methods())
        for outcome in (PermissionError(errno.EPERM, "fixture denied"), None):
            with self.subTest(outcome=outcome):
                effect = {"side_effect": outcome} if isinstance(outcome, Exception) else {"return_value": None}
                with patch("cold_session.os.killpg", **effect) as queries, self.assertRaises(RequestError):
                    self.cancel()
                self.assertTrue(queries.call_args_list)
                self.assertTrue(all(call.args[1] == 0 for call in queries.call_args_list))
                self.assert_not_retired()
        self.assertEqual(boundary, len(self.peer.methods()))

    def test_boot_namespace_home_and_configuration_drift_never_retire_the_root(self):
        boundary = len(self.peer.methods())
        cases = ((self.job["_coldProcess"], "bootId", "00000000-0000-0000-0000-000000000000"),
                 (self.job["_originalProcessGroup"], "pidNamespace", [0, 0]),
                 (self.job["_coldConfigHome"], "home", str(self.peer.default_home)),
                 (self.manager.environments["computer"]["harnesses"][0], "configRoot", str(self.peer.default_home)))
        for target, key, value in cases:
            with self.subTest(key=key):
                previous = target[key]
                target[key] = value
                try:
                    with self.assertRaises(RequestError):
                        self.cancel()
                    self.assert_not_retired()
                finally:
                    target[key] = previous
        self.peer.fixture.file.write_text(self.peer.fixture.file.read_text() + "\n# changed after inspection\n")
        with self.assertRaises(RequestError):
            self.cancel()
        self.assertEqual(boundary, len(self.peer.methods()))
        self.assert_not_retired()

    def test_retirement_never_revokes_or_reuses_a_new_current_owner_endpoint(self):
        import test_harness_config as controlled
        current = self.peer.fixture.lease()
        gateways = copy.deepcopy(self.manager.harness_configs.endpoints)
        gateway_rows = self.manager.ledger.execute("SELECT * FROM gateway_leases").fetchall()
        retired = self.cancel()
        self.assertEqual(gateways, self.manager.harness_configs.endpoints)
        self.assertEqual(current["environmentBinding"], self.manager.environment_binding("computer"))
        self.assertEqual(gateway_rows, self.manager.ledger.execute("SELECT * FROM gateway_leases").fetchall())
        self.assertFalse(retired["harnessColdRootRetirement"]["oldEndpointRevived"])
        self.assertFalse(retired["harnessColdRootRetirement"]["newExecutionAuthorized"])
        self.assertNotIn(controlled.ACCESS, json.dumps(retired))
        self.assertNotIn(controlled.ACCESS, self.lease()[5])
        self.assertEqual("interrupted", retired["status"])

    def test_changed_latest_turn_or_configuration_cannot_reuse_an_old_terminal_proof(self):
        stored = self.peer.fixture.config_root / "stored.json"
        value = json.loads(stored.read_text())
        stored.write_text(json.dumps({**value, "turnId": "another-newer-turn"}))
        with self.assertRaises(RequestError):
            self.cancel()
        self.assert_not_retired()
        self.assertEqual("unconfirmed", self.job["coldRecoveryRead"]["status"])
        self.assertEqual("1", (self.peer.fixture.config_root / "starts").read_text())

    def test_input_becoming_unknown_during_fresh_read_blocks_the_final_root_write(self):
        launch = self.manager._launch
        def changed(env, argv):
            self.job["questionReceipts"] = [{"status": "unknown", "requestId": "late-input"}]
            return launch(env, argv)
        self.manager._launch = changed
        with self.assertRaises(RequestError):
            self.cancel()
        self.assertEqual("unknown", self.job["questionReceipts"][0]["status"])
        self.assert_not_retired()

    def test_root_row_replaced_during_fresh_read_is_not_overwritten(self):
        launch = self.manager._launch
        def changed(env, argv):
            self.manager.ledger.execute("UPDATE harness_root_leases SET job_id='another-original-job' WHERE resource_key=?", (self.active[0],))
            self.manager.ledger.commit()
            return launch(env, argv)
        self.manager._launch = changed
        with self.assertRaises(RequestError):
            self.cancel()
        after = self.lease()
        self.assertEqual("another-original-job", after[3])
        self.assertEqual("ACTIVE", after[4])
        self.assertEqual(self.active[5], after[5])
        self.assertEqual("interrupted", self.job["status"])

    def test_post_commit_job_save_failure_reconciles_from_lease_without_replaying_the_read(self):
        save = self.manager._save
        def failed(job):
            if job.get("harnessColdRootRetirement"):
                raise OSError("controlled lost job-file save after root commit")
            return save(job)
        with patch.object(self.manager, "_save", side_effect=failed), self.assertRaises(OSError):
            self.cancel()
        self.assertEqual("CONFIGURED", self.lease()[4])
        saved, boundary = self.lease(), len(self.peer.methods())
        self.manager.close()
        self.manager = self.peer.manager = self.peer.fixture.manager = JobManager(self.peer.fixture.config, self.token)
        self.peer.fixture.service = self.manager.harness_configs
        self.assertNotIn("harnessColdRootRetirement", self.manager.jobs[self.jobid])
        self.assertEqual("retired", self.manager.get(self.jobid)["harnessColdRootRetirement"]["status"])
        result = self.cancel()
        self.assertEqual("retired", result["harnessColdRootRetirement"]["status"])
        self.assertEqual(saved, self.lease())
        self.assertEqual(boundary, len(self.peer.methods()))

    @contextmanager
    def failed_retirement_writes(self, after_commit=False, rollback_fails=False, close_fails=False):
        connect, opened = sqlite3.connect, []
        def failed_connection(*args, **kwargs):
            db = connect(*args, **kwargs)
            state = {"database": args[0], "uri": kwargs.get("uri"), "closed": False}
            opened.append(state)
            class FailedCommit:
                def __getattr__(self, key): return getattr(db, key)
                def commit(self):
                    if after_commit: db.commit()
                    state["pendingAtCommitError"] = db.in_transaction
                    raise sqlite3.OperationalError("controlled uncertain commit")
                def rollback(self):
                    if rollback_fails:
                        state["pendingAtRollbackError"] = db.in_transaction
                        raise sqlite3.OperationalError("controlled failed rollback")
                    db.rollback()
                def close(self):
                    state["pendingAtClose"] = db.in_transaction
                    db.close()
                    state["closed"] = True
                    if close_fails:
                        raise sqlite3.OperationalError("controlled failed close after actual close")
            return FailedCommit()
        # The injected errors belong to a real second SQLite connection, never
        # to the original shared ledger or a synthetic transaction flag.
        with patch("sqlite3.connect", side_effect=failed_connection):
            yield opened

    def committed_lease(self):
        db = sqlite3.connect(str(self.manager.state / "operations.sqlite3"))
        try:
            return db.execute("SELECT * FROM harness_root_leases WHERE resource_key=?", (self.active[0],)).fetchone()
        finally:
            db.close()

    def commit_failure(self, after_commit):
        with self.failed_retirement_writes(after_commit=after_commit) as opened:
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
                self.cancel()
        self.assertEqual(1, len(opened))
        self.assertTrue(opened[0]["closed"])
        self.assertTrue(opened[0]["uri"])
        self.assertEqual((self.manager.state / "operations.sqlite3").as_uri() + "?mode=rw", opened[0]["database"])
        self.assertFalse(self.manager.ledger.in_transaction)
        boundary = len(self.peer.methods())
        if after_commit:
            self.assertEqual("CONFIGURED", self.lease()[4])
            saved = self.lease()
            self.assertEqual("retired", self.manager.get(self.jobid)["harnessColdRootRetirement"]["status"])
            result = self.cancel()
            self.assertEqual("retired", result["harnessColdRootRetirement"]["status"])
            self.assertEqual(saved, self.lease())
            self.assertEqual(boundary, len(self.peer.methods()))
        else:
            self.assert_not_retired()
            self.assertEqual("retired", self.cancel()["harnessColdRootRetirement"]["status"])
            self.assertGreater(len(self.peer.methods()), boundary)

    def test_commit_error_before_commit_rolls_back_and_requires_a_new_explicit_checked_attempt(self):
        self.commit_failure(False)

    def test_commit_error_after_commit_is_reconciled_without_a_second_retirement(self):
        self.commit_failure(True)

    def test_commit_and_rollback_failure_never_publish_or_piggyback_uncommitted_retirement(self):
        config = self.peer.fixture.file.read_bytes()
        with self.failed_retirement_writes(rollback_fails=True) as opened:
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
                self.cancel()
        self.assertEqual(1, len(opened))
        self.assertTrue(opened[0]["pendingAtCommitError"])
        self.assertTrue(opened[0]["pendingAtRollbackError"])
        self.assertTrue(opened[0]["pendingAtClose"])
        self.assertTrue(opened[0]["closed"])
        self.assertFalse(self.manager.ledger.in_transaction)
        self.assertNotIn("harnessColdRootRetirement", self.manager.get(self.jobid))
        self.assertEqual(self.active, self.committed_lease())
        self.manager._save(self.job)  # An ordinary shared-ledger save cannot commit the failed write.
        self.assertEqual(self.active, self.committed_lease())
        self.assertEqual(config, self.peer.fixture.file.read_bytes())
        self.assert_not_retired()

    def test_commit_and_rollback_failure_retry_never_replays_uncommitted_success(self):
        with self.failed_retirement_writes(rollback_fails=True) as opened:
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
                self.cancel()
            boundary = len(self.peer.methods())
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
                self.cancel()
        self.assertEqual(2, len(opened))
        self.assertTrue(all(state["pendingAtRollbackError"] and state["closed"] for state in opened))
        self.assertGreater(len(self.peer.methods()), boundary)
        self.assertEqual(self.active, self.committed_lease())
        self.assert_not_retired()
        boundary = len(self.peer.methods())
        self.assertEqual("retired", self.cancel()["harnessColdRootRetirement"]["status"])
        self.assertGreater(len(self.peer.methods()), boundary)

    def test_commit_and_rollback_failure_cannot_authorize_reviewed_restore(self):
        with self.failed_retirement_writes(rollback_fails=True):
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
                self.cancel()
        config = self.peer.fixture.file.read_bytes()
        with self.assertRaisesRegex(ConfigError, "COMMITTED/CONFIGURED"):
            self.peer.fixture.restore()
        self.assertEqual(config, self.peer.fixture.file.read_bytes())
        self.assertEqual(self.active, self.committed_lease())
        self.assert_not_retired()

    def test_commit_after_error_with_rollback_and_close_errors_uses_only_committed_outcome(self):
        with self.failed_retirement_writes(after_commit=True, rollback_fails=True, close_fails=True) as opened:
            with self.assertRaisesRegex(RequestError, "persistence is unconfirmed") as failure:
                self.cancel()
        self.assertEqual("controlled uncertain commit", str(failure.exception.__cause__.__cause__))
        self.assertEqual(1, len(opened))
        self.assertFalse(opened[0]["pendingAtCommitError"])
        self.assertFalse(opened[0]["pendingAtRollbackError"])
        self.assertTrue(opened[0]["closed"])
        self.assertFalse(self.manager.ledger.in_transaction)
        committed, boundary = self.committed_lease(), len(self.peer.methods())
        self.assertEqual("CONFIGURED", committed[4])
        self.assertEqual("retired", self.manager.get(self.jobid)["harnessColdRootRetirement"]["status"])
        self.assertEqual("retired", self.cancel()["harnessColdRootRetirement"]["status"])
        self.assertEqual(committed, self.committed_lease())
        self.assertEqual(boundary, len(self.peer.methods()))

    def test_missing_original_ledger_is_not_created_as_a_new_retirement_database(self):
        database = self.manager.state / "operations.sqlite3"
        parked = database.with_suffix(".parked")
        connect, created = sqlite3.connect, []
        def missing(*args, **kwargs):
            database.rename(parked)
            try:
                return connect(*args, **kwargs)
            finally:
                created.append(database.exists())
                parked.rename(database)
        with patch("sqlite3.connect", side_effect=missing), self.assertRaisesRegex(RequestError, "persistence is unconfirmed"):
            self.cancel()
        self.assertEqual([False], created)
        self.assertFalse(self.manager.ledger.in_transaction)
        self.assertEqual(self.active, self.committed_lease())
        self.assert_not_retired()

    def test_existing_unresolved_shared_transaction_cannot_publish_retirement_or_be_saved_by_retry(self):
        self.cancel()
        retired = self.lease()
        self.manager.ledger.execute("DELETE FROM harness_root_leases")
        self.manager.ledger.execute("INSERT INTO harness_root_leases VALUES (?,?,?,?,?,?)", self.active)
        self.manager.ledger.commit()
        self.manager.ledger.execute("UPDATE harness_root_leases SET job_id=?,state=?,proof=? WHERE resource_key=?", (retired[3], retired[4], retired[5], self.active[0]))
        try:
            self.assertTrue(self.manager.ledger.in_transaction)
            self.assertNotIn("harnessColdRootRetirement", self.manager.get(self.jobid))
            with self.assertRaisesRegex(RequestError, "unresolved transaction"):
                self.cancel()
            with self.assertRaisesRegex(ConfigError, "unresolved transaction"):
                self.peer.fixture.restore()
            self.assertTrue(self.manager.ledger.in_transaction)
            self.assertEqual(self.active, self.committed_lease())
        finally:
            self.manager.ledger.rollback()
        self.assert_not_retired()

    def test_unsupported_interrupted_configured_workers_keep_original_cancel_behavior(self):
        for worker in ("claude", "pi"):
            with self.subTest(worker=worker):
                self.job["worker"] = worker
                try:
                    with patch.object(self.manager.harness_configs, "cold_root_retirement", side_effect=AssertionError("Not a Codex cold job")), patch.object(self.manager, "_reconcile_original_process", side_effect=AssertionError("Not a Codex cold job")):
                        result = self.cancel()
                    self.assertEqual("interrupted", result["status"])
                    self.assertTrue(result["originalCancellation"]["identityMatched"])
                    self.assertNotIn("harnessColdRootRetirement", result)
                    self.assert_not_retired()
                finally:
                    self.job["worker"] = "codex_app_server"

    def test_restoration_rejects_tampered_retirement_evidence(self):
        self.cancel()
        original_proof = json.loads(self.lease()[5])
        mutations = (("explicitOriginalOwnerCancellation", False), ("newExecutionAuthorized", True), ("jobStatus", "cancelled"), ("coldProcess", {}), ("storedTurnProof", {}), ("configurationHomePin", {}))
        for key, value in mutations:
            with self.subTest(key=key):
                proof = {**copy.deepcopy(original_proof), key: value}
                self.manager.ledger.execute("UPDATE harness_root_leases SET proof=? WHERE resource_key=?", (json.dumps(proof), self.active[0]))
                self.manager.ledger.commit()
                with self.assertRaises(ConfigError):
                    self.peer.fixture.restore()
                self.assertEqual("CONFIGURED", self.lease()[4])
                self.assertEqual("interrupted", self.job["status"])
        self.assertEqual({}, self.manager.harness_configs.endpoints)
