"""Bundle integrity tests with inert fixture bytes, not a Windows installation test."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile

SPEC = importlib.util.spec_from_file_location("pocketworld_bundle", Path(__file__).parent / "packaging/windows/build_bundle.py")
bundle_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle_module)


class PackagingIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.frozen = self.root / "frozen"; self.frozen.mkdir()
        (self.frozen / "PocketWorld-Companion.exe").write_bytes(b"INERT TEST FIXTURE; NEVER EXECUTABLE")
        self.driver = self.root / "cua-driver.exe"; self.driver.write_bytes(b"INERT CUA ARTIFACT FIXTURE")
        self.sha = hashlib.sha256(self.driver.read_bytes()).hexdigest()
        self.provenance = self.root / "provenance.json"
        self.provenance.write_text(json.dumps({"sourceCommit": bundle_module.CUA_COMMIT, "sourceClean": True,
                                              "artifactSha256": self.sha, "driverVersion": "0.38.0", "platform": "windows-x64",
                                              "spacesIncluded": False, "perceptionIncluded": False, "cargoLockSha256": "1" * 64,
                                              "features": [], "releaseStatus": "prerelease"}))

        self.agent = self.root / "agent-desktop.exe"; self.agent.write_bytes(b"INERT SEMANTIC DRIVER FIXTURE")
        self.agent_provenance = self.root / "agent-provenance.json"
        self.agent_provenance.write_text(json.dumps({"sourceCommit": bundle_module.AGENT_DESKTOP_COMMIT, "sourceClean": True,
            "cargoLockGitBlobSha1": "de3997ec90e91217baa87fefaa3363b7784c9be9", "cargoLockSha256": "3"*64,
            "platform": "windows-x64", "driverVersion": "1.1.0", "contractVersion": "2.6", "runtime": "headless-semantic-cli",
            "license": "Apache-2.0", "artifactSha256": bundle_module.file_hash(self.agent)}))
        self.runtime_notices = self.root / "runtime-notices"; self.runtime_notices.mkdir()
        (self.runtime_notices / "fixture-license.txt").write_text("INERT runtime license fixture, not a release")
        self.driver_notices = Path(os.environ.get("PW_TEST_CUA_NOTICES_PATH", str(Path(__file__).parent.parent / "licenses/cua-driver-THIRD-PARTY-NOTICES.md")))
        self.runtime_provenance = self.root / "runtime-provenance.json"
        self.runtime_provenance.write_text(json.dumps({"format": "pocketworld.windows-runtime-build.v1", "sourceCommit": "5"*40,
            "platform": "windows-x64", "pythonVersion": "fixture-only", "pyinstallerVersion": "fixture-only", "cryptographyVersion": "fixture-only",
            "aiortcVersion": "1.15.0", "pyavVersion": "17.0.1", "runtimeStreamProbeSha256": "a"*64,
            "runtimeStreamProbe": {"available": True, "codec": "video/VP8", "networkOpened": False, "desktopCaptured": False},
            "toolchainDescription": "INERT fixtures, never used to claim a runnable Windows package", "inputLockSha256": "2"*64,
            "unsignedFiles": {"PocketWorld-Companion.exe": bundle_module.file_hash(self.frozen / "PocketWorld-Companion.exe")},
            "licenseFiles": {"fixture-license.txt": bundle_module.file_hash(self.runtime_notices / "fixture-license.txt")},
            "managementUnsignedFiles": {name: bundle_module.file_hash(Path(bundle_module.__file__).parent / name) for name in bundle_module.MANAGEMENT_SCRIPTS}}))

    def tearDown(self):
        self.temp.cleanup()

    def build(self, output="fixture.zip", sha=None, **kwargs):
        return bundle_module.bundle(self.frozen, self.driver, sha or self.sha, "5" * 40, "fixture-dev-1", self.root / output, self.provenance,
            agent_desktop_binary=self.agent, agent_desktop_provenance=self.agent_provenance, runtime_provenance=self.runtime_provenance, runtime_notices=self.runtime_notices, driver_notices=self.driver_notices,
            allow_unsigned_development=True, **kwargs)

    def test_admitted_inputs_replaced_before_copy_are_rejected(self):
        # Replace real files after admission, at the first path-based copy read.
        dll = self.frozen / "runtime.dll"; dll.write_bytes(b"INERT ORIGINAL DLL")
        management = self.root / "management"; management.mkdir()
        for name in bundle_module.MANAGEMENT_SCRIPTS:
            shutil.copyfile(Path(bundle_module.__file__).parent / name, management / name)
        runtime = json.loads(self.runtime_provenance.read_text())
        runtime["unsignedFiles"][dll.name] = bundle_module.file_hash(dll)
        self.runtime_provenance.write_text(json.dumps(runtime))
        paths = (dll, self.driver, management / bundle_module.MANAGEMENT_SCRIPTS[0],
                 self.runtime_notices / "fixture-license.txt")
        copyfile = shutil.copyfile
        for index, path in enumerate(paths):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                replaced = []
                def replace_then_copy(source, target, *args, **kwargs):
                    if Path(source) == path and not replaced:
                        replacement = path.with_name(path.name + ".replacement")
                        replacement.write_bytes(b"INERT REPLACEMENT, NOT ADMITTED")
                        os.replace(replacement, path)
                        replaced.append(True)
                    return copyfile(source, target, *args, **kwargs)
                output = f"replaced-{index}.zip"
                try:
                    with mock.patch.object(bundle_module.shutil, "copyfile", side_effect=replace_then_copy):
                        with self.assertRaisesRegex(ValueError, "admitted"):
                            self.build(output, management_dir=management)
                    self.assertEqual([True], replaced)
                    self.assertFalse((self.root / output).exists())
                finally:
                    path.write_bytes(original)

    def test_signed_final_input_replacement_does_not_inherit_signing_evidence(self):
        original = self.sha
        self.driver.write_bytes(b"INERT SIGNED-FINAL FIXTURE")
        final = bundle_module.file_hash(self.driver)
        signing = self.root / "signing.json"
        signing.write_text(json.dumps({"format": "pocketworld.windows-signing.v1", "sourceCommit": "5"*40, "complete": True,
            "artifacts": {"driver/cua-driver.exe": {"unsignedSha256": original, "finalSha256": final,
                "signatureStatus": "Valid", "signerThumbprint": "FIXTURE-NOT-A-TRUSTED-SIGNATURE"}}}))
        copyfile = shutil.copyfile
        replaced = []
        def replace_then_copy(source, target, *args, **kwargs):
            if Path(source) == self.driver and not replaced:
                replacement = self.driver.with_suffix(".replacement")
                replacement.write_bytes(b"INERT DIFFERENT POST-SIGN BYTES")
                os.replace(replacement, self.driver)
                replaced.append(True)
            return copyfile(source, target, *args, **kwargs)
        with mock.patch.object(bundle_module.shutil, "copyfile", side_effect=replace_then_copy):
            with self.assertRaisesRegex(ValueError, "admitted"):
                self.build(sha=final, signing_provenance=signing)
        self.assertEqual([True], replaced)
        self.assertFalse((self.root / "fixture.zip").exists())

    def test_driver_identity_cannot_change_after_the_supplied_digest_check(self):
        final_bytes = b"INERT OTHER SIGNED DRIVER"
        signing = self.root / "signing.json"
        signing.write_text(json.dumps({"format": "pocketworld.windows-signing.v1", "sourceCommit": "5"*40, "complete": True,
            "artifacts": {"driver/cua-driver.exe": {"unsignedSha256": self.sha,
                "finalSha256": hashlib.sha256(final_bytes).hexdigest(),
                "signatureStatus": "Valid", "signerThumbprint": "FIXTURE-NOT-A-TRUSTED-SIGNATURE"}}}))
        file_hash = bundle_module.file_hash
        replaced = []
        def replace_after_hash(path):
            result = file_hash(path)
            if path == self.driver and not replaced:
                replacement = self.driver.with_suffix(".replacement")
                replacement.write_bytes(final_bytes)
                os.replace(replacement, self.driver)
                replaced.append(True)
            return result
        with mock.patch.object(bundle_module, "file_hash", side_effect=replace_after_hash):
            with self.assertRaisesRegex(ValueError, "admitted"):
                self.build(signing_provenance=signing)
        self.assertEqual([True], replaced)
        self.assertFalse((self.root / "fixture.zip").exists())

    def test_new_runtime_member_after_admission_is_not_bundled(self):
        walk = bundle_module.tree
        calls = []
        def add_after_admission(path):
            result = walk(path)
            if Path(path) == self.frozen and not calls:
                calls.append(True)
                # The admitted list has already been captured; no later scan may
                # silently admit another member using only a new manifest hash.
                (self.frozen / "late.dll").write_bytes(b"INERT UNADMITTED ADDITION")
            return result
        with mock.patch.object(bundle_module, "tree", side_effect=add_after_admission):
            result = self.build()
        with zipfile.ZipFile(result["path"]) as archive:
            self.assertNotIn("app/late.dll", archive.namelist())

    def test_archive_read_replacement_cannot_disagree_with_manifest(self):
        read_bytes = Path.read_bytes
        replaced = []
        def replace_then_read(path):
            if path.name == "PocketWorld-Companion.exe" and path.parent.name == "app" and not replaced:
                replacement = path.with_suffix(".replacement")
                replacement.write_bytes(b"INERT STAGE REPLACEMENT AFTER MANIFEST")
                os.replace(replacement, path)
                replaced.append(True)
            return read_bytes(path)
        with mock.patch.object(Path, "read_bytes", replace_then_read):
            with self.assertRaisesRegex(ValueError, "admitted"):
                self.build()
        self.assertEqual([True], replaced)

    def test_release_verifier_receives_admitted_staged_paths(self):
        # Stub-only routing test: these inert bytes have no real signatures, and
        # this does not establish Windows or Authenticode acceptance.
        artifacts = {"app/PocketWorld-Companion.exe": self.frozen / "PocketWorld-Companion.exe",
                     "driver/cua-driver.exe": self.driver, "driver/agent-desktop.exe": self.agent}
        artifacts.update({"tools/"+name: Path(bundle_module.__file__).parent / name
                          for name in bundle_module.MANAGEMENT_SCRIPTS})
        records = {name: {"unsignedSha256": bundle_module.file_hash(path), "finalSha256": bundle_module.file_hash(path),
                          "signatureStatus": "Valid", "signerThumbprint": "FIXTURE-ONLY"}
                   for name, path in artifacts.items()}
        signing = self.root / "signing.json"
        signing.write_text(json.dumps({"format": "pocketworld.windows-signing.v1", "sourceCommit": "5"*40,
                                       "complete": True, "artifacts": records}))
        verified = []
        def verify_staged(path, thumbprint):
            self.assertIn(path.parent.name, {"app", "driver", "tools"})
            name = path.parent.name + "/" + path.name
            self.assertNotEqual(artifacts[name], path)
            self.assertEqual(records[name]["finalSha256"], bundle_module.file_hash(path))
            self.assertEqual("FIXTURE-ONLY", thumbprint)
            verified.append(name)
        with mock.patch.object(bundle_module, "os", SimpleNamespace(name="nt", walk=os.walk)), \
                mock.patch.object(bundle_module, "verify_authenticode", side_effect=verify_staged):
            bundle_module.bundle(self.frozen, self.driver, self.sha, "5"*40, "fixture-release-routing", self.root / "routing.zip", self.provenance,
                agent_desktop_binary=self.agent, agent_desktop_provenance=self.agent_provenance, runtime_provenance=self.runtime_provenance, runtime_notices=self.runtime_notices, driver_notices=self.driver_notices,
                signing_provenance=signing)
        self.assertCountEqual(artifacts, verified)
        with mock.patch.object(bundle_module, "os", SimpleNamespace(name="nt", walk=os.walk)), \
                mock.patch.object(bundle_module, "verify_authenticode", side_effect=ValueError("signature rejected")):
            with self.assertRaisesRegex(ValueError, "signature rejected"):
                bundle_module.bundle(self.frozen, self.driver, self.sha, "5"*40, "fixture-release-routing", self.root / "rejected.zip", self.provenance,
                    agent_desktop_binary=self.agent, agent_desktop_provenance=self.agent_provenance, runtime_provenance=self.runtime_provenance, runtime_notices=self.runtime_notices, driver_notices=self.driver_notices,
                    signing_provenance=signing)
        self.assertFalse((self.root / "rejected.zip").exists())

    def test_each_actual_archive_file_is_hash_pinned_and_reproducible(self):
        first, second = self.build(), self.build("fixture-second.zip")
        self.assertEqual(first["sha256"], second["sha256"])
        self.assertEqual("unverified", first["guiAcceptance"])
        with zipfile.ZipFile(first["path"]) as archive:
            manifest = json.loads(archive.read("bundle-manifest.json"))
            declared = {item["path"]: item for item in manifest["files"]}
            self.assertEqual(set(declared) | {"bundle-manifest.json"}, set(archive.namelist()))
            for name, item in declared.items():
                raw = archive.read(name)
                self.assertEqual(item["sha256"], hashlib.sha256(raw).hexdigest())
                self.assertEqual(item["bytes"], len(raw))
            self.assertEqual("prerelease", manifest["cua"]["releaseStatus"])
            self.assertFalse(manifest["cua"]["perceptionIncluded"])

    def test_driver_tampering_wrong_source_and_private_state_are_rejected(self):
        with self.assertRaises(ValueError):
            self.build(sha="0" * 64)
        data = json.loads(self.provenance.read_text()); data["sourceCommit"] = "8" * 40
        self.provenance.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            self.build()
        data["sourceCommit"] = bundle_module.CUA_COMMIT; self.provenance.write_text(json.dumps(data))
        (self.frozen / "companion-token.txt").write_text("INERT NOT-A-REAL-SECRET")
        with self.assertRaises(ValueError):
            self.build()

    def test_windows_path_alias_and_links_are_rejected(self):
        for path in ("../escape", "/absolute", "x\\y", "C:/escape", "x:stream", "CON", "x.", "x ", "a//b"):
            with self.subTest(path), self.assertRaises(ValueError):
                bundle_module.safe_name(path)
        if os.name != "nt":
            (self.frozen / "link").symlink_to(self.driver)
            with self.assertRaises(ValueError):
                self.build()

    def test_management_runtime_and_pinned_notices_are_actually_in_archive(self):
        result = self.build()
        with zipfile.ZipFile(result["path"]) as archive:
            for name in bundle_module.MANAGEMENT_SCRIPTS: self.assertIn("tools/"+name, archive.namelist())
            self.assertIn("licenses/runtime/fixture-license.txt", archive.namelist())
            self.assertEqual(bundle_module.CUA_NOTICES_SHA256, hashlib.sha256(archive.read("licenses/cua-driver-THIRD-PARTY-NOTICES.md")).hexdigest())
            manifest = json.loads(archive.read("bundle-manifest.json"))
            self.assertEqual("unsigned_development", manifest["codeSigning"])
            self.assertEqual(2, manifest["installationSchemaVersion"])

    def test_changed_runtime_or_management_bytes_require_matching_build_evidence(self):
        (self.frozen / "PocketWorld-Companion.exe").write_bytes(b"DIFFERENT INERT BYTE")
        with self.assertRaises(ValueError): self.build()

    def test_pre_sign_digest_is_not_silently_replaced_by_final_digest(self):
        original = json.loads(self.provenance.read_text())["artifactSha256"]
        self.driver.write_bytes(b"INERT POST-SIGN FIXTURE")
        final = bundle_module.file_hash(self.driver)
        with self.assertRaises(ValueError): self.build(sha=final)
        signing = self.root / "signing.json"
        signing.write_text(json.dumps({"format": "pocketworld.windows-signing.v1", "sourceCommit": "5"*40, "complete": True,
            "artifacts": {"driver/cua-driver.exe": {"unsignedSha256": original, "finalSha256": final,
                "signatureStatus": "Valid", "signerThumbprint": "FIXTURE-NOT-A-TRUSTED-SIGNATURE"}}}))
        result = bundle_module.bundle(self.frozen, self.driver, final, "5"*40, "fixture-sign-link", self.root/"link.zip", self.provenance,
            agent_desktop_binary=self.agent, agent_desktop_provenance=self.agent_provenance, runtime_provenance=self.runtime_provenance, runtime_notices=self.runtime_notices, driver_notices=self.driver_notices,
            signing_provenance=signing, allow_unsigned_development=True)
        with zipfile.ZipFile(result["path"]) as archive:
            self.assertEqual(original, json.loads(archive.read("driver/cua-build-provenance.json"))["artifactSha256"])
            self.assertEqual(final, json.loads(archive.read("bundle-manifest.json"))["cua"]["sha256"])
            self.assertEqual("unsigned_development", json.loads(archive.read("bundle-manifest.json"))["codeSigning"])

    def test_missing_runtime_license_and_default_release_gate_fail_closed(self):
        data=json.loads(self.runtime_provenance.read_text());data["licenseFiles"]={};self.runtime_provenance.write_text(json.dumps(data))
        with self.assertRaises(ValueError): self.build()
        if os.name != "nt":
            with self.assertRaisesRegex(ValueError, "Windows signature"):
                bundle_module.bundle(self.frozen,self.driver,self.sha,"5"*40,"release",self.root/"release.zip",self.provenance)

    def test_frozen_spec_keeps_qr_and_actual_target_decoder_dependency_contracts(self):
        # Static build-contract evidence only, not an executed Windows frozen build.
        source=(Path(bundle_module.__file__).parent/"pocket-companion.spec").read_text()
        compile(source, "pocket-companion.spec", "exec")
        self.assertIn('copy_metadata(item["name"])', source)
        self.assertIn('"qrcode": "8.2", "colorama": "0.4.6"', source)
        self.assertIn('"qrcode.image.pure"', source)
        self.assertIn('excludes=["png", "qrcode.image.pil"]', source)
        self.assertIn('"PIL.Image", "PIL.PngImagePlugin"', source)
        self.assertIn('copy_metadata("pillow")', source)
        self.assertIn('"windows-desktop-dependencies.json"', source)
        self.assertIn('"requirements-windows-desktop.txt"', source)
        self.assertIn('desktop_lock["licenseSha256"]', source)
        self.assertIn('item["licenseSha256"]', source)
        self.assertIn('"windows-pairing-THIRD-PARTY-NOTICES.txt"', source)

    def test_install_sources_use_fixed_entry_and_recoverable_pointer_task_commit(self):
        # Source-contract assertions only. They are not Windows Task Scheduler or ACL evidence.
        root=Path(bundle_module.__file__).parent
        install=(root/"Install-Companion.ps1").read_text();manage=(root/"Manage-Companion.ps1").read_text()
        common=(root/"Companion-InstallState.ps1").read_text();launcher=(root/"Launch-Companion.ps1").read_text()
        self.assertNotIn("New-ScheduledTaskAction -Execute (Join-Path $destination", install)
        self.assertIn("Launch-Companion.ps1", common)
        self.assertIn("@('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1')", common)
        self.assertLess(common.index("Write-PWJsonAtomic $Paths.Transaction $journal"), common.index("Set-PWLoginTask $Paths ([bool]$NextPointer.loginStartEnabled)"))
        self.assertIn("previousTaskXml", common);self.assertIn("Repair-PWTransaction", common)
        self.assertIn("Assert-PWFixedConfig", install);self.assertIn("install-state.json", common)
        self.assertIn("$pointer.previousRelease", manage);self.assertIn("Set-PWCurrentTransaction", manage)
        self.assertIn("Read-PWCurrent", launcher);self.assertIn("$pointer.root", launcher)
        self.assertIn("'PairDevices'", manage)
        self.assertIn("elseif ($Action -eq 'PairDevices') { & $exe --pairing --config $paths.Config }", launcher)
        self.assertNotIn("--pairing --identity", launcher)
        self.assertNotIn("ExecutionPolicy Bypass", common+launcher+install+manage)
        self.assertNotRegex(common, r"(?im)^\s*\$home\s*=")


if __name__ == "__main__":
    unittest.main()
