"""Draws docs/media/pixie-outfits.png: every Pixie outfit x pose, straight from the art in Sprites.java.

Mirrors Sprites.render(): fills, cape underlay, emblem/jetpack overlay, automatic 1-pixel outline.
Usage: python scripts/pixie_sheet.py [scale]
"""
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "launcher", "app", "src", "main", "java", "dev", "malaiesun", "home", "Sprites.java")
OUT = os.path.join(ROOT, "docs", "media", "pixie-outfits.png")
SCALE = int(sys.argv[1]) if len(sys.argv) > 1 else 8

src = open(SRC, encoding="utf-8").read()


def art(name):
    m = re.search(r"static final String\[\] " + name + r" = \{(.*?)\};", src, re.S)
    return re.findall(r'"([^"]*)"', m.group(1))


def shade(c, f):  # Theme.shade toward black
    r, g, b = (c >> 16) & 255, (c >> 8) & 255, c & 255
    return (int(r * (1 + f)), int(g * (1 + f)), int(b * (1 + f)))


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


OUTFITS = [  # theme, name, body, shade, scarf, boots, antenna, emblem, outline, cape, emblem?, jetpack
    ("retro", "Retro 8-bit", 0xF4F6FB, 0xA9B3CC, 0xFF5A36, 0x3B4466, 0xFFD84D, 0, 0x101420, False, False, False),
    ("comic", "Comic Pop", 0xFFD60A, 0xE09A00, 0xE63946, 0xE63946, 0xE63946, 0x1D4ED8, 0x111111, True, True, False),
    ("hero", "Hero", 0x34466A, 0x1F2B45, 0xE23636, 0xF2C14E, 0xF2C14E, 0xF2C14E, 0x05070D, True, True, False),
    ("neon", "Neon Grid", 0x2A1450, 0x180A33, 0xFF2BD6, 0x00E5FF, 0x00E5FF, 0, 0x00E5FF, False, False, True),
    ("aurora", "Aurora", 0xE6E0FF, 0xB9AEF0, 0xB266FF, 0x5B8CFF, 0xFFFFFF, 0, 0x101420, False, False, False),
    ("minimal", "Minimal", 0xE9ECF2, 0xB8BEC9, 0x5B8CFF, 0x4A5060, 0x8AB4FF, 0, 0x101420, False, False, False),
]
POSES = [("IDLE", "CAPE_STAND"), ("RUN1", "CAPE_RUN"), ("JUMP", "CAPE_JUMP"), ("WAVE1", "CAPE_STAND")]


def palette(o):
    _, _, body, sh, scarf, boots, ant, emb, outline, *_ = o
    return {
        "H": rgb(0xF4F6FB), "h": rgb(0xA9B3CC), "V": rgb(0x1C2540), "C": rgb(0x5CF2FF),
        "W": rgb(body), "G": rgb(sh), "R": rgb(scarf), "P": rgb(scarf), "r": shade(scarf, -0.3), "p": shade(scarf, -0.3),
        "B": rgb(boots), "Y": rgb(ant), "K": rgb(outline), "S": rgb(emb), "J": rgb(0x8A97B8), "j": rgb(0x4A5470),
    }


def render(base, under, over, pal, outline):
    h, w = len(base), len(base[0])
    W, H = w + 2, h + 2
    px = [[None] * W for _ in range(H)]
    for r in range(h):
        for c in range(w):
            col = pal.get(base[r][c])
            if col is None and under and r < len(under) and c < len(under[r]):
                col = pal.get(under[r][c])
            if over and r < len(over) and c < len(over[r]) and over[r][c] != ".":
                col = pal.get(over[r][c], col)
            px[r + 1][c + 1] = col
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for y in range(H):
        for x in range(W):
            if px[y][x] is not None:
                img.putpixel((x, y), px[y][x] + (255,))
            elif any(0 <= y + dy < H and 0 <= x + dx < W and px[y + dy][x + dx] is not None
                     for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img.putpixel((x, y), rgb(outline) + (255,))
    return img


cell, pad, label_w, head = 18 * SCALE, 6 * SCALE // 2, 190, 40
sheet = Image.new("RGBA", (label_w + len(POSES) * (cell + pad) + pad, head + len(OUTFITS) * (cell + pad) + pad), (24, 26, 38, 255))
d = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype("consolab.ttf", 20)
except OSError:
    font = ImageFont.load_default()
for i, (pose, _) in enumerate(POSES):
    d.text((label_w + pad + i * (cell + pad) + cell // 2 - 24, 10), pose.lower().rstrip("1"), fill=(200, 205, 220), font=font)
for row, o in enumerate(OUTFITS):
    theme, name, *_rest = o
    cape, emblem, jet = o[9], o[10], o[11]
    pal = palette(o)
    y = head + pad + row * (cell + pad)
    d.text((16, y + cell // 2 - 12), name, fill=(235, 238, 245), font=font)
    for i, (pose, cape_layer) in enumerate(POSES):
        over = art("EMBLEM") if emblem else art("JETPACK") if jet else None
        img = render(art(pose), art(cape_layer) if cape else None, over, pal, o[8])
        img = img.resize((cell, cell), Image.NEAREST)
        sheet.alpha_composite(img, (label_w + pad + i * (cell + pad), y))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
sheet.save(OUT)
print(OUT)
