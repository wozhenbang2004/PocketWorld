#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
gradle_bin="${POCKETENGINE_GRADLE:-$project_root/gradlew}"
if [[ ! -x "$gradle_bin" ]]; then gradle_bin="$project_root/gradlew"; fi
if [[ ! -x "$gradle_bin" ]]; then gradle_bin="$(command -v gradle || true)"; fi
if [[ ! -x "$gradle_bin" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
  echo 'Set POCKETENGINE_GRADLE and ANDROID_HOME to Gradle 8.9 and Android SDK 35.' >&2
  exit 2
fi
if [[ $# -eq 0 ]]; then set -- assembleDebug; fi
gradle_args=(--no-daemon --console=plain -p "$project_root")
if [[ "${POCKETENGINE_OFFLINE:-1}" == 1 ]]; then gradle_args+=(--offline); fi
exec "$gradle_bin" "${gradle_args[@]}" "$@"
