"""Priza Tuya pe LAN: driver (tinytuya) si un worker (thread) care detine conexiunea.

Reguli: o singura conexiune TCP per priza, folosita DOAR din thread-ul workerului.
Comenzile (telefoane, programator) intra intr-o coada si sunt executate pe rand.
"""
import logging
import queue
import threading
import time
from concurrent.futures import Future, InvalidStateError
from dataclasses import dataclass
from typing import Callable, Optional, Protocol

from .devices import DeviceInfo

log = logging.getLogger("casa.plugs")


class PlugError(Exception):
    """Orice eroare de comunicatie cu priza."""


class PlugDriver(Protocol):
    def connect(self) -> None: ...
    def status(self) -> dict: ...
    def set_dp(self, dp: int, value) -> None: ...
    def close(self) -> None: ...


class TinyTuyaDriver:
    """Conexiune persistenta la o priza prin tinytuya (TCP 6668)."""

    def __init__(self, dev_id: str, ip: Optional[str], local_key: Optional[str],
                 version: Optional[str]):
        self.dev_id = dev_id
        self.ip = ip
        self.local_key = local_key
        self.version = version
        self._dev = None

    def connect(self) -> None:
        self.close()
        if not (self.ip and self.local_key and self.version):
            raise PlugError("lipsesc ip/cheie/versiune")
        try:
            import tinytuya
            dev = tinytuya.OutletDevice(
                self.dev_id, address=self.ip, local_key=self.local_key,
                version=float(self.version), persist=True)
            dev.set_socketPersistent(True)
            dev.set_socketTimeout(5)
            dev.set_socketRetryLimit(1)
        except Exception as e:  # noqa: BLE001
            raise PlugError(f"init tinytuya: {e}") from e
        self._dev = dev

    def _need(self):
        if self._dev is None:
            raise PlugError("neconectat")
        return self._dev

    def status(self) -> dict:
        dev = self._need()
        try:
            result = dev.status()
        except Exception as e:  # noqa: BLE001
            raise PlugError(str(e)) from e
        if not isinstance(result, dict):
            raise PlugError("raspuns gol")
        dps = result.get("dps")
        if dps is None:
            raise PlugError(str(result.get("Error") or result.get("Err") or "raspuns fara dps"))
        return dps

    def set_dp(self, dp: int, value) -> None:
        dev = self._need()
        try:
            result = dev.set_value(dp, value)
        except Exception as e:  # noqa: BLE001
            raise PlugError(str(e)) from e
        if isinstance(result, dict) and (result.get("Error") or result.get("Err")):
            raise PlugError(str(result.get("Error") or result.get("Err")))

    def close(self) -> None:
        dev, self._dev = self._dev, None
        if dev is not None:
            try:
                dev.close()
            except Exception:  # noqa: BLE001
                pass


class FakeDriver:
    """Driver in memorie pentru teste si modul demo.

    `fail_next`: urmatoarele N apeluri (connect/status/set_dp) arunca PlugError.
    `fail_set`: urmatoarele N apeluri set_dp arunca PlugError.
    `calls`: jurnal [(nume, id thread)].
    """

    def __init__(self, dps: Optional[dict] = None):
        self.dps = dict(dps or {})
        self.fail_next = 0
        self.fail_set = 0
        self.calls: list = []
        self.connect_count = 0
        self.connected = False

    def _log(self, name: str) -> None:
        self.calls.append((name, threading.get_ident()))

    def _maybe_fail(self) -> None:
        if self.fail_next > 0:
            self.fail_next -= 1
            raise PlugError("eroare simulata")

    def connect(self) -> None:
        self._log("connect")
        self.connect_count += 1
        self._maybe_fail()
        self.connected = True

    def status(self) -> dict:
        self._log("status")
        self._maybe_fail()
        if not self.connected:
            raise PlugError("neconectat")
        return dict(self.dps)

    def set_dp(self, dp: int, value) -> None:
        self._log("set_dp")
        self._maybe_fail()
        if self.fail_set > 0:
            self.fail_set -= 1
            raise PlugError("set simulat esuat")
        if not self.connected:
            raise PlugError("neconectat")
        self.dps[str(dp)] = value

    def close(self) -> None:
        self._log("close")
        self.connected = False


@dataclass
class PlugReading:
    """Unitati: power in W, voltage in V, current in A (Tuya da cur_current in mA)."""
    online: bool
    on: Optional[bool]
    power: Optional[float]
    voltage: Optional[float]
    current: Optional[float]
    countdown_s: Optional[int]
    error: Optional[str]
    at: float


def decode_reading(device: DeviceInfo, dps: dict, at: Optional[float] = None) -> PlugReading:
    """Transforma dps brute in PlugReading, folosind harta DP/scale a dispozitivului."""
    def raw(code):
        dp = device.dps.get(code)
        return None if dp is None else dps.get(str(dp))

    def scaled(code):
        v = raw(code)
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            return None
        return v / 10 ** int(device.scales.get(code, 0))

    sw = raw("switch")
    cd = raw("countdown")
    return PlugReading(
        online=True,
        on=sw if isinstance(sw, bool) else None,
        power=scaled("power"),
        voltage=scaled("voltage"),
        current=None if scaled("current") is None else scaled("current") / 1000,
        countdown_s=int(cd) if isinstance(cd, (int, float)) and not isinstance(cd, bool) else None,
        error=None,
        at=time.time() if at is None else at,
    )


_STOP = object()


class PlugWorker(threading.Thread):
    """Un thread per priza: singurul care atinge driverul (conexiune, interogare, comenzi)."""

    def __init__(self, device: DeviceInfo, driver: PlugDriver, poll_s: float,
                 on_reading: Callable[[str, PlugReading], None], clock=time.monotonic,
                 backoff_base: float = 2.0, backoff_max: float = 60.0):
        super().__init__(name=f"plug-{device.id[:8]}", daemon=True)
        self.device = device
        self.driver = driver
        self.poll_s = poll_s
        self.on_reading = on_reading
        self.clock = clock
        self.backoff_base = backoff_base
        self.backoff_max = backoff_max
        self._q: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._closed = False
        self._connected = False
        self._backoff = backoff_base
        self._next_poll = 0.0

    # --- API apelat din alte thread-uri ---
    def submit(self, dp: int, value) -> Future:
        fut: Future = Future()
        with self._lock:
            if self._closed:
                fut.set_exception(PlugError("worker oprit"))
            else:
                self._q.put((dp, value, fut))
        return fut

    def stop(self) -> None:
        with self._lock:
            if not self._closed:
                self._q.put(_STOP)

    # --- thread-ul workerului ---
    def run(self) -> None:
        try:
            self._next_poll = self.clock()
            while True:
                wait = max(0.0, self._next_poll - self.clock())
                try:
                    item = self._q.get(timeout=wait)
                except queue.Empty:
                    item = None
                if item is _STOP:
                    break
                if item is not None:
                    self._command(*item)
                else:
                    self._cycle()
        finally:
            self._shutdown()

    def _shutdown(self) -> None:
        with self._lock:
            self._closed = True
        while True:
            try:
                item = self._q.get_nowait()
            except queue.Empty:
                break
            if item is _STOP:
                continue
            fut = item[2]
            try:
                if not fut.cancelled():
                    fut.set_exception(PlugError("worker oprit"))
            except InvalidStateError:
                pass  # anulat de apelant intre timp: nimic de rezolvat
        self._safe_close()

    def _safe_close(self) -> None:
        self._connected = False
        try:
            self.driver.close()
        except Exception:  # noqa: BLE001
            pass

    def _ensure_connected(self) -> None:
        if not self._connected:
            self.driver.connect()
            self._connected = True

    def _read_status(self) -> None:
        dps = self.driver.status()
        self._backoff = self.backoff_base
        self._next_poll = self.clock() + self.poll_s
        self._emit(decode_reading(self.device, dps))

    def _emit(self, reading: PlugReading) -> None:
        try:
            self.on_reading(self.device.id, reading)
        except Exception:  # noqa: BLE001
            log.exception("on_reading a esuat pentru %s", self.device.name)

    def _fail(self, err: Exception) -> None:
        """O incercare esuata: o singura citire offline, apoi retragere 2->4->...->60 s."""
        msg = str(err) or err.__class__.__name__
        self._safe_close()
        delay = self._backoff
        self._backoff = min(self.backoff_max, self._backoff * 2)
        self._next_poll = self.clock() + delay
        log.warning("priza %s offline: %s (reincerc in %.2f s)", self.device.name, msg, delay)
        self._emit(PlugReading(online=False, on=None, power=None, voltage=None,
                               current=None, countdown_s=None, error=msg, at=time.time()))

    def _cycle(self) -> None:
        try:
            self._ensure_connected()
            self._read_status()
        except Exception as e:  # noqa: BLE001
            self._fail(e)

    def _command(self, dp: int, value, fut: Future) -> None:
        if not fut.set_running_or_notify_cancel():
            return
        try:
            self._ensure_connected()  # offline: o incercare imediata, fara asteptarea retragerii
            self.driver.set_dp(dp, value)
        except Exception as e:  # noqa: BLE001
            err = e if isinstance(e, PlugError) else PlugError(str(e))
            fut.set_exception(err)
            self._fail(e)
            return
        fut.set_result(None)
        try:
            self._read_status()
        except Exception as e:  # noqa: BLE001
            self._fail(e)
