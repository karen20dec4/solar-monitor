#!/usr/bin/env python3
"""
Solar Monitor collector - Growatt SPF 6000 ES Plus
Citeste Modbus RTU (READ-ONLY) si scrie in InfluxDB (live + history).
Trimite alerte push (ntfy) pentru consum mare, protectie baterie,
supraincalzire, pierdere iesire AC si "monitorizare cazuta" (watchdog).
"""
import os
import time
import logging
import urllib.request

import serial
import minimalmodbus
from influxdb_client import InfluxDBClient, Point
from influxdb_client.client.write_api import SYNCHRONOUS

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("collector")


def envf(name, default):
    return float(os.getenv(name, default))


# ---------- config Modbus / InfluxDB ----------
MODBUS_PORT  = os.getenv("MODBUS_PORT", "/dev/ttyUSB0")
MODBUS_BAUD  = int(os.getenv("MODBUS_BAUD", "9600"))
MODBUS_SLAVE = int(os.getenv("MODBUS_SLAVE", "1"))
POLL_LIVE    = float(os.getenv("POLL_INTERVAL_LIVE", "1"))
HIST_EVERY   = float(os.getenv("HISTORY_INTERVAL", "60"))
DEBUG_RAW    = os.getenv("DEBUG_RAW", "0") == "1"
REG_COUNT    = int(os.getenv("REG_COUNT", "45"))

INFLUX_URL   = os.getenv("INFLUX_URL", "http://influxdb:8086")
INFLUX_TOKEN = os.getenv("INFLUX_TOKEN", "")
INFLUX_ORG   = os.getenv("INFLUX_ORG", "casa")
BUCKET_LIVE  = os.getenv("INFLUX_BUCKET_LIVE", "live")
BUCKET_HIST  = os.getenv("INFLUX_BUCKET_HISTORY", "history")

# ---------- config alerte (ntfy) ----------
NTFY_URL          = os.getenv("NTFY_URL", "")          # gol = dezactivat
ALERT_COOLDOWN_S  = envf("ALERT_COOLDOWN_S", "300")    # pauza intre realertari
ALERT_DEBOUNCE_S  = envf("ALERT_DEBOUNCE_S", "3")      # conditia trebuie sa tina N sec
WATCHDOG_FAILS    = int(os.getenv("WATCHDOG_FAILS", "30"))

# praguri (cu histerezis: _CLR = revenire la normal)
ALERT_THRESHOLD_W = envf("ALERT_THRESHOLD_W", "5500")
ALERT_CLEAR_W     = envf("ALERT_CLEAR_W", "5000")
BAT_LOW_V         = envf("BAT_LOW_V", "48.0")
BAT_LOW_V_CLR     = envf("BAT_LOW_V_CLR", "49.0")
BAT_LOW_SOC       = envf("BAT_LOW_SOC", "20")
BAT_LOW_SOC_CLR   = envf("BAT_LOW_SOC_CLR", "25")
BAT_HIGH_V        = envf("BAT_HIGH_V", "57.0")
BAT_HIGH_V_CLR    = envf("BAT_HIGH_V_CLR", "56.0")
TEMP_HIGH         = envf("TEMP_HIGH", "65")
TEMP_HIGH_CLR     = envf("TEMP_HIGH_CLR", "60")
OUT_LOST_V        = envf("OUT_LOST_V", "180")
OUT_LOST_V_CLR    = envf("OUT_LOST_V_CLR", "200")
GRID_PRESENT_V    = envf("GRID_PRESENT_V", "100")
BATTERY_FIRST_MIN_V = envf("BATTERY_FIRST_MIN_V", str(BAT_LOW_V))
INVERTER_LOSS_ESTIMATE_W = envf("INVERTER_LOSS_ESTIMATE_W", "90")

MEASUREMENT = "inverter"

STATUS_BYPASS = {8, 9, 10, 11}
STATUS_DISCHARGE = {2, 12}


def u32(regs, hi):
    return (regs[hi] << 16) | regs[hi + 1]


def s32(regs, hi):
    value = u32(regs, hi)
    return value - 4294967296 if value > 2147483647 else value


def parse(regs):
    status = regs[0]
    pv1_p = u32(regs, 3) * 0.1
    pv2_p = u32(regs, 5) * 0.1
    pv_p  = pv1_p + pv2_p
    out_p = u32(regs, 9) * 0.1
    grid_charge = u32(regs, 13) * 0.1

    v_bat = regs[17] * 0.01
    # Reg 77/78 = Bat_Watt (signed int32, 0.1W): pozitiv descarcare, negativ incarcare.
    # In dashboard pastram conventia veche: pozitiv = incarcare, negativ = descarcare.
    bat_w = s32(regs, 77) * 0.1
    charge_current = regs[83] * 0.1
    discharge_current = regs[84] * 0.1
    i_bat = charge_current - discharge_current
    bat_p = -bat_w
    charge_p    = max(bat_p, 0.0)
    discharge_p = max(-bat_p, 0.0)

    grid_voltage = regs[20] * 0.1
    grid_available = grid_voltage >= GRID_PRESENT_V
    grid_charge_for_balance = grid_charge if grid_available else 0.0

    # Bilant fara consumul casei din retea:
    #   PV + descarcare_baterie + retea->baterie - consum_casa - incarcare_baterie
    # Daca e pozitiv, diferenta este consum propriu/pierdere invertor.
    # Daca e negativ si reteaua exista, diferenta lipsa vine din retea catre casa (bypass/line).
    # Daca reteaua e 0V, deficitul ramas este descarcare baterie inferata (reg90 poate ramane 0).
    balance = pv_p + discharge_p + grid_charge_for_balance - out_p - charge_p
    measured_loss = max(balance, 0.0)
    deficit = max(-balance, 0.0)

    bypass_active = status in STATUS_BYPASS
    discharge_active = status in STATUS_DISCHARGE

    # Reg 0 spune modul real de lucru: codurile 8-11 sunt Bypass, 2/12 sunt Discharge.
    # In BAT.FIRST/Only SOL, prezenta tensiunii AC nu inseamna ca reteaua alimenteaza casa.
    battery_first_active = v_bat > BATTERY_FIRST_MIN_V
    infer_battery_discharge = (not bypass_active) and (
        (not grid_available) or discharge_active or battery_first_active
    )
    grid_import = 0.0 if infer_battery_discharge else deficit

    loss_estimated = 0.0
    if infer_battery_discharge and deficit > 0.0 and discharge_p == 0.0:
        loss_estimated = INVERTER_LOSS_ESTIMATE_W
    loss = max(measured_loss, loss_estimated)

    battery_inferred_discharge = (deficit + loss_estimated) if infer_battery_discharge else 0.0
    battery_support = discharge_p + battery_inferred_discharge
    battery_display = bat_p - battery_inferred_discharge

    # Sursa consumului casei (cod numeric pt. dashboard, colorat):
    #   1 = PV (verde) / 2 = Baterie (galben) / 3 = Retea (rosu)
    DEAD = 50.0
    if grid_import > DEAD:
        house_source = 3.0          # deficit acoperit din retea (bypass/line)
    elif grid_available and grid_charge > DEAD:
        house_source = 3.0          # invertor pe retea (incarcare AC)
    elif battery_support > DEAD:
        house_source = 2.0          # bateria se descarca -> alimenteaza casa
    elif pv_p > DEAD:
        house_source = 1.0          # PV produce si acopera consumul
    elif out_p > DEAD:
        house_source = 3.0 if grid_available else 2.0
    else:
        house_source = 1.0          # ~fara consum (repaus)

    data = {
        "status":                  status,
        "bypass_active":           1.0 if bypass_active else 0.0,
        "discharge_active":        1.0 if discharge_active else 0.0,
        "pv1_voltage":             regs[1] * 0.1,
        "pv2_voltage":             regs[2] * 0.1,
        "pv1_power":               pv1_p,
        "pv2_power":               pv2_p,
        "pv_power":                pv_p,
        "output_power":            out_p,
        "output_va":               u32(regs, 11) * 0.1,
        "grid_charge_power":       grid_charge,
        "battery_voltage":         v_bat,
        "battery_soc":             regs[18],
        "bus_voltage":             regs[19] * 0.1,
        "grid_voltage":            grid_voltage,
        "grid_available":          1.0 if grid_available else 0.0,
        "grid_freq":               regs[21] * 0.01,
        "output_voltage":          regs[22] * 0.1,
        "output_freq":             regs[23] * 0.01,
        "inverter_temp":           regs[25] * 0.1,
        "load_percent":            regs[27],
        "battery_current":         i_bat,
        "battery_charge_current":  charge_current,
        "battery_discharge_current": discharge_current,
        "battery_power":           bat_p,
        "battery_charge_power":    charge_p,
        "battery_discharge_power": discharge_p,
        "battery_inferred_discharge_power": battery_inferred_discharge,
        "battery_support_power":   battery_support,
        "battery_display_power":   battery_display,
        "inverter_loss":           loss,
        "inverter_loss_estimated":  1.0 if loss_estimated > 0.0 else 0.0,
        "power_deficit":           deficit,
        "grid_import_power":       grid_import,
        "house_source":            house_source,
    }
    # Rutez consumul casei pe campul sursei active -> card unic colorat in Grafana
    # (doar campul activ e scris in fiecare ciclu => o singura pastila afisata).
    if house_source == 1.0:
        data["house_pv"] = out_p
    elif house_source == 2.0:
        data["house_bat"] = out_p
    else:
        data["house_grid"] = out_p
    if DEBUG_RAW:
        for i in range(REG_COUNT):
            v = regs[i]
            data[f"r{i}"] = v
            data[f"s{i}"] = v - 65536 if v > 32767 else v
    return data


def make_point(data):
    p = Point(MEASUREMENT).tag("device", "spf6000es")
    for k, v in data.items():
        p = p.field(k, float(v))
    return p


def send_ntfy(title, message, priority="default", tags=""):
    """Notificare push ntfy (titlu/headere ASCII, body UTF-8)."""
    if not NTFY_URL:
        return
    try:
        req = urllib.request.Request(
            NTFY_URL, data=message.encode("utf-8"), method="POST",
            headers={"Title": title, "Priority": priority, "Tags": tags})
        urllib.request.urlopen(req, timeout=5)
    except Exception as e:
        log.warning("ntfy POST esuat: %s", e)


def build_alerts():
    """Lista de alerte. fire/clear primesc dict-ul de date parsate."""
    return [
        {"key": "consum_mare", "prio": "urgent", "tags": "warning,zap",
         "title": "Consum mare invertor!",
         "fire":  lambda d: d["output_power"] >= ALERT_THRESHOLD_W,
         "clear": lambda d: d["output_power"] <= ALERT_CLEAR_W,
         "msg":   lambda d: f"Consum {d['output_power']:.0f} W (prag {ALERT_THRESHOLD_W:.0f} W) - risc de protectie."},

        {"key": "baterie_jos", "prio": "urgent", "tags": "warning,battery",
         "title": "Baterie descarcata!",
         "fire":  lambda d: d["battery_voltage"] <= BAT_LOW_V or d["battery_soc"] <= BAT_LOW_SOC,
         "clear": lambda d: d["battery_voltage"] >= BAT_LOW_V_CLR and d["battery_soc"] >= BAT_LOW_SOC_CLR,
         "msg":   lambda d: f"Baterie {d['battery_voltage']:.2f} V / SOC {d['battery_soc']:.0f}% - reduceti consumul."},

        {"key": "baterie_sus", "prio": "high", "tags": "battery",
         "title": "Baterie aproape plina",
         "fire":  lambda d: d["battery_voltage"] >= BAT_HIGH_V,
         "clear": lambda d: d["battery_voltage"] <= BAT_HIGH_V_CLR,
         "msg":   lambda d: f"Baterie {d['battery_voltage']:.2f} V (peste {BAT_HIGH_V:.1f} V)."},

        {"key": "supraincalzire", "prio": "urgent", "tags": "fire",
         "title": "Invertor supraincalzit!",
         "fire":  lambda d: d["inverter_temp"] >= TEMP_HIGH,
         "clear": lambda d: d["inverter_temp"] <= TEMP_HIGH_CLR,
         "msg":   lambda d: f"Temperatura invertor {d['inverter_temp']:.1f} C (prag {TEMP_HIGH:.0f} C)."},

        {"key": "iesire_pierduta", "prio": "urgent", "tags": "rotating_light",
         "title": "Iesire AC pierduta!",
         "fire":  lambda d: d["output_voltage"] < OUT_LOST_V,
         "clear": lambda d: d["output_voltage"] >= OUT_LOST_V_CLR,
         "msg":   lambda d: f"Tensiune iesire {d['output_voltage']:.0f} V - invertor posibil in protectie/oprit."},
    ]


def eval_alerts(alerts, data, now):
    for a in alerts:
        if a["active"]:
            if a["clear"](data):
                send_ntfy(a["title"] + " - revenit", "Revenit la normal.", "default", "white_check_mark")
                a["active"] = False
                a["since"] = 0.0
                log.info("Alerta '%s' revenita la normal.", a["key"])
        else:
            if a["fire"](data):
                if a["since"] == 0.0:
                    a["since"] = now
                if (now - a["since"] >= ALERT_DEBOUNCE_S) and (now - a["last"] >= ALERT_COOLDOWN_S):
                    send_ntfy(a["title"], a["msg"](data), a["prio"], a["tags"])
                    a["active"] = True
                    a["last"] = now
                    a["since"] = 0.0
                    log.info("ALERTA '%s': %s", a["key"], a["msg"](data))
            else:
                a["since"] = 0.0


def connect_modbus():
    inst = minimalmodbus.Instrument(MODBUS_PORT, MODBUS_SLAVE)
    inst.serial.baudrate = MODBUS_BAUD
    inst.serial.bytesize = 8
    inst.serial.parity   = serial.PARITY_NONE
    inst.serial.stopbits = 1
    inst.serial.timeout  = 1.0
    inst.clear_buffers_before_each_transaction = True
    return inst


def main():
    log.info("Collector pornit: port=%s baud=%d slave=%d regs=%d live=%.1fs hist=%.0fs debug_raw=%s ntfy=%s prag=%.0fW",
             MODBUS_PORT, MODBUS_BAUD, MODBUS_SLAVE, REG_COUNT, POLL_LIVE, HIST_EVERY,
             DEBUG_RAW, bool(NTFY_URL), ALERT_THRESHOLD_W)

    influx = InfluxDBClient(url=INFLUX_URL, token=INFLUX_TOKEN, org=INFLUX_ORG)
    write_api = influx.write_api(write_options=SYNCHRONOUS)

    alerts = build_alerts()
    for a in alerts:
        a["active"] = False
        a["since"]  = 0.0
        a["last"]   = 0.0

    inst = None
    last_hist = 0.0
    fail = 0
    watchdog_alerted = False

    while True:
        t0 = time.time()
        try:
            if inst is None:
                inst = connect_modbus()
            regs = inst.read_registers(0, REG_COUNT, functioncode=4)
            data = parse(regs)

            eval_alerts(alerts, data, t0)

            point = make_point(data)
            write_api.write(bucket=BUCKET_LIVE, record=point)
            if t0 - last_hist >= HIST_EVERY:
                write_api.write(bucket=BUCKET_HIST, record=point)
                last_hist = t0

            if fail:
                log.info("Comunicatie Modbus restabilita.")
                if watchdog_alerted:
                    send_ntfy("Monitorizare revenita", "Invertorul raspunde din nou.",
                              "default", "white_check_mark")
                    watchdog_alerted = False
                fail = 0
        except Exception as e:
            fail += 1
            if fail <= 3 or fail % 30 == 0:
                log.warning("Eroare citire/scriere (%d): %s", fail, e)
            if fail == WATCHDOG_FAILS and not watchdog_alerted:
                send_ntfy("Monitorizare cazuta!",
                          f"Invertorul nu mai raspunde de ~{fail}s (USB deconectat / invertor oprit?).",
                          "urgent", "rotating_light")
                watchdog_alerted = True
                log.warning("WATCHDOG: alerta 'monitorizare cazuta' trimisa.")
            try:
                if inst is not None:
                    inst.serial.close()
            except Exception:
                pass
            inst = None
            time.sleep(1)

        dt = time.time() - t0
        if dt < POLL_LIVE:
            time.sleep(POLL_LIVE - dt)


if __name__ == "__main__":
    main()
