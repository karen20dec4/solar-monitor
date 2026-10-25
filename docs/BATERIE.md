# BATERIE — ce avem, ce opțiuni am discutat pentru capacitate în plus

> Stare: **NEDECIS** (2026-10-23). Utilizatorul nu a ales încă între un al doilea pachet NMC și trecerea la
> LiFePO4. Documentul păstrează datele și argumentele ca să nu se refacă discuția; nu e o hotărâre.
> Proiectul rămâne strict **READ-ONLY** față de invertor și BMS: orice setare (tensiuni de încărcare, praguri
> BMS) o face utilizatorul, nu codul.

## 1. Ce avem acum

| | |
|---|---|
| Chimie | **NMC (Li-ion)**, pachet 14S din module de mașină electrică. Eticheta zice „LIION 14S MIN 48V MAX 57V", modulele „MB6VAD" (`COPILOT_CONTEXT.md` §2). |
| Origine | Module de tracțiune din **Opel Vivaro C** („OPEL VIVARO C Battery cell MB6VAD"), **3 module și jumătate** în pachet (confirmat de utilizator, 2026-10-25). Din 14S pe 3,5 module ar ieși 4 celule în serie pe modul: deducție, neconfirmată. |
| Capacitate | 180 Ah × ~51,8 V nominal ≈ **9,3 kWh** (180 Ah e valoarea *setată* în BMS, nu una măsurată) |
| BMS | JK `JK_BD6A20S12P` (hw 11U, sw 11.54), citit prin Bluetooth de `bms/jk_bms_reader.py` (vezi `CLAUDE.md` §„BMS reader") |
| Cicluri | **220** după contorul BMS (39.644 Ah trecuți prin baterie ÷ 180 Ah = 220,2), 2026-10-23. Contorul pornește de când a fost pus BMS-ul: viața modulelor în mașină nu e inclusă. |
| SOH | „100%" în BMS e o valoare fixă, nu o măsurătoare |
| Încărcare | invertorul încarcă la 56 V (4,0 V pe celulă), doar din panouri (`COPILOT_CONTEXT.md` §13.14) |

**Cum știm că e NMC.** BMS-ul nu are câmp „chimie" în cadrele pe care le citim (doar cererile `0x96`/`0x97`;
nu citim nimic din setările lui). Dovezile: eticheta; în 30 de zile celula maximă a ajuns la 3,985 V și pachetul la
55,7 V, iar un LiFePO4 nu trece de 3,65 V pe celulă; încărcarea la 4,0 V pe celulă e curba unui NMC.

**De ce se pune problema capacității.** Pe 2026-10-23 bateria a coborât la 47,58 V pe la 8:05 (alerta
`baterie_jos`), iar invertorul a trecut singur pe **bypass** (casa pe rețea) ~4 ore: ~1,1 kWh luați din rețea.
Zile anterioare cu bypass (kWh din rețea): 5 oct 0,25, 7 oct 0,90, 12 oct 0,38, 13 oct 3,29, 14 oct 1,23. În noaptea de azi un
singur pachet de 9,3 kWh nu a ajuns până dimineața. La 6 kW invertorul poate cere ~125 A, iar pentru modulele de pe OLX
(Hyundai/Kia) vânzătorul recomandă maximum ~100 A continuu pe banc. Două pachete în paralel își împart curentul.

## 2. Anunțurile discutate (2026-10-23; se pot șterge oricând)

Curs folosit pentru comparații: ~5,1 lei/€.

### A. Al doilea pachet NMC — module Hyundai/Kia, 9,3 kWh

[olx.ro … baterie-stocare-fotovoltaic-9-3-kwh-module-auto-hyundai-14s-180ah](https://www.olx.ro/d/oferta/baterie-stocare-fotovoltaic-9-3-kwh-module-auto-hyundai-14s-180ah-IDkZa9e.html)

- Persoană fizică, Căteasca (Argeș, lângă DN65, ~15 km de Pitești), postat 3 oct 2026. **Fără garanție, fără curier**, doar ridicare.
- 3 bancuri; module originale Hyundai/Kia **HL GreenPower, cod BK330-B0000**; NMC „celule pungă LG" (afirmația vânzătorului, neverificată).
- 14S, 180 Ah, ~9,3 kWh, 51,8 V nominal, interval 46,2–57,4 V (3,30–4,10 V pe celulă), până la 100 A continuu.
- **Cicluri 81–188** după banc, Δ între celule în repaus 2–5 mV, fără intrări în protecție (Time Emerg. = 0), fără umflături; oferă capturi din BMS pe fiecare banc.
- **2.500 lei fără BMS / 3.000 lei cu BMS JK montat și configurat**, negociabil. Nu e clar dacă prețul e pe un banc sau pe toate trei (dacă e pe toate trei, e prea ieftin ca să fie adevărat).
- Pe un banc: ~53 €/kWh.

### B1. LiFePO4 sVolt 135 Ah, 7 kWh — firmă, cu factură

[olx.ro … modul-baterie-lifepo4-48v-135-ah-7kwh-svolt](https://www.olx.ro/d/oferta/modul-baterie-lifepo4-48v-135-ah-7kwh-svolt-factura-si-garantie-livrare-in-tara-sau-predare-fagaras-prahova-bucuresti-IDkkpGR.html)

- Ecovip Pro SRL (firmă, pe OLX din 2016), Făgăraș; reactualizat 19 oct 2026. **Factură, garanție** (durata nu e scrisă), retur 14 zile ca firmă, livrare în țară („livrare cu verificare").
- Modul second-hand scos din pachete mai mari: celule prismatice „blade" sVolt, 16S1P, 48 V, 135 Ah, ~7 kWh, 40 kg, SOH minim 95% (declarat). Cicluri și curent maxim: nespecificate. Resturi de lipici/folie desprinsă în poze.
- **2.499 lei pe modul**, negociabil. BMS separat: JK 100 A cu Bluetooth 380 lei (există și 150/200 A).
- Cu BMS: ~2.880 lei (~565 €) pentru 7 kWh ≈ 80 €/kWh.

### B2. LiFePO4 „13kw", făcut din celule — persoană fizică

[olx.ro … vand-baterie-solara-lifepo4-13kw](https://www.olx.ro/d/oferta/vand-baterie-solara-lifepo4-13kw-nefolosita-facuta-din-celule-bms-jk-IDkYuPI.html)

- Târgu Neamț, persoană fizică, postat 29 sept 2026, **5.000 lei**. Descrierea e o singură linie: „Vand baterie solara LiFePo4 13kw, nefolosita, facuta din celule, BMS Jk".
- Lipsesc: marca și clasa celulelor, Ah, tensiunea, ciclurile, modelul BMS, garanția, factura, livrarea. „13 kW" e putere, nu energie (probabil 13 kWh). Se contrazice: câmpul zice „utilizat", titlul „nefolosită".
- Dacă sunt 13 kWh reale: ~75 €/kWh. Fără date nu se poate evalua; **nu l-aș lua așa cum e scris**.
- De cerut: marca și Ah-ul celulelor (poză cu eticheta/QR), test de capacitate, ciclurile din BMS, greutatea (13 kWh din celule de 280 Ah ≈ 85–90 kg), chitanță/factură.

## 3. De ce nu se amestecă LiFePO4 cu NMC pe aceeași magistrală

- Pachetul nostru are 14 celule NMC, un LiFePO4 are 16: tensiunile nominale sunt apropiate (~51,8 V / 51,2 V), dar curbele de descărcare nu: NMC coboară pe o pantă, LFP stă pe un palier.
- În paralel curentul se împarte neuniform; spre sfârșitul descărcării (~48 V) LFP ajunge în zona abruptă (≈3,0 V pe celulă) când NMC mai are energie, iar un BMS poate tăia mai devreme decât celălalt. Capacitatea utilă nu se adună curat.
- Invertorul are o singură setare de încărcare: 56 V = 4,0 V pe celulă la NMC, dar 3,5 V pe celulă la LFP.
- Deci LFP înseamnă **în locul** NMC-ului (sau un sistem separat), nu lângă el.

## 4. Cele două drumuri

**Drum 1 — al doilea pachet NMC (anunțul A).** Același tip de pachet, același BMS JK, aceleași praguri, 18,6 kWh în total.
Cel mai ieftin și mai simplu. Riscuri: fără garanție, ciclurile vin dintr-un contor resetabil, capacitatea reală neverificată.
Cum se leagă: câte un BMS pe pachet, siguranță pe fiecare, cabluri de lungime egală până la bare, **egalizezi tensiunea
pachetelor înainte să le legi** (diferență peste ~0,3–0,5 V dă curent mare de egalizare); modulele noastre (MB6VAD, din Opel Vivaro C) au altă
etichetă decât cele de pe OLX (BK330-B0000, Hyundai/Kia), deci rezistența internă diferă și un pachet va lucra puțin mai mult.
Dacă apare un banc tot din Opel Vivaro C / MB6VAD, el se potrivește cel mai bine cu ce avem (același tip de modul).
Aș lua **un singur banc**, nu trei: 18,6 kWh sunt suficienți, iar invertorul nu încarcă mai mult de 70 A.

**Drum 2 — trecere la LiFePO4 (anunțul B1, eventual dublat).** Durată de viață mult mai mare (orientativ 3.000–6.000
de cicluri la LFP față de ~1.000–2.000 la module auto NMC — cifre tipice, nu măsurate la noi) și risc termic mai mic.
Costă mai mult pe kWh și un modul (7 kWh) e mai mic decât pachetul actual: pentru ~14 kWh ar trebui două module
(~5.000 lei + 2 BMS) față de ~3.000 lei pentru banca NMC. Pachetul NMC actual ar rămâne de vândut sau de folosit separat.
Singurul anunț serios dintre cele două LFP e B1 (factură, garanție, retur); B2 numai cu datele de mai sus.

## 5. Ce trebuie verificat la ridicare (indiferent de drum)

1. Prețul e pe un banc sau pe toate?
2. Captura din BMS pentru exact bancul cumpărat; cele 14/16 celule măsurate cu multimetrul trebuie să se potrivească cu ea.
3. Test real de descărcare (Ah măsurați); „180 Ah" din BMS e doar setat, iar contorul de cicluri se poate reseta.
4. Module: fără umflături, fără urme de lichid, etichete identice.
5. Plată cash la ridicare, după verificare. Fără avans, fără linkuri de „livrare OLX".
6. La varianta „cu BMS": ce praguri a setat vânzătorul; se corectează pe ale noastre (56 V la încărcare).
7. La B1: durata garanției în scris, ciclurile, un test de capacitate.

## 6. Ce ar însemna pentru software (de făcut după decizie, nu înainte)

- **Cititorul BMS** citește un singur BMS (`BMS_ADDRESS` în `.env`). Pentru al doilea JK trebuie fie o a doua instanță
  a serviciului cu altă adresă și alt tag `device`, fie suport pentru mai multe adrese; apoi panouri Grafana și zone în
  aplicație (SISTEM/TABLOU arată acum un singur BMS). Nu s-a testat dacă telefonul și serverul se pot conecta
  simultan la același BMS (vezi `CLAUDE.md`).
- **Praguri de alertă** (`BMS_CELL_HIGH_V`, `BMS_CELL_LOW_V`, `BMS_DELTA_HIGH_V`, … în `.env`) sunt pe chimie: pentru
  LFP aproximativ celulă maximă 3,65 V și minimă 2,8 V, în loc de 4,12 V și 3,20 V.
- **Invertorul:** tensiunile de încărcare/float (acum 56 V) și pragul de trecere pe rețea le setează utilizatorul din
  panoul invertorului. Proiectul nu scrie nimic acolo (decizie închisă, `COPILOT_CONTEXT.md` §13.14, §13.17).
- **SOC-ul bateriei:** la LFP palierul plat face SOC-ul din tensiune nesigur; BMS-ul numără Ah, deci rămâne cel de
  încredere. Invertorul nu are SOC util (vezi `CLAUDE.md`, „Do not use `battery_soc`").

## 7. Rămâne de decis

- NMC în plus (drum 1) sau LFP (drum 2)? Cât vrea utilizatorul să investească acum?
- Dacă drum 1: se discută cu vânzătorul A (preț pe banc, capturi BMS, test); dacă drum 2: se întreabă B1 despre garanție/cicluri.
- După alegere: se adaptează cititorul BMS, pragurile și panourile (secțiunea 6).
