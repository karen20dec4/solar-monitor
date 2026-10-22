#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Captura de ecran a unui dashboard Grafana, fara plugin de randare (pluginul "Image Renderer" nu e
instalat). Porneste Chromium headless, se autentifica cu utilizatorul din .env printr-un header
HTTP (parola nu apare in linia de comanda) si salveaza PNG-ul.

Utilizare:
  python3 -I scripts/grafana-screenshot.py <url> <iesire.png> <latime> <inaltime> [secunde_asteptare]
Exemplu (randul BMS e pe la y=1000..2400 la 1600 px latime):
  python3 -I scripts/grafana-screenshot.py \
    'http://127.0.0.1:3000/d/solar-main/x?orgId=1&kiosk&from=now-30m&to=now' /tmp/dash.png 1600 3100 25
Apoi decupezi cu PIL/ImageMagick si te uiti la imagine. Dupa ce editezi grafana/dashboards/solar.json,
asteapta ~30 s (updateIntervalSeconds) ca Grafana sa reincarce fisierul.
"""
import base64, json, shutil, subprocess, sys, tempfile, time, urllib.request
import websocket

url, out, width, height = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
env = {}
for line in open('/opt/solar-monitor/.env', encoding='utf-8'):
    line = line.strip()
    if line and not line.startswith('#') and '=' in line:
        k, v = line.split('=', 1); env[k] = v
auth = base64.b64encode(f"{env['GRAFANA_ADMIN_USER']}:{env['GRAFANA_ADMIN_PASSWORD']}".encode()).decode()

profile = tempfile.mkdtemp(prefix='grafana-shot-')
proc = subprocess.Popen(['chromium', '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
                         '--remote-debugging-port=9333', '--remote-allow-origins=*', f'--user-data-dir={profile}',
                         f'--window-size={width},{height}', 'about:blank'],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(40):
        try:
            tabs = json.load(urllib.request.urlopen('http://127.0.0.1:9333/json', timeout=2))
            page = next(t for t in tabs if t['type'] == 'page')
            break
        except Exception:
            time.sleep(0.5)
    ws = websocket.create_connection(page['webSocketDebuggerUrl'], timeout=60)
    mid = [0]
    def send(method, params=None):
        mid[0] += 1
        ws.send(json.dumps({'id': mid[0], 'method': method, 'params': params or {}}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get('id') == mid[0]:
                return msg
    send('Network.enable')
    send('Network.setExtraHTTPHeaders', {'headers': {'Authorization': 'Basic ' + auth}})
    send('Emulation.setDeviceMetricsOverride', {'width': width, 'height': height, 'deviceScaleFactor': 1, 'mobile': False})
    send('Page.navigate', {'url': url})
    time.sleep(float(sys.argv[5]) if len(sys.argv) > 5 else 20)
    shot = send('Page.captureScreenshot', {'format': 'png'})
    open(out, 'wb').write(base64.b64decode(shot['result']['data']))
    print('ok', out)
finally:
    proc.terminate()
    proc.wait(timeout=10)
    shutil.rmtree(profile, ignore_errors=True)
