import json
import logging
import os
import stat
import tempfile
import threading
import time
import traceback
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from casa.plugs import PlugError, PlugReading, decode_inching, decode_reading, encode_inching
from casa.devices import TASMOTA_DPS, TASMOTA_SCALES, DeviceInfo
from casa.tasmota import (TASMOTA_MAX_S, TasmotaDriver, decode_pulsetime, encode_pulsetime,
                          load_tasmota_password, save_tasmota_password)

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures", "tasmota")


def fixture(name):
    with open(os.path.join(FIXTURES, name + ".json"), encoding="utf-8") as f:
        return json.load(f)


class FakePlug:
    """Fetch fals: o priza Tasmota minima pornita din fixture-urile reale; tine jurnalul comenzilor.

    `overrides[cmnd]` inlocuieste raspunsul unei comenzi (cu un dict, sau o exceptie de ridicat).
    """

    def __init__(self, base="nr1"):
        self.on = fixture(base + "-power")["POWER"] == "ON"
        pt = fixture(base + "-pulsetime1")["PulseTime1"]
        self.set, self.remaining = pt["Set"], pt["Remaining"]
        self.mem1 = fixture(base + "-mem1")["Mem1"]
        self.mem2 = fixture(base + "-mem2")["Mem2"]
        self.status10 = fixture(base + "-status10")
        self.log = []
        self.overrides = {}

    def energy(self, **fields):
        self.status10["StatusSNS"]["ENERGY"].update(fields)

    def sent(self, prefix):
        return [c for c in self.log if c == prefix or c.startswith(prefix + " ")]

    def __call__(self, cmnd):
        self.log.append(cmnd)
        if cmnd in self.overrides:
            r = self.overrides[cmnd]
            if isinstance(r, Exception):
                raise r
            return r
        name, _, arg = cmnd.partition(" ")
        if name == "Power":
            if arg:
                self.on = arg == "On"
                self.remaining = self.set if self.on else 0
            return {"POWER": "ON" if self.on else "OFF"}
        if name == "Status" and arg == "10":
            return self.status10
        if name == "PulseTime1":
            if arg:
                self.set = int(arg)
                if self.on:
                    self.remaining = self.set
            return {"PulseTime1": {"Set": self.set, "Remaining": self.remaining}}
        if name in ("Mem1", "Mem2"):
            if arg:
                setattr(self, name.lower(), arg)
            return {name: getattr(self, name.lower())}
        return {"Command": "Unknown", "Input": cmnd.upper()}


def make(plug=None, **kw):
    plug = plug or FakePlug()
    drv = TasmotaDriver("192.0.2.7", fetch=plug, **kw)
    return drv, plug


def connected(plug=None, **kw):
    drv, plug = make(plug, **kw)
    drv.connect()
    plug.log.clear()
    return drv, plug


class PulseTimeCodecTests(unittest.TestCase):
    def test_pulsetime_codec(self):
        for secs, want in [(0, 0), (5, 50), (11, 110), (12, 112), (1800, 1900),
                           (64800, 64900), (99999, 64900)]:
            self.assertEqual(encode_pulsetime(secs), want, secs)
        for val, want in [(0, 0), (50, 5), (1, 1), (1900, 1800), (1899, 1799)]:
            self.assertEqual(decode_pulsetime(val), want, val)

    def test_codec_edges(self):
        self.assertEqual(TASMOTA_MAX_S, 64800)
        self.assertEqual(encode_pulsetime(-3), 0)
        self.assertEqual(decode_pulsetime(-3), 0)
        self.assertEqual(decode_pulsetime(111), 12)       # 11.1 s, rotunjit in sus
        self.assertEqual(decode_pulsetime(112), 12)
        self.assertEqual(decode_pulsetime(70000), TASMOTA_MAX_S)
        for secs in (1, 7, 11, 12, 13, 900, 43200, 64800):
            self.assertEqual(decode_pulsetime(encode_pulsetime(secs)), secs)


class StatusTests(unittest.TestCase):
    def test_status_maps_fixture_nr1(self):
        drv, plug = connected()
        self.assertEqual(drv.status(), {"1": False, "9": 0, "18": 0, "19": 0, "20": 2280})
        self.assertNotIn("44", drv.status())

    def test_status_on_with_load(self):
        plug = FakePlug()
        plug.on = True
        plug.energy(Power=1158.0, Voltage=228, Current=4.95)
        drv, plug = connected(plug)
        dps = drv.status()
        self.assertIs(dps["1"], True)
        self.assertEqual((dps["19"], dps["20"], dps["18"]), (11580, 2280, 4950))

    def test_status_decodes_to_watts_volts_amps(self):
        plug = FakePlug()
        plug.on = True
        plug.energy(Power=1158.0, Voltage=228, Current=4.95)
        drv, plug = connected(plug)
        dev = DeviceInfo(id="p", name="P", kind="plug", local_key=None, ip="192.0.2.7", version=None,
                         dps=dict(TASMOTA_DPS), scales=dict(TASMOTA_SCALES), driver="tasmota")
        r = decode_reading(dev, drv.status())
        self.assertIsInstance(r, PlugReading)
        self.assertIs(r.on, True)
        self.assertAlmostEqual(r.power, 1158.0)
        self.assertAlmostEqual(r.voltage, 228.0)
        self.assertAlmostEqual(r.current, 4.95)
        self.assertIsNone(r.inching)

    def test_one_reading_is_three_requests(self):
        drv, plug = connected()
        drv.status()
        self.assertEqual(plug.log, ["Power", "Status 10", "PulseTime1"])

    def test_connect_reads_mem_once_and_status_does_not_reread_it(self):
        drv, plug = make()
        drv.connect()
        self.assertEqual(plug.log, ["Mem1", "Mem2"])
        plug.log.clear()
        drv.status()
        drv.status()
        self.assertEqual(plug.sent("Mem1") + plug.sent("Mem2"), [])

    def test_status_without_connect_loads_mem_lazily(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        drv, plug = make(plug)
        self.assertEqual(drv.status()["44"], encode_inching(True, 1800))

    def test_status_reports_inching_from_mem(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        plug.set = 1900
        drv, plug = connected(plug)
        self.assertEqual(drv.status()["44"], encode_inching(True, 1800))
        plug.mem2 = "0"
        plug.set = 0
        drv.connect()
        self.assertEqual(drv.status()["44"], encode_inching(False, 1800))

    def test_inching_decodes_through_decode_reading(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1500", "0"
        drv, plug = connected(plug)
        dev = DeviceInfo(id="p", name="P", kind="plug", local_key=None, ip="192.0.2.7", version=None,
                         dps=dict(TASMOTA_DPS), scales=dict(TASMOTA_SCALES), driver="tasmota")
        self.assertEqual(decode_reading(dev, drv.status()).inching, (False, 1500))

    def test_garbage_mem_reports_no_inching(self):
        for bad in ("", "abc", "-5", "0", "99999999", "18.5", " "):
            with self.subTest(mem1=bad):
                plug = FakePlug("nr4")          # PulseTime1 Set 1900, priza oprita
                plug.mem1, plug.mem2 = bad, "1"
                drv, plug = connected(plug)
                self.assertNotIn("44", drv.status())
                self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])
        for bad in ("", "2", "x"):
            with self.subTest(mem2=bad):
                plug = FakePlug("nr4")
                plug.mem1, plug.mem2 = "1800", bad
                drv, plug = connected(plug)
                self.assertNotIn("44", drv.status())
                self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])

    def test_empty_mem_of_todays_plugs_changes_nothing(self):
        drv, plug = connected(FakePlug("nr4"))
        dps = drv.status()
        self.assertNotIn("44", dps)
        self.assertEqual(plug.set, 1900)
        self.assertEqual(plug.sent("PulseTime1"), ["PulseTime1"])        # doar citirea

    def test_self_heal_restores_base_when_off(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        plug.set = 3700                       # termen de siguranta ramas de la ultima pornire
        drv, plug = connected(plug)
        dps = drv.status()
        self.assertIn("PulseTime1 1900", plug.log)
        self.assertEqual(plug.set, 1900)
        self.assertIs(dps["1"], False)
        self.assertEqual(dps["9"], 0)
        plug.log.clear()
        drv.status()                          # acum e deja la baza: nu se mai scrie nimic
        self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])

    def test_self_heal_leaves_matching_base_alone(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        plug.set = 1900
        drv, plug = connected(plug)
        drv.status()
        self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])

    def test_self_heal_zero_when_inching_off(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "0"
        plug.set = 1900
        drv, plug = connected(plug)
        drv.status()
        self.assertIn("PulseTime1 0", plug.log)
        self.assertEqual(plug.set, 0)

    def test_no_self_heal_while_on(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        plug.on, plug.set, plug.remaining = True, 3700, 3600
        drv, plug = connected(plug)
        drv.status()
        self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])
        self.assertEqual(plug.set, 3700)

    def test_failed_self_heal_does_not_take_the_plug_offline(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        plug.set = 3700
        drv, plug = connected(plug)
        plug.overrides["PulseTime1 1900"] = {"Command": "Error"}
        with self.assertLogs("casa.tasmota", level="WARNING") as cm:
            dps = drv.status()
        self.assertIs(dps["1"], False)
        self.assertTrue(any("192.0.2.7" in line for line in cm.output))

    def test_countdown_reads_remaining(self):
        plug = FakePlug()
        plug.on, plug.set, plug.remaining = True, 3700, 3600
        drv, plug = connected(plug)
        self.assertEqual(drv.status()["9"], 3500)

    def test_countdown_zero_when_off_or_not_counting(self):
        plug = FakePlug()
        plug.on, plug.set, plug.remaining = True, 1900, 0
        drv, plug = connected(plug)
        self.assertEqual(drv.status()["9"], 0)

    def test_no_energy_block_gives_no_power_keys(self):
        plug = FakePlug()
        plug.status10 = {"StatusSNS": {"Time": "2026-11-10T12:32:35"}}
        drv, plug = connected(plug)
        dps = drv.status()
        for k in ("18", "19", "20"):
            self.assertNotIn(k, dps)
        self.assertIn("1", dps)

    def test_nonfinite_energy_value_is_skipped(self):
        plug = FakePlug()
        plug.energy(Power=float("nan"), Voltage=229, Current=True)
        drv, plug = connected(plug)
        dps = drv.status()
        self.assertEqual(dps["20"], 2290)
        self.assertNotIn("19", dps)
        self.assertNotIn("18", dps)

    def test_pulsetime_without_numbers_omits_countdown_and_heal(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        drv, plug = connected(plug)
        plug.overrides["PulseTime1"] = {"PulseTime1": "weird"}
        dps = drv.status()
        self.assertNotIn("9", dps)
        self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])

    def test_unknown_power_state_raises(self):
        drv, plug = connected()
        plug.overrides["Power"] = {"POWER": "MAYBE"}
        with self.assertRaises(PlugError):
            drv.status()

    def test_power1_key_is_accepted(self):
        drv, plug = connected()
        plug.overrides["Power"] = {"POWER1": "ON"}
        self.assertIs(drv.status()["1"], True)

    def test_close_forgets_mem_and_connect_rereads_it(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "600", "1"
        drv, plug = connected(plug)
        self.assertEqual(drv.status()["44"], encode_inching(True, 600))
        drv.close()
        plug.mem1 = "900"
        drv.connect()
        self.assertEqual(drv.status()["44"], encode_inching(True, 900))

    def test_connect_error_is_plugerror(self):
        drv, plug = make()
        plug.overrides["Mem1"] = PlugError("nu raspunde")
        with self.assertRaises(PlugError):
            drv.connect()


class SetDpTests(unittest.TestCase):
    def test_set_switch(self):
        drv, plug = connected()
        drv.set_dp(1, True)
        self.assertEqual(plug.log, ["Power On"])
        self.assertTrue(plug.on)
        plug.log.clear()
        drv.set_dp(1, False)
        self.assertEqual(plug.log, ["Power Off"])
        self.assertFalse(plug.on)

    def test_set_switch_wrong_state_raises(self):
        drv, plug = connected()
        plug.overrides["Power On"] = {"POWER": "OFF"}
        with self.assertRaises(PlugError):
            drv.set_dp(1, True)
        plug.overrides["Power Off"] = {"POWER": "ON"}
        with self.assertRaises(PlugError):
            drv.set_dp(1, False)

    def test_set_switch_rejects_non_bool(self):
        drv, plug = connected()
        with self.assertRaises(PlugError):
            drv.set_dp(1, "On")
        self.assertEqual(plug.log, [])

    def test_set_countdown(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "1"
        drv, plug = connected(plug)
        drv.set_dp(9, 3600)
        self.assertEqual(plug.log, ["PulseTime1 3700"])
        plug.log.clear()
        drv.set_dp(9, 0)
        self.assertEqual(plug.log, ["PulseTime1 1900"])
        plug.log.clear()
        drv.set_dp(9, 90000)
        self.assertEqual(plug.log, ["PulseTime1 64900"])

    def test_set_countdown_zero_without_valid_mem_clears(self):
        drv, plug = connected()               # Mem gol
        plug.set = 3700
        drv.set_dp(9, 0)
        self.assertEqual(plug.log, ["PulseTime1 0"])
        self.assertEqual(plug.set, 0)

    def test_set_countdown_zero_with_inching_off_clears(self):
        plug = FakePlug()
        plug.mem1, plug.mem2 = "1800", "0"
        drv, plug = connected(plug)
        drv.set_dp(9, 0)
        self.assertEqual(plug.log, ["PulseTime1 0"])

    def test_set_countdown_checks_what_the_plug_took(self):
        drv, plug = connected()
        plug.overrides["PulseTime1 3700"] = {"PulseTime1": {"Set": 0, "Remaining": 0}}
        with self.assertRaises(PlugError):
            drv.set_dp(9, 3600)
        plug.overrides["PulseTime1 3700"] = {"POWER": "ON"}
        with self.assertRaises(PlugError):
            drv.set_dp(9, 3600)

    def test_set_countdown_rejects_bad_values(self):
        drv, plug = connected()
        for bad in (-1, "60", None, True, 1.5):
            with self.subTest(value=bad):
                with self.assertRaises(PlugError):
                    drv.set_dp(9, bad)
        self.assertEqual(plug.log, [])

    def test_set_inching_writes_mem_and_base_when_off(self):
        drv, plug = connected()
        drv.set_dp(44, encode_inching(True, 1800))
        self.assertIn("Mem1 1800", plug.log)
        self.assertIn("Mem2 1", plug.log)
        self.assertIn("PulseTime1 1900", plug.log)
        self.assertEqual(plug.set, 1900)
        plug.log.clear()
        self.assertEqual(drv.status()["44"], encode_inching(True, 1800))
        self.assertEqual([c for c in plug.log if c.startswith(("Mem", "PulseTime1 "))], [])

    def test_set_inching_while_on_leaves_pulsetime_alone(self):
        plug = FakePlug()
        plug.on, plug.set, plug.remaining = True, 3700, 3600
        drv, plug = connected(plug)
        drv.set_dp(44, encode_inching(True, 1800))
        self.assertIn("Mem1 1800", plug.log)
        self.assertIn("Mem2 1", plug.log)
        self.assertEqual([c for c in plug.log if c.startswith("PulseTime1 ")], [])
        self.assertEqual(plug.set, 3700)
        self.assertEqual(drv.status()["44"], encode_inching(True, 1800))

    def test_set_inching_off_keeps_duration_and_clears_base(self):
        plug = FakePlug()
        plug.mem1, plug.mem2, plug.set = "1800", "1", 1900
        drv, plug = connected(plug)
        drv.set_dp(44, encode_inching(False, 1500))
        self.assertEqual((plug.mem1, plug.mem2), ("1500", "0"))
        self.assertIn("PulseTime1 0", plug.log)
        self.assertEqual(drv.status()["44"], encode_inching(False, 1500))

    def test_set_inching_rejects_bad_values_without_writing(self):
        drv, plug = connected()
        for bad in ("!!!", "", None, 5, encode_inching(True, 0), encode_inching(True, TASMOTA_MAX_S + 1)):
            with self.subTest(value=bad):
                with self.assertRaises(PlugError):
                    drv.set_dp(44, bad)
        self.assertEqual(plug.log, [])

    def test_set_inching_failure_makes_driver_reread_mem(self):
        plug = FakePlug()
        plug.mem1, plug.mem2, plug.set = "600", "1", 700
        drv, plug = connected(plug)
        plug.overrides["Mem2 0"] = {"Command": "Error"}
        with self.assertRaises(PlugError):
            drv.set_dp(44, encode_inching(False, 1800))
        del plug.overrides["Mem2 0"]
        # Mem1 a fost scris, Mem2 nu: starea reala a prizei e cea care se raporta, nu o presupunere
        self.assertEqual((plug.mem1, plug.mem2), ("1800", "1"))
        self.assertEqual(drv.status()["44"], encode_inching(True, 1800))

    def test_unknown_dp_raises(self):
        drv, plug = connected()
        with self.assertRaises(PlugError):
            drv.set_dp(5, 1)
        self.assertEqual(plug.log, [])

    def test_command_error_raises(self):
        drv, plug = connected()
        for resp in ({"Command": "Unknown", "Input": "BOGUS"}, {"Command": "Error"}):
            with self.subTest(resp=resp):
                plug.overrides["Power"] = resp
                with self.assertRaises(PlugError):
                    drv.status()
                plug.overrides["Power On"] = resp
                with self.assertRaises(PlugError):
                    drv.set_dp(1, True)

    def test_non_dict_response_raises(self):
        drv, plug = connected()
        plug.overrides["Power"] = ["POWER", "ON"]
        with self.assertRaises(PlugError):
            drv.status()

    def test_foreign_exception_from_fetch_becomes_plugerror_without_its_text(self):
        drv, plug = connected()
        plug.overrides["Power"] = RuntimeError("http://x/cm?password=S3cret!")
        with self.assertRaises(PlugError) as cm:
            drv.status()
        self.assertNotIn("S3cret", str(cm.exception))
        self.assertIsNone(cm.exception.__cause__)
        self.assertIsNone(cm.exception.__context__)


class _Handler(BaseHTTPRequestHandler):
    """Handler configurat de test prin atributele clasei serverului: `server.script(handler)`."""

    def do_GET(self):  # noqa: N802
        self.server.paths.append(self.path)
        self.server.script(self)

    def log_message(self, *args):
        pass


class _Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass


def _reply(h, code, body, ctype="application/json", headers=()):
    data = body.encode("utf-8")
    h.send_response(code)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    for k, v in headers:
        h.send_header(k, v)
    h.end_headers()
    h.wfile.write(data)


class _Capture(logging.Handler):
    def __init__(self):
        super().__init__(logging.DEBUG)
        self.lines = []

    def emit(self, record):
        text = record.getMessage()
        if record.exc_info:
            text += "".join(traceback.format_exception(*record.exc_info))
        self.lines.append(text)


class HttpTransportTests(unittest.TestCase):
    SECRET = "S3cret!"

    def serve(self, script):
        srv = _Server(("127.0.0.1", 0), _Handler)
        srv.script = script
        srv.paths = []
        t = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        t.start()
        self.addCleanup(self._close, srv, t)
        return srv, f"127.0.0.1:{srv.server_address[1]}"

    @staticmethod
    def _close(srv, t):
        srv.shutdown()
        srv.server_close()
        t.join(2)

    def assert_clean(self, err, logs):
        blob = [str(err), repr(err), "".join(traceback.format_exception(err))]
        for chained in (err.__cause__, err.__context__):
            if chained is not None:
                blob += [str(chained), repr(chained)]
        blob += logs.lines
        for text in blob:
            self.assertNotIn("S3cret", text)

    def expect_error(self, script, timeout=3.0, cmnd="Power", password=SECRET):
        _, addr = self.serve(script)
        drv = TasmotaDriver(addr, password=password, timeout=timeout)
        cap = _Capture()
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(cap)
        root.setLevel(logging.DEBUG)
        try:
            with self.assertRaises(PlugError) as cm:
                drv._cmd(cmnd)
        finally:
            root.removeHandler(cap)
            root.setLevel(old_level)
        self.assert_clean(cm.exception, cap)
        return cm.exception

    def test_success_builds_encoded_url_with_credentials(self):
        srv, addr = self.serve(lambda h: _reply(h, 200, '{"POWER":"ON"}'))
        drv = TasmotaDriver(addr, password=self.SECRET, timeout=3)
        self.assertEqual(drv._cmd("Power On"), {"POWER": "ON"})
        self.assertEqual(srv.paths, ["/cm?cmnd=Power%20On&user=admin&password=S3cret%21"])

    def test_no_credentials_without_password(self):
        srv, addr = self.serve(lambda h: _reply(h, 200, '{"POWER":"OFF"}'))
        drv = TasmotaDriver(addr, timeout=3)
        drv._cmd("Status 10")
        self.assertEqual(srv.paths, ["/cm?cmnd=Status%2010"])

    def test_password_with_special_characters_is_encoded(self):
        srv, addr = self.serve(lambda h: _reply(h, 200, '{"POWER":"OFF"}'))
        TasmotaDriver(addr, password="a b&c=d#e+f/", timeout=3)._cmd("Power")
        self.assertEqual(srv.paths, ["/cm?cmnd=Power&user=admin&password=a%20b%26c%3Dd%23e%2Bf%2F"])

    def test_full_status_over_http(self):
        def script(h):
            q = h.path.split("cmnd=")[1].split("&")[0].replace("%20", " ")
            name = {"Power": "nr1-power", "Status 10": "nr1-status10", "PulseTime1": "nr1-pulsetime1",
                    "Mem1": "nr1-mem1", "Mem2": "nr1-mem2"}[q]
            _reply(h, 200, json.dumps(fixture(name)))
        _, addr = self.serve(script)
        drv = TasmotaDriver(addr, timeout=3)
        drv.connect()
        self.assertEqual(drv.status(), {"1": False, "9": 0, "18": 0, "19": 0, "20": 2280})

    def test_http_401_says_password_rejected_without_password(self):
        err = self.expect_error(lambda h: _reply(
            h, 401, '{"WARNING":"Need user=<username>&password=<password>"}'))
        self.assertIn("parola Tasmota respinsa", str(err))
        self.assertIn("127.0.0.1", str(err))

    def test_http_403_is_password_rejected_too(self):
        err = self.expect_error(lambda h: _reply(h, 403, "{}"))
        self.assertIn("parola Tasmota respinsa", str(err))

    def test_warning_in_a_200_body_is_password_rejected(self):
        err = self.expect_error(lambda h: _reply(
            h, 200, '{"WARNING":"Need user=<username>&password=<password>"}'))
        self.assertIn("parola Tasmota respinsa", str(err))

    def test_timeout(self):
        def script(h):
            time.sleep(1.0)
        started = time.monotonic()
        err = self.expect_error(script, timeout=0.3)
        self.assertLess(time.monotonic() - started, 0.9)
        self.assertIn("timeout", str(err))

    def test_html_instead_of_json(self):
        err = self.expect_error(lambda h: _reply(
            h, 200, f"<html><body>Login failed for {self.SECRET}</body></html>", "text/html"))
        self.assertIn("127.0.0.1", str(err))

    def test_http_500(self):
        err = self.expect_error(lambda h: _reply(h, 500, f"boom {self.SECRET}"))
        self.assertIn("500", str(err))

    def test_json_that_is_not_an_object(self):
        self.expect_error(lambda h: _reply(h, 200, "[1, 2]"))

    def test_oversized_response_is_refused(self):
        self.expect_error(lambda h: _reply(h, 200, '{"x":"' + "a" * 200000 + '"}'))

    def test_redirect_is_not_followed(self):
        def script(h):
            _reply(h, 302, "", headers=[("Location", "/elsewhere")])
        srv, addr = self.serve(script)
        drv = TasmotaDriver(addr, password=self.SECRET, timeout=3)
        with self.assertRaises(PlugError) as cm:
            drv._cmd("Power")
        self.assertEqual(len(srv.paths), 1)
        self.assertNotIn("S3cret", str(cm.exception))

    def test_connection_refused(self):
        srv, addr = self.serve(lambda h: None)
        srv.shutdown()
        srv.server_close()
        drv = TasmotaDriver(addr, password=self.SECRET, timeout=1)
        with self.assertRaises(PlugError) as cm:
            drv._cmd("Power")
        self.assert_clean(cm.exception, _Capture())
        self.assertIn("127.0.0.1", str(cm.exception))

    def test_errors_never_contain_password(self):
        # cele trei cazuri din brief, prin driver (status), cu captura de log
        cases = [
            (lambda h: _reply(h, 401, "{}"), 3.0),
            (lambda h: time.sleep(1.0), 0.3),
            (lambda h: _reply(h, 200, f"<html>{self.SECRET}</html>", "text/html"), 3.0),
        ]
        for script, timeout in cases:
            _, addr = self.serve(script)
            drv = TasmotaDriver(addr, password=self.SECRET, timeout=timeout)
            cap = _Capture()
            root = logging.getLogger()
            old_level = root.level
            root.addHandler(cap)
            root.setLevel(logging.DEBUG)
            try:
                with self.assertRaises(PlugError) as cm:
                    drv.connect()
                with self.assertRaises(PlugError) as cm2:
                    drv.status()
                with self.assertRaises(PlugError) as cm3:
                    drv.set_dp(1, True)
            finally:
                root.removeHandler(cap)
                root.setLevel(old_level)
            for e in (cm.exception, cm2.exception, cm3.exception):
                self.assert_clean(e, cap)

    def test_401_message_reaches_the_offline_reading(self):
        from casa.plugs import PlugWorker
        _, addr = self.serve(lambda h: _reply(h, 401, "{}"))
        got = []
        dev = DeviceInfo(id="p", name="P", kind="plug", local_key=None, ip=addr, version=None,
                         dps=dict(TASMOTA_DPS), scales=dict(TASMOTA_SCALES), driver="tasmota")
        ev = threading.Event()

        def on_reading(pid, r):
            got.append(r)
            ev.set()
        w = PlugWorker(dev, TasmotaDriver(addr, password=self.SECRET, timeout=1), 0.05, on_reading,
                       backoff_base=0.05)
        w.start()
        try:
            self.assertTrue(ev.wait(3))
        finally:
            w.stop()
            w.join(3)
        self.assertFalse(got[0].online)
        self.assertIn("parola Tasmota respinsa", got[0].error)
        self.assertNotIn("S3cret", got[0].error)


class PasswordFileTests(unittest.TestCase):
    def test_password_file_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(load_tasmota_password(d))
            save_tasmota_password(d, "S3cret!")
            self.assertEqual(load_tasmota_password(d), "S3cret!")
            path = os.path.join(d, "tasmota.json")
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            with open(path, encoding="utf-8") as f:
                self.assertEqual(json.load(f), {"password": "S3cret!"})
            save_tasmota_password(d, "alta")
            self.assertEqual(load_tasmota_password(d), "alta")

    def test_empty_password_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            for bad in ("", "   ", "\n", None, 123):
                with self.subTest(value=bad):
                    with self.assertRaises(ValueError) as cm:
                        save_tasmota_password(d, bad)
                    self.assertNotIn("S3cret", str(cm.exception))
            self.assertFalse(os.path.exists(os.path.join(d, "tasmota.json")))
            self.assertIsNone(load_tasmota_password(d))

    def test_blank_or_malformed_stored_password_loads_as_none(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "tasmota.json")
            for content in ('{"password": ""}', '{"password": "  "}', '{"password": 5}', '{}', '[]'):
                with self.subTest(content=content):
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(content)
                    self.assertIsNone(load_tasmota_password(d))


class InchingCodecSanity(unittest.TestCase):
    def test_encode_matches_decode(self):
        self.assertEqual(decode_inching(encode_inching(True, 1800)), (True, 1800))


if __name__ == "__main__":
    unittest.main()
