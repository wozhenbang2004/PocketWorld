"""Runtime producer contracts using inert files; never Windows-build evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("pocket_runtime_builder", Path(__file__).parent / "packaging/windows/build_runtime.py")
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class RuntimeBuildContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="runtime-producer-inert-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def locked(self):
        return {"format": builder.FORMAT, "pythonVersion": "3.12.0", "pythonExecutableSha256": "1" * 64,
                "basePrefixSha256": "2" * 64, "environmentSha256": "3" * 64, "pythonLicenseSha256": "4" * 64,
                "toolchainDescription": "INERT contract fixture, NOT an approved or installed Windows builder",
                "packages": [{"name": name, "version": version, "wheel": name + "-" + version + "-py3-none-any.whl", "sha256": "5" * 64}
                             for name, version in [("pyinstaller", "6.0.0"), ("cryptography", "50.0.0"), ("qrcode", "8.2"), ("colorama", "0.4.6")]]}

    def write_lock(self, value):
        path = self.root / "reviewed-inputs.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path, builder.file_hash(path)

    @unittest.skipIf(os.name == "nt", "This counterexample specifically proves the Linux front-door refusal")
    def test_linux_refuses_before_reads_commands_or_output_creation(self):
        output = self.root / "must-not-exist"
        with patch.object(builder.subprocess, "run", side_effect=AssertionError("no command allowed")) as run:
            with self.assertRaisesRegex(ValueError, "Actual Windows x64"):
                builder.build("5" * 40, self.root / "missing-lock", "0" * 64, self.root / "missing-wheels", output)
        run.assert_not_called()
        self.assertFalse(output.exists())

    def test_reviewed_lock_closed_shape_digest_and_complete_dependency_set(self):
        path, digest = self.write_lock(self.locked())
        self.assertEqual(self.locked(), builder.read_lock(path, digest))
        with self.assertRaisesRegex(ValueError, "approved SHA"):
            builder.read_lock(path, "0" * 64)
        for mutate in (lambda value: value.update(allowCurrentEnvironment=True),
                       lambda value: value["packages"].append(dict(value["packages"][0])),
                       lambda value: value["packages"][0].update(wheel="../escape.whl"),
                       lambda value: value["packages"].pop()):
            value = self.locked(); mutate(value); path, digest = self.write_lock(value)
            with self.assertRaises(ValueError):
                builder.read_lock(path, digest)
        path.write_bytes(b'{"format":"one","format":"two"}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            builder.read_lock(path, builder.file_hash(path))

    def test_local_wheel_real_hash_identity_and_license_bytes_no_download(self):
        wheel = self.root / "fixture-1-py3-none-any.whl"
        def create(extra=None):
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("fixture-1.dist-info/METADATA", "Name: fixture\nVersion: 1\n")
                archive.writestr("fixture-1.dist-info/licenses/LICENSE.txt", b"INERT LICENSE fixture")
                archive.writestr("fixture/NOTICE.txt", b"INERT NOTICE fixture")
                if extra:
                    archive.writestr(extra, b"unsafe")
        create()
        item = {"name": "fixture", "version": "1", "sha256": builder.file_hash(wheel)}
        result = builder.wheel_licenses(wheel, item)
        self.assertEqual(b"INERT LICENSE fixture", result["fixture-1.dist-info/licenses/LICENSE.txt"])
        self.assertEqual(2, len(result))
        with self.assertRaisesRegex(ValueError, "differs from lock"):
            builder.wheel_licenses(wheel, {**item, "sha256": "0" * 64})
        create("../escape")
        with self.assertRaises(ValueError):
            builder.wheel_licenses(wheel, {**item, "sha256": builder.file_hash(wheel)})

    def test_same_qr_version_cannot_substitute_different_wheel_bytes(self):
        product = json.loads((Path(__file__).parent / "packaging/windows-pairing-dependencies.json").read_text())
        lock = self.locked()
        for item in product["components"]:
            record = next(record for record in lock["packages"] if record["name"] == item["name"])
            record.update({key: item[key] for key in ("version", "wheel", "sha256")})
        builder.require_pairing_wheels(lock, product)
        next(item for item in lock["packages"] if item["name"] == "qrcode")["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "official wheel lock"):
            builder.require_pairing_wheels(lock, product)

    def test_provenance_covers_actual_files_and_existing_consumer_fields_not_a_success_claim(self):
        source = self.root / "source"; management = source / "companion/packaging/windows"; management.mkdir(parents=True)
        for name in builder.MANAGEMENT:
            (management / name).write_bytes(b"INERT NEVER EXECUTED MANAGEMENT FIXTURE " + name.encode())
        (management / "pocket-companion.spec").write_bytes(b"INERT NEVER EXECUTED SPEC")
        frozen = self.root / "frozen"; frozen.mkdir()
        (frozen / "PocketWorld-Companion.exe").write_bytes(b"INERT NOT AN EXECUTABLE")
        (frozen / "runtime.dll").write_bytes(b"INERT DLL")
        notices = self.root / "notices"; notices.mkdir(); (notices / "LICENSE.txt").write_bytes(b"INERT LICENSE")
        versions = {item["name"]: item["version"] for item in self.locked()["packages"]}
        record = builder.record_outputs(source, "5" * 40, self.locked(), "6" * 64, versions, frozen, notices)
        self.assertEqual("pocketworld.windows-runtime-build.v1", record["format"])
        self.assertEqual({"PocketWorld-Companion.exe", "runtime.dll"}, set(record["unsignedFiles"]))
        self.assertEqual(builder.file_hash(frozen / "runtime.dll"), record["unsignedFiles"]["runtime.dll"])
        self.assertEqual(set(builder.MANAGEMENT), set(record["managementUnsignedFiles"]))
        for key in ("pythonVersion", "pyinstallerVersion", "cryptographyVersion", "toolchainDescription", "inputLockSha256", "licenseFiles"):
            self.assertTrue(record[key])  # Exact fields read by the original build_bundle consumer.
        self.assertNotIn("runtimeHelpProbe", record)  # Only actual build() can assert its real probe.
        self.assertNotIn("sourceClean", record)
        self.assertEqual("unverified", record["guiAcceptance"])
        (frozen / "runtime.dll").write_bytes(b"CHANGED INERT DLL")
        self.assertNotEqual(record["unsignedFiles"], builder.inventory(frozen))
        (frozen / "PocketWorld-Companion.exe").unlink()
        with self.assertRaisesRegex(ValueError, "executable missing"):
            builder.record_outputs(source, "5" * 40, self.locked(), "6" * 64, versions, frozen, notices)

    def test_alias_private_state_and_case_collision_refused(self):
        for name in ("../x", "C:/x", "a\\b", "a//b", "CON", "name.", "operations.sqlite3", "identity-key", "a/private.pfx"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                builder.safe_name(name)
        (self.root / "same").write_bytes(b"one")
        if os.name != "nt":
            (self.root / "Same").write_bytes(b"two")
            with self.assertRaisesRegex(ValueError, "Case-colliding"):
                builder.inventory(self.root)
            (self.root / "Same").unlink()
            (self.root / "alias").symlink_to(self.root / "same")
            with self.assertRaisesRegex(ValueError, "link/reparse"):
                builder.inventory(self.root)

    def test_child_environment_has_no_model_relay_companion_credentials(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "inert-model", "PW_ROUTE_TOKEN": "inert-route", "POCKETENGINE_COMPANION_TOKEN": "inert-companion", "PYTHONPATH": "unreviewed"}):
            child = builder.build_environment(self.root)
        for name in ("OPENAI_API_KEY", "PW_ROUTE_TOKEN", "POCKETENGINE_COMPANION_TOKEN", "PYTHONPATH"):
            self.assertNotIn(name, child)
        self.assertEqual("1", child["PIP_NO_INDEX"])
        self.assertEqual(str(self.root / "pyinstaller-cache"), child["PYINSTALLER_CONFIG_DIR"])


if __name__ == "__main__":
    unittest.main()
