"""
Resize ic_launcher_fg.png and ic_launcher_bg.png into all mipmap densities.
Also produces a round icon version (circle-clipped).
"""
from PIL import Image, ImageDraw
import os, shutil

STORE = os.path.join(os.path.dirname(__file__), "store")
OUT   = os.path.join(os.path.dirname(__file__), "mipmaps")

DENSITIES = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}

fg = Image.open(os.path.join(STORE, "ic_launcher_fg.png")).convert("RGBA")
bg = Image.open(os.path.join(STORE, "ic_launcher_bg.png")).convert("RGBA")

def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([0, 0, size, size], fill=255)
    return mask

def rounded_mask(size, radius_frac=0.22):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    r = int(size * radius_frac)
    draw.rounded_rectangle([0, 0, size, size], radius=r, fill=255)
    return mask

for density, size in DENSITIES.items():
    d = os.path.join(OUT, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)

    fg_r = fg.resize((size, size), Image.LANCZOS)
    bg_r = bg.resize((size, size), Image.LANCZOS)

    # Composite foreground onto background
    composed = bg_r.copy()
    composed.paste(fg_r, (0, 0), fg_r)

    # Square icon (rounded rect, for ic_launcher)
    sq = composed.copy()
    sq.putalpha(rounded_mask(size))
    sq.save(os.path.join(d, "ic_launcher.png"))

    # Round icon (circle, for ic_launcher_round)
    rnd = composed.copy()
    rnd.putalpha(circle_mask(size))
    rnd.save(os.path.join(d, "ic_launcher_round.png"))

    # Foreground layer only
    fg_r.save(os.path.join(d, "ic_launcher_foreground.png"))

    print(f"  {density}: {size}px")

print("\nMipmaps saved to:", OUT)
