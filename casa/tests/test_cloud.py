import contextlib
import io
import json
import os
import stat
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

from casa import __main__ as cli
from casa.cloud import CLIENT_ID, SCHEMA, CloudSession, LoginFailed
from casa.devices import load_devices

KEY = "SECRETKEY1234567"
STRATEGY = {
    1: {"status_code": "switch_1", "config_item": {"valueDesc": "{}"}},
    9: {"status_code": "countdown_1", "config_item": {"valueDesc": "{\"scale\":0}"}},
    18: {"status_code": "cur_current", "config_item": {"valueDesc": "{\"scale\":0}"}},
    19: {"status_code": "cur_power", "config_item": {"valueDesc": "{\"scale\":1}"}},
    20: {"status_code": "cur_voltage", "config_item": {"valueDesc": "{\"scale\":1}"}},
}


def dev(dev_id, name, category, status=None, local_strategy=None, status_range=None,
        update_time=None):
    return SimpleNamespace(
        id=dev_id, name=name, category=category, local_key=KEY, online=True,
        status=dict(status or {}), local_strategy=local_strategy or {},
        status_range=status_range or {}, update_time=update_time, support_local=False)


def rng(scale):
    return SimpleNamespace(values=json.dumps({"unit": "", "min": -400, "max": 1000, "scale": scale}))


def sensor(dev_id="s1", name="Temp BECI", status=None, **kw):
    status = {"va_temperature": 215, "va_humidity": 55, "battery_state": "middle"} if status is None else status
    return dev(dev_id, name, "wsdcg", status=status, status_range={"va_temperature": rng(1)}, **kw)


class FakeClient:
    def __init__(self, connected):
        self.connected = connected

    def is_connected(self):
        return self.connected


class FakeManager:
    devices = []
    created = []

    def __init__(self, client_id, user_code, terminal_id, endpoint, token_response, listener):
        self.args = (client_id, user_code, terminal_id, endpoint, token_response, listener)
        self.device_map = {}
        self.device_listeners = []
        self.cache_updates = 0
        self.mq = None
        self.mq_connected = True
        FakeManager.created.append(self)

    def update_device_cache(self):
        self.cache_updates += 1
        self.device_map = {d.id: d for d in FakeManager.devices}

    def add_device_listener(self, listener):
        self.device_listeners.append(listener)

    def refresh_mq(self):
        self.mq = SimpleNamespace(client=FakeClient(self.mq_connected), stopped=False, listeners=[],
                                  subscribed=[d.id for d in self.device_map.values() if getattr(d, "set_up", False)])
        self.mq.stop = lambda: setattr(self.mq, "stopped", True)
        self.mq.add_message_listener = self.mq.listeners.append

    def raw(self, msg):
        for lst in self.mq.listeners:
            lst(msg)

    def report(self, device_id, status, timestamps_ms=None):
        d = self.device_map[device_id]
        d.status.update(status)
        for lst in self.device_listeners:
            lst.update_device(d, list(status), timestamps_ms or {})


class FakeLogin:
    def __init__(self, results):
        self.results = list(results)
        self.qr_calls = []
        self.polls = 0

    def qr_code(self, client_id, schema, user_code):
        self.qr_calls.append((client_id, schema, user_code))
        return {"success": True, "result": {"qrcode": "QRTOKEN123"}}

    def login_result(self, token, client_id, user_code):
        self.polls += 1
        assert token == "QRTOKEN123" and client_id == CLIENT_ID
        return self.results.pop(0) if self.results else (False, {"msg": "wait"})


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t

    def sleep(self, s):
        self.t += s


SESSION = {"user_code": "UC", "terminal_id": "TERM", "endpoint": "https://ep",
           "token_info": {"t": 1, "uid": "u", "expire_time": 7200,
                          "access_token": "AT", "refresh_token": "RT"}}


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name
        self.clock = Clock()
        self.notes = []
        FakeManager.devices = []
        FakeManager.created = []

    def tearDown(self):
        self._tmp.cleanup()

    def session(self, with_session=True, **kw):
        cs = CloudSession(self.dir, lambda t, m, p: self.notes.append((t, m, p)), clock=self.clock,
                          manager_factory=FakeManager, sleep=self.clock.sleep, **kw)
        if with_session:
            cs._store.save(SESSION)
        return cs

    def started(self, devices=None):
        FakeManager.devices = devices if devices is not None else [sensor()]
        cs = self.session()
        cs._start_once()
        return cs, FakeManager.created[-1]


class SyncTest(Base):
    def test_sync_writes_devices_json_mode_600_and_classifies_kinds(self):
        FakeManager.devices = [
            dev("p1", "Boiler 10.30 - 16.00", "cz", status={"cur_power": 5}, local_strategy=STRATEGY),
            dev("p2", "Perete", "kg"), dev("p3", "Cam", "pc"),
            sensor(), dev("x1", "Bec", "dj"),
        ]
        cs = self.session()
        result = cs.sync()
        path = os.path.join(self.dir, "devices.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        kinds = {d.id: d.kind for d in result}
        self.assertEqual(kinds, {"p1": "plug", "p2": "plug", "p3": "plug", "s1": "sensor", "x1": "other"})
        saved = {d.id: d for d in load_devices(self.dir)}
        self.assertEqual(saved["p1"].name, "Boiler")
        self.assertEqual(saved["p1"].raw_name, "Boiler 10.30 - 16.00")
        self.assertEqual(saved["p1"].category, "cz")
        self.assertEqual(saved["p1"].local_key, KEY)
        self.assertEqual(saved["p1"].dps["power"], 19)
        self.assertEqual(saved["p1"].scales["power"], 1)
        self.assertEqual(saved["s1"].name, "Temp BECI")
        self.assertEqual(saved["x1"].kind, "other")

    def test_kind_from_codes_when_category_is_not_a_plug(self):
        # Reale (2026-11-05): "T34-Smart Plug" si senzorii "T & H Sensor" au categoria "tdq".
        t34 = {1: {"status_code": "switch_1", "config_item": {}}, 9: {"status_code": "countdown_1", "config_item": {}}}
        th = dev("s2", "Temp Etaj", "tdq")
        th.product_name = "T & H Sensor"
        FakeManager.devices = [
            dev("p4", "Lumina Curte", "tdq", status={"switch_1": False, "countdown_1": 0}, local_strategy=t34),
            th, dev("s3", "Temp", "tdq", status={"temp_current": 215}), dev("x2", "Necunoscut", "tdq"),
        ]
        kinds = {d.id: d.kind for d in self.session().sync()}
        self.assertEqual(kinds, {"p4": "plug", "s2": "sensor", "s3": "sensor", "x2": "other"})
        p4 = {d.id: d for d in load_devices(self.dir)}["p4"]
        self.assertEqual(p4.dps, {"switch": 1, "countdown": 9})

    def test_sync_without_strategy_does_not_invent_metering(self):
        FakeManager.devices = [dev("p2", "Perete", "kg", status={"switch_1": True, "countdown_1": 0})]
        self.session().sync()
        d = load_devices(self.dir)[0]
        self.assertIn("switch", d.dps)
        self.assertNotIn("power", d.dps)

    def test_sync_without_session_raises(self):
        with self.assertRaises(LoginFailed):
            self.session(with_session=False).sync()

    def test_sync_cli_output_never_contains_local_key(self):
        FakeManager.devices = [
            dev("bf5cc0f2db15b9eab2a0zy", "Boiler", "cz", status={"cur_power": 5}, local_strategy=STRATEGY),
            sensor(),
        ]
        cs = self.session()
        out = io.StringIO()
        cfg = SimpleNamespace(data_dir=self.dir, ntfy_url="")
        with mock.patch.object(cli, "_cloud", return_value=cs), \
                mock.patch.object(cli.Config, "from_env", return_value=cfg), contextlib.redirect_stdout(out):
            self.assertEqual(cli.main(["sync"]), 0)
        text = out.getvalue()
        self.assertNotIn(KEY, text)
        self.assertIn("Boiler", text)
        self.assertIn("bf5cc0f2db15b9eab2a0zy", text)
        self.assertIn("Temp BECI", text)
        with open(os.path.join(self.dir, "devices.json")) as f:
            self.assertIn(KEY, f.read())  # cheia e in fisier, dar nu pe ecran


class SensorTest(Base):
    def test_sensor_values_scaled_and_battery_mapped(self):
        cs, _ = self.started([
            sensor("s1", "A", {"va_temperature": 215, "va_humidity": 55, "battery_state": "middle"}),
            sensor("s2", "B", {"va_temperature": -35, "va_humidity": 80, "battery_percentage": 87}),
            sensor("s3", "C", {"va_temperature": 200, "va_humidity": 10, "battery_state": "low"}),
            sensor("s4", "D", {"va_temperature": 190, "va_humidity": 40, "battery_state": "high"}),
            sensor("s5", "E", {}),
        ])
        by = {s["id"]: s for s in cs.sensors()}
        self.assertEqual(by["s1"]["temperature"], 21.5)
        self.assertEqual(by["s1"]["humidity"], 55)
        self.assertEqual(by["s1"]["battery"], 50)
        self.assertEqual(by["s1"]["name"], "A")
        self.assertEqual(by["s2"]["temperature"], -3.5)
        self.assertEqual(by["s2"]["battery"], 87)
        self.assertEqual(by["s3"]["battery"], 10)
        self.assertEqual(by["s4"]["battery"], 100)
        self.assertIsNone(by["s5"]["temperature"])
        self.assertIsNone(by["s5"]["battery"])

    def test_scale_zero_from_status_range_and_default_one(self):
        d0 = dev("s1", "A", "wsdcg", status={"va_temperature": 21, "va_humidity": 50},
                 status_range={"va_temperature": rng(0)})
        d1 = dev("s2", "B", "wsdcg", status={"va_temperature": 215, "va_humidity": 50})
        cs, _ = self.started([d0, d1])
        by = {s["id"]: s for s in cs.sensors()}
        self.assertEqual(by["s1"]["temperature"], 21.0)
        self.assertEqual(by["s2"]["temperature"], 21.5)

    def test_plugs_are_not_sensors(self):
        cs, _ = self.started([dev("p1", "Boiler", "cz"), sensor()])
        self.assertEqual([s["id"] for s in cs.sensors()], ["s1"])

    def test_mqtt_update_changes_value_and_calls_hook(self):
        cs, mgr = self.started()
        seen = []
        cs.on_sensor_update = seen.append
        self.clock.t += 10
        mgr.report("s1", {"va_temperature": 230}, {"va_temperature": int((self.clock.t - 3) * 1000)})
        self.assertEqual(cs.sensors()[0]["temperature"], 23.0)
        self.assertEqual(cs.sensors()[0]["updated"], self.clock.t - 3)
        self.assertEqual(len(seen), 1)
        self.assertEqual(seen[0]["id"], "s1")
        self.assertEqual(seen[0]["temperature"], 23.0)

    def test_online_event_without_status_does_not_touch_updated(self):
        cs, mgr = self.started()
        before = cs.sensors()[0]["updated"]
        self.clock.t += 600
        mgr.device_listeners[0].update_device(mgr.device_map["s1"], [], {})
        self.assertEqual(cs.sensors()[0]["updated"], before)

    def test_sensors_report_updated_epoch_not_now(self):
        cs, mgr = self.started()
        t0 = self.clock.t
        mgr.report("s1", {"va_temperature": 210})  # fara timestamp DP: ora primirii
        self.assertEqual(cs.sensors()[0]["updated"], t0)
        self.clock.t += 6 * 3600
        self.assertEqual(cs.sensors()[0]["updated"], t0)
        self.assertEqual(cs.sensors()[0]["updated"], t0)
        # timestamp DP din SDK (milisecunde) are prioritate
        mgr.report("s1", {"va_humidity": 51}, {"va_humidity": int((t0 + 5) * 1000)})
        self.assertEqual(cs.sensors()[0]["updated"], t0 + 5)

    def test_cache_refresh_uses_device_update_time_and_never_now(self):
        t0 = self.clock.t - 6 * 3600
        cs, _ = self.started([sensor(update_time=int(t0))])
        self.assertEqual(cs.sensors()[0]["updated"], t0)

    def test_cache_refresh_unknown_time_is_none_not_now(self):
        cs, _ = self.started([sensor()])
        self.assertIsNone(cs.sensors()[0]["updated"])


def th_sensor(dev_id="s2", name="Temp Etaj", update_time=1743546011):
    """Senzorul real "T & H Sensor": categoria tdq, fara stare si fara strategie prin API-ul de partajare."""
    d = dev(dev_id, name, "tdq", update_time=update_time)
    d.product_name = "T & H Sensor"
    return d


def dp_msg(dev_id, items, t_ms):
    return {"protocol": 4, "data": {"devId": dev_id,
                                    "status": [{"dpId": k, "t": t_ms, "value": v} for k, v in items]}}


class RawMqttSensorTest(Base):
    """Senzorii tdq isi trimit valorile doar prin MQTT, pe DP 27/46/101 (masurat 2026-11-05)."""

    def test_sensor_subscribed_and_dp_report_decoded(self):
        cs, mgr = self.started([th_sensor(), dev("p1", "Boiler", "cz", status={"switch_1": False})])
        self.assertEqual(mgr.mq.subscribed, ["s2"])
        seen = []
        cs.on_sensor_update = seen.append
        t = self.clock.t - 2
        mgr.raw(dp_msg("s2", [(27, 201), (46, 57), (101, "high")], int(t * 1000)))
        s = cs.sensors()[0]
        self.assertEqual((s["temperature"], s["humidity"], s["battery"], s["updated"]), (20.1, 57.0, 100.0, t))
        self.assertEqual(seen[-1]["temperature"], 20.1)

    def test_first_cache_read_without_values_has_no_updated(self):
        cs, _ = self.started([th_sensor()])
        s = cs.sensors()[0]
        self.assertEqual((s["temperature"], s["humidity"], s["updated"]), (None, None, None))

    def test_partial_report_and_empty_rest_refresh_keep_values(self):
        cs, mgr = self.started([th_sensor()])
        t = self.clock.t
        mgr.raw(dp_msg("s2", [(27, 201), (46, 57)], int(t * 1000)))
        mgr.raw(dp_msg("s2", [(27, 195)], int((t + 60) * 1000)))
        self.assertEqual((cs.sensors()[0]["temperature"], cs.sensors()[0]["humidity"]), (19.5, 57.0))
        FakeManager.devices = [th_sensor()]          # REST: obiecte noi, tot fara stare
        self.clock.t += 400
        cs._tick()
        self.assertEqual(mgr.cache_updates, 2)
        s = cs.sensors()[0]
        self.assertEqual((s["temperature"], s["humidity"], s["updated"]), (19.5, 57.0, t + 60))
        mgr.raw(dp_msg("s2", [(46, 60)], int((t + 500) * 1000)))   # dupa refresh: obiect nou
        self.assertEqual((cs.sensors()[0]["temperature"], cs.sensors()[0]["humidity"]), (19.5, 60.0))

    def test_seeded_values_survive_first_empty_cache_read(self):
        FakeManager.devices = [th_sensor()]
        cs = self.session()
        cs.seed_sensors([{"id": "s2", "name": "Temp Etaj", "temperature": 20.1, "humidity": 57.0,
                          "battery": 100.0, "updated": 123.0},
                         {"id": "sters", "name": "Vechi", "temperature": 1.0, "humidity": None,
                          "battery": None, "updated": 1.0}], {"s2"})
        cs._start_once()
        self.assertEqual([(s["id"], s["temperature"], s["updated"]) for s in cs.sensors()], [("s2", 20.1, 123.0)])

    def test_other_messages_ignored(self):
        cs, mgr = self.started([th_sensor(), dev("p1", "Boiler", "cz", status={"switch_1": False})])
        mgr.raw(dp_msg("p1", [(1, True)], 1))
        mgr.raw(dp_msg("s2", [(99, 5)], 1))
        mgr.raw(dp_msg("necunoscut", [(27, 5)], 1))
        mgr.raw({"protocol": 20, "data": {"devId": "s2"}})
        mgr.raw({"protocol": 4, "data": None})
        self.assertIsNone(cs.sensors()[0]["temperature"])


class StateTest(Base):
    def test_no_session(self):
        cs = self.session(with_session=False)
        self.assertEqual(cs.state(), "no_session")
        cs.check_health()
        self.clock.t += 7200
        cs.check_health()
        self.assertEqual(self.notes, [])

    def test_ok_after_recent_rest_refresh(self):
        cs, mgr = self.started()
        self.assertEqual(cs.state(), "ok")

    def test_mqtt_connected_does_not_make_state_ok_without_rest_refresh(self):
        cs, mgr = self.started()
        self.assertTrue(mgr.mq.client.is_connected())
        self.clock.t += 601
        self.assertEqual(cs.state(), "disconnected")  # MQTT conectat dar fara date: nu conteaza

    def test_disconnected_without_mqtt_and_old_refresh_ok_with_recent_refresh(self):
        cs, mgr = self.started()
        mgr.mq.client.connected = False
        self.assertEqual(cs.state(), "ok")  # refresh reusit acum 0 s
        self.clock.t += 601
        self.assertEqual(cs.state(), "disconnected")

    def test_state_disconnected_notifies_once_after_30_min_and_again_after_recovery(self):
        cs, mgr = self.started()
        mgr.mq.client.connected = False
        self.clock.t += 700
        self.assertEqual(cs.state(), "disconnected")
        cs.check_health()
        self.clock.t += 1700  # ~40 min de la ultimul ok, dar doar 28 min de cand e vazut cazut
        cs.check_health()
        self.assertEqual(self.notes, [])
        self.clock.t += 200
        cs.check_health()
        self.assertEqual(self.notes, [("CASA: cloud Tuya deconectat",
                                       "Senzorii nu mai primesc date. Refa login: python -m casa login",
                                       "high")])
        self.clock.t += 3600
        cs.check_health()
        self.assertEqual(len(self.notes), 1)  # o singura data
        cs._refresh_cache()  # revenire: o citire REST reusita
        self.assertEqual(cs.state(), "ok")
        cs.check_health()
        self.clock.t += 700
        cs.check_health()
        self.clock.t += 1900
        cs.check_health()
        self.assertEqual(len(self.notes), 2)

    def test_rest_refresh_every_5_min_at_most(self):
        cs, mgr = self.started()
        n = mgr.cache_updates
        cs._tick()
        self.assertEqual(mgr.cache_updates, n)  # abia s-a citit
        self.clock.t += 301
        cs._tick()
        self.assertEqual(mgr.cache_updates, n + 1)
        self.clock.t += 60
        cs._tick()
        self.assertEqual(mgr.cache_updates, n + 1)
        self.clock.t += 250
        cs._tick()
        self.assertEqual(mgr.cache_updates, n + 2)

    def test_sensor_messages_do_not_postpone_rest_refresh(self):
        cs, mgr = self.started()
        n = mgr.cache_updates
        self.clock.t += 250
        mgr.report("s1", {"va_humidity": 56})
        self.clock.t += 250
        cs._tick()
        self.assertEqual(mgr.cache_updates, n + 1)  # starea "ok" depinde de citirea REST

    def test_only_sensor_messages_update_last_msg(self):
        cs, mgr = self.started([sensor(), dev("p1", "Priza", "cz", status={})])
        t0 = cs._last_msg
        self.clock.t += 50
        mgr.device_listeners[0].update_device(mgr.device_map["p1"], ["switch_1"], {})
        self.assertEqual(cs._last_msg, t0)
        mgr.report("s1", {"va_humidity": 56})
        self.assertEqual(cs._last_msg, t0 + 50)

    def test_failed_cache_refresh_does_not_count_as_ok(self):
        cs, mgr = self.started()
        mgr.mq.client.connected = False
        self.clock.t += 700
        mgr.update_device_cache = mock.Mock(side_effect=OSError("retea"))
        with self.assertLogs("casa.cloud", level="WARNING"):
            cs._tick()
        self.assertEqual(cs.state(), "disconnected")


class RelogginTest(Base):
    def test_new_login_on_disk_rebuilds_manager(self):
        cs, old = self.started()
        self.assertEqual(len(FakeManager.created), 1)
        new = dict(SESSION, terminal_id="TERM2",
                   token_info=dict(SESSION["token_info"], access_token="AT-NOU", refresh_token="RT-NOU"))
        cs._store.save(new)  # `python -m casa login` din alt proces
        cs._tick()
        self.assertEqual(len(FakeManager.created), 2)
        mgr = FakeManager.created[-1]
        self.assertEqual(mgr.args[2], "TERM2")
        self.assertEqual(mgr.args[4]["access_token"], "AT-NOU")
        self.assertTrue(old.mq.stopped)
        self.assertIsNotNone(mgr.mq)  # MQTT repornit pe managerul nou
        self.assertEqual(cs.state(), "ok")

    def test_own_token_refresh_does_not_rebuild(self):
        cs, mgr = self.started()
        mgr.args[5].update_token({"t": 5, "uid": "u", "expire_time": 7200,
                                  "access_token": "AT2", "refresh_token": "RT2"})
        cs._tick()
        self.assertEqual(len(FakeManager.created), 1)

    def test_failure_with_dead_mqtt_rebuilds_on_next_tick(self):
        cs, mgr = self.started()
        mgr.mq.client.connected = False
        self.clock.t += 400
        mgr.update_device_cache = mock.Mock(side_effect=OSError("SECRET"))
        with self.assertLogs("casa.cloud", level="WARNING"):
            cs._tick()
        cs._tick()
        self.assertEqual(len(FakeManager.created), 2)

    def test_failure_with_live_mqtt_keeps_manager(self):
        cs, mgr = self.started()
        self.clock.t += 400
        mgr.update_device_cache = mock.Mock(side_effect=OSError("retea"))
        with self.assertLogs("casa.cloud", level="WARNING"):
            cs._tick()
        self.assertEqual(len(FakeManager.created), 1)

    def test_state_does_not_reparse_file_while_unchanged(self):
        cs, _ = self.started()
        with mock.patch.object(cs._store, "load", wraps=cs._store.load) as load:
            for _ in range(5):
                cs.state()
        self.assertEqual(load.call_count, 0)
        cs._store.save(dict(SESSION, terminal_id="T3"))
        with mock.patch.object(cs._store, "load", wraps=cs._store.load) as load:
            cs.state()
            cs.state()
        self.assertEqual(load.call_count, 1)


class PollingHookTest(Base):
    def test_values_found_by_cache_polling_reach_hook(self):
        FakeManager.devices = [sensor(update_time=int(self.clock.t - 100))]
        cs = self.session()
        seen = []
        cs.on_sensor_update = seen.append
        cs._start_once()
        self.assertEqual(len(seen), 1)  # la pornire
        self.assertEqual(seen[0]["temperature"], 21.5)
        self.clock.t += 301
        cs._tick()  # nimic schimbat -> fara apel
        self.assertEqual(len(seen), 1)
        FakeManager.devices = [sensor(status={"va_temperature": 240, "va_humidity": 55,
                                              "battery_state": "middle"},
                                      update_time=int(self.clock.t - 5))]
        self.clock.t += 301
        cs._tick()
        self.assertEqual(len(seen), 2)
        self.assertEqual(seen[1]["temperature"], 24.0)
        self.assertEqual(seen[1]["updated"], int(self.clock.t - 306))

    def test_hook_failure_is_logged_without_exception_text(self):
        FakeManager.devices = [sensor()]
        cs = self.session()

        def boom(entry):
            raise RuntimeError("SECRET-influx-token")
        cs.on_sensor_update = boom
        with self.assertLogs("casa.cloud", level="WARNING") as cm:
            cs._start_once()
        self.assertNotIn("SECRET", "\n".join(cm.output))

    def test_sensor_with_none_name_sorts(self):
        cs, _ = self.started([sensor("s1", None), sensor("s2", "Alfa")])
        self.assertEqual({s["id"] for s in cs.sensors()}, {"s1", "s2"})


class _Capture:
    def __enter__(self):
        import logging
        self.records = []
        self.handler = logging.Handler()
        self.handler.emit = lambda r: self.records.append(self.handler.format(r))
        logging.getLogger().addHandler(self.handler)
        self._old = logging.getLogger().level
        logging.getLogger().setLevel(logging.DEBUG)
        return self

    def __exit__(self, *a):
        import logging
        logging.getLogger().removeHandler(self.handler)
        logging.getLogger().setLevel(self._old)


class SecretsTest(Base):
    def test_cli_login_failure_prints_no_exception_text(self):
        class Boom(FakeLogin):
            def qr_code(self, *a):
                raise RuntimeError("https://x/?token=SECRET&usercode=UC")
        cs = self.session(with_session=False, login_control=Boom([]))
        out, err = io.StringIO(), io.StringIO()
        cfg = SimpleNamespace(data_dir=self.dir, ntfy_url="")
        with _Capture() as cap, mock.patch.object(cli, "_cloud", return_value=cs), \
                mock.patch.object(cli.Config, "from_env", return_value=cfg), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(cli.main(["login", "UC"]), 1)
        everything = out.getvalue() + err.getvalue() + "\n".join(cap.records)
        self.assertNotIn("SECRET", everything)
        self.assertIn("RuntimeError", err.getvalue())

    def test_cli_sync_failure_prints_no_exception_text(self):
        FakeManager.devices = []
        cs = self.session()
        cs._ensure_manager().update_device_cache = mock.Mock(side_effect=OSError("url?token=SECRET"))
        out, err = io.StringIO(), io.StringIO()
        cfg = SimpleNamespace(data_dir=self.dir, ntfy_url="")
        with _Capture() as cap, mock.patch.object(cli, "_cloud", return_value=cs), \
                mock.patch.object(cli.Config, "from_env", return_value=cfg), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(cli.main(["sync"]), 1)
        self.assertNotIn("SECRET", out.getvalue() + err.getvalue() + "\n".join(cap.records))

    def test_login_result_error_is_logged_by_type_only(self):
        class Boom(FakeLogin):
            def login_result(self, *a):
                self.polls += 1
                raise RuntimeError("get https://x/tokens/SECRET")
        cs = self.session(with_session=False, login_control=Boom([]))
        with _Capture() as cap:
            with self.assertRaises(LoginFailed):
                cs.login_interactive("UC", out=io.StringIO())
        self.assertTrue(cap.records)
        self.assertNotIn("SECRET", "\n".join(cap.records))

    def test_tick_failure_is_logged_by_type_only(self):
        cs, mgr = self.started()
        mgr.mq.client.connected = False
        self.clock.t += 400
        mgr.update_device_cache = mock.Mock(side_effect=OSError("url SECRET"))
        with _Capture() as cap:
            cs._tick()
        self.assertTrue(cap.records)
        self.assertNotIn("SECRET", "\n".join(cap.records))

    def test_notify_failure_is_logged_by_type_only(self):
        def boom(*a):
            raise RuntimeError("SECRET")
        cs, mgr = self.started()
        cs._notify = boom
        mgr.mq.client.connected = False
        self.clock.t += 700
        cs.check_health()
        self.clock.t += 1900
        with _Capture() as cap:
            cs.check_health()
        self.assertTrue(cap.records)
        self.assertNotIn("SECRET", "\n".join(cap.records))


class LoginTest(Base):
    def test_login_saves_session_600_and_syncs(self):
        FakeManager.devices = [sensor()]
        login = FakeLogin([(False, {"msg": "wait"}),
                           (True, {"t": 111, "uid": "U1", "expire_time": 7200, "access_token": "AT9",
                                   "refresh_token": "RT9", "terminal_id": "TERM9",
                                   "endpoint": "https://ep9"})])
        cs = self.session(with_session=False, login_control=login)
        out = io.StringIO()
        cs.login_interactive("UC1", out=out)
        self.assertEqual(login.qr_calls, [(CLIENT_ID, SCHEMA, "UC1")])
        self.assertEqual(login.polls, 2)
        self.assertGreater(len(out.getvalue()), 200)  # QR ASCII
        self.assertNotIn("AT9", out.getvalue())
        path = os.path.join(self.dir, "cloud_session.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        with open(path) as f:
            data = json.load(f)
        self.assertEqual(data["terminal_id"], "TERM9")
        self.assertEqual(data["endpoint"], "https://ep9")
        self.assertEqual(data["user_code"], "UC1")
        self.assertEqual(data["token_info"]["refresh_token"], "RT9")
        self.assertEqual(FakeManager.created[-1].args[:4], (CLIENT_ID, "UC1", "TERM9", "https://ep9"))
        self.assertTrue(os.path.exists(os.path.join(self.dir, "devices.json")))

    def test_login_times_out_after_120_s_polling_every_2_s(self):
        login = FakeLogin([])
        cs = self.session(with_session=False, login_control=login)
        with self.assertRaises(LoginFailed):
            cs.login_interactive("UC1", out=io.StringIO())
        self.assertEqual(login.polls, 60)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "cloud_session.json")))

    def test_login_qr_failure_raises(self):
        login = FakeLogin([])
        login.qr_code = lambda *a: {"success": False, "msg": "user_code gresit"}
        cs = self.session(with_session=False, login_control=login)
        with self.assertRaises(LoginFailed):
            cs.login_interactive("UC1", out=io.StringIO())

    def test_refreshed_token_is_saved_by_listener(self):
        cs, mgr = self.started()
        mgr.args[5].update_token({"t": 5, "uid": "u", "expire_time": 7200,
                                  "access_token": "AT2", "refresh_token": "RT2"})
        path = os.path.join(self.dir, "cloud_session.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        with open(path) as f:
            data = json.load(f)
        self.assertEqual(data["token_info"]["access_token"], "AT2")
        self.assertEqual(data["terminal_id"], "TERM")


class DemoTest(unittest.TestCase):
    def _run(self, d, *args):
        out, err = io.StringIO(), io.StringIO()
        cfg = SimpleNamespace(data_dir=d, ntfy_url="")
        with mock.patch.object(cli.Config, "from_env", return_value=cfg), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            return cli.main(["demo-devices", *args]), err.getvalue()

    def test_demo_refuses_to_overwrite_real_keys_without_force(self):
        from casa.devices import DeviceInfo, save_devices
        with tempfile.TemporaryDirectory() as d:
            save_devices(d, [DeviceInfo(id="p1", name="Boiler", kind="plug", local_key=KEY,
                                        ip=None, version=None)])
            rc, err = self._run(d)
            self.assertEqual(rc, 1)
            self.assertIn("--force", err)
            self.assertEqual(load_devices(d)[0].local_key, KEY)
            rc, _ = self._run(d, "--force")
            self.assertEqual(rc, 0)
            self.assertEqual(len(load_devices(d)), 8)

    def test_demo_overwrites_file_without_keys_and_refuses_corrupt(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self._run(d)[0], 0)
            self.assertEqual(self._run(d)[0], 0)  # fara chei: se poate repeta
            with open(os.path.join(d, "devices.json"), "w") as f:
                f.write("{corupt")
            self.assertEqual(self._run(d)[0], 1)

    def test_demo_devices_writes_five_plugs_and_three_sensors_without_keys(self):
        with tempfile.TemporaryDirectory() as d:
            out = io.StringIO()
            cfg = SimpleNamespace(data_dir=d, ntfy_url="")
            with mock.patch.object(cli.Config, "from_env", return_value=cfg), contextlib.redirect_stdout(out):
                self.assertEqual(cli.main(["demo-devices"]), 0)
            devs = load_devices(d)
            plugs = [x for x in devs if x.kind == "plug"]
            sens = [x for x in devs if x.kind == "sensor"]
            self.assertEqual([p.name for p in plugs],
                             ["Boiler", "Living sus", "Priza cam Georgi", "Bucatarie", "Lumina Curte"])
            self.assertEqual(sorted(s.name for s in sens), ["Temp BECI", "Temp Etaj", "Temp afara"])
            self.assertTrue(all(x.local_key is None for x in devs))
            self.assertIn("power", plugs[0].dps)
            self.assertNotIn("power", plugs[3].dps)
            self.assertTrue(all(p.ip for p in plugs))  # IP din valorile implicite
            self.assertEqual(stat.S_IMODE(os.stat(os.path.join(d, "devices.json")).st_mode), 0o600)

    def test_demo_devices_has_one_tasmota(self):
        from casa.devices import TASMOTA_DPS, TASMOTA_SCALES
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self._run(d)[0], 0)
            plugs = [x for x in load_devices(d) if x.kind == "plug"]
            tas = [p for p in plugs if p.driver == "tasmota"]
            self.assertEqual(len(tas), 1)
            self.assertEqual(tas[0].dps, TASMOTA_DPS)
            self.assertEqual(tas[0].scales, TASMOTA_SCALES)
            self.assertEqual(tas[0].name, "Living sus")           # numele ramane
            self.assertTrue(all(p.driver == "tuya" for p in plugs if p is not tas[0]))
            self.assertTrue(all(x.driver == "tuya" for x in load_devices(d) if x.kind == "sensor"))


if __name__ == "__main__":
    unittest.main()
