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

## Corrective follow-up: interactive player dismissal and mini-player expansion — 2026-10-27

This follow-up corrects the B2 interaction layer without opening Batch 3, Batch 4, or Batch 5:

- Expanded the Player's downward-drag recognition from the 64dp top band to the artwork image itself. Track-identity text, future lyrics area, progress row, playback controls, and bottom navigation remain outside the gesture region. Queue's top-band-only exit behavior is unchanged.
- The whole Player destination now tracks the accumulated downward finger delta. A gesture released below `max(120dp, 18% of the content viewport)` or cancelled animates back; a qualified drag animates the complete player layer offscreen before returning to the previously active root destination.
- The top two corners round up to 32dp while dragging. The underlying root shell is rendered as a non-interactive visual backdrop from the same app-owned presentation and Runtime; its blur decreases linearly with drag progress and reaches zero after the player leaves the screen.
- Opening the Player from the mini-player expands the full-player layer vertically around the lower mini-player region over 250ms, paired with backdrop blur. No whole-screen alpha-fade transition is used. Ordinary artwork taps are not turned into dismiss gestures; lyrics remain unimplemented and out of scope.
- Unit and instrumentation coverage now includes the pure drag/blur/radius/entry-transform policy, artwork-origin long downward dismissal, short drag release restoring the Player, plain artwork tap not dismissing, previous top-band Player exit, and existing Queue top-band exit.

### Corrective verification

- Final target-device instrumentation: **7 tests passed, 0 failures/errors/skips** on SM-G986N / Android 13. XML: `app/build/outputs/androidTest-results/connected/debug/TEST-SM-G986N - 13-_app-.xml`; Gradle log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-motion-correction-device-tests-final2.log`.
- Covered dismiss from album art; short artwork drag returns to Player; plain artwork tap does not dismiss; existing Player/Queue top-band exit behavior; root backdrop’s measured bounds; artist/album callbacks and presentation layout.
- Focused policy unit tests passed in `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-motion-correction-focused.log`. Final comprehensive command `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`: **BUILD SUCCESSFUL**, 161 unit tests passed, 0 failures/errors/skips; lint had no errors and AndroidTest sources compiled. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-motion-correction-finalverify.log`.
- Final overlaid debug APK SHA-256: `305c592195f34207d876a08856d810dfea196f48ab624f1bba60a8d046067282`. `git diff --check` passed; generated `.kotlin/` scratch data was removed. Existing changes remain uncommitted on `task4`.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`. DataStore SHA-256 before/after was `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`; `cmp` passed, confirming the existing MS server origin preferences remained unchanged.
- `git diff --check` passed. No playback, Queue, DAC or other server mutation was issued. The current root/backdrop uses the existing app-owned presentation and Runtime; there is no new playback state owner. Tap-to-lyrics remains intentionally unimplemented.


## Corrective follow-up: full-screen blurred artwork backdrop — 2026-10-27

The originally implemented B2 Player had not implemented the product-spec requirement in `docs/superpowers/specs/2026-10-24-android-client-product-and-engineering-spec.md` §9: a Gaussian-blurred background derived from the currently confirmed artwork, with a theme-specific contrast veil across the entire full-screen Player. The only blur originally present belonged to the root page behind an interactively dismissed Player. This was a B2 omission, not work intentionally assigned to a later batch; it is now implemented here.

- Added `PlayerArtworkBackdrop` as a full-size, non-interactive background layer beneath the whole Player UI. The foreground album art remains sharp and uses its existing responsive stage.
- Blurred background is derived only from the confirmed current song's existing artwork resource. Foreground and blurred versions are produced off the main thread and assigned together for that song identity; an identity change clears old art, and cancellation/late results are not used as a new track's background.
- The blur uses a downsampled bitmap and separable Gaussian convolution in software, keeping the implementation available from minSdk 26 without RenderScript, a new dependency, or relying exclusively on API 31 `Modifier.blur`.
- A light/dark theme-adaptive mask protects foreground contrast. When there is no valid artwork, the app displays a soft app-owned gradient placeholder behind the same theme mask; it does not reuse an unrelated/last-seen song or Queue artwork.
- The full-screen Player's wrapping Surface is now transparent so this backdrop can be seen across the complete Player content area; Queue remains on its current themed surface.
- New unit policy tests cover image downsample sizing and mask selection. Compose instrumentation asserts the entire backdrop layer, no-art fallback and theme veil are present while existing artwork-swipe/tap and Queue exit tests remain active.
- Focused policy tests: `b2-artwork-backdrop-focused.log`. Final comprehensive build and real-device tests, along with overlay/DataStore checks, are recorded in the final section after validation.


## Corrective follow-up: eager current-artwork memory cache — 2026-10-27

- Root cause: the artwork HTTP request and Gaussian blur generation were first started inside `FullScreenPlayerScreen`'s `LaunchedEffect`. The full-screen route and its expansion animation could therefore become visible before either bitmap existed.
- The existing navigation shell now warms a one-entry process-memory cache as soon as its `PlayerPresentation` projects a fresh/confirmed current song with a server-provided artwork reference. Image bytes are obtained through the same `AppRuntime.reads.getArtwork()` and current origin; decoding and the existing software Gaussian blur run off the UI thread.
- The sharp and blurred bitmap are published together under an identity containing `song_id`, artwork/file resource identity, server epoch, library revision, and connection generation. Missing/unconfirmed art never creates a preload key. A changed identity cancels the previous job and a request token prevents delayed results from replacing the newer current song. The cache is transient image/UI data only.
- Mini-player navigation remains synchronous to let Navigation own the back-stack lifecycle. While the full-player canvas is at its collapsed mini-player size, the route effect waits for the current identity to settle to Ready/Failed; only then does the existing 250ms expansion animation begin. On success both images are ready before the expansion begins. Failed startup reads do not loop; the same identity is not retried on the tap path; a new song, library revision or connection identity starts a new preload.
- `PlayerArtworkMemoryCacheUiTest` covers startup warming, foreground + blur atomic readiness, no duplicate read for the same identity, no repeat read after same-identity failure, fail-closed missing/unconfirmed state, late previous-song response isolation, epoch/library revision identity, and reuse in the player screen. `FullScreenPlayerNavigationUiTest` also checks the existing navigation and swipe behavior after the expansion is fully laid out. RED compile evidence: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-red.log`; focused device instrumentation completed 12/12 tests on SM-G986N / Android 13 (`artwork-preload-device-tests.log`). Full local build/lint/unit-test outcome and overlay preference preservation are recorded below after completion.
- Boundaries: the cache is transient image/UI data only; canonical song/actual playback remain from the existing server snapshot. No new read owner, runtime/socket/client, dependency, local business database, or server endpoint; no playback or Queue writes.

### Final verification — 2026-10-27

- `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`: **BUILD SUCCESSFUL**. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-finalverify.log`.
- Focused instrumentation on **SM-G986N / Android 13**: **12/12 passed** (4 cache/resource lifecycle tests plus existing full-player navigation, presentation and backdrop tests). Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-device-tests.log`. The navigation subset was also rerun independently and passed **5/5** after synchronizing NavController navigation and gating only the expansion animation; log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-navigation-focused.log`.
- Installed the final generated `app-debug.apk` on the already-connected device using `adb install -r` (**Success**, package/version unchanged). The existing `files/datastore/ui_preferences.preferences_pb` SHA-256 was `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c` both immediately before and after the final overlay; byte-for-byte `cmp` passed, preserving the configured MS origin and UI preferences. Final evidence copies: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/artwork-preload-pref-before-final.pb` and `artwork-preload-pref-after-final.pb`.
- Launched the final installed app; `MainActivity` became the resumed activity and no startup `FATAL EXCEPTION` / `Unable to start activity` appeared in the fresh logcat window. `git diff --check` passed. No other Task 4 batches were changed.

## Corrective follow-up: edge-to-edge full-window Player motion — 2026-10-27

The Player dismissal animation initially did not include the transparent status-bar area in its moving canvas. This was a layout hierarchy regression, not a missing edge-to-edge window setup: `MainActivity` already uses `WindowCompat.setDecorFitsSystemWindows(window, false)` and transparent system-bar colors. On SM-G986N / Android 13, a new failing instrumentation assertion measured the old Player motion layer as `Rect.fromLTRB(0.0, 73.0, 1080.0, 2400.0)`; the layer was nested inside a parent with `safeContentPadding`, so its top edge started 73px below the physical window top.

- Moved only the Player's animated motion layer to the full-window root so its canvas, background, upper corners, and foreground move together from y=0 to the physical window bottom.
- Applied the same Android-reported safe-content insets to the Player's foreground Column instead of the animated outer layer. The exit gesture band therefore starts below the actual status-bar/display-cutout safe inset, while artwork/title/progress/controls/footer keep their safe positions.
- Kept the previous root backdrop and its input shield inside the safe-content region. The shield does not cover the actual status-bar/cutout band, preserving Android's notification-shade/system gesture ownership. The system status icons remain OS overlays; the corrected moving element is the full-window app canvas behind the transparent bars.
- Extended `MainActivityEdgeToEdgeUiTest` to assert the full-window motion-layer bounds, artwork backdrop top edge, exact inset alignment of the gesture band, active short-drag movement of the full canvas, and return to y=0 after release.
- TDD RED evidence: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-edge-to-edge-red.log` failed on the old y=73px bounds as expected. GREEN and short-drag restore evidence: `b2-edge-to-edge-gesture-final.log`.
- Real-device regression on SM-G986N / Android 13 for MainActivity edge-to-edge, Player navigation, Player presentation and artwork backdrop: **10 instrumentation tests passed, 0 failures/errors/skips**. Log: `b2-edge-to-edge-device-regression.log`; result XML `app/build/outputs/androidTest-results/connected/debug/TEST-SM-G986N - 13-_app-.xml`.
- Local comprehensive verification `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`: **BUILD SUCCESSFUL**, 163 unit tests passed, 0 failures/errors/skips, lint had no errors, AndroidTest sources compiled. Log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-edge-to-edge-finalverify-rerun.log`. The first comprehensive attempt had one existing intermittent DataStore recreation test failure (“multiple DataStores active for the same file”); that test passed when rerun alone (`b2-edge-to-edge-datastore-flake.log`) and the subsequent full rerun passed all 163 tests.
- Installed the rebuilt debug APK on the same device: `adb install -r app/build/outputs/apk/debug/app-debug.apk` returned `Success`. The stored UI preferences SHA-256 stayed `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c` before and after installation. APK SHA-256: `ab987a21351eb5373673d1042163c6746bc876adf612f6b57ea84d25cb06aaed`.
- `git diff --check`: passed. Changes remain uncommitted on `task4`; no playback, Queue or server write operation was performed.

## Corrective follow-up — Player identity/control hierarchy — 2026-10-27

- Increased the fixed playback-control band from 64dp to 88dp and each of its three touch targets from 56dp to 72dp. The extra fixed height shifts the title/artist/album area and the progress/source-format stack upward, while the control centers move less; the bottom three frameless destinations and their anchors remain unchanged.
- Replaced the sharp previous/next drawings with filled double triangles using rounded corners, enlarged their drawing area to 44×36dp, and enlarged the center play/pause glyph to 48dp. The three playback glyphs use the theme's `onSurface` color for primary contrast. Control semantics and existing disabled state are unchanged; no playback or queue logic was modified.
- TDD RED was observed on the old layout: the new on-device assertion for an 88dp control band failed as expected. After the focused implementation, Player presentation instrumentation passed 2/2; the Player presentation/navigation regression passed 7/7 on SM-G986N / Android 13. Full local verification `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug` succeeded: 163 unit tests passed, 0 failures/errors/skips, lint without errors, and AndroidTest sources compiled. Logs: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-controls-finalverify.log` and `b2-controls-device-tests.log`.
- Installed the debug APK using `adb install -r` (**Success**) and relaunched the app. DataStore preferences were preserved byte-for-byte by hash: `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c` before and after install, retaining the configured MS origin. APK SHA-256: `220dc9e416b35a9ca40c23254211922113014ccd8879eda83c16d317a21660ed`. Device screenshot: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b2-controls-installed-player.png`.
- `git diff --check` passed. No playback, Queue, or server mutation was issued; no new state/network owner or dependency was introduced.
