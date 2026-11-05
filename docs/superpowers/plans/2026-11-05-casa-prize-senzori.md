# CASA (prize și senzori Tuya) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Control the user's 5 Tuya Wi-Fi plugs (on/off, daily schedules, countdown, inching) and read his 3 Tuya temperature/humidity sensors from the Solar Monitor app (new SISTEM | CASA page) and Grafana, safely from the internet.

**Architecture:** New Python service `solar-casa` (container, bridge network `br-solar`, internal port 8100, exposed by Caddy at `/solar/casa/*`). Plugs are driven locally with `tinytuya` (one persistent worker thread per plug); sensors come from the Tuya cloud through `tuya-device-sharing-sdk` (QR login, MQTT push). A controller thread runs schedules/countdown/inching and always writes the nearest "off" deadline into the plug's own countdown DP. Every route needs a per-phone bearer token obtained with a shared password. Data goes to InfluxDB (`casa_plug`, `casa_sensor`); the Android app gets a native Compose CASA page + detail screens.

**Tech Stack:** Python 3.12, Flask 3.0.3 + gunicorn 23 (1 worker, 8 threads), influxdb-client 1.44.0, tinytuya 1.20.0, tuya-device-sharing-sdk (latest on PyPI, pin exact), cryptography, qrcode, tzdata; Kotlin + Jetpack Compose (compose-bom 2024.09.00), `HttpURLConnection` + `org.json`; Grafana 11.3 JSON.

**Spec:** `docs/superpowers/specs/2026-11-05-casa-prize-senzori-design.md` (Romanian). Read it fully before any task. Also read `CLAUDE.md` and `docs/DEZVOLTARE.md`.

## Global Constraints

- **Never write to the inverter or the BMS.** Do not touch `collector/` or `bms/` logic. The Tuya plugs are the only thing this system commands.
- `solar-api` stays read-only; all CASA routes live in the new service.
- Every `/solar/casa/*` route except `POST /register` requires `Authorization: Bearer <token>`. No unauthenticated control, ever.
- Secrets (password, tokens, Tuya `local_key`, cloud session) never go to git, logs, ntfy or Telegram. Server stores only hashes of password/tokens. Files with keys/session: mode 600, inside the service data volume (`/data`, host dir `casa/data/`, gitignored).
- Timezone for all schedules: `Europe/Bucharest` (zoneinfo + `tzdata` pip package).
- Limits: max 6 schedule intervals per plug; countdown 1–1440 min; inching 1–720 min (app steps of 5 min); password attempts: 5 wrong per 15 min per client IP, 20 wrong per hour total → 429; commands ~1/s per token → 429.
- Influx field names in English; comments, docs, log messages and UI text in Romanian; Retro UI labels uppercase without diacritics.
- Retro UI: no vertical scrolling of the page; live values and click targets native Compose; any new polling loop uses `WhileVisibleEffect`, never a bare `LaunchedEffect` loop.
- Do not open firewalld ports. Plugs are reached by fixed IP on TCP 6668.
- `caddy/Caddyfile` has uncommitted changes belonging to another project (beci-cloud). Add only the casa route; stage only your hunk (`git add -p`); never commit `Caddyfile.bak-*`.
- Android release = `versionCode 44`, `versionName "3.40"`.
- Tests: Python `unittest` (no pytest), no network. Android: JUnit4 under `android/app/src/test/java/com/rolling7/solar/`.

Known devices (from passive LAN scan; device id = Tuya `gwId`):

| id | IP | protocol |
|---|---|---|
| `bf5cc0f2db15b9eab2a0zy` | 192.168.1.244 | 3.3 |
| `bf3bac46d1a367e845thx2` | 192.168.1.104 | 3.3 |
| `bf7b3c62e47bfd15fczfov` | 192.168.1.204 | 3.3 |
| `bfc30380ff8a29c0d88wcz` | 192.168.1.185 | 3.4 |
| `bf3897ad46cd66d32cww3j` | 192.168.1.158 | 3.4 |

Sensor "Temp BECI" id `bfd920a7665be19c4cjytc` (Wi-Fi battery, cloud only). Names and local keys come later from the QR login (user is asleep; real-device steps are deferred, see Task 15).

## Review Focus

1. **Plug unreachable at an edge** (Wi-Fi drop, plug unplugged): a "stop" must be retried until it succeeds, a "start" only until the interval ends; the error is visible in `GET /plugs` and the plug shows `online:false`. → tests in Task 5.
2. **DST days**: on 2026-03-29 02:00–03:00 does not exist, on 2026-10-25 02:00–03:00 happens twice; an edge at 02:30 must fire exactly once on both days, and an interval 22:00→06:00 must still work. → tests in Task 3.
3. **Three phones + scheduler hitting one plug at once**: all commands to a plug go through that plug's single worker queue; no second TCP connection is ever opened to the same plug. → test in Task 4.
4. **Token revoked / password changed while the app is open**: server answers 401, the app shows the password plate once and stops polling `/plugs` (no 401 loop). → tests in Task 8 (server) and Task 11 (`CasaAccessTest`).
5. **Sensor silent for hours / cloud session lost**: `/sensors` returns `updated` (epoch) and `cloud:"disconnected"`; the app shows "ACUM 6 H" in red / "CLOUD DECONECTAT", never a fresh-looking stale value. → tests in Task 6 and Task 10.

---

## File structure

```
casa/
  Dockerfile, requirements.txt
  casa/__init__.py
  casa/__main__.py      CLI: login | sync | set-password | phones | revoke | demo-devices
  casa/config.py        Config dataclass from env
  casa/store.py         JsonStore: atomic JSON files in /data
  casa/auth.py          password hash, tokens, phones, RateLimiter
  casa/schedule.py      pure time logic: intervals, edges, missed edges, off deadline
  casa/devices.py       device registry (devices.json + devices.local.json), DP mapping, name cleanup
  casa/plugs.py         PlugDriver protocol, TinyTuyaDriver, FakeDriver, PlugWorker thread
  casa/controller.py    Controller: plug state, commands, schedules/countdown/inching tick
  casa/cloud.py         CloudSession (sharing SDK): QR login, sync, sensor cache, session watchdog
  casa/influx.py        CasaInflux: write casa_plug/casa_sensor, history queries
  casa/notify.py        ntfy push helper
  casa/web.py           Flask app factory create_app(), routes, auth decorator
  tests/test_*.py       unittest, no network
  data/                 runtime volume (gitignored)
android/app/src/main/java/com/rolling7/solar/
  CasaModel.kt          data classes + JSON parsers + formatters (pure)
  CasaRepository.kt     HTTP for /solar/casa/* with token
  CasaAccess.kt         token storage + 401 handling state
  RetroCasa.kt          SISTEM|CASA selector, CASA page, plug rows, sensor plates, password plate
  RetroCasaDetail.kt    plug detail (D) and sensor detail (E) panels
  SimpleCasa.kt         Simple theme card + reuse of detail panels
grafana/dashboards/solar.json   new row "🏠 Casa: prize si senzori"
```

---

### Task 1: Service skeleton, config, store, container

**Files:**
- Create: `casa/Dockerfile`, `casa/requirements.txt`, `casa/casa/__init__.py`, `casa/casa/config.py`, `casa/casa/store.py`, `casa/tests/__init__.py`, `casa/tests/test_store.py`, `casa/data/.gitkeep`
- Modify: `.gitignore` (add `casa/data/*` and `!casa/data/.gitkeep`), `docker-compose.yml` (service `casa`), `.env.example` (casa vars, no secrets)

**Interfaces:**
- Produces: `Config.from_env() -> Config` with fields `data_dir: str` (env `CASA_DATA_DIR`, default `/data`), `influx_url, influx_token, influx_org, bucket_live, bucket_history` (same env names as `api`), `ntfy_url: str` (`NTFY_URL`), `tz: str = "Europe/Bucharest"`, `poll_s: float` (`CASA_POLL_S`, default 5), `demo: bool` (`CASA_DEMO`, default false).
- Produces: `JsonStore(path: str)` with `load(default: dict) -> dict`, `save(data: dict, mode: int = 0o600) -> None` (write temp file in same dir, fsync, `os.replace`, chmod), `update(fn: Callable[[dict], None], default: dict) -> dict` (load→fn→save under a `threading.Lock`).

- [ ] **Step 1: Write failing tests** `test_store.py`: `test_save_then_load_roundtrip`, `test_missing_file_returns_default_copy` (mutating the result does not change the default), `test_save_is_atomic_and_mode_600` (after save, no `*.tmp` left, `stat.S_IMODE == 0o600`), `test_corrupt_file_raises_value_error` (garbage JSON → `ValueError` mentioning the path; never silently resets schedules), `test_update_applies_function`.
- [ ] **Step 2: Run** `docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src python:3.12-slim python -m unittest discover -s tests -v` → FAIL (module missing).
- [ ] **Step 3: Implement** `config.py`, `store.py`.
- [ ] **Step 4: Run tests** → PASS.
- [ ] **Step 5: Dockerfile + requirements + compose.** `requirements.txt` pins: `flask==3.0.3`, `gunicorn==23.0.0`, `influxdb-client==1.44.0`, `tinytuya==1.20.0`, `tuya-device-sharing-sdk==<latest, check pip index>`, `cryptography` (pin current), `qrcode==<current>`, `tzdata` (pin current). Dockerfile: `python:3.12-slim`, `WORKDIR /app`, install requirements, `COPY casa ./casa`, `CMD ["gunicorn","-b","0.0.0.0:8100","-w","1","--threads","8","--access-logfile","-","casa.web:create_app()"]` (Task 8 creates `web.py`; until then build only, do not start). Compose service `casa`: `build: ./casa`, `container_name: solar-casa`, `restart: unless-stopped`, `env_file: ./.env`, `environment:` INFLUX_* like `api` plus `CASA_DATA_DIR: /data`, `volumes: - ./casa/data:/data`, `depends_on: influxdb: condition: service_healthy`, no `ports:`. Add a test image build step for later tasks: tests run inside the built image: `docker compose build casa && docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src --entrypoint python solar-monitor-casa -m unittest discover -s tests -v`.
- [ ] **Step 6: Verify** `docker compose build casa` succeeds and the test command above passes inside the image.
- [ ] **Step 7: Commit** `feat(casa): schelet serviciu, config, store atomic`.

### Task 2: Auth: password, tokens, phones, rate limit, CLI

**Files:**
- Create: `casa/casa/auth.py`, `casa/casa/__main__.py` (subcommands `set-password`, `phones`, `revoke` now; others added later), `casa/tests/test_auth.py`

**Interfaces:**
- Consumes: `JsonStore` (file `/data/auth.json`).
- Produces: `class Auth(store: JsonStore, clock: Callable[[], float] = time.time)`:
  - `set_password(password: str) -> None` (reject < 8 chars with `ValueError`; hash with `hashlib.scrypt(n=2**14, r=8, p=1, dklen=32)` + 16-byte random salt; keeps existing phones).
  - `has_password() -> bool`
  - `register(password: str, device: str, client_ip: str) -> tuple[str, str]` returns `(token, phone_id)`; raises `RateLimited` or `BadPassword` or `NoPassword`. Token = `secrets.token_urlsafe(32)`; stored as sha256 hex with `{phone_id (8 hex), device (max 60 chars, sanitized), created, last_used}`.
  - `check(token: str) -> dict | None` (constant-time compare via dict lookup on sha256; updates `last_used` at most once per minute).
  - `phones() -> list[dict]`, `revoke(phone_id: str) -> bool`.
- Produces: `class RateLimiter(limit: int, window_s: float, clock)` with `hit(key: str) -> bool` (True = allowed) and `allowed(key) -> bool` (peek). Used for password (`5/900s` per IP, `20/3600s` key `"*"`) and commands (`1/1s` per phone, burst 3: implement as `limit=3, window_s=3`).
- CLI: `python -m casa set-password` (reads twice with `getpass`, never argv; `--stdin` reads one line from stdin for automation, e.g. a temporary test password piped in — still never argv), `python -m casa phones` (table: id, device, created, last_used), `python -m casa revoke <phone_id>`.

- [ ] **Step 1: Failing tests:** `test_register_with_right_password_returns_token_and_check_finds_phone`, `test_wrong_password_raises_bad_password`, `test_no_password_set_raises_no_password`, `test_sixth_wrong_attempt_from_same_ip_in_15_min_is_rate_limited` (5 wrong → 6th raises `RateLimited` even with the right password; after 900 s fake clock → allowed), `test_twenty_wrong_total_per_hour_limits_all_ips`, `test_revoke_makes_check_return_none`, `test_set_password_keeps_registered_phones`, `test_auth_file_contains_no_plain_password_or_token` (read raw file text; assert neither string appears), `test_short_password_rejected`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): acces cu parola, token per telefon, limitare incercari`.

### Task 3: Schedule logic (pure)

**Files:**
- Create: `casa/casa/schedule.py`, `casa/tests/test_schedule.py`

**Interfaces:**
- Produces:
  - `@dataclass(frozen=True) class Interval: id: str; start: str; stop: str; enabled: bool` (`"HH:MM"`, 24 h; `parse_hhmm(s) -> tuple[int,int]` raises `ValueError` on bad format; `start == stop` → `ValueError`).
  - `@dataclass(frozen=True) class Edge: interval_id: str; kind: Literal["start","stop"]; at: datetime` (aware, UTC).
  - `edges_between(intervals: list[Interval], t0: datetime, t1: datetime, tz: ZoneInfo) -> list[Edge]`: all edges of enabled intervals with `t0 < at <= t1`, sorted. Local wall time → UTC: for a non-existent local time (spring forward) use the instant right after the gap (`fold=0` then normalize: 02:30 → 03:30 local / same as 03:00 + 30 min is fine, just fire once); for an ambiguous time (fall back) use the first occurrence (`fold=0`) only.
  - `active_interval(intervals, now, tz) -> Interval | None`: an enabled interval whose window contains `now` (handles over-midnight).
  - `interval_end(interval, now, tz) -> datetime`: the stop instant of the window containing `now`.
  - `missed_edges(intervals, last_done: dict[str, dict[str, str]], now, tz, lookback: timedelta = timedelta(days=1)) -> list[Edge]`: edges in `(now - lookback, now]` whose ISO time is newer than `last_done[interval_id][kind]`. Caller applies the rules (stop always, start only if still inside the window).
  - `@dataclass class OffDeadline: at: datetime; reason: Literal["schedule","inching","countdown"]`; `nearest_off(candidates: list[OffDeadline]) -> OffDeadline | None` (earliest; ties: `countdown` > `schedule` > `inching` in reason priority for display only).

- [ ] **Step 1: Failing tests** (all with `ZoneInfo("Europe/Bucharest")`):
  - `test_daily_edges_in_one_day`: interval 10:30→16:00; `edges_between` 2026-11-05 00:00→23:59 local → start at 08:30Z, stop at 14:00Z.
  - `test_over_midnight_interval`: 22:00→06:00; `active_interval` at 23:00 and at 05:00 → the interval; at 12:00 → None; `interval_end` at 23:00 on 11-05 = 11-06 06:00 local.
  - `test_disabled_interval_has_no_edges`.
  - `test_spring_forward_edge_fires_once`: interval 02:30→07:00, window 2026-03-28 12:00 → 2026-03-29 12:00 local → exactly one start edge.
  - `test_fall_back_edge_fires_once`: interval 02:30→07:00, window 2026-10-24 12:00 → 2026-10-25 12:00 → exactly one start edge.
  - `test_missed_edges_skips_already_done`: last_done start = today 10:30 → only the stop edge is returned at 16:05.
  - `test_nearest_off_picks_earliest`.
  - `test_bad_hhmm_and_equal_start_stop_raise`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): logica programari (fronturi, DST, peste miezul noptii)`.

### Task 4: Devices, plug driver, plug worker

**Files:**
- Create: `casa/casa/devices.py`, `casa/casa/plugs.py`, `casa/tests/test_devices.py`, `casa/tests/test_plugs.py`

**Interfaces:**
- Produces in `devices.py`:
  - `@dataclass class DeviceInfo: id: str; name: str; kind: Literal["plug","sensor","other"]; local_key: str | None; ip: str | None; version: str | None; dps: dict[str, int]` where `dps` maps codes `switch`, `countdown`, `power`, `voltage`, `current` to DP numbers (missing = not supported); `scales: dict[str, int]` (decimal places, e.g. power 1 → value/10).
  - `load_devices(data_dir: str) -> list[DeviceInfo]`: reads `devices.json` (written by Task 6 sync; mode 600) and merges `devices.local.json` overrides (`{id: {ip, version, name}}`); built-in defaults for IP/version from the table in Global Constraints when missing.
  - `clean_name(raw: str) -> str`: strips time ranges like `"11:30 - 16:00"`, `"10.30 - 16.00"`, `": 11:00 - 16.25"` and trailing punctuation/spaces: `"Boiler  10.30 - 16.00"` → `"Boiler"`, `"Living sus: 11:00 - 16.25"` → `"Living sus"`, `"Priza cam Georgi"` unchanged.
  - `dp_map_from_strategy(local_strategy: dict) -> tuple[dict[str,int], dict[str,int]]`: from the sharing SDK `CustomerDevice.local_strategy` (`{dp_id: {"status_code": "switch_1"|"countdown_1"|"cur_power"|"cur_voltage"|"cur_current"|..., "config_item": {"valueDesc": "{\"scale\":1,...}"}}}`) build `dps` and `scales`. Codes: switch ← `switch_1`/`switch`; countdown ← `countdown_1`/`countdown`; power ← `cur_power`; voltage ← `cur_voltage`; current ← `cur_current`. Fallback when strategy is empty: `{switch:1, countdown:9, current:18, power:19, voltage:20}`, scales `{power:1, voltage:1, current:0}`; `metered` = `power` present AND a real reading was seen (Task 5 decides from data).
- Produces in `plugs.py`:
  - `class PlugDriver(Protocol)`: `connect() -> None`, `status() -> dict[str, object]` (raw dps `{"1": True, "19": 0, ...}`), `set_dp(dp: int, value) -> None`, `close() -> None`. Raises `PlugError` on any failure.
  - `class TinyTuyaDriver(PlugDriver)`: wraps `tinytuya.OutletDevice(dev_id, address=ip, local_key=key, version=float(version), persist=True)`, `set_socketTimeout(5)`, `set_socketRetryAttempts(1)`; `status()` returns `result["dps"]` or raises `PlugError(result.get("Error"))`; heartbeat every ~10 s handled by the worker via `status()` polling.
  - `class FakeDriver(PlugDriver)`: in-memory dps, `fail_next: int` to simulate errors, `calls: list` log; also used by demo mode.
  - `@dataclass class PlugReading: online: bool; on: bool | None; power: float | None; voltage: float | None; current: float | None; countdown_s: int | None; error: str | None; at: float`.
  - `class PlugWorker(threading.Thread)`: `__init__(device: DeviceInfo, driver: PlugDriver, poll_s: float, on_reading: Callable[[str, PlugReading], None], clock=time.monotonic)`; `submit(dp: int, value) -> Future[None]` (queue; executed by the worker thread only, then an immediate `status()`); `stop()`. Polls `status()` every `poll_s`; on error: `online=False`, close, reconnect with backoff 2→4→…→60 s; decodes dps through `device.dps/scales` into `PlugReading`.

- [ ] **Step 1: Failing tests** `test_devices.py`: `test_clean_name_cases` (the three examples above), `test_dp_map_from_strategy_reads_codes_and_scale`, `test_dp_map_fallback_when_empty`, `test_load_devices_merges_local_overrides_and_defaults`. `test_plugs.py` (FakeDriver, fast poll 0.01): `test_reading_decodes_power_with_scale` (dps 19=1650 with scale 1 → 165.0 W; the fake uses 16500 → 1650.0), `test_error_marks_offline_and_reconnects` (fail_next=2 → readings offline then online again), `test_submit_runs_on_worker_thread_only` (submit from 3 threads concurrently; FakeDriver records `threading.get_ident()` of every call → exactly one ident; never two `connect()` without `close()`), `test_submit_failure_sets_future_exception`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): registru dispozitive si worker local per priza (tinytuya)`.

### Task 5: Controller (state, commands, schedules, countdown, inching)

**Files:**
- Create: `casa/casa/controller.py`, `casa/tests/test_controller.py`

**Interfaces:**
- Consumes: `JsonStore` (`/data/state.json`), `schedule.*`, `PlugWorker`/`PlugReading`, `DeviceInfo`.
- State file shape (per plug id): `{"schedules": [{"id","start","stop","enabled"}], "countdown": {"action": "on"|"off", "at": epoch} | null, "inching": {"enabled": bool, "minutes": int}, "last_done": {interval_id: {"start": iso, "stop": iso}}, "started_by": interval_id | null}`.
- Produces: `class Controller(devices: list[DeviceInfo], workers: dict[str, PlugWorker-like], store: JsonStore, tz: ZoneInfo, clock: Callable[[], datetime])`:
  - `on_reading(plug_id, reading)` (worker callback; detects off→on transitions → inching and deadline rewrite; on→off clears `started_by` and in-progress off countdown).
  - `plug_view(plug_id) -> dict` / `plugs_view() -> list[dict]`: exact JSON shape of §"API shapes" below (without `energy_today`, added by web layer).
  - `switch(plug_id, on: bool) -> dict` (cancels pending countdown; returns view after the command completes or raises `CommandError`).
  - `set_countdown(plug_id, action, minutes) -> dict`, `cancel_countdown(plug_id) -> dict`.
  - `add_interval(plug_id, start, stop, enabled=True) -> dict` (max 6 → `ValueError`), `update_interval(plug_id, interval_id, **changes) -> dict`, `delete_interval(plug_id, interval_id) -> dict`.
  - `set_inching(plug_id, enabled, minutes) -> dict`.
  - `tick() -> None` called every 5 s by a thread started in `start()`: (1) due countdowns; (2) new schedule edges since last tick; (3) on first tick after start and on each worker reconnect: `missed_edges` with rules **stop always (if plug on and `started_by` == that interval, or plug on and no newer manual command), start only if `active_interval` is still that interval**; (4) for every plug that is on: compute `nearest_off` from {schedule end if `started_by`, inching (on-time + minutes), countdown "off"} and, if it differs from the last written value by > 5 s, write the plug countdown DP with the remaining seconds (`max(1, ...)`, capped at 86400); if the plug has no countdown DP, the controller switches it off itself when due.
  - Retries: a failed start edge is retried every 30 s while still inside the window; a failed stop edge is retried every 30 s with no limit; the last error string stays in `plug_view()["error"]` until a command succeeds.
  - Manual `switch(off)` while `started_by` is set: `started_by` = null, the interval's start is consumed (no re-start until the next day).

**API shapes (also used by Tasks 8, 10):**

```json
{"id":"bf…","name":"Boiler","online":true,"on":false,"metered":true,
 "power":0.0,"voltage":231.2,"current":0.0,"energy_today":2.4,
 "schedules":[{"id":"a1b2","start":"10:30","stop":"16:00","enabled":true}],
 "countdown":{"action":"on","at":1730800000,"seconds_left":18720},
 "inching":{"enabled":false,"minutes":30},
 "off_deadline":{"at":1730820000,"seconds_left":3600,"reason":"schedule"},
 "error":null}
```
`countdown`/`off_deadline` are `null` when absent; `power/voltage/current/energy_today` are `null` for non-metered plugs or unknown.

- [ ] **Step 1: Failing tests** (fake clock, fake workers that apply `submit` to a FakeDriver synchronously and call `on_reading`):
  - `test_schedule_start_and_stop_edges_switch_plug` (10:30 → on, 16:00 → off).
  - `test_manual_off_inside_interval_is_not_overridden` (on at 10:30, manual off 12:00, ticks until 15:59 → stays off; 16:00 no error).
  - `test_missed_stop_is_executed_after_long_outage` (plug on, started_by interval, controller restarted at 18:40 → switched off).
  - `test_missed_start_recovered_only_inside_window` (restart 11:00 → on; restart 16:30 → stays off).
  - `test_off_deadline_written_to_plug_countdown_dp` (scheduled start at 10:30 → DP 9 = 19800 s; with inching 30 min → DP 9 = 1800 and `off_deadline.reason == "inching"`).
  - `test_inching_triggers_on_external_switch_on` (reading flips on without a controller command → DP 9 = minutes*60).
  - `test_countdown_on_fires_and_persists_across_restart` (set 300 min, new Controller from same store 1 h later → fires at the original `at`).
  - `test_manual_switch_cancels_countdown`.
  - `test_unreachable_plug_stop_retried_until_success` (driver fails 10 times then succeeds → off; error visible meanwhile).
  - `test_start_retry_gives_up_at_interval_end`.
  - `test_max_six_intervals` and `test_bad_inching_and_countdown_values_rejected` (0, 721, 1441).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): programator - programari, countdown, inching, fronturi ratate`.

### Task 6: Cloud session (QR login, sync, sensors)

**Files:**
- Create: `casa/casa/cloud.py`, `casa/casa/notify.py`, `casa/tests/test_cloud.py`
- Modify: `casa/casa/__main__.py` (subcommands `login`, `sync`)

**Interfaces:**
- First read the installed `tuya_sharing` package source inside the image (`python -c "import tuya_sharing, inspect; print(tuya_sharing.__file__)"`) and Home Assistant's `tuya/tuya-smart-life` integration (`config_flow.py`, `__init__.py` on GitHub) for the exact flow: `LoginControl().qr_code(client_id, schema, user_code)` → QR content `tuyaSmart--qrLogin?token=<qrcode>`; poll `login_result(token, client_id, user_code)`; then `Manager(client_id, user_code, terminal_id, endpoint, token_response, mq_listener)`, `update_device_cache()`, `device_map`, `add_device_listener`, `refresh_mq()`, token refresh listener. Use the same `client_id`/`schema` constants as that integration. Write in the code a comment with the source/version you verified.
- Produces: `class CloudSession(data_dir: str, notify: Callable[[str,str,str],None], clock=time.time)`:
  - `login_interactive(user_code: str, out=sys.stdout) -> None`: prints the QR as ASCII (`qrcode` lib, `print_ascii(invert=True)`), waits up to 120 s, saves session to `/data/cloud_session.json` (600).
  - `sync() -> list[DeviceInfo]`: `update_device_cache()`, writes `/data/devices.json` (600) with id, raw name, cleaned name, category, `kind` (`cz`/`kg`/`pc` → plug; `wsdcg` → sensor), `local_key`, `local_strategy`-derived `dps/scales`; never prints keys (CLI prints a table: name, kind, id, ip-known yes/no, metered yes/no).
  - `start() -> None`: loads session, starts the manager + MQTT in a daemon thread; `sensors() -> list[dict]` returns `{"id","name","temperature","humidity","battery","updated"}` (temperature from `va_temperature` with its scale, humidity `va_humidity`, battery `battery_percentage` or mapped `battery_state` low/middle/high → 10/50/100; verify codes against the real device status in Task 15 and keep the mapping table at the top of the module).
  - `state() -> Literal["ok","disconnected","no_session"]`; when not `ok` for 30 min → `notify("CASA: cloud Tuya deconectat", "Senzorii nu mai primesc date. Refa login: python -m casa login", "high")` once until it recovers; if MQTT is quiet, `update_device_cache()` at most every 5 min.
  - `on_sensor_update: Callable[[dict], None]` hook used by Task 7 to write Influx.
- `notify.py`: `make_notify(ntfy_url: str) -> Callable[[title, message, priority], None]` (urllib POST, 5 s timeout, errors logged not raised; title ASCII-safe like the BMS reader).
- Demo: `python -m casa demo-devices` writes a `devices.json` with the 5 known ids, names (`Boiler`, `Living sus`, `Priza cam Georgi`, `Bucatarie`, `Lumina Curte`) and 3 sensors (`Temp Etaj`, `Temp afara`, `Temp BECI`) without keys, for `CASA_DEMO=1`.

- [ ] **Step 1: Failing tests** with a fake `Manager`/device objects (no network): `test_sync_writes_devices_json_mode_600_and_classifies_kinds`, `test_sync_cli_output_never_contains_local_key`, `test_sensor_values_scaled_and_battery_mapped`, `test_state_disconnected_notifies_once_after_30_min_and_again_after_recovery`, `test_sensors_report_updated_epoch_not_now` (a sensor last updated 6 h ago keeps that `updated`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): sesiune cloud Tuya (login QR, sync, senzori)`.

### Task 7: InfluxDB writes and history queries

**Files:**
- Create: `casa/casa/influx.py`, `casa/tests/test_influx.py`

**Interfaces:**
- Produces: `class CasaInflux(cfg: Config, client=None)`:
  - `write_plug(device: DeviceInfo, reading: PlugReading) -> None`: `live` point measurement `casa_plug`, tags `device_id`, `name`; float fields `on`, `online`, and `power`/`voltage`/`current` only when not None. A 60 s averager per plug writes the `history` mean (`on`/`online` = minimum over the minute; power/voltage/current mean). Write errors are logged and buffered (bounded deque 900 live / 1440 history), retried every 10 s; never raise into the worker.
  - `write_sensor(sensor: dict) -> None`: `casa_sensor` tags `device_id`, `name`; fields `temperature`, `humidity`, `battery` (when present) to **both** `live` and `history` at the sensor's `updated` timestamp (sensors report rarely; no averaging).
  - `sensor_history(device_id: str, range_: Literal["2h","1d","7d"]) -> dict`: 2h → `live`, window 2m; 1d → `live`, 10m; 7d → `history`, 1h; mean per window, `createEmpty:false`. Returns `{"temperature": [[epoch, v]...], "humidity": [[epoch, v]...], "stats": {"temp_min": {"v","t"}, "temp_max": {...}, "hum_min": {...}, "hum_max": {...}}}` with stats from raw points (not window means).
  - `sensor_spark(device_id) -> list[list[float]]`: last 2 h temperature, ≤ 24 points.
  - `energy_today(device_id) -> float | None`: integral of `power` from `live` since local midnight (Europe/Bucharest), kWh, 2 decimals; None if no samples.
- Bucket name validation: `range_` not in the three keys → `ValueError`.

- [ ] **Step 1: Failing tests** with a fake write API / fake query API capturing Flux text and returning canned tables: `test_plug_point_fields_and_tags`, `test_history_minute_mean_uses_min_for_on_online`, `test_sensor_written_to_both_buckets_at_updated_time`, `test_history_range_selects_bucket_and_window` (assert bucket + `every:` in the Flux string for each range), `test_unknown_range_raises`, `test_energy_today_starts_at_local_midnight` (Flux `start:` equals local midnight in UTC ISO on a fixed fake clock), `test_write_error_is_buffered_not_raised`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(casa): scriere InfluxDB (casa_plug, casa_sensor) si istoric senzori`.

### Task 8: HTTP API, wiring, Caddy, deploy

**Files:**
- Create: `casa/casa/web.py`, `casa/tests/test_web.py`
- Modify: `caddy/Caddyfile` (only add the casa block), `docker-compose.yml` (already has the service)

**Interfaces:**
- Produces: `create_app(services: Services | None = None) -> Flask`. `Services` bundles `auth, controller, cloud, influx` (tests inject fakes; production builds real ones from `Config.from_env()`, loads devices, starts one `PlugWorker` per plug with `TinyTuyaDriver` — or `FakeDriver` when `CASA_DEMO=1` — starts `Controller.start()` and `CloudSession.start()`. Wiring: each worker's `on_reading` → `controller.on_reading` and `influx.write_plug`; `cloud.on_sensor_update` → `influx.write_sensor`. **Demo mode (`CASA_DEMO=1`)**: FakeDriver plugs (Boiler/Living sus/Priza cam Georgi metered, Bucatarie/Lumina Curte not), fixed demo sensors (`updated` = now − 4 min), synthetic sensor history/spark/energy; **no InfluxDB writes and no cloud** in demo mode (production data must not get fake points).
- Routes (prefix stripped by Caddy; all JSON; all except `/register` and `/health` behind `@require_token`, which also enforces the command rate limit on non-GET):
  - `GET /health` → `{"ok": true}` (no secrets, no auth; used by compose/watchdog only).
  - `POST /register` `{password, device}` → 200 `{"token","phone_id"}`; 401 `{"error":"parola gresita"}`; 429 `{"error":"prea multe incercari","retry_after":s}`; 503 `{"error":"parola nesetata"}`. Client IP from `X-Forwarded-For` first entry when the request comes from the Caddy container network, else `remote_addr`.
  - `GET /me` → `{"phone_id","device"}`.
  - `GET /plugs` → `{"plugs":[plug view + "energy_today"], "timestamp": epoch}`.
  - `POST /plugs/<id>/switch` `{on: bool}`; `PUT /plugs/<id>/countdown` `{action, minutes}`; `DELETE /plugs/<id>/countdown`; `POST /plugs/<id>/schedules` `{start, stop, enabled?}`; `PUT /plugs/<id>/schedules/<sid>` (any of start/stop/enabled); `DELETE /plugs/<id>/schedules/<sid>`; `PUT /plugs/<id>/inching` `{enabled, minutes}` → each returns the updated plug view (200), 400 `{"error": "..."}` on validation, 404 unknown id, 502 `{"error": "priza nu raspunde", "plug": view}` on `CommandError`.
  - `GET /sensors` → `{"cloud": state, "sensors": [{id,name,temperature,humidity,battery,updated,spark}]}`.
  - `GET /sensors/<id>/history?range=2h|1d|7d` → `{"id","name","range", ...sensor_history}`; 400 on bad range.
- Caddy: inside `vyra.go.ro { ... }`, **before** `handle_path /solar/* {`, add:
  ```
  	# CASA: prize si senzori Tuya (serviciul solar-casa). Autentificarea o face serviciul (token per telefon).
  	handle_path /solar/casa/* {
  		reverse_proxy casa:8100
  	}
  ```

- [ ] **Step 1: Failing tests** (`app.test_client()`, fake services): `test_every_route_without_token_is_401` (iterate over `app.url_map`, skipping `/register` and `/health`), `test_register_flow_and_me`, `test_register_rate_limited_returns_429_with_retry_after`, `test_revoked_token_gets_401`, `test_switch_returns_view_and_502_on_command_error`, `test_validation_errors_are_400` (bad HH:MM, minutes 0/1441, unknown range), `test_command_rate_limit_429`, `test_sensors_shape`, `test_unknown_plug_404`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS (full casa suite).
- [ ] **Step 5: Deploy in demo mode** (no real keys yet): `docker compose run --rm casa python -m casa demo-devices`; add `CASA_DEMO=1` to `.env` (not committed); `docker compose up -d --build casa`; Caddy: `docker exec solar-caddy caddy validate --config /etc/caddy/Caddyfile` then `docker exec solar-caddy caddy reload --config /etc/caddy/Caddyfile`.
- [ ] **Step 6: Verify through Caddy:** `curl -sk --resolve vyra.go.ro:9443:127.0.0.1 https://vyra.go.ro:9443/solar/casa/plugs -o /dev/null -w '%{http_code}'` → `401`; `/solar/casa/health` → `{"ok":true}`; `/solar/latest` still 200 and Grafana `/` still 200/302; `/beci-cloud/` still answers as before (status code unchanged from a check taken before the reload).
- [ ] **Step 7: Commit** only `casa/` and the casa hunk of `caddy/Caddyfile` (`git add -p caddy/Caddyfile`), plus `docker-compose.yml`, `.gitignore`, `.env.example`: `feat(casa): API HTTP cu token, ruta Caddy /solar/casa, mod demo`.

### Task 9: Grafana row

**Files:**
- Modify: `grafana/dashboards/solar.json`

- [ ] **Step 1:** Add row id 142 "🏠 Casa: prize si senzori" at `gridPos {h:1,w:24,x:0,y:99}`; panels 143 "Putere prize (W)" time series (`casa_plug.power` by `name`, bucket `live`, `aggregateWindow(every: v.windowPeriod, fn: mean)`), 144 "Prize pornite" state timeline (`on` by `name`, value mappings 0=OPRIT 1=PORNIT, colour mode `palette-classic`), 145 "Temperatura senzori (°C)", 146 "Umiditate senzori (%)" (bucket `history` when range > 2 days: use `if` on `v.timeRangeStart` like existing history panels, or simply `history` bucket), 147 stat "Senzori acum" (last temperature per sensor). Datasource `{"type":"influxdb","uid":"influxdb"}`. Bump dashboard `version`.
- [ ] **Step 2: Verify** JSON valid (`python3 -m json.tool`), then `curl -s -u admin:<from .env> http://localhost:3000/api/dashboards/uid/solar-main | python3 -c` check that the 6 new panel ids exist (wait 30 s for the provider). Use the token/password only from `.env`, never print it.
- [ ] **Step 3: Commit** `feat(grafana): rand Casa - prize si senzori`.

### Task 10: Android model, repository, access

**Files:**
- Create: `android/.../CasaModel.kt`, `android/.../CasaRepository.kt`, `android/.../CasaAccess.kt`, `android/app/src/test/java/com/rolling7/solar/CasaModelTest.kt`, `CasaAccessTest.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` + new `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` excluding the `casa_access` shared prefs from backup.

**Interfaces:**
- Produces in `CasaModel.kt`:
  - `data class CasaSchedule(val id: String, val start: String, val stop: String, val enabled: Boolean)`
  - `data class CasaCountdown(val action: String, val at: Long, val secondsLeft: Long)`
  - `data class CasaOffDeadline(val at: Long, val secondsLeft: Long, val reason: String)`
  - `data class CasaPlug(val id: String, val name: String, val online: Boolean, val on: Boolean, val metered: Boolean, val power: Double?, val voltage: Double?, val current: Double?, val energyToday: Double?, val schedules: List<CasaSchedule>, val countdown: CasaCountdown?, val inchingEnabled: Boolean, val inchingMinutes: Int, val offDeadline: CasaOffDeadline?, val error: String?)`
  - `data class CasaSensor(val id: String, val name: String, val temperature: Double?, val humidity: Double?, val battery: Double?, val updated: Long?, val spark: List<Pair<Long, Double>>)`
  - `data class CasaSensors(val cloudOk: Boolean, val sensors: List<CasaSensor>)`
  - `data class CasaSensorHistory(val range: String, val temperature: List<Pair<Long, Double>>, val humidity: List<Pair<Long, Double>>, val tempMin: Pair<Double, Long>?, val tempMax: Pair<Double, Long>?, val humMin: Pair<Double, Long>?, val humMax: Pair<Double, Long>?)`
  - `fun parsePlugs(json: String): List<CasaPlug>`, `fun parsePlug(json: String): CasaPlug`, `fun parseSensors(json: String): CasaSensors`, `fun parseSensorHistory(json: String): CasaSensorHistory`.
  - `fun formatDuration(seconds: Long): String` → `"5H 12"` (≥ 1 h), `"23 MIN"` (< 1 h), `"<1 MIN"`.
  - `fun formatAge(nowEpoch: Long, updated: Long?): String` → `"ACUM 4 MIN"`, `"ACUM 6 H"`, `"FARA DATE"`; `fun isStale(nowEpoch: Long, updated: Long?): Boolean` (> 30 min).
  - `fun retroLabel(name: String): String` uppercase + strip diacritics (`ă→A, â→A, î→I, ș/ş→S, ț/ţ→T`).
  - `fun plugBadges(p: CasaPlug): List<String>` in display order: active schedules as `"10:30-16:00"`, countdown as `"PORNIRE IN 5H 12"` / `"OPRIRE IN 1H 40"`, inching as `"AUTO 30 MIN"`, off deadline (if not already shown) `"STINGE IN 23 MIN"`.
- Produces in `CasaRepository.kt` (`object CasaRepository`), base `https://vyra.go.ro:31443/solar/casa`, same User-Agent as `SolarRepository` (make `SolarRepository.userAgent` `internal`):
  - `sealed interface CasaResult<out T> { data class Ok<T>(val value: T); object Unauthorized; data class Error(val message: String) }`
  - `fun register(password: String, device: String): CasaResult<String>` (token), `fun plugs(token: String): CasaResult<List<CasaPlug>>`, `fun switch(token, id, on): CasaResult<CasaPlug>`, `fun setCountdown(token, id, action, minutes)`, `fun cancelCountdown(token, id)`, `fun addInterval(token, id, start, stop)`, `fun updateInterval(token, id, sid, enabled: Boolean? = null, start: String? = null, stop: String? = null)`, `fun deleteInterval(token, id, sid)`, `fun setInching(token, id, enabled, minutes)` — all `CasaResult<CasaPlug>`; `fun sensors(token): CasaResult<CasaSensors>`, `fun sensorHistory(token, id, range): CasaResult<CasaSensorHistory>`. 401 → `Unauthorized`; 429 → `Error("PREA MULTE INCERCARI")`; 502 → `Error("PRIZA NU RASPUNDE")`; network → `Error("FARA LEGATURA")`. Synchronous, called on `Dispatchers.IO`.
- Produces in `CasaAccess.kt`: `class CasaAccess(prefs: SharedPreferences)` with `token: String?`, `save(token)`, `forget()`, `fun onResult(r: CasaResult<*>)` (on `Unauthorized`: `forget()`); `fun casaAccess(context): CasaAccess` using prefs file `casa_access` (MODE_PRIVATE, excluded from backup).

- [ ] **Step 1: Failing tests** `CasaModelTest`: parse the exact JSON from Task 5 "API shapes" (nulls for countdown/off_deadline and for non-metered power), `formatDuration(18720) == "5H 12"`, `formatDuration(1380) == "23 MIN"`, `formatAge` cases incl. 6 h, `isStale` boundary 30 min, `retroLabel("Bucătărie") == "BUCATARIE"`, `plugBadges` order. `CasaAccessTest` (fake SharedPreferences map-backed): unauthorized result clears token; Ok keeps it.
- [ ] **Step 2: Run** `cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk /opt/gradle-8.9/bin/gradle -q :app:testDebugUnitTest` → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(android): model, client HTTP si token pentru CASA`.

### Task 11: Retro CASA page (mockup B)

**Files:**
- Create: `android/.../RetroCasa.kt`
- Modify: `android/.../RetroDashboard.kt` (SYSTEM branch → host with selector; `RetroSystemTopArtwork` gets the selector overlay), `android/.../MainActivity.kt` (state `rememberSaveable` `systemSub: "SISTEM"|"CASA"` next to `retroTabName`, pass down)

**Interfaces:**
- Consumes: Task 10 everything.
- Produces: `@Composable internal fun RetroSystemHost(data, bms, onEnergyFieldClick, sub: String, onSubChange: (String) -> Unit)`; `@Composable internal fun RetroCasaSelector(sub: String, onSubChange: (String) -> Unit, modifier: Modifier)` (two-position native plate "SISTEM | CASA", selected half lit green like mockup B, ≥ 48 dp touch height); `@Composable internal fun RetroCasaPage(sub, onSubChange, onOpenPlug: (String) -> Unit, onOpenSensor: (String) -> Unit)`; `@Composable internal fun RetroCasaPasswordPlate(onRegistered: () -> Unit)`.
- Behaviour:
  - SISTEM page unchanged except `RetroCasaSelector` overlaid on the empty area of the top plate (right of the painted "SISTEM" title, left of the painted LED; must not cover painted text; verify on screenshot).
  - CASA page = mockup B (`android/build/emulator-artifacts/design/mockups-prize/B.html` / `B-sistem-pagina-casa.png`), without the explanatory caption: native header plate (`RetroPanelSurface`) with "CASA", "PRIZE SI SENZORI", selector and clock; card "PRIZE · N PORNITE · X W" with 5 rows (LED, `retroLabel(name)`, `plugBadges`, VFD watts via `RetroVfdDisplay` or "---", `RetroSettingsAnimatedToggle`-style brass toggle); "SENZORI" with 3 plates (`retro_dashboard_dial_blank.webp`) showing temperature (segment digits), humidity, sparkline (Canvas, orange), `formatAge` (red when `isStale`). Cloud not ok → plates show "CLOUD DECONECTAT".
  - Toggle tap → optimistic state, `CasaRepository.switch` on IO; on `Error` revert + short red message in the card header for 4 s. Row tap (outside toggle) → `onOpenPlug(id)`. Sensor tap → `onOpenSensor(id)`.
  - Polling: `WhileVisibleEffect` while CASA visible: `/plugs` every 3 s, `/sensors` every 60 s; 20 s without a good `/plugs` → values become dashes and header shows "ASTEPT DATE". `Unauthorized` → `CasaAccess.forget()`, show `RetroCasaPasswordPlate`, stop polling.
  - No token → only `RetroCasaPasswordPlate`: "ACCES CASA", masked password field, button "INREGISTREAZA", errors from `CasaResult.Error`; on success save token, call `onRegistered`.
  - Offline plug: LED off, row at 45 % alpha, toggle disabled, badge "OFFLINE".
  - The whole page fits without vertical scroll on `note9-large`.
- [ ] **Step 1:** Implement composables; keep pure helpers in `CasaModel.kt` (tested there).
- [ ] **Step 2: Build + unit tests** (Task 10 command) → PASS.
- [ ] **Step 3: Emulator check** with the server in demo mode: generate a random temporary password (`python3 -c 'import secrets;print(secrets.token_urlsafe(12))'` into a shell variable, never echoed to logs), pipe it to `docker exec -i solar-casa python -m casa set-password --stdin`, register the emulator through the app UI (`adb shell input text`); after verification `python -m casa revoke <emulator phone_id>`. The user sets the real password in the morning (Task 15). Run `.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify`, then `layout-check`; take screenshots of SISTEM (selector visible, nothing else changed) and CASA; Read the PNGs; fix overlaps/clipping. Save as `android/build/emulator-artifacts/casa-*.png`.
- [ ] **Step 4: Commit** `feat(android): pagina CASA in SISTEM (tema Retro)`.

### Task 12: Detail screens D (plug) and E (sensor)

**Files:**
- Create: `android/.../RetroCasaDetail.kt`
- Modify: `android/.../RetroCasa.kt` (open/close state, `BackHandler`)

**Interfaces:**
- Produces: `@Composable internal fun RetroPlugDetail(plugId: String, onClose: () -> Unit)` and `@Composable internal fun RetroSensorDetail(sensorId: String, onClose: () -> Unit)`; both full panels over the tab content (bottom nav stays visible), closed by X or Back.
- D = mockup `D-detaliu-priza.png`: name, X, big toggle, VFD watts + "AZI x.x kWh" (metered only), COUNTDOWN (30M/1H/2H/6H/ALTA DURATA → numeric dialog hours+minutes ≤ 24 h; PORNESTE/OPRESTE choice, default = opposite of current state; running countdown line + ANULEAZA), PROGRAMARI (list, per-interval ACTIV/INACTIV toggle, long-press or small X to delete with confirm, "+ ADAUGA INTERVAL" opening two native `TimePicker` dialogs start/stop; hide the add button at 6), INCHING (toggle + −/+ in 5-min steps, 5–720). Show `off_deadline` as "OPRIRE IN …" under the toggle. All actions call the repository, replace the plug with the returned view, show errors inline. Only the PROGRAMARI list may scroll inside its card if > 3 intervals; the panel itself does not scroll.
- E = mockup `E-detaliu-senzor.png`: name, X, temperature + humidity VFDs, `formatAge` + battery, PERIOADA 2H/1D/7D (default 1D), Canvas chart (temperature orange left axis, humidity blue right axis, time ticks, min/max markers), MIN/MAX plate with times. Loading/error states: "ISTORIC INDISPONIBIL".
- Polling while open: plug detail `/plugs` every 3 s (pick the plug); sensor detail fetches history on open and on range change only.
- [ ] **Step 1:** Implement. **Step 2:** Build + unit tests → PASS. **Step 3:** Emulator: open D and E (demo data), screenshot `casa-detaliu-priza.png`, `casa-detaliu-senzor.png` on `note9-large`, Read and fix. **Step 4: Commit** `feat(android): ecrane detaliu priza si senzor`.

### Task 13: Simple theme card and SETARI row

**Files:**
- Create: `android/.../SimpleCasa.kt`
- Modify: `android/.../MainActivity.kt` (`simpleSystemContent` adds the card at the top; SETARI Retro and Simple add row "ACCES CASA")

**Interfaces:**
- Produces: `@Composable internal fun SimpleCasaCard(onOpenPlug, onOpenSensor)` (list of plugs: name, W, Material switch; sensors: name, °C, %, age; same polling rules, inside `WhileVisibleEffect`); no token → inline password field. Taps open `RetroPlugDetail` / `RetroSensorDetail` as full-screen dialogs (functional parity; Retro styling accepted).
- SETARI row "ACCES CASA": text "TELEFON INREGISTRAT" / "NEINREGISTRAT"; button "UITA TELEFONUL" (clears local token only, with confirm). Retro row must follow the existing native-row style of "ALARMĂ BATERIE" / "MESAJ REȚEA ENEL" and fit without scroll (run `layout-check`).
- [ ] **Step 1:** Implement. **Step 2:** Build + tests. **Step 3:** Emulator: Simple theme SISTEM and both SETARI screenshots; Read. **Step 4: Commit** `feat(android): CASA in tema Simple si rand ACCES CASA in SETARI`.

### Task 14: Docs, version, release build

**Files:**
- Modify: `CLAUDE.md`, `GEMINI.md` (same content, Romanian), `README.md`, `docs/DEZVOLTARE.md` (vertical map row for `casa`, API contract lines for `/solar/casa/*`), `COPILOT_CONTEXT.md` (§13.78), `.env.example`, `android/app/build.gradle.kts` (44 / "3.40")

- [ ] **Step 1:** Docs: new container `solar-casa` (7 containers now), internal port 8100, Caddy route, CLI (`login`, `sync`, `set-password`, `phones`, `revoke`, `demo-devices`), data dir and what is secret, the READ-ONLY clarification (Tuya plugs yes; inverter/BMS never), how to run casa tests, the "delete SmartLife schedules once ours work" note, DHCP reservations for the 5 plugs.
- [ ] **Step 2:** Bump version; `scripts/build-android-release.sh`; check package/version with the script output.
- [ ] **Step 3:** Final full test run: casa suite, api suite (unchanged), Android unit tests, emulator `verify` + `layout-check`, `emulator-check.sh stop`.
- [ ] **Step 4: Commit** `docs: CASA - serviciul solar-casa, comenzi, reguli; release v3.40`. Do **not** send the APK on Telegram and do **not** merge to `main` yet: the user reviews in the morning (Task 15).

### Task 15: With the user (morning) — real devices

Not executed overnight. Checklist for the morning:
1. User: DHCP reservations for the 5 IPs.
2. `docker exec -it solar-casa python -m casa login` (user scans QR) → `python -m casa sync`; confirm names ↔ ids, which are metered, DP map, sensor status codes; fix `cloud.py` mapping if codes differ (with a test).
3. Remove `CASA_DEMO=1`, restart casa; check every plug online, power readings vs SmartLife.
4. User sets the real password (`! docker exec -it solar-casa python -m casa set-password`); revoke the emulator phone.
5. Live tests with the user's OK: switch Lumina Curte from the app; a 2-minute countdown; a schedule at +2 min; inching 1 min on Lumina Curte; check the plug countdown DP stops it with the service stopped.
6. Send APK v3.40 on Telegram (`scripts/send-android-release-telegram.sh`), install on 3 phones, register each.
7. User deletes the equivalent SmartLife schedules.
8. Merge `feat/casa-prize` to `main`, push.
