# Mini-player artwork and playback-control wiring

**Date:** 2026-10-31
**Branch:** `task4`

## Intent
Show the real album artwork in the 48dp mini-player surface and make its single play/pause button send the matching remote control, without adding another playback state owner or another artwork request path.

## Root-cause findings
- `MiniPlayer` always draws the decorative `♫` placeholder. It has no bitmap input and never renders an image.
- `MiniPlayerPresentation.from` hardcodes `artworkUrl = null`, and the root composable does not pass the already-existing image cache state into MiniPlayer.
- `AppNavHost` already warms `PlayerArtworkMemoryCache` from the confirmed `mediaProjection.mediaPresentation`; `FullScreenPlayerScreen` reads the same cache and checks `PlayerArtworkCacheKey.matches` before displaying a bitmap. The app shell simply fails to reuse that result.
- The play/pause `IconButton` is explicitly `enabled = false`, has an empty `onClick`, and announces that control is not connected. This is the remaining Task 3 placeholder. Task 4 already supplies `PlayerActions` and gates PAUSE/RESUME against the same fresh, occurrence-bound playback observation used by the full-screen controls.

## Selected approach
Reuse the existing one-entry `PlayerArtworkMemoryCache` and its current `PlayerArtworkCacheKey`. AppNavHost will expose a bitmap to MiniPlayer only when the Ready state belongs to the currently matched media presentation. MiniPlayer will display it with `ContentScale.Crop`; on missing/loading/failed/mismatched art, it retains the app-owned music-note placeholder.

Use the existing `PlayerActions` adapter for play/pause. AppNavHost chooses PAUSE for confirmed PLAYING, RESUME for confirmed PAUSED, and no action for UNKNOWN. The button is enabled only when the selected action passes `PlayerActions.isEnabled`; the click calls `PlayerActions.perform`. The full-screen and mini-player surfaces therefore share the same Runtime, operation coordinator, server-issued control target, and pending/unknown guards.

## Constraints and non-goals
- No Coil/dependency addition, new artwork endpoint, duplicate artwork loader/cache, second playback state owner, direct repository call from the UI, or local audio engine.
- Never display a bitmap whose cache key does not match the current confirmed song/resource identity.
- Never dispatch a control while playback is UNKNOWN, stale, disconnected, handoff-only, or otherwise rejected by `PlayerActions.isEnabled`.
- Preserve the mini-player geometry, 48dp image, title layout, tap-to-open body, and existing upward pull gesture. Clicking the control must not open the full-screen route.
- Preserve clear no-track, no-artwork, loading, failed, and unconfirmed states.

## Alternatives considered
1. Add Coil / fetch artwork independently from the mini-player: rejected because this duplicates the existing artwork cache and network path.
2. Call the playback repository directly from MiniPlayer: rejected because it bypasses the shared PlayerActions / MutationCoordinator policy.
3. Reuse the root-owned artwork cache and PlayerActions: selected because both are already the authoritative app-scoped paths and the full-screen player uses them successfully.
