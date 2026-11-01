package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import kotlin.math.roundToInt

/**
 * Window-local, non-clickable visual. Interaction belongs to the separate in-app activity entry,
 * so drawing around a camera cutout never steals status-bar taps or pull-down gestures.
 */
@Composable
fun GlobalStatusRingOverlay(
    presentation: ActivityPresentation,
    geometry: StatusRingWindowGeometry,
    preferences: StatusRingPreferences = StatusRingPreferences.DEFAULT,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.clipToBounds().testTag("status-ring-overlay"),
    ) {
        val density = LocalDensity.current
        val placement = calculateWindowPlacement(
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight,
            geometry = geometry,
            density = density.density,
            preferences = preferences,
        )
        val diameter = with(density) { placement.diameterPx.toDp() }
        val ringLabel = "状态环：${presentation.statusLabel}；定位方式：${
            if (placement.anchorSource == StatusRingAnchorSource.DISPLAY_CUTOUT) "系统挖孔几何" else "手动校准回退"
        }"
        StatusRingVisual(
            presentation = presentation,
            lineWidthDp = preferences.lineWidthDp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (placement.centerXpx - placement.diameterPx / 2f).roundToInt(),
                        (placement.centerYpx - placement.diameterPx / 2f).roundToInt(),
                    )
                }
                .size(diameter)
                .semantics { contentDescription = ringLabel }
                .testTag("status-ring-visual"),
        )
    }
}

/**
 * Draft-only, non-interactive preview rendered in physical window coordinates. It calls the exact
 * placement and visual pipeline used by GlobalStatusRingOverlay; the INFO state is local UI data.
 */
@Composable
fun StatusRingDraftPreviewOverlay(
    geometry: StatusRingWindowGeometry,
    preferences: StatusRingPreferences,
    modifier: Modifier = Modifier,
) {
    val previewPresentation = androidx.compose.runtime.remember {
        ActivityPresentation(
            visualState = ActivityVisualState.INFO,
            statusLabel = "预览示意",
            connectionLabel = "预览",
            freshnessLabel = "预览",
            readError = null,
            activities = emptyList(),
            isConfirmedAvailable = false,
        )
    }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().clipToBounds().testTag("status-ring-draft-preview"),
    ) {
        val density = LocalDensity.current
        val placement = calculateWindowPlacement(
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight,
            geometry = geometry,
            density = density.density,
            preferences = preferences,
        )
        val diameter = with(density) { placement.diameterPx.toDp() }
        val placementLabel = "状态环预览；中心 ${placement.centerXpx.toInt()},${placement.centerYpx.toInt()}；直径 ${placement.diameterPx.toInt()}px"
        StatusRingVisual(
            presentation = previewPresentation,
            lineWidthDp = preferences.lineWidthDp,
            modifier = Modifier
                .offset {
                    IntOffset(
                        (placement.centerXpx - placement.diameterPx / 2f).roundToInt(),
                        (placement.centerYpx - placement.diameterPx / 2f).roundToInt(),
                    )
                }
                .size(diameter)
                .semantics { contentDescription = placementLabel }
                .testTag("status-ring-draft-preview-visual"),
        )
    }
}

private fun calculateWindowPlacement(
    widthPx: Int,
    heightPx: Int,
    geometry: StatusRingWindowGeometry,
    density: Float,
    preferences: StatusRingPreferences,
) = StatusRingPlacementCalculator.calculate(
    screenWidthPx = widthPx,
    screenHeightPx = heightPx,
    safeInsets = geometry.safeInsets,
    cutoutBounds = geometry.cutoutBounds,
    density = density,
    settings = preferences.toPlacementSettings(),
)

private fun StatusRingPreferences.toPlacementSettings() = StatusRingPlacementSettings(
    sizeScale = sizeScale,
    horizontalOffsetDp = horizontalOffsetDp,
    verticalOffsetDp = verticalOffsetDp,
)

@Composable
private fun StatusRingVisual(
    presentation: ActivityPresentation,
    lineWidthDp: Float,
    modifier: Modifier,
) {
    val pending = presentation.visualState == ActivityVisualState.PENDING ||
        presentation.visualState == ActivityVisualState.SYNCING
    val color = when (presentation.visualState) {
        ActivityVisualState.UNKNOWN, ActivityVisualState.FAILED -> MaterialTheme.colorScheme.error
        ActivityVisualState.PENDING, ActivityVisualState.SYNCING -> MaterialTheme.colorScheme.primary
        ActivityVisualState.DISCONNECTED, ActivityVisualState.CONNECTING, ActivityVisualState.STALE ->
            MaterialTheme.colorScheme.onSurfaceVariant
        ActivityVisualState.SUCCESS, ActivityVisualState.INFO, ActivityVisualState.IDLE ->
            MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            AnimatedStatusRingGraphic(presentation = presentation, color = color, lineWidthDp = lineWidthDp)
        } else {
            StatusRingGraphic(presentation = presentation, color = color, rotation = 0f, lineWidthDp = lineWidthDp)
        }
        Text(
            text = when (presentation.visualState) {
                ActivityVisualState.UNKNOWN -> "?"
                ActivityVisualState.FAILED -> "!"
                ActivityVisualState.PENDING, ActivityVisualState.SYNCING -> "…"
                ActivityVisualState.DISCONNECTED -> "×"
                ActivityVisualState.CONNECTING -> "↻"
                ActivityVisualState.STALE -> "–"
                ActivityVisualState.SUCCESS -> "✓"
                ActivityVisualState.INFO -> "i"
                ActivityVisualState.IDLE -> "·"
            },
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AnimatedStatusRingGraphic(
    presentation: ActivityPresentation,
    color: Color,
    lineWidthDp: Float,
) {
    val transition = rememberInfiniteTransition(label = "status-ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "status-ring-rotation",
    )
    StatusRingGraphic(presentation = presentation, color = color, rotation = rotation, lineWidthDp = lineWidthDp)
}

@Composable
private fun StatusRingGraphic(
    presentation: ActivityPresentation,
    color: Color,
    rotation: Float,
    lineWidthDp: Float,
) {
    Canvas(Modifier.fillMaxSize().testTag("status-ring-graphic")) {
        val stroke = lineWidthDp.dp.toPx()
        drawCircle(
            color = color.copy(alpha = 0.24f),
            radius = size.minDimension / 2f - stroke / 2f,
            style = Stroke(width = stroke),
        )
        val pending = presentation.visualState == ActivityVisualState.PENDING ||
            presentation.visualState == ActivityVisualState.SYNCING
        val start = if (pending) rotation else -90f
        val sweep = when (presentation.visualState) {
            ActivityVisualState.UNKNOWN, ActivityVisualState.DISCONNECTED, ActivityVisualState.CONNECTING,
            ActivityVisualState.STALE -> 245f
            ActivityVisualState.FAILED -> 285f
            ActivityVisualState.PENDING, ActivityVisualState.SYNCING -> 100f
            else -> 360f
        }
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
