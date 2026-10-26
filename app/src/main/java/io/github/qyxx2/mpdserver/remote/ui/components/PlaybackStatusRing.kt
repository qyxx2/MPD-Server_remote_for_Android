package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackStatusRing(
    presentation: ActivityPresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = presentation.visualState in setOf(ActivityVisualState.PENDING, ActivityVisualState.SYNCING)
    val color = when (presentation.visualState) {
        ActivityVisualState.UNKNOWN, ActivityVisualState.FAILED -> MaterialTheme.colorScheme.error
        ActivityVisualState.PENDING, ActivityVisualState.SYNCING -> MaterialTheme.colorScheme.primary
        ActivityVisualState.DISCONNECTED, ActivityVisualState.CONNECTING, ActivityVisualState.STALE -> MaterialTheme.colorScheme.onSurfaceVariant
        ActivityVisualState.SUCCESS, ActivityVisualState.INFO, ActivityVisualState.IDLE -> MaterialTheme.colorScheme.outline
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).clip(CircleShape).testTag("global-status-entry")
            .semantics { contentDescription = "连接与活动状态：${presentation.statusLabel}" },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
            if (pending) {
                AnimatedStatusRingGraphic(presentation = presentation, color = color)
            } else {
                StatusRingGraphic(presentation = presentation, color = color, rotation = 0f)
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
}

@Composable
private fun AnimatedStatusRingGraphic(presentation: ActivityPresentation, color: Color) {
    val transition = rememberInfiniteTransition(label = "status-ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "status-ring-rotation",
    )
    StatusRingGraphic(presentation = presentation, color = color, rotation = rotation)
}

@Composable
private fun StatusRingGraphic(presentation: ActivityPresentation, color: Color, rotation: Float) {
    Canvas(Modifier.size(36.dp).testTag("status-ring-visual")) {
        val stroke = 3.dp.toPx()
        drawCircle(color = color.copy(alpha = 0.24f), style = Stroke(width = stroke))
        val pending = presentation.visualState in setOf(ActivityVisualState.PENDING, ActivityVisualState.SYNCING)
        val start = if (pending) rotation else -90f
        val sweep = when (presentation.visualState) {
            ActivityVisualState.UNKNOWN, ActivityVisualState.DISCONNECTED, ActivityVisualState.CONNECTING, ActivityVisualState.STALE -> 245f
            ActivityVisualState.FAILED -> 285f
            ActivityVisualState.PENDING, ActivityVisualState.SYNCING -> 100f
            else -> 360f
        }
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
