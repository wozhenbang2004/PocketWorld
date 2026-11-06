#!/usr/bin/env python3
"""One local, checked PocketWorld APK release. Never invokes GitHub CI."""
from __future__ import annotations

import argparse
import base64
import contextlib
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
PART_BYTES = 16 * 1024 * 1024
CORE_CASES = ("account_model", "conversation", "native_files", "agent_execution", "owner_controls")
APP_PATHS = ("app/src/main", "app/src/pendingAdb/java", "app/build.gradle", "app/gradle.lockfile", "web-ui", "display-helper",
             "third-party", "gradle", "gradle.lockfile", "gradle.properties", "build.gradle", "settings.gradle",
             ":(glob)tools/build*", ":(glob)tools/*runtime*")


class ReleaseError(RuntimeError):
    pass


class ScriptUpdated(ReleaseError):
    pass


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def owned(path, root):
    resolved, parent = Path(path).resolve(), Path(root).resolve()
    if not resolved.is_relative_to(parent) or resolved == parent:
        raise ReleaseError("运行文件不在本次发布目录内。")
    return resolved


def no_system_drive(path):
    path = Path(path)
    if not path.is_absolute() or path.drive.lower() == "c:":
        raise ReleaseError("仓库、构建缓存和产物必须使用非 C 盘绝对路径。")
    return path.resolve()


def sanitize(text):
    text = re.sub(r"(?i)Bearer\s+\S+", "Bearer [redacted]", text)
    return re.sub(r"(https?://)[^/\s@]+@", r"\1[redacted]@", text)


def load_config(path):
    c = read_json(path)
    if c.get("schema") != 1 or any(key in c for key in ("token", "password", "access_token")):
        raise ReleaseError("配置格式不正确；上传凭据只能保存在受保护文件中。")
    for key in ("repo", "work_root", "artifact_root", "cache_root"):
        c[key] = str(no_system_drive(c[key]))
    origin = urllib.parse.urlsplit(c["origin"])
    if origin.scheme != "https" or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ("", "/"):
        raise ReleaseError("下载站必须是没有凭据或查询参数的 HTTPS origin。")
    c["origin"] = c["origin"].rstrip("/")
    if c.get("manifest_path") != "/releases/ed896/release.json" or c.get("package_name") != "dev.pocketengine":
        raise ReleaseError("配置不属于现有 PocketWorld 更新链。")
    for key in ("apk_signer_sha256", "public_pin_sha256"):
        if not re.fullmatch(r"[a-f0-9]{64}", c[key]):
            raise ReleaseError("签名 pin 格式不正确。")
    return c


class Runner:
    def __init__(self, config, directory=None):
        self.c = config
        self.repo = Path(config["repo"])
        self.directory = Path(directory) if directory else None
        self.env = os.environ.copy()
        cache = Path(config["cache_root"])
        temporary = (self.directory / "temp") if self.directory else Path(config["work_root"]) / "temp"
        for key in ("TEMP", "TMP", "TMPDIR"):
            self.env[key] = str(temporary)
        self.env.update(PYTHONDONTWRITEBYTECODE="1", GRADLE_USER_HOME=str(cache / "gradle-windows"),
                        npm_config_cache=str(cache / "npm-windows"), npm_config_prefix=str(cache / "npm-prefix"),
                        PLAYWRIGHT_BROWSERS_PATH=str(cache / "playwright"), PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD="1",
                        JAVA_HOME=config["java_home"], ANDROID_HOME=str(Path(config["build_tools"]).parent.parent))
        self.env.update(GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never", PYTHONUTF8="1")
        self.env["JAVA_TOOL_OPTIONS"] = '-Djava.io.tmpdir="' + str(temporary) + '"'
        self.env["PATH"] = str(Path(config["node"]).parent) + os.pathsep + str(Path(config["java_home"]) / "bin") + os.pathsep + str(Path(config["openssl"]).parent) + os.pathsep + self.env.get("PATH", "")
        if config.get("proxy"):
            self.env.update(HTTP_PROXY=config["proxy"], HTTPS_PROXY=config["proxy"], NO_PROXY="localhost,127.0.0.1")

    def command(self, command, cwd=None, capture=False, input_text=None, check=True):
        if self.directory:
            Path(self.env["TEMP"]).mkdir(parents=True, exist_ok=True)
        lines = []
        with subprocess.Popen(list(map(str, command)), cwd=cwd or self.repo, env=self.env,
                              stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                              encoding="utf-8", errors="replace") as process:
            if input_text is not None:
                process.stdin.write(input_text)
                process.stdin.close()
            for line in process.stdout:
                safe = sanitize(line.rstrip("\r\n"))
                if capture:
                    lines.append(safe)
                else:
                    print(safe, flush=True)
                if self.directory:
                    with (self.directory / "commands.log").open("a", encoding="utf-8") as log:
                        log.write(safe + "\n")
            code = process.wait()
        if check and code:
            if capture:
                for line in lines[-60:]:
                    print(line, file=sys.stderr)
            raise ReleaseError("命令失败（退出码 " + str(code) + "）：" + Path(str(command[0])).name)
        return (code, "\n".join(lines))

    def git(self, *args, check=True):
        return self.command([self.c.get("git", "git"), *args], capture=True, check=check)

    def tool(self, name, *args):
        return self.command([self.c["python"], "-B", self.repo / "tools/windows-release.py",
                             "--repo", self.repo, "--java-home", self.c["java_home"],
                             "--build-tools", self.c["build_tools"], "--temp-dir", self.env["TEMP"],
                             "--openssl", self.c["openssl"], name, *args])

    def build(self, version, targets):
        # All variable data travels as JSON stdin; it never becomes shell source.
        child = "$ErrorActionPreference='Stop';$p=[Console]::In.ReadToEnd()|ConvertFrom-Json;& $p.script -VersionCode ([int]$p.versionCode) -VersionName ([string]$p.versionName) -Targets ([string[]]$p.targets);exit $LASTEXITCODE"
        payload = json.dumps({"script": self.c["build_script"], "versionCode": version,
                              "versionName": self.c["version_prefix"] + str(version), "targets": targets})
        return self.command([self.c["powershell"], "-NoProfile", "-NonInteractive", "-Command", child],
                            capture=True, input_text=payload)[1]


def source_state(runner):
    _, head = runner.git("rev-parse", "HEAD")
    _, branch = runner.git("symbolic-ref", "--quiet", "--short", "HEAD", check=False)
    _, dirty = runner.git("status", "--porcelain=v1", "--untracked-files=normal")
    if not branch or dirty:
        raise ReleaseError("Git 当前有未提交/未跟踪改动，或处于 detached HEAD；已保留现场，未开始构建。")
    for name in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"):
        _, value = runner.git("rev-parse", "--git-path", name)
        path = Path(value)
        if (path if path.is_absolute() else runner.repo / path).exists():
            raise ReleaseError("Git 存在未完成的合并/变基操作；已保留现场。")
    if not re.fullmatch(r"[a-f0-9]{40}", head):
        raise ReleaseError("无法确定完整源码版本。")
    return head


def sync_source(runner):
    source_state(runner)
    runner.git("fetch", "--no-tags", "origin", "main")
    try:
        runner.git("merge", "--no-edit", "-m", "Merge origin/main for local release [skip ci]", "origin/main")
    except ReleaseError:
        raise ReleaseError("同步 main 失败或发生冲突；现场已保留，未 reset/stash/clean，未开始构建。") from None
    return source_state(runner)


def core_acceptance(runner, head):
    try:
        receipt = read_json(runner.c["core_acceptance_file"])["releaseAcceptance"]
        source = receipt["sourceCommit"]
        if receipt.get("status") != "passed" or not re.fullmatch(r"[a-f0-9]{40}", source):
            raise ValueError()
        if any(receipt.get("cases", {}).get(case, {}).get("status") != "passed" for case in CORE_CASES):
            raise ValueError()
        code, _ = runner.git("merge-base", "--is-ancestor", source, head, check=False)
        if code:
            raise ValueError()
        code, _ = runner.git("diff", "--quiet", source, head, "--", *APP_PATHS, check=False)
        if code:
            raise ValueError()
        return receipt
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        raise ReleaseError("核心批量验收尚未通过，或应用源码在验收后已变化；不构建、不上传。请先完成原核心验收记录。") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


class Http:
    def __init__(self, origin, token=None, proxy=None):
        self.origin, self.token = origin, token
        handlers = [NoRedirect()]
        if proxy:
            handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
        self.opener = urllib.request.build_opener(*handlers)

    def request(self, path, method="GET", data=None, auth=False, headers=None):
        if not path.startswith("/") or path.startswith("//"):
            raise ReleaseError("上传路径不属于既定下载站。")
        h = {"User-Agent": "PocketWorld-Release/1.0 (+https://github.com/wozhenbang2004/PocketWorld)",
             "Accept": "*/*"}
        h.update(headers or {})
        if auth:
            if not self.token:
                raise ReleaseError("缺少本机受保护上传凭据。")
            h["Authorization"] = "Bearer " + self.token
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data, separators=(",", ":")).encode()
            h["Content-Type"] = "application/json"
        request = urllib.request.Request(self.origin + path, data=data, method=method, headers=h)
        try:
            return self.opener.open(request, timeout=180)
        except urllib.error.HTTPError as error:
            raise ReleaseError("下载站请求失败：HTTP " + str(error.code) + " " + path.split("?")[0]) from None
        except (OSError, urllib.error.URLError):
            raise ReleaseError("下载站请求未确认完成；本地产物保留，下次将先对账，不自动重打包。") from None

    def json(self, path, method="GET", data=None):
        with self.request(path, method, data, auth=True) as response:
            raw = response.read(128 * 1024 + 1)
        if len(raw) > 128 * 1024:
            raise ReleaseError("上传服务响应超过限制。")
        return json.loads(raw)


def dpapi(data, protect=False):
    if os.name != "nt":
        raise ReleaseError("本机上传凭据需要 Windows 当前用户 DPAPI。")
    from ctypes import wintypes
    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]
    memory = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    incoming, outgoing = Blob(len(data), memory), Blob()
    function = ctypes.windll.crypt32.CryptProtectData if protect else ctypes.windll.crypt32.CryptUnprotectData
    function.restype = wintypes.BOOL
    ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    ctypes.windll.kernel32.LocalFree.restype = ctypes.c_void_p
    if not function(ctypes.byref(incoming), None, None, None, None, 1, ctypes.byref(outgoing)):
        raise ReleaseError("无法使用当前 Windows 用户保护/读取发布凭据。")
    try:
        return ctypes.string_at(outgoing.data, outgoing.size)
    finally:
        ctypes.windll.kernel32.LocalFree(ctypes.cast(outgoing.data, ctypes.c_void_p))


def read_token(c):
    value = dpapi(Path(c["publisher_token_file"]).read_bytes()).decode("ascii")
    if not re.fullmatch(r"[A-Za-z0-9_-]{40,100}", value):
        raise ReleaseError("上传凭据格式不正确。")
    return value


def package_info(runner, apk):
    executable = Path(runner.c["build_tools"]) / ("aapt.exe" if os.name == "nt" else "aapt")
    code, output = runner.command([executable, "dump", "badging", apk], capture=True, check=False)
    if code:
        raise ReleaseError("已有 APK 无法读取版本，不能安全决定新版本号：" + Path(apk).name)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)'", output)
    return {"packageName": match[1], "versionCode": int(match[2])} if match else None


def next_version(runner, online):
    highest = max(online, int(runner.c.get("minimum_version", 0)))
    paths = list(Path(runner.c["artifact_root"]).glob("*.apk"))
    paths += list((runner.repo / "app/build/outputs/apk/debug").glob("*.apk"))
    for apk in paths:
        item = package_info(runner, apk)
        if item and item["packageName"] == runner.c["package_name"]:
            highest = max(highest, item["versionCode"])
    if highest >= 2_100_000_000:
        raise ReleaseError("版本号已到安全上限。")
    return highest + 1


def download_exact(http, url, target, expected):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme + "://" + parsed.netloc != http.origin or parsed.query or parsed.fragment:
        raise ReleaseError("产物 URL 与既定下载站不一致。")
    h, count = hashlib.sha256(), 0
    with http.request(parsed.path) as response, Path(target).open("xb") as output:
        if response.status != 200:
            raise ReleaseError("完整产物下载没有返回200。")
        for block in iter(lambda: response.read(1024 * 1024), b""):
            count += len(block)
            if count > expected["bytes"]:
                raise ReleaseError("产物长度超过已签名值。")
            h.update(block)
            output.write(block)
    if count != expected["bytes"] or h.hexdigest() != expected["sha256"]:
        raise ReleaseError("产物完整回下载大小/SHA不匹配。")


def baseline(runner, http):
    path = runner.directory / "baseline-envelope.json"
    with http.request(runner.c["manifest_path"]) as response:
        raw = response.read(96 * 1024 + 1)
    if len(raw) > 96 * 1024:
        raise ReleaseError("线上清单超过上限。")
    path.write_bytes(raw)
    verified = runner.directory / "baseline-verified.json"
    runner.tool("verify-envelope", "--envelope", path, "--classpath", runner.c["org_json_classpath"],
                "--allow-expired-baseline", "--output", verified)
    item = read_json(verified)
    if item["pinSpkiSha256"] != runner.c["public_pin_sha256"]:
        raise ReleaseError("App 公钥 pin 与发布配置不一致。")
    expected = item["apk"]
    candidates = list(Path(runner.c["artifact_root"]).glob("*.apk"))
    for candidate in candidates:
        if candidate.stat().st_size == expected["bytes"] and digest(candidate) == expected["sha256"]:
            return item, candidate, hashlib.sha256(raw).hexdigest()
    candidate = runner.directory / ("baseline-" + str(item["versionCode"]) + ".apk")
    download_exact(http, expected["url"], candidate, expected)
    return item, candidate, hashlib.sha256(raw).hexdigest()


def check_host_xml(directory):
    files, count = list(Path(directory).glob("junit/TEST-*.xml")), 0
    for path in files:
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        declared = int(suite.get("tests", 0))
        if suite.tag != "testsuite" or declared != len(cases):
            raise ReleaseError("回归汇总数与实际测试用例不一致。")
        if any(case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None for case in cases):
            raise ReleaseError("回归实际用例包含失败、错误或跳过。")
        count += declared
        if any(int(suite.get(key, 0)) for key in ("failures", "errors", "skipped")):
            raise ReleaseError("本地回归有失败、错误或跳过，停止发布。")
    if not files or not count:
        raise ReleaseError("没有有效的本地回归测试回执。")
    return count


def make_bundle(runner, head, version, base_apk):
    c, run = runner.c, runner.directory
    runner.env["GITHUB_SHA"] = head
    web = runner.repo / "web-ui"
    lock = web / "package-lock.json"
    if not (web / "node_modules").is_dir() or not (Path(c["cache_root"]) / "release-web-lock.sha256").is_file() or (Path(c["cache_root"]) / "release-web-lock.sha256").read_text().strip() != digest(lock):
        runner.command([c["node"], c["npm_cli"], "ci"], cwd=web)
        (Path(c["cache_root"]) / "release-web-lock.sha256").write_text(digest(lock))
    _, web_tests = runner.command([c["node"], c["npm_cli"], "test"], cwd=web, capture=True)
    for line in web_tests.splitlines():
        print(line, flush=True)
    plain = re.sub(r"\x1b\[[0-9;]*m", "", web_tests)
    counts = {key: re.findall(r"(?m)^[#\u2139]\s*" + key + r"\s+(\d+)\s*$", plain)
              for key in ("tests", "fail", "cancelled", "skipped")}
    if not counts["tests"] or int(counts["tests"][-1]) < 1 or any(not counts[key] or int(counts[key][-1]) for key in ("fail", "cancelled", "skipped")):
        raise ReleaseError("Web 测试没有有效的零失败、零跳过结果。")
    runner.command([c["node"], c["npm_cli"], "run", "build"], cwd=web)
    identity = read_json(runner.repo / "app/src/main/assets/pocket-ui/build-identity.json")
    if identity.get("sourceRevision") != head:
        raise ReleaseError("Web 资产没有对应当前源码。")
    _, python_tests = runner.command([c["python"], "-B", "-m", "unittest", "-v", "tools/tests/test_app_version.py",
                    "tools/tests/test_apk_workflow_paths.py", "tools/tests/test_packaged_web_receipt.py",
                    "tools/tests/test_update_artifact_preflight.py", "tools/tests/test_update_release_bundle.py",
                    "tools/tests/test_update_manifest_signing.py"], capture=True)
    print(python_tests, flush=True)
    count = re.search(r"Ran (\d+) tests? in", python_tests)
    if not count or int(count[1]) < 1 or re.search(r"\bskipped\s*[= ]\s*[1-9]", python_tests):
        raise ReleaseError("Python 发布检查存在跳过或没有执行用例。")
    host = run / "host-tests"
    targets = [":app:testDebugUnitTest", ":app:assembleDebug", "-PpocketworldHostTests=" + c["host_test_groups"],
               "-PpocketworldHostReport=" + host.as_posix(), "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"]
    build_output = runner.build(version, targets)
    check_host_xml(host)
    evidence = re.findall(r"Build evidence:\s*(.+)", build_output)
    if not evidence or read_json(Path(evidence[-1].strip()) / "result.json").get("head") != head:
        raise ReleaseError("构建回执与冻结的源码不一致。")
    if source_state(runner) != head:
        raise ReleaseError("构建期间源码改变，停止发布。")
    built = runner.repo / "app/build/outputs/apk/debug/app-debug.apk"
    target = Path(c["artifact_root"]) / ("PocketWorld-v" + str(version) + "-" + head[:7] + ".apk")
    with built.open("rb") as source, target.open("xb") as destination:
        shutil.copyfileobj(source, destination)
    runner.tool("verify-apk", "--apk", target, "--out", run / "apk-check", "--expected-signer", c["apk_signer_sha256"])
    preliminary = run / "preflight-before-delta.json"
    runner.tool("preflight", "--old", base_apk, "--new", target, "--output", preliminary)
    inspected = read_json(preliminary)
    if inspected.get("sameSignerSet") is not True or inspected.get("strictlyNewerVersion") is not True or inspected["target"]["versionCode"] != version or inspected["target"]["signerCertificateSha256"] != [c["apk_signer_sha256"]]:
        raise ReleaseError("APK 版本或签名不匹配；未开始生成差分。")
    patch = run / ("from-baseline-to-" + str(version) + ".patch.gz")
    runner.tool("delta", "generate", "--old", base_apk, "--new", target, "--output", patch)
    bundle = run / "bundle"
    runner.tool("prepare", "--origin", c["origin"], "--old", base_apk, "--new", target, "--patch", patch,
                "--build-tools", c["build_tools"], "--java-home", c["java_home"], "--notes", c.get("notes", ""),
                "--valid-hours", "168", "--output-dir", bundle)
    proof = read_json(bundle / "artifact-evidence.json")
    expected = proof["target"]
    if expected["versionCode"] != version or expected["signerCertificateSha256"] != [c["apk_signer_sha256"]]:
        raise ReleaseError("构建包的版本或证书与本次发布不一致。")
    plan = read_json(bundle / "release-plan.json")
    if plan["manifestUrl"] != c["origin"] + c["manifest_path"]:
        raise ReleaseError("APK 更新地址与上传目标不一致。")
    if plan.get("inefficientDeltaOmitted"):
        meta = proof["delta"]
        relative = "artifacts/" + meta["sha256"] + "/from-" + str(proof["base"]["versionCode"]) + "-to-" + str(version) + ".patch.gz"
        destination = owned(bundle / relative, bundle)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with patch.open("rb") as source, destination.open("xb") as output:
            shutil.copyfileobj(source, output)
        plan["artifactUploads"].append({"file": relative, "url": c["origin"] + "/" + relative,
                                       "sha256": meta["sha256"], "bytes": meta["bytes"], "contentType": "application/gzip"})
        write_json(bundle / "release-plan.json", plan)
    runner.tool("sign", "--payload", bundle / "unsigned-payload.json", "--private-key", c["manifest_private_key"],
                "--public-key", c["manifest_public_key"], "--output", bundle / "release.json")
    runner.tool("verify-envelope", "--envelope", bundle / "release.json", "--classpath", c["org_json_classpath"],
                "--output", bundle / "signed-verified.json")
    return bundle


def artifact_descriptors(bundle):
    plan, result = read_json(Path(bundle) / "release-plan.json"), []
    for entry in plan["artifactUploads"]:
        path = owned(Path(bundle) / entry["file"], bundle)
        if path.stat().st_size != entry["bytes"] or digest(path) != entry["sha256"]:
            raise ReleaseError("已准备的上传文件改变。")
        parts = []
        with path.open("rb") as source:
            for data in iter(lambda: source.read(PART_BYTES), b""):
                parts.append({"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        result.append({"path": "/" + entry["file"], "bytes": entry["bytes"], "sha256": entry["sha256"],
                       "contentType": entry["contentType"], "parts": parts})
    return result


def upload_release(runner, http, bundle, baseline_sha, head):
    raw = (Path(bundle) / "release.json").read_bytes()
    manifest_sha = hashlib.sha256(raw).hexdigest()
    descriptors = artifact_descriptors(bundle)
    request = {"schema": 1, "manifestBase64": base64.b64encode(raw).decode("ascii"),
               "expectedCurrentManifestSha256": baseline_sha, "sourceCommit": head, "artifacts": descriptors}
    registered = http.json("/__publisher/releases", "POST", request)
    release_id = registered["releaseId"]
    if release_id != manifest_sha or registered["manifestSha256"] != manifest_sha:
        raise ReleaseError("站点登记的发布身份不一致。")
    prefix = "/__publisher/releases/" + release_id
    status = http.json(prefix)
    by_hash = {entry["sha256"]: entry for entry in status["artifacts"]}
    for descriptor in descriptors:
        sha = descriptor["sha256"]
        state = by_hash[sha]
        if not state["ready"]:
            local = owned(Path(bundle) / descriptor["path"].lstrip("/"), bundle)
            with local.open("rb") as source:
                for index in state["missingParts"]:
                    source.seek(index * PART_BYTES)
                    data = source.read(descriptor["parts"][index]["bytes"])
                    if hashlib.sha256(data).hexdigest() != descriptor["parts"][index]["sha256"]:
                        raise ReleaseError("上传块与已经验证的文件不一致。")
                    with http.request(prefix + "/blobs/" + sha + "/parts/" + str(index), "PUT", data, auth=True,
                                      headers={"Content-Type": "application/octet-stream"}) as response:
                        response.read(4096)
                    print("已上传 " + Path(descriptor["path"]).name + " 块 " + str(index + 1) + "/" + str(len(descriptor["parts"])), flush=True)
            http.json(prefix + "/blobs/" + sha + "/complete", "POST", {})
        public_check = runner.directory / ("public-" + sha + ".verified")
        if public_check.exists():
            public_check = runner.directory / ("public-" + sha + "-" + uuid.uuid4().hex[:6] + ".verified")
        download_exact(http, http.origin + descriptor["path"], public_check, descriptor)
        length = min(64, descriptor["bytes"])
        with http.request(descriptor["path"], headers={"Range": "bytes=0-" + str(length - 1)}) as response:
            if response.status != 206 or response.headers.get("Content-Range") != "bytes 0-" + str(length - 1) + "/" + str(descriptor["bytes"]):
                raise ReleaseError("新产物的范围下载校验失败。")
            with owned(Path(bundle) / descriptor["path"].lstrip("/"), bundle).open("rb") as original:
                if response.read(length + 1) != original.read(length):
                    raise ReleaseError("新产物范围字节不匹配。")
    # A lost activation reply is reconciled, never treated as permission to rebuild or downgrade.
    if source_state(runner) != head:
        raise ReleaseError("上传期间源码变化，保留已上传的不可变文件，未激活更新。")
    core_acceptance(runner, head)
    try:
        http.json(prefix + "/activate", "POST", {})
    except ReleaseError:
        status = http.json("/__publisher/status")
        if status.get("activeReleaseId") != release_id or status.get("current", {}).get("manifestSha256") != manifest_sha:
            raise
    with http.request(runner.c["manifest_path"]) as response:
        actual = response.read(96 * 1024 + 1)
    if actual != raw:
        raise ReleaseError("公开更新清单与本次已验签原字节不同。")
    return {"status": "published", "sourceCommit": head, "releaseId": release_id,
            "manifestSha256": manifest_sha, "manifestUrl": http.origin + runner.c["manifest_path"],
            "artifacts": [http.origin + row["path"] for row in descriptors]}


@contextlib.contextmanager
def release_lock(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    lock = (root / "release.lock").open("a+b")
    try:
        if os.name == "nt":
            import msvcrt
            if lock.tell() == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise ReleaseError("另一份本机发布脚本仍在运行。") from None
        yield
    finally:
        lock.close()


def run_release(c, prepare_only=False):
    with release_lock(c["work_root"]):
        root = Path(c["work_root"])
        directory = root / (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + uuid.uuid4().hex[:7])
        directory.mkdir()
        runner = Runner(c, directory)
        print("发布日志：" + str(directory), flush=True)
        needed = runtime_paths(c, publish=not prepare_only)
        if needed:
            raise ReleaseError("缺少已配置的本机工具或受保护文件：" + ", ".join(needed))
        Path(c["cache_root"]).mkdir(parents=True, exist_ok=True)
        Path(c["artifact_root"]).mkdir(parents=True, exist_ok=True)
        print("[1/7] 保留现有工作，自动同步 main", flush=True)
        original_script = digest(Path(__file__))
        head = sync_source(runner)
        if digest(Path(__file__)) != original_script:
            raise ScriptUpdated("发布脚本已随 main 更新，将在构建前自动重启一次。")
        if not prepare_only:
            core_acceptance(runner, head)
        http = Http(c["origin"], proxy=c.get("proxy"))
        if not prepare_only:
            http.token = read_token(c)
        previous = root / "last-run.json"
        print("[2/7] 验证已分发基线及版本号", flush=True)
        base, base_apk, base_sha = baseline(runner, http)
        if not prepare_only:
            status = http.json("/__publisher/status")
            if status["current"]["manifestSha256"] != base_sha or status["pinSpkiSha256"] != c["public_pin_sha256"] or status["partBytes"] != PART_BYTES or status.get("digestStreamAvailable") is not True:
                raise ReleaseError("上传服务配置、当前基线或签名链不一致；未开始构建。")
        pending = read_json(previous) if previous.is_file() else {}
        published_source = pending.get("sourceCommit", "")
        if pending.get("stage") == "published" and pending.get("manifestSha256") == base_sha and base.get("validNow") is True and re.fullmatch(r"[a-f0-9]{40}", published_source):
            code, _ = runner.git("diff", "--quiet", published_source, head, "--", *APP_PATHS, check=False)
            if code == 0:
                return {"status": "up_to_date", "sourceCommit": published_source, "currentCheckout": head,
                        "applicationInputsUnchanged": True, "noBuildOrPublication": True}
        bundle = None
        if pending.get("sourceCommit") == head and pending.get("stage") == "artifacts_ready":
            candidate = owned(Path(pending["bundle"]), root)
            if (candidate / "release.json").is_file():
                if digest(candidate / "release.json") == base_sha:
                    return {"status": "published", "sourceCommit": head, "manifestUrl": c["origin"] + c["manifest_path"], "reconciled": True}
                if pending.get("baselineManifestSha256") != base_sha:
                    raise ReleaseError("线上基线已被另一发布推进；保留本次候选包和差分，停止而不自动重建。")
                verification = directory / "resume-verified.json"
                runner.tool("verify-envelope", "--envelope", candidate / "release.json", "--classpath", c["org_json_classpath"], "--output", verification)
                if read_json(verification)["versionCode"] > base["versionCode"]:
                    bundle = candidate
                    print("复用上次已验证产物，继续上传，不重新构建。", flush=True)
        if bundle is None:
            version = next_version(runner, base["versionCode"])
            print("[3/7] 本地测试、更新 Web 资产、一次构建 APK v" + str(version), flush=True)
            bundle = make_bundle(runner, head, version, base_apk)
            print("[4/7] 差分已完整重建并核对，清单已本机签名。", flush=True)
            write_json(previous, {"stage": "artifacts_ready", "sourceCommit": head, "bundle": str(bundle), "baselineManifestSha256": base_sha})
        if prepare_only:
            return {"status": "prepared_local_only", "sourceCommit": head, "bundle": str(bundle)}
        if source_state(runner) != head:
            raise ReleaseError("上传前源码变化；已保存产物，没有发布。")
        core_acceptance(runner, head)
        http.token = read_token(c)
        status = http.json("/__publisher/status")
        if status["current"]["manifestSha256"] != base_sha or status["pinSpkiSha256"] != c["public_pin_sha256"]:
            raise ReleaseError("站点版本或签名链已改变；已保存产物，没有发布。")
        print("[5/7] 上传完整 APK 和差分（支持断点对账）", flush=True)
        result = upload_release(runner, http, bundle, base_sha, head)
        print("[6/7] 公开字节与范围校验通过，已最后激活签名清单。", flush=True)
        write_json(directory / "published.json", result)
        write_json(previous, result | {"stage": "published", "bundle": str(bundle)})
        print("[7/7] 完成", flush=True)
        return result


def runtime_paths(c, publish=True):
    names = ["python", "node", "npm_cli", "powershell", "build_script", "openssl",
             "manifest_private_key", "manifest_public_key", "org_json_classpath"]
    if publish:
        names.append("publisher_token_file")
    return [name for name in names if not Path(c[name]).is_file()]


def check(c):
    missing = runtime_paths(c)
    runner = Runner(c)
    blockers = []
    try:
        head = source_state(runner)
    except ReleaseError as error:
        blockers.append(str(error))
        head = runner.git("rev-parse", "HEAD", check=False)[1]
    try:
        core_acceptance(runner, head)
    except ReleaseError as error:
        blockers.append(str(error))
    return {"infrastructureReady": not missing, "missingPaths": missing, "readyToRelease": not missing and not blockers, "blockers": blockers}


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--plan", action="store_true", help="Offline preview only; no fetch/build/upload")
    mode.add_argument("--check", action="store_true", help="Read-only local readiness check")
    mode.add_argument("--prepare-only", action="store_true", help="Explicit local candidate preparation; never uploads")
    args = parser.parse_args(argv)
    try:
        c = load_config(args.config)
        if args.plan:
            result = {"mode": "plan_only", "repo": c["repo"], "origin": c["origin"], "noBuildOrPublication": True,
                      "stages": ["保留现场并同步main", "核心批量验收门", "线上签名基线及实际本地版本", "本地检查与一次APK构建", "同签名差分生成及完整重建", "整包与差分上传/公开核验", "最后激活签名清单"],
                      "uploadResume": "同源码且已有有效产物时复用，不重复打包", "readiness": check(c)}
        elif args.check:
            result = check(c)
        else:
            result = run_release(c, args.prepare_only)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if args.plan or result.get("readyToRelease", True) else 2
    except ScriptUpdated as error:
        restarts = int(os.environ.get("POCKETWORLD_RELEASE_RESTARTS", "0"))
        if restarts >= 1:
            print("发布脚本持续发生变化，已停止；没有构建或上传。", file=sys.stderr)
            return 2
        os.environ["POCKETWORLD_RELEASE_RESTARTS"] = str(restarts + 1)
        print(str(error), flush=True)
        os.execv(sys.executable, [sys.executable, "-B", str(Path(__file__).resolve()), *(sys.argv[1:] if argv is None else argv)])
    except (ReleaseError, OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print("发布已停止：" + sanitize(str(error)), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
