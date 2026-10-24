package io.github.qyxx2.mpdserver.remote.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpResultTest {
    @Test fun accepts204WithoutBody() {
        val result = HttpResult.fromResponse(response(204, ""))
        assertTrue(result is HttpResult.NoContent)
    }

    @Test fun preservesNonJsonErrorStatus() {
        val result = HttpResult.fromResponse(response(502, "upstream unavailable", "text/plain"))
        assertTrue(result is HttpResult.HttpError)
        assertEquals(502, (result as HttpResult.HttpError).statusCode)
    }

    @Test fun accepts202ActivityEnvelope() {
        val result = HttpResult.fromResponse(response(202, "{\"activity_id\":\"scan-1\"}"))
        assertTrue(result is HttpResult.Body)
        assertEquals(202, (result as HttpResult.Body).statusCode)
    }

    @Test fun preservesWriteMethodPathBodyAndIdempotencyKey() {
        val origin = ServerOrigin.parse("http://music.example:18000")
        val transport = HttpTransport(origin, OkHttpClient())
        val captured = transport.buildRequest("PUT", "/api/playlists/p-1", "{\"name\":\"mix\"}", mapOf("Idempotency-Key" to "intent-7"))
        assertEquals("PUT", captured.method)
        assertEquals("http://music.example:18000/api/playlists/p-1", captured.url.toString())
        assertEquals("{\"name\":\"mix\"}", captured.body!!.let { body -> okio.Buffer().also(body::writeTo).readUtf8() })
        assertEquals("intent-7", captured.header("Idempotency-Key"))
    }

    @Test fun requestDiagnosticsDoNotExposeSecretsOrBody() {
        val transport = HttpTransport(ServerOrigin.parse("http://music.example:18000"), OkHttpClient())
        val diagnostics = transport.safeDiagnostics(Request.Builder().url("http://music.example:18000/api/state").header("Authorization", "Bearer private").post("secret-body".toRequestBody("text/plain".toMediaType())).build())
        assertFalse(diagnostics.contains("private"))
        assertFalse(diagnostics.contains("secret-body"))
    }

    private fun response(code: Int, body: String, contentType: String = "application/json"): Response {
        val request = Request.Builder().url("http://music.example:18000/api/test").build()
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        if (code != 204 && code != 304) builder.body(body.toResponseBody(contentType.toMediaType()))
        else builder.body("".toResponseBody(contentType.toMediaType()))
        return builder.build()
    }
}
