#!/usr/bin/env bash
set -euo pipefail

APP_ID="io.vibe.wearbridge"
APP_ACTIVITY="io.vibe.wearbridge/.MainActivity"
ACTION_SCREENSHOT="io.vibe.wearbridge.action.REQUEST_WATCH_SCREENSHOT"
EXTRA_SESSION_ID="io.vibe.wearbridge.extra.SESSION_ID"
EXTRA_REQUEST_ID="io.vibe.wearbridge.extra.REQUEST_ID"
EXTRA_SOURCE="io.vibe.wearbridge.extra.SOURCE"

SERIAL=""
POLL_ENABLED=1
POLL_SECONDS=0
SOURCE_VALUE="adb"
REQUEST_ID=""
SESSION_ID=""

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [-s PHONE_SERIAL] [--request-id ID] [--source NAME] [--poll-seconds N|--no-poll]

What it does:
  1) Launches WearBridge on the phone with a screenshot request intent
  2) WearBridge forwards /request-screenshot to the watch
  3) Polls phone logcat session lines until screenshot is saved (optional)

Options:
  -s, --serial SERIAL      ADB serial for the phone (required if multiple devices)
      --request-id ID      Request identifier forwarded to the watch (defaults to session id)
      --source NAME        Source tag sent in screenshot request payload (default: adb)
      --poll-seconds N     Poll session progress from logcat for N seconds
      --no-poll            Do not poll logcat after launch
  -h, --help               Show this help
USAGE
}

log() {
  printf '[watch-screenshot] %s\n' "$*"
}

die() {
  printf '[watch-screenshot] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      SERIAL="$2"
      shift 2
      ;;
    --request-id)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      REQUEST_ID="$2"
      shift 2
      ;;
    --source)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      SOURCE_VALUE="$2"
      shift 2
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
    *)
      die "Unknown argument: $1"
      ;;
  esac
done

require_cmd adb

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

launch_wearbridge_screenshot() {
  local request_id="$1"
  adb_cmd shell am start \
    -S \
    -n "$APP_ACTIVITY" \
    -a "$ACTION_SCREENSHOT" \
    --es "$EXTRA_SESSION_ID" "$SESSION_ID" \
    --es "$EXTRA_REQUEST_ID" "$request_id" \
    --es "$EXTRA_SOURCE" "$SOURCE_VALUE" >/dev/null
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
          printf '%s\n' "$message"
        done
        printed="$total"
      fi

      local joined
      joined="$(printf '%s\n' "${lines[@]}")"

      if [[ "$joined" == *"state=session_finished reason=screenshot_saved"* ]]; then
        return 0
      fi

      if [[ "$joined" == *"state=session_finished reason="* ]]; then
        return 2
      fi

      if [[ "$joined" == *"state=screenshot_request_failed"* ]] || [[ "$joined" == *"state=screenshot_save_failed"* ]]; then
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
  ensure_app_installed
  SESSION_ID="wbs-$(date +%s)-$$-$RANDOM"
  local request_id="${REQUEST_ID:-$SESSION_ID}"

  log "Using phone: $SERIAL"
  log "Session: $SESSION_ID"
  log "Request ID: $request_id"

  launch_wearbridge_screenshot "$request_id"
  log "WearBridge launched with screenshot request"

  if (( POLL_ENABLED == 1 )); then
    if poll_session_progress "$POLL_SECONDS"; then
      log "Screenshot saved"
    else
      rc="$?"
      if [[ "$rc" -eq 1 ]]; then
        die "Session failed: no terminal status received within ${POLL_SECONDS}s"
      fi
      die "Screenshot request failed (see log lines above)"
    fi
  fi

  log "Done"
}

main
