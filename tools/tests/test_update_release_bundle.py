"""Offline bundle-policy fixtures. No key generation, SDK, upload or signing."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / file)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


BUNDLE = load("prepare_update_release", "tools/prepare-update-release.py")
HOST = load("verify_update_host", "tools/verify-update-host.py")


class ReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.old, self.new, self.patch = (self.root / name for name in ("old.apk", "new.apk", "patch.gz"))
        self.old.write_bytes(b"old"); self.new.write_bytes(b"new target"); self.patch.write_bytes(b"zip")
        self.output = self.root / "bundle"
        self.origin = "https://example.chatgpt.site"
        self.config_root = self.root / "source"
        self.config_root.mkdir()
        self.manifest_url = "https://feed.example.chatgpt.site/releases/app/release.json"
        (self.config_root / "gradle.properties").write_text(
            "pocketworldUpdateManifestUrl=" + self.manifest_url + "\n", encoding="utf-8")
        configured_root = mock.patch.object(BUNDLE, "ROOT", self.config_root)
        configured_root.start()
        self.addCleanup(configured_root.stop)
        self.evidence = {"kind": "pocketworld-update-artifact-evidence", "sameSignerSet": True, "strictlyNewerVersion": True,
                         "base": BUNDLE.PREFLIGHT.artifact(self.old, 100) | {"versionCode": 3},
                         "target": BUNDLE.PREFLIGHT.artifact(self.new, 100) | {
                             "packageName": "dev.pocketengine", "versionCode": 4, "versionName": "test.4", "minSdk": 30},
                         "delta": BUNDLE.PREFLIGHT.artifact(self.patch, 100) | {"reconstructedByteExact": True, "smallerThanFullApk": True}}

    def tearDown(self): self.temporary.cleanup()

    def prepare(self, patch=True, **kwargs):
        args = {"evidence": self.evidence, "old": self.old, "new": self.new, "patch": self.patch if patch else None,
                "base_url": self.origin, "output": self.output, "notes": "Reviewed test notes", "now": 1_800_000_000_000, "valid_hours": 24}
        args.update(kwargs)
        return BUNDLE.prepare(**args)

    def test_hash_addressed_bundle_is_unsigned_and_manifest_last(self):
        plan = self.prepare()
        payload = json.loads((self.output / "unsigned-payload.json").read_text())
        self.assertEqual(self.manifest_url, plan["manifestUrl"])
        self.assertFalse(plan["readyForPublication"])
        self.assertFalse(plan["persistentKeyCreated"])
        self.assertFalse(plan["deviceInstallationVerified"])
        self.assertFalse((self.output / "release.json").exists())
        self.assertNotIn("signature", payload)
        self.assertEqual(24 * 3600000, payload["expiresAt"] - payload["issuedAt"])
        for artifact in plan["artifactUploads"]:
            self.assertIn("/artifacts/" + artifact["sha256"] + "/", artifact["url"])
            self.assertEqual(artifact["sha256"], BUNDLE.PREFLIGHT.artifact(self.output / artifact["file"], 100)["sha256"])
            self.assertTrue(artifact["rangeRequired"])
            self.assertEqual("identity", artifact["contentEncoding"])
        self.assertTrue(plan["manifestUpload"]["uploadLast"])
        self.assertEqual("no-store, max-age=0", plan["manifestUpload"]["cacheControl"])
        self.assertEqual(3, payload["deltas"][0]["baseVersionCode"])
        self.assertIn("# pocketworldUpdatePublicKey=", (self.output / "bootstrap-config.template.properties").read_text())

    def test_app_configured_feed_drives_plan_and_bootstrap_instead_of_artifact_origin(self):
        plan = self.prepare(patch=False)
        self.assertEqual(self.manifest_url, plan["manifestUrl"])
        self.assertEqual(self.manifest_url, plan["manifestUpload"]["url"])
        bootstrap = (self.output / "bootstrap-config.template.properties").read_text()
        self.assertIn("pocketworldUpdateManifestUrl=" + self.manifest_url + "\n", bootstrap)
        self.assertTrue(plan["artifactUploads"][0]["url"].startswith(self.origin + "/artifacts/"))

    def test_missing_or_ambiguous_app_feed_fails_before_creating_a_bundle(self):
        for settings in ("# no configured feed\n", "pocketworldUpdateManifestUrl=\n",
                         "pocketworldUpdateManifestUrl=" + self.manifest_url + "\n" +
                         "pocketworldUpdateManifestUrl=" + self.manifest_url + "\n"):
            with self.subTest(settings=settings):
                (self.config_root / "gradle.properties").write_text(settings, encoding="utf-8")
                with self.assertRaises(ValueError): self.prepare(patch=False)
                self.assertFalse(self.output.exists())

    def test_full_only_and_inefficient_delta_are_not_advertised(self):
        del self.evidence["delta"]
        self.assertEqual(1, len(self.prepare(patch=False)["artifactUploads"]))
        self.assertEqual([], json.loads((self.output / "unsigned-payload.json").read_text())["deltas"])

    def test_verified_inefficient_delta_is_omitted(self):
        self.evidence["delta"]["smallerThanFullApk"] = False
        plan = self.prepare()
        self.assertTrue(plan["inefficientDeltaOmitted"])
        self.assertEqual(1, len(plan["artifactUploads"]))

    def test_origins_cannot_hide_credentials_query_or_nonroot_paths(self):
        for value in ("http://example.com", "https://user:secret@example.com", "https://example.com/path",
                      "https://example.com?token=secret", "https://example.com#fragment"):
            with self.subTest(value=value), self.assertRaises(ValueError): BUNDLE.origin(value)
        self.assertEqual("https://example.com", BUNDLE.origin("https://example.com/"))

    def test_never_overwrites_a_bundle(self):
        self.output.mkdir(); sentinel = self.output / "mine"; sentinel.write_bytes(b"keep")
        with self.assertRaises(FileExistsError): self.prepare()
        self.assertEqual(b"keep", sentinel.read_bytes())

    def test_changed_inputs_missing_proof_and_invalid_notes_fail(self):
        for change in (lambda: self.new.write_bytes(b"changed"),
                       lambda: self.evidence.update(sameSignerSet=False),
                       lambda: self.evidence["delta"].update(reconstructedByteExact=False)):
            with self.subTest(change=change):
                original = self.new.read_bytes(); evidence = json.loads(json.dumps(self.evidence))
                change()
                with self.assertRaises(ValueError): self.prepare()
                self.assertFalse(self.output.exists())
                self.new.write_bytes(original); self.evidence = evidence
        for notes, hours in (("\0", 24), ("x" * 4001, 24), ("", 0), ("", 169)):
            with self.subTest(hours=hours), self.assertRaises(ValueError): self.prepare(notes=notes, valid_hours=hours)

    def test_failed_copy_cleans_only_new_bundle(self):
        with mock.patch.object(BUNDLE.shutil, "copyfile", side_effect=OSError("disk full")):
            with self.assertRaises(OSError): self.prepare()
        self.assertFalse(self.output.exists())
        self.assertEqual(b"new target", self.new.read_bytes())

    def test_cloud_proxy_is_explicit_and_uses_current_process_configuration(self):
        with mock.patch.dict(os.environ, {"HTTPS_PROXY": "http://127.0.0.1:12345"}, clear=True):
            self.assertEqual([], HOST.proxy_options(False))
            self.assertEqual(["-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=12345"], HOST.proxy_options(True))
        for value in ("", "https://example.com", "http://secret:password@example.com"):
            with self.subTest(value=value), mock.patch.dict(os.environ, {"HTTPS_PROXY": value}, clear=True):
                with self.assertRaises(ValueError): HOST.proxy_options(True)

    def test_host_probe_reuses_only_existing_locked_dependencies(self):
        jar = self.root / "existing.jar"; jar.touch()
        self.assertEqual(str(jar), HOST.classpath(str(jar), None))
        with self.assertRaises(ValueError): HOST.classpath(str(self.root / "missing.jar"), None)
        with self.assertRaises(ValueError): HOST.classpath(None, self.root / "empty-cache")


if __name__ == "__main__": unittest.main()
