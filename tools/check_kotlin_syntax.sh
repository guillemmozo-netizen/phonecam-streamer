#!/usr/bin/env bash
# Parse every Kotlin source in the Android module and report syntax errors.
#
# Why this exists: this environment cannot reach dl.google.com, so it has no
# Android SDK and `./gradlew` cannot run. For a long stretch that meant the
# only feedback on android.* code was a CI build minutes long, and a misplaced
# bracket — a mistake that costs two seconds to see and two seconds to fix —
# cost a full round trip to a GitHub runner instead.
#
# Resolution needs the SDK. *Parsing* does not. kotlinc will emit a torrent of
# "unresolved reference 'android'" for these files and that is expected and
# ignored here; what it also emits, before it ever gets to resolution, is every
# syntax error. Those are the ones this script keeps.
#
# So: a clean run does NOT mean the module compiles. It means nothing in it is
# malformed. That is a narrow claim, deliberately — see tools/run_kotlin_tests.sh
# for the Android-free code that genuinely does compile and run here, and the
# Android build workflow for the real compile.
#
#   tools/check_kotlin_syntax.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_MAIN="$REPO_ROOT/android-app/app/src/main/java"
SRC_TEST="$REPO_ROOT/android-app/app/src/test/java"

KOTLINC="${KOTLIN_HOME:-/opt/kotlin-jvm/kotlinc}/bin/kotlinc"

if [ ! -x "$KOTLINC" ]; then
    echo "missing: $KOTLINC" >&2
    echo "set KOTLIN_HOME to a kotlinc installation" >&2
    exit 2
fi

sources=()
while IFS= read -r file; do
    sources+=("$file")
done < <(find "$SRC_MAIN" "$SRC_TEST" -name '*.kt' 2>/dev/null | sort)

if [ ${#sources[@]} -eq 0 ]; then
    echo "no Kotlin sources found under $SRC_MAIN" >&2
    exit 2
fi

output_dir="$(mktemp -d)"
trap 'rm -rf "$output_dir"' EXIT

# kotlinc exits non-zero because of the unresolved references; its exit code
# carries no information here, so the diagnostics are what get inspected.
diagnostics="$("$KOTLINC" "${sources[@]}" -d "$output_dir" 2>&1)"

# Parse-phase diagnostics. "cannot infer type" is deliberately NOT in this list:
# it is what resolution says about a lambda whose receiver it could not resolve,
# which happens constantly here for reasons that have nothing to do with syntax.
syntax_errors="$(printf '%s\n' "$diagnostics" \
    | grep -E "error: (syntax error|expecting|unexpected token)" || true)"

if [ -n "$syntax_errors" ]; then
    echo "Kotlin syntax errors:" >&2
    printf '%s\n' "$syntax_errors" >&2
    exit 1
fi

echo "parsed ${#sources[@]} Kotlin files, no syntax errors"
