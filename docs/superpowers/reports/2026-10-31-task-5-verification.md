# Task 5 Batch 7 — Integration, Performance, Regression and Device Verification

**Date:** 2026-11-01
**Branch:** `task5`
**Starting revision:** `b8f6a7fffa4083596454ae8b88a259074c1fbb56`
**Scope:** Batch 7 only. No production feature or server-side data change was introduced.

## Changes

- Added `Task5CacheReuseTest`: three integration-style unit tests exercising the real `RuntimeReadApi` and `ResourceMemoryCache`; only the HTTP boundary is faked. They count GETs across Home/Search/Library/Album/Playlist/Favorites/Artwork, check canonical Song object reuse, exercise selective revision invalidation, and preserve 400 AutoPlay queue occurrences over a 400-song fixture without queue GETs or Song-detail N+1 requests.
- Added `Task5CrossScreenIntegrationUiTest`: launches the installed app's existing `AppRuntime`, waits for an accepted live snapshot, reads the configured MS Recent Updates (`modified_at desc`), persistent History and album summaries, and traverses Home/Search/nested Library/Settings. It verifies the configured origin, `SnapshotStore`, `ResourceMemoryCache` and `RealtimeController` remain the same owners.
- Updated legacy `AppNavigationUiTest` assertions from the removed `root-placeholder-content` shell to the actual Task 5 Home/Search/Library screens and their scroll containers. Its status-ring preference test now permits the legitimate live server sequence to advance while asserting the session epoch, connection generation, configured origin and shared activity history are not changed by local UI preferences. This is a test-contract correction; no production file changed.

## Automated verification

| Verification | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **419 tests, 0 failures, 0 errors, 0 skipped** |
| `Task5CacheReuseTest` | **3/3 passed** |
| `AppNavigationUiTest` on SM-G986N / Android 13 | **11/11 passed** |
| `Task5CrossScreenIntegrationUiTest` on SM-G986N / Android 13 | **1/1 passed**, including a second run after the overlay install |
| Full connected instrumentation (opt-in live mutation test excluded) | **104 tests, 103 passed, 1 conditional skip, 0 failures** |
| `./gradlew :app:assembleDebug :app:lintDebug` | **BUILD SUCCESSFUL** |

The single connected-test skip is `LiveMsStatusRingUiTest`; it explicitly requires the optional runner argument `-e liveOrigin <MS-origin>` and calls `assumeTrue` when that argument is absent. The new Task 5 device test independently used the already-saved app origin and successfully performed live GET-only checks.

`LiveMsMutationCoordinatorTest` was excluded from the connected suite because it creates and deletes a real playlist on the configured MS. That write path is opt-in and not required for this read-only acceptance. No playback, queue, playlist, favorites, library-scan or server-configuration mutation was sent during Batch 7.

### Cache and state evidence

- Repeated Home Recent Updates and History reads, Search results, Library title-sorted songs, Album summaries/members, Playlist summary/detail/members, Favorites members/collection and Song artwork are each fetched once per cache identity/key in the counted test.
- Search and Album members resolve to the same interned Song entity as Home; resolving 400 Songs after the 400-song list does not issue 400 detail GETs.
- The 400-item AutoPlay fixture preserves all 400 `queue_item_id` occurrences, including repeated Song IDs; AutoPlay projection makes no `/api/playback/queue` request. History is read through `/api/history`, not `/api/history/played`.
- Library and playlist revision changes invalidate their declared dependent resources without invalidating unrelated History data.
- The 400-song test asserts metadata retention remains at or below 16 MiB and raw artwork retention at or below 24 MiB. Existing cache tests cover the 512-entry limit, 8 MiB per raw image, the shared three-request concurrency ceiling, stale/session-race protection, disconnect/reconnect semantics and the 60-second History TTL.
- The 40 MiB decoded-thumbnail limit applies to the shared 160 px Queue/Player thumbnail cache. Home's separate 768 px high-resolution artwork cache retains its Batch 3 128 MiB policy; these are distinct caches and the Home limit was not reduced to satisfy a Queue-thumbnail budget.

## Live MS and device acceptance

Device: **SM-G986N, Android 13**. The app's preconfigured MS address was read from existing preferences and left unchanged. The real-device integration test observed a fresh accepted snapshot and successful GET responses for Recent Updates, persistent History and album summaries, with the app still using its existing runtime owners.

Visual captures are stored locally under `.superpowers/sdd/2026-10-31-task-5-implementation/evidence/`:

- `home-portrait-default.png`: portrait 1080×2400, density 420, font scale 0.9.
- `home-short-large-font.png`: short portrait 1080×1800, font scale 1.3. All three Home rails remain visible, artwork-led horizontal lists remain separate, and the mini-player plus bottom navigation remain present without large vertical gaps.
- `home-portrait-restored.png`: captured after restoring the original display configuration.

After the short-height check, display size, density and font scale were restored to **1080×2400 / 420 / 0.9**. The DataStore preferences file SHA-256 remained `e34b2c892eaf766fb5550388c541d2406d898e58ee8a0169dbd146f97b01d5a1`.

The debug APK identity remained `io.github.qyxx2.mpdserver.remote`, versionCode `1`, versionName `0.1.0`. The built and installed APK signing certificate SHA-256 both equal `36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`.

The final APK was installed with **`adb install -r`**. No uninstall or `pm clear` was performed. Install output was `Success`; `firstInstallTime` remained `2026-10-24 18:47:26`, while `lastUpdateTime` advanced from `2026-11-01 10:36:07` to `2026-11-01 10:44:06`. The DataStore SHA-256 before and after was identical. The live read-only Task 5 integration test passed again after this overlay installation, confirming the configured MS connection and persisted app settings were reusable.

The connected regression suite includes the Home rail reset, Home album navigation (including one-track cards), Library album detail, Search, Playlist/Favorites, mini-player, Player, Queue reorder/Played reveal, full-screen transition and status-ring tests. Their passing results are included in the full connected run; the separate visual device check focused on default portrait and short-height/large-font layout.

## Architecture and lint audit

- Production construction remains in the existing `AppRuntime` boundary: no new runtime, SnapshotStore, RealtimeController, MutationCoordinator, OkHttpClient or resource-cache owner was introduced.
- No Room/SQLite business store, direct MPD protocol path, local Queue/History/AutoPlay authority, local server-owned sorting/randomization or direct UI mutation transport was found in the inspected Android production package.
- Lint completed with **0 errors, 16 warnings and 4 hints**. Warnings are dependency-update suggestions (3), screen-configuration API suggestions (2), Modifier parameter ordering (8), debug HTTP base configuration (1, retained for the configured HTTP MS endpoint), and KTX suggestions (2). The four hints recommend primitive Compose state holders. None blocked build/lint.
- Gradle emitted its existing deprecation notice for Gradle 9 compatibility and the experimental AAPT2 override notice; neither caused task failure.

## Task 5 contract-matrix evidence from Batch 7

| Matrix row | Evidence |
|---|---|
| T5-33 — Search/Library/Home cache integration | Counted GET assertions and canonical Song/artwork identity in `Task5CacheReuseTest`; selective revision checks plus the existing session/freshness/TTL tests. |
| T5-34 — Full Task integration | `Task5CrossScreenIntegrationUiTest`, the updated 11-case `AppNavigationUiTest`, the full 104-case connected run, and Task 4 Player/Queue/mini-player regressions. |
| T5-35 — No backend/local authority drift | Production source/dependency audit and the runtime identity assertions in the live device integration test. |
| T5-38 — Home album cards use the shared album detail route | Passing `HomeScreenUiTest.everyAlbumLinkedHomeCardOpensItsServerAlbumEvenWhenProjectedAsSingleSong`, `LibraryRoutesTest.albumEntryPointsShareOneCanonicalRouteAndPreserveOpaqueServerIds`, and album-detail UI tests in the full connected/unit suites. |

Rows T5-00…T5-32 remain supported by the already-completed Batch 0–6 ledger entries and their retained tests; Batch 7 adds the cross-screen integration proof above. No matrix row was satisfied by sending a write to the live MS.

## Batch 7 outcome

Batch 7 integration, regression, cache-budget and device gates passed. The one opt-in live status-ring test and the explicitly mutating live playlist test are documented exclusions; neither was silently treated as a pass. Only the local `task5` branch is changed. No push, merge or remote branch update was performed.
