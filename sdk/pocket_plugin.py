#!/usr/bin/env python3
"""Pocket World declaration SDK. Java production code is the validation authority."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path("app/src/main/java")
MAIN = "dev.pocketengine.sdk.PluginSdkMain"


def java_sources(root, w07_root):
    base = ["dev/pocketengine/core/RunControl.java", "dev/pocketengine/features/EngineModule.java"]
    base += ["dev/pocketengine/features/plugins/" + name + ".java" for name in
             ("PluginRules", "PluginArguments", "McpData", "McpHeaders", "HttpToolClient")]
    paths = [root / SOURCE / path for path in base]
    paths += [w07_root / SOURCE / "dev/pocketengine/features/plugins/PluginRevisions.java"]
    paths += [w07_root / SOURCE / "dev/pocketengine/features/plugins/distribution" / (name + ".java")
              for name in ("PackageJson", "PluginPackages")]
    paths += [root / SOURCE / "dev/pocketengine/features/plugins/distribution/SdkPackageCodec.java"]
    paths += sorted((root / SOURCE / "dev/pocketengine/features/plugins/sdk").rglob("*.java"))
    paths += sorted((root / "sdk/java").rglob("*.java"))
    for path in paths:
        if not path.is_file():
            raise ValueError(f"Missing production dependency: {path}. Integrate W07 first or specify --w07-source-root.")
    return paths


def bridge(request, options):
    java = options.java or shutil.which("java")
    if not java:
        raise ValueError("A JDK 17+ java executable is required; no dependency download is performed.")
    if options.classpath:
        return invoke(java, options.classpath, request)
    dep_root = options.deps or os.environ.get("POCKET_PLUGIN_JAVA_DEPS")
    if not dep_root:
        raise ValueError("Specify --deps containing json, okhttp, okio and kotlin jars, or --classpath with compiled SDK classes.")
    jars = []
    for pattern in ("json-*.jar", "okhttp-*.jar", "okio-jvm-*.jar", "kotlin-stdlib-*.jar"):
        matches = sorted(Path(dep_root).glob(pattern))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one {pattern} in --deps; found {len(matches)}.")
        jars.append(str(matches[0].resolve()))
    deps = os.pathsep.join(jars)
    sources = java_sources(ROOT, Path(options.w07_source_root).resolve() if options.w07_source_root else ROOT)
    with tempfile.TemporaryDirectory(prefix="pocket-plugin-sdk-") as output:
        compile_command = [java, "-XX:-UsePerfData", "--module", "jdk.compiler/com.sun.tools.javac.Main",
                           "--release", "17", "-encoding", "UTF-8", "-cp", deps, "-d", output]
        compile_command += [str(path) for path in sources]
        subprocess.run(compile_command, check=True)
        return invoke(java, output + os.pathsep + deps, request)


def invoke(java, classpath, request):
    payload = json.dumps(request, ensure_ascii=False, allow_nan=False).encode("utf-8")
    process = subprocess.Popen([java, "-XX:-UsePerfData", "-cp", classpath, MAIN], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        out, err = process.communicate(payload)
    except KeyboardInterrupt:
        process.terminate()
        process.communicate(timeout=10)
        raise ValueError("Debug interrupted: any dispatched HTTP effect is unknown. Query the original operationId; do not replay automatically.")
    if process.returncode:
        raise ValueError(err.decode("utf-8", errors="replace").strip() or "Java validation failed")
    return json.loads(out.decode("utf-8"))


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--deps", help="Existing dependency jar directory; never downloaded")
    result.add_argument("--java", help="JDK java executable")
    result.add_argument("--classpath", help="Precompiled production SDK plus dependencies")
    result.add_argument("--w07-source-root", help="Read-only W07 dependency checkout (only for an unintegrated checkout)")
    commands = result.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Create a new authoring project; does not start a server")
    init.add_argument("directory"); init.add_argument("--id", required=True)
    init.add_argument("--name", required=True); init.add_argument("--version", default="1.0")
    init.add_argument("--kind", choices=("echo", "http"), default="echo")
    init.add_argument("--endpoint", help="Explicit HTTP endpoint, e.g. http://127.0.0.1:8765/tools")
    init.add_argument("--source-label", default="Local developer project")
    for name in ("validate", "pack", "diagnose", "review-debug", "debug"):
        command = commands.add_parser(name)
        command.add_argument("project", help="Directory with manifest.json and source.json")
        if name == "pack":
            command.add_argument("--output", required=True, help="New output file; overwrite rejected")
            command.add_argument("--package-url", help="Optional W12 catalog entry URL; does not upload")
            command.add_argument("--min-android-api", type=int, default=30)
        if name in ("diagnose", "review-debug", "debug"):
            command.add_argument("--tool", required=True); command.add_argument("--arguments", required=True, help="Strict JSON object file")
        if name in ("review-debug", "debug"):
            command.add_argument("--operation-id", required=True)
        if name == "debug":
            command.add_argument("--approve", required=True, help="Exact digest from review-debug; may perform real local side effects")
    inspect = commands.add_parser("inspect"); inspect.add_argument("package")
    return result


def main(argv=None):
    options = parser().parse_args(argv)
    command = options.command
    if command == "init":
        if options.kind == "http" and not options.endpoint:
            raise ValueError("HTTP templates require an explicit --endpoint.")
        request = dict(action="template", kind=options.kind, id=options.id, name=options.name, version=options.version)
        if options.endpoint: request["endpoint"] = options.endpoint
        manifest = bridge(request, options)
        target = Path(options.directory)
        target.mkdir(parents=True, exist_ok=False)
        try:
            (target / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            (target / "source.json").write_text(json.dumps({"label": options.source_label}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            (target / "arguments.json").write_text('{"text":"  real   local text  "}\n', encoding="utf-8")
            if options.kind == "http":
                shutil.copyfile(ROOT / "sdk/examples/text_tools.py", target / "tools.py")
        except Exception as failure:
            # A sibling process can replace this path after mkdir; even a stat before
            # rmtree cannot establish ownership across the subsequent deletion.
            raise ValueError("Initialization failed; no output was removed. Output path may contain "
                             f"partial or replaced data: {target.absolute()}. "
                             f"Inspect it before cleanup or retry. Cause: {failure}") from failure
        return {"created": str(target.resolve()), "serverStarted": False, "installed": False}
    if command == "inspect":
        request = dict(action="inspect", packagePath=str(Path(options.package).resolve()))
    else:
        project = Path(options.project).resolve()
        request = dict(action=command, manifestPath=str(project / "manifest.json"), sourcePath=str(project / "source.json"))
        if command == "pack":
            request["outputPath"] = str(Path(options.output).resolve())
            if options.package_url:
                if not 1 <= options.min_android_api <= 2147483647:
                    raise ValueError("minAndroidApi must be an integer in 1..2147483647")
                request.update(packageUrl=options.package_url, minAndroidApi=options.min_android_api)
        if command in ("diagnose", "review-debug", "debug"):
            request.update(tool=options.tool, argumentsPath=str(Path(options.arguments).resolve()))
        if command in ("review-debug", "debug"): request["operationId"] = options.operation_id
        if command == "debug": request["acceptedDigest"] = options.approve
    return bridge(request, options)


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False, allow_nan=False, indent=2))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
