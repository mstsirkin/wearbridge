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
```

