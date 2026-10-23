# -*- coding: utf-8 -*-
"""Teste pentru BalanceDeriver: pierderea invertorului si importul din retea, in special pe bypass.

Rulare (collector.py are nevoie de minimalmodbus/influxdb_client, care sunt in imaginea collectorului):
  docker run --rm --network none -v /opt/solar-monitor/collector:/src:ro -w /src \
    --entrypoint python solar-monitor-collector -m unittest
"""
import unittest

import collector


def sample(**kw):
    """O citire parsata, cu valori neutre; fiecare test schimba doar ce conteaza."""
    data = {
        "pv_power": 0.0, "output_power": 0.0, "grid_charge_power": 0.0,
        "battery_charge_power": 0.0, "battery_discharge_power": 0.0, "battery_power": 0.0,
        "battery_voltage": 50.0, "grid_available": 1.0,
        "bypass_active": 0.0, "discharge_active": 0.0,
    }
    data.update(kw)
    return data


def run(deriver, seconds, **kw):
    out = None
    for t in range(seconds):
        out = sample(**kw)
        deriver.apply(out, float(t))
    return out


class BypassTest(unittest.TestCase):
    def setUp(self):
        self.deriver = collector.BalanceDeriver(smooth_s=60, hold_s=0)

    def test_bypass_loss_is_pv_minus_charge_not_zero(self):
        # Date reale din 2026-10-23: PV 353 W, incarcare 267 W, sarcina 207 W toata pe retea.
        d = run(self.deriver, 30, pv_power=353.0, battery_charge_power=267.0, battery_power=267.0,
                output_power=207.0, bypass_active=1.0)
        self.assertAlmostEqual(d["inverter_loss"], 86.0, places=3)
        self.assertEqual(d["inverter_loss_estimated"], 0.0)

    def test_bypass_grid_import_is_the_whole_load(self):
        d = run(self.deriver, 30, pv_power=353.0, battery_charge_power=267.0, battery_power=267.0,
                output_power=207.0, bypass_active=1.0)
        self.assertAlmostEqual(d["grid_import_power"], 207.0, places=3)
        self.assertAlmostEqual(d["grid_power"], 207.0, places=3)
        self.assertEqual(d["house_source"], 3.0)

    def test_bypass_without_pv_falls_back_to_the_estimate_and_says_so(self):
        d = run(self.deriver, 30, output_power=300.0, bypass_active=1.0)
        self.assertEqual(d["inverter_loss"], collector.INVERTER_LOSS_ESTIMATE_W)
        self.assertEqual(d["inverter_loss_estimated"], 1.0)
        self.assertAlmostEqual(d["grid_power"], 300.0, places=3)

    def test_bypass_ac_charging_is_added_to_the_grid_draw(self):
        d = run(self.deriver, 30, pv_power=0.0, grid_charge_power=1000.0, battery_charge_power=950.0,
                battery_power=950.0, output_power=200.0, bypass_active=1.0)
        self.assertAlmostEqual(d["grid_power"], 1200.0, places=3)
        self.assertAlmostEqual(d["inverter_loss"], 50.0, places=3)   # 1000 AC -> 950 in baterie

    def test_bypass_loss_ignores_the_load_and_uses_the_median(self):
        # Un esantion izolat (registre decalate) nu schimba pierderea afisata.
        for t in range(30):
            d = sample(pv_power=353.0, battery_charge_power=267.0, battery_power=267.0,
                       output_power=207.0, bypass_active=1.0)
            if t == 15:
                d["battery_charge_power"] = 0.0
            self.deriver.apply(d, float(t))
        self.assertAlmostEqual(d["inverter_loss"], 86.0, places=3)


class BatteryModeTest(unittest.TestCase):
    def setUp(self):
        self.deriver = collector.BalanceDeriver(smooth_s=60, hold_s=0)

    def test_on_battery_loss_is_the_positive_balance_and_nothing_is_taken_from_grid(self):
        # Noaptea pe baterie: descarcare 560 W, sarcina 460 W -> 100 W consum propriu.
        d = run(self.deriver, 30, battery_discharge_power=560.0, battery_power=-560.0, output_power=460.0,
                discharge_active=1.0)
        self.assertAlmostEqual(d["inverter_loss"], 100.0, places=3)
        self.assertEqual(d["grid_import_power"], 0.0)
        self.assertEqual(d["grid_power"], 0.0)

    def test_pv_charging_on_battery_mode_keeps_the_old_balance(self):
        d = run(self.deriver, 30, pv_power=2000.0, battery_charge_power=1500.0, battery_power=1500.0,
                output_power=400.0)
        self.assertAlmostEqual(d["inverter_loss"], 100.0, places=3)
        self.assertEqual(d["grid_power"], 0.0)

    def test_bypass_window_is_dropped_when_leaving_bypass(self):
        run(self.deriver, 30, pv_power=353.0, battery_charge_power=267.0, battery_power=267.0,
            output_power=207.0, bypass_active=1.0)
        self.assertTrue(self.deriver.side_window)
        sample_out = sample(battery_discharge_power=560.0, battery_power=-560.0, output_power=460.0,
                            discharge_active=1.0)
        self.deriver.apply(sample_out, 31.0)
        self.assertFalse(self.deriver.side_window)


if __name__ == "__main__":
    unittest.main()
