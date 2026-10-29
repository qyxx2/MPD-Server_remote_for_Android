package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerFacts
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class QueueSnapshotStatus { NO_SNAPSHOT, READY, STALE, INVALID }
enum class QueuePlayedSource { SNAPSHOT, FALLBACK, UNAVAILABLE }

/**
 * Cheap invalidation key for Queue presentation work. Playback clock ticks and observation timestamps
 * do not change the list's contents or current-row label; queue revision, autoplay, confirmation
 * semantics and connection freshness do. Keeping those dimensions explicit prevents each realtime
 * position update from rebuilding hundreds of row models and restarting scroll prefetch effects.
 */
internal data class QueuePresentationCacheKey(
    val epoch: String?,
    val queueRevision: Long?,
    val libraryRevision: Long?,
    val connectionGeneration: Long,
    val snapshotConnectionGeneration: Long?,
    val connectionStatus: RuntimeConnectionStatus,
    val freshness: SnapshotFreshness,
    val controlsEligible: Boolean,
    val autoplayEnabled: Boolean,
    val actualState: String?,
    val observationFreshness: String?,
    val actualFreshness: String?,
    val matchesCurrent: Boolean?,
    val boundQueueItemId: String?,
    val syncStatus: String?,
) {
    companion object {
        fun from(state: SnapshotStoreState): QueuePresentationCacheKey {
            val snapshot = state.snapshot
            val playback = snapshot?.wire?.playback as? JsonObject
            val observation = snapshot?.wire?.playbackObservation as? JsonObject
            fun stringField(owner: JsonObject?, name: String): String? =
                owner?.get(name)?.let { value ->
                    runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
                }
            fun booleanField(owner: JsonObject?, name: String): Boolean? =
                (owner?.get(name) as? JsonPrimitive)?.let { value ->
                    if (value.isString) null else value.booleanOrNull
                }

            return QueuePresentationCacheKey(
                epoch = snapshot?.epoch,
                queueRevision = snapshot?.queueRevision,
                libraryRevision = snapshot?.libraryRevision,
                connectionGeneration = state.connectionGeneration,
                snapshotConnectionGeneration = state.snapshotConnectionGeneration,
                connectionStatus = state.connectionStatus,
                freshness = state.freshness,
                controlsEligible = state.controlsEligible,
                autoplayEnabled = booleanField(playback, "autoplay_enabled") == true,
                actualState = stringField(observation, "actual_state"),
                observationFreshness = stringField(observation, "freshness"),
                actualFreshness = stringField(observation, "actual_freshness"),
                matchesCurrent = booleanField(observation, "matches_current"),
                boundQueueItemId = stringField(observation, "bound_queue_item_id"),
                syncStatus = stringField(observation, "sync_status"),
            )
        }
    }
}

/** Read-only projection of one canonical snapshot. It never mutates or reconstructs Queue occurrences. */
data class QueuePresentation(
    val snapshotStatus: QueueSnapshotStatus,
    val epoch: String?,
    val queueRevision: Long?,
    val libraryRevision: Long?,
    val connectionGeneration: Long,
    val current: QueueItemDto?,
    val currentConfirmed: Boolean,
    val currentLabel: String,
    val pending: List<QueueItemDto>,
    val next: List<QueueItemDto>,
    val played: List<QueueItemDto>,
    val playedSource: QueuePlayedSource,
) {
    /** B5 starts at the business current and appends Web's next candidate(s); B8 adds Played reveal. */
    val visibleRows: List<QueueItemDto>
        get() = buildList {
            current?.let(::add)
            addAll(next)
        }

    /** Apply the existing Played fallback without decoding/re-deriving the canonical Queue again. */
    fun withPlayedFallback(playedFallback: List<QueueItemDto>?): QueuePresentation {
        if (playedSource == QueuePlayedSource.SNAPSHOT || playedFallback == null) return this
        return copy(
            played = playedFallback.filter { it.position < 0 }.sortedByDescending { it.position },
            playedSource = QueuePlayedSource.FALLBACK,
        )
    }

    companion object {
        fun deriveQueue(
            state: SnapshotStoreState,
            playedFallback: List<QueueItemDto>? = null,
        ): QueuePresentation {
            val fullSnapshot = state.snapshot
            if (fullSnapshot == null) {
                val fallback = playedFallback
                    ?.filter { it.position < 0 }
                    ?.sortedByDescending { it.position }
                    .orEmpty()
                return QueuePresentation(
                    snapshotStatus = QueueSnapshotStatus.NO_SNAPSHOT,
                    epoch = null,
                    queueRevision = null,
                    libraryRevision = null,
                    connectionGeneration = state.connectionGeneration,
                    current = null,
                    currentConfirmed = false,
                    currentLabel = "当前业务条目 · 实际播放未确认",
                    pending = emptyList(),
                    next = emptyList(),
                    played = fallback,
                    playedSource = if (playedFallback == null) QueuePlayedSource.UNAVAILABLE else QueuePlayedSource.FALLBACK,
                )
            }

            val wire: StateSnapshotDto = fullSnapshot.wire
            // Reuse PlayerFacts' decoded canonical Queue rather than separately decoding wire.queue
            // here and then decoding it again inside PlayerPresentation.from(state).
            val facts = PlayerFacts.from(state)
            val queue = facts.queue
            val status = when {
                queue == null -> QueueSnapshotStatus.INVALID
                state.freshness == SnapshotFreshness.FRESH && state.controlsEligible -> QueueSnapshotStatus.READY
                else -> QueueSnapshotStatus.STALE
            }
            val items = queue?.items.orEmpty()
            val current = items.firstOrNull { it.position == 0 }
            val pending = items.filter { it.position > 0 }.sortedBy { it.position }
            val manual = pending.filter { it.source.name == "MANUAL" }
            // Mirror Web's direct snapshot.playback.autoplay_enabled lookup. Decoding the full
            // legacy PlaybackStateDto here rejects the canonical wire fields (position_seconds and
            // playback_context_id) under the app's strict JsonConfiguration, silently hiding autoplay.
            val autoplayEnabled = (wire.playback as? JsonObject)
                ?.get("autoplay_enabled")
                ?.let { value ->
                    (value as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true }
                } == true
            val next = when {
                manual.isNotEmpty() -> manual
                current != null && autoplayEnabled -> pending.filter { it.source.name == "AUTOPLAY" }.take(1)
                else -> emptyList()
            }

            // Reuse the already-tested B1 projection: playback.state alone cannot confirm a current.
            val playbackStatus = if (queue == null) {
                ConfirmedPlaybackStatus.UNKNOWN
            } else {
                PlayerPresentation.from(facts).playbackStatus
            }
            val confirmed = current != null && playbackStatus != ConfirmedPlaybackStatus.UNKNOWN &&
                items.count { it.position == 0 } == 1
            val currentLabel = when {
                !confirmed -> "业务当前 · 实际播放未确认"
                playbackStatus == ConfirmedPlaybackStatus.PAUSED -> "已暂停"
                else -> "正在播放"
            }

            val canonicalPlayedTrusted = queue != null && state.controlsEligible
            val played: List<QueueItemDto>
            val playedSource: QueuePlayedSource
            if (canonicalPlayedTrusted) {
                played = items.filter { it.position < 0 }.sortedByDescending { it.position }
                playedSource = QueuePlayedSource.SNAPSHOT
            } else if (playedFallback != null) {
                played = playedFallback.filter { it.position < 0 }.sortedByDescending { it.position }
                playedSource = QueuePlayedSource.FALLBACK
            } else {
                played = emptyList()
                playedSource = QueuePlayedSource.UNAVAILABLE
            }

            return QueuePresentation(
                snapshotStatus = status,
                epoch = fullSnapshot.epoch,
                queueRevision = queue?.revision ?: fullSnapshot.queueRevision,
                libraryRevision = fullSnapshot.libraryRevision,
                connectionGeneration = state.connectionGeneration,
                current = current,
                currentConfirmed = confirmed,
                currentLabel = currentLabel,
                pending = pending,
                next = next,
                played = played,
                playedSource = playedSource,
            )
        }
    }
}
