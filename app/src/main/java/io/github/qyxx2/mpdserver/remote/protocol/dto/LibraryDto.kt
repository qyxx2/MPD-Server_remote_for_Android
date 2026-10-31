package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AvailabilityStatus { AVAILABLE, MISSING, UNREADABLE }

@Serializable
enum class ArtworkSourceDto { EMBEDDED }

@Serializable
data class ArtistSummaryDto(
    @SerialName("artist_id") val artistId: String,
    val name: String,
    @SerialName("song_count") val songCount: Int,
)

@Serializable
data class ArtistListDto(
    val items: List<ArtistSummaryDto>,
    val count: Int,
)

@Serializable
data class AlbumSummaryDto(
    @SerialName("album_id") val albumId: String,
    val title: String,
    @SerialName("album_artists") val albumArtists: List<String>,
    val year: Int?,
    val date: String?,
    @SerialName("song_count") val songCount: Int,
    val artwork: ArtworkDto?,
)

@Serializable
data class AlbumListDto(val items: List<AlbumSummaryDto>, val count: Int)

@Serializable
data class GenreSummaryDto(
    @SerialName("genre_id") val genreId: String,
    val name: String,
    @SerialName("song_count") val songCount: Int,
)

@Serializable
data class GenreListDto(val items: List<GenreSummaryDto>, val count: Int)

@Serializable
data class YearSummaryDto(
    @SerialName("source_id") val sourceId: String,
    val value: Int?,
    @SerialName("song_count") val songCount: Int,
)

@Serializable
data class YearListDto(val items: List<YearSummaryDto>, val count: Int)

@Serializable
data class TagSummaryDto(
    @SerialName("tag_id") val tagId: String,
    val name: String,
    @SerialName("song_count") val songCount: Int,
)

@Serializable
data class TagListDto(val items: List<TagSummaryDto>, val count: Int)

@Serializable
data class SongListDto(val items: List<SongDto>, val count: Int)

@Serializable
data class ArtworkDto(
    @SerialName("artwork_id") val artworkId: String,
    val source: ArtworkSourceDto,
    @SerialName("picture_index") val pictureIndex: Int,
    @SerialName("mime_type") val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("content_sha256") val contentSha256: String? = null,
)

@Serializable
data class SongDto(
    @SerialName("song_id") val songId: String? = null,
    val title: String,
    @SerialName("file_uri") val fileUri: String,
    val artists: List<String> = emptyList(),
    @SerialName("album_id") val albumId: String?,
    val album: String?,
    @SerialName("album_artists") val albumArtists: List<String> = emptyList(),
    @SerialName("track_number") val trackNumber: Int?,
    @SerialName("disc_number") val discNumber: Int?,
    val year: Int?,
    val date: String?,
    val genres: List<String> = emptyList(),
    @SerialName("tag_names") val tagNames: List<String> = emptyList(),
    val duration: Double?,
    val lyrics: String?,
    @SerialName("lyrics_format") val lyricsFormat: String?,
    @SerialName("lyrics_source") val lyricsSource: String?,
    @SerialName("lyrics_status") val lyricsStatus: String = "missing",
    @SerialName("bit_depth") val bitDepth: Int?,
    @SerialName("sample_rate_hz") val sampleRateHz: Int?,
    @SerialName("channel_count") val channelCount: Int?,
    val codec: String?,
    @SerialName("metadata_status") val metadataStatus: String?,
    @SerialName("last_scanned_at") val lastScannedAt: String?,
    @SerialName("file_size") val fileSize: Long?,
    @SerialName("file_mtime_ns") val fileMtimeNs: Long?,
    @SerialName("content_hash") val contentHash: String?,
    @SerialName("availability_status") val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    val artwork: ArtworkDto?,
)

@Serializable
data class ActivityAcceptedDto(@SerialName("activity_id") val activityId: String)
