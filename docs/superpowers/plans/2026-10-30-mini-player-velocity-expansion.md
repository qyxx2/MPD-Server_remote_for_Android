# Mini-player velocity-triggered expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Complete (with the unrelated class-level Queue lifecycle failure documented below).

**Goal:** Let a fast upward mini-player flick enter the full-screen Player before the existing 50%-of-window drag distance, without changing slow-drag behavior.

**Architecture:** Track the active mini-player pointer using Compose `VelocityTracker`, convert the upward velocity to dp/s, and send it with the current drag distance to AppNavHost. The existing pure expansion policy makes the single decision: distance threshold OR upward-velocity threshold.

**Tech Stack:** Kotlin, Jetpack Compose UI pointer input, JUnit, Android Compose instrumentation tests.

**Spec:** [Mini-player velocity-triggered expansion design](../specs/2026-10-30-mini-player-velocity-expansion-design.md)

## Global Constraints
- Continue on the existing `task4` branch; do not create a branch/worktree or use other coding agents.
- Preserve the existing measured distance threshold of 50% of app-window height.
- Trigger the velocity path at 1000 dp/s upward, during the active drag and before pointer release.
- Preserve tap-to-open, slow pre-threshold rebound, navigation-bar motion/opacity, and full-screen downward dismissal.
- Add no dependencies and make no playback, Queue, backend, or persistent-state changes.

## Review Focus
- Slow drag below 50% must not be mistaken for a fling.
- Slow drag reaching 50% must still expand independently of velocity.
- Upward-dominant drags qualify; downward velocity is ignored and the existing direction gate rejects horizontal-dominant movement.
- A fast flick must navigate before release and while its distance remains below 50%.
- Invalid/non-finite velocity or an unavailable window measurement must not create an accidental distance-triggered expansion.

---

### Task 1: Add the fast upward-flick expansion path

**Files:**
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayer.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerLayoutPolicy.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Test: `app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerGestureTest.kt`
- Test: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`

**Interfaces:**
- MiniPlayer expansion callbacks carry `distancePx: Float` and `upwardVelocityDpPerSecond: Float`.
- `MiniPlayerExpansionPolicy.shouldExpand(upwardDistancePx, windowHeightPx, upwardVelocityDpPerSecond)` returns true when either the measured 50% distance or the 1000 dp/s upward-speed threshold qualifies.

- [x] Write unit tests for the inclusive 1000 dp/s threshold, sub-threshold speed, downward speed, invalid speed, and unchanged distance-trigger behavior.
- [x] Run the focused unit test and confirm RED because the policy does not yet accept velocity (compiler rejected the missing parameter).
- [x] Implement the minimal policy and pointer-velocity propagation; evaluate the velocity during drag updates and on release.
- [x] Add instrumentation proving a <50%-distance fast flick navigates while the pointer is still held; slow 30% pull still rebounds; slow 50% pull still expands by distance.
- [x] Run focused unit tests and compile the affected navigation instrumentation source.
- [x] Run the affected device gesture cases individually on SM-G986N / Android 13: fast flick, slow pre-threshold rebound, and slow distance-threshold expansion (all 3 passed).
- [x] Run the full local verification command:
  `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug` (passed).
- [x] Review the diff and run `git diff --check`.
- [x] Verify the final APK with `adb install -r`; the existing app data and MS origin were preserved.
- [x] Attempt the full `FullScreenPlayerNavigationUiTest` class. It stopped on its first Queue navigation test with a NavBackStackEntry lifecycle exception before reaching mini-player tests; all three relevant gesture tests were run individually and passed (see the verification report).
- [x] Final review: self-review (no subagent tool; user disallowed other coding agents). No commit or push was requested.
