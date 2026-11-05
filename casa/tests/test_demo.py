import unittest

from casa.demo import DemoCloud, DemoDriver, DemoInflux
from casa.devices import DeviceInfo
from casa.plugs import decode_reading


def plug(name, metered):
    dps = {"switch": 1, "countdown": 9}
    scales = {}
    if metered:
        dps.update({"current": 18, "power": 19, "voltage": 20})
        scales = {"power": 1, "voltage": 1, "current": 0}
    return DeviceInfo(id=name, name=name, kind="plug", local_key=None, ip=None, version=None,
                      dps=dps, scales=scales)


class TestDemo(unittest.TestCase):
    def test_metered_plug_reports_power_only_when_on(self):
        dev = plug("Boiler", True)
        drv = DemoDriver(dev)
        drv.connect()
        self.assertEqual(decode_reading(dev, drv.status()).power, 0.0)
        drv.set_dp(1, True)
        r = decode_reading(dev, drv.status())
        self.assertEqual((r.on, r.power), (True, 2000.0))
        self.assertGreater(r.voltage, 200)

    def test_unmetered_plug_has_no_power(self):
        dev = plug("Bucatarie", False)
        drv = DemoDriver(dev)
        drv.connect()
        drv.set_dp(1, True)
        self.assertIsNone(decode_reading(dev, drv.status()).power)

    def test_sensors_fixed_and_four_minutes_old(self):
        d = DeviceInfo(id="demo_sensor_afara", name="Temp afara", kind="sensor", local_key=None,
                       ip=None, version=None)
        s = DemoCloud([d], clock=lambda: 1000.0).sensors()[0]
        self.assertEqual((s["temperature"], s["humidity"], s["battery"], s["updated"]), (6.6, 83.0, 100, 760.0))

    def test_history_shapes(self):
        inf = DemoInflux({"x": 2000.0}, clock=lambda: 1730800000.0)
        h = inf.sensor_history("demo_sensor_etaj", "2h")
        self.assertEqual(len(h["temperature"]), 60)
        self.assertLessEqual(h["stats"]["temp_min"]["v"], h["stats"]["temp_max"]["v"])
        self.assertEqual(len(inf.sensor_spark("demo_sensor_etaj")), 24)
        self.assertIsNone(inf.energy_today("nu-e-masurata"))
        self.assertGreaterEqual(inf.energy_today("x"), 0)
        with self.assertRaises(ValueError):
            inf.sensor_history("x", "3y")


if __name__ == "__main__":
    unittest.main()
