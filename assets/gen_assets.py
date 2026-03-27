"""
Generate Play Store visual assets for RubikSolver:
  - store/icon_512.png          (Play Store icon, 512×512)
  - store/feature_graphic.png   (Feature graphic, 1024×500)
  - store/ic_launcher_fg.png    (Adaptive icon foreground, 108dp @ xxxhdpi = 432px)
  - store/ic_launcher_bg.png    (Adaptive icon background, 432px)
"""

from PIL import Image, ImageDraw, ImageFont
import math, os

OUT = os.path.join(os.path.dirname(__file__), "store")
os.makedirs(OUT, exist_ok=True)

# ── Palette ───────────────────────────────────────────────────────────────────
BG_DARK   = (18, 18, 24)       # near-black background
ACCENT    = (99, 202, 255)     # camera-blue accent / viewfinder
WHITE     = (255, 255, 255)
CUBE_W    = (245, 240, 230)    # white face
CUBE_Y    = (255, 210,  30)    # yellow
CUBE_R    = (210,  35,  35)    # red
CUBE_O    = (240, 110,  20)    # orange
CUBE_B    = ( 30,  80, 210)    # blue
CUBE_G    = ( 30, 170,  60)    # green

FACE_COLORS = [CUBE_R, CUBE_O, CUBE_Y, CUBE_W, CUBE_B, CUBE_G]  # 9 tiles each face (2 visible faces)

# ── Helpers ───────────────────────────────────────────────────────────────────

def rounded_rect(draw, xy, radius, fill, outline=None, outline_width=2):
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=fill,
                           outline=outline, width=outline_width)


def draw_cube_isometric(draw, cx, cy, size, face_top, face_left, face_right):
    """
    Draw a 3×3 Rubik's cube in isometric projection.
    cx, cy  = centre of the cube
    size    = half-width of the full cube (total cube width = 2*size)
    face_top   = list of 9 colors (row-major, top face)
    face_left  = list of 9 colors (row-major, left face)
    face_right = list of 9 colors (row-major, right face)
    """
    s = size / 3  # one tile unit

    # Standard isometric projection: all three axes at 120° apart.
    # This gives equal apparent face size for top, left, and right.
    # The geometric centre of the cube (1.5,1.5,1.5) projects to (cx, cy).
    ax = ( s * 0.866,  s * 0.5)
    ay = (-s * 0.866,  s * 0.5)
    az = ( 0,         -s)

    def pt(xi, yi, zi):
        return (cx + xi*ax[0] + yi*ay[0] + zi*az[0],
                cy + xi*ax[1] + yi*ay[1] + zi*az[1])

    def tile_poly_bottom(col, row):
        # bottom face: z=0, fills the gap between the two side wings
        # drawn back-to-front: row=0 is back (high y), row=2 is front (low y)
        r = 2 - row
        return [pt(col,   r,   0),
                pt(col+1, r,   0),
                pt(col+1, r+1, 0),
                pt(col,   r+1, 0)]

    def tile_poly_left(col, row):
        # left face: x=0, vary y(col) and z(row from top)
        z = 3 - row
        return [pt(0, col,   z),
                pt(0, col+1, z),
                pt(0, col+1, z-1),
                pt(0, col,   z-1)]

    def tile_poly_right(col, row):
        # right face: y=0, vary x(col) and z(row from top)
        z = 3 - row
        return [pt(col,   0, z),
                pt(col+1, 0, z),
                pt(col+1, 0, z-1),
                pt(col,   0, z-1)]

    gap = max(2, int(s * 0.10))  # inter-tile gap in pixels

    def draw_face_tiles(poly_fn, colors, shade):
        for row in range(3):
            for col in range(3):
                poly = poly_fn(col, row)
                idx  = row * 3 + col
                r, g, b = colors[idx]
                fill = (int(r*shade), int(g*shade), int(b*shade))
                draw.polygon(poly, fill=fill)
                draw.line(poly + [poly[0]], fill=(0, 0, 0, 200), width=gap)

    # Painter's order for view-from-below: bottom first (back), sides on top
    draw_face_tiles(tile_poly_bottom, face_top,   0.88)
    draw_face_tiles(tile_poly_left,   face_left,  0.72)
    draw_face_tiles(tile_poly_right,  face_right, 0.85)


def make_top_face():
    # Yellow face with a couple of stray tiles — looks like a real scrambled cube
    return [CUBE_Y, CUBE_Y, CUBE_W,
            CUBE_Y, CUBE_Y, CUBE_Y,
            CUBE_O, CUBE_Y, CUBE_Y]

def make_left_face():
    return [CUBE_G, CUBE_G, CUBE_R,
            CUBE_G, CUBE_G, CUBE_R,
            CUBE_B, CUBE_B, CUBE_O]

def make_right_face():
    return [CUBE_R, CUBE_W, CUBE_O,
            CUBE_R, CUBE_R, CUBE_O,
            CUBE_R, CUBE_B, CUBE_W]


def draw_viewfinder(draw, cx, cy, size, color, line_w, corner_len):
    """Four L-shaped corner marks of a camera viewfinder."""
    half = size // 2
    corners = [
        ((cx - half, cy - half), (1,  1)),   # top-left
        ((cx + half, cy - half), (-1, 1)),   # top-right
        ((cx - half, cy + half), (1, -1)),   # bottom-left
        ((cx + half, cy + half), (-1,-1)),   # bottom-right
    ]
    for (x, y), (dx, dy) in corners:
        draw.line([(x, y), (x + dx*corner_len, y)],           fill=color, width=line_w)
        draw.line([(x, y), (x,                y + dy*corner_len)], fill=color, width=line_w)


# ══════════════════════════════════════════════════════════════════════════════
# 1. App Icon  512 × 512
# ══════════════════════════════════════════════════════════════════════════════

def make_icon(size=512):
    img  = Image.new("RGBA", (size, size), (0,0,0,0))
    draw = ImageDraw.Draw(img, "RGBA")

    # Rounded-square background (like Android adaptive icon)
    pad  = int(size * 0.04)
    r    = int(size * 0.22)
    rounded_rect(draw, [pad, pad, size-pad, size-pad], radius=r, fill=BG_DARK)

    # Viewfinder frame
    vf_size    = int(size * 0.72)
    vf_cx, vf_cy = size//2, int(size * 0.52)
    corner_len = int(size * 0.10)
    line_w     = max(3, int(size * 0.018))
    draw_viewfinder(draw, vf_cx, vf_cy, vf_size, ACCENT, line_w, corner_len)

    # Small lens circle at top-right of viewfinder
    lr = int(size * 0.04)
    lx = vf_cx + vf_size//2 - lr - int(size*0.05)
    ly = vf_cy - vf_size//2 + lr + int(size*0.05)
    draw.ellipse([lx-lr, ly-lr, lx+lr, ly+lr], outline=ACCENT, width=max(2, line_w//2))

    # Rubik's cube — geometric centre at (vf_cx, vf_cy); standard isometric so all 3 faces show
    cube_size = int(size * 0.30)
    draw_cube_isometric(draw, vf_cx, vf_cy,
                        cube_size, make_top_face(), make_left_face(), make_right_face())

    # Small "RUBIK SOLVER" label below viewfinder
    label_y = vf_cy + vf_size//2 + int(size*0.03)
    try:
        font = ImageFont.truetype("arial.ttf", int(size*0.055))
    except Exception:
        font = ImageFont.load_default()
    text = "RUBIK SOLVER"
    bbox = draw.textbbox((0,0), text, font=font)
    tw = bbox[2] - bbox[0]
    draw.text((size//2 - tw//2, label_y), text, font=font, fill=ACCENT)

    return img

icon = make_icon(512)
icon.save(os.path.join(OUT, "icon_512.png"))
print("icon_512.png done")


# ══════════════════════════════════════════════════════════════════════════════
# 2. Adaptive icon foreground  432 × 432  (108dp @ xxxhdpi, safe zone = 66dp centre)
# ══════════════════════════════════════════════════════════════════════════════

def make_adaptive_fg(size=432):
    img  = Image.new("RGBA", (size, size), (0,0,0,0))
    draw = ImageDraw.Draw(img, "RGBA")

    cx, cy = size//2, size//2
    vf_size    = int(size * 0.60)
    corner_len = int(size * 0.09)
    line_w     = max(3, int(size * 0.018))
    draw_viewfinder(draw, cx, cy, vf_size, ACCENT, line_w, corner_len)

    cube_size = int(size * 0.25)
    draw_cube_isometric(draw, cx, cy,
                        cube_size, make_top_face(), make_left_face(), make_right_face())
    return img

fg = make_adaptive_fg(432)
fg.save(os.path.join(OUT, "ic_launcher_fg.png"))
print("ic_launcher_fg.png done")


# ══════════════════════════════════════════════════════════════════════════════
# 3. Adaptive icon background  432 × 432
# ══════════════════════════════════════════════════════════════════════════════

def make_adaptive_bg(size=432):
    img  = Image.new("RGBA", (size, size), BG_DARK + (255,))
    return img

bg = make_adaptive_bg(432)
bg.save(os.path.join(OUT, "ic_launcher_bg.png"))
print("ic_launcher_bg.png done")


# ══════════════════════════════════════════════════════════════════════════════
# 4. Feature Graphic  1024 × 500
# ══════════════════════════════════════════════════════════════════════════════

def make_feature(w=1024, h=500):
    img  = Image.new("RGB", (w, h), BG_DARK)
    draw = ImageDraw.Draw(img)

    # Subtle grid dots in background
    dot_spacing = 40
    for gx in range(0, w, dot_spacing):
        for gy in range(0, h, dot_spacing):
            draw.ellipse([gx-1, gy-1, gx+1, gy+1], fill=(40, 40, 55))

    # Left side: big viewfinder with cube
    cube_cx = int(w * 0.30)
    cube_cy = int(h * 0.50)
    vf_size = int(h * 0.72)
    corner_len = int(h * 0.08)
    line_w = max(3, int(h * 0.016))
    draw_viewfinder(draw, cube_cx, cube_cy, vf_size, ACCENT, line_w, corner_len)

    cube_size = int(h * 0.27)
    draw_cube_isometric(draw, cube_cx, cube_cy,
                        cube_size, make_top_face(), make_left_face(), make_right_face())

    # Right side: text
    tx = int(w * 0.55)
    try:
        font_big  = ImageFont.truetype("arialbd.ttf", int(h * 0.14))
        font_sub  = ImageFont.truetype("arial.ttf",   int(h * 0.065))
        font_tag  = ImageFont.truetype("arial.ttf",   int(h * 0.052))
    except Exception:
        font_big  = ImageFont.load_default()
        font_sub  = font_big
        font_tag  = font_big

    draw.text((tx, int(h*0.15)), "RUBIK",  font=font_big, fill=WHITE)
    draw.text((tx, int(h*0.37)), "SOLVER", font=font_big, fill=ACCENT)

    tag = "Scan · Solve · Enjoy"
    draw.text((tx, int(h*0.72)), tag, font=font_sub, fill=(180, 200, 220))

    # Thin accent line
    draw.line([(tx, int(h*0.65)), (tx + int(w*0.35), int(h*0.65))],
              fill=ACCENT, width=max(2, int(h*0.006)))

    return img

feat = make_feature()
feat.save(os.path.join(OUT, "feature_graphic.png"))
print("feature_graphic.png done")

print("\nAll assets saved to:", OUT)
