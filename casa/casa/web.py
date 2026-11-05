"""API HTTP al serviciului CASA (prefixul /solar/casa este scos de Caddy).

Toate rutele in afara de /health si /register cer `Authorization: Bearer <token>` (un token
per telefon, vezi auth.py). Corpurile cererilor nu se logheaza niciodata (contin parole).
"""
import atexit
import ipaddress
import logging
import os
import re
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from functools import wraps
from typing import Callable, Optional
from zoneinfo import ZoneInfo

from flask import Flask, g, jsonify, request
from werkzeug.exceptions import HTTPException

from .auth import Auth, BadPassword, NoPassword, RateLimited, RateLimiter
from .config import Config
from .controller import COUNTDOWN_MAX_MIN, INCHING_MAX_MIN, CommandError, Controller
from .devices import load_devices
from .store import CorruptStore, JsonStore

log = logging.getLogger("casa.web")

HHMM = re.compile(r"([01][0-9]|2[0-3]):[0-5][0-9]")
RANGES = ("2h", "1d", "7d")
CHALLENGE = 'Bearer realm="casa"'
MAX_BODY_BYTES = 16000            # = max_size 16KB din Caddy (KB zecimal)
MAX_PASSWORD_LEN = 256
MAX_DEVICE_LEN = 100
COMMAND_LIMIT = (3, 3.0)           # 3 comenzi / 3 s / telefon

ENERGY_TTL_S = 20.0                # interogarile Influx se tin minte scurt: 3 telefoane la 5 s
SPARK_TTL_S = 60.0
FAIL_TTL_S = 30.0                  # dupa o eroare nu mai asteptam Influx la fiecare cerere
# Cat asteapta o cerere o comanda in curs catre aceeasi priza. Cel mai rau caz al unei comenzi:
# PLUG_BUSY_WAIT_S + asteptarea lock-ului priza din controller (pana la CMD_TIMEOUT_S, daca programatorul
# trimite chiar atunci) + CMD_TIMEOUT_S = 5 + 10 + 10 = 25 s; teoretic cel mult ~45 s (comutare + scrierea DP
# + un tick al programatorului). Trebuie sa ramana sub read_timeout-ul Caddy (60 s, caddy/Caddyfile), ca
# telefonul sa primeasca JSON-ul 502/503 al serviciului, nu un 502 gol de la proxy. Timeout-ul de citire al
# comenzilor din aplicatia Android trebuie sa fie 50 s.
PLUG_BUSY_WAIT_S = 5.0
STATE_FILE = "state.json"
DEMO_STATE_FILE = "state.demo.json"   # demo: niciodata starea prizelor reale


class StateIsDemo(RuntimeError):
    """state.json poarta marcajul de demonstratie: nu il rulam pe prizele reale."""


class ApiError(Exception):
    def __init__(self, status: int, error: str, **extra):
        super().__init__(error)
        self.status = status
        self.payload = dict(extra, error=error)


@dataclass
class Services:
    auth: Auth
    controller: object
    cloud: object
    influx: object
    command_limiter: RateLimiter = field(default_factory=lambda: RateLimiter(*COMMAND_LIMIT))
    shutdown: Optional[Callable[[], None]] = None


class _TtlCache:
    def __init__(self):
        self._items: dict = {}
        self._lock = threading.Lock()

    def get(self, key, compute: Callable, ok_ttl: float, default, fail_ttl: float = FAIL_TTL_S):
        now = time.monotonic()
        with self._lock:
            hit = self._items.get(key)
            if hit is not None and hit[0] > now:
                return hit[1]
        try:
            value, ttl = compute(), ok_ttl
        except Exception as e:  # noqa: BLE001 - Influx cazut nu strica restul raspunsului
            log.warning("interogare Influx esuata (%s): %s", key[0], type(e).__name__)
            value, ttl = default, fail_ttl
        with self._lock:
            self._items[key] = (time.monotonic() + ttl, value)
        return value


# ---------------------------------------------------------------------------- constructie --
_services: Optional[Services] = None
_services_lock = threading.Lock()


def build_services(cfg: Optional[Config] = None) -> Services:
    """Serviciile reale (sau de demonstratie) din mediu; porneste firele de fundal."""
    cfg = cfg or Config.from_env()
    # Starea (programari, countdown, inching) a modului demo sta intr-un fisier separat, marcat;
    # modul real refuza un fisier marcat ca demo (altfel o programare de test ar porni prize reale).
    state = JsonStore(os.path.join(cfg.data_dir, DEMO_STATE_FILE if cfg.demo else STATE_FILE))
    if cfg.demo:
        state.update(lambda d: d.__setitem__("demo", True), {"plugs": {}})
    elif state.load({"plugs": {}}).get("demo"):
        raise StateIsDemo(f"{state.path} are marcajul demo: il mut in {DEMO_STATE_FILE} inainte de a porni "
                          "in modul real")
    from .plugs import PlugWorker, TinyTuyaDriver

    auth = Auth(JsonStore(os.path.join(cfg.data_dir, "auth.json")))
    all_devices = load_devices(cfg.data_dir)
    plugs = [d for d in all_devices if d.kind == "plug"]
    holder: dict = {}

    if cfg.demo:
        from .demo import DemoCloud, DemoDriver, DemoInflux, DEMO_WATTS
        log.warning("MOD DEMO: prize false, senzori fixi, fara scrieri in InfluxDB si fara cloud")
        influx = DemoInflux({d.id: DEMO_WATTS[d.name] for d in plugs if d.name in DEMO_WATTS}, cfg.tz)
        cloud = DemoCloud(all_devices)
        drivers = {d.id: DemoDriver(d) for d in plugs}
    else:
        from .cloud import CloudSession
        from .influx import CasaInflux
        from .notify import make_notify
        influx = CasaInflux(cfg)
        cloud = CloudSession(cfg.data_dir, make_notify(cfg.ntfy_url))
        cloud.on_sensor_update = influx.write_sensor
        drivers = {d.id: TinyTuyaDriver(d.id, d.ip, d.local_key, d.version) for d in plugs}

    def on_reading(device):
        def hook(pid, reading):
            holder["controller"].on_reading(pid, reading)
            if not cfg.demo:
                influx.write_plug(device, reading)
        return hook

    workers = {d.id: PlugWorker(d, drivers[d.id], cfg.poll_s, on_reading(d)) for d in plugs}
    controller = Controller(plugs, workers, state, ZoneInfo(cfg.tz), lambda: datetime.now(timezone.utc))
    holder["controller"] = controller

    def shutdown() -> None:
        for step in (controller.stop, *(w.stop for w in workers.values()), cloud.stop, influx.close):
            try:
                step()
            except Exception:  # noqa: BLE001
                log.exception("oprire incompleta")

    influx.start()
    controller.start()
    for w in workers.values():
        w.start()
    cloud.start()
    return Services(auth, controller, cloud, influx, shutdown=shutdown)


def _services_from_env() -> Services:
    global _services
    with _services_lock:
        if _services is None:
            try:
                _services = build_services()
            except StateIsDemo as e:
                log.critical("%s", e)
                sys.exit(1)
            except Exception as e:  # noqa: BLE001 - orice defect la pornire se vede in log, apoi gunicorn se opreste
                log.critical("pornirea serviciului CASA a esuat: %s: %s", type(e).__name__, str(e)[:200])
                raise
            if _services.shutdown is not None:
                atexit.register(_services.shutdown)
        return _services


# ----------------------------------------------------------------------------- validare --
def _body(required: tuple = (), optional: tuple = (), at_least_one: bool = False) -> dict:
    data = request.get_json(force=True, silent=True)
    if not isinstance(data, dict):
        raise ApiError(400, "corp JSON invalid")
    unknown = set(data) - set(required) - set(optional)
    if unknown:
        raise ApiError(400, "camp necunoscut: " + ", ".join(sorted(map(str, unknown))))
    for key in required:
        if key not in data:
            raise ApiError(400, f"lipseste campul: {key}")
    if at_least_one and not data:
        raise ApiError(400, "nimic de modificat")
    return data


def _bool(data: dict, key: str) -> bool:
    if not isinstance(data[key], bool):
        raise ApiError(400, f"{key}: trebuie true sau false")
    return data[key]


def _minutes(data: dict, key: str, hi: int) -> int:
    v = data[key]
    if isinstance(v, bool) or not isinstance(v, int) or not 1 <= v <= hi:
        raise ApiError(400, f"{key}: intreg intre 1 si {hi}")
    return v


def _hhmm(data: dict, key: str) -> str:
    v = data[key]
    if not isinstance(v, str) or not HHMM.fullmatch(v):
        raise ApiError(400, f"{key}: ora trebuie sa fie HH:MM")
    return v


def _client_ip() -> str:
    """Caddy e singurul client (retea bridge, fara port publicat) si pune IP-ul real intr-un
    singur X-Forwarded-For. Se foloseste ULTIMA intrare, numai daca cererea vine dintr-o retea privata."""
    remote = request.remote_addr or ""
    try:
        private = ipaddress.ip_address(remote).is_private
    except ValueError:
        private = False
    xff = request.headers.get("X-Forwarded-For", "")
    if private and xff:
        last = xff.split(",")[-1].strip()
        try:
            return str(ipaddress.ip_address(last))
        except ValueError:
            pass
    return remote or "?"


def _bearer() -> Optional[str]:
    parts = request.headers.get("Authorization", "").split(None, 1)
    if len(parts) == 2 and parts[0].lower() == "bearer":
        return parts[1].strip() or None
    return None


# ---------------------------------------------------------------------------------- app --
def create_app(services: Optional[Services] = None) -> Flask:
    svc = services or _services_from_env()
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = MAX_BODY_BYTES
    cache = _TtlCache()
    controller, cloud, influx, auth = svc.controller, svc.cloud, svc.influx, svc.auth

    # -- erori si antete --
    def unauthorized():
        resp = jsonify({"error": "neautorizat"})
        resp.status_code = 401
        resp.headers["WWW-Authenticate"] = CHALLENGE
        return resp

    @app.errorhandler(ApiError)
    def _api_error(e: ApiError):
        resp = jsonify(e.payload)
        resp.status_code = e.status
        if e.status == 401:
            resp.headers["WWW-Authenticate"] = CHALLENGE
        if e.status == 429 and "retry_after" in e.payload:
            resp.headers["Retry-After"] = str(e.payload["retry_after"])
        return resp

    @app.errorhandler(404)
    def _not_found(_e):
        return jsonify({"error": "negasit"}), 404

    @app.errorhandler(405)
    def _bad_method(_e):
        return jsonify({"error": "metoda nepermisa"}), 405

    @app.errorhandler(413)
    def _too_big(_e):
        return jsonify({"error": "cerere prea mare"}), 413

    @app.errorhandler(Exception)
    def _internal(e):
        if isinstance(e, HTTPException):
            return jsonify({"error": e.name.lower()}), e.code
        if isinstance(e, (CorruptStore, OSError)):
            log.error("eroare de stocare la %s %s: %s: %s", request.method, request.path,
                      type(e).__name__, str(e)[:160])
            return jsonify({"error": "eroare interna"}), 500
        # fara corpul cererii in log; tipul + traseul sunt de ajuns
        log.exception("eroare interna la %s %s", request.method, request.path)
        return jsonify({"error": "eroare interna"}), 500

    @app.after_request
    def _headers(resp):
        resp.headers["Cache-Control"] = "no-store"
        return resp

    def require_token(fn):
        @wraps(fn)
        def wrapper(*args, **kwargs):
            token = _bearer()
            phone = auth.check(token) if token else None
            if phone is None:
                return unauthorized()
            g.phone = phone
            if request.method != "GET" and not svc.command_limiter.hit(phone["phone_id"]):
                raise ApiError(429, "prea multe comenzi",
                               retry_after=svc.command_limiter.retry_after(phone["phone_id"]))
            return fn(*args, **kwargs)
        return wrapper

    def known_plug(pid: str) -> str:
        if pid not in controller.devices:
            raise ApiError(404, "priza necunoscuta")
        return pid

    plug_locks = {pid: threading.Lock() for pid in controller.devices}
    app.config["PLUG_LOCKS"] = plug_locks   # vizibil in teste

    def run(pid: str, call: Callable):
        """Executa o comanda pe priza si intoarce vederea ei; CommandError -> 502 cu vederea.

        Comenzile catre aceeasi priza se executa pe rand; o cerere nu asteapta mai mult de
        PLUG_BUSY_WAIT_S dupa una in curs. Comanda pleaca mereu spre priza, chiar daca pare offline:
        workerul face o incercare imediata de conectare si hotaraste el (502 doar daca esueaza)."""
        lock = plug_locks[pid]
        if not lock.acquire(timeout=PLUG_BUSY_WAIT_S):
            raise ApiError(503, "priza e ocupata, reincearca")
        try:
            return jsonify(call())
        except CommandError:
            resp = jsonify({"error": "priza nu raspunde", "plug": controller.plug_view(pid)})
            resp.status_code = 502
            return resp
        except KeyError as e:
            msg = str(e.args[0]) if e.args else ""
            if not msg.startswith(("interval necunoscut", "priza necunoscuta")):
                raise   # alt KeyError = defect, nu "negasit"
            raise ApiError(404, msg)
        except CorruptStore:
            raise       # fisier de stare stricat: 500, nu "date invalide"
        except ValueError as e:
            raise ApiError(400, str(e))
        finally:
            lock.release()

    # -- fara autentificare --
    @app.get("/health")
    def health():
        return jsonify({"ok": True})

    @app.post("/register")
    def register():
        data = _body(("password", "device"))
        pw, device = data["password"], data["device"]
        if not isinstance(pw, str) or not 1 <= len(pw) <= MAX_PASSWORD_LEN:
            raise ApiError(400, f"parola: text de 1 pana la {MAX_PASSWORD_LEN} caractere")
        if not isinstance(device, str) or len(device) > MAX_DEVICE_LEN:
            raise ApiError(400, f"device: text de cel mult {MAX_DEVICE_LEN} caractere")
        try:
            token, phone_id = auth.register(pw, device, _client_ip())
        except NoPassword:
            raise ApiError(503, "parola nesetata")
        except RateLimited as e:
            raise ApiError(429, "prea multe incercari", retry_after=e.retry_after)
        except BadPassword:
            raise ApiError(401, "parola gresita")
        return jsonify({"token": token, "phone_id": phone_id})

    # -- telefon --
    @app.get("/me")
    @require_token
    def me():
        return jsonify({"phone_id": g.phone["phone_id"], "device": g.phone["device"]})

    # -- prize --
    @app.get("/plugs")
    @require_token
    def plugs():
        out = []
        for view in controller.plugs_view():
            view = dict(view)
            view["energy_today"] = None
            if view.get("metered"):
                view["energy_today"] = cache.get(("energy", view["id"]),
                                                 lambda pid=view["id"]: influx.energy_today(pid),
                                                 ENERGY_TTL_S, None)
            out.append(view)
        return jsonify({"plugs": out, "timestamp": time.time()})

    @app.post("/plugs/<pid>/switch")
    @require_token
    def plug_switch(pid):
        known_plug(pid)
        on = _bool(_body(("on",)), "on")
        return run(pid, lambda: controller.switch(pid, on))

    @app.put("/plugs/<pid>/countdown")
    @require_token
    def plug_countdown(pid):
        known_plug(pid)
        data = _body(("action", "minutes"))
        if data["action"] not in ("on", "off"):
            raise ApiError(400, "action: on sau off")
        minutes = _minutes(data, "minutes", COUNTDOWN_MAX_MIN)
        return run(pid, lambda: controller.set_countdown(pid, data["action"], minutes))

    @app.delete("/plugs/<pid>/countdown")
    @require_token
    def plug_countdown_cancel(pid):
        known_plug(pid)
        return run(pid, lambda: controller.cancel_countdown(pid))

    @app.post("/plugs/<pid>/schedules")
    @require_token
    def schedule_add(pid):
        known_plug(pid)
        data = _body(("start", "stop"), ("enabled",))
        start, stop = _hhmm(data, "start"), _hhmm(data, "stop")
        enabled = _bool(data, "enabled") if "enabled" in data else True
        return run(pid, lambda: controller.add_interval(pid, start, stop, enabled))

    @app.put("/plugs/<pid>/schedules/<sid>")
    @require_token
    def schedule_update(pid, sid):
        known_plug(pid)
        data = _body((), ("start", "stop", "enabled"), at_least_one=True)
        changes = {}
        for key in ("start", "stop"):
            if key in data:
                changes[key] = _hhmm(data, key)
        if "enabled" in data:
            changes["enabled"] = _bool(data, "enabled")
        return run(pid, lambda: controller.update_interval(pid, sid, **changes))

    @app.delete("/plugs/<pid>/schedules/<sid>")
    @require_token
    def schedule_delete(pid, sid):
        known_plug(pid)
        return run(pid, lambda: controller.delete_interval(pid, sid))

    @app.put("/plugs/<pid>/inching")
    @require_token
    def plug_inching(pid):
        known_plug(pid)
        data = _body(("enabled", "minutes"))
        enabled = _bool(data, "enabled")
        minutes = _minutes(data, "minutes", INCHING_MAX_MIN)
        return run(pid, lambda: controller.set_inching(pid, enabled, minutes))

    # -- senzori --
    @app.get("/sensors")
    @require_token
    def sensors():
        out = []
        for s in cloud.sensors():
            out.append({
                "id": s["id"], "name": s["name"], "temperature": s["temperature"],
                "humidity": s["humidity"], "battery": s["battery"], "updated": s["updated"],
                "spark": cache.get(("spark", s["id"]), lambda sid=s["id"]: influx.sensor_spark(sid),
                                   SPARK_TTL_S, []),
            })
        return jsonify({"cloud": cloud.state(), "sensors": out})

    @app.get("/sensors/<sid>/history")
    @require_token
    def sensor_history(sid):
        range_ = request.args.get("range", "")
        if range_ not in RANGES:
            raise ApiError(400, "range: 2h, 1d sau 7d")
        sensor = next((s for s in cloud.sensors() if s["id"] == sid), None)
        if sensor is None:
            raise ApiError(404, "senzor necunoscut")
        try:
            hist = influx.sensor_history(sid, range_)
        except Exception as e:  # noqa: BLE001
            log.warning("istoric senzor esuat: %s", type(e).__name__)
            raise ApiError(503, "istoric indisponibil")
        return jsonify({"id": sid, "name": sensor["name"], "range": range_, **hist})

    return app
