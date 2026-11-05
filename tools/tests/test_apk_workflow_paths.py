"""Single-file changes which must (and must not) select the existing APK workflow.

Only literal paths and GitHub's * / ** globs are used by this workflow. Fail closed
if that contract is changed to a richer syntax, rather than silently mis-testing it.
No GitHub API call, workflow dispatch, dependency download, or APK build is needed.
"""
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/build-apk.yml"


def push_paths(text):
    push = re.search(r"(?m)^  push:\n(?P<body>(?:^    .*\n|^\n)+)", text)
    if not push:
        raise ValueError("expected on.push mapping")
    paths = re.search(r"(?m)^    paths:\n(?P<items>(?:^      - '[^']+'[ ]*\n)+)", push["body"])
    if not paths:
        # The former one-line declaration remains parseable for red/green probes.
        inline = re.search(r"(?m)^    paths: (\[[^\n]+\])$", push["body"])
        if not inline:
            raise ValueError("expected quoted, positive push.paths list")
        return json.loads(inline[1].replace("'", '"'))
    return re.findall(r"(?m)^      - '([^']+)'", paths["items"])


def matches(path, patterns):
    def regex(pattern):
        if any(c in pattern for c in "!?+[]{}\\"):
            raise ValueError("unsupported path-pattern syntax: " + pattern)
        return re.escape(pattern).replace(r"\*\*", ".*").replace(r"\*", "[^/]*")
    return any(re.fullmatch(regex(pattern), path) for pattern in patterns)


class ApkWorkflowPathsTest(unittest.TestCase):
    def setUp(self):
        self.patterns = push_paths(WORKFLOW.read_text())
        self.fixtures = json.loads((ROOT / "tools/tests/fixtures/apk-build-paths.json").read_text())

    def test_stable_signing_is_ephemeral_and_passwords_use_environment(self):
        workflow = WORKFLOW.read_text()
        signing = workflow.split('      - name: Apply stable project signing key\n', 1)[1].split('      - name:', 1)[0]
        self.assertIn('secrets.POCKETWORLD_SIGNING_KEYSTORE_B64', signing)
        self.assertIn('secrets.POCKETWORLD_SIGNING_PASSWORD', signing)
        self.assertIn('umask 077', signing)
        self.assertIn('set +x', signing)
        self.assertIn('mktemp -d "$RUNNER_TEMP/', signing)
        self.assertIn("trap 'rm -rf -- \"$SIGNING_DIR\"' EXIT", signing)
        self.assertIn('--ks-pass env:POCKETWORLD_SIGNING_PASSWORD', signing)
        self.assertIn('--key-pass env:POCKETWORLD_SIGNING_PASSWORD', signing)
        self.assertNotIn('pass:$', signing)
        self.assertIn('unset POCKETWORLD_SIGNING_PASSWORD', signing)
        self.assertIn('signer_digests == [certificate_sha256]', workflow)
        self.assertIn('ed896ec1199f5be4e591a3222ddd60486ea1fce6853f1bb850aa5b699f8b01a0', workflow)
        self.assertNotIn('Temporary debug signing only', workflow)

    def test_production_single_file_changes_trigger(self):
        for path in self.fixtures["build"]:
            with self.subTest(path=path):
                self.assertTrue(matches(path, self.patterns), "APK build misses production input: " + path)

    def test_unrelated_single_file_changes_do_not_package(self):
        for path in self.fixtures["ignore"]:
            with self.subTest(path=path):
                self.assertFalse(matches(path, self.patterns), "unrelated change selects APK build: " + path)

    def test_all_current_production_sources_and_gradle_inputs_are_covered(self):
        tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
        required = [path for path in tracked if path.startswith(("app/src/main/", "app/src/pendingAdb/java/", "display-helper/src/", "gradle/"))]
        self.assertGreater(len(required), 100, "must inspect the real tracked source tree")
        for path in required:
            with self.subTest(path=path):
                self.assertTrue(matches(path, self.patterns), path)

    def test_minimal_globs_do_not_match_other_roots(self):
        self.assertFalse(matches("other/app/src/main/Test.java", ["app/src/main/**"]))
        self.assertFalse(matches("gradle/wrapper/deeper/file", ["gradle/*"]))
        self.assertTrue(matches("gradle/wrapper/deeper/file", ["gradle/**"]))


if __name__ == "__main__":
    unittest.main()
