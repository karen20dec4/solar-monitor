import json
import logging
import os
import tempfile
import threading
import unittest
from concurrent.futures import Future
from datetime import datetime, timedelta, timezone
from unittest import mock
from zoneinfo import ZoneInfo

from casa.controller import CommandError, Controller, migrate_state
from casa.devices import DeviceInfo
from casa.plugs import FakeDriver, PlugError, PlugReading, decode_inching, decode_reading, encode_inching
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


def device(with_countdown=True, with_power=True, native_inching=False):
    dps = {"switch": 1}
    if with_countdown:
        dps["countdown"] = 9
    if native_inching:
        dps["inching"] = 44
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

    def set_events(self, items):
        """Evenimente zilnice active cu id-uri alese: [(id, ora, actiune), ...]. Ca la add_event,
        cutoff-ul fiecaruia e momentul curent (aparitiile trecute nu se executa)."""
        now = self.clock.now.isoformat()

        def f(p):
            p["events"] = [{"id": i, "time": t, "action": a, "days": None, "enabled": True}
                           for i, t, a in items]
            p["event_cutoff"] = {i: now for i, _, _ in items}
        self.c._mut(PID, f)

    def switch_commands(self):
        return [x for x in self.worker.submitted if x[0] == 1]

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

    def event(self, r, time="16:00", action="off", days=None, enabled=True):
        """Adauga un eveniment si intoarce id-ul lui (ultimul adaugat in stare)."""
        r.c.add_event(PID, time, action, days, enabled)
        return r.c._get(PID)["events"][-1]["id"]

    def on_off(self, r, on="10:30", off="16:00"):
        """Perechea clasica PORNESTE `on` + OPRESTE `off`, zilnic."""
        return self.event(r, on, "on"), self.event(r, off, "off")


MIN5 = timedelta(minutes=5)


class EventExecutionTest(ControllerBase):
    def test_off_event_switches_off_at_its_time(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        self.event(r, "17:00", "off")
        r.tick_to(loc(16, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(17, 0))
        self.assertFalse(r.on)
        v = r.c.plug_view(PID)
        self.assertIsNone(v["error"])
        self.assertFalse(v["on"])

    def test_on_event_switches_on(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
        r.tick_to(loc(10, 29))
        self.assertFalse(r.on)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        self.assertIsNone(r.c.plug_view(PID)["error"])

    def test_event_in_the_requested_state_is_consumed_without_a_command(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        self.event(r, "10:30", "on")           # deja pornita
        r.tick_to(loc(11))
        self.assertEqual(r.switch_commands(), [])
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(10, 30).isoformat())
        r2 = self.rig(loc(9))
        self.event(r2, "10:30", "off")         # deja oprita
        r2.tick_to(loc(11))
        self.assertEqual(r2.worker.submitted, [])
        self.assertEqual(r2.c._get(PID)["last_event_at"], loc(10, 30).isoformat())

    def test_on_event_applies_inching(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 30)
        self.event(r, "10:30", "on")
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "inching")
        self.assertEqual(v["off_deadline"]["seconds_left"], 1800)
        self.assertEqual(r.dp9, 1800)

    def test_manual_after_event_wins(self):
        # PORNESTE 11:00 ratat (serverul cazut); la 11:10 userul opreste priza din aplicatie,
        # inainte ca programatorul sa apuce sa execute evenimentul ratat
        r = self.rig(loc(10, 50))
        self.event(r, "11:00", "on")
        r.clock.set(loc(11, 10))
        r.restart()
        r.c.switch(PID, False)
        r.tick_to(loc(11, 30))
        self.assertFalse(r.on)
        self.assertNotIn((1, True), r.worker.submitted)
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(11).isoformat())

    def test_manual_off_after_on_event_stays_off_until_next_day(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.c.switch(PID, False)
        r.tick_to(loc(23), step=MIN5)
        self.assertFalse(r.on)
        r.tick_to(loc(10, 30, day=6), step=MIN5)
        self.assertTrue(r.on)

    def test_manual_off_between_events_is_not_overridden(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(12))
        r.c.switch(PID, False)
        n = len(r.worker.submitted)
        r.tick_to(loc(16, 0))
        self.assertFalse(r.on)
        self.assertIsNone(r.c.plug_view(PID)["error"])
        self.assertEqual(len(r.worker.submitted), n)  # nici o comanda in plus

    def test_manual_on_between_events_is_stopped_by_the_off_event(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(13))
        r.c.switch(PID, True)
        r.tick_to(loc(16))
        self.assertFalse(r.on)

    def test_disabled_event_does_nothing(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on", enabled=False)
        r.tick_to(loc(17))
        self.assertFalse(r.on)
        self.assertEqual(r.worker.submitted, [])

    def test_events_act_only_on_their_weekdays(self):
        # 2026-11-05 e joi (4), 2026-11-06 vineri (5); priza fara DP de countdown: nu conteaza decat serverul
        r = self.rig(loc(9), dev=device(with_countdown=False), dps={"1": True})
        r.worker.poll()
        self.event(r, "16:00", "off", days=[5])
        r.tick_to(loc(16, 5), step=MIN5)
        self.assertTrue(r.on)                   # joi: nu
        r.tick_to(loc(15, 55, day=6), step=MIN5)
        self.assertTrue(r.on)
        r.tick_to(loc(16, 0, day=6), step=MIN5)
        self.assertFalse(r.on)                  # vineri: da
        r2 = self.rig(loc(9), dev=device(with_countdown=False), dps={"1": True})
        r2.worker.poll()
        self.event(r2, "16:00", "off", days=[4])
        r2.tick_to(loc(16, 0), step=MIN5)
        self.assertFalse(r2.on)

    def test_invalid_stored_event_is_ignored(self):
        r = self.rig(loc(9))
        r.c._mut(PID, lambda p: p["events"].append({"id": "zz", "time": "25:99"}))
        self.event(r, "10:30", "on")
        r.tick_to(loc(11))
        self.assertTrue(r.on)
        self.assertEqual(len(r.c.plug_view(PID)["events"]), 1)


class MissedEventsTest(ControllerBase):
    def test_missed_off_runs_late(self):
        r = self.rig(loc(9))
        self.event(r, "17:00", "off")
        r.c.switch(PID, True)
        self.assertTrue(r.on)
        r.clock.set(loc(20))                    # serverul a fost cazut 17:00-20:00
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_off_executed_days_later(self):
        r = self.rig(loc(9))
        self.event(r, "17:00", "off")
        r.c.switch(PID, True)
        r.clock.set(loc(7, 0, day=7))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_on_recovered_until_the_next_event(self):
        r = self.rig(loc(9))
        self.on_off(r, "11:00", "16:00")
        r.clock.set(loc(12))
        r.restart()
        r.c.tick()
        self.assertTrue(r.on)

    def test_missed_on_dropped_after_next_event(self):
        # PORNESTE 11:00 + OPRESTE 16:00, serverul revine la 16:30: se ia in calcul doar OPRESTE
        r = self.rig(loc(9))
        self.on_off(r, "11:00", "16:00")
        r.clock.set(loc(16, 30))
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.switch_commands(), [])      # nici pornire, nici oprire
        r.tick_to(loc(17))
        self.assertEqual(r.switch_commands(), [])
        # priza gasita pornita: oprita
        r2 = self.rig(loc(9), dps={"1": True})
        r2.worker.poll()
        self.on_off(r2, "11:00", "16:00")
        r2.clock.set(loc(16, 30))
        r2.restart()
        r2.c.tick()
        self.assertFalse(r2.on)
        self.assertEqual(r2.switch_commands(), [(1, False)])

    def test_missed_events_wait_for_first_reading(self):
        r = self.rig(loc(9))
        self.event(r, "16:00", "off")
        r.c.switch(PID, True)
        r.clock.set(loc(18, 40))
        r.restart(poll=False)
        r.c.tick()
        self.assertTrue(r.on)  # nu stim inca starea
        r.worker.poll()
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_events_run_again_on_reconnect(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_next = 1000  # priza cade
        r.worker.on_reading(PID, PlugReading(False, None, None, None, None, None, "x", 0))
        r.tick_to(loc(16, 5))
        self.assertTrue(r.on)  # comanda nu a putut ajunge
        r.driver.fail_next = 0
        r.worker.poll()  # revine online
        r.c.tick()
        self.assertFalse(r.on)

    def test_missed_off_skipped_after_newer_manual_on(self):
        r = self.rig(loc(9))
        self.event(r, "16:00", "off")
        r.c.switch(PID, True)
        r.clock.set(loc(17))
        r.c.switch(PID, True)  # dupa OPRESTE 16:00 (care n-a fost vazut)
        r.restart()
        r.c.tick()
        self.assertTrue(r.on)
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(16).isoformat())


class RetryTest(ControllerBase):
    def test_missed_off_retried_until_success(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        self.event(r, "16:00", "off")
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

    def test_off_retry_has_no_time_limit(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        self.event(r, "16:00", "off")
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(23), step=timedelta(seconds=60))
        self.assertTrue(r.on)
        r.driver.fail_set = 0
        r.tick_to(loc(23, 2), step=timedelta(seconds=30))
        self.assertFalse(r.on)

    def test_on_retried_every_30s(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
        r.driver.fail_set = 2
        r.tick_to(loc(10, 30))
        self.assertFalse(r.on)
        self.assertEqual(len(r.worker.submitted), 1)
        r.clock.advance(seconds=10)
        r.c.tick()
        self.assertEqual(len(r.worker.submitted), 1)  # inca nu au trecut 30 s
        r.tick_to(loc(10, 31, 30), step=timedelta(seconds=10))
        self.assertEqual(len(r.switch_commands()), 3)
        self.assertTrue(r.on)
        self.assertIsNone(r.c.plug_view(PID)["error"])

    def test_on_retry_stops_when_a_newer_event_becomes_the_latest(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 1))              # PORNESTE 10:30 reincercat la 30 s, fara succes
        self.assertFalse(r.on)
        self.assertGreater(len(r.worker.submitted), 3)
        r.driver.fail_set = 0
        n = len(r.worker.submitted)
        r.tick_to(loc(17), step=timedelta(seconds=30))
        self.assertFalse(r.on)             # OPRESTE 16:00 e cel mai recent: priza e oprita, PORNESTE nu se mai incearca
        self.assertEqual(len(r.worker.submitted), n)
        self.assertIsNone(r.c._rt[PID].retry_at)
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(16).isoformat())

    def test_consumed_event_is_not_retried(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
        r.driver.fail_set = 1
        r.tick_to(loc(10, 31))
        self.assertTrue(r.on)
        self.assertIsNone(r.c._rt[PID].retry_at)
        n = len(r.worker.submitted)
        r.tick_to(loc(13), step=timedelta(seconds=30))
        self.assertEqual(len(r.worker.submitted), n)
        self.assertTrue(r.on)


class DeadlineTest(ControllerBase):
    def test_off_deadline_written_to_plug_countdown_dp(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 19800)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "schedule")
        self.assertEqual(v["off_deadline"]["seconds_left"], 19800)

    def test_deadline_is_next_off_event(self):
        r = self.rig(loc(13), dps={"1": True})
        r.worker.poll()
        eid = self.event(r, "17:00", "off")
        self.assertEqual(r.dp9, 4 * 3600)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "schedule")
        self.assertEqual(v["off_deadline"]["at"], int(loc(17).timestamp()))
        self.assertEqual(v["off_deadline"]["seconds_left"], 4 * 3600)
        r.c.delete_event(PID, eid)
        self.assertEqual(r.dp9, 0)
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])

    def test_on_event_is_not_a_deadline(self):
        r = self.rig(loc(13), dps={"1": True})
        r.worker.poll()
        self.event(r, "17:00", "on")
        self.assertEqual(r.dp9, 0)
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])

    def test_off_deadline_with_inching_is_nearest(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 30)
        self.on_off(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 1800)
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "inching")
        self.assertEqual(v["off_deadline"]["seconds_left"], 1800)

    def test_deadline_not_rewritten_within_5s(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        n = len(r.worker.submitted)
        r.tick_to(loc(10, 40), step=timedelta(seconds=5))
        self.assertEqual(len(r.worker.submitted), n)

    def test_countdown_dp_rewritten_when_deadline_changes(self):
        r = self.rig(loc(9))
        self.on_off(r)
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

    def test_external_off_clears_off_countdown(self):
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        r.c.set_countdown(PID, "off", 60)
        r.driver.dps["1"] = False
        r.worker.poll()
        self.assertIsNone(r.c._get(PID)["countdown"])

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

    def test_unknown_plug(self):
        r = self.rig(loc(8))
        with self.assertRaises(KeyError):
            r.c.plug_view("nope")
        with self.assertRaises(KeyError):
            r.c.switch("nope", True)


class EventCrudTest(ControllerBase):
    def test_max_twelve_events(self):
        r = self.rig(loc(8))
        for i in range(12):
            r.c.add_event(PID, f"{i:02d}:00", "on" if i % 2 == 0 else "off", None)
        with self.assertRaises(ValueError) as cm:
            r.c.add_event(PID, "13:00", "on", None)
        self.assertEqual(str(cm.exception), "cel mult 12 evenimente pe priza")
        self.assertEqual(len(r.c.plug_view(PID)["events"]), 12)
        r.c.delete_event(PID, r.c._get(PID)["events"][0]["id"])
        self.assertEqual(len(r.c.add_event(PID, "13:00", "on", None)["events"]), 12)

    def test_bad_events_rejected(self):
        r = self.rig(loc(8))
        for args in (("25:00", "on", None), ("x", "on", None), ("10:00", "toggle", None),
                     ("10:00", "on", []), ("10:00", "on", [0]), ("10:00", "on", [8]),
                     ("10:00", "on", ["1"]), ("10:00", "on", [True]), ("10:00", "on", 5),
                     (None, "on", None)):
            with self.assertRaises(ValueError, msg=repr(args)):
                r.c.add_event(PID, *args)
        self.assertEqual(r.c.plug_view(PID)["events"], [])

    def test_ids_unique_and_short(self):
        r = self.rig(loc(8))
        ids = []
        for i in range(12):
            r.c.add_event(PID, f"{i:02d}:00", "on", None)
            ids.append(r.c._get(PID)["events"][-1]["id"])
        self.assertEqual(len(set(ids)), 12)
        for i in ids:
            self.assertEqual(len(i), 4)
            int(i, 16)

    def test_days_are_normalized(self):
        r = self.rig(loc(8))
        v = r.c.add_event(PID, "10:00", "on", [3, 1, 3])
        self.assertEqual(v["events"][0]["days"], [1, 3])
        v = r.c.add_event(PID, "11:00", "on", [7, 6, 5, 4, 3, 2, 1])
        self.assertIsNone(v["events"][1]["days"])

    def test_update_and_delete_event(self):
        r = self.rig(loc(8))
        eid = self.event(r, "10:30", "on")
        v = r.c.update_event(PID, eid, time="11:00", action="off", days=[1, 2], enabled=False)
        e = v["events"][0]
        self.assertEqual((e["time"], e["action"], e["days"], e["enabled"]), ("11:00", "off", [1, 2], False))
        before = r.c._get(PID)["events"]
        for bad in ({"time": "25:00"}, {"color": "red"}, {"days": []}, {"action": "x"}):
            with self.assertRaises(ValueError, msg=repr(bad)):
                r.c.update_event(PID, eid, **bad)
        self.assertEqual(r.c._get(PID)["events"], before)       # o editare gresita nu schimba nimic
        v = r.c.update_event(PID, eid, days=None)
        self.assertIsNone(v["events"][0]["days"])
        v = r.c.delete_event(PID, eid)
        self.assertEqual(v["events"], [])
        self.assertNotIn(eid, r.c._get(PID)["event_cutoff"])

    def test_unknown_event_keyerror(self):
        r = self.rig(loc(8))
        with self.assertRaisesRegex(KeyError, "eveniment necunoscut"):
            r.c.update_event(PID, "zzzz", enabled=True)
        with self.assertRaisesRegex(KeyError, "eveniment necunoscut"):
            r.c.delete_event(PID, "zzzz")
        with self.assertRaises(KeyError):
            r.c.add_event("nope", "10:00", "on", None)

    def test_conflict_rejected(self):
        r = self.rig(loc(8))
        a = self.event(r, "10:00", "on")
        for action, days in (("off", None), ("on", None), ("on", [1, 2])):
            with self.assertRaises(ValueError, msg=(action, days)) as cm:
                r.c.add_event(PID, "10:00", action, days)
            self.assertTrue(str(cm.exception).startswith("conflict"), str(cm.exception))
        self.assertEqual(len(r.c.plug_view(PID)["events"]), 1)
        # un eveniment inactiv, sau la alte zile, nu intra in conflict
        b = self.event(r, "10:00", "off", enabled=False)
        r.c.update_event(PID, a, days=[1, 2, 3])
        c = self.event(r, "10:00", "off", days=[4, 5])
        # editare spre conflict si activare spre conflict
        with self.assertRaises(ValueError) as cm:
            r.c.update_event(PID, c, days=[3, 4])
        self.assertTrue(str(cm.exception).startswith("conflict"))
        with self.assertRaises(ValueError) as cm:
            r.c.update_event(PID, b, enabled=True)
        self.assertTrue(str(cm.exception).startswith("conflict"))
        with self.assertRaises(ValueError):
            r.c.update_event(PID, c, time="10:00", days=[1])
        # un eveniment nu intra in conflict cu el insusi
        r.c.update_event(PID, a, action="off")
        self.assertEqual(r.c._get(PID)["events"][0]["action"], "off")

    def test_event_added_in_the_past_today_does_not_fire(self):
        r = self.rig(loc(10, 30))
        self.event(r, "10:00", "on")
        r.tick_to(loc(11), step=MIN5)
        self.assertFalse(r.on)
        r.restart()                              # nici dupa o repornire
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.worker.submitted, [])
        r.tick_to(loc(9, 55, day=6), step=MIN5)
        self.assertFalse(r.on)
        r.tick_to(loc(10, 0, day=6), step=MIN5)
        self.assertTrue(r.on)                    # a doua zi actioneaza
        # la fel un OPRESTE: nu opreste retroactiv
        r2 = self.rig(loc(10, 30), dps={"1": True})
        r2.worker.poll()
        self.event(r2, "10:00", "off")
        r2.tick_to(loc(11), step=MIN5)
        self.assertTrue(r2.on)

    def test_event_moved_to_a_past_time_today_does_not_fire(self):
        r = self.rig(loc(9))
        eid = self.event(r, "12:00", "on")
        r.clock.set(loc(10, 30))
        r.c.update_event(PID, eid, time="10:00")
        r.tick_to(loc(11), step=MIN5)
        self.assertFalse(r.on)
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)
        r.tick_to(loc(10, 0, day=6), step=MIN5)
        self.assertTrue(r.on)

    def test_event_enabled_after_its_time_today_does_not_fire(self):
        r = self.rig(loc(9))
        eid = self.event(r, "10:00", "on", enabled=False)
        r.clock.set(loc(10, 30))
        r.c.update_event(PID, eid, enabled=True)
        r.tick_to(loc(11), step=MIN5)
        self.assertFalse(r.on)
        r.restart()
        r.c.tick()
        self.assertFalse(r.on)
        r.tick_to(loc(10, 0, day=6), step=MIN5)
        self.assertTrue(r.on)

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


LEGACY_KEYS = ("schedules", "last_done", "started_by", "release_off_at", "last_start_at", "cutoff")


def legacy_plug(**kw):
    """Starea unei prize in formatul vechi (intervale), cum o scria versiunea 3.43."""
    p = {"schedules": [{"id": "4a95", "start": "07:00", "stop": "07:30", "enabled": True}],
         "countdown": None, "inching": {"enabled": False, "minutes": 30},
         "last_done": {"4a95": {"start": "2026-11-04T05:00:00+00:00", "stop": "2026-11-04T05:30:00+00:00"}},
         "started_by": None, "inching_off_at": None, "last_manual_at": None,
         "release_off_at": None, "last_start_at": "2026-11-04T05:00:00+00:00",
         "cutoff": {"4a95": "2026-10-20T08:00:00+00:00"}}
    p.update(kw)
    return p


class MigrationTest(ControllerBase):
    def legacy_rig(self, now, plug=None, extra=None, **kw):
        """Controller pornit la `now` peste un state.json in formatul vechi."""
        r = self.rig(loc(6, 50), **kw)
        state = {"plugs": {PID: plug or legacy_plug()}}
        state.update(extra or {})
        r.store.save(state)
        r.clock.set(now)
        r.restart()
        return r

    def test_migration_converts_intervals_without_firing(self):
        r = self.legacy_rig(loc(7, 10))
        st = r.c._get(PID)
        self.assertEqual(st["events"], [
            {"id": "4a95s", "time": "07:00", "action": "on", "days": None, "enabled": True},
            {"id": "4a95e", "time": "07:30", "action": "off", "days": None, "enabled": True}])
        self.assertEqual(st["last_event_at"], loc(7, 10).isoformat())
        for k in LEGACY_KEYS:
            self.assertNotIn(k, st)
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.worker.submitted, [])        # PORNESTE 07:00 nu se executa retroactiv
        # priza pornita de mana: OPRESTE 07:30 tot se trimite
        r.driver.dps["1"] = True
        r.worker.poll()
        r.tick_to(loc(7, 29, 30))
        self.assertTrue(r.on)
        r.tick_to(loc(7, 30))
        self.assertFalse(r.on)

    def test_migration_without_history_does_not_fire_retroactively(self):
        plug = legacy_plug(last_done={}, cutoff={}, last_start_at=None)
        r = self.legacy_rig(loc(7, 10), plug=plug, dps={"1": True})
        r.c.tick()
        self.assertTrue(r.on)                           # OPRESTE de ieri 07:30 nu mai conteaza
        self.assertEqual(r.switch_commands(), [])
        r.tick_to(loc(7, 30))
        self.assertFalse(r.on)

    def test_migration_keeps_the_demo_marker_and_other_top_level_keys(self):
        r = self.legacy_rig(loc(7, 10), extra={"demo": True})
        with open(r.path, encoding="utf-8") as f:
            data = json.load(f)
        self.assertIs(data["demo"], True)
        self.assertEqual([e["id"] for e in data["plugs"][PID]["events"]], ["4a95s", "4a95e"])

    def test_migration_runs_once(self):
        r = self.legacy_rig(loc(7, 10))
        with open(r.path, encoding="utf-8") as f:
            first = f.read()
        r.clock.set(loc(7, 20))
        r.restart()
        with open(r.path, encoding="utf-8") as f:
            self.assertEqual(f.read(), first)           # a doua pornire nu rescrie nimic

    def test_migration_keeps_enabled_flag_and_skips_malformed_intervals(self):
        plug = legacy_plug(schedules=[
            {"id": "aa11", "start": "08:00", "stop": "09:00", "enabled": False},
            {"id": "bad1", "start": "25:00", "stop": "09:00", "enabled": True},
            {"id": 7, "start": "08:00", "stop": "09:00", "enabled": True},
            {"start": "08:00"}, "gunoi"])
        r = self.legacy_rig(loc(7, 10), plug=plug)
        self.assertEqual(r.c._get(PID)["events"], [
            {"id": "aa11s", "time": "08:00", "action": "on", "days": None, "enabled": False},
            {"id": "aa11e", "time": "09:00", "action": "off", "days": None, "enabled": False}])

    def test_migrate_state_function(self):
        now = loc(7, 10)
        data = {"plugs": {PID: legacy_plug(), "p2": {"events": [], "event_cutoff": {}, "last_event_at": None}},
                "demo": True}
        self.assertTrue(migrate_state(data, now))
        self.assertEqual(data["demo"], True)
        self.assertEqual(data["plugs"]["p2"], {"events": [], "event_cutoff": {}, "last_event_at": None})
        self.assertEqual(data["plugs"][PID]["last_event_at"], now.isoformat())
        self.assertFalse(migrate_state(data, now + timedelta(hours=1)))   # nimic de convertit a doua oara
        self.assertEqual(data["plugs"][PID]["last_event_at"], now.isoformat())
        for empty in ({}, {"plugs": {}}, {"plugs": None}, {"plugs": {PID: None}}):
            self.assertFalse(migrate_state(empty, now))

    def test_nothing_to_migrate_writes_no_state_file(self):
        r = self.rig(loc(9))
        self.assertFalse(os.path.exists(r.path))

    def test_corrupt_state_does_not_stop_startup(self):
        r = self.rig(loc(9))
        with open(r.path, "w", encoding="utf-8") as f:
            f.write("{nu e json")
        with self.assertLogs("casa.controller", level="ERROR"):
            r.restart(poll=False)                       # CorruptStore se logheaza, serviciul porneste
        with open(r.path, encoding="utf-8") as f:
            self.assertEqual(f.read(), "{nu e json")    # fisierul stricat nu se atinge


class ViewTest(ControllerBase):
    def test_view_shape_and_values(self):
        r = self.rig(loc(9), dps={"1": True, "19": 16500, "20": 2312, "18": 7000})
        r.worker.poll()
        v = r.c.plug_view(PID)
        self.assertEqual(set(v), {
            "id", "name", "online", "on", "metered", "power", "voltage", "current",
            "schedules", "events", "next_event", "countdown", "inching", "off_deadline", "error"})
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
        self.assertEqual((v["schedules"], v["events"], v["next_event"]), ([], [], None))
        self.assertEqual(r.c.plugs_view(), [v])

    def test_view_has_events_next_event_and_empty_schedules(self):
        r = self.rig(loc(9))                       # joi 2026-11-05
        off = self.event(r, "17:00", "off")
        on = self.event(r, "10:00", "on", days=[6, 7])
        off2 = self.event(r, "10:00", "off", days=[1, 2])
        dis = self.event(r, "08:00", "on", days=[1], enabled=False)
        v = r.c.plug_view(PID)
        self.assertEqual(v["schedules"], [])
        # ordonate dupa (ora, zile)
        self.assertEqual([e["id"] for e in v["events"]], [dis, off2, on, off])
        self.assertEqual(v["events"][0], {"id": dis, "time": "08:00", "action": "on", "days": [1],
                                          "enabled": False})
        self.assertEqual(v["events"][2]["days"], [6, 7])
        # urmatorul eveniment ACTIV: OPRESTE 17:00 azi (08:00 de luni e inactiv)
        self.assertEqual(v["next_event"], {"id": off, "time": "17:00", "action": "off",
                                           "at": int(loc(17).timestamp())})
        r.c.delete_event(PID, off)
        # PORNESTE sambata 10:00 (2026-11-07) e inaintea lui OPRESTE de luni
        nxt = r.c.plug_view(PID)["next_event"]
        self.assertEqual((nxt["id"], nxt["at"]), (on, int(loc(10, 0, day=7).timestamp())))
        r.c.update_event(PID, on, enabled=False)
        nxt = r.c.plug_view(PID)["next_event"]
        self.assertEqual((nxt["id"], nxt["at"]), (off2, int(loc(10, 0, day=9).timestamp())))
        r.c.update_event(PID, off2, enabled=False)
        self.assertIsNone(r.c.plug_view(PID)["next_event"])

    def test_events_are_stored_as_lists_in_json_form(self):
        r = self.rig(loc(9))
        self.event(r, "10:00", "on", days=[2, 4])
        e = r.c.plug_view(PID)["events"][0]
        self.assertEqual(e["days"], [2, 4])
        json.dumps(r.c.plug_view(PID))                # vederea trebuie sa fie serializabila

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
    def test_event_in_fall_back_hour_fires_once(self):
        # 2026-10-25: 03:00-04:00 se repeta; evenimentul de 03:30 trebuie sa porneasca o singura data
        r = self.rig(loc(2, 0, day=25, month=10))
        self.event(r, "03:30", "on")
        r.tick_to(loc(2, 59, day=25, month=10))
        self.assertFalse(r.on)
        r.tick_to(datetime(2026, 10, 25, 0, 29, tzinfo=UTC), step=timedelta(minutes=1))
        self.assertFalse(r.on)
        r.tick_to(datetime(2026, 10, 25, 0, 45, tzinfo=UTC), step=timedelta(minutes=1))
        self.assertTrue(r.on)                    # 03:30 EEST = 00:30Z
        n = len(r.switch_commands())
        r.c.switch(PID, False)                   # inainte de a doua 03:30 (EET = 01:30Z)
        r.tick_to(datetime(2026, 10, 25, 3, 0, tzinfo=UTC), step=timedelta(minutes=1))
        self.assertFalse(r.on)                   # nu mai porneste a doua oara
        self.assertEqual(len(r.switch_commands()), n + 1)


class OffNeverFollowedByArmedDpTest(ControllerBase):
    """Critical: dupa o oprire reusita nu se scrie niciodata un DP nenul (priza oprita
    cu countdown armat se poate reporni singura). Workerul real rezolva future-ul inainte
    de citire, deci aici citirile sunt amanate (defer=True)."""

    def assert_clean_off(self, r):
        self.assertFalse(r.on)
        later = r.dp_writes_after_last_off()
        self.assertEqual([x for x in later if x[0] == 9 and x[1] != 0], [], later)
        self.assertEqual(r.dp9, 0)

    def test_off_event_with_inching_pending(self):
        r = self.rig(loc(9), defer=True)
        r.c.set_inching(PID, True, 720)
        self.on_off(r)
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
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(10, 32), step=timedelta(seconds=5))
        self.assert_clean_off(r)

    def test_external_off_cancels_armed_dp(self):
        r = self.rig(loc(9), defer=True)
        self.on_off(r)
        r.tick_to(loc(10, 30))
        self.assertEqual(r.dp9, 19800)
        r.driver.dps["1"] = False  # buton fizic
        r.worker.poll()
        r.tick_to(loc(10, 31))
        self.assertEqual(r.dp9, 0)


class EventEditWhileRunningTest(ControllerBase):
    """Editarea/stergerea/dezactivarea unui OPRESTE cat priza e pornita: se aplica de acum,
    nimic retroactiv; priza ramane pornita, fara termen vechi (spec §4.4)."""

    def running(self):
        r = self.rig(loc(9))
        on, off = self.on_off(r)
        r.tick_to(loc(11))
        self.assertTrue(r.on)
        self.assertEqual(r.dp9, 19800)          # scris la 10:30 pentru 16:00
        return r, on, off

    def test_edit_off_time_moves_the_deadline(self):
        r, _, off = self.running()
        r.c.update_event(PID, off, time="17:00")
        self.assertEqual(r.dp9, 6 * 3600)
        r.tick_to(loc(16, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(17))
        self.assertFalse(r.on)

    def test_edit_off_to_a_time_already_past_does_not_switch_off_now(self):
        r, _, off = self.running()
        v = r.c.update_event(PID, off, time="10:45")
        self.assertTrue(r.on)
        self.assertTrue(v["on"])
        self.assertEqual(v["off_deadline"]["at"], int(loc(10, 45, day=6).timestamp()))
        self.assertEqual(r.dp9, 23 * 3600 + 45 * 60)
        r.tick_to(loc(12))
        self.assertTrue(r.on)

    def test_disable_off_event_leaves_the_plug_on_without_deadline(self):
        r, _, off = self.running()
        v = r.c.update_event(PID, off, enabled=False)
        self.assertTrue(r.on)
        self.assertIsNone(v["off_deadline"])
        self.assertEqual(r.dp9, 0)
        r.tick_to(loc(17), step=MIN5)
        self.assertTrue(r.on)
        # reactivat la 17:00 (dupa ora lui): nu actioneaza azi, termenul e maine 16:00
        v = r.c.update_event(PID, off, enabled=True)
        self.assertTrue(r.on)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16, day=6).timestamp()))

    def test_enable_back_before_its_time_rearms_the_deadline(self):
        r, _, off = self.running()
        r.c.update_event(PID, off, enabled=False)
        r.clock.set(loc(12))
        r.c.update_event(PID, off, enabled=True)
        self.assertEqual(r.dp9, 4 * 3600)
        r.tick_to(loc(16))
        self.assertFalse(r.on)

    def test_delete_off_event_leaves_the_plug_on(self):
        r, _, off = self.running()
        v = r.c.delete_event(PID, off)
        self.assertTrue(r.on)
        self.assertIsNone(v["off_deadline"])
        self.assertEqual(r.dp9, 0)
        r.tick_to(loc(17), step=MIN5)
        self.assertTrue(r.on)

    def test_edit_of_the_on_event_leaves_the_deadline_alone(self):
        r, on, _ = self.running()
        n = len(r.worker.submitted)
        r.c.update_event(PID, on, time="12:00")
        self.assertEqual(len(r.worker.submitted), n)
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["at"], int(loc(16).timestamp()))

    def test_manual_switch_does_not_touch_the_events(self):
        r, _, _ = self.running()
        r.c.switch(PID, False)
        self.assertEqual(len(r.c.plug_view(PID)["events"]), 2)
        r.c.switch(PID, True)
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["at"], int(loc(16).timestamp()))


class PendingOffBookkeepingTest(ControllerBase):
    def failing_off(self):
        r = self.rig(loc(9), dev=device(with_countdown=False))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 10))
        self.assertTrue(r.on)
        return r

    def test_adding_event_does_not_cancel_pending_off(self):
        r = self.failing_off()
        r.c.add_event(PID, "17:00", "on", None)
        r.tick_to(loc(16, 20))
        r.driver.fail_set = 0
        r.tick_to(loc(16, 21))
        self.assertFalse(r.on)

    def test_editing_other_event_does_not_cancel_pending_off(self):
        r = self.failing_off()
        r.c.add_event(PID, "17:00", "on", None)
        other = r.c._get(PID)["events"][-1]["id"]
        r.c.update_event(PID, other, time="17:30")
        r.driver.fail_set = 0
        r.tick_to(loc(16, 12))
        self.assertFalse(r.on)

    def test_saving_an_event_unchanged_keeps_its_pending_occurrence(self):
        r = self.failing_off()
        off = r.c._get(PID)["events"][1]["id"]
        r.c.update_event(PID, off, time="16:00", action="off", enabled=True)   # nicio schimbare
        r.driver.fail_set = 0
        r.tick_to(loc(16, 12))
        self.assertFalse(r.on)

    def test_later_event_supersedes_a_stale_pending_off(self):
        r = self.failing_off()
        r.driver.fail_set = 0
        r.c._set(PID, next_cmd_try=None)
        r.restart()
        r.clock.set(loc(10, 30, day=6))
        r.c.tick()          # azi PORNESTE 10:30 e cel mai recent: priza e pornita, OPRESTE de ieri e depasit
        self.assertTrue(r.on)
        r.tick_to(loc(10, 40))
        self.assertTrue(r.on)


class SameInstantEventsTest(ControllerBase):
    """Intervale lipite (OPRESTE al unuia = PORNESTE al altuia) migrate: aceeasi ora, deci
    la egalitate PORNESTE e considerat cel mai recent si priza ramane pornita."""

    def back_to_back(self, a_id, b_id):
        r = self.rig(loc(9))
        r.store.save({"plugs": {PID: {"schedules": [
            {"id": a_id, "start": "10:30", "stop": "16:00", "enabled": True},
            {"id": b_id, "start": "16:00", "stop": "18:00", "enabled": True}]}}})
        r.restart()
        return r

    def check(self, a_id, b_id):
        r = self.back_to_back(a_id, b_id)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        n = len(r.switch_commands())
        r.tick_to(loc(16, 0))
        self.assertTrue(r.on)
        self.assertEqual(len(r.switch_commands()), n)         # nicio oprire/repornire la 16:00
        r.tick_to(loc(17, 59))
        self.assertTrue(r.on)
        r.tick_to(loc(18, 0))
        self.assertFalse(r.on)

    def test_back_to_back_a_id_high(self):
        self.check("ffff", "0000")

    def test_back_to_back_a_id_low(self):
        self.check("0000", "ffff")

    def test_back_to_back_restart_replay(self):
        for a, b in (("ffff", "0000"), ("0000", "ffff")):
            r = self.back_to_back(a, b)
            r.clock.set(loc(17))
            r.restart()
            r.c.tick()
            self.assertTrue(r.on)

    def test_back_to_back_shared_instant_is_not_armed_in_the_plug(self):
        # OPRESTE 16:00 urmat de PORNESTE 16:00 nu opreste nimic: termenul din priza e urmatorul
        # OPRESTE real (18:00). Altfel priza s-ar opri singura la 16:00, serverul ar vedea o apasare
        # de buton si PORNESTE 16:00 ar fi consumat cu priza oprita.
        r = self.back_to_back("0000", "ffff")
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        self.assertEqual(r.dp9, int((loc(18) - loc(10, 30)).total_seconds()))
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["at"], int(loc(18).timestamp()))
        self.assertEqual(v["off_deadline"]["reason"], "schedule")

    def test_off_and_on_at_the_same_time_on_different_days_is_still_a_deadline(self):
        r = self.rig(loc(9))
        self.event(r, "16:00", "off", days=[1, 2, 3, 4, 5])    # joi 5 nov
        self.event(r, "16:00", "on", days=[6, 7])             # alte zile: nu se suprapun
        r.tick_to(loc(10))
        v = r.c.switch(PID, True)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16).timestamp()))

    def test_off_seen_at_the_shared_instant_is_a_button_press_and_wins(self):
        # DP-ul nu e armat pe 16:00, deci o oprire vazuta atunci vine de la buton: userul castiga,
        # PORNESTE 16:00 e consumat fara comanda (documenteaza comportamentul ales)
        r = self.back_to_back("0000", "ffff")
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        n = len(r.switch_commands())
        r.clock.set(loc(16, 0, 3))
        r.driver.dps["1"] = False
        r.worker.poll()
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.c._get(PID)["last_manual_at"], loc(16, 0, 3).isoformat())
        r.tick_to(loc(17, 59), step=timedelta(minutes=10))
        self.assertFalse(r.on)
        self.assertEqual(len(r.switch_commands()), n)


class ButtonPressTest(ControllerBase):
    """Butonul fizic = comanda manuala (spec 4.2b): o schimbare de stare vazuta la citire, cat priza
    e online, pe care n-a cauzat-o serverul, pune last_manual_at si anuleaza countdown-ul."""

    @staticmethod
    def press(r, on):
        r.driver.dps["1"] = on
        r.worker.poll()

    @staticmethod
    def manual(r):
        return r.c._get(PID)["last_manual_at"]

    @staticmethod
    def offline(r):
        r.worker.on_reading(PID, PlugReading(False, None, None, None, None, None, "x", 0))

    def test_button_press_sets_last_manual_at_both_ways(self):
        r = self.rig(loc(9))
        self.assertIsNone(self.manual(r))
        r.clock.set(loc(9, 5))
        self.press(r, True)
        self.assertEqual(self.manual(r), loc(9, 5).isoformat())
        r.clock.set(loc(9, 7))
        self.press(r, False)
        self.assertEqual(self.manual(r), loc(9, 7).isoformat())

    def test_button_press_cancels_countdown(self):
        r = self.rig(loc(9))
        r.c.set_countdown(PID, "on", 30)         # priza oprita: ar porni peste 30 min
        r.clock.set(loc(9, 5))
        self.press(r, True)                      # userul o porneste de la buton
        self.assertIsNone(r.c._get(PID)["countdown"])
        r.clock.set(loc(9, 6))
        self.press(r, False)                     # si o opreste: countdown-ul nu mai revine
        r.tick_to(loc(9, 40))
        self.assertFalse(r.on)
        self.assertEqual(r.switch_commands(), [])

    def test_button_off_blocks_retried_on(self):
        # PORNESTE 11:00 nu poate fi trimis (comenzile esueaza) si ramane de reincercat la 30 s;
        # pana atunci userul o porneste si o opreste de la buton: reincercarea nu o mai porneste
        r = self.rig(loc(10, 50))
        self.event(r, "11:00", "on")
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(11, 0))
        self.assertFalse(r.on)
        self.assertIsNotNone(r.c._rt[PID].retry_at)
        r.clock.set(loc(11, 0, 10))
        r.worker.poll()                          # revine online (reconectare, nu apasare)
        self.assertIsNone(self.manual(r))
        r.clock.set(loc(11, 0, 20))
        self.press(r, True)
        r.clock.set(loc(11, 0, 25))
        self.press(r, False)
        self.assertEqual(self.manual(r), loc(11, 0, 25).isoformat())
        r.driver.fail_set = 0
        n = len(r.worker.submitted)
        r.tick_to(loc(11, 5))
        self.assertFalse(r.on)
        self.assertEqual(len(r.worker.submitted), n)
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(11).isoformat())   # consumat, nu repornit

    def test_reconnect_is_not_a_press(self):
        r = self.rig(loc(9), dps={"1": True})    # prima citire de dupa pornire
        self.assertIsNone(self.manual(r))
        r.c.set_countdown(PID, "on", 600)
        self.offline(r)
        r.clock.set(loc(9, 10))
        self.press(r, False)                     # prima citire dupa offline: alta stare, nu conteaza
        self.assertIsNone(self.manual(r))
        self.assertIsNotNone(r.c._get(PID)["countdown"])     # countdown-ul nu e anulat de reconectare
        self.offline(r)
        r.clock.set(loc(9, 20))
        self.press(r, True)
        self.assertIsNone(self.manual(r))
        r.clock.set(loc(9, 21))
        self.press(r, False)                     # intre doua citiri online conteaza
        self.assertEqual(self.manual(r), loc(9, 21).isoformat())

    def test_first_reading_with_a_known_state_after_an_unknown_one_is_not_a_press(self):
        r = self.rig(loc(9))
        r.restart(poll=False)
        r.worker.on_reading(PID, PlugReading(True, None, None, None, None, None, None, 0))
        r.clock.set(loc(9, 1))
        self.press(r, True)                      # starea dinainte nu era cunoscuta
        self.assertIsNone(self.manual(r))

    def test_server_command_echo_is_not_a_press(self):
        # comanda trimisa de server: citirea care o confirma (inainte sau dupa ce serverul consemneaza
        # singur starea noua) nu e apasare. Un countdown care ar fi anulat de o apasare ramane.
        for defer in (False, True):
            with self.subTest(defer=defer):
                r = self.rig(loc(10, 29), defer=defer)
                self.event(r, "10:30", "on")
                r.c.set_countdown(PID, "off", 120)
                r.tick_to(loc(10, 30))
                r.worker.flush()
                self.assertTrue(r.on)
                self.assertIsNone(self.manual(r))
                self.assertIsNotNone(r.c._get(PID)["countdown"])
                r2 = self.rig(loc(16, 59), dps={"1": True}, defer=defer)
                r2.worker.poll()
                self.event(r2, "17:00", "off")
                r2.c.set_countdown(PID, "on", 600)
                r2.tick_to(loc(17, 0))
                r2.worker.flush()
                self.assertFalse(r2.on)
                self.assertIsNone(self.manual(r2))
                self.assertIsNotNone(r2.c._get(PID)["countdown"])

    def stuck_worker(self, r):
        """Workerul nu raspunde la timp: comanda intra in coada, future-ul ramane nerezolvat."""
        def submit(dp, value):
            r.worker.submitted.append((dp, value))
            return Future()
        r.worker.submit = submit

    @staticmethod
    def working_again(r):
        del r.worker.submit

    def test_server_command_echo_is_not_a_press_when_the_command_timed_out(self):
        # future-ul expira (CommandError), dar priza executa comanda: citirea de dupa, in 15 s de la
        # trimitere, arata starea ceruta si nu e apasare
        r = self.rig(loc(10, 29))
        self.event(r, "10:30", "on")
        r.c.set_countdown(PID, "off", 120)
        self.stuck_worker(r)
        with mock.patch("casa.controller.CMD_TIMEOUT_S", 0.01):
            r.tick_to(loc(10, 30))
        self.assertEqual(r.switch_commands(), [(1, True)])
        self.assertIn("timeout", r.c.plug_view(PID)["error"])
        self.working_again(r)
        r.clock.set(loc(10, 30, 12))
        self.press(r, True)                      # priza a executat comanda
        self.assertTrue(r.c.plug_view(PID)["on"])
        self.assertIsNone(self.manual(r))
        self.assertIsNotNone(r.c._get(PID)["countdown"])
        r.tick_to(loc(11), step=timedelta(seconds=30))
        self.assertEqual(r.switch_commands(), [(1, True)])   # PORNESTE consumat, fara a doua comanda

    def test_command_echo_window_is_15_seconds(self):
        for secs, counts in ((15, False), (16, True)):
            with self.subTest(secs=secs):
                r = self.rig(loc(10, 29))
                self.event(r, "10:30", "on")
                self.stuck_worker(r)
                with mock.patch("casa.controller.CMD_TIMEOUT_S", 0.01):
                    r.tick_to(loc(10, 30))
                r.clock.set(loc(10, 30, secs))
                self.press(r, True)
                self.assertEqual(self.manual(r) is not None, counts)

    def test_press_to_the_other_state_right_after_a_command_counts(self):
        r = self.rig(loc(10, 29))
        self.event(r, "10:30", "on")
        r.tick_to(loc(10, 30))
        self.assertTrue(r.on)
        r.clock.set(loc(10, 30, 5))
        self.press(r, False)                     # serverul a cerut PORNIT; cineva a oprit-o
        self.assertEqual(self.manual(r), loc(10, 30, 5).isoformat())

    def test_store_error_while_saving_a_press_does_not_break_the_reading(self):
        r = self.rig(loc(9), dps={"1": True}, store_cls=FlakyStore)
        r.worker.poll()
        self.event(r, "17:00", "off")
        self.assertEqual(r.dp9, 8 * 3600)
        r.clock.set(loc(9, 5))
        r.store.fail_updates = 1               # prima scriere (apasarea) esueaza
        self.press(r, False)
        self.assertFalse(r.c.plug_view(PID)["on"])
        r.c.tick()
        self.assertEqual(r.dp9, 0)             # restul prelucrarii (DP-ul armat anulat) s-a facut

    def test_dp_fired_off_is_harmless(self):
        # priza se opreste singura la termenul scris in DP; serverul o vede ca apasare, dar
        # priza e deja oprita: OPRESTE 17:00 e consumat fara comanda, DP-ul ramas e anulat
        r = self.rig(loc(9), dps={"1": True})
        r.worker.poll()
        self.event(r, "17:00", "off")
        self.assertEqual(r.dp9, 8 * 3600)
        r.tick_to(loc(16, 59), step=timedelta(minutes=30))
        r.clock.set(loc(17, 0, 3))
        r.driver.dps["9"] = 0
        self.press(r, False)
        r.c.tick()
        self.assertFalse(r.on)
        self.assertEqual(r.switch_commands(), [])
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(17).isoformat())
        self.assertEqual(r.dp9, 0)


class FailSafeResyncTest(ControllerBase):
    def test_dp_rewritten_after_plug_reconnect(self):
        r = self.rig(loc(9))
        self.on_off(r)
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
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.driver.dps["9"] = 0  # sters din SmartLife
        r.clock.advance(minutes=5)
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.dp9, 19800 - 300)

    def test_small_difference_is_not_a_resync(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        n = len(r.worker.submitted)
        r.clock.advance(seconds=30)
        r.worker.poll()  # fake-ul nu numara in jos: 30 s diferenta, sub prag
        r.c.tick()
        self.assertEqual(len(r.worker.submitted), n)


class RetryRobustnessTest(ControllerBase):
    def test_store_error_while_switching_does_not_lose_the_retry(self):
        r = self.rig(loc(9), store_cls=FlakyStore, defer=True)
        self.on_off(r)
        r.tick_to(loc(15, 59))
        self.assertTrue(r.on)
        r.store.fail_updates = 1
        r.tick_to(loc(16, 0))  # oprire reusita, dar scrierea starii esueaza
        self.assertFalse(r.on)
        r.tick_to(loc(16, 1))
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(16).isoformat())

    def test_store_error_while_consuming_does_not_lose_the_retry(self):
        r = self.rig(loc(9), store_cls=FlakyStore, dps={"1": True})
        r.worker.poll()
        self.event(r, "10:30", "on")           # priza e deja pornita: singura scriere e consumarea
        r.tick_to(loc(10, 29))
        r.store.fail_updates = 1
        r.tick_to(loc(10, 30))
        self.assertIsNone(r.c._get(PID)["last_event_at"])
        self.assertIsNotNone(r.c._rt[PID].retry_at)
        r.tick_to(loc(10, 31))
        self.assertEqual(r.c._get(PID)["last_event_at"], loc(10, 30).isoformat())
        self.assertIsNone(r.c._rt[PID].retry_at)

    def test_pending_on_does_not_override_later_manual_command(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
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
        self.event(r, "16:00", "off")
        r.tick_to(loc(15, 30))
        r.c.switch(PID, True)               # inching: se opreste la 16:00
        r.c.set_countdown(PID, "off", 30)   # si countdown-ul scade la 16:00
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
        self.on_off(r)
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
        self.on_off(r)
        r.tick_to(loc(15, 59))
        r.driver.fail_set = 10 ** 6
        r.tick_to(loc(16, 0))
        r.driver.fail_set = 0
        r.clock.set(loc(16, 0, 40))  # offline de peste 30 s
        r.worker.poll()
        self.assertTrue(r.on)
        r.c.tick()
        self.assertFalse(r.on)


class NextOffEventAsDeadlineTest(ControllerBase):
    def test_manual_on_between_events_shows_and_arms_the_next_off(self):
        r = self.rig(loc(9))
        self.on_off(r)
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

    def test_external_on_after_manual_off_arms_the_next_off(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.c.switch(PID, False)
        r.tick_to(loc(13))
        r.driver.dps["1"] = True  # buton fizic
        r.worker.poll()
        r.c.tick()
        self.assertEqual(r.c.plug_view(PID)["off_deadline"]["reason"], "schedule")
        self.assertEqual(r.dp9, 3 * 3600)

    def test_no_off_event_no_deadline(self):
        r = self.rig(loc(9))
        self.event(r, "10:30", "on")
        self.event(r, "17:00", "off", enabled=False)
        r.tick_to(loc(17))
        r.c.switch(PID, True)
        self.assertIsNone(r.c.plug_view(PID)["off_deadline"])
        self.assertEqual(r.dp9, 0)

    def test_off_event_of_tomorrow_is_the_deadline_after_todays_time_passed(self):
        r = self.rig(loc(9))
        self.event(r, "16:00", "off")
        r.tick_to(loc(17))
        v = r.c.switch(PID, True)
        self.assertEqual(v["off_deadline"]["at"], int(loc(16, day=6).timestamp()))
        self.assertEqual(r.dp9, 23 * 3600)


class FarDeadlineTest(ControllerBase):
    """Termen de oprire la peste 24 h (spec 4.3): nu se scrie nimic in priza (un countdown plafonat
    ar opri-o prea devreme); se scrie abia cand intra sub 24 h. Serverul tot il executa."""

    @staticmethod
    def nonzero_dp_writes(r):
        return [x for x in r.worker.submitted if x[0] == 9 and x[1] != 0]

    def weekday_off(self, r):
        self.event(r, "17:00", "off", days=(1, 2, 3, 4, 5))

    def test_far_off_deadline_not_written(self):
        r = self.rig(loc(18, 0, day=6))                       # vineri 2026-11-06 18:00
        self.weekday_off(r)
        v = r.c.switch(PID, True)                             # urmatorul OPRESTE: luni 17:00 (71 h)
        self.assertEqual(self.nonzero_dp_writes(r), [])
        self.assertEqual(v["off_deadline"]["at"], int(loc(17, day=9).timestamp()))   # se vede, serverul il executa
        r.tick_to(loc(16, 30, day=8), step=timedelta(minutes=30))     # duminica 16:30: 24,5 h
        self.assertEqual(self.nonzero_dp_writes(r), [])
        self.assertTrue(r.on)
        r.clock.set(loc(17, 30, day=8))                       # duminica 17:30: 23,5 h
        r.c.tick()
        self.assertEqual(r.dp9, 84600)
        self.assertEqual(r.c._rt[PID].cd_written, int(loc(17, day=9).timestamp()))
        r.tick_to(loc(17, day=9), step=timedelta(minutes=30))
        self.assertFalse(r.on)                                # luni 17:00

    def test_far_deadline_is_written_when_it_comes_within_24_h(self):
        r = self.rig(loc(16, 59, 30, day=8), dps={"1": True})     # duminica, OPRESTE luni 17:00 la 24 h 30 s
        r.worker.poll()
        self.weekday_off(r)
        r.tick_to(loc(16, 59, 55, day=8), step=timedelta(seconds=5))
        self.assertEqual(self.nonzero_dp_writes(r), [])
        r.tick_to(loc(17, 0, 5, day=8), step=timedelta(seconds=5))
        self.assertEqual(r.dp9, 86400)                        # exact 24 h: intra sub plafon
        self.assertEqual(len(self.nonzero_dp_writes(r)), 1)

    def test_far_deadline_clears_previous_dp(self):
        r = self.rig(loc(18, 0, day=6))
        self.weekday_off(r)
        r.c.set_inching(PID, True, 10)
        r.c.switch(PID, True)
        self.assertEqual(r.dp9, 600)                          # inching 10 min
        v = r.c.set_inching(PID, False, 10)
        self.assertEqual(r.dp9, 0)                            # singurul termen ramas e la peste 24 h
        self.assertIsNone(r.c._rt[PID].cd_written)
        self.assertEqual(v["off_deadline"]["at"], int(loc(17, day=9).timestamp()))

    def test_far_deadline_replaces_a_near_one_with_nothing(self):
        r = self.rig(loc(18, 0, day=6))
        self.weekday_off(r)
        r.c.switch(PID, True)
        r.c.set_countdown(PID, "off", 60)
        self.assertEqual(r.dp9, 3600)
        r.c.cancel_countdown(PID)
        self.assertEqual(r.dp9, 0)
        r.tick_to(loc(19), step=timedelta(minutes=10))
        self.assertEqual(self.nonzero_dp_writes(r), [(9, 3600)])


class DpCountdownRealismTest(ControllerBase):
    def test_no_rewrite_loop_when_plug_counts_down_normally(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        writes = lambda: len([x for x in r.worker.submitted if x[0] == 9])  # noqa: E731
        self.assertEqual(writes(), 1)
        r.tick_to(loc(15, 59), step=timedelta(seconds=30), poll=True)
        self.assertEqual(writes(), 1)  # citiri la fiecare tick, scrisa o singura data
        r.tick_to(loc(16, 1), step=timedelta(seconds=30), poll=True)
        self.assertFalse(r.on)

    def test_cleared_from_outside_rewritten_once_within_a_minute(self):
        r = self.rig(loc(9))
        self.on_off(r)
        r.tick_to(loc(10, 30))
        r.driver.dps["9"] = 0
        r.tick_to(loc(11, 0), step=timedelta(seconds=5), poll=True)
        writes = [x for x in r.worker.submitted if x[0] == 9]
        self.assertEqual(len(writes), 2)  # initiala + o singura rescriere
        self.assertEqual(r.dp9, 19800 - 5)  # rescris la primul tick dupa stergere


class StaleDeadlineTest(ControllerBase):
    def test_known_off_clears_stale_inching_deadline(self):
        r = self.rig(loc(9))
        r.c._mut(PID, lambda p: p.__setitem__("inching_off_at", int(loc(8).timestamp())))
        r.c.tick()
        self.assertIsNone(r.c._get(PID)["inching_off_at"])


class RenameTest(ControllerBase):
    def test_rename_changes_view_name(self):
        r = self.rig(loc(9))
        self.assertEqual(r.c.rename(PID, "Calorifer")["name"], "Calorifer")
        self.assertEqual(r.c.plug_view(PID)["name"], "Calorifer")
        with self.assertRaises(KeyError):
            r.c.rename("nu-exista", "X")


class NativeInchingTest(ControllerBase):
    """Inching-ul din firmware-ul prizei (DP 44 `switch_inching`, base64: activ, secunde pe 2 octeti).
    Bucatarie, 2026-11-05: setat la 25 min din SmartLife, invizibil in aplicatie."""

    def native(self, value="AQXc", **kw):
        return self.rig(loc(9), dev=device(native_inching=True), dps={"44": value}, **kw)

    def test_codec(self):
        self.assertEqual(decode_inching("AQXc"), (True, 1500))
        self.assertEqual(decode_inching("AAcI"), (False, 1800))
        self.assertEqual(encode_inching(True, 1800), "AQcI")
        self.assertEqual(encode_inching(False, 1500), "AAXc")
        for bad in (None, 5, "", "!!", "AQ==", "AQXcAA=="):
            self.assertIsNone(decode_inching(bad))

    def test_plug_setting_is_shown_and_used_on_switch_on(self):
        r = self.native("AQXc")
        self.assertEqual(r.c.plug_view(PID)["inching"], {"enabled": True, "minutes": 25})
        r.driver.dps["1"] = True
        r.worker.poll()
        r.c.tick()
        v = r.c.plug_view(PID)
        self.assertEqual(v["off_deadline"]["reason"], "inching")
        self.assertEqual(v["off_deadline"]["seconds_left"], 1500)
        self.assertEqual(r.dp9, 1500)

    def test_set_inching_writes_the_plug(self):
        r = self.native("AQXc")
        v = r.c.set_inching(PID, False, 25)
        self.assertEqual(r.driver.dps["44"], "AAXc")
        self.assertEqual(v["inching"], {"enabled": False, "minutes": 25})
        r.c.set_inching(PID, True, 30)
        self.assertEqual(r.driver.dps["44"], "AQcI")
        r.worker.poll()
        self.assertEqual(r.c.plug_view(PID)["inching"], {"enabled": True, "minutes": 30})

    def test_change_made_in_smartlife_is_picked_up(self):
        r = self.native("AQXc")
        r.driver.dps["44"] = "AAcI"
        r.worker.poll()
        self.assertEqual(r.c.plug_view(PID)["inching"], {"enabled": False, "minutes": 30})

    def test_failed_write_changes_nothing(self):
        r = self.native("AQXc")
        r.driver.fail_set = 1
        with self.assertRaises(CommandError):
            r.c.set_inching(PID, False, 25)
        self.assertEqual(r.c.plug_view(PID)["inching"], {"enabled": True, "minutes": 25})
        self.assertEqual(r.driver.dps["44"], "AQXc")

    def test_plug_without_native_inching_keeps_server_inching(self):
        r = self.rig(loc(9))
        r.c.set_inching(PID, True, 10)
        self.assertNotIn("44", r.driver.dps)
        self.assertEqual(r.c.plug_view(PID)["inching"], {"enabled": True, "minutes": 10})


if __name__ == "__main__":
    unittest.main()
