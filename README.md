# WearBridge (clean-room phone + watch stack)

This project is a fresh implementation under `/scm/vibe/watchadmin/wearbridge`.
It does **not** reuse source code from the existing `wearload` app.

## Current scope

- Phone app (`:wearbridge-phone`)
- Watch companion app (`:wearbridge-watch`)
- WearLoad-compatible protocol on both sides
- Core flows implemented end-to-end:
  - Request watch app sync
  - Receive chunked watch app list
  - Send APK/APKS install payload to watch
  - Receive install status/progress logs from watch
  - Request app delete on watch
  - Request app export from watch
  - Receive exported archive (`/apk-export`) and save to `Downloads/WearBridge`
  - Companion version check

## Protocol compatibility implemented

Message paths:

- `/request-sync`
- `/app-list-start`
- `/app-list-chunk`
- `/app-list-end`
- `/delete-app`
- `/request-apk`
- `/log-message`
- `/check-companion`
- `/check-companion-response`

Data layer paths and keys:

- Install payload path prefix: `/apk/`
  - `package_name`
  - `apk_count`
  - `apk_0..n` (Asset)
  - `file_name_0..n` (String)
  - `apk_size_0..n` (Long)
- Export payload path: `/apk-export`
  - `apk_file` (Asset)
  - `package_name` (String)
  - `app_label` (String)

## Build

```bash
cd /scm/vibe/watchadmin/wearbridge
./gradlew :wearbridge-phone:assembleDebug :wearbridge-watch:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/wearbridge-phone-debug.apk`
- `watch/build/outputs/apk/debug/wearbridge-watch-debug.apk`

## Watch Permissions (Required)

On the watch, make sure `WearBridge Watch` has these permissions/special access enabled:

- `System settings` (modify system settings)
- `Install apps` / `Install unknown apps`

Without these, watch-side install flows can fail or stall waiting for system restrictions.

## APK Share Support (Phone App)

The app supports receiving APK/APKS files through Android share intents.

Supported incoming actions:

- `ACTION_SEND`
- `ACTION_SEND_MULTIPLE`
- `ACTION_VIEW` (for APK content/file URIs)

Supported MIME types:

- `application/vnd.android.package-archive`
- `application/octet-stream`
- `application/zip`
- `application/x-zip-compressed`

Behavior:

- Extracts URIs from `EXTRA_STREAM`, `ClipData`, and `intent.data` fallback.
- Accepts one or many files.
- Tries to infer package name automatically from APK/APKS content.
- Lets you edit/override package name before sending install payload to watch.

Quick test:

1. Build and install `wearbridge-phone-debug.apk` on your phone.
2. In a file manager, Share an `.apk` or `.apks` file to `WearBridge`.
3. Confirm selected files in app UI and tap `Send install payload`.

## ADB Push via Phone App (PC -> Phone -> Watch)

Use this when you want to trigger watch install from your PC, but route through the WearBridge app on the phone.
The script:

1. expands artifact into one or more APK files,
2. pushes them to WearBridge app-specific phone storage,
3. launches WearBridge with an explicit intent,
4. WearBridge forwards install payload to the watch via Data Layer.

Command:

```bash
cd /scm/vibe/watchadmin/wearbridge
./tools/watch_adb_install.sh -s <PHONE_SERIAL> <artifact>
```

Supported artifacts:

- `.apk`
- `.apks` / `.zip` containing split APKs
- directory containing `*.apk`

Examples:

```bash
./tools/watch_adb_install.sh app-release.apk
./tools/watch_adb_install.sh -s NRT8R8KRCUJV6XSO app-release.apk
./tools/watch_adb_install.sh watchface.apks
./tools/watch_adb_install.sh --package com.example.app split_bundle.zip
./tools/watch_adb_install.sh --no-auto-send app-release.apk
./tools/watch_adb_install.sh --poll-seconds 180 app-release.apk
./tools/watch_adb_install.sh --no-poll app-release.apk
```

Options:

- `-s, --serial`: phone device serial.
- `--package`: override package name used by WearBridge.
- `--no-auto-send`: only prefill files in app; do not immediately forward to watch.
- `--poll-seconds N`: poll app progress log for session updates (default: `120`).
- `--no-poll`: skip polling.

Per-session files on phone:

- Base: `/sdcard/Android/data/io.vibe.wearbridge/files/WearBridgeSessions`
- For each run: `<session-id>/`
  - `payload/` (APK files pushed from PC)
  - `progress.log` (timestamped session progress)
- Script polls `progress.log` for the active session.
- Script prints a ready-to-run cleanup command for that session directory.
- In auto-send mode, script returns:
  - success only on explicit `reason=watch_terminal`,
  - failure on any other terminal reason or if no terminal status is received by timeout.
