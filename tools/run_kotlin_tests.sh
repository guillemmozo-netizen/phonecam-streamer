#!/usr/bin/env bash
# Compile and run the Android module's pure-Kotlin unit tests without Gradle.
#
# Why this exists: `./gradlew testDebugUnitTest` needs AGP, androidx and an
# Android SDK, all of which come from dl.google.com — unreachable from the
# environment this project's checks run in. But several classes here are
# deliberately written free of Android types precisely so their logic can be
# tested on a plain JVM (ConnectionSupervisor says so in its own docs), and
# kotlinc plus junit are both on Maven Central. So those tests can run, and
# not running them because the *other* tests can't is the wrong trade.
#
# Only files that import nothing from android.* are eligible; anything else is
# skipped and listed, so the gap between "tested here" and "needs a real build"
# stays visible rather than implied.
#
# Requires kotlinc and junit; set KOTLIN_HOME/JUNIT_JAR to override discovery.
#
#   tools/run_kotlin_tests.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_MAIN="$REPO_ROOT/android-app/app/src/main/java"
SRC_TEST="$REPO_ROOT/android-app/app/src/test/java"

KOTLINC="${KOTLIN_HOME:-/opt/kotlin-jvm/kotlinc}/bin/kotlinc"
JUNIT_JAR="${JUNIT_JAR:-/opt/kotlin-jvm/junit-4.13.2.jar}"
HAMCREST_JAR="${HAMCREST_JAR:-/opt/kotlin-jvm/hamcrest-core-1.3.jar}"
# org.json ships inside Android at runtime, so StreamProtocol imports it
# without declaring it — app/build.gradle.kts adds the real artifact for unit
# tests for the same reason, and this mirrors that.
JSON_JAR="${JSON_JAR:-/opt/kotlin-jvm/json-20250517.jar}"

for required in "$KOTLINC" "$JUNIT_JAR" "$HAMCREST_JAR" "$JSON_JAR"; do
    if [ ! -e "$required" ]; then
        echo "missing: $required" >&2
        echo "see the header of this script for what it needs" >&2
        exit 2
    fi
done

# A file is JVM-testable when neither it nor anything it needs touches the
# Android framework. Checked by import, which is what actually decides whether
# kotlinc can resolve it without android.jar on the classpath.
is_pure_kotlin() {
    ! grep -qE '^import +(android|androidx)\.' "$1"
}

pure_sources=()
skipped=()
while IFS= read -r -d '' file; do
    if is_pure_kotlin "$file"; then
        pure_sources+=("$file")
    else
        skipped+=("${file#"$REPO_ROOT"/}")
    fi
done < <(find "$SRC_MAIN" "$SRC_TEST" -name '*.kt' -print0 | sort -z)

pure_tests=()
for file in "${pure_sources[@]}"; do
    case "$file" in "$SRC_TEST"/*) pure_tests+=("$file");; esac
done

if [ ${#pure_tests[@]} -eq 0 ]; then
    echo "no JVM-testable test files found" >&2
    exit 1
fi

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "compiling ${#pure_sources[@]} Android-free source file(s)..."
"$KOTLINC" -nowarn -cp "$JUNIT_JAR:$JSON_JAR" -d "$BUILD_DIR/classes" "${pure_sources[@]}" 2>&1 |
    grep -v '^Picked up JAVA_TOOL_OPTIONS' || true

# kotlinc exits 0 even after emitting errors in some versions; the absence of
# output classes is the reliable signal that it did not produce anything.
if [ ! -d "$BUILD_DIR/classes" ]; then
    echo "compilation produced no classes" >&2
    exit 1
fi

test_classes=()
for file in "${pure_tests[@]}"; do
    package="$(grep -m1 '^package ' "$file" | awk '{print $2}')"
    class="$(basename "$file" .kt)"
    test_classes+=("${package:+$package.}$class")
done

echo "running: ${test_classes[*]}"
java -cp "$BUILD_DIR/classes:$JUNIT_JAR:$HAMCREST_JAR:$JSON_JAR:${KOTLIN_HOME:-/opt/kotlin-jvm/kotlinc}/lib/kotlin-stdlib.jar" \
    org.junit.runner.JUnitCore "${test_classes[@]}" 2>&1 |
    grep -v '^Picked up JAVA_TOOL_OPTIONS' || {
        echo "TESTS FAILED" >&2
        exit 1
    }

if [ ${#skipped[@]} -gt 0 ]; then
    echo
    echo "not covered here — these import android.*/androidx.* and need a real build:"
    printf '    %s\n' "${skipped[@]}"
fi
