"""Render the Windows/Play-listing FrameCast assets from the Android launcher icon.

The Android adaptive icon is the single source of truth for the mark: this
script reads the gradient out of `ic_launcher_background.xml` and the paths out
of `ic_launcher_foreground.xml` and re-emits them as SVG, so the .ico on the
user's PC and the icon on their phone cannot drift apart. `android:pathData` is
already SVG path syntax, which is what makes that a transcription rather than a
conversion.

Outputs (committed to the repo, so building the PC zip needs no render deps):

    brand/framecast_icon.svg        master, full-bleed square
    brand/framecast_icon_512.png    Play Store listing icon (512x512, opaque)
    brand/framecast_logo_256.png    rounded, transparent - the human-viewable logo
    brand/framecast.ico             Windows shortcut/Explorer icon, 16..256

Run after changing either launcher drawable:

    python tools/make_brand_assets.py

Requires `cairosvg` and `pillow`, neither of which the app or the receiver
needs at runtime - they are build-time-only, hence not in requirements.txt.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
AAPT_NS = "{http://schemas.android.com/aapt}"

REPO_ROOT = Path(__file__).resolve().parent.parent
RES_DIR = REPO_ROOT / "android-app" / "app" / "src" / "main" / "res" / "drawable"
BRAND_DIR = REPO_ROOT / "brand"

CANVAS = 108  # the adaptive-icon viewport, shared by both layers
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)
# Windows icons are visually rounded; 108-unit canvas -> ~22% corner radius,
# which is close to what Windows 11 uses for app icons.
ICO_CORNER_RADIUS = 24


def _android_color(value: str) -> tuple[str, float]:
    """#AARRGGBB or #RRGGBB -> (#RRGGBB, opacity). SVG has no alpha channel in
    the color itself, so the alpha byte becomes a separate opacity attribute."""
    value = value.strip()
    if not value.startswith("#"):
        raise ValueError(f"unsupported color literal: {value!r}")
    digits = value[1:]
    if len(digits) == 8:
        alpha = int(digits[0:2], 16) / 255.0
        return "#" + digits[2:], alpha
    if len(digits) == 6:
        return "#" + digits, 1.0
    raise ValueError(f"unsupported color literal: {value!r}")


def _read_background(path: Path) -> str:
    """The background layer as an SVG <defs>+<rect> pair."""
    root = ET.parse(path).getroot()
    gradient = root.find(f".//{AAPT_NS}attr/gradient")
    if gradient is None:
        # A flat <shape>/<vector> background: fall back to its solid fill.
        solid = root.find(f".//path")
        color, _ = _android_color(solid.get(f"{ANDROID_NS}fillColor"))
        return f'  <rect width="{CANVAS}" height="{CANVAS}" fill="{color}"/>'

    stops = []
    for item in gradient.findall("item"):
        color, alpha = _android_color(item.get(f"{ANDROID_NS}color"))
        offset = item.get(f"{ANDROID_NS}offset", "0")
        stops.append(
            f'      <stop offset="{offset}" stop-color="{color}" stop-opacity="{alpha:g}"/>'
        )

    x1 = gradient.get(f"{ANDROID_NS}startX", "0")
    y1 = gradient.get(f"{ANDROID_NS}startY", "0")
    x2 = gradient.get(f"{ANDROID_NS}endX", str(CANVAS))
    y2 = gradient.get(f"{ANDROID_NS}endY", str(CANVAS))

    return (
        "  <defs>\n"
        f'    <linearGradient id="bg" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"\n'
        '                    gradientUnits="userSpaceOnUse">\n'
        + "\n".join(stops)
        + "\n    </linearGradient>\n"
        "  </defs>\n"
        f'  <rect width="{CANVAS}" height="{CANVAS}" fill="url(#bg)"/>'
    )


def _read_foreground(path: Path) -> str:
    """The foreground layer's <path> elements, transcribed to SVG."""
    root = ET.parse(path).getroot()
    out = []
    for node in root.findall("path"):
        data = node.get(f"{ANDROID_NS}pathData")
        if not data:
            continue
        attrs = [f'd="{" ".join(data.split())}"']

        fill = node.get(f"{ANDROID_NS}fillColor")
        if fill:
            color, alpha = _android_color(fill)
            attrs.append(f'fill="{color}"')
            if alpha != 1.0:
                attrs.append(f'fill-opacity="{alpha:g}"')
        else:
            attrs.append('fill="none"')

        stroke = node.get(f"{ANDROID_NS}strokeColor")
        if stroke:
            color, alpha = _android_color(stroke)
            attrs.append(f'stroke="{color}"')
            if alpha != 1.0:
                attrs.append(f'stroke-opacity="{alpha:g}"')
            attrs.append(f'stroke-width="{node.get(f"{ANDROID_NS}strokeWidth", "1")}"')
            cap = node.get(f"{ANDROID_NS}strokeLineCap")
            if cap:
                attrs.append(f'stroke-linecap="{cap}"')
            join = node.get(f"{ANDROID_NS}strokeLineJoin")
            if join:
                attrs.append(f'stroke-linejoin="{join}"')

        out.append("  <path " + " ".join(attrs) + "/>")
    if not out:
        raise SystemExit(f"no <path> elements found in {path}")
    return "\n".join(out)


def build_svg() -> str:
    background = _read_background(RES_DIR / "ic_launcher_background.xml")
    foreground = _read_foreground(RES_DIR / "ic_launcher_foreground.xml")
    return (
        "<!-- Generated by tools/make_brand_assets.py from the Android launcher\n"
        "     icon - edit android-app/.../drawable/ic_launcher_*.xml, not this. -->\n"
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}"\n'
        f'     viewBox="0 0 {CANVAS} {CANVAS}">\n'
        f"{background}\n{foreground}\n</svg>\n"
    )


def _rounded(image, radius_px: int):
    from PIL import Image, ImageDraw

    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, image.size[0] - 1, image.size[1] - 1), radius=radius_px, fill=255
    )
    out = image.copy()
    out.putalpha(mask)
    return out


def main() -> int:
    try:
        import cairosvg
        from PIL import Image
    except ImportError:
        print(
            "needs the render-only dependencies:\n"
            "    python -m pip install cairosvg pillow",
            file=sys.stderr,
        )
        return 1

    BRAND_DIR.mkdir(exist_ok=True)
    svg = build_svg()
    svg_path = BRAND_DIR / "framecast_icon.svg"
    svg_path.write_text(svg, encoding="utf-8")

    def render(size: int):
        png = cairosvg.svg2png(
            bytestring=svg.encode("utf-8"), output_width=size, output_height=size
        )
        import io

        return Image.open(io.BytesIO(png)).convert("RGBA")

    # Play Store listing icon: 512x512, square, no transparency - Play applies
    # its own rounding, and a pre-rounded icon gets double-rounded corners.
    listing = Image.new("RGB", (512, 512), (0, 0, 0))
    listing.paste(render(512), (0, 0), render(512))
    listing.save(BRAND_DIR / "framecast_icon_512.png")

    # Everything shown on Windows is rounded with real transparency.
    logo = _rounded(render(256), round(256 * ICO_CORNER_RADIUS / CANVAS))
    logo.save(BRAND_DIR / "framecast_logo_256.png")

    frames = [
        _rounded(render(s), max(1, round(s * ICO_CORNER_RADIUS / CANVAS)))
        for s in ICO_SIZES
    ]
    # Pillow rescales from the base image for each requested size; handing it
    # the largest frame plus an explicit size list keeps every entry sharp
    # because each one is re-rendered from the vector above, not upscaled.
    frames[-1].save(
        BRAND_DIR / "framecast.ico",
        format="ICO",
        sizes=[(s, s) for s in ICO_SIZES],
        append_images=frames[:-1],
    )

    for f in sorted(BRAND_DIR.iterdir()):
        print(f"  {f.relative_to(REPO_ROOT)}  ({f.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
