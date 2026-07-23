#!/usr/bin/env bash
set -euo pipefail

# Extrage miniaturile fotorealiste din referinta aprobata de utilizator.
# Valorile live, LED-urile si traseele nu sunt incluse in aceste resurse.
SOURCE="${1:-/opt/delete/retro-theme-v4.png}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$REPO_ROOT/android/app/src/main/res/drawable-nodpi"
WORK_DIR="$(mktemp -d /tmp/solar-retro-assets.XXXXXX)"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if [[ ! -f "$SOURCE" ]]; then
    printf 'Lipseste imaginea sursa: %s\n' "$SOURCE" >&2
    exit 1
fi
if ! command -v magick >/dev/null 2>&1; then
    printf 'Lipseste ImageMagick. Instaleaza pachetul imagemagick.\n' >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

make_asset() {
    local crop="$1"
    local width="$2"
    local height="$3"
    local name="$4"
    local draw="$5"

    magick "$SOURCE" -crop "$crop" +repage "$WORK_DIR/$name-crop.png"
    magick -size "${width}x${height}" xc:black \
        -fill white -stroke white -draw "$draw" \
        -blur 0x0.8 "$WORK_DIR/$name-mask.png"
    magick "$WORK_DIR/$name-crop.png" -alpha on \
        -fuzz 10% \
        -transparent '#554526' \
        -transparent '#544d34' \
        -transparent '#4e4932' \
        -transparent '#4d4a30' \
        -transparent '#615b3d' \
        "$WORK_DIR/$name-keyed.png"
    magick "$WORK_DIR/$name-mask.png" -alpha copy "$WORK_DIR/$name-mask-alpha.png"
    magick "$WORK_DIR/$name-keyed.png" "$WORK_DIR/$name-mask-alpha.png" \
        -compose DstIn -composite \
        -trim +repage "$OUTPUT_DIR/$name.png"
}

# Panou fotovoltaic pe suport, inclusiv soarele, piciorul si umbra originala.
make_asset "220x175+358+922" 220 175 "retro_flow_solar" "\
    stroke-width 0 polygon 34,48 180,48 162,128 18,122 \
    stroke-width 13 line 100,120 100,153 \
    stroke-width 9 line 70,157 137,157 \
    stroke-width 0 ellipse 62,148 146,170 0,360 \
    circle 166,43 183,43 \
    stroke-width 5 line 166,15 166,24 line 166,62 166,73 line 137,43 148,43 line 184,43 205,43 line 145,20 153,29 line 181,58 191,68 line 143,67 153,57 line 182,29 192,19 \
    fill black stroke black stroke-width 0 rectangle 0,0 28,66 polygon 0,118 18,124 48,175 0,175"

# Baterie grea cu borne, muchii laterale si umbra de contact.
make_asset "180x165+68+1167" 180 165 "retro_flow_battery" "\
    stroke-width 0 polygon 13,35 39,19 137,29 147,45 144,138 126,155 17,138 \
    ellipse 32,7 60,32 0,360 ellipse 94,11 126,36 0,360 \
    ellipse 4,137 155,164 0,360 \
    fill black stroke black polygon 126,0 180,0 180,38 148,38 138,29 126,23"

# Casa izometrica, horn, vegetatie si soclul original.
make_asset "230x175+368+1162" 230 175 "retro_flow_house" "\
    stroke-width 0 polygon 18,79 76,23 94,31 96,14 113,14 118,35 163,53 178,44 198,64 190,128 208,137 210,156 199,168 20,168 12,153 20,131 \
    ellipse 12,145 214,174 0,360 \
    fill black stroke black rectangle 0,70 19,92 rectangle 190,70 230,94"

# Stalp de retea: masca urmareste structura metalica si norii, nu un dreptunghi.
make_asset "220x235+683+1097" 220 235 "retro_flow_grid" "\
    stroke-width 0 ellipse 18,115 88,194 0,360 ellipse 65,108 142,195 0,360 ellipse 118,121 205,198 0,360 ellipse 35,151 190,218 0,360 \
    stroke-width 12 line 106,19 54,213 line 106,19 163,213 \
    stroke-width 10 line 65,74 151,74 line 43,111 174,111 line 28,151 191,151 line 48,190 173,190 \
    stroke-width 7 line 76,73 138,111 line 138,73 76,111 line 55,111 158,151 line 158,111 55,151 line 40,151 170,190 line 176,151 48,190 line 70,190 150,213 line 150,190 70,213 \
    stroke-width 6 line 65,78 65,94 line 151,78 151,94 line 43,115 43,132 line 174,115 174,132 \
    stroke-width 0 ellipse 27,207 197,233 0,360 \
    fill black stroke black rectangle 0,144 45,158"

printf 'Resurse extrase in %s:\n' "$OUTPUT_DIR"
printf '  %s\n' \
    "$OUTPUT_DIR/retro_flow_solar.png" \
    "$OUTPUT_DIR/retro_flow_battery.png" \
    "$OUTPUT_DIR/retro_flow_house.png" \
    "$OUTPUT_DIR/retro_flow_grid.png"
