#!/usr/bin/env bash
set -euo pipefail

APP_ID="io.vibe.wearbridge"
A11Y_COMPONENT="${APP_ID}/io.vibe.wearbridge.watch.screenshot.WatchScreenshotAccessibilityService"

SERIAL=""
SKIP_INSTALL_APP_OPS=0
SKIP_ACCESSIBILITY=0

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [-s WATCH_SERIAL] [--skip-install-appops] [--skip-accessibility]

Best-effort setup for WearBridge on the watch via adb:
  - enables "Install unknown apps" app-op for WearBridge (if supported)
  - enables the WearBridge screenshot accessibility service

Notes:
  - This targets the WATCH device (not the phone).
  - Some OEM / Wear OS builds may block one or more commands.
  - Manifest-only permissions (QUERY_ALL_PACKAGES, REQUEST_DELETE_PACKAGES) are not adb-grantable.

Options:
  -s, --serial SERIAL        ADB serial for the watch (required if multiple devices)
      --skip-install-appops  Skip REQUEST_INSTALL_PACKAGES app-op setup
      --skip-accessibility   Skip accessibility service enablement
  -h, --help                 Show this help
USAGE
}

log() {
  printf '[watch-setup] %s\n' "$*"
}

warn() {
  printf '[watch-setup] WARN: %s\n' "$*" >&2
}

die() {
  printf '[watch-setup] ERROR: %s\n' "$*" >&2
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
    --skip-install-appops)
      SKIP_INSTALL_APP_OPS=1
      shift
      ;;
    --skip-accessibility)
      SKIP_ACCESSIBILITY=1
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
    die "Multiple ADB devices connected. Use -s SERIAL (watch device)"
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
    die "WearBridge app ($APP_ID) is not installed on the target device"
  fi
}

show_device_info() {
  local model characteristics
  model="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  characteristics="$(adb_cmd shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  log "Target device: ${SERIAL} model=${model:-unknown} characteristics=${characteristics:-unknown}"
  if [[ -n "$characteristics" && "$characteristics" != *watch* ]]; then
    warn "Target device does not report 'watch' characteristics"
  fi
}

try_set_install_unknown_apps_appop() {
  local ok=0

  if adb_cmd shell cmd appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1; then
    ok=1
  elif adb_cmd shell appops set "$APP_ID" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1; then
    ok=1
  fi

  if [[ "$ok" -eq 1 ]]; then
    log "Enabled install-source app-op (REQUEST_INSTALL_PACKAGES) for $APP_ID"
  else
    warn "Could not set REQUEST_INSTALL_PACKAGES app-op via adb (device may require manual UI setup)"
    return 1
  fi

  # Best-effort verification only.
  adb_cmd shell cmd appops get "$APP_ID" REQUEST_INSTALL_PACKAGES 2>/dev/null | tr -d '\r' || true
}

get_secure_setting() {
  local key="$1"
  adb_cmd shell settings get secure "$key" 2>/dev/null | tr -d '\r'
}

put_secure_setting() {
  local key="$1"
  local value="$2"
  adb_cmd shell settings put secure "$key" "$value"
}

enable_accessibility_service() {
  local current new_value
  current="$(get_secure_setting enabled_accessibility_services || true)"
  current="${current//$'\n'/}"
  if [[ "$current" == "null" ]]; then
    current=""
  fi

  if [[ -z "$current" ]]; then
    new_value="$A11Y_COMPONENT"
  elif [[ ":$current:" == *":$A11Y_COMPONENT:"* ]]; then
    new_value="$current"
  else
    new_value="${current}:$A11Y_COMPONENT"
  fi

  if put_secure_setting enabled_accessibility_services "$new_value" >/dev/null 2>&1 && \
     put_secure_setting accessibility_enabled 1 >/dev/null 2>&1; then
    log "Enabled accessibility service: $A11Y_COMPONENT"
  else
    warn "Could not enable accessibility service via adb secure settings"
    warn "Enable it manually on watch: Accessibility -> WearBridge Screenshot Capture"
    return 1
  fi

  local verify
  verify="$(get_secure_setting enabled_accessibility_services || true)"
  if [[ ":${verify}:" != *":$A11Y_COMPONENT:"* ]]; then
    warn "Accessibility service not present in enabled_accessibility_services after write"
    return 1
  fi
  log "Accessibility secure settings updated successfully"
}

print_notes() {
  log "Manifest-only permissions require no adb grant:"
  log "  - QUERY_ALL_PACKAGES"
  log "  - REQUEST_DELETE_PACKAGES"
  log "  - BIND_ACCESSIBILITY_SERVICE (service binding permission)"
  log "If install still fails, confirm watch UI allows 'Install unknown apps' for WearBridge."
}

main() {
  pick_serial_if_needed
  show_device_info
  ensure_app_installed

  if [[ "$SKIP_INSTALL_APP_OPS" -eq 0 ]]; then
    try_set_install_unknown_apps_appop || true
  else
    log "Skipping install app-op setup"
  fi

  if [[ "$SKIP_ACCESSIBILITY" -eq 0 ]]; then
    enable_accessibility_service || true
  else
    log "Skipping accessibility setup"
  fi

  print_notes
  log "Done"
}

main
