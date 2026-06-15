#!/usr/bin/env python3
"""
Micro-API READ-ONLY pentru aplicatia mobila.
Citeste ultimele valori din InfluxDB (bucket live) si le returneaza ca JSON.
Expus prin Caddy la https://vyra.go.ro:31443/solar/latest (handle_path strip /solar).
NU vorbeste cu invertorul; doar cu InfluxDB.
"""
import os
from flask import Flask, jsonify
from influxdb_client import InfluxDBClient

INFLUX_URL = os.getenv("INFLUX_URL", "http://influxdb:8086")
TOKEN      = os.getenv("INFLUX_TOKEN", "")
ORG        = os.getenv("INFLUX_ORG", "casa")
BUCKET     = os.getenv("INFLUX_BUCKET_LIVE", "live")

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

app = Flask(__name__)
client = InfluxDBClient(url=INFLUX_URL, token=TOKEN, org=ORG, timeout=5000)


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/latest")
def latest():
    flt = " or ".join(f'r._field == "{f}"' for f in FIELDS)
    q = (f'from(bucket: "{BUCKET}") |> range(start: -15s) '
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


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
