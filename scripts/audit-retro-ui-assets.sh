#!/usr/bin/env bash
set -euo pipefail

# Verifica exporturile Photoshop fara sa le modifice. Modul implicit este doar
# raport; --strict intoarce cod nenul pana cand toate cerintele sunt indeplinite.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STRICT=false
if [[ "${1:-}" == "--strict" ]]; then
    STRICT=true
    shift
fi
DESIGN_DIR="${1:-$REPO_ROOT/android/build/emulator-artifacts/design}"
ISSUES=0

report_issue() {
    printf 'NECESITA CURATARE: %s\n' "$1"
    ISSUES=$((ISSUES + 1))
}

check_file() {
    local name="$1"
    local expected_dimensions="$2"
    local transparent="$3"
    local path="$DESIGN_DIR/$name"
    local actual_dimensions colorspace channels minimum_alpha corners

    if [[ ! -f "$path" ]]; then
        report_issue "$name lipseste din $DESIGN_DIR"
        return
    fi

    actual_dimensions="$(magick identify -format '%wx%h' "$path")"
    if [[ "$actual_dimensions" != "$expected_dimensions" ]]; then
        report_issue "$name are $actual_dimensions; trebuie $expected_dimensions"
    else
        printf 'OK DIMENSIUNI: %s (%s)\n' "$name" "$actual_dimensions"
    fi

    colorspace="$(magick identify -format '%[colorspace]' "$path")"
    if [[ "${colorspace,,}" != "srgb" ]]; then
        report_issue "$name foloseste spatiul de culoare $colorspace; trebuie sRGB"
    else
        printf 'OK CULOARE: %s (sRGB)\n' "$name"
    fi

    if [[ "$transparent" != "yes" ]]; then
        return
    fi

    channels="$(magick identify -format '%[channels]' "$path")"
    if [[ "$channels" != *a* ]]; then
        report_issue "$name este opac; exporta PNG-32 cu Transparency si Matte: None"
        return
    fi

    minimum_alpha="$(magick "$path" -alpha extract -format '%[fx:minima]' info:)"
    if ! awk -v alpha="$minimum_alpha" 'BEGIN { exit !(alpha < 0.999) }'; then
        report_issue "$name are canal alpha, dar niciun pixel transparent"
        return
    fi

    corners="$(magick "$path" -format '%[fx:p{0,0}.a] %[fx:p{w-1,0}.a] %[fx:p{0,h-1}.a] %[fx:p{w-1,h-1}.a]' info:)"
    if ! awk -v values="$corners" 'BEGIN {
        count = split(values, alpha, " ")
        for (index = 1; index <= count; index++) {
            if (alpha[index] > 0.01) exit 1
        }
    }'; then
        report_issue "$name nu are toate cele patru colturi complet transparente"
    else
        printf 'OK ALPHA: %s (exterior transparent)\n' "$name"
    fi
}

if ! command -v magick >/dev/null 2>&1; then
    printf 'Lipseste ImageMagick (comanda magick).\n' >&2
    exit 1
fi

check_file pag-tablou-card-ACUM.png 1448x1086 yes
check_file pag-tablou-card-FLUX-ENERGETIC.png 1448x1086 yes
check_file pag-tablou-card-NAV.png 2172x724 yes
check_file pag-tablou-background.png 941x1672 no
check_file background-cu-navbar.png 941x1672 no

printf '\nRezultat: %d problema(e) detectata(e).\n' "$ISSUES"
printf 'Franjurii negri de 1-2 px de pe muchii necesita in continuare inspectie vizuala la 200-400%%.\n'

if $STRICT && (( ISSUES > 0 )); then
    exit 1
fi
