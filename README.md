# 7xDash (head unit)

Dashcam and shortcut app for the Zeekr head unit, in the Zeekr OS 7 style. English and Thai.

Based on [zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car), itself based on
[EVCam](https://github.com/suyunkai/EVCam). Released under GPL-3.0 (see `LICENSE` and `NOTICE.md`).

## Features

- Drive recorder for surround and cabin cameras, with the driving info bar
- Photo, instant capture, flash-to-pass capture
- Rear camera mirror, blind-spot view on the screen or the HUD
- Other apps' notifications on the HUD
- Dim display, megaphone
- Smart sticker (ZEEKR_button BLE) and shortcuts
- Remote view, Google Drive upload

## Install

Download the APK from [Releases](https://github.com/kitnipanz/7xdash-companion/releases), then on a computer:

```bash
adb install -r 7xDash-<version>.apk
```

Later updates: **Settings → Check for updates** on the car.

Shortcuts on every screen and HUD notifications need one permission, granted once from a computer:

```bash
adb shell pm grant lab.sevenx.dash android.permission.WRITE_SECURE_SETTINGS
```

## Build

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Google Drive sign-in needs `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` in `local.properties`
(or as GitHub Actions secrets). Without them, paste a client in Settings → Google Drive.

## Release

Bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section to `CHANGELOG.md`,
then push a tag `v<versionName>`. GitHub Actions builds, tests and publishes the release.
