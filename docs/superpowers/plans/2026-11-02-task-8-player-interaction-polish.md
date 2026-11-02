# Task 8 Player Interaction Polish Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. This work is executed directly in the authorized workspace; do not dispatch or query other coding agents. Steps use checkbox syntax for tracking.

**Goal:** Complete the approved Queue, navigation, lyrics, mini-player artwork and app-owned popup interaction polish without changing playback/server authority.

**Architecture:** Keep Queue reorder and artwork geometry as domain-specific transient UI state. Add one shared popup-motion geometry/progress layer while each owning screen retains its existing content, form state and actions. Preserve the existing Navigation shell and full-screen Player/Queue presentation split.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose 2.9.0, Compose UI instrumentation tests, JUnit, existing Gradle/ADB toolchain.

**Spec:** docs/superpowers/specs/2026-11-02-task-8-player-interaction-polish-design.md

**Contract Matrix:** docs/superpowers/specs/2026-11-02-task-8-contract-matrix.md

## Global Constraints

- Base branch is main at 4d185f9a9928852f169d3e127e0e7cf25183c731; implementation branch is feature/task-8-player-interaction-polish.
- No server API, DTO, networking owner, playback state owner, mutation owner, database or runtime dependency changes.
- Queue identity remains queue_item_id; canonical server Queue order remains authoritative.
- Preserve epoch, connection generation, queue revision, current occurrence and pending membership guards.
- Current and played Queue rows cannot be dragged or used as drop targets.
- Preserve mini-player entry threshold (50% of window height), velocity trigger, 250ms settle, direct tap, full-screen dismissal, root navigation motion and Player/Queue pager gesture.
- Use exact current PlayerArtworkCacheKey / PlayerArtworkMemoryCache identity; never animate stale artwork.
- Ordinary route transitions use 200ms and approximately 1/4 viewport horizontal travel, with no page-content fade.
- Timed lyrics use 32sp active / 26sp inactive; keep 40sp / 34sp line heights unless actual clipping proves they must change.
- Popup reveal uses 180ms, scale 0.82→1.0 and alpha 0→1.0, with the reverse close motion and trigger-relative transform origin.
- Preserve popup/dialog positioning, width, outside tap, Back, action callbacks, text input, IME, focus and accessibility. If a trigger disappears, use the last measured trigger or measured owning row/control/container bounds as the anchor; never invent a screen-center origin.
- No push, merge or main-branch edits unless explicitly requested.

## Review Focus

1. Pointer-input node reuse after drag/menu state changes: repeated handle gestures and overflow menu taps must remain available in the same Queue composition. Covered by B0/B2 lifecycle tests.
2. A dragged overlay that only visually overlaps a row by exactly 50% must not select it; only a ratio strictly above 50% is eligible. Covered by QueueDragTargetPolicyTest.
3. Queue canonical state can change while a pointer is down; stale draft must cancel without sending an occurrence reorder. Covered by QueueReorderDraftTest and QueueReorderUiTest.
4. Artwork source/target bounds or media identity may change during the 250ms settle; the overlay must not jump or show a previous song's bitmap. Covered by geometry and transition UI tests.
5. Dialog close animation can race with owner state changes, IME or navigation; keep the host composed only through the reverse transition, then dispose exactly once. Covered by PopupMotion tests and dialog UI tests.

---

## Batch 0 — Queue repeated-interaction root-cause diagnosis

### Task 0.1 — Establish the failing interaction matrix and evidence

**Files:**
- Create: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueInteractionLifecycleUiTest.kt
- Inspect: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueOccurrenceRow.kt
- Inspect: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt
- Inspect: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt
- Inspect: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueReorderDraftTest.kt
- Create: docs/superpowers/reports/2026-11-02-task-8-batch0-root-cause.md

**Interfaces:**
- Reuse the existing QueueScreen test seam: snapshotProvider and queueActionsFactory.
- The test fixture must acknowledge each submitted reorder with a matching canonical readback before the next gesture; otherwise a mutation lock can be mistaken for a pointer-input failure.
- Do not change production code in this batch.

- [ ] **Step 1: Add named instrumentation reproductions**
  Add tests:
  - sameQueueHandleCanDragTwiceInOneComposition
  - differentQueueHandlesRemainDraggableAfterFirstDrag
  - dragThenImmediatelyOpenSameRowsOverflowMenu
  - dismissOverflowMenuThenDragQueueRow
  - cancelledDragCanBeFollowedBySuccessfulDrag
  - repeatedDragAndMenuCyclesRemainAvailable
  Each test asserts both the expected gesture callback/mutation and the continued existence/enabled semantics of the next handle/menu.

- [ ] **Step 2: Run each reproduction independently**
  Run each method independently using the instrumentation runner class argument with the method suffix, for example QueueInteractionLifecycleUiTest#sameQueueHandleCanDragTwiceInOneComposition; then run the full class once. Expected: each test either reproduces the reported failure consistently or passes; record the exact test, cycle number and observable failure. Do not classify a failure caused by the fake mutation remaining locked as a pointer bug.

- [ ] **Step 3: Trace the first failing boundary**
  Inspect the failing sequence in this order: pointerInput key/node identity; gesture detector cancellation and change consumption; callback freshness; resetReorderDrag/LaunchedEffect ordering; LazyColumn item key/content reuse; selectedMenuItem and Popup composition; QueueActions action lock and canonical readback. Use focused temporary diagnostics only if needed and remove them before the batch ends.
  Expected: report names the earliest failing boundary and cites reproduction output or relevant state transitions. If no stable reproduction exists, record that the root cause remains unlocated and do not invent a fix.

- [ ] **Step 4: Save the diagnosis and verify the batch diff**
  Update docs/superpowers/reports/2026-11-02-task-8-batch0-root-cause.md with exact device/API, commands, reproduction steps, evidence, excluded hypotheses and root-cause confidence.
  Run git diff --check and inspect git diff.
  Expected: only the test/report files changed; no production fix is bundled into B0.

- [ ] **Step 5: Commit the diagnostic baseline**
  Commit message:
  test(queue): reproduce repeated row interaction lifecycle

**B0 exit:** The lifecycle matrix is executable and the first proven failing boundary is documented. If the bug cannot be reproduced, B0 exits as UNLOCATED and B2 must not guess; collect additional evidence before changing production input code.

---

## Batch 1 — Full-screen Queue row menu visual redesign

### Task 1.1 — Freeze menu visual measurements in UI tests

**Files:**
- Create: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenuUiTest.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt only after RED is observed.
- Update: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueActionsUiTest.kt to assert the new shared left edge instead of the superseded centered-label contract.

**Interfaces:**
- Preserve QueueMenuPresentation.from(item, enabled) and all existing callbacks/action availability.
- Preserve the menu's current anchor and dismissal owner; B5 will later add shared popup motion.

- [x] **Step 1: Write failing Compose assertions**
  Add tests:
  - queueRowMenuUsesLightCompactSurface
  - queueRowMenuActionsUseOneIconColumnAndLeftTextEdge
  - queueRowMenuWidthComesFromLongestActionNotSongTitle
  - queueRowMenuDismissesOnBackAndOutsideTap
  Assert white 0.94 alpha, 18dp shape, 7dp shadow and zero tonal elevation using rendered-pixel capture or component-exposed test semantics; use node bounds for equal icon columns, matching label edges and title-width constraints; also verify long-title ellipsis and unchanged dismissal.

- [x] **Step 2: Run the focused UI test to verify RED**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueRowMenuUiTest
  Expected: current surface/shape/elevation/alignment/width assertions fail for the existing 22dp/10dp centered menu.

- [x] **Step 3: Implement the menu layout**
  In QueueRowMenu.kt:
  - Use Color.White.copy(alpha = 0.94f), RoundedCornerShape(18.dp), shadowElevation = 7.dp and tonalElevation = 0.dp.
  - Render each action as an icon + label Row with a fixed 22dp icon column and consistent 8dp gap; align all labels to the same leading edge.
  - Use the project's existing line-icon visual language for Move Next, Move to End, Remove and Cancel.
  - Measure the action column as the width authority. Constrain the optional song title to that measured width and ellipsize it; do not let the title determine intrinsic menu width.
  - Keep action ordering, enabled state, IDs, semantics, outside tap and Back behavior unchanged.

- [x] **Step 4: Run the focused test and existing Queue regressions**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueRowMenuUiTest
  ./gradlew --offline --no-daemon testDebugUnitTest
  Expected: new visual tests pass and QueueMenuPresentationTest / Queue actions tests remain green.

- [x] **Step 5: Review and commit**
  Run git diff --check; inspect the visual diff and test output.
  Commit message:
  feat(queue): refine full-screen row action menu

**B1 exit:** All menu measurements and interaction semantics pass; no Queue mutation or row identity behavior changes.

---

## Batch 2 — Queue drag geometry and repeated-interaction repair

### Task 2.1 — Add a pure overlap-target policy

**Files:**
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueDragTargetPolicy.kt
- Create: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueDragTargetPolicyTest.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt after policy tests are RED.

**Interfaces:**
- Define QueueDragTarget(queueItemId: String, bounds: Rect).
- Define QueueDragTargetSelection(queueItemId: String, overlapRatio: Float).
- Define QueueDragTargetPolicy.select(draggedBounds: Rect, candidates: List<QueueDragTarget>, draggedQueueItemId: String): QueueDragTargetSelection?.
- Candidates supplied by QueueScreen contain only visible pending occurrences other than the dragged occurrence; current/played rows are never admitted.

- [x] **Step 1: Write pure policy tests**
  Add tests:
  - overlapBelowHalfIsRejected
  - overlapExactlyHalfIsRejected
  - overlapAboveHalfIsAccepted
  - greatestOverlapWins
  - equalOverlapChoosesNearestCenter
  - draggedOccurrenceIsNeverItsOwnTarget
  - emptyAndZeroAreaCandidatesAreIgnored
  Assert strict > 0.5f against target full area, not intersection area and not row-center distance.

- [x] **Step 2: Add the smallest compilable policy signature, then verify RED**
  Create QueueDragTargetPolicy with the specified types/signature and a temporary neutral implementation that returns null. This keeps the test source compiling while ensuring the behavior assertion fails.

- [x] **Step 3: Run QueueDragTargetPolicyTest and verify RED**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*QueueDragTargetPolicyTest'
  Expected: the policy test compiles and fails on the first threshold/selection assertion because the neutral stub returns null.

- [x] **Step 4: Implement the pure policy**
  Replace the neutral stub. Calculate intersection area / target bounds area. Ignore non-finite or zero-area rectangles. Sort eligible candidates by descending ratio, then ascending distance between target center and dragged center. No screen or Compose state is read by this policy.

- [x] **Step 5: Run pure policy and existing draft tests**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*QueueDragTargetPolicyTest' --tests '*QueueReorderDraftTest'
  Expected: all threshold/tie-break and baseline tests pass.

### Task 2.2 — Make the handle start on touch slop and drive the floating overlay from measured bounds

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueOccurrenceRow.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueReorderUiTest.kt

**Interfaces:**
- Keep drag pointer input attached only to QueueDragHandle.
- Add an onRowBoundsChanged callback to QueueOccurrenceRow and report that composable row's actual bounds (not the LazyColumn item wrapper that also contains its divider). QueueScreen stores keyed measured row Rects in one shared root/screen coordinate space and translates the source row Rect by accumulated pointer deltas.
- Continue using QueueOccurrenceDragGhost as a presentation-only overlay; do not move canonical rows locally.

- [x] **Step 1: Add failing handle gesture tests**
  Add/update tests:
  - pendingHandleDragStartsAfterTouchSlopWithoutLongPress
  - stationaryHandleTouchDoesNotCreateReorder
  - ordinaryRowTapStillPlaysAndDoesNotStartDrag
  - currentHandleRemainsNonDraggable
  Assert that moving beyond touch slop starts the callback without a 500–700ms hold and that the whole-row click is unchanged.

- [x] **Step 2: Run the focused Queue reorder UI test and verify RED**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueReorderUiTest
  Expected: the old long-press detector fails the no-long-press behavior assertion.

- [x] **Step 3: Replace long-press detection with touch-slop drag detection**
  Use the Compose drag detector that starts after touch slop, or awaitEachGesture + awaitTouchSlopOrCancellation + drag. Consume movement only after drag recognition. Keep currentOnDragStart/Delta/End/Cancel updated-state callbacks and preserve cancellation cleanup.

- [x] **Step 4: Measure and translate the complete drag rectangle**
  On drag start, capture the source row Rect reported by QueueOccurrenceRow; do not use the enclosing LazyColumn item bounds because the item wrapper includes its divider. For every delta, translate the overlay Rect and pass the keyed measured pending row Rects to QueueDragTargetPolicy. Update the draft only when an eligible target changes. Render an explicit insertion cue on that selected row. Preserve append-at-end when the overlay is below the last pending row and no target qualifies.

- [x] **Step 5: Verify preview and mutation invariants**
  Update tests:
  - dragGhostTracksFingerAndRetainsSourceRowSize
  - insertionCueAppearsOnlyAboveFiftyPercentOverlap
  - releaseSubmitsBeforeHighlightedOccurrence
  - noTargetAtEndUsesAppendSemantics
  - invalidatedBaselineCancelsWithoutMutation
  Expected: preview tracks pointer; canonical row positions stay unchanged before server readback; request uses dragged queue_item_id and selected before_queue_item_id.

### Task 2.3 — Fix only the B0-proven lifecycle root cause

**Files:** Modify only the file(s) identified by B0 evidence, expected candidates:
- app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueOccurrenceRow.kt
- app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt
- app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt

**Interfaces:**
- Retain all new QueueDragTargetPolicy and existing QueueReorderDraft contracts.
- Do not redesign unrelated mutation coordination or add a retry path.

- [x] **Step 1: Add the smallest regression test at the proven boundary**
  Use the failing test sequence and state transition documented by B0. The test must fail before the production fix and assert the first observable event that becomes unavailable.

- [x] **Step 2: Run the regression test to verify RED**
  Run the exact QueueInteractionLifecycleUiTest case that reproduced the defect.
  Expected: failure matches the B0 evidence, not a fixture/mutation-lock error.

- [x] **Step 3: Apply one root-cause correction**
  Change only the proven lifecycle/input-routing/reset condition. Keep stable pointer node identity across ordinary drag state updates; do not add speculative keys, global pointer handlers or broad recomposition workarounds.

- [x] **Step 4: Run the complete interaction matrix**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueInteractionLifecycleUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueReorderUiTest
  Expected: same row twice, A then B, drag→menu, menu→drag, cancel→drag and repeated cycles all pass without leaving/re-entering Queue.

- [x] **Step 5: Run all Queue regression suites and commit**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest
  Commit message:
  fix(queue): keep drag and row menus reusable across gestures

**B2 exit:** Immediate handle dragging, measured overlay, strict overlap targeting, clear insertion cue, occurrence/baseline-safe reorder and repeated menu/drag cycles pass. If B0 remains UNLOCATED, do not execute Task 2.3 until a reproducible trace exists.

---

## Batch 3 — Ordinary page transitions and timed lyrics

### Task 3.1 — Add directional navigation motion

**Files:**
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/NavigationMotionPolicy.kt
- Create: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/navigation/NavigationMotionPolicyTest.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavigationUiTest.kt

**Interfaces:**
- NavigationMotionPolicy exposes durationMillis = 200, travelFraction = 0.25f, forwardEnterOffset(widthPx) = +widthPx/4, forwardExitOffset(widthPx) = -widthPx/4, popEnterOffset(widthPx) = -widthPx/4 and popExitOffset(widthPx) = +widthPx/4 (integer rounding is deterministic).
- NavHost forward enter/exit use opposite horizontal offsets; pop enter/exit use the reverse offsets. Use slideIntoContainer/slideOutOfContainer with 1/4-width offset lambdas and no page alpha animation. Keep FULL_SCREEN_PLAYER and QUEUE destination transitions explicitly set to EnterTransition.None / ExitTransition.None / pop None so their custom full-screen surface and horizontal Pager remain untouched.

- [ ] **Step 1: Write policy and navigation UI tests**
  Add tests:
  - forwardTransitionStartsFromRightAndSettlesAtZero
  - popTransitionStartsFromLeftAndSettlesAtZero
  - ordinaryNavigationUsesQuarterWidthTravelAndTwoHundredMillis
  - fullscreenPlayerAndQueueKeepTheirOwnPagerMotion
  Use the test clock to inspect an intermediate frame and settled bounds; assert no opacity fade is applied to page content.

- [ ] **Step 2: Add a compiling neutral NavigationMotionPolicy signature**
  Define durationMillis, travelFraction and all four named offset helper signatures with zero offsets so the tests compile.

- [ ] **Step 3: Run the focused tests to verify RED**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*NavigationMotionPolicyTest'
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavigationUiTest
  Expected: the zero-offset policy and current no-transition route behavior fail the new assertions, not because of missing symbols.

- [ ] **Step 4: Implement policy and NavHost transitions**
  Use 200ms tween. Wire forward enter to a positive quarter-width slideInHorizontally offset and forward exit to a negative quarter-width slideOutHorizontally offset; wire pop enter/exit to the corresponding opposite signs. Use separate enter/exit and popEnter/popExit lambdas so the back-stack direction is unambiguous. Keep FULL_SCREEN_PLAYER and QUEUE routes explicitly excluded with None transitions (FULL_SCREEN_PLAYER already has this override; add the matching four overrides to QUEUE). Retain NavHost destination/back-stack semantics. Keep the separately composed full-screen Player/Queue layer, mini-player entry preview and dismiss layer outside this ordinary route motion.

- [ ] **Step 5: Run focused navigation regressions**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*NavigationMotionPolicyTest'
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavigationUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest
  Expected: ordinary routes move directionally; mini-player entry, Player/Queue page gestures and downward dismiss remain unchanged.

### Task 3.2 — Reduce timed lyric typography and remove second-line marquee

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStage.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStageUiTest.kt
- Optional test-only extraction if needed: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/lyrics/LyricsTypographyPolicyTest.kt

- [ ] **Step 1: Add failing lyric UI assertions**
  Add tests:
  - activeTimedCueUsesThirtyTwoSp
  - inactiveTimedCueUsesTwentySixSp
  - overlongSecondLineRemainsStaticAndEllipsized
  - timedCueStillSeeksAndFollowsCurrentLine
  Assert 32sp/26sp text styles, retained 40sp/34sp line heights, one-line overflow ellipsis without marquee, and unchanged cue selection/seek/follow behavior.

- [ ] **Step 2: Run LyricsStageUiTest and verify RED**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest
  Expected: the current 34sp/28sp values and basicMarquee behavior fail the new checks.

- [ ] **Step 3: Implement the typography change**
  Set active cue to 32sp and inactive cue to 26sp; retain lineHeight 40sp and 34sp. Remove basicMarquee from the second line and use maxLines=1 with TextOverflow.Ellipsis. Do not alter untimed/plain lyrics scrolling or timed seek/follow state.

- [ ] **Step 4: Run lyrics and full Player presentation tests**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.LyricsStageUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest
  Expected: new typography/overflow checks pass and existing lyric navigation/seek/presentation tests remain green.

- [ ] **Step 5: Commit Batch 3**
  Commit message:
  feat(ui): add directional page motion and refine lyrics

**B3 exit:** forward/pop motion is verified without changing special Player motion; lyric size and overflow satisfy the exact 32sp/26sp contract.

---

## Batch 4 — Mini-player artwork shared-element transition

### Task 4.1 — Implement and test measured rectangle interpolation

**Files:**
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/ArtworkTransitionGeometry.kt
- Create: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/ArtworkTransitionGeometryTest.kt

**Interfaces:**
- ArtworkTransitionGeometry.interpolate(source: Rect, target: Rect, progress: Float): Rect.
- Clamp finite progress to 0f..1f; interpolate left, top, right and bottom independently. Do not assume portrait screen dimensions or fixed dp/px coordinates.

- [ ] **Step 1: Write geometry tests**
  Add tests:
  - progressZeroReturnsSourceBounds
  - progressOneReturnsTargetBounds
  - midpointInterpolatesAllFourEdges
  - progressOutsideRangeIsClamped
  - invalidOrEmptyBoundsAreRejected
- [ ] **Step 2: Add a compiling neutral geometry stub**
  Define ArtworkTransitionGeometry.interpolate with an identity/neutral result so the test compiles.
- [ ] **Step 3: Run focused unit tests and verify RED**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*ArtworkTransitionGeometryTest'
  Expected: midpoint and endpoint assertions fail against the neutral stub.
- [ ] **Step 4: Implement the pure interpolation helper**
  Validate rectangles, clamp progress and interpolate each edge. Keep the helper independent of Compose state.
- [ ] **Step 5: Run the geometry tests**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*ArtworkTransitionGeometryTest'
  Expected: all geometry tests pass.

### Task 4.2 — Measure both artwork surfaces and hand off one identity-safe overlay

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayer.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/ArtworkStage.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerScreen.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt
- Create: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/MiniPlayerArtworkTransitionUiTest.kt

**Interfaces:**
- Add onArtworkBoundsChanged(boundsOnScreen: Rect) to MiniPlayer and ArtworkStage; keep existing mini-player row onBoundsChanged for its current top/height geometry. Build each Rect from LayoutCoordinates.positionOnScreen() plus measured size.
- AppNavHost retains measured mini artwork bounds, full-screen artwork bounds and the same playerEntryProgress already used by the full-screen surface.
- The overlay consumes only miniPlayerArtwork whose key equals currentMiniPlayerArtworkKey; it does not issue artwork requests.

- [ ] **Step 1: Add failing measurement and transition tests**
  Add tests:
  - miniArtworkReportsItsOwnBounds
  - fullScreenArtworkReportsItsOwnBounds
  - dragProgressMovesOverlayBetweenMeasuredBounds
  - directTapUsesTheSameArtworkGeometry
  - earlyReleaseRestoresMiniArtwork
  - artworkIdentityChangeNeverShowsPreviousBitmap
  - completedTransitionHandsOffWithoutAVisibleJump
  Capture source/target bounds and overlay bounds at progress 0, intermediate progress and 1.

- [ ] **Step 2: Run the focused instrumentation tests and verify RED**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.MiniPlayerArtworkTransitionUiTest
  Expected: existing MiniPlayer/ArtworkStage expose no independent measured artwork transition.

- [ ] **Step 3: Report measured image bounds**
  Add onGloballyPositioned callbacks to the artwork image containers (not the entire mini-player row). Report bounds in a common window coordinate space. Keep current measured mini-player row top/height callbacks unchanged.

- [ ] **Step 4: Add the artwork-only transition overlay**
  In AppNavHost, render the current identity-checked bitmap in a temporary overlay at ArtworkTransitionGeometry.interpolate(source, target, playerEntryProgress). The overlay occupies the interpolated rectangle; do not scale the full Player page. Hide/dim source and destination artwork only while the overlay is the visible owner, then hand off to the normal full-screen ArtworkStage at completion without a duplicate frame.

- [ ] **Step 5: Coordinate title/control fade and readiness**
  During expansion, fade mini-player title and playback controls over the first 20% of normalized entry progress (alpha = 1 - clamp(progress / 0.20, 0, 1)); keep the album-art overlay visible. Use existing 250ms settle and same progress for tap and drag. If either bound or bitmap is not ready, wait only for the measured/current identity state or use the existing normal transition fallback; never guess geometry or display another song's bitmap. Cancel/release below threshold must restore the mini-player's normal alpha and artwork.

- [ ] **Step 6: Run focused and existing transition tests**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.MiniPlayerArtworkTransitionUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerNavigationUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayerUiTest
  Expected: drag/tap/cancel/handoff pass; current mini-player entry/dismiss and cache identity regressions remain green.

- [ ] **Step 7: Commit Batch 4**
  Commit message:
  feat(player): animate mini-player artwork into fullscreen

**B4 exit:** Real measured geometry drives the artwork-only animation; source/target use the same current media identity; tap, drag, cancellation and handoff have automated evidence.

---

## Batch 5 — Shared app-wide Popup and Dialog motion

### Task 5.1 — Implement shared trigger-origin geometry and lifecycle progress

**Files:**
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/components/PopupMotion.kt
- Create: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/components/PopupMotionGeometryTest.kt
- Create: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/components/PopupMotionUiTest.kt

**Interfaces:**
- PopupMotionSpec exposes durationMillis = 180 and initialScale = 0.82f.
- PopupMotionSpec.resolveAnchorBounds(triggerBoundsOnScreen: Rect?, ownerBoundsOnScreen: Rect): Rect. PopupMotionSpec.transformOrigin(anchorBoundsOnScreen: Rect, popupBoundsOnScreen: Rect): TransformOrigin.
- Shared transition exposes alpha, scale, transformOrigin and a keepComposed state until reverse close completes. Popup/Dialog hosts supply their own measured final bounds and anchor bounds.
- Anchor tracking is a reusable Modifier or small state holder based on onGloballyPositioned; derive screen-space Rects from LayoutCoordinates.positionOnScreen() plus size. Popup and Dialog may occupy separate Android windows, so do not compare unrelated window-local coordinates. If the trigger is not measurable, resolve to its measured owning control/container bounds, never a fabricated screen-center point.

- [ ] **Step 1: Write failing pure geometry tests**
  Add tests:
  - triggerCenterMapsToPopupTransformOrigin
  - triggerAboveAndBelowPopupAreClampedToSurfaceEdges
  - triggerOutsideHorizontalBoundsMapsToNearestEdge
  - missingTriggerUsesMeasuredOwnerBounds
  Assert normalized TransformOrigin values from resolved anchor center relative to popup screen bounds, clamped to 0f..1f; the missing-trigger case must use the supplied owner screen bounds rather than screen center.

- [ ] **Step 2: Write failing shared motion lifecycle tests**
  Add tests:
  - openingMovesFromPointEightTwoScaleToOne
  - closingReversesScaleAndAlpha
  - popupHostRemainsComposedUntilCloseFinishes
  - dismissCallbackIsDeliveredOnce
  Assert 180ms transition and no immediate disposal on visible=false.

- [ ] **Step 3: Add compiling neutral geometry/motion signatures**
  Add the specified PopupMotionSpec API and a transition shell that compiles but yields neutral values and immediate disposal.

- [ ] **Step 4: Run focused tests and verify RED**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest --tests '*PopupMotionGeometryTest'
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionUiTest
  Expected: origin, scale/alpha progression and delayed-disposal assertions fail (not a compilation error).

- [ ] **Step 5: Implement shared policy and transition**
  Implement exact constants, origin calculation, alpha/scale transition and delayed disposal. Keep content state and action callbacks outside the shared motion helper. The helper must not call actions or modify each surface's owner state.

- [ ] **Step 6: Run shared tests**
  Run the same focused unit/instrumentation commands.
  Expected: all geometry and open/close lifecycle tests pass.

### Task 5.2 — Migrate anchored menus

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/library/SongActionMenu.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueOccurrenceRow.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/library/LibraryCategoryScreen.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/library/SongActionMenuUiTest.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenuUiTest.kt

- [ ] **Step 1: Add failing anchor-origin and reverse-close tests**
  Verify SongActionMenu, QueueRowMenu and Library sort menu each expand from their actual invoking row/button bounds, close in reverse, and preserve their existing popup position policy and width.

- [ ] **Step 2: Run focused menu tests and verify RED**
  Run SongActionMenuUiTest and QueueRowMenuUiTest through connectedDebugAndroidTest.
  Expected: current Popup/DropdownMenu behavior has no shared measured trigger-origin reverse-close contract.

- [ ] **Step 3: Add anchor bounds to the invoking controls**
  Capture measured trigger bounds in window coordinates. In Queue, capture the actual overflow control bounds, not the full row center. In Library, capture the sort button bounds. In SongActionMenu, capture the existing action-area bounds. Preserve each popup's old anchor-side preference and edge clamping when replacing DropdownMenu with raw Popup; only the shared reveal layer may own scale/alpha motion.

- [ ] **Step 4: Apply the shared reveal values to each popup surface**
  Keep each menu's current relative anchor placement, edge clamping, intrinsic sizing, width, action labels, action ordering and dismissal semantics. Do not stack the built-in Material3 DropdownMenu reveal with the shared reveal: QueueRowMenu and Library sort should use the shared low-level Popup host plus an explicit PopupPositionProvider that reproduces their existing anchor alignment/clamping; SongActionMenu keeps its existing centered position provider. Use shared transform origin, 0.82→1.0 scale and 0→1 alpha; on dismissal play the reverse transition before disposing the Popup host.

- [ ] **Step 5: Run menu regressions**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.SongActionMenuUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueRowMenuUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.LibraryNavigationUiTest
  Expected: anchors and reverse animations pass; existing menu visual and Queue behavior contracts stay unchanged.

### Task 5.3 — Migrate all app-owned AlertDialogs

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueRowMenu.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/library/PlaylistScreen.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/FullScreenPlayerScreen.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/queue/QueueScreen.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/components/DialogSurfaceStyle.kt
- Modify: relevant Queue, Playlist, Player and PopupMotion UI tests.

**Interfaces:**
- Add a shared anchored Dialog host/content adapter that applies PopupMotionSpec to the dialog surface and modal scrim. Do not keep Material3 AlertDialog's internal reveal animation underneath the custom reveal; retain a real Compose Dialog window and reproduce the existing surface width, actions and padding in its shared animated content host.
- Each dialog receives the bounds of its invoking control. If the trigger has left composition, close safely from the last measured anchor; do not substitute screen center.
- Dialog body/form/action state remains owned by QueueToolsDialog, PlaylistScreen or OutputDetailsDialog.

- [ ] **Step 1: Write failing dialog animation and behavior tests**
  Cover Queue clear/save, Playlist create/rename/delete/add, and Output Details. For each class verify trigger-origin expansion, reverse close, Back and outside dismissal. For editable dialogs verify TextField input, keyboard shown/hidden, focus and selection still work.

- [ ] **Step 2: Run the focused tests and verify RED**
  Run the existing relevant QueueActionsUiTest, PlaylistFavoritesUiTest and FullScreenPlayerPresentationUiTest plus PopupMotionUiTest.
  Expected: current AlertDialog content does not use shared trigger-origin motion and reverse close.

- [ ] **Step 3: Implement the shared Dialog host**
  Keep a real Android Compose Dialog window for IME/focus/accessibility. Render a transition-controlled scrim and surface inside that host; calculate the surface transform origin from measured trigger and final surface bounds. Keep the Dialog composed until close transition completes, then dispose once.

- [ ] **Step 4: Migrate Queue, Playlist and Output dialogs**
  Preserve all existing title/text/form state, action enabling, save readback retry, playlist mutation guards, accessibility tags and callbacks. Do not postpone or repeat mutations for animation.

- [ ] **Step 5: Run dialog regressions**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueActionsUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.PlaylistFavoritesUiTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.player.FullScreenPlayerPresentationUiTest
  Expected: action/form/dismissal semantics remain green with shared opening and closing motion.

### Task 5.4 — Prove popup inventory completeness and commit

**Files:**
- Modify any remaining popup source found by a fresh rg scan.
- Create: docs/superpowers/reports/2026-11-02-task-8-batch5-popup-motion.md

- [ ] **Step 1: Scan all application-owned popup hosts**
  Run:
  rg -n 'AlertDialog\(|DropdownMenu\(|Popup\(' app/src/main/java
  Expected inventory includes SongActionMenu, QueueRowMenu, LibraryCategoryScreen sort menu, Queue clear/save, Playlist create/rename/delete/add, and Output Details. Any additional app-owned surface found must be migrated or explicitly justified as non-popup/system-owned.

- [ ] **Step 2: Add one test per popup family for reverse close and trigger origin**
  Verify all existing popup test tags and action semantics still exist. No app-owned popup may remain on the old immediate-disposal path.

- [ ] **Step 3: Run popup and affected screen regressions**
  Run:
  ./gradlew --offline --no-daemon testDebugUnitTest
  ./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionUiTest
  Run the focused Song, Queue, Library, Playlist and Player UI test classes listed above.
  Expected: no test failure/error; all dialog text input and focus paths pass.

- [ ] **Step 4: Save evidence and commit**
  Record the popup inventory, test results, timing/origin measurements and any accessibility/IME limitations in the B5 report.
  Commit message:
  feat(ui): unify anchored popup and dialog motion

**B5 exit:** Every app-owned popup/dialog uses the shared trigger-origin opening/reverse-close mechanism; all action, focus, IME and dismissal semantics pass.

---

## Final Task 8 UI-polish integration gate

**Files:**
- Create: docs/superpowers/reports/2026-11-02-task-8-ui-polish-verification.md
- Update: docs/superpowers/specs/2026-11-02-task-8-contract-matrix.md
- Update: docs/superpowers/plans/2026-11-02-task-8-player-interaction-polish.md
- Update: docs/superpowers/README.md
- Update: docs/superpowers/plans/2026-10-24-android-client-master-plan.md only to record this sub-scope and its current state; do not mark overall Task 8 complete.

- [ ] **Step 1: Run clean offline machine verification**
  Run:
  ./gradlew --offline --no-daemon clean testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin assembleDebugAndroidTest
  Expected: exit code 0. Record unit suite/test counts, Lint errors/warnings/hints, APK outputs and Android-test compile/assembly result.

- [ ] **Step 2: Run connected instrumentation regression**
  Run:
  ./gradlew --offline --no-daemon connectedDebugAndroidTest
  Expected: all runnable connected tests pass on the attached API 33 device. Record device/API, test count and any test runner/environment limitation. Do not convert a skipped or unexecuted test into a pass.

- [ ] **Step 3: Audit architectural and code boundaries**
  Check:
  - No new networking, playback owner, DB, API/DTO or mutation path.
  - Queue uses queue_item_id and baseline checks; no canonical optimistic reorder.
  - Mini-player transition thresholds and Page/Queue Pager remain unchanged.
  - Artwork overlay only consumes the matching PlayerArtworkCacheKey.
  - All popup hosts are in the shared inventory and no Dialog is disposed before reverse motion completes.
  Run rg popup inventory, git diff --check, inspect full diff and git status.

- [ ] **Step 4: Install and inspect on the real device**
  Build APK first. Confirm package/applicationId and signing certificate match the already installed app, then use adb install -r only; never uninstall or clear app data. On the target device verify:
  - Queue menu light surface, exact alignment and width;
  - repeated drag/menu cycles without leaving Queue;
  - drag starts on handle movement without long press; >50% target cue and exact insertion;
  - forward/back ordinary pages and Player/Queue gesture separation;
  - timed lyric sizes and static ellipsis;
  - artwork drag and direct-tap handoff, early release restore;
  - each menu/dialog opening and reverse closing, including editable dialogs with keyboard.
  Capture test device/API, APK identity and any vendor-specific behavior in the report.

- [ ] **Step 5: Reconcile all contracts and report remaining gates**
  Update T8-01..T8-17 with exact automated/manual evidence. If B0 was not reproducible, retain the explicit unlocated limitation; do not claim T8-07 fixed. Keep Task 7 Batch 4 lock-screen acceptance open unless it was independently completed and evidenced. Do not mark the main Task 8 total exit gate complete while its Task 0–7 dependencies remain open.

- [ ] **Step 6: Final local commit**
  Commit only the final verification/report/index updates if they are not already included in the relevant batch commits.
  Do not push or merge unless the user explicitly requests it.

## Plan completion and execution boundary

This plan covers the approved B0–B5 UI-polish scope and is designed for direct, in-session implementation in the existing feature branch. Review and approve this plan before implementation begins. B0 is the first executable batch; no production code changes are authorized merely by creating this plan.
