"""Isolated helper incremental builds, without configuring AGP or building an APK.

Set JAVA_HOME and ANDROID_HOME to the existing JDK/Android SDK. Set GRADLE_BIN
to an installed Gradle executable to also exercise the real task's up-to-date checks.
All compiler outputs and Gradle state belong to a temporary fixture.
"""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64"))
ANDROID_HOME = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Android/Sdk"))))
GRADLE_BIN = os.environ.get("GRADLE_BIN") or shutil.which("gradle")
TOOLCHAIN_AVAILABLE = all(path.is_file() for path in (
    JAVA_HOME / "bin/javac", JAVA_HOME / "bin/jar",
    ANDROID_HOME / "platforms/android-35/android.jar",
    ANDROID_HOME / "build-tools/35.0.0/d8",
))


@unittest.skipUnless(TOOLCHAIN_AVAILABLE, "existing JDK and Android 35 SDK required")
class DisplayHelperBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pocketworld-display-helper-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.helper = self.root / "display-helper/src/dev/pocketengine/displayhelper"
        self.shared = self.root / "app/src/main/java/dev/pocketengine/phone/display"
        self.helper.mkdir(parents=True)
        self.shared.mkdir(parents=True)
        (self.root / "tools").mkdir()
        shutil.copyfile(ROOT / "tools/build-display-helper.sh", self.root / "tools/build-display-helper.sh")
        (self.shared / "DisplayWire.java").write_text(
            'package dev.pocketengine.phone.display;\n'
            'public final class DisplayWire { public static final String VALUE = "wire-v1"; }\n')
        self.write_capabilities("capabilities-v1")
        self.write_main(with_inner=True)
        (self.helper / "RemovedHelper.java").write_text(
            'package dev.pocketengine.displayhelper;\n'
            'public final class RemovedHelper {}\n')
        self.build = self.root / "display-helper/build"
        self.output = self.root / "app/build/generated/display-helper-assets/pocketengine-display-helper.jar"
        self.env = dict(os.environ, JAVA_HOME=str(JAVA_HOME), ANDROID_HOME=str(ANDROID_HOME))

    def write_capabilities(self, value):
        (self.shared / "PhoneParallelCapabilities.java").write_text(
            'package dev.pocketengine.phone.display;\n'
            'public final class PhoneParallelCapabilities {\n'
            '  public static String current() { return "' + value + '"; }\n}\n')

    def write_main(self, with_inner):
        (self.helper / "Main.java").write_text(
            'package dev.pocketengine.displayhelper;\n'
            'import dev.pocketengine.phone.display.DisplayWire;\n'
            'import dev.pocketengine.phone.display.PhoneParallelCapabilities;\n'
            'public final class Main {\n'
            '  public static void main(String[] args) {\n'
            '    System.out.println(DisplayWire.VALUE + PhoneParallelCapabilities.current());\n'
            '  }\n' + ('  static final class RemovedInner {}\n' if with_inner else '') + '}\n')

    def run_command(self, command):
        result = subprocess.run(command, cwd=self.root, env=self.env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        self.assertEqual(0, result.returncode, result.stdout)
        return result.stdout

    def run_script(self):
        return self.run_command(["bash", "tools/build-display-helper.sh", str(self.output)])

    def test_rebuild_removes_deleted_sources_inner_classes_and_stale_dex(self):
        self.run_script()
        with zipfile.ZipFile(self.build / "helper-classes.jar") as classes:
            self.assertIn("dev/pocketengine/displayhelper/RemovedHelper.class", classes.namelist())
            self.assertIn("dev/pocketengine/displayhelper/Main$RemovedInner.class", classes.namelist())
        (self.build / "dex/classes2.dex").write_bytes(b"stale secondary dex")
        keep = [self.build / "unrelated-sentinel.txt", self.root / "app/build/unrelated-sentinel.txt"]
        for path in keep:
            path.write_text("keep this unrelated output")
        (self.helper / "RemovedHelper.java").unlink()
        self.write_main(with_inner=False)

        self.run_script()

        stale = [self.build / "classes/dev/pocketengine/displayhelper/RemovedHelper.class",
                 self.build / "classes/dev/pocketengine/displayhelper/Main$RemovedInner.class",
                 self.build / "dex/classes2.dex"]
        for path in stale:
            with self.subTest(path=path.relative_to(self.root)):
                self.assertFalse(path.exists(), "stale owned intermediate survived rebuild")
        with zipfile.ZipFile(self.build / "helper-classes.jar") as classes:
            self.assertNotIn("dev/pocketengine/displayhelper/RemovedHelper.class", classes.namelist())
            self.assertNotIn("dev/pocketengine/displayhelper/Main$RemovedInner.class", classes.namelist())
        with zipfile.ZipFile(self.output) as helper:
            dex = helper.read("classes.dex")
            self.assertNotIn(b"RemovedHelper", dex)
            self.assertNotIn(b"RemovedInner", dex)
        for path in keep:
            self.assertEqual("keep this unrelated output", path.read_text())
        self.assertTrue((self.helper / "Main.java").is_file())

    @unittest.skipUnless(GRADLE_BIN, "set GRADLE_BIN to an existing Gradle executable")
    def test_gradle_rebuilds_for_shared_capabilities_source_change(self):
        # Evaluate the production task verbatim in a plugin-free, two-project build.
        # This keeps Gradle's real input snapshots and Exec action, but excludes AGP/APK tasks.
        match = re.search(r"(?ms)^def helperTask = tasks.register\('buildDisplayHelper', Exec\) \{.*?^\}",
                          (ROOT / "app/build.gradle").read_text())
        self.assertIsNotNone(match, "production helper task registration not found")
        (self.root / "settings.gradle").write_text("rootProject.name = 'helper-fixture'\ninclude 'app'\n")
        (self.root / "build.gradle").write_text("")
        (self.root / "app/build.gradle").write_text(match[0] + "\n")
        command = [str(GRADLE_BIN), "--offline", "--no-daemon", "--console=plain", "--max-workers=1",
                   "--gradle-user-home", str(self.root / "gradle-user-home"),
                   "-Dorg.gradle.vfs.watch=false", "-Dorg.gradle.jvmargs=-Xmx256m -XX:MaxMetaspaceSize=256m",
                   ":app:buildDisplayHelper"]
        self.run_command(command)
        original = hashlib.sha256(self.output.read_bytes()).hexdigest()
        self.assertIn(":app:buildDisplayHelper UP-TO-DATE", self.run_command(command))

        self.write_capabilities("capabilities-v2")

        changed = self.run_command(command)
        self.assertNotIn(":app:buildDisplayHelper UP-TO-DATE", changed)
        self.assertNotEqual(original, hashlib.sha256(self.output.read_bytes()).hexdigest())
        with zipfile.ZipFile(self.output) as helper:
            self.assertIn(b"capabilities-v2", helper.read("classes.dex"))
        self.assertIn(":app:buildDisplayHelper UP-TO-DATE", self.run_command(command))


if __name__ == "__main__":
    unittest.main()
