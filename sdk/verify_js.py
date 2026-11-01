#!/usr/bin/env python3
"""Verify only executable_js static authoring. No W07 dependency or script engine is loaded."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from pocket_js import ROOT, sources


def one(directory, pattern):
    matches = sorted(directory.glob(pattern))
    if len(matches) != 1:
        raise ValueError(f"Need exactly one {pattern}")
    return str(matches[0].resolve())


def run(command, **kwargs):
    print("$ " + " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deps", required=True, type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--android-jvm", action="store_true")
    options = parser.parse_args()
    common = [one(options.deps, pattern) for pattern in ("junit-*.jar", "hamcrest-core-*.jar")]
    code = sources() + sorted((ROOT / "app/src/test/java/dev/pocketengine/features/plugins/sdk/js").glob("*.java"))
    for android in ([False, True] if options.android_jvm else [False]):
        deps = os.pathsep.join([one(options.deps, "android-all-*.jar" if android else "json-*.jar")] + common)
        with tempfile.TemporaryDirectory(prefix="pocket-js-verify-") as classes:
            run([options.java, "-XX:-UsePerfData", "--module", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                 "-encoding", "UTF-8", "-cp", deps, "-d", classes, *code])
            classpath = classes + os.pathsep + deps
            print("JSON implementation: " + ("Android API35 JVM" if android else "org.json desktop"), flush=True)
            run([options.java, "-XX:-UsePerfData", "-cp", classpath, "org.junit.runner.JUnitCore", "dev.pocketengine.features.plugins.sdk.js.JsPluginPackageTest"])
            if not android:
                env = dict(os.environ, POCKET_JS_TEST_CLASSPATH=classpath, POCKET_JS_TEST_JAVA=options.java, PYTHONDONTWRITEBYTECODE="1")
                run([sys.executable, "-B", "-m", "unittest", "discover", "-s", ROOT / "sdk/tests", "-p", "test_js*.py", "-v"], cwd=ROOT, env=env)


if __name__ == "__main__":
    main()
