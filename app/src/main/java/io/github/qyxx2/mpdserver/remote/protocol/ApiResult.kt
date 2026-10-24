package io.github.qyxx2.mpdserver.remote.protocol

import io.github.qyxx2.mpdserver.remote.network.HttpFailure as TransportFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.ActivityAcceptedDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val statusCode: Int) : ApiResult<T>
    data class Accepted(val activityId: String?, val statusCode: Int = 202) : ApiResult<Nothing>
    data object NoContent : ApiResult<Nothing>
    data class BusinessError(val statusCode: Int, val error: ApiErrorDto) : ApiResult<Nothing>
    data class HttpError(val statusCode: Int, val message: String, val contentType: String?) : ApiResult<Nothing>
    data class TransportError(val failure: TransportFailure.Transport) : ApiResult<Nothing>
    data class DecodeError(val statusCode: Int?, val message: String) : ApiResult<Nothing>
    data class ProtocolError(val message: String) : ApiResult<Nothing>
}

inline fun <reified T> HttpResult.decodeJson(): ApiResult<T> = when (this) {
    is HttpResult.NoContent -> ApiResult.NoContent
    is HttpResult.Failed -> when (val cause = failure) {
        is TransportFailure.Transport -> ApiResult.TransportError(cause)
        is TransportFailure.Decode -> ApiResult.DecodeError(null, cause.message)
        is TransportFailure.Protocol -> ApiResult.ProtocolError(cause.message)
    }
    is HttpResult.HttpError -> {
        if (!contentType.orEmpty().contains("json", ignoreCase = true)) {
            ApiResult.HttpError(statusCode, "HTTP $statusCode returned a non-JSON error body", contentType)
        } else {
            try {
                val envelope = JsonConfiguration.json.decodeFromString<ApiErrorEnvelope>(bytes.toString(Charsets.UTF_8))
                ApiResult.BusinessError(statusCode, envelope.error)
            } catch (error: SerializationException) {
                ApiResult.DecodeError(statusCode, "Invalid error envelope for HTTP $statusCode")
            }
        }
    }
    is HttpResult.Body -> {
        if (!contentType.orEmpty().contains("json", ignoreCase = true)) {
            ApiResult.DecodeError(statusCode, "Expected JSON response for HTTP $statusCode")
        } else if (statusCode == 202) {
            try {
                val accepted = JsonConfiguration.json.decodeFromString<ActivityAcceptedDto>(bytes.toString(Charsets.UTF_8))
                if (accepted.activityId.isBlank()) {
                    ApiResult.DecodeError(statusCode, "Activity response has a blank activity_id")
                } else {
                    ApiResult.Accepted(accepted.activityId, statusCode)
                }
            } catch (error: SerializationException) {
                ApiResult.DecodeError(statusCode, "Invalid activity response")
            }
        } else {
            try {
                ApiResult.Success(JsonConfiguration.json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)), statusCode)
            } catch (error: SerializationException) {
                ApiResult.DecodeError(statusCode, "Invalid JSON response for HTTP $statusCode")
            }
        }
    }
}
