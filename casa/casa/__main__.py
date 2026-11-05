"""Unelte de administrare: python -m casa <comanda> (rulate in container: docker exec)."""
import argparse
import getpass
import os
import shutil
import sys
import tempfile
from datetime import datetime
from zoneinfo import ZoneInfo

from .auth import Auth
from .cloud import CloudSession, LoginFailed
from .config import Config
from .devices import DeviceInfo, apply_scan, load_devices, save_devices
from .notify import make_notify
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


def _cloud(cfg: Config) -> CloudSession:
    return CloudSession(cfg.data_dir, make_notify(cfg.ntfy_url))


def _print_devices(cfg: Config) -> None:
    """Tabel fara chei locale: nume, tip, id, IP si protocol, DP de masurare."""
    devices = load_devices(cfg.data_dir)
    print(f"{'NUME':<22}{'TIP':<8}{'ID':<26}{'IP':<20}{'MASURAT'}")
    for d in devices:
        metered = d.kind == "plug" and "power" in d.dps
        where = f"{d.ip} ({d.version})" if d.ip else ("-" if d.kind != "plug" else "NECUNOSCUT")
        print(f"{d.name:<22}{d.kind:<8}{d.id:<26}{where:<20}{'da' if metered else 'nu'}")
    print(f"{len(devices)} dispozitive.")


def _cmd_login(args, cfg: Config) -> int:
    user_code = args.user_code or input("Cod utilizator Smart Life (Eu > Setari > Cont si securitate): ").strip()
    try:
        _cloud(cfg).login_interactive(user_code)
    except LoginFailed as e:
        print(f"Eroare: {e}", file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001 - textul exceptiei poate contine URL cu tokenul QR
        print(f"Eroare: login esuat ({type(e).__name__}). Verifica reteaua si codul de utilizator.",
              file=sys.stderr)
        return 1
    _print_devices(cfg)
    return 0


def _cmd_sync(args, cfg: Config) -> int:
    try:
        _cloud(cfg).sync()
    except LoginFailed as e:
        print(f"Eroare: {e}", file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001
        print(f"Eroare: sincronizare esuata ({type(e).__name__}). Verifica reteaua si sesiunea.",
              file=sys.stderr)
        return 1
    _print_devices(cfg)
    return 0


def _discover() -> dict:
    """Asculta anunturile UDP ale prizelor din LAN (6666/6667/7000). Merge doar cu `--network host`."""
    import tinytuya
    old, tmp = os.getcwd(), tempfile.mkdtemp()
    os.chdir(tmp)  # tinytuya citeste devices.json din directorul curent; sa nu-l vada pe al nostru (chei)
    try:
        raw = tinytuya.deviceScan(verbose=False, maxretry=None, color=False, poll=False, byID=True)
    finally:
        os.chdir(old)
        shutil.rmtree(tmp, ignore_errors=True)
    return {k: {"ip": v.get("ip"), "version": v.get("version")} for k, v in raw.items() if isinstance(v, dict)}


def _cmd_scan(args, cfg: Config) -> int:
    if not any(d.kind == "plug" for d in load_devices(cfg.data_dir)):
        print("Nicio priza in devices.json: ruleaza intai `python -m casa sync`.", file=sys.stderr)
        return 1
    print("Ascult prizele din retea (~20 s)...")
    found = _discover()
    if not found:
        print("Nicio priza nu s-a anuntat. Comanda trebuie rulata cu `docker run --network host` "
              "(scripts/casa-sync.sh), iar firewall-ul trebuie sa lase UDP 6666/6667/7000 din LAN.",
              file=sys.stderr)
        return 1
    try:
        changes, unknown = apply_scan(cfg.data_dir, found)
    except ValueError as e:
        print(f"Eroare: {e}", file=sys.stderr)
        return 1
    for name, ip, version in changes:
        print(f"Actualizat: {name} -> {ip} (protocol {version})")
    for dev_id, ip in unknown:
        print(f"Necunoscut: {dev_id} la {ip} (nu e in devices.json; ruleaza sync daca e o priza noua din Smart Life)")
    if not changes:
        print("Nicio schimbare: IP-urile stiute sunt corecte.")
    _print_devices(cfg)
    return 0


# Pentru CASA_DEMO=1: aceleasi id-uri ca in prizele reale (nume din aplicatia Tuya), fara chei.
_DEMO_PLUGS = (
    ("bf5cc0f2db15b9eab2a0zy", "Boiler", True),
    ("bf3bac46d1a367e845thx2", "Living sus", True),
    ("bf7b3c62e47bfd15fczfov", "Priza cam Georgi", True),
    ("bfc30380ff8a29c0d88wcz", "Bucatarie", False),
    ("bf3897ad46cd66d32cww3j", "Lumina Curte", False),
)
_DEMO_SENSORS = (
    ("demo_sensor_etaj", "Temp Etaj"),
    ("demo_sensor_afara", "Temp afara"),
    ("bfd920a7665be19c4cjytc", "Temp BECI"),
)


def _has_local_keys(cfg: Config) -> bool:
    try:
        data = JsonStore(os.path.join(cfg.data_dir, "devices.json")).load({"devices": []})
    except ValueError:
        return True  # fisier ilizibil: nu stim ce contine, nu-l suprascriem
    return any(d.get("local_key") for d in data.get("devices", []))


def _cmd_demo_devices(args, cfg: Config) -> int:
    if _has_local_keys(cfg) and not args.force:
        print("devices.json contine chei locale reale (sau nu poate fi citit): nu il suprascriu. "
              "Foloseste --force daca vrei sa-l inlocuiesti cu cel de demonstratie.", file=sys.stderr)
        return 1
    devices = []
    for dev_id, name, metered in _DEMO_PLUGS:
        dps = {"switch": 1, "countdown": 9}
        scales = {}
        if metered:
            dps.update({"current": 18, "power": 19, "voltage": 20})
            scales = {"power": 1, "voltage": 1, "current": 0}
        devices.append(DeviceInfo(id=dev_id, name=name, kind="plug", local_key=None, ip=None, version=None,
                                  dps=dps, scales=scales, category="cz", raw_name=name))
    for dev_id, name in _DEMO_SENSORS:
        devices.append(DeviceInfo(id=dev_id, name=name, kind="sensor", local_key=None, ip=None, version=None,
                                  category="wsdcg", raw_name=name))
    save_devices(cfg.data_dir, devices)
    _print_devices(cfg)
    return 0


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
    p = sub.add_parser("login", help="autentificare Smart Life prin cod QR (cere codul de utilizator)")
    p.add_argument("user_code", nargs="?", help="cod utilizator din aplicatia Smart Life (altfel se cere interactiv)")
    p.set_defaults(fn=_cmd_login)
    p = sub.add_parser("sync", help="descarca dispozitivele si cheile locale in devices.json")
    p.set_defaults(fn=_cmd_sync)
    p = sub.add_parser("scan", help="gaseste IP-ul prizelor in LAN (doar cu --network host: scripts/casa-sync.sh)")
    p.set_defaults(fn=_cmd_scan)
    p = sub.add_parser("demo-devices", help="scrie devices.json de demonstratie (CASA_DEMO=1), fara chei")
    p.add_argument("--force", action="store_true", help="suprascrie si un devices.json cu chei locale")
    p.set_defaults(fn=_cmd_demo_devices)
    args = parser.parse_args(argv)
    return args.fn(args, Config.from_env())


if __name__ == "__main__":
    sys.exit(main())
