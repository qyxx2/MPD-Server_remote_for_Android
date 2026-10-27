package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputObservationDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Read-only extraction of typed facts from the single app-scoped canonical snapshot. */
data class PlayerFacts(
    val isSnapshotAvailable: Boolean,
    val snapshotFreshness: SnapshotFreshness,
    val connectionStatus: RuntimeConnectionStatus,
    val isSnapshotGenerationCurrent: Boolean,
    val controlsEligible: Boolean,
    val businessPlayback: PlaybackStateDto?,
    val businessPlaybackDecodeFailed: Boolean,
    val queue: QueueSnapshotDto?,
    val queueDecodeFailed: Boolean,
    val currentOccurrence: QueueItemDto?,
    val currentOccurrenceAmbiguous: Boolean,
    val playbackObservation: PlaybackObservationDto?,
    val playbackObservationDecodeFailed: Boolean,
    val currentSong: SongDto?,
    val songDecodeStatus: SongDecodeStatus,
    val output: OutputSnapshotDto?,
    val outputDecodeFailed: Boolean,
    val outputObservation: OutputObservationDto?,
    val outputObservationDecodeFailed: Boolean,
) {
    enum class SongDecodeStatus { ABSENT, VALID, INVALID }

    companion object {
        fun from(state: SnapshotStoreState): PlayerFacts {
            val snapshot = state.snapshot
            if (snapshot == null) {
                return PlayerFacts(
                    isSnapshotAvailable = false,
                    snapshotFreshness = state.freshness,
                    connectionStatus = state.connectionStatus,
                    isSnapshotGenerationCurrent = false,
                    controlsEligible = false,
                    businessPlayback = null,
                    businessPlaybackDecodeFailed = false,
                    queue = null,
                    queueDecodeFailed = false,
                    currentOccurrence = null,
                    currentOccurrenceAmbiguous = false,
                    playbackObservation = null,
                    playbackObservationDecodeFailed = false,
                    currentSong = null,
                    songDecodeStatus = SongDecodeStatus.ABSENT,
                    output = null,
                    outputDecodeFailed = false,
                    outputObservation = null,
                    outputObservationDecodeFailed = false,
                )
            }

            val json = JsonConfiguration.json
            val wire: StateSnapshotDto = snapshot.wire
            val businessPlayback = runCatching { json.decodeFromJsonElement<PlaybackStateDto>(wire.playback) }
            val queue = runCatching { json.decodeFromJsonElement<QueueSnapshotDto>(wire.queue) }
            val playbackObservation = runCatching {
                json.decodeFromJsonElement<PlaybackObservationDto>(wire.playbackObservation)
            }
            val output = runCatching { json.decodeFromJsonElement<OutputSnapshotDto>(wire.output) }
            val outputObservation = runCatching {
                json.decodeFromJsonElement<OutputObservationDto>(wire.outputObservation)
            }

            val songResult = when {
                wire.currentSong == null || wire.currentSong == JsonNull ->
                    SongDecodeStatus.ABSENT to null
                else -> {
                    val decoded = runCatching { json.decodeFromJsonElement<SongDto>(wire.currentSong) }
                    if (decoded.isSuccess) SongDecodeStatus.VALID to decoded.getOrNull()
                    else SongDecodeStatus.INVALID to null
                }
            }

            val currentItems = queue.getOrNull()?.items?.filter { it.position == 0 }.orEmpty()
            val generationCurrent = state.snapshotConnectionGeneration != null &&
                state.snapshotConnectionGeneration == state.connectionGeneration

            return PlayerFacts(
                isSnapshotAvailable = true,
                snapshotFreshness = state.freshness,
                connectionStatus = state.connectionStatus,
                isSnapshotGenerationCurrent = generationCurrent,
                controlsEligible = state.controlsEligible,
                businessPlayback = businessPlayback.getOrNull(),
                businessPlaybackDecodeFailed = businessPlayback.isFailure,
                queue = queue.getOrNull(),
                queueDecodeFailed = queue.isFailure,
                currentOccurrence = currentItems.singleOrNull(),
                currentOccurrenceAmbiguous = currentItems.size > 1,
                playbackObservation = playbackObservation.getOrNull(),
                playbackObservationDecodeFailed = playbackObservation.isFailure,
                currentSong = songResult.second,
                songDecodeStatus = songResult.first,
                output = output.getOrNull(),
                outputDecodeFailed = output.isFailure,
                outputObservation = outputObservation.getOrNull(),
                outputObservationDecodeFailed = outputObservation.isFailure,
            )
        }
    }
}
