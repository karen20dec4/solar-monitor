import contextlib
import io
import json
import os
import tempfile
import unittest
from unittest import mock

from casa.auth import Auth, BadPassword, NoPassword, RateLimited, RateLimiter
from casa.store import JsonStore


class FakeClock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


class AuthBase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = os.path.join(self.dir.name, "auth.json")
        self.clock = FakeClock()
        self.auth = Auth(JsonStore(self.path), clock=self.clock)


class AuthTest(AuthBase):
    def test_register_with_right_password_returns_token_and_check_finds_phone(self):
        self.auth.set_password("parola-buna-1")
        token, phone_id = self.auth.register("parola-buna-1", "Samsung Note 9", "10.0.0.1")
        self.assertEqual(len(phone_id), 8)
        info = self.auth.check(token)
        self.assertIsNotNone(info)
        self.assertEqual(info["phone_id"], phone_id)
        self.assertEqual(info["device"], "Samsung Note 9")
        self.assertIsNone(self.auth.check("token-inventat"))
        self.assertIsNone(self.auth.check(""))

    def test_wrong_password_raises_bad_password(self):
        self.auth.set_password("parola-buna-1")
        with self.assertRaises(BadPassword):
            self.auth.register("alta-parola-1", "tel", "10.0.0.1")

    def test_no_password_set_raises_no_password(self):
        self.assertFalse(self.auth.has_password())
        with self.assertRaises(NoPassword):
            self.auth.register("orice-parola", "tel", "10.0.0.1")

    def test_sixth_wrong_attempt_from_same_ip_in_15_min_is_rate_limited(self):
        self.auth.set_password("parola-buna-1")
        for _ in range(5):
            with self.assertRaises(BadPassword):
                self.auth.register("gresita-123", "tel", "10.0.0.1")
        with self.assertRaises(RateLimited) as cm:
            self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        self.assertTrue(0 < cm.exception.retry_after <= 900)
        # alt IP nu e afectat
        self.auth.register("parola-buna-1", "tel", "10.0.0.2")
        self.clock.t += 900
        token, _ = self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        self.assertIsNotNone(self.auth.check(token))

    def test_correct_password_does_not_consume_attempts(self):
        self.auth.set_password("parola-buna-1")
        for _ in range(10):
            self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        with self.assertRaises(BadPassword):
            self.auth.register("gresita-123", "tel", "10.0.0.1")

    def test_twenty_wrong_total_per_hour_limits_all_ips(self):
        self.auth.set_password("parola-buna-1")
        for i in range(20):
            with self.assertRaises(BadPassword):
                self.auth.register("gresita-123", "tel", f"10.0.1.{i}")
        with self.assertRaises(RateLimited) as cm:
            self.auth.register("parola-buna-1", "tel", "10.0.9.9")
        self.assertTrue(0 < cm.exception.retry_after <= 3600)
        self.clock.t += 3600
        self.auth.register("parola-buna-1", "tel", "10.0.9.9")

    def test_revoke_makes_check_return_none(self):
        self.auth.set_password("parola-buna-1")
        token, phone_id = self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        self.assertFalse(self.auth.revoke("deadbeef"))
        self.assertTrue(self.auth.revoke(phone_id))
        self.assertIsNone(self.auth.check(token))
        self.assertEqual(self.auth.phones(), [])

    def test_set_password_keeps_registered_phones(self):
        self.auth.set_password("parola-buna-1")
        token, _ = self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        self.auth.set_password("parola-noua-22")
        self.assertIsNotNone(self.auth.check(token))
        with self.assertRaises(BadPassword):
            self.auth.register("parola-buna-1", "tel2", "10.0.0.2")
        self.auth.register("parola-noua-22", "tel2", "10.0.0.2")

    def test_auth_file_contains_no_plain_password_or_token(self):
        self.auth.set_password("parola-secreta-77")
        token, _ = self.auth.register("parola-secreta-77", "tel", "10.0.0.1")
        with open(self.path, "r", encoding="utf-8") as f:
            raw = f.read()
        self.assertNotIn("parola-secreta-77", raw)
        self.assertNotIn(token, raw)
        self.assertEqual(os.stat(self.path).st_mode & 0o777, 0o600)

    def test_short_password_rejected(self):
        with self.assertRaises(ValueError):
            self.auth.set_password("scurta1")
        self.assertFalse(self.auth.has_password())

    def test_device_is_sanitized_and_truncated(self):
        self.auth.set_password("parola-buna-1")
        token, _ = self.auth.register("parola-buna-1", "<b>Tel\n\x00 ăâ</b>" + "x" * 100, "10.0.0.1")
        device = self.auth.check(token)["device"]
        self.assertLessEqual(len(device), 60)
        self.assertTrue(all(c.isascii() and (c.isalnum() or c in " .-_+()") for c in device))
        token2, _ = self.auth.register("parola-buna-1", "<>\n", "10.0.0.1")
        self.assertEqual(self.auth.check(token2)["device"], "telefon")

    def test_last_used_updates_at_most_once_per_minute(self):
        self.auth.set_password("parola-buna-1")
        token, phone_id = self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        t0 = self.auth.phones()[0]["last_used"]
        self.clock.t += 30
        self.auth.check(token)
        self.assertEqual(self.auth.phones()[0]["last_used"], t0)
        self.clock.t += 31
        self.auth.check(token)
        self.assertEqual(self.auth.phones()[0]["last_used"], t0 + 61)

    def test_phones_listing_has_no_hashes(self):
        self.auth.set_password("parola-buna-1")
        token, phone_id = self.auth.register("parola-buna-1", "tel", "10.0.0.1")
        p = self.auth.phones()[0]
        self.assertEqual(set(p), {"phone_id", "device", "created", "last_used"})
        self.assertEqual(p["phone_id"], phone_id)


class RateLimiterTest(unittest.TestCase):
    def test_burst_then_blocked_then_freed(self):
        clock = FakeClock(0.0)
        rl = RateLimiter(3, 3, clock)
        self.assertTrue(rl.hit("p"))
        self.assertTrue(rl.hit("p"))
        self.assertTrue(rl.hit("p"))
        self.assertFalse(rl.allowed("p"))
        self.assertFalse(rl.hit("p"))
        self.assertTrue(rl.allowed("q"))
        clock.t = 3.0
        self.assertTrue(rl.allowed("p"))
        self.assertTrue(rl.hit("p"))


class CliTest(unittest.TestCase):
    def run_cli(self, args, stdin=""):
        from casa import __main__ as cli
        out = io.StringIO()
        with mock.patch.dict(os.environ, {"CASA_DATA_DIR": self.dir.name}), \
                mock.patch("sys.stdin", io.StringIO(stdin)), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cli.main(args)
        return code, out.getvalue()

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def test_set_password_stdin_then_phones_and_revoke(self):
        code, _ = self.run_cli(["set-password", "--stdin"], "parola-din-stdin\n")
        self.assertEqual(code, 0)
        auth = Auth(JsonStore(os.path.join(self.dir.name, "auth.json")))
        self.assertTrue(auth.has_password())
        token, phone_id = auth.register("parola-din-stdin", "Tel Test", "10.0.0.1")
        code, out = self.run_cli(["phones"])
        self.assertEqual(code, 0)
        self.assertIn(phone_id, out)
        self.assertIn("Tel Test", out)
        code, out = self.run_cli(["revoke", phone_id])
        self.assertEqual(code, 0)
        self.assertIsNone(auth.check(token))
        code, _ = self.run_cli(["revoke", phone_id])
        self.assertNotEqual(code, 0)

    def test_set_password_stdin_short_fails(self):
        code, out = self.run_cli(["set-password", "--stdin"], "scurta\n")
        self.assertNotEqual(code, 0)
        self.assertNotIn("scurta", out)


if __name__ == "__main__":
    unittest.main()
