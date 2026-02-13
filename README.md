# WearBridge (clean-room phone client)

This project is a fresh implementation under `/scm/vibe/watchadmin/wearbridge`.
It does **not** reuse source code from the existing `wearload` app.

## Current scope

- Phone app only (`:app`)
- Compatible with the existing WearLoad watch app protocol
- Core flows implemented:
  - Request watch app sync
  - Receive chunked watch app list
  - Send APK/APKS install payload to watch
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
./gradlew :app:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/app-debug.apk`

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

1. Build and install `app-debug.apk` on your phone.
2. In a file manager, Share an `.apk` or `.apks` file to `WearBridge`.
3. Confirm selected files in app UI and tap `Send install payload`.

## Direct ADB Install (PC -> Watch)

If you want to install directly from your PC to a watch over ADB (without phone transfer), use:

```bash
cd /scm/vibe/watchadmin/wearbridge
./tools/watch_adb_install.sh -s <WATCH_SERIAL_OR_IP:PORT> <artifact>
```

Supported artifacts:

- `.apk`
- `.apks` / `.zip` containing split APKs
- directory containing `*.apk`

Examples:

```bash
./tools/watch_adb_install.sh app-release.apk
./tools/watch_adb_install.sh -s 192.168.1.55:5555 app-release.apk
./tools/watch_adb_install.sh --downgrade watchface.apks
./tools/watch_adb_install.sh app-release.apk -- --user 0
```

Passthrough behavior:

- `--downgrade` / `-d` is passed directly to `adb install` / `adb install-multiple`.
- Additional adb install arguments can be passed either:
  - with `--adb-arg <arg>` (repeatable), or
  - after `--` at the end of the command.
