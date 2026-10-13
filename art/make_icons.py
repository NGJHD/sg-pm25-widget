"""Builds the adaptive launcher icon layers from art/Haze.png.

Run from anywhere:  python art/make_icons.py
Writes mipmap-<density>/ic_launcher_foreground.png and
ic_launcher_monochrome.png. The background layer is plain white
(@color/icon_bg). The artwork is scaled to fit the 66dp safe zone of the
108dp adaptive canvas, so no launcher mask clips it.
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

src = Image.open(os.path.join(HERE, "Haze.png")).convert("RGBA")

# White -> transparent: alpha from darkness, colour kept as the artwork grey.
gray = src.convert("L")
alpha = gray.point(lambda v: max(0, min(255, int((255 - v) * 255 / (255 - 85)))))
art = Image.new("RGBA", src.size, (85, 85, 88, 255))
art.putalpha(alpha)
art = art.crop(alpha.getbbox())

for name, scale in DENSITIES.items():
    canvas_px = round(108 * scale)
    safe_px = round(66 * scale)
    w, h = art.size
    k = safe_px / max(w, h)
    sized = art.resize((max(1, round(w * k)), max(1, round(h * k))), Image.LANCZOS)
    fg = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    fg.paste(sized, ((canvas_px - sized.width) // 2, (canvas_px - sized.height) // 2), sized)
    mono = Image.new("RGBA", fg.size, (255, 255, 255, 0))
    mono.putalpha(fg.getchannel("A"))
    out = os.path.join(RES, "mipmap-" + name)
    os.makedirs(out, exist_ok=True)
    fg.save(os.path.join(out, "ic_launcher_foreground.png"))
    mono.save(os.path.join(out, "ic_launcher_monochrome.png"))
    print(name, fg.size)
