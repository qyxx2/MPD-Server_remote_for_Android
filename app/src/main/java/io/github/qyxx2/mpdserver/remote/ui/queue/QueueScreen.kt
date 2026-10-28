package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenBottomNavigation
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenExitGestureBand
import io.github.qyxx2.mpdserver.remote.ui.player.OutputDetailsDialog
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private enum class PlayedReadStatus { WAITING, LOADING, CANONICAL, FALLBACK, FAILED }

/** Read-only Queue/Played surface. B6+ attach actions and interactive history reveal separately. */
@Composable
internal fun QueueScreen(
    runtime: AppRuntime,
    snapshotState: SnapshotStoreState,
    presentation: PlayerPresentation,
    onExit: () -> Unit,
    onOpenPlayer: () -> Unit,
    playedReader: (suspend () -> ApiResult<QueueListDto>)? = null,
    songResourceLoader: (suspend (QueueResourceIdentity, String) -> QueueSongResource)? = null,
    snapshotProvider: () -> SnapshotStoreState = { runtime.snapshots.state.value },
) {
    val scope = rememberCoroutineScope()
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
    val cache = remember(runtime, scope) {
        QueueSongCache(
            scope = scope,
            maxConcurrentRequests = 3,
            maxEntries = 48,
            loader = { identity, songId ->
                if (songResourceLoader != null) {
                    songResourceLoader(identity, songId)
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
            },
        )
    }
    val cacheEntries by cache.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val visibleRows = remember(queueProjection.current, queueProjection.next) {
        buildList {
            queueProjection.current?.let(::add)
            addAll(queueProjection.next)
        }
    }

    // snapshotFlow coalesces LazyList layout changes and emits only when the bounded visible
    // metadata window changes. It avoids feeding a newly allocated visibleItemsInfo list back
    // through Compose state on every recomposition.
    LaunchedEffect(cache, resourceIdentity, visibleRows) {
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleRows.isEmpty()) {
                emptySet()
            } else {
                val start = visibleItems.minOfOrNull { it.index } ?: 0
                val end = minOf(visibleRows.lastIndex, (visibleItems.maxOfOrNull { it.index } ?: 1) + 3)
                (start..end).mapNotNull { index ->
                    visibleRows.getOrNull(index)?.songId?.takeIf(String::isNotBlank)
                }.toSet()
            }
        }.collect { songIds ->
            cache.updateScope(resourceIdentity, songIds)
            songIds.forEach(cache::load)
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

    var showOutputDetails by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().testTag("queue-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FullScreenExitGestureBand(
            onExit = onExit,
            modifier = Modifier.testTag("full-screen-exit-gesture-band"),
        ) {
            Text(
                text = "队列",
                modifier = Modifier.padding(start = 24.dp).align(Alignment.CenterStart),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
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
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            items = visibleRows,
                            key = { it.queueItemId },
                            contentType = { "queue-occurrence" },
                        ) { occurrence ->
                            val entry = cacheEntries[occurrence.songId]
                                ?.takeIf { it.identity == resourceIdentity }
                            QueueOccurrenceRow(
                                occurrence = occurrence,
                                isCurrent = occurrence.position == 0,
                                currentLabel = queueProjection.currentLabel,
                                cacheEntry = entry,
                            )
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
    if (showOutputDetails) {
        OutputDetailsDialog(presentation = presentation, onDismiss = { showOutputDetails = false })
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
