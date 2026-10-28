# Task 4 Batch 3 Corrective Follow-up — Full-screen Player Status Flap

Date: 2026-10-28
Branch: `task4`
Scope: Only the reported Batch 3 fullscreen-player track/UNKNOWN flicker after Play/Resume.

## Why the previous attempt was ineffective

The first handoff correction retained media while an action was visibly pending, but Android's `MutationCoordinator` can publish `SUCCESS` before the follow-up snapshot read completes. Meanwhile, `PlayerActions` may retire its page-local interaction after the server temporarily drops the bound control target. The first correction consequently disarmed on the still-old confirmed snapshot. A second timing edge remained: a newer confirmed observation could arrive before the first quiet-`UNKNOWN` observation, disarming the fallback just before another snapshot went `UNKNOWN`. Either edge allowed title/artwork/format to switch to the unconfirmed placeholder and then back at the polling cadence.

The Web baseline at local MPD-Server commit `2ad84454ceb70817bd996c92b590cfa2d9e9b128` was checked in `playerPresentation.ts`, `playerFacts.ts`, `playerActions.ts` and `playerSession.ts`. Android now treats this as a bounded *display-only handoff* across the coordinator/readback timing window and short same-scope confirmed/quiet-UNKNOWN oscillations.

## Correction

`PlayerPresentationHandoff` now:

- Arms from the original action lifecycle, including the gap where the coordinator has returned `SUCCESS` but the server readback is not complete. It recognizes the same `OperationActivity`/intent when `PlayerActions` retires page-local state; it creates neither a new intent nor a new mutation owner.
- Does not disarm merely because a same-scope confirmed sample arrives before the first quiet-`UNKNOWN` sample. Once the first quiet-`UNKNOWN` interval starts, its monotonic six-second deadline is preserved across recovered-confirmed and repeated quiet-`UNKNOWN` samples; the timer cannot be extended on every poll.
- Retains the last confirmed media presentation/artwork eligibility and last confirmed display position only for the same server epoch, Runtime connection generation, library revision, business playback song/context, song/file and queue position-zero occurrence. The Runtime must still be connected on the current generation, with no snapshot read error, and the original same-scope action must remain pending/syncing/success. Disconnection, scope change, read error, failure/unknown outcome or deadline removes the fallback.
- Keeps the server snapshot as the sole authority. The fallback never promotes a song/state to confirmed, never enables play/pause/previous/next/seek, never changes an action target, and never writes a cached position back to SnapshotStore. In fullscreen only media title/artist/album/source format/artwork and the bounded visual progress display use the retained projection; controls continue to use canonical `PlayerPresentation` and server observation.

Changed only the Task 4 fullscreen presentation handoff, its regression tests and the corrective report/progress evidence. No Web/server implementation, API, dependency, Batch 4 lyrics or Batch 5 Queue work was introduced.

## Regression evidence

- Test-first regression `bridgesCoordinatorSuccessReadbackGapAndRepeatedQuietUnknownFlapsUntilOriginalDeadline` initially failed on the first quiet-UNKNOWN handoff (after coordinator success/readback gap). A further assertion then exposed the separate confirmed-sample-before-UNKNOWN edge. Both paths pass after the final correction. Evidence: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/bugfix-status-flap/second-gap-red.log` and `final-focused-unit.log`.
- `PlayerPresentationHandoffTest`: 7 tests, 0 failures/errors. Coverage includes coordinator outcome/readback gap, a confirmed readback before UNKNOWN, repeated unknown/confirmed oscillation without deadline reset, action retirement with shared activity, scope drift, failure/unknown outcomes, six-second expiry, disconnection and read-error refusal.
- Final local verification command: `./gradlew --offline --no-daemon --no-build-cache testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`. Result: **BUILD SUCCESSFUL; 194 unit tests passed, 0 failures, 0 errors, 0 skipped**; lint, AndroidTest Kotlin compilation and APK assembly succeeded. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/bugfix-status-flap/final-local-verify.log`.
- Final focused real-device instrumentation on SM-G986N / Android 13 selected `PlayerControlsUiTest` and `FullScreenPlayerNavigationUiTest`: **9/9 passed**. The new `quietUnknownHandoffKeepsLastTrackVisibleWithoutEnablingCanonicalControls` asserts the track remains visible in quiet UNKNOWN while canonical play and seek controls remain disabled. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/bugfix-status-flap/final-device-tests.log`.
- Installed the final debug APK with the existing package identity. APK SHA-256: `9619f8843d210e67e133432d828dd07a29435a4a56988a887df0d283e0b0400a`. The saved `files/datastore/ui_preferences.preferences_pb` SHA-256 was `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c` before and after installation, so the configured MS origin was retained.
- A live Play/Resume UI smoke action was triggered against the already-configured MS server. Subsequent read-only `/api/state` polls across 5.7 seconds returned the same track (`酷爱`) and queue occurrence, with `PLAYING`, `fresh`, `actual_state=playing`, `sync_status=CONFIRMED` and matching bound/control-target occurrence on all ten samples. No music-library mutations or file deletion were performed. The automated snapshot-transition and UI regression tests remain the direct evidence for the reported rapid UNKNOWN/confirmed flap; no server or music files were changed for the test.

## Delivery boundary

The Batch 3 handoff correction and dependent Batch 4 lyrics work are committed together locally on `task4`. They share `FullScreenPlayerScreen.kt` and `AppNavHost.kt`, and Batch 4 consumes Batch 3's canonical playback clock/action boundary; a single tested changeset avoids a misleading split of the shared-screen integration. No push, branch switch, or merge into another branch was performed. `.superpowers` logs are local execution evidence. The combined final verification passed.
