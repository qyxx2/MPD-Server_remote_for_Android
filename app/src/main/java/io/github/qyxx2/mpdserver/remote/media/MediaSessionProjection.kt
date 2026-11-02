package io.github.qyxx2.mpdserver.remote.media

import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerControlCapabilities
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerFacts
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.SongMetadataStatus

enum class MediaSessionPlaybackState { PLAYING, PAUSED, NONE }

data class MediaSessionMetadata(
    val mediaId: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long?,
)

data class MediaSessionCommandAvailability(
    val pause: Boolean = false,
    val resume: Boolean = false,
    val previous: Boolean = false,
    val next: Boolean = false,
) {
    companion object {
        val NONE = MediaSessionCommandAvailability()

        fun from(controls: PlayerControlCapabilities) = MediaSessionCommandAvailability(
            pause = controls.pause,
            resume = controls.resume,
            previous = controls.previous,
            next = controls.next,
        )
    }
}

data class ConfirmedMediaSessionIdentity(
    val originAddress: String,
    val connectionGeneration: Long,
    val epoch: String,
    val libraryRevision: Long,
    val explicitStopGeneration: Long,
    val queueItemId: String,
    val songId: String,
    val artworkId: String?,
    val metadata: MediaSessionMetadata,
)

object MediaSessionProjection {
    enum class Disposition { CONFIRMED, RETAINED, WITHDRAWN }

    data class Result(
        val disposition: Disposition,
        val metadata: MediaSessionMetadata?,
        val playbackState: MediaSessionPlaybackState,
        val positionMillis: Long?,
        val actions: MediaSessionCommandAvailability,
        val confirmedIdentity: ConfirmedMediaSessionIdentity?,
    )

    fun project(
        state: SnapshotStoreState,
        originAddress: String,
        previousConfirmed: ConfirmedMediaSessionIdentity?,
        commandActions: MediaSessionCommandAvailability,
    ): Result {
        val facts = PlayerFacts.from(state)
        val presentation = PlayerPresentation.from(facts)
        val snapshot = state.snapshot
        val current = facts.currentOccurrence

        var retainedIdentity = previousConfirmed
        if (previousConfirmed != null) {
            val sameSession = previousConfirmed.originAddress == originAddress &&
                (snapshot == null || snapshot.epoch == previousConfirmed.epoch)
            if (!sameSession) {
                // A new origin/epoch may immediately publish its own fresh confirmed current item,
                // but it can never inherit metadata from the prior server/session.
                retainedIdentity = null
            } else if (snapshot?.explicitStopGeneration?.let {
                    it != previousConfirmed.explicitStopGeneration
                } == true
            ) {
                // An explicit Stop generation is a revocation signal, even if Queue current remains.
                return withdrawn()
            } else if (snapshot != null) {
                // A missing/ambiguous current withdraws; a new, confirmed occurrence may replace the
                // previous one in this same canonical update without first exposing stale metadata.
                if (facts.currentOccurrenceAmbiguous || facts.queueDecodeFailed || current == null) {
                    return withdrawn()
                }
                if (current.queueItemId != previousConfirmed.queueItemId ||
                    current.songId != previousConfirmed.songId
                ) {
                    retainedIdentity = null
                }
            }
        }

        val confirmed = snapshot != null &&
            state.controlsEligible &&
            current != null &&
            !facts.currentOccurrenceAmbiguous &&
            !facts.queueDecodeFailed &&
            presentation.playbackStatus in setOf(
                ConfirmedPlaybackStatus.PLAYING,
                ConfirmedPlaybackStatus.PAUSED,
            ) &&
            presentation.songStatus == SongMetadataStatus.AVAILABLE &&
            presentation.song != null

        if (!confirmed) {
            return retainedIdentity?.let {
                Result(
                    disposition = Disposition.RETAINED,
                    metadata = it.metadata,
                    playbackState = MediaSessionPlaybackState.NONE,
                    positionMillis = null,
                    actions = MediaSessionCommandAvailability.NONE,
                    confirmedIdentity = it,
                )
            } ?: withdrawn()
        }

        val occurrence = requireNotNull(current)
        val song = requireNotNull(presentation.song)
        val metadata = MediaMetadataMapper.map(occurrence.queueItemId, song)
        val identity = ConfirmedMediaSessionIdentity(
            originAddress = originAddress,
            connectionGeneration = state.connectionGeneration,
            epoch = requireNotNull(snapshot).epoch,
            libraryRevision = snapshot.libraryRevision,
            explicitStopGeneration = snapshot.explicitStopGeneration,
            queueItemId = occurrence.queueItemId,
            songId = occurrence.songId,
            artworkId = facts.currentSong?.artwork?.artworkId?.trim()?.takeIf(String::isNotEmpty),
            metadata = metadata,
        )
        val playbackState = when (presentation.playbackStatus) {
            ConfirmedPlaybackStatus.PLAYING -> MediaSessionPlaybackState.PLAYING
            ConfirmedPlaybackStatus.PAUSED -> MediaSessionPlaybackState.PAUSED
            ConfirmedPlaybackStatus.UNKNOWN -> MediaSessionPlaybackState.NONE
        }
        val observation = facts.playbackObservation
        val actions = MediaSessionCommandAvailability(
            pause = presentation.controls.pause && commandActions.pause,
            resume = presentation.controls.resume && commandActions.resume,
            previous = presentation.controls.previous && commandActions.previous,
            next = presentation.controls.next && commandActions.next,
        )
        return Result(
            disposition = Disposition.CONFIRMED,
            metadata = metadata,
            playbackState = playbackState,
            positionMillis = observation?.positionSeconds.toNonNegativeMillis(),
            actions = actions,
            confirmedIdentity = identity,
        )
    }

    private fun withdrawn() = Result(
        disposition = Disposition.WITHDRAWN,
        metadata = null,
        playbackState = MediaSessionPlaybackState.NONE,
        positionMillis = null,
        actions = MediaSessionCommandAvailability.NONE,
        confirmedIdentity = null,
    )

    private fun Double?.toNonNegativeMillis(): Long? {
        val seconds = this ?: return null
        if (!seconds.isFinite() || seconds < 0.0 ||
            seconds > Long.MAX_VALUE.toDouble() / 1_000.0
        ) return null
        return (seconds * 1_000.0).toLong()
    }
}
