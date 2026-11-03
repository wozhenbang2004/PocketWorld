#!/usr/bin/env python3
"""Resolve the APK version consumed by app/build.gradle, without network or state.

Local development keeps the former version. The original APK workflow uses its
own run sequence; a re-run keeps the same version and cannot promote an old build.
This sequence is not repository-wide and does not order manually assigned releases.
Another CI/release chain passes BOTH -PpocketworldVersionCode and
-PpocketworldVersionName to Gradle. Its owner must choose a code above all already
distributed APKs, and keep subsequent releases/CI version policies above that code.

Primary contracts:
https://docs.github.com/en/actions/reference/workflows-and-actions/variables
https://developer.android.com/studio/publish/versioning
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections.abc import Mapping


LOCAL_CODE = 3
LOCAL_NAME = "0.3.0-dev"
VERSION_NAME_BASE = "0.3.0"
MAX_CODE = 2_100_000_000
REPOSITORY = "wozhenbang2004/PocketWorld"
WORKFLOW = ".github/workflows/build-apk.yml"
GITHUB_INPUTS = ("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_WORKFLOW_REF", "GITHUB_RUN_NUMBER")


def positive_code(value: str | None, label: str, maximum: int = MAX_CODE) -> int:
    # ASCII canonical decimal only: never truncate floats, wrap IDs, or overflow.
    if not isinstance(value, str) or re.fullmatch(r"[1-9][0-9]{0,9}", value) is None:
        raise ValueError(f"{label} must be a positive decimal integer")
    code = int(value)
    if code > maximum:
        raise ValueError(f"{label} exceeds the supported Android version range")
    return code


def resolve_version(environment: Mapping[str, str], code: str | None = None,
                    name: str | None = None) -> dict:
    if code is not None or name is not None:
        if code is None or name is None:
            raise ValueError("set both pocketworldVersionCode and pocketworldVersionName")
        explicit = positive_code(code, "pocketworldVersionCode")
        if not isinstance(name, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,79}", name) is None:
            raise ValueError("pocketworldVersionName must contain 1-80 version-label characters")
        return {"versionCode": explicit, "versionName": name, "source": "explicit_release",
                "ordering": "owner_assigned_must_exceed_previously_distributed_codes"}

    github = any(key in environment for key in GITHUB_INPUTS)
    ci = environment.get("CI", "").lower() not in ("", "false", "0")
    if not github and not ci:
        return {"versionCode": LOCAL_CODE, "versionName": LOCAL_NAME,
                "source": "local_development", "ordering": "fixed_local_version"}

    prefix = f"{REPOSITORY}/{WORKFLOW}@refs/"
    workflow_ref = environment.get("GITHUB_WORKFLOW_REF", "")
    if (environment.get("GITHUB_ACTIONS") != "true"
            or environment.get("GITHUB_REPOSITORY") != REPOSITORY
            or re.fullmatch(re.escape(prefix) + r"(?:heads|tags|pull)/[^\s]+", workflow_ref) is None):
        raise ValueError("automatic CI versions require the original build-apk workflow; "
                         "otherwise set both explicit version properties")
    run = positive_code(environment.get("GITHUB_RUN_NUMBER"), "GITHUB_RUN_NUMBER", MAX_CODE - LOCAL_CODE)
    return {"versionCode": LOCAL_CODE + run, "versionName": f"{VERSION_NAME_BASE}-ci.{run}",
            "source": "original_apk_workflow", "ordering": "workflow_run_number_only_reruns_unchanged"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-code", help="explicit Android version code (requires --version-name)")
    parser.add_argument("--version-name", help="explicit display version (requires --version-code)")
    arguments = parser.parse_args(argv)
    try:
        version = resolve_version(os.environ, arguments.version_code, arguments.version_name)
    except ValueError as invalid:
        # Never echo arbitrary environment/property values into build diagnostics.
        print("APK version configuration: " + str(invalid), file=sys.stderr)
        return 2
    print(json.dumps(version, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
