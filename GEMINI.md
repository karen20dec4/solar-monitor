# GEMINI.md — punct de intrare pentru agentul care continuă proiectul

> Echivalentul lui `CLAUDE.md`, pentru agentul Gemini.
> **Citește-l întreg înainte de prima modificare.** Apoi `docs/DEZVOLTARE.md` pentru
> procedura completă de adăugat funcții, build, release și livrare pe Telegram.
>
> ⚠️ **Nu presupune că acest fișier ți-a fost injectat automat.** Verificat pe 2026-08-23:
> CLI-ul Antigravity (`agy`) în modul `--print` **nu** încarcă singur `GEMINI.md`. Dacă
> nu-i vezi conținutul deja în context, deschide-l explicit cu un tool de citire, împreună
> cu `docs/DEZVOLTARE.md`, **înainte** de a atinge orice fișier.

## Ce este proiectul

Monitorizare **100% locală, self-hosted, READ-ONLY** pentru un invertor off-grid
**Growatt SPF 6000 ES Plus**. Înlocuiește complet ShinePhone și cloud-ul Growatt.
Un collector Python interoghează invertorul pe Modbus RTU și scrie în InfluxDB;
Grafana vizualizează; ntfy trimite alerte pe telefon; o aplicație Android proprie
(`com.rolling7.solar`) citește dintr-un micro-API JSON.

Totul rulează în Docker Compose pe un HP 290 G4 (Debian 13) din beci, IP `192.168.1.199`.

Utilizator: **Florin**, român. Vezi §„Convenții" pentru limbă.

---

## ⚠️ Invariantul dur: READ-ONLY

Data-loggerul oficial a fost scos pentru că **update-urile firmware OTA au blocat
invertorul de trei ori în două zile**. De aceea:

**Collector-ul nu scrie NICIODATĂ în invertor.**

Singurul apel Modbus din tot proiectul e în `collector/collector.py`:

```python
regs = inst.read_registers(0, REG_COUNT, functioncode=4)   # FC04 = read input registers
```

FC04 este prin definiția protocolului **doar citire**. Nu adăuga FC06/FC16 și nu adăuga
funcții de *control* al invertorului (schimbare mod încărcare, prioritate sursă etc.).
Orice modificare ce ar putea scrie pe portul serial este **interzisă** fără cerere
explicită a utilizatorului care își asumă riscul.

**Subiect ÎNCHIS, nu-l redeschide:**

- *2026-07-10* — buton de încărcare la cerere: **evaluat și refuzat**. Ținta de încărcare
  e deja 56 V, încărcarea e deja doar solară, deci scrierea acelor registre nu schimbă
  nimic. (`COPILOT_CONTEXT.md` §13.14) Din 2026-10-26 ținta de încărcare e **sezonieră**, setată de
  mână de utilizator din panoul invertorului: **57 V iarna** (în vigoare acum), **56 V vara**.
  `BAT_SOC_FULL_V` din `.env` trebuie să o urmeze (vezi „SOC în aplicație").
- *2026-07-18* — cercetat protocolul SPF (documentație oficială + implementări comunitare)
  pentru o comandă „încarcă bateria acum": **nu există**. Încărcătorul solar e un automat
  cu prag intern de re-încărcare nesetabil (≈ float − 2 V). Singurul workaround
  (scriere în holding register 1, comutare ieșire pe rețea) a fost **refuzat de utilizator**.
  (`COPILOT_CONTEXT.md` §13.17)
- *2026-10-22* — **cititorul BMS** (`bms/jk_bms_reader.py`, bateria JK prin Bluetooth LE) e
  tot **READ-ONLY**: trimite doar cererile de date `0x96`/`0x97`. Protocolul JK are și comenzi
  de scriere (praguri, balansare, MOSFET-uri de încărcare/descărcare, parolă) — nu le adăuga.
  `request_frame()` refuză orice altă comandă, iar un test unitar verifică asta.

- *2026-11-05* — serviciul nou **`solar-casa`** *comandă* ceva: cele cinci prize inteligente ale utilizatorului (Tasmota și
  Tuya din 2026-11-10; pornit/oprit, countdown, evenimente, inching), prin LAN. Asta e singurul lucru pe care îl comandă sistemul. Nu atinge
  invertorul și nici BMS-ul, care rămân READ-ONLY; regula de mai sus nu se schimbă pentru ele. Detalii în „CASA".

Recon-ul FC03 (citire holding registers) e o *citire* și nu încalcă invariantul.

---

## Arhitectura

```
Invertor --USB (/dev/growatt)--> collector.py  (minimalmodbus, FC04, 1 dată/secundă)
   |-> InfluxDB bucket `live`     (1s,  retenție 48h)
   |-> InfluxDB bucket `history`  (medii pe 60s, retenție 31 zile)
   |-> ntfy push (alerte pe telefon)
   |-> Grafana dashboard `solar-main`
   `-> solar-api (Flask) --Caddy HTTPS--> aplicația Android
```

**Șapte containere** în `docker-compose.yml`:

| Container | Rol | Port host |
|---|---|---|
| `solar-collector` | Modbus FC04 → InfluxDB + alerte | — |
| `solar-influxdb` | serie de timp, org `casa` | 8086 |
| `solar-grafana` | dashboard `solar-main` | 3000 |
| `solar-ntfy` | push pe telefon, topic din `.env` | 8088 |
| `solar-api` | micro-API JSON pentru Android (citește datele; scrie doar evidența telefoanelor `app_client`) | **niciunul** (intern `api:8000`) |
| `solar-casa` | prizele Tasmota/Tuya și senzorii Tuya (vezi „CASA"); comandă prizele local | **niciunul** (intern `casa:8100`) |
| `solar-caddy` | reverse proxy HTTPS, `tls internal` | 9443 → 443, 8443 |

Toate cu `restart: unless-stopped`, pornesc la boot.

**Lanțul de rețea până la telefon:**
`https://vyra.go.ro:31443` → router ZTE 31443 → `.210:443` → TP-Link 443 → `caddy:443`.
Caddy face `handle_path /solar/casa/*` → `casa:8100`, apoi `handle_path /solar/*` → `api:8000` (taie prefixul `/solar`), restul → Grafana.

### Rețeaua Docker — NU o atinge fără să citești mai întâi

Bridge-ul e fixat pe numele `br-solar` prin `driver_opts:
com.docker.network.bridge.name`. **Nu scoate asta.** Regula firewalld care ține containerele
capabile să vorbească între ele e legată de acest nume de interfață. Fără fixare, Docker
numește bridge-ul `br-<id-rețea>`, iar id-ul se schimbă la fiecare `docker compose down` —
și incidentul din 2026-08-19 se repetă tăcut. Vezi `deploy/README-firewalld.md`.

---

## Protocoale

Un singur protocol atinge invertorul; restul lanțului e HTTP obișnuit.

| Etapă | Protocol | Detalii |
|---|---|---|
| Invertor → server | **Modbus RTU** peste serial RS-232 prin USB | 9600 8N1, timeout 1 s, slave `1`. Adaptor Exar XR21B1411 (`04e2:1411`, driver `xr_serial`), fixat de udev pe `/dev/growatt`. |
| Citirea | **FC04** — Read Input Registers | un bloc de 91 registre, 1 dată/secundă |
| Collector → InfluxDB | HTTP, line protocol InfluxDB v2 | `http://influxdb:8086` |
| API / Grafana → InfluxDB | HTTP + **Flux** | bucket-urile `live` și `history` |
| Telefon → server | **HTTPS** (`tls internal`) + JSON | `https://vyra.go.ro:31443/solar/*` |
| Alerte → telefon | HTTP POST către **ntfy** | topic din `.env` |

Ce **nu** folosim, deliberat: fără ShineServer/cloud Growatt, fără MQTT, fără dongle
ShineWiFi, fără Modbus TCP, fără scriere de niciun fel.

---

## Configurare

Tot ce e secret sau reglabil stă în **`.env`** (gitignored; model în `.env.example`):
credențiale InfluxDB/Grafana, setări Modbus, praguri de alerte, retenții.

Collector-ul primește tot fișierul prin `env_file: ./.env`, deci reglarea unei alerte
= editezi `.env` + `docker compose up -d collector` (fără rebuild).

`REG_COUNT` trebuie să fie **≥ cel mai mare index citit de cod** (acum 88 → 91 e bine).
Dacă îl scazi, `parse()` dă `IndexError`.

---

## Comenzi uzuale

**Nu există suite de teste, linter sau CI** pentru stack-ul de infrastructură. Iterația
se face prin Docker și interogări InfluxDB.

**Excepție: există două suite de teste unitare și trebuie să treacă înainte de release.**
API: `api/test_app.py` și `api/test_system_metrics.py` (`unittest`). Flask nu e instalat pe
gazdă, dar imaginea API-ului îl are. Android: logica pură Kotlin (selecția de pe ENERGIE,
etichetele axei, citirile SISTEM) în `android/app/src/test/`.

```bash
docker run --rm --network none -v /opt/solar-monitor/api:/src:ro -w /src --entrypoint python \
  solar-monitor-api -m unittest
cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk \
  /opt/gradle-8.9/bin/gradle -q :app:testDebugUnitTest
```

Rulează-le după orice modificare în `api/app.py` — inclusiv după adăugarea unui interval nou.

```bash
cd /opt/solar-monitor

docker compose up -d                      # pornește tot
docker compose up -d --build collector    # după editarea collector.py
docker compose up -d --build api          # după editarea api/app.py
docker compose up -d --build casa         # după editarea casa/ (doar variabile CASA_* din .env: up -d casa)
docker compose up -d collector            # după editarea doar a .env (fără rebuild)
docker compose ps
docker compose logs -f collector
docker compose down

# interogare date live (tokenul e în .env)
docker exec solar-influxdb influx query --org casa --token <INFLUXDB_TOKEN> \
  'from(bucket:"live") |> range(start:-30s) |> filter(fn:(r)=> r._field=="battery_voltage") |> last()'

# test push ntfy
curl -d "test" -H "Title: test" -H "Priority: urgent" -H "Tags: zap" \
  http://localhost:8088/Alerta_6Kw

# ce vede telefonul, testat local (Caddy servește pe SNI vyra.go.ro, de aceea --resolve)
curl -sk --resolve vyra.go.ro:9443:127.0.0.1 https://vyra.go.ro:9443/solar/latest
```

**Flux de deploy:** editezi local → `git push` → pe server
`cd /opt/solar-monitor && git pull && docker compose up -d --build`.

Pentru depanarea mapării Modbus: `DEBUG_RAW=1` în `.env`, restart collector, apoi inspectezi
câmpurile brute `r{i}` / `s{i}` (signed) în InfluxDB.

---

## Cum funcționează `collector.py`

Buclă unică, un singur fir, într-un singur fișier. Per ciclu: deschide instrumentul Modbus
(lazy, reconectare la eșec) → un FC04 de `REG_COUNT` registre → `parse(regs)` →
`BalanceDeriver.apply()` → evaluează alertele → pune în coadă un punct pentru `live` și, la fiecare
`HISTORY_INTERVAL`, unul pentru `history` care e **media intervalului** (`HistoryAverager`;
contoarele de energie, codurile de stare și `battery_soc` păstrează ultima valoare) →
`InfluxSink.flush()`.

- **`parse(regs)`** e inima și nu ține stare: mapează registrele brute în câmpuri float cu nume.
  `u32`/`s32` decodează perechi de registre pe 32 de biți (big-endian, complement față de doi).
  Puterea bateriei vine din **Bat_Watt signed int32, registrele 77/78** (codul o neagă, ca să
  păstreze convenția *pozitiv = încărcare, negativ = descărcare*); curenții de
  încărcare/descărcare sunt 83/84.
- **`BalanceDeriver`** (cu stare) calculează tot ce vine din bilanțul de puteri
  `PV + descărcare + rețea→baterie − consum − încărcare`: `inverter_loss` (autoconsum,
  ~100–350 W în funcție de sarcină), `power_deficit`, `grid_import_power`, descărcarea inferată a
  bateriei și din ea `battery_support_power` / `battery_display_power`, plus `house_source`
  (1=PV / 2=baterie / 3=rețea). Bilanțul e un reziduu, nu o măsurătoare: registrele de putere se
  reîmprospătează la ~3–4 s, decalat, deci valoarea instantanee sare 1–4000 W la fiecare variație de
  sarcină. **Bilanțul semnat trece printr-o mediană glisantă pe 60 s** (`BALANCE_SMOOTH_S`) înainte
  de orice decizie; valorile instantanee rămân în `inverter_loss_raw` / `power_deficit_raw` /
  `grid_import_power_raw`. Dacă un deficit vine din rețea sau din baterie se decide din codul de
  stare din registrul 0 (`STATUS_BYPASS`/`STATUS_DISCHARGE`), tensiunea rețelei și modul
  battery-first. `house_source` se schimbă doar dacă noua sursă ține `HOUSE_SOURCE_HOLD_S` (10 s)
  și rutează `output_power` către exact unul dintre `house_pv`/`house_bat`/`house_grid` la fiecare
  ciclu. Cu ambele setări pe 0, rezultatul e identic cu logica veche nefiltrată (verificat pe 26 h
  de date reale).
- **Bypass** (status 8–11, rețea prezentă): casa e direct pe rețea, sarcina nu trece prin invertor. Reziduul de mai
  sus iese atunci negativ (pierdere 0, import prea mic exact cu autoconsumul invertorului). De aceea pe bypass:
  `inverter_loss` = mediana pe 60 s (fereastră proprie, golită la ieșirea din bypass) a părții PV fără sarcină
  (`PV + descărcare + rețea→baterie − încărcare`; dacă iese ≤ 0, estimarea `INVERTER_LOSS_ESTIMATE_W` cu
  `inverter_loss_estimated=1`), iar `grid_import_power` = `output_power`. Măsurat 2026-10-23: PV−încărcare ≈ 87 W,
  la fel ca cei ~100 W pe care îi consumă noaptea pe baterie; importul din rețea era 112 W față de 207 W sarcină.
  `grid_power` = `grid_import_power` + încărcarea AC a bateriei (`grid_charge_power`): tot ce ia sistemul din
  rețea, în W, mediat în `history`. Invertorul n-are contor de energie din rețea, deci barele kWh din aplicație
  integrează puterea (`energy_grid` în API, 2h/1d din `live`, 7d/30d din `history`). Istoricul de dinainte de
  2026-10-23 09:53 UTC a fost completat o singură dată cu aceeași formulă. Teste:
  `docker run --rm --network none -v /opt/solar-monitor/collector:/src:ro -w /src --entrypoint python
  solar-monitor-collector -m unittest`.
- **Alerte** (`build_alerts` / `eval_alerts`): listă de dicționare cu lambda-uri `fire`/`clear`
  peste datele parsate. Fiecare are **debounce** (condiția trebuie să țină `ALERT_DEBOUNCE_S`),
  **cooldown** (`ALERT_COOLDOWN_S` între realertări) și **histerezis** (prag separat de
  revenire); timpii merg pe `time.monotonic()`. Acoperă: consum mare, baterie joasă (doar după
  tensiune) / înaltă (57,5 V), supraîncălzire, ieșire AC pierdută. Separat, un **watchdog** se
  declanșează după `WATCHDOG_FAILS` erori **Modbus** consecutive.
- **`InfluxSink`**: erorile InfluxDB sunt tratate separat de cele Modbus. Punctele au timestamp
  propriu, așteaptă într-o coadă limitată în memorie (`INFLUX_BUFFER_LIVE` ≈ 15 min,
  `INFLUX_BUFFER_HISTORY` ≈ 24 h) și se retrimit la `INFLUX_RETRY_S`. O pană InfluxDB nu mai
  declanșează watchdog-ul „invertorul nu mai răspunde” și nu mai redeschide portul serial;
  alertele ntfy merg în continuare, fiindcă nu trec prin InfluxDB. Respingerile 400/422 se
  aruncă, reîncercarea nu le-ar rezolva.
- **Nu folosi `battery_soc` (registrul 18) în logică.** În 31 de zile a avut doar valorile
  25/50/75/100 și arăta 100% noaptea, cu tensiunea coborând 54 → 49 V (decizie 2026-10-18). Se
  înregistrează în continuare, iar aplicația și Grafana încă îl afișează.

> **Despre documentația de registre:** `COPILOT_CONTEXT.md` e un jurnal de sesiuni ținut de
> mână. Părți din tabelul lui de registre sunt **învechite** — de exemplu documentează puterea
> bateriei prin registrul 90, dar codul a trecut la registrele oficiale Bat_Watt 77/78.
> **Când se contrazic, `collector.py` este sursa de adevăr.**

---

## Aplicația Android

`android/`, Kotlin + Jetpack Compose, package `com.rolling7.solar`, semnată cu
`keystore.properties` + `key-Rolling7` (ambele **gitignored**, nu le comite niciodată).

Două teme, comutabile din SETĂRI, alegerea persistată de `DashboardStyleStore`:
- **Simple** — dashboard clasic (`MainActivity.kt`)
- **Retro** — implicită, panou de instrumente fotografic (`RetroDashboard.kt`)

### Reguli dure pentru UI-ul Retro

1. **Patru taburi fixe, pe tot ecranul, FĂRĂ derulare verticală.** Conținutul fiecărei pagini
   trebuie să încapă deasupra barei foto de navigare. `emulator-check.sh retro-tabs` respinge
   automat orice pagină pe care Android expune un container derulabil.
2. **Fotografia e strict decorativă.** Valorile live, acul cadranului, LED-urile, semantica de
   accesibilitate și zonele tactile rămân Compose nativ și dinamice. Nicio valoare nu se
   lipește în bitmap.
3. **Textul din poze nu se schimbă din cod.** Etichetele desenate în asset-urile WebP
   (numele taburilor, titlurile secțiunilor ENERGIE, etichetele cadranelor) sunt pixeli. Excepții
   pe ENERGIE din v3.28: butoanele de interval, rândul PUTERE/ENERGIE/TENSIUNE și linia de jos
   sunt plăci native opace peste text pictat vechi („7d"/„30d", „PRODUCȚIE ZILNICĂ", „Ultima
   actualizare: ACUM"). Din v3.29 fanta vechilor taburi de sus e scoasă din asset (petic
   ImageMagick, `scripts/patch-retro-energy-top.py`), iar LED-ul verde pictat primește o
   suprapunere recolorată portocaliu/roșu când datele live întârzie. Din v3.30 placa temperaturii de pe
   TABLOU nu mai e folosită ca atare: `scripts/make-retro-blank-dial.py` îi șterge „°C"-ul pictat și face
   `retro_dashboard_dial_blank.webp`, pe care Compose scrie unitățile (%, A, mV) ale citirilor BMS.
   Dacă schimbi valoarea din Kotlin,
   trebuie să repictezi și asset-ul, altfel butonul scrie una și face alta. **Vezi
   `docs/DEZVOLTARE.md` §„Capcana etichetelor din poze".**
4. **Un build Gradle reușit nu e dovadă vizuală.** Rulează emulatorul și **uită-te la PNG**.

### Emulator (verificare UI)

Gazda are un emulator Android headless, accelerat KVM:

```bash
cd /opt/solar-monitor
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify
```

Subcomenzi: `doctor`, `start`, `wait`, `build`, `install`, `launch`, `screenshot`,
`retro-tabs`, `profile`, `layout-check`, `pinch`, `status`, `stop`.

- SDK `/opt/android-sdk`, emulator 36.6.11, platform-tools 37.0.0
- AVD `SolarMonitor_API_34` (Pixel 6, Android 14 / API 34, x86_64, 1080×2400)
- Renderer headless stabil: **`swangle`**. Nu folosi `swiftshader_indirect` — pe gazda asta
  a omorât repetat emulatorul cu `SIGSEGV`.
- Artefactele (PNG, ierarhie UI, logcat) merg în `android/build/emulator-artifacts/` (gitignored).
- ⚠️ **AVD-ul brut NU e telefonul utilizatorului.** Telefonul e un **Samsung Galaxy Note 9**: 1080×2220,
  420 dpi, butoane de navigare Android (48 dp), font de sistem ~130%. Pixel 6 are bara de stare mai înaltă
  (45 dp față de ~25 dp) și navigare prin gesturi, deci pe el layout-ul are cu 20–45 dp *mai mult* loc.
  De aceea `emulator-check.sh verify` și `layout-check` aplică **singure** profilul `note9-large`
  (1080×2272, 420 dpi, butoane, font 130% — zona utilă a aplicației iese ~2028 px, ca pe telefon);
  `SOLAR_EMULATOR_PROFILE=none` îl sare, `profile note9|reset` îl schimbă. Utilizatorul va trece
  ulterior pe un Samsung S22 FE și va anunța și trimite o captură: atunci se adaugă profilul lui.
  `profile reset` la final (suprascrierile rămân în datele AVD). Vezi `COPILOT_CONTEXT.md` §13.56, §13.66.
  Zoom-ul cu două degete se verifică cu `emulator-check.sh pinch` (`zoom-held.png` mărit,
  `zoom-after.png` revenit).
  Ecranele CASA fără serverul real (emulatorul nu are token casa și nu se creează unul pe serverul real): APK debug cu
  `-PcasaBase=http://10.0.2.2:18100` (BuildConfig `CASA_BASE` doar în debug; release-ul folosește mereu producția) și un
  container demo de unică folosință (`docker run -d --rm --name casa-emu-demo -p 127.0.0.1:18100:8100 -e CASA_DEMO=1
  -e CASA_DATA_DIR=/data -v <dir temporar>:/data solar-monitor-casa`, apoi `python -m casa demo-devices` și
  `set-password --stdin` cu o parolă de test). `src/debug/res/xml/network_security_config.xml` permite cleartext doar
  către 10.0.2.2 și repetă ancorele vyra.go.ro (se ține la zi cu cel din main).
- **La finalul oricărei sarcini cu emulatorul rulează `stop`** și verifică oprirea. AVD-ul
  idle mănâncă mai multe nuclee CPU și ~5 GB RAM.

### Release

Regula de versionare: **fiecare APK livrat utilizatorului sau trimis pe Telegram primește
obligatoriu `versionCode + 1` și `versionName + 0.01`**, chiar dacă utilizatorul cere
„rebuild la aceeași versiune". Nu suprascrie și nu retrimite un nume de fișier deja livrat.
Doar un build local de diagnostic, nelivrat, poate păstra versiunea curentă.

Procedura completă (build, verificare `aapt`, SHA-256, trimitere Telegram, consemnare în
`COPILOT_CONTEXT.md`) e în **`docs/DEZVOLTARE.md` §„Release Android"**.

---

## Cititorul BMS (`bms/`)

BMS-ul bateriei: JK `JK_BD6A20S12P` (hw 11U, sw 11.54), pachet 14S. Citit prin Bluetooth LE
cu dongle-ul USB al serverului (TP-Link UB500, Realtek RTL8761B; cere `firmware-realtek`) de
`bms/jk_bms_reader.py` — serviciu systemd **pe host** (`deploy/solar-bms.service`, instalat în
`/etc/systemd/system/`), nu container: are nevoie de BlueZ și de dongle. Dependență: pachetul
Debian `python3-bleak`. Adresa e în `.env` ca `BMS_ADDRESS`.

- Scrie măsurătoarea `bms` (tag `device=jk_bms`, toate câmpurile float) în `live` (~1 s) și
  `history` (medie pe 60 s; `soc`, cicluri și capacități păstrează ultima valoare,
  `charge_mosfet`/`discharge_mosfet` **minimul**, `alarm_bits` maximul — ca o deconectare de
  câteva secunde să rămână vizibilă). Câmpuri: `cell_01..cell_14`, `cell_min/max/avg/delta`,
  `voltage`, `current`, `power`, `temp_mos/1/2`, `soc`, `soh`, `capacity_remaining_ah`,
  `capacity_nominal_ah`, `cycles`, `cycle_capacity_ah`, `charge_mosfet`, `discharge_mosfet`,
  `balancing`, `alarm_bits`.
- Semn: `current`/`power` **pozitiv = încarcă, negativ = descarcă**, ca la invertor
  (verificat 2026-10-22: -47,7 A la BMS față de 48,2 A descărcare la invertor). BMS-ul vede ~0,5 V
  peste invertor la 48 A (cabluri + siguranță ≈ 10 mΩ).
- Offseturi verificate (cadru JK02_32S, 300 de octeți): celule u16@6, mască celule u32@70,
  temperatură MOS@144, tensiune u32@150, curent s32@158, T1@162, T2@164, SOC u8@173,
  rămas/nominal Ah@174/178, cicluri@182, SOH@190, MOSFET-uri@198/199. `balancing` (u8@172, a fost
  2 cu delta de 16–19 mV) și sensul biților din `alarm_bits` (u16@166, încă mereu 0) **nu** sunt
  verificate.
- Se presupune un singur client BLE odată (netestat): oprește serviciul
  (`systemctl stop solar-bms`) înainte de aplicația JK de pe telefon și repornește-l după.
  Aplicația JK cere și locația pornită pe telefon.
- Alerte: regulile și motorul sunt în `bms/bms_alerts.py` (debounce/cooldown/histerezis ca în
  collector): MOSFET oprit (bateria deconectată), `alarm_bits` ≠ 0, celulă prea sus/jos, diferență între
  celule (60 s), temperatură mare/mică, fără date de la BMS 3 min. Fiecare trimite push ntfy pe același
  topic (de pe host, prin `127.0.0.1:8088`) și, cât timp condiția e adevărată, se scrie în fiecare secundă
  ca măsurătoarea `bms_alert` (tag `key`; câmpuri text `title`/`msg`/`prio`, `active=1.0`). `solar-api` o
  servește: `GET /solar/bms` întoarce ultimele câmpuri `bms`, `timestamp` (null fără date în ultimele 15 s)
  și `alarms` (alertele active, adică scrise în ultimele 6 s). Aplicația sună la `prio` urgent/high.
  Pragurile se reglează în `.env` (`BMS_CELL_HIGH_V`, `BMS_CELL_LOW_V`, `BMS_DELTA_HIGH_V`, `BMS_TEMP_HIGH`,
  `BMS_MOS_TEMP_HIGH`, `BMS_TEMP_LOW`, `BMS_LINK_LOST_S`, fiecare cu valoare de revenire `_CLR` — vezi
  `.env.example`).
- Grafana: rândul „BMS baterie (JK)" (live: SOC, tensiune, curent, diferență celule, temperatură, MOSFET;
  tensiuni celule, diferență, curent BMS vs invertor, temperaturi, linie de stare MOSFET/echilibrare) și
  trei panouri BMS la capătul rândului de istoric. Linia de stare trebuie să folosească culori
  `palette-classic`: în modul `thresholds` Grafana ignoră mapările de valori și scrie „1+" / „< 1".
- Serviciul se oprește curat (`systemctl stop solar-bms` deconectează BMS-ul). Un BMS rămas „conectat"
  după un proces ucis nu mai face reclamă; cititorul se conectează atunci direct după adresă, iar
  `bluetoothctl disconnect <adresa>` îl eliberează manual.
- Aplicația (v3.30, temele Retro și Simple): `GET /solar/bms` se interoghează la 2 s
  (`SolarRepository.fetchBms`, parser în `BmsModel.kt`). TABLOU: bandă BMS (SOC, curent, diferența dintre
  celule + LED de stare) sub Bat/Inv, atingerea duce pe SISTEM; placa „Temperatura" a dispărut, iar
  temperatura invertorului e un „29°C" mic sub „Inv". SISTEM: `RetroBmsMonitor` ia locul fostei console
  (bare pe celule + cifre). SETARI: rând nativ „ALARMĂ BATERIE" cu întrerupător propriu
  (`AlarmSettings.batteryEnabled`, implicit oprit); `SolarAlarmService` rulează cât timp e pornită oricare
  alarmă, interoghează `/latest` la 5 s și `/bms` la 10 s (v3.36; înainte 2 s / 4 s) și sună (același sunet/vibrație/cooldown ca la consum) o singură dată
  per alarmă cu `prio` urgent/high (`BmsAlarmTracker`). Pragurile sunt pe server, nu în aplicație.
- Interogări (v3.36): buclele de rețea ale aplicației (`/latest` și `/bms` la 2 s, `/grid-month` la 60 s, istoricul
  de 1h din SISTEM la 60 s) rulează în `WhileVisibleEffect` (`repeatOnLifecycle(STARTED)`), deci se opresc cu aplicația
  în fundal sau cu ecranul stins; acolo interoghează doar `SolarAlarmService`. `MainActivity` e `singleTask`, iar
  intentul din notificare are CLEAR_TOP|SINGLE_TOP. Înainte buclele erau `LaunchedEffect` simple care nu se opreau
  niciodată, iar fiecare atingere a notificării mai deschidea o `MainActivity` cu buclele ei: logul api arăta ~1–3,5
  cereri/s de la un singur telefon, zi și noapte (2026-10-28). Orice buclă nouă de interogare folosește
  `WhileVisibleEffect`, nu `LaunchedEffect`.
- Reparații din audit (v3.37): 20 s fără `/latest` proaspăt (fără răspuns sau cu `timestamp` null) pun `data = null`,
  deci ecranele arată „ASTEPT DATE" / liniuțe în loc de cifre înghețate (textul de pe TABLOU devine roșu); graficele
  ENERGIE se reîmprospătează discret o dată pe minut cât se văd; `AlarmBootReceiver` repornește serviciul de alarmă după
  repornirea telefonului și după o actualizare (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`; un broadcast trimis din shell e
  refuzat de Android 14, o repornire reală merge); serviciul rulează pe firul principal (rețeaua pe IO), un consum mic
  oprește doar sunetul alarmei de consum (înainte tăia alarma bateriei și sunetul de test după o citire) și ignoră
  răspunsurile `/latest` fără timestamp.
- Telefoane și versiuni ale aplicației (v3.38, 2026-10-28): aplicația trimite `User-Agent: SolarMonitor/<versionName>
  (<versionCode>; Android <release>; <Build.MODEL>)` (`SolarRepository.open`). `api/app_clients.py` numără cererile
  aplicației pe telefon (model, Android, versiune; pe rută `latest`/`bms`/`grid_month`/`grid_outage`/`history` plus `requests`) și le scrie
  o dată pe minut în `history`, măsurătoarea `app_client` (taguri `model`, `android`, `app_version`). Aplicațiile până la
  3.37 trimit doar agentul Dalvik, deci apar ca `veche (≤3.37)`; curl, Python și browserele nu se numără. API-ul rulează cu
  **un** worker gunicorn cu 8 fire, ca un singur proces să țină numărătoarea. Grafana: rândul „📱 Telefoane si aplicatie"
  la finalul `solar-main`, tabelul „Telefoane cu acces si versiunea aplicatiei" (un rând pe model: Android, versiune,
  ultima cerere, cereri/min pe 10 min, cereri în 24h). Numele telefoanelor sunt value mappings pe model în acel panou
  (SM-N960U = Note 9 al utilizatorului; SM-G781B = S20 FE al soției); la un telefon nou se adaugă o mapare. Nimic în aplicație.
- Mesaj la căderea rețelei Enel (v3.39, 2026-11-02): notificare fără sunet (canal `solar_grid`, fără vibrație) când pică
  rețeaua, înlocuită de „Curent Enel revenit" cu orele și durata când revine. **Penele le ține minte serverul, nu telefonul:**
  internetul casei cade odată cu rețeaua, pentru că routerul/media-convertorul Digi de la poartă e alimentat din Enel, nu din invertor (2026-11-02: 2–4 min offline la pene de 35–40 s), deci un telefon care compară
  `/latest` n-ar vedea niciodată 0 V. `GET /solar/grid-outage` -> `{down, outages: [{start, end, seconds}], timestamp}`
  (penele terminate în ultimele 24 h, citite din 48 h de `live` ca începutul să nu alunece cu fereastra, epoch în secunde,
  `end` null cât durează, `grid_voltage` < 100 V, ≥ 3 s, probe la ≤ 180 s = aceeași pană; reparat 2026-11-07: citirea pe 24 h
  făcea o pană pe cale să iasă din fereastră să pară nouă la fiecare sondare, 2–3 mesaje „revenit” în plus, iar o revenire
  de 72 s dădea două mesaje). `SolarAlarmService` îl cere la 30 s, iar `evaluateGridOutages` (`GridOutage.kt`) decide ce e nou față de un marcaj din
  prefs; la prima rulare trecutul nu se anunță. Mesajul poate veni cu minute întârziere, dar spune orele reale. SETARI: rândul
  „MESAJ REȚEA ENEL", `AlarmSettings.gridEnabled`, **pornit din oficiu**, deci serviciul din prim-plan merge acum pe orice
  telefon (doar cu asta pornit bucla e la 30 s, nu 5 s), iar aplicația cere permisiunea de notificări la pornire. Dashboard:
  `SolarData.gridDown` din `/latest`: TABLOU arată un X roșu care clipește peste stâlp și „ENEL PICAT" în locul wattilor
  (`RetroGridDownPlate`), Simple „ENEL PICAT" sub stâlp, SISTEM pe rândul rețelei. Se vede doar dacă telefonul ajunge la
  server în timpul penei.
- ENERGIE (v3.32): al patrulea subiect RETEA, cu putere (`grid_power`, W) și energie (`energy_grid`, bare kWh), 2h/1d/7d
  ca la celelalte, accent roșu. Rândul de subiecte (CASA / PANOURI / BATERIE / RETEA) e format din patru plăci goale
  puse în asset de `scripts/patch-retro-energy-subjects.py`; pictogramele și numele le desenează Compose. kWh sub 1
  se afișează cu două zecimale.
- SOC în aplicație (v3.35): tot SOC-ul afișat de aplicație (banda TABLOU, monitorul SISTEM, eticheta BAT, caseta Simple)
  vine din `soc_voltage` din `GET /solar/bms`, **nu** din contorul BMS-ului: liniar din tensiunea bateriei măsurată de
  invertor (media pe 30 s; tensiunea BMS dacă invertorul tace), **1% la `BAT_SOC_EMPTY_V` (48 V), 100% la `BAT_SOC_FULL_V`**
  = pragul de încărcare setat în invertor (57 V iarna, 56 V vara), tăiat la 0–100 (decizia utilizatorului, 2026-10-26).
  `soc` rămâne contorul de Ah al JK (100% la vârful încărcării, dar jos derivă, fiindcă 180 Ah e valoare setată) și e
  tot în Grafana. Când utilizatorul schimbă sezonul în invertor: `BAT_SOC_FULL_V` în `.env`, apoi `docker compose up -d api`
  (fără release de aplicație), **și** `fullV` din cele două panouri de capacitate (vezi mai jos). Sub sarcină mare tensiunea,
  deci și SOC-ul, scade; revine în repaus.
- Capacitate măsurată (Grafana, 2026-10-29): stat-ul „Capacitate baterie: cat s-a scos de la ultima incarcare completa"
  (id 140) și tabelul „Descarcari masurate de la plin" (id 141), la finalul rândului BMS live. Doar Flux pe `history`:
  `current`/`power` din BMS în medii de 5 min, integrate cu `elapsed()` (golurile peste 15 min se sar), net de ce s-a
  reîncărcat între timp. Un ciclu nou începe când tensiunea BMS trece în sus de `fullV`; referința e punctul cel mai plin de
  după, iar „scos net maxim" e cea mai adâncă coborâre față de el (= capacitatea utilă, după ce invertorul a trecut pe rețea
  la ~47,6–47,9 V). `fullV` e o constantă în capul ambelor interogări = pragul de încărcare din invertor − 0,5 V: **56.5
  iarna (57 V), 55.5 vara (56 V)**. Primul rezultat: plin pe 2026-10-25 (56,75 V) până la 47,61 V pe 2026-10-29 = ~125 Ah /
  ~6,4 kWh, față de 180 Ah / 9,3 kWh setați în BMS.
- Factura de curent (v3.33): ENERGIE arată cât s-a luat din rețea de la 1 a lunii (ora Europe/Bucharest), pentru factura
  Hidroelectrica. `GET /solar/grid-month` -> `{month: "2026-10", since, kwh}` (`kwh` null dacă luna n-are încă nicio
  mostră): API-ul integrează `grid_power` din `history` între începutul lunii și acum, aceeași serie ca barele RETEA
  (suma lor pe 7d/30d se potrivește la ~0,01 kWh), deci e estimarea sistemului, nu citirea contorului furnizorului.
  Retro (v3.34, varianta „A" aleasă de utilizator): afișaj VFD galben stins, pe o sticlă mică semi-transparentă, între
  titlu și LED, pe placa de sus (`RetroBillReadout`; luna „OCT" mare cu „FACTURA" mic dedesubt, cifre mari cu segmente,
  „kWh" mic, o zecimală, fără zecimale de la 100 kWh; textele sunt în dp, nu în sp, ca placa să nu crească cu fontul
  sistemului). Simple: un card pe toată lățimea deasupra lui „Produs azi /
  Consumat azi" (`SimpleBillTile`). Aplicația îl cere o dată pe minut (la 10 s după un eșec) și păstrează ultima valoare
  bună. Trece pe luna nouă la miezul nopții din 1 (ora Bucureștiului); încă nu există vedere „luna trecută".
  `layout-check` verifică că afișajul rămâne pe placă.
- Zoom elastic (v3.31): `ElasticZoom.kt` înfășoară tot tabloul de bord. Cu două degete aplicația se mărește
  (până la ~3×, limită moale) și se mută odată cu degetele; la ridicarea degetelor revine singură, cu un mic
  resort, la mărimea inițială — nu rămâne nimic mărit. Un deget singur trece neschimbat (atingeri, glisare între
  taburi); gestul cu două degete e consumat în pasul Initial, deci butoanele și glisarea nu se declanșează.
- Capacitate bateriei / trecerea la LiFePO4 (deschis, 2026-10-23): **`docs/BATERIE.md`** (datele pachetului actual, inclusiv originea
  modulelor: Opel Vivaro C, eticheta MB6VAD, 3 module și jumătate (utilizator, 2026-10-25); anunțurile OLX comparate, de ce chimiile nu se amestecă pe aceeași magistrală, ce ar cere cititorul BMS pentru un al doilea pachet).
  Nu e nimic decis; nu începe lucru la un al doilea BMS sau la praguri LFP fără acordul utilizatorului.
- Teste (fără Bluetooth): `python3 -I -m unittest discover -s /opt/solar-monitor/bms`.
  Citire unică pentru depanare: `python3 -I bms/jk_bms_reader.py --once` (oprește întâi
  serviciul). Jurnal: `journalctl -u solar-bms`.

---

## CASA (prize Tasmota și Tuya, senzori Tuya, `casa/`)

Container `solar-casa` (Python, gunicorn 1 worker × 8 fire, port 8100, **fără port publicat**). Specificații:
`docs/superpowers/specs/2026-11-05-casa-prize-senzori-design.md` (baza), `2026-11-07-casa-evenimente-design.md`
(evenimente), `2026-11-10-casa-tasmota-design.md` (Tasmota); jurnale: `COPILOT_CONTEXT.md` §13.78 (construcția), §13.81 (Tasmota).
**Stare (2026-11-10): mod real (`CASA_DEMO=0`), 5 prize online: 4 Tasmota + 1 Tuya (Bucatarie), parola setată de user. Lista
pașilor de punere în funcțiune de la finalul secțiunii e din 2026-11-05 și n-a fost reverificată după trecerea pe Tasmota.**

- **Două drivere de priză (din 2026-11-10, v3.45):** o priză e `tuya` (implicit; `tinytuya`, `casa/casa/plugs.py`) sau
  `tasmota` (HTTP în LAN, `casa/casa/tasmota.py`); elementele din `GET /plugs` au `"driver"`. Ambele stau în spatele
  aceluiași protocol `PlugDriver` (`connect/status/set_dp/close`), iar controllerul vorbește în „DP-uri” cu amândouă
  (driverul Tasmota le mapează: `{"switch": 1, "countdown": 9, "current": 18, "power": 19, "voltage": 20, "inching": 44}`).
  Userul a trecut prizele de pe Tuya pe **Eightree ET28 16 A cu Tasmota** (fără cloud, fără MQTT, fără SmartLife); prizele
  Tuya vechi sunt în sertar ca **rezervă**, deci suportul Tuya rămâne (nu-l șterge). Prizele de acum (mapare finală, user,
  2026-11-10 14:07):

  | Nume în aplicație | Driver | IP | Id dispozitiv (rămâne = stare, evenimente, ordine, istoric Influx) |
  |---|---|---|---|
  | Boiler | tasmota nr1 | .186 | `bf7b3c62e47bfd15fczfov` |
  | Living sus | tasmota nr2 | .234 | `bf5cc0f2db15b9eab2a0zy` |
  | Priza cam Georgi | tasmota nr3 | .170 | `tasmota_431ea4` (nou, intrare de sine stătătoare; vechiul id aparține acum Bucătăriei, iar istoricul Influx al lui Georgi dinainte de 14:07 rămâne sub el) |
  | Lumina Curte | tasmota nr4 | .188 | `bf3897ad46cd66d32cww3j` (inching-ul de 30 min stă în priză, `PulseTime1`) |
  | Bucatarie | tuya „WiFi Plug”, cu contor | .104 | `bf3bac46d1a367e845thx2` (MAC fc:67:1f:7a:04:dd; până la 14:07 a fost Priza cam Georgi, deci istoricul Influx de dinainte e al lui Georgi; inching nativ DP 44 = 20 min, setat de user) |

  T34 Bucatarie (`bfc30380ff8a29c0d88wcz`, fără contor) e retrasă: `"hidden": true`. MAC-urile prizelor Tasmota și tot ce
  ține de punerea lor în funcțiune sunt în `COPILOT_CONTEXT.md` §13.81.
- **Ce face (partea Tuya):** prize Tuya comandate local (`tinytuya`, TCP 6668, o conexiune persistentă per priză; IP și protocol implicite
  în `casa/casa/devices.py`, potrivite după device id: Boiler .204, Living sus .244, Priza cam Georgi .104, Bucatarie .185,
  Lumina Curte .158 (confirmate de primul `sync` real, 2026-11-05; Bucatarie și Lumina Curte sunt „T34-Smart Plug”,
  protocol 3.4, fără măsurare, categoria Tuya `tdq`); azi se folosește doar .104, restul sunt rezervă). 3 senzori de temperatură/umiditate din cloud-ul Tuya (`tuya-device-sharing-sdk`, sesiune din login QR). Senzorii
  reali („T & H Sensor”, categoria `tdq`) vin **fără nicio stare prin REST**; valorile sosesc **doar prin MQTT**, ca DP-uri
  numerotate pe care SDK-ul le aruncă (n-au strategie locală): **27 = temperatură ×10, 46 = umiditate %, 101 = baterie
  `low`/`middle`/`high`** (`SENSOR_DP_CODES` în `cloud.py`, măsurat 2026-11-05). casa pune `set_up=True` pe senzori ca
  SDK-ul să-i aboneze și decodează singur mesajele brute (`_on_raw_message`). Un senzor pe baterie se trezește și trimite
  cam **o dată pe oră**; o valoare lipsă nu șterge una știută, iar la pornire ultimele valori vin din InfluxDB
  (`sensor_last`, 7 zile). Citirea REST la 5 min decide în continuare starea cloud: `ok` doar dacă ultima citire REST
  reușită are ≤ 10 min. Fără
  rezervă prin cloud pentru comenzile prizelor: o priză care refuză conexiunea locală apare OFFLINE.
- **Dispozitivele se încarcă o singură dată, la pornire** (`casa/casa/web.py`). După `login`/`sync`, o redenumire în
  SmartLife sau o editare a lui `casa/data/devices.local.json` (`{"<id>": {"ip": "...", "version": "3.3", "name": "...", "hidden": true, "driver": "tasmota"}}`;
  `hidden` scoate un dispozitiv din aplicație, ex. „Boiler Buc”, nefolosit, în sertar; o intrare greșită se sare cu
  avertisment în log) → `docker compose restart casa`.
- **`devices.local.json` și driverul Tasmota:** `"driver": "tasmota"` + `"ip"` pe o intrare cheiată după **id-ul Tuya** al
  prizei o face priză Tasmota și păstrează tot ce atârnă de id (nume, evenimente, inching, locul în listă, istoricul
  Influx, telefoanele). Drivere valide: `tuya` (implicit), `tasmota`; un driver necunoscut sau `tasmota` fără `ip` sare
  intrarea cu avertisment, iar pornirea continuă. O intrare cu `driver: tasmota` **plus `name` și `ip`** e suficientă singură:
  dacă id-ul ei nu e în `devices.json` (priza Tuya ștearsă din SmartLife sau o priză care n-a avut niciodată cont Tuya, ca
  Priza cam Georgi `tasmota_431ea4`), priza rămâne (kind `plug`). **Un asemenea id singur trebuie să respecte
  `^[A-Za-z0-9_]+$`** (aflat live 2026-11-10): garda de id din Influx (`casa/casa/influx.py`, `_ID`) respinge un `-`, deci
  interogarea de energie dădea ValueError și nu se scriau puncte pentru priză; `tasmota-431ea4` a trebuit redenumit
  `tasmota_431ea4`. `scan` / `apply_scan` nu ating niciodată intrările `driver: tasmota` (ar scrie IP-ul vechi Tuya peste
  cel Tasmota). **Înapoi pe Tuya:** scoți `driver` și `ip` din intrare (IP-ul Tuya vine atunci din `devices.json` /
  `BUILTIN_DEFAULTS`), pui priza Tuya la loc și `docker compose restart casa`; o intrare singură ca `tasmota_431ea4` nu are
  geamăn Tuya sub id-ul ei.
- **`casa/data/tasmota.json`** (mod 600, ignorat de git ca restul lui `data/`; `{"password": "..."}`): unica `WebPassword`
  Tasmota (utilizator `admin`), aceeași pe toate prizele Tasmota; fără fișier = fără parolă. Se setează cu
  `docker exec -i solar-casa python -m casa tasmota-password --stdin` (sau fără `--stdin`, cerută de două ori; niciodată în
  argv), apoi `docker compose restart casa` (se citește o singură dată, la pornire; doar dacă există cel puțin o priză
  Tasmota). Parola merge în URL-ul cererii (`/cm?cmnd=...&user=admin&password=...`), deci **nicio eroare, niciun log și
  niciun `PlugReading.error` nu are voie să conțină URL-ul sau parola**: mesajele sunt `priza <ip>: <fel>`, doar din tipul
  erorii (fără `str(exc)` de la `urllib`, fără `__context__`, fără redirecționări, fără proxy). O priză care răspunde 401/403
  (serverul n-are parola sau o are greșită) apare OFFLINE cu „parola Tasmota respinsa”. Un `tasmota.json` lipsă/corupt scrie un
  avertisment (fără conținut) și pornirea continuă fără parolă (prizele care au parolă arată atunci mesajul offline de mai
  sus). `WebPassword` pe priză se setează de mână la punerea în funcțiune (prin HTTP, dintr-un fișier, nu din argv/istoricul
  shell-ului); serverul nu o scrie niciodată.
- **Comportamentul Tasmota (`casa/casa/tasmota.py`):** HTTP `http://<ip>/cm?cmnd=...`, `urllib` din stdlib, timeout 3 s, cel
  mult 3 cereri pe citire (`Power`, `Status 10` pentru contor, `PulseTime1`; `Mem1`/`Mem2` se citesc la `connect()` și se
  țin în cache). În priză nu se stochează decât `PulseTime1`, `Mem1`, `Mem2`: **nicio regulă sau timer în priză**, toate
  programările sunt ale serverului. Semantica evenimentelor/countdown-ului/inching-ului de mai jos e aceeași pentru ambele drivere.
  - **`PulseTime1` e termenul de siguranță** (echivalentul DP 9 de la Tuya): `0` = oprit, `1..111` = zecimi de secundă,
    `112..64900` = valoare − 100 de secunde. Se aplică la fiecare pornire a releului și, scris cât releul e **pornit**,
    repornește numărătoarea pe loc. `PulseTime1 Remaining` are **aceeași codificare** ca `Set` (verificat pe o priză reală,
    Lumina Curte, 2026-11-10: pornită la 14:07:52, 24 s mai târziu `Remaining` 1875 = 1775 s, așteptat 1776), deci
    countdown-ul se citește din el. **Plafon: `TASMOTA_MAX_S` = 64800 s (18 h)** pe priză, față de 24 h (`DP_MAX_S` = 86400) la
    Tuya; `_dp_max_s` din `controller.py` îl alege per dispozitiv, iar un termen mai departe decât plafonul **nu** se scrie
    (îl execută serverul). Testat: 20 h nu se scrie pe Tasmota, 17 h se scrie, 20 h se scrie pe Tuya.
  - **Inching** (echivalentul DP 44): `Mem1` = durata în secunde (păstrată și când e oprit, ca aplicația să nu piardă durata
    aleasă), `Mem2` = `1`/`0` activ. *Baza* = `Mem1` dacă `Mem2 == 1`, altfel 0. Cu releul **oprit**, `PulseTime1` trebuie să
    fie egal cu *baza*, ca butonul fizic să aplice inching-ul și cu serverul căzut (așa merge cele 30 min ale Luminii Curte).
    `Mem1`/`Mem2` goale (`""`, starea de fabrică) sau gunoi (`abc`, `-5`, `0`, peste 64800) -> driverul **nu** raportează DP 44
    și nu se auto-vindecă (serverul păstrează valoarea din `state.json`). Punerea în funcțiune trebuie să inițializeze
    `Mem1`/`Mem2` pe fiecare priză **înainte** de deploy-ul casa (altfel o apăsare de buton după un termen scris de server ar
    opri priza prea devreme). Verificat pe o priză reală: `Mem2 0` stochează „0”, `Mem2 "` îl șterge. Odată scrisă, priza e
    sursa de adevăr (ca la Tuya, v3.43).
  - **Auto-vindecare:** dacă la o citire releul e oprit și `PulseTime1 Set` != *baza*, driverul rescrie *baza* (un termen
    de siguranță rămas de la ultima pornire nu are voie să oprească prea devreme următoarea pornire din buton). O vindecare
    eșuată se loghează o dată și nu scoate priza offline.
  - `set_dp(9, N)`: `N > 0` scrie `PulseTime1` = N s; `N = 0` scrie *baza*. `set_dp(44, ...)` scrie `Mem1`, `Mem2` și
    `PulseTime1` = *baza* doar dacă releul e oprit.
- **Măsurare și calibrare (Tasmota):** contorul BL0937 al fiecărei ET28 a fost calibrat cu **același calorifer electric**
  (treapta 2, rezistiv; referința nr2 ≈ 1157 W / 4,947 A, invertorul arăta ~1100–1150 W). Valorile din fabrică citeau cu
  4–8 % peste la nr1, nr3, nr4, iar **priza Tuya veche citea cu ~10 % peste** (Living sus 1285 W pe aceeași sarcină). Nu
  recalibra Tasmota după o citire Tuya. Valorile și istoricul pe priză: `COPILOT_CONTEXT.md` §13.81.
- **Cele trei „WiFi Plug” Tuya (Boiler, Living sus, Priza cam Georgi; azi se folosește doar ultima, acum Bucatarie .104) nu răspund la ARP** (2026-11-05): se anunță în rețea,
  dar `ip neigh` arată FAILED, deci ping și TCP 6668 eșuează. O intrare statică (`ip neigh replace <ip> lladdr <mac> nud
  permanent`) rezolvă. `deploy/tuya_arp.py` (pe host, root) ascultă ~25 s anunțurile Tuya, ia IP + MAC din cadrul Ethernet
  și pune intrările; șterge una când MAC-ul trece pe alt IP sau nu mai e văzut 24 h (stare în `/var/lib/solar-tuya-arp/`).
  Rulat de `deploy/solar-tuya-arp.timer` (la pornire + la 10 min); instalare: `cp deploy/solar-tuya-arp.{service,timer}
  /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now solar-tuya-arp.timer`. Teste: `python3 -I -m
  unittest discover -s deploy -p 'test_tuya_arp.py'`.
- **Inching în firmware-ul prizei (Tuya)** (DP 44 `switch_inching`, base64 pe 3 octeți: activ, secunde big-endian; mapat ca
  `dps["inching"]` de `sync`): Bucatarie 25 min și Lumina Curte 30 min au fost pornite **intenționat** (user, 2026-11-05; din
  2026-11-10 Bucatarie e priza veche cu contor, cu 20 min, iar cele 30 min ale Luminii Curte stau într-o priză Tasmota).
  Din v3.43 priza e sursa de adevăr (alegerea userului): fiecare citire copiază DP 44 în setarea `inching` a prizei
  (`_sync_native_inching`), deci o schimbare din SmartLife apare și la noi, iar `PUT .../inching` scrie întâi DP 44 (o
  scriere eșuată nu schimbă nimic). Serverul își ține și termenul lui de inching, ca „STINGE ÎN …” să apară și DP-ul de
  countdown de siguranță să fie de acord cu firmware-ul. Prizele fără DP 44 păstrează inching-ul doar pe server. Prizele Tasmota
  își raportează inching-ul prin `Mem1`/`Mem2`, la fel (vezi mai sus).
- **Nume editabile din aplicație (v3.43):** atingerea titlului din detaliul prizei/senzorului (creionul).
  `PUT /plugs/<id>/name` și `PUT /sensors/<id>/name` (1–24 caractere, `clean_user_name`) scriu `name` în
  `devices.local.json`; numele prizei se schimbă imediat în memorie, al senzorului prin `Services.names`. SmartLife își
  păstrează numele lui; în Influx eticheta prizei ia numele nou de atunci înainte (istoricul e după `device_id`).
- **Lista compactă de prize (v3.43):** în Retro CASA rândurile au fix 54 dp (până la 7 prize, `CompactPlugRows`; peste,
  își împart cardul), cel mult 2 rânduri de insigne, iar prizele fără contor nu au placa de wați (Retro și Simple).
- **Internetul tăiat prizelor (probă din 2026-11-05 ~18:45, alegerea userului; depășită pentru cele patru prize Tasmota, care n-au deloc cloud; profilul n-a fost reverificat la trecerea din 2026-11-10):** scopul e să nu mai depindem de
  SmartLife / cloud-ul Tuya. Access Control (lista neagră) din router **nu** se poate folosi: taie și rețeaua locală
  (Lumina Curte a căzut pentru casa, anulat imediat). Ce merge: Parental Controls, profilul „Prize fara internet”
  (`owner_id` 0) cu `internet_blocked` pornit (API `admin/smart_network?form=patrol_owner_list|patrol_owner_block`, vezi
  memoria routerului / COPILOT §13). Deocamdată doar Lumina Curte e în profil: comanda locală merge, cloud-ul Tuya o vede
  offline. Dacă rămâne stabilă ~o zi, se adaugă și celelalte patru; SmartLife (și programările lui) nu mai merg pentru ele.
  Anulare: `internet_blocked=false` sau scoaterea prizei din profil.
- **Mai târziu, nu acum (user, 2026-11-05):** senzori Zigbee (stick USB + zigbee2mqtt), ca senzorii să nu mai depindă de
  cloud-ul Tuya; OpenBeken pe prizele Tuya (tuya-cloudcutter sau prin serial) „când avem timp” (în mare parte depășit: patru
  prize sunt Tasmota din 2026-11-10); prize viitoare cu ESPHome (sau încă Tasmota), ca asocierea să se facă din aplicația
  noastră. Un buton (+) „adaugă dispozitiv” are rost doar cu acestea; cu prize Tuya de fabrică asocierea rămâne în SmartLife
  + `scripts/casa-sync.sh`.
- **Adăugarea unei prize Tasmota noi:** (1) verifici firmware-ul (16.0.0) și modulul/template-ul: template-ul `EIGHTREE` are
  GPIO `[0,0,0,2720,2624,32,224,2656,0,…,320,7648]`, iar unele unități vin cu modul generic `ESP32C3`, fără releu și fără
  măsurare (unitățile 15.1.0: nr2, nr3), deci aplici template-ul; `VoltageSet 230`; (2) calibrezi pe calorifer
  (`PowerSet`/`CurrentSet`, vezi §13.81); (3) rezervare DHCP după MAC; (4) `WebPassword` = cea din `tasmota.json` (verifici
  401 fără / 200 cu); (5) `Mem1`/`Mem2` + `PulseTime1` = *baza*; (6) intrare în `devices.local.json` (`driver`, `ip`, plus
  `name` dacă id-ul nu e în `devices.json`; un id nou trebuie să respecte `^[A-Za-z0-9_]+$`), cu copie de siguranță a lui
  `state.json` și `devices.local.json` înainte dacă starea se mută între id-uri; (7) `docker compose restart casa` (sau
  `up -d --build casa` după modificări de cod). Fără `scan`, fără `sync`.
- **Adăugarea unei prize/unui senzor Tuya nou:** (1) îl asociezi în SmartLife; (2) `scripts/casa-sync.sh` (= `sync`, apoi `scan`,
  apoi `docker compose restart casa`). `scan` rulează într-un container temporar cu `--network host` (anunțurile UDP ale
  prizelor pe 6666/6667/7000 nu ajung în rețeaua bridge), ascultă ~20 s și scrie în `devices.local.json` IP-ul și protocolul
  fiecărei prize din `devices.json` a cărei adresă diferă (un `name` pus de mână rămâne); ce nu e în `devices.json` apare ca
  „Necunoscut". `--scan-only` sare peste `sync` (o priză și-a schimbat IP-ul). În firewalld, zona `public` are trei reguli rich
  pentru asta, **doar din LAN** (`192.168.1.0/24`, UDP 6666, 6667, 7000; aprobate de user 2026-11-05). Apoi rezervare DHCP
  pentru IP-ul nou. Apare în aplicație, fără APK nou. Pagina CASA din Retro e făcută pentru **cel mult 5 prize și 3 senzori**.
- **Rută:** Caddy `handle_path /solar/casa/*` → `casa:8100`, pusă **înaintea** lui `/solar/*`. `request_body max_size 16KB`,
  `request_buffers 20KB` (trebuie să rămână mai mare decât max_size; `MAX_BODY_BYTES` 16000 din Flask rămâne ≤ max_size),
  timeout citire/scriere 60 s (o comandă durează ≈ 25 s în mod obișnuit, 45 s în cel mai rău caz teoretic; aplicația așteaptă 50 s), `header_up X-Forwarded-For
  {remote_host}` (IP-ul clientului pentru limita de parolă). Doar blocul casa din `caddy/Caddyfile` e al nostru; restul e
  al proiectului beci-cloud și se comite separat (`git add -p`); fișierele `Caddyfile.bak-*` se ignoră (`.gitignore`).
- **Autentificare:** o singură parolă comună, setată pe server; `POST /solar/casa/register` `{password, device}` dă un
  token lung per telefon (serverul păstrează doar hash-uri). Orice altă rută cere `Authorization: Bearer`. Limită: 5
  încercări greșite / 15 min per IP și 20 / oră în total (deci plafonul global poate bloca **noi** înregistrări până la o
  oră; telefoanele deja înregistrate nu sunt afectate); comenzi: 3 la 3 s per telefon, doar cererile non-GET. Un 401 pe un token salvat (revocat) readuce placa de parolă în aplicație.
- **CLI** (în container): `docker exec -it solar-casa python -m casa <comandă>`:
  `login [cod_utilizator]` (login QR Smart Life: Eu > Setări > Cont și securitate > User Code, apoi scanezi QR-ul din
  terminal), `sync` (reîmprospătează dispozitivele și cheile locale în `devices.json`), `set-password [--stdin]` (se cere
  de două ori, niciodată în argv; schimbarea nu deconectează telefoanele), `tasmota-password [--stdin]` (`WebPassword`
  Tasmota în `casa/data/tasmota.json`, vezi mai sus), `phones` (telefoanele înregistrate),
  `revoke <phone_id>`, `demo-devices [--force]` (`devices.json` fals pentru `CASA_DEMO=1`; refuză să suprascrie chei reale
  fără `--force`), `scan` (IP-urile prizelor din LAN; merge doar cu `--network host`, deci prin `scripts/casa-sync.sh`).
- **Directorul de date `casa/data/`** (ignorat de git, montat la `/data`, fișiere cu mod 600): `devices.json` (**chei
  locale**), `devices.local.json` (nume, IP-uri, `hidden`, `driver`), `tasmota.json` (**parola Tasmota**), `cloud_session.json` (**sesiunea cloud**), `auth.json` (hash de parolă + hash-uri de token), `state.json`
  (programări, countdown, inching; mod real) / `state.demo.json` (mod demo, poartă un marcaj demo; modul real **refuză** o
  stare marcată demo, ca programările de test să nu poată porni niciodată boilerul real). Secretele nu ajung niciodată în
  git, log-uri, ntfy sau Telegram (nu se loghează text de excepție cu token QR / cod de utilizator).
- **Mod demo:** `CASA_DEMO=1` în `.env` (**oprit din 2026-11-05**, `CASA_DEMO=0`): prize false din `casa/casa/demo.py`, fără scrieri în
  InfluxDB, fără cloud. Scoate-l (și `docker compose up -d casa`) pentru funcționarea reală. Serviciul `casa` **nu**
  primește tot `.env`: `docker-compose.yml` îi dă doar `INFLUX_*`, `NTFY_URL`, `CASA_DEMO` (implicit 0), `CASA_POLL_S`
  (implicit 5) și `CASA_DATA_DIR`. O variabilă `CASA_*` nouă trebuie adăugată și acolo. Dacă fișierul de stare e marcat
  demo, modul real iese cu codul 4 al gunicorn (eroare la încărcarea aplicației), deci gunicorn se oprește și se aplică
  backoff-ul de restart al Docker.
- **Semantică — evenimente (din 2026-11-07, v3.44; spec `docs/superpowers/specs/2026-11-07-casa-evenimente-design.md`):**
  ora Europe/Bucharest. O priză are cel mult **12 evenimente**: `HH:MM` + PORNEȘTE/OPREȘTE (`action` on/off), **zilnic**
  (`days: null`) sau în zilele ISO alese (1=luni..7=duminică), activ/inactiv; două evenimente active la aceeași oră cu zile
  suprapuse sunt refuzate („conflict"). Un eveniment acționează **doar la ora lui**; între evenimente priza ascultă de aplicație și
  de butonul ei. Evenimente ratate (server oprit, priză offline, comandă eșuată): contează doar **cea mai recentă apariție
  scadentă** (`last_event_at`); o comandă manuală mai nouă o consumă; un **OPREȘTE ratat se execută oricât de târziu** (lookback 2
  zile) și se reîncearcă fără limită; un **PORNEȘTE ratat doar până la următorul eveniment** al prizei. Un eveniment adăugat/editat/
  activat la o oră deja trecută azi nu acționează azi (`event_cutoff`). **Butonul fizic al prizei contează ca manual** (o schimbare
  pornit/oprit văzută între două citiri online pe care serverul n-a comandat-o în ultimele 15 s; o apăsare cât priza e offline nu se
  vede). Cât timp priza e pornită (din orice sursă), **cel mai apropiat termen de oprire** (următorul OPREȘTE activ, inching,
  countdown „oprește") se scrie în DP-ul de countdown al prizei (dp 9 doar de rezervă; DP-ul real vine din `sync`), ca priza să se
  stingă și dacă serverul cade — dar **doar când e la ≤ 24 h (Tasmota: ≤ 18 h, `PulseTime1`)** (un OPREȘTE pe zile, peste câteva zile, nu se scrie; un countdown
  plafonat la 24 h ar opri-o prea devreme). Ștergerea unui OPREȘTE cât priza e pornită o lasă pornită. O comandă manuală anulează
  countdown-ul în curs. Intervalele vechi au fost convertite o dată în perechi PORNEȘTE/OPREȘTE (copie
  `casa/data/state.json.bak-2026-11-07`). Lock-uri și throttle per priză: o priză moartă nu întârzie oprirea alteia; ține un fir ≈ 25 s
  obișnuit / 45 s în cel mai rău caz. Rute: `POST /plugs/<id>/events` `{time, action, days?, enabled?}`, `PUT`/`DELETE
  /plugs/<id>/events/<eid>`; elementele din `GET /plugs` au `events`, `next_event` și `schedules: []` gol (pentru aplicațiile ≤ 3.43).
- **Ordinea prizelor (v3.44):** aceeași pe toate telefoanele: cheia `plug_order` în `state.json`, `PUT /plug-order` `{"order": [id-uri]}`
  (necunoscute/duplicate → 400; cele lipsă se pun la sfârșit) întoarce corpul lui `GET /plugs`. Aplicația: apăsare lungă pe un rând
  de priză în SISTEM → CASA → vibrație, rândurile tremură, tragi ca să muți, se salvează la ridicarea degetului, „GATA" sau o
  atingere în afara listei ies din mod.
- **InfluxDB:** `casa_plug` (tag-uri `device_id`, `name`; câmpuri `on`, `online`, `power`, `voltage`, `current` în A) și
  `casa_sensor` (câmpuri `temperature`, `humidity`, `battery`) în `live` și `history`. Senzorii se scriu **la fiecare
  schimbare** în ambele găleți (nu medii pe 60 s); istoricul prizelor e pe minut: `on`/`online` = minimul, `power`/
  `voltage`/`current` = media. Le scrie un singur fir de flush, deci un InfluxDB căzut nu întârzie comenzile. Energia azi din API = integrala lui `power` de la miezul
  nopții local. Grafana: rândul „🏠 Casa: prize si senzori" (panourile 142–147: putere prize, prize pornite,
  temperatură/umiditate senzori, senzori acum).
- **Aplicația (v3.40, versionCode 44; v3.41 sondarea veche, v3.42 vibrație, v3.43/47 listă compactă + redenumire; v3.44/48 editor de evenimente + ordinea prizelor; v3.45/49 eticheta driverului + detaliu compact):** pagina SISTEM din Retro are comutatorul SISTEM | CASA; CASA = 5 plăci de priză +
  senzori, atingerea deschide detaliul prizei (comutator, countdown 30M/1H/2H/6H/altă durată, programări, inching) sau al
  senzorului (grafice 2h/1d/7d). Tema Simple: un card CASA. SETARI: rândul „ACCES CASA" cu „UITA TELEFONUL" (șterge doar
  token-ul local; serverul ține telefonul până la `revoke`). Placa de parolă apare la prima deschidere. Timeout comandă 50 s;
  comenzile rămân active chiar dacă priza pare offline. Fișiere: `CasaModel.kt`, `CasaRepository.kt`, `CasaAccess.kt`,
  `RetroCasa.kt`, `RetroCasaDetail.kt`, `SimpleCasa.kt`. **Ordinea sondare/comandă (v3.41):** un `GET /plugs` plecat
  înainte să se termine o comandă poate sosi după răspunsul comenzii, cu starea veche (comutatorul revenea ~3 s, Living
  sus, 2026-11-05). `CasaFreshness` (`CasaModel.kt`) dă fiecărei sondări un bilet; răspunsul ei se folosește doar dacă
  între timp n-a început și nu s-a terminat nicio comandă. Orice cale nouă de comandă trebuie să apeleze
  `commandStarted/Finished`. Log-ul de acces casa arată durata fiecărei cereri (`… 200 240 1.234s`). **Vibrație
  (v3.42):** `CasaHaptics` vibrează o dată (70 ms) când serverul confirmă comutarea, de două ori la eșec.
  **v3.45 (versionCode 49, versiunea curentă a aplicației; doar detaliul prizei, `RetroCasaDetail.kt` + `CasaModel.kt`):**
  `CasaPlug.driver` (din `GET /plugs`, `"tuya"` dacă lipsește); subtitlul arată `PRIZA 3/5 · CONTORIZATA · WIFI OK ·
  TASMOTA` (sau `· TUYA`, numele driverului în alamă; offline: `PRIZA 3/5 · WIFI OFFLINE · TASMOTA`; eroarea și „FARA
  LEGATURA” înlocuiesc tot subtitlul). Antetul e pe **un singur rând**: comutatorul (PORNIT/OPRIT sub el) | placa de wați
  mai mică (46 dp) | placa kWh `AZI`; fără contor -> „PRIZA FARA CONTOR” în locul celor două plăci; linia albastră „OPRIRE IN
  …” rămâne sub rând. „PROGRAMARI EVENIMENTE” are un singur stil de titlu; rândurile de eveniment au ~40 dp, deci **5
  evenimente încap fără derulare** pe profilul `note9-large` (~4 dp rezervă; al 6-lea derulează în interiorul cardului, ca
  înainte). INCHING e pe **un singur rând**: „INCHING” cu „OPRIRE AUTOMATA” mic dedesubt, comutatorul, `−`, `30 MIN`, `+`;
  textul explicativ a trecut în descrierea de accesibilitate a comutatorului. Lista prizelor (SISTEM → CASA) și tema Simple
  rămân neschimbate (fără etichetă de driver acolo). Textele noi sunt Compose, nimic pictat în WebP.
- **Citiri vechi pe socketul persistent (reparat 2026-11-05):** tinytuya întoarce URMĂTORUL mesaj din socket, nu
  răspunsul cererii; anunțurile nesolicitate ale prizei (countdown, putere) decalau răspunsurile cu unul, deci fiecare
  citire la 5 s aducea starea citirii precedente și comutarea părea neexecutată ~5 s. `TinyTuyaDriver._drain` golește
  socketul (timeout 0,05 s) înainte de fiecare `status()` și `set_dp()`. Măsurat după reparație pe Lumina Curte: starea
  corectă la 0,2 s după comandă.
- **Teste:** `docker run --rm --network none -v /opt/solar-monitor/casa:/src:ro -w /src --entrypoint python
  solar-monitor-casa -m unittest discover -s tests -v` (482 de teste; teste unitare Android: 144; rulează după orice modificare în `casa/`, suita
  completă de mai multe ori: un eșec nereproductibil a apărut o dată în timpul editărilor).
- **Pașii de punere în funcțiune:** făcute 2026-11-05: login + `sync`, mod real, toate prizele online, parola, timerul ARP.
  Rămân: (1) rezervări DHCP în router pentru cele 5 IP-uri; (2) confirmarea unității curentului cu un consumator pornit
  (mA Tuya / 1000); (5) înregistrează telefoanele (Note 9, S20 FE, S26) din aplicație și verifică
  `phones`; (6) teste live de comutare, countdown, programare, inching pe o priză (boilerul la urmă); (7) **șterge
  programările echivalente din SmartLife** după ce merg ale noastre, altfel se bat între ele. (2026-11-10: cele patru prize
  Tasmota au rezervare DHCP după MAC și nu au SmartLife; (2) s-a lămurit pentru ele prin testul cu caloriferul, curentul vine
  în A din `Status 10`; (7) contează doar pentru prizele Tuya care revin în folosință.)

---

## Convenții

- **Limba:** comentariile, documentația (`README.md`, `COPILOT_CONTEXT.md`, `docs/`),
  mesajele de log și cheile/titlurile alertelor sunt în **română**. Numele câmpurilor
  InfluxDB sunt în **engleză** (`battery_voltage`, `output_power`, …). Respectă asta.
- Toate puterile se raportează și se afișează în **wați** (nu kW).
- Fișierele documentate/afișate în UI folosesc diacritice; codul sursă și scripturile shell
  existente sunt scrise fără diacritice — păstrează stilul fișierului pe care îl editezi.

---

## Capcane cunoscute

- **Discul de sistem are erori reale de mediu (2026-11-02).** GIGABYTE GP-GM30512G-G: SMART numără 519.330 „Media and Data
  Integrity Errors", deși spune PASSED și 0% uzură. O eroare de citire pe `kotlin-gradle-plugin-2.0.21-gradle85.jar`
  (`critical medium error`, sector 904624024) a apărut într-un build Gradle la 14:04, cel mai probabil „bus error"-ul văzut
  atunci; jurnalul se oprește acolo, serverul a revenit la 14:16 (cauza pauzei nu e în jurnal). La `Bus error`/SIGBUS sau
  `Could not read workspace metadata` de la Gradle, caută întâi `journalctl -k | grep 'medium error'` și mapează sectorul pe
  fișier (`debugfs icheck`/`ncheck`). Jar-ul stricat e ținut în `/root/.gradle/bad-sector-quarantine/`, ca blocurile să rămână ocupate.
  O copie completă a discului (rootfs + EFI, ultima trecere cu containerele oprite, 2026-11-02 15:58, fără folderul de carantină) e pe HDD în `/data/backup-nvme-2026-11-02/`.
- **firewalld taie traficul dintre containere.** Dacă aplicația arată „aștept date" iar
  logurile collector-ului par perfect sănătoase, verifică `firewall-cmd --get-active-zones`
  **înainte** de a bănui invertorul — `br-solar` trebuie să fie în zona `docker`.
  `Errno 113` / `no route to host` între containere este *întotdeauna* asta. Conexiunile deja
  stabilite supraviețuiesc, deci collector-ul continuă să scrie în InfluxDB în timp ce
  `solar-api` dă 503 și Caddy dă 502 — simptom foarte înșelător. firewalld **nu poate fi
  dezactivat** (fail2ban banează prin el). Povestea completă: `deploy/README-firewalld.md`.
- După un restart de firewalld, `docker compose up` poate eșua cu `Failed to Setup IP tables:
  Unable to enable NAT rule: (dbus: connection closed by user)` — daemonul Docker are
  conexiunea D-Bus către firewalld învechită. Leac: `systemctl restart docker`.
- O sondă end-to-end (`solar-watchdog.timer`, la 15 min) trimite push ntfy când se rupe exact
  lanțul folosit de telefon; alertele collector-ului nu acoperă asta. Surse în `deploy/`,
  unitățile instalate în `/etc/systemd/system/`.
- După copierea fișierelor în `grafana/` de pe o gazdă Windows (scp le face `700`/root),
  Grafana rulează ca uid 472 și dă „permission denied" la provisioning. Leac:
  `chmod -R a+rX /opt/solar-monitor/grafana`.
- CLI-ul InfluxDB `influx bucket create --retention` acceptă **doar durate Go** (`48h`),
  nu `2d`. (Variabila `DOCKER_INFLUXDB_INIT_RETENTION` acceptă totuși `31d`.)
- Caddy servește pe SNI `vyra.go.ro`. Un `curl https://localhost:9443/...` eșuează cu
  exit 35 (TLS handshake). Folosește `--resolve vyra.go.ro:9443:127.0.0.1`.

---

## Ce să nu comiți niciodată

`.env`, `keystore.properties`, `key-Rolling7`, `*.jks`, `*.keystore`, `*.apk`, tokenul
botului de Telegram. Toate sunt deja în `.gitignore`; verifică `git status --short` înainte
de fiecare commit și stagează **doar** fișierele intenționate.

---

## Unde e restul documentației

Vezi §„Documentație" din `README.md` pentru indexul complet. Cele trei pe care le vei
deschide cel mai des:

| Fișier | Când |
|---|---|
| `docs/DEZVOLTARE.md` | **Înainte de prima modificare de cod.** Procedura completă de adăugat o funcție, build, release, Telegram. |
| `COPILOT_CONTEXT.md` | Istoric de sesiuni și decizii (§13.x). Citește secțiunea relevantă înainte să „re-descoperi" ceva deja decis. |
| `deploy/README-firewalld.md` | Orice problemă de rețea între containere. |
