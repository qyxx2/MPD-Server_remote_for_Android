# Task 5 Batch 6 — Playlist and Favorites verification

## Scope

- Repository: `MS_remote_for_Android`
- Branch: `task5`
- Starting commit: `4377d0e`
- Scope completed: Batch 6 only. Batch 7 was not started.
- No other coding agents were queried or used.

## Implementation

- Added `PlaylistActions` for create, rename, delete, member add/remove and complete-order replacement, backed by the existing shared `PlaybackMutationGateway` and application-owned `MutationCoordinator`.
- Added `FavoritesActions` for server membership reads and PUT/DELETE toggles, using the same gateway and coordinator.
- Playlist editing requires a fresh list, detail and member read. The listed playlist, detail and ordered `/songs` IDs are cross-checked; stale, missing or inconsistent data disables edits.
- System playlists disallow rename/delete only. Add/remove/reorder remain available.
- Reorder validates and submits the complete member ID sequence, not the visible list window.
- Unknown mutation retry reuses the original coordinator intent and idempotency identity. Server 404/409 failures are retained; no client-side duplicate-add rejection hides the server's canonical conflict.
- Favorites membership is observed once from the shared resource cache. Favorite song metadata resolves through the existing canonical Song cache; unavailable IDs remain visible placeholders and can still be unfavorited.
- Post-mutation reads explicitly invalidate and reload the corresponding shared cache entries. Only clean SUCCESS proceeds as success; failed or inconsistent readback records a synchronization error and leaves writes blocked. Partial readback still refreshes the visible server-backed state.
- The shell owns the playlist/favorite adapters and shared membership projection for Home, Search and Library song menus. No per-row favorites request or client-owned membership state was introduced.

## Verification

| Check | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` | PASS |
| Unit tests | 416 passed; 0 failures, 0 errors, 0 skipped |
| Targeted connected test `PlaylistFavoritesUiTest` | PASS — 3/3 on SM-G986N, Android 13 |
| `git diff --check` | PASS |
| Debug APK overlay install with `adb install -r` | PASS |
| Persisted preferences before/after overlay install | DataStore preferences file checksum unchanged |

The unit coverage includes exact method/path/body contracts, system playlist policy, duplicate-add and reorder conflict preservation, full-order validation, stale-resource and generation guards, UNKNOWN retry identity, clean-success/readback rules, failed partial readback, and forced cache invalidation. The device UI tests cover the unavailable favorite placeholder, the enabled unfavorite action while playback actions remain disabled, and the add-favorite action.

## Safety and state preservation

- No live playlist/favorite mutations were sent to the MS server. Mutation tests use isolated fake coordinators; connected tests use Compose fixtures.
- No app data clear, uninstall or factory reset was run.
- The installed app was replaced using `adb install -r`; the persisted DataStore preference file remained byte-for-byte unchanged.
- Existing server-origin configuration was not edited.

## Local commit

- Commit: `8e62c39 feat(library): add playlist and favorites management` on `task5`.
- Post-commit worktree: clean. No push or merge.
