# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

100% local, self-hosted monitoring for a **Growatt SPF 6000 ES Plus** off-grid inverter. It
replaces the Growatt cloud / ShinePhone app. A Python collector polls the inverter over Modbus
RTU and writes to InfluxDB; Grafana visualizes; ntfy pushes alerts to a phone. Everything runs in
Docker Compose on a small server in the basement (IP `192.168.1.199`).

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

## Architecture

```
Inverter --USB (/dev/growatt)--> collector.py (minimalmodbus, FC04, polls every 1s)
   |-> InfluxDB bucket `live`    (1s data,  48h retention)
   |-> InfluxDB bucket `history` (60s data, 31d retention)
   |-> ntfy push (phone alerts on threshold breach / watchdog)
   `-> Grafana dashboard `solar-main` (live 1s + 30-day history)
```

Four containers (`docker-compose.yml`): `solar-collector`, `solar-influxdb`, `solar-grafana`,
`solar-ntfy`. All `restart: unless-stopped`, start on boot.

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

There is **no test suite, linter, or CI** — this is a small operational stack. Iteration is via
Docker and InfluxDB queries.

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
- UI asset tooling: ImageMagick 7.1.1-43 and WebP 1.5.0. Photoshop sources are staged in
  `android/build/emulator-artifacts/design/`; run `scripts/prepare-retro-ui-assets.sh` to regenerate the
  tracked WebP resources.

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

**Deploy flow:** edit locally → `git push` → on the server `cd /opt/solar-monitor && git pull &&
docker compose up -d --build`. To debug the Modbus mapping live, set `DEBUG_RAW=1` in `.env`,
restart the collector, and inspect the raw `r{i}`/`s{i}` (signed) register fields in InfluxDB.

## How `collector.py` works

Single-file, single-threaded loop in `collector.py`. Per cycle: open Modbus instrument (lazily,
reconnect on failure) → one FC04 read of `REG_COUNT` registers → `parse(regs)` → evaluate alerts →
write a point to `live` (and to `history` every `HISTORY_INTERVAL`).

- **`parse(regs)`** is the heart: maps raw registers to named float fields and computes derived
  metrics. `u32`/`s32` decode 32-bit register pairs (big-endian, two's complement). Battery power
  comes from the signed int32 **Bat_Watt at regs 77/78** (the code negates it so the field
  convention stays *positive = charging, negative = discharging*); charge/discharge currents are
  regs 83/84. Voltages/SOC/temp are single registers with the scalings shown inline.
  - Derived signals worth understanding before touching them: `inverter_loss` (self-consumption,
    from a power balance, ~90–110 W in daylight), `grid_import_power` vs. inferred battery
    discharge (uses inverter **status code** reg 0 — `STATUS_BYPASS`/`STATUS_DISCHARGE` — plus grid
    voltage and battery-first mode to decide whether an unexplained load deficit is coming from the
    grid or the battery), and `house_source` (1=PV / 2=battery / 3=grid). `house_source` also routes
    `output_power` to exactly one of `house_pv`/`house_bat`/`house_grid` each cycle so the dashboard
    can show a single source-colored pill.
- **Alerts** (`build_alerts` / `eval_alerts`): list of dicts with `fire`/`clear` lambdas over the
  parsed data. Each alert has **debounce** (condition must hold `ALERT_DEBOUNCE_S`), **cooldown**
  (`ALERT_COOLDOWN_S` between re-alerts), and **hysteresis** (separate clear threshold). Covers high
  load, battery low/high, overheat, lost AC output. A separate **watchdog** fires after
  `WATCHDOG_FAILS` consecutive read failures (inverter silent / USB unplugged).

> Note on register docs: `COPILOT_CONTEXT.md` is a hand-maintained session log with the hardware
> details, access credentials' locations, and the discovery history of the register map. Parts of
> its register table are **stale** — e.g. it documents battery power via register 90, but the code
> moved to the official Bat_Watt registers 77/78 (commit "Use official battery watt registers").
> When they disagree, **`collector.py` is the source of truth.**

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
