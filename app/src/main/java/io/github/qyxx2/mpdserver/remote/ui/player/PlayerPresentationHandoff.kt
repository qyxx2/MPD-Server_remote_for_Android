package io.github.qyxx2.mpdserver.remote.ui.player


import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackSyncStatusDto
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Short-lived visual handoff matching Web's bounded display-only presentation fallback.
 *
 * A last-confirmed media projection may bridge only the server's quiet UNKNOWN interval after
 * an already-started action. It is never used for control enablement, action targets, canonical
 * state, freshness, or clock confirmation. All identity and action checks are revalidated here.
 */
internal class PlayerPresentationHandoff(
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val retentionMillis: Long = 6_000L,
) {
    data class Projection(
        val mediaPresentation: PlayerPresentation,
        val isHandoff: Boolean,
        val positionSeconds: Double?,
        /** Monotonic deadline used only to schedule recomposition when retention expires. */
        val expiresAtElapsedMillis: Long?,
    )

    private data class MediaScope(
        val epoch: String,
        val connectionGeneration: Long,
        val libraryRevision: Long,
        val playbackSongId: String?,
        val playbackContext: String?,
        val currentSongId: String?,
        val currentSongFileUri: String?,
        val currentQueueItemId: String?,
    )

    private var retainedPresentation: PlayerPresentation? = null
    private var retainedPositionSeconds: Double? = null
    private var retainedScope: MediaScope? = null
    private var armed = false
    private var wasPending = false
    private var armedIntentId: String? = null
    /** Starts at the first quiet UNKNOWN and is not reset by a brief confirmed recovery. */
    private var handoffDeadlineMillis: Long? = null
    /** Bounds the gap between write confirmation and the first readback observation. */
    private var armedOutcomeDeadlineMillis: Long? = null
    private var handoffActive = false

    fun project(
        snapshot: SnapshotStoreState,
        canonicalPresentation: PlayerPresentation,
        actionState: PlayerActionsState,
        activities: List<OperationActivity> = emptyList(),
    ): Projection {
        val now = nowMillis()
        if (handoffDeadlineMillis?.let { now >= it } == true) {
            clearRetention()
        } else if (handoffDeadlineMillis == null &&
            armedOutcomeDeadlineMillis?.let { now >= it } == true
        ) {
            // No UNKNOWN handoff began within the bounded post-write readback window.
            clearHandoffArm()
        }

        if (actionState.pending && !wasPending) {
            // The app coordinator reports SUCCESS before its follow-up state read finishes. Start
            // from the last confirmed observation, not from that intermediate outcome emission.
            armed = true
            armedIntentId = actionState.activeIntentId
            handoffDeadlineMillis = null
            armedOutcomeDeadlineMillis = null
            handoffActive = false
        }
        if (actionState.activeIntentId != null && actionState.action != null &&
            (actionState.pending || actionState.confirmed || actionState.syncing)
        ) {
            armedIntentId = actionState.activeIntentId
        }
        wasPending = actionState.pending

        val scope = mediaScope(snapshot)
        val facts = PlayerFacts.from(snapshot)
        val observation = facts.playbackObservation
        val currentOccurrenceId = facts.currentOccurrence?.queueItemId
        val hasBoundControlTarget = observation?.controlTarget != null &&
            currentOccurrenceId != null &&
            observation.boundQueueItemId == currentOccurrenceId

        // A newly confirmed readback ends the visible handoff, but if it arrives during the
        // original six-second grace window, keep that same window armed. Server snapshots can
        // briefly alternate confirmed/quiet-UNKNOWN; repeatedly resetting the page to the UNKNOWN
        // placeholder would make metadata flicker at the snapshot cadence.
        if (canonicalPresentation.song != null && hasBoundControlTarget) {
            if (retainedScope != null && retainedScope != scope) {
                // Do not carry metadata across a business track/context/occurrence change.
                clearRetention()
            }
            val recoveredFromHandoff = handoffActive
            retainedPresentation = canonicalPresentation
            retainedPositionSeconds = observation?.positionSeconds?.takeIf { it.isFinite() && it >= 0.0 }
            retainedScope = scope

            if (armed && !hasSuccessfulOrPendingAction(actionState, activities)) {
                clearHandoffArm()
            } else if (recoveredFromHandoff) {
                handoffActive = false
                armedOutcomeDeadlineMillis = null
                return Projection(
                    canonicalPresentation,
                    isHandoff = false,
                    positionSeconds = null,
                    expiresAtElapsedMillis = handoffDeadlineMillis,
                )
            } else if (armed && handoffDeadlineMillis != null) {
                // Preserve the original deadline through intermittent confirmed snapshots.
                return Projection(
                    canonicalPresentation,
                    isHandoff = false,
                    positionSeconds = null,
                    expiresAtElapsedMillis = handoffDeadlineMillis,
                )
            } else if (armed) {
                val operationStillInFlight = actionState.pending || actionState.syncing ||
                    activities.any { it.intentId == armedIntentId &&
                        it.status in setOf(MutationStatus.PENDING, MutationStatus.SYNCING) }
                if (!operationStillInFlight && armedOutcomeDeadlineMillis == null) {
                    // Keep the same-scope display handoff eligible after a fresh readback too:
                    // observation freshness can briefly alternate between confirmed and quiet
                    // UNKNOWN. Start one bounded post-action window; never reset it on each sample.
                    armedOutcomeDeadlineMillis = now + retentionMillis
                }
            }
            return Projection(
                canonicalPresentation,
                isHandoff = false,
                positionSeconds = null,
                expiresAtElapsedMillis = armedOutcomeDeadlineMillis,
            )
        }

        val currentScope = scope
        val canHandoff = armed &&
            retainedPresentation != null &&
            retainedScope == currentScope &&
            isRuntimeWritable(snapshot) &&
            snapshot.readError == null &&
            isQuietUnknown(observation) &&
            hasSuccessfulOrPendingAction(actionState, activities)

        if (!canHandoff) {
            clearRetention()
            return Projection(canonicalPresentation, false, null, null)
        }

        if (handoffDeadlineMillis == null) {
            handoffDeadlineMillis = now + retentionMillis
            armedOutcomeDeadlineMillis = null
        }
        if (now >= requireNotNull(handoffDeadlineMillis)) {
            clearRetention()
            return Projection(canonicalPresentation, false, null, null)
        }
        handoffActive = true
        return Projection(
            mediaPresentation = requireNotNull(retainedPresentation),
            isHandoff = true,
            positionSeconds = retainedPositionSeconds,
            expiresAtElapsedMillis = handoffDeadlineMillis,
        )
    }

    private fun mediaScope(snapshot: SnapshotStoreState): MediaScope? {
        val canonical = snapshot.snapshot ?: return null
        val playback = runCatching {
            JsonConfiguration.json.decodeFromJsonElement<PlaybackStateDto>(canonical.wire.playback)
        }.getOrNull()
        val facts = PlayerFacts.from(snapshot)
        val song = facts.currentSong
        return MediaScope(
            epoch = canonical.epoch,
            connectionGeneration = snapshot.connectionGeneration,
            libraryRevision = canonical.libraryRevision,
            playbackSongId = playback?.songId,
            playbackContext = playback?.context?.toString(),
            currentSongId = song?.songId,
            currentSongFileUri = song?.fileUri,
            currentQueueItemId = facts.queue?.items?.firstOrNull { it.position == 0 }?.queueItemId,
        )
    }

    private fun isRuntimeWritable(snapshot: SnapshotStoreState): Boolean =
        snapshot.snapshot != null &&
            snapshot.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            snapshot.snapshotConnectionGeneration == snapshot.connectionGeneration

    /**
     * PlayerActions can retire its page-local interaction when a server snapshot drops the bound
     * target. The app-scoped activity is the same mutation owner's durable-in-process outcome, so
     * use its original intent ID to distinguish that expected retirement from FAILED/UNKNOWN.
     */
    private fun hasSuccessfulOrPendingAction(
        actionState: PlayerActionsState,
        activities: List<OperationActivity>,
    ): Boolean {
        if (!armed || armedIntentId.isNullOrBlank()) return false
        if (actionState.activeIntentId == armedIntentId) {
            if (actionState.failed || actionState.unknown || actionState.readFailed) return false
            if (actionState.action != null && (actionState.pending || actionState.confirmed)) return true
        }
        val activity = activities.firstOrNull { it.intentId == armedIntentId }
        return when (activity?.status) {
            MutationStatus.PENDING, MutationStatus.SYNCING, MutationStatus.SUCCESS -> true
            MutationStatus.FAILED, MutationStatus.UNKNOWN, MutationStatus.INFO, null -> false
        }
    }

    private fun isQuietUnknown(
        observation: io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto?,
    ): Boolean = observation != null &&
        observation.freshness == ObservationFreshnessDto.unknown &&
        observation.actualFreshness == ObservationFreshnessDto.unknown &&
        observation.actualState == null &&
        observation.actualCurrent == null &&
        observation.boundQueueItemId == null &&
        observation.controlTarget == null &&
        observation.syncStatus == PlaybackSyncStatusDto.UNBOUND &&
        !observation.reconciliationRequired &&
        observation.errorCode.isNullOrBlank() &&
        observation.errorMessage.isNullOrBlank()

    private fun clearHandoffArm() {
        armed = false
        armedIntentId = null
        armedOutcomeDeadlineMillis = null
        handoffDeadlineMillis = null
        handoffActive = false
    }

    private fun clearRetention() {
        retainedPresentation = null
        retainedPositionSeconds = null
        retainedScope = null
        clearHandoffArm()
    }
}
