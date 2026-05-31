#!/bin/bash
# Creeaza utilizatorul Grafana 'florin' cu rol Admin.
# Parola data ca argument; daca lipseste, foloseste una temporara.
# Utilizare: bash create-grafana-user.sh [PAROLA]
GRAFANA="http://localhost:3000"
AU="${GRAFANA_ADMIN_USER:-admin}"
AP="${GRAFANA_ADMIN_PASSWORD:?Seteaza GRAFANA_ADMIN_PASSWORD in env}"
LOGIN="florin"
NAME="Florin"
PASS="${1:?Utilizare: GRAFANA_ADMIN_PASSWORD=... bash create-grafana-user.sh PAROLA_USER}"

echo "== Creez utilizatorul '$LOGIN' =="
curl -s -u "$AU:$AP" -H "Content-Type: application/json" \
  -X POST "$GRAFANA/api/admin/users" \
  -d "{\"name\":\"$NAME\",\"login\":\"$LOGIN\",\"password\":\"$PASS\"}"
echo

echo "== Caut id-ul utilizatorului =="
USERID=$(curl -s -u "$AU:$AP" "$GRAFANA/api/users/lookup?loginOrEmail=$LOGIN" \
  | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
echo "id=$USERID"

echo "== Setez rol Admin in organizatia 1 =="
curl -s -u "$AU:$AP" -H "Content-Type: application/json" \
  -X PATCH "$GRAFANA/api/orgs/1/users/$USERID" -d '{"role":"Admin"}'
echo
echo "== GATA. Login: $LOGIN / $PASS (schimba parola dupa prima logare) =="
