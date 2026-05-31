#!/bin/bash
# Seteaza dashboard-ul solar-main ca pagina "Home" implicita a organizatiei.
# Dupa login, Grafana duce direct la acest dashboard.
AU="${GRAFANA_ADMIN_USER:-admin}"
AP="${GRAFANA_ADMIN_PASSWORD:?Seteaza GRAFANA_ADMIN_PASSWORD in env}"

curl -s -u "$AU:$AP" -H "Content-Type: application/json" \
  -X PUT http://localhost:3000/api/org/preferences \
  -d '{"homeDashboardUID":"solar-main","timezone":"browser"}'
echo
echo "== Home dashboard setat la solar-main =="
