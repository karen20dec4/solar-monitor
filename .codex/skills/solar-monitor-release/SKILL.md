---
name: solar-monitor-release
description: Build, verify, package, and report signed APK releases for the Solar Monitor project. Use when the user says "fa-mi un release", "da-mi un release", asks for a new APK, asks what Android version the app is at, or needs the Solar Monitor Android release workflow repeated from the repo on Windows or HP Linux.
---

# Solar Monitor Release

Use this skill for Solar Monitor Android release requests. The source of truth for release automation is the tracked Linux script `scripts/build-android-release.sh`.

## Workflow

1. Inspect repo state and Android version:
   - `git status --short`
   - `rg -n "versionCode|versionName" android/app/build.gradle.kts`
   - `rg --files -g "*.apk" -g "!**/build/**"`
2. Increment every delivered release:
   - Every new APK delivered to the user or sent through Telegram must use `versionCode + 1` and
     `versionName + 0.01`, even when the user asks to rebuild the current APK.
   - Never overwrite or resend an APK filename/version that was already delivered.
   - Only a local diagnostic build that is not delivered may keep the current version.
   - Update `android/app/build.gradle.kts` and `COPILOT_CONTEXT.md`, then commit.
3. On HP/Linux, build with:
   ```bash
   cd /opt/solar-monitor
   scripts/build-android-release.sh
   ```
4. Verify the script output:
   - package must be `com.rolling7.solar`;
   - confirm `versionCode` and `versionName` from `aapt dump badging`;
   - record APK path, size, and SHA256.
5. After all APK checks pass, send it once through the configured Telegram bot unless the user explicitly
   asks not to:
   ```bash
   scripts/send-android-release-telegram.sh /opt/solar-monitor/SolarMonitor-v<versionName>.apk
   ```
   This uses SSH to `root@celestia.go.ro`; the bot token and `ADMIN_CHAT_ID` remain in
   `/opt/sun-tattva/.env`. First run with `--dry-run` when diagnosing delivery. Confirm returned filename,
   size, and Telegram-downloaded SHA-256. A Telegram failure does not invalidate or delete the local APK.
6. If repo files changed, stage only intended files, commit, and push.
7. Report final version, local APK path, SHA256, Telegram delivery/message id, commit hash if any, and
   whether server rebuild is needed.

## Rules

- A pure APK release build does not require `docker compose up -d --build api`.
- For API/server/deploy changes, run or remind: `cd /opt/solar-monitor && docker compose up -d --build api`.
- APKs, build directories, keystores, and `keystore.properties` stay ignored and untracked.
- Never print, copy locally, or commit the Telegram bot token. Do not modify the dirty Sun Tattva worktree
  merely to send a Solar Monitor release.
- The system remains READ-ONLY toward the Growatt inverter.

## Common Fixes

- If `scripts/build-android-release.sh` is not executable after checkout, run `chmod +x scripts/build-android-release.sh` or execute it with `bash scripts/build-android-release.sh`.
- On HP the expected defaults are `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `ANDROID_HOME=/opt/android-sdk`, and `/opt/gradle-8.9/bin/gradle`.
- If Gradle reports tasks `UP-TO-DATE`, the release can still be valid; trust the APK only after `aapt`/SHA256 verification.
