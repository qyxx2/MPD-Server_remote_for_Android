# Task 4 Batch 3 — Playing-state fullscreen flicker fix

Date: 2026-10-28
Branch: `task4`
Scope: Fix the confirmed WebSocket decoder/server protocol mismatch only.

## Root cause

The attached diagnosis report and `.superpowers/sdd/2026-10-27-task-4-full-player-queue/diagnosis-playing-status-flap/runtime-evidence.log` document the real-device reproduction and exact runtime exception: `SerializationException: protocol_version is only valid on the initial snapshot`, followed immediately by a transition from `CONNECTED/FRESH` to `DISCONNECTED/STALE`. The checked-out MS server sends `protocol_version: 1` on both `snapshot` and every `invalidate` frame, but Android rejected the version field on `invalidate`. That disconnect made the full-screen presentation become `UNKNOWN`; reconnecting restored it until the next invalidate.

## Implementation

- `RealtimeFrameDecoder` now requires `protocol_version` on both frame types and accepts only version 1. An `invalidate` remains a read watermark only; it is still not accepted as a state patch. Missing versions and unsupported versions are explicit protocol errors.
- Updated protocol/network/runtime fixtures to match the exact current MS wire shape.
- Added `RealtimeConnectionTest.serverVersionedInvalidateKeepsTheRealtimeConnectionReady`: sends the exact versioned invalidate frame from the live server contract and asserts the event is decoded, the socket remains Ready, and no reconnect/socket replacement occurs. Before the implementation this test failed (test-first RED in `/tmp/task4-playing-repro/protocol-red.log`, `AssertionError` at the versioned-invalidate assertion). After the change it passes.
- Added decoder coverage for protocol-v1 invalidate, missing version and unsupported version. Updated `docs/superpowers/specs/2026-10-23-android-backend-api-contract.md` to specify version 1 on both snapshot and invalidate frames.
- No MS server source/API behavior was changed. No second state owner, retry loop, or UI-only suppression was added.

## Verification

- Focused protocol/runtime tests: `RealtimeFrameDecoderTest` (7), `RealtimeConnectionTest` (4), and `RuntimeCompositionTest` all passed in `/tmp/task4-playing-repro/protocol-focused-green.log`.
- Full verification: `./gradlew --offline --no-daemon --no-build-cache testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug` — **BUILD SUCCESSFUL**. All 197 unit tests passed (0 failures, 0 errors, 0 skipped); lint, Android-test Kotlin compilation and APK assembly succeeded. Log: `/tmp/task4-playing-repro/final-validation.log`.
- Installed the built APK over the same package without clearing app data. Saved server-origin preferences SHA-256 remained `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c` before and after installation. APK SHA-256: `b2a561e0d6f7eef97b00f733aa366419aee9d0b5645e1fc91e66d6b12a5a62b9`.
- Live-device check on SM-G986N / Android 13: launched the full-screen player, tapped Play, then captured 9 screenshots at intervals of about 1.6 seconds over 13.9 seconds. All 9 corresponding read-only `/api/state` samples stayed on `另一个天堂`, `PLAYING`, `fresh`, `actual_state=playing`, `sync_status=CONFIRMED`, and matching bound/control-target occurrence. The captures all showed similarly sized full-artwork frames (about 1.71 MB each), not alternating placeholder frames. No application crash was logged. This is the same play path that previously disconnected/reconnected on each invalidate. After the check, playback was paused again; final server state is `PAUSED/fresh/paused/CONFIRMED`.
- Temporary diagnostic code was already removed before this implementation. `RealtimeConnection.kt` and `SnapshotStore.kt` remain byte-for-byte unchanged from their pre-diagnosis Git state. `git diff --check` passes.

## Commit boundary

The commit contains only the protocol decoder fix, its tests, the realtime API-contract update, and the root-cause/fix reports. The earlier, unrelated uncommitted Task 4 fullscreen/handoff changes in this worktree are deliberately left unstaged and uncommitted. No music files or library records were changed or deleted.
