# CASA: programări pe evenimente în loc de intervale (design)

Data: 2026-11-07. Stare: propus, aștept aprobarea userului.
Mockup aprobat: **A, listă de evenimente** (+ ecranul de adăugare), în
`android/build/emulator-artifacts/mock-evenimente/` (gitignored; `a.html`/`a.png`, `editor.html`/`editor.png`; B a fost respins).
Designul de bază al CASA: `2026-11-05-casa-prize-senzori-design.md` (§4.4 semantica de azi).

## 1. De ce

Azi o programare e un interval zilnic `start → stop`: pornește la început, oprește la sfârșit. Userul (Florin) lucrează
altfel: când vede soare pornește priza manual (butonul prizei sau aplicația) și vrea ca la 17:00, când nu mai e soare,
să se oprească singură în fiecare zi. Cu intervale nu se poate pune doar o oprire. Vrea **evenimente**: la o oră aleasă,
PORNEȘTE sau OPREȘTE, independent unul de altul. Exemple reale (cuvintele lui):

- doar „OPREȘTE 17:00 zilnic” pe o priză pornită de mână la soare;
- „PORNEȘTE 11:00” + „OPREȘTE 16:00”;
- doar „PORNEȘTE”, fără să se mai închidă vreodată.

Zile: implicit **zilnic**; trebuie să poată alege și anumite zile ale săptămânii pentru un eveniment, dar ~95% din
evenimente vor fi zilnice (user, 2026-11-07).

Context: pe 2026-11-07 userul a comandat 4 prize Tasmota (Eightree ET28) care vor înlocui prizele Tuya (Tuya rămân în
sertar, de rezervă). Ele nu sunt încă aici; acest design e pentru driverul Tuya de azi, dar modelul de evenimente trebuie
să se poată mapa mai târziu pe timerele proprii Tasmota (oră + zile + acțiune).

## 2. Obiective, non-obiective, anti-obiective

Obiective:
1. Pe fiecare priză, o listă de evenimente; fiecare: oră `HH:MM`, acțiune PORNEȘTE/OPREȘTE, zile (zilnic sau o
   submulțime L–D), activ/inactiv. Adăugare, editare, ștergere, activare/dezactivare din aplicație (Retro și Simple).
2. Un eveniment acționează **doar la ora lui**. Între evenimente priza ascultă de buton și de aplicație.
3. Oprirea programată funcționează și când priza a fost pornită de la butonul fizic și și când serverul e căzut
   (prin DP-ul de countdown al prizei, ca azi).
4. Intervalele existente se convertesc automat, fără pierderi.

Non-obiective: evenimente legate de răsărit/apus sau de surplus solar; mutarea evenimentelor în firmware-ul Tasmota
(vine odată cu driverul Tasmota); schimbări la Countdown și Inching (rămân exact cum sunt).

Anti-obiective (eșec chiar dacă „merge”):
- O priză oprită de user după ora unui PORNEȘTE este repornită de acel eveniment (comanda manuală mai nouă câștigă
  întotdeauna).
- Un OPREȘTE ratat (server sau priză offline la ora lui) nu se mai execută și priza rămâne pornită (ex. boilerul).
- Aplicația ≤ 3.43 se blochează sau crapă după actualizarea serverului.

## 3. Modelul de date (server, `state.json`)

Pe fiecare priză, `events` înlocuiește `schedules`:

```json
{"id": "a1b2", "time": "17:00", "action": "off", "days": null, "enabled": true}
```

- `action`: `"on"` | `"off"`.
- `days`: `null` = zilnic; altfel listă sortată, fără duplicate, nevidă, de zile ISO `1`(luni)..`7`(duminică).
  O listă cu toate 7 zilele se salvează ca `null`.
- Limită: **12 evenimente pe priză** (azi 6 intervale = 12 fronturi). Decizia mea.
- Două evenimente **active** la aceeași oră cu zile care se suprapun sunt refuzate la salvare (400, mesaj clar), oricare
  ar fi acțiunea: altfel ordinea ar fi arbitrară. Decizia mea.
- Orele sunt în Europe/Bucharest. DST ca azi: ora inexistentă primăvara (03:00–04:00) cade la 04:00; ora dublă toamna
  se execută o singură dată, la prima apariție.
- Zilele se judecă după data locală la care cade evenimentul.

Starea de execuție pe priză (înlocuiește `last_done`, `started_by`, `release_off_at`, `cutoff`, `last_start_at`):
ce trebuie ca să știi ce eveniment s-a executat deja (ex. `last_event_at` = instantul ultimului eveniment consumat) și
`last_manual_at` (există deja). Forma exactă e la builder.

## 4. Semantica

### 4.1 La ora evenimentului
La ora unui eveniment activ, pe o zi potrivită, serverul trimite comanda (PORNEȘTE/OPREȘTE). Dacă priza e deja în
starea cerută, evenimentul e consumat fără comandă. Inching se aplică la o pornire făcută de un eveniment, ca la orice
pornire (comportamentul de azi).

### 4.2 Eveniment ratat (server oprit, priză offline, comandă eșuată)
Se ia în calcul doar **cel mai recent eveniment scadent** al prizei (cele mai vechi sunt depășite de el), în fereastra de
lookback de azi (`LOOKBACK` = 2 zile, neschimbat):
- dacă există o comandă manuală mai nouă decât el → consumat, nu se execută;
- **OPREȘTE**: se execută oricât de târziu (în lookback de 2 zile), dacă priza nu e sigur oprită; se reîncearcă fără limită cât
  timp e tot cel mai recent eveniment;
- **PORNEȘTE**: se execută doar cât timp e încă cel mai recent eveniment scadent (adică până la următorul eveniment
  activ al prizei, de orice tip). Dacă priza are un singur eveniment, PORNEȘTE, fereastra e până la următoarea lui
  apariție. Decizia mea; continuă regula de azi „start recuperat doar în fereastra lui”.

### 4.3 Oprire garantată în priză (DP countdown)
Cât timp priza e pornită (oricum ar fi pornit: eveniment, aplicație, buton fizic), serverul scrie în DP-ul de countdown al
prizei cel mai apropiat termen de oprire dintre: **următorul eveniment OPREȘTE activ** (pe o zi potrivită), inching,
countdown „oprește”. Plafonat la 24 h (`DP_MAX_S`, ca azi). Așa „OPREȘTE 17:00” oprește priza și dacă serverul e căzut.
Pornirea de la buton e văzută la următoarea citire (≤ `CASA_POLL_S`) și primește termenul atunci.

**Termen la peste 24 h** (posibil acum, cu zile alese: ex. OPREȘTE L–V 17:00, priza pornită vineri 18:00 → luni 17:00):
în priză **nu se scrie nimic** cât termenul e la peste 24 h (un countdown plafonat la 24 h ar opri-o prea devreme, sâmbătă);
se scrie abia când termenul intră sub 24 h. Logica de azi din `_sync_deadline` („plafonat, scris o dată, nerescris”,
`cd_capped`) se înlocuiește cu asta. Dacă în priză era deja un termen scris și cel nou e la peste 24 h, se scrie 0.
Protecția la server căzut lipsește doar pentru termenele aflate la peste 24 h. Test dedicat. Decizia mea.

### 4.2b Butonul fizic e comandă manuală
O schimbare de stare văzută la citire (pornit↔oprit) pe care **n-a cauzat-o serverul** (nicio comandă trimisă de el în
ultimele câteva secunde pentru starea respectivă) contează ca o comandă manuală: `last_manual_at` = momentul observării.
Doar cât priza e online (o reconectare nu e apăsare de buton: prima citire după offline nu contează ca manual). Exemplu:
PORNEȘTE 11:00 ratat (priză offline), userul o oprește de la buton la 11:10, se reconectează la 11:30 → nu o repornește.
Ca la comenzile din aplicație, anulează un countdown în curs. Răspunde direct anti-obiectivului 1 (userul folosește des
butonul).

### 4.4 Editare, ștergere, dezactivare
Se aplică din momentul salvării; nu se execută nimic retroactiv (un eveniment mutat la o oră deja trecută azi nu
acționează azi). Termenul din priză se recalculează: ștergerea lui „OPREȘTE 17:00” cât priza e pornită lasă priza
pornită (nu mai există conceptul de azi „rămâne pornită până la vechiul stop”: evenimentele sunt independente).
Comanda manuală anulează countdown-ul în curs (ca azi), dar nu atinge evenimentele.

## 5. API `casa`

- `GET /plugs` și detaliul unei prize: câmpul `events` (lista, ordonată după oră, apoi zile) și `next_event`
  (`{id, time, action, at}` cu `at` epoch, sau `null`) pentru rândul „URMĂTORUL” din aplicație.
- `POST /plugs/<id>/events` `{time, action, days, enabled}` → priza actualizată; `PUT /plugs/<id>/events/<eid>` (câmpuri
  parțiale); `DELETE /plugs/<id>/events/<eid>`. Validare pe server (format oră, acțiune, zile, limită, conflict).
- Compatibilitate: rutele `/schedules` dispar; răspunsul păstrează cheia `schedules: []`, ca aplicația ≤ 3.43 să nu
  crape (secțiunea ei de programări apare goală). Builder-ul verifică parserul vechi (`CasaModel.kt`) și ce afișează
  aplicația veche cu listă goală.

## 6. Migrare

La încărcarea stării, o singură dată: fiecare interval devine două evenimente cu aceleași `enabled`, `days: null`:
`start` → PORNEȘTE, `stop` → OPREȘTE. Starea de execuție veche se transformă astfel încât **nimic să nu se execute
retroactiv** la prima pornire după migrare. Singurul interval real azi: „Priza cam Georgi” 07:00–07:30 (caloriferul, activ
iarna). `state.demo.json` se migrează la fel. Copie a `state.json` înainte de prima pornire cu codul nou.

## 7. Aplicația Android (v3.44)

### 7.1 Retro, detaliul prizei: secțiunea „PROGRAMĂRI · EVENIMENTE” (mockup A)
Înlocuiește secțiunea de intervale, în același loc și același stil de plăci:
- sus, rândul „URMĂTORUL: OPREȘTE 16:00 · ÎN 2H 14M” (din `next_event`); fără evenimente active: „NICIUN EVENIMENT”;
- rânduri: LED (activ), ora mare (chihlimbar), etichetă PORNEȘTE (verde) / OPREȘTE (roșu), zilele („ZILNIC” sau
  „SA · DU”, „L–V” pentru luni–vineri), comutator activ/inactiv, X ștergere (cu confirmare, ca azi la intervale);
  rândurile inactive estompate; tap pe rând → editor;
- jos, două butoane: „+ PORNIRE” și „+ OPRIRE”, care deschid editorul cu acțiunea deja aleasă; ascunse/dezactivate la 12.
- Secțiunea poate derula intern când sunt multe rânduri (ca azi peste 2 intervale). Pagina SISTEM/CASA rămâne fără
  derulare verticală.

### 7.2 Editorul (mockup „editor”)
CE FACE: PORNEȘTE / OPREȘTE. LA ORA: cifre mari, +/− pentru ore și pentru minute, pas 1 min, apăsare lungă = repetare
rapidă. ÎN CE ZILE: „ZILNIC” (implicit) / „ALEG ZILELE” + L MA MI J V S D. ANULEAZĂ / SALVEAZĂ. Erorile serverului
(ex. conflict) se arată în editor. Același editor pentru editare.

### 7.3 Tema Simple și ecuson-urile din liste
Tema Simple refolosește deja detaliul Retro (`SimpleCasaDetailDialog` → `RetroPlugDetail`), deci lista și editorul vin
gratis; nu se face un detaliu Simple separat. `plugBadges()` (rândurile Retro CASA și cardul Simple) nu mai arată
intervalele: arată **doar următorul eveniment**, ex. „OPREȘTE 17:00” (zi dacă nu e azi: „OPREȘTE LU 17:00”), apoi
ecusoanele de azi (countdown, AUTO, STINGE ÎN). Decizia mea.

### 7.4 Reguli existente
Comenzile trec prin `CasaFreshness.commandStarted/Finished`; vibrația de confirmare (`CasaHaptics`) la salvare; textele
statice noi sunt `Text(...)` Compose pe plăci (fără repictare de WebP).

## 8. Teste și verificare

- `casa`: teste unitare pentru: apariții pe zile și DST; regula „cel mai recent eveniment”; manual mai nou anulează;
  OPREȘTE ratat executat târziu, PORNEȘTE ratat doar până la următorul eveniment; termenul din DP (pornire de la buton,
  zile, plafon 24 h); conflict și validare; migrarea intervalelor (inclusiv fără execuție retroactivă); compatibilitatea
  `schedules: []`. Suita completă rulată de mai multe ori (vezi CLAUDE.md).
- Android: teste unitare pentru textul zilelor și „URMĂTORUL … ÎN …”; `emulator-check.sh verify` + `layout-check`,
  capturi ale detaliului cu 0, 3 și 12 evenimente și ale editorului, inspectate.
- Live, după instalare, pe o priză nefolositoare (nu boilerul): un PORNEȘTE și un OPREȘTE la câteva minute distanță;
  pornire de la buton și verificarea termenului scris în priză.

## 9. Documentație

`CLAUDE.md` și `GEMINI.md` (secțiunea CASA, „Semantics”, „App”), `COPILOT_CONTEXT.md` (jurnal), `docs/DEZVOLTARE.md` dacă
se schimbă harta fișierelor. Livrare APK pe Telegram după procedura din `docs/DEZVOLTARE.md`.

## 10. Lăsat la latitudinea builder-ului

Forma exactă a stării de execuție și a funcțiilor din `schedule.py`; numele intern al câmpurilor din `plug_view`
(în afară de `events`, `next_event`, `schedules`); împărțirea în fișiere Kotlin; ordinea rândurilor cu aceeași oră.

## Deciziile mele (de verificat de user)

- 12 evenimente pe priză; conflictul la aceeași oră cu zile suprapuse e refuzat.
- PORNEȘTE ratat se recuperează doar până la următorul eveniment al prizei; OPREȘTE ratat oricât de târziu (24 h).
- Ștergerea/dezactivarea unui OPREȘTE cât priza e pornită nu o mai oprește la vechea oră.
- Editorul: pas de 1 minut (nu 5, cum scria în schiță), ca să se poată pune și 16:25.
- Aplicația veche (≤ 3.43) vede secțiunea de programări goală până la actualizare.
- Termen de oprire la peste 24 h: nu se scrie în priză până nu intră sub 24 h (fără protecție la server căzut până atunci).
- Butonul fizic al prizei contează ca o comandă manuală (vezi §4.2b).
- În listele CASA apare doar următorul eveniment.

## 11. Adăugat 2026-11-07: ordinea prizelor (aprobat de user)

Pe SISTEM → CASA, userul vrea să schimbe ordinea prizelor ca la iconițele Android:
- apăsare lungă pe un rând de priză → vibrație scurtă → **modul de mutare**: toate rândurile tremură ușor;
- tragi rândul în sus/jos, celelalte se dau la o parte; la ridicarea degetului ordinea se salvează;
- ieșire: butonul „GATA” sau atingere în afara listei; în modul de mutare atingerea scurtă nu deschide priza și
  comutatoarele nu comandă nimic;
- cardul CASA din tema Simple folosește aceeași ordine; o priză nouă apare la sfârșit.
- **Ordinea e aceeași pe toate telefoanele** (user): ține de server, în `state.json` (`plug_order`: listă de id-uri),
  nu în telefon. `GET /plugs` întoarce prizele în ordinea asta (id-urile necunoscute sau lipsă: la sfârșit, în ordinea
  de azi). `PUT /plug-order` `{"order": [id, ...]}` (id-uri cunoscute, fără duplicate; cele lipsă se pun la sfârșit) →
  lista de prize. Aceeași autentificare și același ritm de comenzi ca celelalte rute non-GET.
