package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.ui.home.HomeProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cross-screen contract tests use the real RuntimeReadApi and ResourceMemoryCache owners.
 * Only the HTTP boundary is deterministic/faked; no second resource, queue, or playback owner
 * is introduced by the tests.
 */
class Task5CacheReuseTest {
    private val serverOrigin = ServerOrigin.parse("http://music.example:18000")
    private val baseSong = JsonConfiguration.json.parseToJsonElement(
        javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText(),
    ).jsonObject

    @Test
    fun homeSearchLibraryAlbumPlaylistFavoritesAndArtworkReuseOneResourceOwner() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val observedRequests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val ids = listOf("song-1", "song-2", "song-3")
        val songBody = songListJson(ids)
        val playlist = playlistJson()
        val api = RuntimeReadApi(
            originProvider = { serverOrigin },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                observedRequests += request
                val key = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
                calls.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
                val body = when (request.url.encodedPath) {
                    "/api/library/songs" -> songBody
                    "/api/library/albums" ->
                        """{"items":[{"album_id":"album-1","title":"Album","album_artists":["Artist"],"year":2024,"date":"2024-01-01","song_count":3,"artwork":null}],"count":1}"""
                    "/api/library/albums/album-1/songs" -> songBody
                    "/api/library/search/results" ->
                        """{"groups":[],"song_ids":["song-1","song-2"]}"""
                    "/api/history" -> historyBody(ids)
                    "/api/playlists" -> """{"items":[$playlist],"count":1}"""
                    "/api/playlists/playlist-1" -> playlist
                    "/api/playlists/playlist-1/songs" -> songBody
                    "/api/favorites/members" -> """{"song_ids":["song-1","song-2"]}"""
                    "/api/favorites" ->
                        """{"source_type":"FAVORITES","source_id":null,"song_ids":["song-1","song-2"],"unavailable_song_ids":[],"random_seed":null}"""
                    "/api/library/songs/song-1/artwork" -> return@RuntimeReadApi HttpResult.Body(
                        200,
                        "image/jpeg",
                        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01),
                    )
                    else -> songJson("song-1")
                }
                HttpResult.Body(200, "application/json; charset=utf-8", body.toByteArray())
            },
        )
        try {
            resources.updateIdentity(
                ResourceSessionIdentity(serverOrigin.address, 1L, "epoch-home"),
                ResourceWatermarks(12L, 4L, "history-a"),
                isFresh = true,
            )

            // Home resources: Recent Updates and persistent History.
            val recent = api.getSongs(sortBy = "modified_at", sortOrder = "desc") as ApiResult.Success
            val history = api.getHistory() as ApiResult.Success
            assertEquals(ids, recent.value.items.map { it.songId })
            assertEquals(ids, history.value.items.map { it.songId })

            // Search resolves the same Song IDs; no detail GET is needed after Home interned them.
            val search = api.getSearchResults("shared songs") as ApiResult.Success
            assertEquals(ids.take(2), search.value.songIds)
            val searchSong = api.getSong("song-1") as ApiResult.Success
            assertSame(recent.value.items[0], searchSong.value)

            // Library's different sort query is a distinct resource, reused on route re-entry.
            assertTrue(api.getSongs(sortBy = "title", sortOrder = "asc") is ApiResult.Success)
            assertTrue(api.getSongs(sortBy = "title", sortOrder = "asc") is ApiResult.Success)
            assertTrue(api.getSongs(sortBy = "modified_at", sortOrder = "desc") is ApiResult.Success)

            // Album summary and album members preserve server IDs and reuse the same Song entities.
            assertEquals("album-1", (api.getAlbums() as ApiResult.Success).value.items.single().albumId)
            val albumSongs = api.getAlbumSongs("album-1") as ApiResult.Success
            assertSame(recent.value.items[0], albumSongs.value.items[0])

            // Playlist and Favorites reads share the same cache identity and request owner.
            assertTrue(api.getPlaylists() is ApiResult.Success)
            assertTrue(api.getPlaylist("playlist-1") is ApiResult.Success)
            assertTrue(api.getPlaylistSongs("playlist-1") is ApiResult.Success)
            assertEquals(ids.take(2), (api.getFavoriteMembers() as ApiResult.Success).value.songIds)
            assertEquals(ids.take(2), (api.getFavorites() as ApiResult.Success).value.songIds)
            assertTrue(api.getPlaylists() is ApiResult.Success)
            assertTrue(api.getFavoriteMembers() is ApiResult.Success)

            // Queue/Player/Home share the Song and raw artwork owners; repeated artwork is one GET.
            val artworkFirst = api.getArtwork("song-1") as HttpResult.Body
            val artworkSecond = api.getArtwork("song-1") as HttpResult.Body
            assertSame(artworkFirst, artworkSecond)

            assertEquals(1, count(calls, "/api/library/songs?sort_by=modified_at&sort_order=desc"))
            assertEquals(1, count(calls, "/api/library/songs?sort_by=title&sort_order=asc"))
            assertEquals(1, count(calls, "/api/history"))
            assertEquals(1, count(calls, "/api/library/search/results?q=shared%20songs"))
            assertEquals(1, count(calls, "/api/library/albums"))
            assertEquals(1, count(calls, "/api/library/albums/album-1/songs"))
            assertEquals(1, count(calls, "/api/playlists"))
            assertEquals(1, count(calls, "/api/playlists/playlist-1"))
            assertEquals(1, count(calls, "/api/playlists/playlist-1/songs"))
            assertEquals(1, count(calls, "/api/favorites/members"))
            assertEquals(1, count(calls, "/api/favorites"))
            assertEquals(1, count(calls, "/api/library/songs/song-1/artwork"))
            assertEquals("the Home, Search and Library list sources must have interned the canonical Song entity", 0,
                calls.keys.count { it.startsWith("/api/library/songs/song-") && it != "/api/library/songs/song-1/artwork" })
            assertTrue(observedRequests.all { it.method == "GET" })
            assertFalse(observedRequests.any { it.url.encodedPath.startsWith("/api/playback/queue") })
            assertFalse(observedRequests.any { it.url.encodedPath == "/api/history/played" })
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test
    fun fourHundredSongAndQueueFixturePreservesOccurrencesWithoutQueueGetOrHistoryNPlusOne() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val ids = (0 until 400).map { "song-$it" }
        val imageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01)
        val api = RuntimeReadApi(
            originProvider = { serverOrigin },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                val path = request.url.encodedPath
                calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val body = when (path) {
                    "/api/library/songs" -> songListJson(ids)
                    "/api/history" -> historyBody(ids)
                    "/api/library/songs/song-0/artwork" -> return@RuntimeReadApi HttpResult.Body(200, "image/jpeg", imageBytes)
                    else -> songJson("song-0")
                }
                HttpResult.Body(200, "application/json", body.toByteArray())
            },
        )
        try {
            resources.updateIdentity(
                ResourceSessionIdentity(serverOrigin.address, 1L, "epoch-400"),
                ResourceWatermarks(20L, 6L, "history-400"),
                isFresh = true,
            )

            val serverOrderedSongs = api.getSongs(sortBy = "modified_at", sortOrder = "desc") as ApiResult.Success
            assertEquals(400, serverOrderedSongs.value.items.size)
            assertEquals(ids, serverOrderedSongs.value.items.map { it.songId })
            assertEquals(400, api.getHistory().let { (it as ApiResult.Success).value.items.size })

            // Detail resolution for every Song already present in a list must not cause 400 extra GETs.
            ids.forEachIndexed { index, id ->
                val result = api.getSong(id) as ApiResult.Success
                assertSame(serverOrderedSongs.value.items[index], result.value)
            }
            assertEquals(1, count(calls, "/api/library/songs"))
            assertEquals(1, count(calls, "/api/history"))
            assertEquals(0, calls.keys.count { it.startsWith("/api/library/songs/song-") })

            // Accepted server snapshot owns Queue/AutoPlay; 400 occurrences may refer to 200 songs.
            val snapshot = snapshotWithAutoplay(ids.take(200), occurrenceCount = 400)
            val autoplay = HomeProjection.autoplay(snapshot, serverOrderedSongs.value.items)
            assertEquals(400, autoplay.cards.size)
            assertEquals((0 until 400).map { "queue-$it" }, autoplay.cards.map { it.queueItemId })
            assertEquals((0 until 400).map { "song-${it % 200}" }, autoplay.cards.map { it.songId })
            assertEquals(0, calls.keys.count { it.contains("/api/playback/queue") })
            assertTrue(api.getArtwork("song-0") is HttpResult.Body)
            assertEquals(1, count(calls, "/api/library/songs/song-0/artwork"))
            assertEquals(400, ids.count { resources.peekSong(it) != null })
            assertTrue(resources.metadataTotalWeightBytesForTest <= 16L * 1024L * 1024L)
            assertTrue(resources.artworkRetainedBytesForTest <= 24L * 1024L * 1024L)
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test
    fun libraryAndPlaylistRevisionsInvalidateOnlyTheirDependentScreenResources() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val api = RuntimeReadApi(
            originProvider = { serverOrigin },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, request ->
                val path = request.url.encodedPath
                calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                val body = when (path) {
                    "/api/library/songs" -> songListJson(listOf("song-1"))
                    "/api/history" -> historyBody(listOf("song-1"))
                    "/api/playlists" -> """{"items":[],"count":0}"""
                    "/api/favorites/members" -> """{"song_ids":["song-1"]}"""
                    "/api/library/search/results" -> """{"groups":[],"song_ids":["song-1"]}"""
                    else -> """{"items":[],"count":0}"""
                }
                HttpResult.Body(200, "application/json", body.toByteArray())
            },
        )
        try {
            val identity = ResourceSessionIdentity(serverOrigin.address, 3L, "epoch-revisions")
            resources.updateIdentity(identity, ResourceWatermarks(1L, 1L, "history-1"), true)

            api.getSongs()
            api.getHistory()
            api.getPlaylists()
            api.getFavoriteMembers()
            api.getSearchResults("song")

            resources.updateIdentity(identity, ResourceWatermarks(1L, 2L, "history-1"), true)
            api.getSongs()
            api.getHistory()
            api.getPlaylists()
            api.getFavoriteMembers()
            api.getSearchResults("song")

            assertEquals(1, count(calls, "/api/library/songs"))
            assertEquals(1, count(calls, "/api/history"))
            assertEquals(2, count(calls, "/api/playlists"))
            assertEquals(2, count(calls, "/api/favorites/members"))
            assertEquals(1, count(calls, "/api/library/search/results"))

            resources.updateIdentity(identity, ResourceWatermarks(2L, 2L, "history-1"), true)
            api.getSongs()
            api.getHistory()
            api.getPlaylists()
            api.getFavoriteMembers()
            api.getSearchResults("song")

            assertEquals(2, count(calls, "/api/library/songs"))
            assertEquals(1, count(calls, "/api/history"))
            assertEquals(3, count(calls, "/api/playlists"))
            assertEquals(3, count(calls, "/api/favorites/members"))
            assertEquals(2, count(calls, "/api/library/search/results"))
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    private fun count(calls: Map<String, AtomicInteger>, key: String): Int = calls[key]?.get() ?: 0

    private fun songJson(id: String): String = JsonObject(baseSong.toMutableMap().apply {
        this["song_id"] = JsonPrimitive(id)
        this["title"] = JsonPrimitive("Title $id")
        this["file_uri"] = JsonPrimitive("music/$id.flac")
    }).toString()

    private fun songListJson(ids: List<String>): String = buildJsonObject {
        put("count", JsonPrimitive(ids.size))
        putJsonArray("items") {
            ids.forEach { id -> add(JsonConfiguration.json.parseToJsonElement(songJson(id))) }
        }
    }.toString()

    private fun historyBody(ids: List<String>): String = buildJsonObject {
        put("count", JsonPrimitive(ids.size))
        putJsonArray("items") {
            ids.forEachIndexed { index, id ->
                add(buildJsonObject {
                    put("history_id", JsonPrimitive(index.toLong() + 1L))
                    put("song_id", JsonPrimitive(id))
                    put("started_at", JsonPrimitive("2026-10-31T10:${(index / 60).toString().padStart(2, '0')}:${(index % 60).toString().padStart(2, '0')}Z"))
                    put("ended_at", kotlinx.serialization.json.JsonNull)
                    put("reason", JsonPrimitive("completed"))
                    put("session_id", JsonPrimitive("session-1"))
                })
            }
        }
    }.toString()

    private fun playlistJson(): String =
        """{"playlist_id":"playlist-1","name":"Mix","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-02T00:00:00Z","is_system":false,"song_ids":["song-1","song-2"]}"""

    private fun snapshotWithAutoplay(songIds: List<String>, occurrenceCount: Int): io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot {
        val fixture = javaClass.classLoader!!.getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val json = JsonConfiguration.json
        val root = json.parseToJsonElement(fixture).jsonObject.toMutableMap()
        root["playback"] = json.parseToJsonElement(
            """{"song_id":"current","state":"PLAYING","playback_context_id":"context-400","position_seconds":5.0,"autoplay_enabled":true,"updated_at":"2026-10-31T10:00:00Z"}""",
        )
        val queueJson = buildJsonObject {
            put("revision", JsonPrimitive(19))
            putJsonArray("items") {
                repeat(occurrenceCount) { index ->
                    add(buildJsonObject {
                        put("queue_item_id", JsonPrimitive("queue-$index"))
                        put("song_id", JsonPrimitive(songIds[index % songIds.size]))
                        put("position", JsonPrimitive(index + 1))
                        put("source", JsonPrimitive("AUTOPLAY"))
                        put("playback_context_id", JsonPrimitive("context-400"))
                    })
                }
            }
        }
        root["queue"] = queueJson
        return FullStateSnapshot.from(
            json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root)),
        )
    }
}
