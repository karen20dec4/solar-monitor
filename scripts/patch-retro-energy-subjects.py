"""Petic pe retro_energy_controls_chart_artwork.webp (v3.32): randul CE urmaresti are patru placi (CASA /
PANOURI / BATERIE / RETEA) in loc de trei, iar textul si pictogramele lor le deseneaza Compose.

Asset-ul ORIGINAL are trei placi late cu icoana si textul pictate. Peticul:
  1. face o placa goala din prima (CASA): sterge icoana si textul cu textura din dreapta cuvantului;
  2. din ea face patru placi mai inguste (capete cu suruburi + un mijloc de textura);
  3. le aseaza pe acelasi loc, cu aceleasi goluri ca intre placile vechi (taiate din golurile reale).

Intrarea e asset-ul de dinainte de petic (pe cel deja peticit l-ar petici a doua oara):

    git show 581d1c5:android/app/src/main/res/drawable-nodpi/retro_energy_controls_chart_artwork.webp > /tmp/controls.webp
    python3 -I scripts/patch-retro-energy-subjects.py /tmp/controls.webp /tmp/controls-new.webp
"""
import sys

from PIL import Image, ImageFilter

src_path, out_path = sys.argv[1], sys.argv[2]
art = Image.open(src_path).convert("RGBA")   # cu alfa: coltunile rotunjite ale panoului sunt transparente
assert art.size == (1024, 1266), art.size

# Placile vechi (px de asset): y 60..168; x 55..344, 362..656, 674..969; goluri reale 344..362 si 656..674.
Y0, Y1 = 58, 172
P_X0, P_X1 = 55, 344
GAP_W = 18
ROW_X0, ROW_X1 = 55, 969
COUNT = 4
PLATE_W = (ROW_X1 - ROW_X0 - GAP_W * (COUNT - 1)) // COUNT  # 215
CAP = 88                                                      # capete: suruburi + muchii

# --- 1. placa goala ------------------------------------------------------------------------
plate = art.crop((P_X0, Y0, P_X1, Y1))
pw, ph = plate.size
# Icoana si textul CASA: x 96..262, y 82..134 (px de asset); aureola si umbra lor ajung mai departe,
# deci petecul e mai mare, dar ramane intre suruburi (x 66..86 si 313..333) si deasupra muchiei de jos.
EX0, EY0, EX1, EY1 = 90 - P_X0, 72 - Y0, 284 - P_X0, 150 - Y0
# Textura curata, la dreapta cuvantului si intre suruburi (x 286..312).
tile = art.crop((286, 72, 312, 150))
fill = Image.new("RGBA", (EX1 - EX0, EY1 - EY0))
x, flip = 0, False
while x < fill.width:
    t = tile.transpose(Image.FLIP_LEFT_RIGHT) if flip else tile
    fill.paste(t, (x, 0))
    x += tile.width
    flip = not flip
mask = Image.new("L", fill.size, 0)
mask.paste(Image.new("L", (fill.width - 16, fill.height - 16), 255), (8, 8))
mask = mask.filter(ImageFilter.GaussianBlur(3))
plate.paste(fill, (EX0, EY0), mask)

# --- 2. o placa ingusta: capetele + un mijloc -------------------------------------------------
mid_src_x = 120
mid = plate.crop((mid_src_x, 0, mid_src_x + (PLATE_W - 2 * CAP), ph))
narrow = Image.new("RGBA", (PLATE_W, ph))
narrow.paste(plate.crop((0, 0, CAP, ph)), (0, 0))
narrow.paste(mid, (CAP, 0))
narrow.paste(plate.crop((pw - CAP, 0, pw, ph)), (PLATE_W - CAP, 0))

# --- 3. randul nou ----------------------------------------------------------------------------
gap = art.crop((345, Y0, 345 + GAP_W, Y1))   # gol real dintre primele doua placi
row = Image.new("RGBA", (ROW_X1 - ROW_X0, ph))
for i in range(COUNT):
    x = i * (PLATE_W + GAP_W)
    row.paste(narrow, (x, 0))
    if i < COUNT - 1:
        row.paste(gap, (x + PLATE_W, 0))
out = art.copy()
out.paste(row, (ROW_X0, Y0))
out.save(out_path, "WEBP", quality=96, method=6)
print("ok", out_path, "placa", PLATE_W, "x", ph, "px")
