"""Local-only release signing. All cryptographic material below is disposable test data."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "tools/sign-update-manifest.py"
SPEC = importlib.util.spec_from_file_location("update_signer", TOOL)
SIGNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIGNER)


class UpdateSigningTest(unittest.TestCase):
    def payload(self):
        now = int(time.time() * 1000)
        return {"schema": 1, "packageName": "dev.pocketengine", "versionCode": 4, "versionName": "0.3.1", "minSdk": 30,
                "issuedAt": now - 1000, "expiresAt": now + 3600000,
                "apk": {"url": "https://private.example/app.apk", "bytes": 100, "sha256": "a" * 64}}

    def test_rejects_non_objects_and_boolean_or_string_numbers(self):
        for value in ([], None, {}, self.payload() | {"schema": True}, self.payload() | {"versionCode": "4"}, self.payload() | {"versionCode": True}):
            with self.subTest(value=value), self.assertRaises((ValueError, KeyError)):
                SIGNER.validate(value, int(time.time() * 1000))

    def test_rejects_cleartext_credentials_wrong_hash_and_large_artifact(self):
        for replacement in ({"url": "http://private.example/a"}, {"url": "https://user:password@private.example/a"},
                            {"sha256": "bad"}, {"bytes": 512 * 1024 * 1024 + 1}):
            payload = self.payload(); payload["apk"].update(replacement)
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                SIGNER.validate(payload, int(time.time() * 1000))

    def test_rejects_wrong_format_or_expired_manifest(self):
        payload = self.payload(); payload["deltas"] = [{"format": "unknown"}]
        with self.assertRaises(ValueError): SIGNER.validate(payload, int(time.time() * 1000))
        payload = self.payload(); payload["expiresAt"] = payload["issuedAt"]
        with self.assertRaises(ValueError): SIGNER.validate(payload, int(time.time() * 1000))

    def test_actual_openssl_envelope_verifies_and_never_overwrites(self):
        with tempfile.TemporaryDirectory(prefix="pocketworld-signing-test-") as temporary:
            root = Path(temporary); private, public = root / "test-private.pem", root / "test-public.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            payload, envelope = root / "payload.json", root / "release.json"; payload.write_text(json.dumps(self.payload()))
            command = [sys.executable, "-B", str(TOOL), "--payload", str(payload), "--private-key", str(private), "--public-key", str(public), "--output", str(envelope)]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            signed = envelope.read_bytes(); value = json.loads(signed)
            raw, signature = root / "raw", root / "signature"; raw.write_bytes(base64.b64decode(value["payload"])); signature.write_bytes(base64.b64decode(value["signature"]))
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public), "-signature", str(signature), str(raw)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            retry = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(2, retry.returncode); self.assertEqual(signed, envelope.read_bytes())
            # A private key in the public-key slot is rejected before producing an envelope.
            envelope.unlink(); command[command.index(str(public))] = str(private)
            self.assertEqual(2, subprocess.run(command, capture_output=True, text=True, check=False).returncode)
            self.assertFalse(envelope.exists())


if __name__ == "__main__":
    unittest.main()
