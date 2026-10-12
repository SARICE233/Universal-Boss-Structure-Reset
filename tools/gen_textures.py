#!/usr/bin/env python3
"""Procedurally generate the 16x16 item textures of the Structure Reset addon.

Every texture is drawn pixel by pixel with Pillow from a small palette plus a
glyph mask -- there is no external artwork and no single colour placeholder.
All four icons share the same "coin" silhouette (rim, body, face, glowing core)
so they read as one item family, while their palette and their glyph differ, so
they stay clearly distinguishable at 16x16.

Generated files:
    src/main/resources/assets/structurereset/textures/item/structure_reset_core.png
    src/main/resources/assets/structurereset/textures/item/structure_reset_core_skin1.png
    src/main/resources/assets/structurereset/textures/item/structure_reset_core_skin2.png
    kubejs-examples/assets/structurereset/textures/item/custom_core.png

Usage:
    python tools/gen_textures.py            # (re)write every texture, exit 0
    python tools/gen_textures.py --check    # only re-verify the files on disk
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

try:
    from PIL import Image, ImageDraw
except ImportError:  # Pillow is a hard requirement, reported cleanly below
    Image = None
    ImageDraw = None

SCRIPT = Path(__file__).resolve()
WORKSPACE = SCRIPT.parent.parent

ITEM_TEXTURES = WORKSPACE / "src/main/resources/assets/structurereset/textures/item"
KUBEJS_TEXTURES = WORKSPACE / "kubejs-examples/assets/structurereset/textures/item"

SIZE = 16

TEXTURES = (
    {
        "path": ITEM_TEXTURES / "structure_reset_core.png",
        "glyph": "plus",
        # An animated strip: 16 frames of a pulsing core played at one tick each,
        # so the item animates at the full 20 Hz of the game instead of stepping.
        "frames": 16,
        "frametime": 1,
        # Also write <name>_glow.png, the emissive companion drawn by the mod.
        "glow": True,
        "palette": {
            "rim": (18, 14, 32, 255),
            "body": (56, 42, 92, 255),
            "face": (98, 74, 158, 255),
            "core": (128, 232, 255, 255),
            "glyph": (218, 250, 255, 255),
        },
    },
    {
        "path": ITEM_TEXTURES / "structure_reset_core_skin1.png",
        "glyph": "cross",
        "palette": {
            "rim": (40, 18, 6, 255),
            "body": (92, 48, 12, 255),
            "face": (146, 84, 22, 255),
            "core": (255, 104, 40, 255),
            "glyph": (255, 226, 150, 255),
        },
    },
    {
        "path": ITEM_TEXTURES / "structure_reset_core_skin2.png",
        "glyph": "diamond",
        "palette": {
            "rim": (6, 32, 20, 255),
            "body": (16, 72, 44, 255),
            "face": (30, 112, 66, 255),
            "core": (140, 255, 180, 255),
            "glyph": (236, 255, 242, 255),
        },
    },
    {
        "path": KUBEJS_TEXTURES / "custom_core.png",
        "glyph": "ring",
        "palette": {
            "rim": (14, 22, 40, 255),
            "body": (44, 70, 112, 255),
            "face": (84, 128, 180, 255),
            "core": (255, 196, 64, 255),
            "glyph": (255, 246, 214, 255),
        },
    },
    {
        # The "armed confirmation window" icon: the same coin silhouette in a
        # clearly warmer and brighter palette than every other core, so the
        # active state is readable at a glance at 16x16.
        "path": ITEM_TEXTURES / "structure_reset_core_active.png",
        "glyph": "diamond",
        # The armed state pulses twice as fast, so "active" also reads as motion.
        "frames": 16,
        "frametime": 1,
        "glow": True,
        "palette": {
            "rim": (64, 16, 4, 255),
            "body": (148, 52, 10, 255),
            "face": (222, 104, 24, 255),
            "core": (255, 246, 186, 255),
            "glyph": (255, 255, 244, 255),
        },
    },
)


def glyph_pixels(kind):
    """The canvas coordinates covered by one glyph mask."""
    points = set()
    for y in range(SIZE):
        for x in range(SIZE):
            dx = x - (SIZE / 2.0 - 0.5)
            dy = y - (SIZE / 2.0 - 0.5)
            if kind == "plus":
                hit = (abs(dx) <= 1.0 and abs(dy) <= 4.0) or (abs(dy) <= 1.0 and abs(dx) <= 4.0)
            elif kind == "cross":
                hit = ((abs(dx - dy) <= 1.0 or abs(dx + dy) <= 1.0) and max(abs(dx), abs(dy)) <= 5.5)
            elif kind == "diamond":
                hit = abs(dx) + abs(dy) <= 4.5
            elif kind == "diamond_center":
                hit = abs(dx) + abs(dy) <= 1.5
            elif kind == "ring":
                hit = 2.0 <= math.hypot(dx, dy) <= 3.4
            else:
                hit = False
            if hit:
                points.add((x, y))
    return points


def pulse(colour, phase, amount=0.32):
    """The same colour, brightened or dimmed along one sine cycle."""
    factor = 1.0 + amount * math.sin(2.0 * math.pi * phase)
    out = []
    for index, channel in enumerate(colour):
        if index < 3:
            out.append(min(255, max(0, int(channel * factor))))
        else:
            out.append(channel)
    return tuple(out)


def draw_icon(palette, glyph, phase=0.0):
    """Render one 16x16 RGBA icon: rim, body, face, core, then the glyph.

    The phase drives the pulse of the core and of the glyph, so the frames of an
    animated strip differ in exactly the way the eye reads as "it is alive".
    """
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    core = pulse(palette["core"], phase)
    glyph_colour = pulse(palette["glyph"], phase, 0.22)
    draw.ellipse([0, 0, SIZE - 1, SIZE - 1], fill=palette["rim"])
    draw.ellipse([1, 1, SIZE - 2, SIZE - 2], fill=palette["body"])
    draw.ellipse([2, 2, SIZE - 3, SIZE - 3], fill=palette["face"])
    draw.ellipse([5, 5, SIZE - 6, SIZE - 6], fill=core)

    pixels = image.load()
    for x, y in glyph_pixels(glyph):
        if pixels[x, y][3] != 0:
            pixels[x, y] = glyph_colour
    if glyph == "diamond":
        # a core coloured heart keeps the diamond from looking like a blob
        for x, y in glyph_pixels("diamond_center"):
            pixels[x, y] = core
    return image


def draw_glow(palette, glyph, phase=0.0):
    """The glowing pixels only: the core and the glyph, transparent elsewhere.

    This is the emissive companion of an icon. The mod draws it as a second,
    unlit render pass, so exactly these pixels shine.
    """
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    core = pulse(palette["core"], phase)
    glyph_colour = pulse(palette["glyph"], phase, 0.22)
    draw.ellipse([5, 5, SIZE - 6, SIZE - 6], fill=core)
    pixels = image.load()
    for x, y in glyph_pixels(glyph):
        if pixels[x, y][3] != 0:
            pixels[x, y] = glyph_colour
    if glyph == "diamond":
        for x, y in glyph_pixels("diamond_center"):
            pixels[x, y] = core
    return image


def draw_glow_strip(palette, glyph, frames):
    """Every frame of a glow companion, stacked top to bottom."""
    strip = Image.new("RGBA", (SIZE, SIZE * frames), (0, 0, 0, 0))
    for frame in range(frames):
        strip.paste(draw_glow(palette, glyph, frame / float(frames)), (0, frame * SIZE))
    return strip


def draw_strip(palette, glyph, frames):
    """Every frame of an animated icon, stacked top to bottom."""
    strip = Image.new("RGBA", (SIZE, SIZE * frames), (0, 0, 0, 0))
    for frame in range(frames):
        strip.paste(draw_icon(palette, glyph, frame / float(frames)), (0, frame * SIZE))
    return strip


def inspect(path, frames=1):
    """(colour count, sha1 of the raw pixels) of a texture, or (None, error)."""
    expected = (SIZE, SIZE * frames)
    try:
        with Image.open(path) as image:
            image.load()
            if image.size != expected:
                return None, "is %dx%d, expected %dx%d" % (
                    image.size[0], image.size[1], expected[0], expected[1])
            raw = image.convert("RGBA").tobytes()
    except Exception as exc:  # noqa: BLE001 - reported as a problem
        return None, "cannot be opened by Pillow (%s)" % exc
    colours = len(set(zip(raw[0::4], raw[1::4], raw[2::4], raw[3::4])))
    return colours, hashlib.sha1(raw).hexdigest()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Generate the Structure Reset item textures.")
    parser.add_argument("--check", action="store_true",
                        help="only verify the textures already on disk")
    args = parser.parse_args(list(sys.argv[1:] if argv is None else argv))

    if Image is None:
        print("FAIL: gen-textures: Pillow is required (python -m pip install Pillow)", file=sys.stderr)
        return 1

    problems = []
    digests = {}
    for spec in TEXTURES:
        path = spec["path"]
        label = path.relative_to(WORKSPACE).as_posix()
        frames = int(spec.get("frames", 1))
        meta = path.with_suffix(path.suffix + ".mcmeta")
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            if frames > 1:
                draw_strip(spec["palette"], spec["glyph"], frames).save(path, format="PNG")
                meta.write_text(json.dumps({"animation": {
                    "frametime": int(spec.get("frametime", 6)),
                    "interpolate": True}}, indent=2) + "\n", encoding="utf-8")
            else:
                draw_icon(spec["palette"], spec["glyph"]).save(path, format="PNG")
        if not path.is_file():
            problems.append("%s: missing" % label)
            continue
        if frames > 1 and not meta.is_file():
            problems.append("%s: animated texture without a .mcmeta beside it" % label)
            continue
        colours, digest = inspect(path, frames)
        if colours is None:
            problems.append("%s: %s" % (label, digest))
            continue
        if colours < 3:
            problems.append("%s: only %d colour(s), a flat placeholder is not allowed" % (label, colours))
            continue
        digests.setdefault(digest, []).append(label)
        print("ok: %s (%dx%d, %d frame(s), %d colours)"
              % (label, SIZE, SIZE * frames, frames, colours))

        if spec.get("glow"):
            glow = path.with_name(path.stem + "_glow" + path.suffix)
            glow_meta = glow.with_suffix(glow.suffix + ".mcmeta")
            if not args.check:
                (draw_glow_strip(spec["palette"], spec["glyph"], frames)
                 if frames > 1 else draw_glow(spec["palette"], spec["glyph"])).save(glow, format="PNG")
                if frames > 1:
                    glow_meta.write_text(json.dumps({"animation": {
                        "frametime": int(spec.get("frametime", 6)),
                        "interpolate": True}}, indent=2) + "\n", encoding="utf-8")
            glow_label = glow.relative_to(WORKSPACE).as_posix()
            if not glow.is_file():
                problems.append("%s: missing" % glow_label)
                continue
            glow_colours, _ = inspect(glow, frames)
            if glow_colours is None:
                problems.append("%s: %s" % (glow_label, glow_colours))
                continue
            print("ok: %s (%dx%d, glow companion)"
                  % (glow_label, SIZE, SIZE * frames))

    for digest, labels in sorted(digests.items()):
        if len(labels) > 1:
            problems.append("identical textures: %s" % ", ".join(sorted(labels)))

    if problems:
        for problem in problems:
            print("FAIL: gen-textures: %s" % problem)
        return 1
    print("OK: gen-textures: %d distinguishable %dx%d texture(s)%s"
          % (len(TEXTURES), SIZE, SIZE, " verified" if args.check else " written"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
