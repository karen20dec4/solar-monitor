# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

100% local, self-hosted monitoring for a **Growatt SPF 6000 ES Plus** off-grid inverter. It
replaces the Growatt cloud / ShinePhone app. A Python collector polls the inverter over Modbus
RTU and writes to InfluxDB; Grafana visualizes; ntfy pushes alerts to a phone. Everything runs in
Docker Compose on a small server in the basement (IP `192.168.1.199`).

## Where the rest of the docs are

`README.md` has the full documentation index. The two that matter most alongside this file:

- **`docs/DEZVOLTARE.md`** — the development guide: the vertical map of which file to touch for
  each layer (collector → InfluxDB → `api/app.py` → `SolarRepository.kt` → Compose → WebP
  artwork), how to add a feature end to end, the Android release + Telegram delivery procedure,
  and the end-of-task checklist. **Read it before the first code change.**
- **`GEMINI.md`** — same operating rules as this file, in Romanian, for the Gemini agent. If you
  change a rule, a command, a port or a container here, change it there too — they are meant to
  stay in sync. Note: the Antigravity CLI (`agy`) does **not** auto-inject `GEMINI.md` in
  `--print` mode (verified 2026-08-23), so any task prompt sent to it must tell it to read
  `GEMINI.md` and `docs/DEZVOLTARE.md` first.

## ⚠️ The one hard invariant: READ-ONLY

The official data-logger was removed because OTA firmware updates bricked the inverter three times
in two days. Therefore **the collector must NEVER write to the inverter.** The only Modbus call in
`collector.py` is `read_registers(..., functioncode=4)` (FC04 = read input registers). Do not add
any FC06/FC16 write, and do not add inverter *control* features (changing charge mode, source
priority, etc.) — this is deliberately unimplemented and gated behind an explicit human decision.
Treat any change that could write to the serial device as off-limits unless the user explicitly
asks and accepts the risk.

> **Decision log (2026-07-10):** on-demand charge control (an app button that would write charge/
> priority registers) was evaluated with the user and **declined** — the charge target is already
> 56V, charging is already solar-only, so writing them changes nothing, and the only real lever
> (source priority) isn't worth breaking the invariant. System stays 100% READ-ONLY. The verified
> FC03 holding-register setpoints (charge/float 56V, max charge 70A, SBU, solar-only) are documented
> in `COPILOT_CONTEXT.md` §13.14 — read via a one-off FC03 recon, which is a *read* and does not
> violate the invariant. Since 2026-10-26 the charge target is **seasonal**, set by hand on the inverter panel by the
> user: **57 V in winter** (in force now), **56 V in summer**. `BAT_SOC_FULL_V` in `.env` must follow it (see "SOC in the app").
>
> **Decision log (2026-07-18, topic CLOSED):** researched the SPF Modbus protocol (official docs +
> community implementations) for a "start charging battery now" command — **no such command exists**;
> the solar charger is an autonomous state machine with an internal, non-settable re-charge threshold
> (≈ float −2V). The only protocol-level workaround (writing holding reg 1 to switch output to
> utility so PV dedicates to the battery) was **rejected by the user** (grid consumption not worth
> ~0.5V). Final: no button, no writes, READ-ONLY stands. Full research in `COPILOT_CONTEXT.md` §13.17.
> Do not reopen without new information (e.g. new firmware with documented charge-control registers).
>
> **CASA (2026-11-05):** the new `solar-casa` service **does command** things: the user's five Tuya smart plugs
> (switch, countdown, schedules, inching) over the LAN. That is the only thing the system commands. It does not
> touch the inverter or the BMS, which stay READ-ONLY; the rule above is unchanged for them. Details in "CASA" below.
>
> **BMS reader (2026-10-22):** the battery's JK BMS is read over Bluetooth LE by `bms/jk_bms_reader.py`
> (see "BMS reader" below). It is **READ-ONLY** too: the only thing it may send are the data requests
> `0x96`/`0x97`. The JK protocol also has write commands (thresholds, balancer, charge/discharge MOSFETs,
> password) — never add them. `request_frame()` rejects every other command and a unit test enforces it.

## Architecture

```
Inverter --USB (/dev/growatt)--> collector.py (minimalmodbus, FC04, polls every 1s)
   |-> InfluxDB bucket `live`    (1s data,  48h retention)
   |-> InfluxDB bucket `history` (60s averages, 31d retention)
   |-> ntfy push (phone alerts on threshold breach / watchdog)
   `-> Grafana dashboard `solar-main` (live 1s + 30-day history)
```

Seven containers (`docker-compose.yml`): `solar-collector`, `solar-influxdb`, `solar-grafana`,
`solar-ntfy`, `solar-casa` (Tuya plugs and sensors, see "CASA" below; internal port 8100, no published port), plus `solar-api` (JSON micro-API for the Android app, read-only towards the data — its only write
is the `app_client` phone log, see "Phones and app versions"; no published port,
reached internally at `api:8000`) and `solar-caddy` (HTTPS reverse proxy, `tls internal`, routes
`/solar/casa/*` → casa, `/solar/*` → api and everything else → Grafana). All `restart: unless-stopped`, start on boot.

They share one bridge network whose interface name is **pinned to `br-solar`** via
`driver_opts: com.docker.network.bridge.name`. Do not remove that — the firewalld rule that keeps
containers able to talk to each other is bound to that interface name (see Gotchas).

- **InfluxDB**: org `casa`, Flux queries. `history` bucket is auto-created by the init env vars;
  the `live` bucket is created by `influxdb/init/10-create-live-bucket.sh` on first boot.
- **Grafana**: provisioned (no manual setup). Datasource and dashboard come from
  `grafana/provisioning/`; dashboard JSON lives in `grafana/dashboards/solar.json` (uid
  `solar-main`, set as the org home page). `${INFLUX_*}` vars are injected into provisioning.
- **udev** (`deploy/99-growatt.rules`, installed on the host): pins the inverter's serial port to a
  stable `/dev/growatt` symlink by USB vendor/serial, regardless of `/dev/ttyUSBx` index.

## Configuration — everything is in `.env`

`.env` (gitignored; see `.env.example`) holds **all** secrets, Modbus settings, alert thresholds,
and retentions. The collector gets the whole file via `env_file: ./.env`, so tuning an alert =
edit `.env` then `docker compose up -d collector` (no rebuild needed for env changes).

`REG_COUNT` must be **≥ the highest register index the code reads** (currently 88 → 91 is fine).
The collector reads registers `0..REG_COUNT-1` in one FC04 call. Lower it and `parse()` will
IndexError.

## Common commands

There is **no linter or CI**, and the infrastructure itself is iterated via Docker and InfluxDB
queries. Two unit-test suites do exist and must pass before a release:

```bash
# API (Flask is not on the host; the built API image has it)
docker run --rm --network none -v /opt/solar-monitor/api:/src:ro -w /src --entrypoint python \
  solar-monitor-api -m unittest
# Android (pure Kotlin logic: energy page selection, axis ticks, readings)
cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
  /opt/gradle-8.9/bin/gradle -q :app:testDebugUnitTest
```

```bash
# build + (re)start everything, or one service
docker compose up -d
docker compose up -d --build collector      # after editing collector.py
docker compose up -d collector              # after editing only .env (no rebuild)

docker compose ps                           # status
docker compose up -d --build casa           # after editing casa/ (CASA_* env changes: up -d casa)
docker compose logs -f collector            # collector logs (also: docker logs -f solar-collector)
docker compose down                         # stop

# query live data (e.g. battery voltage) — note: token is in .env
docker exec solar-influxdb influx query --org casa --token <INFLUXDB_TOKEN> \
  'from(bucket:"live") |> range(start:-30s) |> filter(fn:(r)=> r._field=="battery_voltage") |> last()'

# test an ntfy push
curl -d "test" -H "Title: test" -H "Priority: urgent" -H "Tags: zap" http://localhost:8088/Alerta_6Kw
```

### Android emulator

The host has a working, KVM-accelerated, headless Android emulator for UI verification:

- SDK: `/opt/android-sdk`; emulator `36.6.11`; platform-tools `37.0.0`.
- AVD: `SolarMonitor_API_34` (Pixel 6, Android 14/API 34, Google APIs x86_64, 1080x2400).
- AVD files: `/root/.android/avd/SolarMonitor_API_34.avd`.
- Project skill: `.codex/skills/solar-monitor-emulator/SKILL.md`.
- Generated screenshots, UI hierarchy and logcat are stored in the gitignored
  `android/build/emulator-artifacts/` directory.
- Stable headless renderer: `swangle`. The helper starts the AVD in the transient
  `solar-monitor-emulator.service`; do not use `swiftshader_indirect` on Emulator 36.6.11 because it has
  repeatedly crashed with `SIGSEGV` on this host.
- UI asset tooling: ImageMagick 7.1.1-43 and WebP 1.5.0. Active Photoshop exports are in
  `android/build/emulator-artifacts/design/optimized/text-display/`; run
  `scripts/audit-retro-ui-assets.sh --strict` before `scripts/prepare-retro-ui-assets.sh`.
  The four old large PNG sources were intentionally deleted; their finalized WebP resources stay tracked.

Run the complete build/install/render/crash check with:

```bash
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify
```

Useful subcommands are `doctor`, `start`, `wait`, `build`, `install`, `launch`, `screenshot`, `retro-tabs`,
`profile`, `layout-check`, `pinch`, `status` and `stop`. `verify` and `layout-check` apply the user's phone profile
(`note9-large`: Galaxy Note 9, 1080×2220, 420 dpi, three-button navigation, font 130%) by themselves;
`SOLAR_EMULATOR_PROFILE=none` skips it. The user will move to a Samsung S22 FE later and will send a screenshot.
`pinch` checks the two-finger zoom. CASA screens without touching the real server (the emulator has no casa token, and
none may be minted on the real server): build the debug APK with `-PcasaBase=http://10.0.2.2:18100` (debug-only BuildConfig
`CASA_BASE`; release always uses production) and run a throwaway demo container
(`docker run -d --rm --name casa-emu-demo -p 127.0.0.1:18100:8100 -e CASA_DEMO=1 -e CASA_DATA_DIR=/data -v <temp dir>:/data
solar-monitor-casa`, then `python -m casa demo-devices` and `set-password --stdin` with a throwaway password).
`src/debug/res/xml/network_security_config.xml` allows cleartext only to 10.0.2.2 and repeats the vyra.go.ro trust anchors
(keep it in step with the main one). `retro-tabs` captures all four Retro pages and rejects accidental scrolling. The
emulator is intentionally started on demand rather than as a boot service. A successful Gradle build is
not enough for UI work: inspect the captured PNG after `verify`.

The Retro UI is a fixed full-screen four-tab instrument panel. It must never gain vertical scrolling:
the page content fits above the fixed photo navigation bar. Keep photographic chassis assets decorative;
live values, the gauge needle, LEDs, semantics and click targets must remain native Compose and dynamic.

**Static labels are painted into the WebP, not rendered by Compose.** The tab names, the dial captions,
the ENERGIE section titles — those are pixels. (Exceptions on ENERGIE since v3.28: the range buttons, the
PUTERE/ENERGIE/TENSIUNE row and the line at the bottom are opaque native plates laid over old painted
text — `7d`/`30d`, `PRODUCȚIE ZILNICĂ`, `Ultima actualizare: ACUM`. Since v3.29 the old tab slot at the
top is gone from the asset itself (ImageMagick patch, `scripts/patch-retro-energy-top.py`), and the
painted green LED gets a recoloured orange/red overlay when live data is late. The ENERGIE subject row (CASA / PANOURI / BATERIE / RETEA, v3.32) is four blank plates patched into the asset by `scripts/patch-retro-energy-subjects.py`, with icons and names drawn by Compose. TABLOU's BMS plates reuse
`retro_dashboard_dial_blank.webp` — the old temperature plate with its painted "°C" erased by
`scripts/make-retro-blank-dial.py` — so units are Compose text.)
Changing the string in Kotlin
leaves the photo saying the old thing while the button does the new thing. Rule of thumb: if the text
does not appear inside a `Text(...)` call, it is in the artwork and needs a repaint (ideally a fresh
Photoshop export from the user). Coordinates and the check command are in `docs/DEZVOLTARE.md` §3.

**Deploy flow:** edit locally → `git push` → on the server `cd /opt/solar-monitor && git pull &&
docker compose up -d --build`. To debug the Modbus mapping live, set `DEBUG_RAW=1` in `.env`,
restart the collector, and inspect the raw `r{i}`/`s{i}` (signed) register fields in InfluxDB.

## How `collector.py` works

Single-file, single-threaded loop in `collector.py`. Per cycle: open Modbus instrument (lazily,
reconnect on failure) → one FC04 read of `REG_COUNT` registers → `parse(regs)` →
`BalanceDeriver.apply()` → evaluate alerts → queue a point for `live` and, every `HISTORY_INTERVAL`,
a point for `history` that is the **mean of the interval** (`HistoryAverager`; energy counters,
status codes and `battery_soc` keep the last value) → `InfluxSink.flush()`.

- **`parse(regs)`** is the heart and is stateless: maps raw registers to named float fields.
  `u32`/`s32` decode 32-bit register pairs (big-endian, two's complement). Battery power comes from
  the signed int32 **Bat_Watt at regs 77/78** (the code negates it so the field convention stays
  *positive = charging, negative = discharging*); charge/discharge currents are regs 83/84.
  Voltages/SOC/temp are single registers with the scalings shown inline.
- **`BalanceDeriver`** (stateful) computes everything that comes from the power balance
  `PV + discharge + grid→battery − load − charge`: `inverter_loss` (self-consumption, ~100–350 W
  depending on load), `power_deficit`, `grid_import_power`, the inferred battery discharge and from
  it `battery_support_power` / `battery_display_power`, and `house_source` (1=PV / 2=battery /
  3=grid). The balance is a residual, not a measurement: the inverter's power registers refresh
  every ~3–4 s out of phase, so the instantaneous value spikes 1–4000 W on load steps. The **signed
  balance goes through a 60 s rolling median** (`BALANCE_SMOOTH_S`) before any decision;
  instantaneous values stay in `inverter_loss_raw` / `power_deficit_raw` / `grid_import_power_raw`.
  Grid vs. battery for an unexplained deficit is decided from the inverter **status code** reg 0
  (`STATUS_BYPASS`/`STATUS_DISCHARGE`) plus grid voltage and battery-first mode. `house_source`
  only switches when the new source holds for `HOUSE_SOURCE_HOLD_S` (10 s), and routes
  `output_power` to exactly one of `house_pv`/`house_bat`/`house_grid` each cycle so the dashboard
  can show a single source-colored pill. With both knobs at 0 the output is identical to the old
  unfiltered logic (checked on 26 h of real data).
- **Bypass** (status 8–11, grid present): the house sits directly on the grid, the load does not pass through
  the inverter. The residual above would then be negative (loss 0, import too small by exactly the inverter's
  own draw). So in bypass: `inverter_loss` = median of the PV side without the load (`PV + discharge +
  grid→battery − charge`, own 60 s window, reset when bypass ends; if that is ≤ 0, the
  `INVERTER_LOSS_ESTIMATE_W` estimate with `inverter_loss_estimated=1`) and `grid_import_power` = `output_power`.
  Measured 2026-10-23: PV−charge ≈ 87 W, the same as the ~100 W the inverter eats at night on battery; grid
  import was 112 W against 207 W of load before this. `grid_power` = `grid_import_power` + AC charging of the
  battery (`grid_charge_power`): everything the system takes from the grid, W, averaged into `history`. There is
  no grid energy counter in the inverter, so the app's kWh bars integrate it (`energy_grid` in the API, 2h/1d
  from `live`, 7d/30d from `history`). History before 2026-10-23 09:53 UTC was filled once with the same formula
  (`grid_power`, bypass → `output_power`, otherwise `grid_import_power`, plus AC charge). Tests:
  `docker run --rm --network none -v /opt/solar-monitor/collector:/src:ro -w /src --entrypoint python
  solar-monitor-collector -m unittest`.
- **Alerts** (`build_alerts` / `eval_alerts`): list of dicts with `fire`/`clear` lambdas over the
  parsed data. Each alert has **debounce** (condition must hold `ALERT_DEBOUNCE_S`), **cooldown**
  (`ALERT_COOLDOWN_S` between re-alerts), and **hysteresis** (separate clear threshold); timing uses
  `time.monotonic()`. Covers high load, battery low (voltage only) / high (57.5 V), overheat, lost AC
  output. A separate **watchdog** fires after `WATCHDOG_FAILS` consecutive **Modbus** failures
  (inverter silent / USB unplugged).
- **`InfluxSink`**: InfluxDB errors are handled apart from Modbus errors. Points carry their own
  timestamp, wait in a bounded in-memory queue (`INFLUX_BUFFER_LIVE` ≈ 15 min,
  `INFLUX_BUFFER_HISTORY` ≈ 24 h) and are retried every `INFLUX_RETRY_S`. An InfluxDB outage no
  longer fires the "inverter not responding" watchdog or reopens the serial port; ntfy alerts keep
  working because they don't go through InfluxDB. 400/422 rejections are dropped, since retrying
  cannot fix them.
- **Do not use `battery_soc` (reg 18) in logic.** In 31 days it only took the values 25/50/75/100
  and read 100% overnight while the voltage fell 54 → 49 V (decision 2026-10-18). It is still
  recorded, and the app and Grafana still display it.

> Note on register docs: `COPILOT_CONTEXT.md` is a hand-maintained session log with the hardware
> details, access credentials' locations, and the discovery history of the register map. Parts of
> its register table are **stale** — e.g. it documents battery power via register 90, but the code
> moved to the official Bat_Watt registers 77/78 (commit "Use official battery watt registers").
> When they disagree, **`collector.py` is the source of truth.**

## BMS reader (`bms/`)

Battery BMS: JK `JK_BD6A20S12P` (hw 11U, sw 11.54), 14S pack. Read over Bluetooth LE from the host's USB
dongle (TP-Link UB500, Realtek RTL8761B; needs `firmware-realtek`) by `bms/jk_bms_reader.py` — a **host**
systemd service (`deploy/solar-bms.service`, installed in `/etc/systemd/system/`), not a container, because it
needs BlueZ and the dongle. Dependency: Debian `python3-bleak`. Address in `.env` as `BMS_ADDRESS`.

- Writes measurement `bms` (tag `device=jk_bms`, all fields float) to `live` (~1 s) and `history` (60 s mean;
  `soc`/cycles/capacity keep the last value, `charge_mosfet`/`discharge_mosfet` the **minimum** and
  `alarm_bits` the maximum, so a few seconds of disconnect stay visible). Fields: `cell_01..cell_14`,
  `cell_min/max/avg/delta`, `voltage`, `current`, `power`, `temp_mos/1/2`, `soc`, `soh`,
  `capacity_remaining_ah`, `capacity_nominal_ah`, `cycles`, `cycle_capacity_ah`, `charge_mosfet`,
  `discharge_mosfet`, `balancing`, `alarm_bits`.
- Sign: `current`/`power` **positive = charging, negative = discharging**, same as the inverter fields
  (checked against the inverter on 2026-10-22: -47.7 A vs 48.2 A discharge). The BMS reads ~0.5 V above the
  inverter at 48 A (cable/fuse loop ≈ 10 mΩ).
- Verified offsets (JK02_32S frame, 300 bytes): cells u16@6, enabled mask u32@70, MOS temp@144, voltage
  u32@150, current s32@158, T1@162, T2@164, SOC u8@173, remaining/nominal Ah@174/178, cycles@182,
  SOH@190, MOSFETs@198/199. `balancing` (u8@172, was 2 with a 16–19 mV delta) and the meaning of the bits in
  `alarm_bits` (u16@166, always 0 so far) are **not** verified yet.
- One BLE client at a time is assumed (untested): stop the service (`systemctl stop solar-bms`) before using
  the JK phone app, and start it again after. The phone app also needs the phone's location turned on.
- Alerts: rules and engine in `bms/bms_alerts.py` (debounce/cooldown/hysteresis like the collector):
  MOSFET off (battery disconnected), `alarm_bits` != 0, cell high/low, cell delta (60 s), temperature
  high/low, no BMS data for 3 min. Each sends an ntfy push on the same topic (from the host, via
  `127.0.0.1:8088`) and, while the condition holds, is written every second as measurement `bms_alert`
  (tag `key`; string fields `title`/`msg`/`prio`, `active=1.0`). `solar-api` serves it: `GET /solar/bms`
  returns the latest `bms` fields flat, `timestamp` (null without data in the last 15 s) and
  `alarms` (active alerts, i.e. written in the last 6 s). The phone app rings on `prio` urgent/high.
  Thresholds are tunable in `.env` (`BMS_CELL_HIGH_V`, `BMS_CELL_LOW_V`, `BMS_DELTA_HIGH_V`, `BMS_TEMP_HIGH`,
  `BMS_MOS_TEMP_HIGH`, `BMS_TEMP_LOW`, `BMS_LINK_LOST_S`, each with a `_CLR` clear value — see `.env.example`).
- Grafana: row "BMS baterie (JK)" (live: SOC, voltage, current, cell delta, temperature, MOSFET stats; cell
  voltages, delta, BMS-vs-inverter current, temperatures, MOSFET/balancing state timeline) and three BMS
  panels at the end of the history row. The state timeline must use `palette-classic` colours: in
  `thresholds` colour mode Grafana ignores the value mappings and prints "1+" / "< 1".
- The service stops cleanly (`systemctl stop solar-bms` disconnects from the BMS). A BMS left "connected" by
  a killed process stops advertising; the reader then falls back to connecting by address, and
  `bluetoothctl disconnect <addr>` clears it by hand.
- Phone app (v3.30, Retro + Simple themes): `GET /solar/bms` is polled every 2 s (`SolarRepository.fetchBms`,
  parser `BmsModel.kt`). TABLOU: BMS strip (SOC, current, cell delta + status LED) under Bat/Inv, tap opens
  SISTEM; the old "Temperatura" plate is gone and the inverter temperature is a small "29°C" under "Inv".
  SISTEM: `RetroBmsMonitor` replaces the old console (cell bars + figures). SETARI: native row "ALARMĂ
  BATERIE" with its own toggle (`AlarmSettings.batteryEnabled`, default off); `SolarAlarmService` runs while
  any alarm is on, polls `/latest` every 5 s and `/bms` every 10 s (v3.36; was 2 s / 4 s) and rings (same sound/vibration/cooldown as the consumption
  alarm) once per alarm with `prio` urgent/high (`BmsAlarmTracker`). Thresholds live on the server, not in the app.
- Polling (v3.36): the app's network loops (`/latest` and `/bms` every 2 s, `/grid-month` every 60 s, SISTEM's 1h
  history every 60 s) run in `WhileVisibleEffect` (`repeatOnLifecycle(STARTED)`), so they stop when the app is in the
  background or the screen is off; only `SolarAlarmService` keeps polling there. `MainActivity` is `singleTask` and the
  notification intent uses CLEAR_TOP|SINGLE_TOP. Before, the loops were plain `LaunchedEffect`s that never paused, and
  each notification tap stacked another `MainActivity` with its own loops: the api log showed ~1–3.5 req/s from one
  phone around the clock (2026-10-28). Any new polling loop must use `WhileVisibleEffect`, not `LaunchedEffect`.
- Audit fixes (v3.37): 20 s without fresh `/latest` (no reply, or `timestamp` null) sets `data = null`, so the
  screens show "ASTEPT DATE" / dashes instead of frozen numbers (TABLOU text turns red); the ENERGIE charts refresh
  quietly once a minute while visible; `AlarmBootReceiver` restarts the alarm service after a reboot and after an app
  update (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`; a shell-sent broadcast is refused by Android 14, a real reboot works);
  the service runs on the main thread (network on IO), a low house load stops only the consumption alarm's sound (it
  used to cut the battery alarm and the test sound after one poll), and it ignores `/latest` replies without a timestamp.
- Phones and app versions (v3.38, 2026-10-28): the app sends `User-Agent: SolarMonitor/<versionName> (<versionCode>;
  Android <release>; <Build.MODEL>)` (`SolarRepository.open`). `api/app_clients.py` counts app requests per phone (model,
  Android, app version; per route `latest`/`bms`/`grid_month`/`grid_outage`/`history` plus `requests`), and once a minute writes them to
  `history` as measurement `app_client` (tags `model`, `android`, `app_version`). Apps up to 3.37 only send the Dalvik
  agent, so they show as `veche (≤3.37)`; curl, Python and browsers are not counted. The API runs as **one** gunicorn
  worker with 8 threads, so one process owns the counts. Grafana: row "📱 Telefoane si aplicatie" at the bottom of
  `solar-main`, table "Telefoane cu acces si versiunea aplicatiei" (one row per model: Android, app version, last request,
  requests/min over 10 min, requests 24h). Phone names are value mappings on the model in that panel (SM-N960U = Note 9,
  the user's; SM-G781B = S20 FE, the user's wife's); add a mapping when a new phone shows up. Nothing in the app UI.
- Enel outage message (v3.39, 2026-11-02): a silent notification (channel `solar_grid`: no sound, no vibration) when the
  grid drops, replaced by "Curent Enel revenit" with the times and the duration when it returns. **The server remembers the
  outages, not the phone:** the house's internet falls with the grid, because the Digi router/media converter at the gate is powered from Enel, not from the inverter (2026-11-02: 2–4 min offline for outages of 35–40 s), so
  a phone comparing `/latest` would never see 0 V. `GET /solar/grid-outage` -> `{down, outages: [{start, end, seconds}],
  timestamp}` (outages that ended in the last 24 h, read from 48 h of `live` so a start never slides with the window, epoch
  seconds, `end` null while it lasts, `grid_voltage` < 100 V, ≥ 3 s, samples ≤ 180 s apart are one outage; fixed 2026-11-07:
  a 24 h read made an expiring outage look new at each poll, 2–3 extra "revenit", and a 72 s return gave two messages). `SolarAlarmService` polls it every 30 s and `evaluateGridOutages` (`GridOutage.kt`) decides what is
  new against a mark kept in prefs; the first run stays quiet about the past. So the message can arrive minutes late, but
  says the real times. SETARI: row "MESAJ REȚEA ENEL", `AlarmSettings.gridEnabled`, **default on**, so the foreground
  service now runs on every phone (with only this on it loops every 30 s, not 5 s) and the app asks for the notification
  permission at launch. Dashboard: `SolarData.gridDown` from `/latest`: TABLOU shows a blinking red X over the tower and
  "ENEL PICAT" instead of the watts (`RetroGridDownPlate`), Simple shows "ENEL PICAT" under the pole, SISTEM on the grid line.
  It is only visible when the phone reaches the server during the outage.
- ENERGIE (v3.32): fourth subject RETEA, power (`grid_power`, W) and energy (`energy_grid`, kWh bars), 2h/1d/7d like the others; red accent. kWh under 1 shows two decimals.
- Electricity bill (v3.33): ENERGIE shows how much was taken from the grid since the 1st of the month (Europe/Bucharest),
  for the Hidroelectrica bill. `GET /solar/grid-month` -> `{month: "2026-10", since, kwh}` (`kwh` null with no samples yet):
  the API integrates `grid_power` from `history` over `[start of month, now]`, the same series as the RETEA bars (their
  7d/30d sum agrees to ~0.01 kWh), so it is the system's estimate, not the supplier's meter reading. Retro (v3.34, variant "A"
  chosen by the user): a small dimmed-yellow VFD readout on a semi-transparent glass between the title and the LED on the
  top plate (`RetroBillReadout`; big month "OCT" with a small "FACTURA" under it, big segment digits, small "kWh", one
  decimal, none from 100 kWh up; text sizes are dp, not sp, so the plate does not grow with the system font). Simple: a full-width tile above "Produs azi / Consumat azi" (`SimpleBillTile`).
  The app polls it once a minute (10 s after a failure) and keeps the last good value. It flips to the new month at
  Bucharest midnight on the 1st (no "previous month" view yet). `layout-check` asserts the readout stays on the plate.
- SOC in the app (v3.35): every SOC the app shows (TABLOU strip, SISTEM monitor, BAT label, Simple box) comes from
  `soc_voltage` in `GET /solar/bms`, **not** from the BMS counter: linear from the inverter's battery voltage (30 s mean;
  the BMS voltage if the inverter is silent), **1% at `BAT_SOC_EMPTY_V` (48 V), 100% at `BAT_SOC_FULL_V`** = the charge
  threshold set on the inverter (57 V winter, 56 V summer), clamped 0–100 (user decision, 2026-10-26). `soc` stays the JK
  Ah counter (100% at the top of charge, but it drifts at the bottom since 180 Ah is a set value) and is still in Grafana.
  When the user switches season on the inverter: edit `BAT_SOC_FULL_V` in `.env`, then `docker compose up -d api`
  (no app release), **and** `fullV` in the two capacity panels (see next bullet). Under heavy load the voltage, and so the
  SOC, dips; it recovers at rest.
- Measured capacity (Grafana, 2026-10-29): stat "Capacitate baterie: cat s-a scos de la ultima incarcare completa" (id 140)
  and table "Descarcari masurate de la plin" (id 141) at the end of the BMS live row. Pure Flux over `history`: BMS
  `current`/`power` in 5 min means, integrated with `elapsed()` (gaps over 15 min are skipped), net of any recharge in
  between. A new cycle starts when the BMS voltage crosses `fullV` upwards; the reference is the most-charged point after
  that, and "scos net maxim" is the deepest drawdown from it (= usable capacity once the inverter has gone to the grid at
  ~47.6–47.9 V). `fullV` is a constant at the top of both queries = inverter charge threshold − 0.5 V: **56.5 in winter
  (57 V), 55.5 in summer (56 V)**. First result: 2026-10-25 full (56.75 V) to 47.61 V on 2026-10-29 = ~125 Ah / ~6.4 kWh,
  against the 180 Ah / 9.3 kWh set in the BMS.
- Elastic zoom (v3.31): `ElasticZoom.kt` wraps the whole dashboard. Two fingers magnify it (up to ~3×, soft limit) and
  pan it; when the fingers lift it springs back to the original size, nothing is kept. One finger is untouched (taps,
  tab swipes); the two-finger gesture is consumed in the Initial pass so buttons and swipes do not fire.
- Battery capacity / LiFePO4 decision (open, 2026-10-23): **`docs/BATERIE.md`** (current pack facts, incl. the origin of the
  modules: Opel Vivaro C, label MB6VAD, 3½ modules (user, 2026-10-25); the OLX listings compared, why chemistries must not share a bus, what the BMS reader would need for a second pack).
  Nothing is decided yet; do not start work on a second BMS or on LFP thresholds without the user's go-ahead.
- Tests (no Bluetooth needed): `python3 -I -m unittest discover -s /opt/solar-monitor/bms`.
  One-shot read for debugging: `python3 -I bms/jk_bms_reader.py --once` (stop the service first).
  Logs: `journalctl -u solar-bms`.

## CASA (Tuya plugs and sensors, `casa/`)

Container `solar-casa` (Python, gunicorn 1 worker x 8 threads, port 8100, **no published port**). Spec:
`docs/superpowers/specs/2026-11-05-casa-prize-senzori-design.md`; log of the build: `COPILOT_CONTEXT.md` §13.78.
**Status (2026-11-05): real mode (`CASA_DEMO=0`), QR login + `sync` done, all 5 plugs online locally, password set by
the user; still open: DHCP reservations, phones, live tests, deleting the SmartLife schedules (list below).**

- **What it does:** 5 plugs controlled locally (`tinytuya`, TCP 6668, one persistent connection per plug; IPs/protocol
  defaults in `casa/casa/devices.py`, matched by device id: Boiler .204, Living sus .244, Priza cam Georgi .104,
  Bucatarie .185, Lumina Curte .158 (confirmed by the first real `sync`, 2026-11-05; Bucatarie and Lumina Curte are
  "T34-Smart Plug", protocol 3.4, no metering, Tuya category `tdq`)). 3 temperature/humidity sensors from the Tuya cloud
  (`tuya-device-sharing-sdk`, session from a QR login). The real sensors ("T & H Sensor", category `tdq`) come with **no
  status at all over REST**; their values arrive **only by MQTT**, as numbered DPs the SDK drops (no local strategy):
  **27 = temperature ×10, 46 = humidity %, 101 = battery `low`/`middle`/`high`** (`SENSOR_DP_CODES` in `cloud.py`, measured
  2026-11-05). casa sets `set_up=True` on sensors so the SDK subscribes them, and decodes the raw messages itself
  (`_on_raw_message`). A battery sensor wakes and reports roughly **once an hour**; a missing value never erases a known
  one, and at boot the last values come from InfluxDB (`sensor_last`, 7 days). The REST read every 5 min still decides
  the cloud state: `ok` only if the last successful REST read is ≤ 10 min old. No cloud fallback for plug commands: a plug that refuses the local
  connection shows OFFLINE.
- **Devices are loaded once, at boot** (`casa/casa/web.py`). After `login`/`sync`, a rename in SmartLife, or an edit of
  `casa/data/devices.local.json` (`{"<id>": {"ip": "...", "version": "3.3", "name": "...", "hidden": true}}`; `hidden` keeps a
  device out of the app, e.g. "Boiler Buc", unused, in a drawer; a malformed entry is skipped with a warning in the log) →
  `docker compose restart casa`.
- **The three "WiFi Plug" units (Boiler, Living sus, Priza cam Georgi) do not answer ARP** (2026-11-05): they broadcast,
  but `ip neigh` shows FAILED, so ping and TCP 6668 fail. A static entry (`ip neigh replace <ip> lladdr <mac> nud permanent`)
  fixes it. `deploy/tuya_arp.py` (host, root) listens ~25 s for Tuya broadcasts, takes IP + MAC from the Ethernet frame and
  sets those entries; it drops an entry when the MAC moves to another IP or is unseen for 24 h (state in
  `/var/lib/solar-tuya-arp/`). Run by `deploy/solar-tuya-arp.timer` (boot + every 10 min); install:
  `cp deploy/solar-tuya-arp.{service,timer} /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now
  solar-tuya-arp.timer`. Tests: `python3 -I -m unittest discover -s deploy -p 'test_tuya_arp.py'`.
- **Inching in the plug's own firmware** (DP 44 `switch_inching`, base64 of 3 bytes: enabled, seconds big-endian; mapped
  as `dps["inching"]` by `sync`): Bucatarie 25 min and Lumina Curte 30 min are on **on purpose** (user, 2026-11-05). Since
  v3.43 the plug is the source of truth (user's choice): every reading copies DP 44 into the plug's `inching` setting
  (`_sync_native_inching`), so a change made in SmartLife shows up, and `PUT .../inching` writes DP 44 first (a failed
  write changes nothing). The server keeps its own inching deadline too, so "STINGE IN …" shows and the fail-safe
  countdown DP agrees with the firmware. Plugs without DP 44 keep the server-only inching.
- **Names editable from the app (v3.43):** tap the title of a plug/sensor detail (pencil). `PUT /plugs/<id>/name` and
  `PUT /sensors/<id>/name` (1–24 chars, `clean_user_name`) write `name` into `devices.local.json`; plug names change in
  memory at once, sensor names via `Services.names`. SmartLife keeps its own name; Influx tags take the new plug name
  from then on (history is by `device_id`).
- **Compact plug list (v3.43):** Retro CASA rows are a fixed 54 dp (up to 7 plugs, `CompactPlugRows`; more share the
  card), at most 2 badge lines, and plugs without metering have no watts plate (Retro and Simple).
- **Internet cut for the plugs (test since 2026-11-05 ~18:45, user's choice):** the goal is no dependency on SmartLife /
  the Tuya cloud. Router Access Control (blacklist) is **not** usable: it also cuts the LAN (Lumina Curte went offline
  for casa, reverted at once). What works: Parental Controls, profile "Prize fara internet" (`owner_id` 0) with
  `internet_blocked` on (API `admin/smart_network?form=patrol_owner_list|patrol_owner_block`, see the router memory /
  COPILOT §13). For now only Lumina Curte is in it: local control works, the Tuya cloud sees it offline. If it stays
  stable ~1 day, add the other four plugs to the profile; SmartLife (and its schedules) then stops working for them.
  Undo: `internet_blocked=false` or remove the device from the profile.
- **Later, not now (user, 2026-11-05):** Zigbee sensors (USB dongle + zigbee2mqtt) to drop the Tuya cloud for the
  temperature sensors; OpenBeken on the current plugs (tuya-cloudcutter or serial) "when there is time"; future plugs
  with ESPHome/Tasmota (e.g. Athom) so pairing can happen from our app. A (+) "add device" button only makes sense
  with those; with stock Tuya plugs pairing stays in SmartLife + `scripts/casa-sync.sh`.
- **Adding a new plug/sensor:** (1) pair it in SmartLife; (2) `scripts/casa-sync.sh` (= `sync`, then `scan`, then
  `docker compose restart casa`). `scan` runs in a throwaway container with `--network host` (the plugs' UDP broadcasts on
  6666/6667/7000 never reach the bridge network), listens ~20 s and writes the IP and protocol of every plug in
  `devices.json` whose address differs into `devices.local.json` (a hand-set `name` stays); devices not in `devices.json`
  are listed as "Necunoscut". `--scan-only` skips `sync` (a plug changed its IP). firewalld zone `public` has three rich rules
  for this, **LAN only** (`192.168.1.0/24`, UDP 6666, 6667, 7000; user approval 2026-11-05). Then add a DHCP reservation
  for the new IP. It appears in the app, no new APK. The Retro CASA page is laid out for **≤ 5 plugs and ≤ 3 sensors**.
- **Route:** Caddy `handle_path /solar/casa/*` → `casa:8100`, placed **before** `/solar/*`. `request_body max_size 16KB`,
  `request_buffers 20KB` (must stay larger than max_size; Flask `MAX_BODY_BYTES` 16000 must stay ≤ max_size),
  read/write timeouts 60 s (a command takes ≈ 25 s typical, 45 s theoretical worst case; the app waits 50 s), `header_up X-Forwarded-For {remote_host}`
  (client IP for the password limit). Only the casa block of `caddy/Caddyfile` is ours; the rest belongs to beci-cloud
  and is committed apart (`git add -p`); the `Caddyfile.bak-*` files are ignored (`.gitignore`).
- **Auth:** one shared password, set on the server; `POST /solar/casa/register` `{password, device}` returns a long
  token per phone (server keeps only hashes). Every other route needs `Authorization: Bearer`. Limit: 5 wrong tries /
  15 min per IP and 20 / h overall (so the global cap can block **new** registrations for up to 1 h; phones already
  registered are unaffected); commands: 3 per 3 s per phone, non-GET requests only.
  401 on a stored token (revoked) brings the password plate back in the app.
- **CLI** (run in the container): `docker exec -it solar-casa python -m casa <cmd>`:
  `login [user_code]` (QR login with Smart Life: Eu > Setari > Cont si securitate > User Code, then scan the QR in the
  terminal), `sync` (refresh devices + local keys into `devices.json`), `set-password [--stdin]` (asked twice, never in
  argv; changing it does not log phones out), `phones` (registered phones), `revoke <phone_id>`,
  `demo-devices [--force]` (fake `devices.json` for `CASA_DEMO=1`; refuses to overwrite real keys without `--force`),
  `scan` (plug IPs from the LAN; only useful with `--network host`, so run it through `scripts/casa-sync.sh`).
- **Data dir `casa/data/`** (gitignored, mounted at `/data`, files mode 600): `devices.json` (**local keys**),
  `cloud_session.json` (**cloud session**), `auth.json` (password hash + token hashes), `state.json` (schedules,
  countdowns, inching; real mode) / `state.demo.json` (demo mode, carries a demo marker; real mode **refuses** a
  demo-marked state, so test schedules can never run on the real boiler). Secrets never go to git, logs, ntfy or
  Telegram (no exception text with QR token / user code is logged).
- **Demo mode:** `CASA_DEMO=1` in `.env` (**off since 2026-11-05**, `CASA_DEMO=0`): fake plugs from `casa/casa/demo.py`, no
  InfluxDB writes, no cloud. Remove it (and `docker compose up -d casa`) for real operation. The `casa` service does
  **not** get the whole `.env`: `docker-compose.yml` passes only `INFLUX_*`, `NTFY_URL`, `CASA_DEMO` (default 0),
  `CASA_POLL_S` (default 5) and `CASA_DATA_DIR`. A new `CASA_*` variable must be added there too. If the state file is
  demo-marked, real mode exits with gunicorn's code 4 (app load error), so gunicorn halts and Docker's restart backoff applies.
- **Semantics — events (since 2026-11-07, v3.44; spec `docs/superpowers/specs/2026-11-07-casa-evenimente-design.md`):**
  times in Europe/Bucharest. A plug has up to **12 events**, each `HH:MM` + PORNESTE/OPRESTE (`action` on/off), **daily**
  (`days: null`) or on chosen ISO weekdays (1=Mon..7=Sun), active/inactive; two active events at the same time with
  overlapping days are refused ("conflict"). An event acts **only at its time**; between events the plug obeys the app and
  its own button. Missed events (server down, plug offline, failed command): only the **latest pending occurrence** counts
  (`last_event_at`); a newer manual command consumes it; a missed **OFF runs however late** (2-day lookback) and is retried
  without limit; a missed **ON runs only until the next event** of that plug. An event added/edited/enabled at a time already
  passed today does not fire today (`event_cutoff`). **The plug's physical button counts as manual** (an on/off change seen
  between two online readings that the server did not command within 15 s; a press made while the plug is offline is
  invisible). While a plug is on (from any source), the **nearest off deadline** (next active OFF event, inching, countdown
  "off") is written to the plug's countdown DP (dp 9 only as fallback; real DP from `sync`), so it switches off even if the
  server is down — but **only once it is ≤ 24 h away** (a weekday OFF days ahead is not written; a capped 24 h countdown would
  switch off too early). Deleting an OFF while the plug is on leaves it on. A manual command cancels a running countdown.
  The old intervals were migrated once into ON/OFF event pairs (backup `casa/data/state.json.bak-2026-11-07`). Per-plug
  locks and throttle: one dead plug never delays another's stop; a dead plug ties one thread for a command ≈ 25 s typical /
  45 s theoretical worst case. Routes: `POST /plugs/<id>/events` `{time, action, days?, enabled?}`, `PUT`/`DELETE
  /plugs/<id>/events/<eid>`; `GET /plugs` items carry `events`, `next_event` and an empty `schedules: []` (for apps ≤ 3.43).
- **Plug order (v3.44):** the same on every phone: `state.json` top-level `plug_order`, `PUT /plug-order` `{"order": [ids]}`
  (unknown/duplicate → 400; missing ids appended) returns the `GET /plugs` body. App: long-press a plug row on SISTEM → CASA →
  vibration, rows wiggle, drag to reorder, saved on drop, "GATA" or a tap outside leaves the mode.
- **InfluxDB:** `casa_plug` (tags `device_id`, `name`; fields `on`, `online`, `power`, `voltage`, `current` in A) and
  `casa_sensor` (fields `temperature`, `humidity`, `battery`) in `live` and `history`. Sensors are written **on every
  change** to both buckets (not 60 s means); plug history is per minute: `on`/`online` = minimum, `power`/`voltage`/
  `current` = mean. Written by one flusher thread, so a down InfluxDB never delays plug commands. The API energy today = integral of `power` since local midnight.
  Grafana: row "🏠 Casa: prize si senzori" (panels 142–147: plug power, plugs on, sensor temperature/humidity, sensors now).
- **App (v3.40, versionCode 44; v3.41 stale poll fix, v3.42 haptics, v3.43/47 compact list + rename; v3.44/48 events editor + plug order):** Retro SISTEM page has a SISTEM | CASA selector; CASA = 5 plug plates + sensors, tap
  opens plug detail (switch, countdown 30M/1H/2H/6H/other, events list "PROGRAMARI · EVENIMENTE" with "URMATORUL: …" and an editor, inching) or sensor detail (2h/1d/7d charts). Simple
  theme: a CASA card. SETARI: row "ACCES CASA" with "UITA TELEFONUL" (deletes only the local token; the server keeps the phone until `revoke`). The password plate shows on first open.
  Command timeout 50 s; commands stay enabled when a plug looks offline. Files: `CasaModel.kt`, `CasaRepository.kt`,
  `CasaAccess.kt`, `RetroCasa.kt`, `RetroCasaDetail.kt`, `SimpleCasa.kt`. **Poll vs. command order (v3.41):** a
  `GET /plugs` that left before a command finished can land after the command's reply with the old state (the switch
  flipped back for ~3 s, Living sus, 2026-11-05). `CasaFreshness` (`CasaModel.kt`) gives each poll a ticket; its reply
  is used only if no command started or finished meanwhile. Any new command path must call `commandStarted/Finished`.
  The casa access log shows each request's duration (`… 200 240 1.234s`). **Haptics (v3.42):** `CasaHaptics` vibrates
  once (70 ms) when the server confirms a switch, twice on failure (list, Simple card, plug detail).
- **Stale reads on the persistent socket (fixed 2026-11-05):** tinytuya returns the NEXT message on the socket, not the
  reply to the request; a plug's unsolicited pushes (countdown, power) shifted every reply by one, so each 5 s poll
  returned the previous poll's state and a switch looked undone for ~5 s. `TinyTuyaDriver._drain` empties the socket
  (0.05 s timeout) before every `status()` and `set_dp()`. Measured after the fix on Lumina Curte: correct state 0.2 s
  after the command.
- **Tests:** `docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src --entrypoint python
  solar-monitor-casa -m unittest discover -s tests -v` (293 tests, run it after any change in `casa/`; run the full
  suite more than once, a non-reproducible failure was seen once during edits).
- **Go-live steps:** done 2026-11-05: login + `sync`, real mode, every plug online, password, ARP timer. Left: (1) DHCP
  reservations on the router for the 5 plug IPs; (2) confirm the current unit with a load on (Tuya mA / 1000); (5) register the phones (Note 9, S20 FE, S26) from the app and check `phones`; (6) live tests of
  switch, countdown, schedule, inching on a plug (boiler last); (7) **delete the equivalent SmartLife schedules** once
  ours work, or the two will fight.

## Conventions

- **Language:** comments, docs (`README.md`, `COPILOT_CONTEXT.md`), log messages, and alert
  keys/titles are in **Romanian** (the user, Florin, is Romanian). InfluxDB **field names are
  English** (`battery_voltage`, `output_power`, …). Match this when editing.
- All powers are reported and displayed in **watts** (not kW).

## Gotchas

- **The system disk has real media errors (2026-11-02).** GIGABYTE GP-GM30512G-G: SMART counts 519,330 "Media and Data
  Integrity Errors", though it still says PASSED and 0% used. A read error on `kotlin-gradle-plugin-2.0.21-gradle85.jar`
  (`critical medium error`, sector 904624024) hit during a Gradle build at 14:04, most likely the bus error seen then; the journal
  stops there and the server only came back at 14:16 (cause of that gap not logged). A `Bus error` / SIGBUS, or `Could not read workspace metadata` from Gradle, means look at
  `journalctl -k | grep 'medium error'` first and map the sector to a file (`debugfs icheck`/`ncheck`).
  The bad jar is parked in `/root/.gradle/bad-sector-quarantine/` so its blocks stay allocated. A full copy of the disk (rootfs + EFI,
  last pass with the containers stopped, 2026-11-02 15:58, without the quarantine folder) is on the HDD in `/data/backup-nvme-2026-11-02/`.
- After copying files into `grafana/` from a Windows host (scp creates them `700`/root-owned),
  Grafana runs as uid 472 and gets "permission denied" on provisioning. Fix:
  `chmod -R a+rX /opt/solar-monitor/grafana`.
- The InfluxDB CLI `influx bucket create --retention` accepts **only Go durations** (`48h`), not
  `2d`. (The `DOCKER_INFLUXDB_INIT_RETENTION` env var does accept `31d`.)
- **firewalld cuts container-to-container traffic.** If the phone app shows "aștept date" while the
  collector logs look perfectly healthy, check `firewall-cmd --get-active-zones` *before* suspecting
  the inverter — `br-solar` must be in the `docker` zone. `Errno 113` / `no route to host` between
  containers is always this. Established connections survive, so the collector keeps writing to
  InfluxDB while `solar-api` returns 503 and Caddy returns 502 — a very misleading symptom. firewalld
  cannot be disabled (fail2ban bans through it). Full story and fix: `deploy/README-firewalld.md`.
- After a firewalld restart, `docker compose up` may fail with `Failed to Setup IP tables: Unable to
  enable NAT rule: (dbus: connection closed by user)` — the daemon's D-Bus handle to firewalld is
  stale. Cure: `systemctl restart docker`.
- An end-to-end probe (`solar-watchdog.timer`, every 15 min) pushes to ntfy when the chain the phone
  actually uses breaks; the collector's own alerts do not cover it. Sources in `deploy/`, units
  installed in `/etc/systemd/system/`.
