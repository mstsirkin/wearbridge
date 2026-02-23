#!/usr/bin/env bash
set -euo pipefail

APP_ID="io.vibe.wearbridge"
APP_ACTIVITY="io.vibe.wearbridge/.MainActivity"
ACTION_INSTALL="io.vibe.wearbridge.action.INSTALL_TO_WATCH"
EXTRA_AUTO_SEND="io.vibe.wearbridge.extra.AUTO_SEND"
EXTRA_PACKAGE_NAME="io.vibe.wearbridge.extra.PACKAGE_NAME"
EXTRA_FILE_COUNT="io.vibe.wearbridge.extra.FILE_COUNT"
EXTRA_FILE_URI_PREFIX="io.vibe.wearbridge.extra.FILE_URI_"
EXTRA_SESSION_ID="io.vibe.wearbridge.extra.SESSION_ID"
APP_STAGING_ABS="/data/user/0/${APP_ID}/files/WearBridgeStaging"
APP_STAGING_REL="files/WearBridgeStaging"
APP_STAGING_MANIFEST_REL="${APP_STAGING_REL}/staged-files.txt"

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [-s PHONE_SERIAL] [--package PACKAGE_NAME] [--no-auto-send] [--poll-seconds N|--no-poll] <artifact>

What it does:
  1) Expands artifact into one or more APK files (if needed)
  2) Pushes APK file(s) to phone storage
  3) Launches WearBridge on phone with an explicit intent
  4) WearBridge forwards payload to watch via Data Layer protocol

Artifacts supported:
  - .apk
  - .apks / .zip (must contain one or more .apk files)
  - directory containing *.apk files

Options:
  -s, --serial SERIAL      ADB serial for the phone (required if multiple devices)
      --package NAME       Override package name sent to WearBridge
      --no-auto-send       Open WearBridge with files selected, but do not auto-send to watch
      --poll-seconds N     Poll session progress from logcat for N seconds
      --no-poll            Do not poll logcat after launch
  -h, --help               Show this help

Examples:
  $(basename "$0") app-release.apk
  $(basename "$0") -s VQ92H7L1XK4M app-release.apk
  $(basename "$0") watchface.apks
  $(basename "$0") --package com.example.app split_bundle.zip
USAGE
}

log() {
  printf '[watch-bridge] %s\n' "$*"
}

die() {
  printf '[watch-bridge] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

SERIAL=""
PACKAGE_OVERRIDE=""
AUTO_SEND=1
POLL_SECONDS=0
POLL_ENABLED=1
ARTIFACT=""
TMP_DIR=""
SESSION_ID=""
declare -a TMP_FILES=()

cleanup() {
  for tmp_file in "${TMP_FILES[@]}"; do
    if [[ -f "$tmp_file" ]]; then
      rm -f "$tmp_file"
    fi
  done
  if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
    rmdir "$TMP_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      SERIAL="$2"
      shift 2
      ;;
    --package)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      PACKAGE_OVERRIDE="$2"
      shift 2
      ;;
    --no-auto-send)
      AUTO_SEND=0
      shift
      ;;
    --poll-seconds)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      [[ "$2" =~ ^[0-9]+$ ]] || die "--poll-seconds must be an integer"
      POLL_SECONDS="$2"
      POLL_ENABLED=1
      shift 2
      ;;
    --no-poll)
      POLL_ENABLED=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      die "Unknown option: $1"
      ;;
    *)
      ARTIFACT="$1"
      shift
      break
      ;;
  esac
done

if [[ -z "$ARTIFACT" && $# -ge 1 ]]; then
  ARTIFACT="$1"
  shift
fi

[[ -n "$ARTIFACT" ]] || {
  usage
  die "Artifact path is required"
}

[[ -e "$ARTIFACT" ]] || die "Artifact not found: $ARTIFACT"

require_cmd adb
require_cmd unzip

pick_serial_if_needed() {
  if [[ -n "$SERIAL" ]]; then
    return
  fi

  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#devices[@]} -eq 0 ]]; then
    die "No ADB devices in 'device' state"
  elif [[ ${#devices[@]} -gt 1 ]]; then
    die "Multiple ADB devices connected. Use -s SERIAL"
  fi

  SERIAL="${devices[0]}"
}

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

ensure_app_installed() {
  if ! adb_cmd shell pm list packages | grep -q "^package:${APP_ID}$"; then
    die "WearBridge app ($APP_ID) is not installed on the phone"
  fi
}

ensure_run_as() {
  if ! adb_cmd shell run-as "$APP_ID" true >/dev/null 2>&1; then
    die "run-as failed for $APP_ID (install a debuggable build)"
  fi
}

prepare_staging_dir() {
  adb_cmd shell "run-as $APP_ID sh -c 'mkdir -p \"$APP_STAGING_REL/payload\"; touch \"$APP_STAGING_MANIFEST_REL\"'" >/dev/null

  local previous_manifest_raw
  previous_manifest_raw="$(adb_cmd shell "run-as $APP_ID sh -c 'cat \"$APP_STAGING_MANIFEST_REL\"'" 2>/dev/null | tr -d '\r')"
  if [[ -n "$previous_manifest_raw" ]]; then
    local -a previous_names=()
    mapfile -t previous_names <<<"$previous_manifest_raw"
    for name in "${previous_names[@]}"; do
      [[ -n "$name" ]] || continue
      adb_cmd shell "run-as $APP_ID sh -c 'rm -f \"$APP_STAGING_REL/payload/$name\"'" >/dev/null
    done
  fi

  adb_cmd shell "run-as $APP_ID sh -c ': > \"$APP_STAGING_MANIFEST_REL\"'" >/dev/null
}

write_staging_manifest() {
  local -a names=("$@")
  if [[ ${#names[@]} -eq 0 ]]; then
    adb_cmd shell "run-as $APP_ID sh -c ': > \"$APP_STAGING_MANIFEST_REL\"'" >/dev/null
    return
  fi

  local payload=""
  for name in "${names[@]}"; do
    payload+="${name}"$'\n'
  done
  adb_cmd shell "run-as $APP_ID sh -c 'cat > \"$APP_STAGING_MANIFEST_REL\"'" <<<"$payload"
}

collect_apks_from_dir() {
  local dir="$1"
  mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' | sort)
  [[ ${#apks[@]} -gt 0 ]] || die "No .apk files found in directory: $dir"

  local ordered=()
  local others=()
  for apk in "${apks[@]}"; do
    if [[ "$(basename "$apk")" == "base.apk" ]]; then
      ordered+=("$apk")
    else
      others+=("$apk")
    fi
  done
  ordered+=("${others[@]}")

  printf '%s\n' "${ordered[@]}"
}

collect_apks_from_archive() {
  local archive="$1"
  TMP_DIR="$(mktemp -d)"
  local -a entries=()
  mapfile -t entries < <(unzip -Z1 "$archive" | awk 'tolower($0) ~ /\.apk$/')
  [[ ${#entries[@]} -gt 0 ]] || die "Archive contains no .apk files: $archive"

  local -a ordered_entries=()
  local -a other_entries=()
  for entry in "${entries[@]}"; do
    if [[ "${entry##*/,,}" == "base.apk" ]]; then
      ordered_entries+=("$entry")
    else
      other_entries+=("$entry")
    fi
  done
  ordered_entries+=("${other_entries[@]}")

  local -a extracted=()
  local index=0
  for entry in "${ordered_entries[@]}"; do
    index=$((index + 1))
    local out_path="${TMP_DIR}/archive-${index}.apk"
    unzip -p "$archive" "$entry" > "$out_path" || die "Failed to extract entry from archive: $entry"
    TMP_FILES+=("$out_path")
    extracted+=("$out_path")
  done

  printf '%s\n' "${extracted[@]}"
}

collect_input_apks() {
  case "${ARTIFACT,,}" in
    *.apk)
      printf '%s\n' "$ARTIFACT"
      ;;
    *.apks|*.zip)
      collect_apks_from_archive "$ARTIFACT"
      ;;
    *)
      if [[ -d "$ARTIFACT" ]]; then
        collect_apks_from_dir "$ARTIFACT"
      else
        die "Unsupported artifact type: $ARTIFACT"
      fi
      ;;
  esac
}

push_apks_to_phone() {
  local -a local_apks=("$@")
  local remote_dir_rel="${APP_STAGING_REL}/payload"
  local remote_dir_abs="${APP_STAGING_ABS}/payload"

  local total_files="${#local_apks[@]}"
  local total_bytes=0
  local done_bytes=0
  local index=0
  for local_apk in "${local_apks[@]}"; do
    local file_size
    file_size="$(wc -c < "$local_apk" | tr -d '[:space:]')"
    total_bytes=$((total_bytes + file_size))
  done

  local -a uris=()
  for local_apk in "${local_apks[@]}"; do
    index=$((index + 1))
    local local_name
    local_name="$(basename "$local_apk")"
    local remote_name
    remote_name="$(printf 'slot-%04d.apk' "$index")"
    local file_size
    file_size="$(wc -c < "$local_apk" | tr -d '[:space:]')"
    local remote_path_rel="${remote_dir_rel}/${remote_name}"
    local remote_path_abs="${remote_dir_abs}/${remote_name}"
    adb_cmd shell "run-as $APP_ID sh -c 'cat > \"$remote_path_rel\"'" < "$local_apk"
    done_bytes=$((done_bytes + file_size))
    local percent=0
    if (( total_bytes > 0 )); then
      percent=$((done_bytes * 100 / total_bytes))
    fi
    uris+=("file://${remote_path_abs}")
    log "Pushed [${index}/${total_files}] ${local_name} -> ${remote_name} (${percent}%)" >&2
  done

  printf '%s\n' "${uris[@]}"
}

launch_wearbridge() {
  local -a uri_list=("$@")

  local -a cmd=(
    shell am start
    -S
    -n "$APP_ACTIVITY"
    -a "$ACTION_INSTALL"
    --ez "$EXTRA_AUTO_SEND" "$([[ "$AUTO_SEND" -eq 1 ]] && echo true || echo false)"
    --ei "$EXTRA_FILE_COUNT" "${#uri_list[@]}"
    --es "$EXTRA_SESSION_ID" "$SESSION_ID"
  )

  if [[ -n "$PACKAGE_OVERRIDE" ]]; then
    cmd+=(--es "$EXTRA_PACKAGE_NAME" "$PACKAGE_OVERRIDE")
  fi

  for i in "${!uri_list[@]}"; do
    cmd+=(--es "${EXTRA_FILE_URI_PREFIX}${i}" "${uri_list[$i]}")
  done

  adb_cmd "${cmd[@]}" >/dev/null
}

poll_session_progress() {
  local timeout="$1"
  local start_ts now_ts elapsed
  local printed=0

  start_ts="$(date +%s)"
  if (( timeout > 0 )); then
    log "Polling logcat for session=$SESSION_ID (timeout=${timeout}s)"
  else
    log "Polling logcat for session=$SESSION_ID (no timeout; Ctrl-C to stop)"
  fi

  while true; do
    local raw
    raw="$(adb_cmd logcat -d -v brief -s "WearBridge:I" "*:S" 2>/dev/null | tr -d '\r')"

    if [[ -n "$raw" ]]; then
      local -a lines
      mapfile -t lines < <(printf '%s\n' "$raw" | grep "session=${SESSION_ID} " || true)
      local total="${#lines[@]}"
      if (( total > printed )); then
        for ((i=printed; i<total; i++)); do
          local line message
          line="${lines[$i]}"
          message="${line#*: }"
          if [[ "$line" == *"state=upload_progress"* ]]; then
            local progress stage
            progress="?"
            stage="unknown"
            if [[ "$line" =~ percent=([0-9]+) ]]; then
              progress="${BASH_REMATCH[1]}"
            fi
            if [[ "$line" =~ stage=([A-Za-z0-9._-]+) ]]; then
              stage="${BASH_REMATCH[1]}"
            fi
            log "Upload progress: ${progress}% (${stage})"
          else
            printf '%s\n' "$message"
          fi
        done
        printed="$total"
      fi

      local joined
      joined="$(printf '%s\n' "${lines[@]}")"

      if [[ "$joined" == *"state=session_finished reason=watch_terminal"* ]]; then
        return 0
      fi

      if [[ "$joined" == *"state=session_finished reason="* ]]; then
        return 2
      fi

      if [[ "$joined" == *"state=auto_send_failed"* ]] || [[ "$joined" == *"state=processing_failed"* ]] || [[ "$joined" == *"reason=auto_send_failed"* ]] || [[ "$joined" == *"reason=processing_failed"* ]] || [[ "$joined" == *"state=watch_terminal_timeout"* ]] || [[ "$joined" == *"reason=watch_terminal_timeout"* ]]; then
        return 2
      fi
    fi

    if (( timeout > 0 )); then
      now_ts="$(date +%s)"
      elapsed=$((now_ts - start_ts))
      if (( elapsed >= timeout )); then
        return 1
      fi
    fi
    sleep 2
  done
}

main() {
  pick_serial_if_needed
  log "Using phone: $SERIAL"
  ensure_app_installed
  ensure_run_as
  SESSION_ID="wb-$(date +%s)-$$-$RANDOM"
  log "Session: $SESSION_ID"

  mapfile -t local_apks < <(collect_input_apks)
  [[ ${#local_apks[@]} -gt 0 ]] || die "No APK files to send"

  local session_dir="$APP_STAGING_ABS"
  log "Preparing ${#local_apks[@]} APK file(s)"
  prepare_staging_dir
  mapfile -t phone_uris < <(push_apks_to_phone "${local_apks[@]}")
  local -a manifest_names=()
  local idx
  for ((idx = 1; idx <= ${#local_apks[@]}; idx++)); do
    manifest_names+=("$(printf 'slot-%04d.apk' "$idx")")
  done
  write_staging_manifest "${manifest_names[@]}"

  launch_wearbridge "${phone_uris[@]}"

  if [[ "$AUTO_SEND" -eq 1 ]]; then
    log "WearBridge launched with auto-send enabled"
  else
    log "WearBridge launched with files selected (manual send mode)"
  fi

  if (( POLL_ENABLED == 1 )) && [[ "$AUTO_SEND" -eq 1 ]]; then
    if poll_session_progress "$POLL_SECONDS"; then
      log "Session reached terminal state"
    else
      rc="$?"
      if [[ "$rc" -eq 1 ]]; then
        die "Session failed: no terminal status received within ${POLL_SECONDS}s"
      fi
      die "Session reported failure (see log lines above)"
    fi
  fi

  log "Session dir on phone: $session_dir"
  log "Cleanup: previous staged files are deleted explicitly by name on each new run"
  log "Done"
}

main
