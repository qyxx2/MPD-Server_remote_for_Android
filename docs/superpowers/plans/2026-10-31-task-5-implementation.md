# Task 5 Implementation Plan

> **Superpowers execution plan.** Follow the Batch sequence and test-first steps below. Checkboxes are the implementation record. Do not start the next Batch until the current Batch has passed its tests, review, and local commit.
>
> **This document decomposes implementation only.** It does not authorize implementation in this planning step.

**Goal:** Deliver Home, Search, Library, Playlist, and Favorites in native Compose, using existing MPD-Server APIs and one AppRuntime-owned in-memory resource cache.

**Architecture:** MPD-Server remains the only business-state authority. The existing AppRuntime owns one RuntimeReadApi, SnapshotStore, MutationCoordinator, and ResourceMemoryCache. Screens render typed GET resources and the canonical snapshot. Queue/Player reuse the shared Song and artwork resource owners. No page owns its own HTTP client, cache, snapshot, Queue/History copy, or mutation coordinator.

**Stack:** Use the Kotlin, Compose, Coroutines, OkHttp, kotlinx.serialization, and Navigation Compose versions already frozen in the repository. Do not add dependencies unless a concrete gap is recorded and reviewed within the owning Batch.

**Authority:**
- ../specs/2026-10-31-task-5-home-library-search-contract.md
- ../specs/2026-10-31-task-5-contract-matrix.md
- ../specs/2026-10-23-android-backend-api-contract.md
- ../specs/2026-10-23-android-web-behavior-contract.md
- ../specs/2026-10-23-android-client-boundary-and-architecture.md
- ../specs/2026-10-25-task-3-visual-design-specification.md

## Global constraints

1. **Single authority:** MPD-Server is authoritative for songs, file availability, metadata, History, Queue, AutoPlay, playback context, search, collections, playlists, favorites, sorting, and randomization.
2. **One runtime:** retain exactly one AppRuntime, RealtimeController, SnapshotStore, MutationCoordinator, RuntimeReadApi/OkHttp owner, and ResourceMemoryCache. Pages must not construct any of them.
3. **No local business database:** no Room/SQLite/disk cache, no durable copy of song/library/queue/history/playlist/favorites state, no client-side AutoPlay, no local playback engine, no direct MPD TCP.
4. **Canonical snapshot:** Queue/Playback state comes only from SnapshotStore. ResourceMemoryCache stores GET resources, never another FullStateSnapshot or competing playback/queue state.
5. **Cache identity:** entries bind to origin, connection generation, epoch, and their dependency watermark. A stale/disconnected entry may be displayed as last-known but cannot enable writes. Reconnection alone does not promote old resource data to fresh.
6. **Mutation:** every write goes through the existing MutationCoordinator. HTTP receipt is not final state. Only matching activity SUCCESS with syncError == null allows forced resource readback and fresh UI state. Unknown retry reuses the same intent ID, method, path, payload, origin, and idempotency key.
7. **Queue identity:** any operation against an existing Queue occurrence uses queue_item_id. Duplicate song_id values are not interchangeable.
8. **Home sources:** Recent Updates = server songs sorted modified_at desc; History = persistent GET /api/history, never /api/history/played; Recommendations = accepted snapshot AutoPlay occurrences with matching current playback_context_id. No Queue GET or local recommendation algorithm.
9. **Home AutoPlay:** require playback.autoplay_enabled and non-null playback.playback_context_id; include only pending AUTOPLAY items whose playback_context_id exactly equals that current ID. A null/mismatched occurrence context is excluded. Every matching occurrence remains a separate card.
10. **Library order:** Songs, Playlists, Artists, Albums, Genres, Years, Tags, Favorites. Only Songs moves; all other seven preserve relative order.
11. **Server sorting:** every Songs ordering uses GET query parameters title/modified_at and asc/desc. No client-side sort of Song DTOs.
12. **Home controls:** Recent Updates and History expose Play All/Shuffle under Task 3 collection rules. AutoPlay is an existing queue projection and does not expose collection Play All/Shuffle.
13. **Navigation:** four root tabs remain Home/Search/Library/Settings. Home/Search/Library share the mini-player; Settings hides it. Nested Library routes still select Library root.
14. **No live writes during smoke tests:** use fixtures/instrumentation or an isolated test server for write-path tests. Real configured MS smoke tests are read-only.
15. **Local work only:** build, tests, APK and files stay in /home/Gold/WorkSpace/MS_remote_for_Android. No CI/remote build, no signing-secret changes, no push/merge unless separately instructed.
16. **Regression:** Task 4 full-screen Player, Queue, drag/reorder, Played, mini-player artwork/control and status-ring behavior must remain intact.

## Critical review focus

- **Playback wire shape:** current PlaybackStateDto uses legacy context/position keys, while the actual snapshot/API uses playback_context_id/position_seconds. B0 must correct it and regress PlayerActions/Handoff before Home consumes it.
- **History vs Queue Played:** /api/history is persistent History. /api/history/played is a projection of Queue items with position < 0. They must never be substituted for each other.
- **Orphan History:** if a historical song no longer exists in the current Library, retain its History card as non-playable; do not drop it and do not issue an unbounded per-event Song GET.
- **AutoPlay occurrence:** if two pending items share song_id or album_id, show two cards and preserve both queue_item_id values.
- **Cache revision isolation:** library revision invalidates Library and Playlist-dependent entries; playlist revision invalidates Playlist-dependent entries only; History fingerprint invalidates History only. Revisions must not all be folded into one global identity.
- **Late response:** origin/generation/epoch or dependency watermark changes while a GET is in flight; the old result must never repopulate the new cache.
- **Artwork:** ARTWORK_NOT_FOUND is a missing-cover state; SONG_NOT_FOUND, arbitrary 404, 500, transport error, malformed image and oversize response remain distinct. Enforce the 8 MiB limit while streaming, not after loading the full response into memory.
- **System playlists:** verified server/Web behavior hides rename/delete only. Add/remove/reorder remains available and server-authorized; do not invent a broader restriction.
- **Small screen:** three Home sections must all remain visible. Reduce square cover size and spacing instead of allowing a vertical scroll or pushing the third section below the viewport.

## File and interface map

Package root: app/src/main/java/io/github/qyxx2/mpdserver/remote

| Area | Planned files | Responsibility |
|---|---|---|
| DTOs | protocol/dto/LibraryDto.kt; create HistoryDto.kt, SearchDto.kt, CollectionDto.kt, FavoritesDto.kt; modify PlaybackDto.kt | Strict wire DTOs; canonical playback context/position fields |
| Read API | data/LibraryApiPaths.kt; runtime/RuntimeReadApi.kt | Typed GET methods and safely encoded paths/query; keep existing runtime HTTP owner |
| Cache | runtime/ResourceCacheModels.kt; runtime/ResourceMemoryCache.kt; runtime/AppRuntime.kt | Single-flight resource reads, dependency stamps, LRU/weights and app-scoped ownership |
| Bounded images | network/HttpTransport.kt; runtime/RuntimeReadApi.kt | Streaming body limit for artwork on the existing OkHttpClient |
| Existing cache adapters | ui/queue/QueueSongCache.kt; QueueScreen.kt; QueueArtworkThumbnailCache.kt; ui/player/PlayerArtworkMemoryCache.kt | Queue/Player use shared Song/artwork bytes, retain presentation-only bitmap caches |
| Home | ui/home/HomeModels.kt; HomeProjection.kt; HomeDataController.kt; HomeScreen.kt; HomeSection.kt; HomeArtworkCard.kt; HomeCollectionActions.kt | Home data/state/projection and three horizontal artwork rails |
| Library | ui/library/LibraryRoutes.kt; LibraryScreen.kt; LibraryCategoryScreen.kt; LibraryCollectionScreen.kt; LibrarySongRow.kt; SongActionMenu.kt; SongActions.kt; LibraryCollectionActions.kt; PlaylistScreen.kt; FavoritesScreen.kt; PlaylistActions.kt; FavoritesActions.kt | Eight category routes, read pages, song/collection actions and playlist/favorite management |
| Search | ui/search/SearchController.kt; SearchProjection.kt; SearchScreen.kt | Search query lifecycle and server result projection |
| Navigation | ui/navigation/AppNavHost.kt; AppDestination.kt; ui/shell/RootNavigationBar.kt | Root-route resolver, nested routes, shared Home scroll holder and both normal/blurred root render paths |
| Unit tests | Matching package trees under app/src/test/java/io/github/qyxx2/mpdserver/remote | DTO, paths, cache, data projections, action payloads and race tests |
| Instrumentation | Matching package trees under app/src/androidTest/java/io/github/qyxx2/mpdserver/remote | Home/Library/Search/Playlist UI, navigation and 400-item regression |
| Report | docs/superpowers/reports/2026-10-31-task-5-verification.md | Final evidence for all T5 matrix rows |

### Cross-Batch interfaces

Keep these stable or update the plan and contract matrix in the same Batch before proceeding.

**B0 typed read surface (read-only):**
- RuntimeReadApi.getSongs(sortBy, sortOrder): ApiResult<SongListDto>
- Existing getSong(songId): ApiResult<SongDto>
- getAlbums/getArtists/getGenres/getYears/getTags(): typed list DTOs
- getAlbumSongs/getArtistSongs/getGenreSongs/getYearSongs/getTagSongs(): SongListDto
- getHistory(): HistoryListDto from /api/history (persistent)
- Existing getPlayed(): QueueListDto from /api/history/played (Queue-only; preserve)
- getSearchResults(query): SearchResultsDto
- getPlaylist/getPlaylistSongs/getPlaylists(): Playlist DTOs; preserve the existing list path
- getFavoriteMembers/getFavorites(): FavoriteMembersDto/CollectionDto
- Existing getArtwork(songId): HttpResult with missing/error distinction and bounded body

**B1 cache surface:**
- ResourceSessionIdentity(originAddress, connectionGeneration, epoch)
- ResourceWatermarks(libraryRevision, playlistRevision, historyFingerprint)
- ResourceCacheKey(method, canonicalPathAndQuery, responseTypeKey); query and response DTO type are part of identity
- ResourceDependency.SESSION / LIBRARY / PLAYLIST / HISTORY
- ResourceMemoryCache.readResource<T>(key, dependency, loader): ApiResult<T>, cache only Success
- ResourceMemoryCache.observeResource<T>(key): StateFlow<ResourceEntryState<T>>
- internSongs(songs) normalizes all SongListDto items to one Song entity per non-null song_id; readSong checks this entity index before GET
- readArtwork(ArtworkCacheKey, loader), updateIdentity(session, watermarks), clear(), close()
- AppRuntime.resources is one stable instance; close it before cancelling the runtime scope

**B2 Home surface:**
- HomeUiState(recentlyUpdated, playbackHistory, autoplay), each an independent HomeSectionState
- Home card identity: album_id for grouped album cards; song_id/file_uri for singleton library cards; history_id for orphan events; queue_item_id for every AutoPlay occurrence
- HomeProjection.recentlyUpdated(songs), playbackHistory(history, songIndex), autoplay(snapshot, songIndex) are pure functions preserving source order
- HomeDataController(runtime) loads Songs and persistent History concurrently; AutoPlay projects only from SnapshotStore

**B5/B6 mutation surface:**
- Actions build immutable MutationIntent and submit only through runtime.mutations.submit(intent)
- New user intent = new ID/key. Unknown retry = runtime.mutations.retryUnknown(existingIntentId)
- Queue occurrence action validates current epoch/generation/queue revision/context and uses queue_item_id
- Resource readback only after matching SUCCESS and syncError == null

---

## Batch 0 — Typed DTOs and existing API read surface

**Purpose:** close the typed DTO/API gap before screens and cache are built. Read-only, no page UI and no new mutation behavior.

**Files:**
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/LibraryDto.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/PlaybackDto.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerActions.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerPresentationHandoff.kt
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/HistoryDto.kt
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/SearchDto.kt
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/CollectionDto.kt
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/FavoritesDto.kt
- Create: app/src/main/java/io/github/qyxx2/mpdserver/remote/data/LibraryApiPaths.kt
- Modify: app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/RuntimeReadApi.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/protocol/dto/LibraryResourceDtoTest.kt, HistoryDtoTest.kt, SearchDtoTest.kt, CollectionResourceDtoTest.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/PlaybackDtoTest.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/RuntimeReadApiTest.kt

- [x] **Step 1 — Test first:** add failing DTO fixtures for SongList, Album/Artist/Genre/Year/Tag summaries, HistoryList/HistoryEvent, SearchResults/SearchGroup, FavoriteMembers and CollectionResponse.
  - SongList: items/count. Song retains nullable song_id, file_uri, album_id, availability and artwork.
  - Album: album_id/title/album_artists/year/date/song_count/artwork.
  - Artist/Genre/Tag: ID/name/song_count. Year: source_id/value/song_count with nullable value.
  - History event: history_id/song_id/started_at/ended_at/reason/session_id; envelope items/count. Do not decode /api/history/played as this DTO.
  - Search group kind is a strict enum of albums/artists/genres/years/tags; fields source_id/name/song_ids. Results contain groups and top-level song_ids.
  - Favorites members contains song_ids. Collection contains source_type/source_id/song_ids/unavailable_song_ids/random_seed.
  - Canonical PlaybackStateDto must use nullable song_id, state enum, nullable playback_context_id, nullable position_seconds, autoplay_enabled and updated_at. The old context/position shape must be rejected.
- [x] **Step 2 — Prove tests fail:** run:
  ~~~bash
  ./gradlew :app:testDebugUnitTest --tests "*LibraryResourceDtoTest" --tests "*PlaybackDtoTest" --tests "*HistoryDtoTest" --tests "*SearchDtoTest" --tests "*CollectionResourceDtoTest"
  ~~~
- [x] **Step 3 — Implement exact DTOs:** use existing JsonConfiguration and kotlinx.serialization; keep required fields required, nullable fields nullable, enums strict, unknown fields strict. Align PlaybackStateDto to actual playback_context_id / position_seconds wire names. Update PlayerActions and PlayerPresentationHandoff to use playbackContextId; this is required so Home can verify the current AutoPlay context.
- [x] **Step 4 — Add safe API paths:** build only current API routes. Reject blank/dot/dot-dot/slash path IDs before encoding; percent-encode UTF-8 path segments. Encode search q and sort parameters with a URL builder, never raw string interpolation.
- [x] **Step 5 — Extend one RuntimeReadApi:** add typed GET methods from the cross-Batch contract. getHistory() must call /api/history; preserve getPlayed() at /api/history/played. getSongs() always sends an explicit allowed sort_by and sort_order. Do not create another HttpTransport or OkHttpClient owner.
- [x] **Step 6 — Route tests:** verify all paths/methods, origin resolution on each call, Chinese and reserved search characters (space, &, #, +, /, ?), rejected path injection, and distinct History/Played routes.
- [x] **Step 7 — Player regression tests:** run:
  ~~~bash
  ./gradlew :app:testDebugUnitTest --tests "*PlaybackDtoTest" --tests "*PlayerActionsTest" --tests "*PlayerPresentationHandoffTest" --tests "*QueuePresentationTest"
  ~~~
  Canonical playback_context_id must reach scope/identity projections; Task 4 controls and status remain unchanged.
- [x] **Step 8 — Full unit suite:** ./gradlew :app:testDebugUnitTest.
- [x] **Step 9 — Local commit:** feat(api): add typed Task 5 resource reads. Commit only DTOs, path builder, RuntimeReadApi, regression fixes and tests.

**Exit gate:** all Task 5 GETs have typed DTOs and safe paths; History/Played are distinct; canonical playback context parses; existing Player/Queue tests pass.

---

## Batch 1 — AppRuntime ResourceMemoryCache and existing cache migration

**Purpose:** make one resource cache serve Home/Search/Library/Playlist/Favorites/Queue/Player. This Batch is the foundation; do not build screen-local caches.

**Files:**
- Create: runtime/ResourceCacheModels.kt, runtime/ResourceMemoryCache.kt
- Modify: runtime/AppRuntime.kt, network/HttpTransport.kt, runtime/RuntimeReadApi.kt
- Modify: ui/queue/QueueSongCache.kt, QueueScreen.kt, QueueArtworkThumbnailCache.kt
- Modify: ui/player/PlayerArtworkMemoryCache.kt, ui/navigation/AppNavHost.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/ResourceMemoryCacheTest.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/network/HttpTransportBoundedBodyTest.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/RuntimeCompositionTest.kt
- Existing regression: QueueSongCacheTest, PlayerArtworkMemoryCacheUiTest, QueueScreenUiTest, MiniPlayerUiTest

**Fixed policy:**
- One AppRuntime owner; process memory only.
- Identity has two levels: session identity (origin/generation/epoch) and per-entry dependency stamp.
- Dependency stamps: SESSION = no revision; LIBRARY = libraryRevision; PLAYLIST = (libraryRevision, playlistRevision); HISTORY = historyFingerprint.
- Identity change (origin/generation/epoch) clears all entries and cancels flights. Library revision invalidates Library + Playlist dependents; playlist revision invalidates Playlist dependents only; history fingerprint invalidates History only. Do not put every revision into one global identity or invalidate everything on every snapshot sequence.
- Snapshot disconnect/stale marks entries Stale but can preserve last-known data. Reconnect alone must not promote old resources to Ready; revalidate before writes.
- GET concurrency 3; completed metadata/entity LRU max 512 entries and estimated weight max 16 MiB. Same key/type/session is single-flight. Screen cancellation does not cancel a runtime-owned shared request.
- Song entities: all SongListDto sources pass through internSongs; lists share references to one canonical SongDto per non-null song_id. Null song_id rows remain only within their source list.
- Artwork raw bytes max 8 MiB/image, max 24 MiB retained total. HTTP body must be streamed under the 8 MiB ceiling: reject known oversized Content-Length before reading; for unknown length read at most limit+1 then fail. Checking after ResponseBody.bytes() is too late.
- Validate image MIME and PNG/JPEG/GIF/WebP signature. Only structured ARTWORK_NOT_FOUND can be negative-cached for the current library revision; do not negative-cache SONG_NOT_FOUND, arbitrary 404, 500, transport, protocol or decoder failures.
- Decoded artwork remains a separate presentation cache: shared Queue thumbnail budget 40 MiB and existing 160px thumbnail target; Player foreground/blur ImageBitmap cache remains presentation-only. Raw bytes are shared by Home/Queue/Player.

- [x] **Step 1 — Test first:** add tests for single-flight, different queries/types not aliasing, max concurrency 3, runtime-scope ownership after caller cancellation, session identity invalidation, late completion rejection, dependency-specific revision invalidation, disconnect/stale/reconnect, 60-second History TTL, LRU/weight, image signatures, bounded body streaming, missing artwork negative-cache and no caching on transport/DTO errors.
- [x] **Step 2 — Prove failures:** ./gradlew :app:testDebugUnitTest --tests "*ResourceMemoryCacheTest" --tests "*HttpTransportBoundedBodyTest". 首轮运行先暴露并修正编译问题；修正后专项及完整单元测试通过。
- [x] **Step 3 — Implement cache identities:** create ResourceSessionIdentity and per-entry ResourceDependencyStamp. Clear all only for origin/generation/epoch changes. On revisions invalidate only dependencies listed above. Compute History fingerprint deterministically from the complete snapshot.wire.history JSON. Do not use general playback sequence/progress as a Library cache key.
- [x] **Step 4 — Implement typed entries:** readResource/observeResource; typed responseTypeKey; single-flight Deferred owned by AppRuntime scope; bounded semaphore; success-only Ready; Empty/Loading/Ready/Stale/Failed; completed-entry LRU; estimated weight; retryable errors. Verify the key expected type before returning any cached value.
- [x] **Step 5 — Normalize Song entities:** intern every SongListDto response; readSong checks entity index before GET and verifies response song_id equals requested ID. Search/Favorites can fetch only unique IDs not already seeded.
- [x] **Step 6 — Implement bounded artwork transport/store:** use same HttpTransport/OkHttp client, stream to cap, preserve JSON error codes for 404/500, cache ARTWORK_NOT_FOUND only, store by server identity + library revision + artwork_id, enforce 8/24 MiB.
- [x] **Step 7 — Attach cache to AppRuntime:** expose stable AppRuntime.resources; observe accepted SnapshotStore identity/watermarks/freshness and Runtime origin/generation; selective invalidate; clear all on session change; close cache before runtime scope cancellation.
- [x] **Step 8 — Convert QueueSongCache to adapter:** remove its raw Song DTO/artwork-byte owner. Keep Queue visibility/prefetch and presentation state, but source Song/raw artwork from AppRuntime.resources. A row leaving the viewport does not cancel a shared in-flight load.
- [x] **Step 9 — Migrate PlayerArtworkMemoryCache:** retain its foreground/blur ImageBitmap presentation role; its existing `runtime.reads.getArtwork` loader now receives bytes through the shared AppRuntime resource cache, so no second raw-artwork owner was introduced.
- [x] **Step 10 — Share decoded thumbnails:** key decoded thumbnails by server identity + `artwork_id`; preserve the existing 160px and 40 MiB budgets. The process-wide thumbnail cache is reusable by Home when its artwork cards are wired in later batches; without a server artwork reference, no thumbnail is requested.
- [x] **Step 11 — Verify regression:** run full unit tests and:
  ~~~bash
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkMemoryCacheUiTest,io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayerUiTest
  ~~~
- [x] **Step 12 — Local commit:** `ff813eb feat(cache): share revision-aware resources across app screens`.

**Exit gate:** one raw Song/artwork GET owner; Queue and Player are adapters; cache survives route changes; session/revision/freshness races and memory bounds have tests.

---

## Batch 2 — Home data models, loaders, and pure projections

**Purpose:** define Home data without Compose so the UI does not invent grouping, order, empty states, or queue behavior.

**Files:**
- Create: ui/home/HomeModels.kt, HomeProjection.kt, HomeDataController.kt
- Tests: app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/home/HomeProjectionTest.kt, HomeDataControllerTest.kt
- Modify StateSnapshotResources.kt only if existing typed snapshot projection cannot expose canonical PlaybackStateDto/QueueSnapshotDto cleanly.

**Models and projection rules:**
- Each section independently exposes Loading, Empty, Ready, Stale, Failed.
- Recent Updates: only /api/library/songs?sort_by=modified_at&sort_order=desc; preserve source order, group non-null album_id by first appearance; null album_id is singleton keyed by song_id/file_uri. Do not sort by file_mtime_ns again.
- Album display metadata uses group’s first (newest modified) Song. Artwork source uses first member in server order that is AVAILABLE and has artwork; if none qualifies, use placeholder. This prevents missing/unreadable first track making a valid album appear coverless.
- History: only /api/history; preserve server order; group known album_id by first/latest event. History item IDs use history_id where present; orphan singleton IDs must not depend on title. If Song no longer in Library index, retain non-playable placeholder; never request every History song independently.
- History album display metadata uses newest event’s Song. Cover source comes from current Song index for the same album: first AVAILABLE artwork-bearing track in Library server order. If none exists, placeholder.
- AutoPlay: decode accepted canonical snapshot; require autoplay_enabled=true and current playback_context_id non-null. Keep only position > 0, source AUTOPLAY, item.playback_context_id equal to playback.playback_context_id; null or mismatched context excluded. Preserve ascending server queue position and every occurrence. Stable card key queue_item_id.
- AutoPlay artwork comes from that occurrence’s Song only; no neighbor/album substitution. If Song metadata absent, use placeholder but keep occurrence identity.
- Recent/History grouped album keys use album_id; single library card uses song_id/file_uri; orphan History uses history_id/composite event identity; AutoPlay uses queue_item_id.

- [ ] **Step 1 — Test first:** projection tests for modified_at order, album first-seen grouping, null album IDs, missing artwork, History order/album de-dup/orphan preservation, no active_event promotion, AutoPlay context match, duplicate song/album occurrences, MANUAL/current/Played exclusion and stable identities.
- [ ] **Step 2 — Prove failures:** ./gradlew :app:testDebugUnitTest --tests "*HomeProjectionTest".
- [ ] **Step 3 — Implement pure Home models/projections:** no Compose imports, no API calls, no mutation side effects, no input list mutation or re-sort.
- [ ] **Step 4 — Implement HomeDataController:** start Songs and History reads concurrently through ResourceMemoryCache. Recent Updates depends on Song list. History can immediately show placeholder event cards even if Song list read fails; AutoPlay always projects from snapshot even if library metadata is unavailable. Reconcile placeholders when Song index later arrives. Do not call /api/history/played or /api/playback/queue.
- [ ] **Step 5 — Section freshness/failure:** failure of one module does not erase others. Stale snapshot marks AutoPlay Stale; stale Library marks Recent Updates Stale; History TTL expiry while Home active is one-shot at monotonic deadline, marks Stale and starts coalesced refresh; no short-interval polling. When Home inactive, cancel expiry timer; Home re-entry checks TTL immediately.
- [ ] **Step 6 — Controller tests:** counted fake loaders prove no N+1 History Song GET, one GET per unique missing metadata ID, separate section error states, late Library response rehydrates History/AutoPlay placeholders, library revision refresh, History fingerprint/TTL refresh and Queue changes cause no HTTP.
- [ ] **Step 7 — Full unit suite:** ./gradlew :app:testDebugUnitTest.
- [ ] **Step 8 — Local commit:** feat(home): add authoritative home data projections.

**Exit gate:** every Home source is proven; projections are deterministic; AutoPlay preserves all matching occurrences; History can render independently of Library read.

---

## Batch 3 — Home Compose screen, artwork rails, and scroll lifecycle

**Purpose:** render exactly three horizontal sections that fill the Home content region.

**Files:**
- Create: ui/home/HomeScreen.kt, HomeSection.kt, HomeArtworkCard.kt
- Modify: ui/navigation/AppNavHost.kt and Home usage in ui/screens/NavigationPlaceholderScreens.kt
- Tests: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/home/HomeScreenUiTest.kt, HomeScrollResetUiTest.kt

- [x] **Step 1 — UI tests first:** assert order/labels, three independent horizontal LazyRows, all three rails visible, no verticalScroll parent, equal section weights within tolerance, and loading/empty/error states do not collapse their section.
- [x] **Step 2 — Scroll reset tests:** scroll all three independently; navigate Home → Search → Home, Home → Library detail → Home, Home → full-screen Player/Queue → Home; every rail returns to index 0, with no cross-rail state leakage.
- [x] **Step 3 — Layout:** full-size Column, three weight(1f) section containers. Compact header, artwork-dominant LazyRow. No vertical scrolling parent, arbitrary top/bottom spacer, or oversized section gap. All sections share root content area between AppBar and mini-player/navigation.
- [x] **Step 4 — Adaptive covers:** calculate square artwork side from each section’s available height after header/metadata. On short displays reduce cover/spacing before hiding/cropping the third section. Text is compact and truncated.
- [x] **Step 5 — Card identity/artwork:** Recent Updates/History group uses defined album cover source; AutoPlay creates one card per queue_item_id and uses that occurrence’s Song artwork. Never cross-fallback between unrelated tracks/albums.
- [x] **Step 6 — Bounded prefetch/cache:** visible items plus at most two look-ahead cards; use B1's original artwork-byte cache, then Home's separate 768px high-resolution decoded-bitmap LRU (128 MiB; do not reuse Queue's 160px thumbnail cache). Pin the visible artwork from all three Home rails so scrolling one rail cannot evict another rail's visible covers. Evict only non-visible LRU entries; never decode the complete History or Queue image set eagerly. All image reads honor the 8 MiB stream cap.
- [x] **Step 7 — Scroll owner:** HomeScrollStateHolder owns three LazyListStates above NavHost, outside Home NavBackStackEntry; do not rely on Navigation saveState/restoreState. On leaving resolved HOME destination (Search, any Library child, Settings, Player, Queue), reset all three to index zero. Normal Home and blurred RootBackdropShell use same state owner.
- [x] **Step 8 — Semantics:** stable test tags, accessible labels containing section/title and queue occurrence identity; cards/header controls retain 48dp touch targets. B5 wires Play All/Shuffle.
- [x] **Step 9 — Instrumentation:**
  ~~~bash
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.home.HomeScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.home.HomeScrollResetUiTest
  ~~~
- [x] **Step 10 — Local commit:** feat(home): render dense horizontal discovery rails.

**Exit gate:** all three rails fill available content; artwork is dominant; scroll is horizontal and independent; route exit always resets to index zero.

---

## Batch 4 — Library read screens, routes, and Search

**Purpose:** replace Library/Search placeholders with typed, server-backed browsing. All pages in this Batch remain read-only; mutation actions are B5/B6.

**Files:**
- Create: ui/library/LibraryRoutes.kt, LibraryScreen.kt, LibraryCategoryScreen.kt, LibraryCollectionScreen.kt, LibrarySongRow.kt, PlaylistScreen.kt, FavoritesScreen.kt
- Create: ui/search/SearchController.kt, SearchProjection.kt, SearchScreen.kt
- Modify: ui/navigation/AppNavHost.kt, AppDestination.kt, ui/shell/RootNavigationBar.kt
- Unit tests: ui/library/LibraryRoutesTest.kt, LibraryProjectionTest.kt, ui/search/SearchProjectionTest.kt
- Instrumentation: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/library/LibraryNavigationUiTest.kt, SearchScreenUiTest.kt

- [ ] **Step 1 — Route tests first:** Library root, Songs, all five category lists/details, Playlist list/detail, Favorites, Search result → category detail → back to Search. IDs survive URL encode/decode. All nested Library paths select Library root.
- [ ] **Step 2 — Route contract:** library; library/{kind}; library/{kind}/{id}. Allowed kinds: songs/albums/artists/genres/years/tags/playlists/favorites. One root-destination resolver maps every nested Library route to LIBRARY and preserves saved root when Player/Queue opens.
- [ ] **Step 3 — Shell integration:** use same root resolver in RootNavigationBar, AppShell title, RootBackdropShell, mini-player visibility and Player return. Replace Home/Search/Library placeholders in both normal and blurred root rendering.
- [ ] **Step 4 — Library root:** exact category order Songs, Playlists, Artists, Albums, Genres, Years, Tags, Favorites; preserve Task3 row/icon/divider/touch/accessibility style; do not show fabricated counts.
- [ ] **Step 5 — Songs list:** default sort_by=title&sort_order=asc; show every server row and availability. Null song_id row key falls back to file_uri. Only title/modified_at × asc/desc options. Changing sort issues a different GET key and renders server response; never sort locally.
- [ ] **Step 6 — Categories:** typed summaries use server ID/name/count; member endpoints use exact server ID. Map albums→ALBUM, artists→ARTIST, genres→GENRE, years→YEAR, tags→TAG. Do not derive source_id from display names. Song list responses feed internSongs.
- [ ] **Step 7 — Playlist/Favorites read views:** playlist list/detail/members preserve server song_ids order and is_system flag; Favorites use /api/favorites/members and shared Song entity store; IDs no longer in Library remain non-playable placeholders.
- [ ] **Step 8 — Search controller:** blank/whitespace query sends no request; trimmed query encoded as q; 250ms debounce; cancel prior query UI subscription; guard results by query generation + origin/generation/epoch/library revision. Search grouping order: server groups and each group song_ids in response order, then top-level song_ids, de-duplicated by first appearance.
- [ ] **Step 9 — Search UI:** render groups in service order; click routes with exact kind/source_id and keeps Search beneath for Back. Song results resolve through shared entity cache; never run local search or reorder.
- [ ] **Step 10 — Error/race tests:** Empty success differs from API/DTO failure; old query cannot overwrite new; clear cancels current UI subscription; page leave releases observers but not AppRuntime cache.
- [ ] **Step 11 — Unit + targeted instrumentation:**
  ~~~bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.LibraryNavigationUiTest,io.github.qyxx2.mpdserver.remote.ui.search.SearchScreenUiTest
  ~~~
- [ ] **Step 12 — Local commit:** feat(library): add library navigation and server-backed search.

**Exit gate:** Library/Search read-only routes are complete; Songs is first; sorting/search are server-backed; root navigation and back stack are correct.

---

## Batch 5 — Song actions, collection playback, and Home section controls

**Purpose:** wire existing playback actions to Library/Search/Home. No new playback server logic.

**Files:**
- Create: ui/library/SongActions.kt, LibraryCollectionActions.kt, ui/home/HomeCollectionActions.kt, ui/library/SongActionMenu.kt
- Modify: ui/library/LibrarySongRow.kt, LibraryCollectionScreen.kt, ui/search/SearchScreen.kt, ui/home/HomeScreen.kt, HomeArtworkCard.kt
- Unit tests: ui/library/SongActionsTest.kt, LibraryCollectionActionsTest.kt, ui/home/HomeCollectionActionsTest.kt

- [ ] **Step 1 — Song action tests first:** exact Play Now /api/playback/songs/{id}/play, Play Next /api/playback/songs/{id}/play-next, Add to Queue /api/playback/songs/{id}/queue. Because existing HttpTransport requires a body for POST, send an explicit empty JSON object {} for routes without a schema body. Block null ID, MISSING/UNREADABLE, stale resource/snapshot, non-writable runtime and unresolved mutation.
- [ ] **Step 2 — SongActions:** build immutable MutationIntent with new UUID per user intent; submit through runtime.mutations only; expose matching OperationActivity. No direct HTTP call or optimistic Queue/Playback mutation.
- [ ] **Step 3 — SongActionMenu:** match Web: Play Now, Play Next, Add to Queue, Favorite toggle (Favorite wired in B6). Dismiss popup before action. One global mutation lock; state uses resource/snapshot freshness and availability.
- [ ] **Step 4 — Collection payload tests:** verify LIBRARY/ALBUM/ARTIST/GENRE/YEAR/TAG/PLAYLIST/FAVORITES/SEARCH/SONGS mappings and permitted fields. Do not send locally randomized ID order.
- [ ] **Step 5 — LibraryCollectionActions:** POST /api/playback/collections/play; Songs uses current server sort. Category/playlist/favorites use original IDs. Search uses exact trimmed query and SEARCH source. randomize is a server flag.
- [ ] **Step 6 — HomeCollectionActions:**
  - Recent Updates Play All/Shuffle: LIBRARY + modified_at desc; sends full collection intent, not visible rail window.
  - History Play All/Shuffle: SONGS + all unique song_ids from complete persistent History in first-seen order; never truncate to visible cards.
  - AutoPlay: no Play All/Shuffle, because that would replace existing playback context. Each card action calls POST /api/playback/queue/items/{queue_item_id}/play with explicit empty JSON object {} (the route has no body schema but the existing transport requires one), preserves queue occurrence identity and validates current epoch/generation/queue revision/context immediately before submit.
- [ ] **Step 7 — Synchronization tests:** receipt is not success; require activity SUCCESS and syncError == null, then force-read affected resource if relevant. Readback failure leaves stale/disabled state.
- [ ] **Step 8 — Retry/race tests:** retry unknown using existing intent ID only. AutoPlay item removed/reordered/current context changed before click must be rejected as stale; never select another occurrence by song_id.
- [ ] **Step 9 — Wire actions:** Home Recent/History header buttons; AutoPlay occurrence play affordance; Library/category/Search collection actions; Song menu only single-song operations.
- [ ] **Step 10 — Unit + targeted instrumentation:**
  ~~~bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueActionsUiTest,io.github.qyxx2.mpdserver.remote.ui.queue.QueueScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.home.HomeScreenUiTest,io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayerUiTest
  ~~~
- [ ] **Step 11 — Local commit:** feat(playback): wire library and home collection actions.

**Exit gate:** every action uses exact existing API, shared MutationCoordinator, fresh gating, idempotency and readback; no client-generated Queue or random list.

---

## Batch 6 — Playlist and Favorites management

**Purpose:** complete list/detail edits and favorite toggles, preserving server membership/order as authority.

**Files:**
- Create: ui/library/PlaylistActions.kt, FavoritesActions.kt
- Modify: ui/library/PlaylistScreen.kt, FavoritesScreen.kt, SongActionMenu.kt, LibrarySongRow.kt
- Unit tests: ui/library/PlaylistActionsTest.kt, FavoritesActionsTest.kt
- Instrumentation: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/library/PlaylistFavoritesUiTest.kt

- [ ] **Step 1 — Playlist tests first:** create, rename, delete, add, remove and reorder; exact method/path/payload; duplicate member; full ordered_song_ids; system playlist rules.
- [ ] **Step 2 — PlaylistActions:** use MutationCoordinator and new intent keys. After SUCCESS + clean snapshot sync, force-read list/detail/members as appropriate. Preserve 404/409/duplicate/reorder-mismatch codes.
- [ ] **Step 3 — Consistency guard:** compare Playlist detail.song_ids exactly to /songs response song IDs and order. Disable editing on stale/missing/mismatch data. Reorder always submits complete membership, never visible window only.
- [ ] **Step 4 — System playlist:** is_system=true hides/disables rename/delete only. Existing Web and repository do allow member add/remove/reorder for system lists; do not invent broader lock.
- [ ] **Step 5 — Favorites tests first:** GET members; PUT/DELETE exact song path; timeout UNKNOWN; retry same intent; missing Song placeholder; 204 readback.
- [ ] **Step 6 — FavoritesActions + Song menu:** use PUT /api/favorites/{song_id} and DELETE same path; provide {} for bodyless PUT because MutationIntent requires a body; route ignores that body. Refresh /api/favorites/members and collection after coordinator synchronization. No optimistic toggle.
- [ ] **Step 7 — Playlist UI:** create/rename forms, delete confirmation, add/remove, reorder drag/keyboard. Keep member action availability aligned with Web; system restrictions only for rename/delete.
- [ ] **Step 8 — Favorites UI:** one shared members resource, Song entity cache for metadata, retain unavailable IDs as placeholders; SongRows must not each fetch favorites.
- [ ] **Step 9 — Failure tests:** 404 playlist; system rename/delete 409; add/remove/reorder on system playlist not blocked client-side; duplicate song 409; member mismatch 409; 204; UNKNOWN retry; readback failure.
- [ ] **Step 10 — Unit + targeted instrumentation:**
  ~~~bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.library.PlaylistFavoritesUiTest
  ~~~
- [ ] **Step 11 — Local commit:** feat(library): add playlist and favorites management.

**Exit gate:** playlist/favorite changes use coordinator, respect rename/delete-only system rules and full membership readback.

---

## Batch 7 — Integration, performance, regression, and device acceptance

**Purpose:** prove all T5 matrix rows and close architecture boundaries. Do not add features in this Batch.

**Files:**
- Create: app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/navigation/Task5CrossScreenIntegrationUiTest.kt
- Create: app/src/test/java/io/github/qyxx2/mpdserver/remote/runtime/Task5CacheReuseTest.kt
- Create: docs/superpowers/reports/2026-10-31-task-5-verification.md
- Modify only files required by failing tests within existing Task5 modules.

- [ ] **Step 1 — Counted cache reuse:** same server identity, Home → Search → Library Songs → Album detail → Playlist/Favorites → Queue → Home. Each path/key fetches once; same Song IDs/artwork share entries; AutoPlay causes no /api/playback/queue GET.
- [ ] **Step 2 — Selective invalidation:** library revision invalidates Library/Song/Search/Artwork and Playlist dependents; playlist revision invalidates Playlist/Favorites only; history fingerprint/60s TTL invalidates History; queue revision reprojects AutoPlay without HTTP.
- [ ] **Step 3 — Session races:** origin/generation/epoch change clears all session data and in-flight completions cannot repopulate new cache; disconnect marks stale; reconnect alone cannot mark old resources Ready.
- [ ] **Step 4 — Navigation stack:** nested Library remains selected; Search→category→Back returns Search; Player/Queue returns correct root; leaving Home resets all three rails; normal and blurred root render paths share state owner.
- [ ] **Step 5 — Task 4 regression:** run Player, Queue reorder, Played reveal, mini-player artwork/controls, status-ring and full-screen transition instrumentation. No unrelated fixes in this Batch.
- [ ] **Step 6 — Performance/weight:** with 400-song/400-queue fixtures, GET concurrency <=3, entity cache <=512 entries/16 MiB, raw artwork stream cap 8 MiB/item and retained total <=24 MiB, decoded thumbnails <=40 MiB, no N+1 History Song GET. Fast-fling Home and Queue without eager image decode or repeated fetches.
- [ ] **Step 7 — Full unit suite:** ./gradlew :app:testDebugUnitTest.
- [ ] **Step 8 — Full connected instrumentation:** ./gradlew :app:connectedDebugAndroidTest; record all skips and ensure no required path is hidden by a skip.
- [ ] **Step 9 — Build/lint:** ./gradlew :app:assembleDebug :app:lintDebug; verify applicationId, unified signing identity and version consistency. Do not change signing keys.
- [ ] **Step 10 — Live MS read-only checks:** compare Home Recent Updates to modified_at desc, History to /api/history, AutoPlay to accepted snapshot. Do not trigger playback, Queue, Playlist/Favorites writes, library scan or system config against live user library.
- [ ] **Step 11 — Manual device acceptance:** real portrait device, short-height layout and large font; all three Home sections visible/no large vertical gaps; artwork-dominant rails scroll fully and reset; Songs first/all four sort options; Search debounce/back; no-cover/unavailable/orphan History; offline/stale/reconnect; playlist/favorites controls; mini-player and full Player/Queue unchanged.
- [ ] **Step 12 — Architecture audit:** no second OkHttpClient/RealtimeController/SnapshotStore/MutationCoordinator/resource cache; no Room/SQLite/disk business cache; no direct MPD protocol; no local sorting/randomization/AutoPlay/History/Queue logic; no direct UI mutation requests; no duplicate Queue/Player raw artwork owner.
- [ ] **Step 13 — Report and matrix:** record exact commit hashes, test counts, build/lint results, read-only live checks, manual checks, exclusions and evidence for every T5-00…T5-37 row. Keep Task 5 master checkbox unchecked while any required gate is blocked.
- [ ] **Step 14 — Local commit:** test(task5): verify home library search and shared resources. Do not push or merge without explicit instruction.

**Exit gate:** all matrix rows have evidence; Task 4 regression is green; screens use existing server resources; cache identity/freshness/memory rules pass; no second authority or unbounded GET path remains.

## Verification commands

Targeted unit tests per Batch; full gates in B7:

~~~bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:lintDebug
git diff --check
git status --short --branch
~~~

Connected tests require the authorized device attached to this local environment. Use the existing unified signing configuration; never fall back to a different key or change applicationId.
