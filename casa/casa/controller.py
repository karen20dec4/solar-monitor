"""Programatorul prizelor: comenzi, programari, countdown, inching, fronturi ratate.

Starea care trebuie sa supravietuiasca unei reporniri (programari, countdown, inching,
ultimul front executat) sta in state.json (JsonStore). Starea de moment a prizei
(citirea, erori, ce s-a scris in DP-ul de countdown) sta doar in memorie.

Reguli de siguranta:
  * oprirea conteaza mai mult decat pornirea: un "stop" ratat se executa oricat de vechi,
    se reincearca la 30 s fara limita; un "start" doar cat timp suntem in interval;
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
from .plugs import PlugReading
from .schedule import (Interval, OffDeadline, edges_between, interval_end, missed_edges,
                       nearest_off)
from .store import JsonStore

log = logging.getLogger("casa.controller")
UTC = timezone.utc

TICK_S = 5
RETRY_S = 30
CMD_TIMEOUT_S = 10
MAX_INTERVALS = 6
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
LOOKBACK = timedelta(days=2)      # ferestrele dureaza cel mult ~25 h

_DEFAULT = {"plugs": {}}


class CommandError(Exception):
    """Comanda catre priza a esuat (mesajul se arata in aplicatie)."""


def _blank() -> dict:
    return {"schedules": [], "countdown": None,
            "inching": {"enabled": False, "minutes": 30},
            "last_done": {}, "started_by": None,
            "inching_off_at": None, "last_manual_at": None,
            # last_done = fronturi "consumate" (executate sau sarite); last_start_at = ultimul
            # start EXECUTAT cu adevarat; cutoff = momentul crearii/editarii unui interval
            # (fronturile lui mai vechi nu se mai executa). Sunt separate ca o creare/editare
            # sa nu poata anula un stop in asteptare.
            "last_start_at": None, "cutoff": {}}


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
    retry_at: Optional[datetime] = None  # reincercare front esuat
    cd_written: Optional[int] = None     # termenul (epoch) scris in DP-ul de countdown
    cd_capped: bool = False
    cd_dirty: bool = False               # priza oprita cu un DP scris: de anulat (0)
    next_dp_try: Optional[datetime] = None
    next_resync: Optional[datetime] = None
    off_sent_at: Optional[datetime] = None
    # UN singur ritm de reincercare pe priza, respectat de TOATE caile automate
    # (fronturi, countdown, inching): cel mult o incercare la 30 s cat timp esueaza.
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
    def _intervals(st: dict) -> dict:
        out = {}
        for s in st["schedules"]:
            try:
                out[s["id"]] = Interval(s["id"], s["start"], s["stop"], bool(s["enabled"]))
            except (KeyError, ValueError):
                log.warning("interval invalid ignorat: %r", s)
        return out

    def _window_end(self, iv: Interval, now: datetime) -> Optional[datetime]:
        """Sfarsitul ferestrei intervalului care contine `now`, sau None."""
        try:
            return interval_end(iv, now, self.tz)
        except ValueError:
            return None

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
            else:
                rt.poll_error = r.error or "offline"
        if trans == "on":
            self._became_on(pid, now)
        elif trans == "first_on":
            self._became_on(pid, now, only_if_unset=True)
        elif trans == "off":
            self._became_off(pid)

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
            p["started_by"] = None
            p["inching_off_at"] = None
            if p["countdown"] and p["countdown"].get("action") == "off":
                p["countdown"] = None
        self._mut(pid, f)

    # ------------------------------------------------------------------ vederi
    def _candidates(self, st: dict, now: datetime) -> list:
        c = []
        sb = st["started_by"]
        iv = self._intervals(st).get(sb) if sb else None
        end = self._window_end(iv, now) if iv and iv.enabled else None
        if end:
            c.append(OffDeadline(end, "schedule"))
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
        return {
            "id": pid,
            "name": dev.name,
            "online": online,
            "on": on,
            "metered": metered,
            "power": r.power if live else None,
            "voltage": r.voltage if live else None,
            "current": r.current if live else None,
            "schedules": [{"id": s["id"], "start": s["start"], "stop": s["stop"],
                           "enabled": bool(s["enabled"])} for s in st["schedules"]],
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
        self._dev(pid)
        with self._plock[pid]:
            now = self.clock()
            self._send_switch(pid, on)          # CommandError: nimic nu se schimba
            def f(p):
                p["countdown"] = None
                p["started_by"] = None
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

    def set_inching(self, pid: str, enabled: bool, minutes: int) -> dict:
        self._dev(pid)
        _int_in(minutes, 1, INCHING_MAX_MIN, "minute inching")
        enabled = bool(enabled)
        with self._plock[pid]:
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

    def add_interval(self, pid: str, start: str, stop: str, enabled: bool = True) -> dict:
        self._dev(pid)
        with self._plock[pid]:
            st = self._get(pid)
            if len(st["schedules"]) >= MAX_INTERVALS:
                raise ValueError(f"cel mult {MAX_INTERVALS} intervale pe priza")
            taken = {s["id"] for s in st["schedules"]}
            iid = secrets.token_hex(2)
            while iid in taken:
                iid = secrets.token_hex(2)
            iv = Interval(iid, start, stop, bool(enabled))  # valideaza formatul
            now = self.clock().isoformat()
            def f(p):
                p["schedules"].append({"id": iv.id, "start": iv.start, "stop": iv.stop,
                                       "enabled": iv.enabled})
                p["cutoff"][iv.id] = now      # fronturile trecute ale unui interval nou nu se executa
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    @staticmethod
    def _earlier_off_countdown(p: dict, at: int) -> None:
        """Pune un countdown "oprire" la `at`, doar daca nu exista deja unul mai devreme."""
        cd = p["countdown"]
        if not (cd and cd.get("action") == "off" and cd["at"] <= at):
            p["countdown"] = {"action": "off", "at": at}

    def _release_holder(self, pid: str, now: datetime, old: Interval,
                        new_end: Optional[datetime], retired: bool) -> Optional[int]:
        """Intervalul `old` tine priza (started_by) si e editat/dezactivat/sters.
        Priza nu ramane NICIODATA pornita fara termen. Intoarce (epoch) momentul
        pentru un countdown "oprire", sau None daca intervalul ramane valabil.
          * editat, fereastra noua contine `now`  -> ramane started_by, termen = noul sfarsit;
          * editat, fereastra noua nu mai contine -> oprire acum;
          * dezactivat / sters                     -> ramane pornita pana la vechiul stop."""
        if new_end is not None and not retired:
            return None
        old_end = self._window_end(old, now) if retired else None
        return _epoch(old_end or now)

    def update_interval(self, pid: str, interval_id: str, **changes) -> dict:
        self._dev(pid)
        unknown = set(changes) - {"start", "stop", "enabled"}
        if unknown:
            raise ValueError(f"campuri necunoscute: {sorted(unknown)}")
        with self._plock[pid]:
            now = self.clock()
            st = self._get(pid)
            cur = next((s for s in st["schedules"] if s["id"] == interval_id), None)
            if cur is None:
                raise KeyError(f"interval necunoscut: {interval_id}")
            new = dict(cur)
            new.update(changes)
            iv = Interval(interval_id, new["start"], new["stop"], bool(new["enabled"]))
            old = Interval(interval_id, cur["start"], cur["stop"], bool(cur["enabled"]))
            changed = (iv.start, iv.stop, iv.enabled) != (old.start, old.stop, old.enabled)
            new_end = self._window_end(iv, now) if iv.enabled else None
            off_at = None
            if changed and st["started_by"] == interval_id and self._rt[pid].on is not False:
                off_at = self._release_holder(pid, now, old, new_end, retired=not iv.enabled)
            def f(p):
                for s in p["schedules"]:
                    if s["id"] == interval_id:
                        s.update({"start": iv.start, "stop": iv.stop, "enabled": iv.enabled})
                if changed:
                    p["cutoff"][interval_id] = now.isoformat()
                    if p["started_by"] == interval_id and new_end is None:
                        p["started_by"] = None
                if off_at is not None:
                    self._earlier_off_countdown(p, off_at)
            self._mut(pid, f)
            self._flush_due(pid, now, off_at)
        return self.plug_view(pid)

    def delete_interval(self, pid: str, interval_id: str) -> dict:
        self._dev(pid)
        with self._plock[pid]:
            now = self.clock()
            st = self._get(pid)
            cur = next((s for s in st["schedules"] if s["id"] == interval_id), None)
            if cur is None:
                raise KeyError(f"interval necunoscut: {interval_id}")
            off_at = None
            if st["started_by"] == interval_id and self._rt[pid].on is not False:
                old = Interval(interval_id, cur["start"], cur["stop"], bool(cur["enabled"]))
                off_at = self._release_holder(pid, now, old, None, retired=True)
            def f(p):
                p["schedules"] = [s for s in p["schedules"] if s["id"] != interval_id]
                p["last_done"].pop(interval_id, None)
                p["cutoff"].pop(interval_id, None)
                if p["started_by"] == interval_id:
                    p["started_by"] = None
                if off_at is not None:
                    self._earlier_off_countdown(p, off_at)
            self._mut(pid, f)
            self._flush_due(pid, now, off_at)
        return self.plug_view(pid)

    def _flush_due(self, pid: str, now: datetime, off_at: Optional[int]) -> None:
        """Dupa o editare: daca s-a cerut oprire imediata o incearca acum (esecul ramane in
        countdown si se reincearca la 30 s), apoi rescrie termenul pe priza."""
        try:
            if off_at is not None and off_at <= _epoch(now):
                self._due_countdown(pid, now, force=True)
        except Exception:  # noqa: BLE001
            log.exception("oprire imediata esuata pentru %s", pid)
        self._after(pid)

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
            ivs = list(self._intervals(self._get(pid)).values())
            if edges_between(ivs, prev, now, self.tz):
                self._set(pid, needs_missed=True)
        if (rt.reading is not None and not self._throttled(rt, now)
                and (rt.needs_missed or (rt.retry_at is not None and now >= rt.retry_at))):
            self._process_missed(pid, now)
        self._due_inching(pid, now)
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

    def _due_inching(self, pid: str, now: datetime) -> None:
        off_at = self._get(pid)["inching_off_at"]
        rt = self._rt[pid]
        if off_at is None or rt.on is False:
            return
        if now < _from_epoch(off_at) + self._grace(pid):
            return
        if self._auto_switch(pid, now, False):
            self._mut(pid, lambda p: p.__setitem__("inching_off_at", None))

    # ------------------------------------------------------------------ fronturi
    def _process_missed(self, pid: str, now: datetime) -> None:
        """Executa fronturile neexecutate (live, ratate sau esuate), dupa last_done.

        Stop: mereu, oricat de vechi, daca priza nu e sigur oprita si nu l-a anulat o
        comanda manuala mai noua, un start executat mai nou sau alt interval care tine
        priza (started_by). Start: doar daca fereastra lui inca contine `now` si nu a
        urmat o comanda manuala. La acelasi instant, stop-urile merg inaintea start-urilor."""
        rt = self._rt[pid]
        st = self._get(pid)
        ivs = self._intervals(st)
        cutoff = {i: _parse_iso(v) for i, v in st["cutoff"].items()}
        edges = [e for e in missed_edges(list(ivs.values()), st["last_done"], now, self.tz,
                                         lookback=LOOKBACK)
                 if not (cutoff.get(e.interval_id) and e.at <= cutoff[e.interval_id])]
        edges.sort(key=lambda e: (e.at, e.kind != "stop", e.interval_id))
        with self._rt_lock:
            rt.needs_missed = False
            rt.retry_at = None
        if not edges:
            return
        last_manual = _parse_iso(st["last_manual_at"])
        last_start = _parse_iso(st["last_start_at"])
        holder = st["started_by"]
        latest_start = {e.interval_id: e for e in edges if e.kind == "start"}
        known_on = rt.on

        def record(e, started_by="=", real_start=False):
            def f(p):
                p["last_done"].setdefault(e.interval_id, {})[e.kind] = e.at.isoformat()
                if started_by != "=":
                    p["started_by"] = started_by
                if real_start:
                    cur = _parse_iso(p["last_start_at"])
                    if cur is None or e.at > cur:
                        p["last_start_at"] = e.at.isoformat()
            self._mut(pid, f)

        # Daca ceva se intampla (esec, lock, disc), reincercarea se programeaza oricum.
        pending = True
        try:
            for e in edges:
                iv = ivs[e.interval_id]
                if e.kind == "start":
                    if (latest_start[e.interval_id] is not e or self._window_end(iv, now) is None
                            or (last_manual is not None and last_manual > e.at)):
                        record(e)             # nu mai poate / nu mai trebuie executat: consumat
                        continue
                    if known_on is not True:
                        if not self._auto_switch(pid, now, True):
                            break             # reincercam cat timp suntem in interval
                        known_on = True
                    holder = iv.id
                    last_start = e.at if last_start is None or e.at > last_start else last_start
                    record(e, started_by=iv.id, real_start=True)
                else:
                    if (known_on is False
                            or (last_manual is not None and last_manual > e.at)
                            or (last_start is not None and last_start > e.at)
                            or (holder is not None and holder != e.interval_id)):
                        record(e)             # deja oprita / anulat de ceva mai nou / tinuta de alt interval
                        continue
                    if not self._auto_switch(pid, now, False):
                        break                 # stop-ul se reincearca fara limita
                    known_on, holder = False, None
                    record(e, started_by=None)
            else:
                pending = False
        finally:
            if pending:
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
            return                        # scadent: il executa frontul sau _due_*
        value = max(1, min(DP_MAX_S, int(remaining)))
        capped = remaining > DP_MAX_S
        if rt.cd_written is not None and (
                abs(_epoch(nd.at) - rt.cd_written) <= DP_TOLERANCE_S or (capped and rt.cd_capped)):
            return
        if self._write_dp(pid, dp, value, now):
            self._set(pid, cd_written=_epoch(now) + value, cd_capped=capped, cd_dirty=False)
