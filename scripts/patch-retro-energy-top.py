"""Petic pe retro_energy_top_artwork.webp (v3.29): acopera fanta vechilor taburi cu tabla placii si
genereaza LED-ul portocaliu / rosu ca suprapuneri peste LED-ul verde pictat.

Intrarea e asset-ul ORIGINAL, de dinainte de petic (pe cel deja peticit l-ar petici a doua oara):

    git show 1d728bc:android/app/src/main/res/drawable-nodpi/retro_energy_top_artwork.webp > /tmp/top.webp
    python3 -I scripts/patch-retro-energy-top.py /tmp/top.webp /tmp/out

Scrie in <out_dir> cele trei WebP-uri pentru drawable-nodpi plus PNG-uri de previzualizare.
Daca vine un export Photoshop nou al placii, ruleaza doar partea 2 (LED) pe el.
"""
import colorsys
import math
import sys

from PIL import Image, ImageOps

src_path, out_dir = sys.argv[1], sys.argv[2]
art = Image.open(src_path).convert("RGB")
W, H = art.size
assert (W, H) == (1024, 218), art.size

# --- 1. Fanta acoperita ------------------------------------------------------------------
# Fanta PRODUCTIE / CONSUM / ISTORIC: x 45..810, y 124..197 (px de asset). Peticul e putin mai lat
# si se topeste pe margini in tabla neatinsa.
FX0, FY0, FX1, FY1 = 40, 106, 818, 200
FEATHER = 6
FEATHER_TOP = 12
fw, fh = FX1 - FX0, FY1 - FY0

# Tabla goala dintre titlul ENERGIE si carcasa LED-ului.
SX0, SY0, SX1 = 352, 26, 845
src = art.crop((SX0, SY0, SX1, SY0 + fh))
fill = Image.new("RGB", (fw, fh))
fill.paste(src, (0, 0))
fill.paste(ImageOps.mirror(src), (src.width, 0))  # oglindit: imbinare fara treapta


def mean_rgb(img):
    px = list(img.getdata())
    n = len(px)
    return [sum(p[c] for p in px) / n for c in range(3)]


# Luminozitatea peticului = media tablei din stanga si din dreapta fantei.
ref = mean_rgb(art.crop((814, 122, 850, 196)))
ref_left = mean_rgb(art.crop((14, 122, 40, 170)))
ref = [(a + b) / 2 for a, b in zip(ref, ref_left)]
cur = mean_rgb(fill)
gain = [r / c if c else 1.0 for r, c in zip(ref, cur)]
fp = fill.load()
for y in range(fh):
    for x in range(fw):
        r, g, b = fp[x, y]
        fp[x, y] = tuple(max(0, min(255, round(v * k))) for v, k in zip((r, g, b), gain))

mask = Image.new("L", (fw, fh), 0)
mp = mask.load()
for y in range(fh):
    for x in range(fw):
        # Marginea de jos ramane dura: imediat dedesubt incepe bizoul placii.
        t = min(min(x, fw - 1 - x) / FEATHER, y / FEATHER_TOP, 1.0)
        mp[x, y] = round(255 * t * t * (3 - 2 * t))
art.paste(fill, (FX0, FY0), mask)

out_art = f"{out_dir}/retro_energy_top_artwork.webp"
art.save(out_art, "WEBP", quality=92, method=6)

# --- 2. LED portocaliu / rosu ------------------------------------------------------------
# Recitit dupa codare, ca suprapunerea sa porneasca exact din pixelii peste care sta.
base = Image.open(out_art).convert("RGB")
CX, CY, R = 900, 113, 60          # centrul lentilei si jumatate din latura (px de asset)
box = (CX - R, CY - R, CX + R, CY + R)
crop = base.crop(box)


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def overlay(shift_deg, name):
    out = Image.new("RGBA", crop.size)
    cp, op = crop.load(), out.load()
    for y in range(crop.height):
        for x in range(crop.width):
            r, g, b = (v / 255 for v in cp[x, y])
            h, l, s = colorsys.rgb_to_hls(r, g, b)
            hue = h * 360
            mx, mn = max(r, g, b), min(r, g, b)
            sat_v = (mx - mn) / mx if mx else 0.0
            # Lumina verde-galbuie (lentila + reflexia pe carcasa). Tabla de bronz are ~45 de grade.
            w = smoothstep(50, 62, hue) * (1 - smoothstep(150, 175, hue))
            w *= smoothstep(0.18, 0.40, sat_v)
            dist = math.hypot(x + 0.5 - R, y + 0.5 - R)
            w *= 1 - smoothstep(44, 54, dist)
            # Miezul alb-incandescent ramane cald, nu ia complet noua nuanta.
            core = smoothstep(0.80, 0.95, l)
            # Oprit la 0 grade: reflexia slaba de pe tabla nu trebuie sa treaca in magenta.
            nh = max(0.0, hue + shift_deg * (1 - 0.45 * core)) / 360
            nr, ng, nb = colorsys.hls_to_rgb(nh, l, s)
            op[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), round(255 * w))
    out.save(f"{out_dir}/{name}", "WEBP", lossless=True, quality=100, method=6)
    preview = base.copy()
    preview.paste(out, box[:2], out)
    preview.save(f"{out_dir}/preview_{name.replace('.webp', '.png')}")


overlay(-47, "retro_energy_led_orange.webp")
overlay(-76, "retro_energy_led_red.webp")
base.save(f"{out_dir}/preview_green.png")
print("box", box)
