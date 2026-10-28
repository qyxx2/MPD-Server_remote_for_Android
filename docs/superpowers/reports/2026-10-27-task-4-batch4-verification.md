# Task 4 Batch 4 Verification Report

Date: 2026-10-28
Workspace: /home/Gold/WorkSpace/MS_remote_for_Android
Branch: task4
Scope: Batch 4 only. No MS server, Web source, API contract, playback engine, or dependency changes.

## Implemented

- Added a native lyrics parser mirroring the frozen MPD-Server Web parser: lrc/text formats (case-insensitive), embedded timed lyrics tagged text, Web's 1–3 digit fraction conversion, multiple timestamps, stable tie ordering, metadata filtering, safe-integer offset handling, active cue, and offset-corrected seek target.
- Added distinct server-backed presentation for missing lyrics, read_error with retained fallback text, plain text, malformed/no-readable cues, unsupported format, and unconfirmed resources. The client does not inspect media tags, scan files, or override the server's embedded/sidecar choice.
- Added Compose lyrics mode. Artwork tap enters lyrics; the top-right return icon returns to artwork only. Lyrics replace the main content region without moving the progress, playback controls, source-format area, or bottom navigation.
- Timed lyrics use only the fresh B3 playback display clock and current canonical occurrence/target scope. Cue taps flow through existing PlayerActions/MutationCoordinator; no local network or mutation owner was added. Unknown playback position does not highlight a row or enable cue seek.
- Added a 3-second manual-follow reducer. Continued scrolling resets the deadline; active gesture/text-selection protection blocks automatic return. Plain text has independent manual scrolling, no active cue, and no lyric-triggered seek/follow timer.

## Automated verification

Command:
    ./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest

Result: BUILD SUCCESSFUL. Full log: .superpowers/sdd/2026-10-27-task-4-full-player-queue/b4-finalverify.log

- Unit tests: 211 passed, 0 failures, 0 errors, 0 skipped.
- Instrumented tests on SM-G986N / Android 13: 5 passed, 0 failures, 0 errors, 0 skipped. XML: app/build/outputs/androidTest-results/connected/debug/TEST-SM-G986N - 13-_app-.xml.
- The real-device follow test manually scrolls timed lyrics, waits beyond 3 seconds, advances the Compose test clock and verifies that the active cue returns.
- lintDebug, assembleDebug, and compileDebugAndroidTestKotlin passed.
- git diff --check passed.
- Final debug APK SHA-256: a8c63c49e04315cdc6d01e6b5bb2f3a9f8797474d640052ee90b456b47742db7.

Instrumented scenarios cover artwork/lyrics switching and fixed lower anchors; offset-corrected cue seek and unknown-clock no-highlight behavior; drag/long-press versus cue-tap separation; timed lyric follow returning after the 3-second idle interval; plain-text presentation/scrolling; and visible timed fallback text with server source embedded and read_error status.

## Combined B3/B4 integration regression gate — 2026-10-28

Command:

    ./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest,io.github.qyxx2.mpdserver.remote.ui.player.PlayerControlsUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest

Result: **BUILD SUCCESSFUL**; 211 unit tests passed, 0 failures/errors/skips; 18/18 real-device instrumentation tests passed on SM-G986N / Android 13; lint, APK assembly, AndroidTest Kotlin compilation and `git diff --check` passed. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b3-b4-combined-finalverify-rerun.log`.

The initial combined run exposed an obsolete B2 navigation test assumption: after a plain tap, it looked for artwork even though Batch 4 explicitly defines artwork tap as opening lyrics. Updated that regression to assert the lyrics mode opens without leaving the Player route, the return control restores artwork, and a separate short downward drag does not dismiss the Player. The rerun passed all 18 tests. This was a test/spec integration correction; no production behavior was weakened or changed to satisfy the old expectation.

## Device data preservation

The connected test workflow replaced the debug APK without uninstalling the app. The saved DataStore preferences hash was recorded before testing and read again afterward.

Before: ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45
After:  ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45

The hashes match exactly; saved MS origin/settings were preserved. No playback or Queue mutation was submitted to the real MS service.

## Coverage limits and repository state

- Actual embedded-LRC and ordinary-text tracks from the live library were not used as fixtures; source/fallback/parser behavior is covered with contract fixtures. A live cue-seek was not submitted, to avoid changing the user's current playback.
- Plain-text scrolling was verified as independent/manual and has no timed-follow effect in its composition branch, but an on-device measurement of the same scroll offset after a >3-second idle was not added.
- Before consolidation, the task4 worktree contained uncommitted Batch 3 status-flap/handoff changes in shared player/navigation files. Dependency review confirmed Batch 4 uses Batch 3's playback clock and `PlayerActions`, while both batches modify `FullScreenPlayerScreen.kt` and B3's projection is wired through `AppNavHost.kt`. After reviewing the combined diff and passing the integration regression gate, the related Batch 3 and Batch 4 changes were committed together locally rather than split into a B4-only commit.
- The Web baseline repository was read-only throughout.
