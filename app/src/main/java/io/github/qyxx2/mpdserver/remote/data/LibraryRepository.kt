package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

class LibraryRepository(transport: HttpTransport) : ApiRepository(transport) {
    fun getSongs(): ApiResult<JsonElement> = get("/api/library/songs")
    fun getSong(songId: String): ApiResult<JsonElement> = get("/api/library/songs/${pathSegment(songId)}")

    private fun pathSegment(value: String): String {
        require(value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..") {
            "Invalid path segment"
        }
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
