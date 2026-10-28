# Task 4 Batch 4 — Lyrics UI corrective follow-up

Date: 2026-10-28

Workspace: `/home/Gold/WorkSpace/MS_remote_for_Android`

Branch: `task4`

Scope: requested UI correction only; no Batch 5 work, no API/Web/server/runtime owner change.

## Changes

- Expanded the timed-lyrics list to the full available main-content region, preserving the progress, playback controls and bottom navigation anchors and leaving a 12dp gap at the bottom of the lyrics content.
- Increased timed lyric typography to 28sp / 34sp line height for regular cues and 34sp / 40sp for the active cue. Each cue renders no more than two visual lines. A cue that fits uses its actual one-line or two-line content; an overlong cue keeps the first visual line and horizontally marquee-scrolls the remaining text in the second line. No synthetic lyric rows are inserted.
- The final return-to-artwork control is aligned at the upper-left and uses a compact horizontal left arrow drawn at 18dp, with rounded endpoints and a moderately thick stroke. Its 48dp touch target remains unchanged; it only switches lyrics mode back to artwork.
- Kept server-provided `lyrics_source` intact in the presentation model but suppresses the internal `embedded` label in the status UI. Real error/synchronization statuses remain visible.
- Updated the Task 4 contract matrix, visual spec and implementation plan so they consistently describe the available-height viewport and two-line cue limit instead of the previous five-line target.

## TDD and verification

The new device UI checks first failed against the old implementation: the normal cue row was too short for the new typography target, the return glyph had no explicit Canvas bounds, and `来源：embedded` was displayed. After the production changes, the focused lyrics UI suite passed **6/6** on SM-G986N / Android 13.

Final command:

```sh
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest,io.github.qyxx2.mpdserver.remote.ui.player.PlayerControlsUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest
```

Result: **BUILD SUCCESSFUL**; 211 unit tests passed, 0 failures/errors; 19/19 connected instrumentation tests passed on SM-G986N / Android 13; lint, Android-test Kotlin compilation, APK assembly and `git diff --check` passed. Full log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/lyrics-ui-correction-finalverify.log`.

- Debug APK SHA-256: `69a05a7c895ca1c96d95b026b3322fbce46802b6769586b5190b3af48b561191`.
- `adb install -r` succeeded. The app's DataStore preferences hash before and after overlay was identical: `ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`, preserving the configured MS server address.
- No live MS playback, seek or Queue mutation was issued. Verification covers the UI and existing action boundaries; a live-library lyric visual sample and large system-font-scale/narrow-screen manual inspection were not performed.
- No commit, push, merge or branch switch was performed at the time of this initial correction report.

## Final icon refinement — 2026-10-28

- Following additional visual feedback, moved the return-to-artwork action to the upper-left and changed the diagonal arrow to a horizontal left arrow. The glyph is now 18dp with rounded line caps and a moderately thick stroke; the 48dp touch target is unchanged. No playback, queue, lyrics parsing, or mutation behavior changed.
- Final focused command: `./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest` — **BUILD SUCCESSFUL**, 6/6 instrumentation tests passed on SM-G986N / Android 13.
- `git diff --check` passed. Final debug APK SHA-256: `7ec6091f72a81becd76812349732a73c4404f8be7dc173af1042adaa18376927`. Overlay installation and app launch smoke check succeeded; DataStore preferences SHA-256 remained `ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`, so the saved MS server configuration was preserved.
- The broader verification above predates this final icon-only refinement; the focused 6/6 run verifies the final icon change. Live server playback/seek/Queue actions were not performed.
