# Task 6 Settings Reorganization and MPD Status Implementation Plan

> **For agentic workers:** Implement this plan task-by-task in the existing `task6` branch. Steps use checkbox syntax. Do not use other programming agents.

**Goal:** Replace the flat Settings screen with Status, Server, Appearance, and About sections; surface the current MPD status from the existing read-only API; compact and correctly position the status-ring preview.

**Architecture:** Keep the existing `AppRuntime`, `UiPreferencesRepository`, `ActivityPresentation`, and `StatusRingPlacementCalculator` as the only state/network/geometry authorities. Split the work into a settings-information-architecture batch and a separately testable MPD-status read/display batch. The MPD status screen is a read-only projection of `GET /api/system/mpd`; it does not become a second state owner.

**Tech Stack:** Kotlin, Jetpack Compose, Navigation Compose, Preferences DataStore, kotlinx.serialization, existing OkHttp/AppRuntime.

**Spec:** `docs/superpowers/specs/2026-11-01-task-6-contract-matrix.md`

## Global Constraints

- Keep `applicationId = io.github.qyxx2.mpdserver.remote`, current signing identity, DataStore file, and existing preference keys.
- Do not uninstall the application or clear application data during device verification; install with `adb install -r`.
- Do not create a second `AppRuntime`, `OkHttpClient`, WebSocket, `MutationCoordinator`, or service-side business state.
- All local preferences remain local; `/api/system/mpd` is read-only and the only MPD status source.
- Do not implement the superseded Task 6 scope for MPD connection-target editing or output-mode controls.
- Preserve Task 4/5 playback, Queue, Library, Home, Search, Playlist, Favorites, and realtime semantics.
- Do not create CI jobs or remote build tasks; use the existing local Gradle and connected-device workflow.

## Review Focus

1. **Origin changes while MPD status is loading:** a late response from the previous origin must not appear as the new server's status. Test in Batch 2 with a delayed mock response and an origin switch.
2. **MPD is disconnected while Android remains connected to MS:** display the MPD connection state independently from Android-to-MS connection/freshness. Test true/false/null cases in Batch 2 and the live server in device verification.
3. **Old persisted vertical offsets are outside the new range:** normalize to the new range without changing preference keys or destroying unrelated preferences. Test in Batch 1 with legacy values at -24dp and +24dp.
4. **Cutout geometry is absent or unreliable:** preview must use the same manual fallback as the production overlay and remain within the current window. Test in Batch 1 at geometry-calculator and UI levels.
5. **Activity retry action is UNKNOWN:** moving the activity view must preserve the original intent ID and retry handler; the new status page must not submit a fresh mutation. Test in Batch 1 with an existing activity fixture.

---

## File Structure

### Batch 1 — Settings information architecture and local preferences

**Create**
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/SettingsSection.kt` — stable section route IDs and titles.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/SettingsHomeScreen.kt` — four category entries.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/StatusSettingsScreen.kt` — full-page projection of the existing activity presentation.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/ServerSettingsScreen.kt` — one-line MS-origin input, help toggle, and save feedback; MPD status panel is added in Batch 2.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/AppearanceSettingsScreen.kt` — theme selection and compact status-ring controls.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/AboutScreen.kt` — app introduction, repository link, author, and BuildConfig version.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/components/ActivityStatusContent.kt` — reusable activity details content extracted from the current modal sheet.

**Modify**
- `ui/navigation/AppDestination.kt` — retain Settings as a root destination and resolve nested Settings routes to it.
- `ui/navigation/AppNavHost.kt` — add category routes, route-aware titles/back handling, move activity content into Status, and host the real-window ring preview while Appearance is active.
- `ui/shell/AppBar.kt` and `ui/shell/AppShell.kt` — remove the global Activity action; add a child-page back affordance without changing the root navigation shell.
- `ui/components/ActivityStatusSheet.kt` — remove duplicated presentation logic or delegate its content to `ActivityStatusContent`; no remaining AppBar entry may open it.
- `ui/components/PlaybackStatusRing.kt` — replace the 92dp preview-card behavior with a non-interactive real-window preview overlay reusing production placement/drawing.
- `ui/components/StatusRingPlacement.kt` — clamp horizontal and vertical offsets against their separate ranges.
- `ui/preferences/StatusRingPreferences.kt` — introduce independent horizontal/vertical offset bounds and steps; vertical range becomes -12dp..+12dp in 1dp steps.
- `ui/preferences/UiPreferencesRepository.kt` — keep the existing four stored keys; normalize legacy vertical values to the new bounds.
- `ui/navigation/AppNavigationUiTest.kt`, `ui/components/StatusRingPlacementTest.kt`, `ui/preferences/StatusRingPreferencesTest.kt`, and `ui/preferences/UiPreferencesRepositoryTest.kt` — update and extend coverage.

### Batch 2 — Authoritative MPD status

**Modify**
- `protocol/dto/SystemDto.kt` — add typed `MpdStatusDto`, `MpdStatsDto`, and database-update state models.
- `data/SystemRepository.kt` — decode `GET /api/system/mpd` into the typed response while keeping the existing route unchanged.
- `runtime/RuntimeReadApi.kt` — add a runtime-owned read method using the existing client and origin provider, plus ResourceMemoryCache’s existing three-slot uncached GET limit; discard a response if the origin changes during the request.
- `ui/settings/ServerSettingsScreen.kt` — show MPD status, loading/error/stale states, and manual refresh.
- `ui/settings/MpdStatusPresentation.kt` — project DTO fields to labels and format seconds/time points.
- `ui/navigation/AppNavHost.kt` — provide the existing runtime read API to Server settings; no second runtime/network owner.
- `runtime/RuntimeReadApiTest.kt`, DTO/formatter/presentation tests, and `ui/navigation/AppNavigationUiTest.kt` — add the focused coverage.

**Create**
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/settings/MpdStatusPresentation.kt` — pure seconds/time-point formatting and display projection.
- `app/src/test/java/io/github/qyxx2/mpdserver/remote/protocol/dto/MpdStatusDtoTest.kt` — strict wire-field decoding fixtures.
- `app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/settings/MpdStatusPresentationTest.kt` — duration, timestamp, missing-value, and error-display boundaries.

---

## Batch 1: Settings information architecture and local preferences

### Task 1.1 — Prove the new settings navigation contract

**Files:** Extend `app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/navigation/AppNavigationUiTest.kt`.

- [ ] Add `settingsRootShowsExactlyFourCategoriesAndNestedPagesReturnToSettings`: verify the four entries, each destination, selected root Settings tab, AppBar category title, back button, and Android back behavior.
- [ ] Add `appBarDoesNotExposeActivityEntryOnAnyRootPage`: verify `activity-details-entry` is absent on Home, Search, Library, and Settings.
- [ ] Run the focused instrumentation class and confirm the new assertions fail against the current single-page settings screen/AppBar action.

Run:
```sh
./gradlew --offline --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavigationUiTest
```
Expected: new hierarchy/removal assertions fail before implementation.

- [ ] Add section route IDs in `ui/settings/SettingsSection.kt`; keep `AppDestination.SETTINGS` as the existing root tab.
- [ ] Update `AppDestination.rootRouteFor` so every `settings/*` route maps to Settings.
- [ ] Add the Settings home destination and Status/Server/Appearance/About child destinations to `AppNavHost`.
- [ ] Remove `onStatusClick` and the Activity button from `AppBar`/`AppShell`; preserve the app bar on normal root screens and the existing full-screen backdrop.
- [ ] Add a child-page title and back affordance; pop only the Settings child route so the Settings root remains selected.

- [ ] Re-run the focused navigation tests; verify four categories, child route titles, selected root tab, and system back.

### Task 1.2 — Move activity details into the Status page

**Files:** `ActivityStatusContent.kt`, `StatusSettingsScreen.kt`, `ActivityStatusSheet.kt`, `AppNavHost.kt`, `AppNavigationUiTest.kt`.

- [ ] Add `statusPageShowsCanonicalConnectionFreshnessAndEmptyActivityState`: use the current runtime state and verify connection, freshness, and the empty activity state.
- [ ] Add `statusPageRetriesUnknownActivityWithOriginalIntentId`: supply an UNKNOWN activity fixture and assert the original retry callback receives the unchanged intent ID.
- [ ] Run the new tests and confirm they fail because the page/content is not yet available.
- [ ] Extract the current activity details body into `ActivityStatusContent`; preserve the same `ActivityPresentation`, list order, errors, messages, and retry affordances.
- [ ] Compose `StatusSettingsScreen` as a full page using the shared content; remove the dependency on opening a ModalBottomSheet from AppBar.
- [ ] Pass the existing retry dispatch from `AppNavHost`; do not create an activity repository/controller or new mutation handler.
- [ ] Verify empty, pending, failed, and UNKNOWN retry presentations with unit/UI tests.

### Task 1.3 — Reorganize server address, appearance, and About

**Files:** `ServerSettingsScreen.kt`, `AppearanceSettingsScreen.kt`, `AboutScreen.kt`, `SettingsHomeScreen.kt`, existing preferences repository and UI tests.

- [ ] Add `serverAddressHelpIconTogglesExistingFormatHint`: help starts hidden, tapping `!` shows the existing IPv4/IPv6/domain/HTTP/port guidance, and tapping again hides it.
- [ ] Add `serverAddressPageHasOnlyOneLineInputAndSaveAction`: verify one single-line field, one Save button, no independent current-origin row, and validation/save feedback still appears.
- [ ] Add `aboutPageShowsRepositoryAndAuthorAndOpensRepositoryLink`: verify app name/introduction/author/version and repository link intent.
- [ ] Run those tests and confirm they fail before implementation.
- [ ] Move the existing origin input and save flow into Server settings. Keep `ServerOrigin.parseAddress`, `onApplyServerOrigin`, and the existing “not yet connectivity-verified” success semantics. Put the hint behind the trailing info icon.
- [ ] Remove the permanent current-config row and permanent support paragraph; retain validation, progress, and save result feedback.
- [ ] Move theme choices and all ring controls into Appearance; keep Follow System/Light/Dark and all four existing ring parameters plus reset.
- [ ] Move existing theme writes and ring writes through `UiPreferencesRepository`; do not change DataStore key names or file name.
- [ ] Add About content with the exact repository URL and author `qyxx2`; use `BuildConfig.VERSION_NAME` for the displayed version and an ACTION_VIEW intent for the repository link.
- [ ] Verify all four pages remain reachable through root Settings and that returning from each child restores the Settings root.

### Task 1.4 — Compact ring settings and real-window preview

**Files:** `StatusRingPreferences.kt`, `StatusRingPlacement.kt`, `PlaybackStatusRing.kt`, `AppNavHost.kt`, `AppearanceSettingsScreen.kt`, unit/UI tests.

- [ ] Add `verticalOffsetUsesOneDpStepAndTwelveDpBounds`: assert min=-12dp, max=+12dp, step=1dp; assert horizontal remains ±24dp/2dp and size/line-width contracts remain unchanged.
- [ ] Add `legacyVerticalOffsetValuesNormalizeWithoutChangingOtherPreferences`: verify old -24dp/+24dp values clamp to -12dp/+12dp, while theme, origin, size, width, and horizontal offset are preserved.
- [ ] Add `previewPlacementMatchesProductionWindowPlacement`: for reliable centered cutout and manual fallback geometry, compare preview and production placement centers/diameters using identical window bounds and draft settings.
- [ ] Run focused preference/placement tests and confirm new range/placement assertions fail before implementation.
- [ ] Separate horizontal and vertical range constants in `StatusRingPreferences`; normalize each axis against its own range/step. Keep the original DataStore keys.
- [ ] Update `StatusRingPlacementCalculator` to apply independent axis bounds; do not change cutout reliability or fallback selection semantics.
- [ ] Replace the four long vertical slider blocks with a compact two-column control layout. Keep every control, its current value, semantics/test tags, sufficient touch target, and reset action.
- [ ] Lift only the unsaved Appearance draft to the existing window-level composition so the preview can be drawn by a sibling overlay that has full window bounds; do not put the preview inside the scroll card or app-bar/content coordinate space.
- [ ] Render the INFO-only preview with the real `StatusRingWindowGeometry`, same placement calculator, and same drawing component. It follows the draft live and is non-clickable; it must not enter the shared runtime/activity list.
- [ ] Show this preview only while Appearance is active. Outside Appearance, preserve the existing real-activity visibility controller and five-second hide behavior.
- [ ] Add an instrumentation assertion that the preview's measured bounds match the production placement around the test device's actual cutout/manual-fallback coordinate path.

### Task 1.5 — Batch 1 regression and commit

- [ ] Run:
```sh
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
```
Expected: BUILD SUCCESSFUL; all unit tests, lint, Debug APK build, and AndroidTest Kotlin compilation pass.
- [ ] Run focused connected tests for Settings navigation, Status activity content/retry, server address input, About link, and Appearance preview.
- [ ] Run Task 4/5 cross-screen regression instrumentation where supported.
- [ ] Verify `git diff --check`; inspect the final diff for preference-key changes, accidental MPD/output controls, new network owners, and removed ring controls.
- [ ] Commit Batch 1 locally:
```sh
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat(settings): reorganize settings categories and appearance"
```
- [ ] Do not push or merge.

---

## Batch 2: Authoritative MPD status

### Task 2.1 — Typed MPD status DTO and endpoint read

**Files:** `SystemDto.kt`, `SystemRepository.kt`, `RuntimeReadApi.kt`, DTO/RuntimeReadApi tests.

- [ ] Add a fixture based on the observed wire shape, including all fields from the contract: version/source, artists/albums/songs, three duration fields, db update timestamp, update state/job ID, connected, errors, observed_at.
- [ ] Add `mpdStatusDtoDecodesCurrentSystemEndpointShape` and `mpdStatusDtoPreservesNullAndDynamicErrorFields`; verify null job ID, absent optional fields, and non-empty dynamic error values are not silently fabricated.
- [ ] Add `getMpdStatusUsesCurrentOriginAndRejectsLatePreviousOriginResponse` using the existing mock HTTP/runtime test pattern; delay the first response, change origin, and assert its data is not accepted for the new origin.
- [ ] Run new DTO/RuntimeReadApi tests and confirm they fail before implementation.
- [ ] Add serializable `MpdStatusDto`, `MpdStatsDto`, and `MpdDatabaseUpdateStatusDto` with the exact nullable property types frozen in contract T6-07a: counts `Long?`, durations `Double?`, db_update epoch `Long?`, errors `Map<String, JsonElement>?`, and job_id `JsonElement?`. Preserve absent/null fields.
- [ ] Change `SystemRepository.getMpd` to typed decoding (or add a typed method while retaining source compatibility if another call site is found).
- [ ] Add `RuntimeReadApi.getMpdStatus()`: use the existing origin provider and OkHttpClient; run the uncached GET through `resources?.readUncached(loader)` to share ResourceMemoryCache’s existing three-request global GET semaphore; capture the request origin, and reject a successful response if the live origin changed before completion.
- [ ] Do not cache the endpoint as a revision-bound Library resource; its values change independently of Library/Playlist revisions.
- [ ] Verify the request is GET-only and does not start another WebSocket, mutate the SnapshotStore, or submit a MutationCoordinator activity.

### Task 2.2 — Format and project the MPD response

**Files:** `ui/settings/MpdStatusPresentation.kt`, unit tests, `ServerSettingsScreen.kt`.

- [ ] Add `mpdDurationsFormatAsDaysHoursMinutesSeconds`: cover zero, sub-minute, exact minute/hour/day boundaries, multi-day duration, and fractional seconds.
- [ ] Add `invalidDurationAndMissingTimestampStayUnknown`: cover null, negative, NaN/infinite, and unparseable timestamps.
- [ ] Add `mpdStatusPresentationSeparatesMpdAndAndroidServerConnectivity`: verify `connected` only describes MS-to-MPD and never derives from Android's WebSocket state.
- [ ] Run formatter/presentation tests and confirm failures before implementation.
- [ ] Implement `formatDurationSeconds(seconds: Double?): String`: floor to whole seconds, then render `X天 X小时 X分 X秒`; do not round fractional seconds. Missing/negative/non-finite values render `未知`, not zero.
- [ ] Format `stats.db_update` as a local date/time from Unix epoch seconds; do not pass it through the duration formatter. Format `observed_at` as a local date/time while preserving an explicit unknown/fallback for invalid input.
- [ ] Map version, MS-to-MPD connection, artist/album/song counts, uptime, database playtime, accumulated playtime, database update state, optional job ID, observation time, and every returned error entry to UI.
- [ ] Keep unknown fields absent/unknown rather than filling zero or success defaults.

### Task 2.3 — Server page loading, refresh, and stale handling

**Files:** `ServerSettingsScreen.kt`, `AppNavHost.kt`, UI tests.

- [ ] Add `serverPageLoadsMpdStatusOnEntryAndRefreshesOnDemand`: verify one read on entry and a second read only after explicit refresh.
- [ ] Add `serverPageMarksSameOriginReadFailureStaleAndClearsOldOriginStatus`: retain same-origin last-known data as stale on failure, but clear it when origin changes.
- [ ] Add `serverPageDisplaysDisconnectedAndMpdErrorsWithoutFalseSuccess`: test connected=false, error entries, update-in-progress, and unknown fields.
- [ ] Run the new UI tests and confirm failure before implementation.
- [ ] Add loading, fresh, stale/error, and unavailable states in the Server page. Provide an explicit refresh action; do not poll in the background.
- [ ] Fetch on Server route entry through `runtime.reads.getMpdStatus()`; use the existing application Runtime and current origin.
- [ ] Cancel or ignore in-flight results when the page leaves or the origin changes. Do not carry old-origin status into the newly configured server.
- [ ] On same-origin refresh failure, preserve last-known data only with an obvious stale/error indication and timestamp. With no prior success, show unavailable/error.
- [ ] Keep the MS address input/save behavior independent from MPD connection state. Never display “MPD connected” based on the Android-to-MS socket.

### Task 2.4 — Live read-only verification, regression, and commit

- [ ] Run:
```sh
./gradlew --offline --no-daemon testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
```
Expected: BUILD SUCCESSFUL.
- [ ] Run focused connected instrumentation for MPD status DTO projection/Server page refresh and the complete Settings navigation suite.
- [ ] Against the user's already configured MS server, perform only `GET /api/system/mpd`; compare version, counts, connection, update state, timestamps, errors, and all duration displays. Do not issue MPD configuration or output writes.
- [ ] Verify that duration strings are based on floor(seconds), that `db_update` is rendered as a date/time, and that null/error values are not shown as zero/connected.
- [ ] Run Task 4/5 regression instrumentation and inspect AppRuntime/WS/mutation counts to ensure no new owner or mutation was added.
- [ ] Verify `git diff --check`.
- [ ] Build and install over the existing package using `adb install -r app/build/outputs/apk/debug/app-debug.apk`; do not uninstall or clear data.
- [ ] Before and after install, compare the saved MS origin and theme/ring preferences. Reopen Server settings and verify the current MPD status read succeeds using the preserved origin.
- [ ] Commit Batch 2 locally:
```sh
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat(settings): display authoritative MPD status"
```
- [ ] Do not push or merge.

## Completion Gate

Task 6 is complete only when every contract T6-01 through T6-10 is covered, both batch commits exist on `task6`, all local verification passes, connected-device installation preserves the existing user data/signature/package, and no Task 4/5 regression is found. User manual visual acceptance remains a separate explicit gate; do not claim it from compilation alone.
