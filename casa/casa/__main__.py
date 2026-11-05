"""Unelte de administrare: python -m casa <comanda> (rulate in container: docker exec)."""
import argparse
import getpass
import os
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from .auth import Auth
from .config import Config
from .store import JsonStore


def _auth(cfg: Config) -> Auth:
    return Auth(JsonStore(os.path.join(cfg.data_dir, "auth.json")))


def _fmt(ts: float, tz: str) -> str:
    return datetime.fromtimestamp(ts, ZoneInfo(tz)).strftime("%Y-%m-%d %H:%M")


def _cmd_set_password(args, cfg: Config) -> int:
    if args.stdin:
        password = sys.stdin.readline().rstrip("\r\n").strip()
    else:
        password = getpass.getpass("Parola noua: ")
        if getpass.getpass("Repeta parola: ") != password:
            print("Parolele nu coincid.", file=sys.stderr)
            return 1
    try:
        _auth(cfg).set_password(password)
    except ValueError as e:
        print(f"Eroare: {e}", file=sys.stderr)
        return 1
    print("Parola a fost setata. Telefoanele deja inregistrate raman valabile.")
    return 0


def _cmd_phones(args, cfg: Config) -> int:
    phones = _auth(cfg).phones()
    if not phones:
        print("Niciun telefon inregistrat.")
        return 0
    print(f"{'ID':<10}{'TELEFON':<62}{'CREAT':<18}{'ULTIMA FOLOSIRE'}")
    for p in phones:
        print(f"{p['phone_id']:<10}{p['device']:<62}{_fmt(p['created'], cfg.tz):<18}{_fmt(p['last_used'], cfg.tz)}")
    return 0


def _cmd_revoke(args, cfg: Config) -> int:
    if _auth(cfg).revoke(args.phone_id):
        print(f"Telefonul {args.phone_id} a fost revocat.")
        return 0
    print(f"Nu exista telefonul {args.phone_id}.", file=sys.stderr)
    return 1


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="python -m casa", description="Administrare CASA (prize si senzori)")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("set-password", help="seteaza parola comuna (se cere de doua ori; niciodata in argv)")
    p.add_argument("--stdin", action="store_true", help="citeste parola dintr-o linie de pe stdin (automatizare)")
    p.set_defaults(fn=_cmd_set_password)
    p = sub.add_parser("phones", help="listeaza telefoanele inregistrate")
    p.set_defaults(fn=_cmd_phones)
    p = sub.add_parser("revoke", help="revoca un telefon dupa id")
    p.add_argument("phone_id")
    p.set_defaults(fn=_cmd_revoke)
    args = parser.parse_args(argv)
    return args.fn(args, Config.from_env())


if __name__ == "__main__":
    sys.exit(main())
