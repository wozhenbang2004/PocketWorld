"""Parser and orchestration fixtures, not APK signature or device-install claims."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("update_artifact_preflight", ROOT / "tools/inspect-update-artifacts.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)

SIGNER = "a" * 64


def badging(code=3, package="dev.pocketengine", minimum=30):
    return f"package: name='{package}' versionCode='{code}' versionName='test.{code}'\nminSdkVersion:'{minimum}'\ntargetSdkVersion:'35'\n"


def signature(digest=SIGNER):
    return "\n".join(["Verifies", "Verified using v1 scheme (JAR signing): false",
                      "Verified using v2 scheme (APK Signature Scheme v2): true",
                      "Number of signers: 1", "Signer #1 certificate SHA-256 digest: " + digest]) + "\n"


class UpdateArtifactPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.old = self.root / "old.apk"
        self.new = self.root / "new.apk"
        self.patch = self.root / "patch.gz"
        self.old.write_bytes(b"old")
        self.new.write_bytes(b"new target")
        self.patch.write_bytes(b"zip")
        self.build_tools = self.root / "official-sdk"

    def tearDown(self):
        self.temporary.cleanup()

    def sdk(self, command, timeout=120):
        if "badging" in command:
            return badging(3 if command[-1] == str(self.old) else 4)
        if "--print-certs" in command:
            return signature()
        if "verify" in command:
            return "reconstructedByteExact=true\n"
        raise AssertionError(command)

    def test_parse_real_sdk_output_shape(self):
        self.assertEqual({"packageName": "dev.pocketengine", "versionCode": 3,
                          "versionName": "test.3", "minSdk": 30}, TOOL.parse_badging(badging()))
        self.assertEqual(30, TOOL.parse_badging(badging().replace("minSdkVersion", "sdkVersion"))["minSdk"])
        self.assertEqual([SIGNER], TOOL.parse_signers(signature())["signerCertificateSha256"])

    def test_reject_missing_ambiguous_and_split_identity(self):
        for text in ("", badging() + badging(), badging().replace("versionCode='3'", ""),
                     badging().replace("package: ", "package: split='config.arm64' "),
                     badging() + "sdkVersion:'30'\n"):
            with self.subTest(text=text):
                with self.assertRaises(ValueError): TOOL.parse_badging(text)

    def test_reject_unsupported_protocol_identity(self):
        for code, sdk in ((0, 30), (2_100_000_001, 30), (4, 29), (4, 101)):
            with self.subTest(code=code, sdk=sdk):
                with self.assertRaises(ValueError): TOOL.parse_badging(badging(code, minimum=sdk))

    def test_reject_missing_duplicate_or_legacy_signers(self):
        for text in ("", signature().replace("true", "false"),
                     signature().replace("Number of signers: 1", "Number of signers: 17"),
                     signature() + "Signer #1 certificate SHA-256 digest: " + SIGNER + "\n",
                     signature().replace("Signer #1", "Signer #2"),
                     signature().replace(SIGNER, "not-a-digest")):
            with self.subTest(text=text):
                with self.assertRaises(ValueError): TOOL.parse_signers(text)

    def test_multiple_signers_normalized_as_exact_set(self):
        text = signature("B" * 64).replace("Number of signers: 1", "Number of signers: 2")
        text += "Signer #2 certificate SHA-256 digest: " + SIGNER + "\n"
        self.assertEqual([SIGNER, "b" * 64], TOOL.parse_signers(text)["signerCertificateSha256"])
        with self.assertRaises(ValueError): TOOL.parse_signers(text.replace("B" * 64, SIGNER))

    def test_same_signer_newer_artifacts_without_patch_are_evidence_only(self):
        with mock.patch.object(TOOL, "run", side_effect=self.sdk) as run:
            result = TOOL.inspect(self.old, self.new, self.build_tools)
        self.assertEqual("pocketworld-update-artifact-evidence", result["kind"])
        self.assertFalse(result["publishableManifest"])
        self.assertFalse(result["deviceInstallationVerified"])
        self.assertNotIn("delta", result)
        self.assertNotIn("url", str(result).lower())
        self.assertEqual(4, run.call_count)
        for call in (run.call_args_list[1], run.call_args_list[3]):
            self.assertIn("--min-sdk-version", call.args[0])
            self.assertIn("30", call.args[0])

    def test_optional_patch_calls_production_adapter_verification(self):
        with mock.patch.object(TOOL, "run", side_effect=self.sdk) as run:
            result = TOOL.inspect(self.old, self.new, self.build_tools, self.patch, "/trusted/jdk")
        delta = result["delta"]
        self.assertTrue(delta["reconstructedByteExact"])
        self.assertTrue(delta["smallerThanFullApk"])
        self.assertEqual(7, delta["bytesSaved"])
        command = run.call_args_list[-1].args[0]
        self.assertIn("/trusted/jdk", command)
        self.assertIn(str(ROOT / "tools/apk-delta.py"), command)
        self.assertIn("verify", command)
        self.assertNotIn("generate", command)

    def test_patch_needs_explicit_success_evidence(self):
        def sdk(command, timeout=120):
            return "unexpected output\n" if command[0] == TOOL.sys.executable else self.sdk(command, timeout)
        with mock.patch.object(TOOL, "run", side_effect=sdk):
            with self.assertRaises(ValueError): TOOL.inspect(self.old, self.new, self.build_tools, self.patch)

    def test_inefficient_verified_patch_is_not_marked_smaller(self):
        self.patch.write_bytes(b"x" * 20)
        with mock.patch.object(TOOL, "run", side_effect=self.sdk):
            result = TOOL.inspect(self.old, self.new, self.build_tools, self.patch)
        self.assertFalse(result["delta"]["smallerThanFullApk"])
        self.assertEqual(-10, result["delta"]["bytesSaved"])

    def test_wrong_package_version_or_signer_never_pass(self):
        for target, signer in ((badging(3), SIGNER), (badging(2), SIGNER),
                               (badging(4, "someone.else"), SIGNER), (badging(4), "b" * 64)):
            def sdk(command, timeout=120):
                if command[-1] == str(self.new):
                    return target if "badging" in command else signature(signer)
                return self.sdk(command, timeout)
            with self.subTest(target=target, signer=signer), mock.patch.object(TOOL, "run", side_effect=sdk):
                with self.assertRaises(ValueError): TOOL.inspect(self.old, self.new, self.build_tools)

    def test_input_mutation_aborts_evidence(self):
        def sdk(command, timeout=120):
            result = self.sdk(command, timeout)
            if "--print-certs" in command and command[-1] == str(self.new):
                self.old.write_bytes(b"changed")
            return result
        with mock.patch.object(TOOL, "run", side_effect=sdk):
            with self.assertRaises(ValueError): TOOL.inspect(self.old, self.new, self.build_tools)

    def test_zero_missing_directory_and_oversize_artifacts_rejected(self):
        empty = self.root / "empty.apk"; empty.touch()
        large = self.root / "large.apk"
        with large.open("wb") as output: output.truncate(TOOL.MAX_APK_BYTES + 1)
        for path in (self.root, empty, large, self.root / "missing"):
            with self.subTest(path=path):
                with self.assertRaises((ValueError, OSError)): TOOL.artifact(path, TOOL.MAX_APK_BYTES)

    def test_sdk_failure_is_not_mistaken_for_valid_signatures(self):
        with mock.patch.object(TOOL, "run", side_effect=subprocess.CalledProcessError(1, ["apksigner"])):
            with self.assertRaises(subprocess.CalledProcessError): TOOL.inspect(self.old, self.new, self.build_tools)

    def test_sdk_commands_are_argument_arrays_without_shell_execution(self):
        completed = subprocess.CompletedProcess([], 0, "Verifies\n", "")
        with mock.patch.object(TOOL.subprocess, "run", return_value=completed) as run:
            self.assertEqual("Verifies\n", TOOL.run(["/sdk/apksigner", "verify", "/tmp/a;echo bad.apk"]))
        self.assertIsInstance(run.call_args.args[0], list)
        self.assertNotIn("shell", run.call_args.kwargs)
        self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stdin"])


if __name__ == "__main__":
    unittest.main()
