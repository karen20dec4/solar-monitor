#!/usr/bin/env python3
"""
Micro-API READ-ONLY pentru aplicatia mobila.
Citeste ultimele valori din InfluxDB (bucket live) si le returneaza ca JSON.
Expus prin Caddy la https://vyra.go.ro:31443/solar/latest, /solar/bms si /solar/grid-month (handle_path strip /solar).
NU vorbeste cu invertorul; doar cu InfluxDB.
"""
import os
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from flask import Flask, jsonify, request
from influxdb_client import InfluxDBClient
from system_metrics import HostMetricsSampler

INFLUX_URL = os.getenv("INFLUX_URL", "http://influxdb:8086")
TOKEN      = os.getenv("INFLUX_TOKEN", "")
ORG        = os.getenv("INFLUX_ORG", "casa")
BUCKET_LIVE = os.getenv("INFLUX_BUCKET_LIVE", "live")
BUCKET_HISTORY = os.getenv("INFLUX_BUCKET_HISTORY", "history")

# Campurile expuse aplicatiei (acelasi set ca diagrama de flux + cateva extra).
FIELDS = [
    "status",
    "pv_power", "pv1_power", "pv2_power",
    "output_power", "output_voltage", "load_percent",
    "energy_pv_today", "energy_pv_total", "energy_load_today", "energy_load_total",
    "battery_voltage", "battery_soc",
    "battery_power", "battery_display_power",
    "battery_charge_power", "battery_discharge_power", "battery_support_power",
    "grid_voltage", "grid_import_power", "grid_charge_power",
    "inverter_temp", "inverter_loss",
    "house_source",
]

HISTORY_FIELDS = {
    "battery_voltage": {
        "label": "Tensiune baterie", "unit": "V", "chart": "line",
        "ranges": {
            "2h": {"start": "-2h", "window": "1m", "bucket": "live", "fn": "mean"},
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
            "1d": {"start": "-1d", "window": "1h", "bucket": "history", "fn": "mean", "stats_bucket": "live"},
            "7d": {"start": "-7d", "window": "30m", "bucket": "history", "fn": "mean"},
            "30d": {"start": "-30d", "window": "2h", "bucket": "history", "fn": "mean"},
        },
    },
    "output_power": {
        "label": "Consum casa", "unit": "W", "chart": "line",
        "ranges": {
            "2h": {"start": "-2h", "window": "1m", "bucket": "live", "fn": "mean"},
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
            "1d": {"start": "-1d", "window": "1h", "bucket": "history", "fn": "mean", "stats_bucket": "live"},
            "7d": {"start": "-7d", "window": "30m", "bucket": "history", "fn": "mean"},
            "30d": {"start": "-30d", "window": "2h", "bucket": "history", "fn": "mean"},
        },
    },
    "pv_power": {
        "label": "PV intrari", "unit": "W", "chart": "line",
        "ranges": {
            "2h": {"start": "-2h", "window": "1m", "bucket": "live", "fn": "mean"},
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
            "1d": {"start": "-1d", "window": "1h", "bucket": "history", "fn": "mean", "stats_bucket": "live"},
            "7d": {"start": "-7d", "window": "30m", "bucket": "history", "fn": "mean"},
            "30d": {"start": "-30d", "window": "2h", "bucket": "history", "fn": "mean"},
        },
    },
    # Bare de energie. Fiecare bara poarta ora de INCEPUT a ferestrei (timeSrc _start) si intervalul
    # e aliniat la fereastra, cu fereastra curenta (partiala) ultima: 2h = 12 bare de 10 min,
    # 1d = 24 bare orare, 7d = 7 bare zilnice, toate incluzand perioada in curs.
    # 2h si 1d integreaza puterea din `live`: contoarele invertorului au pas de 0,1 kWh si se
    # reseteaza la 23:51 (ceasul invertorului e ~9 min inainte), ambele prea grosiere pe ora.
    # 7d/30d iau maximul zilnic al contorului, exact pe zile intregi.
    "energy_pv_today": {
        "label": "Produs", "unit": "kWh", "chart": "bar",
        "ranges": {
            "2h": {"start": "-110m", "window": "10m", "bucket": "live", "integral_of": "pv_power"},
            "1d": {"start": "-23h", "window": "1h", "bucket": "live", "integral_of": "pv_power"},
            "7d": {"start": "-6d", "window": "1d", "bucket": "history", "fn": "max"},
            "30d": {"start": "-29d", "window": "1d", "bucket": "history", "fn": "max"},
        },
    },
    "energy_load_today": {
        "label": "Consum", "unit": "kWh", "chart": "bar",
        "ranges": {
            "2h": {"start": "-110m", "window": "10m", "bucket": "live", "integral_of": "output_power"},
            "1d": {"start": "-23h", "window": "1h", "bucket": "live", "integral_of": "output_power"},
            "7d": {"start": "-6d", "window": "1d", "bucket": "history", "fn": "max"},
            "30d": {"start": "-29d", "window": "1d", "bucket": "history", "fn": "max"},
        },
    },
    # Consumul din retea (casa pe bypass + incarcare AC a bateriei). `grid_power` il scrie collectorul
    # din v3.32; istoricul de dinainte a fost completat o data cu aceeasi formula. Nu exista contor al
    # invertorului pentru asta, deci barele integreaza puterea, ca la 2h/1d la celelalte: 2h/1d din `live`,
    # 7d/30d din `history` (medii pe 60 s), pe zile intregi aliniate la miezul noptii.
    "grid_power": {
        "label": "Retea", "unit": "W", "chart": "line",
        "ranges": {
            "2h": {"start": "-2h", "window": "1m", "bucket": "live", "fn": "mean"},
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
            "1d": {"start": "-1d", "window": "1h", "bucket": "history", "fn": "mean", "stats_bucket": "live"},
            "7d": {"start": "-7d", "window": "30m", "bucket": "history", "fn": "mean"},
            "30d": {"start": "-30d", "window": "2h", "bucket": "history", "fn": "mean"},
        },
    },
    "energy_grid": {
        "label": "Consum din retea", "unit": "kWh", "chart": "bar",
        "ranges": {
            "2h": {"start": "-110m", "window": "10m", "bucket": "live", "integral_of": "grid_power"},
            "1d": {"start": "-23h", "window": "1h", "bucket": "live", "integral_of": "grid_power"},
            "7d": {"start": "-6d", "window": "1d", "bucket": "history", "integral_of": "grid_power"},
            "30d": {"start": "-29d", "window": "1d", "bucket": "history", "integral_of": "grid_power"},
        },
    },
    "inverter_temp": {
        "label": "Temperatura invertor", "unit": "°C", "chart": "line",
        "ranges": {
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
        },
    },
}

app = Flask(__name__)
client = InfluxDBClient(url=INFLUX_URL, token=TOKEN, org=ORG, timeout=5000)
host_metrics = HostMetricsSampler()


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/latest")
def latest():
    flt = " or ".join(f'r._field == "{f}"' for f in FIELDS)
    q = (f'from(bucket: "{BUCKET_LIVE}") |> range(start: -15s) '
         f'|> filter(fn: (r) => r._measurement == "inverter" and ({flt})) '
         f'|> last()')
    out, ts = {}, None
    try:
        for table in client.query_api().query(q, org=ORG):
            for rec in table.records:
                out[rec.get_field()] = rec.get_value()
                ts = rec.get_time()
    except Exception as e:
        return jsonify({"error": str(e)}), 503
    out.update(host_metrics.snapshot())
    out["timestamp"] = ts.isoformat() if ts else None
    return jsonify(out)


@app.get("/bms")
def bms():
    """Bateria vazuta de BMS-ul JK (masuratoarea `bms`, scrisa de serviciul solar-bms de pe host).

    Campuri plate ca la /latest (+ `timestamp`, null fara date proaspete) si `alarms`: alertele
    BMS active chiar acum, cu titlu/mesaj/prioritate, evaluate pe server (pragurile sunt in .env).
    """
    out, ts, alarms = {}, None, {}
    try:
        q = (f'from(bucket: "{BUCKET_LIVE}") |> range(start: -15s) '
             '|> filter(fn: (r) => r._measurement == "bms") |> last()')
        for table in client.query_api().query(q, org=ORG):
            for rec in table.records:
                out[rec.get_field()] = rec.get_value()
                ts = rec.get_time()
        # Cat dureaza o alerta, cititorul o rescrie in fiecare secunda; ce nu mai apare s-a stins.
        q = (f'from(bucket: "{BUCKET_LIVE}") |> range(start: -6s) '
             '|> filter(fn: (r) => r._measurement == "bms_alert") |> last()')
        for table in client.query_api().query(q, org=ORG):
            for rec in table.records:
                alarms.setdefault(rec.values.get("key"), {})[rec.get_field()] = rec.get_value()
    except Exception as e:
        return jsonify({"error": str(e)}), 503
    out["timestamp"] = ts.isoformat() if ts else None
    out["alarms"] = [
        {"key": key, "title": a.get("title", key), "msg": a.get("msg", ""), "prio": a.get("prio", "default")}
        for key, a in sorted(alarms.items()) if key and a.get("active") == 1
    ]
    return jsonify(out)


BUCHAREST = ZoneInfo("Europe/Bucharest")


def _month_start(now=None):
    """Miezul noptii (ora Bucuresti) din prima zi a lunii in curs."""
    now = (now or datetime.now(BUCHAREST)).astimezone(BUCHAREST)
    return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


@app.get("/grid-month")
def grid_month():
    """Cat s-a luat din retea (kWh) de la inceputul lunii calendaristice, ora Bucuresti: pentru factura.

    Acelasi `grid_power` ca la graficul RETEA, integrat din `history` (medii pe 60 s). Nu e citirea
    contorului furnizorului, ci estimarea sistemului: casa pe bypass plus incarcarea AC a bateriei.
    `kwh` e null cand luna nu are inca nicio mostra.
    """
    start = _month_start().astimezone(timezone.utc)
    q = (
        f'from(bucket: "{BUCKET_HISTORY}") |> range(start: {start.strftime("%Y-%m-%dT%H:%M:%SZ")}) '
        '|> filter(fn: (r) => r._measurement == "inverter" and r._field == "grid_power") '
        '|> integral(unit: 1h) '
        '|> map(fn: (r) => ({r with _value: r._value / 1000.0})) '  # Wh -> kWh
        '|> keep(columns: ["_value"])'
    )
    kwh = None
    try:
        for table in client.query_api().query(q, org=ORG):
            for rec in table.records:
                if rec.get_value() is not None:
                    kwh = float(rec.get_value())
    except Exception as e:
        return jsonify({"error": str(e)}), 503
    return jsonify({
        "month": start.astimezone(BUCHAREST).strftime("%Y-%m"),
        "since": start.isoformat(),
        "kwh": kwh,
    })


FLUX_HEADER = (
    'import "timezone"\n'
    'import "date"\n'
    'option location = timezone.location(name: "Europe/Bucharest")\n'
)


def _bucket(name):
    return BUCKET_HISTORY if name == "history" else BUCKET_LIVE


def _range_start(cfg, chart):
    # Barele incep la o granita de fereastra, ca prima sa nu acopere doar o parte din ea.
    if chart == "bar":
        return f'date.add(d: {cfg["start"]}, to: date.truncate(t: now(), unit: {cfg["window"]}))'
    return cfg["start"]


def _series_query(field, cfg, chart):
    source = cfg.get("integral_of", field)
    if cfg.get("integral_of"):
        fn = "(column, tables=<-) => tables |> integral(unit: 1h, column: column)"
    else:
        fn = cfg["fn"]
    time_src = ', timeSrc: "_start"' if chart == "bar" else ""
    q = (
        FLUX_HEADER
        + f'from(bucket: "{_bucket(cfg["bucket"])}") |> range(start: {_range_start(cfg, chart)}) '
        f'|> filter(fn: (r) => r._measurement == "inverter" and r._field == "{source}") '
        f'|> aggregateWindow(every: {cfg["window"]}, fn: {fn}, createEmpty: false{time_src}) '
    )
    if cfg.get("integral_of"):
        q += '|> map(fn: (r) => ({r with _value: r._value / 1000.0})) '  # Wh -> kWh
    return q + '|> keep(columns: ["_time", "_value"])'


def _exact_stats_query(field, cfg):
    """Min/max/medie/ultima din datele brute, nu din punctele graficului.

    Graficul de 24 h are medii orare: varful lor (2964 W) ascundea varful real (4720 W).
    """
    bucket = _bucket(cfg.get("stats_bucket", cfg["bucket"]))
    return (
        f'data = from(bucket: "{bucket}") |> range(start: {cfg["start"]}) '
        f'|> filter(fn: (r) => r._measurement == "inverter" and r._field == "{field}")\n'
        'union(tables: ['
        'data |> min() |> set(key: "stat", value: "min"), '
        'data |> max() |> set(key: "stat", value: "max"), '
        'data |> mean() |> set(key: "stat", value: "avg"), '
        'data |> last() |> set(key: "stat", value: "last")'
        ']) |> keep(columns: ["stat", "_value"])'
    )


@app.get("/history")
def history():
    field = request.args.get("field", "").strip()
    range_key = request.args.get("range", "1h").strip()

    if field not in HISTORY_FIELDS:
        return jsonify({"error": "field nepermis", "allowed": sorted(HISTORY_FIELDS.keys())}), 400
    field_cfg = HISTORY_FIELDS[field]
    if range_key not in field_cfg["ranges"]:
        return jsonify({"error": "range nepermis", "allowed": sorted(field_cfg["ranges"].keys())}), 400

    range_cfg = field_cfg["ranges"][range_key]
    chart = field_cfg["chart"]

    points = []
    try:
        for table in client.query_api().query(_series_query(field, range_cfg, chart), org=ORG):
            for rec in table.records:
                value = rec.get_value()
                if value is None:
                    continue
                points.append({"t": rec.get_time().isoformat(), "v": float(value)})
    except Exception as e:
        return jsonify({"error": str(e)}), 503

    values = [p["v"] for p in points]
    stats = None
    if values:
        # Pentru bare (energie) punctele SUNT valorile exacte: total, medie pe bara, maxim, ultima.
        stats = {
            "min": min(values),
            "max": max(values),
            "avg": sum(values) / len(values),
            "sum": sum(values),
            "last": values[-1],
        }
        if chart == "line":
            try:
                for table in client.query_api().query(_exact_stats_query(field, range_cfg), org=ORG):
                    for rec in table.records:
                        key, value = rec.values.get("stat"), rec.get_value()
                        if key in stats and value is not None:
                            stats[key] = float(value)
            except Exception:
                pass  # raman statisticile din punctele graficului

    return jsonify({
        "field": field,
        "label": field_cfg["label"],
        "unit": field_cfg["unit"],
        "chart": chart,
        "range": range_key,
        "window": range_cfg["window"],
        "points": points,
        "stats": stats,
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
