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
PHONE_SESSIONS_BASE="/sdcard/Android/data/${APP_ID}/files/WearBridgeSessions"

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
      --poll-seconds N     Poll session progress log for N seconds (default: 120)
      --no-poll            Do not poll app log after launch
  -h, --help               Show this help

Examples:
  $(basename "$0") app-release.apk
  $(basename "$0") -s NRT8R8KRCUJV6XSO app-release.apk
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
POLL_SECONDS=120
ARTIFACT=""
TMP_DIR=""
SESSION_ID=""

cleanup() {
  if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
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
      shift 2
      ;;
    --no-poll)
      POLL_SECONDS=0
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

  unzip -qq "$archive" '*.apk' -d "$TMP_DIR" || die "Failed to extract APKs from archive: $archive"
  mapfile -t apks < <(find "$TMP_DIR" -type f -name '*.apk' | sort)
  [[ ${#apks[@]} -gt 0 ]] || die "Archive contains no .apk files: $archive"

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
  local remote_dir="${PHONE_SESSIONS_BASE}/${SESSION_ID}/payload"

  adb_cmd shell mkdir -p "$remote_dir" >/dev/null

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
    local base
    base="$(basename "$local_apk")"
    local file_size
    file_size="$(wc -c < "$local_apk" | tr -d '[:space:]')"
    local remote_path="${remote_dir}/${base}"
    adb_cmd push "$local_apk" "$remote_path" >/dev/null
    done_bytes=$((done_bytes + file_size))
    local percent=0
    if (( total_bytes > 0 )); then
      percent=$((done_bytes * 100 / total_bytes))
    fi
    uris+=("file://${remote_path}")
    log "Pushed [${index}/${total_files}] ${base} (${percent}%)" >&2
  done

  printf '%s\n' "${uris[@]}"
}

launch_wearbridge() {
  local -a uri_list=("$@")

  local -a cmd=(
    shell am start
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
  local session_log_path="${PHONE_SESSIONS_BASE}/${SESSION_ID}/progress.log"
  local start_ts now_ts elapsed
  local printed=0

  start_ts="$(date +%s)"
  log "Polling progress log for session=$SESSION_ID (timeout=${timeout}s)"
  log "Log path: $session_log_path"

  while true; do
    local raw
    raw="$(adb_cmd shell "if [ -f '$session_log_path' ]; then cat '$session_log_path'; fi" 2>/dev/null | tr -d '\r')"

    if [[ -n "$raw" ]]; then
      mapfile -t lines <<<"$raw"
      local total="${#lines[@]}"
      if (( total > printed )); then
        for ((i=printed; i<total; i++)); do
          line="${lines[$i]}"
          if [[ "$line" == *"state=upload_progress"* ]]; then
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
            printf '%s\n' "$line"
          fi
        done
        printed="$total"
      fi

      if [[ "$raw" == *"state=session_finished reason=watch_terminal"* ]]; then
        return 0
      fi

      if [[ "$raw" == *"state=session_finished reason="* ]]; then
        return 2
      fi

      if [[ "$raw" == *"state=auto_send_failed"* ]] || [[ "$raw" == *"state=processing_failed"* ]] || [[ "$raw" == *"reason=auto_send_failed"* ]] || [[ "$raw" == *"reason=processing_failed"* ]] || [[ "$raw" == *"state=watch_terminal_timeout"* ]] || [[ "$raw" == *"reason=watch_terminal_timeout"* ]]; then
        return 2
      fi
    fi

    now_ts="$(date +%s)"
    elapsed=$((now_ts - start_ts))
    if (( elapsed >= timeout )); then
      return 1
    fi
    sleep 2
  done
}

main() {
  pick_serial_if_needed
  log "Using phone: $SERIAL"
  ensure_app_installed
  SESSION_ID="wb-$(date +%s)-$$-$RANDOM"
  log "Session: $SESSION_ID"

  mapfile -t local_apks < <(collect_input_apks)
  [[ ${#local_apks[@]} -gt 0 ]] || die "No APK files to send"

  local session_dir="${PHONE_SESSIONS_BASE}/${SESSION_ID}"
  log "Preparing ${#local_apks[@]} APK file(s)"
  mapfile -t phone_uris < <(push_apks_to_phone "${local_apks[@]}")

  launch_wearbridge "${phone_uris[@]}"

  if [[ "$AUTO_SEND" -eq 1 ]]; then
    log "WearBridge launched with auto-send enabled"
  else
    log "WearBridge launched with files selected (manual send mode)"
  fi

  if (( POLL_SECONDS > 0 )) && [[ "$AUTO_SEND" -eq 1 ]]; then
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
  if [[ -n "$SERIAL" ]]; then
    log "Cleanup command: adb -s $SERIAL shell rm -rf '$session_dir'"
  else
    log "Cleanup command: adb shell rm -rf '$session_dir'"
  fi
  log "Done"
}

main
