import json
import os
import stat
import tempfile
import unittest

from casa.devices import (DRIVERS, TASMOTA_DPS, TASMOTA_SCALES, DeviceInfo, apply_scan, clean_name,
                          dp_map_from_strategy, load_devices, local_names, save_devices,
                          set_local_name)


class CleanNameTest(unittest.TestCase):
    def test_clean_name_cases(self):
        self.assertEqual(clean_name("Boiler  10.30 - 16.00"), "Boiler")
        self.assertEqual(clean_name("Living sus: 11:00 - 16.25"), "Living sus")
        self.assertEqual(clean_name("Priza cam Georgi"), "Priza cam Georgi")
        self.assertEqual(clean_name("Boiler 11:30 - 16:00 ."), "Boiler")


class DpMapTest(unittest.TestCase):
    def test_dp_map_from_strategy_reads_codes_and_scale(self):
        strategy = {
            "1": {"status_code": "switch_1", "config_item": {"valueDesc": "{}"}},
            "9": {"status_code": "countdown_1",
                  "config_item": {"valueDesc": "{\"unit\":\"s\",\"scale\":0}"}},
            "18": {"status_code": "cur_current",
                   "config_item": {"valueDesc": "{\"scale\":0}"}},
            "19": {"status_code": "cur_power",
                   "config_item": {"valueDesc": "{\"scale\":1,\"unit\":\"W\"}"}},
            "20": {"status_code": "cur_voltage",
                   "config_item": {"valueDesc": "{\"scale\":1}"}},
            "38": {"status_code": "relay_status", "config_item": {}},
        }
        dps, scales = dp_map_from_strategy(strategy)
        self.assertEqual(dps, {"switch": 1, "countdown": 9, "current": 18,
                               "power": 19, "voltage": 20})
        self.assertEqual(scales["power"], 1)
        self.assertEqual(scales["voltage"], 1)
        self.assertEqual(scales["current"], 0)

    def test_dp_map_fallback_when_empty(self):
        dps, scales = dp_map_from_strategy({})
        self.assertEqual(dps, {"switch": 1, "countdown": 9, "current": 18,
                               "power": 19, "voltage": 20})
        self.assertEqual(scales, {"power": 1, "voltage": 1, "current": 0})


class DeviceReprTest(unittest.TestCase):
    def test_repr_does_not_leak_local_key(self):
        d = DeviceInfo(id="x", name="P", kind="plug", local_key="SECRETKEY123",
                       ip=None, version=None)
        self.assertNotIn("SECRETKEY123", repr(d))
        self.assertNotIn("SECRETKEY123", str(d))


class StoreDevicesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = self.tmp.name

    def _dev(self, dev_id, **kw):
        base = dict(id=dev_id, name="Priza", kind="plug", local_key="k",
                    ip=None, version=None,
                    dps={"switch": 1, "power": 19}, scales={"power": 1})
        base.update(kw)
        return DeviceInfo(**base)

    def test_save_load_roundtrip_mode_600_and_extra_fields(self):
        save_devices(self.dir, [self._dev("bf5cc0f2db15b9eab2a0zy")],
                     raw_names={"bf5cc0f2db15b9eab2a0zy": "Boiler 10.30 - 16.00"},
                     categories={"bf5cc0f2db15b9eab2a0zy": "cz"})
        path = os.path.join(self.dir, "devices.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        with open(path) as f:
            raw = json.load(f)
        self.assertIn("synced_at", raw)
        entry = raw["devices"][0]
        self.assertEqual(entry["raw_name"], "Boiler 10.30 - 16.00")
        self.assertEqual(entry["category"], "cz")
        got = load_devices(self.dir)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0].raw_name, "Boiler 10.30 - 16.00")
        self.assertEqual(got[0].category, "cz")
        self.assertEqual(got[0].dps, {"switch": 1, "power": 19})

    def test_load_devices_missing_file_is_empty(self):
        self.assertEqual(load_devices(self.dir), [])

    def test_load_devices_merges_local_overrides_and_defaults(self):
        save_devices(self.dir, [
            self._dev("bf5cc0f2db15b9eab2a0zy"),                 # lipsesc ip/versiune
            self._dev("bfc30380ff8a29c0d88wcz"),                 # idem, protocol 3.4
            self._dev("bf3897ad46cd66d32cww3j", ip="10.0.0.9", version="3.3"),
            self._dev("necunoscut"),                             # fara default
        ])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump({"bfc30380ff8a29c0d88wcz": {"ip": "192.168.1.250",
                                                  "name": "Boiler nou"}}, f)
        got = {d.id: d for d in load_devices(self.dir)}
        a = got["bf5cc0f2db15b9eab2a0zy"]
        self.assertEqual((a.ip, a.version), ("192.168.1.244", "3.3"))
        b = got["bfc30380ff8a29c0d88wcz"]
        self.assertEqual((b.ip, b.version, b.name),
                         ("192.168.1.250", "3.4", "Boiler nou"))
        c = got["bf3897ad46cd66d32cww3j"]
        self.assertEqual((c.ip, c.version), ("10.0.0.9", "3.3"))
        d = got["necunoscut"]
        self.assertEqual((d.ip, d.version), (None, None))

    def test_bad_local_entries_are_skipped_with_warning(self):
        save_devices(self.dir, [self._dev("bfc30380ff8a29c0d88wcz"), self._dev("bf5cc0f2db15b9eab2a0zy"),
                                self._dev("x3"), self._dev("x4")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump({"bfc30380ff8a29c0d88wcz": "192.168.1.9",
                       "bf5cc0f2db15b9eab2a0zy": {"ip": 5},
                       "x3": {"ip": "10.0.0.3", "name": ["lista"]},
                       "x4": {"ip": "10.0.0.4"}}, f)
        with self.assertLogs("casa.devices", level="WARNING") as cm:
            got = {d.id: d for d in load_devices(self.dir)}
        self.assertEqual(len(cm.records), 3)
        self.assertEqual(got["bfc30380ff8a29c0d88wcz"].ip, "192.168.1.185")   # implicit, nu suprascris
        self.assertEqual(got["bf5cc0f2db15b9eab2a0zy"].ip, "192.168.1.244")
        self.assertIsNone(got["x3"].ip)
        self.assertEqual(got["x4"].ip, "10.0.0.4")                            # intrarea buna merge

    def test_local_file_not_an_object_is_ignored(self):
        save_devices(self.dir, [self._dev("x4")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump(["nu", "obiect"], f)
        with self.assertLogs("casa.devices", level="WARNING"):
            got = load_devices(self.dir)
        self.assertEqual([d.id for d in got], ["x4"])

    def test_malformed_device_entry_is_skipped_with_warning(self):
        save_devices(self.dir, [self._dev("bun")])
        path = os.path.join(self.dir, "devices.json")
        with open(path) as f:
            raw = json.load(f)
        raw["devices"] += ["nu-obiect", {"name": "fara id", "kind": "plug"}, {"id": 7, "name": "id numeric",
                                                                              "kind": "plug", "local_key": None,
                                                                              "ip": None, "version": None},
                           {"id": "fara-nume", "kind": "plug", "local_key": None, "ip": None, "version": None}]
        with open(path, "w") as f:
            json.dump(raw, f)
        with self.assertLogs("casa.devices", level="WARNING") as cm:
            got = load_devices(self.dir)
        self.assertEqual([d.id for d in got], ["bun"])
        self.assertEqual(len(cm.records), 4)


class HiddenDeviceTest(unittest.TestCase):
    setUp = StoreDevicesTest.setUp
    _dev = StoreDevicesTest._dev

    def test_hidden_device_is_not_loaded(self):
        save_devices(self.dir, [self._dev("a"), self._dev("b")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump({"b": {"hidden": True}, "a": {"hidden": False, "name": "Priza A"}}, f)
        got = load_devices(self.dir)
        self.assertEqual([(d.id, d.name) for d in got], [("a", "Priza A")])

    def test_hidden_must_be_bool(self):
        save_devices(self.dir, [self._dev("b")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump({"b": {"hidden": "da"}}, f)
        with self.assertLogs("casa.devices", level="WARNING"):
            self.assertEqual([d.id for d in load_devices(self.dir)], ["b"])


class ApplyScanTest(unittest.TestCase):
    """Scanarea retelei pune IP-ul si protocolul prizelor in devices.local.json."""
    setUp = StoreDevicesTest.setUp
    _dev = StoreDevicesTest._dev

    def _local(self):
        with open(os.path.join(self.dir, "devices.local.json")) as f:
            return json.load(f)

    def test_new_plug_ip_written_and_loaded(self):
        save_devices(self.dir, [self._dev("noua", name="Priza noua")])
        changes, unknown = apply_scan(self.dir, {"noua": {"ip": "192.168.1.77", "version": "3.4"}})
        self.assertEqual(changes, [("Priza noua", "192.168.1.77", "3.4")])
        self.assertEqual(unknown, [])
        self.assertEqual(self._local(), {"noua": {"ip": "192.168.1.77", "version": "3.4"}})
        path = os.path.join(self.dir, "devices.local.json")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        dev = load_devices(self.dir)[0]
        self.assertEqual((dev.ip, dev.version), ("192.168.1.77", "3.4"))

    def test_known_ip_not_rewritten(self):
        save_devices(self.dir, [self._dev("bf5cc0f2db15b9eab2a0zy")])  # default .244 / 3.3
        changes, _ = apply_scan(self.dir, {"bf5cc0f2db15b9eab2a0zy": {"ip": "192.168.1.244", "version": "3.3"}})
        self.assertEqual(changes, [])
        self.assertFalse(os.path.exists(os.path.join(self.dir, "devices.local.json")))

    def test_changed_ip_keeps_name_override(self):
        save_devices(self.dir, [self._dev("bf5cc0f2db15b9eab2a0zy")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump({"bf5cc0f2db15b9eab2a0zy": {"name": "Boiler mare", "ip": "192.168.1.244"}}, f)
        changes, _ = apply_scan(self.dir, {"bf5cc0f2db15b9eab2a0zy": {"ip": "192.168.1.50", "version": "3.3"}})
        self.assertEqual(changes, [("Boiler mare", "192.168.1.50", "3.3")])
        self.assertEqual(self._local()["bf5cc0f2db15b9eab2a0zy"],
                         {"name": "Boiler mare", "ip": "192.168.1.50", "version": "3.3"})

    def test_unknown_device_reported_not_written(self):
        save_devices(self.dir, [self._dev("bf5cc0f2db15b9eab2a0zy")])
        changes, unknown = apply_scan(self.dir, {"strain": {"ip": "192.168.1.9", "version": "3.3"},
                                                 "bf5cc0f2db15b9eab2a0zy": {"ip": "192.168.1.244", "version": "3.3"}})
        self.assertEqual((changes, unknown), ([], [("strain", "192.168.1.9")]))
        self.assertFalse(os.path.exists(os.path.join(self.dir, "devices.local.json")))

    def test_sensor_and_bad_entries_ignored(self):
        save_devices(self.dir, [self._dev("senzor", kind="sensor"), self._dev("p")])
        changes, unknown = apply_scan(self.dir, {"senzor": {"ip": "192.168.1.5", "version": "3.3"},
                                                 "p": {"ip": None, "version": "3.3"}})
        self.assertEqual((changes, unknown), ([], []))

    def test_local_file_not_an_object_is_left_alone(self):
        save_devices(self.dir, [self._dev("p")])
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump(["gresit"], f)
        with self.assertLogs("casa.devices", level="WARNING"):
            with self.assertRaises(ValueError):
                apply_scan(self.dir, {"p": {"ip": "192.168.1.5", "version": "3.3"}})
        self.assertEqual(self._local(), ["gresit"])


class TasmotaDriverTest(unittest.TestCase):
    """devices.local.json poate trece o priza pe driverul Tasmota (acelasi id Tuya) sau poate adauga una noua."""
    setUp = StoreDevicesTest.setUp
    _dev = StoreDevicesTest._dev

    def _write_local(self, data):
        with open(os.path.join(self.dir, "devices.local.json"), "w") as f:
            json.dump(data, f)

    def test_constants(self):
        self.assertEqual(DRIVERS, ("tuya", "tasmota"))
        self.assertEqual(TASMOTA_DPS, {"switch": 1, "countdown": 9, "current": 18, "power": 19,
                                       "voltage": 20, "inching": 44})
        self.assertEqual(TASMOTA_SCALES, {"power": 1, "voltage": 1, "current": 0})

    def test_default_driver_is_tuya(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204", version="3.3")])
        self._write_local({"P1": {"name": "Boiler"}})
        dev = load_devices(self.dir)[0]
        self.assertEqual(dev.driver, "tuya")
        self.assertEqual(dev.dps, {"switch": 1, "power": 19})
        self.assertEqual(dev.scales, {"power": 1})
        self.assertEqual(DeviceInfo(id="x", name="P", kind="plug", local_key=None, ip=None, version=None).driver,
                         "tuya")

    def test_driver_survives_save_load_roundtrip(self):
        save_devices(self.dir, [self._dev("P1")])
        with open(os.path.join(self.dir, "devices.json")) as f:
            self.assertEqual(json.load(f)["devices"][0]["driver"], "tuya")
        self.assertEqual(load_devices(self.dir)[0].driver, "tuya")

    def test_local_tasmota_overrides_tuya_device(self):
        save_devices(self.dir, [self._dev("P1", name="Boiler", ip="192.168.1.204", version="3.3")])
        self._write_local({"P1": {"driver": "tasmota", "ip": "192.168.1.186"}})
        devs = load_devices(self.dir)
        self.assertEqual(len(devs), 1)
        dev = devs[0]
        self.assertEqual(dev.driver, "tasmota")
        self.assertEqual(dev.ip, "192.168.1.186")
        self.assertEqual(dev.dps, TASMOTA_DPS)
        self.assertEqual(dev.scales, TASMOTA_SCALES)
        self.assertEqual(dev.id, "P1")
        self.assertEqual(dev.name, "Boiler")
        self.assertEqual(dev.kind, "plug")

    def test_tasmota_maps_are_copies(self):
        save_devices(self.dir, [self._dev("P1")])
        self._write_local({"P1": {"driver": "tasmota", "ip": "192.168.1.186"}})
        dev = load_devices(self.dir)[0]
        dev.dps["switch"] = 99
        dev.scales["power"] = 99
        self.assertEqual(TASMOTA_DPS["switch"], 1)
        self.assertEqual(TASMOTA_SCALES["power"], 1)

    def test_local_tasmota_override_keeps_name_override(self):
        save_devices(self.dir, [self._dev("P1", name="Boiler")])
        self._write_local({"P1": {"driver": "tasmota", "ip": "192.168.1.186", "name": "Boiler nou"}})
        dev = load_devices(self.dir)[0]
        self.assertEqual((dev.driver, dev.name), ("tasmota", "Boiler nou"))

    def test_local_tasmota_entry_self_sufficient(self):
        save_devices(self.dir, [])
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        devs = load_devices(self.dir)
        self.assertEqual(len(devs), 1)
        dev = devs[0]
        self.assertEqual((dev.id, dev.kind, dev.name, dev.driver, dev.ip),
                         ("T9", "plug", "Boiler", "tasmota", "192.168.1.170"))
        self.assertEqual(dev.dps, TASMOTA_DPS)
        self.assertEqual(dev.scales, TASMOTA_SCALES)
        self.assertIsNone(dev.local_key)

    def test_self_sufficient_entry_without_devices_file(self):
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        self.assertEqual([d.id for d in load_devices(self.dir)], ["T9"])

    def test_self_sufficient_entry_added_after_tuya_devices(self):
        save_devices(self.dir, [self._dev("P1"), self._dev("P2")])
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Noua"},
                           "P2": {"driver": "tasmota", "ip": "192.168.1.186"}})
        got = load_devices(self.dir)
        self.assertEqual([(d.id, d.driver) for d in got],
                         [("P1", "tuya"), ("P2", "tasmota"), ("T9", "tasmota")])

    def test_self_sufficient_entry_needs_name_and_ip(self):
        self._write_local({"A": {"driver": "tasmota", "ip": "192.168.1.170"},
                           "B": {"driver": "tasmota", "name": "Fara ip"}})
        with self.assertLogs("casa.devices", level="WARNING") as cm:
            self.assertEqual(load_devices(self.dir), [])
        self.assertEqual(len(cm.records), 2)

    def test_lone_tasmota_id_outside_influx_alphabet_warns_but_stays(self):
        self._write_local({"T-9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        with self.assertLogs("casa.devices", level="WARNING") as cm:
            devs = load_devices(self.dir)
        self.assertEqual([d.id for d in devs], ["T-9"])           # nu e sarita: ar pierde evenimentele
        self.assertEqual(len(cm.records), 1)
        msg = cm.records[0].getMessage()
        self.assertIn("T-9", msg)
        self.assertIn("[A-Za-z0-9_]", msg)

    def test_tasmota_override_id_outside_influx_alphabet_warns_but_stays(self):
        save_devices(self.dir, [self._dev("P 1"), self._dev("P2")])
        self._write_local({"P 1": {"driver": "tasmota", "ip": "192.168.1.186"}})
        with self.assertLogs("casa.devices", level="WARNING") as cm:
            devs = load_devices(self.dir)
        self.assertEqual([(d.id, d.driver) for d in devs], [("P 1", "tasmota"), ("P2", "tuya")])
        self.assertEqual(len(cm.records), 1)
        self.assertIn("P 1", cm.records[0].getMessage())

    def test_valid_ids_do_not_warn(self):
        save_devices(self.dir, [self._dev("bf3bac46d1a367e845thx2"), self._dev("S-1", kind="sensor")])
        self._write_local({"T_9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        with self.assertNoLogs("casa.devices", level="WARNING"):
            self.assertEqual(len(load_devices(self.dir)), 3)      # senzorii nu ajung in energia prizelor

    def test_local_tasmota_without_ip_is_skipped(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204", version="3.3")])
        self._write_local({"P1": {"driver": "tasmota"}})
        with self.assertLogs("casa.devices", level="WARNING"):
            devs = load_devices(self.dir)
        self.assertEqual(len(devs), 1)
        dev = devs[0]
        self.assertEqual((dev.driver, dev.ip, dev.version), ("tuya", "192.168.1.204", "3.3"))
        self.assertEqual(dev.dps, {"switch": 1, "power": 19})
        self.assertEqual(dev.scales, {"power": 1})

    def test_local_tasmota_with_empty_ip_is_skipped(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204")])
        self._write_local({"P1": {"driver": "tasmota", "ip": ""}})
        with self.assertLogs("casa.devices", level="WARNING"):
            dev = load_devices(self.dir)[0]
        self.assertEqual((dev.driver, dev.ip), ("tuya", "192.168.1.204"))

    def test_lone_tasmota_entry_without_ip_creates_nothing(self):
        self._write_local({"T9": {"driver": "tasmota", "name": "Fara ip"}})
        with self.assertLogs("casa.devices", level="WARNING"):
            self.assertEqual(load_devices(self.dir), [])

    def test_unknown_driver_is_skipped(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204", version="3.3")])
        self._write_local({"P1": {"driver": "esphome", "ip": "192.168.1.186"},
                           "T9": {"driver": "esphome", "ip": "192.168.1.170", "name": "Alta"}})
        with self.assertLogs("casa.devices", level="WARNING"):
            devs = load_devices(self.dir)
        self.assertEqual([d.id for d in devs], ["P1"])
        dev = devs[0]
        self.assertEqual((dev.driver, dev.ip, dev.version), ("tuya", "192.168.1.204", "3.3"))
        self.assertEqual(dev.dps, {"switch": 1, "power": 19})

    def test_driver_must_be_text(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204")])
        self._write_local({"P1": {"driver": ["tasmota"], "ip": "192.168.1.186"}})
        with self.assertLogs("casa.devices", level="WARNING"):
            dev = load_devices(self.dir)[0]
        self.assertEqual((dev.driver, dev.ip), ("tuya", "192.168.1.204"))

    def test_tasmota_override_on_sensor_is_skipped(self):
        save_devices(self.dir, [self._dev("S1", kind="sensor")])
        self._write_local({"S1": {"driver": "tasmota", "ip": "192.168.1.186"}})
        with self.assertLogs("casa.devices", level="WARNING"):
            dev = load_devices(self.dir)[0]
        self.assertEqual((dev.driver, dev.kind), ("tuya", "sensor"))

    def test_tasmota_hidden_entry_skipped(self):
        save_devices(self.dir, [self._dev("P1"), self._dev("P2")])
        self._write_local({"P1": {"driver": "tasmota", "ip": "192.168.1.186", "hidden": True},
                           "T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Ascunsa",
                                  "hidden": True}})
        self.assertEqual([d.id for d in load_devices(self.dir)], ["P2"])

    def test_local_names_include_tasmota_entry(self):
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        self.assertEqual(local_names(self.dir), {"T9": "Boiler"})

    def test_set_local_name_keeps_driver_and_ip(self):
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        set_local_name(self.dir, "T9", "Boiler nou")
        dev = load_devices(self.dir)[0]
        self.assertEqual((dev.name, dev.driver, dev.ip), ("Boiler nou", "tasmota", "192.168.1.170"))

    def test_apply_scan_ignores_tasmota(self):
        save_devices(self.dir, [self._dev("P1", ip="192.168.1.204", version="3.3")])
        self._write_local({"P1": {"driver": "tasmota", "ip": "192.168.1.186"}})
        changes, unknown = apply_scan(self.dir, {"P1": {"ip": "192.168.1.204", "version": "3.3"}})
        self.assertEqual((changes, unknown), ([], []))
        with open(os.path.join(self.dir, "devices.local.json")) as f:
            self.assertEqual(json.load(f), {"P1": {"driver": "tasmota", "ip": "192.168.1.186"}})

    def test_apply_scan_ignores_self_sufficient_tasmota(self):
        self._write_local({"T9": {"driver": "tasmota", "ip": "192.168.1.170", "name": "Boiler"}})
        changes, unknown = apply_scan(self.dir, {"T9": {"ip": "192.168.1.99", "version": "3.3"}})
        self.assertEqual((changes, unknown), ([], []))
        with open(os.path.join(self.dir, "devices.local.json")) as f:
            self.assertEqual(json.load(f)["T9"]["ip"], "192.168.1.170")


if __name__ == "__main__":
    unittest.main()
