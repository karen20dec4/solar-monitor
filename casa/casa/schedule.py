"""Logica programarilor: fronturi zilnice, DST, intervale peste miezul noptii.

Modul pur, fara I/O. Orele locale sunt in fusul dat (Europe/Bucharest);
toate instantele returnate sunt aware, in UTC.
"""
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Literal, Optional
from zoneinfo import ZoneInfo

UTC = timezone.utc
_HHMM = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")


def parse_hhmm(s: str) -> tuple[int, int]:
    """'HH:MM' (24 h) -> (ora, minut); ValueError la format gresit."""
    if not isinstance(s, str):
        raise ValueError(f"ora invalida: {s!r}")
    m = _HHMM.match(s)
    if not m:
        raise ValueError(f"ora invalida (asteptat HH:MM): {s!r}")
    return int(m.group(1)), int(m.group(2))


@dataclass(frozen=True)
class Interval:
    id: str
    start: str
    stop: str
    enabled: bool

    def __post_init__(self):
        if parse_hhmm(self.start) == parse_hhmm(self.stop):
            raise ValueError("start si stop nu pot fi egale")


@dataclass(frozen=True)
class Edge:
    interval_id: str
    kind: Literal["start", "stop"]
    at: datetime  # aware, UTC


@dataclass
class OffDeadline:
    at: datetime
    reason: Literal["schedule", "inching", "countdown"]


def _local_to_utc(d: date, hhmm: str, tz: ZoneInfo) -> datetime:
    """Ora locala -> UTC. Ora ambigua (trecerea la ora de iarna): prima
    aparitie (fold=0). Ora inexistenta (trecerea la ora de vara): primul
    instant valid de dupa gol, adica 04:00 local (01:00Z)."""
    h, m = parse_hhmm(hhmm)
    dt = datetime(d.year, d.month, d.day, h, m, tzinfo=tz, fold=0)
    u = dt.astimezone(UTC)
    if u.astimezone(tz).replace(tzinfo=None) == dt.replace(tzinfo=None):
        return u
    # ora inexistenta: tranzitia e intre fold=1 (offset vechi) si fold=0 (offset nou)
    lo = dt.replace(fold=1).astimezone(UTC)
    hi = u
    old = lo.astimezone(tz).utcoffset()
    while (hi - lo) > timedelta(seconds=1):
        mid = lo + (hi - lo) / 2
        if mid.astimezone(tz).utcoffset() == old:
            lo = mid
        else:
            hi = mid
    return hi.replace(microsecond=0)


def _window(iv: "Interval", d: date, tz: ZoneInfo) -> Optional[tuple[datetime, datetime]]:
    """Fereastra (start, stop) a intervalului care incepe in ziua locala d,
    sau None daca start si stop se prabusesc pe acelasi instant (ambele in gol DST)."""
    over_midnight = parse_hhmm(iv.stop) < parse_hhmm(iv.start)
    s = _local_to_utc(d, iv.start, tz)
    e = _local_to_utc(d + timedelta(days=1) if over_midnight else d, iv.stop, tz)
    return (s, e) if s < e else None


def _as_utc(dt: datetime) -> datetime:
    return dt.astimezone(UTC)


def edges_between(intervals: list[Interval], t0: datetime, t1: datetime,
                  tz: ZoneInfo) -> list[Edge]:
    """Fronturile intervalelor active cu t0 < at <= t1, sortate.
    Se enumera pe zile calendaristice locale (nu +24 h in UTC)."""
    t0u, t1u = _as_utc(t0), _as_utc(t1)
    first = t0u.astimezone(tz).date() - timedelta(days=1)
    last = t1u.astimezone(tz).date() + timedelta(days=1)
    seen: set[tuple[str, str, datetime]] = set()
    out: list[Edge] = []
    d = first
    while d <= last:
        for iv in intervals:
            if not iv.enabled:
                continue
            w = _window(iv, d, tz)
            if w is None:
                continue
            for kind, at in (("start", w[0]), ("stop", w[1])):
                key = (iv.id, kind, at)
                if t0u < at <= t1u and key not in seen:
                    seen.add(key)
                    out.append(Edge(iv.id, kind, at))
        d += timedelta(days=1)
    out.sort(key=lambda e: (e.at, e.interval_id, e.kind))
    return out


def _window_containing(iv: Interval, now: datetime,
                       tz: ZoneInfo) -> Optional[tuple[datetime, datetime]]:
    """Fereastra [start, stop) a intervalului care contine `now`, sau None."""
    nowu = _as_utc(now)
    today = nowu.astimezone(tz).date()
    for d in (today - timedelta(days=1), today):
        w = _window(iv, d, tz)
        if w and w[0] <= nowu < w[1]:
            return w
    return None


def active_interval(intervals: list[Interval], now: datetime,
                    tz: ZoneInfo) -> Optional[Interval]:
    """Un interval activ a carui fereastra contine `now` (inclusiv peste miezul noptii)."""
    for iv in intervals:
        if iv.enabled and _window_containing(iv, now, tz):
            return iv
    return None


def interval_end(interval: Interval, now: datetime, tz: ZoneInfo) -> datetime:
    """Instantul stop al ferestrei care contine `now` (ValueError daca nu e in fereastra)."""
    w = _window_containing(interval, now, tz)
    if w is None:
        raise ValueError("momentul nu e in fereastra intervalului")
    return w[1]


def _parse_done(s) -> Optional[datetime]:
    try:
        dt = datetime.fromisoformat(s)
    except (TypeError, ValueError):
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def missed_edges(intervals: list[Interval], last_done: dict[str, dict[str, str]],
                 now: datetime, tz: ZoneInfo,
                 lookback: timedelta = timedelta(days=1)) -> list[Edge]:
    """Fronturi din (now - lookback, now] mai noi decat last_done[id][kind].
    Cheie lipsa = niciodata executat. Regulile (stop mereu, start doar in
    fereastra) le aplica apelantul."""
    nowu = _as_utc(now)
    out = []
    for e in edges_between(intervals, nowu - lookback, nowu, tz):
        done = _parse_done(last_done.get(e.interval_id, {}).get(e.kind))
        if done is None or e.at > done:
            out.append(e)
    return out


_PRIORITY = {"countdown": 0, "schedule": 1, "inching": 2}


def nearest_off(candidates: list[OffDeadline]) -> Optional[OffDeadline]:
    """Cel mai apropiat termen de oprire; la egalitate: countdown > schedule > inching."""
    if not candidates:
        return None
    return min(candidates, key=lambda c: (_as_utc(c.at), _PRIORITY[c.reason]))
