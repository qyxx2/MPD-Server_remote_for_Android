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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.protocol.dto.ActualPlaybackStateDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.components.dialogSurfaceColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Native presentation of the same app-scoped canonical snapshot as Web. The bounded media handoff
 * is display-only; playback status, control capabilities and mutation targets remain canonical.
 */
@Composable
fun FullScreenPlayerScreen(
    runtime: AppRuntime,
    presentation: PlayerPresentation,
    mediaPresentation: PlayerPresentation = presentation,
    mediaHandoffActive: Boolean = false,
    mediaHandoffPositionSeconds: Double? = null,
    actions: PlayerActions? = null,
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
    val media = mediaPresentation
    val snapshotState by runtime.snapshots.state.collectAsStateWithLifecycle()
    val actionState = if (actions != null) {
        val collected by actions.state.collectAsStateWithLifecycle()
        collected
    } else {
        PlayerActionsState()
    }
    val playerClock = remember(runtime) { PlayerClock() }
    var clockPosition by remember(playerClock) { mutableStateOf<Double?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleScope = androidx.compose.runtime.rememberCoroutineScope()

    val clockSnapshot = actions?.currentSnapshot() ?: snapshotState
    LaunchedEffect(playerClock, snapshotState, actionState) {
        playerClock.update(clockSnapshot)
        clockPosition = playerClock.positionSeconds
    }
    LaunchedEffect(playerClock) {
        while (true) {
            delay(100)
            clockPosition = playerClock.positionSeconds
        }
    }
    val clockObservation = PlayerFacts.from(snapshotState).playbackObservation
    LaunchedEffect(runtime, snapshotState.controlsEligible, clockObservation?.actualState) {
        // Match the Web session: establish a timing baseline on mount/writable recovery when
        // playback is active. The shared coordinator coalesces this with any pending invalidation read.
        if (snapshotState.controlsEligible && clockObservation?.actualState == ActualPlaybackStateDto.playing) {
            try {
                runtime.refreshSnapshot()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The existing SnapshotStore/StateReadCoordinator retain the authoritative read error.
            }
            playerClock.update(actions?.currentSnapshot() ?: runtime.snapshots.state.value)
            clockPosition = playerClock.positionSeconds
        }
    }
    DisposableEffect(lifecycleOwner, playerClock, runtime) {
        var backgrounded = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    backgrounded = true
                    playerClock.setVisible(false)
                    clockPosition = playerClock.positionSeconds
                }
                Lifecycle.Event.ON_START -> if (backgrounded) {
                    backgrounded = false
                    playerClock.setVisible(true)
                    lifecycleScope.launch {
                        runtime.refreshSnapshot()
                        playerClock.completeForegroundRead(actions?.currentSnapshot() ?: runtime.snapshots.state.value)
                        // If the coalesced request had begun before foregrounding, its timing is
                        // intentionally rejected by PlayerClock. Start one post-foreground read.
                        if (playerClock.isAwaitingForegroundRead) {
                            runtime.refreshSnapshot()
                            playerClock.completeForegroundRead(actions?.currentSnapshot() ?: runtime.snapshots.state.value)
                        }
                        clockPosition = playerClock.positionSeconds
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The slider preview is local presentation only. The base position always comes from a fresh,
    // target-bound server observation and is never written into SnapshotStore.
    val displayedPosition = actionState.displayPositionSeconds ?:
        if (mediaHandoffActive) mediaHandoffPositionSeconds else clockPosition
    val observed = PlayerFacts.from(clockSnapshot).playbackObservation
    val displayedDuration = if (mediaHandoffActive) {
        media.song?.durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    } else observed?.durationSeconds?.takeIf {
        presentation.playbackStatus != ConfirmedPlaybackStatus.UNKNOWN &&
            clockSnapshot.controlsEligible && it.isFinite() && it > 0.0
    }
    var showOutputDetails by remember { mutableStateOf(false) }
    // Never let a previously cached track survive a canonical identity/connection change.
    // Subscribe only inside this screen so background preload state changes never tear down or
    // replace the root NavHost before a delayed mini-player open completes.
    val artworkCacheState = artworkCache?.state?.collectAsStateWithLifecycle()?.value
        ?: PlayerArtworkCacheState.Empty
    val currentArtworkKey = artworkCacheKey?.takeIf { it.matches(media) }
    val currentArtworkState = artworkCacheState.takeIf {
        currentArtworkKey != null && it.key == currentArtworkKey
    }
    val artworkBitmap = (currentArtworkState as? PlayerArtworkCacheState.Ready)?.foreground
    val blurredArtworkBitmap = (currentArtworkState as? PlayerArtworkCacheState.Ready)?.blurred
    val artworkLoading = currentArtworkKey != null &&
        currentArtworkState !is PlayerArtworkCacheState.Ready &&
        currentArtworkState !is PlayerArtworkCacheState.Failed
    var showLyrics by remember { mutableStateOf(false) }
    val lyricsPlaybackConfirmed = !mediaHandoffActive &&
        presentation.playbackStatus != ConfirmedPlaybackStatus.UNKNOWN &&
        presentation.currentOccurrenceId != null &&
        presentation.currentOccurrenceId == media.currentOccurrenceId &&
        clockSnapshot.controlsEligible
    val lyricsPositionSeconds = clockPosition.takeIf {
        lyricsPlaybackConfirmed && it != null && it.isFinite() && it >= 0.0
    }
    val lyricsPlaybackObservation = PlayerFacts.from(clockSnapshot).playbackObservation
    val lyricsDurationSeconds = lyricsPlaybackObservation?.durationSeconds?.takeIf {
        lyricsPlaybackConfirmed && it.isFinite() && it > 0.0
    }
    val lyricsControlTarget = lyricsPlaybackObservation?.controlTarget
    val lyricsResourceIdentity = listOf(
        snapshotState.snapshot?.epoch.orEmpty(),
        snapshotState.connectionGeneration.toString(),
        presentation.currentOccurrenceId.orEmpty(),
        media.currentOccurrenceId.orEmpty(),
        lyricsControlTarget?.queueItemId.orEmpty(),
        lyricsControlTarget?.token.orEmpty(),
        media.song?.songId.orEmpty(),
        media.song?.fileUri.orEmpty(),
        media.song?.lyrics.orEmpty(),
        media.song?.lyricsFormat.orEmpty(),
        media.song?.lyricsSource.orEmpty(),
        media.song?.rawLyricsStatus.orEmpty(),
    ).joinToString(separator = "") { it.length.toString() + ":" + it }

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
                    if (showLyrics) {
                        LyricsStage(
                            song = media.song,
                            resourceIdentity = lyricsResourceIdentity,
                            positionSeconds = lyricsPositionSeconds,
                            durationSeconds = lyricsDurationSeconds,
                            seekEnabled = lyricsPlaybackConfirmed &&
                                actions?.isEnabled(PlayerAction.SEEK) == true,
                            onSeek = { seconds -> actions?.perform(PlayerAction.SEEK, seconds) },
                            onShowArtwork = { showLyrics = false },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            val placeholder = when {
                                media.playbackStatus == ConfirmedPlaybackStatus.UNKNOWN ->
                                    "当前曲目未确认"
                                media.artworkReferenceStatus == ArtworkReferenceStatus.MISSING -> "无封面"
                                media.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED && artworkLoading ->
                                    "正在读取封面"
                                media.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED && artworkBitmap == null ->
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
                                onTap = { showLyrics = true },
                            )
                        }

                        PlayerSongIdentity(
                            presentation = media,
                            onArtistClick = onArtistClick,
                            onEntityNavigationRequest = onEntityNavigationRequest,
                        )
                    }
                }
            }

            PlayerProgressControl(
                presentation = media,
                positionSeconds = displayedPosition,
                durationSeconds = displayedDuration,
                actions = actions,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            PlaybackControls(presentation = presentation, actions = actions)
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
private fun PlayerProgressControl(
    presentation: PlayerPresentation,
    positionSeconds: Double?,
    durationSeconds: Double?,
    actions: PlayerActions?,
    modifier: Modifier = Modifier,
) {
    val actionState by if (actions != null) {
        actions.state.collectAsState()
    } else {
        remember { mutableStateOf(PlayerActionsState()) }
    }
    val validDuration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    val validPosition = positionSeconds?.takeIf { it.isFinite() && it >= 0.0 }
    val progress = if (validDuration != null && validPosition != null) {
        (validPosition / validDuration).coerceIn(0.0, 1.0).toFloat()
    } else 0f
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    LaunchedEffect(interactions, actions) {
        interactions.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Cancel) {
                actions?.cancelSeekDraft()
            }
        }
    }
    val seekEnabled = actions?.isEnabled(PlayerAction.SEEK) == true && validDuration != null
    Column(modifier = modifier.fillMaxWidth()) {
        // The visible track is independent of Material3's default slider artwork; the transparent
        // Slider remains the sole accessible touch/seek surface and uses the shared PlayerActions.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f))
                    .testTag("player-progress-track"),
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.96f))
                            .testTag("player-progress-fill"),
                    )
                }
            }
            Slider(
                value = progress,
                onValueChange = { fraction ->
                    if (actions?.state?.value?.seekDraftSeconds == null) actions?.beginSeek()
                    if (validDuration != null) actions?.previewSeek(fraction * validDuration)
                },
                onValueChangeFinished = { actions?.finishSeek() },
                enabled = seekEnabled,
                valueRange = 0f..1f,
                interactionSource = interactions,
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("player-progress-slider")
                    .semantics {
                        contentDescription = "播放进度"
                    },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .testTag("player-progress-readout"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(validPosition),
                modifier = Modifier.testTag("player-elapsed-time"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceFormatLabel(
                presentation = presentation,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatPlayerRemainingTime(validPosition, validDuration),
                modifier = Modifier.testTag("player-remaining-time"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val status = when {
            actionState.unknown -> "操作结果未知 · 可从应用状态环查看并重试原操作"
            actionState.failed -> actionState.message?.let { "操作失败 · $it" } ?: "操作失败"
            actionState.readFailed -> "操作已确认 · 状态同步失败"
            actionState.syncing -> "操作已确认 · 等待新的播放观察"
            actionState.pending -> actionState.action?.let(::playerActionPendingText) ?: "操作进行中"
            else -> ""
        }
        Text(
            text = status,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 0.dp)
                .testTag("player-action-status"),
            style = MaterialTheme.typography.labelSmall,
            color = if (actionState.unknown || actionState.failed || actionState.readFailed) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
private fun formatPlayerTime(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0.0) return "--:--"
    val whole = seconds.toLong()
    return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
}

private fun formatPlayerRemainingTime(positionSeconds: Double?, durationSeconds: Double?): String {
    val position = positionSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return "--:--"
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return "--:--"
    return "-${formatPlayerTime((duration - position).coerceAtLeast(0.0))}"
}

private fun playerActionPendingText(action: PlayerAction): String = when (action) {
    PlayerAction.PAUSE -> "正在暂停"
    PlayerAction.RESUME -> "正在继续播放"
    PlayerAction.PREVIOUS -> "正在切换上一首"
    PlayerAction.NEXT -> "正在切换下一首"
    PlayerAction.SEEK -> "正在调整进度"
}

@Composable
private fun SourceFormatLabel(
    presentation: PlayerPresentation,
    modifier: Modifier = Modifier,
) {
    val format = presentation.sourceFormat
    val label = buildList {
        format.codec?.takeIf { it.isNotBlank() }?.let(::add)
        format.bitDepth?.takeIf { it > 0 }?.let { add("$it bit") }
        format.sampleRateHz?.takeIf { it > 0 }?.let { rate ->
            if (rate % 1000 == 0) add(rate.div(1000).toString() + " kHz")
            else add(String.format(java.util.Locale.ROOT, "%.1f kHz", rate / 1000.0))
        }
        format.channelCount?.takeIf { it > 0 }?.let { add("$it ch") }
    }.joinToString(" · ").ifBlank { "源文件格式未知" }

    Box(
        modifier = modifier.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("player-source-format-pill"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                modifier = Modifier.testTag("player-source-format"),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun PlaybackControls(
    presentation: PlayerPresentation,
    actions: PlayerActions?,
) {
    // Explicitly subscribe here: enabled state must recompose immediately when the shared
    // coordinator reports a pending/unknown operation, even if playback snapshot did not change.
    val actionState by if (actions != null) {
        actions.state.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(PlayerActionsState()) }
    }
    val actionLocked = actionState.locked
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
            enabled = !actionLocked && actions?.isEnabled(PlayerAction.PREVIOUS) == true,
            onClick = { actions?.perform(PlayerAction.PREVIOUS) },
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
        val primaryAction = when (presentation.playbackStatus) {
            ConfirmedPlaybackStatus.PLAYING -> PlayerAction.PAUSE
            ConfirmedPlaybackStatus.PAUSED -> PlayerAction.RESUME
            ConfirmedPlaybackStatus.UNKNOWN -> null
        }
        IconButton(
            enabled = !actionLocked && primaryAction != null && actions?.isEnabled(primaryAction) == true,
            onClick = { primaryAction?.let { actions?.perform(it) } },
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
            enabled = !actionLocked && actions?.isEnabled(PlayerAction.NEXT) == true,
            onClick = { actions?.perform(PlayerAction.NEXT) },
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
        containerColor = dialogSurfaceColor(),
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
