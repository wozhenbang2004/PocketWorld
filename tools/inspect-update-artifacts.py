#!/usr/bin/env python3
"""Read-only APK/delta release preflight. Produces evidence, never a signed release.

Requires existing official Android SDK build-tools and (for a patch) Java 17+.
Never generates credentials, signs, uploads, publishes, installs or configures a server.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_PATCH_BYTES = 256 * 1024 * 1024


def fingerprint(path: Path) -> tuple[int, ...]:
    status = path.stat()
    return status.st_dev, status.st_ino, status.st_size, status.st_mtime_ns, status.st_ctime_ns


def artifact(path: Path, maximum: int) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError("Artifact must be a nonempty regular file within its size limit: " + str(path))
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return {"bytes": path.stat().st_size, "sha256": digest.hexdigest()}


def run(command: list[str], timeout: int = 120) -> str:
    completed = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, check=True, timeout=timeout)
    if len(completed.stdout) > 1024 * 1024:
        raise ValueError("Unexpectedly large SDK output")
    return completed.stdout


def parse_badging(text: str) -> dict:
    lines = [line for line in text.splitlines() if line.startswith("package: ")]
    if len(lines) != 1 or re.search(r"\bsplit=", lines[0]):
        raise ValueError("Expected one standalone/base APK, not a split APK")
    package = re.search(r"\bname='([A-Za-z0-9_.]+)'", lines[0])
    code = re.search(r"\bversionCode='([0-9]+)'", lines[0])
    name = re.search(r"\bversionName='([^'\r\n]+)'", lines[0])
    sdk = re.findall(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", text, re.MULTILINE)
    if not package or not code or not name or len(sdk) != 1:
        raise ValueError("Incomplete APK identity from aapt")
    if not 1 <= int(code[1]) <= 2_100_000_000 or not 30 <= int(sdk[0]) <= 100 or len(name[1]) > 80:
        raise ValueError("APK identity exceeds supported update protocol")
    return {"packageName": package[1], "versionCode": int(code[1]),
            "versionName": name[1], "minSdk": int(sdk[0])}


def parse_signers(text: str) -> dict:
    counts = re.findall(r"^Number of signers: ([0-9]+)$", text, re.MULTILINE)
    certificates = re.findall(r"^Signer #([0-9]+) certificate SHA-256 digest: ([a-fA-F0-9]{64})$",
                              text, re.MULTILINE)
    schemes = re.findall(r"^Verified using (v[0-9.]+) scheme[^\r\n]*: true$", text, re.MULTILINE)
    if len(counts) != 1 or not 1 <= int(counts[0]) <= 16 or len(certificates) != int(counts[0]):
        raise ValueError("Missing, duplicate or unbounded APK signer evidence")
    if {int(index) for index, _ in certificates} != set(range(1, int(counts[0]) + 1)):
        raise ValueError("APK signer numbering mismatch")
    digests = {digest.lower() for _, digest in certificates}
    if len(digests) != len(certificates) or not {"v2", "v3", "v3.1"}.intersection(schemes):
        raise ValueError("APK needs distinct verified modern Android signers")
    return {"signerCertificateSha256": sorted(digests), "verifiedSignatureSchemes": sorted(schemes)}


def inspect_apk(path: Path, build_tools: Path) -> dict:
    result = artifact(path, MAX_APK_BYTES)
    result.update(parse_badging(run([str(build_tools / "aapt"), "dump", "badging", str(path)])))
    result.update(parse_signers(run([str(build_tools / "apksigner"), "verify", "--min-sdk-version", "30",
                                    "--verbose", "--print-certs", str(path)])))
    return result


def inspect(old: Path, new: Path, build_tools: Path, patch: Path | None = None,
            java_home: str | None = None) -> dict:
    files = [old, new] + ([patch] if patch is not None else [])
    before = {path: fingerprint(path) for path in files}
    base, target = inspect_apk(old, build_tools), inspect_apk(new, build_tools)
    if base["packageName"] != "dev.pocketengine" or target["packageName"] != base["packageName"]:
        raise ValueError("Both APKs must be the same PocketWorld package")
    if target["versionCode"] <= base["versionCode"]:
        raise ValueError("Target version code must strictly exceed base version code")
    if target["signerCertificateSha256"] != base["signerCertificateSha256"]:
        raise ValueError("Target signer set differs from the exact base; no key rotation is permitted")
    evidence = {"kind": "pocketworld-update-artifact-evidence", "publishableManifest": False,
                "base": base, "target": target, "sameSignerSet": True,
                "strictlyNewerVersion": True, "deviceInstallationVerified": False}
    if patch is not None:
        delta = artifact(patch, MAX_PATCH_BYTES)
        command = [sys.executable, "-B", str(ROOT / "tools/apk-delta.py")]
        if java_home:
            command += ["--java-home", java_home]
        command += ["verify", "--old", str(old), "--new", str(new), "--patch", str(patch)]
        if "reconstructedByteExact=true" not in run(command, timeout=600).splitlines():
            raise ValueError("Production delta verification did not confirm exact reconstruction")
        delta.update({"format": "bsdiff43-gzip-v1", "reconstructedByteExact": True,
                      "smallerThanFullApk": delta["bytes"] < target["bytes"],
                      "bytesSaved": target["bytes"] - delta["bytes"]})
        evidence["delta"] = delta
    if before != {path: fingerprint(path) for path in files}:
        raise ValueError("An input changed during verification; keep exact release artifacts immutable")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old", type=Path, required=True, help="Exact previously distributed standalone APK")
    parser.add_argument("--new", type=Path, required=True, help="Exact signed target APK")
    parser.add_argument("--build-tools", type=Path, required=True, help="Existing official SDK build-tools directory")
    parser.add_argument("--patch", type=Path, help="Optional existing GZIP delta to rebuild and verify")
    parser.add_argument("--java-home", help="Existing Java 17+ JDK for optional delta verification")
    parser.add_argument("--output", type=Path, help="Optional new evidence JSON; refuses overwrite. Otherwise stdout")
    args = parser.parse_args()
    try:
        tools = args.build_tools.resolve()
        for name in ("aapt", "apksigner"):
            if not (tools / name).is_file() or not os.access(tools / name, os.X_OK):
                raise ValueError("Missing existing official SDK executable: " + str(tools / name))
        evidence = inspect(args.old.resolve(), args.new.resolve(), tools,
                           args.patch.resolve() if args.patch else None, args.java_home)
        text = json.dumps(evidence, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            with args.output.open("x", encoding="utf-8") as output:
                output.write(text)
        else:
            print(text, end="")
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("Update artifact preflight failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
