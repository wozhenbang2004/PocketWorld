"""Exercise the exact process interface used by Gradle; no SDK/network/secret needed."""

import json
import os
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
RESOLVER = ROOT / "tools/app-version.py"
REPO = "wozhenbang2004/PocketWorld"


def original_run(number="142", attempt="1", ref="refs/heads/main"):
    return {"CI": "true", "GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": REPO,
            "GITHUB_WORKFLOW_REF": f"{REPO}/.github/workflows/build-apk.yml@{ref}",
            "GITHUB_RUN_NUMBER": number, "GITHUB_RUN_ATTEMPT": attempt}


class AppVersionTest(unittest.TestCase):
    def invoke(self, environment=None, code=None, name=None):
        env = {key: value for key, value in os.environ.items()
               if key != "CI" and not key.startswith("GITHUB_")}
        env.update(environment or {})
        command = [sys.executable, "-B", str(RESOLVER)]
        if code is not None:
            command += ["--version-code", code]
        if name is not None:
            command += ["--version-name", name]
        return subprocess.run(command, env=env, capture_output=True, text=True, check=False, timeout=10)

    def accepted(self, environment=None, code=None, name=None):
        result = self.invoke(environment, code, name)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stderr)
        return json.loads(result.stdout)

    def rejected(self, environment=None, code=None, name=None):
        result = self.invoke(environment, code, name)
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout, "invalid configuration must not publish a fallback version")
        self.assertIn("APK version configuration:", result.stderr)
        return result

    def test_local_development_preserves_existing_version(self):
        for environment in ({}, {"CI": "false"}, {"CI": "0"}):
            with self.subTest(environment=environment):
                result = self.accepted(environment)
                self.assertEqual((3, "0.3.0-dev", "local_development"),
                                 (result["versionCode"], result["versionName"], result["source"]))

    def test_successive_original_workflow_runs_increase_and_exceed_old_apk(self):
        first = self.accepted(original_run("1"))
        next_run = self.accepted(original_run("143"))
        previous_run = self.accepted(original_run("142"))
        self.assertGreater(first["versionCode"], 3)
        self.assertGreater(next_run["versionCode"], previous_run["versionCode"])
        self.assertEqual((145, "0.3.0-ci.142"), (previous_run["versionCode"], previous_run["versionName"]))
        self.assertIn("workflow_run_number_only", previous_run["ordering"])

    def test_rerunning_older_commit_cannot_promote_it_over_newer_run(self):
        old = self.accepted(original_run("142", "1"))
        rerun = self.accepted(original_run("142", "50"))
        newer = self.accepted(original_run("143", "1"))
        self.assertEqual(old, rerun)
        self.assertLess(rerun["versionCode"], newer["versionCode"])

    def test_branches_prs_and_tags_of_same_workflow_share_its_counter(self):
        for ref in ("refs/heads/pro/version-test", "refs/pull/9/merge", "refs/tags/v0.4.0"):
            with self.subTest(ref=ref):
                self.assertEqual(145, self.accepted(original_run(ref=ref))["versionCode"])

    def test_wrong_or_missing_workflow_identity_never_uses_its_counter(self):
        cases = [{"CI": "true"}, {"GITHUB_RUN_NUMBER": "142"}]
        for field, value in (("GITHUB_ACTIONS", "false"), ("GITHUB_REPOSITORY", "someone/PocketWorld"),
                             ("GITHUB_WORKFLOW_REF", f"{REPO}/.github/workflows/other.yml@refs/heads/main"),
                             ("GITHUB_WORKFLOW_REF", f"{REPO}/.github/workflows/build-apk.yml@"),
                             ("GITHUB_WORKFLOW_REF", f"{REPO}/.github/workflows/build-apk.yml@refs/heads/main\n")):
            env = original_run()
            env[field] = value
            cases.append(env)
        for field in ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_WORKFLOW_REF", "GITHUB_RUN_NUMBER"):
            env = original_run()
            del env[field]
            cases.append(env)
        for environment in cases:
            with self.subTest(environment=environment):
                self.rejected(environment)

    def test_malformed_and_overflowing_ci_numbers_fail_without_wrapping(self):
        for value in ("", "0", "-1", "01", "1.5", "1e4", " 142", "142\n", "１２３", "2100000000", "9" * 100):
            with self.subTest(value=value):
                self.rejected(original_run(value))
        edge = self.accepted(original_run("2099999997"))
        self.assertEqual(2100000000, edge["versionCode"])
        self.rejected(original_run("2099999998"))

    def test_explicit_release_pair_is_consumed_even_in_another_ci(self):
        for environment in ({}, {"CI": "true"}, original_run(), {"GITHUB_ACTIONS": "true"}):
            with self.subTest(environment=environment):
                version = self.accepted(environment, "5000", "0.4.0-rc.1+build.2")
                self.assertEqual((5000, "0.4.0-rc.1+build.2", "explicit_release"),
                                 (version["versionCode"], version["versionName"], version["source"]))
                self.assertIn("owner_assigned", version["ordering"])

    def test_partial_blank_or_unsafe_release_inputs_do_not_fall_back(self):
        pairs = [("5000", None), (None, "0.4.0"), ("", "0.4.0"), ("5000", ""),
                 ("0", "0.4.0"), ("-1", "0.4.0"), ("001", "0.4.0"), ("1.2", "0.4.0"),
                 ("2100000001", "0.4.0"), ("5000", "0.4.0\nsecret-marker"),
                 ("5000", "a" * 81), ("5000", "$(secret-marker)"), ("5000", " 0.4.0")]
        for code, name in pairs:
            with self.subTest(code=code, name=name):
                failure = self.rejected(original_run(), code, name)
                self.assertNotIn("secret-marker", failure.stderr)
        self.assertEqual(2100000000, self.accepted(code="2100000000", name="1.0.0")["versionCode"])

    def test_large_run_id_and_other_environment_never_become_version_or_output(self):
        environment = original_run()
        environment.update({"GITHUB_RUN_ID": "41586648704", "UNRELATED_SECRET": "private-marker"})
        version = self.accepted(environment)
        self.assertEqual(145, version["versionCode"])
        self.assertNotIn("private-marker", json.dumps(version))
        self.assertNotIn("41586648704", json.dumps(version))


if __name__ == "__main__":
    unittest.main()
