#!/usr/bin/env python3
"""Verifica pe un dump uiautomator ca elementele noi ale paginii nu se suprapun si nu ies din ecran.

Utilizare: check-layout.py <window.xml> <TABLOU|SISTEM|SETARI>
Iese cu 1 si tipareste motivul daca o regula e incalcata.
"""
import re
import sys

xml = open(sys.argv[1], encoding="utf-8").read()
tab = sys.argv[2]


def nodes():
    for m in re.finditer(r'<node [^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        yield m.group(1), tuple(int(v) for v in m.groups()[1:])


def find(prefix):
    for desc, b in nodes():
        if desc.startswith(prefix):
            return b
    raise SystemExit(f"[{tab}] nu gasesc elementul cu descrierea '{prefix}*'")


def need(cond, message):
    if not cond:
        raise SystemExit(f"[{tab}] {message}")


screen_w = max(b[2] for _, b in nodes())
nav = find(f"Tab {tab}")  # bara de navigare a aplicatiei (deasupra celei de sistem)

if tab == "TABLOU":
    strip = find("Baterie BMS")
    bat = find("Baterie ")           # placa Bat; "Baterie BMS" e gasit separat mai sus
    inv = find("Consum propriu invertor")
    need(strip[1] >= max(bat[3], inv[3]) - 2, f"banda BMS {strip} se suprapune cu placile Bat/Inv {bat} {inv}")
    need(strip[3] <= nav[1] + 2, f"banda BMS {strip} intra in bara de navigare {nav}")
    need(0 <= strip[0] and strip[2] <= screen_w, f"banda BMS {strip} iese din latime")
elif tab == "SISTEM":
    mon = find("Monitor sistem")
    bms = find("Baterie BMS")
    need(bms[1] >= mon[1] and bms[3] <= mon[3] + 2, f"monitorul BMS {bms} iese din panou {mon}")
    need(bms[3] <= nav[1] + 2, f"monitorul BMS {bms} intra in bara de navigare {nav}")
elif tab == "SETARI":
    card = find("Setari alarma consum mare")
    row = find("Setari alarma baterie")
    need(row[1] >= card[3] - 2, f"randul Alarma baterie {row} se suprapune cu cardul {card}")
    need(row[3] <= nav[1] + 2, f"randul Alarma baterie {row} intra in bara de navigare {nav}")
else:
    raise SystemExit(f"tab necunoscut: {tab}")
print(f"[{tab}] aranjare ok")
