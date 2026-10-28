# Task 4 Batch 6 Verification Report — Queue Actions

Date: 2026-10-28
Branch: task4
Starting HEAD: 1dc1d95010466c3d8cbf195686f246bac8cf1611 (fix(android): show autoplay next in Queue)

## Scope and implementation

Batch 6 adds native Queue row play, the Web-equivalent row menu, Move Next, Add to Queue tail, Remove, Clear pending and Save Queue as Playlist. All writes are intents delegated to the existing AppRuntime MutationCoordinator; QueueScreen has no HTTP client or direct transport. The canonical server snapshot remains authoritative. No Batch 7 drag-reorder behavior or Batch 8 Played reveal behavior was added.

- Row play addresses the clicked queue_item_id, never searches by song_id. The URI path escaping uses JavaScript encodeURIComponent's unescaped character set, including the slash/Unicode-safe regression cases.
- Menu parity follows current Web QueueView.vue and queueActions.ts: all visible occurrences retain a menu; Next/Add are shown only for position > 0; Remove is exposed for visible current, Played or pending occurrences still present in the canonical snapshot. The server decides final mutation legality.
- Clear sends only DELETE /api/playback/queue. It has an explicit confirmation, cancellation sends nothing, and no Stop route or local Queue clearing is used.
- Playlist save rejects blank-only input but submits the exact entered name. After the write receipt, the dialog is locked while the existing RuntimeReadApi reads GET /api/playlists. If this authoritative read fails, only a readback retry is offered; it does not POST a second time. A server-origin switch after the original write also prevents a duplicate write.
- MutationCoordinator reports confirmed writes as SYNCING until authoritative state synchronization completes. A failed readback remains a confirmed write with a synchronization error; it is not misreported as a failed write.
- Explicit retry uses the same intent, idempotency key, origin, route and body. QueueMutationScope binds retry permission to epoch, Runtime connection generation, explicit-stop generation, Queue revision and the ordered occurrence identity/context. Disconnection, unknown freshness or scope drift blocks stale retries. An ordinary failed read that only makes the still-same accepted snapshot stale does not by itself invalidate retry permission.

## Automated verification

Final local command completed successfully:

    ./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin

Result: BUILD SUCCESSFUL. The aggregated unit XML contains 242 tests, 0 failures, 0 errors and 0 skipped. Lint, debug APK assembly and Android-test Kotlin compilation passed. The final log is .superpowers/sdd/2026-10-27-task-4-full-player-queue/b6-finalverify-13.log.

The connected UI suite passed 12/12 once on SM-G986N / Android 13 during Batch 6 implementation (5 QueueScreenUiTest and 7 QueueActionsUiTest cases; log b6-connected-final.log). A later final rerun after the last small cleanup did not complete successfully: the phone entered Dozing/lockscreen before the instrumentation run. The first UI case timed out waiting for its metadata-loading node, then subsequent cases reported “No compose hierarchies found in the app”; the log is b6-connected-final-2.log. adb confirmed mWakefulness=Dozing and NotificationShade/keyguard focus. This rerun is recorded as blocked by the locked device, not as a passing final run. No app FATAL EXCEPTION was found in the inspected logcat segment.

Additional verification passed: git diff --check; source-boundary scan found no page-owned OkHttpClient/HttpTransport/direct execution in QueueActions/QueueScreen/row/menu; a clear-route scan found no Stop request or local Queue row mutation in QueueActions/QueueScreen. Unit tests cover exact methods/routes/payloads, duplicate-song occurrence IDs, escaping, current/Played menu behavior, no-op moves, stale/locked write gating, Clear cancellation and Stop separation, save-name preservation, authoritative playlist read, coordinator readback status transitions and retry-scope drift.

## Device overlay installation and data preservation

The final debug APK was installed using adb install -r, without uninstalling or clearing application data. Package firstInstallTime stayed 2026-10-24 18:47:26; lastUpdateTime advanced to 2026-10-29 00:33:18. The DataStore preferences file hash before and after install remained identical:

    ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45

Final installed debug APK SHA-256: 4d772de31579818361aa6b925da39eeca59aed92613d659c311b92c2c8812d91.

## NOT COVERED

- No real Queue write, play, move, remove, clear or save request was sent to the configured MS server. The user did not authorize live mutation testing with a defined recovery procedure; write semantics were verified with deterministic unit and Compose fakes.
- The final connected-test rerun is blocked by the device being locked/Dozing after the earlier 12/12 green instrumentation run. Final-source local tests/lint/build/Android-test compilation all passed, but a new final-source on-device pass is not claimed.
- No manual live-server Queue visual inspection or server-side mutation/readback was claimed.

No server/Web source or API was changed. No push, merge or branch switch was performed.