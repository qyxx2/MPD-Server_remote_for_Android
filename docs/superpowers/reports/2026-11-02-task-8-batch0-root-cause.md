# Task 8 Batch 0 — Queue repeated-interaction root-cause report

**Date:** 2026-11-02
**Branch:** `feature/task-8-player-interaction-polish`
**Base:** `4d185f9a9928852f169d3e127e0e7cf25183c731`
**Device:** Samsung SM-G986N, Android 13 / API 33
**Scope:** Diagnosis and instrumented reproduction only. No production behavior changed.

## Test fixture and safety boundary

Added `QueueInteractionLifecycleUiTest.kt` with the six B0 scenarios. The fixture injects the existing `QueueScreen` `snapshotProvider` and `queueActionsFactory` seams. Its fake submitter records the exact mutation intent and returns `Submitted`; after each successful reorder the fixture publishes a matching canonical snapshot readback with the reordered occurrence list, an incremented Queue revision and an incremented snapshot sequence.

The tests use an empty mutation-activity list and a stub Song loader. They do not send Queue mutations to the configured MS server, and do not depend on network responses. No app uninstall, package-data clear, or server-side data mutation was performed.

## Independent instrumentation matrix

Each method was run independently on SM-G986N / API 33 with:

```bash
./gradlew :app:connectedDebugAndroidTest --offline \
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueInteractionLifecycleUiTest#<method>'
```

| Test method | Result | Observation |
|---|---|---|
| `sameQueueHandleCanDragTwiceInOneComposition` | FAIL — reproduced | First drag submits. After the fixture applies its canonical readback, the second drag starts but submits no second reorder (expected 2 submissions, observed 1). |
| `differentQueueHandlesRemainDraggableAfterFirstDrag` | FAIL — reproduced | First occurrence drag submits. Dragging a different pending occurrence after readback also produces no second submission. |
| `dragThenImmediatelyOpenSameRowsOverflowMenu` | PASS | A completed first drag is followed by opening and dismissing that row's menu; row/menu semantics remain present and enabled. |
| `dismissOverflowMenuThenDragQueueRow` | PASS | Dismissing the menu before dragging does not block the reorder. |
| `cancelledDragCanBeFollowedBySuccessfulDrag` | PASS | A cancelled gesture submits nothing; a subsequent drag on the same row submits successfully. |
| `repeatedDragAndMenuCyclesRemainAvailable` | FAIL — reproduced | The first drag/menu cycle succeeds. The next drag fails to submit, matching the repeated-drag cases above. |

The complete class was then run once:

```bash
./gradlew :app:connectedDebugAndroidTest --offline \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueInteractionLifecycleUiTest
```

Result: **6 tests, 3 failures, 0 skipped**. The failures were exactly the same-row repeat, different-row repeat and repeated drag/menu cycle tests. The three menu/cancellation control scenarios passed.

## First failing boundary

Temporary, focused Logcat diagnostics were added during diagnosis and removed before the batch ended. The decisive same-row trace was:

1. The first drag completed and submitted `pending-b before pending-a`.
2. The fixture published the authoritative readback: snapshot sequence `5 → 6`, Queue revision `17 → 18`, pending order `[pending-a, pending-b, pending-c] → [pending-b, pending-a, pending-c]`.
3. `QueueScreen` recomposed against revision 18. Its new projection and reorder baseline both contained `[pending-b, pending-a, pending-c]`.
4. The second pointer gesture reached `dragStart`, `dragDelta` and `dragEnd`; it was not lost by the pointer detector. The new start/end callbacks saw revision 18.
5. The delta callback still executed the prior `updateReorder` closure, whose captured baseline was revision 17 and whose pending list was `[pending-a, pending-b, pending-c]`. It rejected the new draft as incompatible, reset the draft, and left `finishReorder` with no commit.

Callback identity diagnostics isolated why the delta callback stayed old. On recomposition for `pending-b`, the newly supplied `onDragDelta` had a different identity from the value retained by `rememberUpdatedState`, while Kotlin equality still returned true:

- Newly supplied delta callback identity: `56990106`
- Remembered delta callback identity: `7120205`
- Equality: `true`
- Referential identity: `false`

The second pointer event then invoked the remembered callback identity `7120205`. The start and end callbacks had new identities and used the current Queue revision, so the stale behavior is isolated to the delta callback.

Relevant production call sites (left unchanged):

- `QueueScreen.kt:802` passes the local function reference as `onReorderDelta = ::updateReorder`.
- `QueueOccurrenceRow.kt:359` stores it with `rememberUpdatedState(onDragDelta)`; the pointer-input coroutine then calls `currentOnDragDelta`.

**Root cause:** the repeated-drag failure is at the freshness/equality boundary of the `onDragDelta` callback, not at pointer-input node attachment or gesture delivery. The local function reference supplied by `::updateReorder` compares equal to its replacement despite carrying a different captured composition state. Because `rememberUpdatedState` uses state equality, the old callback remains stored; its stale baseline rejects the next drag. Confidence: **high** — the old/new callback identity, equality result, captured Queue revision and resulting compatibility rejection were all observed in the same instrumented run.

## Excluded hypotheses

- **Pointer-input node not receiving a second gesture:** excluded. The same handle emitted second `dragStart`, `dragDelta` and `dragEnd`; the pointer-input key remained enabled.
- **Gesture cancellation or event consumption:** excluded for the reproduced second drag. It reached the delta callback and normal drag-end callback. The separate cancellation test also passed.
- **Queue projection/readback not refreshing:** excluded. The screen recomposed at revision 18 and exposed the reordered occurrence list before the second gesture.
- **Mutation lock or unresolved action:** excluded. The injected activity list is empty, `actions.isEnabled` remained true, and the fake submitter has no server/network dependency.
- **Menu popup lifecycle:** not causal in the passing menu-dismiss-then-drag and drag-then-menu scenarios.
- **Reset/finish ordering as the initiating fault:** not the first failure. The stale delta callback rejects compatibility and resets the draft before the normal finish callback runs.

## Batch verification and boundary

- `./gradlew :app:compileDebugAndroidTestKotlin --offline` — PASS after restoring production sources.
- `git diff --check` — PASS.
- Temporary diagnostics were removed. `QueueOccurrenceRow.kt` and `QueueScreen.kt` are clean relative to the starting worktree.
- No production fix, API/DTO change, playback-owner change, mutation-owner change, persistence change, or server change is included in B0.
- Existing user changes to the Task 8 plan/specs and the Superpowers README/master plan were left untouched.

B0 exits with an executable lifecycle matrix and a proven first failing boundary. The callback freshness correction is deliberately deferred to its assigned implementation batch.
