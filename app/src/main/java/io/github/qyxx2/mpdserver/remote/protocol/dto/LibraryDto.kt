package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AvailabilityStatus { AVAILABLE, MISSING, UNREADABLE }

@Serializable
data class SongDto(
    @SerialName("song_id") val songId: String?,
    val title: String,
    @SerialName("file_uri") val fileUri: String,
    @SerialName("availability_status") val availabilityStatus: AvailabilityStatus,
    val duration: Double?,
    val artist: String?,
    val album: String?,
)

@Serializable
data class ActivityAcceptedDto(@SerialName("activity_id") val activityId: String)
