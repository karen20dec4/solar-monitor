# Schimbare server Solar Monitor

Runbook pentru ziua mutarii de pe serverul vechi `192.168.1.199` pe serverul nou `192.168.1.150`, cu pastrarea IP-ului final `192.168.1.199`.

Obiectiv: noul server inlocuieste complet serverul vechi fara modificari in aplicatie/routere. Stack-ul ramane 100% local si READ-ONLY fata de invertor.

## Stare pregatita deja

- Server vechi: `root@192.168.1.199`, Linux Mint, stack-ul activ in `/opt/solar-monitor`.
- Server nou: `root@192.168.1.150`, Debian 13, hostname `hpG4`.
- Pe serverul nou:
  - A fost mutat in beci si conectat prin cablu Ethernet.
  - OpenClaw/Ollama si modelele locale au fost sterse.
  - Docker + Docker Compose sunt instalate si active.
  - Android SDK este in `/opt/android-sdk`.
  - Gradle este in `/opt/gradle-8.9`.
  - Repo-ul este in `/opt/solar-monitor`.
  - Build Android `debug` si `release` a fost verificat cu succes.
  - Regula udev pentru invertor este instalata in `/etc/udev/rules.d/99-growatt.rules`.
  - Imaginile Docker pentru stack sunt pregatite.
  - Nu exista containere/volume Docker create pe noul server.

## Stare mutare 2026-06-16

- IP-ul final `192.168.1.199` este setat pe serverul nou `hpG4`, interfata Ethernet `enp1s0`.
- Discul Seagate de ~1 TB este formatat `ext4`, montat permanent in `/data`.
- Directoare pregatite pe HDD: `/data/backups`, `/data/projects/kotlin`, `/data/projects/php`, `/data/large-files`.
- Volumele Docker au fost copiate/restaurate pe serverul nou; backup-ul arhivelor este pastrat in `/data/backups/solar-volume-backup-cutover`.
- Invertorul USB Exar XR21B1411 apare pe HP ca `/dev/growatt -> ttyUSB0`.
- Toate serviciile `influxdb`, `grafana`, `ntfy`, `api`, `caddy`, `collector` sunt pornite pe serverul nou.
- Colectarea a fost verificata: live scrie la 1s, history scrie la 60s.

## Reguli critice

1. Nu porni vechiul si noul server simultan cu IP-ul `192.168.1.199`.
2. Nu porni `collector` pe noul server pana cand invertorul USB nu este conectat si exista `/dev/growatt`.
3. Istoricul nu este in `/opt`; este in volume Docker:
   - `solar-monitor_influxdb-data`
   - `solar-monitor_influxdb-config`
   - `solar-monitor_grafana-data`
   - `solar-monitor_ntfy-cache`
   - `solar-monitor_caddy-data`
   - `solar-monitor_caddy-config`
4. In ziua mutarii, opreste stack-ul vechi inainte de copierea volumelor, ca InfluxDB sa fie consistent.
5. Daca se modifica API-ul in viitor, dupa `git pull` pe server se ruleaza:
   ```bash
   cd /opt/solar-monitor
   docker compose up -d --build api
   ```
   La cutover complet se poate porni tot stack-ul cu `docker compose up -d --build`.

## Preflight

Ruleaza de pe PC-ul local:

```powershell
ssh root@192.168.1.199 "hostname; cd /opt/solar-monitor && git status --short && docker compose ps"
ssh root@192.168.1.150 "hostname; cd /opt/solar-monitor && git status --short && docker ps -a && docker volume ls"
```

Pe serverul nou trebuie sa fie zero containere si zero volume Docker pentru proiect. Daca exista deja volume `solar-monitor_*`, opreste-te si verifica manual inainte de restore.

Pregateste imaginea helper pentru arhivarea volumelor:

```powershell
ssh root@192.168.1.199 "docker pull alpine:3.20"
ssh root@192.168.1.150 "docker pull alpine:3.20"
```

## 1. Actualizare cod pe ambele servere

```powershell
ssh root@192.168.1.199 "cd /opt/solar-monitor && git pull"
ssh root@192.168.1.150 "cd /opt/solar-monitor && git pull"
```

## 2. Oprire stack vechi

Aceasta este intreruperea reala. Din acest moment nu mai colectam date pana porneste noul server.

```powershell
ssh root@192.168.1.199 "cd /opt/solar-monitor && docker compose down"
ssh root@192.168.1.199 "docker ps --format '{{.Names}} {{.Status}}'"
```

## 3. Backup volume Docker pe serverul vechi

```powershell
ssh root@192.168.1.199 "mkdir -p /tmp/solar-volume-backup"
ssh root@192.168.1.199 'for v in influxdb-data influxdb-config grafana-data ntfy-cache caddy-data caddy-config; do docker run --rm -v solar-monitor_${v}:/from:ro -v /tmp/solar-volume-backup:/backup alpine:3.20 sh -c "cd /from && tar --numeric-owner -cpf /backup/${v}.tar ."; done'
ssh root@192.168.1.199 "cd /tmp/solar-volume-backup && sha256sum *.tar > SHA256SUMS && ls -lh"
```

## 4. Copiere arhive pe serverul nou

Varianta simpla si robusta, prin PC-ul local:

```powershell
New-Item -ItemType Directory -Force -Path .\solar-volume-backup
scp root@192.168.1.199:/tmp/solar-volume-backup/* .\solar-volume-backup\
ssh root@192.168.1.150 "mkdir -p /tmp/solar-volume-backup"
scp .\solar-volume-backup\* root@192.168.1.150:/tmp/solar-volume-backup/
ssh root@192.168.1.150 "cd /tmp/solar-volume-backup && sha256sum -c SHA256SUMS"
```

## 5. Restore volume Docker pe serverul nou

```powershell
ssh root@192.168.1.150 'for v in influxdb-data influxdb-config grafana-data ntfy-cache caddy-data caddy-config; do docker volume create solar-monitor_${v}; docker run --rm -v solar-monitor_${v}:/to -v /tmp/solar-volume-backup:/backup alpine:3.20 sh -c "cd /to && tar --numeric-owner -xpf /backup/${v}.tar"; done'
ssh root@192.168.1.150 "docker volume ls --format '{{.Name}}' | grep solar-monitor"
```

## 6. Mutare fizica si pastrare IP `.199`

1. Opreste serverul vechi.
2. Scoate USB-WiFi-ul din serverul vechi.
3. Muta serverul nou in beci.
4. Conecteaza USB-WiFi-ul vechi in serverul nou.
5. Conecteaza USB-ul invertorului la serverul nou.
6. Porneste serverul nou.

Pentru ca USB-WiFi-ul este acelasi, routerul ar trebui sa dea acelasi IP rezervat, `192.168.1.199`, daca rezervarea este dupa MAC-ul dongle-ului.

Verificari dupa boot:

```bash
ip -br addr
ping -c 3 192.168.1.1
lsusb
ls -l /dev/growatt
```

Daca Wi-Fi-ul nu se conecteaza automat:

```bash
nmcli device
nmcli dev wifi list
nmcli dev wifi connect "SSID" password "PAROLA"
```

Daca trebuie IP static manual:

```bash
nmcli connection show
nmcli connection modify "NUME_CONEXIUNE" ipv4.addresses 192.168.1.199/24 ipv4.gateway 192.168.1.1 ipv4.dns "192.168.1.1 1.1.1.1" ipv4.method manual
nmcli connection up "NUME_CONEXIUNE"
```

## 7. Pornire stack pe serverul nou

Conecteaza-te la `192.168.1.199` dupa ce noul server are IP-ul final:

```bash
ssh root@192.168.1.199
cd /opt/solar-monitor
git pull
docker compose up -d --build
docker compose ps
```

Verifica explicit ca invertorul este vazut:

```bash
ls -l /dev/growatt
docker logs --tail=120 solar-collector
docker logs --tail=80 solar-api
```

## 8. Validare aplicatie/API/Grafana

Local pe server:

```bash
curl -k https://localhost/solar/latest
curl http://localhost:3000/api/health
docker exec solar-influxdb influx ping
```

Din reteaua locala:

```powershell
curl.exe -k https://192.168.1.199/solar/latest
```

Verifica pe telefon:

- aplicatia Android arata date live;
- cardurile cu istoric se incarca;
- consumul casei apare in notificarea permanenta daca alarma locala este activa;
- Grafana merge local la `http://192.168.1.199:3000`;
- accesul remote `https://vyra.go.ro:31443` merge dupa ce routerele vad noul host pe IP-ul `.199`.

## 9. Daca ceva nu merge

### Nu apare `/dev/growatt`

```bash
lsusb
dmesg | tail -n 80
udevadm control --reload-rules
udevadm trigger
ls -l /dev/growatt
```

Adaptorul asteptat este Exar XR21B1411, VID:PID `04e2:1411`, serial `Q3370413461`.

### Collectorul nu porneste

```bash
cd /opt/solar-monitor
docker compose ps
docker logs --tail=200 solar-collector
cat .env | grep INVERTER_DEVICE
ls -l /dev/growatt
```

### API-ul nu raspunde

```bash
cd /opt/solar-monitor
docker compose up -d --build api
docker logs --tail=120 solar-api
```

### InfluxDB nu are date vechi

Opreste-te. Nu rescrie volumele fara verificare. Verifica daca restore-ul a mers:

```bash
docker volume ls --format '{{.Name}}' | grep solar-monitor
docker run --rm -v solar-monitor_influxdb-data:/data alpine:3.20 sh -c 'du -sh /data && find /data -maxdepth 2 -type f | head'
docker logs --tail=120 solar-influxdb
```

## 10. Dupa confirmare

Dupa ce noul server merge stabil:

```bash
cd /opt/solar-monitor
docker compose ps
docker logs --tail=80 solar-collector
docker logs --tail=80 solar-api
df -h /
```

Pastreaza serverul vechi oprit cateva zile, fara sa stergi nimic de pe el, ca fallback.
