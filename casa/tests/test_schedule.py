"""Teste pentru logica programarilor (pura, fara I/O)."""
import unittest
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from casa.schedule import (Edge, Interval, OffDeadline, active_interval,
                           edges_between, interval_end, missed_edges,
                           nearest_off, parse_hhmm)

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
        self.assertEqual(starts[0].at.utcoffset(), timedelta(0))

    def test_fall_back_edge_fires_once(self):
        iv = Interval("a", "03:30", "07:00", True)
        edges = edges_between([iv], loc(2026, 10, 24, 12), loc(2026, 10, 25, 12), TZ)
        starts = [e for e in edges if e.kind == "start"]
        self.assertEqual(len(starts), 1)
        # prima aparitie (fold=0): 03:30 EEST = 00:30Z
        self.assertEqual(starts[0].at, datetime(2026, 10, 25, 0, 30, tzinfo=UTC))

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


if __name__ == "__main__":
    unittest.main()
