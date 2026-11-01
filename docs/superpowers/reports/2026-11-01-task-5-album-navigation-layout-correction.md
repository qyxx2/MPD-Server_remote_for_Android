# Task 5 Batch 5 correction — album navigation and shared detail layout

## Scope

Applied only the requested album navigation and non-fullscreen album detail correction on the existing `task5` branch.

## Behavior

- Every Home card with a nonblank server `album_id` opens `library/albums/{album_id}`, independent of card projection kind and album track count. This includes one-track albums projected as `SINGLE_SONG`, history cards, and AutoPlay occurrences.
- Home cards without an album ID retain the existing single-song action menu. AutoPlay's dedicated play affordance remains an independent exact `queue_item_id` action; it is not replaced by album navigation.
- Home, Search, Library, and the full-screen Player's album-name link converge on the same Album detail destination. The full-screen Player presentation itself is unchanged.
- Album detail follows the existing Web `CollectionView.vue` layout: 104dp contain-scaled cover, album title/artist/year/song count, centered Play All/Shuffle, playable-song count, and text-only title/artist track rows. The page retains Android swipe-back and system-back behavior without adding a visible Back button.

## State and API boundaries

Album summary/member data and cover bytes use the existing `AppRuntime.reads` GET APIs and shared resource cache. Decoded large cover art reuses `HomeArtworkMemoryCache`, including visible-item retention. Album play and shuffle continue through the existing server-owned collection action adapter; no local album queue or playback authority was introduced. No backend files or APIs were changed.

## Verification

- `:app:testDebugUnitTest`: PASS, 397 tests.
- `:app:compileDebugAndroidTestKotlin`: PASS.
- Targeted `:app:connectedDebugAndroidTest` on SM-G986N / Android 13: PASS, 27 tests, 0 failures, 0 skipped. Coverage included Album detail metadata/actions/rows, Home album-linked card navigation and no-album fallback, Library, Search, and Player navigation.
- The configured-MS read-only Library test passed and verified the configured server origin remained unchanged.
- `git diff --check`: PASS.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: PASS. Existing app data was preserved; no uninstall or app-data clear was performed.

## Repository status

Changes remain local on `task5`. No commit, push, merge, branch switch, or MS server mutation was performed.
