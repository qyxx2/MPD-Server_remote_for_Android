package io.github.qyxx2.mpdserver.remote.network

import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class HttpTransportBoundedBodyTest {
    @Test fun knownOversizedBodyIsRejectedBeforeAnyRead() {
        val bytes = pngBytes(32)
        val readBytes = AtomicLong()
        val client = clientWithBody(bytes, declaredLength = bytes.size.toLong(), readBytes)
        val result = HttpTransport(
            ServerOrigin.parse("http://server.example:18000"),
            client,
        ).executeBounded(request(), maxBodyBytes = 16)

        assertTrue(result is HttpResult.Failed)
        assertTrue((result as HttpResult.Failed).failure is HttpFailure.Protocol)
        assertEquals(0L, readBytes.get())
    }

    @Test fun unknownLengthBodyReadsOnlyOneBoundedOkioSegmentThenRejects() {
        val bytes = pngBytes(1024 * 1024)
        val readBytes = AtomicLong()
        val client = clientWithBody(bytes, declaredLength = -1L, readBytes)
        val result = HttpTransport(
            ServerOrigin.parse("http://server.example:18000"),
            client,
        ).executeBounded(request(), maxBodyBytes = 16)

        assertTrue(result is HttpResult.Failed)
        assertTrue((result as HttpResult.Failed).failure is HttpFailure.Protocol)
        // Okio may read ahead one fixed 8 KiB segment; the response accumulator itself remains capped at maxBodyBytes + 1.
        assertTrue("read ${readBytes.get()} bytes", readBytes.get() <= 8_192L)
        assertTrue("must not consume the full 1 MiB body", readBytes.get() < bytes.size.toLong())
    }

    @Test fun bodyAtLimitIsReturnedAndHttpErrorEnvelopeIsPreserved() {
        val bytes = pngBytes(16)
        val successfulClient = clientWithBody(bytes, declaredLength = bytes.size.toLong(), AtomicLong())
        val successful = HttpTransport(
            ServerOrigin.parse("http://server.example:18000"),
            successfulClient,
        ).executeBounded(request(), maxBodyBytes = 16)
        assertTrue(successful is HttpResult.Body)
        assertEquals(bytes.toList(), (successful as HttpResult.Body).bytes.toList())

        val errorBytes = """{"error":{"code":"ARTWORK_NOT_FOUND","message":"missing"}}""".toByteArray()
        val errorClient = clientWithBody(errorBytes, declaredLength = errorBytes.size.toLong(), AtomicLong(), status = 404)
        val error = HttpTransport(
            ServerOrigin.parse("http://server.example:18000"),
            errorClient,
        ).executeBounded(request(), maxBodyBytes = 128)
        assertTrue(error is HttpResult.HttpError)
        assertEquals(errorBytes.toList(), (error as HttpResult.HttpError).bytes.toList())
    }

    private fun request(): Request = Request.Builder()
        .url("http://server.example:18000/api/library/songs/song-1/artwork")
        .get()
        .build()

    private fun clientWithBody(
        bytes: ByteArray,
        declaredLength: Long,
        readBytes: AtomicLong,
        status: Int = 200,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val body = object : ResponseBody() {
            private val upstream = Buffer().write(bytes)
            private val tracked = object : ForwardingSource(upstream) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val read = super.read(sink, byteCount)
                    if (read > 0) readBytes.addAndGet(read)
                    return read
                }
            }.buffer()

            override fun contentType() = "image/png".toMediaType()
            override fun contentLength() = declaredLength
            override fun source(): BufferedSource = tracked
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(if (status == 200) "OK" else "Not Found")
            .header("Content-Type", if (status == 200) "image/png" else "application/json")
            .body(body)
            .build()
    }.build()

    private fun pngBytes(size: Int): ByteArray {
        require(size >= 8)
        return ByteArray(size).also {
            byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10).copyInto(it)
        }
    }
}
