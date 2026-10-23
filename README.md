############################################################
############################################################
############################################################
>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
<<< sesiune claude pe linux in directorul /opt/solar-monitor >>>
claude --resume 81479638-c44a-4616-b180-9a88efd1603b
>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
#############################################################
#############################################################
#############################################################


# Solar Monitor — Growatt SPF 6000 ES Plus

Monitorizare 100% locală (self-hosted), **READ-ONLY**, fără cloud / fără ShinePhone.

## Acces

- **Grafana:** http://192.168.1.199:3000/d/solar-main
  - credentialele sunt în `.env` pe server
- **InfluxDB:** http://192.168.1.199:8086
  - credentialele sunt în `.env` pe server

## Documentație

Toate fișierele de documentație din repo, în ordinea în care le citești:

| Fișier | Conținut |
|---|---|
| **`GEMINI.md`** | Punct de intrare pentru agentul Gemini care continuă proiectul: invariantul READ-ONLY, arhitectura, protocoalele, comenzile, regulile UI-ului Retro, capcanele. Trebuie citit **explicit** — `agy --print` nu îl injectează automat. |
| **`CLAUDE.md`** | Echivalentul pentru Claude Code. Același conținut esențial; ține-le sincronizate când schimbi o regulă. |
| **`docs/DEZVOLTARE.md`** | **Ghidul de dezvoltare.** Harta verticală a straturilor, cum adaugi o funcție cap-coadă, capcana etichetelor desenate în poze, procedura de release Android și livrarea pe Telegram, checklist de încheiere. |
| **`COPILOT_CONTEXT.md`** | Jurnal de sesiuni și decizii (secțiunile `13.x`): istoricul descoperirii registrelor, fiecare release, incidente. Tabelul de registre are porțiuni **învechite** — `collector/collector.py` e sursa de adevăr. |
| **`deploy/README-firewalld.md`** | firewalld, rețeaua Docker, sonda end-to-end. De citit la orice problemă de trafic între containere. |
| **`bms/jk_bms_reader.py`** | Cititorul bateriei: BMS JK prin Bluetooth LE → InfluxDB (măsurătoarea `bms`). Serviciu systemd pe host (`deploy/solar-bms.service`), strict READ-ONLY. Detalii în `CLAUDE.md` §„BMS reader". |
| **`docs/BATERIE.md`** | Bateria: ce avem (NMC 14S, 9,3 kWh, 220 cicluri), anunțurile OLX discutate (al doilea pachet NMC sau trecere la LiFePO4), de ce nu se amestecă chimiile, ce se verifică la ridicare, ce ar însemna pentru software. **Decizia nu e luată.** |
| **`android/DASHBOARD_REDESIGN.md`** | Design-ul temelor `Simple` și `Retro`, arhitectura hibridă fotografie + Compose, lista resurselor. |
| **`deploy-windows.md`** | Fluxul de lucru de pe stația Windows (Android Studio, scp către server). |
| **`.codex/skills/solar-monitor-emulator/SKILL.md`** | Emulatorul Android headless: comenzi, AVD, renderer, politica de oprire. |
| **`.codex/skills/solar-monitor-release/SKILL.md`** | Fluxul de release al APK-ului, pe scurt. |
| `README.md` | Fișierul de față: acces, arhitectură, protocoale, operare. |

## Arhitectură

```
Invertor --USB (/dev/growatt)--> Collector Python (Modbus RTU, READ-ONLY)
    |-> InfluxDB bucket `live`    (1s,  retenție 48h)
    |-> InfluxDB bucket `history` (medii pe 60s, retenție 31 zile)
    |-> API Android READ-ONLY (telemetrie, istoric și agregate CPU/RAM din `/proc:ro`)
    `-> Grafana (dashboard live 1s + istoric 30 zile)
```

## Protocoale — cum ajung datele din invertor pe telefon

Un singur protocol atinge invertorul; restul lanțului e HTTP obișnuit.

| Etapă | Protocol | Detalii |
|---|---|---|
| Invertor → server | **Modbus RTU** peste **serial RS-232 prin USB** | 9600 baud, 8N1, timeout 1 s, slave `1`. Adaptor Exar XR21B1411 (`04e2:1411`, driver kernel `xr_serial`), fixat de udev pe `/dev/growatt`. |
| Citirea propriu-zisă | **FC04** — Read Input Registers | `inst.read_registers(0, REG_COUNT, functioncode=4)`, un singur bloc de 91 registre, o dată pe secundă. **Singura** operație Modbus din tot proiectul. |
| Collector → InfluxDB | HTTP (line protocol InfluxDB v2) | `http://influxdb:8086`, intern în rețeaua Docker |
| API / Grafana → InfluxDB | HTTP + **Flux** | interogări pe bucket-urile `live` și `history` |
| Telefon → server | **HTTPS** (TLS intern Caddy) + JSON | `https://vyra.go.ro:31443/solar/latest` → Caddy → `api:8000` |
| Alerte → telefon | HTTP POST către **ntfy** | topic din `.env`, push nativ pe Android |

Ce **nu** folosim, deliberat: fără ShineServer / cloud Growatt, fără MQTT, fără dongle
WiFi/GPRS ShineWiFi (data-logger-ul oficial a fost scos — OTA-urile lui au blocat invertorul de
trei ori în două zile), fără MODBUS TCP, fără scriere de niciun fel.

Mapările registru → mărime fizică sunt în `parse()` din `collector/collector.py`, care este
**sursa de adevăr**; tabelul de registre din `COPILOT_CONTEXT.md` are porțiuni învechite.

## ⚠️ GARANȚIE READ-ONLY

Collector-ul **CITEȘTE DOAR**. Singura operație Modbus din `collector/collector.py`
este `read_registers(..., functioncode=4)` (FC04 = read input registers).
**Nu scrie NICIODATĂ** în invertor, nu poate modifica nicio setare sau configurație.
Nu există niciun apel de scriere (FC06 / FC16) în cod.

## Operare

```bash
cd /opt/solar-monitor
docker compose up -d        # pornire
docker compose down         # oprire
docker compose logs -f collector   # loguri collector
docker compose ps           # status
```

Stack-ul pornește automat la boot (`restart: unless-stopped` + Docker enabled).

### Sondă end-to-end și firewall

Alertele din `collector.py` acoperă invertorul. Sonda `solar-watchdog` acoperă *lanțul complet*,
exact cum îl vede telefonul, și trimite push ntfy când se rupe:

```bash
systemctl list-timers solar-watchdog.timer   # rulează la 15 min
systemctl start solar-watchdog.service       # verificare manuală acum
journalctl -u solar-watchdog.service -n 20   # rezultate
```

Dacă aplicația arată „aștept date" deși collector-ul loghează normal, **prima verificare** e
firewall-ul, nu invertorul:

```bash
firewall-cmd --get-active-zones   # br-solar TREBUIE să fie în zona docker
```

`Errno 113` / `no route to host` între containere = problemă de zonă firewalld. Context complet,
cauza incidentului din 2026-08-19 și procedura de instalare: **`deploy/README-firewalld.md`**.

## Configurare

```bash
cp .env.example .env
nano .env
docker compose up -d
```

Fișierul `.env` conține parole și tokenuri reale și nu se comite în Git.

## Flux de deploy

Fluxul preferat:

```text
modificare locală -> git push GitHub -> server beci -> git pull
```

Pe server:

```bash
cd /opt/solar-monitor
git pull
docker compose up -d --build
```

## TODO (viitor — NEimplementat acum, intenționat)

- [ ] **Control invertor din aplicație** (ex: schimbare mod încărcare/prioritate sursă).
      NEIMPLEMENTAT DELIBERAT. Necesită validare atentă a registrelor de scriere
      (holding registers, FC06/FC16) ca să NU introducem configurări greșite.
      Sistemul rămâne **strict citire** până la o decizie explicită + testare.
- [ ] Cablu Ethernet în beci (fiabilitate vs Wi-Fi).
- [ ] Mutarea serverului de pe capacul invertorului (ventilație termică).
