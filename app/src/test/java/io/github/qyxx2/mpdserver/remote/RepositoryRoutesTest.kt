package io.github.qyxx2.mpdserver.remote.data

import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryRoutesTest {
    @Test fun repositoriesBuildOnlyContractedApiPaths() {
        val transport = HttpTransport(ServerOrigin.parse("http://music.example:18000"), OkHttpClient())
        assertEquals("http://music.example:18000/api/state", transport.buildRequest("GET", "/api/state").url.toString())
        assertEquals("http://music.example:18000/api/system/output", transport.buildRequest("GET", "/api/system/output").url.toString())
        assertEquals("http://music.example:18000/api/playback/state", transport.buildRequest("GET", "/api/playback/state").url.toString())
        assertEquals("http://music.example:18000/api/library/songs", transport.buildRequest("GET", "/api/library/songs").url.toString())
        assertEquals("http://music.example:18000/api/playlists", transport.buildRequest("GET", "/api/playlists").url.toString())
    }

    @Test fun resourceIdentifiersCannotInjectAdditionalPathSegments() {
        val repository = LibraryRepository(HttpTransport(ServerOrigin.parse("http://music.example:18000"), OkHttpClient()))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { repository.getSong("../admin") }
    }
}
