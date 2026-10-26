# Task 3 Batch 5B — Status Ring Settings Verification

**Date:** 2026-10-26
**Branch:** `feature/task-3-native-app-shell`
**Workspace:** `/home/Gold/WorkSpace/MS_remote_for_Android`
**Base:** `fee73e5` (`feat(android): implement task 3 batch 5a status ring placement`)
**Scope:** Batch 5B only. No push, merge, Batch 6 work, service/API changes, or programming-agent use.

## Implementation

Added four local appearance preferences in `StatusRingPreferences`: size scale (`1.00×`, `0.70×–1.40×`, step `0.05×`), line width (`3.0dp`, `2.0–5.0dp`, step `0.5dp`), and horizontal/vertical offsets (each `0dp`, `−24dp–+24dp`, step `2dp`). The defaults and all ranges are centralized in that model and referenced by the placement settings and UI. Values are clamped/snapped; non-finite inputs revert to their respective defaults.

Extended the existing Preferences DataStore repository to read and atomically write the four values, including an explicit persisted reset. Replaced only the Settings placeholder destination with four labelled controls, a live preview, and “恢复默认值”. The real ring renderer and preview share the Batch 5A placement calculator and draw code. The preview uses a fixed UI-local `INFO` sample and does not write to or mutate the application `Runtime`, canonical snapshot, connection state, or shared activity collection. Preference writes use an Application-owned coroutine scope so leaving Settings does not cancel an in-flight write.

## TDD and automated verification

- RED evidence: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5b-red.log` records the expected unresolved preference model/repository and updated navigation wiring before implementation.
- Final unit run: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5b-unit-final.log` — **105 unit tests, 0 failures, 0 errors, 0 skipped**.
- Android-device verification build: `.superpowers/sdd/2026-10-25-task-3-native-app-shell/task-3-batch5b-deviceverify.log` — `lintDebug`, `assembleDebug`, Android-test Kotlin compilation, and `connectedDebugAndroidTest` all completed successfully. **10 instrumentation tests, 0 failures, 0 errors, 0 skipped** on SM-G986N / Android 13.
- `git diff --check` passed. Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (**12,041,958 bytes** at final check).
- Tests cover frozen defaults and range snapping/clamping, non-finite inputs, repository defaults, persisted values across DataStore/store reconstruction, reset persistence, and fallback calibration with stored offsets/scale. The instrumented UI tests exercise all four sliders, live preview values, unchanged Runtime/activity state, Settings leave/re-entry persistence, and reset.

## Physical-device evidence

Device: Samsung SM-G986N, Android 13; app window override `1080×2400`, density override `420dpi`.

- `device-evidence/batch5b-settings-ui.xml` and `device-evidence/batch5b-settings.png`: Settings contains the four controls, spec ranges/steps, preview description, and reset action.
- `device-evidence/batch5b-all-adjusted-ui.xml` and `device-evidence/batch5b-all-adjusted.png`: direct touch input changed all four controls to **`1.15×`, `4.5dp`, `10dp`, `−6dp`** and the preview accessibility description immediately reflected all four values.
- `device-evidence/batch5b-four-pref-restart-ui.xml`: after force-stopping and relaunching the app, those same four values and preview description were restored from DataStore.
- `device-evidence/batch5b-reenter-settings-ui.xml`: the earlier size adjustment also survived leaving and reopening Settings.
- `device-evidence/batch5b-final-reset-ui.xml` and `device-evidence/batch5b-final-reset.png`: tapping reset returned all four controls and the preview to **`1.00×`, `3.0dp`, `0dp`, `0dp`**.

## Boundaries and remaining device gate

Batch 5B implementation and its available automated checks are complete. The UI preview is intentionally a local visual sample, not proof of an active real operation. No usable MS service/real shared active operation is available in the current device setup, so the active production ring around the physical camera cutout, actual activity-driven five-second hide/reset, and unresolved `UNKNOWN`/`FAILED` visuals remain unverified on-device, as recorded by Batch 5A. This device also cannot provide a physical no-cutout fallback scenario. The placement calculator’s manual-fallback offset/scale path is covered by deterministic unit tests, but the **complete physical manual-calibration gate remains BLOCKED** until a controlled no-reliable-cutout device/condition and a real active-ring visual path are available. This report does not claim overall A+B status-ring visual acceptance.
