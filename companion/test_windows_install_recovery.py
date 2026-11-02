"""Inert source/control-flow contracts. NO PowerShell/Windows install is run.

The portable byte-file model below only illustrates crash checkpoints and
before-image retention; it is explicitly not Win32 ReplaceFile/ACL/Task evidence.
"""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).parent / "packaging/windows"
NAMES = ("Launch-Companion.ps1", "Manage-Companion.ps1", "Companion-InstallState.ps1")


class InstallerRecoveryContracts(unittest.TestCase):
    def setUp(self):
        self.install = (ROOT / "Install-Companion.ps1").read_text()
        self.manage = (ROOT / "Manage-Companion.ps1").read_text()
        self.common = (ROOT / "Companion-InstallState.ps1").read_text()

    def test_trusted_installer_repair_is_independent_of_missing_fixed_manage_helper(self):
        self.assertIn("[switch]$Repair", self.install)
        verified = self.install.index("Use the installer delivered with this exact bundle.")
        loaded = self.install.index(". (Join-Path $stage 'tools\\Companion-InstallState.ps1')")
        repair = self.install.index("Repair-PWTransaction $paths -FromVerifiedInstaller")
        blocked = self.install.index("An interrupted install must be repaired before updating.")
        self.assertLess(verified, loaded)
        self.assertLess(loaded, repair)
        self.assertLess(repair, blocked)
        branch = self.install[loaded:blocked]
        self.assertNotIn("& $launcher", branch)
        self.assertNotIn(". (Join-Path $paths.Home", branch)
        self.assertNotIn("Manage-Companion.ps1' -Action", branch)
        for binding in ("$original.nextPointer.version -ne $manifest.version", "$original.nextPointer.manifestSha256", "$original.nextPointer.allowUnsignedDevelopment", "$original.nextPointer.signerThumbprint"):
            self.assertIn(binding, branch)
        self.assertIn("Assert-PWPrivateAcl $paths.Transaction $sid", branch)

    def test_external_manage_still_refused_before_dot_sourcing_any_helper(self):
        gate = self.manage.index("if ([IO.Path]::GetFullPath($PSScriptRoot) -ne $fixedHome)")
        helper = self.manage.index(". (Join-Path $PSScriptRoot 'Companion-InstallState.ps1')")
        self.assertLess(gate, helper)
        self.assertIn("original trusted Installer and bundle with -Repair", self.manage[:helper])
        self.assertNotIn("FromVerifiedInstaller", self.manage)

    def test_atomic_fixed_entries_are_published_after_original_journal_and_keep_backups(self):
        function = self.common[self.common.index("function Set-PWFixedEntryAtomic"):self.common.index("function Repair-PWTransaction")]
        for required in ("[IO.FileMode]::CreateNew", "$outputStream.Flush($true)", "[IO.File]::Replace($temporary, $destination, $backup)",
                         "[IO.File]::Move($temporary, $destination)", "$ExpectedCurrentHash", "Get-PWPaths).Home", ".replaced-"):
            self.assertIn(required, function)
        self.assertNotIn("Copy-Item", function)
        self.assertNotIn("Remove-Item", function)  # No finally destroys partial replacement recovery material.
        transaction = self.common[self.common.index("function Set-PWCurrentTransaction"):]
        self.assertLess(transaction.index("Write-PWJsonAtomic $Paths.Transaction $journal"), transaction.index("Set-PWFixedEntryAtomic $Paths $name"))
        self.assertLess(transaction.index("Set-PWFixedEntryAtomic $Paths $name"), transaction.index("Set-PWLoginTask"))

    def test_recovery_uses_verified_release_sources_and_does_not_trust_partial_backup_as_code(self):
        repair = self.common[self.common.index("function Repair-PWTransaction"):self.common.index("function Set-PWCurrentTransaction")]
        self.assertIn("$restorePointer = if ($null -ne $journal.previousPointer)", repair)
        self.assertIn("$restoreManifest = Assert-PWRelease $Paths $restorePointer", repair)
        self.assertIn("$nextManifest = Assert-PWRelease $Paths $journal.nextPointer", repair)
        self.assertIn("Get-PWFixedEntryHash $restoreManifest $name", repair)
        self.assertIn("if (-not $FromVerifiedInstaller)", repair)
        self.assertNotIn("Copy-Item", repair)
        self.assertNotIn(".previous-", repair)
        self.assertLess(repair.index("Set-PWFixedEntryAtomic"), repair.rindex("Remove-Item -LiteralPath $Paths.Transaction"))
        self.assertIn("$registry.installId -ne $journal.nextPointer.installId", repair)
        self.assertIn("Assert-PWStopped $Paths", repair)

    @unittest.skipIf(os.name == "nt", "Explicit POSIX inert byte/checkpoint model, not native installation evidence")
    def test_inert_first_install_every_entry_boundary_and_truncated_legacy_bytes_are_recoverable(self):
        def digest(raw): return hashlib.sha256(raw).hexdigest()
        # Source contracts above link these three exact names to the real entry;
        # this model never evaluates a PowerShell script or changes permissions.
        for count in range(4):
            for damaged in (None, "Manage-Companion.ps1", "Companion-InstallState.ps1"):
                with self.subTest(published=count, damaged=damaged), tempfile.TemporaryDirectory(prefix="inert-install-recovery-") as directory:
                    home = Path(directory)
                    trusted = {name: ("INERT VERIFIED BUNDLE " + name).encode() for name in NAMES}
                    journal = home / "installation-transaction.json"; journal.write_bytes(b"INERT PREPARED JOURNAL")
                    for name in NAMES[:count]:
                        (home / name).write_bytes(trusted[name])
                    if damaged:
                        (home / damaged).write_bytes(b"INERT TRUNCATED OLD COPY")
                    before = {path.name: path.read_bytes() for path in home.iterdir() if path.name in NAMES}
                    # Explicit trusted-installer recovery does not read/execute
                    # either fixed Manage or Helper, even when absent/truncated.
                    retained = {}
                    for name, raw in trusted.items():
                        destination = home / name
                        current = destination.read_bytes() if destination.exists() else None
                        if current == raw:
                            continue
                        temporary = home / (name + ".new-INERT")
                        with temporary.open("xb") as stream:
                            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
                        self.assertEqual(digest(raw), digest(temporary.read_bytes()))
                        if current is not None:
                            # POSIX-only model of the required unique before-image.
                            backup = home / (name + ".replaced-INERT"); backup.write_bytes(current)
                            retained[name] = backup.read_bytes()
                        os.replace(temporary, destination)
                    journal.unlink()
                    self.assertEqual(trusted, {name: (home / name).read_bytes() for name in NAMES})
                    self.assertFalse(journal.exists())
                    for name, raw in before.items():
                        if raw != trusted[name]:
                            self.assertEqual(raw, retained[name])
                    self.assertFalse((home / "current.json").exists())  # First install remains uncommitted; rerun Install explicitly.


if __name__ == "__main__":
    unittest.main()
