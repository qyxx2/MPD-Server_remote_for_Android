package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputObservationDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackObservationDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateValue
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Read-only extraction of typed facts from the single app-scoped canonical snapshot.
 *
 * The canonical snapshot is immutable and shared by the Runtime. A one-entry identity cache lets
 * Player, Queue and action projections reuse its decoded DTOs while a playback-only observation
 * advances. Connection/freshness eligibility remains specific to each SnapshotStoreState wrapper.
 */
data class PlayerFacts(
    val isSnapshotAvailable: Boolean,
    val snapshotFreshness: SnapshotFreshness,
    val connectionStatus: RuntimeConnectionStatus,
    val isSnapshotGenerationCurrent: Boolean,
    val controlsEligible: Boolean,
    val businessPlaybackState: PlaybackStateValue?,
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
        /**
         * Parsed DTOs belong to one immutable FullStateSnapshot object. Keep only the latest object:
         * old snapshots remain correct but are decoded again if a delayed consumer asks for one.
         * This bounds memory and avoids equality/hash work over the 600+ Queue item list.
         */
        private data class DecodedSnapshotFacts(
            val businessPlaybackState: PlaybackStateValue?,
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
        )

        private val decodedSnapshotLock = Any()
        private var cachedSnapshot: FullStateSnapshot? = null
        private var cachedDecodedFacts: DecodedSnapshotFacts? = null

        fun from(state: SnapshotStoreState): PlayerFacts {
            val snapshot = state.snapshot
            if (snapshot == null) {
                return PlayerFacts(
                    isSnapshotAvailable = false,
                    snapshotFreshness = state.freshness,
                    connectionStatus = state.connectionStatus,
                    isSnapshotGenerationCurrent = false,
                    controlsEligible = false,
                    businessPlaybackState = null,
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

            val decoded = decodeSnapshot(snapshot)
            val generationCurrent = state.snapshotConnectionGeneration != null &&
                state.snapshotConnectionGeneration == state.connectionGeneration

            return PlayerFacts(
                isSnapshotAvailable = true,
                snapshotFreshness = state.freshness,
                connectionStatus = state.connectionStatus,
                isSnapshotGenerationCurrent = generationCurrent,
                controlsEligible = state.controlsEligible,
                businessPlaybackState = decoded.businessPlaybackState,
                businessPlaybackDecodeFailed = decoded.businessPlaybackDecodeFailed,
                queue = decoded.queue,
                queueDecodeFailed = decoded.queueDecodeFailed,
                currentOccurrence = decoded.currentOccurrence,
                currentOccurrenceAmbiguous = decoded.currentOccurrenceAmbiguous,
                playbackObservation = decoded.playbackObservation,
                playbackObservationDecodeFailed = decoded.playbackObservationDecodeFailed,
                currentSong = decoded.currentSong,
                songDecodeStatus = decoded.songDecodeStatus,
                output = decoded.output,
                outputDecodeFailed = decoded.outputDecodeFailed,
                outputObservation = decoded.outputObservation,
                outputObservationDecodeFailed = decoded.outputObservationDecodeFailed,
            )
        }

        private fun decodeSnapshot(snapshot: FullStateSnapshot): DecodedSnapshotFacts =
            synchronized(decodedSnapshotLock) {
                if (cachedSnapshot === snapshot) {
                    return@synchronized requireNotNull(cachedDecodedFacts)
                }

                val json = JsonConfiguration.json
                val wire = snapshot.wire
                val businessPlaybackState = runCatching {
                    val state = wire.playback.jsonObject["state"]
                        ?: throw IllegalArgumentException("playback.state is missing")
                    json.decodeFromJsonElement<PlaybackStateValue>(state)
                }
                val queue = runCatching {
                    json.decodeFromJsonElement<QueueSnapshotDto>(wire.queue)
                }
                val playbackObservation = runCatching {
                    json.decodeFromJsonElement<PlaybackObservationDto>(wire.playbackObservation)
                }
                val output = runCatching {
                    json.decodeFromJsonElement<OutputSnapshotDto>(wire.output)
                }
                val outputObservation = runCatching {
                    json.decodeFromJsonElement<OutputObservationDto>(wire.outputObservation)
                }

                val songResult = when {
                    wire.currentSong == null || wire.currentSong == JsonNull ->
                        SongDecodeStatus.ABSENT to null
                    else -> {
                        val decoded = runCatching {
                            json.decodeFromJsonElement<SongDto>(wire.currentSong)
                        }
                        if (decoded.isSuccess) {
                            SongDecodeStatus.VALID to decoded.getOrNull()
                        } else {
                            SongDecodeStatus.INVALID to null
                        }
                    }
                }

                val currentItems = queue.getOrNull()?.items
                    ?.filter { it.position == 0 }
                    .orEmpty()
                val decoded = DecodedSnapshotFacts(
                    businessPlaybackState = businessPlaybackState.getOrNull(),
                    businessPlaybackDecodeFailed = businessPlaybackState.isFailure,
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
                cachedSnapshot = snapshot
                cachedDecodedFacts = decoded
                decoded
            }
    }
}
