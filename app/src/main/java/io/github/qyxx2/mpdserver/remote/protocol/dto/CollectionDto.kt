package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CollectionSourceTypeDto {
    ALBUM, ARTIST, GENRE, YEAR, TAG, SEARCH, PLAYLIST, FAVORITES, LIBRARY, SONGS,
}

@Serializable
data class CollectionDto(
    @SerialName("source_type") val sourceType: CollectionSourceTypeDto,
    @SerialName("source_id") val sourceId: String?,
    @SerialName("song_ids") val songIds: List<String>,
    @SerialName("unavailable_song_ids") val unavailableSongIds: List<String>,
    @SerialName("random_seed") val randomSeed: Long?,
)
