"""Offline adapter contracts. No SDK, network, key generation or APK builds."""
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import contextlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("windows_release", ROOT / "tools/windows-release.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class WindowsReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="windows-release-test-")
        self.root = Path(self.temporary.name)
        self.jdk = self.root / "existing JDK"
        (self.jdk / "bin").mkdir(parents=True)
        for name in ("java", "javac", "java.exe", "javac.exe"):
            (self.jdk / "bin" / name).touch()
        self.sdk = self.root / "official SDK"
        (self.sdk / "lib").mkdir(parents=True)
        (self.sdk / "aapt.exe").touch()
        (self.sdk / "zipalign.exe").touch()
        (self.sdk / "apksigner.bat").touch()
        (self.sdk / "lib/apksigner.jar").touch()
        self.runtime = TOOL.Runtime(ROOT, self.root / "scratch", self.jdk, self.sdk)
        self.runtime.windows = True

    def tearDown(self):
        self.temporary.cleanup()

    def test_explicit_jdk_suffixes_and_missing_jdk_fail_closed(self):
        java, javac = self.runtime.java_tools()
        self.assertEqual("java.exe", Path(java).name)
        self.assertEqual("javac.exe", Path(javac).name)
        (self.jdk / "bin/javac.exe").unlink()
        with self.assertRaises(ValueError):
            self.runtime.java_tools()

    def test_sdk_uses_exe_and_official_jar_without_cmd_or_shell_text(self):
        apk = str(self.root / "a & b %literal%.apk")
        aapt = self.runtime.command([str(self.sdk / "aapt"), "dump", "badging", apk])
        self.assertEqual(str(self.sdk / "aapt.exe"), aapt[0])
        signer = self.runtime.command([str(self.sdk / "apksigner"), "verify", "--print-certs", apk])
        self.assertEqual([str((self.jdk / "bin/java.exe").resolve()), "-jar", str(self.sdk / "lib/apksigner.jar")], signer[:3])
        self.assertEqual(apk, signer[-1])
        self.assertFalse(any(value.lower().endswith(("cmd.exe", ".bat")) for value in signer))
        alignment = self.runtime.command([str(self.sdk / "zipalign"), "-c", "-P", "16", "-v", "4", apk])
        self.assertEqual(str(self.sdk / "zipalign.exe"), alignment[0])
        self.assertEqual(apk, alignment[-1])

    def test_missing_sdk_files_cannot_become_a_successful_preflight(self):
        (self.sdk / "lib/apksigner.jar").unlink()
        with self.assertRaises(ValueError):
            self.runtime.command([str(self.sdk / "apksigner"), "verify", "a.apk"])
        (self.sdk / "aapt.exe").unlink()
        with self.assertRaises(ValueError):
            self.runtime.command([str(self.sdk / "aapt"), "dump", "badging", "a.apk"])

    def test_nested_delta_verification_reuses_adapter_and_explicit_temp_repo(self):
        command = self.runtime.command([sys.executable, "-B", str(ROOT / "tools/apk-delta.py"),
                                        "verify", "--old", "base.apk", "--new", "next.apk", "--patch", "delta.gz"])
        self.assertEqual(str(TOOL.SCRIPT), command[2])
        self.assertIn("delta", command)
        self.assertIn("verify", command)
        self.assertNotIn("generate", command)
        self.assertEqual(str(ROOT.resolve()), command[command.index("--repo") + 1])
        self.assertEqual(str(self.runtime.temp_dir), command[command.index("--temp-dir") + 1])

    def test_openssl_executable_is_parameterized_without_a_shell(self):
        executable = self.root / "existing openssl.exe"
        executable.touch()
        self.runtime.openssl = str(executable)
        command = self.runtime.command(["openssl", "dgst", "-sha256", "-sign", "private.pem"])
        self.assertEqual(str(executable), command[0])
        self.assertEqual("private.pem", command[-1])

    def test_environment_is_local_restored_and_uses_only_explicit_scratch(self):
        before = {name: os.environ.get(name) for name in ("TEMP", "TMP", "TMPDIR", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "PATH")}
        old_temp = tempfile.tempdir
        with self.runtime.environment():
            self.assertEqual(str(self.runtime.temp_dir), tempfile.gettempdir())
            self.assertIn(self.runtime.temp_dir.as_posix(), os.environ["JAVA_TOOL_OPTIONS"])
            with tempfile.TemporaryDirectory() as created:
                self.assertTrue(Path(created).is_relative_to(self.runtime.temp_dir))
        self.assertEqual(old_temp, tempfile.tempdir)
        self.assertEqual(before, {name: os.environ.get(name) for name in before})

    def test_environment_replaces_parent_java_temp_once_and_preserves_other_options(self):
        parent = '-Dhttp.proxyHost=127.0.0.1 -Djava.io.tmpdir="E:/old scratch" -Xmx256m -Duser.note="two words" -Duser.literal="keep -Djava.io.tmpdir=literal" -Djava.io.tmpdir=E:/stale'
        with mock.patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": parent}):
            with self.runtime.environment():
                options = os.environ["JAVA_TOOL_OPTIONS"]
                self.assertEqual('-Dhttp.proxyHost=127.0.0.1 -Xmx256m -Duser.note="two words" '
                                 '-Duser.literal="keep -Djava.io.tmpdir=literal" -Djava.io.tmpdir="'
                                 + self.runtime.temp_dir.as_posix() + '"', options)
                self.assertIn(self.runtime.temp_dir.as_posix(), options)
                self.assertIn('-Dhttp.proxyHost=127.0.0.1', options)
                self.assertIn('-Xmx256m', options)
                self.assertIn('-Duser.note="two words"', options)
                self.assertIn('-Duser.literal="keep -Djava.io.tmpdir=literal"', options)
                self.assertNotIn("E:/old scratch", options)
                self.assertNotIn("E:/stale", options)
            self.assertEqual(parent, os.environ["JAVA_TOOL_OPTIONS"])

    def test_original_pinned_sources_and_generator_are_not_replaced(self):
        module = self.runtime.existing("apk-delta")
        sources = module.verify_sources()
        self.assertGreater(len(sources), 0)
        self.assertTrue(all(path.is_relative_to(ROOT / "third-party/archive-patcher") for path in sources))
        fake = self.root / "modified-upstream"
        fake.mkdir()
        (fake / "UPSTREAM.json").write_text(json.dumps({"commit": "unreviewed", "files": {}}))
        module.UPSTREAM = fake
        with self.assertRaises(RuntimeError):
            module.verify_sources()

    def test_shared_options_after_tool_are_forwarded_without_fixed_paths(self):
        with mock.patch.object(TOOL, "call_existing", return_value=0) as call:
            result = TOOL.main(["delta", "generate", "--repo", str(ROOT), "--java-home", str(self.jdk),
                                "--temp-dir", str(self.runtime.temp_dir), "--old", "a.apk", "--new", "b.apk", "--output", "p.gz"])
        self.assertEqual(0, result)
        self.assertEqual("delta", call.call_args.args[1])
        self.assertEqual(["generate", "--old", "a.apk", "--new", "b.apk", "--output", "p.gz"], call.call_args.args[2])

    def test_no_implicit_system_temp_or_overwriting_evidence(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            TOOL.main(["delta", "test"])
        output = self.root / "existing.json"
        output.write_text("keep")
        with self.assertRaises(FileExistsError):
            TOOL.write_json({"new": True}, output)
        self.assertEqual("keep", output.read_text())

    def test_subprocess_failure_never_prints_captured_key_material(self):
        secret = "PRIVATE-KEY-DATA-MUST-NOT-LOG"
        with mock.patch.object(TOOL, "call_existing", side_effect=subprocess.CalledProcessError(2, [secret], output=secret, stderr=secret)), contextlib.redirect_stderr(io.StringIO()) as error:
            result = TOOL.main(["--temp-dir", str(self.runtime.temp_dir), "sign"])
        self.assertEqual(2, result)
        self.assertNotIn(secret, error.getvalue())

    def test_preflight_keeps_original_signer_policy_and_reconstruction_gate(self):
        module = self.runtime.existing("inspect-update-artifacts")
        old, new, patch = (self.root / name for name in ("old.apk", "new.apk", "patch.gz"))
        old.write_bytes(b"old"); new.write_bytes(b"new target"); patch.write_bytes(b"zip")
        output = self.root / "evidence.json"

        def sdk(command, timeout=120):
            if "badging" in command:
                code = 1 if command[-1] == str(old) else 2
                return f"package: name='dev.pocketengine' versionCode='{code}' versionName='fixture'\nminSdkVersion:'30'\n"
            if "--print-certs" in command:
                return "Verified using v2 scheme: true\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
            return "reconstructedByteExact=true\n"

        forwarded = ["--old", str(old), "--new", str(new), "--patch", str(patch), "--output", str(output)]
        with mock.patch.object(self.runtime, "existing", return_value=module), mock.patch.object(module, "run", side_effect=sdk):
            self.assertEqual(0, TOOL.call_existing(self.runtime, "preflight", forwarded))
        self.assertTrue(json.loads(output.read_text())["delta"]["reconstructedByteExact"])
        output.unlink()
        with mock.patch.object(self.runtime, "existing", return_value=module), mock.patch.object(module, "run", side_effect=lambda command, timeout=120: sdk(command, timeout).replace("a" * 64, "b" * 64) if command[-1] == str(new) else sdk(command, timeout)):
            with self.assertRaises(ValueError):
                TOOL.call_existing(self.runtime, "preflight", forwarded)
        self.assertFalse(output.exists())

    def test_prepare_retains_existing_preflight_and_sign_uses_existing_cli(self):
        module = self.runtime.existing("prepare-update-release")
        self.assertIsInstance(module.PREFLIGHT.subprocess, TOOL.SubprocessAdapter)
        self.assertEqual(ROOT, module.ROOT)
        signer = self.runtime.existing("sign-update-manifest")
        forwarded = ["--payload", "unsigned.json", "--private-key", "persistent.pem", "--public-key", "public.pem", "--output", "new.json"]
        with mock.patch.object(self.runtime, "existing", return_value=signer), mock.patch.object(signer, "main", side_effect=lambda: self.assertEqual(forwarded, sys.argv[1:])):
            self.assertEqual(0, TOOL.call_existing(self.runtime, "sign", forwarded))

    def envelope_inputs(self):
        repo = self.root / "repo"
        repo.mkdir()
        pin = base64.b64encode(b"public-spki-fixture").decode("ascii")
        (repo / "gradle.properties").write_text("pocketworldUpdatePublicKey=" + pin + "\n")
        envelope, dependency, output = self.root / "release.json", self.root / "locked-json.jar", self.root / "verified.json"
        envelope.write_text('{"payload":"signed fixture","signature":"signature fixture"}')
        dependency.touch()
        runtime = TOOL.Runtime(repo, self.runtime.temp_dir, self.jdk)
        runtime.windows = True
        runtime.temp_dir.mkdir()
        return runtime, envelope, dependency, output

    def test_manifest_uses_production_verifier_configured_pin_and_exact_local_bytes(self):
        runtime, envelope, dependency, output = self.envelope_inputs()
        evidence = {"signatureVerified": True, "packageName": "dev.pocketengine", "versionCode": 9,
                    "apk": {"bytes": 123, "sha256": "a" * 64, "url": "https://example.invalid/app.apk"}}
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            self.assertNotIn("shell", kwargs)
            if "WindowsReleaseManifest" in command:
                index = command.index("WindowsReleaseManifest")
                self.assertEqual(envelope.read_bytes(), Path(command[index + 1]).read_bytes())
                self.assertEqual(TOOL.configured_pin(runtime.repo)[0], Path(command[index + 2]).read_text())
                return subprocess.CompletedProcess(command, 0, json.dumps(evidence), "")
            self.assertIn(str(runtime.repo / "app/src/main/java/dev/pocketengine/features/updates/UpdateManifest.java"), command)
            probe = Path(command[-1]).read_text()
            self.assertIn("UpdateManifest.verify(", probe)
            return subprocess.CompletedProcess(command, 0, "", "")

        with mock.patch.object(TOOL.subprocess, "run", side_effect=run):
            self.assertEqual(0, TOOL.verify_envelope(runtime, ["--envelope", str(envelope), "--classpath", str(dependency), "--output", str(output)]))
        self.assertEqual(2, len(calls))
        self.assertTrue(json.loads(output.read_text())["signatureVerified"])
        self.assertFalse(json.loads(output.read_text())["networkAccessed"])
        self.assertTrue(json.loads(output.read_text())["validNow"])
        self.assertFalse(json.loads(output.read_text())["historicalBaselineOnly"])
        self.assertEqual([], list(runtime.temp_dir.iterdir()))

    @staticmethod
    def timed_envelope(issued, expires):
        raw = json.dumps({"issuedAt": issued, "expiresAt": expires}).encode()
        return json.dumps({"payload": base64.b64encode(raw).decode("ascii"), "signature": "untrusted"}).encode()

    def test_expired_baseline_option_is_not_a_default_clock_bypass(self):
        envelope = self.timed_envelope(1000, 2000)
        self.assertEqual((3000, True, False), TOOL.baseline_clock(envelope, 3000, False))
        self.assertEqual((1999, False, True), TOOL.baseline_clock(envelope, 3000, True))
        self.assertEqual((1500, True, False), TOOL.baseline_clock(envelope, 1500, True))

    def test_expired_baseline_rejects_future_issued_bad_ttl_and_non_integer_dates(self):
        now = 1000000
        cases = [(0, 1), (2000, 2000), (3000, 2000), (1000, 1001 + 7 * 86400000),
                 (now + 300001, now + 300002), (True, 2000), (1000, False),
                 ("1000", 2000), (1000, "2000"), (1000.0, 2000), (1000, 2**63)]
        for issued, expires in cases:
            with self.subTest(issued=issued, expires=expires), self.assertRaises(ValueError):
                TOOL.baseline_clock(self.timed_envelope(issued, expires), now, True)

    def test_baseline_timing_never_trusts_malformed_or_unbounded_payloads(self):
        for envelope in (b"[]", b"{}", b'{"payload":"not base64!"}',
                         json.dumps({"payload": base64.b64encode(b"[]").decode()}).encode(),
                         json.dumps({"payload": base64.b64encode(b" " * (64 * 1024 + 1)).decode()}).encode()):
            with self.subTest(envelopeBytes=len(envelope)), self.assertRaises(ValueError):
                TOOL.baseline_clock(envelope, 3000, True)

    def test_expired_option_still_delegates_signature_and_package_checks_to_production(self):
        runtime, envelope, dependency, output = self.envelope_inputs()
        envelope.write_bytes(self.timed_envelope(1000, 2000))
        evidence = {"signatureVerified": True, "packageName": "dev.pocketengine", "versionCode": 9}

        def run(command, **kwargs):
            if "WindowsReleaseManifest" in command:
                self.assertEqual("1999", command[-1])
                return subprocess.CompletedProcess(command, 0, json.dumps(evidence), "")
            return subprocess.CompletedProcess(command, 0, "", "")

        forwarded = ["--envelope", str(envelope), "--classpath", str(dependency), "--output", str(output), "--allow-expired-baseline"]
        with mock.patch.object(TOOL.subprocess, "run", side_effect=run), mock.patch.object(TOOL.time, "time", return_value=3):
            self.assertEqual(0, TOOL.verify_envelope(runtime, forwarded))
        verified = json.loads(output.read_text())
        self.assertFalse(verified["validNow"])
        self.assertTrue(verified["historicalBaselineOnly"])
        output.unlink()

        def bad_signature(command, **kwargs):
            if "WindowsReleaseManifest" in command:
                raise subprocess.CalledProcessError(2, command)
            return subprocess.CompletedProcess(command, 0, "", "")

        with mock.patch.object(TOOL.subprocess, "run", side_effect=bad_signature), mock.patch.object(TOOL.time, "time", return_value=3):
            with self.assertRaises(subprocess.CalledProcessError):
                TOOL.verify_envelope(runtime, forwarded)
        self.assertFalse(output.exists())

    def test_manifest_rejection_or_existing_output_never_produces_trusted_evidence(self):
        runtime, envelope, dependency, output = self.envelope_inputs()
        forwarded = ["--envelope", str(envelope), "--classpath", str(dependency), "--output", str(output)]
        with mock.patch.object(TOOL.subprocess, "run", side_effect=subprocess.CalledProcessError(2, ["java"])):
            with self.assertRaises(subprocess.CalledProcessError):
                TOOL.verify_envelope(runtime, forwarded)
        self.assertFalse(output.exists())
        output.write_text("existing")
        with mock.patch.object(TOOL.subprocess, "run") as run:
            with self.assertRaises(ValueError):
                TOOL.verify_envelope(runtime, forwarded)
        run.assert_not_called()
        self.assertEqual("existing", output.read_text())

    def test_missing_or_ambiguous_configured_pin_is_not_replaced_by_downloaded_metadata(self):
        runtime, envelope, dependency, output = self.envelope_inputs()
        for properties in ("", "pocketworldUpdatePublicKey=\n", "pocketworldUpdatePublicKey=%%\n",
                           "pocketworldUpdatePublicKey=YQ==\npocketworldUpdatePublicKey=Yg==\n"):
            (runtime.repo / "gradle.properties").write_text(properties)
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                TOOL.configured_pin(runtime.repo)

    def test_verify_apk_delegates_complete_verifier_and_pins_active_sdk_signer(self):
        module = self.runtime.existing("ci/verify-web-ui")
        preflight = self.runtime.existing("inspect-update-artifacts")
        apk, out = self.root / "existing signed.apk", self.root / "new-apk-evidence"
        apk.write_bytes(b"existing APK fixture")
        expected, commands = "a" * 64, []

        def evidence(command, destination):
            commands.append(command)
            if Path(destination).name == "signature.txt":
                Path(destination).write_text("Verified using v2 scheme: true\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + expected + "\n")
            else:
                Path(destination).write_text("original evidence fixture")

        def verify(args):
            self.assertEqual(ROOT.resolve(), Path.cwd())
            self.assertEqual(str(apk.resolve()), args.apk)
            self.assertEqual(str(out.resolve()), args.out)
            for name, destination, arguments in (("apksigner", "signature.txt", ["verify", "--print-certs"]),
                                                  ("zipalign", "alignment.txt", ["-c", "-P", "16", "-v", "4"]),
                                                  ("aapt", "package.txt", ["dump", "badging"])):
                module.run_evidence(["old-sdk/build-tools/35.0.0/" + name] + arguments + [args.apk], Path(args.out) / destination)
            (Path(args.out) / "apk-identity.json").write_text(json.dumps({"signature": "ephemeral Android debug key; not stable across fresh runners", "validation": ["original full verifier fixture"]}))

        cwd, sdk = Path.cwd(), os.environ.get("ANDROID_HOME")
        with mock.patch.object(self.runtime, "existing", side_effect=lambda name: module if name == "ci/verify-web-ui" else preflight), \
                mock.patch.object(module, "run_evidence", side_effect=evidence), mock.patch.object(module, "verify_apk", side_effect=verify) as original, \
                mock.patch.object(module, "acquire_apk", side_effect=AssertionError("must not acquire or read GitHub credentials")) as acquire:
            self.assertEqual(0, TOOL.verify_apk(self.runtime, ["--apk", str(apk), "--out", str(out), "--expected-signer", expected.upper()]))
        original.assert_called_once()
        acquire.assert_not_called()
        self.assertEqual(str(self.jdk / "bin/java.exe"), commands[0][0])
        self.assertEqual(str(self.sdk / "zipalign.exe"), commands[1][0])
        self.assertEqual(str(self.sdk / "aapt.exe"), commands[2][0])
        receipt = json.loads((out / "apk-identity.json").read_text())
        self.assertEqual([expected], receipt["signerCertificateSha256"])
        self.assertTrue(receipt["stableSignerPinVerified"])
        self.assertNotIn("ephemeral", receipt["signature"])
        self.assertIn(expected, receipt["signature"])
        self.assertEqual(["original full verifier fixture"], receipt["validation"])
        self.assertEqual(cwd, Path.cwd())
        self.assertEqual(sdk, os.environ.get("ANDROID_HOME"))
        self.assertEqual(b"existing APK fixture", apk.read_bytes())

    def test_verify_apk_rejects_wrong_or_multiple_active_signers_before_copying(self):
        for signers in (["b" * 64], ["a" * 64, "b" * 64]):
            module = self.runtime.existing("ci/verify-web-ui")
            preflight = self.runtime.existing("inspect-update-artifacts")
            apk, out = self.root / "existing.apk", self.root / ("failed-evidence-" + str(len(signers)))
            apk.write_bytes(b"APK fixture")

            def evidence(command, destination):
                text = "Verified using v3 scheme: true\nNumber of signers: " + str(len(signers)) + "\n"
                text += "".join("Signer #" + str(n) + " certificate SHA-256 digest: " + digest + "\n" for n, digest in enumerate(signers, 1))
                Path(destination).write_text(text)

            def verify(args):
                module.run_evidence(["old-sdk/apksigner", "verify", args.apk], Path(args.out) / "signature.txt")
                self.fail("A wrong active signer must stop the original verifier before artifact copying")

            with self.subTest(signers=signers), mock.patch.object(self.runtime, "existing", side_effect=lambda name: module if name == "ci/verify-web-ui" else preflight), \
                    mock.patch.object(module, "run_evidence", side_effect=evidence), mock.patch.object(module, "verify_apk", side_effect=verify):
                with self.assertRaises(ValueError):
                    TOOL.verify_apk(self.runtime, ["--apk", str(apk), "--out", str(out), "--expected-signer", "a" * 64])
            self.assertFalse((out / "apk-identity.json").exists())

    def test_verify_apk_requires_exact_pin_and_exclusive_new_evidence(self):
        apk, out = self.root / "existing.apk", self.root / "evidence"
        apk.write_bytes(b"APK fixture")
        for signer in ("", "a" * 63, "a" * 65, "z" * 64, "sha256:" + "a" * 64):
            with self.subTest(signer=signer), self.assertRaises(ValueError):
                TOOL.verify_apk(self.runtime, ["--apk", str(apk), "--out", str(out), "--expected-signer", signer])
        self.assertFalse(out.exists())
        out.mkdir()
        (out / "existing.txt").write_text("keep")
        with self.assertRaises(ValueError):
            TOOL.verify_apk(self.runtime, ["--apk", str(apk), "--out", str(out), "--expected-signer", "a" * 64])
        self.assertEqual("keep", (out / "existing.txt").read_text())

    def test_verify_apk_does_not_turn_original_package_failure_into_a_pass(self):
        module = self.runtime.existing("ci/verify-web-ui")
        preflight = self.runtime.existing("inspect-update-artifacts")
        apk, out = self.root / "existing.apk", self.root / "failed-original-check"
        apk.write_bytes(b"APK fixture")
        cwd, sdk = Path.cwd(), os.environ.get("ANDROID_HOME")
        with mock.patch.object(self.runtime, "existing", side_effect=lambda name: module if name == "ci/verify-web-ui" else preflight), \
                mock.patch.object(module, "verify_apk", side_effect=AssertionError("original packaged Web receipt mismatch")):
            with self.assertRaisesRegex(AssertionError, "original packaged Web receipt mismatch"):
                TOOL.verify_apk(self.runtime, ["--apk", str(apk), "--out", str(out), "--expected-signer", "a" * 64])
        self.assertFalse((out / "apk-identity.json").exists())
        self.assertEqual(cwd, Path.cwd())
        self.assertEqual(sdk, os.environ.get("ANDROID_HOME"))


if __name__ == "__main__":
    unittest.main()
