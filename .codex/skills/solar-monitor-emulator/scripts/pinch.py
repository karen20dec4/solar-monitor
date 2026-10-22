#!/usr/bin/env python3
"""Ciupire cu doua degete in emulator, prin sendevent (adb trebuie sa ruleze ca root: `adb root`).

  pinch.py hold    CX CY D0 D1   pune doua degete la distanta D0, le desface pana la D1 si le LASA jos
  pinch.py release               ridica ambele degete

Coordonatele sunt in pixeli de ecran (dupa `wm size`). Se foloseste dispozitivul tactil principal
(`/dev/input/event2`, "virtio_input_multi_touch_1" pe AVD-ul Pixel 6).
"""
import subprocess
import sys

ADB = "/opt/android-sdk/platform-tools/adb"
DEV = "/dev/input/event2"


def screen_size():
    out = subprocess.run([ADB, "shell", "wm", "size"], capture_output=True, text=True, check=True).stdout
    # "Override size" (profilul) are prioritate fata de "Physical size".
    sizes = [line.split(":")[1].strip() for line in out.splitlines() if "size" in line]
    w, h = sizes[-1].split("x")
    return int(w), int(h)


def ev(t, c, v):
    return f"sendevent {DEV} {t} {c} {v}"


def run(cmds):
    subprocess.run([ADB, "shell", "sh"], input="\n".join(cmds).encode(), check=True)


def hold(cx, cy, d0, d1, steps=12):
    w, h = screen_size()
    nx = lambda x: int(x / w * 32767)
    ny = lambda y: int(y / h * 32767)
    cmds = []
    for i in range(steps + 1):
        d = d0 + (d1 - d0) * i / steps
        for slot, x in ((0, cx - d / 2), (1, cx + d / 2)):
            cmds.append(ev(3, 47, slot))                   # ABS_MT_SLOT
            if i == 0:
                cmds.append(ev(3, 57, 100 + slot))         # ABS_MT_TRACKING_ID
            cmds += [ev(3, 53, nx(x)), ev(3, 54, ny(cy)), ev(3, 58, 200)]
        cmds.append(ev(0, 0, 0))                           # SYN_REPORT
    run(cmds)


def release():
    cmds = []
    for slot in (0, 1):
        cmds += [ev(3, 47, slot), ev(3, 57, -1)]
    cmds.append(ev(0, 0, 0))
    run(cmds)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "hold" and len(sys.argv) == 6:
        hold(*(float(a) for a in sys.argv[2:6]))
    elif mode == "release":
        release()
    else:
        sys.exit(__doc__)
