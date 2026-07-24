"""Metrici agregate READ-ONLY citite din procfs-ul gazdei."""

from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from typing import Callable


class HostMetricsSampler:
    """Calculează CPU, RAM, uptime și upload fără acces la Docker socket."""

    def __init__(
        self,
        proc_root: str | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        configured_root = Path(proc_root or os.getenv("HOST_PROC_ROOT", "/host/proc"))
        self.proc_root = configured_root if (configured_root / "stat").exists() else Path("/proc")
        self.clock = clock
        self.lock = threading.Lock()
        self.previous_cpu: tuple[int, int] | None = None
        self.previous_tx_bytes: int | None = None
        self.previous_time: float | None = None

    def snapshot(self) -> dict[str, float | None]:
        now = self.clock()
        try:
            cpu_total, cpu_idle = self._read_cpu()
            memory_used_mb, memory_total_mb, memory_percent = self._read_memory()
            tx_bytes = self._read_tx_bytes()
            uptime_seconds = self._read_uptime()
        except (OSError, ValueError, IndexError):
            return self._empty_snapshot()

        with self.lock:
            cpu_percent = self._cpu_percent(cpu_total, cpu_idle)
            upload_kbps = self._upload_kbps(tx_bytes, now)
            self.previous_cpu = (cpu_total, cpu_idle)
            self.previous_tx_bytes = tx_bytes
            self.previous_time = now

        return {
            "server_cpu_percent": round(cpu_percent, 1),
            "server_memory_percent": round(memory_percent, 1),
            "server_memory_used_mb": round(memory_used_mb, 1),
            "server_memory_total_mb": round(memory_total_mb, 1),
            "server_upload_kbps": round(upload_kbps, 1),
            "server_uptime_seconds": round(uptime_seconds, 1),
        }

    def _read_cpu(self) -> tuple[int, int]:
        fields = (self.proc_root / "stat").read_text(encoding="utf-8").splitlines()[0].split()
        if not fields or fields[0] != "cpu":
            raise ValueError("invalid proc stat")
        counters = [int(value) for value in fields[1:9]]
        total = sum(counters)
        idle = counters[3] + counters[4]
        return total, idle

    def _cpu_percent(self, total: int, idle: int) -> float:
        if self.previous_cpu is None:
            try:
                load = os.getloadavg()[0]
                return self._clamp(load / max(os.cpu_count() or 1, 1) * 100.0)
            except OSError:
                return 0.0
        previous_total, previous_idle = self.previous_cpu
        total_delta = total - previous_total
        idle_delta = idle - previous_idle
        if total_delta <= 0:
            return 0.0
        return self._clamp((total_delta - idle_delta) / total_delta * 100.0)

    def _read_memory(self) -> tuple[float, float, float]:
        values: dict[str, int] = {}
        for line in (self.proc_root / "meminfo").read_text(encoding="utf-8").splitlines():
            key, _, raw = line.partition(":")
            if key in {"MemTotal", "MemAvailable"}:
                values[key] = int(raw.strip().split()[0])
        total_kb = values["MemTotal"]
        available_kb = values["MemAvailable"]
        used_kb = max(total_kb - available_kb, 0)
        percent = used_kb / total_kb * 100.0 if total_kb else 0.0
        return used_kb / 1024.0, total_kb / 1024.0, self._clamp(percent)

    def _read_tx_bytes(self) -> int:
        total = 0
        for line in (self.proc_root / "net/dev").read_text(encoding="utf-8").splitlines()[2:]:
            interface_raw, separator, counters_raw = line.partition(":")
            if not separator:
                continue
            interface = interface_raw.strip()
            if not self._include_interface(interface):
                continue
            counters = counters_raw.split()
            total += int(counters[8])
        return total

    @staticmethod
    def _include_interface(interface: str) -> bool:
        configured = os.getenv("HOST_NETWORK_INTERFACES", "").strip()
        if configured:
            return interface in {name.strip() for name in configured.split(",") if name.strip()}
        ignored_prefixes = ("lo", "docker", "br-", "veth")
        return not interface.startswith(ignored_prefixes)

    def _upload_kbps(self, tx_bytes: int, now: float) -> float:
        if self.previous_tx_bytes is None or self.previous_time is None:
            return 0.0
        elapsed = now - self.previous_time
        if elapsed <= 0:
            return 0.0
        byte_delta = max(tx_bytes - self.previous_tx_bytes, 0)
        return byte_delta / 1024.0 / elapsed

    def _read_uptime(self) -> float:
        return float((self.proc_root / "uptime").read_text(encoding="utf-8").split()[0])

    @staticmethod
    def _clamp(value: float) -> float:
        return max(0.0, min(value, 100.0))

    @staticmethod
    def _empty_snapshot() -> dict[str, None]:
        return {
            "server_cpu_percent": None,
            "server_memory_percent": None,
            "server_memory_used_mb": None,
            "server_memory_total_mb": None,
            "server_upload_kbps": None,
            "server_uptime_seconds": None,
        }
