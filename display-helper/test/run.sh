#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
helper_java="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
helper_tests="$(mktemp -d)"
trap 'rm -rf -- "$helper_tests"' EXIT
"$helper_java/bin/javac" --release 17 -d "$helper_tests" \
  "$project_root/app/src/main/java/dev/pocketengine/phone/display/DisplayWire.java" \
  "$project_root/display-helper/src/dev/pocketengine/displayhelper/LeasePolicy.java" \
  "$project_root/display-helper/src/dev/pocketengine/displayhelper/PrimaryAppGuard.java" \
  "$project_root/display-helper/test/DisplayProtocolTest.java"
"$helper_java/bin/java" -cp "$helper_tests" dev.pocketengine.displayhelper.DisplayProtocolTest
