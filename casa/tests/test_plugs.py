import threading
import time
import unittest
from concurrent.futures import Future

from casa.devices import DeviceInfo
from casa.plugs import FakeDriver, PlugError, PlugReading, PlugWorker


def _device():
    return DeviceInfo(
        id="p1", name="Priza", kind="plug", local_key="k", ip="1.2.3.4",
        version="3.3",
        dps={"switch": 1, "countdown": 9, "current": 18, "power": 19, "voltage": 20},
        scales={"power": 1, "voltage": 1, "current": 0})


class Collector:
    def __init__(self):
        self.readings = []
        self.cond = threading.Condition()

    def __call__(self, dev_id, reading):
        with self.cond:
            self.readings.append((dev_id, reading))
            self.cond.notify_all()

    def wait_for(self, pred, timeout=3.0):
        with self.cond:
            ok = self.cond.wait_for(
                lambda: any(pred(r) for _, r in self.readings), timeout)
        return ok


class WorkerBase(unittest.TestCase):
    def start(self, driver, **kw):
        self.col = Collector()
        w = PlugWorker(_device(), driver, 0.01, self.col, backoff_base=0.01, **kw)
        w.start()
        self.addCleanup(self._stop, w)
        return w

    @staticmethod
    def _stop(w):
        w.stop()
        w.join(3)


class PlugWorkerTest(WorkerBase):
    def test_reading_decodes_power_with_scale(self):
        d = FakeDriver({"1": True, "9": 120, "18": 7000, "19": 16500, "20": 2305})
        self.start(d)
        self.assertTrue(self.col.wait_for(lambda r: r.online and r.power is not None))
        r = next(r for _, r in self.col.readings if r.online)
        self.assertIsInstance(r, PlugReading)
        self.assertEqual(r.power, 1650.0)
        self.assertEqual(r.voltage, 230.5)
        self.assertEqual(r.current, 7.0)  # mA -> A
        self.assertIs(r.on, True)
        self.assertEqual(r.countdown_s, 120)
        self.assertIsNone(r.error)

    def test_missing_dps_are_none(self):
        d = FakeDriver({"1": False})
        self.start(d)
        self.assertTrue(self.col.wait_for(lambda r: r.online))
        r = next(r for _, r in self.col.readings if r.online)
        self.assertIs(r.on, False)
        self.assertIsNone(r.power)
        self.assertIsNone(r.countdown_s)

    def test_error_marks_offline_and_reconnects(self):
        d = FakeDriver({"1": True, "19": 100})
        d.fail_next = 2
        self.start(d)
        self.assertTrue(self.col.wait_for(lambda r: not r.online and r.error))
        # dupa offline apare din nou online
        self.assertTrue(self.col.wait_for(
            lambda r: r.online and r.error is None))
        with self.col.cond:
            seq = [r.online for _, r in self.col.readings]
        first_off = seq.index(False)
        # dupa prima citire offline exista una online (reconectare reala)
        self.assertIn(True, seq[first_off:])
        self.assertGreaterEqual(d.connect_count, 1)

    def test_submit_runs_on_worker_thread_only(self):
        d = FakeDriver({"1": False})
        w = self.start(d)
        self.assertTrue(self.col.wait_for(lambda r: r.online))
        futures = []
        lock = threading.Lock()

        def go(i):
            f = w.submit(1, bool(i % 2))
            with lock:
                futures.append(f)

        ts = [threading.Thread(target=go, args=(i,)) for i in range(3)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        for f in futures:
            self.assertIsInstance(f, Future)
            self.assertIsNone(f.result(timeout=3))
        self.assertEqual(len(futures), 3)
        idents = {ident for _, ident in d.calls}
        self.assertEqual(len(idents), 1)
        self.assertNotIn(threading.get_ident(), idents)
        # niciodata doua connect() fara close()
        open_ = 0
        for name, _ in d.calls:
            if name == "connect":
                open_ += 1
                self.assertLessEqual(open_, 1)
            elif name == "close":
                open_ = 0

    def test_submit_failure_sets_future_exception(self):
        d = FakeDriver({"1": False})
        w = self.start(d)
        self.assertTrue(self.col.wait_for(lambda r: r.online))
        d.fail_set = 1
        f = w.submit(1, True)
        with self.assertRaises(PlugError):
            f.result(timeout=3)
        # se recupereaza si urmatoarea comanda reuseste
        self.assertTrue(self.col.wait_for(lambda r: r.online and r.on is False))
        f2 = w.submit(1, True)
        self.assertIsNone(f2.result(timeout=3))
        self.assertTrue(self.col.wait_for(lambda r: r.on is True))

    def test_submit_while_offline_connects_immediately(self):
        d = FakeDriver({"1": False})
        # backoff lung: comanda nu trebuie sa il astepte
        self.col = Collector()
        w = PlugWorker(_device(), d, 0.01, self.col, backoff_base=30.0)
        d.fail_next = 1
        w.start()
        self.addCleanup(self._stop, w)
        self.assertTrue(self.col.wait_for(lambda r: not r.online))
        f = w.submit(1, True)
        self.assertIsNone(f.result(timeout=3))
        self.assertTrue(self.col.wait_for(lambda r: r.online and r.on is True))

    def test_submit_while_offline_fails_future_when_connect_fails(self):
        d = FakeDriver({"1": False})
        d.fail_next = 3
        self.col = Collector()
        w = PlugWorker(_device(), d, 0.01, self.col, backoff_base=30.0)
        w.start()
        self.addCleanup(self._stop, w)
        self.assertTrue(self.col.wait_for(lambda r: not r.online))
        f = w.submit(1, True)
        with self.assertRaises(PlugError):
            f.result(timeout=3)

    def test_stop_fails_pending_futures(self):
        d = FakeDriver({"1": False})
        w = self.start(d)
        w.stop()
        w.join(3)
        f = w.submit(1, True)
        with self.assertRaises(PlugError):
            f.result(timeout=1)

    def test_stop_with_cancelled_and_blocked_futures_closes_cleanly(self):
        class BlockingDriver(FakeDriver):
            def __init__(self, dps):
                super().__init__(dps)
                self.in_status = threading.Event()
                self.release = threading.Event()

            def status(self):
                r = super().status()
                self.in_status.set()
                self.release.wait(5)
                return r

        d = BlockingDriver({"1": False})
        w = self.start(d)
        self.assertTrue(d.in_status.wait(3))  # workerul e blocat in status()
        w.stop()                              # _STOP intra primul in coada
        f1 = w.submit(1, True)                # comenzile raman in spatele lui _STOP
        f2 = w.submit(1, False)
        f3 = w.submit(1, True)
        self.assertTrue(f2.cancel())
        d.release.set()
        w.join(3)
        self.assertFalse(w.is_alive())
        for f in (f1, f3):
            with self.assertRaises(PlugError):
                f.result(timeout=1)
        self.assertTrue(f2.cancelled())
        self.assertEqual(d.calls[-1][0], "close")
        self.assertFalse(d.connected)


if __name__ == "__main__":
    unittest.main()
