package io.github.qyxx2.mpdserver.remote.protocol

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResultTest {
    @Test fun classifiesBusinessErrorEnvelopeSeparately() {
        val result = HttpResult.HttpError(
            statusCode = 404,
            contentType = "application/json",
            bytes = """{"error":{"code":"SONG_NOT_FOUND","message":"song missing"}}""".toByteArray(),
        ).decodeJson<Probe>()
        assertTrue(result is ApiResult.BusinessError)
        assertEquals("SONG_NOT_FOUND", (result as ApiResult.BusinessError).error.code)
    }

    @Test fun preservesNonJsonErrorAsHttpError() {
        val result = HttpResult.HttpError(502, "text/plain", "upstream down".toByteArray()).decodeJson<Probe>()
        assertTrue(result is ApiResult.HttpError)
        assertEquals(502, (result as ApiResult.HttpError).statusCode)
    }

    @Test fun transportFailureIsNotMisclassifiedAsHttpStatusError() {
        val failure = io.github.qyxx2.mpdserver.remote.network.HttpFailure.Transport(java.io.IOException("offline"))
        val result = HttpResult.Failed(failure).decodeJson<Probe>()
        assertTrue(result is ApiResult.TransportError)
    }

    @Test fun malformedJsonBodyIsDecodeError() {
        val result = HttpResult.Body(200, "application/json", "{broken".toByteArray()).decodeJson<Probe>()
        assertTrue(result is ApiResult.DecodeError)
    }

    @Test fun accepts202ActivityIdWithoutDecodingAsBusinessState() {
        val result = HttpResult.Body(202, "application/json", """{"activity_id":"scan-23"}""".toByteArray()).decodeJson<Probe>()
        assertTrue(result is ApiResult.Accepted)
        assertEquals("scan-23", (result as ApiResult.Accepted).activityId)
    }

    @Test fun rejects202ActivityWithoutRequiredId() {
        val result = HttpResult.Body(202, "application/json", """{"status":"accepted"}""".toByteArray()).decodeJson<Probe>()
        assertTrue(result is ApiResult.DecodeError)
    }

    @Test fun rejects202ActivityWithNonJsonContentType() {
        val result = HttpResult.Body(202, "text/plain", "activity_id=scan-23".toByteArray()).decodeJson<Probe>()
        assertTrue(result is ApiResult.DecodeError)
    }

    @Serializable data class Probe(val value: String)
}
