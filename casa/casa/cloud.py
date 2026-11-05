"""Sesiune cloud Tuya (Smart Life): login cu cod QR, lista dispozitivelor cu chei locale, senzori prin MQTT.

Fara cont de dezvoltator Tuya: se foloseste SDK-ul oficial `tuya-device-sharing-sdk` (aceeasi cale ca
integrarea Home Assistant). Verificat pe:
  * SDK `tuya_sharing` 0.2.15 (sursa din imagine: user.py LoginControl, manager.py Manager,
    device.py CustomerDevice, customerapi.py SharingTokenListener, mq.py SharingMQ);
  * Home Assistant core, `homeassistant/components/tuya/` (config_flow.py, const.py, __init__.py) si
    `tuya/tuya-smart-life`, `custom_components/smartlife/` (config_flow.py, const.py): client_id
    `HA_3y9q4ak7g4ephrvke`, schema `haauthorize`, continut QR `tuyaSmart--qrLogin?token=<qrcode>`.
Fluxul: LoginControl().qr_code(client_id, schema, user_code) -> result.qrcode -> se afiseaza QR ->
login_result(token, client_id, user_code) pana da (True, info) cu info = {t, uid, expire_time,
access_token, refresh_token, terminal_id, endpoint} -> Manager(client_id, user_code, terminal_id,
endpoint, token_info, token_listener) -> update_device_cache() -> device_map -> add_device_listener()
-> refresh_mq().

Clasele SDK se pot injecta (`manager_factory`, `login_control`) ca testele sa nu atinga reteaua.
"""
import json
import logging
import os
import sys
import threading
import time
from typing import Callable, Literal, Optional

from .devices import DeviceInfo, clean_name, dp_map_from_strategy, save_devices
from .store import JsonStore

log = logging.getLogger("casa.cloud")

CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
SCHEMA = "haauthorize"
QR_PREFIX = "tuyaSmart--qrLogin?token="
SESSION_FILE = "cloud_session.json"

# --- Tabele de mapare (de verificat pe dispozitivele reale, Task 15) --------------------------
KIND_BY_CATEGORY = {"cz": "plug", "kg": "plug", "pc": "plug", "wsdcg": "sensor"}
CODE_TEMPERATURE = "va_temperature"   # intreg; zecimale din status_range/local_strategy (implicit 1 -> /10)
CODE_HUMIDITY = "va_humidity"         # intreg; implicit 0 zecimale
CODE_BATTERY_PCT = "battery_percentage"  # 0-100
CODE_BATTERY_STATE = "battery_state"
BATTERY_STATE_PCT = {"low": 10, "middle": 50, "high": 100}
DEFAULT_SCALE = {CODE_TEMPERATURE: 1, CODE_HUMIDITY: 0}
SENSOR_CODES = {CODE_TEMPERATURE, CODE_HUMIDITY, CODE_BATTERY_PCT, CODE_BATTERY_STATE}
# cheile de masurare din DeviceInfo.dps si codul Tuya corespunzator (pentru prize fara strategie locala)
METERING_CODES = {"power": "cur_power", "voltage": "cur_voltage", "current": "cur_current"}

LOGIN_TIMEOUT_S = 120
LOGIN_POLL_S = 2
OK_REFRESH_S = 600       # un refresh reusit din ultimele 10 min conteaza ca "ok"
QUIET_MQTT_S = 300       # MQTT tacut -> update_device_cache(), cel mult o data la 5 min
NOTIFY_AFTER_S = 1800    # nu e ok de 30 min -> notificare, o singura data pana la revenire
TICK_S = 30

State = Literal["ok", "disconnected", "no_session"]


class LoginFailed(Exception):
    """Login imposibil sau sesiune lipsa (mesajul nu contine secrete)."""


def _epoch(value) -> Optional[float]:
    """Timestamp Tuya (secunde sau milisecunde) -> secunde epoch; None daca lipseste."""
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if v <= 0:
        return None
    return v / 1000.0 if v > 1e11 else v


def _scale(device, code: str) -> int:
    """Zecimale pentru un cod: status_range[cod].values, apoi local_strategy, apoi implicit."""
    rng = (getattr(device, "status_range", None) or {}).get(code)
    sources = [getattr(rng, "values", None)]
    for item in (getattr(device, "local_strategy", None) or {}).values():
        if isinstance(item, dict) and item.get("status_code") == code:
            sources.append((item.get("config_item") or {}).get("valueDesc"))
    for raw in sources:
        try:
            desc = json.loads(raw) if isinstance(raw, str) else raw
            return int(desc["scale"])
        except (TypeError, ValueError, KeyError):
            continue
    return DEFAULT_SCALE.get(code, 0)


def _number(device, code: str) -> Optional[float]:
    raw = (device.status or {}).get(code)
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        return None
    scale = _scale(device, code)
    return round(raw / (10 ** scale), scale) if scale else float(raw)


def _battery(device) -> Optional[float]:
    status = device.status or {}
    pct = status.get(CODE_BATTERY_PCT)
    if isinstance(pct, (int, float)) and not isinstance(pct, bool):
        return float(pct)
    mapped = BATTERY_STATE_PCT.get(status.get(CODE_BATTERY_STATE))
    return float(mapped) if mapped is not None else None


def _kind(category: str) -> str:
    return KIND_BY_CATEGORY.get(category or "", "other")


class _DeviceListener:
    """Ascultator pentru `Manager.add_device_listener` (SDK apeleaza update/add/remove_device)."""

    def __init__(self, cloud: "CloudSession"):
        self._cloud = cloud

    def update_device(self, device, updated_status_properties=None, dp_timestamps=None):
        self._cloud._on_device_update(device, updated_status_properties, dp_timestamps)

    def add_device(self, device):
        self._cloud._on_device_update(device, list(SENSOR_CODES), None)

    def remove_device(self, device_id):
        self._cloud._drop_sensor(device_id)


class _TokenListener:
    """`SharingTokenListener`: SDK-ul ne da tokenul reinnoit; il salvam in sesiune (mod 600)."""

    def __init__(self, cloud: "CloudSession"):
        self._cloud = cloud

    def update_token(self, token_info):
        self._cloud._save_token(token_info)


class CloudSession:
    def __init__(self, data_dir: str, notify: Callable[[str, str, str], None],
                 clock: Callable[[], float] = time.time, manager_factory=None, login_control=None,
                 sleep: Callable[[float], None] = time.sleep):
        self.data_dir = data_dir
        self._notify = notify
        self._clock = clock
        self._sleep = sleep
        self._manager_factory = manager_factory
        self._login = login_control
        self._store = JsonStore(os.path.join(data_dir, SESSION_FILE))
        self._manager = None
        self._listening = False
        self._mq_started = False
        self._lock = threading.RLock()
        self._sensors: dict = {}
        self._last_msg = clock()
        self._last_refresh_ok: Optional[float] = None
        self._last_refresh_try: Optional[float] = None
        self._bad_since: Optional[float] = None
        self._notified = False
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self.on_sensor_update: Optional[Callable[[dict], None]] = None

    # ------------------------------------------------------------------ sesiune --
    def _load_session(self) -> Optional[dict]:
        try:
            data = self._store.load({})
        except ValueError:
            log.error("cloud_session.json corupt")
            return None
        need = ("user_code", "terminal_id", "endpoint", "token_info")
        return data if all(data.get(k) for k in need) else None

    def _save_token(self, token_info: dict) -> None:
        def put(data):
            data["token_info"] = dict(token_info)
        self._store.update(put, {})

    # -------------------------------------------------------------------- login --
    def login_interactive(self, user_code: str, out=sys.stdout) -> None:
        import qrcode  # import tarziu: nu e nevoie de el pentru serviciu

        login = self._login_control()
        resp = login.qr_code(CLIENT_ID, SCHEMA, user_code)
        if not resp.get("success"):
            raise LoginFailed(f"Tuya a refuzat codul de utilizator: {resp.get('msg', 'eroare necunoscuta')}")
        token = resp["result"]["qrcode"]
        qr = qrcode.QRCode(border=1)
        qr.add_data(QR_PREFIX + token)
        qr.make(fit=True)
        print("Smart Life > Eu (profil) > Scaneaza (pictograma din colt) si scaneaza codul de mai jos.\n"
              f"Ai {LOGIN_TIMEOUT_S} secunde.\n", file=out)
        qr.print_ascii(out=out, invert=True)
        deadline = self._clock() + LOGIN_TIMEOUT_S
        info = None
        while self._clock() < deadline:
            try:
                ok, result = login.login_result(token, CLIENT_ID, user_code)
            except Exception as exc:  # noqa: BLE001 - retea intrerupta: mai incercam
                log.warning("login_result esuat: %s", exc)
                ok, result = False, {}
            if ok:
                info = result
                break
            self._sleep(LOGIN_POLL_S)
        if info is None:
            raise LoginFailed("Timp expirat: codul QR nu a fost scanat in 120 s.")
        session = {
            "user_code": user_code,
            "terminal_id": info["terminal_id"],
            "endpoint": info["endpoint"],
            "token_info": {k: info.get(k) for k in ("t", "uid", "expire_time", "access_token", "refresh_token")},
        }
        self._store.save(session, mode=0o600)
        self._manager = None
        self._listening = False
        self._mq_started = False
        print("Autentificare reusita. Sincronizez dispozitivele...", file=out)
        self.sync()

    def _login_control(self):
        if self._login is None:
            from tuya_sharing import LoginControl
            self._login = LoginControl()
        return self._login

    # ------------------------------------------------------------------ manager --
    def _ensure_manager(self):
        with self._lock:
            if self._manager is not None:
                return self._manager
            s = self._load_session()
            if s is None:
                raise LoginFailed("Nu exista sesiune Tuya. Ruleaza: python -m casa login")
            factory = self._manager_factory
            if factory is None:
                from tuya_sharing import Manager as factory
            self._manager = factory(CLIENT_ID, s["user_code"], s["terminal_id"], s["endpoint"],
                                    s["token_info"], _TokenListener(self))
            return self._manager

    def _refresh_cache(self) -> None:
        mgr = self._ensure_manager()
        self._last_refresh_try = self._clock()
        mgr.update_device_cache()
        with self._lock:
            self._last_refresh_ok = self._clock()
            for dev in list(mgr.device_map.values()):
                if _kind(getattr(dev, "category", "")) == "sensor":
                    self._store_sensor(dev, None, from_cache=True)

    # --------------------------------------------------------------------- sync --
    def sync(self) -> list:
        """Descarca lista de dispozitive si o scrie in devices.json (mod 600, cu chei locale)."""
        self._refresh_cache()
        devices = []
        for dev in self._manager.device_map.values():
            category = getattr(dev, "category", "") or ""
            kind = _kind(category)
            dps, scales = {}, {}
            if kind == "plug":
                strategy = getattr(dev, "local_strategy", None) or {}
                dps, scales = dp_map_from_strategy(strategy)
                status = getattr(dev, "status", None) or {}
                if not strategy and status:
                    # fara strategie locala nu inventam masurare: doar ce apare in starea prizei
                    for key, code in METERING_CODES.items():
                        if code not in status:
                            dps.pop(key, None)
                            scales.pop(key, None)
            raw = getattr(dev, "name", "") or ""
            devices.append(DeviceInfo(
                id=dev.id, name=clean_name(raw) or raw, kind=kind,
                local_key=getattr(dev, "local_key", None), ip=None, version=None,
                dps=dps, scales=scales, category=category, raw_name=raw))
        save_devices(self.data_dir, devices)
        return devices

    # ------------------------------------------------------------------ senzori --
    def _store_sensor(self, dev, timestamps, from_cache=False, touched=None) -> Optional[dict]:
        now = self._clock()
        prev = self._sensors.get(dev.id)
        values = {"temperature": _number(dev, CODE_TEMPERATURE), "humidity": _number(dev, CODE_HUMIDITY),
                  "battery": _battery(dev)}
        prev_updated = prev["updated"] if prev else None
        if from_cache:
            ut = _epoch(getattr(dev, "update_time", None))
            if ut is not None:
                updated = max(ut, prev_updated) if prev_updated else ut
            elif prev is None:
                updated = None
            elif all(prev[k] == v for k, v in values.items()):
                updated = prev_updated
            else:
                updated = now
        else:
            stamps = [e for e in (_epoch(t) for code, t in (timestamps or {}).items()
                                  if code in (touched or ())) if e is not None]
            updated = max(stamps) if stamps else now
        entry = {"id": dev.id, "name": clean_name(dev.name or "") or dev.name, **values, "updated": updated}
        self._sensors[dev.id] = entry
        return entry

    def _on_device_update(self, dev, props, timestamps) -> None:
        self._last_msg = self._clock()
        if _kind(getattr(dev, "category", "")) != "sensor":
            return
        touched = set(props or ()) & SENSOR_CODES
        with self._lock:
            if not touched:
                prev = self._sensors.get(dev.id)
                if prev is None:
                    self._store_sensor(dev, None, from_cache=True)
                else:
                    prev["name"] = clean_name(dev.name or "") or dev.name  # nameUpdate / online / offline
                return
            entry = dict(self._store_sensor(dev, timestamps, touched=touched))
        hook = self.on_sensor_update
        if hook is not None:
            try:
                hook(entry)
            except Exception:  # noqa: BLE001
                log.exception("on_sensor_update a esuat")

    def _drop_sensor(self, device_id: str) -> None:
        with self._lock:
            self._sensors.pop(device_id, None)

    def sensors(self) -> list:
        with self._lock:
            return sorted((dict(s) for s in self._sensors.values()), key=lambda s: s["name"])

    # ------------------------------------------------------------------- stare --
    def _mqtt_connected(self) -> bool:
        try:
            client = getattr(getattr(self._manager, "mq", None), "client", None)
            return bool(client is not None and client.is_connected())
        except Exception:  # noqa: BLE001
            return False

    def state(self) -> State:
        if self._load_session() is None:
            return "no_session"
        if self._manager is None:
            return "disconnected"
        if self._mqtt_connected():
            return "ok"
        if self._last_refresh_ok is not None and self._clock() - self._last_refresh_ok <= OK_REFRESH_S:
            return "ok"
        return "disconnected"

    def check_health(self) -> None:
        """Notifica o singura data cand starea nu e `ok` de 30 min; reseteaza la revenire."""
        st = self.state()
        now = self._clock()
        if st != "disconnected":
            # "ok" -> revenire; "no_session" -> userul nu s-a logat inca, nu alarmam
            self._bad_since = None
            self._notified = False
            return
        if self._bad_since is None:
            self._bad_since = now
        if not self._notified and now - self._bad_since >= NOTIFY_AFTER_S:
            self._notified = True
            try:
                self._notify("CASA: cloud Tuya deconectat",
                             "Senzorii nu mai primesc date. Refa login: python -m casa login", "high")
            except Exception:  # noqa: BLE001
                log.exception("notificarea a esuat")

    # --------------------------------------------------------------- pornire MQTT --
    def _start_once(self) -> None:
        mgr = self._ensure_manager()
        self._refresh_cache()
        if not self._listening:
            mgr.add_device_listener(_DeviceListener(self))
            self._listening = True
        mgr.refresh_mq()
        self._mq_started = True
        self._last_msg = self._clock()

    def _tick(self) -> None:
        try:
            if self._load_session() is None:
                pass
            elif not self._mq_started:
                self._start_once()
            else:
                now = self._clock()
                if (now - self._last_msg >= QUIET_MQTT_S
                        and (self._last_refresh_try is None or now - self._last_refresh_try >= QUIET_MQTT_S)):
                    self._refresh_cache()
        except Exception as exc:  # noqa: BLE001
            log.warning("ciclu cloud esuat: %s", exc)
        self.check_health()

    def start(self) -> None:
        """Porneste (in fir daemon) managerul + MQTT; reincearca la fiecare 30 s pana reuseste."""
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="casa-cloud", daemon=True)
        self._thread.start()

    def _run(self) -> None:
        while not self._stop.is_set():
            self._tick()
            self._stop.wait(TICK_S)

    def stop(self) -> None:
        self._stop.set()
        mq = getattr(self._manager, "mq", None)
        if mq is not None:
            try:
                mq.stop()
            except Exception:  # noqa: BLE001
                pass
