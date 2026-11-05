"""Acces: parola comuna inregistreaza un telefon si primeste un token propriu.

Pe disc (auth.json, mod 600) stau doar hash-ul parolei (scrypt + sare) si
sha256 al fiecarui token. Parola si tokenul in clar nu se scriu nicaieri.
"""
import hashlib
import hmac
import math
import secrets
import threading
import time
from collections import deque
from typing import Callable, Optional

from .store import JsonStore

MIN_PASSWORD_LEN = 8
MAX_DEVICE_LEN = 60
LAST_USED_STEP_S = 60

PASSWORD_IP_LIMIT = (5, 900)        # 5 greseli / 15 min / IP
PASSWORD_GLOBAL_LIMIT = (20, 3600)  # 20 greseli / ora, total
GLOBAL_KEY = "*"

_DEVICE_EXTRA = " .-_+()"
_DEFAULT = {"password": None, "phones": {}}


class BadPassword(Exception):
    """Parola gresita."""


class NoPassword(Exception):
    """Nu s-a setat nicio parola (python -m casa set-password)."""


class RateLimited(Exception):
    """Prea multe incercari; retry_after = secunde pana se elibereaza fereastra."""

    def __init__(self, retry_after: int):
        super().__init__(f"prea multe incercari, reincearca peste {retry_after} s")
        self.retry_after = retry_after


class RateLimiter:
    """Fereastra glisanta: cel mult `limit` lovituri in `window_s` secunde, per cheie."""

    def __init__(self, limit: int, window_s: float, clock: Callable[[], float] = time.time):
        self.limit = limit
        self.window_s = window_s
        self._clock = clock
        self._hits: dict[str, deque] = {}
        self._lock = threading.Lock()

    def _prune(self, key: str, now: float) -> deque:
        q = self._hits.get(key)
        if q is None:
            return deque()
        while q and now - q[0] >= self.window_s:
            q.popleft()
        if not q:
            del self._hits[key]
            return deque()
        return q

    def allowed(self, key: str) -> bool:
        with self._lock:
            return len(self._prune(key, self._clock())) < self.limit

    def hit(self, key: str) -> bool:
        """Inregistreaza o lovitura. True = permisa; False = limita atinsa (nu se inregistreaza)."""
        with self._lock:
            now = self._clock()
            q = self._prune(key, now)
            if len(q) >= self.limit:
                return False
            q.append(now)
            self._hits[key] = q
            return True

    def refund(self, key: str) -> None:
        """Anuleaza ultima lovitura inregistrata pentru `key` (rezervare care nu s-a consumat)."""
        with self._lock:
            q = self._hits.get(key)
            if q:
                q.pop()
                if not q:
                    del self._hits[key]

    def retry_after(self, key: str) -> int:
        """Secunde pana cand `key` are din nou loc (0 daca are deja)."""
        with self._lock:
            now = self._clock()
            q = self._prune(key, now)
            if len(q) < self.limit:
                return 0
            return max(1, math.ceil(q[0] + self.window_s - now))


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _scrypt(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)


def _clean_device(device: str) -> str:
    text = "".join(c for c in str(device) if c.isascii() and (c.isalnum() or c in _DEVICE_EXTRA))
    text = text.strip()[:MAX_DEVICE_LEN].strip()
    return text or "telefon"


class Auth:
    def __init__(self, store: JsonStore, clock: Callable[[], float] = time.time):
        self._store = store
        self._clock = clock
        self._ip_limiter = RateLimiter(*PASSWORD_IP_LIMIT, clock)
        self._global_limiter = RateLimiter(*PASSWORD_GLOBAL_LIMIT, clock)
        self._reserve_lock = threading.Lock()

    def set_password(self, password: str) -> None:
        if len(password) < MIN_PASSWORD_LEN:
            raise ValueError(f"parola trebuie sa aiba cel putin {MIN_PASSWORD_LEN} caractere")
        salt = secrets.token_bytes(16)
        entry = {"salt": salt.hex(), "hash": _scrypt(password, salt).hex()}

        def fn(data: dict) -> None:
            data["password"] = entry  # telefoanele inregistrate raman

        self._store.update(fn, _DEFAULT)

    def has_password(self) -> bool:
        return bool(self._store.load(_DEFAULT).get("password"))

    def _password_ok(self, password: str) -> bool:
        entry = self._store.load(_DEFAULT)["password"]
        expected = bytes.fromhex(entry["hash"])
        return hmac.compare_digest(_scrypt(password, bytes.fromhex(entry["salt"])), expected)

    def register(self, password: str, device: str, client_ip: str) -> tuple[str, str]:
        if not self.has_password():
            raise NoPassword()
        # Incercarea se rezerva INAINTE de scrypt (atomic) si se returneaza daca parola e buna:
        # incercari paralele nu pot trece toate de limita cat timp scrypt ruleaza.
        # Cat timp limitele sunt pline, nici parola corecta nu trece.
        with self._reserve_lock:
            blocked = max(self._ip_limiter.retry_after(client_ip),
                          self._global_limiter.retry_after(GLOBAL_KEY))
            if blocked:
                raise RateLimited(blocked)
            self._ip_limiter.hit(client_ip)
            self._global_limiter.hit(GLOBAL_KEY)
        try:
            ok = self._password_ok(password)
        except BaseException:
            self._refund(client_ip)
            raise
        if not ok:
            raise BadPassword()
        self._refund(client_ip)

        token = secrets.token_urlsafe(32)
        now = self._clock()
        out = {}

        def fn(data: dict) -> None:
            phones = data.setdefault("phones", {})
            ids = {p["phone_id"] for p in phones.values()}
            phone_id = secrets.token_hex(4)
            while phone_id in ids:
                phone_id = secrets.token_hex(4)
            phones[_sha256(token)] = {
                "phone_id": phone_id,
                "device": _clean_device(device),
                "created": now,
                "last_used": now,
            }
            out["phone_id"] = phone_id

        self._store.update(fn, _DEFAULT)
        return token, out["phone_id"]

    def _refund(self, client_ip: str) -> None:
        with self._reserve_lock:
            self._ip_limiter.refund(client_ip)
            self._global_limiter.refund(GLOBAL_KEY)

    def check(self, token: str) -> Optional[dict]:
        if not token:
            return None
        key = _sha256(token)
        phone = self._store.load(_DEFAULT).get("phones", {}).get(key)
        if phone is None:
            return None
        now = self._clock()
        if now - phone.get("last_used", 0) >= LAST_USED_STEP_S:
            def fn(data: dict) -> None:
                p = data.get("phones", {}).get(key)
                if p is not None:  # revocat intre timp: nu-l readucem
                    p["last_used"] = now

            self._store.update(fn, _DEFAULT)
            phone = dict(phone, last_used=now)
        return dict(phone)

    def phones(self) -> list[dict]:
        phones = self._store.load(_DEFAULT).get("phones", {}).values()
        return sorted((dict(p) for p in phones), key=lambda p: p["created"])

    def revoke(self, phone_id: str) -> bool:
        found = []

        def fn(data: dict) -> None:
            phones = data.get("phones", {})
            for key in [k for k, p in phones.items() if p["phone_id"] == phone_id]:
                del phones[key]
                found.append(key)

        self._store.update(fn, _DEFAULT)
        return bool(found)
