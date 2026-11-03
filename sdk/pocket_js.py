#!/usr/bin/env python3
"""Static executable_js SDK. Does not initialize, describe, evaluate or execute source."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
MAIN = "dev.pocketengine.sdk.JsPluginSdkMain"


def sources():
    return [ROOT / "app/src/main/java/dev/pocketengine/core/RunControl.java",
            *sorted((ROOT / "app/src/main/java/dev/pocketengine/features/plugins/sdk/js").glob("*.java")),
            ROOT / "sdk/java/dev/pocketengine/sdk/JsPluginSdkMain.java"]


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate project metadata field")
        result[key] = value
    return result


def metadata(project):
    path = project / "project.json"
    with path.open("rb", buffering=0) as stream:
        raw = stream.read(4097)
    if len(raw) > 4096:
        raise ValueError("Project metadata exceeds4KiB")
    value = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=unique_object)
    if not isinstance(value, dict) or set(value) != {"id", "name", "version"} or any(not isinstance(x, str) for x in value.values()):
        raise ValueError("project.json must contain only id,name,version strings")
    return [value["id"], value["name"], value["version"]]


def invoke(java, classpath, arguments):
    result = subprocess.run([java, "-XX:-UsePerfData", "-cp", classpath, MAIN, *arguments], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(result.stderr.strip() or "Static Java package validation failed")
    return json.loads(result.stdout)


def bridge(arguments, options):
    java = options.java or shutil.which("java")
    if not java:
        raise ValueError("JDK17+ required for developer CLI; phone uses the Java production API")
    if options.classpath:
        return invoke(java, options.classpath, arguments)
    dep_root = options.deps or os.environ.get("POCKET_PLUGIN_JAVA_DEPS")
    if not dep_root:
        raise ValueError("Specify --deps containing one existing json-*.jar, or precompiled --classpath; no download occurs")
    jars = sorted(Path(dep_root).glob("json-*.jar"))
    if len(jars) != 1:
        raise ValueError("Need exactly one json-*.jar")
    with tempfile.TemporaryDirectory(prefix="pocket-js-sdk-") as classes:
        subprocess.run([java, "-XX:-UsePerfData", "--module", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                        "-encoding", "UTF-8", "-cp", str(jars[0]), "-d", classes, *map(str, sources())], check=True)
        return invoke(java, classes + os.pathsep + str(jars[0].resolve()), arguments)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java"); parser.add_argument("--deps"); parser.add_argument("--classpath")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init"); init.add_argument("directory"); init.add_argument("--id", required=True)
    init.add_argument("--name", required=True); init.add_argument("--version", default="1.0")
    for name in ("validate", "pack"):
        command = commands.add_parser(name); command.add_argument("project")
        if name == "pack": command.add_argument("--output", required=True)
    inspect = commands.add_parser("inspect"); inspect.add_argument("package")
    options = parser.parse_args(argv)
    if options.command == "init":
        # This copies independently authored inert text. Validation invokes only Java's static package codec.
        target = Path(options.directory); target.mkdir(parents=True, exist_ok=False)
        try:
            (target / "project.json").write_text(json.dumps(dict(id=options.id, name=options.name, version=options.version), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            shutil.copyfile(ROOT / "sdk/examples/executable-js/extension.js", target / "extension.js")
            receipt = bridge(["validate", str(target / "extension.js"), *metadata(target)], options)
        except Exception as failure:
            # A sibling process can replace this path after mkdir; even a stat before
            # rmtree cannot establish ownership across the subsequent deletion.
            raise ValueError("Initialization failed; no output was removed. Output path may contain "
                             f"partial or replaced data: {target.absolute()}. "
                             f"Inspect it before cleanup or retry. Cause: {failure}") from failure
        receipt.update(created=str(target.resolve()))
        return receipt
    if options.command == "inspect":
        return bridge(["inspect", str(Path(options.package).resolve())], options)
    project = Path(options.project).resolve()
    arguments = [options.command, str(project / "extension.js"), *metadata(project)]
    if options.command == "pack": arguments += [str(Path(options.output).resolve())]
    return bridge(arguments, options)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False, indent=2))
    except (ValueError, OSError, subprocess.CalledProcessError) as failure:
        print(str(failure), file=sys.stderr); sys.exit(2)
