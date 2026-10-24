package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire DTOs only; decoding and connection lifecycle are implemented in Batch 2. */
@Serializable
data class RealtimeFrameDto(
    val type: String,
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    val epoch: String,
    val sequence: Long,
    val state: JsonElement? = null,
    val domains: List<String>? = null,
    val revisions: JsonElement? = null,
)
