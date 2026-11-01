package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MpdStatusRuntimeReadApiTest {
    private val response = """{"version":"0.23.5","connected":true}""".toByteArray()

    @Test
    fun getMpdStatusUsesCurrentOriginAndIsAnUncachedGet() = runBlocking {
        var origin = ServerOrigin.parse("http://first.example:18000")
        val requests = mutableListOf<Request>()
        val api = RuntimeReadApi(
            originProvider = { origin },
            client = OkHttpClient(),
            dispatcher = Dispatchers.IO,
            execute = { _, request ->
                requests += request
                HttpResult.Body(200, "application/json", response)
            },
        )

        val first = api.getMpdStatus() as ApiResult.Success
        origin = ServerOrigin.parse("https://second.example:9443")
        val second = api.getMpdStatus() as ApiResult.Success

        assertEquals("0.23.5", first.value.version)
        assertEquals("0.23.5", second.value.version)
        assertEquals(listOf("first.example", "second.example"), requests.map { it.url.host })
        assertTrue(requests.all { it.url.encodedPath == "/api/system/mpd" && it.method == "GET" })
    }

    @Test
    fun getMpdStatusUsesSharedUncachedReadLimiterAndDoesNotCacheValues() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(owner)
        val reads = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val api = RuntimeReadApi(
            originProvider = { ServerOrigin.parse("http://server.example") },
            client = OkHttpClient(),
            resources = resources,
            execute = { _, _ ->
                reads.incrementAndGet()
                val current = active.incrementAndGet()
                maxActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(60)
                } finally {
                    active.decrementAndGet()
                }
                HttpResult.Body(200, "application/json", response)
            },
        )
        try {
            val results = coroutineScope {
                (1..6).map { async(Dispatchers.IO) { api.getMpdStatus() } }.awaitAll()
            }
            assertTrue(results.all { it is ApiResult.Success })
            assertTrue("Uncached MPD reads must run concurrently through the shared limiter", maxActive.get() > 1)
            assertTrue("Uncached MPD reads must never exceed the shared three-request limit", maxActive.get() <= 3)
            assertEquals(6, reads.get())

            assertTrue(api.getMpdStatus() is ApiResult.Success)
            assertEquals("MPD status must not use the revision-bound resource cache", 7, reads.get())
        } finally {
            resources.close()
            owner.cancel()
        }
    }

    @Test
    fun getMpdStatusRejectsLatePreviousOriginResponse() = runBlocking {
        var origin = ServerOrigin.parse("http://old.example:18000")
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val seenRequest = AtomicReference<Request>()
        val api = RuntimeReadApi(
            originProvider = { origin },
            client = OkHttpClient(),
            dispatcher = Dispatchers.IO,
            execute = { _, request ->
                seenRequest.set(request)
                requestStarted.countDown()
                check(releaseResponse.await(5, TimeUnit.SECONDS))
                HttpResult.Body(200, "application/json", response)
            },
        )

        val pending = async(Dispatchers.Default) { api.getMpdStatus() }
        assertTrue("The old-origin request should start", requestStarted.await(5, TimeUnit.SECONDS))
        origin = ServerOrigin.parse("http://new.example:18000")
        releaseResponse.countDown()

        val result = pending.await()
        assertEquals("old.example", seenRequest.get().url.host)
        assertTrue("A previous-origin success must be rejected", result is ApiResult.ProtocolError)
    }
}
