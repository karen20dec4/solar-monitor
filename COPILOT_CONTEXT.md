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
✅ App Android nativă cu teme Retro/Simple, flux animat, grafice istoric și alarmă locală foreground service. Versiune curentă: **versionCode 30 / versionName 3.26**.
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
- Regula de versionare: fiecare APK livrat ca release nou sau trimis pe Telegram primește obligatoriu
  `versionCode + 1` și `versionName + 0.01`. Nu se mai suprascrie și nu se mai retrimite un nume de APK
  folosit anterior. Numai buildurile locale de diagnostic, care nu sunt livrate, pot păstra versiunea.
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

Emulatorul se păstrează pornit numai în timpul dezvoltării sau verificării active. La finalul fiecărei
sarcini care îl folosește se rulează obligatoriu `stop`, apoi se verifică serviciul `inactive/dead`, absența
procesului `qemu-system-x86` și lista ADB goală. Excepție numai dacă utilizatorul cere explicit să rămână
pornit.

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

### 13.26 Retro v4 fotografic hibrid + fundaluri Photoshop (2026-07-23)

- Referința vizuală este `/opt/delete/retro-theme-v4.png`. Pentru fidelitate, tema Retro folosește acum
  un sistem hibrid: șasiul, patina, miniaturile și NAV-ul sunt resurse WebP fotografice, iar valorile live,
  unitățile, acul cadranului, LED-urile, animațiile și zonele tactile rămân native Compose.
- Toate cele patru pagini folosesc acum același `background-v1-optimized.png`, fără delimitări desenate
  pentru carduri. TABLOU suprapune resursele optimizate `pag-tablou-card-ACUM-optimized.png`,
  `pag-tablou-card-FLUX-ENERGETIC-optimized.png` și `pag-tablou-card-NAV-optimized.png`; alpha-ul lor curat
  elimină vechile margini negre.
- Resursele finale sunt în `android/app/src/main/res/drawable-nodpi/`. Cele cinci resurse WebP fotografice
  rămân versionate; sursele lor PNG mari au fost eliminate intenționat ulterior. Scriptul
  `scripts/prepare-retro-ui-assets.sh` importă acum separat instrumentele active din `text-display/`.
- Toate cele patru pagini Retro ocupă ecranul disponibil și nu folosesc scroll vertical. NAV-ul comun este
  fix jos, iar conținutul se măsoară în spațiul rămas. Tema Simple nu este modificată de această regulă.
- Pe TABLOU, ACUM și FLUX sunt ancorate în partea de sus și folosesc raportul natural de aspect al
  exporturilor decupate. Zona rămasă sub ele este rezervată pentru viitoare informații esențiale și
  trebuie păstrată liberă până la aprobarea conținutului.
- Codul de culoare rămâne: verde = solar/normal, albastru = casă/consum, galben = baterie/atenție,
  roșu = rețea/alarmă. Nu există CONTROL și nu s-a adăugat nicio operație de scriere spre invertor.
- Textura fotografică `retro_metal_texture.png` este activă și pe panourile Compose din paginile adiacente;
  testul cu rendererul `swangle` a rămas stabil și reduce clar aspectul flat-vector.
- Emulatorul 36.6.11 este pornit stabil headless cu `-gpu swangle` într-un serviciu tranzitoriu systemd.
  Skill-ul `.codex/skills/solar-monitor-emulator/` și copia instalată în `/root/.codex/skills/` au fost
  actualizate; vechiul `swiftshader_indirect` a produs repetat `SIGSEGV` pe acest host.
- Subcomanda `retro-tabs` deschide și capturează automat toate cele patru taburi, verifică semantica tabului
  activ și eșuează dacă arborele Android expune vreun container cu `scrollable=true`.
- `scripts/audit-retro-ui-assets.sh` verifică fără modificări dimensiunile, sRGB și transparența exporturilor
  Photoshop. Importerul păstrează alpha-ul curat; flood-fill-ul rămâne doar fallback temporar pentru PNG-urile
  opace existente, ca să nu afecteze umbrele și patina viitoarelor exporturi finale.
- Capturile de verificare sunt generate temporar în directorul ignorat
  `android/build/emulator-artifacts/`. Aceste schimbări sunt incluse în release-ul Android 3.0.

### 13.27 Release Android v3.0 — Retro v4 fotografic (2026-07-23)

- **versionCode 13 / versionName 3.0**; APK semnat: `/opt/solar-monitor/SolarMonitor-v3.0.apk`.
- Dimensiune: 2.539.180 bytes; SHA-256:
  `aa7114e69b32f1d237584665d25faa6575aacb9c85173c0e69b38fa7a0832a2d`.
- `aapt` confirmă pachetul `com.rolling7.solar`, minSdk 26, target/compile SDK 34 și versiunea 3.0 (13).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- APK-ul semnat a fost instalat și lansat pe emulatorul Android 14. Toate cele patru taburi au fost
  deschise automat fără crash și fără container scrollabil; atingerea cadranului a selectat ENERGIE/CASA.
  Captura release este `android/build/emulator-artifacts/release-v3.0-signed.png`.
- Livrare Telegram reușită prin `@sun_tattva_access_bot`: mesaj ID 47, nume și dimensiune confirmate de API.
- Release-ul rămâne READ-ONLY; nu s-au adăugat endpointuri sau operații de scriere către invertor.

### 13.28 Corecție semantică flux solar/baterie (feedback telefon, 2026-07-23)

- Fluxurile sunt calculate independent prin `resolveRetroEnergyFlow()`: Panouri → Casă este o conexiune
  directă verde; Panouri → Baterie apare verde numai la încărcare; Baterie → Casă apare galben numai la
  descărcare. Nu se mai folosește segmentul bateriei ca substitut vizual pentru energia solară spre casă.
- Valoarea PV a fost mutată la dreapta miniaturii panourilor, eliberând centrul pentru cablul fizic vertical
  și LED-urile care intră în acoperișul casei.
- Puterea bateriei este verde la încărcare, galbenă la descărcare și olive în repaus. Patru teste unitare
  acoperă încărcarea solară, descărcarea spre casă, zona neutră și încărcarea din rețea.
- Captura de verificare cu date live este
  `android/build/emulator-artifacts/TABLOU-feedback-2.png`.

### 13.29 Exporturi Photoshop optimizate și reglaje FLUX (2026-07-23)

- Contractul aprobat este în `android/build/emulator-artifacts/design/optimized/`: fundal 937×1666,
  ACUM 1386×1011, FLUX 1405×939 și NAV 1835×321. Toate sunt PNG sRGB cu alpha și trec auditul strict.
- `background-v1-optimized.png` este fundalul comun pentru TABLOU, ENERGIE, SISTEM și SETARI. Cardurile
  păstrează raportul natural de aspect. În geometria revizuită, ACUM și FLUX au 95% din dimensiunea
  anterioară și sunt centrate; ACUM este coborât exact 40 px, iar FLUX exact 140 px față de captura
  precedentă. Pozițiile lor sunt independente, ca shrink-ul să nu producă o nouă suprapunere.
- Toate valorile FLUX sunt 18 sp bold. PV a fost mutat la stânga/jos, Casa și Bateria spre dreapta,
  iar starea din dreapta are margine mai mare și se extinde spre stânga.
- Eticheta statică `ACUM` a devenit `Versiune V${BuildConfig.VERSION_NAME}` în `#c9bc93`, deci urmează
  automat versiunea APK-ului; în build-ul curent afișează V3.0.
- NAV-ul fotografic are 95% din dimensiunea anterioară și este centrat, lăsând vizibil fundalul metalic.
- Build-ul debug, testele unitare și lint-ul trec. Toate cele patru taburi au fost verificate în emulator,
  pe același fundal, fără crash și fără scroll vertical.

### 13.30 Hand-off pentru Android Studio pe Windows 10 (2026-07-23)

- Instrucțiunile autonome pentru agentul Windows sunt în `deploy-windows.md`. Proiectul Android trebuie
  deschis din `H:\__Proiecte\_Growatt\solar-monitor\android`, nu din rădăcina repository-ului.
- Clona Windows păstrează istoricul Git, referința `design-v5.png` și cele șase exporturi Photoshop din
  `android/build/emulator-artifacts/design/optimized/text-display/`, dar exclude `.env`, cheia de semnare, parolele,
  `local.properties`, APK-urile, cache-urile și restul build-urilor generate.
- Build-ul debug nu necesită cheia de release. Agentul Windows nu trebuie să genereze o cheie nouă și nu
  trebuie să reseteze schimbările locale livrate în snapshot.

### 13.31 TABLOU v5 — instrumente esențiale sub FLUX (2026-07-23)

- Referința aprobată este `android/build/emulator-artifacts/design-v5.png`; sursele grafice sunt cele șase
  PNG-uri transparente din `android/build/emulator-artifacts/design/optimized/text-display/`.
- TABLOU afișează sub FLUX trei rânduri fără card exterior: Baterie, Invertor și Temperatura. Cadranele au
  exact 42 dp înălțime și aceeași margine dreaptă. Etichetele au exact 30 dp, 29 dp și 34 dp; cadrul
  temperaturii păstrează raportul mai îngust 477:190.
- PNG-urile conțin numai etichetele, rama, patina și unitatea. Valorile rămân dinamice: tensiunea bateriei,
  pierderea/consumul propriu al invertorului și temperatura invertorului sunt desenate prin
  `RetroVfdDisplay`. Apăsarea cadranului bateriei deschide ENERGIE cu graficul tensiunii.
- Layout-ul rămâne complet fix, fără scroll. Captura aprobată din emulatorul Pixel 6 1080×2400 este
  `android/build/emulator-artifacts/design-v5-preview.png`; toate cele patru taburi trec verificarea
  automată `retro-tabs`.
- Release-ul asociat folosește **versionCode 14 / versionName 3.01**.
- Cele patru PNG-uri mari vechi pentru fundal, ACUM, FLUX și NAV au fost șterse manual și intenționat.
  Nu trebuie restaurate; WebP-urile finale rămân versionate, iar scripturile sar peste regenerarea lor când
  întregul set sursă lipsește.

### 13.32 Release Android v3.01 — TABLOU v5 (2026-07-23)

- **versionCode 14 / versionName 3.01**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.01.apk`.
- Dimensiune: **2.798.679 bytes**; SHA-256:
  `1ac25cbc8342561758b614c60d8fda9a6d72029e4934a473ff60e796d5480365`.
- `aapt` confirmă pachetul `com.rolling7.solar`, minSdk 26, target/compile SDK 34 și versiunea 3.01 (14).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- APK-ul semnat a fost instalat și lansat pe emulatorul Android 14, 1080×2400. Captura este
  `android/build/emulator-artifacts/release-v3.01-signed.png`; toate cele patru taburi au fost verificate
  fără crash și fără container scrollabil.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **48**, nume
  `SolarMonitor-v3.01.apk`, dimensiune 2.798.679 bytes.
- Release-ul rămâne READ-ONLY; nu necesită rebuild pentru API sau containerele serverului.

### 13.33 TABLOU v6 — instrumente compacte și valori FLUX digitale (2026-07-23)

- Referința aprobată este
  `android/build/emulator-artifacts/design/optimized/design-v6.png`.
- Cadranele Baterie și Invertor sunt pe același rând, cu minimum 20 dp între ele și cu înălțimea exact
  1,2× față de cadranul Temperatură. Rândul Temperatură rămâne dedesubt, iar întregul grup este ancorat
  jos ca să nu se suprapună peste cardul FLUX pe telefoane mai scurte.
- Etichetele mici `Bat` și `Inv` folosesc verdele Retro `#accc78`; ambele au fost mutate cu 20 px spre
  dreapta și 20 px în sus față de prima randare v6.
- Semnul puterii bateriei are prioritate fără zonă neutră artificială: orice valoare pozitivă înseamnă
  încărcare și este verde, orice valoare negativă înseamnă descărcare și este galben-portocaliu
  `#f1e169`. Testul explicit pentru `-44 W` confirmă descărcarea spre casă.
- Valorile Panouri, Baterie, Casă și Rețea din FLUX folosesc același renderer 7-segment
  `RetroVfdDisplay` ca instrumentele digitale. Unitatea `W` are spațiu rezervat și rămâne vizibilă
  indiferent de semn, numărul de cifre sau font scale.
- Versiunea asociată este **versionCode 15 / versionName 3.02**. Aplicația rămâne READ-ONLY și nu
  necesită modificări de API sau collector.

### 13.34 Release Android v3.02 — TABLOU v6 (2026-07-23)

- **versionCode 15 / versionName 3.02**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.02.apk`.
- Dimensiune: **2.751.499 bytes**; SHA-256:
  `3f7126dd4312d392b510fb0f0fe56510944a703b434ecee483129973c228d278`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.02 (15).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- APK-ul final semnat a fost instalat și randat pe emulatorul Android 14, 1080×2400. Captura este
  `android/build/emulator-artifacts/release-v3.02-signed.png`; toate cele patru taburi au trecut
  verificarea automată fără crash și fără container scrollabil.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **50**, nume
  `SolarMonitor-v3.02.apk`, dimensiune 2.751.499 bytes.
- Release-ul rămâne READ-ONLY; nu necesită rebuild pentru API sau containerele serverului.

### 13.35 ENERGIE Retro — structură fotografică, etapa 1 (2026-07-23)

- Pagina ENERGIE folosește primele trei exporturi Photoshop din
  `android/build/emulator-artifacts/design/Pag-Energie/`: top-card, ENERGIE ASTĂZI și blocul
  CASA/PANOURI/BATERIE cu rama viitorului grafic.
- Resursele Android optimizate sunt `retro_energy_top_artwork.webp`,
  `retro_energy_today_artwork.webp` și `retro_energy_controls_chart_artwork.webp`.
- Valorile PRODUS și CONSUM rămân live, randate nativ prin `RetroVfdDisplay`; imaginile conțin numai
  rama, textura și etichetele statice.
- Al treilea card primește numai înălțimea rămasă. Pagina rămâne fixă, fără scroll, iar NAV-ul nu este
  duplicat: toate taburile folosesc aceeași instanță `RetroBottomNavigation`, ancorată identic jos.
- Butoanele și zona graficului sunt încă strict vizuale. Interacțiunile, selecțiile și graficele vor fi
  adăugate după validarea release-ului pe telefon.
- Pe TABLOU, `Versiune V...`, LED-ul de sursă și textul `CASA DIN ...` au fost mutate exact 7 px în sus.
- Versiunea asociată este **versionCode 16 / versionName 3.03**. Aplicația rămâne READ-ONLY.

### 13.36 Release Android v3.03 — ENERGIE etapa 1 (2026-07-23)

- **versionCode 16 / versionName 3.03**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.03.apk`.
- Dimensiune: **3.151.846 bytes**; SHA-256:
  `c5cc21132c830b43fa9608338e637634fe4ebd90bd69d0d744d93bf9f11df42b`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.03 (16).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- APK-ul final semnat a fost instalat și randat pe emulatorul Android 14, 1080×2400. Captura ENERGIE este
  `android/build/emulator-artifacts/release-v3.03-energie-signed.png`; toate cele patru taburi au trecut
  verificarea automată fără crash și fără container scrollabil.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **51**, nume
  `SolarMonitor-v3.03.apk`, dimensiune 3.151.846 bytes.
- Release-ul rămâne READ-ONLY; nu necesită rebuild pentru API sau containerele serverului.

### 13.37 ENERGIE Retro funcțională (v3.04, 2026-07-24)

- Cele trei exporturi Photoshop rămân stratul fotografic. Compose adaugă numai zone tactile, selecții
  luminoase discrete, titlul dinamic și graficul; nu desenează un card nou peste rama aprobată.
- Sunt funcționale toate cele cinci selecții: Casa (`output_power`), Panouri (`pv_power`), Baterie
  (`battery_voltage`), Producție zilnică (`energy_pv_today`) și Consum zilnic (`energy_load_today`).
  Atingerea celor două afișaje „ENERGIE ASTĂZI” selectează același istoric zilnic.
- Bara de sus are logică distinctă: PRODUCTIE selectează energia PV zilnică, CONSUM selectează energia
  consumată zilnic, iar ISTORIC restaurează ultimul grafic tehnic Casa/Panouri/Baterie (implicit Panouri).
- Selectoarele `7d` și `30d` funcționează pentru toate cele cinci grafice. API-ul READ-ONLY acceptă acum
  aceste intervale și pentru putere/tensiune: 7d = medie la 30 minute, 30d = medie la 2 ore, din bucket-ul
  `history`. Graficele zilnice păstrează agregarea `max` la 1 zi.
- Bar chart-ul este folosit pentru producție/consum zilnic; Casa, Panouri și Baterie folosesc line chart.
  Bateria are axă fixă 48–58 V și praguri roșii la 48 V și 57 V. Titlul, unitatea, culoarea și datele axei
  se schimbă dinamic; verde = solar, albastru = casă, galben = baterie.
- Sunt implementate stări `SE INCARCA`, `NU EXISTA DATE` și eroare cu atingere pentru reîncercare. Zonele
  tactile au descrieri semantice, iar pagina rămâne complet fixă, fără scroll.
- Contractele sunt acoperite de `RetroEnergyInteractionTest` și `api/test_app.py`. În emulator Android 14
  au fost apăsate și validate 11 trasee de interacțiune; cele patru taburi rămân fără container scrollabil.
  Capturile principale sunt `energie-functionala-final.png` și `energie-functionala-baterie-30d.png`.
- API-ul de producție a fost reconstruit și verificat: Casa/Panouri/Baterie întorc 337 puncte pe 7d și
  361 pe 30d; graficele zilnice întorc 8, respectiv 31 puncte.
- Versiunea asociată este **versionCode 17 / versionName 3.04**. Toate operațiile rămân READ-ONLY.

### 13.38 Release Android v3.04 — ENERGIE funcțională (2026-07-24)

- **versionCode 17 / versionName 3.04**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.04.apk`.
- Dimensiune: **3.151.846 bytes**; SHA-256:
  `d88b34450cd8451a572553c55ed4996ec4fad099b4d498b0e88c1c743e57f23f`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.04 (17).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- Upgrade real verificat pe emulator Android 14: instalare release semnat 3.03, apoi `adb install -r`
  pentru 3.04. Cinci scenarii esențiale au fost repetate pe APK-ul R8, inclusiv restaurarea ultimului
  grafic tehnic prin ISTORIC; toate cele patru taburi au rămas fără crash și fără scroll.
- Captura release este `android/build/emulator-artifacts/release-v3.04-energie-signed.png`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **52**, nume
  `SolarMonitor-v3.04.apk`, dimensiune 3.151.846 bytes.
- API-ul a fost reconstruit și este sănătos în producție. Release-ul și endpointurile rămân READ-ONLY.

### 13.39 SISTEM Retro V5 — structură fotografică, etapa 1 (2026-07-24)

- Referința vizuală este
  `android/build/emulator-artifacts/design/Pag-Sistem/pag-sistem-Retro-V5.png`.
- Pagina SISTEM folosește cele două exporturi Photoshop optimizate:
  `pag-sistem-card-SISTEM-optimized.png` și `pag-sistem-card-INFORMATII-optimized.png`.
  Resursele Android sunt `retro_system_top_artwork.webp` și `retro_system_info_artwork.webp`.
- Prima placă conține titlul SISTEM, starea telemetriei și starea conexiunii invertorului. A doua conține
  șase ferestre goale: Consum casă, Panouri, Baterie, Consum invertor, Temperatură și Rețea.
- Valorile nu sunt încă desenate din Compose. Această etapă validează exclusiv aspectul, proporțiile și
  poziționarea; funcționalitatea va fi adăugată numai după aprobarea release-ului pe telefon.
- Ambele carduri folosesc exact proporțiile din compoziția Retro V5, sunt centrate la 95% din lățime și
  rămân ancorate sus. NAV-ul este instanța globală comună, deci poziția lui nu diferă între pagini.
  Pagina rămâne fixă, fără scroll.
- Exportul primit pentru INFORMATII are unitatea statică `V` în fereastra „CONSUM INVERTOR”, deși
  referința finală arată `W`. Imaginea a fost păstrată neschimbată pentru aprobarea vizuală; PNG-ul trebuie
  corectat în Photoshop înaintea etapei funcționale.
- Versiunea asociată este **versionCode 18 / versionName 3.05**. Aplicația rămâne READ-ONLY.

### 13.40 Release Android v3.05 — SISTEM etapa 1 (2026-07-24)

- **versionCode 18 / versionName 3.05**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.05.apk`.
- Dimensiune: **3.514.780 bytes**; SHA-256:
  `ac95b5e23c66210d7faf24a7094358eae239ebb1234393066cdbcfdc4ca3c8f5`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.05 (18).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- Upgrade real verificat pe emulator Android 14: instalare release semnat 3.04, apoi `adb install -r`
  pentru 3.05. Toate cele patru taburi au trecut verificarea fără crash și fără container scrollabil.
- Captura release la dimensiunea compoziției este
  `android/build/emulator-artifacts/release-v3.05-sistem-signed.png`; verificarea suplimentară la
  1080×2400 este `android/build/emulator-artifacts/release-v3.05-sistem-signed-1080x2400.png`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **54**, nume
  `SolarMonitor-v3.05.apk`, dimensiune 3.514.780 bytes.
- Modificarea este exclusiv Android și READ-ONLY; API-ul și containerele serverului nu necesită rebuild.

### 13.41 SISTEM Retro V5 funcțional (2026-07-24)

- Deformarea observată pe 1080×2400 a fost eliminată: artwork-urile folosesc rapoartele reale
  `1024/301` și `971/942`, cu `ContentScale.Fit`. Primul card pornește exact la aceeași poziție ca în
  TABLOU, iar distanța fizică dintre cele două carduri este 40 px.
- Cele șase afișaje sunt acum randate nativ peste ferestrele fotografice și se actualizează la fiecare
  răspuns live: Consum casă = `output_power` W, Panouri = `pv_power` W, Baterie =
  `battery_voltage` V, Consum invertor = `inverter_loss` W, Temperatură = `inverter_temp` °C și
  Rețea = `grid_voltage` V.
- Codul cromatic rămâne semantic: casă albastru, solar verde, baterie galben, rețea roșu. Temperatura
  este verde sub 45 °C, galbenă între 45–54,9 °C și roșie de la 55 °C.
- Afișajele acoperă inclusiv unitățile statice din bitmap; astfel, eroarea `V` imprimată în sursa
  „CONSUM INVERTOR” este înlocuită corect în aplicație cu `W`. PNG-ul Photoshop poate fi totuși corectat
  ulterior pentru a elimina defectul din sursă.
- Cardul superior afișează ora ultimei telemetrii în fusul Europe/Bucharest. Semantica accesibilă include
  conexiunea, codul invertorului și ora ultimei actualizări.
- Atingerea valorilor Casă, Panouri sau Baterie deschide tabul ENERGIE cu graficul potrivit selectat.
  Traseele au fost apăsate și validate individual în emulator.
- `RetroSystemReadingsTest` verifică mapările, formatarea, starea fără date, pragurile de temperatură și
  câmpurile de navigare. `testDebugUnitTest`, `lintDebug` și `assembleDebug` trec.
- Verificarea Android 14 la etalonul 1080×2400 a trecut pentru toate cele patru taburi, fără crash și fără
  scroll. Captura este `android/build/emulator-artifacts/pag-sistem-v5-functional-1080x2400.png`.
- Funcționalitatea este inclusă în release-ul **versionCode 19 / versionName 3.06**.

### 13.42 Monitor SISTEM, mini-grafice și consolă live (2026-07-24)

- Al treilea artwork primit, `pag-sistem-card-monitor-optimized.png`, este importat ca
  `retro_system_monitor_artwork.webp` la raportul real 1030/531. Cardul este ancorat jos și compensează
  exact padding-ul paginii, astfel încât rama lui se îmbină vizual cu NAV-ul global fără suprapunere.
- Monitorul afișează agregate reale ale serverului: CPU %, memorie %, uptime și trafic upload în KB/s.
  API-ul citește numai `/proc`, montat în container ca `/host/proc:ro`; nu montează Docker socket, nu
  expune hostname, procese, IP-uri sau nume de interfețe.
- `/solar/latest` include noile câmpuri nullable `server_cpu_percent`, `server_memory_percent`,
  `server_memory_used_mb`, `server_memory_total_mb`, `server_upload_kbps` și
  `server_uptime_seconds`. Aplicația afișează `—` dacă ele lipsesc, nu zero inventat.
- Mini-graficele BATERIE și TEMP folosesc câte maximum 120 puncte din ultima oră. Bateria folosește
  istoricul existent `battery_voltage`; API-ul acceptă acum și
  `/solar/history?field=inverter_temp&range=1h`, cu bucket `live` și fereastră de 30 secunde. Seriile sunt
  reîncărcate la 60 secunde numai cât pagina SISTEM este vizibilă.
- Consola nativă are cinci linii live: temperatură, stare API + upload, sănătatea invertorului și codul
  lui, starea/puterea/tensiunea bateriei și conectarea/tensiunea rețelei. Textele provin numai din date
  măsurate sau reguli explicite de prag; nu sunt lipite în bitmap.
- API-ul de producție a fost reconstruit exclusiv pentru serviciul `solar-api`. Verificare: health OK,
  metrici reale CPU/RAM/upload/uptime și aproximativ 120 puncte de temperatură pe 1h. InfluxDB, collectorul și
  accesul READ-ONLY la invertor nu au fost modificate.
- Teste: 6 teste API în container și 16 teste Android trec; `lintDebug` și `assembleDebug` trec. Toate
  cele patru taburi rămân fixe, iar scurtăturile Casă/Panouri/Baterie au fost reapăsate cu succes.
- Captura etalon este
  `android/build/emulator-artifacts/pag-sistem-v5-monitor-functional-1080x2400.png`.
- Funcționalitatea este inclusă în release-ul **versionCode 19 / versionName 3.06**.

### 13.43 Release Android v3.06 — monitor SISTEM și etichetă TABLOU (2026-07-24)

- **versionCode 19 / versionName 3.06**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.06.apk`.
- Eticheta de pe cardul „CONSUM CASĂ” este acum `V3.06`, fără prefixul „Versiune”. Folosește același
  font RetroMono, aceeași dimensiune de 7sp, aceeași greutate și aceeași spațiere ca textul
  „CASA DIN SOLAR”; culoarea galben-retro `#C9BC93` este păstrată. Față de poziția anterioară,
  eticheta este mutată fizic cu 8 px spre dreapta și încă 5 px în sus.
- Dimensiune: **3.573.257 bytes**; SHA-256:
  `40c79176b958e3f363df37e5b5b15b1030074e0da813ef373c78bcc1be0a0616`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.06 (19).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- Upgrade real verificat pe emulator Android 14: instalare release semnat 3.05, apoi `adb install -r`
  pentru 3.06. Release-ul semnat a trecut verificarea tuturor celor patru taburi la 1080×2400, fără
  crash și fără container scrollabil.
- Captura TABLOU a release-ului semnat este
  `android/build/emulator-artifacts/release-v3.06-tablou-signed-1080x2400.png`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **57**, nume
  `SolarMonitor-v3.06.apk`, dimensiune 3.573.257 bytes.
- Release-ul include monitorul SISTEM și extensiile API documentate la 13.42. API-ul de producție a fost
  deja reconstruit și verificat; accesul la invertor rămâne strict READ-ONLY.

### 13.44 Rebuild v3.06 — layout SISTEM pentru Samsung Note 9 (2026-07-24)

- Versiunea rămâne intenționat **versionCode 19 / versionName 3.06**, la cererea utilizatorului. APK-ul
  semnat actual suprascrie buildul anterior la `/opt/solar-monitor/SolarMonitor-v3.06.apk`.
- Cardul „INFORMAȚII SISTEM” este mutat fizic cu 10 px în sus. Cardul monitorului rămâne ancorat de NAV,
  dar înălțimea lui este calculată adaptiv și comprimată numai pe axa Y când ecranul este mai scurt.
  Layout-ul rezervă minimum 20 px fizici între cele două carduri, fără scroll.
- Titlul Compose „MONITOR SISTEM”, care ieșea din artwork și se suprapunea pe telefoanele 18.5:9, a fost
  eliminat complet.
- Linia vizibilă `BATERIE · 60 MIN ... | TEMP · 60 MIN ...` a fost eliminată. Mini-graficele dinamice
  rămân, iar spațiul eliberat este folosit de consola live.
- Fontul consolei a fost dublat de la 5sp la 10sp; titlul consolei a fost dublat de la 5,5sp la 11sp.
  Coloanele pentru oră, etichetă și valoare au spațiere explicită și păstrează toate cele cinci linii.
- Eticheta `V3.06` din TABLOU a crescut exact cu 25%, de la 7sp la 8,75sp, și rămâne bold.
- Verificare vizuală făcută atât la 1080×2400, cât și pe profil Samsung Note 9 1440×2960 / densitate 560.
  Captura Note 9 este
  `android/build/emulator-artifacts/v3.06-layout2-sistem-note9-final-1440x2960.png`; captura release
  semnată la etalon este
  `android/build/emulator-artifacts/release-v3.06-layout2-sistem-signed-1080x2400.png`.
- `testDebugUnitTest`, `lintDebug`, `assembleDebug`, verificarea semnăturii și reinstalarea peste aceeași
  versiune au trecut. Toate cele patru taburi rămân fixe, fără crash și fără container scrollabil.
- APK: **3.573.257 bytes**; SHA-256:
  `274f40c81567fe6b00bfb6c18a2a82aeb2d849c568fd9043a0b9c40266d4daf6`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **58**, nume
  `SolarMonitor-v3.06.apk`, dimensiune 3.573.257 bytes.

### 13.45 Release Android v3.07 — identitate unică și verificare Telegram end-to-end (2026-07-24)

- Refolosirea identității v3.06 a creat ambiguitate pe telefon: cele două APK-uri aveau același
  `versionCode`, `versionName`, nume și aceeași dimensiune, iar un fișier descărcat anterior putea fi
  redeschis din cache. APK-ul local v3.06 era corect și nu conținea titlul vechi, dar livrarea nu putea fi
  deosebită sigur de buildul precedent.
- Corecția folosește **versionCode 20 / versionName 3.07** și fișierul unic
  `/opt/solar-monitor/SolarMonitor-v3.07.apk`. Eticheta din TABLOU afișează vizibil `V3.07`.
- Buildul a pornit cu `clean`; `testDebugUnitTest`, `lintDebug`, `assembleDebug`, R8 și semnarea release
  au trecut. Upgrade real verificat de la release-ul semnat v3.06 (19) la v3.07 (20).
- Audit direct în `classes.dex`: stringul/titlul învechit `MONITOR SISTEM` este absent. Captura release
  semnată pe profilul Note 9 1440×2960 este
  `android/build/emulator-artifacts/release-v3.07-sistem-signed-note9-1440x2960.png`.
- APK: **3.573.257 bytes**; SHA-256:
  `3b3c59bad6405f8288b95c42c34ca0bb96ff1478a5388207821539f5fe36857a`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **59**, nume
  `SolarMonitor-v3.07.apk`, `file_unique_id` **AgADzyAAAn_NGFM**. După upload, scriptul a descărcat fișierul
  înapoi din Telegram prin `getFile`; SHA-256 Telegram este identic:
  `3b3c59bad6405f8288b95c42c34ca0bb96ff1478a5388207821539f5fe36857a`.
- Regula permanentă a skill-ului de release: fiecare APK livrat primește obligatoriu
  `versionCode + 1` și `versionName + 0.01`; nicio versiune/nume livrat anterior nu se reutilizează.
- Modificările sunt Android, documentație și verificarea transportului Telegram. API-ul și containerele
  serverului nu necesită rebuild; accesul la invertor rămâne strict READ-ONLY.

### 13.46 Politică resurse emulator Android (2026-07-25)

- Procesul `qemu-system-x86_64-headless` identificat era AVD-ul nostru `SolarMonitor_API_34`, nu un serviciu
  de producție. Rula de aproximativ 46 de ore și ajunsese la circa 266% CPU cumulat și 4,9 GB RAM.
- Emulatorul a fost oprit prin helperul versionat. Verificare finală: serviciul
  `solar-monitor-emulator.service` este `inactive/dead`, `MainPID=0`, procesul QEMU lipsește și ADB nu
  listează niciun dispozitiv.
- Emulatorul nu este necesar pentru collector, InfluxDB, API, Grafana sau aplicația instalată pe telefon.
  Helperul îl pornește la nevoie pentru dezvoltare, capturi și verificări APK.
- Regula permanentă este salvată atât în skill-ul proiectului, cât și în copia globală: la finalul oricărei
  sarcini emulator/release se rulează obligatoriu `stop` și se verifică eliberarea resurselor, exceptând
  numai cererea explicită de a-l lăsa pornit.

### 13.47 SETĂRI Retro V5 și navigare swipe (2026-07-27)

- Pagina SETĂRI folosește exporturile Photoshop din
  `android/build/emulator-artifacts/design/Pag-Setari/`: cardul SETĂRI, TEMA DASHBOARD și cardul
  ALARMĂ/SUNET. Resursele Android lossless sunt `retro_settings_top_artwork.webp`,
  `retro_settings_theme_artwork.webp` și `retro_settings_alarm_artwork.webp`.
- Cele trei carduri păstrează raportul natural, pornesc la cota fizică de 40 px folosită pe celelalte
  pagini și se redimensionează uniform numai când înălțimea disponibilă o cere. Pagina rămâne fixă,
  fără scroll, iar NAV-ul este componenta globală comună.
- Zonele Compose proporționale păstrează funcționale tema Retro/Simple, activarea alarmei, pragul
  0–10000 W, cooldown-ul 0–600 s, vibrația, alegerea soneriei și testul alarmei. Numele soneriei este
  dinamic; valorile diferite de 5000 W/300 s primesc un strat dinamic peste traseul fotografic.
- Toate cele patru pagini acceptă swipe stânga/dreapta cu prag de 72 dp și tranziție laterală de 240 ms.
  Gesturile scurte/verticale sunt ignorate, marginile nu depășesc TABLOU/SETĂRI, iar controalele copil
  au prioritate. Nu este folosit `HorizontalPager`, deci ierarhia Android nu expune scroll.
- Testele unitare, lint și debug build trec. În emulator Android 14, NAV-ul și swipe-urile
  TABLOU–ENERGIE–SISTEM–SETĂRI au fost verificate în ambele direcții; glisarea pragului a păstrat tabul
  SETĂRI, iar alarma a fost activată și oprită prin noile zone tactile.
- La începutul etapei au fost șterse 336 fișiere generate vechi din rădăcina
  `android/build/emulator-artifacts/`, eliberând 201.729.333 bytes. Directorul `design/` și toate sursele
  Photoshop curente au fost păstrate.
- Captura etalon este
  `android/build/emulator-artifacts/pag-setari-v5-preview-1080x2400.png`. Versiunea rămâne
  **versionCode 20 / versionName 3.07**; nu a fost creat încă un release nou.
- Preview-ul 1080×2400 a fost livrat prin `@sun_tattva_access_bot`: mesaj ID **61**,
  `file_unique_id` **AQADzQ5rGxugOVN8**. După verificare, emulatorul a fost oprit complet; serviciul este
  `inactive/dead`, QEMU lipsește și ADB nu listează dispozitive.

### 13.48 Release Android v3.08 — SETĂRI Retro V5 (2026-07-27)

- Cardul ALARMĂ folosește exportul revizuit
  `SETARI-card-alarma-optimized-v2.png`. Geometria rămâne 960×1077, sRGB cu alpha; conversia lossless în
  `retro_settings_alarm_artwork.webp` are diferență pixel cu pixel zero. Nuanța olive/bronz este acum
  apropiată de cardurile SETĂRI și TEMA, fără modificarea layoutului sau a zonelor tactile.
- **versionCode 21 / versionName 3.08**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.08.apk`.
- Dimensiune: **6.998.764 bytes**; SHA-256:
  `1fec210030d3a82156b7a51997158a467ba600d5286af341be237747e46f2c00`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.08 (21).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- Upgrade real verificat pe emulator Android 14: instalare release semnat 3.07, apoi `adb install -r`
  pentru 3.08. Toate cele patru taburi au trecut verificarea fără crash și fără container scrollabil;
  swipe dreapta/stânga SETĂRI–SISTEM–SETĂRI a fost verificat pe APK-ul R8.
- Captura release semnată este
  `android/build/emulator-artifacts/release-v3.08-setari-signed-1080x2400.png`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **62**, nume
  `SolarMonitor-v3.08.apk`, dimensiune 6.998.764 bytes, `file_unique_id` **AgADeyEAAhugOVM**. Fișierul
  descărcat înapoi din Telegram are același SHA-256.
- Release-ul modifică numai aplicația Android. API-ul și containerele serverului nu necesită rebuild;
  accesul la invertor rămâne strict READ-ONLY.

### 13.49 Release Android v3.09 — comutatoare Retro animate (2026-07-28)

- Cardul ALARMĂ pornește din exportul cu șine goale
  `SETARI-card-alarma-optimized-v3.png`. Etichetele de scală eronate din acel export (`5080`, `7300`,
  `30u`) au fost înlocuite cu etichetele corecte din V2, păstrând textura fotografică și șinele goale.
- `knob.png` este folosit pentru cursoarele native Prag și Cooldown. Poziția lui urmărește valorile
  dinamice salvate, iar valorile `W` și `s` sunt randate de Compose.
- `toggle-off.png` și `toggle-on.png` au fost separate în trei resurse lossless: șină, buton metalic și
  lumină. Pentru Alarmă și Vibrație, butonul se deplasează fizic stânga/dreapta în 260 ms, iar lumina
  verde se aprinde și se stinge progresiv. Nu este o simplă schimbare statică de bitmap.
- Starea textuală `OPRITĂ` / `ACTIVĂ` este dinamică și nu mai este lipită peste textul din imagine.
  Activarea alarmei și vibrația persistă după oprirea și relansarea aplicației.
- **versionCode 22 / versionName 3.09**; APK semnat:
  `/opt/solar-monitor/SolarMonitor-v3.09.apk`.
- Dimensiune: **7.071.252 bytes**; SHA-256:
  `822ce12ae5944a98cc69ef9b295c7860edf8b3cd3ad925b7466fba10c70d2b7a`.
- `aapt` confirmă pachetul `com.rolling7.solar`, target/compile SDK 34 și versiunea 3.09 (22).
  `apksigner` confirmă APK Signature Scheme v2 și certificatul permanent Borealis Media, SHA-256
  `b892e453841228510aa4c08f9a164652baa0005638279cc18572dde677d293f6`.
- Upgrade real verificat pe emulator Android 14: instalare release semnat 3.08, apoi `adb install -r`
  pentru 3.09. Cele patru taburi sunt fixe, fără crash și fără container scrollabil. Comutatoarele au
  fost apăsate, verificate în timpul cursei și reverificate după relansare.
- Au trecut 20 teste unitare Android, `lintDebug`, `assembleDebug`, R8 și `assembleRelease`.
  Captura semnată este
  `android/build/emulator-artifacts/release-v3.09-setari-signed-1080x2400.png`.
- Livrare Telegram confirmată prin `@sun_tattva_access_bot`: mesaj ID **63**, nume
  `SolarMonitor-v3.09.apk`, dimensiune 7.071.252 bytes, `file_unique_id` **AgADzx8AAhugQVM**;
  SHA-256 raportat de transport este identic.
- Release-ul modifică numai aplicația Android și rămâne strict READ-ONLY. API-ul și containerele
  serverului nu necesită rebuild.

### 13.50 Incident firewalld: aplicația pe „aștept date" 13 ore (2026-08-19 / 2026-08-20)

**Simptom.** Aplicația Android a rămas pe „aștept date" din 19 aug ~20:56 până pe 20 aug ~10:15.
Invertorul, cablul USB, collector-ul și InfluxDB erau perfect sănătoase: bateria raporta 56 V, datele
din bucket-ul `live` erau proaspete la secundă. Nicio alertă nu s-a declanșat, pentru că nimic din ce
era monitorizat nu se stricase.

**Cauza.** `firewalld` (instalat de Virtualmin pe 2026-07-28, activ de atunci la fiecare boot) a fost
**repornit**, nu pornit. Lanțul exact, din jurnal:

1. `20:53:45` — `apt upgrade -y` (firefox-esr).
2. `20:55:58` — **needrestart** pornește `restart-dbus.service` („Transient dbus restarter"); dbus cade
   și antrenează în cascadă NetworkManager, wpa_supplicant și firewalld.
3. `20:56:02` — firewalld repornit.

Docker își înregistrează bridge-urile în zona firewalld `docker` doar ca **runtime config**. La restart
firewalld încarcă numai configul permanent, iar Docker 26.1.5 își re-adaugă interfețele *exclusiv* la
semnalul D-Bus `Reloaded`, nu după un restart complet. Bridge-urile au rămas fără zonă și au căzut pe
politica implicită `reject with icmpx admin-prohibited` din `filter_FORWARD_POLICIES`.

**De ce simptomul a fost înșelător.** Regula `ct state {established, related} accept` a lăsat intacte
conexiunile deja deschise — de-aia collector-ul a continuat să scrie în InfluxDB toată noaptea. Doar
conexiunile **noi** au fost respinse: `solar-api` → InfluxDB `Errno 113`, Caddy → `api`
`502 dial tcp 172.18.0.2:8000: connect: no route to host`.

**firewalld nu poate fi oprit.** `/etc/fail2ban/jail.d/virtualmin-firewalld.conf` setează
`banaction = firewallcmd-rich-rules` (suprascrie default-ul Debian `nftables`) pentru toate cele 7
jail-uri: sshd, postfix, postfix-sasl, dovecot, proftpd, webmin-auth, usermin-auth. Confirmat cu
`fail2ban-client get sshd actions`.

**Remedieri aplicate (2026-08-20).** Detaliile complete în `deploy/README-firewalld.md`.

1. Bridge-urile puse **permanent** în zona `docker` (target `ACCEPT`), ca să supraviețuiască
   restart / reload / reboot.
2. Numele bridge-ului fixat la `br-solar` în `docker-compose.yml` prin
   `driver_opts: com.docker.network.bridge.name`. Implicit era `br-<id-rețea>`, iar id-ul se schimbă la
   fiecare `docker compose down` — regula firewalld ar fi rămas legată de o interfață inexistentă și
   incidentul s-ar fi repetat tăcut.
3. **Fără** `--add-source` pe subnet-uri. Ar fi supraviețuit redenumirii bridge-ului, dar
   `net.ipv4.conf.enp1s0.rp_filter = 2` (loose) înseamnă că un pachet din internet cu sursă falsificată
   din spațiul RFC1918 ar fi fost încadrat în zona `docker` (ACCEPT) în loc de `public`; zonele pe sursă
   au prioritate față de cele pe interfață, deci nu s-ar fi putut corecta cu o rich rule în `public`.
4. `/etc/needrestart/conf.d/90-solar-monitor-critical.conf` scoate `dbus`, `firewalld`, `docker` și
   `containerd` din restartul automat la `apt upgrade`. Când needrestart le semnalează ca învechite,
   răspunsul corect este un **reboot planificat**.
5. Sondă end-to-end `solar-watchdog.timer` (la 15 min) — vezi mai jos.

**Capcană la recreare.** Primul `docker compose up` după recrearea rețelei a eșuat cu
`Failed to Setup IP tables: Unable to enable NAT rule: (dbus: connection closed by user)`: daemonul
Docker avea conexiunea D-Bus către firewalld învechită după restartul firewalld. Leac:
`systemctl restart docker`.

**Sonda end-to-end.** `deploy/solar-watchdog.{sh,service,timer}` verifică exact lanțul pe care îl vede
telefonul — `Caddy (9443, TLS intern) → api:8000 → InfluxDB → collector`. Eșec = HTTP ≠ 200, JSON
invalid, **sau** `timestamp` mai vechi de `MAX_AGE_S` (180 s implicit); ultima condiție prinde cazul
„API răspunde dar datele sunt înghețate". Alertă ntfy la primul eșec, apoi la fiecare al 4-lea
consecutiv (o dată pe oră), plus mesaj de revenire la normal. Testate izolat toate trei căile (HTTP
căzut, date vechi, revenire) cu `STATE_DIR` și `NTFY_BASE` redirecționate, ca să nu plece alerte reale.

**Verificat după remediere.** `systemctl restart firewalld` nu mai rupe nimic: zona `docker` păstrează
`br-solar`, `https://vyra.go.ro:9443/solar/latest` răspunde `200`, vârsta datelor sub 1 s, fail2ban activ.
Nimic din acest incident nu a atins invertorul; sistemul rămâne strict **READ-ONLY**.

### 13.51 Predare proiect către Gemini — documentație de continuare (2026-08-23)

**Context.** Utilizatorul dă proiectul mai departe unui alt agent (Gemini CLI). Prima sarcină
planificată pentru el: schimbarea intervalelor graficelor din `7d`/`30d` în `1d`/`7d` și livrarea
unui release pe Telegram. Documentația existentă era scrisă pentru cineva care avea deja contextul
sesiunilor anterioare, deci s-a scris un set de intrare autonom.

**Fișiere noi.**

- `GEMINI.md` (rădăcina repo-ului) — punct de intrare citit automat de Gemini CLI, echivalentul lui
  `CLAUDE.md`, în română: invariantul READ-ONLY cu ambele decizii închise (§13.14, §13.17), cele șase
  containere și lanțul de rețea până la telefon, tabelul de protocoale, `.env` și `REG_COUNT`,
  comenzile uzuale, cum funcționează `parse()` și alertele, regulile UI-ului Retro, emulatorul,
  regula de versionare, convențiile de limbă, capcanele (firewalld, D-Bus, SNI Caddy, grafana `chmod`)
  și lista de fișiere care nu se comit niciodată.
- `docs/DEZVOLTARE.md` — ghidul de dezvoltare propriu-zis: harta verticală a celor 9 straturi cu
  fișierul de editat pentru fiecare, contractul `/solar/latest` și `/solar/history`, regula de alegere
  a bucket-ului în funcție de interval, exemplul complet al schimbării de intervale, capcana
  etichetelor din poze, procedura de release, livrarea pe Telegram, checklist de încheiere și lista
  „ce NU face proiectul".

**Fișiere actualizate.**

- `README.md` — secțiune nouă **Documentație**: tabel cu toate cele 10 fișiere de documentație din
  repo și ce conține fiecare (utilizatorul a cerut explicit lista).
- `CLAUDE.md` — trimitere către `docs/DEZVOLTARE.md` și `GEMINI.md` cu obligația de a le ține
  sincronizate; capcană nouă documentată la secțiunea Retro (vezi mai jos).

**Descoperire tehnică relevantă pentru prima sarcină.** Etichetele `7d` / `30d` de pe butoanele de
interval **nu sunt randate de Compose** — sunt desenate în
`retro_energy_controls_chart_artwork.webp` (1024×1266 px, spațiu logic Compose 1045×1292, factor
0,9799). Zonele: butonul stâng x 358–514, butonul drept x 514–671, y 382–463 în pixeli de asset.
Codul Kotlin desenează doar zona tactilă transparentă și evidențierea, peste poză. Schimbarea numai
a stringului din Kotlin ar produce un buton care scrie una și cere alta.

**A doua descoperire.** Pagina ENERGIE Retro folosește aceleași două butoane de interval pentru toate
cele cinci câmpuri (`output_power`, `pv_power`, `battery_voltage`, `energy_pv_today`,
`energy_load_today`), iar `/history` validează cu listă albă și dă `400` la un `range` necunoscut.
Deci `1d` trebuie adăugat în `HISTORY_FIELDS` din `api/app.py` la toate cinci, altfel butonul nou dă
„ISTORIC INDISPONIBIL". Pentru cele două grafice **bară** există o problemă de semantică:
`energy_pv_today` / `energy_load_today` sunt contoare zilnice cumulative (`u32(regs,48)*0.1`,
`u32(regs,85)*0.1`), agregate cu `window: 1d, fn: max` — un interval `1d` dă **o singură bară**.
Ambele opțiuni corecte sunt descrise în `docs/DEZVOLTARE.md` §2.2; alegerea îi revine utilizatorului.
`window: 1h, fn: max` este **greșit** pe aceste câmpuri (ar da o scară crescătoare, nu consum orar).

**Nemodificat.** Nu s-a atins niciun cod — doar documentație. Cele două fișiere Android modificate
local (`build.gradle.kts` la versionCode 23 / versionName 3.10 și offset-ul din `RetroDashboard.kt`)
rămân lucrul în curs al utilizatorului și nu au fost comise. Sistemul rămâne strict **READ-ONLY**.

### 13.52 Interval 1d cu agregare orară (24 linii) și 7d/30d — Release v3.10 (2026-08-23)

**Modificare.** La cererea utilizatorului s-a implementat **Opțiunea A**:
1. **Grafice linie (`output_power`, `pv_power`, `battery_voltage`):**
   - Suport pentru intervalele `1d` și `7d`.
   - Pe intervalul `1d`, datele sunt agregate din InfluxDB bucket `history` cu `window: 1h, fn: mean` (rezultând 24 puncte/linii orare pe ultimele 24 de ore).
   - Pe axa X și grila graficului sunt afișate repere orare formatate `HH:mm` (ex: 15:00, 20:00, 01:00, 06:00, 11:00).
2. **Grafice bară (`energy_pv_today`, `energy_load_today`):**
   - Păstrează intervalele `7d` (7 bare) și `30d` (30 bare) agregate cu `window: 1d, fn: max`, etichetate cu `dd.MM`.
3. **UI Retro dinamic peste bitmap:**
   - Butoanele de interval din `RetroEnergyControlsArtwork` afișează dinamic etichetele corespunzătoare seriei selectate (`1d` / `7d` pentru linie, respectiv `7d` / `30d` pentru bară), cu un badge stilizat retro/industrial peste bitmap.
4. **API (`api/app.py`):**
   - Adăugat intervalul `1d` în `HISTORY_FIELDS` pentru toate cele 5 serii de istoric.
   - Containerul `solar-api` a fost reconstruit și relansat (`docker compose up -d --build api`).
5. **Verificare și Release:**
   - Testele unitare Python (`api/test_app.py`, 6 teste) și Android (`testDebugUnitTest`, 20 teste) au trecut 100%.
   - Verificare vizuală completă pe emulatorul headless KVM (`emulator-check.sh verify`, `retro-tabs`, capturi `panouri-1d-fixed.png`, `panouri-7d.png`, `casa-1d-hourly.png`).
   - Build de release semnat `SolarMonitor-v3.10.apk` (versionCode 23, versionName 3.10), SHA-256 `6573cf4c0c8cfbc8e2b1f6e9f761a37be56ffcad3d58dc7ec17c381c8292bf2a`, 7.071.252 bytes.
   - Livrare automată confirmată pe Telegram prin `@sun_tattva_access_bot` (message_id **77**, `file_unique_id` `AgADJCIAAhuGWFA`).
   - Sistemul rămâne strict **READ-ONLY**.

### 13.52 Release Android v3.21 — intervalul `1d` cuantificat orar (2026-08-23)

**Cerința.** La selectarea intervalului `1d`, graficul trebuie cuantificat pe oră — ultimele 24 h,
24 de bare — peste tot unde există intervalul, nu doar pe graficele linie.

**Ce era greșit.** Graficele linie (`output_power`, `pv_power`, `battery_voltage`) aveau deja
`window: 1h`. Cele două grafice bară aveau `1d` cu fereastră de o zi, deci produceau 2 bare.

**Cauza de fond.** `energy_pv_today` și `energy_load_today` sunt contoare zilnice cumulative care
se resetează la miezul nopții (`u32(regs,48)*0.1`, `u32(regs,85)*0.1`). O fereastră de 1 h cu
`fn: max` ar fi dat scara crescătoare a contorului, nu energia pe oră. Corect e derivarea în Flux:
`aggregateWindow(every: 1h, fn: max)` urmat de `difference(nonNegative: true)`.

**Modificări.**
- `api/app.py`: cheie opțională `"diff": True` în configurația unui interval; când e prezentă se
  inserează `difference(nonNegative: true)` între `aggregateWindow` și `keep`.
- `api/app.py`: `1d` pentru cele două câmpuri bară devine
  `{"start": "-25h", "window": "1h", "fn": "max", "diff": True}`. `-25h` pentru că `difference()`
  consumă primul punct, deci ies exact 24 de valori orare.
- `api/test_app.py`: trei teste pentru prezența/absența lui `difference` (9 teste în total, toate trec).
- `MainActivity.kt`: `HistoryStatsGrid` primește `range`; la `1d` etichetele barelor devin
  „Medie/ora", „Max ora", „Ultima ora". Intervalele devin uniform `1d`/`7d`, inclusiv pe bare.

**Verificat.** Cele cinci câmpuri raportează 24–25 de puncte la `range=1d`. `energy_pv_today`
întoarce energii orare reale: 0 noaptea, 0,3 kWh la răsărit (08:00), vârf **2,9 kWh la 14:00**,
total 18,6 kWh pe 24 h. Confirmat vizual în emulator pe ambele grafice bară — 24 de bare cu
etichete orare, fără derulare pe pagina Retro. Butoanele `1d`/`7d` se randează nativ, fără urme
ale vechiului text „7d"/„30d" din WebP.

**Limitare cunoscută.** Ora resetului de la miezul nopții este eliminată de `nonNegative`, deci
ziua are 24 de bare acoperind 25 de ore, cu o oră lipsă în jurul orei 01:00. La producție e
invizibilă (e 0 oricum); la consum se pierd ~0,3 kWh din total.

**Flux de lucru.** Prima folosire a schemei „Claude planifică, Gemini execută, Claude verifică":
branch `gemini/1d-orar`, specificație cu query-ul Flux validat în prealabil pe InfluxDB și criteriu
de acceptare explicit, execuție cu `agy --model gemini-3.1-pro-high` (2 min 33 s), verificare
independentă, merge în `main`.

**Release.** `versionCode` 25, `versionName` 3.21, `SolarMonitor-v3.21.apk`, 7.071.252 bytes,
SHA-256 `d615ec233ce9993e23e47103b24fc17c92f6d10bef718a2dabd84e05f8c71120`.
API-ul de pe server a fost reconstruit (`docker compose up -d --build api`) — obligatoriu, altfel
aplicația nouă ar primi „ISTORIC INDISPONIBIL" la `1d`.

**Observat, nerezolvat.** Poza `retro_settings_alarm_artwork.webp` conține două greșeli de scris:
„CODLDOWN" în loc de „COOLDOWN" și „Inpuls seurt la deslansarea alarmei" în loc de „Impuls scurt
la declanșarea alarmei". Sunt pictate în bitmap, deci cer un export Photoshop nou.

Sistemul rămâne strict **READ-ONLY** față de invertor.

### 13.53 Redesign tema Simple + release v3.22 (2026-08-23)

**Cerința.** Tema `Simple` „arăta nedefinit și avea aceleași culori ca Retro". Ținta: mai curată,
mai luminoasă, altă paletă, senzație High-Tech, **pe fundal gri** (referința primită avea prea
mult alb). Referință vizuală: `/opt/pics-logs-copilot/SIMPLE-new.png`.

**Etapa 1 — paleta.** Toate culorile temei Simple stăteau în 11 constante, deci schimbarea a fost
mică și a atins tot. Varianta aleasă de utilizator dintre trei propuse: gri-albastru rece —
pagină `#DDE3EA`, card `#F5F8FB`, card evidențiat `#FFFFFF`, accent `#0B72E7`. Accentele au fost
închise față de versiunea pe fond negru (verde `#1F9D55`, ambru `#C2790A`): pe gri deschis un ton
luminos are contrast prea mic.

Două capcane Material 3, ambele măsurate în captură, nu ghicite:
- tema folosea `darkColorScheme` deși e luminoasă; `tonalElevation` amestecă `primary` în
  suprafață, deci toate cardurile ieșeau spălate în verde;
- `surfaceTint = Color.Transparent` **nu** rezolvă: fiind `0x00000000`, M3 îi aplică alfa-ul de
  elevație și compune **negru** peste card. Măsurat `#E4E6E9` în loc de `#F5F8FB`. Corect e
  `surfaceTint = chrome.panel`, ca amestecul să fie neutru.
- bara de stare și cea de navigare urmează acum fundalul temei, cu `isAppearanceLight*`.

**Etapa 2 — patru taburi fixe.** Simple era o pagină cu derulare plus trei `ModalBottomSheet`.
Trece la structura Retro: același enum `RetroTab`, aceeași stare `retroTabName` (deci tabul
supraviețuiește comutării de temă), bară de navigare proprie cu patru VectorDrawable.
TABLOU / ENERGIE / SISTEM nu au niciun nod `scrollable`; SETĂRI se derulează deliberat.
Pe ENERGIE singurul nod scrollabil e rândul **orizontal** de chipuri (996×127 px), nu o derulare
verticală — verificat prin `uiautomator dump`.

**Etapa 3 — cadranul și ilustrațiile.** `SimpleGauge` desenat integral cu Canvas: arc de 240°
de la 150°, scală 0..7000 W etichetată în kW, ac triunghiular animat, zona ambru pornind de la
pragul de alarmă din setări. Ilustrațiile 3D (panou, baterie, casă, stâlp) generate cu Gemini și
prelucrate local.

**De reținut despre `generate_image` al lui `agy`:** livrează **JPEG fără canal alfa**, chiar dacă
fișierul are extensia `.png`. Cerut fundal transparent, a *desenat* tabla de șah ca pixeli reali.
Eliminarea fundalului s-a făcut local, cu floodfill din puncte semănate pe **tot conturul** —
fundalul generat e în degrade, iar floodfill compară cu culoarea seminței, deci un singur colț
lasă o bandă opacă. Rezultat: WebP fără pierderi, cu alfa real, 33–54 KB fiecare.

**Corecții de geometrie după inspecția capturilor** (niciuna prinsă de compilator):
- raza cadranului era calculată din `minDimension`, deci se tăia pe lățime; arcul ocupă `2R` pe
  lățime și `1.5R` pe înălțime, raza trebuie limitată de ambele;
- valoarea centrală se suprapunea peste eticheta „0";
- linia panou→casă avea decupajul mai lung decât distanța dintre noduri, deci nu se desena deloc;
- pictograma SISTEM avea un contur de dreptunghi în plus și subcăi degenerate de lățime zero
  (`M9 7v2H9V7z`), deci se randa ca un document cu pătrățele;
- pista inactivă a slider-elor folosea `surfaceVariant` implicit Material și ieșea lavandă.

**Flux de lucru.** A doua și a treia etapă executate de Gemini 3.1 Pro prin `agy`, pe branch-uri
separate, pe bază de specificație scrisă în prealabil, cu verificare independentă în emulator.
Notă operațională: `agy` trebuie lansat **în fundal** — o rulare a fost tăiată la 10 minute de
limita de execuție a comenzilor, cu treaba aproape terminată.

**Release.** `versionCode` 26, `versionName` 3.22, `SolarMonitor-v3.22.apk`, 7.217.932 bytes,
SHA-256 `bd40a4cc68c245b0b0c2e161461f450a675a6dd51a85ccc46388f07354266d69`, livrat pe Telegram
prin `@sun_tattva_access_bot`, mesaj **80**, SHA-256 descărcat înapoi identic.

**Rămas de curățat.** `EnergyOverview`, `DailySummary` și `EnergyNode` nu mai sunt apelate după
restructurare. Tema Retro nu a fost atinsă vizual; singura modificare acolo e
`retroSwipeNavigation` trecut de la `private` la `internal`.

Sistemul rămâne strict **READ-ONLY** față de invertor.

### 13.54 TABLOU compactat, cadran cu relief + release v3.23 (2026-08-23)

Cerințe primite după v3.22, toate implementate:

1. **Cardul PANOURI dispare**; PV1/PV2/TOTAL intră sub cadran, în același card, despărțite de o
   linie. Se câștigă un card întreg.
2. **Ilustrația bateriei regenerată** — prima variantă avea fundal în degrade care lăsa un
   dreptunghi gri vizibil pe card.
3. **Titlurile de card scoase** de pe TABLOU; spațiul câștigat a mers în înălțimea diagramei de
   flux (200 → 268 dp), ca panoul să stea vizibil mai sus decât casa.
4. **Praguri fixe pe cadran**: galben de la 5500 W, roșu de la 6000 W până la 7000 W. Nu mai
   depind de pragul de alarmă din setări.
5. **Relief 3D**: gradient radial pe fața discului (lumină stânga-sus), umbră proprie pe inel,
   gradient + umbră pe ac și pe butuc, `Shadow` în stilul textului pentru valoare.

**Bug de geometrie, prins doar în captură.** Fața cadranului e un cerc **întreg**, dar raza era
calculată pentru sectorul de 240° (care ocupă `2R` pe lățime dar doar `1.5R` pe înălțime), deci
discul ieșea din card peste rândul PV. Rezolvat cu `BoxWithConstraints`: raza încape pe ambele
axe, iar valoarea se poziționează relativ la rază, nu la marginea de jos a cutiei.

**Rețetă pentru ilustrațiile generate.** Cea mai fiabilă cale găsită: cere-i lui `generate_image`
fundal **magenta plat** (`#FF00FF`), apoi local
`-fuzz 38% -transparent magenta` + `-channel A -morphology Erode Disk:2 +channel`. Erodarea
șterge franjurile magenta rămase pe umbra difuză. Magenta nu apare în obiectele astea, deci
decupajul e curat — spre deosebire de fundalul gri-albastru, unde floodfill-ul se oprește în
umbră și lasă un dreptunghi.

**Curățat**: `SimpleCardHeader`, `EnergyOverview`, `DailySummary`, `EnergyNode` — nu mai erau
apelate după restructurare.

**Release.** `versionCode` 27, `versionName` 3.23, `SolarMonitor-v3.23.apk`, 7.213.764 bytes,
SHA-256 `a7c2d2d5b8b7dbb9f1e303344a9f22cff2fc3c371a040b01c96567e13ed18c62`, Telegram mesaj **81**,
SHA-256 descărcat înapoi identic. Sistemul rămâne strict **READ-ONLY**.

### 13.55 Valori cu relief, citiri gravate + release v3.24 (2026-08-23)

Cerințe după v3.23, toate implementate:

1. **Valorile în wați cresc cu ~30%** și primesc aceeași umbră comună (`ValueShadow`): rândul PV
   18 → 23 sp, valorile din diagrama de flux 19 → 25 sp, valoarea cadranului 36 → 46 sp.
   Unitățile cresc proporțional.
2. **Diagrama de flux se strânge** de la 268 la 226 dp — după mărirea fontului distanța
   panou–casă devenise prea mare și legătura nu se mai citea ca flux.
3. **Cele trei citiri de jos** (tensiune baterie, autoconsum invertor, temperatură invertor —
   același set ca pe TABLOU-ul Retro) devin `EngravedReadout`.

**Rețeta pentru efectul de gravură**, cerut de utilizator („sculptate, umbră negativă, ceva
high-tech"), fără nicio imagine:

- adâncitura are fundul cu gradient vertical (`#D5DDE7` → `#F2F6FA`), deci pare scobită;
- peste ea, un contur de 3,5 dp cu gradient vertical: gri închis la 70% sus, aproape transparent
  la mijloc, alb pur jos. Muchia de sus în umbră + muchia de jos în lumină = adâncitură;
- cifra e desenată **de două ori**: o copie albă decalată cu (1 dp, 1,5 dp), apoi textul colorat
  peste ea. Compose acceptă un singur `Shadow` pe text, deci copia decalată e singura cale să
  obții gravură (lumină jos-dreapta) în loc de relief (umbră jos-dreapta).

Verificat în emulator: TABLOU fără noduri `scrollable`, totul încape deasupra barei de navigare,
inclusiv o valoare de patru cifre în cadran (testat la 1670 W).

**Release.** `versionCode` 28, `versionName` 3.24, `SolarMonitor-v3.24.apk`, 7.213.764 bytes,
SHA-256 `329ff2b998f7cd65cbcaa80173ab46d50c9a2b2116754e92f215580d68bd3cba`, Telegram mesaj **82**,
SHA-256 descărcat înapoi identic. Sistemul rămâne strict **READ-ONLY**.

### 13.56 TABLOU adaptiv la înălțimea ecranului + release v3.25 (2026-08-24)

**Bugul.** Pe telefonul real — Samsung Note 9, 1440×2960, densitate 560 — rândul de jos al paginii
TABLOU era tăiat de bara de navigare: se vedeau doar etichetele BATERIE / INVERTOR / TEMP, nu și
cifrele.

**Cauza, și lecția.** Înălțimile erau fixe în `dp`, calibrate pe AVD-ul Pixel 6
(1080×2400 @ 440 dpi = **393×873 dp**). Note 9 are 1440/3.5 × 2960/3.5 = **411×846 dp** — cu 27 dp
mai puțin pe înălțime, plus o bară de stare mai înaltă. Marja dispăruse.

> **Profilul implicit al emulatorului nu e reprezentativ pentru telefonul utilizatorului.** O
> verificare făcută doar pe Pixel 6 poate trece în timp ce pe Note 9 ecranul e rupt. Comută
> profilul cu `adb shell wm size 1440x2960` + `wm density 560` și verifică **ambele**; la final
> `wm size reset` și `wm density reset`.

**Rezolvarea** nu a fost ajustarea câtorva `dp`, ci layout adaptiv:

- rândul de antet, rândul PV și rândul citirilor gravate rămân fixe — sunt bugetul minim și
  trebuie să fie mereu complet vizibile;
- cardul cu cadranul și cel cu diagrama împart restul prin `weight` (0,54 / 0,46), cu minime de
  150 dp, respectiv 170 dp;
- `EnergyFlow` scalează ilustrațiile între 44 și 58 dp după înălțimea primită și recalculează din
  ea pozițiile nodurilor și decupajele liniilor, ca acestea să cadă în continuare peste centrele
  imaginilor;
- fontul valorii din cadran și raza etichetelor devin **proporționale cu raza cadranului**. Cu
  font fix, pe cadranul mai mic de pe Note 9 cifrele ajungeau peste etichetele „0" și „7".

Verificat pe ambele profile: toate cele patru taburi încap, cifrele gravate complet vizibile,
niciun nod `scrollable` pe TABLOU și SISTEM (pe ENERGIE singurul e rândul orizontal de chipuri).

**Flux de lucru.** Structura adaptivă executată de Gemini 3.1 Pro prin `agy`; a raportat criteriile
ca îndeplinite, dar ratase suprapunerea valoare/etichete din cadran — prinsă la verificarea
vizuală. Scalarea fontului și reechilibrarea greutăților, corectate manual.

**Release.** `versionCode` 29, `versionName` 3.25, `SolarMonitor-v3.25.apk`, 7.213.764 bytes,
SHA-256 `f0b01c5a4a07052fee881f8e880a060c599a11d896fedbba8ba1c7f62ad0e426`, Telegram mesaj **83**,
SHA-256 descărcat înapoi identic. Sistemul rămâne strict **READ-ONLY**.

### 13.57 Release Android v3.26 (2026-09-24)

Release nou, fără modificări funcționale Android față de v3.25. `versionCode` 30,
`versionName` 3.26, APK semnat `/opt/solar-monitor/SolarMonitor-v3.26.apk`,
7.213.764 bytes, SHA-256 `95f108d4a9851c3170c13a989909eacb6f57e9b6fe9b4c39ea68a4dbac84bf94`.
Trimis prin @sun_tattva_access_bot, mesaj **112**; SHA-256 al fișierului descărcat din
Telegram este identic. Sistemul rămâne strict **READ-ONLY**.
