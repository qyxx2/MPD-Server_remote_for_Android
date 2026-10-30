# Mini-player velocity-triggered expansion — verification

**Date:** 2026-10-30 / 2026-10-31 local device time  
**Branch:** `task4`  
**Base:** `effbdf763683a0b1ee78243c418d1df8c622c6df`

## Implemented behavior

Mini-player pull-up now has two independent entry conditions:

1. The existing slow-drag path: upward distance reaches 50% of the measured app-window height.
2. The new fast-flick path: upward velocity reaches **1000 dp/s**. This check runs on every active pointer movement, so navigation starts before release and before the 50% distance point.

Velocity is estimated with Compose `VelocityTracker.addPointerInputChange` and converted from px/s to dp/s using the active display density. Only the upward component is passed to the policy; downward speed is clamped to zero, and the existing vertical-dominant drag gate continues to reject horizontal-dominant movement. The final release sample is evaluated as a fallback.

Tap-to-open, slow pre-threshold rebound, existing player-entry animation, navigation-bar behavior, full-screen downward dismissal, playback state, Queue behavior, backend communication, and persisted preferences were not changed.

## TDD and automated verification

- RED confirmed: the new unit tests initially failed compilation because `MiniPlayerExpansionPolicy.shouldExpand` did not yet accept a velocity parameter.
- Focused `FullScreenPlayerGestureTest`: passed after implementation.
- `compileDebugAndroidTestKotlin`: passed.
- Device instrumentation on SM-G986N / Android 13:
  - `fastUpwardFlickEntersFullScreenBeforeHalfWindowDistanceWhileFingerIsHeld`: passed. The test uses two timed upward movement samples and confirms the full-screen route is reached at 28% of the window height while the pointer remains held.
  - `miniPlayerPullUpTracksFingerAndReturnsWhenReleasedBeforeThreshold`: passed.
  - `miniPlayerPullUpCrossingThresholdContinuesIntoFullScreenPlayer`: passed.
- Full local verification passed:
  `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`
- `git diff --check`: passed.

### Navigation instrumentation class note

A whole-class `connectedDebugAndroidTest` attempt stopped at the first existing Queue navigation test, `horizontalSwipesSwitchBetweenPlayerAndQueueRoutes`, with a Navigation lifecycle exception:

`State must be at least 'CREATED' to be moved to 'DESTROYED' ... route=queue`.

That class-level run did not reach the mini-player cases. The three directly relevant gesture cases were therefore run individually and all passed. The failure is documented rather than represented as a passing class run.

## Device install and data preservation

Built APK: `app/build/outputs/apk/debug/app-debug.apk`  
SHA-256: `906428a1b3863386c1742b54e8596e0c276bba9b60b75af70d40994eba8d85c4`

Installed with `adb install -r`: **Success**.

Before and after the overwrite install:
- `firstInstallTime` remained `2026-10-24 18:47:26`.
- DataStore file `ui_preferences.preferences_pb` retained the same SHA-256: `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`.
- Stored `server_origin` remained `http://[2409:8a28:dec:7c40:2e0:4cff:feff:3468]:17999`.
- The app was launched after installation.

This verifies the configured MS server address and app preferences survived the install. The install verification did not perform a new backend mutation or alter server state.

## Scope and delivery

Only the mini-player velocity-triggered expansion path, its tests, and its Superpowers design/plan/report documents were changed. No other coding agent was used. Self-review completed. Changes remain uncommitted on the existing `task4` branch; no push was performed.
