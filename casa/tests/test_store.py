import os
import stat
import tempfile
import threading
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

    def test_two_instances_on_same_path_serialize(self):
        # Doua JsonStore pe acelasi fisier = doua procese (lock-uri threading separate).
        a, b = JsonStore(self.path), JsonStore(self.path)
        a.save({"n": 0})
        inside = threading.Event()
        release = threading.Event()
        order = []

        def slow(data):
            order.append("a-start")
            inside.set()
            release.wait(5)
            data["n"] += 1
            order.append("a-end")

        def fast(data):
            order.append("b-start")
            data["n"] += 10

        ta = threading.Thread(target=lambda: a.update(slow, {}))
        tb = threading.Thread(target=lambda: b.update(fast, {}))
        ta.start()
        self.assertTrue(inside.wait(5))
        tb.start()
        tb.join(0.3)
        self.assertTrue(tb.is_alive(), "b nu trebuia sa intre cat timp a tine lock-ul")
        release.set()
        ta.join(5)
        tb.join(5)
        self.assertEqual(order, ["a-start", "a-end", "b-start"])
        self.assertEqual(a.load({}), {"n": 11})

    def test_lock_file_is_mode_600(self):
        store = JsonStore(self.path)
        store.update(lambda d: d.update(x=1), {})
        self.assertEqual(stat.S_IMODE(os.stat(self.path + ".lock").st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
