# firewalld, reteaua Docker si sonda end-to-end

Note operationale de pe host (nu se aplica automat la `docker compose up`).

## Contextul: incidentul din 2026-08-19

Aplicatia de telefon a ramas pe *"astept date"* ~13 ore. Collector-ul era perfect
sanatos si scria in InfluxDB, deci nu a alertat nimic.

Lantul cauzal:

1. `20:53` — `apt upgrade -y`.
2. `20:55:58` — **needrestart** porneste `restart-dbus.service` ("Transient dbus restarter"),
   care repornește dbus si, in cascada, **firewalld**.
3. Docker isi inregistreaza bridge-urile in zona firewalld `docker` doar ca **runtime config**.
   La restartul firewalld acesta se pierde, iar Docker 26.1.5 si le re-adauga *numai* la semnalul
   D-Bus `Reloaded`, nu dupa un restart complet.
4. Bridge-urile raman fara zona -> cad pe politica implicita `reject with icmpx admin-prohibited`
   din `filter_FORWARD_POLICIES`.
5. Conexiunile deja stabilite au supravietuit (`ct state established accept`) — de-aia collector-ul
   a continuat sa scrie in InfluxDB — dar orice conexiune **noua** intre containere a fost respinsa:
   `solar-api` -> InfluxDB `Errno 113`, Caddy -> `api` `502 no route to host`.

**firewalld nu poate fi dezactivat**: e instalat de Virtualmin si fail2ban baneaza prin el —
`/etc/fail2ban/jail.d/virtualmin-firewalld.conf` seteaza `banaction = firewallcmd-rich-rules`
pentru toate cele 7 jail-uri (suprascrie default-ul Debian `nftables`).

## 1. Zona `docker` din firewalld (permanenta)

Zona `docker` are `target: ACCEPT`. Bridge-urile trebuie sa fie in ea **permanent**, ca sa
supravietuiasca restart / reload / reboot:

```bash
firewall-cmd --permanent --zone=docker --add-interface=br-solar
firewall-cmd --permanent --zone=docker --add-interface=docker0
firewall-cmd --permanent --zone=docker --add-interface=br-2cbda3f7161d   # stack-ul mp4-to-srt
firewall-cmd --reload
firewall-cmd --get-active-zones      # verificare
```

> **Deliberat fara `--add-source`.** Sursele pe subnet (`172.16.0.0/12` etc.) ar fi supravietuit
> redenumirii bridge-ului, dar `net.ipv4.conf.enp1s0.rp_filter = 2` (loose) inseamna ca un pachet
> venit din internet cu sursa falsificata din spatiul RFC1918 ar fi incadrat in zona `docker`
> (target ACCEPT) in loc de `public`. Zonele pe sursa au prioritate fata de cele pe interfata,
> deci nu s-ar putea corecta cu o rich rule in `public`. Solutia curata e numele fix de bridge
> de mai jos.

## 2. Numele bridge-ului e fixat in `docker-compose.yml`

```yaml
networks:
  default:
    driver_opts:
      com.docker.network.bridge.name: br-solar
```

Fara asta bridge-ul se numeste `br-<id-retea>`, iar id-ul se schimba la fiecare
`docker compose down` — regula firewalld de mai sus ar ramane legata de o interfata inexistenta
si incidentul s-ar repeta tacut. Max 15 caractere (IFNAMSIZ).

> Daca dupa un restart de firewalld `docker compose up` da
> `Failed to Setup IP tables: Unable to enable NAT rule: (dbus: connection closed by user)`,
> daemonul Docker are conexiunea D-Bus catre firewalld invechita: `systemctl restart docker`.

## 3. needrestart nu mai repornește serviciile critice

`/etc/needrestart/conf.d/90-solar-monitor-critical.conf` scoate `dbus`, `firewalld`, `docker` si
`containerd` din restartul automat la `apt upgrade`. Cand needrestart le semnaleaza ca invechite,
raspunsul corect e un **reboot planificat**, nu un restart partial.

## 4. Sonda end-to-end (`solar-watchdog`)

Alertele din `collector.py` acopera invertorul. Sonda acopera *lantul complet*, exact cum il vede
telefonul: `Caddy (9443, TLS intern) -> api:8000 -> InfluxDB -> collector`.

Esec = HTTP != 200, JSON invalid, sau `timestamp` mai vechi de `MAX_AGE_S` (implicit 180s).
Alerta ntfy la primul esec, apoi la fiecare al 4-lea consecutiv (timer la 15 min => o data pe ora),
plus un mesaj de revenire la normal.

Instalare:

```bash
install -m 0755 deploy/solar-watchdog.sh /opt/solar-monitor/deploy/solar-watchdog.sh
install -m 0644 deploy/solar-watchdog.service /etc/systemd/system/
install -m 0644 deploy/solar-watchdog.timer   /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now solar-watchdog.timer
```

Operare:

```bash
systemctl list-timers solar-watchdog.timer      # cand ruleaza urmatoarea data
systemctl start solar-watchdog.service          # rulare manuala
journalctl -u solar-watchdog.service -n 20      # rezultatele
cat /var/lib/solar-watchdog/consecutive_failures
```

Test fara sa trimiti alerte reale (stare separata, ntfy catre un port mort):

```bash
STATE_DIR=/tmp/wd NTFY_BASE=http://127.0.0.1:9 PROBE_PORT=9999 deploy/solar-watchdog.sh  # HTTP fail
STATE_DIR=/tmp/wd NTFY_BASE=http://127.0.0.1:9 MAX_AGE_S=0     deploy/solar-watchdog.sh  # date vechi
```

## 5. Descoperirea prizelor Tuya (CASA, 2026-11-05)

Aprobat de user: zona `public` lasa sa intre anunturile UDP ale prizelor Tuya, **doar din LAN**:

```bash
for p in 6666 6667 7000; do
  r="rule family=\"ipv4\" source address=\"192.168.1.0/24\" port port=\"$p\" protocol=\"udp\" accept"
  firewall-cmd --zone=public --add-rich-rule="$r" && firewall-cmd --permanent --zone=public --add-rich-rule="$r"
done
```

Adaugate runtime + permanent, fara `--reload` (vezi mai sus de ce un restart de firewalld strica Docker). Pe porturile
astea asculta ceva doar cat ruleaza `scripts/casa-sync.sh` (~20 s, container temporar cu `--network host`); comenzile spre
prize sunt TCP 6668 iesite din server si nu au nevoie de nicio regula. Verificare: `firewall-cmd --zone=public --list-rich-rules`.

## Diagnostic rapid daca reapare "astept date"

```bash
firewall-cmd --get-active-zones                    # br-solar TREBUIE sa fie in zona docker
docker compose ps
docker exec solar-caddy wget -qO- http://api:8000/latest
docker compose logs --tail=50 api caddy
```

`Errno 113` / `no route to host` intre containere = problema de zona firewalld, nu de invertor.
