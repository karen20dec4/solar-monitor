"""Placa goala pentru citirile BMS de pe TABLOU (v3.30): retro_dashboard_dial_blank.webp.

Pornește de la placa temperaturii (fereastra VFD cu "°C" pictat in dreapta jos) si sterge unitatea,
ca sa poata scrie Compose orice unitate (%, A, mV) peste ea. Unitatea e acoperita cu o bucata din
aceeasi fereastra, de langa ea (pe aceleasi randuri), topita pe margini.

Intrarea e placa ORIGINALA din repo la commit-ul dd0aaf5 (dupa v3.30 PNG-ul temperaturii e scos):

    git show dd0aaf5:android/app/src/main/res/drawable-nodpi/retro_dashboard_dial_temperature.png > /tmp/dial_temp.png
    python3 -I scripts/make-retro-blank-dial.py /tmp/dial_temp.png android/app/src/main/res/drawable-nodpi/retro_dashboard_dial_blank.webp
"""
import sys

from PIL import Image, ImageChops, ImageFilter

src_path, out_path = sys.argv[1], sys.argv[2]
plate = Image.open(src_path).convert("RGBA")
assert plate.size == (477, 190), plate.size

# "°C" ocupa x 353..396, y 95..124; aureola lui (glow) ajunge cu ~15 px mai departe.
X0, Y0, X1, Y1 = 326, 70, 424, 148
SHIFT = 100  # sursa: aceeasi fereastra, mai in stanga, pe aceleasi randuri
patch = plate.crop((X0 - SHIFT, Y0, X1 - SHIFT, Y1))

mask = Image.new("L", patch.size, 0)
inner = Image.new("L", (patch.width - 20, patch.height - 20), 255)
mask.paste(inner, (10, 10))
mask = mask.filter(ImageFilter.GaussianBlur(5))

out = plate.copy()
out.paste(patch, (X0, Y0), mask)
out.save(out_path, "WEBP", lossless=True, quality=100, method=6)

# Verificare: in zona unitatii nu mai ramane niciun pixel verde luminos.
px = out.load()
left = [
    (x, y)
    for y in range(Y0, Y1)
    for x in range(X0, X1)
    if px[x, y][1] > 110 and px[x, y][1] - (px[x, y][0] + px[x, y][2]) // 2 > 40
]
assert not left, f"au ramas {len(left)} pixeli verzi"
print("ok", out_path, out.size)
