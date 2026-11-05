#!/usr/bin/env bash
# CASA: aduce dispozitivele din Smart Life (sync), gaseste IP-ul prizelor in LAN (scan), reporneste serviciul.
# Folosit dupa ce ai imperecheat o priza/un senzor nou in Smart Life sau cand o priza si-a schimbat IP-ul.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
do_sync=1

case "${1:-}" in
  --scan-only) do_sync=0 ;;
  "") ;;
  *)
    echo "Folosire: scripts/casa-sync.sh [--scan-only]" >&2
    exit 2
    ;;
esac

cd "$repo_root"

if [[ $do_sync -eq 1 ]]; then
  echo "== sync: dispozitive si chei locale din Smart Life"
  docker exec solar-casa python -m casa sync
fi

# Anunturile UDP ale prizelor (broadcast 6666/6667/7000) nu ajung in reteaua bridge a containerului:
# scanarea ruleaza intr-un container temporar cu --network host, pe acelasi director de date.
echo "== scan: IP-ul si protocolul prizelor din LAN"
docker run --rm --network host -v "$repo_root/casa/data:/data" -e CASA_DATA_DIR=/data \
  --entrypoint python solar-monitor-casa -m casa scan

echo "== repornesc solar-casa (dispozitivele se citesc la pornire)"
docker compose restart casa
