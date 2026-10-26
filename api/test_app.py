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


class _Rec:
    def __init__(self, field, value, time, **tags):
        self._field, self._value, self._time = field, value, time
        self.values = dict(tags)

    def get_field(self):
        return self._field

    def get_value(self):
        return self._value

    def get_time(self):
        return self._time


class _Table:
    def __init__(self, records):
        self.records = records


class _ScriptedQueryApi:
    """Raspunde dupa masuratoarea ceruta in query."""

    def __init__(self, bms, alerts, fail=False, inverter=()):
        self.bms, self.alerts, self.fail, self.inverter = bms, alerts, fail, list(inverter)

    def query(self, query, org=None):
        if self.fail:
            raise RuntimeError("influx jos")
        if '"inverter"' in query:
            return [_Table(self.inverter)]
        return [_Table(self.alerts if '"bms_alert"' in query else self.bms)]


class _ScriptedClient:
    def __init__(self, **kwargs):
        self.api = _ScriptedQueryApi(**kwargs)

    def query_api(self):
        return self.api


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
                ("1d", "-1d", "1h"),
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
            for range_key in ("2h", "1d", "7d", "30d"):
                response = self.client.get(f"/history?field={field}&range={range_key}")
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

    def test_energy_short_ranges_integrate_power_from_live(self):
        for field, source in (("energy_pv_today", "pv_power"), ("energy_load_today", "output_power")):
            for range_key, start, window in (("2h", "-110m", "10m"), ("1d", "-23h", "1h")):
                with self.subTest(field=field, range=range_key):
                    response = self.client.get(f"/history?field={field}&range={range_key}")
                    self.assertEqual(200, response.status_code)
                    self.assertEqual(window, response.get_json()["window"])
                    query = self.queries[-1]
                    self.assertIn('from(bucket: "live")', query)
                    self.assertIn(f'r._field == "{source}"', query)
                    self.assertIn("integral(unit: 1h", query)
                    self.assertIn(f"date.add(d: {start}, to: date.truncate(t: now(), unit: {window}))", query)
                    self.assertIn('timeSrc: "_start"', query)
                    self.assertNotIn("difference(", query)

    def test_energy_week_uses_daily_counter_max_aligned_to_midnight(self):
        response = self.client.get("/history?field=energy_pv_today&range=7d")
        self.assertEqual(200, response.status_code)
        query = self.queries[-1]
        self.assertIn('from(bucket: "history")', query)
        self.assertIn("fn: max", query)
        self.assertIn("date.add(d: -6d, to: date.truncate(t: now(), unit: 1d))", query)
        self.assertNotIn("integral(", query)

    def test_grid_consumption_is_a_line_power_and_a_bar_energy_metric(self):
        for range_key in ("2h", "1d", "7d"):
            response = self.client.get(f"/history?field=grid_power&range={range_key}")
            self.assertEqual(200, response.status_code)
            payload = response.get_json()
            self.assertEqual(("line", "W"), (payload["chart"], payload["unit"]))
            self.assertIn('r._field == "grid_power"', self.queries[-1])
        response = self.client.get("/history?field=energy_grid&range=1d")
        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertEqual(("bar", "kWh"), (payload["chart"], payload["unit"]))

    def test_grid_energy_integrates_grid_power_live_for_short_ranges_and_history_for_days(self):
        for range_key, bucket, start, window in (
            ("2h", "live", "-110m", "10m"),
            ("1d", "live", "-23h", "1h"),
            ("7d", "history", "-6d", "1d"),
            ("30d", "history", "-29d", "1d"),
        ):
            with self.subTest(range=range_key):
                response = self.client.get(f"/history?field=energy_grid&range={range_key}")
                self.assertEqual(200, response.status_code)
                self.assertEqual(window, response.get_json()["window"])
                query = self.queries[-1]
                self.assertIn(f'from(bucket: "{bucket}")', query)
                self.assertIn('r._field == "grid_power"', query)
                self.assertIn("integral(unit: 1h", query)
                self.assertIn(f"date.add(d: {start}, to: date.truncate(t: now(), unit: {window}))", query)
                self.assertIn('timeSrc: "_start"', query)

    def test_line_ranges_keep_window_end_timestamps(self):
        response = self.client.get("/history?field=pv_power&range=2h")
        self.assertEqual(200, response.status_code)
        self.assertEqual("1m", response.get_json()["window"])
        self.assertIn('from(bucket: "live")', self.queries[-1])
        self.assertNotIn("timeSrc", self.queries[-1])


class _Record:
    def __init__(self, value, stat=None):
        self._value = value
        self.values = {"stat": stat} if stat else {}

    def get_value(self):
        return self._value

    def get_time(self):
        import datetime
        return datetime.datetime(2026, 10, 18, 12, 0, tzinfo=datetime.timezone.utc)


class _Table:
    def __init__(self, records):
        self.records = records


class _StatsQueryApi:
    def __init__(self, queries):
        self.queries = queries

    def query(self, query, org=None):
        self.queries.append(query)
        if "union(" in query:
            return [_Table([_Record(266.0, "min"), _Record(4720.0, "max"),
                            _Record(2128.0, "avg"), _Record(3298.0, "last")])]
        return [_Table([_Record(1000.0), _Record(2964.0)])]


class _StatsClient:
    def __init__(self, queries):
        self.queries = queries

    def query_api(self):
        return _StatsQueryApi(self.queries)


class ExactStatsTest(unittest.TestCase):
    def setUp(self):
        self.original_client = api_module.client
        self.queries = []
        api_module.client = _StatsClient(self.queries)
        self.client = api_module.app.test_client()

    def tearDown(self):
        api_module.client = self.original_client

    def test_line_stats_come_from_raw_data_not_chart_points(self):
        payload = self.client.get("/history?field=output_power&range=1d").get_json()
        self.assertEqual(4720.0, payload["stats"]["max"])
        self.assertEqual(266.0, payload["stats"]["min"])
        self.assertEqual(3298.0, payload["stats"]["last"])
        # 24 h de date la 1 s: statisticile vin din `live`, graficul din `history`
        self.assertIn('from(bucket: "history")', self.queries[0])
        self.assertIn('from(bucket: "live")', self.queries[1])

    def test_bar_stats_stay_on_the_bars(self):
        payload = self.client.get("/history?field=energy_pv_today&range=2h").get_json()
        self.assertEqual(1, len(self.queries))
        self.assertEqual(3964.0, payload["stats"]["sum"])
        self.assertEqual(2964.0, payload["stats"]["max"])


class BmsApiTest(unittest.TestCase):
    def setUp(self):
        self.original_client = api_module.client
        self.client = api_module.app.test_client()

    def tearDown(self):
        api_module.client = self.original_client

    def _now(self):
        from datetime import datetime, timezone
        return datetime(2026, 10, 22, 18, 0, 0, tzinfo=timezone.utc)

    def test_bms_returns_flat_fields_timestamp_and_active_alarms(self):
        now = self._now()
        api_module.client = _ScriptedClient(
            bms=[_Rec("voltage", 55.8, now), _Rec("current", -47.7, now), _Rec("soc", 95.0, now)],
            alerts=[
                _Rec("title", "BMS: bateria deconectata!", now, key="bms_mosfet"),
                _Rec("msg", "MOSFET incarcare oprit", now, key="bms_mosfet"),
                _Rec("prio", "urgent", now, key="bms_mosfet"),
                _Rec("active", 1.0, now, key="bms_mosfet"),
                _Rec("active", 0.0, now, key="bms_vechi"),
            ])
        payload = self.client.get("/bms").get_json()
        self.assertEqual(55.8, payload["voltage"])
        self.assertEqual(-47.7, payload["current"])
        self.assertEqual("2026-10-22T18:00:00+00:00", payload["timestamp"])
        self.assertEqual(
            [{"key": "bms_mosfet", "title": "BMS: bateria deconectata!",
              "msg": "MOSFET incarcare oprit", "prio": "urgent"}],
            payload["alarms"])

    def test_bms_without_fresh_data_has_null_timestamp_and_no_alarms(self):
        api_module.client = _ScriptedClient(bms=[], alerts=[])
        response = self.client.get("/bms")
        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertIsNone(payload["timestamp"])
        self.assertEqual([], payload["alarms"])

    def test_voltage_soc_is_one_percent_at_48v_and_full_at_the_charge_threshold(self):
        self.assertEqual((48.0, 57.0), (api_module.SOC_EMPTY_V, api_module.SOC_FULL_V))
        self.assertEqual(1.0, api_module.voltage_soc(48.0))
        self.assertEqual(100.0, api_module.voltage_soc(57.0))
        self.assertEqual(78.0, api_module.voltage_soc(55.0))
        self.assertEqual(100.0, api_module.voltage_soc(57.4))
        self.assertEqual(0.0, api_module.voltage_soc(47.0))
        self.assertIsNone(api_module.voltage_soc(None))
        summer = api_module.SOC_FULL_V
        try:
            api_module.SOC_FULL_V = 56.0
            self.assertEqual(100.0, api_module.voltage_soc(56.0))
            self.assertEqual(50.5, api_module.voltage_soc(52.0))
        finally:
            api_module.SOC_FULL_V = summer

    def test_bms_soc_voltage_uses_the_inverter_voltage_and_falls_back_to_the_bms(self):
        now = self._now()
        bms = [_Rec("voltage", 55.5, now), _Rec("soc", 88.0, now)]
        api_module.client = _ScriptedClient(bms=bms, alerts=[], inverter=[_Rec("battery_voltage", 55.0, now)])
        payload = self.client.get("/bms").get_json()
        self.assertEqual(78.0, payload["soc_voltage"])
        self.assertEqual(88.0, payload["soc"])
        self.assertEqual((48.0, 57.0), (payload["soc_empty_v"], payload["soc_full_v"]))
        api_module.client = _ScriptedClient(bms=bms, alerts=[])
        self.assertEqual(83.5, self.client.get("/bms").get_json()["soc_voltage"])
        api_module.client = _ScriptedClient(bms=[], alerts=[])
        self.assertIsNone(self.client.get("/bms").get_json()["soc_voltage"])

    def test_bms_reports_influx_failure_as_503(self):
        api_module.client = _ScriptedClient(bms=[], alerts=[], fail=True)
        self.assertEqual(503, self.client.get("/bms").status_code)

    def test_bms_reads_only_live_bucket_measurements_bms_and_bms_alert(self):
        queries = []
        api_module.client = _FakeInfluxClient(queries)
        self.client.get("/bms")
        self.assertEqual(3, len(queries))
        self.assertIn('r._measurement == "bms"', queries[0])
        self.assertIn('r._measurement == "bms_alert"', queries[1])
        self.assertIn('r._measurement == "inverter" and r._field == "battery_voltage"', queries[2])
        for q in queries:
            self.assertIn('from(bucket: "live")', q)

    def test_grid_month_integrates_grid_power_from_the_start_of_the_bucharest_month(self):
        from datetime import datetime, timezone
        queries = []

        class _OneValue(_FakeQueryApi):
            def query(self, query, org=None):
                queries.append(query)
                return [_Table([_Rec("_value", 9.34, datetime(2026, 10, 24, 11, 0, tzinfo=timezone.utc))])]

        class _Client:
            def query_api(self):
                return _OneValue(queries)

        api_module.client = _Client()
        original = api_module._month_start
        api_module._month_start = lambda now=None: original(datetime(2026, 10, 24, 11, 20, tzinfo=timezone.utc))
        try:
            payload = self.client.get("/grid-month").get_json()
        finally:
            api_module._month_start = original
        self.assertEqual("2026-10", payload["month"])
        self.assertEqual(9.34, payload["kwh"])
        # 1 octombrie 00:00 la Bucuresti (EEST, UTC+3) = 30 septembrie 21:00 UTC
        self.assertEqual("2026-09-30T21:00:00+00:00", payload["since"])
        self.assertEqual(1, len(queries))
        self.assertIn('from(bucket: "history")', queries[0])
        self.assertIn("range(start: 2026-09-30T21:00:00Z)", queries[0])
        self.assertIn('r._field == "grid_power"', queries[0])
        self.assertIn("integral(unit: 1h)", queries[0])

    def test_month_start_follows_bucharest_time_across_the_year_and_the_dst_change(self):
        from datetime import datetime, timezone
        # 31 octombrie 22:30 UTC e deja 1 noiembrie la Bucuresti (EET, UTC+2 dupa schimbarea orei)
        start = api_module._month_start(datetime(2026, 10, 31, 22, 30, tzinfo=timezone.utc))
        self.assertEqual((2026, 11, 1), (start.year, start.month, start.day))
        self.assertEqual("2026-10-31T22:00:00+00:00", start.astimezone(timezone.utc).isoformat())
        # iarna: 1 ianuarie 00:00 la Bucuresti = 31 decembrie 22:00 UTC
        start = api_module._month_start(datetime(2027, 1, 15, 12, 0, tzinfo=timezone.utc))
        self.assertEqual("2026-12-31T22:00:00+00:00", start.astimezone(timezone.utc).isoformat())

    def test_grid_month_without_samples_has_null_kwh_and_influx_failure_is_503(self):
        api_module.client = _FakeInfluxClient([])
        response = self.client.get("/grid-month")
        self.assertEqual(200, response.status_code)
        self.assertIsNone(response.get_json()["kwh"])
        api_module.client = _ScriptedClient(bms=[], alerts=[], fail=True)
        self.assertEqual(503, self.client.get("/grid-month").status_code)


if __name__ == "__main__":
    unittest.main()
