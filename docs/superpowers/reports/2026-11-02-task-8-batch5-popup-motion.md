# Task 8 / Batch 5 — Shared Popup and Dialog Motion

## Scope

Implement one shared measured-anchor motion contract for application-owned menus and dialogs. Opening uses a 180 ms `FastOutSlowIn` transition from alpha 0 / scale 0.82 to alpha 1 / scale 1. Closing reverses the same transition and retains the Popup/Dialog host until the reverse animation completes. The trigger rectangle is measured in screen coordinates; owner geometry is retained as a fallback after the trigger leaves composition.

## Migrated inventory

| Surface | Host | Anchor owner / trigger |
|---|---|---|
| Song action menu | `PopupMotionHost` + `Popup` | Song action area |
| Queue row overflow menu | `PopupMotionHost` + `Popup` | Queue row owner + overflow control |
| Library Songs sort menu | `PopupMotionHost` + `Popup` | Sort control owner + button |
| Queue clear confirmation | `MotionAlertDialog` | Clear toolbar action |
| Queue save-playlist dialog | `MotionAlertDialog` | Save toolbar action |
| Playlist create | `MotionAlertDialog` | Create action |
| Playlist rename | `MotionAlertDialog` | Rename action |
| Playlist delete | `MotionAlertDialog` | Delete action |
| Playlist add songs | `MotionAlertDialog` | Add-songs action |
| Output details | `MotionAlertDialog` | Output status control |

A fresh source scan found no remaining app-owned calls to `AlertDialog(` or `DropdownMenu(`. The remaining raw `Popup(` calls are the three migrated Popup hosts listed above. `DropdownMenuItem` remains only as an item primitive inside the custom Library sort Popup; it does not create a nested menu host.

## Implementation notes

- `PopupMotion.kt` owns anchor state, screen-space measurement, transform-origin calculation, the 180 ms alpha/scale transition, reverse-close retention, and one-shot close completion.
- `MotionAlertDialog.kt` retains a real Compose `Dialog` window for platform Back, focus, and IME handling. The platform dim is disabled so the custom scrim follows the same motion as the dialog surface. The dialog root applies `imePadding()` and uses the last measured trigger/owner geometry.
- Queue clear/save content is retained while the closing animation runs; business actions and mutation callbacks remain owned by the existing screen state.
- Song menu test fixture now centers its invoking action-area because the test asserts a centered, non-edge-clamped case. Its label-bound queries use the unmerged semantics tree so they inspect individual labels rather than the merged clickable row.

## Verification evidence

Commands were run offline on the connected Samsung SM-G986N / Android 13 device.

| Verification | Result |
|---|---|
| `PopupMotionGeometryTest` | 4 geometry unit tests passed |
| `PopupMotionUiTest` | 3 instrumentation tests passed, including outside-tap and Back-key reverse-close assertions |
| Focused `SongActionMenuUiTest` | 6 instrumentation tests passed |
| Combined focused regression set (`PopupMotionUiTest`, `SongActionMenuUiTest`, `QueueRowMenuUiTest`, `LibraryNavigationUiTest`, `QueueActionsUiTest`, `PlaylistFavoritesUiTest`, `FullScreenPlayerPresentationUiTest`) | 33 instrumentation tests passed, 0 skipped, 0 failed |
| `lintDebug` | Passed |
| `assembleDebug` | Passed / APK assembled |
| `git diff --check` | Passed |

The first full `testDebugUnitTest` attempt encountered a transient DataStore test failure: `UiPreferencesRepositoryTest.allStatusRingPreferencesSurviveRepositoryAndStoreRecreation` threw `IllegalStateException` because two DataStore instances were active for the same temporary preferences file (`FileStorage.kt:52`). A standalone rerun of that test together with `PopupMotionGeometryTest` passed. The subsequent complete unit-suite rerun passed all 499 tests with 0 failures, 0 errors and 0 skips.

## Final status

Batch 5 implementation and verification are complete. Queue save-name entry, queue actions, playlist mutations, player/output presentation and the shared Dialog Back/outside dismissal paths passed their focused connected regressions. The shared dialog host retains the real Compose Dialog window and applies `imePadding()`; existing editable dialog tests exercise text entry through that host.
