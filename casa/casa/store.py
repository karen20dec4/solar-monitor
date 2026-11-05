"""Stocare JSON atomica (fisier temporar + fsync + os.replace), mod 600."""
import copy
import json
import os
import tempfile
import threading
from typing import Callable


class JsonStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()

    def load(self, default: dict) -> dict:
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            return copy.deepcopy(default)
        except ValueError as e:
            # Nu resetam niciodata in tacere programarile: fisierul stricat ramane pe disc.
            raise ValueError(f"fisier JSON corupt: {self.path}: {e}") from e

    def save(self, data: dict, mode: int = 0o600) -> None:
        directory = os.path.dirname(os.path.abspath(self.path))
        fd, tmp = tempfile.mkstemp(dir=directory, prefix=".store-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False)
                f.flush()
                os.fsync(f.fileno())
            os.chmod(tmp, mode)
            os.replace(tmp, self.path)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    def update(self, fn: Callable[[dict], None], default: dict) -> dict:
        with self._lock:
            data = self.load(default)
            fn(data)
            self.save(data)
            return data
