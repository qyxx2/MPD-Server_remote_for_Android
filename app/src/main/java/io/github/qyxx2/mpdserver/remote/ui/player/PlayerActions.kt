package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackControlTargetDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID

enum class PlayerAction { PAUSE, RESUME, PREVIOUS, NEXT, SEEK }

data class PlayerActionsState(
    val action: PlayerAction? = null,
    val activeIntentId: String? = null,
    val seekDraftSeconds: Double? = null,
    val seekPreviewSeconds: Double? = null,
    val pending: Boolean = false,
    val syncing: Boolean = false,
    val confirmed: Boolean = false,
    val failed: Boolean = false,
    val unknown: Boolean = false,
    val readFailed: Boolean = false,
    val message: String? = null,
    val receiptUpdatedAt: String? = null,
) {
    val locked: Boolean get() = pending || syncing || unknown
    val displayPositionSeconds: Double? get() = seekDraftSeconds ?: seekPreviewSeconds
}

/**
 * Player UI intent adapter over the one app-owned SnapshotStore and MutationCoordinator.
 * This class owns only transient interaction state; it never owns HTTP, retries or canonical facts.
 */
class PlayerActions internal constructor(
    private val stateProvider: () -> SnapshotStoreState,
    private val activitiesProvider: () -> List<OperationActivity>,
    private val submitter: (MutationIntent) -> MutationOutcome,
    private val retryUnknownSubmitter: (String) -> MutationOutcome,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    /** Mirrors the Web player controlIdentity, deliberately independent of read freshness. */
    private data class ControlIdentity(
        val epoch: String?,
        val generation: Long,
        val boundQueueItemId: String?,
        val targetQueueItemId: String?,
        val targetToken: String?,
        val actualEntryId: Long?,
        val businessSongId: String?,
        val businessContextId: String?,
        val queueCurrentItemId: String?,
    )

    private data class InteractionIdentity(
        val epoch: String?,
        val generation: Long,
        val snapshotGeneration: Long?,
        val queueRevision: Long?,
        val currentOccurrenceId: String?,
        val boundQueueItemId: String?,
        val targetQueueItemId: String?,
        val targetToken: String?,
        val actualEntryId: Long?,
        val freshness: SnapshotFreshness,
        val connection: RuntimeConnectionStatus,
        val controlsEligible: Boolean,
        val playbackFreshness: String?,
        val actualFreshness: String?,
    )

    private val _state = MutableStateFlow(PlayerActionsState())
    val state: StateFlow<PlayerActionsState> = _state.asStateFlow()

    private var latestSnapshot: SnapshotStoreState = stateProvider()
    private var latestActivities: List<OperationActivity> = activitiesProvider()
    private var draftIdentity: InteractionIdentity? = null
    private var activeIdentity: InteractionIdentity? = null
    private var activeControlIdentity: ControlIdentity? = null
    private var seekSampleAtSubmission: String? = null
    private var seekReceiptUpdatedAt: String? = null

    constructor(runtime: AppRuntime) : this(
        stateProvider = { runtime.snapshots.state.value },
        activitiesProvider = { runtime.mutations.activities.value },
        submitter = { runtime.mutations.submit(it) },
        retryUnknownSubmitter = { runtime.mutations.retryUnknown(it) },
    )

    /** Read-only current canonical state for the screen's presentation/clock. */
    fun currentSnapshot(): SnapshotStoreState = stateProvider()

    fun update(snapshot: SnapshotStoreState, activities: List<OperationActivity>) {
        val oldIdentity = identity(latestSnapshot)
        latestSnapshot = snapshot
        latestActivities = activities
        val newIdentity = identity(snapshot)
        val old = _state.value

        if (old.seekDraftSeconds != null &&
            (draftIdentity != newIdentity || !PlayerPresentation.from(snapshot).controls.seek)
        ) {
            clearDraft()
        }

        if (old.action == PlayerAction.SEEK && old.seekPreviewSeconds != null) {
            when {
                activeIdentity != newIdentity -> {
                    _state.value = _state.value.copy(seekPreviewSeconds = null, syncing = false)
                }
                snapshot.freshness != SnapshotFreshness.FRESH ||
                    snapshot.connectionStatus != RuntimeConnectionStatus.CONNECTED -> {
                    _state.value = _state.value.copy(seekPreviewSeconds = null, syncing = false)
                }
                _state.value.confirmed && seekReceiptUpdatedAt != null && isSeekReadbackCovered(snapshot, seekReceiptUpdatedAt!!) -> {
                    _state.value = _state.value.copy(
                        seekPreviewSeconds = null,
                        syncing = false,
                        readFailed = false,
                        message = null,
                    )
                }
            }
        }

        // Keep the immutable intent and its shared coordinator owner alive if the current track
        // moves on; only the presentation draft/preview is retired from that old identity.
        if (oldIdentity != newIdentity && old.seekDraftSeconds == null && old.action == PlayerAction.SEEK) {
            _state.value = _state.value.copy(seekPreviewSeconds = null, syncing = false)
        }

        // Match Web's separate controlIdentity/pendingScope rule: a read-only/stale refresh can
        // retain an UNKNOWN intent while the live connection and original server target remain
        // unchanged. Retire the page interaction only when that target/business scope really moves,
        // or when the Runtime connection/generation is no longer writable. The shared coordinator
        // continues to retain the immutable operation for the global activity sheet.
        if (activeControlIdentity != null &&
            (activeControlIdentity != controlIdentity(snapshot) || !connectionWritable(snapshot))
        ) {
            activeIdentity = null
            activeControlIdentity = null
            draftIdentity = null
            seekSampleAtSubmission = null
            seekReceiptUpdatedAt = null
            _state.value = PlayerActionsState()
        }
        refreshFromActivity()
    }

    fun reconcile() = update(stateProvider(), activitiesProvider())

    fun isEnabled(action: PlayerAction): Boolean {
        val snapshot = stateProvider()
        val presentation = PlayerPresentation.from(snapshot)
        if (isLocked(snapshot)) return false
        return when (action) {
            PlayerAction.PAUSE -> presentation.controls.pause
            PlayerAction.RESUME -> presentation.controls.resume
            PlayerAction.PREVIOUS -> presentation.controls.previous
            PlayerAction.NEXT -> presentation.controls.next
            PlayerAction.SEEK -> presentation.controls.seek
        }
    }

    fun perform(action: PlayerAction, seconds: Double? = null): MutationOutcome? {
        reconcile()
        if (!isEnabled(action)) return null

        val snapshot = stateProvider()
        val presentation = PlayerPresentation.from(snapshot)
        val observation = PlayerFacts.from(snapshot).playbackObservation
        val target = observation?.controlTarget
        if (action == PlayerAction.SEEK) {
            val duration = observation?.durationSeconds
            if (seconds == null || !seconds.isFinite() || duration == null ||
                !duration.isFinite() || duration <= 0.0 || seconds !in 0.0..duration ||
                target == null || target.queueItemId != presentation.currentOccurrenceId
            ) return null
        }
        if ((action == PlayerAction.RESUME || action == PlayerAction.SEEK) &&
            (target == null || target.queueItemId != presentation.currentOccurrenceId || target.token.isBlank())
        ) return null

        val intent = createIntent(action, target, seconds)
        val identity = identity(snapshot)
        activeIdentity = identity
        activeControlIdentity = controlIdentity(snapshot)
        seekSampleAtSubmission = observation?.observedAt
        seekReceiptUpdatedAt = null
        _state.value = PlayerActionsState(
            action = action,
            activeIntentId = intent.id,
            seekPreviewSeconds = if (action == PlayerAction.SEEK) seconds else null,
            pending = true,
        )
        val outcome = submitter(intent)
        applyImmediateOutcome(outcome)
        refreshFromActivity()
        return outcome
    }

    fun beginSeek(): Boolean {
        reconcile()
        if (!isEnabled(PlayerAction.SEEK)) return false
        val snapshot = stateProvider()
        val position = PlayerFacts.from(snapshot).playbackObservation?.positionSeconds
        val duration = PlayerFacts.from(snapshot).playbackObservation?.durationSeconds
        if (position == null || !position.isFinite() || position < 0 ||
            duration == null || !duration.isFinite() || duration <= 0
        ) return false
        draftIdentity = identity(snapshot)
        _state.value = _state.value.copy(seekDraftSeconds = position, seekPreviewSeconds = null)
        return true
    }

    fun cancelSeekDraft() = clearDraft()

    fun previewSeek(seconds: Double) {
        val current = _state.value
        val identity = draftIdentity ?: return
        if (current.seekDraftSeconds == null || identity != identity(stateProvider()) ||
            !isEnabled(PlayerAction.SEEK) || !seconds.isFinite()
        ) {
            clearDraft()
            return
        }
        val duration = PlayerFacts.from(stateProvider()).playbackObservation?.durationSeconds ?: return
        _state.value = current.copy(seekDraftSeconds = seconds.coerceIn(0.0, duration))
    }

    fun finishSeek(): MutationOutcome? {
        val seconds = _state.value.seekDraftSeconds ?: return null
        val baseline = draftIdentity
        clearDraft()
        val current = stateProvider()
        if (baseline == null || baseline != identity(current) || !isEnabled(PlayerAction.SEEK)) {
            reconcile()
            return null
        }
        return perform(PlayerAction.SEEK, seconds)
    }

    fun canRetryUnknown(): Boolean {
        val current = _state.value
        val id = current.activeIntentId ?: return false
        val snapshot = stateProvider()
        if (!current.unknown || !connectionWritable(snapshot) ||
            activeControlIdentity == null || activeControlIdentity != controlIdentity(snapshot)
        ) return false
        return latestActivities.any { it.intentId == id && it.status == MutationStatus.UNKNOWN }
    }

    fun retryUnknown(): MutationOutcome? {
        reconcile()
        val current = _state.value
        val id = current.activeIntentId ?: return null
        if (!canRetryUnknown()) return null
        val outcome = retryUnknownSubmitter(id)
        if (outcome == MutationOutcome.Submitted) {
            _state.value = current.copy(pending = true, unknown = false, failed = false, message = "正在使用原操作重试")
        } else {
            applyImmediateOutcome(outcome)
        }
        refreshFromActivity()
        return outcome
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

    private fun isLocked(snapshot: SnapshotStoreState): Boolean {
        if (!snapshot.controlsEligible) return true
        if (_state.value.locked) return true
        return activitiesProvider().any {
            (it.intentId.startsWith("player:") || it.label.startsWith("播放器 ·")) &&
                it.status in setOf(MutationStatus.PENDING, MutationStatus.SYNCING)
        }
    }

    private fun connectionWritable(snapshot: SnapshotStoreState): Boolean =
        snapshot.snapshot != null &&
            snapshot.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            snapshot.snapshotConnectionGeneration == snapshot.connectionGeneration

    private fun controlIdentity(snapshot: SnapshotStoreState): ControlIdentity? {
        val canonical = snapshot.snapshot ?: return null
        val facts = PlayerFacts.from(snapshot)
        val observation = facts.playbackObservation
        val wirePlayback = runCatching {
            io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json.decodeFromJsonElement<
                io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
            >(canonical.wire.playback)
        }.getOrNull()
        return ControlIdentity(
            epoch = canonical.epoch,
            generation = snapshot.connectionGeneration,
            boundQueueItemId = observation?.boundQueueItemId,
            targetQueueItemId = observation?.controlTarget?.queueItemId,
            targetToken = observation?.controlTarget?.token,
            actualEntryId = observation?.actualCurrent?.entryId,
            businessSongId = wirePlayback?.songId,
            businessContextId = wirePlayback?.context?.toString(),
            queueCurrentItemId = facts.queue?.items?.firstOrNull { it.position == 0 }?.queueItemId,
        )
    }

    private fun identity(snapshot: SnapshotStoreState): InteractionIdentity {
        val facts = PlayerFacts.from(snapshot)
        val observation = facts.playbackObservation
        return InteractionIdentity(
            epoch = snapshot.snapshot?.epoch,
            generation = snapshot.connectionGeneration,
            snapshotGeneration = snapshot.snapshotConnectionGeneration,
            queueRevision = facts.queue?.revision,
            currentOccurrenceId = PlayerPresentation.from(facts).currentOccurrenceId,
            boundQueueItemId = observation?.boundQueueItemId,
            targetQueueItemId = observation?.controlTarget?.queueItemId,
            targetToken = observation?.controlTarget?.token,
            actualEntryId = observation?.actualCurrent?.entryId,
            freshness = snapshot.freshness,
            connection = snapshot.connectionStatus,
            controlsEligible = snapshot.controlsEligible,
            playbackFreshness = observation?.freshness?.name,
            actualFreshness = observation?.actualFreshness?.name,
        )
    }

    private fun isSeekReadbackCovered(snapshot: SnapshotStoreState, updatedAt: String): Boolean {
        if (!snapshot.controlsEligible) return false
        val observation = PlayerFacts.from(snapshot).playbackObservation ?: return false
        if (observation.freshness.name != "fresh" || observation.actualFreshness.name != "fresh") return false
        val observedAt = observation.observedAt ?: return false
        val covers = runCatching { !Instant.parse(observedAt).isBefore(Instant.parse(updatedAt)) }.getOrDefault(false)
        val coveredPlaybackUpdatedAt = observation.coveredPlaybackUpdatedAt
        val playbackUpdatedAt = runCatching {
            snapshot.snapshot?.wire?.playback?.let {
                io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json.decodeFromJsonElement<
                    io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
                >(it).updatedAt
            }
        }.getOrNull()
        return covers || (coveredPlaybackUpdatedAt != null &&
            coveredPlaybackUpdatedAt == updatedAt && playbackUpdatedAt == coveredPlaybackUpdatedAt)
    }

    private fun observationSampleKey(snapshot: SnapshotStoreState): String? =
        PlayerFacts.from(snapshot).playbackObservation?.observedAt

    private fun clearDraft() {
        draftIdentity = null
        _state.value = _state.value.copy(seekDraftSeconds = null)
    }

    private fun applyImmediateOutcome(outcome: MutationOutcome) {
        _state.value = when (outcome) {
            MutationOutcome.Submitted, MutationOutcome.DuplicatePending -> _state.value.copy(pending = true)
            is MutationOutcome.Confirmed -> _state.value.copy(
                pending = false, confirmed = true, unknown = false, failed = false, message = "操作已确认",
            )
            is MutationOutcome.Failed -> _state.value.copy(
                pending = false, failed = true, unknown = false, message = outcome.message,
            )
            is MutationOutcome.Unknown -> _state.value.copy(
                pending = false, unknown = true, failed = false, message = outcome.message,
            )
            MutationOutcome.BlockedOriginChange, MutationOutcome.NotRetryable -> _state.value.copy(
                pending = false, failed = true, message = "无法重试此操作",
            )
        }
    }

    private fun refreshFromActivity() {
        val current = _state.value
        val id = current.activeIntentId ?: return
        latestActivities = activitiesProvider()
        val activity = latestActivities.firstOrNull { it.intentId == id } ?: return
        if (current.action == PlayerAction.SEEK && activity.receiptUpdatedAt != null) {
            seekReceiptUpdatedAt = activity.receiptUpdatedAt
        }
        _state.value = when (activity.status) {
            MutationStatus.PENDING -> current.copy(pending = true, syncing = false, unknown = false, failed = false, message = activity.message)
            MutationStatus.SYNCING -> current.copy(pending = false, syncing = true, unknown = false, failed = false, message = activity.message)
            MutationStatus.UNKNOWN -> current.copy(pending = false, syncing = false, unknown = true, failed = false, message = activity.message ?: "操作结果未知")
            MutationStatus.FAILED -> current.copy(
                pending = false, syncing = false, unknown = false, failed = true,
                readFailed = activity.syncError != null, message = activity.message ?: activity.syncError,
            )
            MutationStatus.SUCCESS -> {
                if (activity.syncError != null) current.copy(
                    pending = false, syncing = false, confirmed = true, unknown = false, failed = false,
                    readFailed = true, seekPreviewSeconds = null, message = "操作已确认 · 状态同步失败：${activity.syncError}",
                ) else if (current.action == PlayerAction.SEEK && current.seekPreviewSeconds != null &&
                    activity.receiptUpdatedAt != null
                ) current.copy(
                    pending = false, syncing = true, confirmed = true, unknown = false, failed = false,
                    readFailed = false, receiptUpdatedAt = activity.receiptUpdatedAt,
                    message = "操作已确认 · 等待新的播放观察",
                ) else current.copy(
                    pending = false, syncing = false, confirmed = true, unknown = false, failed = false,
                    readFailed = false, seekPreviewSeconds = null, receiptUpdatedAt = activity.receiptUpdatedAt,
                    message = "操作已确认",
                )
            }
            MutationStatus.INFO -> current.copy(pending = false, syncing = false, message = activity.message)
        }
    }
}
