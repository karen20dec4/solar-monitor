"""Registrul dispozitivelor Tuya: devices.json (scris de sincronizare) + devices.local.json (suprascrieri)."""
import json
import logging
import os
import re
import time
from dataclasses import asdict, dataclass, field
from typing import Literal, Optional

from .store import JsonStore

log = logging.getLogger("casa.devices")

DEVICES_FILE = "devices.json"
LOCAL_FILE = "devices.local.json"

# IP si protocol din scanarea retelei (id Tuya = gwId), confirmate de `sync` 2026-11-05:
# Living sus .244, Priza cam Georgi .104, Boiler .204, Bucatarie .185, Lumina Curte .158.
BUILTIN_DEFAULTS = {
    "bf5cc0f2db15b9eab2a0zy": {"ip": "192.168.1.244", "version": "3.3"},
    "bf3bac46d1a367e845thx2": {"ip": "192.168.1.104", "version": "3.3"},
    "bf7b3c62e47bfd15fczfov": {"ip": "192.168.1.204", "version": "3.3"},
    "bfc30380ff8a29c0d88wcz": {"ip": "192.168.1.185", "version": "3.4"},
    "bf3897ad46cd66d32cww3j": {"ip": "192.168.1.158", "version": "3.4"},
}

FALLBACK_DPS = {"switch": 1, "countdown": 9, "current": 18, "power": 19, "voltage": 20}
FALLBACK_SCALES = {"power": 1, "voltage": 1, "current": 0}

_CODE_TO_KEY = {
    "switch_1": "switch", "switch": "switch",
    "countdown_1": "countdown", "countdown": "countdown",
    "cur_power": "power",
    "cur_voltage": "voltage",
    "cur_current": "current",
}
_SCALED = ("power", "voltage", "current")


@dataclass
class DeviceInfo:
    id: str
    name: str
    kind: Literal["plug", "sensor", "other"]
    local_key: Optional[str] = field(repr=False)
    ip: Optional[str]
    version: Optional[str]
    dps: dict = field(default_factory=dict)
    scales: dict = field(default_factory=dict)
    category: str = ""
    raw_name: str = ""


_TIME_RANGE = re.compile(r"[\s:\-]*\d{1,2}[:.]\d{2}\s*-\s*\d{1,2}[:.]\d{2}")


def clean_name(raw: str) -> str:
    """Scoate intervalul orar din numele dat in aplicatia Tuya ("Boiler 10.30 - 16.00" -> "Boiler")."""
    s = _TIME_RANGE.sub("", raw)
    return s.strip(" \t:;,.-")


def dp_map_from_strategy(local_strategy: dict) -> tuple:
    """Din `CustomerDevice.local_strategy` (SDK sharing) scoate {cod: nr DP} si {cod: zecimale}."""
    if not local_strategy:
        return dict(FALLBACK_DPS), dict(FALLBACK_SCALES)
    dps: dict = {}
    scales: dict = {}
    for dp_id, item in local_strategy.items():
        if not isinstance(item, dict):
            continue
        key = _CODE_TO_KEY.get(item.get("status_code"))
        if key is None or key in dps:
            continue
        try:
            dps[key] = int(dp_id)
        except (TypeError, ValueError):
            continue
        if key in _SCALED:
            scales[key] = _scale_of(item)
    for key in _SCALED:
        if key in dps:
            scales.setdefault(key, 0)
    return dps, scales


def _scale_of(item: dict) -> int:
    cfg = item.get("config_item") or {}
    desc = cfg.get("valueDesc") if isinstance(cfg, dict) else None
    try:
        if isinstance(desc, str):
            desc = json.loads(desc)
        return int(desc.get("scale", 0))
    except (ValueError, TypeError, AttributeError):
        return 0


def save_devices(data_dir: str, devices: list, raw_names: Optional[dict] = None,
                 categories: Optional[dict] = None) -> None:
    raw_names = raw_names or {}
    categories = categories or {}
    out = []
    for d in devices:
        entry = asdict(d)
        entry["raw_name"] = raw_names.get(d.id, d.raw_name)
        entry["category"] = categories.get(d.id, d.category)
        out.append(entry)
    JsonStore(os.path.join(data_dir, DEVICES_FILE)).save(
        {"devices": out, "synced_at": time.time()}, mode=0o600)


def _valid_local(over) -> bool:
    """Intrare din devices.local.json: dict cu `ip`/`version`/`name` optionale, doar text."""
    return isinstance(over, dict) and all(
        isinstance(over[k], str) for k in ("ip", "version", "name") if k in over)


def load_devices(data_dir: str) -> list:
    """Citeste devices.json + devices.local.json; intrarile gresite se sar cu avertisment (nu opresc pornirea)."""
    data = JsonStore(os.path.join(data_dir, DEVICES_FILE)).load({"devices": []})
    local = JsonStore(os.path.join(data_dir, LOCAL_FILE)).load({})
    if not isinstance(local, dict):
        log.warning("%s: nu e un obiect JSON; il ignor", LOCAL_FILE)
        local = {}
    known = {f for f in DeviceInfo.__dataclass_fields__}
    entries = data.get("devices", []) if isinstance(data, dict) else []
    if not isinstance(entries, list):
        log.warning("%s: 'devices' nu e o lista; o ignor", DEVICES_FILE)
        entries = []
    result = []
    for entry in entries:
        try:
            if not isinstance(entry, dict):
                raise TypeError("intrarea nu e un obiect")
            dev = DeviceInfo(**{k: v for k, v in entry.items() if k in known})
            if not isinstance(dev.id, str) or not dev.id or not isinstance(dev.name, str):
                raise ValueError("id/name lipsa sau nu sunt text")
        except (TypeError, ValueError) as exc:
            ident = entry.get("id") if isinstance(entry, dict) else None
            log.warning("%s: intrare gresita (id=%s): %s; o sar", DEVICES_FILE, ident, exc)
            continue
        defaults = BUILTIN_DEFAULTS.get(dev.id, {})
        if not dev.ip:
            dev.ip = defaults.get("ip")
        if not dev.version:
            dev.version = defaults.get("version")
        over = local.get(dev.id)
        if over is not None and not _valid_local(over):
            log.warning("%s: intrare gresita pentru %s (asteptat obiect cu ip/version/name text); o sar",
                        LOCAL_FILE, dev.id)
            over = None
        for key in ("ip", "version", "name"):
            if over and over.get(key):
                setattr(dev, key, over[key])
        result.append(dev)
    return result


def apply_scan(data_dir: str, found: dict) -> tuple:
    """Scrie in devices.local.json IP-ul si protocolul gasite la scanarea retelei pentru prizele din devices.json.

    `found`: {id Tuya: {"ip": ..., "version": ...}} (din `tinytuya.deviceScan(byID=True)`). Se scrie doar
    ce difera de ce stie deja `load_devices`; numele pus de mana in devices.local.json ramane.
    Intoarce (schimbari [(nume, ip, protocol)], necunoscute [(id, ip)] care lipsesc din devices.json).
    """
    devices = {d.id: d for d in load_devices(data_dir)}
    updates, changes, unknown = {}, [], []
    for dev_id, info in sorted(found.items()):
        ip = info.get("ip") if isinstance(info, dict) else None
        if not isinstance(ip, str) or not ip:
            continue
        version = info.get("version")
        version = str(version) if version else None
        dev = devices.get(dev_id)
        if dev is None:
            unknown.append((dev_id, ip))
            continue
        if dev.kind != "plug" or (dev.ip == ip and (version is None or dev.version == version)):
            continue
        updates[dev_id] = {"ip": ip, **({"version": version} if version else {})}
        changes.append((dev.name, ip, version or dev.version))
    if updates:
        def merge(local):
            if not isinstance(local, dict):
                raise ValueError(f"{LOCAL_FILE} nu e un obiect JSON; nu il modific")
            for dev_id, upd in updates.items():
                entry = local.get(dev_id)
                entry = entry if _valid_local(entry) else {}
                entry.update(upd)
                local[dev_id] = entry
        JsonStore(os.path.join(data_dir, LOCAL_FILE)).update(merge, {})
    return changes, unknown
