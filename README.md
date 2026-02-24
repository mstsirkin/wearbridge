# WearBridge (phone + watch stack)

## Current scope

- Phone app (`:wearbridge-phone`)
- Watch companion app (`:wearbridge-watch`)
- Core flows implemented end-to-end:
  - Request watch app sync
  - Receive chunked watch app list
  - Send APK/APKS install payload to watch
  - Receive install status/progress logs from watch
  - Trigger watch screenshot capture (phone UI / adb via phone)
  - Receive watch screenshot export (`/screenshot-export`) and save to `Pictures/WearBridge`
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
- `/check-capabilities`
- `/check-capabilities-response`
- `/request-screenshot`

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
- Screenshot export payload path: `/screenshot-export`
  - `screenshot_file` (Asset)
  - `request_id` (String, optional)
  - `mime_type` (String, optional)

## Build

```bash
./gradlew :wearbridge-phone:assembleDebug :wearbridge-watch:assembleDebug
```

APK output:

- `app/build/outputs/apk/debug/wearbridge-phone-debug.apk`
- `watch/build/outputs/apk/debug/wearbridge-watch-debug.apk`

## Watch Permissions (Required)

WearBridge declares watch-side manifest permissions for install/delete/query flows:

- `REQUEST_INSTALL_PACKAGES`
- `REQUEST_DELETE_PACKAGES`
- `QUERY_ALL_PACKAGES`

Only `Install unknown apps` behaves like user-configurable special access. The manifest
permissions above are not manually grantable runtime permissions.

For install flows to work reliably, make sure `WearBridge Watch` is allowed to install
unknown apps (source installs) on the watch.

Best-effort adb setup helper (watch device):

```bash
./tools/watch_adb_setup_access.sh -s <WATCH_SERIAL>
```

This helper tries to:

- enable the install-source app-op (`REQUEST_INSTALL_PACKAGES`)
- enable the `WearBridge Screenshot Capture` accessibility service

Some Wear OS builds may still require manual confirmation in Settings.

## Watch Screenshot Setup (Required for Screenshot Feature)

Screenshot capture uses an accessibility service on the watch.

Before screenshots will work:

1. Install and open `WearBridge Watch` on the watch at least once.
2. On the watch, open system Accessibility settings.
3. Enable `WearBridge Screenshot Capture` accessibility service.

You can also try enabling it from adb (watch device) using:

```bash
./tools/watch_adb_setup_access.sh -s <WATCH_SERIAL> --skip-install-appops
```

If the service is disabled, screenshot requests will fail and capability status in the
phone UI will show screenshot as `not ready`.

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

## Watch Screenshot from Phone UI

Use this when you want a screenshot saved to the phone via the Wear Data Layer.

1. Make sure the watch accessibility service (`WearBridge Screenshot Capture`) is enabled.
2. In the phone app, tap `Check companion` to refresh capability status (optional but useful).
3. Tap `Screenshot`.
4. Wait for the status/log lines; on success, the image is saved to:
   - `Pictures/WearBridge` (phone media library)

Notes:

- Screenshot success is phone-side `screenshot_saved` (after the phone stores the image),
  not just a watch capture success log line.
- If screenshot capability shows `not ready`, check the watch accessibility service setting.

## ADB Push via Phone App (PC -> Phone -> Watch)

Use this when you want to trigger watch install from your PC, but route through the WearBridge app on the phone.
If you set a message password in the watch app, pass the same password here.
The script:

1. expands artifact into one or more APK files,
2. pushes them to WearBridge app-specific phone storage,
3. launches WearBridge with an explicit intent,
4. WearBridge forwards install payload to the watch via Data Layer.

Command:

```bash
cd /workspaces/wearbridge-demo-7f3c
./tools/watch_adb_install.sh -s <PHONE_SERIAL> --password <PASSWORD> <artifact>
```

Supported artifacts:

- `.apk`
- `.apks` / `.zip` containing split APKs
- directory containing `*.apk`

Examples:

```bash
./tools/watch_adb_install.sh --password secret123 app-release.apk
./tools/watch_adb_install.sh -s VQ92H7L1XK4M --password secret123 app-release.apk
./tools/watch_adb_install.sh --password secret123 watchface.apks
./tools/watch_adb_install.sh --password secret123 --package com.example.app split_bundle.zip
./tools/watch_adb_install.sh --password secret123 --no-auto-send app-release.apk
./tools/watch_adb_install.sh --password secret123 --poll-seconds 180 app-release.apk
./tools/watch_adb_install.sh --password secret123 --no-poll app-release.apk
```

Options:

- `-s, --serial`: phone device serial.
- `--package`: override package name used by WearBridge.
- `--password`: password forwarded to the watch (required only if a watch password is set).
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

## ADB Watch Screenshot via Phone App (PC -> Phone -> Watch)

Use this when you want to trigger a watch screenshot from your PC, but route through
the phone app and save the image on the phone.
If you set a message password in the watch app, pass the same password here.

Command:

```bash
cd /workspaces/wearbridge-demo-7f3c
./tools/watch_adb_screenshot.sh -s <PHONE_SERIAL> --password <PASSWORD>
```

Examples:

```bash
./tools/watch_adb_screenshot.sh --password secret123
./tools/watch_adb_screenshot.sh -s VQ92H7L1XK4M --password secret123
./tools/watch_adb_screenshot.sh --password secret123 --poll-seconds 60
./tools/watch_adb_screenshot.sh --password secret123 --request-id demo-shot-001
./tools/watch_adb_screenshot.sh --password secret123 --no-poll
```

Notes:

- The script launches the phone app with a custom screenshot intent, then optionally polls
  phone logcat session lines (`tag=WearBridge`).
- Custom screenshot/install intents may include `io.vibe.wearbridge.extra.PASSWORD`.
  It is required only when the watch has a message password set. Phone GUI actions can use
  the password saved in the app UI.
- Success condition is `state=session_finished reason=screenshot_saved`.
- The watch accessibility service must already be enabled or the request will fail with
  `screenshot_request_failed`.
- To best-effort configure install-source access + screenshot accessibility service on the
  watch first, run `./tools/watch_adb_setup_access.sh -s <WATCH_SERIAL>`.
