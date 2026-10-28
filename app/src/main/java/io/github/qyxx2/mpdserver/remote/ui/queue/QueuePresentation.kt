package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

enum class QueueSnapshotStatus { NO_SNAPSHOT, READY, STALE, INVALID }
enum class QueuePlayedSource { SNAPSHOT, FALLBACK, UNAVAILABLE }

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
            val queueResult = runCatching {
                JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(wire.queue)
            }
            val queue = queueResult.getOrNull()
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
                PlayerPresentation.from(state).playbackStatus
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
