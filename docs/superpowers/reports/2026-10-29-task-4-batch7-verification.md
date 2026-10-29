# Task 4 Batch 7 Verification Report — Queue Pending Drag Reorder

Date: 2026-10-29
Branch: `task4`
Starting HEAD: `3a86757` (`docs(android): record final Batch 6 verification`)
Scope: Batch 7 only — reorder pending Queue occurrences. Batch 8 Played reveal and Batch 9 integration/exit gates were not started.

## Implementation

- Added `QueueReorderBaseline`, `QueueReorderDraft` and `QueueReorderCommit`. The baseline is captured only from an eligible fresh snapshot tied to its owning connection generation and includes server epoch, Queue revision, current occurrence and pending occurrence identities, positions, sources and playback contexts. Ambiguous/malformed current rows, duplicate occurrence IDs or duplicate pending positions are rejected.
- The draft uses `queue_item_id` identity throughout, so two occurrences of the same song remain distinct. Only `position > 0` rows can begin a drag. The current-row and Played-row six-dot handles remain inert; self/current/played/missing targets cannot create a reorder commit.
- Reorder is a transient Compose draft, not a second queue state owner. Pending row positions can preview the draft order with stable LazyColumn keys and an insertion marker. On drop, a fresh baseline and current visible occurrence list are checked again before one existing `QueueActions` intent is submitted. The intent is `PUT /api/playback/queue/items/{queue_item_id}` with `before_queue_item_id`; `null` represents the tail. The existing Runtime mutation coordinator remains the only write path. A response does not locally patch the canonical queue; the server snapshot remains authoritative.
- Pointer cancellation clears the draft. Changes in epoch, connection generation, Queue revision, current occurrence, pending membership/order/position/source, visible occurrence list or control eligibility prevent a stale reorder intent. Viewport-based drop targeting accounts for off-screen occurrences rather than treating the viewport row count as a global index.
- No Web/server/API contracts, playback owner, queue owner or other Task 4 batches were changed.

## Test-first evidence

- Initial reducer test file was run before the reorder production types existed; `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b7-reorder-draft-red.log` records the expected compile-time RED. This was not a behavioral assertion failure.
- Review identified an off-screen insertion defect in a naïve viewport-index implementation. The test `dropTargetUsesViewportCentersButKeepsFullOrderIndexesForOffscreenRows` then failed at `QueueReorderDraftTest.kt:67` with 9 tests completed / 1 failure. Evidence: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b7-viewport-target-red.log`. The corrected full-order/viewport-center mapping is included in the final green run.
- `QueueReorderDraftTest` covers pending insertion/end, duplicate-song identity, current/Played/self/missing rejection, unchanged and unavailable commit refusal, baseline drift, malformed/ambiguous snapshots and scrolled viewport targeting. `QueueActionsTest` asserts the exact reorder method/path/body and invalid target rejection.

## Final automated verification

Command:

```text
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueReorderUiTest
```

Result: `BUILD SUCCESSFUL`.

- Unit tests: **253 passed, 0 failures, 0 errors, 0 skipped**.
- `QueueReorderDraftTest`: 9/9 passed; `QueueActionsTest`: 10/10 passed.
- Connected Compose instrumentation: **2/2 passed** on SM-G986N / Android 13. It exercised a pending-row long-press drag and exact single PUT intent, asserted the unchanged canonical row order is still displayed before server readback, and verified pointer cancellation/current-row handle do not submit writes. XML: `app/build/outputs/androidTest-results/connected/debug/TEST-SM-G986N - 13-_app-.xml`.
- `lintDebug`, debug APK assembly, Android-test Kotlin compilation and `git diff --check` passed. Final log: `.superpowers/sdd/2026-10-27-task-4-full-player-queue/b7-finalverify-final.log`.
- Final debug APK SHA-256: `ffcd8caf3f5085dc45b13dac46db4cc9d4c0edadf55e9b38e3f4d07a0b652d3c` (`app/build/outputs/apk/debug/app-debug.apk`).

## Existing app data preservation

Instrumentation/build replaced the installed debug app by overlay without uninstalling the package or clearing application data. Package `firstInstallTime` stayed `2026-10-24 18:47:26`; `lastUpdateTime` advanced to `2026-10-29 13:03:18`. The persisted DataStore preferences hash after verification remained `ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`, matching the pre-run hash; the configured MS server origin was retained.

## NOT COVERED

- No live MS Queue reorder/write or subsequent real-server readback was issued. The device gesture tests rendered a synthetic canonical snapshot and captured the outgoing `MutationIntent` with a fake submitter; they did not write to the user's configured server.
- No manual visual verification against the live server Queue was claimed. A distinct navigation-away-during-an-active-drag instrumentation case was not added; pointer cancellation and baseline drift are tested, and the ephemeral draft is composable-local state, but route-exit gesture cleanup is not separately device-verified.
- A transient success/UNKNOWN reply against the live server was not tested. The existing coordinator and canonical snapshot remain the write and state authorities; live mutation outcome/readback is outside this batch's verified scope.

No push, merge, branch switch or live server mutation was performed. Batch 8 and Batch 9 remain untouched.
