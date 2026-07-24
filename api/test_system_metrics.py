import tempfile
import unittest
from pathlib import Path

from system_metrics import HostMetricsSampler


class _Clock:
    def __init__(self):
        self.value = 100.0

    def __call__(self):
        current = self.value
        self.value += 2.0
        return current


class HostMetricsSamplerTest(unittest.TestCase):
    def test_procfs_deltas_produce_cpu_memory_network_and_uptime(self):
        with tempfile.TemporaryDirectory() as directory:
            proc = Path(directory)
            (proc / "net").mkdir()
            self._write_proc(
                proc,
                cpu="cpu 100 0 100 700 100 0 0 0",
                tx_bytes=1024,
            )
            sampler = HostMetricsSampler(proc_root=directory, clock=_Clock())
            sampler.snapshot()

            self._write_proc(
                proc,
                cpu="cpu 120 0 120 740 120 0 0 0",
                tx_bytes=21504,
            )
            metrics = sampler.snapshot()

            self.assertEqual(40.0, metrics["server_cpu_percent"])
            self.assertEqual(50.0, metrics["server_memory_percent"])
            self.assertEqual(4000.0, metrics["server_memory_used_mb"])
            self.assertEqual(8000.0, metrics["server_memory_total_mb"])
            self.assertEqual(10.0, metrics["server_upload_kbps"])
            self.assertEqual(172800.0, metrics["server_uptime_seconds"])

    @staticmethod
    def _write_proc(proc: Path, cpu: str, tx_bytes: int):
        (proc / "stat").write_text(cpu + "\n", encoding="utf-8")
        (proc / "meminfo").write_text(
            "MemTotal:       8192000 kB\n"
            "MemAvailable:   4096000 kB\n",
            encoding="utf-8",
        )
        (proc / "uptime").write_text("172800.0 1000.0\n", encoding="utf-8")
        (proc / "net/dev").write_text(
            "Inter-|   Receive                                                |  Transmit\n"
            " face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
            f"  eth0: 0 0 0 0 0 0 0 0 {tx_bytes} 0 0 0 0 0 0 0\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
