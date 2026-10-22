# -*- coding: utf-8 -*-
"""
Alerte pentru bateria JK BMS: reguli + motor cu debounce, cooldown si histerezis,
ca in collector.py (`build_alerts` / `eval_alerts`).

Doua iesiri:
  * push ntfy, prin `notify` (la declansare si la revenire), cu cooldown intre re-alerte;
  * lista alertelor ACTIVE (`active_payload`), scrisa de cititor in InfluxDB ca
    masuratoarea `bms_alert`, pe care o citeste API-ul pentru alarma din aplicatie.
    Lista e doar conditia (cu debounce), fara cooldown: cooldown-ul alarmei din aplicatie
    il tine aplicatia, dupa setarile ei.

Titlurile sunt ASCII (merg in headere HTTP); corpul mesajelor e UTF-8.
"""
from dataclasses import dataclass
from typing import Callable, Optional


@dataclass
class Alert:
    key: str
    title: str
    prio: str            # urgent / high / default (conventia ntfy; aplicatia suna la urgent si high)
    tags: str
    fire: Callable[[dict], bool]
    clear: Callable[[dict], bool]
    msg: Callable[[dict], str]
    debounce_s: Optional[float] = None   # None = implicit din .env (ALERT_DEBOUNCE_S)
    group: str = "frame"                 # "frame" = la fiecare cadru BMS, "link" = din ceasul de legatura


def _num(cfg, key, default):
    try:
        return float(cfg.get(key, default))
    except (TypeError, ValueError):
        return float(default)


def build_alerts(cfg) -> list:
    cell_hi, cell_hi_clr = _num(cfg, "BMS_CELL_HIGH_V", 4.12), _num(cfg, "BMS_CELL_HIGH_V_CLR", 4.08)
    cell_lo, cell_lo_clr = _num(cfg, "BMS_CELL_LOW_V", 3.20), _num(cfg, "BMS_CELL_LOW_V_CLR", 3.30)
    delta_hi, delta_clr = _num(cfg, "BMS_DELTA_HIGH_V", 0.100), _num(cfg, "BMS_DELTA_HIGH_V_CLR", 0.060)
    temp_hi, temp_hi_clr = _num(cfg, "BMS_TEMP_HIGH", 45), _num(cfg, "BMS_TEMP_HIGH_CLR", 40)
    mos_hi, mos_hi_clr = _num(cfg, "BMS_MOS_TEMP_HIGH", 80), _num(cfg, "BMS_MOS_TEMP_HIGH_CLR", 70)
    temp_lo, temp_lo_clr = _num(cfg, "BMS_TEMP_LOW", 2), _num(cfg, "BMS_TEMP_LOW_CLR", 5)
    link_s = _num(cfg, "BMS_LINK_LOST_S", 180)

    def pack_temp_max(d):
        return max(d["temp_1"], d["temp_2"])

    def pack_temp_min(d):
        return min(d["temp_1"], d["temp_2"])

    def mosfets_off(d):
        off = []
        if d["charge_mosfet"] < 1:
            off.append("incarcare")
        if d["discharge_mosfet"] < 1:
            off.append("descarcare")
        return off

    return [
        Alert("bms_mosfet", "BMS: bateria deconectata!", "urgent", "rotating_light,battery",
              fire=lambda d: bool(mosfets_off(d)),
              clear=lambda d: not mosfets_off(d),
              msg=lambda d: f"MOSFET {' si '.join(mosfets_off(d))} oprit - BMS a deschis circuitul bateriei."),

        Alert("bms_alarma", "BMS: protectie activa!", "urgent", "warning,battery",
              fire=lambda d: d["alarm_bits"] != 0,
              clear=lambda d: d["alarm_bits"] == 0,
              msg=lambda d: f"BMS raporteaza alarma (cod 0x{int(d['alarm_bits']):04X})."),

        Alert("bms_celula_sus", "BMS: celula prea incarcata!", "urgent", "warning,battery",
              fire=lambda d: d["cell_max"] >= cell_hi,
              clear=lambda d: d["cell_max"] <= cell_hi_clr,
              msg=lambda d: f"Celula maxima {d['cell_max']:.3f} V (prag {cell_hi:.2f} V)."),

        Alert("bms_celula_jos", "BMS: celula prea descarcata!", "urgent", "warning,battery",
              fire=lambda d: d["cell_min"] <= cell_lo,
              clear=lambda d: d["cell_min"] >= cell_lo_clr,
              msg=lambda d: f"Celula minima {d['cell_min']:.3f} V (prag {cell_lo:.2f} V) - reduceti consumul."),

        Alert("bms_dezechilibru", "BMS: celule dezechilibrate", "high", "battery",
              fire=lambda d: d["cell_delta"] >= delta_hi,
              clear=lambda d: d["cell_delta"] <= delta_clr,
              msg=lambda d: f"Diferenta intre celule {d['cell_delta'] * 1000:.0f} mV (prag {delta_hi * 1000:.0f} mV).",
              debounce_s=60),

        Alert("bms_temp_sus", "BMS: baterie fierbinte!", "urgent", "fire",
              fire=lambda d: pack_temp_max(d) >= temp_hi or d["temp_mos"] >= mos_hi,
              clear=lambda d: pack_temp_max(d) <= temp_hi_clr and d["temp_mos"] <= mos_hi_clr,
              msg=lambda d: (f"Baterie {pack_temp_max(d):.1f} C (prag {temp_hi:.0f} C), "
                             f"MOSFET {d['temp_mos']:.1f} C (prag {mos_hi:.0f} C)."),
              debounce_s=30),

        Alert("bms_temp_jos", "BMS: baterie prea rece", "high", "snowflake,battery",
              fire=lambda d: pack_temp_min(d) <= temp_lo,
              clear=lambda d: pack_temp_min(d) >= temp_lo_clr,
              msg=lambda d: f"Baterie {pack_temp_min(d):.1f} C - nu incarca sub 0 C.",
              debounce_s=30),

        Alert("bms_legatura", "BMS: fara date", "default", "warning",
              fire=lambda d: d["link_age"] >= link_s,
              clear=lambda d: d["link_age"] < 10,
              msg=lambda d: f"Nicio citire de la BMS de {d['link_age'] / 60:.0f} min (Bluetooth sau serviciul solar-bms).",
              debounce_s=0, group="link"),
    ]


class AlertEngine:
    def __init__(self, alerts, notify, debounce_s=3.0, cooldown_s=300.0):
        self.alerts = alerts
        self.notify = notify
        self.debounce_s = debounce_s
        self.cooldown_s = cooldown_s
        self._state = {a.key: {"active": False, "since": None, "last": None,
                               "notified": False, "msg": ""} for a in alerts}

    def update(self, data: dict, now: float, group: str = "frame"):
        for alert in self.alerts:
            if alert.group != group:
                continue
            st = self._state[alert.key]
            if st["active"]:
                if alert.clear(data):
                    st["active"], st["since"] = False, None
                    if st["notified"]:
                        self.notify(alert.title + " - revenit", "Revenit la normal.",
                                    "default", "white_check_mark")
                    st["notified"] = False
                else:
                    st["msg"] = alert.msg(data)
                continue
            if not alert.fire(data):
                st["since"] = None
                continue
            if st["since"] is None:
                st["since"] = now
            debounce = self.debounce_s if alert.debounce_s is None else alert.debounce_s
            if now - st["since"] < debounce:
                continue
            st["active"], st["since"] = True, None
            st["msg"] = alert.msg(data)
            if st["last"] is None or now - st["last"] >= self.cooldown_s:
                st["last"] = now
                st["notified"] = True
                self.notify(alert.title, st["msg"], alert.prio, alert.tags)

    def active_payload(self) -> list:
        return [{"key": a.key, "title": a.title, "prio": a.prio, "msg": self._state[a.key]["msg"]}
                for a in self.alerts if self._state[a.key]["active"]]
