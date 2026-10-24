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
- Process lifetime: transient `solar-monitor-emulator.service` created by `systemd-run`; keep it alive only
  during active development or verification

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
$SCRIPT profile note9-large   # sau note9 / reset
$SCRIPT layout-check
$SCRIPT pinch                  # zoom elastic cu doua degete (needs adb root)
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
6. Leave the emulator running only while actively iterating. At the end of every emulator/release task,
   always run `stop` and verify that `solar-monitor-emulator.service` is inactive, no `qemu-system-x86`
   process remains, and ADB lists no emulator. Keep it running after the task only when the user explicitly
   asks. Never leave the AVD idle between sessions: on this host it can consume multiple CPU cores and
   roughly 5 GB RAM.

For the fixed Retro layout, run `retro-tabs` after any Compose or Photoshop-asset change. It taps all four
navigation regions on the pinned Pixel 6 AVD, saves `retro-tab-{tablou,energie,sistem,setari}.png`, confirms
the selected-tab semantics, and fails if Android exposes a scrollable container on any Retro page.

**Verify on the user's phone, not on the Pixel 6 default.** The user's phone is a Galaxy Note 9: 1080×2220,
420 dpi, three-button Android navigation (48 dp), system font ~130% (read from their screenshot
`/opt/logs/aplicatie-mobil.jpg`). The Pixel 6 AVD has a 45 dp status bar (cutout) against the Note 9's ~25 dp and
gesture navigation, so the same layout has ~20–45 dp *more* room there. `profile note9-large` sets
1080×2272 (the extra 52 px make the app's usable height equal the phone's, ~2028 px), 420 dpi, three-button
navigation and font 130%; `profile note9` is the same at 100%; `profile reset` restores the AVD (do it before
`stop`, the overrides persist in the AVD data). `layout-check` then runs `retro-tabs` (which now taps tabs by
their uiautomator bounds, so it also works with three-button navigation) and `check-layout.py`, which fails if
the TABLOU BMS strip overlaps the Bat/Inv plates or the navigation bar, the SISTEM BMS monitor leaves its panel,
the SETARI "Alarma baterie" row overlaps the alarm card or the navigation bar, the ENERGIE subject plates overlap or
the electricity-bill readout ("Factura ...") leaves the top plate or covers its title or LED. Run it on `note9-large`,
`note9` and `reset` for any change to the Retro pages.

`verify` and `layout-check` apply `note9-large` by themselves (`SOLAR_EMULATOR_PROFILE=none` skips it): the user
said to keep testing on this profile until they move to a Samsung S22 FE, and they will send a screenshot then.

`pinch` tests the app-wide two-finger elastic zoom (`ElasticZoom.kt`): it needs `adb root` (done by the command), holds
two fingers down with `sendevent` (`pinch.py hold CX CY D0 D1`), saves `zoom-held.png` (magnified), lifts them and
saves `zoom-after.png` 2.5 s later (back to normal size). `pinch.py release` lifts the fingers by hand.

Playwright does not apply here: the app is native Compose, not a web page. The end-to-end check is the real app
in the emulator against the real API, with the UI hierarchy asserted by `layout-check` and the PNGs inspected.

To see alarm states without a real fault, write a synthetic `bms_alert` point once a second into the `live`
bucket (measurement `bms_alert`, tag `key`, string fields `title`/`msg`/`prio`, `active=1.0`), look at the app,
then delete the measurement (`influx delete ... --predicate '_measurement="bms_alert"'`).

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
