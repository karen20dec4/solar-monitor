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

## Arhitectură

```
Invertor --USB (/dev/growatt)--> Collector Python (Modbus RTU, READ-ONLY)
    |-> InfluxDB bucket `live`    (1s,  retenție 48h)
    |-> InfluxDB bucket `history` (60s, retenție 31 zile)
    |-> API Android READ-ONLY (telemetrie, istoric și agregate CPU/RAM din `/proc:ro`)
    `-> Grafana (dashboard live 1s + istoric 30 zile)
```

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
