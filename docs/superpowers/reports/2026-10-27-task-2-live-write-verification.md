# Task 2 — Android Real-Device Live Mutation and Readback Verification

**Date:** 2026-10-27
**Workspace branch:** `feature/task-3-native-app-shell` (continued in place; no branch switch)
**Device:** Samsung SM-G986N, Android 13
**Service:** `http://192.168.3.94:18000`
**Scope:** Finish the Task 2 real-device mutation/readback gate only. No playback, Queue, library-file, or favorite mutation; no server changes; no Task 4 implementation; no merge or push.

## 1. Live service and protocol

Direct read-only probes from the authorized Ubuntu workbench returned:

- `GET /api/health`: HTTP 200; MS healthy, MPD connected, library sync `SYNCED`, 407 confirmed items and no pending/failed items at probe time.
- `GET /api/state`: HTTP 200; complete canonical snapshot returned.
- `GET /api/playlists`: HTTP 200; the actual response shape was `{"items":[],"count":0}`.

The playlist response exposed a contract/decoder mismatch: Android's repository previously decoded `/api/playlists` as a bare `List<PlaylistDto>`, while the live service returns an `items` + `count` envelope.

## 2. Contract correction using TDD

Added `PlaylistListDto` and changed `PlaylistRepository.getPlaylists()` to return `ApiResult<PlaylistListDto>`.

- **RED:** `/tmp/task2-playlist-list-red.log` — the focused `PlaylistListDtoTest` compile failed because `PlaylistListDto` did not exist, as expected before the model change.
- **GREEN:** `/tmp/task2-playlist-list-green.log` — `./gradlew ... testDebugUnitTest --tests '*PlaylistListDtoTest'` completed `BUILD SUCCESSFUL`; both non-empty and empty envelope tests passed.

No server endpoint or wire format was changed.

## 3. Real Android mutation/readback test

Added opt-in instrumentation test:

`app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/runtime/LiveMsMutationCoordinatorTest.kt`

The test was run on the physical SM-G986N after installing both freshly-built APKs. It passed **1/1**:

```sh
adb shell am instrument -w \
  -e class io.github.qyxx2.mpdserver.remote.runtime.LiveMsMutationCoordinatorTest \
  -e liveOrigin http://192.168.3.94:18000 \
  io.github.qyxx2.mpdserver.remote.test/androidx.test.runner.AndroidJUnitRunner
```

Device log: `/tmp/task2-live-write-device.log` — `OK (1 test)`, exit code 0.

The instrumentation ran through the real installed application's `AppRuntime`, `RealtimeController`, shared `SnapshotStore`, `MutationCoordinator`, HTTP transport and playlist repository. It:

1. Injected the live origin into the in-process runtime, connected via Realtime, and waited for an accepted fresh snapshot and write eligibility.
2. Confirmed a UUID-based temporary playlist name did not already exist.
3. Created only that new user playlist via `POST /api/playlists` through the shared `MutationCoordinator`; required an explicit `SUCCESS` activity.
4. Independently re-read `GET /api/playlists` and verified the playlist exists and is not a system playlist.
5. Deleted only the playlist created by this test via `DELETE /api/playlists/{playlist_id}`; required an explicit `SUCCESS` activity from the coordinator.
6. Independently re-read `GET /api/playlists` and verified the playlist was absent. The API contract specifies 204 for this route and unit tests exercise the coordinator's 204 branch; `OperationActivity` does not expose the numeric HTTP status, so this live test asserts its success receipt plus authoritative absence rather than claiming to independently observe the status code.
7. Included a `finally` cleanup guard for the uniquely-named test playlist and restored the runtime's original origin.

The test did not alter playback, queue contents, the music library, or favorites. A final independent server read returned `{"items":[],"count":0}` with HTTP 200, confirming no test playlist remained.

## 4. Fresh complete build and regression verification

Command:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 \
  clean testDebugUnitTest lintDebug assembleDebug \
  compileDebugAndroidTestKotlin assembleDebugAndroidTest
```

Result: **BUILD SUCCESSFUL**, exit code 0; 81 actionable tasks (49 executed, 32 from cache). Log: `/tmp/task2-live-write-build-verify.log`.

- Unit tests: **108 tests**, 0 failures, 0 errors, 0 skipped across 23 suites.
- Both new `PlaylistListDtoTest` cases are included in the 108-test count.
- `lintDebug`, Debug APK build, instrumentation test Kotlin compilation and Android-test APK assembly all passed; Lint reported **0 errors and 11 warnings** (dependency-version suggestions and existing Compose layout API/style findings).
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `d4455352486795a59443266d365751f21a5038b94a942ba30961f7165437b4e8`.
- Instrumentation APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; SHA-256 `9ba783a70b3e99de0e327fc20707d7c09cd69fb2b77a78197ee58d0dc88f0a8d`.
- Package identity verified as `io.github.qyxx2.mpdserver.remote`; the APK signature verified as the expected internal signer (certificate SHA-256 `36c589d3e947dee7f36204d1d19f4e96275159a6a7ff9cd058e5cdd448a2c89f`).
- `git diff --check` passed.

Non-blocking pre-existing build warnings include Gradle deprecations, the experimental AAPT2 override, Android deprecated system-bar APIs, and libraries that could not be stripped. No Lint task failure occurred.

## 5. Acceptance and remaining boundary

**Task 2's outstanding real-device/live-origin/mutation/readback evidence is now recorded as passed for the runtime/coordinator contract.** The installed APK's existing runtime connected to the real MS service and exercised create → authoritative readback → delete success receipt → authoritative absence readback. The route contract documents 204 and the unit suite covers 204 handling; the live activity model does not surface numeric HTTP codes.

This verification injects `liveOrigin` through an instrumentation argument and calls the existing runtime API; it does not add a production settings screen. The ordinary application still defaults to `http://localhost:18000` until the planned server-origin setting is implemented in the settings task. This product UI boundary remains open for its own task and is not misrepresented as covered here.

No changes were merged or pushed. The work remains on the existing Task 3 branch, and pre-existing unrelated Task 3 documentation changes were left untouched.
