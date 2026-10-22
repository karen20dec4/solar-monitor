#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Teste pentru bms_alerts (fara Bluetooth, fara InfluxDB, cu ceas simulat).

Rulare: python3 -m unittest discover -s /opt/solar-monitor/bms
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bms_alerts as ba  # noqa: E402
import jk_bms_reader as jk  # noqa: E402

OK = {
    "charge_mosfet": 1.0, "discharge_mosfet": 1.0, "alarm_bits": 0.0,
    "cell_min": 3.98, "cell_max": 3.99, "cell_delta": 0.010,
    "temp_1": 21.0, "temp_2": 20.0, "temp_mos": 25.0,
}


class Recorder:
    def __init__(self):
        self.sent = []

    def __call__(self, title, message, priority="default", tags=""):
        self.sent.append((title, message, priority))


def engine(cfg=None, debounce=3.0, cooldown=300.0):
    rec = Recorder()
    return ba.AlertEngine(ba.build_alerts(cfg or {}), rec, debounce, cooldown), rec


class RulesTest(unittest.TestCase):
    def test_healthy_pack_raises_nothing(self):
        eng, rec = engine()
        for t in range(0, 600, 5):
            eng.update(OK, t)
        self.assertEqual(rec.sent, [])
        self.assertEqual(eng.active_payload(), [])

    def test_mosfet_off_is_debounced_then_pushed_then_cleared(self):
        eng, rec = engine()
        off = dict(OK, charge_mosfet=0.0)
        eng.update(off, 0)
        eng.update(off, 2)                       # sub debounce (3 s)
        self.assertEqual(eng.active_payload(), [])
        eng.update(off, 3)
        active = eng.active_payload()
        self.assertEqual([a["key"] for a in active], ["bms_mosfet"])
        self.assertEqual(active[0]["prio"], "urgent")
        self.assertIn("incarcare", active[0]["msg"])
        self.assertEqual(len(rec.sent), 1)
        eng.update(off, 10)                      # nu repeta cat timp e activa
        self.assertEqual(len(rec.sent), 1)
        eng.update(OK, 11)
        self.assertEqual(eng.active_payload(), [])
        self.assertEqual(rec.sent[-1][0], "BMS: bateria deconectata! - revenit")

    def test_short_blip_below_debounce_is_ignored(self):
        eng, rec = engine()
        eng.update(dict(OK, discharge_mosfet=0.0), 0)
        eng.update(OK, 1)
        eng.update(dict(OK, discharge_mosfet=0.0), 2)
        eng.update(dict(OK, discharge_mosfet=0.0), 4)   # 2 s de la reinceput
        self.assertEqual(rec.sent, [])

    def test_cooldown_blocks_repush_but_not_the_active_list(self):
        eng, rec = engine(cooldown=300)
        off = dict(OK, charge_mosfet=0.0)
        eng.update(off, 0); eng.update(off, 3)
        eng.update(OK, 10)                       # revenit
        eng.update(off, 20); eng.update(off, 24)  # iar cazut, in cooldown
        self.assertEqual(len([s for s in rec.sent if "revenit" not in s[0]]), 1)
        self.assertEqual([a["key"] for a in eng.active_payload()], ["bms_mosfet"])
        eng.update(OK, 30)
        # revenirea e anuntata doar daca alerta fusese anuntata
        self.assertEqual(len([s for s in rec.sent if "revenit" in s[0]]), 1)

    def test_alarm_bits_cell_and_temperature_rules(self):
        cases = [
            ("bms_alarma", dict(OK, alarm_bits=4.0), 3),
            ("bms_celula_sus", dict(OK, cell_max=4.13), 3),
            ("bms_celula_jos", dict(OK, cell_min=3.15), 3),
            ("bms_temp_sus", dict(OK, temp_2=46.0), 30),
            ("bms_temp_sus", dict(OK, temp_mos=81.0), 30),
            ("bms_temp_jos", dict(OK, temp_1=1.0), 30),
        ]
        for key, data, wait in cases:
            with self.subTest(key=key, data=data):
                eng, rec = engine()
                eng.update(data, 0)
                eng.update(data, wait)
                self.assertEqual([a["key"] for a in eng.active_payload()], [key])

    def test_imbalance_needs_a_minute_and_has_hysteresis(self):
        eng, rec = engine()
        bad = dict(OK, cell_delta=0.120)
        eng.update(bad, 0)
        eng.update(bad, 59)
        self.assertEqual(eng.active_payload(), [])
        eng.update(bad, 60)
        self.assertEqual([a["key"] for a in eng.active_payload()], ["bms_dezechilibru"])
        eng.update(dict(OK, cell_delta=0.080), 61)   # sub prag, peste pragul de revenire
        self.assertEqual(len(eng.active_payload()), 1)
        eng.update(dict(OK, cell_delta=0.050), 62)
        self.assertEqual(eng.active_payload(), [])

    def test_thresholds_come_from_env(self):
        eng, rec = engine({"BMS_CELL_HIGH_V": "4.00"})
        data = dict(OK, cell_max=4.01)
        eng.update(data, 0); eng.update(data, 3)
        self.assertEqual([a["key"] for a in eng.active_payload()], ["bms_celula_sus"])


class LinkTest(unittest.TestCase):
    def test_link_lost_uses_separate_group(self):
        eng, rec = engine()
        eng.update({"link_age": 179}, 0, group="link")
        self.assertEqual(eng.active_payload(), [])
        eng.update({"link_age": 180}, 1, group="link")
        self.assertEqual([a["key"] for a in eng.active_payload()], ["bms_legatura"])
        self.assertEqual(eng.active_payload()[0]["prio"], "default")   # aplicatia nu suna
        eng.update({"link_age": 2}, 2, group="link")
        self.assertEqual(eng.active_payload(), [])

    def test_frame_updates_do_not_touch_link_alert(self):
        eng, rec = engine()
        eng.update(OK, 0)                        # nu are cheia link_age: nu trebuie sa dea KeyError
        self.assertEqual(rec.sent, [])


class AlertLineTest(unittest.TestCase):
    def test_line_protocol_quotes_strings(self):
        line = jk.alert_line({"key": "bms_mosfet", "title": "BMS: x", "prio": "urgent",
                              "msg": 'a "b" \\ c'}, 7)
        self.assertEqual(
            line,
            'bms_alert,key=bms_mosfet title="BMS: x",msg="a \\"b\\" \\\\ c",prio="urgent",active=1.0 7')


if __name__ == "__main__":
    unittest.main()
