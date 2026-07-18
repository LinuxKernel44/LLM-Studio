"""One-off script: turns icon.png (a flat neon-on-navy square image) into a proper
Android adaptive icon (foreground PNG with the navy keyed out to transparency,
a solid background color sampled from the original artwork) plus flattened
legacy launcher icons for pre-Android-8 launchers. Not part of the app build -
run manually whenever the source icon changes, then delete/ignore the output.
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "scripts", "icon_source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

im = Image.open(SRC).convert("RGBA")
w, h = im.size
print("source size:", im.size)

# Sample the background color from the four corners (a few px in, to dodge any
# anti-aliased edge) and average them.
px = im.load()
corners = [(4, 4), (w - 5, 4), (4, h - 5), (w - 5, h - 5)]
bg = [sum(px[c][ch] for c in corners) / 4 for ch in range(3)]
print("sampled background RGB:", bg)


def dist(p, q):
    return sum((p[i] - q[i]) ** 2 for i in range(3)) ** 0.5


# Key out the background: pixels close to bg become transparent, pixels far from
# it (the neon lines/nodes/bubble) stay opaque. A soft ramp over a threshold
# window keeps the glow's natural falloff instead of a hard cutout edge.
LOW, HIGH = 12, 70  # distance-from-bg thresholds (soft edge between them)
out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
opx = out.load()
minx, miny, maxx, maxy = w, h, 0, 0
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        d = dist((r, g, b), bg)
        if d <= LOW:
            alpha = 0
        elif d >= HIGH:
            alpha = 255
        else:
            alpha = int(255 * (d - LOW) / (HIGH - LOW))
        if alpha > 0:
            opx[x, y] = (r, g, b, alpha)
            if x < minx: minx = x
            if x > maxx: maxx = x
            if y < miny: miny = y
            if y > maxy: maxy = y

print("glyph bbox:", (minx, miny, maxx, maxy))
glyph = out.crop((minx, miny, maxx + 1, maxy + 1))
gw, gh = glyph.size

# Adaptive icons: only the center ~66% of the 108dp canvas survives every mask
# shape (circle, squircle, rounded square, teardrop) across different
# launchers. Scale the glyph to fit within that safe zone on a square canvas.
CANVAS = 1024
SAFE_FRACTION = 0.62
safe = int(CANVAS * SAFE_FRACTION)
scale = min(safe / gw, safe / gh)
new_w, new_h = int(gw * scale), int(gh * scale)
glyph_resized = glyph.resize((new_w, new_h), Image.LANCZOS)

foreground = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
foreground.paste(glyph_resized, ((CANVAS - new_w) // 2, (CANVAS - new_h) // 2), glyph_resized)

fg_dir = os.path.join(RES, "drawable-nodpi")
os.makedirs(fg_dir, exist_ok=True)
foreground.save(os.path.join(fg_dir, "ic_launcher_foreground.png"))
print("wrote", os.path.join(fg_dir, "ic_launcher_foreground.png"))

bg_hex = "#{:02X}{:02X}{:02X}".format(*(int(round(c)) for c in bg))
print("background hex:", bg_hex, "- update @color/ic_launcher_background in colors.xml with this")

# Legacy (pre-API26) flattened icons: background color + centered foreground,
# baked per density, square and circle-cropped ("round") variants.
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
bg_rgba = (int(round(bg[0])), int(round(bg[1])), int(round(bg[2])), 255)

from PIL import ImageDraw

for density, size in DENSITIES.items():
    flat = Image.new("RGBA", (CANVAS, CANVAS), bg_rgba)
    flat.paste(foreground, (0, 0), foreground)
    square = flat.resize((size, size), Image.LANCZOS)

    mask = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, CANVAS, CANVAS), fill=255)
    round_full = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    round_full.paste(flat, (0, 0), mask)
    round_icon = round_full.resize((size, size), Image.LANCZOS)

    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    square.save(os.path.join(d, "ic_launcher.webp"), "WEBP")
    round_icon.save(os.path.join(d, "ic_launcher_round.webp"), "WEBP")
    print("wrote", density, size)

print("done")
