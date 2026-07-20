# COPILOT_CONTEXT — Monitorizare Solar Growatt (hot start)

> Citește acest fișier la începutul fiecărei sesiuni ca să continui de unde am rămas.
> Proiect: monitorizare **100% locală, self-hosted, READ-ONLY** pentru un sistem fotovoltaic,
> care înlocuiește complet aplicația ShinePhone și serverele Growatt (cloud).

---

## 1. Context & motivație
- Data-loggerul oficial a fost scos pentru că **update-urile firmware OTA au blocat invertorul de 3 ori în 2 zile**.
- De aceea: **sistemul NU scrie NICIODATĂ în invertor** (doar citire Modbus FC04). Fără control, fără cloud.
- Utilizator: Florin. Limba: română.

## 2. Hardware
| Componentă | Detalii |
|---|---|
| Invertor | **Growatt SPF 6000 ES Plus** (off-grid/hybrid), în beci. Protecție la consum > ~6.6 kW. |
| Baterie | **DIY 14S Li-ion** (NMC), etichetă „LIION 14S MIN 48V MAX 57V", BMS generic, module MB6VAD. ~50V nominal, 48–57V. |
| Panouri | 16 × Canadian Solar 465Wp N-Type TOPCon (~7.44 kWp). |
| Server | **HP 290 G4**, **Debian 13**, hostname `hpG4`, în beci pe cablu Ethernet. IP final **192.168.1.199**. |
| Stocare extra | HDD Seagate ~1TB, ext4, montat permanent în `/data` pentru backup-uri, fișiere mari și proiecte noi. |
| Legătură | Cablu USB-A↔USB-B. Invertor USB = chip **Exar XR21B1411** (VID:PID `04e2:1411`, serial `Q3370413461`) → `/dev/growatt` → `/dev/ttyUSB0`. |
| Modbus | RTU, **9600 8N1, slave ID 1**, function code **04** (input registers). |

⚠️ Serverul vechi Dell Optiplex 7010 este oprit și păstrat doar ca fallback. Nu porni Dell-ul în același timp cu HP-ul pe IP-ul `192.168.1.199`.

## 3. Arhitectură software (stack „lean", fără MQTT)
```
Invertor --USB(/dev/growatt)--> Collector Python (minimalmodbus, READ-ONLY, 1s)
   |-> InfluxDB bucket `live`    (1s,  retenție 48h)
   |-> InfluxDB bucket `history` (60s, retenție 31 zile)
   |-> API Flask `/solar/latest` + `/solar/history` (READ-ONLY, pentru Android)
   |-> ntfy (push pe telefon) cand se declanseaza o alerta
   |-> Grafana (dashboard `solar-main`, setat ca Home)
   `-> Caddy (HTTPS remote pe `https://vyra.go.ro:31443`)
```
Tot în **Docker Compose**, `restart: unless-stopped`, pornește la boot. 6 containere:
`solar-collector`, `solar-influxdb`, `solar-grafana`, `solar-ntfy`, `solar-api`, `solar-caddy`.

## 4. Fișiere & deploy
- **Sursă (Windows):** `H:\_SOLAR-MONITOR\`
- **Server producție:** `/opt/solar-monitor/` pe HP `192.168.1.199`.
- **Flux de lucru:** editezi local → `git commit` + `git push` → pe server `git pull` → rebuild doar serviciile afectate.
- **Regulă importantă:** după orice modificare la API/server/deploy, pe server rulează: `cd /opt/solar-monitor && docker compose up -d --build api`. Pentru modificări doar în documentație nu e necesar rebuild.
- Execuție: prin **SSH** (`ssh root@192.168.1.199` local sau `ssh -p 31422 root@vyra.go.ro` remote, cheie deja instalată). Shell-ul local e **PowerShell** (cwd `H:\_SOLAR-MONITOR`). Pentru query-uri Flux cu ghilimele, folosește tool-ul **Bash** (sau escape).

Structură:
```
solar-monitor/
  .env                      # TOATE setările (praguri, token, retenții)
  docker-compose.yml
  README.md
  api/        app.py, Dockerfile, requirements.txt
  collector/  collector.py, Dockerfile, requirements.txt
  caddy/      Caddyfile, root CA
  grafana/    provisioning/{datasources,dashboards}/, dashboards/solar.json
  influxdb/   init/10-create-live-bucket.sh
  deploy/     99-growatt.rules, create-grafana-user.sh, set-home-dashboard.sh
  android/    aplicația Kotlin/Jetpack Compose
```

## 5. Acces
- **SSH LAN:** `ssh root@192.168.1.199` (cheie)
- **SSH remote:** `ssh -p 31422 root@vyra.go.ro` (cheie)
- **Grafana:** http://192.168.1.199:3000/d/solar-main — `florin` / *(parola setată de user)* sau `admin` / `Gr0w@tt-Grafana-2026-k3Lm`. Home dashboard = solar-main.
- **InfluxDB:** http://192.168.1.199:8086 — `admin` / `muli*neta`. Org `casa`. Token în `.env` (`INFLUXDB_TOKEN`).
- **ntfy:** http://192.168.1.199:8088, topic **`Alerta_6Kw`** (telefonul e abonat aici, cu sunet de alarmă pe canalul „Urgent").

## 6. Maparea registrelor Modbus (FC04 input, VERIFICATĂ pe LCD)
| Reg | Scalare | Metrică |
|---|---|---|
| 0 | — | status invertor |
| 1 / 2 | ×0.1 V | tensiune PV1 / PV2 |
| 3-4 / 5-6 | ×0.1 W (32-bit) | putere PV1 / PV2 |
| 9-10 | ×0.1 W (32-bit) | **output_power = consum casă** |
| 11-12 | ×0.1 VA | putere aparentă ieșire |
| 13-14 | ×0.1 W | încărcare baterie din rețea |
| 17 | ×0.01 V | **tensiune baterie** (validat: 55.6V pe LCD) |
| 18 | % | **SOC baterie** |
| 19 | ×0.1 V | tensiune bus DC |
| 20 | ×0.1 V | tensiune rețea |
| 21 | ×0.01 Hz | frecvență rețea |
| 22 | ×0.1 V | tensiune ieșire AC |
| 23 | ×0.01 Hz | frecvență ieșire |
| 25 | ×0.1 °C | temperatură invertor |
| 27 | ×0.1 → % | grad de încărcare invertor (load%). ⚠️ registru ×10 → `regs[27]*0.1` (altfel raporta 312% în loc de 31.2%) |
| 48-49 / 50-51 | ×0.1 kWh (32-bit) | energie PV azi / total |
| **77-78** | **×0.1 W (32-bit semnat, compl. 2)** | **Bat_Watt = putere baterie (OFICIAL).** În registru: **+ descărcare / − încărcare**; codul îl neagă → câmpul `battery_power` păstrează convenția **+ încărcare / − descărcare** |
| 83 | ×0.1 A | curent **încărcare** baterie (`battery_charge_current`) |
| 84 | ×0.1 A | curent **descărcare** baterie (`battery_discharge_current`) |
| 85-86 / 87-88 | ×0.1 kWh (32-bit) | consum casă azi / total |

⚠️ **Registrul 90 NU mai e folosit.** Puterea și curenții bateriei au fost mutate pe registrele oficiale **77/78 (Bat_Watt)** și **83/84 (curenți)** în commit-ul `d0287f0 "Use official battery watt registers"`. Cel mai mare index citit acum este **88** (`energy_load_total`, reg 87/88) → `REG_COUNT` trebuie **≥ 89**; păstrăm `REG_COUNT=91` (marjă). **`collector.py` e sursa de adevăr** când tabelul de mai sus diferă.

**Putere baterie** = **REALĂ (măsurată):** `battery_power = -(Bat_Watt reg77/78 semnat × 0.1)` (semn câmp: **+ încărcare / − descărcare**). `battery_current = charge_current(reg83) − discharge_current(reg84)`. Câmpuri derivate: `battery_charge_current`, `battery_discharge_current`, `inverter_loss`, `house_source`, plus logica de descărcare inferată / import rețea (vezi CLAUDE.md „Derived signals").

**Sursă consum casă** `house_source` (cod, calculat în collector): `1=PV` / `2=Baterie` / `3=Rețea`. Prag mort 50W. Ordine: încărcare rețea>50→3; descărcare baterie>50→2; PV>50→1; consum fără sursă→3.
Consumul e rutat și pe câmpul sursei active: `house_pv` / `house_bat` / `house_grid` = `output_power` (doar cel activ scris/ciclu). Cardul „Consum casa" are 3 serii (A=house_pv verde ☀️, B=house_bat galben 🔋, C=house_grid roșu ⚡) cu override `displayName`+`color fixed`, `textMode=value_and_name`, range `-8s` → o singură pastilă colorată cu W + emoji sursă.

**Pierdere invertor / consum propriu** (din bilanț de putere, validat ziua ~90–110W):
`inverter_loss = pv_power + battery_discharge_power + grid_charge_power − output_power − battery_charge_power` (clamp ≥0).
- Ziua (încărcare): `= PV − consum − încărcare_baterie_reală` (exact formula cerută de Florin).
- Noaptea (descărcare): `= descărcare_baterie − consum`.
✅ Semnul puterii bateriei la descărcare e validat noaptea: `battery_power` negativ la descărcare (validare istorică pe reg90; convenția a rămas identică după mutarea pe Bat_Watt reg77/78).

## 7. Alerte (în collector, praguri în `.env`)
| Cheie | Declanșare | Revenire |
|---|---|---|
| consum_mare | ≥ `ALERT_THRESHOLD_W` (5500) | ≤ `ALERT_CLEAR_W` (5000) |
| baterie_jos | ≤48V sau SOC≤20% | ≥49V și SOC≥25% |
| baterie_sus | ≥57V | <56V |
| supraincalzire | ≥65°C | <60°C |
| iesire_pierduta | output_voltage <180V | ≥200V |
| watchdog | invertor mut ~30s (`WATCHDOG_FAILS`) | la revenire |

Toate: **debounce 3s + cooldown 300s + histerezis**. ntfy prioritate `urgent`. Testat OK (inclusiv supraîncălzire forțată la 20°C → a venit pe telefon cu sunet).

## 8. Gotchas (capcane întâlnite — IMPORTANT)
1. **Permisiuni scp:** `scp` de pe Windows creează fișiere/foldere cu drepturi `700` (doar root). Grafana rulează ca uid 472 → „permission denied" la provisioning. **Fix:** după scp în grafana/, rulează `chmod -R a+rX /opt/solar-monitor/grafana`.
2. **Retenție InfluxDB:** flag-ul `influx bucket create --retention` acceptă DOAR durate Go (`48h`), NU `2d`. (Dar `DOCKER_INFLUXDB_INIT_RETENTION` acceptă `31d`.)
3. **Collector env:** are `env_file: ./.env` → toate variabilele din `.env` ajung la el. Reglare praguri = editezi `.env` + `docker compose up -d collector`.
4. **Tool Bash** uneori indisponibil (classifier Anthropic) → fallback pe **PowerShell**.
5. **Server după `git pull`:** dacă s-a modificat API-ul sau deploy-ul, trebuie rebuild explicit: `cd /opt/solar-monitor && docker compose up -d --build api`.

## 9. Comenzi utile
```bash
# loguri collector
docker logs -f solar-collector
# status
cd /opt/solar-monitor && docker compose ps
# query live (ex: SOC)
docker exec solar-influxdb influx query --org casa --token <TOKEN> \
  'from(bucket:"live") |> range(start:-30s) |> filter(fn:(r)=> r._field=="battery_soc") |> last()'
# test ntfy
curl -d "mesaj" -H "Title: test" -H "Priority: urgent" -H "Tags: zap" http://localhost:8088/Alerta_6Kw
```

## 10. Dashboard actual (`solar.json`, uid `solar-main`)
- Rând „ACUM": gauge mare **Tensiune baterie** (46–58V, praguri 48/50/56.5/57, fără etichete text); **Consum casa** (card combinat: emoji+W colorat după sursă ☀️/🔋/⚡); Putere PV; **Baterie** (putere reală, verde=încarcă/roșu=descarcă); **Consum invertor (pierderi)**; Tensiune rețea; Temperatură.
- ⚠️ Cardul **SOC a fost ELIMINAT** (BMS DIY raporta în trepte grosiere 75/100, neutil). Tensiunea e indicatorul real.
- Rând „Timp real" (live) + Rând „Istoric" (history 30 zile).
- Toate puterile în **W** (unit `suffix:W`, nu kW). Refresh 5s.

---

## 11. TODO

### ✅ #1 — REZOLVAT 2026-05-31 (PV mare): pierderea în conversie + putere baterie reală
**Făcut:** registrul **90 = curent baterie ×0.1 A (semnat)** identificat prin corelație cu PV (r90 urca 554→572 când bateria urca 3082→3169W). `battery_power` comutat pe măsurătoarea reală (`reg90×0.1 × Vbat`). Adăugat câmp `inverter_loss` + câmp `battery_current`. Panou nou **„⚡ Consum invertor (pierderi)"** pe dashboard + linie în graficul live. Validat: pierdere stabilă **~90–110W** ziua. `REG_COUNT=91`, `DEBUG_RAW=0`.

> ⚠️ **Actualizare ulterioară (commit `d0287f0`):** puterea bateriei a fost mutată de pe reg90 pe registrele **oficiale Bat_Watt 77/78** (int32 semnat ×0.1W), iar curenții pe **83/84**. Reg90 nu mai e citit/folosit. Vezi tabelul din secțiunea 6 — `collector.py` e sursa de adevăr.

#### ✅ #1b — REZOLVAT 2026-06-17: validare semn reg90 la DESCĂRCARE (noaptea)
Validat dupa mutarea pe serverul HP: la consum din baterie, `battery_current=-8.1A`, `battery_power=-423W`, `battery_discharge_power=423W`, `output_power=301W`, `inverter_loss=122W`. Semnul registrului 90 este corect negativ la descărcare; nu trebuie schimbat `parse()`.

### #2 — Dashboard: power-flow + pagină mobilă (ales de user, neînceput)
- Diagramă flux energetic (PV → baterie/casă/rețea).
- A doua pagină compactă, optimizată pentru telefon.
- Eventual: Tensiune rețea pe jumătate de lățime, ca să facă loc altui panou.

### #3 — Alte idei (opționale)
- Energie zilnică **kWh** (produs/consumat) + sumar zilnic pe telefon — necesită recon registre de energie.
- Acces remote securizat (Tailscale/WireGuard), fără cloud.
- Backup automat config + DB; istoric lung (downsampling 1 an).

---

## 12. Stare curentă (la 2026-06-17)
✅ Server producție mutat pe HP 290 G4 / Debian 13, IP `192.168.1.199`, Ethernet, invertor pe `/dev/growatt`.
✅ Stack Docker complet pornit pe HP: `influxdb`, `collector`, `grafana`, `ntfy`, `api`, `caddy`.
✅ Monitorizare live 1s + istoric 60s/31 zile, verificate după cutover.
✅ API Android: `/solar/latest` + `/solar/history`, acces prin `https://vyra.go.ro:31443`.
✅ App Android nativă cu teme Retro/Simple, flux animat, grafice istoric și alarmă locală foreground service. Versiune curentă: **versionCode 11 / versionName 2.0**.
✅ Alerte protecție în collector + ntfy; alarmă locală în Android pentru consum mare.
✅ 100% local/self-hosted pentru datele invertorului, read-only, pornește la boot.
✅ **Putere baterie REALĂ (reg90) + pierdere/consum invertor (~90–110W) — afișat pe dashboard.**
✅ HDD Seagate ~1TB montat permanent în `/data`; backup volume Docker de cutover păstrat în `/data/backups/solar-volume-backup-cutover`.
⏳ Rămas opțional: power-flow mai avansat, backup automat, istoric lung/downsampling.

---

## 13. Sesiune 2026-06-15: acces remote HTTPS, API JSON, app Android, energie kWh

### 13.1 Acces remote securizat (HTTPS) — REZOLVAT
- Container nou **`caddy`** (reverse proxy, `caddy/Caddyfile`) cu **TLS self-signed** (`tls internal`,
  Let's Encrypt nu merge pe port non-standard). Servește Grafana + API-ul.
- Lanț rețea (dublu NAT): `https://vyra.go.ro:31443` → router ZTE poartă (`31443→192.168.100.210:443`)
  → TP-Link (`443→192.168.1.199:443`) → `caddy:443` → `grafana:3000`.
  - Pe ZTE mai există: `31422→22` (SSH, funcțional) și `31480→80` (HTTP, rezervă).
- Grafana: `GF_SERVER_ROOT_URL=http://...`→ acum `https://vyra.go.ro:31443/`, `GF_SECURITY_DISABLE_GRAVATAR=true`.
- Root CA Caddy extras în `caddy/vyra-root-ca.crt` (de instalat pe telefon ca să nu mai dea warning).
- ⚠️ Acces local neschimbat: `http://192.168.1.199:3000`. Doar 443→Caddy e expus pe WAN (8086/8088 NU).

### 13.2 Micro-API JSON pentru aplicația mobilă — `api/`
- Container nou **`solar-api`** (Flask + gunicorn, `api/app.py`), **READ-ONLY** (citește din InfluxDB).
- Endpoint **`https://vyra.go.ro:31443/solar/latest`** → JSON cu ultimele valori (Caddy `handle_path /solar/*`
  scoate prefixul, proxy la `api:8000`). Restul → Grafana.

### 13.3 Aplicație Android nativă — `android/` (REZOLVAT TODO #2 mobil)
- Kotlin + Jetpack Compose, package **`com.rolling7.solar`**, label "Solar Monitor", minSdk 26.
- UI: **diagramă flux energetic animată** (PV→Casă, baterie/rețea, săgeți care curg) + **carduri live**
  (PV, baterie, casă, rețea, temp, pierderi, **energie produs/consumat azi**). Polling `/solar/latest` la 2s.
- HTTPS self-signed: root CA Caddy inclus în app (`res/raw/vyra_root_ca.crt` + `network_security_config`).
- **Release semnat** din `keystore.properties` (alias `key0`, store `key-Rolling7`) + R8 `minifyEnabled`
  + `shrinkResources` → **APK ~750 KB**. versionCode 2 / versionName 1.1.
- Build pe server: JDK 17 + Android SDK (`/opt/android-sdk`, platform-34, build-tools 34) + Gradle 8.9
  wrapper. Comandă: din `android/` → `./gradlew assembleRelease`. APK copiat la `SolarMonitor-v1.1.apk`.
- `.gitignore` extins: `key-Rolling7`, `keystore.properties`, `*.jks`, `*.apk`, build dirs.

### 13.4 Energie zilnică kWh — REZOLVAT (TODO #3) + fix load_percent
Registre de energie identificate prin corelație (DEBUG_RAW + integralul puterii), **toate ×0.1 kWh, 32-bit**:
| Reg (hi/lo) | Câmp | Sens |
|---|---|---|
| 48/49 | `energy_pv_today` | energie PV produsă azi |
| 50/51 | `energy_pv_total` | energie PV totală |
| 85/86 | `energy_load_today` | consum casă azi |
| 87/88 | `energy_load_total` | consum casă total |

- ⚠️ **Fix scalare:** reg **27 = `load_percent` este ×10** → corectat la `regs[27] * 0.1` (raporta 312% în loc de 31.2%).
- Adăugate în `collector.py` `parse()`, în API (`api/app.py` FIELDS), pe dashboard (rând "⚡ Energie", panouri 30-33)
  și în app. `REG_COUNT=91` acoperă deja reg 88 (DEBUG_RAW revenit la 0).

### 13.5 Dashboard Grafana (`solar.json`) — REZOLVAT TODO #2 power-flow
- Panou nou **"🔀 Flux energetic"** (text/HTML/SVG live, în stilul gauge-ului de baterie existent) — afișat
  **primul, vedetă**; bateria al doilea; restul dedesubt; rând nou "⚡ Energie".


### 13.6 App Android - istoric pe carduri (2026-06-15)
- API nou `/solar/history` (READ-ONLY, doar InfluxDB):
  - `field=battery_voltage|output_power`
  - `range=1h|6h|24h`
  - `1h` = bucket `live`, agregare `30s`; `6h` = bucket `live`, agregare `2m`; `24h` = bucket `history`, agregare `5m`.
  - raspuns: `points[{t,v}]` + `stats{min,max,avg,last}`.
- App Android:
  - cardurile `Baterie` si `Casa` sunt clickabile.
  - bottom sheet cu grafic Canvas, statistici si selector `1h / 6h / 24h`.
  - Baterie deschide implicit `24h` si traseaza praguri 48V / 57V.
  - Casa deschide implicit `1h` si afiseaza varful maxim de consum.
  - Polling-ul live ramane la 2s in app; nu creste frecventa Modbus.

### 13.7 App Android - istoric energie zilnica (2026-06-15)
- `/solar/history` suporta acum si `energy_pv_today` / `energy_load_today`.
- Intervalele pentru energie sunt `7d` si `30d`.
- API foloseste bucket-ul `history`, agregare zilnica `max` peste campurile `*_today`, cu timezone `Europe/Bucharest`.
- Cardurile `Produs azi` si `Consum azi` sunt clickabile in app.
- Bottom sheet-ul pentru energie foloseste bar chart vertical si statistici: total, medie/zi, max zi, ultima zi.
- Versiune Android: versionCode 5 / versionName 1.4.

### 13.8 App Android - axe grafice si PV history (2026-06-15)
- Cardul `PV intrari` este clickabil si foloseste `/solar/history?field=pv_power` cu `1h / 6h / 24h`.
- Line chart-urile au axa Y etichetata in stanga si axa X cu timp: `1h` = 10 minute, `6h` = 60 minute, `24h` = 3 ore.
- Graficul `Baterie` are scala fixa 48-58V si linii etichetate 48/50/52/54/56/58V plus praguri 48V/57V.
- Versiune Android: versionCode 6 / versionName 1.5.

### 13.9 App Android - alarma locala foreground service (2026-06-15)
- Settings bottom sheet deschis din gear in header.
- Setari locale in SharedPreferences: alarma on/off, prag W default 5000, cooldown default 300s, vibratie, ringtone URI.
- Foreground service `SolarAlarmService` citeste `/solar/latest` la 2s prin API si declanseaza local cand `output_power >= threshold` doua citiri consecutive.
- Histerezis: rearmare la prag-200W. Sunetul se opreste automat dupa 30s sau cand consumul scade sub clear.
- Ringtone picker Android foloseste sunete de tip alarm; buton `Testeaza` porneste alarma local.
- Nu modifica server/API si nu creste polling-ul Modbus. Versiune Android: versionCode 7 / versionName 1.6.

### 13.10 App Android - notificare alarma compacta (2026-06-15)
- Notificarea permanenta a foreground service-ului afiseaza consumul casei in titlu, ex. `Casa 1.2 kW`.
- Pragul si clear-ul sunt afisate in kW in textul notificarii.
- Iconul static al notificarii a fost schimbat din icon info Android intr-un icon solar monochrome.
- Versiune Android: versionCode 8 / versionName 1.7.

### 13.11 Cutover server HP finalizat (2026-06-16 / 2026-06-17)
- Server producție actual: **HP 290 G4 / Debian 13**, hostname `hpG4`, IP final **`192.168.1.199`** pe Ethernet `enp1s0`.
- IP-ul `192.168.1.150` a fost doar IP-ul temporar de pregătire; nu mai este adresa operațională a sistemului Solar Monitor.
- Serverul vechi Dell Optiplex 7010 a fost oprit și păstrat ca fallback. Nu îl porni simultan cu HP-ul pe IP-ul `.199`.
- HP-ul este în beci, conectat prin cablu Ethernet, cu invertorul USB Exar `04e2:1411` disponibil ca `/dev/growatt`.
- Colectare verificată după cutover și după schimbarea cablului de alimentare: live scrie la 1s, history scrie la 60s, `collector` rulează fără restarturi.
- Volumele Docker restaurate pe HP:
  - `solar-monitor_influxdb-data`;
  - `solar-monitor_influxdb-config`;
  - `solar-monitor_grafana-data`;
  - `solar-monitor_ntfy-cache`;
  - `solar-monitor_caddy-data`;
  - `solar-monitor_caddy-config`.
- Backup-ul volumelor de cutover este păstrat în `/data/backups/solar-volume-backup-cutover`.
- Discul Seagate de ~1 TB este formatat ext4 și montat permanent în `/data` pentru backup-uri, fișiere mari și proiecte noi.
- Curățenie făcută pe HP:
  - dezinstalat/șters OpenClaw;
  - oprit/dezactivat/șters Ollama și modelele locale;
  - eliminată completarea OpenClaw din `/root/.bashrc`;
  - dezactivat `linger` pentru root;
  - dezactivate servicii inutile pentru rolul de server: Bluetooth, CUPS, Avahi, ModemManager, Blueman.
- Instalate/verificate pe HP:
  - Docker `26.1.5+dfsg1` + Docker Compose `2.26.1`;
  - OpenJDK 21;
  - `/opt/solar-monitor` din repo;
  - `/opt/android-sdk`;
  - `/opt/gradle-8.9`;
  - `/opt/pics-logs-copilot`;
  - **nu** s-a copiat `/opt/containerd` (runtime intern Docker; HP-ul folosește propriul Docker).
- Android pe HP:
  - `/etc/profile.d/android-sdk.sh` setează `ANDROID_HOME=/opt/android-sdk`, `ANDROID_SDK_ROOT=/opt/android-sdk`, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`;
  - build verificat OK: `./gradlew :app:assembleDebug`;
  - build release semnat verificat OK: `./gradlew :app:assembleRelease`.
- Invertor/udev:
  - regula `/etc/udev/rules.d/99-growatt.rules` instalată;
  - `/dev/growatt` apare pe HP după conectarea invertorului USB: Exar XR21B1411, VID:PID `04e2:1411`, serial `Q3370413461`.
- Docker pe HP:
  - imaginile pentru `influxdb`, `grafana`, `ntfy`, `caddy`, `api`, `collector` au fost trase/construite;
  - containerele `influxdb`, `collector`, `grafana`, `ntfy`, `api`, `caddy` sunt pornite pe HP.
- Runbook-ul **`schimbare-server.md`** a fost **șters** (2026-07-10, de Florin) după finalizarea mutării — mutarea principală este completă și nu mai e necesar.
### 13.12 Release Android portabil (2026-06-17)
- Script tracked în repo: **`scripts/build-android-release.sh`**.
- Comandă recomandată pe HP/Linux:
  ```bash
  cd /opt/solar-monitor
  scripts/build-android-release.sh
  ```
- Scriptul:
  - citește `versionCode` / `versionName` din `android/app/build.gradle.kts`;
  - folosește implicit `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/opt/android-sdk`, `GRADLE_CMD=/opt/gradle-8.9/bin/gradle` dacă există;
  - rulează build release semnat;
  - copiază APK-ul în root ca `SolarMonitor-v<versionName>.apk`;
  - afișează `aapt dump badging`, dimensiune și SHA256.
- Instrucțiune Codex tracked în repo: **`.codex/skills/solar-monitor-release/SKILL.md`**.
  - Pe HP se poate instala pentru sesiunile locale cu:
    ```bash
    mkdir -p ~/.codex/skills
    cp -a /opt/solar-monitor/.codex/skills/solar-monitor-release ~/.codex/skills/
    ```
- Regula de versionare: se incrementează `versionCode` / `versionName` doar când s-a modificat codul/resursele Android. Pentru rebuild al aceleiași versiuni nu se incrementează.
- APK-urile rămân ignorate de git; release-ul nu cere rebuild API. Pentru API/server/deploy rămâne regula: `docker compose up -d --build api`.

### 13.13 Sincronizare documentație cu collector.py (2026-07-10)
- Verificat `collector.py` ca **sursă de adevăr** și aliniat `COPILOT_CONTEXT.md` la cod. Fără modificări de cod — doar documentație. Collectorul rămâne READ-ONLY (doar FC04).
- **Putere/curent baterie:** confirmat că **NU mai** vin din reg90, ci din **Bat_Watt reg 77/78** (int32 semnat ×0.1W) pentru putere și **reg 83 (încărcare) / reg 84 (descărcare)** pentru curenți (commit `d0287f0 "Use official battery watt registers"`). Actualizat tabelul de registre (secțiunea 6), formula puterii bateriei și nota `REG_COUNT`.
- **REG_COUNT:** cel mai mare index citit este **88** (`energy_load_total`, reg 87/88) → minim real **≥ 89**; păstrat `REG_COUNT=91` (marjă). Corectate comentariile stale din `.env` și `.env.example` care ziceau „reg90 = curent baterie".
- **load_percent (reg27):** confirmat ×0.1 în cod (`regs[27]*0.1`); tabel actualizat.
- Adăugate în tabelul secțiunii 6 și registrele de energie deja folosite: **48/49, 50/51** (PV azi/total), **85/86, 87/88** (consum azi/total).

### 13.14 Recon FC03 (holding registers) + decizie „rămânem READ-ONLY" (2026-07-10)
**Context:** Florin vrea bateria plină la pragul de sus (**56V**) seara (~19–20), ca să aibă mai mult curent
noaptea; în practică ajunge pe la ~54.5V. A propus un buton în app care să pornească încărcarea din PV până
la 56V. Asta ar fi cerut **scriere în invertor** (FC06/FC16) → atinge invariantul dur READ-ONLY.

**Diagnostic pe datele READ-ONLY (InfluxDB, zi însorită 9 iulie 2026):**
- La prânz PV era **gâtuit (curtailment):** panourile puteau da **5184W**, dar media la prânz ~1100W ≈ consum,
  pentru că bateria era deja plină la 56V. ~4kW PV nefolosit → bateria e la vârf, n-are unde încărca.
- Bateria NU scade lent toată ziua; **cade după-amiaza** (ex. 16:00: consum 1032W, PV real doar 286W →
  descărcare 907W → 53.9V). După 16:00 PV < consum, deci **nu mai există surplus PV de reîncărcat**.
- Concluzie: problema nu e pragul de încărcare, ci **PV insuficient seara + consum mare după-amiaza**.

**Recon FC03 (READ-ONLY, one-off):** collectorul oprit temporar (ca să nu fie doi maeștri pe bus), rulat un
container din imaginea collectorului care citește **doar FC03 (holding) + FC04 (input)**, apoi collector repornit.
Script: `scratchpad/recon.py` (nu e în repo). Ancoră OK: `reg17 input = 5600 = 56.00V`. Registre de setare
(holding), **validate pe LCD de Florin:**

| holding reg | citit | funcție (confirmat pe LCD) |
|---|---|---|
| 34 | 70 | curent max încărcare = **70A** ✅ |
| 35 | 560 (×0.1) | tensiune încărcare C.V./bulk = **56.0V** ✅ |
| 36 | 560 (×0.1) | tensiune **float = 56.0V** ✅ |
| 37 | 482 (×0.1) | prag jos ~48.2V (back-to-grid, neconfirmat exact) |
| — | — | **Output source priority = SBU** (Solar>Baterie>Rețea) ✅ |
| — | — | **Charger source priority = SOLAR ONLY** (bateria se încarcă DOAR din PV, niciodată din rețea) ✅ |

Alte praguri plauzibile (neetichetate încă): reg 87=60.0V, reg 109=58.4V (probabil protecție supratensiune
~4.17V/celulă), reg 94=42.0V (cutoff jos), reg 82=46.0, 85=50.0, 86=48.0, 95=51.0.
⚠️ `reg 155=5600`, `158=2287`, `160=2303` **NU sunt praguri** — sunt valori **live oglindite** în holding
(baterie/rețea/ieșire), identice cu input-urile 17/20/22. **FC03 = citire, nu încalcă invariantul.**

**Decizie (Florin, 2026-07-10): NU adăugăm control/scriere. Sistemul rămâne 100% READ-ONLY (FC04).**
- Ținta de încărcare e **deja 56V** (bulk=float=56, confirmat) ȘI încărcarea e deja **doar din PV**
  (charger source = SOLAR ONLY) → un buton „încarcă din PV până la 56V" ar cere fix ce e deja setat →
  **fără efect.** Pârghia reală ar fi prioritatea sursei (consum din rețea seara, nu din baterie), dar aia
  folosește rețeaua (nu „doar PV") și tot scriere ar fi. Nu merită spart invariantul.
- Butonul „live" din app **se păstrează**; nu se implementează butonul de încărcare.
- Alternative fără scriere pentru 56V seara, dacă se dorește cândva: **load-shifting** (muți consumatorii mari
  spre prânz, unde sunt ~4kW PV irosiți) sau o **setare one-time pe LCD** (prioritate ieșire pe rețea în ferestrele
  fără soare).
- Collector **nemodificat:** poll rămâne la **1s** (`POLL_INTERVAL_LIVE=1`); ideea de 2s a rămas doar discuție.

### 13.15 App Android v1.8 — alarmă opribilă, tensiune baterie mare, chevroane flux (2026-07-10)
**versionCode 9 / versionName 1.8.** Doar UI/serviciu Android; server/API/collector neatinse. Build debug verificat OK.
- **Alarmă consum mare — mai ușor de oprit:**
  - Durata maximă a sunetului **30s → 15s** (`SolarAlarmService.ALARM_SOUND_MS`).
  - **Pop-up în aplicație** când sună: dialog cu buton mare roșu „OPRESTE ALARMA" (`AlarmOverlay` în
    `MainActivity`) → trimite `ACTION_SILENCE` la service și oprește sunetul. Notificarea cu „Opreste sunet"
    rămâne și ea.
  - Stare partajată nouă **`AlarmState`** (StateFlow in-proces) între service (care sună) și activitate
    (care afișează pop-up-ul).
- **Card „Consum casa" (`MainStatusPanel`):** tensiunea bateriei afișată **mare (38sp)**, lângă consum
  (ambele numere-titlu, egale). Dedesubt: pastila sursă (solar/baterie/rețea) + puterea bateriei; apoi
  PV acum + Pierderi. **Bara „Nivel baterie" eliminată** (redundantă). Puterea bateriei **colorată după
  sens:** descărcare (−W) galben `CBat`, încărcare (+W) verde `CPv`. (Eliminat `SourceBadge`/`batteryVoltageLevel`,
  folosit `StatusPill`.)
- **Header:** scoasă pastila „live/offline" de lângă butonul de setări (inutilă); scos și state-ul `online`.
- **Flux energie (`ArrowLine`):** liniuțele animate înlocuite cu **chevroane care curg** (`> < ^ v`),
  subțiri (stroke 2.5dp) și rare (spacing 15dp), în sensul curgerii (încărcare→spre baterie, descărcare→spre
  casă, rețea→spre casă, PV→jos).
- Nemodificat în browser/Grafana (Florin nu prea folosește browserul); dacă se dorește, tensiunea mare se
  poate reflecta și pe dashboard-ul `solar.json`.

### 13.16 App Android v1.9 — notificare cu Casa/PV/Bat, fără titlu flux (2026-07-10)
**versionCode 10 / versionName 1.9.** Doar UI/serviciu Android. Release semnat OK.
- **Notificarea permanentă** (`SolarAlarmService.monitorNotification`) arată acum mai multe date fără a
  deschide app-ul: titlu `Casa: X kW · PV: Y kW · Bat: ±Z kW` (Bat cu semn: + încărcare / − descărcare),
  text `Prag alarma: N kW`. Scos „clear" și subtext-ul redundant; adăugat `formatKwSigned`.
- **Card „Flux energie":** scos titlul „Flux energie" (`SectionTitle` eliminat) — se câștigă spațiu,
  diagrama e evidentă oricum.

### 13.17 Cercetare protocol: comandă „încarcă bateria" — NU EXISTĂ. Subiect ÎNCHIS (2026-07-18)
**Întrebarea lui Florin:** se poate trimite din app o comandă Modbus care să pornească încărcarea bateriei
din PV până la limita setată (56V)? Context: după-amiaza bateria plutește sub 56V (ex. 55.6V, descărcare
~50W) și nu se reîncarcă, deși e soare; seara intră în noapte cu ~54.5V.

**Rezultatul cercetării (documentație oficială + comunitate): NU există o astfel de comandă.**
- Încărcătorul solar e mașină de stări autonomă (bulk → CV → float → done). După „done", reintră la
  încărcare doar sub un **prag intern din firmware, nesetabil** (comunitate: ≈ float − 2V ≈ **54.0V** la noi).
  De-aceea bateria plutește 56 → ~54.5 toată după-amiaza fără reîncărcare. Confirmat de prezentarea oficială
  Growatt SPF 6000ES PLUS (tabel „Off-Grid Battery Related Settings", ierarhia setărilor 21<12<13<20<19;
  item 19=CV, 20=float, 12=comutare pe rețea, 21=cutoff).
- Harta holding validată încrucișat (recon nostru FC03 + proiecte comunitate): **reg 1** = prioritate ieșire
  (0=SBU/1=SOL/2=UTI), **reg 2** = sursă încărcare (2=PV only), reg 3–6 ferestre orare UTI, reg 20–22
  restart/buzzer, reg 34–38 = curent max/CV/float/prag-spre-rețea/curent float, reg 39 = tip baterie (2=custom),
  **reg 45–50 = ceasul invertorului (RTC)** — citit 2026-07-10 17:56:54, exact momentul recon-ului ✓.
- Surse: github rodrigojfernandez/Growatt_SPF5000ES_HomeAssistant (scrie doar reg 1,2,3-6,20-22,34);
  github Tobster86/growatt-spf5000es-modbus-offpeak-charging (proiect dedicat controlului încărcării —
  folosește DOAR reg 1); OpenInverterGateway (zero holding pt. SPF); PDF oficial Growatt SPF 6000ES PLUS
  Introduction/Troubleshooting (SolarNRG, mayoristaenergiasolar.com).
- Singura manevră existentă în protocol: comutare **reg 1 SBU→UTI** (casa pe rețea, PV dedicat bateriei).
  **RESPINSĂ de Florin (2026-07-18):** casa ar consuma 3–4 kWh din rețea în câteva ore pentru ~+0.5V în
  baterie — nu e un câștig.

**DECIZIE FINALĂ (Florin): ne oprim. Fără buton, fără scrieri. Sistemul rămâne 100% READ-ONLY (FC04).**
Subiectul „comandă de încărcare on-demand" este închis definitiv — nu redeschide fără informații noi
(ex. firmware nou cu registre noi documentate).

### 13.18 App Android v2.0 — dashboard premium, compact (2026-07-20)
**versionCode 11 / versionName 2.0.** Schimbare numai în stratul UI Android; API-ul, collectorul, alarma și
regula READ-ONLY rămân neschimbate. Build debug și lint verificate OK.
- Eliminată repetarea acelorași valori în status, flux și grila de opt carduri. Noul `EnergyOverview`
  grupează fluxul live și sumarul energetic al zilei într-o singură suprafață Material 3.
- Casa este nodul vizual principal; Panouri este secundar; Baterie și Rețea au o greutate mai mică.
- Contururile colorate au fost eliminate din dashboard. Separarea folosește culoare tonală și elevatie
  subtilă; culorile de status apar numai în valori, puncte și particule.
- Fluxul nu mai folosește chevroane/săgeți text. Un `Canvas` desenează topologia și particule animate în
  sensurile Panouri→Casă, Panouri→Baterie, Baterie→Casă și Rețea→Casă.
- Istoricul are buton unic în header și un selector cu toate cele cinci metrici; valorile relevante rămân
  scurtături directe printr-o pictogramă discretă de grafic.
- Detaliile tehnice sunt rânduri într-o singură suprafață, nu carduri individuale.
- Adăugat ghidul pentru începători `android/DASHBOARD_REDESIGN.md` cu logica UX, layout-urile, modifierii,
  tipografia și animația explicate pas cu pas.

### 13.19 App Android v2.0 final — teme Retro/Simple + cadran analogic (2026-07-20)
**versionCode 11 / versionName 2.0.** Această iterație face parte din release-ul public 2.0, următorul după
1.9. Schimbare numai în UI-ul Android; API-ul, collectorul, polling-ul și
alarma rămân neschimbate, iar sistemul continuă să fie READ-ONLY.
- Adăugate două dashboarduri complete: `RetroDashboard` și tema existentă, redenumită `Simple`.
- Tema implicită este **Retro**, inclusiv pentru instalările existente care nu au încă o preferință salvată.
- Selector segmentat `Retro / Simple` în Settings; alegerea este salvată în `SharedPreferences` prin
  `DashboardStyleStore` și persistă după închiderea aplicației sau repornirea telefonului.
- Tema Retro folosește paleta aleasă (`#accc78`, `#81795a`, `#f1e169`), panouri industriale olive,
  etichete monospace, LED-uri și valori cu șapte segmente desenate nativ în Compose Canvas.
- Consumul casei are cadran analogic animat 0–7 kW. Zona de avertizare începe la pragul real configurat
  pentru alarma locală, iar atingerea cadranului deschide istoricul consumului.
- Flux energetic Retro cu particule animate pentru Panouri→Casă, Panouri→Baterie, Baterie→Casă și
  Rețea→Casă; sumar zilnic și panou de sistem grupate dedesubt.

### 13.20 Emulator Android local + skill de verificare (2026-07-20)

Pe serverul HP există acum un mediu complet de test Android, accelerat KVM și utilizabil fără interfață
grafică. Emulatorul se pornește numai la cerere, nu ca serviciu la boot.

- SDK: `/opt/android-sdk`; `ANDROID_HOME` și `ANDROID_SDK_ROOT` sunt configurate global în
  `/etc/profile.d/android-sdk.sh`.
- Pachete instalate: Android Emulator **36.6.11**, platform-tools **37.0.0**, platform API 34,
  build-tools 34.0.0 și `system-images;android-34;google_apis;x86_64` revizia 14.
- AVD: `SolarMonitor_API_34`, profil Pixel 6, Android 14/API 34, Google APIs x86_64, 1080×2400,
  stocat în `/root/.android/avd/SolarMonitor_API_34.avd`.
- Accelerarea hardware este activă prin `/dev/kvm`; `emulator -accel-check` confirmă că KVM este utilizabil.
- Spațiu ocupat la instalare: aproximativ 819 MB emulator, 4,2 GB imagine de sistem și 1,3 GB AVD.

A fost creat skill-ul versionat `.codex/skills/solar-monitor-emulator/`, instalat și în catalogul local
`/root/.codex/skills/solar-monitor-emulator/` pentru a fi descoperit în sesiuni viitoare. Comanda recomandată:

```bash
cd /opt/solar-monitor
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify
```

`verify` controlează SDK/AVD/KVM, pornește emulatorul headless, așteaptă boot-ul, construiește APK-ul debug,
îl instalează și lansează, apoi salvează screenshot, arbore UI și logcat în directorul ignorat de Git
`android/build/emulator-artifacts/`. Subcomenzi disponibile: `doctor`, `start`, `wait`, `build`, `install`,
`launch`, `screenshot`, `status`, `verify`, `stop`.

Verificare reală efectuată pe Android 14 pentru aplicația **2.0**: dashboardul Retro s-a randat cu date live,
selectorul Retro/Simple a funcționat, tema Simple s-a păstrat după force-stop/restart, apoi preferința a fost
readusă la Retro. Captura stabilă este `android/build/emulator-artifacts/retro-verified.png`. Nu s-a modificat
collectorul, API-ul sau regula READ-ONLY.

### 13.21 Release Android v2.0 (2026-07-20)

- Release public următor după `SolarMonitor-v1.9.apk`: **versionCode 11 / versionName 2.0**.
- APK semnat: `/opt/solar-monitor/SolarMonitor-v2.0.apk`, 1.005.983 bytes.
- SHA-256: `6350aee68869d42f8e3d5df2959eff2479f910fea3af2bc6f302b8366909e2f3`.
- `aapt` confirmă pachetul `com.rolling7.solar`, versiunea 2.0 (11), compile SDK 34.
- `apksigner` confirmă APK Signature Scheme v2 și certificatul SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`, identic cu v1.9.
- Verificat upgrade real în emulator Android 14: instalare v1.9, apoi `adb install -r` v2.0 cu succes;
  release-ul semnat pornește, afișează date live și nu produce crash. Captură:
  `android/build/emulator-artifacts/release-v2.0-signed.png`.
- `testDebugUnitTest` (fără teste definite), `lintDebug`, `lintVitalRelease`, build R8 și verificarea vizuală
  au trecut. Nu este necesar rebuild pentru API/server; modificările sunt exclusiv Android/UI și documentație.

### 13.22 Retro extins pe ecranele adiacente (release 2.01, 2026-07-20)

- Când este selectat `RETRO`, meniul Istoric, paginile Consum/PV/Baterie/Energie și Setările folosesc acum
  aceeași paletă olive/sage/yellow, fundal industrial și font monospace; tema albastră rămâne numai în `SIMPLE`.
- Panourile mari Retro, graficele și cardurile de statistici au patru șuruburi discrete desenate nativ în
  Compose Canvas, inspirate din `retro-theme.png`; nu sunt imagini raster și se scalează corect.
- Cod semantic păstrat și clarificat: verde = solar/normal, galben = baterie/atenție, roșu = rețea/alarmă;
  casa este neutră. Cadranul consumului trece verde → galben la 80% din prag → roșu peste prag.
- Controalele Material din Settings (sheet handle, switch și slider) primesc culorile Retro, fără urme albastre.
- Verificat pe emulator Android 14: dashboard, Istoric, Energie produsă cu date reale, Setări, schimbare
  Simple→Retro, persistență `RETRO`, build debug și lint OK, fără crash-uri. Capturi în directorul ignorat Git:
  `retro-screws-dashboard.png`, `retro-history-menu.png`, `retro-energy-produced.png`, `retro-settings-final.png`.
- Inclus în release-ul Android 2.01.

### 13.23 Navigație Retro în patru taburi (release 2.01, 2026-07-20)

- Tema `RETRO` are acum o bară inferioară fixă, desenată ca un singur panou metalic: `TABLOU`, `ENERGIE`,
  `SISTEM`, `SETARI`. Nu există și nu se va adăuga `CONTROL`; aplicația rămâne intenționat READ-ONLY.
- `TABLOU` conține numai consumul live și fluxul energetic. `ENERGIE` grupează totalurile zilei, selectorul
  celor cinci metrici și graficele. `SISTEM` grupează bateria, rețeaua, temperatura, pierderile, codul brut
  și starea conexiunii invertorului. `SETARI` este pagină, nu bottom sheet, în tema Retro.
- Butonul separat `Istoric` a fost eliminat din Retro. Apăsarea cadranului sau a valorii PV schimbă direct
  pe `ENERGIE` cu graficul potrivit; valorile zilnice aleg direct graficul produs/consumat.
- Bara și butoanele active au relief discret: umbră exterioară plus muchie luminoasă sus-stânga și muchie
  întunecată jos-dreapta. Tabul activ este iluminat subtil cu `#f1e169`. Aceeași logică de relief este
  aplicată panourilor, graficelor și statisticilor, fără a colora integral contururile.
- Șuruburile tuturor panourilor au fost mutate cu 3 dp spre interior (inset 9 dp → 12 dp). Sunt desenate
  prin Compose Canvas și nu interceptează apăsările.
- Tema `SIMPLE` își păstrează navigația și foile modale existente.

### 13.24 Release Android v2.01 (2026-07-20)

- **versionCode 12 / versionName 2.01**; fișier semnat: `/opt/solar-monitor/SolarMonitor-v2.01.apk`.
- Dimensiune: **1.018.031 bytes**; SHA-256:
  `3a6d5dbd1f0794f1c5c5dd44d1e8acd6d616c224a048772ebfa88b443eddfec7`.
- `aapt` confirmă pachetul `com.rolling7.solar`, minSdk 26, target/compile SDK 34 și versiunea 2.01 (12).
- `apksigner` confirmă APK Signature Scheme v2 și certificatul SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`, identic cu APK-ul 2.0.
- Upgrade real verificat pe emulator Android 14: instalare APK semnat 2.0 (code 11), urmată de
  `adb install -r` pentru 2.01 (code 12), lansare cu date live și fără crash. Captură:
  `android/build/emulator-artifacts/release-v2.01-signed.png`.
- `assembleDebug`, `lintDebug`, `testDebugUnitTest` (fără teste definite), `lintVitalRelease`, R8 și build-ul
  release semnat au trecut. Schimbările sunt exclusiv Android/UI și documentație; serverul nu necesită rebuild.

### 13.25 Livrare release Android prin Telegram (2026-07-21)

- Release-urile Solar Monitor rămân salvate local în `/opt/solar-monitor/SolarMonitor-v<versiune>.apk` și
  sunt trimise, după verificare, ca document prin `@sun_tattva_access_bot` către chatul administratorului.
- Tokenul și `ADMIN_CHAT_ID` rămân exclusiv pe `root@celestia.go.ro`, în `/opt/sun-tattva/.env`; nu sunt
  copiate pe serverul Solar Monitor, afișate în loguri sau salvate în Git.
- Scriptul versionat `scripts/send-android-release-telegram.sh` verifică pachetul `com.rolling7.solar`,
  versiunea, dimensiunea și SHA-256, copiază temporar APK-ul prin SSH, verifică identitatea botului, trimite
  documentul și șterge copia temporară. `--dry-run` verifică integrarea fără să trimită un mesaj.
- Skill-ul `solar-monitor-release` cere acum această livrare după ce build-ul, semnătura și upgrade-ul au
  fost validate. O eroare Telegram nu invalidează și nu șterge APK-ul local.
- Prima livrare verificată: `SolarMonitor-v2.01.apk`, 1.018.031 bytes, mesaj Telegram ID 45.
