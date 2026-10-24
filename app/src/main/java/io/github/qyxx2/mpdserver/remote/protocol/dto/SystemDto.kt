package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class OutputMode { NAS_DAC, CLIENT_STREAM }

@Serializable
enum class OutputState { UNAVAILABLE, INACTIVE, ACTIVE }

@Serializable
data class OutputRequestDto(val mode: OutputMode, val enabled: Boolean)

@Serializable
data class SystemOutputDto(
    val states: Map<String, OutputState>,
    @SerialName("last_request") val lastRequest: JsonElement?,
)
