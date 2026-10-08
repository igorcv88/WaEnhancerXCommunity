#!/usr/bin/env python3
"""Generates the Liquid Glass calibration image (tools/liquid-glass-calibration.png).

Send it to yourself in a chat and scroll it behind the header and the message input. Each band
tests one thing: the 24 px grid shows the warp (lines must bend smoothly and never double back
at the rounded ends), the text rows show whether background text competes with the controls, the
fine bars show filtering and colour fringes, and the flat patches show tint and contrast
protection over light, mid and dark backdrops. Deterministic: same bytes every run.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 2160
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def main(out):
    img = Image.new("RGB", (W, H), (236, 236, 236))
    d = ImageDraw.Draw(img)
    small = ImageFont.truetype(FONT, 30)
    big = ImageFont.truetype(BOLD, 44)
    y = 0
    # 1. Grid: 24 px cells, 2 px lines.
    d.text((24, y + 10), "1  GRID 24 px - lines must bend smoothly, never fold or double", font=small, fill=(20, 20, 20))
    for x in range(0, W, 24):
        d.rectangle([x, y + 60, x + 1, y + 420], fill=(30, 30, 30))
    for gy in range(y + 60, y + 421, 24):
        d.rectangle([0, gy, W, gy + 1], fill=(30, 30, 30))
    y += 440
    # 2. Text rows, as in a chat, dark on light and light on dark.
    d.text((24, y + 10), "2  TEXT - should not be readable under the input field body", font=small, fill=(20, 20, 20))
    lines = ["The quick brown fox jumps over the lazy dog 0123456789",
             "Liquid Glass calibration: refraction, blur, contrast",
             "WWWWWWWWWW iiiiiiiiii llllllllll MMMMMMMMMM ||||||||||"]
    ty = y + 60
    for i, line in enumerate(lines * 2):
        dark = i >= 3
        if dark:
            d.rectangle([0, ty - 6, W, ty + 46], fill=(18, 24, 28))
        d.text((24, ty), line, font=small, fill=(240, 240, 240) if dark else (15, 15, 15))
        ty += 56
    y = ty + 20
    # 3. Fine bars: 2, 3, 4, 6 px periods, black/white, and an RGB band for fringes.
    d.text((24, y + 10), "3  BARS 2/3/4/6 px and RGB - filtering and colour fringes", font=small, fill=(20, 20, 20))
    by = y + 60
    for period in (2, 3, 4, 6):
        for x in range(0, W, period):
            if (x // period) % 2 == 0:
                d.rectangle([x, by, x + period // 2 if period > 2 else x, by + 60], fill=(0, 0, 0))
        by += 70
    for i, col in enumerate([(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255), (0, 0, 0)]):
        d.rectangle([i * W // 5, by, (i + 1) * W // 5, by + 120], fill=col)
    y = by + 140
    # 4. Flat patches: white, light grey, WhatsApp green, mid grey, dark, black.
    d.text((24, y + 10), "4  FLAT - tint and contrast protection over known luminance", font=small, fill=(20, 20, 20))
    py = y + 60
    patches = [(255, 255, 255), (200, 200, 200), (37, 211, 102), (128, 128, 128), (31, 44, 52), (0, 0, 0)]
    for i, col in enumerate(patches):
        d.rectangle([0, py, W, py + 90], fill=col)
        label = "%02X%02X%02X" % col
        d.text((24, py + 22), label, font=big, fill=(0, 0, 0) if sum(col) > 380 else (255, 255, 255))
        py += 90
    img.save(out, "PNG", optimize=True)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "tools/liquid-glass-calibration.png")
