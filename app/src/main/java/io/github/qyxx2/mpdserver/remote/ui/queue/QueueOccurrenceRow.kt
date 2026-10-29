package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
/** Keep the selected row's existing hue/lightness and opacity; strengthen only its saturation. */
internal fun queueOccurrenceBackgroundColor(isCurrent: Boolean, primary: Color): Color {
    if (!isCurrent) return Color.Transparent
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(primary.toArgb(), hsl)
    hsl[1] = (hsl[1] * 1.12f).coerceAtMost(0.92f)
    return Color(ColorUtils.HSLToColor(hsl)).copy(alpha = 0.10f)
}

/** A deliberately subtle white veil distinguishes Played history without creating opaque cards. */
internal fun queuePlayedOverlayColor(isPlayed: Boolean): Color =
    if (isPlayed) Color.White.copy(alpha = 0.045f) else Color.Transparent

@Composable
internal fun QueueOccurrenceRow(
    occurrence: QueueItemDto,
    isCurrent: Boolean,
    currentLabel: String,
    cacheEntry: QueueSongCacheEntry?,
    menuEnabled: Boolean,
    menuTitle: String,
    menuExpanded: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    reorderEnabled: Boolean = false,
    isDragSource: Boolean = false,
    isDropTarget: Boolean = false,
    dropAtEnd: Boolean = false,
    onReorderStart: () -> Unit = {},
    onReorderDelta: (Float) -> Unit = {},
    onReorderEnd: () -> Unit = {},
    onReorderCancel: () -> Unit = {},
) {
    val song = cacheEntry?.takeIf { it.identity.epoch.isNotBlank() }?.song
    val background = queueOccurrenceBackgroundColor(
        isCurrent = isCurrent,
        primary = MaterialTheme.colorScheme.primary,
    )
    val insertionLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 84.dp)
            .background(background)
            .then(
                if (occurrence.position < 0) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(queuePlayedOverlayColor(isPlayed = true))
                    }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                if (isDropTarget) {
                    val stroke = 2.dp.toPx()
                    val y = if (dropAtEnd) size.height - stroke / 2f else stroke / 2f
                    drawLine(
                        color = insertionLineColor,
                        start = Offset(8.dp.toPx(), y),
                        end = Offset(size.width - 8.dp.toPx(), y),
                        strokeWidth = stroke,
                    )
                }
            }
            // A graphics layer for every non-dragging row adds layout/render-node work without
            // changing its appearance. Allocate one only for the actively dragged occurrence.
            .then(if (isDragSource) Modifier.graphicsLayer { alpha = 0.76f } else Modifier)
            // Merge pointer handling and accessibility click semantics into one modifier node;
            // keep the previous no-ripple row tap behavior.
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "播放队列条目",
                role = Role.Button,
                onClick = onPlay,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("queue-row-${occurrence.queueItemId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueDragHandle(
            enabled = reorderEnabled && occurrence.position > 0,
            onDragStart = onReorderStart,
            onDragDelta = onReorderDelta,
            onDragEnd = onReorderEnd,
            onDragCancel = onReorderCancel,
            modifier = Modifier.size(width = 28.dp, height = 40.dp)
                .testTag("queue-row-drag-handle-${occurrence.queueItemId}"),
        )
        Spacer(Modifier.width(8.dp))
        QueueRowArtwork(
            entry = cacheEntry,
            modifier = Modifier.size(54.dp).testTag("queue-row-artwork-${occurrence.queueItemId}"),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            if (isCurrent) {
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (currentLabel == "业务当前 · 实际播放未确认") {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("queue-row-current-label-${occurrence.queueItemId}"),
                )
            }
            Text(
                text = song?.title?.takeIf { it.isNotBlank() }
                    ?: if (cacheEntry?.status == QueueSongLoadStatus.FAILED) "曲目信息不可用" else "正在读取曲目信息…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("queue-row-title-${occurrence.queueItemId}"),
            )
            val artists = song?.artists.orEmpty().filter(String::isNotBlank).joinToString(" / ")
            val album = song?.album.orEmpty()
            val subline = listOf(artists, album).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                text = subline.ifBlank {
                    if (cacheEntry?.status == QueueSongLoadStatus.FAILED) "无法读取艺术家或专辑信息" else " "
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,

            )
            val format = listOfNotNull(
                song?.codec?.takeIf { it.isNotBlank() },
                song?.bitDepth?.takeIf { it > 0 }?.let { "$it bit" },
                song?.sampleRateHz?.takeIf { it > 0 }?.let { formatSampleRate(it) },
            ).joinToString(" · ")
            // Keep the format line allocated even while metadata is loading or unavailable;
            // replacing a placeholder with codec details must never change the measured row height.
            val availability = song?.availabilityStatus?.name?.takeIf { it in setOf("MISSING", "UNREADABLE") }
            Text(
                text = format.ifBlank { availability ?: " " },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,

            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            IconButton(
                enabled = menuEnabled,
                onClick = onMenu,
                modifier = Modifier.size(48.dp).testTag("queue-row-menu-${occurrence.queueItemId}"),
            ) {
                QueueMoreGlyph()
            }
            QueueRowMenu(
                item = occurrence.takeIf { menuExpanded },
                title = menuTitle,
                enabled = menuEnabled,
                expanded = menuExpanded,
                onDismiss = onDismissMenu,
                onMoveNext = onMoveNext,
                onMoveToEnd = onMoveToEnd,
                onRemove = onRemove,
            )
        }
    }
}

private fun formatSampleRate(rate: Int): String =
    if (rate % 1000 == 0) "${rate / 1000} kHz" else "${rate} Hz"

@Composable
private fun QueueDragHandle(
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.78f else 0.28f)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val dragGestureModifier = if (enabled) {
        Modifier.pointerInput(enabled) {
            detectDragGesturesAfterLongPress(
                onDragStart = { currentOnDragStart() },
                onDragEnd = { currentOnDragEnd() },
                onDragCancel = { currentOnDragCancel() },
            ) { change, dragAmount ->
                change.consume()
                currentOnDragDelta(dragAmount.y)
            }
        }
    } else {
        Modifier
    }
    Canvas(
        modifier = modifier.then(dragGestureModifier),
    ) {
        val dotRadius = 1.6.dp.toPx()
        val centerX = size.width / 2f
        val gapX = 6.dp.toPx()
        val startY = (size.height - 2 * 7.dp.toPx()) / 2f
        for (row in 0..2) {
            for (column in 0..1) {
                drawCircle(
                    color = tint,
                    radius = dotRadius,
                    center = Offset(centerX + (column - 0.5f) * gapX, startY + row * 7.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun QueueMoreGlyph() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val radius = 1.7.dp.toPx()
            repeat(3) { index ->
                drawCircle(
                    color = tint,
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * (0.2f + index * 0.3f)),
                )
            }
        }
    }
}

@Composable
private fun QueueRowArtwork(
    entry: QueueSongCacheEntry?,
    modifier: Modifier = Modifier,
) {
    val readyEntry = entry?.takeIf { it.status == QueueSongLoadStatus.READY && it.song?.artwork != null }
    val thumbnailKey = readyEntry?.let {
        QueueArtworkThumbnailKey(it.identity, it.song!!.songId.orEmpty())
    }?.takeIf { it.songId.isNotBlank() }
    val bytes = readyEntry?.takeIf { it.artworkStatus == QueueArtworkStatus.AVAILABLE }?.artworkBytes
    LaunchedEffect(thumbnailKey, bytes) {
        if (thumbnailKey != null && bytes != null) {
            QueueArtworkThumbnailCache.preload(thumbnailKey, bytes)
        }
    }
    val thumbnailState = remember(thumbnailKey) {
        thumbnailKey?.let(QueueArtworkThumbnailCache::stateFor)
    }
    // The decoded image is a shared LRU value. Recycling a row or reopening Queue reuses it
    // instead of decoding the same compressed artwork on every composition.
    val bitmap = thumbnailState?.collectAsStateWithLifecycle()?.value

    Box(
        modifier = modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "专辑封面",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "♪",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
