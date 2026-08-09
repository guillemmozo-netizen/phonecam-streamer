"""No Kotlin source in the Android module may be malformed.

This is the cheapest possible check on code that cannot otherwise be compiled
here, and it exists because it was skipped once: a constructor whose closing
parenthesis landed in the wrong place shipped to CI and cost a two-minute
Gradle run to discover something kotlinc reports in eleven seconds.

It does *not* claim the module compiles - resolution needs an Android SDK this
environment cannot reach. It claims only that every file parses.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
CHECKER = REPO_ROOT / "tools" / "check_kotlin_syntax.sh"
KOTLINC = Path(os.environ.get("KOTLIN_HOME", "/opt/kotlin-jvm/kotlinc")) / "bin" / "kotlinc"


@pytest.mark.skipif(not KOTLINC.is_file(), reason="kotlinc not installed")
def test_every_kotlin_source_parses():
    result = subprocess.run(
        [str(CHECKER)], cwd=REPO_ROOT, capture_output=True, text=True, timeout=600
    )
    assert result.returncode == 0, (
        "Kotlin sources have syntax errors; the Android build cannot succeed.\n"
        f"{result.stdout}\n{result.stderr}"
    )


def test_the_checker_is_executable():
    """A script committed without the executable bit is a check that silently
    never runs - the same defect that was already found in android-app/gradlew."""
    listing = subprocess.run(
        ["git", "ls-files", "-s", "tools/check_kotlin_syntax.sh"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    assert listing, "tools/check_kotlin_syntax.sh is not tracked by git"
    assert listing[0] == "100755", f"committed as mode {listing[0]}, not executable"


@pytest.mark.skipif(not KOTLINC.is_file(), reason="kotlinc not installed")
def test_the_checker_actually_detects_a_syntax_error(tmp_path):
    """A checker that always passes is worse than no checker.

    Rather than trust the filter, this plants a real syntax error in a scratch
    copy of the tree and requires the script to fail on it.
    """
    scratch = tmp_path / "repo"
    source_root = REPO_ROOT / "android-app" / "app" / "src"
    shutil.copytree(source_root, scratch / "android-app" / "app" / "src")
    shutil.copy(CHECKER, scratch / "check.sh")
    (scratch / "check.sh").chmod(0o755)
    # The script derives the tree from its own location, hence the layout above.
    (scratch / "tools").mkdir()
    shutil.move(str(scratch / "check.sh"), scratch / "tools" / "check_kotlin_syntax.sh")

    broken = scratch / "android-app" / "app" / "src" / "main" / "java" / "broken.kt"
    broken.write_text("class Broken( { val x = 1 }\n", encoding="utf-8")

    result = subprocess.run(
        [str(scratch / "tools" / "check_kotlin_syntax.sh")],
        capture_output=True, text=True, timeout=600,
    )
    assert result.returncode == 1, (
        "the checker passed a file that does not parse:\n"
        f"{result.stdout}\n{result.stderr}"
    )
    assert "broken.kt" in result.stderr


@pytest.mark.skipif(not KOTLINC.is_file(), reason="kotlinc not installed")
def test_unresolved_android_references_are_not_reported_as_syntax_errors(tmp_path):
    """The other way to be useless: fail on everything.

    Every file importing android.* produces unresolved references here. If those
    counted, the check would be permanently red and would be ignored, which is
    the same as not having it.
    """
    scratch = tmp_path / "repo"
    (scratch / "android-app" / "app" / "src" / "main" / "java").mkdir(parents=True)
    (scratch / "tools").mkdir(parents=True)
    shutil.copy(CHECKER, scratch / "tools" / "check_kotlin_syntax.sh")
    (scratch / "tools" / "check_kotlin_syntax.sh").chmod(0o755)

    android_only = scratch / "android-app" / "app" / "src" / "main" / "java" / "Uses.kt"
    android_only.write_text(
        "import android.util.Log\n"
        "class Uses { fun go(v: android.view.View) { Log.d(\"t\", v.toString()) } }\n",
        encoding="utf-8",
    )

    result = subprocess.run(
        [str(scratch / "tools" / "check_kotlin_syntax.sh")],
        capture_output=True, text=True, timeout=600,
    )
    assert result.returncode == 0, (
        "unresolved android.* references were treated as syntax errors:\n"
        f"{result.stdout}\n{result.stderr}"
    )
