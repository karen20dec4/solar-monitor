import json
import os
import stat
import tempfile
import unittest

from casa.devices import (DeviceInfo, clean_name, dp_map_from_strategy,
                          load_devices, save_devices)


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


if __name__ == "__main__":
    unittest.main()
