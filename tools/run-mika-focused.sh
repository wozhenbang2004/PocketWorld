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
sources=app/src/main/java/dev/pocketengine/features/local/runtime/native
g++ -std=c++17 -Wall -Wextra -Werror -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$sources/process_supervisor.cpp" "$sources/pty_supervisor.cpp" "$sources/workspace_file_access.cpp" \
  -o "$native_dir/current/libpocketprocess.so"
# This deliberately lacks the offline process entry. The separate test JVM must fail closed.
g++ -std=c++17 -Wall -Wextra -Werror -fPIC -shared \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$sources/workspace_file_access.cpp" -o "$native_dir/unavailable/libpocketprocess.so"
bash display-helper/test/run.sh
./gradlew --no-daemon --no-parallel --max-workers=1 --console=plain :app:buildDisplayHelper
gradle_args=(--no-daemon --no-parallel --max-workers=1 --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process -x buildAndroidRuntime -x buildDisplayHelper \
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
cp -R app/build/reports/tests/testDebugUnitTest "$native_dir/main-reports"
cp -R app/build/test-results/testDebugUnitTest "$native_dir/main-results"
unavailable_status=0
LD_LIBRARY_PATH="$native_dir/unavailable${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  ./gradlew "${gradle_args[@]}" --tests dev.pocketengine.features.local.runtime.NativeOfflineUnavailableTest || unavailable_status=$?
mkdir -p app/build/reports/tests/testDebugUnitTest/main-capabilities app/build/test-results/testDebugUnitTest/main-capabilities
cp -R "$native_dir/main-reports/." app/build/reports/tests/testDebugUnitTest/main-capabilities/
cp -R "$native_dir/main-results/." app/build/test-results/testDebugUnitTest/main-capabilities/
if [[ "$main_status" != 0 ]]; then exit "$main_status"; fi
if [[ "$python_status" != 0 ]]; then exit "$python_status"; fi
if [[ "$node_status" != 0 ]]; then exit "$node_status"; fi
exit "$unavailable_status"
