package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistDto(
    @SerialName("playlist_id") val playlistId: String,
    val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("is_system") val isSystem: Boolean,
    @SerialName("song_ids") val songIds: List<String>,
)

/** Actual GET /api/playlists response envelope returned by MPD-Server. */
@Serializable
data class PlaylistListDto(
    val items: List<PlaylistDto>,
    val count: Int,
)
