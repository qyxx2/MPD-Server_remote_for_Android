# Full-screen downward dismissal: navigation reveal and opacity correction

**Date:** 2026-10-30
**Branch:** `task4`

## Intent

Keep the established mini-player entry behavior unchanged: upward drag follows the finger and commits at 50% of the measured app-window height. Do not introduce velocity- or acceleration-based early expansion. Preserve the accepted full-screen downward-dismiss gesture and its reverse navigation-bar reveal, while ensuring the navigation bar retains its normal visual strength during the entire transition.

## User-visible behavior

- Mini-player upward drag continues to use the existing 50% measured-window threshold. A fast or accelerating swipe does not bypass that threshold.
- During full-screen downward dismissal, the player follows the finger and the root navigation bar rises from below the window in synchrony with the same drag offset.
- The navigation bar keeps its regular surface, selected, and unselected colors throughout the transition. Neither its container nor its icon/text content is faded, masked, dimmed, or blurred.
- Cancelling a short downward gesture restores the player and moves the navigation bar below the window again.
- Completing dismissal ends at the root navigation bar's normal resting position.

## Root-cause findings

The earlier implementation correctly moved the exit copy of the navigation bar outside `RootBackdropShell`, whose `blurRadiusDp` modifier wraps the full root `AppShell`. That alone did not preserve the navigation bar's appearance.

The direct opacity cause was one level further down:

1. `PullUpRootNavigationOverlay` rendered `RootNavigationBar(interactionEnabled = false)` so the moving bar could not navigate during the gesture.
2. `RootNavigationBar` forwarded this value to `NavigationBarItem(enabled = false)`.
3. Material 3's default `NavigationBarItemColors` uses disabled icon and label colors with alpha `0.38f`. The disabled overlay therefore dimmed its icons and labels even though no alpha modifier was applied to the bar itself.

A device screenshot regression test compared the regular root navigation item with the transition copy at the same visual position. With Material 3's default disabled colors it detected 1,862 differing pixels. Supplying disabled colors equal to the corresponding selected/unselected normal colors made the raster identical (0 differing pixels). This isolates the visual change to the disabled-state color policy rather than backdrop blur or a parent alpha layer.

## Implementation choices

- Retain the existing single drag offset `playerDragOffsetPx` as the source of truth for the player and navigation-bar motion.
- While a downward dismissal is active, replace the blurred root-backdrop navigation bar with a spacer and render one transition copy above the player surface.
- Keep transition items non-interactive (`enabled = false`) to prevent navigation during the gesture.
- Explicitly set `disabledIconColor` and `disabledTextColor` to the same selected/unselected colors used by enabled items. This avoids Material 3's default disabled alpha without enabling interaction.
- Keep `NavigationBar`'s surface color and all existing position/inset geometry unchanged.
- Keep the existing downward-dismiss threshold and cancel/commit animations unchanged.
- Do not add velocity tracking or early-commit behavior to `MiniPlayer`.

## Scope boundaries

- No changes to MPD-Server, HTTP/WebSocket, playback, queue semantics, persistence, server address, app identity, or dependencies.
- No changes to the mini-player upward 50% commit threshold, full-screen Player layout, or full-screen downward-dismiss distance policy.
- Work only on the existing `task4` workspace; no other agents or unrelated refactors.
