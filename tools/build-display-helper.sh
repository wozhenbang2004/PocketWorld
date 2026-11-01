#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
helper_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
helper_java="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
helper_build="$project_root/display-helper/build"
helper_output="${1:-$helper_build/pocketengine-display-helper.jar}"
mkdir -p "$helper_build/classes" "$helper_build/dex" "$(dirname -- "$helper_output")"
mapfile -t helper_sources < <(find "$project_root/display-helper/src" -name '*.java' -type f | sort)
"$helper_java/bin/javac" --release 17 -cp "$helper_sdk/platforms/android-35/android.jar" -d "$helper_build/classes" "${helper_sources[@]}" "$project_root/app/src/main/java/dev/pocketengine/phone/display/DisplayWire.java"
"$helper_java/bin/jar" --create --file "$helper_build/helper-classes.jar" -C "$helper_build/classes" .
JAVA_HOME="$helper_java" "$helper_sdk/build-tools/35.0.0/d8" --min-api 34 --lib "$helper_sdk/platforms/android-35/android.jar" --output "$helper_build/dex" "$helper_build/helper-classes.jar"
"$helper_java/bin/jar" --create --file "$helper_output" -C "$helper_build/dex" classes.dex
printf '%s\n' "$helper_output"
