import os
import stat
import tempfile
import unittest

from casa.store import JsonStore


class JsonStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.name, "date.json")

    def test_save_then_load_roundtrip(self):
        store = JsonStore(self.path)
        store.save({"a": 1, "b": [1, 2], "nume": "prize"})
        self.assertEqual(store.load({}), {"a": 1, "b": [1, 2], "nume": "prize"})

    def test_missing_file_returns_default_copy(self):
        store = JsonStore(self.path)
        default = {"plugs": {}}
        got = store.load(default)
        self.assertEqual(got, default)
        got["plugs"]["x"] = 1
        self.assertEqual(default, {"plugs": {}})

    def test_save_is_atomic_and_mode_600(self):
        store = JsonStore(self.path)
        store.save({"a": 1})
        self.assertEqual([f for f in os.listdir(self.tmp.name) if f.endswith(".tmp")], [])
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)

    def test_corrupt_file_raises_value_error(self):
        with open(self.path, "w") as f:
            f.write("{nu e json")
        with self.assertRaises(ValueError) as ctx:
            JsonStore(self.path).load({})
        self.assertIn(self.path, str(ctx.exception))

    def test_update_applies_function(self):
        store = JsonStore(self.path)
        store.save({"n": 1})

        def inc(d):
            d["n"] += 1

        out = store.update(inc, {"n": 0})
        self.assertEqual(out, {"n": 2})
        self.assertEqual(store.load({}), {"n": 2})


if __name__ == "__main__":
    unittest.main()
