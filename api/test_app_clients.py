import unittest

import app_clients


class ParseUserAgentTest(unittest.TestCase):
    def test_new_app_reports_model_android_and_version(self):
        self.assertEqual(
            ("SM-G781B", "13", "3.38 (42)"),
            app_clients.parse_user_agent("SolarMonitor/3.38 (42; Android 13; SM-G781B)"))

    def test_old_app_is_known_by_the_dalvik_agent_without_a_version(self):
        self.assertEqual(
            ("SM-N960U", "10", app_clients.OLD_VERSION),
            app_clients.parse_user_agent("Dalvik/2.1.0 (Linux; U; Android 10; SM-N960U Build/QP1A.190711.020)"))
        self.assertEqual(
            ("sdk_gphone64_x86_64", "14", app_clients.OLD_VERSION),
            app_clients.parse_user_agent("Dalvik/2.1.0 (Linux; U; Android 14; sdk_gphone64_x86_64 Build/UE1A.230829.050)"))

    def test_other_clients_are_not_counted(self):
        for ua in [None, "", "curl/8.14.1", "Python-urllib/3.12", "Mozilla/5.0 (X11; Linux x86_64)"]:
            self.assertIsNone(app_clients.parse_user_agent(ua))

    def test_odd_characters_do_not_reach_the_tags(self):
        model, _, _ = app_clients.parse_user_agent('SolarMonitor/3.38 (42; Android 13; SM"G7,=81B\n)')
        self.assertNotRegex(model, r'[",=\n]')


class ClientCounterTest(unittest.TestCase):
    def test_counts_per_phone_and_route_then_starts_over(self):
        c = app_clients.ClientCounter()
        new = "SolarMonitor/3.38 (42; Android 13; SM-G781B)"
        old = "Dalvik/2.1.0 (Linux; U; Android 10; SM-N960U Build/QP1A.190711.020)"
        for _ in range(3):
            c.record(new, "/latest")
        c.record(new, "/bms")
        c.record(old, "/latest")
        c.record("curl/8.14.1", "/latest")
        c.record(new, "/health")
        counts = c.drain()
        self.assertEqual({("SM-G781B", "13", "3.38 (42)"): {"latest": 3, "bms": 1},
                          ("SM-N960U", "10", app_clients.OLD_VERSION): {"latest": 1}}, counts)
        self.assertEqual({}, c.drain())

    def test_points_carry_tags_route_fields_and_a_total(self):
        pts = app_clients.points({("SM-G781B", "13", "3.38 (42)"): {"latest": 12, "bms": 6}}, 123)
        self.assertEqual([{
            "measurement": "app_client",
            "tags": {"model": "SM-G781B", "android": "13", "app_version": "3.38 (42)"},
            "fields": {"latest": 12.0, "bms": 6.0, "requests": 18.0},
            "time": 123,
        }], pts)


if __name__ == "__main__":
    unittest.main()
