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
    excluded = "".join("<li>" + html.escape(item["area"] + "：" + item["requires"]) + "</li>"
                       for item in REPORT.get("coverage", {}).get("notCovered", []))
    (OUTPUT / "index.html").write_text(
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        '<title>PocketWorld UI screenshots</title><style>body{font:16px system-ui;margin:24px;background:#eee;color:#222}'
        'main{display:flex;flex-wrap:wrap;gap:24px}figure{margin:0;width:320px}img{width:100%;height:auto}'
        'figcaption{margin:12px 0}li{margin:6px 0}</style><h1>PocketWorld 实际界面截图</h1>'
        '<p>来自当前构建的真实 APK。不是测试结果，也不是已验收声明。</p><p>Source: ' +
        html.escape(str(REPORT.get("sourceCommit", "unavailable"))) + '</p><ul>' + notes + '</ul>' +
        ('<h2>未到达的页面</h2><ul>' + unavailable + '</ul>' if unavailable else '') +
        ('<h2>需要真实对象或环境的未覆盖状态</h2><ul>' + excluded + '</ul>' if excluded else '') +
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


def find_target(tree, label, *, prefix=False, first_equivalent=False):
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
    # Only opt in for duplicate controls whose source invokes the same action
    # (the team empty state and toolbar both open the same unsaved editor).
    if first_equivalent and points:
        return min(points, key=lambda point: (point[1], point[0]))
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


def tap(label, tree=None, *, prefix=False, first_equivalent=False):
    point = find_target(tree if tree is not None else read_layout(), label, prefix=prefix,
                        first_equivalent=first_equivalent)
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
    print(f"Captured {path.name}: {title}", flush=True)


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
    controls = ("关闭文件工作区", "关闭导航", "打开导航", "对话菜单", "工作区与导航",
                "暂时跳过准备", "以后再说")
    tree = read_layout()
    if tree is None or not any(node.get("package") == PACKAGE for node in tree.iter("node")):
        # Never swipe a launcher or the Android notification shade while looking
        # for app navigation. Preserve the real failure instead of leaving the app.
        return tree
    # Some real pages (for example the notification inbox) scroll their own
    # navigation header off-screen. Restore it through touch before looking
    # for a drawer button; waiting cannot make an off-screen button visible.
    if not any(find_target(tree, label, prefix=label == "对话菜单") for label in controls):
        tree = scroll_to_top(tree)
    # Profile and other detail views intentionally expose Back, not a drawer.
    # Follow their visible controls rather than assuming every page has global
    # navigation. This only leaves unchanged screens; forms are never submitted.
    for _ in range(3):
        if any(find_target(tree, label, prefix=label == "对话菜单") for label in controls):
            break
        if not any(tap(label, tree) for label in ("返回上一级", "返回对话", "返回")):
            break
        tree = read_layout()
    if not any(find_target(tree, label, prefix=label == "对话菜单") for label in controls):
        tree = wait_for_controls(*controls, timeout=5)
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


def scroll_page(direction="down"):
    # Stay inside the content area, away from Android's Back gesture edges.
    start, end = (1810, 680) if direction == "down" else (680, 1810)
    adb("shell", "input", "swipe", "840", str(start), "840", str(end), "450")
    REPORT["actions"].append({"action": "scroll", "direction": direction,
                              "source": "actual Android touch input"})
    time.sleep(1)
    return read_layout()


def locate_control(label, *, first_equivalent=False):
    tree = read_layout()
    for attempt in range(5):
        if find_target(tree, label, prefix=True, first_equivalent=first_equivalent):
            return tree
        if attempt == 4:
            break
        prior = visible_labels(tree)
        tree = scroll_page()
        if visible_labels(tree) == prior:
            break
    return tree


def scroll_to_top(tree):
    for _ in range(5):
        prior = visible_labels(tree)
        tree = scroll_page("up")
        if visible_labels(tree) == prior:
            break
    return tree


def capture_pages(slug, title, tree, path, markers=(), *, scroll=True):
    labels = visible_labels(tree)
    observed = bool(all(marker in labels for marker in markers) and not find_target(tree, "关闭导航")) if markers else None
    screenshot(slug, title, tree=tree, requested_path=list(path), destination_observed=observed)
    if observed is False or not labels:
        REPORT["unavailable"].append(" → ".join(path) + "：已点按入口，但当前可访问内容未确认目标；请查看实际图片。")
    if not scroll or not labels:
        return
    last = labels
    for page in range(1, 5):
        tree = scroll_page()
        labels = visible_labels(tree)
        if not labels or labels == last:
            break
        screenshot(f"{slug}-scroll-{page}", title + f" · 向下滚动 {page}", tree=tree,
                   requested_path=list(path))
        last = labels
    else:
        REPORT["unavailable"].append(" → ".join(path) + "：已保留首屏及 4 次滚动，未宣称无限长内容全部覆盖。")


def capture_route(path, slug, markers=(), *, dialog=False, scroll=True):
    # Every route starts from the real drawer. Never dispatch internal routes or
    # bridge commands; only inspect current accessible controls and tap them.
    print("Opening " + " → ".join(path), flush=True)
    tree = navigation()
    if not find_target(tree, "关闭导航"):
        REPORT["unavailable"].append(" → ".join(path) + "：当前导航未打开。")
        return
    if not find_target(tree, path[0], prefix=True) and tap("更多工作区", tree, prefix=True):
        tree = read_layout()
    for index, label in enumerate(path):
        if index:
            tree = locate_control(label, first_equivalent=label == "新建团队")
        if not tap(label, tree, prefix=True, first_equivalent=label == "新建团队"):
            REPORT["unavailable"].append(" → ".join(path) + "：未找到当前可用入口“" + label + "”。")
            return
        tree = wait_for_page_surface() if index == 0 else read_layout()
        if index == 0 and not find_target(tree, "关闭文件工作区"):
            # The product correctly preserves per-page reading positions. Each
            # capture starts at the real top again using touch, not injected state.
            tree = scroll_to_top(tree)
    # The file workspace is a dismissible bottom sheet. A downward gesture
    # closes the actual sheet instead of resetting a full-page scroll position.
    capture_pages(slug, " → ".join(path), tree, path, markers,
                  scroll=scroll and not find_target(tree, "关闭文件工作区"))
    if dialog:
        # These scenarios only open forms/popovers. Do not save, send, pair,
        # install, authorize, toggle policy, request a model or create records.
        tree = read_layout()
        closed = next((label for label in (
            "关闭设置", "关闭执行权限", "关闭模型选择", "关闭推理设置",
            "关闭文件工作区", "关闭", "取消", "返回上一级", "返回"
        ) if tap(label, tree)), None)
        # Android Back can leave the Activity while a WebView modal is still
        # open. Prefer its actual close control; an uncloseable surface remains
        # explicitly partial rather than pretending it was dismissed.
        REPORT["actions"].append({"action": "dismiss", "path": list(path),
                                  "control": closed, "saved": False})
        if closed is None and not any(find_target(tree, label) for label in ("打开导航", "对话菜单")):
            REPORT["unavailable"].append(" → ".join(path) + "：未找到明确关闭入口，未发送系统返回。")


def capture_catalog():
    REPORT["coverage"] = {
        "scope": "Real fresh-install navigation, available tabs, unsaved forms and scroll positions",
        "notCovered": [
            {"area": "有内容的对话、流式回复、工具调用、任务轨迹和审批", "requires": "真实模型配置与真实执行记录；不植入合成消息或任务"},
            {"area": "已保存团队、群成员、记忆详情、项目文件、文件阅读编辑与预览", "requires": "真实用户对象或文件；本任务不创建占位内容"},
            {"area": "主屏控制、独立屏、配对设备、SSH 与电脑终端", "requires": "真实设备连接及对应授权；不伪造连接状态"},
            {"area": "登录后账号、媒体生成、实时通话与在线服务结果", "requires": "真实账号/服务及对应授权；本任务不登录或调用付费服务"},
            {"area": "横屏、深色、键盘、运行中和错误状态的全部组合", "requires": "对应真实状态；本次仅当前浅色竖屏，不把页面截图当功能验收"},
        ],
    }
    # Base pages include the genuinely empty state of this installation.
    pages = [
        (("任务",), "tasks", ("任务",)),
        (("设备",), "devices", ("设备连接",)),
        (("记忆",), "memory", ("记忆",)),
        (("能力",), "capabilities", ("技能与工具",)),
        (("团队",), "teams", ("团队",)),
        (("成果",), "artifacts", ("文件与成果",)),
        (("项目",), "projects", ("切换项目",)),
        (("自动化",), "automation", ("自动化",)),
        (("设置",), "settings", ("设置",)),
        (("设置", "AI 接入"), "ai-services", ("AI 接入", "服务与账号")),
        (("通知",), "notifications", ()),
        (("媒体",), "media", ()),
        (("我的，个人资料与偏好",), "profile", ("个人资料",)),
        (("开发诊断",), "diagnostics", ("开发诊断",)),
    ]
    # Read-only subpages are reached via the same visible tabs a person uses.
    subpages = [
        (("设备", "电脑"), "devices-computer"),
        (("设备", "SSH"), "devices-ssh"),
        (("设备", "本机开发"), "devices-local"),
        (("记忆", "待审阅"), "memory-pending"),
        (("记忆", "旧事与来源"), "memory-history"),
        (("记忆", "学习与经验"), "memory-learning"),
        (("能力", "插件"), "tools-plugins"),
        (("能力", "插件", "选择版本"), "plugins-catalog"),
        (("能力", "插件", "JS 扩展"), "plugins-js"),
        (("能力", "插件", "完整功能包"), "plugins-packs"),
        (("能力", "MCP"), "tools-mcp"),
        (("能力", "工作配置"), "tools-profiles"),
        (("团队", "协作记录"), "team-runs"),
        (("团队", "群协作"), "team-groups"),
        (("团队", "消息渠道"), "team-channels"),
        (("自动化", "委托与目标"), "automation-commitments"),
        (("媒体", "实时通话"), "media-realtime"),
        (("设置", "AI 接入", "模型"), "ai-models"),
        (("设置", "AI 接入", "调度"), "ai-routing"),
        (("设置", "AI 接入", "本机 API"), "ai-local-api"),
        (("设置", "AI 接入", "用量与诊断"), "ai-usage"),
        (("设置", "外观与字体"), "settings-appearance"),
        (("设置", "语音与朗读"), "settings-speech"),
        (("设置", "系统权限"), "settings-permissions"),
        (("设置", "观察与隐私"), "settings-observation"),
        (("设置", "后台运行"), "settings-background"),
        (("设置", "模型调用记录"), "settings-usage"),
        (("设置", "项目存储"), "settings-storage"),
        (("设置", "备份与迁移"), "settings-backups"),
    ]
    # No form submission or credential entry is part of a screenshot path.
    forms = [
        (("对话", "执行权限"), "composer-permissions"),
        (("对话", "输入方式与选项"), "composer-options"),
        (("对话", "查看上下文："), "composer-context"),
        (("项目", "新项目"), "project-new"),
        (("团队", "新建团队"), "team-new"),
        (("团队", "群协作", "新建群空间"), "group-new"),
        (("能力", "工作配置", "新建配置"), "profile-new"),
        (("自动化", "新建提醒"), "reminder-new"),
        (("设置", "AI 接入", "添加服务"), "ai-add-service"),
        (("媒体", "服务配置"), "media-configuration"),
        (("设置", "应用更新"), "application-updates"),
    ]
    group = os.environ.get("CAPTURE_GROUP", "all") or "all"
    if group not in ("all", "forms"):
        raise RuntimeError("Unsupported screenshot capture group")
    if group == "forms":
        pages, subpages = [], []
    REPORT["coverage"]["group"] = group
    REPORT["coverage"]["plannedPaths"] = [["输入区", "切换此对话模型："], ["输入区", "推理强度："], ["设置", "准备中心"]] + [list(path) for path, *_ in pages + subpages + forms]
    save_report()
    for path, slug, markers in pages:
        capture_route(path, slug, markers)
    for path, slug in subpages:
        capture_route(path, slug)
    for path, slug in forms:
        capture_route(path, slug, dialog=True)


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
    # Inspect the real composer menus. A fresh install may expose only the
    # model empty state; never configure a fake model to enable reasoning.
    for label, close_label, slug, title in (
        ("切换此对话模型：", "关闭模型选择", "model-picker", "此对话的模型选择"),
        ("推理强度：", "关闭推理设置", "reasoning-picker", "此对话的推理强度"),
    ):
        tree = read_layout()
        if not tap(label, tree, prefix=True):
            REPORT["unavailable"].append(title + "：当前入口未启用或不可见，保留实际配置状态。")
            continue
        tree = wait_for_controls(close_label, timeout=15)
        if slug == "model-picker":
            tree = read_layout(("添加你的第一个 AI 服务",))
        observed = find_target(tree, close_label) is not None
        screenshot(slug, "点按输入区入口后的" + title, tree=tree,
                   requested_path=[label], destination_observed=observed)
        if not tap(close_label, tree):
            REPORT["unavailable"].append(title + "：关闭入口不可用，按系统返回恢复页面。")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
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
    capture_route(("设置", "准备中心"), "setup", ("准备中心", "可选能力"), dialog=True, scroll=False)
    capture_catalog()


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



