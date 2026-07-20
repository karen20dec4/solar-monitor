#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVD_NAME="${SOLAR_AVD_NAME:-SolarMonitor_API_34}"
PACKAGE="com.rolling7.solar"
ACTIVITY="$PACKAGE/.MainActivity"
APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
ARTIFACT_DIR="${SOLAR_EMULATOR_ARTIFACT_DIR:-$REPO_ROOT/android/build/emulator-artifacts}"
EMULATOR_LOG="$ARTIFACT_DIR/emulator.log"

usage() {
    printf '%s\n' \
        "Usage: $0 doctor|start|wait|build|install|launch|screenshot|status|verify|stop [screenshot.png]" \
        "" \
        "  doctor      Verify SDK, emulator, KVM and AVD prerequisites" \
        "  start       Start the configured AVD headlessly" \
        "  wait        Wait until Android reports sys.boot_completed=1" \
        "  build       Build app-debug.apk" \
        "  install     Install the existing debug APK" \
        "  launch      Force-stop and launch Solar Monitor" \
        "  screenshot  Save a PNG and UI hierarchy under android/build" \
        "  status      Show AVD, adb, Android, app and saved-theme status" \
        "  verify      Run doctor, start, wait, build, install, launch and capture" \
        "  stop        Shut down the running emulator"
}

require_file() {
    local path="$1"
    local hint="$2"
    if [[ ! -e "$path" ]]; then
        printf 'Missing: %s\n%s\n' "$path" "$hint" >&2
        exit 1
    fi
}

emulator_serial() {
    "$ADB" devices | awk '/^emulator-/ { print $1; exit }'
}

online_emulator_serial() {
    "$ADB" devices | awk '/^emulator-/ && $2 == "device" { print $1; exit }'
}

doctor() {
    require_file "$ADB" "Install platform-tools with sdkmanager."
    require_file "$EMULATOR" "Install it with: sdkmanager --sdk_root=$SDK_ROOT emulator"
    require_file "/dev/kvm" "Enable CPU virtualization/KVM on the host."
    if ! "$EMULATOR" -list-avds | awk -v wanted="$AVD_NAME" '$0 == wanted { found=1 } END { exit !found }'; then
        printf 'Missing AVD: %s\n' "$AVD_NAME" >&2
        printf 'Create it with avdmanager and system-images;android-34;google_apis;x86_64.\n' >&2
        exit 1
    fi
    printf 'SDK: %s\nAVD: %s\nKVM: available\n' "$SDK_ROOT" "$AVD_NAME"
    "$EMULATOR" -accel-check 2>&1 | grep -m1 'KVM' || true
}

start_emulator() {
    mkdir -p "$ARTIFACT_DIR"
    if [[ -n "$(emulator_serial)" ]]; then
        printf 'Emulator already present: %s\n' "$(emulator_serial)"
        return
    fi
    nohup "$EMULATOR" \
        -avd "$AVD_NAME" \
        -no-window \
        -no-audio \
        -no-boot-anim \
        -gpu swiftshader_indirect \
        -no-snapshot \
        >"$EMULATOR_LOG" 2>&1 &
    printf 'Started %s (log: %s)\n' "$AVD_NAME" "$EMULATOR_LOG"
}

wait_for_boot() {
    local serial boot
    for _ in $(seq 1 90); do
        serial="$(online_emulator_serial)"
        if [[ -n "$serial" ]]; then
            boot="$($ADB -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
            if [[ "$boot" == "1" ]]; then
                printf 'Boot complete: %s\n' "$serial"
                return
            fi
        fi
        sleep 2
    done
    printf 'Emulator did not finish booting. Inspect %s\n' "$EMULATOR_LOG" >&2
    exit 1
}

require_online_serial() {
    local serial
    serial="$(online_emulator_serial)"
    if [[ -z "$serial" ]]; then
        printf 'No online emulator. Run: %s start && %s wait\n' "$0" "$0" >&2
        exit 1
    fi
    printf '%s' "$serial"
}

build_app() {
    (cd "$REPO_ROOT/android" && ./gradlew :app:assembleDebug)
}

install_app() {
    local serial
    require_file "$APK" "Run: $0 build"
    serial="$(require_online_serial)"
    "$ADB" -s "$serial" install -r "$APK"
}

launch_app() {
    local serial
    serial="$(require_online_serial)"
    "$ADB" -s "$serial" shell am force-stop "$PACKAGE"
    "$ADB" -s "$serial" shell am start -n "$ACTIVITY"
}

capture_screen() {
    local requested_path="${1:-}"
    local serial stamp screenshot hierarchy logcat_file
    serial="$(require_online_serial)"
    mkdir -p "$ARTIFACT_DIR"
    stamp="$(date +%Y%m%d-%H%M%S)"
    screenshot="${requested_path:-$ARTIFACT_DIR/solar-monitor-$stamp.png}"
    hierarchy="$ARTIFACT_DIR/window-$stamp.xml"
    logcat_file="$ARTIFACT_DIR/logcat-$stamp.txt"
    mkdir -p "$(dirname "$screenshot")"
    "$ADB" -s "$serial" exec-out screencap -p >"$screenshot"
    "$ADB" -s "$serial" shell uiautomator dump /sdcard/solar-window.xml >/dev/null
    "$ADB" -s "$serial" pull /sdcard/solar-window.xml "$hierarchy" >/dev/null
    "$ADB" -s "$serial" logcat -b all -d -t 400 >"$logcat_file"
    printf 'Screenshot: %s\nUI hierarchy: %s\nLogcat: %s\n' "$screenshot" "$hierarchy" "$logcat_file"
}

show_status() {
    local serial
    printf 'Configured AVD: %s\nAvailable AVDs:\n' "$AVD_NAME"
    "$EMULATOR" -list-avds | sed 's/^/  /'
    printf 'ADB devices:\n'
    "$ADB" devices -l
    serial="$(online_emulator_serial)"
    if [[ -z "$serial" ]]; then
        return
    fi
    printf 'Android: '
    "$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r'
    printf 'Resolution: '
    "$ADB" -s "$serial" shell wm size | tr -d '\r'
    printf 'App package: '
    "$ADB" -s "$serial" shell dumpsys package "$PACKAGE" 2>/dev/null \
        | sed -n 's/.*versionName=/versionName=/p' | head -1 || true
    printf 'App process: '
    "$ADB" -s "$serial" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true
    printf 'Saved dashboard theme:\n'
    "$ADB" -s "$serial" shell run-as "$PACKAGE" \
        cat shared_prefs/solar_dashboard_style.xml 2>/dev/null || printf '  not saved yet (Retro default)\n'
}

stop_emulator() {
    local serial
    serial="$(emulator_serial)"
    if [[ -z "$serial" ]]; then
        printf 'No emulator is running.\n'
        return
    fi
    "$ADB" -s "$serial" emu kill
}

verify_all() {
    local screenshot_path="${1:-}"
    doctor
    start_emulator
    wait_for_boot
    build_app
    install_app
    local serial
    serial="$(require_online_serial)"
    "$ADB" -s "$serial" logcat -c
    launch_app
    sleep 5
    show_status
    capture_screen "$screenshot_path"
    if [[ -z "$("$ADB" -s "$serial" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
        printf 'Solar Monitor process is not running after launch.\n' >&2
        exit 1
    fi
    if "$ADB" -s "$serial" logcat -b crash -d | grep -Fq "$PACKAGE"; then
        printf 'Fatal Solar Monitor exception detected in logcat.\n' >&2
        exit 1
    fi
    printf 'Solar Monitor emulator verification passed.\n'
}

command="${1:-}"
case "$command" in
    doctor) doctor ;;
    start) start_emulator ;;
    wait) wait_for_boot ;;
    build) build_app ;;
    install) install_app ;;
    launch) launch_app ;;
    screenshot) capture_screen "${2:-}" ;;
    status) doctor; show_status ;;
    verify) verify_all "${2:-}" ;;
    stop) stop_emulator ;;
    *) usage; exit 2 ;;
esac
