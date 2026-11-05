import json
import os
import tempfile
import threading
import unittest
from unittest import mock

from casa import web
from casa.auth import Auth, RateLimiter
from casa.config import Config
from casa.controller import CommandError
from casa.devices import DeviceInfo, save_devices
from casa.store import CorruptStore, JsonStore
from casa.web import Services, StateIsDemo, build_services, create_app

PASSWORD = "parola-de-test"
VIEW = {"id": "p1", "name": "Boiler", "online": True, "on": False, "metered": True, "power": 0.0,
        "voltage": 230.0, "current": 0.0, "schedules": [], "countdown": None,
        "inching": {"enabled": False, "minutes": 30}, "off_deadline": None, "error": None}


class FakeController:
    def __init__(self):
        self.devices = {"p1": object(), "p2": object()}
        self.calls = []
        self.fail = False
        self.raise_value = None

    def _view(self, pid):
        return dict(VIEW, id=pid, metered=(pid == "p1"), online=pid not in getattr(self, "offline", ()))

    def plug_view(self, pid):
        return self._view(pid)

    def plugs_view(self):
        return [self._view(p) for p in self.devices]

    def _do(self, name, pid, *args, **kw):
        self.calls.append((name, pid, args, kw))
        if self.fail:
            raise CommandError("timeout: priza nu raspunde")
        if self.raise_value:
            raise ValueError(self.raise_value)
        return self._view(pid)

    def switch(self, pid, on):
        if getattr(self, "slow", None):
            self.slow.wait(5)
        return self._do("switch", pid, on)

    def set_countdown(self, pid, action, minutes):
        return self._do("countdown", pid, action, minutes)

    def cancel_countdown(self, pid):
        return self._do("cancel", pid)

    def set_inching(self, pid, enabled, minutes):
        return self._do("inching", pid, enabled, minutes)

    def add_interval(self, pid, start, stop, enabled=True):
        return self._do("add", pid, start, stop, enabled)

    def update_interval(self, pid, sid, **changes):
        if sid == "nope":
            raise KeyError("interval necunoscut: nope")
        return self._do("update", pid, sid, **changes)

    def delete_interval(self, pid, sid):
        if sid == "nope":
            raise KeyError("interval necunoscut: nope")
        return self._do("delete", pid, sid)


class FakeCloud:
    def __init__(self):
        self.items = [{"id": "s1", "name": "Etaj", "temperature": 20.3, "humidity": 54.0,
                       "battery": 100, "updated": 1730800000.0},
                      {"id": "s2", "name": "Afara", "temperature": 6.6, "humidity": 83.0,
                       "battery": 90, "updated": 1730800100.0}]
        self.st = "ok"

    def sensors(self):
        return [dict(s) for s in self.items]

    def state(self):
        return self.st


class FakeInflux:
    def __init__(self):
        self.fail = False
        self.energy_calls = 0

    def _maybe(self):
        if self.fail:
            raise RuntimeError("influx jos")

    def sensor_spark(self, sid):
        self._maybe()
        return [[1730799000.0, 20.0], [1730799300.0, 20.3]]

    def energy_today(self, pid):
        self.energy_calls += 1
        self._maybe()
        return 2.4

    def sensor_history(self, sid, range_):
        self._maybe()
        return {"temperature": [[1.0, 2.0]], "humidity": [[1.0, 50.0]],
                "stats": {"temp_min": None, "temp_max": None, "hum_min": None, "hum_max": None}}


class WebCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.auth = Auth(JsonStore(os.path.join(self.tmp.name, "auth.json")))
        self.auth.set_password(PASSWORD)
        self.ctl, self.cloud, self.influx = FakeController(), FakeCloud(), FakeInflux()
        self.limiter = RateLimiter(1000, 1.0)
        self.app = self.make_app()
        self.c = self.app.test_client()

    def make_app(self):
        svc = Services(auth=self.auth, controller=self.ctl, cloud=self.cloud, influx=self.influx,
                       command_limiter=self.limiter)
        return create_app(svc)

    def register(self, client=None, ip="10.0.0.5"):
        r = (client or self.c).post("/register", json={"password": PASSWORD, "device": "Pixel 8"},
                                    headers={"X-Forwarded-For": ip})
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        return r.get_json()["token"]

    def h(self, token=None):
        return {"Authorization": "Bearer " + (token or self.token)}

    @property
    def token(self):
        if not hasattr(self, "_token"):
            self._token = self.register()
        return self._token


class TestAuthRoutes(WebCase):
    def test_every_route_without_token_is_401(self):
        checked = 0
        for rule in self.app.url_map.iter_rules():
            if rule.rule in ("/register", "/health") or rule.endpoint == "static":
                continue
            url = rule.rule
            for arg in rule.arguments:
                url = url.replace(f"<{arg}>", "p1").replace(f"<int:{arg}>", "1")
            for method in rule.methods - {"HEAD", "OPTIONS"}:
                r = self.c.open(url, method=method, json={})
                self.assertEqual(r.status_code, 401, f"{method} {url}")
                self.assertEqual(r.headers.get("WWW-Authenticate"), 'Bearer realm="casa"',
                                 f"{method} {url}")
                checked += 1
        self.assertEqual(checked, 11)

    def test_bad_and_malformed_token_is_401(self):
        for hdr in ({"Authorization": "Bearer nu-exista"}, {"Authorization": "Basic abc"},
                    {"Authorization": "Bearer"}, {}):
            r = self.c.get("/plugs", headers=hdr)
            self.assertEqual(r.status_code, 401)
            self.assertEqual(r.headers.get("WWW-Authenticate"), 'Bearer realm="casa"')

    def test_health_open(self):
        r = self.c.get("/health")
        self.assertEqual((r.status_code, r.get_json()), (200, {"ok": True}))

    def test_register_flow_and_me(self):
        r = self.c.post("/register", json={"password": PASSWORD, "device": "Pixel 8"})
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertTrue(body["token"] and body["phone_id"])
        me = self.c.get("/me", headers=self.h(body["token"]))
        self.assertEqual(me.status_code, 200)
        self.assertEqual(me.get_json(), {"phone_id": body["phone_id"], "device": "Pixel 8"})

    def test_register_wrong_password_401_with_challenge(self):
        r = self.c.post("/register", json={"password": "gresita!", "device": "x"})
        self.assertEqual(r.status_code, 401)
        self.assertEqual(r.get_json(), {"error": "parola gresita"})
        self.assertEqual(r.headers.get("WWW-Authenticate"), 'Bearer realm="casa"')

    def test_register_no_password_503(self):
        auth = Auth(JsonStore(os.path.join(self.tmp.name, "other.json")))
        svc = Services(auth=auth, controller=self.ctl, cloud=self.cloud, influx=self.influx)
        r = create_app(svc).test_client().post("/register", json={"password": "abc", "device": "x"})
        self.assertEqual(r.status_code, 503)
        self.assertEqual(r.get_json(), {"error": "parola nesetata"})

    def test_register_rate_limited_returns_429_with_retry_after(self):
        for _ in range(5):
            r = self.c.post("/register", json={"password": "gresita!", "device": "x"},
                            headers={"X-Forwarded-For": "9.9.9.9"})
            self.assertEqual(r.status_code, 401)
        r = self.c.post("/register", json={"password": PASSWORD, "device": "x"},
                        headers={"X-Forwarded-For": "9.9.9.9"})
        self.assertEqual(r.status_code, 429)
        body = r.get_json()
        self.assertEqual(body["error"], "prea multe incercari")
        self.assertGreater(body["retry_after"], 0)
        self.assertEqual(r.headers.get("Retry-After"), str(body["retry_after"]))

    def test_register_ip_is_last_forwarded_entry(self):
        # 5 greseli de la IP-ul din ultima pozitie blocheaza doar acel IP; primul (falsificabil) e ignorat
        for i in range(5):
            self.c.post("/register", json={"password": "gresita!", "device": "x"},
                        headers={"X-Forwarded-For": f"1.1.1.{i}, 8.8.8.8"})
        blocked = self.c.post("/register", json={"password": PASSWORD, "device": "x"},
                              headers={"X-Forwarded-For": "2.2.2.2, 8.8.8.8"})
        self.assertEqual(blocked.status_code, 429)

    def test_register_validation_400(self):
        bad = [None, {}, {"password": "", "device": "x"}, {"password": "a" * 257, "device": "x"},
               {"password": 123, "device": "x"}, {"password": "abc", "device": 5},
               {"password": "abc", "device": "d" * 101}, {"password": "abc"}, [1, 2]]
        for body in bad:
            r = self.c.post("/register", json=body)
            self.assertEqual(r.status_code, 400, body)
            self.assertIn("error", r.get_json())
        r = self.c.post("/register", data="nu-e-json", content_type="text/plain")
        self.assertEqual(r.status_code, 400)

    def test_revoked_token_gets_401(self):
        self.assertEqual(self.c.get("/plugs", headers=self.h()).status_code, 200)
        phone = self.c.get("/me", headers=self.h()).get_json()["phone_id"]
        self.assertTrue(self.auth.revoke(phone))
        r = self.c.get("/plugs", headers=self.h())
        self.assertEqual(r.status_code, 401)
        self.assertEqual(r.headers.get("WWW-Authenticate"), 'Bearer realm="casa"')


class TestPlugRoutes(WebCase):
    def test_plugs_list_has_energy_and_timestamp(self):
        r = self.c.get("/plugs", headers=self.h())
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertIsInstance(body["timestamp"], (int, float))
        plugs = {p["id"]: p for p in body["plugs"]}
        self.assertEqual(plugs["p1"]["energy_today"], 2.4)
        self.assertIsNone(plugs["p2"]["energy_today"])  # neinregistrata la consum

    def test_plugs_energy_failure_is_null_not_error(self):
        self.influx.fail = True
        r = self.c.get("/plugs", headers=self.h())
        self.assertEqual(r.status_code, 200)
        self.assertIsNone(r.get_json()["plugs"][0]["energy_today"])

    def test_switch_returns_view_and_502_on_command_error(self):
        r = self.c.post("/plugs/p1/switch", json={"on": True}, headers=self.h())
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["id"], "p1")
        self.assertEqual(self.ctl.calls[-1], ("switch", "p1", (True,), {}))
        self.ctl.fail = True
        r = self.c.post("/plugs/p1/switch", json={"on": False}, headers=self.h())
        self.assertEqual(r.status_code, 502)
        body = r.get_json()
        self.assertEqual(body["error"], "priza nu raspunde")
        self.assertEqual(body["plug"]["id"], "p1")

    def test_all_commands_route_to_controller(self):
        h = self.h()
        self.assertEqual(self.c.put("/plugs/p1/countdown", json={"action": "off", "minutes": 30},
                                    headers=h).status_code, 200)
        self.assertEqual(self.c.delete("/plugs/p1/countdown", headers=h).status_code, 200)
        self.assertEqual(self.c.post("/plugs/p1/schedules", json={"start": "10:30", "stop": "16:00"},
                                     headers=h).status_code, 200)
        self.assertEqual(self.c.put("/plugs/p1/schedules/ab12", json={"enabled": False},
                                    headers=h).status_code, 200)
        self.assertEqual(self.c.delete("/plugs/p1/schedules/ab12", headers=h).status_code, 200)
        self.assertEqual(self.c.put("/plugs/p1/inching", json={"enabled": True, "minutes": 45},
                                    headers=h).status_code, 200)
        names = [c[0] for c in self.ctl.calls]
        self.assertEqual(names, ["countdown", "cancel", "add", "update", "delete", "inching"])
        self.assertEqual(self.ctl.calls[2][2], ("10:30", "16:00", True))
        self.assertEqual(self.ctl.calls[3][3], {"enabled": False})

    def test_validation_errors_are_400(self):
        h = self.h()
        cases = [
            ("post", "/plugs/p1/switch", {"on": "da"}),
            ("post", "/plugs/p1/switch", {"on": 1}),
            ("post", "/plugs/p1/switch", {}),
            ("put", "/plugs/p1/countdown", {"action": "off", "minutes": 0}),
            ("put", "/plugs/p1/countdown", {"action": "off", "minutes": 1441}),
            ("put", "/plugs/p1/countdown", {"action": "off", "minutes": 5.5}),
            ("put", "/plugs/p1/countdown", {"action": "off", "minutes": True}),
            ("put", "/plugs/p1/countdown", {"action": "toggle", "minutes": 5}),
            ("post", "/plugs/p1/schedules", {"start": "25:00", "stop": "16:00"}),
            ("post", "/plugs/p1/schedules", {"start": "10:60", "stop": "16:00"}),
            ("post", "/plugs/p1/schedules", {"start": "9:30", "stop": "16:00"}),
            ("post", "/plugs/p1/schedules", {"start": "10:30", "stop": "16:00\n"}),
            ("post", "/plugs/p1/schedules", {"start": "10:30", "stop": "16:00", "enabled": "yes"}),
            ("post", "/plugs/p1/schedules", {"start": "10:30"}),
            ("put", "/plugs/p1/schedules/ab12", {}),
            ("put", "/plugs/p1/schedules/ab12", {"start": "xx"}),
            ("put", "/plugs/p1/schedules/ab12", {"colour": "red"}),
            ("put", "/plugs/p1/inching", {"enabled": True, "minutes": 721}),
            ("put", "/plugs/p1/inching", {"enabled": 1, "minutes": 10}),
            ("put", "/plugs/p1/inching", {"enabled": True}),
        ]
        for method, url, body in cases:
            r = getattr(self.c, method)(url, json=body, headers=h)
            self.assertEqual(r.status_code, 400, (url, body))
            self.assertIn("error", r.get_json())
        r = self.c.post("/plugs/p1/switch", data="nu-json", content_type="text/plain", headers=h)
        self.assertEqual(r.status_code, 400)
        r = self.c.post("/plugs/p1/switch", json=[True], headers=h)
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.ctl.calls, [])

    def test_controller_value_error_is_400(self):
        self.ctl.raise_value = "cel mult 6 intervale pe priza"
        r = self.c.post("/plugs/p1/schedules", json={"start": "10:30", "stop": "16:00"}, headers=self.h())
        self.assertEqual(r.status_code, 400)
        self.assertEqual(r.get_json(), {"error": "cel mult 6 intervale pe priza"})

    def test_unknown_plug_404(self):
        h = self.h()
        for method, url, body in [("post", "/plugs/zzz/switch", {"on": True}),
                                  ("delete", "/plugs/zzz/countdown", None),
                                  ("put", "/plugs/zzz/inching", {"enabled": True, "minutes": 5}),
                                  ("delete", "/plugs/zzz/schedules/ab12", None)]:
            r = getattr(self.c, method)(url, json=body, headers=h)
            self.assertEqual(r.status_code, 404, url)
            self.assertIn("error", r.get_json())

    def test_unknown_schedule_404(self):
        r = self.c.delete("/plugs/p1/schedules/nope", headers=self.h())
        self.assertEqual(r.status_code, 404)
        r = self.c.put("/plugs/p1/schedules/nope", json={"enabled": True}, headers=self.h())
        self.assertEqual(r.status_code, 404)

    def test_command_rate_limit_429(self):
        self.limiter = RateLimiter(3, 3.0)
        app = self.make_app()
        c = app.test_client()
        h = self.h(self.register(c))
        for _ in range(3):
            self.assertEqual(c.post("/plugs/p1/switch", json={"on": True}, headers=h).status_code, 200)
        r = c.post("/plugs/p1/switch", json={"on": True}, headers=h)
        self.assertEqual(r.status_code, 429)
        body = r.get_json()
        self.assertEqual(body["error"], "prea multe comenzi")
        self.assertGreaterEqual(body["retry_after"], 1)
        self.assertEqual(len(self.ctl.calls), 3)
        # citirile (GET) nu sunt limitate
        for _ in range(10):
            self.assertEqual(c.get("/plugs", headers=h).status_code, 200)
        # alt telefon are limita proprie
        h2 = self.h(self.register(c, ip="10.0.0.6"))
        self.assertEqual(c.post("/plugs/p1/switch", json={"on": True}, headers=h2).status_code, 200)


class TestSensorRoutes(WebCase):
    def test_sensors_shape(self):
        r = self.c.get("/sensors", headers=self.h())
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual(body["cloud"], "ok")
        self.assertEqual(len(body["sensors"]), 2)
        s = body["sensors"][0]
        self.assertEqual(set(s), {"id", "name", "temperature", "humidity", "battery", "updated", "spark"})
        self.assertEqual(s["spark"], [[1730799000.0, 20.0], [1730799300.0, 20.3]])

    def test_sensors_spark_failure_is_empty_not_503(self):
        self.influx.fail = True
        self.cloud.st = "disconnected"
        r = self.c.get("/sensors", headers=self.h())
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual(body["cloud"], "disconnected")
        self.assertEqual([s["spark"] for s in body["sensors"]], [[], []])

    def test_history_ok_and_validation(self):
        h = self.h()
        r = self.c.get("/sensors/s1/history?range=1d", headers=h)
        self.assertEqual(r.status_code, 200)
        body = r.get_json()
        self.assertEqual((body["id"], body["name"], body["range"]), ("s1", "Etaj", "1d"))
        self.assertIn("stats", body)
        self.assertEqual(self.c.get("/sensors/s1/history?range=3y", headers=h).status_code, 400)
        self.assertEqual(self.c.get("/sensors/s1/history", headers=h).status_code, 400)
        self.assertEqual(self.c.get("/sensors/zzz/history?range=2h", headers=h).status_code, 404)

    def test_history_influx_down_503(self):
        self.influx.fail = True
        r = self.c.get("/sensors/s1/history?range=2h", headers=self.h())
        self.assertEqual(r.status_code, 503)
        self.assertEqual(r.get_json(), {"error": "istoric indisponibil"})


class TestRobustness(WebCase):
    def test_public_remote_addr_ignores_forwarded_for(self):
        env = {"REMOTE_ADDR": "8.8.4.4"}
        for i in range(5):
            r = self.c.post("/register", json={"password": "gresita!", "device": "x"},
                            headers={"X-Forwarded-For": f"1.1.1.{i}"}, environ_overrides=env)
            self.assertEqual(r.status_code, 401)
        r = self.c.post("/register", json={"password": PASSWORD, "device": "x"},
                        headers={"X-Forwarded-For": "3.3.3.3"}, environ_overrides=env)
        self.assertEqual(r.status_code, 429)  # cheia e IP-ul real, nu antetul falsificabil

    def test_oversized_body_is_413(self):
        r = self.c.post("/register", data="x" * (web.MAX_BODY_BYTES + 1), content_type="application/json")
        self.assertEqual(r.status_code, 413)
        self.assertEqual(r.get_json(), {"error": "cerere prea mare"})
        r = self.c.post("/plugs/p1/switch", data="x" * (web.MAX_BODY_BYTES + 1),
                        content_type="application/json", headers=self.h())
        self.assertEqual(r.status_code, 413)

    def test_command_for_offline_flagged_plug_is_still_submitted(self):
        self.ctl.offline = {"p1"}
        r = self.c.post("/plugs/p1/switch", json={"on": True}, headers=self.h())
        self.assertEqual(r.status_code, 200)          # workerul decide; aici raspunde
        self.assertEqual(self.ctl.calls, [("switch", "p1", (True,), {})])
        self.ctl.fail = True                          # workerul nu a reusit conectarea
        r = self.c.post("/plugs/p1/switch", json={"on": False}, headers=self.h())
        self.assertEqual(r.status_code, 502)
        self.assertEqual(r.get_json()["plug"]["online"], False)

    def test_concurrent_command_to_same_plug_times_out_with_503(self):
        self.ctl.slow = threading.Event()
        h = self.h()
        first = []
        t = threading.Thread(target=lambda: first.append(
            self.app.test_client().post("/plugs/p1/switch", json={"on": True}, headers=h).status_code))
        t.start()
        try:
            for _ in range(100):  # asteapta ca prima cerere sa tina lock-ul
                if self.ctl.slow is not None and any(l.locked() for l in [self.app.config["PLUG_LOCKS"]["p1"]]):
                    break
                threading.Event().wait(0.02)
            with mock.patch.object(web, "PLUG_BUSY_WAIT_S", 0.2):
                r = self.c.post("/plugs/p1/switch", json={"on": False}, headers=h)
            self.assertEqual(r.status_code, 503)
            # alta priza nu e blocata
            self.assertEqual(self.c.post("/plugs/p2/switch", json={"on": True}, headers=h).status_code, 200)
        finally:
            self.ctl.slow.set()
            t.join(5)
        self.assertEqual(first, [200])

    def test_storage_and_unexpected_errors_are_500_not_400_404(self):
        h = self.h()
        for exc in (CorruptStore("fisier JSON corupt: /data/state.json: x"), OSError("disc plin"),
                    KeyError("altceva")):
            with mock.patch.object(self.ctl, "switch", side_effect=exc):
                with self.assertLogs("casa.web", level="ERROR") as logs:
                    r = self.c.post("/plugs/p1/switch", json={"on": True}, headers=h)
            self.assertEqual(r.status_code, 500, repr(exc))
            self.assertEqual(r.get_json(), {"error": "eroare interna"})
            self.assertNotIn(PASSWORD, " ".join(logs.output))


class TestBuildServices(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = self.tmp.name
        plug = DeviceInfo(id="bfplug1", name="Boiler", kind="plug", local_key=None, ip=None, version=None,
                          dps={"switch": 1, "countdown": 9, "current": 18, "power": 19, "voltage": 20},
                          scales={"power": 1, "voltage": 1, "current": 0})
        sensor = DeviceInfo(id="demo_sensor_etaj", name="Temp Etaj", kind="sensor", local_key=None,
                            ip=None, version=None)
        save_devices(self.dir, [plug, sensor])

    def cfg(self, demo):
        return Config(data_dir=self.dir, influx_url="http://127.0.0.1:1", influx_token="t", influx_org="o",
                      bucket_live="live", bucket_history="history", ntfy_url="", poll_s=0.2, demo=demo)

    def test_demo_wiring_has_no_influx_no_cloud_and_never_touches_state_json(self):
        boom = mock.Mock(side_effect=AssertionError("nu trebuia construit in demo"))
        with mock.patch("casa.influx.CasaInflux", boom), mock.patch("casa.cloud.CloudSession", boom):
            svc = build_services(self.cfg(True))
        self.addCleanup(svc.shutdown)
        boom.assert_not_called()
        svc.controller.set_countdown("bfplug1", "off", 30)   # scrie stare
        names = set(os.listdir(self.dir))
        self.assertNotIn("state.json", names)
        with open(os.path.join(self.dir, "state.demo.json")) as f:
            data = json.load(f)
        self.assertIs(data["demo"], True)                      # marcajul supravietuieste scrierilor
        self.assertIn("bfplug1", data["plugs"])
        self.assertEqual(svc.cloud.state(), "ok")

    def test_real_mode_refuses_demo_marked_state(self):
        with open(os.path.join(self.dir, "state.json"), "w") as f:
            json.dump({"demo": True, "plugs": {"bfplug1": {}}}, f)
        boom = mock.Mock(side_effect=AssertionError("nu trebuia construit"))
        with mock.patch("casa.influx.CasaInflux", boom), mock.patch("casa.cloud.CloudSession", boom):
            with self.assertRaises(StateIsDemo):
                build_services(self.cfg(False))
        boom.assert_not_called()

    def test_real_mode_ignores_demo_state_file(self):
        with open(os.path.join(self.dir, "state.demo.json"), "w") as f:
            json.dump({"demo": True, "plugs": {"bfplug1": {"countdown": {"action": "on", "at": 1}}}}, f)
        fake = mock.Mock()
        with mock.patch("casa.influx.CasaInflux", return_value=fake), \
                mock.patch("casa.cloud.CloudSession", return_value=fake):
            svc = build_services(self.cfg(False))
        self.addCleanup(svc.shutdown)
        self.assertEqual(svc.controller.plug_view("bfplug1")["countdown"], None)


if __name__ == "__main__":
    unittest.main()
