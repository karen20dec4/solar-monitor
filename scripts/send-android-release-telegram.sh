#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_file="$repo_root/android/app/build.gradle.kts"
remote_host="root@celestia.go.ro"
remote_project="/opt/sun-tattva"
expected_bot="sun_tattva_access_bot"
dry_run=0

usage() {
  cat <<'EOF'
Usage: scripts/send-android-release-telegram.sh [--dry-run] [APK]

Sends a signed Solar Monitor APK through @sun_tattva_access_bot. The Telegram
token and admin chat id remain on root@celestia.go.ro in /opt/sun-tattva/.env.
If APK is omitted, SolarMonitor-v<versionName>.apk from the repository root is used.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
    *)
      if [[ -n "${apk_path:-}" ]]; then
        printf 'Only one APK may be supplied.\n' >&2
        exit 2
      fi
      apk_path="$1"
      shift
      ;;
  esac
done

version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$build_file" | head -n 1)"
apk_path="${apk_path:-$repo_root/SolarMonitor-v${version_name}.apk}"
apk_path="$(realpath "$apk_path")"

if [[ ! -f "$apk_path" ]]; then
  printf 'APK not found: %s\n' "$apk_path" >&2
  exit 1
fi

filename="$(basename "$apk_path")"
if [[ ! "$filename" =~ ^[A-Za-z0-9._-]+\.apk$ ]]; then
  printf 'Unsafe APK filename: %s\n' "$filename" >&2
  exit 1
fi

android_home="${ANDROID_HOME:-/opt/android-sdk}"
aapt="$android_home/build-tools/34.0.0/aapt"
if [[ ! -x "$aapt" ]]; then
  printf 'aapt not found: %s\n' "$aapt" >&2
  exit 1
fi

badging="$($aapt dump badging "$apk_path" | sed -n '1p')"
package_name="$(sed -nE "s/^package: name='([^']+)'.*/\1/p" <<<"$badging")"
apk_version_code="$(sed -nE "s/.*versionCode='([^']+)'.*/\1/p" <<<"$badging")"
apk_version_name="$(sed -nE "s/.*versionName='([^']+)'.*/\1/p" <<<"$badging")"

if [[ "$package_name" != "com.rolling7.solar" || -z "$apk_version_code" || -z "$apk_version_name" ]]; then
  printf 'Refusing to send an unrecognized APK: %s\n' "$badging" >&2
  exit 1
fi

sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
size_bytes="$(stat -c '%s' "$apk_path")"

if [[ "$dry_run" == "1" ]]; then
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$remote_host" \
    "cd '$remote_project' && .venv/bin/python - '$expected_bot'" <<'PY'
import sys
import httpx
from app import config

expected_bot = sys.argv[1]
if not config.TELEGRAM_BOT_TOKEN or config.admin_chat_id_int() is None:
    raise SystemExit("Telegram bot or admin chat is not configured")
response = httpx.get(
    f"https://api.telegram.org/bot{config.TELEGRAM_BOT_TOKEN}/getMe",
    timeout=10.0,
)
data = response.json()
username = data.get("result", {}).get("username")
if not data.get("ok") or username != expected_bot:
    raise SystemExit("Configured Telegram bot does not match the expected bot")
print("telegram_delivery_ready: True")
print("bot_username: @" + username)
print("admin_chat_configured: True")
PY
  printf 'dry_run: %s · v%s (%s) · %s bytes · %s\n' \
    "$filename" "$apk_version_name" "$apk_version_code" "$size_bytes" "$sha256"
  exit 0
fi

remote_apk="$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$remote_host" \
  'mktemp /tmp/solar-monitor-release.XXXXXX.apk')"
case "$remote_apk" in
  /tmp/solar-monitor-release.*.apk) ;;
  *)
    printf 'Unexpected remote temporary path.\n' >&2
    exit 1
    ;;
esac

cleanup_remote_apk() {
  ssh -o BatchMode=yes "$remote_host" "rm -f -- '$remote_apk'" >/dev/null 2>&1 || true
}
trap cleanup_remote_apk EXIT

scp -q -o BatchMode=yes "$apk_path" "$remote_host:$remote_apk"
ssh -o BatchMode=yes "$remote_host" \
  "cd '$remote_project' && .venv/bin/python - '$remote_apk' '$filename' '$apk_version_name' '$apk_version_code' '$sha256' '$size_bytes' '$expected_bot'" <<'PY'
import sys
import httpx
from app import config

path, filename, version_name, version_code, sha256, size_bytes, expected_bot = sys.argv[1:]
if not config.TELEGRAM_BOT_TOKEN or config.admin_chat_id_int() is None:
    raise SystemExit("Telegram bot or admin chat is not configured")

identity = httpx.get(
    f"https://api.telegram.org/bot{config.TELEGRAM_BOT_TOKEN}/getMe",
    timeout=10.0,
).json()
if not identity.get("ok") or identity.get("result", {}).get("username") != expected_bot:
    raise SystemExit("Configured Telegram bot does not match the expected bot")

caption = (
    f"☀️ Solar Monitor v{version_name}\n"
    f"Android release semnat · versionCode {version_code}\n"
    f"SHA-256: {sha256}"
)
with open(path, "rb") as apk:
    response = httpx.post(
        f"https://api.telegram.org/bot{config.TELEGRAM_BOT_TOKEN}/sendDocument",
        data={"chat_id": config.admin_chat_id_int(), "caption": caption},
        files={"document": (filename, apk, "application/vnd.android.package-archive")},
        timeout=90.0,
    )
data = response.json()
if not data.get("ok"):
    raise SystemExit(f"Telegram send failed: {data.get('description', response.status_code)}")

result = data["result"]
document = result.get("document", {})
if int(document.get("file_size", -1)) != int(size_bytes):
    raise SystemExit("Telegram reported an unexpected file size")
print("telegram_send_ok: True")
print("bot_username: @" + expected_bot)
print("message_id:", result.get("message_id"))
print("filename:", document.get("file_name"))
print("file_size:", document.get("file_size"))
PY
