package io.github.qyxx2.mpdserver.remote.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorDto)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)
