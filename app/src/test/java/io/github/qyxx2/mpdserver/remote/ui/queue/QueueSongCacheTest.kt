package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QueueSongCacheTest {
    @Test
    fun concurrentRequestsForTheSameVisibleSongAreMerged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val release = CompletableDeferred<QueueSongResource>()
        val cache = QueueSongCache(scope, maxConcurrentRequests = 2) { _, _ ->
            calls.incrementAndGet()
            release.await()
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            cache.load("song-1")
            cache.load("song-1")
            awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.LOADING }
            assertEquals(1, calls.get())

            release.complete(resource(song("song-1")))
            val ready = awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }
            assertEquals("song-1", ready.song?.songId)
            assertEquals(1, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentMetadataReadsNeverExceedConfiguredLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        val cache = QueueSongCache(scope, maxConcurrentRequests = 2) { _, id ->
            calls.incrementAndGet()
            val current = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, current) }
            try {
                delay(40)
                resource(song(id))
            } finally {
                active.decrementAndGet()
            }
        }
        try {
            val ids = (1..7).map { "song-$it" }.toSet()
            cache.updateScope(identity(), ids)
            ids.forEach(cache::load)
            withTimeout(5_000) {
                while (ids.any { cache.state.value[it]?.status != QueueSongLoadStatus.READY }) delay(10)
            }
            assertEquals(ids.size, calls.get())
            assertTrue("observed concurrent requests: ${maximum.get()}", maximum.get() <= 2)
            assertEquals(2, maximum.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changingResourceIdentityRejectsAnOlderLateResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<QueueSongResource>()
        val cache = QueueSongCache(scope, maxConcurrentRequests = 2) { key, id ->
            if (key.epoch == "epoch-old") {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
            } else {
                resource(song(id, title = "New canonical title"))
            }
        }
        try {
            cache.updateScope(identity(epoch = "epoch-old"), setOf("song-1"))
            cache.load("song-1")
            withTimeout(3_000) { oldStarted.await() }

            cache.updateScope(identity(epoch = "epoch-new"), setOf("song-1"))
            assertNull(cache.state.value["song-1"])
            cache.load("song-1")
            val current = awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }
            assertEquals("New canonical title", current.song?.title)

            releaseOld.complete(resource(song("song-1", title = "Late old title")))
            delay(60)
            assertEquals("New canonical title", cache.state.value["song-1"]?.song?.title)

            val changedGeneration = identity(epoch = "epoch-new", revision = 7, generation = 2)
            cache.updateScope(changedGeneration, setOf("song-1"))
            assertNull(cache.state.value["song-1"])
            cache.load("song-1")
            awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }

            val changedLibraryRevision = changedGeneration.copy(libraryRevision = 8)
            cache.updateScope(changedLibraryRevision, setOf("song-1"))
            assertNull(cache.state.value["song-1"])
        } finally {
            releaseOld.complete(resource(song("song-1")))
            scope.cancel()
        }
    }

    @Test
    fun artwork404BadMimeAndBadBytesAreNotPresentedAsAvailableImages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cases = listOf(
            "not-found" to HttpResult.HttpError(404, "application/json", """{}""".toByteArray()),
            "bad-mime" to HttpResult.Body(200, "text/plain", pngHeader()),
            "bad-bytes" to HttpResult.Body(200, "image/png", "not an image".toByteArray()),
        )
        val cache = QueueSongCache(scope) { _, id ->
            resource(song(id, withArtwork = true), cases.first { it.first == id }.second)
        }
        try {
            cache.updateScope(identity(), cases.map { it.first }.toSet())
            cases.forEach { cache.load(it.first) }
            cases.forEach { (id, _) ->
                val entry = awaitEntry(cache, id) { it.status == QueueSongLoadStatus.READY }
                assertNull(entry.artworkBytes)
                assertEquals(QueueArtworkStatus.UNAVAILABLE, entry.artworkStatus)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun validImageSignatureIsRetainedAndSongIdentityMismatchIsRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = QueueSongCache(scope) { _, id ->
            when (id) {
                "valid" -> resource(song(id, withArtwork = true), HttpResult.Body(200, "image/png", pngHeader()))
                else -> resource(song("different-song"))
            }
        }
        try {
            cache.updateScope(identity(), setOf("valid", "mismatch"))
            cache.load("valid")
            val valid = awaitEntry(cache, "valid") { it.status == QueueSongLoadStatus.READY }
            assertEquals(QueueArtworkStatus.AVAILABLE, valid.artworkStatus)
            assertNotNull(valid.artworkBytes)

            cache.load("mismatch")
            val mismatch = awaitEntry(cache, "mismatch") { it.status == QueueSongLoadStatus.FAILED }
            assertNull(mismatch.song)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun perImageAndTotalArtworkByteBudgetsAreEnforced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val prefetchedArtworkIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val cache = QueueSongCache(
            scope = scope,
            maxConcurrentRequests = 2,
            maxEntries = 4,
            maxArtworkBytesPerItem = 8,
            maxArtworkBytesTotal = 8,
            artworkPrefetcher = { _, id, bytes ->
                assertTrue(bytes.isNotEmpty())
                prefetchedArtworkIds += id
            },
        ) { _, id ->
            calls.incrementAndGet()
            resource(song(id, withArtwork = true), HttpResult.Body(200, "image/png", pngHeader()))
        }
        try {
            cache.updateScope(identity(), setOf("song-a", "song-b"))
            cache.load("song-a")
            cache.load("song-b")
            withTimeout(5_000) {
                while (calls.get() < 2 || cache.state.value.values.any { it.status == QueueSongLoadStatus.LOADING }) delay(10)
            }
            val retainedBytes = cache.state.value.values.sumOf { it.artworkBytes?.size?.toLong() ?: 0L }
            assertTrue("retained artwork bytes=$retainedBytes", retainedBytes <= 8)
            assertEquals("artwork budget must not evict small song metadata", 2, cache.state.value.size)
            assertTrue(cache.state.value.values.all { it.status == QueueSongLoadStatus.READY && it.song != null })
            assertTrue(
                "raw artwork callback must run before byte-budget pruning",
                prefetchedArtworkIds.containsAll(setOf("song-a", "song-b")),
            )
        } finally {
            scope.cancel()
        }

        val oversizedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oversizedCache = QueueSongCache(
            scope = oversizedScope,
            maxArtworkBytesPerItem = 7,
            maxArtworkBytesTotal = 8,
        ) { _, id ->
            resource(song(id, withArtwork = true), HttpResult.Body(200, "image/png", pngHeader()))
        }
        try {
            oversizedCache.updateScope(identity(), setOf("oversized"))
            oversizedCache.load("oversized")
            val entry = awaitEntry(oversizedCache, "oversized") { it.status == QueueSongLoadStatus.READY }
            assertNull(entry.artworkBytes)
            assertEquals(QueueArtworkStatus.UNAVAILABLE, entry.artworkStatus)
        } finally {
            oversizedScope.cancel()
        }
    }

    @Test
    fun offscreenLoadsFinishAndCompletedMetadataCacheRemainsBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<QueueSongResource>()
        val cache = QueueSongCache(scope, maxConcurrentRequests = 1, maxEntries = 2) { _, id ->
            if (id == "song-1") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            } else resource(song(id))
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            withTimeout(3_000) { started.await() }
            cache.updateScope(identity(), emptySet())
            release.complete(resource(song("song-1")))
            val retained = awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }
            assertEquals("song-1", retained.song?.songId)

            cache.updateScope(identity(), setOf("song-2", "song-3", "song-4"))
            listOf("song-2", "song-3", "song-4").forEach(cache::load)
            withTimeout(5_000) {
                while (cache.state.value.values.any { it.status != QueueSongLoadStatus.READY } ||
                    cache.state.value.size < 2 || activeCacheLoadCount(cache) > 0
                ) delay(10)
            }
            assertTrue("cache entries=${cache.state.value.size}", cache.state.value.size <= 2)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reenteringQueueReusesTheSameInMemoryMetadataWithoutAnotherRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val cache = QueueSongCache(scope, maxEntries = 512) { _, id ->
            calls.incrementAndGet()
            resource(song(id, title = "Cached title"))
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            val loaded = awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }
            assertEquals("Cached title", loaded.song?.title)

            // Leaving Queue does not destroy its app-scoped cache; re-entry must be a memory hit.
            cache.updateScope(identity(), emptySet())
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")

            assertEquals(1, calls.get())
            assertEquals("Cached title", cache.state.value["song-1"]?.song?.title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cacheRetainsMetadataForA400ItemQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songIds = (1..400).map { "song-$it" }.toSet()
        val cache = QueueSongCache(
            scope = scope,
            maxConcurrentRequests = 6,
            maxEntries = 512,
        ) { _, id -> resource(song(id)) }
        try {
            cache.updateScope(identity(), songIds)
            songIds.forEach(cache::load)
            withTimeout(10_000) {
                while (songIds.any { cache.state.value[it]?.status != QueueSongLoadStatus.READY }) delay(10)
            }
            assertEquals(400, cache.state.value.size)
            assertTrue(songIds.all { cache.state.value[it]?.song?.songId == it })
        } finally {
            scope.cancel()
        }
    }

    private fun activeCacheLoadCount(cache: QueueSongCache): Int =
        cache.state.value.values.count { it.status == QueueSongLoadStatus.LOADING }

    private fun identity(epoch: String = "epoch-1", revision: Long = 7, generation: Long = 1) =
        QueueResourceIdentity(
            epoch = epoch,
            libraryRevision = revision,
            connectionGeneration = generation,
            originAddress = "http://127.0.0.1:18000",
        )

    private fun resource(song: SongDto, artwork: HttpResult? = null) = QueueSongResource(song, artwork)

    private fun song(id: String, title: String = id, withArtwork: Boolean = false): SongDto {
        val fixture = JsonConfiguration.json.decodeFromString<SongDto>(
            QueueSongCacheTest::class.java.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText(),
        )
        return fixture.copy(songId = id, title = title, artwork = if (withArtwork) fixture.artwork else null)
    }

    private fun pngHeader() = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    private suspend fun awaitEntry(
        cache: QueueSongCache,
        id: String,
        predicate: (QueueSongCacheEntry) -> Boolean,
    ): QueueSongCacheEntry = withTimeout(5_000) {
        while (true) {
            val entry = cache.state.value[id]
            if (entry != null && predicate(entry)) return@withTimeout entry
            delay(5)
        }
        error("unreachable")
    }
}
