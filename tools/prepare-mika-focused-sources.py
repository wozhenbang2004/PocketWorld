#!/usr/bin/env python3
"""Prepare Java compilation sources for the explicit Mika focused-test opt-in.

This does not change tests, source sets, Kotlin compilation, or default builds.
Source declarations, rather than directory names, identify the selected classes.
Only test-source dependencies are resolved here; production/library symbols keep
their normal compiler classpath. This is a bounded lexical dependency resolver,
not a Java/Kotlin compiler or a selector that can discard requested test classes.
"""
from __future__ import annotations

import argparse
from collections import defaultdict, deque
import json
from pathlib import Path
import re
import sys
import tempfile

SOURCE_ROOTS = (
    "app/src/test/java",
    "app/src/test/kotlin",
    "app/src/pendingAdb/test",
    "display-helper/test",
)
OFFLINE_CLASS = "dev.pocketengine.features.local.runtime.NativeOfflineUnavailableTest"
IDENTIFIER = r"[A-Za-z_$][A-Za-z0-9_$]*"
QUALIFIED = rf"{IDENTIFIER}(?:\.{IDENTIFIER})*"


class SourceError(ValueError):
    pass


def code_only(text: str) -> str:
    """Blank comments, quoted strings, text blocks, and chars; retain line shape."""
    chars = list(text)
    index = 0
    while index < len(text):
        end = index
        if text.startswith("//", index):
            end = text.find("\n", index)
            if end < 0:
                end = len(text)
        elif text.startswith("/*", index):
            end, nesting = index + 2, 1
            while end < len(text) and nesting:
                if text.startswith("/*", end):
                    nesting += 1
                    end += 2
                elif text.startswith("*/", end):
                    nesting -= 1
                    end += 2
                else:
                    end += 1
        elif text.startswith('"""', index):
            found = text.find('"""', index + 3)
            end = len(text) if found < 0 else found + 3
        elif text[index] in "\"'":
            quote, end = text[index], index + 1
            while end < len(text):
                if text[end] == "\\":
                    end += 2
                elif text[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
        if end > index:
            for at in range(index, min(end, len(chars))):
                if chars[at] != "\n":
                    chars[at] = " "
            index = end
        else:
            index += 1
    return "".join(chars)


def parse_source(repo: Path, path: Path) -> dict:
    code = code_only(path.read_text(encoding="utf-8"))
    package_match = re.search(rf"\bpackage\s+({QUALIFIED})", code)
    package = package_match.group(1) if package_match else ""
    depths, depth = [], 0
    for char in code:
        depths.append(depth)
        depth += (char == "{") - (char == "}")
    types, exports, local_types = set(), set(), set()
    declaration = re.compile(
        rf"\b(?:enum\s+class|annotation\s+class|data\s+class|sealed\s+class|"
        rf"fun\s+interface|class|interface|enum|record|object|typealias)\s+({IDENTIFIER})"
    )
    for match in declaration.finditer(code):
        name = match.group(1)
        local_types.add(name)
        if depths[match.start()] != 0:
            continue
        types.add(name)
        prefix = code[max(code.rfind("\n", 0, match.start()),
                          code.rfind(";", 0, match.start())) + 1:match.start()]
        if not (path.suffix == ".kt" and re.search(r"\bprivate\b", prefix)):
            exports.add(name)
    # Kotlin helpers can be imported by a top-level function or property name.
    if path.suffix == ".kt":
        declarations = re.compile(
            rf"\b(?:fun\s+(?:<[^>\n]+>\s*)?(?:{IDENTIFIER}\.)?|val\s+|var\s+)({IDENTIFIER})"
        )
        for match in declarations.finditer(code):
            prefix = code[code.rfind("\n", 0, match.start()) + 1:match.start()]
            if depths[match.start()] == 0 and not re.search(r"\bprivate\b", prefix):
                exports.add(match.group(1))
    imports, import_spans = [], []
    for match in re.finditer(
        rf"\bimport\s+(?:(static)\s+)?({QUALIFIED})(\.\*)?"
        rf"(?:\s+as\s+({IDENTIFIER}))?[ \t]*;?", code
    ):
        imports.append({"name": match.group(2), "wildcard": bool(match.group(3)),
                        "static": bool(match.group(1)), "alias": match.group(4)})
        import_spans.append(match.span())
    # Import declarations do not count as actual wildcard/same-package usage.
    # Blank declaration spans, not whole lines: Java permits inline imports and
    # a class body on the same physical line as its package declaration.
    body_chars = list(code)
    for start, end in import_spans + ([package_match.span()] if package_match else []):
        for at in range(start, end):
            if body_chars[at] != "\n":
                body_chars[at] = " "
    body = "".join(body_chars)
    return {
        "path": path.relative_to(repo).as_posix(), "language": path.suffix[1:],
        "package": package, "types": sorted(types), "exports": sorted(exports),
        "localTypes": local_types, "imports": imports, "code": body,
        "identifiers": set(re.findall(IDENTIFIER, body)), "stem": path.stem,
    }


def read_classes(path: Path) -> list[str]:
    classes = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        name = raw.split("#", 1)[0].strip()
        if not name:
            continue
        if not re.fullmatch(rf"{IDENTIFIER}(?:\.{IDENTIFIER})+", name):
            raise SourceError(f"{path}:{number}: expected an exact class name: {name}")
        if name in classes:
            raise SourceError(f"{path}:{number}: duplicate selected class: {name}")
        classes.append(name)
    if not classes:
        raise SourceError(f"No selected classes in {path}")
    return classes


def prepare(repo: Path) -> dict:
    repo = repo.resolve()
    requested = read_classes(repo / "tools/mika-focused-tests.txt")
    selected = requested + ([] if OFFLINE_CLASS in requested else [OFFLINE_CLASS])
    sources = {}
    declared, symbols = defaultdict(set), defaultdict(set)
    roots = []
    for relative in SOURCE_ROOTS:
        directory = repo / relative
        if not directory.is_dir():
            continue
        roots.append(relative)
        for path in sorted(directory.rglob("*")):
            if path.suffix not in {".java", ".kt"} or not path.is_file():
                continue
            source = parse_source(repo, path)
            sources[source["path"]] = source
            prefix = source["package"] + "." if source["package"] else ""
            for name in source["types"]:
                declared[prefix + name].add(source["path"])
            for name in source["exports"] + [source["stem"]]:
                symbols[prefix + name].add(source["path"])
            if source["language"] == "kt":
                symbols[prefix + source["stem"] + "Kt"].add(source["path"])

    def unique(index: dict, name: str) -> str | None:
        matches = index.get(name, set())
        if len(matches) > 1:
            raise SourceError(f"Ambiguous test-source symbol {name}: {sorted(matches)}")
        return next(iter(matches), None)

    def owner(name: str) -> str | None:
        while name:
            resolved = unique(symbols, name)
            if resolved:
                return resolved
            name = name.rpartition(".")[0]
        return None

    selected_sources = {}
    for name in selected:
        path = unique(declared, name)
        if not path:
            same_name = [s["path"] for s in sources.values()
                         if name.rpartition(".")[2] in s["types"]]
            raise SourceError(f"Selected class has no declared source: {name}; "
                              f"same simple-name candidates: {same_name}")
        selected_sources[name] = path

    closure, queue = set(selected_sources.values()), deque(sorted(set(selected_sources.values())))
    edges = defaultdict(set)
    while queue:
        source = sources[queue.popleft()]
        references = {}

        def reference(path: str | None, reason: str, symbol: str) -> None:
            if path and path != source["path"]:
                references.setdefault(path, set()).add((reason, symbol))

        for imported in source["imports"]:
            name = imported["name"]
            if not imported["wildcard"]:
                reference(owner(name), "explicit_import", name)
            else:
                # Static/nested wildcard imports require their containing type.
                reference(owner(name), "wildcard_owner", name)
                for identifier in source["identifiers"] - source["localTypes"]:
                    reference(unique(symbols, name + "." + identifier),
                              "used_wildcard_symbol", name + "." + identifier)
        prefix = source["package"] + "." if source["package"] else ""
        for identifier in source["identifiers"] - source["localTypes"]:
            reference(unique(symbols, prefix + identifier), "same_package_symbol",
                      prefix + identifier)
        for qualified in re.findall(rf"\b{IDENTIFIER}(?:\.{IDENTIFIER})+\b", source["code"]):
            reference(owner(qualified), "qualified_reference", qualified)
        for path, reasons in references.items():
            edges[(source["path"], path)].update(reasons)
            if path not in closure:
                closure.add(path)
                queue.append(path)
    dependencies = [
        {"from": start, "to": target,
         "reasons": [{"kind": kind, "symbol": symbol} for kind, symbol in sorted(reasons)]}
        for (start, target), reasons in sorted(edges.items())
    ]
    return {
        "format": "pocketworld.mika-focused-test-sources.v1",
        "classList": "tools/mika-focused-tests.txt", "sourceRoots": roots,
        "requestedClasses": requested, "independentClasses": [OFFLINE_CLASS],
        "selectedClasses": selected, "selectedClassSources": selected_sources,
        "javaSources": sorted(path for path in closure if path.endswith(".java")),
        "kotlinSources": sorted(path for path in closure if path.endswith(".kt")),
        "dependencySources": sorted(closure - set(selected_sources.values())),
        "dependencies": dependencies,
        "counts": {"requestedClasses": len(requested), "selectedClasses": len(selected),
                   "selectedSourceFiles": len(set(selected_sources.values())),
                   "javaSources": sum(path.endswith(".java") for path in closure),
                   "kotlinSources": sum(path.endswith(".kt") for path in closure),
                   "dependencySourceFiles": len(closure - set(selected_sources.values()))},
        "scope": {"explicitOptInOnly": True, "defaultSourceSetsChanged": False,
                  "unitKotlinSelectionChanged": False, "testSourcesModified": False},
        "resolution": [
            "Package/type declarations determine selected-class identity; directories may differ.",
            "Comments and quoted content are blanked before lexical reference matching.",
            "Imports, used wildcard names, same-package exports/file names and qualified references recurse.",
            "Nested/local types are not mistaken for same-package test helpers.",
            "Non-test symbols retain the normal production/library classpath.",
            "This lexical graph does not resolve reflection or dynamic class-name strings.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, help="Default: <repo>/app/build/mika-focused-tests/sources.json")
    args = parser.parse_args(argv)
    try:
        manifest = prepare(args.repo)
        output = args.output or args.repo / "app/build/mika-focused-tests/sources.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".sources-", suffix=".json", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(manifest, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        try:
            temporary.replace(output)
        finally:
            temporary.unlink(missing_ok=True)
        print(json.dumps({"manifest": str(output), **manifest["counts"]}, sort_keys=True))
        return 0
    except (OSError, SourceError, UnicodeError) as failure:
        print(f"Focused test sources were not prepared: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
