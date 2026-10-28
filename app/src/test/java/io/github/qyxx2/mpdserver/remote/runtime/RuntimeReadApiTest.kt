package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class RuntimeReadApiTest {
    @Test fun resolvesCurrentOriginForEachReadAndUsesOnlyGetRequests() = runBlocking {
        var origin = ServerOrigin.parse("http://first.example:8000")
        val seen = mutableListOf<Request>()
        val api = RuntimeReadApi({ origin }, OkHttpClient()) { _, request ->
            seen += request
            HttpResult.Body(200, "application/json", """{"items":[],"count":0}""".toByteArray())
        }
        assertEquals(0, api.getPlayed().let {
            (it as io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success).value.count
        })
        origin = ServerOrigin.parse("https://second.example:9443")
        api.getPlayed()
        assertEquals("first.example", seen[0].url.host)
        assertEquals("second.example", seen[1].url.host)
        assertTrue(seen.all { it.url.encodedPath == "/api/history/played" })
        assertTrue(seen.all { it.method == "GET" && it.header("Content-Type") == null })
    }

    @Test fun playlistReadbackUsesTheExistingWebEndpointAndWireShape() = runBlocking {
        var path = ""
        var method = ""
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            path = request.url.encodedPath
            method = request.method
            HttpResult.Body(200, "application/json", """{"items":[],"count":0}""".toByteArray())
        }
        val result = api.getPlaylists() as io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success
        assertEquals("/api/playlists", path)
        assertEquals("GET", method)
        assertEquals(0, result.value.count)
    }

    @Test fun artistsReadUsesTheExistingWebNavigationEndpointAndWireShape() = runBlocking {
        var path = ""
        var method = ""
        val response = """{"items":[{"artist_id":"artist-1","name":"Artist One","song_count":3}],"count":1}""".toByteArray()
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, request ->
            path = request.url.encodedPath
            method = request.method
            HttpResult.Body(200, "application/json", response)
        }
        val result = api.getArtists() as io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success
        assertEquals("/api/library/artists", path)
        assertEquals("GET", method)
        assertEquals("artist-1", result.value.items.single().artistId)
        assertEquals("Artist One", result.value.items.single().name)
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
        val result = api.getSong("song-1") as io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success
        assertEquals("/api/library/songs/song-1", seenPath)
        assertEquals("song-1", result.value.songId)
    }

    @Test fun artworkNotFoundRemainsAnHttpError() = runBlocking {
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            HttpResult.HttpError(404, "application/json", "{}".toByteArray())
        }
        assertEquals(404, (api.getArtwork("missing") as HttpResult.HttpError).statusCode)
    }

    @Test fun rejectsUnsafeIdsBeforeRequestConstruction() = runBlocking {
        val api = RuntimeReadApi({ ServerOrigin.parse("http://server.example") }, OkHttpClient()) { _, _ ->
            fail("executor must not be called for an invalid id")
            HttpResult.NoContent()
        }
        try {
            api.getSong("../other")
            fail("Expected invalid path segment")
        } catch (_: IllegalArgumentException) {
        }
    }
}
