package io.github.qyxx2.mpdserver.remote.media

import io.github.qyxx2.mpdserver.remote.ui.player.PlayerSongPresentation

/** Metadata is mapped only from the song already bound to the canonical current occurrence. */
object MediaMetadataMapper {
    fun map(queueItemId: String, song: PlayerSongPresentation): MediaSessionMetadata {
        require(queueItemId.isNotBlank()) { "A confirmed queue occurrence is required" }
        val artists = song.artists.map(String::trim).filter(String::isNotEmpty)
        return MediaSessionMetadata(
            mediaId = queueItemId,
            title = song.title?.trim()?.takeIf(String::isNotEmpty),
            artist = artists.takeIf { it.isNotEmpty() }?.joinToString(", "),
            album = song.album?.trim()?.takeIf(String::isNotEmpty),
            durationMillis = song.durationSeconds.toPositiveMillis(),
        )
    }

    private fun Double?.toPositiveMillis(): Long? {
        val seconds = this ?: return null
        if (!seconds.isFinite() || seconds <= 0.0) return null
        if (seconds > Long.MAX_VALUE.toDouble() / 1_000.0) return null
        return (seconds * 1_000.0).toLong().takeIf { it > 0L }
    }
}
