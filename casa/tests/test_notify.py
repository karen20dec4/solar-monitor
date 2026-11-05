import unittest
from unittest import mock

from casa.notify import make_notify


class NotifyTest(unittest.TestCase):
    def test_posts_title_priority_and_body_with_timeout(self):
        with mock.patch("casa.notify.urllib.request.urlopen") as urlopen:
            make_notify("http://ntfy/Alerta")("CASA: cloud Tuya deconectat", "Refa login", "high")
        req = urlopen.call_args[0][0]
        self.assertEqual(req.full_url, "http://ntfy/Alerta")
        self.assertEqual(req.data, b"Refa login")
        self.assertEqual(req.get_header("Title"), "CASA: cloud Tuya deconectat")
        self.assertEqual(req.get_header("Priority"), "high")
        self.assertEqual(urlopen.call_args[1]["timeout"], 5)

    def test_title_with_diacritics_is_ascii(self):
        with mock.patch("casa.notify.urllib.request.urlopen") as urlopen:
            make_notify("http://ntfy/x")("Priza și ță", "m", "default")
        req = urlopen.call_args[0][0]
        req.get_header("Title").encode("ascii")  # nu arunca

    def test_errors_are_logged_not_raised(self):
        with mock.patch("casa.notify.urllib.request.urlopen", side_effect=OSError("jos")):
            with self.assertLogs("casa.notify", level="WARNING"):
                make_notify("http://ntfy/x")("t", "m", "high")

    def test_empty_url_is_noop(self):
        with mock.patch("casa.notify.urllib.request.urlopen") as urlopen:
            make_notify("")("t", "m", "high")
        urlopen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
