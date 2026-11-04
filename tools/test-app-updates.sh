#!/usr/bin/env bash
# Focused offline JVM tests. No APK build, CI invocation, service access or installation.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
ROOT=$(mktemp -d)
trap 'rm -rf -- "$ROOT"' EXIT
if [[ -n "${PW_UPDATE_TEST_CLASSPATH:-}" ]]; then
  CP=$PW_UPDATE_TEST_CLASSPATH
else
  CACHE=${GRADLE_RO_DEP_CACHE:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches}/modules-2/files-2.1
  CP=$(find "$CACHE" -type f \( -name 'json-20240303.jar' -o -name 'junit-4.13.2.jar' -o -name 'hamcrest-core-1.3.jar' -o -name 'okhttp-4.12.1.jar' -o -name 'okio-jvm-3.6.0.jar' -o -name 'kotlin-stdlib-2.1.20.jar' \) | paste -sd:)
fi
"$JAVAC" -encoding UTF-8 -cp "$CP" -d "$ROOT" \
  app/src/main/java/dev/pocketengine/features/updates/{UpdateManifest,UpdateDownloads}.java \
  app/src/test/java/dev/pocketengine/features/updates/{UpdateManifestTest,UpdateDownloadsTest}.java
"$JAVA" -cp "$ROOT:$CP" org.junit.runner.JUnitCore \
  dev.pocketengine.features.updates.UpdateManifestTest dev.pocketengine.features.updates.UpdateDownloadsTest
