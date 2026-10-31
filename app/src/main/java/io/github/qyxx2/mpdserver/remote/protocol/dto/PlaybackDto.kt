package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackStateValue { PLAYING, PAUSED, STOPPED }

/** Strict wire representation shared by GET /api/playback/state and FullStateSnapshot.playback. */
@Serializable
data class PlaybackStateDto(
    @SerialName("song_id") val songId: String?,
    val state: PlaybackStateValue,
    @SerialName("playback_context_id") val playbackContextId: String?,
    @SerialName("position_seconds") val positionSeconds: Double?,
    @SerialName("autoplay_enabled") val autoplayEnabled: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)
