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


class HistoryApiTest(unittest.TestCase):
    def setUp(self):
        self.original_client = api_module.client
        self.queries = []
        api_module.client = _FakeInfluxClient(self.queries)
        self.client = api_module.app.test_client()

    def tearDown(self):
        api_module.client = self.original_client

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

    def test_unknown_range_is_rejected(self):
        response = self.client.get("/history?field=pv_power&range=90d")
        self.assertEqual(400, response.status_code)
        self.assertEqual("range nepermis", response.get_json()["error"])


if __name__ == "__main__":
    unittest.main()
