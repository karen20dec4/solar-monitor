import logging
import os
import tempfile
import threading
import unittest
from concurrent.futures import Future
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from casa.controller import CommandError, Controller
from casa.devices import DeviceInfo
from casa.plugs import FakeDriver, PlugError, PlugReading, decode_reading
from casa.store import JsonStore

UTC = timezone.utc
TZ = ZoneInfo("Europe/Bucharest")
PID = "p1"
logging.getLogger("casa.controller").setLevel(logging.CRITICAL)


def loc(h, m=0, s=0, day=5, month=11, year=2026):
    """Ora locala Bucuresti -> UTC aware."""
    return datetime(year, month, day, h, m, s, tzinfo=TZ).astimezone(UTC)


class FlakyStore(JsonStore):
    fail_updates = 0

    def update(self, fn, default):
        if self.fail_updates > 0:
            self.fail_updates -= 1
            raise OSError("disc plin (simulat)")
        return super().update(fn, default)


def device(with_countdown=True, with_power=True):
    dps = {"switch": 1}
    if with_countdown:
        dps["countdown"] = 9
    scales = {}
    if with_power:
        dps.update({"current": 18, "power": 19, "voltage": 20})
        scales = {"power": 1, "voltage": 1, "current": 0}
    return DeviceInfo(id=PID, name="Boiler", kind="plug", local_key="k",
                      ip="1.2.3.4", version="3.3", dps=dps, scales=scales)


class Clock:
    def __init__(self, now):
        self.now = now

    def __call__(self):
        return self.now

    def set(self, now):
        self.now = now

    def advance(self, **kw):
        self.now += timedelta(**kw)


class FakeWorker:
    """Acelasi contract submit() ca PlugWorker, dar sincron, fara thread-uri."""

    def __init__(self, dev, driver, clock, defer=False):
        self.device = dev
        self.driver = driver
        self.clock = clock
        self.on_reading = lambda *_: None
        self.submitted = []
        # defer=True: ca PlugWorker real, future-ul se rezolva INAINTE ca citirea sa ajunga
        self.defer = defer
        self.pending = []
        self._cd = None
        driver.connect()

    def flush(self):
        pending, self.pending = self.pending, []
        for fn in pending:
            fn()

    def _ts(self):
        return self.clock().timestamp()

    def poll(self):
        dps = self.driver.status()
        # ca o priza reala, countdown-ul scris scade cu timpul (ceasul fals)
        if self._cd is not None and dps.get("9") == self._cd[0]:
            dps["9"] = max(0, self._cd[0] - int((self.clock() - self._cd[1]).total_seconds()))
        self.on_reading(self.device.id, decode_reading(self.device, dps, at=self._ts()))

    def submit(self, dp, value):
        self.submitted.append((dp, value))
        fut = Future()
        try:
            self.driver.connect()
            self.driver.set_dp(dp, value)
            if dp == 9:
                self._cd = (value, self.clock())
        except PlugError as e:
            fut.set_exception(e)
            offline = lambda: self.on_reading(self.device.id, PlugReading(  # noqa: E731
                online=False, on=None, power=None, voltage=None, current=None,
                countdown_s=None, error=str(e), at=self._ts()))
            (self.pending.append(offline) if self.defer else offline())
            return fut
        fut.set_result(None)
        (self.pending.append(self.poll) if self.defer else self.poll())
        return fut


class Rig:
    def __init__(self, start, dev=None, dps=None, defer=False, store_cls=JsonStore):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.tmp.name, "state.json")
        self.store = store_cls(self.path)
        self.clock = Clock(start)
        self.dev = dev or device()
        init = {"1": False}
        if "countdown" in self.dev.dps:
            init["9"] = 0
        init.update(dps or {})
        self.driver = FakeDriver(init)
        self.worker = FakeWorker(self.dev, self.driver, self.clock, defer=defer)
        self.c = self.build()
        self.worker.poll()

    def build(self):
        c = Controller([self.dev], {PID: self.worker}, self.store, TZ, self.clock)
        self.worker.on_reading = c.on_reading
        return c

    def restart(self, poll=True):
        self.c = self.build()
        if poll:
            self.worker.poll()
        return self.c

    @property
    def on(self):
        return self.driver.dps["1"] is True

    @property
    def dp9(self):
        return self.driver.dps.get("9")

    def set_schedules(self, items):
        """Intervale cu id-uri alese: [(id, start, stop), ...]."""
        def f(p):
            p["schedules"] = [{"id": i, "start": a, "stop": b, "enabled": True}
                              for i, a, b in items]
        self.c._mut(PID, f)

    def dp_writes_after_last_off(self):
        idx = max(i for i, x in enumerate(self.worker.submitted) if x == (1, False))
        return self.worker.submitted[idx + 1:]

    def tick_to(self, target, step=timedelta(seconds=30), poll=False):
        """Tick-uri la fiecare `step` pana la `target` inclusiv (poll: o citire intre tick-uri)."""
        while self.clock.now < target:
            self.clock.set(min(self.clock.now + step, target))
            self.worker.flush()  # citirile ajung intre tick-uri
            if poll:
                self.worker.poll()
            self.c.tick()

    def close(self):
        self.tmp.cleanup()


class ControllerBase(unittest.TestCase):
    def rig(self, start, **kw):
        r = Rig(start, **kw)
        self.addCleanup(r.close)
        return r

    def schedule(self, r, start="10:30", stop="16:00"):
        return r.c.add_interval(PID, start, stop)["schedules"][-1]["id"]


class ScheduleEdgesTest(ControllerBase):
    def test_schedule_start_and_stop_edges_switch_plug(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 29))
        self.assertFalse(r.on)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(16, 0))
        self.assertFalse(r.on)
        v = r.c.plug_view(PID)
        self.assertIsNone(v["error"])
        self.assertFalse(v["on"])

    def test_manual_off_inside_interval_is_not_overridden(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(12))
        r.c.switch(PID, False)
        self.assertFalse(r.on)
        n = len(r.worker.submitted)
        r.tick_to(loc(15, 59))
        self.assertFalse(r.on)
        r.tick_to(loc(16, 0))
        self.assertFalse(r.on)
        self.assertIsNone(r.c.plug_view(PID)["error"])
        self.assertEqual(len(r.worker.submitted), n)  # nici o comanda in plus

    def test_manual_on_inside_interval_is_stopped_at_stop_edge(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(13))
        r.c.switch(PID, True)
        r.tick_to(loc(16))
        self.assertFalse(r.on)

    def test_over_midnight_interval(self):
        r = self.rig(loc(20))
        self.schedule(r, "22:00", "06:00")
        r.tick_to(loc(22, 0))
        self.assertTrue(r.on)
        r.tick_to(loc(5, 59, day=6))
        self.assertTrue(r.on)
        r.tick_to(loc(6, 0, day=6))
        self.assertFalse(r.on)

    def test_disabled_interval_does_nothing(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.c.update_interval(PID, iid, enabled=False)
        r.tick_to(loc(17))
        self.assertFalse(r.on)
        self.assertEqual(r.worker.submitted, [])

    def test_new_interval_inside_window_does_not_start_immediately(self):
        r = self.rig(loc(12))
        self.schedule(r)  # 10:30-16:00, deja in curs
        r.tick_to(loc(12, 5))
        self.assertFalse(r.on)

    def test_started_by_set_when_plug_already_on_at_start(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual([s for s in r.worker.submitted if s[0] == 1], [])  # nicio comanda de pornire
        self.assertEqual(r.c._get(PID)["started_by"], iid)
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["reason"], "schedule")


class MissedEdgesTest(ControllerBase):
    def test_missed_stop_is_executed_after_long_outage(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.clock.set(loc(18, 40))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_stop_executed_days_later(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.clock.set(loc(7, 0, day=7))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_start_recovered_only_inside_window(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.clock.set(loc(11))
        r.restart()
        r.c.tick()
        self.assertTrue(r.on)
        self.assertEqual(r.c._get(PID)["started_by"] is not None, True)
        # a doua situatie: server cazut peste tot intervalul
        r2 = self.rig(loc(9))
        self.schedule(r2)
        r2.clock.set(loc(16, 30))
        r2.restart()
        r2.c.tick()
        self.assertFalse(r2.on)
        self.assertEqual(r2.worker.submitted, [])
        r2.tick_to(loc(17))
        self.assertEqual(r2.worker.submitted, [])

    def test_missed_start_not_repeated_after_manual_off(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.clock.set(loc(12))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_edges_wait_for_first_reading(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.clock.set(loc(18, 40))
        r.restart(poll=False)
        r.c.tick()
        self.assertTrue(r.on)  # nu stim inca starea
        r.worker.poll()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_edges_run_again_on_reconnect(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_next = 1000  # priza cade
        r.worker.on_reading(PID, PlugReading(False, None, None, None, None, None, "x", 0))
        r.tick_to(loc(16, 5))
        self.assertTrue(r.on)  # comanda nu a putut ajunge
        r.driver.fail_next = 0
        r.worker.poll()  # revine online
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_stop_skipped_after_newer_manual_on(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.clock.set(loc(17))
        r.c.switch(PID, True)  # dupa frontul de stop de la 16:00 (care n-a fost vazut)
        r.restart()
        r.c.tick()
        self.assertTrue(r.on)


class RetryTest(ControllerBase):
    def test_unreachable_plug_stop_retried_until_success(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.driver.fail_set = 10
        r.tick_to(loc(16, 0))
        self.assertTrue(r.on)
        v = r.c.plug_view(PID)
        self.assertFalse(v["online"])
        self.assertIsNotNone(v["error"])
        r.tick_to(loc(16, 3))
        self.assertTrue(r.on)
        self.assertIsNotNone(r.c.plug_view(PID)["error"])
        r.tick_to(loc(16, 10))
        self.assertFalse(r.on)
        v = r.c.plug_view(PID)
        self.assertIsNone(v["error"])
        self.assertTrue(v["online"])
        self.assertEqual(r.driver.fail_set, 0)

    def test_stop_retry_has_no_time_limit(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(23), step=timedelta(seconds=60))
        self.assertTrue(r.on)
        r.driver.fail_set = 0
        r.tick_to(loc(23, 2), step=timedelta(seconds=30))
        self.assertFalse(r.on)

    def test_start_retried_every_30s_inside_window(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.driver.fail_set = 2
        r.tick_to(loc(10, 30))
        self.assertFalse(r.on)
        self.assertEqual(len(r.worker.submitted), 1)
        r.clock.advance(seconds=10)
        r.c.tick()
        self.assertEqual(len(r.worker.submitted), 1)  # inca nu au trecut 30 s
        r.tick_to(loc(10, 31, 30), step=timedelta(seconds=10))
        self.assertEqual(len([x for x in r.worker.submitted if x[0] == 1]), 3)
        self.assertTrue(r.on)
        self.assertIsNone(r.c.plug_view(PID)["error"])

    def test_start_retry_gives_up_at_interval_end(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 1))
        self.assertFalse(r.on)
        r.driver.fail_set = 0
        n = len(r.worker.submitted)
        r.tick_to(loc(17), step=timedelta(seconds=30))
        self.assertFalse(r.on)
        self.assertEqual(len(r.worker.submitted), n)  # nu mai incearca


class DeadlineTest(ControllerBase):
    def test_off_deadline_written_to_plug_countdown_dp(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 19800)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "schedule")
        self.assertEqual(v["off_deadline"]["seconds_left"], 19800)

    def test_off_deadline_with_inching_is_nearest(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 30)
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 1800)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "inching")
        self.assertEqual(v["off_deadline"]["seconds_left"], 1800)

    def test_deadline_not_rewritten_within_5s(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        n = len(r.worker.submitted)
        r.tick_to(loc(10, 40), step=timedelta(seconds=5))
        self.assertEqual(len(r.worker.submitted), n)

    def test_countdown_dp_rewritten_when_deadline_changes(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.clock.set(loc(11))
        r.c.set_countdown(PID, "off", 30)
        self.assertEqual(r.dp9, 1800)
        r.c.cancel_countdown(PID)
        self.assertEqual(r.dp9, 5 * 3600)

    def test_dp_cancelled_with_zero_when_no_deadline_left(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        r.c.set_countdown(PID, "off", 30)
        self.assertEqual(r.dp9, 1800)
        r.c.cancel_countdown(PID)
        self.assertEqual(r.dp9, 0)
        n = len(r.worker.submitted)
        r.tick_to(loc(9, 5))
        self.assertEqual(len(r.worker.submitted), n)

    def test_no_deadline_no_write_when_plug_off(self):
        r = self.rig(loc(9))
        r.c.set_countdown(PID, "off", 30)
        self.assertEqual(r.worker.submitted, [])

    def test_deadline_without_countdown_dp_server_switches_off(self):
        r = self.rig(loc(9), dev=device(with_countdown=False))
        r.c.set_inching(PID, True, 30)
        r.driver.dps["1"] = True  # priza pornita de la buton
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.worker.submitted, [])
        r.tick_to(loc(9, 29, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(9, 30, 5))
        self.assertFalse(r.on)

    def test_server_switches_off_when_plug_ignored_its_countdown(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        r.c.set_countdown(PID, "off", 30)
        r.tick_to(loc(9, 30, 5))
        self.assertTrue(r.on)  # prioritate pentru DP-ul prizei (10 s gratie)
        r.tick_to(loc(9, 30, 20))
        self.assertFalse(r.on)
        self.assertIsNone(r.c.plug_view(PID)["countdown"])

    def test_inching_triggers_on_external_switch_on(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 45)
        r.driver.dps["1"] = True
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.dp9, 45 * 60)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "inching")

    def test_inching_disabled_does_nothing(self):
        r = self.rig(loc(9))
        r.driver.dps["1"] = True
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.worker.submitted, [])
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])

    def test_inching_applies_to_manual_on_and_cleared_on_off(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 10)
        r.c.switch(PID, True)
        self.assertEqual(r.dp9, 600)
        r.c.switch(PID, False)
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])
        self.assertIsNone(r.c._get(PID)["inching_off_at"])

    def test_external_off_clears_started_by_and_off_countdown(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.set_countdown(PID, "off", 60)
        self.assertEqual(r.c._get(PID)["started_by"], iid)
        r.driver.dps["1"] = False
        r.worker.poll()
        st = r.c._get(PID)
        self.assertIsNone(st["started_by"])
        self.assertIsNone(st["countdown"])

    def test_restart_keeps_inching_deadline(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 30)
        r.driver.dps["1"] = True
        r.worker.poll()
        r.clock.set(loc(9, 10))
        r.restart()
        r.c.tick()
        self.assertEqual(r.dp9, 20 * 60)


class CountdownTest(ControllerBase):
    def test_countdown_on_fires_and_persists_across_restart(self):
        r = self.rig(loc(8))
        v = r.c.set_countdown(PID, "on", 300)
        at = v["countdown"]["at"]
        self.assertEqual(at, int(loc(13).timestamp()))
        self.assertEqual(v["countdown"]["seconds_left"], 300 * 60)
        r.clock.set(loc(9))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.c.plug_view(PID)["countdown"]["at"], at)
        r.tick_to(loc(12, 59, 30))
        self.assertFalse(r.on)
        r.tick_to(loc(13))
        self.assertTrue(r.on)
        self.assertIsNone(r.c.plug_view(PID)["countdown"])

    def test_countdown_off_fires(self):
        r = self.rig(loc(8), dev=device(with_countdown=False), dps={"1": True})
        r.worker.poll()
        r.c.set_countdown(PID, "off", 30)
        r.tick_to(loc(8, 30))
        self.assertFalse(r.on)
        self.assertIsNone(r.c.plug_view(PID)["countdown"])

    def test_new_countdown_replaces_old(self):
        r = self.rig(loc(8))
        r.c.set_countdown(PID, "on", 60)
        v = r.c.set_countdown(PID, "off", 30)
        self.assertEqual(v["countdown"]["action"], "off")
        self.assertEqual(v["countdown"]["seconds_left"], 1800)

    def test_manual_switch_cancels_countdown(self):
        r = self.rig(loc(8))
        r.c.set_countdown(PID, "on", 60)
        v = r.c.switch(PID, True)
        self.assertIsNone(v["countdown"])
        self.assertTrue(v["on"])
        r.c.set_countdown(PID, "off", 60)
        v = r.c.switch(PID, False)
        self.assertIsNone(v["countdown"])

    def test_cancel_countdown(self):
        r = self.rig(loc(8))
        r.c.set_countdown(PID, "on", 60)
        v = r.c.cancel_countdown(PID)
        self.assertIsNone(v["countdown"])
        r.tick_to(loc(10))
        self.assertFalse(r.on)

    def test_failed_countdown_on_retried(self):
        r = self.rig(loc(8))
        r.c.set_countdown(PID, "on", 10)
        r.driver.fail_set = 2
        r.tick_to(loc(8, 10))
        self.assertFalse(r.on)
        self.assertIsNotNone(r.c.plug_view(PID)["countdown"])
        r.tick_to(loc(8, 12))
        self.assertTrue(r.on)
        self.assertIsNone(r.c.plug_view(PID)["countdown"])


class CommandTest(ControllerBase):
    def test_switch_failure_raises_command_error_and_keeps_countdown(self):
        r = self.rig(loc(8), dps={"1": True})
        r.worker.poll()
        r.c.set_countdown(PID, "off", 60)
        r.driver.fail_set = 1
        with self.assertRaises(CommandError):
            r.c.switch(PID, False)
        v = r.c.plug_view(PID)
        self.assertIsNotNone(v["error"])
        self.assertIsNotNone(v["countdown"])  # plasa de siguranta ramane
        self.assertFalse(v["online"])
        v = r.c.switch(PID, False)
        self.assertIsNone(v["error"])

    def test_manual_off_consumes_start_until_next_day(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.c._get(PID)["started_by"], iid)
        r.c.switch(PID, False)
        self.assertIsNone(r.c._get(PID)["started_by"])
        r.tick_to(loc(23))
        self.assertFalse(r.on)
        r.tick_to(loc(10, 30, day=6))
        self.assertTrue(r.on)

    def test_unknown_plug(self):
        r = self.rig(loc(8))
        with self.assertRaises(KeyError):
            r.c.plug_view("nope")
        with self.assertRaises(KeyError):
            r.c.switch("nope", True)


class IntervalCrudTest(ControllerBase):
    def test_max_six_intervals(self):
        r = self.rig(loc(8))
        for i in range(6):
            r.c.add_interval(PID, f"0{i}:00", f"0{i}:30")
        with self.assertRaises(ValueError):
            r.c.add_interval(PID, "08:00", "09:00")
        self.assertEqual(len(r.c.plug_view(PID)["schedules"]), 6)

    def test_bad_intervals_rejected(self):
        r = self.rig(loc(8))
        for args in (("25:00", "10:00"), ("10:00", "10:00"), ("x", "y")):
            with self.assertRaises(ValueError):
                r.c.add_interval(PID, *args)
        self.assertEqual(r.c.plug_view(PID)["schedules"], [])

    def test_ids_unique_and_short(self):
        r = self.rig(loc(8))
        ids = []
        for i in range(6):
            ids.append(r.c.add_interval(PID, f"0{i}:00", f"0{i}:30")["schedules"][-1]["id"])
        self.assertEqual(len(set(ids)), 6)
        for i in ids:
            self.assertEqual(len(i), 4)
            int(i, 16)

    def test_update_and_delete_interval(self):
        r = self.rig(loc(8))
        iid = self.schedule(r)
        v = r.c.update_interval(PID, iid, start="11:00", stop="12:00", enabled=False)
        s = v["schedules"][0]
        self.assertEqual((s["start"], s["stop"], s["enabled"]), ("11:00", "12:00", False))
        with self.assertRaises(ValueError):
            r.c.update_interval(PID, iid, start="12:00")  # start == stop
        with self.assertRaises(ValueError):
            r.c.update_interval(PID, iid, color="red")
        with self.assertRaises(KeyError):
            r.c.update_interval(PID, "zzzz", enabled=True)
        v = r.c.delete_interval(PID, iid)
        self.assertEqual(v["schedules"], [])
        with self.assertRaises(KeyError):
            r.c.delete_interval(PID, iid)

    def test_edit_end_while_running_moves_deadline(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        r.clock.set(loc(11))
        r.c.update_interval(PID, iid, stop="17:00")
        self.assertEqual(r.dp9, 6 * 3600)
        r.tick_to(loc(17))
        self.assertFalse(r.on)

    def test_delete_running_interval_keeps_plug_on_until_old_stop(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.delete_interval(PID, iid)
        st = r.c._get(PID)
        self.assertIsNone(st["started_by"])
        self.assertIsNone(st["countdown"])  # slotul countdown ramane liber
        self.assertEqual(st["release_off_at"], int(loc(16).timestamp()))
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "schedule")
        self.assertEqual(r.dp9, 19800)  # DP-ul ramane armat
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(16, 1))
        self.assertFalse(r.on)
        self.assertIsNone(r.c._get(PID)["release_off_at"])

    def test_bad_inching_and_countdown_values_rejected(self):
        r = self.rig(loc(8))
        for m in (0, 721, -5, True, 2.5, "30"):
            with self.assertRaises(ValueError):
                r.c.set_inching(PID, True, m)
        for m in (0, 1441, -1, True, 1.5, "30"):
            with self.assertRaises(ValueError):
                r.c.set_countdown(PID, "on", m)
        with self.assertRaises(ValueError):
            r.c.set_countdown(PID, "toggle", 30)
        v = r.c.set_inching(PID, True, 720)
        self.assertEqual(v["inching"], {"enabled": True, "minutes": 720})
        v = r.c.set_countdown(PID, "on", 1440)
        self.assertEqual(v["countdown"]["seconds_left"], 86400)
        self.assertEqual(r.c.set_inching(PID, False, 1)["inching"],
                         {"enabled": False, "minutes": 1})


class ViewTest(ControllerBase):
    def test_view_shape_and_values(self):
        r = self.rig(loc(9), dps={"1": True, "19": 16500, "20": 2312, "18": 7000})
        r.worker.poll()
        v = r.c.plug_view(PID)
        self.assertEqual(set(v), {
            "id", "name", "online", "on", "metered", "power", "voltage", "current",
            "schedules", "countdown", "inching", "off_deadline", "error"})
        self.assertEqual(v["id"], PID)
        self.assertEqual(v["name"], "Boiler")
        self.assertTrue(v["online"])
        self.assertTrue(v["on"])
        self.assertTrue(v["metered"])
        self.assertEqual((v["power"], v["voltage"], v["current"]), (1650.0, 231.2, 7.0))
        self.assertEqual(v["inching"], {"enabled": False, "minutes": 30})
        self.assertIsNone(v["countdown"])
        self.assertIsNone(v["off_deadline"])
        self.assertIsNone(v["error"])
        self.assertEqual(r.c.plugs_view(), [v])

    def test_not_metered_has_null_measurements(self):
        r = self.rig(loc(9), dev=device(with_power=False))
        v = r.c.plug_view(PID)
        self.assertFalse(v["metered"])
        self.assertEqual((v["power"], v["voltage"], v["current"]), (None, None, None))

    def test_metered_needs_a_power_reading(self):
        r = self.rig(loc(9))
        self.assertFalse(r.c.plug_view(PID)["metered"])  # niciun "19" in dps
        r.driver.dps["19"] = 0
        r.worker.poll()
        self.assertTrue(r.c.plug_view(PID)["metered"])

    def test_offline_view(self):
        r = self.rig(loc(9))
        r.worker.on_reading(PID, PlugReading(False, None, None, None, None, None, "timeout", 0))
        v = r.c.plug_view(PID)
        self.assertFalse(v["online"])
        self.assertEqual(v["error"], "timeout")
        self.assertIsNone(v["power"])
        r.worker.poll()
        self.assertIsNone(r.c.plug_view(PID)["error"])

    def test_view_before_any_reading(self):
        r = self.rig(loc(9))
        r.c = r.build()
        v = r.c.plug_view(PID)
        self.assertFalse(v["online"])
        self.assertFalse(v["on"])


class DstTest(ControllerBase):
    def test_edge_in_fall_back_hour_fires_once(self):
        # 2026-10-25: 03:00-04:00 se repeta; frontul de 03:30 trebuie sa porneasca o singura data
        r = self.rig(loc(2, 0, day=25, month=10))
        self.schedule(r, "03:30", "05:00")
        r.tick_to(loc(2, 59, day=25, month=10))
        self.assertFalse(r.on)
        r.tick_to(datetime(2026, 10, 25, 2, 0, tzinfo=UTC), step=timedelta(minutes=1))
        self.assertTrue(r.on)
        switches = lambda: len([x for x in r.worker.submitted if x[0] == 1])  # noqa: E731
        n = switches()
        r.c.switch(PID, False)
        r.tick_to(datetime(2026, 10, 25, 3, 0, tzinfo=UTC), step=timedelta(minutes=1))
        self.assertFalse(r.on)  # nu mai porneste a doua oara
        self.assertEqual(switches(), n + 1)


class OffNeverFollowedByArmedDpTest(ControllerBase):
    """Critical: dupa o oprire reusita nu se scrie niciodata un DP nenul (priza oprita
    cu countdown armat se poate reporni singura). Workerul real rezolva future-ul inainte
    de citire, deci aici citirile sunt amanate (defer=True)."""

    def assert_clean_off(self, r):
        self.assertFalse(r.on)
        later = r.dp_writes_after_last_off()
        self.assertEqual([x for x in later if x[0] == 9 and x[1] != 0], [], later)
        self.assertEqual(r.dp9, 0)

    def test_schedule_stop_with_inching_pending(self):
        r = self.rig(loc(9), defer=True)
        r.c.set_inching(PID, True, 720)
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(16, 0))
        r.tick_to(loc(16, 2), step=timedelta(seconds=5))
        self.assert_clean_off(r)

    def test_countdown_off_with_inching_pending(self):
        r = self.rig(loc(9), defer=True)
        r.c.set_inching(PID, True, 720)
        r.driver.dps["1"] = True
        r.worker.poll()
        r.c.set_countdown(PID, "off", 30)
        self.assertEqual(r.dp9, 1800)
        r.tick_to(loc(9, 31), step=timedelta(seconds=5))
        self.assert_clean_off(r)

    def test_inching_off_with_countdown_pending(self):
        r = self.rig(loc(9), defer=True)
        r.c.set_inching(PID, True, 30)
        r.driver.dps["1"] = True
        r.worker.poll()
        r.c.set_countdown(PID, "off", 60)
        self.assertEqual(r.dp9, 1800)  # inching e mai aproape
        r.tick_to(loc(9, 31), step=timedelta(seconds=5))
        self.assert_clean_off(r)
        self.assertIsNone(r.c.plug_view(PID)["countdown"])

    def test_manual_off_with_everything_pending(self):
        r = self.rig(loc(9), defer=True)
        r.c.set_inching(PID, True, 30)
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(10, 32), step=timedelta(seconds=5))
        self.assert_clean_off(r)

    def test_external_off_cancels_armed_dp(self):
        r = self.rig(loc(9), defer=True)
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 19800)
        r.driver.dps["1"] = False  # buton fizic
        r.worker.poll()
        r.tick_to(loc(10, 31))
        self.assertEqual(r.dp9, 0)


class HolderEditTest(ControllerBase):
    def running(self):
        r = self.rig(loc(9))
        iid = self.schedule(r)
        r.tick_to(loc(11))
        self.assertTrue(r.on)
        return r, iid

    def test_edit_still_containing_now_keeps_holder(self):
        r, iid = self.running()
        r.c.update_interval(PID, iid, stop="12:00")
        self.assertEqual(r.c._get(PID)["started_by"], iid)
        self.assertEqual(r.dp9, 3600)
        r.tick_to(loc(12))
        self.assertFalse(r.on)

    def test_edit_moving_window_past_now_switches_off_now(self):
        r, iid = self.running()
        v = r.c.update_interval(PID, iid, stop="10:45")
        self.assertFalse(r.on)
        self.assertFalse(v["on"])
        self.assertIsNone(v["countdown"])
        self.assertIsNone(r.c._get(PID)["started_by"])

    def test_edit_past_now_with_unreachable_plug_keeps_retrying(self):
        r, iid = self.running()
        r.driver.fail_set = 1
        r.c.update_interval(PID, iid, stop="10:45")  # nu ridica eroarea
        self.assertTrue(r.on)
        self.assertIsNotNone(r.c._get(PID)["release_off_at"])  # reincercat pana reuseste
        self.assertIsNone(r.c._get(PID)["countdown"])
        self.assertIsNotNone(r.c.plug_view(PID)["error"])
        r.tick_to(loc(11, 1))
        self.assertFalse(r.on)

    def test_disable_keeps_plug_on_until_old_stop(self):
        r, iid = self.running()
        r.c.update_interval(PID, iid, enabled=False)
        self.assertTrue(r.on)
        self.assertEqual(r.c._get(PID)["release_off_at"], int(loc(16).timestamp()))
        self.assertIsNone(r.c._get(PID)["countdown"])
        self.assertEqual(r.dp9, 19800)  # acelasi termen absolut: nu se rescrie
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(16, 1))
        self.assertFalse(r.on)

    def test_disable_keeps_earlier_off_countdown_as_nearest(self):
        r, iid = self.running()
        r.c.set_countdown(PID, "off", 30)
        r.c.update_interval(PID, iid, enabled=False)
        v = r.c.plug_view(PID)
        self.assertEqual(v["countdown"]["at"], int(loc(11, 30).timestamp()))
        self.assertEqual(v["off_deadline"]["reason"], "countdown")
        self.assertEqual(r.c._get(PID)["release_off_at"], int(loc(16).timestamp()))

    def test_disable_with_later_off_countdown_old_stop_wins(self):
        r, iid = self.running()
        r.c.set_countdown(PID, "off", 600)
        r.c.update_interval(PID, iid, enabled=False)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))
        self.assertEqual(v["countdown"]["at"], int(loc(21).timestamp()))

    def test_released_deadline_survives_new_countdown_on_and_cancel(self):
        r, iid = self.running()
        r.c.delete_interval(PID, iid)
        v = r.c.set_countdown(PID, "on", 30)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))
        self.assertEqual(r.dp9, 19800)
        v = r.c.cancel_countdown(PID)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))
        self.assertEqual(r.dp9, 19800)
        r.tick_to(loc(16, 1))
        self.assertFalse(r.on)

    def test_manual_switch_clears_released_deadline(self):
        r, iid = self.running()
        r.c.delete_interval(PID, iid)
        r.c.switch(PID, True)
        self.assertIsNone(r.c._get(PID)["release_off_at"])
        r.tick_to(loc(17))
        self.assertTrue(r.on)

    def test_edit_of_other_interval_leaves_holder(self):
        r, iid = self.running()
        other = self.schedule(r, "20:00", "21:00")
        r.c.update_interval(PID, other, enabled=False)
        self.assertTrue(r.on)
        self.assertEqual(r.c._get(PID)["started_by"], iid)
        self.assertIsNone(r.c._get(PID)["countdown"])


class PendingStopBookkeepingTest(ControllerBase):
    def failing_stop(self):
        r = self.rig(loc(9), dev=device(with_countdown=False))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 10))
        self.assertTrue(r.on)
        return r

    def test_adding_interval_does_not_cancel_pending_stop(self):
        r = self.failing_stop()
        r.c.add_interval(PID, "17:00", "18:00")
        r.tick_to(loc(16, 20))
        r.driver.fail_set = 0
        r.tick_to(loc(16, 21))
        self.assertFalse(r.on)

    def test_editing_other_interval_does_not_cancel_pending_stop(self):
        r = self.failing_stop()
        other = r.c.add_interval(PID, "17:00", "18:00")["schedules"][-1]["id"]
        r.c.update_interval(PID, other, start="17:30")
        r.driver.fail_set = 0
        r.tick_to(loc(16, 12))
        self.assertFalse(r.on)

    def test_later_real_start_cancels_stale_stop(self):
        r = self.rig(loc(9), dev=device(with_countdown=False))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 5))
        r.driver.fail_set = 0
        r.c._set(PID, next_cmd_try=None)
        r.restart()
        r.clock.set(loc(10, 30, day=6))
        r.c.tick()  # azi porneste (start real, mai nou decat stop-ul de ieri)
        self.assertTrue(r.on)
        r.tick_to(loc(10, 40))
        self.assertTrue(r.on)


class AdjacentIntervalsTest(ControllerBase):
    def back_to_back(self, a_id, b_id):
        r = self.rig(loc(9))
        r.set_schedules([(a_id, "10:30", "16:00"), (b_id, "16:00", "18:00")])
        r.tick_to(loc(15, 59))
        self.assertEqual(r.c._get(PID)["started_by"], a_id)
        r.tick_to(loc(16, 0))
        self.assertTrue(r.on)
        self.assertEqual(r.c._get(PID)["started_by"], b_id)
        r.tick_to(loc(17, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(18, 0))
        self.assertFalse(r.on)

    def test_back_to_back_a_id_high(self):
        self.back_to_back("ffff", "0000")

    def test_back_to_back_a_id_low(self):
        self.back_to_back("0000", "ffff")

    def test_back_to_back_restart_replay(self):
        for a, b in (("ffff", "0000"), ("0000", "ffff")):
            r = self.rig(loc(9))
            r.set_schedules([(a, "10:30", "16:00"), (b, "16:00", "18:00")])
            r.clock.set(loc(17))
            r.restart()
            r.c.tick()
            self.assertTrue(r.on)
            self.assertEqual(r.c._get(PID)["started_by"], b)

    def test_overlap_stop_of_other_interval_is_ignored(self):
        r = self.rig(loc(9))
        r.set_schedules([("aaaa", "10:00", "14:00"), ("bbbb", "12:00", "16:00")])
        r.tick_to(loc(12))
        self.assertEqual(r.c._get(PID)["started_by"], "bbbb")
        r.tick_to(loc(14, 1))
        self.assertTrue(r.on)  # stop-ul lui A nu opreste ce tine B
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(16))
        self.assertFalse(r.on)

    def test_overlap_other_order(self):
        r = self.rig(loc(9))
        r.set_schedules([("bbbb", "10:00", "14:00"), ("aaaa", "12:00", "16:00")])
        r.tick_to(loc(14, 1))
        self.assertTrue(r.on)
        r.tick_to(loc(16))
        self.assertFalse(r.on)


class FailSafeResyncTest(ControllerBase):
    def test_dp_rewritten_after_plug_reconnect(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 19800)
        r.worker.on_reading(PID, PlugReading(False, None, None, None, None, None, "wifi", 0))
        r.driver.dps["9"] = 0  # priza a repornit si a pierdut countdown-ul
        r.clock.advance(minutes=10)
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.dp9, 19800 - 600)

    def test_dp_rewritten_when_cleared_from_outside(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.dps["9"] = 0  # sters din SmartLife
        r.clock.advance(minutes=5)
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.dp9, 19800 - 300)

    def test_small_difference_is_not_a_resync(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        n = len(r.worker.submitted)
        r.clock.advance(seconds=30)
        r.worker.poll()  # fake-ul nu numara in jos: 30 s diferenta, sub prag
        r.c.tick()
        self.assertEqual(len(r.worker.submitted), n)


class RetryRobustnessTest(ControllerBase):
    def test_store_error_during_edge_does_not_lose_the_retry(self):
        r = self.rig(loc(9), store_cls=FlakyStore, defer=True)
        iid = self.schedule(r)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.store.fail_updates = 1
        r.tick_to(loc(16, 0))  # stop reusit, dar scrierea starii esueaza
        self.assertFalse(r.on)
        r.tick_to(loc(16, 1))
        done = r.c._get(PID)["last_done"][iid]
        self.assertEqual(done["stop"], loc(16).isoformat())

    def test_pending_start_does_not_override_later_manual_command(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.driver.fail_set = 1
        r.tick_to(loc(10, 30))
        self.assertFalse(r.on)
        r.clock.advance(seconds=10)
        r.c.switch(PID, False)
        r.tick_to(loc(10, 33))
        self.assertFalse(r.on)

    def test_first_reading_with_plug_on_starts_inching(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 20)
        r.driver.dps["1"] = True
        r.restart()  # prima citire dupa repornire: priza e deja pornita
        r.c.tick()
        self.assertEqual(r.dp9, 20 * 60)
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["reason"], "inching")


class ThrottleAndLocksTest(ControllerBase):
    def test_offline_plug_gets_one_automatic_attempt_per_30s(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 30)
        iid = self.schedule(r, "15:30", "16:00")
        r.tick_to(loc(15, 30))
        r.c.set_countdown(PID, "off", 30)  # si countdown-ul scade la 16:00
        self.assertTrue(r.on)
        r.driver.fail_set = 10 ** 6
        r.clock.set(loc(16, 0))
        offs = lambda: len([x for x in r.worker.submitted if x == (1, False)])  # noqa: E731
        r.c.tick()
        self.assertEqual(offs(), 1)
        for sec in range(5, 30, 5):
            r.clock.set(loc(16, 0, sec))
            r.c.tick()
        self.assertEqual(offs(), 1)
        r.clock.set(loc(16, 0, 30))
        r.c.tick()
        self.assertEqual(offs(), 2)
        r.clock.set(loc(16, 0, 45))
        r.c.tick()
        self.assertEqual(offs(), 2)
        r.clock.set(loc(16, 1, 0))
        r.c.tick()
        self.assertEqual(offs(), 3)
        self.assertIsNotNone(iid)

    def test_stuck_plug_does_not_block_commands_to_other_plugs(self):
        da, db = device(), device()
        db.id, db.name = "p2", "Lumina"
        clock = Clock(loc(9))
        drv_a, drv_b = FakeDriver({"1": False, "9": 0}), FakeDriver({"1": False, "9": 0})
        wa, wb = FakeWorker(da, drv_a, clock), FakeWorker(db, drv_b, clock)
        with tempfile.TemporaryDirectory() as tmp:
            c = Controller([da, db], {PID: wa, "p2": wb},
                           JsonStore(os.path.join(tmp, "s.json")), TZ, clock)
            wa.on_reading = wb.on_reading = c.on_reading
            held, release = threading.Event(), threading.Event()

            def hold():
                with c._plock[PID]:
                    held.set()
                    release.wait(10)
            t = threading.Thread(target=hold)
            t.start()
            held.wait(5)
            done = []
            u = threading.Thread(target=lambda: done.append(c.switch("p2", True)))
            u.start()
            u.join(3)
            release.set()
            t.join(5)
            self.assertTrue(done and done[0]["on"])
            self.assertTrue(drv_b.dps["1"])


class ReconnectThrottleTest(ControllerBase):
    def test_failed_command_reconnect_does_not_reset_throttle(self):
        """Workerul real: comanda esuata -> citire offline, apoi ~2 s mai tarziu o citire
        online (priza raspunde la status, dar set_dp esueaza)."""
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.driver.fail_set = 10 ** 6
        offs = lambda: len([x for x in r.worker.submitted if x == (1, False)])  # noqa: E731
        r.clock.set(loc(16, 0))
        r.c.tick()
        for sec in range(5, 60, 5):
            r.clock.set(loc(16, 0, sec))
            r.worker.poll()  # offline -> online intre tick-uri
            r.c.tick()
        self.assertEqual(offs(), 2)  # la 0 s si la 30 s, nu la fiecare tick

    def test_long_offline_reconnect_retries_at_once(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(15, 59))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 0))
        r.driver.fail_set = 0
        r.clock.set(loc(16, 0, 40))  # offline de peste 30 s
        r.worker.poll()
        self.assertTrue(r.on)
        r.c.tick()
        self.assertFalse(r.on)


class ScheduleEndAsDeadlineTest(ControllerBase):
    def test_manual_on_inside_window_shows_and_arms_schedule_end(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(12))
        v = r.c.switch(PID, True)
        self.assertEqual(v["off_deadline"]["reason"], "schedule")
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))
        self.assertEqual(r.dp9, 4 * 3600)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(16))
        self.assertFalse(r.on)

    def test_external_on_inside_window_after_manual_off(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(13))
        r.driver.dps["1"] = True  # buton fizic
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["reason"], "schedule")
        self.assertEqual(r.dp9, 3 * 3600)

    def test_other_holder_stop_is_not_a_deadline(self):
        r = self.rig(loc(9))
        r.set_schedules([("aaaa", "10:00", "14:00"), ("bbbb", "12:00", "16:00")])
        r.tick_to(loc(13))
        v = r.c.plug_view(PID)
        self.assertEqual(r.c._get(PID)["started_by"], "bbbb")
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))
        self.assertEqual(r.dp9, 4 * 3600)  # armat la 12:00 pentru B, nu 2 h pentru A

    def test_no_deadline_outside_any_window(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(17))
        r.c.switch(PID, True)
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])
        self.assertEqual(r.dp9, 0)


class DpCountdownRealismTest(ControllerBase):
    def test_no_rewrite_loop_when_plug_counts_down_normally(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        writes = lambda: len([x for x in r.worker.submitted if x[0] == 9])  # noqa: E731
        self.assertEqual(writes(), 1)
        r.tick_to(loc(15, 59), step=timedelta(seconds=30), poll=True)
        self.assertEqual(writes(), 1)  # citiri la fiecare tick, scrisa o singura data
        r.tick_to(loc(16, 1), step=timedelta(seconds=30), poll=True)
        self.assertFalse(r.on)

    def test_cleared_from_outside_rewritten_once_within_a_minute(self):
        r = self.rig(loc(9))
        self.schedule(r)
        r.tick_to(loc(10, 30))
        r.driver.dps["9"] = 0
        r.tick_to(loc(11, 0), step=timedelta(seconds=5), poll=True)
        writes = [x for x in r.worker.submitted if x[0] == 9]
        self.assertEqual(len(writes), 2)  # initiala + o singura rescriere
        self.assertEqual(r.dp9, 19800 - 5)  # rescris la primul tick dupa stergere


class StaleReleaseTest(ControllerBase):
    """N1: release_off_at ramas nu supravietuieste repornirii si nu opreste urmatoarea pornire."""

    def scenario(self, dev):
        r = self.rig(loc(9), dev=dev)
        iid = self.schedule(r)
        r.tick_to(loc(10, 30))
        r.tick_to(loc(12))
        r.c.delete_interval(PID, iid)
        self.assertEqual(r.c._get(PID)["release_off_at"], int(loc(16).timestamp()))
        r.c.add_interval(PID, "08:00", "09:00")      # programarea de a doua zi
        r.driver.dps["1"] = False                    # oprita de DP-ul ei / cu mana, serverul era cazut
        r.clock.set(loc(17))
        r.restart()
        r.c.tick()
        return r

    def check(self, dev):
        r = self.scenario(dev)
        self.assertIsNone(r.c._get(PID)["release_off_at"])
        n = len([x for x in r.worker.submitted if x == (1, False)])
        r.tick_to(loc(6, day=6), step=timedelta(minutes=5))
        r.driver.dps["1"] = True                     # buton fizic
        r.worker.poll()
        r.tick_to(loc(7, 30, day=6), step=timedelta(seconds=30))
        self.assertTrue(r.on)                        # nu e oprita pe loc
        self.assertEqual(len([x for x in r.worker.submitted if x == (1, False)]), n)
        # (b) programarea 08:00-09:00 ruleaza normal a doua zi
        r.c.switch(PID, False)
        r.tick_to(loc(8, 0, day=6), step=timedelta(seconds=30))
        self.assertTrue(r.on)
        sw = [x for x in r.worker.submitted if x[0] == 1]
        self.assertEqual(sw[-1], (1, True))          # nu (1,True) urmat de (1,False)
        r.tick_to(loc(8, 59, day=6), step=timedelta(seconds=30))
        self.assertTrue(r.on)
        r.tick_to(loc(9, 0, day=6), step=timedelta(seconds=30))
        self.assertFalse(r.on)

    def test_restart_with_plug_off_dp_plug(self):
        self.check(device())

    def test_restart_with_plug_off_no_dp_plug(self):
        self.check(device(with_countdown=False))

    def test_real_off_on_transition_clears_leftover(self):
        r = self.rig(loc(9))
        r.c._mut(PID, lambda p: p.__setitem__("release_off_at", int(loc(8).timestamp())))
        r.driver.dps["1"] = True  # pornire reala observata inainte de orice tick
        r.worker.poll()
        self.assertIsNone(r.c._get(PID)["release_off_at"])
        r.tick_to(loc(9, 5))
        self.assertTrue(r.on)

    def test_known_off_clears_stale_inching_deadline(self):
        r = self.rig(loc(9))
        r.c._mut(PID, lambda p: p.__setitem__("inching_off_at", int(loc(8).timestamp())))
        r.c.tick()
        self.assertIsNone(r.c._get(PID)["inching_off_at"])

    def test_first_reading_on_keeps_release_deadline(self):
        r = self.rig(loc(9), dps={"1": True})
        r.c._mut(PID, lambda p: p.__setitem__("release_off_at", int(loc(10).timestamp())))
        r.restart()  # prima citire: priza pornita, termenul de eliberare e inca valabil
        self.assertEqual(r.c._get(PID)["release_off_at"], int(loc(10).timestamp()))
        r.tick_to(loc(10, 1))
        self.assertFalse(r.on)


if __name__ == "__main__":
    unittest.main()
