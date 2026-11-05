"""Notificari push prin ntfy (acelasi mecanism ca la citirea BMS: POST simplu, fara dependente)."""
import logging
import unicodedata
import urllib.request
from typing import Callable

log = logging.getLogger("casa.notify")

Notify = Callable[[str, str, str], None]


def _ascii(text: str) -> str:
    """Antetele HTTP sunt latin-1: scoatem diacriticele din titlu."""
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")


def make_notify(ntfy_url: str) -> Notify:
    """callable(title, message, priority) -> None; erorile se logheaza, nu se arunca.

    Mesajele nu trebuie sa contina secrete (parole, chei, token-uri).
    """
    def notify(title: str, message: str, priority: str = "default") -> None:
        log.info("ALERTA: %s - %s", title, message)
        if not ntfy_url:
            return
        try:
            req = urllib.request.Request(
                ntfy_url, data=message.encode("utf-8"), method="POST",
                headers={"Title": _ascii(title), "Priority": priority, "Tags": "house"})
            urllib.request.urlopen(req, timeout=5).close()
        except Exception as exc:  # noqa: BLE001
            log.warning("ntfy POST esuat: %s", exc)

    return notify
