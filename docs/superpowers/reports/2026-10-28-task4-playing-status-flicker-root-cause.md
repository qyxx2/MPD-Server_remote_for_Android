# Task 4 Batch 3 — Root-cause diagnosis of playing-only fullscreen flicker

Date: 2026-10-28
Branch: `task4`
Status: Root-cause record. The corrective implementation is documented in [the follow-up fix report](2026-10-28-task4-playing-status-flicker-fix.md).

## User-visible symptom reproduced

On the connected SM-G986N / Android 13 device, the full-screen player was in PAUSED state showing `爱爱爱` at approximately 3:23 / 3:33. Triggered Play from the player, then captured 17 device screenshots over approximately 12 seconds while polling the configured MS server's read-only `/api/state` in parallel. The screenshots alternated between the original song/artwork and the `当前曲目未确认` placeholder. The server-side read samples in the first portion reported `PLAYING`, `fresh`, `actual_state=playing`, `sync_status=CONFIRMED`, matching `bound_queue_item_id` and `control_target`; the only UNKNOWN sample in that run occurred at the later song boundary. Captures are under `/tmp/task4-playing-repro/frame_*.png` on the device's workspace host.

A second reproduction while the device's current song was `慢冷` showed the underlying local transition directly. With the server remaining `PLAYING` and its read API returning a fresh confirmed observation, the Android app received an initial full snapshot, then repeatedly logged the same WebSocket decode failure about 0.4–0.6 seconds later. The reconnect cycle repeated as the server sequence advanced 7596, 7597, 7598, 7599, 7600, while the app's accepted initial snapshot was fresh/confirmed on each reconnect.

Runtime evidence is preserved in `.superpowers/sdd/2026-10-27-task-4-full-player-queue/diagnosis-playing-status-flap/runtime-evidence.log`.

## Confirmed root cause: Android/server realtime protocol mismatch

The live server implementation at the checked-out MPD-Server `main` baseline `2ad8445` sends `protocol_version: 1` on both the initial `snapshot` frame and every later `invalidate` frame:

- `server/app/services/realtime_connections.py:58-61`: initial `type=snapshot`, `protocol_version=1`.
- `server/app/services/realtime_connections.py:67-72`: subsequent `type=invalidate`, **also with `protocol_version=1`**.

The Android decoder contradicts that wire contract:

- `app/src/main/java/io/github/qyxx2/mpdserver/remote/protocol/dto/RealtimeFrameDecoder.kt:50-57`: for `type=invalidate`, line 51 rejects any frame containing `protocol_version` with `SerializationException("protocol_version is only valid on the initial snapshot")`.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/RealtimeConnection.kt:101-115`: any decoder exception invalidates the socket and ends the connection as a protocol error.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/runtime/SnapshotStore.kt:84-91`: when the socket status becomes DISCONNECTED, the retained snapshot is marked STALE.
- `app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/PlayerPresentation.kt:193-205` requires fresh snapshot/control eligibility to confirm playback. A stale snapshot therefore projects `ConfirmedPlaybackStatus.UNKNOWN` and loses the confirmed song presentation.
- `PlayerPresentationHandoff` deliberately refuses its media-only fallback when the Runtime is disconnected/not writable, so it cannot hide this real transport disconnect. On reconnect, the initial `snapshot` is accepted and the title/artwork return. The next playback invalidation is rejected again.

This sequence precisely explains why it occurs while playing and why it does not normally occur while paused: the advancing playback produces repeated server invalidations; each one triggers the decoder mismatch and disconnect/reconnect. Pausing removes the recurring playback-time invalidation pattern, so the socket can remain connected. This is not an album-art decoder/cache root cause, and it is not caused by an alternation in the authoritative HTTP `/api/state` observation.

## Decisive runtime log

Repeated on-device diagnostic messages:

`WS_DECODE_FAIL first=true type=SerializationException reason=protocol_version is only valid on the initial snapshot`

Each was immediately followed by:

`CONNECTION old=CONNECTED/FRESH new=DISCONNECTED ...`

and a full-screen projection log equivalent to:

`freshness=STALE controls=false conn=DISCONNECTED ... canonical=UNKNOWN/null obs=fresh/fresh/playing/CONFIRMED`

Thus the server's embedded playback observation was still `fresh/playing/CONFIRMED`; the app itself changed to UNKNOWN because its realtime connection had gone DISCONNECTED and the snapshot became STALE.

## Scope / no-fix guarantee at diagnosis time

- No server playback or library data was changed for the diagnosis. The only live action was starting playback from the paused player to reproduce the reported symptom; all diagnostic state reads were GETs.
- No functional code fix, protocol modification, branch switch, commit, push or merge was performed.
- Temporary `DEBUG-FLAP-9c2e` log probes were removed after diagnosis. `RealtimeConnection.kt` and `SnapshotStore.kt` are byte-for-byte back to their pre-diagnostic Git state. The prior uncommitted Task 4 media-handoff code and tests were preserved untouched.
- The diagnostic APK was overlaid with the same package identity to capture runtime evidence; the installed saved-origin preferences hash remained `86f0fc14d00b146e90ec67736c0103fbeffe9a57ef5309fb2f44aa1460e1666c`. The normal non-instrumented APK was rebuilt and overlaid after capture (`app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `29637ba4d785bbfe7596320d1267db01e737b2b85f70bebf9f5bb33b1d6394ab`). The saved-origin hash remained identical after restoration, the app relaunched without a crash, and no `DEBUG-FLAP-9c2e` source or log probe remains.

At the time this diagnosis was completed, no implementation choice had been made. The follow-up implementation reconciles the Android decoder with the existing server wire contract and adds the exact live-frame regression test; see `2026-10-28-task4-playing-status-flicker-fix.md`.
