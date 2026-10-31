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

/** Runtime-owned, read-only access to typed Library, History, Search, Playlist, Favorites and artwork resources. */
class RuntimeReadApi(
    private val originProvider: () -> ServerOrigin,
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val execute: (HttpTransport, Request) -> HttpResult = { transport, request -> transport.execute(request) },
) {
    suspend fun getSongs(sortBy: String = "title", sortOrder: String = "asc"): ApiResult<SongListDto> =
        jsonGet(LibraryApiPaths.songs(sortBy, sortOrder))

    suspend fun getSong(songId: String): ApiResult<SongDto> = jsonGet(LibraryApiPaths.song(songId))

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
    suspend fun getHistory(): ApiResult<HistoryListDto> = jsonGet(LibraryApiPaths.history())

    /** Queue Played projection retained for the existing Queue screen. */
    suspend fun getPlayed(): ApiResult<QueueListDto> = jsonGet(LibraryApiPaths.played())

    suspend fun getSearchResults(query: String): ApiResult<SearchResultsDto> =
        jsonGet(LibraryApiPaths.searchResults(query))

    suspend fun getPlaylists(): ApiResult<PlaylistListDto> = jsonGet(LibraryApiPaths.playlists())
    suspend fun getPlaylist(playlistId: String): ApiResult<PlaylistDto> =
        jsonGet(LibraryApiPaths.playlist(playlistId))
    suspend fun getPlaylistSongs(playlistId: String): ApiResult<SongListDto> =
        jsonGet(LibraryApiPaths.playlistSongs(playlistId))

    suspend fun getFavoriteMembers(): ApiResult<FavoriteMembersDto> = jsonGet(LibraryApiPaths.favoriteMembers())
    suspend fun getFavorites(): ApiResult<CollectionDto> = jsonGet(LibraryApiPaths.favorites())

    /** Returns original image bytes; HTTP 404 stays distinguishable from malformed/non-image content. */
    suspend fun getArtwork(songId: String): HttpResult = withContext(dispatcher) {
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

    private suspend inline fun <reified T> jsonGet(path: String): ApiResult<T> = withContext(dispatcher) {
        val transport = HttpTransport(originProvider(), client)
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
