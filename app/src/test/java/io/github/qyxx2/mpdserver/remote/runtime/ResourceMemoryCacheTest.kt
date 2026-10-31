package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ResourceMemoryCacheTest {
    @Test fun identicalReadsAreSingleFlightAndCallerCancellationDoesNotCancelRuntimeWork() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val key = ResourceCacheKey.get<String>("/api/library/search/results?q=ambient")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ApiResult<String>>()
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), isFresh = true)
            val first = async { cache.readResource(key, ResourceDependency.LIBRARY) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
            } }
            withTimeout(2_000) { started.await() }
            val second = async { cache.readResource(key, ResourceDependency.LIBRARY) {
                calls.incrementAndGet()
                ApiResult.Success("duplicate", 200)
            } }
            first.cancel()
            release.complete(ApiResult.Success("shared", 200))
            assertEquals("shared", (second.await() as ApiResult.Success).value)
            assertEquals(1, calls.get())
            assertEquals("shared", (cache.observeResource<String>(key).value as ResourceEntryState.Ready).value)
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun historyFingerprintIgnoresObjectKeyOrderButPreservesArrayOrder() {
        val first = Json.parseToJsonElement("""{"played":[{"song_id":"a","at":1},{"song_id":"b","at":2}],"count":2}""")
        val reorderedKeys = Json.parseToJsonElement("""{"count":2,"played":[{"at":1,"song_id":"a"},{"at":2,"song_id":"b"}]}""")
        val reorderedItems = Json.parseToJsonElement("""{"count":2,"played":[{"song_id":"b","at":2},{"song_id":"a","at":1}]}""")
        assertEquals(historyResourceFingerprint(first), historyResourceFingerprint(reorderedKeys))
        assertFalse(historyResourceFingerprint(first) == historyResourceFingerprint(reorderedItems))
    }

    @Test fun pathQueryAndResponseTypeArePartOfTheCacheKey() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val title = ResourceCacheKey.get<String>("/api/library/songs?sort_by=title&sort_order=asc")
            val modified = ResourceCacheKey.get<String>("/api/library/songs?sort_by=modified_at&sort_order=desc")
            val typed = ResourceCacheKey.get<Int>("/api/library/songs?sort_by=title&sort_order=asc")
            assertEquals("title", (cache.readResource(title, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("title", 200)
            } as ApiResult.Success).value)
            assertEquals("modified", (cache.readResource(modified, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("modified", 200)
            } as ApiResult.Success).value)
            assertEquals(7, (cache.readResource(typed, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success(7, 200)
            } as ApiResult.Success).value)
            assertEquals("title", (cache.readResource(title, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("unexpected", 200)
            } as ApiResult.Success).value)
            assertEquals(3, calls.get())
            assertNotSame(title, modified)
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun activeObserverRemainsAttachedWhenItsResourceIsLruEvictedAndReloaded() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner, maxEntries = 1, maxWeightBytes = 1_024)
        val keyA = ResourceCacheKey.get<String>("/a")
        val keyB = ResourceCacheKey.get<String>("/b")
        val observed = cache.observeResource<String>(keyA)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { observed.collect {} }
        try {
            cache.updateIdentity(session(), watermarks(), true)
            cache.readResource(keyA, ResourceDependency.LIBRARY) {
                ApiResult.Success("a-v1", 200)
            }
            assertEquals("a-v1", (observed.value as ResourceEntryState.Ready).value)

            cache.readResource(keyB, ResourceDependency.LIBRARY) {
                ApiResult.Success("b-v1", 200)
            }
            assertEquals(ResourceEntryState.Empty, observed.value)

            cache.readResource(keyA, ResourceDependency.LIBRARY) {
                ApiResult.Success("a-v2", 200)
            }
            assertEquals("a-v2", (observed.value as ResourceEntryState.Ready).value)
        } finally {
            collector.cancel()
            cache.close()
            owner.cancel()
        }
    }

    @Test fun cachedArtworkAndUncachedReadsShareThreeGlobalSlots() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner, maxConcurrentRequests = 3)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val tasks = (1..12).map { index -> async {
                when (index % 3) {
                    0 -> cache.readUncached {
                        calls.incrementAndGet()
                        val now = active.incrementAndGet()
                        maximum.updateAndGet { maxOf(it, now) }
                        try {
                            delay(35)
                            "uncached-$index"
                        } finally {
                            active.decrementAndGet()
                        }
                    } == "uncached-$index"
                    1 -> {
                        val key = ResourceCacheKey.get<String>("/resource/$index")
                        cache.readResource(key, ResourceDependency.SESSION) {
                            calls.incrementAndGet()
                            val now = active.incrementAndGet()
                            maximum.updateAndGet { maxOf(it, now) }
                            try {
                                delay(35)
                                ApiResult.Success("value-$index", 200)
                            } finally {
                                active.decrementAndGet()
                            }
                        } is ApiResult.Success
                    }
                    else -> {
                        val response = cache.readArtwork(ArtworkCacheKey("art-$index", "song-$index")) {
                            calls.incrementAndGet()
                            val now = active.incrementAndGet()
                            maximum.updateAndGet { maxOf(it, now) }
                            try {
                                delay(35)
                                HttpResult.Body(200, "image/png", png())
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                        response is HttpResult.Body
                    }
                }
            } }
            tasks.forEach { assertTrue(it.await()) }
            assertEquals(12, calls.get())
            assertEquals(3, maximum.get())
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun sessionChangeRejectsLateOldResponseAndClearsAllPriorEntries() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val key = ResourceCacheKey.get<String>("/resource")
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val oldRead = async { cache.readResource(key, ResourceDependency.LIBRARY) {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
                ApiResult.Success("old", 200)
            } }
            withTimeout(2_000) { oldStarted.await() }
            cache.updateIdentity(session(generation = 2, epoch = "epoch-2"), watermarks(), true)
            assertEquals(ResourceEntryState.Empty, cache.observeResource<String>(key).value)
            assertEquals("new", (cache.readResource(key, ResourceDependency.LIBRARY) {
                ApiResult.Success("new", 200)
            } as ApiResult.Success).value)
            releaseOld.complete(Unit)
            val rejected = runCatching { withTimeout(2_000) { oldRead.await() } }
            assertTrue(
                rejected.exceptionOrNull() is kotlinx.coroutines.CancellationException ||
                    rejected.getOrNull() is ApiResult.ProtocolError,
            )
            assertEquals("new", (cache.observeResource<String>(key).value as ResourceEntryState.Ready).value)
        } finally {
            releaseOld.complete(Unit)
            cache.close()
            owner.cancel()
        }
    }

    @Test fun revisionsInvalidateOnlyTheirDeclaredDependencies() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val sessionKey = ResourceCacheKey.get<String>("/session")
        val libraryKey = ResourceCacheKey.get<String>("/library")
        val playlistKey = ResourceCacheKey.get<String>("/playlists")
        val historyKey = ResourceCacheKey.get<String>("/history")
        try {
            cache.updateIdentity(session(), watermarks(), true)
            suspend fun seed(key: ResourceCacheKey<String>, dependency: ResourceDependency) {
                cache.readResource(key, dependency) { ApiResult.Success(key.canonicalPathAndQuery, 200) }
            }
            seed(sessionKey, ResourceDependency.SESSION)
            seed(libraryKey, ResourceDependency.LIBRARY)
            seed(playlistKey, ResourceDependency.PLAYLIST)
            seed(historyKey, ResourceDependency.HISTORY)

            cache.updateIdentity(session(), watermarks(library = 2), true)
            assertTrue(cache.observeResource<String>(sessionKey).value is ResourceEntryState.Ready)
            assertTrue(cache.observeResource<String>(libraryKey).value is ResourceEntryState.Stale)
            assertTrue(cache.observeResource<String>(playlistKey).value is ResourceEntryState.Stale)
            assertTrue(cache.observeResource<String>(historyKey).value is ResourceEntryState.Ready)

            cache.readResource(libraryKey, ResourceDependency.LIBRARY) { ApiResult.Success("library-v2", 200) }
            cache.readResource(playlistKey, ResourceDependency.PLAYLIST) { ApiResult.Success("playlist-v2", 200) }
            cache.updateIdentity(session(), watermarks(library = 2, playlist = 2), true)
            assertTrue(cache.observeResource<String>(libraryKey).value is ResourceEntryState.Ready)
            assertTrue(cache.observeResource<String>(playlistKey).value is ResourceEntryState.Stale)
            assertTrue(cache.observeResource<String>(historyKey).value is ResourceEntryState.Ready)

            cache.updateIdentity(session(), watermarks(library = 2, playlist = 2, history = "h2"), true)
            assertTrue(cache.observeResource<String>(historyKey).value is ResourceEntryState.Stale)
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun disconnectMarksLastKnownResourcesStaleAndReconnectDoesNotPromoteThem() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val key = ResourceCacheKey.get<String>("/library")
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            cache.readResource(key, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("last-known", 200)
            }
            cache.updateIdentity(session(), watermarks(), false)
            assertTrue(cache.observeResource<String>(key).value is ResourceEntryState.Stale)
            assertEquals("last-known", (cache.readResource(key, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("must-not-read-offline", 200)
            } as ApiResult.Success).value)
            cache.updateIdentity(session(), watermarks(), true)
            assertTrue(cache.observeResource<String>(key).value is ResourceEntryState.Stale)
            assertEquals("fresh", (cache.readResource(key, ResourceDependency.LIBRARY) {
                calls.incrementAndGet(); ApiResult.Success("fresh", 200)
            } as ApiResult.Success).value)
            assertEquals(2, calls.get())
            assertTrue(cache.observeResource<String>(key).value is ResourceEntryState.Ready)
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun disconnectDoesNotLeaveAnInFlightResourceObservedAsLoading() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val key = ResourceCacheKey.get<String>("/in-flight")
        val started = CompletableDeferred<Unit>()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val pending = async {
                cache.readResource(key, ResourceDependency.LIBRARY) {
                    started.complete(Unit)
                    CompletableDeferred<ApiResult<String>>().await()
                }
            }
            withTimeout(2_000) { started.await() }
            assertEquals(ResourceEntryState.Loading, cache.observeResource<String>(key).value)

            cache.updateIdentity(session(), watermarks(), isFresh = false)
            assertEquals(ResourceEntryState.Empty, cache.observeResource<String>(key).value)
            runCatching { withTimeout(2_000) { pending.await() } }
            Unit
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun historyResourcesExpireAtSixtySecondsAndRefreshOnlyWhenRead() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = AtomicLong(1_000L)
        val cache = ResourceMemoryCache(owner, historyTtlMillis = 60_000, clockMillis = now::get)
        val key = ResourceCacheKey.get<String>("/api/history")
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            cache.readResource(key, ResourceDependency.HISTORY) {
                calls.incrementAndGet(); ApiResult.Success("history-v1", 200)
            }
            now.addAndGet(60_001)
            assertEquals("history-v2", (cache.readResource(key, ResourceDependency.HISTORY) {
                calls.incrementAndGet(); ApiResult.Success("history-v2", 200)
            } as ApiResult.Success).value)
            assertEquals(2, calls.get())
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun songListsInternOneCanonicalSongAndReadSongChecksThatIndexFirst() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val first = song("song-1", "Canonical")
            val second = song("song-1", "Duplicate DTO")
            val interned = cache.internSongs(listOf(first, second))
            assertSame(interned[0], interned[1])
            assertSame(first, cache.peekSong("song-1"))
            val reads = AtomicInteger()
            val indexed = cache.readSong("song-1") {
                reads.incrementAndGet()
                ApiResult.Success(second, 200)
            }
            assertEquals(0, reads.get())
            assertSame(first, (indexed as ApiResult.Success).value)

            val detailKey = ResourceCacheKey.get<SongDto>(LibraryApiPaths.song("song-3"))
            val detailResponse = song("song-3", "Detail response")
            cache.readResource(detailKey, ResourceDependency.LIBRARY) {
                ApiResult.Success(detailResponse, 200)
            }
            val listResponse = song("song-3", "Canonical list response")
            val listCanonical = cache.internSongs(listOf(listResponse)).single()
            assertSame(listResponse, listCanonical)
            val sharedDetail = cache.readResource(detailKey, ResourceDependency.LIBRARY) {
                ApiResult.ProtocolError("detail response must already be interned")
            }
            assertSame(listCanonical, (sharedDetail as ApiResult.Success).value)
            val readThroughSongApi = cache.readSong("song-3") {
                ApiResult.ProtocolError("canonical entity index should satisfy Song GET")
            }
            assertSame(listCanonical, (readThroughSongApi as ApiResult.Success).value)

            val mismatch = cache.readSong("song-2") {
                ApiResult.Success(song("wrong-id"), 200)
            }
            assertTrue(mismatch is ApiResult.ProtocolError)
            assertNull(cache.peekSong("song-2"))
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun songEntityRemainsStaleOfflineAndRefreshesBeforeReconnectReuse() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        val identity = session()
        val revisions = watermarks()
        val oldSong = song("song-stale", "Last known title")
        val refreshedSong = song("song-stale", "Server refreshed title")
        val reads = AtomicInteger()
        try {
            cache.updateIdentity(identity, revisions, true)
            assertSame(oldSong, cache.internSongs(listOf(oldSong)).single())

            cache.updateIdentity(identity, revisions, isFresh = false)
            val offline = cache.readSong("song-stale") {
                reads.incrementAndGet()
                ApiResult.Success(refreshedSong, 200)
            }
            assertSame(oldSong, (offline as ApiResult.Success).value)
            assertEquals(0, reads.get())

            cache.updateIdentity(identity, revisions, isFresh = true)
            val reconnected = cache.readSong("song-stale") {
                reads.incrementAndGet()
                ApiResult.Success(refreshedSong, 200)
            }
            assertSame(refreshedSong, (reconnected as ApiResult.Success).value)
            assertSame(refreshedSong, cache.peekSong("song-stale"))
            assertEquals(1, reads.get())

            val subsequent = cache.readSong("song-stale") {
                reads.incrementAndGet()
                ApiResult.ProtocolError("fresh entity should be reused")
            }
            assertSame(refreshedSong, (subsequent as ApiResult.Success).value)
            assertEquals(1, reads.get())
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun oldSongListResponseCannotSeedEntitiesAfterLibraryIdentityChanges() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = cache(owner)
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val captured = cache.captureLibraryIdentity()!!
            cache.updateIdentity(
                session(generation = 2, epoch = "epoch-2"),
                watermarks(library = 2),
                true,
            )
            val oldResponse = song("song-old", "Obsolete server response")
            val returned = cache.internSongs(listOf(oldResponse), captured)
            assertSame(oldResponse, returned.single())
            assertNull(cache.peekSong("song-old"))
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun artworkSharesByArtworkIdValidatesBytesAndCachesOnlyExplicitMissing() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(
            owner,
            maxArtworkBytesPerImage = 64,
            maxArtworkBytesTotal = 64,
        )
        val valid = HttpResult.Body(200, "image/png", png())
        try {
            cache.updateIdentity(session(), watermarks(), true)
            val calls = AtomicInteger()
            val key = ArtworkCacheKey("art-shared", "song-a")
            assertTrue(cache.readArtwork(key) { calls.incrementAndGet(); valid } is HttpResult.Body)
            assertTrue(cache.readArtwork(key.copy(songId = "song-b")) {
                calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Protocol("should be cached"))
            } is HttpResult.Body)
            assertEquals(1, calls.get())

            val missing = HttpResult.HttpError(
                404,
                "application/json",
                """{"error":{"code":"ARTWORK_NOT_FOUND","message":"not found"}}""".toByteArray(),
            )
            val missingKey = ArtworkCacheKey("missing-art", "missing-song")
            assertEquals(missing, cache.readArtwork(missingKey) { missing })
            assertEquals(missing, cache.readArtwork(missingKey) {
                calls.incrementAndGet(); valid
            })
            assertEquals(1, calls.get())

            val retryable = listOf(
                HttpResult.HttpError(404, "application/json", """{"error":{"code":"SONG_NOT_FOUND","message":"x"}}""".toByteArray()),
                HttpResult.HttpError(500, "application/json", """{"error":{"code":"ARTWORK_READ_ERROR","message":"x"}}""".toByteArray()),
                HttpResult.Failed(HttpFailure.Protocol("transport/protocol failure")),
            )
            retryable.forEachIndexed { index, response ->
                val retryKey = ArtworkCacheKey("retry-$index", "song-$index")
                var reads = 0
                cache.readArtwork(retryKey) { reads++; response }
                cache.readArtwork(retryKey) { reads++; valid }
                assertEquals("non-ARTWORK_NOT_FOUND failures must not be cached", 2, reads)
            }
            val invalidKey = ArtworkCacheKey("invalid", "invalid-song")
            val invalid = HttpResult.Body(200, "text/plain", png())
            assertTrue(cache.readArtwork(invalidKey) { invalid } is HttpResult.Failed)
            assertEquals(2, run {
                var reads = 0
                cache.readArtwork(invalidKey) { reads++; invalid }
                cache.readArtwork(invalidKey) { reads++; valid }
                reads
            })
            assertTrue(cache.artworkRetainedBytesForTest <= 64L)
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun artworkRequiresSupportedMimeAndPngJpegGifOrWebpSignature() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner)
        val supported = listOf(
            "image/png" to png(),
            "image/jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00),
            "image/gif" to "GIF89a".toByteArray() + byteArrayOf(0x00, 0x00),
            "image/webp" to "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray(),
        )
        try {
            cache.updateIdentity(session(), watermarks(), true)
            supported.forEachIndexed { index, (mime, bytes) ->
                val result = cache.readArtwork(ArtworkCacheKey("art-$index", "song-$index")) {
                    HttpResult.Body(200, mime, bytes)
                }
                assertTrue("$mime signature should be accepted", result is HttpResult.Body)
            }
            val malformed = HttpResult.Body(200, "image/png", "not a PNG".toByteArray())
            assertTrue(
                cache.readArtwork(ArtworkCacheKey("bad-signature", "bad-song")) { malformed } is HttpResult.Failed,
            )
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun artworkStaysLastKnownOfflineAndIsRevalidatedAfterReconnect() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner)
        val key = ArtworkCacheKey("art-stale", "song-stale")
        val original = HttpResult.Body(200, "image/png", png())
        val refreshedBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
        val refreshed = HttpResult.Body(200, "image/jpeg", refreshedBytes)
        val loads = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            assertTrue(cache.readArtwork(key) {
                loads.incrementAndGet()
                original
            } is HttpResult.Body)

            cache.updateIdentity(session(), watermarks(), false)
            val offline = cache.readArtwork(key) {
                loads.incrementAndGet()
                refreshed
            } as HttpResult.Body
            assertEquals(png().toList(), offline.bytes.toList())
            assertEquals(1, loads.get())

            cache.updateIdentity(session(), watermarks(), true)
            val afterReconnect = cache.readArtwork(key) {
                loads.incrementAndGet()
                refreshed
            } as HttpResult.Body
            assertEquals(refreshedBytes.toList(), afterReconnect.bytes.toList())
            assertEquals(2, loads.get())
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun songEntityWeightIncludesLyricsAndAllTagMetadata() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner, maxEntries = 8, maxWeightBytes = 2_048)
        try {
            cache.updateIdentity(session(), watermarks(), true)
            cache.internSongs(listOf(song("large-lyrics").copy(lyrics = "L".repeat(8_000))))
            assertTrue(cache.metadataTotalWeightBytesForTest <= 2_048L)
            assertNull("large lyric text must be included in the shared metadata weight", cache.peekSong("large-lyrics"))
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    @Test fun metadataAndArtworkBudgetsAreBoundedAndFailuresAreNotCached() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = ResourceMemoryCache(owner, maxEntries = 2, maxWeightBytes = 1_024)
        val calls = AtomicInteger()
        try {
            cache.updateIdentity(session(), watermarks(), true)
            cache.internSongs(listOf(song("budget-song")))
            val keys = listOf("/a", "/b", "/c").map { ResourceCacheKey.get<String>(it) }
            keys.forEach { key ->
                cache.readResource(key, ResourceDependency.SESSION) {
                    calls.incrementAndGet(); ApiResult.Success("x".repeat(40), 200)
                }
            }
            assertTrue(cache.metadataResourceCountForTest <= 2)
            assertTrue(cache.metadataTotalWeightBytesForTest <= 1_024L)
            assertNull("song entities share the metadata LRU budget", cache.peekSong("budget-song"))
            val errorKey = ResourceCacheKey.get<String>("/failure")
            repeat(2) {
                assertTrue(cache.readResource(errorKey, ResourceDependency.SESSION) {
                    calls.incrementAndGet(); ApiResult.TransportError(
                        HttpFailure.Transport(java.io.IOException("offline")),
                    )
                } is ApiResult.TransportError)
            }
            assertTrue(cache.metadataResourceCountForTest <= 2)
            assertEquals(5, calls.get())
        } finally {
            cache.close()
            owner.cancel()
        }
    }

    private fun cache(owner: CoroutineScope) = ResourceMemoryCache(owner)
    private fun session(generation: Long = 1, epoch: String = "epoch-1") =
        ResourceSessionIdentity("http://127.0.0.1:18000", generation, epoch)
    private fun watermarks(library: Long = 1, playlist: Long = 1, history: String = "h1") =
        ResourceWatermarks(library, playlist, history)

    private fun song(id: String, title: String = id): SongDto {
        val fixture = ResourceMemoryCacheTest::class.java.classLoader!!
            .getResource("fixtures/task4/song-response.json")!!.readText()
        return JsonConfiguration.json.decodeFromString<SongDto>(fixture).copy(songId = id, title = title)
    }

    private fun png() = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
}
