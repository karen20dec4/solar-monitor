"""Mod demonstratie (CASA_DEMO=1): prize false, senzori fixi, istoric sintetic.

Nimic de aici nu scrie in InfluxDB si nu vorbeste cu cloud-ul Tuya: datele de productie
nu trebuie sa primeasca niciodata puncte inventate.
"""
import math
import time
from datetime import datetime
from typing import Callable, Optional
from zoneinfo import ZoneInfo

from .devices import DeviceInfo
from .plugs import FakeDriver

# Putere realista cand priza e pornita (W), dupa numele din aplicatia Tuya.
DEMO_WATTS = {"Boiler": 2000.0, "Living sus": 60.0, "Priza cam Georgi": 1650.0}
DEMO_VOLTS = 231.0

# (temperatura C, umiditate %) dupa id-ul senzorului din `python -m casa demo-devices`.
DEMO_SENSOR_VALUES = {
    "demo_sensor_etaj": (20.3, 54.0),
    "demo_sensor_afara": (6.6, 83.0),
    "bfd920a7665be19c4cjytc": (19.8, 50.0),
}
DEMO_SENSOR_AGE_S = 240

_RANGES = {"2h": (2 * 3600, 120), "1d": (86400, 600), "7d": (7 * 86400, 3600)}


class DemoDriver(FakeDriver):
    """FakeDriver care raporteaza consum realist la prizele cu masurare."""

    def __init__(self, device: DeviceInfo):
        super().__init__({str(device.dps.get("switch", 1)): False})
        self.device = device
        self.watts = DEMO_WATTS.get(device.name) if "power" in device.dps else None

    def status(self) -> dict:
        dps = super().status()
        if self.watts is not None:
            on = bool(dps.get(str(self.device.dps.get("switch", 1))))
            power = self.watts if on else 0.0
            volts = DEMO_VOLTS if on else 0.0
            sc = self.device.scales
            dps[str(self.device.dps["power"])] = int(round(power * 10 ** sc.get("power", 0)))
            if "voltage" in self.device.dps:
                dps[str(self.device.dps["voltage"])] = int(round(volts * 10 ** sc.get("voltage", 0)))
            if "current" in self.device.dps:
                amps_ma = power / DEMO_VOLTS * 1000.0
                dps[str(self.device.dps["current"])] = int(round(amps_ma * 10 ** sc.get("current", 0)))
        return dps


class DemoCloud:
    """Senzori fixi; starea cloud mereu "ok"; `updated` = acum - 4 minute."""

    def __init__(self, sensor_devices: list, clock: Callable[[], float] = time.time):
        self._devices = [d for d in sensor_devices if d.kind == "sensor"]
        self._clock = clock
        self.on_sensor_update = None

    def sensors(self) -> list:
        out = []
        for d in self._devices:
            temp, hum = DEMO_SENSOR_VALUES.get(d.id, (20.0, 50.0))
            out.append({"id": d.id, "name": d.name, "temperature": temp, "humidity": hum,
                        "battery": 100, "updated": self._clock() - DEMO_SENSOR_AGE_S})
        return sorted(out, key=lambda s: s["name"] or "")

    def state(self) -> str:
        return "ok"

    def start(self) -> None:
        pass

    def stop(self) -> None:
        pass


def _temp_at(device_id: str, t: float) -> float:
    base = DEMO_SENSOR_VALUES.get(device_id, (20.0, 50.0))[0]
    return round(base + 1.8 * math.sin(2 * math.pi * (t - 6 * 3600) / 86400), 2)


def _hum_at(device_id: str, t: float) -> float:
    base = DEMO_SENSOR_VALUES.get(device_id, (20.0, 50.0))[1]
    return round(base - 4.0 * math.sin(2 * math.pi * (t - 6 * 3600) / 86400), 1)


class DemoInflux:
    """Aceeasi suprafata ca CasaInflux, dar istoric sintetic si fara nicio scriere."""

    def __init__(self, plug_watts: Optional[dict] = None, tz: str = "Europe/Bucharest",
                 clock: Callable[[], float] = time.time):
        self._watts = dict(plug_watts or {})
        self._tz = ZoneInfo(tz)
        self._clock = clock

    # --- fara scrieri ---
    def write_plug(self, device, reading) -> None:
        pass

    def write_sensor(self, sensor: dict) -> None:
        pass

    def start(self) -> None:
        pass

    def close(self) -> None:
        pass

    # --- interogari sintetice ---
    def _grid(self, span: int, step: int) -> list:
        end = int(self._clock() // step) * step
        return [float(t) for t in range(end - span + step, end + 1, step)]

    def sensor_history(self, device_id: str, range_: str) -> dict:
        if range_ not in _RANGES:
            raise ValueError("interval necunoscut")
        span, step = _RANGES[range_]
        temp = [[t, _temp_at(device_id, t)] for t in self._grid(span, step)]
        hum = [[t, _hum_at(device_id, t)] for t in self._grid(span, step)]

        def ext(points, fn):
            t, v = fn(points, key=lambda p: p[1])
            return {"v": v, "t": t}

        return {"temperature": temp, "humidity": hum,
                "stats": {"temp_min": ext(temp, min), "temp_max": ext(temp, max),
                          "hum_min": ext(hum, min), "hum_max": ext(hum, max)}}

    def sensor_spark(self, device_id: str) -> list:
        return [[t, _temp_at(device_id, t)] for t in self._grid(24 * 300, 300)]

    def energy_today(self, device_id: str) -> Optional[float]:
        watts = self._watts.get(device_id)
        if not watts:
            return None
        now = datetime.fromtimestamp(self._clock(), self._tz)
        hours = now.hour + now.minute / 60.0
        return round(watts / 1000.0 * hours * 0.15, 2)

