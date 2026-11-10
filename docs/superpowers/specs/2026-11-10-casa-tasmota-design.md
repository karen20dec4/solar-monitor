# CASA: prize Tasmota alături de Tuya + detaliul prizei compactat (v3.45)

Data: 2026-11-10. Stare: aprobat de user în chat („varianta B, restul e ok, mergi mai departe”).
Spec-uri anterioare: `2026-11-05-casa-prize-senzori-design.md`, `2026-11-07-casa-evenimente-design.md`.

## 1. De ce

Userul a cumpărat 4 prize **Eightree ET28 16 A cu Tasmota** ca să scape de cloud-ul Tuya/SmartLife. Au venit
2026-11-10 și sunt deja montate fizic (sau gata de montat) în locul a patru prize Tuya. Serverul `solar-casa`
știe azi doar Tuya (`tinytuya`), deci cele patru apar OFFLINE în aplicație și nu-și execută evenimentele
(boilerul nu se mai oprește la 15:45, Priza cam Georgi nu pornește la 07:00 etc.). Asta e problema urgentă.

A doua parte, cerută în aceeași discuție: ecranul de detaliu al prizei (Retro, SISTEM → CASA → priză) arată
doar 2 programări fără derulare; userul vrea 5, un card de antet mai mic și eticheta TASMOTA / TUYA.

## 2. Ce există fizic (verificat pe prize, 2026-11-10)

| Tasmota | MAC | IP rezervat | Înlocuiește (id Tuya, rămâne id-ul prizei) |
|---|---|---|---|
| nr1 | DC:06:75:44:D3:4C | 192.168.1.186 | Boiler `bf7b3c62e47bfd15fczfov` |
| nr2 | DC:06:75:42:0F:84 | 192.168.1.234 | Living sus `bf5cc0f2db15b9eab2a0zy` |
| nr3 | DC:06:75:43:1E:A4 | 192.168.1.170 | Priza cam Georgi `bf3bac46d1a367e845thx2` |
| nr4 | DC:06:75:43:7C:84 | 192.168.1.188 | Lumina Curte `bf3897ad46cd66d32cww3j` |

Bucătărie (`bfc30380ff8a29c0d88wcz`, Tuya T34, fără contor) **rămâne Tuya**. Deci cele două tipuri merg
împreună, nu e un comutator global.

Toate: ESP32-C3 (ESP8685), Tasmota **16.0.0** (release-tasmota32, engleză), template `EIGHTREE`
(`GPIO [0,0,0,2720,2624,32,224,2656,0,…,320,7648]`, BL0937), calibrate pe același calorifer (≈ invertorul +3–5 %),
`PowerOnState 3`, fără MQTT, fără WebPassword (încă), HTTP API pe port 80. DeviceName/FriendlyName = numele prizei.
`PulseTime1`: 0 la nr1–nr3, **1900 (= 30 min) la nr4** (Lumina Curte, preluat de la inching-ul Tuya).
nr2 și nr3 citesc 0 V cu releul oprit; nr1 și nr4 citesc tensiunea rețelei și oprite. Copii ale configurației
dinainte în `/root/tasmota-backup/` (conțin parola Wi-Fi: nu intră în git).

Observat pe prize: `PulseTime1 <v>` scris cu releul PORNIT repornește imediat numărătoarea (`Remaining` arată
noua valoare); `Remaining` scade cu 1/s și pare să fie în aceeași codificare ca `Set` (v ≤ 111 → zecimi de
secundă, v ≥ 112 → v − 100 secunde; maxim 64900 = 18 h). Dovadă: nr4 cu `Set 1900` scris pornită a arătat
`Remaining 1899` imediat și `1765` după ~135 s. **De reconfirmat pe o priză reală la implementare.**

## 3. Ce trebuie să facă (deciziile userului)

1. Prizele Tasmota și Tuya merg împreună; o priză are un **tip** (driver): `tuya` sau `tasmota`.
2. Priza Tasmota **preia** prizei Tuya înlocuite: numele, evenimentele, inching-ul, locul în listă (ordinea) și
   istoricul din grafice. (Implementare: **același id de priză**, doar driverul și IP-ul diferă, vezi §4.1.)
3. Fără cloud, fără MQTT: serverul vorbește direct cu priza prin HTTP pe LAN, citire la `CASA_POLL_S` (5 s).
4. Toate programările (evenimente, countdown) le face **serverul**, nu timere/reguli în priză.
5. Butonul fizic al prizei Tasmota = comandă manuală, exact ca la Tuya (aceeași detecție din citiri).
6. Oprirea de siguranță: cât priza e pornită, cel mai apropiat termen de oprire se scrie în priză (Tasmota:
   `PulseTime1`), ca să se stingă și dacă serverul cade.
7. Lumina Curte: oprirea după 30 min rămâne **în priză** (merge și din buton cu serverul căzut), ca la Tuya.
   Schimbarea din aplicație se scrie în priză.
8. În aplicație: în lista prizelor **toate arată la fel**. În detaliul prizei scrie `TASMOTA` sau `TUYA` pe rândul
   cu `WIFI OK`.
9. Detaliul prizei, **varianta B** (machetă trimisă pe Telegram 2026-11-10, mesaj 47; sursa HTML în
   `android/build/emulator-artifacts/mock-tasmota/b.html`, `b.png`), vezi §6.
10. Întoarcerea la Tuya (prize Tuya în sertar, „în caz de avarie”) trebuie să rămână posibilă: suportul Tuya nu se
    șterge.
11. Implementarea cu agenți Sonnet (subagent-driven-development).

Non-goals: buton „adaugă priză” în aplicație; descoperirea automată a prizelor Tasmota; MQTT; actualizarea
firmware-ului Tasmota din server; ESPHome; mutarea senzorilor pe Zigbee; schimbări la tema Simple (în afară de
ce cere câmpul nou, nimic).

Anti-goals: scrieri spre invertor sau BMS (rămân READ-ONLY); parola prizelor în git/log/ntfy/Telegram;
o priză Tasmota căzută care întârzie comenzile altei prize; ca un termen de siguranță rămas în priză să o
oprească mai devreme decât trebuie când serverul merge.

## 4. Server (`casa/`)

### 4.1 Registrul de dispozitive

- `devices.local.json` primește pentru o priză câmpul opțional `"driver": "tasmota"` (implicit `"tuya"`) și
  `"ip"`. Intrarea rămâne cheiată pe **id-ul Tuya** din `devices.json`, deci starea (`state.json` →
  `plugs[id]`), ordinea (`plug_order`), tag-ul InfluxDB `device_id` și telefoanele nu se schimbă.
  Exemplu: `"bf7b3c62e47bfd15fczfov": {"driver": "tasmota", "ip": "192.168.1.186"}`.
- `_valid_local` acceptă `driver` ∈ {`tuya`, `tasmota`}; altă valoare → intrarea e ignorată cu avertisment
  (ca acum pentru intrări greșite).
- `DeviceInfo` primește `driver: str = "tuya"`. Pentru `tasmota`: `dps`/`scales` sunt înlocuite cu o hartă
  **sintetică fixă** (§4.2), `local_key`/`version` nu contează.
- O intrare locală cu `"driver": "tasmota"` care are `name` și `ip` e **suficientă singură**: dacă id-ul ei nu mai e
  în `devices.json` (prizele Tuya șterse din SmartLife, `casa-sync.sh` rulat după), priza rămâne (kind `plug`).
  Așa boilerul nu dispare dacă cineva curăță contul SmartLife. (Decizia mea.)
- `apply_scan` / `scan` (descoperirea Tuya) nu ating intrările cu `driver: tasmota` (nu le rescriu IP-ul cu
  IP-ul vechi al prizei Tuya dacă aceasta mai e cumva în rețea).
- Întoarcerea la Tuya = scoți `driver`/`ip` din intrare + `docker compose restart casa`.

### 4.2 `TasmotaDriver` (în `casa/casa/tasmota.py`, nou)

Implementează exact protocolul `PlugDriver` existent (`connect/status/set_dp/close`), ca să nu se schimbe
`PlugWorker` și aproape deloc `Controller`. Vorbește HTTP cu `http://<ip>/cm?cmnd=…` (stdlib `urllib`, timeout
scurt, ex. 3 s; fără dependențe noi). Harta sintetică de DP-uri (aceleași numere ca FALLBACK-ul Tuya, ca restul
codului să le trateze identic):

| cheie | DP | în `status()` | `set_dp` |
|---|---|---|---|
| switch | 1 | `Power` → bool | `Power On/Off` |
| countdown | 9 | secunde rămase din `PulseTime1` `Remaining` (0 = niciunul) | §4.3 |
| current | 18 | A × 1000 (mA, ca la Tuya; `scales.current = 0`) | – |
| power | 19 | W × 10 (`scales.power = 1`) | – |
| voltage | 20 | V × 10 (`scales.voltage = 1`) | – |
| inching | 44 | base64 (activ, secunde) ca DP-ul Tuya, din `Mem1` | §4.3 |

`status()` face câteva cereri HTTP (Power, Status 10 sau 8, PulseTime1, Mem1 — builderul alege combinația
minimă; ideal ≤ 3 cereri/citire). Orice eroare HTTP/JSON/timeout → `PlugError` (worker-ul deja face offline +
retragere 2→60 s). `connect()` doar verifică faptul că priza răspunde (nu există conexiune persistentă).

### 4.3 Countdown, inching și `PulseTime1`

Tasmota are un singur mecanism, `PulseTime1`: se aplică la fiecare pornire a releului și, scris cu releul pornit,
repornește numărătoarea. Tuya avea două DP-uri (countdown unic + inching la fiecare pornire). Regula:

- **Baza** (`base`) = inching-ul prizei: `Mem1` ține **durata** în secunde (păstrată și când e oprit, ca aplicația să
  nu piardă durata aleasă), `Mem2` ține `1`/`0` = activ/oprit. base = `Mem1` dacă `Mem2` = 1, altfel 0. `PulseTime1` cu releul **oprit** trebuie
  să fie `base` (codificat), ca butonul să aplice inching-ul și cu serverul căzut.
- `set_dp(44, base64)` → scrie `Mem1` = secunde, `Mem2` = activ și, dacă releul e oprit, `PulseTime1` = base. Dacă releul
  e pornit, nu atinge `PulseTime1` (controllerul rescrie oricum termenul cel mai apropiat prin DP 9).
- `set_dp(9, N)` cu N > 0 → `PulseTime1` = N secunde (codificat). Cu N = 0 → `PulseTime1` = base.
- **Auto-vindecare:** dacă la o citire releul e oprit și `PulseTime1 Set` ≠ base, driverul rescrie base (un termen
  de siguranță rămas de la ultima pornire nu are voie să oprească mai devreme următoarea pornire din buton).
- Dacă `Mem1`/`Mem2` lipsesc/sunt invalide (azi sunt `""` pe toate prizele): driverul **nu raportează** DP 44 în
  citire (controllerul păstrează valoarea din `state.json`, ca la prizele fără inching nativ); la punerea în
  funcțiune se inițializează din starea serverului (§8). Odată scrise, priza e sursa de adevăr (ca la Tuya v3.43).
- Dacă la implementare `Remaining` nu se poate decoda sigur, driverul raportează `countdown_s = None` (oprește
  bucla de resincronizare de 60 s) — acceptat.
- **Plafonul termenului scris în priză:** Tuya 24 h (`DP_MAX_S = 86400`), Tasmota 18 h (64800 s). Plafonul devine
  per dispozitiv (ex. `DeviceInfo.countdown_max_s` sau o constantă pe driver), folosit în `_sync_deadline` în locul
  lui `DP_MAX_S`. Regula rămâne: un termen mai departe decât plafonul **nu** se scrie (îl execută serverul).
- Restul semanticii evenimentelor (spec 2026-11-07) rămâne neschimbat: evenimente ratate, butonul = manual,
  ecoul comenzii 15 s, oprirea din `PulseTime` văzută ca „apăsare” (deja tratată pentru DP-ul Tuya).

### 4.4 Parola Tasmota

- O singură parolă pentru toate prizele Tasmota (`WebPassword`, utilizator `admin`), în
  `casa/data/tasmota.json` (`{"password": "..."}`, mode 600, gitignored ca restul lui `data/`). Fără fișier →
  fără parolă (merge cu prizele de acum, care n-au).
- Trimisă ca parametri `user`/`password` în cererea `/cm` (Tasmota HTTP API). **URL-ul conține parola**: nicio
  excepție, niciun log, nicio citire offline (`PlugReading.error`, care ajunge în `plug_view` și la telefon) nu are
  voie să conțină URL-ul sau parola. Mesajele de eroare se construiesc din tipul erorii + IP, nu din `str(exc)`
  brut de la `urllib`. Test unitar obligatoriu pentru asta.
- CLI nou: `python -m casa tasmota-password --stdin` (scrie fișierul; ca `set-password`, niciodată în argv).
  Setarea `WebPassword` pe prize o face omul/agentul la punerea în funcțiune (§8), nu serverul.

### 4.5 `GET /plugs` și restul

- Fiecare priză din `plug_view` primește `"driver": "tuya" | "tasmota"`.
- `web.py` alege driverul după `DeviceInfo.driver` (`TinyTuyaDriver` / `TasmotaDriver`); modul demo rămâne pe
  `DemoDriver`, dar o priză demo poate raporta `driver: "tasmota"` (pentru verificarea etichetei pe emulator; alegerea
  builderului).
- InfluxDB, Grafana, `tuya_arp.py`: neschimbate (intrările ARP statice ale vechilor IP-uri Tuya expiră singure în 24 h).
- Teste: driverul cu un server HTTP fals (stdlib `http.server` pe 127.0.0.1) sau cu transportul injectat; harta DP;
  codificarea `PulseTime`; auto-vindecarea; plafonul de 18 h în `_sync_deadline`; `devices.local.json` cu `driver`;
  `plug_view.driver`; nicio parolă în erori. Suita existentă (381 teste) trebuie să treacă neschimbată.

## 5. Ce rămâne neschimbat

Autentificarea telefoanelor, rutele existente, semantica evenimentelor/countdown/inching din spec-ul 2026-11-07,
Caddy, `docker-compose.yml` (nu e nevoie de variabile noi; dacă builderul adaugă una `CASA_*`, intră și acolo).

## 6. Aplicația Android (v3.45, versionCode 49), varianta B

Doar `RetroCasaDetail.kt` (detaliul prizei Retro) și modelul:

1. `CasaPlug.driver` (din `GET /plugs`, implicit `"tuya"` dacă lipsește, pentru server vechi).
2. **Antet:** subtitlul devine `PRIZA 3/5 · CONTORIZATA · WIFI OK · TASMOTA` (sau `· TUYA`), cu numele driverului
   evidențiat (galben-auriu ca în machetă). Offline: `PRIZA 3/5 · WIFI OFFLINE · TASMOTA`. Sub antet un **singur
   rând**: comutatorul (cu PORNIT/OPRIT sub el) | placa de wați **mai mică** (≈ 46 dp înălțime față de 58) |
   placa kWh `AZI` (cu „AZI” sub ea). Linia albastră „STINGE …” rămâne sub rând. Prizele fără contor: în locul
   celor două plăci, „PRIZA FARA CONTOR”.
3. **COUNTDOWN:** neschimbat.
4. **PROGRAMARI EVENIMENTE:** titlul cu **același font, mărime și culoare** pe ambele cuvinte (azi „EVENIMENTE”
   e mai mic și estompat). Rânduri compacte (~40 dp în loc de 54: LED mai mic, ora ~19 sp, eticheta mai îngustă,
   comutator ~48×24, X ~32 dp, zona de atingere a X și a comutatorului rămâne cât se poate de mare). **5 rânduri
   vizibile fără derulare** pe profilul `note9-large` (1080×2220, 420 dpi, font 130 %, trei butoane); de la al
   6-lea, derularea internă de acum. Butoanele „+ PORNIRE / + OPRIRE” rămân jos.
5. **INCHING:** un singur rând, fără linie de titlu separată: în stânga „INCHING” (același font/mărime ca titlul
   actual) cu „OPRIRE AUTOMATA” mic sub el, apoi comutatorul, `−`, afișajul `30 MIN`, `+`. Textul explicativ
   „Dupa fiecare pornire…” dispare (rămâne în descrierea de accesibilitate a comutatorului).
6. Pagina **nu derulează niciodată** (regula Retro); spațiul câștigat la antet și inching merge la PROGRAMARI.
   Textele noi sunt Compose (nimic nou pictat în WebP).
7. Lista prizelor (SISTEM → CASA) și tema Simple: neschimbate.

Verificare: `emulator-check.sh layout-check` + capturi ale detaliului cu 0, 5 și 12 evenimente, priză Tasmota și
Tuya, contorizată și necontorizată, pe serverul demo (`-PcasaBase`, vezi CLAUDE.md), inspectate vizual.
Testele unitare Android trec.

## 7. Documentație

CLAUDE.md + GEMINI.md (secțiunea CASA: drivere, `devices.local.json` cu `driver`, `tasmota.json`, CLI nou,
plafon 18 h, App v3.45), `docs/DEZVOLTARE.md` (câmpul `driver`), `COPILOT_CONTEXT.md` §13.81 (tabelul de mai sus,
calibrarea, observațiile de firmware).

## 8. Punerea în funcțiune (după merge, pe server)

1. Backup `casa/data/state.json` și `devices.local.json`.
2. Parola: generată, `tasmota-password --stdin`, apoi `WebPassword` setat pe cele 4 prize (prin HTTP, fără să apară
   în istoricul shell-ului / în argv vizibil; ex. din fișier).
3. `Mem1`/`Mem2` pe fiecare priză din `state.json` (durata în secunde / activ): azi Lumina Curte 1800/1,
   Boiler 1500/0, Living sus 1800/0, Priza cam Georgi 60/0 (sau ce arată starea în acel moment); `PulseTime1` = base.
   Plus `name` în intrarea locală (vezi §4.1).
4. Intrările `driver: tasmota` + IP în `devices.local.json` pentru cele 4; `docker compose up -d --build casa`.
5. Verificare: toate 5 online în `GET /plugs`, `driver` corect; comutare din server pe o priză Tasmota (nu boilerul
   primul); un termen scris în `PulseTime1` când priza e pornită și are OPRESTE în ≤ 18 h; inching-ul Lumina Curte
   din buton.
6. APK 3.45 pe Telegram (`scripts/send-android-release-telegram.sh`).

## 9. Decizii luate după verificarea builderului (ale mele, nu ale userului)

- **Prizele reale:** agenții de implementare **nu** ating prizele. Răspunsurile JSON reale (citite read-only de pe
  nr1 și nr4, 2026-11-10, SSID/BSSID înlocuite) sunt în `casa/tests/fixtures/tasmota/` (`*-power`, `*-status8/10/11`,
  `*-pulsetime1`, `*-mem1/2`, `*-status2`); comanda necunoscută întoarce `{"Command":"Unknown","Input":"BOGUS"}`,
  `Mem1` gol = `{"Mem1":""}`. Punerea în funcțiune (§8) o face sesiunea principală, după merge, cu verificări.
- **Evenimente ratate la trecere:** semantica rămâne cea din 2026-11-07 (nicio excepție). Starea la 2026-11-10 11:35 UTC:
  nicio pornire în așteptare; dacă boilerul ratează 15:45 până la trecere, OPRESTE-ul se execută la trecere — dorit.
- Lucrul se face direct în `/opt/solar-monitor` (ramura `feat/casa-tasmota`), fără worktree; machetele B sunt în
  `android/build/emulator-artifacts/mock-tasmota/`.
- Server și APK se livrează separat (serverul întâi). Tema Simple fără etichetă de driver. `caddy/Caddyfile` nu se
  stage-uiește.

## 10. Lăsat builderului

Combinația exactă de comenzi HTTP pe citire; timeout-urile; unde stă plafonul per driver; cum arată fișierul de
test HTTP fals; dimensiunile exacte în dp (cât să încapă 5 rânduri pe profilul Note 9, verificat pe emulator);
dacă demo-ul are o priză Tasmota.
