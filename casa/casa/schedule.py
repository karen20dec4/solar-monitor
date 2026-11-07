"""Logica programarilor: evenimente (ora + zile + actiune), aparitiile lor, DST.

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


def _as_utc(dt: datetime) -> datetime:
    return dt.astimezone(UTC)


_PRIORITY = {"countdown": 0, "schedule": 1, "inching": 2}


def nearest_off(candidates: list[OffDeadline]) -> Optional[OffDeadline]:
    """Cel mai apropiat termen de oprire; la egalitate: countdown > schedule > inching."""
    if not candidates:
        return None
    return min(candidates, key=lambda c: (_as_utc(c.at), _PRIORITY[c.reason]))


# --- Evenimente: fiecare programare e un moment (ora + zile) cu o actiune ---

_ACTIONS = ("on", "off")
_ALL_DAYS = 7


def normalize_days(days) -> Optional[tuple[int, ...]]:
    """None sau lista de zile ISO (1=luni..7=duminica) -> None (zilnic) sau
    tuplu sortat, fara duplicate. ValueError la gol, tip gresit sau in afara 1..7.
    Cele 7 zile inseamna zilnic, deci tot None."""
    if days is None:
        return None
    try:
        items = list(days)
    except TypeError:
        raise ValueError(f"zile invalide: {days!r}") from None
    if not items:
        raise ValueError("lista de zile nu poate fi goala")
    for d in items:
        # bool e subclasa de int: True ar trece drept ziua 1
        if isinstance(d, bool) or not isinstance(d, int) or not 1 <= d <= _ALL_DAYS:
            raise ValueError(f"zi invalida (asteptat 1..7): {d!r}")
    uniq = tuple(sorted(set(items)))
    return None if len(uniq) == _ALL_DAYS else uniq


@dataclass(frozen=True)
class Event:
    id: str
    time: str  # HH:MM, ora locala
    action: Literal["on", "off"]
    days: Optional[tuple[int, ...]]  # None = zilnic; altfel sortat, unic, 1..7
    enabled: bool

    def __post_init__(self):
        parse_hhmm(self.time)
        if self.action not in _ACTIONS:
            raise ValueError(f"actiune invalida (on/off): {self.action!r}")
        if self.days != normalize_days(self.days):
            raise ValueError(f"zile nenormalizate: {self.days!r}")

    @classmethod
    def from_dict(cls, d: dict) -> "Event":
        return cls(d["id"], d["time"], d["action"], normalize_days(d.get("days")),
                   bool(d["enabled"]))

    def to_dict(self) -> dict:
        return {"id": self.id, "time": self.time, "action": self.action,
                "days": None if self.days is None else list(self.days),
                "enabled": self.enabled}


@dataclass(frozen=True)
class Occurrence:
    event_id: str
    action: str
    at: datetime  # aware, UTC


def occurrences_between(events: list[Event], t0: datetime, t1: datetime,
                        tz: ZoneInfo) -> list[Occurrence]:
    """Aparitiile evenimentelor active cu t0 < at <= t1, sortate dupa (at, id).
    Se enumera pe zile calendaristice locale (nu +24 h in UTC); ziua saptamanii
    e cea a datei locale."""
    t0u, t1u = _as_utc(t0), _as_utc(t1)
    first = t0u.astimezone(tz).date() - timedelta(days=1)
    last = t1u.astimezone(tz).date() + timedelta(days=1)
    seen: set[tuple[str, datetime]] = set()
    out: list[Occurrence] = []
    d = first
    while d <= last:
        weekday = d.isoweekday()
        for ev in events:
            if not ev.enabled or (ev.days is not None and weekday not in ev.days):
                continue
            at = _local_to_utc(d, ev.time, tz)
            key = (ev.id, at)
            if t0u < at <= t1u and key not in seen:
                seen.add(key)
                out.append(Occurrence(ev.id, ev.action, at))
        d += timedelta(days=1)
    out.sort(key=lambda o: (o.at, o.event_id))
    return out


_NEXT_HORIZON = timedelta(days=8)  # un eveniment saptamanal reapare in cel mult 7 zile


def next_occurrence(events: list[Event], now: datetime, tz: ZoneInfo,
                    action: Optional[str] = None) -> Optional[Occurrence]:
    """Prima aparitie activa cu at > now (doar actiunea data, daca e data);
    cauta 8 zile inainte, None daca nu gaseste."""
    nowu = _as_utc(now)
    for o in occurrences_between(events, nowu, nowu + _NEXT_HORIZON, tz):
        if action is None or o.action == action:
            return o
    return None


def events_conflict(a: Event, b: Event) -> bool:
    """Doua evenimente active, diferite, la aceeasi ora si cu zile comune
    (None = toate zilele) s-ar bate cap in cap."""
    if a.id == b.id or not (a.enabled and b.enabled) or a.time != b.time:
        return False
    if a.days is None or b.days is None:
        return True
    return bool(set(a.days) & set(b.days))
