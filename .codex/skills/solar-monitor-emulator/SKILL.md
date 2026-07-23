---
name: solar-monitor-emulator
description: Build, install, launch, inspect, and screenshot the Solar Monitor Android app in the local headless Android emulator. Use when the user asks to verify Android UI changes, test the Retro or Simple dashboard, check theme persistence, capture emulator screenshots, inspect app crashes/logcat, confirm the APK version, or diagnose the local Android emulator/AVD setup on the HP Linux server.
---

# Solar Monitor Emulator

Use the tracked helper for deterministic emulator checks:

```bash
cd /opt/solar-monitor
.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh verify
```

This verifies KVM and the AVD, starts Android headlessly if needed, waits for boot, builds and installs the
debug APK, launches the app, captures a screenshot/UI hierarchy/logcat, and fails on a detected app crash.
Artifacts go to the gitignored `android/build/emulator-artifacts/` directory.

## Environment

- SDK: `/opt/android-sdk`
- Emulator: `/opt/android-sdk/emulator/emulator`
- AVD: `SolarMonitor_API_34`
- Device profile: Pixel 6, 1080×2400
- Image: `system-images;android-34;google_apis;x86_64`
- Package/activity: `com.rolling7.solar/.MainActivity`
- Acceleration: `/dev/kvm`
- Headless renderer: `swangle` (stable with Emulator 36.6.11 on this host)
- Process lifetime: transient `solar-monitor-emulator.service` created by `systemd-run`

Use `ANDROID_SDK_ROOT` or `ANDROID_HOME` to override the SDK and `SOLAR_AVD_NAME` to override the AVD.
Use `SOLAR_EMULATOR_GPU` only for diagnosis; the verified default is `swangle`. Do not switch back to
`swiftshader_indirect`: on this host it has repeatedly terminated Emulator 36.6.11 with `SIGSEGV`.

## Commands

```bash
SCRIPT=.codex/skills/solar-monitor-emulator/scripts/emulator-check.sh
$SCRIPT doctor
$SCRIPT start
$SCRIPT wait
$SCRIPT build
$SCRIPT install
$SCRIPT launch
$SCRIPT screenshot
$SCRIPT retro-tabs
$SCRIPT status
$SCRIPT stop
```

Pass a PNG path as the second argument to `screenshot` or `verify` when a stable destination is needed.

## Verification workflow

1. Run `verify` and retain its screenshot, UI XML, and logcat paths.
2. Inspect the PNG with the local image-viewing tool; compilation alone is not visual proof.
3. Check `status` for Android version, resolution, installed app version, process PID, and saved theme.
4. For theme-switch testing, open Settings in the emulator, switch `Retro / Simple`, relaunch the app, and
   confirm `shared_prefs/solar_dashboard_style.xml` through `adb shell run-as com.rolling7.solar`.
5. Inspect logcat for `AndroidRuntime`, `FATAL EXCEPTION`, layout symptoms, or networking failures when the
   screenshot does not show live data.
6. Leave the emulator running while iterating; use `stop` when work is finished or resources are needed.

For the fixed Retro layout, run `retro-tabs` after any Compose or Photoshop-asset change. It taps all four
navigation regions on the pinned Pixel 6 AVD, saves `retro-tab-{tablou,energie,sistem,setari}.png`, confirms
the selected-tab semantics, and fails if Android exposes a scrollable container on any Retro page.

The emulator uses the app's existing read-only HTTPS API. Do not add inverter writes or direct serial access.

## Repair/setup

Only when `doctor` reports missing components, install the pinned packages and recreate the AVD:

```bash
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  "emulator" "system-images;android-34;google_apis;x86_64"

echo no | /opt/android-sdk/cmdline-tools/latest/bin/avdmanager create avd \
  --name SolarMonitor_API_34 \
  --package "system-images;android-34;google_apis;x86_64" \
  --device pixel_6 \
  --force
```

Do not recreate a healthy AVD. Report whether the check was compile-only or included a rendered screenshot.
