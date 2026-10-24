"""Ekran köşesindeki küçük Alin's logosunu üretir (şeffaf PNG)."""
import math
from PIL import Image, ImageDraw, ImageFont

W, H = 360, 96          # xhdpi: ekranda yaklaşık 180x48 dp
FONT = "C:/Windows/Fonts/segoeuib.ttf"
WHITE = (255, 255, 255, 255)

img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Ay yıldız (soldaki küçük işaret)
cx, cy, r = 44, 48, 30
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)
r2, off = r * 0.8, r * 0.25
d.ellipse([cx - r2 + off, cy - r2, cx + r2 + off, cy + r2], fill=(0, 0, 0, 0))
sx, sy, so = cx + r * 1.05, cy, r * 0.42
pts = []
for i in range(10):
    rr = so if i % 2 == 0 else so * 0.4
    a = math.pi * i / 5
    pts.append((sx + rr * math.cos(a), sy + rr * math.sin(a)))
d.polygon(pts, fill=WHITE)

# Yazı
font = ImageFont.truetype(FONT, 66)
d.text((112, 50), "Alin's", font=font, fill=WHITE, anchor="lm")

# Açık renkli yayınlarda da okunsun diye hafif gölge
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
mask = img.split()[3]
shadow.paste((0, 0, 0, 140), (2, 2), mask)
out = Image.alpha_composite(shadow, img)
out.save("app/src/main/res/drawable-xhdpi/watermark.png")
print("ok")
