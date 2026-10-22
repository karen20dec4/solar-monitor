#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
solar-bms - citeste bateria prin BMS-ul JK (Bluetooth LE) si scrie in InfluxDB.

STRICT READ-ONLY, ca si collector-ul invertorului. Singurele lucruri trimise
catre BMS sunt cererile de DATE 0x96 (celule) si 0x97 (info dispozitiv). Setarile
BMS-ului (praguri, balansare, MOSFET-uri, parola) au alte comenzi, pe care acest
cod nu le construieste si nu le poate trimite: `request_frame()` refuza orice
cod in afara listei `ALLOWED_COMMANDS`. Nu adauga comenzi de scriere aici.

Ruleaza pe HOST (nu in container: are nevoie de adaptorul Bluetooth USB si de
BlueZ), ca serviciu systemd: deploy/solar-bms.service.

Protocol: JK02_32S (BMS cu firmware 11.x, ex. JK_BD6A20S12P). Cadrele de 300 de
octeti vin pe FFE1 in bucati de 20; offseturile sunt verificate pe datele reale
(vezi COPILOT_CONTEXT.md). Referinta: syssi/esphome-jk-bms.
"""
import argparse
import asyncio
import json
import logging
import os
import signal
import struct
import sys
import time
import urllib.error
import urllib.request
from collections import deque

# ---------------------------------------------------------------- protocol --
NOTIFY_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
HEADER = bytes([0x55, 0xAA, 0xEB, 0x90])
FRAME_LEN = 300
TYPE_CELL_INFO = 0x02
TYPE_DEVICE_INFO = 0x03

CMD_CELL_INFO = 0x96
CMD_DEVICE_INFO = 0x97
ALLOWED_COMMANDS = frozenset({CMD_CELL_INFO, CMD_DEVICE_INFO})


def request_frame(cmd: int) -> bytes:
    """Cadru de 20 de octeti pentru o cerere de DATE. Refuza orice alta comanda."""
    if cmd not in ALLOWED_COMMANDS:
        raise ValueError(f"comanda BMS 0x{cmd:02x} nu e permisa (doar citire)")
    f = bytearray(20)
    f[0:4] = bytes([0xAA, 0x55, 0x90, 0xEB])
    f[4] = cmd
    f[19] = sum(f[:19]) & 0xFF
    return bytes(f)


def frame_ok(frame: bytes) -> bool:
    return (
        len(frame) == FRAME_LEN
        and frame[:4] == HEADER
        and (sum(frame[: FRAME_LEN - 1]) & 0xFF) == frame[FRAME_LEN - 1]
    )


class FrameAssembler:
    """Lipeste notificarile de 20 de octeti in cadre de 300."""

    def __init__(self):
        self._buf = bytearray()

    def feed(self, data: bytes):
        """Intoarce un cadru complet (bytes) sau None."""
        if data[:4] == HEADER:
            self._buf = bytearray()
        self._buf += data
        if len(self._buf) >= FRAME_LEN:
            frame = bytes(self._buf[:FRAME_LEN])
            self._buf = bytearray()
            return frame
        return None


def decode_cell_info(f: bytes) -> dict:
    """Cadrul de tip 2 -> campuri (unitati SI). Ridica ValueError la date absurde."""
    if not frame_ok(f) or f[4] != TYPE_CELL_INFO:
        raise ValueError("nu e un cadru valid de tip 2")
    u16 = lambda o: struct.unpack_from("<H", f, o)[0]
    s16 = lambda o: struct.unpack_from("<h", f, o)[0]
    u32 = lambda o: struct.unpack_from("<I", f, o)[0]
    s32 = lambda o: struct.unpack_from("<i", f, o)[0]

    mask = u32(70)
    cells = [u16(6 + 2 * i) / 1000.0 for i in range(32) if mask & (1 << i)]
    if not cells or any(not 0.5 < v < 5.0 for v in cells):
        raise ValueError(f"tensiuni celule absurde: {cells}")
    voltage = u32(150) / 1000.0
    if not 10.0 < voltage < 100.0:
        raise ValueError(f"tensiune pachet absurda: {voltage}")

    out = {}
    for n, v in enumerate(cells, start=1):
        out[f"cell_{n:02d}"] = v
    out["cell_min"] = min(cells)
    out["cell_max"] = max(cells)
    out["cell_avg"] = sum(cells) / len(cells)
    out["cell_delta"] = max(cells) - min(cells)
    out["cell_count"] = float(len(cells))
    out["voltage"] = voltage
    # Semnul JK e deja conventia colectorului: pozitiv = incarca, negativ = descarca
    # (verificat 2026-10-22: -47,7 A la 2,67 kW descarcare pe invertor).
    out["current"] = s32(158) / 1000.0
    out["power"] = out["current"] * voltage
    out["temp_mos"] = s16(144) / 10.0
    out["temp_1"] = s16(162) / 10.0
    out["temp_2"] = s16(164) / 10.0
    out["alarm_bits"] = float(u16(166))
    out["balancing"] = float(f[172])
    out["soc"] = float(f[173])
    out["capacity_remaining_ah"] = u32(174) / 1000.0
    out["capacity_nominal_ah"] = u32(178) / 1000.0
    out["cycles"] = float(u32(182))
    out["cycle_capacity_ah"] = u32(186) / 1000.0
    out["soh"] = float(f[190])
    out["charge_mosfet"] = float(f[198])
    out["discharge_mosfet"] = float(f[199])
    return out


def decode_device_info(f: bytes) -> dict:
    """Doar campurile nesensibile (fara parole) - pentru jurnal la conectare."""
    if not frame_ok(f) or f[4] != TYPE_DEVICE_INFO:
        raise ValueError("nu e un cadru valid de tip 3")

    def text(o, n):
        return f[o : o + n].split(b"\0")[0].decode("ascii", "replace")

    return {"model": text(6, 16), "hw": text(22, 8), "sw": text(30, 8)}


# ------------------------------------------------------------------ influx --
MEASUREMENT = "bms"
DEVICE_TAG = "jk_bms"

# La mediere pe minut: implicit media; campurile discrete isi pastreaza ultima
# valoare, iar MOSFET-urile minimul (o deconectare de cateva secunde trebuie sa
# ramana vizibila in istoric) si alarmele maximul.
LAST_FIELDS = {"cell_count", "soc", "soh", "cycles", "cycle_capacity_ah",
               "capacity_remaining_ah", "capacity_nominal_ah", "balancing"}
MIN_FIELDS = {"charge_mosfet", "discharge_mosfet"}
MAX_FIELDS = {"alarm_bits"}


class Averager:
    def __init__(self):
        self._rows = []

    def add(self, fields: dict):
        self._rows.append(fields)

    def __len__(self):
        return len(self._rows)

    def pop(self) -> dict:
        rows, self._rows = self._rows, []
        out = {}
        for key in rows[-1]:
            vals = [r[key] for r in rows if key in r]
            if key in LAST_FIELDS:
                out[key] = vals[-1]
            elif key in MIN_FIELDS:
                out[key] = min(vals)
            elif key in MAX_FIELDS:
                out[key] = max(vals)
            else:
                out[key] = sum(vals) / len(vals)
        return out


def line_protocol(fields: dict, ts_ns: int) -> str:
    # Totul float: un camp creat o data ca float nu accepta apoi intregi (422).
    body = ",".join(f"{k}={float(v)!r}" for k, v in sorted(fields.items()))
    return f"{MEASUREMENT},device={DEVICE_TAG} {body} {ts_ns}"


class InfluxWriter:
    """Scriere HTTP cu coada limitata; o cadere InfluxDB nu opreste citirea BMS."""

    def __init__(self, url, org, token, buckets, max_lines=(900, 1500)):
        self.url, self.org, self.token = url.rstrip("/"), org, token
        self.queues = {
            buckets["live"]: deque(maxlen=max_lines[0]),
            buckets["history"]: deque(maxlen=max_lines[1]),
        }

    def push(self, bucket: str, line: str):
        self.queues[bucket].append(line)

    def flush(self):
        for bucket, queue in self.queues.items():
            while queue:
                batch = [queue[i] for i in range(min(len(queue), 500))]
                try:
                    self._post(bucket, "\n".join(batch))
                except urllib.error.HTTPError as exc:
                    if exc.code in (400, 422):  # respinse definitiv: nu le mai reincerca
                        logging.error("InfluxDB a respins %d linii (%s)", len(batch), exc.code)
                    else:
                        logging.warning("InfluxDB %s, reincerc mai tarziu", exc.code)
                        break
                except (urllib.error.URLError, OSError) as exc:
                    logging.warning("InfluxDB indisponibil (%s), reincerc mai tarziu", exc)
                    break
                for _ in batch:
                    queue.popleft()

    def _post(self, bucket, payload):
        url = f"{self.url}/api/v2/write?org={self.org}&bucket={bucket}&precision=ns"
        req = urllib.request.Request(
            url, data=payload.encode(), method="POST",
            headers={"Authorization": f"Token {self.token}"})
        with urllib.request.urlopen(req, timeout=5):
            pass


# ------------------------------------------------------------------ config --
def load_env(path):
    env = {}
    try:
        with open(path, encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    env[key.strip()] = value.strip().strip('"').strip("'")
    except OSError:
        pass
    return env


def load_config():
    cfg = load_env(os.environ.get("ENV_FILE", "/opt/solar-monitor/.env"))
    cfg.update(os.environ)
    return cfg


# -------------------------------------------------------------------- main --
async def run_session(cfg, device, on_fields):
    """O conexiune BLE: trimite cererile de date si preda fiecare cadru decodat."""
    from bleak import BleakClient

    lost = asyncio.Event()
    assembler = FrameAssembler()
    state = {"last_frame": time.monotonic()}

    def on_notify(_, data):
        frame = assembler.feed(bytes(data))
        if frame is None or not frame_ok(frame):
            return
        if frame[4] == TYPE_CELL_INFO:
            try:
                fields = decode_cell_info(frame)
            except ValueError as exc:
                logging.warning("cadru ignorat: %s", exc)
                return
            state["last_frame"] = time.monotonic()
            on_fields(fields)
        elif frame[4] == TYPE_DEVICE_INFO:
            logging.info("BMS: %s", decode_device_info(frame))

    async with BleakClient(device, disconnected_callback=lambda _: lost.set(),
                           timeout=30) as client:
        logging.info("conectat la BMS %s", device.address)
        await client.start_notify(NOTIFY_UUID, on_notify)
        await client.write_gatt_char(NOTIFY_UUID, request_frame(CMD_DEVICE_INFO), response=False)
        await asyncio.sleep(1)
        last_request = time.monotonic()
        await client.write_gatt_char(NOTIFY_UUID, request_frame(CMD_CELL_INFO), response=False)
        while not lost.is_set():
            await asyncio.sleep(1)
            now = time.monotonic()
            silence = now - state["last_frame"]
            if silence > 45:
                raise TimeoutError(f"niciun cadru de {silence:.0f} s")
            if silence > 5 and now - last_request > 5:
                last_request = now
                await client.write_gatt_char(
                    NOTIFY_UUID, request_frame(CMD_CELL_INFO), response=False)
        raise ConnectionError("BMS a inchis conexiunea")


async def main_loop(cfg, once):
    from bleak import BleakScanner

    address = cfg.get("BMS_ADDRESS", "").strip()
    if not address:
        sys.exit("lipseste BMS_ADDRESS (adresa Bluetooth a BMS-ului) in .env")
    live_every = float(cfg.get("BMS_LIVE_INTERVAL_S", 1))
    history_every = float(cfg.get("BMS_HISTORY_INTERVAL_S", cfg.get("HISTORY_INTERVAL", 60)))
    buckets = {"live": cfg.get("INFLUXDB_BUCKET_LIVE", "live"),
               "history": cfg.get("INFLUXDB_BUCKET_HISTORY", "history")}
    writer = None
    if not once:
        writer = InfluxWriter(cfg.get("BMS_INFLUX_URL", "http://127.0.0.1:8086"),
                              cfg.get("INFLUXDB_ORG", "casa"), cfg["INFLUXDB_TOKEN"], buckets)
    averager = Averager()
    clock = {"live": 0.0, "history": time.monotonic()}
    done = asyncio.Event()
    health = {"failures": 0}

    def on_fields(fields):
        health["failures"] = 0  # a venit un cadru bun: reconectarea a reusit
        if once:
            print(json.dumps(fields, indent=1, sort_keys=True))
            done.set()
            return
        now = time.monotonic()
        ts_ns = time.time_ns()
        averager.add(fields)
        if now - clock["live"] >= live_every:
            clock["live"] = now
            writer.push(buckets["live"], line_protocol(fields, ts_ns))
        if now - clock["history"] >= history_every:
            clock["history"] = now
            writer.push(buckets["history"], line_protocol(averager.pop(), ts_ns))

    while not done.is_set():
        try:
            device = await BleakScanner.find_device_by_address(address, timeout=20)
            if device is None:
                raise ConnectionError(f"BMS {address} nu a fost gasit (telefonul conectat la el?)")
            session = asyncio.create_task(run_session(cfg, device, on_fields))
            finished = asyncio.create_task(done.wait())
            flusher = asyncio.create_task(_flush_loop(writer)) if writer else None
            await asyncio.wait({session, finished}, return_when=asyncio.FIRST_COMPLETED)
            if flusher:
                flusher.cancel()
            if finished.done():
                session.cancel()
                break
            session.result()  # ridica motivul intreruperii
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - orice eroare BLE => reconectare
            health["failures"] += 1
            wait = min(5 * health["failures"], 60)
            logging.warning("BMS: %s; reincerc in %d s", exc, wait)
            if writer:
                await asyncio.to_thread(writer.flush)
            await asyncio.sleep(wait)


async def _flush_loop(writer):
    while True:
        await asyncio.sleep(1)
        await asyncio.to_thread(writer.flush)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--once", action="store_true",
                        help="citeste un cadru, il afiseaza (JSON) si iese; nu scrie in InfluxDB")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    cfg = load_config()
    loop = asyncio.new_event_loop()
    task = loop.create_task(main_loop(cfg, args.once))
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, task.cancel)
    try:
        loop.run_until_complete(task)
    except asyncio.CancelledError:
        pass


if __name__ == "__main__":
    main()
