# Task 4 Batch 3 Verification Report

Date: 2026-10-27
Branch: task4
Scope: Batch 3 — shared playback clock, progress draft/seek, player controls, shared mutation feedback and explicit UNKNOWN recovery.

## Implementation boundary

Added native PlayerClock and PlayerActions for the existing full-screen Player. The clock is a presentation-only projection of the accepted canonical snapshot: it uses fresh server observation data, server-side captured_at minus observed_at plus monotonic request/response RTT to estimate sample age, and a six-second maximum lease. The same observation key cannot renew its lease; repeated snapshots may update the canonical base position without extending that deadline. A missing/invalid timing estimate cannot start interpolation. Paused playback does not extrapolate; stale/disconnected facts stop presenting a fresh clock; backgrounding freezes the display and foregrounding requests a read through the Runtime's existing coalescing StateReadCoordinator. This reproduces the Web playerClock.ts timing lease without comparing the phone's wall clock to the server clock.

The Player action adapter receives the single Application-owned AppRuntime. It does not create an HTTP client, transport, WebSocket, snapshot store, retry journal or independent mutation owner. Every action submits an immutable intent to runtime.mutations; the existing MutationCoordinator continues to own the request, idempotency key, origin, result classification and activity. The existing app-scoped SnapshotStore remains the only canonical playback/Queue state.

Wire actions are aligned to the fixed Web implementation at MPD-Server baseline 2ad8445:

- Pause, previous and next use POST with an explicit {} body, as required by the existing HttpTransport contract.
- Resume copies the server observation's control_target.queue_item_id and opaque token; it never reconstructs or substitutes a current song.
- Seek sends one POST containing the selected seconds and the captured server target. While dragging, only an in-memory draft/preview changes; a release revalidates the current target, occurrence, snapshot epoch/generation and control eligibility before dispatch.
- Pending activity locks duplicate controls. A transport/protocol ambiguity remains UNKNOWN. An explicit retry is delegated to the same coordinator-stored intent and key, and only while its original control identity is still the active identity. No automatic new-key replay is introduced.
- When a seek response contains an updated_at receipt, only that timestamp is retained as narrow response metadata. The preview stays in a syncing state until a fresh authoritative observation covers the receipt. A confirmed write whose follow-up read fails remains confirmed with a synchronization warning rather than being represented as a failed write.

Updated the global activity sheet to expose a retry action only for a current-origin UNKNOWN operation whose retry preconditions are still valid. The action remains owned by the shared coordinator; no per-page network/retry owner was added. Retry visibility is gated by current server origin, connected Runtime generation and original intent/control identity, not by fresh-read status alone (matching Web behavior after a read error).

## TDD and automated verification

Test-first RED evidence is retained at:

- .superpowers/sdd/2026-10-27-task-4-full-player-queue/b3-red.log

Final local verification log:

- .superpowers/sdd/2026-10-27-task-4-full-player-queue/b3-finalverify.log

Command:

    ./gradlew --offline --no-daemon testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug

Result: BUILD SUCCESSFUL. The full local unit suite passed: 187 tests, 0 failures, 0 errors, 0 skipped. Lint completed without lint errors; Android instrumentation sources compiled; debug APK assembled. Focused tests cover clock lease expiry/sample age + RTT/repeated samples/pause/stale/disconnect/identity/background behavior; the shared StateReadCoordinator timing calculation is separately tested with a deterministic monotonic clock; pause/resume/previous/next/seek endpoint and payload contracts; exact resume target token; one seek submission per release; receipt-to-readback convergence; draft cancellation on target/freshness changes; duplicate action blocking; UNKNOWN retry reusing the original intent; retaining retry across read staleness while the Runtime target/scope is unchanged; rejecting retry after identity drift; coordinator key/origin/error/readback behavior; and safe activity-sheet retry eligibility. UNKNOWN retry identity follows Web semantics: temporary read staleness alone does not retire the original intent while Runtime connectivity/generation and the same control/business scope remain; target, occurrence/business scope or connection-generation changes prevent retry.

## Device verification and persisted configuration

Device: Samsung SM-G986N, Android 13; ADB target 10.87.61.250:40689.

- PlayerControlsUiTest passed 3/3 on device in the final combined instrumentation run. It uses an injected test submitter and fake canonical snapshot, so it verifies control enablement, seek interaction and cancellation without changing live MS playback.
- FullScreenPlayerNavigationUiTest passed 5/5 on device after the final implementation edits in the same combined instrumentation run (8/8 device tests passed overall). The broader navigation regression retained the existing back-stack, edge-to-edge, root destination and player presentation assertions.
- Debug APK overlay installation uses adb install -r. The latest built APK was installed successfully using adb install -r; its SHA-256 is 2e97708a5349fd16881aa66931ae55fa1cc46d062d025c8c6f61f9278e3ef97d. The existing DataStore file files/datastore/ui_preferences.preferences_pb has SHA-256 86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c; immediately before and after the final overlay installation this hash was unchanged.
- Startup smoke check injected a launch into the installed app and showed no FATAL EXCEPTION or activity-start failure in the sampled fresh logcat window.

## Authority and scope audit

Static source scan of PlayerClock, PlayerActions, the full-screen screen and navigation wiring found no new OkHttpClient, HttpTransport, RuntimeReadApi, realtime socket, local canonical playback/Queue store or separate retry owner. The new foreground refresh delegates to the existing RealtimeController / StateReadCoordinator; the coordinator performs the existing shared, coalesced authoritative read. git diff --check passed.

NOT COVERED: No real pause/resume/next/previous/seek/stop write was sent to the live MS server in this batch. This avoids unexpectedly changing the user's current playback. Endpoint, HTTP method, JSON body, target binding, operation activity, idempotency/retry and readback semantics are covered by deterministic unit/UI instrumentation tests; live playback mutation latency and actual MPD seek convergence require a separately authorized real-playback test.

No Web/server source was modified, no dependency was added, and no Batch 4 lyrics or Batch 5 Queue implementation was started.
