# Task 4 Queue Drag Visual Correction

Date: 2026-10-29
Branch: `task4`
Scope: Follow-up correction to Batch 7 only. Batch 8/9 and unrelated Queue actions were not started.

## Change

- Replaced the previous in-place row reorder preview with a Web-equivalent floating row proxy. The source stays in its canonical visual position at 0.30 opacity; the proxy matches the row content/height and follows accumulated horizontal and vertical pointer deltas above the LazyColumn.
- Added a translucent white overlay, theme-accent outline and soft elevation to the floating proxy, plus the existing insertion marker remains tied to the transient reorder draft.
- Release continues to submit at most one occurrence-keyed PUT through the existing `QueueActions`/Runtime mutation coordinator. The canonical Queue order is not locally patched. Pointer cancellation or a changed baseline discards the draft. Current/Played handles remain non-interactive.
- Updated the Task 4 visual/interaction contract to freeze this drag appearance and its canonical-state boundary. No Web/server/API/transport/state-owner/dependency changes.

## Verification

Command:

```text
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.queue.QueueReorderUiTest
```

Result: `BUILD SUCCESSFUL`. Unit tests: **260 passed, 0 failures, 0 errors, 0 skipped**. `QueueReorderUiTest`: **3/3 passed** on SM-G986N / Android 13, covering the floating proxy and white-mask surface/offset, one exact reorder intent with canonical rows unchanged before readback, and pointer cancellation/current-handle inertness. `QueueReorderDraftTest`: **9/9 passed**. Lint, APK assembly, Android-test Kotlin compilation and `git diff --check` passed.

## Data and scope safety

- Instrumentation uses synthetic snapshots and a fake submitter. No live MS Queue mutation or server-side reorder/readback was performed.
- The app package was overlaid by the connected instrumentation build, not uninstalled; `firstInstallTime` remained `2026-10-24 18:47:26`. The installed debug APK SHA-256 is recorded by the build artifact. App preferences were not modified by the reorder tests.
- No commit, push, merge, branch switch or Batch 8/9 work was performed.
