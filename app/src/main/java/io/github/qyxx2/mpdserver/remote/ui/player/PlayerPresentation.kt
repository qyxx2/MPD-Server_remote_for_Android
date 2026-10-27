package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.ActualPlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputModeDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.OutputStatusDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateValue
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackSyncStatusDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import java.util.Locale

enum class ConfirmedPlaybackStatus { PLAYING, PAUSED, UNKNOWN }
enum class SongMetadataStatus { AVAILABLE, MISSING, INVALID, OCCURRENCE_MISMATCH }
enum class SourceFormatStatus { COMPLETE, PARTIAL, UNKNOWN }
enum class LyricsResourceStatus { AVAILABLE, MISSING, READ_ERROR, UNKNOWN }
enum class ArtworkReferenceStatus { REFERENCED, MISSING, UNKNOWN }
enum class OutputObservationStatus { FRESH, STALE, UNKNOWN, ERROR }
enum class DacOutputStatus { ACTIVE, INACTIVE, UNAVAILABLE, NOT_PRESENT, STALE, UNKNOWN, ERROR }

enum class PlayerDiagnostic {
    NO_SNAPSHOT,
    SNAPSHOT_NOT_FRESH,
    SNAPSHOT_GENERATION_MISMATCH,
    CONNECTION_NOT_CONNECTED,
    QUEUE_INVALID,
    CURRENT_OCCURRENCE_MISSING,
    CURRENT_OCCURRENCE_AMBIGUOUS,
    PLAYBACK_OBSERVATION_INVALID,
    PLAYBACK_OBSERVATION_NOT_FRESH,
    ACTUAL_OBSERVATION_NOT_FRESH,
    CURRENT_NOT_MATCHED,
    BOUND_OCCURRENCE_MISMATCH,
    SYNC_NOT_CONFIRMED,
    ACTUAL_STATE_NOT_PLAYING_OR_PAUSED,
    CONTROL_TARGET_MISSING_OR_MISMATCH,
    CURRENT_SONG_MISSING,
    CURRENT_SONG_INVALID,
    CURRENT_SONG_OCCURRENCE_MISMATCH,
    LYRICS_STATUS_UNKNOWN,
    OUTPUT_INVALID,
    OUTPUT_OBSERVATION_INVALID,
    OUTPUT_OBSERVATION_NOT_FRESH,
    OUTPUT_STATE_MISSING,
    OUTPUT_STATE_NOT_FRESH,
}

data class SourceFormatPresentation(
    val status: SourceFormatStatus,
    val codec: String?,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
)

data class PlayerSongPresentation(
    val songId: String,
    val title: String?,
    val fileUri: String,
    val artists: List<String>,
    val albumId: String?,
    val album: String?,
    val albumArtists: List<String>,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationSeconds: Double?,
    val lyrics: String?,
    val lyricsFormat: String?,
    val lyricsSource: String?,
    val lyricsStatus: LyricsResourceStatus,
    val rawLyricsStatus: String,
    val artworkReferenceStatus: ArtworkReferenceStatus,
)

data class PlayerControlCapabilities(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
    val seek: Boolean = false,
)

data class OutputPresentation(
    val observationStatus: OutputObservationStatus,
    /** NAS_DAC state reported by the server; this proves neither physical sound nor bit-perfect output. */
    val dacStatus: DacOutputStatus,
    val dacFormat: String?,
    val dacSampleRateHz: Int?,
    val dacBitDepth: Int?,
    val dacChannels: Int?,
    val errorCode: String?,
    val errorMessage: String?,
    val diagnostics: List<PlayerDiagnostic>,
) {
    val describesBitPerfectOrPhysicalPlayback: Boolean get() = false
}

data class PlayerPresentation(
    val playbackStatus: ConfirmedPlaybackStatus,
    val actualState: ActualPlaybackStateDto?,
    val businessPlaybackState: PlaybackStateValue?,
    val playbackDiagnostics: List<PlayerDiagnostic>,
    val currentOccurrenceId: String?,
    val songStatus: SongMetadataStatus,
    val song: PlayerSongPresentation?,
    val artworkReferenceStatus: ArtworkReferenceStatus,
    val sourceFormat: SourceFormatPresentation,
    val output: OutputPresentation,
    val controls: PlayerControlCapabilities,
    val resourceDiagnostics: List<PlayerDiagnostic>,
) {
    companion object {
        fun from(state: SnapshotStoreState): PlayerPresentation = from(PlayerFacts.from(state))

        fun from(facts: PlayerFacts): PlayerPresentation {
            val playbackDiagnostics = linkedSetOf<PlayerDiagnostic>()
            val resourceDiagnostics = linkedSetOf<PlayerDiagnostic>()
            val current = facts.currentOccurrence
            val observation = facts.playbackObservation
            val decodedSong = facts.currentSong
            // A matching song_id alone is insufficient: duplicate Queue occurrences must also
            // match the observation's server-owned queue_item_id binding.
            val songMatchesCurrent = current != null &&
                !decodedSong?.songId.isNullOrBlank() &&
                decodedSong?.songId == current.songId &&
                observation?.boundQueueItemId == current.queueItemId
            val selectedSong = decodedSong?.takeIf { songMatchesCurrent }

            if (!facts.isSnapshotAvailable) playbackDiagnostics += PlayerDiagnostic.NO_SNAPSHOT
            if (facts.snapshotFreshness != SnapshotFreshness.FRESH || !facts.controlsEligible) {
                playbackDiagnostics += PlayerDiagnostic.SNAPSHOT_NOT_FRESH
            }
            if (facts.connectionStatus != RuntimeConnectionStatus.CONNECTED) {
                playbackDiagnostics += PlayerDiagnostic.CONNECTION_NOT_CONNECTED
            }
            if (facts.isSnapshotAvailable && !facts.isSnapshotGenerationCurrent) {
                playbackDiagnostics += PlayerDiagnostic.SNAPSHOT_GENERATION_MISMATCH
            }
            if (facts.queueDecodeFailed) playbackDiagnostics += PlayerDiagnostic.QUEUE_INVALID
            if (facts.currentOccurrenceAmbiguous) {
                playbackDiagnostics += PlayerDiagnostic.CURRENT_OCCURRENCE_AMBIGUOUS
            } else if (current == null) {
                playbackDiagnostics += PlayerDiagnostic.CURRENT_OCCURRENCE_MISSING
            }
            if (facts.playbackObservationDecodeFailed) {
                playbackDiagnostics += PlayerDiagnostic.PLAYBACK_OBSERVATION_INVALID
            }

            if (observation == null) {
                playbackDiagnostics += PlayerDiagnostic.PLAYBACK_OBSERVATION_NOT_FRESH
                playbackDiagnostics += PlayerDiagnostic.ACTUAL_OBSERVATION_NOT_FRESH
                playbackDiagnostics += PlayerDiagnostic.ACTUAL_STATE_NOT_PLAYING_OR_PAUSED
            } else {
                if (observation.freshness != ObservationFreshnessDto.fresh) {
                    playbackDiagnostics += PlayerDiagnostic.PLAYBACK_OBSERVATION_NOT_FRESH
                }
                if (observation.actualFreshness != ObservationFreshnessDto.fresh) {
                    playbackDiagnostics += PlayerDiagnostic.ACTUAL_OBSERVATION_NOT_FRESH
                }
                if (observation.matchesCurrent != true) {
                    playbackDiagnostics += PlayerDiagnostic.CURRENT_NOT_MATCHED
                }
                if (current == null || observation.boundQueueItemId != current.queueItemId) {
                    playbackDiagnostics += PlayerDiagnostic.BOUND_OCCURRENCE_MISMATCH
                }
                if (observation.syncStatus !in setOf(
                        PlaybackSyncStatusDto.CONFIRMED,
                        PlaybackSyncStatusDto.NO_CANDIDATES,
                    )
                ) {
                    playbackDiagnostics += PlayerDiagnostic.SYNC_NOT_CONFIRMED
                }
                if (observation.actualState !in setOf(
                        ActualPlaybackStateDto.playing,
                        ActualPlaybackStateDto.paused,
                    )
                ) {
                    playbackDiagnostics += PlayerDiagnostic.ACTUAL_STATE_NOT_PLAYING_OR_PAUSED
                }
            }

            val target = observation?.controlTarget
            val targetMatchesCurrent = current != null &&
                target != null &&
                target.queueItemId == current.queueItemId &&
                target.token.isNotBlank()
            if (!targetMatchesCurrent) {
                playbackDiagnostics += PlayerDiagnostic.CONTROL_TARGET_MISSING_OR_MISMATCH
            }

            val confirmed = facts.isSnapshotAvailable &&
                facts.snapshotFreshness == SnapshotFreshness.FRESH &&
                facts.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                facts.isSnapshotGenerationCurrent &&
                facts.controlsEligible &&
                current != null &&
                !facts.currentOccurrenceAmbiguous &&
                observation != null &&
                observation.freshness == ObservationFreshnessDto.fresh &&
                observation.actualFreshness == ObservationFreshnessDto.fresh &&
                observation.matchesCurrent == true &&
                observation.boundQueueItemId == current.queueItemId &&
                observation.syncStatus in setOf(
                    PlaybackSyncStatusDto.CONFIRMED,
                    PlaybackSyncStatusDto.NO_CANDIDATES,
                ) &&
                observation.actualState in setOf(
                    ActualPlaybackStateDto.playing,
                    ActualPlaybackStateDto.paused,
                )
            val playbackStatus = if (!confirmed) {
                ConfirmedPlaybackStatus.UNKNOWN
            } else when (observation?.actualState) {
                ActualPlaybackStateDto.playing -> ConfirmedPlaybackStatus.PLAYING
                ActualPlaybackStateDto.paused -> ConfirmedPlaybackStatus.PAUSED
                else -> ConfirmedPlaybackStatus.UNKNOWN
            }

            val controlsAllowed = confirmed && targetMatchesCurrent
            val position = observation?.positionSeconds
            val duration = observation?.durationSeconds
            val seekRangeValid = position != null && position.isFinite() && position >= 0.0 &&
                duration != null && duration.isFinite() && duration > 0.0 && position <= duration
            val songStatus = when {
                facts.songDecodeStatus == PlayerFacts.SongDecodeStatus.INVALID -> {
                    resourceDiagnostics += PlayerDiagnostic.CURRENT_SONG_INVALID
                    SongMetadataStatus.INVALID
                }
                facts.songDecodeStatus == PlayerFacts.SongDecodeStatus.ABSENT || decodedSong == null -> {
                    resourceDiagnostics += PlayerDiagnostic.CURRENT_SONG_MISSING
                    SongMetadataStatus.MISSING
                }
                !songMatchesCurrent -> {
                    resourceDiagnostics += PlayerDiagnostic.CURRENT_SONG_OCCURRENCE_MISMATCH
                    SongMetadataStatus.OCCURRENCE_MISMATCH
                }
                else -> SongMetadataStatus.AVAILABLE
            }

            val lyricsStatus = selectedSong?.let { classifyLyricsStatus(it.lyricsStatus) }
                ?: LyricsResourceStatus.UNKNOWN
            if (selectedSong != null && lyricsStatus == LyricsResourceStatus.UNKNOWN) {
                resourceDiagnostics += PlayerDiagnostic.LYRICS_STATUS_UNKNOWN
            }
            val artworkReferenceStatus = when {
                selectedSong == null -> ArtworkReferenceStatus.UNKNOWN
                selectedSong.artwork?.artworkId.isNullOrBlank() -> ArtworkReferenceStatus.MISSING
                else -> ArtworkReferenceStatus.REFERENCED
            }
            val songPresentation = selectedSong?.toPresentation(lyricsStatus, artworkReferenceStatus)
            val sourceFormat = selectedSong.toSourceFormat()

            val outputDiagnostics = linkedSetOf<PlayerDiagnostic>()
            if (facts.outputDecodeFailed) outputDiagnostics += PlayerDiagnostic.OUTPUT_INVALID
            if (facts.outputObservationDecodeFailed) {
                outputDiagnostics += PlayerDiagnostic.OUTPUT_OBSERVATION_INVALID
            }
            val outputObservationStatus = classifyOutputObservationStatus(facts)
            if (outputObservationStatus != OutputObservationStatus.FRESH) {
                outputDiagnostics += PlayerDiagnostic.OUTPUT_OBSERVATION_NOT_FRESH
            }

            val dacState = facts.output?.states?.firstOrNull { it.mode == OutputModeDto.NAS_DAC }
            val dacStatus = when {
                outputObservationStatus == OutputObservationStatus.ERROR || facts.outputDecodeFailed ->
                    DacOutputStatus.ERROR
                outputObservationStatus == OutputObservationStatus.STALE ->
                    DacOutputStatus.STALE
                outputObservationStatus == OutputObservationStatus.UNKNOWN ->
                    DacOutputStatus.UNKNOWN
                dacState == null -> {
                    outputDiagnostics += PlayerDiagnostic.OUTPUT_STATE_MISSING
                    DacOutputStatus.NOT_PRESENT
                }
                dacState.stale -> {
                    outputDiagnostics += PlayerDiagnostic.OUTPUT_STATE_NOT_FRESH
                    DacOutputStatus.STALE
                }
                else -> when (dacState.status) {
                    OutputStatusDto.ACTIVE -> DacOutputStatus.ACTIVE
                    OutputStatusDto.INACTIVE -> DacOutputStatus.INACTIVE
                    OutputStatusDto.UNAVAILABLE -> DacOutputStatus.UNAVAILABLE
                }
            }
            val outputPresentation = OutputPresentation(
                observationStatus = outputObservationStatus,
                dacStatus = dacStatus,
                dacFormat = dacState?.format.ifOutputFactFresh(dacStatus),
                dacSampleRateHz = dacState?.sampleRate.ifOutputFactFresh(dacStatus),
                dacBitDepth = dacState?.bitDepth.ifOutputFactFresh(dacStatus),
                dacChannels = dacState?.channels.ifOutputFactFresh(dacStatus),
                errorCode = facts.outputObservation?.errorCode,
                errorMessage = facts.outputObservation?.errorMessage,
                diagnostics = outputDiagnostics.toList(),
            )

            return PlayerPresentation(
                playbackStatus = playbackStatus,
                actualState = observation?.actualState,
                businessPlaybackState = facts.businessPlayback?.state,
                playbackDiagnostics = playbackDiagnostics.toList(),
                currentOccurrenceId = current?.queueItemId,
                songStatus = songStatus,
                song = songPresentation,
                artworkReferenceStatus = artworkReferenceStatus,
                sourceFormat = sourceFormat,
                output = outputPresentation,
                controls = PlayerControlCapabilities(
                    pause = controlsAllowed && observation?.actualState == ActualPlaybackStateDto.playing,
                    resume = controlsAllowed && observation?.actualState == ActualPlaybackStateDto.paused,
                    previous = controlsAllowed,
                    next = controlsAllowed,
                    seek = controlsAllowed && seekRangeValid,
                ),
                resourceDiagnostics = resourceDiagnostics.toList(),
            )
        }

        private fun classifyLyricsStatus(raw: String): LyricsResourceStatus =
            when (raw.trim().lowercase(Locale.ROOT)) {
                "available" -> LyricsResourceStatus.AVAILABLE
                "missing" -> LyricsResourceStatus.MISSING
                "read_error" -> LyricsResourceStatus.READ_ERROR
                else -> LyricsResourceStatus.UNKNOWN
            }

        private fun classifyOutputObservationStatus(facts: PlayerFacts): OutputObservationStatus {
            if (!facts.isSnapshotAvailable) return OutputObservationStatus.UNKNOWN
            if (facts.snapshotFreshness != SnapshotFreshness.FRESH ||
                facts.connectionStatus != RuntimeConnectionStatus.CONNECTED ||
                !facts.isSnapshotGenerationCurrent
            ) return OutputObservationStatus.STALE
            if (facts.outputObservationDecodeFailed || facts.outputObservation == null) {
                return OutputObservationStatus.ERROR
            }
            val observation = facts.outputObservation
            if (!observation.errorCode.isNullOrBlank() || !observation.errorMessage.isNullOrBlank()) {
                return OutputObservationStatus.ERROR
            }
            return when (observation.freshness) {
                ObservationFreshnessDto.fresh -> OutputObservationStatus.FRESH
                ObservationFreshnessDto.stale -> OutputObservationStatus.STALE
                ObservationFreshnessDto.unknown -> OutputObservationStatus.UNKNOWN
            }
        }

        private fun SongDto?.toSourceFormat(): SourceFormatPresentation {
            val codec = this?.codec?.takeIf { it.isNotBlank() }
            val bitDepth = this?.bitDepth?.takeIf { it > 0 }
            val sampleRate = this?.sampleRateHz?.takeIf { it > 0 }
            val channels = this?.channelCount?.takeIf { it > 0 }
            val known = listOf(codec, bitDepth, sampleRate, channels).count { it != null }
            val status = when (known) {
                0 -> SourceFormatStatus.UNKNOWN
                4 -> SourceFormatStatus.COMPLETE
                else -> SourceFormatStatus.PARTIAL
            }
            return SourceFormatPresentation(status, codec, bitDepth, sampleRate, channels)
        }

        private fun SongDto.toPresentation(
            lyricsStatus: LyricsResourceStatus,
            artworkReferenceStatus: ArtworkReferenceStatus,
        ): PlayerSongPresentation = PlayerSongPresentation(
            songId = requireNotNull(songId),
            title = title.takeIf { it.isNotBlank() },
            fileUri = fileUri,
            artists = artists.filter { it.isNotBlank() },
            albumId = albumId,
            album = album?.takeIf { it.isNotBlank() },
            albumArtists = albumArtists.filter { it.isNotBlank() },
            trackNumber = trackNumber?.takeIf { it > 0 },
            discNumber = discNumber?.takeIf { it > 0 },
            durationSeconds = duration?.takeIf { it.isFinite() && it > 0.0 },
            lyrics = lyrics,
            lyricsFormat = lyricsFormat?.takeIf { it.isNotBlank() },
            lyricsSource = lyricsSource?.takeIf { it.isNotBlank() },
            lyricsStatus = lyricsStatus,
            rawLyricsStatus = this.lyricsStatus,
            artworkReferenceStatus = artworkReferenceStatus,
        )

        private fun <T> T?.ifOutputFactFresh(status: DacOutputStatus): T? =
            if (status == DacOutputStatus.ACTIVE ||
                status == DacOutputStatus.INACTIVE ||
                status == DacOutputStatus.UNAVAILABLE
            ) this else null
    }
}
