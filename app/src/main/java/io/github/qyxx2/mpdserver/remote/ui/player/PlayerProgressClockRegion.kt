package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the progress bar visually live without recomposing its Slider and time labels every 100ms.
 *
 * The precise fraction is exposed as Compose state for draw-phase reads only. The position used by
 * Text and Slider advances when the whole second changes, or immediately during a seek preview.
 */
@Composable
internal fun PlayerProgressClockRegion(
    clockPosition: StateFlow<Double?>?,
    durationSeconds: Double?,
    fallbackPositionSeconds: Double?,
    previewPositionSeconds: Double?,
    content: @Composable (readoutPositionSeconds: Double?, drawProgress: State<Float>) -> Unit,
) {
    // A null fallback is safe for the first frame: StateFlow.collect emits its current value
    // immediately in the effect, without making this Composable observe every clock tick.
    val initialPosition = previewPositionSeconds ?: fallbackPositionSeconds
    val readoutPosition = androidx.compose.runtime.remember(
        clockPosition,
        durationSeconds,
    ) { mutableStateOf(initialPosition) }
    val drawProgress = androidx.compose.runtime.remember(
        clockPosition,
        durationSeconds,
    ) { mutableFloatStateOf(progressFraction(initialPosition, durationSeconds)) }

    LaunchedEffect(
        clockPosition,
        durationSeconds,
        fallbackPositionSeconds,
        previewPositionSeconds,
    ) {
        if (previewPositionSeconds != null || clockPosition == null) {
            val fixedPosition = previewPositionSeconds ?: fallbackPositionSeconds
            readoutPosition.value = fixedPosition
            drawProgress.floatValue = progressFraction(fixedPosition, durationSeconds)
            return@LaunchedEffect
        }

        var displayedWholeSecond = readoutPosition.value?.toLong()
        var firstSample = true
        clockPosition.collect { position ->
            drawProgress.floatValue = progressFraction(position, durationSeconds)
            val wholeSecond = position?.toLong()
            if (firstSample || wholeSecond != displayedWholeSecond) {
                readoutPosition.value = position
                displayedWholeSecond = wholeSecond
                firstSample = false
            }
        }
    }

    content(readoutPosition.value, drawProgress)
}

private fun progressFraction(positionSeconds: Double?, durationSeconds: Double?): Float {
    val position = positionSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return 0f
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return 0f
    return (position / duration).coerceIn(0.0, 1.0).toFloat()
}
