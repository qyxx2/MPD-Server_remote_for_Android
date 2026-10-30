package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenBottomNavigation
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenExitGestureBand
import io.github.qyxx2.mpdserver.remote.ui.player.OutputDetailsDialog
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkBackdrop
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkCacheKey
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkCacheState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerArtworkMemoryCache
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class PlayedReadStatus { WAITING, LOADING, CANONICAL, FALLBACK, FAILED }

/** Creates only a bounded presentation cache around the existing app-scoped RuntimeReadApi. */
internal fun createQueueSongCache(
    runtime: AppRuntime,
    scope: CoroutineScope,
    maxConcurrentRequests: Int = 5,
    maxEntries: Int = 512,
    maxArtworkBytesTotal: Long = 24L * 1024 * 1024,
    loaderOverride: (suspend (QueueResourceIdentity, String) -> QueueSongResource)? = null,
): QueueSongCache = QueueSongCache(
    scope = scope,
    maxConcurrentRequests = maxConcurrentRequests,
    maxEntries = maxEntries,
    maxArtworkBytesTotal = maxArtworkBytesTotal,
    artworkPrefetcher = { identity, songId, bytes ->
        QueueArtworkThumbnailCache.preloadPrefetchOnce(
            QueueArtworkThumbnailKey(identity, songId),
            bytes,
        )
    },
) { identity, songId ->
    if (loaderOverride != null) {
        loaderOverride(identity, songId)
    } else {
        val response = runtime.reads.getSong(songId)
        val song = (response as? ApiResult.Success)?.value
            ?: throw IllegalStateException("Song metadata could not be read")
        if (song.songId != songId) {
            throw IllegalStateException("Song metadata identity did not match the requested song")
        }
        val artwork = if (song.artwork != null) runtime.reads.getArtwork(songId) else null
        QueueSongResource(song, artwork)
    }
}

/** Canonical Queue/Played projection with B6 mutations delegated to the shared Runtime coordinator. */
@Composable
internal fun QueueScreen(
    runtime: AppRuntime,
    snapshotState: SnapshotStoreState,
    presentation: PlayerPresentation,
    onExit: () -> Unit,
    onOpenPlayer: () -> Unit,
    artworkPresentation: PlayerPresentation = presentation,
    artworkCacheKey: PlayerArtworkCacheKey? = null,
    artworkCache: PlayerArtworkMemoryCache? = null,
    contentPadding: PaddingValues = PaddingValues(),
    playedReader: (suspend () -> ApiResult<QueueListDto>)? = null,
    songResourceLoader: (suspend (QueueResourceIdentity, String) -> QueueSongResource)? = null,
    songCache: QueueSongCache? = null,
    snapshotProvider: () -> SnapshotStoreState = { runtime.snapshots.state.value },
    queueActionsFactory: (() -> QueueActions)? = null,
    onDismissDragDelta: (Float) -> Unit = {},
    onDismissGestureEnd: ((Float) -> Unit)? = null,
    onDismissGestureCancel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activities by runtime.mutations.activities.collectAsStateWithLifecycle()
    val actions = remember(runtime, queueActionsFactory) {
        queueActionsFactory?.invoke() ?: QueueActions(
            stateProvider = { runtime.snapshots.state.value },
            activitiesProvider = { runtime.mutations.activities.value },
            submitter = { runtime.mutations.submit(it) },
        )
    }
    var selectedMenuItem by remember { mutableStateOf<QueueItemDto?>(null) }
    var toolDialog by remember { mutableStateOf<QueueToolDialog?>(null) }
    var pendingSaveIntentId by remember { mutableStateOf<String?>(null) }
    var saveReadbackError by remember { mutableStateOf<String?>(null) }
    var saveReadbackOnlyRetry by remember { mutableStateOf(false) }
    var saveAlreadyConfirmed by remember { mutableStateOf(false) }
    // A realtime playback-position tick replaces SnapshotStoreState even when Queue contents do
    // not change. Keep the expensive Queue projection stable until a Queue revision, autoplay rule,
    // confirmation semantic, or connection/freshness input actually changes.
    val queueProjectionKey = QueuePresentationCacheKey.from(snapshotState)
    val canonicalProjection = remember(queueProjectionKey) {
        QueuePresentation.deriveQueue(snapshotState)
    }
    var playedFallback by remember { mutableStateOf<List<QueueItemDto>?>(null) }
    var playedReadStatus by remember { mutableStateOf(PlayedReadStatus.WAITING) }
    var playedReadRequestGeneration by remember(runtime) { mutableLongStateOf(0L) }
    val queueProjection = remember(canonicalProjection, playedFallback) {
        canonicalProjection.withPlayedFallback(playedFallback)
    }

    val currentOrigin: ServerOrigin = runtime.realtimeController.currentOrigin()
    val resourceIdentity = QueueResourceIdentity(
        epoch = queueProjection.epoch ?: "no-snapshot",
        libraryRevision = queueProjection.libraryRevision ?: 0L,
        connectionGeneration = snapshotState.connectionGeneration,
        originAddress = currentOrigin.address,
    )
    // AppNavHost can supply its stable instance so metadata/artwork survive Queue route re-entry.
    // Direct callers (including isolated UI tests) still receive a screen-scoped cache.
    val cache = songCache ?: remember(runtime, scope) {
        createQueueSongCache(runtime, scope, loaderOverride = songResourceLoader)
    }
    val artworkCacheState = artworkCache?.state?.collectAsStateWithLifecycle()?.value
        ?: PlayerArtworkCacheState.Empty
    val currentArtworkKey = artworkCacheKey?.takeIf { it.matches(artworkPresentation) }
    val blurredArtworkBitmap = (artworkCacheState as? PlayerArtworkCacheState.Ready)
        ?.takeIf { it.key == currentArtworkKey }
        ?.blurred
    val visibleRows = remember(queueProjection.current, queueProjection.next) {
        buildList {
            queueProjection.current?.let(::add)
            addAll(queueProjection.next)
        }
    }
    // Display order runs oldest -> newest -> current -> pending, matching Web's reversal of
    // its newest-first Played prefix. The current occurrence remains the initial viewport anchor.
    val playedRows = remember(queueProjection.played) {
        PlayedHistoryOrder.inDisplayOrder(queueProjection.played)
    }
    val queueListTopPadding = 4.dp
    val hasQueueAnchor = queueProjection.current != null || visibleRows.isNotEmpty()
    val queueAnchorScrollOffsetPx = with(LocalDensity.current) {
        if (hasQueueAnchor) queueListTopPadding.roundToPx() else 0
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (hasQueueAnchor) playedRows.size else 0,
        initialFirstVisibleItemScrollOffset = queueAnchorScrollOffsetPx,
    )
    val showHistoryJumpButton by remember(listState, playedRows.size, visibleRows.isNotEmpty()) {
        derivedStateOf {
            playedRows.isNotEmpty() && visibleRows.isNotEmpty() &&
                listState.firstVisibleItemIndex < playedRows.size
        }
    }
    val queueRowIds = remember(playedRows, visibleRows) {
        playedRows.map { it.queueItemId } + visibleRows.map { it.queueItemId }
    }
    var listViewportHeightPx by remember(
        queueProjection.epoch,
        queueProjection.queueRevision,
        queueProjection.current?.queueItemId,
        queueRowIds,
    ) { mutableIntStateOf(0) }
    LaunchedEffect(
        listState,
        queueProjection.epoch,
        queueProjection.queueRevision,
        queueProjection.current?.queueItemId,
        queueRowIds,
    ) {
        listViewportHeightPx = snapshotFlow { listState.layoutInfo.viewportSize.height }
            .first { height -> height > 0 }
    }
    val density = LocalDensity.current
    // If current + pending rows do not fill the viewport, preserve enough transparent space
    // after the pending rows for the current row to anchor at the top while Played stays scrollable
    // above it. This spacer is layout-only and is never a Queue occurrence.
    val bottomScrollBufferHeightPx = with(density) {
        if (queueProjection.current == null || listViewportHeightPx == 0) 0 else {
            (listViewportHeightPx - visibleRows.size * 84.dp.roundToPx() - 8.dp.roundToPx())
                .coerceAtLeast(0)
        }
    }
    val reorderBaseline = remember(queueProjectionKey) { QueueReorderBaseline.from(snapshotState) }
    val visiblePendingIds = remember(visibleRows) { visibleRows.filter { it.position > 0 }.map { it.queueItemId } }
    var reorderDraft by remember(runtime) { mutableStateOf<QueueReorderDraft?>(null) }
    // The ordered draft drives only the insertion marker. As in Web, the canonical rows stay in
    // place until release; a separate masked proxy follows the pointer above the list.
    var dragPointerY by remember(runtime) { mutableStateOf<Float?>(null) }
    var dragSourceTop by remember(runtime) { mutableStateOf<Float?>(null) }
    var dragSourceHeight by remember(runtime) { mutableStateOf<Float?>(null) }
    var dragOffsetX by remember(runtime) { mutableStateOf(0f) }
    var dragOffsetY by remember(runtime) { mutableStateOf(0f) }
    val activeReorderDraft = reorderDraft?.takeIf {
        it.isCompatible(reorderBaseline, actions.isEnabled, visiblePendingIds)
    }
    val activeDraggedSongId = activeReorderDraft?.draggedQueueItemId?.let { id ->
        visibleRows.firstOrNull { it.queueItemId == id }?.songId
    }
    // Keep the proxy metadata in sync even when a resource read completes without pointer motion.
    // The flow is narrowed to the dragged song so unrelated prefetch completions do not recompose
    // the Queue screen during normal scrolling.
    val draggedEntry by remember(cache, resourceIdentity, activeDraggedSongId) {
        cache.state.map { entries ->
            activeDraggedSongId?.let { entries[it]?.takeIf { entry -> entry.identity == resourceIdentity } }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = activeDraggedSongId?.let { cache.currentEntry(it, resourceIdentity) },
    )

    fun resetReorderDrag() {
        reorderDraft = null
        dragPointerY = null
        dragSourceTop = null
        dragSourceHeight = null
        dragOffsetX = 0f
        dragOffsetY = 0f
    }

    LaunchedEffect(reorderDraft, reorderBaseline, actions.isEnabled, visiblePendingIds) {
        if (reorderDraft != null && activeReorderDraft == null) resetReorderDrag()
    }

    fun startReorder(queueItemId: String) {
        if (!actions.isEnabled || reorderBaseline == null || visiblePendingIds.size < 2) return
        val latestState = snapshotProvider()
        val latestBaseline = QueueReorderBaseline.from(latestState) ?: return
        if (latestBaseline != reorderBaseline) return
        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueItemId } ?: return
        val draft = QueueReorderDraft.begin(latestBaseline, queueItemId, visiblePendingIds) ?: return
        dragSourceTop = itemInfo.offset.toFloat()
        dragSourceHeight = itemInfo.size.toFloat()
        dragPointerY = itemInfo.offset + itemInfo.size / 2f
        dragOffsetX = 0f
        dragOffsetY = 0f
        reorderDraft = draft
    }

    fun updateReorder(delta: Offset) {
        val draft = reorderDraft ?: return
        if (!draft.isCompatible(reorderBaseline, actions.isEnabled, visiblePendingIds)) {
            resetReorderDrag()
            return
        }
        val previousPointerY = dragPointerY ?: return
        val pointerY = previousPointerY + delta.y
        dragPointerY = pointerY
        dragOffsetX += delta.x
        dragOffsetY += delta.y
        val remaining = draft.orderedVisiblePendingIds.filterNot { it == draft.draggedQueueItemId }.toSet()
        val rowCenters = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val id = info.key.toString()
            id.takeIf { it in remaining }?.let { it to (info.offset + info.size / 2f) }
        }.toMap()
        if (rowCenters.isEmpty()) return
        val targetBefore = draft.targetBefore(pointerY, rowCenters)
        val updated = draft.moveBefore(targetBefore)
        if (updated != null) reorderDraft = updated
    }

    fun finishReorder() {
        val draft = reorderDraft
        val latestState = snapshotProvider()
        val latestBaseline = QueueReorderBaseline.from(latestState)
        val latestProjection = QueuePresentation.deriveQueue(latestState, playedFallback)
        val latestRows = buildList {
            latestProjection.current?.let(::add)
            addAll(latestProjection.next)
        }
        val latestVisiblePendingIds = latestRows.filter { it.position > 0 }.map { it.queueItemId }
        val commit = draft?.finish(latestBaseline, actions.isEnabled, latestVisiblePendingIds)
        resetReorderDrag()
        if (commit != null) {
            val source = latestRows.firstOrNull { it.queueItemId == commit.queueItemId }
                ?: latestProjection.pending.firstOrNull { it.queueItemId == commit.queueItemId }
            val title = cache.state.value[source?.songId]?.song?.title?.takeIf(String::isNotBlank)
                ?: source?.songId
                ?: commit.queueItemId
            actions.reorder(commit.queueItemId, commit.beforeQueueItemId, title)
        }
    }

    fun cancelReorder() {
        resetReorderDrag()
    }

    // History occurrences are already in the same LazyColumn as current/pending rows. Warm a
    // small recent-history neighborhood before the first gesture; as ordinary scrolling exposes
    // older history, extend prefetch around the viewport without making pointer-driven requests.
    LaunchedEffect(cache, resourceIdentity, visibleRows, playedRows) {
        QueueArtworkThumbnailCache.updateIdentity(resourceIdentity)
        val newestHistorySongIds = playedRows.takeLast(20)
            .mapNotNull { it.songId.takeIf(String::isNotBlank) }
            .toSet()
        snapshotFlow {
            val layout = listState.layoutInfo
            val visibleIndices = layout.visibleItemsInfo.map { it.index }
            val visibleHistoryIndices = visibleIndices.filter { it in 0 until playedRows.size }
            val firstHistoryIndex = visibleHistoryIndices.minOrNull()
            val lastHistoryIndex = visibleHistoryIndices.maxOrNull()
            val historyStart = if (firstHistoryIndex == null) {
                (playedRows.size - 20).coerceAtLeast(0)
            } else {
                (firstHistoryIndex - 20).coerceAtLeast(0)
            }
            val historyEnd = if (playedRows.isEmpty()) 0 else
                ((lastHistoryIndex ?: playedRows.lastIndex) + 21).coerceAtMost(playedRows.size)
            val nearbyHistorySongIds = if (historyStart < historyEnd) {
                playedRows.subList(historyStart, historyEnd)
                    .mapNotNull { it.songId.takeIf(String::isNotBlank) }
            } else {
                emptyList()
            }
            val lastVisibleIndex = visibleIndices.maxOrNull() ?: playedRows.size
            val lastVisibleQueueIndex = (lastVisibleIndex - playedRows.size).coerceAtLeast(0)
            val queueEndExclusive = QueuePrefetchWindow.endExclusive(
                lastVisibleIndex = lastVisibleQueueIndex,
                totalItems = visibleRows.size,
            )
            (
                newestHistorySongIds +
                    nearbyHistorySongIds +
                    visibleRows.take(queueEndExclusive).mapNotNull { it.songId.takeIf(String::isNotBlank) }
            ).toSet()
        }.distinctUntilChanged().collect { songIds ->
            cache.updateScope(resourceIdentity, songIds)
            songIds.forEach(cache::load)
        }
    }

    // Decode queue thumbnails once in the background. The shared LRU keeps visible/recent images
    // ready as LazyColumn recycles rows and retains them when the Queue destination is reopened.
    LaunchedEffect(cache, resourceIdentity) {
        QueueArtworkThumbnailCache.updateIdentity(resourceIdentity)
        val warmedSongIds = mutableSetOf<String>()
        cache.state.collect { entries ->
            entries.forEach { (songId, entry) ->
                val bytes = entry.artworkBytes
                if (entry.identity == resourceIdentity &&
                    entry.status == QueueSongLoadStatus.READY &&
                    entry.artworkStatus == QueueArtworkStatus.AVAILABLE &&
                    bytes != null &&
                    warmedSongIds.add(songId)
                ) {
                    QueueArtworkThumbnailCache.preloadPrefetchOnce(
                        QueueArtworkThumbnailKey(resourceIdentity, songId),
                        bytes,
                    )
                }
            }
        }
    }

    // Match Web's rule: fresh snapshot Played is reused; otherwise make one fallback read per
    // session/connection freshness transition. Read results never overwrite the canonical snapshot.
    LaunchedEffect(
        runtime,
        queueProjection.snapshotStatus,
        queueProjection.epoch,
        snapshotState.connectionGeneration,
        snapshotState.controlsEligible,
    ) {
        val requestGeneration = playedReadRequestGeneration + 1L
        playedReadRequestGeneration = requestGeneration
        if (canonicalProjection.snapshotStatus == QueueSnapshotStatus.READY) {
            playedFallback = null
            playedReadStatus = PlayedReadStatus.CANONICAL
            return@LaunchedEffect
        }
        if (snapshotState.snapshot == null) {
            playedFallback = null
            playedReadStatus = PlayedReadStatus.WAITING
            return@LaunchedEffect
        }

        playedFallback = null
        playedReadStatus = PlayedReadStatus.LOADING
        val readGeneration = PlayedReadGeneration(
            epoch = queueProjection.epoch,
            queueRevision = queueProjection.queueRevision,
            connectionGeneration = snapshotState.connectionGeneration,
            explicitStopGeneration = snapshotState.snapshot?.explicitStopGeneration,
            originAddress = currentOrigin.address,
            requestGeneration = requestGeneration,
        )
        try {
            val result = (playedReader ?: { runtime.reads.getPlayed() }).invoke()
            currentCoroutineContext().ensureActive()
            val now = snapshotProvider()
            val latestProjection = QueuePresentation.deriveQueue(now)
            val sameReadScope = readGeneration.stillMatches(
                currentEpoch = latestProjection.epoch,
                currentQueueRevision = latestProjection.queueRevision,
                currentConnectionGeneration = now.connectionGeneration,
                currentExplicitStopGeneration = now.snapshot?.explicitStopGeneration,
                currentOriginAddress = runtime.realtimeController.currentOrigin().address,
                currentRequestGeneration = playedReadRequestGeneration,
                currentSnapshotReady = latestProjection.snapshotStatus == QueueSnapshotStatus.READY,
            )
            if (!sameReadScope) {
                // Keep the one-read-per-freshness-transition boundary; stale results wait for a
                // later connection/freshness transition instead of triggering a duplicate GET.
                playedReadStatus = PlayedReadStatus.WAITING
                return@LaunchedEffect
            }
            when (result) {
                is ApiResult.Success -> {
                    playedFallback = result.value.items.filter { it.position < 0 }.sortedByDescending { it.position }
                    playedReadStatus = PlayedReadStatus.FALLBACK
                }
                else -> playedReadStatus = PlayedReadStatus.FAILED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            playedReadStatus = PlayedReadStatus.FAILED
        }
    }

    LaunchedEffect(
        queueProjection.epoch,
        queueProjection.queueRevision,
        queueProjection.current?.queueItemId,
        visibleRows.map { it.queueItemId },
        playedRows.size,
        listViewportHeightPx,
        bottomScrollBufferHeightPx,
    ) {
        val targetIndex = if (hasQueueAnchor) playedRows.size else 0
        // Wait for real list rows and, when short Queue content needs it, a measured transparent
        // footer. Without enough scroll range LazyColumn clamps current back to index 0.
        if (hasQueueAnchor) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }
                .first { totalItems -> totalItems > targetIndex }
            if (bottomScrollBufferHeightPx > 0) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "queue-viewport-scroll-buffer" }?.size ?: 0
                }.first { height -> height >= bottomScrollBufferHeightPx }
            }
        }
        listState.scrollToItem(
            index = targetIndex,
            scrollOffset = queueAnchorScrollOffsetPx,
        )
    }
    LaunchedEffect(
        selectedMenuItem?.queueItemId,
        queueProjection.visibleRows.map { it.queueItemId },
        playedRows.map { it.queueItemId },
    ) {
        val selectedId = selectedMenuItem?.queueItemId ?: return@LaunchedEffect
        if (queueProjection.visibleRows.none { it.queueItemId == selectedId } &&
            playedRows.none { it.queueItemId == selectedId }
        ) {
            selectedMenuItem = null
        }
    }

    // Keep the save dialog open until the shared mutation has a confirmed receipt/readback and
    // Web's existing GET /api/playlists read succeeds. UNKNOWN waits for an explicit shared retry.
    LaunchedEffect(pendingSaveIntentId) {
        val intentId = pendingSaveIntentId ?: return@LaunchedEffect
        val activity = runtime.mutations.activities.first { entries ->
            entries.firstOrNull { entry ->
                entry.intentId == intentId && (
                    entry.status in setOf(MutationStatus.SUCCESS, MutationStatus.FAILED) ||
                        (entry.status == MutationStatus.UNKNOWN &&
                            entry.origin != runtime.realtimeController.currentOrigin())
                )
            } != null
        }.firstOrNull { it.intentId == intentId } ?: return@LaunchedEffect
        when {
            activity.status == MutationStatus.FAILED -> {
                saveReadbackError = activity.message ?: "保存失败，请检查服务器反馈"
                saveAlreadyConfirmed = false
                saveReadbackOnlyRetry = false
                pendingSaveIntentId = null
            }
            activity.status == MutationStatus.UNKNOWN -> {
                // The outcome may still have committed on the previous origin. Do not let the
                // user repeat the save against either origin; the original server is no longer active.
                saveReadbackError = "服务器地址已变化；原服务器的保存结果未知。为避免重复提交，当前表单已锁定。"
                saveAlreadyConfirmed = true
                saveReadbackOnlyRetry = false
                pendingSaveIntentId = null
            }
            activity.syncError != null -> {
                saveReadbackError = "写入已确认，但权威状态同步失败：${activity.syncError}"
                saveAlreadyConfirmed = true
                saveReadbackOnlyRetry = true
                pendingSaveIntentId = null
            }
            activity.origin != runtime.realtimeController.currentOrigin() -> {
                saveReadbackError = "服务器地址已变化；原服务器的保存结果未能确认，请勿重复提交"
                saveAlreadyConfirmed = true
                saveReadbackOnlyRetry = false
                pendingSaveIntentId = null
            }
            else -> {
                // The write receipt already exists. Lock the form while the independent
                // playlist-list readback runs, so another tap cannot issue a second POST.
                saveAlreadyConfirmed = true
                saveReadbackOnlyRetry = false
                when (runtime.reads.getPlaylists()) {
                    is ApiResult.Success -> {
                        if (activity.origin == runtime.realtimeController.currentOrigin()) {
                            saveReadbackError = null
                            saveAlreadyConfirmed = false
                            saveReadbackOnlyRetry = false
                            pendingSaveIntentId = null
                            toolDialog = null
                        } else {
                            saveReadbackError = "服务器地址已变化；原服务器的保存结果未能确认，请勿重复提交"
                            saveAlreadyConfirmed = true
                            saveReadbackOnlyRetry = false
                            pendingSaveIntentId = null
                        }
                    }
                    else -> {
                        saveReadbackError = "保存请求已确认，但读取歌单列表失败。重试将只读取权威状态，不会再次提交保存。"
                        saveAlreadyConfirmed = true
                        saveReadbackOnlyRetry = true
                        pendingSaveIntentId = null
                    }
                }
            }
        }
    }

    var showOutputDetails by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().testTag("queue-screen-root")) {
        PlayerArtworkBackdrop(
            blurredArtwork = blurredArtworkBitmap,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).testTag("queue-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FullScreenExitGestureBand(
                onExit = onExit,
                modifier = Modifier.testTag("full-screen-exit-gesture-band"),
                onDragDelta = onDismissDragDelta,
                onGestureEnd = onDismissGestureEnd,
                onGestureCancel = onDismissGestureCancel,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "剩余播放时间：--:--",
                        modifier = Modifier.weight(1f).testTag("queue-remaining-time"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TextButton(
                            enabled = actions.isEnabled,
                            onClick = { toolDialog = QueueToolDialog.CLEAR },
                            modifier = Modifier.testTag("queue-clear-button"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            QueueToolbarActionIcon(kind = QueueToolbarActionKind.CLEAR, tag = "queue-clear-icon")
                            Spacer(Modifier.width(4.dp))
                            Text("清空待播", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1)
                        }
                        TextButton(
                            enabled = actions.isEnabled,
                            onClick = { toolDialog = QueueToolDialog.SAVE_PLAYLIST },
                            modifier = Modifier.testTag("queue-save-button"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        ) {
                            QueueToolbarActionIcon(kind = QueueToolbarActionKind.SAVE_PLAYLIST, tag = "queue-save-icon")
                            Spacer(Modifier.width(4.dp))
                            Text("保存歌单", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp), maxLines = 1)
                        }
                    }
                }
            }

            when {
                queueProjection.snapshotStatus == QueueSnapshotStatus.NO_SNAPSHOT -> {
                    QueueStatusMessage(
                        text = "正在等待服务器队列状态…",
                        tag = "queue-state-loading",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                queueProjection.snapshotStatus == QueueSnapshotStatus.INVALID -> {
                    QueueStatusMessage(
                        text = "队列数据不可读取",
                        tag = "queue-state-error",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                else -> {
                    if (queueProjection.snapshotStatus == QueueSnapshotStatus.STALE) {
                        Text(
                            text = "服务器状态未确认；当前行不代表已确认播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                                .testTag("queue-stale-state"),
                        )
                    }
                    activities.lastOrNull { it.intentId.startsWith("queue:") }?.let { activity ->
                        val feedback = when {
                            activity.syncError != null -> "写入已确认，状态同步失败：${activity.syncError}"
                            activity.status == MutationStatus.PENDING -> activity.message ?: "队列操作处理中…"
                            activity.status == MutationStatus.UNKNOWN -> "操作结果未知：${activity.message ?: "请先确认服务器状态"}"
                            activity.status == MutationStatus.FAILED -> "操作失败：${activity.message ?: "服务器拒绝请求"}"
                            activity.status == MutationStatus.SUCCESS -> activity.message ?: "队列操作已确认"
                            else -> null
                        }
                        feedback?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activity.status == MutationStatus.FAILED || activity.status == MutationStatus.UNKNOWN || activity.syncError != null)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp)
                                    .testTag("queue-operation-feedback"),
                            )
                        }
                    }
                    when (playedReadStatus) {
                        PlayedReadStatus.LOADING -> Text(
                            text = "正在读取已播放记录…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)
                                .testTag("queue-played-loading"),
                        )
                        PlayedReadStatus.FAILED -> Text(
                            text = "已播放记录读取失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)
                                .testTag("queue-played-error"),
                        )
                        else -> Unit
                    }
                    if (visibleRows.isEmpty() && playedRows.isEmpty()) {
                        QueueStatusMessage(
                            text = "队列为空",
                            tag = "queue-empty",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("queue-list"),
                                contentPadding = PaddingValues(top = queueListTopPadding, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                            itemsIndexed(
                                items = playedRows,
                                key = { _, occurrence -> "played-history-${occurrence.queueItemId}" },
                                contentType = { _, _ -> "played-history" },
                            ) { historyIndex, occurrence ->
                                val entryFlow = remember(cache, resourceIdentity, occurrence.songId) {
                                    cache.state.map { entries ->
                                        entries[occurrence.songId]?.takeIf { it.identity == resourceIdentity }
                                    }.distinctUntilChanged()
                                }
                                val entry by entryFlow.collectAsStateWithLifecycle(
                                    initialValue = cache.currentEntry(occurrence.songId, resourceIdentity),
                                )
                                val menuTitle = entry?.song?.title?.takeIf(String::isNotBlank) ?: occurrence.songId
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag("queue-history-row-${occurrence.queueItemId}"),
                                ) {
                                    QueueOccurrenceRow(
                                        occurrence = occurrence,
                                        isCurrent = false,
                                        currentLabel = "",
                                        cacheEntry = entry,
                                        menuEnabled = actions.canShowMenu(occurrence.queueItemId),
                                        menuTitle = menuTitle,
                                        menuExpanded = selectedMenuItem?.queueItemId == occurrence.queueItemId,
                                        onPlay = { actions.play(occurrence.queueItemId, menuTitle) },
                                        onMenu = { selectedMenuItem = occurrence },
                                        onDismissMenu = { selectedMenuItem = null },
                                        onMoveNext = {
                                            actions.move(occurrence.queueItemId, QueueMove.NEXT, menuTitle)
                                            selectedMenuItem = null
                                        },
                                        onMoveToEnd = {
                                            actions.move(occurrence.queueItemId, QueueMove.ADD, menuTitle)
                                            selectedMenuItem = null
                                        },
                                        onRemove = {
                                            actions.remove(occurrence.queueItemId, menuTitle)
                                            selectedMenuItem = null
                                        },
                                    )
                                    if (historyIndex < playedRows.lastIndex || visibleRows.isNotEmpty()) {
                                        HorizontalDivider(
                                            modifier = Modifier.fillMaxWidth()
                                                .testTag("queue-history-divider-${occurrence.queueItemId}"),
                                            thickness = 0.75.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
                                        )
                                    }
                                }
                            }
                            itemsIndexed(
                                items = visibleRows,
                                key = { _, item -> item.queueItemId },
                                contentType = { _, _ -> "queue-occurrence" },
                            ) { rowIndex, occurrence ->
                                val entryFlow = remember(cache, resourceIdentity, occurrence.songId) {
                                    cache.state.map { entries ->
                                        entries[occurrence.songId]?.takeIf { it.identity == resourceIdentity }
                                    }.distinctUntilChanged()
                                }
                                val entry by entryFlow.collectAsStateWithLifecycle(
                                    initialValue = cache.currentEntry(occurrence.songId, resourceIdentity),
                                )
                                val song = entry?.song
                                val menuTitle = song?.title?.takeIf(String::isNotBlank) ?: occurrence.songId
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    QueueOccurrenceRow(
                                        occurrence = occurrence,
                                        isCurrent = occurrence.position == 0,
                                        currentLabel = queueProjection.currentLabel,
                                        cacheEntry = entry,
                                        menuEnabled = actions.canShowMenu(occurrence.queueItemId),
                                        menuTitle = menuTitle,
                                        menuExpanded = selectedMenuItem?.queueItemId == occurrence.queueItemId,
                                        onPlay = { actions.play(occurrence.queueItemId, menuTitle) },
                                        onMenu = { selectedMenuItem = occurrence },
                                        onDismissMenu = { selectedMenuItem = null },
                                        onMoveNext = {
                                            actions.move(occurrence.queueItemId, QueueMove.NEXT, menuTitle)
                                            selectedMenuItem = null
                                        },
                                        onMoveToEnd = {
                                            actions.move(occurrence.queueItemId, QueueMove.ADD, menuTitle)
                                            selectedMenuItem = null
                                        },
                                        onRemove = {
                                            actions.remove(occurrence.queueItemId, menuTitle)
                                            selectedMenuItem = null
                                        },
                                        reorderEnabled = occurrence.position > 0 && actions.isEnabled &&
                                            reorderBaseline != null && visiblePendingIds.size > 1,
                                        isDragSource = activeReorderDraft?.draggedQueueItemId == occurrence.queueItemId,
                                        isDropTarget = activeReorderDraft?.changed == true && (
                                            activeReorderDraft.orderedVisiblePendingIds.getOrNull(
                                                activeReorderDraft.orderedVisiblePendingIds.indexOf(activeReorderDraft.draggedQueueItemId) + 1,
                                            ) == occurrence.queueItemId ||
                                                (activeReorderDraft.orderedVisiblePendingIds.lastOrNull() == occurrence.queueItemId &&
                                                    activeReorderDraft.orderedVisiblePendingIds.indexOf(activeReorderDraft.draggedQueueItemId) ==
                                                    activeReorderDraft.orderedVisiblePendingIds.lastIndex)
                                            ),
                                        dropAtEnd = activeReorderDraft?.changed == true &&
                                            activeReorderDraft.orderedVisiblePendingIds.lastOrNull() == occurrence.queueItemId &&
                                            activeReorderDraft.orderedVisiblePendingIds.indexOf(activeReorderDraft.draggedQueueItemId) ==
                                            activeReorderDraft.orderedVisiblePendingIds.lastIndex,
                                        onReorderStart = { startReorder(occurrence.queueItemId) },
                                        onReorderDelta = ::updateReorder,
                                        onReorderEnd = ::finishReorder,
                                        onReorderCancel = ::cancelReorder,
                                    )
                                    if (rowIndex < visibleRows.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                                .testTag("queue-row-divider-${occurrence.queueItemId}"),
                                            thickness = 0.75.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
                                        )
                                    }
                                }
                            }
                                item(
                                    key = "queue-viewport-scroll-buffer",
                                    contentType = "queue-scroll-buffer",
                                ) {
                                    Spacer(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(with(density) { bottomScrollBufferHeightPx.toDp() })
                                            .testTag("queue-viewport-scroll-buffer"),
                                    )
                                }
                            }

                            val draggedOccurrence = activeReorderDraft?.let { draft ->
                                visibleRows.firstOrNull { it.queueItemId == draft.draggedQueueItemId }
                            }
                            val ghostTop = dragSourceTop
                            val ghostHeightPx = dragSourceHeight
                            if (draggedOccurrence != null && ghostTop != null && ghostHeightPx != null) {
                                val density = LocalDensity.current
                                QueueOccurrenceDragGhost(
                                    occurrence = draggedOccurrence,
                                    cacheEntry = draggedEntry,
                                    rowHeight = with(density) { ghostHeightPx.toDp() },
                                    modifier = Modifier
                                        .offset {
                                            IntOffset(
                                                dragOffsetX.roundToInt(),
                                                (ghostTop + dragOffsetY).roundToInt(),
                                            )
                                        }
                                        .zIndex(4f),
                                )
                            }
                            if (showHistoryJumpButton) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val targetIndex = playedRows.size
                                            if (targetIndex < listState.layoutInfo.totalItemsCount) {
                                                listState.animateScrollToItem(
                                                    index = targetIndex,
                                                    scrollOffset = queueAnchorScrollOffsetPx,
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                        .zIndex(5f)
                                        .testTag("queue-history-jump-button"),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        text = "跳转至待播放项目",
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FullScreenBottomNavigation(
                selectedDestination = FullScreenBottomNavigation.Destination.QUEUE,
                onOutputClick = { showOutputDetails = true },
                onNowPlayingClick = onOpenPlayer,
                onQueueClick = {},
            )
        }
    }
    if (showOutputDetails) {
        OutputDetailsDialog(presentation = presentation, onDismiss = { showOutputDetails = false })
    }
    QueueToolsDialog(
        dialog = toolDialog,
        enabled = actions.isEnabled,
        onDismiss = { toolDialog = null },
        onClear = { actions.clear(); toolDialog = null },
        onSave = { name ->
            if (!saveAlreadyConfirmed) {
                saveReadbackError = null
                saveReadbackOnlyRetry = false
                if (actions.save(name) == io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome.Submitted) {
                    pendingSaveIntentId = actions.lastSubmittedIntentId
                }
            }
        },
        saveFeedback = saveReadbackError,
        saveAlreadyConfirmed = saveAlreadyConfirmed,
        saveReadbackOnlyRetry = saveReadbackOnlyRetry,
        onRetrySaveReadback = {
            scope.launch {
                val originBefore = runtime.realtimeController.currentOrigin()
                // Prevent concurrent taps from starting overlapping readback retries.
                saveReadbackOnlyRetry = false
                saveReadbackError = null
                val snapshotResult = runtime.refreshSnapshot()
                if (originBefore != runtime.realtimeController.currentOrigin()) {
                    saveReadbackError = "服务器地址已变化；原服务器的保存结果未能确认，请勿重复提交"
                    saveReadbackOnlyRetry = false
                } else if (snapshotResult !is io.github.qyxx2.mpdserver.remote.runtime.StateReadResult.Accepted) {
                    saveReadbackError = "权威队列状态读取仍失败；未重新提交保存请求"
                    saveReadbackOnlyRetry = true
                } else {
                    when (runtime.reads.getPlaylists()) {
                        is ApiResult.Success -> {
                            if (originBefore == runtime.realtimeController.currentOrigin()) {
                                saveReadbackError = null
                                saveAlreadyConfirmed = false
                                saveReadbackOnlyRetry = false
                                toolDialog = null
                            } else {
                                saveReadbackError = "服务器地址已变化；原服务器的保存结果未能确认，请勿重复提交"
                                saveReadbackOnlyRetry = false
                            }
                        }
                        else -> {
                            saveReadbackError = "读取歌单列表仍失败；未重新提交保存请求"
                            saveReadbackOnlyRetry = true
                        }
                    }
                }
            }
        },
    )
}

private enum class QueueToolbarActionKind { CLEAR, SAVE_PLAYLIST }

@Composable
private fun QueueToolbarActionIcon(kind: QueueToolbarActionKind, tag: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(16.dp).testTag(tag)) {
        val stroke = 1.5.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(
                color = tint,
                start = Offset(size.width * x1, size.height * y1),
                end = Offset(size.width * x2, size.height * y2),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        when (kind) {
            QueueToolbarActionKind.CLEAR -> {
                line(0.20f, 0.25f, 0.80f, 0.25f)
                line(0.38f, 0.15f, 0.62f, 0.15f)
                line(0.30f, 0.34f, 0.36f, 0.84f)
                line(0.70f, 0.34f, 0.64f, 0.84f)
                line(0.36f, 0.84f, 0.64f, 0.84f)
                line(0.45f, 0.38f, 0.45f, 0.73f)
                line(0.55f, 0.38f, 0.55f, 0.73f)
            }
            QueueToolbarActionKind.SAVE_PLAYLIST -> {
                line(0.22f, 0.17f, 0.22f, 0.83f)
                line(0.22f, 0.17f, 0.77f, 0.17f)
                line(0.22f, 0.83f, 0.77f, 0.83f)
                line(0.77f, 0.17f, 0.77f, 0.34f)
                line(0.38f, 0.38f, 0.65f, 0.38f)
                line(0.38f, 0.53f, 0.65f, 0.53f)
                line(0.38f, 0.68f, 0.59f, 0.68f)
            }
        }
    }
}

@Composable
private fun QueueStatusMessage(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag(tag), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
