package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SearchGroupKindDto {
    @SerialName("albums") ALBUMS,
    @SerialName("artists") ARTISTS,
    @SerialName("genres") GENRES,
    @SerialName("years") YEARS,
    @SerialName("tags") TAGS,
}

@Serializable
data class SearchGroupDto(
    val kind: SearchGroupKindDto,
    @SerialName("source_id") val sourceId: String,
    val name: String,
    @SerialName("song_ids") val songIds: List<String>,
)

@Serializable
data class SearchResultsDto(
    val groups: List<SearchGroupDto>,
    @SerialName("song_ids") val songIds: List<String>,
)
