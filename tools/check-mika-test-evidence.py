#!/usr/bin/env python3
"""Audit every focused selector against one isolated phase of original Gradle XML.

Only top-level TEST-*.xml files are read; archived main-capabilities subdirectories
are not another execution. Supply a fresh phase directory or the exact workflow
artifact: XML alone cannot prove its workflow/source provenance. Counts come from
testcases, including ordinary JUnit assumption skips, without a skip whitelist.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

OFFLINE_CLASS = "dev.pocketengine.features.local.runtime.NativeOfflineUnavailableTest"
COUNTS = ("tests", "errors", "failures", "skipped", "nonSkipped")


def selected_classes(path: Path) -> list[str]:
    names = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        name = raw.strip()
        if not name or name.startswith("#"):
            continue
        if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", name) or name in names:
            raise ValueError(f"Invalid or duplicate exact selector at {path}:{number}")
        names.append(name)
    if not names:
        raise ValueError("The original selector list is empty")
    return names


def audit(results: Path, expected: list[str]) -> dict:
    metrics = {name: Counter({key: 0 for key in COUNTS}) for name in expected}
    present, unexpected, problems, files = set(), set(), [], []

    def walk(node: ET.Element, suite: str | None, file: str) -> Counter:
        tag = node.tag.rsplit("}", 1)[-1]
        if tag == "testsuite":
            suite = node.get("classname") or node.get("name") or suite
            if suite in metrics:
                present.add(suite)
        if tag == "testcase":
            name = node.get("classname") or suite
            tags = {child.tag.rsplit("}", 1)[-1] for child in node}
            skipped = int("skipped" in tags)
            counts = Counter(tests=1, errors=int("error" in tags),
                             failures=int("failure" in tags), skipped=skipped,
                             nonSkipped=1 - skipped)
            if name in metrics:
                present.add(name)
                metrics[name].update(counts)
            else:
                unexpected.add(name or "<missing classname>")
            return counts
        counts = Counter()
        for child in node:
            counts.update(walk(child, suite, file))
        if tag == "testsuite":
            for key in ("tests", "errors", "failures", "skipped"):
                if node.get(key) is None:
                    continue
                try:
                    declared = int(node.get(key))
                except ValueError:
                    declared = -1
                if declared < 0 or declared != counts[key]:
                    problems.append({"kind": "suite_counter_mismatch", "file": file,
                                     "suite": suite, "counter": key,
                                     "declared": node.get(key), "testcaseCount": counts[key]})
        return counts

    for path in sorted(results.glob("TEST-*.xml")):
        try:
            raw = path.read_bytes()
            files.append({"path": str(path), "sha256": hashlib.sha256(raw).hexdigest()})
            walk(ET.fromstring(raw), None, str(path))
        except (OSError, ET.ParseError) as failure:
            problems.append({"kind": "unreadable_xml", "file": str(path), "error": str(failure)})

    classes, all_skipped, empty = {}, [], []
    for name, counts in metrics.items():
        if name not in present:
            state = "missing"
        elif not counts["tests"]:
            state = "present_without_testcases"
            empty.append(name)
        elif not counts["nonSkipped"]:
            state = "all_skipped"
            all_skipped.append(name)
        elif counts["errors"] or counts["failures"]:
            state = "executed_with_failures"
        elif counts["skipped"]:
            state = "executed_with_skips"
        else:
            state = "executed"
        classes[name] = {"present": name in present, "state": state,
                         "actuallyExecuted": counts["nonSkipped"] > 0, **dict(counts)}
    missing = [name for name in expected if name not in present]
    totals = {key: sum(row[key] for row in metrics.values()) for key in COUNTS}
    if missing:
        problems.append({"kind": "missing_selected_classes", "classes": missing})
    if empty:
        problems.append({"kind": "selected_classes_without_testcases", "classes": empty})
    if unexpected:
        problems.append({"kind": "unexpected_testcase_classes", "classes": sorted(unexpected)})
    if not totals["nonSkipped"]:
        problems.append({"kind": "no_non_skipped_selected_testcases"})
    failed = bool(problems or totals["errors"] or totals["failures"])
    return {"status": "failed" if failed else "complete_with_skips" if totals["skipped"] else "complete",
            "expectedClasses": expected, "classes": classes, "totals": totals,
            "classesPresent": len(present),
            "classesActuallyExecuted": sum(row["actuallyExecuted"] for row in classes.values()),
            "allSkippedClasses": all_skipped, "missingClasses": missing,
            "problems": problems, "xmlFiles": files}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", required=True, choices=("main", "native-unavailable"))
    parser.add_argument("--results-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--selectors-file", type=Path,
                        default=Path(__file__).resolve().with_name("mika-focused-tests.txt"),
                        help="Original selector list from the exact source checkout/artifact")
    args = parser.parse_args(argv)
    report = {"format": "pocketworld.mika-test-evidence.v1", "phase": args.phase,
              "resultsDirectory": str(args.results_dir),
              "provenance": "Caller must bind this isolated phase/artifact to its workflow and source."}
    try:
        expected = selected_classes(args.selectors_file) if args.phase == "main" else [OFFLINE_CLASS]
        report.update(audit(args.results_dir, expected))
        if args.phase == "main":
            report.update(selectorsFile=str(args.selectors_file),
                          selectorsSha256=hashlib.sha256(args.selectors_file.read_bytes()).hexdigest())
    except (OSError, ValueError, UnicodeError) as failure:
        report.update(status="failed", problems=[{"kind": "selector_read_failed", "error": str(failure)}])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"phase": args.phase, "status": report["status"], "output": str(args.output),
                      "totals": report.get("totals"), "missingClasses": report.get("missingClasses"),
                      "allSkippedClasses": report.get("allSkippedClasses")}, sort_keys=True))
    return int(report["status"] == "failed")


if __name__ == "__main__":
    sys.exit(main())
