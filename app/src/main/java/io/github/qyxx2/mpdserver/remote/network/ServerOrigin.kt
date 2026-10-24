package io.github.qyxx2.mpdserver.remote.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@JvmInline
value class ServerOrigin private constructor(val httpUrl: HttpUrl) {
    val httpScheme: String get() = httpUrl.scheme
    val webSocketScheme: String get() = if (httpScheme == "https") "wss" else "ws"

    fun httpUrl(path: String): HttpUrl {
        require('#' !in path && '\r' !in path && '\n' !in path && '\u0000' !in path) {
            "Fragments and control characters are not permitted in API endpoints"
        }
        val querySeparator = path.indexOf('?')
        val apiPath = if (querySeparator >= 0) path.substring(0, querySeparator) else path
        val encodedQuery = if (querySeparator >= 0) path.substring(querySeparator + 1) else null
        val builder = httpUrl.newBuilder()
            .encodedPath(validateApiPath(apiPath))
            .query(null)
            .fragment(null)
        if (encodedQuery != null) builder.encodedQuery(encodedQuery)
        return builder.build()
    }

    fun webSocketUrl(path: String): String =
        httpUrl(path).toString().replaceFirst(Regex("^https?"), webSocketScheme)

    companion object {
        fun parse(value: String): ServerOrigin {
            val parsed = value.trim().toHttpUrl()
            require(parsed.scheme == "http" || parsed.scheme == "https") { "Server origin must use HTTP or HTTPS" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Credentials must not be embedded in server origin" }
            require(parsed.query == null && parsed.fragment == null) { "Server origin must not include query or fragment" }
            return ServerOrigin(parsed.newBuilder().encodedPath("/").query(null).fragment(null).build())
        }

        internal fun validateApiPath(path: String): String {
            require(path.startsWith("/api/") || path == "/api") { "Only /api/ endpoints are permitted" }
            require(!path.contains('\\') && !path.contains('\u0000')) { "Invalid API path" }
            val segments = path.split('/').filter { it.isNotEmpty() }
            require(segments.none { it == "." || it == ".." || it.equals("%2e", true) || it.equals("%2e%2e", true) }) { "Path traversal is not permitted" }
            return "/" + segments.joinToString("/")
        }
    }
}

class EndpointResolver(private val origin: ServerOrigin) {
    fun http(path: String): HttpUrl = origin.httpUrl(path)
    fun webSocket(path: String): String = origin.webSocketUrl(path)

    fun resolveHttp(candidate: String): HttpUrl {
        val parsed = candidate.toHttpUrl()
        val expected = origin.httpUrl
        require(parsed.scheme == expected.scheme && parsed.host == expected.host && parsed.port == expected.port) {
            "Endpoint does not belong to the configured server origin"
        }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Endpoint credentials are not permitted" }
        ServerOrigin.validateApiPath(parsed.encodedPath)
        return parsed
    }
}
