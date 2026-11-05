# Queue Visible Remaining Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Display the remaining duration of exactly the Queue screen's current visible rows, using the same server-calibrated playback clock as the Player.

**Architecture:** AppNavHost owns one PlayerClock and its StateFlow; Player and Queue consume that flow. Queue loads SongDto.duration for unique visible song IDs through RuntimeReadApi.getSong and the existing ResourceMemoryCache, then a pure projection computes and formats the total.

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin coroutines/StateFlow, existing RuntimeReadApi/ResourceMemoryCache, JUnit.

**Spec:** [Queue 可见待播剩余时间设计](../specs/2026-11-05-queue-visible-remaining-time-design.md)

## Global Constraints
- MPD-Server remains the only canonical owner of playback and Queue state.
- Count only QueuePresentation.visibleRows (current + server-rule-selected next); exclude Played and all other rows.
- SongDto.duration is in seconds; current row uses duration minus the shared PlayerClock position, subsequent rows use full duration.
- Unknown duration or unconfirmed position displays --:--; an empty visible list displays 0:00.
- Reuse RuntimeReadApi.getSong and ResourceMemoryCache; do not add a cache owner, API, database, dependency, or artwork request.
- Work only in /home/Gold/WorkSpace/MS_remote_for_Android on feature/queue-visible-remaining-time; do not modify MPD-Server.
- Preserve PlayerClock foreground/background behavior and isolate 100ms ticker collection to the Queue remaining-time label.

## Review Focus
- A repeated song_id in multiple Queue occurrences is counted once per occurrence.
- A visible row with null, zero, negative, NaN, or infinite duration must not produce a misleading partial sum.
- A current row with no confirmed PlayerClock position displays unknown, including stale/disconnected snapshots.
- A Queue with many visible rows must request all their durations without expanding artwork prefetch.
- Switching between Player and Queue, or backgrounding and foregrounding the app, must not create a second clock or leave the Queue timer frozen.

---

### Task 1: Implement Queue Visible Remaining Time (one batch)

**Files:**
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRemainingTime.kt
- Test: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRemainingTimeTest.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerScreen.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt

**Interfaces:**
- Consumes: QueuePresentation.visibleRows, RuntimeReadApi.getSong(songId), and PlayerClock.positionSeconds.
- Produces: QueueRemainingTime.remainingSeconds(rows, durationsBySongId, currentPositionSeconds): Double? and QueueRemainingTime.format(seconds): String; one shared StateFlow<Double?> is supplied to both Player and Queue.

- [x] **Step 1: Write failing unit tests**
  - adds current-song remainder and full durations of following visible rows: current 240s at 60s position, followed by 180s and 90s, yields 450s.
  - counts duplicate song occurrences independently: current and next occurrence share a song_id and each 100s; position 50s yields 150s.
  - returns unknown when current position or any visible duration is unavailable or invalid.
  - returns zero for no visible rows and formats minutes/seconds like the Player, including totals above 60 minutes.

- [x] **Step 2: Verify RED**
  - Run: ./gradlew testDebugUnitTest --tests 'io.github.qyxx2.mpdserver.remote.ui.queue.QueueRemainingTimeTest'
  - Expected: compilation/test failure because QueueRemainingTime does not exist yet.

- [x] **Step 3: Implement the pure QueueRemainingTime projection**
  - Find the current occurrence by position == 0; subtract only its confirmed clock position. Sum complete durations for every other visible occurrence. Return null for unknown/invalid input and zero for an empty list.

- [x] **Step 4: Verify GREEN**
  - Run the focused QueueRemainingTimeTest command from Step 2.
  - Expected: all new calculation/formatting cases pass.

- [x] **Step 5: Share PlayerClock at AppNavHost**
  - Move PlayerClock creation, snapshot/action updates, active-route ticking, and lifecycle freeze/foreground refresh from FullScreenPlayerScreen to AppNavHost.
  - Pass the same StateFlow to each FullScreenPlayerScreen and QueueScreen callsite. The clock ticks only while Player or Queue is the active route; the Player preview overlay does not start a second timer or refresh.

- [x] **Step 6: Load durations for every visible Queue song**
  - In QueueScreen, collect unique nonblank song IDs from visibleRows, request missing durations through runtime.reads.getSong, and retain only the small duration projection for the current resource identity.
  - Do not use QueueSongCache to extend artwork prefetch. Do not read Played or rows beyond visibleRows.
  - Render the value in an isolated composable that alone collects the 100ms clock StateFlow; keep the existing test tag and label.

- [x] **Step 7: Verify the complete Android unit-test suite**
  - Run: ./gradlew testDebugUnitTest
  - Expected: BUILD SUCCESSFUL; all unit tests pass.

- [x] **Step 8: Build and verify the device installation**
  - Run: ./gradlew assembleDebug
  - Install with adb install -r app/build/outputs/apk/debug/app-debug.apk; do not clear app data or uninstall.
  - Verified the app returned to the foreground after cover-install, the existing DataStore preferences file remained present, and firstInstallTime after install was. The app was not uninstalled and data was not cleared.
  - The real-device check covered installation and launch; the actual countdown was verified by the pure calculation tests and build, not by an instrumented live-queue interaction. MS connection was previously tested by the user and its saved settings file remained intact; this install check did not independently inspect a successful post-install API response.

- [x] **Step 9: Review and commit**
  - Run git diff --check, inspect the complete diff, and confirm no service/API/database/dependency changes.
  - Commit the completed batch locally with message feat(android): show queue visible remaining time. Do not push or merge.
  - Verification details: [Batch verification report](../reports/2026-11-05-queue-visible-remaining-time-verification.md).
