# Mini-player pull-up expansion transition design

**Date:** 2026-10-30
**Branch:** `task4`

## Intent

Replace the mini-player-to-full-screen entry motion with a continuous pull-up transition. The mini-player is treated as the handle/surface being pulled out from the bottom navigation area; this must not look like a small full-screen window scaling open.

## User-visible behavior

- Pressing and dragging upward on the mini-player reveals the full-screen Player from its measured top edge. The Player layer's top follows the finger without scaling the whole screen.
- The bottom navigation bar travels downward behind the screen edge at full opacity. It does not fade; the Player fills the space it vacates.
- Releasing before the expansion threshold springs the Player surface and navigation bar back to their original positions.
- Crossing 50% of the measured app-window height commits to the full-screen destination and continues the same motion to completion without a discontinuity.
- A direct tap follows the same pull-up geometry and settles into the full-screen Player.
- The existing full-screen-to-root dismissal/return gesture and animation are not changed.

## Approaches

1. **Scale the full-screen page from the bottom (existing approach):** rejected. It reads as an opening/zoom animation instead of extracting the mini-player.
2. **Translate a full-height Player surface from the measured mini-player top, while the fully opaque bottom navigation slides down:** selected. This gives the top edge a direct relationship to the drag and lets the Player surface continuously occupy the space left by navigation.
3. **Rebuild the full-screen page as a separately resizing layout:** rejected for this change. It would introduce a second layout path and risk disturbing the stable full-screen Player layout/anchors.

## Implementation choices

- Measure the actual mini-player top and root navigation height from Compose layout; do not guess screen coordinates from fixed device pixels.
- Use upward drag distance to derive a clamped reveal fraction. Expand when the drag reaches `50% of the measured app window height`; before that, release/cancel restores the mini-player.
- Keep the root screen mounted during the pre-threshold drag so the active pointer gesture remains owned by the mini-player. At threshold, hand off to the full-screen route at the current reveal fraction.
- Translate the full-height Player layer from the mini-player top to the window top; do not scale its X/Y dimensions.
- Move the bottom navigation downward by its measured height plus bottom safe inset, keeping its alpha at 1 throughout the transition.
- For a direct tap, use the same measured geometry and a 250ms settle.
- Preserve the existing Player layout once fully expanded; only the top safe inset/dismiss band grows into its normal full-screen size during entry.

## Scope boundaries

- No changes to playback, queue, backend/network ownership, artwork caching semantics, or navigation destinations.
- No changes to the existing downward dismissal/return animation.
- No new dependencies or persistent state.
