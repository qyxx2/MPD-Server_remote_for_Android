package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.data.LibraryApiPaths
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheKey
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState
import io.github.qyxx2.mpdserver.remote.runtime.ResourceEntryState
import io.github.qyxx2.mpdserver.remote.runtime.ResourceMemoryCache
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStore
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.runtime.historyResourceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.Closeable

/**
 * Owns Home observation and refresh lifecycle, but not network/cache/snapshot ownership.
 * All resource reads are delegated to AppRuntime's existing RuntimeReadApi.
 */
class HomeDataController private constructor(
    private val resources: ResourceMemoryCache,
    private val snapshots: SnapshotStore,
    private val readRecentUpdates: suspend () -> ApiResult<SongListDto>,
    private val readPlaybackHistory: suspend () -> ApiResult<HistoryListDto>,
    parentScope: CoroutineScope,
    private val ownsParentScope: Boolean,
    private val currentOriginAddress: () -> String,
) : Closeable {
    private val parentScope = parentScope
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val recentKey = ResourceCacheKey.get<SongListDto>(
        LibraryApiPaths.songs(sortBy = "modified_at", sortOrder = "desc"),
    )
    private val historyKey = ResourceCacheKey.get<HistoryListDto>(LibraryApiPaths.history())
    private val lifecycleLock = Any()
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    @Volatile private var active = false
    @Volatile private var closed = false
    private var lastRequestedIdentity: HomeRequestIdentity? = null
    private var pendingIdentity: HomeRequestIdentity? = null
    private var identityWaitJob: Job? = null
    private var loadJob: Job? = null
    private var historyExpiryJob: Job? = null
    private val historyFingerprintLock = Any()
    private var hasHistoryElement = false
    private var lastHistoryElement: JsonElement? = null
    private var lastHistoryFingerprint = historyResourceFingerprint(null)

    private data class HomeRequestIdentity(
        val originAddress: String,
        val connectionGeneration: Long,
        val epoch: String,
        val libraryRevision: Long,
        val historyFingerprint: String,
    )

    private fun ResourceCacheIdentityState.matches(identity: HomeRequestIdentity): Boolean =
        !isClosed && isFresh &&
            session?.originAddress == identity.originAddress &&
            session.connectionGeneration == identity.connectionGeneration &&
            session.epoch == identity.epoch &&
            watermarks?.libraryRevision == identity.libraryRevision &&
            watermarks.historyFingerprint == identity.historyFingerprint

    private val recentResource = resources.observeResource(recentKey)
    private val historyResource = resources.observeResource(historyKey)

    /** Song/History projections are independent of high-frequency playback snapshot emissions. */
    private val resourceSections = combine(recentResource, historyResource) { recent, history ->
        val songIndex = recent.songIndex()
        HomeResourceSections(
            recentlyUpdated = recent.toRecentState(),
            playbackHistory = history.toHistoryState(songIndex, recent),
        )
    }.distinctUntilChanged()

    /** Rebuild AutoPlay only when its queue/context/freshness inputs change, not on progress ticks. */
    private val autoplaySection = combine(snapshots.state, recentResource) { snapshot, recent ->
        snapshot.autoplayInput(recent)
    }.distinctUntilChanged { previous, next ->
        previous.key == next.key && previous.songIndex === next.songIndex
    }.map { it.toSection() }

    private val projectionJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        combine(resourceSections, autoplaySection) { sections, autoplay ->
            HomeUiState(sections.recentlyUpdated, sections.playbackHistory, autoplay)
        }.collect { projected -> _state.value = projected }
    }

    private val snapshotJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        snapshots.state.collect { snapshotState ->
            if (!snapshotState.controlsEligible) {
                pauseForUnavailableSnapshot()
            } else if (active) {
                requestForSnapshot(snapshotState, force = false)
            }
        }
    }

    internal constructor(
        resources: ResourceMemoryCache,
        snapshots: SnapshotStore,
        readRecentUpdates: suspend () -> ApiResult<SongListDto>,
        readPlaybackHistory: suspend () -> ApiResult<HistoryListDto>,
        scope: CoroutineScope,
        currentOriginAddress: () -> String = {
            resources.identityState.value.session?.originAddress.orEmpty()
        },
    ) : this(
        resources,
        snapshots,
        readRecentUpdates,
        readPlaybackHistory,
        scope,
        ownsParentScope = false,
        currentOriginAddress = currentOriginAddress,
    )

    constructor(runtime: AppRuntime) : this(
        resources = runtime.resources,
        snapshots = runtime.snapshots,
        readRecentUpdates = {
            runtime.reads.getSongs(sortBy = "modified_at", sortOrder = "desc")
        },
        readPlaybackHistory = { runtime.reads.getHistory() },
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ownsParentScope = true,
        currentOriginAddress = { runtime.realtimeController.currentOrigin().address },
    )

    /** Begin Home observation. Re-reading an unexpired resource is a cache hit, not another GET. */
    fun activate() {
        if (closed) return
        active = true
        requestForSnapshot(snapshots.state.value, force = true)
    }

    /** Stop the Home-only History expiry timer; shared cache and in-flight runtime reads remain owned by AppRuntime. */
    fun deactivate() {
        active = false
        synchronized(lifecycleLock) {
            identityWaitJob?.cancel()
            identityWaitJob = null
            pendingIdentity = null
        }
        cancelHistoryExpiry()
    }

    /** Retry only the failed/stale Song index; dependent History and AutoPlay projections update automatically. */
    fun refreshRecentUpdates() {
        if (!canReadCurrentSnapshot()) return
        scope.launch { readRecentSafely() }
    }

    /** Retry only persistent History, never Queue's /api/history/played projection. */
    fun refreshPlaybackHistory() {
        if (!canReadCurrentSnapshot()) return
        scope.launch {
            if (readHistorySafely() is ApiResult.Success) scheduleHistoryExpiry()
        }
    }

    /** Explicit refresh for all Home data sources, while preserving their independent result states. */
    fun refresh() {
        if (closed || !active) return
        requestForSnapshot(snapshots.state.value, force = true)
    }

    private fun requestForSnapshot(snapshotState: SnapshotStoreState, force: Boolean) {
        if (closed || !active || !snapshotState.controlsEligible) return
        val snapshot = snapshotState.snapshot ?: return
        val identity = HomeRequestIdentity(
            originAddress = currentOriginAddress(),
            connectionGeneration = snapshotState.connectionGeneration,
            epoch = snapshot.epoch,
            libraryRevision = snapshot.libraryRevision,
            historyFingerprint = historyFingerprintFor(snapshot.wire.history),
        )
        synchronized(lifecycleLock) {
            if (!force && lastRequestedIdentity == identity) return
            if (pendingIdentity == identity && identityWaitJob?.isActive == true) return
            if (force && lastRequestedIdentity == identity && loadJob?.isActive == true) return

            identityWaitJob?.cancel()
            pendingIdentity = identity
            identityWaitJob = scope.launch {
                // SnapshotStore and AppRuntime each observe the same accepted state. Wait for the
                // cache owner to publish matching watermarks instead of racing collector scheduling.
                val cacheState = resources.identityState.first { state ->
                    state.isClosed || state.matches(identity)
                }
                if (cacheState.isClosed) return@launch
                val latestState = snapshots.state.value
                val latestSnapshot = latestState.snapshot
                val latestIdentity = latestSnapshot?.let {
                    HomeRequestIdentity(
                        originAddress = currentOriginAddress(),
                        connectionGeneration = latestState.connectionGeneration,
                        epoch = it.epoch,
                        libraryRevision = it.libraryRevision,
                        historyFingerprint = historyFingerprintFor(it.wire.history),
                    )
                }
                synchronized(lifecycleLock) {
                    if (pendingIdentity == identity) {
                        pendingIdentity = null
                        identityWaitJob = null
                    }
                    if (closed || !active || !latestState.controlsEligible || latestIdentity != identity) {
                        return@synchronized
                    }
                    if (!force && lastRequestedIdentity == identity) return@synchronized
                    if (force && lastRequestedIdentity == identity && loadJob?.isActive == true) {
                        return@synchronized
                    }
                    lastRequestedIdentity = identity
                    loadJob?.cancel()
                    loadJob = scope.launch {
                        val historyResult = coroutineScope {
                            val recent = async { readRecentSafely() }
                            val history = async { readHistorySafely() }
                            recent.await()
                            history.await()
                        }
                        if (historyResult is ApiResult.Success) {
                            scheduleHistoryExpiry()
                        } else {
                            cancelHistoryExpiry()
                        }
                    }
                }
            }
        }
    }

    /** Avoid re-hashing the full persistent History JSON on playback/progress-only snapshots. */
    private fun historyFingerprintFor(history: JsonElement?): String = synchronized(historyFingerprintLock) {
        if (!hasHistoryElement || history !== lastHistoryElement) {
            if (!hasHistoryElement || history != lastHistoryElement) {
                lastHistoryFingerprint = historyResourceFingerprint(history)
            }
            lastHistoryElement = history
            hasHistoryElement = true
        }
        lastHistoryFingerprint
    }

    /** Any stale/disconnected interval must permit a same-revision read after freshness returns. */
    private fun pauseForUnavailableSnapshot() {
        synchronized(lifecycleLock) {
            identityWaitJob?.cancel()
            identityWaitJob = null
            pendingIdentity = null
            lastRequestedIdentity = null
            loadJob?.cancel()
            loadJob = null
        }
        cancelHistoryExpiry()
    }

    private suspend fun readRecentSafely(): ApiResult<SongListDto> = try {
        readRecentUpdates()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ApiResult.ProtocolError(error.message ?: "Recent updates read failed")
    }

    private suspend fun readHistorySafely(): ApiResult<HistoryListDto> = try {
        readPlaybackHistory()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ApiResult.ProtocolError(error.message ?: "Persistent History read failed")
    }

    private fun canReadCurrentSnapshot(): Boolean {
        if (closed || !active) return false
        val snapshotState = snapshots.state.value
        val snapshot = snapshotState.snapshot ?: return false
        if (!snapshotState.controlsEligible) return false
        val identity = HomeRequestIdentity(
            originAddress = currentOriginAddress(),
            connectionGeneration = snapshotState.connectionGeneration,
            epoch = snapshot.epoch,
            libraryRevision = snapshot.libraryRevision,
            historyFingerprint = historyFingerprintFor(snapshot.wire.history),
        )
        return resources.identityState.value.matches(identity)
    }

    private fun scheduleHistoryExpiry() {
        val remaining = resources.remainingTtlMillis(historyKey) ?: return
        synchronized(lifecycleLock) {
            historyExpiryJob?.cancel()
            if (closed || !active || !snapshots.state.value.controlsEligible) {
                historyExpiryJob = null
                return
            }
            historyExpiryJob = scope.launch {
                delay(remaining.coerceAtLeast(1L))
                synchronized(lifecycleLock) { historyExpiryJob = null }
                if (closed || !active || !snapshots.state.value.controlsEligible) return@launch
                if (readHistorySafely() is ApiResult.Success) {
                    scheduleHistoryExpiry()
                }
            }
        }
    }

    private fun cancelHistoryExpiry() {
        synchronized(lifecycleLock) {
            historyExpiryJob?.cancel()
            historyExpiryJob = null
        }
    }

    private data class HomeResourceSections(
        val recentlyUpdated: HomeSectionState<HomeRecentUpdates>,
        val playbackHistory: HomeSectionState<HomePlaybackHistory>,
    )

    private data class HomeAutoplayProjectionKey(
        val epoch: String?,
        val connectionGeneration: Long,
        val snapshotConnectionGeneration: Long?,
        val freshness: io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness,
        val controlsEligible: Boolean,
        val queueRevision: Long?,
        val libraryRevision: Long?,
        val playbackContextId: String?,
        val autoplayEnabled: Boolean?,
        val snapshotReadError: String?,
        val songResourceState: String,
    )

    private data class HomeAutoplayProjectionInput(
        val snapshotState: SnapshotStoreState,
        val songIndex: List<SongDto>,
        val songIndexStale: Boolean,
        val key: HomeAutoplayProjectionKey,
    ) {
        fun toSection(): HomeSectionState<HomeAutoplay> {
            val accepted = snapshotState.snapshot ?: return if (snapshotState.readError.isNullOrBlank()) {
                HomeSectionState.Loading
            } else {
                HomeSectionState.Failed(snapshotState.readError)
            }
            val content = try {
                HomeProjection.autoplay(accepted, songIndex)
            } catch (error: Exception) {
                return HomeSectionState.Failed(error.message ?: "Accepted playback snapshot could not be decoded")
            }
            return when {
                snapshotState.freshness != io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness.FRESH ||
                    !snapshotState.controlsEligible || songIndexStale -> HomeSectionState.Stale(content)
                content.cards.isEmpty() -> HomeSectionState.Empty
                else -> HomeSectionState.Ready(content)
            }
        }
    }

    private fun SnapshotStoreState.autoplayInput(
        recent: ResourceEntryState<SongListDto>,
    ): HomeAutoplayProjectionInput {
        val songIndex = recent.songIndex()
        val snapshot = snapshot
        val playback = snapshot?.wire?.playback?.let { wire ->
            runCatching { JsonConfiguration.json.decodeFromJsonElement<PlaybackStateDto>(wire) }.getOrNull()
        }
        val resourceState = when (recent) {
            ResourceEntryState.Empty -> "empty"
            ResourceEntryState.Loading -> "loading"
            is ResourceEntryState.Ready -> "ready"
            is ResourceEntryState.Stale -> "stale"
            is ResourceEntryState.Failed -> "failed:${recent.message}"
        }
        return HomeAutoplayProjectionInput(
            snapshotState = this,
            songIndex = songIndex,
            songIndexStale = recent is ResourceEntryState.Stale,
            key = HomeAutoplayProjectionKey(
                epoch = snapshot?.epoch,
                connectionGeneration = connectionGeneration,
                snapshotConnectionGeneration = snapshotConnectionGeneration,
                freshness = freshness,
                controlsEligible = controlsEligible,
                queueRevision = snapshot?.queueRevision,
                libraryRevision = snapshot?.libraryRevision,
                playbackContextId = playback?.playbackContextId,
                autoplayEnabled = playback?.autoplayEnabled,
                snapshotReadError = readError,
                songResourceState = resourceState,
            ),
        )
    }

    private fun ResourceEntryState<SongListDto>.songIndex(): List<SongDto> = when (this) {
        is ResourceEntryState.Ready -> value.items
        is ResourceEntryState.Stale -> value.items
        else -> emptyList()
    }

    private fun ResourceEntryState<SongListDto>.toRecentState(): HomeSectionState<HomeRecentUpdates> =
        when (this) {
            ResourceEntryState.Empty, ResourceEntryState.Loading -> HomeSectionState.Loading
            is ResourceEntryState.Failed -> HomeSectionState.Failed(message)
            is ResourceEntryState.Ready -> {
                val content = HomeProjection.recentlyUpdated(value.items)
                if (content.cards.isEmpty()) HomeSectionState.Empty else HomeSectionState.Ready(content)
            }
            is ResourceEntryState.Stale -> HomeSectionState.Stale(HomeProjection.recentlyUpdated(value.items))
        }

    private fun ResourceEntryState<HistoryListDto>.toHistoryState(
        songs: List<io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto>,
        songState: ResourceEntryState<SongListDto>,
    ): HomeSectionState<HomePlaybackHistory> = when (this) {
        ResourceEntryState.Empty, ResourceEntryState.Loading -> HomeSectionState.Loading
        is ResourceEntryState.Failed -> HomeSectionState.Failed(message)
        is ResourceEntryState.Ready -> {
            val content = HomeProjection.playbackHistory(value.items, songs)
            if (content.cards.isEmpty()) HomeSectionState.Empty
            else if (songState is ResourceEntryState.Stale) HomeSectionState.Stale(content)
            else HomeSectionState.Ready(content)
        }
        is ResourceEntryState.Stale -> HomeSectionState.Stale(
            HomeProjection.playbackHistory(value.items, songs),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        deactivate()
        projectionJob.cancel()
        snapshotJob.cancel()
        loadJob?.cancel()
        controllerJob.cancel()
        if (ownsParentScope) parentScope.cancel()
    }
}
