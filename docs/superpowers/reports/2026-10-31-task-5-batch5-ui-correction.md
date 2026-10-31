# Task 5 Batch 5 UI correction

## Scope

Applied the requested UI-only correction on the existing `task5` branch.

- Replaced the standalone three-dot affordance with a full-surface song action target. A tap anywhere on a normal single-song row or Home single-song card opens the same existing Play Now / Play Next / Add to Queue menu. The shared row component carries this behavior to Search and Music Library. The popup remains anchored near the trailing edge and uses 90% content opacity.
- Removed the Home Recent Updates and Playback History Play All / Shuffle controls.
- Centered the remaining shared collection Play All / Shuffle pair as a group and added compact play and shuffle glyphs consistent with the Material-based UI.
- Kept the AutoPlay occurrence-specific play affordance unchanged. AutoPlay represents a specific server queue occurrence, so its existing exact-queue-item action remains distinct from normal single-song menu actions.

## State and API boundaries

No playback endpoint, mutation coordinator, queue ownership, resource freshness check, server-authoritative state flow, or payload contract was changed. Normal song actions continue through the shared `SongActions` adapter. AutoPlay continues to use its queue-item identity rather than falling back to a song ID.

## Verification

- `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`: PASS.
- Targeted instrumentation on SM-G986N / Android 13 (`SongActionMenuUiTest` and `HomeScreenUiTest`): 10 tests, 0 failures.
- `:app:testDebugUnitTest`: 394 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug`: PASS.
- `git diff --check`: PASS.
- Source audit found no remaining three-dot overflow affordance in the Home, Search, or Library UI. Remaining visible Play All / Shuffle labels are confined to the shared collection control.

## Device install and data safety

Installed the debug APK over the existing package using `adb install -r`. No uninstall, package-data clear, server-origin edit, playback command, or queue mutation was performed. The reinstall path preserves the application's existing data.

## Repository status

Changes remain local on `task5`; no commit, push, merge, or branch switch was performed.
