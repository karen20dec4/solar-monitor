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
ON_COUNTDOWN_GIVE_UP = timedelta(minutes=30)
LOOKBACK = timedelta(days=2)      # ferestrele dureaza cel mult ~25 h

_DEFAULT = {"plugs": {}}


class CommandError(Exception):
    """Comanda catre priza a esuat (mesajul se arata in aplicatie)."""


def _blank() -> dict:
    return {"schedules": [], "countdown": None,
            "inching": {"enabled": False, "minutes": 30},
            "last_done": {}, "started_by": None,
            "inching_off_at": None, "last_manual_at": None}


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
    next_dp_try: Optional[datetime] = None
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
        self._op = threading.RLock()       # tick + comenzi publice, serializate
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
            t.join(CMD_TIMEOUT_S + 5)

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
                    rt.needs_missed = True      # prima citire sau reconectare
                if r.on is not None:
                    rt.on = r.on
                    if prev_on is False and r.on:
                        trans = "on"
                    elif prev_on is True and not r.on:
                        trans = "off"
            else:
                rt.poll_error = r.error or "offline"
        if trans == "on":
            self._became_on(pid, now)
        elif trans == "off":
            self._became_off(pid)

    def _became_on(self, pid: str, now: datetime) -> None:
        """oprit -> pornit, din orice sursa: porneste inching-ul (din acest moment)."""
        with self._rt_lock:
            self._rt[pid].cd_written = None
        def f(p):
            inch = p["inching"]
            p["inching_off_at"] = (_epoch(now) + inch["minutes"] * 60) if inch["enabled"] else None
        self._mut(pid, f)

    def _became_off(self, pid: str) -> None:
        with self._rt_lock:
            rt = self._rt[pid]
            rt.cd_written = None
            rt.next_cmd_try = None
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
        if iv and iv.enabled:
            try:
                c.append(OffDeadline(interval_end(iv, now, self.tz), "schedule"))
            except ValueError:
                pass
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
        with self._op:
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
        with self._op:
            at = _epoch(self.clock()) + minutes * 60
            self._mut(pid, lambda p: p.__setitem__("countdown", {"action": action, "at": at}))
            self._after(pid)
        return self.plug_view(pid)

    def cancel_countdown(self, pid: str) -> dict:
        self._dev(pid)
        with self._op:
            self._mut(pid, lambda p: p.__setitem__("countdown", None))
            self._after(pid)
        return self.plug_view(pid)

    def set_inching(self, pid: str, enabled: bool, minutes: int) -> dict:
        self._dev(pid)
        _int_in(minutes, 1, INCHING_MAX_MIN, "minute inching")
        enabled = bool(enabled)
        with self._op:
            now = self.clock()
            with self._rt_lock:
                is_on = self._rt[pid].on is True
            def f(p):
                p["inching"] = {"enabled": enabled, "minutes": minutes}
                if is_on:  # priza deja pornita: inching-ul curge din momentul modificarii
                    p["inching_off_at"] = _epoch(now) + minutes * 60 if enabled else None
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def add_interval(self, pid: str, start: str, stop: str, enabled: bool = True) -> dict:
        self._dev(pid)
        with self._op:
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
                # fronturile trecute ale unui interval nou nu se mai executa
                p["last_done"][iv.id] = {"start": now, "stop": now}
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def update_interval(self, pid: str, interval_id: str, **changes) -> dict:
        self._dev(pid)
        unknown = set(changes) - {"start", "stop", "enabled"}
        if unknown:
            raise ValueError(f"campuri necunoscute: {sorted(unknown)}")
        with self._op:
            now = self.clock()
            st = self._get(pid)
            cur = next((s for s in st["schedules"] if s["id"] == interval_id), None)
            if cur is None:
                raise KeyError(f"interval necunoscut: {interval_id}")
            new = dict(cur)
            new.update(changes)
            iv = Interval(interval_id, new["start"], new["stop"], bool(new["enabled"]))
            changed = (iv.start, iv.stop, iv.enabled) != (cur["start"], cur["stop"], bool(cur["enabled"]))
            in_window = True
            if iv.enabled:
                try:
                    interval_end(iv, now, self.tz)
                except ValueError:
                    in_window = False
            def f(p):
                for s in p["schedules"]:
                    if s["id"] == interval_id:
                        s.update({"start": iv.start, "stop": iv.stop, "enabled": iv.enabled})
                if changed:
                    p["last_done"][interval_id] = {"start": now.isoformat(), "stop": now.isoformat()}
                    if p["started_by"] == interval_id and not (iv.enabled and in_window):
                        p["started_by"] = None
            self._mut(pid, f)
            self._after(pid)
        return self.plug_view(pid)

    def delete_interval(self, pid: str, interval_id: str) -> dict:
        self._dev(pid)
        with self._op:
            if not any(s["id"] == interval_id for s in self._get(pid)["schedules"]):
                raise KeyError(f"interval necunoscut: {interval_id}")
            def f(p):
                p["schedules"] = [s for s in p["schedules"] if s["id"] != interval_id]
                p["last_done"].pop(interval_id, None)
                if p["started_by"] == interval_id:
                    p["started_by"] = None
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
        with self._op:
            now = self.clock()
            prev, self._last_tick = self._last_tick, now
            for pid in self.devices:
                try:
                    self._tick_plug(pid, now, prev)
                except Exception:  # noqa: BLE001
                    log.exception("tick esuat pentru priza %s", pid)

    def _tick_plug(self, pid: str, now: datetime, prev: Optional[datetime]) -> None:
        rt = self._rt[pid]
        self._due_countdown(pid, now)
        if prev is not None and not rt.needs_missed:
            ivs = [i for i in self._intervals(self._get(pid)).values()]
            if edges_between(ivs, prev, now, self.tz):
                rt.needs_missed = True
        if rt.reading is not None and (
                rt.needs_missed or (rt.retry_at is not None and now >= rt.retry_at)):
            self._process_missed(pid, now)
        self._due_inching(pid, now)
        self._sync_deadline(pid, now)

    def _grace(self, pid: str) -> timedelta:
        return timedelta(seconds=GRACE_S if "countdown" in self.devices[pid].dps else 0)

    def _throttled(self, rt: _Rt, now: datetime) -> bool:
        return rt.next_cmd_try is not None and now < rt.next_cmd_try

    def _due_countdown(self, pid: str, now: datetime) -> None:
        cd = self._get(pid)["countdown"]
        if not cd:
            return
        rt = self._rt[pid]
        at = _from_epoch(cd["at"])
        action = cd["action"]
        if now < at + (self._grace(pid) if action == "off" else timedelta(0)):
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
        if self._throttled(rt, now):
            return
        try:
            self._send_switch(pid, action == "on")
        except CommandError:
            rt.next_cmd_try = now + timedelta(seconds=RETRY_S)
            return
        rt.next_cmd_try = None
        self._mut(pid, clear)

    def _due_inching(self, pid: str, now: datetime) -> None:
        off_at = self._get(pid)["inching_off_at"]
        rt = self._rt[pid]
        if off_at is None or rt.on is False:
            return
        if now < _from_epoch(off_at) + self._grace(pid) or self._throttled(rt, now):
            return
        try:
            self._send_switch(pid, False)
        except CommandError:
            rt.next_cmd_try = now + timedelta(seconds=RETRY_S)
            return
        rt.next_cmd_try = None
        self._mut(pid, lambda p: p.__setitem__("inching_off_at", None))

    # ------------------------------------------------------------------ fronturi
    def _process_missed(self, pid: str, now: datetime) -> None:
        """Executa fronturile neexecutate (live, ratate sau esuate), dupa last_done."""
        rt = self._rt[pid]
        st = self._get(pid)
        ivs = self._intervals(st)
        edges = missed_edges(list(ivs.values()), st["last_done"], now, self.tz, lookback=LOOKBACK)
        rt.needs_missed = False
        rt.retry_at = None
        if not edges:
            return
        last_manual = _parse_iso(st["last_manual_at"])
        newest_start = max((d for d in (_parse_iso(v.get("start"))
                                        for v in st["last_done"].values()) if d), default=None)
        latest_start = {e.interval_id: e for e in edges if e.kind == "start"}
        known_on = rt.on
        failed = False

        def mark(e):
            self._mut(pid, lambda p: p["last_done"].setdefault(e.interval_id, {}).__setitem__(
                e.kind, e.at.isoformat()))

        for e in edges:
            iv = ivs[e.interval_id]
            if e.kind == "start":
                in_window = True
                try:
                    interval_end(iv, now, self.tz)
                except ValueError:
                    in_window = False
                if latest_start[e.interval_id] is not e or not in_window:
                    mark(e)               # nu mai poate fi executat: consumat
                    continue
                if known_on is not True:
                    try:
                        self._send_switch(pid, True)
                    except CommandError:
                        failed = True     # reincercam cat timp suntem in interval
                        continue
                    known_on = True
                self._mut(pid, lambda p, i=iv.id: p.__setitem__("started_by", i))
                mark(e)
                newest_start = max(newest_start, e.at) if newest_start else e.at
            else:
                if (known_on is False
                        or (last_manual is not None and last_manual > e.at)
                        or (newest_start is not None and newest_start > e.at)):
                    mark(e)               # priza deja oprita / comanda sau pornire mai noua
                    continue
                try:
                    self._send_switch(pid, False)
                except CommandError:
                    failed = True         # stop-ul se reincearca fara limita
                    continue
                known_on = False
                self._mut(pid, lambda p: p.__setitem__("started_by", None))
                mark(e)
        if failed:
            rt.retry_at = now + timedelta(seconds=RETRY_S)

    # ------------------------------------------------------------------ termen de oprire in priza
    def _sync_deadline(self, pid: str, now: datetime) -> None:
        """Scrie cel mai apropiat termen de oprire in DP-ul de countdown al prizei."""
        rt = self._rt[pid]
        dp = self.devices[pid].dps.get("countdown")
        if dp is None or rt.online is not True or rt.on is not True:
            return
        if rt.next_dp_try is not None and now < rt.next_dp_try:
            return
        nd = nearest_off(self._candidates(self._get(pid), now))
        if nd is None:
            if rt.cd_written is None:
                return
            value, written, capped = 0, None, False
        else:
            remaining = (nd.at - now).total_seconds()
            if remaining <= 0:
                return                    # scadent: il executa frontul sau _due_*
            value = max(1, min(DP_MAX_S, int(remaining)))
            capped = remaining > DP_MAX_S
            if rt.cd_written is not None and (
                    abs(_epoch(nd.at) - rt.cd_written) <= DP_TOLERANCE_S
                    or (capped and rt.cd_capped)):
                return
            written = _epoch(now) + value
        try:
            self._send(pid, dp, value)
        except CommandError:
            rt.next_dp_try = now + timedelta(seconds=RETRY_S)
            return
        rt.next_dp_try = None
        rt.cd_written, rt.cd_capped = written, capped
