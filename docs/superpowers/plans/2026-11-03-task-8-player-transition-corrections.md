# Task 8 Player Transition Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Correct the five reported mini-player/Player transition defects while keeping the existing gesture thresholds, media identity and server-owned playback state unchanged.

**Architecture:** Keep the existing AppNavHost-owned progress and artwork cache as the single transition authority. Delay route commit until pointer release, draw the backdrop scrim at the unpadded window level, and reuse one measured artwork overlay in both entry and dismissal directions. The artwork stage's hidden state must include its surface background as well as its image.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, JUnit, Compose UI instrumentation tests, offline Gradle and ADB.

**Spec:** `docs/superpowers/specs/2026-11-02-task-8-player-interaction-polish-design.md` and `docs/superpowers/specs/2026-11-03-task-8-player-transition-corrections.md`

## Global Constraints

- Stay on `feature/task-8-player-interaction-polish`; do not create another worktree or query other coding agents.
- Preserve the 50% window-distance and 1000dp/s upward-fling commit thresholds, applying them only on pointer release.
- Preserve the existing 250ms entry settle, direct tap, early-release restore and downward-dismiss threshold/settle behavior.
- Use the already-loaded artwork bitmap only when its `PlayerArtworkCacheKey` matches the current confirmed artwork identity.
- Use measured on-screen/root coordinates; do not hard-code display dimensions.
- No server, network, DTO, playback state, queue mutation, database, media-session, or cache-loading ownership changes.
- Do not start Task 8 Batch 5 or alter popup/dialog behavior.

## Review Focus

1. A high-velocity upward pointer still held across the threshold must keep the preview on the finger; route commit is release-only.
2. A slow upward pointer released above the distance threshold and a fast pointer released below it must still use the existing accepted-release policy.
3. The edge-to-edge scrim must cover the root window even when foreground content applies top and bottom system insets.
4. A missing destination measurement must not reveal the stationary mini-player image or leave an empty/white transition frame.
5. A cache identity change or absent bitmap must suppress the overlay rather than animate stale artwork.

---

### Task 1 — Keep the pulled Player attached to the pointer until release

**Files:**
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Modify: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`

**Interfaces:**
- `MiniPlayer.onExpandDragDistanceChanged(distancePx, upwardVelocityDpPerSecond)` continues to update only preview progress.
- `MiniPlayer.onExpandDragEnd(distancePx, upwardVelocityDpPerSecond)` remains the only gesture callback that decides whether to navigate.
- `MiniPlayerExpansionPolicy.shouldExpand(...)` keeps its existing threshold and velocity rules.

- [x] **Step 1: Replace the existing fast-flick test with a held-pointer regression**
  Test name: `distanceThresholdKeepsFollowingFingerUntilPointerRelease`.
  Move slowly but beyond the 50%-of-window distance threshold while retaining the pointer. Assert the destination remains HOME, `mini-player-expansion-preview` remains composed, and the Player motion layer remains at a non-settled position. Then send pointer-up; assert the existing release-time distance policy enters FULL_SCREEN_PLAYER and the 250ms settle completes.

- [x] **Step 2: Run the focused test and verify RED**
  Run:
  `./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest#distanceThresholdKeepsFollowingFingerUntilPointerRelease`
  Expected: the current implementation navigates to FULL_SCREEN_PLAYER before pointer-up, so the HOME/preview assertion fails.

- [x] **Step 3: Remove commit logic from the drag-update callback**
  In `updateMiniPlayerExpansion`, keep preview creation and `revealProgress` updates only. Do not call `shouldExpand` or `navigateToExpandedPlayer` from this callback. Keep the existing `finishMiniPlayerExpansionGesture` release-time distance/velocity decision and 250ms route-entry settle unchanged.

- [x] **Step 4: Run focused navigation and unit regressions**
  Run the Task 1 instrumentation class and:
  `./gradlew --offline --no-daemon testDebugUnitTest --tests '*FullScreenPlayerGestureTest'`
  Expected: held-pointer stays on preview; accepted release commits; early release restores; thresholds remain unchanged.

- [x] **Step 5: Commit Task 1**
  `fix(player): keep pull-up sheet attached until release`

### Task 2 — Make dismissal scrim edge-to-edge and hide the entire ArtworkStage surface

**Files:**
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/ArtworkStage.kt`
- Modify: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`
- Modify: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/MiniPlayerArtworkTransitionUiTest.kt`

**Interfaces:**
- Root content and RootBackdropShell retain their existing safe-inset padding.
- `full-screen-root-backdrop-scrim` becomes a full-window sibling layer and continues to use `rootBackdropScrimAlpha(entryProgress, dismissProgress)`.
- `ArtworkStage(artworkVisible = false)` draws neither artwork nor the stage's `surfaceVariant` base.

- [x] **Step 1: Add a failing full-window scrim bounds assertion**
  Test name: `dismissalScrimCoversFullWindowIncludingSystemBarInsets`.
  Compare the scrim's bounds to `full-screen-motion-container` bounds while Player is open and while a downward drag is held. Assert top, bottom, width and height match; this explicitly includes status and navigation bar insets.

- [x] **Step 2: Run the focused test and verify RED**
  Run the FullScreenPlayerNavigationUiTest class.
  Expected: current scrim bounds are reduced by safeContentPadding and do not match the full motion container.

- [x] **Step 3: Move only the scrim outside the safe-content padding**
  Keep RootBackdropShell inside a `fillMaxSize().padding(safeContentPadding)` child. Render the scrim as a `fillMaxSize()` sibling at the unpadded AppNavHost window level, above the root backdrop and below the existing input shield/player. Preserve its existing alpha policy.

- [x] **Step 4: Add a failing hidden-stage pixel test**
  Test name: `hiddenArtworkStageDoesNotDrawSurfaceVariantPlaceholder`.
  Place ArtworkStage over a contrasting parent background with `artworkVisible = false`; capture the rendered center pixel and assert it equals the parent background, not MaterialTheme surfaceVariant. Verify the visible-state case still renders its image.

- [x] **Step 5: Hide background and image in one ArtworkStage layer**
  Reorder the ArtworkStage modifier chain so its alpha graphics layer encloses clipping, surface background and image content. Do not change stage size, measured bounds, tap behavior or gesture ownership.

- [x] **Step 6: Run focused instrumentation and unit tests**
  Run FullScreenPlayerNavigationUiTest, MiniPlayerArtworkTransitionUiTest and the full `testDebugUnitTest` suite.
  Expected: full-window scrim bounds pass; hidden stage is transparent; visible artwork and measurement behavior remain unchanged.

- [x] **Step 7: Commit Task 2**
  `fix(player): extend transition scrim edge to edge`

### Task 3 — Keep artwork ownership stable and implement a continuous linear dismissal trajectory

**Files:**
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt:RootBackdropShell`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/ArtworkTransitionOverlay.kt`
- Modify: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/MiniPlayerArtworkTransitionUiTest.kt`
- Modify: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerNavigationUiTest.kt`

**Interfaces:**
- Add `onArtworkBoundsChanged: (Rect) -> Unit` to RootBackdropShell and pass it to the background MiniPlayer so mini-player artwork bounds remain measurable during full-screen dismissal.
- `ArtworkTransitionOverlay` accepts a nullable destination rectangle and an explicit `dismissalTransition` direction. When destination is not yet measured, it draws the matching bitmap at the measured source rectangle.
- Both entry and dismissal use the same linear four-edge interpolation. Dismissal has no easing or piecewise trajectory switch; all four edges follow raw finger progress from the full-screen rectangle to the mini-player rectangle. The sheet/scrim remain driven by raw finger progress.
- AppNavHost owns separate entry and dismissal ownership predicates, but both consume the same current cache bitmap/key.

- [x] **Step 1: Add a failing target-readiness continuity test**
  Test name: `overlayStaysAtSourceUntilDestinationBoundsAreMeasured`.
  Render the overlay with a matching bitmap, measured source, null target and nonzero progress. Assert the overlay stays exactly on source bounds. Then publish a target and assert the same overlay moves to the interpolated rectangle. Keep the cache identity mismatch test proving stale art is not rendered.

- [x] **Step 2: Run the focused test and verify RED**
  Run MiniPlayerArtworkTransitionUiTest.
  Expected: current overlay is absent when targetBounds is null instead of retaining the image at the source.

- [x] **Step 3: Keep the entry overlay as the artwork owner while target bounds are pending**
  In AppNavHost, the entry-transition active predicate requires matching bitmap identity and measured mini-player source bounds, not a measured full-screen target. Pass the nullable target to ArtworkTransitionOverlay, which falls back to source bounds. Hide the stationary mini-player image only while this overlay is active. Do not guess the destination or display stale artwork.

- [x] **Step 4: Add a failing reverse-geometry handoff test**
  Test name: `overlayInterpolatesFromFullScreenArtworkBackToMiniPlayerArtwork`.
  Use the same bitmap/key and the existing four-edge geometry. At dismissal progress 0, assert the overlay equals full-screen source bounds; at an intermediate dismissal progress, assert it interpolates toward mini-player bounds; at dismissal progress 1, assert it equals the measured mini-player artwork bounds. Add a null-target case that remains at the full-screen source until mini-player bounds arrive.

- [x] **Step 5: Wire the measured reverse source and destination**
  Pass the mini-player bounds callback into RootBackdropShell. During FULL_SCREEN_PLAYER dismissal, normalize the measured full-screen ArtworkStage bounds by removing the live Player drag translation; use measured mini-player bounds as the destination. Pass raw `FullScreenPlayerDismissPolicy.dragProgress(playerDragOffsetPx, playerLayerHeightPx)` to the overlay; it uses the same linear `ArtworkTransitionGeometry.interpolate` as entry. No easing or final-segment trajectory switch is allowed. Hide ArtworkStage and stationary RootBackdropShell mini artwork while the overlay is the visible owner. Exclude the Queue route from Player artwork dismissal handoff. Keep the Player sheet/scrim on raw finger progress.

- [x] **Step 6: Run focused UI and unit regressions**
  Run MiniPlayerArtworkTransitionUiTest, FullScreenPlayerNavigationUiTest and the full `testDebugUnitTest` suite.
  Expected: entry overlay no longer reappears while target measurement is pending; reverse overlay moves to the mini-player image; cancellation and successful dismissal preserve normal ownership handoff.

- [x] **Step 7: Commit Task 3**
  `fix(player): reverse artwork handoff on dismissal`

## Final Verification

- Run `./gradlew --offline --no-daemon testDebugUnitTest`.
- Run connected MiniPlayerArtworkTransitionUiTest, FullScreenPlayerNavigationUiTest and MiniPlayerUiTest on the attached API 33 device.
- Run `./gradlew --offline --no-daemon assembleDebug`.
- Run `git diff --check`; inspect the complete diff for state-authority and route-scope changes.
- Install with `adb install -r` only; never uninstall or clear app data. Verify the updated app launches, original first-install time is retained, and no fatal app exception appears in recent logcat.
- Record exact test counts, APK install identity, current branch/commit and worktree status in the SDD ledger. Do not push or merge.

## Execution boundary

This is a native, inline execution plan for the existing Task 8 branch and authorized workspace. Execute Tasks 1–3 in order with TDD. It covers only the five corrections in the supplement; Batch 5 remains untouched.
