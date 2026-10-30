package io.github.qyxx2.mpdserver.remote.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed-height mini-player. Taps open Player; upward drags pull the full Player surface out of this row. */
@Composable
fun MiniPlayer(
    presentation: MiniPlayerPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChanged: (topPx: Float, heightPx: Float) -> Unit = { _, _ -> },
    onExpandDragDistanceChanged: (distancePx: Float) -> Unit = {},
    onExpandDragEnd: (distancePx: Float) -> Unit = {},
    onExpandDragCancel: () -> Unit = {},
) {
    val currentOnOpen = rememberUpdatedState(onOpen)
    val currentOnDragDistanceChanged = rememberUpdatedState(onExpandDragDistanceChanged)
    val currentOnDragEnd = rememberUpdatedState(onExpandDragEnd)
    val currentOnDragCancel = rememberUpdatedState(onExpandDragCancel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(
                    coordinates.positionInRoot().y,
                    coordinates.size.height.toFloat(),
                )
            }
            .testTag("mini-player"),
        verticalArrangement = Arrangement.Top,
    ) {
        Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
        Row(
            modifier = Modifier.fillMaxWidth().height(63.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val touchSlop = viewConfiguration.touchSlop
                            var lastPosition = down.position
                            var totalX = 0f
                            var totalY = 0f
                            var dragging = false
                            var finished = false
                            try {
                                while (!finished) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val delta = change.position - lastPosition
                                    lastPosition = change.position
                                    totalX += delta.x
                                    totalY += delta.y

                                    if (!dragging &&
                                        totalY < -touchSlop &&
                                        kotlin.math.abs(totalY) > kotlin.math.abs(totalX)
                                    ) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        currentOnDragDistanceChanged.value((-totalY).coerceAtLeast(0f))
                                        change.consume()
                                    }

                                    if (!change.pressed) {
                                        finished = true
                                        if (dragging) {
                                            currentOnDragEnd.value((-totalY).coerceAtLeast(0f))
                                        } else if (
                                            totalX * totalX + totalY * totalY < touchSlop * touchSlop
                                        ) {
                                            currentOnOpen.value()
                                        }
                                    }
                                }
                            } finally {
                                if (dragging && !finished) currentOnDragCancel.value()
                            }
                        }
                    }
                    .semantics {
                        onClick(label = "打开全屏播放器") {
                            currentOnOpen.value()
                            true
                        }
                    }
                    .testTag("mini-player-body"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp).testTag("mini-player-artwork"),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 25.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = presentation.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mini-player-title")
                            .semantics { contentDescription = presentation.title },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    presentation.statusLabel?.let { label ->
                        Text(
                            text = label,
                            modifier = Modifier.testTag("mini-player-status"),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("mini-player-play-pause")
                    .semantics { contentDescription = "播放/暂停（暂未接入）" },
            ) {
                Text(
                    text = if (presentation.isPlaying) "Ⅱ" else "▶",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 21.sp,
                )
            }
        }
    }
}
