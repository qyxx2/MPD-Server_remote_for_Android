package io.github.qyxx2.mpdserver.remote.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.ApiErrorEnvelope
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.decodeFromString
import java.security.MessageDigest

/** Server/session boundary for process-local GET resources; never contains playback progress. */
data class ResourceSessionIdentity(
    val originAddress: String,
    val connectionGeneration: Long,
    val epoch: String,
)

data class ResourceWatermarks(
    val libraryRevision: Long,
    val playlistRevision: Long,
    val historyFingerprint: String,
)

/** Observable owner identity; lets consumers wait for the AppRuntime snapshot watermark handoff. */
data class ResourceCacheIdentityState(
    val session: ResourceSessionIdentity? = null,
    val watermarks: ResourceWatermarks? = null,
    val isFresh: Boolean = false,
    val isClosed: Boolean = false,
)

/** Captured Library identity used to prevent an old GET response being interned into a new session. */
data class ResourceLibraryIdentity(
    val session: ResourceSessionIdentity,
    val libraryRevision: Long,
)

enum class ResourceDependency { SESSION, LIBRARY, PLAYLIST, HISTORY }

/** Typed per-entry revision dependency; unrelated snapshot revisions cannot alias. */
sealed interface ResourceDependencyStamp {
    data object Session : ResourceDependencyStamp
    data class Library(val revision: Long) : ResourceDependencyStamp
    data class Playlist(val libraryRevision: Long, val playlistRevision: Long) : ResourceDependencyStamp
    data class History(val fingerprint: String) : ResourceDependencyStamp
}

/** Request path and response DTO type are both part of cache identity. */
class ResourceCacheKey<T : Any> @PublishedApi internal constructor(
    val method: String,
    val canonicalPathAndQuery: String,
    val responseTypeKey: String,
) {
    init {
        require(method == "GET") { "Only GET resources can be cached" }
        require(canonicalPathAndQuery.startsWith('/')) { "Resource path must be absolute" }
        require('\r' !in canonicalPathAndQuery && '\n' !in canonicalPathAndQuery) {
            "Invalid resource path"
        }
        require(responseTypeKey.isNotBlank()) { "Response type identity must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceCacheKey<*> &&
            method == other.method &&
            canonicalPathAndQuery == other.canonicalPathAndQuery &&
            responseTypeKey == other.responseTypeKey

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + canonicalPathAndQuery.hashCode()
        result = 31 * result + responseTypeKey.hashCode()
        return result
    }

    override fun toString(): String = "$method $canonicalPathAndQuery <$responseTypeKey>"

    companion object {
        inline fun <reified T : Any> get(pathAndQuery: String): ResourceCacheKey<T> {
            require(pathAndQuery.startsWith('/')) { "Resource path must be absolute" }
            require('\r' !in pathAndQuery && '\n' !in pathAndQuery) { "Invalid resource path" }
            return ResourceCacheKey(
                method = "GET",
                canonicalPathAndQuery = pathAndQuery,
                responseTypeKey = T::class.java.name,
            )
        }
    }
}

data class ArtworkCacheKey(
    val artworkId: String,
    val songId: String,
) {
    init {
        require(artworkId.isNotBlank()) { "Artwork ID must not be blank" }
        require(songId.isNotBlank()) { "Song ID must not be blank" }
    }
}

/** Negative artwork caching is restricted to the server's explicit structured absence code. */
internal fun HttpResult?.isExplicitArtworkMissing(): Boolean {
    if (this !is HttpResult.HttpError || statusCode != 404 ||
        !contentType.orEmpty().contains("json", ignoreCase = true)
    ) return false
    return runCatching {
        JsonConfiguration.json.decodeFromString<ApiErrorEnvelope>(bytes.toString(Charsets.UTF_8))
            .error.code == "ARTWORK_NOT_FOUND"
    }.getOrDefault(false)
}

/** Complete history payload fingerprint. Object key ordering is normalized; array order is semantic. */
internal fun historyResourceFingerprint(history: JsonElement?): String {
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${JsonPrimitive(key)}:${canonical(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        JsonNull -> "null"
        is JsonPrimitive -> element.toString()
    }
    val source = canonical(history ?: JsonNull)
    return MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
