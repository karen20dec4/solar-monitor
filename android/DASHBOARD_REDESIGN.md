# Dashboard Android — teme Simple si Retro

> Din versiunea 2.01, dashboardul descris initial in sectiunile 1–8 este tema `Simple`. Aplicatia porneste
> implicit cu tema `Retro`, implementata separat in `RetroDashboard.kt`. Utilizatorul poate schimba tema
> din SETARI, iar `DashboardStyleStore` pastreaza alegerea local.

## Retro v4 — arhitectura hibrida actuala

Tema Retro reproduce referinta `retro-theme-v4.png` prin doua straturi care raman independente:

1. resursele Photoshop/WebP dau materialul fotografic: patina, oxidare, zgarieturi, rame, suruburi,
   miniaturile industriale si bara de navigare;
2. Compose deseneaza si actualizeaza toate informatiile functionale: valori live, unitati, acul cadranului,
   LED-urile animate, selectia tabului si zonele tactile.

Nicio valoare nu este lipita in bitmap. Astfel putem inlocui ulterior o placa Photoshop fara sa schimbam
API-ul sau logica, iar datele continua sa se actualizeze la fiecare raspuns de la `/solar/latest`.

Resursele principale sunt in `app/src/main/res/drawable-nodpi/`:

- `retro_dashboard_background_artwork.webp` si `retro_page_background_artwork.webp` — fundalul metalic
  finalizat folosit pe toate cele patru pagini;
- `retro_dashboard_live_artwork.webp` — placa ACUM/PANOURI, cu ferestre goale pentru valori dinamice;
- `retro_dashboard_flow_artwork.webp` — placa FLUX ENERGETIC cu miniaturi fotografice;
- `retro_dashboard_{label,dial}_{battery,inverter,temperature}.png` — cele trei etichete gravate si
  cadranele VFD din zona esentiala de sub FLUX;
- `retro_bottom_navigation_artwork.webp` — bara comuna cu cele patru instrumente de navigare.

Sursele Photoshop active sunt versionate in `android/build/emulator-artifacts/design/optimized/text-display/`.
Cele patru exporturi mari folosite anterior pentru fundal, ACUM, FLUX si NAV au fost eliminate intentionat
dupa import; resursele WebP finale raman versionate. Importul reproductibil al instrumentelor v5 se face cu:

```bash
scripts/audit-retro-ui-assets.sh
scripts/prepare-retro-ui-assets.sh
```

Primul script verifica dimensiunile, spatiul sRGB si alpha-ul fara sa modifice imaginile. Cu `--strict`,
acesta esueaza pana cand exporturile active respecta contractul. Al doilea script copiază exact cele sase
PNG-uri v5 in resursele Android. Daca setul vechi complet este reintrodus, scriptul poate regenera si
WebP-urile; daca lipseste complet, pastreaza resursele finale existente.

### Contract pentru exporturile Photoshop finale

Contractul activ foloseste exact aceste exporturi PNG-32 sRGB cu alpha real:

- `text-display/`: cadrane 600×190, 600×190 si 477×190;
- `text-display/`: etichete 200×55, 220×55 si 271×55.

Exporta cu `Transparency` activ si `Matte: None` (alpha ne-premultiplicat). Nu include cifre dinamice,
acul cadranului, LED-uri animate sau starea selectata; acestea sunt desenate de Compose. Dupa inlocuire,
ruleaza `scripts/audit-retro-ui-assets.sh --strict`, apoi `scripts/prepare-retro-ui-assets.sh` si verificarea
in emulator.

### Structura fixa, fara scroll

Tema Retro este un panou fizic care ocupa intregul spatiu al aplicatiei. `RetroDashboard` foloseste un
`Column(fillMaxSize())`: continutul paginii primeste `weight(1f)`, iar `RetroBottomNavigation` ramane ultimul
copil, fix la baza. Niciuna dintre cele patru pagini Retro nu foloseste `verticalScroll` sau `LazyColumn`.
Pe TABLOU, placile ACUM si FLUX sunt ancorate sus, cu umbrele lor usor suprapuse, nu distribuite cu
`SpaceEvenly`. Pe emulatorul de referinta 1080×2400, ambele au 95% din latimea anterioara si isi pastreaza
raportul natural de aspect: ACUM este deplasat cu 40 px in jos, iar FLUX cu 140 px fata de pozitia sa
anterioara. Pozitiile sunt calculate independent intr-un `BoxWithConstraints`, astfel incat shrink-ul
cardului de sus sa nu traga automat FLUX inapoi in sus. Zona metalica ramasa sub FLUX este rezervata
intentionat pentru un viitor grup compact de informatii esentiale; nu este umpluta automat cu decor sau
carduri provizorii.

- TABLOU — consum live, PV si flux energetic;
- ENERGIE — patru subiecte (CASA, PANOURI, BATERIE, RETEA), doua moduri, trei intervale si graficul ales;
- SISTEM — stare invertor, baterie, retea, temperatura si pierderi;
- SETARI — tema, alarma, cooldown, vibratie, sunet si informatii aplicatie.

Nu exista tab CONTROL. Aplicatia ramane READ-ONLY. Atingerea cadranului, a valorilor PV sau a unei valori
zilnice schimba direct tabul pe ENERGIE si selecteaza graficul potrivit; nu exista un buton separat Istoric
in tema Retro. Placa NAV foloseste la randul ei 95% din dimensiunea anterioara si este centrata, pentru a
lasa vizibil fundalul metalic pe toate laturile.

Codul semantic ramane: verde `#accc78` = solar/normal, albastru = casa/consum, galben `#f1e169` = baterie
sau atentie si rosu = retea/alarma. Olive `#81795a` este material neutru, nu stare energetica.

Fluxul nu foloseste bateria ca nod vizual comun. `resolveRetroEnergyFlow()` stabileste ramuri independente:

- Panouri → Casa: cablu vertical cu LED-uri verzi cand exista productie si consum;
- Panouri → Baterie: ramura diagonala verde numai cand bateria se incarca;
- Baterie → Casa: ramura orizontala galbena numai cand bateria se descarca;
- Retea → Casa/Baterie: rosu numai pentru importul corespunzator.

Puterea bateriei este verde la incarcare, galbena la descarcare si olive in zona neutra de ±50 W. Valoarea
PV este plasata la dreapta miniaturii panourilor, nu peste traseul direct Panouri → Casa.

Valorile din FLUX folosesc 18 sp bold (cu 20% peste vechiul 15 sp). Eticheta din stanga sus este
`Versiune V${BuildConfig.VERSION_NAME}`, astfel incat afisajul urmeaza automat versiunea reala a APK-ului,
fara text duplicat in layout.

Sub FLUX, TABLOU afiseaza doua placi (Baterie, Invertor) si, de la v3.30, banda BMS: titlu + LED de stare
si trei placi mai mici (SOC, curent, diferenta intre celule). Ramele sunt PNG fotografice, iar valorile sunt
VFD dinamic Compose. Placa temperaturii si cuvantul „Temperatura" au fost scoase; temperatura invertorului
apare discret, ca „29°C", sub eticheta „Inv". Placile BMS folosesc `retro_dashboard_dial_blank.webp` = placa
temperaturii cu „°C"-ul sters (`scripts/make-retro-blank-dial.py`), ca Compose sa scrie %, A, mV peste ea.
Atingerea benzii BMS duce pe SISTEM.

Implementarea este impartita astfel:

- `RetroDashboard.kt` — layout TABLOU/SISTEM, cardurile fotografice, acul si fluxul animat;
- `RetroIndustrialTheme.kt` — culori, VFD, panouri, relief, suruburi si LED-uri;
- `RetroIndustrialIcons.kt` — miniaturi si iconografie modulara;
- `MainActivity.kt` — starea comuna, ENERGIE, SETARI, graficele si tema Simple.

### Pagina ENERGIE: interacțiuni și grafice native

Cele trei plăci Photoshop ale paginii ENERGIE rămân fundalul fotografic. Peste ele, Compose definește
zone tactile proporționale cu dimensiunea plăcii, folosind `BoxWithConstraints`. Coordonatele sursă sunt
înmulțite separat cu `scaleX` și `scaleY`, deci butoanele rămân aliniate chiar când ultimul card primește
numai înălțimea disponibilă.

Controalele sunt trei rânduri, citite de sus în jos (`EnergySelection.kt`): CE urmărești (subiect), CUM (mod) și CÂT
timp (interval). Fiecare combinație e un câmp al API-ului:

| Subiect | Mod | Câmp API | Grafic |
|---|---|---|---|
| CASA | PUTERE · W | `output_power` | linie |
| CASA | ENERGIE · kWh | `energy_load_today` | bare |
| PANOURI | PUTERE · W | `pv_power` | linie |
| PANOURI | ENERGIE · kWh | `energy_pv_today` | bare |
| BATERIE | TENSIUNE · V | `battery_voltage` | linie |
| RETEA (v3.32) | PUTERE · W | `grid_power` | linie |
| RETEA (v3.32) | ENERGIE · kWh | `energy_grid` | bare |

Intervalele sunt 2h, 1d și 7d la toate. Rândul de subiecte are, din v3.32, patru plăci: poza șasiului a fost
peticită (`scripts/patch-retro-energy-subjects.py`) ca să aibă patru plăci goale, iar pictograma și numele le
desenează Compose (`RetroSubjectLabel`); celelalte două rânduri (moduri, intervale) sunt plăci native opace peste
textul pictat vechi. Accentul subiectului: CASA albastru, PANOURI verde, BATERIE galben, RETEA roșu (ca rețeaua în FLUX).
Valorile sub 1 kWh au două zecimale, iar axele cu pas sub 0,1 la fel.

Schimbarea seriei sau a intervalului activează `LaunchedEffect`, afișează starea de încărcare și înlocuiește
graficul numai după răspuns. Dacă API-ul nu răspunde, mesajul din rama graficului poate fi apăsat pentru reîncercare.

Graficele sunt desenate nativ cu `Canvas`, direct în fereastra goală a ramei: bare pentru energie, linie cu umplere
discretă pentru putere și tensiune, grilă, axe și pragurile bateriei. Datele, titlul, unitatea și selecțiile nu sunt
lipite în bitmap. Pagina nu folosește `verticalScroll`.

API-ul rămâne READ-ONLY și citește numai InfluxDB. Barele de energie integrează puterea (2h: bare de 10 minute, 1d: pe
oră, ambele din `live`; 7d: pe zi, din `history`); la CASA și PANOURI 7d/30d iau maximul zilnic al contorului invertorului
(`*_today` se resetează la miezul nopții). Pentru RETEA nu există contor: `grid_power` (import pe bypass + încărcare AC)
îl scrie collector-ul, iar barele îl integrează și la 7d.

### Zoom elastic (v3.31)

`ElasticZoom.kt` înfășoară tot tabloul de bord (Retro și Simple; fereastra de alarmă rămâne deasupra, nemărită). Cu două
degete aplicația se mărește până la ~3× (limită moale, `elasticScale`) și se mută odată cu degetele; la ridicare revine,
cu un mic resort, la mărimea inițială, fără nicio stare „mărit". Un deget singur trece neschimbat spre conținut (atingeri,
glisare între taburi); gestul cu două degete e consumat în pasul `Initial`, deci butoanele și glisarea nu se declanșează.
Se verifică în emulator cu `emulator-check.sh pinch`.

### Pagina SISTEM: structură fotografică V5

SISTEM folosește două plăci fotografice independente peste fundalul comun:
`retro_system_top_artwork.webp` și `retro_system_info_artwork.webp`. Prima etapă afișează numai materialul
Photoshop, fără valori native. Ferestrele sunt intenționat goale până la aprobarea pe telefon.

Ambele plăci sunt centrate la 95% din lățime și păstrează proporțiile compoziției
`pag-sistem-Retro-V5.png`. Sunt ancorate sus într-un `Column` fără scroll. Spațiul rămas pe telefoane foarte
înalte rămâne fundal metalic; cardurile nu sunt întinse automat până la NAV. Bara NAV este componenta
globală existentă, nu o copie din imaginile paginii.

### Pagina SETĂRI: structură fotografică V5

SETĂRI folosește trei exporturi Photoshop independente, importate lossless pentru a păstra patina,
zgârieturile, șuruburile și umbrele originale:

- `retro_settings_top_artwork.webp` — titlul și indicatoarele TEMA/ALARMA/SUNET;
- `retro_settings_theme_artwork.webp` — selectorul RETRO/SIMPLE;
- `retro_settings_alarm_artwork.webp` — alarma, pragul, cooldown-ul, vibrația și sunetul.

Cardurile pornesc la aceeași cotă fizică de 40 px ca TABLOU și SISTEM. Un calcul din
`BoxWithConstraints` păstrează raportul real al fiecărui export și reduce uniform toate cardurile numai
dacă înălțimea telefonului nu este suficientă. Nu există deformare separată pe X/Y și nu există scroll.
NAV-ul rămâne componenta globală comună, fixată identic pe toate paginile.

Compose adaugă zone tactile proporționale peste artwork. Tema, alarma, pragul 0–10000 W, cooldown-ul
0–600 s, vibrația, selectorul de sonerie și testul de alarmă păstrează logica existentă. Slider-ele sunt
invizibile în starea implicită pentru a nu acoperi materialul Photoshop; dacă valoarea diferă de exportul
de bază, Compose desenează peste traseu poziția live. Numele soneriei rămâne text dinamic.

### Navigare prin swipe

Pe lângă NAV, `RetroDashboard` recunoaște swipe orizontal pe conținut:

- swipe spre stânga deschide pagina următoare;
- swipe spre dreapta deschide pagina anterioară;
- prima și ultima pagină opresc navigarea la margine;
- gesturile scurte sau predominant verticale sunt ignorate.

Schimbarea folosește o tranziție laterală de 240 ms. Detectarea rulează după controalele copil: un slider
sau buton care consumă gestul are prioritate și nu schimbă accidental pagina. Implementarea nu folosește
`HorizontalPager`, deci nu introduce un container scrollabil și păstrează contractul ecranelor fixe.

## Tema Simple — explicatia redesignului initial

## 1. Ce pastreaza noul ecran

Aplicatia continua sa citeasca acelasi endpoint `GET /solar/latest` la doua secunde. Nu s-a schimbat
collectorul, API-ul, maparea registrelor, alarma sau regula fundamentala READ-ONLY. Redesignul foloseste
aceleasi obiecte `SolarData` si `HistoryMetric`; se schimba numai felul in care sunt grupate si desenate.

Ierarhia noua este:

```text
App
├── Header                         titlu, Istoric, Setari
├── EnergyOverview                o singura suprafata principala
│   ├── EnergyFlow                Panouri, Casa, Baterie, Retea
│   └── DailySummary              Produs si Consum astazi
├── SystemDetails                 detalii tehnice in randuri
├── HistoryMenuSheet              selector unic pentru istoric
├── HistorySheet                  graficul deja existent
└── SettingsSheet / AlarmOverlay  functiile deja existente
```

Casa este nodul cel mai mare, pentru ca raspunde prima la intrebarea „cat consum acum?”. Panourile au
urmatoarea importanta. Bateria si reteaua raman vizibile, dar nu concureaza tipografic cu primele doua.
Energia zilnica este in aceeasi suprafata cu valorile live, nu in alte doua carduri. Valorile mai tehnice
sunt mutate intr-o singura lista `Detalii sistem`.

## 2. Deciziile vizuale

- Verdele, albastrul, galbenul si rosul apar numai in puncte, valori, particule si iconite mici.
- Cardurile nu mai au contur colorat. `Surface` foloseste o culoare tonala, colturi rotunjite si elevatie
  foarte mica.
- Numarul este bold si mare, iar unitatea este mai mica si gri. Astfel `865` este citit inainte de `W`.
- Nu mai exista `>>>`, `vvv` sau chevroane. Liniile de flux sunt desenate cu `Canvas`; trei particule mici
  se deplaseaza pe fiecare traseu activ.
- Daca datele nu au sosit, UI-ul arata `—` si „astept date”, nu un `0` care ar putea fi interpretat gresit.
- Istoricul are o actiune clara in header. Simbolul de grafic de langa o valoare indica si faptul ca acea
  valoare poate fi apasata direct.

## 3. Cum functioneaza layout-urile Compose

Toata implementarea este in `MainActivity.kt`.

### `Column`, `Row` si `Box`

`Column` pune copiii vertical, unul sub altul. In `App`, headerul, overview-ul si detaliile sunt intr-un
`Column`. `verticalArrangement = Arrangement.spacedBy(16.dp)` insereaza automat 16 dp intre ei.

`Row` pune copiii orizontal. De exemplu, cele doua valori din `DailySummary` stau in acelasi `Row`.
`Modifier.weight(1f)` le spune sa imparta in mod egal spatiul ramas.

`Box` suprapune copiii. `EnergyFlow` foloseste un `Box`: `Canvas` este primul copil, deci deseneaza
traseele in fundal; cele patru `EnergyNode` vin dupa el si apar deasupra liniilor. `Modifier.align(...)`
pozitioneaza fiecare nod in zona dorita: `TopStart`, `BottomStart`, `Center` sau `CenterEnd`.

### Ordinea unui `Modifier`

Un `Modifier` este o lista de operatii aplicate in ordinea in care sunt scrise. Exemplu simplificat:

```kotlin
Modifier
    .fillMaxWidth()
    .clip(RoundedCornerShape(14.dp))
    .clickable(onClick = onClick)
    .padding(10.dp)
```

- `fillMaxWidth()` cere toata latimea disponibila de la parinte.
- `clip(...)` taie desenul si efectul de apasare la forma cu colturi rotunjite.
- `clickable(...)` face zona interactiva si adauga feedback vizual la atingere.
- `padding(10.dp)` muta continutul cu 10 dp spre interior. Fiind pus dupa `clickable`, spatiul de padding
  ramane parte din tinta tactila.

Modifierii folositi pe ecran:

- `fillMaxSize()` ocupa toata latimea si toata inaltimea disponibila.
- `fillMaxWidth()` ocupa numai latimea completa.
- `height(...)`, `width(...)` si `size(...)` dau o dimensiune exacta.
- `heightIn(min = ...)` impune doar o inaltime minima; continutul poate creste daca are nevoie.
- `padding(...)` creeaza spatiu interior sau exterior, in functie de pozitia lui in lant.
- `background(...)` deseneaza o culoare in spatele continutului.
- `clip(...)` limiteaza desenul la o forma.
- `verticalScroll(rememberScrollState())` permite derularea numai in tema Simple; Retro este intentionat fix.
- `weight(1f)` imparte spatiul liber intre copiii unui `Row` sau `Column`.
- `align(...)` pozitioneaza un copil in interiorul unui `Box`.
- `then(...)` adauga un modifier calculat conditionat; aici este folosit pentru click doar cand exista
  istoric.
- `semantics { contentDescription = ... }` da un nume butonului pentru TalkBack/accesibilitate.

`dp` inseamna density-independent pixel si se foloseste pentru dimensiuni. Android il adapteaza densitatii
ecranului. `sp` este folosit pentru text si respecta inclusiv marimea de font aleasa de utilizator.

## 4. Suprafetele Material 3

Overview-ul nu este construit cu `background + border`, ci cu:

```kotlin
Surface(
    shape = RoundedCornerShape(28.dp),
    color = CPanel,
    tonalElevation = 2.dp,
    shadowElevation = 1.dp
) { ... }
```

- `shape` stabileste forma suprafetei.
- `color` este baza dark.
- `tonalElevation` diferentiaza suprafata printr-o variatie foarte discreta a tonului Material 3.
- `shadowElevation` adauga o umbra minima. La 1–2 dp separa planurile fara aspect de „cutie conturata”.

Nodul Casa are o suprafata putin mai luminoasa (`CPanelRaised`) si elevatie ceva mai mare. Este singurul
nod accentuat astfel, pentru a crea ierarhie, nu decor.

## 5. Tipografia numar + unitate

`MeasurementText` construieste un singur `Text`, dar cu doua stiluri:

```kotlin
buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
        append(number)
    }
    append(" ")
    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)) {
        append(unit)
    }
}
```

`buildAnnotatedString` permite mai multe stiluri in aceeasi linie. Numarul ramane dominant, iar unitatea
nu mai are aceeasi greutate vizuala. Fiind un singur `Text`, `865 W` ramane aliniat si nu se rupe usor.

## 6. Fluxul energetic animat

Animatia are doua parti. Prima produce continuu un numar `phase` intre 0 si 1:

```kotlin
val phase by rememberInfiniteTransition().animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        animation = tween(1_400, easing = LinearEasing)
    )
)
```

`rememberInfiniteTransition` pastreaza animatia intre recompozitii. `tween(1_400)` inseamna ca o trecere
dureaza 1,4 secunde. `LinearEasing` pastreaza viteza constanta.

A doua parte interpoleaza pozitia unei particule intre punctul de start si cel de final:

```kotlin
val x = start.x + (end.x - start.x) * progress
val y = start.y + (end.y - start.y) * progress
```

La `progress = 0`, particula este la `start`; la `progress = 1`, este la `end`. Trei particule folosesc
aceeasi formula cu decalaje de o treime. Directia nu necesita sageti: ordinea `start -> end` spune directia.

Conexiunile active sunt:

- Panouri spre Casa cand productia depaseste pragul mort de 50 W.
- Panouri spre Baterie cand bateria se incarca.
- Baterie spre Casa cand bateria se descarca.
- Retea spre Casa cand exista import.

Liniile inactive raman foarte subtiri si neutre, ca topologia sistemului sa fie inteleasa fara a atrage
atentia. Pentru o animatie mai lenta se mareste `durationMillis`; pentru mai putine particule se schimba
`repeat(3)` in `repeat(2)`.

## 7. Istoricul in tema Simple

Lista unica `DashboardHistoryMetrics` defineste cele cinci grafice disponibile. Butonul `Istoric` deschide
`HistoryMenuSheet`; atingerea unei intrari seteaza `selectedHistory`, iar `App` deschide `HistorySheet`.
Aceeasi functie este apelata cand utilizatorul atinge direct Casa, Panouri, Baterie sau valorile zilnice.

Avantajul este ca nu mai exista texte mici „istoric” cu culori diferite si tinte tactile neclare. Exista o
intrare globala usor de descoperit, plus scurtaturi contextuale pe valorile relevante.

## 8. Cum se leaga datele reale

Nu trebuie facuta o conversie noua. Exemplele principale sunt:

```kotlin
data?.pv                 // puterea totala a panourilor, W
data?.house              // consumul casei, W
data?.batteryDisplay     // + incarcare, - descarcare, W
data?.gridImport         // import din retea, W
data?.batteryVoltage     // tensiunea reala, V
data?.energyPvToday      // energie produsa azi, kWh
data?.energyLoadToday    // energie consumata azi, kWh
```

Operatorul `?.` inseamna „foloseste proprietatea numai daca obiectul nu este null”. Cat timp primul raspuns
de retea nu a sosit, functiile `wholeNumber` si `decimalNumber` transforma `null` in `—`. Dupa sosirea unui
nou `SolarData`, Compose observa schimbarea state-ului `data` si redeseneaza automat numai partile care il
folosesc.

## 9. Pagina SISTEM Retro V5

Cele două carduri păstrează artwork-ul Photoshop, dar folosesc raporturile reale ale resurselor și
`ContentScale.Fit`; bitmap-urile nu sunt întinse pentru a umple o înălțime artificială. Pornirea verticală
este comună cu primul card din TABLOU, iar spațiul dintre carduri este convertit din 40 px în `Dp` folosind
densitatea ecranului.

Ferestrele instrumentelor sunt suprapuneri Compose native. Ele primesc valorile live din `SolarData`:

- Consum casă: `house`, afișat în W și colorat albastru.
- Panouri: `pv`, afișat în W și colorat verde.
- Baterie: `batteryVoltage`, afișată cu două zecimale în V și colorată galben.
- Consum invertor: `inverterLoss`, afișat corect în W.
- Temperatură: `inverterTemp`, afișată în °C cu praguri verde/galben/roșu.
- Rețea: `gridVoltage`, afișată în V și colorată roșu.

`RetroVfdDisplay` desenează cifrele, unitatea, glow-ul și scanline-urile peste ferestrele fotografice.
Valorile rămân dinamice; nu sunt lipite în resursele bitmap. Când telemetria lipsește, toate ferestrele
afișează `—`, nu valori zero inventate.

Primele trei ferestre sunt și scurtături contextuale. Atingerea lor schimbă tabul global în ENERGIE și
selectează `output_power`, `pv_power` sau `battery_voltage`. Bara NAV rămâne o singură instanță comună
tuturor paginilor.

## 10. Monitorul de sistem și consola

Artwork-ul `retro_system_monitor_artwork.webp` este stratul fotografic al cardului inferior. Este ancorat
la baza suprafeței de conținut și folosește raportul nativ 1030/531, fără deformare. Titlul, metricile,
graficele și consola sunt desenate de Compose.

Rândul de diagnostic citește din același răspuns `/solar/latest`:

- `serverCpuPercent`: utilizarea agregată a procesoarelor gazdei.
- `serverMemoryPercent`: memoria folosită raportată la `MemAvailable`.
- `serverUptimeSeconds`: uptime-ul Linux, formatat în zile și ore.
- `serverUploadKbps`: traficul total transmis de interfețele gazdei, fără loopback și interfețe Docker.

API-ul obține aceste agregate dintr-un mount `/proc` strict read-only. Aplicația nu primește informații
despre procese, adrese, hostname sau containere.

Cele două mini-grafice citesc `battery_voltage` și `inverter_temp` pe intervalul `1h`. Cât pagina SISTEM
este vizibilă, Compose încarcă seriile la intrare și apoi la fiecare 60 secunde. Plecarea din tab oprește
automat coroutine-ul. Telemetria principală rămâne la polling-ul existent de două secunde.

Până la v3.29 sub mini-grafice era o „consolă" cu temperatura, API/upload, starea invertorului, bateria și
rețeaua. De la v3.30 locul ei îl ia monitorul BMS (`RetroBmsMonitor` în `RetroBms.kt`): titlu și LED de
stare, câte o bară pe celulă (abaterea de la medie, ±15 mV scară plină; galben ≥ 10 mV, roșu ≥ 30 mV),
apoi SOC · curent · tensiune, diferența · temperaturi · MOSFET și un rând cu MOSFET-uri, echilibrare, Ah
și cicluri; la alarmă ultimul rând devine mesajul alarmei. Totul e READ-ONLY, din `/solar/bms`.
