#!/usr/bin/env python3
"""Sign an offline release payload. This command never generates keys, uploads or publishes."""
from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


def integer(data: dict, name: str, maximum: int) -> int:
    value = data.get(name)
    if type(value) is not int or not 1 <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return value


def artifact(data: dict, maximum: int) -> None:
    if not isinstance(data, dict):
        raise ValueError("Invalid artifact")
    url = data.get("url", "")
    if not isinstance(url, str):
        raise ValueError("Invalid artifact URL")
    parsed = urlsplit(url)
    if len(url) > 4096 or parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
        raise ValueError("Artifact requires HTTPS without embedded credentials or fragments")
    if re.fullmatch(r"[a-f0-9]{64}", data.get("sha256", "")) is None:
        raise ValueError("Artifact requires a lower-case SHA-256 digest")
    integer(data, "bytes", maximum)


def validate(data: dict, now: int) -> None:
    if not isinstance(data, dict) or type(data.get("schema")) is not int or data.get("schema") != 1 or data.get("packageName") != "dev.pocketengine":
        raise ValueError("Expected PocketWorld update schema 1")
    integer(data, "versionCode", 2_100_000_000)
    name = data.get("versionName")
    if not isinstance(name, str) or not 1 <= len(name) <= 80 or "\0" in name:
        raise ValueError("Invalid versionName")
    notes = data.get("notes", "")
    if not isinstance(notes, str) or len(notes) > 4000 or "\0" in notes:
        raise ValueError("Invalid release notes")
    sdk = integer(data, "minSdk", 100)
    issued = integer(data, "issuedAt", 2**63 - 1)
    expires = integer(data, "expiresAt", 2**63 - 1)
    if sdk < 30 or issued > now + 300_000 or expires <= now or not 0 < expires - issued <= 7 * 86400000:
        raise ValueError("Invalid Android minimum or release validity window")
    artifact(data["apk"], 512 * 1024 * 1024)
    deltas = data.get("deltas", [])
    if not isinstance(deltas, list) or len(deltas) > 12:
        raise ValueError("Too many deltas")
    for delta in deltas:
        if not isinstance(delta, dict) or delta.get("format") != "bsdiff43-gzip-v1":
            raise ValueError("Only pinned Google BSDIFF43 GZIP deltas are supported")
        integer(delta, "baseVersionCode", 2_100_000_000)
        if re.fullmatch(r"[a-f0-9]{64}", delta.get("baseSha256", "")) is None:
            raise ValueError("Invalid base APK digest")
        artifact(delta, 256 * 1024 * 1024)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", type=Path, required=True, help="Reviewed release JSON, with actual APK identity and immutable HTTPS URLs")
    parser.add_argument("--private-key", type=Path, required=True, help="Operator-provided RSA PEM key. Never commit this file")
    parser.add_argument("--public-key", type=Path, required=True, help="Matching RSA public PEM used to verify output before saving")
    parser.add_argument("--output", type=Path, required=True, help="New signed-envelope JSON; must not already exist")
    args = parser.parse_args()
    try:
        if args.payload.stat().st_size > 64 * 1024:
            raise ValueError("Payload exceeds limit")
        data = json.loads(args.payload.read_text(encoding="utf-8"))
        validate(data, int(time.time() * 1000))
        payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
        if len(payload) > 64 * 1024:
            raise ValueError("Encoded payload exceeds limit")
        with tempfile.TemporaryDirectory(prefix="pocketworld-sign-manifest-") as temporary:
            root = Path(temporary)
            raw, signature = root / "payload.json", root / "signature.bin"
            raw.write_bytes(payload)
            public_pem = args.public_key.read_bytes()
            if not public_pem.startswith(b"-----BEGIN PUBLIC KEY-----") or b"PRIVATE KEY" in public_pem:
                raise ValueError("Expected an SPKI PUBLIC KEY PEM, never a private key")
            public = subprocess.run(["openssl", "rsa", "-pubin", "-in", str(args.public_key.resolve()), "-modulus", "-noout"],
                                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, timeout=30).stdout
            match = re.fullmatch(rb"Modulus=([A-Fa-f0-9]+)\s*", public)
            if not match or not 2048 <= int(match[1], 16).bit_length() <= 8192:
                raise ValueError("RSA public key must be 2048-8192 bits")
            subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(args.private_key.resolve()), "-out", str(signature), str(raw)],
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=30)
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(args.public_key.resolve()), "-signature", str(signature), str(raw)],
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True, timeout=30)
            envelope = {"payload": base64.b64encode(payload).decode("ascii"), "signature": base64.b64encode(signature.read_bytes()).decode("ascii")}
        with args.output.open("x", encoding="utf-8") as output:
            json.dump(envelope, output, separators=(",", ":"))
            output.write("\n")
        print("Signed manifest saved locally. Nothing was uploaded or published.")
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("Could not sign manifest. Check reviewed payload, RSA key pair and output path. No release was published.", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
