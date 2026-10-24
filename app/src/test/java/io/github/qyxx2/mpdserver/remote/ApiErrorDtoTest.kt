package io.github.qyxx2.mpdserver.remote.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiErrorDtoTest {
    @Test fun decodesRequiredErrorEnvelope() {
        val decoded = JsonConfiguration.json.decodeFromString<ApiErrorEnvelope>("""{"error":{"code":"NOT_FOUND","message":"missing","details":{"id":"x"}}}""")
        assertEquals("NOT_FOUND", decoded.error.code)
    }

    @Test fun requiresErrorEnvelope() {
        assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<ApiErrorEnvelope>("""{"code":"NOT_FOUND","message":"missing"}""") }
    }
}
