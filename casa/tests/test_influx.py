import threading
import unittest
from datetime import datetime, timezone

from influxdb_client.client.write_api import SYNCHRONOUS
from influxdb_client.rest import ApiException

from casa.config import Config
from casa.devices import DeviceInfo
from casa.influx import CasaInflux
from casa.plugs import PlugReading


class Rec:
    def __init__(self, t, v, f=None, values=None):
        self._t, self._v, self._f = t, v, f
        self.values = values or {}

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
        self.exc = None
        self.closed = False

    def close(self):
        self.closed = True

    def write(self, bucket=None, org=None, record=None, **kw):
        if self.exc is not None:
            raise self.exc
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
        self.write_api_calls = 0
        self.closed = False

    def write_api(self, write_options=None, **k):
        # orice altceva decat SYNCHRONOUS ar insemna mod batching (erori inghitite, fire in plus)
        assert write_options is SYNCHRONOUS, "write_api trebuie creat cu SYNCHRONOUS"
        self.write_api_calls += 1
        return self.w

    def close(self):
        self.closed = True

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
        self.db = CasaInflux(CFG, client=self.c, clock=lambda: self.now[0], threaded=False)

    def test_plug_point_fields_and_tags(self):
        self.db.write_plug(DEV, reading(1_700_000_000.0))
        self.assertEqual(self.c.w.calls, [])  # scrierea nu face retea
        self.db.flush()
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
        self.db.flush()
        s = line(self.c.w.calls[0][2][0])
        self.assertIn("online=0", s)
        for f in ("on=", "power", "voltage", "current"):
            self.assertNotIn(f, s.replace("online=", ""))

    def test_history_minute_mean_uses_min_for_on_online(self):
        base = 1_700_000_040.0  # inceput de minut
        self.db.write_plug(DEV, reading(base + 1, on=True, power=100, voltage=230, current=1))
        self.db.write_plug(DEV, reading(base + 20, on=False, online=False, power=300,
                                        voltage=232, current=3))
        self.db.flush()
        self.assertEqual([c[0] for c in self.c.w.calls if c[0] == "history"], [])
        self.db.write_plug(DEV, reading(base + 61))  # minut nou -> se emite media
        self.db.flush()
        hist = [c for c in self.c.w.calls if c[0] == "history"]
        self.assertEqual(len(hist), 1)
        s = line(hist[0][2][0])
        for f in ("on=0", "online=0", "power=200", "voltage=231", "current=2"):
            self.assertIn(f, s)
        self.assertTrue(s.endswith(str(int(base) * 1_000_000_000)), s)

    def test_sensor_written_to_both_buckets_at_updated_time(self):
        self.db.write_sensor({"id": "s1", "name": "Temp BECI", "temperature": 21.5,
                              "humidity": 40, "battery": 90, "updated": 1_700_000_123})
        self.db.flush()
        by = {c[0]: c[2] for c in self.c.w.calls}
        self.assertEqual(set(by), {"live", "history"})
        s = line(by["live"][0])
        self.assertTrue(s.startswith("casa_sensor,device_id=s1,name=Temp\\ BECI "), s)
        for f in ("temperature=21.5", "humidity=40", "battery=90"):
            self.assertIn(f, s)
        self.assertTrue(s.endswith("1700000123000000000"), s)

    def test_sensor_without_values_not_written(self):
        self.db.write_sensor({"id": "s1", "name": "x", "updated": None})
        self.db.flush()
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

    def test_sensor_last_groups_by_device(self):
        tag = {"device_id": "s1", "name": "Temp Etaj"}
        self.c.q.answer = lambda flux: [Table([
            Rec(100, 20.1, "temperature", tag), Rec(160, 57.0, "humidity", tag), Rec(100, 100.0, "battery", tag),
            Rec(50, 7.8, "temperature", {"device_id": "s2", "name": "Temp afara"}),
            Rec(70, None, "humidity", {"device_id": "s2", "name": "Temp afara"}),
            Rec(80, 1.0, "altceva", tag)])]
        out = {e["id"]: e for e in self.db.sensor_last()}
        self.assertEqual(out["s1"], {"id": "s1", "name": "Temp Etaj", "temperature": 20.1, "humidity": 57.0,
                                     "battery": 100.0, "updated": 160.0})
        self.assertEqual(out["s2"], {"id": "s2", "name": "Temp afara", "temperature": 7.8, "humidity": None,
                                     "battery": None, "updated": 50.0})
        self.assertIn("-7d", self.c.q.queries[0])
        self.assertIn("|> last()", self.c.q.queries[0])

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
        self.db._flush(False)
        self.assertEqual(self.c.w.calls, [])
        # inainte de 10 s nu reincearca
        self.c.w.fail = False
        self.now[0] += 5
        self.db.write_plug(DEV, reading(1_700_000_001.0))
        self.db._flush(False)
        self.assertEqual(self.c.w.calls, [])
        # dupa 10 s trimite tot ce s-a strans
        self.now[0] += 6
        self.db.write_plug(DEV, reading(1_700_000_002.0))
        self.db._flush(False)
        self.assertEqual(len(self.c.w.calls), 1)
        self.assertEqual(len(self.c.w.calls[0][2]), 3)

    def test_write_api_is_synchronous_and_created_once(self):
        for i in range(3):
            self.db.write_plug(DEV, reading(1_700_000_000.0 + i))
            self.db.flush()
        self.assertEqual(self.c.write_api_calls, 1)  # FakeClient respinge orice alt write_options

    def test_write_error_with_synchronous_api_reaches_buffer(self):
        self.c.w.exc = ApiException(status=503, reason="jos")
        self.db.write_plug(DEV, reading(1_700_000_000.0))
        self.db.flush()
        self.assertEqual(len(self.db._buf["live"]), 1)

    def test_rejected_400_422_batch_is_dropped(self):
        for status in (400, 422):
            self.c.w.exc = ApiException(status=status, reason="respins")
            self.db.write_plug(DEV, reading(1_700_000_000.0))
            self.db.flush()
            self.assertEqual(len(self.db._buf["live"]), 0, status)
        # dupa respingere, scrierile urmatoare merg normal
        self.c.w.exc = None
        self.db.write_plug(DEV, reading(1_700_000_001.0))
        self.db.flush()
        self.assertEqual(len(self.c.w.calls), 1)

    def test_non_finite_values_skipped(self):
        self.db.write_plug(DEV, reading(1_700_000_000.0, power=float("nan"),
                                        voltage=float("inf"), current=0.5))
        self.db.flush()
        s = line(self.c.w.calls[0][2][0])
        self.assertNotIn("power", s)
        self.assertNotIn("voltage", s)
        self.assertIn("current=0.5", s)
        self.db.write_sensor({"id": "s1", "name": "x", "temperature": float("nan"),
                              "updated": 5})
        self.db.flush()
        self.assertEqual(len(self.c.w.calls), 1)  # senzorul fara valori finite nu se scrie

    def test_buffer_is_bounded(self):
        self.c.w.fail = True
        for i in range(1000):
            self.db.write_plug(DEV, reading(1_700_000_000.0 + i))
        self.assertEqual(len(self.db._buf["live"]), 900)

    def test_requeue_after_failure_drops_oldest(self):
        self.c.w.fail = True
        for i in range(900):
            self.db.write_plug(DEV, reading(1_700_000_000.0 + i))
        # flush esueaza cu 900 in batch; intre timp apar 50 puncte noi
        orig = self.c.w.write

        def failing_write(**kw):
            if kw["bucket"] == "live":
                for i in range(900, 950):
                    self.db.write_plug(DEV, reading(1_700_000_000.0 + i))
            return orig(**kw)
        self.c.w.write = failing_write
        self.db.flush()
        buf = list(self.db._buf["live"])
        self.assertEqual(len(buf), 900)
        ts = [int(line(p).split()[-1]) // 1_000_000_000 for p in buf]
        self.assertEqual(ts[-1], 1_700_000_949)   # cele mai noi au ramas
        self.assertEqual(ts[0], 1_700_000_050)    # cele mai vechi 50 au cazut
        self.assertEqual(ts, sorted(ts))

    def test_flush_forces_attempt(self):
        self.c.w.fail = True
        self.db.write_sensor({"id": "s1", "name": "x", "temperature": 1, "updated": 5})
        self.db.flush()
        self.c.w.fail = False
        self.db.flush()
        self.assertEqual({c[0] for c in self.c.w.calls}, {"live", "history"})

    def test_device_id_trailing_newline_rejected(self):
        for fn in (lambda: self.db.sensor_history("abc\n", "2h"),
                   lambda: self.db.sensor_spark("abc\n"),
                   lambda: self.db.energy_today("abc\n")):
            with self.assertRaises(ValueError):
                fn()

    def test_energy_today_on_dst_day(self):
        # 2026-03-29: trecerea la ora de vara are loc la 03:00; miezul noptii e inca EET (+2)
        self.now[0] = datetime(2026, 3, 29, 10, 0, tzinfo=timezone.utc).timestamp()
        self.db.energy_today("bf1")
        self.assertIn("start: 2026-03-28T22:00:00Z", self.c.q.queries[0])

    def test_writes_do_not_touch_network_on_caller_thread_and_flusher_sends(self):
        sent = threading.Event()
        callers = []
        orig = self.c.w.write

        def write(**kw):
            callers.append(threading.current_thread())
            orig(**kw)
            sent.set()
        self.c.w.write = write
        db = CasaInflux(CFG, client=self.c, clock=lambda: self.now[0])  # cu fir
        try:
            db.write_plug(DEV, reading(1_700_000_000.0))
            self.assertTrue(sent.wait(5))
            self.assertNotIn(threading.current_thread(), callers)
            self.assertTrue(all(t.daemon for t in callers))
        finally:
            db.close()
        self.assertTrue(self.c.closed)
        self.assertTrue(self.c.w.closed)

    def test_close_stops_thread(self):
        db = CasaInflux(CFG, client=self.c, clock=lambda: self.now[0])
        db.start()
        t = db._thread
        db.close()
        self.assertFalse(t.is_alive())


if __name__ == "__main__":
    unittest.main()
