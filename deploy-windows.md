# Solar Monitor — instalare pentru dezvoltare pe Windows 10

Acest document este un hand-off pentru agentul Codex/GPT-5.6-SOL care va pregati proiectul pe calculatorul
Windows. Scopul este deschiderea, compilarea si verificarea aplicatiei Android in Android Studio, fara
modificarea serverului sau a logicii READ-ONLY.

## 1. Locatia proiectului

Cloneaza repository-ul (sau extrage arhiva) astfel incat structura sa fie:

```text
H:\__Proiecte\_Growatt\solar-monitor\
├── android\
├── api\
├── collector\
├── deploy-windows.md
└── ...
```

Varianta recomandata pentru Git:

```powershell
Set-Location 'H:\__Proiecte\_Growatt'
git clone https://github.com/karen20dec4/solar-monitor.git
```

Proiectul care trebuie deschis in Android Studio este:

```text
H:\__Proiecte\_Growatt\solar-monitor\android
```

Nu deschide radacina `solar-monitor` ca proiect Android. Radacina contine si componentele Linux ale
serverului, in timp ce `android` este proiectul Gradle propriu-zis.

## 2. Citeste contextul inainte de orice schimbare

Citeste integral, in aceasta ordine:

1. `CLAUDE.md`
2. `COPILOT_CONTEXT.md`
3. `android\DASHBOARD_REDESIGN.md`
4. `.codex\skills\solar-monitor-emulator\SKILL.md`

Reguli care nu trebuie incalcate:

- aplicatia monitorizeaza invertorul si ramane strict READ-ONLY;
- tema Retro are patru taburi fixe si nu permite scroll vertical;
- imaginile fotografice raman decorative, iar valorile, LED-urile si interactiunile raman dinamice in Compose;
- nu reseta schimbarile locale din arhiva si nu inlocui resursele WebP cu forme vectoriale;
- nu incrementa versiunea si nu crea release pana cand utilizatorul cere explicit acest lucru.

## 3. Instaleaza Android Studio si SDK-ul

Foloseste versiunea stabila Android Studio pentru Windows de la:

<https://developer.android.com/studio/install>

In Setup Wizard sau `Tools > SDK Manager`, instaleaza:

- Android SDK Platform 34;
- Android SDK Build-Tools;
- Android SDK Platform-Tools;
- Android Emulator;
- un system image Google APIs Android 14/API 34, x86_64, daca va fi folosit emulatorul.

Documentatia oficiala pentru SDK Manager este:

<https://developer.android.com/studio/intro/update>

Proiectul foloseste:

- Android Gradle Plugin 8.5.2;
- Gradle Wrapper 8.9;
- Kotlin si Compose plugin 2.0.21;
- Java/JVM target 17;
- `compileSdk` si `targetSdk` 34;
- `minSdk` 26.

In Android Studio seteaza `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`
la JDK-ul integrat compatibil cu Java 17. Nu seta o cale Linux pentru Java sau SDK.

## 4. Deschide si sincronizeaza proiectul

1. Porneste Android Studio.
2. Alege `Open` si selecteaza `H:\__Proiecte\_Growatt\solar-monitor\android`.
3. Confirma `Trust Project`.
4. Lasa Android Studio sa creeze automat `android\local.properties` cu `sdk.dir` pentru calculatorul Windows.
5. Ruleaza `File > Sync Project with Gradle Files` si asteapta descarcarea dependentelor.

Clona nu contine vechiul `local.properties` de Linux, cache-uri Gradle sau directoare `build`, cu exceptia
referintei `design-v5.png` si a celor sase exporturi Photoshop versionate explicit.

## 5. Verificare din PowerShell

Din PowerShell:

```powershell
Set-Location 'H:\__Proiecte\_Growatt\solar-monitor\android'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Rezultatul debug trebuie sa fie:

```text
H:\__Proiecte\_Growatt\solar-monitor\android\app\build\outputs\apk\debug\app-debug.apk
```

Daca build-ul esueaza din cauza SDK-ului, verifica `local.properties` si instalarea Platform 34. Nu modifica
versiunile Gradle/Kotlin ca metoda de evitare a configurarii SDK.

## 6. Emulatorul de referinta

In `Tools > Device Manager`, creeaza:

- profil Pixel 6;
- Android 14 / API 34 / Google APIs;
- rezolutie de referinta 1080×2400;
- orientare Portrait.

Porneste emulatorul si ruleaza configuratia `app`. Aplicatia foloseste tema Retro implicit daca nu exista
preferinte salvate.

Verifica vizual, la zoom 100%:

- suruburile cadrului superior sunt complet vizibile;
- cardul `Versiune` si cardul `FLUX ENERGETIC` nu se ating si nu se suprapun;
- fundalul metalic se vede in jurul ambelor carduri si in jurul NAV-ului;
- NAV-ul ramane fix jos;
- TABLOU, ENERGIE, SISTEM si SETARI se deschid fara scroll vertical;
- valorile live nu sunt parte din bitmap;
- directia Panouri → Casa si culorile bateriei respecta documentatia.

Pentru un telefon fizic, activeaza USB debugging, selecteaza dispozitivul in Android Studio si foloseste
`Run app`. Nu este necesara cheia de release pentru instalarea build-ului debug.

## 7. Resursele Photoshop

Resursele Android deja generate sunt in:

```text
android\app\src\main\res\drawable-nodpi
```

Sursele Photoshop/PNG sunt versionate in:

```text
android\build\emulator-artifacts\design\optimized\text-display
```

Un build normal nu necesita ImageMagick. Scripturile `scripts\audit-retro-ui-assets.sh` si
`scripts\prepare-retro-ui-assets.sh` sunt Bash; pentru regenerarea resurselor pe Windows foloseste Git Bash
sau WSL si instaleaza ImageMagick cu executabilul `magick`. Cele patru surse PNG vechi pentru fundal,
ACUM, FLUX si NAV au fost sterse intentionat; nu le recrea. Resursele WebP finale sunt deja versionate.

## 8. Git si siguranta

Clona include istoricul `.git` si sursele curente. Inainte de modificari ruleaza:

```powershell
Set-Location 'H:\__Proiecte\_Growatt\solar-monitor'
git status --short
git diff --check
```

Imediat dupa clonare, `git status --short` trebuie sa fie gol. Nu folosi `git reset --hard`,
`git checkout -- .` sau alte comenzi care ar sterge modificarile facute ulterior.

Repository-ul exclude intentionat:

- `.env` si orice credentiale ale serverului;
- cheia privata si parolele de semnare Android;
- `keystore.properties`;
- APK-urile deja compilate;
- cache-urile Gradle, logcat, capturile intermediare si celelalte fisiere generate.

Build-ul debug functioneaza fara aceste secrete. Pentru un release semnat trebuie cerute separat fisierele
de semnare; nu genera o cheie noua, deoarece ar rupe upgrade-ul peste aplicatia instalata.

## 9. Rezultatul asteptat de la agentul Windows

Agentul trebuie sa raporteze:

1. versiunea Android Studio si calea SDK detectata;
2. rezultatul Gradle sync;
3. rezultatul testelor, build-ului debug si lint-ului;
4. emulatorul sau telefonul folosit;
5. o captura TABLOU la rezolutia originala;
6. orice diferenta vizuala fata de captura Linux, fara a modifica designul din proprie initiativa.
