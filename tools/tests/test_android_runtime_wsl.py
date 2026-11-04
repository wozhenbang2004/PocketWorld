"""Check Windows artifact handoff without compiling or requiring a WSL distro."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which("powershell.exe")
NATIVE = Path("app/src/main/java/dev/pocketengine/features/local/runtime/native")
OUTPUT = Path("app/build/generated/android-runtime")


@unittest.skipUnless(os.name == "nt" and POWERSHELL, "Windows PowerShell required")
class WindowsRuntimeHandoffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocketworld-wsl-handoff-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative in (Path("tools/build-android-runtime.py"), NATIVE / "source-lock.json",
                         Path("app/src/main/assets/runtime/catalog.json")):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
        source = ROOT / OUTPUT
        if not (source / "native-build-receipt.json").is_file():
            self.skipTest("verified production native outputs required")
        shutil.copytree(source, self.root / OUTPUT, ignore=shutil.ignore_patterns("work", "sources"))
        self.output = self.root / OUTPUT

    def invoke(self, output=None):
        return subprocess.run([
            POWERSHELL, "-NoProfile", "-NonInteractive", "-File",
            str(ROOT / "tools/build-android-runtime-wsl.ps1"),
            "-SourceRoot", str(self.root), "-Output", str(output or self.output),
            "-Distribution", "PocketWorld-test-must-not-use-WSL", "-Ndk", "/missing-test-ndk", "-Offline",
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)

    def test_verified_output_needs_no_wsl_distribution(self):
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn(b"Verified local PRoot output; no WSL build needed.", result.stdout)

    def test_tampered_payload_is_not_trusted_or_overwritten(self):
        payload = self.output / "jniLibs/arm64-v8a/libproot.so"
        payload.write_bytes(b"tampered fixture")
        result = self.invoke()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn(b"Verified local PRoot output", result.stdout)
        self.assertEqual(b"tampered fixture", payload.read_bytes())

    def test_changed_source_lock_fails_before_handoff(self):
        with (self.root / NATIVE / "source-lock.json").open("ab") as stream:
            stream.write(b"\n")
        result = self.invoke()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(b"Runtime catalogue does not match source lock.", result.stdout)

    def test_output_outside_owned_directory_is_rejected(self):
        target = self.root / "unrelated-output"
        target.mkdir()
        sentinel = target / "keep.txt"
        sentinel.write_text("untouched")
        result = self.invoke(target)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(b"Runtime output must be the project-owned generated directory.", result.stdout)
        self.assertEqual("untouched", sentinel.read_text())
        self.assertEqual([sentinel], list(target.iterdir()))


if __name__ == "__main__":
    unittest.main()
