#!/usr/bin/env python3
"""Verifica pe un dump uiautomator ca elementele noi ale paginii nu se suprapun si nu ies din ecran.

Utilizare: check-layout.py <window.xml> <TABLOU|ENERGIE|SISTEM|SETARI>
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
elif tab == "ENERGIE":
    # Randul "CE urmaresti": patru placi (CASA, PANOURI, BATERIE, RETEA), una langa alta, in latime.
    subjects = sorted(b for d, b in nodes() if re.match(r"Grafic (casa|panouri|baterie|retea)", d))
    need(len(subjects) == 4, f"trebuie 4 placi de subiect (Grafic ...), am {len(subjects)}")
    for a, b in zip(subjects, subjects[1:]):
        need(b[0] >= a[2] - 1, f"placile de subiect {a} si {b} se suprapun")
    need(all(0 <= s[0] and s[2] <= screen_w for s in subjects), f"placile de subiect {subjects} ies din latime")
    need(len({s[1] for s in subjects}) == 1 and len({s[3] for s in subjects}) == 1, "placile de subiect nu sunt aliniate")
    mode = find("Mod ")
    need(mode[1] >= subjects[0][3] - 2, f"randul de moduri {mode} se suprapune cu placile {subjects[0]}")
    need(find("Interval ")[1] >= mode[3] - 2, "butoanele de interval se suprapun cu randul de moduri")
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
