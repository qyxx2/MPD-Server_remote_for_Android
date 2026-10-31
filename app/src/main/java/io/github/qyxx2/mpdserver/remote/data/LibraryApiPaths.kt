package io.github.qyxx2.mpdserver.remote.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Canonical, encoded paths for the existing MPD-Server read API. */
object LibraryApiPaths {
    private val baseUrl = "http://localhost".toHttpUrl()

    fun songs(sortBy: String = "title", sortOrder: String = "asc"): String {
        require(sortBy in SONG_SORT_FIELDS) { "Unsupported song sort field" }
        require(sortOrder in SORT_ORDERS) { "Unsupported song sort order" }
        return path("library", "songs", query = listOf("sort_by" to sortBy, "sort_order" to sortOrder))
    }

    fun song(songId: String): String = path("library", "songs", id(songId))
    fun songArtwork(songId: String): String = path("library", "songs", id(songId), "artwork")

    fun albums(): String = path("library", "albums")
    fun albumSongs(albumId: String): String = path("library", "albums", id(albumId), "songs")
    fun artists(): String = path("library", "artists")
    fun artistSongs(artistId: String): String = path("library", "artists", id(artistId), "songs")
    fun genres(): String = path("library", "genres")
    fun genreSongs(genreId: String): String = path("library", "genres", id(genreId), "songs")
    fun years(): String = path("library", "years")
    fun yearSongs(year: String): String = path("library", "years", id(year), "songs")
    fun tags(): String = path("library", "tags")
    fun tagSongs(tagId: String): String = path("library", "tags", id(tagId), "songs")

    fun history(): String = path("history")
    fun played(): String = path("history", "played")
    fun searchResults(query: String): String = path("library", "search", "results", query = listOf("q" to query))

    fun playlists(): String = path("playlists")
    fun playlist(playlistId: String): String = path("playlists", id(playlistId))
    fun playlistSongs(playlistId: String): String = path("playlists", id(playlistId), "songs")
    fun favoriteMembers(): String = path("favorites", "members")
    fun favorites(): String = path("favorites")

    private fun id(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && '/' !in value && '\\' !in value) {
            "Invalid API path segment"
        }
        return value
    }

    private fun path(
        vararg segments: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val builder = baseUrl.newBuilder().addPathSegment("api")
        segments.forEach(builder::addPathSegment)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        val url = builder.build()
        return buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { append('?').append(it) }
        }
    }

    private val SONG_SORT_FIELDS = setOf("title", "modified_at")
    private val SORT_ORDERS = setOf("asc", "desc")
}
