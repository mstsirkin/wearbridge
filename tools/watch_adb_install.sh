#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  $(basename "$0") [-s SERIAL] [--downgrade] <artifact>

Artifacts supported:
  - .apk
  - .apks / .zip (must contain one or more .apk files)
  - directory containing *.apk files

Examples:
  $(basename "$0") app-release.apk
  $(basename "$0") -s 192.168.1.20:5555 app-release.apk
  $(basename "$0") watchface.apks

Notes:
  - If SERIAL is omitted and exactly one ADB device is connected, it is used.
  - For split packages, this script calls: adb install-multiple -r [ -d ] <apks...>
USAGE
}

log() {
  printf '[watch-adb] %s\n' "$*"
}

die() {
  printf '[watch-adb] ERROR: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

SERIAL=""
ALLOW_DOWNGRADE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      [[ $# -ge 2 ]] || die "Missing value after $1"
      SERIAL="$2"
      shift 2
      ;;
    --downgrade|-d)
      ALLOW_DOWNGRADE=1
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
      break
      ;;
  esac
done

[[ $# -eq 1 ]] || {
  usage
  die "Exactly one artifact path is required"
}

ARTIFACT="$1"
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

check_watch_like_device() {
  local characteristics
  characteristics="$(adb_cmd shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r' || true)"
  if [[ "$characteristics" != *watch* ]]; then
    log "Selected device characteristics: ${characteristics:-unknown}"
    log "Continuing anyway (device may still be a watch)."
  else
    log "Target looks like a watch (ro.build.characteristics=$characteristics)"
  fi
}

collect_apks_from_dir() {
  local dir="$1"
  mapfile -t apks < <(find "$dir" -maxdepth 1 -type f -name '*.apk' | sort)
  [[ ${#apks[@]} -gt 0 ]] || die "No .apk files found in directory: $dir"

  # Move base.apk first when present.
  local ordered=()
  local others=()
  for apk in "${apks[@]}"; do
    local base
    base="$(basename "$apk")"
    if [[ "$base" == "base.apk" ]]; then
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
  local tmpdir
  tmpdir="$(mktemp -d)"

  unzip -qq "$archive" '*.apk' -d "$tmpdir" || die "Failed to extract APKs from archive: $archive"
  mapfile -t apks < <(find "$tmpdir" -type f -name '*.apk' | sort)
  [[ ${#apks[@]} -gt 0 ]] || die "Archive contains no .apk files: $archive"

  local ordered=()
  local others=()
  for apk in "${apks[@]}"; do
    local base
    base="$(basename "$apk")"
    if [[ "$base" == "base.apk" ]]; then
      ordered+=("$apk")
    else
      others+=("$apk")
    fi
  done
  ordered+=("${others[@]}")

  printf '%s\n' "${ordered[@]}"
}

install_single_apk() {
  local apk="$1"
  local args=(-r)
  if [[ "$ALLOW_DOWNGRADE" -eq 1 ]]; then
    args+=(-d)
  fi

  log "Installing single APK: $apk"
  adb_cmd install "${args[@]}" "$apk"
}

install_multiple_apks() {
  local -a files=("$@")
  local args=(-r)
  if [[ "$ALLOW_DOWNGRADE" -eq 1 ]]; then
    args+=(-d)
  fi

  log "Installing split package (${#files[@]} APK files)"
  adb_cmd install-multiple "${args[@]}" "${files[@]}"
}

main() {
  pick_serial_if_needed
  log "Using device: $SERIAL"
  check_watch_like_device

  if [[ -d "$ARTIFACT" ]]; then
    mapfile -t apks < <(collect_apks_from_dir "$ARTIFACT")
    if [[ ${#apks[@]} -eq 1 ]]; then
      install_single_apk "${apks[0]}"
    else
      install_multiple_apks "${apks[@]}"
    fi
    log "Done"
    exit 0
  fi

  case "${ARTIFACT,,}" in
    *.apk)
      install_single_apk "$ARTIFACT"
      ;;
    *.apks|*.zip)
      mapfile -t apks < <(collect_apks_from_archive "$ARTIFACT")
      if [[ ${#apks[@]} -eq 1 ]]; then
        install_single_apk "${apks[0]}"
      else
        install_multiple_apks "${apks[@]}"
      fi
      ;;
    *)
      die "Unsupported artifact type: $ARTIFACT"
      ;;
  esac

  log "Done"
}

main
