"""Priza Tasmota pe LAN: driver HTTP care se poarta ca o priza Tuya (aceleasi DP-uri sintetice).

Controllerul vorbeste doar in "DP-uri" Tuya; harta sintetica (`TASMOTA_DPS` din devices.py):
  1 = releu (bool)                 <-  `Power`            /  `Power On|Off`
  9 = secunde pana la oprire       <-  `PulseTime1` Remaining  /  `PulseTime1 <v>`
 18 = curent, mA                   <-  `Status 10` ENERGY.Current * 1000
 19 = putere, W * 10               <-  `Status 10` ENERGY.Power * 10
 20 = tensiune, V * 10             <-  `Status 10` ENERGY.Voltage * 10
 44 = inching, base64 (activ, s)   <-  `Mem1` (secunde) si `Mem2` (1/0)  /  scrie `Mem1`, `Mem2`

`PulseTime1` e singurul mecanism de oprire automata al prizei: se aplica la fiecare pornire a releului
si, scris cu releul pornit, reporneste numaratoarea. De aceea cu releul OPRIT trebuie sa fie mereu "baza"
(inching-ul prizei, 0 daca e dezactivat), ca butonul sa aplice inching-ul si cu serverul cazut; cat releul
e pornit, controllerul scrie in el cel mai apropiat termen de oprire (DP 9).

Parola (`WebPassword`) merge in URL: nicio eroare, niciun log si niciun mesaj de pe citirea offline nu au
voie sa contina URL-ul sau parola. Mesajele se construiesc din IP + tipul erorii, niciodata din `str()`-ul
exceptiei `urllib`, iar `PlugError` se ridica in afara blocului `except` (fara `__context__`).
"""
import http.client
import json
import logging
import math
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Optional

from .plugs import PlugError, decode_inching, encode_inching
from .store import JsonStore

log = logging.getLogger("casa.tasmota")

TASMOTA_MAX_S = 64800            # 18 h: cel mai lung termen pe care PulseTime il poate tine (64900 codificat)
PASSWORD_FILE = "tasmota.json"
MAX_RESPONSE_BYTES = 65536
_TENTHS_MAX = 111                # PulseTime 1..111 = zecimi de secunda
_LONG_OFFSET = 100               # PulseTime 112..64900 = valoare - 100 secunde
_MEM_NUMBER = re.compile(r"[0-9]{1,6}")


def encode_pulsetime(seconds: int) -> int:
    """Secunde -> valoarea `PulseTime1` (0 = oprit; 1..11 s = zecimi; peste = secunde + 100; plafon 18 h)."""
    s = max(0, min(int(seconds), TASMOTA_MAX_S))
    if s == 0:
        return 0
    if s * 10 <= _TENTHS_MAX:
        return s * 10
    return s + _LONG_OFFSET


def decode_pulsetime(value: int) -> int:
    """Valoarea `PulseTime1` (si `Remaining`) -> secunde, rotunjit in sus pentru zecimi."""
    v = int(value)
    if v <= 0:
        return 0
    if v <= _TENTHS_MAX:
        return -(-v // 10)
    return min(v - _LONG_OFFSET, TASMOTA_MAX_S)


def load_tasmota_password(data_dir: str) -> Optional[str]:
    """Parola `WebPassword` comuna prizelor Tasmota din `<data_dir>/tasmota.json`; None daca lipseste."""
    data = JsonStore(os.path.join(data_dir, PASSWORD_FILE)).load({})
    pw = data.get("password") if isinstance(data, dict) else None
    return pw if isinstance(pw, str) and pw.strip() else None


def save_tasmota_password(data_dir: str, password: str) -> None:
    if not isinstance(password, str) or not password.strip():
        raise ValueError("parola Tasmota nu poate fi goala")
    JsonStore(os.path.join(data_dir, PASSWORD_FILE)).save({"password": password})


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Parola e in URL: nu urmam redirectionari (spre alta gazda ar duce parola cu ele)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


# Fara proxy din mediu (parola n-are ce cauta la un proxy) si fara redirectionari.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect)


def _is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def _mem_text(v) -> Optional[str]:
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return str(v)
    return v.strip() if isinstance(v, str) else None


def _parse_mem(mem1, mem2) -> Optional[tuple]:
    """`Mem1`/`Mem2` -> (activ, secunde), sau None daca lipsesc/sunt gunoi (azi sunt `""` pe toate prizele)."""
    t1, t2 = _mem_text(mem1), _mem_text(mem2)
    if t1 is None or t2 not in ("0", "1") or not _MEM_NUMBER.fullmatch(t1):
        return None
    secs = int(t1)
    if not 1 <= secs <= TASMOTA_MAX_S:
        return None
    return t2 == "1", secs


def _parse_power(data: dict) -> bool:
    state = data.get("POWER", data.get("POWER1"))
    if state == "ON":
        return True
    if state == "OFF":
        return False
    raise PlugError("stare releu necunoscuta")


class TasmotaDriver:
    """Priza Tasmota prin HTTP (`http://<ip>/cm?cmnd=...`), fara conexiune persistenta.

    `ip` poate contine si port (`127.0.0.1:8080`, folosit de teste). `fetch(cmnd)` primeste textul comenzii
    (ex. "Status 10") si intoarce JSON-ul decodat; implicit face cererea HTTP. Apelat doar din thread-ul
    workerului: fara blocari.
    """

    def __init__(self, ip: str, password: Optional[str] = None, timeout: float = 3.0,
                 fetch: Optional[Callable[[str], dict]] = None):
        self.ip = ip
        self.timeout = timeout
        self._password = password or None
        self._fetch = fetch if fetch is not None else self._http_fetch
        self._mem: Optional[tuple] = None      # (activ, secunde) din Mem1/Mem2, None daca lipsesc/sunt invalide
        self._mem_loaded = False
        self._heal_failing = False

    # --- transport ---
    def _http_fetch(self, cmnd: str) -> dict:
        params = {"cmnd": cmnd}
        if self._password:
            params["user"] = "admin"
            params["password"] = self._password
        url = f"http://{self.ip}/cm?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
        problem = None
        body = b""
        data = None
        try:
            with _OPENER.open(url, timeout=self.timeout) as resp:
                body = resp.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as e:
            e.close()
            problem = "parola Tasmota respinsa" if e.code in (401, 403) else f"HTTP {e.code}"
        except urllib.error.URLError as e:
            problem = self._network_problem(e.reason)
        except (OSError, http.client.HTTPException) as e:
            problem = self._network_problem(e)
        if problem is None:
            if len(body) > MAX_RESPONSE_BYTES:
                problem = "raspuns prea mare"
            else:
                try:
                    data = json.loads(body.decode("utf-8", errors="replace"))
                except ValueError:
                    problem = "raspuns invalid (nu e JSON)"
        if problem is not None:
            raise PlugError(f"priza {self.ip}: {problem}")
        return data

    @staticmethod
    def _network_problem(exc) -> str:
        if isinstance(exc, TimeoutError):
            return "timeout"
        if isinstance(exc, ConnectionRefusedError):
            return "conexiune refuzata"
        return f"eroare de retea ({type(exc).__name__})"

    def _cmd(self, cmnd: str) -> dict:
        """O comanda; PlugError daca transportul sau priza o resping. Niciodata text brut din exceptii."""
        failure = None
        try:
            data = self._fetch(cmnd)
        except PlugError:
            raise
        except Exception as e:  # noqa: BLE001
            failure = type(e).__name__
        if failure is not None:
            raise PlugError(f"priza {self.ip}: eroare {failure}")
        if not isinstance(data, dict):
            raise PlugError(f"priza {self.ip}: raspuns neasteptat")
        warning = data.get("WARNING")
        if warning is not None:
            if "password" in str(warning).lower():
                raise PlugError(f"priza {self.ip}: parola Tasmota respinsa")
            raise PlugError(f"priza {self.ip}: avertisment Tasmota")
        if data.get("Command") in ("Unknown", "Error"):
            raise PlugError(f"priza {self.ip}: comanda {cmnd.split(' ')[0]} respinsa ({data.get('Command')})")
        return data

    # --- Mem1/Mem2 (inching) ---
    def _load_mem(self) -> None:
        self._mem_loaded = False
        mem1 = self._cmd("Mem1").get("Mem1")
        mem2 = self._cmd("Mem2").get("Mem2")
        self._mem = _parse_mem(mem1, mem2)
        self._mem_loaded = True

    def _ensure_mem(self) -> None:
        if not self._mem_loaded:
            self._load_mem()

    def _base_s(self) -> int:
        """Durata inching-ului activ in secunde, 0 daca e dezactivat sau necunoscut."""
        return self._mem[1] if self._mem is not None and self._mem[0] else 0

    # --- PlugDriver ---
    def connect(self) -> None:
        self._load_mem()

    def close(self) -> None:
        self._mem = None
        self._mem_loaded = False

    def _read_power(self) -> bool:
        return _parse_power(self._cmd("Power"))

    def _read_energy(self) -> dict:
        sns = self._cmd("Status 10").get("StatusSNS")
        energy = sns.get("ENERGY") if isinstance(sns, dict) else None
        out: dict = {}
        if isinstance(energy, dict):
            for key, dp, mult in (("Current", "18", 1000), ("Power", "19", 10), ("Voltage", "20", 10)):
                v = energy.get(key)
                if _is_number(v):
                    out[dp] = int(round(v * mult))
        return out

    def status(self) -> dict:
        self._ensure_mem()
        on = self._read_power()
        dps: dict = {"1": on}
        dps.update(self._read_energy())
        pt = self._cmd("PulseTime1").get("PulseTime1")
        set_v = pt.get("Set") if isinstance(pt, dict) else None
        rem_v = pt.get("Remaining") if isinstance(pt, dict) else None
        if _is_number(rem_v):
            dps["9"] = decode_pulsetime(rem_v) if on else 0
        if self._mem is not None:
            dps["44"] = encode_inching(*self._mem)
            if not on and _is_number(set_v):
                self._self_heal(int(set_v))
        return dps

    def _self_heal(self, set_v: int) -> None:
        """Releu oprit: PulseTime1 trebuie sa fie baza. Un termen de siguranta ramas de la ultima pornire
        (sau de cand serverul a cazut) ar opri prea devreme urmatoarea pornire din buton."""
        want = encode_pulsetime(self._base_s())
        if set_v == want:
            return
        try:
            self._write_pulsetime(want)
        except PlugError as e:
            if not self._heal_failing:
                log.warning("priza %s: PulseTime1 nu a putut fi readus la baza: %s", self.ip, e)
            self._heal_failing = True
        else:
            self._heal_failing = False

    def _write_pulsetime(self, value: int) -> None:
        pt = self._cmd(f"PulseTime1 {value}").get("PulseTime1")
        if not isinstance(pt, dict):
            raise PlugError(f"priza {self.ip}: raspuns neasteptat la PulseTime1")
        got = pt.get("Set")
        if _is_number(got) and int(got) != value:
            raise PlugError(f"priza {self.ip}: PulseTime1 nu a fost aplicat")

    def set_dp(self, dp: int, value) -> None:
        if dp == 1:
            self._set_switch(value)
        elif dp == 9:
            self._set_countdown(value)
        elif dp == 44:
            self._set_inching(value)
        else:
            raise PlugError(f"priza {self.ip}: DP {dp} necunoscut")

    def _set_switch(self, value) -> None:
        if not isinstance(value, bool):
            raise PlugError(f"priza {self.ip}: valoare releu invalida")
        if _parse_power(self._cmd("Power On" if value else "Power Off")) is not value:
            raise PlugError(f"priza {self.ip}: releul nu a trecut in starea ceruta")

    def _set_countdown(self, value) -> None:
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise PlugError(f"priza {self.ip}: termen invalid")
        self._ensure_mem()
        self._write_pulsetime(encode_pulsetime(value if value > 0 else self._base_s()))

    def _set_inching(self, value) -> None:
        native = decode_inching(value)
        if native is None:
            raise PlugError(f"priza {self.ip}: inching invalid")
        enabled, secs = native
        if not 1 <= secs <= TASMOTA_MAX_S:
            raise PlugError(f"priza {self.ip}: durata inching in afara limitei")
        on = self._read_power()
        self._mem_loaded = False          # daca o scriere esueaza la jumatate, citirea urmatoare reia Mem1/Mem2
        for name, text in (("Mem1", str(secs)), ("Mem2", "1" if enabled else "0")):
            if _mem_text(self._cmd(f"{name} {text}").get(name)) != text:
                raise PlugError(f"priza {self.ip}: {name} nu a fost scris")
        self._mem = (enabled, secs)
        self._mem_loaded = True
        if not on:
            self._write_pulsetime(encode_pulsetime(secs if enabled else 0))
