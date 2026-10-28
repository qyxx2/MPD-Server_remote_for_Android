package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.decodeJson
import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtistListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** Runtime-owned, read-only access to song metadata, Played and artwork resources. */
class RuntimeReadApi(
    private val originProvider: () -> ServerOrigin,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val execute: (HttpTransport, Request) -> HttpResult = { transport, request -> transport.execute(request) },
) {
    suspend fun getSong(songId: String): ApiResult<SongDto> = jsonGet("/api/library/songs/${pathSegment(songId)}")

    /** Existing Web navigation lookup: artist IDs are resolved by exact server-returned display name. */
    suspend fun getArtists(): ApiResult<ArtistListDto> = jsonGet("/api/library/artists")

    suspend fun getPlayed(): ApiResult<QueueListDto> = jsonGet("/api/history/played")

    /** Web-equivalent post-save readback; the server's playlist list remains authoritative. */
    suspend fun getPlaylists(): ApiResult<PlaylistListDto> = jsonGet("/api/playlists")

    /** Returns original image bytes; HTTP 404 stays distinguishable from malformed/non-image content. */
    suspend fun getArtwork(songId: String): HttpResult = withContext(dispatcher) {
        val path = "/api/library/songs/${pathSegment(songId)}/artwork"
        val transport = HttpTransport(originProvider(), client)
        val result = execute(transport, transport.buildRequest("GET", path))
        when (result) {
            is HttpResult.Body -> {
                if (!isSupportedImage(result.contentType, result.bytes)) {
                    HttpResult.Failed(HttpFailure.Protocol("Artwork response is not a supported image"))
                } else result
            }
            is HttpResult.NoContent -> HttpResult.Failed(HttpFailure.Protocol("Artwork endpoint returned no content"))
            else -> result
        }
    }

    private suspend inline fun <reified T> jsonGet(path: String): ApiResult<T> = withContext(dispatcher) {
        val transport = HttpTransport(originProvider(), client)
        execute(transport, transport.buildRequest("GET", path)).decodeJson<T>()
    }

    private fun pathSegment(value: String): String {
        require(value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..") {
            "Invalid path segment"
        }
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun isSupportedImage(contentType: String?, bytes: ByteArray): Boolean {
        if (!contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true)) return false
        val png = bytes.size >= 8 && bytes.take(8).map { it.toInt() and 0xff } == listOf(137, 80, 78, 71, 13, 10, 26, 10)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        return png || jpeg || gif || webp
    }
}
