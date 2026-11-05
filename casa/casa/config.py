"""Configurare serviciu CASA din variabile de mediu (aceleasi nume ca la `api`)."""
import os
from dataclasses import dataclass


def _bool(valoare: str) -> bool:
    return valoare.strip().lower() in ("1", "true", "yes", "da", "on")


@dataclass(frozen=True)
class Config:
    data_dir: str
    influx_url: str
    influx_token: str
    influx_org: str
    bucket_live: str
    bucket_history: str
    ntfy_url: str
    tz: str = "Europe/Bucharest"
    poll_s: float = 5.0
    demo: bool = False

    @classmethod
    def from_env(cls) -> "Config":
        e = os.environ
        return cls(
            data_dir=e.get("CASA_DATA_DIR", "/data"),
            influx_url=e.get("INFLUX_URL", ""),
            influx_token=e.get("INFLUX_TOKEN", ""),
            influx_org=e.get("INFLUX_ORG", ""),
            bucket_live=e.get("INFLUX_BUCKET_LIVE", "live"),
            bucket_history=e.get("INFLUX_BUCKET_HISTORY", "history"),
            ntfy_url=e.get("NTFY_URL", ""),
            poll_s=float(e.get("CASA_POLL_S", "5")),
            demo=_bool(e.get("CASA_DEMO", "false")),
        )
