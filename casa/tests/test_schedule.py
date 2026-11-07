"""Teste pentru logica programarilor (pura, fara I/O)."""
import unittest
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from casa.schedule import (Edge, Event, Interval, OffDeadline, Occurrence,
                           active_interval, edges_between, events_conflict,
                           interval_end, missed_edges, nearest_off,
                           next_occurrence, normalize_days,
                           occurrences_between, parse_hhmm)

TZ = ZoneInfo("Europe/Bucharest")
UTC = timezone.utc


def loc(y, mo, d, h=0, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=TZ)


class ScheduleTest(unittest.TestCase):
    def test_daily_edges_in_one_day(self):
        iv = Interval("a", "10:30", "16:00", True)
        edges = edges_between([iv], loc(2026, 11, 5, 0, 0),
                              loc(2026, 11, 5, 23, 59), TZ)
        self.assertEqual([(e.kind, e.at) for e in edges], [
            ("start", datetime(2026, 11, 5, 8, 30, tzinfo=UTC)),
            ("stop", datetime(2026, 11, 5, 14, 0, tzinfo=UTC)),
        ])
        self.assertTrue(all(e.interval_id == "a" for e in edges))

    def test_edges_window_is_open_closed(self):
        iv = Interval("a", "10:30", "16:00", True)
        start = datetime(2026, 11, 5, 8, 30, tzinfo=UTC)
        self.assertEqual(edges_between([iv], start, start, TZ), [])
        self.assertEqual(len(edges_between([iv], start - timedelta(seconds=1),
                                           start, TZ)), 1)

    def test_over_midnight_interval(self):
        iv = Interval("n", "22:00", "06:00", True)
        self.assertEqual(active_interval([iv], loc(2026, 11, 5, 23, 0), TZ), iv)
        self.assertEqual(active_interval([iv], loc(2026, 11, 6, 5, 0), TZ), iv)
        self.assertIsNone(active_interval([iv], loc(2026, 11, 5, 12, 0), TZ))
        self.assertEqual(interval_end(iv, loc(2026, 11, 5, 23, 0), TZ),
                         loc(2026, 11, 6, 6, 0))
        self.assertEqual(interval_end(iv, loc(2026, 11, 6, 5, 0), TZ),
                         loc(2026, 11, 6, 6, 0))

    def test_active_interval_boundaries_and_disabled(self):
        iv = Interval("a", "10:30", "16:00", True)
        off = Interval("b", "10:00", "17:00", False)
        self.assertEqual(active_interval([off, iv], loc(2026, 11, 5, 10, 30), TZ), iv)
        self.assertIsNone(active_interval([off, iv], loc(2026, 11, 5, 16, 0), TZ))
        self.assertIsNone(active_interval([off], loc(2026, 11, 5, 12, 0), TZ))

    def test_disabled_interval_has_no_edges(self):
        iv = Interval("a", "10:30", "16:00", False)
        self.assertEqual(edges_between([iv], loc(2026, 11, 5), loc(2026, 11, 6), TZ), [])

    def test_spring_forward_edge_fires_once(self):
        iv = Interval("a", "03:30", "07:00", True)
        edges = edges_between([iv], loc(2026, 3, 28, 12), loc(2026, 3, 29, 12), TZ)
        starts = [e for e in edges if e.kind == "start"]
        self.assertEqual(len(starts), 1)
        # ora inexistenta -> primul instant dupa gol: 04:00 local = 01:00Z
        self.assertEqual(starts[0].at, datetime(2026, 3, 29, 1, 0, tzinfo=UTC))

    def test_fall_back_edge_fires_once(self):
        iv = Interval("a", "03:30", "07:00", True)
        edges = edges_between([iv], loc(2026, 10, 24, 12), loc(2026, 10, 25, 12), TZ)
        starts = [e for e in edges if e.kind == "start"]
        self.assertEqual(len(starts), 1)
        # prima aparitie (fold=0): 03:30 EEST = 00:30Z
        self.assertEqual(starts[0].at, datetime(2026, 10, 25, 0, 30, tzinfo=UTC))

    def test_spring_forward_start_in_gap_stop_just_after(self):
        iv = Interval("a", "03:30", "04:15", True)
        edges = edges_between([iv], loc(2026, 3, 28, 12), loc(2026, 3, 29, 12), TZ)
        self.assertEqual([(e.kind, e.at) for e in edges], [
            ("start", datetime(2026, 3, 29, 1, 0, tzinfo=UTC)),
            ("stop", datetime(2026, 3, 29, 1, 15, tzinfo=UTC)),
        ])
        at = datetime(2026, 3, 29, 1, 5, tzinfo=UTC)
        self.assertEqual(active_interval([iv], at, TZ), iv)
        self.assertEqual(interval_end(iv, at, TZ),
                         datetime(2026, 3, 29, 1, 15, tzinfo=UTC))
        # in zilele normale nu se schimba nimic
        edges = edges_between([iv], loc(2026, 3, 29, 12), loc(2026, 3, 30, 12), TZ)
        self.assertEqual([(e.kind, e.at) for e in edges], [
            ("start", datetime(2026, 3, 30, 0, 30, tzinfo=UTC)),
            ("stop", datetime(2026, 3, 30, 1, 15, tzinfo=UTC)),
        ])

    def test_spring_forward_both_in_gap_no_edges(self):
        iv = Interval("a", "03:10", "03:50", True)
        edges = edges_between([iv], loc(2026, 3, 28, 12), loc(2026, 3, 29, 12), TZ)
        self.assertEqual(edges, [])
        for h, m in ((0, 59), (1, 0), (1, 30)):
            at = datetime(2026, 3, 29, h, m, tzinfo=UTC)
            self.assertIsNone(active_interval([iv], at, TZ))

    def test_over_midnight_across_dst(self):
        iv = Interval("n", "22:00", "06:00", True)
        edges = edges_between([iv], loc(2026, 3, 28, 12), loc(2026, 3, 29, 23), TZ)
        self.assertEqual([e.kind for e in edges], ["start", "stop", "start"])
        self.assertEqual(interval_end(iv, loc(2026, 3, 28, 23), TZ),
                         loc(2026, 3, 29, 6))

    def test_missed_edges_skips_already_done(self):
        iv = Interval("a", "10:30", "16:00", True)
        now = loc(2026, 11, 5, 16, 5)
        start_at = datetime(2026, 11, 5, 8, 30, tzinfo=UTC)
        last_done = {"a": {"start": start_at.isoformat()}}
        missed = missed_edges([iv], last_done, now, TZ)
        self.assertEqual([(e.kind, e.at) for e in missed],
                         [("stop", datetime(2026, 11, 5, 14, 0, tzinfo=UTC))])

    def test_missed_edges_never_done_and_lookback(self):
        iv = Interval("a", "10:30", "16:00", True)
        now = loc(2026, 11, 5, 16, 5)
        missed = missed_edges([iv], {}, now, TZ)
        self.assertEqual([e.kind for e in missed], ["start", "stop"])
        short = missed_edges([iv], {}, now, TZ, lookback=timedelta(minutes=3))
        self.assertEqual(short, [])

    def test_nearest_off_picks_earliest(self):
        t = datetime(2026, 11, 5, 12, 0, tzinfo=UTC)
        a = OffDeadline(t + timedelta(minutes=30), "schedule")
        b = OffDeadline(t + timedelta(minutes=10), "inching")
        c = OffDeadline(t + timedelta(minutes=20), "countdown")
        self.assertIs(nearest_off([a, b, c]), b)
        self.assertIsNone(nearest_off([]))

    def test_nearest_off_tie_prefers_countdown(self):
        t = datetime(2026, 11, 5, 12, 0, tzinfo=UTC)
        a = OffDeadline(t, "inching")
        b = OffDeadline(t, "schedule")
        c = OffDeadline(t, "countdown")
        self.assertEqual(nearest_off([a, b, c]).reason, "countdown")
        self.assertEqual(nearest_off([a, b]).reason, "schedule")

    def test_bad_hhmm_and_equal_start_stop_raise(self):
        for bad in ("", "9", "24:00", "10:60", "ab:cd", "10:5", "10-30", "1:30"):
            with self.assertRaises(ValueError, msg=bad):
                parse_hhmm(bad)
        self.assertEqual(parse_hhmm("07:05"), (7, 5))
        with self.assertRaises(ValueError):
            Interval("a", "10:00", "10:00", True)
        with self.assertRaises(ValueError):
            Interval("a", "10:00", "x", True)


class EventTest(unittest.TestCase):
    def test_normalize_days(self):
        self.assertIsNone(normalize_days(None))
        self.assertEqual(normalize_days([3, 1, 1]), (1, 3))
        self.assertIsNone(normalize_days(range(1, 8)))
        self.assertEqual(normalize_days((7, 6)), (6, 7))
        for bad in ([], [0], [8], [True], ["1"], [1.0], [1, None]):
            with self.assertRaises(ValueError, msg=repr(bad)):
                normalize_days(bad)

    def test_event_rejects_bad_fields(self):
        with self.assertRaises(ValueError):
            Event("a", "24:00", "off", None, True)
        with self.assertRaises(ValueError):
            Event("a", "17:00", "toggle", None, True)
        with self.assertRaises(ValueError):
            Event("a", "17:00", "off", (3, 1), True)  # nesortat
        with self.assertRaises(ValueError):
            Event("a", "17:00", "off", (1, 2, 3, 4, 5, 6, 7), True)  # toate 7 = None
        with self.assertRaises(ValueError):
            Event("a", "17:00", "off", (), True)

    def test_event_dict_round_trip(self):
        e = Event("a", "17:00", "off", (1, 2, 3), False)
        d = e.to_dict()
        self.assertEqual(d, {"id": "a", "time": "17:00", "action": "off",
                             "days": [1, 2, 3], "enabled": False})
        self.assertEqual(Event.from_dict(d), e)
        daily = Event("b", "06:05", "on", None, True)
        self.assertEqual(daily.to_dict()["days"], None)
        self.assertEqual(Event.from_dict(daily.to_dict()), daily)

    def test_event_from_dict_normalizes_days(self):
        e = Event.from_dict({"id": "a", "time": "17:00", "action": "off",
                             "days": [5, 1, 5], "enabled": True})
        self.assertEqual(e.days, (1, 5))
        e = Event.from_dict({"id": "a", "time": "17:00", "action": "off",
                             "days": [1, 2, 3, 4, 5, 6, 7], "enabled": True})
        self.assertIsNone(e.days)

    def test_events_conflict(self):
        daily = Event("a", "17:00", "off", None, True)
        wknd = Event("b", "17:00", "on", (6, 7), True)
        self.assertTrue(events_conflict(daily, wknd))
        self.assertTrue(events_conflict(wknd, daily))
        mon = Event("c", "17:00", "on", (1,), True)
        tue = Event("d", "17:00", "on", (2,), True)
        self.assertFalse(events_conflict(mon, tue))
        self.assertFalse(events_conflict(daily, Event("e", "17:00", "on", None, False)))
        self.assertFalse(events_conflict(Event("e", "17:00", "on", None, False), daily))
        self.assertFalse(events_conflict(daily, Event("f", "17:01", "on", None, True)))
        # acelasi id = acelasi eveniment (editare), nu conflict
        self.assertFalse(events_conflict(daily, Event("a", "17:00", "on", None, True)))


class OccurrenceTest(unittest.TestCase):
    def test_daily_occurrences_two_days(self):
        e = Event("a", "17:00", "off", None, True)
        occ = occurrences_between(
            [e], datetime(2026, 11, 6, 0, 0, tzinfo=UTC),
            datetime(2026, 11, 8, 0, 0, tzinfo=UTC), TZ)
        self.assertEqual(occ, [
            Occurrence("a", "off", datetime(2026, 11, 6, 15, 0, tzinfo=UTC)),
            Occurrence("a", "off", datetime(2026, 11, 7, 15, 0, tzinfo=UTC)),
        ])

    def test_window_is_open_closed(self):
        e = Event("a", "17:00", "off", None, True)
        at = datetime(2026, 11, 6, 15, 0, tzinfo=UTC)
        self.assertEqual(occurrences_between([e], at, at, TZ), [])
        self.assertEqual(len(occurrences_between(
            [e], at - timedelta(seconds=1), at, TZ)), 1)

    def test_weekday_filter_uses_local_date(self):
        # sambata 00:30 Bucuresti = vineri 22:30Z
        e = Event("w", "00:30", "on", (6,), True)
        occ = occurrences_between(
            [e], datetime(2026, 11, 5, 0, 0, tzinfo=UTC),
            datetime(2026, 11, 9, 0, 0, tzinfo=UTC), TZ)
        self.assertEqual(occ, [
            Occurrence("w", "on", datetime(2026, 11, 6, 22, 30, tzinfo=UTC))])

    def test_disabled_event_has_no_occurrences(self):
        e = Event("a", "17:00", "off", None, False)
        self.assertEqual(occurrences_between(
            [e], loc(2026, 11, 5), loc(2026, 11, 9), TZ), [])
        self.assertIsNone(next_occurrence([e], loc(2026, 11, 5), TZ))

    def test_sorted_by_time_then_id(self):
        a = Event("b", "10:00", "on", None, True)
        b = Event("a", "10:00", "off", None, True)
        c = Event("c", "09:00", "on", None, True)
        occ = occurrences_between([a, b, c], loc(2026, 11, 5, 0), loc(2026, 11, 5, 23, 59), TZ)
        self.assertEqual([o.event_id for o in occ], ["c", "a", "b"])

    def test_spring_gap_lands_on_0400(self):
        e = Event("a", "03:30", "on", None, True)
        occ = occurrences_between(
            [e], loc(2026, 3, 28, 12), loc(2026, 3, 29, 12), TZ)
        self.assertEqual([o.at for o in occ],
                         [datetime(2026, 3, 29, 1, 0, tzinfo=UTC)])

    def test_autumn_ambiguous_once(self):
        e = Event("a", "03:30", "on", None, True)
        occ = occurrences_between(
            [e], loc(2026, 10, 24, 12), loc(2026, 10, 25, 12), TZ)
        self.assertEqual([o.at for o in occ],
                         [datetime(2026, 10, 25, 0, 30, tzinfo=UTC)])

    def test_next_occurrence_skips_days_and_filters_action(self):
        off = Event("off", "17:00", "off", (1, 2, 3, 4, 5), True)
        on = Event("on", "11:00", "on", None, True)
        now = loc(2026, 11, 6, 18, 0)  # vineri
        nxt_off = next_occurrence([off, on], now, TZ, action="off")
        self.assertEqual(nxt_off.at, datetime(2026, 11, 9, 15, 0, tzinfo=UTC))
        self.assertEqual(nxt_off.event_id, "off")
        nxt = next_occurrence([off, on], now, TZ)
        self.assertEqual(nxt.at, datetime(2026, 11, 7, 9, 0, tzinfo=UTC))
        self.assertEqual(nxt.action, "on")

    def test_next_occurrence_is_strictly_after_now(self):
        e = Event("a", "17:00", "off", None, True)
        at = datetime(2026, 11, 6, 15, 0, tzinfo=UTC)
        self.assertEqual(next_occurrence([e], at, TZ).at,
                         datetime(2026, 11, 7, 15, 0, tzinfo=UTC))

    def test_next_occurrence_none_when_no_match(self):
        e = Event("a", "17:00", "on", None, True)
        self.assertIsNone(next_occurrence([e], loc(2026, 11, 5), TZ, action="off"))
        self.assertIsNone(next_occurrence([], loc(2026, 11, 5), TZ))

    def test_next_occurrence_weekly_event_within_horizon(self):
        # singura aparitie e peste 7 zile (acelasi weekday, ora trecuta azi)
        e = Event("a", "08:00", "on", (5,), True)  # vineri
        now = loc(2026, 11, 6, 9, 0)  # vineri, dupa 08:00
        self.assertEqual(next_occurrence([e], now, TZ).at,
                         datetime(2026, 11, 13, 6, 0, tzinfo=UTC))


if __name__ == "__main__":
    unittest.main()
