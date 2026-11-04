#!/usr/bin/env python3
"""Prepare a reviewable local update upload bundle. Never signs, uploads or creates keys.

Runs the existing APK/signer/delta preflight, copies hash-addressed immutable
artifacts, and produces an UNSIGNED payload plus a manifest-last publication plan.
The client still needs a separately approved pinned manifest public key and bootstrap.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("update_artifact_preflight", ROOT / "tools/inspect-update-artifacts.py")
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)


def origin(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password \
            or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise ValueError("Use the explicit public HTTPS origin, without credentials, query or path")
    return value.rstrip("/")


def prepare(evidence: dict, old: Path, new: Path, patch: Path | None, base_url: str,
            output: Path, notes: str, now: int, valid_hours: int) -> dict:
    base_url = origin(base_url)
    if not 1 <= valid_hours <= 168 or len(notes) > 4000 or "\0" in notes:
        raise ValueError("Validity must be 1-168 hours and release notes at most 4000 characters")
    if evidence.get("kind") != "pocketworld-update-artifact-evidence" \
            or evidence.get("sameSignerSet") is not True or evidence.get("strictlyNewerVersion") is not True:
        raise ValueError("Exact APK preflight evidence is required")
    target, base = evidence["target"], evidence["base"]
    for path, metadata in ((old, base), (new, target)):
        if PREFLIGHT.artifact(path, PREFLIGHT.MAX_APK_BYTES) != {key: metadata[key] for key in ("bytes", "sha256")}:
            raise ValueError("APK changed since preflight")
    delta = evidence.get("delta")
    use_delta = patch is not None and delta is not None and delta.get("reconstructedByteExact") is True \
        and delta.get("smallerThanFullApk") is True
    if patch is not None and (delta is None or delta.get("reconstructedByteExact") is not True):
        raise ValueError("A supplied delta must pass exact production-adapter reconstruction")
    if patch is not None and PREFLIGHT.artifact(patch, PREFLIGHT.MAX_PATCH_BYTES) != {key: delta[key] for key in ("bytes", "sha256")}:
        raise ValueError("Patch changed since preflight")
    # mkdir is exclusive: an existing directory, APK or release bundle is never replaced.
    output.mkdir()
    try:
        files = []

        def stage(source: Path, metadata: dict, filename: str, content_type: str) -> dict:
            relative = "artifacts/" + metadata["sha256"] + "/" + filename
            destination = output / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            if PREFLIGHT.artifact(destination, PREFLIGHT.MAX_APK_BYTES) != {key: metadata[key] for key in ("bytes", "sha256")}:
                raise ValueError("Copied artifact differs from exact verified input")
            result = {"url": base_url + "/" + relative, "bytes": metadata["bytes"], "sha256": metadata["sha256"]}
            files.append(result | {"file": relative, "contentType": content_type,
                                   "cacheControl": "public, max-age=31536000, immutable",
                                   "contentEncoding": "identity", "rangeRequired": True})
            return result

        apk = stage(new, target, "app-" + str(target["versionCode"]) + ".apk", "application/vnd.android.package-archive")
        payload = {"schema": 1, "packageName": target["packageName"], "versionCode": target["versionCode"],
                   "versionName": target["versionName"], "minSdk": target["minSdk"], "issuedAt": now,
                   "expiresAt": now + valid_hours * 3600000, "notes": notes, "apk": apk, "deltas": []}
        if use_delta:
            artifact = stage(patch, delta, "from-" + str(base["versionCode"]) + "-to-" + str(target["versionCode"]) + ".patch.gz",
                             "application/gzip")
            payload["deltas"].append(artifact | {"format": "bsdiff43-gzip-v1", "baseVersionCode": base["versionCode"],
                                                "baseSha256": base["sha256"]})
        manifest_url = base_url + "/releases/stable/release.json"
        plan = {"kind": "pocketworld-unsigned-update-upload-plan", "readyForPublication": False,
                "manifestUrl": manifest_url, "artifactUploads": files,
                "inefficientDeltaOmitted": patch is not None and not use_delta,
                "manifestUpload": {"file": "release.json", "exists": False, "uploadLast": True,
                                   "cacheControl": "no-store, max-age=0", "contentType": "application/json",
                                   "signatureRequired": "SHA256withRSA over exact decoded payload bytes"},
                "requiredBeforePublication": [
                    "Approve or supply the persistent manifest RSA signing key; only its SPKI public key goes into the APK.",
                    "Build and inspect a same-Android-signer bootstrap with the exact manifest URL and pinned public key; version must exceed every distributed build.",
                    "Review and sign unsigned-payload.json locally with tools/sign-update-manifest.py and the approved existing key pair.",
                    "Upload immutable artifacts first, verify public bytes and exact Range/206 behavior, then upload only the verified signed release.json.",
                    "Run the configured APK on a supported device and verify manual bootstrap, notification, interrupted download, permission denial/return and cover-install."
                ], "deviceInstallationVerified": False, "persistentKeyCreated": False}
        for filename, data in (("unsigned-payload.json", payload), ("artifact-evidence.json", evidence), ("release-plan.json", plan)):
            (output / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (output / "bootstrap-config.template.properties").write_text(
            "# Template only. No public key is supplied, so this is intentionally not a usable build config.\n"
            "pocketworldUpdateManifestUrl=" + manifest_url + "\n"
            "# pocketworldUpdatePublicKey=<approved base64 RSA SPKI PUBLIC key, never a private key>\n", encoding="utf-8")
        return plan
    except BaseException:
        shutil.rmtree(output)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True, help="Confirmed public HTTPS Site origin")
    parser.add_argument("--old", type=Path, required=True)
    parser.add_argument("--new", type=Path, required=True)
    parser.add_argument("--patch", type=Path)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--java-home")
    parser.add_argument("--notes", default="")
    parser.add_argument("--valid-hours", type=int, default=24)
    parser.add_argument("--output-dir", type=Path, required=True, help="New local directory; never replaces an existing bundle")
    args = parser.parse_args()
    try:
        output = args.output_dir.resolve()
        if output.exists(): raise ValueError("Output directory already exists")
        base_url = origin(args.origin)
        old, new = args.old.resolve(), args.new.resolve()
        patch = args.patch.resolve() if args.patch else None
        evidence = PREFLIGHT.inspect(old, new, args.build_tools.resolve(), patch, args.java_home)
        plan = prepare(evidence, old, new, patch, base_url, output, args.notes, int(time.time() * 1000), args.valid_hours)
        print(json.dumps({"output": str(output), "manifestUrl": plan["manifestUrl"],
                          "artifacts": len(plan["artifactUploads"]), "readyForPublication": False,
                          "signedManifestCreated": False, "persistentKeyCreated": False}, indent=2))
        return 0
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("Local release preparation failed: " + str(error), file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
