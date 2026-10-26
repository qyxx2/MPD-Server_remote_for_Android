# Task 3 Batch 5A — Status Ring Placement and Lifecycle Verification

Date: 2026-10-26
Branch: `feature/task-3-native-app-shell`
Starting HEAD: `fd7f641`
Scope: Batch 5A only. Batch 5B (settings controls and persistence), Batch 6, upstream changes, merges, and pushes are excluded.

## Implementation

- Switched `MainActivity` to edge-to-edge, transparent system bars, theme-aware system-bar icons, and live `WindowInsetsCompat` / `DisplayCutout` geometry.
- Added a pure `StatusRingPlacementCalculator` that accepts actual cutout rectangles; invalid/missing/off-centre/out-of-window geometry explicitly uses `MANUAL_FALLBACK` rather than claiming that screen-centre estimation detected a camera cutout. Geometry handles scale/offset bounds and safe horizontal limits.
- Added deterministic `StatusRingVisibilityController`: pending/syncing and unresolved UNKNOWN/FAILED activities remain visible; terminal feedback expires after 5 seconds; an arriving activity clears an old deadline; removing an active activity without a terminal snapshot gets the 5-second grace period.
- Rendered the ring as a non-clickable overlay inside the app window. There is no cross-application overlay or new runtime/network/mutation owner.
- Kept the activity-details entry independent of the transient ring, with a measured 48dp target. No settings UI, Preferences DataStore, or backend/API changes were introduced.

## Verification

### TDD and unit tests

- Red evidence: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5a-logic-red.log` — the geometry/controller tests failed before the corresponding implementations existed.
- Focused green evidence: `task-3-batch5a-logic-green.log` — 4 placement tests and 7 lifecycle tests passed.
- Final unit suite: **97 tests, 0 failures, 0 errors, 0 skipped**.
- `git diff --check`: passed.
- Static boundary scan under the changed UI/component paths found no runtime construction, network connection start, mutation submit/retry, or new backend owner.

### Build and lint

Command:
```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 \
  testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
```

Result: **BUILD SUCCESSFUL**. Unit tests, Lint, Debug APK assembly, and Android instrumentation-test source compilation all passed. Final log: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5a-finalverify.log`.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (12,096,245 bytes at verification).

### Real device

Device: Samsung SM-G986N, Android 13. The duplicate ADB TLS alias was disconnected so Gradle had one stable device identity.

- `connectedDebugAndroidTest`: **8 tests completed, BUILD SUCCESSFUL**. The updated root-shell test confirms the idle ring is absent and the independent activity-details entry is visible/clickable with a 48dp target. `MainActivityEdgeToEdgeUiTest` checks transparent status/navigation bar colours, receives actual window insets, and verifies the app bar and bottom navigation align to those safe insets.
- Captured idle-state screenshot at `.superpowers/sdd/2026-10-25-task-3-native-app-shell/device-evidence/batch5a-idle-edge-to-edge.png` (1080×2400) and UI hierarchy at `batch5a-ui-hierarchy.xml`. The capture confirms normal idle content and the independent “活动” entry; the ring correctly does not remain permanently visible without activity.
- Device test log: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5a-device-test.log`.

## Explicitly unverified / blocked

The test app's shared runtime currently has no real activity, and no usable MS service is configured on this test device. I therefore did **not** fabricate a mutation or claim visual verification of an active ring. The physical-cutout ring rendering while an actual activity is pending, its on-device 5-second disappearance/reset, and persistent UNKNOWN/FAILED display remain **BLOCKED for real-device visual acceptance**. Their deterministic geometry and lifecycle behavior is covered by passing unit tests; that is not represented as a substitute for the blocked visual check.

Batch 5A's code and automated validation are complete with the above limitation recorded. Batch 5B remains unchecked and unimplemented.
