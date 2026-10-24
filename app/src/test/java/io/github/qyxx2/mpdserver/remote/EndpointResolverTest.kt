package io.github.qyxx2.mpdserver.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointResolverTest {
    @Test fun httpAndWsShareOrigin() {
        val resolver = EndpointResolver(ServerOrigin.parse("https://music.example:18000/base/"))
        assertEquals("https://music.example:18000/api/state", resolver.http("/api/state").toString())
        assertEquals("wss://music.example:18000/api/realtime", resolver.webSocket("/api/realtime").toString())
    }

    @Test fun preservesEncodedQueryWithinConfiguredOrigin() {
        val resolver = EndpointResolver(ServerOrigin.parse("http://music.example:18000"))
        val url = resolver.http("/api/library/search?q=ambient%20jazz")
        assertEquals("ambient jazz", url.queryParameter("q"))
        assertEquals("ws://music.example:18000/api/realtime?cursor=next", resolver.webSocket("/api/realtime?cursor=next"))
    }

    @Test fun rejectsForeignOrigin() {
        val resolver = EndpointResolver(ServerOrigin.parse("http://music.example:18000"))
        assertThrows(IllegalArgumentException::class.java) { resolver.resolveHttp("https://other.example/api/state") }
    }

    @Test fun rejectsTraversalAndNonApiPaths() {
        val resolver = EndpointResolver(ServerOrigin.parse("http://music.example:18000"))
        assertThrows(IllegalArgumentException::class.java) { resolver.http("/api/../admin") }
        assertThrows(IllegalArgumentException::class.java) { resolver.http("/health") }
    }
}
