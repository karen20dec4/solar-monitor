#!/bin/bash
# Ruleaza automat la prima initializare a InfluxDB (dupa setup).
# Creeaza bucket-ul `live` (date brute 1s) cu retentie scurta.
# Bucket-ul `history` este deja creat de DOCKER_INFLUXDB_INIT_BUCKET.
set -e

echo "[init] Creez bucket-ul live='${INFLUXDB_BUCKET_LIVE}' retentie='${INFLUXDB_RETENTION_LIVE}'..."

# NOTA: --retention accepta DOAR durate Go (ex: 48h), NU "2d".
influx bucket create \
  --host http://localhost:8086 \
  --token "${DOCKER_INFLUXDB_INIT_ADMIN_TOKEN}" \
  --org "${DOCKER_INFLUXDB_INIT_ORG}" \
  --name "${INFLUXDB_BUCKET_LIVE}" \
  --retention "${INFLUXDB_RETENTION_LIVE}"

echo "[init] Gata."
