"""
Evidenta telefoanelor care cer date de la API: model, versiune Android, versiunea aplicatiei si cate cereri fac.

Aplicatia (de la v3.38) se prezinta in User-Agent ca `SolarMonitor/3.38 (42; Android 13; SM-G781B)`; versiunile mai
vechi trimit doar User-Agent-ul implicit `Dalvik/2.1.0 (Linux; U; Android 13; SM-G781B Build/...)`, deci modelul se
vede, versiunea nu. Cererile se numara in memorie si se scriu o data pe minut in InfluxDB (bucket `history`, masuratoarea
`app_client`), de unde le citeste tabelul „Telefoane si aplicatie" din Grafana. Altceva decat aplicatia (sonda
solar-watchdog cu curl, browsere) nu se numara.
"""
import re
import sys
import threading
import time

# Rutele pe care le cere aplicatia (Caddy taie prefixul /solar).
TRACKED_PATHS = {"/latest": "latest", "/bms": "bms", "/grid-month": "grid_month", "/history": "history"}
OLD_VERSION = "veche (≤3.37)"
FLUSH_INTERVAL_S = 60

_APP_UA = re.compile(r"^SolarMonitor/([\w.\-]+) \((\d+); Android ([\w.]+); ([^;)]+)\)")
_DALVIK_UA = re.compile(r"^Dalvik/[\d.]+ \(Linux; U; Android ([\w.]+); (.+?)(?: Build/|\))")


def _clean(value, limit=40):
    value = re.sub(r"[^\w .\-+()]", "", value or "").strip()
    return value[:limit] or "necunoscut"


def parse_user_agent(user_agent):
    """(model, android, versiune_app) pentru aplicatia noastra, None pentru orice alt client."""
    ua = user_agent or ""
    m = _APP_UA.match(ua)
    if m:
        version, code, android, model = m.groups()
        return _clean(model), _clean(android, 12), _clean(f"{version} ({code})", 20)
    m = _DALVIK_UA.match(ua)
    if m:
        android, model = m.groups()
        return _clean(model), _clean(android, 12), OLD_VERSION
    return None


class ClientCounter:
    """Numara cererile pe (model, android, versiune) si ruta; `drain()` le da si o ia de la zero."""

    def __init__(self):
        self._lock = threading.Lock()
        self._counts = {}

    def record(self, user_agent, path):
        route = TRACKED_PATHS.get(path)
        client = parse_user_agent(user_agent)
        if route is None or client is None:
            return
        with self._lock:
            per_route = self._counts.setdefault(client, {})
            per_route[route] = per_route.get(route, 0) + 1

    def drain(self):
        with self._lock:
            counts, self._counts = self._counts, {}
        return counts


def points(counts, timestamp_ns):
    """Punctele InfluxDB (line protocol ca dict) pentru un minut de cereri."""
    out = []
    for (model, android, version), per_route in sorted(counts.items()):
        fields = {route: float(n) for route, n in per_route.items()}
        fields["requests"] = float(sum(per_route.values()))
        out.append({
            "measurement": "app_client",
            "tags": {"model": model, "android": android, "app_version": version},
            "fields": fields,
            "time": timestamp_ns,
        })
    return out


def start_flusher(counter, write, interval_s=FLUSH_INTERVAL_S):
    """Fir de fundal: la fiecare interval scrie ce s-a numarat. O scriere ratata se pierde (e doar evidenta)."""

    def loop():
        while True:
            time.sleep(interval_s)
            counts = counter.drain()
            if not counts:
                continue
            try:
                write(points(counts, time.time_ns()))
            except Exception as e:  # InfluxDB jos: nu oprim API-ul pentru o statistica
                print(f"app_clients: nu pot scrie in InfluxDB: {e}", file=sys.stderr, flush=True)

    thread = threading.Thread(target=loop, name="app-clients", daemon=True)
    thread.start()
    return thread
