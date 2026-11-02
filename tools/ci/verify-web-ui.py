#!/usr/bin/env python3
"""Verify the exact packaged application and Android WebView capture evidence.

Uses only the Python standard library. This is an Android app capture pipeline;
it does not render a replacement browser page, crop/repaint UI, or seed tasks.
"""

import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import zlib


APP_ID = "dev.pocketengine"
ENTRY_ACTIVITY = "dev.pocketengine/.ui.ComposeActivity"
SOURCE_URL = "https://pocketengine.local/assets/pocket-ui/index.html"
MIN_WEBVIEW_MAJOR = 111


def sha256(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path, value):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def current_commit():
    commit = os.environ.get("GITHUB_SHA") or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True
    ).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A complete source commit SHA is required")
    return commit


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def github_json(url, token):
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "PocketWorld-CI"
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def acquire_apk(args):
    """Consume one successful original APK build for this exact commit only."""
    if not re.fullmatch(r"[1-9][0-9]*", args.run_id):
        raise ValueError("apk_run_id must be a positive workflow run ID")
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("GITHUB_REPOSITORY is required")
    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        raise ValueError("The read-only Actions token is required")
    expected = current_commit()
    base = "https://api.github.com/repos/" + repo
    run = github_json(base + "/actions/runs/" + args.run_id, token)
    assert run["head_sha"] == expected, "Source APK run is for a different commit"
    assert run["head_repository"]["full_name"] == repo, "Source APK is from a different repository"
    assert run["path"].split("@", 1)[0] == ".github/workflows/build-apk.yml", "Source run is not the original APK workflow"
    same_run = args.run_id == os.environ.get("GITHUB_RUN_ID")
    if not same_run:
        assert run["status"] == "completed" and run["conclusion"] == "success", "Source APK run is not successful"
    # A reusable capture job runs after the original APK job in the same run.
    # Waiting for that whole run to finish here would deadlock on this job.
    jobs = github_json(base + "/actions/runs/" + args.run_id + "/jobs?per_page=100&filter=all", token)
    assert jobs["total_count"] <= 100, "Ambiguous source job collection"
    apk_jobs = [job for job in jobs["jobs"] if job["name"] == "apk"]
    assert apk_jobs, "Original apk job is missing"
    latest_apk_attempt = max(job["run_attempt"] for job in apk_jobs)
    apk_jobs = [job for job in apk_jobs if job["run_attempt"] == latest_apk_attempt]
    assert len(apk_jobs) == 1 and apk_jobs[0]["status"] == "completed" and apk_jobs[0]["conclusion"] == "success", "Original apk job has not succeeded"
    response = github_json(base + "/actions/runs/" + args.run_id + "/artifacts?per_page=100", token)
    assert response["total_count"] <= 100, "Ambiguous artifact collection; do not guess another page"
    expected_artifact_name = "pocketworld-debug-arm64-" + str(run["run_number"]) + "-" + str(latest_apk_attempt)
    candidates = [item for item in response["artifacts"] if
                  item["name"] == expected_artifact_name and not item["expired"]]
    assert len(candidates) == 1, "Exactly one original APK artifact must be available"
    artifact = candidates[0]
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(base + "/actions/artifacts/" + str(artifact["id"]) + "/zip", headers={
        "Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "PocketWorld-CI"
    })
    # GitHub's signed storage URL receives no repository token.
    try:
        urllib.request.build_opener(NoRedirect).open(request, timeout=60)
        raise AssertionError("Expected an authenticated artifact redirect")
    except urllib.error.HTTPError as error:
        if error.code not in (301, 302, 303, 307, 308):
            raise
        signed_url = error.headers["Location"]
    assert urllib.parse.urlparse(signed_url).scheme == "https", "Artifact redirect must remain HTTPS"
    archive = out / "source-apk-artifact.zip"
    with urllib.request.urlopen(signed_url, timeout=120) as response, archive.open("wb") as handle:
        shutil.copyfileobj(response, handle)
    advertised_digest = artifact.get("digest")
    if advertised_digest:
        assert advertised_digest == "sha256:" + sha256(archive), "Source artifact digest mismatch"
    with zipfile.ZipFile(archive) as bundle:
        assert bundle.testzip() is None, "Source APK artifact CRC mismatch"
        selected = {}
        for basename in ("app-debug.apk", "apk-identity.json"):
            matches = [name for name in bundle.namelist() if pathlib.PurePosixPath(name).name == basename]
            assert len(matches) == 1, "Missing or ambiguous artifact member: " + basename
            info = bundle.getinfo(matches[0])
            assert info.file_size < 512 * 1024 * 1024, "Unexpectedly large APK artifact member"
            selected[basename] = bundle.read(matches[0])
        identity = json.loads(selected["apk-identity.json"])
        assert identity["commit"] == expected, "Packaged identity commit mismatch"
        assert hashlib.sha256(selected["app-debug.apk"]).hexdigest() == identity["sha256"], "Packaged identity hash mismatch"
        assert identity["application_id"] == APP_ID and identity["abi"] == "arm64-v8a", "Unexpected source package"
        (out / "app-debug.apk").write_bytes(selected["app-debug.apk"])
        (out / "source-apk-identity.json").write_bytes(selected["apk-identity.json"])
    archive.unlink()
    write_json(out / "source-apk-run.json", {
        "run_id": int(args.run_id), "head_sha": expected, "conclusion": run["conclusion"],
        "same_run_reuse": same_run, "apk_job_id": apk_jobs[0]["id"], "apk_job_conclusion": apk_jobs[0]["conclusion"],
        "apk_job_attempt": latest_apk_attempt,
        "workflow": run["path"], "artifact_id": artifact["id"], "artifact_name": artifact["name"],
        "artifact_digest": advertised_digest,
    })
    print("Verified same-commit original APK artifact", artifact["id"])


def run_evidence(command, destination):
    with pathlib.Path(destination).open("w") as handle:
        subprocess.run(command, stdout=handle, stderr=subprocess.STDOUT, check=True)


def verify_apk(args):
    """Retains the original APK signature, alignment and native payload checks."""
    apk = pathlib.Path(args.apk)
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    assert apk.is_file() and apk.stat().st_size > 0, "APK is missing"
    sdk = pathlib.Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools/35.0.0"
    run_evidence([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], out / "signature.txt")
    run_evidence([str(tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(apk)], out / "alignment.txt")
    run_evidence([str(tools / "aapt"), "dump", "badging", str(apk)], out / "package.txt")
    catalog = json.loads(pathlib.Path("app/src/main/assets/runtime/catalog.json").read_text())
    with zipfile.ZipFile(apk) as bundle:
        assert bundle.testzip() is None, "APK ZIP integrity failed"
        names = set(bundle.namelist())
        for path in ("AndroidManifest.xml", "classes.dex", "assets/pocketengine-display-helper.jar",
                     "lib/arm64-v8a/libpocketprocess.so", "assets/pocket-ui/index.html"):
            assert path in names, "Missing APK entry: " + path
        for name, key in (("libproot.so", "prootSha256"), ("libloader.so", "loaderSha256")):
            assert hashlib.sha256(bundle.read("lib/arm64-v8a/" + name)).hexdigest() == catalog["engine"][key], "Runtime hash mismatch: " + name
        for name in names:
            if name.startswith("lib/") and name.endswith(".so"):
                assert name.startswith("lib/arm64-v8a/"), "Unexpected native ABI: " + name
                data = bundle.read(name)
                assert data[:4] == b"\x7fELF" and data[4] == 2 and int.from_bytes(data[18:20], "little") == 183, "Not AArch64 ELF: " + name
        with zipfile.ZipFile(io.BytesIO(bundle.read("assets/pocketengine-display-helper.jar"))) as helper:
            assert helper.testzip() is None and "classes.dex" in helper.namelist(), "Display helper DEX missing"
        web_files = sorted(name for name in names if name.startswith("assets/pocket-ui/"))
        assert any(name.endswith(".js") for name in web_files), "Packaged Web application JavaScript is missing"
        assert any(name.endswith(".css") for name in web_files), "Packaged Web application styles are missing"
        web_identity = [{"path": name, "bytes": len(bundle.read(name)),
                         "sha256": hashlib.sha256(bundle.read(name)).hexdigest()} for name in web_files]
    badging = (out / "package.txt").read_text()
    assert "package: name='dev.pocketengine'" in badging and "sdkVersion:'30'" in badging, "Unexpected application or minimum API"
    digest = sha256(apk)
    source_run = json.loads((out / "source-apk-run.json").read_text()) if (out / "source-apk-run.json").is_file() else None
    receipt = {
        "commit": current_commit(), "tree": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip(),
        "run_id": os.environ.get("GITHUB_RUN_ID"), "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "apk": "app-debug.apk", "bytes": apk.stat().st_size, "sha256": digest,
        "application_id": APP_ID, "abi": "arm64-v8a", "minimum_android_api": 30,
        "validation": ["original assembleDebug" if source_run else "assembleDebug", "APK signature", "ZIP alignment",
                       "APK CRC", "native ABI and catalog hashes", "display helper DEX", "packaged Web UI files"],
        "source_apk_run": source_run, "web_assets": web_identity,
        "device_install_or_runtime_test": "separate capture stage; see web-ui-captures/manifest.json",
        "signature": "ephemeral Android debug key; not stable across fresh runners",
    }
    write_json(out / "apk-identity.json", receipt)
    (out / "SHA256SUMS").write_text(digest + "  app-debug.apk\n")
    destination = out / "app-debug.apk"
    if destination.resolve() != apk.resolve():
        shutil.copyfile(apk, destination)
    print("Verified APK", digest)


def parse_bounds(value):
    match = re.fullmatch(r"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]", value or "")
    if not match:
        raise ValueError("Invalid accessibility bounds")
    left, top, right, bottom = map(int, match.groups())
    if right <= left or bottom <= top:
        raise ValueError("Accessibility node has no visible bounds")
    return left, top, right, bottom


def target(args):
    root = ET.parse(args.xml).getroot()
    parents = {child: parent for parent in root.iter() for child in parent}
    candidates = []
    for node in root.iter("node"):
        if args.label not in (node.attrib.get("text"), node.attrib.get("content-desc")):
            continue
        button = node
        while button is not None and button.attrib.get("clickable") != "true":
            button = parents.get(button)
        if button is None or button.attrib.get("enabled") != "true":
            continue
        try:
            bounds = parse_bounds(button.attrib.get("bounds"))
        except ValueError:
            continue
        if button.attrib.get("package") != APP_ID:
            continue
        candidates.append((button, bounds))
    unique = {bounds: node for node, bounds in candidates}
    assert len(unique) == 1, "Expected one accessible app control named " + args.label
    bounds, node = next(iter(unique.items()))
    point = [(bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2]
    write_json(args.out, {"label": args.label, "bounds": bounds, "point": point,
                          "method": "Android accessibility bounds", "class": node.attrib.get("class")})
    print(*point)


def webview_provider_identity(text):
    # This is the active provider field, not the preferred/available package list.
    # Format: AOSP WebViewUpdateServiceImpl.dumpState / Chromium WebView docs.
    matches = re.findall(
        r"^\s*Current WebView package \(name, version\):\s*\(([^,()\s]+),\s*([^()\s]+)\)\s*$",
        text, re.MULTILINE,
    )
    if len(matches) != 1:
        raise ValueError("Exactly one active WebView provider and version must be observed")
    package, version = matches[0]
    parsed = re.fullmatch(r"([0-9]+)(?:\.[0-9]+){1,3}", version)
    if not parsed:
        raise ValueError("Active WebView version cannot be interpreted: " + version)
    major = int(parsed.group(1))
    return {"package": package, "version": version, "major": major,
            "minimum_major": MIN_WEBVIEW_MAJOR, "compatible": major >= MIN_WEBVIEW_MAJOR,
            "basis": "active provider reported by adb shell dumpsys webviewupdate"}


def verify_provider(args):
    identity = webview_provider_identity(pathlib.Path(args.dump).read_text(errors="replace"))
    write_json(args.out, identity)
    assert identity["compatible"], "Observed WebView major " + str(identity["major"]) + " is below the packaged Web UI's required major " + str(MIN_WEBVIEW_MAJOR)
    print(identity["major"])


def png_dimensions(path):
    """Validate PNG chunks, scanlines and visible variation without image editing."""
    data = pathlib.Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "Invalid screenshot PNG: " + str(path)
    position, pixels, header, ended = 8, bytearray(), None, False
    while position < len(data):
        length = int.from_bytes(data[position:position + 4], "big")
        kind = data[position + 4:position + 8]
        content = data[position + 8:position + 8 + length]
        checksum = data[position + 8 + length:position + 12 + length]
        assert len(content) == length and len(checksum) == 4, "Truncated screenshot PNG"
        assert zlib.crc32(kind + content) & 0xffffffff == int.from_bytes(checksum, "big"), "Screenshot PNG CRC mismatch"
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", content)
        elif kind == b"IDAT":
            pixels.extend(content)
        elif kind == b"IEND":
            ended = True
        position += length + 12
    assert ended and header, "Incomplete screenshot PNG"
    width, height, depth, color_type, compression, filtering, interlace = header
    assert 320 <= width <= 4096 and 600 <= height <= 8192, "Unexpected screenshot dimensions"
    assert depth == 8 and color_type in (2, 6) and compression == filtering == interlace == 0, "Unsupported screenshot PNG encoding"
    channels = 4 if color_type == 6 else 3
    stride = width * channels
    raw = zlib.decompress(pixels)
    assert len(raw) == height * (stride + 1), "Invalid screenshot scanlines"
    previous = bytearray(stride)
    colors, visible = set(), 0
    for y in range(height):
        offset = y * (stride + 1)
        filter_type = raw[offset]
        assert filter_type in range(5), "Invalid PNG filter"
        line = bytearray(raw[offset + 1:offset + 1 + stride])
        for x in range(stride):
            left = line[x - channels] if x >= channels else 0
            up = previous[x]
            upper_left = previous[x - channels] if x >= channels else 0
            if filter_type == 1:
                line[x] = (line[x] + left) & 255
            elif filter_type == 2:
                line[x] = (line[x] + up) & 255
            elif filter_type == 3:
                line[x] = (line[x] + (left + up) // 2) & 255
            elif filter_type == 4:
                estimate = left + up - upper_left
                distances = (abs(estimate - left), abs(estimate - up), abs(estimate - upper_left))
                predictor = (left, up, upper_left)[distances.index(min(distances))]
                line[x] = (line[x] + predictor) & 255
        if y % 16 == 0:
            for x in range(0, width, 16):
                pixel = tuple(line[x * channels:(x + 1) * channels])
                colors.add(pixel)
                visible += channels == 3 or pixel[3] > 0
        previous = line
    assert visible > 100 and len(colors) > 12, "Screenshot is transparent/solid or contains too little UI detail"
    return width, height


def verify_capture(args):
    root = pathlib.Path(args.directory)
    identity = json.loads(pathlib.Path(args.apk_identity).read_text())
    assert identity["commit"] == current_commit(), "APK evidence does not match current commit"
    assert identity["sha256"] == sha256(args.apk), "Captured APK differs from the verified package"
    device = json.loads((root / "device.json").read_text())
    assert "arm64-v8a" in device["abi_list"].split(","), "Guest does not advertise ARM64 compatibility"
    assert device["api"] == 30, "Unexpected capture Android API"
    assert device["activity"] == ENTRY_ACTIVITY and device["source_url"] == SOURCE_URL, "Wrong UI entry"
    assert device["pid"] and device["ready"] is True, "Actual Web UI did not become ready"
    assert device["final_pid"] == device["pid"], "App process changed during the captured journey"
    provider = webview_provider_identity((root / "webview-provider.txt").read_text(errors="replace"))
    assert provider["compatible"], "Observed WebView is older than the packaged JavaScript/CSS target"
    assert device["webview"] == provider, "WebView identity does not match the observed provider"
    assert webview_provider_identity((root / "webview-provider-final.txt").read_text(errors="replace")) == provider, "Active WebView provider changed during capture"
    log = (root / "device-logcat.txt").read_text(errors="replace")
    assert re.search(r"PocketWebUi.*ready source=library-web", log), "Library Web UI ready signal is missing"
    assert not re.search(r"PocketWebUi.*(?:resource-error|console-error|render-process-gone|main-frame-error)", log, re.I), "Web UI resource/runtime error recorded"
    for match in re.finditer(r"FATAL EXCEPTION", log):
        assert APP_ID not in log[match.start():match.start() + 1200], "App crash recorded in logcat"
    files = []
    dimensions = set()
    for name in ("01-workroom.png", "02-all-work.png", "03-workroom-return.png"):
        path = root / name
        width, height = png_dimensions(path)
        dimensions.add((width, height))
        files.append({"file": name, "bytes": path.stat().st_size, "sha256": sha256(path), "width": width, "height": height})
    assert len(dimensions) == 1, "Viewport changed during the captured journey"
    for name in ("open-work-target.json", "close-work-target.json", "return-work-target.json"):
        target_data = json.loads((root / name).read_text())
        assert target_data["method"] == "Android accessibility bounds", "Unverified coordinate fallback"
    mp4 = root / "04-interaction.mp4"
    content = mp4.read_bytes()
    assert len(content) > 10000 and content[4:8] == b"ftyp", "Screen recording is missing or invalid"
    assert b"moov" in content and b"mdat" in content, "Screen recording did not finalize"
    files.append({"file": mp4.name, "bytes": len(content), "sha256": sha256(mp4)})
    write_json(root / "manifest.json", {
        "commit": current_commit(), "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "application_id": APP_ID,
        "source": "formal Android WebView application with transplanted library UI",
        "device": device, "apk": identity, "captures": files,
        "interaction_scope": "fresh app entry, actual work switcher open/close, return, short screen recording",
        "limitations": ["Android emulator capture, not a physical phone", "No model or phone-execution task initiated by the capture script",
                       "Does not prove every business journey, touch performance, accessibility or visual acceptance"],
        "status": "capture integrity and startup checks passed; inspect PNG/MP4 for design acceptance",
    })
    print("Verified three device PNGs and one app screen recording")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    acquire = commands.add_parser("acquire-apk")
    acquire.add_argument("--run-id", required=True)
    acquire.add_argument("--out", default="build-artifacts")
    acquire.set_defaults(function=acquire_apk)
    apk = commands.add_parser("verify-apk")
    apk.add_argument("--apk", required=True)
    apk.add_argument("--out", default="build-artifacts")
    apk.set_defaults(function=verify_apk)
    control = commands.add_parser("target")
    control.add_argument("--xml", required=True)
    control.add_argument("--label", required=True)
    control.add_argument("--out", required=True)
    control.set_defaults(function=target)
    provider = commands.add_parser("provider")
    provider.add_argument("--dump", required=True)
    provider.add_argument("--out", required=True)
    provider.set_defaults(function=verify_provider)
    capture = commands.add_parser("verify-capture")
    capture.add_argument("--directory", required=True)
    capture.add_argument("--apk", required=True)
    capture.add_argument("--apk-identity", required=True)
    capture.set_defaults(function=verify_capture)
    args = parser.parse_args()
    args.function(args)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ValueError, KeyError, OSError, ET.ParseError) as error:
        print("Web UI verification failed:", str(error), file=sys.stderr)
        sys.exit(1)
