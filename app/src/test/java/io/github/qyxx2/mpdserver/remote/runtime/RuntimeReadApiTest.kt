package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.CollectionDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class RuntimeReadApiTest {
    private fun emptyListBody() = """{"items":[],"count":0}""".toByteArray()

    @Test fun resolvesCurrentOriginForEachReadAndUsesOnlyGetRequests() = runBlocking {
        var origin = ServerOrigin.parse("http://first.example:8000")
        val seen = mutableListOf<Request>()
        val api = RuntimeReadApi({ origin }, OkHttpClient()) { _, request ->
            seen += request
            HttpResult.Body(200, "application/json", emptyListBody())
        }
        assertEquals(0, (api.getPlayed() as ApiResult.Success).value.count)
        origin = ServerOrigin.parse("https://second.example:9443")
        api.getPlayed()
        assertEquals("first.example", seen[0].url.host)
        assertEquals("second.example", seen[1].url.host)
        assertTrue(seen.all { it.url.encodedPath == "/api/history/played" })
        assertTrue(seen.all { it.method == "GET" && it.header("Content-Type") == null })
    }

    @Test fun playlistListReadUsesExistingEndpointAndWireShape() = runBlocking {
        var path = ""
        var method = ""
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            path = request.url.encodedPath
            method = request.method
            HttpResult.Body(200, "application/json", emptyListBody())
        }
        val result = api.getPlaylists() as ApiResult.Success
        assertEquals("/api/playlists", path)
        assertEquals("GET", method)
        assertEquals(0, result.value.count)
    }

    @Test fun artistsReadUsesExistingNavigationEndpointAndWireShape() = runBlocking {
        var path = ""
        var method = ""
        val response = """{"items":[{"artist_id":"artist-1","name":"Artist One","song_count":3}],"count":1}""".toByteArray()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            path = request.url.encodedPath
            method = request.method
            HttpResult.Body(200, "application/json", response)
        }
        val result = api.getArtists() as ApiResult.Success
        assertEquals("/api/library/artists", path)
        assertEquals("GET", method)
        assertEquals("artist-1", result.value.items.single().artistId)
        assertEquals("Artist One", result.value.items.single().name)
    }

    @Test fun songsAlwaysUseAnExplicitServerSortAndRejectUnsupportedValues() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            requests += request
            HttpResult.Body(200, "application/json", emptyListBody())
        }
        assertEquals(0, (api.getSongs() as ApiResult.Success<SongListDto>).value.count)
        api.getSongs(sortBy = "modified_at", sortOrder = "desc")
        assertEquals("title", requests[0].url.queryParameter("sort_by"))
        assertEquals("asc", requests[0].url.queryParameter("sort_order"))
        assertEquals("modified_at", requests[1].url.queryParameter("sort_by"))
        assertEquals("desc", requests[1].url.queryParameter("sort_order"))
        assertTrue(requests.all { it.url.encodedPath == "/api/library/songs" && it.method == "GET" })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.getSongs("file_uri", "asc") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.getSongs("title", "random") } }
        Unit
    }

    @Test fun typedCategoryAndMemberReadsMapOnlyToExistingGetRoutes() = runBlocking {
        val requests = mutableListOf<Request>()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            requests += request
            HttpResult.Body(200, "application/json", emptyListBody())
        }
        assertEquals(0, (api.getAlbums() as ApiResult.Success).value.count)
        assertEquals(0, (api.getArtists() as ApiResult.Success).value.count)
        assertEquals(0, (api.getGenres() as ApiResult.Success).value.count)
        assertEquals(0, (api.getYears() as ApiResult.Success).value.count)
        assertEquals(0, (api.getTags() as ApiResult.Success).value.count)
        api.getAlbumSongs("album-1")
        api.getArtistSongs("artist-1")
        api.getGenreSongs("genre-1")
        api.getYearSongs("2024")
        api.getTagSongs("tag-1")
        assertEquals(
            listOf(
                "/api/library/albums", "/api/library/artists", "/api/library/genres",
                "/api/library/years", "/api/library/tags", "/api/library/albums/album-1/songs",
                "/api/library/artists/artist-1/songs", "/api/library/genres/genre-1/songs",
                "/api/library/years/2024/songs", "/api/library/tags/tag-1/songs",
            ),
            requests.map { it.url.encodedPath },
        )
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun persistentHistoryAndQueuePlayedUseDistinctTypedEndpoints() = runBlocking {
        val paths = mutableListOf<String>()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            paths += request.url.encodedPath
            val json = when (request.url.encodedPath) {
                "/api/history" -> """{"items":[{"history_id":7,"song_id":"song-1","started_at":"2026-10-31T10:00:00Z","ended_at":null,"reason":null,"session_id":"s1"}],"count":1}"""
                else -> """{"items":[{"queue_item_id":"played-1","song_id":"song-1","position":-1,"source":"MANUAL","playback_context_id":null}],"count":1}"""
            }
            HttpResult.Body(200, "application/json", json.toByteArray())
        }
        val history = api.getHistory() as ApiResult.Success<HistoryListDto>
        val played = api.getPlayed() as ApiResult.Success<QueueListDto>
        assertEquals(7L, history.value.items.single().historyId)
        assertEquals("played-1", played.value.items.single().queueItemId)
        assertEquals(listOf("/api/history", "/api/history/played"), paths)
    }

    @Test fun searchQueryIsEncodedAsOneUtf8QueryParameter() = runBlocking {
        val query = "周杰伦 & Live #1 + 曲目/试听?"
        var seen: Request? = null
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            seen = request
            HttpResult.Body(200, "application/json", """{"groups":[],"song_ids":[]}""".toByteArray())
        }
        val result = api.getSearchResults(query) as ApiResult.Success<SearchResultsDto>
        assertTrue(result.value.groups.isEmpty())
        assertEquals("/api/library/search/results", seen!!.url.encodedPath)
        assertEquals(query, seen!!.url.queryParameter("q"))
        assertEquals("GET", seen!!.method)
        assertTrue(seen!!.url.encodedQuery!!.contains("%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        assertTrue(seen!!.url.encodedQuery!!.contains("%26"))
        assertTrue(seen!!.url.encodedQuery!!.contains("%23"))
        assertTrue(seen!!.url.encodedQuery!!.contains("%2B"))
        assertTrue(seen!!.url.encodedQuery!!.contains("%2F"))
        assertTrue(seen!!.url.encodedQuery!!.contains("%3F"))
    }

    @Test fun playlistDetailMembersAndFavoritesHaveTypedReadMethods() = runBlocking {
        val paths = mutableListOf<String>()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/api/playlists/user-1" -> """{"playlist_id":"user-1","name":"Road Trip","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z","is_system":false,"song_ids":["song-1"]}"""
                "/api/favorites/members" -> """{"song_ids":["song-1"]}"""
                "/api/favorites" -> """{"source_type":"FAVORITES","source_id":null,"song_ids":["song-1"],"unavailable_song_ids":[],"random_seed":null}"""
                else -> emptyListBody().decodeToString()
            }
            HttpResult.Body(200, "application/json", body.toByteArray())
        }
        val playlist = api.getPlaylist("user-1") as ApiResult.Success
        val songs = api.getPlaylistSongs("user-1") as ApiResult.Success
        val members = api.getFavoriteMembers() as ApiResult.Success
        val favorites = api.getFavorites() as ApiResult.Success<CollectionDto>
        assertEquals("user-1", playlist.value.playlistId)
        assertTrue(songs.value.items.isEmpty())
        assertEquals(listOf("song-1"), members.value.songIds)
        assertEquals(listOf("song-1"), favorites.value.songIds)
        assertEquals(
            listOf("/api/playlists/user-1", "/api/playlists/user-1/songs", "/api/favorites/members", "/api/favorites"),
            paths,
        )
    }

    @Test fun artworkReturnsOriginalImageBytesAndRejectsNonImageBodies() = runBlocking {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
        var artworkPath = ""
        val pathCheckingApi = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            artworkPath = request.url.encodedPath
            HttpResult.Body(200, "image/jpeg", jpeg)
        }
        val result = pathCheckingApi.getArtwork("song-1") as HttpResult.Body
        assertEquals("/api/library/songs/song-1/artwork", artworkPath)
        assertArrayEquals(jpeg, result.bytes)
        val invalid = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            HttpResult.Body(200, "image/jpeg", "not image".toByteArray())
        }
        assertTrue(invalid.getArtwork("song-1") is HttpResult.Failed)
    }

    @Test fun songReadUsesContractedPathAndDecodesCompleteSongResponse() = runBlocking {
        var seenPath = ""
        val body = javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText().toByteArray()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            seenPath = request.url.encodedPath
            HttpResult.Body(200, "application/json; charset=utf-8", body)
        }
        val result = api.getSong("song-1") as ApiResult.Success
        assertEquals("/api/library/songs/song-1", seenPath)
        assertEquals("song-1", result.value.songId)
    }

    @Test fun pathSegmentsAreUtf8EncodedAndTraversalIsRejected() = runBlocking {
        var seen: Request? = null
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            seen = request
            HttpResult.Body(200, "application/json", javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readBytes())
        }
        api.getSong("歌曲 & # + ?")
        assertEquals("歌曲 & # + ?", seen!!.url.pathSegments.last())
        assertTrue(seen!!.url.encodedPath.contains("%E6%AD%8C%E6%9B%B2"))
        listOf("", " ", ".", "..", "../other", "a/b", "a\\b").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.getSong(invalid) } }
        }
    }

    @Test fun jsonErrorEnvelopeRemainsTypedAndMalformedEnvelopeIsNotSilentlyAccepted() = runBlocking {
        val business = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            HttpResult.HttpError(
                404,
                "application/json",
                """{"error":{"code":"SONG_NOT_FOUND","message":"song not found","details":null}}""".toByteArray(),
            )
        }
        val error = business.getSong("missing") as ApiResult.BusinessError
        assertEquals(404, error.statusCode)
        assertEquals("SONG_NOT_FOUND", error.error.code)

        val malformed = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            HttpResult.HttpError(404, "application/json", """{"message":"missing"}""".toByteArray())
        }
        assertTrue(malformed.getSong("missing") is ApiResult.DecodeError)
    }

    @Test fun queuePlayedReadBypassesPersistentHistoryTtlCache() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        resources.updateIdentity(
            ResourceSessionIdentity("http://server.example", 1, "epoch-1"),
            ResourceWatermarks(3, 5, "history-1"),
            isFresh = true,
        )
        val paths = mutableListOf<String>()
        val api = RuntimeReadApi(
            originProvider = { ServerOrigin.parse("http://server.example") },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                paths += request.url.encodedPath
                HttpResult.Body(200, "application/json", emptyListBody())
            },
        )
        try {
            repeat(2) {
                assertTrue(api.getPlayed() is ApiResult.Success)
            }
            assertEquals(
                listOf("/api/history/played", "/api/history/played"),
                paths,
            )
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test fun appRuntimeSongListReadsShareResourcesAndInternCanonicalSongs() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        resources.updateIdentity(
            ResourceSessionIdentity("http://server.example", 1, "epoch-1"),
            ResourceWatermarks(3, 5, "history-1"),
            isFresh = true,
        )
        val fixtureSong = javaClass.classLoader!!
            .getResource("fixtures/task4/song-response.json")!!.readText()
        val listBody = """{"items":[$fixtureSong],"count":1}""".toByteArray()
        var networkReads = 0
        val seenQueries = mutableListOf<String?>()
        val api = RuntimeReadApi(
            originProvider = { ServerOrigin.parse("http://server.example") },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                networkReads += 1
                seenQueries += request.url.queryParameter("sort_by")
                assertEquals("/api/library/songs", request.url.encodedPath)
                HttpResult.Body(200, "application/json", listBody)
            },
        )
        try {
            val first = api.getSongs() as ApiResult.Success
            val second = api.getSongs() as ApiResult.Success
            assertEquals(1, networkReads)
            assertSame(first.value.items.single(), second.value.items.single())

            val detail = api.getSong("song-1") as ApiResult.Success
            assertSame(first.value.items.single(), detail.value)

            api.getSongs(sortBy = "modified_at", sortOrder = "desc")
            assertEquals(2, networkReads)
            assertEquals(listOf("title", "modified_at"), seenQueries)
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test fun appRuntimeArtworkReadsShareTheSameRawArtworkEntry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        resources.updateIdentity(
            ResourceSessionIdentity("http://server.example", 1, "epoch-1"),
            ResourceWatermarks(3, 5, "history-1"),
            isFresh = true,
        )
        val songBody = javaClass.classLoader!!
            .getResource("fixtures/task4/song-response.json")!!.readBytes()
        val imageBytes = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        val paths = mutableListOf<String>()
        val api = RuntimeReadApi(
            originProvider = { ServerOrigin.parse("http://server.example") },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath.endsWith("/artwork")) {
                    HttpResult.Body(200, "image/png", imageBytes)
                } else {
                    HttpResult.Body(200, "application/json", songBody)
                }
            },
        )
        try {
            val first = api.getArtwork("song-1") as HttpResult.Body
            val second = api.getArtwork("song-1") as HttpResult.Body
            assertEquals(imageBytes.toList(), first.bytes.toList())
            assertEquals(imageBytes.toList(), second.bytes.toList())
            assertEquals(
                listOf("/api/library/songs/song-1", "/api/library/songs/song-1/artwork"),
                paths,
            )
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test fun artworkNotFoundRemainsAnHttpError() = runBlocking {
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            HttpResult.HttpError(404, "application/json", "{}".toByteArray())
        }
        assertEquals(404, (api.getArtwork("missing") as HttpResult.HttpError).statusCode)
    }
}
