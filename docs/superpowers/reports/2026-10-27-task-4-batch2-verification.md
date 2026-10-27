# Task 4 Batch 2 Verification Report

Date: 2026-10-27
Workspace: `/home/Gold/WorkSpace/MS_remote_for_Android`
Branch: `task4`

## Scope and result

Implemented Task 4 Batch 2 only: responsive full-screen artwork player presentation, secondary Queue navigation shell, frameless three-action bottom navigation, app-content-only downward exit gesture, and typed artist/album navigation requests. The Queue body intentionally remains an empty placeholder for Batch 5; this batch does not implement Queue state/rows or playback mutations.

## Reuse and state authority

- `FullScreenPlayerScreen` receives the existing Application-owned `AppRuntime` and the `PlayerPresentation` derived by `AppNavHost` from its existing canonical `SnapshotStoreState`. It does not create another runtime, snapshot/store, realtime connection, mutation coordinator, or parallel playback state.
- Artwork is fetched only via existing `runtime.reads.getArtwork()`, and only when the canonical presentation confirms the current song and an artwork reference. The real server image bytes are decoded for display; absent/failed artwork produces a blank-state placeholder rather than fabricated artwork or retained previous-song art.
- Added `GET /api/library/artists` to the same Runtime-owned read API using its current-origin provider and existing HTTP client. Artist clicks resolve the existing Web-style exact display-name match to the server-returned `artist_id`; album clicks retain the song's server-provided album ID. Requests cross the app-navigation callback boundary and do not create Task 5 artist/album list screens in Batch 2.
- Inspection of production source found no call to `runtime.start()`; without a lifecycle hook the persisted server origin was applied but its shared realtime controller was never started by the app. This batch therefore starts that same Runtime after a valid non-default configured address is restored or explicitly saved. A fresh install with only the default placeholder remains offline. This repairs the connection lifecycle needed for the existing authoritative snapshot to reach the UI; it does not add a second connection owner.

## UI and interaction

- `QUEUE` is a secondary destination and is not included in `rootDestinations`; the existing four root tabs remain unchanged.
- The Player and Queue destinations both hide the root app bar, mini-player and root nav bar. Their bottom nav uses three frameless 56dp targets: output details, center waveform/current player, and Queue. Only the current Player/Queue destination is accented. Output status/details are projected from the same current presentation, with a caveat that reported ACTIVE does not prove physical output or bit-perfect playback.
- The artwork size is constrained by the actual Compose available width and height. The dedicated 64dp exit region is placed inside content after the real safe-inset padding, so it does not cover the system status bar/cutout and it is not attached to Queue content or playback controls. A sustained downward vertical drag (80dp threshold, vertical movement greater than horizontal) exits to the prior root; normal Android Back still uses the navigation back stack.
- Playback/seek controls are deliberately disabled until Batch 3 connects the shared mutation path and authoritative playback clock. Progress display uses unavailable placeholders, not fake times.
- Lint identified an unused outer `BoxWithConstraints`; it was replaced with `Box`, while actual responsive sizing remains in `ArtworkStage`.

## Verification evidence

- TDD RED: before production layout/gesture policy was added, the focused layout/gesture unit tests failed to compile due to missing `calculateArtworkSideDp` and `FullScreenExitGesturePolicy`, as expected. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-red.log`.
- Final local verification command: `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug` — **BUILD SUCCESSFUL**. The test XML reports **159 passed, 0 failures, 0 errors, 0 skipped**. Lint completed with existing warnings; no lint errors. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-finalverify.log`.
- Manual device launch after the final overwrite install: the Player displayed the server-confirmed track `字字句句 (Live版)`, its actual album-art image, artist and album metadata, and the source format `FLAC · 24 bit · 48 kHz · 2 ch`; the Queue destination displayed its explicitly empty B5 placeholder. Screenshots are retained under `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-player-screen.png` and `b2-queue-screen.png`.
- `git diff --check`: passed.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Target device: ADB `10.87.61.250:40689`, package `io.github.qyxx2.mpdserver.remote`.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: returned `Success`.
- DataStore retention: `files/datastore/ui_preferences.preferences_pb` SHA-256 before and after installation is the same: `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`. Byte-for-byte `cmp` passed. The saved server origin remains `http://[2409:8a28:dec:7c40:2e0:4cff:feff:3468]:17999`.
- Focused real-device instrumentation on SM-G986N / Android 13 — **5 tests passed, 0 failures/errors/skips; BUILD SUCCESSFUL**. Command: `./gradlew --offline --no-daemon connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest,io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest'`. It exercised the title→artist→album→source layout, no-cover placeholder, typed artist/album callbacks, Player/Queue route switching, secondary-route chrome removal, 48dp nav targets, disabled pre-B3 playback controls, and downward exit from Player and Queue. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-final-device-tests.log`; XML test cases are in `app/build/outputs/androidTest-results/connected/debug/TEST-SM-G986N - 13-_app-.xml`.
- Live MS connectivity/sync smoke on the same device: a temporary instrumentation test called the saved HTTP origin's existing `GET /api/library/artists` and observed `RuntimeConnectionStatus.CONNECTED` with a non-null, `SnapshotFreshness.FRESH` canonical Runtime snapshot. Result: **1 test passed, 0 failures/errors** (1.982s); the temporary environment-specific test source was removed after validation. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-live-ms-smoke.log`.

## Commit boundary

No commit was created in this run. The worktree already contained the earlier MS server-address settings implementation, and it shares `AppNavHost` and `MpdServerRemoteApplication` with Batch 2. The implementation and tests are complete in the worktree; the plan's separate-commit item remains unchecked so those earlier changes are not silently bundled into a Batch 2 commit.

## Explicit exclusions

No Batch 3 playback controls/clock/seek mutation; no Batch 4 lyrics presentation/gestures; no Batch 5 Queue/Played state, history paging or row actions; no DAC writes or hardware claims; no server-repository changes. Batch 2 does not claim complete end-to-end equivalence for realtime lifecycle/mutation recovery or physical audio.
