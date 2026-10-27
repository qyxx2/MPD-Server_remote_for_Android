package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotReadTiming
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerClockTest {
    @Test
    fun extrapolatesFreshPlayingSampleForAtMostSixSecondsAndDuplicateSampleDoesNotRenewLease() {
        var now = 1_000L
        val clock = PlayerClock(nowMillis = { now })
        val initial = timed(PlayerTestFixtures.state(), requestedAt = 950, receivedAt = 1_000, sampleAge = 0)
        clock.update(initial)
        assertEquals(12.5, clock.positionSeconds!!, 0.001)

        now += 2_000
        assertEquals(14.5, clock.positionSeconds!!, 0.001)

        now += 8_000
        clock.update(initial)
        assertEquals(18.5, clock.positionSeconds!!, 0.001)
        assertTrue(clock.isLeaseExpired)
    }

    @Test
    fun serverSampleAgeAndReadRttReduceTheSixSecondLease() {
        var now = 1_000L
        val clock = PlayerClock(nowMillis = { now })
        clock.update(timed(PlayerTestFixtures.state(), requestedAt = 900, receivedAt = 1_000, sampleAge = 3_500))

        now += 2_000
        assertEquals(14.5, clock.positionSeconds!!, 0.001)
        now += 2_000
        assertEquals(15.0, clock.positionSeconds!!, 0.001)
        assertTrue(clock.isLeaseExpired)

        // A timing estimate with an unknown age cannot start a clock lease.
        now = 10_000
        val noAge = timed(PlayerTestFixtures.state(), requestedAt = 9_900, receivedAt = 10_000, sampleAge = null)
        clock.update(noAge)
        now += 3_000
        assertEquals(12.5, clock.positionSeconds!!, 0.001)
        assertTrue(clock.isLeaseExpired)
    }

    @Test
    fun pausedSampleDoesNotExtrapolate() {
        var now = 2_000L
        val paused = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val clock = PlayerClock(nowMillis = { now })
        clock.update(timed(PlayerTestFixtures.state(observation = paused), requestedAt = 1_950, receivedAt = 2_000, sampleAge = 0))

        now += 5_000
        assertEquals(12.5, clock.positionSeconds!!, 0.001)
    }

    @Test
    fun staleOrDisconnectedStateCannotKeepAProjectedPosition() {
        val clock = PlayerClock(nowMillis = { 1_000L })
        val initial = timed(PlayerTestFixtures.state(), requestedAt = 950, receivedAt = 1_000, sampleAge = 0)
        clock.update(initial)
        assertEquals(12.5, clock.positionSeconds!!, 0.001)

        clock.update(initial.copy(freshness = SnapshotFreshness.STALE))
        assertNull(clock.positionSeconds)

        clock.update(initial)
        assertEquals(12.5, clock.positionSeconds!!, 0.001)
        clock.update(initial.copy(connectionStatus = RuntimeConnectionStatus.DISCONNECTED))
        assertNull(clock.positionSeconds)
    }

    @Test
    fun latestCanonicalPositionRebasesWithoutRenewingTheSameObservationLease() {
        var now = 1_000L
        val clock = PlayerClock(nowMillis = { now })
        val initial = timed(PlayerTestFixtures.state(), requestedAt = 950, receivedAt = 1_000, sampleAge = 0)
        clock.update(initial)

        val sameSampleDifferentPayload = PlayerTestFixtures.observationJson
            .replace("\"position_seconds\":12.5", "\"position_seconds\":40.0")
        clock.update(PlayerTestFixtures.state(observation = sameSampleDifferentPayload))
        assertEquals(40.0, clock.positionSeconds!!, 0.001)

        now += 3_000
        val nextObservation = PlayerTestFixtures.observationJson
            .replace("\"position_seconds\":12.5", "\"position_seconds\":40.0")
            .replace("\"observed_at\":\"2026-10-27T10:00:01Z\"", "\"observed_at\":\"2026-10-27T10:00:04Z\"")
        clock.update(timed(PlayerTestFixtures.state(observation = nextObservation, capturedAt = "2026-10-27T10:00:04Z"), requestedAt = 3_950, receivedAt = 4_000, sampleAge = 0))
        assertEquals(40.0, clock.positionSeconds!!, 0.001)
        now += 1_000
        assertEquals(41.0, clock.positionSeconds!!, 0.001)
    }

    @Test
    fun noMatchingReadTimingMeansNoInterpolation() {
        var now = 1_000L
        val clock = PlayerClock(nowMillis = { now })
        val snapshotOnly = PlayerTestFixtures.state()
        clock.update(snapshotOnly)
        now += 5_000
        assertEquals(12.5, clock.positionSeconds!!, 0.001)

        clock.update(timed(snapshotOnly, requestedAt = 5_900, receivedAt = 6_000, sampleAge = 0))
        now += 1_000
        assertEquals(13.5, clock.positionSeconds!!, 0.001)
    }

    @Test
    fun backgroundFreezesAndForegroundWaitsForPostForegroundReadWithoutRenewingOldLease() {
        var now = 1_000L
        val clock = PlayerClock(nowMillis = { now })
        val initial = timed(PlayerTestFixtures.state(), requestedAt = 950, receivedAt = 1_000, sampleAge = 0)
        clock.update(initial)
        now += 1_500
        val frozen = clock.positionSeconds!!
        assertEquals(14.0, frozen, 0.001)
        clock.setVisible(false)

        now += 5_000
        assertEquals(frozen, clock.positionSeconds!!, 0.001)
        clock.setVisible(true)
        assertEquals(12.5, clock.positionSeconds!!, 0.001)

        // Foreground refresh returned the same observation. It unblocks display projection but
        // cannot renew the already-expired lease; the displayed base is the latest server value.
        clock.completeForegroundRead(timed(initial.copy(readTiming = null), requestedAt = 7_400, receivedAt = 7_500, sampleAge = 0))
        assertEquals(12.5, clock.positionSeconds!!, 0.001)
        now += 4_000
        clock.update(initial)
        assertEquals(12.5, clock.positionSeconds!!, 0.001)
    }

    private fun timed(
        state: SnapshotStoreState,
        requestedAt: Long,
        receivedAt: Long,
        sampleAge: Long?,
    ): SnapshotStoreState {
        val snapshot = requireNotNull(state.snapshot)
        val observation = JsonConfiguration.json.decodeFromJsonElement<PlaybackObservationDto>(snapshot.wire.playbackObservation)
        return state.copy(
            readTiming = SnapshotReadTiming(
                epoch = snapshot.epoch,
                sequence = snapshot.sequence,
                observedAt = observation.observedAt,
                requestedAtElapsedMillis = requestedAt,
                receivedAtElapsedMillis = receivedAt,
                sampleAgeMillis = sampleAge,
            ),
        )
    }
}
