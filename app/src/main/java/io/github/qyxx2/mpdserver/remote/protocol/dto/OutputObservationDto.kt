package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OutputObservationDto(
    @SerialName("observed_at") val observedAt: String?,
    val freshness: ObservationFreshnessDto,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
)
