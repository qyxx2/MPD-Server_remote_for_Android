package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.ActualPlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotReadTiming
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState

/**
 * Display-only projection of the canonical server observation. The lease starts from the existing
 * StateReadCoordinator's monotonic receive timing and is reduced by the sample's server age + RTT;
 * wall-clock subtraction between the phone and server is never used. This class never writes state.
 */
class PlayerClock(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val leaseDurationMillis: Long = 6_000L,
) {
    private data class SampleIdentity(
        val epoch: String,
        val generation: Long,
        val snapshotGeneration: Long?,
        val currentOccurrenceId: String?,
        val boundQueueItemId: String?,
        val targetQueueItemId: String?,
        val targetToken: String?,
        val actualEntryId: Long?,
        val observedAt: String?,
    )

    private var state: SnapshotStoreState? = null
    private var presentation: PlayerPresentation? = null
    private var observation: PlaybackObservationDto? = null
    private var identity: SampleIdentity? = null
    private var basePosition: Double? = null
    private var startedAtMillis: Long? = null
    private var expiresAtMillis: Long = 0L
    private var leased: Boolean = false
    private var timingVersion: SnapshotReadTiming? = null
    private var visible: Boolean = true
    private var frozenPosition: Double? = null
    private var awaitingRead: Boolean = false
    private var foregroundAtMillis: Long = Long.MIN_VALUE

    val isLeaseExpired: Boolean
        get() = startedAtMillis != null && nowMillis() >= expiresAtMillis

    val isAwaitingForegroundRead: Boolean
        get() = visible && awaitingRead

    /** Current display estimate. Null means the server has not confirmed a safe position. */
    val positionSeconds: Double?
        get() {
            if (!visible) return frozenPosition
            val current = state ?: return null
            val presentation = presentation ?: return null
            val observation = observation
            confirmedPosition(current, presentation, observation) ?: return null
            var position = basePosition ?: return null
            if (!awaitingRead && presentation.playbackStatus == ConfirmedPlaybackStatus.PLAYING &&
                observation?.actualState == ActualPlaybackStateDto.playing && startedAtMillis != null
            ) {
                val elapsed = (nowMillis().coerceAtMost(expiresAtMillis) - startedAtMillis!!).coerceAtLeast(0L)
                position += elapsed / 1_000.0
            }
            val duration = observation?.durationSeconds
            if (duration != null && duration.isFinite() && duration > 0.0) position = position.coerceAtMost(duration)
            return position
        }

    fun update(next: SnapshotStoreState) {
        val nextFacts = PlayerFacts.from(next)
        val nextPresentation = PlayerPresentation.from(nextFacts)
        val nextObservation = nextFacts.playbackObservation
        val nextIdentity = next.snapshot?.let {
            SampleIdentity(
                epoch = it.epoch,
                generation = next.connectionGeneration,
                snapshotGeneration = next.snapshotConnectionGeneration,
                currentOccurrenceId = nextPresentation.currentOccurrenceId,
                boundQueueItemId = nextObservation?.boundQueueItemId,
                targetQueueItemId = nextObservation?.controlTarget?.queueItemId,
                targetToken = nextObservation?.controlTarget?.token,
                actualEntryId = nextObservation?.actualCurrent?.entryId,
                observedAt = nextObservation?.observedAt,
            )
        }

        // Retain the visible estimate while hidden. This mirrors the Web clock's frozen projection.
        if (!visible && frozenPosition == null && state != null) frozenPosition = positionSeconds
        if (nextIdentity != identity) {
            identity = nextIdentity
            startedAtMillis = null
            expiresAtMillis = 0L
            leased = false
        }
        state = next
        presentation = nextPresentation
        observation = nextObservation
        // Web recalibrates its base from the latest accepted canonical facts on each store update,
        // even when a repeated observation has the same identity. The lease deadline itself never
        // moves forward for that same identity.
        basePosition = confirmedPosition(next, nextPresentation, nextObservation)
        calibrate(next, nextObservation)
    }

    private fun calibrate(next: SnapshotStoreState, observation: PlaybackObservationDto?) {
        val snapshot = next.snapshot ?: return
        val timing = next.readTiming ?: return
        if (!visible || observation == null || timing === timingVersion ||
            timing.epoch != snapshot.epoch || timing.sequence != snapshot.sequence ||
            timing.observedAt != observation.observedAt
        ) return
        timingVersion = timing
        if (timing.requestedAtElapsedMillis < foregroundAtMillis) return

        awaitingRead = false
        val age = timing.sampleAgeMillis
        if (age == null || age < 0L || timing.receivedAtElapsedMillis < 0L) {
            expiresAtMillis = 0L
            return
        }
        val deadline = timing.receivedAtElapsedMillis + (leaseDurationMillis - age).coerceAtLeast(0L)
        if (!leased) {
            leased = true
            expiresAtMillis = deadline
        } else {
            expiresAtMillis = minOf(expiresAtMillis, deadline)
        }
        if (startedAtMillis == null) startedAtMillis = timing.receivedAtElapsedMillis
    }

    /** Foreground uses the shared Runtime reader; this method only applies its resulting state. */
    fun completeForegroundRead(next: SnapshotStoreState) = update(next)

    fun setVisible(value: Boolean) {
        if (value == visible) return
        if (!value) {
            frozenPosition = positionSeconds
            visible = false
            return
        }
        visible = true
        awaitingRead = true
        foregroundAtMillis = nowMillis()
        startedAtMillis = null
        frozenPosition = null
    }

    private fun confirmedPosition(
        current: SnapshotStoreState,
        presentation: PlayerPresentation,
        observation: PlaybackObservationDto?,
    ): Double? {
        if (presentation.playbackStatus == ConfirmedPlaybackStatus.UNKNOWN || !current.controlsEligible) return null
        observation ?: return null
        if (observation.freshness != ObservationFreshnessDto.fresh ||
            observation.actualFreshness != ObservationFreshnessDto.fresh
        ) return null
        return observation.positionSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    }
}
