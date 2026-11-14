#!/usr/bin/env python3
"""生成应用图标和电视桌面横幅（需要 Pillow）：python3 tools/make_icons.py
图案：蓝紫渐变的圆角方块，里面 2×2 四个「豆腐块」，左上那块是播放键。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

RES = Path(__file__).resolve().parent.parent / "res"
C1, C2 = (47, 156, 240), (107, 111, 240)
NAVY = (11, 18, 32)
FONTS_LATIN = ["/System/Library/Fonts/Avenir Next.ttc", "/System/Library/Fonts/Helvetica.ttc"]
FONTS_CJK = ["/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/STHeiti Medium.ttc"]


def font(cands, size, index=0):
    for f in cands:
        try:
            return ImageFont.truetype(f, size, index=index)
        except Exception:
            pass
    return ImageFont.load_default()


def gradient(w, h, a, b):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / (w + h - 2)
            px[x, y] = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return g


def icon(size=1024):
    s = size
    base = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * .23), fill=255)
    base.paste(gradient(s, s, C1, C2), (0, 0), mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))  # 半透明的块要叠上去，不能直接画（会把底色的透明度冲掉）
    d = ImageDraw.Draw(layer)
    pad, gap = s * .2, s * .07
    cell = (s - 2 * pad - gap) / 2
    r = int(cell * .2)
    alphas = [255, 120, 150, 85]
    for i in range(4):
        x0 = pad + (i % 2) * (cell + gap)
        y0 = pad + (i // 2) * (cell + gap)
        d.rounded_rectangle([x0, y0, x0 + cell, y0 + cell], radius=r, fill=(255, 255, 255, alphas[i]))
    # 左上那块里的播放键
    cx, cy, k = pad + cell / 2 + cell * .05, pad + cell / 2, cell * .26
    d.polygon([(cx - k * .8, cy - k), (cx - k * .8, cy + k), (cx + k, cy)], fill=C2 + (255,))
    return Image.alpha_composite(base, layer)


def main():
    big = icon(1024)
    for name, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        out = RES / f"mipmap-{name}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        big.resize((px, px), Image.LANCZOS).save(out)

    # 电视桌面横幅 320×180（xhdpi），先画 4 倍再缩小
    W, H = 1280, 720
    ban = Image.new("RGB", (W, H), NAVY)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([W * .45, -H * .6, W * 1.4, H * .9], fill=(47, 134, 224, 90))
    ImageDraw.Draw(glow).ellipse([-W * .3, H * .5, W * .5, H * 1.6], fill=(160, 91, 216, 60))
    ban.paste(glow.filter(ImageFilter.GaussianBlur(120)), (0, 0), glow.filter(ImageFilter.GaussianBlur(120)))
    ic = icon(400)
    ban.paste(ic, (110, (H - 400) // 2), ic)
    d = ImageDraw.Draw(ban)
    d.text((570, 190), "BiliPicks", font=font(FONTS_LATIN, 160, index=0), fill=(255, 255, 255))  # Avenir Next 第 0 个是 Bold
    d.text((578, 420), "B站精选 · 电视版", font=font(FONTS_CJK, 76, index=1), fill=(178, 196, 222))
    out = RES / "drawable-xhdpi" / "banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    ban.resize((320, 180), Image.LANCZOS).save(out)
    big.resize((512, 512), Image.LANCZOS).save(RES.parent / "tools" / "icon-512.png")
    print("已生成", RES)


if __name__ == "__main__":
    main()
