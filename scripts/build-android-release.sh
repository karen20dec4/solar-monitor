#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage: scripts/build-android-release.sh [--repo-root PATH]

Builds the signed Solar Monitor Android release APK, copies it to the
repository root as SolarMonitor-v<versionName>.apk, and prints manifest,
size, and SHA256 verification details.

Environment overrides:
  JAVA_HOME       default: /usr/lib/jvm/java-21-openjdk-amd64
  ANDROID_HOME    default: /opt/android-sdk
  ANDROID_SDK_ROOT default: ANDROID_HOME
  GRADLE_CMD      default: /opt/gradle-8.9/bin/gradle when present, else ./gradlew
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo-root)
      repo_root="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

repo_root="$(cd "$repo_root" && pwd)"
android_dir="$repo_root/android"
build_file="$android_dir/app/build.gradle.kts"
release_apk="$android_dir/app/build/outputs/apk/release/app-release.apk"

if [[ ! -f "$build_file" ]]; then
  echo "ERROR: build.gradle.kts not found at $build_file" >&2
  exit 1
fi

if [[ ! -f "$repo_root/keystore.properties" ]]; then
  echo "ERROR: missing $repo_root/keystore.properties; signed release cannot be built." >&2
  exit 1
fi

version_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$build_file" | head -n 1)"
version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$build_file" | head -n 1)"

if [[ -z "$version_code" || -z "$version_name" ]]; then
  echo "ERROR: could not parse versionCode/versionName from $build_file" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"

if [[ ! -d "$JAVA_HOME" ]]; then
  echo "ERROR: JAVA_HOME does not exist: $JAVA_HOME" >&2
  exit 1
fi

if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "ERROR: ANDROID_HOME does not exist: $ANDROID_HOME" >&2
  exit 1
fi

if [[ -n "${GRADLE_CMD:-}" ]]; then
  gradle_cmd="$GRADLE_CMD"
elif [[ -x /opt/gradle-8.9/bin/gradle ]]; then
  gradle_cmd="/opt/gradle-8.9/bin/gradle"
else
  gradle_cmd="./gradlew"
fi

if [[ "$gradle_cmd" == "./gradlew" && ! -x "$android_dir/gradlew" ]]; then
  chmod +x "$android_dir/gradlew" 2>/dev/null || true
fi

echo "Building Solar Monitor Android release..."
echo "Repo: $repo_root"
echo "Version: $version_name ($version_code)"
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo "Gradle: $gradle_cmd"

(
  cd "$android_dir"
  "$gradle_cmd" :app:assembleRelease
)

if [[ ! -f "$release_apk" ]]; then
  echo "ERROR: release APK not found at $release_apk" >&2
  exit 1
fi

root_apk="$repo_root/SolarMonitor-v${version_name}.apk"
release_hash="$(sha256sum "$release_apk" | awk '{print $1}')"

if [[ -f "$root_apk" ]]; then
  existing_hash="$(sha256sum "$root_apk" | awk '{print $1}')"
  if [[ "$existing_hash" != "$release_hash" ]]; then
    cp -f "$release_apk" "$root_apk"
  fi
else
  cp -f "$release_apk" "$root_apk"
fi

size_bytes="$(stat -c '%s' "$root_apk")"
sha256="$(sha256sum "$root_apk" | awk '{print $1}')"

aapt=""
if command -v aapt >/dev/null 2>&1; then
  aapt="$(command -v aapt)"
elif [[ -d "$ANDROID_HOME/build-tools" ]]; then
  aapt="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -type f -name aapt 2>/dev/null | sort -V | tail -n 1)"
fi

badging=""
if [[ -n "$aapt" && -x "$aapt" ]]; then
  badging="$("$aapt" dump badging "$root_apk" | grep '^package:' | head -n 1 || true)"
else
  badging="WARN: aapt not found; manifest verification skipped"
fi

cat <<EOF

Release APK ready:
  versionName: $version_name
  versionCode: $version_code
  apk: $root_apk
  sizeBytes: $size_bytes
  sha256: $sha256
  badging: $badging
EOF
