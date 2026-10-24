"""
Alin's marka görselleri: logo, TV afişi, uygulama ikonu ve ekran köşesi logosu.
Logo: kırmızı yuvarlak köşeli rozet içinde beyaz "oynat" üçgeni + "Alin's" yazısı.
Kullanım: python tools/make_brand.py [önizleme_klasörü]
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

RES = "app/src/main/res"
RED_TOP = (240, 32, 44)
RED_BOT = (176, 6, 18)
WHITE = (255, 255, 255, 255)
INK = (14, 17, 22)


def font(size):
    f = ImageFont.truetype("C:/Windows/Fonts/bahnschrift.ttf", size)
    try:
        f.set_variation_by_name("Bold")
    except Exception:
        pass
    return f


def gradient(w, h):
    g = Image.new("RGBA", (w, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(RED_TOP[i] + (RED_BOT[i] - RED_TOP[i]) * t) for i in range(3)) + (255,)
        for x in range(w):
            px[x, y] = c
    return g


def badge(size):
    """Rozet: degrade kırmızı daire; içinde tepesinden yayın dalgaları çıkan bir "A"
    (yayın kulesi gibi). Yumuşak kenar için 4 kat büyük çizilip küçültülür."""
    s = size * 4
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s - 1, s - 1], fill=255)
    b = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    b.paste(gradient(s, s), (0, 0), mask)
    d = ImageDraw.Draw(b)

    # "A": iki bacak + yatay çizgi, kalın ve geometrik
    apex = (s * 0.5, s * 0.40)
    left, right = (s * 0.27, s * 0.80), (s * 0.73, s * 0.80)
    stroke = int(s * 0.105)

    def leg(foot):
        # tepe ile ayak arasında, tabanı yatay olan kalın bir bacak
        dx, dy = foot[0] - apex[0], foot[1] - apex[1]
        ln = (dx * dx + dy * dy) ** 0.5
        nx, ny = -dy / ln * stroke / 2, dx / ln * stroke / 2
        pts = []
        for sgn in (1, -1):
            ax, ay = apex[0] + sgn * nx, apex[1] + sgn * ny
            t = (foot[1] - ay) / dy  # bu kenarın taban çizgisini kestiği yer
            pts.append((ax, ay))
            pts.append((ax + dx * t, foot[1]))
        d.polygon([pts[0], pts[1], pts[3], pts[2]], fill=WHITE)

    leg(left)
    leg(right)
    yb = s * 0.66
    t = (yb - apex[1]) / (left[1] - apex[1])
    xl = apex[0] + (left[0] - apex[0]) * t
    xr = apex[0] + (right[0] - apex[0]) * t
    d.line([(xl, yb), (xr, yb)], fill=WHITE, width=int(stroke * 0.8))
    # tepe noktası
    r = stroke * 0.62
    d.ellipse([apex[0] - r, apex[1] - r, apex[0] + r, apex[1] + r], fill=WHITE)

    # yayın dalgaları (tepenin iki yanında iki yay)
    for k, rad in enumerate((s * 0.13, s * 0.22)):
        box = [apex[0] - rad, apex[1] - rad, apex[0] + rad, apex[1] + rad]
        w = int(s * (0.045 - k * 0.008))
        d.arc(box, 205, 250, fill=WHITE, width=w)
        d.arc(box, 290, 335, fill=WHITE, width=w)

    # dairenin dışına taşanları kırp
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(b, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def lockup(height, text_color=WHITE):
    """Rozet + Alin's yazısı yan yana, şeffaf zemin."""
    bsz = height
    f = font(int(height * 0.92))
    tw = int(f.getlength("Alin's"))
    gap = int(height * 0.28)
    w = bsz + gap + tw + int(height * 0.08)
    img = Image.new("RGBA", (w, height), (0, 0, 0, 0))
    img.paste(badge(bsz), (0, 0), badge(bsz))
    ImageDraw.Draw(img).text((bsz + gap, height * 0.53), "Alin's", font=f, fill=text_color, anchor="lm")
    return img


def banner(path):
    w, h = 640, 360
    img = Image.new("RGBA", (w, h), INK + (255,))
    # sağ alttan hafif kırmızı ışık
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(60, 0, -1):
        a = int(3.2 * (60 - i))
        gd.ellipse([w - i * 9, h - i * 6, w + i * 9, h + i * 6], fill=(200, 10, 25, min(a, 90)))
    img = Image.alpha_composite(img, glow)
    lk = lockup(120)
    img.alpha_composite(lk, ((w - lk.width) // 2, (h - lk.height) // 2 - 16))
    d = ImageDraw.Draw(img)
    d.text((w / 2, h / 2 + 78), "CANLI TV", font=font(26), fill=(255, 255, 255, 170), anchor="mm")
    img.convert("RGB").save(path)


def icon(px, path):
    badge(px).save(path)


def watermark(path):
    # xhdpi: 72 px yükseklik = ekranda 36 dp
    lockup(72).save(path)


if __name__ == "__main__":
    os.makedirs(f"{RES}/drawable-xhdpi", exist_ok=True)
    banner(f"{RES}/drawable-xhdpi/banner.png")
    watermark(f"{RES}/drawable-xhdpi/watermark.png")
    badge(280).save(f"{RES}/drawable-xhdpi/radio_logo.png")  # radyo ekranındaki büyük logo
    for q, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        os.makedirs(f"{RES}/mipmap-{q}", exist_ok=True)
        icon(px, f"{RES}/mipmap-{q}/ic_launcher.png")
    if len(sys.argv) > 1:
        out = sys.argv[1]
        lockup(200, INK + (255,)).save(f"{out}/logo-light.png")
        lockup(200).save(f"{out}/logo-dark.png")
        badge(512).save(f"{out}/logo-icon.png")
        # önizleme sayfası
        sheet = Image.new("RGB", (1300, 760), (245, 245, 247))
        sheet.paste(Image.open(f"{RES}/drawable-xhdpi/banner.png"), (20, 20))
        sheet.paste(badge(300), (700, 40), badge(300))
        lt = lockup(110, INK + (255,))
        sheet.paste(lt, (20, 420), lt)
        dk = Image.new("RGB", (640, 260), INK)
        lk = lockup(110)
        dk.paste(lk, (20, 75), lk)
        sheet.paste(dk, (640, 480))
        sheet.save(f"{out}/brand-preview.png")
    print("ok")
