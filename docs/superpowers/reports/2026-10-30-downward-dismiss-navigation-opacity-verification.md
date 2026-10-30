# Downward-dismiss navigation reveal and opacity correction — verification

**Date:** 2026-10-30
**Branch:** `task4`
**Device:** Samsung SM-G986N, Android 13
**Workspace:** `/home/Gold/WorkSpace/MS_remote_for_Android`

## Scope correction

The earlier velocity/acceleration expansion was rejected because its gesture recognition and feel did not match the requested behavior. Its production code, callback wiring, policy, and unit/UI tests have been removed. The mini-player retains its original 50%-of-window distance threshold; fast or accelerating upward gestures do not bypass it.

The downward full-screen dismissal and reverse navigation-bar reveal are retained because that motion was accepted. This revision addresses only the remaining visual dimming of the moving navigation bar.

## Root cause and RED/GREEN evidence

The exit overlay passed `interactionEnabled = false` to `RootNavigationBar`, which passed it to Material 3 `NavigationBarItem(enabled = false)`. Material 3's default disabled icon and label colors apply alpha `0.38f`. This was the remaining fade; moving the overlay outside the blurred root hierarchy had not removed that disabled-state dimming.

`downwardDismissNavigationBarUsesNormalEnabledColors` captures the root navigation item before full-screen entry and the moving overlay item during a held dismissal, then compares the pixel raster:

| State | Result |
|---|---:|
| Material 3 default disabled colors (before correction) | RED — 1,862 differing pixels |
| Disabled colors explicitly match selected/unselected normal colors | GREEN — 0 differing pixels |

The test runs at a 99%-of-window held drag so the transition copy is visible and its surface is not clipped by the screen edge. The separate navigation-motion test continues to assert movement from below the resting position, upward progression during the downward player drag, cancellation rebound, and final root-resting bounds.

## Implementation

- Removed the rejected fast/accelerating expansion logic from `MiniPlayer`, `MiniPlayerExpansionPolicy`, and `AppNavHost`.
- Removed the velocity-specific unit and instrumentation tests.
- Kept the shared-offset downward-dismiss transition and the overlay above the player.
- Kept transition navigation items disabled, but explicitly set their disabled icon/text colors to the same selected/unselected colors used in the normal state. The interaction lock remains intact without Material 3's default dimming.
- No playback, queue, network, persistence, or backend code was changed.

## Current verification

The color-raster test passed after the correction (`BUILD SUCCESSFUL`; 1/1). A control run with the disabled-color overrides temporarily removed failed as expected with 1,862 differing pixels. With the overrides restored, the same 99%-drag comparison passed with 0 differing pixels.

## Fresh verification results

| Verification | Result |
|---|---:|
| `testDebugUnitTest` | 267/267 passed; 0 failures, 0 errors, 0 skipped |
| `lintDebug` | Completed; 0 errors, 12 warnings, 3 hints |
| `compileDebugAndroidTestKotlin` | Passed |
| `assembleDebug` | Passed |
| `downwardDismissNavigationBarUsesNormalEnabledColors` | 1/1 passed; moving icon/text raster exactly matches enabled root copy |
| `downwardDismissRevealsNavigationBarFromBottomInSyncWithPlayer` | 1/1 passed |
| `miniPlayerPullUpTracksFingerAndReturnsWhenReleasedBeforeThreshold` | 1/1 passed |
| `miniPlayerPullUpCrossingThresholdContinuesIntoFullScreenPlayer` | 1/1 passed |
| `AppNavigationUiTest` | 11/11 passed |
| `MainActivityEdgeToEdgeUiTest` | 3/3 passed |
| `Task4CrossScreenIntegrationUiTest` | 1/1 passed |

The complete `FullScreenPlayerNavigationUiTest` class was also attempted. It did not complete: `horizontalSwipesSwitchBetweenPlayerAndQueueRoutes` triggered the known `NavBackStackEntry` lifecycle exception for the Queue entry while the test Activity was being destroyed (`State must be at least 'CREATED' to be moved to 'DESTROYED'`). The class-level run therefore remains failed/incomplete; the relevant dismissal, navigation-bar, mini-player rebound, and 50% threshold cases were run individually and passed. No queue/navigation lifecycle production code was modified as part of this request.

The full local verification command was:

```bash
./gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug
```

Result: **BUILD SUCCESSFUL**. Lint's 12 warnings and 3 hints remain; the only findings in modified `AppNavHost.kt` are the two existing `ModifierParameter` warnings and one `AutoboxingStateCreation` hint. There are no lint findings in `RootNavigationBar.kt` or the modified instrumentation test.

## Replacement install and data preservation

The resulting Debug APK was installed on Samsung SM-G986N / Android 13 using `adb install -r`, without uninstalling the package or clearing app data.

- Package: `io.github.qyxx2.mpdserver.remote`, version `0.1.0`, versionCode `1`.
- APK SHA-256: `5d004efa8bf3dcf68b5d762eb70502d4c5c97ecbca08759a536057ed39cffbf5`.
- Install result: `Success`.
- `firstInstallTime` remained `2026-10-24 18:47:26`; `lastUpdateTime` advanced from `2026-10-30 22:03:56` to `2026-10-30 22:05:18`.
- `files/datastore/ui_preferences.preferences_pb` SHA-256 before and after: `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`; byte-for-byte `cmp` passed.
- `MainActivity` was relaunched and confirmed as `topResumedActivity`.

`git diff --check` passed. The changes were committed locally on `task4` as `8aebb81` (`fix(android): preserve navigation bar opacity on dismiss`). The commit has not been pushed.
