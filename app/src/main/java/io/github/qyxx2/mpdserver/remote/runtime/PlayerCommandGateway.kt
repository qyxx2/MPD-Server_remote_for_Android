package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackControlTargetDto
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerFacts
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

data class PlayerCommandAvailability(
    val snapshot: SnapshotStoreState,
    val pause: Boolean = false,
    val resume: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val seek: Boolean = false,
) {
    fun allows(action: PlayerAction): Boolean = when (action) {
        PlayerAction.PAUSE -> pause
        PlayerAction.RESUME -> resume
        PlayerAction.PREVIOUS -> previous
        PlayerAction.NEXT -> next
        PlayerAction.SEEK -> seek
    }

    companion object {
        fun unavailable(snapshot: SnapshotStoreState) = PlayerCommandAvailability(snapshot)
    }
}

data class PlayerCommandSubmission(
    val intent: MutationIntent,
    val outcome: MutationOutcome,
    val snapshot: SnapshotStoreState,
)

/**
 * Application-scoped command owner shared by the in-app player and operating-system controls.
 *
 * It derives capabilities and targets from the current canonical snapshot and serializes player
 * submissions across callers. It owns no network client, playback state, or UI seek-draft state.
 */
class PlayerCommandGateway(
    private val stateProvider: () -> SnapshotStoreState,
    private val activitiesProvider: () -> List<OperationActivity>,
    private val submitter: (MutationIntent) -> MutationOutcome,
    private val retryUnknownSubmitter: (String) -> MutationOutcome,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val commandEligibilityProvider: () -> Boolean = { true },
    private val commandLock: Any = Any(),
) {

    private var activeIntentId: String? = null
    private var activeControlIdentity: PlayerControlIdentity? = null

    /** One atomic snapshot of all player capabilities, including the shared mutation lock. */
    fun availability(): PlayerCommandAvailability = synchronized(commandLock) {
        val snapshot = stateProvider()
        retireObsoleteIntent(snapshot)
        if (!commandEligibilityProvider() || !snapshot.controlsEligible || isLocked(snapshot)) {
            return@synchronized PlayerCommandAvailability.unavailable(snapshot)
        }
        val controls = PlayerPresentation.from(snapshot).controls
        PlayerCommandAvailability(
            snapshot = snapshot,
            pause = controls.pause,
            resume = controls.resume,
            previous = controls.previous,
            next = controls.next,
            seek = controls.seek,
        )
    }

    fun isEnabled(action: PlayerAction): Boolean = availability().allows(action)

    /** Re-checks canonical state and serializes intent creation/submission across all callers. */
    fun dispatch(action: PlayerAction, seconds: Double? = null): PlayerCommandSubmission? =
        synchronized(commandLock) {
            val snapshot = stateProvider()
            retireObsoleteIntent(snapshot)
            if (!canDispatch(action, snapshot)) return@synchronized null

            val presentation = PlayerPresentation.from(snapshot)
            val observation = PlayerFacts.from(snapshot).playbackObservation
            val target = observation?.controlTarget

            if (action == PlayerAction.SEEK) {
                val duration = observation?.durationSeconds
                if (seconds == null || !seconds.isFinite() || duration == null ||
                    !duration.isFinite() || duration <= 0.0 || seconds !in 0.0..duration ||
                    target == null || target.queueItemId != presentation.currentOccurrenceId
                ) return@synchronized null
            }
            if ((action == PlayerAction.RESUME || action == PlayerAction.SEEK) &&
                (target == null || target.queueItemId != presentation.currentOccurrenceId ||
                    target.token.isBlank())
            ) return@synchronized null

            val intent = createIntent(action, target, seconds)
            activeIntentId = intent.id
            activeControlIdentity = PlayerControlIdentity.from(snapshot)
            val outcome = submitter(intent)
            if (outcome !is MutationOutcome.Submitted &&
                outcome !is MutationOutcome.DuplicatePending
            ) {
                val status = activitiesProvider().firstOrNull { it.intentId == intent.id }?.status
                if (status !in setOf(MutationStatus.PENDING, MutationStatus.SYNCING, MutationStatus.UNKNOWN)) {
                    clearActiveIntent()
                }
            }
            PlayerCommandSubmission(intent, outcome, snapshot)
        }

    fun canRetryUnknown(intentId: String): Boolean = synchronized(commandLock) {
        val snapshot = stateProvider()
        commandEligibilityProvider() &&
            activeIntentId == intentId &&
            activeControlIdentity == PlayerControlIdentity.from(snapshot) &&
            connectionWritable(snapshot) &&
            activitiesProvider().any {
                it.intentId == intentId && it.status == MutationStatus.UNKNOWN
            }
    }

    /** Retry the exact UNKNOWN intent; the coordinator retains its original key and payload. */
    fun retryUnknown(intentId: String): MutationOutcome = synchronized(commandLock) {
        val snapshot = stateProvider()
        if (!commandEligibilityProvider() ||
            activeIntentId != intentId ||
            activeControlIdentity != PlayerControlIdentity.from(snapshot) ||
            !connectionWritable(snapshot) ||
            activitiesProvider().none {
                it.intentId == intentId && it.status == MutationStatus.UNKNOWN
            }
        ) return@synchronized MutationOutcome.NotRetryable

        retryUnknownSubmitter(intentId)
    }

    private fun canDispatch(action: PlayerAction, snapshot: SnapshotStoreState): Boolean {
        if (!commandEligibilityProvider() || !snapshot.controlsEligible || isLocked(snapshot)) return false
        val controls = PlayerPresentation.from(snapshot).controls
        return when (action) {
            PlayerAction.PAUSE -> controls.pause
            PlayerAction.RESUME -> controls.resume
            PlayerAction.PREVIOUS -> controls.previous
            PlayerAction.NEXT -> controls.next
            PlayerAction.SEEK -> controls.seek
        }
    }

    private fun isLocked(snapshot: SnapshotStoreState): Boolean {
        val activities = activitiesProvider()
        if (activities.any {
                isPlayerActivity(it) && it.status in setOf(MutationStatus.PENDING, MutationStatus.SYNCING)
            }
        ) return true

        val id = activeIntentId ?: return false
        val activity = activities.firstOrNull { it.intentId == id } ?: return true
        return activity.status == MutationStatus.UNKNOWN &&
            activeControlIdentity == PlayerControlIdentity.from(snapshot)
    }

    private fun retireObsoleteIntent(snapshot: SnapshotStoreState) {
        val id = activeIntentId ?: return
        val activity = activitiesProvider().firstOrNull { it.intentId == id } ?: return
        when (activity.status) {
            MutationStatus.SUCCESS, MutationStatus.FAILED, MutationStatus.INFO -> clearActiveIntent()
            MutationStatus.UNKNOWN -> {
                if (activeControlIdentity != PlayerControlIdentity.from(snapshot) || !connectionWritable(snapshot)) {
                    clearActiveIntent()
                }
            }
            MutationStatus.PENDING, MutationStatus.SYNCING -> Unit
        }
    }

    private fun createIntent(
        action: PlayerAction,
        target: PlaybackControlTargetDto?,
        seconds: Double?,
    ): MutationIntent {
        val path = when (action) {
            PlayerAction.PAUSE -> "/api/playback/pause"
            PlayerAction.RESUME -> "/api/playback/resume"
            PlayerAction.PREVIOUS -> "/api/playback/previous"
            PlayerAction.NEXT -> "/api/playback/next"
            PlayerAction.SEEK -> "/api/playback/seek"
        }
        val payload = when (action) {
            PlayerAction.PAUSE, PlayerAction.PREVIOUS, PlayerAction.NEXT -> "{}"
            PlayerAction.RESUME -> targetPayload(requireNotNull(target))
            PlayerAction.SEEK -> seekPayload(requireNotNull(seconds), requireNotNull(target))
        }
        val label = when (action) {
            PlayerAction.PAUSE -> "播放器 · 暂停"
            PlayerAction.RESUME -> "播放器 · 继续播放"
            PlayerAction.PREVIOUS -> "播放器 · 上一首"
            PlayerAction.NEXT -> "播放器 · 下一首"
            PlayerAction.SEEK -> "播放器 · 调整进度"
        }
        return MutationIntent(
            id = "player:${idFactory()}",
            method = "POST",
            path = path,
            payload = payload,
            label = label,
        )
    }

    private fun targetPayload(target: PlaybackControlTargetDto): String = buildJsonObject {
        putJsonObject("target") {
            put("queue_item_id", target.queueItemId)
            put("token", target.token)
        }
    }.toString()

    private fun seekPayload(seconds: Double, target: PlaybackControlTargetDto): String = buildJsonObject {
        put("seconds", seconds)
        putJsonObject("target") {
            put("queue_item_id", target.queueItemId)
            put("token", target.token)
        }
    }.toString()

    private fun isPlayerActivity(activity: OperationActivity): Boolean =
        activity.intentId.startsWith("player:") || activity.label.startsWith("播放器 ·")

    private fun connectionWritable(snapshot: SnapshotStoreState): Boolean =
        snapshot.snapshot != null &&
            snapshot.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            snapshot.snapshotConnectionGeneration == snapshot.connectionGeneration


    private fun clearActiveIntent() {
        activeIntentId = null
        activeControlIdentity = null
    }
}
