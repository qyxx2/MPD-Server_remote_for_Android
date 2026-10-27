package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class QueueSourceDto { MANUAL, AUTOPLAY }

@Serializable
data class QueueItemDto(
    @SerialName("queue_item_id") val queueItemId: String,
    @SerialName("song_id") val songId: String,
    val position: Int,
    val source: QueueSourceDto,
    @SerialName("playback_context_id") val playbackContextId: String? = null,
)

@Serializable
data class QueueListDto(val items: List<QueueItemDto> = emptyList(), val count: Int = 0)

@Serializable
data class QueueSnapshotDto(val revision: Long, val items: List<QueueItemDto>)
