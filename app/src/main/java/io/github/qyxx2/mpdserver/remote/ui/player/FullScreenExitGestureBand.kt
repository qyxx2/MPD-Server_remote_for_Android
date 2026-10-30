package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * This band starts after AppNavHost has applied Android's actual safe insets. It never occupies the
 * system status-bar/cutout area, and no gesture handler is installed on Queue or player controls.
 */
@Composable
fun FullScreenExitGestureBand(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onDragDelta: (Float) -> Unit = {},
    onGestureEnd: ((Float) -> Unit)? = null,
    onGestureCancel: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val thresholdPx = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(onExit, thresholdPx) {
                var startX = 0f
                var horizontalDistance = 0f
                var verticalDistance = 0f
                detectVerticalDragGestures(
                    onDragStart = { start ->
                        startX = start.x
                        horizontalDistance = 0f
                        verticalDistance = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        horizontalDistance = change.position.x - startX
                        verticalDistance += dragAmount
                        onDragDelta(dragAmount)
                        change.consume()
                    },
                    onDragEnd = {
                        val end = onGestureEnd
                        if (end != null) {
                            end(verticalDistance)
                        } else if (FullScreenExitGesturePolicy.shouldExit(
                                startedInTopBand = true,
                                deltaX = horizontalDistance,
                                deltaY = verticalDistance,
                                thresholdPx = thresholdPx,
                            )
                        ) {
                            onExit()
                        }
                    },
                    onDragCancel = {
                        horizontalDistance = 0f
                        verticalDistance = 0f
                        onGestureCancel()
                    },
                )
            }
            .testTag("full-screen-exit-gesture-band"),
        content = content,
    )
}
