# CASA: programări pe evenimente — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-plug daily intervals (`start → stop`) with independent daily/weekday events (`HH:MM` + PORNEȘTE/OPREȘTE) on the `casa` server and in the Android app (v3.44).

**Architecture:** Pure occurrence math in `casa/casa/schedule.py`; `casa/casa/controller.py` keeps state in `state.json`, executes the latest pending occurrence, writes the nearest OFF into the plug's countdown DP (only when ≤ 24 h away), treats observed button presses as manual; `web.py` exposes `/plugs/<id>/events`. Android parses `events`/`next_event`, shows mockup A list + editor in `RetroPlugDetail` (Simple reuses it).

**Tech Stack:** Python 3.12 + Flask (container `solar-monitor-casa`), unittest; Kotlin + Jetpack Compose, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-07-casa-evenimente-design.md` (read it first; Romanian). Mockups: `android/build/emulator-artifacts/mock-evenimente/a.png`, `editor.png`.

## Global Constraints

- Comments, log messages, UI text: **Romanian, without diacritics in code strings** (follow existing files: "PORNESTE", "OPRESTE", "ZILNIC"). Field names in JSON/Influx: English.
- Times: Europe/Bucharest; `HH:MM` 24 h; DST rules = existing `_local_to_utc` (spring gap → 04:00, autumn ambiguous → fold=0, executed once).
- Days: `null` = daily, else sorted unique non-empty list of ISO weekdays `1`(Mon)..`7`(Sun); all 7 → stored as `null`.
- `MAX_EVENTS = 12` per plug. Two **enabled** events with equal `time` and overlapping days → `ValueError` starting with `"conflict"`.
- `LOOKBACK` stays `timedelta(days=2)`. `DP_MAX_S = 86400`.
- READ-ONLY invariant for inverter/BMS untouched; only `casa` commands plugs.
- Never `git add -A` / `git add .`: `caddy/Caddyfile` has unrelated uncommitted changes. Stage only your files.
- casa tests: `docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src --entrypoint python solar-monitor-casa -m unittest discover -s tests -v` — run the full suite **twice**.
- Android tests: `cd /opt/solar-monitor/android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk /opt/gradle-8.9/bin/gradle -q :app:testDebugUnitTest`. On `Bus error`/SIGBUS see CLAUDE.md "system disk" gotcha; retry once.
- Commit messages: conventional prefix, Romanian body, end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Event added/edited/enabled at a time already passed today (e.g. add PORNESTE 10:00 at 10:30) → must NOT fire now (per-event cutoff). Test in Task 2.
2. Button press observed while plug online, then a missed/retried PORNESTE → must not turn the plug back on. A reconnect (first reading after offline) is not a press. Test in Task 3.
3. Next OFF > 24 h away (OPRESTE L–V 17:00, plug on Friday 18:00) → nothing (or 0) written in DP; written once it is < 24 h. Test in Task 3.
4. Migration of existing `state.json` (one real interval 07:00–07:30 on `bf3bac46d1a367e845thx2`) → two events, nothing executes retroactively on first start; demo-marked state still refused by real mode. Test in Task 2.
5. App ≤ 3.43 receives `schedules: []` and keeps working; app 3.44 on a server reply without `events` (old server) shows an empty list, no crash. Tests in Tasks 4 and 5.

---

### Task 1: Event model and occurrence math (`schedule.py`)

**Files:**
- Modify: `casa/casa/schedule.py` (add event code; delete `Interval`, `Edge`, `_window`, `edges_between`, `_window_containing`, `active_interval`, `interval_end`, `missed_edges` once Task 2 no longer imports them — in this task keep them, Task 2 deletes them)
- Test: `casa/tests/test_schedule.py` (add `EventTest`, `OccurrenceTest`)

**Interfaces — Produces:**
- `normalize_days(days) -> Optional[tuple[int, ...]]` — accepts `None` or iterable of ints; ValueError on empty, non-int/bool, outside 1..7; returns `None` for all 7, else sorted unique tuple.
- `@dataclass(frozen=True) class Event: id: str; time: str; action: Literal["on","off"]; days: Optional[tuple[int,...]]; enabled: bool` — `__post_init__` validates `parse_hhmm(time)`, action, and that `days == normalize_days(days)`.
- `Event.from_dict(d: dict) -> Event` / `Event.to_dict() -> dict` (`days` as list or None).
- `@dataclass(frozen=True) class Occurrence: event_id: str; action: str; at: datetime` (aware UTC).
- `occurrences_between(events: list[Event], t0: datetime, t1: datetime, tz: ZoneInfo) -> list[Occurrence]` — enabled only, `t0 < at <= t1`, enumerated per local calendar day, weekday = `d.isoweekday()` of the local date, deduped, sorted by `(at, event_id)`.
- `next_occurrence(events, now, tz, action: Optional[str] = None) -> Optional[Occurrence]` — first enabled occurrence with `at > now` (filtered by action if given), search horizon 8 days.
- `events_conflict(a: Event, b: Event) -> bool` — both enabled, same time, days overlap (`None` overlaps everything), different ids.
- `OffDeadline`, `nearest_off`, `parse_hhmm`, `_local_to_utc` unchanged.

- [ ] **Step 1: Write failing tests** in `test_schedule.py`:
  - `test_normalize_days`: `None→None`, `[3,1,1]→(1,3)`, `range(1,8)→None`, `[]`/`[0]`/`[8]`/`[True]`/`["1"]` → ValueError.
  - `test_event_rejects_bad_fields`: time `"24:00"`, action `"toggle"`, days `(3,1)` (unsorted) → ValueError.
  - `test_daily_occurrences_two_days`: `Event("a","17:00","off",None,True)`, window 2026-11-06T00:00Z..2026-11-08T00:00Z → `at` = 2026-11-06T15:00Z and 2026-11-07T15:00Z.
  - `test_weekday_filter_uses_local_date`: `Event("w","00:30","on",(6,),True)` (Saturday 00:30 Bucharest = Friday 22:30Z on 2026-11-06) → single occurrence `2026-11-06T22:30Z` in a window covering 2026-11-05..2026-11-09.
  - `test_disabled_event_has_no_occurrences`.
  - `test_spring_gap_lands_on_0400`: `"03:30"` on 2026-03-29 → `2026-03-29T01:00Z`; `test_autumn_ambiguous_once`: `"03:30"` on 2026-10-25 → exactly one occurrence `2026-10-25T00:30Z`.
  - `test_next_occurrence_skips_days_and_filters_action`: events OFF L–V 17:00 + ON daily 11:00; now = Friday 2026-11-06 18:00 local → `next_occurrence(..., action="off").at == 2026-11-09T15:00Z`; without action → Saturday 11:00 (`2026-11-07T09:00Z`).
  - `test_events_conflict`: same time daily vs `(6,7)` → True; `(1,)` vs `(2,)` → False; one disabled → False; different time → False.
- [ ] **Step 2: Run** the casa suite — new tests FAIL (ImportError).
- [ ] **Step 3: Implement** the interfaces above in `schedule.py` (reuse `_local_to_utc`; enumerate days `t0_local.date()-1 .. t1_local.date()+1`).
- [ ] **Step 4: Run** the casa suite twice — all PASS.
- [ ] **Step 5: Commit** `feat(casa): model de evenimente si aparitiile lor (schedule.py)`.

---

### Task 2: Controller on events — state, migration, execution, CRUD, view

**Files:**
- Modify: `casa/casa/controller.py`, `casa/casa/schedule.py` (delete interval code)
- Test: `casa/tests/test_controller.py` (replace interval tests with event tests; keep countdown/inching/command tests passing), `casa/tests/test_schedule.py` (drop interval tests)

**Interfaces — Consumes:** Task 1. **Produces:**
- Persistent per-plug state (`_blank()`): `events: list[dict]`, `event_cutoff: dict[str, str]` (event id → ISO; occurrences with `at <= cutoff` never run), `last_event_at: Optional[str]` (ISO; occurrences with `at <= last_event_at` are consumed), plus existing `countdown`, `inching`, `inching_off_at`, `last_manual_at`. Removed: `schedules`, `last_done`, `started_by`, `release_off_at`, `last_start_at`, `cutoff`.
- `migrate_state(data: dict, now: datetime) -> bool` (module function; True if changed): per plug with legacy keys, each interval `{id,start,stop,enabled}` → events `{id: id+"s", time: start, action: "on", days: None, enabled}` and `{id: id+"e", time: stop, action: "off", ...}`; set `last_event_at = now`; drop legacy keys. Called once in `Controller.__init__` via `store.update`. Must not touch the demo marker.
- `Controller.add_event(pid, time: str, action: str, days, enabled: bool = True) -> dict`, `update_event(pid, event_id, **changes) -> dict` (keys ⊆ `time, action, days, enabled`), `delete_event(pid, event_id) -> dict`. Unknown id → `KeyError("eveniment necunoscut: …")`. Limit → `ValueError("cel mult 12 evenimente pe priza")`. Add/edit/enable sets `event_cutoff[id] = now`. Delete pops cutoff. All end with `self._after(pid)`.
- `plug_view` adds `"events"` (dicts sorted by `(time, days or ())`), `"next_event": {"id","time","action","at"} | None` (from `next_occurrence`), and keeps `"schedules": []`. `off_deadline` uses the new candidates.
- `MAX_EVENTS = 12` replaces `MAX_INTERVALS`.

Execution rule (`_process_events(pid, now)`, replaces `_process_missed`): pending = enabled occurrences in `(max(last_event_at, now-LOOKBACK), now]` not cut off; take only the **latest** `o`; older pending ones are consumed with it.
- `last_manual_at > o.at` → consume.
- `o.action == "off"`: if `rt.on is False` → consume; else `_auto_switch(off)`; failure → keep pending (`retry_at = now + RETRY_S`), no limit.
- `o.action == "on"`: if `rt.on is True` → consume; else `_auto_switch(on)`; failure → keep pending (it stops being retried naturally when a newer occurrence becomes latest).
- Consume = `last_event_at = o.at`. `_tick_plug` sets `needs_missed` when `occurrences_between(events, prev, now)` is non-empty (same pattern as today).
- `_candidates`: next enabled OFF occurrence (`OffDeadline(at, "schedule")`) + inching + countdown off. `switch()` no longer touches removed keys.

- [ ] **Step 1: Write failing tests** (use the existing fakes/clock helpers in `test_controller.py`), at minimum:
  - `test_off_event_switches_off_at_its_time`; `test_on_event_switches_on`;
  - `test_manual_after_event_wins` (event ON 11:00 missed, app `switch(off)` at 11:10, plug back 11:30 → stays off);
  - `test_missed_off_runs_late` (server down 17:00–20:00 → off at 20:00); `test_missed_off_retried_until_success`;
  - `test_missed_on_dropped_after_next_event` (ON 11:00 + OFF 16:00, back at 16:30 → only OFF considered; plug ends off);
  - `test_event_added_in_the_past_today_does_not_fire` (add ON 10:00 at 10:30 → nothing; next day 10:00 → fires) — Review Focus 1; same for `update_event` and enabling;
  - `test_conflict_rejected`, `test_limit_12`, `test_unknown_event_keyerror`;
  - `test_migration_converts_intervals_without_firing` (state with `schedules [{id:"4a95",start:"07:00",stop:"07:30",enabled:true}]`, `last_done`, `cutoff`; controller created at 07:10 with plug off → events `4a95s`/`4a95e`, no command sent at 07:10; OFF at 07:30 still sent if plug on) — Review Focus 4;
  - `test_view_has_events_next_event_and_empty_schedules`;
  - `test_deadline_is_next_off_event` (plug on at 13:00, OFF 17:00 → DP written ≈ 4 h; delete the OFF → DP rewritten 0 / no deadline).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** per the interfaces; delete interval code from `schedule.py`/`controller.py` and their old tests.
- [ ] **Step 4: Run** the casa suite twice — all PASS.
- [ ] **Step 5: Commit** `feat(casa): programatorul pe evenimente, migrarea intervalelor`.

---

### Task 3: Button press = manual; deadlines beyond 24 h

**Files:** Modify `casa/casa/controller.py`; Test `casa/tests/test_controller.py`.

**Interfaces — Consumes:** Task 2. **Produces:** `_Rt.last_cmd: Optional[tuple[bool, datetime]]` (state + time of the last switch command the server sent, set in `_send_switch` before sending); `_Rt.cd_capped` removed.

- Manual detection in `on_reading`: transition `on↔off` observed with `prev_online is True` and `prev_on is not None`, and **not** explained by `last_cmd` (same target state sent within `GRACE_S + CASA poll` — use 15 s) → `last_manual_at = now`, countdown cleared (as `switch()` does). The plug's own DP firing OFF also counts — harmless (plug is already off). First reading after offline never counts.
- `_sync_deadline`: if nearest deadline `remaining > DP_MAX_S` → treat as no deadline for the DP (write 0 if `cd_written` is set, else nothing); when it comes within 24 h it is written normally. Delete the `capped`/`cd_capped` path.

- [ ] **Step 1: Failing tests:** `test_button_off_blocks_retried_on` (Review Focus 2); `test_reconnect_is_not_a_press`; `test_server_command_echo_is_not_a_press`; `test_button_press_cancels_countdown`; `test_far_off_deadline_not_written` (OFF days `(1,2,3,4,5)` 17:00, plug turned on Friday 2026-11-06 18:00 local → no non-zero DP write; at Sunday 2026-11-08 17:30 local → DP ≈ 23.5 h written) — Review Focus 3; `test_far_deadline_clears_previous_dp` (DP written for inching 10 min, inching disabled, next OFF in 3 days → DP 0).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** suite twice — PASS.
- [ ] **Step 5: Commit** `feat(casa): butonul prizei conteaza ca manual; termen peste 24 h nescris in priza`.

---

### Task 4: HTTP routes `/plugs/<id>/events`

**Files:** Modify `casa/casa/web.py` (remove `/schedules` routes; error-prefix check at ~line 340 → `"eveniment necunoscut"`), `casa/casa/demo.py` only if it references schedules; Test `casa/tests/test_web.py`.

**Interfaces — Consumes:** Task 2 controller methods. **Produces (HTTP):**
- `POST /plugs/<pid>/events` body `{time, action, days?, enabled?}` (`days` default `null`, `enabled` default `true`) → 200 plug view.
- `PUT /plugs/<pid>/events/<eid>` any non-empty subset of `time, action, days, enabled` → 200 plug view.
- `DELETE /plugs/<pid>/events/<eid>` → 200 plug view.
- 400 on: bad `time` (`_hhmm`), action not `on|off`, `days` not null/list of ints 1..7, conflict, limit (message from the ValueError); 404 on unknown event/plug. Same auth and command throttle as other non-GET routes.

- [ ] **Step 1: Failing tests:** add/update/delete happy paths; each 400 case; 404; `/schedules` now 404/405; `GET /plugs` contains `events`, `next_event`, `schedules: []` (Review Focus 5, server side).
- [ ] **Step 2–4:** run (FAIL), implement, run suite twice (PASS).
- [ ] **Step 5: Commit** `feat(casa): rute HTTP pentru evenimente`.

---

### Task 5: Android model, repository, badges

**Files:** Modify `android/app/src/main/java/com/rolling7/solar/CasaModel.kt`, `CasaRepository.kt`, `RetroCasa.kt` (badge regex/kind); Test `android/app/src/test/java/com/rolling7/solar/CasaModelTest.kt`, `CasaRetroTest.kt`.

**Interfaces — Produces:**
- `data class CasaEvent(val id: String, val time: String, val action: String, val days: List<Int>?, val enabled: Boolean)`; `data class CasaNextEvent(val id: String, val time: String, val action: String, val at: Long)`.
- `CasaPlug`: `schedules` replaced by `events: List<CasaEvent>` and `nextEvent: CasaNextEvent?` (missing keys → empty / null).
- `internal const val MaxEvents = 12`; `internal fun casaCanAddEvent(count: Int): Boolean`.
- `internal val CasaDayShort = listOf("L","MA","MI","J","V","SA","DU")` (index = ISO day − 1).
- `internal fun casaDaysText(days: List<Int>?): String` — `null`→`"ZILNIC"`, `[1..5]`→`"L-V"`, `[6,7]`→`"SA · DU"`, else short names joined `" · "`.
- `internal fun casaActionText(action: String): String` — `"on"`→`"PORNESTE"`, else `"OPRESTE"`.
- `internal fun casaNextEventText(e: CasaNextEvent, nowEpoch: Long, zone: ZoneId): String` — `"OPRESTE 16:00 · IN 2H 14M"`; if the local date of `at` ≠ today, the `CasaDayShort` of that date goes before the time: `"OPRESTE L 17:00 · IN 2 ZILE …"` (whatever `formatDuration` prints). Duration via the existing `formatDuration`.
- `plugBadges(p, nowEpoch, zone)`: first badge = next event as `"<ACTIUNE> [ZI ]HH:MM"` (no “IN …”), then the existing countdown/inching/STINGE badges. Update callers. `RetroCasa.kt`: the SCHEDULE badge regex matches `^(PORNESTE|OPRESTE)( (L|MA|MI|J|V|SA|DU))? \d\d:\d\d$` and shows the text unchanged.
- `CasaRepository.addEvent(token, id, time, action, days: List<Int>?)`, `updateEvent(token, id, eid, time: String? = null, action: String? = null, days: List<Int>? = null, daily: Boolean = false, enabled: Boolean? = null)` (`daily = true` sends `"days": null`), `deleteEvent(token, id, eid)`; remove the interval functions.

- [ ] **Step 1: Failing tests:** parse events + next_event; parse a reply without `events` (old server) → empty list, null (Review Focus 5); `casaDaysText` cases above; `casaNextEventText` today/other day; `plugBadges` order; badge regex classification for `"OPRESTE 17:00"` and `"PORNESTE SA 09:00"`.
- [ ] **Step 2–4:** run (FAIL), implement, run Android tests (PASS). UI compile errors in `RetroCasaDetail.kt` from removed interval API are expected to be fixed in Task 6; if the build of unit tests requires compiling it, make the minimal stub change (call sites compile) and leave the UI to Task 6.
- [ ] **Step 5: Commit** `feat(android): modelul de evenimente CASA si ecusonul urmatorului eveniment`.

---

### Task 6: Android UI — events card and editor (mockup A), v3.44

**Files:** Modify `RetroCasaDetail.kt` (replace `PlugSchedulesCard`/`ScheduleRow`/interval dialogs), `android/app/build.gradle*` (`versionCode = 48`, `versionName = "3.44"`); Test `CasaDetailTest.kt` for pure helpers you add.

**Interfaces — Consumes:** Task 5.
- `PlugEventsCard`: title “PROGRAMARI” + small “EVENIMENTE”; line `"URMATORUL: " + casaNextEventText(...)` or `"NICIUN EVENIMENT"`; rows: LED (enabled), time (orange, large), action chip (green PORNESTE / red OPRESTE), `casaDaysText`, `CasaToggle` enable, X delete (confirm dialog `"STERGI EVENIMENTUL?"`); disabled rows at reduced alpha; tap row → editor in edit mode; bottom buttons `"+ PORNIRE"` / `"+ OPRIRE"` hidden when `!casaCanAddEvent`. Card scrolls internally when > 2 rows (existing pattern). The SISTEM/CASA page itself never scrolls.
- `CasaEventEditor` dialog (`CasaDialog`): "CE FACE" two buttons; "LA ORA" big digits with +/− for hours and minutes (step 1, long-press repeats); "IN CE ZILE" `ZILNIC` / `ALEG ZILELE` + seven chips from `CasaDayShort` (no chip selected → SALVEAZA disabled); `ANULEAZA` / `SALVEAZA`; server error text shown inside the dialog. Commands go through the existing `command { … }` helper (which calls `CasaFreshness.commandStarted/Finished` and `CasaHaptics`).
- Pure helpers (tested): `casaStepTime(time: String, dh: Int, dm: Int): String` wraps 23:59→00:00 both ways.

- [ ] **Step 1: Failing tests** for `casaStepTime` (`"23:59",0,1→"00:00"`, `"00:00",-1,0→"23:00"`, `"16:20",0,5→"16:25"`).
- [ ] **Step 2–4:** run (FAIL), implement UI + helpers, run Android tests (PASS).
- [ ] **Step 5: Visual check:** `.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify` and `layout-check`; capture the plug detail with 0, 3 and 12 events and the editor (demo server or the live server via the emulator, whatever the skill supports) into `android/build/emulator-artifacts/`; inspect PNGs; no vertical scroll on the four tabs.
- [ ] **Step 6: Commit** `feat(android): programari pe evenimente in detaliul prizei (v3.44)`.

---

### Task 7 (controller, not a subagent): docs, deploy, release

- [ ] Update `CLAUDE.md` + `GEMINI.md` (CASA: semantics, app, routes), `COPILOT_CONTEXT.md` (journal), spec status → implemented.
- [ ] Backup: `cp casa/data/state.json casa/data/state.json.bak-2026-11-07` (mode 600), then `docker compose up -d --build casa`; check logs, `state.json` migrated, plugs online.
- [ ] Build release APK and deliver per `docs/DEZVOLTARE.md` (Telegram).
- [ ] Commit docs.

---

### Task 8: Plug order shared by all phones (spec §11)

**Files:** Modify `casa/casa/controller.py` (`plugs_view` order, `set_order`), `casa/casa/web.py` (route), Android `CasaRepository.kt`, `RetroCasa.kt` (reorder mode on the CASA plug rows), `CasaModel.kt` (pure helper); Tests `casa/tests/test_controller.py`, `casa/tests/test_web.py`, `android/.../CasaModelTest.kt`.

**Interfaces — Produces:**
- Persistent top-level key in `state.json`: `"plug_order": list[str]` (sibling of `"plugs"`; the demo marker stays untouched).
- `Controller.set_order(order: list[str]) -> list[dict]` — ValueError on unknown id or duplicate; stores the given ids followed by missing known ids in current order; returns `plugs_view()`.
- `Controller.plugs_view()` returns plugs sorted by `plug_order`, unknown/missing ids appended in device order.
- `PUT /plug-order` body `{"order": [..]}` → 200 JSON list (same shape as `GET /plugs`); 400 on bad body/unknown/duplicate. `@require_token`, same command throttle as other non-GET routes.
- Android: `CasaRepository.setPlugOrder(token: String, ids: List<String>): CasaResult<List<CasaPlug>>`; pure `internal fun casaMove(ids: List<String>, from: Int, to: Int): List<String>`.
- UI (Retro CASA rows, `CompactPlugRows`): long-press a row → `CasaHaptics` short vibration → reorder mode; rows wiggle (small rotation ±1°, ~120 ms loop); drag a row vertically, others shift; on drop: optimistic local reorder + `setPlugOrder` via the existing command path (`CasaFreshness.commandStarted/Finished`; on failure revert and double-vibrate); "GATA" button (or tap outside the rows) leaves the mode; in reorder mode taps don't open details and switches don't command. Simple's CASA card simply renders the server order.

- [ ] **Step 1: Failing tests:** controller `test_order_applied_in_view`, `test_order_missing_ids_appended`, `test_order_rejects_unknown_and_duplicates`, `test_order_survives_restart` (new Controller on same store); web `test_put_plug_order`, `test_put_plug_order_400`, auth required; Android `casaMove` (move down, move up, same index, out-of-range clamps).
- [ ] **Step 2–4:** run (FAIL), implement, run casa suite twice + Android tests (PASS).
- [ ] **Step 5: Visual check** with the emulator skill: reorder mode screenshot (wiggle frame + GATA), layout-check passes, no page scrolling.
- [ ] **Step 6: Commit** `feat(casa): ordinea prizelor, aceeasi pe toate telefoanele (apasare lunga + tragere)`.
