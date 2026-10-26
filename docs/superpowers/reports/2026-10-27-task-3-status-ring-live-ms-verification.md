# Task 3 — Live MS Connectivity and Status-Ring Follow-up Verification

**Date:** 2026-10-27
**Branch:** `feature/task-3-native-app-shell`
**Base commit:** `882e6c8` (`docs(task3): record batch 7 integration verification`)
**Device:** Samsung SM-G986N, Android 13
**Server:** `http://192.168.3.94:18000`
**Scope:** Read-only live-service smoke test, status-ring visual instrumentation, and a connection-state race found during clean regression. No playback/queue/library mutations, no server changes, no Task 4 work, no merge or push.

## 1. Live server read-only verification

Direct access from the Ubuntu test workbench (proxy bypass) returned:

- `GET /api/health`: HTTP 200; service status `ok`, MPD `connected`, library sync `SYNCED`.
- `GET /api/state`: HTTP 200; complete snapshot returned; playback observation was `fresh` and `CONFIRMED`.
- `WS /api/realtime`: connected successfully; protocol v1 initial frame was a full `snapshot`; outer/inner epoch and sequence matched.

The Android phone was also reachable over ADB and directly reached TCP port 18000.

## 2. Android live-runtime smoke test

Added opt-in instrumentation test `LiveMsStatusRingUiTest`. It is skipped in the normal offline suite unless the `liveOrigin` instrumentation argument is supplied. The explicit invocation against the MS server passed **1/1**.

The test temporarily changes the existing Application-owned runtime to the real origin, starts the shared realtime connection, and waits for an accepted canonical snapshot with `CONNECTED` / `FRESH`. It checks actual-playback confirmation, opens the existing activity details UI, verifies “connected” and “latest snapshot”, confirms the activity list remains empty, and asserts the ring stays hidden when there is no shared operation activity. It then stops the test connection and restores the original origin.

No mutation was submitted. The test does not leave the installed app configured to use the LAN server; production origin configuration remains a later settings task.

## 3. Network permission and safe debug-only LAN HTTP

The first live Android attempt timed out because the app manifest lacked `android.permission.INTERNET`; the package permission inspection confirmed it was absent. This was the reason for the initial failure, not a failure of the MS health/state endpoints.

Added the required `INTERNET` permission to the main manifest. Added a **debug-only** Network Security Config that keeps cleartext disabled by default and allows HTTP only to the exact test host `192.168.3.94`. The cleartext exception is under `app/src/debug`, not the release source set; no app-wide cleartext exemption or arbitrary host allowlist was added.

After this change, the Android live smoke test passed. Dynamic production-origin security handling still needs to be designed with the future server-address settings flow.

## 4. Status-ring real-device instrumentation

Added `StatusRingVisualUiTest`, using the real device window Insets/DisplayCutout report and production ring placement/drawing code. It passed **1/1** on the SM-G986N. It checks the rendered/clipped bounds against `StatusRingPlacementCalculator` and verifies accessible ring semantics/graphics for UNKNOWN, FAILED, PENDING, SYNCING, DISCONNECTED, CONNECTING, STALE, SUCCESS, and INFO presentations. These local presentation samples do not enter the shared runtime and do not create server activities.

**Device geometry limitation found:** the phone reports a physical resolution of 1440×3200 but currently has an override of 1080×2400. Its reported cutout bounding rectangle is off-centre in the overridden width, so the production calculator correctly rejects it as unreliable and selects `MANUAL_FALLBACK`. The test verifies the actual rendered placement under this fallback. The default-resolution DisplayCutout auto-anchor and complete manual calibration on a device without reliable cutout geometry are still not fully accepted; the phone override was not changed.

## 5. Runtime race caught by clean regression

The first clean regression run exposed an intermittent existing test failure: `RuntimeCompositionTest.initialSnapshotIsAcceptedIntoTheSingleCanonicalStore` sometimes saw a just-accepted complete realtime snapshot downgraded from `FRESH` to `STALE`. A targeted rerun passed, indicating a timing race.

TDD evidence:
- RED: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/snapshot-connectivity-race-red.log` — the new deterministic regression test failed because a late same-generation CONNECTING status downgraded the accepted snapshot.
- GREEN: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/snapshot-connectivity-race-green.log` — the new guard and targeted regressions passed, including initial snapshot acceptance and same-origin reconnect.

`SnapshotStore` now ignores a late same-generation CONNECTING phase only when that same generation already has an accepted snapshot and the store is CONNECTED. An actual reconnect's DISCONNECTED transition still invalidates freshness, and the existing reconnect regression passed.

## 6. Fresh full verification

Command:

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest
```

**Result:** BUILD SUCCESSFUL, exit code 0.

- Unit tests: **106/106 passed**, 0 failures/errors/skips.
- Connected instrumentation on SM-G986N / Android 13: **11 passed**, 0 failures; the opt-in live test is intentionally skipped in this default no-argument run, and was then run separately and passed 1/1 against the real server.
- `lintDebug`, `assembleDebug`, `compileDebugAndroidTestKotlin`: passed.
- Debug APK: 12,040,852 bytes; SHA-256 `6348068ff1e8a97dbfc4962727322a69bcaba534af27fc97d6cb0fe85d26ee8e`.
- Full clean log: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/status-ring-finalverify.log`.
- Device visual-test log: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/status-ring-visual-on-device.log`.
- Live smoke pass: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/live-ms-smoke-green.log`.

## 7. Remaining acceptance boundary

The real server GET/WebSocket connection, accepted fresh snapshot projection, idle-hidden behavior, on-device ring geometry calculation/fallback, and rendered status semantics now have concrete evidence. Deterministic unit tests still cover the 5-second hide/reset and unresolved UNKNOWN/FAILED lifecycle.

This does **not** claim a real production mutation/activity lifecycle was generated on the server; no such write was performed. Because the test phone's size override causes unreliable DisplayCutout coordinates, the auto-anchor visual and complete physical calibration gates remain open. The broader Task 3 manual theme/layout acceptance remains open. Task 2's real mutation/readback and production APK live-origin exit gate remains separately **BLOCKED**.
