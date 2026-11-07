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
        "voltage": 230.0, "current": 0.0, "schedules": [], "events": [], "next_event": None,
        "countdown": None, "inching": {"enabled": False, "minutes": 30}, "off_deadline": None, "error": None}


class FakeController:
    def __init__(self):
        self.devices = {"p1": object(), "p2": object()}
        self.calls = []
        self.fail = False
        self.raise_value = None
        self.raise_key = None

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
        if self.raise_key:
            raise KeyError(self.raise_key)
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

    def add_event(self, pid, time, action, days, enabled=True):
        return self._do("add_event", pid, time, action, days, enabled)

    def update_event(self, pid, event_id, **changes):
        return self._do("update_event", pid, event_id, **changes)

    def delete_event(self, pid, event_id):
        return self._do("delete_event", pid, event_id)

    def rename(self, pid, name):
        return dict(self._do("rename", pid, name), name=name)


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
                       command_limiter=self.limiter, data_dir=self.tmp.name)
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
        self.assertEqual(checked, 13)

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
        self.assertEqual(self.c.put("/plugs/p1/inching", json={"enabled": True, "minutes": 45},
                                    headers=h).status_code, 200)
        names = [c[0] for c in self.ctl.calls]
        self.assertEqual(names, ["countdown", "cancel", "inching"])

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
        self.ctl.raise_value = "minute inching invalide"
        r = self.c.put("/plugs/p1/inching", json={"enabled": True, "minutes": 45}, headers=self.h())
        self.assertEqual(r.status_code, 400)
        self.assertEqual(r.get_json(), {"error": "minute inching invalide"})

    def test_unknown_plug_404(self):
        h = self.h()
        for method, url, body in [("post", "/plugs/zzz/switch", {"on": True}),
                                  ("delete", "/plugs/zzz/countdown", None),
                                  ("put", "/plugs/zzz/inching", {"enabled": True, "minutes": 5})]:
            r = getattr(self.c, method)(url, json=body, headers=h)
            self.assertEqual(r.status_code, 404, url)
            self.assertIn("error", r.get_json())

    def test_schedule_routes_are_gone(self):
        # intervalele au fost inlocuite de evenimente; aplicatia <= 3.43 doar citeste "schedules": []
        h = self.h()
        for method, url in (("post", "/plugs/p1/schedules"), ("put", "/plugs/p1/schedules/ab12"),
                            ("delete", "/plugs/p1/schedules/ab12")):
            r = getattr(self.c, method)(url, json={"start": "10:30", "stop": "16:00"}, headers=h)
            self.assertIn(r.status_code, (404, 405), url)
        self.assertEqual(self.ctl.calls, [])

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


class TestEventRoutes(WebCase):
    def test_add_event_defaults_to_daily_and_enabled(self):
        r = self.c.post("/plugs/p1/events", json={"time": "07:00", "action": "on"}, headers=self.h())
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        self.assertEqual(r.get_json()["id"], "p1")
        self.assertEqual(self.ctl.calls, [("add_event", "p1", ("07:00", "on", None, True), {})])

    def test_add_event_with_days_and_enabled(self):
        h = self.h()
        cases = [([1, 3, 5], (1, 3, 5)), ([5, 1, 1, 3], (1, 3, 5)), ([7], (7,)),
                 ([1, 2, 3, 4, 5, 6, 7], None), (None, None)]
        for sent, expected in cases:
            self.ctl.calls.clear()
            r = self.c.post("/plugs/p2/events", headers=h,
                            json={"time": "23:59", "action": "off", "days": sent, "enabled": False})
            self.assertEqual(r.status_code, 200, sent)
            self.assertEqual(self.ctl.calls, [("add_event", "p2", ("23:59", "off", expected, False), {})], sent)

    def test_update_event_passes_only_the_sent_fields(self):
        h = self.h()
        cases = [({"enabled": False}, {"enabled": False}),
                 ({"time": "08:15"}, {"time": "08:15"}),
                 ({"action": "off"}, {"action": "off"}),
                 ({"days": None}, {"days": None}),
                 ({"days": [6, 7]}, {"days": (6, 7)}),
                 ({"time": "00:00", "action": "on", "days": [2], "enabled": True},
                  {"time": "00:00", "action": "on", "days": (2,), "enabled": True})]
        for body, changes in cases:
            self.ctl.calls.clear()
            r = self.c.put("/plugs/p1/events/ab12", json=body, headers=h)
            self.assertEqual(r.status_code, 200, body)
            self.assertEqual(r.get_json()["id"], "p1")
            self.assertEqual(self.ctl.calls, [("update_event", "p1", ("ab12",), changes)], body)

    def test_delete_event(self):
        r = self.c.delete("/plugs/p1/events/ab12", headers=self.h())
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["id"], "p1")
        self.assertEqual(self.ctl.calls, [("delete_event", "p1", ("ab12",), {})])

    def test_add_validation_errors_are_400(self):
        h = self.h()
        ok = {"time": "07:00", "action": "on"}
        bad = [
            {"action": "on"},                                  # lipseste ora
            {"time": "07:00"},                                 # lipseste actiunea
            {},
            dict(ok, time="7:00"), dict(ok, time="24:00"), dict(ok, time="07:60"), dict(ok, time="07:00:00"),
            dict(ok, time=700), dict(ok, time=None), dict(ok, time=" 07:00"), dict(ok, time="07:00\n"),
            dict(ok, action="toggle"), dict(ok, action="ON"), dict(ok, action=True), dict(ok, action=None),
            dict(ok, days=[]), dict(ok, days=[0]), dict(ok, days=[8]), dict(ok, days=[-1]),
            dict(ok, days=[1.0]), dict(ok, days=[1.5]), dict(ok, days=[True]), dict(ok, days=["1"]),
            dict(ok, days=[None]), dict(ok, days=[[1]]), dict(ok, days="1"), dict(ok, days="12"),
            dict(ok, days=3), dict(ok, days=True), dict(ok, days={"1": 1}),
            dict(ok, enabled="false"), dict(ok, enabled=0), dict(ok, enabled=None),
            dict(ok, extra=1),
        ]
        for body in bad:
            r = self.c.post("/plugs/p1/events", json=body, headers=h)
            self.assertEqual(r.status_code, 400, body)
            self.assertIn("error", r.get_json(), body)
        r = self.c.post("/plugs/p1/events", data="nu-json", content_type="text/plain", headers=h)
        self.assertEqual(r.status_code, 400)
        r = self.c.post("/plugs/p1/events", json=[ok], headers=h)
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.ctl.calls, [])

    def test_update_validation_errors_are_400(self):
        h = self.h()
        bad = [
            {},                                                # nimic de modificat
            {"time": "7:00"}, {"time": 700}, {"time": None}, {"time": "24:00"},
            {"action": "toggle"}, {"action": None}, {"action": 1},
            {"days": []}, {"days": [0]}, {"days": [8]}, {"days": [1.0]}, {"days": [True]}, {"days": ["1"]},
            {"days": "1"}, {"days": 3},
            {"enabled": "false"}, {"enabled": 1}, {"enabled": None},
            {"id": "zz99"}, {"extra": 1},                      # id-ul nu se schimba
            {"enabled": False, "time": "99:99"},               # un camp bun nu salveaza unul rau
        ]
        for body in bad:
            r = self.c.put("/plugs/p1/events/ab12", json=body, headers=h)
            self.assertEqual(r.status_code, 400, body)
            self.assertIn("error", r.get_json(), body)
        r = self.c.put("/plugs/p1/events/ab12", json=["enabled"], headers=h)
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.ctl.calls, [])

    def test_days_error_names_the_field(self):
        r = self.c.post("/plugs/p1/events", json={"time": "07:00", "action": "on", "days": [9]},
                        headers=self.h())
        self.assertEqual(r.status_code, 400)
        self.assertTrue(r.get_json()["error"].startswith("days:"), r.get_json())

    def test_controller_value_errors_reach_the_client_as_400_with_the_message(self):
        h = self.h()
        for msg in ("conflict: exista deja un eveniment activ la 07:00 in aceleasi zile",
                    "cel mult 12 evenimente pe priza"):
            self.ctl.raise_value = msg
            for method, url, body in (("post", "/plugs/p1/events", {"time": "07:00", "action": "on"}),
                                      ("put", "/plugs/p1/events/ab12", {"enabled": True})):
                r = getattr(self.c, method)(url, json=body, headers=h)
                self.assertEqual(r.status_code, 400, (method, msg))
                self.assertEqual(r.get_json(), {"error": msg})

    def test_unknown_event_is_404(self):
        h = self.h()
        self.ctl.raise_key = "eveniment necunoscut: zz99"
        for method, body in (("put", {"enabled": True}), ("delete", None)):
            r = getattr(self.c, method)("/plugs/p1/events/zz99", json=body, headers=h)
            self.assertEqual(r.status_code, 404, method)
            self.assertEqual(r.get_json(), {"error": "eveniment necunoscut: zz99"})

    def test_other_key_error_is_not_masked_as_404(self):
        # un KeyError care nu e "eveniment necunoscut" e defect: 500, nu "negasit"
        self.app.config["PROPAGATE_EXCEPTIONS"] = False
        self.ctl.raise_key = "state"
        with self.assertLogs("casa.web", level="ERROR"):
            r = self.c.put("/plugs/p1/events/ab12", json={"enabled": True}, headers=self.h())
        self.assertEqual(r.status_code, 500)

    def test_unknown_plug_is_404_before_the_controller_is_called(self):
        h = self.h()
        for method, url, body in (("post", "/plugs/zzz/events", {"time": "07:00", "action": "on"}),
                                  ("put", "/plugs/zzz/events/ab12", {"enabled": True}),
                                  ("delete", "/plugs/zzz/events/ab12", None)):
            r = getattr(self.c, method)(url, json=body, headers=h)
            self.assertEqual(r.status_code, 404, url)
            self.assertIn("error", r.get_json())
        self.assertEqual(self.ctl.calls, [])

    def test_event_routes_share_the_command_throttle(self):
        self.limiter = RateLimiter(3, 3.0)
        app = self.make_app()
        c = app.test_client()
        h = self.h(self.register(c))
        for _ in range(3):
            self.assertEqual(c.post("/plugs/p1/events", json={"time": "07:00", "action": "on"},
                                    headers=h).status_code, 200)
        for method, url, body in (("post", "/plugs/p1/events", {"time": "07:00", "action": "on"}),
                                  ("put", "/plugs/p1/events/ab12", {"enabled": False}),
                                  ("delete", "/plugs/p1/events/ab12", None)):
            r = getattr(c, method)(url, json=body, headers=h)
            self.assertEqual(r.status_code, 429, url)
        self.assertEqual(len(self.ctl.calls), 3)


class TestRenameRoutes(WebCase):
    def local(self):
        with open(os.path.join(self.tmp.name, "devices.local.json")) as f:
            return json.load(f)

    def test_rename_plug_saved_and_returned(self):
        r = self.c.put("/plugs/p1/name", json={"name": "  Calorifer Georgi "}, headers=self.h())
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        self.assertEqual(r.get_json()["name"], "Calorifer Georgi")
        self.assertIn(("rename", "p1", ("Calorifer Georgi",), {}), self.ctl.calls)
        self.assertEqual(self.local(), {"p1": {"name": "Calorifer Georgi"}})

    def test_rename_sensor_shows_everywhere(self):
        r = self.c.put("/sensors/s1/name", json={"name": "Dormitor"}, headers=self.h())
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json(), {"id": "s1", "name": "Dormitor"})
        names = {s["id"]: s["name"] for s in self.c.get("/sensors", headers=self.h()).get_json()["sensors"]}
        self.assertEqual(names, {"s1": "Dormitor", "s2": "Afara"})
        self.assertEqual(self.c.get("/sensors/s1/history?range=2h", headers=self.h()).get_json()["name"], "Dormitor")
        self.assertEqual(self.local(), {"s1": {"name": "Dormitor"}})

    def test_rename_keeps_other_local_fields(self):
        with open(os.path.join(self.tmp.name, "devices.local.json"), "w") as f:
            json.dump({"p1": {"ip": "192.168.1.5", "version": "3.4"}}, f)
        self.c.put("/plugs/p1/name", json={"name": "Boiler"}, headers=self.h())
        self.assertEqual(self.local(), {"p1": {"ip": "192.168.1.5", "version": "3.4", "name": "Boiler"}})

    def test_rename_validation(self):
        h = self.h()
        for body in ({"name": ""}, {"name": "   "}, {"name": "x" * 25}, {"name": 5}, {"name": "a\nb"}, {}):
            self.assertEqual(self.c.put("/plugs/p1/name", json=body, headers=h).status_code, 400, body)
        self.assertEqual(self.c.put("/plugs/zz/name", json={"name": "A"}, headers=h).status_code, 404)
        self.assertEqual(self.c.put("/sensors/zz/name", json={"name": "A"}, headers=h).status_code, 404)
        self.assertEqual(self.c.put("/plugs/p1/name", json={"name": "A"}).status_code, 401)


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

    def test_demo_marked_state_exits_with_gunicorn_app_load_error(self):
        # cod 4 = APP_LOAD_ERROR: gunicorn se opreste in loc sa respawneze workerul in bucla
        with mock.patch.object(web, "_services", None), \
                mock.patch.object(web, "build_services", side_effect=StateIsDemo("marcaj demo")):
            with self.assertLogs("casa.web", level="CRITICAL"):
                with self.assertRaises(SystemExit) as cm:
                    web._services_from_env()
        self.assertEqual(cm.exception.code, 4)
        self.assertEqual(web.GUNICORN_APP_LOAD_ERROR, 4)

    def test_real_mode_ignores_demo_state_file(self):
        with open(os.path.join(self.dir, "state.demo.json"), "w") as f:
            json.dump({"demo": True, "plugs": {"bfplug1": {"countdown": {"action": "on", "at": 1}}}}, f)
        fake = mock.Mock()
        with mock.patch("casa.influx.CasaInflux", return_value=fake), \
                mock.patch("casa.cloud.CloudSession", return_value=fake):
            svc = build_services(self.cfg(False))
        self.addCleanup(svc.shutdown)
        self.assertEqual(svc.controller.plug_view("bfplug1")["countdown"], None)

    def test_demo_state_with_old_intervals_is_migrated_and_stays_demo_marked(self):
        demo_path = os.path.join(self.dir, "state.demo.json")
        with open(demo_path, "w") as f:
            json.dump({"demo": True, "plugs": {"bfplug1": {
                "schedules": [{"id": "ab12", "start": "10:30", "stop": "16:00", "enabled": True}],
                "last_done": {}, "started_by": None, "cutoff": {}}}}, f)
        svc = build_services(self.cfg(True))
        view = svc.controller.plug_view("bfplug1")
        svc.shutdown()
        self.assertEqual([e["id"] for e in view["events"]], ["ab12s", "ab12e"])
        self.assertEqual(view["schedules"], [])
        with open(demo_path) as f:
            data = json.load(f)
        self.assertIs(data["demo"], True)                    # marcajul demo ramane
        self.assertNotIn("schedules", data["plugs"]["bfplug1"])
        # mutat in state.json, modul real il refuza in continuare
        os.replace(demo_path, os.path.join(self.dir, "state.json"))
        boom = mock.Mock(side_effect=AssertionError("nu trebuia construit"))
        with mock.patch("casa.influx.CasaInflux", boom), mock.patch("casa.cloud.CloudSession", boom):
            with self.assertRaises(StateIsDemo):
                build_services(self.cfg(False))


class TestEventRoutesWithController(unittest.TestCase):
    """Rutele de evenimente pe controllerul real (modul demo): mesajele si codurile ajung asa cum le vede telefonul."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        plug = DeviceInfo(id="bfplug1", name="Boiler", kind="plug", local_key=None, ip=None, version=None,
                          dps={"switch": 1, "countdown": 9}, scales={})
        save_devices(self.tmp.name, [plug])
        cfg = Config(data_dir=self.tmp.name, influx_url="http://127.0.0.1:1", influx_token="t", influx_org="o",
                     bucket_live="live", bucket_history="history", ntfy_url="", poll_s=0.2, demo=True)
        svc = build_services(cfg)
        self.addCleanup(svc.shutdown)
        svc.command_limiter = RateLimiter(1000, 1.0)
        svc.auth.set_password(PASSWORD)
        self.c = create_app(svc).test_client()
        r = self.c.post("/register", json={"password": PASSWORD, "device": "Pixel 8"},
                        headers={"X-Forwarded-For": "10.0.0.5"})
        self.h = {"Authorization": "Bearer " + r.get_json()["token"]}

    def add(self, time, action="on", **extra):
        return self.c.post("/plugs/bfplug1/events", json=dict(time=time, action=action, **extra), headers=self.h)

    def test_add_update_delete_round_trip_and_plug_list(self):
        r = self.add("07:00", "on", days=[1, 2, 3, 4, 5])
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        view = r.get_json()
        self.assertEqual(len(view["events"]), 1)
        ev = view["events"][0]
        self.assertEqual((ev["time"], ev["action"], ev["days"], ev["enabled"]), ("07:00", "on", [1, 2, 3, 4, 5], True))
        self.assertEqual(view["schedules"], [])
        self.assertEqual(view["next_event"]["id"], ev["id"])
        self.assertIsInstance(view["next_event"]["at"], (int, float))

        r = self.c.put(f"/plugs/bfplug1/events/{ev['id']}", json={"days": None, "time": "08:30"}, headers=self.h)
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        ev2 = r.get_json()["events"][0]
        self.assertEqual((ev2["id"], ev2["time"], ev2["days"]), (ev["id"], "08:30", None))

        listed = self.c.get("/plugs", headers=self.h).get_json()["plugs"][0]
        self.assertEqual(listed["events"], [ev2])
        self.assertEqual(listed["schedules"], [])
        self.assertEqual(listed["next_event"]["time"], "08:30")

        r = self.c.put(f"/plugs/bfplug1/events/{ev['id']}", json={"enabled": False}, headers=self.h)
        self.assertEqual(r.status_code, 200)
        self.assertIsNone(r.get_json()["next_event"])

        r = self.c.delete(f"/plugs/bfplug1/events/{ev['id']}", headers=self.h)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["events"], [])
        self.assertIsNone(r.get_json()["next_event"])

    def test_conflict_is_400_with_the_controller_message(self):
        self.assertEqual(self.add("07:00", "on", days=[1, 2]).status_code, 200)
        r = self.add("07:00", "off", days=[2, 3])
        self.assertEqual(r.status_code, 400)
        self.assertTrue(r.get_json()["error"].startswith("conflict"), r.get_json())
        # zile disjuncte sau eveniment dezactivat: nu e conflict
        self.assertEqual(self.add("07:00", "off", days=[3]).status_code, 200)
        self.assertEqual(self.add("07:00", "off", enabled=False).status_code, 200)

    def test_update_into_a_conflict_is_400(self):
        self.add("07:00", "on")
        eid = self.add("08:00", "off").get_json()["events"][-1]["id"]
        r = self.c.put(f"/plugs/bfplug1/events/{eid}", json={"time": "07:00"}, headers=self.h)
        self.assertEqual(r.status_code, 400)
        self.assertTrue(r.get_json()["error"].startswith("conflict"), r.get_json())

    def test_limit_of_12_events_is_400_with_the_message(self):
        for i in range(12):
            self.assertEqual(self.add(f"{i:02d}:00", "on").status_code, 200, i)
        r = self.add("13:00", "on")
        self.assertEqual(r.status_code, 400)
        self.assertEqual(r.get_json(), {"error": "cel mult 12 evenimente pe priza"})

    def test_unknown_event_is_404(self):
        r = self.c.put("/plugs/bfplug1/events/zz99", json={"enabled": False}, headers=self.h)
        self.assertEqual(r.status_code, 404)
        self.assertTrue(r.get_json()["error"].startswith("eveniment necunoscut"), r.get_json())
        r = self.c.delete("/plugs/bfplug1/events/zz99", headers=self.h)
        self.assertEqual(r.status_code, 404)
        self.assertTrue(r.get_json()["error"].startswith("eveniment necunoscut"), r.get_json())

    def test_events_are_listed_by_time_then_days(self):
        self.add("18:00", "off")
        self.add("07:00", "on", days=[6, 7])
        self.add("07:00", "on", days=[1, 2])
        times = [(e["time"], e["days"]) for e in
                 self.c.get("/plugs", headers=self.h).get_json()["plugs"][0]["events"]]
        self.assertEqual(times, [("07:00", [1, 2]), ("07:00", [6, 7]), ("18:00", None)])


if __name__ == "__main__":
    unittest.main()
