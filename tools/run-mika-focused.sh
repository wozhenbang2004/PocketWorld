#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
: "${JAVA_HOME:?Java 17 is required}"
native_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/pocketworld-mika-native.XXXXXX")"
trap 'rm -rf -- "$native_dir"' EXIT
mkdir -p "$native_dir/current" "$native_dir/unavailable"
python3 -m venv "$native_dir/python"
"$native_dir/python/bin/python" -m pip install --disable-pip-version-check -r companion/requirements-device-identity.txt
python_status=0
(cd companion && "$native_dir/python/bin/python" -m unittest -v \
  test_device_protocol.DeviceProtocolTests test_phone_tasks.PhoneDispatchTests \
  test_phone_tasks.LocalSurfaceTests test_phone_tasks.UsbOwnershipTests) || python_status=$?
node_status=0
node --check companion/phone_task_desk.js || node_status=$?
python3 tools/prepare-mika-focused-sources.py
sources=app/src/main/java/dev/pocketengine/features/local/runtime/native
g++ -std=c++17 -Wall -Wextra -Werror -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$sources/process_supervisor.cpp" "$sources/pty_supervisor.cpp" "$sources/workspace_file_access.cpp" \
  -o "$native_dir/current/libpocketprocess.so"
# This deliberately lacks the offline process entry. The separate test JVM must fail closed.
g++ -std=c++17 -Wall -Wextra -Werror -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$sources/workspace_file_access.cpp" -o "$native_dir/unavailable/libpocketprocess.so"
# Compile the changed production JNI sources with the declared Android ABI too.
# Host behavior tests cannot compile the Android-specific branches or bionic headers.
: "${ANDROID_HOME:?The declared Android SDK and NDK are required}"
android_compile_status=0
if cmake -S "$sources" -B "$native_dir/android-arm64" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Debug; then
  cmake --build "$native_dir/android-arm64" --parallel 1 || android_compile_status=$?
else
  android_compile_status=$?
fi
bash display-helper/test/run.sh
./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain :app:buildDisplayHelper
gradle_args=(--no-daemon --no-parallel --max-workers=1 --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process -PmikaFocusedTests=true -x buildAndroidRuntime -x buildDisplayHelper \
  :app:testDebugUnitTest)
selectors=()
while IFS= read -r class; do
  [[ -z "$class" || "$class" == \#* ]] && continue
  selectors+=(--tests "$class")
done < tools/mika-focused-tests.txt
main_status=0
LD_LIBRARY_PATH="$native_dir/current${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  ./gradlew "${gradle_args[@]}" "${selectors[@]}" || main_status=$?
# A compilation failure has no test evidence to preserve or repeat in another JVM.
if ! compgen -G 'app/build/test-results/testDebugUnitTest/TEST-*.xml' >/dev/null; then
  if [[ "$main_status" != 0 ]]; then exit "$main_status"; fi
  printf '%s\n' 'Focused tests produced no XML evidence.' >&2
  exit 1
fi
main_evidence_status=0
python3 tools/check-mika-test-evidence.py --phase main \
  --results-dir app/build/test-results/testDebugUnitTest \
  --output app/build/test-results/testDebugUnitTest/mika-evidence-main.json || main_evidence_status=$?
cp -R app/build/reports/tests/testDebugUnitTest "$native_dir/main-reports"
cp -R app/build/test-results/testDebugUnitTest "$native_dir/main-results"
unavailable_status=0
LD_LIBRARY_PATH="$native_dir/unavailable${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  ./gradlew "${gradle_args[@]}" --tests dev.pocketengine.features.local.runtime.NativeOfflineUnavailableTest || unavailable_status=$?
negative_evidence_status=0
python3 tools/check-mika-test-evidence.py --phase native-unavailable \
  --results-dir app/build/test-results/testDebugUnitTest \
  --output app/build/test-results/testDebugUnitTest/mika-evidence-native-unavailable.json || negative_evidence_status=$?
mkdir -p app/build/reports/tests/testDebugUnitTest/main-capabilities app/build/test-results/testDebugUnitTest/main-capabilities
cp -R "$native_dir/main-reports/." app/build/reports/tests/testDebugUnitTest/main-capabilities/
cp -R "$native_dir/main-results/." app/build/test-results/testDebugUnitTest/main-capabilities/
if [[ "$main_status" != 0 ]]; then exit "$main_status"; fi
if [[ "$main_evidence_status" != 0 ]]; then exit "$main_evidence_status"; fi
if [[ "$python_status" != 0 ]]; then exit "$python_status"; fi
if [[ "$node_status" != 0 ]]; then exit "$node_status"; fi
if [[ "$android_compile_status" != 0 ]]; then exit "$android_compile_status"; fi
if [[ "$negative_evidence_status" != 0 ]]; then exit "$negative_evidence_status"; fi
exit "$unavailable_status"
