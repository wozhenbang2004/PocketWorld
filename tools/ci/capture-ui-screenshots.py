#!/usr/bin/env python3
"""Screenshot the real release APK on a disposable emulator, without test cases.

No application database, preferences, bridge messages, account credentials or
conversation data are injected. Missing UI destinations are recorded, not faked.
"""
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


REPOSITORY = "wozhenbang2004/PocketWorld"
PACKAGE = "dev.pocketengine"
OUTPUT = Path("build-artifacts/ui-screenshots").resolve()
OUTPUT.mkdir(parents=True, exist_ok=True)
TEMP = Path(os.environ["RUNNER_TEMP"]) / "pocketworld-ui-screenshots"
TEMP.mkdir(parents=True, exist_ok=True)
SDK = Path(os.environ["ANDROID_HOME"])
ADB = SDK / "platform-tools/adb"
SERIAL = "emulator-5580"
REPORT = {
    "purpose": "screenshots, not functional or visual test results",
    "toolingCommit": os.environ.get("GITHUB_SHA"),
    "screens": [], "unavailable": [], "actions": [],
    "limitations": [
        "Fresh Android 15 emulator, not a physical phone or existing user installation.",
        "No accounts, model keys, chat history, seeded tasks or injected bridge state.",
        "Only real reachable empty-state and configuration screens are captured.",
        "Screenshots do not establish functional correctness, performance or UI acceptance.",
        "APK ARM64 code uses the official emulator image's native translation when available.",
    ],
}
EMULATOR = None
EMULATOR_LOG = None
SEQUENCE = 0


def save_report():
    (OUTPUT / "capture.json").write_text(json.dumps(REPORT, ensure_ascii=False, indent=2) + "\n")
    cards = "".join(
        '<figure><figcaption>' + html.escape(item["title"]) + '</figcaption><a href="' +
        html.escape(item["file"], quote=True) + '"><img loading="lazy" src="' +
        html.escape(item["file"], quote=True) + '" alt="' + html.escape(item["title"], quote=True) +
        '"></a></figure>' for item in REPORT["screens"]
    )
    notes = "".join("<li>" + html.escape(note) + "</li>" for note in REPORT["limitations"])
    unavailable = "".join("<li>" + html.escape(item) + "</li>" for item in REPORT["unavailable"])
    (OUTPUT / "index.html").write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        '<title>PocketWorld UI screenshots</title><style>body{font:16px system-ui;margin:24px;background:#eee;color:#222}'
        'main{display:flex;flex-wrap:wrap;gap:24px}figure{margin:0;width:320px}img{width:100%;height:auto}'
        'figcaption{margin:12px 0}li{margin:6px 0}</style><h1>PocketWorld 实际界面截图</h1>'
        '<p>来自当前构建的真实 APK。不是测试结果，也不是已验收声明。</p><p>Source: ' +
        html.escape(str(REPORT.get("sourceCommit", "unavailable"))) + '</p><ul>' + notes + '</ul>' +
        ('<h2>未到达的页面</h2><ul>' + unavailable + '</ul>' if unavailable else '') +
        '<main>' + cards + '</main><p><a href="capture.json">完整截图记录</a></p></html>'
    )


def command(args, *, timeout=30, input_text=None, check=True, env=None):
    result = subprocess.run([str(arg) for arg in args], input=input_text, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=timeout, env=env)
    if check and result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} exited {result.returncode}: {result.stdout[-2000:]}")
    return result.stdout


def adb(*args, timeout=30, check=True):
    return command([ADB, "-s", SERIAL, *args], timeout=timeout, check=check)


def api(path):
    request = urllib.request.Request("https://api.github.com" + path, headers={
        "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
        "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def download_artifact(artifact_id, destination):
    # Obtain GitHub's signed storage URL without forwarding the Actions token to it.
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip",
        headers={"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
                 "Accept": "application/vnd.github+json"})
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=30)
    except urllib.error.HTTPError as failure:
        if failure.code not in (301, 302, 303, 307, 308):
            raise
        location = failure.headers.get("Location", "")
        if urllib.parse.urlsplit(location).scheme != "https":
            raise RuntimeError("Artifact download did not provide an HTTPS location") from None
        response = urllib.request.urlopen(location, timeout=120)
    with response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def release_identity(run_id):
    run = api(f"/repos/{REPOSITORY}/actions/runs/{run_id}")
    if (run.get("head_branch") != "main" or run.get("head_repository", {}).get("full_name") != REPOSITORY or
            run.get("path", "").split("@", 1)[0] != ".github/workflows/github-release.yml"):
        raise RuntimeError("Capture requires this repository's main-branch GitHub Release run")
    if not re.fullmatch(r"[0-9a-f]{40}", run.get("head_sha", "")):
        raise RuntimeError("Source release has no exact commit identity")
    return run


def acquire_apk(run_id, source):
    attempt = int(source["run_attempt"])
    requested_attempt = os.environ.get("SOURCE_RELEASE_ATTEMPT", "")
    if requested_attempt and int(requested_attempt) != attempt:
        raise RuntimeError("Release was rerun; this screenshot event belongs to the older attempt")
    expected_name = f"pocketworld-verified-build-{run_id}-{attempt}"
    deadline = time.monotonic() + 45 * 60
    while time.monotonic() < deadline:
        current = release_identity(run_id)
        if current["head_sha"] != source["head_sha"] or int(current["run_attempt"]) != attempt:
            raise RuntimeError("Source release attempt changed while waiting for its APK")
        artifacts = api(f"/repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
        matches = [item for item in artifacts if item.get("name") == expected_name and not item.get("expired")]
        if matches:
            if len(matches) != 1:
                raise RuntimeError("Source release has ambiguous immutable build artifacts")
            artifact = matches[0]
            archive = TEMP / "verified-build.zip"
            download_artifact(artifact["id"], archive)
            with zipfile.ZipFile(archive) as bundle:
                for name in ("app-debug.apk", "apk-identity.json"):
                    paths = [path for path in bundle.namelist() if Path(path).name == name]
                    if len(paths) != 1:
                        raise RuntimeError(f"Build artifact does not contain one exact {name}")
                    with bundle.open(paths[0]) as data, (TEMP / name).open("wb") as output:
                        shutil.copyfileobj(data, output)
            receipt = json.loads((TEMP / "apk-identity.json").read_text())
            apk = TEMP / "app-debug.apk"
            with apk.open("rb") as apk_bytes:
                digest = hashlib.file_digest(apk_bytes, "sha256").hexdigest()
            if (receipt.get("commit") != source["head_sha"] or str(receipt.get("run_id")) != run_id or
                    str(receipt.get("run_attempt")) != str(attempt) or receipt.get("sha256") != digest):
                raise RuntimeError("APK identity does not match the selected release artifact")
            shutil.copyfile(TEMP / "apk-identity.json", OUTPUT / "apk-identity.json")
            REPORT.update(sourceCommit=source["head_sha"], sourceRunId=run_id,
                          sourceAttempt=attempt, sourceArtifactId=artifact["id"], apkSha256=digest)
            return apk
        if current.get("status") == "completed":
            raise RuntimeError("Source release completed without its immutable APK artifact")
        time.sleep(15)
    raise RuntimeError("Source APK was not available within the 45-minute capture wait")


def start_emulator():
    global EMULATOR, EMULATOR_LOG
    avd_home = TEMP / "avd"
    avd_home.mkdir(exist_ok=True)
    env = {key: value for key, value in os.environ.items()
           if key in ("PATH", "HOME", "LANG", "LD_LIBRARY_PATH")}
    env.update(ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK), ANDROID_AVD_HOME=str(avd_home))
    command([ADB, "start-server"], env=env)
    if SERIAL in command([ADB, "devices"], env=env):
        raise RuntimeError("Screenshot emulator port is already in use")
    avd_name = "pocketworld_screenshots"
    command([SDK / "cmdline-tools/latest/bin/avdmanager", "create", "avd", "--force",
             "--name", avd_name, "--package", "system-images;android-35;google_apis;x86_64",
             "--device", "pixel_3a"], input_text="no\n", timeout=60, env=env)
    args = [str(SDK / "emulator/emulator"), "-avd", avd_name, "-port", "5580", "-no-window",
            "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "software", "-accel", "on",
            "-memory", "2048", "-cores", "2", "-camera-back", "none", "-camera-front", "none"]
    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        # Elevate only this official emulator process on the disposable runner;
        # do not weaken /dev/kvm permissions or pass any GitHub credentials to it.
        args = ["sudo", "env", "-i", *[f"{key}={value}" for key, value in env.items()], *args]
    EMULATOR_LOG = (OUTPUT / "emulator.log").open("w")
    EMULATOR = subprocess.Popen(args, stdout=EMULATOR_LOG, stderr=subprocess.STDOUT, env=env)


def wait_for_boot():
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        if EMULATOR.poll() is not None:
            raise RuntimeError("Screenshot emulator exited before boot; see emulator.log")
        try:
            if adb("shell", "getprop", "sys.boot_completed", timeout=10, check=False).strip() == "1":
                return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(2)
    raise RuntimeError("Screenshot emulator did not boot within five minutes")


def layout():
    global SEQUENCE
    SEQUENCE += 1
    remote = f"/sdcard/pocketworld-screenshot-{SEQUENCE}.xml"
    try:
        text = adb("shell", "uiautomator", "dump", "--compressed", remote, timeout=12, check=False)
        if "dumped to:" not in text:
            return None
        xml = adb("exec-out", "cat", remote, timeout=10)
        (OUTPUT / f"layout-{SEQUENCE:02d}.xml").write_text(xml)
        return ET.fromstring(xml)
    except (RuntimeError, subprocess.TimeoutExpired, ET.ParseError):
        return None


def visible_bounds(node):
    if node.get("package") != PACKAGE or node.get("visible-to-user") == "false":
        return None
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if bounds:
        left, top, right, bottom = map(int, bounds.groups())
        if 0 <= left < right <= 1080 and 0 <= top < bottom <= 2220 and min(right - left, bottom - top) >= 24:
            return left, top, right, bottom
    return None


def visible_labels(tree):
    if tree is None:
        return []
    return list(dict.fromkeys(label for node in tree.iter("node") if visible_bounds(node)
                             for label in (node.get("text"), node.get("content-desc")) if label))


def read_layout(expected=(), *, attempts=4):
    # WebView may expose an empty first accessibility snapshot after it is visible.
    tree = None
    for attempt in range(attempts):
        tree = layout()
        labels = visible_labels(tree)
        if labels and (not expected or any(label == item or label.startswith(item + "，")
                                          for label in labels for item in expected)):
            break
        if attempt + 1 < attempts:
            time.sleep(1)
    return tree


def find_target(tree, label, *, prefix=False):
    if tree is None:
        return None
    parents = {child: parent for parent in tree.iter() for child in parent}
    points = set()
    for node in tree.iter("node"):
        names = (node.get("text", ""), node.get("content-desc", ""))
        if not visible_bounds(node) or not any(name == label or prefix and name.startswith(label) for name in names):
            continue
        # Text can be a child of the button. Ignore headings, disabled controls,
        # clipped/offscreen nodes and ambiguous duplicate actions.
        chain = []
        target = node
        while target is not None and target.get("package") == PACKAGE:
            if target.get("enabled") == "false" or not visible_bounds(target):
                chain = []
                break
            chain.append(target)
            if target.get("class") == "android.webkit.WebView":
                break
            target = parents.get(target)
        target = next((item for item in chain if item.get("clickable") == "true"
                       and item.get("class") != "android.webkit.WebView"), None)
        if target is not None:
            left, top, right, bottom = visible_bounds(target)
            points.add(((left + right) // 2, (top + bottom) // 2))
    return next(iter(points)) if len(points) == 1 else None


def wait_for_controls(*labels, timeout=20):
    """Wait for the current accessible actions, including WebView's first refresh."""
    deadline = time.monotonic() + timeout
    tree = None
    while time.monotonic() < deadline:
        tree = layout()
        if any(find_target(tree, label, prefix=label == "对话菜单") for label in labels):
            return tree
        time.sleep(1)
    return tree


def tap(label, tree=None, *, prefix=False):
    point = find_target(tree if tree is not None else read_layout(), label, prefix=prefix)
    if point is None:
        return False
    adb("shell", "input", "tap", str(point[0]), str(point[1]))
    REPORT["actions"].append({"label": label, "point": point, "source": "current accessibility hierarchy"})
    time.sleep(1.5)
    return True


def screenshot(slug, title, *, tree=None, requested_path=None, destination_observed=None):
    path = OUTPUT / f"{len(REPORT['screens']) + 1:02d}-{slug}.png"
    with path.open("wb") as output:
        result = subprocess.run([str(ADB), "-s", SERIAL, "exec-out", "screencap", "-p"],
                                stdout=output, stderr=subprocess.PIPE, timeout=20)
    if result.returncode or path.stat().st_size == 0:
        raise RuntimeError("Android could not produce the requested PNG")
    screen = {"file": path.name, "title": title, "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    if tree is not None:
        screen["observedLabels"] = visible_labels(tree)
    if requested_path is not None:
        screen.update(requestedPath=requested_path, destinationObserved=destination_observed)
    REPORT["screens"].append(screen)
    save_report()


def wait_for_page_surface(timeout=30):
    """Wait for two readable page snapshots; an empty tree is not a closed drawer."""
    deadline = time.monotonic() + timeout
    settled = 0
    tree = None
    while time.monotonic() < deadline:
        tree = layout()
        if visible_labels(tree) and not find_target(tree, "关闭导航") and not find_target(tree, "工作区与导航"):
            settled += 1
            if settled >= 2:
                time.sleep(2)
                return tree
        else:
            settled = 0
        time.sleep(1)
    return tree


def navigation():
    tree = wait_for_controls("关闭文件工作区", "关闭导航", "打开导航", "对话菜单", "工作区与导航",
                             "暂时跳过准备", "以后再说")
    if tap("关闭文件工作区", tree):
        tree = wait_for_controls("关闭导航", "打开导航", "对话菜单", "工作区与导航")
    if find_target(tree, "关闭导航"):
        return tree
    # The initial snapshot may have arrived before the real preparation controls.
    if tap("以后再说", tree) or tap("暂时跳过准备", tree):
        tree = wait_for_controls("对话菜单", "打开导航", timeout=30)
    if tap("打开导航", tree):
        return wait_for_controls("关闭导航")
    if tap("工作区与导航", tree):
        return wait_for_controls("关闭导航")
    if tap("对话菜单", tree, prefix=True):
        tree = wait_for_controls("工作区与导航")
    if tap("工作区与导航", tree):
        return wait_for_controls("关闭导航")
    return tree


def capture_screens(apk):
    wait_for_boot()
    for args in (("wm", "size", "1080x2220"), ("wm", "density", "440"),
                 ("input", "keyevent", "KEYCODE_WAKEUP"), ("wm", "dismiss-keyguard")):
        adb("shell", *args)
    REPORT["emulator"] = {
        "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "abis": adb("shell", "getprop", "ro.product.cpu.abilist").strip(),
        "nativeBridge": adb("shell", "getprop", "ro.dalvik.vm.native.bridge").strip(),
        "size": [1080, 2220], "density": 440,
    }
    (OUTPUT / "webview-provider.txt").write_text(adb("shell", "dumpsys", "webviewupdate", check=False))
    (OUTPUT / "install.txt").write_text(adb("install", "-t", str(apk), timeout=120))
    (OUTPUT / "launch.txt").write_text(adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.ui.ComposeActivity", timeout=60))
    tree = wait_for_controls("暂时跳过准备", "以后再说", "对话菜单", "打开导航", timeout=90)
    logs = adb("logcat", "-d", "-s", "PocketWebUi:I", "*:S", timeout=15, check=False)
    # This is the host's configuration log, not proof that the renderer is ready.
    REPORT["hostConfigurationSignalObserved"] = "host source=library-web" in logs
    REPORT["initialAccessibilityContentObserved"] = any(find_target(tree, label, prefix=label == "对话菜单")
                                                       for label in ("暂时跳过准备", "以后再说", "对话菜单", "打开导航"))
    if tap("以后再说", tree) or tap("暂时跳过准备", tree):
        tree = wait_for_controls("对话菜单", "打开导航", timeout=30)
        screenshot("workroom", "点按跳过准备后的实际界面", tree=tree)
    else:
        screenshot("first-launch", "首次启动的实际界面", tree=tree)
    tree = wait_for_controls("对话菜单", "打开导航", timeout=10)
    if tap("对话菜单", tree, prefix=True):
        tree = wait_for_controls("工作区与导航")
        screenshot("chat-menu", "点按对话菜单后的实际界面", tree=tree)
    menu = navigation()
    if not find_target(menu, "关闭导航"):
        REPORT["unavailable"].append("导航未能打开；保留启动/阻塞界面，不注入状态绕过。")
        screenshot("navigation-unavailable", "尝试打开导航后的实际状态", tree=menu)
        return
    screenshot("navigation", "当前可访问树已显示关闭导航入口的侧栏", tree=menu)
    destinations = [(("任务",), "tasks", ("任务",)), (("设备",), "devices", ("设备连接",)),
                    (("记忆",), "memory", ("记忆",)), (("能力",), "capabilities", ("技能与工具",)),
                    (("团队",), "teams", ("团队",)), (("成果",), "artifacts", ("文件与成果",)),
                    (("项目",), "projects", ("项目",)), (("自动化",), "automation", ("自动化",)),
                    (("设置",), "settings", ("设置",)),
                    (("设置", "AI 接入"), "models", ("AI 接入", "服务与账号"))]
    for path, slug, markers in destinations:
        tree = navigation()
        if not find_target(tree, "关闭导航"):
            REPORT["unavailable"].append(" → ".join(path) + "：未取得当前侧栏入口，未继续点击。")
            continue
        label = path[0]
        if not find_target(tree, label) and tap("更多工作区", tree, prefix=True):
            tree = read_layout((label,))
        clicked = tap(label, tree)
        for label in path[1:]:
            if not clicked:
                break
            tree = read_layout((label,))
            clicked = tap(label, tree, prefix=True)
        if not clicked:
            REPORT["unavailable"].append(" → ".join(path) + "：当前页面未找到唯一可见且可用的入口。")
            continue
        # Observe the real drawer leaving before taking the destination image.
        tree = wait_for_page_surface()
        if visible_labels(tree) and not find_target(tree, "关闭导航"):
            tree = read_layout((markers[-1],))
        observed = all(marker in visible_labels(tree) for marker in markers) and not find_target(tree, "关闭导航")
        screenshot(slug, "点按“" + " → ".join(path) + "”后的实际界面", tree=tree,
                   requested_path=list(path), destination_observed=bool(observed))
        if not observed:
            REPORT["unavailable"].append(" → ".join(path) + "：已点按入口，但未从当前可见标题确认目标页面。")


def main():
    run_id = os.environ.get("SOURCE_RELEASE_RUN_ID", "")
    if not re.fullmatch(r"[0-9]+", run_id):
        raise RuntimeError("A numeric GitHub Release run ID is required")
    source = release_identity(run_id)
    REPORT.update(sourceCommit=source["head_sha"], sourceRunId=run_id, sourceAttempt=source["run_attempt"])
    save_report()
    # Boot the official device while the independent release builds its APK.
    start_emulator()
    apk = acquire_apk(run_id, source)
    capture_screens(apk)


if __name__ == "__main__":
    try:
        main()
        REPORT["captureStatus"] = "partial; see unavailable destinations" if REPORT["unavailable"] else "finished; inspect the actual screens"
    except Exception as failure:
        REPORT["captureStatus"] = "capture could not finish"
        REPORT["error"] = f"{type(failure).__name__}: {failure}"
        if EMULATOR is not None:
            try:
                screenshot("capture-stopped", "截图任务停止时的实际画面")
            except Exception:
                pass
        raise
    finally:
        if EMULATOR is not None:
            try:
                (OUTPUT / "device-logcat.txt").write_text(adb("logcat", "-d", "-v", "threadtime", timeout=15, check=False))
                adb("emu", "kill", timeout=15, check=False)
            except Exception:
                pass
            try:
                EMULATOR.wait(timeout=10)
            except subprocess.TimeoutExpired:
                command(["sudo", "kill", "-TERM", str(EMULATOR.pid)], check=False)
        if EMULATOR_LOG is not None:
            EMULATOR_LOG.close()
        save_report()
