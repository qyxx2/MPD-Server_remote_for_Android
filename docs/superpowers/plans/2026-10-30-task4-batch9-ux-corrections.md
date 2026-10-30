# Task 4 Batch 9 UX Corrections Implementation Plan

> **Execution:** Implement directly on the existing `task4` branch in the requested workspace. Do not dispatch or query coding agents. Follow RED→GREEN for each behavior and run full verification before reporting.

**Goal:** Correct the lyrics layout, Queue row menu appearance, Queue dismiss motion, and add interactive horizontal Player↔Queue paging without changing server-owned playback or queue state.

**Architecture:** Keep lyrics and Queue as presentation-only views over the existing `AppRuntime`. Reuse the existing full-screen vertical-dismiss transform and threshold for Queue's dedicated safe-top gesture band. Use Compose Foundation `HorizontalPager` for finger-following horizontal motion; synchronize its settled page to the existing Navigation routes, while the existing bottom buttons request pager movement. The Queue list remains a native `LazyColumn`.

**Tech Stack:** Existing Kotlin, Jetpack Compose BOM/Foundation/Material 3, Navigation Compose and current test dependencies. No new dependencies or server changes.

**Approaches considered:**
- Keep separate routes and add horizontal drag detection plus hand-built translation layers: rejected because both pages must be rendered and positioned during the drag, and custom pointer handling risks conflicting with Queue scrolling.
- Change only Navigation enter/exit transitions: rejected because the transition would begin after gesture release rather than follow the finger.
- A shared `HorizontalPager` between the existing Player and Queue destinations: selected because it provides interactive drag progress and native axis arbitration while keeping vertical Queue scrolling in its own LazyColumn.

## Global Constraints
- The service and existing `AppRuntime` remain the only playback/Queue state and mutation authority.
- Do not add HTTP clients, network requests, state stores, or local copies of canonical Queue/playback data.
- Do not attach Queue dismissal pointer handling to the LazyColumn, Played rows, or reorder handles; Queue dismissal remains exclusive to the existing safe-content top band.
- Lyrics must not move progress, playback controls, source metadata, or bottom navigation anchors.
- Keep the queue row menu anchored, compact, outside-click dismissible, and no wider than 264dp.
- No Web, backend API, database, or dependency changes.

## Implementation Tasks

### Task 1: Immersive lyrics and Queue row menu
**Files**
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerScreen.kt`
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt`
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/components/DialogSurfaceStyle.kt` or a focused Queue menu color helper
- Test `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStageUiTest.kt`
- Test `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueActionsUiTest.kt`
- Test `app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueMenuPresentationTest.kt`

**Required behavior**
- When lyrics are visible, remove the artwork-only 24dp side inset from the lyrics viewport; retain modest text insets and the two-line cue/second-line marquee contract. Do not change the fixed lower layout anchors.
- Tint only the Queue row menu panel with a low-saturation blend of the active theme surface and primary-container accent. Keep other dialogs unchanged.
- Reduce menu side padding and excess vertical gaps, increase action label size while preserving a touch target of at least 44dp, and keep the panel width at or below 264dp.

### Task 2: Queue top-band dismiss uses the shared full-screen motion
**Files**
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenExitGestureBand.kt`
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt`
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Test `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`

**Required behavior**
- Queue's safe-content top band reports drag delta, release distance, and cancellation to the same parent motion owner used by Player.
- The whole full-screen layer follows vertical drag, rounds its top corners, and reveals/de-blurs the same root backdrop; below threshold or on cancellation it springs back, above threshold it exits after the same off-screen animation.
- The Queue LazyColumn and row reorder/menu pointer handling remain untouched.

### Task 3: Finger-following Player↔Queue horizontal paging
**Files**
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Modify `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppDestination.kt` only if a small route helper is needed
- Test `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`

**Required behavior**
- Player→Queue uses a leftward horizontal swipe; Queue→Player uses a rightward swipe. The page content tracks pointer position and settles naturally.
- Bottom Queue/Now Playing buttons use the same pager; settled page updates the existing Navigation route and preserves expected Back/exit behavior.
- Vertical scrolling in Queue remains native and must not change the selected page.
- No changes to playback, Queue data, or mutation flow.

## Verification
- Run each focused unit/UI test RED before its production change and GREEN after it.
- Run `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`.
- Run focused connected instrumentation for lyrics, Queue menu, and Player/Queue navigation/gestures on the configured Android device when available.
- Install the debug APK with `adb install -r`; do not clear app data. Verify the installed package and preserve the configured MS server address.
- Run `git diff --check`, inspect the complete diff, and confirm that no files outside this correction scope or server-owned state boundaries changed.
- Do not commit unless separately requested.

## Completion Notes
- [x] All three implementation tasks completed on the existing `task4` branch.
- [x] New/extended RED tests were run on the configured device before production implementation; baseline confirmed the narrow lyrics view, 44dp menu rows, and lack of horizontal route change.
- [x] Final verification passed: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and 26/26 connected instrumentation tests (0 skipped, 0 failed).
- [x] `git diff --check` passed.
- [x] `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeded without uninstalling or clearing app data. DataStore preference-file hashes before tests, after tests and after install were identical: `f00cefe7ddf4a4e3e1be5c495cb1ea2f32216eebb04c2806e70f131c1e516084`.
- [x] No backend/API, Web, server-owned state, or dependency changes. No commit created.
- Detailed evidence: `docs/superpowers/reports/2026-10-30-task-4-batch9-ux-corrections.md`.
