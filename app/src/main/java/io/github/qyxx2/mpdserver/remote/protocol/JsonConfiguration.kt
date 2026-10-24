package io.github.qyxx2.mpdserver.remote.protocol

import kotlinx.serialization.json.Json

object JsonConfiguration {
    val json: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        encodeDefaults = true
    }
}
