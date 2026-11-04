#!/usr/bin/env python3
"""Opt-in read-only HTTPS download/cancel/resume probe using production UpdateDownloads.

Needs existing locked JVM dependencies and a Java 17+ JDK. Does not install APKs,
trust unsigned releases, upload, configure the application or create credentials.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlsplit

import importlib.util

ROOT = Path(__file__).resolve().parents[1]


def classpath(explicit, cache):
    supplied = explicit or os.environ.get("PW_UPDATE_TEST_CLASSPATH")
    if supplied:
        if not all(Path(item).is_file() for item in supplied.split(os.pathsep)):
            raise ValueError("Every classpath entry must be an existing locked dependency JAR")
        return supplied
    if cache is None:
        cache = Path(os.environ.get("GRADLE_RO_DEP_CACHE", os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")) + "/caches"))
    root = cache / "modules-2/files-2.1"
    coordinates = [("org.json", "json", "20240303"), ("com.squareup.okhttp3", "okhttp", "4.12.1"),
                   ("com.squareup.okio", "okio-jvm", "3.6.0"), ("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20")]
    paths = []
    for group, name, version in coordinates:
        found = list((root / group / name / version).glob("*/" + name + "-" + version + ".jar"))
        if len(found) != 1:
            raise ValueError("Missing or ambiguous locked dependency: " + name + "-" + version)
        paths.append(str(found[0]))
    return os.pathsep.join(paths)


def proxy_options(enabled):
    if not enabled:
        return []
    value = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    parsed = urlsplit(value or "")
    if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Environment proxy must be an existing HTTP CONNECT proxy without credentials")
    return ["-Dhttps.proxyHost=" + parsed.hostname, "-Dhttps.proxyPort=" + str(parsed.port or 80)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--expected-file", type=Path, required=True, help="Exact trusted local bytes, never a remote file reference")
    parser.add_argument("--kind", choices=("synthetic", "apk", "patch"), required=True)
    parser.add_argument("--cancel-after-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--java-home")
    parser.add_argument("--runtime-java", type=Path, help="Optional existing Java 17+ runtime executable; its default TLS trust remains unchanged")
    parser.add_argument("--classpath")
    parser.add_argument("--dependency-cache", type=Path, help="Existing read-only Gradle dependency cache root")
    parser.add_argument("--environment-proxy", action="store_true", help="Use this process's existing HTTPS_PROXY for cloud egress; TLS verification stays enabled")
    parser.add_argument("--output", type=Path, help="New evidence JSON, refuses overwrite; defaults to stdout")
    args = parser.parse_args()
    parsed = urlsplit(args.url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error("Probe requires an explicit HTTPS URL without credentials, query or fragment")
    expected = args.expected_file.resolve()
    if not expected.is_file() or not 0 < args.cancel_after_bytes < expected.stat().st_size <= 512 * 1024 * 1024:
        parser.error("Expected local file/offset must fit production artifact limits")
    spec = importlib.util.spec_from_file_location("apk_delta_host", ROOT / "tools/apk-delta.py")
    delta = importlib.util.module_from_spec(spec); spec.loader.exec_module(delta)
    java, javac = delta.java_tools(args.java_home)
    if args.runtime_java:
        if not args.runtime_java.is_file() or not os.access(args.runtime_java, os.X_OK):
            parser.error("Runtime Java must be an existing executable")
        java = str(args.runtime_java.resolve())
    dependencies = classpath(args.classpath, args.dependency_cache)
    with tempfile.TemporaryDirectory(prefix="pocketworld-update-host-") as temporary:
        classes = Path(temporary) / "classes"; classes.mkdir()
        sources = [ROOT / "app/src/main/java/dev/pocketengine/features/updates" / (name + ".java")
                   for name in ("UpdateDownloads", "UpdateManifest")]
        sources.append(ROOT / "tools/update-host/UpdateHostProbe.java")
        subprocess.run([javac, "--release", "17", "-cp", dependencies, "-d", str(classes)] + list(map(str, sources)), check=True)
        result = subprocess.run([java] + proxy_options(args.environment_proxy) + ["-Xmx128m", "-Djava.io.tmpdir=" + temporary, "-cp",
                                 str(classes) + os.pathsep + dependencies, "dev.pocketengine.features.updates.UpdateHostProbe",
                                 args.url, str(expected), str(args.cancel_after_bytes), args.kind],
                                check=True, stdout=subprocess.PIPE, text=True, timeout=1900)
        evidence = json.loads(result.stdout)
        evidence["environmentProxyUsed"] = args.environment_proxy
        text = json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            with args.output.open("x", encoding="utf-8") as output: output.write(text)
        else: print(text, end="")


if __name__ == "__main__":
    main()
