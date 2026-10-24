# DEZVOLTARE — cum adaugi o funcție în Solar Monitor

> Ghid operațional pentru agentul/omul care continuă proiectul.
> Presupune că ai citit deja `GEMINI.md` (sau `CLAUDE.md`) — mai ales invariantul READ-ONLY.

---

## 1. Harta verticală: unde trăiește un câmp de date

Aproape orice funcție nouă atinge mai multe straturi. Înainte să editezi, localizează
toate straturile pe care le traversează, altfel obții o funcție pe jumătate: UI care cere
ceva ce API-ul refuză, sau API care servește ceva ce nimeni nu afișează.

| # | Strat | Fișier | Ce faci aici |
|---|---|---|---|
| 1 | Invertor → registre | `collector/collector.py`, funcția `parse()` și clasa `BalanceDeriver` | `parse()` mapează registre brute în câmpuri cu nume; tot ce vine din bilanțul de puteri (pierdere, deficit, import rețea, `grid_power`, sursa casei) e în `BalanceDeriver`; teste în `collector/test_collector.py` |
| 2 | Alerte | `collector/collector.py`, `build_alerts()` | praguri, debounce, cooldown, histerezis |
| 3 | Stocare | InfluxDB, bucket `live` (1s/48h) și `history` (medii pe 60s/31z) | nimic de editat; doar alegi din care bucket citești |
| 4 | Expunere JSON | `api/app.py` — listele `FIELDS` și `HISTORY_FIELDS` | ce câmpuri și ce intervale sunt permise aplicației |
| 5 | Client HTTP | `android/.../SolarRepository.kt` | `SolarData` / `HistorySeries`, parsarea JSON |
| 6 | Stare + config UI | `android/.../MainActivity.kt` (`DashboardHistoryMetrics`), `RetroEnergyInteraction.kt` | ce metrici există, ce intervale, care e implicit |
| 7 | Desen UI | `android/.../RetroDashboard.kt` (Retro) / `MainActivity.kt` (Simple) | zone tactile, etichete, grafice |
| 8 | Fotografie | `android/app/src/main/res/drawable-nodpi/*.webp` | **textul desenat pe șasiu** (vezi §3) |
| 9 | Grafana | `grafana/dashboards/solar.json` | opțional, dashboard-ul de pe desktop |

**Regula de aur:** un câmp nou trebuie adăugat *și* în `FIELDS` din `api/app.py`, altfel
`/solar/latest` nu îl returnează niciodată, oricât de corect l-ar scrie collector-ul.

### Contractul API în două rânduri

```
GET /solar/latest                          -> JSON plat cu toate câmpurile din FIELDS + timestamp
GET /solar/history?field=<f>&range=<r>     -> { field,label,unit,chart,range,window,points[],stats }
GET /solar/bms                             -> câmpurile `bms` plate + timestamp + alarms[]
GET /solar/grid-month                      -> { month: "2026-10", since, kwh }  (rețea de la 1 a lunii, pentru factură)
```

`/history` face **validare strictă cu listă albă**: dacă `field` nu e cheie în `HISTORY_FIELDS`
sau `range` nu e cheie în `HISTORY_FIELDS[field]["ranges"]`, răspunde **400** cu lista permisă.
Clientul Android tratează orice cod ≠ 200 ca `null` și afișează „ISTORIC INDISPONIBIL".

De aceea: **orice interval nou trebuie adăugat mai întâi în API**, altfel butonul din aplicație
va exista dar va da mereu eroare.

### Ce bucket alegi pentru un interval

| Interval cerut | Bucket | De ce |
|---|---|---|
| ≤ 6 ore | `live` | are rezoluție 1 s, dar reține doar 48 h |
| `2h` / `1d` energie (kWh) | `live` | integrala puterii pe ferestre de 10 min / 1 h (`integral_of` în `HISTORY_FIELDS`); contoarele au pas de 0,1 kWh și se resetează la 23:51 |
| `7d` energie (kWh) | `history` | maximul zilnic al contorului, ferestre aliniate la miezul nopții: exact 7 bare, inclusiv azi |

Barele poartă ora de **început** a ferestrei (`timeSrc: "_start"`), iar intervalul lor e aliniat la
fereastră. Pentru liniile de putere/tensiune, statisticile (min/max/medie/ultima) vin din datele
brute (`_exact_stats_query`, `stats_bucket`), nu din punctele graficului: la `1d` vârful mediilor
orare ascundea vârful real.
| ≥ 24 ore | `history` | medii pe 60 s, reține 31 zile |
| > 31 zile | **imposibil** | datele nu mai există; nu inventa un interval mai lung |

`window` (dimensiunea ferestrei de agregare) se alege ca să iasă ~100–300 de puncte pe grafic.
Prea multe puncte = JSON gras și grafic ilizibil pe telefon; prea puține = grafic în trepte.

---

## 2. Exemplu complet: schimbă intervalele graficelor din 7d/30d în 1d/7d

Aceasta e **prima sarcină** cerută de utilizator. E și cel mai bun exemplu de felie verticală,
pentru că atinge API, Kotlin, fotografie și release.

### 2.1 Ce trebuie schimbat — inventarul exact

```
api/app.py                                          # adaugă range-ul "1d" la 5 câmpuri
android/.../RetroEnergyInteraction.kt:9             # RetroEnergyRanges = listOf("7d", "30d")
android/.../RetroDashboard.kt:735-754               # cele două zone tactile, etichete + valori
android/.../MainActivity.kt:1425-1442               # defaultRange + ranges, tema Simple
android/app/src/main/res/drawable-nodpi/retro_energy_controls_chart_artwork.webp
```

Găsește-le oricând cu:

```bash
rg -n '"7d"|"30d"|7 zile|30 zile' api/app.py android/app/src/main/java/com/rolling7/solar/
```

### 2.2 API — adaugă `1d`

În `api/app.py`, dicționarul `HISTORY_FIELDS`. Pagina ENERGIE din tema Retro folosește
**aceleași două butoane de interval pentru toate cele 5 câmpuri** (`output_power`, `pv_power`,
`battery_voltage`, `energy_pv_today`, `energy_load_today`), deci `1d` trebuie adăugat la
**toate cinci**, nu doar la unul.

Pentru graficele **linie** (`output_power`, `pv_power`, `battery_voltage`), `1d` e practic
`24h` sub alt nume:

```python
"1d": {"start": "-1d", "window": "5m", "bucket": "history", "fn": "mean"},
```

⚠️ **Pentru graficele bară (`energy_pv_today`, `energy_load_today`) atenție la semantică.**
Aceste câmpuri sunt **contoare zilnice cumulative** care se resetează la miezul nopții
(`u32(regs, 48) * 0.1`, respectiv `u32(regs, 85) * 0.1`). De aceea agregarea lor e
`window: "1d", fn: "max"` — maximul de peste zi *este* totalul zilei.

Consecință: un interval `1d` cu fereastră `1d` produce **o singură bară**. Un grafic cu o
bară nu spune nimic. Ai două opțiuni corecte:

- **(recomandat)** păstrează pentru barele de energie perechea `7d`/`30d` și aplică `1d`/`7d`
  doar graficelor linie — adică `RetroEnergyRanges` devine dependent de câmpul selectat;
- sau adaugă `1d` și la bare cu `window: "1d"` și acceptă conștient o singură bară.

**Nu** încerca `window: "1h", fn: "max"` pe barele de energie: fiind contor cumulativ, ai
obține o scară crescătoare pe parcursul zilei, nu consum orar. Un consum orar real ar cere
o metrică nouă calculată prin `difference()` în Flux — funcție separată, nu parte din
sarcina asta.

Dacă alegi varianta recomandată, întreabă utilizatorul înainte, pentru că schimbă
comportamentul a două butoane față de cererea literală.

**Verificare, înainte de a atinge Android:**

```bash
docker compose up -d --build api
for f in output_power pv_power battery_voltage energy_pv_today energy_load_today; do
  echo "--- $f"
  curl -sk --resolve vyra.go.ro:9443:127.0.0.1 \
    "https://vyra.go.ro:9443/solar/history?field=$f&range=1d" | head -c 200; echo
done
```

Fiecare trebuie să întoarcă `points` nevid. Dacă vezi `{"error":"range nepermis"...}`,
API-ul nu s-a reconstruit — `--build` e obligatoriu, `api/app.py` intră în imagine.

Rulează și testele API (Flask nu e instalat pe gazdă, deci într-un container efemer):

```bash
docker run --rm -v /opt/solar-monitor/api:/app:ro -w /app python:3.12-slim \
  sh -c "pip install -q -r requirements.txt && python -m unittest discover -p 'test_*.py'"
```

### 2.3 Kotlin — lista de intervale

`android/app/src/main/java/com/rolling7/solar/RetroEnergyInteraction.kt:9`

```kotlin
internal val RetroEnergyRanges = listOf("7d", "30d")   // -> listOf("1d", "7d")
```

`normalizedRetroEnergyRange()` respinge orice valoare din afara listei și cade pe primul
element, iar `MainActivity.kt:394` inițializează starea cu `RetroEnergyRanges.first()` —
deci implicitul se schimbă automat. Nu mai e nimic de ajustat pentru starea inițială.

⚠️ `selectedRange` e `rememberSaveable`. La utilizatorii care actualizează aplicația,
starea salvată poate conține încă `"30d"`; `normalizedRetroEnergyRange()` o corectează, dar
verifică pe emulator că prima deschidere după update arată `1d` selectat, nu niciun buton.

### 2.4 Kotlin — zonele tactile Retro

`android/app/src/main/java/com/rolling7/solar/RetroDashboard.kt`, în
`RetroEnergyControlsArtwork`, cele două blocuri `RetroEnergyTouchTarget` de la ~linia 735:

```kotlin
RetroEnergyTouchTarget(
    description = "Interval 7 zile",          // <- textul de accesibilitate
    selected = selectedRange == "7d",
    ...
    onClick = { onRangeClick("7d") }
)
```

Schimbă **toate trei**: `description`, comparația din `selected` și argumentul din `onClick`.
`description` ajunge în semantica de accesibilitate și e citit de `retro-tabs` — dacă îl uiți,
verificarea automată poate trece dar TalkBack va minți.

Coordonatele (`offset`/`width`/`height`) **nu se schimbă** — butoanele rămân în aceleași
locașuri din fotografie.

### 2.5 Kotlin — tema Simple

`android/app/src/main/java/com/rolling7/solar/MainActivity.kt`, în `DashboardHistoryMetrics`,
intrările `energy_pv_today` și `energy_load_today`:

```kotlin
defaultRange = "7d",
ranges = listOf("7d", "30d"),
```

Aici etichetele butoanelor sunt **generate din listă** (`RangeChip(label = range)`), deci nu
există text desenat de corectat. Tema Simple e mai simplu de modificat decât Retro tocmai
din acest motiv.

### 2.6 Fotografia — pasul care se uită cel mai des

Vezi §3 de mai jos. **Fără el, butonul va scrie „7d" și va cere `1d`.**

### 2.7 Verificare vizuală și release

```bash
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh retro-tabs
# uită-te efectiv la android/build/emulator-artifacts/retro-tab-energie.png
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh stop
```

⚠️ **Testează pe telefonul utilizatorului, nu pe AVD-ul brut.** Telefonul e un Samsung Galaxy Note 9:
1080×2220, 420 dpi, butoane de navigare (48 dp), font ~130%. Pixel 6 are bara de stare de 45 dp și gesturi, deci
un layout care încape pe el poate fi tăiat pe telefon (`COPILOT_CONTEXT.md` §13.56, §13.66). `verify` și
`layout-check` aplică singure profilul `note9-large` (`SOLAR_EMULATOR_PROFILE=none` îl sare); mai sunt
`profile note9|note9-large|reset`. Utilizatorul trece mai târziu pe un Samsung S22 FE și va trimite o captură —
atunci se adaugă profilul lui.

Readu profilul implicit la final (`profile reset`). Soluția durabilă nu e calibrarea de `dp` pe un singur ecran,
ci layout adaptiv cu `weight` și `BoxWithConstraints`.

Apoi §4 (Release) și §5 (Telegram).

---

## 3. Capcana etichetelor din poze

Șasiul Retro e fotografic. În poză, butoanele de interval scriu încă **„7d" / „30d"**.
Din v3.27 ele sunt acoperite de **plăci native** (`RetroEnergyTouchTarget` cu `labelText`),
care împart lățimea ramei (365..685 în spațiul logic) la câte intervale are
`RetroEnergyRanges` — acum trei: `2h`, `1d`, `7d`. Textul vizibil vine deci din Kotlin;
pixelii vechi rămân doar sub plăci. Din v3.28 la fel sunt acoperite rândul „PRODUCȚIE ZILNICĂ /
CONSUM ZILNIC" (acum PUTERE · W / ENERGIE · kWh, sau TENSIUNE · V la baterie) și „Ultima
actualizare: ACUM" de jos (acum de când pornesc datele și ce reprezintă un punct). Restul
etichetelor de pe șasiu sunt în continuare pixeli.

Din v3.29 fanta vechilor taburi PRODUCȚIE / CONSUM / ISTORIC nu mai e în
`retro_energy_top_artwork.webp`: a fost acoperită cu tabla placii printr-un petic
(`scripts/patch-retro-energy-top.py`, arătat utilizatorului înainte de release, cf. regula de mai
jos). Același script decupează LED-ul (px 840,53, 120 × 120 din asset-ul 1024 × 218) și îl
recolorează în `retro_energy_led_orange.webp` / `retro_energy_led_red.webp`; Compose le pune exact
peste LED-ul verde pictat când ultimele date live au peste 10 s, respectiv peste 1 min
(`liveLedFor` în `EnergySelection.kt`). Dacă vine un export Photoshop nou al plăcii, regenerează
și suprapunerile din el.

Din v3.30 TABLOU nu mai are placa „Temperatura" (nici cuvântul pictat, `retro_dashboard_label_temperature`):
citirile BMS (SOC, curent, diferența dintre celule) folosesc `retro_dashboard_dial_blank.webp`, adică placa
temperaturii cu „°C"-ul pictat șters de `scripts/make-retro-blank-dial.py` (intrarea e PNG-ul original din
commit-ul `dd0aaf5`). Unitățile sunt text Compose, deci se pot schimba din cod. Rândul „ALARMĂ BATERIE"
din SETARI și monitorul BMS din SISTEM sunt Compose pur (fără pixeli noi), deci nu cer export Photoshop.

Din v3.32 rândul „CE urmărești" are **patru** plăci (CASA / PANOURI / BATERIE / RETEA). Asset-ul original avea trei
plăci late cu pictograma și textul pictate; `scripts/patch-retro-energy-subjects.py` (intrarea e asset-ul de la
commit-ul `581d1c5`) face din prima o placă goală, din ea patru plăci mai înguste (capete cu șuruburi + un mijloc
de textură) și le așază pe același loc, cu golurile reale dintre plăcile vechi. Pictograma (casă, grilă, baterie,
stâlp) și numele sunt desenate de Compose (`RetroSubjectLabel`), coordonate 56 + 237,8·i, lățime 219,4, în spațiul
logic 1045 × 1292. Petecul păstrează canalul alfa al asset-ului (colțurile rotunjite sunt transparente). E un
plasture ImageMagick/PIL: arată-l utilizatorului în capturi înainte de release; un export Photoshop nou îl înlocuiește.

Asset: `android/app/src/main/res/drawable-nodpi/retro_energy_controls_chart_artwork.webp`
— **1024 × 1266 px**, desenat în spațiul logic 1045 × 1292 folosit de Compose
(factor 1024/1045 ≈ **0,9799**).

Poziția celor două etichete pictate (acum acoperite) în pixeli de asset:

| Buton | x (asset) | y (asset) |
|---|---|---|
| stânga (acum „7d") | 358 – 514 | 382 – 463 |
| dreapta (acum „30d") | 514 – 671 | 382 – 463 |

Verifică oricând ce scrie efectiv acolo:

```bash
magick android/app/src/main/res/drawable-nodpi/retro_energy_controls_chart_artwork.webp \
  -crop 400x120+330+370 +repage -resize 300% /tmp/range_buttons.png
# apoi deschide /tmp/range_buttons.png
```

**Cum repictezi corect:**

1. Sursa canonică sunt exporturile Photoshop ale utilizatorului, în
   `android/build/emulator-artifacts/design/optimized/text-display/`. Dacă acolo există o
   variantă actualizată, folosește-o — nu picta manual peste WebP-ul final.
2. Dacă nu există, **cere utilizatorului un export nou**. El deține fișierele Photoshop;
   fontul retro, patina și zgârieturile nu se reproduc convingător cu ImageMagick, iar un
   petic vizibil strică tot panoul.
3. Un plasture ImageMagick e acceptabil doar ca soluție temporară și doar dacă îl arăți
   utilizatorului într-un screenshot înainte de release.

Înainte de `scripts/prepare-retro-ui-assets.sh` rulează întotdeauna:

```bash
scripts/audit-retro-ui-assets.sh --strict
```

Cele patru PNG-uri sursă mari au fost șterse intenționat; resursele WebP finalizate rămân
urmărite în git.

> Aceeași capcană există pentru orice altă etichetă scrisă pe șasiu: numele taburilor din
> `retro_bottom_navigation_artwork.webp`, titlurile din `retro_energy_top_artwork.webp`,
> etichetele cadranelor. **Regula: dacă textul nu apare într-un `Text(...)` din Kotlin, e în poză.**

---

## 4. Release Android

### 4.1 Versionarea — obligatoriu

**Fiecare APK livrat utilizatorului sau trimis pe Telegram primește `versionCode + 1` și
`versionName + 0.01`**, chiar dacă utilizatorul cere „rebuild la aceeași versiune".
Nu suprascrie și nu retrimite un nume de fișier deja livrat. Doar un build local de
diagnostic, nelivrat, poate păstra versiunea curentă.

`android/app/build.gradle.kts`:

```kotlin
versionCode = 23
versionName = "3.10"
```

### 4.2 Procedura

```bash
cd /opt/solar-monitor

# 1. starea repo-ului și versiunea curentă
git status --short
rg -n "versionCode|versionName" android/app/build.gradle.kts

# 2. incrementează versiunea în android/app/build.gradle.kts

# 3. build semnat
scripts/build-android-release.sh
```

Scriptul compilează `:app:assembleRelease`, copiază rezultatul în rădăcina repo-ului ca
`SolarMonitor-v<versionName>.apk` și tipărește versiune, cale, dimensiune, SHA-256 și
`aapt dump badging`.

Implicite pe serverul HP: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`,
`ANDROID_HOME=/opt/android-sdk`, `/opt/gradle-8.9/bin/gradle`.

**Verifică obligatoriu în ieșirea scriptului:**
- pachetul este `com.rolling7.solar`;
- `versionCode` și `versionName` sunt cele așteptate;
- notează calea APK, dimensiunea și SHA-256.

Dacă Gradle raportează task-uri `UP-TO-DATE`, release-ul poate fi totuși valid — ai
încredere în APK doar după verificarea `aapt` + SHA-256.

Dacă scriptul nu e executabil după checkout: `chmod +x scripts/build-android-release.sh`
sau rulează-l cu `bash`.

### 4.3 Când e nevoie și de rebuild pe server

- Un release Android **pur** nu cere `docker compose up -d --build`.
- Dacă ai modificat `api/`, `collector/`, `caddy/` sau `docker-compose.yml`, rulează
  (sau amintește utilizatorului) `cd /opt/solar-monitor && docker compose up -d --build api`.
- **Pentru sarcina cu intervalele: DA, e nevoie** — `api/app.py` se schimbă. Aplicația nouă
  fără API-ul nou = „ISTORIC INDISPONIBIL" la fiecare apăsare.

---

## 5. Livrarea release-ului pe Telegram

```bash
scripts/send-android-release-telegram.sh /opt/solar-monitor/SolarMonitor-v<versionName>.apk
```

Fără argument, scriptul deduce singur numele din `versionName`.

### Cum funcționează

Trimite prin botul **`@sun_tattva_access_bot`**. Tokenul și `ADMIN_CHAT_ID` **nu sunt în acest
repo** — stau pe altă mașină, în `/opt/sun-tattva/.env` pe `root@celestia.go.ro`, iar scriptul
ajunge acolo prin SSH și rulează venv-ul de acolo. Ai nevoie de acces SSH funcțional
(`BatchMode`, fără parolă) către `root@celestia.go.ro`.

Scriptul refuză să trimită dacă:
- APK-ul nu există sau are nume nesigur (acceptă doar `[A-Za-z0-9._-]+\.apk`);
- pachetul din `aapt dump badging` nu e `com.rolling7.solar`;
- `versionCode`/`versionName` lipsesc;
- botul sau `ADMIN_CHAT_ID` nu sunt configurate pe gazda remote.

Pentru diagnosticarea livrării, fără a trimite nimic:

```bash
scripts/send-android-release-telegram.sh --dry-run
```

### Reguli

- **Nu tipări, nu copia local și nu comite niciodată tokenul botului.**
- Nu modifica worktree-ul Sun Tattva (care e murdar) doar ca să trimiți un release Solar Monitor.
- Confirmă în raport numele fișierului întors de API, dimensiunea și SHA-256-ul descărcat
  înapoi din Telegram (`getFile`) — trebuie să fie identic cu cel local.
- Un eșec Telegram **nu** invalidează și **nu** șterge APK-ul local. Raportează-l ca atare
  și nu reface build-ul cu o versiune nouă doar din cauza asta.

---

## 6. Checklist de încheiere a unei sarcini

1. `git status --short` — stagează **doar** fișierele intenționate.
   `.env`, `keystore.properties`, `key-Rolling7`, `*.apk` nu se comit niciodată.
2. Consemnează în `COPILOT_CONTEXT.md` o secțiune `### 13.<n+1> ...` cu: ce s-a schimbat,
   versiunea livrată, SHA-256, id-ul mesajului Telegram, ce a fost verificat vizual.
   Numerotarea continuă de la ultima secțiune existentă.
3. Actualizează `README.md` / `GEMINI.md` / `CLAUDE.md` dacă s-a schimbat o comandă, un
   container, un port sau o regulă.
4. Commit cu mesaj în stil conventional commits (`feat(android):`, `fix(api):`, `docs:`),
   corpul în română.
5. `git push`.
5b. Pentru orice modificare a paginilor Retro: `emulator-check.sh layout-check` (aplică singur `note9-large`;
   la nevoie și pe `note9`, `reset`) — telefonul utilizatorului e un Note 9 (1080×2220, butoane de navigare, font
   ~130%), nu Pixel 6; vezi `.codex/skills/solar-monitor-emulator/SKILL.md`. La final `profile reset`.
6. Dacă ai folosit emulatorul: `emulator-check.sh stop` și confirmă că
   `solar-monitor-emulator.service` e inactiv și nu mai există proces `qemu-system-x86`.
7. Raportează: versiunea finală, calea APK, SHA-256, id-ul livrării Telegram, hash-ul de
   commit și dacă e nevoie de rebuild pe server.

---

## 7. Ce NU face proiectul

Înainte de a propune o funcție, verifică lista asta și `COPILOT_CONTEXT.md` §13 — sunt
decizii deja luate, nu scăpări.

- **Nicio scriere către invertor.** Fără FC06/FC16, fără control de mod/prioritate,
  fără „încarcă bateria acum". Subiect închis; vezi `GEMINI.md`.
- **Nicio scriere către BMS.** Cititorul `bms/jk_bms_reader.py` trimite doar cererile de date
  `0x96`/`0x97`; fără comenzi JK de setări (praguri, balansare, MOSFET-uri, parolă).
- Fără cloud Growatt / ShineServer, fără MQTT, fără dongle ShineWiFi, fără Modbus TCP.
- Fără derulare verticală în UI-ul Retro.
- Fără valori „coapte" în bitmap — datele rămân dinamice, Compose nativ.
- Fără dezactivarea firewalld (fail2ban banează prin el).
- Fără intervale de istoric mai lungi de 31 de zile (retenția bucket-ului `history`).
