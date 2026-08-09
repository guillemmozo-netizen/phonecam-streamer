"""The APK workflow is the only thing that compiles the Android module.

Nothing in this environment can run Gradle, so these tests cannot tell you the
build succeeds. What they can tell you is that the workflow is not wrong in the
ways a workflow is usually wrong: a working directory that doesn't exist, a
`./gradlew` that isn't executable, an artifact path that no build ever writes
to. Each of those fails on GitHub's runner minutes into a build, which is a
slow and expensive way to find a typo.
"""

from __future__ import annotations

import stat
import subprocess
from pathlib import Path

import pytest

yaml = pytest.importorskip("yaml")

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "android-build.yml"


@pytest.fixture(scope="module")
def workflow() -> dict:
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))


@pytest.fixture(scope="module")
def steps(workflow: dict) -> list[dict]:
    return workflow["jobs"]["build"]["steps"]


def test_the_workflow_is_valid_yaml(workflow):
    """A file GitHub cannot parse is a workflow that silently never runs."""
    assert isinstance(workflow, dict)
    # `on:` is YAML 1.1's boolean True, which is why it is read this way.
    assert workflow[True] is not None, "no trigger; the workflow would never fire"


def test_the_working_directory_exists(workflow):
    working_directory = workflow["jobs"]["build"]["defaults"]["run"]["working-directory"]
    assert (REPO_ROOT / working_directory / "gradlew").is_file(), (
        f"{working_directory}/gradlew is missing; every run step would fail"
    )


def test_gradlew_is_executable_in_git():
    """`./gradlew` on a Linux runner needs the executable bit *in the index*.

    A fresh clone takes its file modes from git, not from whatever the file
    happened to be locally when it was committed. Mode 100644 here means every
    run step dies with 'Permission denied' on a runner while working fine on
    the Windows machine it was authored on.
    """
    listing = subprocess.run(
        ["git", "ls-files", "-s", "android-app/gradlew"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    assert listing, "android-app/gradlew is not tracked by git"
    assert listing[0] == "100755", (
        f"gradlew is committed as mode {listing[0]}; a runner cannot execute it. "
        "Fix with: git update-index --chmod=+x android-app/gradlew"
    )

    on_disk = (REPO_ROOT / "android-app" / "gradlew").stat().st_mode
    assert on_disk & stat.S_IXUSR, "gradlew is not executable on disk either"


def test_the_wrapper_jar_is_committed():
    """Without it `./gradlew` downloads nothing and fails immediately."""
    jar = REPO_ROOT / "android-app" / "gradle" / "wrapper" / "gradle-wrapper.jar"
    assert jar.is_file() and jar.stat().st_size > 0


def test_the_apk_artifact_path_matches_what_gradle_writes(steps):
    """The upload path is relative to the workspace, not the working directory.

    `defaults.run.working-directory` applies to `run:` steps only — an action's
    `with:` inputs are resolved from the repository root. Dropping the
    `android-app/` prefix here yields a green build that uploads nothing, which
    is the failure mode this whole workflow exists to avoid.
    """
    upload = next(s for s in steps if s.get("name") == "Upload APK")
    path = upload["with"]["path"]

    assert path.startswith("android-app/"), (
        f"artifact path {path!r} is not workspace-relative; nothing would be found"
    )
    # Where AGP puts a debug APK; the variant directory is part of the contract.
    assert path == "android-app/app/build/outputs/apk/debug/*.apk", path
    assert upload["with"]["if-no-files-found"] == "error", (
        "a missing APK must fail the run, not pass quietly"
    )


def test_a_missing_apk_cannot_pass_silently(steps):
    """The one outcome worse than a red build: a green one with no APK."""
    upload = next(s for s in steps if s.get("name") == "Upload APK")
    assert upload.get("if") == "success()"


def test_the_build_actually_assembles_and_tests(steps):
    commands = " ".join(s["run"] for s in steps if "run" in s)
    assert "assembleDebug" in commands, "no APK would be produced"
    assert "testDebugUnitTest" in commands, "the unit tests would never run"


def test_it_builds_debug_rather_than_release(steps):
    """Release needs the keystore, which is deliberately not in the repo — a
    release build here would produce an unsigned APK no phone will install."""
    commands = " ".join(s["run"] for s in steps if "run" in s)
    assert "assembleRelease" not in commands


def test_the_jdk_matches_what_the_module_compiles_against(steps):
    """Gradle runs the toolchain on the JVM that launched it: a runner default
    newer or older than the module's target is a class-file version error."""
    setup = next(s for s in steps if str(s.get("uses", "")).startswith("actions/setup-java"))
    java_version = str(setup["with"]["java-version"])

    build_file = (REPO_ROOT / "android-app" / "app" / "build.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert "JavaVersion.VERSION_17" in build_file, (
        "the module no longer targets Java 17; this workflow needs updating too"
    )
    assert java_version == "17", f"workflow sets up JDK {java_version}, module targets 17"


def test_the_workflow_only_needs_read_access():
    """It publishes an artifact, nothing more. A workflow that runs on every
    push to every branch should not be able to write to the repository."""
    workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    assert workflow["permissions"] == {"contents": "read"}
