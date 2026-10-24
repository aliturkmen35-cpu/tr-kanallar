"""Alin's afişini ve ikonunu üretir. Kullanım: python tools/make_banner.py"""
import math
from PIL import Image, ImageDraw, ImageFont

RED = (227, 10, 23)
WHITE = (255, 255, 255)
FONT = "C:/Windows/Fonts/segoeuib.ttf"
RES = "app/src/main/res"


def crescent_star(d, cx, cy, r, bg):
    # Ay
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)
    r2 = r * 0.8
    off = r * 0.25
    d.ellipse([cx - r2 + off, cy - r2, cx + r2 + off, cy + r2], fill=bg)
    # Yıldız
    sx, sy, so = cx + r * 1.05, cy, r * 0.42
    pts = []
    for i in range(10):
        rr = so if i % 2 == 0 else so * 0.4
        a = math.pi * i / 5
        pts.append((sx + rr * math.cos(a), sy + rr * math.sin(a)))
    d.polygon(pts, fill=WHITE)


def banner(w, h, path):
    img = Image.new("RGB", (w, h), RED)
    d = ImageDraw.Draw(img)
    s = w / 320
    crescent_star(d, 62 * s, 90 * s, 34 * s, RED)
    font = ImageFont.truetype(FONT, int(64 * s))
    d.text((122 * s, 84 * s), "Alin's", font=font, fill=WHITE, anchor="lm")
    small = ImageFont.truetype(FONT, int(18 * s))
    d.text((126 * s, 128 * s), "CANLI TV", font=small, fill=(255, 220, 220), anchor="lm")
    img.save(path)


def icon(size, path):
    img = Image.new("RGB", (size, size), RED)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, int(size * 0.62))
    d.text((size / 2, size / 2), "A", font=font, fill=WHITE, anchor="mm")
    img.save(path)


if __name__ == "__main__":
    import os
    os.makedirs(f"{RES}/drawable-xhdpi", exist_ok=True)
    banner(640, 360, f"{RES}/drawable-xhdpi/banner.png")
    for q, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        os.makedirs(f"{RES}/mipmap-{q}", exist_ok=True)
        icon(px, f"{RES}/mipmap-{q}/ic_launcher.png")
    print("ok")
