# TODO - WearBridge

This tracks remaining work after the current clean-room MVP.

## 1. Protocol and Compatibility

- [ ] Verify wire compatibility against a real WearLoad watch app for all paths/keys:
  - `/request-sync`
  - `/app-list-start`, `/app-list-chunk`, `/app-list-end`
  - `/apk/*` install payload keys
  - `/request-apk` -> `/apk-export`
  - `/delete-app`
  - `/check-companion` -> `/check-companion-response`
- [ ] Add integration tests that assert payload shape and message/data path behavior.
- [ ] Handle protocol edge cases explicitly (out-of-order chunks, duplicate chunks, missing `END`, oversized chunk payloads).

## 2. Install Pipeline

- [ ] Improve package-name inference for archives (APKS/ZIP) with split APK heuristics and better fallback rules.
- [ ] Support explicit user override when inferred package name is wrong.
- [ ] Add install preflight checks (connected node, readable URIs, non-zero file size, expected MIME/extension).
- [ ] Add user-facing progress for `putDataItem` transfer and completion acknowledgement.

## 3. Export Pipeline

- [ ] Add richer export result UI (saved file URI/path and quick-open action).
- [ ] Deduplicate repeated `/apk-export` items and ensure cleanup resilience if delete fails.
- [ ] Add error recovery for MediaStore write failures (storage full, permission issues, bad stream).

## 4. App List and Local Model

- [ ] Persist synced watch app list locally (Room) so list survives process death/restart.
- [ ] Cache/evict icons with bounded storage policy.
- [ ] Add app list filtering/sorting controls in UI.
- [ ] Add stale-state handling when watch disconnects mid-sync.

## 5. UX and Product

- [ ] Add runtime guidance for phone/watch prerequisites (Bluetooth/Wi-Fi/GMS state where relevant).
- [ ] Add first-run setup/help screen.
- [ ] Add confirmation dialogs for destructive actions (delete request).
- [ ] Improve logs panel (search, copy/export, level tags).
- [ ] Add localization scaffolding (at least `en` + `ru`).

## 6. Permissions and Intents

- [ ] Narrow intent filters from `*/*` to targeted types where possible while keeping share-flow support.
- [ ] Validate URI permission handling across all picker/share entry points.
- [ ] Add explicit handling for denied/expired URI permissions.

## 7. Architecture and Code Quality

- [ ] Split protocol, transport, and UI state into clearer modules/packages.
- [ ] Add DI (Hilt/Koin or lightweight manual DI) to simplify testing.
- [ ] Replace singleton global state with repository/state holder lifecycle-aware components.
- [ ] Add strict lint/ktlint/detekt configuration and CI checks.

## 8. Testing

- [ ] Unit tests:
  - File metadata + package extraction logic
  - Protocol serialization/deserialization
  - ViewModel state transitions
- [ ] Instrumented tests:
  - Intent/share handling
  - ListenerService message/data handling
  - MediaStore export writes
- [ ] End-to-end smoke test script for phone + watch interaction.

## 9. Build and Release

- [ ] Add release build config (minify/obfuscation review, crash-safe keep rules).
- [ ] Set up signing config strategy (debug vs release keys).
- [ ] Add versioning policy and changelog process.
- [ ] Add GitHub Actions (assemble, lint, tests, artifact upload).

## 10. Security and Privacy

- [ ] Add threat model for incoming shared files and watch-delivered data.
- [ ] Validate and bound all parsed payload sizes.
- [ ] Add safer logging policy (avoid sensitive file paths/package data where unnecessary).
- [ ] Document privacy posture and data retention behavior.

## 11. Parity Backlog (Optional)

- [ ] ADB-based fallback transport for watches where Data Layer path is unreliable.
- [ ] Embedded watch companion bootstrap flow (if needed for future independent stack).
- [ ] Rich file manager features (scan, previews, statuses, indexing).
- [ ] Update-check flow and remote version metadata handling.

