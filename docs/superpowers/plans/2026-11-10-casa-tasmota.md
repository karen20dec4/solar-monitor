# CASA Tasmota + detaliul prizei compactat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `solar-casa` comandă prizele Tasmota (HTTP pe LAN) alături de cele Tuya, iar detaliul prizei din aplicație (v3.45) arată driverul și 5 programări fără derulare.

**Architecture:** Un `TasmotaDriver` nou implementează protocolul existent `PlugDriver` (`connect/status/set_dp/close`) cu o hartă **sintetică** de DP-uri, deci `PlugWorker` și `Controller` rămân aproape neschimbate. Registrul de dispozitive capătă câmpul `driver`; prizele Tasmota păstrează id-ul prizei Tuya pe care o înlocuiesc. Android: un câmp nou `driver` și relayout-ul detaliului (varianta B).

**Tech Stack:** Python 3 stdlib (`urllib`, `json`, `base64`, `unittest`), Flask existent; Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-10-casa-tasmota-design.md` (citește-l întâi; §4 server, §6 aplicație, §9 decizii).

## Global Constraints

- Inversorul și BMS-ul rămân READ-ONLY; nimic din plan nu atinge `collector/`, `bms/`, `api/`.
- **Agenții nu contactează prizele reale** și nu rulează nimic pe serverul de producție (`docker compose up`, `python -m casa` pe `solar-casa`). Testele folosesc fixture-urile din `casa/tests/fixtures/tasmota/` și transport injectat.
- Parola Tasmota nu apare niciodată în log-uri, excepții, `PlugReading.error`, răspunsuri HTTP, argv, git.
- Fără dependențe Python noi (doar stdlib). Fără variabile de mediu noi.
- Comentarii, mesaje de log și erori în **română fără diacritice** (ca restul `casa/`); nume de câmpuri JSON în engleză.
- Harta sintetică Tasmota: `{"switch": 1, "countdown": 9, "current": 18, "power": 19, "voltage": 20, "inching": 44}`, scale `{"power": 1, "voltage": 1, "current": 0}`.
- `PulseTime`: 0 = oprit; 1..111 = zecimi de secundă; 112..64900 = valoare − 100 secunde. Plafon Tasmota `TASMOTA_MAX_S = 64800`; Tuya rămâne `DP_MAX_S = 86400`.
- `Mem1` = durata inching în secunde (păstrată și când e oprit), `Mem2` = `1`/`0` activ. Base = `Mem1` dacă `Mem2 == 1`, altfel 0.
- App: `versionCode 49`, `versionName "3.45"`. Pagina Retro nu derulează niciodată; texte noi doar în Compose.
- Nu stage-ui `caddy/Caddyfile` (modificare străină). Lucrul se face în `/opt/solar-monitor`, ramura `feat/casa-tasmota`.
- Teste casa: `docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src --entrypoint python solar-monitor-casa -m unittest discover -s tests -v` (toate trebuie să treacă; azi 381). Android: `cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk /opt/gradle-8.9/bin/gradle -q :app:testDebugUnitTest`.

## Review Focus

1. Priza Tasmota cu `WebPassword` setat, dar serverul fără parolă/cu parolă greșită (HTTP 401): citirea devine offline cu mesaj „parola Tasmota respinsa”, fără parola în text — test în Task 2.
2. După ce un termen de siguranță a oprit priza (sau serverul a căzut), `PulseTime1 Set` rămâne termenul vechi; următoarea pornire din buton ar fi oprită prea devreme — auto-vindecarea la citire rescrie base; test în Task 2.
3. `Mem1`/`Mem2` goale (`""`, starea de azi a tuturor prizelor) sau gunoi (`"abc"`, `-5`): nicio cheie `"44"` în citire și nicio auto-vindecare — test în Task 2.
4. O intrare `driver: tasmota` fără `ip`, sau cu `driver` necunoscut (`"esphome"`): sărită cu avertisment, pornirea nu cade — test în Task 1.
5. Un termen de oprire la 20 h pe o priză Tasmota nu se scrie (peste 18 h), iar unul la 17 h se scrie; pe Tuya 20 h se scrie — test în Task 3.

---

### Task 1: Registrul de dispozitive cunoaște driverul

**Files:**
- Modify: `casa/casa/devices.py`
- Test: `casa/tests/test_devices.py`

**Interfaces:**
- Produces: `DeviceInfo.driver: str = "tuya"` (ultimul câmp, cu valoare implicită); `DRIVERS = ("tuya", "tasmota")`; `TASMOTA_DPS: dict`, `TASMOTA_SCALES: dict` (valorile din Global Constraints); `load_devices(data_dir) -> list[DeviceInfo]` care aplică `driver`.

- [ ] **Step 1: Scrie testele care pică** (în `test_devices.py`, stilul fișierului: director temporar, `devices.json` + `devices.local.json`):
  - `test_local_tasmota_overrides_tuya_device`: `devices.json` are priza Tuya `P1` (dps Tuya, ip .204); local `{"P1": {"driver": "tasmota", "ip": "192.168.1.186"}}` → `dev.driver == "tasmota"`, `dev.ip == "192.168.1.186"`, `dev.dps == TASMOTA_DPS`, `dev.scales == TASMOTA_SCALES`, `dev.id == "P1"`, numele din `devices.json` păstrat.
  - `test_local_tasmota_entry_self_sufficient`: `devices.json` gol; local `{"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}}` → o priză `kind == "plug"`, `name == "Boiler"`, `driver == "tasmota"`.
  - `test_local_tasmota_without_ip_is_skipped` și `test_unknown_driver_is_skipped`: intrarea e ignorată (priza Tuya din `devices.json` rămâne Tuya, neschimbată; intrarea locală singură nu creează nimic), `assertLogs("casa.devices", "WARNING")`.
  - `test_tasmota_hidden_entry_skipped`: `hidden: true` ascunde și o intrare Tasmota.
  - `test_default_driver_is_tuya`: fără `driver` → `"tuya"`, dps neschimbate.
  - `test_apply_scan_ignores_tasmota`: `apply_scan` cu `found = {"P1": {"ip": "192.168.1.204", "version": "3.3"}}` pentru o priză cu `driver: tasmota` → nicio schimbare, intrarea locală rămâne `ip .186`.
- [ ] **Step 2: Rulează** suita casa — testele noi pică (`driver` necunoscut).
- [ ] **Step 3: Implementează** în `devices.py`: câmpul, constantele, `_valid_local` acceptă `driver` ∈ `DRIVERS` (alt text → invalid); în `load_devices` aplică `driver: tasmota` (ip obligatoriu, dps/scales = copii ale hărților Tasmota); după bucla peste `devices.json`, adaugă intrările locale Tasmota valide, cu `name` și `ip`, al căror id nu apare în `devices.json`; `apply_scan` sare dispozitivele cu `driver == "tasmota"`.
- [ ] **Step 4: Rulează** suita casa — totul trece.
- [ ] **Step 5: Commit** `feat(casa): registrul de dispozitive cunoaste driverul (tuya/tasmota)`.

### Task 2: `TasmotaDriver`

**Files:**
- Create: `casa/casa/tasmota.py`
- Test: `casa/tests/test_tasmota.py` (folosește `casa/tests/fixtures/tasmota/*.json`)

**Interfaces:**
- Consumes: `PlugError`, `decode_inching`, `encode_inching` din `casa/casa/plugs.py`; `JsonStore` din `casa/casa/store.py`.
- Produces:
  - `TASMOTA_MAX_S = 64800`
  - `encode_pulsetime(seconds: int) -> int` și `decode_pulsetime(value: int) -> int` (secunde, rotunjit în sus pentru zecimi)
  - `class TasmotaDriver(ip: str, password: Optional[str] = None, timeout: float = 3.0, fetch: Optional[Callable[[str], dict]] = None)` cu `connect()`, `status() -> dict`, `set_dp(dp: int, value) -> None`, `close()`. `fetch(cmnd)` primește textul comenzii (ex. `"Status 10"`) și întoarce JSON-ul decodat; implicit face `GET http://<ip>/cm?cmnd=…[&user=admin&password=…]` cu `urllib`.
  - `load_tasmota_password(data_dir: str) -> Optional[str]` și `save_tasmota_password(data_dir: str, password: str) -> None` (fișier `tasmota.json`, `{"password": ...}`, mode 0o600; parolă goală/doar spații → `ValueError`).

- [ ] **Step 1: Scrie testele care pică.** Un fetch fals care întoarce fixture-uri după comandă și ține un jurnal al comenzilor primite. Teste:
  - `test_pulsetime_codec`: `encode(0)==0`, `encode(5)==50`, `encode(11)==110`, `encode(12)==112`, `encode(1800)==1900`, `encode(64800)==64900`, `encode(99999)==64900`; `decode(0)==0`, `decode(50)==5`, `decode(1)==1`, `decode(1900)==1800`, `decode(1899)==1799`.
  - `test_status_maps_fixture_nr1`: cu `nr1-power`, `nr1-status10`, `nr1-pulsetime1` (Mem gol) → `{"1": False, "9": 0, "18": 0, "19": 0, "20": 2280}` și **fără** cheia `"44"`. Cu o variantă pornită (`{"POWER":"ON"}`, `Power 1158.0`, `Voltage 228`, `Current 4.95`) → `"1": True, "19": 11580, "20": 2280, "18": 4950`.
  - `test_status_reports_inching_from_mem`: `Mem1 "1800"`, `Mem2 "1"` → `"44" == encode_inching(True, 1800)`; `Mem2 "0"` → `encode_inching(False, 1800)`.
  - `test_garbage_mem_reports_no_inching` (Review Focus 3): `Mem1` ∈ `""`, `"abc"`, `"-5"` → fără `"44"` și nicio comandă `PulseTime1 <n>` trimisă.
  - `test_self_heal_restores_base_when_off` (Review Focus 2): releu OFF, `PulseTime1 Set 3700`, Mem 1800/1 → în jurnal apare `PulseTime1 1900`; cu `Set 1900` nu se trimite nimic.
  - `test_countdown_reads_remaining`: releu ON, `{"PulseTime1":{"Set":3700,"Remaining":3600}}` → `"9" == 3500`.
  - `test_set_switch`: `set_dp(1, True)` → `Power On`; `set_dp(1, False)` → `Power Off`; răspuns cu altă stare decât cea cerută → `PlugError`.
  - `test_set_countdown`: `set_dp(9, 3600)` → `PulseTime1 3700`; `set_dp(9, 0)` cu base 1800/1 → `PulseTime1 1900`; fără Mem valid → `PulseTime1 0`; `set_dp(9, 90000)` → `PulseTime1 64900`.
  - `test_set_inching_writes_mem_and_base_when_off`: `set_dp(44, encode_inching(True, 1800))` cu releu OFF → `Mem1 1800`, `Mem2 1`, `PulseTime1 1900`; cu releu ON → doar `Mem1`, `Mem2` (fără `PulseTime1`); citirea următoare raportează noul `"44"`.
  - `test_unknown_dp_raises`: `set_dp(5, 1)` → `PlugError`.
  - `test_command_error_raises`: răspuns `{"Command":"Unknown","Input":"BOGUS"}` sau `{"Command":"Error"}` → `PlugError`.
  - `test_errors_never_contain_password` (Review Focus 1): driver cu `password="S3cret!"` și fetch-ul implicit lovind un `http.server` local pe 127.0.0.1 care răspunde 401, apoi unul care nu răspunde (timeout scurt), apoi HTML în loc de JSON → de fiecare dată `PlugError`, iar `"S3cret"` nu apare în `str(err)`, în `repr(err)`, în `err.__cause__`/`__context__` (`str`) și nici în log-urile capturate cu `assertLogs`/handler; mesajul pentru 401 conține `parola Tasmota respinsa`.
  - `test_password_file_roundtrip`: `save_` apoi `load_` → aceeași parolă; mod fișier `0o600`; fișier lipsă → `None`; parolă goală → `ValueError`.
- [ ] **Step 2: Rulează** suita — testele noi pică (modul inexistent).
- [ ] **Step 3: Implementează `casa/casa/tasmota.py`.** O citire = cel mult 3 cereri (`Power`, `Status 10`, `PulseTime1`); `Mem1`/`Mem2` se citesc în `connect()` și se țin în memorie (actualizate de `set_dp(44)`). Mesajele `PlugError` se construiesc din IP + tipul erorii (`HTTPError` 401/403 → `parola Tasmota respinsa`), **niciodată** din `str()` al excepției `urllib`, și se ridică `from None`. Parametrii URL se codează cu `urllib.parse.urlencode`.
- [ ] **Step 4: Rulează** suita — totul trece (rulează-o de două ori).
- [ ] **Step 5: Commit** `feat(casa): driver Tasmota (HTTP local, PulseTime ca termen de siguranta, inching in Mem1/Mem2)`.

### Task 3: Controller, rute și CLI folosesc driverul

**Files:**
- Modify: `casa/casa/controller.py` (constanta `DP_MAX_S` la ~45, `_sync_deadline` ~850–888, `plug_view` ~464–500)
- Modify: `casa/casa/web.py` (~115–148, alegerea driverului)
- Modify: `casa/casa/__main__.py` (subcomanda nouă; `demo-devices`)
- Test: `casa/tests/test_controller.py`, `casa/tests/test_web.py`, `casa/tests/test_demo.py` (sau un fișier nou `casa/tests/test_cli.py` dacă CLI-ul nu are teste)

**Interfaces:**
- Consumes: `DeviceInfo.driver`, `TASMOTA_DPS` (Task 1); `TasmotaDriver`, `TASMOTA_MAX_S`, `load_tasmota_password`, `save_tasmota_password` (Task 2).
- Produces: `GET /plugs` (și `/plugs/<id>`) au cheia `"driver": "tuya" | "tasmota"`; CLI `python -m casa tasmota-password [--stdin]`.

- [ ] **Step 1: Scrie testele care pică:**
  - `test_tasmota_deadline_cap_18h` (Review Focus 5, în `test_controller.py`, cu `FakeDriver` și un `DeviceInfo(driver="tasmota", dps=TASMOTA_DPS…)`): priză pornită cu OPRESTE la +20 h → nicio scriere pe DP 9; la +17 h → scriere `61200` (± rotunjirea existentă). Aceeași priză cu `driver="tuya"` la +20 h → scrie `72000`.
  - `test_plug_view_has_driver`: `plug_view` → `"driver"` egal cu `dev.driver` pentru ambele tipuri.
  - în `test_web.py`: `GET /plugs` conține `driver` pentru fiecare priză.
  - `test_cli_tasmota_password_stdin`: `main(["tasmota-password", "--stdin"])` cu stdin `"abc123\n"` și `CASA_DATA_DIR` temporar → `load_tasmota_password(...) == "abc123"`; parola nu apare în stdout.
  - `test_demo_devices_has_one_tasmota`: `demo-devices` scrie exact o priză demo cu `driver == "tasmota"` și `dps == TASMOTA_DPS`.
- [ ] **Step 2: Rulează** suita — pică.
- [ ] **Step 3: Implementează:** în `_sync_deadline`, plafonul vine dintr-o funcție `_dp_max_s(dev) -> int` (`TASMOTA_MAX_S` pentru `tasmota`, `DP_MAX_S` altfel); `plug_view` adaugă `"driver": dev.driver`; `web.py` construiește `TasmotaDriver(d.ip, password=load_tasmota_password(cfg.data_dir))` pentru `driver == "tasmota"` și `TinyTuyaDriver` altfel (demo: tot `DemoDriver`); subcomanda `tasmota-password` copiază tiparul lui `set-password` (getpass de două ori sau `--stdin`); `demo-devices` marchează o priză demo ca Tasmota (alegerea ta care; numele ei rămâne).
- [ ] **Step 4: Rulează** suita — totul trece (de două ori).
- [ ] **Step 5: Commit** `feat(casa): prizele Tasmota in controller (plafon 18 h), driver in GET /plugs, comanda tasmota-password`.

### Task 4: Aplicația v3.45 — driverul și detaliul prizei, varianta B

**Files:**
- Modify: `android/app/src/main/java/com/rolling7/solar/CasaModel.kt` (`CasaPlug`, `parsePlugObject`)
- Modify: `android/app/src/main/java/com/rolling7/solar/RetroCasaDetail.kt` (`RetroPlugDetail` subtitlul ~727–734, `CasaDetailHeader`, `PlugPowerRow` ~887–935, `PlugEventsCard` ~1043–1110, `CasaEventRow` ~1164–1217, `PlugInchingCard` ~1351–1391)
- Modify: `android/app/src/main/java/com/rolling7/solar/RetroCasa.kt` (`CasaSectionTitle` ~594, doar dacă trebuie să accepte `sub` gol fără spațiu)
- Modify: `android/app/build.gradle.kts` (versionCode 49, versionName "3.45")
- Test: testele unitare existente din `android/app/src/test/...` pentru `CasaModel` (adaugă în fișierul care testează deja `parsePlugs`)

**Interfaces:**
- Consumes: cheia JSON `driver` din `GET /plugs` (Task 3).
- Produces: `CasaPlug.driver: String` (implicit `"tuya"`); `internal fun casaPlugSubtitle(index: Int, total: Int, metered: Boolean, online: Boolean): String` (partea fără driver) și `internal fun casaDriverLabel(driver: String): String` (`"tasmota"` → `"TASMOTA"`, `"tuya"` → `"TUYA"`, altceva → uppercase).

- [ ] **Step 1: Teste unitare care pică:** `parsePlugs` fără `driver` → `"tuya"`; cu `"driver": "tasmota"` → `"tasmota"`; `"driver": null` → `"tuya"`; `casaDriverLabel("tasmota") == "TASMOTA"`; `casaPlugSubtitle(3, 5, true, true) == "PRIZA 3/5 · CONTORIZATA · WIFI OK"`, `casaPlugSubtitle(3, 5, false, true) == "PRIZA 3/5 · FARA CONTOR · WIFI OK"`, `casaPlugSubtitle(3, 5, true, false) == "PRIZA 3/5 · WIFI OFFLINE"`.
- [ ] **Step 2: Rulează** testele Android — pică.
- [ ] **Step 3: Implementează modelul și helper-ele**, apoi UI-ul după spec §6 și macheta `android/build/emulator-artifacts/mock-tasmota/b.png` (+ `b.html`):
  - subtitlul = `casaPlugSubtitle(...) + " · " + casaDriverLabel(p.driver)`, cu eticheta driverului într-o culoare aurie existentă (ex. `RetroBrassLight`/`RetroYellow`), restul ca acum (roșu la eroare/offline, mesajele de eroare înlocuiesc tot subtitlul ca acum);
  - `PlugPowerRow` pe un rând: comutator (+ PORNIT/OPRIT sub el) | placa de wați ~46 dp înălțime | placa kWh cu „AZI” sub ea; linia „STINGE …” sub rând; prize fără contor: „PRIZA FARA CONTOR” în locul plăcilor;
  - `PlugEventsCard`: titlul `"PROGRAMARI EVENIMENTE"` integral cu stilul titlului (fără „EVENIMENTE” mic/estompat); rândurile ~40 dp (LED ~18 dp, ora ~19 sp, eticheta ~82 dp lățime, comutator ~48×24 dp, X ~32 dp vizibil cu zona de atingere ≥ 40 dp lățime);
  - `PlugInchingCard`: un singur rând fără linie de titlu: „INCHING” (12 sp, ExtraBold, ca titlurile) cu „OPRIRE AUTOMATA” mic dedesubt, comutator, `−`, afișaj `30 MIN`, `+`; textul „Dupa fiecare pornire…” trece în `contentDescription` al comutatorului;
  - `versionCode 49`, `versionName "3.45"`.
- [ ] **Step 4: Verifică:** testele unitare Android trec; apoi pe emulator, cu serverul demo (procedura din CLAUDE.md, secțiunea „Android emulator”: container `casa-emu-demo` pe `127.0.0.1:18100`, APK debug cu `-PcasaBase=http://10.0.2.2:18100`, parolă de unică folosință): capturi ale detaliului pentru priza demo Tasmota cu 5 evenimente (toate 5 vizibile, fără derulare, fără bara de derulare), cu 12 evenimente (derulare internă), o priză Tuya fără contor, plus `emulator-check.sh layout-check` (profil `note9-large`). Salvează capturile în `android/build/emulator-artifacts/tasmota-*.png` și **uită-te la ele**. Oprește containerul demo și șterge directorul lui temporar la final.
- [ ] **Step 5: Commit** `feat(android): v3.45 driverul prizei (TASMOTA/TUYA) si detaliul prizei compactat (5 programari fara derulare)`.

### Task 5: Documentația

**Files:**
- Modify: `CLAUDE.md`, `GEMINI.md` (secțiunea CASA, în sincron), `docs/DEZVOLTARE.md` (câmpul `driver` în `GET /plugs`), `COPILOT_CONTEXT.md` (§13.81 nou)

- [ ] **Step 1:** CLAUDE.md/GEMINI.md: prizele sunt Tuya sau Tasmota (tabelul din spec §2), `devices.local.json` cu `"driver": "tasmota"` + `ip` (+ `name` pentru intrările care nu mai sunt în `devices.json`), întoarcerea la Tuya, `casa/data/tasmota.json` + `python -m casa tasmota-password [--stdin]`, `PulseTime1` ca termen de siguranță (plafon 18 h; Tuya 24 h), `Mem1`/`Mem2` pentru inching, auto-vindecarea, calibrarea pe calorifer, „Tuya citea ~10 % peste”, App v3.45 / versionCode 49 (antet într-un rând, TASMOTA/TUYA, 5 programări, inching pe un rând). Actualizează numărul de teste casa.
- [ ] **Step 2:** COPILOT_CONTEXT.md §13.81: jurnalul de punere în funcțiune a celor 4 prize (MAC, IP, firmware 14.1.0.3 DE / 15.1.0 fără template → 16.0.0, calibrări pe același calorifer (treapta 2, referința nr2 ≈ 1157 W / 4,947 A; invertorul ~1100–1150 W): `PowerCal/CurrentCal/VoltageCal` nr1 8809/2962/1505, nr2 9500/3150/1617, nr3 9055/3038/1547, nr4 9013/3008/1541; nr1, nr3, nr4 citeau din fabrică cu 4–8 % peste; Tuya Living sus 1285 W pe aceeași sarcină (~10 % peste); nr2 și nr3 au venit fără template (Module ESP32C3 generic), nr1 și nr4 cu template și firmware german; observațiile despre `PulseTime`/`Remaining`).
- [ ] **Step 3: Commit** `docs(casa): prize Tasmota, driverul, v3.45`.

---

După Task 5: review final al ramurii (Opus), apoi punerea în funcțiune din spec §8 o face sesiunea principală.
