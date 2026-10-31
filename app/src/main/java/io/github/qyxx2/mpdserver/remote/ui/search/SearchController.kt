package io.github.qyxx2.mpdserver.remote.ui.search

import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState
import io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.Closeable

/** Identity boundary for a Search projection; playback progress and unrelated History revisions are excluded. */
data class SearchResourceIdentity(
    val session: ResourceSessionIdentity?,
    val libraryRevision: Long?,
    val isFresh: Boolean,
    val isClosed: Boolean = false,
) {
    internal fun sameLibraryAs(other: SearchResourceIdentity): Boolean =
        session == other.session && libraryRevision == other.libraryRevision
}

sealed interface SearchContentState {
    data object Loading : SearchContentState
    data object Empty : SearchContentState
    data class Ready(val projection: SearchProjection) : SearchContentState
    data class Stale(val projection: SearchProjection) : SearchContentState
    data class Failed(val message: String) : SearchContentState
}

data class SearchUiState(
    val query: String = "",
    val content: SearchContentState = SearchContentState.Empty,
)

/**
 * Owns only the transient Search subscription and presentation projection.
 * GETs, Song entities, request coalescing and cache identity remain owned by AppRuntime.
 */
class SearchController internal constructor(
    private val readSearchResults: suspend (String) -> ApiResult<SearchResultsDto>,
    private val readSong: suspend (String) -> ApiResult<SongDto>,
    private val identityFlow: Flow<SearchResourceIdentity>,
    private val parentScope: CoroutineScope,
    private val debounceMillis: Long = 250L,
    private val ownsParentScope: Boolean = false,
) : Closeable {
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val lock = Any()
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private var identity: SearchResourceIdentity? = null
    private var currentQuery = ""
    private var active = false
    private var generation = 0L
    private var queryJob: Job? = null
    private var identityJob: Job? = null

    init {
        require(debounceMillis >= 0L)
    }

    constructor(runtime: AppRuntime) : this(
        readSearchResults = { query -> runtime.reads.getSearchResults(query) },
        readSong = { songId -> runtime.reads.getSong(songId) },
        identityFlow = runtime.resources.identityState.map(ResourceCacheIdentityState::toSearchIdentity),
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        debounceMillis = 250L,
        ownsParentScope = true,
    )

    fun activate() {
        synchronized(lock) {
            if (active) return
            active = true
            // Identity observation belongs to the visible Search page. Cancelling it on leave
            // releases this screen's observer while AppRuntime keeps shared GET flights/cache.
            identityJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var firstEmission = true
                identityFlow.distinctUntilChanged().collect { next ->
                    onIdentityChanged(next, forceReload = firstEmission)
                    firstEmission = false
                }
            }
        }
    }

    /** Cancels only this screen's wait/subscription; ResourceMemoryCache owns shared network flights. */
    fun deactivate() {
        synchronized(lock) {
            active = false
            generation += 1
            queryJob?.cancel()
            queryJob = null
            identityJob?.cancel()
            identityJob = null
        }
    }

    fun setQuery(query: String) {
        val shouldLoad = synchronized(lock) {
            if (currentQuery == query) return
            currentQuery = query
            generation += 1
            queryJob?.cancel()
            queryJob = null
            active && query.isNotBlank()
        }
        mutableState.value = SearchUiState(
            query = query,
            content = if (query.isBlank()) SearchContentState.Empty else SearchContentState.Loading,
        )
        if (shouldLoad) scheduleCurrentQuery()
    }

    fun refresh() {
        val shouldLoad = synchronized(lock) { active && currentQuery.isNotBlank() }
        if (shouldLoad) scheduleCurrentQuery()
    }

    private fun onIdentityChanged(next: SearchResourceIdentity, forceReload: Boolean = false) {
        val shouldLoad: Boolean
        synchronized(lock) {
            val previous = identity
            identity = next
            val resourceChanged = previous != null && !previous.sameLibraryAs(next)
            if (next.isClosed || resourceChanged || !next.isFresh) {
                generation += 1
                queryJob?.cancel()
                queryJob = null
            }
            if (next.isClosed || resourceChanged) {
                mutableState.value = SearchUiState(
                    query = currentQuery,
                    content = if (currentQuery.isBlank()) SearchContentState.Empty else SearchContentState.Loading,
                )
            } else if (!next.isFresh) {
                mutableState.value = mutableState.value.copy(
                    content = mutableState.value.content.asStale(),
                )
            }
            shouldLoad = !next.isClosed && next.isFresh && active && currentQuery.isNotBlank() &&
                (forceReload || previous == null || resourceChanged || previous.isClosed || !previous.isFresh)
        }
        if (shouldLoad) scheduleCurrentQuery()
    }

    private fun scheduleCurrentQuery() {
        synchronized(lock) {
            if (!active || currentQuery.isBlank()) return
            val currentIdentity = identity
            if (currentIdentity?.isFresh != true || currentIdentity.session == null ||
                currentIdentity.libraryRevision == null
            ) return

            generation += 1
            val requestedGeneration = generation
            val requestedQuery = currentQuery
            val requestedIdentity = currentIdentity
            queryJob?.cancel()
            mutableState.value = SearchUiState(requestedQuery, SearchContentState.Loading)
            queryJob = scope.launch {
                delay(debounceMillis)
                executeQuery(requestedGeneration, requestedQuery, requestedIdentity)
            }
        }
    }

    private suspend fun executeQuery(
        requestedGeneration: Long,
        requestedQuery: String,
        requestedIdentity: SearchResourceIdentity,
    ) {
        val normalizedQuery = requestedQuery.trim()
        if (normalizedQuery.isEmpty() || !isCurrent(requestedGeneration, requestedQuery, requestedIdentity)) return

        val result = try {
            readSearchResults(normalizedQuery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publishFailure(requestedGeneration, requestedQuery, requestedIdentity, error.message ?: "搜索请求失败")
            return
        }
        if (!isCurrent(requestedGeneration, requestedQuery, requestedIdentity)) return

        when (result) {
            is ApiResult.Success -> {
                val orderedIds = SearchProjection.orderedSongIds(result.value)
                val songsById = linkedMapOf<String, SongDto>()
                // The shared runtime cache also limits GETs globally; small batches bound this
                // presentation fan-out and keep large search responses from creating hundreds of jobs.
                for (batch in orderedIds.chunked(3)) {
                    if (!isCurrent(requestedGeneration, requestedQuery, requestedIdentity)) return
                    val loaded = coroutineScope {
                        batch.map { id ->
                            async { id to readSongSafely(id) }
                        }.awaitAll()
                    }
                    loaded.forEach { (id, song) ->
                        if (song != null && song.songId == id) songsById[id] = song
                    }
                }
                if (!isCurrent(requestedGeneration, requestedQuery, requestedIdentity)) return
                val projection = SearchProjection.from(result.value, songsById)
                val content = if (projection.groups.isEmpty() && projection.songs.isEmpty()) {
                    SearchContentState.Empty
                } else {
                    SearchContentState.Ready(projection)
                }
                mutableState.value = SearchUiState(requestedQuery, content)
            }
            else -> publishFailure(
                requestedGeneration,
                requestedQuery,
                requestedIdentity,
                result.messageForSearch(),
            )
        }
    }

    private suspend fun readSongSafely(songId: String): SongDto? = try {
        when (val result = readSong(songId)) {
            is ApiResult.Success -> result.value.takeIf { it.songId == songId }
            else -> null // Keep the server-provided ID as a non-playable placeholder in the projection.
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun publishFailure(
        requestedGeneration: Long,
        requestedQuery: String,
        requestedIdentity: SearchResourceIdentity,
        message: String,
    ) {
        if (isCurrent(requestedGeneration, requestedQuery, requestedIdentity)) {
            mutableState.value = SearchUiState(requestedQuery, SearchContentState.Failed(message))
        }
    }

    private fun isCurrent(
        requestedGeneration: Long,
        requestedQuery: String,
        requestedIdentity: SearchResourceIdentity,
    ): Boolean = synchronized(lock) {
        active && generation == requestedGeneration && currentQuery == requestedQuery &&
            identity?.isFresh == true && identity?.sameLibraryAs(requestedIdentity) == true
    }

    override fun close() {
        synchronized(lock) {
            identityJob?.cancel()
            identityJob = null
            queryJob?.cancel()
            queryJob = null
            active = false
        }
        controllerJob.cancel()
        if (ownsParentScope) parentScope.cancel()
    }

    private fun SearchContentState.asStale(): SearchContentState = when (this) {
        is SearchContentState.Ready -> SearchContentState.Stale(projection)
        is SearchContentState.Stale -> this
        SearchContentState.Loading -> SearchContentState.Loading
        SearchContentState.Empty -> SearchContentState.Empty
        is SearchContentState.Failed -> this
    }

    private fun ApiResult<*>.messageForSearch(): String = when (this) {
        is ApiResult.BusinessError -> error.message
        is ApiResult.HttpError -> message
        is ApiResult.TransportError -> "无法连接音乐服务器"
        is ApiResult.DecodeError -> message
        is ApiResult.ProtocolError -> message
        ApiResult.NoContent -> "服务器未返回搜索结果"
        is ApiResult.Accepted -> "搜索请求意外进入异步队列"
        is ApiResult.Success<*> -> "未知搜索状态"
    }
}

private fun ResourceCacheIdentityState.toSearchIdentity() = SearchResourceIdentity(
    session = session,
    libraryRevision = watermarks?.libraryRevision,
    isFresh = isFresh,
    isClosed = isClosed,
)
