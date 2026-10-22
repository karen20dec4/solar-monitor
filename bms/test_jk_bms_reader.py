#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Teste pentru jk_bms_reader (fara Bluetooth si fara InfluxDB).

Rulare: python3 -m unittest discover -s /opt/solar-monitor/bms
"""
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jk_bms_reader as jk  # noqa: E402

# Cadru real de tip 2 captat 2026-10-22 de la JK_BD6A20S12P: 14 celule ~4,029 V,
# 56,401 V, -3,468 A (consum de noapte), SOC 97 %, 219 cicluri, MOSFET-uri pornite.
CELL_FRAME = bytes.fromhex(
    "55aaeb90029bbd0fbd0fbe0fbd0fbd0fbd0fbc0fbb0fbc0fbc0fbc0fbc0fbd0f"
    "bd0f000000000000000000000000000000000000000000000000000000000000"
    "000000000000ff3f0000bd0f05000207a600a300a2009f009e00a100a500a600"
    "a700a400a200a200a600ac000000000000000000000000000000000000000000"
    "00000000000000000000000000000000dd000000000051dc000010fc020074f2"
    "ffffd400c3000000000000000061baa6020020bf0200db00000042e55b026400"
    "00000d12340301010000000000000000000000000000ff0001000000fc030000"
    "11005d443f4000000000081600000000000103070000e09e040000000000dd00"
    "00000000fc031ee0ce0c190000008051010000000000000000000000000001fe"
    "ff7fdc0f010080030000007e"
)


class DecodeTest(unittest.TestCase):
    def test_frame_is_valid(self):
        self.assertTrue(jk.frame_ok(CELL_FRAME))
        broken = bytearray(CELL_FRAME)
        broken[100] ^= 0x01
        self.assertFalse(jk.frame_ok(bytes(broken)))
        self.assertFalse(jk.frame_ok(CELL_FRAME[:-1]))

    def test_cell_info_fields(self):
        d = jk.decode_cell_info(CELL_FRAME)
        self.assertEqual(d["cell_count"], 14)
        self.assertNotIn("cell_15", d)
        self.assertAlmostEqual(d["cell_03"], 4.030)
        self.assertAlmostEqual(d["cell_max"], 4.030)
        self.assertAlmostEqual(d["cell_min"], 4.027)
        self.assertAlmostEqual(d["cell_delta"], 0.003)
        self.assertAlmostEqual(d["voltage"], 56.401)
        self.assertAlmostEqual(d["current"], -3.468)
        self.assertAlmostEqual(d["power"], -3.468 * 56.401)
        self.assertAlmostEqual(d["temp_1"], 21.2)
        self.assertAlmostEqual(d["temp_2"], 19.5)
        self.assertAlmostEqual(d["temp_mos"], 22.1)
        self.assertEqual(d["soc"], 97)
        self.assertEqual(d["soh"], 100)
        self.assertAlmostEqual(d["capacity_nominal_ah"], 180.0)
        self.assertAlmostEqual(d["capacity_remaining_ah"], 173.754)
        self.assertEqual(d["cycles"], 219)
        self.assertEqual(d["charge_mosfet"], 1)
        self.assertEqual(d["discharge_mosfet"], 1)
        self.assertEqual(d["alarm_bits"], 0)
        self.assertEqual(d["balancing"], 0)

    def test_rejects_nonsense(self):
        bad = bytearray(CELL_FRAME)
        bad[6:8] = b"\x00\x00"  # celula 1 la 0 V
        bad[299] = sum(bad[:299]) & 0xFF
        with self.assertRaises(ValueError):
            jk.decode_cell_info(bytes(bad))
        with self.assertRaises(ValueError):
            jk.decode_cell_info(b"\x00" * 300)


class ReadOnlyTest(unittest.TestCase):
    def test_only_data_requests_are_buildable(self):
        for cmd in (0x96, 0x97):
            frame = jk.request_frame(cmd)
            self.assertEqual(len(frame), 20)
            self.assertEqual(frame[:5], bytes([0xAA, 0x55, 0x90, 0xEB, cmd]))
            self.assertEqual(frame[19], sum(frame[:19]) & 0xFF)
        for cmd in range(256):
            if cmd not in (0x96, 0x97):
                with self.assertRaises(ValueError):
                    jk.request_frame(cmd)

    def test_every_ble_write_goes_through_request_frame(self):
        with open(jk.__file__, encoding="utf-8") as fh:
            src = fh.read()
        writes = re.findall(r"write_gatt_char\(([^)]*\))", src)
        self.assertGreaterEqual(len(writes), 3)
        for call in writes:
            self.assertIn("request_frame(", call)


class PlumbingTest(unittest.TestCase):
    def test_assembler_joins_20_byte_chunks(self):
        asm = jk.FrameAssembler()
        out = None
        for i in range(0, 300, 20):
            out = asm.feed(CELL_FRAME[i:i + 20])
            if i < 280:
                self.assertIsNone(out)
        self.assertEqual(out, CELL_FRAME)

    def test_assembler_resyncs_on_header(self):
        asm = jk.FrameAssembler()
        asm.feed(CELL_FRAME[:20])
        asm.feed(CELL_FRAME[20:40])
        out = None
        for i in range(0, 300, 20):  # un cadru nou peste unul ramas la jumatate
            out = asm.feed(CELL_FRAME[i:i + 20])
        self.assertEqual(out, CELL_FRAME)

    def test_averager(self):
        avg = jk.Averager()
        avg.add({"voltage": 50.0, "soc": 90.0, "charge_mosfet": 1.0, "alarm_bits": 0.0})
        avg.add({"voltage": 52.0, "soc": 91.0, "charge_mosfet": 0.0, "alarm_bits": 4.0})
        out = avg.pop()
        self.assertEqual(out, {"voltage": 51.0, "soc": 91.0, "charge_mosfet": 0.0, "alarm_bits": 4.0})
        self.assertEqual(len(avg), 0)

    def test_line_protocol(self):
        line = jk.line_protocol({"soc": 97, "voltage": 52.3}, 1234)
        self.assertEqual(line, "bms,device=jk_bms soc=97.0,voltage=52.3 1234")


if __name__ == "__main__":
    unittest.main()
