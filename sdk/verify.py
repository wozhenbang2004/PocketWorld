#!/usr/bin/env python3
"""Focused SDK verification using existing dependencies; no Gradle, downloads or shared build cache."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from pocket_plugin import ROOT, java_sources


def one(directory, pattern):
    matches = sorted(directory.glob(pattern))
    if len(matches) != 1:
        raise ValueError(f"Need exactly one {pattern}, found {len(matches)}")
    return str(matches[0].resolve())


def run(command, **kwargs):
    print("$ " + " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deps", required=True, type=Path)
    parser.add_argument("--w07-source-root", type=Path, default=ROOT)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--android-jvm", action="store_true", help="Also compile/run against the actual Android JSON jar")
    options = parser.parse_args()
    if not options.java:
        raise ValueError("JDK17 java required")
    common = [one(options.deps, name) for name in ("okhttp-*.jar", "okio-jvm-*.jar", "kotlin-stdlib-*.jar", "junit-*.jar", "hamcrest-core-*.jar")]
    sources = java_sources(ROOT, options.w07_source_root.resolve())
    sources += sorted((ROOT / "app/src/test/java/dev/pocketengine/features/plugins/sdk").glob("*.java"))
    for android in ([False, True] if options.android_jvm else [False]):
        json_jar = one(options.deps, "android-all-*.jar" if android else "json-*.jar")
        dependencies = os.pathsep.join([json_jar] + common)
        with tempfile.TemporaryDirectory(prefix="pocket-sdk-verify-") as classes:
            run([options.java, "-XX:-UsePerfData", "--module", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                 "-encoding", "UTF-8", "-cp", dependencies, "-d", classes] + sources)
            classpath = classes + os.pathsep + dependencies
            print("JSON implementation: " + ("Android API35 JVM" if android else "org.json desktop"), flush=True)
            run([options.java, "-XX:-UsePerfData", "-cp", classpath, "org.junit.runner.JUnitCore", "dev.pocketengine.features.plugins.sdk.PluginSdkTest"])
            if not android:
                env = dict(os.environ, POCKET_PLUGIN_TEST_CLASSPATH=classpath, POCKET_PLUGIN_TEST_JAVA=options.java, PYTHONDONTWRITEBYTECODE="1")
                run([sys.executable, "-B", "-m", "unittest", "discover", "-s", ROOT / "sdk/tests", "-p", "test_tooling.py", "-v"], env=env, cwd=ROOT)


if __name__ == "__main__":
    main()
