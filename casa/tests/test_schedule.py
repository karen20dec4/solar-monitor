"""Teste pentru logica programarilor (pura, fara I/O)."""
import unittest
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from casa.schedule import (Event, OffDeadline, Occurrence, events_conflict,
                           nearest_off, next_occurrence, normalize_days,
                           occurrences_between, parse_hhmm)

TZ = ZoneInfo("Europe/Bucharest")
UTC = timezone.utc


def loc(y, mo, d, h=0, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=TZ)


class ScheduleTest(unittest.TestCase):
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

    def test_bad_hhmm_raises(self):
        for bad in ("", "9", "24:00", "10:60", "ab:cd", "10:5", "10-30", "1:30", None, 1030):
            with self.assertRaises(ValueError, msg=bad):
                parse_hhmm(bad)
        self.assertEqual(parse_hhmm("07:05"), (7, 5))


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
