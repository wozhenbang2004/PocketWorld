#!/usr/bin/env python3
"""Verify exact released contract assets and generated runtime constants; read-only.

profileId = pwp1_ + SHA256(PocketWorld canonical profile.json without profileId).
The manifest hashes schema bytes (whose profile field is a pattern, not this ID),
so there is no self-hash cycle. Stable spend/correlation ID namespaces deliberately
exclude the profile digest: application updates must not reset existing pools.
"""
import hashlib
import json
from pathlib import Path
import re


def verify():
    directory = Path(__file__).resolve().parent
    root = directory.parents[1]
    manifest = json.loads((directory / "profile.json").read_text(encoding="utf-8"))
    body = {key: value for key, value in manifest.items() if key != "profileId"}
    canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    expected = "pwp1_" + hashlib.sha256(canonical).hexdigest()
    if manifest["profileId"] != expected:
        raise ValueError("Profile manifest identity differs from canonical machine metadata")
    for name, record in body["files"].items():
        if Path(name).name != name:
            raise ValueError("Profile input is not a fixed local contract asset")
        raw = (directory / name).read_bytes()
        if len(raw) != record["bytes"] or hashlib.sha256(raw).hexdigest() != record["sha256"]:
            raise ValueError("Profile input bytes changed: " + name)
    for file, pattern in (("companion/device_protocol.py", r'PROFILE = "(pwp1_[a-f0-9]{64})"'),
                          ("app/src/main/java/dev/pocketengine/features/devices/DeviceProtocol.java", r'PROFILE="(pwp1_[a-f0-9]{64})"')):
        matches = re.findall(pattern, (root / file).read_text(encoding="utf-8"))
        if matches != [expected]:
            raise ValueError("Runtime profile constant differs: " + file)
    return {"profileId": expected, "contractAssets": len(body["files"]), "runtimeConstants": 2, "status": "verified"}


if __name__ == "__main__":
    print(json.dumps(verify(), sort_keys=True))
