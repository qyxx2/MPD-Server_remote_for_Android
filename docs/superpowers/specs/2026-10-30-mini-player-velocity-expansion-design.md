# Mini-player velocity-triggered expansion design

**Date:** 2026-10-30  
**Branch:** `task4`

## Intent
Preserve the current measured 50%-of-window distance trigger for deliberate slow pulls, while allowing a fast upward flick to enter the full-screen Player before the dragged surface reaches that distance.

## Behavior
- Keep the existing 50% measured app-window distance threshold unchanged.
- Also enter full-screen as soon as the active upward drag reaches **1000 dp/s** upward velocity. This is a deliberate-flick threshold, expressed in dp/s so it behaves consistently across screen densities.
- Evaluate velocity during pointer movement, not only after release. Navigation must begin while the finger is still down as soon as the threshold is observed.
- Only upward-dominant drags qualify. The existing vertical-dominant drag gate continues to reject horizontal-dominant movement; downward velocity is clamped to zero.
- A slow drag below 50% still returns to the mini-player on release. A slow drag that reaches 50% still expands. Tap-to-open, the expansion animation, navigation-bar motion, and full-screen downward dismissal remain unchanged.
- On release, evaluate the final measured velocity as a fallback in addition to the accumulated distance.

## Approaches
1. **Replace the distance threshold with velocity:** rejected because slow deliberate pulls would lose their current behavior.
2. **Use either the existing distance threshold or an upward-velocity threshold:** selected. It preserves the established distance contract while adding the requested fast-flick path.
3. **Check velocity only after finger release:** rejected because it cannot navigate early during the pull-up animation.

## Implementation boundaries
- Track the active pointer with Compose's `VelocityTracker`; convert its pixel velocity to dp/s using the current density.
- Keep the threshold decision in the existing pure `MiniPlayerExpansionPolicy` and cover it with unit tests.
- Extend only the mini-player expansion callbacks and their AppNavHost consumer.
- No new dependencies; no changes to playback, queue, server state, click behavior, navigation-bar opacity/translation, or the existing downward dismissal gesture.
