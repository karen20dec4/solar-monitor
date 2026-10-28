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

**Șase containere** în `docker-compose.yml`:

| Container | Rol | Port host |
|---|---|---|
| `solar-collector` | Modbus FC04 → InfluxDB + alerte | — |
| `solar-influxdb` | serie de timp, org `casa` | 8086 |
| `solar-grafana` | dashboard `solar-main` | 3000 |
| `solar-ntfy` | push pe telefon, topic din `.env` | 8088 |
| `solar-api` | micro-API JSON READ-ONLY pentru Android | **niciunul** (intern `api:8000`) |
| `solar-caddy` | reverse proxy HTTPS, `tls internal` | 9443 → 443, 8443 |

Toate cu `restart: unless-stopped`, pornesc la boot.

**Lanțul de rețea până la telefon:**
`https://vyra.go.ro:31443` → router ZTE 31443 → `.210:443` → TP-Link 443 → `caddy:443`.
Caddy face `handle_path /solar/*` → `api:8000` (taie prefixul `/solar`), restul → Grafana.

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
  (fără release de aplicație). Sub sarcină mare tensiunea, deci și SOC-ul, scade; revine în repaus.
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

## Convenții

- **Limba:** comentariile, documentația (`README.md`, `COPILOT_CONTEXT.md`, `docs/`),
  mesajele de log și cheile/titlurile alertelor sunt în **română**. Numele câmpurilor
  InfluxDB sunt în **engleză** (`battery_voltage`, `output_power`, …). Respectă asta.
- Toate puterile se raportează și se afișează în **wați** (nu kW).
- Fișierele documentate/afișate în UI folosesc diacritice; codul sursă și scripturile shell
  existente sunt scrise fără diacritice — păstrează stilul fișierului pe care îl editezi.

---

## Capcane cunoscute

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
