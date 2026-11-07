"""Programatorul prizelor: comenzi, evenimente programate, countdown, inching, evenimente ratate.

Starea care trebuie sa supravietuiasca unei reporniri (evenimente, countdown, inching,
ultimul eveniment consumat) sta in state.json (JsonStore). Starea de moment a prizei
(citirea, erori, ce s-a scris in DP-ul de countdown) sta doar in memorie.

Un eveniment (ora + zile + PORNESTE/OPRESTE) actioneaza doar la ora lui; intre evenimente priza
asculta de buton si de aplicatie. Un eveniment ratat se judeca dupa cel mai recent eveniment scadent.

Reguli de siguranta:
  * oprirea conteaza mai mult decat pornirea: un OPRESTE ratat se executa oricat de tarziu (in
    lookback) si se reincearca la 30 s fara limita cat timp e cel mai recent eveniment; un PORNESTE
    ratat doar cat timp e inca cel mai recent eveniment scadent;
  * orice comanda catre priza trece prin worker-ul ei (o singura conexiune);
  * termenul de oprire cel mai apropiat se scrie si in countdown-ul intern al prizei.
"""
import copy
import logging
import secrets
import threading
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional
from zoneinfo import ZoneInfo

from .devices import DeviceInfo
from .plugs import PlugReading, encode_inching
from .schedule import (Event, OffDeadline, events_conflict, nearest_off, next_occurrence,
                       normalize_days, occurrences_between, parse_hhmm)
from .store import CorruptStore, JsonStore

log = logging.getLogger("casa.controller")
UTC = timezone.utc

TICK_S = 5
RETRY_S = 30
CMD_TIMEOUT_S = 10
MAX_EVENTS = 12
COUNTDOWN_MAX_MIN = 1440
INCHING_MAX_MIN = 720
DP_MAX_S = 86400
DP_TOLERANCE_S = 5
GRACE_S = 10                      # cat lasam DP-ul prizei sa opreasca singur inainte sa o oprim noi
RESYNC_TOL_S = 60                 # cat de mult poate diferi DP-ul citit de cel scris
RESYNC_EVERY = timedelta(seconds=60)
# Un countdown "porneste" care nu a putut fi executat se abandoneaza dupa 30 min de intarziere
# (o pornire tarzie dupa o pana lunga ar fi o surpriza); se afiseaza o eroare.
ON_COUNTDOWN_GIVE_UP = timedelta(minutes=30)
LOOKBACK = timedelta(days=2)      # cat de veche poate fi o aparitie ratata si totusi executata

_DEFAULT = {"plugs": {}}


class CommandError(Exception):
    """Comanda catre priza a esuat (mesajul se arata in aplicatie)."""


def _blank() -> dict:
    return {"events": [], "countdown": None,
            "inching": {"enabled": False, "minutes": 30},
            "inching_off_at": None, "last_manual_at": None,
            # event_cutoff: id eveniment -> momentul crearii/editarii/activarii; aparitiile lui
            # mai vechi (at <= cutoff) nu se executa niciodata (nimic retroactiv).
            # last_event_at: aparitiile cu at <= last_event_at sunt consumate (executate sau sarite).
            "event_cutoff": {}, "last_event_at": None}


def _fill(p: dict) -> dict:
    for k, v in _blank().items():
        if k not in p:
            p[k] = copy.deepcopy(v)
    return p


def _epoch(dt: datetime) -> int:
    return int(dt.timestamp())


def _from_epoch(ts) -> datetime:
    return datetime.fromtimestamp(ts, UTC)


def _parse_iso(s) -> Optional[datetime]:
    try:
        dt = datetime.fromisoformat(s)
    except (TypeError, ValueError):
        return None
    return (dt if dt.tzinfo else dt.replace(tzinfo=UTC)).astimezone(UTC)


def _int_in(value, lo: int, hi: int, what: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
        raise ValueError(f"{what}: asteptat intreg intre {lo} si {hi}, primit {value!r}")
    return value


# Cheile starii vechi (intervale): dispar la migrare. `schedules` se converteste, restul se arunca.
_LEGACY_KEYS = ("schedules", "last_done", "started_by", "release_off_at", "last_start_at", "cutoff")


def migrate_state(data: dict, now: datetime) -> bool:
    """Intervalele vechi -> evenimente, pe loc. True daca s-a schimbat ceva.

    Fiecare interval {id, start, stop, enabled} devine PORNESTE (`<id>s`, ora start) si OPRESTE
    (`<id>e`, ora stop), zilnice, cu acelasi `enabled`. `last_event_at = now`: nimic din ce era
    scadent pana acum nu se executa retroactiv la prima pornire. Starea de executie veche (last_done,
    started_by, release_off_at, last_start_at, cutoff) se arunca. Se ating doar prizele cu chei vechi;
    restul fisierului (ex. marcajul `demo`) ramane neschimbat."""
    plugs = data.get("plugs") if isinstance(data, dict) else None
    if not isinstance(plugs, dict):
        return False
    changed = False
    for pid, p in plugs.items():
        if not isinstance(p, dict) or not any(k in p for k in _LEGACY_KEYS):
            continue
        events = p.get("events")
        if not isinstance(events, list):
            events = []
        taken = {e.get("id") for e in events if isinstance(e, dict)}
        for s in p.get("schedules") or []:
            try:
                iid, start, stop, enabled = s["id"], s["start"], s["stop"], bool(s["enabled"])
                if not isinstance(iid, str):
                    raise TypeError("id de interval ne-text")
                parse_hhmm(start)
                parse_hhmm(stop)
            except (KeyError, TypeError, ValueError):
                log.warning("interval invalid ignorat la migrare (priza %s): %r", pid, s)
                continue
            for suffix, hhmm, action in (("s", start, "on"), ("e", stop, "off")):
                if iid + suffix not in taken:
                    events.append({"id": iid + suffix, "time": hhmm, "action": action,
                                   "days": None, "enabled": enabled})
                    taken.add(iid + suffix)
        p["events"] = events
        if not isinstance(p.get("event_cutoff"), dict):
            p["event_cutoff"] = {}
        p["last_event_at"] = now.astimezone(UTC).isoformat()
        for k in _LEGACY_KEYS:
            p.pop(k, None)
        changed = True
    return changed


@dataclass
class _Rt:
    """Starea de moment a unei prize (nu se salveaza)."""
    reading: Optional[PlugReading] = None
    online: Optional[bool] = None
    on: Optional[bool] = None            # ultima stare cunoscuta (ramane cat e offline)
    has_power: bool = False
    cmd_error: Optional[str] = None      # ramane pana reuseste o comanda
    poll_error: Optional[str] = None     # cat timp e offline
    needs_missed: bool = True            # la pornire si la fiecare reconectare
    retry_at: Optional[datetime] = None  # reincercare eveniment esuat
    cd_written: Optional[int] = None     # termenul (epoch) scris in DP-ul de countdown
    cd_capped: bool = False
    cd_dirty: bool = False               # priza oprita cu un DP scris: de anulat (0)
    next_dp_try: Optional[datetime] = None
    next_resync: Optional[datetime] = None
    off_sent_at: Optional[datetime] = None
    offline_since: Optional[datetime] = None
    # UN singur ritm de reincercare pe priza, respectat de TOATE caile automate
    # (evenimente, countdown, inching): cel mult o incercare la 30 s cat timp esueaza.
    next_cmd_try: Optional[datetime] = None


class Controller:
    def __init__(self, devices: list, workers: dict, store: JsonStore, tz: ZoneInfo,
                 clock: Callable[[], datetime]):
        self.devices: dict = {d.id: d for d in devices}
        self.workers = workers
        self.store = store
        self.tz = tz
        self.clock = clock
        self._rt = {pid: _Rt() for pid in self.devices}
        self._rt_lock = threading.Lock()   # starea de moment (scurt, fara I/O de retea)
        # un lock pe priza: o priza blocata nu tine pe loc comenzile catre celelalte
        self._plock = {pid: threading.RLock() for pid in self.devices}
        self._last_tick: Optional[datetime] = None
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._migrate()

    def _migrate(self) -> None:
        """O singura data, la pornire: intervalele din formatul vechi devin evenimente. Nu scrie
        nimic cand nu e nimic de convertit. Un fisier stricat sau ilizibil nu opreste serviciul
        (senzorii merg mai departe); ramane de rezolvat de mana, ca si pana acum."""
        now = self.clock()
        try:
            if not migrate_state(self.store.load(_DEFAULT), now):
                return
            self.store.update(lambda d: migrate_state(d, now), _DEFAULT)
        except (CorruptStore, OSError):
            log.exception("migrarea starii (intervale -> evenimente) a esuat")
        else:
            log.warning("intervalele prizelor au fost convertite in evenimente")

    # ------------------------------------------------------------------ ciclu de viata
    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="casa-controller", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        t = self._thread
        if t:
            t.join(30)

    def _loop(self) -> None:
        while True:
            try:
                self.tick()
            except Exception:  # noqa: BLE001
                log.exception("tick esuat")
            if self._stop.wait(TICK_S):
                break

    # ------------------------------------------------------------------ stare persistenta
    def _dev(self, pid: str) -> DeviceInfo:
        try:
            return self.devices[pid]
        except KeyError:
            raise KeyError(f"priza necunoscuta: {pid}") from None

    def _get(self, pid: str) -> dict:
        data = self.store.load(_DEFAULT)
        return _fill(data.get("plugs", {}).get(pid) or {})

    def _mut(self, pid: str, fn: Callable[[dict], None]) -> dict:
        def apply(d: dict) -> None:
            fn(_fill(d.setdefault("plugs", {}).setdefault(pid, {})))
        return self.store.update(apply, _DEFAULT)["plugs"][pid]

    @staticmethod
    def _events(st: dict) -> list:
        """Evenimentele din stare ca obiecte `Event`; un eveniment stricat se ignora (cu avertisment)."""
        out = []
        for e in st["events"]:
            try:
                out.append(Event.from_dict(e))
            except (AttributeError, KeyError, TypeError, ValueError):
                log.warning("eveniment invalid ignorat: %r", e)
        return out

    def _set(self, pid: str, **kw) -> None:
        """Scrie campuri din starea de moment, sub lock."""
        with self._rt_lock:
            rt = self._rt[pid]
            for k, v in kw.items():
                setattr(rt, k, v)

    # ------------------------------------------------------------------ comenzi catre priza
    def _send(self, pid: str, dp: int, value) -> None:
        """Trimite o comanda prin worker si asteapta rezultatul; CommandError la esec."""
        rt = self._rt[pid]
        worker = self.workers.get(pid)
        msg = None
        if worker is None:
            msg = "fara conexiune la priza"
        else:
            fut = None
            try:
                fut = worker.submit(dp, value)
                fut.result(timeout=CMD_TIMEOUT_S)
            except TimeoutError:
                if fut is not None:
                    fut.cancel()
                msg = "timeout: priza nu raspunde"
            except Exception as e:  # noqa: BLE001
                msg = str(e) or e.__class__.__name__
        with self._rt_lock:
            rt.cmd_error = msg
        if msg is not None:
            log.warning("comanda catre %s esuata: %s", self.devices[pid].name, msg)
            raise CommandError(msg)

    def _send_switch(self, pid: str, on: bool) -> None:
        self._send(pid, self.devices[pid].dps.get("switch", 1), bool(on))
        # Workerul rezolva future-ul INAINTE sa citeasca starea: nu asteptam citirea, ci
        # consemnam acum noua stare (si tranzitia), ca nimic din acelasi tick sa nu mai
        # lucreze cu starea veche. Citirea de dupa nu mai e o a doua tranzitie.
        self._apply_known(pid, bool(on), self.clock())

    def _apply_known(self, pid: str, on: bool, now: datetime) -> None:
        with self._rt_lock:
            rt = self._rt[pid]
            prev = rt.on
            rt.on = on
            rt.next_cmd_try = None
            rt.off_sent_at = None if on else now
        if on and prev is not True:
            self._became_on(pid, now)
        elif not on and prev is not False:
            self._became_off(pid)

    def _auto_switch(self, pid: str, now: datetime, on: bool, force: bool = False) -> bool:
        """Comanda automata, cu ritm unic de reincercare. False = nu s-a trimis / a esuat."""
        rt = self._rt[pid]
        if not force and self._throttled(rt, now):
            return False
        try:
            self._send_switch(pid, on)
        except CommandError:
            self._set(pid, next_cmd_try=now + timedelta(seconds=RETRY_S))
            return False
        return True

    # ------------------------------------------------------------------ citiri (thread-ul workerului)
    def on_reading(self, pid: str, r: PlugReading) -> None:
        if pid not in self.devices:
            return
        now = self.clock()
        trans = None
        with self._rt_lock:
            rt = self._rt[pid]
            prev_online, prev_on = rt.online, rt.on
            rt.reading = r
            rt.online = bool(r.online)
            if r.online:
                rt.poll_error = None
                if r.power is not None:
                    rt.has_power = True
                if prev_online is not True:
                    # prima citire / reconectare: poate a repornit priza si si-a pierdut countdown-ul
                    rt.needs_missed = True
                    rt.cd_written = None
                    # O comanda esuata produce ea insasi offline -> online in ~2 s: ritmul de
                    # reincercare se reseteaza doar daca priza a fost cazuta cel putin cat ritmul.
                    if (rt.offline_since is None
                            or now - rt.offline_since >= timedelta(seconds=RETRY_S)):
                        rt.next_cmd_try = rt.next_dp_try = None
                elif (rt.cd_written is not None and r.countdown_s is not None
                      and abs(r.countdown_s - (rt.cd_written - _epoch(now))) > RESYNC_TOL_S
                      and (rt.next_resync is None or now >= rt.next_resync)):
                    rt.cd_written = None      # sters din alta parte (SmartLife): il rescriem
                    rt.next_resync = now + RESYNC_EVERY
                if r.on is not None:
                    rt.on = r.on
                    if r.on and prev_on is not True:
                        trans = "on" if prev_on is False else "first_on"
                    elif prev_on is True and not r.on:
                        trans = "off"
                rt.offline_since = None
            else:
                rt.poll_error = r.error or "offline"
                if rt.offline_since is None:
                    rt.offline_since = now
        if r.online and r.inching is not None and "inching" in self.devices[pid].dps:
            self._sync_native_inching(pid, r.inching)
        if trans == "on":
            self._became_on(pid, now)
        elif trans == "first_on":
            self._became_on(pid, now, only_if_unset=True)
        elif trans == "off":
            self._became_off(pid)

    def _sync_native_inching(self, pid: str, native: tuple) -> None:
        """Setarea de inching din priza (setata si din SmartLife) e sursa de adevar pentru `st["inching"]`."""
        enabled, seconds = native
        want = {"enabled": bool(enabled), "minutes": min(INCHING_MAX_MIN, max(1, round(seconds / 60)))}
        if self._get(pid)["inching"] == want:
            return
        def f(p):
            p["inching"] = want
            if not want["enabled"]:
                p["inching_off_at"] = None
        self._mut(pid, f)

    def _became_on(self, pid: str, now: datetime, only_if_unset: bool = False) -> None:
        """oprit -> pornit, din orice sursa: porneste inching-ul (din acest moment).
        `only_if_unset`: prima citire dupa repornire, cu priza deja pornita."""
        self._set(pid, cd_written=None)
        def f(p):
            inch = p["inching"]
            if only_if_unset and (p["inching_off_at"] is not None or not inch["enabled"]):
                return
            p["inching_off_at"] = (_epoch(now) + inch["minutes"] * 60) if inch["enabled"] else None
        self._mut(pid, f)

    def _became_off(self, pid: str) -> None:
        with self._rt_lock:
            rt = self._rt[pid]
            if rt.cd_written is not None:
                rt.cd_dirty = True        # un DP armat pe o priza oprita trebuie anulat (0)
            rt.cd_written = None
        def f(p):
            p["inching_off_at"] = None
            if p["countdown"] and p["countdown"].get("action") == "off":
                p["countdown"] = None
        self._mut(pid, f)

    # ------------------------------------------------------------------ vederi
    def _candidates(self, st: dict, now: datetime) -> list:
        """Termenele la care serverul va opri priza (cel mai apropiat castiga): urmatorul eveniment
        OPRESTE activ, inching, countdown "opreste"."""
        c = []
        o = next_occurrence(self._events(st), now, self.tz, action="off")
        if o is not None:
            c.append(OffDeadline(o.at, "schedule"))
        if st["inching_off_at"] is not None:
            c.append(OffDeadline(_from_epoch(st["inching_off_at"]), "inching"))
        cd = st["countdown"]
        if cd and cd.get("action") == "off":
            c.append(OffDeadline(_from_epoch(cd["at"]), "countdown"))
        return c

    def plug_view(self, pid: str) -> dict:
        dev = self._dev(pid)
        now = self.clock()
        st = self._get(pid)
        with self._rt_lock:
            rt = self._rt[pid]
            r, online, on = rt.reading, bool(rt.online), bool(rt.on)
            metered = "power" in dev.dps and rt.has_power
            error = rt.cmd_error or rt.poll_error
        live = metered and online and r is not None
        cd = st["countdown"]
        nd = nearest_off(self._candidates(st, now)) if on else None
        events = self._events(st)
        nxt = next_occurrence(events, now, self.tz)
        return {
            "id": pid,
            "name": dev.name,
            "online": online,
            "on": on,
            "metered": metered,
            "power": r.power if live else None,
            "voltage": r.voltage if live else None,
            "current": r.current if live else None,
            # cheia veche, goala: aplicatia <= 3.43 afiseaza o sectiune de programari goala
            "schedules": [],
            "events": [e.to_dict() for e in sorted(events, key=lambda e: (e.time, e.days or ()))],
            "next_event": None if nxt is None else {
                "id": nxt.event_id, "time": next(e.time for e in events if e.id == nxt.event_id),
                "action": nxt.action, "at": _epoch(nxt.at)},
            "countdown": None if not cd else {
                "action": cd["action"], "at": int(cd["at"]),
                "seconds_left": max(0, int(cd["at"] - now.timestamp()))},
            "inching": {"enabled": bool(st["inching"]["enabled"]),
                        "minutes": int(st["inching"]["minutes"])},
            "off_deadline": None if not nd else {
                "at": _epoch(nd.at), "seconds_left": max(0, int((nd.at - now).total_seconds())),
                "reason": nd.reason},
            "error": error,
        }

    def plugs_view(self) -> list:
        return [self.plug_view(pid) for pid in self.devices]

    # ------------------------------------------------------------------ comenzi publice
    def switch(self, pid: str, on: bool) -> dict:
        # O comanda manuala e decizia utilizatorului: anuleaza countdown-ul si bate orice eveniment
        # mai vechi (last_manual_at); evenimentele ramase nu se ating.
        self._dev(pid)
        with self._plock[pid]:
            now = self.clock()
            self._send_switch(pid, on)          # CommandError: nimic nu se schimba
            def f(p):
                p["countdown"] = None
                p["last_manual_at"] = now.isoformat()
                if not on:
                    p["inching_off_at"] = None
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def set_countdown(self, pid: str, action: str, minutes: int) -> dict:
        self._dev(pid)
        if action not in ("on", "off"):
            raise ValueError(f"actiune necunoscuta: {action!r}")
        _int_in(minutes, 1, COUNTDOWN_MAX_MIN, "minute countdown")
        with self._plock[pid]:
            at = _epoch(self.clock()) + minutes * 60
            self._mut(pid, lambda p: p.__setitem__("countdown", {"action": action, "at": at}))
            self._after(pid)
        return self.plug_view(pid)

    def cancel_countdown(self, pid: str) -> dict:
        self._dev(pid)
        with self._plock[pid]:
            self._mut(pid, lambda p: p.__setitem__("countdown", None))
            self._after(pid)
        return self.plug_view(pid)

    def rename(self, pid: str, name: str) -> dict:
        """Numele dat din aplicatie (salvat de web in devices.local.json)."""
        self._dev(pid).name = name
        return self.plug_view(pid)

    def set_inching(self, pid: str, enabled: bool, minutes: int) -> dict:
        self._dev(pid)
        _int_in(minutes, 1, INCHING_MAX_MIN, "minute inching")
        enabled = bool(enabled)
        with self._plock[pid]:
            native_dp = self.devices[pid].dps.get("inching")
            if native_dp is not None:
                # inching-ul din firmware: il scriem in priza (CommandError: nimic nu se schimba)
                self._send(pid, native_dp, encode_inching(enabled, minutes * 60))
            now = self.clock()
            is_on = self._rt[pid].on is True
            def f(p):
                p["inching"] = {"enabled": enabled, "minutes": minutes}
                # Priza deja pornita: ceasul inching-ului PORNESTE DIN NOU din momentul
                # modificarii (nu din momentul pornirii), ca sa nu se opreasca pe loc.
                if is_on:
                    p["inching_off_at"] = _epoch(now) + minutes * 60 if enabled else None
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    @staticmethod
    def _check_conflict(others: list, ev: Event) -> None:
        for o in others:
            if events_conflict(ev, o):
                raise ValueError(f"conflict: exista deja un eveniment activ la {ev.time} in aceleasi zile")

    @staticmethod
    def _find_event(st: dict, event_id: str) -> Optional[dict]:
        return next((e for e in st["events"] if isinstance(e, dict) and e.get("id") == event_id), None)

    def add_event(self, pid: str, time: str, action: str, days, enabled: bool = True) -> dict:
        self._dev(pid)
        with self._plock[pid]:
            now = self.clock()
            st = self._get(pid)
            taken = {e.get("id") for e in st["events"] if isinstance(e, dict)}
            eid = secrets.token_hex(2)
            while eid in taken:
                eid = secrets.token_hex(2)
            ev = Event(eid, time, action, normalize_days(days), bool(enabled))  # valideaza
            if len(st["events"]) >= MAX_EVENTS:
                raise ValueError(f"cel mult {MAX_EVENTS} evenimente pe priza")
            self._check_conflict(self._events(st), ev)

            def f(p):
                p["events"].append(ev.to_dict())
                p["event_cutoff"][ev.id] = now.isoformat()   # aparitiile trecute ale unui eveniment nou nu se executa
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def update_event(self, pid: str, event_id: str, **changes) -> dict:
        self._dev(pid)
        unknown = set(changes) - {"time", "action", "days", "enabled"}
        if unknown:
            raise ValueError(f"campuri necunoscute: {sorted(unknown)}")
        with self._plock[pid]:
            now = self.clock()
            st = self._get(pid)
            cur = self._find_event(st, event_id)
            if cur is None:
                raise KeyError(f"eveniment necunoscut: {event_id}")
            new = dict(cur)
            new.update(changes)
            ev = Event(event_id, new.get("time"), new.get("action"), normalize_days(new.get("days")),
                       bool(new.get("enabled")))                    # valideaza
            self._check_conflict(self._events(st), ev)
            try:
                changed = ev.to_dict() != Event.from_dict(cur).to_dict()
            except (AttributeError, KeyError, TypeError, ValueError):
                changed = True                                      # evenimentul stocat era stricat

            def f(p):
                p["events"] = [ev.to_dict() if isinstance(e, dict) and e.get("id") == event_id else e
                               for e in p["events"]]
                if changed:
                    # editat/activat: aparitiile lui de pana acum nu se mai executa (nimic retroactiv)
                    p["event_cutoff"][event_id] = now.isoformat()
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def delete_event(self, pid: str, event_id: str) -> dict:
        self._dev(pid)
        with self._plock[pid]:
            if self._find_event(self._get(pid), event_id) is None:
                raise KeyError(f"eveniment necunoscut: {event_id}")

            def f(p):
                p["events"] = [e for e in p["events"]
                               if not (isinstance(e, dict) and e.get("id") == event_id)]
                p["event_cutoff"].pop(event_id, None)
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def _after(self, pid: str) -> None:
        """Dupa o modificare: recalculeaza si rescrie termenul de oprire (nu ridica erori)."""
        try:
            self._sync_deadline(pid, self.clock())
        except Exception:  # noqa: BLE001
            log.exception("sincronizare termen esuata pentru %s", pid)

    # ------------------------------------------------------------------ tick
    def tick(self) -> None:
        now = self.clock()
        prev, self._last_tick = self._last_tick, now
        for pid in self.devices:
            with self._plock[pid]:
                try:
                    self._tick_plug(pid, now, prev)
                except Exception:  # noqa: BLE001
                    log.exception("tick esuat pentru priza %s", pid)

    def _tick_plug(self, pid: str, now: datetime, prev: Optional[datetime]) -> None:
        rt = self._rt[pid]
        self._due_countdown(pid, now)
        if prev is not None and not rt.needs_missed:
            if occurrences_between(self._events(self._get(pid)), prev, now, self.tz):
                self._set(pid, needs_missed=True)
        if (rt.reading is not None and not self._throttled(rt, now)
                and (rt.needs_missed or (rt.retry_at is not None and now >= rt.retry_at))):
            self._process_events(pid, now)
        self._due_off(pid, now, "inching_off_at")
        self._sync_deadline(pid, now)

    def _grace(self, pid: str) -> timedelta:
        return timedelta(seconds=GRACE_S if "countdown" in self.devices[pid].dps else 0)

    @staticmethod
    def _throttled(rt: _Rt, now: datetime) -> bool:
        return rt.next_cmd_try is not None and now < rt.next_cmd_try

    def _due_countdown(self, pid: str, now: datetime, force: bool = False) -> None:
        cd = self._get(pid)["countdown"]
        if not cd:
            return
        rt = self._rt[pid]
        at = _from_epoch(cd["at"])
        action = cd["action"]
        grace = self._grace(pid) if (action == "off" and not force) else timedelta(0)
        if now < at + grace:
            return
        def clear(p):
            if p["countdown"] and p["countdown"].get("at") == cd["at"]:
                p["countdown"] = None
        if (action == "off" and rt.on is False) or (action == "on" and rt.on is True):
            self._mut(pid, clear)
            return
        if action == "on" and now - at > ON_COUNTDOWN_GIVE_UP:
            with self._rt_lock:
                rt.cmd_error = "countdown pornire expirat: priza n-a raspuns"
            self._mut(pid, clear)
            return
        if self._auto_switch(pid, now, action == "on", force=force):
            self._mut(pid, clear)

    def _due_off(self, pid: str, now: datetime, key: str, force: bool = False) -> None:
        """Termen de oprire scadent din starea persistenta (`inching_off_at`)."""
        off_at = self._get(pid)[key]
        rt = self._rt[pid]
        if off_at is None:
            return
        if rt.on is False:
            # Priza sigur oprita: un termen ramas (repornire a serverului, oprire cu mana sau
            # de DP-ul prizei) nu are voie sa supravietuiasca si sa opreasca urmatoarea pornire.
            self._mut(pid, lambda p: p.__setitem__(key, None))
            return
        if now < _from_epoch(off_at) + (timedelta(0) if force else self._grace(pid)):
            return
        if self._auto_switch(pid, now, False, force=force):
            self._mut(pid, lambda p: p.__setitem__(key, None))

    # ------------------------------------------------------------------ evenimente
    def _process_events(self, pid: str, now: datetime) -> None:
        """Executa evenimentul scadent cel mai recent (live, ratat sau esuat), dupa last_event_at.

        Se iau aparitiile active din (max(last_event_at, now - LOOKBACK), now] care nu sunt taiate de
        event_cutoff; conteaza doar cea mai recenta (la aceeasi ora, PORNESTE dupa OPRESTE), cele mai
        vechi sunt depasite de ea si se consuma odata cu ea. O comanda manuala mai noua o anuleaza.
        OPRESTE: nimic de trimis daca priza e sigur oprita, altfel se reincearca la 30 s fara limita.
        PORNESTE: la fel, dar nu mai e reincercat cand o aparitie mai noua devine cea mai recenta.
        Consumat = last_event_at = momentul aparitiei."""
        rt = self._rt[pid]
        st = self._get(pid)
        with self._rt_lock:
            rt.needs_missed = False
            rt.retry_at = None
        lo = now - LOOKBACK
        last = _parse_iso(st["last_event_at"])
        if last is not None and last > lo:
            lo = last
        cutoff = {i: _parse_iso(v) for i, v in st["event_cutoff"].items()}
        pending = [o for o in occurrences_between(self._events(st), lo, now, self.tz)
                   if not (cutoff.get(o.event_id) and o.at <= cutoff[o.event_id])]
        if not pending:
            return
        o = max(pending, key=lambda x: (x.at, x.action == "on", x.event_id))
        last_manual = _parse_iso(st["last_manual_at"])
        want_on = o.action == "on"

        def consume(p):
            cur = _parse_iso(p["last_event_at"])
            if cur is None or o.at > cur:
                p["last_event_at"] = o.at.isoformat()

        # Daca ceva se intampla (esec, lock, disc), reincercarea se programeaza oricum.
        retry = True
        try:
            if not ((last_manual is not None and last_manual > o.at) or rt.on is want_on):
                if not self._auto_switch(pid, now, want_on):
                    return                # se reincearca: ramane cel mai recent pana reuseste sau vine altul
            self._mut(pid, consume)
            retry = False
        finally:
            if retry:
                self._set(pid, retry_at=now + timedelta(seconds=RETRY_S))

    # ------------------------------------------------------------------ termen de oprire in priza
    def _write_dp(self, pid: str, dp: int, value: int, now: datetime) -> bool:
        try:
            self._send(pid, dp, value)
        except CommandError:
            self._set(pid, next_dp_try=now + timedelta(seconds=RETRY_S))
            return False
        self._set(pid, next_dp_try=None)
        return True

    def _sync_deadline(self, pid: str, now: datetime) -> None:
        """Scrie cel mai apropiat termen de oprire in DP-ul de countdown al prizei."""
        rt = self._rt[pid]
        dp = self.devices[pid].dps.get("countdown")
        if dp is None or rt.online is not True or self._throttled(rt, now):
            return
        if rt.next_dp_try is not None and now < rt.next_dp_try:
            return
        if rt.on is False:
            if rt.cd_dirty and self._write_dp(pid, dp, 0, now):
                self._set(pid, cd_dirty=False)
            return
        if rt.on is not True:
            return
        # Nu scriem niciodata un termen nenul intr-un tick in care tocmai am oprit priza.
        if rt.off_sent_at is not None and now - rt.off_sent_at < timedelta(seconds=GRACE_S):
            return
        nd = nearest_off(self._candidates(self._get(pid), now))
        if nd is None:
            if rt.cd_written is None:
                return
            if self._write_dp(pid, dp, 0, now):
                self._set(pid, cd_written=None, cd_capped=False)
            return
        remaining = (nd.at - now).total_seconds()
        if remaining <= 0:
            return                        # scadent: il executa evenimentul sau _due_*
        value = max(1, min(DP_MAX_S, int(remaining)))
        capped = remaining > DP_MAX_S
        if rt.cd_written is not None and (
                abs(_epoch(nd.at) - rt.cd_written) <= DP_TOLERANCE_S or (capped and rt.cd_capped)):
            return
        if self._write_dp(pid, dp, value, now):
            self._set(pid, cd_written=_epoch(now) + value, cd_capped=capped, cd_dirty=False)
