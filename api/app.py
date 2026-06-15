#!/usr/bin/env python3
"""
Micro-API READ-ONLY pentru aplicatia mobila.
Citeste ultimele valori din InfluxDB (bucket live) si le returneaza ca JSON.
Expus prin Caddy la https://vyra.go.ro:31443/solar/latest (handle_path strip /solar).
NU vorbeste cu invertorul; doar cu InfluxDB.
"""
import os
from flask import Flask, jsonify, request
from influxdb_client import InfluxDBClient

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
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
        },
    },
    "output_power": {
        "label": "Consum casa", "unit": "W", "chart": "line",
        "ranges": {
            "1h": {"start": "-1h", "window": "30s", "bucket": "live", "fn": "mean"},
            "6h": {"start": "-6h", "window": "2m", "bucket": "live", "fn": "mean"},
            "24h": {"start": "-24h", "window": "5m", "bucket": "history", "fn": "mean"},
        },
    },
    "energy_pv_today": {
        "label": "Produs", "unit": "kWh", "chart": "bar",
        "ranges": {
            "7d": {"start": "-7d", "window": "1d", "bucket": "history", "fn": "max"},
            "30d": {"start": "-30d", "window": "1d", "bucket": "history", "fn": "max"},
        },
    },
    "energy_load_today": {
        "label": "Consum", "unit": "kWh", "chart": "bar",
        "ranges": {
            "7d": {"start": "-7d", "window": "1d", "bucket": "history", "fn": "max"},
            "30d": {"start": "-30d", "window": "1d", "bucket": "history", "fn": "max"},
        },
    },
}

app = Flask(__name__)
client = InfluxDBClient(url=INFLUX_URL, token=TOKEN, org=ORG, timeout=5000)


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
    out["timestamp"] = ts.isoformat() if ts else None
    return jsonify(out)


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
    bucket = BUCKET_HISTORY if range_cfg["bucket"] == "history" else BUCKET_LIVE
    q = (
        'import "timezone"\n'
        'option location = timezone.location(name: "Europe/Bucharest")\n'
        f'from(bucket: "{bucket}") |> range(start: {range_cfg["start"]}) '
        f'|> filter(fn: (r) => r._measurement == "inverter" and r._field == "{field}") '
        f'|> aggregateWindow(every: {range_cfg["window"]}, fn: {range_cfg["fn"]}, createEmpty: false) '
        '|> keep(columns: ["_time", "_value"])'
    )

    points = []
    try:
        for table in client.query_api().query(q, org=ORG):
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
        stats = {
            "min": min(values),
            "max": max(values),
            "avg": sum(values) / len(values),
            "sum": sum(values),
            "last": values[-1],
        }

    return jsonify({
        "field": field,
        "label": field_cfg["label"],
        "unit": field_cfg["unit"],
        "chart": field_cfg["chart"],
        "range": range_key,
        "window": range_cfg["window"],
        "points": points,
        "stats": stats,
    })


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
