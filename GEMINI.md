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
  nimic. (`COPILOT_CONTEXT.md` §13.14)
- *2026-07-18* — cercetat protocolul SPF (documentație oficială + implementări comunitare)
  pentru o comandă „încarcă bateria acum": **nu există**. Încărcătorul solar e un automat
  cu prag intern de re-încărcare nesetabil (≈ float − 2 V). Singurul workaround
  (scriere în holding register 1, comutare ieșire pe rețea) a fost **refuzat de utilizator**.
  (`COPILOT_CONTEXT.md` §13.17)

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

**Excepție: API-ul are teste.** `api/test_app.py` și `api/test_system_metrics.py` (`unittest`,
6 teste). Flask nu e instalat pe gazdă și testele nu intră în imagine, deci rulează-le într-un
container efemer:

```bash
docker run --rm -v /opt/solar-monitor/api:/app:ro -w /app python:3.12-slim \
  sh -c "pip install -q -r requirements.txt && python -m unittest discover -p 'test_*.py'"
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
   (de ex. „7d" / „30d" pe butoanele de interval) sunt pixeli. Dacă schimbi valoarea din Kotlin,
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
`retro-tabs`, `status`, `stop`.

- SDK `/opt/android-sdk`, emulator 36.6.11, platform-tools 37.0.0
- AVD `SolarMonitor_API_34` (Pixel 6, Android 14 / API 34, x86_64, 1080×2400)
- Renderer headless stabil: **`swangle`**. Nu folosi `swiftshader_indirect` — pe gazda asta
  a omorât repetat emulatorul cu `SIGSEGV`.
- Artefactele (PNG, ierarhie UI, logcat) merg în `android/build/emulator-artifacts/` (gitignored).
- ⚠️ **AVD-ul implicit NU e telefonul utilizatorului.** Pixel 6 = 393×873 dp; telefonul real e un
  **Samsung Note 9, 1440×2960 la densitate 560 = 411×846 dp**, adică 27 dp mai scurt. Un layout
  care încape pe Pixel 6 poate fi tăiat pe telefon — s-a întâmplat deja (vezi
  `COPILOT_CONTEXT.md` §13.56). Verifică **ambele** profile:
  ```bash
  ADB=/opt/android-sdk/platform-tools/adb
  $ADB shell wm size 1440x2960 && $ADB shell wm density 560   # profilul Note 9
  $ADB shell wm size reset     && $ADB shell wm density reset # inapoi la Pixel 6
  ```
  Readu întotdeauna profilul implicit la final. Și mai bine: fă layout-ul adaptiv (`weight`,
  `BoxWithConstraints`) în loc să calibrezi înălțimi fixe pe un singur ecran.
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
