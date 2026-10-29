# Task 4 Batch 6 — Queue popup, edge-to-edge, and modal-surface correction

Date: 2026-10-29

Branch: `task4`
Starting HEAD: `abf5bc375985434279cb274a4dfad32696f3e27a` (`feat(android): add Queue actions and shared mutation feedback`)

## Scope

Only the outstanding Batch 6 Android UI corrections and related UI tests/specification were addressed. All modifications already present in the working tree were preserved and included with this follow-up.

## Changes

- Replaced the full-window centered row-action dialog with a compact Material 3 `DropdownMenu` attached to the selected row's trailing three-dot action. It uses a 264dp maximum width, a single centered title line, compact action rows, a theme-aware lavender surface, and explicit dismissal on outside taps and system Back. Existing `QueueMenuPresentation` filtering and `QueueActions` callbacks remain authoritative; actions still identify entries by `queueItemId`.
- Made the Queue artwork backdrop cover the full window behind transparent system bars by moving safe-inset padding to the foreground content only. Queue reuses the existing `PlayerArtworkBackdrop`, current media presentation, app-scoped artwork cache and cache key. No independent playback or artwork state owner was introduced.
- Removed the “队列” heading. The safe-content top row now shows `剩余播放时间：--:--` as an explicit placeholder (no duration is inferred) and right-aligned “清空待播” / “保存歌单” actions with simple line icons.
- Added shared `dialogSurfaceColor()` in `ui/components/DialogSurfaceStyle.kt`. Row menus, clear/save confirmations, output details, and the activity-status modal sheet use the shared theme-aware lavender-tinted surface. The visual design spec now requires this shared surface for present and future dialogs, popup menus, and modal sheets.
- Added tests for the Queue toolbar, compact row popup and outside-tap dismissal, and real-Activity edge-to-edge bounds/safe insets. Tests exercise fake mutation submitters; no live Queue/playback mutation was sent to MS.

## Verification

- `./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` — **BUILD SUCCESSFUL**. Unit tests: **242 passed, 0 failed, 0 errors, 0 skipped**; lint, debug APK assembly, and Android-test Kotlin compilation passed.
- Focused on-device instrumentation on **SM-G986N / Android 13**: `QueueActionsUiTest`, `QueueScreenUiTest`, and `MainActivityEdgeToEdgeUiTest` — **18 passed, 0 failed, 0 errors, 0 skipped**. This includes actual outside-screen touch injection to confirm popup dismissal, toolbar content/no-title assertions, and full-window backdrop plus safe-foreground inset assertions.
- `git diff --check` passed.
- Installed `app/build/outputs/apk/debug/app-debug.apk` with `adb install -r` without uninstalling or clearing app data; ADB returned `Success`. Package `firstInstallTime` remained `2026-10-24 18:47:26`. DataStore preference SHA-256 before and after overlay install was unchanged at `ebe11ce30740f434681d1ac22c99fea61870cdef04cd5164588312c5f815bf45`, preserving the configured server origin. APK SHA-256: `ab8c988db33c893e3ba93c0239435485f991d87e02a40c5ffbb3f96b4b31b515`.
- Launch smoke check completed via `adb shell monkey -p io.github.qyxx2.mpdserver.remote 1`; no matching fatal application exception appeared in the inspected logcat window.

## Boundaries

- No Web, MS server, API contract, database, playback engine, Queue business semantics, or mutation-coordinator implementation was changed.
- No live Queue/playback write was issued during validation. The device's saved server configuration and app data were preserved.
