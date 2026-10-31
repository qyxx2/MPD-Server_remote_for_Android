# Task 5 Batch 5 verification

## Scope and implementation

Completed Task 5 Batch 5 on the existing `task5` branch in the requested checkout. Added a shared playback mutation gateway over the application-owned `MutationCoordinator`; wired Play Now, Play Next and Add to Queue to their existing Web endpoints with immutable intents and explicit `{}` POST bodies; and gated writes on canonical song availability, resource freshness, snapshot writability and unresolved global mutations.

Added strict server-owned collection playback requests for Library Songs, category members, playlists, Favorites and Search. Source-only payload fields follow the backend CollectionRequest contract. Sorting and randomization remain server-side. Recent Updates uses the complete LIBRARY collection ordered by `modified_at desc`; Playback History uses every unique persistent History song ID in first-seen order. AutoPlay exposes per-occurrence Play only, and validates the exact queue item, song, source, position, epoch, connection generation, queue revision, playback context and queue fingerprint before submit and unknown-result retry.

Home, Library and Search share one set of shell-owned action adapters and one activity projection. Row/card affordances are disabled by one global enabled flag rather than creating a StateFlow collector per row. Favorites CRUD remains out of scope for B6.

## Verification

- Main Kotlin, unit-test Kotlin and instrumentation-test Kotlin compilation: PASS.
- Full unit suite: 394 tests, 0 failures, 0 errors, 0 skipped.
- `git diff --check`: PASS.
- Targeted device instrumentation ran on SM-G986N / Android 13. The selected regression set covered 35 distinct cases across Queue Actions, Queue Screen, Home Screen, Library Navigation, Search, Mini Player and Song Action Menu. The initial combined run passed 34/35; the single failure was a new test that bypassed the production SongActionMenu wrapper. The test was corrected to render the production wrapper. The corrected SongActionMenuUiTest rerun passed 4/4. Together these runs provide passing evidence for all 35 distinct targeted cases.

## Safety and review

No configured-MS playback, Queue, playlist, Favorites, scan or settings writes were issued. No app-data clear, uninstall, server-origin edit, push or merge was performed. Self-review checked backend/Web payload parity, shared mutation ownership, source validation, freshness gates, History completeness, AutoPlay occurrence identity and retry safety. No other programming or reviewer agents were queried or dispatched.

## Commit

Local commit message: `feat(playback): wire library and home collection actions`.
