# Task 5 Single-Song Menu Presentation Implementation Plan

> **For agentic workers:** Implement inline with the Superpowers TDD workflow. Steps use checkbox syntax for tracking.

**Goal:** Center the shared single-song action popup on the tapped row, with a readable translucent white surface, left-aligned icon/label rows, and intrinsic width based on the longest label.

**Architecture:** Keep SongActionMenu as the one shared entry point used by Home, Library rows, and album members. Replace only its anchored menu presentation with a Compose PopupPositionProvider and intrinsic-width surface; continue routing actions through existing SongActions and MutationCoordinator.

**Tech Stack:** Kotlin, Jetpack Compose UI/Material 3, existing Android instrumentation tests.

**Spec:** docs/superpowers/specs/2026-11-01-task5-single-song-menu-presentation.md

## Global Constraints
- Remain on the existing task5 branch.
- Do not add server APIs, local playback/queue authority, independent mutation state, or new network owners.
- Do not change the full-screen player or Queue occurrence menu.
- Preserve outside-tap/back dismissal, availability gating, favorite freshness, and existing action semantics.
- Keep the existing application ID and unified signing configuration unchanged.

## Review Focus
- Row near each window edge: menu remains fully visible while staying centered as far as the safe bounds allow.
- Longest label changes (including “取消收藏”): all labels remain left-aligned and the right edge follows the widest rendered label.
- Disabled playback/favorite option: icon and text remain legible while the item stays non-actionable.
- Home singleton card and Library/album rows: all resolve through the same popup component.
- Outside touch and Android back: popup dismisses without triggering the underlying row or changing playback state.

---

### Task 1: Shared single-song menu presentation

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/library/SongActionMenu.kt
- Modify: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/library/SongActionMenuUiTest.kt
- Create: docs/superpowers/specs/2026-11-01-task5-single-song-menu-presentation.md
- Create: docs/superpowers/reports/2026-11-01-task5-single-song-menu-presentation-verification.md

**Interfaces:**
- Consumes: SongActionMenu, SongActions, SongPlaybackAction, LocalPlaybackActionsEnabled, LocalFavoritesActions, and LocalFavoriteSongIds.
- Produces: the same public SongActionMenu entry point and the same action callbacks; only popup layout/position/presentation changes.

- [x] Step 1 — Add instrumentation assertions for row-centered placement, icon visibility, shared label left edge, longest-label right boundary, and preserved outside dismissal/action selection.
- [x] Step 2 — Attempt RED instrumentation run. The environment returned DeviceException: No connected devices; this is an infrastructure limitation, not a behavioral test result.
- [x] Step 3 — Replace the default anchored dropdown with a centered/clamped popup; render all options as left-aligned rows with a fixed icon column and intrinsic-width text column.
- [x] Step 4 — Preserve existing item gating and action callbacks; style the popup as 94% white with dark text, 18dp corners, and 7dp shadow.
- [ ] Step 5 — Run ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.SongActionMenuUiTest on a connected device. Expected: all menu UI assertions pass.
- [x] Step 6 — Run ./gradlew :app:testDebugUnitTest; passed: 419 tests, 0 failures, 0 errors.
- [x] Step 7a — :app:assembleDebug completed; APK and unified signature identity verified.
- [ ] Step 7b — :app:lintDebug did not finish: lint analysis made no further progress; bounded retry with --no-daemon --max-workers=2 was also stopped after no progress. Lint result is inconclusive.
- [x] Step 8 — git diff --check passed; branch is task5; no commit/push/merge performed.
