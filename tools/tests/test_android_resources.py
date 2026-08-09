"""Static checks over the Android module.

These exist because the Android SDK cannot be downloaded in the environment
this project's checks run in (`dl.google.com` is blocked by network policy), so
`./gradlew assemble` is not available to catch the mistakes a package rename
makes: a source file whose `package` no longer matches its folder, a manifest
pointing at a class that moved, a `@style/` reference left behind by a renamed
theme, a translation that lost a key.

None of this replaces a real build. It catches the specific class of breakage
that a rename introduces, which a build would otherwise be the first to notice.
"""

from __future__ import annotations

import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
APP = REPO_ROOT / "android-app" / "app"
SRC = APP / "src"
RES = SRC / "main" / "res"
MANIFEST = SRC / "main" / "AndroidManifest.xml"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

NAMESPACE = "com.framecast.streamer"

# Reference kinds that live in res/ and can be resolved from the files there.
# @id is excluded: ids are declared inline with @+id in layouts, which is a
# different resolution rule and not something a rename breaks.
# Style names carry dots (Theme.FrameCast, Widget.FrameCast.Switch), so the
# name part cannot be plain \w+.
RESOURCE_REFERENCE = re.compile(r"@(string|drawable|mipmap|color|style|anim|dimen|array)/([\w.]+)")


# Styles inherited from Material Components / AppCompat live in those AARs, not
# in this module's res/, so there is nothing local to resolve them against.
LIBRARY_STYLE = re.compile(r"\.(Material3|MaterialComponents|Material|AppCompat|Design)\.")


def tracked_files() -> list[Path]:
    """Only files git knows about — build output and caches under the repo
    (.pytest_cache in particular, which records node ids containing the
    checkout's own directory name) are not part of what ships."""
    listing = subprocess.run(
        ["git", "ls-files", "-z"], cwd=REPO_ROOT,
        capture_output=True, text=True, check=True,
    ).stdout
    return [REPO_ROOT / name for name in listing.split("\0") if name]


def kotlin_sources() -> list[Path]:
    return sorted(SRC.rglob("*.kt"))


def _defined_resources() -> dict[str, set[str]]:
    """Every resource this module defines, by kind.

    Two ways a resource comes into being: a file in res/<kind>/ (drawables,
    layouts, anims), or a <string>/<color>/<style> element inside a values
    file. Both count.
    """
    defined: dict[str, set[str]] = {}

    for directory in RES.iterdir():
        if not directory.is_dir():
            continue
        kind = directory.name.split("-")[0]
        if kind == "values":
            for values_file in directory.glob("*.xml"):
                for element in ET.parse(values_file).getroot():
                    name = element.get("name")
                    if not name:
                        continue
                    # <item name="x" type="string"/> declares into another kind.
                    element_kind = element.get("type") or element.tag
                    defined.setdefault(element_kind, set()).add(name)
        else:
            for resource_file in directory.iterdir():
                if resource_file.is_file():
                    defined.setdefault(kind, set()).add(resource_file.stem)

    return defined


def test_every_kotlin_package_matches_its_directory():
    """A file moved without its `package` line updated compiles to the wrong
    place — or not at all, once something imports it by its new path."""
    mismatched = []
    for path in kotlin_sources():
        declared = re.search(r"^package\s+([\w.]+)", path.read_text(), re.M)
        assert declared, f"{path.relative_to(REPO_ROOT)} has no package declaration"
        # src/<variant>/java/<package as dirs>/File.kt
        parts = path.relative_to(SRC).parts
        expected = ".".join(parts[2:-1])
        if declared.group(1) != expected:
            mismatched.append(
                f"{path.relative_to(REPO_ROOT)}: declares {declared.group(1)}, "
                f"directory says {expected}"
            )
    assert not mismatched, "\n".join(mismatched)


def test_namespace_and_application_id_agree_with_the_sources():
    build_file = (APP / "build.gradle.kts").read_text()
    assert f'namespace = "{NAMESPACE}"' in build_file
    assert f'applicationId = "{NAMESPACE}"' in build_file
    # The view-binding classes AGP generates land in <namespace>.databinding,
    # so a namespace that disagreed with the sources would break every
    # binding import in MainActivity/SettingsActivity.
    assert 'viewBinding = true' in build_file


def test_first_party_imports_resolve_to_a_real_file():
    """`import com.framecast.streamer.foo.Bar` has to have somewhere to come
    from — either a source file or a generated view-binding class."""
    source_dirs = [SRC / variant / "java" for variant in ("main", "debug", "test")]
    layouts = {p.stem for p in (RES / "layout").glob("*.xml")}

    def binding_class_names() -> set[str]:
        # activity_main.xml -> ActivityMainBinding
        return {
            "".join(part.capitalize() for part in name.split("_")) + "Binding"
            for name in layouts
        }

    bindings = binding_class_names()
    unresolved = []
    for path in kotlin_sources():
        for imported in re.findall(rf"^import\s+({re.escape(NAMESPACE)}[\w.]*)", path.read_text(), re.M):
            symbol = imported.rsplit(".", 1)[-1]
            if imported.startswith(f"{NAMESPACE}.databinding."):
                if symbol not in bindings:
                    unresolved.append(f"{path.relative_to(REPO_ROOT)}: {imported} (no such layout)")
                continue
            relative = Path(*imported.split("."))
            # A symbol is either its own file, or declared inside the file for
            # the package member above it (top-level functions, enums, etc).
            candidates = [
                base / relative.with_suffix(".kt") for base in source_dirs
            ] + [
                base / relative.parent for base in source_dirs
            ]
            if not any(c.exists() for c in candidates):
                if not any((base / relative.parent).is_dir() for base in source_dirs):
                    unresolved.append(f"{path.relative_to(REPO_ROOT)}: {imported}")
    assert not unresolved, "unresolvable first-party imports:\n" + "\n".join(unresolved)


def test_manifest_classes_exist():
    """`android:name=".FrameCastApp"` resolves against the namespace; if the
    class was renamed and the manifest wasn't, the app dies at launch."""
    manifest = ET.parse(MANIFEST).getroot()
    missing = []
    for element in manifest.iter():
        name = element.get(f"{ANDROID_NS}name")
        if not name or not name.startswith("."):
            continue
        if element.tag not in ("application", "activity", "service", "receiver", "provider"):
            continue
        relative = Path(*(NAMESPACE + name).split("."))
        if not (SRC / "main" / "java" / relative).with_suffix(".kt").exists():
            missing.append(f"<{element.tag} android:name=\"{name}\">")
    assert not missing, f"manifest points at classes that don't exist: {missing}"


def test_every_resource_reference_resolves():
    """A renamed theme or drawable leaves dangling @style//@drawable
    references, which fail the resource-linking step of a real build."""
    defined = _defined_resources()
    dangling = []

    files = list(RES.rglob("*.xml")) + [MANIFEST]
    for path in files:
        text = path.read_text(encoding="utf-8")
        for kind, name in RESOURCE_REFERENCE.findall(text):
            if kind == "style" and LIBRARY_STYLE.search(name):
                continue
            if name not in defined.get(kind, set()):
                dangling.append(f"{path.relative_to(REPO_ROOT)}: @{kind}/{name}")

    assert not dangling, "references to resources that don't exist:\n" + "\n".join(sorted(set(dangling)))


def test_every_locale_defines_the_same_strings_as_the_default():
    """A missing key falls back to the default locale silently, so an
    incomplete translation shows English inside an otherwise translated screen
    rather than failing anywhere."""
    def names(path: Path, translatable_only: bool = False) -> set[str]:
        return {
            e.get("name") for e in ET.parse(path).getroot()
            if e.tag == "string" and e.get("name")
            # translatable="false" is the toolchain's own way of saying "this
            # one stays English on purpose" — lint honours it, so this does too.
            and not (translatable_only and e.get("translatable") == "false")
        }

    default = names(RES / "values" / "strings.xml", translatable_only=True)
    assert default, "values/strings.xml defines no strings"

    incomplete = {}
    for directory in sorted(RES.glob("values-*")):
        strings = directory / "strings.xml"
        if not strings.exists():
            continue
        missing = default - names(strings)
        if missing:
            incomplete[directory.name] = sorted(missing)

    assert not incomplete, f"locales missing strings: {incomplete}"


def test_the_launcher_icon_is_wired_up():
    manifest = (MANIFEST).read_text()
    assert 'android:icon="@mipmap/ic_launcher"' in manifest
    assert 'android:roundIcon="@mipmap/ic_launcher_round"' in manifest

    for name in ("ic_launcher", "ic_launcher_round"):
        adaptive = ET.parse(RES / "mipmap-anydpi-v26" / f"{name}.xml").getroot()
        layers = {child.tag for child in adaptive}
        assert layers == {"background", "foreground", "monochrome"}, (
            f"{name}.xml has layers {layers} — themed icons (Android 13+) need "
            "a <monochrome> layer"
        )


def test_the_launcher_mark_stays_inside_the_adaptive_icon_safe_zone():
    """Adaptive icons are drawn on a 108dp canvas of which launchers may mask
    away everything outside the central 72dp circle. Art outside that radius
    gets clipped on the strictest masks — which is a rendering bug you only
    see on someone else's phone."""
    foreground = ET.parse(RES / "drawable" / "ic_launcher_foreground.xml").getroot()
    centre, safe_radius = 54.0, 36.0

    worst = 0.0
    for path in foreground.findall("path"):
        stroke = float(path.get(f"{ANDROID_NS}strokeWidth", "0"))
        coordinates = [float(n) for n in re.findall(r"-?\d+\.?\d*", path.get(f"{ANDROID_NS}pathData"))]
        # pathData alternates x,y for every command this icon uses (L/C/M).
        for x, y in zip(coordinates[::2], coordinates[1::2]):
            distance = ((x - centre) ** 2 + (y - centre) ** 2) ** 0.5 + stroke / 2
            worst = max(worst, distance)

    assert worst <= safe_radius, (
        f"launcher mark reaches {worst:.1f} from centre, past the {safe_radius} "
        "safe radius — a circular mask would clip it"
    )


def test_nothing_is_still_called_phonecam():
    """The rename is only done when the old name is gone everywhere, including
    protocol constants and file names."""
    # The release checklist describes the rename, and this test names the old
    # string to look for it — both mention it on purpose.
    allowed = {"docs/RELEASE_CHECKLIST.md", "tools/tests/test_android_resources.py"}

    offenders = []
    for path in tracked_files():
        if not path.is_file():
            continue
        if str(path.relative_to(REPO_ROOT)) in allowed:
            continue
        if path.suffix in {".png", ".ico", ".jar", ".zip", ".svg"}:
            continue
        if "phonecam" in path.name.lower():
            offenders.append(str(path.relative_to(REPO_ROOT)))
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if "phonecam" in text.lower():
            offenders.append(str(path.relative_to(REPO_ROOT)))
    assert not offenders, f"still mention PhoneCam: {sorted(set(offenders))}"
