# Dashboard Android — teme Simple si Retro

> Din versiunea 2.1, dashboardul descris initial mai jos este tema `Simple`. Aplicatia porneste implicit
> cu tema `Retro`, implementata separat in `RetroDashboard.kt`. Utilizatorul poate schimba tema din
> Settings, iar `DashboardStyleStore` pastreaza alegerea local.

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
- `verticalScroll(rememberScrollState())` permite derularea ecranului pe telefoane mai mici.
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

## 7. Istoricul

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
