#!/usr/bin/env bash
# Capture the installed formal Android WebView app, never a substitute browser page.
set -euo pipefail

PW_REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$PW_REPO_ROOT"
PW_CAPTURE_DIR=${1:-build-artifacts/web-ui-captures}
PW_APK=${2:-build-artifacts/app-debug.apk}
PW_ANDROID_SDK=${ANDROID_HOME:?Android SDK is required}
PW_ADB="$PW_ANDROID_SDK/platform-tools/adb"
PW_EMULATOR="$PW_ANDROID_SDK/emulator/emulator"
PW_AVDMANAGER="$PW_ANDROID_SDK/cmdline-tools/latest/bin/avdmanager"
PW_SERIAL=emulator-5580
PW_AVD_NAME="pocketworld_web_${GITHUB_RUN_ID:-local}_${GITHUB_RUN_ATTEMPT:-$$}"
PW_AVD_ROOT=${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pocketworld-web-avd
export ANDROID_AVD_HOME="$PW_AVD_ROOT"
mkdir -p "$PW_CAPTURE_DIR" "$ANDROID_AVD_HOME"
PW_CAPTURE_DIR=$(realpath "$PW_CAPTURE_DIR")
PW_APK=$(realpath "$PW_APK")
PW_STAGE=preflight
PW_EMULATOR_PID=
PW_LOGCAT_PID=
PW_RECORD_PID=

pw_adb() {
    local limit=30 remaining
    # pw_scene's local deadline is visible to its nested calls. A blocked dump
    # must not turn the 20s scene wait into five independent 30s adb waits.
    if [[ -n "${PW_ADB_DEADLINE:-}" ]]; then
        remaining=$((PW_ADB_DEADLINE - SECONDS))
        (( remaining > 0 )) || return 124
        (( remaining >= limit )) || limit=$remaining
    fi
    timeout "${limit}s" "$PW_ADB" -s "$PW_SERIAL" "$@"
}

pw_cleanup() {
    PW_EXIT_CODE=$?
    trap - EXIT INT TERM
    set +e
    if [[ "$PW_EXIT_CODE" != 0 ]]; then
        PW_FAILURE_STAGE="$PW_STAGE" PW_FAILURE_CODE="$PW_EXIT_CODE" python3 - "$PW_CAPTURE_DIR/failure.json" <<'PY'
import json, os, pathlib, sys
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    'stage': os.environ['PW_FAILURE_STAGE'], 'exit_code': int(os.environ['PW_FAILURE_CODE']),
    'status': 'failed; partial files are diagnostic evidence, not acceptance',
}, indent=2) + '\n')
PY
    fi
    if [[ -n "$PW_EMULATOR_PID" ]]; then
        pw_adb logcat -d -v threadtime > "$PW_CAPTURE_DIR/device-logcat-final.txt" 2>&1
        pw_adb shell dumpsys activity activities > "$PW_CAPTURE_DIR/activity-final.txt" 2>&1
        if [[ "$PW_EXIT_CODE" != 0 ]]; then
            pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/failure.png"
        fi
        # This AVD and recording were created by this script on the CI runner.
        if [[ -n "$PW_RECORD_PID" ]] && kill -0 "$PW_RECORD_PID" 2>/dev/null; then
            pw_adb shell pkill -INT screenrecord
            kill "$PW_RECORD_PID" 2>/dev/null
        fi
        pw_adb emu kill > "$PW_CAPTURE_DIR/emulator-stop.txt" 2>&1
        kill "$PW_EMULATOR_PID" 2>/dev/null
    fi
    if [[ -n "$PW_LOGCAT_PID" ]]; then kill "$PW_LOGCAT_PID" 2>/dev/null; fi
    exit "$PW_EXIT_CODE"
}
trap pw_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

test -s "$PW_APK"
test -s build-artifacts/apk-identity.json
test -c /dev/kvm
test -r /dev/kvm && test -w /dev/kvm
"$PW_EMULATOR" -version > "$PW_CAPTURE_DIR/emulator-version.txt" 2>&1
"$PW_EMULATOR" -accel-check > "$PW_CAPTURE_DIR/acceleration.txt" 2>&1
"$PW_ADB" start-server
if "$PW_ADB" devices | awk '{print $1}' | grep -qx "$PW_SERIAL"; then
    echo "Capture port is already owned by another emulator" >&2
    exit 1
fi

PW_STAGE=create-avd
# API35's official Google APIs x86_64 image declares ARM64 translation. The
# booted guest and provider below remain authoritative; minimum API30 runtime
# compatibility is a separate, still unverified target.
PW_SYSTEM_IMAGE='system-images;android-35;google_apis;x86_64'
PW_IMAGE_ROOT="$PW_ANDROID_SDK/system-images/android-35/google_apis/x86_64"
cp "$PW_IMAGE_ROOT/source.properties" "$PW_CAPTURE_DIR/image-source.properties"
cp "$PW_IMAGE_ROOT/package.xml" "$PW_CAPTURE_DIR/image-package.xml"
"$PW_AVDMANAGER" create avd --force --name "$PW_AVD_NAME" \
    --package "$PW_SYSTEM_IMAGE" --device pixel_3a <<<'no' \
    > "$PW_CAPTURE_DIR/avd-create.txt" 2>&1
# Current official software renderer; old swiftshader_indirect is deprecated.
"$PW_EMULATOR" -avd "$PW_AVD_NAME" -port 5580 -no-window -no-audio \
    -no-snapshot -no-boot-anim -gpu software -accel on -memory 2048 -cores 2 \
    -camera-back none -camera-front none \
    > "$PW_CAPTURE_DIR/emulator.log" 2>&1 &
PW_EMULATOR_PID=$!

PW_STAGE=boot
PW_BOOT_DEADLINE=$((SECONDS + 300))
PW_BOOTED=false
while (( SECONDS < PW_BOOT_DEADLINE )); do
    kill -0 "$PW_EMULATOR_PID" 2>/dev/null || { echo "Emulator exited before boot" >&2; exit 1; }
    if [[ $(pw_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
        PW_BOOTED=true
        break
    fi
    sleep 2
done
[[ "$PW_BOOTED" == true ]] || { echo "Android did not boot within 300 seconds" >&2; exit 1; }

PW_STAGE=abi-gate
pw_adb shell getprop > "$PW_CAPTURE_DIR/device-properties.txt"
PW_API=$(pw_adb shell getprop ro.build.version.sdk | tr -d '\r')
PW_ANDROID_RELEASE=$(pw_adb shell getprop ro.build.version.release | tr -d '\r')
PW_BUILD_FINGERPRINT=$(pw_adb shell getprop ro.build.fingerprint | tr -d '\r')
[[ "$PW_API" == 35 ]] || { echo "Expected the official API35 capture guest; observed API $PW_API" >&2; exit 1; }
PW_ABIS=$(pw_adb shell getprop ro.product.cpu.abilist | tr -d '\r')
PW_NATIVE_BRIDGE=$(pw_adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')
case ",$PW_ABIS," in
    *,arm64-v8a,*) ;;
    *) echo "This API35 guest does not advertise ARM64; do not strip or rebuild the APK to pass" >&2; exit 1 ;;
esac
[[ "$PW_NATIVE_BRIDGE" == libndk_translation.so ]] || {
    echo "Official x86_64 guest ARM64 translation bridge was not observed" >&2; exit 1;
}
pw_adb shell wm size 1080x2220
pw_adb shell wm density 440
pw_adb shell settings put system font_scale 1.0
pw_adb shell settings put system accelerometer_rotation 0
pw_adb shell settings put system user_rotation 0
pw_adb shell settings put global window_animation_scale 1.0
pw_adb shell settings put global transition_animation_scale 1.0
pw_adb shell settings put global animator_duration_scale 1.0
pw_adb shell input keyevent KEYCODE_WAKEUP
pw_adb shell wm dismiss-keyguard
pw_adb shell dumpsys webviewupdate > "$PW_CAPTURE_DIR/webview-provider.txt"
PW_STAGE=webview-provider-gate
python3 tools/ci/verify-web-ui.py provider --dump "$PW_CAPTURE_DIR/webview-provider.txt" \
    --out "$PW_CAPTURE_DIR/webview-identity.json" > /dev/null

PW_STAGE=install
pw_adb install -r -t "$PW_APK" > "$PW_CAPTURE_DIR/install.txt" 2>&1
pw_adb logcat -c
"$PW_ADB" -s "$PW_SERIAL" logcat -v threadtime > "$PW_CAPTURE_DIR/device-logcat.txt" 2>&1 &
PW_LOGCAT_PID=$!
PW_STAGE=start-formal-app
pw_adb shell am start -W -n dev.pocketengine/.ui.ComposeActivity > "$PW_CAPTURE_DIR/start.txt" 2>&1
PW_READY=false
PW_READY_DEADLINE=$((SECONDS + 90))
while (( SECONDS < PW_READY_DEADLINE )); do
    if grep -Eqi 'PocketWebUi.*(resource-error|console-error|renderer-exit|render-process-gone|main-frame-error)' "$PW_CAPTURE_DIR/device-logcat.txt"; then
        echo "Web UI failed during startup; see captured logcat" >&2
        exit 1
    fi
    if grep -Eq 'PocketWebUi.*ready source=library-web' "$PW_CAPTURE_DIR/device-logcat.txt"; then
        PW_READY=true
        break
    fi
    sleep 1
done
[[ "$PW_READY" == true ]] || { echo "Formal library Web UI ready signal was not observed" >&2; exit 1; }
PW_APP_PID=$(pw_adb shell pidof -s dev.pocketengine | tr -d '\r')
[[ "$PW_APP_PID" =~ ^[0-9]+$ ]] || { echo "App process is not running" >&2; exit 1; }
pw_adb shell dumpsys activity activities > "$PW_CAPTURE_DIR/activity-ready.txt"
python3 tools/ci/verify-web-ui.py foreground --dump "$PW_CAPTURE_DIR/activity-ready.txt" \
    --out "$PW_CAPTURE_DIR/foreground-ready.json"

pw_dump_layout() {
    local name=$1 attempt
    local remote=/sdcard/pocketworld-web-layout.xml
    for attempt in 1 2 3 4 5; do
        if [[ -n "${PW_ADB_DEADLINE:-}" ]] && (( SECONDS >= PW_ADB_DEADLINE )); then break; fi
        if pw_adb shell uiautomator dump --compressed "$remote" >> "$PW_CAPTURE_DIR/uiautomator.txt" 2>&1 &&
            pw_adb exec-out cat "$remote" > "$PW_CAPTURE_DIR/$name.tmp"; then
            if python3 - "$PW_CAPTURE_DIR/$name.tmp" <<'PY'
import sys, xml.etree.ElementTree as ET
assert ET.parse(sys.argv[1]).getroot().tag == 'hierarchy'
PY
            then
                mv "$PW_CAPTURE_DIR/$name.tmp" "$PW_CAPTURE_DIR/$name"
                return 0
            fi
        fi
        if [[ -n "${PW_ADB_DEADLINE:-}" ]] && (( SECONDS >= PW_ADB_DEADLINE )); then break; fi
        sleep 1
    done
    echo "Android accessibility hierarchy could not be read" >&2
    return 1
}

pw_scene() {
    local name=$1 scene=$2 expected_ime=${3:-} started=$SECONDS attempt=0
    local PW_ADB_DEADLINE=$((SECONDS + 20))
    local attempt_error="$PW_CAPTURE_DIR/$name-scene-attempt-error.tmp"
    local last_error="$PW_CAPTURE_DIR/$name-scene-last-error.txt"
    local wait_log="$PW_CAPTURE_DIR/$name-scene-wait.txt"
    while (( SECONDS < PW_ADB_DEADLINE )); do
        attempt=$((attempt + 1))
        if pw_dump_layout "$name.xml" > "$attempt_error" 2>&1 &&
            python3 tools/ci/verify-web-ui.py scene --xml "$PW_CAPTURE_DIR/$name.xml" \
                --scene "$scene" --out "$PW_CAPTURE_DIR/$name-scene.json" > "$attempt_error" 2>&1 &&
            { [[ -z "$expected_ime" ]] || {
                pw_adb shell dumpsys input_method > "$PW_CAPTURE_DIR/$name-input-method.txt" 2>> "$attempt_error" &&
                python3 tools/ci/verify-web-ui.py ime --xml "$PW_CAPTURE_DIR/$name.xml" \
                    --dump "$PW_CAPTURE_DIR/$name-input-method.txt" --visible "$expected_ime" \
                    --out "$PW_CAPTURE_DIR/$name-ime.json" >> "$attempt_error" 2>&1;
            }; }; then
            printf 'scene=%s attempt=%s elapsed=%ss result=accepted-by-original-scene-assertion\n' \
                "$scene" "$attempt" "$((SECONDS - started))" >> "$wait_log"
            rm -f "$attempt_error"
            return 0
        fi
        mv "$attempt_error" "$last_error"
        printf 'scene=%s attempt=%s elapsed=%ss result=not-ready\n' \
            "$scene" "$attempt" "$((SECONDS - started))" >> "$wait_log"
        # Retain each original assertion failure even if the deadline's final XML dump fails.
        cat "$last_error" >> "$wait_log"
        (( SECONDS < PW_ADB_DEADLINE )) || break
        sleep 1
    done
    echo "Android scene $scene did not satisfy its original assertions within 20 seconds; see $name.xml and $name-scene-last-error.txt" >&2
    [[ ! -f "$last_error" ]] || cat "$last_error" >&2
    return 1
}

# All coordinates come from the exact current Android accessibility hierarchy.
# Prefix mode is explicit for BoardUI buttons whose name also includes a subtitle.
pw_tap() {
    local name=$1 label=$2 receipt=$3
    shift 3
    local point x y
    point=$(python3 tools/ci/verify-web-ui.py target --xml "$PW_CAPTURE_DIR/$name.xml" \
        --label "$label" --out "$PW_CAPTURE_DIR/$receipt.json" "$@")
    read -r x y <<< "$point"
    [[ "$x" =~ ^[0-9]+$ && "$y" =~ ^[0-9]+$ ]]
    pw_adb shell input tap "$x" "$y"
    sleep 1
}

PW_STAGE=entry-capture
sleep 1
pw_scene entry workroom
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/01-workroom.png"
PW_STAGE=screen-recording
timeout 195s "$PW_ADB" -s "$PW_SERIAL" shell screenrecord --time-limit 180 --bit-rate 4000000 \
    --size 720x1480 /sdcard/pocketworld-web-interaction.mp4 \
    > "$PW_CAPTURE_DIR/screenrecord.txt" 2>&1 &
PW_RECORD_PID=$!
sleep 3

PW_STAGE=open-work-switcher
PW_POINT=$(python3 tools/ci/verify-web-ui.py target --xml "$PW_CAPTURE_DIR/entry.xml" \
    --label '全部工作' --out "$PW_CAPTURE_DIR/open-work-target.json")
read -r PW_X PW_Y <<< "$PW_POINT"
[[ "$PW_X" =~ ^[0-9]+$ && "$PW_Y" =~ ^[0-9]+$ ]]
pw_adb shell input tap "$PW_X" "$PW_Y"
sleep 1
pw_scene all-work work-menu
PW_CLOSE_POINT=$(python3 tools/ci/verify-web-ui.py target --xml "$PW_CAPTURE_DIR/all-work.xml" \
    --label '关闭全部工作' --out "$PW_CAPTURE_DIR/close-work-target.json")
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/02-all-work.png"
sleep 3

PW_STAGE=return-to-work
read -r PW_X PW_Y <<< "$PW_CLOSE_POINT"
[[ "$PW_X" =~ ^[0-9]+$ && "$PW_Y" =~ ^[0-9]+$ ]]
pw_adb shell input tap "$PW_X" "$PW_Y"
sleep 1
pw_scene return workroom
python3 - "$PW_CAPTURE_DIR/return.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).getroot().iter('node'))
assert not any(n.get('content-desc') == '关闭全部工作' or n.get('text') == '关闭全部工作' for n in nodes), 'Work switcher did not close'
PY
python3 tools/ci/verify-web-ui.py target --xml "$PW_CAPTURE_DIR/return.xml" \
    --label '全部工作' --out "$PW_CAPTURE_DIR/return-work-target.json" > /dev/null
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/03-workroom-return.png"

PW_STAGE=primary-task-navigation
pw_tap return '任务' primary-tasks-target
pw_scene tasks-root tasks
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/11-tasks.png"

PW_STAGE=primary-project-navigation
pw_tap tasks-root '项目' primary-projects-target
pw_scene projects-root projects
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/12-projects.png"

PW_STAGE=primary-work-navigation
pw_tap projects-root '工作' primary-work-target
pw_scene primary-work-return workroom

PW_STAGE=work-input-real-ime
# UIAutomator may omit a WebView textarea's accessible name. Resolve the unique
# usable EditText inside the observed named section, never the section center.
# Focus that actual editor without entering or sending a draft.
# Native current IME service state and app XML jointly verify the hidden tabs.
pw_tap primary-work-return '工作输入' work-input-target --section-editor
pw_scene workroom-ime workroom-ime true
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/13-workroom-ime.png"

PW_STAGE=work-input-ime-back
pw_adb shell input keyevent KEYCODE_BACK
sleep 1
pw_scene workroom-ime-return workroom false
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/14-workroom-ime-return.png"

PW_STAGE=settings-root
pw_tap workroom-ime-return '全部工作' settings-work-target
pw_scene settings-menu work-menu
pw_tap settings-menu '设置与连接' settings-target
pw_scene settings settings
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/05-settings.png"

PW_STAGE=model-settings-purpose
pw_tap settings '模型与连接' models-group-target --match prefix
pw_scene model-purpose model-purpose
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/06-model-purpose.png"

PW_STAGE=model-directory
pw_tap model-purpose '选择模型' models-list-target --match prefix
pw_scene model-list model-list
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/07-model-directory.png"

PW_STAGE=model-connection-management
pw_tap model-list '管理连接' model-connections-target
pw_scene model-connections model-connections
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/15-connection-management.png"

PW_STAGE=model-unsaved-editor
# A fresh install normally has no API profile. Use the real add editor then;
# never seed a connection merely to make an edit-only selector pass.
pw_tap model-connections '编辑当前 API 连接' model-editor-target --match prefix --fallback-label '添加 API 连接'
pw_scene model-editor model-editor
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/08-model-editor.png"

PW_STAGE=model-advanced
pw_tap model-editor '高级设置' model-advanced-target --match prefix
pw_scene model-advanced model-advanced
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/09-model-advanced.png"

PW_STAGE=return-through-native-back
# Exercise the real Android Back delivery; no JavaScript fixture/bridge injection.
pw_adb shell input keyevent KEYCODE_BACK
sleep 1
pw_scene model-connections-return model-connections
pw_adb shell input keyevent KEYCODE_BACK
sleep 1
pw_scene model-list-return model-list
pw_adb shell input keyevent KEYCODE_BACK
sleep 1
pw_scene settings-return model-purpose
pw_adb shell input keyevent KEYCODE_BACK
sleep 1
pw_scene workroom-final workroom
pw_adb exec-out screencap -p > "$PW_CAPTURE_DIR/10-workroom-final.png"

PW_STAGE=finalize-recording
# End this script-owned recording at the actual journey end, retaining MP4
# finalization. The added root-navigation and real IME legs use the native
# 180s recording limit and a 195s host timeout; neither can run indefinitely.
kill -0 "$PW_RECORD_PID" 2>/dev/null || { echo "Screen recording ended before the navigation journey completed" >&2; exit 1; }
pw_adb shell pkill -INT screenrecord
wait "$PW_RECORD_PID"
PW_RECORD_PID=
pw_adb pull /sdcard/pocketworld-web-interaction.mp4 "$PW_CAPTURE_DIR/04-interaction.mp4" \
    > "$PW_CAPTURE_DIR/recording-pull.txt" 2>&1
PW_STAGE=record-device-identity
PW_FINAL_PID=$(pw_adb shell pidof -s dev.pocketengine | tr -d '\r')
[[ "$PW_FINAL_PID" == "$PW_APP_PID" ]] || { echo "App process changed or exited during capture" >&2; exit 1; }
pw_adb shell dumpsys activity activities > "$PW_CAPTURE_DIR/activity-return.txt"
python3 tools/ci/verify-web-ui.py foreground --dump "$PW_CAPTURE_DIR/activity-return.txt" \
    --out "$PW_CAPTURE_DIR/foreground-return.json"
pw_adb shell dumpsys webviewupdate > "$PW_CAPTURE_DIR/webview-provider-final.txt"
PW_ABIS="$PW_ABIS" PW_NATIVE_BRIDGE="$PW_NATIVE_BRIDGE" PW_APP_PID="$PW_APP_PID" \
PW_FINAL_PID="$PW_FINAL_PID" PW_DEVICE_SERIAL="$PW_SERIAL" PW_API="$PW_API" \
PW_ANDROID_RELEASE="$PW_ANDROID_RELEASE" PW_BUILD_FINGERPRINT="$PW_BUILD_FINGERPRINT" \
PW_SYSTEM_IMAGE="$PW_SYSTEM_IMAGE" python3 - "$PW_CAPTURE_DIR/device.json" <<'PY'
import json, os, pathlib, sys
root = pathlib.Path(sys.argv[1]).parent
pathlib.Path(sys.argv[1]).write_text(json.dumps({
    'api': int(os.environ['PW_API']), 'android_release': os.environ['PW_ANDROID_RELEASE'],
    'build_fingerprint': os.environ['PW_BUILD_FINGERPRINT'], 'system_image': os.environ['PW_SYSTEM_IMAGE'],
    'minimum_api30_runtime_verified': False,
    'abi_list': os.environ['PW_ABIS'], 'native_bridge': os.environ['PW_NATIVE_BRIDGE'],
    'serial': os.environ['PW_DEVICE_SERIAL'], 'pid': os.environ['PW_APP_PID'], 'final_pid': os.environ['PW_FINAL_PID'],
    'activity': 'dev.pocketengine/.ui.ComposeActivity',
    'source_url': 'https://pocketengine.local/assets/pocket-ui/index.html', 'ready': True,
    'physical_phone': False, 'size_px': [1080, 2220], 'density_dpi': 440,
    'nominal_viewport_dp': [1080 * 160 / 440, 2220 * 160 / 440],
    'font_scale': 1.0, 'system_animation_scale': 1.0,
    'emulator_version': (root / 'emulator-version.txt').read_text(errors='replace'),
    'webview_provider': (root / 'webview-provider.txt').read_text(errors='replace'),
    'webview': json.loads((root / 'webview-identity.json').read_text()),
    'foreground_ready': json.loads((root / 'foreground-ready.json').read_text()),
    'foreground_final': json.loads((root / 'foreground-return.json').read_text()),
}, ensure_ascii=False, indent=2) + '\n')
PY
PW_STAGE=verify-captures
python3 tools/ci/verify-web-ui.py verify-capture --directory "$PW_CAPTURE_DIR" \
    --apk "$PW_APK" --apk-identity build-artifacts/apk-identity.json
cp build-artifacts/apk-identity.json "$PW_CAPTURE_DIR/apk-identity.json"
PW_STAGE=complete
