import unittest
from datetime import datetime, timezone

from casa.config import Config
from casa.devices import DeviceInfo
from casa.influx import CasaInflux
from casa.plugs import PlugReading


class Rec:
    def __init__(self, t, v, f=None):
        self._t, self._v, self._f = t, v, f

    def get_time(self):
        return datetime.fromtimestamp(self._t, timezone.utc)

    def get_value(self):
        return self._v

    def get_field(self):
        return self._f


class Table:
    def __init__(self, records):
        self.records = records


class FakeWrite:
    def __init__(self):
        self.calls = []
        self.fail = False

    def write(self, bucket=None, org=None, record=None, **kw):
        if self.fail:
            raise OSError("jos")
        self.calls.append((bucket, org, list(record)))


class FakeQuery:
    def __init__(self):
        self.queries = []
        self.answer = lambda flux: []

    def query(self, flux, org=None):
        self.queries.append(flux)
        return self.answer(flux)


class FakeClient:
    def __init__(self):
        self.w, self.q = FakeWrite(), FakeQuery()

    def write_api(self, *a, **k):
        return self.w

    def query_api(self):
        return self.q


CFG = Config(data_dir="/tmp", influx_url="http://x", influx_token="t", influx_org="casa",
             bucket_live="live", bucket_history="history", ntfy_url="")
DEV = DeviceInfo(id="bf1", name="Boiler", kind="plug", local_key=None, ip=None, version=None)


def reading(at, on=True, online=True, power=100.0, voltage=230.0, current=0.5):
    return PlugReading(online=online, on=on, power=power, voltage=voltage, current=current,
                       countdown_s=None, error=None, at=at)


def line(p):
    return p.to_line_protocol()


class InfluxTest(unittest.TestCase):
    def setUp(self):
        self.c = FakeClient()
        self.now = [1_000_000.0]
        self.db = CasaInflux(CFG, client=self.c, clock=lambda: self.now[0])

    def test_plug_point_fields_and_tags(self):
        self.db.write_plug(DEV, reading(1_700_000_000.0))
        bucket, org, pts = self.c.w.calls[0]
        self.assertEqual((bucket, org), ("live", "casa"))
        s = line(pts[0])
        self.assertTrue(s.startswith("casa_plug,device_id=bf1,name=Boiler "), s)
        for f in ("on=1", "online=1", "power=100", "voltage=230", "current=0.5"):
            self.assertIn(f, s)
        self.assertTrue(s.endswith("1700000000000000000"), s)

    def test_none_fields_omitted(self):
        self.db.write_plug(DEV, reading(1_700_000_000.0, on=None, online=False, power=None,
                                        voltage=None, current=None))
        s = line(self.c.w.calls[0][2][0])
        self.assertIn("online=0", s)
        for f in ("on=", "power", "voltage", "current"):
            self.assertNotIn(f, s.replace("online=", ""))

    def test_history_minute_mean_uses_min_for_on_online(self):
        base = 1_700_000_040.0  # inceput de minut
        self.db.write_plug(DEV, reading(base + 1, on=True, power=100, voltage=230, current=1))
        self.db.write_plug(DEV, reading(base + 20, on=False, online=False, power=300,
                                        voltage=232, current=3))
        self.assertEqual([c[0] for c in self.c.w.calls if c[0] == "history"], [])
        self.db.write_plug(DEV, reading(base + 61))  # minut nou -> se emite media
        hist = [c for c in self.c.w.calls if c[0] == "history"]
        self.assertEqual(len(hist), 1)
        s = line(hist[0][2][0])
        for f in ("on=0", "online=0", "power=200", "voltage=231", "current=2"):
            self.assertIn(f, s)
        self.assertTrue(s.endswith(str(int(base) * 1_000_000_000)), s)

    def test_sensor_written_to_both_buckets_at_updated_time(self):
        self.db.write_sensor({"id": "s1", "name": "Temp BECI", "temperature": 21.5,
                              "humidity": 40, "battery": 90, "updated": 1_700_000_123})
        by = {c[0]: c[2] for c in self.c.w.calls}
        self.assertEqual(set(by), {"live", "history"})
        s = line(by["live"][0])
        self.assertTrue(s.startswith("casa_sensor,device_id=s1,name=Temp\\ BECI "), s)
        for f in ("temperature=21.5", "humidity=40", "battery=90"):
            self.assertIn(f, s)
        self.assertTrue(s.endswith("1700000123000000000"), s)

    def test_sensor_without_values_not_written(self):
        self.db.write_sensor({"id": "s1", "name": "x", "updated": None})
        self.assertEqual(self.c.w.calls, [])

    def test_history_range_selects_bucket_and_window(self):
        for rng, bucket, every in (("2h", "live", "2m"), ("1d", "live", "10m"),
                                   ("7d", "history", "1h")):
            self.c.q.queries.clear()
            self.db.sensor_history("s1", rng)
            win = [q for q in self.c.q.queries if "aggregateWindow" in q]
            self.assertEqual(len(win), 1)
            self.assertIn(f'from(bucket: "{bucket}")', win[0])
            self.assertIn(f"every: {every}", win[0])
            self.assertIn("createEmpty: false", win[0])

    def test_history_result_and_stats_from_raw(self):
        def answer(flux):
            if "aggregateWindow" in flux:
                return [Table([Rec(100, 20.0, "temperature"), Rec(200, 22.0, "temperature"),
                               Rec(100, 50.0, "humidity")])]
            return [Table([Rec(90, 18.0, "temperature"), Rec(150, 25.0, "temperature"),
                           Rec(90, 45.0, "humidity"), Rec(150, 55.0, "humidity")])]
        self.c.q.answer = answer
        r = self.db.sensor_history("s1", "2h")
        self.assertEqual(r["temperature"], [[100.0, 20.0], [200.0, 22.0]])
        self.assertEqual(r["humidity"], [[100.0, 50.0]])
        self.assertEqual(r["stats"]["temp_min"], {"v": 18.0, "t": 90.0})
        self.assertEqual(r["stats"]["temp_max"], {"v": 25.0, "t": 150.0})
        self.assertEqual(r["stats"]["hum_max"], {"v": 55.0, "t": 150.0})

    def test_history_empty(self):
        r = self.db.sensor_history("s1", "1d")
        self.assertEqual(r["temperature"], [])
        self.assertIsNone(r["stats"]["temp_min"])

    def test_unknown_range_raises(self):
        with self.assertRaises(ValueError):
            self.db.sensor_history("s1", "30d")

    def test_bad_device_id_raises(self):
        for fn in (lambda: self.db.sensor_history('a"b', "2h"), lambda: self.db.sensor_spark("a b"),
                   lambda: self.db.energy_today('x") |> drop()')):
            with self.assertRaises(ValueError):
                fn()
        self.assertEqual(self.c.q.queries, [])

    def test_spark_limited_to_24(self):
        self.c.q.answer = lambda flux: [Table([Rec(i * 300, float(i), "temperature")
                                               for i in range(30)])]
        out = self.db.sensor_spark("s1")
        self.assertEqual(len(out), 24)
        self.assertEqual(out[-1], [29 * 300.0, 29.0])
        self.assertIn("-2h", self.c.q.queries[0])

    def test_energy_today_starts_at_local_midnight(self):
        # 2026-11-05 10:00 UTC = 12:00 Bucharest (EET, +2) -> miezul noptii 2026-11-04T22:00:00Z
        self.now[0] = datetime(2026, 11, 5, 10, 0, tzinfo=timezone.utc).timestamp()
        self.c.q.answer = lambda flux: [Table([Rec(0, 1500.0)])]
        self.assertEqual(self.db.energy_today("bf1"), 1.5)
        q = self.c.q.queries[0]
        self.assertIn("start: 2026-11-04T22:00:00Z", q)
        self.assertIn("integral(unit: 1h)", q)
        self.assertIn('from(bucket: "live")', q)

    def test_energy_today_summer_offset_and_none(self):
        self.now[0] = datetime(2026, 7, 5, 10, 0, tzinfo=timezone.utc).timestamp()
        self.assertIsNone(self.db.energy_today("bf1"))
        self.assertIn("start: 2026-07-04T21:00:00Z", self.c.q.queries[0])

    def test_write_error_is_buffered_not_raised(self):
        self.c.w.fail = True
        self.db.write_plug(DEV, reading(1_700_000_000.0))  # nu ridica
        self.assertEqual(self.c.w.calls, [])
        # inainte de 10 s nu reincearca
        self.c.w.fail = False
        self.now[0] += 5
        self.db.write_plug(DEV, reading(1_700_000_001.0))
        self.assertEqual(self.c.w.calls, [])
        # dupa 10 s trimite tot ce s-a strans
        self.now[0] += 6
        self.db.write_plug(DEV, reading(1_700_000_002.0))
        self.assertEqual(len(self.c.w.calls), 1)
        self.assertEqual(len(self.c.w.calls[0][2]), 3)

    def test_buffer_is_bounded(self):
        self.c.w.fail = True
        for i in range(1000):
            self.db.write_plug(DEV, reading(1_700_000_000.0 + i))
        self.assertEqual(len(self.db._buf["live"]), 900)

    def test_flush_forces_attempt(self):
        self.c.w.fail = True
        self.db.write_sensor({"id": "s1", "name": "x", "temperature": 1, "updated": 5})
        self.c.w.fail = False
        self.db.flush()
        self.assertEqual({c[0] for c in self.c.w.calls}, {"live", "history"})


if __name__ == "__main__":
    unittest.main()
