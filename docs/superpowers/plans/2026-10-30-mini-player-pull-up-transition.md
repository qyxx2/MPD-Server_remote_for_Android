# Mini-player pull-up expansion transition Implementation Plan

> **For agentic workers:** Implement inline in the existing `task4` workspace. Follow Superpowers TDD RED→GREEN. No subagents/other programming agents.

**Goal:** Replace the mini-player's opening-style scale transition with a finger-following pull-up surface, synchronized fully opaque bottom-navigation slide, and early-release recovery. Commit only after an upward drag reaches 50% of the measured app-window height.

**Architecture:** Keep the root shell mounted for the interactive pre-threshold drag and render a temporary full-screen Player surface above it. At the expansion threshold, hand off to the existing full-screen route at the current progress. Direct taps use the same geometry and settle animation. The existing full-screen dismissal path remains independent and unchanged.

**Tech Stack:** Existing Kotlin, Jetpack Compose, Navigation, Coroutines, and current Gradle configuration. No new dependencies.

**Spec:** [Mini-player pull-up transition design](../specs/2026-10-30-mini-player-pull-up-transition-design.md); existing Task 4 contracts and visual design remain authoritative.

## Global Constraints

- Keep the single app-scoped AppRuntime, SnapshotStore, realtime owner and MutationCoordinator.
- Do not modify playback/queue behavior or resource ownership.
- Do not change the existing full-screen-to-root dismissal animation.
- Do not use screen scaling for the new entry transition.
- Measure mini-player/navigation geometry from layout; no fixed device-pixel coordinates.
- User requested execution directly in the current `task4` workspace and no programming agents.

## Review Focus

1. Releasing/cancelling before threshold must return to the mini-player without changing the navigation route.
2. Threshold handoff must not reset reveal progress or interrupt the pointer-following surface.
3. Taps must retain the existing accessibility/click behavior and animate through the same geometry.
4. Bottom navigation must slide downward in lockstep with the expanding surface, remain fully opaque, and account for the bottom system inset.
5. The existing downward full-screen dismissal gesture must retain its previous threshold and spring/exit behavior.

## Task 1: Pull-up transition and synchronized navigation

**Files**
- Modify: `ui/shell/MiniPlayer.kt` — upward-drag callbacks and measured bounds.
- Modify: `ui/shell/RootNavigationBar.kt` — measured height and temporary disabled state for transition overlay.
- Modify: `ui/navigation/AppNavHost.kt` — interactive preview, 50% threshold handoff, direct-tap settle, and full-opacity navigation translation.
- Modify: `ui/player/FullScreenPlayerScreen.kt` — animate only entry top inset/dismiss-band geometry; preserve stable final layout.
- Create/modify unit tests for the pure expansion policy.
- Modify `FullScreenPlayerNavigationUiTest.kt` and/or `MainActivityEdgeToEdgeUiTest.kt` for drag follow, early-release recovery, threshold completion, and navigation motion.

**Steps**
- [x] Add pure policy tests for measured reveal progress and the 50%-of-window commit threshold.
- [x] Run focused unit tests RED against the old 18% policy, then GREEN with the 50% policy.
- [x] Add device instrumentation coverage for a 30% pre-threshold drag/rebound and a 50% threshold crossing; both final cases pass.
- [x] Implement mini-player drag tracking and measured geometry.
- [x] Implement preview overlay while keeping the root route alive below threshold.
- [x] Implement same-progress route handoff and direct-tap settle; remove entry scale transforms.
- [x] Synchronize navigation bar downward translation with reveal progress while retaining full opacity.
- [x] Run focused unit tests and device instrumentation; the full navigation class passed 9/9, including existing downward dismissal cases.
- [x] Run full project verification, `git diff --check`, and replacement install; DataStore hash and firstInstallTime were preserved.

## Completion evidence

Recorded commands and results in [the dated verification report](../reports/2026-10-30-mini-player-pull-up-transition-verification.md). Manual visual frame-by-frame acceptance was not performed.
