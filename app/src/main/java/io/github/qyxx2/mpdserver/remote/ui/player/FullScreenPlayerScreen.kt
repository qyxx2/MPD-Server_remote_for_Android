package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime

/**
 * Native presentation of the same app-scoped canonical snapshot as Web. This screen never creates
 * a state/network/mutation owner; playback actions intentionally remain disabled until Batch 3.
 */
@Composable
fun FullScreenPlayerScreen(
    runtime: AppRuntime,
    presentation: PlayerPresentation,
    onExit: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onEntityNavigationRequest: (PlayerEntityNavigationRequest) -> Unit = {},
    onDismissDragDelta: (Float) -> Unit = {},
    onDismissGestureEnd: (Float) -> Unit = { distance -> if (distance >= 120f) onExit() },
    onDismissGestureCancel: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(),
    artworkCacheKey: PlayerArtworkCacheKey? = null,
    artworkCache: PlayerArtworkMemoryCache? = null,
) {
    val currentOnDismissDragDelta = rememberUpdatedState(onDismissDragDelta)
    val currentOnDismissGestureEnd = rememberUpdatedState(onDismissGestureEnd)
    val currentOnDismissGestureCancel = rememberUpdatedState(onDismissGestureCancel)
    val song = presentation.song
    var showOutputDetails by remember { mutableStateOf(false) }
    // Never let a previously cached track survive a canonical identity/connection change.
    // Subscribe only inside this screen so background preload state changes never tear down or
    // replace the root NavHost before a delayed mini-player open completes.
    val artworkCacheState = artworkCache?.state?.collectAsStateWithLifecycle()?.value
        ?: PlayerArtworkCacheState.Empty
    val currentArtworkKey = artworkCacheKey?.takeIf { it.matches(presentation) }
    val currentArtworkState = artworkCacheState.takeIf {
        currentArtworkKey != null && it.key == currentArtworkKey
    }
    val artworkBitmap = (currentArtworkState as? PlayerArtworkCacheState.Ready)?.foreground
    val blurredArtworkBitmap = (currentArtworkState as? PlayerArtworkCacheState.Ready)?.blurred
    val artworkLoading = currentArtworkKey != null &&
        currentArtworkState !is PlayerArtworkCacheState.Ready &&
        currentArtworkState !is PlayerArtworkCacheState.Failed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("full-screen-player-root"),
    ) {
        PlayerArtworkBackdrop(
            blurredArtwork = blurredArtworkBitmap,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("full-screen-player-media-region"),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .fullScreenPlayerDismissGesture(
                            dragDelta = currentOnDismissDragDelta,
                            gestureEnd = currentOnDismissGestureEnd,
                            gestureCancel = currentOnDismissGestureCancel,
                        )
                        .testTag("full-screen-exit-gesture-band"),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val placeholder = when {
                            presentation.playbackStatus == ConfirmedPlaybackStatus.UNKNOWN ->
                                "当前曲目未确认"
                            presentation.artworkReferenceStatus == ArtworkReferenceStatus.MISSING -> "无封面"
                            presentation.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED && artworkLoading ->
                                "正在读取封面"
                            presentation.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED && artworkBitmap == null ->
                                "封面暂不可用"
                            else -> "无封面"
                        }
                        ArtworkStage(
                            bitmap = artworkBitmap,
                            placeholderLabel = placeholder,
                            modifier = Modifier
                                .fillMaxSize()
                                .align(Alignment.Center),
                            dismissGestureModifier = Modifier.fullScreenPlayerDismissGesture(
                                dragDelta = currentOnDismissDragDelta,
                                gestureEnd = currentOnDismissGestureEnd,
                                gestureCancel = currentOnDismissGestureCancel,
                            ),
                        )
                    }

                    PlayerSongIdentity(
                        presentation = presentation,
                        onArtistClick = onArtistClick,
                        onEntityNavigationRequest = onEntityNavigationRequest,
                    )
                }
            }

            PlayerProgressPlaceholder(modifier = Modifier.padding(horizontal = 24.dp))
            SourceFormatLabel(
                presentation = presentation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 2.dp),
            )
            DisabledPlaybackControls(presentation = presentation)
            FullScreenBottomNavigation(
                selectedDestination = FullScreenBottomNavigation.Destination.NOW_PLAYING,
                onOutputClick = { showOutputDetails = true },
                onNowPlayingClick = onOpenPlayer,
                onQueueClick = onOpenQueue,
            )
        }

        if (showOutputDetails) {
            OutputDetailsDialog(
                presentation = presentation,
                onDismiss = { showOutputDetails = false },
            )
        }
    }
}

private fun Modifier.fullScreenPlayerDismissGesture(
    dragDelta: State<(Float) -> Unit>,
    gestureEnd: State<(Float) -> Unit>,
    gestureCancel: State<() -> Unit>,
): Modifier = pointerInput(Unit) {
    var totalVerticalDistance = 0f
    detectVerticalDragGestures(
        onDragStart = { totalVerticalDistance = 0f },
        onVerticalDrag = { change, dragAmount ->
            totalVerticalDistance += dragAmount
            dragDelta.value(dragAmount)
            change.consume()
        },
        onDragEnd = { gestureEnd.value(totalVerticalDistance) },
        onDragCancel = { gestureCancel.value() },
    )
}

@Composable
private fun PlayerSongIdentity(
    presentation: PlayerPresentation,
    onArtistClick: (String) -> Unit,
    onEntityNavigationRequest: (PlayerEntityNavigationRequest) -> Unit,
) {
    val song = presentation.song
    if (song == null) {
        val message = when (presentation.songStatus) {
            SongMetadataStatus.NOT_CONFIRMED, SongMetadataStatus.OCCURRENCE_MISMATCH ->
                "当前曲目状态未确认"
            SongMetadataStatus.MISSING -> "当前曲目元数据不可用"
            SongMetadataStatus.INVALID -> "当前曲目元数据无效"
            SongMetadataStatus.AVAILABLE -> "当前曲目未确认"
        }
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp)
                .testTag("player-song-unconfirmed"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        return
    }

    Text(
        text = song.title?.takeIf { it.isNotBlank() } ?: "曲名未知",
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("player-song-title"),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
    )

    if (song.artists.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            song.artists.forEachIndexed { index, artist ->
                if (index > 0) {
                    Text(
                        text = "/",
                        modifier = Modifier.padding(horizontal = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = artist,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = artist.isNotBlank()) { onArtistClick(artist) }
                        .padding(horizontal = 4.dp, vertical = 12.dp)
                        .semantics { contentDescription = "查看艺术家 $artist" }
                        .testTag("player-artist-$index"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        Text(
            text = "艺术家未知",
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }

    val album = song.album
    val albumId = song.albumId
    if (!album.isNullOrBlank() && !albumId.isNullOrBlank()) {
        Text(
            text = album,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable {
                    onEntityNavigationRequest(
                        PlayerEntityNavigationRequest(
                            type = PlayerEntityType.ALBUM,
                            id = albumId,
                            displayName = album,
                        ),
                    )
                }
                .padding(horizontal = 8.dp, vertical = 12.dp)
                .semantics { contentDescription = "查看专辑 $album" }
                .testTag("player-album-link"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else if (!album.isNullOrBlank()) {
        Text(
            text = album,
            modifier = Modifier.heightIn(min = 40.dp).padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = "专辑未知",
            modifier = Modifier.heightIn(min = 40.dp).padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlayerProgressPlaceholder(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "--:--",
                modifier = Modifier.testTag("player-elapsed-time"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "--:--",
                modifier = Modifier.testTag("player-duration-time"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
                .testTag("player-progress-track"),
        )
    }
}

@Composable
private fun SourceFormatLabel(
    presentation: PlayerPresentation,
    modifier: Modifier = Modifier,
) {
    val format = presentation.sourceFormat
    val parts = buildList {
        format.codec?.takeIf { it.isNotBlank() }?.let(::add)
        format.bitDepth?.takeIf { it > 0 }?.let { add("$it bit") }
        format.sampleRateHz?.takeIf { it > 0 }?.let { rate ->
            if (rate % 1000 == 0) add(rate.div(1000).toString() + " kHz")
            else add(String.format(java.util.Locale.ROOT, "%.1f kHz", rate / 1000.0))
        }
        format.channelCount?.takeIf { it > 0 }?.let { add("$it ch") }
    }
    Text(
        text = if (parts.isEmpty()) "源文件格式未知" else parts.joinToString(" · "),
        modifier = modifier
            .heightIn(min = 24.dp)
            .testTag("player-source-format"),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DisabledPlaybackControls(presentation: PlayerPresentation) {
    val playGlyph = when (presentation.playbackStatus) {
        ConfirmedPlaybackStatus.PAUSED, ConfirmedPlaybackStatus.UNKNOWN -> PlayerGlyphKind.PLAY
        ConfirmedPlaybackStatus.PLAYING -> PlayerGlyphKind.PAUSE
    }
    val actionLabel = when (presentation.playbackStatus) {
        ConfirmedPlaybackStatus.PAUSED -> "继续播放"
        ConfirmedPlaybackStatus.PLAYING -> "暂停播放"
        ConfirmedPlaybackStatus.UNKNOWN -> "播放状态未确认"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .padding(horizontal = 28.dp)
            .testTag("player-playback-controls"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = false,
            onClick = {},
            modifier = Modifier
                .size(72.dp)
                .semantics { contentDescription = "上一首" }
                .testTag("player-previous"),
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.PREVIOUS,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(width = 44.dp, height = 36.dp),
            )
        }
        IconButton(
            enabled = false,
            onClick = {},
            modifier = Modifier.size(72.dp).testTag("player-play-pause").then(
                Modifier.semantics { contentDescription = actionLabel },
            ),
        ) {
            PlayerGlyph(
                kind = playGlyph,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(48.dp),
            )
        }
        IconButton(
            enabled = false,
            onClick = {},
            modifier = Modifier
                .size(72.dp)
                .semantics { contentDescription = "下一首" }
                .testTag("player-next"),
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.NEXT,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(width = 44.dp, height = 36.dp),
            )
        }
    }
}

@Composable
internal fun OutputDetailsDialog(
    presentation: PlayerPresentation,
    onDismiss: () -> Unit,
) {
    val output = presentation.output
    val observation = when (output.observationStatus) {
        OutputObservationStatus.FRESH -> "观察新鲜"
        OutputObservationStatus.STALE -> "观察已过期"
        OutputObservationStatus.UNKNOWN -> "观察状态未知"
        OutputObservationStatus.ERROR -> "观察读取出错"
    }
    val status = when (output.dacStatus) {
        DacOutputStatus.ACTIVE -> "已启用"
        DacOutputStatus.INACTIVE -> "未启用"
        DacOutputStatus.UNAVAILABLE -> "不可用"
        DacOutputStatus.STALE -> "状态已过期"
        DacOutputStatus.UNKNOWN -> "状态未知"
        DacOutputStatus.ERROR -> "状态错误"
    }
    val details = buildList {
        add("DAC：$status")
        add("输出观察：$observation")
        output.dacFormat?.takeIf { it.isNotBlank() }?.let { add("输出格式：$it") }
        output.dacSampleRateHz?.takeIf { it > 0 }?.let { add("输出采样率：$it Hz") }
        output.dacBitDepth?.takeIf { it > 0 }?.let { add("输出位深：$it bit") }
        output.dacChannels?.takeIf { it > 0 }?.let { add("输出声道：$it") }
        output.errorMessage?.takeIf { it.isNotBlank() }?.let(::add)
        add("服务端显示为 ACTIVE 不代表已确认硬件发声或 bit-perfect。")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DAC / 输出状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details.forEach { detail ->
                    Text(text = detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        modifier = Modifier.testTag("player-output-details"),
    )
}
