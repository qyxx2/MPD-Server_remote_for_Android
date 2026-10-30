# Mini-player artwork and playback-control wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement the plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display the current confirmed album cover in the mini-player and wire its play/pause button to the existing remote playback coordinator.

**Architecture:** Reuse the app-shell-owned `PlayerArtworkMemoryCache` and require an exact current `PlayerArtworkCacheKey` match before exposing the bitmap. Use the existing `PlayerActions` plus canonical `PlayerPresentation.playbackStatus` to select PAUSE/RESUME and reject UNKNOWN/stale/pending operations.

**Tech Stack:** Kotlin, Jetpack Compose, Android Compose instrumentation tests, JUnit.

**Spec:** [Mini-player artwork and playback-control wiring](../specs/2026-10-31-mini-player-artwork-and-controls-design.md)

## Global Constraints
- No new dependency, artwork endpoint/loader/cache, playback state owner, or direct repository mutation from MiniPlayer.
- Display only the Ready bitmap whose key matches the current confirmed presentation; otherwise keep the native placeholder.
- Select PAUSE only for confirmed PLAYING, RESUME only for confirmed PAUSED, and no action for UNKNOWN.
- Button enablement must come from the existing `PlayerActions.isEnabled`; dispatch through `PlayerActions.perform`.
- Preserve 64dp mini-player height, 48dp artwork, body tap-to-open, upward drag behavior, and button/body separation.

## Review Focus
- Cached art from the previously playing song must not appear after current song/connection identity changes.
- Loading/failed/missing artwork must retain the placeholder without affecting row geometry.
- A fresh PAUSED observation must dispatch RESUME; a fresh PLAYING observation must dispatch PAUSE.
- UNKNOWN/stale/disconnected/pending states must keep the button disabled and must not dispatch.
- Clicking play/pause must not trigger the body navigation gesture.

---

### Task 1: Reuse the shared artwork cache and playback action adapter

**Files:**
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayer.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayerPresentation.kt`
- Modify: `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavHost.kt`
- Modify: `app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayerPresentationTest.kt`
- Create: `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/shell/MiniPlayerUiTest.kt`

**Interfaces:**
- `MiniPlayer` consumes `artwork: ImageBitmap?`, `playbackControlEnabled: Boolean`, and `onTogglePlayback: () -> Unit`; status-specific glyph and accessibility label use `presentation.playbackStatus`.
- AppNavHost exposes artwork only from `PlayerArtworkCacheState.Ready` when `ready.key == currentArtworkKey` and `currentArtworkKey.matches(mediaProjection.mediaPresentation)`.
- AppNavHost maps `ConfirmedPlaybackStatus.PLAYING -> PlayerAction.PAUSE`, `PAUSED -> RESUME`, `UNKNOWN -> null`; action eligibility is `playerActions.isEnabled(action)`.

- [x] Write direct MiniPlayer UI tests for rendering a supplied bitmap, toggling a confirmed paused/playing action without opening the body, and rejecting disabled/unknown control.
- [x] Run the new UI test and verify RED because MiniPlayer had no artwork/action inputs yet.
- [x] Update the mini-player presentation to remove hardcoded null artwork and permanently-disabled action fields; preserve last-known title and explicit unconfirmed status.
- [x] Pass a currently key-matched cached bitmap from AppNavHost; render it in the existing 48dp Surface with `ContentScale.Crop` and retain the placeholder when null.
- [x] Route the mini-player button through the shared `PlayerActions`; derive status from canonical `PlayerPresentation`, disable on UNKNOWN/pending/otherwise-ineligible state, and preserve button/body separation.
- [x] Update prior AppNavigationUiTest assumptions to match real PlayerActions eligibility while preserving the disabled-click/no-navigation and body-open checks.
- [x] Run focused presentation/action unit tests, the new 4-case MiniPlayer UI test on SM-G986N, and 3 AppNavigationUiTest cases (all passed).
- [x] Run `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug` (passed).
- [x] Review the final diff and run `git diff --check`.
- [x] Install the final APK with `adb install -r` and verify the configured MS origin/DataStore remain unchanged.
- [ ] Commit this fix locally as a separate commit; do not push.
