# Downward-dismiss navigation reveal and opacity correction — Implementation Plan

> Execute inline in the existing `task4` workspace. Follow Superpowers TDD RED→GREEN. Do not use subagents or other programming agents.

**Goal:** Preserve the accepted full-screen downward-dismiss/reverse-navigation motion, correct the actual Material 3 disabled-item dimming, and remove the rejected fast/accelerating mini-player expansion.

**Architecture:** Keep the existing measured drag offset as the shared animation source. Hide the root navigation copy while the exit transition is active and render one copy above the player. It stays non-interactive, while explicit disabled colors preserve the ordinary selected/unselected appearance.

**Spec:** [Downward-dismiss navigation reveal and opacity correction](../specs/2026-10-30-downward-dismiss-navigation-opacity-design.md); preserve [the established mini-player pull-up transition](../specs/2026-10-30-mini-player-pull-up-transition-design.md) and Task 4 contracts.

## Constraints

- Mini-player upward expansion remains distance-only and commits at 50% of the measured app-window height.
- Do not use `VelocityTracker`, velocity thresholds, or acceleration heuristics for mini-player expansion.
- Keep the full-screen downward-dismiss policy (120dp minimum and 18% window guard) unchanged.
- Navigation motion remains driven by the player drag offset; no independent animation clock.
- The transition navigation bar remains non-interactive and visually identical to the enabled root navigation bar.
- No changes to playback/network/runtime ownership, Queue semantics, app identity, dependencies, or persistent data.

## Task 1: Remove the rejected velocity expansion

- [x] Remove the velocity predicate from `MiniPlayerExpansionPolicy`.
- [x] Remove velocity tracking, threshold conversion, and the velocity callback from `MiniPlayer`.
- [x] Remove the AppNavHost early-commit velocity callback and its unit/UI tests.
- [x] Restore the existing duration values of the 30% rebound and 50% distance-threshold UI tests; the 50% distance threshold remains the only upward commit rule.

## Task 2: Prove and correct disabled-state navigation colors

- [x] Trace the exit navigation overlay through `PullUpRootNavigationOverlay` → `RootNavigationBar` → Material 3 `NavigationBarItem`.
- [x] Add a device screenshot test comparing the normal root navigation item against the moving transition item at the same near-resting position.
- [x] Run the screenshot test with Material 3 default disabled colors and observe the expected RED result: 1,862 pixel differences.
- [x] Set disabled icon/label colors to the same selected/unselected colors as enabled items while retaining `enabled = false`.
- [x] Run the same device screenshot test and observe GREEN: 0 pixel differences.
- [x] Preserve the existing held-drag test proving the navigation bar rises as the player moves down and returns to its root resting position after dismissal.

## Final verification

- [x] Run focused `FullScreenPlayerGestureTest` as part of the complete unit-test suite; all 267 unit tests pass.
- [x] Run individual device tests for below-threshold rebound, the normal 50% mini-player expansion threshold, the color-raster regression, and downward-dismiss synchronized navigation reveal; each passes 1/1.
- [x] Run `AppNavigationUiTest` (11/11), `MainActivityEdgeToEdgeUiTest` (3/3), and `Task4CrossScreenIntegrationUiTest` (1/1).
- [x] Run the complete unit tests, lint, Android-test Kotlin compilation, and Debug APK assembly with `./gradlew --offline --no-daemon --max-workers=1 testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug`.
- [ ] The full `FullScreenPlayerNavigationUiTest` class does not complete: its first `horizontalSwipesSwitchBetweenPlayerAndQueueRoutes` case crashes during Activity destruction with the known Queue `NavBackStackEntry` lifecycle exception. The relevant transition tests pass individually; the class-level crash is recorded in the report.
- [x] Run `git diff --check` and review the final diff for acceleration rollback and dismissal-only scope.
- [x] Install the replacement Debug APK without uninstalling or clearing application data; verify the package remains launchable and the DataStore remains byte-for-byte unchanged.
- [x] Record fresh verification evidence and the class-level lifecycle failure in the report.
