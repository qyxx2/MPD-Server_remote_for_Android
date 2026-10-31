package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HistoryEventDto(
    @SerialName("history_id") val historyId: Long?,
    @SerialName("song_id") val songId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String?,
    val reason: String?,
    @SerialName("session_id") val sessionId: String?,
)

@Serializable
data class HistoryListDto(val items: List<HistoryEventDto>, val count: Int)
