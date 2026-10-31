package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

/** Server-owned source description for POST /api/playback/collections/play. */
class CollectionPlaybackRequest internal constructor(
    val sourceType: CollectionPlaybackSource,
    val sourceId: String? = null,
    val query: String? = null,
    songIds: List<String> = emptyList(),
    val randomize: Boolean = false,
    val sortBy: String? = null,
    val sortOrder: String? = null,
) {
    /** Defensive copy prevents a mutable UI/resource list from changing an already-built intent. */
    val songIds: List<String> = songIds.toList()

    fun withRandomize(randomize: Boolean): CollectionPlaybackRequest = CollectionPlaybackRequest(
        sourceType = sourceType,
        sourceId = sourceId,
        query = query,
        songIds = songIds,
        randomize = randomize,
        sortBy = sortBy,
        sortOrder = sortOrder,
    )

    internal fun isValid(): Boolean {
        if (sortOrder != null && sortOrder !in setOf("asc", "desc")) return false
        if (sortBy != null && sortBy !in setOf("title", "modified_at")) return false
        val idSources = setOf(
            CollectionPlaybackSource.ALBUM,
            CollectionPlaybackSource.ARTIST,
            CollectionPlaybackSource.GENRE,
            CollectionPlaybackSource.YEAR,
            CollectionPlaybackSource.TAG,
            CollectionPlaybackSource.PLAYLIST,
        )
        // Mirror the backend's frozen CollectionRequest source guard exactly. In particular,
        // sorting fields are not sent for non-LIBRARY sources, and SONGS alone carries song_ids.
        if (sourceType in idSources && sourceId.isNullOrBlank()) return false
        if (sourceType !in idSources && sourceId != null) return false
        if (sourceType == CollectionPlaybackSource.SEARCH) {
            if (query == null) return false
        } else if (query != null) return false
        if (sourceType != CollectionPlaybackSource.SONGS && songIds.isNotEmpty()) return false
        if (sourceType != CollectionPlaybackSource.LIBRARY && (sortBy != null || sortOrder != null)) return false
        if (sourceType == CollectionPlaybackSource.SONGS &&
            (songIds.isEmpty() || songIds.any(String::isBlank) || songIds.distinct().size != songIds.size)
        ) return false
        return true
    }

    companion object {
        fun forCategory(category: LibraryCategory, sourceId: String): CollectionPlaybackRequest? {
            if (sourceId.isBlank()) return null
            val source = when (category) {
                LibraryCategory.ALBUMS -> CollectionPlaybackSource.ALBUM
                LibraryCategory.ARTISTS -> CollectionPlaybackSource.ARTIST
                LibraryCategory.GENRES -> CollectionPlaybackSource.GENRE
                LibraryCategory.YEARS -> CollectionPlaybackSource.YEAR
                LibraryCategory.TAGS -> CollectionPlaybackSource.TAG
                else -> return null
            }
            return CollectionPlaybackRequest(sourceType = source, sourceId = sourceId)
        }

        fun forSongs(
            sortBy: String? = null,
            sortOrder: String? = null,
            randomize: Boolean = false,
        ): CollectionPlaybackRequest? {
            if (sortBy != null && sortBy !in setOf("title", "modified_at")) return null
            if (sortOrder != null && sortOrder !in setOf("asc", "desc")) return null
            return CollectionPlaybackRequest(
                sourceType = CollectionPlaybackSource.LIBRARY,
                randomize = randomize,
                sortBy = sortBy,
                sortOrder = sortOrder,
            )
        }

        fun forPlaylist(playlistId: String): CollectionPlaybackRequest? =
            playlistId.takeIf(String::isNotBlank)?.let {
                CollectionPlaybackRequest(CollectionPlaybackSource.PLAYLIST, sourceId = it)
            }

        fun forFavorites(): CollectionPlaybackRequest =
            CollectionPlaybackRequest(CollectionPlaybackSource.FAVORITES)

        fun forSearch(query: String): CollectionPlaybackRequest? =
            query.trim().takeIf(String::isNotEmpty)?.let {
                CollectionPlaybackRequest(CollectionPlaybackSource.SEARCH, query = it)
            }

        /** Deduplicate by first occurrence; this mirrors the full persistent History source order. */
        fun forSongIds(songIds: List<String>): CollectionPlaybackRequest? {
            val unique = LinkedHashSet<String>()
            songIds.forEach { id -> if (id.isNotBlank()) unique += id }
            if (unique.isEmpty()) return null
            return CollectionPlaybackRequest(CollectionPlaybackSource.SONGS, songIds = unique.toList())
        }
    }
}

enum class CollectionPlaybackSource(val wireValue: String) {
    ALBUM("ALBUM"), ARTIST("ARTIST"), GENRE("GENRE"), YEAR("YEAR"), TAG("TAG"),
    SEARCH("SEARCH"), PLAYLIST("PLAYLIST"), FAVORITES("FAVORITES"), LIBRARY("LIBRARY"), SONGS("SONGS"),
}

internal object CollectionPlaybackPayload {
    fun encode(request: CollectionPlaybackRequest): String = buildJsonObject {
        put("source_type", JsonPrimitive(request.sourceType.wireValue))
        request.sourceId?.let { put("source_id", JsonPrimitive(it)) }
        request.query?.let { put("query", JsonPrimitive(it)) }
        if (request.sourceType == CollectionPlaybackSource.SONGS) {
            put("song_ids", buildJsonArray { request.songIds.forEach { add(JsonPrimitive(it)) } })
        }
        put("randomize", JsonPrimitive(request.randomize))
        request.sortBy?.let { put("sort_by", JsonPrimitive(it)) }
        request.sortOrder?.let { put("sort_order", JsonPrimitive(it)) }
    }.toString()
}

/** Only submits collection intents; collection resolution, ordering and shuffle stay on the server. */
class LibraryCollectionActions internal constructor(
    private val gateway: PlaybackMutationGateway,
) {
    constructor(runtime: AppRuntime) : this(PlaybackMutationGateway(runtime))

    fun canPlay(resourceFresh: Boolean = true): Boolean = gateway.canSubmit(resourceFresh)

    fun play(
        request: CollectionPlaybackRequest,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        if (!request.isValid() || !gateway.canSubmit(resourceFresh)) return null
        return gateway.submit(
            intentPrefix = "collection",
            path = "/api/playback/collections/play",
            payload = CollectionPlaybackPayload.encode(request),
            label = "${request.sourceType.wireValue} collection ${if (request.randomize) "随机播放" else "全部播放"}",
            resourceFresh = resourceFresh,
        )
    }

    fun activity(intentId: String): OperationActivity? = gateway.activity(intentId)
    fun isReconciled(intentId: String): Boolean = gateway.isReconciled(intentId)
    fun retryUnknown(intentId: String): MutationOutcome? = gateway.retry(intentId)
    fun activities(): StateFlow<List<OperationActivity>> = gateway.activities()
    val lastSubmittedIntentId: String? get() = gateway.lastSubmittedIntentId
}
