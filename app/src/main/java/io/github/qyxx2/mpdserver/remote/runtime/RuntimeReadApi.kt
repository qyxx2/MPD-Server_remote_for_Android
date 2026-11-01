package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.decodeJson
import io.github.qyxx2.mpdserver.remote.protocol.dto.AlbumListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtistListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.CollectionDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.FavoriteMembersDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.GenreListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatusDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.TagListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.YearListDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MAX_ARTWORK_RESPONSE_BYTES = 8 * 1024 * 1024

/** Runtime-owned, read-only access to typed Library, History, Search, Playlist, Favorites and artwork resources. */
class RuntimeReadApi(
    private val originProvider: () -> ServerOrigin,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resources: ResourceMemoryCache? = null,
    private val execute: (HttpTransport, Request) -> HttpResult = { transport, request ->
        if (request.url.encodedPath.endsWith("/artwork")) {
            transport.executeBounded(request, MAX_ARTWORK_RESPONSE_BYTES)
        } else {
            transport.execute(request)
        }
    },
) {
    suspend fun getSongs(sortBy: String = "title", sortOrder: String = "asc"): ApiResult<SongListDto> =
        jsonGet(LibraryApiPaths.songs(sortBy, sortOrder), ResourceDependency.LIBRARY)

    suspend fun getSong(songId: String): ApiResult<SongDto> {
        val cache = resources ?: return jsonGet(LibraryApiPaths.song(songId), ResourceDependency.LIBRARY)
        return cache.readSong(songId) { rawJsonGet(LibraryApiPaths.song(songId)) }
    }

    suspend fun getAlbums(): ApiResult<AlbumListDto> = jsonGet(LibraryApiPaths.albums())
    suspend fun getAlbumSongs(albumId: String): ApiResult<SongListDto> = jsonGet(LibraryApiPaths.albumSongs(albumId))

    /** Existing Web navigation lookup: artist IDs are resolved by exact server-returned display name. */
    suspend fun getArtists(): ApiResult<ArtistListDto> = jsonGet(LibraryApiPaths.artists())
    suspend fun getArtistSongs(artistId: String): ApiResult<SongListDto> = jsonGet(LibraryApiPaths.artistSongs(artistId))

    suspend fun getGenres(): ApiResult<GenreListDto> = jsonGet(LibraryApiPaths.genres())
    suspend fun getGenreSongs(genreId: String): ApiResult<SongListDto> = jsonGet(LibraryApiPaths.genreSongs(genreId))

    suspend fun getYears(): ApiResult<YearListDto> = jsonGet(LibraryApiPaths.years())
    suspend fun getYearSongs(year: String): ApiResult<SongListDto> = jsonGet(LibraryApiPaths.yearSongs(year))

    suspend fun getTags(): ApiResult<TagListDto> = jsonGet(LibraryApiPaths.tags())
    suspend fun getTagSongs(tagId: String): ApiResult<SongListDto> = jsonGet(LibraryApiPaths.tagSongs(tagId))

    /** Persistent History. This is deliberately separate from Queue's /api/history/played projection. */
    suspend fun getHistory(): ApiResult<HistoryListDto> = jsonGet(LibraryApiPaths.history(), ResourceDependency.HISTORY)

    /** Queue Played projection retained for the existing Queue screen. */
    // This Task 4 Queue endpoint is not persistent Home history and has no history watermark.
    // Leave it uncached, but still use AppRuntime's shared three-request resource GET limit.
    suspend fun getPlayed(): ApiResult<QueueListDto> {
        val loader: suspend () -> ApiResult<QueueListDto> = {
            rawJsonGet(LibraryApiPaths.played())
        }
        return resources?.readUncached(loader) ?: loader()
    }

    /**
     * Reads the live MPD status from the currently configured MS origin.
     * This endpoint is intentionally uncached because its values are not tied to Library revisions.
     * The request is bounded by the same global read semaphore as other uncached Runtime GETs.
     */
    suspend fun getMpdStatus(): ApiResult<MpdStatusDto> {
        val requestOrigin = originProvider()
        val loader: suspend () -> ApiResult<MpdStatusDto> = {
            val result = rawJsonGet<MpdStatusDto>("/api/system/mpd", requestOrigin)
            if (result is ApiResult.Success && originProvider().address != requestOrigin.address) {
                ApiResult.ProtocolError("MPD status response discarded because the server origin changed during the request")
            } else {
                result
            }
        }
        return resources?.readUncached(loader) ?: loader()
    }

    suspend fun getSearchResults(query: String): ApiResult<SearchResultsDto> =
        jsonGet(LibraryApiPaths.searchResults(query), ResourceDependency.LIBRARY)

    suspend fun getPlaylists(): ApiResult<PlaylistListDto> = jsonGet(LibraryApiPaths.playlists(), ResourceDependency.PLAYLIST)
    suspend fun getPlaylist(playlistId: String): ApiResult<PlaylistDto> =
        jsonGet(LibraryApiPaths.playlist(playlistId), ResourceDependency.PLAYLIST)
    suspend fun getPlaylistSongs(playlistId: String): ApiResult<SongListDto> =
        jsonGet(LibraryApiPaths.playlistSongs(playlistId), ResourceDependency.PLAYLIST)

    suspend fun getFavoriteMembers(): ApiResult<FavoriteMembersDto> = jsonGet(LibraryApiPaths.favoriteMembers(), ResourceDependency.PLAYLIST)
    suspend fun getFavorites(): ApiResult<CollectionDto> = jsonGet(LibraryApiPaths.favorites(), ResourceDependency.PLAYLIST)

    /** Explicit post-mutation reads bypass a previously fresh value while retaining the shared cache owner. */
    suspend fun refreshPlaylists(): ApiResult<PlaylistListDto> =
        refreshJsonGet(LibraryApiPaths.playlists(), ResourceDependency.PLAYLIST)

    suspend fun refreshPlaylist(playlistId: String): ApiResult<PlaylistDto> =
        refreshJsonGet(LibraryApiPaths.playlist(playlistId), ResourceDependency.PLAYLIST)

    suspend fun refreshPlaylistSongs(playlistId: String): ApiResult<SongListDto> =
        refreshJsonGet(LibraryApiPaths.playlistSongs(playlistId), ResourceDependency.PLAYLIST)

    suspend fun refreshFavoriteMembers(): ApiResult<FavoriteMembersDto> =
        refreshJsonGet(LibraryApiPaths.favoriteMembers(), ResourceDependency.PLAYLIST)

    suspend fun refreshFavorites(): ApiResult<CollectionDto> =
        refreshJsonGet(LibraryApiPaths.favorites(), ResourceDependency.PLAYLIST)

    /** Returns original image bytes. In the app runtime, one shared cache owns the raw response. */
    suspend fun getArtwork(songId: String): HttpResult {
        val cache = resources ?: return fetchArtwork(songId)
        val capturedIdentity = cache.captureLibraryIdentity()
            ?: return HttpResult.Failed(HttpFailure.Protocol("Resource Library identity is not established"))
        val songResult = cache.readSong(songId) { rawJsonGet(LibraryApiPaths.song(songId)) }
        val song = (songResult as? ApiResult.Success)?.value
            ?: return HttpResult.Failed(
                if (songResult is ApiResult.TransportError) songResult.failure
                else HttpFailure.Protocol("Song metadata is unavailable for artwork"),
            )
        if (!cache.isCurrent(capturedIdentity)) {
            return HttpResult.Failed(HttpFailure.Protocol("Artwork song metadata belongs to an obsolete Library identity"))
        }
        val artworkId = song.artwork?.artworkId?.takeIf(String::isNotBlank)
            ?: return HttpResult.Failed(HttpFailure.Protocol("Song has no artwork reference"))
        return cache.readArtwork(
            ArtworkCacheKey(artworkId, songId),
            expectedIdentity = capturedIdentity,
        ) { fetchArtwork(songId) }
    }

    private suspend fun fetchArtwork(songId: String): HttpResult = withContext(dispatcher) {
        val transport = HttpTransport(originProvider(), client)
        val result = execute(transport, transport.buildRequest("GET", LibraryApiPaths.songArtwork(songId)))
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

    private suspend inline fun <reified T : Any> refreshJsonGet(
        path: String,
        dependency: ResourceDependency,
    ): ApiResult<T> {
        resources?.invalidateResource(ResourceCacheKey.get<T>(path))
        return jsonGet(path, dependency)
    }

    private suspend inline fun <reified T : Any> jsonGet(
        path: String,
        dependency: ResourceDependency = ResourceDependency.LIBRARY,
    ): ApiResult<T> = withContext(dispatcher) {
        val loader: suspend () -> ApiResult<T> = { rawJsonGet(path) }
        val cache = resources
        val capturedIdentity = cache?.captureLibraryIdentity()
        val result = if (cache == null) loader() else {
            cache.readResource(ResourceCacheKey.get<T>(path), dependency, loader)
        }
        if (cache != null && result is ApiResult.Success && result.value is SongListDto) {
            val songList = result.value as SongListDto
            @Suppress("UNCHECKED_CAST")
            ApiResult.Success(
                songList.copy(items = cache.internSongs(songList.items, capturedIdentity)) as T,
                result.statusCode,
            )
        } else result
    }

    private suspend inline fun <reified T : Any> rawJsonGet(
        path: String,
        requestOrigin: ServerOrigin = originProvider(),
    ): ApiResult<T> = withContext(dispatcher) {
        val transport = HttpTransport(requestOrigin, client)
        execute(transport, transport.buildRequest("GET", path)).decodeJson<T>()
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
