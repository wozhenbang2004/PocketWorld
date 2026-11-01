import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SDK = Path(__file__).resolve().parents[1]


class JsToolingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classpath = os.environ["POCKET_JS_TEST_CLASSPATH"]
        cls.java = os.environ["POCKET_JS_TEST_JAVA"]

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pocket-js-cli-")
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)

    def cli(self, *args, expected=0):
        result = subprocess.run([sys.executable, "-B", str(SDK / "pocket_js.py"), "--java", self.java, "--classpath", self.classpath,
                                 *map(str, args)], capture_output=True, text=True, timeout=15)
        self.assertEqual(expected, result.returncode, result.stderr)
        return json.loads(result.stdout) if expected == 0 else result.stderr

    def project(self):
        project = self.path / "project"
        receipt = self.cli("init", project, "--id", "user.custom.js", "--name", "My real JS source")
        self.assertTrue(receipt["staticOnly"])
        self.assertFalse(receipt["initialized"])
        return project

    def test_template_pack_actual_bytes_hashes_and_no_overwrite(self):
        project = self.project(); target = self.path / "custom.pwjs.json"
        receipt = self.cli("pack", project, "--output", target)
        source = (project / "extension.js").read_bytes(); raw = target.read_bytes()
        self.assertEqual(hashlib.sha256(source).hexdigest(), receipt["sourceSha256"])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), receipt["packageSha256"])
        self.assertEqual("pocketworld.executable-js.v1", json.loads(raw)["format"])
        inspected = self.cli("inspect", target)
        self.assertEqual(receipt["packageSha256"], inspected["packageSha256"])
        self.assertFalse(inspected["approved"])
        self.assertFalse(inspected["described"])
        self.cli("pack", project, "--output", target, expected=2)
        self.assertEqual(raw, target.read_bytes())

    def test_validation_does_not_run_top_level_source_and_preserves_crlf_bom(self):
        project = self.project()
        source = b"\xef\xbb\xbfthrow new Error('MUST NEVER RUN');\r\nwhile (true) {}\r\n"
        (project / "extension.js").write_bytes(source)
        receipt = self.cli("validate", project)
        self.assertEqual(hashlib.sha256(source).hexdigest(), receipt["sourceSha256"])
        self.assertEqual(len(source), receipt["sourceByteSize"])
        self.assertTrue(receipt["staticOnly"])
        self.assertFalse(receipt["initialized"])

    def test_package_unknown_fields_and_digest_changes_are_blocked(self):
        project = self.project(); target = self.path / "one.pwjs.json"
        self.cli("pack", project, "--output", target)
        value = json.loads(target.read_bytes())
        value["approved"] = True
        target.write_text(json.dumps(value), encoding="utf-8")
        self.cli("inspect", target, expected=2)
        value.pop("approved"); value["source"] += "\n// changed"
        target.write_text(json.dumps(value), encoding="utf-8")
        self.cli("inspect", target, expected=2)

    def test_invalid_utf8_and_duplicate_author_metadata_are_rejected(self):
        project = self.project()
        (project / "extension.js").write_bytes(b"\xff")
        self.cli("validate", project, expected=2)
        (project / "project.json").write_text('{"id":"a","id":"b","name":"Name","version":"1"}', encoding="utf-8")
        self.cli("validate", project, expected=2)


if __name__ == "__main__":
    unittest.main()
