#!/usr/bin/env python3
"""Intrari ARP statice pentru prizele Tuya care nu raspund la cereri ARP.

Prizele "WiFi Plug" (Boiler, Living sus, Priza cam Georgi; 2026-11-05) isi trimit anunturile UDP
in LAN, dar nu raspund la cererile ARP ale serverului: fara intrare ARP, serverul nu le poate
deschide conexiunea TCP 6668 (ping si TCP esueaza, `ip neigh` arata FAILED). Cu o intrare statica
(IP -> MAC) merg normal.

Scriptul asculta ~25 s anunturile Tuya (UDP 6666/6667/7000) pe interfata LAN, ia IP-ul si MAC-ul
sursei din cadrul Ethernet si pune `ip neigh replace <ip> lladdr <mac> nud permanent`. Tine minte
ce a pus (STATE_FILE): daca un MAC apare pe alt IP, sau un IP nu mai e vazut de MAX_AGE_S, sterge
intrarea veche, ca un IP refolosit de alt aparat sa nu ramana legat de MAC-ul prizei.
Ruleaza pe host (root), din solar-tuya-arp.timer. Nu trimite nimic in retea: doar asculta.
"""
import ipaddress
import json
import os
import socket
import struct
import subprocess
import sys
import time

IFACE = os.environ.get("TUYA_ARP_IFACE", "enp1s0")
LAN = ipaddress.ip_network(os.environ.get("TUYA_ARP_LAN", "192.168.1.0/24"))
LISTEN_S = float(os.environ.get("TUYA_ARP_LISTEN_S", "25"))
STATE_FILE = os.environ.get("TUYA_ARP_STATE", "/var/lib/solar-tuya-arp/state.json")
MAX_AGE_S = 24 * 3600
TUYA_PORTS = (6666, 6667, 7000)


def parse_frame(pkt: bytes):
    """(ip, mac) pentru un anunt Tuya IPv4/UDP, altfel None."""
    if len(pkt) < 42 or pkt[12:14] != b"\x08\x00" or pkt[23] != 17:
        return None
    ihl = (pkt[14] & 0x0F) * 4
    if len(pkt) < 14 + ihl + 8:
        return None
    _sport, dport = struct.unpack("!HH", pkt[14 + ihl:18 + ihl])
    if dport not in TUYA_PORTS:
        return None
    ip = socket.inet_ntoa(pkt[26:30])
    mac = ":".join(f"{b:02x}" for b in pkt[6:12])
    return ip, mac


def plan(state: dict, seen: dict, now: float, lan=LAN):
    """Din starea veche {ip: {mac, last_seen}} si ce s-a vazut acum {ip: mac}:
    (de pus [(ip, mac)], de sters [ip], starea noua)."""
    seen = {ip: mac for ip, mac in seen.items() if ipaddress.ip_address(ip) in lan}
    new_state = {ip: dict(v) for ip, v in state.items()}
    adds, deletes = [], []
    for ip, mac in sorted(seen.items()):
        new_state[ip] = {"mac": mac, "last_seen": now}
        adds.append((ip, mac))
    seen_macs = {mac: ip for ip, mac in seen.items()}
    for ip, v in sorted(state.items()):
        if ip in seen:
            continue
        moved = v.get("mac") in seen_macs
        stale = now - float(v.get("last_seen", 0)) > MAX_AGE_S
        if moved or stale:
            deletes.append(ip)
            new_state.pop(ip, None)
    return adds, deletes, new_state


def listen(iface: str, seconds: float) -> dict:
    sock = socket.socket(socket.AF_PACKET, socket.SOCK_RAW, socket.ntohs(0x0800))
    try:
        sock.bind((iface, 0))
        sock.settimeout(1.0)
        seen = {}
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                pkt = sock.recv(2048)
            except socket.timeout:
                continue
            got = parse_frame(pkt)
            if got:
                seen[got[0]] = got[1]
        return seen
    finally:
        sock.close()


def load_state(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (FileNotFoundError, ValueError):
        return {}


def save_state(path: str, state: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=1, sort_keys=True)
    os.replace(tmp, path)


def main() -> int:
    state = load_state(STATE_FILE)
    seen = listen(IFACE, LISTEN_S)
    adds, deletes, new_state = plan(state, seen, time.time())
    for ip in deletes:
        subprocess.run(["ip", "neigh", "del", ip, "dev", IFACE], check=False)
        print(f"sters {ip} (MAC mutat pe alt IP sau nevazut de 24 h)")
    for ip, mac in adds:
        subprocess.run(["ip", "neigh", "replace", ip, "lladdr", mac, "dev", IFACE, "nud", "permanent"], check=True)
    save_state(STATE_FILE, new_state)
    print(f"{len(adds)} prize Tuya vazute: " + ", ".join(f"{ip}={mac}" for ip, mac in adds))
    return 0


if __name__ == "__main__":
    sys.exit(main())
