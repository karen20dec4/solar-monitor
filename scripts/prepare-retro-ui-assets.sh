#!/usr/bin/env bash
set -euo pipefail

# Pregateste resursele Photoshop aprobate pentru tema Retro.
# Imaginile contin numai sasiul/patina; toate valorile, acul, LED-urile si
# zonele tactile sunt suprapuse dinamic in Jetpack Compose.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DESIGN_DIR="${1:-$REPO_ROOT/android/build/emulator-artifacts/design}"
OUTPUT_DIR="$REPO_ROOT/android/app/src/main/res/drawable-nodpi"
WORK_DIR="$(mktemp -d /tmp/solar-retro-ui.XXXXXX)"

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

if ! command -v magick >/dev/null 2>&1; then
    printf 'Lipseste ImageMagick (comanda magick).\n' >&2
    exit 1
fi

require_source() {
    local name="$1"
    if [[ ! -f "$DESIGN_DIR/$name" ]]; then
        printf 'Lipseste resursa sursa: %s\n' "$DESIGN_DIR/$name" >&2
        exit 1
    fi
}

require_dimensions() {
    local name="$1"
    local expected="$2"
    local actual
    actual="$(magick identify -format '%wx%h' "$DESIGN_DIR/$name")"
    if [[ "$actual" != "$expected" ]]; then
        printf 'Dimensiune invalida pentru %s: %s (asteptat %s).\n' "$name" "$actual" "$expected" >&2
        exit 1
    fi
}

has_real_transparency() {
    local source="$1"
    local channels minimum_alpha
    channels="$(magick identify -format '%[channels]' "$source")"
    [[ "$channels" == *a* ]] || return 1
    minimum_alpha="$(magick "$source" -alpha extract -format '%[fx:minima]' info:)"
    awk -v alpha="$minimum_alpha" 'BEGIN { exit !(alpha < 0.999) }'
}

for source in \
    background-cu-navbar.png \
    pag-tablou-background.png \
    pag-tablou-card-ACUM.png \
    pag-tablou-card-FLUX-ENERGETIC.png \
    pag-tablou-card-NAV.png; do
    require_source "$source"
done


require_dimensions background-cu-navbar.png 941x1672
require_dimensions pag-tablou-background.png 941x1672
require_dimensions pag-tablou-card-ACUM.png 1448x1086
require_dimensions pag-tablou-card-FLUX-ENERGETIC.png 1448x1086
require_dimensions pag-tablou-card-NAV.png 2172x724

mkdir -p "$OUTPUT_DIR"

make_background() {
    local source="$1"
    local output="$2"
    magick "$DESIGN_DIR/$source" \
        -strip \
        -quality 90 \
        -define webp:method=6 \
        "$WORK_DIR/$output"
    install -m 0644 "$WORK_DIR/$output" "$OUTPUT_DIR/$output"
}

make_card() {
    local source="$1"
    local output="$2"
    if has_real_transparency "$DESIGN_DIR/$source"; then
        printf 'Alpha Photoshop detectat: %s (fara eliminare automata a fundalului).\n' "$source"
        magick "$DESIGN_DIR/$source" \
            -filter Lanczos \
            -resize '1024x768!' \
            -strip \
            -quality 90 \
            -define webp:method=6 \
            -define webp:alpha-quality=95 \
            "$WORK_DIR/$output"
    else
        printf 'ATENTIE: %s este opac; folosesc temporar flood-fill pentru exteriorul negru.\n' "$source" >&2
        magick "$DESIGN_DIR/$source" \
            -alpha on \
            -fuzz 8% \
            -fill none \
            -draw 'color 0,0 floodfill' \
            -filter Lanczos \
            -resize '1024x768!' \
            -strip \
            -quality 90 \
            -define webp:method=6 \
            -define webp:alpha-quality=95 \
            "$WORK_DIR/$output"
    fi
    install -m 0644 "$WORK_DIR/$output" "$OUTPUT_DIR/$output"
}

make_navigation() {
    local source="$1"
    local output="$2"
    if has_real_transparency "$DESIGN_DIR/$source"; then
        printf 'Alpha Photoshop detectat: %s (fara eliminare automata a fundalului).\n' "$source"
        magick "$DESIGN_DIR/$source" \
            -trim +repage \
            -filter Lanczos \
            -resize '1024x' \
            -strip \
            -quality 90 \
            -define webp:method=6 \
            -define webp:alpha-quality=95 \
            "$WORK_DIR/$output"
    else
        printf 'ATENTIE: %s este opac; folosesc temporar flood-fill pentru exteriorul negru.\n' "$source" >&2
        magick "$DESIGN_DIR/$source" \
            -alpha on \
            -fuzz 8% \
            -fill none \
            -draw 'color 0,0 floodfill' \
            -trim +repage \
            -filter Lanczos \
            -resize '1024x' \
            -strip \
            -quality 90 \
            -define webp:method=6 \
            -define webp:alpha-quality=95 \
            "$WORK_DIR/$output"
    fi
    install -m 0644 "$WORK_DIR/$output" "$OUTPUT_DIR/$output"
}

make_background pag-tablou-background.png retro_dashboard_background_artwork.webp
make_background background-cu-navbar.png retro_page_background_artwork.webp
make_card pag-tablou-card-ACUM.png retro_dashboard_live_artwork.webp
make_card pag-tablou-card-FLUX-ENERGETIC.png retro_dashboard_flow_artwork.webp
make_navigation pag-tablou-card-NAV.png retro_bottom_navigation_artwork.webp

printf 'Resurse Retro pregatite in %s:\n' "$OUTPUT_DIR"
printf '  %s\n' \
    retro_dashboard_background_artwork.webp \
    retro_page_background_artwork.webp \
    retro_dashboard_live_artwork.webp \
    retro_dashboard_flow_artwork.webp \
    retro_bottom_navigation_artwork.webp
