package io.github.qyxx2.mpdserver.remote.ui.search

import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchControllerTest {
    private fun identity(revision: Long = 1L, fresh: Boolean = true) = SearchResourceIdentity(
        session = ResourceSessionIdentity("http://server.example:8080", 3L, "epoch-1"),
        libraryRevision = revision,
        isFresh = fresh,
    )

    @Test
    fun blankOrWhitespaceQueryNeverStartsAServerRead() = runBlocking {
        val requests = mutableListOf<String>()
        val fixture = controller(readSearch = { query ->
            requests += query
            ApiResult.Success(SearchResultsDto(emptyList(), emptyList()), 200)
        })
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("  \n  ")
            delay(30)
            assertTrue(requests.isEmpty())
            assertTrue(fixture.controller.state.value.content is SearchContentState.Empty)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun queryIsTrimmedOnlyForTheServerAndDebounced() = runBlocking {
        val requests = mutableListOf<String>()
        val fixture = controller(
            readSearch = { query ->
                requests += query
                ApiResult.Success(SearchResultsDto(emptyList(), emptyList()), 200)
            },
            debounceMillis = 40L,
        )
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("  jazz piano  ")
            delay(15)
            assertTrue("The query must wait for the debounce interval", requests.isEmpty())
            withTimeout(2_000) {
                fixture.controller.state.first { it.content is SearchContentState.Empty }
            }
            assertEquals(listOf("jazz piano"), requests)
            assertEquals("  jazz piano  ", fixture.controller.state.value.query)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun lateResponseFromAnOlderQueryCannotReplaceTheNewerResults() = runBlocking {
        val oldResult = CompletableDeferred<ApiResult<SearchResultsDto>>()
        val oldStarted = CompletableDeferred<Unit>()
        val fixture = controller(
            readSearch = { query ->
                if (query == "old") {
                    oldStarted.complete(Unit)
                    oldResult.await()
                } else {
                    ApiResult.Success(SearchResultsDto(emptyList(), listOf("new-song")), 200)
                }
            },
            readSong = { ApiResult.Success(song("new-song"), 200) },
            debounceMillis = 0L,
        )
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("old")
            withTimeout(2_000) { oldStarted.await() }
            fixture.controller.setQuery("new")
            withTimeout(2_000) {
                fixture.controller.state.first {
                    it.query == "new" && it.content is SearchContentState.Ready
                }
            }
            oldResult.complete(ApiResult.Success(SearchResultsDto(emptyList(), listOf("old-song")), 200))
            delay(30)
            val state = fixture.controller.state.value
            assertEquals("new", state.query)
            assertEquals(listOf("new-song"), (state.content as SearchContentState.Ready).projection.songs.map { it.songId })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun libraryIdentityChangeInvalidatesThePreviousQueryProjection() = runBlocking {
        val identity = MutableStateFlow(identity())
        val fixture = controller(identity = identity, debounceMillis = 0L)
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("album")
            withTimeout(2_000) {
                fixture.controller.state.first { it.content is SearchContentState.Ready }
            }
            identity.value = identity(revision = 2L)
            withTimeout(2_000) {
                fixture.controller.state.first {
                    it.content is SearchContentState.Ready &&
                        (it.content as SearchContentState.Ready).projection.songs.singleOrNull()?.songId == "song-2"
                }
            }
            assertEquals(2L, identity.value.libraryRevision)
        } finally {
            fixture.close()
        }
    }


    @Test
    fun clearingQueryCancelsTheCurrentUiSubscriptionWithoutPublishingItsLateResult() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val lateResult = CompletableDeferred<ApiResult<SearchResultsDto>>()
        val fixture = controller(
            readSearch = {
                started.complete(Unit)
                try {
                    lateResult.await()
                } finally {
                    cancelled.complete(Unit)
                }
            },
            debounceMillis = 0L,
        )
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("slow")
            withTimeout(2_000) { started.await() }
            fixture.controller.setQuery("   ")
            withTimeout(2_000) { cancelled.await() }
            lateResult.complete(ApiResult.Success(SearchResultsDto(emptyList(), listOf("too-late")), 200))
            delay(30)
            assertTrue(fixture.controller.state.value.content is SearchContentState.Empty)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun emptySuccessAndServerFailureRemainDistinctUiStates() = runBlocking {
        var shouldFail = false
        val fixture = controller(
            readSearch = {
                if (shouldFail) ApiResult.ProtocolError("search DTO invalid")
                else ApiResult.Success(SearchResultsDto(emptyList(), emptyList()), 200)
            },
            debounceMillis = 0L,
        )
        try {
            fixture.controller.activate()
            fixture.controller.setQuery("empty")
            withTimeout(2_000) {
                fixture.controller.state.first { it.content is SearchContentState.Empty }
            }
            shouldFail = true
            fixture.controller.setQuery("failure")
            withTimeout(2_000) {
                fixture.controller.state.first { it.content is SearchContentState.Failed }
            }
            assertEquals("search DTO invalid", (fixture.controller.state.value.content as SearchContentState.Failed).message)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun identityObserverIsReleasedWhenSearchLeavesAndResubscribedOnReturn() = runBlocking {
        val activeCollectors = AtomicInteger(0)
        val source = MutableStateFlow(identity())
        val tracked = flow {
            activeCollectors.incrementAndGet()
            try {
                source.collect { emit(it) }
            } finally {
                activeCollectors.decrementAndGet()
            }
        }
        val requests = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = SearchController(
            readSearchResults = { query ->
                requests += query
                ApiResult.Success(SearchResultsDto(emptyList(), emptyList()), 200)
            },
            readSong = { ApiResult.ProtocolError("no songs in this test") },
            identityFlow = tracked,
            parentScope = scope,
            debounceMillis = 0L,
        )
        try {
            controller.activate()
            withTimeout(2_000) { while (activeCollectors.get() != 1) delay(5) }
            controller.setQuery("query")
            withTimeout(2_000) { while (requests.isEmpty()) delay(5) }
            controller.deactivate()
            withTimeout(2_000) { while (activeCollectors.get() != 0) delay(5) }

            source.value = identity(revision = 2L)
            delay(30)
            assertEquals(listOf("query"), requests)

            controller.activate()
            withTimeout(2_000) { while (activeCollectors.get() != 1 || requests.size < 2) delay(5) }
            assertEquals(listOf("query", "query"), requests)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    private data class Fixture(val controller: SearchController, val scope: CoroutineScope) {
        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private fun controller(
        identity: MutableStateFlow<SearchResourceIdentity> = MutableStateFlow(identity()),
        readSearch: suspend (String) -> ApiResult<SearchResultsDto> = {
            ApiResult.Success(SearchResultsDto(emptyList(), listOf("song-${identity.value.libraryRevision}")), 200)
        },
        readSong: suspend (String) -> ApiResult<SongDto> = { ApiResult.Success(song(it), 200) },
        debounceMillis: Long = 10L,
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Fixture(SearchController(readSearch, readSong, identity, scope, debounceMillis), scope)
    }

    private fun song(id: String) = SongDto(
        songId = id, title = id, fileUri = "music/$id.flac",
        artists = emptyList(), albumId = null, album = null, albumArtists = emptyList(),
        trackNumber = null, discNumber = null, year = null, date = null,
        genres = emptyList(), tagNames = emptyList(), duration = null, lyrics = null,
        lyricsFormat = null, lyricsSource = null, bitDepth = null, sampleRateHz = null,
        channelCount = null, codec = null, metadataStatus = null, lastScannedAt = null,
        fileSize = null, fileMtimeNs = null, contentHash = null, lastSeenAt = null, artwork = null,
    )
}
