package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class OutputModeDto { NAS_DAC, CLIENT_STREAM }
@Serializable
enum class OutputStatusDto { UNAVAILABLE, INACTIVE, ACTIVE }
@Serializable
enum class OutputRequestStatusDto { PREPARING, SUCCEEDED, SWITCH_FAILED }

@Serializable
data class OutputStateDto(
    val mode: OutputModeDto,
    val status: OutputStatusDto,
    @SerialName("target_client_id") val targetClientId: String?,
    @SerialName("stream_url") val streamUrl: String?,
    val format: String?,
    @SerialName("sample_rate") val sampleRate: Int?,
    @SerialName("bit_depth") val bitDepth: Int?,
    val channels: Int?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("updated_at") val updatedAt: String,
    val stale: Boolean,
)

@Serializable
data class OutputLastRequestDto(
    val mode: OutputModeDto,
    val enabled: Boolean,
    val status: OutputRequestStatusDto,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class OutputSnapshotDto(val states: List<OutputStateDto>, @SerialName("last_request") val lastRequest: OutputLastRequestDto?)
