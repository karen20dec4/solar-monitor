# CASA: prize și senzori Tuya în Solar Monitor (design)

Data: 2026-11-05. Stare: propus, aștept aprobarea userului.
Mockup-uri aprobate (B, D, E): `android/build/emulator-artifacts/design/mockups-prize/` (gitignored; HTML + PNG).

## 1. De ce

Userul (Florin) comandă azi 5 prize Wi-Fi Tuya și citește 3 senzori de temperatură din aplicația SmartLife.
Vrea același lucru, mai simplu, în Solar Monitor, ca să nu mai depindă de SmartLife pentru ce folosește zilnic:
pornit/oprit, programări pe oră, countdown, oprire automată (Inching), plus temperaturi cu istoric. Cazuri reale:

- Boiler, Living sus, Bucătărie pornesc zilnic pe orele cu soare (ex. Boiler 10:30–16:00).
- Caloriferul de pe „Priza cam Georgi” pornește 07:00 și se oprește 07:30, doar iarna (vara programarea se
  dezactivează, nu se șterge).
- „Lumina Curte”: după fiecare aprindere se stinge singură după 30 min (Inching).
- Plecat o săptămână: pornește boilerul de pe date mobile cu ~6 h înainte să ajungă acasă. **Comanda din afara
  casei, pe date mobile, e obligatorie.**

Mai târziu (nu acum): pornire automată pe surplus solar. Designul de acum trebuie să-i lase loc (programatorul
stă pe server, lângă datele invertorului).

## 2. Obiective, non-obiective, anti-obiective

Obiective (v1):
1. Starea celor 5 prize (pornit/oprit, online), puterea la cele contorizate, comandă pornit/oprit din aplicație,
   din casă și din afara ei.
2. Pe fiecare priză: **Programări** zilnice (mai multe intervale, fiecare activ/inactiv), **Countdown** (o singură
   dată: pornește sau oprește după o durată), **Inching** (după fiecare pornire se stinge după durata setată).
3. Cei 3 senzori: temperatură, umiditate, prospețimea citirii; istoric 2h / 1d / 7d; sparkline 2h pe pagina CASA.
4. Prize și senzori în InfluxDB (live + history) și un rând nou în Grafana.

Non-obiective (v1): automatizare pe surplus solar; zile ale săptămânii la programări (toate sunt zilnice);
scene; notificări push pentru prize/senzori; adăugare/împerechere de dispozitive noi din aplicație (lista se
ia din contul SmartLife); temă Simple cu aceleași ecrane bogate (vezi §6.3).

Anti-obiective (ar fi eșec chiar dacă „merge”):
- Oricine găsește `vyra.go.ro:31443` poate porni/opri prize. Comanda fără autentificare e interzisă.
- SmartLife sau programările lui nu mai merg cât timp coexistăm (prizele rămân online în cloud; nu blocăm
  internetul prizelor).
- Orice scriere spre invertor sau BMS. Invariantul READ-ONLY rămâne neschimbat pentru ele; prizele Tuya sunt
  singurul lucru pe care sistemul îl comandă.
- Derulare verticală în tema Retro.
- Chei locale Tuya, token-uri cloud sau token-uri de acces în git, în log-uri sau pe Telegram.

## 3. Ce știm deja (recon 2026-11-05)

- Dispozitive (casa „Musetica” în SmartLife): prize **Priza cam Georgi, Bucătărie, Living sus, Boiler, Lumina
  Curte** (3 contorizate); senzori T+H **Temp Etaj, Temp afara, Temp BECI**.
- Scanare pasivă LAN: 5 dispozitive Tuya, toate prize după toate probabilitățile:
  `192.168.1.244`, `.104`, `.204` (același product key, protocol **3.3**) și `.185`, `.158` (alt model, protocol
  **3.4**). Presupunere: cele 3 identice sunt cele contorizate. Se confirmă la extragerea cheilor.
- Broadcast-urile UDP 6666/6667/7000 ajung la placa de rețea, dar firewalld (zona `public` pe `enp1s0`) le
  respinge. Comanda locală e TCP 6668 de la server spre priză și nu e blocată.
- Senzorii sunt Wi-Fi pe baterie (Temp BECI: conectat direct, IP-ul raportat e adresa publică a casei), deci
  **nu se pot citi local**; doar prin cloud-ul Tuya.
- `tuya-device-sharing-sdk` (oficial Tuya, folosit de integrarea Home Assistant `tuya/tuya-smart-life`): login
  cu QR din aplicația SmartLife, **fără cont de dezvoltator**; dă lista de dispozitive cu `local_key`, starea lor
  și push MQTT de la cloud. `tinytuya` 1.20 face comanda locală, protocoale 3.1–3.5.
- O priză Tuya acceptă de regulă o singură conexiune TCP locală; SmartLife merge în paralel prin cloud.
- API-ul `solar-api` nu are autentificare și e public prin Caddy (`handle_path /solar/*`). Aplicația face doar
  GET (`SolarRepository.open`), fără antet de autentificare.

## 4. Arhitectură (decizii)

### 4.1 Serviciu nou `casa` (container `solar-casa`)

Python, un singur proces, în `casa/`, adăugat în `docker-compose.yml` după tiparul `collector`
(`build`, `container_name: solar-casa`, `restart: unless-stopped`, `env_file: ./.env`, `depends_on` influxdb
healthy), pe rețeaua `br-solar`, **fără port publicat**, cu un volum pentru starea lui (`./casa/data` sau volum
numit, gitignored). Face trei lucruri:

1. **Prize, local** (`tinytuya`): o conexiune persistentă per priză, heartbeat ~10 s, status complet la ~5 s,
   reconectare cu backoff, protocol fixat per priză (din extragere). Adresele IP sunt fixe (vezi §8, rezervări
   DHCP). Scrie în InfluxDB. *(decizia agentului)* Fără comandă prin cloud ca rezervă în v1: o priză care refuză
   conexiunea locală apare OFFLINE cu eroarea ei; rezerva prin cloud se poate adăuga ulterior dacă apare nevoia.
2. **Senzori, cloud** (`tuya-device-sharing-sdk`): sesiune din login-ul QR, primește actualizările prin MQTT;
   dacă push-ul tace, citește starea prin SDK la cel mult 5 min. Scrie în InfluxDB.
3. **Programator + API HTTP intern** (port 8100): starea prizelor, comenzi, programări, countdown, inching.

`solar-api` rămâne read-only. Caddy primește o rută nouă `handle_path /solar/casa/* { reverse_proxy casa:8100 }`,
pusă **înaintea** lui `/solar/*`.

Atenție la `caddy/Caddyfile`: are deja modificări necommise care aparțin altui proiect (beci-cloud: log de acces
pentru fail2ban, rutele `/beci-cloud`), active în Caddy. Builder-ul adaugă doar ruta casa peste fișierul așa cum e
și **commite numai hunk-ul lui** (`git add -p`); restul îl lasă proprietarului. Fișierele `Caddyfile.bak-*` se ignoră.

### 4.2 Autentificare (obligatorie pe toate rutele `/solar/casa/*`)

Decizia userului (2026-11-05): **parolă la prima deschidere a paginii CASA**; toate telefoanele pe care e instalată
aplicația primesc acces complet după parola corectă. Azi: Note 9 (userul), S20 FE (va trece la user), S26
(telefonul nou al soției, Georgiana). Parola există pentru cazul în care APK-ul ajunge și în altă parte.

- **Parola** se setează pe server, interactiv, fără să apară în linia de comandă sau în log-uri:
  `docker exec -it solar-casa python -m casa set-password`. Serverul păstrează doar hash-ul ei (scrypt/argon2, cu
  sare) în volumul serviciului. Userul o tastează singur (ex. `! docker exec -it ...` în sesiunea agentului).
  Schimbarea parolei nu invalidează telefoanele deja înregistrate.
- **Înregistrarea telefonului:** la prima deschidere a SISTEM → CASA, fără token salvat, pagina arată o placă
  „ACCES CASA” cu câmp de parolă (ascunsă) și buton. `POST /solar/casa/register` `{password, device}` → token lung
  (≥ 32 octeți aleatori), salvat în aplicație (EncryptedSharedPreferences sau echivalent). `device` = modelul din
  `Build.MODEL` (+ un nume afișat, ex. „SM-N960U”), ca să se vadă în listă.
- Toate celelalte rute cer `Authorization: Bearer <token>`. Serverul păstrează doar hash-ul token-ului, cu
  modelul telefonului și data înregistrării / ultimei folosiri. `python -m casa phones` listează, `python -m casa
  revoke <id>` revocă un telefon. Un 401 pe un token salvat (revocat) readuce în aplicație placa de parolă.
- **Limită parolă:** max. 5 încercări greșite pe 15 min per IP și max. 20 pe oră în total; după aceea
  `/register` răspunde 429 până expiră fereastra. Comenzi max. ~1/s per token.
- Răspunsurile 401/429 apar în log-ul de acces Caddy, deci fail2ban le poate vedea; un filtru fail2ban nou
  pentru `/solar/casa/register` e la latitudinea builder-ului.
- **Parola și token-urile nu se trimit niciodată pe Telegram** și nu apar în git sau în log-uri.

### 4.3 Extragerea cheilor și lista de dispozitive

- `docker exec -it solar-casa python -m casa login`: cere „User Code” din SmartLife (Eu → Setări → Cont și
  securitate → User Code), afișează codul QR în terminal, userul îl scanează din SmartLife și confirmă.
- După login: lista de dispozitive (id, nume, categorie, `local_key`, contorizare) se salvează în volumul
  serviciului (`devices.json`, permisiuni 600). IP-ul și protocolul prizelor se completează din scanarea pasivă de
  mai sus sau dintr-un fișier `casa/devices.local.json` editat de mână; builder-ul alege, dar fără să deschidă
  firewall-ul.
- Prizele se potrivesc cu IP-urile după **device id** (= `gwId` din broadcast), nu după ghicit; nu e nevoie de
  test prin comutare fizică.
- *(decizia agentului)* Dacă login-ul QR eșuează sau nu dă `local_key`/senzorii, builder-ul **se oprește și
  întreabă userul**; contul de dezvoltator Tuya (iot.tuya.com) e doar o alternativă pe care o alege userul.
- *(decizia agentului)* Sesiunea cloud expirată sau căzută afectează doar senzorii (prizele merg local cu cheile
  salvate). Aplicația arată la senzori „CLOUD DECONECTAT” în loc de valori vechi, iar serverul trimite un push ntfy
  (topicul existent) după 30 min fără sesiune; refacerea e din nou `python -m casa login`.
- Lista se poate reîmprospăta cu `python -m casa sync` (după ce userul adaugă/redenumește ceva în SmartLife).
  Numele afișate sunt cele din SmartLife, scrise cu majuscule fără diacritice în Retro; orele din nume
  („Boiler 10.30 - 16.00”) se scot din numele afișat doar dacă e simplu; altfel userul le redenumește în SmartLife.

### 4.4 Semantica funcțiilor pe priză

Timp: Europe/Bucharest, cu trecerea la ora de vară/iarnă.

- **Programare** = interval zilnic `start → stop` (poate trece peste miezul nopții, ex. 22:00 → 06:00), flag
  `activ`. La `start` priza se pornește, la `stop` se oprește. **Doar pe fronturi**: dacă userul o oprește manual
  la mijlocul intervalului, rămâne oprită până la următorul front (ca în SmartLife). Mai multe intervale pe aceeași
  priză se pot suprapune; fiecare acționează doar la fronturile lui.
- **Countdown** = o singură acțiune programată per priză: `pornește` sau `oprește` la `acum + durată`
  (butoane 30M / 1H / 2H / 6H / ALTA DURATA, max. 24 h). Un countdown nou îl înlocuiește pe cel vechi;
  se poate anula. Supraviețuiește repornirii serviciului (persistat cu ora țintă absolută).
- **Inching** = per priză: `activ` + durată (pas 5 min, 1 min–12 h). La orice tranziție oprit → pornit
  (din aplicația noastră, din SmartLife, din programare sau de la butonul fizic), serverul scrie pe priză
  countdown-ul ei intern (DP de countdown, de regulă dp 9, în secunde), ca priza să se stingă singură chiar dacă
  serverul cade. Dacă priza nu are DP de countdown, serverul o oprește el la expirare. Countdown-ul „STINGE IN
  23 MIN” afișat vine din DP-ul prizei sau din timerul serverului.
- Interacțiuni: o comandă manuală pornit/oprit anulează countdown-ul în curs al prizei. Inching se aplică și
  când pornirea vine dintr-o programare.
- **Termenul de oprire al prizei** *(decizia agentului)*: cât timp o priză e pornită, serverul calculează cel mai
  apropiat termen de oprire dintre: sfârșitul intervalului de programare care a pornit-o, inching, countdown-ul
  „oprește”. **Cel mai apropiat câștigă**, iar exact acela se scrie în countdown-ul intern al prizei (DP), ca
  priza să se stingă singură și dacă serverul cade. Aplicația le arată pe toate (ex. „PROGRAM PANA 16:00 ·
  INCHING 30 MIN”) și pe cel activ ca „OPRIRE IN …”. La orice schimbare (countdown nou/anulat, interval editat,
  inching modificat) termenul se recalculează și se rescrie pe priză. Un countdown „pornește” e doar pe server.
- **Fronturi ratate** *(decizia agentului)*: serverul ține minte, per interval, ultimul front executat.
  La pornire și la reconectarea unei prize:
  - un **„stop” ratat se execută întotdeauna**, oricât de vechi, dacă priza e încă pornită de acel interval
    (siguranță: calorifer, boiler, lumină);
  - un **„start” ratat se recuperează doar dacă suntem încă în interval** (ex. server căzut 10:20–11:00 → boilerul
    pornește la 11:00 și merge până la 16:00). Dacă startul a fost executat și userul a oprit manual, nu se
    repornește (frontul e deja consumat).
  Priza offline la ora frontului: reîncearcă la 30 s până se reconectează, cel mult până la sfârșitul intervalului
  pentru „start”; „stop”-ul se reîncearcă fără limită. Eroarea se vede în starea prizei în aplicație.
- Programările, countdown-urile și inching-ul stau într-un fișier de stare în volum (JSON sau SQLite), scris atomic.
- După ce merg, userul șterge programările echivalente din SmartLife, ca să nu se bată. Asta e un pas manual al
  userului, de amintit în raportul final.

### 4.5 API `casa` (prin Caddy la `/solar/casa/*`)

Forma exactă (nume de câmpuri, coduri de eroare) e la latitudinea builder-ului, cu aceste capabilități:

- `POST /register` (fără token, cu parola) → token.
- `GET /plugs` → listă: id, nume, online, pornit, contorizată, putere W (+ V, A dacă există), energie azi kWh
  (integrare din InfluxDB pentru contorizate), programări, countdown curent (acțiune + secunde rămase), inching
  (activ, durată, secunde rămase dacă e pornită), ultima eroare de comandă.
- `POST /plugs/{id}/switch` `{on: bool}`; `PUT/DELETE` pe countdown; CRUD pe intervale de programare
  (inclusiv activ/inactiv); `PUT` inching.
- `GET /sensors` → per senzor: nume, temperatură, umiditate, baterie (dacă există), ora ultimei citiri, plus
  sparkline 2h (puncte reduse, ex. 24) ca pagina CASA să facă un singur request.
- `GET /sensors/{id}/history?range=2h|1d|7d` → temperatură și umiditate, min/max cu ora lor. 2h și 1d din `live`,
  7d din `history` (ca la `/history` din solar-api).
- Fiecare request de la aplicație poartă deja `User-Agent: SolarMonitor/...`; nu e nevoie de mai mult.

### 4.6 InfluxDB

- Measurement `casa_plug`, tag-uri `device_id`, `name`; câmpuri float: `on` (0/1), `online` (0/1), `power`,
  `voltage`, `current` (doar la contorizate). În `live` la fiecare citire (~5 s), în `history` media pe 60 s
  (`on`/`online`: minimul sau ultima valoare, la alegerea builder-ului, consecvent cu `bms`).
- Measurement `casa_sensor`, tag-uri `device_id`, `name`; câmpuri float `temperature` (°C), `humidity` (%),
  `battery` (dacă există). Scris la fiecare actualizare primită (la senzori pe baterie: rar, minute); în `history`
  ultima valoare pe 60 s doar când există date noi.
- Nume de câmpuri în engleză, ca în restul proiectului.

## 5. Grafana

Rând nou la finalul `solar-main` (după rândul 📱, de la `y=99`, id-uri de la 142): „🏠 Casa: prize si senzori”.
Panouri: putere pe priză (time series, W), stare pornit/oprit (state timeline, culori `palette-classic` — vezi
gotcha-ul din CLAUDE.md), temperatură pe senzor, umiditate pe senzor, ultima valoare a fiecărui senzor (stat).
Bump `version` în JSON.

## 6. Aplicația Android

### 6.1 Retro: SISTEM | CASA (mockup B)

- Tabul SISTEM primește un comutator cu două poziții **SISTEM | CASA** în placa de sus. Poziția SISTEM arată
  pagina de azi neschimbată. Poziția CASA arată pagina nouă. Alegerea se ține minte (`rememberSaveable`, ca
  `retroTabName`). Swipe-ul orizontal rămâne pentru taburi.
- Placa de sus a SISTEM are „SISTEM” pictat în imagine. Pe pagina CASA nu trebuie să scrie „SISTEM”: fie o placă
  goală obținută prin patch ImageMagick din `retro_system_top_artwork.webp` (precedent:
  `scripts/patch-retro-energy-*.py`, `scripts/make-retro-blank-dial.py`), arătată userului înainte, fie titlul și
  comutatorul ca plăci native opace (precedent ENERGIE v3.28). Builder-ul alege; comutatorul e oricum Compose.
- Pagina CASA, fără derulare, potrivită pe profilul `note9-large` (1080×2220, 420 dpi, font 130%):
  card PRIZE cu antet „2 PORNITE · 1650 W” și 5 rânduri (LED, nume, insigne programare / countdown / inching,
  VFD cu W sau „---” la necontorizate, comutator alamă); dedesubt SENZORI: 3 plăci cu temperatură, umiditate,
  sparkline 2h și prospețimea. Fără textul explicativ de jos din mockup.
- Atingere pe comutatorul unei prize = pornit/oprit imediat (optimist, apoi confirmat de server; revine și arată
  eroarea dacă serverul refuză). Atingere pe restul rândului = ecranul de detaliu D.
- Atingere pe un senzor = ecranul de detaliu E.
- Priză offline: LED stins, rândul estompat, comutatorul dezactivat, text „OFFLINE”.
- Fără token (telefon neînregistrat): pagina CASA arată doar placa „ACCES CASA” cu parola (§4.2). Prizele și
  senzorii sunt toate în spatele token-ului.

### 6.2 Ecrane de detaliu (mockup D și E)

- D (priză): nume, X de închidere, comutator mare, VFD cu W, „AZI x.x kWh” (doar contorizate), secțiunile
  COUNTDOWN, PROGRAMARI (listă cu activ/inactiv per interval, „+ ADAUGA INTERVAL” cu selector de oră nativ,
  ștergere interval), INCHING / OPRIRE AUTOMATA (comutator + durată cu −/+). La o priză fără contorizare, zona de
  putere/energie se ascunde. Dacă intervalele nu mai încap fără derulare, lista din PROGRAMARI poate derula în
  interiorul cardului ei (pagina în sine nu derulează); max. 6 intervale per priză.
- E (senzor): temperatură, umiditate, „actualizat acum N min”, baterie dacă există, butoane 2H/1D/7D, grafic
  temperatură (portocaliu) + umiditate (albastru, axă dreapta), MIN/MAX cu ora.
- Ambele sunt panouri peste tab (bara de taburi rămâne vizibilă), închise cu X sau cu butonul Back.
- Textele noi din aceste ecrane sunt native Compose; stilul (plăci alamă, VFD, comutatoare) refolosește
  componentele Retro existente (`RetroSystemLiveDisplay`, plăci `retro_dashboard_dial_blank.webp`, etc.).

### 6.3 Tema Simple

SISTEM din Simple derulează deja. Primește un card „Casa” cu cele 5 prize (nume, W, comutator) și cei 3 senzori
(temperatură, umiditate). Atingerea deschide aceleași ecrane D/E (funcțional identice; stilul poate fi cel Retro
sau simplificat, la alegerea builder-ului).

### 6.4 Rețea și polling

- `SolarRepository` primește GET/POST/PUT/DELETE cu corp JSON și antetul `Authorization` pentru rutele casa.
- Polling doar cât e vizibilă pagina CASA sau un ecran de detaliu, prin `WhileVisibleEffect` (regulă proiect):
  `/plugs` la 3 s, `/sensors` la 60 s. Nimic nou în `SolarAlarmService`.
- 20 s fără răspuns bun → valorile devin liniuțe („ASTEPT DATE”), ca la restul aplicației.
- SETARI: rândul „ACCES CASA” arată starea (înregistrat / neînregistrat) și permite „uită acest telefon”;
  înregistrarea se face din pagina CASA.
- Release: v3.40, procedura din `docs/DEZVOLTARE.md` §4–5; `emulator-check.sh layout-check` pentru pagina CASA
  și ecranele D/E pe `note9-large`.

## 7. Teste și verificare

- `casa/`: unittest fără rețea (ca `bms/`, `collector/`): calcul fronturi programări (inclusiv peste miezul nopții,
  schimbare DST, „stop” ratat executat oricât de vechi, „start” ratat recuperat doar în interval), termenul de
  oprire (cel mai apropiat câștigă, rescris pe priză), countdown persistat, inching declanșat la tranziție, autentificare
  (parolă greșită, rate-limit, token greșit/revocat), validare intrări.
- API `solar-api`: testele existente trec neschimbate (sau cu teste noi dacă se atinge ceva acolo).
- Android: teste unitare Kotlin pentru parsarea răspunsurilor casa și formatarea duratelor; emulator `verify` și
  `layout-check` pe profilul userului; capturi PNG inspectate.
- Verificare reală: o priză pornită/oprită din aplicație, de pe date mobile (telefonul userului) sau, minim,
  prin `https://vyra.go.ro:31443/solar/casa/...` cu token; un countdown de 1–2 min executat; inching scurt testat
  pe Lumina Curte cu acordul userului; o programare de test la +2 min; senzorii în Grafana.

## 8. Pași care cer userul

1. Login QR în SmartLife (o dată, câteva minute, în terminal cu agentul).
2. Rezervări DHCP în router pentru cele 5 prize (IP-urile de mai sus), ca să nu se schimbe. Alternativa,
   deschiderea UDP 6666/6667/7000 în firewalld pentru descoperire automată, cere aprobarea explicită a userului.
3. Setarea parolei pe server (o dată) și introducerea ei pe fiecare telefon (Note 9, S20 FE, S26).
4. Ștergerea programărilor din SmartLife după ce cele noi merg.

## 9. Documentație

`CLAUDE.md` și `GEMINI.md` (sincron): serviciu și container nou, port intern 8100, ruta Caddy, comenzile `casa`
(login/sync/set-password/phones/revoke), excepția clară la READ-ONLY (prize Tuya da; invertor și BMS nu), unde stau
cheile. `README.md`, `docs/DEZVOLTARE.md` (harta verticală: rând nou pentru `casa`), `COPILOT_CONTEXT.md` §13.78,
`.env.example` (variabilele noi, fără valori secrete).

## 10. Lăsat la latitudinea builder-ului

Structura internă a pachetului `casa/` și framework-ul HTTP (Flask + gunicorn cu 1 worker, ca `solar-api`, e
alegerea implicită); formatul fișierului de stare; numele exacte ale rutelor/câmpurilor; cum se mapează DP-urile
per model (citite din lista de la login + status real); intervalele exacte de heartbeat/poll în limitele de mai
sus; varianta de placă de sus pentru CASA (§6.1); detaliile vizuale din D/E în limita mockup-urilor.
