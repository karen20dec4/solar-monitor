"""Scriere si interogare InfluxDB pentru CASA (prize si senzori).

Masuratori: `casa_plug` (tag-uri device_id, name; campuri on, online, power, voltage, current)
si `casa_sensor` (tag-uri device_id, name; campuri temperature, humidity, battery).
Caile de scriere nu ridica niciodata exceptii: punctele raman intr-un buffer marginit si sunt
reincercate la cel mult 10 s.
"""
import logging
import re
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Optional
from zoneinfo import ZoneInfo

from influxdb_client import InfluxDBClient, Point, WritePrecision

from .config import Config
from .devices import DeviceInfo
from .plugs import PlugReading

log = logging.getLogger("casa.influx")

BUFFER_LIVE = 900
BUFFER_HISTORY = 1440
RETRY_S = 10.0

PLUG = "casa_plug"
SENSOR = "casa_sensor"

_ID = re.compile(r"^[A-Za-z0-9_]+$")

# interval -> (bucket logic, start Flux, fereastra de mediere)
_RANGES = {
    "2h": ("live", "-2h", "2m"),
    "1d": ("live", "-1d", "10m"),
    "7d": ("history", "-7d", "1h"),
}
_SENSOR_FIELDS = ("temperature", "humidity", "battery")


def _check_id(device_id: str) -> str:
    if not isinstance(device_id, str) or not _ID.match(device_id):
        raise ValueError("device_id invalid")
    return device_id


def _point(measurement: str, device_id: str, name: str, fields: dict, at: float) -> Point:
    p = Point(measurement).tag("device_id", device_id).tag("name", name or device_id)
    for k, v in fields.items():
        p = p.field(k, float(v))
    return p.time(int(at * 1_000_000_000), WritePrecision.NS)


class _Minute:
    """Acumuleaza citirile unei prize intr-un minut."""

    def __init__(self, minute: int, device: DeviceInfo):
        self.minute = minute
        self.device = device
        self.on: Optional[float] = None
        self.online: Optional[float] = None
        self.sums = {"power": 0.0, "voltage": 0.0, "current": 0.0}
        self.counts = {"power": 0, "voltage": 0, "current": 0}

    def add(self, r: PlugReading) -> None:
        if r.on is not None:
            self.on = float(r.on) if self.on is None else min(self.on, float(r.on))
        o = float(bool(r.online))
        self.online = o if self.online is None else min(self.online, o)
        for k in self.sums:
            v = getattr(r, k)
            if v is not None:
                self.sums[k] += v
                self.counts[k] += 1

    def fields(self) -> dict:
        f = {}
        if self.on is not None:
            f["on"] = self.on
        if self.online is not None:
            f["online"] = self.online
        for k, n in self.counts.items():
            if n:
                f[k] = self.sums[k] / n
        return f


class CasaInflux:
    def __init__(self, cfg: Config, client=None, clock=time.time):
        self.cfg = cfg
        self._client = client
        self._clock = clock
        self._lock = threading.Lock()
        self._flush_lock = threading.Lock()
        self._buf = {"live": deque(maxlen=BUFFER_LIVE), "history": deque(maxlen=BUFFER_HISTORY)}
        self._minutes: dict = {}
        self._retry_at = 0.0
        self._tz = ZoneInfo(cfg.tz)

    # ---- client ----
    def _cli(self):
        if self._client is None:
            self._client = InfluxDBClient(url=self.cfg.influx_url, token=self.cfg.influx_token,
                                          org=self.cfg.influx_org, timeout=5000)
        return self._client

    def _bucket(self, which: str) -> str:
        return self.cfg.bucket_live if which == "live" else self.cfg.bucket_history

    # ---- scriere ----
    def write_plug(self, device: DeviceInfo, reading: PlugReading) -> None:
        try:
            fields = {"online": float(bool(reading.online))}
            if reading.on is not None:
                fields["on"] = float(reading.on)
            for k in ("power", "voltage", "current"):
                v = getattr(reading, k)
                if v is not None:
                    fields[k] = v
            done = None
            with self._lock:
                self._buf["live"].append(_point(PLUG, device.id, device.name, fields, reading.at))
                minute = int(reading.at // 60)
                cur = self._minutes.get(device.id)
                if cur is not None and minute != cur.minute:
                    done = cur
                    cur = None
                if cur is None:
                    cur = self._minutes[device.id] = _Minute(minute, device)
                cur.add(reading)
                if done is not None:
                    self._buf["history"].append(_point(
                        PLUG, done.device.id, done.device.name, done.fields(), done.minute * 60))
        except Exception as e:  # noqa: BLE001
            log.warning("pregatire punct priza esuata: %s", e)
            return
        self._flush(False)

    def write_sensor(self, sensor: dict) -> None:
        try:
            fields = {k: sensor[k] for k in _SENSOR_FIELDS if sensor.get(k) is not None}
            if not fields:
                return
            at = sensor.get("updated")
            at = float(at) if at is not None else self._clock()
            with self._lock:
                for which in ("live", "history"):
                    self._buf[which].append(_point(
                        SENSOR, sensor["id"], sensor.get("name", ""), fields, at))
        except Exception as e:  # noqa: BLE001
            log.warning("pregatire punct senzor esuata: %s", e)
            return
        self._flush(False)

    def flush(self) -> None:
        self._flush(True)

    def _flush(self, force: bool) -> None:
        if not force and self._clock() < self._retry_at:
            return
        if not self._flush_lock.acquire(blocking=False):
            return
        try:
            for which in ("live", "history"):
                with self._lock:
                    batch = list(self._buf[which])
                    self._buf[which].clear()
                if not batch:
                    continue
                try:
                    self._cli().write_api().write(
                        bucket=self._bucket(which), org=self.cfg.influx_org, record=batch)
                except Exception as e:  # noqa: BLE001
                    self._retry_at = self._clock() + RETRY_S
                    log.warning("scriere InfluxDB esuata (%s puncte in asteptare): %s",
                                len(batch), e)
                    with self._lock:
                        # punctele vechi inapoi in fata; deque-ul marginit le taie pe cele mai vechi
                        self._buf[which].extendleft(reversed(batch))
        except Exception as e:  # noqa: BLE001
            self._retry_at = self._clock() + RETRY_S
            log.warning("flush InfluxDB esuat: %s", e)
        finally:
            self._flush_lock.release()

    # ---- interogari ----
    def _rows(self, flux: str):
        for table in self._cli().query_api().query(flux, org=self.cfg.influx_org):
            for rec in table.records:
                yield rec

    def sensor_history(self, device_id: str, range_: str) -> dict:
        _check_id(device_id)
        if range_ not in _RANGES:
            raise ValueError("interval necunoscut")
        which, start, every = _RANGES[range_]
        bucket = self._bucket(which)
        base = (f'from(bucket: "{bucket}") |> range(start: {start}) '
                f'|> filter(fn: (r) => r._measurement == "{SENSOR}" and r.device_id == "{device_id}" '
                f'and (r._field == "temperature" or r._field == "humidity"))')
        series = {"temperature": [], "humidity": []}
        for rec in self._rows(
                base + f' |> aggregateWindow(every: {every}, fn: mean, createEmpty: false)'):
            f = rec.get_field()
            if f in series and rec.get_value() is not None:
                series[f].append([rec.get_time().timestamp(), float(rec.get_value())])
        for v in series.values():
            v.sort()
        raw = {"temperature": [], "humidity": []}
        for rec in self._rows(base):
            f = rec.get_field()
            if f in raw and rec.get_value() is not None:
                raw[f].append((rec.get_time().timestamp(), float(rec.get_value())))

        def ext(points, fn):
            if not points:
                return None
            t, v = fn(points, key=lambda p: p[1])
            return {"v": v, "t": t}

        return {
            "temperature": series["temperature"],
            "humidity": series["humidity"],
            "stats": {
                "temp_min": ext(raw["temperature"], min), "temp_max": ext(raw["temperature"], max),
                "hum_min": ext(raw["humidity"], min), "hum_max": ext(raw["humidity"], max),
            },
        }

    def sensor_spark(self, device_id: str) -> list:
        _check_id(device_id)
        flux = (f'from(bucket: "{self._bucket("live")}") |> range(start: -2h) '
                f'|> filter(fn: (r) => r._measurement == "{SENSOR}" and r.device_id == "{device_id}" '
                f'and r._field == "temperature") '
                f'|> aggregateWindow(every: 5m, fn: mean, createEmpty: false)')
        out = [[rec.get_time().timestamp(), float(rec.get_value())]
               for rec in self._rows(flux) if rec.get_value() is not None]
        out.sort()
        return out[-24:]

    def energy_today(self, device_id: str) -> Optional[float]:
        _check_id(device_id)
        now = datetime.fromtimestamp(self._clock(), self._tz)
        midnight = datetime.combine(now.date(), datetime.min.time(), tzinfo=self._tz)
        start = midnight.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        flux = (f'from(bucket: "{self._bucket("live")}") |> range(start: {start}) '
                f'|> filter(fn: (r) => r._measurement == "{PLUG}" and r.device_id == "{device_id}" '
                f'and r._field == "power") |> integral(unit: 1h)')
        total, found = 0.0, False
        for rec in self._rows(flux):
            if rec.get_value() is not None:
                total += float(rec.get_value())
                found = True
        return round(total / 1000.0, 2) if found else None
