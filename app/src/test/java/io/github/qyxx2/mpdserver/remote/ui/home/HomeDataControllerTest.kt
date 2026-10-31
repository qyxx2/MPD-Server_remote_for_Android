package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryEventDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.serialization.json.decodeFromJsonElement
import io.github.qyxx2.mpdserver.remote.runtime.ResourceDependency
import io.github.qyxx2.mpdserver.remote.runtime.ResourceMemoryCache
import io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity
import io.github.qyxx2.mpdserver.remote.runtime.ResourceWatermarks
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotSource
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStore
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.historyResourceFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HomeDataControllerTest {
    private val json get() = JsonConfiguration.json
    private val recentKey get() = io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheKey.get<SongListDto>(
        LibraryApiPaths.songs("modified_at", "desc"),
    )
    private val historyKey get() = io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheKey.get<HistoryListDto>(
        LibraryApiPaths.history(),
    )

    private fun song(id: String): SongDto {
        val fixture = javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText()
        return json.decodeFromString<SongDto>(fixture).copy(songId = id, fileUri = "music/$id.flac")
    }

    private fun event(id: Long, songId: String) = HistoryEventDto(
        historyId = id,
        songId = songId,
        startedAt = "2026-10-31T10:00:00Z",
        endedAt = null,
        reason = "completed",
        sessionId = "session-1",
    )

    private fun snapshotDto(
        sequence: Long = 12,
        libraryRevision: Long = 3,
        queueRevision: Long = 7,
        historyJson: String? = null,
        queueItems: String = """[
            {"queue_item_id":"auto-1","song_id":"song-a","position":1,"source":"AUTOPLAY","playback_context_id":"context-a"}
        ]""",
    ): StateSnapshotDto {
        val fixture = javaClass.classLoader!!
            .getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val root = json.parseToJsonElement(fixture).jsonObject.toMutableMap()
        root["sequence"] = JsonPrimitive(sequence)
        root["revisions"] = buildJsonObject {
            put("library", JsonPrimitive(libraryRevision))
            put("playlist", JsonPrimitive(5))
        }
        root["queue"] = json.parseToJsonElement("""{"revision":$queueRevision,"items":$queueItems}""")
        historyJson?.let { root["history"] = json.parseToJsonElement(it) }
        return json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root))
    }

    private fun runtimeState(dto: StateSnapshotDto = snapshotDto()): Triple<SnapshotStore, ResourceMemoryCache, CoroutineScope> {
        val snapshots = SnapshotStore()
        snapshots.onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
        assertTrue(snapshots.accept(dto, 1, SnapshotSource.REALTIME_INITIAL))
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(cacheScope)
        resources.updateIdentity(
            ResourceSessionIdentity("http://server.example", 1, dto.epoch),
            ResourceWatermarks(dto.revisions.library, dto.revisions.playlist, historyResourceFingerprint(dto.history)),
            isFresh = true,
        )
        return Triple(snapshots, resources, cacheScope)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }

    @Test
    fun homeStartsRecentAndPersistentHistoryReadsConcurrentlyThroughSharedCache() = runBlocking {
        val dto = snapshotDto()
        val (snapshots, resources, cacheScope) = runtimeState(dto)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    if (starts.incrementAndGet() == 2) bothStarted.complete(Unit)
                    release.await()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    if (starts.incrementAndGet() == 2) bothStarted.complete(Unit)
                    release.await()
                    ApiResult.Success(HistoryListDto(listOf(event(8, "song-a")), 1), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            withTimeout(2_000) { bothStarted.await() }
            release.complete(Unit)
            awaitCondition {
                controller.state.value.recentlyUpdated is HomeSectionState.Ready<*> &&
                    controller.state.value.playbackHistory is HomeSectionState.Ready<*>
            }
            assertEquals(1, songLoads.get())
            assertEquals(1, historyLoads.get())
            val history = (controller.state.value.playbackHistory as HomeSectionState.Ready<HomePlaybackHistory>).value
            assertEquals(listOf("song-a"), history.songIds)
            assertEquals("song-a", history.cards.single().songId)
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun homeWaitsForMatchingSharedCacheWatermarksBeforeReadingNewSnapshotResources() = runBlocking {
        val dto = snapshotDto()
        val snapshots = SnapshotStore()
        snapshots.onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
        assertTrue(snapshots.accept(dto, 1, SnapshotSource.REALTIME_INITIAL))
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(cacheScope)
        val oldSession = ResourceSessionIdentity("http://first.example", 1, dto.epoch)
        val currentSession = ResourceSessionIdentity("http://second.example", 1, dto.epoch)
        resources.updateIdentity(
            oldSession,
            ResourceWatermarks(dto.revisions.library, dto.revisions.playlist, "old-history-fingerprint"),
            isFresh = true,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    ApiResult.Success(HistoryListDto(listOf(event(12, "song-a")), 1), 200)
                }
            },
            scope = controllerScope,
            currentOriginAddress = { "http://second.example" },
        )
        try {
            controller.activate()
            delay(40)
            assertEquals("must not read using a previous server origin or History watermark", 0, historyLoads.get())
            assertEquals("must not start a partial Home load under a mismatched identity", 0, songLoads.get())

            resources.updateIdentity(
                currentSession,
                ResourceWatermarks(
                    dto.revisions.library,
                    dto.revisions.playlist,
                    historyResourceFingerprint(dto.history),
                ),
                isFresh = true,
            )
            awaitCondition { songLoads.get() == 1 && historyLoads.get() == 1 }
            awaitCondition { controller.state.value.playbackHistory is HomeSectionState.Ready<*> }
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun sameRevisionReconnectRevalidatesLastKnownHomeResources() = runBlocking {
        val initial = snapshotDto()
        val (snapshots, resources, cacheScope) = runtimeState(initial)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    ApiResult.Success(HistoryListDto(listOf(event(13, "song-a")), 1), 200)
                }
            },
            scope = controllerScope,
        )
        val session = ResourceSessionIdentity("http://server.example", 1, initial.epoch)
        val watermarks = ResourceWatermarks(
            initial.revisions.library,
            initial.revisions.playlist,
            historyResourceFingerprint(initial.history),
        )
        try {
            controller.activate()
            awaitCondition { songLoads.get() == 1 && historyLoads.get() == 1 }
            snapshots.markReadFailure("temporary offline")
            resources.updateIdentity(session, watermarks, isFresh = false)
            awaitCondition { controller.state.value.autoplay is HomeSectionState.Stale<*> }

            resources.updateIdentity(session, watermarks, isFresh = true)
            val reconnected = snapshotDto(sequence = initial.sequence + 1)
            assertTrue(snapshots.accept(reconnected, 1, SnapshotSource.HTTP_READ))
            awaitCondition { songLoads.get() == 2 && historyLoads.get() == 2 }
            awaitCondition { controller.state.value.recentlyUpdated is HomeSectionState.Ready<*> }
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun historyRendersOrphansWithoutLibraryAndRehydratesAfterLibraryArrives() = runBlocking {
        val (snapshots, resources, cacheScope) = runtimeState()
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songAttempts = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    if (songAttempts.incrementAndGet() == 1) {
                        ApiResult.TransportError(HttpFailure.Transport(java.io.IOException("library offline")))
                    } else {
                        ApiResult.Success(SongListDto(listOf(song("late-song")), 1), 200)
                    }
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    ApiResult.Success(HistoryListDto(listOf(event(42, "late-song")), 1), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition {
                controller.state.value.recentlyUpdated is HomeSectionState.Failed &&
                    controller.state.value.playbackHistory is HomeSectionState.Ready<*>
            }
            val orphan = (controller.state.value.playbackHistory as HomeSectionState.Ready<HomePlaybackHistory>).value.cards.single()
            assertTrue(orphan.song == null)
            assertFalse(orphan.isPlayable)

            controller.refreshRecentUpdates()
            awaitCondition {
                val history = controller.state.value.playbackHistory as? HomeSectionState.Ready<HomePlaybackHistory>
                history?.value?.cards?.singleOrNull()?.song != null
            }
            assertEquals(2, songAttempts.get())
            assertEquals("late-song", (controller.state.value.playbackHistory as HomeSectionState.Ready<HomePlaybackHistory>).value.cards.single().songId)
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun autoplayRemainsSnapshotAuthoritativeWhenLibrarySongMetadataFails() = runBlocking {
        val (snapshots, resources, cacheScope) = runtimeState(snapshotDto())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    if (songLoads.incrementAndGet() == 1) {
                        ApiResult.TransportError(HttpFailure.Transport(java.io.IOException("library offline")))
                    } else {
                        ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                    }
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    ApiResult.Success(HistoryListDto(emptyList(), 0), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition {
                controller.state.value.recentlyUpdated is HomeSectionState.Failed &&
                    controller.state.value.autoplay is HomeSectionState.Ready<*>
            }
            val autoplay = (controller.state.value.autoplay as HomeSectionState.Ready<HomeAutoplay>).value
            assertEquals(listOf("auto-1"), autoplay.cards.map { it.queueItemId })
            assertEquals("song-a", autoplay.cards.single().songId)
            assertTrue(autoplay.cards.single().song == null)
            assertTrue(autoplay.cards.single().artwork == null)
            assertFalse(autoplay.cards.single().isPlayable)
            assertEquals(1, songLoads.get())

            controller.refreshRecentUpdates()
            awaitCondition {
                val updated = controller.state.value.autoplay as? HomeSectionState.Ready<HomeAutoplay>
                updated?.value?.cards?.singleOrNull()?.song?.songId == "song-a"
            }
            val rehydrated = (controller.state.value.autoplay as HomeSectionState.Ready<HomeAutoplay>).value.cards.single()
            assertEquals(autoplay.cards.single().key, rehydrated.key)
            assertEquals(autoplay.cards.single().queueItemId, rehydrated.queueItemId)
            assertEquals("song-a", rehydrated.song?.songId)
            assertEquals(2, songLoads.get())
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun queueSnapshotChangesReprojectAutoplayWithoutRepeatingResourceGets() = runBlocking {
        val initial = snapshotDto()
        val (snapshots, resources, cacheScope) = runtimeState(initial)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    ApiResult.Success(HistoryListDto(emptyList(), 0), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition { controller.state.value.autoplay is HomeSectionState.Ready<*> }
            assertEquals(listOf("auto-1"), (controller.state.value.autoplay as HomeSectionState.Ready<HomeAutoplay>).value.cards.map { it.queueItemId })

            val changed = snapshotDto(
                sequence = initial.sequence + 1,
                queueRevision = 8,
                queueItems = """[
                    {"queue_item_id":"auto-2","song_id":"song-a","position":2,"source":"AUTOPLAY","playback_context_id":"context-a"},
                    {"queue_item_id":"auto-1","song_id":"song-a","position":1,"source":"AUTOPLAY","playback_context_id":"context-a"}
                ]""",
            )
            assertTrue(snapshots.accept(changed, 1, SnapshotSource.HTTP_READ))
            awaitCondition {
                controller.state.value.autoplay is HomeSectionState.Ready<*> &&
                    (controller.state.value.autoplay as HomeSectionState.Ready<HomeAutoplay>).value.cards.size == 2
            }
            assertEquals(listOf("auto-1", "auto-2"), (controller.state.value.autoplay as HomeSectionState.Ready<HomeAutoplay>).value.cards.map { it.queueItemId })
            assertEquals(1, songLoads.get())
            assertEquals(1, historyLoads.get())
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun persistentHistoryRefreshesOnceAtItsTtlAndTimerStopsWhenHomeIsInactive() = runBlocking {
        val dto = snapshotDto()
        val snapshots = SnapshotStore()
        snapshots.onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
        assertTrue(snapshots.accept(dto, 1, SnapshotSource.REALTIME_INITIAL))
        val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val resources = ResourceMemoryCache(cacheScope, historyTtlMillis = 100L)
        resources.updateIdentity(
            ResourceSessionIdentity("http://server.example", 1, dto.epoch),
            ResourceWatermarks(dto.revisions.library, dto.revisions.playlist, historyResourceFingerprint(dto.history)),
            isFresh = true,
        )
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    ApiResult.Success(SongListDto(emptyList(), 0), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    ApiResult.Success(HistoryListDto(emptyList(), 0), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition { historyLoads.get() == 1 }
            awaitCondition { historyLoads.get() == 2 }
            awaitCondition { (resources.remainingTtlMillis(historyKey) ?: 0L) > 0L }
            controller.deactivate()
            delay(180)
            assertEquals("inactive Home must not run its History expiry refresh", 2, historyLoads.get())
            controller.activate()
            awaitCondition { historyLoads.get() == 3 }
            awaitCondition { (resources.remainingTtlMillis(historyKey) ?: 0L) > 0L }
            controller.deactivate()
            delay(160)
            assertEquals("re-entry checks the expired History TTL once, then stops while inactive", 3, historyLoads.get())
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun persistentHistoryIsNotPromotedFromSnapshotActiveEventAndFingerprintRefreshesOnlyHistory() = runBlocking {
        val initial = snapshotDto()
        val (snapshots, resources, cacheScope) = runtimeState(initial)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    if (historyLoads.incrementAndGet() == 1) {
                        ApiResult.Success(HistoryListDto(emptyList(), 0), 200)
                    } else {
                        ApiResult.Success(HistoryListDto(listOf(event(77, "song-a")), 1), 200)
                    }
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition { controller.state.value.playbackHistory is HomeSectionState.Empty }
            assertEquals("snapshot.history.active_event is not the persistent Home History source", 1, historyLoads.get())

            val changed = snapshotDto(
                sequence = initial.sequence + 1,
                historyJson = """{"has_entries":true,"active_event":{"history_id":77,"song_id":"song-a","started_at":"2026-10-31T11:00:00Z","ended_at":null,"reason":"completed","session_id":"session-2"},"session_id":"session-2"}""",
            )
            resources.updateIdentity(
                ResourceSessionIdentity("http://server.example", 1, changed.epoch),
                ResourceWatermarks(changed.revisions.library, changed.revisions.playlist, historyResourceFingerprint(changed.history)),
                isFresh = true,
            )
            assertTrue(snapshots.accept(changed, 1, SnapshotSource.HTTP_READ))
            awaitCondition { historyLoads.get() == 2 && controller.state.value.playbackHistory is HomeSectionState.Ready<*> }
            assertEquals(1, songLoads.get())
            val history = (controller.state.value.playbackHistory as HomeSectionState.Ready<HomePlaybackHistory>).value
            assertEquals(listOf("song-a"), history.songIds)
            assertEquals(77L, history.cards.single().historyId)
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }

    @Test
    fun libraryRevisionRefreshesSongIndexWhileHistoryRemainsCached() = runBlocking {
        val initial = snapshotDto()
        val (snapshots, resources, cacheScope) = runtimeState(initial)
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val songLoads = AtomicInteger()
        val historyLoads = AtomicInteger()
        val controller = HomeDataController(
            resources = resources,
            snapshots = snapshots,
            readRecentUpdates = {
                resources.readResource(recentKey, ResourceDependency.LIBRARY) {
                    songLoads.incrementAndGet()
                    ApiResult.Success(SongListDto(listOf(song("song-a")), 1), 200)
                }
            },
            readPlaybackHistory = {
                resources.readResource(historyKey, ResourceDependency.HISTORY) {
                    historyLoads.incrementAndGet()
                    ApiResult.Success(HistoryListDto(listOf(event(5, "song-a")), 1), 200)
                }
            },
            scope = controllerScope,
        )
        try {
            controller.activate()
            awaitCondition { controller.state.value.playbackHistory is HomeSectionState.Ready<*> }
            assertEquals(1, songLoads.get())
            assertEquals(1, historyLoads.get())

            val changed = snapshotDto(sequence = initial.sequence + 1, libraryRevision = initial.revisions.library + 1)
            resources.updateIdentity(
                ResourceSessionIdentity("http://server.example", 1, changed.epoch),
                ResourceWatermarks(changed.revisions.library, changed.revisions.playlist, historyResourceFingerprint(changed.history)),
                isFresh = true,
            )
            assertTrue(snapshots.accept(changed, 1, SnapshotSource.HTTP_READ))
            awaitCondition { songLoads.get() == 2 && controller.state.value.recentlyUpdated is HomeSectionState.Ready<*> }
            assertEquals(1, historyLoads.get())
        } finally {
            controller.close()
            resources.close()
            cacheScope.cancel()
            controllerScope.cancel()
        }
    }
}
