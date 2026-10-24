package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class PlaybackStateValue { PLAYING, PAUSED, STOPPED }

@Serializable
data class PlaybackStateDto(
    @SerialName("song_id") val songId: String?,
    val state: PlaybackStateValue,
    val context: JsonElement?,
    val position: Double,
    @SerialName("autoplay_enabled") val autoplayEnabled: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)
