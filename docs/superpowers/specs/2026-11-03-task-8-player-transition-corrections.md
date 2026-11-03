# Task 8 Player Transition Corrections — Behavioral Supplement

**Date:** 2026-11-03
**Branch:** feature/task-8-player-interaction-polish
**Applies to:** Task 8 Batch 4 artwork/mini-player transition only. This supplement corrects the interaction details observed after device installation; it does not authorize Batch 5 popup work.

## Required behavior

1. **Mini-player pull-up remains pointer-driven until release.** While the pointer is pressed, the extracted Player sheet must remain at the progress derived from the current drag distance. Crossing the 50%-of-window commit threshold or reaching the fling-velocity threshold must not navigate, start the 250ms settle, or stop following the finger before release. On pointer release, apply the existing distance/velocity commit policy: commit and settle to full screen when eligible; otherwise restore the mini-player. Direct tap remains unchanged.
2. **Dismissal backdrop is edge-to-edge.** During downward dismissal, the root dark scrim must cover the complete app window canvas, including the status-bar inset and navigation-bar inset. Foreground root content may keep safe-inset padding; the scrim must not inherit that padding. Scrim opacity continues to follow existing dismissal progress.
3. **Entry artwork ownership has no one-frame reappearance.** Once an identity-matching mini-player artwork and source rectangle are known and expansion starts, the transition overlay must render at the exact source rectangle even while the full-screen target rectangle is not yet measured. The stationary mini-player image remains hidden while the overlay owns it. When the destination measurement becomes available, the overlay begins interpolating toward it without a frame jump. Never use a stale artwork cache key.
4. **Hidden full-screen artwork does not leave a fixed placeholder.** When the full-screen ArtworkStage is hidden for a shared-element handoff, its background/surface placeholder must also be hidden. The transition overlay is the sole visible owner of the cover image.
5. **Dismissal uses a dedicated artwork shrink trajectory, not a reversed entry animation.** During a downward Player dismissal, an identity-checked overlay moves the current artwork from the stable full-screen ArtworkStage rectangle to the measured mini-player artwork rectangle. The full-screen stage and stationary mini-player image are hidden while the overlay owns the art. The sheet and backdrop continue to follow raw finger drag progress. The artwork has its own normalized trajectory: horizontal center progress is `p^1.25` (delayed horizontal convergence), vertical center progress is `p * (2 - p)` (early downward travel), and width/height progress is `p * (1.7 - 0.7p)` (early visible shrink). This matches the supplied three-frame reference: cover remains near center horizontally while shrinking and moving down, then converges precisely to the mini-player bounds at progress 1. Do not use the entry transition's linear rectangle interpolation for dismissal. If the mini-player target measurement is not ready, keep the overlay anchored at the full-screen source rectangle until it is measured. On route pop, hand off to the normal mini-player image without a visible jump.

## Invariants

- Preserve the existing 50% distance and upward-fling thresholds as *release-time* commit rules.
- Preserve the existing 250ms settle animation after the pointer is released and the gesture is accepted.
- Preserve direct tap behavior and early-release cancellation.
- The artwork overlay consumes only the already-loaded bitmap whose PlayerArtworkCacheKey matches the current confirmed artwork identity. No new loader, request, playback owner or cache owner is introduced.
- Use measured screen/root-coordinate bounds; do not hard-code device dimensions.
- No server, network, queue, database, media-session, navigation-stack, or playback-state authority changes.
- No changes to Task 8 Batch 5 popup/dialog work.
