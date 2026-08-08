"""What the PC download has to satisfy before it's fit to hand to a user.

These are about the *package*, not the code inside it: that the zip contains
everything the installer will look for, that everything it contains can
actually run from where it lands, that its dependency list is complete, and
that nothing local to a developer's machine got swept in.
"""

from __future__ import annotations

import ast
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build_pc_zip  # noqa: E402

REPO_ROOT = build_pc_zip.REPO_ROOT
TOP = build_pc_zip.TOP_LEVEL

# Import name -> the distribution that provides it. Import names and PyPI names
# disagree often enough (cv2, websocket) that mapping them by hand is the only
# honest way to check requirements.txt covers what the code imports.
DISTRIBUTION_BY_IMPORT = {
    "av": "av",
    "cv2": "opencv-python-headless",
    "numpy": "numpy",
    "pyvirtualcam": "pyvirtualcam",
    "websocket": "websocket-client",
}

FIRST_PARTY = {"pc_receiver", "reward_engine"}


@pytest.fixture(scope="module")
def zip_path(tmp_path_factory) -> Path:
    return build_pc_zip.build(tmp_path_factory.mktemp("build") / build_pc_zip.ZIP_NAME)


@pytest.fixture(scope="module")
def shipped(zip_path: Path) -> list[str]:
    """Paths inside the zip, relative to its single top-level folder.

    Read from the archive rather than from an extraction: importing the
    modules (which one of these tests does) leaves __pycache__ behind, and a
    test that answers "what ships?" must not be able to see it."""
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
    return [n[len(TOP) + 1:] for n in names if n.startswith(f"{TOP}/")]


@pytest.fixture(scope="module")
def extracted(zip_path: Path, tmp_path_factory) -> Path:
    """The zip unpacked exactly as a user would get it."""
    dest = tmp_path_factory.mktemp("extracted")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(dest)
    return dest / TOP


def _shipped_python_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.py"))


def test_installer_required_files_all_ship(extracted: Path):
    """Install_FrameCast.vbs refuses to run if any file in its requiredFiles
    array is missing. Every one of those has to be in the zip, or the installer
    rejects its own download."""
    vbs = (REPO_ROOT / "Install_FrameCast.vbs").read_text(encoding="utf-8", errors="replace")
    block = re.search(r"requiredFiles = Array\((.*?)\)\s*\n", vbs, re.S)
    assert block, "could not find the requiredFiles array in Install_FrameCast.vbs"
    required = re.findall(r'"([^"]+)"', block.group(1))
    assert required, "requiredFiles array parsed as empty"

    missing = [r for r in required if not (extracted / r.replace("\\", "/")).is_file()]
    assert not missing, f"installer checks for files the zip doesn't ship: {missing}"


def test_zip_ships_nothing_the_installer_forgot_to_check(shipped: list[str]):
    """The other direction: a module in the zip that the installer never
    verifies can go missing in a partial extraction without anyone noticing
    until a service silently fails to start."""
    vbs = (REPO_ROOT / "Install_FrameCast.vbs").read_text(encoding="utf-8", errors="replace")
    block = re.search(r"requiredFiles = Array\((.*?)\)\s*\n", vbs, re.S)
    required = {r.replace("\\", "/") for r in re.findall(r'"([^"]+)"', block.group(1))}

    # Top-level files (installer, README, icons) are covered by their own tests
    # and by the installer simply not being able to run without them.
    in_packages = {name for name in shipped if "/" in name}
    unchecked = sorted(in_packages - required)
    assert not unchecked, (
        "these ship but Install_FrameCast.vbs never checks for them — add them "
        f"to its requiredFiles array: {unchecked}"
    )


def test_every_third_party_import_is_in_requirements(extracted: Path):
    """The installer pip-installs requirements.txt and nothing else, so any
    import outside the stdlib that isn't listed there is a module that will
    ImportError on a clean PC."""
    requirements = (extracted / "pc_receiver" / "requirements.txt").read_text()
    listed = {
        line.split("#")[0].strip().lower()
        for line in requirements.splitlines()
        if line.strip() and not line.strip().startswith("#")
    }

    imported: dict[str, str] = {}
    for path in _shipped_python_files(extracted):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom):
                # level > 0 is a relative import — first-party by definition.
                names = [node.module] if node.module and node.level == 0 else []
            else:
                continue
            for name in names:
                imported.setdefault(name.split(".")[0], str(path.relative_to(extracted)))

    unsatisfied = {}
    for module, where in sorted(imported.items()):
        if module in FIRST_PARTY or module in sys.stdlib_module_names:
            continue
        distribution = DISTRIBUTION_BY_IMPORT.get(module)
        if distribution is None:
            unsatisfied[module] = f"{where} (unknown distribution)"
        elif distribution.lower() not in listed:
            unsatisfied[module] = f"{where} (needs {distribution!r} in requirements.txt)"

    assert not unsatisfied, f"imports the installer would not install: {unsatisfied}"


def test_every_shipped_module_imports_from_the_extracted_layout(extracted: Path):
    """`from pc_receiver.protocol import ...` only resolves when the extraction
    root is on sys.path — which is the whole reason the zip nests both packages
    under one folder. Import each module in a subprocess rooted there, which is
    what FrameCast_Service.vbs and FrameCast_PC.bat both set up."""
    modules = []
    for path in _shipped_python_files(extracted):
        relative = path.relative_to(extracted)
        if relative.name == "__init__.py":
            continue
        modules.append(".".join(relative.with_suffix("").parts))

    assert modules, "no modules found in the extracted zip"

    program = (
        "import importlib, sys\n"
        "failures = []\n"
        f"for name in {modules!r}:\n"
        "    try:\n"
        "        importlib.import_module(name)\n"
        "    except Exception as e:\n"
        "        failures.append(f'{name}: {type(e).__name__}: {e}')\n"
        "print('\\n'.join(failures))\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", program],
        cwd=extracted, capture_output=True, text=True, timeout=180,
    )
    assert result.returncode == 0, result.stderr
    assert not result.stdout.strip(), f"modules that don't import from the zip:\n{result.stdout}"


def test_no_developer_or_generated_files_leak(shipped: list[str]):
    """A build run on a machine that has already installed the PC side must not
    ship that machine's venv, logs, caches or auth token."""
    never_ship = {".control_token", "setup_log.txt", "requirements-dev.txt"}
    never_ship_dirs = {".venv", "__pycache__", "logs", "tests", ".pytest_cache"}

    leaked = [
        name for name in shipped
        if Path(name).name in never_ship
        or never_ship_dirs & set(Path(name).parts)
        or Path(name).suffix in {".pyc", ".log"}
    ]
    assert not leaked, f"these should never ship: {leaked}"


def test_logo_ships_and_is_a_real_image(extracted: Path):
    """The Startup shortcut points at FrameCast.ico, so a zip without it (or
    with a zero-byte placeholder) leaves users a broken-icon shortcut."""
    ico = extracted / "FrameCast.ico"
    png = extracted / "FrameCast.png"
    assert ico.is_file() and png.is_file()

    # Magic numbers rather than Pillow: the check has to work in the plain
    # runtime environment, and pillow is a build-only dependency.
    assert ico.read_bytes()[:4] == b"\x00\x00\x01\x00", "FrameCast.ico is not an ICO file"
    assert png.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", "FrameCast.png is not a PNG file"
    assert ico.stat().st_size > 1024 and png.stat().st_size > 1024


def test_installer_points_the_shortcut_at_the_shipped_icon():
    vbs = (REPO_ROOT / "Install_FrameCast.vbs").read_text(encoding="utf-8", errors="replace")
    assert 'iconPath = rootDir & "\\FrameCast.ico"' in vbs
    assert "shortcut.IconLocation = iconPath" in vbs


def test_zip_build_is_reproducible(tmp_path):
    """Same sources in, same bytes out — so "has the download changed?" is a
    hash comparison rather than a judgement call."""
    first = build_pc_zip.build(tmp_path / "a.zip")
    second = build_pc_zip.build(tmp_path / "b.zip")
    assert first.read_bytes() == second.read_bytes()


def test_everything_lands_under_one_folder():
    """Extracting must not scatter loose files into the user's Downloads."""
    tops = {name.split("/")[0] for _, name in build_pc_zip.collect()}
    assert tops == {TOP}


def test_committed_zip_matches_its_sources(zip_path: Path):
    """dist/FrameCast_PC_Setup.zip is committed so the README's download link
    resolves on GitHub — which only helps if it is the zip these sources
    actually produce. Reproducible builds make that an exact comparison."""
    committed = REPO_ROOT / "dist" / build_pc_zip.ZIP_NAME
    assert committed.is_file(), (
        f"dist/{build_pc_zip.ZIP_NAME} is missing — run: python tools/build_pc_zip.py"
    )
    assert committed.read_bytes() == zip_path.read_bytes(), (
        "the committed PC download is stale — rebuild and commit it:\n"
        "    python tools/build_pc_zip.py"
    )
