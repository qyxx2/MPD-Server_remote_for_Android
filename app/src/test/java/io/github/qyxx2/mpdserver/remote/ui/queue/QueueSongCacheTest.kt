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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QueueSongCacheTest {
    @Test fun concurrentVisibleLoadsAreMergedAndOnlyLightweightPresentationStateIsRetained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<QueueSongResource>()
        val warmed = mutableListOf<Pair<String, ByteArray>>()
        val cache = QueueSongCache(
            scope = scope,
            artworkPrefetcher = { _, artworkId, bytes -> warmed += artworkId to bytes },
        ) { _, _ ->
            calls.incrementAndGet()
            started.complete(Unit)
            release.await()
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            cache.load("song-1")
            cache.load("song-1")
            withTimeout(2_000) { started.await() }
            release.complete(resource(song("song-1", withArtwork = true), image()))
            val ready = awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }
            assertEquals(1, calls.get())
            assertEquals("song-1", ready.title)
            assertEquals("art-1", ready.artworkId)
            assertEquals(QueueArtworkStatus.AVAILABLE, ready.artworkStatus)
            assertEquals("art-1", warmed.single().first)
            assertEquals(image().bytes.toList(), warmed.single().second.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test fun resourceIdentityChangeRejectsLateQueueAdapterCompletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<QueueSongResource>()
        val cache = QueueSongCache(scope) { key, id ->
            if (key.epoch == "epoch-old") {
                started.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
            } else resource(song(id, title = "New canonical title"))
        }
        try {
            cache.updateScope(identity(epoch = "epoch-old"), setOf("song-1"))
            cache.load("song-1")
            withTimeout(2_000) { started.await() }

            cache.updateScope(identity(epoch = "epoch-new"), setOf("song-1"))
            assertNull(cache.state.value["song-1"])
            cache.load("song-1")
            assertEquals(
                "New canonical title",
                awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }.title,
            )

            releaseOld.complete(resource(song("song-1", title = "Late old title")))
            delay(60)
            assertEquals("New canonical title", cache.state.value["song-1"]?.title)
        } finally {
            releaseOld.complete(resource(song("song-1")))
            scope.cancel()
        }
    }

    @Test fun invalidArtworkIsNotWarmedAndValidArtworkUsesArtworkIdAsTheCacheIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val warmedIds = mutableListOf<String>()
        val cache = QueueSongCache(
            scope = scope,
            artworkPrefetcher = { _, artworkId, _ -> warmedIds += artworkId },
        ) { _, id ->
            val response = when (id) {
                "bad" -> HttpResult.Body(200, "text/plain", image().bytes)
                else -> image()
            }
            resource(song(id, withArtwork = true), response)
        }
        try {
            cache.updateScope(identity(), setOf("good", "bad"))
            cache.load("good")
            cache.load("bad")
            val good = awaitEntry(cache, "good") { it.status == QueueSongLoadStatus.READY }
            val bad = awaitEntry(cache, "bad") { it.status == QueueSongLoadStatus.READY }
            assertEquals(QueueArtworkStatus.AVAILABLE, good.artworkStatus)
            assertEquals(QueueArtworkStatus.RETRYABLE_FAILURE, bad.artworkStatus)
            assertEquals(listOf("art-1"), warmedIds)
        } finally {
            scope.cancel()
        }
    }

    @Test fun retryableArtworkFailureCanRecoverWithoutReloadingSongMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val metadataCalls = AtomicInteger()
        val artworkReads = AtomicInteger()
        val warmed = mutableListOf<String>()
        val cache = QueueSongCache(
            scope = scope,
            artworkPrefetcher = { _, artworkId, _ -> warmed += artworkId },
            artworkRehydrator = { _, _, _ ->
                artworkReads.incrementAndGet()
                image()
            },
            artworkRetryIntervalMillis = 0,
            clockMillis = { 100L },
        ) { _, id ->
            metadataCalls.incrementAndGet()
            resource(
                song(id, withArtwork = true),
                HttpResult.HttpError(
                    500,
                    "application/json",
                    """{"error":{"code":"ARTWORK_READ_ERROR","message":"temporary"}}""".toByteArray(),
                ),
            )
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            assertEquals(
                QueueArtworkStatus.RETRYABLE_FAILURE,
                awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }.artworkStatus,
            )
            cache.preloadArtwork("song-1")
            withTimeout(2_000) {
                while (cache.state.value["song-1"]?.artworkStatus != QueueArtworkStatus.AVAILABLE) delay(5)
            }
            assertEquals(1, metadataCalls.get())
            assertEquals(1, artworkReads.get())
            assertEquals(listOf("art-1"), warmed)
        } finally {
            scope.cancel()
        }
    }

    @Test fun explicitArtworkNotFoundIsNotRetriedByQueueAdapter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val artworkReads = AtomicInteger()
        val cache = QueueSongCache(
            scope = scope,
            artworkRehydrator = { _, _, _ ->
                artworkReads.incrementAndGet()
                image()
            },
        ) { _, id ->
            resource(
                song(id, withArtwork = true),
                HttpResult.HttpError(
                    404,
                    "application/json",
                    """{"error":{"code":"ARTWORK_NOT_FOUND","message":"missing"}}""".toByteArray(),
                ),
            )
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            assertEquals(
                QueueArtworkStatus.MISSING,
                awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }.artworkStatus,
            )
            cache.preloadArtwork("song-1")
            delay(50)
            assertEquals(0, artworkReads.get())
        } finally {
            scope.cancel()
        }
    }

    @Test fun leavingViewportDoesNotCancelAnInflightResourceRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<QueueSongResource>()
        val cache = QueueSongCache(scope, maxEntries = 2) { _, id ->
            if (id == "song-1") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            } else resource(song(id))
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            withTimeout(2_000) { started.await() }
            cache.updateScope(identity(), emptySet())
            release.complete(resource(song("song-1")))
            assertEquals("song-1", awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }.title)
        } finally {
            scope.cancel()
        }
    }

    @Test fun queueAdapterSurvivesRouteReentryAndRetainsFourHundredQueueOccurrences() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val cache = QueueSongCache(scope, maxEntries = 512) { _, id ->
            calls.incrementAndGet()
            resource(song(id, title = "Cached title"))
        }
        try {
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            assertEquals("Cached title", awaitEntry(cache, "song-1") { it.status == QueueSongLoadStatus.READY }.title)

            cache.updateScope(identity(), emptySet())
            cache.updateScope(identity(), setOf("song-1"))
            cache.load("song-1")
            assertEquals(1, calls.get())
            assertEquals("Cached title", cache.state.value["song-1"]?.title)

            val songIds = (1..400).map { "song-$it" }.toSet()
            cache.updateScope(identity(), songIds)
            songIds.forEach(cache::load)
            withTimeout(10_000) {
                while (songIds.any { cache.state.value[it]?.status != QueueSongLoadStatus.READY }) delay(10)
            }
            assertEquals(400, cache.state.value.size)
            assertTrue(songIds.all { cache.state.value[it]?.title == "Cached title" })
        } finally {
            scope.cancel()
        }
    }

    private fun identity(epoch: String = "epoch-1", revision: Long = 7, generation: Long = 1) =
        QueueResourceIdentity(epoch, revision, generation, "http://127.0.0.1:18000")

    private fun resource(song: SongDto, artwork: HttpResult? = null) = QueueSongResource(song, artwork)

    private fun song(id: String, title: String = id, withArtwork: Boolean = false): SongDto {
        val fixture = QueueSongCacheTest::class.java.classLoader!!
            .getResource("fixtures/task4/song-response.json")!!.readText()
        return JsonConfiguration.json.decodeFromString<SongDto>(fixture).copy(
            songId = id,
            title = title,
            artwork = if (withArtwork) fixtureArtwork() else null,
        )
    }

    private fun fixtureArtwork() = JsonConfiguration.json.decodeFromString<SongDto>(
        QueueSongCacheTest::class.java.classLoader!!
            .getResource("fixtures/task4/song-response.json")!!.readText(),
    ).artwork

    private fun image() = HttpResult.Body(
        200,
        "image/png",
        byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
    )

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
