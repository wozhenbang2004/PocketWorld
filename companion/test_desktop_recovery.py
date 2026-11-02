"""Recovery protocol/SQLite/signature tests with explicitly non-Win32 OS fixtures."""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid

from desktop_adapter import DesktopAdapter
import desktop_owner_confirmation as owner_ui
import desktop_recovery as recovery
from platform_backend import PlatformError, PosixBackend
from pocket_companion import JobManager
from test_desktop_adapter import ContractDriver, ContractHealth

try:
    import device_identity
    SHARED_IDENTITY = True
except ImportError:
    SHARED_IDENTITY = False


class OsEvidenceFixture:
    """NOT Windows evidence. Only exercises decisions made from OS proof outcomes."""
    def __init__(self):
        self.host_active = False
        self.job_active = 0
        self.query_available = True
        self.context = {"userSid": "S-1-fixture-only", "sessionId": 1, "authenticationId": "fixture-logon"}

    def logon_context(self):
        return dict(self.context)

    def original_host_stopped(self, pid, created, session):
        return {"originalHostStoppedVerified": self.query_available and not self.host_active,
                "currentProcessIdentity": {"available": self.query_available, "exists": self.host_active,
                                           "created": created, "exited": not self.host_active}, "contractFixture": True}

    def named_job_stopped(self, name):
        return {"processGroupStoppedVerified": self.query_available and self.job_active == 0,
                "activeProcesses": self.job_active, "contractFixture": True}

    @contextmanager
    def directory(self, root):
        yield root, None, ["contract-fixture"]

    def read_bytes(self, root, name):
        path = Path(root) / name
        return path.read_bytes() if path.exists() else None

    def compare_and_swap(self, root, name, before, after):
        return PosixBackend().compare_and_swap(root, name, before, after)

    def delete_local_control_file(self, root, name, sha, size):
        assert name == "desktop-local-stop"
        raw = (Path(root) / name).read_bytes()
        assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha
        (Path(root) / name).unlink()
        return {"verified": True, "contractFixture": True}


@unittest.skipUnless(SHARED_IDENTITY, "Requires the single identity domain; no fake long-term key implementation")
class LocalRecoveryContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.identity = device_identity.provision_identity(root / "ephemeral-test-only-identity")
        self.manager = JobManager({"stateDir": str(root / "original-state"), "environments": []}, "test-only-recovery-token-not-real-0000000000000")
        self.os = OsEvidenceFixture()
        self.adapter = DesktopAdapter(self.manager, ContractDriver(), ContractHealth(), lambda *args: {}, self.identity.device_id)
        binding = {"kind": "desktop", "sessionId": 1, "displayId": "primary"}
        self.lease = self.adapter.control("acquire", binding, "old-peer", {"action": "acquire"}, lambda: None)["lease"]
        worker = {"format": "windows_original_job_v1", "jobName": "Local\\PocketWorldDesktop-" + "1" * 32 + "-" + "2" * 32,
                  "hostPid": 12345, "hostCreated": "fixture-created", "sessionId": 1, "logonContext": self.os.context}
        self.manager.ledger.execute("UPDATE desktop_worker_instances SET binding_json=?", (json.dumps(worker),))
        self.business_op = "old-unknown-business-op"
        self.manager.ledger.execute("INSERT INTO operations(operation_id,fingerprint,job_id,last_status,updated_at,details) VALUES (?,?,?,?,?,?)",
                                   (self.business_op, "d" * 64, str(uuid.uuid4()), "unknown", self.manager.now(), json.dumps({"protocol": "desktop-v1"})))
        self.manager.ledger.commit()
        self.adapter.close()
        self.marker = self.manager.state / "desktop-local-stop"
        self.marker.write_bytes(b"fixture-owner-pause-generation-1")

    def tearDown(self):
        self.adapter.close()
        self.manager.close()
        self.temp.cleanup()

    def prepare(self, manual=False):
        # Only the UI result is a clearly labeled fixture. Actual signing and
        # original SQLite mutations are real. This is not a Windows GUI PASS.
        proof = owner_ui.PhysicalOwnerConfirmation(owner_ui._LOCAL_UI, int(time.time() * 1000), "a" * 64, 8)
        with patch.object(recovery, "confirm_local_recovery", return_value=proof):
            return recovery.prepare_local_request(self.manager.state, self.identity, self.os, manual_after_os_restart=manual)

    def old_status(self):
        return self.manager.ledger.execute("SELECT last_status FROM operations WHERE operation_id=?", (self.business_op,)).fetchone()[0]

    def test_exact_native_stop_outcomes_release_only_resource_and_preserve_unknown_operation(self):
        self.prepare()
        row_before = self.manager.ledger.execute("SELECT lease_id,fence FROM desktop_resource_leases").fetchone()
        receipt = recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.assertTrue(receipt["nativeStopVerified"])
        self.assertFalse(receipt["oldOperationsReplayed"])
        self.assertFalse(receipt["oldOperationStatusesChanged"])
        self.assertEqual("unknown", self.old_status())
        self.assertEqual((row_before[0], row_before[1] + 1, "RELEASED"), self.manager.ledger.execute("SELECT lease_id,fence,state FROM desktop_resource_leases").fetchone())
        self.assertFalse(self.marker.exists())
        self.assertEqual(b"", (self.manager.state / recovery.PENDING).read_bytes())

    def test_positive_active_evidence_cannot_be_overridden_by_manual_route(self):
        self.os.host_active = True
        with self.assertRaises(PlatformError):
            self.prepare(manual=True)
        self.assertEqual("unknown", self.old_status())
        self.assertTrue(self.marker.exists())

    def test_query_failure_requires_labeled_manual_owner_route_not_native_verified(self):
        self.os.query_available = False
        with self.assertRaises(PlatformError):
            self.prepare()
        self.prepare(manual=True)
        receipt = recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.assertFalse(receipt["nativeStopVerified"])
        self.assertTrue(receipt["ownerAttestedStopped"])
        self.assertFalse(receipt["sameUserArbitraryCodeIsolationVerified"])
        self.assertEqual("unknown", self.old_status())

    def test_proof_loss_after_confirmation_does_not_silently_downgrade_or_release(self):
        self.prepare()
        self.os.query_available = False
        with self.assertRaises(PlatformError):
            recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.assertNotEqual("RELEASED", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
        self.assertEqual("unknown", self.old_status())

    def test_signature_or_fence_change_rejects_before_resource_mutation(self):
        self.prepare()
        pending = self.manager.state / recovery.PENDING
        value = json.loads(pending.read_text()); value["body"]["leases"][0]["fence"] += 1
        pending.write_text(json.dumps(value))
        with self.assertRaises(Exception):
            recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.assertTrue(self.marker.exists())
        self.assertEqual("unknown", self.old_status())

    def test_consumed_signed_approval_never_clears_new_pause_or_later_lease(self):
        self.prepare()
        pending = self.manager.state / recovery.PENDING
        signed = pending.read_bytes()
        recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.manager.ledger.execute("UPDATE desktop_resource_leases SET lease_id='new-lease',fence=fence+1,state='HELD'")
        self.manager.ledger.commit()
        self.marker.write_bytes(b"new-local-owner-pause")
        pending.write_bytes(signed)
        receipt = recovery.apply_pending_local_request(self.manager, self.identity, self.os)
        self.assertEqual("newer_resource_generation_exists", receipt["cleanupSkipped"])
        self.assertEqual(b"new-local-owner-pause", self.marker.read_bytes())
        self.assertEqual("HELD", self.manager.ledger.execute("SELECT state FROM desktop_resource_leases").fetchone()[0])
        self.assertEqual("unknown", self.old_status())

    def test_remote_boolean_cannot_construct_local_confirmation(self):
        with self.assertRaises(PlatformError):
            owner_ui.confirmation_record({"ownerConfirmed": True})


if __name__ == "__main__":
    unittest.main()
