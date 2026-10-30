# Mini-player pull-up transition verification

**Date:** 2026-10-30
**Branch:** task4
**Device:** SM-G986N, Android 13
**Workspace:** `/home/Gold/WorkSpace/MS_remote_for_Android`

## Requested adjustments

1. Full-screen Player entry now commits only when the upward drag reaches at least **50% of the measured app-window height**. The threshold is based on the measured app window, not a fixed dp distance. A drag below 50% remains in the preview and returns to the mini-player when released.
2. The bottom navigation no longer fades. Its alpha remains at the default full opacity while its measured height plus bottom safe inset is translated downward with the same reveal progress.

The direct-tap entry path and the existing full-screen-to-root downward dismissal policy remain unchanged. The old 120dp downward-dismiss distance is retained only for that existing exit gesture.

## TDD evidence

- RED: `.superpowers/sdd/2026-10-30-mini-player-pull-up-transition/threshold-50-red.log` — the new unit expectation for a 50% threshold failed against the previous 18% behavior.
- GREEN: `.superpowers/sdd/2026-10-30-mini-player-pull-up-transition/threshold-50-green-final.log` — focused expansion policy tests passed after implementation.

## Device instrumentation

All final device runs below completed on SM-G986N / Android 13:

| Test group | Result |
|---|---:|
| 30% upward drag, release before threshold, rebound to mini-player | 1/1 passed |
| Cross 50% threshold and complete full-screen entry | 1/1 passed |
| FullScreenPlayerNavigationUiTest, including existing dismissal/navigation cases | 9/9 passed |
| MainActivityEdgeToEdgeUiTest | 3/3 passed |
| AppNavigationUiTest + Task4CrossScreenIntegrationUiTest | 12/12 passed |

Evidence logs are under `.superpowers/sdd/2026-10-30-mini-player-pull-up-transition/`:
`pre-threshold-30-device.log`, `threshold-cross-50-device.log`, `navigation-full-device-final.log`, `edge-device-final.log`, and `shell-regression-final.log`.

One earlier full-class instrumentation attempt stopped with a Navigation back-stack lifecycle exception while destroying the Queue destination in `horizontalSwipesSwitchBetweenPlayerAndQueueRoutes`. The two changed gesture cases were then run independently and passed, and the complete navigation class was rerun successfully at 9/9. No production changes were made for that one-off test-run crash.

## Full verification

Command:

```bash
./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug
```

Result: **BUILD SUCCESSFUL**. The unit-test XML reports 267 tests, 0 failures, 0 errors, and 0 skipped. Lint, Android-test Kotlin compilation, and Debug APK assembly completed successfully. `git diff --check` also passed.

## Replacement install and data preservation

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256: `1b58a3981dcb7f5db27bd4212beec68cb44a9f8c09c74b1a96bb41c1b2200c94`
- `adb install -r`: Success.
- Package remains `io.github.qyxx2.mpdserver.remote`, versionName `0.1.0`, versionCode `1`.
- `firstInstallTime` remained `2026-10-24 18:47:26`, confirming this was a replacement install rather than a data-clearing reinstall.
- DataStore SHA-256 before and after install matched:
  `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`.
- Persisted server origin remained:
  `http://[2409:8a28:dec:7c40:2e0:4cff:feff:3468]:17999`.
- The installed app was launched after replacement install; its process was present.

## Acceptance boundary

The automated device tests verify the 30% pre-threshold behavior, 50% threshold handoff, navigation movement, existing route/dismissal flows, and system-bar insets. This run did not include a separate human visual review of every animation frame; no manual visual acceptance is claimed.
