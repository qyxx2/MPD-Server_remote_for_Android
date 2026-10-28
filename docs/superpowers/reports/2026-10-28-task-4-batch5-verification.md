# Task 4 Batch 5 Verification Report — Queue / Played Read-only Surface

Date: 2026-10-28
Branch: `task4`
Starting HEAD: `25902179e884df7125d7f4f7b6da7438368a310a`

## Scope

Implemented Batch 5 only: read-only Queue projection and row rendering, canonical current/next ordering, Played resource selection/fallback, bounded metadata/artwork reads, Queue route integration, and focused automated tests. No Queue mutations, menu actions, drag reorder submission, or continuous Played reveal were added; these remain Batch 6, Batch 7 and Batch 8 respectively.

## Implementation

- Replaced the Queue destination placeholder with `QueueScreen`, wired through the existing `AppRuntime` and shared `RuntimeReadApi`.
- Added pure `QueuePresentation`: selects the position-0 occurrence; delegates actual playback confirmation to the existing B1 `PlayerPresentation`; follows Web’s MANUAL-first and AutoPlay-fallback projection; sorts Played by descending negative position. When confirmation is unavailable it keeps the business current visibly unconfirmed.
- Rendered occurrence rows using `queue_item_id` as the stable lazy-list key. Repeated `song_id` values therefore keep distinct row identities while sharing a single metadata read.
- Added the bounded in-memory `QueueSongCache`: shared/in-flight request deduplication, concurrency limit, finite entry count and artwork-byte budgets, and eviction/late-response guards keyed by epoch, library revision, connection generation, origin and currently visible IDs. It checks requested song identity and validates image MIME type, per-image byte cap and image signature before retaining artwork bytes.
- Reserved the format metadata line while the song is loading or unavailable, and added an instrumented measurement asserting that a loaded title/artwork resource does not change the Queue row’s measured height.
- Reuses fresh canonical Played data. Only a non-ready snapshot with a retained session triggers the existing `runtime.reads.getPlayed()` fallback; the completion is rejected if connection, epoch, origin or snapshot freshness scope changed. No new HTTP client, state owner or persistent music-library copy was introduced.
- Initial list begins at the business current and scrolls to the top when the relevant Queue baseline changes. Visible metadata prefetch is limited to the visible rows plus a small look-ahead window; a de-duplicated `snapshotFlow` observes window changes without causing a Compose re-layout/recomposition loop.
- Kept the current/Played drag glyph disabled and the trailing menu glyph visual-only, preserving the later batches’ write/gesture boundary.

## Verification

The following commands completed successfully:

- `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL**.
- Unit test reports contain **224 tests, 0 failures, 0 errors and 0 skipped**. The new Queue tests cover manual/AutoPlay precedence, current-confirmation/paused labeling, occurrence identity, Played ordering/fallback selection, invalid/empty queue, request deduplication and concurrency, stale-response rejection after epoch/library revision/connection generation changes, artwork error/type/signature handling and bounded cache behavior.
- `./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest` — **BUILD SUCCESSFUL, 8/8 instrumentation tests passed** on SM-G986N / Android 13 (3 Queue UI tests plus 5 full-screen/navigation regression tests, including Queue route re-entry and fixed row height during metadata replacement).
- `git diff --check` — passed.
- Source boundary scan — passed: Queue UI/cache contains no new HTTP transport/client, MutationCoordinator, Queue write route or persisted duplicate Queue data. Its production reads are only `runtime.reads.getSong`, `runtime.reads.getArtwork` and `runtime.reads.getPlayed`.

Lint completes with non-blocking warnings and no errors. The report contains dependency-version recommendations and existing framework/style warnings; this Batch does not update unrelated dependency or security configuration.

## Installation and saved configuration

The connected Android test run installed the debug build over package `io.github.qyxx2.mpdserver.remote`. The device retained its original `firstInstallTime` (2026-10-24 18:47:26) while `lastUpdateTime` advanced, confirming an update rather than data-clearing reinstall. The app DataStore preference file `files/datastore/ui_preferences.preferences_pb` has SHA-256:

`ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`

This matches the previously recorded hash; the configured server-origin preference was preserved. No server-side playback or Queue mutation was sent. The installed debug APK SHA-256 is `9401f4bf9b50d02ccb60cbb2502140c063fe7d9e4e2a69e8a8c9c82fcd5f81bb`.

## Boundaries / not covered

- Batch 5’s Queue row UI is instrumented with controlled snapshot fixtures so results are deterministic. This report does not claim a manual visual inspection of the live server’s Queue contents on-device.
- No Queue action, playback write, drag reorder, or Played reveal gesture was tested or implemented because these are outside Batch 5.
- No independent visual review at narrow-window/system-font extremes was performed.

## Artifacts

- Main implementation: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/`
- Unit tests: `app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/`
- Compose/navigation tests: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreenUiTest.kt` and `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`
- Final build log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b5-finalverify-3.log`
- Focused connected test log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b5-connected-finalverify.log`

---

## Corrective follow-up — AUTOPLAY next row missing — 2026-10-28

### Reproduction and cause

- Reproduced the reported issue on the configured live MS without changing server state: read-only `GET /api/state` returned `playback.autoplay_enabled=true`, a position-0 `AUTOPLAY` current occurrence, a position-1 `AUTOPLAY` candidate, and no positive-position `MANUAL` rows. The connected app showed only the current row.
- Audited the read-only Web implementation (`web/src/components/queue/queuePresentation.ts`): it reads `snapshot.playback.autoplay_enabled` directly, then shows at most the first AutoPlay candidate when there is a current item and no manual pending item.
- Root cause: Android tried to decode the entire playback JSON element into the legacy `PlaybackStateDto`, which expects `context` and `position`. The actual canonical snapshot has `playback_context_id` and `position_seconds`. With strict `JsonConfiguration` (`ignoreUnknownKeys=false`), the DTO decode threw; the surrounding `runCatching` turned that failure into `false`, suppressing the AUTOPLAY row. Earlier Queue tests used the old shape, so they did not exercise the real wire contract.

### Correction and regression evidence

- Added a unit regression and a Queue Compose instrumentation regression using the actual canonical playback wire shape. Both were observed RED before the production correction:
  - `autoplay-wire-shape-red.log`: the projection returned no next row.
  - `autoplay-ui-red.log`: the expected AUTOPLAY row was not displayed.
- Corrected only `QueuePresentation`: read `autoplay_enabled` directly from the snapshot's playback JSON object, with strict JSON-boolean handling. This mirrors Web's field-level behavior and avoids coupling this projection to the obsolete full-object DTO. Manual-pending precedence, first-AUTOPLAY-only behavior, canonical occurrence identity, confirmed-playback authority and all write boundaries remain unchanged. No DTO shared by B3 was changed.
- Final verification: `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL; 225 unit tests, 0 failures/errors/skips**. Full log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/autoplay-finalverify.log`.
- Focused connected test: `./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreenUiTest` — **4/4 instrumentation tests passed** on SM-G986N / Android 13, including the canonical-shape autoplay-row regression. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/autoplay-ui-green.log`.
- After an `adb install -r` overlay install, the real app was reopened and its Queue screen was checked through the device UI hierarchy. It now showed the current row (`山外小楼夜听雨`) and the next AutoPlay row (`不值得`); additional AutoPlay candidates were not rendered. The test and live-server checks were read-only with respect to playback/Queue.
- DataStore preference SHA-256 before and after the overlay install was identical: `ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`. Installed debug APK SHA-256: `0e87a7ef48fb288cd58eab8ca437339403c22b5911c97c79fd4327ba5608c572`.
- `git diff --check` passed. Only the Queue projection and its unit/UI regression tests changed. No Web/server/API/dependency change, no new state/network owner, no server mutation, and no Batch 6/7/8 work was performed.
