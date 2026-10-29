# Task 4 Queue Performance Correction — 2026-10-29

## Scope
Only the Queue rendering/resource-loading performance issue on the existing `task4` branch. No Task 4 batch was started or reworked.

## Cause
The Queue used a small viewport-only metadata cache (48 entries). Each scroll updated the cache to only currently visible song IDs and cancelled in-flight reads as soon as rows left the viewport. Reopening Queue disposed that cache, so metadata and artwork were requested/decoded again. Row artwork decoding also happened as rows composed, causing extra work during fast scrolling.

## Changes
- Added `QueuePrefetchWindow`: stage 100 rows initially and extend by another 100 once the visible index approaches within 20 rows of the current window. This is client-side prefetch over the canonical snapshot, not API pagination.
- Moved Queue song metadata caching to the app navigation scope so rows retain the cache across Queue route disposal/re-entry. Expanded its bound to 512 entries and the default request concurrency to 5.
- Let queued read-only resource requests complete when rows leave the viewport instead of cancelling/restarting them on every scroll. Server/session/resource identity changes still invalidate in-flight work and retained entries.
- Separated artwork bytes from the metadata budget, and added a shared, bounded 160px decoded-thumbnail LRU (40 MiB) with background decode concurrency limited to two. Artwork decode is scheduled before the compressed-byte budget can evict a response.
- Kept all queue/playback facts and operations on the existing Runtime/snapshot/API/mutation owners. No new API, database, transport, dependency, or client-side playback/queue state was added.

## Verification
- Added prefetch-window unit tests (3) for initial 100-item staging, next-batch threshold, and end-of-list boundaries.
- Extended `QueueSongCacheTest` to cover completed metadata retention after rows leave the viewport, retention of 400 unique songs, no repeat read on route re-entry, and preserving metadata while trimming artwork bytes.
- Focused unit tests: `QueuePrefetchWindowTest` 3/3 and `QueueSongCacheTest` 9/9 passed.
- `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL.
- Device `QueueScreenUiTest`: 8/8 passed on SM-G986N / Android 13.
- A broader connected instrumentation run finished 62 tests with four failures in unrelated `AppNavigationUiTest` assertions (root placeholder text/controls/settings visibility); no QueueScreenUiTest assertion failed. Do not treat that full instrumentation run as green.
- `git diff --check` passed.
- Debug APK built and installed with `adb install -r`. The DataStore file hash immediately before and after that overlay install was identical (`6ee8c94b39d783a81f815341687ce605860d29e8d96948535d9a775413e26822`), so the install itself did not reset the post-test data.

## Live-device limitation / data safety note
After the broad instrumentation run, the app's Activity sheet showed `连接：未连接` and `快照：未确认`; therefore a real server-backed 400-row scrolling smoke test could not be established. The existing `AppNavigationUiTest.serverOriginSettingsRejectsInvalidInputAndAppliesValidAddressToSharedRuntime` test unconditionally saves `ServerOrigin.DEFAULT_ADDRESS` in its `finally` block, so that test run may have overwritten the user's previously configured MS address. The attempted direct preference restoration was blocked by the environment safety checks; no address was guessed or written. The installed app should not be considered live-server-verified until the original address is restored.

## Files
Production changes are limited to:
- `ui/navigation/AppNavHost.kt`
- `ui/queue/QueueScreen.kt`
- `ui/queue/QueueOccurrenceRow.kt`
- `ui/queue/QueueSongCache.kt`
- new `ui/queue/QueuePrefetchWindow.kt`
- new `ui/queue/QueueArtworkThumbnailCache.kt`

Tests:
- `QueueSongCacheTest.kt`
- new `QueuePrefetchWindowTest.kt`

No commit, push, or merge was performed. No playback/Queue mutation was sent.
