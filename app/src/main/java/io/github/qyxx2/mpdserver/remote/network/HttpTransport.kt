package io.github.qyxx2.mpdserver.remote.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

sealed interface HttpFailure {
    data class Transport(val cause: IOException) : HttpFailure
    data class Decode(val message: String) : HttpFailure
    data class Protocol(val message: String) : HttpFailure
}

sealed interface HttpResult {
    data class Body(val statusCode: Int, val contentType: String?, val bytes: ByteArray) : HttpResult
    data class NoContent(val statusCode: Int = 204) : HttpResult
    data class HttpError(val statusCode: Int, val contentType: String?, val bytes: ByteArray) : HttpResult
    data class Failed(val failure: HttpFailure) : HttpResult

    companion object {
        fun fromResponse(response: Response): HttpResult = response.use { closed ->
            if (closed.code == 204) return@use NoContent(closed.code)
            val contentType = closed.header("Content-Type")
            val bytes = closed.body?.bytes() ?: ByteArray(0)
            if (closed.isSuccessful) Body(closed.code, contentType, bytes)
            else HttpError(closed.code, contentType, bytes)
        }
    }
}

class HttpTransport(
    private val origin: ServerOrigin,
    private val client: OkHttpClient,
) {
    private val endpoints = EndpointResolver(origin)

    fun buildRequest(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        val endpoint = endpoints.http(path)
        val normalizedMethod = method.uppercase()
        require(normalizedMethod in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) {
            "Unsupported HTTP method"
        }
        val requiresBody = normalizedMethod in setOf("POST", "PUT", "PATCH")
        require(!requiresBody || body != null) { "$normalizedMethod requires an explicit request body" }
        require(normalizedMethod != "GET" || body == null) { "GET cannot carry a request body" }
        val builder = Request.Builder().url(endpoint)
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && '\r' !in name && '\n' !in name) { "Invalid HTTP header name" }
            require('\r' !in value && '\n' !in value) { "Invalid HTTP header value" }
            builder.header(name, value)
        }
        builder.method(normalizedMethod, body?.toRequestBody(JSON_MEDIA_TYPE))
        return builder.build()
    }

    fun execute(request: Request): HttpResult {
        endpoints.resolveHttp(request.url.toString())
        return try {
            HttpResult.fromResponse(client.newCall(request).execute())
        } catch (error: IOException) {
            HttpResult.Failed(HttpFailure.Transport(error))
        }
    }

    /** Deliberately metadata-only: never includes authorization, headers, or body. */
    fun safeDiagnostics(request: Request): String = "${request.method} ${request.url.encodedPath}"

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
