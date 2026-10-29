package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    val canonicalProjection = remember(snapshotState) { QueuePresentation.deriveQueue(snapshotState) }
    var playedFallback by remember { mutableStateOf<List<QueueItemDto>?>(null) }
    var playedReadStatus by remember { mutableStateOf(PlayedReadStatus.WAITING) }
    val queueProjection = remember(snapshotState, playedFallback) {
        QueuePresentation.deriveQueue(snapshotState, playedFallback)
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
    val listState = rememberLazyListState()
    val visibleRows = remember(queueProjection.current, queueProjection.next) {
        buildList {
            queueProjection.current?.let(::add)
            addAll(queueProjection.next)
        }
    }
    val reorderBaseline = remember(snapshotState) { QueueReorderBaseline.from(snapshotState) }
    val visiblePendingIds = remember(visibleRows) { visibleRows.filter { it.position > 0 }.map { it.queueItemId } }
    var reorderDraft by remember(runtime) { mutableStateOf<QueueReorderDraft?>(null) }
    var dragPointerY by remember(runtime) { mutableStateOf<Float?>(null) }
    val activeReorderDraft = reorderDraft?.takeIf {
        it.isCompatible(reorderBaseline, actions.isEnabled, visiblePendingIds)
    }
    val renderedRows = remember(visibleRows, activeReorderDraft?.orderedVisiblePendingIds) {
        val byId = visibleRows.filter { it.position > 0 }.associateBy { it.queueItemId }
        val currentRows = visibleRows.filter { it.position <= 0 }
        val order = activeReorderDraft?.orderedVisiblePendingIds ?: visiblePendingIds
        buildList {
            addAll(currentRows)
            order.mapNotNullTo(this, byId::get)
        }
    }
    LaunchedEffect(reorderDraft, reorderBaseline, actions.isEnabled, visiblePendingIds) {
        if (reorderDraft != null && activeReorderDraft == null) {
            reorderDraft = null
            dragPointerY = null
        }
    }

    fun startReorder(queueItemId: String) {
        if (!actions.isEnabled || reorderBaseline == null || visiblePendingIds.size < 2) return
        val latestState = snapshotProvider()
        val latestBaseline = QueueReorderBaseline.from(latestState) ?: return
        if (latestBaseline != reorderBaseline) return
        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == queueItemId } ?: return
        val draft = QueueReorderDraft.begin(latestBaseline, queueItemId, visiblePendingIds) ?: return
        dragPointerY = itemInfo.offset + itemInfo.size / 2f
        reorderDraft = draft
    }

    fun updateReorder(deltaY: Float) {
        val draft = reorderDraft ?: return
        if (!draft.isCompatible(reorderBaseline, actions.isEnabled, visiblePendingIds)) {
            reorderDraft = null
            dragPointerY = null
            return
        }
        val pointerY = (dragPointerY ?: return) + deltaY
        dragPointerY = pointerY
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
        reorderDraft = null
        dragPointerY = null
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
        reorderDraft = null
        dragPointerY = null
    }

    // Prefetch the first 100 queue resources immediately, then extend the cache window when
    // the user approaches its end. This is client-side staging over the canonical snapshot, not
    // server pagination. Requests stay bounded by QueueSongCache's semaphore.
    LaunchedEffect(cache, resourceIdentity, visibleRows) {
        QueueArtworkThumbnailCache.updateIdentity(resourceIdentity)
        snapshotFlow {
            QueuePrefetchWindow.endExclusive(
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0,
                totalItems = visibleRows.size,
            )
        }.collect { endExclusive ->
            val songIds = visibleRows.take(endExclusive)
                .mapNotNull { it.songId.takeIf(String::isNotBlank) }
                .toSet()
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
        try {
            val result = (playedReader ?: { runtime.reads.getPlayed() }).invoke()
            currentCoroutineContext().ensureActive()
            val now = snapshotProvider()
            val sameReadScope = now.connectionGeneration == resourceIdentity.connectionGeneration &&
                now.snapshot?.epoch == resourceIdentity.epoch &&
                runtime.realtimeController.currentOrigin().address == resourceIdentity.originAddress &&
                QueuePresentation.deriveQueue(now).snapshotStatus != QueueSnapshotStatus.READY
            if (!sameReadScope) return@LaunchedEffect
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

    LaunchedEffect(queueProjection.epoch, queueProjection.queueRevision, queueProjection.current?.queueItemId) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(selectedMenuItem?.queueItemId, queueProjection.visibleRows.map { it.queueItemId }) {
        val selectedId = selectedMenuItem?.queueItemId ?: return@LaunchedEffect
        if (queueProjection.visibleRows.none { it.queueItemId == selectedId }) {
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
                    if (visibleRows.isEmpty()) {
                        QueueStatusMessage(
                            text = "队列为空",
                            tag = "queue-empty",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("queue-list"),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(
                                items = renderedRows,
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
