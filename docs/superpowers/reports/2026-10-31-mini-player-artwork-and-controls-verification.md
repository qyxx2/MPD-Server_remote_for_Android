# Mini-player artwork and playback-control wiring — verification

**Date:** 2026-10-31
**Branch:** `task4`
**Base:** `6127376` (`feat(android): enter full player on fast mini-player flick`)

## Root cause

- The mini-player artwork Surface always rendered a decorative music-note glyph; the MiniPlayer composable had no bitmap input.
- The root navigation shell already owned a warmed `PlayerArtworkMemoryCache`, also used by the full-screen Player, but it did not pass the Ready bitmap into MiniPlayer.
- The mini-player play/pause IconButton was hardcoded with `enabled = false` and an empty `onClick`; this was an unfinished Task 3 placeholder.
- Task 4 already supplies `PlayerActions`, the canonical confirmed PLAYING/PAUSED projection, server-issued control-target validation, mutation serialization, and pending/unknown protection.

## Changes

- AppNavHost now observes the existing app-scoped artwork cache and exposes the foreground bitmap only when the cache is Ready, its key equals the current mini-player key, and the key matches the current media presentation. No second loader, request, cache, or dependency was added.
- MiniPlayer displays the bitmap with `ContentScale.Crop` in the existing 48dp artwork Surface. Missing/loading/error/mismatched artwork continues to render the native music-note placeholder.
- MiniPlayerPresentation now reuses the same typed `PlayerPresentation.playbackStatus` used by the full-screen Player. It retains the last known title while labeling unconfirmed states, and considers a decoded song object a track even when its title is blank.
- AppNavHost maps confirmed PLAYING to PAUSE and confirmed PAUSED to RESUME. The button is enabled only when the shared `PlayerActions.isEnabled` allows that action; clicks call the existing `PlayerActions.perform`. UNKNOWN, stale, disconnected, unresolved targets, and pending operations remain disabled. The control remains a sibling of the tap/drag body and cannot open the full-screen route.

## Verification

**RED:** the initial new MiniPlayer UI tests failed to compile because the original composable had no bitmap/action parameters. The presentation tests also caught an incomplete test fixture that could not satisfy the real current-occurrence binding; it was replaced with the project's canonical Task 4 playback fixture.

**Unit/build:**
- `MiniPlayerPresentationTest`: passed, including complete confirmed PLAYING/PAUSED fixtures, unknown/stale snapshots, missing tracks, and untitled-track artwork slot.
- `PlayerActionsTest`: passed, confirming the existing PAUSE/RESUME intents still use the real shared control-target path.
- Full local command passed:
  `./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`
- `git diff --check`: passed.

**SM-G986N / Android 13 instrumentation:**
- `MiniPlayerUiTest`: 4/4 passed (provided artwork bitmap renders; paused click dispatches Resume callback without opening; playing click dispatches Pause callback without opening; UNKNOWN stays disabled).
- `AppNavigationUiTest#miniPlayerControlEligibilityFollowsTheCanonicalSnapshotAndBodyOpensFullScreenPlayer`: passed.
- `AppNavigationUiTest#rootPagesShowSharedAppBarScrollableContentMiniPlayerAndNavigation`: passed.
- `AppNavigationUiTest#allRootPagesUseTheSharedShellAndSettingsDoesNotAddMiniPlayerSpace`: passed.

These tests cover the actual mini-player component and the AppNavHost route that binds its controls and artwork. The dispatched callback is verified in UI tests; the existing PlayerActions suite separately verifies the PAUSE/RESUME mutation contract without issuing writes to the configured user's MS during instrumentation.

## APK install and persisted settings

- APK: `app/build/outputs/apk/debug/app-debug.apk`
- SHA-256: `884811ba5717252ad37a7c6e6f721b2a44c069ebb2f3e874eecce7cf607a0369`
- `adb install -r`: Success.
- `firstInstallTime` remained `2026-10-24 18:47:26`; the package was overwritten, not uninstalled.
- DataStore SHA-256 before and after install remained `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`.
- Saved `server_origin` remained `http://[2409:8a28:dec:7c40:2e0:4cff:feff:3468]:17999`.
- The app was launched after installation.

## Scope and delivery

Only mini-player artwork binding, typed playback status/action wiring, related tests, and Superpowers docs were changed. Existing playback/Queue/backend logic, the full-screen player, artwork loader/cache, and the previous velocity-triggered expansion commit were not altered. No other coding agent was used. Changes are ready for a separate local commit; no push is intended.
