package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationHandoffTest {
    private var now = 10_000L
    private fun handoff() = PlayerPresentationHandoff(nowMillis = { now })

    private fun quietUnknownState(
        playback: String = """{"song_id":"song-1","state":"PLAYING","context":null,"position":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
    ) = PlayerTestFixtures.state(
        playback = playback,
        observation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":null")
            .replace("\"actual_current\":{\"entry_id\":5,\"uri\":\"music/example.flac\",\"position\":0}", "\"actual_current\":null")
            .replace("\"actual_freshness\":\"fresh\"", "\"actual_freshness\":\"unknown\"")
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":null")
            .replace("\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}", "\"control_target\":null")
            .replace("\"sync_status\":\"CONFIRMED\"", "\"sync_status\":\"UNBOUND\"")
            .replace("\"matches_current\":true", "\"matches_current\":null")
            .replace("\"position_seconds\":12.5", "\"position_seconds\":null")
            .replace("\"duration_seconds\":123.5", "\"duration_seconds\":null")
            .replace("\"observed_at\":\"2026-10-27T10:00:01Z\"", "\"observed_at\":null")
            .replace("\"freshness\":\"fresh\"", "\"freshness\":\"unknown\""),
    )

    private fun confirmedAction(pending: Boolean = false, confirmed: Boolean = true) =
        PlayerActionsState(
            action = PlayerAction.RESUME,
            activeIntentId = "player:intent-1",
            pending = pending,
            confirmed = confirmed,
        )

    private fun activity(status: MutationStatus) = OperationActivity(
        intentId = "player:intent-1",
        label = "播放器 · 继续播放",
        status = status,
        idempotencyKey = "intent-key",
        origin = ServerOrigin.parse("http://localhost:18000"),
    )

    @Test
    fun bridgesCoordinatorSuccessReadbackGapAndRepeatedQuietUnknownFlapsUntilOriginalDeadline() {
        val tracker = handoff()
        val confirmed = PlayerTestFixtures.state()
        val confirmedPresentation = PlayerPresentation.from(confirmed)

        tracker.project(confirmed, confirmedPresentation, PlayerActionsState())
        tracker.project(
            confirmed,
            confirmedPresentation,
            confirmedAction(pending = true, confirmed = false),
            listOf(activity(MutationStatus.PENDING)),
        )

        // MutationCoordinator publishes SUCCESS before its follow-up snapshot read finishes.
        // The old confirmed sample is still in SnapshotStore at that instant and must not
        // disarm the pending media handoff before the quiet-UNKNOWN read arrives.
        tracker.project(
            confirmed,
            confirmedPresentation,
            confirmedAction(pending = false, confirmed = true),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        // A fresh readback may briefly arrive before the observation oscillates back to quiet
        // UNKNOWN. This same-scope recovery must not disarm the presentation debounce.
        val readbackBeforeFirstUnknown = PlayerTestFixtures.state(
            observation = PlayerTestFixtures.observationJson.replace(
                "\"observed_at\":\"2026-10-27T10:00:01Z\"",
                "\"observed_at\":\"2026-10-27T10:00:02Z\"",
            ),
        )
        val initialReadbackProjection = tracker.project(
            readbackBeforeFirstUnknown,
            PlayerPresentation.from(readbackBeforeFirstUnknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertFalse(initialReadbackProjection.isHandoff)
        assertEquals("Example", initialReadbackProjection.mediaPresentation.song?.title)

        val unknown = quietUnknownState()
        val firstHandoff = tracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertTrue(firstHandoff.isHandoff)
        assertEquals("Example", firstHandoff.mediaPresentation.song?.title)

        // A brief confirmed observation may recover between UNKNOWN samples. It must not
        // clear the original bounded grace period and make the next UNKNOWN frame go blank.
        val recoveredProjection = tracker.project(
            readbackBeforeFirstUnknown,
            PlayerPresentation.from(readbackBeforeFirstUnknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertFalse(recoveredProjection.isHandoff)
        assertEquals("Example", recoveredProjection.mediaPresentation.song?.title)
        assertEquals(firstHandoff.expiresAtElapsedMillis, recoveredProjection.expiresAtElapsedMillis)

        now += 500
        val secondHandoff = tracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertTrue(secondHandoff.isHandoff)
        assertEquals("Example", secondHandoff.mediaPresentation.song?.title)
        assertEquals(firstHandoff.expiresAtElapsedMillis, secondHandoff.expiresAtElapsedMillis)

        now += 5_501
        val expired = tracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertFalse(expired.isHandoff)
        assertNull(expired.mediaPresentation.song)
    }

    @Test
    fun retainsLastConfirmedMediaOnlyForQuietUnknownDuringTheSameSuccessfulAction() {
        val tracker = handoff()
        val confirmed = PlayerTestFixtures.state()
        val confirmedPresentation = PlayerPresentation.from(confirmed)

        tracker.project(confirmed, confirmedPresentation, PlayerActionsState())
        tracker.project(confirmed, confirmedPresentation, confirmedAction(pending = true, confirmed = false))

        val quietUnknown = quietUnknownState()
        val canonicalUnknown = PlayerPresentation.from(quietUnknown)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, canonicalUnknown.playbackStatus)
        assertNull(canonicalUnknown.song)
        assertFalse(canonicalUnknown.controls.resume)

        val projection = tracker.project(
            quietUnknown,
            canonicalUnknown,
            confirmedAction(pending = false, confirmed = true),
        )
        assertTrue(projection.isHandoff)
        assertEquals("Example", projection.mediaPresentation.song?.title)
        assertEquals(12.5, projection.positionSeconds!!, 0.0)
        // The canonical facts remain UNKNOWN even while the previous media is displayed.
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, canonicalUnknown.playbackStatus)
    }

    @Test
    fun handoffSurvivesPlayerActionsRetiringItsPageStateButUsesTheOriginalCoordinatorIntent() {
        val tracker = handoff()
        val confirmed = PlayerTestFixtures.state()
        val confirmedPresentation = PlayerPresentation.from(confirmed)
        tracker.project(confirmed, confirmedPresentation, PlayerActionsState())
        tracker.project(
            confirmed,
            confirmedPresentation,
            confirmedAction(pending = true, confirmed = false),
            listOf(activity(MutationStatus.PENDING)),
        )

        // PlayerActions intentionally retires its local state when the target disappears. The
        // shared coordinator still owns the same operation, so presentation may bridge this
        // quiet-UNKNOWN transition without restoring a target or re-enabling controls.
        val unknown = quietUnknownState()
        val projection = tracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertTrue(projection.isHandoff)
        assertEquals("Example", projection.mediaPresentation.song?.title)
        assertFalse(PlayerPresentation.from(unknown).controls.resume)

        val unknownOutcomeTracker = handoff()
        unknownOutcomeTracker.project(confirmed, confirmedPresentation, PlayerActionsState())
        unknownOutcomeTracker.project(
            confirmed,
            confirmedPresentation,
            confirmedAction(pending = true, confirmed = false),
            listOf(activity(MutationStatus.PENDING)),
        )
        val unknownOutcome = unknownOutcomeTracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            PlayerActionsState(),
            listOf(activity(MutationStatus.UNKNOWN)),
        )
        assertFalse(unknownOutcome.isHandoff)
        assertNull(unknownOutcome.mediaPresentation.song)
    }

    @Test
    fun doesNotRetainMediaWithoutAPendingToConfirmedActionTransition() {
        val tracker = handoff()
        val unknown = quietUnknownState()
        val projection = tracker.project(
            unknown,
            PlayerPresentation.from(unknown),
            confirmedAction(),
        )
        assertFalse(projection.isHandoff)
        assertNull(projection.mediaPresentation.song)
    }

    @Test
    fun retiresHandoffWhenScopeChangesOrActionFailsOrBecomesUnknown() {
        val confirmed = PlayerTestFixtures.state()
        val confirmedPresentation = PlayerPresentation.from(confirmed)
        val unknown = quietUnknownState()

        fun armedTracker() = handoff().also { tracker ->
            tracker.project(confirmed, confirmedPresentation, PlayerActionsState())
            tracker.project(confirmed, confirmedPresentation, confirmedAction(pending = true, confirmed = false))
        }

        val changedPlayback = PlayerTestFixtures.state(
            playback = """{"song_id":"song-2","state":"PLAYING","context":null,"position":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
        )
        val changed = armedTracker().project(
            changedPlayback,
            PlayerPresentation.from(changedPlayback),
            confirmedAction(),
        )
        assertFalse(changed.isHandoff)

        val changedScopeUnknown = quietUnknownState(
            playback = """{"song_id":"song-2","state":"PLAYING","context":null,"position":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
        )
        val noCrossTrackHandoff = armedTracker().project(
            changedScopeUnknown,
            PlayerPresentation.from(changedScopeUnknown),
            confirmedAction(),
            listOf(activity(MutationStatus.SUCCESS)),
        )
        assertFalse(noCrossTrackHandoff.isHandoff)
        assertNull(noCrossTrackHandoff.mediaPresentation.song)

        val failed = armedTracker().project(
            unknown,
            PlayerPresentation.from(unknown),
            confirmedAction().copy(confirmed = false, failed = true, message = "failed"),
        )
        assertFalse(failed.isHandoff)

        val unknownOutcome = armedTracker().project(
            unknown,
            PlayerPresentation.from(unknown),
            confirmedAction().copy(confirmed = false, unknown = true),
        )
        assertFalse(unknownOutcome.isHandoff)
    }

    @Test
    fun retentionExpiresAfterSixSecondsAndDisconnectNeverUsesLastMedia() {
        val confirmed = PlayerTestFixtures.state()
        val unknown = quietUnknownState()

        val tracker = handoff()
        tracker.project(confirmed, PlayerPresentation.from(confirmed), PlayerActionsState())
        tracker.project(confirmed, PlayerPresentation.from(confirmed), confirmedAction(pending = true, confirmed = false))
        assertTrue(tracker.project(unknown, PlayerPresentation.from(unknown), confirmedAction()).isHandoff)

        now += 6_001
        val expired = tracker.project(unknown, PlayerPresentation.from(unknown), confirmedAction())
        assertFalse(expired.isHandoff)
        assertNull(expired.mediaPresentation.song)

        val disconnectTracker = handoff()
        disconnectTracker.project(confirmed, PlayerPresentation.from(confirmed), PlayerActionsState())
        disconnectTracker.project(confirmed, PlayerPresentation.from(confirmed), confirmedAction(pending = true, confirmed = false))
        val disconnected = unknown.copy(connectionStatus = RuntimeConnectionStatus.DISCONNECTED)
        val offline = disconnectTracker.project(
            disconnected,
            PlayerPresentation.from(disconnected),
            confirmedAction(),
        )
        assertFalse(offline.isHandoff)
        assertNull(offline.mediaPresentation.song)
    }

    @Test
    fun staleReadFailureIsNotTheQuietUnknownHandoffCase() {
        val tracker = handoff()
        val confirmed = PlayerTestFixtures.state()
        tracker.project(confirmed, PlayerPresentation.from(confirmed), PlayerActionsState())
        tracker.project(confirmed, PlayerPresentation.from(confirmed), confirmedAction(pending = true, confirmed = false))
        val unknown = quietUnknownState().copy(
            freshness = SnapshotFreshness.STALE,
            readError = "state read failed",
        )
        val projection = tracker.project(unknown, PlayerPresentation.from(unknown), confirmedAction())
        assertFalse(projection.isHandoff)
        assertNull(projection.mediaPresentation.song)
    }
}
