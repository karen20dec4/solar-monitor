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
> violate the invariant.
>
> **Decision log (2026-07-18, topic CLOSED):** researched the SPF Modbus protocol (official docs +
> community implementations) for a "start charging battery now" command — **no such command exists**;
> the solar charger is an autonomous state machine with an internal, non-settable re-charge threshold
> (≈ float −2V). The only protocol-level workaround (writing holding reg 1 to switch output to
> utility so PV dedicates to the battery) was **rejected by the user** (grid consumption not worth
> ~0.5V). Final: no button, no writes, READ-ONLY stands. Full research in `COPILOT_CONTEXT.md` §13.17.
> Do not reopen without new information (e.g. new firmware with documented charge-control registers).
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

Six containers (`docker-compose.yml`): `solar-collector`, `solar-influxdb`, `solar-grafana`,
`solar-ntfy`, plus `solar-api` (READ-ONLY JSON micro-API for the Android app; no published port,
reached internally at `api:8000`) and `solar-caddy` (HTTPS reverse proxy, `tls internal`, routes
`/solar/*` → api and everything else → Grafana). All `restart: unless-stopped`, start on boot.

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
`status` and `stop`. `retro-tabs` captures all four Retro pages and rejects accidental scrolling. The
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
painted green LED gets a recoloured orange/red overlay when live data is late.)
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
- Tests (no Bluetooth needed): `python3 -I -m unittest discover -s /opt/solar-monitor/bms`.
  One-shot read for debugging: `python3 -I bms/jk_bms_reader.py --once` (stop the service first).
  Logs: `journalctl -u solar-bms`.

## Conventions

- **Language:** comments, docs (`README.md`, `COPILOT_CONTEXT.md`), log messages, and alert
  keys/titles are in **Romanian** (the user, Florin, is Romanian). InfluxDB **field names are
  English** (`battery_voltage`, `output_power`, …). Match this when editing.
- All powers are reported and displayed in **watts** (not kW).

## Gotchas

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
