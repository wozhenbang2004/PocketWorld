#!/usr/bin/env python3
"""Offline, pinned Google bsdiff generation and focused host regression entrypoint.

No SDK, Gradle, network, signing credentials, publishing or installed package access.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / "third-party/archive-patcher"


def verify_sources():
    manifest = json.loads((UPSTREAM / "UPSTREAM.json").read_text())
    if manifest["commit"] != "50ca40a3de7983392a383ed3cc7b48e25f1b69b3":
        raise RuntimeError("Unexpected archive-patcher commit")
    for relative, expected in manifest["files"].items():
        data = (UPSTREAM / relative).read_bytes()
        if len(data) != expected["bytes"] or hashlib.sha256(data).hexdigest() != expected["sha256"]:
            raise RuntimeError("Pinned source/fixture integrity mismatch: " + relative)
    return sorted(UPSTREAM / relative for relative in manifest["files"] if relative.endswith(".java"))


def java_tools(java_home):
    home = java_home or os.environ.get("JAVA_HOME")
    if home:
        java, javac = (Path(home) / "bin" / tool for tool in ("java", "javac"))
    else:
        java, javac = (Path(shutil.which(tool) or "") for tool in ("java", "javac"))
    if not java.is_file() or not javac.is_file():
        raise RuntimeError("A Java 17+ JDK is required; set JAVA_HOME or --java-home")
    return str(java), str(javac)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("test")
    generate = commands.add_parser("generate")
    generate.add_argument("--old", required=True)
    generate.add_argument("--new", required=True)
    generate.add_argument("--output", required=True)
    verify = commands.add_parser("verify", help="Rebuild an existing patch and compare the complete target hash")
    verify.add_argument("--old", required=True)
    verify.add_argument("--new", required=True)
    verify.add_argument("--patch", required=True)
    args = parser.parse_args()
    sources = verify_sources()
    java, javac = java_tools(args.java_home)
    sources += [ROOT / "app/src/main/java/dev/pocketengine/features/updates/ApkDelta.java"]
    sources += sorted((ROOT / "tools/apk-delta").glob("*.java"))
    with tempfile.TemporaryDirectory(prefix="pocketworld-delta-host-") as temporary:
        directory = Path(temporary)
        classes = directory / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "17", "-d", str(classes)] + [str(p) for p in sources], check=True)
        command = [java, "-Xmx256m", "-Djava.io.tmpdir=" + str(directory), "-cp", str(classes)]
        if args.command == "test":
            command += ["dev.pocketengine.tools.ApkDeltaRegression", str(UPSTREAM)]
        elif args.command == "generate":
            command += ["dev.pocketengine.tools.ApkDeltaTool", args.old, args.new, args.output]
        else:
            command += ["dev.pocketengine.tools.ApkDeltaVerifier", args.old, args.new, args.patch]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
