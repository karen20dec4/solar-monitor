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
| Server | **Dell Optiplex 7010 SFF**, i7-3770, 16GB RAM, SSD 240GB, **Linux Mint 21.3** (bază Ubuntu jammy), în beci. IP **192.168.1.199**. |
| Legătură | Cablu USB-A↔USB-B. Invertor USB = chip **Exar XR21B1411** (VID:PID `04e2:1411`, serial `Q3370413461`) → `/dev/ttyUSB0`. |
| Modbus | RTU, **9600 8N1, slave ID 1**, function code **04** (input registers). |

⚠️ Hardware de rezolvat: serverul stă pe capacul invertorului (problemă termică) — de mutat. Wi-Fi dongle ieftin (MT7601U) — eventual Ethernet.

## 3. Arhitectură software (stack „lean", fără MQTT)
```
Invertor --USB(/dev/growatt)--> Collector Python (minimalmodbus, READ-ONLY, 1s)
   |-> InfluxDB bucket `live`    (1s,  retenție 48h)
   |-> InfluxDB bucket `history` (60s, retenție 31 zile)
   |-> ntfy (push pe telefon)  cand se declanseaza o alerta
   `-> Grafana (dashboard `solar-main`, setat ca Home)
```
Tot în **Docker Compose**, `restart: unless-stopped`, pornește la boot. 4 containere:
`solar-collector`, `solar-influxdb`, `solar-grafana`, `solar-ntfy`.

## 4. Fișiere & deploy
- **Sursă (Windows):** `H:\_Growatt\solar-monitor\`
- **Server:** `/opt/solar-monitor/`
- **Flux de lucru:** editezi local → `scp` pe server → `cd /opt/solar-monitor && docker compose up -d [--build] [serviciu]`.
- Execuție: prin **SSH** (`ssh root@192.168.1.199`, cheie deja instalată). Shell-ul local e **PowerShell** (cwd `H:\_Growatt`); pentru scp folosește căi relative (`solar-monitor/...`). Pentru query-uri Flux cu ghilimele, folosește tool-ul **Bash** (sau escape).

Structură:
```
solar-monitor/
  .env                      # TOATE setările (praguri, token, retenții)
  docker-compose.yml
  README.md
  collector/  collector.py, Dockerfile, requirements.txt
  grafana/    provisioning/{datasources,dashboards}/, dashboards/solar.json
  influxdb/   init/10-create-live-bucket.sh
  deploy/     99-growatt.rules, create-grafana-user.sh, set-home-dashboard.sh
```

## 5. Acces
- **SSH:** `ssh root@192.168.1.199` (cheie)
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
| 27 | % | grad de încărcare invertor (load%) |
| **90** | **×0.1 A (semnat, compl. 2)** | **curent baterie** (+ încărcare / − descărcare) — IDENTIFICAT 2026-05-31 ziua, prin corelație cu PV |

⚠️ `REG_COUNT` trebuie să rămână **≥ 91** (avem nevoie de registrul 90).

**Putere baterie** = acum **REALĂ (măsurată):** `battery_power = (reg90×0.1) × battery_voltage` (semn: + încărcare / − descărcare). Câmpuri noi: `battery_current`, `inverter_loss`, `house_source`.

**Sursă consum casă** `house_source` (cod, calculat în collector): `1=PV` / `2=Baterie` / `3=Rețea`. Prag mort 50W. Ordine: încărcare rețea>50→3; descărcare baterie>50→2; PV>50→1; consum fără sursă→3.
Consumul e rutat și pe câmpul sursei active: `house_pv` / `house_bat` / `house_grid` = `output_power` (doar cel activ scris/ciclu). Cardul „Consum casa" are 3 serii (A=house_pv verde ☀️, B=house_bat galben 🔋, C=house_grid roșu ⚡) cu override `displayName`+`color fixed`, `textMode=value_and_name`, range `-8s` → o singură pastilă colorată cu W + emoji sursă.

**Pierdere invertor / consum propriu** (din bilanț de putere, validat ziua ~90–110W):
`inverter_loss = pv_power + battery_discharge_power + grid_charge_power − output_power − battery_charge_power` (clamp ≥0).
- Ziua (încărcare): `= PV − consum − încărcare_baterie_reală` (exact formula cerută de Florin).
- Noaptea (descărcare): `= descărcare_baterie − consum`.
⚠️ **De validat semnul reg90 la descărcare (noaptea)** — vezi TODO #1b.

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

#### #1b — mic follow-up: validare semn reg90 la DESCĂRCARE (noaptea)
Ziua (încărcare) reg90 e pozitiv și corect. Noaptea, când bateria se descarcă, verifică: `docker exec solar-influxdb influx query ...` pe `battery_current` / `battery_power` → trebuie să fie **negativ** (descărcare). Dacă reg90 e unsigned (rămâne 0 sau sare la valoare mare) și există un registru separat de descărcare, ajustează `parse()` (în loc de complement față de 2). Pierderea noaptea ar trebui = `descărcare − consum` (mică, ~zeci de W).

### #2 — Dashboard: power-flow + pagină mobilă (ales de user, neînceput)
- Diagramă flux energetic (PV → baterie/casă/rețea).
- A doua pagină compactă, optimizată pentru telefon.
- Eventual: Tensiune rețea pe jumătate de lățime, ca să facă loc altui panou.

### #3 — Alte idei (opționale)
- Energie zilnică **kWh** (produs/consumat) + sumar zilnic pe telefon — necesită recon registre de energie.
- Acces remote securizat (Tailscale/WireGuard), fără cloud.
- Backup automat config + DB; istoric lung (downsampling 1 an).

---

## 12. Stare curentă (la 2026-05-30)
✅ Monitorizare live 1s + istoric 30 zile, validată cu LCD.
✅ Alerte protecție (consum, baterie jos/sus, supraîncălzire, ieșire pierdută) + watchdog — testate.
✅ Push ntfy pe telefon cu sunet (topic `Alerta_6Kw`).
✅ 100% local, read-only, pornește la boot.
✅ **Putere baterie REALĂ (reg90) + pierdere/consum invertor (~90–110W) — afișat pe dashboard.**
⏳ Rămas: TODO #1b (validare semn reg90 noaptea) și TODO #2 (dashboard power-flow + mobil).

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
