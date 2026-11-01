#!/usr/bin/env python3
"""Draws the mod's icon (a planner grid with an area, a building, a path and a marker) at any size, supersampled:

    python3 tools/make-icon.py                → src/main/resources/assets/mcplanner/icon.png (128 px, in the jar)
    python3 tools/make-icon.py 512 out.png    → the 512 px icon for Modrinth (or any other size)

Coordinates are in a 128-unit square. Needs Pillow."""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

DARK = (28, 36, 48, 255)
GREEN = (58, 104, 62, 255)
GRID = (66, 115, 70, 255)
BLUE = (75, 178, 238, 255)
RED = (232, 90, 74, 255)
PINK = (255, 191, 181, 255)
YELLOW = (232, 176, 74, 255)
PURPLE = (175, 74, 231, 255)
LILAC = (240, 225, 255, 255)


def draw(size: int) -> Image.Image:
    ss = 8  # supersampling
    s = size * ss / 128.0
    im = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    S = lambda *v: [round(x * s) for x in v]

    d.rounded_rectangle(S(4, 4, 124, 124), radius=round(20 * s), fill=DARK)
    d.rounded_rectangle(S(14, 15, 114, 114), radius=round(7 * s), fill=GREEN)
    # the grid, clipped to the green square
    grid = Image.new("RGBA", im.size, (0, 0, 0, 0))
    g = ImageDraw.Draw(grid)
    for i in range(11):
        v = 23.4 + i * 9.55
        g.line(S(v, 15, v, 114), fill=GRID, width=max(1, round(1.1 * s)))
        g.line(S(14, v + 1.6, 114, v + 1.6), fill=GRID, width=max(1, round(1.1 * s)))
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(S(14, 15, 114, 114), radius=round(7 * s), fill=255)
    im.paste(grid, (0, 0), Image.composite(grid, Image.new("RGBA", im.size), mask).getchannel("A"))
    d = ImageDraw.Draw(im)

    # an area (outline)
    d.rectangle(S(23.5, 24.5, 60.5, 57.5), outline=BLUE, width=round(2.6 * s))
    # a building (filled, two storeys)
    d.rectangle(S(75, 28, 104, 51.5), fill=PINK)
    d.rectangle(S(77, 30, 102, 49.5), fill=RED)
    d.rectangle(S(77, 39.2, 102, 40.8), fill=PINK)
    # a path
    pts = [(30, 105), (49.5, 105), (49.5, 82.5), (74.5, 82.5), (74.5, 62), (99, 62)]
    w = 6.0
    d.line([tuple(S(x, y)) for x, y in pts], fill=YELLOW, width=round(w * s), joint="curve")
    for x, y in pts[1:-1]:
        r = w / 2
        d.ellipse(S(x - r, y - r, x + r, y + r), fill=YELLOW)
    # a marker pin
    d.polygon([tuple(S(94.6, 81.5)), tuple(S(102.5, 94)), tuple(S(110.4, 81.5))], fill=PURPLE)
    d.ellipse(S(94.2, 70, 110.8, 86.6), fill=PURPLE)
    d.ellipse(S(99.3, 75.1, 105.7, 81.5), fill=LILAC)
    return im.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 128
    root = Path(__file__).resolve().parent.parent
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "src/main/resources/assets/mcplanner/icon.png"
    draw(size).save(out, optimize=True)
    print(f"{out} ({size} px)")
