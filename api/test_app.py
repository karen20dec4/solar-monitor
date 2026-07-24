import unittest

import app as api_module


class _FakeQueryApi:
    def __init__(self, queries):
        self.queries = queries

    def query(self, query, org=None):
        self.queries.append(query)
        return []


class _FakeInfluxClient:
    def __init__(self, queries):
        self.queries = queries

    def query_api(self):
        return _FakeQueryApi(self.queries)


class _FakeHostMetrics:
    def snapshot(self):
        return {
            "server_cpu_percent": 12.5,
            "server_memory_percent": 43.2,
            "server_memory_used_mb": 3456.0,
            "server_memory_total_mb": 8192.0,
            "server_upload_kbps": 10.4,
            "server_uptime_seconds": 172800.0,
        }


class HistoryApiTest(unittest.TestCase):
    def setUp(self):
        self.original_client = api_module.client
        self.original_host_metrics = api_module.host_metrics
        self.queries = []
        api_module.client = _FakeInfluxClient(self.queries)
        api_module.host_metrics = _FakeHostMetrics()
        self.client = api_module.app.test_client()

    def tearDown(self):
        api_module.client = self.original_client
        api_module.host_metrics = self.original_host_metrics

    def test_energy_page_line_metrics_support_seven_and_thirty_days(self):
        for field in ("output_power", "pv_power", "battery_voltage"):
            for range_key, start, window in (
                ("7d", "-7d", "30m"),
                ("30d", "-30d", "2h"),
            ):
                with self.subTest(field=field, range=range_key):
                    response = self.client.get(
                        f"/history?field={field}&range={range_key}"
                    )
                    self.assertEqual(200, response.status_code)
                    payload = response.get_json()
                    self.assertEqual(field, payload["field"])
                    self.assertEqual(range_key, payload["range"])
                    self.assertEqual(window, payload["window"])
                    self.assertIn(f'range(start: {start})', self.queries[-1])
                    self.assertIn('from(bucket: "history")', self.queries[-1])

    def test_daily_energy_metrics_keep_bar_contract(self):
        for field in ("energy_pv_today", "energy_load_today"):
            response = self.client.get(f"/history?field={field}&range=7d")
            self.assertEqual(200, response.status_code)
            self.assertEqual("bar", response.get_json()["chart"])

    def test_system_temperature_supports_one_hour_mini_chart(self):
        response = self.client.get("/history?field=inverter_temp&range=1h")

        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertEqual("inverter_temp", payload["field"])
        self.assertEqual("°C", payload["unit"])
        self.assertEqual("30s", payload["window"])
        self.assertIn('from(bucket: "live")', self.queries[-1])

    def test_latest_includes_only_aggregate_server_metrics(self):
        response = self.client.get("/latest")

        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertEqual(12.5, payload["server_cpu_percent"])
        self.assertEqual(43.2, payload["server_memory_percent"])
        self.assertEqual(10.4, payload["server_upload_kbps"])
        self.assertNotIn("hostname", payload)
        self.assertNotIn("processes", payload)
        self.assertNotIn("interfaces", payload)

    def test_unknown_range_is_rejected(self):
        response = self.client.get("/history?field=pv_power&range=90d")
        self.assertEqual(400, response.status_code)
        self.assertEqual("range nepermis", response.get_json()["error"])


if __name__ == "__main__":
    unittest.main()
